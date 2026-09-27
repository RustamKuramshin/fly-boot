package dev.flyboot.brain.learn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Requests that look like the application's endpoints but hit none of them: the fly learns their smell as
 * "unknown" and answers 404. Decoys mutate real requests - a literal segment replaced, a segment added or
 * removed, a method that the path does not serve - or are random paths. A decoy that happens to match a real
 * endpoint is discarded.
 */
public final class DecoyGenerator {

    static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH");

    private final List<RouteTemplate> routes;

    public DecoyGenerator(List<RouteTemplate> routes) {
        this.routes = List.copyOf(routes);
    }

    /** A decoy request as {@code {method, path}}. */
    public String[] next(SplittableRandom rnd) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String[] decoy = mutate(rnd);
            if (routes.stream().noneMatch(r -> r.matches(decoy[0], decoy[1]))) {
                return decoy;
            }
        }
        return new String[]{"GET", "/" + VarKind.WORD.sample(rnd) + "/" + VarKind.WORD.sample(rnd) + "/" + VarKind.WORD.sample(rnd)};
    }

    private String[] mutate(SplittableRandom rnd) {
        RouteTemplate base = routes.get(rnd.nextInt(routes.size()));
        String method = base.method();
        List<String> segments = new ArrayList<>(Arrays.stream(base.samplePath(rnd).split("/")).filter(s -> !s.isEmpty()).toList());
        switch (rnd.nextInt(5)) {
            case 0 -> { // replace a segment with a foreign word
                if (segments.isEmpty()) {
                    segments.add(VarKind.WORD.sample(rnd));
                } else {
                    segments.set(rnd.nextInt(segments.size()), VarKind.WORD.sample(rnd));
                }
            }
            case 1 -> segments.add(rnd.nextInt(segments.size() + 1), VarKind.values()[rnd.nextInt(4)].sample(rnd));
            case 2 -> {
                if (!segments.isEmpty()) {
                    segments.remove(rnd.nextInt(segments.size()));
                } else {
                    segments.add(VarKind.WORD.sample(rnd));
                }
            }
            case 3 -> method = METHODS.get(rnd.nextInt(METHODS.size()));
            default -> {
                segments.clear();
                for (int i = 0, n = rnd.nextInt(1, 4); i < n; i++) {
                    segments.add(VarKind.values()[rnd.nextInt(4)].sample(rnd));
                }
            }
        }
        return new String[]{method, "/" + String.join("/", segments)};
    }
}
