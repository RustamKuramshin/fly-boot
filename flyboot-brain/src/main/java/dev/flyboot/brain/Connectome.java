package dev.flyboot.brain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Directed synaptic graph of the brain in CSR form: outgoing edges of neuron {@code i}
 * occupy {@code [rowPtr[i], rowPtr[i + 1])} in {@link #post()} and {@link #weight()}.
 * A weight is the signed synapse count: positive for excitatory, negative for inhibitory presynaptic neurons.
 */
public final class Connectome {

    private static final int MAGIC = 0x464C5942; // "FLYB"
    private static final int VERSION = 1;

    private final String name;
    private final long[] rootIds;
    private final int[] rowPtr;
    private final int[] post;
    private final short[] weight;
    private Map<Long, Integer> indexByRootId;

    public Connectome(String name, long[] rootIds, int[] rowPtr, int[] post, short[] weight) {
        if (rowPtr.length != rootIds.length + 1 || post.length != weight.length || rowPtr[rootIds.length] != post.length) {
            throw new IllegalArgumentException("Inconsistent CSR arrays");
        }
        this.name = name;
        this.rootIds = rootIds;
        this.rowPtr = rowPtr;
        this.post = post;
        this.weight = weight;
    }

    public String name() {
        return name;
    }

    public int neuronCount() {
        return rootIds.length;
    }

    public int edgeCount() {
        return post.length;
    }

    public long rootId(int index) {
        return rootIds[index];
    }

    /** Index of the neuron with the given FlyWire root id, or -1 if the neuron is absent. */
    public int indexOf(long rootId) {
        if (indexByRootId == null) {
            Map<Long, Integer> map = HashMap.newHashMap(rootIds.length);
            for (int i = 0; i < rootIds.length; i++) {
                map.put(rootIds[i], i);
            }
            indexByRootId = map;
        }
        return indexByRootId.getOrDefault(rootId, -1);
    }

    int[] rowPtr() {
        return rowPtr;
    }

    int[] post() {
        return post;
    }

    short[] weight() {
        return weight;
    }

    public long totalSynapses() {
        long sum = 0;
        for (short w : weight) {
            sum += Math.abs(w);
        }
        return sum;
    }

    /**
     * Control graph for "does the wiring matter" experiments: postsynaptic targets are randomly permuted
     * across all edges. Out-degree, in-degree, weights and the sign of every presynaptic neuron are preserved,
     * only who talks to whom is destroyed.
     */
    public Connectome shuffledTargets(long seed) {
        int[] shuffled = post.clone();
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = shuffled.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = shuffled[i];
            shuffled[i] = shuffled[j];
            shuffled[j] = tmp;
        }
        return new Connectome(name + "-shuffled", rootIds, rowPtr, shuffled, weight);
    }

    /**
     * Copy with the outgoing synapse counts of the given neurons multiplied by {@code factor}, rounded and clamped
     * to the short range. Used to strengthen a neuron the LIF model under-represents, e.g. the graded,
     * non-spiking APL.
     */
    public Connectome withScaledOutputs(int[] neurons, double factor) {
        short[] scaled = weight.clone();
        for (int pre : neurons) {
            for (int e = rowPtr[pre]; e < rowPtr[pre + 1]; e++) {
                long w = Math.round(weight[e] * factor);
                scaled[e] = (short) Math.clamp(w, Short.MIN_VALUE, Short.MAX_VALUE);
            }
        }
        return new Connectome(name + "-x" + factor, rootIds, rowPtr, post, scaled);
    }

    public void write(Path file) throws IOException {
        int n = rootIds.length;
        int m = post.length;
        long size = 16L + 8L * n + 4L * (n + 1) + 4L * m + 2L * m;
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_WRITE, 0, size);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(MAGIC).putInt(VERSION).putInt(n).putInt(m);
            buf.asLongBuffer().put(rootIds);
            buf.position(buf.position() + 8 * n);
            buf.asIntBuffer().put(rowPtr);
            buf.position(buf.position() + 4 * (n + 1));
            buf.asIntBuffer().put(post);
            buf.position(buf.position() + 4 * m);
            buf.asShortBuffer().put(weight);
            buf.force();
        }
    }

    public static Connectome read(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.getInt() != MAGIC) {
                throw new IOException(file + " is not a .flyb connectome");
            }
            int version = buf.getInt();
            if (version != VERSION) {
                throw new IOException("Unsupported .flyb version " + version + " in " + file);
            }
            int n = buf.getInt();
            int m = buf.getInt();
            long[] rootIds = new long[n];
            int[] rowPtr = new int[n + 1];
            int[] post = new int[m];
            short[] weight = new short[m];
            buf.asLongBuffer().get(rootIds);
            buf.position(buf.position() + 8 * n);
            buf.asIntBuffer().get(rowPtr);
            buf.position(buf.position() + 4 * (n + 1));
            buf.asIntBuffer().get(post);
            buf.position(buf.position() + 4 * m);
            buf.asShortBuffer().get(weight);
            String name = file.getFileName().toString().replaceFirst("\\.flyb$", "");
            return new Connectome(name, rootIds, rowPtr, post, weight);
        }
    }
}
