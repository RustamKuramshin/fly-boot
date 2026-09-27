package dev.flyboot.tool;

import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.NeuronCatalog;
import dev.flyboot.brain.NeuronPositions;
import org.apache.arrow.compression.CommonsCompressionFactory;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the MaleCNS v1.0 flat connectome (Janelia FlyEM, Cell 2026) into {@code malecns-v1.flyb} and
 * {@code malecns-v1.neurons.tsv}.
 * <ul>
 *   <li>Neurons: bodies with a {@code superclass}, ordered by body id (166,700 in v1.0).</li>
 *   <li>Edges: {@code connectome-weights-...-significant-only}, weight = synapse count.</li>
 *   <li>Sign as in Shiu et al. 2024: acetylcholine, dopamine, octopamine, serotonin excite; GABA and glutamate
 *       inhibit; histamine (photoreceptors) inhibits. An unclear consensus falls back to the cell-type and then
 *       the body prediction, then to excitatory, the majority transmitter.</li>
 *   <li>Annotations are mapped to the FlyWire vocabulary the atlas uses: {@code descending_neuron} becomes
 *       {@code descending}, {@code *_sensory} becomes {@code sensory}, ORNs of DA1, DC3, DL3, VA1d, VA1v get
 *       the {@code pheromone} sub-class.</li>
 * </ul>
 * Usage: {@code ConvertMaleCns [rawDir] [outDir]}, defaults {@code data/raw/malecns} and {@code data}.
 */
public final class ConvertMaleCns {

    static final String NAME = "malecns-v1";
    /**
     * Calibration of phase 5 (BrainProfile + RouterDemo): with 2.3x the synapses of FlyWire
     * and a much stronger APL, the Shiu weight 0.275 mV floods the brain (93% of KCs fire). 0.175 mV brings the
     * projection neurons to FlyWire's regime (82% vs 78% active) and APL x1.6 sparsifies KCs to 7.5%.
     */
    static final String TUNING = """
            # MaleCNS v1.0 calibrated for FlyBoot, phase 5
            neuron.wSynMv=0.175
            router.aplGain=1.6
            """;

    static final Set<String> PHEROMONE = Set.of("ORN_DA1", "ORN_DC3", "ORN_DL3", "ORN_VA1d", "ORN_VA1v");

    public static void main(String[] args) throws Exception {
        Path raw = Path.of(args.length > 0 ? args[0] : "data/raw/malecns").toAbsolutePath();
        Path out = Path.of(args.length > 1 ? args[1] : "data").toAbsolutePath();
        long started = System.nanoTime();
        try (BufferAllocator alloc = new RootAllocator()) {
            List<String[]> neurons = new ArrayList<>();
            read(alloc, raw.resolve("body-annotations-male-cns-v1.0-minconf-0.5.feather"), root -> {
                FieldVector id = root.getVector("bodyId");
                for (int i = 0; i < root.getRowCount(); i++) {
                    String superclass = text(root.getVector("superclass"), i);
                    if (!superclass.isEmpty()) {
                        neurons.add(new String[]{Long.toString(((BigIntVector) id).get(i)), superclass,
                                text(root.getVector("class"), i), text(root.getVector("subclass"), i),
                                text(root.getVector("type"), i), side(root, i), location(root, i)});
                    }
                }
            });
            neurons.sort((a, b) -> Long.compare(Long.parseLong(a[0]), Long.parseLong(b[0])));
            int n = neurons.size();
            long[] ids = new long[n];
            Map<Long, Integer> index = HashMap.newHashMap(n);
            for (int i = 0; i < n; i++) {
                ids[i] = Long.parseLong(neurons.get(i)[0]);
                index.put(ids[i], i);
            }
            System.out.printf("Neurons: %,d%n", n);

            String[] nt = new String[n];
            read(alloc, raw.resolve("body-neurotransmitters-male-cns-v1.0.feather"), root -> {
                BigIntVector body = (BigIntVector) root.getVector("body");
                for (int i = 0; i < root.getRowCount(); i++) {
                    Integer idx = index.get(body.get(i));
                    if (idx != null) {
                        nt[idx] = transmitter(text(root.getVector("consensus_nt"), i),
                                text(root.getVector("celltype_predicted_nt"), i), text(root.getVector("predicted_nt"), i));
                    }
                }
            });
            int[] sign = new int[n];
            Map<String, Integer> ntCounts = new HashMap<>();
            for (int i = 0; i < n; i++) {
                nt[i] = nt[i] == null ? "" : nt[i];
                sign[i] = switch (nt[i]) {
                    case "gaba", "glutamate", "histamine" -> -1;
                    default -> 1;
                };
                ntCounts.merge(nt[i].isEmpty() ? "(none)" : nt[i], 1, Integer::sum);
            }
            System.out.println("Transmitters: " + ntCounts);

            EdgeBuffer edges = new EdgeBuffer();
            read(alloc, raw.resolve("connectome-weights-male-cns-v1.0-minconf-0.5-significant-only.feather"), root -> {
                BigIntVector pre = (BigIntVector) root.getVector("body_pre");
                BigIntVector post = (BigIntVector) root.getVector("body_post");
                BigIntVector w = (BigIntVector) root.getVector("weight");
                for (int i = 0; i < root.getRowCount(); i++) {
                    Integer a = index.get(pre.get(i));
                    Integer b = index.get(post.get(i));
                    if (a != null && b != null) {
                        long count = Math.min(w.get(i), Short.MAX_VALUE);
                        edges.add(a, b, (short) (sign[a] * count));
                    }
                }
            });
            Connectome connectome = edges.toCsr(ids);
            Path flyb = out.resolve(NAME + ".flyb");
            connectome.write(flyb);
            System.out.printf("Edges: %,d, synapses: %,d -> %s (%,d bytes)%n", connectome.edgeCount(),
                    connectome.totalSynapses(), flyb, Files.size(flyb));

            List<NeuronCatalog.Neuron> catalog = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String[] a = neurons.get(i);
                String superClass = superClass(a[1]);
                String subClass = PHEROMONE.contains(a[4]) ? "pheromone" : a[3];
                catalog.add(new NeuronCatalog.Neuron(i, ids[i], flow(a[1]), superClass, a[2], subClass, a[5], a[4], nt[i]));
            }
            Path tsv = out.resolve(NAME + ".neurons.tsv");
            new NeuronCatalog(catalog).write(tsv);
            System.out.printf("Annotations -> %s%n", tsv);
            NeuronPositions positions = NeuronPositions.unknown(n);
            int known = 0;
            for (int i = 0; i < n; i++) {
                String loc = neurons.get(i)[6];
                if (!loc.isEmpty()) {
                    String[] p = loc.split(",");
                    // FlyEM voxels are 8 nm
                    positions.set(i, 8f * Float.parseFloat(p[0]), 8f * Float.parseFloat(p[1]), 8f * Float.parseFloat(p[2]));
                    known++;
                }
            }
            positions.write(out.resolve(NAME + ".positions.bin"));
            System.out.printf("Positions known: %,d of %,d%n", known, n);
            Path tuning = out.resolve(NAME + ".brain.properties");
            Files.writeString(tuning, TUNING);
            System.out.printf("Tuning -> %s%n", tuning);
        }
        System.out.printf("Done in %.1f s%n", (System.nanoTime() - started) / 1e9);
    }

    interface BatchConsumer {
        void accept(VectorSchemaRoot root) throws IOException;
    }

    static void read(BufferAllocator alloc, Path file, BatchConsumer consumer) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
             ArrowFileReader reader = new ArrowFileReader(ch, alloc, CommonsCompressionFactory.INSTANCE)) {
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            while (reader.loadNextBatch()) {
                consumer.accept(root);
            }
        }
    }

    static String text(FieldVector v, int i) {
        Object o = v.getObject(i);
        return o == null ? "" : o.toString().trim();
    }

    /** Soma location, or the "to soma" point for neurons whose soma was not traced, as "x,y,z" voxels. */
    static String location(VectorSchemaRoot root, int i) {
        for (String column : new String[]{"somaLocation", "tosomaLocation"}) {
            Object o = root.getVector(column).getObject(i);
            if (o instanceof java.util.List<?> list && list.size() == 3) {
                return list.get(0) + "," + list.get(1) + "," + list.get(2);
            }
        }
        return "";
    }

    static String side(VectorSchemaRoot root, int i) {
        String soma = text(root.getVector("somaSide"), i);
        return soma.isEmpty() ? text(root.getVector("rootSide"), i) : soma;
    }

    static String transmitter(String consensus, String cellType, String body) {
        for (String s : new String[]{consensus, cellType, body}) {
            if (!s.isEmpty() && !s.equals("unclear") && !s.equals("unknown")) {
                return s;
            }
        }
        return "";
    }

    /** FlyWire-style super class, so the atlas selectors work on both brains. */
    static String superClass(String mcns) {
        return switch (mcns) {
            case "descending_neuron" -> "descending";
            case "ascending_neuron" -> "ascending";
            case "cb_sensory", "ol_sensory", "vnc_sensory" -> "sensory";
            case "cb_motor", "vnc_motor" -> "motor";
            case "cb_intrinsic" -> "central";
            case "ol_intrinsic" -> "optic";
            default -> mcns;
        };
    }

    static String flow(String mcns) {
        if (mcns.contains("sensory")) {
            return "afferent";
        }
        if (mcns.contains("motor") || mcns.contains("efferent") || mcns.contains("endocrine")) {
            return "efferent";
        }
        return "intrinsic";
    }

    /** Growable edge list turned into CSR by a counting sort on the presynaptic index. */
    static final class EdgeBuffer {
        int[] pre = new int[1 << 20];
        int[] post = new int[1 << 20];
        short[] weight = new short[1 << 20];
        int size;

        void add(int a, int b, short w) {
            if (size == pre.length) {
                int cap = size * 2;
                pre = Arrays.copyOf(pre, cap);
                post = Arrays.copyOf(post, cap);
                weight = Arrays.copyOf(weight, cap);
            }
            pre[size] = a;
            post[size] = b;
            weight[size] = w;
            size++;
        }

        Connectome toCsr(long[] ids) {
            int n = ids.length;
            int[] rowPtr = new int[n + 1];
            for (int e = 0; e < size; e++) {
                rowPtr[pre[e] + 1]++;
            }
            for (int i = 0; i < n; i++) {
                rowPtr[i + 1] += rowPtr[i];
            }
            int[] fill = Arrays.copyOf(rowPtr, n);
            int[] csrPost = new int[size];
            short[] csrWeight = new short[size];
            for (int e = 0; e < size; e++) {
                int slot = fill[pre[e]]++;
                csrPost[slot] = post[e];
                csrWeight[slot] = weight[e];
            }
            return new Connectome(NAME, ids, rowPtr, csrPost, csrWeight);
        }
    }
}
