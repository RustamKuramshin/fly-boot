package dev.flyboot.web;

/** Decides which endpoint answers a request. In FlyBoot this is the fly ({@link FlyRouterOracle}). */
@FunctionalInterface
public interface RouteOracle {

    /**
     * @param route    index of the chosen endpoint in the handler list, or -1 for "no endpoint"
     * @param belief   how sure the oracle is, 0..1
     * @param spikes   spikes the brain fired to decide
     * @param sniffMs  wall time of the decision
     * @param outcome  told the HTTP status the request was answered with, so the oracle can learn from it
     */
    record Verdict(int route, double belief, long spikes, double sniffMs, java.util.function.IntConsumer outcome) {

        public Verdict(int route, double belief, long spikes, double sniffMs) {
            this(route, belief, spikes, sniffMs, status -> { });
        }

        public boolean recognized() {
            return route >= 0;
        }
    }

    Verdict consult(String method, String rawPath);
}
