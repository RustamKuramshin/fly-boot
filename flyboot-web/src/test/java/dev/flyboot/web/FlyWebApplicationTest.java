package dev.flyboot.web;

import dev.flyboot.brain.Brain;
import dev.flyboot.brain.learn.FlyRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The whole stack with the real fly: scanning, learning the sample endpoints, routing real HTTP requests. */
class FlyWebApplicationTest {

    record Probe(String method, String path, String expectedRoute) {
    }

    static final List<Probe> PROBES = List.of(
            new Probe("GET", "/pets", "GET /pets"),
            new Probe("GET", "/pets?limit=2", "GET /pets"),
            new Probe("POST", "/pets", "POST /pets"),
            new Probe("GET", "/pets/1", "GET /pets/{id}"),
            new Probe("GET", "/pets/2", "GET /pets/{id}"),
            new Probe("GET", "/pets/31337", "GET /pets/{id}"),
            new Probe("DELETE", "/pets/77", "DELETE /pets/{id}"),
            new Probe("DELETE", "/pets/1234567", "DELETE /pets/{id}"),
            new Probe("GET", "/pets/1/name", "GET /pets/{id}/name"),
            new Probe("GET", "/pets/2/name", "GET /pets/{id}/name"),
            new Probe("GET", "/echo/hello.json", "GET /echo/{word}.{ext}"),
            new Probe("GET", "/echo/buzz.txt", "GET /echo/{word}.{ext}"),
            new Probe("GET", "/visits/3f2b8c1e-9d4a-4f6b-8e2c-1a2b3c4d5e6f", "GET /visits/{visitId}"),
            new Probe("GET", "/visits/00000000-0000-0000-0000-000000000001", "GET /visits/{visitId}"),
            new Probe("GET", "/boom", "GET /boom"),
            new Probe("GET", "/any", "GET /any"),
            new Probe("PUT", "/any", "PUT /any"),
            new Probe("DELETE", "/any", "DELETE /any"),
            new Probe("PATCH", "/any", "PATCH /any"),
            new Probe("POST", "/any", "POST /any"));

    @Test
    void theFlyRoutesRealHttpRequests(@TempDir Path cache, TestReporter reporter) throws Exception {
        Path data = Path.of(System.getProperty("flyboot.data", "../data"));
        assumeTrue(Files.exists(data.resolve(Brain.DEFAULT_NAME + ".flyb")), "no brain data");
        List<String> log = new ArrayList<>();
        try (FlyWebApplication app = FlyWebApplication.builder()
                .scanPackages("dev.flyboot.web.sample")
                .brain(Brain.load(data))
                .routerOptions(FlyRouter.Options.defaults().withSamples(40, 20, 10))
                .cacheDir(cache)
                .port(0)
                .log(log::add)
                .start()) {
            assertEquals(13, app.dispatcher().handlers().size());
            assertTrue(log.stream().anyMatch(l -> l.startsWith("The fly knows 13 endpoints")), String.join("\n", log));
            HttpClient http = HttpClient.newHttpClient();
            int correct = 0;
            List<String> misses = new ArrayList<>();
            for (Probe p : PROBES) {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(app.url() + p.path()));
                b.method(p.method(), p.method().equals("POST") && p.path().equals("/pets")
                        ? HttpRequest.BodyPublishers.ofString("{\"name\":\"Zhuzha\",\"kind\":\"bee\"}")
                        : HttpRequest.BodyPublishers.noBody());
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                String route = r.headers().firstValue("X-Fly-Route").orElse("404");
                if (route.equals(p.expectedRoute())) {
                    correct++;
                } else {
                    misses.add(p.method() + " " + p.path() + " -> " + route + " (" + r.statusCode() + ")");
                }
            }
            reporter.publishEntry("routing", "Fly routed " + correct + "/" + PROBES.size() + " correctly; misses: " + misses);
            assertTrue(correct >= 17, "correct " + correct + "/" + PROBES.size() + ", misses: " + misses);
            assertEquals(PROBES.size(), app.dispatcher().requests());
        }
    }
}
