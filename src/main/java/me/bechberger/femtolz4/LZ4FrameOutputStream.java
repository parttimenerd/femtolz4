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
 * This stream intentionally writes block-independent frames only; block-dependent
 * encoding is not implemented to keep the encoder simple.
 * The default constructor uses 4 MiB blocks and the fastest compression level.
 *
 * <p>The public compression knob is {@code level} from {@link LZ4#LEVEL_FAST} to
 * {@link LZ4#LEVEL_MAX}. Higher levels spend more effort searching for matches,
 * improving ratio at the cost of CPU time.
 *
 * @see <a href="https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md">LZ4 frame format spec</a>
 */
public final class LZ4FrameOutputStream extends OutputStream {

    private static final int[] BLOCK_SIZES = {64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024};

    /** Default block size: 4 MiB. */
    public static final int DEFAULT_BLOCK_SIZE = 4 << 20;

    private final OutputStream out;
    private final int blockSize;
    private final LZ4.Compressor compressor;
    private final byte[] inputBuf;
    private final byte[] compBuf;
    private int inputPos;
    private boolean headerWritten;
    private boolean closed;

    /**
     * Wraps {@code out} using the given compressor and block size.
     *
     * @param blockSize bytes per block; must be 64 KiB, 256 KiB, 1 MiB, or 4 MiB
     */
    public LZ4FrameOutputStream(OutputStream out, int blockSize, LZ4.Compressor compressor) {
        this.out        = out;
        this.blockSize  = validateBlockSize(blockSize);
        this.compressor = compressor;
        this.inputBuf   = new byte[this.blockSize];
        this.compBuf    = new byte[LZ4.maxCompressedLength(this.blockSize)];
    }

    /** Wraps {@code out} using the given compressor and the default block size. */
    public LZ4FrameOutputStream(OutputStream out, LZ4.Compressor compressor) {
        this(out, DEFAULT_BLOCK_SIZE, compressor);
    }

    /**
     * Wraps {@code out} with the given block size and compression level.
     *
     * @param blockSize bytes per block; must be 64 KiB, 256 KiB, 1 MiB, or 4 MiB
     * @param level compression level from {@link LZ4#LEVEL_FAST} to {@link LZ4#LEVEL_MAX};
     *              values outside this range are clamped
     */
    public LZ4FrameOutputStream(OutputStream out, int blockSize, int level) {
        this(out, blockSize, LZ4.compressor(level));
    }

    /** Wraps {@code out} with the default block size and the given compression level. */
    public LZ4FrameOutputStream(OutputStream out, int level) {
        this(out, DEFAULT_BLOCK_SIZE, level);
    }

    /** Wraps {@code out} with 4 MiB blocks and fastest compression. */
    public LZ4FrameOutputStream(OutputStream out) {
        this(out, DEFAULT_BLOCK_SIZE, LZ4.LEVEL_FAST);
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
            if (inputPos == 0 && len >= blockSize) {
                /* Compress directly from caller's buffer — avoids a full-block arraycopy. */
                flushBlock(b, off, blockSize);
                off += blockSize;
                len -= blockSize;
            } else {
                int chunk = Math.min(len, blockSize - inputPos);
                System.arraycopy(b, off, inputBuf, inputPos, chunk);
                inputPos += chunk;
                off += chunk;
                len -= chunk;
                if (inputPos == blockSize) flushBlock();
            }
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        ensureHeader();
        if (inputPos > 0) flushBlock();
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

    // -------------------------------------------------------------------------

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
        flushBlock(inputBuf, 0, inputPos);
        inputPos = 0;
    }

    private void flushBlock(byte[] src, int srcOff, int srcLen) throws IOException {
        int comp    = compressor.compress(src, srcOff, srcLen, compBuf, 0, compBuf.length);
        boolean raw = comp >= srcLen;
        int payload = raw ? srcLen : comp;
        writeLE32(raw ? (srcLen | 0x80000000) : comp);
        if (raw) {
            out.write(src, srcOff, payload);
        } else {
            out.write(compBuf, 0, payload);
        }
    }

    private void writeLE32(int v) throws IOException {
        out.write( v         & 0xFF);
        out.write((v >>>  8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static int validateBlockSize(int blockSize) {
        if (blockSizeIndex(blockSize) >= 0) return blockSize;
        throw new IllegalArgumentException(
                "blockSize must be 64 KiB, 256 KiB, 1 MiB, or 4 MiB, got: " + blockSize);
    }

    private static int blockSizeIndex(int blockSize) {
        for (int i = 0; i < BLOCK_SIZES.length; i++) {
            if (BLOCK_SIZES[i] == blockSize) return i;
        }
        return -1;
    }

    private static byte bdByte(int blockSize) {
        int index = blockSizeIndex(blockSize);
        if (index >= 0) return (byte) ((index + 4) << 4);
        throw new IllegalArgumentException(
                "blockSize must be 64 KiB, 256 KiB, 1 MiB, or 4 MiB, got: " + blockSize);
    }
}