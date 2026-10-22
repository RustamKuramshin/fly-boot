package dev.flyboot.boot;

import dev.flyboot.brain.learn.FlyRouter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlyPropertiesTest {

    @Test
    void valuesEnvironmentAndDefaults() {
        FlyProperties p = new FlyProperties(Map.of("server.port", "9090", "flyboot.banner.enabled", "off",
                "blank", "  "), Map.of("FLYBOOT_ROUTER_SNIFF_MS", "50", "SERVER_PORT", "1"));
        assertEquals(9090, p.getInt("server.port", 8080), "explicit values beat the environment");
        assertEquals(50.0, p.getDouble("flyboot.router.sniff-ms", 75), "relaxed environment names");
        assertFalse(p.getBoolean("flyboot.banner.enabled", true));
        assertEquals("fallback", p.get("blank", "fallback"));
        assertEquals(Path.of("data"), p.getPath("flyboot.brain.data-dir", "data"));
    }

    @Test
    void environmentBeatsTheFileAndExplicitValuesBeatTheEnvironment() {
        Map<String, String> file = Map.of("server.port", "8080", "flyboot.brain.data-dir", "../flyboot/data",
                "flyboot.brain.name", "flywire-783");
        Map<String, String> env = Map.of("FLYBOOT_BRAIN_DATA_DIR", "/brains", "SERVER_PORT", "9000");
        FlyProperties p = new FlyProperties(file, env, Map.of("server.port", "7070"));
        assertEquals("/brains", p.get("flyboot.brain.data-dir", "data"), "environment over application.properties");
        assertEquals(7070, p.getInt("server.port", 1), "-D and --key=value over the environment");
        assertEquals("flywire-783", p.get("flyboot.brain.name", "x"), "the file when nothing overrides it");
        assertEquals("fallback", p.get("flyboot.router.sniff-ms", "fallback"));
    }

    @Test
    void commandLineBeatsTheFileAndBadValuesNameTheProperty() {
        FlyProperties p = FlyProperties.load(getClass().getClassLoader(),
                new String[]{"--server.port=7070", "positional", "--flyboot.router.min-belief=0.5"});
        assertEquals(7070, p.getInt("server.port", 8080));
        assertEquals(0.5, p.getDouble("flyboot.router.min-belief", 0.3));
        FlyProperties bad = new FlyProperties(Map.of("server.port", "eighty", "flag", "maybe"), Map.of());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> bad.getInt("server.port", 1)).getMessage()
                .contains("server.port"));
        assertThrows(IllegalArgumentException.class, () -> bad.getBoolean("flag", true));
    }

    @Test
    void routerOptionsDefaultToThePhaseTwoChoiceAndCanBeOverridden() {
        dev.flyboot.brain.Brain brain = brainWithAplGain(1.6);
        FlyRouter.Options defaults = FlyApplication.routerOptions(new FlyProperties(Map.of(), Map.of()), brain);
        assertEquals(FlyRouter.Options.defaults().withAplGain(1.6), defaults, "APL gain comes from the brain");
        FlyRouter.Options tuned = FlyApplication.routerOptions(new FlyProperties(Map.of(
                "flyboot.router.sniff-ms", "50", "flyboot.learning.samples-per-route", "20",
                "flyboot.router.apl-gain", "2"), Map.of()), brain);
        assertEquals(2, tuned.aplGain());
        assertEquals(50, tuned.sniffMs());
        assertEquals(20, tuned.samplesPerRoute());
        assertEquals(defaults.channels(), tuned.channels());
    }

    static dev.flyboot.brain.Brain brainWithAplGain(double gain) {
        dev.flyboot.brain.Connectome c = new dev.flyboot.brain.Connectome("tiny", new long[]{1}, new int[]{0, 0},
                new int[0], new short[0]);
        dev.flyboot.brain.NeuronCatalog catalog = new dev.flyboot.brain.NeuronCatalog(java.util.List.of(
                new dev.flyboot.brain.NeuronCatalog.Neuron(0, 1, "", "", "", "", "", "", "")));
        return new dev.flyboot.brain.Brain(c, catalog, dev.flyboot.brain.LifParams.shiu2024(), gain);
    }

    @Test
    void cacheCanBeDisabled() {
        assertEquals(Path.of(".flyboot"), FlyApplication.cacheDir(new FlyProperties(Map.of(), Map.of())));
        assertNull(FlyApplication.cacheDir(new FlyProperties(Map.of("flyboot.brain.cache-dir", "none"), Map.of())));
    }

    @Test
    void applicationClassMustBeAnnotated() {
        assertThrows(IllegalArgumentException.class, () -> FlyApplication.run(FlyPropertiesTest.class));
    }
}
