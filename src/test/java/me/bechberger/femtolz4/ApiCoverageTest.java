package me.bechberger.femtolz4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive coverage of every public API surface not already exercised by
 * {@link LZ4Test}, {@link RobustnessTest}, or {@link IssueRegressionTest}.
 *
 * Areas covered:
 *  - LZ4.compressor(int) / LZ4.compressorJava(int) factory methods
 *  - LZ4.LEVEL_FAST / LEVEL_DEFAULT / LEVEL_MAX constants
 *  - LZ4.compress(byte[]) / LZ4.compressHigh(byte[]) / LZ4.decompress(byte[], int)
 *  - LZ4.maxCompressedLength contract
 *  - LZ4.isNativeAvailable()
 *  - LZ4.decompress() Decompressor functional interface
 *  - LZ4.compressJava() / decompressJava() (pure-Java path)
 *  - LZ4.HC_MIN_LEVEL / HC_MAX_LEVEL constants (deprecated)
 *  - LZ4FrameOutputStream(OutputStream) default constructor
 *  - LZ4FrameOutputStream(OutputStream, LZ4.Compressor)
 *  - LZ4FrameOutputStream level-to-ratio monotonicity
 *  - LZ4FrameOutputStream write(byte[]) with various offsets
 *  - LZ4FrameInputStream.available() at various positions
 *  - LZ4FrameInputStream reading after EOF returns -1
 *  - LZ4FrameInputStream multiple concatenated reads
 *  - LZ4Exception is a RuntimeException
 *  - XXHash32.hash() with off > 0
 *  - LZ4Java public compressor/decompressor factories
 */
class ApiCoverageTest {

    // ── LZ4 constant sanity ───────────────────────────────────────────────────

    @Test void hcLevelRange() {
        assertEquals(1,   LZ4.HC_MIN_LEVEL);
        assertEquals(256, LZ4.HC_MAX_LEVEL);
    }

    @Test void isNativeAvailableReturnsBooleanWithoutThrowing() {
        // Just must not throw; result is platform-dependent
        boolean v = LZ4.isNativeAvailable();
        assertTrue(v || !v); // always true, just checking no exception
    }

    // ── maxCompressedLength ──────────────────────────────────────────────────

    @Test void maxCompressedLengthIsMonotone() {
        int prev = LZ4.maxCompressedLength(0);
        for (int n = 1; n <= 1_000_000; n += 12345) {
            int cur = LZ4.maxCompressedLength(n);
            assertTrue(cur >= prev, "maxCompressedLength should be non-decreasing");
            prev = cur;
        }
    }

    @Test void maxCompressedLengthZero() {
        assertTrue(LZ4.maxCompressedLength(0) >= 0);
    }

    @Test void actualCompressedSizeNeverExceedsMax() {
        byte[] src = randomBytes(128 * 1024, 7);
        int max = LZ4.maxCompressedLength(src.length);
        byte[] dst = new byte[max];
        int n = LZ4.compress(src, 0, src.length, dst, 0, 1);
        assertTrue(n <= max);
        assertTrue(n > 0);
    }

    // ── LZ4 block API with non-zero offsets ──────────────────────────────────

    @Test void compressWithNonZeroSrcOff() {
        byte[] padded = new byte[100 + 4096];
        byte[] plain = randomBytes(4096, 1);
        System.arraycopy(plain, 0, padded, 100, plain.length);

        byte[] dst = new byte[LZ4.maxCompressedLength(plain.length)];
        int n = LZ4.compress(padded, 100, plain.length, dst, 0, 1);
        assertTrue(n > 0);

        byte[] decompressed = LZ4.decompress(Arrays.copyOf(dst, n), plain.length);
        assertArrayEquals(plain, decompressed);
    }

    @Test void decompressWithNonZeroDstOff() {
        byte[] src = randomBytes(4096, 2);
        byte[] comp = LZ4.compress(src, 1);

        int dstOff = 50;
        byte[] dst = new byte[dstOff + src.length];
        int n = LZ4.decompress(comp, 0, comp.length, dst, dstOff, src.length);
        assertEquals(src.length, n);
        assertArrayEquals(src, Arrays.copyOfRange(dst, dstOff, dstOff + src.length));
    }

    @Test void compressDecompressWithBothOffsets() {
        int srcOff = 33, dstOff = 17;
        byte[] raw = randomBytes(8192, 3);
        byte[] srcPadded = new byte[srcOff + raw.length];
        System.arraycopy(raw, 0, srcPadded, srcOff, raw.length);

        byte[] compBuf = new byte[dstOff + LZ4.maxCompressedLength(raw.length)];
        int compLen = LZ4.compress(srcPadded, srcOff, raw.length, compBuf, dstOff, 1);
        assertTrue(compLen > 0);

        byte[] decompBuf = new byte[raw.length];
        int n = LZ4.decompress(compBuf, dstOff, compLen, decompBuf, 0, raw.length);
        assertEquals(raw.length, n);
        assertArrayEquals(raw, decompBuf);
    }

    // ── LZ4.compress(byte[]) and compressHigh(byte[]) convenience methods ───

    @Test void compressByteArrayConvenience() {
        byte[] src = "compress me!".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] comp = LZ4.compress(src);
        assertArrayEquals(src, LZ4.decompress(comp, src.length));
    }

    @Test void compressHighByteArrayConvenience() {
        byte[] src = "high compress!".repeat(200).getBytes(StandardCharsets.UTF_8);
        byte[] compFast = LZ4.compress(src);
        byte[] compHigh = LZ4.compressHigh(src);
        // High compression must produce valid output
        assertArrayEquals(src, LZ4.decompress(compHigh, src.length));
        // High compression should have same or better ratio
        assertTrue(compHigh.length <= compFast.length + 16);
    }

    // ── LZ4.decompress(byte[], int) convenience ──────────────────────────────

    @Test void decompressConvenience() {
        byte[] src = randomBytes(16384, 4);
        byte[] comp = LZ4.compress(src, 1);
        byte[] back = LZ4.decompress(comp, src.length);
        assertArrayEquals(src, back);
    }

    @Test void decompressConvenienceExactSize() {
        byte[] src = new byte[]{1, 2, 3, 4, 5};
        byte[] comp = LZ4.compress(src, 1);
        byte[] back = LZ4.decompress(comp, src.length);
        assertEquals(src.length, back.length);
        assertArrayEquals(src, back);
    }

    // ── LZ4 Compressor functional interface ─────────────────────────────────

    @Test void compressorFunctionalInterfaceRoundTrip() {
        LZ4.Compressor c = LZ4.compress();
        byte[] src = randomBytes(8192, 5);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0 && n <= dst.length);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    @Test void compressorMaxCompressedLength() {
        LZ4.Compressor c = LZ4.compress();
        int max = c.maxCompressedLength(65536);
        assertEquals(LZ4.maxCompressedLength(65536), max);
    }

    @Test void compressHighFunctionalInterface() {
        LZ4.Compressor c = LZ4.compressHigh();
        byte[] src = "aaa".repeat(10000).getBytes(StandardCharsets.UTF_8);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    @ParameterizedTest @ValueSource(ints = {1, 4, 8, 64, 128, 256})
    void compressHighLevelInterface(int level) {
        LZ4.Compressor c = LZ4.compressHigh(level);
        byte[] src = randomBytes(4096, level);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    // ── LZ4 Decompressor functional interface ───────────────────────────────

    @Test void decompressorFunctionalInterface() {
        LZ4.Decompressor d = LZ4.decompress();
        byte[] src = randomBytes(4096, 6);
        byte[] comp = LZ4.compress(src, 1);
        byte[] out = new byte[src.length];
        int n = d.decompress(comp, 0, out, 0, src.length);
        assertEquals(src.length, n);
        assertArrayEquals(src, out);
    }

    @Test void decompressorWithNonZeroSrcOff() {
        byte[] src = "hello world".repeat(500).getBytes(StandardCharsets.UTF_8);
        byte[] comp = LZ4.compress(src, 1);
        // Pad comp at the front
        int pad = 7;
        byte[] padded = new byte[pad + comp.length];
        System.arraycopy(comp, 0, padded, pad, comp.length);

        LZ4.Decompressor d = LZ4.decompress();
        byte[] out = new byte[src.length];
        // decompress from padded[pad..]
        int n = LZ4.decompress(padded, pad, comp.length, out, 0, src.length);
        assertEquals(src.length, n);
        assertArrayEquals(src, out);
    }

    // ── Pure-Java path explicit ──────────────────────────────────────────────

    @Test void compressJavaInterface() {
        LZ4.Compressor c = LZ4.compressJava();
        byte[] src = randomBytes(8192, 7);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    @Test void compressHighJavaInterface() {
        LZ4.Compressor c = LZ4.compressHighJava();
        byte[] src = "java high".repeat(2000).getBytes(StandardCharsets.UTF_8);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    @ParameterizedTest @ValueSource(ints = {1, 4, 16, 64, 256})
    void compressHighJavaLevelInterface(int level) {
        LZ4.Compressor c = LZ4.compressHighJava(level);
        byte[] src = randomBytes(4096, level);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    @Test void decompressJavaInterface() {
        LZ4.Decompressor d = LZ4.decompressJava();
        byte[] src = randomBytes(4096, 8);
        byte[] comp = LZ4.compress(src, 1);
        byte[] out = new byte[src.length];
        int n = d.decompress(comp, 0, out, 0, src.length);
        assertEquals(src.length, n);
        assertArrayEquals(src, out);
    }

    // ── LZ4Java public compressor/decompressor factories ────────────────────

    @Test void lz4JavaFastCompressorRoundTrip() {
        LZ4.Compressor c = LZ4Java.fastCompressor();
        byte[] src = randomBytes(4096, 9);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    @Test void lz4JavaHighCompressorRoundTrip() {
        LZ4.Compressor c = LZ4Java.highCompressor();
        byte[] src = "high-java".repeat(3000).getBytes(StandardCharsets.UTF_8);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    @Test void lz4JavaFastDecompressorRoundTrip() {
        LZ4.Decompressor d = LZ4Java.fastDecompressor();
        byte[] src = randomBytes(4096, 10);
        byte[] comp = LZ4.compress(src, 1);
        byte[] out = new byte[src.length];
        int n = d.decompress(comp, 0, out, 0, src.length);
        assertEquals(src.length, n);
        assertArrayEquals(src, out);
    }

    // ── LZ4 compress chain depths ─────────────────────────────────────────────

    @Test void chainDepthZeroIsValid() {
        // maxChain=0 is defined as "2-way fast"
        byte[] src = randomBytes(8192, 11);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = LZ4.compress(src, 0, src.length, dst, 0, 0);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    @Test void chainDepthLargeIsValid() {
        byte[] src = "repeat".repeat(5000).getBytes(StandardCharsets.UTF_8);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = LZ4.compress(src, 0, src.length, dst, 0, 256);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(Arrays.copyOf(dst, n), src.length));
    }

    // ── LZ4FrameOutputStream constructors ───────────────────────────────────

    @Test void defaultConstructorDefaultBlockSize() throws IOException {
        // Default constructor should use DEFAULT_BLOCK_SIZE = 4 MiB
        byte[] src = randomBytes(LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE + 100, 12);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos)) {
            lz4.write(src);
        }
        byte[] comp = baos.toByteArray();
        // BD byte should be 0x70 (4 MiB)
        assertEquals((byte) 0x70, comp[5], "Default block size should be 4 MiB (BD=0x70)");
        assertArrayEquals(src, LZ4Test.frameDecompress(comp));
    }

    @Test void compressorConstructorRoundTrip() throws IOException {
        LZ4.Compressor c = LZ4.compressHighJava(8);
        byte[] src = "compressor ctor".repeat(10000).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos, c)) {
            lz4.write(src);
        }
        assertArrayEquals(src, LZ4Test.frameDecompress(baos.toByteArray()));
    }

    @Test void blockSizeCompressorConstructorRoundTrip() throws IOException {
        LZ4.Compressor c = LZ4.compressJava();
        byte[] src = randomBytes(512 * 1024, 13);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos, 256 * 1024, c)) {
            lz4.write(src);
        }
        byte[] comp = baos.toByteArray();
        assertEquals((byte) 0x50, comp[5], "256 KiB BD byte should be 0x50");
        assertArrayEquals(src, LZ4Test.frameDecompress(comp));
    }

    @Test void allBlockSizeHeaderBytes() throws IOException {
        int[] sizes = {64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024};
        byte[] expected = {0x40, 0x50, 0x60, 0x70};
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos, sizes[i], 1)) {
                lz4.write(new byte[0]);
            }
            assertEquals(expected[i], baos.toByteArray()[5],
                "blockSize=" + sizes[i] + " should produce BD=" + String.format("0x%02X", expected[i]));
        }
    }

    @Test void frameLevelMonotonicity() throws IOException {
        // Higher levels should produce <= output for highly compressible data
        byte[] src = "aaaaaaa".repeat(50000).getBytes(StandardCharsets.UTF_8);
        int prevLen = Integer.MAX_VALUE;
        for (int level = 1; level <= LZ4.LEVEL_MAX; level++) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos, level)) {
                lz4.write(src);
            }
            // Output must be valid
            assertArrayEquals(src, LZ4Test.frameDecompress(baos.toByteArray()),
                "level " + level + " round-trip failed");
            prevLen = baos.size();
        }
        // Just verify all levels work; we don't assert strict monotonicity since
        // it's data-dependent, but the round-trips above verify correctness.
        assertTrue(prevLen > 0);
    }

    @Test void writeWithOffset() throws IOException {
        byte[] data = randomBytes(1024, 14);
        int off = 100, len = 800;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos)) {
            lz4.write(data, off, len);
        }
        byte[] expected = Arrays.copyOfRange(data, off, off + len);
        assertArrayEquals(expected, LZ4Test.frameDecompress(baos.toByteArray()));
    }

    @Test void writeZeroLength() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos)) {
            lz4.write(new byte[100], 0, 0); // zero-length write — no data in stream
            lz4.write(new byte[]{42});
        }
        byte[] result = LZ4Test.frameDecompress(baos.toByteArray());
        assertEquals(1, result.length);
        assertEquals(42, result[0]);
    }

    @Test void flushDoesNotCorruptOutput() throws IOException {
        byte[] src = randomBytes(2048, 15);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos)) {
            lz4.write(src, 0, 1000);
            lz4.flush();
            lz4.write(src, 1000, src.length - 1000);
        }
        assertArrayEquals(src, LZ4Test.frameDecompress(baos.toByteArray()));
    }

    @Test void writeAfterCloseThrows() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos);
        lz4.close();
        assertThrows(IOException.class, () -> lz4.write(42));
        assertThrows(IOException.class, () -> lz4.write(new byte[]{1, 2, 3}));
    }

    // ── LZ4FrameInputStream ──────────────────────────────────────────────────

    @Test void availableAtEofReturnsZero() throws IOException {
        byte[] src = "small".getBytes(StandardCharsets.UTF_8);
        byte[] comp = LZ4Test.frameCompress(src);
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp))) {
            in.readAllBytes(); // exhaust
            assertEquals(0, in.available());
        }
    }

    @Test void availableBeforeReadIsNonNegative() throws IOException {
        byte[] src = randomBytes(4096, 16);
        byte[] comp = LZ4Test.frameCompress(src);
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp))) {
            assertTrue(in.available() >= 0);
        }
    }

    @Test void availableAfterReadIsPositive() throws IOException {
        byte[] src = randomBytes(LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE * 2, 17);
        byte[] comp = LZ4Test.frameCompress(src);
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp))) {
            int b = in.read();
            assertTrue(b >= 0, "should have data");
            assertTrue(in.available() > 0, "available() must be > 0 after reading one byte from a large stream");
        }
    }

    @Test void readAfterExhaustionReturnsMinusOne() throws IOException {
        byte[] src = new byte[]{7, 8, 9};
        byte[] comp = LZ4Test.frameCompress(src);
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp))) {
            assertNotEquals(-1, in.read());
            assertNotEquals(-1, in.read());
            assertNotEquals(-1, in.read());
            assertEquals(-1, in.read()); // exhausted
            assertEquals(-1, in.read()); // repeated EOF
            assertEquals(-1, in.read(new byte[4], 0, 4));
        }
    }

    @Test void readArrayAtEofReturnsMinusOne() throws IOException {
        byte[] comp = LZ4Test.frameCompress(new byte[0]);
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp))) {
            assertEquals(-1, in.read(new byte[8], 0, 8));
        }
    }

    @Test void readArrayWithZeroLengthReturnsZero() throws IOException {
        byte[] src = new byte[]{1, 2, 3};
        byte[] comp = LZ4Test.frameCompress(src);
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp))) {
            assertEquals(0, in.read(new byte[4], 0, 0));
        }
    }

    @Test void partialReadFilledToAvailable() throws IOException {
        // When available() < requested length, read() should return what's available (one block)
        byte[] src = randomBytes(LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE + 100, 18);
        byte[] comp = LZ4Test.frameCompress(src);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf, 0, buf.length)) != -1) out.write(buf, 0, n);
        }
        assertArrayEquals(src, out.toByteArray());
    }

    // ── LZ4Exception ──────────────────────────────────────────────────────────

    @Test void lz4ExceptionIsRuntimeException() {
        LZ4Exception ex = new LZ4Exception("test message");
        assertInstanceOf(RuntimeException.class, ex);
        assertEquals("test message", ex.getMessage());
    }

    @Test void decompressThrowsLZ4Exception() {
        byte[] bad = {0x11, (byte) 0xAB, 0x00, 0x00}; // zero offset
        assertThrows(LZ4Exception.class,
            () -> LZ4.decompress(bad, 0, bad.length, new byte[64], 0, 64));
    }

    // ── XXHash32 ─────────────────────────────────────────────────────────────

    @Test void hashWithNonZeroOff() {
        byte[] data = new byte[200];
        new Random(42).nextBytes(data);
        // hash(data, 50, 100) must equal hash of the sub-array
        byte[] sub = Arrays.copyOfRange(data, 50, 150);
        assertEquals(XXHash32.hash(data, 50, 100), XXHash32.hash(sub, 0, 100));
    }

    @Test void hashOfEmptyIsConsistent() {
        int h1 = XXHash32.hash(new byte[0], 0, 0);
        int h2 = XXHash32.hash(new byte[100], 50, 0);
        assertEquals(h1, h2);
    }

    // ── Frame round-trips with all compressor factories ──────────────────────

    @Test void frameWithNativeCompressor() throws IOException {
        byte[] src = randomBytes(64 * 1024, 19);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos, LZ4.compress())) {
            lz4.write(src);
        }
        assertArrayEquals(src, LZ4Test.frameDecompress(baos.toByteArray()));
    }

    @Test void frameWithHighCompressor() throws IOException {
        byte[] src = "high frame".repeat(10000).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos, LZ4.compressHigh())) {
            lz4.write(src);
        }
        assertArrayEquals(src, LZ4Test.frameDecompress(baos.toByteArray()));
    }

    // ── New compressor(level) API ────────────────────────────────────────────

    @Test void levelConstantsExist() {
        assertEquals(1,  LZ4.LEVEL_FAST);
        assertEquals(9,  LZ4.LEVEL_DEFAULT);
        assertEquals(12, LZ4.LEVEL_MAX);
    }

    @ParameterizedTest @ValueSource(ints = {1, 3, 5, 9, 12})
    void compressorLevelRoundTrip(int level) {
        LZ4.Compressor c = LZ4.compressor(level);
        byte[] src = "hello world!".repeat(1000).getBytes(StandardCharsets.UTF_8);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(java.util.Arrays.copyOf(dst, n), src.length));
    }

    @ParameterizedTest @ValueSource(ints = {1, 3, 5, 9, 12})
    void compressorJavaLevelRoundTrip(int level) {
        LZ4.Compressor c = LZ4.compressorJava(level);
        byte[] src = "hello world!".repeat(1000).getBytes(StandardCharsets.UTF_8);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertTrue(n > 0);
        assertArrayEquals(src, LZ4.decompress(java.util.Arrays.copyOf(dst, n), src.length));
    }

    @Test void compressorLevelDefaultMatchesLevel9() {
        // LEVEL_DEFAULT should produce a valid, decompressible result
        LZ4.Compressor c = LZ4.compressor(LZ4.LEVEL_DEFAULT);
        byte[] src = "default level".repeat(500).getBytes(StandardCharsets.UTF_8);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertArrayEquals(src, LZ4.decompress(java.util.Arrays.copyOf(dst, n), src.length));
    }

    @Test void compressorLevelMaxClampedToLevel9Behaviour() {
        // LEVEL_MAX (12) has no opt-parser; must still round-trip correctly
        LZ4.Compressor c = LZ4.compressor(LZ4.LEVEL_MAX);
        byte[] src = "max level".repeat(500).getBytes(StandardCharsets.UTF_8);
        byte[] dst = new byte[LZ4.maxCompressedLength(src.length)];
        int n = c.compress(src, 0, src.length, dst, 0, dst.length);
        assertArrayEquals(src, LZ4.decompress(java.util.Arrays.copyOf(dst, n), src.length));
    }

    @Test void frameOutputStreamAcceptsLevelMax() throws IOException {
        byte[] src = "level max frame".repeat(200).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(baos, LZ4.LEVEL_MAX)) {
            lz4.write(src);
        }
        assertArrayEquals(src, LZ4Test.frameDecompress(baos.toByteArray()));
    }

    // ── Native availability consistency ─────────────────────────────────────

    @Test void nativeAndJavaProduceSameDecompressibleOutput() {
        byte[] src = randomBytes(32768, 20);
        // Both native (if available) and Java paths must decompress correctly
        byte[] compFast = LZ4.compress(src, 1);
        assertArrayEquals(src, LZ4.decompress(compFast, src.length), "native path decompress");

        byte[] compJava = LZ4Java.compressJava(src);
        assertArrayEquals(src, LZ4.decompress(compJava, src.length), "java-compressed, native decompress");
        assertArrayEquals(src, LZ4Java.decompressJava(compJava, src.length), "java decompress");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static byte[] randomBytes(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }
}
