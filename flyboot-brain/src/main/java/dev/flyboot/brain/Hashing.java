package dev.flyboot.brain;

import java.nio.charset.StandardCharsets;

/** Stable 64-bit hashing: FNV-1a over UTF-8 bytes with the murmur3 finalizer. Same value on every JVM. */
public final class Hashing {

    private Hashing() {
    }

    public static long hash64(String text) {
        long h = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h = (h ^ (b & 0xff)) * 0x100000001b3L;
        }
        return mix(h);
    }

    public static long mix(long x) {
        x = (x ^ (x >>> 33)) * 0xff51afd7ed558ccdL;
        x = (x ^ (x >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return x ^ (x >>> 33);
    }
}
