package me.bechberger.femtolz4;

import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps an {@link InputStream} and reads the standard LZ4 frame format.
 *
 * <p>Usage mirrors {@link java.util.zip.GZIPInputStream}:
 * <pre>{@code
 *   try (var lz4 = new LZ4FrameInputStream(Files.newInputStream(path))) {
 *       byte[] data = lz4.readAllBytes();
 *   }
 * }</pre>
 *
 * <p>Reads the frame header on construction, then decompresses blocks on demand.
 * Handles both compressed and raw (uncompressed) blocks, and transparently
 * concatenated LZ4 frames (multiple back-to-back frames in one stream).
 * Supports both block-independent and block-dependent frame decoding.
 * Verifies frame checksums when present and rejects malformed or truncated input
 * with {@link LZ4Exception}.
 *
 * @see <a href="https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md">LZ4 frame format spec</a>
 */
public final class LZ4FrameInputStream extends InputStream {

    private static final int MAGIC = 0x184D2204;
    private static final int DEP_HISTORY = 64 * 1024;

    private final InputStream in;
    private final XXHash32.Streaming contentHasher = new XXHash32.Streaming();
    private int  blockMaxSize;
    private boolean blockIndependent;
    private boolean hasBlockChecksum;
    private boolean hasContentChecksum;
    private byte[] blockBuf = new byte[0];
    private byte[] compBuf  = new byte[0];  // reusable compressed-block read buffer
    private byte[] depBuf = new byte[0];
    private byte[] history = new byte[DEP_HISTORY];
    private int historyLen;
    private int    blockPos;
    private int    blockLen;
    private boolean eof;

    /**
     * Creates a decompressing stream that reads from {@code in}.
     * The LZ4 frame header is validated immediately.
     *
     * @throws LZ4Exception if the stream does not start with a valid LZ4 frame header
     * @throws IOException  on I/O error
     */
    public LZ4FrameInputStream(InputStream in) throws IOException {
        this.in = in;
        readFrameHeader(true);
    }

    @Override
    public int read() throws IOException {
        if (!refill()) return -1;
        return blockBuf[blockPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (!refill()) return -1;
        int n = Math.min(len, blockLen - blockPos);
        System.arraycopy(blockBuf, blockPos, b, off, n);
        blockPos += n;
        return n;
    }

    @Override
    public int available() {
        if (eof) return 0;
        return blockLen - blockPos;
    }

    @Override
    public void close() throws IOException { in.close(); }

    // ── Private ───────────────────────────────────────────────────────────────

    /** Ensure blockBuf has unread bytes. Returns false on EOF. */
    private boolean refill() throws IOException {
        if (eof) return false;
        while (blockPos >= blockLen) {
            if (!readNextBlock()) return false;
        }
        return true;
    }

    /**
     * Reads and decompresses the next block.
     * On end-mark, looks for another concatenated frame.
     * Returns false on true EOF.
     */
    private boolean readNextBlock() throws IOException {
        int b0 = in.read();
        if (b0 < 0) { eof = true; return false; } // EOF between frames
        int sizeField = b0 | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);

        if (sizeField == 0) {
            // end mark
            if (hasContentChecksum) verifyContentChecksum();
            return tryNextFrame();
        }

        return readBlock(sizeField);
    }

    /**
     * After an end-mark (and its optional content checksum), look for a
     * concatenated LZ4 frame or a skippable frame. Returns false on true EOF.
     */
    private boolean tryNextFrame() throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 < 0) { eof = true; return false; }
            int magic = b0 | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
            if (magic == MAGIC) {
                readFrameHeaderAfterMagic();
                int sizeField = readLE32();
                if (sizeField == 0) {
                    // empty frame — verify its content checksum and try again
                    if (hasContentChecksum) verifyContentChecksum();
                    continue;
                }
                return readBlock(sizeField);
            }
            if ((magic & 0xFFFFFFF0) == 0x184D2A50) {
                // skippable frame: skip size + data, then loop
                int skipSize = readSkippableFrameSize();
                skipBytes(skipSize);
                continue;
            }
            throw new LZ4Exception(
                    "unexpected bytes after end mark (0x" + Integer.toHexString(magic) + ")");
        }
    }

    /** Reads, validates, and decompresses a single block given its already-read size field. */
    private boolean readBlock(int sizeField) throws IOException {
        boolean isRaw      = (sizeField & 0x80000000) != 0;
        int     payloadLen = sizeField & 0x7FFFFFFF;

        if (payloadLen > blockMaxSize)
            throw new LZ4Exception("block size " + payloadLen + " exceeds max " + blockMaxSize);

        if (compBuf.length < payloadLen) compBuf = new byte[payloadLen];
        byte[] payload = compBuf;
        readFully(payload, payloadLen);
        if (hasBlockChecksum) {
            int expected = readLE32();
            int actual = XXHash32.hash(payload, 0, payloadLen);
            if (expected != actual)
                throw new LZ4Exception("block checksum mismatch");
        }

        if (isRaw) {
            blockBuf = payload;
            blockLen = payloadLen;
            if (!blockIndependent) appendHistory(payload, 0, payloadLen);
        } else {
            if (blockIndependent) {
                if (blockBuf.length < blockMaxSize) blockBuf = new byte[blockMaxSize];
                blockLen = LZ4.decompress(payload, 0, payloadLen, blockBuf, 0, blockMaxSize);
            } else {
                int need = historyLen + blockMaxSize;
                if (depBuf.length < need) depBuf = new byte[need];
                System.arraycopy(history, 0, depBuf, 0, historyLen);
                blockLen = LZ4.decompressJavaWithMatchLowerBound(
                        payload, 0, payloadLen, depBuf, historyLen, blockMaxSize, 0);
                if (blockBuf.length < blockLen) blockBuf = new byte[blockLen];
                System.arraycopy(depBuf, historyLen, blockBuf, 0, blockLen);
                appendHistory(blockBuf, 0, blockLen);
            }
        }
        if (hasContentChecksum) contentHasher.update(blockBuf, 0, blockLen);
        blockPos = 0;
        return true;
    }

    private void readFrameHeader(boolean required) throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 < 0) {
                if (required) throw new LZ4Exception("empty stream — no LZ4 frame");
                eof = true;
                return;
            }
            int magic = b0 | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
            if (magic == MAGIC) {
                readFrameHeaderAfterMagic();
                return;
            }
            if ((magic & 0xFFFFFFF0) == 0x184D2A50) {
                // skippable frame: read size, skip data, try again
                int skipSize = readSkippableFrameSize();
                skipBytes(skipSize);
                continue;
            }
            throw new LZ4Exception("not an LZ4 frame (magic=0x" + Integer.toHexString(magic) + ")");
        }
    }

    private void readFrameHeaderAfterMagic() throws IOException {
        int flg = readByte();
        int bd  = readByte();
        int version = (flg >> 6) & 0x3;
        if (version != 1)
            throw new LZ4Exception("unsupported LZ4 frame version: " + version);
        blockIndependent = (flg & 0x20) != 0;
        boolean hasContentSize = (flg & 0x08) != 0; // FLG bit 3
        boolean hasDictId      = (flg & 0x01) != 0;  // FLG bit 0
        hasBlockChecksum       = (flg & 0x10) != 0;  // FLG bit 4
        hasContentChecksum     = (flg & 0x04) != 0;  // FLG bit 2

        // Collect descriptor bytes for header checksum verification.
        // HC = (xxh32(FLG || BD || [ContentSize] || [DictId]) >> 8) & 0xFF
        int descLen = 2 + (hasContentSize ? 8 : 0) + (hasDictId ? 4 : 0);
        byte[] desc = new byte[descLen];
        desc[0] = (byte) flg;
        desc[1] = (byte) bd;
        int dp = 2;
        if (hasContentSize) { for (int i = 0; i < 8; i++) desc[dp++] = (byte) readByte(); }
        if (hasDictId)      { for (int i = 0; i < 4; i++) desc[dp++] = (byte) readByte(); }

        int hc = readByte();
        int expectedHc = (XXHash32.hash(desc, 0, descLen) >> 8) & 0xFF;
        if (hc != expectedHc)
            throw new LZ4Exception("header checksum mismatch");

        blockMaxSize = bdToBlockSize((bd >> 4) & 0x7);
        if (blockBuf.length < blockMaxSize) blockBuf = new byte[blockMaxSize];
        blockLen = 0;
        blockPos = 0;
        historyLen = 0;
        contentHasher.reset();
    }

    private void appendHistory(byte[] data, int off, int len) {
        if (len >= DEP_HISTORY) {
            System.arraycopy(data, off + len - DEP_HISTORY, history, 0, DEP_HISTORY);
            historyLen = DEP_HISTORY;
            return;
        }

        int overflow = historyLen + len - DEP_HISTORY;
        if (overflow > 0) {
            System.arraycopy(history, overflow, history, 0, historyLen - overflow);
            historyLen -= overflow;
        }
        System.arraycopy(data, off, history, historyLen, len);
        historyLen += len;
    }

    private static int bdToBlockSize(int field) {
        return switch (field) {
            case 4 -> 64 * 1024;
            case 5 -> 256 * 1024;
            case 6 -> 1024 * 1024;
            case 7 -> 4 * 1024 * 1024;
            default -> throw new LZ4Exception("unknown BD block-size field: " + field);
        };
    }

    private void verifyContentChecksum() throws IOException {
        int expected = readLE32();
        int actual   = contentHasher.digest();
        if (expected != actual)
            throw new LZ4Exception("content checksum mismatch");
    }

    /** Read a 4-byte little-endian int, throwing on EOF. */
    private int readLE32() throws IOException {
        return readByte() | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
    }

    private int readSkippableFrameSize() throws IOException {
        int size = readLE32();
        if (size < 0) throw new LZ4Exception("skippable frame too large");
        return size;
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new LZ4Exception("unexpected EOF");
        return b;
    }

    private void readFully(byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new LZ4Exception("unexpected EOF in block payload");
            off += n;
        }
    }

    private void skipBytes(int count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                // skip() may return 0; fall back to read
                if (in.read() < 0) throw new LZ4Exception("unexpected EOF");
                count--;
            } else {
                count -= (int) skipped;
            }
        }
    }
}