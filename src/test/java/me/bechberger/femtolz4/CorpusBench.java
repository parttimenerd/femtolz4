package me.bechberger.femtolz4;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Allocation-free corpus matrix benchmark.
 *
 * <p>Intended for cross-architecture comparisons with fixed corpora.
 */
public final class CorpusBench {
    private static final int WARM_MS = Integer.getInteger("bench.warmMs", 250);
    private static final int MEASURE_MS = Integer.getInteger("bench.measureMs", 400);
    private static final int TRIALS = Integer.getInteger("bench.trials", 5);
    private static final String IMPL = System.getProperty("bench.impl", "dispatch");
    // Values are maxChain depths: 0=2-way-fast, 1=fast, 2+=chain
    private static final int[] LEVELS = Arrays.stream(System.getProperty("bench.levels", "0,1,2,4,8").split(","))
            .map(String::trim).mapToInt(Integer::parseInt).toArray();
    private static volatile int sink;

    // ── yawkat (lz4-java) optional comparison ─────────────────────────────────

    private static final net.jpountz.lz4.LZ4Factory YAWKAT;
    private static final net.jpountz.lz4.LZ4Compressor YAWKAT_FAST;
    private static final net.jpountz.lz4.LZ4Compressor YAWKAT_HC;
    private static final net.jpountz.lz4.LZ4FastDecompressor YAWKAT_DEC;
    static {
        net.jpountz.lz4.LZ4Factory f = null;
        try { f = net.jpountz.lz4.LZ4Factory.safeInstance(); }
        catch (Throwable t) { System.err.println("yawkat unavail: " + t.getMessage()); }
        YAWKAT      = f;
        YAWKAT_FAST = (f != null) ? f.fastCompressor()  : null;
        YAWKAT_HC   = (f != null) ? f.highCompressor()  : null;
        YAWKAT_DEC  = (f != null) ? f.fastDecompressor() : null;
    }

    // maxChain equivalent for yawkat's default highCompressor() (level 9 = 1<<8 = 256)
    private static final int YAWKAT_HC_MAXCHAIN = 256;

    @FunctionalInterface
    private interface Op { int run(); }

    private record Timing(double mbps, double ns) {}

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String[] corpora = args.length == 0 ? new String[] {
                "bench-data/corpora/json-10m.bin",
                "bench-data/corpora/random-20m.bin",
                "bench-data/corpora/mixed-20m.bin",
        } : args;

        System.out.printf("META,impl=%s,nativeAvailable=%s,java=%s,arch=%s,warmMs=%d,measureMs=%d,trials=%d%n",
                IMPL, LZ4.isNativeAvailable(), System.getProperty("java.version"),
                System.getProperty("os.arch"), WARM_MS, MEASURE_MS, TRIALS);
        System.out.println("CSV,operation,corpus,size,maxChain,impl,mbps,ns,ratio,compressed");

        for (String corpus : corpora) {
            byte[] src = load(corpus);
            if (src == null) continue;
            String name = Path.of(corpus).getFileName().toString();

            // ── femtolz4 rows ─────────────────────────────────────────────────
            for (int maxChain : LEVELS) {
                if (IMPL.equals("native") && maxChain == 0) continue;

                byte[] compressed = new byte[LZ4.maxCompressedLength(src.length)];
                int compressedLength = compress(src, compressed, maxChain);
                byte[] restored = new byte[src.length];
                int restoredLength = decompress(compressed, compressedLength, restored);
                if (restoredLength != src.length || !Arrays.equals(src, restored)) {
                    throw new AssertionError("round-trip failed for " + name + " maxChain=" + maxChain);
                }

                Timing comp = benchmark(() -> compress(src, compressed, maxChain), src.length);
                emit("compress", name, src.length, maxChain, IMPL, comp,
                        (double) src.length / compressedLength, compressedLength);

                Timing dec = benchmark(() -> decompress(compressed, compressedLength, restored), src.length);
                emit("decompress", name, src.length, maxChain, IMPL, dec,
                        (double) src.length / compressedLength, compressedLength);
            }

            // ── femto-java bypass rows (always, independent of IMPL/LEVELS) ──
            if (!IMPL.equals("java")) {
                emitFemtoJava(src, name, 1,   "femto-java-fast");
                emitFemtoJava(src, name, 256, "femto-java");
            }

            // ── yawkat comparison rows ────────────────────────────────────────
            if (YAWKAT != null) {
                emitYawkat(src, name, YAWKAT_FAST, YAWKAT_DEC, "yawkat-fast", 1);
                emitYawkat(src, name, YAWKAT_HC,   YAWKAT_DEC, "yawkat-hc",   YAWKAT_HC_MAXCHAIN);
            }
        }

        System.out.println("SINK," + sink);
    }

    private static void emitFemtoJava(byte[] src, String name, int maxChain, String label) {
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int compLen = LZ4Java.compressJava(src, 0, src.length, dst, 0, maxChain);
        byte[] compressed = Arrays.copyOf(dst, compLen);
        byte[] restored = new byte[src.length];

        Timing ct = benchmark(() -> LZ4Java.compressJava(src, 0, src.length, dst, 0, maxChain), src.length);
        emit("compress", name, src.length, maxChain, label, ct,
                (double) src.length / compLen, compLen);

        Timing dt = benchmark(() -> {
            LZ4Java.decompressJavaWithMatchLowerBound(compressed, 0, compLen, restored, 0, src.length, 0);
            return src.length;
        }, src.length);
        emit("decompress", name, src.length, maxChain, label, dt,
                (double) src.length / compLen, compLen);
    }

    private static void emitYawkat(byte[] src, String name,
                                   net.jpountz.lz4.LZ4Compressor comp,
                                   net.jpountz.lz4.LZ4FastDecompressor decomp,
                                   String label, int maxChain) {
        byte[] dst = new byte[comp.maxCompressedLength(src.length)];
        int compLen = compressYawkat(src, dst, comp);
        byte[] compressed = Arrays.copyOf(dst, compLen);
        byte[] restored = new byte[src.length];

        // Skip compress row for incompressible data: yawkat-fast exits early on
        // random input, producing a meaningless throughput number.
        if (compLen < src.length) {
            Timing ct = benchmark(() -> compressYawkat(src, dst, comp), src.length);
            emit("compress", name, src.length, maxChain, label, ct,
                    (double) src.length / compLen, compLen);
        }

        Timing dt = benchmark(() -> decompressYawkat(compressed, src.length, restored, decomp), src.length);
        emit("decompress", name, src.length, maxChain, label, dt,
                (double) src.length / compLen, compLen);
    }

    private static int compressYawkat(byte[] src, byte[] dst,
                                      net.jpountz.lz4.LZ4Compressor c) {
        return c.compress(src, 0, src.length, dst, 0, dst.length);
    }

    private static int decompressYawkat(byte[] src, int originalLen, byte[] dst,
                                        net.jpountz.lz4.LZ4FastDecompressor d) {
        d.decompress(src, 0, dst, 0, originalLen);
        return originalLen;
    }

    private static byte[] load(String path) throws Exception {
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) {
            System.err.println("SKIP missing corpus: " + path);
            return null;
        }
        return Files.readAllBytes(p);
    }

    private static int compress(byte[] src, byte[] dst, int maxChain) {
        return switch (IMPL) {
            case "dispatch" -> LZ4.compress(src, 0, src.length, dst, 0, maxChain);
            case "java" -> LZ4Java.compressJava(src, 0, src.length, dst, 0, maxChain);
            case "native" -> NativeLZ4.compress(src, 0, src.length, dst, 0, dst.length, maxChain);
            default -> throw new IllegalArgumentException("unknown bench.impl: " + IMPL);
        };
    }

    private static int decompress(byte[] src, int srcLen, byte[] dst) {
        return switch (IMPL) {
            case "dispatch" -> LZ4.decompress(src, 0, srcLen, dst, 0, dst.length);
            case "java" -> LZ4Java.decompressJavaWithMatchLowerBound(src, 0, srcLen, dst, 0, dst.length, 0);
            case "native" -> NativeLZ4.decompress(src, 0, srcLen, dst, 0, dst.length);
            default -> throw new IllegalArgumentException("unknown bench.impl: " + IMPL);
        };
    }

    private static Timing benchmark(Op op, int bytes) {
        runFor(op, bytes, WARM_MS * 1_000_000L);

        double[] mbps = new double[TRIALS];
        double[] ns = new double[TRIALS];
        for (int trial = 0; trial < TRIALS; trial++) {
            long start = System.nanoTime();
            long deadline = start + MEASURE_MS * 1_000_000L;
            long operations = 0;
            int batch = Math.max(1, Math.min(1024, 65536 / Math.max(1, bytes)));
            do {
                for (int i = 0; i < batch; i++) sink ^= op.run();
                operations += batch;
            } while (System.nanoTime() < deadline);

            long elapsed = System.nanoTime() - start;
            mbps[trial] = (double) bytes * operations / elapsed * 1_000.0;
            ns[trial] = (double) elapsed / operations;
        }

        Arrays.sort(mbps);
        Arrays.sort(ns);
        return new Timing(mbps[TRIALS / 2], ns[TRIALS / 2]);
    }

    private static void runFor(Op op, int bytes, long durationNanos) {
        long deadline = System.nanoTime() + durationNanos;
        int batch = Math.max(1, Math.min(1024, 65536 / Math.max(1, bytes)));
        do {
            for (int i = 0; i < batch; i++) sink ^= op.run();
        } while (System.nanoTime() < deadline);
    }

    private static void emit(String operation, String corpus, int size, int maxChain,
                             String implName, Timing timing, double ratio, int compressed) {
        System.out.printf("CSV,%s,%s,%d,%d,%s,%.3f,%.3f,%.8f,%d%n",
                operation, corpus, size, maxChain, implName,
                timing.mbps(), timing.ns(), ratio, compressed);
    }

    private CorpusBench() {}
}
