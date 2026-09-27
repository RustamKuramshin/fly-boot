package dev.flyboot.tornado;

import dev.flyboot.brain.SimulationEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * On a plain JVM the GPU engine must stay out of the way. Its numerics are compared with the CPU engine by
 * dev.flyboot.lab.EngineCompare under the TornadoVM launcher.
 */
class TornadoEngineTest {

    @Test
    void isRegisteredButUnavailableWithoutTheTornadoRuntime() {
        assumeTrue(System.getProperty(TornadoEngine.RUNTIME_PROPERTY) == null, "running under TornadoVM");
        TornadoEngine engine = new TornadoEngine();
        assertFalse(engine.available());
        assertTrue(engine.device().contains("tornado-argfile"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SimulationEngine.byName("tornado"));
        assertTrue(e.getMessage().contains("cannot run here"), e.getMessage());
    }
}
