package dev.flyboot.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Configuration, from lowest to highest precedence: {@code application.properties} on the classpath, environment
 * variables ({@code SERVER_PORT} for {@code server.port}), system properties ({@code -Dserver.port=9090}) and
 * command-line arguments ({@code --server.port=9090}).
 */
public final class FlyProperties {

    private final Map<String, String> values;
    private final Map<String, String> environment;

    FlyProperties(Map<String, String> values, Map<String, String> environment) {
        this.values = new TreeMap<>(values);
        this.environment = Map.copyOf(environment);
    }

    public static FlyProperties load(ClassLoader loader, String[] args) {
        Map<String, String> values = new TreeMap<>();
        try (InputStream in = loader.getResourceAsStream("application.properties")) {
            if (in != null) {
                Properties file = new Properties();
                file.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                file.stringPropertyNames().forEach(k -> values.put(k, file.getProperty(k).trim()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read application.properties", e);
        }
        System.getProperties().stringPropertyNames().forEach(k -> values.put(k, System.getProperty(k)));
        for (String arg : args) {
            if (arg.startsWith("--") && arg.indexOf('=') > 2) {
                values.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
            }
        }
        return new FlyProperties(values, System.getenv());
    }

    /** Value of the key; an environment variable is used when the key is set nowhere else. */
    public String get(String key, String defaultValue) {
        String v = values.get(key);
        if (v == null) {
            v = environment.get(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        }
        return v == null || v.isBlank() ? defaultValue : v.trim();
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key, null);
        try {
            return v == null ? defaultValue : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property " + key + " must be an integer, got '" + v + "'");
        }
    }

    public double getDouble(String key, double defaultValue) {
        String v = get(key, null);
        try {
            return v == null ? defaultValue : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property " + key + " must be a number, got '" + v + "'");
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key, null);
        if (v == null) {
            return defaultValue;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException("Property " + key + " must be true or false, got '" + v + "'");
        };
    }

    public Path getPath(String key, String defaultValue) {
        return Path.of(get(key, defaultValue));
    }
}
