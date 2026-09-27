package dev.flyboot.tool;

import dev.flyboot.brain.Connectome;
import dev.flyboot.brain.NeuronCatalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts FlyWire v783 as prepared by Shiu et al. 2024 ({@code Connectivity_783.parquet},
 * {@code Completeness_783.csv}) plus Codex annotations into {@code flywire-783.flyb} and
 * {@code flywire-783.neurons.tsv}.
 * <p>
 * Usage: {@code ConvertFlyWire [rawDir] [outDir]}, defaults {@code data/raw} and {@code data}.
 */
public final class ConvertFlyWire {

    public static void main(String[] args) throws Exception {
        Path raw = Path.of(args.length > 0 ? args[0] : "data/raw").toAbsolutePath();
        Path out = Path.of(args.length > 1 ? args[1] : "data").toAbsolutePath();
        long started = System.nanoTime();

        long[] rootIds = readCompleteness(raw.resolve("Completeness_783.csv"));
        int n = rootIds.length;
        System.out.printf("Neurons: %,d%n", n);

        try (Connection db = DriverManager.getConnection("jdbc:duckdb:")) {
            Connectome connectome = readConnectivity(db, raw.resolve("Connectivity_783.parquet"), rootIds);
            Path flyb = out.resolve("flywire-783.flyb");
            connectome.write(flyb);
            System.out.printf("Edges: %,d, synapses: %,d -> %s (%,d bytes)%n",
                    connectome.edgeCount(), connectome.totalSynapses(), flyb, Files.size(flyb));

            NeuronCatalog catalog = readAnnotations(db, raw, rootIds);
            Path tsv = out.resolve("flywire-783.neurons.tsv");
            catalog.write(tsv);
            System.out.printf("Annotations -> %s%n", tsv);
        }
        System.out.printf("Done in %.1f s%n", (System.nanoTime() - started) / 1e9);
    }

    /** Row order of Completeness_783.csv defines the neuron index used by the parquet file. */
    private static long[] readCompleteness(Path file) throws IOException {
        List<Long> ids = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file)) {
            in.readLine();
            String line;
            while ((line = in.readLine()) != null) {
                ids.add(Long.parseLong(line.substring(0, line.indexOf(','))));
            }
        }
        return ids.stream().mapToLong(Long::longValue).toArray();
    }

    private static Connectome readConnectivity(Connection db, Path parquet, long[] rootIds) throws SQLException {
        int n = rootIds.length;
        int m;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + parquet + "')")) {
            rs.next();
            m = rs.getInt(1);
        }
        int[] rowPtr = new int[n + 1];
        int[] post = new int[m];
        short[] weight = new short[m];
        String sql = """
                SELECT Presynaptic_Index, Postsynaptic_Index, "Excitatory x Connectivity", Presynaptic_ID, Postsynaptic_ID
                FROM read_parquet('%s')
                ORDER BY Presynaptic_Index, Postsynaptic_Index
                """.formatted(parquet);
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int e = 0;
            while (rs.next()) {
                int pre = rs.getInt(1);
                int to = rs.getInt(2);
                int w = rs.getInt(3);
                // The parquet carries both the index and the FlyWire id; a mismatch means a different Completeness file.
                if (rootIds[pre] != rs.getLong(4) || rootIds[to] != rs.getLong(5)) {
                    throw new IllegalStateException("Index/root id mismatch at edge " + e + ": pre " + pre + ", post " + to);
                }
                if (w < Short.MIN_VALUE || w > Short.MAX_VALUE) {
                    throw new IllegalStateException("Synapse count " + w + " does not fit into short at edge " + e);
                }
                rowPtr[pre + 1]++;
                post[e] = to;
                weight[e] = (short) w;
                e++;
            }
        }
        for (int i = 0; i < n; i++) {
            rowPtr[i + 1] += rowPtr[i];
        }
        return new Connectome("flywire-783", rootIds, rowPtr, post, weight);
    }

    private static NeuronCatalog readAnnotations(Connection db, Path raw, long[] rootIds) throws SQLException {
        String sql = """
                SELECT c.root_id, c.flow, c.super_class, c.class, c.sub_class, c.side, t.primary_type, n.nt_type
                FROM read_csv('%s', all_varchar = true) c
                LEFT JOIN read_csv('%s', all_varchar = true) t ON t.root_id = c.root_id
                LEFT JOIN read_csv('%s', all_varchar = true) n ON n.root_id = c.root_id
                """.formatted(raw.resolve("classification.csv.gz"), raw.resolve("consolidated_cell_types.csv.gz"),
                raw.resolve("neurons.csv.gz"));
        Map<Long, String[]> byId = new HashMap<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String[] row = new String[7];
                for (int i = 0; i < row.length; i++) {
                    String value = rs.getString(i + 2);
                    row[i] = value == null ? "" : value;
                }
                byId.put(Long.parseLong(rs.getString(1)), row);
            }
        }
        List<NeuronCatalog.Neuron> neurons = new ArrayList<>(rootIds.length);
        int missing = 0;
        for (int i = 0; i < rootIds.length; i++) {
            String[] a = byId.get(rootIds[i]);
            if (a == null) {
                missing++;
                a = new String[]{"", "", "", "", "", "", ""};
            }
            neurons.add(new NeuronCatalog.Neuron(i, rootIds[i], a[0], a[1], a[2], a[3], a[4], a[5], a[6]));
        }
        System.out.printf("Annotated: %,d, without annotation: %,d%n", rootIds.length - missing, missing);
        return new NeuronCatalog(neurons);
    }
}
