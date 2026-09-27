package dev.flyboot.brain.learn;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.BrainAtlas;
import dev.flyboot.brain.SyntheticBrainAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FlyRouterTest {

    static final FlyRouter.Options SMALL = new FlyRouter.Options(20, 1,
            List.of(new CodeChannel(BrainAtlas.KC, 20, true, 1)), 2, 8, 4, 2, 20, 5, 0.3, 1);

    static final List<RouteTemplate> ROUTES = List.of(RouteTemplate.of("GET /pets"),
            RouteTemplate.of("GET /pets/{id}", Map.of("id", VarKind.NUMBER)), RouteTemplate.of("POST /owners"));

    @Test
    void learnsOnceThenLoadsTheSameSynapsesFromCache(@TempDir Path cache) throws Exception {
        Brain brain = SyntheticBrainAccess.create(1500, 11);
        List<String> log = new ArrayList<>();
        FlyRouter first = FlyRouter.learn(brain, ROUTES, SMALL, cache, log::add);
        assertFalse(first.metrics().fromCache());
        assertTrue(first.metrics().sniffs() > 0);
        try (var files = Files.list(cache)) {
            assertEquals(1, files.filter(f -> f.getFileName().toString().startsWith("flyroute-")).count());
        }

        FlyRouter second = FlyRouter.learn(brain, ROUTES, SMALL.withFlies(1), cache, log::add);
        assertTrue(second.metrics().fromCache(), "flies do not change what is learned");
        assertEquals(first.metrics().accuracy(), second.metrics().accuracy());
        for (String path : List.of("/pets", "/pets/7", "/owners", "/nowhere")) {
            assertEquals(first.route("GET", path).decision(), second.route("GET", path).decision(), path);
        }

        FlyRouter other = FlyRouter.learn(brain, ROUTES.subList(0, 2), SMALL, cache, log::add);
        assertFalse(other.metrics().fromCache(), "different endpoints need new learning");
    }

    @Test
    void routingIsDeterministic() throws Exception {
        FlyRouter router = FlyRouter.learn(SyntheticBrainAccess.create(1500, 11), ROUTES, SMALL, null, s -> { });
        FlyRouter.Routing a = router.route("GET", "/pets/42");
        FlyRouter.Routing b = router.route("get", "/pets/42");
        assertEquals(a.decision(), b.decision());
        assertEquals(a.perception().totalSpikes(), b.perception().totalSpikes());
    }

    @Test
    void nothingToLearnIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> FlyRouter.learn(SyntheticBrainAccess.create(300, 1), List.of(), SMALL, null, s -> { }));
    }

    @Test
    void realFlyLearnsAFewEndpoints() throws Exception {
        Path data = Path.of(System.getProperty("flyboot.data", "../data"));
        assumeTrue(Files.exists(data.resolve(Brain.DEFAULT_NAME + ".flyb")), "no brain data");
        List<RouteTemplate> routes = List.of(RouteTemplate.of("GET /pets"), RouteTemplate.of("POST /pets"),
                RouteTemplate.of("GET /pets/{id}", Map.of("id", VarKind.NUMBER)),
                RouteTemplate.of("DELETE /pets/{id}", Map.of("id", VarKind.NUMBER)),
                RouteTemplate.of("GET /owners/{name}", Map.of("name", VarKind.WORD)));
        FlyRouter router = FlyRouter.learn(Brain.load(data), routes,
                FlyRouter.Options.defaults().withSamples(30, 15, 10), null, s -> { });
        assertTrue(router.metrics().accuracy() >= 0.9, "accuracy " + router.metrics().accuracy());
        // fresh requests through route(): the fly is probabilistic, so judge a batch, not a single request
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(77);
        int correct = 0;
        for (int i = 0; i < 100; i++) {
            RouteTemplate t = routes.get(i % routes.size());
            correct += router.route(t.method(), t.samplePath(rnd)).route() == i % routes.size() ? 1 : 0;
        }
        assertTrue(correct >= 85, "fresh requests routed correctly: " + correct + "/100");
    }
}
