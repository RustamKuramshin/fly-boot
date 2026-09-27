package dev.flyboot.lab.bench;

import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.LifParams;
import dev.flyboot.brain.LifSimulation;
import dev.flyboot.brain.NeuronCatalog;
import dev.flyboot.brain.Stimulus;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One sniff of the whole FlyWire brain: ~18 glomeruli driven at 30-60 Hz, as a typical request odor.
 * Needs data/flywire-783.* (scripts/download-data.sh + ConvertFlyWire).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = "-Xmx4g")
public class SniffBenchmark {

    @State(Scope.Benchmark)
    public static class Brain {
        Connectome connectome;
        Stimulus odor;

        @Setup(Level.Trial)
        public void load() throws IOException {
            Path data = Path.of(System.getProperty("flyboot.data", "data"));
            connectome = Connectome.read(data.resolve("flywire-783.flyb"));
            NeuronCatalog catalog = NeuronCatalog.read(data.resolve("flywire-783.neurons.tsv"));
            Map<String, int[]> glomeruli = catalog.groupByType(n -> n.cellClass().equals("olfactory") && n.type().startsWith("ORN_"));
            Stimulus.Builder b = Stimulus.builder();
            int k = 0;
            for (int[] glom : glomeruli.values()) {
                if (k % 3 == 0) {
                    b.addAll(glom, 30 + (k % 2) * 30);
                }
                k++;
            }
            odor = b.build();
        }
    }

    @State(Scope.Thread)
    public static class Fly {
        @Param({"75"})
        double sniffMs;
        LifSimulation sim;
        long seed;

        @Setup(Level.Trial)
        public void create(Brain brain) {
            sim = new LifSimulation(brain.connectome, LifParams.shiu2024());
            sim.setStimulus(brain.odor);
        }
    }

    @Benchmark
    public long sniff(Fly fly) {
        fly.sim.reset(fly.seed++);
        fly.sim.run(fly.sniffMs);
        return fly.sim.totalSpikes();
    }
}
