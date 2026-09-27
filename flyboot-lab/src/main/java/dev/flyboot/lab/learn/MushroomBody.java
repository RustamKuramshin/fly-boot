package dev.flyboot.lab.learn;

import dev.flyboot.brain.learn.Decision;
import dev.flyboot.brain.learn.SparseCode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Research variant, lost to {@link dev.flyboot.brain.learn.PredictionErrorBody} (see the phase 2 notes in README.md).
 * The learned part of the fly: plastic synapses from Kenyon cells onto one route neuron per endpoint, as KC to
 * MBON synapses in the mushroom body. Everything upstream is the frozen connectome.
 * <p>
 * A route neuron's drive is the weighted sum of the code, divided by the norms of its weights and of the code
 * (homeostatic synaptic scaling), so drives are comparable across routes and lie in 0..1. Learning is a
 * three-factor rule: presynaptic activity, normalized per sniff, times a dopamine signal of the route's
 * compartment. Reward potentiates synapses from active cells onto the correct route neuron; punishment depresses
 * them onto a route neuron that wrongly won or that answered a decoy. Weights stay within {@code [0, wMax]}.
 */
public final class MushroomBody {

    private static final int MAGIC = 0x4D425931; // "MBY1"

    private final List<String> routes;
    private final int inputSize;
    private final float wMax;
    private final float[][] weights;
    private final double[] norms;
    private final double[] thresholds;

    public MushroomBody(List<String> routes, int inputSize, double wMax) {
        this.routes = List.copyOf(routes);
        this.inputSize = inputSize;
        this.wMax = (float) wMax;
        this.weights = new float[routes.size()][inputSize];
        this.norms = new double[routes.size()];
        this.thresholds = new double[routes.size()];
    }

    public List<String> routes() {
        return routes;
    }

    public int inputSize() {
        return inputSize;
    }

    public double threshold(int route) {
        return thresholds[route];
    }

    /** Normalized drive of every route neuron, 0..1. */
    public double[] scores(SparseCode code) {
        double codeNorm = code.norm();
        double[] s = new double[routes.size()];
        if (codeNorm == 0) {
            return s;
        }
        int[] cells = code.cells();
        float[] values = code.values();
        for (int r = 0; r < s.length; r++) {
            if (norms[r] == 0) {
                continue;
            }
            float[] w = weights[r];
            double dot = 0;
            for (int i = 0; i < cells.length; i++) {
                dot += w[cells[i]] * values[i];
            }
            s[r] = dot / (norms[r] * codeNorm);
        }
        return s;
    }

    public Decision decide(SparseCode code) {
        double[] s = scores(code);
        int best = -1;
        int second = -1;
        for (int r = 0; r < s.length; r++) {
            if (best < 0 || s[r] > s[best]) {
                second = best;
                best = r;
            } else if (second < 0 || s[r] > s[second]) {
                second = r;
            }
        }
        if (best < 0) {
            return new Decision(-1, -1, 0, 0, 0);
        }
        double score = s[best];
        double runnerUp = second < 0 ? 0 : s[second];
        boolean recognized = score > 0 && score >= thresholds[best];
        return new Decision(recognized ? best : -1, best, score, runnerUp, thresholds[best]);
    }

    /** Dopamine reward in the route's compartment: potentiate synapses from the active cells. */
    public void reward(int route, SparseCode code, double rate) {
        plasticity(route, code, rate);
    }

    /** Dopamine punishment in the route's compartment: depress synapses from the active cells. */
    public void punish(int route, SparseCode code, double rate) {
        plasticity(route, code, -rate);
    }

    private void plasticity(int route, SparseCode code, double rate) {
        float[] w = weights[route];
        int[] cells = code.cells();
        float[] values = code.values();
        double norm = code.norm();
        if (norm == 0) {
            return;
        }
        double r = rate / norm;
        for (int i = 0; i < cells.length; i++) {
            w[cells[i]] = Math.clamp(w[cells[i]] + (float) (r * values[i]), 0f, wMax);
        }
        double s = 0;
        for (float x : w) {
            s += x * x;
        }
        norms[route] = Math.sqrt(s);
    }

    public int train(List<SparseCode> codes, int[] labels, TrainingOptions o) {
        return train(codes, labels, List.of(), o);
    }

    /**
     * Trains on labelled codes: one reward pass (every sample potentiates its route), then error-driven epochs
     * where a sample whose route does not win by {@code margin} rewards its route and punishes the winner, and a
     * decoy that a route neuron recognizes punishes that neuron. Recognition thresholds are recalibrated every
     * epoch and at the end.
     *
     * @param decoys codes of requests that must not be recognized
     * @return number of error-driven corrections in the last epoch
     */
    public int train(List<SparseCode> codes, int[] labels, List<SparseCode> decoys, TrainingOptions o) {
        for (int i = 0; i < codes.size(); i++) {
            reward(labels[i], codes.get(i), 1.0);
        }
        int corrections = 0;
        Integer[] order = new Integer[codes.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        SplittableRandom rnd = new SplittableRandom(o.seed());
        calibrate(codes, labels, o.thresholdQuantile(), o.thresholdSlack());
        for (int epoch = 0; epoch < o.epochs(); epoch++) {
            for (int i = order.length - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                Integer t = order[i];
                order[i] = order[j];
                order[j] = t;
            }
            corrections = 0;
            for (int idx : order) {
                SparseCode code = codes.get(idx);
                int target = labels[idx];
                double[] s = scores(code);
                int rival = -1;
                for (int r = 0; r < s.length; r++) {
                    if (r != target && (rival < 0 || s[r] > s[rival])) {
                        rival = r;
                    }
                }
                if (rival >= 0 && s[target] - s[rival] < o.margin() * s[target]) {
                    reward(target, code, o.learningRate());
                    punish(rival, code, o.learningRate());
                    corrections++;
                }
            }
            for (SparseCode decoy : decoys) {
                Decision d = decide(decoy);
                if (d.recognized()) {
                    punish(d.route(), decoy, o.learningRate() * o.decoyRate());
                    corrections++;
                }
            }
            calibrate(codes, labels, o.thresholdQuantile(), o.thresholdSlack());
            if (corrections == 0) {
                break;
            }
        }
        calibrate(codes, labels, o.thresholdQuantile(), o.thresholdSlack());
        return corrections;
    }

    /** Per route: slack times the given quantile of the drive its own samples produce. */
    public void calibrate(List<SparseCode> codes, int[] labels, double quantile, double slack) {
        List<List<Double>> own = new ArrayList<>();
        for (int r = 0; r < routes.size(); r++) {
            own.add(new ArrayList<>());
        }
        for (int i = 0; i < codes.size(); i++) {
            own.get(labels[i]).add(scores(codes.get(i))[labels[i]]);
        }
        for (int r = 0; r < routes.size(); r++) {
            double[] s = own.get(r).stream().mapToDouble(Double::doubleValue).sorted().toArray();
            thresholds[r] = s.length == 0 ? 1 : slack * s[(int) Math.floor(quantile * (s.length - 1))];
        }
    }

    public void write(OutputStream stream, String fingerprint) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeUTF(fingerprint);
        out.writeInt(routes.size());
        out.writeInt(inputSize);
        out.writeFloat(wMax);
        for (int r = 0; r < routes.size(); r++) {
            out.writeUTF(routes.get(r));
            out.writeDouble(thresholds[r]);
            for (float w : weights[r]) {
                out.writeFloat(w);
            }
        }
        out.flush();
    }

    /** Reads a mushroom body written with the same fingerprint, or returns null if the fingerprint differs. */
    public static MushroomBody read(InputStream stream, String fingerprint) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC || !in.readUTF().equals(fingerprint)) {
            return null;
        }
        int n = in.readInt();
        int inputSize = in.readInt();
        float wMax = in.readFloat();
        List<String> routes = new ArrayList<>();
        double[] thresholds = new double[n];
        float[][] weights = new float[n][inputSize];
        for (int r = 0; r < n; r++) {
            routes.add(in.readUTF());
            thresholds[r] = in.readDouble();
            for (int i = 0; i < inputSize; i++) {
                weights[r][i] = in.readFloat();
            }
        }
        MushroomBody mb = new MushroomBody(routes, inputSize, wMax);
        for (int r = 0; r < n; r++) {
            System.arraycopy(weights[r], 0, mb.weights[r], 0, inputSize);
            mb.thresholds[r] = thresholds[r];
            double s = 0;
            for (float x : weights[r]) {
                s += x * x;
            }
            mb.norms[r] = Math.sqrt(s);
        }
        return mb;
    }

    @Override
    public String toString() {
        return "MushroomBody" + Arrays.toString(thresholds);
    }
}
