package dev.flyboot.lab;

import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.NeuronCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** Shared loading of the converted FlyWire v783 brain for lab experiments. */
final class Lab {

    static final Path DATA = Path.of(System.getProperty("flyboot.data", "data"));
    static final String BRAIN = System.getProperty("flyboot.brain.name", dev.flyboot.brain.Brain.DEFAULT_NAME);

    static dev.flyboot.brain.Brain brain() {
        try {
            return dev.flyboot.brain.Brain.load(DATA, BRAIN);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Lab() {
    }

    static Connectome connectome() {
        try {
            long t = System.nanoTime();
            Connectome c = Connectome.read(DATA.resolve("flywire-783.flyb"));
            System.out.printf("Loaded %s: %,d neurons, %,d edges in %d ms%n",
                    c.name(), c.neuronCount(), c.edgeCount(), (System.nanoTime() - t) / 1_000_000);
            return c;
        } catch (IOException e) {
            throw new UncheckedIOException("Run scripts/download-data.sh and ConvertFlyWire first", e);
        }
    }

    static NeuronCatalog catalog() {
        try {
            return NeuronCatalog.read(DATA.resolve("flywire-783.neurons.tsv"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
