package dev.flyboot.brain.learn;

import dev.flyboot.brain.Perception;

import java.util.Arrays;
import java.util.List;

/**
 * Sparse code of one sniff that feeds the route neurons: the most active cells of one or more populations.
 * Keeping only the strongest Kenyon cells stands in for the APL feedback inhibition that sparsifies them in the
 * fly. Several channels are concatenated, each scaled to norm {@code gain}, cell indices shifted by the sizes of
 * the channels before it.
 *
 * @param cells  cell indices, ascending
 * @param values activity of those cells, same order
 */
public record SparseCode(int[] cells, float[] values) {

    /**
     * @param counts spike counts of the population
     * @param k      number of cells kept; ties broken by the lower index so the code is deterministic
     * @param binary whether kept cells get value 1 instead of their spike count
     */
    public static SparseCode of(int[] counts, int k, boolean binary) {
        Integer[] order = new Integer[counts.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> counts[a] != counts[b] ? Integer.compare(counts[b], counts[a]) : Integer.compare(a, b));
        int kept = 0;
        while (kept < Math.min(k, counts.length) && counts[order[kept]] > 0) {
            kept++;
        }
        int[] cells = new int[kept];
        for (int i = 0; i < kept; i++) {
            cells[i] = order[i];
        }
        Arrays.sort(cells);
        float[] values = new float[kept];
        for (int i = 0; i < kept; i++) {
            values[i] = binary ? 1f : counts[cells[i]];
        }
        return new SparseCode(cells, values);
    }

    /** Concatenated code of the perception over the channels. */
    public static SparseCode of(Perception perception, List<CodeChannel> channels) {
        if (channels.size() == 1 && channels.getFirst().gain() == 1) {
            CodeChannel c = channels.getFirst();
            return of(perception.counts(c.layer()), c.cells(), c.binary());
        }
        int total = 0;
        SparseCode[] parts = new SparseCode[channels.size()];
        for (int i = 0; i < parts.length; i++) {
            CodeChannel c = channels.get(i);
            parts[i] = of(perception.counts(c.layer()), c.cells(), c.binary());
            total += parts[i].size();
        }
        int[] cells = new int[total];
        float[] values = new float[total];
        int pos = 0;
        int offset = 0;
        for (int i = 0; i < parts.length; i++) {
            double norm = parts[i].norm();
            double scale = norm == 0 ? 0 : channels.get(i).gain() / norm;
            for (int j = 0; j < parts[i].size(); j++) {
                cells[pos] = parts[i].cells[j] + offset;
                values[pos] = (float) (parts[i].values[j] * scale);
                pos++;
            }
            offset += perception.counts(channels.get(i).layer()).length;
        }
        return new SparseCode(cells, values);
    }

    /** Total input size of the channels for this perception. */
    public static int inputSize(Perception perception, List<CodeChannel> channels) {
        return channels.stream().mapToInt(c -> perception.counts(c.layer()).length).sum();
    }

    public int size() {
        return cells.length;
    }

    public double norm() {
        double s = 0;
        for (float v : values) {
            s += v * v;
        }
        return Math.sqrt(s);
    }
}
