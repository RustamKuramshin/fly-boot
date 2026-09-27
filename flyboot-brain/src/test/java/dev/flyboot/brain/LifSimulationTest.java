package dev.flyboot.brain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifSimulationTest {

    /** 0 -> 1 with the given signed synapse count. */
    private static Connectome pair(int synapses) {
        return new Connectome("pair", new long[]{10, 11}, new int[]{0, 1, 1}, new int[]{1}, new short[]{(short) synapses});
    }

    @Test
    void brainWithoutInputStaysSilent() {
        LifSimulation sim = new LifSimulation(randomGraph(500, 20, 1), LifParams.shiu2024());
        sim.run(100);
        assertEquals(0, sim.totalSpikes());
        assertEquals(0, sim.neuronUpdates());
    }

    @Test
    void postsynapticPotentialMatchesAnalyticSolution() {
        LifParams p = LifParams.shiu2024().withRestEpsilon(0);
        LifSimulation sim = new LifSimulation(pair(1), p);
        sim.setStimulus(Stimulus.builder().add(0, 1e7).build()); // p >= 1: a kick on the first step
        sim.reset(0);
        sim.step(); // step 0: kick
        sim.setStimulus(Stimulus.NONE);
        int delay = (int) Math.round(p.delayMs() / p.dtMs());
        for (int s = 1; s <= delay + 1; s++) {
            sim.step(); // step 1: neuron 0 spikes; step 1 + delay: g of neuron 1 gets w after the state update
        }
        assertEquals(1, sim.spikeCount(0));
        // u(t) = w * ts / (ts - tm) * (exp(-t/ts) - exp(-t/tm)) after the synaptic event
        double w = p.wSynMv();
        double tm = p.tauMembraneMs();
        double ts = p.tauSynapseMs();
        double peak = 0;
        for (int s = 0; s <= 200; s++) {
            double t = s * p.dtMs();
            double expected = w * ts / (ts - tm) * (Math.exp(-t / ts) - Math.exp(-t / tm));
            peak = Math.max(peak, expected);
            assertEquals(expected, sim.voltage(1) - p.vRestMv(), 1e-7, "at " + t + " ms"); // float state
            sim.step();
        }
        assertEquals(0.043, peak, 0.001); // ~0.157 * w_syn, as derived from Shiu et al.
    }

    @Test
    void spikeArrivesAfterSynapticDelay() {
        LifParams p = LifParams.shiu2024();
        LifSimulation sim = new LifSimulation(pair(300), p); // 300 synapses: enough to fire neuron 1
        sim.setStimulus(Stimulus.builder().add(0, 1e7).build());
        sim.reset(0);
        sim.step();
        sim.setStimulus(Stimulus.NONE);
        int firstSpikeOf1 = -1;
        for (int s = 1; s < 100 && firstSpikeOf1 < 0; s++) {
            sim.step();
            if (sim.spikeCount(1) > 0) {
                firstSpikeOf1 = s;
            }
        }
        int delay = (int) Math.round(p.delayMs() / p.dtMs());
        assertTrue(firstSpikeOf1 > 1 + delay, "neuron 1 fired at step " + firstSpikeOf1);
    }

    @Test
    void inputDuringRefractoryPeriodIsDropped() {
        // Two presynaptic spikes 1 step apart; the first fires neuron 2 (strong edge), the second arrives
        // while neuron 2 is refractory and must not change g (Brian2 conditional write).
        Connectome c = new Connectome("refr", new long[]{1, 2, 3}, new int[]{0, 1, 2, 2}, new int[]{2, 2},
                new short[]{2000, 2000});
        LifParams p = LifParams.shiu2024().withRestEpsilon(0);
        LifSimulation sim = new LifSimulation(c, p);
        sim.setStimulus(Stimulus.builder().add(0, 1e7).build());
        sim.reset(0);
        sim.step();
        sim.setStimulus(Stimulus.builder().add(1, 1e7).build());
        sim.step();
        sim.setStimulus(Stimulus.NONE);
        for (int s = 0; s < 60; s++) {
            sim.step();
        }
        assertEquals(1, sim.spikeCount(2), "second volley hit the refractory period");
    }

    @Test
    void activeListGivesSameSpikesAsDenseUpdate() {
        Connectome c = randomGraph(2000, 40, 3);
        Stimulus stim = Stimulus.builder().addAll(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, 120).build();
        LifSimulation dense = new LifSimulation(c, LifParams.shiu2024().withRestEpsilon(0));
        LifSimulation sparse = new LifSimulation(c, LifParams.shiu2024().withRestEpsilon(1e-9));
        for (LifSimulation sim : new LifSimulation[]{dense, sparse}) {
            sim.setStimulus(stim);
            sim.reset(99);
            sim.run(100);
        }
        assertTrue(dense.totalSpikes() > 100, "network should be active: " + dense.totalSpikes());
        assertArrayEquals(dense.spikeCounts(), sparse.spikeCounts());
        assertTrue(sparse.neuronUpdates() < dense.neuronUpdates());
    }

    @Test
    void recordingListsEverySpikeInStepOrder() {
        Connectome c = randomGraph(2000, 40, 3);
        LifSimulation sim = new LifSimulation(c, LifParams.shiu2024());
        sim.setStimulus(Stimulus.builder().addAll(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, 120).build());
        sim.reset(5);
        sim.run(20);
        assertEquals(null, sim.recording(), "off by default");
        sim.setRecording(true);
        sim.reset(5);
        sim.run(50);
        SpikeRecording rec = sim.recording();
        assertEquals(sim.totalSpikes(), rec.size());
        int[] perNeuron = new int[c.neuronCount()];
        for (int i = 0; i < rec.size(); i++) {
            perNeuron[rec.neurons()[i]]++;
            assertTrue(i == 0 || rec.steps()[i] >= rec.steps()[i - 1]);
        }
        assertArrayEquals(sim.spikeCounts(), perNeuron);
        assertEquals(0.1, rec.dtMs(), 1e-12);
    }

    @Test
    void enginesAreFoundByNameWithAClearErrorOtherwise() {
        assertEquals("cpu", SimulationEngine.byName("CPU").name());
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SimulationEngine.byName("quantum"));
        assertTrue(e.getMessage().contains("available: cpu"), e.getMessage());
    }

    @Test
    void poolNeverGrowsMoreFliesThanTheEngineAllows() {
        SimulationEngine one = new SimulationEngine() {
            @Override
            public String name() {
                return "one";
            }

            @Override
            public int maxFlies() {
                return 1;
            }

            @Override
            public FlySimulation create(Connectome connectome, LifParams params) {
                return new LifSimulation(connectome, params);
            }
        };
        Brain brain = SyntheticBrain.create(300, 2);
        assertEquals(1, brain.pool(8, 10, java.util.List.of(BrainAtlas.KC), one).size());
        assertEquals(8, brain.pool(8, 10, java.util.List.of(BrainAtlas.KC)).size());
    }

    @Test
    void connectomeRoundTripsAndShuffleKeepsDegrees() throws Exception {
        Connectome c = randomGraph(300, 15, 5);
        Path file = Files.createTempFile("brain", ".flyb");
        try {
            c.write(file);
            Connectome read = Connectome.read(file);
            assertArrayEquals(c.rowPtr(), read.rowPtr());
            assertArrayEquals(c.post(), read.post());
            assertArrayEquals(c.weight(), read.weight());
            assertEquals(c.rootId(42), read.rootId(42));
            assertEquals(42, read.indexOf(c.rootId(42)));
        } finally {
            Files.deleteIfExists(file);
        }
        Connectome shuffled = c.shuffledTargets(1);
        assertArrayEquals(c.rowPtr(), shuffled.rowPtr());
        int[] inBefore = inDegree(c);
        int[] inAfter = inDegree(shuffled);
        assertArrayEquals(inBefore, inAfter);
        assertTrue(!Arrays.equals(c.post(), shuffled.post()));
    }

    private static int[] inDegree(Connectome c) {
        int[] d = new int[c.neuronCount()];
        for (int j : c.post()) {
            d[j]++;
        }
        return d;
    }

    /** Random graph, 80% excitatory neurons, 1..40 synapses per edge. */
    static Connectome randomGraph(int n, int outDegree, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        long[] ids = new long[n];
        int[] rowPtr = new int[n + 1];
        int[] post = new int[n * outDegree];
        short[] w = new short[n * outDegree];
        for (int i = 0; i < n; i++) {
            ids[i] = 1000 + i;
            int sign = r.nextDouble() < 0.8 ? 1 : -1;
            int[] targets = r.ints(0, n).filter(j -> j != 0).distinct().limit(outDegree).sorted().toArray();
            for (int k = 0; k < outDegree; k++) {
                post[i * outDegree + k] = targets[k];
                w[i * outDegree + k] = (short) (sign * r.nextInt(1, 41));
            }
            rowPtr[i + 1] = rowPtr[i] + outDegree;
        }
        return new Connectome("random", ids, rowPtr, post, w);
    }
}
