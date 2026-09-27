package dev.flyboot.brain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Named neuron populations of the brain, selected by FlyWire annotations: olfactory glomeruli as sensory
 * channels, and the layers of the olfactory pathway and beyond as readouts.
 */
public final class BrainAtlas {

    public static final String ALPN = "ALPN";
    public static final String KC = "KC";
    public static final String LHN = "LHN";
    public static final String MBON = "MBON";
    public static final String DAN = "DAN";
    public static final String APL = "APL";
    public static final String DN = "DN";

    private final Map<String, int[]> glomeruli;
    private final Set<String> pheromoneGlomeruli;
    private final Map<String, int[]> populations;

    private BrainAtlas(Map<String, int[]> glomeruli, Set<String> pheromoneGlomeruli, Map<String, int[]> populations) {
        this.glomeruli = Collections.unmodifiableMap(glomeruli);
        this.pheromoneGlomeruli = Collections.unmodifiableSet(pheromoneGlomeruli);
        this.populations = Collections.unmodifiableMap(populations);
    }

    public static BrainAtlas of(NeuronCatalog catalog) {
        Map<String, int[]> glomeruli = catalog.groupByType(n -> n.cellClass().equals("olfactory") && n.type().startsWith("ORN_"));
        Set<String> pheromone = new TreeSet<>();
        for (int i = 0; i < catalog.size(); i++) {
            NeuronCatalog.Neuron n = catalog.get(i);
            if (n.subClass().equals("pheromone") && glomeruli.containsKey(n.type())) {
                pheromone.add(n.type());
            }
        }
        Map<String, int[]> populations = new LinkedHashMap<>();
        populations.put(ALPN, catalog.select(n -> n.cellClass().equals("ALPN")));
        populations.put(KC, catalog.select(n -> n.cellClass().equals("Kenyon_Cell")));
        populations.put(LHN, catalog.select(n -> n.cellClass().equals("LHLN") || n.cellClass().equals("LHCENT")));
        populations.put(MBON, catalog.select(n -> n.cellClass().equals("MBON")));
        populations.put(DAN, catalog.select(n -> n.cellClass().equals("DAN")));
        populations.put(APL, catalog.select(n -> n.type().equals("APL")));
        populations.put(DN, catalog.select(n -> n.superClass().equals("descending")));
        return new BrainAtlas(glomeruli, pheromone, populations);
    }

    /** Olfactory receptor neurons by glomerulus type, e.g. {@code ORN_DA1}, sorted by name. */
    public Map<String, int[]> glomeruli() {
        return glomeruli;
    }

    public List<String> glomerulusTypes() {
        return List.copyOf(glomeruli.keySet());
    }

    /** Glomeruli whose receptors are annotated as pheromone-sensing (DA1, DC3, DL3, VA1d, VA1v in FlyWire). */
    public Set<String> pheromoneGlomeruli() {
        return pheromoneGlomeruli;
    }

    public int[] population(String name) {
        int[] neurons = populations.get(name);
        if (neurons == null) {
            throw new IllegalArgumentException("Unknown population " + name + ", known: " + populations.keySet());
        }
        return neurons;
    }

    public Map<String, int[]> populations() {
        return populations;
    }

    /** Poisson drive of every receptor neuron of each glomerulus at the odor's rate for that glomerulus. */
    public Stimulus stimulus(Odor odor) {
        Stimulus.Builder builder = Stimulus.builder();
        odor.ratesHz().forEach((type, rate) -> {
            int[] receptors = glomeruli.get(type);
            if (receptors == null) {
                throw new IllegalArgumentException("Odor uses glomerulus " + type + " absent from this brain");
            }
            builder.addAll(receptors, rate);
        });
        return builder.build();
    }
}
