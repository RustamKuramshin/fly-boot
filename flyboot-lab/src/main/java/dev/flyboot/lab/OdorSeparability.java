package dev.flyboot.lab;

import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.LifParams;
import dev.flyboot.brain.LifSimulation;
import dev.flyboot.brain.NeuronCatalog;
import dev.flyboot.brain.Stimulus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Can the fly tell odors apart? Synthetic odors drive the 53 ORN glomerular types, the whole brain is simulated
 * for one sniff, and a nearest-centroid classifier is trained on spike counts of each neuron population.
 * The same is done on a graph with shuffled wiring and on the raw input rates.
 * <p>
 * Usage: {@code OdorSeparability [odors] [variantsPerOdor] [sniffMs] [glomeruliPerOdor] [noise] [minHz] [maxHz]}.
 */
public final class OdorSeparability {

    record Sample(int odor, int variant, double[] input, Map<String, int[]> counts, long spikes, double wallMs) {
    }

    public static void main(String[] args) {
        int odors = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int variants = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        double sniffMs = args.length > 2 ? Double.parseDouble(args[2]) : 100;
        int perOdor = args.length > 3 ? Integer.parseInt(args[3]) : 8;
        double noise = args.length > 4 ? Double.parseDouble(args[4]) : 0.2;
        double minHz = args.length > 5 ? Double.parseDouble(args[5]) : 50;
        double maxHz = args.length > 6 ? Double.parseDouble(args[6]) : 200;

        Connectome brain = Lab.connectome();
        NeuronCatalog catalog = Lab.catalog();
        Map<String, int[]> glomeruli = catalog.groupByType(n -> n.cellClass().equals("olfactory") && n.type().startsWith("ORN_"));
        List<int[]> glom = new ArrayList<>(glomeruli.values());

        Map<String, int[]> readouts = new LinkedHashMap<>();
        readouts.put("ALPN", catalog.select(n -> n.cellClass().equals("ALPN")));
        readouts.put("KC", catalog.select(n -> n.cellClass().equals("Kenyon_Cell")));
        readouts.put("MBON", catalog.select(n -> n.cellClass().equals("MBON")));
        readouts.put("LHN", catalog.select(n -> n.cellClass().equals("LHLN") || n.cellClass().equals("LHCENT")));
        readouts.put("DN", catalog.select(n -> n.superClass().equals("descending")));
        readouts.put("ALL", catalog.select(n -> !n.cellClass().equals("olfactory")));
        System.out.printf("Glomeruli: %d, readouts: %s%n", glom.size(),
                readouts.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().length).toList());
        System.out.printf("Odors: %d x %d variants, %d glomeruli per odor at %.0f..%.0f Hz, noise %.0f%%, sniff %.0f ms%n",
                odors, variants, perOdor, minHz, maxHz, noise * 100, sniffMs);

        // odor = random glomeruli with rates minHz..maxHz; a variant jitters every rate
        SplittableRandom rnd = new SplittableRandom(42);
        double[][] odorRates = new double[odors][glom.size()];
        for (double[] rates : odorRates) {
            int[] chosen = rnd.ints(0, glom.size()).distinct().limit(perOdor).toArray();
            for (int c : chosen) {
                rates[c] = minHz + (maxHz - minHz) * rnd.nextDouble();
            }
        }

        LifParams params = LifParams.shiu2024();
        for (Connectome c : List.of(brain, brain.shuffledTargets(7))) {
            List<Sample> samples = simulate(c, params, glom, odorRates, variants, noise, sniffMs, readouts);
            report(c.name(), samples, odors, variants, readouts.keySet());
        }
        latency(brain, params, glom, odorRates[0], sniffMs);
    }

    static List<Sample> simulate(Connectome c, LifParams params, List<int[]> glom, double[][] odorRates, int variants,
                                 double noise, double sniffMs, Map<String, int[]> readouts) {
        ThreadLocal<LifSimulation> sims = ThreadLocal.withInitial(() -> new LifSimulation(c, params));
        int odors = odorRates.length;
        return IntStream.range(0, odors * variants).parallel().mapToObj(k -> {
            int odor = k / variants;
            int variant = k % variants;
            SplittableRandom r = new SplittableRandom(1_000_003L * odor + variant);
            double[] input = new double[glom.size()];
            Stimulus.Builder stim = Stimulus.builder();
            for (int gI = 0; gI < glom.size(); gI++) {
                double base = odorRates[odor][gI];
                if (base > 0) {
                    input[gI] = Math.max(0, base * (1 + noise * r.nextGaussian()));
                    stim.addAll(glom.get(gI), input[gI]);
                }
            }
            LifSimulation sim = sims.get();
            sim.setStimulus(stim.build());
            sim.reset(r.nextLong());
            long t = System.nanoTime();
            sim.run(sniffMs);
            double wall = (System.nanoTime() - t) / 1e6;
            Map<String, int[]> counts = new LinkedHashMap<>();
            readouts.forEach((name, idx) -> {
                int[] v = new int[idx.length];
                for (int i = 0; i < idx.length; i++) {
                    v[i] = sim.spikeCount(idx[i]);
                }
                counts.put(name, v);
            });
            return new Sample(odor, variant, input, counts, sim.totalSpikes(), wall);
        }).toList();
    }

    static void report(String name, List<Sample> samples, int odors, int variants, Iterable<String> readouts) {
        int train = variants / 2;
        System.out.printf("%n== %s ==%n", name);
        double meanSpikes = samples.stream().mapToLong(Sample::spikes).average().orElse(0);
        System.out.printf("spikes per sniff: %.0f%n", meanSpikes);
        System.out.printf("%-6s %8s %10s %10s%n", "layer", "accuracy", "active", "spikes");
        System.out.printf("%-6s %7.1f%%%n", "INPUT", 100 * accuracy(samples, s -> s.input(), odors, train));
        for (String layer : readouts) {
            double acc = accuracy(samples, s -> toDouble(s.counts().get(layer)), odors, train);
            double active = samples.stream().mapToDouble(s -> fractionActive(s.counts().get(layer))).average().orElse(0);
            double spikes = samples.stream().mapToDouble(s -> Arrays.stream(s.counts().get(layer)).sum()).average().orElse(0);
            System.out.printf("%-6s %7.1f%% %9.1f%% %10.0f%n", layer, 100 * acc, 100 * active, spikes);
        }
    }

    /** Nearest centroid by cosine similarity: first half of variants trains, second half tests. */
    static double accuracy(List<Sample> samples, java.util.function.Function<Sample, double[]> features, int odors, int train) {
        int dim = features.apply(samples.getFirst()).length;
        double[][] centroid = new double[odors][dim];
        for (Sample s : samples) {
            if (s.variant() < train) {
                double[] f = normalize(features.apply(s));
                for (int i = 0; i < dim; i++) {
                    centroid[s.odor()][i] += f[i];
                }
            }
        }
        int correct = 0;
        int total = 0;
        for (Sample s : samples) {
            if (s.variant() >= train) {
                double[] f = normalize(features.apply(s));
                int best = -1;
                double bestSim = Double.NEGATIVE_INFINITY;
                for (int o = 0; o < odors; o++) {
                    double sim = dot(f, normalize(centroid[o]));
                    if (sim > bestSim) {
                        bestSim = sim;
                        best = o;
                    }
                }
                correct += best == s.odor() ? 1 : 0;
                total++;
            }
        }
        return (double) correct / total;
    }

    static void latency(Connectome c, LifParams params, List<int[]> glom, double[] rates, double sniffMs) {
        LifSimulation sim = new LifSimulation(c, params);
        Stimulus.Builder stim = Stimulus.builder();
        for (int gI = 0; gI < glom.size(); gI++) {
            stim.addAll(glom.get(gI), rates[gI]);
        }
        sim.setStimulus(stim.build());
        double[] wall = new double[30];
        long updates = 0;
        long events = 0;
        for (int i = 0; i < wall.length; i++) {
            sim.reset(i);
            long t = System.nanoTime();
            sim.run(sniffMs);
            wall[i] = (System.nanoTime() - t) / 1e6;
            updates += sim.neuronUpdates();
            events += sim.synapticEvents();
        }
        Arrays.sort(wall);
        System.out.printf("%nSingle-thread sniff of %.0f ms brain time: median %.1f ms, p90 %.1f ms, "
                        + "mean active neurons %.0f, synaptic events %.1fM%n",
                sniffMs, wall[wall.length / 2], wall[(int) (wall.length * 0.9)],
                (double) updates / wall.length / Math.round(sniffMs / params.dtMs()), events / 1e6 / wall.length);
    }

    static double[] toDouble(int[] v) {
        return Arrays.stream(v).asDoubleStream().toArray();
    }

    static double fractionActive(int[] v) {
        return v.length == 0 ? 0 : (double) Arrays.stream(v).filter(x -> x > 0).count() / v.length;
    }

    static double[] normalize(double[] v) {
        double norm = Math.sqrt(dot(v, v));
        return norm == 0 ? v.clone() : Arrays.stream(v).map(x -> x / norm).toArray();
    }

    static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
