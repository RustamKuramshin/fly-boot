package dev.flyboot.web;

import dev.flyboot.brain.learn.FlyRouter;

/**
 * The fly as the oracle: its route neurons were trained on the handler templates in handler order.
 * <p>
 * With online learning on, the fly keeps learning from the answers: a 2xx rewards the route it chose, a 5xx or a
 * 405 (it took the request for an endpoint of another method) punishes it. Other statuses teach nothing: a 404
 * from a handler means a missing resource, not a wrong route.
 */
public final class FlyRouterOracle implements RouteOracle {

    private final FlyRouter router;
    private final boolean online;
    private final double onlineRate;

    public FlyRouterOracle(FlyRouter router) {
        this(router, false, 0);
    }

    /** @param onlineRate learning rate of one online step (training starts at 5 and decays) */
    public FlyRouterOracle(FlyRouter router, boolean online, double onlineRate) {
        this.router = router;
        this.online = online;
        this.onlineRate = onlineRate;
    }

    @Override
    public Verdict consult(String method, String rawPath) {
        FlyRouter.Routing r = router.route(method, rawPath);
        return new Verdict(r.route(), r.decision().score(), r.perception().totalSpikes(), r.perception().wallMs(),
                status -> learn(r, status));
    }

    private void learn(FlyRouter.Routing r, int status) {
        if (!online || !r.recognized()) {
            return;
        }
        if (status >= 200 && status < 300) {
            router.reinforce(r, true, onlineRate);
        } else if (status >= 500 || status == 405) {
            router.reinforce(r, false, onlineRate);
        }
    }

    public boolean online() {
        return online;
    }

    public FlyRouter router() {
        return router;
    }
}
