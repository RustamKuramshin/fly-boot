package dev.flyboot.boot;

import dev.flyboot.boot.actuator.BrainActuator;
import dev.flyboot.brain.Brain;
import dev.flyboot.brain.learn.FlyRouter;
import dev.flyboot.core.FlyBootApplication;
import dev.flyboot.web.FlyWebApplication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Starts a FlyBoot application:
 * <pre>
 * &#64;FlyBootApplication
 * public class FlyClinic {
 *     public static void main(String[] args) {
 *         FlyApplication.run(FlyClinic.class, args);
 *     }
 * }
 * </pre>
 * Loads the brain, scans components, teaches the fly the endpoints (or loads its synapses from the cache) and
 * serves HTTP until the JVM stops. Properties, with defaults:
 * <ul>
 *   <li>{@code server.port=8080}, {@code server.host=0.0.0.0}</li>
 *   <li>{@code flyboot.brain.data-dir=data} - where converted brains live</li>
 *   <li>{@code flyboot.brain.name=flywire-783} - which brain: {@code flywire-783} (female brain, FlyWire) or
 *       {@code malecns-v1} (male brain and nerve cord, MaleCNS); any {@code <name>.flyb} in the data directory</li>
 *   <li>{@code flyboot.brain.w-syn} - weight of one synapse in mV; default from {@code <name>.brain.properties}</li>
 *   <li>{@code flyboot.brain.engine=cpu} - {@code tornado} simulates on the GPU; needs flyboot-tornado on the
 *       classpath and the TornadoVM launcher ({@code scripts/run-demo.sh --tornado})</li>
 *   <li>{@code flyboot.brain.control=none} - {@code shuffled} rewires the brain randomly, to see what the wiring is worth</li>
 *   <li>{@code flyboot.brain.cache-dir=.flyboot}, {@code none} disables the synapse cache</li>
 *   <li>{@code flyboot.router.sniff-ms=75}, {@code flyboot.router.flies=0} (one per processor),
 *       {@code flyboot.router.min-belief=0.3}, {@code flyboot.router.apl-gain} (default from the brain: 3 for
 *       FlyWire, 1.6 for MaleCNS)</li>
 *   <li>{@code flyboot.learning.samples-per-route=60}, {@code flyboot.learning.decoys-per-route=30},
 *       {@code flyboot.learning.holdout-per-route=10}</li>
 *   <li>{@code flyboot.learning.online.enabled=false}, {@code flyboot.learning.online.rate=0.5} - keep learning from
 *       responses: 2xx rewards the chosen route, 5xx and 405 punish it</li>
 *   <li>{@code flyboot.actuator.enabled=true} - {@code /actuator/health}, {@code /actuator/brain}, {@code /actuator/routes}</li>
 *   <li>{@code flyboot.banner.enabled=true}</li>
 * </ul>
 */
public final class FlyApplication {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    private static final System.Logger LOG = System.getLogger("dev.flyboot");

    private FlyApplication() {
    }

    public static FlyWebApplication run(Class<?> applicationClass, String... args) {
        long started = System.nanoTime();
        configureLogging();
        FlyBootApplication annotation = applicationClass.getAnnotation(FlyBootApplication.class);
        if (annotation == null) {
            throw new IllegalArgumentException(applicationClass.getName() + " is not annotated with @FlyBootApplication");
        }
        ClassLoader loader = applicationClass.getClassLoader();
        FlyProperties props = FlyProperties.load(loader, args);
        if (props.getBoolean("flyboot.banner.enabled", true)) {
            Banner.print(System.out, loader, VERSION);
        }
        String[] packages = annotation.scanBasePackages().length > 0 ? annotation.scanBasePackages()
                : new String[]{applicationClass.getPackageName()};

        Brain brain = loadBrain(props);
        FlyWebApplication.Builder builder = FlyWebApplication.builder()
                .scanPackages(packages)
                .brain(brain)
                .routerOptions(routerOptions(props, brain))
                .cacheDir(cacheDir(props))
                .host(props.get("server.host", "0.0.0.0"))
                .port(props.getInt("server.port", 8080))
                .onlineLearning(props.getBoolean("flyboot.learning.online.enabled", false),
                        props.getDouble("flyboot.learning.online.rate", 0.5))
                .log(message -> LOG.log(System.Logger.Level.INFO, message));
        dev.flyboot.brain.SimulationEngine engine = dev.flyboot.brain.SimulationEngine.byName(
                props.get("flyboot.brain.engine", dev.flyboot.brain.SimulationEngine.CPU));
        LOG.log(System.Logger.Level.INFO, "Simulation engine: " + engine.name() + " (" + engine.device() + ")");
        if (props.getBoolean("flyboot.actuator.enabled", true)) {
            builder.component(BrainActuator.class);
        }
        try {
            FlyWebApplication app = builder.start();
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
                System.out.println("The fly is going to sleep"); // java.util.logging is already reset in shutdown hooks
                app.close();
            }));
            LOG.log(System.Logger.Level.INFO, String.format(Locale.ROOT, "Started %s in %.1f s on %s",
                    applicationClass.getSimpleName(), (System.nanoTime() - started) / 1e9, app.url()));
            return app;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + applicationClass.getSimpleName(), e);
        }
    }

    static Brain loadBrain(FlyProperties props) {
        Path dataDir = props.getPath("flyboot.brain.data-dir", "data");
        String name = props.get("flyboot.brain.name", Brain.DEFAULT_NAME);
        long t = System.nanoTime();
        Brain brain;
        try {
            brain = Brain.load(dataDir, name);
        } catch (IOException e) {
            List<String> available;
            try {
                available = Brain.available(dataDir);
            } catch (IOException ignored) {
                available = List.of();
            }
            throw new UncheckedIOException("Cannot load brain '" + name + "' from " + dataDir.toAbsolutePath()
                    + (available.isEmpty() ? ". Run scripts/download-data.sh and the converters (see README)"
                    : ". Available: " + available + " (flyboot.brain.name)"), e);
        }
        String wSyn = props.get("flyboot.brain.w-syn", null);
        if (wSyn != null) {
            brain = brain.withParams(brain.params().withWSyn(props.getDouble("flyboot.brain.w-syn", 0)));
        }
        String control = props.get("flyboot.brain.control", "none");
        switch (control) {
            case "none" -> {
            }
            case "shuffled" -> {
                brain = brain.shuffled(props.getInt("flyboot.brain.control-seed", 7));
                LOG.log(System.Logger.Level.WARNING, "Control mode: the brain is randomly rewired, expect a clueless fly");
            }
            default -> throw new IllegalArgumentException("flyboot.brain.control must be none or shuffled, got " + control);
        }
        LOG.log(System.Logger.Level.INFO, String.format(Locale.ROOT, "Brain %s: %,d neurons, %,d connections loaded in %d ms",
                brain.connectome().name(), brain.connectome().neuronCount(), brain.connectome().edgeCount(),
                (System.nanoTime() - t) / 1_000_000));
        return brain;
    }

    static Path cacheDir(FlyProperties props) {
        String dir = props.get("flyboot.brain.cache-dir", ".flyboot");
        return dir.equalsIgnoreCase("none") ? null : Path.of(dir);
    }

    static FlyRouter.Options routerOptions(FlyProperties props, Brain brain) {
        FlyRouter.Options d = FlyRouter.Options.defaultsFor(brain);
        return new FlyRouter.Options(
                props.getDouble("flyboot.router.sniff-ms", d.sniffMs()),
                props.getDouble("flyboot.router.apl-gain", d.aplGain()),
                d.channels(),
                props.getInt("flyboot.router.flies", d.flies()),
                props.getInt("flyboot.learning.samples-per-route", d.samplesPerRoute()),
                props.getInt("flyboot.learning.decoys-per-route", d.decoysPerRoute()),
                props.getInt("flyboot.learning.holdout-per-route", d.holdoutPerRoute()),
                props.getInt("flyboot.learning.epochs", d.epochs()),
                props.getDouble("flyboot.learning.rate", d.learningRate()),
                props.getDouble("flyboot.router.min-belief", d.minBelief()),
                props.getInt("flyboot.learning.seed", (int) d.seed()),
                props.get("flyboot.brain.engine", d.engine()));
    }

    /** One line per record, unless the application configured java.util.logging itself. */
    private static void configureLogging() {
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null
                && System.getProperty("java.util.logging.config.file") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT.%1$tL %4$-7s %3$s : %5$s%6$s%n");
        }
    }

    /** Names of properties, for documentation and tests. */
    static List<String> knownProperties() {
        return List.of("server.port", "server.host", "flyboot.brain.data-dir", "flyboot.brain.name",
                "flyboot.brain.engine",
                "flyboot.brain.w-syn", "flyboot.brain.control",
                "flyboot.brain.control-seed", "flyboot.brain.cache-dir", "flyboot.router.sniff-ms",
                "flyboot.router.flies", "flyboot.router.min-belief", "flyboot.router.apl-gain",
                "flyboot.learning.samples-per-route", "flyboot.learning.decoys-per-route",
                "flyboot.learning.holdout-per-route", "flyboot.learning.epochs", "flyboot.learning.rate",
                "flyboot.learning.seed", "flyboot.learning.online.enabled", "flyboot.learning.online.rate",
                "flyboot.actuator.enabled", "flyboot.banner.enabled");
    }
}
