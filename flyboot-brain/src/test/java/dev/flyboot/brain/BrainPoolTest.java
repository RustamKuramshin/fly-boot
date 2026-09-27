package dev.flyboot.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainPoolTest {

    private final Brain brain = SyntheticBrain.create(1500, 11);
    private final HttpNose nose = new HttpNose(brain.atlas());

    @Test
    void sameOdorAndSeedGiveTheSamePerception() {
        BrainPool pool = brain.pool(2, 50, List.of(BrainAtlas.KC));
        Odor odor = nose.smell("GET", "/pets/42");
        Perception a = pool.sniff(odor, 7);
        pool.sniff(nose.smell("POST", "/owners"), 8); // the fly must be fully reset in between
        Perception b = pool.sniff(odor, 7);
        assertTrue(a.totalSpikes() > 0, "the synthetic brain should respond");
        assertEquals(a.totalSpikes(), b.totalSpikes());
        assertArrayEquals(a.counts(BrainAtlas.KC), b.counts(BrainAtlas.KC));
        assertEquals(50, a.brainMs(), 1e-9);
        assertTrue(a.totalSpikes() != pool.sniff(odor, 8).totalSpikes() || a.synapticEvents() > 0);
    }

    @Test
    void concurrentSniffsMatchSequentialOnes() throws Exception {
        BrainPool pool = brain.pool(3, 30, List.of(BrainAtlas.KC));
        List<Odor> odors = IntStream.range(0, 24).mapToObj(i -> nose.smell(i % 2 == 0 ? "GET" : "PUT", "/pets/" + i)).toList();
        List<Perception> sequential = odors.stream().map(o -> pool.sniff(o, 1)).toList();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<Perception>> futures = odors.stream().map(o -> executor.submit(() -> pool.sniff(o, 1))).toList();
            for (int i = 0; i < odors.size(); i++) {
                assertArrayEquals(sequential.get(i).counts(BrainAtlas.KC), futures.get(i).get().counts(BrainAtlas.KC), "odor " + i);
            }
        }
    }

    @Test
    void unknownReadoutIsRejectedUpFront() {
        assertThrows(IllegalArgumentException.class, () -> brain.pool(1, 10, List.of("VISUAL_CORTEX")));
        assertThrows(IllegalArgumentException.class, () -> brain.pool(0, 10, List.of(BrainAtlas.KC)));
    }

    @Test
    void odorWithGlomerulusAbsentFromBrainIsRejected() {
        BrainPool pool = brain.pool(1, 10, List.of(BrainAtlas.KC));
        Odor alien = new Odor(java.util.Map.of("ORN_UNKNOWN", 50.0));
        assertThrows(IllegalArgumentException.class, () -> pool.sniff(alien, 1));
    }
}
