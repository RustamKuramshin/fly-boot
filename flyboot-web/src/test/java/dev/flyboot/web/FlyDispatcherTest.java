package dev.flyboot.web;

import dev.flyboot.web.sample.EchoController;
import dev.flyboot.web.sample.PetController;
import dev.flyboot.web.sample.PetStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP mechanics of the dispatcher with a stand-in oracle instead of the fly: a conventional template matcher,
 * which can also be told to force a choice. The fly itself is tested in FlyWebApplicationTest.
 */
class FlyDispatcherTest {

    static FlyWebServer server;
    static List<HandlerMethod> handlers;
    static volatile BiFunction<String, String, Integer> forced;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        handlers = HandlerRegistry.collect(List.of(new PetController(new PetStore()), new EchoController()));
        RouteOracle oracle = (method, path) -> {
            Integer f = forced == null ? null : forced.apply(method, path);
            int route = f != null ? f : -1;
            for (int i = 0; route < 0 && i < handlers.size(); i++) {
                if (handlers.get(i).template().matches(method, path)) {
                    route = i;
                }
            }
            return new RouteOracle.Verdict(route, route < 0 ? 0.2 : 0.9, 1234, 1.5);
        };
        server = FlyWebServer.start(new InetSocketAddress("127.0.0.1", 0),
                new FlyDispatcher(handlers, oracle, JsonMapper.builder().build()));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    static HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static int route(String id) {
        return handlers.stream().map(h -> h.template().id()).toList().indexOf(id);
    }

    @Test
    void pathVariablesJsonAndFlyHeaders() throws Exception {
        HttpResponse<String> r = send("GET", "/pets/1", null);
        assertEquals(200, r.statusCode());
        assertEquals("{\"id\":1,\"name\":\"Muha\",\"kind\":\"fly\"}", r.body());
        assertEquals("application/json", r.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("GET /pets/{id}", r.headers().firstValue("X-Fly-Route").orElseThrow());
        assertEquals("0.900", r.headers().firstValue("X-Fly-Belief").orElseThrow());
        assertEquals("1234", r.headers().firstValue("X-Fly-Spikes").orElseThrow());
        assertTrue(r.headers().firstValue("X-Fly-Literal-Match").isEmpty());
        assertEquals("text/plain; charset=utf-8", send("GET", "/pets/2/name", null).headers().firstValue("Content-Type").orElseThrow());
        assertEquals("Barsik", send("GET", "/pets/2/name", null).body());
    }

    @Test
    void queryParametersWithDefaultsAndLists() throws Exception {
        assertEquals(3, JsonMapper.builder().build().readTree(send("GET", "/pets", null).body()).size());
        assertEquals(1, JsonMapper.builder().build().readTree(send("GET", "/pets?limit=1", null).body()).size());
        String catsAndDogs = send("GET", "/pets?kind=cat&kind=dog", null).body();
        assertTrue(catsAndDogs.contains("Barsik") && catsAndDogs.contains("Sharik") && !catsAndDogs.contains("Muha"));
        HttpResponse<String> bad = send("GET", "/pets?limit=many", null);
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("Cannot read query parameter limit 'many' as int"));
    }

    @Test
    void bodyStatusAndResponseEntity() throws Exception {
        HttpResponse<String> created = send("POST", "/pets", "{\"name\":\"Zhuzha\",\"kind\":\"bee\"}", "Content-Type", "application/json");
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"name\":\"Zhuzha\""));
        assertEquals(400, send("POST", "/pets", "{not json", "Content-Type", "application/json").statusCode());
        assertEquals(400, send("POST", "/pets", null).statusCode());
        long id = JsonMapper.builder().build().readTree(created.body()).get("id").asLong();
        assertEquals(204, send("DELETE", "/pets/" + id, null).statusCode());
        assertEquals(404, send("DELETE", "/pets/" + id, null).statusCode());
    }

    @Test
    void handlerErrorsBecomeJsonErrors() throws Exception {
        HttpResponse<String> missing = send("GET", "/pets/999", null);
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("\"message\":\"No pet 999\""));
        assertTrue(missing.body().contains("\"route\":\"GET /pets/{id}\""));
        HttpResponse<String> boom = send("GET", "/boom", null);
        assertEquals(500, boom.statusCode());
        assertTrue(boom.body().contains("IllegalStateException: kaboom"));
        assertEquals(400, send("GET", "/pets/abc", null).statusCode(), "matches no template, but see forced below");
    }

    @Test
    void partialSegmentsHeadersUuidAndRawExchange() throws Exception {
        assertEquals("{\"word\":\"hello\",\"ext\":\"json\",\"mood\":\"calm\"}".length(),
                send("GET", "/echo/hello.json", null).body().length());
        assertTrue(send("GET", "/echo/hi.txt", null, "X-Mood", "buzzing").body().contains("\"mood\":\"buzzing\""));
        assertEquals("3f2b8c1e-9d4a-4f6b-8e2c-1a2b3c4d5e6f",
                send("GET", "/visits/3f2b8c1e-9d4a-4f6b-8e2c-1a2b3c4d5e6f", null).body());
        assertEquals(400, send("GET", "/visits/not-a-uuid", null).statusCode());
        assertEquals("PATCH", send("PATCH", "/any", null).body());
    }

    @Test
    void unknownSmellIs404() throws Exception {
        HttpResponse<String> r = send("GET", "/nowhere/at/all", null);
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("does not recognize the smell of GET /nowhere/at/all"));
        assertTrue(r.body().contains("\"route\":null"));
        assertTrue(r.headers().firstValue("X-Fly-Route").isEmpty());
    }

    @Test
    void theFlysChoiceIsTrustedEvenWhenItRoutesByResemblance() throws Exception {
        forced = (m, p) -> p.startsWith("/cats/") ? route("GET /pets/{id}") : p.equals("/pets") && m.equals("PATCH")
                ? route("GET /pets") : p.equals("/short") ? route("GET /pets/{id}/name") : null;
        try {
            HttpResponse<String> cat = send("GET", "/cats/2", null);
            assertEquals(200, cat.statusCode(), "variables are read by position");
            assertTrue(cat.body().contains("Barsik"));
            assertEquals("false", cat.headers().firstValue("X-Fly-Literal-Match").orElseThrow());

            HttpResponse<String> patch = send("PATCH", "/pets", null);
            assertEquals(405, patch.statusCode());
            assertEquals("GET", patch.headers().firstValue("Allow").orElseThrow());

            HttpResponse<String> shortPath = send("GET", "/short", null);
            assertEquals(400, shortPath.statusCode());
            assertTrue(shortPath.body().contains("no segment for {id}"));
        } finally {
            forced = null;
        }
    }
}
