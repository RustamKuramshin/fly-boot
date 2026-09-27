package dev.flyboot.brain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs on the real FlyWire v783 brain when data/ is present; skipped otherwise. */
class FlyWireBrainTest {

    private static Brain brain;

    @BeforeAll
    static void load() throws Exception {
        Path data = Path.of(System.getProperty("flyboot.data", "../data"));
        assumeTrue(Files.exists(data.resolve(Brain.DEFAULT_NAME + ".flyb")), "no brain data in " + data.toAbsolutePath());
        brain = Brain.load(data);
    }

    @Test
    void atlasFindsTheOlfactoryPathway() {
        BrainAtlas atlas = brain.atlas();
        assertEquals(53, atlas.glomeruli().size());
        assertEquals(java.util.Set.of("ORN_DA1", "ORN_DC3", "ORN_DL3", "ORN_VA1d", "ORN_VA1v"), atlas.pheromoneGlomeruli());
        assertEquals(685, atlas.population(BrainAtlas.ALPN).length);
        assertEquals(5177, atlas.population(BrainAtlas.KC).length);
        assertEquals(96, atlas.population(BrainAtlas.MBON).length);
        assertEquals(2, atlas.population(BrainAtlas.APL).length);
    }

    @Test
    void requestOdorReachesKenyonCellsDeterministically() {
        BrainPool pool = brain.pool(2, 75, List.of(BrainAtlas.ALPN, BrainAtlas.KC));
        Odor odor = new HttpNose(brain.atlas()).smell("GET", "/owners/7/pets/42");
        Perception a = pool.sniff(odor, Hashing.hash64("GET /owners/7/pets/42"));
        Perception b = pool.sniff(odor, Hashing.hash64("GET /owners/7/pets/42"));
        assertArrayEquals(a.counts(BrainAtlas.KC), b.counts(BrainAtlas.KC));
        assertTrue(Arrays.stream(a.counts(BrainAtlas.KC)).sum() > 100, "KC spikes: " + Arrays.stream(a.counts(BrainAtlas.KC)).sum());
        assertTrue(Arrays.stream(a.counts(BrainAtlas.ALPN)).sum() > 100);
    }

    @Test
    void flyWireKnowsWhereItsNeuronsAre() {
        NeuronPositions p = brain.positions();
        assertEquals(brain.connectome().neuronCount(), p.size());
        assertTrue(p.known(0) || p.known(1));
        assertEquals(3.0, brain.recommendedAplGain());
    }

    @Test
    void maleCnsLoadsWithItsOwnTuning() throws Exception {
        Path data = Path.of(System.getProperty("flyboot.data", "../data"));
        assumeTrue(Files.exists(data.resolve("malecns-v1.flyb")), "no MaleCNS in " + data.toAbsolutePath());
        Brain male = Brain.load(data, "malecns-v1");
        assertEquals(166_700, male.connectome().neuronCount());
        assertEquals(0.175, male.params().wSynMv(), 1e-12);
        assertEquals(1.6, male.recommendedAplGain());
        assertEquals(53, male.atlas().glomeruli().size());
        assertEquals(java.util.Set.of("ORN_DA1", "ORN_DC3", "ORN_DL3", "ORN_VA1d", "ORN_VA1v"), male.atlas().pheromoneGlomeruli());
        assertEquals(4064, male.atlas().population(BrainAtlas.KC).length);
        assertEquals(2, male.atlas().population(BrainAtlas.APL).length);
        assertTrue(male.atlas().population(BrainAtlas.DN).length > 1000);
        assertTrue(Brain.available(data).containsAll(List.of("flywire-783", "malecns-v1")));
    }
}
