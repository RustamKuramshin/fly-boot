package dev.flyboot.lab;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.learn.FlyRouter;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * End-to-end FlyRouter: learns the 20 LearningStudy endpoints (or loads them from .flyboot/), routes sample
 * requests, and repeats learning on a rewired brain as a control.
 * <p>
 * Usage: {@code RouterDemo [skipControl]}.
 */
public final class RouterDemo {

    static final List<String> REQUESTS = List.of(
            "GET /", "GET /pets", "POST /pets", "GET /pets/search", "GET /pets/31337", "PUT /pets/7", "DELETE /pets/7",
            "GET /pets/12/photos", "GET /owners/42", "GET /owners/42/pets", "POST /owners/42/pets",
            "GET /owners/42/pets/100500", "GET /vets/aibolit", "GET /vets/aibolit/specialties",
            "GET /visits/3f2b8c1e-9d4a-4f6b-8e2c-1a2b3c4d5e6f", "GET /blog/why-flies-love-bananas", "GET /api/v1/health",
            "GET /cats/7", "GET /admin", "GET /owners/42/cars", "PATCH /pets/7", "GET /api/v2/users/1");

    public static void main(String[] args) throws Exception {
        Brain brain = Brain.load(Lab.DATA);
        FlyRouter.Options options = FlyRouter.Options.defaults();
        FlyRouter router = FlyRouter.learn(brain, LearningStudy.ROUTES, options, Path.of(".flyboot"), System.out::println);
        report("FlyWire v783", router);

        System.out.println();
        for (String request : REQUESTS) {
            String[] parts = request.split(" ", 2);
            FlyRouter.Routing r = router.route(parts[0], parts[1]);
            System.out.printf("%-52s -> %-32s belief %.2f, %5.1f ms%n", request,
                    r.recognized() ? router.routes().get(r.route()).id() : "404 (unknown smell)",
                    r.decision().score(), r.perception().wallMs());
        }
        double[] wall = REQUESTS.stream().mapToDouble(q -> router.route(q.split(" ")[0], q.split(" ")[1]).perception().wallMs())
                .sorted().toArray();
        System.out.printf("%nSequential routing: median %.1f ms, max %.1f ms%n", wall[wall.length / 2], wall[wall.length - 1]);

        if (!(args.length > 0 && args[0].equals("skipControl"))) {
            System.out.println("\nControl: the same learning on a rewired brain");
            report("shuffled", FlyRouter.learn(brain.shuffled(7), LearningStudy.ROUTES, options, null, s -> { }));
        }
    }

    static void report(String name, FlyRouter router) {
        FlyRouter.Metrics m = router.metrics();
        System.out.printf("%s: accuracy on unseen values %.1f%%, decoys rejected %.1f%%, %d sniffs, %.1f s%s%n", name,
                100 * m.accuracy(), 100 * m.decoyRejection(), m.sniffs(), m.learnMillis() / 1000.0,
                m.fromCache() ? " (from cache)" : "");
    }
}
