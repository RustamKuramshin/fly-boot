package dev.flyboot.web;

import dev.flyboot.brain.learn.FlyRouter;

/** The fly as the oracle: its route neurons were trained on the handler templates in handler order. */
public final class FlyRouterOracle implements RouteOracle {

    private final FlyRouter router;

    public FlyRouterOracle(FlyRouter router) {
        this.router = router;
    }

    @Override
    public Verdict consult(String method, String rawPath) {
        FlyRouter.Routing r = router.route(method, rawPath);
        return new Verdict(r.route(), r.decision().score(), r.perception().totalSpikes(), r.perception().wallMs());
    }

    public FlyRouter router() {
        return router;
    }
}
