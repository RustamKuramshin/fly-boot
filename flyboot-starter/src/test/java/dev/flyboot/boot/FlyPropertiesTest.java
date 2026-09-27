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
        FlyRouter.Options defaults = FlyApplication.routerOptions(new FlyProperties(Map.of(), Map.of()));
        assertEquals(FlyRouter.Options.defaults(), defaults);
        FlyRouter.Options tuned = FlyApplication.routerOptions(new FlyProperties(Map.of(
                "flyboot.router.sniff-ms", "50", "flyboot.learning.samples-per-route", "20"), Map.of()));
        assertEquals(50, tuned.sniffMs());
        assertEquals(20, tuned.samplesPerRoute());
        assertEquals(defaults.channels(), tuned.channels());
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
