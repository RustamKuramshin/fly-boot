package dev.flyboot.lab;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.FlySimulation;
import dev.flyboot.brain.HttpNose;
import dev.flyboot.brain.SimulationEngine;
import dev.flyboot.brain.Stimulus;

import java.util.Arrays;
import java.util.Locale;

/**
 * CPU engine against another engine (default {@code tornado}) on the same brain: the sugar -> MN9 experiment of
 * Shiu et al. (rates of every neuron over trials) and the wall time of a request sniff.
 * <p>
 * Run under TornadoVM: {@code tornado -cp <classpath> dev.flyboot.lab.EngineCompare [engine] [trials]}.
 */
public final class EngineCompare {

    public static void main(String[] args) {
        String other = args.length > 0 ? args[0] : "tornado";
        int trials = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        Brain brain = Lab.brain();
        SimulationEngine cpu = SimulationEngine.cpu();
        SimulationEngine gpu = SimulationEngine.byName(other);
        System.out.printf("%s vs %s (%s) on %s%n", cpu.device(), gpu.name(), gpu.device(), brain.connectome().name());

        int[] sugar = Arrays.stream(SugarValidation.SUGAR_R).mapToInt(brain.connectome()::indexOf).filter(i -> i >= 0).toArray();
        SimulationEngine[] engines = {cpu, gpu};
        if (brain.connectome().indexOf(SugarValidation.MN9) >= 0) {
            sugar(brain, engines, sugar, trials);
        } else {
            System.out.println("No FlyWire sugar neurons in this brain: comparing sniffs only");
        }
        sniffs(brain, engines);
    }

    static void sugar(Brain brain, SimulationEngine[] engines, int[] sugar, int trials) {
        Stimulus stim = Stimulus.builder().addAll(sugar, 150).build();
        int mn9 = brain.connectome().indexOf(SugarValidation.MN9);
        double[][] rates = new double[2][];
        for (int e = 0; e < 2; e++) {
            FlySimulation fly = engines[e].create(brain.connectome(), brain.params());
            long[] total = new long[brain.connectome().neuronCount()];
            long t = System.nanoTime();
            for (int trial = 0; trial < trials; trial++) {
                fly.setStimulus(stim);
                fly.reset(1000 + trial);
                fly.run(1000);
                for (int i = 0; i < total.length; i++) {
                    total[i] += fly.spikeCount(i);
                }
            }
            double seconds = (System.nanoTime() - t) / 1e9;
            rates[e] = Arrays.stream(total).mapToDouble(c -> (double) c / trials).toArray();
            long active = Arrays.stream(total).filter(c -> c > 0).count();
            System.out.printf(Locale.ROOT, "%-8s sugar 150 Hz: MN9 %.1f Hz, active neurons %d, %.2f s per 1 s of brain time%n",
                    engines[e].name(), rates[e][mn9], active, seconds / trials);
        }
        System.out.printf(Locale.ROOT, "Pearson r of per-neuron rates: %.4f%n", pearson(rates[0], rates[1]));

    }

    static void sniffs(Brain brain, SimulationEngine[] engines) {
        for (SimulationEngine engine : engines) {
            FlySimulation fly = engine.create(brain.connectome(), brain.params());
            fly.setStimulus(Stimulus.NONE);
            fly.reset(0);
            fly.run(75);
            long t = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                fly.reset(i);
                fly.run(75);
            }
            System.out.printf(Locale.ROOT, "%-8s silent brain, 75 ms: %.1f ms (the engine's fixed cost)%n", engine.name(),
                    (System.nanoTime() - t) / 1e6 / 20);
        }

        HttpNose nose = new HttpNose(brain.atlas());
        Stimulus odor = brain.atlas().stimulus(nose.smell("GET", "/owners/42/pets"));
        for (SimulationEngine engine : engines) {
            FlySimulation fly = engine.create(brain.connectome(), brain.params());
            double[] wall = new double[30];
            long spikes = 0;
            for (int i = 0; i < wall.length; i++) {
                fly.setStimulus(odor);
                fly.reset(i);
                long t = System.nanoTime();
                fly.run(75);
                wall[i] = (System.nanoTime() - t) / 1e6;
                spikes += fly.totalSpikes();
            }
            Arrays.sort(wall);
            System.out.printf(Locale.ROOT, "%-8s request sniff of 75 ms: median %.1f ms, p90 %.1f ms, %d spikes on average%n",
                    engine.name(), wall[15], wall[27], spikes / wall.length);
        }
    }

    static double pearson(double[] a, double[] b) {
        double ma = Arrays.stream(a).average().orElse(0);
        double mb = Arrays.stream(b).average().orElse(0);
        double num = 0;
        double da = 0;
        double db = 0;
        for (int i = 0; i < a.length; i++) {
            num += (a[i] - ma) * (b[i] - mb);
            da += (a[i] - ma) * (a[i] - ma);
            db += (b[i] - mb) * (b[i] - mb);
        }
        return num / Math.sqrt(da * db);
    }
}
