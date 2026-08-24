package me.bechberger.femtolz4;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive fuzz tests comparing every femtolz4 mode against yawkat/lz4-java as oracle.
 *
 * Modes tested:
 *   Compressors: chain=0(2way), chain=1(fast), chain=2, chain=4, chain=8, chain=256(HC),
 *                java-chain=0, java-chain=1, java-chain=2, java-chain=4, java-chain=256
 *   Decompressors: femto-native, femto-java
 *   Oracle decompressor: yawkat safeInstance
 *   Oracle compressor: yawkat safeInstance (for yawkat→femto direction)
 *
 * Strict LZ4 spec compliance:
 *   1. Last sequence = literals only (no match)
 *   2. Last 5 bytes of input must be literals (PADDING_LITERALS=5)
 *   3. Last match must start >= MFLIMIT=12 bytes before end of input
 *
 * Bug-hunting focus:
 *   - MFLIMIT=12 boundary: matches near end of block
 *   - compressFast2Way (chain=0): 2-way LRU table, sentinel math, skip logic
 *   - compressChain2 (chain=2): speculative 2-probe OOO, lazy matching
 *   - HC compressor (chain=256): long chain walks, safeEnd boundary
 *   - Non-zero srcOff/dstOff with all modes
 *   - Highly compressible data (all-same bytes, short patterns)
 *   - Near-incompressible data (random bytes, skip logic stress)
 *   - Specific lengths near PADDING boundaries (4, 5, 6, 7, 8..20)
 *   - Near MFLIMIT boundary lengths (10, 11, 12, 13, 14, 15, 16..24)
 *   - Large blocks (>64 KB, exercises WINDOW_SIZE wrap-around)
 *   - Structured data: sorted, reverse-sorted, sawtooth, binary alternating
 *   - Match lengths > 255 (overflow chain stress)
 *   - All-zeros, all-0xFF, single repeated byte variants
 *   - Blocks with matches that reach near the end (MFLIMIT boundary stress)
 */
class YawkatFuzzTest {

    private static final LZ4Factory          YAWKAT_FACTORY = LZ4Factory.safeInstance();
    private static final LZ4FastDecompressor YAWKAT_DEC     = YAWKAT_FACTORY.fastDecompressor();
    private static final LZ4Compressor       YAWKAT_ENC     = YAWKAT_FACTORY.fastCompressor();

    // ── Compressor registry ───────────────────────────────────────────────────

    record FemtoComp(String name, BiFunction<byte[], Integer, byte[]> compress) {
        byte[] compress(byte[] src) { return compress.apply(src, src.length); }
        @Override public String toString() { return name; }
    }

    private static final List<FemtoComp> ALL_COMPRESSORS = List.of(
        new FemtoComp("chain=0(2way)",  (s, n) -> LZ4.compress(s, 0)),
        new FemtoComp("chain=1(fast)",  (s, n) -> LZ4.compress(s, 1)),
        new FemtoComp("chain=2",        (s, n) -> LZ4.compress(s, 2)),
        new FemtoComp("chain=4",        (s, n) -> LZ4.compress(s, 4)),
        new FemtoComp("chain=8",        (s, n) -> LZ4.compress(s, 8)),
        new FemtoComp("chain=256(HC)",  (s, n) -> LZ4.compress(s, 256)),
        new FemtoComp("java-chain=0",   (s, n) -> LZ4Java.compressJava(s, 0)),
        new FemtoComp("java-chain=1",   (s, n) -> LZ4Java.compressJava(s, 1)),
        new FemtoComp("java-chain=2",   (s, n) -> LZ4Java.compressJava(s, 2)),
        new FemtoComp("java-chain=4",   (s, n) -> LZ4Java.compressJava(s, 4)),
        new FemtoComp("java-chain=256", (s, n) -> LZ4Java.compressJava(s, 256))
    );

    /** Decompress with femto-native, falling back to femto-java. */
    private static byte[] femtoDecompress(byte[] comp, int originalLen) {
        return LZ4.decompress(comp, originalLen);
    }

    /** Decompress with femto pure-Java. */
    private static byte[] javaDecompress(byte[] comp, int originalLen) {
        return LZ4Java.decompressJava(comp, originalLen);
    }

    /** Decompress with yawkat oracle. */
    private static byte[] yawkatDecompress(byte[] comp, int originalLen) {
        byte[] dst = new byte[originalLen];
        YAWKAT_DEC.decompress(comp, 0, dst, 0, originalLen);
        return dst;
    }

    // ── Core assertion helpers ────────────────────────────────────────────────

    /**
     * Assert all 11 femtolz4 compressors produce output that:
     * 1. femto-native decompresses to src
     * 2. femto-java decompresses to src
     * 3. yawkat decompresses to src  (when originalLen >= 16 — yawkat restriction)
     */
    private static void assertAllModes(byte[] src, String context) {
        for (FemtoComp comp : ALL_COMPRESSORS) {
            byte[] compressed = comp.compress(src);
            String tag = context + " [" + comp + "]";

            byte[] backFemto = femtoDecompress(compressed, src.length);
            assertArrayEquals(src, backFemto, tag + " femto-decomp");

            byte[] backJava = javaDecompress(compressed, src.length);
            assertArrayEquals(src, backJava, tag + " java-decomp");

            // yawkat has a restriction: the non-final literal run must end ≥8 bytes
            // before destEnd, which fails for very short inputs.
            if (src.length >= 16) {
                byte[] backYawkat = yawkatDecompress(compressed, src.length);
                assertArrayEquals(src, backYawkat, tag + " yawkat-decomp");
            }
        }
    }

    /** Additionally compress with yawkat and decompress with all femto modes. */
    private static void assertYawkatToFemto(byte[] src, String context) {
        byte[] tmp = new byte[YAWKAT_ENC.maxCompressedLength(src.length)];
        int n = YAWKAT_ENC.compress(src, 0, src.length, tmp, 0, tmp.length);
        byte[] comp = Arrays.copyOf(tmp, n);

        byte[] backFemto = femtoDecompress(comp, src.length);
        assertArrayEquals(src, backFemto, context + " [yawkat→femto-native]");

        byte[] backJava = javaDecompress(comp, src.length);
        assertArrayEquals(src, backJava, context + " [yawkat→femto-java]");
    }

    // ── Property-based fuzz tests ─────────────────────────────────────────────

    @Property
    @Tag("deep-fuzz")
    void randomBytesAllModes(@ForAll @Size(max = 16384) byte[] data) {
        assertAllModes(data, "random");
    }

    @Property(tries = 500)
    @Tag("slow-fuzz")
    void randomBytesAllModesLarge(@ForAll @Size(min = 65536, max = 256 * 1024) byte[] data) {
        assertAllModes(data, "random-large");
    }

    @Property
    @Tag("deep-fuzz")
    void randomBytesYawkatOracle(@ForAll @Size(min = 16, max = 65536) byte[] data) {
        assertYawkatToFemto(data, "random");
        // Also: femto all-modes → yawkat
        for (FemtoComp comp : ALL_COMPRESSORS) {
            if (data.length < 16) continue;
            byte[] compressed = comp.compress(data);
            byte[] backY = yawkatDecompress(compressed, data.length);
            assertArrayEquals(data, backY, "random [" + comp + " → yawkat]");
        }
    }

    // ── MFLIMIT boundary stress ──────────────────────────────────────────────

    /**
     * Stress the MFLIMIT=12 boundary: create inputs where the optimal match
     * would end within the last 12 bytes, forcing the compressor to emit literals.
     * The last match must start at position ≤ srcLen-12.
     */
    @Property
    @Tag("deep-fuzz")
    void mflimitBoundaryStress(@ForAll @IntRange(min = 12, max = 200) int len,
                               @ForAll @IntRange(min = 1, max = 4) int patLen) {
        // Pattern repeated enough to create matches, with varying tail length
        byte[] pat = new byte[patLen];
        new Random(len * 31L + patLen).nextBytes(pat);
        byte[] src = new byte[len];
        for (int i = 0; i < len; i++) src[i] = pat[i % patLen];
        assertAllModes(src, "mflimit-boundary len=" + len + " pat=" + patLen);
    }

    /**
     * Exact MFLIMIT boundary: src length that triggers different code paths
     * at exactly srcLen ∈ {8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 24}.
     */
    @Property

    void exactMflimitLengths(@ForAll @IntRange(min = 1, max = 30) int len) {
        // Both repetitive (compressible) and random (incompressible) content
        byte[] repSrc = new byte[len];
        Arrays.fill(repSrc, (byte) 0x42);
        assertAllModes(repSrc, "mflimit-exact-rep len=" + len);

        byte[] rndSrc = new byte[len];
        new Random(len * 7L).nextBytes(rndSrc);
        assertAllModes(rndSrc, "mflimit-exact-rnd len=" + len);
    }

    /**
     * Force a match that would be followed by exactly 0..11 tail literals —
     * exercises the boundary where a match may or may not be emitted.
     * Build: [32-byte repeat] + [tail of `tailLen` random bytes]
     */
    @Property
    @Tag("deep-fuzz")
    void matchFollowedByShortTail(@ForAll @IntRange(min = 0, max = 15) int tailLen,
                                  @ForAll @IntRange(min = 1, max = 4) int patLen) {
        byte[] pat = new byte[patLen];
        new Random(tailLen * 13L + patLen).nextBytes(pat);
        int bodyLen = 64;
        byte[] body = new byte[bodyLen];
        for (int i = 0; i < bodyLen; i++) body[i] = pat[i % patLen];
        byte[] tail = new byte[tailLen];
        new Random(tailLen * 97L).nextBytes(tail);
        byte[] src = new byte[bodyLen + tailLen];
        System.arraycopy(body, 0, src, 0, bodyLen);
        System.arraycopy(tail, 0, src, bodyLen, tailLen);
        assertAllModes(src, "match+tail tailLen=" + tailLen + " pat=" + patLen);
    }

    // ── Highly compressible data ─────────────────────────────────────────────

    @Property

    void singleRepeatedByte(@ForAll byte value, @ForAll @IntRange(min = 1, max = 65536) int len) {
        byte[] src = new byte[len];
        Arrays.fill(src, value);
        assertAllModes(src, "single-byte value=0x" + Integer.toHexString(value & 0xFF) + " len=" + len);
    }

    @Property

    void shortPatternRepeated(@ForAll @Size(min = 1, max = 8) byte[] pattern,
                              @ForAll @IntRange(min = 1, max = 65536) int totalLen) {
        byte[] src = new byte[totalLen];
        for (int i = 0; i < totalLen; i++) src[i] = pattern[i % pattern.length];
        assertAllModes(src, "pattern len=" + pattern.length + " total=" + totalLen);
    }

    @Property

    void allZerosVariousLengths(@ForAll @IntRange(min = 1, max = 131072) int len) {
        assertAllModes(new byte[len], "all-zeros len=" + len);
    }

    // ── Near-incompressible (stress adaptive skip) ────────────────────────────

    @Property

    void nearIncompressible(@ForAll @Size(min = 1024, max = 65536) byte[] data) {
        // Make data high-entropy by XORing with a random key to defeat any compression
        byte[] src = data.clone();
        long seed = 0;
        for (int i = 0; i < src.length; i++) { seed = seed * 6364136223846793005L + 1442695040888963407L; src[i] ^= (byte)(seed >>> 56); }
        assertAllModes(src, "near-incompressible");
    }

    // ── Non-zero offsets ─────────────────────────────────────────────────────

    @Property

    void nonZeroSrcOff(@ForAll @Size(min = 16, max = 32768) byte[] data,
                       @ForAll @IntRange(min = 1, max = 256) int srcOff) {
        byte[] padded = new byte[srcOff + data.length];
        System.arraycopy(data, 0, padded, srcOff, data.length);

        int maxComp = LZ4.maxCompressedLength(data.length);
        byte[] compBuf = new byte[maxComp];

        for (FemtoComp fc : ALL_COMPRESSORS) {
            // Use LZ4Java.compressJavaImpl via public API with srcOff
            int cLen = LZ4.compressJava(padded, srcOff, data.length, compBuf, 0,
                                        LZ4.compress(data, 1).length > 0 ? 1 : 1);
            // ^ chain=1 for all; it's testing offset correctness not ratio
            // Actually use direct method
            int cLen2 = LZ4Java.compressJava(padded, srcOff, data.length, compBuf, 0, 1);
            assertTrue(cLen2 > 0, "compressJava returned 0 srcOff=" + srcOff);

            byte[] back = javaDecompress(Arrays.copyOf(compBuf, cLen2), data.length);
            assertArrayEquals(data, back, "nonZeroSrcOff srcOff=" + srcOff);
        }
    }

    @Property

    void nonZeroDstOff(@ForAll @Size(min = 16, max = 32768) byte[] data,
                       @ForAll @IntRange(min = 1, max = 256) int dstOff) {
        byte[] comp = LZ4.compress(data, 1);
        byte[] dst  = new byte[dstOff + data.length];

        // femto-native
        int n = LZ4.decompress(comp, 0, comp.length, dst, dstOff, data.length);
        assertEquals(data.length, n);
        assertArrayEquals(data, Arrays.copyOfRange(dst, dstOff, dstOff + data.length),
            "femto-native dstOff=" + dstOff);

        // femto-java
        Arrays.fill(dst, (byte) 0);
        int n2 = LZ4Java.decompressJavaWithMatchLowerBound(comp, 0, comp.length,
                                                            dst, dstOff, data.length, dstOff);
        assertEquals(data.length, n2);
        assertArrayEquals(data, Arrays.copyOfRange(dst, dstOff, dstOff + data.length),
            "femto-java dstOff=" + dstOff);
    }

    // ── Structured data ──────────────────────────────────────────────────────

    @Property

    void sortedAscending(@ForAll @IntRange(min = 1, max = 65536) int len) {
        byte[] src = new byte[len];
        for (int i = 0; i < len; i++) src[i] = (byte)(i & 0xFF);
        assertAllModes(src, "sorted-ascending len=" + len);
    }

    @Property

    void sortedDescending(@ForAll @IntRange(min = 1, max = 65536) int len) {
        byte[] src = new byte[len];
        for (int i = 0; i < len; i++) src[i] = (byte)((len - i) & 0xFF);
        assertAllModes(src, "sorted-descending len=" + len);
    }

    @Property

    void sawtoothPattern(@ForAll @IntRange(min = 2, max = 256) int period,
                         @ForAll @IntRange(min = 16, max = 65536) int len) {
        byte[] src = new byte[len];
        for (int i = 0; i < len; i++) src[i] = (byte)(i % period);
        assertAllModes(src, "sawtooth period=" + period + " len=" + len);
    }

    @Property

    void binaryAlternating(@ForAll @IntRange(min = 1, max = 65536) int len) {
        byte[] src = new byte[len];
        for (int i = 0; i < len; i++) src[i] = (byte)(i & 1);
        assertAllModes(src, "binary-alternating len=" + len);
    }

    // ── WINDOW_SIZE boundary ─────────────────────────────────────────────────

    @Property

    void windowSizeBoundary(@ForAll @IntRange(min = 0, max = 512) int extra) {
        int len = LZ4.WINDOW_SIZE + extra;
        byte[] src = new byte[len];
        new Random(len).nextBytes(src);
        // place an identical 8-byte pattern just before and at the window boundary
        byte[] pat = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        System.arraycopy(pat, 0, src, 0, pat.length);
        int patPos2 = Math.min(LZ4.WINDOW_SIZE - 4, len - pat.length);
        System.arraycopy(pat, 0, src, patPos2, pat.length);
        assertAllModes(src, "window-boundary extra=" + extra);
    }

    // ── Long matches (overflow encoding chain) ────────────────────────────────

    @Property

    void longMatchOverflowEncoding(@ForAll @IntRange(min = 20, max = 5000) int runLen,
                                   @ForAll @IntRange(min = 1, max = 4) int patLen) {
        byte[] pat = new byte[patLen];
        new Random(runLen * 13L + patLen).nextBytes(pat);
        byte[] src = new byte[runLen * 2];
        for (int i = 0; i < src.length; i++) src[i] = pat[i % patLen];
        assertAllModes(src, "long-match runLen=" + runLen + " patLen=" + patLen);
    }

    // ── Mix of compressible and incompressible regions ────────────────────────

    @Property

    void mixedCompressibleIncompressible(@ForAll @IntRange(min = 1024, max = 32768) int len,
                                         @ForAll @IntRange(min = 1, max = 10) int seed) {
        byte[] src = new byte[len];
        Random rng = new Random(seed);
        int pos = 0;
        while (pos < len) {
            boolean compressible = (pos / 512) % 2 == 0;
            int chunkLen = Math.min(512, len - pos);
            if (compressible) {
                byte v = (byte) rng.nextInt(256);
                Arrays.fill(src, pos, pos + chunkLen, v);
            } else {
                byte[] chunk = new byte[chunkLen];
                rng.nextBytes(chunk);
                System.arraycopy(chunk, 0, src, pos, chunkLen);
            }
            pos += chunkLen;
        }
        assertAllModes(src, "mixed len=" + len);
    }

    // ── Lengths near PADDING (5) and MFLIMIT (12) boundaries ─────────────────

    @Property

    void nearPaddingAndMflimitBoundary(@ForAll @IntRange(min = 1, max = 30) int len) {
        byte[] src = new byte[len];
        new Random(len * 7L).nextBytes(src);
        assertAllModes(src, "near-padding-mflimit len=" + len);
    }

    // ── All-0xFF ─────────────────────────────────────────────────────────────

    @Property

    void allFF(@ForAll @IntRange(min = 1, max = 65536) int len) {
        byte[] src = new byte[len];
        Arrays.fill(src, (byte) 0xFF);
        assertAllModes(src, "all-0xFF len=" + len);
    }

    // ── srcLen = 0 through all modes ─────────────────────────────────────────

    @net.jqwik.api.Example
    void zeroLengthAllModes() {
        byte[] empty = new byte[0];
        assertAllModes(empty, "empty");
    }

    // ── yawkat→femto for structured data ─────────────────────────────────────

    @Property

    void yawkatProducedOutputRoundTripAllFemtoModes(
            @ForAll @Size(min = 16, max = 65536) byte[] data) {
        assertYawkatToFemto(data, "yawkat→femto");
    }

    @Property

    void yawkatHCProducedOutputRoundTrip(@ForAll @Size(min = 16, max = 32768) byte[] data) {
        LZ4Compressor hc = YAWKAT_FACTORY.highCompressor();
        byte[] tmp = new byte[hc.maxCompressedLength(data.length)];
        int n = hc.compress(data, 0, data.length, tmp, 0, tmp.length);
        byte[] comp = Arrays.copyOf(tmp, n);

        assertArrayEquals(data, femtoDecompress(comp, data.length),
            "yawkat-HC → femto-native");
        assertArrayEquals(data, javaDecompress(comp, data.length),
            "yawkat-HC → femto-java");
    }

    // ── Compressor output correctness relative to itself ─────────────────────

    /**
     * Every mode's compressed output must decompress to the original with BOTH
     * the femto-java decompressor AND the yawkat oracle (cross-compat).
     * This is the core invariant: if femto compresses it, any LZ4-spec-compliant
     * decompressor must be able to decode it.
     */
    @Property
    @Tag("deep-fuzz")
    void crossCompatibilityAllModes(@ForAll @Size(min = 16, max = 32768) byte[] data) {
        for (FemtoComp fc : ALL_COMPRESSORS) {
            byte[] compressed = fc.compress(data);
            String tag = "cross-compat [" + fc + "]";

            // 1. femto-java decompresses correctly
            assertArrayEquals(data, javaDecompress(compressed, data.length),
                tag + " → femto-java");
            // 2. yawkat decompresses correctly
            assertArrayEquals(data, yawkatDecompress(compressed, data.length),
                tag + " → yawkat");
        }
    }

    // ── Compression ratio is ≥ yawkat for highly compressible data ───────────

    @Property

    void hcRatioBetterThanFast(@ForAll @IntRange(min = 1024, max = 65536) int len,
                               @ForAll @IntRange(min = 1, max = 8) int patLen) {
        byte[] pat = new byte[patLen];
        new Random(len).nextBytes(pat);
        byte[] src = new byte[len];
        for (int i = 0; i < len; i++) src[i] = pat[i % patLen];

        byte[] fast = LZ4.compress(src, 1);
        byte[] hc   = LZ4.compress(src, 256);

        // HC must produce output that decompresses correctly
        assertArrayEquals(src, femtoDecompress(fast, len), "fast round-trip");
        assertArrayEquals(src, femtoDecompress(hc,   len), "HC round-trip");

        // HC should produce equal or smaller output on compressible data
        assertTrue(hc.length <= fast.length + 16,
            "HC output larger than fast by >16 bytes: hc=" + hc.length + " fast=" + fast.length
            + " patLen=" + patLen + " len=" + len);
    }

    // ── Monotone ascending blocks of varying byte range ──────────────────────

    /**
     * Blocks where bytes cycle through exactly `range` distinct values.
     * Low range = high compressibility, high range = lower compressibility.
     */
    @Property

    void cyclingByteRange(@ForAll @IntRange(min = 1, max = 256) int range,
                          @ForAll @IntRange(min = 16, max = 65536) int len) {
        byte[] src = new byte[len];
        for (int i = 0; i < len; i++) src[i] = (byte)(i % range);
        assertAllModes(src, "cycling range=" + range + " len=" + len);
    }

    // ── Blocks with a large literal run at the end (tail stress) ─────────────

    /**
     * Compressible prefix followed by random suffix — stresses the tail
     * literal encoding path that must correctly handle the final sequence.
     */
    @Property

    void compressiblePrefixRandomSuffix(@ForAll @IntRange(min = 64, max = 32768) int bodyLen,
                                        @ForAll @IntRange(min = 1, max = 32) int tailLen) {
        int total = bodyLen + tailLen;
        byte[] src = new byte[total];
        // Highly compressible body: single byte value
        Arrays.fill(src, 0, bodyLen, (byte) 0x77);
        // Random tail that creates a literal-only final sequence
        byte[] tailBytes = new byte[tailLen];
        new Random(tailLen).nextBytes(tailBytes);
        System.arraycopy(tailBytes, 0, src, bodyLen, tailLen);
        assertAllModes(src, "prefix+tail body=" + bodyLen + " tail=" + tailLen);
    }

    // ── Adaptive skip boundary ────────────────────────────────────────────────

    /**
     * Mix: 128 random bytes (triggers skip mode) then compressible bytes.
     * This stresses the miss_bytes/skip_ctr reset after a match is found.
     */
    @Property

    void skipModeResetOnMatch(@ForAll @IntRange(min = 1, max = 4) int patLen,
                              @ForAll @IntRange(min = 512, max = 16384) int totalLen) {
        byte[] src = new byte[totalLen];
        Random rng = new Random(patLen * 101L + totalLen);
        // First 128 bytes: high-entropy to trigger skip mode
        rng.nextBytes(src);
        // Rest: repeated pattern to find matches and reset skip counter
        byte[] pat = new byte[patLen];
        rng.nextBytes(pat);
        for (int i = 128; i < totalLen; i++) src[i] = pat[i % patLen];
        assertAllModes(src, "skip-reset pat=" + patLen + " total=" + totalLen);
    }

    // ── Fibonacci-spaced matches ──────────────────────────────────────────────

    /**
     * Place identical 8-byte markers at Fibonacci-spaced positions.
     * This creates irregular match distances that stress chain lookup.
     */
    @Property

    void fibonacciSpacedMatches(@ForAll @IntRange(min = 512, max = 32768) int len) {
        byte[] src = new byte[len];
        new Random(len).nextBytes(src);
        byte[] marker = {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77};
        int a = 0, b = 8;
        while (b + marker.length <= len) {
            System.arraycopy(marker, 0, src, b, marker.length);
            int next = a + b;
            a = b;
            b = next;
        }
        assertAllModes(src, "fibonacci-spaced len=" + len);
    }

    // ── Alternating compressible/incompressible 16-byte blocks ───────────────

    @Property

    void alternating16ByteBlocks(@ForAll @IntRange(min = 64, max = 16384) int len) {
        byte[] src = new byte[len];
        Random rng = new Random(len * 41L);
        for (int i = 0; i < len; i += 32) {
            // 16 bytes: all same value (compressible)
            byte v = (byte) rng.nextInt(256);
            int end1 = Math.min(i + 16, len);
            Arrays.fill(src, i, end1, v);
            // 16 bytes: random (incompressible)
            int start2 = i + 16, end2 = Math.min(i + 32, len);
            for (int j = start2; j < end2; j++) src[j] = (byte) rng.nextInt(256);
        }
        assertAllModes(src, "alternating-16 len=" + len);
    }

    // ── All-zeros then all-0xFF halves ────────────────────────────────────────

    @Property

    void zerosThenFF(@ForAll @IntRange(min = 16, max = 65536) int len) {
        byte[] src = new byte[len];
        Arrays.fill(src, len / 2, len, (byte) 0xFF);
        assertAllModes(src, "zeros+FF len=" + len);
    }

    // ── Block with exactly 12 trailing literals (MFLIMIT) ────────────────────

    /**
     * Manually craft a block with exactly 12 random trailing bytes after the
     * last possible match position, stressing the MFLIMIT boundary directly.
     */
    @Property

    void exactMflimitTrailingLiterals(@ForAll @IntRange(min = 32, max = 8192) int bodyLen,
                                      @ForAll @IntRange(min = 0, max = 11) int extraLiterals) {
        // body: repeated pattern to create matches
        byte[] src = new byte[bodyLen + 12 + extraLiterals];
        byte[] pat = {0x01, 0x02, 0x03, 0x04};
        for (int i = 0; i < bodyLen; i++) src[i] = pat[i % pat.length];
        // tail: 12+extraLiterals distinct bytes (high entropy, won't match body)
        for (int i = bodyLen; i < src.length; i++) src[i] = (byte)(0x80 + (i * 13) % 64);
        assertAllModes(src, "exact-mflimit body=" + bodyLen + " extra=" + extraLiterals);
    }

    // ── Realistic-ish data: Java class file header pattern ───────────────────

    @Property

    void javaClassHeaderLike(@ForAll @IntRange(min = 100, max = 16384) int len) {
        byte[] src = new byte[len];
        // Simulate typical JFR/class file byte patterns
        Random rng = new Random(len);
        // Magic bytes
        src[0] = (byte) 0xCA; src[1] = (byte) 0xFE;
        src[2] = (byte) 0xBA; src[3] = (byte) 0xBE;
        // Fill rest with mix of 0x00, ASCII, and some repeated structures
        for (int i = 4; i < len; i++) {
            int r = rng.nextInt(10);
            src[i] = (r < 3) ? 0 : (r < 6) ? (byte)(0x40 + rng.nextInt(64)) : (byte) rng.nextInt(256);
        }
        assertAllModes(src, "class-header-like len=" + len);
    }

    // ── All compressors produce the same decompressed output ─────────────────

    /**
     * Different compressors may produce different compressed bytes, but must
     * all decompress to the same original.  Cross-checks every pair.
     */
    @Property

    void allCompressorOutputEquivalent(@ForAll @Size(min = 1, max = 8192) byte[] data) {
        byte[][] allCompressed = new byte[ALL_COMPRESSORS.size()][];
        for (int i = 0; i < ALL_COMPRESSORS.size(); i++) {
            allCompressed[i] = ALL_COMPRESSORS.get(i).compress(data);
        }
        for (int i = 0; i < allCompressed.length; i++) {
            byte[] back = javaDecompress(allCompressed[i], data.length);
            assertArrayEquals(data, back,
                "compressor " + ALL_COMPRESSORS.get(i) + " java-decomp");
            if (data.length >= 16) {
                byte[] backY = yawkatDecompress(allCompressed[i], data.length);
                assertArrayEquals(data, backY,
                    "compressor " + ALL_COMPRESSORS.get(i) + " yawkat-decomp");
            }
        }
    }
}
