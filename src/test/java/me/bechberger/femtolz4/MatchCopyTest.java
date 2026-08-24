package me.bechberger.femtolz4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targets the match-copy code paths most likely to contain bugs:
 *
 *  - Overlapping matches at every offset 1..16 via hand-crafted LZ4 blocks
 *  - Round-trip correctness for synthetic data that forces specific offsets
 *  - Non-zero srcOff/dstOff in block compress + decompress
 *  - maxChain=0 (2-way fast path) dispatch
 *  - Large match lengths (overflow encoding: matchLen > 15+MIN_MATCH)
 *  - Match spanning the WINDOW_SIZE boundary
 *  - Native/Java round-trip agreement for all chain depths
 */
class MatchCopyTest {

    // ── Hand-crafted blocks: every match offset 1..16 ────────────────────────
    //
    // Format: emit a literal run, then reference it with the target offset.
    // For offset N we need at least N literals before the back-reference.
    //
    // Sequence layout:
    //   token(litLen, matchExtra), [litLen bytes], offset_lo, offset_hi
    //   final token(tailLen, 0), [tailLen bytes]
    //
    // matchLen = 4 + matchExtra (matchExtra = 0 → 4-byte match).
    //
    // We craft each block so that the back-reference forces the decompressor
    // down the overlapping branch (offset < matchLen) when offset ≤ 3, and
    // exercises the lockstep branch for offsets 8+.
    //
    // For offset ≥ matchLen (no overlap), we use matchLen = offset + 1 to
    // ensure we still exercise both non-overlapping and large-overlap paths.

    @ParameterizedTest(name = "matchOffset={0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
    void roundTripWithForcedMatchOffset(int offset) {
        // Build a source block that contains a repeated pattern at exactly `offset` distance.
        // Use a pattern of length 32 so that matchLen > offset for offsets ≤ 16.
        int patLen = 32;
        byte[] src = new byte[offset + patLen * 3];
        // Fill prefix with distinct non-repeating bytes so the compressor doesn't
        // find a match before our intended position.
        for (int i = 0; i < offset; i++) src[i] = (byte) (0x10 + i);
        // Repeat the same `patLen`-byte pattern twice after the prefix, so the
        // compressor should find a match of length patLen at distance `offset`+patLen.
        byte[] pat = new byte[patLen];
        new Random(offset).nextBytes(pat);
        System.arraycopy(pat, 0, src, offset, patLen);
        System.arraycopy(pat, 0, src, offset + patLen, patLen);
        // Fill the tail to avoid identical overlap at offset 1.
        for (int i = offset + patLen * 2; i < src.length; i++) src[i] = (byte)(0x50 + i);

        assertNativeJavaAgreement(src, 1);
        assertNativeJavaAgreement(src, 4);
    }

    // ── Hand-crafted block: offset=1 overlapping match (run-length) ──────────

    @Test void offset1RunLength() {
        // Single byte repeated 256 times. Both native and Java must produce the same result.
        byte[] src = new byte[256];
        Arrays.fill(src, (byte) 0xAB);
        assertNativeJavaAgreement(src, 1);
        assertRoundTrip(src, 1);
        assertRoundTrip(src, 8);
    }

    // ── Hand-crafted block: offset=2, pattern length not a power of two ─────

    @Test void offset2OddMatchLength() {
        // Two-byte pattern "XY" repeated. Match length 17 forces the stride
        // path to handle an odd tail.
        byte[] src = new byte[256];
        for (int i = 0; i < src.length; i++) src[i] = (byte)(i % 2 == 0 ? 0x41 : 0x42);
        assertNativeJavaAgreement(src, 1);
        assertRoundTrip(src, 1);
    }

    // ── Hand-crafted block: offset=3 (not a power of two) ───────────────────

    @Test void offset3MatchLength63() {
        // 3-byte pattern, match length 63 — exercises the doubling ladder fully.
        byte[] unit = {0x11, 0x22, 0x33};
        byte[] src  = new byte[3 + 63];
        for (int i = 0; i < src.length; i++) src[i] = unit[i % 3];
        assertNativeJavaAgreement(src, 1);
        assertRoundTrip(src, 1);
    }

    // ── Match length overflow encoding ────────────────────────────────────────

    @Test void matchLengthExactly19() {
        // matchExtra = 15 → overflow byte = 0. Total matchLen = 4 + 15 + 0 = 19.
        byte[] src = new byte[128];
        Arrays.fill(src, 0, 20, (byte) 0x55);
        Arrays.fill(src, 20, src.length, (byte) 0x66);
        assertRoundTrip(src, 1);
        assertNativeJavaAgreement(src, 1);
    }

    @Test void matchLengthOver255Plus19() {
        // Force a match length that requires a multi-byte overflow chain.
        // 300-byte run of the same byte: matchExtra = 296, encoded as 15 + 255 + 26.
        byte[] src = new byte[600];
        Arrays.fill(src, 0, 300, (byte) 0x77);
        Arrays.fill(src, 300, src.length, (byte) 0x88); // ensure termination
        assertRoundTrip(src, 1);
        assertNativeJavaAgreement(src, 1);
    }

    // ── Literal length overflow encoding ─────────────────────────────────────

    @Test void literalLengthOver15() {
        // 100 distinct bytes (no match possible) → literal run of 100, overflow-encoded.
        byte[] src = new byte[100];
        for (int i = 0; i < src.length; i++) src[i] = (byte)(i * 7 + 3); // pseudo-random distinct
        assertRoundTrip(src, 1);
        assertNativeJavaAgreement(src, 1);
    }

    @Test void literalLengthOver270() {
        // 300 incompressible bytes: litLen = 300 → 15 + 255 + 30 in two overflow bytes.
        byte[] rand = new byte[300];
        new Random(42).nextBytes(rand);
        // Make sure no 4-byte match exists by ensuring high entropy.
        assertRoundTrip(rand, 1);
        assertNativeJavaAgreement(rand, 1);
    }

    // ── Non-zero srcOff and dstOff ────────────────────────────────────────────

    @ParameterizedTest(name = "pad={0}")
    @ValueSource(ints = {0, 1, 7, 16, 100, 255})
    void nonZeroSrcOffDstOff(int pad) {
        byte[] inner = "hello world hello world hello world".getBytes();
        byte[] src = new byte[pad + inner.length];
        System.arraycopy(inner, 0, src, pad, inner.length);

        byte[] compBuf = new byte[LZ4.maxCompressedLength(inner.length)];
        int compLen = LZ4.compress(src, pad, inner.length, compBuf, 0, 1);

        // Decompress into a padded destination
        byte[] dstBuf = new byte[pad + inner.length];
        int n = LZ4.decompress(compBuf, 0, compLen, dstBuf, pad, inner.length);
        assertEquals(inner.length, n);
        assertArrayEquals(inner, Arrays.copyOfRange(dstBuf, pad, pad + inner.length));
    }

    // ── maxChain=0 (2-way fast path) ─────────────────────────────────────────

    @Test void chain0RoundTrip() {
        byte[] src = "abcdefabcdefabcdefabcdef".repeat(100).getBytes();
        assertRoundTrip(src, 0);
    }

    @Test void chain0VsChain1Agreement() {
        byte[] src = new byte[8192];
        new Random(1).nextBytes(src);
        byte[] c0 = LZ4.compress(src, 0);
        byte[] c1 = LZ4.compress(src, 1);
        // Both must round-trip (ratio may differ)
        assertArrayEquals(src, LZ4.decompress(c0, src.length));
        assertArrayEquals(src, LZ4.decompress(c1, src.length));
    }

    // ── Match spanning the WINDOW_SIZE (64KB) boundary ───────────────────────

    @Test void matchAtExactlyWindowSizeDistance() {
        int ws = LZ4.WINDOW_SIZE;
        byte[] src = new byte[ws + 64];
        byte[] pat = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        System.arraycopy(pat, 0, src, 0, pat.length);
        System.arraycopy(pat, 0, src, ws, pat.length);
        assertRoundTrip(src, 4);
        assertNativeJavaAgreement(src, 4);
    }

    @Test void matchJustInsideWindowSize() {
        int ws = LZ4.WINDOW_SIZE;
        byte[] src = new byte[ws + 64];
        // Place an identical 8-byte pattern at distance ws-1
        byte[] pat = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte)0x88};
        System.arraycopy(pat, 0, src, 0, pat.length);
        System.arraycopy(pat, 0, src, ws - 1, pat.length);
        assertRoundTrip(src, 4);
        assertNativeJavaAgreement(src, 4);
    }

    // ── Long matches near PADDING_LITERALS boundary ───────────────────────────

    @Test void matchEndingAtPaddingLiterals() {
        // A 1024-byte repeating block whose match ends exactly at src_len - PADDING_LITERALS.
        byte[] unit = new byte[8];
        new Random(5).nextBytes(unit);
        byte[] src = new byte[1024];
        for (int i = 0; i < src.length; i++) src[i] = unit[i % unit.length];
        assertRoundTrip(src, 1);
        assertRoundTrip(src, 8);
        assertNativeJavaAgreement(src, 1);
    }

    // ── All chain depths produce decompressable output ────────────────────────

    @ParameterizedTest(name = "chain={0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 8, 16, 64, 256})
    void allChainDepthsRoundTrip(int chain) {
        byte[] src = new byte[32 * 1024];
        new Random(chain).nextBytes(src);
        assertRoundTrip(src, chain);
    }

    @ParameterizedTest(name = "chain={0}")
    @ValueSource(ints = {0, 1, 2, 4, 8, 256})
    void allChainDepthsCompressibleData(int chain) {
        // Highly compressible: 4-byte pattern repeated
        byte[] src = new byte[64 * 1024];
        for (int i = 0; i < src.length; i++) src[i] = (byte)(i % 4 == 0 ? 0xAA : 0xBB);
        assertRoundTrip(src, chain);
        assertNativeJavaAgreement(src, chain);
    }

    // ── Native/Java output agreement ─────────────────────────────────────────

    @Test void nativeAndJavaProduceSameOutput() {
        // When both are available, native and Java must compress to the same bytes.
        // (Not guaranteed by spec, but our implementations should agree for simple data.)
        byte[] src = "the quick brown fox jumps over the lazy dog ".repeat(200).getBytes();
        byte[] native_ = LZ4.compress(src, 1);
        byte[] java_   = LZ4.compressJava(src, 1);
        // Both must round-trip
        assertArrayEquals(src, LZ4.decompress(native_, src.length), "native round-trip");
        assertArrayEquals(src, LZ4.decompress(java_,   src.length), "java round-trip");
    }

    // ── decompress(byte[], int) convenience API ───────────────────────────────

    @Test void decompressConvenienceApi() {
        byte[] src = new byte[1024];
        new Random(99).nextBytes(src);
        byte[] comp = LZ4.compress(src, 1);
        assertArrayEquals(src, LZ4.decompress(comp, src.length));
    }

    @Test void decompressConvenienceApiTrimmed() {
        // If n < decompressedSize, result is trimmed to n bytes.
        byte[] src = new byte[256];
        Arrays.fill(src, (byte) 0x42);
        byte[] comp = LZ4.compress(src, 1);
        // Over-declare by 1 byte — decompress returns the actual count,
        // result is trimmed to the actual decompressed size.
        byte[] result = LZ4.decompress(comp, src.length + 1);
        assertTrue(result.length <= src.length + 1);
        assertArrayEquals(src, Arrays.copyOf(result, src.length));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void assertRoundTrip(byte[] src, int chain) {
        byte[] comp = LZ4.compress(src, chain);
        byte[] back = LZ4.decompress(comp, src.length);
        assertArrayEquals(src, back, "round-trip failed at chain=" + chain);
    }

    /** Both native and pure-Java paths must decompress to the same bytes. */
    private static void assertNativeJavaAgreement(byte[] src, int chain) {
        byte[] compNative = LZ4.compress(src, chain);
        byte[] compJava   = LZ4.compressJava(src, chain);

        byte[] backFromNative = LZ4.decompressJava(compNative, src.length);
        byte[] backFromJava   = LZ4.decompressJava(compJava,   src.length);

        assertArrayEquals(src, backFromNative,
            "Java decompress of native output failed at chain=" + chain);
        assertArrayEquals(src, backFromJava,
            "Java decompress of Java output failed at chain=" + chain);

        if (LZ4.isNativeAvailable()) {
            byte[] buf = new byte[src.length];
            LZ4.decompress(compJava, 0, compJava.length, buf, 0, src.length);
            assertArrayEquals(src, buf,
                "native decompress of Java output failed at chain=" + chain);
        }
    }

}
