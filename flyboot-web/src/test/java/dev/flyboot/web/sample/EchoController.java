package dev.flyboot.web.sample;

import com.sun.net.httpserver.HttpExchange;
import dev.flyboot.web.annotation.FlyController;
import dev.flyboot.web.annotation.FlyGetMapping;
import dev.flyboot.web.annotation.FlyPathVariable;
import dev.flyboot.web.annotation.FlyRequestHeader;
import dev.flyboot.web.annotation.FlyRequestMapping;

import java.util.Map;
import java.util.UUID;

@FlyController
public class EchoController {

    @FlyGetMapping("/echo/{word}.{ext}")
    public Map<String, String> echo(@FlyPathVariable String word, @FlyPathVariable String ext,
                                    @FlyRequestHeader(value = "X-Mood", defaultValue = "calm") String mood) {
        return Map.of("word", word, "ext", ext, "mood", mood);
    }

    @FlyGetMapping("/visits/{visitId}")
    public String visit(@FlyPathVariable UUID visitId) {
        return visitId.toString();
    }

    @FlyGetMapping("/boom")
    public void boom() {
        throw new IllegalStateException("kaboom");
    }

    @FlyRequestMapping("/any")
    public String any(HttpExchange exchange) {
        return exchange.getRequestMethod();
    }
}
