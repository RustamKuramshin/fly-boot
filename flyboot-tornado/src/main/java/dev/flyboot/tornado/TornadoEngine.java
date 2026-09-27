package dev.flyboot.tornado;

import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.FlySimulation;
import dev.flyboot.brain.LifParams;
import dev.flyboot.brain.SimulationEngine;

/**
 * GPU engine on TornadoVM. Registered through {@link java.util.ServiceLoader}; this class mentions no TornadoVM
 * type, so on a plain JVM it loads fine and simply reports itself unavailable. Run the application with the
 * {@code tornado} launcher (or {@code java @$TORNADOVM_HOME/tornado-argfile}) and {@code flyboot.brain.engine=tornado}.
 */
public final class TornadoEngine implements SimulationEngine {

    static final String RUNTIME_PROPERTY = "tornado.load.runtime.implementation";

    @Override
    public String name() {
        return "tornado";
    }

    @Override
    public boolean available() {
        if (System.getProperty(RUNTIME_PROPERTY) == null) {
            return false;
        }
        try {
            Class.forName("uk.ac.manchester.tornado.api.TaskGraph");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String device() {
        if (!available()) {
            return "TornadoVM runtime not loaded: start with the tornado launcher or java @$TORNADOVM_HOME/tornado-argfile";
        }
        return TornadoFly.deviceName();
    }

    /** Four flies by default (phase 5: learning 2.6x faster than one, identical result); each keeps its own copy of the graph on the GPU. */
    @Override
    public int maxFlies() {
        return Integer.getInteger("flyboot.tornado.flies", 4);
    }

    @Override
    public FlySimulation create(Connectome connectome, LifParams params) {
        return new TornadoFly(connectome, params);
    }
}
