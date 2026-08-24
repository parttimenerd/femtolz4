package me.bechberger.femtolz4;

import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Throughput benchmark using the frame stream API — the normal user-facing path.
 * Each impl compresses via LZ4FrameOutputStream (splits into 4MB blocks) and
 * decompresses via LZ4FrameInputStream, matching real-world usage.
 *
 * Prints a WARNING if any femtolz4 impl's compression ratio is worse than
 * yawkat-java by more than 5%.
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
        BENCH_DATA + "/HA_gc_details.jfr",
        BENCH_DATA + "/jvm17-gc-jfc.jfr",
        BENCH_DATA + "/flight.jfr",
        BENCH_DATA + "/failure.jfr",
        BENCH_DATA + "/large_test.bin",
        BENCH_DATA + "/large.jfr",
    };

    static final int WARMUP_REPS  = 4;
    static final int MEASURE_REPS = 8;

    interface Impl {
        String name();
        /** Compress src via frame stream, return compressed bytes. */
        byte[] compress(byte[] src) throws IOException;
        /** Decompress frame-compressed bytes back to originalLen bytes. */
        byte[] decompress(byte[] comp, int originalLen) throws IOException;
    }

    // ── femtolz4 frame impls ──────────────────────────────────────────────────

    static Impl femtoImpl(String name, LZ4.Compressor compressor) {
        return new Impl() {
            public String name() { return name; }
            public byte[] compress(byte[] src) throws IOException {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(src.length / 2 + 256);
                try (LZ4FrameOutputStream out = new LZ4FrameOutputStream(baos, compressor)) {
                    out.write(src);
                }
                return baos.toByteArray();
            }
            public byte[] decompress(byte[] comp, int originalLen) throws IOException {
                byte[] dst = new byte[originalLen];
                try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp))) {
                    int off = 0, rem = originalLen;
                    while (rem > 0) {
                        int n = in.read(dst, off, rem);
                        if (n < 0) break;
                        off += n; rem -= n;
                    }
                }
                return dst;
            }
        };
    }

    static final Impl FEMTO_FAST     = femtoImpl("femto-fast",     LZ4.compress());
    static final Impl FEMTO_HC       = femtoImpl("femto-hc",       LZ4.compressHigh(8));
    static final Impl FEMTO_JAVA_FAST= femtoImpl("femto-java-fast",LZ4.compressJava());
    static final Impl FEMTO_JAVA     = femtoImpl("femto-java",     LZ4.compressHighJava(8));
    static final Impl FEMTO_JAVA_HC  = femtoImpl("femto-java-hc",  LZ4.compressHighJava());

    // ── yawkat frame impls ────────────────────────────────────────────────────

    static LZ4Factory yawkNative;
    static LZ4Factory yawkJava;
    static XXHashFactory xxNative;
    static XXHashFactory xxJava;
    static {
        try { yawkNative = LZ4Factory.fastestInstance(); xxNative = XXHashFactory.fastestInstance(); }
        catch (Throwable t) { yawkNative = null; xxNative = null; System.err.println("yawkat native unavail: " + t.getMessage()); }
        yawkJava = LZ4Factory.fastestJavaInstance();
        xxJava   = XXHashFactory.fastestJavaInstance();
    }

    static Impl yawkFrameImpl(LZ4Factory lz4f, XXHashFactory xxf, String label) {
        if (lz4f == null) return null;
        return new Impl() {
            public String name() { return label; }
            public byte[] compress(byte[] src) throws IOException {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(src.length / 2 + 256);
                try (net.jpountz.lz4.LZ4FrameOutputStream out =
                         new net.jpountz.lz4.LZ4FrameOutputStream(baos,
                             net.jpountz.lz4.LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB,
                             -1L, lz4f.fastCompressor(), xxf.hash32(),
                             net.jpountz.lz4.LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE)) {
                    out.write(src);
                }
                return baos.toByteArray();
            }
            public byte[] decompress(byte[] comp, int originalLen) throws IOException {
                byte[] dst = new byte[originalLen];
                try (net.jpountz.lz4.LZ4FrameInputStream in =
                         new net.jpountz.lz4.LZ4FrameInputStream(
                             new ByteArrayInputStream(comp),
                             lz4f.safeDecompressor(), xxf.hash32())) {
                    int off = 0, rem = originalLen;
                    while (rem > 0) {
                        int n = in.read(dst, off, rem);
                        if (n < 0) break;
                        off += n; rem -= n;
                    }
                }
                return dst;
            }
        };
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        boolean javaOnly = Boolean.getBoolean("bench.java.only");
        List<Impl> impls = new ArrayList<>();
        if (!javaOnly) impls.addAll(Arrays.asList(FEMTO_FAST, FEMTO_HC));
        impls.addAll(Arrays.asList(FEMTO_JAVA_FAST, FEMTO_JAVA, FEMTO_JAVA_HC));
        Impl yawkNativeImpl = javaOnly ? null : yawkFrameImpl(yawkNative, xxNative, "yawkat-native");
        if (yawkNativeImpl != null) impls.add(yawkNativeImpl);
        Impl yawkJavaImpl = yawkFrameImpl(yawkJava, xxJava, "yawkat-java");
        if (yawkJavaImpl != null) impls.add(yawkJavaImpl);

        System.out.printf("native available: %s%n%n", LZ4.isNativeAvailable());
        System.out.printf("%-22s  %8s  %8s  %6s%n", "impl", "comp MB/s", "dec MB/s", "ratio");
        System.out.println("-".repeat(54));

        String[] filePaths = args.length > 0 ? args : FILES;

        // Warmup: 5 rounds over a synthetic 16MB buffer so C2 compiles all hot paths.
        byte[] warmBuf = new byte[16 * 1024 * 1024];
        new java.util.Random(42).nextBytes(warmBuf);
        for (int round = 0; round < 5; round++)
            for (Impl impl : impls) impl.decompress(impl.compress(warmBuf), warmBuf.length);

        for (String path : filePaths) {
            Path p = Path.of(path);
            if (!Files.exists(p)) continue;
            byte[] data = Files.readAllBytes(p);
            double mb = data.length / 1_000_000.0;
            System.out.printf("%n=== %s  (%.0f MB) ===%n", p.getFileName(), mb);

            Map<String, Double> ratios = new LinkedHashMap<>();
            for (Impl impl : impls) {
                byte[] comp = null;
                for (int i = 0; i < WARMUP_REPS; i++) {
                    comp = impl.compress(data);
                    impl.decompress(comp, data.length);
                }
                long t0 = System.nanoTime();
                for (int i = 0; i < MEASURE_REPS; i++) comp = impl.compress(data);
                double cMBs = mb * MEASURE_REPS / ((System.nanoTime() - t0) / 1e9);
                final byte[] cf = comp;
                t0 = System.nanoTime();
                for (int i = 0; i < MEASURE_REPS; i++) impl.decompress(cf, data.length);
                double dMBs = mb * MEASURE_REPS / ((System.nanoTime() - t0) / 1e9);
                double ratio = (double) data.length / comp.length;
                ratios.put(impl.name(), ratio);
                System.out.printf("  %-22s  %8.0f  %8.0f  %5.2fx%n",
                    impl.name(), cMBs, dMBs, ratio);
            }

            // Warn if any femtolz4 fast impl compresses >5% worse than yawkat-java.
            double yawkRatio = ratios.getOrDefault("yawkat-java", 0.0);
            if (yawkRatio > 0) {
                for (Map.Entry<String, Double> e : ratios.entrySet()) {
                    String name = e.getKey();
                    if (!name.startsWith("femto")) continue;
                    double r = e.getValue();
                    if (r < yawkRatio * 0.95) {
                        System.out.printf("  WARNING: %s ratio %.2fx is >5%% worse than yawkat-java %.2fx%n",
                            name, r, yawkRatio);
                    }
                }
            }
        }
    }
}