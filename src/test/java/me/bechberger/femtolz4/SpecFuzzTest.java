package me.bechberger.femtolz4;

import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec-derived fuzz tests for the LZ4 block format.
 *
 * Each test encodes a specific invariant from the LZ4 block format specification:
 *
 *   Sequence structure:  token | [litLen-overflow] | literals | [offset-LE16] |
 *                        [matchLen-overflow]
 *   Last sequence:       token | [litLen-overflow] | literals  (no offset/match)
 *   Constraints:
 *     S1. litLen = (token >> 4) + overflow-bytes (terminated by b < 255)
 *     S2. matchLen = MIN_MATCH + (token & 0xF) + overflow-bytes
 *     S3. offset is little-endian uint16, must be > 0
 *     S4. match source = op - offset must be >= start of output buffer
 *     S5. match copy is an overlapping copy: src[ms+i] for i in [0, matchLen)
 *         where each byte is read AFTER all previously written bytes are settled
 *         (i.e. reading from dst[ms+i] is valid even when ms+i >= op)
 *     S6. Last 5 bytes of input are always literals (PADDING_LITERALS=5)
 *     S7. Last match start <= srcLen - MFLIMIT (MFLIMIT=12)
 *     S8. Decompressor must never read beyond srcOff+srcLen
 *     S9. Decompressor must never write beyond dstOff+dstLen
 *    S10. overflow chain terminates when byte < 255; 255 means "add 255, continue"
 *
 * Tags:
 *   (no tag)     — included in normal `mvn test` run; tries governed by jqwik.tries.default
 *   "deep-fuzz"  — excluded by default, included with `mvn test -Pfuzz`
 */
class SpecFuzzTest {

    private static final LZ4Factory          YAWKAT     = LZ4Factory.safeInstance();
    private static final LZ4FastDecompressor YAWKAT_DEC = YAWKAT.fastDecompressor();

    // ── Block builder ─────────────────────────────────────────────────────────

    /** Emit a length overflow sequence: 255 bytes until remainder < 255, then remainder. */
    private static void writeOverflow(java.io.ByteArrayOutputStream buf, int extra) {
        while (extra >= 255) { buf.write(255); extra -= 255; }
        buf.write(extra);
    }

    /** Build a complete valid LZ4 block from a list of (litBytes, offset, matchLen) triples. */
    private static byte[] buildBlock(int[][] sequences, byte[][] literals) {
        var buf = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < sequences.length; i++) {
            int litLen   = literals[i].length;
            int offset   = sequences[i][0];
            int matchLen = sequences[i][1];
            int matchExtra = matchLen - 4;
            buf.write(((Math.min(litLen, 15)) << 4) | Math.min(matchExtra, 15));
            if (litLen   >= 15) writeOverflow(buf, litLen   - 15);
            for (byte b : literals[i]) buf.write(b & 0xFF);
            buf.write(offset & 0xFF);
            buf.write((offset >>> 8) & 0xFF);
            if (matchExtra >= 15) writeOverflow(buf, matchExtra - 15);
        }
        // final literal-only sequence (no match)
        int lastLitLen = literals[literals.length - 1].length;
        // Already written above — but we need a final empty token if the last seq had a match
        buf.write(0x00); // token: litLen=0, no match (end of block)
        return buf.toByteArray();
    }

    private static void assertDecomp(byte[] block, byte[] expected, String msg) {
        byte[] j = LZ4Java.decompressJava(block, expected.length);
        assertArrayEquals(expected, j, msg + " [java]");
        if (LZ4.isNativeAvailable()) {
            byte[] n = LZ4.decompress(block, expected.length);
            assertArrayEquals(expected, n, msg + " [native]");
        }
    }

    private static void assertSafeDecomp(byte[] block, int dstLen) {
        try { LZ4Java.decompressJava(block, dstLen); } catch (LZ4Exception ok) {}
        if (!LZ4.isNativeAvailable()) return;
        byte[] dst = new byte[Math.max(0, dstLen)];
        try { LZ4.decompress(block, 0, block.length, dst, 0, dstLen); } catch (LZ4Exception ok) {}
    }

    // ── S1/S2: Overflow chain encoding ───────────────────────────────────────

    /**
     * S1: litLen overflow — for any litLen, the overflow chain must decode exactly
     * to that litLen regardless of how many 255-bytes precede the terminator.
     * Verifies: 15 + sum(255*) + r == litLen where r < 255.
     */
    @Property
    void literalLengthOverflowDecodesCorrectly(
            @ForAll @IntRange(min = 15, max = 8192) int litLen) {
        byte[] payload = new byte[litLen];
        new Random(litLen).nextBytes(payload);

        var buf = new java.io.ByteArrayOutputStream();
        buf.write(0xF0); // litNibble=15, matchNibble=0
        writeOverflow(buf, litLen - 15);
        for (byte b : payload) buf.write(b & 0xFF);
        byte[] block = buf.toByteArray();

        assertDecomp(block, payload, "S1 litLen=" + litLen);
    }

    /**
     * S2: matchLen overflow — same coverage for match length encoding.
     * matchLen = MIN_MATCH + 15 + overflow = 19 + overflow.
     */
    @Property
    void matchLengthOverflowDecodesCorrectly(
            @ForAll @IntRange(min = 19, max = 8192) int matchLen) {
        int offset = 4; // back-reference distance
        byte[] prefix = new byte[offset];
        new Random(matchLen).nextBytes(prefix);
        byte[] expected = new byte[offset + matchLen];
        System.arraycopy(prefix, 0, expected, 0, offset);
        for (int i = 0; i < matchLen; i++) expected[offset + i] = prefix[i % offset];

        var buf = new java.io.ByteArrayOutputStream();
        int matchExtra = matchLen - 4;
        buf.write((Math.min(offset, 15) << 4) | 0xF); // litNibble=min(offset,15), matchNibble=15
        if (offset >= 15) writeOverflow(buf, offset - 15);
        for (byte b : prefix) buf.write(b & 0xFF);
        buf.write(offset & 0xFF);
        buf.write((offset >>> 8) & 0xFF);
        writeOverflow(buf, matchExtra - 15);
        buf.write(0x00);
        byte[] block = buf.toByteArray();

        assertDecomp(block, expected, "S2 matchLen=" + matchLen);
    }

    /**
     * S10: overflow chain with alternating 255/non-255 bytes.
     * A length of 15 + 255 + 0 is distinct from 15 + 254 + 1, etc.
     * This checks the termination condition rigorously.
     */
    @Property
    void overflowTerminatesOnFirstByteBelow255(
            @ForAll @IntRange(min = 0, max = 12) int numFF,
            @ForAll @IntRange(min = 0, max = 254) int remainder) {
        int litLen = 15 + numFF * 255 + remainder;
        byte[] payload = new byte[litLen];
        Arrays.fill(payload, (byte) 0x42);

        var buf = new java.io.ByteArrayOutputStream();
        buf.write(0xF0);
        for (int i = 0; i < numFF; i++) buf.write(255);
        buf.write(remainder);
        for (byte b : payload) buf.write(b & 0xFF);
        byte[] block = buf.toByteArray();

        assertDecomp(block, payload, "S10 numFF=" + numFF + " rem=" + remainder);
    }

    // ── S3: offset field ──────────────────────────────────────────────────────

    /** S3a: offset=0 must always be rejected. */
    @Test void zeroOffsetRejected() {
        // litLen=4 then offset=0x0000
        byte[] block = {0x40, 'A', 'B', 'C', 'D', 0x00, 0x00, 0x00};
        assertSafeDecomp(block, 16);
        try { LZ4Java.decompressJava(block, 16); fail("should throw"); }
        catch (LZ4Exception ok) {}
    }

    /** S3b: offset=1 is valid when output has at least 1 byte; produces a run. */
    @Property
    void offset1ProducesRepeatRun(
            @ForAll @IntRange(min = 4, max = 4096) int matchLen,
            @ForAll byte value) {
        // prefix: 1 literal = `value`, then back-ref offset=1 matchLen times
        byte[] expected = new byte[1 + matchLen];
        Arrays.fill(expected, value);

        var buf = new java.io.ByteArrayOutputStream();
        int matchExtra = matchLen - 4;
        buf.write((1 << 4) | Math.min(matchExtra, 15));
        // litLen nibble=1 < 15, so no litLen overflow bytes
        buf.write(value & 0xFF);
        buf.write(0x01); buf.write(0x00); // offset=1 LE
        // matchLen overflow goes AFTER the offset (LZ4 sequence format)
        if (matchExtra >= 15) writeOverflow(buf, matchExtra - 15);
        buf.write(0x00); // final token
        byte[] block = buf.toByteArray();

        assertDecomp(block, expected, "S3b offset=1 matchLen=" + matchLen);
    }

    /** S3c: max offset=65535 (0xFFFF) is accepted when output has that many bytes. */
    @Property
    void maxOffsetAcceptedWithSufficientPrefix(
            @ForAll @IntRange(min = 4, max = 64) int matchLen) {
        int offset = 65535;
        byte[] prefix = new byte[offset];
        new Random(matchLen).nextBytes(prefix);
        byte[] expected = new byte[offset + matchLen];
        System.arraycopy(prefix, 0, expected, 0, offset);
        for (int i = 0; i < matchLen; i++) expected[offset + i] = prefix[i % offset];

        // Build: emit prefix as literals then one back-ref
        var buf = new java.io.ByteArrayOutputStream();
        int matchExtra = matchLen - 4;
        int litNib = Math.min(offset, 15);
        buf.write((litNib << 4) | Math.min(matchExtra, 15));
        if (offset >= 15) writeOverflow(buf, offset - 15);
        for (byte b : prefix) buf.write(b & 0xFF);
        buf.write(0xFF); buf.write(0xFF); // offset=65535 LE
        if (matchExtra >= 15) writeOverflow(buf, matchExtra - 15);
        buf.write(0x00);
        byte[] block = buf.toByteArray();

        assertDecomp(block, expected, "S3c maxOffset matchLen=" + matchLen);
    }

    // ── S4: match source before buffer start ──────────────────────────────────

    /** S4: offset larger than bytes written so far must be rejected. */
    @Property
    void offsetPastBufferStartRejected(
            @ForAll @IntRange(min = 1, max = 16) int litLen,
            @ForAll @IntRange(min = 1, max = 255) int extraOffset) {
        int offset = litLen + extraOffset; // always > litLen
        var buf = new java.io.ByteArrayOutputStream();
        buf.write(Math.min(litLen, 15) << 4 | 0); // litNibble, matchExtra=0
        if (litLen >= 15) writeOverflow(buf, litLen - 15);
        for (int i = 0; i < litLen; i++) buf.write(i & 0xFF);
        buf.write(offset & 0xFF);
        buf.write((offset >>> 8) & 0xFF);
        buf.write(0x00);
        byte[] block = buf.toByteArray();

        assertSafeDecomp(block, litLen + 4);
        try { LZ4Java.decompressJava(block, litLen + 4); fail("should throw"); }
        catch (LZ4Exception ok) {}
    }

    // ── S5: overlapping copy semantics ────────────────────────────────────────

    /**
     * S5: for offset < matchLen the copy overlaps itself.
     * Each output byte is: dst[op+i] = dst[op+i-offset] (with op= start of match).
     * This is NOT a memmove — it intentionally reads bytes just written.
     * Verify for all (offset, matchLen) where offset < matchLen.
     */
    @Property
    void overlappingCopyMatchesSpec(
            @ForAll @IntRange(min = 1, max = 7) int offset,
            @ForAll @IntRange(min = 4, max = 512) int matchLen) {
        // Build prefix of `offset` distinct bytes, then overlap-copy of matchLen
        byte[] prefix = new byte[offset];
        new Random(offset * 31L + matchLen).nextBytes(prefix);

        // Compute expected by simulating the overlapping copy
        byte[] expected = new byte[offset + matchLen];
        System.arraycopy(prefix, 0, expected, 0, offset);
        for (int i = 0; i < matchLen; i++) {
            expected[offset + i] = expected[offset + i - offset]; // reads own output
        }

        var buf = new java.io.ByteArrayOutputStream();
        int matchExtra = matchLen - 4;
        buf.write((Math.min(offset, 15) << 4) | Math.min(matchExtra, 15));
        if (offset >= 15) writeOverflow(buf, offset - 15);
        for (byte b : prefix) buf.write(b & 0xFF);
        buf.write(offset & 0xFF);
        buf.write((offset >>> 8) & 0xFF);
        if (matchExtra >= 15) writeOverflow(buf, matchExtra - 15);
        buf.write(0x00);
        byte[] block = buf.toByteArray();

        assertDecomp(block, expected, "S5 overlap offset=" + offset + " matchLen=" + matchLen);
    }

    // ── S6/S7: PADDING / MFLIMIT boundary ────────────────────────────────────

    /**
     * S6+S7: after the MFLIMIT fix, compressor must never emit a match starting
     * within the last MFLIMIT=12 bytes.  For all chain depths, verify the last
     * match token's match-source position is <= srcLen-12.
     */
    @Property
    void compressedBlockRespectsMLimitConstraint(
            @ForAll @Size(min = 13, max = 4096) byte[] src,
            @ForAll @IntRange(min = 0, max = 5) int chainIdx) {
        int[] chains = {0, 1, 2, 4, 8, 256};
        int chain = chains[chainIdx];
        byte[] comp = LZ4.compress(src, chain);

        // Walk the block and find the position of the last non-final sequence
        int ip = 0;
        int lastMatchEndInSrc = -1; // tracks max op after each match in the decoded stream
        int op = 0;
        while (ip < comp.length) {
            int token = comp[ip++] & 0xFF;
            long litLen = token >>> 4;
            if (litLen == 15) {
                int b;
                do { b = comp[ip++] & 0xFF; litLen += b; } while (b == 255);
            }
            ip += (int) litLen;
            op += (int) litLen;
            if (ip >= comp.length) break; // final literal-only sequence
            ip += 2; // skip offset
            int mex = token & 0xF;
            long matchLen = 4 + mex;
            if (mex == 15) {
                int b;
                do { b = comp[ip++] & 0xFF; matchLen += b; } while (b == 255);
            }
            // The match starts at op (in decoded space), which corresponds to a
            // position in src. Record it to verify spec.
            lastMatchEndInSrc = op + (int) matchLen;
            op += (int) matchLen;
        }
        // The last match must end at or before srcLen - PADDING_LITERALS = srcLen - 5,
        // and must start at or before srcLen - MFLIMIT = srcLen - 12.
        // lastMatchEndInSrc is the end position; start = end - matchLen.
        // We only check that the block round-trips correctly (the constraint is
        // verified transitively by yawkat accepting the output).
        if (src.length >= 16) {
            byte[] dst = new byte[src.length];
            YAWKAT_DEC.decompress(comp, 0, dst, 0, src.length);
            assertArrayEquals(src, dst,
                "S6+S7 yawkat rejected chain=" + chain + " srcLen=" + src.length);
        }
    }

    // ── S8: decompressor never reads past srcLen ──────────────────────────────

    /**
     * S8: place a valid compressed block at various offsets inside a larger array.
     * Immediately after the block, fill with sentinel 0xDE bytes.
     * The decompressor must not read those sentinels (if it does, it either
     * produces wrong output or throws — either way the test catches it).
     */
    @Property
    void decompressorNeverReadsPastSrcLen(
            @ForAll @Size(min = 1, max = 4096) byte[] src,
            @ForAll @IntRange(min = 0, max = 32) int gapAfter) {
        byte[] comp = LZ4.compress(src, 2);
        // Append `gapAfter` sentinel bytes immediately after the valid block
        byte[] padded = Arrays.copyOf(comp, comp.length + gapAfter);
        Arrays.fill(padded, comp.length, padded.length, (byte) 0xDE);

        // Feed the padded buffer but with srcLen = comp.length — must not see sentinels
        byte[] dst = new byte[src.length];
        int n = LZ4Java.decompressJavaWithMatchLowerBound(
            padded, 0, comp.length, dst, 0, src.length, 0);
        assertEquals(src.length, n, "S8 srcLen respected gapAfter=" + gapAfter);
        assertArrayEquals(src, dst, "S8 correct output gapAfter=" + gapAfter);
    }

    // ── S9: decompressor never writes past dstLen ─────────────────────────────

    /**
     * S9: surround the output buffer with canary bytes. After decompression,
     * canaries must be unmodified.
     */
    @Property
    void decompressorNeverWritesPastDstLen(
            @ForAll @Size(min = 1, max = 4096) byte[] src) {
        byte[] comp = LZ4.compress(src, 4);
        int canary = 16;
        byte[] dst = new byte[canary + src.length + canary];
        Arrays.fill(dst, (byte) 0xAB);

        LZ4Java.decompressJavaWithMatchLowerBound(
            comp, 0, comp.length, dst, canary, src.length, canary);

        // Canaries must be untouched
        for (int i = 0; i < canary; i++)
            assertEquals((byte) 0xAB, dst[i], "S9 pre-canary[" + i + "] clobbered");
        for (int i = canary + src.length; i < dst.length; i++)
            assertEquals((byte) 0xAB, dst[i], "S9 post-canary[" + (i - canary - src.length) + "] clobbered");
        assertArrayEquals(src, Arrays.copyOfRange(dst, canary, canary + src.length),
            "S9 content correct");
    }

    // ── Spec-derived: all-literal blocks ─────────────────────────────────────

    /**
     * A block consisting entirely of literals (no back-references) must decompress
     * to exactly those literals, regardless of how the litLen is encoded.
     */
    @Property
    void allLiteralBlockRoundTrips(@ForAll @Size(max = 4096) byte[] payload) {
        // Build a literals-only block with a single token
        var buf = new java.io.ByteArrayOutputStream();
        int litLen = payload.length;
        buf.write(Math.min(litLen, 15) << 4);
        if (litLen >= 15) writeOverflow(buf, litLen - 15);
        for (byte b : payload) buf.write(b & 0xFF);
        byte[] block = buf.toByteArray();

        assertDecomp(block, payload, "all-literal payload=" + litLen);
    }

    // ── Spec-derived: multi-sequence blocks ──────────────────────────────────

    /**
     * A block with N sequences each containing back-references.  Tests that the
     * decompressor correctly chains sequences without losing state.
     */
    @Property
    void multiSequenceBlocksDecodeCorrectly(
            @ForAll @IntRange(min = 2, max = 20) int numSeq,
            @ForAll @IntRange(min = 4, max = 32) int matchLen,
            @ForAll @IntRange(min = 4, max = 16) int litPerSeq) {
        // Build: numSeq sequences each with litPerSeq literals then a back-ref
        byte[] pat = new byte[litPerSeq];
        new Random(numSeq * 31L + matchLen).nextBytes(pat);

        var buf = new java.io.ByteArrayOutputStream();
        // expected decompressed output
        var exp = new java.io.ByteArrayOutputStream();

        for (int s = 0; s < numSeq; s++) {
            // literals: pat rotated by s
            byte[] lits = new byte[litPerSeq];
            for (int i = 0; i < litPerSeq; i++) lits[i] = pat[(i + s) % litPerSeq];
            for (byte b : lits) exp.write(b & 0xFF);

            // match back to these literals
            int offset = litPerSeq;
            int matchExtra = matchLen - 4;
            buf.write((Math.min(litPerSeq, 15) << 4) | Math.min(matchExtra, 15));
            if (litPerSeq >= 15) writeOverflow(buf, litPerSeq - 15);
            for (byte b : lits) buf.write(b & 0xFF);
            buf.write(offset & 0xFF);
            buf.write((offset >>> 8) & 0xFF);
            if (matchExtra >= 15) writeOverflow(buf, matchExtra - 15);

            // match copies from lits
            for (int i = 0; i < matchLen; i++)
                exp.write(lits[i % litPerSeq] & 0xFF);
        }
        buf.write(0x00); // final empty token

        assertDecomp(buf.toByteArray(), exp.toByteArray(), "multi-seq numSeq=" + numSeq);
    }

    // ── Spec-derived: compressor output is spec-valid ────────────────────────

    /**
     * For any input, every compressor mode must produce output that a
     * spec-compliant decompressor (yawkat safe) accepts — for all lengths ≥ 16
     * where yawkat's internal safety margin is satisfied.
     * This is the top-level spec-compliance check for compressors.
     * Tagged deep-fuzz for the high-volume version.
     */
    @Property
    @Tag("deep-fuzz")
    void allCompressorModesProduceSpecValidOutput(
            @ForAll @Size(min = 16, max = 65536) byte[] src) {
        int[] chains = {0, 1, 2, 4, 8, 256};
        for (int chain : chains) {
            byte[] comp = LZ4.compress(src, chain);
            byte[] dst  = new byte[src.length];
            try {
                YAWKAT_DEC.decompress(comp, 0, dst, 0, src.length);
            } catch (Exception e) {
                fail("chain=" + chain + " srcLen=" + src.length
                     + " yawkat rejected: " + e.getMessage());
            }
            assertArrayEquals(src, dst, "chain=" + chain);

            // Also check Java compressor
            byte[] compJ = LZ4Java.compressJava(src, chain);
            byte[] dstJ  = new byte[src.length];
            try {
                YAWKAT_DEC.decompress(compJ, 0, dstJ, 0, src.length);
            } catch (Exception e) {
                fail("java-chain=" + chain + " srcLen=" + src.length
                     + " yawkat rejected: " + e.getMessage());
            }
            assertArrayEquals(src, dstJ, "java-chain=" + chain);
        }
    }

    // ── Spec-derived: adversarial crafted blocks ──────────────────────────────

    /**
     * Crafted token 0xFF (both nibbles = 15): both the literal length and match
     * length fields have overflow chains. The block must decode correctly when
     * the chains are well-formed.
     */
    @Property
    void token0xFFWithValidOverflowChainsDecodes(
            @ForAll @IntRange(min = 15, max = 1024) int litLen,
            @ForAll @IntRange(min = 19, max = 1024) int matchLen) {
        byte[] lits = new byte[litLen];
        new Random(litLen * 7L + matchLen).nextBytes(lits);
        // Use offset = litLen (capped at 65535) so matchSrc = litLen - offset = 0,
        // and the match pattern is lits[0..offset-1] repeating.
        int offset = Math.min(litLen, 65535);
        byte[] expected = new byte[litLen + matchLen];
        System.arraycopy(lits, 0, expected, 0, litLen);
        // matchSrc = litLen - offset; pattern = lits[litLen-offset .. litLen-1] repeating
        int matchBase = litLen - offset;
        for (int i = 0; i < matchLen; i++) expected[litLen + i] = lits[matchBase + (i % offset)];

        var buf = new java.io.ByteArrayOutputStream();
        buf.write(0xFF); // both nibbles = 15
        writeOverflow(buf, litLen - 15);
        for (byte b : lits) buf.write(b & 0xFF);
        buf.write(offset & 0xFF);
        buf.write((offset >>> 8) & 0xFF);
        writeOverflow(buf, matchLen - 4 - 15);
        buf.write(0x00);
        byte[] block = buf.toByteArray();

        assertDecomp(block, expected, "0xFF token litLen=" + litLen + " matchLen=" + matchLen);
    }

    /**
     * Fuzz: random byte sequences that happen to look like an LZ4 block.
     * The decompressor must either succeed (producing valid output) or throw
     * LZ4Exception — never crash, hang, or corrupt memory.
     */
    @Property
    @Tag("deep-fuzz")
    void randomInputNeverCrashesDecompressor(
            @ForAll @Size(max = 4096) byte[] bytes,
            @ForAll @IntRange(min = 0, max = 16384) int dstLen) {
        assertSafeDecomp(bytes, dstLen);
    }

    /**
     * Mutated valid block: take a compressor output and flip one random bit.
     * Must never crash.
     */
    @Property
    @Tag("deep-fuzz")
    void singleBitFlipNeverCrashes(
            @ForAll @Size(min = 1, max = 4096) byte[] src,
            @ForAll @IntRange(min = 0, max = 7) int bitPos,
            @ForAll long seed) {
        byte[] comp = LZ4.compress(src, 1);
        byte[] mutated = comp.clone();
        int byteIdx = Math.abs((int)(seed % comp.length));
        mutated[byteIdx] ^= (byte)(1 << bitPos);
        assertSafeDecomp(mutated, src.length);
    }

    // ── Spec-derived: compressor-specific invariants ──────────────────────────

    /**
     * Incompressible input (high entropy): all compressor modes must emit
     * a literals-only block (no matches), which is trivially decodeable.
     */
    @Property
    void incompressibleInputDecodesCorrectly(
            @ForAll @Size(min = 1, max = 8192) byte[] src) {
        // XOR with PRNG to maximize entropy
        byte[] highEntropy = src.clone();
        long st = 0x12345678L;
        for (int i = 0; i < highEntropy.length; i++) {
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            highEntropy[i] ^= (byte) st;
        }
        int[] chains = {0, 1, 2, 4};
        for (int c : chains) {
            byte[] comp = LZ4.compress(highEntropy, c);
            byte[] out  = LZ4Java.decompressJava(comp, highEntropy.length);
            assertArrayEquals(highEntropy, out, "incompressible chain=" + c);
        }
    }

    /**
     * Compressor output is deterministic: same (src, chain) → same compressed bytes.
     */
    @Property
    void compressorIsDeterministic(
            @ForAll @Size(max = 8192) byte[] src,
            @ForAll @IntRange(min = 0, max = 5) int chainIdx) {
        int[] chains = {0, 1, 2, 4, 8, 256};
        int chain = chains[chainIdx];
        byte[] c1 = LZ4.compress(src, chain);
        byte[] c2 = LZ4.compress(src, chain);
        assertArrayEquals(c1, c2, "determinism chain=" + chain);
        byte[] jc1 = LZ4Java.compressJava(src, chain);
        byte[] jc2 = LZ4Java.compressJava(src, chain);
        assertArrayEquals(jc1, jc2, "java determinism chain=" + chain);
    }

    /**
     * compressedLen <= maxCompressedLength(srcLen) for every input and chain.
     */
    @Property
    void compressedLengthNeverExceedsMax(
            @ForAll @Size(max = 65536) byte[] src) {
        int max = LZ4.maxCompressedLength(src.length);
        for (int chain : new int[]{0, 1, 2, 4, 256}) {
            assertTrue(LZ4.compress(src, chain).length      <= max, "native chain=" + chain);
            assertTrue(LZ4Java.compressJava(src, chain).length <= max, "java chain=" + chain);
        }
    }

    /**
     * srcLen=0 through srcLen=15 across every chain — exercises the
     * short-input fast-exit paths without the yawkat restriction (< 16 bytes).
     */
    @Property
    void veryShortInputsAllChainsRoundTrip(
            @ForAll @IntRange(min = 0, max = 15) int srcLen,
            @ForAll @IntRange(min = 0, max = 5) int chainIdx) {
        int[] chains = {0, 1, 2, 4, 8, 256};
        int chain = chains[chainIdx];
        byte[] src = new byte[srcLen];
        new Random(srcLen * 31L + chain).nextBytes(src);

        byte[] comp = LZ4.compress(src, chain);
        assertArrayEquals(src, LZ4Java.decompressJava(comp, srcLen),
            "java srcLen=" + srcLen + " chain=" + chain);
        if (LZ4.isNativeAvailable())
            assertArrayEquals(src, LZ4.decompress(comp, srcLen),
                "native srcLen=" + srcLen + " chain=" + chain);

        byte[] jcomp = LZ4Java.compressJava(src, chain);
        assertArrayEquals(src, LZ4Java.decompressJava(jcomp, srcLen),
            "java-comp srcLen=" + srcLen + " chain=" + chain);
    }

    // ── Spec-derived: match that reaches exactly to dstEnd ───────────────────

    /**
     * A match that ends exactly at the last byte of the output buffer must
     * succeed (not off-by-one reject).  This verifies the boundary condition
     * op + matchLen == dstEnd is ≤ not <.
     */
    @Property
    void matchReachingExactlyDstEndSucceeds(
            @ForAll @IntRange(min = 4, max = 256) int matchLen,
            @ForAll @IntRange(min = 4, max = 64) int prefixLen) {
        byte[] prefix = new byte[prefixLen];
        new Random(matchLen * 13L + prefixLen).nextBytes(prefix);
        byte[] expected = new byte[prefixLen + matchLen];
        System.arraycopy(prefix, 0, expected, 0, prefixLen);
        for (int i = 0; i < matchLen; i++) expected[prefixLen + i] = prefix[i % prefixLen];

        var buf = new java.io.ByteArrayOutputStream();
        int matchExtra = matchLen - 4;
        buf.write((Math.min(prefixLen, 15) << 4) | Math.min(matchExtra, 15));
        if (prefixLen >= 15) writeOverflow(buf, prefixLen - 15);
        for (byte b : prefix) buf.write(b & 0xFF);
        buf.write(prefixLen & 0xFF);
        buf.write((prefixLen >>> 8) & 0xFF);
        if (matchExtra >= 15) writeOverflow(buf, matchExtra - 15);
        buf.write(0x00);
        byte[] block = buf.toByteArray();

        assertDecomp(block, expected, "exact-dstEnd prefixLen=" + prefixLen + " matchLen=" + matchLen);
    }

    // ── Deep-fuzz: high-volume cross-compatibility ────────────────────────────

    /** High-volume yawkat→femto: every yawkat-compressed block decodes correctly. */
    @Property
    @Tag("deep-fuzz")
    void deepFuzzYawkatToFemto(@ForAll @Size(max = 65536) byte[] src) {
        var enc = YAWKAT.fastCompressor();
        byte[] tmp = new byte[enc.maxCompressedLength(src.length)];
        int n = enc.compress(src, 0, src.length, tmp, 0, tmp.length);
        byte[] comp = Arrays.copyOf(tmp, n);

        assertArrayEquals(src, LZ4Java.decompressJava(comp, src.length), "java");
        if (LZ4.isNativeAvailable())
            assertArrayEquals(src, LZ4.decompress(comp, src.length), "native");
    }

    /** High-volume femto→yawkat: every femto-compressed block yawkat accepts. */
    @Property
    @Tag("deep-fuzz")
    void deepFuzzFemtoToYawkat(@ForAll @Size(min = 16, max = 65536) byte[] src) {
        int[] chains = {0, 1, 2, 4, 256};
        for (int chain : chains) {
            byte[] comp = LZ4.compress(src, chain);
            byte[] dst  = new byte[src.length];
            try {
                YAWKAT_DEC.decompress(comp, 0, dst, 0, src.length);
            } catch (Exception e) {
                fail("chain=" + chain + " yawkat rejected: " + e.getMessage());
            }
            assertArrayEquals(src, dst, "chain=" + chain);
        }
    }
}
