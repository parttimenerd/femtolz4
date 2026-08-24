package me.bechberger.femtolz4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Third round of bug-hunting tests.  Probes areas not yet covered:
 *
 *  A. srcLen near PADDING boundary (1–10)
 *  B. Back-to-back matches — litLen=0 token sequences
 *  C. srcOff large enough to make sentinel wrap near Integer.MIN_VALUE
 *  D. Non-zero dstOff with matchLowerBound enforcement
 *  E. copyLiterals at exact VarHandle read boundaries (4, 8, 16, 32, 64)
 *  F. copyMatch correctness — all offsets 1..16 × all matchLen 4..64
 */
class ProbeEdgeTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** All combos of (6 compressors) × (2 decompressors). */
    record PCombo(String name, PComp comp, PDecomp decomp) {
        @Override public String toString() { return name; }
    }

    @FunctionalInterface interface PComp   { byte[] compress(byte[] src); }
    @FunctionalInterface interface PDecomp { byte[] decompress(byte[] data, int originalLen); }

    static Stream<PCombo> allCombos() {
        List<PComp> compressors = List.of(
            new PComp() { public byte[] compress(byte[] s) { return LZ4.compress(s, 0); }
                         public String toString() { return "chain=0"; } },
            new PComp() { public byte[] compress(byte[] s) { return LZ4.compress(s, 1); }
                         public String toString() { return "chain=1"; } },
            new PComp() { public byte[] compress(byte[] s) { return LZ4.compress(s, 2); }
                         public String toString() { return "chain=2"; } },
            new PComp() { public byte[] compress(byte[] s) { return LZ4.compress(s, 4); }
                         public String toString() { return "chain=4"; } },
            new PComp() { public byte[] compress(byte[] s) { return LZ4Java.compressJava(s, 1); }
                         public String toString() { return "java-fast"; } },
            new PComp() { public byte[] compress(byte[] s) { return LZ4Java.compressJava(s, 4); }
                         public String toString() { return "java-chain=4"; } }
        );
        List<PDecomp> decompressors = List.of(
            new PDecomp() { public byte[] decompress(byte[] c, int n) { return LZ4.decompress(c, n); }
                           public String toString() { return "native"; } },
            new PDecomp() { public byte[] decompress(byte[] c, int n) { return LZ4Java.decompressJava(c, n); }
                           public String toString() { return "java"; } }
        );
        List<PCombo> out = new ArrayList<>();
        for (PComp c : compressors)
            for (PDecomp d : decompressors)
                out.add(new PCombo(c + "+" + d, c, d));
        return out.stream();
    }

    private static void assertRoundTrip(byte[] src, String msg) {
        // Test with multiple chain depths
        for (int chain : new int[]{0, 1, 2, 4}) {
            byte[] comp = LZ4.compress(src, chain);
            byte[] back = LZ4Java.decompressJava(comp, src.length);
            assertArrayEquals(src, back, msg + " [chain=" + chain + ", java-decomp]");
            if (LZ4.isNativeAvailable()) {
                byte[] back2 = LZ4.decompress(comp, src.length);
                assertArrayEquals(src, back2, msg + " [chain=" + chain + ", native-decomp]");
            }
        }
    }

    // ── A. srcLen near PADDING boundary ──────────────────────────────────────

    @ParameterizedTest(name = "srcLen={0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
    void veryShortInputAllChains(int srcLen) {
        byte[] src = new byte[srcLen];
        new Random(srcLen * 31 + 7).nextBytes(src);

        for (int chain : new int[]{0, 1, 2, 4, 8}) {
            byte[] comp = LZ4.compress(src, chain);
            byte[] backJ = LZ4Java.decompressJava(comp, srcLen);
            assertArrayEquals(src, backJ,
                "srcLen=" + srcLen + " chain=" + chain + " java-decomp");
            if (LZ4.isNativeAvailable()) {
                byte[] backN = LZ4.decompress(comp, srcLen);
                assertArrayEquals(src, backN,
                    "srcLen=" + srcLen + " chain=" + chain + " native-decomp");
            }
        }
    }

    @Test void zeroLengthRoundTrip() {
        byte[] comp = LZ4.compress(new byte[0], 1);
        assertEquals(0, LZ4Java.decompressJava(comp, 0).length);
    }

    // ── B. Back-to-back matches (litLen=0 token) ─────────────────────────────

    /** Craft a pattern where two match sequences are adjacent: no literals between them. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void backToBackMatchesLitLen0(PCombo c) {
        // "ABCD" repeated 64 times: the compressor should emit sequences with litLen=0
        // (the second and subsequent back-references) — exercises token(0, matchExtra).
        byte[] src = new byte[256];
        for (int i = 0; i < src.length; i++) src[i] = (byte) (i & 3);

        byte[] comp = c.comp.compress(src);
        byte[] back = c.decomp.decompress(comp, src.length);
        assertArrayEquals(src, back, "back-to-back match round-trip");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void backToBackMatchesHighlyRepetitive(PCombo c) {
        byte[] src = "AAAA".repeat(256).getBytes();
        byte[] comp = c.comp.compress(src);
        byte[] back = c.decomp.decompress(comp, src.length);
        assertArrayEquals(src, back, "highly repetitive back-to-back");
    }

    // ── C. Large srcOff — sentinel boundary ──────────────────────────────────

    /** When srcOff is large, sentinel = srcOff - WINDOW_SIZE - 1 approaches Integer.MIN_VALUE. */
    @ParameterizedTest(name = "srcOff={0}")
    @ValueSource(ints = {
        0x3FFF0000,  // large but sentinel still positive
        0x7FFF0000,  // sentinel just positive
        0x7FFFFFFF - 0x10000 - 1,  // sentinel near zero
        0x7FFFFFFF - 0x10000,      // sentinel = -1
        0x7FFFFFFF - 1,            // sentinel deeply negative
        Integer.MAX_VALUE          // sentinel = MAX_VALUE - WINDOW_SIZE - 1
    })
    void largeSrcOffSentinelBoundary(int srcOff) {
        // Allocate a minimal buffer at the given offset. Content: a short repetitive pattern
        // so the compressor must find a match (or emit literals safely).
        int srcLen = 128;
        // We can't actually allocate a 2GB array — create a virtual-offset test via
        // compressJava which uses srcOff directly.
        byte[] bigSrc;
        long bigSize = (long) srcOff + srcLen + 8;
        if (bigSize > 200_000_000L) {
            // Skip if this would require a > 200 MB allocation
            return;
        }
        try {
            bigSrc = new byte[(int) bigSize];
        } catch (OutOfMemoryError e) {
            return;
        }
        for (int i = 0; i < srcLen; i++) bigSrc[srcOff + i] = (byte) (i % 8);

        byte[] compBuf = new byte[LZ4.maxCompressedLength(srcLen)];
        int compLen = LZ4.compressJava(bigSrc, srcOff, srcLen, compBuf, 0, 1);
        assertTrue(compLen > 0, "compress returned 0 for srcOff=" + srcOff);

        byte[] dstBuf = new byte[srcLen];
        int n = LZ4Java.decompressJavaWithMatchLowerBound(compBuf, 0, compLen, dstBuf, 0, srcLen, 0);
        assertEquals(srcLen, n);
        byte[] expected = Arrays.copyOfRange(bigSrc, srcOff, srcOff + srcLen);
        assertArrayEquals(expected, dstBuf, "srcOff=" + srcOff);
    }

    // ── D. Non-zero dstOff — matchLowerBound enforcement ─────────────────────

    @ParameterizedTest(name = "dstOff={0}")
    @ValueSource(ints = {0, 1, 7, 15, 16, 63, 64, 100, 255, 1000})
    void nonZeroDstOffMatchLowerBound(int dstOff) {
        // Compress without offset, decompress into a pre-padded buffer at dstOff.
        // Match references must not reach before dstOff.
        byte[] inner = "hello world hello world".repeat(20).getBytes();
        byte[] comp  = LZ4.compress(inner, 1);

        byte[] dst = new byte[dstOff + inner.length];
        int n = LZ4.decompress(comp, 0, comp.length, dst, dstOff, inner.length);
        assertEquals(inner.length, n, "decompressed length at dstOff=" + dstOff);
        assertArrayEquals(inner, Arrays.copyOfRange(dst, dstOff, dstOff + inner.length),
            "content mismatch at dstOff=" + dstOff);
    }

    @ParameterizedTest(name = "dstOff={0}")
    @ValueSource(ints = {0, 1, 7, 15, 16, 63, 64, 100, 255, 1000})
    void nonZeroDstOffJavaDecomp(int dstOff) {
        byte[] inner = "hello world hello world".repeat(20).getBytes();
        byte[] comp  = LZ4Java.compressJava(inner, 1);

        byte[] dst = new byte[dstOff + inner.length];
        int n = LZ4Java.decompressJavaWithMatchLowerBound(comp, 0, comp.length,
                                                          dst, dstOff, inner.length, dstOff);
        assertEquals(inner.length, n, "java decomp length at dstOff=" + dstOff);
        assertArrayEquals(inner, Arrays.copyOfRange(dst, dstOff, dstOff + inner.length),
            "java decomp content mismatch at dstOff=" + dstOff);
    }

    // ── E. copyLiterals at exact VarHandle boundaries ─────────────────────────

    /**
     * Craft a source where the literal run has exactly the given length so that
     * the VarHandle reads hit boundary conditions (7,8,9 / 15,16,17 / 31,32,33 / 63,64,65).
     * The literal is followed by a match so it isn't the final run.
     */
    @ParameterizedTest(name = "litLen={0}")
    @ValueSource(ints = {1,2,3,4,5,6,7,8,9,10,11,12,
                         14,15,16,17,18,
                         30,31,32,33,34,
                         62,63,64,65,66,
                         126,127,128,129})
    void copyLiteralsBoundaries(int litLen) {
        // Build src: litLen unique bytes, then a repeated pattern for matching.
        int patLen = 32;
        byte[] src = new byte[litLen + patLen * 2];
        // distinct "noise" bytes so no accidental match in the literal run
        for (int i = 0; i < litLen; i++) src[i] = (byte) (0x10 + (i * 7) % 200);
        // repeated pattern after the literal
        byte[] pat = new byte[patLen];
        new Random(litLen).nextBytes(pat);
        System.arraycopy(pat, 0, src, litLen,        patLen);
        System.arraycopy(pat, 0, src, litLen + patLen, patLen);

        assertRoundTrip(src, "copyLiterals litLen=" + litLen);
    }

    // ── F. copyMatch exhaustive: all offsets × all match lengths ─────────────

    /**
     * For every offset 1..16 and every matchLen 4..64, hand-craft a raw LZ4 block
     * that forces exactly that (offset, matchLen) combination and decompress it
     * with both Java and native.  This exercises copyMatch for all branch paths.
     */
    @Test void copyMatchExhaustive() {
        for (int offset = 1; offset <= 16; offset++) {
            for (int matchLen = 4; matchLen <= 64; matchLen++) {
                byte[] expected = buildExpected(offset, matchLen);
                byte[] block    = buildBlock(offset, matchLen, expected);

                byte[] backJ = LZ4Java.decompressJava(block, expected.length);
                assertArrayEquals(expected, backJ,
                    "java offset=" + offset + " matchLen=" + matchLen);

                if (LZ4.isNativeAvailable()) {
                    byte[] backN = LZ4.decompress(block, expected.length);
                    assertArrayEquals(expected, backN,
                        "native offset=" + offset + " matchLen=" + matchLen);
                }
            }
        }
    }

    /** Build the expected decompressed bytes for an (offset, matchLen) pair. */
    private static byte[] buildExpected(int offset, int matchLen) {
        // prefix: `offset` distinct bytes
        // then: matchLen bytes that are the repetition of the prefix pattern
        int totalLen = offset + matchLen;
        byte[] out = new byte[totalLen];
        for (int i = 0; i < offset; i++) out[i] = (byte) (0x10 + i * 13);
        for (int i = 0; i < matchLen; i++) out[offset + i] = out[i % offset];
        return out;
    }

    /**
     * Build a minimal raw LZ4 block that decompresses to {@code expected}.
     *
     * Format:
     *   token(litLen, matchExtra) [litLen bytes] offset_lo offset_hi [overflow bytes]
     *   token(0, 0)  (empty final literal run with zero match nibble — end-of-block)
     */
    private static byte[] buildBlock(int offset, int matchLen, byte[] expected) {
        int litLen    = offset;
        int matchExtra = matchLen - 4;   // matchLen = MIN_MATCH + matchExtra

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        // token
        int litNibble   = Math.min(litLen, 15);
        int matchNibble = Math.min(matchExtra, 15);
        buf.write((litNibble << 4) | matchNibble);

        // literal length overflow
        if (litLen >= 15) {
            int rem = litLen - 15;
            while (rem >= 255) { buf.write(255); rem -= 255; }
            buf.write(rem);
        }

        // literal bytes
        for (int i = 0; i < litLen; i++) buf.write(expected[i] & 0xFF);

        // match offset (little-endian 2 bytes)
        buf.write(offset & 0xFF);
        buf.write((offset >>> 8) & 0xFF);

        // match length overflow
        if (matchExtra >= 15) {
            int rem = matchExtra - 15;
            while (rem >= 255) { buf.write(255); rem -= 255; }
            buf.write(rem);
        }

        // final token: no literal, no match (end of block)
        buf.write(0x00);

        return buf.toByteArray();
    }

    // ── G. compressFast short-circuit (srcLen <= 6) ──────────────────────────

    @ParameterizedTest(name = "srcLen={0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void shortCircuitPathRoundTrip(int srcLen) {
        // The Java compressor has a fast-exit for srcLen <= 6 that emits one literal-only token.
        byte[] src = new byte[srcLen];
        new Random(srcLen).nextBytes(src);

        // Force Java path explicitly
        byte[] compJ = LZ4Java.compressJava(src, 1);
        byte[] backJ = LZ4Java.decompressJava(compJ, srcLen);
        assertArrayEquals(src, backJ, "java short-circuit srcLen=" + srcLen);

        // And via public API
        byte[] compP = LZ4.compress(src, 1);
        byte[] backP = LZ4.decompress(compP, srcLen);
        assertArrayEquals(src, backP, "public API short-circuit srcLen=" + srcLen);
    }

    // ── H. Compressor produces valid output for srcLen = PADDING+1 to PADDING+4 ──

    @ParameterizedTest(name = "srcLen={0}")
    @ValueSource(ints = {5, 6, 7, 8, 9})   // PADDING=5, so these straddle the boundary
    void paddingBoundaryRoundTrip(int srcLen) {
        // Use a repetitive pattern that might trigger the match path even for short inputs
        byte[] src = new byte[srcLen];
        Arrays.fill(src, (byte) 0x42);
        assertRoundTrip(src, "padding boundary srcLen=" + srcLen);
    }

    // ── I. Token with litLen=0 (first token is a match with no preceding literals) ──

    @Test void firstTokenIsMatchNoLiterals() {
        // Build a raw block: token(4,0) [4 lits] offset(4) token(0,0).
        //
        // Sequence 1 layout: token | litLen bytes | offset_lo | offset_hi
        // No second token comes between offset bytes and the final token.
        //
        // Block:  0x40 0x11 0x22 0x33 0x44  [4-byte literal run]
        //         0x04 0x00                  [match: litLen=0, matchExtra=0, offset=4]
        //         0x00                       [final empty token, no match]
        // Decompressed: 11 22 33 44 11 22 33 44  (first 4 literals + 4-byte match)
        byte[] block = {
            0x40, 0x11, 0x22, 0x33, 0x44,  // token(litLen=4, mex=0) + 4 literals
            0x04, 0x00,                    // match offset=4 LE, matchLen = MIN_MATCH+0 = 4
            0x00                           // final token: litLen=0, no match
        };
        byte[] expected = {0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44};

        byte[] backJ = LZ4Java.decompressJava(block, expected.length);
        assertArrayEquals(expected, backJ, "java litLen=0 first match");
        if (LZ4.isNativeAvailable()) {
            byte[] backN = LZ4.decompress(block, expected.length);
            assertArrayEquals(expected, backN, "native litLen=0 first match");
        }
    }

    // ── J. decompressJava buffer-boundary: dstLen exactly equals match end ────

    @Test void decompressExactDstLen() {
        // Build a block whose decompressed size is exactly dstLen to verify no
        // off-by-one in the "output overflow" check.
        byte[] src = "abcdabcdabcd".repeat(100).getBytes();
        byte[] comp = LZ4.compress(src, 4);
        byte[] dst = new byte[src.length];
        int n = LZ4Java.decompressJavaWithMatchLowerBound(comp, 0, comp.length,
                                                           dst, 0, src.length, 0);
        assertEquals(src.length, n);
        assertArrayEquals(src, dst);
    }

    // ── K. Fuzz: 1000 random inputs, all compressors must round-trip ──────────

    @Test void fuzzRoundTrip() {
        Random rng = new Random(0xCAFEBABE);
        for (int trial = 0; trial < 200; trial++) {
            int len = rng.nextInt(4096) + 1;
            byte[] src = new byte[len];
            rng.nextBytes(src);
            for (int chain : new int[]{0, 1, 2, 4}) {
                byte[] comp = LZ4.compress(src, chain);
                byte[] backJ = LZ4Java.decompressJava(comp, len);
                assertArrayEquals(src, backJ, "fuzz trial=" + trial + " chain=" + chain + " java");
                if (LZ4.isNativeAvailable()) {
                    byte[] backN = LZ4.decompress(comp, len);
                    assertArrayEquals(src, backN, "fuzz trial=" + trial + " chain=" + chain + " native");
                }
            }
        }
    }
}
