package me.bechberger.femtolz4;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Throughput benchmark: femtolz4-native vs femtolz4-java vs at.yawk lz4-java.
 *
 * Run:
 *   mvn test-compile -q
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "target/test-classes:target/femtolz4-0.1.0.jar:$(ls ~/.m2/repository/at/yawk/lz4/lz4-java/1.11.0/*.jar)" \
 *        me.bechberger.femtolz4.Benchmark
 */
public class Benchmark {

    static final String BENCH_DATA = System.getProperty("bench.data",
        Path.of(System.getProperty("user.dir"), "bench-data").toString());

    static final String[] FILES = {
        BENCH_DATA + "/HA_gc_details.jfr", //  3.2 MB
        BENCH_DATA + "/jvm17-gc-jfc.jfr",  //  6.7 MB
        BENCH_DATA + "/flight.jfr",        //   12 MB
        BENCH_DATA + "/failure.jfr",       //   18 MB
        BENCH_DATA + "/large_test.bin",    //  267 MB binary
        BENCH_DATA + "/large.jfr",         //  250 MB JFR
    };

    static final int WARMUP_REPS  = 4;
    static final int MEASURE_REPS = 8;

    // ── Impl interface ────────────────────────────────────────────────────────

    interface Impl {
        String name();
        /** Compress src[0..src.length) and return compressed bytes. */
        byte[] compress(byte[] src);
        /** Decompress comp back to originalLen bytes. */
        byte[] decompress(byte[] comp, int originalLen);
    }

    // ── femtolz4 dispatch (chain=1) ───────────────────────────────────────────

    static final Impl FEMTO_FAST = new Impl() {
        public String name() { return "femto-fast"; }
        public byte[] compress(byte[] s) { return LZ4.compress(s, 1); }
        public byte[] decompress(byte[] c, int n) { return LZ4.decompress(c, n); }
    };

    // ── femtolz4 HC (chain=8) ─────────────────────────────────────────────────

    static final Impl FEMTO = new Impl() {
        public String name() { return "femto-hc"; }
        public byte[] compress(byte[] s) { return LZ4.compress(s, 8); }
        public byte[] decompress(byte[] c, int n) { return LZ4.decompress(c, n); }
    };

    // ── femtolz4 pure-Java fast (direct call, chain=1) ───────────────────────

    static final Impl FEMTO_JAVA_FAST = new Impl() {
        public String name() { return "femto-java-fast"; }
        public byte[] compress(byte[] s) { return LZ4.compressJava(s); }
        public byte[] decompress(byte[] c, int n) { return LZ4.decompressJava(c, n); }
    };

    // ── femtolz4 pure-Java normal (direct call, chain=8) ─────────────────────

    static final Impl FEMTO_JAVA = new Impl() {
        public String name() { return "femto-java"; }
        public byte[] compress(byte[] s) { return LZ4.compressJava(s, 8); }
        public byte[] decompress(byte[] c, int n) { return LZ4.decompressJava(c, n); }
    };

    // ── at.yawk lz4-java (net.jpountz package) – native ──────────────────────

    static LZ4Factory yawkNative;
    static LZ4Factory yawkJava;
    static {
        try { yawkNative = LZ4Factory.nativeInstance(); }
        catch (Throwable t) { yawkNative = null; System.err.println("yawkat native unavail: " + t.getMessage()); }
        yawkJava = LZ4Factory.safeInstance();
    }

    static Impl yawkImpl(LZ4Factory f, String label) {
        if (f == null) return null;
        LZ4Compressor        c = f.fastCompressor();
        LZ4FastDecompressor  d = f.fastDecompressor();
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

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        List<Impl> impls = new ArrayList<>(Arrays.asList(
                FEMTO_FAST, FEMTO, FEMTO_JAVA_FAST, FEMTO_JAVA));
        Impl yn = yawkImpl(yawkNative, "yawkat-native");
        Impl yj = yawkImpl(yawkJava,   "yawkat-java");
        if (yn != null) impls.add(yn);
        impls.add(yj);

        System.out.printf("native available: %s%n%n", LZ4.isNativeAvailable());
        System.out.printf("%-22s  %8s  %8s  %6s%n", "impl", "comp MB/s", "dec MB/s", "ratio");
        System.out.println("-".repeat(54));

        String[] filePaths = args.length > 0 ? args : FILES;

        // JIT warmup: run all impls over a synthetic 16MB buffer (multiple rounds) so
        // C2 has compiled all hot paths before any file is measured. Small files (~1MB)
        // show 3-5x lower throughput if measured before C2 tier-2 kicks in.
        byte[] warmBuf = new byte[16 * 1024 * 1024];
        new java.util.Random(42).nextBytes(warmBuf);
        for (int round = 0; round < 5; round++) {
            for (Impl impl : impls) {
                byte[] c = impl.compress(warmBuf);
                impl.decompress(c, warmBuf.length);
            }
        }

        for (String path : filePaths) {
            Path p = Path.of(path);
            if (!Files.exists(p)) continue;
            byte[] data = Files.readAllBytes(p);
            double mb = data.length / 1_000_000.0;
            System.out.printf("%n=== %s  (%.0f MB) ===%n", p.getFileName(), mb);

            for (Impl impl : impls) {
                byte[] comp = null;
                for (int i = 0; i < WARMUP_REPS; i++) {
                    comp = impl.compress(data);
                    impl.decompress(comp, data.length);
                }
                long t0 = System.nanoTime();
                for (int i = 0; i < MEASURE_REPS; i++) comp = impl.compress(data);
                double cMBs = mb * MEASURE_REPS / ((System.nanoTime()-t0)/1e9);
                final byte[] cf = comp;
                t0 = System.nanoTime();
                for (int i = 0; i < MEASURE_REPS; i++) impl.decompress(cf, data.length);
                double dMBs = mb * MEASURE_REPS / ((System.nanoTime()-t0)/1e9);
                double ratio = (double) data.length / comp.length;
                System.out.printf("  %-22s  %8.0f  %8.0f  %5.2fx%n",
                    impl.name(), cMBs, dMBs, ratio);
            }
        }
    }
}
