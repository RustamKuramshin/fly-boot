package dev.flyboot.lab;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.BrainAtlas;
import dev.flyboot.brain.BrainPool;
import dev.flyboot.brain.Hashing;
import dev.flyboot.brain.HttpNose;
import dev.flyboot.brain.Perception;
import dev.flyboot.brain.learn.CodeChannel;
import dev.flyboot.brain.learn.Decision;
import dev.flyboot.brain.learn.DecoyGenerator;
import dev.flyboot.lab.learn.MushroomBody;
import dev.flyboot.brain.learn.PredictionErrorBody;
import dev.flyboot.brain.learn.RouteTemplate;
import dev.flyboot.brain.learn.SparseCode;
import dev.flyboot.lab.learn.TrainingOptions;
import dev.flyboot.brain.learn.VarKind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Which readout lets the fly learn endpoints best? Every request is sniffed once; mushroom body variants are then
 * trained on the same perceptions. Reports accuracy on requests with unseen variable values, rejection of decoys
 * like those used for training, and rejection of hand-written requests to endpoints that do not exist.
 * <p>
 * Usage: {@code LearningStudy [sniffMs] [trainPerRoute] [holdoutPerRoute] [aplGains] [decoysPerRoute]}.
 */
public final class LearningStudy {

    static final Map<String, VarKind> ID = Map.of("id", VarKind.NUMBER, "petId", VarKind.NUMBER,
            "visitId", VarKind.UUID, "name", VarKind.WORD, "slug", VarKind.ANY);

    static final List<RouteTemplate> ROUTES = List.of(
            RouteTemplate.of("GET /", ID),
            RouteTemplate.of("GET /pets", ID),
            RouteTemplate.of("POST /pets", ID),
            RouteTemplate.of("GET /pets/search", ID),
            RouteTemplate.of("GET /pets/{id}", ID),
            RouteTemplate.of("PUT /pets/{id}", ID),
            RouteTemplate.of("DELETE /pets/{id}", ID),
            RouteTemplate.of("GET /pets/{id}/photos", ID),
            RouteTemplate.of("GET /owners", ID),
            RouteTemplate.of("POST /owners", ID),
            RouteTemplate.of("GET /owners/{id}", ID),
            RouteTemplate.of("GET /owners/{id}/pets", ID),
            RouteTemplate.of("POST /owners/{id}/pets", ID),
            RouteTemplate.of("GET /owners/{id}/pets/{petId}", ID),
            RouteTemplate.of("GET /vets", ID),
            RouteTemplate.of("GET /vets/{name}", ID),
            RouteTemplate.of("GET /vets/{name}/specialties", ID),
            RouteTemplate.of("GET /visits/{visitId}", ID),
            RouteTemplate.of("GET /blog/{slug}", ID),
            RouteTemplate.of("GET /api/v1/health", ID));

    static final List<String> UNKNOWN_TEMPLATES = List.of(
            "GET /admin", "GET /login", "POST /login", "GET /favicon.ico", "GET /cats/{id}", "GET /api/v2/users/{id}",
            "GET /pets/{id}/owners/{petId}/toys", "DELETE /owners", "GET /shop/{name}/cart", "GET /{name}/{name}/{name}",
            "GET /metrics", "GET /owners/{id}/cars");

    static final int NO_ROUTE = ROUTES.size();

    record Sniffed(int route, String request, Perception perception) {
    }

    public static void main(String[] args) throws IOException {
        double sniffMs = args.length > 0 ? Double.parseDouble(args[0]) : 75;
        int train = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int holdout = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        double[] gains = args.length > 3 ? Arrays.stream(args[3].split(",")).mapToDouble(Double::parseDouble).toArray()
                : new double[]{1, 3};
        int decoysPerRoute = args.length > 4 ? Integer.parseInt(args[4]) : 15;
        Brain brain = Lab.brain();
        HttpNose nose = new HttpNose(brain.atlas());
        int flies = Runtime.getRuntime().availableProcessors();

        SplittableRandom rnd = new SplittableRandom(2026);
        List<String[]> trainReq = new ArrayList<>();
        List<String[]> holdReq = new ArrayList<>();
        for (int r = 0; r < ROUTES.size(); r++) {
            RouteTemplate t = ROUTES.get(r);
            for (int s = 0; s < train + holdout; s++) {
                (s < train ? trainReq : holdReq).add(new String[]{Integer.toString(r), t.method(), t.samplePath(rnd)});
            }
        }
        DecoyGenerator decoys = new DecoyGenerator(ROUTES);
        List<String[]> decoyTrain = new ArrayList<>();
        List<String[]> decoyHold = new ArrayList<>();
        for (int i = 0; i < decoysPerRoute * ROUTES.size() + 60; i++) {
            String[] d = decoys.next(rnd);
            (i < decoysPerRoute * ROUTES.size() ? decoyTrain : decoyHold).add(new String[]{Integer.toString(NO_ROUTE), d[0], d[1]});
        }
        List<String[]> unknownReq = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            RouteTemplate t = RouteTemplate.of(UNKNOWN_TEMPLATES.get(i % UNKNOWN_TEMPLATES.size()), ID);
            unknownReq.add(new String[]{Integer.toString(NO_ROUTE), t.method(), t.samplePath(rnd)});
        }
        System.out.printf("Routes %d, train %d + holdout %d per route, decoys %d + %d, unknown %d, sniff %.0f ms, "
                        + "nose valueLiterals=%s%n", ROUTES.size(), train, holdout, decoyTrain.size(), decoyHold.size(),
                unknownReq.size(), sniffMs, System.getProperty("flyboot.nose.valueLiterals", "true"));

        for (double gain : gains) {
            Brain b = brain.withAplGain(gain);
            BrainPool pool = b.pool(flies, sniffMs, List.of(BrainAtlas.ALPN, BrainAtlas.KC, BrainAtlas.LHN));
            long t0 = System.nanoTime();
            List<Sniffed> tr = sniff(pool, nose, trainReq);
            List<Sniffed> ho = sniff(pool, nose, holdReq);
            List<Sniffed> dt = sniff(pool, nose, decoyTrain);
            List<Sniffed> dh = sniff(pool, nose, decoyHold);
            List<Sniffed> un = sniff(pool, nose, unknownReq);
            double kcActive = tr.stream().mapToDouble(x -> OdorSeparability.fractionActive(x.perception().counts(BrainAtlas.KC)))
                    .average().orElse(0);
            System.out.printf("%n== %s == sniffed in %.1f s, KC active %.1f%%%n", b.connectome().name(),
                    (System.nanoTime() - t0) / 1e9, 100 * kcActive);
            System.out.printf("%-34s %8s %8s %8s %8s%n", "readout", "top-1", "holdout", "decoys", "unknown");
            TrainingOptions o = TrainingOptions.defaults();
            CodeChannel kc = new CodeChannel(BrainAtlas.KC, 260, true, 1);
            CodeChannel pn = new CodeChannel(BrainAtlas.ALPN, 685, false, 1);
            CodeChannel lh = new CodeChannel(BrainAtlas.LHN, 559, false, 1);
            study("ALPN reward only (MB)", tr, ho, dt, dh, un, List.of(pn), o.withEpochs(0), false);
            study("KC + ALPN punish (MB)", tr, ho, dt, dh, un, List.of(kc, pn), o.withThreshold(0.05, 0.8, 0.2), true);
            for (double minBelief : new double[]{0.3, 0.5}) {
                String mbl = " p>=" + minBelief;
                studyRpe("RPE KC" + mbl, tr, ho, dt, dh, un, List.of(kc), minBelief);
                studyRpe("RPE ALPN" + mbl, tr, ho, dt, dh, un, List.of(pn), minBelief);
                studyRpe("RPE KC + ALPN" + mbl, tr, ho, dt, dh, un, List.of(kc, pn), minBelief);
                studyRpe("RPE KC + LHN" + mbl, tr, ho, dt, dh, un, List.of(kc, lh), minBelief);
                studyRpe("RPE KC-520 + ALPN" + mbl, tr, ho, dt, dh, un,
                        List.of(new CodeChannel(BrainAtlas.KC, 520, true, 1), pn), minBelief);
            }
        }
    }

    static List<Sniffed> sniff(BrainPool pool, HttpNose nose, List<String[]> requests) {
        return IntStream.range(0, requests.size()).parallel().mapToObj(i -> {
            String[] r = requests.get(i);
            String line = r[1] + " " + r[2];
            return new Sniffed(Integer.parseInt(r[0]), line, pool.sniff(nose.smell(r[1], r[2]), Hashing.hash64(line)));
        }).toList();
    }

    static void study(String name, List<Sniffed> train, List<Sniffed> holdout, List<Sniffed> decoyTrain,
                      List<Sniffed> decoyHold, List<Sniffed> unknown, List<CodeChannel> channels, TrainingOptions o,
                      boolean withDecoys) {
        List<String> labels = ROUTES.stream().map(RouteTemplate::id).toList();
        MushroomBody mb = new MushroomBody(labels, SparseCode.inputSize(train.getFirst().perception(), channels), o.wMax());
        mb.train(train.stream().map(s -> SparseCode.of(s.perception(), channels)).toList(),
                train.stream().mapToInt(Sniffed::route).toArray(),
                withDecoys ? decoyTrain.stream().map(s -> SparseCode.of(s.perception(), channels)).toList() : List.of(), o);
        int top1 = 0;
        int correct = 0;
        Map<String, Integer> confusions = new TreeMap<>();
        for (Sniffed s : holdout) {
            Decision d = mb.decide(SparseCode.of(s.perception(), channels));
            top1 += d.best() == s.route() ? 1 : 0;
            boolean ok = d.recognized() && d.route() == s.route();
            correct += ok ? 1 : 0;
            if (!ok) {
                confusions.merge(ROUTES.get(s.route()).id() + " -> " + (d.recognized() ? labels.get(d.route()) : "404"), 1, Integer::sum);
            }
        }
        System.out.printf("%-34s %7.1f%% %7.1f%% %7.1f%% %7.1f%%%n", name, 100.0 * top1 / holdout.size(),
                100.0 * correct / holdout.size(), 100 * rejection(mb, decoyHold, channels, labels),
                100 * rejection(mb, unknown, channels, labels));
        if (Boolean.getBoolean("study.confusions")) {
            confusions.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8)
                    .forEach(e -> System.out.printf("      %2d  %s%n", e.getValue(), e.getKey()));
        }
    }

    static void studyRpe(String name, List<Sniffed> train, List<Sniffed> holdout, List<Sniffed> decoyTrain,
                         List<Sniffed> decoyHold, List<Sniffed> unknown, List<CodeChannel> channels, double minBelief) {
        List<String> labels = ROUTES.stream().map(RouteTemplate::id).toList();
        PredictionErrorBody body = new PredictionErrorBody(labels, SparseCode.inputSize(train.getFirst().perception(), channels), minBelief);
        body.train(train.stream().map(s -> SparseCode.of(s.perception(), channels)).toList(),
                train.stream().mapToInt(Sniffed::route).toArray(),
                decoyTrain.stream().map(s -> SparseCode.of(s.perception(), channels)).toList(),
                Integer.getInteger("rpe.epochs", 40), Double.parseDouble(System.getProperty("rpe.rate", "0.5")),
                Double.parseDouble(System.getProperty("rpe.decay", "1e-4")), 2026);
        int top1 = 0;
        int correct = 0;
        for (Sniffed s : holdout) {
            Decision d = body.decide(SparseCode.of(s.perception(), channels));
            top1 += d.best() == s.route() ? 1 : 0;
            correct += d.recognized() && d.route() == s.route() ? 1 : 0;
        }
        System.out.printf("%-34s %7.1f%% %7.1f%% %7.1f%% %7.1f%%%n", name, 100.0 * top1 / holdout.size(),
                100.0 * correct / holdout.size(), 100 * rejectionRpe(body, decoyHold, channels),
                100 * rejectionRpe(body, unknown, channels));
    }

    static double rejectionRpe(PredictionErrorBody body, List<Sniffed> requests, List<CodeChannel> channels) {
        return requests.stream().filter(s -> !body.decide(SparseCode.of(s.perception(), channels)).recognized()).count()
                / (double) requests.size();
    }

    static double rejection(MushroomBody mb, List<Sniffed> requests, List<CodeChannel> channels, List<String> labels) {
        int rejected = 0;
        for (Sniffed s : requests) {
            Decision d = mb.decide(SparseCode.of(s.perception(), channels));
            rejected += d.recognized() ? 0 : 1;
        }
        return (double) rejected / requests.size();
    }
}
