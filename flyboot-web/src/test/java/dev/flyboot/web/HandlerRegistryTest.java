package dev.flyboot.web;

import dev.flyboot.brain.learn.VarKind;
import dev.flyboot.web.annotation.GetMapping;
import dev.flyboot.web.annotation.PathVariable;
import dev.flyboot.web.annotation.PostMapping;
import dev.flyboot.web.annotation.RestController;
import dev.flyboot.web.sample.EchoController;
import dev.flyboot.web.sample.PetController;
import dev.flyboot.web.sample.PetStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerRegistryTest {

    @Test
    void collectsEndpointsSortedWithVariableKinds() {
        List<HandlerMethod> handlers = HandlerRegistry.collect(List.of(new PetController(new PetStore()), new EchoController()));
        List<String> ids = handlers.stream().map(h -> h.template().id()).toList();
        assertEquals(List.of("DELETE /any", "DELETE /pets/{id}", "GET /any", "GET /boom", "GET /echo/{word}.{ext}",
                "GET /pets", "GET /pets/{id}", "GET /pets/{id}/name", "GET /visits/{visitId}", "PATCH /any",
                "POST /any", "POST /pets", "PUT /any"), ids);
        HandlerMethod get = handlers.get(ids.indexOf("GET /pets/{id}"));
        assertEquals(Map.of("id", VarKind.NUMBER), get.template().vars());
        assertEquals("PetController#get", get.describe());
        assertEquals(Map.of("visitId", VarKind.UUID), handlers.get(ids.indexOf("GET /visits/{visitId}")).template().vars());
        assertEquals(Map.of("word", VarKind.ANY, "ext", VarKind.ANY), handlers.get(ids.indexOf("GET /echo/{word}.{ext}")).template().vars());
        assertEquals(201, handlers.get(ids.indexOf("POST /pets")).status());
        HandlerMethod echo = handlers.get(ids.indexOf("GET /echo/{word}.{ext}"));
        assertEquals(1, echo.segments().size());
        assertTrue(echo.segments().getFirst().pattern().matcher("hello.json").matches());
    }

    @RestController
    static class Duplicate {
        @GetMapping("/x")
        void a() {
        }

        @GetMapping("x/")
        void b() {
        }
    }

    @RestController
    static class UnknownVariable {
        @GetMapping("/x/{id}")
        void a(@PathVariable long pid) {
        }
    }

    @RestController
    static class UnconvertibleParam {
        @GetMapping("/x")
        void a(Thread thread) {
        }
    }

    @RestController
    static class TwoMappings {
        @GetMapping("/x")
        @PostMapping("/x")
        void a() {
        }
    }

    @Test
    void rejectsMappingsItCannotServe() {
        assertTrue(assertThrows(FlyWebException.class, () -> HandlerRegistry.collect(List.of(new Duplicate())))
                .getMessage().contains("Ambiguous mapping GET /x"));
        assertTrue(assertThrows(FlyWebException.class, () -> HandlerRegistry.collect(List.of(new UnknownVariable())))
                .getMessage().contains("pid is not in /x/{id}"));
        assertTrue(assertThrows(FlyWebException.class, () -> HandlerRegistry.collect(List.of(new UnconvertibleParam())))
                .getMessage().contains("needs @PathVariable"));
        assertTrue(assertThrows(FlyWebException.class, () -> HandlerRegistry.collect(List.of(new TwoMappings())))
                .getMessage().contains("several mapping annotations"));
    }

    @Test
    void normalizesPatterns() {
        assertEquals("/", HandlerRegistry.normalize(""));
        assertEquals("/pets/{id}", HandlerRegistry.normalize("/pets//{id}/"));
    }
}
