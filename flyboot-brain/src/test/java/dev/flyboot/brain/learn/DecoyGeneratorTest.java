package dev.flyboot.brain.learn;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DecoyGeneratorTest {

    @Test
    void decoysNeverHitARealEndpointAndVary() {
        List<RouteTemplate> routes = List.of(RouteTemplate.of("GET /"), RouteTemplate.of("GET /pets"),
                RouteTemplate.of("GET /pets/{id}"), RouteTemplate.of("POST /pets"), RouteTemplate.of("GET /{any}"),
                RouteTemplate.of("DELETE /owners/{id}/pets/{petId}"));
        DecoyGenerator decoys = new DecoyGenerator(routes);
        SplittableRandom rnd = new SplittableRandom(5);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String[] d = decoys.next(rnd);
            assertTrue(routes.stream().noneMatch(r -> r.matches(d[0], d[1])), d[0] + " " + d[1]);
            seen.add(d[0] + " " + d[1]);
        }
        assertTrue(seen.size() > 1500, "decoys should be diverse: " + seen.size());
    }
}
