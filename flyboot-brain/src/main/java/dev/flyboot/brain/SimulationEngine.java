package dev.flyboot.brain;

import java.util.List;
import java.util.ServiceLoader;

/**
 * A way to simulate flies. {@code cpu} is built in; other engines (e.g. {@code tornado} on the GPU) are found with
 * {@link ServiceLoader} when their module is on the classpath.
 */
public interface SimulationEngine {

    String CPU = "cpu";

    /** Short name used in configuration, e.g. {@code cpu}, {@code tornado}. */
    String name();

    /** Whether the engine can run in this JVM (e.g. the GPU runtime is present). */
    default boolean available() {
        return true;
    }

    /** Human-readable description of the device, for logs. */
    default String device() {
        return name();
    }

    /** Most simulations worth running at once; a GPU engine shares one device and prefers few. */
    default int maxFlies() {
        return Integer.MAX_VALUE;
    }

    FlySimulation create(Connectome connectome, LifParams params);

    /** The event-driven CPU engine, {@link LifSimulation}. */
    static SimulationEngine cpu() {
        return new SimulationEngine() {
            @Override
            public String name() {
                return CPU;
            }

            @Override
            public String device() {
                return "CPU, " + Runtime.getRuntime().availableProcessors() + " processors";
            }

            @Override
            public FlySimulation create(Connectome connectome, LifParams params) {
                return new LifSimulation(connectome, params);
            }
        };
    }

    /**
     * The engine with this name. {@code cpu} is always there; others must be on the classpath and available.
     *
     * @throws IllegalArgumentException naming the engines that are there
     */
    static SimulationEngine byName(String name) {
        if (name.equalsIgnoreCase(CPU)) {
            return cpu();
        }
        List<SimulationEngine> found = ServiceLoader.load(SimulationEngine.class).stream()
                .map(ServiceLoader.Provider::get).toList();
        for (SimulationEngine e : found) {
            if (e.name().equalsIgnoreCase(name)) {
                if (!e.available()) {
                    throw new IllegalArgumentException("Engine '" + name + "' is on the classpath but cannot run here: "
                            + e.device());
                }
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown engine '" + name + "'; available: cpu"
                + found.stream().map(e -> ", " + e.name()).reduce("", String::concat));
    }
}
