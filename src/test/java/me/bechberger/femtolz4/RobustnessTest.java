package me.bechberger.femtolz4;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Robustness tests: truncated files and adversarially crafted input must
 * never crash the JVM (or the native library) — only {@link LZ4Exception} /
 * {@link IOException} are acceptable failure modes. Covers both the native
 * and pure-Java code paths, at the block level and the frame-stream level.
 */
class RobustnessTest {

    // ── Truncation: every possible prefix of a valid compressed block ───────

    @Test void everyTruncationOfCompressedBlockThrowsOrSucceeds() {
        byte[] src = randomBytes(4096, 1);
        byte[] comp = LZ4.compress(src, 1);
        for (int len = 0; len < comp.length; len++) {
            byte[] truncated = Arrays.copyOf(comp, len);
            assertSafeDecompress(truncated, src.length);
            assertSafeDecompressJava(truncated, src.length);
        }
    }

    @Tag("slow")
    @Test void everyTruncationOfCompressedBlockThrowsOrSucceeds_chain8() {
        byte[] src = randomBytes(4096, 2);
        byte[] comp = LZ4.compress(src, 8);
        for (int len = 0; len < comp.length; len++) {
            byte[] truncated = Arrays.copyOf(comp, len);
            assertSafeDecompress(truncated, src.length);
            assertSafeDecompressJava(truncated, src.length);
        }
    }

    // ── Truncation: every possible prefix of a valid frame stream ───────────

    @Test void everyTruncationOfFrameStreamThrowsOrSucceeds() throws IOException {
        byte[] src = randomBytes(8192, 3);
        byte[] frame = LZ4Test.frameCompress(src);
        for (int len = 0; len <= frame.length; len++) {
            byte[] truncated = Arrays.copyOf(frame, len);
            assertSafeFrameDecompress(truncated);
        }
    }

    // ── Truncation: mid-stream cut while reading (not just whole-buffer) ────

    @Test void frameStreamTruncatedDuringRead() throws IOException {
        byte[] src = randomBytes(LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE + 4096, 4);
        byte[] frame = LZ4Test.frameCompress(src);
        // cut somewhere in the middle of the second block
        byte[] truncated = Arrays.copyOf(frame, frame.length - 100);
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(truncated))) {
            assertThrows(LZ4Exception.class, in::readAllBytes);
        }
    }

    // ── Fuzz: arbitrary random garbage never crashes the decompressor ──────

    @Property(tries = 2000)
    void randomGarbageNeverCrashes(@ForAll @Size(max = 2048) byte[] garbage,
                                   @IntRange(min = 0, max = 8192) @ForAll int dstLen) {
        assertSafeDecompress(garbage, dstLen);
        assertSafeDecompressJava(garbage, dstLen);
    }

    @Property(tries = 500)
    void randomGarbageAsFrameNeverCrashes(@ForAll @Size(max = 4096) byte[] garbage) {
        assertSafeFrameDecompress(garbage);
    }

    // ── Crafted: literal-length field integer overflow via long 0xFF chain ──

    @Test void craftedLiteralLengthOverflowRejected() {
        byte[] evil = new byte[4096];
        evil[0] = (byte) 0xFF; // token: lit_len nibble=15, match_extra nibble=15
        Arrays.fill(evil, 1, evil.length, (byte) 0xFF); // unterminated overflow chain
        assertSafeDecompress(evil, 4096);
        assertSafeDecompressJava(evil, 4096);
    }

    @Test void craftedMatchLengthOverflowRejected() {
        byte[] evil = new byte[4096];
        evil[0] = 0x0F;              // 0 literals, match_extra nibble=15
        evil[1] = 0x01; evil[2] = 0; // offset = 1
        Arrays.fill(evil, 3, evil.length, (byte) 0xFF); // unterminated overflow chain
        assertSafeDecompress(evil, 4096);
        assertSafeDecompressJava(evil, 4096);
    }

    // ── Crafted: match offset pointing before the output buffer start ──────

    @Test void craftedHugeOffsetRejected() {
        byte[] evil = {0x11, 'A', (byte) 0xFF, (byte) 0xFF};
        assertSafeDecompress(evil, 4096);
        assertSafeDecompressJava(evil, 4096);
    }

    // ── Crafted: zero match offset ───────────────────────────────────────────

    @Test void craftedZeroOffsetRejected() {
        byte[] evil = {0x11, 'A', 0x00, 0x00};
        assertSafeDecompress(evil, 4096);
        assertSafeDecompressJava(evil, 4096);
    }

    // ── Crafted: output/input length lies designed to trip overflow checks ──

    @Property(tries = 500)
    void craftedTokensWithRandomOverflowChainsNeverCrash(
            @ForAll @IntRange(min = 0, max = 15) int litNibble,
            @ForAll @IntRange(min = 0, max = 15) int matchNibble,
            @ForAll @IntRange(min = 0, max = 64) int chainLen) {
        byte[] evil = new byte[8 + chainLen];
        evil[0] = (byte) ((litNibble << 4) | matchNibble);
        int p = 1;
        if (litNibble == 15) {
            for (int i = 0; i < chainLen && p < evil.length; i++) evil[p++] = (byte) 255;
        }
        assertSafeDecompress(Arrays.copyOf(evil, p), 4096);
        assertSafeDecompressJava(Arrays.copyOf(evil, p), 4096);
    }

    // ── Crafted frame: declared block size larger than the negotiated max ──

    @Test void frameOversizedBlockSizeFieldRejected() throws IOException {
        byte[] header = frameHeaderOnly();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(header);
        // Claim a block of 100 MB — must be rejected without allocating 100 MB.
        writeLE32(baos, 100 * 1024 * 1024);
        assertSafeFrameDecompress(baos.toByteArray());
    }

    @Test void frameNegativeSizeFieldHighBitSetHugeRejected() throws IOException {
        byte[] header = frameHeaderOnly();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(header);
        // Raw-block flag set + huge payload length.
        writeLE32(baos, 0x80000000 | (100 * 1024 * 1024));
        assertSafeFrameDecompress(baos.toByteArray());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Decompress (native-preferring path) must either throw LZ4Exception or succeed — never crash/hang. */
    private static void assertSafeDecompress(byte[] data, int dstLen) {
        byte[] dst = new byte[dstLen];
        try {
            LZ4.decompress(data, 0, data.length, dst, 0, dstLen);
        } catch (LZ4Exception expected) {
            // acceptable
        }
    }

    /** Same, but forcing the pure-Java fallback path explicitly. */
    private static void assertSafeDecompressJava(byte[] data, int dstLen) {
        byte[] dst = new byte[dstLen];
        try {
            LZ4.decompressJava(data, dstLen);
        } catch (LZ4Exception expected) {
            // acceptable
        }
    }

    /** Frame decode must either throw LZ4Exception/IOException or succeed — never crash/hang. */
    private static void assertSafeFrameDecompress(byte[] data) {
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(data))) {
            in.readAllBytes();
        } catch (LZ4Exception | IOException expected) {
            // acceptable
        }
    }

    private static byte[] frameHeaderOnly() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream out = new LZ4FrameOutputStream(baos)) {
            out.write(1); // force header to be written, then discard the rest
        }
        byte[] full = baos.toByteArray();
        return Arrays.copyOf(full, 7); // magic(4) + FLG(1) + BD(1) + HC(1)
    }

    private static void writeLE32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static byte[] randomBytes(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }
}
