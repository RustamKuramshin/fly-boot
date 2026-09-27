package dev.flyboot.brain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Poisson drive of sensory neurons: neuron index to firing rate in Hz. */
public final class Stimulus {

    public static final Stimulus NONE = new Stimulus(new int[0], new double[0]);

    private final int[] neurons;
    private final double[] ratesHz;

    private Stimulus(int[] neurons, double[] ratesHz) {
        this.neurons = neurons;
        this.ratesHz = ratesHz;
    }

    public int size() {
        return neurons.length;
    }

    public int neuron(int i) {
        return neurons[i];
    }

    public double rateHz(int i) {
        return ratesHz[i];
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Integer, Double> rates = new LinkedHashMap<>();

        /** Adds the rate to the neuron; rates of a neuron listed several times are summed. */
        public Builder add(int neuron, double rateHz) {
            if (rateHz > 0) {
                rates.merge(neuron, rateHz, Double::sum);
            }
            return this;
        }

        public Builder addAll(int[] neurons, double rateHz) {
            for (int n : neurons) {
                add(n, rateHz);
            }
            return this;
        }

        public Stimulus build() {
            int[] idx = rates.keySet().stream().mapToInt(Integer::intValue).toArray();
            double[] hz = rates.values().stream().mapToDouble(Double::doubleValue).toArray();
            return new Stimulus(idx, hz);
        }
    }

    @Override
    public String toString() {
        return "Stimulus" + Arrays.toString(neurons);
    }
}
