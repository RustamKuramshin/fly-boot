package dev.flyboot.brain;

/** Lets tests in sub-packages grow the synthetic brain. */
public final class SyntheticBrainAccess {

    private SyntheticBrainAccess() {
    }

    public static Brain create(int neurons, long seed) {
        return SyntheticBrain.create(neurons, seed);
    }
}
