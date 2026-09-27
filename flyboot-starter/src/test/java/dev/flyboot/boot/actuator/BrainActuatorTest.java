package dev.flyboot.boot.actuator;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.LifParams;
import dev.flyboot.brain.NeuronCatalog;
import dev.flyboot.brain.NeuronPositions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BrainActuatorTest {

    @Test
    void layoutProjectsTheFrontViewAndLabelsGroups() {
        Connectome c = new Connectome("tiny", new long[]{1, 2, 3, 4}, new int[]{0, 0, 0, 0, 0}, new int[0], new short[0]);
        NeuronCatalog catalog = new NeuronCatalog(List.of(
                new NeuronCatalog.Neuron(0, 1, "", "sensory", "olfactory", "", "", "ORN_DA1", ""),
                new NeuronCatalog.Neuron(1, 2, "", "central", "Kenyon_Cell", "", "", "KCg", ""),
                new NeuronCatalog.Neuron(2, 3, "", "descending", "", "", "", "DNa02", ""),
                new NeuronCatalog.Neuron(3, 4, "", "central", "", "", "", "", "")));
        NeuronPositions pos = NeuronPositions.unknown(4);
        pos.set(0, 100, 50, 7);
        pos.set(1, 300, 150, 7);
        pos.set(2, 200, 100, 7);
        Map<String, Object> layout = BrainActuator.computeLayout(new Brain(c, catalog, LifParams.shiu2024(), 3, pos));
        assertArrayEquals(new int[]{0, 1000, 500, -1}, (int[]) layout.get("x"));
        assertArrayEquals(new int[]{0, 500, 250, -1}, (int[]) layout.get("y"));
        assertEquals(501, layout.get("height"));
        assertEquals(3, layout.get("known"));
        List<String> groups = BrainActuator.GROUPS;
        int[] group = (int[]) layout.get("group");
        assertEquals("ORN", groups.get(group[0]));
        assertEquals("KC", groups.get(group[1]));
        assertEquals("DN", groups.get(group[2]));
        assertEquals("other", groups.get(group[3]));
        assertNotNull(BrainActuator.class.getResource("/flyboot/brain-ui.html"), "the page ships with the starter");
    }
}
