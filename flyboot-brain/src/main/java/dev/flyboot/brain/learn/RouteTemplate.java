package dev.flyboot.brain.learn;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An endpoint as the framework declares it, e.g. {@code GET /owners/{id}/pets/{petId}}. Only used to synthesize
 * requests the fly learns from; the fly itself never sees templates.
 *
 * @param method  HTTP method, upper case
 * @param pattern path pattern with {@code {name}} variables
 * @param vars    kind of every variable; variables without an entry are {@link VarKind#ANY}
 */
public record RouteTemplate(String method, String pattern, Map<String, VarKind> vars) {

    private static final Pattern VAR = Pattern.compile("\\{([^}/]+)}");

    public RouteTemplate {
        method = method.toUpperCase(Locale.ROOT);
        Map<String, VarKind> all = new LinkedHashMap<>();
        Matcher m = VAR.matcher(pattern);
        while (m.find()) {
            all.put(m.group(1), vars.getOrDefault(m.group(1), VarKind.ANY));
        }
        vars = Map.copyOf(all);
    }

    /** Parses {@code "GET /pets/{id}"}; every variable is {@link VarKind#ANY}. */
    public static RouteTemplate of(String route) {
        return of(route, Map.of());
    }

    public static RouteTemplate of(String route, Map<String, VarKind> vars) {
        int space = route.indexOf(' ');
        if (space < 0) {
            throw new IllegalArgumentException("Expected \"METHOD /path\", got \"" + route + "\"");
        }
        return new RouteTemplate(route.substring(0, space), route.substring(space + 1).trim(), vars);
    }

    /**
     * Whether the request would be handled by this endpoint in a conventional router. Only the teacher uses this
     * (to keep decoys from colliding with real endpoints); the fly never does.
     */
    public boolean matches(String requestMethod, String path) {
        if (!method.equalsIgnoreCase(requestMethod)) {
            return false;
        }
        return regex().matcher(normalize(path)).matches();
    }

    private Pattern regex() {
        StringBuilder sb = new StringBuilder();
        Matcher m = VAR.matcher(normalize(pattern));
        int last = 0;
        String p = normalize(pattern);
        while (m.find()) {
            sb.append(Pattern.quote(p.substring(last, m.start()))).append("[^/]+");
            last = m.end();
        }
        sb.append(Pattern.quote(p.substring(last)));
        return Pattern.compile(sb.toString());
    }

    /** Leading slash, no trailing slash, no empty segments, no query. */
    static String normalize(String path) {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        return "/" + String.join("/", java.util.Arrays.stream(p.split("/")).filter(s -> !s.isEmpty()).toList());
    }

    public String id() {
        return method + " " + pattern;
    }

    /** A concrete request path with every variable replaced by a random value of its kind. */
    public String samplePath(SplittableRandom rnd) {
        Matcher m = VAR.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(vars.get(m.group(1)).sample(rnd)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @Override
    public String toString() {
        return id();
    }
}
