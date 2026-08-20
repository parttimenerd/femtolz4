package me.bechberger.femtolz4;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.nio.file.*;
import java.util.*;

/**
 * Deep benchmark: cold JVM, warm JVM, latency percentiles, chain depths.
 *
 * Run:
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "target/test-classes:target/femtolz4-0.1.0.jar:$(ls ~/.m2/repository/at/yawi/lz4/lz4-java/1.11.0/*.jar)" \
 *        me.bechberger.femtolz4.DeepBenchmark
 */
public class DeepBenchmark {

    // ── Files ────────────────────────────────────────────────────────────────

    static final String[] FILES = {
        System.getProperty("user.home") + "/Downloads/cpu_profile.jfr",    // ~1 MB
        System.getProperty("user.home") + "/Downloads/HA_gc_details.jfr",  // ~3 MB
        System.getProperty("user.home") + "/Downloads/jvm17-gc-jfc.jfr",   // ~7 MB
        System.getProperty("user.home") + "/Downloads/flight.jfr",         // ~13 MB
        System.getProperty("user.home") + "/Downloads/failure.jfr",        // ~19 MB
        "/tmp/large_test.bin",                                               // ~267 MB
    };

    // ── Impl interface ────────────────────────────────────────────────────────

    interface Impl {
        String name();
        byte[] compress(byte[] src);
        byte[] decompress(byte[] comp, int originalLen);
    }

    // ── Implementations ───────────────────────────────────────────────────────

    static Impl femtoNative(int chain) {
        String label = "femto-native-c" + chain;
        return new Impl() {
            public String name() { return label; }
            public byte[] compress(byte[] s)        { return LZ4.compress(s, chain); }
            public byte[] decompress(byte[] c, int n) { return LZ4.decompress(c, n); }
        };
    }

    static Impl femtoJava(int chain) {
        String label = "femto-java-c" + chain;
        return new Impl() {
            public String name() { return label; }
            public byte[] compress(byte[] s)        { return LZ4.compressJava(s, chain); }
            public byte[] decompress(byte[] c, int n) { return LZ4.decompressJava(c, n); }
        };
    }

    static Impl yawkImpl(LZ4Factory f, String label) {
        if (f == null) return null;
        LZ4Compressor       c = f.fastCompressor();
        LZ4FastDecompressor d = f.fastDecompressor();
        return new Impl() {
            public String name() { return label; }
            public byte[] compress(byte[] src) {
                byte[] dst = new byte[c.maxCompressedLength(src.length)];
                int n = c.compress(src, 0, src.length, dst, 0, dst.length);
                return Arrays.copyOf(dst, n);
            }
            public byte[] decompress(byte[] comp, int len) {
                byte[] dst = new byte[len];
                d.decompress(comp, 0, dst, 0, len);
                return dst;
            }
        };
    }

    // ── Stats helper ─────────────────────────────────────────────────────────

    static double[] stats(long[] ns) {
        long[] s = Arrays.copyOf(ns, ns.length);
        Arrays.sort(s);
        double mean = 0;
        for (long v : s) mean += v;
        mean /= s.length;
        double p50  = s[s.length / 2];
        double p95  = s[(int)(s.length * 0.95)];
        double p99  = s[(int)(s.length * 0.99)];
        double best = s[0];
        return new double[]{mean, p50, p95, p99, best};
    }

    // ── Section 1: Warm throughput ─────────────────────────────────────────

    static void warmThroughput(List<Impl> impls, byte[] data, String name) {
        double mb = data.length / 1_000_000.0;
        System.out.printf("%n=== Warm throughput — %s (%.1f MB) ===%n", name, mb);
        System.out.printf("  %-22s  %8s  %8s  %6s%n", "impl", "comp MB/s", "dec MB/s", "ratio");
        System.out.println("  " + "-".repeat(52));

        int warmup  = 6;
        int measure = 12;

        for (Impl impl : impls) {
            byte[] comp = null;
            for (int i = 0; i < warmup; i++) {
                comp = impl.compress(data);
                impl.decompress(comp, data.length);
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < measure; i++) comp = impl.compress(data);
            double cMBs = mb * measure / ((System.nanoTime() - t0) / 1e9);

            final byte[] cf = comp;
            t0 = System.nanoTime();
            for (int i = 0; i < measure; i++) impl.decompress(cf, data.length);
            double dMBs = mb * measure / ((System.nanoTime() - t0) / 1e9);

            double ratio = (double) data.length / comp.length;
            System.out.printf("  %-22s  %8.0f  %8.0f  %5.2fx%n",
                impl.name(), cMBs, dMBs, ratio);
        }
    }

    // ── Section 2: Latency distribution ───────────────────────────────────

    static void latencyDistribution(List<Impl> impls, byte[] data, String name) {
        double mb = data.length / 1_000_000.0;
        System.out.printf("%n=== Latency (compress) — %s (%.1f MB) ===%n", name, mb);
        System.out.printf("  %-22s  %8s  %8s  %8s  %8s  %8s%n",
            "impl", "mean µs", "p50 µs", "p95 µs", "p99 µs", "best µs");
        System.out.println("  " + "-".repeat(67));

        int warmup  = 5;
        int measure = 200;

        for (Impl impl : impls) {
            for (int i = 0; i < warmup; i++) impl.compress(data);
            long[] ns = new long[measure];
            for (int i = 0; i < measure; i++) {
                long t = System.nanoTime();
                impl.compress(data);
                ns[i] = System.nanoTime() - t;
            }
            double[] s = stats(ns);
            System.out.printf("  %-22s  %8.0f  %8.0f  %8.0f  %8.0f  %8.0f%n",
                impl.name(),
                s[0]/1000, s[1]/1000, s[2]/1000, s[3]/1000, s[4]/1000);
        }
    }

    // ── Section 3: Cold JVM (first call, no warmup) ───────────────────────

    static void coldCall(List<Impl> impls, byte[] data, String name) {
        double mb = data.length / 1_000_000.0;
        System.out.printf("%n=== Cold first call — %s (%.1f MB) ===%n", name, mb);
        System.out.printf("  %-22s  %8s  %8s%n", "impl", "comp µs", "dec µs");
        System.out.println("  " + "-".repeat(44));

        for (Impl impl : impls) {
            long t0 = System.nanoTime();
            byte[] comp = impl.compress(data);
            long compUs = (System.nanoTime() - t0) / 1000;

            t0 = System.nanoTime();
            impl.decompress(comp, data.length);
            long decUs = (System.nanoTime() - t0) / 1000;

            System.out.printf("  %-22s  %8d  %8d%n", impl.name(), compUs, decUs);
        }
    }

    // ── Section 4: Chain depth sweep (compress only) ──────────────────────

    static void chainSweep(byte[] data, String name) {
        double mb = data.length / 1_000_000.0;
        System.out.printf("%n=== Chain depth sweep — %s (%.1f MB) ===%n", name, mb);
        System.out.printf("  %-10s  %8s  %8s  %8s  %6s%n",
            "chain", "native MB/s", "java MB/s", "java/nat", "ratio");
        System.out.println("  " + "-".repeat(55));

        int warmup = 4, measure = 8;
        for (int chain : new int[]{1, 2, 4, 8, 16, 32, 64}) {
            Impl nat  = femtoNative(chain);
            Impl java = femtoJava(chain);

            byte[] comp = null;
            for (int i = 0; i < warmup; i++) { comp = nat.compress(data);  nat.decompress(comp, data.length); }
            for (int i = 0; i < warmup; i++) { comp = java.compress(data); java.decompress(comp, data.length); }

            long t0 = System.nanoTime();
            for (int i = 0; i < measure; i++) comp = nat.compress(data);
            double natMBs = mb * measure / ((System.nanoTime() - t0) / 1e9);

            t0 = System.nanoTime();
            for (int i = 0; i < measure; i++) comp = java.compress(data);
            double javaMBs = mb * measure / ((System.nanoTime() - t0) / 1e9);

            double ratio = (double) data.length / comp.length;
            System.out.printf("  %-10d  %8.0f  %8.0f  %8.2fx  %5.2fx%n",
                chain, natMBs, javaMBs, javaMBs / natMBs, ratio);
        }
    }

    // ── Main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        boolean nativeAvail = LZ4.isNativeAvailable();
        System.out.println("native available: " + nativeAvail);

        LZ4Factory yawkNative = null, yawkJava = LZ4Factory.safeInstance();
        try { yawkNative = LZ4Factory.nativeInstance(); }
        catch (Throwable t) { System.err.println("yawkat native unavail: " + t.getMessage()); }

        // Impls for throughput + latency sections
        List<Impl> impls = new ArrayList<>();
        if (nativeAvail) {
            impls.add(femtoNative(1));
            impls.add(femtoNative(8));
        }
        impls.add(femtoJava(1));
        impls.add(femtoJava(8));
        if (yawkNative != null) impls.add(yawkImpl(yawkNative, "yawkat-native"));
        impls.add(yawkImpl(yawkJava, "yawkat-java"));

        // Load files
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String path : FILES) {
            Path p = Path.of(path);
            if (Files.exists(p))
                files.put(p.getFileName().toString(), Files.readAllBytes(p));
        }

        if (files.isEmpty()) { System.err.println("No benchmark files found"); return; }

        // ── 1. Warm throughput ─────────────────────────────────────────────
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SECTION 1: Warm throughput (fully JIT-compiled)");
        System.out.println("=".repeat(70));
        for (var e : files.entrySet())
            warmThroughput(impls, e.getValue(), e.getKey());

        // ── 2. Latency distribution (medium file) ─────────────────────────
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SECTION 2: Latency distribution (compress, 200 samples)");
        System.out.println("=".repeat(70));
        // Pick medium (~7 MB) and small (~1 MB) files for latency
        for (var e : files.entrySet()) {
            long sz = e.getValue().length;
            if (sz > 500_000 && sz < 5_000_000)
                latencyDistribution(impls, e.getValue(), e.getKey());
        }
        for (var e : files.entrySet()) {
            long sz = e.getValue().length;
            if (sz >= 5_000_000 && sz < 15_000_000)
                latencyDistribution(impls, e.getValue(), e.getKey());
        }

        // ── 3. Cold first call ────────────────────────────────────────────
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SECTION 3: Cold first call (no JIT warmup — new classloader)");
        System.out.println("=".repeat(70));
        // Use a freshly constructed impl so each sees a cold JIT
        List<Impl> coldImpls = new ArrayList<>();
        if (nativeAvail) { coldImpls.add(femtoNative(1)); coldImpls.add(femtoNative(8)); }
        coldImpls.add(femtoJava(1));
        coldImpls.add(femtoJava(8));
        if (yawkNative != null) coldImpls.add(yawkImpl(yawkNative, "yawkat-native"));
        coldImpls.add(yawkImpl(yawkJava, "yawkat-java"));
        for (var e : files.entrySet())
            coldCall(coldImpls, e.getValue(), e.getKey());

        // ── 4. Chain depth sweep ──────────────────────────────────────────
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SECTION 4: Chain depth sweep (native vs java speed ratio)");
        System.out.println("=".repeat(70));
        // Use the largest available file for chain sweep
        byte[] largest = files.values().stream()
            .max(Comparator.comparingInt(b -> b.length)).orElseThrow();
        String largestName = files.entrySet().stream()
            .filter(e -> e.getValue() == largest).findFirst().get().getKey();
        chainSweep(largest, largestName);

        // Also a medium file
        files.entrySet().stream()
            .filter(e -> e.getValue().length > 5_000_000 && e.getValue().length < 15_000_000)
            .findFirst().ifPresent(e -> chainSweep(e.getValue(), e.getKey()));
    }
}
