package dev.flyboot.brain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Where every neuron is in the fly, in nanometres of the dataset's space ({@code NaN} when unknown). Only used to
 * draw the brain; the simulation does not need it.
 */
public final class NeuronPositions {

    private static final int MAGIC = 0x464C5950; // "FLYP"

    private final float[] xyz;

    public NeuronPositions(float[] xyz) {
        if (xyz.length % 3 != 0) {
            throw new IllegalArgumentException("Expected x, y, z per neuron");
        }
        this.xyz = xyz;
    }

    public static NeuronPositions unknown(int neurons) {
        float[] xyz = new float[neurons * 3];
        java.util.Arrays.fill(xyz, Float.NaN);
        return new NeuronPositions(xyz);
    }

    public int size() {
        return xyz.length / 3;
    }

    public boolean known(int neuron) {
        return !Float.isNaN(xyz[neuron * 3]);
    }

    public float x(int neuron) {
        return xyz[neuron * 3];
    }

    public float y(int neuron) {
        return xyz[neuron * 3 + 1];
    }

    public float z(int neuron) {
        return xyz[neuron * 3 + 2];
    }

    public void set(int neuron, float x, float y, float z) {
        xyz[neuron * 3] = x;
        xyz[neuron * 3 + 1] = y;
        xyz[neuron * 3 + 2] = z;
    }

    public void write(Path file) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 * xyz.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MAGIC).putInt(size());
        buf.asFloatBuffer().put(xyz);
        Files.write(file, buf.array());
    }

    public static NeuronPositions read(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.getInt() != MAGIC) {
                throw new IOException(file + " is not a positions file");
            }
            float[] xyz = new float[buf.getInt() * 3];
            buf.asFloatBuffer().get(xyz);
            return new NeuronPositions(xyz);
        }
    }
}
