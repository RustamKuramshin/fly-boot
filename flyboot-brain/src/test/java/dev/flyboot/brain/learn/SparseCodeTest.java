package dev.flyboot.brain.learn;

import dev.flyboot.brain.Perception;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SparseCodeTest {

    @Test
    void keepsTheMostActiveCellsWithDeterministicTies() {
        SparseCode code = SparseCode.of(new int[]{0, 5, 3, 5, 1, 0, 3}, 3, false);
        assertArrayEquals(new int[]{1, 2, 3}, code.cells()); // 5, 5, then the first of the two 3s
        assertArrayEquals(new float[]{5, 3, 5}, code.values());
        assertArrayEquals(new float[]{1, 1, 1}, SparseCode.of(new int[]{0, 5, 3, 5}, 3, true).values());
    }

    @Test
    void silentCellsAreNeverPartOfTheCode() {
        assertEquals(2, SparseCode.of(new int[]{0, 2, 0, 1, 0}, 4, true).size());
        assertEquals(0, SparseCode.of(new int[]{0, 0}, 2, true).size());
    }

    @Test
    void channelsAreConcatenatedWithOffsetsAndGains() {
        Perception p = new Perception(Map.of("A", new int[]{0, 3, 4}, "B", new int[]{1, 0}), 0, 0, 0, 0);
        SparseCode code = SparseCode.of(p, List.of(new CodeChannel("A", 2, false, 1), new CodeChannel("B", 1, true, 2)));
        assertArrayEquals(new int[]{1, 2, 3}, code.cells());
        assertEquals(0.6f, code.values()[0], 1e-6);
        assertEquals(0.8f, code.values()[1], 1e-6);
        assertEquals(2f, code.values()[2], 1e-6);
        assertEquals(5, SparseCode.inputSize(p, List.of(new CodeChannel("A", 2, false, 1), new CodeChannel("B", 1, true, 2))));
    }
}
