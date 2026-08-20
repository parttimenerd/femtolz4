package me.bechberger.femtolz4;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Wraps an {@link OutputStream} and writes the standard LZ4 frame format.
 *
 * <p>Usage mirrors {@link java.util.zip.GZIPOutputStream}:
 * <pre>{@code
 *   try (var lz4 = new LZ4FrameOutputStream(Files.newOutputStream(path))) {
 *       lz4.write(data);
 *   }
 * }</pre>
 *
 * <p>Data is split into independent blocks of up to {@value #DEFAULT_BLOCK_SIZE} bytes
 * (configurable). The output is compatible with the reference {@code lz4} CLI tool
 * and any spec-compliant LZ4 frame decoder.
 */
public final class LZ4FrameOutputStream extends OutputStream {

    /** Default block size: 1 MiB. */
    public static final int DEFAULT_BLOCK_SIZE = 1 << 20;

    /** Fastest compression (single hash probe per position). */
    public static final int LEVEL_FAST   = 1;
    /** Balanced compression (8 hash probes). */
    public static final int LEVEL_NORMAL = 8;

    private final OutputStream out;
    private final int blockSize;
    private final int maxChain;
    private final byte[] inputBuf;
    private final byte[] compBuf;
    private int inputPos;
    private boolean headerWritten;
    private boolean closed;

    /**
     * Wraps {@code out} with the given block size and compression level.
     *
     * @param blockSize bytes per block; must be 64 KiB, 256 KiB, 1 MiB, or 4 MiB
     * @param maxChain  hash-chain depth (use {@link #LEVEL_FAST} or {@link #LEVEL_NORMAL})
     */
    public LZ4FrameOutputStream(OutputStream out, int blockSize, int maxChain) {
        this.out       = out;
        this.blockSize = blockSize;
        this.maxChain  = maxChain;
        this.inputBuf  = new byte[blockSize];
        this.compBuf   = new byte[LZ4.maxCompressedLength(blockSize)];
    }

    /** Wraps {@code out} with 1 MiB blocks and fastest compression. */
    public LZ4FrameOutputStream(OutputStream out) {
        this(out, DEFAULT_BLOCK_SIZE, LEVEL_FAST);
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        ensureHeader();
        inputBuf[inputPos++] = (byte) b;
        if (inputPos == blockSize) flushBlock();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        ensureHeader();
        while (len > 0) {
            int chunk = Math.min(len, blockSize - inputPos);
            System.arraycopy(b, off, inputBuf, inputPos, chunk);
            inputPos += chunk;
            off += chunk;
            len -= chunk;
            if (inputPos == blockSize) flushBlock();
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        out.flush();
    }

    /**
     * Flushes any buffered data, writes the LZ4 frame end mark, and closes the
     * underlying stream. Calling {@code close()} more than once is harmless.
     */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        ensureHeader();
        if (inputPos > 0) flushBlock();
        writeLE32(0);        // end mark
        out.flush();
        out.close();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Stream closed");
    }

    private void ensureHeader() throws IOException {
        if (headerWritten) return;
        headerWritten = true;
        // magic (LE)
        out.write(0x04); out.write(0x22); out.write(0x4D); out.write(0x18);
        // FLG=0x60: version=01, block_independent=1, no content/block checksums
        byte flg = 0x60;
        byte bd  = bdByte(blockSize);
        out.write(flg);
        out.write(bd);
        // HC = (xxhash32(FLG ‖ BD) >> 8) & 0xFF
        byte[] tmp = {flg, bd};
        out.write((XXHash32.hash(tmp, 0, 2) >> 8) & 0xFF);
    }

    private void flushBlock() throws IOException {
        int comp    = LZ4.compress(inputBuf, 0, inputPos, compBuf, 0, maxChain);
        boolean raw = comp >= inputPos;
        int payload = raw ? inputPos : comp;
        writeLE32(raw ? (inputPos | 0x80000000) : comp);
        out.write(raw ? inputBuf : compBuf, 0, payload);
        inputPos = 0;
    }

    private void writeLE32(int v) throws IOException {
        out.write( v         & 0xFF);
        out.write((v >>>  8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static byte bdByte(int blockSize) {
        if (blockSize <=   64 * 1024) return (byte) (4 << 4);
        if (blockSize <=  256 * 1024) return (byte) (5 << 4);
        if (blockSize <= 1024 * 1024) return (byte) (6 << 4);
        return (byte) (7 << 4); // 4 MiB
    }
}
