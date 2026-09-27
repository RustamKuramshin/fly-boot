package dev.flyboot.lab;

import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.LifParams;
import dev.flyboot.brain.LifSimulation;
import dev.flyboot.brain.NeuronCatalog;
import dev.flyboot.brain.Stimulus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Prototype of the FlyBoot "nose": HTTP requests are encoded as odors and the brain must tell endpoints apart
 * for path variable values it has never smelled. Training requests and test requests use different values.
 * <p>
 * Encoding: the HTTP method drives one pheromone glomerulus; every path feature (literal segment at a position,
 * shape of a segment - number, uuid, word - and the segment count) is hashed onto two of the remaining
 * glomeruli. The router itself never matches path templates: it only sees the brain's spikes.
 * <p>
 * Usage: {@code RouteSeparability [sniffMs] [samplesPerRoute]}.
 */
public final class RouteSeparability {

    static final String[] ROUTES = {
            "GET /pets", "POST /pets", "GET /pets/{id}", "PUT /pets/{id}", "DELETE /pets/{id}",
            "GET /pets/{id}/photos", "GET /owners", "GET /owners/{id}", "GET /owners/{id}/pets",
            "GET /owners/{id}/pets/{petId}", "GET /vets/{name}", "GET /visits/{uuid}",
    };
    static final Map<String, String> METHOD_GLOMERULUS = Map.of(
            "GET", "ORN_DA1", "POST", "ORN_VA1v", "PUT", "ORN_VA1d", "DELETE", "ORN_DL3", "PATCH", "ORN_DC3");
    static final double FEATURE_HZ = 30;
    static final double MAX_HZ = 60;

    public static void main(String[] args) {
        double sniffMs = args.length > 0 ? Double.parseDouble(args[0]) : 50;
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 20;

        Connectome brain = Lab.connectome();
        NeuronCatalog catalog = Lab.catalog();
        Map<String, int[]> glomeruli = catalog.groupByType(n -> n.cellClass().equals("olfactory") && n.type().startsWith("ORN_"));
        List<String> pathGlomeruli = glomeruli.keySet().stream().filter(t -> !METHOD_GLOMERULUS.containsValue(t)).toList();

        Map<String, int[]> readouts = new LinkedHashMap<>();
        readouts.put("ALPN", catalog.select(n -> n.cellClass().equals("ALPN")));
        readouts.put("KC", catalog.select(n -> n.cellClass().equals("Kenyon_Cell")));
        readouts.put("LHN", catalog.select(n -> n.cellClass().equals("LHLN") || n.cellClass().equals("LHCENT")));
        readouts.put("ALL", catalog.select(n -> !n.cellClass().equals("olfactory")));

        // requests: first half of samples train, second half test; values never repeat
        SplittableRandom rnd = new SplittableRandom(2026);
        List<String[]> requests = new ArrayList<>();
        for (int r = 0; r < ROUTES.length; r++) {
            for (int s = 0; s < samples; s++) {
                requests.add(new String[]{Integer.toString(r), Integer.toString(s), instantiate(ROUTES[r], rnd)});
            }
        }
        System.out.printf("Routes: %d, samples per route: %d, sniff %.0f ms, e.g. %s | %s%n",
                ROUTES.length, samples, sniffMs, requests.get(samples * 2)[2], requests.get(samples * 2 + 1)[2]);

        LifParams params = LifParams.shiu2024();
        for (Connectome c : List.of(brain, brain.shuffledTargets(7))) {
            ThreadLocal<LifSimulation> sims = ThreadLocal.withInitial(() -> new LifSimulation(c, params));
            List<OdorSeparability.Sample> result = IntStream.range(0, requests.size()).parallel().mapToObj(k -> {
                String[] req = requests.get(k);
                double[] rates = encode(req[2], glomeruli, pathGlomeruli);
                Stimulus.Builder stim = Stimulus.builder();
                List<int[]> glom = new ArrayList<>(glomeruli.values());
                for (int gI = 0; gI < glom.size(); gI++) {
                    stim.addAll(glom.get(gI), rates[gI]);
                }
                LifSimulation sim = sims.get();
                sim.setStimulus(stim.build());
                sim.reset(req[2].hashCode());
                long t = System.nanoTime();
                sim.run(sniffMs);
                double wall = (System.nanoTime() - t) / 1e6;
                Map<String, int[]> counts = new LinkedHashMap<>();
                readouts.forEach((name, idx) -> counts.put(name, IntStream.of(idx).map(sim::spikeCount).toArray()));
                return new OdorSeparability.Sample(Integer.parseInt(req[0]), Integer.parseInt(req[1]), rates, counts,
                        sim.totalSpikes(), wall);
            }).toList();
            OdorSeparability.report(c.name(), result, ROUTES.length, samples, readouts.keySet());
            System.out.printf("median wall per sniff (parallel): %.1f ms%n",
                    result.stream().mapToDouble(OdorSeparability.Sample::wallMs).sorted().toArray()[result.size() / 2]);
        }
        OdorSeparability.latency(brain, params, new ArrayList<>(glomeruli.values()),
                encode(requests.get(samples * 3)[2], glomeruli, pathGlomeruli), sniffMs);
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

    /** Request line to firing rate per glomerulus, in the iteration order of {@code glomeruli}. */
    static double[] encode(String requestLine, Map<String, int[]> glomeruli, List<String> pathGlomeruli) {
        List<String> types = new ArrayList<>(glomeruli.keySet());
        double[] rates = new double[types.size()];
        String method = requestLine.substring(0, requestLine.indexOf(' '));
        String path = requestLine.substring(requestLine.indexOf(' ') + 1);
        String methodGlom = METHOD_GLOMERULUS.get(method);
        if (methodGlom != null) {
            rates[types.indexOf(methodGlom)] = MAX_HZ;
        }
        String[] segments = path.replaceFirst("^/", "").split("/");
        List<String> features = new ArrayList<>();
        features.add("len:" + segments.length);
        for (int i = 0; i < segments.length; i++) {
            features.add(i + ":shape:" + shape(segments[i]));
            features.add(i + ":lit:" + segments[i]);
        }
        for (String f : features) {
            int h = f.hashCode() * 0x9E3779B9;
            for (int k = 0; k < 2; k++) {
                String glom = pathGlomeruli.get(Math.floorMod(h + k * 0x7F4A7C15, pathGlomeruli.size()));
                int idx = types.indexOf(glom);
                rates[idx] = Math.min(MAX_HZ, rates[idx] + FEATURE_HZ);
            }
        }
        return rates;
    }

    static String shape(String segment) {
        if (segment.matches("\\d+")) {
            return "NUM";
        }
        if (segment.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return "UUID";
        }
        return "WORD";
    }
}
