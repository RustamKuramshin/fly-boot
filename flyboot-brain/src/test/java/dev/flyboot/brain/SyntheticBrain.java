package dev.flyboot.brain;

import java.util.ArrayList;
import java.util.List;

/** Small random brain with FlyWire-like annotations: 15 glomeruli of 4 receptors, 40 KCs, the rest central. */
final class SyntheticBrain {

    static final String[] GLOMERULI = {
            "ORN_DA1", "ORN_VA1v", "ORN_VA1d", "ORN_DL3", "ORN_DC3",
            "ORN_DM1", "ORN_DM2", "ORN_DM3", "ORN_DL1", "ORN_DL2d", "ORN_VM1", "ORN_VM2", "ORN_VC1", "ORN_VC2", "ORN_VA2",
    };

    private SyntheticBrain() {
    }

    static Brain create(int neurons, long seed) {
        Connectome c = LifSimulationTest.randomGraph(neurons, 30, seed);
        List<NeuronCatalog.Neuron> list = new ArrayList<>();
        for (int i = 0; i < neurons; i++) {
            String cls = "";
            String sub = "";
            String type = "";
            String superClass = "central";
            if (i < GLOMERULI.length * 4) {
                type = GLOMERULI[i / 4];
                cls = "olfactory";
                superClass = "sensory";
                sub = i / 4 < 5 ? "pheromone" : "";
            } else if (i < GLOMERULI.length * 4 + 40) {
                cls = "Kenyon_Cell";
                type = "KCg-m";
            }
            list.add(new NeuronCatalog.Neuron(i, c.rootId(i), "", superClass, cls, sub, "right", type, "ACH"));
        }
        return new Brain(c, new NeuronCatalog(list), LifParams.shiu2024());
    }
}
