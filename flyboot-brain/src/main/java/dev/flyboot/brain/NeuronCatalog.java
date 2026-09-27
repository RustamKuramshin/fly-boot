package dev.flyboot.brain;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Per-neuron annotations aligned with connectome indices. Unknown values are empty strings. */
public final class NeuronCatalog {

    public record Neuron(int index, long rootId, String flow, String superClass, String cellClass,
                         String subClass, String side, String type, String nt) {
    }

    private static final String HEADER = "index\troot_id\tflow\tsuper_class\tclass\tsub_class\tside\ttype\tnt";

    private final List<Neuron> neurons;

    public NeuronCatalog(List<Neuron> neurons) {
        this.neurons = List.copyOf(neurons);
    }

    public int size() {
        return neurons.size();
    }

    public Neuron get(int index) {
        return neurons.get(index);
    }

    public int[] select(Predicate<Neuron> filter) {
        return neurons.stream().filter(filter).mapToInt(Neuron::index).toArray();
    }

    /** Neurons grouped by cell type, types sorted by name. */
    public Map<String, int[]> groupByType(Predicate<Neuron> filter) {
        Map<String, List<Integer>> groups = new TreeMap<>();
        for (Neuron n : neurons) {
            if (filter.test(n)) {
                groups.computeIfAbsent(n.type(), k -> new ArrayList<>()).add(n.index());
            }
        }
        Map<String, int[]> result = new TreeMap<>();
        groups.forEach((type, idx) -> result.put(type, idx.stream().mapToInt(Integer::intValue).toArray()));
        return result;
    }

    public void write(Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            out.write(HEADER);
            out.newLine();
            for (Neuron n : neurons) {
                out.write(String.join("\t", Integer.toString(n.index()), Long.toString(n.rootId()), n.flow(),
                        n.superClass(), n.cellClass(), n.subClass(), n.side(), n.type(), n.nt()));
                out.newLine();
            }
        }
    }

    public static NeuronCatalog read(Path file) throws IOException {
        List<Neuron> neurons = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file)) {
            in.readLine();
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\t", -1);
                neurons.add(new Neuron(Integer.parseInt(f[0]), Long.parseLong(f[1]), f[2], f[3], f[4], f[5], f[6], f[7], f[8]));
            }
        }
        return new NeuronCatalog(neurons);
    }
}
