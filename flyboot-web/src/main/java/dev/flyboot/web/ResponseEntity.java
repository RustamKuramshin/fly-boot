package dev.flyboot.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full control over the response: status, headers and body.
 *
 * @param status  HTTP status
 * @param headers extra response headers
 * @param body    body, written like a handler's return value; null for none
 */
public record ResponseEntity<T>(int status, Map<String, String> headers, T body) {

    public ResponseEntity {
        headers = Map.copyOf(headers);
    }

    public static <T> ResponseEntity<T> ok(T body) {
        return new ResponseEntity<>(200, Map.of(), body);
    }

    public static <T> ResponseEntity<T> created(String location, T body) {
        return new ResponseEntity<>(201, Map.of("Location", location), body);
    }

    public static <T> ResponseEntity<T> status(int status, T body) {
        return new ResponseEntity<>(status, Map.of(), body);
    }

    public static ResponseEntity<Void> noContent() {
        return new ResponseEntity<>(204, Map.of(), null);
    }

    public ResponseEntity<T> header(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new ResponseEntity<>(status, h, body);
    }
}
