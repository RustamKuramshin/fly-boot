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
 * <p>
 * The graph is shared and read-only; an instance holds the mutable state of one fly and is not thread-safe.
 */
public final class LifSimulation {

    private final Connectome connectome;
    private final LifParams params;
    private final int n;
    private final int[] rowPtr;
    private final int[] post;
    private final short[] weight;

    private final double vRest;
    private final double vReset;
    private final double vThreshold;
    private final double decayV;
    private final double decayG;
    private final double couplingGtoV;
    private final double wSyn;
    private final double epsilon;
    private final boolean dense;
    private final int delaySteps;
    private final int refractorySteps;

    private final double[] v;
    private final double[] g;
    private final int[] neuronRefractory;
    /** First step at which the neuron integrates again (Brian2 not_refractory in the "groups" slot). */
    private final int[] updateOpenAt;
    /** First step at which input written to v or g is accepted (Brian2 conditional write on not_refractory). */
    private final int[] inputOpenAt;
    private final int[] spikeCount;

    private final boolean[] active;
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
        this.vReset = params.vResetMv();
        this.vThreshold = params.vThresholdMv();
        this.decayV = Math.exp(-dt / tm);
        this.decayG = Math.exp(-dt / ts);
        // Exact solution of du/dt = (g - u) / tm with g decaying at ts: u' = u*decayV + g*ts/(ts-tm)*(decayG-decayV)
        this.couplingGtoV = ts / (ts - tm) * (decayG - decayV);
        this.wSyn = params.wSynMv();
        this.epsilon = params.restEpsilonMv();
        this.dense = epsilon <= 0;
        this.delaySteps = params.steps(params.delayMs());
        this.refractorySteps = params.steps(params.refractoryMs());

        this.v = new double[n];
        this.g = new double[n];
        this.neuronRefractory = new int[n];
        this.updateOpenAt = new int[n];
        this.inputOpenAt = new int[n];
        this.spikeCount = new int[n];
        this.active = new boolean[n];
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
        Arrays.fill(v, vRest);
        Arrays.fill(g, 0);
        Arrays.fill(updateOpenAt, 0);
        Arrays.fill(inputOpenAt, 0);
        Arrays.fill(neuronRefractory, refractorySteps);
        Arrays.fill(spikeCount, 0);
        Arrays.fill(ringSize, 0);
        Arrays.fill(active, dense);
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
        ringSize[slot] = 0;

        // state update + threshold (Brian2 "groups" and "thresholds" slots) over the active neurons
        int kept = 0;
        for (int k = 0, size = activeSize; k < size; k++) {
            int i = activeList[k];
            if (t >= updateOpenAt[i]) {
                double gi = g[i];
                double vi = vRest + (v[i] - vRest) * decayV + gi * couplingGtoV;
                v[i] = vi;
                g[i] = gi * decayG;
                if (vi > vThreshold) {
                    pushSpike(slot, i);
                    int rfc = neuronRefractory[i];
                    updateOpenAt[i] = t + rfc;
                    inputOpenAt[i] = t + Math.max(rfc, 1);
                }
            }
            if (dense || Math.abs(v[i] - vRest) >= epsilon || Math.abs(g[i]) >= epsilon) {
                activeList[kept++] = i;
            } else {
                v[i] = vRest;
                g[i] = 0;
                active[i] = false;
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
                    int j = post[e];
                    if (t >= inputOpenAt[j]) {
                        g[j] += weight[e] * wSyn;
                        activate(j);
                    }
                }
            }
        }

        // Poisson input acts directly on v
        for (int i = 0; i < stimulusNextStep.length; i++) {
            if (stimulusNextStep[i] == t) {
                int j = stimulus.neuron(i);
                if (t >= inputOpenAt[j]) {
                    v[j] += params.poissonKickMv();
                    activate(j);
                }
                stimulusNextStep[i] = t + waitSteps(i);
            }
        }

        // reset ("resets" slot)
        int[] fired = ring[slot];
        for (int k = 0, size = ringSize[slot]; k < size; k++) {
            int i = fired[k];
            v[i] = vReset;
            g[i] = 0;
            spikeCount[i]++;
        }
        totalSpikes += ringSize[slot];
        step++;
    }

    private void activate(int neuron) {
        if (!active[neuron]) {
            active[neuron] = true;
            if (activeSize == activeList.length) {
                activeList = Arrays.copyOf(activeList, Math.min(n, activeSize * 2));
            }
            activeList[activeSize++] = neuron;
        }
    }

    private void pushSpike(int slot, int neuron) {
        int size = ringSize[slot];
        if (size == ring[slot].length) {
            ring[slot] = Arrays.copyOf(ring[slot], size * 2);
        }
        ring[slot][size] = neuron;
        ringSize[slot] = size + 1;
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
