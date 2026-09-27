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

import java.time.Duration;
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

    private final FlyRuntime runtime;

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
