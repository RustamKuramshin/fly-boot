package dev.flyboot.lab;

import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.LifParams;
import dev.flyboot.brain.LifSimulation;
import dev.flyboot.brain.Stimulus;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Same experiment as tools/reference/sugar_reference.py: right-hemisphere sugar GRNs driven by Poisson input,
 * 1 s trials, mean rate of every neuron. Also checks that the brain is silent without input.
 * <p>
 * Usage: {@code SugarValidation [rateHz] [trials]}; writes tools/reference/results/java_sugar_<rate>Hz.csv.
 */
public final class SugarValidation {

    static final long[] SUGAR_R = {
            720575940624963786L, 720575940630233916L, 720575940637568838L, 720575940638202345L, 720575940617000768L,
            720575940630797113L, 720575940632889389L, 720575940621754367L, 720575940621502051L, 720575940640649691L,
            720575940639332736L, 720575940616885538L, 720575940639198653L, 720575940620900446L, 720575940617937543L,
            720575940632425919L, 720575940633143833L, 720575940612670570L, 720575940628853239L, 720575940629176663L,
            720575940611875570L,
    };
    static final long MN9 = 720575940660219265L;

    public static void main(String[] args) throws IOException {
        double rate = args.length > 0 ? Double.parseDouble(args[0]) : 150;
        int trials = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        Connectome brain = Lab.connectome();
        LifParams params = LifParams.shiu2024();

        int[] sugar = Arrays.stream(SUGAR_R).mapToInt(brain::indexOf).filter(i -> i >= 0).toArray();
        Stimulus stimulus = Stimulus.builder().addAll(sugar, rate).build();
        System.out.printf("Sugar GRNs present: %d of %d, rate %.0f Hz, trials %d%n", sugar.length, SUGAR_R.length, rate, trials);

        LifSimulation silent = new LifSimulation(brain, params);
        silent.run(200);
        System.out.printf("Silence check: %d spikes in 200 ms without input%n", silent.totalSpikes());

        long started = System.nanoTime();
        long[] total = new long[brain.neuronCount()];
        double[] wallMs = new double[trials];
        IntStream.range(0, trials).parallel().forEach(trial -> {
            LifSimulation sim = new LifSimulation(brain, params);
            sim.setStimulus(stimulus);
            sim.reset(1000 + trial);
            long t = System.nanoTime();
            sim.run(1000);
            wallMs[trial] = (System.nanoTime() - t) / 1e6;
            int[] counts = sim.spikeCounts();
            synchronized (total) {
                for (int i = 0; i < counts.length; i++) {
                    total[i] += counts[i];
                }
            }
        });
        double elapsed = (System.nanoTime() - started) / 1e9;

        Path out = Path.of("tools/reference/results/java_sugar_" + (int) rate + "Hz.csv");
        Files.createDirectories(out.getParent());
        int active = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("flywire_id,rate_hz");
            for (int i = 0; i < total.length; i++) {
                if (total[i] > 0) {
                    active++;
                    w.printf("%d,%s%n", brain.rootId(i), Double.toString((double) total[i] / trials));
                }
            }
        }
        double mn9 = (double) total[brain.indexOf(MN9)] / trials;
        System.out.printf("Active neurons: %d, MN9 rate: %.1f Hz -> %s%n", active, mn9, out);
        System.out.printf("Wall time: %.1f s total, per trial of 1 s brain time: median %.0f ms (%d threads)%n",
                elapsed, Arrays.stream(wallMs).sorted().toArray()[trials / 2], Runtime.getRuntime().availableProcessors());
    }
}
