package dev.flyboot.brain.learn;

import java.util.SplittableRandom;
import java.util.UUID;

/** What values a path variable takes, used to synthesize training requests. */
public enum VarKind {
    /** Integer ids, 1 to 9 digits. */
    NUMBER,
    /** Canonical UUID strings. */
    UUID,
    /** Lowercase words, optionally hyphenated. */
    WORD,
    /** Anything a String variable may hold: words, numbers, letters mixed with digits. */
    ANY;

    public String sample(SplittableRandom rnd) {
        return switch (this) {
            case NUMBER -> Long.toString(rnd.nextLong(1, pow10(rnd.nextInt(1, 10))));
            case UUID -> new UUID(rnd.nextLong(), rnd.nextLong()).toString();
            case WORD -> word(rnd, 3, 12) + (rnd.nextInt(4) == 0 ? "-" + word(rnd, 2, 8) : "");
            case ANY -> {
                int kind = rnd.nextInt(10);
                if (kind < 5) {
                    yield WORD.sample(rnd);
                } else if (kind < 8) {
                    yield NUMBER.sample(rnd);
                }
                yield word(rnd, 2, 5) + NUMBER.sample(rnd);
            }
        };
    }

    private static long pow10(int digits) {
        long p = 1;
        for (int i = 0; i < digits; i++) {
            p *= 10;
        }
        return p;
    }

    private static String word(SplittableRandom rnd, int min, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = rnd.nextInt(min, max + 1); i < len; i++) {
            sb.append((char) ('a' + rnd.nextInt(26)));
        }
        return sb.toString();
    }
}
