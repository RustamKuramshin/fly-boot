package dev.flyboot.brain;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Whole-brain leaky integrate-and-fire simulation, a Java port of the Brian2 model of Shiu et al. 2024:
 * <pre>
 *   dv/dt = (v_0 - v + g) / t_mbr   (unless refractory)
 *   dg/dt = -g / tau                (unless refractory)
 *   v > v_th: spike, v = v_rst, g = 0;  presynaptic spike: g_post += w after t_dly
 * </pre>
 * Integration is exact (Brian2 method 'linear'). One step follows the Brian2 schedule:
 * state update, threshold, synaptic delivery and Poisson input, reset. As in Brian2, variables marked
 * "unless refractory" are conditionally written: synaptic and Poisson input reaching a neuron from the step it
 * spikes until the end of its refractory period is dropped.
 * <p>
 * Almost the whole brain is at rest at any moment, so only neurons away from rest are kept in an active list
 * (see {@link LifParams#restEpsilonMv()}); spikes are propagated event by event along CSR rows.
 * The cost is dominated by random access on synaptic delivery, so the hot per-neuron state is packed into one
 * {@code int[]} record of 16 bytes: u = v - v_0 and g as float bits (small numbers keep float precision),
 * the input gate with the active flag, the update gate.
 * <p>
 * The graph is shared and read-only; an instance holds the mutable state of one fly and is not thread-safe.
 */
public final class LifSimulation {

    private static final int STRIDE = 4;
    /** u = v - v_0, the deviation from rest. */
    private static final int U = 0;
    private static final int G = 1;
    /** First step at which input written to v or g is accepted (Brian2 conditional write), plus the ACTIVE bit. */
    private static final int INPUT = 2;
    /** First step at which the neuron integrates again (Brian2 not_refractory in the "groups" slot). */
    private static final int UPDATE = 3;
    private static final int ACTIVE = 1 << 30;
    private static final int STEP_MASK = ACTIVE - 1;

    private final Connectome connectome;
    private final LifParams params;
    private final int n;
    private final int[] rowPtr;
    private final int[] post;
    private final short[] weight;

    private final double vRest;
    private final float uReset;
    private final float uThreshold;
    private final float decayV;
    private final float decayG;
    private final float couplingGtoV;
    private final float wSyn;
    private final float poissonKick;
    private final float epsilon;
    private final boolean dense;
    private final int delaySteps;
    private final int refractorySteps;

    private final int[] state;
    private final int[] neuronRefractory;
    private final int[] spikeCount;
    private int[] activeList;
    private int activeSize;

    /** Spikes emitted during the last delaySteps + 1 steps, slot = step % (delaySteps + 1). */
    private final int[][] ring;
    private final int[] ringSize;

    private Stimulus stimulus = Stimulus.NONE;
    private double[] stimulusLogMiss = new double[0];
    private int[] stimulusNextStep = new int[0];
    private SplittableRandom random = new SplittableRandom(0);
    private int step;
    private long totalSpikes;
    private long neuronUpdates;
    private long synapticEvents;

    public LifSimulation(Connectome connectome, LifParams params) {
        this.connectome = connectome;
        this.params = params;
        this.n = connectome.neuronCount();
        this.rowPtr = connectome.rowPtr();
        this.post = connectome.post();
        this.weight = connectome.weight();

        double dt = params.dtMs();
        double tm = params.tauMembraneMs();
        double ts = params.tauSynapseMs();
        this.vRest = params.vRestMv();
        this.uReset = (float) (params.vResetMv() - vRest);
        this.uThreshold = (float) (params.vThresholdMv() - vRest);
        this.decayV = (float) Math.exp(-dt / tm);
        this.decayG = (float) Math.exp(-dt / ts);
        // Exact solution of du/dt = (g - u) / tm with g decaying at ts: u' = u*decayV + g*ts/(ts-tm)*(decayG-decayV)
        this.couplingGtoV = (float) (ts / (ts - tm) * (Math.exp(-dt / ts) - Math.exp(-dt / tm)));
        this.wSyn = (float) params.wSynMv();
        this.poissonKick = (float) params.poissonKickMv();
        this.epsilon = (float) params.restEpsilonMv();
        this.dense = epsilon <= 0;
        this.delaySteps = params.steps(params.delayMs());
        this.refractorySteps = params.steps(params.refractoryMs());

        this.state = new int[n * STRIDE];
        this.neuronRefractory = new int[n];
        this.spikeCount = new int[n];
        this.activeList = new int[dense ? n : 1024];
        this.ring = new int[delaySteps + 1][64];
        this.ringSize = new int[delaySteps + 1];
        reset(0);
    }

    public Connectome connectome() {
        return connectome;
    }

    public LifParams params() {
        return params;
    }

    /** Returns the fly to rest: all neurons at v_0, no pending spikes, counters cleared. */
    public void reset(long seed) {
        for (int b = 0; b < state.length; b += STRIDE) {
            state[b + U] = 0;
            state[b + G] = 0;
            state[b + INPUT] = dense ? ACTIVE : 0;
            state[b + UPDATE] = 0;
        }
        Arrays.fill(neuronRefractory, refractorySteps);
        Arrays.fill(spikeCount, 0);
        Arrays.fill(ringSize, 0);
        activeSize = 0;
        if (dense) {
            for (int i = 0; i < n; i++) {
                activeList[i] = i;
            }
            activeSize = n;
        }
        random = new SplittableRandom(seed);
        step = 0;
        totalSpikes = 0;
        neuronUpdates = 0;
        synapticEvents = 0;
        setStimulus(stimulus);
    }

    /**
     * Replaces the Poisson drive from the current step on. Driven neurons have no refractory period,
     * as in the reference model.
     */
    public void setStimulus(Stimulus next) {
        for (int i = 0; i < stimulus.size(); i++) {
            neuronRefractory[stimulus.neuron(i)] = refractorySteps;
        }
        stimulus = next;
        stimulusLogMiss = new double[next.size()];
        stimulusNextStep = new int[next.size()];
        for (int i = 0; i < next.size(); i++) {
            neuronRefractory[next.neuron(i)] = 0;
            double p = Math.min(1.0, next.rateHz(i) * params.dtMs() / 1000.0);
            stimulusLogMiss[i] = p >= 1 ? Double.NEGATIVE_INFINITY : Math.log1p(-p);
            stimulusNextStep[i] = step + waitSteps(i) - 1;
        }
    }

    /** Steps until the next Poisson event: geometric, same law as one Bernoulli(rate * dt) trial per step. */
    private int waitSteps(int i) {
        double logMiss = stimulusLogMiss[i];
        if (logMiss == Double.NEGATIVE_INFINITY) {
            return 1;
        }
        double wait = Math.floor(Math.log(1.0 - random.nextDouble()) / logMiss);
        return wait >= Integer.MAX_VALUE / 2.0 ? Integer.MAX_VALUE / 2 : 1 + (int) wait;
    }

    public void run(double durationMs) {
        int steps = params.steps(durationMs);
        for (int s = 0; s < steps; s++) {
            step();
        }
    }

    public void step() {
        final int t = step;
        final int slot = t % (delaySteps + 1);
        final int[] s = state;
        ringSize[slot] = 0;

        // state update + threshold (Brian2 "groups" and "thresholds" slots) over the active neurons
        int kept = 0;
        for (int k = 0, size = activeSize; k < size; k++) {
            int i = activeList[k];
            int b = i * STRIDE;
            float ui = Float.intBitsToFloat(s[b + U]);
            float gi = Float.intBitsToFloat(s[b + G]);
            if (t >= s[b + UPDATE]) {
                ui = ui * decayV + gi * couplingGtoV;
                gi = gi * decayG;
                s[b + U] = Float.floatToRawIntBits(ui);
                s[b + G] = Float.floatToRawIntBits(gi);
                if (ui > uThreshold) {
                    pushSpike(slot, i);
                    int rfc = neuronRefractory[i];
                    s[b + UPDATE] = t + rfc;
                    s[b + INPUT] = (t + Math.max(rfc, 1)) | ACTIVE;
                }
            }
            if (dense || Math.abs(ui) >= epsilon || Math.abs(gi) >= epsilon) {
                activeList[kept++] = i;
            } else {
                s[b + U] = 0;
                s[b + G] = 0;
                s[b + INPUT] &= STEP_MASK;
            }
        }
        neuronUpdates += activeSize;
        activeSize = kept;

        // synaptic delivery of spikes emitted delaySteps ago ("synapses" slot)
        if (t >= delaySteps) {
            int from = (t - delaySteps) % (delaySteps + 1);
            int[] spikes = ring[from];
            for (int k = 0, size = ringSize[from]; k < size; k++) {
                int pre = spikes[k];
                int start = rowPtr[pre];
                int end = rowPtr[pre + 1];
                synapticEvents += end - start;
                for (int e = start; e < end; e++) {
                    int b = post[e] * STRIDE;
                    int gate = s[b + INPUT];
                    if (t >= (gate & STEP_MASK)) {
                        s[b + G] = Float.floatToRawIntBits(Float.intBitsToFloat(s[b + G]) + weight[e] * wSyn);
                        if ((gate & ACTIVE) == 0) {
                            s[b + INPUT] = gate | ACTIVE;
                            addActive(post[e]);
                        }
                    }
                }
            }
        }

        // Poisson input acts directly on v
        for (int i = 0; i < stimulusNextStep.length; i++) {
            if (stimulusNextStep[i] == t) {
                int j = stimulus.neuron(i);
                int b = j * STRIDE;
                int gate = s[b + INPUT];
                if (t >= (gate & STEP_MASK)) {
                    s[b + U] = Float.floatToRawIntBits(Float.intBitsToFloat(s[b + U]) + poissonKick);
                    if ((gate & ACTIVE) == 0) {
                        s[b + INPUT] = gate | ACTIVE;
                        addActive(j);
                    }
                }
                stimulusNextStep[i] = t + waitSteps(i);
            }
        }

        // reset ("resets" slot)
        int[] fired = ring[slot];
        int resetBits = Float.floatToRawIntBits(uReset);
        for (int k = 0, size = ringSize[slot]; k < size; k++) {
            int i = fired[k];
            s[i * STRIDE + U] = resetBits;
            s[i * STRIDE + G] = 0;
            spikeCount[i]++;
        }
        totalSpikes += ringSize[slot];
        step++;
    }

    private void addActive(int neuron) {
        if (activeSize == activeList.length) {
            activeList = Arrays.copyOf(activeList, Math.min(n, activeSize * 2));
        }
        activeList[activeSize++] = neuron;
    }

    private void pushSpike(int slot, int neuron) {
        int size = ringSize[slot];
        if (size == ring[slot].length) {
            ring[slot] = Arrays.copyOf(ring[slot], size * 2);
        }
        ring[slot][size] = neuron;
        ringSize[slot] = size + 1;
    }

    /** Membrane potential of the neuron in mV. */
    public double voltage(int neuron) {
        return vRest + Float.intBitsToFloat(state[neuron * STRIDE + U]);
    }

    public int spikeCount(int neuron) {
        return spikeCount[neuron];
    }

    /** Live view of per-neuron spike counts since the last reset; do not modify. */
    public int[] spikeCounts() {
        return spikeCount;
    }

    public long totalSpikes() {
        return totalSpikes;
    }

    /** Number of neuron state updates performed since the last reset, a measure of the work done. */
    public long neuronUpdates() {
        return neuronUpdates;
    }

    /** Number of synapse activations (spike deliveries along edges) since the last reset. */
    public long synapticEvents() {
        return synapticEvents;
    }

    public int activeNeurons() {
        return activeSize;
    }

    public int stepIndex() {
        return step;
    }

    public double timeMs() {
        return step * params.dtMs();
    }
}
