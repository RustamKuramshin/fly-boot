package dev.flyboot.brain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/** A connectome with its annotations and neuron model: everything needed to grow flies. */
public final class Brain {

    /** Files produced by {@code ConvertFlyWire}: {@code <name>.flyb} and {@code <name>.neurons.tsv}. */
    public static final String DEFAULT_NAME = "flywire-783";

    private final Connectome connectome;
    private final NeuronCatalog catalog;
    private final BrainAtlas atlas;
    private final LifParams params;

    public Brain(Connectome connectome, NeuronCatalog catalog, LifParams params) {
        if (connectome.neuronCount() != catalog.size()) {
            throw new IllegalArgumentException("Connectome has " + connectome.neuronCount()
                    + " neurons but the catalog describes " + catalog.size());
        }
        this.connectome = connectome;
        this.catalog = catalog;
        this.atlas = BrainAtlas.of(catalog);
        this.params = params;
    }

    public static Brain load(Path dataDir) throws IOException {
        return load(dataDir, DEFAULT_NAME);
    }

    public static Brain load(Path dataDir, String name) throws IOException {
        Path flyb = dataDir.resolve(name + ".flyb");
        Path tsv = dataDir.resolve(name + ".neurons.tsv");
        if (!Files.exists(flyb) || !Files.exists(tsv)) {
            throw new NoSuchFileException(flyb + " or " + tsv,
                    null, "brain data not found: run scripts/download-data.sh and ConvertFlyWire");
        }
        return new Brain(Connectome.read(flyb), NeuronCatalog.read(tsv), LifParams.shiu2024());
    }

    public Connectome connectome() {
        return connectome;
    }

    public NeuronCatalog catalog() {
        return catalog;
    }

    public BrainAtlas atlas() {
        return atlas;
    }

    public LifParams params() {
        return params;
    }

    public Brain withParams(LifParams next) {
        return new Brain(connectome, catalog, next);
    }

    /** Control brain with the same neurons and degrees but randomly rewired, see {@link Connectome#shuffledTargets}. */
    public Brain shuffled(long seed) {
        return new Brain(connectome.shuffledTargets(seed), catalog, params);
    }

    /** Brain whose APL outputs are {@code factor} times stronger, see {@link Connectome#withScaledOutputs}. */
    public Brain withAplGain(double factor) {
        if (factor == 1) {
            return this;
        }
        return new Brain(connectome.withScaledOutputs(atlas.population(BrainAtlas.APL), factor), catalog, params);
    }

    public BrainPool pool(int flies, double sniffMs, List<String> readouts) {
        return new BrainPool(this, flies, sniffMs, readouts);
    }
}
