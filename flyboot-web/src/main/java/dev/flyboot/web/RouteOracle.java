package dev.flyboot.web;

/** Decides which endpoint answers a request. In FlyBoot this is the fly ({@link FlyRouterOracle}). */
@FunctionalInterface
public interface RouteOracle {

    /**
     * @param route    index of the chosen endpoint in the handler list, or -1 for "no endpoint"
     * @param belief   how sure the oracle is, 0..1
     * @param spikes   spikes the brain fired to decide
     * @param sniffMs  wall time of the decision
     */
    record Verdict(int route, double belief, long spikes, double sniffMs) {

        public boolean recognized() {
            return route >= 0;
        }
    }

    Verdict consult(String method, String rawPath);
}
