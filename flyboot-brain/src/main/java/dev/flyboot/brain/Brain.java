package dev.flyboot.brain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * A connectome with its annotations and neuron model: everything needed to grow flies. Brains live in a data
 * directory as {@code <name>.flyb} + {@code <name>.neurons.tsv}, with optional per-brain tuning in
 * {@code <name>.brain.properties} ({@code neuron.wSynMv}, {@code router.aplGain}).
 */
public final class Brain {

    /** Files produced by {@code ConvertFlyWire}: {@code <name>.flyb} and {@code <name>.neurons.tsv}. */
    public static final String DEFAULT_NAME = "flywire-783";

    private final Connectome connectome;
    private final NeuronCatalog catalog;
    private final BrainAtlas atlas;
    private final LifParams params;
    private final double recommendedAplGain;
    private final NeuronPositions positions;

    public Brain(Connectome connectome, NeuronCatalog catalog, LifParams params) {
        this(connectome, catalog, params, 3);
    }

    /**
     * @param recommendedAplGain APL gain that sparsifies this brain's Kenyon cells to fly-like levels
     *                           (3 for FlyWire, phase 2); routers use it unless configured otherwise
     */
    public Brain(Connectome connectome, NeuronCatalog catalog, LifParams params, double recommendedAplGain) {
        this(connectome, catalog, params, recommendedAplGain, NeuronPositions.unknown(connectome.neuronCount()));
    }

    public Brain(Connectome connectome, NeuronCatalog catalog, LifParams params, double recommendedAplGain,
                 NeuronPositions positions) {
        if (connectome.neuronCount() != catalog.size()) {
            throw new IllegalArgumentException("Connectome has " + connectome.neuronCount()
                    + " neurons but the catalog describes " + catalog.size());
        }
        this.connectome = connectome;
        this.catalog = catalog;
        this.atlas = BrainAtlas.of(catalog);
        this.params = params;
        this.recommendedAplGain = recommendedAplGain;
        this.positions = positions;
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
        Properties tuning = new Properties();
        Path tuningFile = dataDir.resolve(name + ".brain.properties");
        if (Files.exists(tuningFile)) {
            try (var in = Files.newBufferedReader(tuningFile)) {
                tuning.load(in);
            }
        }
        LifParams params = LifParams.shiu2024()
                .withWSyn(Double.parseDouble(tuning.getProperty("neuron.wSynMv", "0.275")));
        double aplGain = Double.parseDouble(tuning.getProperty("router.aplGain", "3"));
        Path positionsFile = dataDir.resolve(name + ".positions.bin");
        Connectome connectome = Connectome.read(flyb);
        NeuronPositions positions = Files.exists(positionsFile) ? NeuronPositions.read(positionsFile)
                : NeuronPositions.unknown(connectome.neuronCount());
        return new Brain(connectome, NeuronCatalog.read(tsv), params, aplGain, positions);
    }

    /** Brains converted into {@code dataDir}: names of {@code *.flyb} files that have annotations next to them. */
    public static List<String> available(Path dataDir) throws IOException {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        try (var files = Files.list(dataDir)) {
            return files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".flyb"))
                    .map(f -> f.substring(0, f.length() - 5))
                    .filter(n -> Files.exists(dataDir.resolve(n + ".neurons.tsv"))).sorted().toList();
        }
    }

    /** Neuron positions for drawing; all unknown when the brain has no {@code .positions.bin}. */
    public NeuronPositions positions() {
        return positions;
    }

    public double recommendedAplGain() {
        return recommendedAplGain;
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
        return new Brain(connectome, catalog, next, recommendedAplGain, positions);
    }

    /** Control brain with the same neurons and degrees but randomly rewired, see {@link Connectome#shuffledTargets}. */
    public Brain shuffled(long seed) {
        return new Brain(connectome.shuffledTargets(seed), catalog, params, recommendedAplGain, positions);
    }

    /** Brain whose APL outputs are {@code factor} times stronger, see {@link Connectome#withScaledOutputs}. */
    public Brain withAplGain(double factor) {
        if (factor == 1) {
            return this;
        }
        return new Brain(connectome.withScaledOutputs(atlas.population(BrainAtlas.APL), factor), catalog, params,
                recommendedAplGain, positions);
    }

    public BrainPool pool(int flies, double sniffMs, List<String> readouts) {
        return new BrainPool(this, flies, sniffMs, readouts);
    }

    public BrainPool pool(int flies, double sniffMs, List<String> readouts, SimulationEngine engine) {
        return new BrainPool(this, flies, sniffMs, readouts, engine);
    }
}
