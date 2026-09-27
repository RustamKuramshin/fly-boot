package dev.flyboot.lab;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.BrainAtlas;
import dev.flyboot.brain.BrainPool;
import dev.flyboot.brain.Hashing;
import dev.flyboot.brain.HttpNose;
import dev.flyboot.brain.Odor;
import dev.flyboot.brain.Perception;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Can the fly tell endpoints apart by the smell of requests with path variable values it has never smelled?
 * Uses the production {@link HttpNose} and {@link BrainPool}; the router never matches path templates, a
 * nearest-centroid classifier sees only spike counts. Training and test requests use different values.
 * <p>
 * Usage: {@code RouteSeparability [sniffMs] [samplesPerRoute]}.
 */
public final class RouteSeparability {

    static final String[] ROUTES = {
            "GET /pets", "POST /pets", "GET /pets/{id}", "PUT /pets/{id}", "DELETE /pets/{id}",
            "GET /pets/{id}/photos", "GET /owners", "GET /owners/{id}", "GET /owners/{id}/pets",
            "GET /owners/{id}/pets/{petId}", "GET /vets/{name}", "GET /visits/{uuid}",
    };

    public static void main(String[] args) throws IOException {
        double sniffMs = args.length > 0 ? Double.parseDouble(args[0]) : 75;
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        int flies = Runtime.getRuntime().availableProcessors();
        List<String> readouts = List.of(BrainAtlas.ALPN, BrainAtlas.KC, BrainAtlas.LHN, BrainAtlas.MBON);

        Brain brain = Lab.brain();
        HttpNose nose = new HttpNose(brain.atlas());

        SplittableRandom rnd = new SplittableRandom(2026);
        List<String[]> requests = new ArrayList<>();
        for (int r = 0; r < ROUTES.length; r++) {
            for (int s = 0; s < samples; s++) {
                requests.add(new String[]{Integer.toString(r), Integer.toString(s), instantiate(ROUTES[r], rnd)});
            }
        }
        System.out.printf("Routes: %d, samples per route: %d, sniff %.0f ms, %d flies, e.g. %s -> %s%n",
                ROUTES.length, samples, sniffMs, flies, requests.get(samples * 2)[2],
                smell(nose, requests.get(samples * 2)[2]));

        for (Brain b : List.of(brain, brain.shuffled(7))) {
            BrainPool pool = b.pool(flies, sniffMs, readouts);
            List<OdorSeparability.Sample> result = IntStream.range(0, requests.size()).parallel().mapToObj(k -> {
                String[] req = requests.get(k);
                Odor odor = smell(nose, req[2]);
                Perception p = pool.sniff(odor, Hashing.hash64(req[2]));
                double[] input = brain.atlas().glomerulusTypes().stream().mapToDouble(odor::rateHz).toArray();
                return new OdorSeparability.Sample(Integer.parseInt(req[0]), Integer.parseInt(req[1]), input,
                        new LinkedHashMap<>(p.counts()), p.totalSpikes(), p.wallMs());
            }).toList();
            OdorSeparability.report(b.connectome().name(), result, ROUTES.length, samples, readouts);
            System.out.printf("median wall per sniff (%d in parallel): %.1f ms%n", flies,
                    result.stream().mapToDouble(OdorSeparability.Sample::wallMs).sorted().toArray()[result.size() / 2]);
        }

        BrainPool single = brain.pool(1, sniffMs, readouts);
        double[] wall = IntStream.range(0, 30).mapToDouble(i -> single.sniff(smell(nose, requests.get(i * 7)[2]), i).wallMs())
                .sorted().toArray();
        System.out.printf("%nSingle fly, one sniff at a time: median %.1f ms, p90 %.1f ms%n", wall[15], wall[27]);
    }

    static Odor smell(HttpNose nose, String requestLine) {
        int space = requestLine.indexOf(' ');
        return nose.smell(requestLine.substring(0, space), requestLine.substring(space + 1));
    }

    static String instantiate(String route, SplittableRandom rnd) {
        return route
                .replace("{id}", Integer.toString(rnd.nextInt(1, 1_000_000)))
                .replace("{petId}", Integer.toString(rnd.nextInt(1, 1_000_000)))
                .replace("{uuid}", new UUID(rnd.nextLong(), rnd.nextLong()).toString())
                .replace("{name}", randomWord(rnd));
    }

    static String randomWord(SplittableRandom rnd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = rnd.nextInt(4, 10); i < len; i++) {
            sb.append((char) ('a' + rnd.nextInt(26)));
        }
        return sb.toString();
    }
}
