package dev.flyboot.boot.actuator;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.learn.FlyRouter;
import dev.flyboot.web.FlyDispatcher;
import dev.flyboot.web.FlyRuntime;
import dev.flyboot.web.HandlerMethod;
import dev.flyboot.web.annotation.GetMapping;
import dev.flyboot.web.annotation.RequestMapping;
import dev.flyboot.web.annotation.RequestParam;
import dev.flyboot.web.annotation.RestController;

import dev.flyboot.brain.BrainAtlas;
import dev.flyboot.brain.NeuronPositions;
import dev.flyboot.brain.SpikeRecording;
import dev.flyboot.web.ResponseEntity;
import dev.flyboot.web.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the fly is and how it is doing. These endpoints are learned and routed by the fly like any other.
 */
@RestController
@RequestMapping("/actuator")
public class BrainActuator {

    /** Neuron groups drawn in their own colour; everything else is "other". */
    static final List<String> GROUPS = List.of("other", "ORN", "ALPN", "KC", "LHN", "MBON", "DAN", "APL", "DN");

    private final FlyRuntime runtime;
    private volatile Map<String, Object> layout;

    public BrainActuator(FlyRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", runtime.started() ? "UP" : "STARTING");
        m.put("fly", runtime.started() ? "buzzing" : "waking up");
        return m;
    }

    @GetMapping("/brain")
    public Map<String, Object> brain(@RequestParam(defaultValue = "10") int recent) {
        Brain brain = runtime.brain();
        FlyRouter router = runtime.router();
        FlyDispatcher dispatcher = runtime.dispatcher();
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Object> connectome = new LinkedHashMap<>();
        connectome.put("name", brain.connectome().name());
        connectome.put("neurons", brain.connectome().neuronCount());
        connectome.put("connections", brain.connectome().edgeCount());
        connectome.put("glomeruli", brain.atlas().glomeruli().size());
        connectome.put("kenyonCells", brain.atlas().population("KC").length);
        connectome.put("projectionNeurons", brain.atlas().population("ALPN").length);
        m.put("connectome", connectome);
        m.put("neuronModel", brain.params());

        FlyRouter.Options o = router.options();
        FlyRouter.Metrics metrics = router.metrics();
        Map<String, Object> learning = new LinkedHashMap<>();
        learning.put("endpoints", router.routes().size());
        learning.put("accuracyOnUnseenValues", metrics.accuracy());
        learning.put("decoyRejection", metrics.decoyRejection());
        learning.put("fromCache", metrics.fromCache());
        learning.put("sniffs", metrics.sniffs());
        learning.put("learnMillis", metrics.learnMillis());
        learning.put("sniffMs", o.sniffMs());
        learning.put("aplGain", o.aplGain());
        learning.put("minBelief", o.minBelief());
        learning.put("flies", router.pool().size());
        learning.put("channels", o.channels());
        Map<String, Object> online = new LinkedHashMap<>();
        online.put("enabled", dispatcher.oracle() instanceof dev.flyboot.web.FlyRouterOracle fly && fly.online());
        online.put("rewards", router.rewards());
        online.put("punishments", router.punishments());
        learning.put("engine", router.pool().engine().name());
        learning.put("device", router.pool().engine().device());
        learning.put("online", online);
        m.put("learning", learning);

        Map<String, Object> traffic = new LinkedHashMap<>();
        traffic.put("requests", dispatcher.requests());
        traffic.put("recognized", dispatcher.recognized());
        List<FlyDispatcher.Trace> traces = dispatcher.recentTraces();
        traffic.put("recent", traces.subList(0, Math.min(Math.max(recent, 0), traces.size())));
        m.put("traffic", traffic);
        m.put("uptime", Duration.between(runtime.startedAt(), Instant.now()).toString());
        return m;
    }

    /** The page that shows the fly sniffing a request: open it in a browser. */
    @GetMapping("/brain/ui")
    public ResponseEntity<byte[]> ui() throws IOException {
        try (InputStream in = BrainActuator.class.getResourceAsStream("/flyboot/brain-ui.html")) {
            if (in == null) {
                throw new ResponseStatusException(500, "brain-ui.html is missing from the starter jar");
            }
            return ResponseEntity.ok(in.readAllBytes()).header("Content-Type", "text/html; charset=utf-8");
        }
    }

    /**
     * Where every neuron is drawn: a front view of the brain scaled to 1000 px wide, -1 for neurons without a
     * known position, and the group of every neuron as an index into {@code groups}.
     */
    @GetMapping("/brain/layout")
    public Map<String, Object> layout() {
        Map<String, Object> l = layout;
        if (l == null) {
            l = computeLayout(runtime.brain());
            layout = l;
        }
        return l;
    }

    /** Sniffs the request with every spike recorded, without routing or learning from it. */
    @GetMapping("/brain/sniff")
    public Map<String, Object> sniff(@RequestParam(defaultValue = "GET") String method, @RequestParam String path) {
        FlyRouter router = runtime.router();
        FlyRouter.Explanation e = router.explain(method, path);
        FlyRouter.Routing r = e.routing();
        SpikeRecording rec = r.perception().spikes();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request", method.toUpperCase(java.util.Locale.ROOT) + " " + path);
        m.put("recognized", r.recognized());
        m.put("route", r.recognized() ? router.routes().get(r.route()).id() : null);
        m.put("belief", r.decision().score());
        List<Map<String, Object>> beliefs = new ArrayList<>();
        for (int i = 0; i < e.beliefs().length; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("route", i < router.routes().size() ? router.routes().get(i).id() : "unknown smell (404)");
            b.put("belief", e.beliefs()[i]);
            beliefs.add(b);
        }
        beliefs.sort((a, b) -> Double.compare((double) b.get("belief"), (double) a.get("belief")));
        m.put("beliefs", beliefs);
        m.put("odor", e.odor().ratesHz());
        m.put("dtMs", rec.dtMs());
        m.put("sniffMs", r.perception().brainMs());
        m.put("wallMs", r.perception().wallMs());
        m.put("totalSpikes", r.perception().totalSpikes());
        m.put("t", rec.steps());
        m.put("n", rec.neurons());
        return m;
    }

    static Map<String, Object> computeLayout(Brain brain) {
        NeuronPositions pos = brain.positions();
        int n = pos.size();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (pos.known(i)) {
                minX = Math.min(minX, pos.x(i));
                maxX = Math.max(maxX, pos.x(i));
                minY = Math.min(minY, pos.y(i));
                maxY = Math.max(maxY, pos.y(i));
            }
        }
        double scale = maxX > minX ? 1000.0 / (maxX - minX) : 1;
        int[] x = new int[n];
        int[] y = new int[n];
        int known = 0;
        for (int i = 0; i < n; i++) {
            if (pos.known(i)) {
                x[i] = (int) Math.round((pos.x(i) - minX) * scale);
                y[i] = (int) Math.round((pos.y(i) - minY) * scale);
                known++;
            } else {
                x[i] = -1;
                y[i] = -1;
            }
        }
        int[] group = new int[n];
        BrainAtlas atlas = brain.atlas();
        for (int[] receptors : atlas.glomeruli().values()) {
            for (int i : receptors) {
                group[i] = 1;
            }
        }
        String[] populations = {BrainAtlas.ALPN, BrainAtlas.KC, BrainAtlas.LHN, BrainAtlas.MBON, BrainAtlas.DAN,
                BrainAtlas.APL, BrainAtlas.DN};
        for (int g = 0; g < populations.length; g++) {
            for (int i : atlas.population(populations[g])) {
                group[i] = g + 2;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", brain.connectome().name());
        m.put("neurons", n);
        m.put("known", known);
        m.put("width", 1000);
        m.put("height", maxY > minY ? (int) Math.ceil((maxY - minY) * scale) + 1 : 1000);
        m.put("groups", GROUPS);
        m.put("x", x);
        m.put("y", y);
        m.put("group", group);
        return m;
    }

    @GetMapping("/routes")
    public List<Map<String, String>> routes() {
        return runtime.dispatcher().handlers().stream().map(BrainActuator::describe).toList();
    }

    private static Map<String, String> describe(HandlerMethod h) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("route", h.template().id());
        m.put("handler", h.bean().getClass().getName() + "#" + h.method().getName());
        m.put("variables", h.template().vars().toString());
        return m;
    }
}
