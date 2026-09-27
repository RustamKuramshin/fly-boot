package dev.flyboot.brain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The fly's nose for HTTP: turns a request method and path into an {@link Odor}.
 * <ul>
 *   <li>A standard method drives its own pheromone glomerulus at the maximum rate
 *       (GET - DA1, POST - VA1v, PUT - VA1d, DELETE - DL3, PATCH - DC3).</li>
 *   <li>Every path feature is hashed onto {@code glomeruliPerFeature} of the remaining glomeruli, each adding
 *       {@code featureHz} up to {@code maxHz}. Features: the segment count, and for every segment its position
 *       together with its shape (number, uuid, word, other) and its literal text.</li>
 * </ul>
 * Like a receptor tuned to a chemical class, the nose senses that a segment is a number, but by default it does
 * not tell one number (or uuid) from another: such literals are values, and smelling them only adds noise that the
 * Kenyon cells amplify (phase 2). The price: endpoints that differ only by a numeric literal, like
 * {@code /errors/404} and {@code /errors/500}, smell the same. The nose never knows which endpoints exist:
 * telling them apart is the brain's job. The query string does not smell.
 */
public final class HttpNose {

    public static final Map<String, String> METHOD_GLOMERULI = Map.of(
            "GET", "ORN_DA1", "POST", "ORN_VA1v", "PUT", "ORN_VA1d", "DELETE", "ORN_DL3", "PATCH", "ORN_DC3");

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}._~-]+");

    private final Map<String, String> methodGlomeruli;
    private final List<String> featureGlomeruli;
    private final double featureHz;
    private final double maxHz;
    private final int glomeruliPerFeature;
    private final boolean methodAsFeature;
    private final boolean valueLiterals;

    /**
     * @param atlas               sensory channels of the brain
     * @param featureHz           rate added to a glomerulus by one feature
     * @param maxHz               rate cap per glomerulus; also the rate of a method glomerulus
     * @param glomeruliPerFeature how many distinct glomeruli one feature drives
     * @param methodAsFeature     whether a standard method is also smelled as a feature on ordinary glomeruli;
     *                            pheromone glomeruli mostly feed the innate lateral horn pathway, so on their own
     *                            they tell the mushroom body little
     * @param valueLiterals       whether the literal text of number and uuid segments is smelled too
     */
    public HttpNose(BrainAtlas atlas, double featureHz, double maxHz, int glomeruliPerFeature, boolean methodAsFeature,
                    boolean valueLiterals) {
        Map<String, String> methods = new HashMap<>();
        METHOD_GLOMERULI.forEach((method, glom) -> {
            if (atlas.glomeruli().containsKey(glom)) {
                methods.put(method, glom);
            }
        });
        this.methodGlomeruli = Map.copyOf(methods);
        this.featureGlomeruli = atlas.glomerulusTypes().stream().filter(t -> !methodGlomeruli.containsValue(t)).toList();
        if (featureGlomeruli.size() < glomeruliPerFeature) {
            throw new IllegalArgumentException("Brain has only " + featureGlomeruli.size() + " feature glomeruli");
        }
        this.featureHz = featureHz;
        this.maxHz = maxHz;
        this.glomeruliPerFeature = glomeruliPerFeature;
        this.methodAsFeature = methodAsFeature;
        this.valueLiterals = valueLiterals;
    }

    /**
     * Defaults chosen in phases 0 and 2: 30 Hz per feature, at most 60 Hz per glomerulus, 2 glomeruli per feature,
     * method only on its pheromone glomerulus, number and uuid values not smelled.
     */
    public HttpNose(BrainAtlas atlas) {
        this(atlas, 30, 60, 2, false, false);
    }

    @Override
    public String toString() {
        return "HttpNose[featureHz=" + featureHz + ", maxHz=" + maxHz + ", glomeruliPerFeature=" + glomeruliPerFeature
                + ", methodAsFeature=" + methodAsFeature + ", valueLiterals=" + valueLiterals + "]";
    }

    public Odor smell(String method, String path) {
        Map<String, Double> rates = new LinkedHashMap<>();
        String m = method.toUpperCase(java.util.Locale.ROOT);
        String methodGlom = methodGlomeruli.get(m);
        List<String> features = new ArrayList<>();
        if (methodGlom != null) {
            rates.put(methodGlom, maxHz);
        }
        if (methodGlom == null || methodAsFeature) {
            features.add("method:" + m);
        }
        List<String> segments = segments(path);
        features.add("len:" + segments.size());
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            String shape = shape(segment);
            features.add(i + ":shape:" + shape);
            if (valueLiterals || !(shape.equals("NUM") || shape.equals("UUID"))) {
                features.add(i + ":lit:" + segment);
            }
        }
        for (String feature : features) {
            for (String glom : glomeruliOf(feature)) {
                rates.merge(glom, featureHz, (a, b) -> Math.min(maxHz, a + b));
            }
        }
        return new Odor(rates);
    }

    /** Path segments without the query string, empty segments and surrounding slashes. */
    static List<String> segments(String path) {
        String p = path;
        int query = p.indexOf('?');
        if (query >= 0) {
            p = p.substring(0, query);
        }
        int fragment = p.indexOf('#');
        if (fragment >= 0) {
            p = p.substring(0, fragment);
        }
        List<String> result = new ArrayList<>();
        for (String s : p.split("/")) {
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        return result;
    }

    static String shape(String segment) {
        if (NUMBER.matcher(segment).matches()) {
            return "NUM";
        }
        if (UUID.matcher(segment).matches()) {
            return "UUID";
        }
        if (WORD.matcher(segment).matches()) {
            return "WORD";
        }
        return "OTHER";
    }

    /** Distinct glomeruli for a feature, chosen by a stable hash of the feature text. */
    List<String> glomeruliOf(String feature) {
        long h = Hashing.hash64(feature);
        List<String> result = new ArrayList<>(glomeruliPerFeature);
        for (int k = 0; result.size() < glomeruliPerFeature; k++) {
            long x = Hashing.mix(h + k * 0x9E3779B97F4A7C15L);
            String glom = featureGlomeruli.get((int) Long.remainderUnsigned(x, featureGlomeruli.size()));
            if (!result.contains(glom)) {
                result.add(glom);
            }
        }
        return result;
    }
}
