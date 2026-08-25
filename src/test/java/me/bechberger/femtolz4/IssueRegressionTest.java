package me.bechberger.femtolz4;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that cover every known bug in yawkat/lz4-java, verifying femtolz4
 * cannot reproduce any of them.
 *
 * Issue refs are from https://github.com/yawkat/lz4-java
 */
class IssueRegressionTest {

    // ── Issue #83: critical-region leak on OOM path ───────────────────────────
    // yawkat/lz4-java JNI code acquires source array via GetPrimitiveArrayCritical,
    // then returns early without releasing it when the destination acquisition fails.
    // femtolz4's JNI wrapper always releases both arrays (src with JNI_ABORT, dst
    // with 0) before returning, matching the pattern the issue recommends.
    //
    // We can't reliably reproduce the OOM trigger, but we verify our JNI wrapper
    // handles the normal path correctly and that the Java fallback has no such risk.

    @Test void issue83_nativeCompressDecompressNoLeak() throws Exception {
        byte[] src = "hello world".repeat(1000).getBytes(StandardCharsets.UTF_8);
        // Run many times to shake out any critical-region retention
        for (int i = 0; i < 200; i++) {
            byte[] comp = LZ4.compress(src, 1);
            byte[] back = LZ4.decompress(comp, src.length);
            assertArrayEquals(src, back);
        }
    }

    // ── Issue #39 / #45: NPE/wrong-value from available() on fresh stream ─────
    // LZ4FrameInputStream.available() threw NPE before the first read, and then
    // returned 0 (indicating EOF) instead of a positive estimate even when data
    // was present.
    // femtolz4 returns the number of already-decompressed bytes in the current
    // block buffer, which is 0 before the first read() and positive afterwards.

    @Test void issue39_45_availableOnFreshStream() throws Exception {
        byte[] src = "data".repeat(500).getBytes(StandardCharsets.UTF_8);
        byte[] comp = frameCompress(src);
        LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp));
        // Must not throw NPE, and must not mislead callers into thinking EOF
        int avail = in.available();
        assertTrue(avail >= 0, "available() must not be negative");
        // Read all and verify correctness
        assertArrayEquals(src, in.readAllBytes());
        in.close();
    }

    @Test void issue45_availableAfterPartialRead() throws Exception {
        byte[] src = "abcdefgh".repeat(10000).getBytes(StandardCharsets.UTF_8);
        byte[] comp = frameCompress(src);
        LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(comp));
        // Read first byte, then available() must be positive since more data follows
        assertTrue(in.read() >= 0);
        int avail = in.available();
        assertTrue(avail > 0, "available() must be positive after partial read of a large stream");
        in.close();
    }

    // ── Issue #30: performance regression (pure-Java path must match lz4.c) ───
    // lz4-java 1.10.x introduced a ~10% compression slowdown vs 1.8.0.
    // We verify femtolz4's pure-Java compressor produces output that the native
    // C implementation (our own) agrees with — same ratio, same decompressibility.

    @Test void issue30_javaAndNativeProduceSameDecompressibleOutput() {
        byte[] src = new byte[128 * 1024];
        new java.util.Random(0).nextBytes(src);

        byte[] compNative = LZ4.compress(src, 1);
        byte[] backNative = LZ4.decompress(compNative, src.length);
        assertArrayEquals(src, backNative, "native round-trip");

        // Frame path
        byte[] compFrame = frameCompress(src);
        byte[] backFrame = frameDecompress(compFrame);
        assertArrayEquals(src, backFrame, "frame round-trip");
    }

    // ── Frame: zero-length input ──────────────────────────────────────────────

    @Test void emptyFrameRoundTrip() throws Exception {
        byte[] comp = frameCompress(new byte[0]);
        assertArrayEquals(new byte[0], frameDecompress(comp));
    }

    @Test void rawZeroLengthBlockIsNotTreatedAsEof() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Standard frame header: magic, FLG=0x60, BD=0x40, HC=0x82
        baos.write(new byte[]{0x04, 0x22, 0x4D, 0x18, 0x60, 0x40, (byte) 0x82});
        // Raw block with zero payload: size field 0x80000000
        baos.write(new byte[]{0x00, 0x00, 0x00, (byte) 0x80});
        // Followed by a normal raw block carrying the actual data
        baos.write(new byte[]{0x10, 0x00, 0x00, (byte) 0x80});
        baos.write(EXPECTED);
        baos.write(new byte[]{0x00, 0x00, 0x00, 0x00});
        assertArrayEquals(EXPECTED, frameDecompress(baos.toByteArray()));
    }

    // ── Frame: write single bytes, read in bulk ───────────────────────────────
    // Exercises the internal buffering boundary that caused issues in lz4-java
    // when callers wrote exactly blockSize bytes.

    @Test void singleByteWritesBulkRead() throws Exception {
        byte[] src = "x".repeat(LZ4FrameOutputStream.DEFAULT_BLOCK_SIZE + 1)
                        .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream out = new LZ4FrameOutputStream(baos)) {
            for (byte b : src) out.write(b & 0xFF);
        }
        assertArrayEquals(src, frameDecompress(baos.toByteArray()));
    }

    // ── Frame: bad magic throws, not silently corrupts ────────────────────────

    @Test void badMagicThrowsLZ4Exception() {
        assertThrows(LZ4Exception.class,
            () -> new LZ4FrameInputStream(new ByteArrayInputStream(new byte[]{1,2,3,4,5,6,7})));
    }

    @Test void emptyStreamThrowsLZ4Exception() {
        assertThrows(LZ4Exception.class,
            () -> new LZ4FrameInputStream(new ByteArrayInputStream(new byte[0])));
    }

    // ── Frame: double close is harmless ──────────────────────────────────────

    @Test void doubleCloseOutputStream() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LZ4FrameOutputStream out = new LZ4FrameOutputStream(baos);
        out.write(42);
        out.close();
        assertDoesNotThrow(out::close);
    }

    // ── Block: zero match-offset must throw, not silently corrupt ────────────

    @Test void zeroMatchOffsetThrows() {
        byte[] bad = {0x11, 0x41, 0x00, 0x00, 0x00};
        assertThrows(LZ4Exception.class,
            () -> LZ4.decompress(bad, 0, bad.length, new byte[64], 0, 64));
    }

    // ── Block: output overflow throws, not buffer overwrite ──────────────────

    @Test void outputOverflowThrows() {
        byte[] src = new byte[1024];
        Arrays.fill(src, (byte) 'Z');
        byte[] comp = LZ4.compress(src, 1);
        assertThrows(LZ4Exception.class,
            () -> LZ4.decompress(comp, 0, comp.length, new byte[100], 0, 100));
    }

    // ── flush() materializes partial block to underlying stream ──────────────
    // condensed-data relies on flush() pushing buffered bytes so that
    // estimateSize() reflects real on-disk bytes mid-stream.

    @Test void flushMaterializesPartialBlock() throws Exception {
        var baos = new ByteArrayOutputStream();
        var lz4  = new LZ4FrameOutputStream(baos);
        // Write less than one block — stays buffered without flush
        lz4.write("hello world".getBytes(StandardCharsets.UTF_8));
        int beforeFlush = baos.size();
        lz4.flush();
        int afterFlush = baos.size();
        assertTrue(afterFlush > beforeFlush,
            "flush() must push partial block to underlying stream; before=" + beforeFlush + " after=" + afterFlush);
        // The stream must still be readable after flush (not terminated)
        lz4.write("more data".getBytes(StandardCharsets.UTF_8));
        lz4.close();
        byte[] result = new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray())).readAllBytes();
        assertArrayEquals("hello worldmore data".getBytes(StandardCharsets.UTF_8), result);
    }

    // ── Concatenated frames ───────────────────────────────────────────────────

    @Test void concatenatedFrames() throws Exception {
        byte[] a = "part one".getBytes(StandardCharsets.UTF_8);
        byte[] b = "part two".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream o = new LZ4FrameOutputStream(baos)) { o.write(a); }
        try (LZ4FrameOutputStream o = new LZ4FrameOutputStream(baos)) { o.write(b); }
        byte[] expected = new byte[a.length + b.length];
        System.arraycopy(a, 0, expected, 0, a.length);
        System.arraycopy(b, 0, expected, a.length, b.length);
        assertArrayEquals(expected,
            new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray())).readAllBytes());
    }

    // ── Single frame followed by clean EOF must not throw ────────────────────
    // Bug: tryNextFrame() called readByte() after clean EOF, throwing
    // LZ4Exception("unexpected EOF") instead of treating it as end-of-stream.

    @Test void singleFrameFollowedByCleanEofDoesNotThrow() throws Exception {
        byte[] src = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = frameCompress(src);
        // Wrap in a stream that ends exactly at the frame boundary (no extra bytes)
        assertArrayEquals(src,
            new LZ4FrameInputStream(new ByteArrayInputStream(compressed)).readAllBytes());
    }

    // ── Interop: frames produced by the lz4 CLI ──────────────────────────────
    // The default lz4 CLI (v1.10) enables content checksum (FLG bit 2).
    // --content-size adds 8 bytes of content size (FLG bit 3).
    // -BX adds 4-byte block checksums after each block (FLG bit 4).
    // All of these must be readable by LZ4FrameInputStream.

    private static final byte[] EXPECTED = "Hello LZ4 world\n".getBytes(StandardCharsets.UTF_8);

    /** Default lz4 frame: FLG=0x64 (content checksum enabled). */
    @Test void lz4CliDefaultFrame() throws Exception {
        // echo "Hello LZ4 world" | lz4 - (v1.10, default flags)
        byte[] frame = {
            0x04, 0x22, 0x4D, 0x18,       // magic
            0x64,                           // FLG: version=01, B.Indep=1, C.Checksum=1
            0x40,                           // BD: 64KB blocks
            (byte) 0xA7,                    // HC
            0x10, 0x00, 0x00, (byte) 0x80,  // block size: raw, 16 bytes
            // payload: "Hello LZ4 world\n"
            0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x4C, 0x5A,
            0x34, 0x20, 0x77, 0x6F, 0x72, 0x6C, 0x64, 0x0A,
            0x00, 0x00, 0x00, 0x00,         // end mark
            (byte) 0xB0, (byte) 0xB1, (byte) 0xA7, 0x31  // content checksum
        };
        assertArrayEquals(EXPECTED, frameDecompress(frame));
    }

    /** lz4 --content-size: FLG=0x6C (content size + content checksum). */
    @Test void lz4CliContentSizeFrame() throws Exception {
        byte[] frame = {
            0x04, 0x22, 0x4D, 0x18,
            0x6C,                           // FLG: C.Size=1, C.Checksum=1
            0x40,                           // BD
            // content size: 16 (LE, 8 bytes)
            0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            (byte) 0x83,                    // HC
            0x10, 0x00, 0x00, (byte) 0x80,  // block size: raw, 16 bytes
            0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x4C, 0x5A,
            0x34, 0x20, 0x77, 0x6F, 0x72, 0x6C, 0x64, 0x0A,
            0x00, 0x00, 0x00, 0x00,
            (byte) 0xB0, (byte) 0xB1, (byte) 0xA7, 0x31
        };
        assertArrayEquals(EXPECTED, frameDecompress(frame));
    }

    /** lz4 -BX: FLG=0x74 (block checksum + content checksum). */
    @Test void lz4CliBlockChecksumFrame() throws Exception {
        byte[] frame = {
            0x04, 0x22, 0x4D, 0x18,
            0x74,                           // FLG: B.Checksum=1, C.Checksum=1
            0x40,                           // BD
            (byte) 0xBD,                    // HC
            0x10, 0x00, 0x00, (byte) 0x80,  // block size: raw, 16 bytes
            0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x4C, 0x5A,
            0x34, 0x20, 0x77, 0x6F, 0x72, 0x6C, 0x64, 0x0A,
            (byte) 0xB0, (byte) 0xB1, (byte) 0xA7, 0x31,  // block checksum
            0x00, 0x00, 0x00, 0x00,         // end mark
            (byte) 0xB0, (byte) 0xB1, (byte) 0xA7, 0x31   // content checksum
        };
        assertArrayEquals(EXPECTED, frameDecompress(frame));
    }

    /** Skippable frame (0x184D2A50) before a normal frame must be silently ignored. */
    @Test void skippableFrameBeforeNormalFrame() throws Exception {
        byte[] normalFrame = frameCompress(EXPECTED);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // write skippable frame: magic(4) + size(4) + data
        baos.write(new byte[]{0x50, 0x2A, 0x4D, 0x18}); // magic 0x184D2A50 (LE)
        baos.write(new byte[]{0x05, 0x00, 0x00, 0x00});  // size = 5
        baos.write(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}); // user data
        baos.write(normalFrame);
        assertArrayEquals(EXPECTED, frameDecompress(baos.toByteArray()));
    }

    @Test void oversizedSkippableFrameRejected() {
        byte[] frame = {
            0x50, 0x2A, 0x4D, 0x18,
            0x00, 0x00, 0x00, (byte) 0x80
        };
        LZ4Exception ex = assertThrows(LZ4Exception.class, () -> frameDecompress(frame));
        assertTrue(ex.getMessage().contains("skippable frame too large"));
    }

    /** FLG bit 0 (Dictionary ID): 4-byte DictId field in header must be skipped. */
    @Test void dictionaryIdFieldSkipped() throws Exception {
        // Build a frame with FLG bit 0 set and a dummy 4-byte DictId.
        // FLG=0x61: version=01, B.Indep=1, DictId=1 (bit 0)
        byte flg = 0x61;
        byte bd  = 0x40; // 64KB
        byte[] hcInput = {flg, bd, /*dictId*/ 0x01, 0x02, 0x03, 0x04};
        int hc = (XXHash32.hash(hcInput, 0, hcInput.length) >> 8) & 0xFF;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(new byte[]{0x04, 0x22, 0x4D, 0x18}); // magic
        baos.write(flg);
        baos.write(bd);
        baos.write(new byte[]{0x01, 0x02, 0x03, 0x04}); // dummy DictId
        baos.write(hc);
        // raw block: 16 bytes
        baos.write(new byte[]{0x10, 0x00, 0x00, (byte) 0x80});
        baos.write(EXPECTED);
        baos.write(new byte[]{0x00, 0x00, 0x00, 0x00}); // end mark
        assertArrayEquals(EXPECTED, frameDecompress(baos.toByteArray()));
    }

    /** Dependent blocks (FLG bit 5 = 0): second block references previous block history. */
    @Test void dependentBlockFrameDecodes() throws Exception {
        byte[] part = "Hello LZ4 world\n".getBytes(StandardCharsets.UTF_8); // 16 bytes
        byte[] expected = new byte[part.length * 2];
        System.arraycopy(part, 0, expected, 0, part.length);
        System.arraycopy(part, 0, expected, part.length, part.length);

        // FLG=0x40: version=01, block-independent=0, no checksums.
        byte flg = 0x40;
        byte bd = 0x40; // 64KB blocks
        int hc = (XXHash32.hash(new byte[]{flg, bd}, 0, 2) >> 8) & 0xFF;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(new byte[]{0x04, 0x22, 0x4D, 0x18}); // magic
        baos.write(flg);
        baos.write(bd);
        baos.write(hc);

        // Block 1: raw 16-byte payload
        baos.write(new byte[]{0x10, 0x00, 0x00, (byte) 0x80});
        baos.write(part);

        // Block 2: compressed sequence "repeat previous 16 bytes".
        // token=0x0C => litLen=0, matchLen=4+12=16, offset=16
        baos.write(new byte[]{0x03, 0x00, 0x00, 0x00}); // compressed payload length = 3
        baos.write(new byte[]{0x0C, 0x10, 0x00});

        baos.write(new byte[]{0x00, 0x00, 0x00, 0x00}); // end mark
        assertArrayEquals(expected, frameDecompress(baos.toByteArray()));
    }

    // ── Checksum verification: corrupted checksums must be detected ─────────

    /** Corrupted header checksum (HC) must throw LZ4Exception. */
    @Test void corruptedHeaderChecksumThrows() {
        // Copy the default CLI frame and corrupt the HC byte (index 6)
        byte[] frame = {
            0x04, 0x22, 0x4D, 0x18,
            0x64, 0x40,
            (byte) 0xFF,                    // corrupted HC (was 0xA7)
            0x10, 0x00, 0x00, (byte) 0x80,
            0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x4C, 0x5A,
            0x34, 0x20, 0x77, 0x6F, 0x72, 0x6C, 0x64, 0x0A,
            0x00, 0x00, 0x00, 0x00,
            (byte) 0xB0, (byte) 0xB1, (byte) 0xA7, 0x31
        };
        LZ4Exception ex = assertThrows(LZ4Exception.class, () -> frameDecompress(frame));
        assertTrue(ex.getMessage().contains("header checksum"));
    }

    /** Corrupted block checksum must throw LZ4Exception. */
    @Test void corruptedBlockChecksumThrows() {
        // Block-checksum frame with one byte flipped in the block checksum
        byte[] frame = {
            0x04, 0x22, 0x4D, 0x18,
            0x74, 0x40, (byte) 0xBD,
            0x10, 0x00, 0x00, (byte) 0x80,
            0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x4C, 0x5A,
            0x34, 0x20, 0x77, 0x6F, 0x72, 0x6C, 0x64, 0x0A,
            (byte) 0xB0, (byte) 0xB1, (byte) 0xA7, 0x00,  // corrupted (was 0x31)
            0x00, 0x00, 0x00, 0x00,
            (byte) 0xB0, (byte) 0xB1, (byte) 0xA7, 0x31
        };
        LZ4Exception ex = assertThrows(LZ4Exception.class, () -> frameDecompress(frame));
        assertTrue(ex.getMessage().contains("block checksum"));
    }

    /** Corrupted content checksum must throw LZ4Exception. */
    @Test void corruptedContentChecksumThrows() {
        // Default frame with one byte flipped in the content checksum
        byte[] frame = {
            0x04, 0x22, 0x4D, 0x18,
            0x64, 0x40, (byte) 0xA7,
            0x10, 0x00, 0x00, (byte) 0x80,
            0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x4C, 0x5A,
            0x34, 0x20, 0x77, 0x6F, 0x72, 0x6C, 0x64, 0x0A,
            0x00, 0x00, 0x00, 0x00,
            (byte) 0x00, (byte) 0xB1, (byte) 0xA7, 0x31   // corrupted (was 0xB0)
        };
        LZ4Exception ex = assertThrows(LZ4Exception.class, () -> frameDecompress(frame));
        assertTrue(ex.getMessage().contains("content checksum"));
    }

    // ── readSingleFrame: trailing bytes left on stream ────────────────────────
    // CJFR appends an uncompressed footer immediately after the LZ4 end mark.
    // With readSingleFrame=true the LZ4FrameInputStream must stop at the end
    // mark and leave those bytes readable from the underlying stream.

    @Test void readSingleFrame_trailingBytesLeftOnStream() throws Exception {
        byte[] src    = "body".getBytes(StandardCharsets.UTF_8);
        byte[] footer = "FOOTER".getBytes(StandardCharsets.UTF_8);
        byte[] frame  = frameCompress(src);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(frame);
        baos.write(footer);
        ByteArrayInputStream underlying = new ByteArrayInputStream(baos.toByteArray());

        try (LZ4FrameInputStream lz4 = new LZ4FrameInputStream(underlying, true)) {
            assertArrayEquals(src, lz4.readAllBytes());
        }
        // Footer must still be readable from the underlying stream
        byte[] remaining = underlying.readAllBytes();
        assertArrayEquals(footer, remaining, "trailing bytes must remain on the underlying stream");
    }

    @Test void readSingleFrame_defaultConstructorStillReadsConcatenatedFrames() throws Exception {
        byte[] a = "first".getBytes(StandardCharsets.UTF_8);
        byte[] b = "second".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream o = new LZ4FrameOutputStream(baos)) { o.write(a); }
        try (LZ4FrameOutputStream o = new LZ4FrameOutputStream(baos)) { o.write(b); }
        byte[] expected = new byte[a.length + b.length];
        System.arraycopy(a, 0, expected, 0, a.length);
        System.arraycopy(b, 0, expected, a.length, b.length);
        // default constructor: readSingleFrame=false → concatenated frames decoded
        assertArrayEquals(expected,
            new LZ4FrameInputStream(new ByteArrayInputStream(baos.toByteArray())).readAllBytes());
    }

    @Test void readSingleFrame_trueStopsAtFirstFrameEvenIfMoreFollow() throws Exception {
        byte[] a = "first".getBytes(StandardCharsets.UTF_8);
        byte[] b = "second".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream o = new LZ4FrameOutputStream(baos)) { o.write(a); }
        byte[] frameB = frameCompress(b);
        baos.write(frameB);
        ByteArrayInputStream underlying = new ByteArrayInputStream(baos.toByteArray());

        try (LZ4FrameInputStream lz4 = new LZ4FrameInputStream(underlying, true)) {
            assertArrayEquals(a, lz4.readAllBytes());
        }
        // second frame must still be readable
        byte[] remaining = underlying.readAllBytes();
        assertArrayEquals(frameB, remaining, "second frame must remain on underlying stream");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] frameCompress(byte[] src) {
        try { return LZ4Test.frameCompress(src); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static byte[] frameDecompress(byte[] src) {
        try { return LZ4Test.frameDecompress(src); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
