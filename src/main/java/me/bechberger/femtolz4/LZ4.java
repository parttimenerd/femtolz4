package me.bechberger.femtolz4;

import java.util.Arrays;

/**
 * Pure-Java LZ4 block compressor and decompressor — dispatch layer only.
 *
 * <p>All implementation lives in {@link LZ4Java}.  This class owns the public
 * API, the native-vs-Java dispatch decision, and the lz4-java-compatible
 * factory interface.  It contains <em>no</em> compression or decompression logic.
 *
 * @see <a href="https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md">LZ4 block format spec</a>
 */
public final class LZ4 {

    static final int WINDOW_SIZE = 1 << 16;

    /* On AArch64 (Apple M-series, AWS Graviton, etc.) the Java JIT generates tighter
       code than clang -O3 for the fast-path inner loop: measured ~40% faster on M4.
       Use the Java path for compress on ARM to avoid the JNI overhead. */
    static final boolean IS_AARCH64 =
        System.getProperty("os.arch", "").toLowerCase().contains("aarch64");

    /** Sentinel for {@link LZ4Java#compressJavaImpl} repeatedSamplesHint: unknown. */
    static final int SAMPLE_COUNT_UNKNOWN = -1;

    /** Minimum src length before paying the cost of {@link #countRepeatedSamples}. */
    static final int X86_NATIVE_CHAIN_SAMPLE_MIN = 4096;

    /**
     * On AArch64, when an early offset-1 match is at least this long the native
     * library has a vectorised fill path that outperforms the JIT for the RLE case.
     */
    static final int ARM_NATIVE_OFFSET1_MATCH_MIN = 256;

    /** Minimum HC compression level. */
    public static final int HC_MIN_LEVEL = 1;
    /** Maximum HC compression level (equivalent to maxChain=256). */
    public static final int HC_MAX_LEVEL = 256;


    /**
     * Compressor handle returned by the factory methods, compatible with the
     * lz4-java {@code LZ4Compressor} API.
     */
    @FunctionalInterface
    public interface Compressor {
        int compress(byte[] src, int srcOff, int srcLen,
                     byte[] dst, int dstOff, int maxDestLen);

        default int maxCompressedLength(int srcLen) {
            return LZ4.maxCompressedLength(srcLen);
        }
    }

    /**
     * Decompressor handle returned by the factory methods, compatible with the
     * lz4-java {@code LZ4FastDecompressor} API.
     */
    @FunctionalInterface
    public interface Decompressor {
        int decompress(byte[] src, int srcOff,
                       byte[] dst, int dstOff, int originalLen);
    }


    /** Returns a fast (chain=1) compressor. Equivalent to lz4-java's {@code fastCompressor()}. */
    public static Compressor compress() {
        return (src, srcOff, srcLen, dst, dstOff, maxDestLen) ->
            compress(src, srcOff, srcLen, dst, dstOff, 1);
    }

    /** Returns a high-compression (chain=256) compressor. Equivalent to lz4-java's {@code highCompressor()}. */
    public static Compressor compressHigh() {
        return (src, srcOff, srcLen, dst, dstOff, maxDestLen) ->
            compress(src, srcOff, srcLen, dst, dstOff, HC_MAX_LEVEL);
    }

    /**
     * Returns a high-compression compressor at the given level.
     *
     * @param level chain depth from {@value #HC_MIN_LEVEL} to {@value #HC_MAX_LEVEL}
     */
    public static Compressor compressHigh(int level) {
        int maxChain = Math.max(HC_MIN_LEVEL, Math.min(HC_MAX_LEVEL, level));
        return (src, srcOff, srcLen, dst, dstOff, maxDestLen) ->
            compress(src, srcOff, srcLen, dst, dstOff, maxChain);
    }

    /** Returns a fast decompressor. Equivalent to lz4-java's {@code fastDecompressor()}. */
    public static Decompressor decompress() {
        return (src, srcOff, dst, dstOff, originalLen) -> {
            decompress(src, srcOff, src.length - srcOff, dst, dstOff, originalLen);
            return originalLen;
        };
    }

    /** Worst-case output size for {@code srcLen} uncompressed bytes. */
    public static int maxCompressedLength(int srcLen) {
        return srcLen + 16 + (srcLen / 255) + 1;
    }


    /** True when the native library was loaded successfully. */
    public static boolean isNativeAvailable() { return NativeLZ4.AVAILABLE; }

    /**
     * Count how many of 8 evenly-spaced 4-byte windows have the same value as
     * the window 4 positions earlier (offset-4 repeat pattern).
     *
     * @return number of repeated-pattern probes in [0, 8]
     */
    static int countRepeatedSamples(byte[] src, int srcOff, int srcLen) {
        return LZ4Java.countRepeatedSamples(src, srcOff, srcLen);
    }

    /**
     * Compress {@code srcLen} bytes from {@code src[srcOff..]} into {@code dst[dstOff..]}.
     *
     * @param maxChain hash-chain depth: 0 = 2-way fast, 1 = fastest, 2+ = hash-chain with lazy
     * @return number of bytes written into dst
     */
    public static int compress(byte[] src, int srcOff, int srcLen,
                               byte[] dst, int dstOff, int maxChain) {
        if (srcLen == 0) return 0;
        if (NativeLZ4.AVAILABLE && !IS_AARCH64 && maxChain > 0) {
            int repeatedSamples = (srcLen >= X86_NATIVE_CHAIN_SAMPLE_MIN)
                ? LZ4Java.countRepeatedSamples(src, srcOff, srcLen) : 0;
            boolean useJava = (maxChain == 1 && repeatedSamples >= 2 && repeatedSamples < 6)
                || (maxChain >= 2 && LZ4Java.hasLongOffsetOneInEarlySequences(src, srcOff, srcLen));
            if (!useJava) {
                int n = NativeLZ4.compress(src, srcOff, srcLen, dst, dstOff, dst.length - dstOff, maxChain);
                if (n > 0) return n;
            }
        }
        return LZ4Java.compressJavaImpl(src, srcOff, srcLen, dst, dstOff, maxChain);
    }

    /** Convenience: fast compress, returns a trimmed copy. */
    public static byte[] compress(byte[] src) {
        return compress(src, 1);
    }

    /** Convenience: high-ratio compress, returns a trimmed copy. */
    public static byte[] compressHigh(byte[] src) {
        return compress(src, HC_MAX_LEVEL);
    }

    /** Convenience: allocates (or reuses) an output buffer and returns a trimmed copy. */
    public static byte[] compress(byte[] src, int maxChain) {
        int maxLen = maxCompressedLength(src.length);
        byte[] dst = LZ4Java.TL_DST.get();
        if (dst.length < maxLen) {
            dst = new byte[maxLen];
            LZ4Java.TL_DST.set(dst);
        }
        int len = compress(src, 0, src.length, dst, 0, maxChain);
        return Arrays.copyOf(dst, len);
    }

    /** Pure-Java compress, bypassing the native path. For benchmarking. */
    static byte[] compressJava(byte[] src, int maxChain) {
        int maxLen = maxCompressedLength(src.length);
        byte[] dst = LZ4Java.TL_DST.get();
        if (dst.length < maxLen) {
            dst = new byte[maxLen];
            LZ4Java.TL_DST.set(dst);
        }
        int len = LZ4Java.compressJavaImpl(src, 0, src.length, dst, 0, maxChain);
        return Arrays.copyOf(dst, len);
    }

    /** Pure-Java compress at chain=1, bypassing the native path. */
    static byte[] compressJava(byte[] src) {
        return compressJava(src, 1);
    }

    /** Pure-Java compress with offsets — no allocation. */
    static int compressJava(byte[] src, int srcOff, int srcLen,
                            byte[] dst, int dstOff, int maxChain) {
        return LZ4Java.compressJavaImpl(src, srcOff, srcLen, dst, dstOff, maxChain);
    }


    /**
     * Decompress an LZ4 block from {@code src[srcOff..srcOff+srcLen)} into
     * {@code dst[dstOff..dstOff+dstLen)}.
     *
     * @return number of bytes written into dst
     * @throws LZ4Exception on malformed input
     */
    public static int decompress(byte[] src, int srcOff, int srcLen,
                                 byte[] dst, int dstOff, int dstLen) {
        if (NativeLZ4.AVAILABLE && !IS_AARCH64) {
            int n = NativeLZ4.decompress(src, srcOff, srcLen, dst, dstOff, dstLen);
            if (n >= 0) return n;
        }
        return LZ4Java.decompressJavaImpl(src, srcOff, srcLen, dst, dstOff, dstLen, dstOff);
    }

    /** Convenience: decompresses into a freshly allocated buffer of {@code decompressedSize}. */
    public static byte[] decompress(byte[] src, int decompressedSize) {
        byte[] dst = new byte[decompressedSize];
        int n = decompress(src, 0, src.length, dst, 0, decompressedSize);
        return n == decompressedSize ? dst : Arrays.copyOf(dst, n);
    }

    /** Pure-Java decompress, bypassing the native path. */
    static byte[] decompressJava(byte[] src, int decompressedSize) {
        return LZ4Java.decompressJava(src, decompressedSize);
    }

    static int decompressJavaWithMatchLowerBound(byte[] src, int srcOff, int srcLen,
                                                 byte[] dst, int dstOff, int dstLen,
                                                 int matchLowerBound) {
        return LZ4Java.decompressJavaImpl(src, srcOff, srcLen, dst, dstOff, dstLen, matchLowerBound);
    }

    private LZ4() {}
}
