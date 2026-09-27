package dev.flyboot.web;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.learn.FlyRouter;

import java.time.Instant;

/**
 * The running fly, injectable into components such as actuator controllers. Components are created before the
 * fly learns their endpoints, so the runtime is filled in when the application starts; until then
 * {@link #started()} is false.
 */
public final class FlyRuntime {

    private volatile Brain brain;
    private volatile FlyRouter router;
    private volatile FlyDispatcher dispatcher;
    private volatile Instant startedAt;

    void start(Brain brain, FlyRouter router, FlyDispatcher dispatcher) {
        this.brain = brain;
        this.router = router;
        this.dispatcher = dispatcher;
        this.startedAt = Instant.now();
    }

    public boolean started() {
        return dispatcher != null;
    }

    /** The brain the application was given (the router may use a variant of it, e.g. with stronger APL). */
    public Brain brain() {
        return brain;
    }

    public FlyRouter router() {
        return router;
    }

    public FlyDispatcher dispatcher() {
        return dispatcher;
    }

    public Instant startedAt() {
        return startedAt;
    }
}
