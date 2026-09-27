package dev.flyboot.web.sample;

import com.sun.net.httpserver.HttpExchange;
import dev.flyboot.web.annotation.FlyController;
import dev.flyboot.web.annotation.GetMapping;
import dev.flyboot.web.annotation.PathVariable;
import dev.flyboot.web.annotation.RequestHeader;
import dev.flyboot.web.annotation.RequestMapping;

import java.util.Map;
import java.util.UUID;

@FlyController
public class EchoController {

    @GetMapping("/echo/{word}.{ext}")
    public Map<String, String> echo(@PathVariable String word, @PathVariable String ext,
                                    @RequestHeader(value = "X-Mood", defaultValue = "calm") String mood) {
        return Map.of("word", word, "ext", ext, "mood", mood);
    }

    @GetMapping("/visits/{visitId}")
    public String visit(@PathVariable UUID visitId) {
        return visitId.toString();
    }

    @GetMapping("/boom")
    public void boom() {
        throw new IllegalStateException("kaboom");
    }

    @RequestMapping("/any")
    public String any(HttpExchange exchange) {
        return exchange.getRequestMethod();
    }
}
