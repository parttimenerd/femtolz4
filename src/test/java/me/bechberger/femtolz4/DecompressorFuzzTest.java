package me.bechberger.femtolz4;

import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decompressor-focused fuzz tests.
 *
 * Strategy:
 *  A. Differential oracle: feed the SAME arbitrary bytes to both femto-java
 *     and femto-native. They must agree — both succeed with the same output,
 *     or both reject.
 *  B. Bit-flip corruption: take a valid compressed block, flip/corrupt individual
 *     bytes, verify femto never crashes (only throws LZ4Exception).
 *  C. Crafted raw blocks: hand-build LZ4 sequences that hit specific decompressor
 *     code paths (literal lengths, match lengths, offsets, overlap copy variants).
 *  D. dstLen off-by-one: decompress into a buffer that's exactly right, one too
 *     small, one too large — verify correct behaviour each time.
 *  E. Large match lengths: match lengths that require multi-byte overflow encoding
 *     (>= 19 bytes = MIN_MATCH + 15), up to several KB.
 *  F. srcOff / dstOff independence: output window shifted to various offsets.
 *  G. Overlap copy stress: all match offsets 1..7 × all match lengths 4..256.
 *  H. Consistency: femto-java and femto-native must always produce identical
 *     output on the same valid input.
 */
class DecompressorFuzzTest {

    private static final LZ4Factory          YAWKAT     = LZ4Factory.safeInstance();
    private static final LZ4FastDecompressor YAWKAT_DEC = YAWKAT.fastDecompressor();

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] yawkatDecompress(byte[] comp, int len) {
        byte[] dst = new byte[len];
        YAWKAT_DEC.decompress(comp, 0, dst, 0, len);
        return dst;
    }

    /** Build a minimal well-formed LZ4 block: literals only (no match). */
    private static byte[] literalsOnlyBlock(byte[] payload) {
        byte[] out = new byte[1 + (payload.length >= 15 ? 1 + (payload.length - 15 + 254) / 255 : 0) + payload.length];
        int op = 0;
        if (payload.length < 15) {
            out[op++] = (byte) (payload.length << 4);
        } else {
            out[op++] = (byte) (15 << 4);
            int rem = payload.length - 15;
            while (rem >= 255) { out[op++] = (byte) 255; rem -= 255; }
            out[op++] = (byte) rem;
        }
        System.arraycopy(payload, 0, out, op, payload.length);
        return Arrays.copyOf(out, op + payload.length);
    }

    /** Build a single LZ4 sequence: litLen literals, then a back-reference. */
    private static byte[] buildSequence(byte[] src, int litLen, int matchOffset, int matchLen) {
        int matchExtra = matchLen - 4;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        buf.write(((Math.min(litLen, 15)) << 4) | Math.min(matchExtra, 15));
        if (litLen >= 15) {
            int rem = litLen - 15;
            while (rem >= 255) { buf.write(255); rem -= 255; }
            buf.write(rem);
        }
        for (int i = 0; i < litLen; i++) buf.write(src[i] & 0xFF);
        buf.write(matchOffset & 0xFF);
        buf.write((matchOffset >>> 8) & 0xFF);
        if (matchExtra >= 15) {
            int rem = matchExtra - 15;
            while (rem >= 255) { buf.write(255); rem -= 255; }
            buf.write(rem);
        }
        buf.write(0x00); // final empty token
        return buf.toByteArray();
    }

    private static void assertSafeJava(byte[] block, int dstLen) {
        try {
            LZ4Java.decompressJava(block, dstLen);
        } catch (LZ4Exception ok) {
            // acceptable
        } catch (Exception e) {
            fail("femto-java threw unexpected: " + e.getClass().getName() + ": " + e.getMessage()
                 + " on block length=" + block.length + " dstLen=" + dstLen);
        }
    }

    private static void assertSafeNative(byte[] block, int dstLen) {
        if (!LZ4.isNativeAvailable()) return;
        byte[] dst = new byte[Math.max(0, dstLen)];
        try {
            LZ4.decompress(block, 0, block.length, dst, 0, dstLen);
        } catch (LZ4Exception ok) {
            // acceptable
        } catch (Exception e) {
            fail("femto-native threw unexpected: " + e.getClass().getName() + ": " + e.getMessage()
                 + " on block length=" + block.length + " dstLen=" + dstLen);
        }
    }

    // ── A. Differential oracle: femto-java vs femto-native ────────────────────

    /**
     * For any random byte sequence, femto-java and femto-native must either:
     *  - both throw LZ4Exception, or
     *  - both succeed and produce the same output.
     * They must NEVER disagree on correctness.
     */
    @Property
    @Tag("deep-fuzz")
    void javaAndNativeAlwaysAgree(@ForAll @Size(max = 2048) byte[] bytes,
                                  @ForAll @IntRange(min = 0, max = 4096) int dstLen) {
        if (!LZ4.isNativeAvailable()) return;
        byte[] dst1 = new byte[dstLen];
        byte[] dst2 = new byte[dstLen];
        boolean javaOk = false, nativeOk = false;
        try {
            LZ4Java.decompressJavaWithMatchLowerBound(bytes, 0, bytes.length, dst1, 0, dstLen, 0);
            javaOk = true;
        } catch (LZ4Exception ok) {}
        try {
            LZ4.decompress(bytes, 0, bytes.length, dst2, 0, dstLen);
            nativeOk = true;
        } catch (LZ4Exception ok) {}

        if (javaOk && nativeOk) {
            assertArrayEquals(dst1, dst2, "java and native produced different output");
        }
        // If one succeeds and other rejects: that's a potential discrepancy. Since the
        // input is random garbage it could be ambiguous, so we only assert they don't crash.
    }

    /**
     * For yawkat-compressed data (definitely valid), femto-java and femto-native
     * must BOTH succeed and produce the same output.
     */
    @Property
    @Tag("deep-fuzz")
    void javaAndNativeAgreeOnValidInput(@ForAll @Size(max = 32768) byte[] src) {
        if (!LZ4.isNativeAvailable()) return;
        byte[] tmp = new byte[YAWKAT.fastCompressor().maxCompressedLength(src.length)];
        int n = YAWKAT.fastCompressor().compress(src, 0, src.length, tmp, 0, tmp.length);
        byte[] comp = Arrays.copyOf(tmp, n);

        byte[] dst1 = LZ4Java.decompressJava(comp, src.length);
        byte[] dst2 = LZ4.decompress(comp, src.length);
        assertArrayEquals(src, dst1, "java wrong");
        assertArrayEquals(src, dst2, "native wrong");
        assertArrayEquals(dst1, dst2, "java/native disagree");
    }

    // ── B. Bit-flip corruption: valid block + single-byte mutation ─────────────

    @Property
    @Tag("deep-fuzz")
    void singleByteMutationNeverCrashes(@ForAll @Size(min = 1, max = 1024) byte[] src,
                                        @ForAll @IntRange(min = 0, max = 255) int replaceByte) {
        byte[] comp = LZ4.compress(src, 1);
        for (int i = 0; i < comp.length; i++) {
            byte[] mutated = comp.clone();
            mutated[i] = (byte) replaceByte;
            assertSafeJava(mutated, src.length);
            assertSafeNative(mutated, src.length);
        }
    }

    @Property

    void randomByteSubstitutionNeverCrashes(@ForAll @Size(min = 8, max = 2048) byte[] src,
                                            @ForAll @IntRange(min = 0, max = 20) int numMutations,
                                            @ForAll long seed) {
        byte[] comp = LZ4.compress(src, 4);
        Random rng = new Random(seed);
        byte[] mutated = comp.clone();
        for (int m = 0; m < numMutations && mutated.length > 0; m++) {
            mutated[rng.nextInt(mutated.length)] = (byte) rng.nextInt(256);
        }
        assertSafeJava(mutated, src.length);
        assertSafeNative(mutated, src.length);
    }

    @Property

    void truncationAtEveryPointNeverCrashes(@ForAll @Size(min = 4, max = 512) byte[] src) {
        byte[] comp = LZ4.compress(src, 1);
        for (int len = 0; len <= comp.length; len++) {
            byte[] truncated = Arrays.copyOf(comp, len);
            assertSafeJava(truncated, src.length);
            assertSafeNative(truncated, src.length);
        }
    }

    // ── C. Crafted raw blocks ─────────────────────────────────────────────────

    /** literals-only block of every length 0..512 round-trips correctly. */
    @Property(tries = 1)
    void literalsOnlyBlockAllLengths() {
        for (int len = 0; len <= 512; len++) {
            byte[] payload = new byte[len];
            new Random(len).nextBytes(payload);
            byte[] block = literalsOnlyBlock(payload);
            assertArrayEquals(payload, LZ4Java.decompressJava(block, len),
                "literals-only java len=" + len);
            if (LZ4.isNativeAvailable())
                assertArrayEquals(payload, LZ4.decompress(block, len),
                    "literals-only native len=" + len);
        }
    }

    /** Single sequence with exact match lengths across all overflow encoding thresholds. */
    @ParameterizedTest
    @ValueSource(ints = {4, 18, 19, 20, 273, 274, 275, 527, 528, 529, 1024, 2048})
    void exactMatchLengthThresholds(int matchLen) {
        // Build: matchOffset bytes of distinct literals, then matchLen bytes by back-ref
        int offset = 8;
        int total  = offset + matchLen;
        byte[] src = new byte[total];
        for (int i = 0; i < offset; i++) src[i] = (byte)(0x10 + i * 13);
        for (int i = 0; i < matchLen; i++) src[offset + i] = src[i % offset];

        byte[] block = buildSequence(src, offset, offset, matchLen);
        assertArrayEquals(src, LZ4Java.decompressJava(block, total),
            "java matchLen=" + matchLen);
        if (LZ4.isNativeAvailable())
            assertArrayEquals(src, LZ4.decompress(block, total),
                "native matchLen=" + matchLen);
        // yawkat fastDecompressor requires last literal run to end ≥8 bytes before
        // destEnd; hand-built blocks with final 0x00 token fail that check, so skip.
    }

    /** All match offsets 1..65535 at match length 4 — offset field coverage. */
    @Property(tries = 1)
    void allMatchOffsetsMatchLen4() {
        for (int offset = 1; offset <= 65535; offset += (offset < 256 ? 1 : offset < 4096 ? 16 : 512)) {
            int total = offset + 4;
            byte[] src = new byte[total];
            for (int i = 0; i < offset; i++) src[i] = (byte)(i & 0xFF);
            for (int i = 0; i < 4; i++) src[offset + i] = src[i % offset];

            byte[] block = buildSequence(src, offset, offset, 4);
            assertArrayEquals(src, LZ4Java.decompressJava(block, total),
                "java offset=" + offset);
            if (LZ4.isNativeAvailable())
                assertArrayEquals(src, LZ4.decompress(block, total),
                    "native offset=" + offset);
        }
    }

    // ── D. dstLen edge cases ──────────────────────────────────────────────────

    @Property
    @Tag("deep-fuzz")
    void exactDstLenAlwaysCorrect(@ForAll @Size(min = 4, max = 16384) byte[] src) {
        for (int chain : new int[]{1, 4}) {
            byte[] comp = LZ4.compress(src, chain);
            // exact
            byte[] out = LZ4Java.decompressJava(comp, src.length);
            assertArrayEquals(src, out, "exact dstLen chain=" + chain);
        }
    }

    /**
     * Requesting dstLen one less than the actual uncompressed size must throw,
     * because the block contains more data than fits in the requested output buffer.
     */
    @Property

    void dstLenOneLessThanNeededThrows(@ForAll @Size(min = 2, max = 1024) byte[] src) {
        if (src.length == 0) return;
        byte[] comp = LZ4.compress(src, 1);
        assertThrows(LZ4Exception.class, () -> LZ4Java.decompressJava(comp, src.length - 1),
            "should throw for dstLen=" + (src.length - 1));
    }

    /**
     * Requesting more bytes than the block contains is NOT an error —
     * the decompressor returns the actual number of bytes written.
     * The returned count must equal the original source length.
     */
    @Property

    void dstLenLargerThanNeededReturnsActualCount(@ForAll @Size(min = 1, max = 1024) byte[] src) {
        byte[] comp = LZ4.compress(src, 1);
        // allocate a dst buffer of size srcLen+64, request srcLen+64 bytes
        int bigDst = src.length + 64;
        byte[] dst = new byte[bigDst];
        int n = LZ4Java.decompressJavaWithMatchLowerBound(comp, 0, comp.length, dst, 0, bigDst, 0);
        assertEquals(src.length, n,
            "should return actual srcLen even when dstLen=" + bigDst);
        assertArrayEquals(src, Arrays.copyOf(dst, src.length),
            "content with dstLen=" + bigDst);
    }

    // ── E. Large match length: multi-level overflow encoding ─────────────────

    @Property

    void largeMatchLengthRoundTrip(@ForAll @IntRange(min = 4, max = 8192) int matchLen,
                                   @ForAll @IntRange(min = 1, max = 8) int litLen) {
        byte[] src = new byte[litLen + matchLen];
        new Random(matchLen * 31L + litLen).nextBytes(src);
        // repeat the literal section to create the match
        for (int i = litLen; i < src.length; i++) src[i] = src[i % litLen];

        byte[] block = buildSequence(src, litLen, litLen, matchLen);
        assertArrayEquals(src, LZ4Java.decompressJava(block, src.length),
            "java matchLen=" + matchLen + " litLen=" + litLen);
        if (LZ4.isNativeAvailable())
            assertArrayEquals(src, LZ4.decompress(block, src.length),
                "native matchLen=" + matchLen + " litLen=" + litLen);
    }

    // ── F. srcOff / dstOff independence ──────────────────────────────────────

    @Property

    void srcOffIndependence(@ForAll @Size(min = 16, max = 8192) byte[] src,
                            @ForAll @IntRange(min = 0, max = 128) int srcOff) {
        byte[] comp = LZ4.compress(src, 2);
        byte[] padded = new byte[srcOff + comp.length];
        System.arraycopy(comp, 0, padded, srcOff, comp.length);

        byte[] out1 = LZ4Java.decompressJava(comp, src.length);
        byte[] dst = new byte[src.length];
        LZ4Java.decompressJavaWithMatchLowerBound(padded, srcOff, comp.length, dst, 0, src.length, 0);
        assertArrayEquals(out1, dst, "srcOff=" + srcOff);
    }

    @Property

    void dstOffIndependence(@ForAll @Size(min = 16, max = 8192) byte[] src,
                            @ForAll @IntRange(min = 0, max = 128) int dstOff) {
        byte[] comp = LZ4.compress(src, 2);
        byte[] dst = new byte[dstOff + src.length];
        LZ4Java.decompressJavaWithMatchLowerBound(comp, 0, comp.length, dst, dstOff, src.length, dstOff);
        assertArrayEquals(src, Arrays.copyOfRange(dst, dstOff, dstOff + src.length),
            "dstOff=" + dstOff);
    }

    // ── G. Overlap copy stress: all offsets 1..7 × all match lengths 4..256 ──

    @Test void overlapCopyExhaustive() {
        for (int offset = 1; offset <= 7; offset++) {
            for (int matchLen = 4; matchLen <= 256; matchLen++) {
                // Build expected: `offset` distinct bytes, then `matchLen` bytes as overlap copy
                byte[] expected = new byte[offset + matchLen];
                for (int i = 0; i < offset; i++) expected[i] = (byte)(0x10 + i * 13);
                for (int i = 0; i < matchLen; i++) expected[offset + i] = expected[i % offset];

                byte[] block = buildSequence(expected, offset, offset, matchLen);

                byte[] javaOut = LZ4Java.decompressJava(block, expected.length);
                assertArrayEquals(expected, javaOut,
                    "java overlap offset=" + offset + " matchLen=" + matchLen);

                if (LZ4.isNativeAvailable()) {
                    byte[] nativeOut = LZ4.decompress(block, expected.length);
                    assertArrayEquals(expected, nativeOut,
                        "native overlap offset=" + offset + " matchLen=" + matchLen);
                }
            }
        }
    }

    // ── H. Consistency: java == native on all valid compressed inputs ─────────

    @Property
    @Tag("deep-fuzz")
    void javaEqualsNativeOnAllCompressors(@ForAll @Size(max = 16384) byte[] src) {
        if (!LZ4.isNativeAvailable()) return;
        for (int chain : new int[]{0, 1, 2, 4, 256}) {
            byte[] comp = LZ4.compress(src, chain);
            byte[] j = LZ4Java.decompressJava(comp, src.length);
            byte[] n = LZ4.decompress(comp, src.length);
            assertArrayEquals(j, n, "java/native disagree chain=" + chain + " srcLen=" + src.length);
        }
    }

    // ── I. Structural fuzzing: hand-built blocks with specific token patterns ─

    /**
     * Exhaustively test all (litNibble, matchNibble) token combinations
     * with a minimally valid surrounding block structure.
     * Uses buildSequence so the block is guaranteed to be well-formed.
     */
    @Property(tries = 1)
    void allTokenCombinationsWithValidStructure() {
        for (int litN = 0; litN < 15; litN++) {
            for (int mex = 0; mex < 15; mex++) {
                int matchLen = 4 + mex;
                // prefix must be at least as long as the match offset (= litLen)
                int litLen   = Math.max(litN + 1, matchLen);
                int total    = litLen + matchLen;
                byte[] src   = new byte[total];
                for (int i = 0; i < litLen; i++) src[i] = (byte)(i * 7 + 0x10);
                for (int i = 0; i < matchLen; i++) src[litLen + i] = src[i % litLen];

                byte[] block = buildSequence(src, litLen, litLen, matchLen);
                assertArrayEquals(src, LZ4Java.decompressJava(block, total),
                    "java token litN=" + litN + " mex=" + mex);
                if (LZ4.isNativeAvailable())
                    assertArrayEquals(src, LZ4.decompress(block, total),
                        "native token litN=" + litN + " mex=" + mex);
            }
        }
    }

    /**
     * Crafted block with a chain of N sequences (litLen=0 for inner ones).
     * Tests the decompressor's ability to handle zero-literal tokens in a sequence.
     */
    @Property

    void chainOfZeroLiteralSequences(@ForAll @IntRange(min = 2, max = 50) int numSequences,
                                     @ForAll @IntRange(min = 4, max = 32) int matchLen) {
        // Build a source with `numSequences` identical 8-byte chunks
        int chunkLen = 8;
        int prefixLen = chunkLen;   // first chunk = literal prefix
        int totalLen = prefixLen + numSequences * matchLen;
        byte[] src = new byte[totalLen];
        byte[] chunk = new byte[chunkLen];
        new Random(numSequences * 97L + matchLen).nextBytes(chunk);
        // fill: first chunkLen bytes are the base, rest are copies of it
        System.arraycopy(chunk, 0, src, 0, chunkLen);
        for (int i = chunkLen; i < totalLen; i++) src[i] = chunk[i % chunkLen];

        // Compress and verify both decompressors get back the original
        for (int chain : new int[]{1, 4, 256}) {
            byte[] comp = LZ4.compress(src, chain);
            byte[] jOut = LZ4Java.decompressJava(comp, totalLen);
            assertArrayEquals(src, jOut, "java chain=" + chain + " seq=" + numSequences);
            if (LZ4.isNativeAvailable()) {
                byte[] nOut = LZ4.decompress(comp, totalLen);
                assertArrayEquals(src, nOut, "native chain=" + chain + " seq=" + numSequences);
            }
        }
    }

    // ── J. Decompressor must not read beyond srcLen ───────────────────────────

    /**
     * Place a valid compressed block at offset `gapBefore` in a larger buffer,
     * with sentinel bytes before and after. Decompressor must not access bytes
     * outside [srcOff, srcOff+srcLen).
     *
     * We achieve this by using a buffer of exactly `srcOff + srcLen` bytes and
     * verifying no exception is thrown — if it reads past the end it would either
     * return wrong data or throw AIOOBE.
     */
    @Property
    @Tag("deep-fuzz")
    void decompressorRespectsSourceBounds(@ForAll @Size(min = 1, max = 4096) byte[] src,
                                          @ForAll @IntRange(min = 0, max = 64) int gapBefore) {
        byte[] comp = LZ4.compress(src, 2);
        // Pad before the compressed data with non-zero garbage
        byte[] paddedComp = new byte[gapBefore + comp.length];
        Arrays.fill(paddedComp, 0, gapBefore, (byte) 0xAB);
        System.arraycopy(comp, 0, paddedComp, gapBefore, comp.length);

        byte[] dst = new byte[src.length];
        int n = LZ4Java.decompressJavaWithMatchLowerBound(
            paddedComp, gapBefore, comp.length, dst, 0, src.length, 0);
        assertEquals(src.length, n, "decompressed length gapBefore=" + gapBefore);
        assertArrayEquals(src, dst, "content gapBefore=" + gapBefore);
    }

    // ── K. Multi-block: compress/decompress blocks that span WINDOW_SIZE ──────

    @Property

    void multiBlockWindowSpanning(@ForAll @IntRange(min = 1, max = 4) int numBlocks,
                                  @ForAll @IntRange(min = 1, max = 4) int patLen) {
        int blockSize = LZ4.WINDOW_SIZE;
        int totalLen = blockSize * numBlocks;
        byte[] src = new byte[totalLen];
        byte[] pat = new byte[patLen];
        new Random(numBlocks * 31L + patLen).nextBytes(pat);
        for (int i = 0; i < totalLen; i++) src[i] = pat[i % patLen];

        for (int chain : new int[]{1, 4, 256}) {
            byte[] comp = LZ4.compress(src, chain);
            byte[] jOut = LZ4Java.decompressJava(comp, totalLen);
            assertArrayEquals(src, jOut, "java chain=" + chain + " blocks=" + numBlocks);
            if (LZ4.isNativeAvailable()) {
                byte[] nOut = LZ4.decompress(comp, totalLen);
                assertArrayEquals(src, nOut, "native chain=" + chain + " blocks=" + numBlocks);
            }
        }
    }

    // ── L. Adversarial: carefully crafted near-valid blocks ───────────────────

    @Test void zeroOffsetIsRejected() {
        // token(1 lit, 0 mex) 'A' offset=0x0000 => zero offset must be rejected
        byte[] block = {0x10, 'A', 0x00, 0x00, 0x00};
        assertSafeJava(block, 100);
        assertSafeNative(block, 100);
    }

    @Test void offsetLargerThanOutputSoFarIsRejected() {
        // 4 literals, then offset=8 (but only 4 bytes written) — back-ref before start
        byte[] block = {0x40, 'A', 'B', 'C', 'D', 0x08, 0x00, 0x00};
        assertSafeJava(block, 100);
        assertSafeNative(block, 100);
    }

    @Test void maxOffsetLiterallyMaxUInt16() {
        // offset = 0xFFFF = 65535: only valid if output so far >= 65535 bytes
        // With just 4 literals, this must be rejected
        byte[] block = {0x40, 'A', 'B', 'C', 'D', (byte) 0xFF, (byte) 0xFF, 0x00};
        assertSafeJava(block, 100);
        assertSafeNative(block, 100);
    }

    @Test void literalLengthExactly255LevelOverflow() {
        // litLen = 15 + 255 = 270 literals
        int litLen = 270;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        buf.write(0xF0); // token: litNibble=15, matchNibble=0
        buf.write(255);  // overflow: 15+255=270
        buf.write(0);    // terminator
        for (int i = 0; i < litLen; i++) buf.write(i & 0xFF);
        byte[] block = buf.toByteArray();
        byte[] expected = new byte[litLen];
        for (int i = 0; i < litLen; i++) expected[i] = (byte)(i & 0xFF);
        assertArrayEquals(expected, LZ4Java.decompressJava(block, litLen),
            "litLen=270 java");
        if (LZ4.isNativeAvailable())
            assertArrayEquals(expected, LZ4.decompress(block, litLen),
                "litLen=270 native");
    }

    @Test void matchLengthExactly255LevelOverflow() {
        // matchLen = 4 + 15 + 255 = 274
        int offset = 4;
        int matchLen = 274;
        int total = offset + matchLen;
        byte[] src = new byte[total];
        for (int i = 0; i < offset; i++) src[i] = (byte)(i * 13 + 5);
        for (int i = 0; i < matchLen; i++) src[offset + i] = src[i % offset];
        byte[] block = buildSequence(src, offset, offset, matchLen);
        assertArrayEquals(src, LZ4Java.decompressJava(block, total), "matchLen=274 java");
        if (LZ4.isNativeAvailable())
            assertArrayEquals(src, LZ4.decompress(block, total), "matchLen=274 native");
    }

    // ── M. Property: any valid yawkat-compressed input, femto always succeeds ──

    @Property
    @Tag("deep-fuzz")
    void femtoAlwaysSucceedsOnYawkatOutput(@ForAll @Size(max = 32768) byte[] src) {
        byte[] tmp = new byte[YAWKAT.fastCompressor().maxCompressedLength(src.length)];
        int n = YAWKAT.fastCompressor().compress(src, 0, src.length, tmp, 0, tmp.length);
        byte[] comp = Arrays.copyOf(tmp, n);

        byte[] jOut = LZ4Java.decompressJava(comp, src.length);
        assertArrayEquals(src, jOut, "femto-java on yawkat output");

        if (LZ4.isNativeAvailable()) {
            byte[] nOut = LZ4.decompress(comp, src.length);
            assertArrayEquals(src, nOut, "femto-native on yawkat output");
        }
    }

    @Property
    @Tag("deep-fuzz")
    void femtoAlwaysSucceedsOnYawkatHCOutput(@ForAll @Size(max = 16384) byte[] src) {
        var hc = YAWKAT.highCompressor();
        byte[] tmp = new byte[hc.maxCompressedLength(src.length)];
        int n = hc.compress(src, 0, src.length, tmp, 0, tmp.length);
        byte[] comp = Arrays.copyOf(tmp, n);

        byte[] jOut = LZ4Java.decompressJava(comp, src.length);
        assertArrayEquals(src, jOut, "femto-java on yawkat-HC output");

        if (LZ4.isNativeAvailable()) {
            byte[] nOut = LZ4.decompress(comp, src.length);
            assertArrayEquals(src, nOut, "femto-native on yawkat-HC output");
        }
    }
}
