package dev.flyboot.brain.learn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTemplateTest {

    @Test
    void parsesMethodPatternAndVariables() {
        RouteTemplate t = RouteTemplate.of("get /owners/{id}/pets/{petId}", Map.of("id", VarKind.NUMBER));
        assertEquals("GET", t.method());
        assertEquals("GET /owners/{id}/pets/{petId}", t.id());
        assertEquals(Map.of("id", VarKind.NUMBER, "petId", VarKind.ANY), t.vars());
        assertThrows(IllegalArgumentException.class, () -> RouteTemplate.of("/no-method"));
    }

    @Test
    void samplesMatchTheirOwnTemplate() {
        SplittableRandom rnd = new SplittableRandom(1);
        for (String route : List.of("GET /", "GET /pets/{id}", "POST /owners/{id}/pets", "GET /files/{name}.{ext}",
                "GET /v/{uuid}")) {
            RouteTemplate t = RouteTemplate.of(route, Map.of("id", VarKind.NUMBER, "uuid", VarKind.UUID));
            for (int i = 0; i < 200; i++) {
                String path = t.samplePath(rnd);
                assertTrue(t.matches(t.method(), path), route + " vs " + path);
            }
        }
    }

    @Test
    void matchingIsSegmentExactAndMethodAware() {
        RouteTemplate t = RouteTemplate.of("GET /pets/{id}");
        assertTrue(t.matches("GET", "/pets/7"));
        assertTrue(t.matches("get", "/pets/7/?x=1"));
        assertTrue(t.matches("GET", "pets//7"));
        assertFalse(t.matches("POST", "/pets/7"));
        assertFalse(t.matches("GET", "/pets"));
        assertFalse(t.matches("GET", "/pets/7/photos"));
        assertFalse(t.matches("GET", "/petsx/7"));
        assertTrue(RouteTemplate.of("GET /").matches("GET", ""));
        assertTrue(RouteTemplate.of("GET /a.b/{x}").matches("GET", "/a.b/c"));
        assertFalse(RouteTemplate.of("GET /a.b/{x}").matches("GET", "/axb/c"));
    }

    @Test
    void varKindsProduceTheirShapes() {
        SplittableRandom rnd = new SplittableRandom(3);
        for (int i = 0; i < 500; i++) {
            assertTrue(VarKind.NUMBER.sample(rnd).matches("[1-9]\\d{0,8}"));
            assertTrue(VarKind.UUID.sample(rnd).matches("[0-9a-f-]{36}"));
            assertTrue(VarKind.WORD.sample(rnd).matches("[a-z]+(-[a-z]+)?"));
            assertFalse(VarKind.ANY.sample(rnd).isEmpty());
        }
    }
}
