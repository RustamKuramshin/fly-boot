package dev.flyboot.tornado;

import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.FlySimulation;
import dev.flyboot.brain.LifParams;
import dev.flyboot.brain.SpikeRecording;
import dev.flyboot.brain.Stimulus;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.types.arrays.ShortArray;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One fly on the GPU. Same model and Brian2 schedule as {@link dev.flyboot.brain.LifSimulation}, organised in
 * windows of {@code delay} steps: a spike needs exactly {@code delay} steps to arrive, so all input of a window is
 * known from the spikes of the previous one. Each window is two kernels (every launch is a synchronous round
 * trip to the GPU, so there are as few as possible):
 * <ol>
 *   <li>{@code neurons}: every neuron runs the window's steps in registers - state update, threshold, synaptic
 *       input and Poisson kicks gated by refractoriness, reset - and leaves a bitmask of the steps it fired in.
 *       Kicks come as one int per neuron: bit k = kick at step k, the top bit marks a driven neuron (no
 *       refractory period, as in the reference model);</li>
 *   <li>{@code deliver}: every neuron that fired pushes its synapse counts along its CSR row into the input of the
 *       next window with integer atomics; {@value #LANES} threads share a row, as a projection neuron has
 *       thousands of synapses.</li>
 * </ol>
 * Unlike the CPU engine every neuron is updated every step (no rest snapping), and inputs of one step are summed
 * as integers before the float add, so results agree statistically, not bit for bit. Poisson events are drawn on
 * the host with the same law.
 */
final class TornadoFly implements FlySimulation {

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final int LOCAL = 256;
    static final int LANES = 32;
    private static final int DRIVEN = 1 << 31;

    // control words
    private static final int BASE = 0;
    private static final int N = 1;
    private static final int RESET = 2;
    private static final int RFC = 5;
    private static final int DELAY = 6;
    private static final int STEPS = 7;

    private final Connectome connectome;
    private final LifParams params;
    private final int n;
    private final int delay;
    private final int[] rowPtrHost;

    private final IntArray ctl = new IntArray(8);
    private final FloatArray consts = new FloatArray(6);
    private final IntArray kicks;
    private final IntArray count;
    private final IntArray mask;
    private final TornadoExecutionPlan plan;
    private final GridScheduler grid;

    private Stimulus stimulus = Stimulus.NONE;
    private double[] logMiss = new double[0];
    private int[] nextKick = new int[0];
    private SplittableRandom random = new SplittableRandom(0);
    private boolean resetPending = true;
    private boolean partialWindowRun;
    private int step;
    private int[] counts;
    private long totalSpikes;
    private boolean recordingOn;
    private int[] recSteps = new int[0];
    private int[] recNeurons = new int[0];
    private int recSize;

    TornadoFly(Connectome connectome, LifParams params) {
        this.connectome = connectome;
        this.params = params;
        this.n = connectome.neuronCount();
        this.delay = (int) Math.round(params.delayMs() / params.dtMs());
        if (delay < 1 || delay > 30) {
            throw new IllegalArgumentException("The GPU engine needs a synaptic delay of 1..30 steps, got " + delay);
        }
        this.rowPtrHost = connectome.rowPtr();

        double dt = params.dtMs();
        double tm = params.tauMembraneMs();
        double ts = params.tauSynapseMs();
        consts.set(0, (float) Math.exp(-dt / tm));
        consts.set(1, (float) Math.exp(-dt / ts));
        consts.set(2, (float) (ts / (ts - tm) * (Math.exp(-dt / ts) - Math.exp(-dt / tm))));
        consts.set(3, (float) (params.vThresholdMv() - params.vRestMv()));
        consts.set(4, (float) params.wSynMv());
        consts.set(5, (float) params.poissonKickMv());
        ctl.set(N, n);
        ctl.set(RFC, (int) Math.round(params.refractoryMs() / dt));
        ctl.set(DELAY, delay);

        IntArray rowPtr = IntArray.fromArray(connectome.rowPtr());
        IntArray post = IntArray.fromArray(connectome.post());
        ShortArray weight = ShortArray.fromArray(connectome.weight());
        FloatArray u = new FloatArray(n);
        FloatArray g = new FloatArray(n);
        IntArray updateOpen = new IntArray(n);
        IntArray inputOpen = new IntArray(n);
        IntArray input = new IntArray(n * delay);
        kicks = new IntArray(n);
        count = new IntArray(n);
        mask = new IntArray(n);
        counts = new int[n];

        String name = "fly" + IDS.incrementAndGet();
        KernelContext kc = new KernelContext();
        TaskGraph tg = new TaskGraph(name)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, consts, rowPtr, post, weight, u, g, updateOpen,
                        inputOpen, input, count, mask)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, ctl, kicks)
                .task("neurons", Kernels::neurons, kc, ctl, consts, u, g, updateOpen, inputOpen, count, kicks, mask,
                        input)
                .task("deliver", Kernels::deliver, kc, ctl, mask, rowPtr, post, weight, input)
                .transferToHost(DataTransferMode.UNDER_DEMAND, count, mask);
        ImmutableTaskGraph itg = tg.snapshot();
        grid = new GridScheduler();
        grid.addWorkerGrid(name + ".neurons", worker(n));
        grid.addWorkerGrid(name + ".deliver", worker(n * LANES));
        plan = new TornadoExecutionPlan(itg);
        plan.withGridScheduler(grid).withPreCompilation();
    }

    private static WorkerGrid worker(int items) {
        WorkerGrid w = new WorkerGrid1D((items + LOCAL - 1) / LOCAL * LOCAL);
        w.setLocalWork(LOCAL, 1, 1);
        return w;
    }

    static String deviceName() {
        try {
            return "TornadoVM " + TornadoExecutionPlan.getDevice(0, 0).getPhysicalDevice().getDeviceName();
        } catch (RuntimeException e) {
            return "TornadoVM (" + e.getMessage() + ")";
        }
    }

    @Override
    public void setStimulus(Stimulus next) {
        for (int i = 0; i < stimulus.size(); i++) {
            kicks.set(stimulus.neuron(i), 0);
        }
        stimulus = next;
        logMiss = new double[next.size()];
        nextKick = new int[next.size()];
        for (int i = 0; i < next.size(); i++) {
            kicks.set(next.neuron(i), DRIVEN);
            double p = Math.min(1.0, next.rateHz(i) * params.dtMs() / 1000.0);
            logMiss[i] = p >= 1 ? Double.NEGATIVE_INFINITY : Math.log1p(-p);
            nextKick[i] = step + waitSteps(i) - 1;
        }
    }

    private int waitSteps(int i) {
        if (logMiss[i] == Double.NEGATIVE_INFINITY) {
            return 1;
        }
        double wait = Math.floor(Math.log(1.0 - random.nextDouble()) / logMiss[i]);
        return wait >= Integer.MAX_VALUE / 2.0 ? Integer.MAX_VALUE / 2 : 1 + (int) wait;
    }

    @Override
    public void reset(long seed) {
        random = new SplittableRandom(seed);
        step = 0;
        totalSpikes = 0;
        recSize = 0;
        Arrays.fill(counts, 0);
        resetPending = true;
        partialWindowRun = false;
        setStimulus(stimulus);
    }

    @Override
    public void run(double durationMs) {
        if (partialWindowRun) {
            throw new IllegalStateException("The GPU engine continues a run only after whole windows of " + delay
                    + " steps; reset first");
        }
        int steps = (int) Math.round(durationMs / params.dtMs());
        TornadoExecutionResult last = null;
        for (int done = 0; done < steps; done += delay) {
            int r = Math.min(delay, steps - done);
            ctl.set(BASE, step);
            ctl.set(STEPS, r);
            ctl.set(RESET, resetPending ? 1 : 0);
            for (int i = 0; i < stimulus.size(); i++) {
                int bits = DRIVEN;
                while (nextKick[i] < step + r) {
                    bits |= 1 << (nextKick[i] - step);
                    nextKick[i] += waitSteps(i);
                }
                kicks.set(stimulus.neuron(i), bits);
            }
            last = plan.execute();
            resetPending = false;
            if (recordingOn) {
                last.transferToHost(mask);
                record(step);
            }
            step += r;
            if (r < delay) {
                partialWindowRun = true;
            }
        }
        if (last != null) {
            last.transferToHost(count);
            long total = 0;
            for (int i = 0; i < n; i++) {
                counts[i] = count.get(i);
                total += counts[i];
            }
            totalSpikes = total;
        }
    }

    private void record(int base) {
        for (int i = 0; i < n; i++) {
            int m = mask.get(i);
            while (m != 0) {
                int k = Integer.numberOfTrailingZeros(m);
                m &= m - 1;
                if (recSize == recSteps.length) {
                    recSteps = Arrays.copyOf(recSteps, Math.max(1024, recSize * 2));
                    recNeurons = Arrays.copyOf(recNeurons, recSteps.length);
                }
                recSteps[recSize] = base + k;
                recNeurons[recSize++] = i;
            }
        }
    }

    @Override
    public int spikeCount(int neuron) {
        return counts[neuron];
    }

    @Override
    public long totalSpikes() {
        return totalSpikes;
    }

    /** Deliveries of the spikes counted so far along their out-edges (spikes of the last window included). */
    @Override
    public long synapticEvents() {
        long events = 0;
        for (int i = 0; i < n; i++) {
            if (counts[i] > 0) {
                events += (long) counts[i] * (rowPtrHost[i + 1] - rowPtrHost[i]);
            }
        }
        return events;
    }

    @Override
    public double timeMs() {
        return step * params.dtMs();
    }

    @Override
    public void setRecording(boolean on) {
        recordingOn = on;
    }

    @Override
    public SpikeRecording recording() {
        if (!recordingOn) {
            return null;
        }
        // the GPU reports spikes per window neuron by neuron; order them by step like the CPU engine
        Integer[] order = new Integer[recSize];
        for (int i = 0; i < recSize; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(recSteps[a], recSteps[b]));
        int[] steps = new int[recSize];
        int[] neurons = new int[recSize];
        for (int i = 0; i < recSize; i++) {
            steps[i] = recSteps[order[i]];
            neurons[i] = recNeurons[order[i]];
        }
        return new SpikeRecording(steps, neurons, params.dtMs());
    }

    /** The kernels; static methods compiled by TornadoVM. Only float and int arithmetic (Metal has no FP64). */
    static final class Kernels {

        private Kernels() {
        }

        static void neurons(KernelContext kc, IntArray ctl, FloatArray consts, FloatArray u, FloatArray g,
                            IntArray updateOpen, IntArray inputOpen, IntArray count, IntArray kicks, IntArray mask,
                            IntArray input) {
            int i = kc.globalIdx;
            int n = ctl.get(N);
            if (i < n) {
                float decayV = consts.get(0);
                float decayG = consts.get(1);
                float coupling = consts.get(2);
                float threshold = consts.get(3);
                float wSyn = consts.get(4);
                float kick = consts.get(5);
                int base = ctl.get(BASE);
                int steps = ctl.get(STEPS);
                int bits = kicks.get(i);
                int rfc = bits < 0 ? 0 : ctl.get(RFC);
                float ui = u.get(i);
                float gi = g.get(i);
                int uo = updateOpen.get(i);
                int io = inputOpen.get(i);
                int spikes = count.get(i);
                if (ctl.get(RESET) == 1) {
                    ui = 0f;
                    gi = 0f;
                    uo = 0;
                    io = 0;
                    spikes = 0;
                    int d = ctl.get(DELAY);
                    for (int k = 0; k < d; k++) {
                        input.set(k * n + i, 0);
                    }
                }
                int fired = 0;
                for (int k = 0; k < steps; k++) {
                    int t = base + k;
                    boolean spiked = false;
                    if (t >= uo) {
                        ui = ui * decayV + gi * coupling;
                        gi = gi * decayG;
                        if (ui > threshold) {
                            spiked = true;
                            fired = fired | (1 << k);
                            spikes = spikes + 1;
                            uo = t + rfc;
                            io = t + (rfc > 1 ? rfc : 1);
                        }
                    }
                    int w = input.get(k * n + i);
                    if (w != 0) {
                        if (t >= io) {
                            gi = gi + w * wSyn;
                        }
                        input.set(k * n + i, 0);
                    }
                    if (((bits >> k) & 1) != 0 && t >= io) {
                        ui = ui + kick;
                    }
                    if (spiked) {
                        ui = 0f;
                        gi = 0f;
                    }
                }
                u.set(i, ui);
                g.set(i, gi);
                updateOpen.set(i, uo);
                inputOpen.set(i, io);
                mask.set(i, fired);
                count.set(i, spikes);
            }
        }

        static void deliver(KernelContext kc, IntArray ctl, IntArray mask, IntArray rowPtr, IntArray post,
                            ShortArray weight, IntArray input) {
            int idx = kc.globalIdx;
            int n = ctl.get(N);
            int i = idx / LANES;
            int lane = idx - i * LANES;
            if (i < n) {
                int fired = mask.get(i);
                if (fired != 0) {
                    int d = ctl.get(DELAY);
                    // a spike at step k of a window of r steps arrives delay steps later: index k + (d - r) of the next window
                    int shift = d - ctl.get(STEPS);
                    int end = rowPtr.get(i + 1);
                    for (int e = rowPtr.get(i) + lane; e < end; e += LANES) {
                        int j = post.get(e);
                        int w = weight.get(e);
                        for (int k = 0; k < d; k++) {
                            if (((fired >> k) & 1) != 0) {
                                kc.atomicAdd(input, (k + shift) * n + j, w);
                            }
                        }
                    }
                }
            }
        }
    }
}
