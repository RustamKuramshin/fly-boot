package dev.flyboot.brain;

/**
 * The mutable state of one simulated fly over a shared read-only connectome. Not thread-safe; a
 * {@link BrainPool} hands each simulation to one request at a time.
 */
public interface FlySimulation {

    /** Replaces the Poisson drive from the current step on. */
    void setStimulus(Stimulus stimulus);

    /** Returns the fly to rest, clears counters and the recording, and reseeds the Poisson input. */
    void reset(long seed);

    void run(double durationMs);

    int spikeCount(int neuron);

    long totalSpikes();

    /** Spike deliveries along edges since the last reset. */
    long synapticEvents();

    double timeMs();

    /** Whether spikes after the next reset are recorded; off by default. */
    void setRecording(boolean on);

    /** Spikes since the last reset, or null when not recording. */
    SpikeRecording recording();
}
