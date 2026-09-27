package dev.flyboot.brain;

import java.util.Map;

/**
 * What the brain did during one sniff.
 *
 * @param counts         spike counts per readout population, in population order
 * @param totalSpikes    spikes of the whole brain
 * @param synapticEvents spike deliveries along edges, the main cost of the simulation
 * @param brainMs        simulated brain time
 * @param wallNanos      wall-clock time of the simulation
 * @param spikes         every spike, when the sniff was recorded; otherwise null
 */
public record Perception(Map<String, int[]> counts, long totalSpikes, long synapticEvents, double brainMs,
                         long wallNanos, SpikeRecording spikes) {

    public Perception {
        counts = Map.copyOf(counts);
    }

    public Perception(Map<String, int[]> counts, long totalSpikes, long synapticEvents, double brainMs, long wallNanos) {
        this(counts, totalSpikes, synapticEvents, brainMs, wallNanos, null);
    }

    public int[] counts(String population) {
        int[] c = counts.get(population);
        if (c == null) {
            throw new IllegalArgumentException("Population " + population + " was not read out, available: " + counts.keySet());
        }
        return c;
    }

    public double wallMs() {
        return wallNanos / 1e6;
    }
}
