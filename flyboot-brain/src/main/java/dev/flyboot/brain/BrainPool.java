package dev.flyboot.brain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed number of flies sharing one read-only connectome. Every sniff borrows a fly, returns it to rest,
 * drives it with the odor for {@code sniffMs} of brain time and reads the spike counts of the readout populations.
 * Flies are grown lazily; when all are busy the caller waits. The same odor and seed always give the same
 * perception, whichever fly handles it.
 */
public final class BrainPool {

    private final Brain brain;
    private final int size;
    private final double sniffMs;
    private final List<String> readouts;
    private final BlockingQueue<LifSimulation> idle;
    private final AtomicInteger grown = new AtomicInteger();

    BrainPool(Brain brain, int size, double sniffMs, List<String> readouts) {
        if (size < 1) {
            throw new IllegalArgumentException("A pool needs at least one fly, got " + size);
        }
        readouts.forEach(brain.atlas()::population);
        this.brain = brain;
        this.size = size;
        this.sniffMs = sniffMs;
        this.readouts = List.copyOf(readouts);
        this.idle = new ArrayBlockingQueue<>(size);
    }

    public Brain brain() {
        return brain;
    }

    public int size() {
        return size;
    }

    public double sniffMs() {
        return sniffMs;
    }

    public List<String> readouts() {
        return readouts;
    }

    public Perception sniff(Odor odor, long seed) {
        return sniff(brain.atlas().stimulus(odor), seed);
    }

    public Perception sniff(Stimulus stimulus, long seed) {
        LifSimulation fly = borrow();
        try {
            fly.setStimulus(stimulus);
            fly.reset(seed);
            long started = System.nanoTime();
            fly.run(sniffMs);
            long wall = System.nanoTime() - started;
            Map<String, int[]> counts = new LinkedHashMap<>();
            for (String name : readouts) {
                int[] neurons = brain.atlas().population(name);
                int[] c = new int[neurons.length];
                for (int i = 0; i < neurons.length; i++) {
                    c[i] = fly.spikeCount(neurons[i]);
                }
                counts.put(name, c);
            }
            return new Perception(counts, fly.totalSpikes(), fly.synapticEvents(), fly.timeMs(), wall);
        } finally {
            fly.setStimulus(Stimulus.NONE);
            idle.offer(fly);
        }
    }

    private LifSimulation borrow() {
        LifSimulation fly = idle.poll();
        if (fly != null) {
            return fly;
        }
        int n = grown.get();
        while (n < size) {
            if (grown.compareAndSet(n, n + 1)) {
                return new LifSimulation(brain.connectome(), brain.params());
            }
            n = grown.get();
        }
        try {
            return idle.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a free fly", e);
        }
    }
}
