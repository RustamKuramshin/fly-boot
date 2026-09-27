package dev.flyboot.brain.learn;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionErrorBodyTest {

    static final int CELLS = 400;

    /** Route r activates its own 30 prototype cells, each kept with probability 0.7, plus 10 random cells. */
    static SparseCode noisy(int[][] prototypes, int route, SplittableRandom rnd) {
        int[] counts = new int[CELLS];
        for (int c : prototypes[route]) {
            if (rnd.nextDouble() < 0.7) {
                counts[c] = 1 + rnd.nextInt(5);
            }
        }
        for (int i = 0; i < 10; i++) {
            counts[rnd.nextInt(CELLS)] += 1 + rnd.nextInt(5);
        }
        return SparseCode.of(counts, 40, false);
    }

    static SparseCode random(SplittableRandom rnd) {
        int[] counts = new int[CELLS];
        for (int i = 0; i < 30; i++) {
            counts[rnd.nextInt(CELLS)] += 1 + rnd.nextInt(5);
        }
        return SparseCode.of(counts, 40, false);
    }

    @Test
    void learnsRoutesAndTheUnknownSmell() throws Exception {
        SplittableRandom rnd = new SplittableRandom(9);
        int routes = 6;
        int[][] prototypes = new int[routes][];
        for (int r = 0; r < routes; r++) {
            prototypes[r] = rnd.ints(0, CELLS).distinct().limit(30).toArray();
        }
        List<SparseCode> codes = new ArrayList<>();
        int[] labels = new int[routes * 40];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = i % routes;
            codes.add(noisy(prototypes, labels[i], rnd));
        }
        List<SparseCode> decoys = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            decoys.add(random(rnd));
        }
        PredictionErrorBody body = new PredictionErrorBody(List.of("a", "b", "c", "d", "e", "f"), CELLS, 0.3);
        body.train(codes, labels, decoys, 60, 5, 0, 1);

        int correct = 0;
        int rejected = 0;
        for (int i = 0; i < 300; i++) {
            correct += body.decide(noisy(prototypes, i % routes, rnd)).route() == i % routes ? 1 : 0;
            rejected += body.decide(random(rnd)).recognized() ? 0 : 1;
        }
        assertTrue(correct >= 285, "accuracy " + correct + "/300");
        assertTrue(rejected >= 240, "rejected " + rejected + "/300");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        body.write(bytes, "fp");
        PredictionErrorBody copy = PredictionErrorBody.read(new ByteArrayInputStream(bytes.toByteArray()), "fp");
        SparseCode probe = noisy(prototypes, 2, rnd);
        assertEquals(body.decide(probe), copy.decide(probe));
        assertNull(PredictionErrorBody.read(new ByteArrayInputStream(bytes.toByteArray()), "other"));
    }

    @Test
    void emptyCodeIsNotRecognizedAsARoute() {
        PredictionErrorBody body = new PredictionErrorBody(List.of("a", "b"), 10, 0.6);
        assertTrue(!body.decide(new SparseCode(new int[0], new float[0])).recognized());
    }
}
