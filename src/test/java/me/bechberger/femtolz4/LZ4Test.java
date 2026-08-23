package me.bechberger.femtolz4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LZ4Test {

    // ── Block compress / decompress ───────────────────────────────────────────

    @Test void emptyRoundTrip() {
        byte[] compressed = LZ4.compress(new byte[0], 1);
        byte[] result = LZ4.decompress(compressed, 0);
        assertArrayEquals(new byte[0], result);
    }

    @Test void singleByteRoundTrip() {
        byte[] src = {42};
        assertRoundTrip(src, 1);
    }

    @Test void allZerosHighlyCompressible() {
        byte[] src = new byte[64 * 1024];
        byte[] compressed = LZ4.compress(src, 1);
        assertTrue(compressed.length < src.length / 10, "zeros should compress >10x");
        assertRoundTrip(src, 1);
    }

    @Test void randomIncompressible() {
        byte[] src = randomBytes(64 * 1024, 1);
        byte[] compressed = LZ4.compress(src, 1);
        // incompressible data — output should not be dramatically larger than input
        assertTrue(compressed.length <= LZ4.maxCompressedLength(src.length));
        assertRoundTrip(src, 1);
    }

    @Test void repeatingPattern() {
        // "abcdefgh" repeated — should compress well
        byte[] unit = "abcdefgh".getBytes(StandardCharsets.UTF_8);
        byte[] src = new byte[32 * 1024];
        for (int i = 0; i < src.length; i++) src[i] = unit[i % unit.length];
        byte[] compressed = LZ4.compress(src, 1);
        assertTrue(compressed.length < src.length / 5);
        assertRoundTrip(src, 1);
    }

    @Test void asciiText() {
        StringBuilder sb = new StringBuilder();
        assertRoundTrip("The quick brown fox jumps over the lazy dog. ".repeat(5000).getBytes(StandardCharsets.UTF_8), 1);
    }

    @ParameterizedTest @ValueSource(ints = {1, 4, 8, 16, 64})
    void multipleChainDepths(int maxChain) {
        byte[] src = randomBytes(16 * 1024, 99);
        assertRoundTrip(src, maxChain);
    }

    @ParameterizedTest @ValueSource(ints = {1, 7, 8, 255, 256, 257, 1023, 1024, 65535, 65536, 128 * 1024})
    void variousSizes(int size) {
        // compressible data of each interesting size
        byte[] src = new byte[size];
        Arrays.fill(src, (byte) 'A');
        assertRoundTrip(src, 1);
    }

    @Test void longMatchSpanningWindow() {
        // Same 4-byte pattern repeated more than WINDOW_SIZE times
        byte[] src = new byte[LZ4.WINDOW_SIZE * 2 + 1024];
        Arrays.fill(src, (byte) 0x55);
        assertRoundTrip(src, 4);
    }

    @Test void maxCompressedLengthIsCorrect() {
        for (int n : new int[]{0, 1, 255, 256, 1024, 65536, 1 << 20}) {
            byte[] src = randomBytes(n, n);
            byte[] comp = new byte[LZ4.maxCompressedLength(n)];
            int len = LZ4.compress(src, 0, n, comp, 0, 1);
            assertTrue(len <= LZ4.maxCompressedLength(n));
        }
    }

    // ── Block decompress error handling ───────────────────────────────────────

    @Test void decompressZeroOffsetThrows() {
        // craft a block with a zero match offset
        byte[] bad = {
            0x11,           // token: 1 literal, 1 match-extra
            0x41,           // literal 'A'
            0x00, 0x00,     // match offset = 0  → must throw
            0x00            // padding
        };
        assertThrows(LZ4Exception.class,
            () -> LZ4.decompress(bad, 0, bad.length, new byte[64], 0, 64));
    }

    @Test void decompressOutputOverflowThrows() {
        byte[] src = new byte[1024];
        Arrays.fill(src, (byte) 'X');
        byte[] compressed = LZ4.compress(src, 1);
        // provide a buffer that is too small
        assertThrows(LZ4Exception.class,
            () -> LZ4.decompress(compressed, 0, compressed.length, new byte[100], 0, 100));
    }

    // ── Frame streams ─────────────────────────────────────────────────────────

    @Test void frameRoundTripSimple() throws IOException {
        byte[] src = "Hello, LZ4 frame world!".getBytes(StandardCharsets.UTF_8);
        assertFrameRoundTrip(src, LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE, LZ4FrameOutputStream.LEVEL_FAST);
    }

    @Test void frameRoundTripEmpty() throws IOException {
        assertFrameRoundTrip(new byte[0], LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE, LZ4FrameOutputStream.LEVEL_FAST);
    }

    @ParameterizedTest @ValueSource(ints = {64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024})
    void frameRoundTripAllBlockSizes(int blockSize) throws IOException {
        byte[] src = randomBytes(blockSize * 2 + 1337, 7);
        assertFrameRoundTrip(src, blockSize, LZ4FrameOutputStream.LEVEL_FAST);
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
    void frameRoundTripAllPublicLevels(int level) throws IOException {
        byte[] src = randomBytes(256 * 1024 + 123, level);
        assertFrameRoundTrip(src, LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE, level);
    }

    @Test void frameInvalidLevelRejected() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class,
            () -> new LZ4FrameOutputStream(baos, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new LZ4FrameOutputStream(baos, 10));
    }

    @Test void frameInvalidBlockSizeRejected() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class,
            () -> new LZ4FrameOutputStream(baos, 12345, LZ4FrameOutputStream.LEVEL_FAST));
        assertThrows(IllegalArgumentException.class,
            () -> new LZ4FrameOutputStream(baos, 65 * 1024, LZ4FrameOutputStream.LEVEL_FAST));
    }

    @Test void frameMultipleBlocks() throws IOException {
        // data larger than one block
        byte[] src = randomBytes(LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE * 3 + 500, 42);
        assertFrameRoundTrip(src, LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE, LZ4FrameOutputStream.LEVEL_FAST);
    }

    @Test void frameExactlyOneBlock() throws IOException {
        byte[] src = randomBytes(LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE, 1);
        assertFrameRoundTrip(src, LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE, LZ4FrameOutputStream.LEVEL_FAST);
    }

    @Test void frameWriteByteAtATime() throws IOException {
        byte[] src = "one byte at a time!".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos)) {
            for (byte b : src) lz4.write(b & 0xFF);
        }
        byte[] result = new LZ4FrameInputStream(
            new ByteArrayInputStream(baos.toByteArray())).readAllBytes();
        assertArrayEquals(src, result);
    }

    @Test void frameReadByteAtATime() throws IOException {
        byte[] src = "one byte at a time!".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = frameCompress(src);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameInputStream lz4 = new LZ4FrameInputStream(new ByteArrayInputStream(compressed))) {
            int b;
            while ((b = lz4.read()) != -1) out.write(b);
        }
        assertArrayEquals(src, out.toByteArray());
    }

    @Test void frameConcatenatedFrames() throws IOException {
        // Two complete LZ4 frames concatenated — decoder must handle both
        byte[] a = "frame one contents".getBytes(StandardCharsets.UTF_8);
        byte[] b = "frame two contents".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos)) { lz4.write(a); }
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos)) { lz4.write(b); }

        byte[] combined = new byte[a.length + b.length];
        System.arraycopy(a, 0, combined, 0, a.length);
        System.arraycopy(b, 0, combined, a.length, b.length);

        byte[] result = new LZ4FrameInputStream(
            new ByteArrayInputStream(baos.toByteArray())).readAllBytes();
        assertArrayEquals(combined, result);
    }

    @Test void frameMagicMismatchThrows() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05};
        assertThrows(LZ4Exception.class,
            () -> new LZ4FrameInputStream(new ByteArrayInputStream(garbage)));
    }

    @Test void frameEmptyStreamThrows() {
        assertThrows(LZ4Exception.class,
            () -> new LZ4FrameInputStream(new ByteArrayInputStream(new byte[0])));
    }

    @Test void frameLargeCompressibleData() throws IOException {
        // 4 MB of zeros — should compress to almost nothing
        byte[] src = new byte[4 * 1024 * 1024];
        byte[] compressed = frameCompress(src);
        assertTrue(compressed.length < 20_000, "4MB zeros should compress to <20KB");
        assertArrayEquals(src, frameDecompress(compressed));
    }

    @Test void frameOutputStreamClosedTwiceIsHarmless() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos);
        lz4.write(42);
        lz4.close();
        lz4.close(); // must not throw
    }

    @Test void frameHeaderIsBySystemLz4Compatible() throws IOException {
        // Verify the frame header bytes match the spec exactly
        // magic=0x184D2204, FLG=0x60, BD=0x60 (1MB), HC=verified
        byte[] compressed = frameCompress("test".getBytes(StandardCharsets.UTF_8));
        assertEquals((byte) 0x04, compressed[0]);
        assertEquals((byte) 0x22, compressed[1]);
        assertEquals((byte) 0x4D, compressed[2]);
        assertEquals((byte) 0x18, compressed[3]);
        assertEquals((byte) 0x60, compressed[4]); // FLG
        assertEquals((byte) 0x60, compressed[5]); // BD = 1MB
        // HC = (xxhash32(FLG‖BD) >> 8) & 0xFF
        byte[] hcInput = {0x60, 0x60};
        int expectedHC = (XXHash32.hash(hcInput, 0, 2) >> 8) & 0xFF;
        assertEquals((byte) expectedHC, compressed[6]);
    }

    @Test void frameLevelConstructorUsesDefaultBlockSize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos, LZ4FrameOutputStream.LEVEL_NORMAL)) {
            lz4.write("test".getBytes(StandardCharsets.UTF_8));
        }
        byte[] compressed = baos.toByteArray();
        assertEquals((byte) 0x60, compressed[5]); // BD = 1MB default block size
    }

    @Test void frameEndsWithEndMark() throws IOException {
        byte[] compressed = frameCompress("end mark test".getBytes(StandardCharsets.UTF_8));
        // last 4 bytes must be 0x00000000
        int n = compressed.length;
        assertEquals(0, compressed[n - 4]);
        assertEquals(0, compressed[n - 3]);
        assertEquals(0, compressed[n - 2]);
        assertEquals(0, compressed[n - 1]);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void assertRoundTrip(byte[] src, int maxChain) {
        byte[] compressed   = LZ4.compress(src, maxChain);
        byte[] decompressed = LZ4.decompress(compressed, src.length);
        assertArrayEquals(src, decompressed,
            "round-trip failed for " + src.length + " bytes, maxChain=" + maxChain);
    }

    private static void assertFrameRoundTrip(byte[] src, int blockSize, int level)
            throws IOException {
        byte[] compressed   = frameCompress(src, blockSize, level);
        byte[] decompressed = frameDecompress(compressed);
        assertArrayEquals(src, decompressed,
            "frame round-trip failed for " + src.length + " bytes, blockSize=" + blockSize);
    }

    static byte[] frameCompress(byte[] src) throws IOException {
        return frameCompress(src, LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE, LZ4FrameOutputStream.LEVEL_FAST);
    }

    static byte[] frameCompress(byte[] src, int blockSize, int level) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos, blockSize, level)) {
            lz4.write(src);
        }
        return baos.toByteArray();
    }

    static byte[] frameDecompress(byte[] src) throws IOException {
        try (LZ4FrameInputStream lz4 = new LZ4FrameInputStream(new ByteArrayInputStream(src))) {
            return lz4.readAllBytes();
        }
    }

    private static byte[] randomBytes(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }
}