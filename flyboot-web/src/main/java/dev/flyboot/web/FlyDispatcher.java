package dev.flyboot.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

/**
 * Answers every HTTP request. The oracle - the fly - decides which handler gets the request; the dispatcher only
 * carries out the decision: it binds the arguments, invokes the handler and writes the response.
 * <p>
 * What the dispatcher checks itself, after the decision: the request method against the chosen handler's
 * (405, a protocol rule the fly cannot smell), and that the path supplies every path variable in a convertible
 * form (400). It never matches path patterns to choose a handler.
 * <p>
 * Every response carries the fly's verdict: {@code X-Fly-Route}, {@code X-Fly-Belief}, {@code X-Fly-Spikes},
 * {@code X-Fly-Sniff-Ms}, and {@code X-Fly-Literal-Match: false} when the chosen pattern's literal segments differ
 * from the path, i.e. the fly routed by resemblance.
 */
public final class FlyDispatcher implements HttpHandler {

    private static final System.Logger LOG = System.getLogger("dev.flyboot.web");

    private final List<HandlerMethod> handlers;
    private final RouteOracle oracle;
    private final JsonMapper json;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong recognized = new AtomicLong();

    public FlyDispatcher(List<HandlerMethod> handlers, RouteOracle oracle, JsonMapper json) {
        this.handlers = List.copyOf(handlers);
        this.oracle = oracle;
        this.json = json;
    }

    public List<HandlerMethod> handlers() {
        return handlers;
    }

    public long requests() {
        return requests.get();
    }

    public long recognized() {
        return recognized.get();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String rawPath = exchange.getRequestURI().getRawPath() == null ? "/" : exchange.getRequestURI().getRawPath();
        try (exchange) {
            requests.incrementAndGet();
            RouteOracle.Verdict v = oracle.consult(method, rawPath);
            int status = answer(exchange, method, rawPath, v);
            long ms = (System.nanoTime() - started) / 1_000_000;
            LOG.log(System.Logger.Level.INFO, () -> String.format(Locale.ROOT,
                    "%s %s -> %s: %d in %d ms (belief %.2f, %d spikes, sniff %.0f ms)", method, rawPath,
                    v.recognized() ? handlers.get(v.route()).template().id() + " " + handlers.get(v.route()).describe()
                            : "unknown smell", status, ms, v.belief(), v.spikes(), v.sniffMs()));
        } catch (RuntimeException | IOException e) {
            LOG.log(System.Logger.Level.ERROR, method + " " + rawPath + " failed", e);
            throw e;
        }
    }

    private int answer(HttpExchange exchange, String method, String rawPath, RouteOracle.Verdict v) throws IOException {
        Headers out = exchange.getResponseHeaders();
        out.set("X-Fly-Belief", String.format(Locale.ROOT, "%.3f", v.belief()));
        out.set("X-Fly-Spikes", Long.toString(v.spikes()));
        out.set("X-Fly-Sniff-Ms", String.format(Locale.ROOT, "%.1f", v.sniffMs()));
        if (!v.recognized()) {
            return error(exchange, 404, "The fly does not recognize the smell of " + method + " " + rawPath, rawPath, v);
        }
        HandlerMethod h = handlers.get(v.route());
        recognized.incrementAndGet();
        out.set("X-Fly-Route", h.template().id());
        List<String> segments;
        try {
            segments = segments(rawPath);
        } catch (BadRequest e) {
            return error(exchange, 400, e.getMessage(), rawPath, v);
        }
        if (!literalsMatch(h, segments)) {
            out.set("X-Fly-Literal-Match", "false");
        }
        if (!h.httpMethod().equals(method)) {
            out.set("Allow", h.httpMethod());
            return error(exchange, 405, "The fly took " + method + " " + rawPath + " for " + h.template().id()
                    + ", which does not allow " + method, rawPath, v);
        }
        return invoke(exchange, h, segments, rawPath, v);
    }

    private int invoke(HttpExchange exchange, HandlerMethod h, List<String> segments, String rawPath,
                       RouteOracle.Verdict v) throws IOException {
        Object[] args;
        try {
            args = arguments(exchange, h, segments);
        } catch (BadRequest e) {
            return error(exchange, 400, e.getMessage(), rawPath, v);
        }
        Object result;
        try {
            result = h.method().invoke(h.bean(), args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ResponseStatusException rse) {
                return error(exchange, rse.status(), rse.getMessage(), rawPath, v);
            }
            LOG.log(System.Logger.Level.ERROR, h.describe() + " failed on " + rawPath, cause);
            return error(exchange, 500, cause.getClass().getSimpleName() + ": " + cause.getMessage(), rawPath, v);
        } catch (IllegalAccessException e) {
            return error(exchange, 500, e.getMessage(), rawPath, v);
        }
        return write(exchange, h.status(), result);
    }

    private Object[] arguments(HttpExchange exchange, HandlerMethod h, List<String> segments) throws IOException {
        Map<String, String> pathVars = pathVariables(h, segments);
        Map<String, List<String>> query = query(exchange.getRequestURI().getRawQuery());
        Object[] args = new Object[h.params().size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = switch (h.params().get(i)) {
                case ParamBinding.PathVar p -> convert(pathVars.get(p.name()), p.type(), "path variable " + p.name());
                case ParamBinding.QueryParam p -> queryValue(p, query.get(p.name()));
                case ParamBinding.Header p -> headerValue(p, exchange.getRequestHeaders().getFirst(p.name()));
                case ParamBinding.Body p -> body(exchange, p);
                case ParamBinding.Exchange p -> exchange;
            };
        }
        return args;
    }

    /** Reads variables from the segments at the pattern's variable positions: the fly's choice is trusted. */
    private static Map<String, String> pathVariables(HandlerMethod h, List<String> segments) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (HandlerMethod.VarSegment vs : h.segments()) {
            if (vs.index() >= segments.size()) {
                throw new BadRequest("The fly chose " + h.template().id() + " but the path has no segment for {"
                        + String.join("}, {", vs.names()) + "}");
            }
            Matcher m = vs.pattern().matcher(segments.get(vs.index()));
            if (!m.matches()) {
                throw new BadRequest("The fly chose " + h.template().id() + " but segment '" + segments.get(vs.index())
                        + "' does not fit " + vs.pattern().pattern());
            }
            for (int g = 0; g < vs.names().size(); g++) {
                vars.put(vs.names().get(g), m.group(g + 1));
            }
        }
        return vars;
    }

    private static boolean literalsMatch(HandlerMethod h, List<String> segments) {
        List<String> pattern = Arrays.stream(h.pattern().split("/")).filter(s -> !s.isEmpty()).toList();
        if (pattern.size() != segments.size()) {
            return false;
        }
        for (int i = 0; i < pattern.size(); i++) {
            if (!pattern.get(i).contains("{") && !pattern.get(i).equals(segments.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static Object queryValue(ParamBinding.QueryParam p, List<String> values) {
        List<String> v = values;
        if (v == null || v.isEmpty()) {
            if (!p.defaultValue().equals(dev.flyboot.web.annotation.ValueConstants.NONE)) {
                v = List.of(p.defaultValue());
            } else if (p.required()) {
                throw new BadRequest("Required query parameter '" + p.name() + "' is missing");
            } else {
                return p.list() ? List.of() : TypeConversion.defaultFor(p.type());
            }
        }
        if (p.list()) {
            List<Object> out = new ArrayList<>();
            for (String s : v) {
                out.add(convert(s, p.type(), "query parameter " + p.name()));
            }
            return List.copyOf(out);
        }
        return convert(v.getFirst(), p.type(), "query parameter " + p.name());
    }

    private static Object headerValue(ParamBinding.Header p, String value) {
        if (value == null) {
            if (!p.defaultValue().equals(dev.flyboot.web.annotation.ValueConstants.NONE)) {
                return convert(p.defaultValue(), p.type(), "header " + p.name());
            }
            if (p.required()) {
                throw new BadRequest("Required header '" + p.name() + "' is missing");
            }
            return TypeConversion.defaultFor(p.type());
        }
        return convert(value, p.type(), "header " + p.name());
    }

    private Object body(HttpExchange exchange, ParamBinding.Body p) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            if (p.required()) {
                throw new BadRequest("Required request body is missing");
            }
            return null;
        }
        if (p.type() == byte[].class) {
            return bytes;
        }
        if (p.type() == String.class) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            JavaType type = json.getTypeFactory().constructType(p.type());
            return json.readValue(bytes, type);
        } catch (JacksonException e) {
            throw new BadRequest("Malformed JSON body: " + e.getOriginalMessage());
        }
    }

    private static Object convert(String text, Class<?> type, String what) {
        try {
            return TypeConversion.convert(text, type);
        } catch (IllegalArgumentException e) {
            throw new BadRequest("Cannot read " + what + " '" + text + "' as " + type.getSimpleName());
        }
    }

    private int write(HttpExchange exchange, int defaultStatus, Object result) throws IOException {
        int status = defaultStatus;
        Object body = result;
        if (result instanceof ResponseEntity<?> entity) {
            status = entity.status();
            entity.headers().forEach(exchange.getResponseHeaders()::set);
            body = entity.body();
        }
        byte[] bytes;
        String contentType;
        switch (body) {
            case null -> {
                bytes = new byte[0];
                contentType = null;
            }
            case String s -> {
                bytes = s.getBytes(StandardCharsets.UTF_8);
                contentType = "text/plain; charset=utf-8";
            }
            case byte[] b -> {
                bytes = b;
                contentType = "application/octet-stream";
            }
            default -> {
                bytes = json.writeValueAsBytes(body);
                contentType = "application/json";
            }
        }
        if (contentType != null && !exchange.getResponseHeaders().containsKey("Content-Type")) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        send(exchange, status, bytes);
        return status;
    }

    private int error(HttpExchange exchange, int status, String message, String path, RouteOracle.Verdict v)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", reason(status));
        body.put("message", message);
        body.put("path", path);
        Map<String, Object> fly = new LinkedHashMap<>();
        fly.put("route", v.recognized() ? handlers.get(v.route()).template().id() : null);
        fly.put("belief", Math.round(v.belief() * 1000) / 1000.0);
        fly.put("spikes", v.spikes());
        body.put("fly", fly);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        send(exchange, status, json.writeValueAsBytes(body));
        return status;
    }

    private static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        boolean noBody = status == 204 || status == 304 || exchange.getRequestMethod().equalsIgnoreCase("HEAD");
        exchange.sendResponseHeaders(status, noBody ? -1 : bytes.length == 0 ? -1 : bytes.length);
        if (!noBody && bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /** Decoded non-empty path segments; '+' stays a plus in paths. */
    static List<String> segments(String rawPath) {
        List<String> out = new ArrayList<>();
        for (String s : rawPath.split("/")) {
            if (!s.isEmpty()) {
                out.add(decode(s.replace("+", "%2B")));
            }
        }
        return out;
    }

    static Map<String, List<String>> query(String rawQuery) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequest("Malformed percent-encoding in '" + s + "'");
        }
    }

    static String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Content";
            case 500 -> "Internal Server Error";
            default -> status >= 500 ? "Server Error" : status >= 400 ? "Client Error" : "OK";
        };
    }

    /** A request the handler cannot be called with. */
    static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message, null, false, false);
        }
    }
}
