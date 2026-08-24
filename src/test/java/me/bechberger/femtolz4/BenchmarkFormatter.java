package me.bechberger.femtolz4;

import java.util.*;

/**
 * Formats Benchmark and CorpusBench output as GitHub-flavoured Markdown tables.
 *
 * <p>Usage (reads from stdin):
 * <pre>
 *   java BenchmarkFormatter --jfr   < jfr_output.txt
 *   java BenchmarkFormatter --corpus < corpus_output.txt
 * </pre>
 *
 * Bolding: the highest value in each numeric column is wrapped in {@code **...**}.
 * Footnotes are appended for known anomalies (e.g. yawkat-fast on incompressible data).
 */
public final class BenchmarkFormatter {

    // ── JFR / Benchmark mode ──────────────────────────────────────────────────

    record JfrRow(String impl, double comp, double decomp, String ratio) {}

    static String formatJfr(String raw) {
        StringBuilder sb = new StringBuilder();
        List<JfrRow> rows = new ArrayList<>();
        String title = null;
        Set<String> footnoteKeys = new LinkedHashSet<>();

        for (String line : raw.lines().toList()) {
            if (line.startsWith("=== ")) {
                if (title != null && !rows.isEmpty()) {
                    sb.append(renderJfrSection(title, rows, footnoteKeys));
                    rows.clear();
                }
                title = line.replaceAll("^=== ", "").replaceAll(" ===$", "");
                continue;
            }
            String trimmed = line.strip();
            String[] f = trimmed.split("\\s+");
            if (f.length == 4 && !f[0].isEmpty()) {
                try {
                    double c = Double.parseDouble(f[1].replace(",", "."));
                    double d = Double.parseDouble(f[2].replace(",", "."));
                    rows.add(new JfrRow(f[0], c, d, f[3]));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (title != null && !rows.isEmpty()) {
            sb.append(renderJfrSection(title, rows, footnoteKeys));
        }
        if (!footnoteKeys.isEmpty()) {
            sb.append("\n");
            for (String fn : footnoteKeys) sb.append(fn).append("\n");
        }
        return sb.toString();
    }

    static String renderJfrSection(String title, List<JfrRow> rows, Set<String> footnoteKeys) {
        double maxC = rows.stream().mapToDouble(r -> r.comp()).max().orElse(0);
        double maxD = rows.stream().mapToDouble(r -> r.decomp()).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        sb.append("\n### ").append(title).append("\n\n");
        sb.append("| implementation | compress MB/s | decompress MB/s | ratio |\n");
        sb.append("|----------------|:-------------:|:---------------:|:-----:|\n");
        for (JfrRow r : rows) {
            String cs = fmtNum(r.comp(), r.comp() == maxC);
            String ds = fmtNum(r.decomp(), r.decomp() == maxD);
            sb.append(String.format("| %-22s | %13s | %15s | %5s |\n", r.impl(), cs, ds, r.ratio()));
        }
        return sb.toString();
    }

    // ── Corpus / CorpusBench mode ─────────────────────────────────────────────

    record CorpusKey(String corpus, String impl, int chain) {}
    record CorpusRow(CorpusKey key, double comp, double decomp, double ratio, long size) {}

    static String formatCorpus(String raw) {
        Map<CorpusKey, CorpusRow> data = new LinkedHashMap<>();
        Map<String, Long> sizes = new LinkedHashMap<>();

        for (String line : raw.lines().toList()) {
            if (!line.startsWith("CSV,")) continue;
            String[] f = line.split(",");
            if (f.length < 9 || f[1].equals("operation")) continue;
            String op = f[1], corpus = f[2], impl = f[6].isEmpty() ? f[5] : f[5]; // impl is f[5]
            // CSV format: op,corpus,size,maxChain,impl,mbps,ns,ratio,compressed
            impl = f[5]; // actual impl name
            try {
                long size = Long.parseLong(f[3]);
                int chain = Integer.parseInt(f[4]);
                double mbps = Double.parseDouble(f[6]);
                double ratio = Double.parseDouble(f[8]);
                sizes.put(corpus, size);
                CorpusKey key = new CorpusKey(corpus, impl, chain);
                CorpusRow existing = data.get(key);
                if (op.equals("compress")) {
                    data.put(key, new CorpusRow(key,
                        mbps,
                        existing != null ? existing.decomp() : -1,
                        ratio, size));
                } else if (op.equals("decompress")) {
                    data.put(key, new CorpusRow(key,
                        existing != null ? existing.comp() : -1,
                        mbps,
                        existing != null ? existing.ratio() : ratio,
                        size));
                }
            } catch (NumberFormatException ignored) {}
        }

        StringBuilder sb = new StringBuilder();
        String curCorpus = null;
        List<CorpusRow> sectionRows = new ArrayList<>();

        for (CorpusRow row : data.values()) {
            String corpus = row.key().corpus();
            if (!corpus.equals(curCorpus)) {
                if (curCorpus != null) {
                    sb.append(renderCorpusSection(curCorpus, sizes.get(curCorpus), sectionRows));
                    sectionRows.clear();
                }
                curCorpus = corpus;
            }
            sectionRows.add(row);
        }
        if (curCorpus != null) {
            sb.append(renderCorpusSection(curCorpus, sizes.get(curCorpus), sectionRows));
        }

        return sb.toString();
    }

    static String renderCorpusSection(String corpus, long size, List<CorpusRow> rows) {
        double maxC = rows.stream().mapToDouble(r -> r.comp()).filter(v -> v >= 0).max().orElse(0);
        double maxD = rows.stream().mapToDouble(r -> r.decomp()).filter(v -> v >= 0).max().orElse(0);

        StringBuilder sb = new StringBuilder();
        String mb = String.format("%.0f MB", size / 1_000_000.0);
        sb.append("\n### ").append(corpus).append(" (").append(mb).append(")\n\n");
        sb.append("| implementation | compress MB/s | decompress MB/s | ratio |\n");
        sb.append("|----------------|:-------------:|:---------------:|:-----:|\n");

        for (CorpusRow r : rows) {
            String label = corpusLabel(r.key());
            String cs = r.comp() >= 0 ? fmtNum(r.comp(), r.comp() == maxC) : "-";
            String ds = r.decomp() >= 0 ? fmtNum(r.decomp(), r.decomp() == maxD) : "-";
            String rat = String.format("%.2fx", r.ratio());
            sb.append(String.format("| %-22s | %13s | %15s | %5s |\n", label, cs, ds, rat));
        }
        return sb.toString();
    }

    static String corpusLabel(CorpusKey key) {
        return switch (key.impl()) {
            case "dispatch" -> key.chain() == 1 ? "femto-fast" : "femto-hc";
            case "femto-java-fast", "femto-java", "yawkat-fast", "yawkat-hc" -> key.impl();
            default -> key.impl() + "-" + key.chain();
        };
    }

    static String fmtNum(double v, boolean bold) {
        String s = String.format("%.0f", v);
        return bold ? "**" + s + "**" : s;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String raw = new String(System.in.readAllBytes());
        boolean jfr = args.length > 0 && args[0].equals("--jfr");
        System.out.print(jfr ? formatJfr(raw) : formatCorpus(raw));
    }

    private BenchmarkFormatter() {}
}
