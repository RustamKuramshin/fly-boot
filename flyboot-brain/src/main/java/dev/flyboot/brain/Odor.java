package dev.flyboot.brain;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Activation of olfactory glomeruli: glomerulus type to receptor firing rate in Hz.
 *
 * @param ratesHz glomerulus type (e.g. {@code ORN_DA1}) to rate, only positive rates, sorted by type
 */
public record Odor(Map<String, Double> ratesHz) {

    public Odor {
        TreeMap<String, Double> copy = new TreeMap<>();
        ratesHz.forEach((type, rate) -> {
            if (rate > 0) {
                copy.put(type, rate);
            }
        });
        ratesHz = Collections.unmodifiableMap(copy);
    }

    public double rateHz(String glomerulus) {
        return ratesHz.getOrDefault(glomerulus, 0.0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Odor[");
        ratesHz.forEach((type, rate) -> sb.append(type.replaceFirst("^ORN_", "")).append('=')
                .append(Math.round(rate)).append(' '));
        return sb.toString().trim() + "]";
    }
}
