package dev.flyboot.lab.learn;

import dev.flyboot.brain.BrainAtlas;

/**
 * How the fly learns routes.
 *
 * @param layer             population whose sparse code feeds the route neurons
 * @param codeSize          number of most active cells kept in the code
 * @param binary            code cells as 1 instead of their spike counts
 * @param samplesPerRoute   training requests per route
 * @param holdoutPerRoute   extra requests per route with unseen values, only to measure accuracy
 * @param epochs            maximum error-driven epochs after the reward pass
 * @param learningRate      weight change per correction, relative to the reward pass
 * @param margin            relative lead the correct route must have to count as learned
 * @param wMax              upper bound of a synaptic weight
 * @param thresholdQuantile quantile of a route's own training drives used for its recognition threshold
 * @param thresholdSlack    factor applied to that quantile
 * @param decoyRate         punishment for a recognized decoy, relative to {@code learningRate}; 0 ignores decoys
 * @param seed              seed of the request generator and of the sample order
 */
public record TrainingOptions(String layer, int codeSize, boolean binary, int samplesPerRoute, int holdoutPerRoute,
                              int epochs, double learningRate, double margin, double wMax,
                              double thresholdQuantile, double thresholdSlack, double decoyRate, long seed) {

    public static TrainingOptions defaults() {
        return new TrainingOptions(BrainAtlas.KC, 260, true, 30, 10, 30, 1.0, 0.1, 1000, 0.05, 0.8, 0.2, 2026);
    }

    public TrainingOptions withCode(String layer, int codeSize, boolean binary) {
        return new TrainingOptions(layer, codeSize, binary, samplesPerRoute, holdoutPerRoute, epochs, learningRate,
                margin, wMax, thresholdQuantile, thresholdSlack, decoyRate, seed);
    }

    public TrainingOptions withEpochs(int n) {
        return new TrainingOptions(layer, codeSize, binary, samplesPerRoute, holdoutPerRoute, n, learningRate,
                margin, wMax, thresholdQuantile, thresholdSlack, decoyRate, seed);
    }

    public TrainingOptions withThreshold(double quantile, double slack, double decoyRate) {
        return new TrainingOptions(layer, codeSize, binary, samplesPerRoute, holdoutPerRoute, epochs, learningRate,
                margin, wMax, quantile, slack, decoyRate, seed);
    }

    public TrainingOptions withSamples(int train, int holdout) {
        return new TrainingOptions(layer, codeSize, binary, train, holdout, epochs, learningRate,
                margin, wMax, thresholdQuantile, thresholdSlack, decoyRate, seed);
    }
}
