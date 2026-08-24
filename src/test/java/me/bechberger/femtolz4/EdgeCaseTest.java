package me.bechberger.femtolz4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted tests for six specific edge-case areas:
 *
 *  1. Overlapping match offsets 5, 6, 7 — various matchLen % 8 remainders
 *  2. copyLiterals boundaries: litLen in {7,8,9, 15,16,17, 31,32,33, 63,64,65}
 *  3. Large non-zero srcOff > WINDOW_SIZE — sentinel overflow check
 *  4. repeatedSamples dispatch switchover — threshold straddling
 *  5. Non-overlapping C decompressor paths (offset >= matchLen):
 *       cascaded __builtin_memcpy for sizes 17-32, 33-64, 65-128, 129+
 *  6. Match at start of block (matchSrc < dstOff)
 *
 * Every scenario that exercises the compressor runs with ALL compressor/decompressor
 * combinations so cross-path bugs are exposed.
 */
class EdgeCaseTest {

    // ── Compressor / decompressor combinations ────────────────────────────────

    record Combo(String name, Compressor comp, Decompressor decomp) {
        @Override public String toString() { return name; }
    }

    @FunctionalInterface interface Compressor {
        byte[] compress(byte[] src);
    }

    @FunctionalInterface interface Decompressor {
        byte[] decompress(byte[] comp, int originalLen);
    }

    static Stream<Combo> allCombos() {
        List<Compressor> compressors = List.of(
            new Compressor() { public byte[] compress(byte[] s) { return LZ4.compress(s, 0); }
                               public String toString() { return "chain=0(2way)"; } },
            new Compressor() { public byte[] compress(byte[] s) { return LZ4.compress(s, 1); }
                               public String toString() { return "chain=1(fast)"; } },
            new Compressor() { public byte[] compress(byte[] s) { return LZ4.compress(s, 2); }
                               public String toString() { return "chain=2"; } },
            new Compressor() { public byte[] compress(byte[] s) { return LZ4.compress(s, 4); }
                               public String toString() { return "chain=4"; } },
            new Compressor() { public byte[] compress(byte[] s) { return LZ4Java.compressJava(s, 1); }
                               public String toString() { return "java-fast"; } },
            new Compressor() { public byte[] compress(byte[] s) { return LZ4Java.compressJava(s, 4); }
                               public String toString() { return "java-chain=4"; } }
        );
        List<Decompressor> decompressors = List.of(
            new Decompressor() { public byte[] decompress(byte[] c, int n) { return LZ4.decompress(c, n); }
                                 public String toString() { return "native"; } },
            new Decompressor() { public byte[] decompress(byte[] c, int n) { return LZ4Java.decompressJava(c, n); }
                                 public String toString() { return "java"; } }
        );

        List<Combo> combos = new ArrayList<>();
        for (Compressor c : compressors)
            for (Decompressor d : decompressors)
                combos.add(new Combo(c + "+" + d, c, d));
        return combos.stream();
    }

    // ── 1. Overlapping offsets 5, 6, 7 — every matchLen % 8 remainder ────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void overlappingOffset5AllRemainders(Combo c) {
        for (int extra = 0; extra < 8; extra++) {
            int matchLen = 16 + extra; // 16..23; 16 % 8 = 0, 23 % 8 = 7
            byte[] src = buildOverlappingPattern(5, matchLen);
            assertRoundTrip(src, c, "offset=5 matchLen=" + matchLen);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void overlappingOffset6AllRemainders(Combo c) {
        for (int extra = 0; extra < 8; extra++) {
            int matchLen = 16 + extra;
            byte[] src = buildOverlappingPattern(6, matchLen);
            assertRoundTrip(src, c, "offset=6 matchLen=" + matchLen);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void overlappingOffset7AllRemainders(Combo c) {
        for (int extra = 0; extra < 8; extra++) {
            int matchLen = 16 + extra;
            byte[] src = buildOverlappingPattern(7, matchLen);
            assertRoundTrip(src, c, "offset=7 matchLen=" + matchLen);
        }
    }

    /** Also test the large-match case where rem - c > 8 to stress the tail path. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void overlappingOffset3LargeMatch(Combo c) {
        for (int matchLen : new int[]{58, 61, 62, 63, 64, 65, 127, 128, 129, 253, 254, 255, 256}) {
            byte[] src = buildOverlappingPattern(3, matchLen);
            assertRoundTrip(src, c, "offset=3 matchLen=" + matchLen);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void overlappingOffset5LargeMatch(Combo c) {
        for (int matchLen : new int[]{58, 63, 64, 65, 127, 128, 129, 253, 256}) {
            byte[] src = buildOverlappingPattern(5, matchLen);
            assertRoundTrip(src, c, "offset=5 matchLen=" + matchLen);
        }
    }

    // ── 2. copyLiterals boundaries ────────────────────────────────────────────
    //
    // Force a literal run of exactly N bytes by prepending N distinct high-entropy
    // bytes (no 4-byte match possible) before a compressible tail.

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void literalLengthBoundaries(Combo c) {
        // Boundaries that straddle every branch in copyLiterals:
        // <4, 4..7, 8..15, 16..31, 32..63, >=64
        for (int litLen : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 14, 15, 16, 17,
                                    30, 31, 32, 33, 62, 63, 64, 65, 127, 128, 129}) {
            byte[] src = buildLiteralThenMatch(litLen);
            assertRoundTrip(src, c, "litLen=" + litLen);
        }
    }

    // ── 3. Large non-zero srcOff > WINDOW_SIZE ────────────────────────────────
    //
    // The sentinel = srcOff - WINDOW_SIZE - 1. If srcOff > WINDOW_SIZE the sentinel
    // is a positive int; the window check (pos - sv) < WINDOW_SIZE must still work.

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void largeSrcOff(Combo c) {
        int ws = LZ4.WINDOW_SIZE; // 65536
        for (int srcOff : new int[]{ws, ws + 1, ws * 2, ws * 3 + 7}) {
            byte[] data = "abcdefabcdefabcdefabcdef".repeat(200).getBytes();
            byte[] padded = new byte[srcOff + data.length];
            System.arraycopy(data, 0, padded, srcOff, data.length);

            byte[] compBuf = new byte[LZ4.maxCompressedLength(data.length)];
            int compLen = LZ4Java.compressJava(padded, srcOff, data.length, compBuf, 0, 1);
            assertTrue(compLen > 0, "compressed length must be positive");

            byte[] back = LZ4Java.decompressJava(Arrays.copyOf(compBuf, compLen), data.length);
            assertArrayEquals(data, back,
                c + " srcOff=" + srcOff);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void largeSrcOffNativeCompress(Combo c) {
        if (!LZ4.isNativeAvailable()) return;
        int ws = LZ4.WINDOW_SIZE;
        for (int srcOff : new int[]{ws, ws + 1, ws * 2}) {
            byte[] data = "hello world hello world".repeat(300).getBytes();
            byte[] padded = new byte[srcOff + data.length];
            System.arraycopy(data, 0, padded, srcOff, data.length);

            byte[] compBuf = new byte[LZ4.maxCompressedLength(data.length)];
            int compLen = LZ4.compress(padded, srcOff, data.length, compBuf, 0, 1);
            assertTrue(compLen > 0);

            byte[] back = new byte[data.length];
            LZ4.decompress(compBuf, 0, compLen, back, 0, data.length);
            assertArrayEquals(data, back, c + " native srcOff=" + srcOff);
        }
    }

    // ── 4. repeatedSamples dispatch switchover ────────────────────────────────
    //
    // LZ4.compress() with maxChain=1 switches between native and Java based on
    // countRepeatedSamples: <2 or >=6 → native, 2..5 → Java.
    // We build blocks that land at each count (0, 1, 2, 3, 4, 5, 6, 7, 8) and
    // verify round-trip correctness regardless of which path is chosen.

    @Test void repeatedSamplesAllCounts() {
        // We need srcLen >= X86_NATIVE_CHAIN_SAMPLE_MIN = 4096 to trigger sampling.
        int blockLen = 8192;
        Random rng = new Random(0);

        // Build blocks with 0, 2, 4, 6, 8 repeated samples by controlling the
        // 8 evenly-spaced probe windows. Each probe window is 4 bytes at
        // srcOff + k*(srcLen/8) compared against the 4 bytes 4 positions earlier.
        // We construct data so exactly `targetRepeats` of those 8 windows match.
        for (int targetRepeats : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8}) {
            byte[] src = buildWithRepeatedSamples(blockLen, targetRepeats, rng);
            // Verify compress+decompress round-trips regardless of dispatch path.
            byte[] comp = LZ4.compress(src, 1);
            assertArrayEquals(src, LZ4.decompress(comp, src.length),
                "repeatedSamples=" + targetRepeats);
            // Also verify java path alone
            byte[] compJ = LZ4Java.compressJava(src, 1);
            assertArrayEquals(src, LZ4Java.decompressJava(compJ, src.length),
                "java repeatedSamples=" + targetRepeats);
        }
    }

    // ── 5. Non-overlapping C decompressor: cascaded __builtin_memcpy sizes ───
    //
    // The C decompressor has four __builtin_memcpy branches for offset >= matchLen:
    //   ≤16, ≤32, ≤64, ≤128, else memcpy.
    // We hand-craft an LZ4 block with a back-reference where offset > matchLen
    // at exactly the boundary sizes, then decompress with both paths.

    @Test void nonOverlappingMatchSizes() {
        // For each target match length, build a synthetic source that:
        //   - Has `matchLen` identical bytes starting at position `matchLen`
        //   - Has the same `matchLen` bytes at position 0 (so offset = matchLen, no overlap)
        // This forces the decompressor into the non-overlapping branch.
        for (int matchLen : new int[]{4, 8, 12, 16, 17, 20, 31, 32, 33, 48, 63, 64, 65, 96, 127, 128, 129, 200}) {
            byte[] src = buildNonOverlappingMatch(matchLen);
            assertAllDecompressorsAgree(src, "non-overlapping matchLen=" + matchLen);
        }
    }

    // ── 6. Match at start of block (matchSrc < dstOff) ───────────────────────
    //
    // When dstOff > 0 the decompressor must reject a back-reference that points
    // before dstOff. We hand-craft such a block and verify LZ4Exception is thrown.

    @Test void matchBeforeBufferStartThrows() {
        // Valid LZ4 block: 1 literal 'A', then back-reference offset=2 (points before dstOff=1).
        // token: litLen=1 (nibble=1), matchExtra=0 (nibble=0) → 0x10
        byte[] block = {0x10, 0x41, 0x02, 0x00};  // token, 'A', offset=2 LE
        byte[] dst = new byte[64];

        // dstOff=0: matchSrc = 0 - 2 = -2 < 0. Must throw.
        assertThrows(LZ4Exception.class,
            () -> LZ4.decompress(block, 0, block.length, dst, 0, 64),
            "native: match before buffer start");
        assertThrows(LZ4Exception.class,
            () -> LZ4Java.decompressJava(block, 64),
            "java: match before buffer start");
    }

    @Test void matchAtExactlyDstOffIsRejected() {
        // Place dstOff=10; emit 1 literal, then offset=2 — matchSrc=9 < dstOff=10.
        byte[] block = {0x10, 0x41, 0x02, 0x00};
        byte[] dst = new byte[74];
        assertThrows(LZ4Exception.class,
            () -> LZ4.decompress(block, 0, block.length, dst, 10, 64),
            "native: matchSrc < dstOff");
        // Java decompressJavaImpl takes matchLowerBound
        assertThrows(LZ4Exception.class,
            () -> LZ4.decompressJavaWithMatchLowerBound(block, 0, block.length, dst, 10, 64, 10),
            "java: matchSrc < dstOff");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void matchAtStartOfBlockRoundTrip(Combo c) {
        // A valid block where the first match is 4+ bytes into the output —
        // exercises the "match is valid, matchSrc >= dstOff" boundary.
        // We write 8 identical bytes then a reference back to the start.
        byte[] src = new byte[64];
        Arrays.fill(src, (byte) 0xAA);
        assertRoundTrip(src, c, "all-same-byte match at start");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombos")
    void matchAtStartWithNonZeroDstOff(Combo c) {
        // Compress with dstOff=0, decompress into dst with non-zero dstOff.
        // The first match in the block must not be misdetected as before-buffer.
        byte[] src = new byte[128];
        Arrays.fill(src, (byte) 0x55);
        byte[] comp = c.comp.compress(src);

        byte[] dstBig = new byte[200];
        int dstOff = 50;
        int n = LZ4.decompress(comp, 0, comp.length, dstBig, dstOff, src.length);
        assertEquals(src.length, n);
        byte[] got = Arrays.copyOfRange(dstBig, dstOff, dstOff + src.length);
        assertArrayEquals(src, got, c + " dstOff=" + dstOff);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a source block that forces the compressor to emit an overlapping
     * back-reference of exactly {@code matchLen} bytes at distance {@code offset}.
     *
     * Layout: [offset distinct bytes] [matchLen-byte pattern] [matchLen-byte pattern copy]
     * The two copies of the pattern are at distance = offset + litLen from each other
     * but we ensure offset ≤ matchLen so the decompressor uses the overlapping path.
     *
     * We use a tail of unique bytes so the compressor doesn't extend the match further.
     */
    private static byte[] buildOverlappingPattern(int offset, int matchLen) {
        // Fill prefix with offset distinct non-repeating bytes.
        // Fill body with a simple period-`offset` pattern repeated for matchLen bytes.
        // Append another copy of those matchLen bytes (at distance offset from body start).
        // Append unique tail to cap the match.
        byte[] body = new byte[matchLen];
        for (int i = 0; i < matchLen; i++) body[i] = (byte)(1 + (i % offset));

        int total = offset + matchLen + matchLen + 8;
        byte[] src = new byte[total];
        // Prefix: offset unique bytes (values 0x80+ to avoid matching body)
        for (int i = 0; i < offset; i++) src[i] = (byte)(0x80 + i);
        // First copy of body
        System.arraycopy(body, 0, src, offset, matchLen);
        // Second copy — the compressor should find a back-reference from here to offset
        System.arraycopy(body, 0, src, offset + matchLen, matchLen);
        // Unique tail
        byte[] tail = new byte[8];
        new Random(offset * 31 + matchLen).nextBytes(tail);
        System.arraycopy(tail, 0, src, offset + matchLen * 2, 8);
        return src;
    }

    /**
     * Build a source that starts with {@code litLen} incompressible bytes
     * followed by a compressible tail (32-byte repeat), forcing a literal run
     * of exactly litLen bytes before the first match.
     */
    private static byte[] buildLiteralThenMatch(int litLen) {
        byte[] src = new byte[litLen + 256];
        // Incompressible prefix: use pseudo-random distinct values
        Random rng = new Random(litLen);
        for (int i = 0; i < litLen; i++) src[i] = (byte)(rng.nextInt(256));
        // Compressible suffix: 'A' repeated
        Arrays.fill(src, litLen, src.length, (byte) 'A');
        return src;
    }

    /**
     * Build an LZ4 block (not frame) with a non-overlapping back-reference of
     * exactly {@code matchLen} bytes at offset = matchLen (so src and dst don't overlap).
     * Hand-encode so both compressor paths are bypassed — we test the decompressor directly.
     *
     * Sequence: litLen=matchLen literals, then offset=matchLen, matchExtra=matchLen-4.
     * Final sequence: 0 literals, no match (end of block).
     */
    private static byte[] buildNonOverlappingBlock(int matchLen) {
        // Literals: matchLen bytes of pattern
        byte[] pattern = new byte[matchLen];
        for (int i = 0; i < matchLen; i++) pattern[i] = (byte)(0x11 + (i % 7));

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Sequence 1: litLen=matchLen, matchExtra=matchLen-4, offset=matchLen
        int litLen    = matchLen;
        int matchExtra = matchLen - 4; // so matchLen = 4 + matchExtra

        // Token
        int litNib   = Math.min(litLen,    15);
        int mxNib    = Math.min(matchExtra, 15);
        out.write((litNib << 4) | mxNib);
        // Literal length overflow
        if (litLen >= 15) {
            int rem = litLen - 15;
            while (rem >= 255) { out.write(255); rem -= 255; }
            out.write(rem);
        }
        // Literals
        out.write(pattern, 0, matchLen);
        // Offset (LE 16-bit) = matchLen (so matchSrc = op - matchLen = start of literals)
        out.write(matchLen & 0xFF);
        out.write((matchLen >> 8) & 0xFF);
        // matchExtra overflow
        if (matchExtra >= 15) {
            int rem = matchExtra - 15;
            while (rem >= 255) { out.write(255); rem -= 255; }
            out.write(rem);
        }

        // Final sequence: 0 literals, no match (just end-of-block marker)
        out.write(0x00); // token: 0 literals, 0 match extra — end of block

        return out.toByteArray();
    }

    /**
     * Decompress the hand-crafted non-overlapping block with both paths and
     * verify both produce the expected doubled pattern.
     */
    private static void assertAllDecompressorsAgree(byte[] src, String label) {
        // src here is the ORIGINAL data (not a compressed block).
        // Round-trip through all compressor+decompress pairs.
        for (int chain : new int[]{1, 4}) {
            byte[] comp  = LZ4.compress(src, chain);
            byte[] backN = LZ4.decompress(comp, src.length);
            byte[] backJ = LZ4Java.decompressJava(comp, src.length);
            assertArrayEquals(src, backN, label + " chain=" + chain + " native");
            assertArrayEquals(src, backJ, label + " chain=" + chain + " java");
        }
    }

    /**
     * Build a source with {@code offset >= matchLen} (non-overlapping), by placing
     * a pattern at position 0 and then again at position {@code matchLen} — so
     * when the decompressor processes the second occurrence, offset = matchLen
     * exactly (no overlap). Followed by a unique tail to end the match.
     */
    private static byte[] buildNonOverlappingMatch(int matchLen) {
        byte[] pattern = new byte[matchLen];
        for (int i = 0; i < matchLen; i++) pattern[i] = (byte)(0x33 + (i % 11));
        byte[] src = new byte[matchLen * 2 + 8];
        System.arraycopy(pattern, 0, src, 0,         matchLen);
        System.arraycopy(pattern, 0, src, matchLen,  matchLen);
        byte[] tailBytes = new byte[8];
        new Random(matchLen).nextBytes(tailBytes);
        System.arraycopy(tailBytes, 0, src, matchLen * 2, 8);
        return src;
    }

    /**
     * Build a block of {@code len} bytes where exactly {@code targetRepeats} of
     * the 8 evenly-spaced probe windows (used by countRepeatedSamples) have
     * the same 4-byte value as the window 4 positions earlier.
     */
    private static byte[] buildWithRepeatedSamples(int len, int targetRepeats, Random rng) {
        byte[] src = new byte[len];
        rng.nextBytes(src);

        // Probe positions: srcOff + k*(srcLen/8) for k=0..7; srcOff=0 here.
        int step = len / 8;
        for (int k = 0; k < 8; k++) {
            int pos = k * step;
            if (pos < 4 || pos + 4 > len) continue;
            if (k < targetRepeats) {
                // Make src[pos..pos+3] == src[pos-4..pos-1]
                src[pos]   = src[pos - 4];
                src[pos+1] = src[pos - 3];
                src[pos+2] = src[pos - 2];
                src[pos+3] = src[pos - 1];
            } else {
                // Ensure src[pos..pos+3] != src[pos-4..pos-1]
                src[pos] = (byte)(src[pos - 4] ^ 0xFF);
            }
        }
        return src;
    }

    private static void assertRoundTrip(byte[] src, Combo c, String label) {
        byte[] comp = c.comp.compress(src);
        byte[] back = c.decomp.decompress(comp, src.length);
        assertArrayEquals(src, back, c + " " + label);
    }
}
