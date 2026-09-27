package dev.flyboot.brain;

/**
 * Every spike of a sniff: {@code neurons[i]} fired at step {@code steps[i]}, in firing order.
 *
 * @param dtMs duration of one step
 */
public record SpikeRecording(int[] steps, int[] neurons, double dtMs) {

    public int size() {
        return steps.length;
    }
}
