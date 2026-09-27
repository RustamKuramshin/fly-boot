package dev.flyboot.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** The startup banner; a {@code banner.txt} on the classpath replaces the default fly. */
final class Banner {

    private static final String DEFAULT = """

              _____ _       ____              _          \\  |  /
             |  ___| |_   _| __ )  ___   ___ | |_     .--.\\ | /.--.
             | |_  | | | | |  _ \\ / _ \\ / _ \\| __|   (    '(o)'    )
             |  _| | | |_| | |_) | (_) | (_) | |_     '--' /|\\ '--'
             |_|   |_|\\__, |____/ \\___/ \\___/ \\__|        / | \\
                      |___/
            """;

    private Banner() {
    }

    static void print(PrintStream out, ClassLoader loader, String version) {
        String text = DEFAULT;
        try (InputStream in = loader.getResourceAsStream("banner.txt")) {
            if (in != null) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // the default fly will do
        }
        out.println(text);
        out.println("  :: FlyBoot ::  (v" + version + ")   routing by Drosophila melanogaster, FlyWire v783");
        out.println();
    }
}
