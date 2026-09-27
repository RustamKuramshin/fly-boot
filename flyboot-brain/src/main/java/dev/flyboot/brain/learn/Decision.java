package dev.flyboot.brain.learn;

/**
 * Which route neuron won.
 *
 * @param route      index of the recognized route, or -1 when the fly does not recognize the smell
 * @param best       index of the strongest route neuron, even when not recognized; -1 if there are no routes
 * @param score      normalized drive of the strongest route neuron, 0..1
 * @param runnerUp   normalized drive of the second strongest route neuron
 * @param threshold  recognition threshold of the strongest route neuron
 */
public record Decision(int route, int best, double score, double runnerUp, double threshold) {

    public boolean recognized() {
        return route >= 0;
    }

    /** Relative lead of the winner over the runner-up, 0..1. */
    public double margin() {
        return score <= 0 ? 0 : (score - runnerUp) / score;
    }
}
