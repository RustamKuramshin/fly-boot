package dev.flyboot.brain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpNoseTest {

    private final HttpNose nose = new HttpNose(SyntheticBrain.create(200, 1).atlas());

    @Test
    void sameRequestAlwaysSmellsTheSame() {
        assertEquals(nose.smell("GET", "/pets/42"), nose.smell("GET", "/pets/42"));
        assertEquals(nose.smell("GET", "/pets/42"), new HttpNose(SyntheticBrain.create(200, 1).atlas()).smell("GET", "/pets/42"));
    }

    @Test
    void methodDrivesItsPheromoneGlomerulus() {
        Odor get = nose.smell("GET", "/pets");
        Odor post = nose.smell("POST", "/pets");
        assertEquals(60, get.rateHz("ORN_DA1"));
        assertEquals(0, get.rateHz("ORN_VA1v"));
        assertEquals(60, post.rateHz("ORN_VA1v"));
        assertEquals(0, post.rateHz("ORN_DA1"));
        // the path part is identical: pheromone glomeruli never carry path features
        get.ratesHz().forEach((glom, rate) -> {
            if (!glom.equals("ORN_DA1")) {
                assertEquals(rate, post.rateHz(glom), glom);
            }
        });
    }

    @Test
    void methodIsCaseInsensitiveAndUnknownMethodsStillSmell() {
        assertEquals(nose.smell("GET", "/pets"), nose.smell("get", "/pets"));
        for (String method : List.of("OPTIONS", "HEAD", "PROPFIND", "BREW")) {
            Odor odor = nose.smell(method, "/pets");
            HttpNose.METHOD_GLOMERULI.values().forEach(g -> assertEquals(0, odor.rateHz(g), method + " " + g));
            assertNotEquals(nose.smell("GET", "/pets").ratesHz().keySet(), odor.ratesHz().keySet());
        }
        assertNotEquals(nose.smell("PROPFIND", "/pets"), nose.smell("BREW", "/pets"));
    }

    @Test
    void queryStringFragmentAndExtraSlashesDoNotSmell() {
        Odor plain = nose.smell("GET", "/pets/42");
        assertEquals(plain, nose.smell("GET", "/pets/42?sort=name&page=2"));
        assertEquals(plain, nose.smell("GET", "pets//42/"));
        assertEquals(plain, nose.smell("GET", "/pets/42#photos"));
        assertEquals(nose.smell("GET", "/"), nose.smell("GET", ""));
    }

    @Test
    void segmentShapes() {
        assertEquals("NUM", HttpNose.shape("42"));
        assertEquals("UUID", HttpNose.shape("3f2b8c1e-9d4a-4f6b-8e2c-1a2b3c4d5e6f"));
        assertEquals("WORD", HttpNose.shape("pets"));
        assertEquals("WORD", HttpNose.shape("Mücke-2"));
        assertEquals("WORD", HttpNose.shape("v1.2_beta~x"));
        assertEquals("OTHER", HttpNose.shape("a%20b"));
        assertEquals(List.of("owners", "7", "pets"), HttpNose.segments("/owners/7/pets/?x=/y"));
    }

    @Test
    void ratesAreCappedAndEveryFeatureHitsDistinctGlomeruli() {
        Odor odor = nose.smell("DELETE", "/a/b/c/d/e/f/g/h/i/j/k/l/m/n");
        odor.ratesHz().values().forEach(rate -> assertTrue(rate <= 60 && rate > 0));
        List<String> gloms = nose.glomeruliOf("0:lit:pets");
        assertEquals(2, gloms.size());
        assertNotEquals(gloms.get(0), gloms.get(1));
        assertFalse(gloms.contains("ORN_DA1"));
    }

    @Test
    void differentValuesOfTheSameTemplateShareTheShape() {
        Odor a = nose.smell("GET", "/pets/1");
        Odor b = nose.smell("GET", "/pets/987654");
        for (String glom : nose.glomeruliOf("1:shape:NUM")) {
            assertTrue(a.rateHz(glom) > 0 && b.rateHz(glom) > 0, glom);
        }
        assertNotEquals(a, b);
    }
}
