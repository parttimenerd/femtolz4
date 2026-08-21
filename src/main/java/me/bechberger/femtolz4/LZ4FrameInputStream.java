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
 *
 * @see <a href="https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md">LZ4 frame format spec</a>
 */
public final class LZ4FrameInputStream extends InputStream {

    private static final int MAGIC = 0x184D2204;

    private final InputStream in;
    private int  blockMaxSize;
    private byte[] blockBuf = new byte[0];
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
        int sizeField = readLE32(false);
        if (sizeField == Integer.MIN_VALUE) { eof = true; return false; } // EOF between frames

        if (sizeField == 0) {
            // end mark — check for a concatenated frame
            int b0 = in.read();
            if (b0 < 0) { eof = true; return false; }
            int magic = b0 | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
            if (magic != MAGIC) throw new LZ4Exception(
                    "unexpected bytes after end mark (0x" + Integer.toHexString(magic) + ")");
            readFrameHeaderAfterMagic();
            sizeField = readLE32(true);
            if (sizeField == 0) { eof = true; return false; }
        }

        boolean isRaw    = (sizeField & 0x80000000) != 0;
        int     payloadLen = sizeField & 0x7FFFFFFF;

        // A well-formed frame never declares a block larger than blockMaxSize
        // (the max size negotiated in BD). Reject up front instead of trying
        // to allocate an attacker-controlled amount of memory for a truncated
        // or malicious stream.
        if (payloadLen < 0 || payloadLen > blockMaxSize)
            throw new LZ4Exception("block size " + payloadLen + " exceeds max " + blockMaxSize);

        byte[] payload = new byte[payloadLen];
        readFully(payload, payloadLen);

        if (isRaw) {
            blockBuf = payload;
            blockLen = payloadLen;
        } else {
            if (blockBuf.length < blockMaxSize) blockBuf = new byte[blockMaxSize];
            blockLen = LZ4.decompress(payload, 0, payloadLen, blockBuf, 0, blockMaxSize);
        }
        blockPos = 0;
        return true;
    }

    private void readFrameHeader(boolean required) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            if (required) throw new LZ4Exception("empty stream — no LZ4 frame");
            eof = true;
            return;
        }
        int magic = b0 | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
        if (magic != MAGIC)
            throw new LZ4Exception("not an LZ4 frame (magic=0x" + Integer.toHexString(magic) + ")");
        readFrameHeaderAfterMagic();
    }

    private void readFrameHeaderAfterMagic() throws IOException {
        /* int flg = */ readByte(); // FLG — we accept any flags (no checksum verification)
        int bd = readByte();
        /* int hc = */ readByte(); // HC — skip checksum verification
        blockMaxSize = bdToBlockSize((bd >> 4) & 0x7);
        if (blockBuf.length < blockMaxSize) blockBuf = new byte[blockMaxSize];
        blockLen = 0;
        blockPos = 0;
    }

    private static int bdToBlockSize(int field) {
        switch (field) {
            case 4: return   64 * 1024;
            case 5: return  256 * 1024;
            case 6: return 1024 * 1024;
            case 7: return 4 * 1024 * 1024;
            default: throw new LZ4Exception("unknown BD block-size field: " + field);
        }
    }

    /**
     * Read a 4-byte LE int.
     * @param required if true, EOF throws; if false, returns {@code Integer.MIN_VALUE} sentinel
     */
    private int readLE32(boolean required) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            if (required) throw new LZ4Exception("truncated stream");
            return Integer.MIN_VALUE;
        }
        return b0 | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
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
}
