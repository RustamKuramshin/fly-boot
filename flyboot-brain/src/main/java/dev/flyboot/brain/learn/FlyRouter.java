package dev.flyboot.brain.learn;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.BrainAtlas;
import dev.flyboot.brain.BrainPool;
import dev.flyboot.brain.Hashing;
import dev.flyboot.brain.HttpNose;
import dev.flyboot.brain.Perception;
import dev.flyboot.brain.SimulationEngine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Routes HTTP requests with a fly brain. The fly learns the endpoints once: it sniffs synthetic requests to every
 * endpoint and decoys that hit none, and dopamine prediction errors shape the synapses onto its route neurons.
 * Afterwards every request is sniffed and the most confident route neuron decides; if the "unknown smell" neuron
 * wins or the belief is too low, the fly does not recognize the request.
 * <p>
 * Templates are only used to synthesize training requests; routing never matches them.
 */
public final class FlyRouter {

    /**
     * @param sniffMs         brain time per request
     * @param aplGain         strength of the APL feedback inhibition that sparsifies Kenyon cells (phase 2: 3)
     * @param channels        populations feeding the route neurons
     * @param flies           concurrent simulations, 0 for the number of processors
     * @param samplesPerRoute training requests per endpoint
     * @param decoysPerRoute  training decoys per endpoint
     * @param holdoutPerRoute requests per endpoint with unseen values, only for the accuracy metric
     * @param epochs          passes of prediction-error learning
     * @param learningRate    learning rate
     * @param minBelief       belief the winning route neuron needs
     * @param seed            seed of the request generator
     * @param engine          simulation engine, {@code cpu} or another {@link SimulationEngine} on the classpath
     */
    public record Options(double sniffMs, double aplGain, List<CodeChannel> channels, int flies, int samplesPerRoute,
                          int decoysPerRoute, int holdoutPerRoute, int epochs, double learningRate, double minBelief,
                          long seed, String engine) {

        public Options {
            channels = List.copyOf(channels);
        }

        /** Options on the CPU engine. */
        public Options(double sniffMs, double aplGain, List<CodeChannel> channels, int flies, int samplesPerRoute,
                       int decoysPerRoute, int holdoutPerRoute, int epochs, double learningRate, double minBelief,
                       long seed) {
            this(sniffMs, aplGain, channels, flies, samplesPerRoute, decoysPerRoute, holdoutPerRoute, epochs,
                    learningRate, minBelief, seed, SimulationEngine.CPU);
        }

        public Options withEngine(String name) {
            return new Options(sniffMs, aplGain, channels, flies, samplesPerRoute, decoysPerRoute, holdoutPerRoute,
                    epochs, learningRate, minBelief, seed, name);
        }

        /** Chosen in phase 2, see README.md. */
        public static Options defaults() {
            return new Options(75, 3, List.of(new CodeChannel(BrainAtlas.KC, 520, true, 1),
                    new CodeChannel(BrainAtlas.ALPN, 685, false, 1)), 0, 60, 30, 10, 100, 5, 0.3, 2026);
        }

        /** Defaults with the APL gain recommended for this brain (its {@code .brain.properties}). */
        public static Options defaultsFor(Brain brain) {
            return defaults().withAplGain(brain.recommendedAplGain());
        }

        public Options withAplGain(double gain) {
            return new Options(sniffMs, gain, channels, flies, samplesPerRoute, decoysPerRoute, holdoutPerRoute,
                    epochs, learningRate, minBelief, seed, engine);
        }

        public Options withSamples(int samplesPerRoute, int decoysPerRoute, int holdoutPerRoute) {
            return new Options(sniffMs, aplGain, channels, flies, samplesPerRoute, decoysPerRoute, holdoutPerRoute,
                    epochs, learningRate, minBelief, seed, engine);
        }

        public Options withFlies(int n) {
            return new Options(sniffMs, aplGain, channels, n, samplesPerRoute, decoysPerRoute, holdoutPerRoute,
                    epochs, learningRate, minBelief, seed, engine);
        }

        public Options withSniffMs(double ms) {
            return new Options(ms, aplGain, channels, flies, samplesPerRoute, decoysPerRoute, holdoutPerRoute,
                    epochs, learningRate, minBelief, seed, engine);
        }

        int resolvedFlies() {
            return flies > 0 ? flies : Runtime.getRuntime().availableProcessors();
        }
    }

    /**
     * Quality of the learned routing, measured on requests the fly never smelled during learning.
     *
     * @param accuracy       share of held-out requests routed to their endpoint
     * @param decoyRejection share of held-out decoys answered as unknown
     * @param sniffs         sniffs performed while learning (0 when loaded from cache)
     * @param learnMillis    wall time of learning, including sniffs
     * @param fromCache      whether the synapses were loaded from the cache
     */
    public record Metrics(double accuracy, double decoyRejection, int sniffs, long learnMillis, boolean fromCache) {
    }

    /**
     * @param route      index into {@link #routes()}, or -1 when the fly does not recognize the request
     * @param decision   the route neurons' verdict
     * @param perception what the brain did
     */
    public record Routing(int route, Decision decision, Perception perception, SparseCode code) {

        public boolean recognized() {
            return route >= 0;
        }
    }

    private final List<RouteTemplate> routes;
    private final Options options;
    private final BrainPool pool;
    private final HttpNose nose;
    private final PredictionErrorBody body;
    private final Metrics metrics;
    private final java.util.concurrent.atomic.AtomicLong rewards = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong punishments = new java.util.concurrent.atomic.AtomicLong();

    private FlyRouter(List<RouteTemplate> routes, Options options, BrainPool pool, HttpNose nose,
                      PredictionErrorBody body, Metrics metrics) {
        this.routes = routes;
        this.options = options;
        this.pool = pool;
        this.nose = nose;
        this.body = body;
        this.metrics = metrics;
    }

    /**
     * Teaches a fly the endpoints, or loads its synapses from {@code cacheDir} if the same brain already learned
     * the same endpoints with the same options.
     *
     * @param cacheDir where learned synapses are kept; null disables the cache
     * @param log      progress messages
     */
    public static FlyRouter learn(Brain brain, List<RouteTemplate> routes, Options options, Path cacheDir,
                                  Consumer<String> log) throws IOException {
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("Nothing to learn: no routes");
        }
        List<RouteTemplate> endpoints = List.copyOf(routes);
        Brain routingBrain = brain.withAplGain(options.aplGain());
        List<String> readouts = options.channels().stream().map(CodeChannel::layer).distinct().toList();
        SimulationEngine engine = SimulationEngine.byName(options.engine());
        BrainPool pool = routingBrain.pool(options.resolvedFlies(), options.sniffMs(), readouts, engine);
        HttpNose nose = new HttpNose(routingBrain.atlas());
        String fingerprint = fingerprint(routingBrain, endpoints, options, nose);
        Path cacheFile = cacheDir == null ? null : cacheDir.resolve("flyroute-" + fingerprint + ".bin");

        if (cacheFile != null && Files.exists(cacheFile)) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(cacheFile))) {
                PredictionErrorBody body = PredictionErrorBody.read(in, fingerprint);
                if (body != null) {
                    DataInputStream data = new DataInputStream(in);
                    Metrics m = new Metrics(data.readDouble(), data.readDouble(), 0, 0, true);
                    log.accept("Loaded learned synapses from " + cacheFile);
                    return new FlyRouter(endpoints, options, pool, nose, body, m);
                }
            } catch (IOException e) {
                log.accept("Ignoring unreadable cache " + cacheFile + ": " + e.getMessage());
            }
        }

        long started = System.nanoTime();
        Sniffer sniffer = new Sniffer(pool, nose, options.channels());
        SplittableRandom rnd = new SplittableRandom(options.seed());
        DecoyGenerator decoys = new DecoyGenerator(endpoints);
        List<String[]> train = new ArrayList<>();
        List<String[]> holdout = new ArrayList<>();
        for (int r = 0; r < endpoints.size(); r++) {
            RouteTemplate t = endpoints.get(r);
            for (int s = 0; s < options.samplesPerRoute(); s++) {
                train.add(new String[]{Integer.toString(r), t.method(), t.samplePath(rnd)});
            }
        }
        for (int r = 0; r < endpoints.size(); r++) {
            RouteTemplate t = endpoints.get(r);
            for (int s = 0; s < options.holdoutPerRoute(); s++) {
                holdout.add(new String[]{Integer.toString(r), t.method(), t.samplePath(rnd)});
            }
        }
        List<String[]> decoyTrain = new ArrayList<>();
        List<String[]> decoyHoldout = new ArrayList<>();
        int decoyCount = options.decoysPerRoute() * endpoints.size();
        int decoyHoldoutCount = Math.max(1, decoyCount / 4);
        for (int i = 0; i < decoyCount + decoyHoldoutCount; i++) {
            String[] d = decoys.next(rnd);
            (i < decoyCount ? decoyTrain : decoyHoldout).add(new String[]{"-1", d[0], d[1]});
        }
        log.accept("The fly is sniffing " + (train.size() + decoyTrain.size()) + " training requests to "
                + endpoints.size() + " endpoints with " + pool.size() + " flies...");
        List<SparseCode> trainCodes = sniffer.codes(train);
        List<SparseCode> decoyCodes = sniffer.codes(decoyTrain);
        int[] labels = train.stream().mapToInt(r -> Integer.parseInt(r[0])).toArray();

        PredictionErrorBody body = new PredictionErrorBody(endpoints.stream().map(RouteTemplate::id).toList(),
                SparseCode.inputSize(sniffer.firstPerception(), options.channels()), options.minBelief());
        body.train(trainCodes, labels, decoyCodes, options.epochs(), options.learningRate(), 0, options.seed());

        List<SparseCode> holdoutCodes = sniffer.codes(holdout);
        int correct = 0;
        for (int i = 0; i < holdout.size(); i++) {
            Decision d = body.decide(holdoutCodes.get(i));
            correct += d.route() == Integer.parseInt(holdout.get(i)[0]) ? 1 : 0;
        }
        long rejected = sniffer.codes(decoyHoldout).stream().filter(c -> !body.decide(c).recognized()).count();
        Metrics m = new Metrics(holdout.isEmpty() ? Double.NaN : (double) correct / holdout.size(),
                (double) rejected / decoyHoldout.size(), sniffer.sniffs(),
                (System.nanoTime() - started) / 1_000_000, false);

        if (cacheFile != null) {
            writeCache(cacheFile, body, fingerprint, m, log);
        }
        return new FlyRouter(endpoints, options, pool, nose, body, m);
    }

    /** The cache only saves the next start from learning, so failing to write it is not worth failing this one. */
    private static void writeCache(Path cacheFile, PredictionErrorBody body, String fingerprint, Metrics m,
                                   Consumer<String> log) {
        Path tmp = null;
        try {
            Files.createDirectories(cacheFile.getParent());
            tmp = Files.createTempFile(cacheFile.getParent(), "flyroute", ".tmp");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                body.write(out, fingerprint);
                DataOutputStream data = new DataOutputStream(out);
                data.writeDouble(m.accuracy());
                data.writeDouble(m.decoyRejection());
                data.flush();
            }
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.accept("Cannot cache learned synapses in " + cacheFile + ", the next start will learn again: " + e);
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // nothing more to do about a leftover temp file
                }
            }
        }
    }

    /** Sniffs the request and lets the route neurons decide. Same request, same decision. */
    public Routing route(String method, String path) {
        String line = method.toUpperCase(java.util.Locale.ROOT) + " " + path;
        Perception p = pool.sniff(nose.smell(method, path), Hashing.hash64(line));
        SparseCode code = SparseCode.of(p, options.channels());
        Decision d = body.decide(code);
        return new Routing(d.route(), d, p, code);
    }

    /**
     * Learns from how the request the fly routed was answered: a good answer rewards the chosen route, a bad one
     * teaches the fly that this smell is unknown. Unrecognized requests teach nothing.
     */
    public void reinforce(Routing routing, boolean reward, double rate) {
        if (!routing.recognized()) {
            return;
        }
        body.reinforce(routing.code(), routing.route(), reward, rate);
        (reward ? rewards : punishments).incrementAndGet();
    }

    /**
     * Like {@link #route} but records every spike of the sniff and returns the belief of every route neuron,
     * the unknown neuron last. For showing what the fly did; does not learn.
     */
    public Explanation explain(String method, String path) {
        String line = method.toUpperCase(java.util.Locale.ROOT) + " " + path;
        Perception p = pool.sniffRecorded(nose.smell(method, path), Hashing.hash64(line));
        SparseCode code = SparseCode.of(p, options.channels());
        Decision d = body.decide(code);
        return new Explanation(new Routing(d.route(), d, p, code), body.beliefs(code), nose.smell(method, path));
    }

    /**
     * @param beliefs belief of every route neuron in {@link #routes()} order, then of the unknown neuron
     * @param odor    what the nose made of the request
     */
    public record Explanation(Routing routing, double[] beliefs, dev.flyboot.brain.Odor odor) {
    }

    /** Online rewards and punishments received since start. */
    public long rewards() {
        return rewards.get();
    }

    public long punishments() {
        return punishments.get();
    }

    public List<RouteTemplate> routes() {
        return routes;
    }

    public Options options() {
        return options;
    }

    public Metrics metrics() {
        return metrics;
    }

    public BrainPool pool() {
        return pool;
    }

    private static String fingerprint(Brain brain, List<RouteTemplate> routes, Options options, HttpNose nose) {
        String routeText = routes.stream()
                .map(r -> r.id() + r.vars().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(",", "[", "]")))
                .collect(Collectors.joining(";"));
        String text = String.join("|", "v1", brain.connectome().name(),
                Integer.toString(brain.connectome().neuronCount()), Integer.toString(brain.connectome().edgeCount()),
                brain.params().toString(), nose.toString(), options.withFlies(0).toString(), routeText);
        return Long.toHexString(Hashing.hash64(text));
    }

    /** Sniffs requests in parallel, reusing the perception of a request line seen before. */
    private static final class Sniffer {
        private final BrainPool pool;
        private final HttpNose nose;
        private final List<CodeChannel> channels;
        private final Map<String, Perception> seen = new ConcurrentHashMap<>();

        Sniffer(BrainPool pool, HttpNose nose, List<CodeChannel> channels) {
            this.pool = pool;
            this.nose = nose;
            this.channels = channels;
        }

        List<SparseCode> codes(List<String[]> requests) {
            Map<String, String[]> fresh = new java.util.LinkedHashMap<>();
            for (String[] r : requests) {
                String line = r[1] + " " + r[2];
                if (!seen.containsKey(line)) {
                    fresh.putIfAbsent(line, r);
                }
            }
            fresh.entrySet().parallelStream().forEach(e -> seen.put(e.getKey(),
                    pool.sniff(nose.smell(e.getValue()[1], e.getValue()[2]), Hashing.hash64(e.getKey()))));
            return requests.stream().map(r -> SparseCode.of(seen.get(r[1] + " " + r[2]), channels)).toList();
        }

        Perception firstPerception() {
            return seen.values().iterator().next();
        }

        int sniffs() {
            return seen.size();
        }
    }
}
