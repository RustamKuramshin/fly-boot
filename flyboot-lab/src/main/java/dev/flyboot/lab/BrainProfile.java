package dev.flyboot.lab;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.BrainAtlas;
import dev.flyboot.brain.BrainPool;
import dev.flyboot.brain.HttpNose;
import dev.flyboot.brain.Perception;

import java.util.Arrays;
import java.util.List;

/**
 * Activity profile of a brain on request odors, to tune a new connectome to the regime the router was designed
 * for (FlyWire: ~78% ALPN active, ~9% KC active with APL x3).
 * <p>
 * Usage: {@code -Dflyboot.brain.name=... BrainProfile [wSyn,...] [aplGain,...] [sniffMs]}.
 */
public final class BrainProfile {

    public static void main(String[] args) {
        double[] wSyns = Arrays.stream((args.length > 0 ? args[0] : "0.275").split(",")).mapToDouble(Double::parseDouble).toArray();
        double[] gains = Arrays.stream((args.length > 1 ? args[1] : "1,3").split(",")).mapToDouble(Double::parseDouble).toArray();
        double sniffMs = args.length > 2 ? Double.parseDouble(args[2]) : 75;
        Brain base = Lab.brain();
        HttpNose nose = new HttpNose(base.atlas());
        List<String> requests = List.of("GET /pets", "POST /pets", "GET /pets/42", "DELETE /pets/7", "GET /owners/3/pets",
                "GET /vets/aibolit", "GET /visits/3f2b8c1e-9d4a-4f6b-8e2c-1a2b3c4d5e6f", "GET /api/v1/health");
        System.out.printf("%s: %,d neurons, %,d connections%n", base.connectome().name(), base.connectome().neuronCount(),
                base.connectome().edgeCount());
        System.out.printf("%8s %6s %10s %8s %8s %8s %10s%n", "wSyn", "APLx", "spikes", "ALPN%", "KC%", "MBON%", "ms/sniff");
        for (double w : wSyns) {
            for (double g : gains) {
                Brain b = base.withParams(base.params().withWSyn(w)).withAplGain(g);
                BrainPool pool = b.pool(1, sniffMs, List.of(BrainAtlas.ALPN, BrainAtlas.KC, BrainAtlas.MBON));
                double spikes = 0, alpn = 0, kc = 0, mbon = 0, ms = 0;
                for (String r : requests) {
                    String[] p = r.split(" ");
                    Perception perception = pool.sniff(nose.smell(p[0], p[1]), r.hashCode());
                    spikes += perception.totalSpikes();
                    alpn += OdorSeparability.fractionActive(perception.counts(BrainAtlas.ALPN));
                    kc += OdorSeparability.fractionActive(perception.counts(BrainAtlas.KC));
                    mbon += OdorSeparability.fractionActive(perception.counts(BrainAtlas.MBON));
                    ms += perception.wallMs();
                }
                int n = requests.size();
                System.out.printf("%8.3f %6.1f %10.0f %7.1f%% %7.1f%% %7.1f%% %10.1f%n", w, g, spikes / n, 100 * alpn / n,
                        100 * kc / n, 100 * mbon / n, ms / n);
            }
        }
    }
}
