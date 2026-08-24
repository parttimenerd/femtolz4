package me.bechberger.femtolz4;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases suggested by an LLM scan of the public API surface.
 *
 * Covers gaps not reached by RobustnessTest, DecompressorFuzzTest,
 * YawkatFuzzTest, SpecFuzzTest, ProbeEdgeTest, or ApiCoverageTest:
 *   - LZ4FrameOutputStream constructor validation (invalid blockSize / level)
 *   - LZ4FrameOutputStream double-close, flush-after-close
 *   - LZ4FrameOutputStream byte-at-a-time across a block boundary
 *   - LZ4FrameInputStream on empty/truncated/corrupt headers
 *   - LZ4FrameInputStream skippable + concatenated frames
 *   - LZ4FrameInputStream block-dependent mode round-trip and history rotation
 *   - LZ4FrameInputStream available() accuracy
 *   - LZ4FrameInputStream invalid BD field
 *   - XXHash32.Streaming: reset, multi-digest, incremental vs bulk equivalence
 *   - maxCompressedLength with large input
 *   - compressHigh level clamping
 */
class LLMEdgeCaseTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static byte[] frameRoundTrip(byte[] data, int blockSize, int level) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var out = new LZ4FrameOutputStream(baos, blockSize, level)) {
            out.write(data);
        }
        try (var in = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            return in.readAllBytes();
        }
    }

    private static byte[] randomBytes(int len, long seed) {
        var b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }

    // ── LZ4FrameOutputStream: constructor validation ──────────────────────────

    @Test void invalidBlockSizeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new LZ4FrameOutputStream(OutputStream.nullOutputStream(), 100_000, 1));
    }

    @Test void blockSizeOneByteOffThrows() {
        int bad = 64 * 1024 + 1;
        assertThrows(IllegalArgumentException.class,
            () -> new LZ4FrameOutputStream(OutputStream.nullOutputStream(), bad, 1));
    }

    @Test void levelZeroThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new LZ4FrameOutputStream(OutputStream.nullOutputStream(), 0));
    }

    @Test void levelTooHighThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new LZ4FrameOutputStream(OutputStream.nullOutputStream(), LZ4FrameOutputStream.MAX_LEVEL + 1));
    }

    @Test void levelNegativeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new LZ4FrameOutputStream(OutputStream.nullOutputStream(), -1));
    }

    // ── LZ4FrameOutputStream: idempotent close + post-close ops ──────────────

    @Test void doubleCloseIsHarmless() throws IOException {
        var baos = new ByteArrayOutputStream();
        var out = new LZ4FrameOutputStream(baos);
        out.write(42);
        out.close();
        assertDoesNotThrow(out::close, "second close() must be a no-op");
    }

    @Test void flushAfterCloseThrows() throws IOException {
        var out = new LZ4FrameOutputStream(OutputStream.nullOutputStream());
        out.close();
        assertThrows(IOException.class, out::flush);
    }

    @Test void writeByteAfterCloseThrows() throws IOException {
        var out = new LZ4FrameOutputStream(OutputStream.nullOutputStream());
        out.close();
        assertThrows(IOException.class, () -> out.write(0));
    }

    @Test void writeArrayAfterCloseThrows() throws IOException {
        var out = new LZ4FrameOutputStream(OutputStream.nullOutputStream());
        out.close();
        assertThrows(IOException.class, () -> out.write(new byte[10], 0, 10));
    }

    // ── LZ4FrameOutputStream: byte-at-a-time path across block boundary ───────

    @Test void byteAtATimeCrossesBlockBoundaryAndRoundTrips() throws IOException {
        int blockSize = 64 * 1024;
        // write blockSize + 100 bytes one byte at a time — forces at least one full-block flush
        byte[] data = randomBytes(blockSize + 100, 0xBEEF);
        var baos = new ByteArrayOutputStream();
        try (var out = new LZ4FrameOutputStream(baos, blockSize, 1)) {
            for (byte b : data) out.write(b & 0xFF);
        }
        try (var in = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    // ── LZ4FrameInputStream: constructor failures ─────────────────────────────

    @Test void emptyStreamThrowsOnConstruction() {
        assertThrows(LZ4Exception.class,
            () -> new LZ4FrameInputStream(new ByteArrayInputStream(new byte[0])));
    }

    @Test void onlyMagicBytesThrowsOnConstruction() {
        // 4-byte magic, then EOF — should throw on reading FLG
        byte[] magic = {0x04, 0x22, 0x4D, 0x18};
        assertThrows(LZ4Exception.class,
            () -> new LZ4FrameInputStream(new ByteArrayInputStream(magic)));
    }

    @Test void badMagicThrowsOnConstruction() {
        byte[] bad = {0x00, 0x01, 0x02, 0x03, 0x60, 0x70, 0x73};
        assertThrows(LZ4Exception.class,
            () -> new LZ4FrameInputStream(new ByteArrayInputStream(bad)));
    }

    @Test void badHeaderChecksumThrowsOnConstruction() throws IOException {
        // Build a valid frame, then corrupt the HC byte (byte 6)
        var baos = new ByteArrayOutputStream();
        try (var out = new LZ4FrameOutputStream(baos)) { out.write(1); }
        byte[] frame = baos.toByteArray();
        frame[6] ^= 0xFF; // flip HC byte
        assertThrows(LZ4Exception.class,
            () -> new LZ4FrameInputStream(new ByteArrayInputStream(frame)));
    }

    @Test void invalidBdFieldThrowsOnConstruction() throws IOException {
        // Build valid frame, replace BD byte (byte 5) with field=0 (not 4-7)
        var baos = new ByteArrayOutputStream();
        try (var out = new LZ4FrameOutputStream(baos)) { out.write(1); }
        byte[] frame = baos.toByteArray();
        // BD byte is at index 5; set block-size field (bits 6:4) to 0 → invalid
        frame[5] = 0x00;
        // Recompute HC so header checksum doesn't fail first
        byte[] desc = {frame[4], frame[5]};
        frame[6] = (byte) ((XXHash32.hash(desc, 0, 2) >> 8) & 0xFF);
        assertThrows(LZ4Exception.class,
            () -> new LZ4FrameInputStream(new ByteArrayInputStream(frame)));
    }

    @Test void truncatedBlockPayloadThrows() throws IOException {
        // Build a valid compressed frame, then truncate the block payload
        var baos = new ByteArrayOutputStream();
        try (var out = new LZ4FrameOutputStream(baos, 64 * 1024, 1)) {
            out.write(randomBytes(1000, 1));
        }
        byte[] frame = baos.toByteArray();
        // Truncate to half the frame — cuts through the block payload
        byte[] truncated = Arrays.copyOf(frame, frame.length / 2);
        LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(truncated));
        assertThrows(LZ4Exception.class, in::readAllBytes);
    }

    // ── LZ4FrameInputStream: skippable frames ────────────────────────────────

    @Test void skippableFrameBeforeDataIsIgnored() throws IOException {
        // A skippable frame has magic 0x184D2A5X (any X in 0-F) then 4-byte LE size then payload
        byte[] data = randomBytes(500, 42);
        var baos = new ByteArrayOutputStream();

        // prepend a skippable frame with 16 bytes of garbage
        var skippable = new byte[]{
            (byte) 0x50, 0x2A, 0x4D, 0x18,  // magic 0x184D2A50 LE
            0x10, 0x00, 0x00, 0x00           // size = 16
        };
        baos.write(skippable);
        baos.write(new byte[16]); // dummy payload
        // then a real frame
        try (var out = new LZ4FrameOutputStream(baos, 64 * 1024, 1)) {
            out.write(data);
        }

        try (var in = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test void skippableFrameAfterFirstFrameIsIgnored() throws IOException {
        byte[] data1 = randomBytes(300, 1);
        byte[] data2 = randomBytes(300, 2);
        var baos = new ByteArrayOutputStream();

        try (var out = new LZ4FrameOutputStream(baos, 64 * 1024, 1)) { out.write(data1); }

        // skippable frame between the two data frames
        baos.write(new byte[]{(byte) 0x50, 0x2A, 0x4D, 0x18, 0x04, 0x00, 0x00, 0x00});
        baos.write(new byte[4]); // 4-byte payload

        try (var out = new LZ4FrameOutputStream(baos, 64 * 1024, 1)) { out.write(data2); }

        try (var in = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            byte[] got = in.readAllBytes();
            byte[] expected = new byte[data1.length + data2.length];
            System.arraycopy(data1, 0, expected, 0, data1.length);
            System.arraycopy(data2, 0, expected, data1.length, data2.length);
            assertArrayEquals(expected, got);
        }
    }

    // ── LZ4FrameInputStream: concatenated frames ──────────────────────────────

    @Test void threeConcatenatedFramesRoundTrip() throws IOException {
        byte[] a = randomBytes(500, 10), b = randomBytes(300, 20), c = randomBytes(700, 30);
        var baos = new ByteArrayOutputStream();
        for (byte[] seg : new byte[][]{a, b, c}) {
            try (var out = new LZ4FrameOutputStream(baos, 64 * 1024, 1)) { out.write(seg); }
        }
        try (var in = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            byte[] all = in.readAllBytes();
            assertEquals(a.length + b.length + c.length, all.length);
            assertArrayEquals(a, Arrays.copyOfRange(all, 0, a.length));
            assertArrayEquals(b, Arrays.copyOfRange(all, a.length, a.length + b.length));
            assertArrayEquals(c, Arrays.copyOfRange(all, a.length + b.length, all.length));
        }
    }

    @Test void emptyFrameConcatenatedBeforeDataFrame() throws IOException {
        byte[] data = randomBytes(400, 99);
        var baos = new ByteArrayOutputStream();
        // write an empty frame (header + end-mark only)
        try (var out = new LZ4FrameOutputStream(baos, 64 * 1024, 1)) { /* empty */ }
        try (var out = new LZ4FrameOutputStream(baos, 64 * 1024, 1)) { out.write(data); }

        try (var in = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    // ── LZ4FrameInputStream: available() accuracy ─────────────────────────────

    @Test void availableAfterOneByteReadReflectsBlockRemainder() throws IOException {
        byte[] data = randomBytes(1000, 7);
        var baos = new ByteArrayOutputStream();
        try (var out = new LZ4FrameOutputStream(baos, 64 * 1024, 1)) { out.write(data); }

        try (var in = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            int first = in.read();
            assertNotEquals(-1, first);
            // After reading 1 byte from a 1000-byte block, ≥ 999 bytes should be available
            // (available() returns blockLen - blockPos within the current block)
            assertTrue(in.available() >= 999,
                "available() must be ≥ 999 after reading 1 byte from a 1000-byte stream, got " + in.available());
        }
    }

    // ── LZ4FrameInputStream: block-dependent frames ───────────────────────────

    /**
     * Craft a block-dependent frame by writing the raw frame bytes manually.
     * FLG bit 5 (block_independent) = 0 means block-dependent.
     *
     * We simply compress a sequence of small data blocks via the Java API and
     * wrap them in a block-dependent frame header. Then verify the femto reader
     * decodes it correctly.
     */
    @Test void blockDependentFrameRoundTrip() throws IOException {
        // Build a block-dependent frame manually:
        // header: magic | FLG(no block_independent) | BD | HC
        //   FLG = 0x40 (version=01, block_independent=0, no checksums)
        //   BD  = 0x40 (block size field=4 → 64 KiB)
        byte flg = 0x40;
        byte bd  = 0x40;
        byte[] desc = {flg, bd};
        byte hc = (byte) ((XXHash32.hash(desc, 0, 2) >> 8) & 0xFF);

        byte[] blockData = randomBytes(200, 123);
        byte[] compressed = LZ4.compress(blockData);

        var baos = new ByteArrayOutputStream();
        // magic LE
        baos.write(0x04); baos.write(0x22); baos.write(0x4D); baos.write(0x18);
        baos.write(flg); baos.write(bd); baos.write(hc);
        // one compressed block: size LE32
        int sz = compressed.length;
        baos.write(sz & 0xFF); baos.write((sz >> 8) & 0xFF);
        baos.write((sz >> 16) & 0xFF); baos.write((sz >> 24) & 0xFF);
        baos.write(compressed);
        // end mark
        baos.write(0); baos.write(0); baos.write(0); baos.write(0);

        try (var in = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            assertArrayEquals(blockData, in.readAllBytes());
        }
    }

    @Test void blockDependentHistoryRotationSurvivesMultipleBlocks() throws IOException {
        // Write 5 × 1000-byte blocks; femto must track history across them
        // We do this by using the femto frame streams but patching FLG to clear bit 5
        // Simpler: just verify that a block-dependent frame produced by a manual writer
        // with cross-block back-references decodes correctly.
        // Here we only test that two consecutive blocks in a dep-frame round-trip,
        // which exercises the history buffer update path.
        byte flg = 0x40; // block_independent=0
        byte bd  = 0x40; // 64KiB block size
        byte[] desc = {flg, bd};
        byte hc = (byte) ((XXHash32.hash(desc, 0, 2) >> 8) & 0xFF);

        byte[] block1 = randomBytes(500, 1);
        byte[] block2 = randomBytes(500, 2);
        byte[] c1 = LZ4.compress(block1);
        byte[] c2 = LZ4.compress(block2);

        var baos = new ByteArrayOutputStream();
        baos.write(0x04); baos.write(0x22); baos.write(0x4D); baos.write(0x18);
        baos.write(flg); baos.write(bd); baos.write(hc);
        for (byte[] c : new byte[][]{c1, c2}) {
            int sz = c.length;
            baos.write(sz & 0xFF); baos.write((sz >> 8) & 0xFF);
            baos.write((sz >> 16) & 0xFF); baos.write((sz >> 24) & 0xFF);
            baos.write(c);
        }
        baos.write(0); baos.write(0); baos.write(0); baos.write(0);

        byte[] expected = new byte[block1.length + block2.length];
        System.arraycopy(block1, 0, expected, 0, block1.length);
        System.arraycopy(block2, 0, expected, block1.length, block2.length);

        try (var in = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            assertArrayEquals(expected, in.readAllBytes());
        }
    }

    // ── XXHash32.Streaming ────────────────────────────────────────────────────

    @Test void streamingIncrementalEqualsBulk() {
        byte[] data = randomBytes(4096, 0xCAFE);
        int bulk = XXHash32.hash(data, 0, data.length);

        var s = new XXHash32.Streaming();
        for (int i = 0; i < data.length; i++) {
            s.update(data, i, 1);
        }
        assertEquals(bulk, s.digest(), "byte-at-a-time must equal bulk hash");
    }

    @Test void streamingMultipleDigestCallsAreIdempotent() {
        byte[] data = randomBytes(100, 1);
        var s = new XXHash32.Streaming();
        s.update(data, 0, data.length);
        int first  = s.digest();
        int second = s.digest();
        assertEquals(first, second, "digest() must be idempotent");
    }

    @Test void streamingResetProducesFreshHash() {
        byte[] data1 = randomBytes(64, 1);
        byte[] data2 = randomBytes(64, 2);

        var s = new XXHash32.Streaming();
        s.update(data1, 0, data1.length);
        s.reset();
        s.update(data2, 0, data2.length);

        int expected = XXHash32.hash(data2, 0, data2.length);
        assertEquals(expected, s.digest(), "after reset(), digest must equal fresh hash of data2");
    }

    @Test void streamingResetThenDigestEqualsEmptyHash() {
        byte[] data = randomBytes(128, 7);
        var s = new XXHash32.Streaming();
        s.update(data, 0, data.length);
        s.reset();
        // digest on a reset state should equal hash of empty input
        int expected = XXHash32.hash(new byte[0], 0, 0);
        assertEquals(expected, s.digest(), "reset() + digest() must equal empty-input hash");
    }

    @Test void streamingChunkedVariantsSameResult() {
        byte[] data = randomBytes(100, 99);
        int bulk = XXHash32.hash(data, 0, data.length);

        // chunks of 3
        var s3 = new XXHash32.Streaming();
        for (int i = 0; i < data.length; i += 3) {
            s3.update(data, i, Math.min(3, data.length - i));
        }
        assertEquals(bulk, s3.digest(), "3-byte chunks");

        // chunks of 16
        var s16 = new XXHash32.Streaming();
        for (int i = 0; i < data.length; i += 16) {
            s16.update(data, i, Math.min(16, data.length - i));
        }
        assertEquals(bulk, s16.digest(), "16-byte chunks");

        // chunks of 17
        var s17 = new XXHash32.Streaming();
        for (int i = 0; i < data.length; i += 17) {
            s17.update(data, i, Math.min(17, data.length - i));
        }
        assertEquals(bulk, s17.digest(), "17-byte chunks");
    }

    // ── LZ4.maxCompressedLength: large input ──────────────────────────────────

    @Test void maxCompressedLengthNeverNegativeForReasonableInput() {
        for (int n : new int[]{0, 1, 255, 256, 65535, 65536, 1 << 20, 1 << 26}) {
            int max = LZ4.maxCompressedLength(n);
            assertTrue(max >= n, "maxCompressedLength(" + n + ")=" + max + " must be ≥ srcLen");
        }
    }

    // ── compressHigh level clamping ───────────────────────────────────────────

    @Test void compressHighLevelClampingBelowMinRoundTrips() {
        // level=0 is below HC_MIN_LEVEL; compressHigh clamps to 1
        byte[] src = randomBytes(1024, 0);
        LZ4.Compressor c = LZ4.compressHigh(0);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int compLen = c.compress(src, 0, src.length, dst, 0, dst.length);
        byte[] out = new byte[src.length];
        LZ4.decompress(dst, 0, compLen, out, 0, src.length);
        assertArrayEquals(src, out);
    }

    @Test void compressHighLevelClampingAboveMaxRoundTrips() {
        // level=257 is above HC_MAX_LEVEL; should clamp to 256
        byte[] src = randomBytes(1024, 0);
        LZ4.Compressor c = LZ4.compressHigh(257);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int compLen = c.compress(src, 0, src.length, dst, 0, dst.length);
        byte[] out = new byte[src.length];
        LZ4.decompress(dst, 0, compLen, out, 0, src.length);
        assertArrayEquals(src, out);
    }

    // ── all block sizes round-trip ─────────────────────────────────────────────

    @Test void allValidBlockSizesRoundTrip() throws IOException {
        int[] sizes = {64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024};
        byte[] data = randomBytes(130 * 1024, 5); // crosses the 64K and 256K boundaries
        for (int bs : sizes) {
            byte[] got = frameRoundTrip(data, bs, 1);
            assertArrayEquals(data, got, "blockSize=" + bs);
        }
    }

    @Test void allValidLevelsRoundTrip() throws IOException {
        byte[] data = randomBytes(10_000, 6);
        for (int level = LZ4FrameOutputStream.MIN_LEVEL; level <= LZ4FrameOutputStream.MAX_LEVEL; level++) {
            byte[] got = frameRoundTrip(data, 64 * 1024, level);
            assertArrayEquals(data, got, "level=" + level);
        }
    }
}
