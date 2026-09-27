package dev.flyboot.web;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.learn.FlyRouter;
import dev.flyboot.brain.learn.RouteTemplate;
import dev.flyboot.core.FlyContext;
import dev.flyboot.web.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Assembles a FlyBoot web application: scans components, collects handlers of the controllers, teaches the fly
 * their endpoints and starts the HTTP server.
 */
public final class FlyWebApplication implements AutoCloseable {

    private final FlyContext context;
    private final FlyRouter router;
    private final FlyDispatcher dispatcher;
    private final FlyWebServer server;

    private FlyWebApplication(FlyContext context, FlyRouter router, FlyDispatcher dispatcher, FlyWebServer server) {
        this.context = context;
        this.router = router;
        this.dispatcher = dispatcher;
        this.server = server;
    }

    public static Builder builder() {
        return new Builder();
    }

    public FlyContext context() {
        return context;
    }

    public FlyRouter router() {
        return router;
    }

    public FlyDispatcher dispatcher() {
        return dispatcher;
    }

    public int port() {
        return server.port();
    }

    public String url() {
        return "http://localhost:" + port();
    }

    @Override
    public void close() {
        server.close();
    }

    public static final class Builder {
        private final List<String> packages = new ArrayList<>();
        private final List<Class<?>> components = new ArrayList<>();
        private final List<Object> singletons = new ArrayList<>();
        private Brain brain;
        private FlyRouter.Options routerOptions = FlyRouter.Options.defaults();
        private Path cacheDir = Path.of(".flyboot");
        private String host = "0.0.0.0";
        private int port = 8080;
        private JsonMapper json = JsonMapper.builder().build();
        private Consumer<String> log = System.out::println;

        public Builder scanPackages(String... names) {
            packages.addAll(List.of(names));
            return this;
        }

        public Builder component(Class<?> type) {
            components.add(type);
            return this;
        }

        public Builder singleton(Object bean) {
            singletons.add(bean);
            return this;
        }

        public Builder brain(Brain value) {
            brain = value;
            return this;
        }

        public Builder routerOptions(FlyRouter.Options value) {
            routerOptions = value;
            return this;
        }

        /** Where learned synapses are cached; null disables the cache. */
        public Builder cacheDir(Path value) {
            cacheDir = value;
            return this;
        }

        public Builder host(String value) {
            host = value;
            return this;
        }

        /** 0 picks a free port. */
        public Builder port(int value) {
            port = value;
            return this;
        }

        public Builder json(JsonMapper value) {
            json = value;
            return this;
        }

        public Builder log(Consumer<String> value) {
            log = value;
            return this;
        }

        public FlyWebApplication start() throws IOException {
            if (brain == null) {
                throw new IllegalStateException("No brain: call brain(Brain.load(dataDir))");
            }
            FlyContext context = packages.isEmpty() ? new FlyContext() : FlyContext.ofPackages(packages.toArray(String[]::new));
            components.forEach(context::registerComponent);
            singletons.forEach(context::registerSingleton);
            FlyRuntime runtime = new FlyRuntime();
            context.registerSingleton(brain);
            context.registerSingleton(runtime);
            context.registerSingleton(json);
            context.refresh();

            List<HandlerMethod> handlers = HandlerRegistry.collect(context.getBeansWithAnnotation(RestController.class));
            if (handlers.isEmpty()) {
                throw new FlyWebException("No handler methods found in @RestController classes of " + packages);
            }
            handlers.forEach(h -> log.accept("Mapped " + h.template().id() + " -> " + h.describe()));
            List<RouteTemplate> templates = handlers.stream().map(HandlerMethod::template).toList();
            FlyRouter router = FlyRouter.learn(brain, templates, routerOptions, cacheDir, log);
            FlyRouter.Metrics m = router.metrics();
            log.accept(String.format(java.util.Locale.ROOT,
                    "The fly knows %d endpoints: accuracy %.1f%% on unseen values, %.1f%% of decoys rejected%s",
                    templates.size(), 100 * m.accuracy(), 100 * m.decoyRejection(),
                    m.fromCache() ? " (synapses from cache)" : String.format(java.util.Locale.ROOT,
                            " (learned from %d sniffs in %.1f s)", m.sniffs(), m.learnMillis() / 1000.0)));
            FlyDispatcher dispatcher = new FlyDispatcher(handlers, new FlyRouterOracle(router), json);
            runtime.start(brain, router, dispatcher);
            FlyWebServer server = FlyWebServer.start(new InetSocketAddress(host, port), dispatcher);
            return new FlyWebApplication(context, router, dispatcher, server);
        }
    }
}
