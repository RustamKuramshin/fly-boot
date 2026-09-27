package dev.flyboot.brain.learn;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Route neurons trained by dopamine prediction errors (in the spirit of Bennett, Philippides and Nowotny 2021,
 * Nat Commun 12:2569: dopaminergic neurons compute the difference between expected and received reinforcement).
 * <p>
 * Every route neuron, plus one "unknown smell" neuron, sums the normalized sparse code through plastic
 * synapses. Lateral inhibition between them is modelled as a softmax, so their outputs are the fly's belief
 * about the route. After a sniff, the dopamine signal of each compartment is {@code reinforcement - belief}:
 * the synapses from active cells onto the right neuron are potentiated by what it missed, the others are
 * depressed by what they claimed. Decoys teach the unknown neuron; classes are balanced so the many decoys do
 * not drown the routes. Synapses are signed effective weights (excitation, or inhibition through interneurons).
 */
public final class PredictionErrorBody {

    private static final int MAGIC = 0x50454231; // "PEB1"

    private final List<String> routes;
    private final int inputSize;
    private final int unknown;
    private final float[][] weights;
    private final float[] bias;
    private double minBelief;

    /**
     * @param routes    route names; an extra unknown neuron is added after them
     * @param inputSize length of the code
     * @param minBelief belief the winning route neuron needs to be trusted, otherwise the smell is unknown
     */
    public PredictionErrorBody(List<String> routes, int inputSize, double minBelief) {
        this.routes = List.copyOf(routes);
        this.inputSize = inputSize;
        this.unknown = routes.size();
        this.weights = new float[routes.size() + 1][inputSize];
        this.bias = new float[routes.size() + 1];
        this.minBelief = minBelief;
    }

    public List<String> routes() {
        return routes;
    }

    public int inputSize() {
        return inputSize;
    }

    public double minBelief() {
        return minBelief;
    }

    /** Beliefs of all route neurons followed by the unknown neuron, summing to 1. */
    public double[] beliefs(SparseCode code) {
        double[] z = new double[weights.length];
        double norm = code.norm();
        int[] cells = code.cells();
        float[] values = code.values();
        double max = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < z.length; r++) {
            float[] w = weights[r];
            double sum = bias[r];
            if (norm > 0) {
                for (int i = 0; i < cells.length; i++) {
                    sum += w[cells[i]] * values[i] / norm;
                }
            }
            z[r] = sum;
            max = Math.max(max, sum);
        }
        double total = 0;
        for (int r = 0; r < z.length; r++) {
            z[r] = Math.exp(z[r] - max);
            total += z[r];
        }
        for (int r = 0; r < z.length; r++) {
            z[r] /= total;
        }
        return z;
    }

    public Decision decide(SparseCode code) {
        double[] p = beliefs(code);
        int best = 0;
        int second = -1;
        for (int r = 1; r < p.length; r++) {
            if (p[r] > p[best]) {
                second = best;
                best = r;
            } else if (second < 0 || p[r] > p[second]) {
                second = r;
            }
        }
        boolean recognized = best != unknown && p[best] >= minBelief;
        int bestRoute = best == unknown ? -1 : best;
        return new Decision(recognized ? best : -1, bestRoute, p[best], second < 0 ? 0 : p[second], minBelief);
    }

    /**
     * @param codes   codes of requests to known routes
     * @param labels  route index of every code
     * @param decoys  codes of requests that must be answered 404
     * @param epochs  passes over the shuffled samples
     * @param rate    learning rate
     * @param decay   weight decay per sample (keeps weights small, i.e. beliefs humble)
     * @param seed    order of samples
     */
    public void train(List<SparseCode> codes, int[] labels, List<SparseCode> decoys, int epochs, double rate,
                      double decay, long seed) {
        int n = codes.size() + decoys.size();
        List<SparseCode> all = new ArrayList<>(codes);
        all.addAll(decoys);
        int[] target = new int[n];
        System.arraycopy(labels, 0, target, 0, labels.length);
        for (int i = labels.length; i < n; i++) {
            target[i] = unknown;
        }
        // class balance: every class, the unknown one included, weighs as much as an average route
        int[] perClass = new int[weights.length];
        for (int t : target) {
            perClass[t]++;
        }
        double average = (double) codes.size() / Math.max(1, routes.size());
        double[] classWeight = new double[weights.length];
        for (int c = 0; c < classWeight.length; c++) {
            classWeight[c] = perClass[c] == 0 ? 0 : average / perClass[c];
        }
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        SplittableRandom rnd = new SplittableRandom(seed);
        float shrink = (float) (1 - decay);
        for (int epoch = 0; epoch < epochs; epoch++) {
            for (int i = n - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = order[i];
                order[i] = order[j];
                order[j] = t;
            }
            double lr = rate / (1 + epoch * 0.1);
            for (int idx : order) {
                SparseCode code = all.get(idx);
                double norm = code.norm();
                if (norm == 0) {
                    continue;
                }
                double[] p = beliefs(code);
                double c = classWeight[target[idx]];
                int[] cells = code.cells();
                float[] values = code.values();
                for (int r = 0; r < weights.length; r++) {
                    double dopamine = ((r == target[idx] ? 1 : 0) - p[r]) * c * lr;
                    float[] w = weights[r];
                    for (int i = 0; i < cells.length; i++) {
                        w[cells[i]] = w[cells[i]] * shrink + (float) (dopamine * values[i] / norm);
                    }
                    bias[r] += (float) (dopamine * 0.1);
                }
            }
        }
    }

    public void write(OutputStream stream, String fingerprint) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeUTF(fingerprint);
        out.writeInt(routes.size());
        out.writeInt(inputSize);
        out.writeDouble(minBelief);
        for (String r : routes) {
            out.writeUTF(r);
        }
        for (int r = 0; r < weights.length; r++) {
            out.writeFloat(bias[r]);
            for (float w : weights[r]) {
                out.writeFloat(w);
            }
        }
        out.flush();
    }

    /** Reads a body written with the same fingerprint, or returns null if the fingerprint differs. */
    public static PredictionErrorBody read(InputStream stream, String fingerprint) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC || !in.readUTF().equals(fingerprint)) {
            return null;
        }
        int n = in.readInt();
        int inputSize = in.readInt();
        double minBelief = in.readDouble();
        List<String> routes = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            routes.add(in.readUTF());
        }
        PredictionErrorBody body = new PredictionErrorBody(routes, inputSize, minBelief);
        for (int r = 0; r <= n; r++) {
            body.bias[r] = in.readFloat();
            for (int i = 0; i < inputSize; i++) {
                body.weights[r][i] = in.readFloat();
            }
        }
        return body;
    }
}
