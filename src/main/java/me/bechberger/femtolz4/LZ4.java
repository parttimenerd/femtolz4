package me.bechberger.femtolz4;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Pure-Java LZ4 block compressor and decompressor.
 *
 * <p>Direct port of the minimal {@code lz4.c} implementation. These methods
 * operate on raw LZ4 blocks — no frame headers. See {@link LZ4FrameOutputStream}
 * and {@link LZ4FrameInputStream} for the standard framed format.
 */
public final class LZ4 {

    static final int WINDOW_SIZE = 1 << 16;
    private static final int WINDOW_MASK = WINDOW_SIZE - 1;
    private static final int HASH_BITS   = 16;
    private static final int HASH_SIZE   = 1 << HASH_BITS;
    private static final int MIN_MATCH   = 4;
    private static final int PADDING     = 5;
    private static final int NIL         = Integer.MIN_VALUE;

    private static final VarHandle INT_LE  = MethodHandles.byteArrayViewVarHandle(int[].class,  ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** Worst-case output size for {@code srcLen} uncompressed bytes. */
    public static int maxCompressedLength(int srcLen) {
        return srcLen + 16 + (srcLen / 255) + 1;
    }

    // ── Compress ──────────────────────────────────────────────────────────────

    /** True when the native library was loaded successfully. */
    public static boolean isNativeAvailable() { return NativeLZ4.AVAILABLE; }

    /**
     * Compress {@code srcLen} bytes from {@code src[srcOff..]} into {@code dst[dstOff..]}.
     * {@code dst} must hold at least {@link #maxCompressedLength}({@code srcLen}) bytes.
     *
     * <p>Uses the native lz4 library when available (ignores {@code maxChain}),
     * otherwise falls back to the pure-Java implementation.
     *
     * @param maxChain hash-chain depth for pure-Java path: 1 = fastest, ≥64 = better ratio
     * @return number of bytes written into dst
     */
    public static int compress(byte[] src, int srcOff, int srcLen,
                               byte[] dst, int dstOff, int maxChain) {
        if (srcLen == 0) return 0;
        if (NativeLZ4.AVAILABLE) {
            int n = NativeLZ4.compress(src, srcOff, srcLen, dst, dstOff, dst.length - dstOff);
            if (n > 0) return n;
        }
        return compressJavaImpl(src, srcOff, srcLen, dst, dstOff, maxChain);
    }

    private static int compressJavaImpl(byte[] src, int srcOff, int srcLen,
                                        byte[] dst, int dstOff, int maxChain) {        int[] head = new int[HASH_SIZE];
        int[] tail = new int[WINDOW_SIZE];
        Arrays.fill(head, NIL);
        // tail needs no fill: chain walks start from head[h] which is NIL until
        // insert() is called, so uninitialized tail slots are never reached.

        int op       = dstOff;
        int litStart = srcOff;
        int pos      = srcOff;
        int safeEnd  = srcOff + srcLen - PADDING; // last position where we can hash safely

        while (pos < srcOff + srcLen) {
            int matchLen  = 0;
            int matchDist = 0;

            if (pos <= safeEnd - 2) {  // need 2 bytes slack for hash5's 5th byte
                int maxMatch  = safeEnd - pos;
                int limit     = pos - WINDOW_SIZE;
                int chainLeft = maxChain;
                int h         = hash5(src, pos);

                for (int sv = head[h]; sv > limit; sv = tail[sv & WINDOW_MASK]) {
                    if (get4(src, sv) != get4(src, pos)
                            || src[sv + matchLen] != src[pos + matchLen]) {
                        if (--chainLeft == 0) break;
                        continue;
                    }
                    int len = extend(src, sv, pos, maxMatch);
                    if (len > matchLen) {
                        matchLen  = len;
                        matchDist = pos - sv;
                        if (len == maxMatch) break;
                    }
                    if (--chainLeft == 0) break;
                }
            }

            if (matchLen >= MIN_MATCH) {
                int litLen     = pos - litStart;
                int matchExtra = matchLen - MIN_MATCH;
                op = emitSequence(src, litStart, litLen, matchExtra, matchDist, dst, op);
                litStart = pos + matchLen;
                int stride = (maxChain == 1) ? 2 : 1;
                int limit  = Math.min(litStart, safeEnd + 1);
                while (pos < limit)  { insert(head, tail, src, pos); pos += stride; }
                pos = litStart;
            } else {
                if (pos <= safeEnd) insert(head, tail, src, pos);
                pos++;
            }
        }

        // final literal run — no match follows
        int litLen = (srcOff + srcLen) - litStart;
        if (litLen > 0) {
            op = emitSequence(src, litStart, litLen, 0, 0, dst, op);
        }
        return op - dstOff;
    }

    /** Convenience: allocates the output buffer. */
    public static byte[] compress(byte[] src, int maxChain) {
        byte[] dst = new byte[maxCompressedLength(src.length)];
        int len = compress(src, 0, src.length, dst, 0, maxChain);
        return Arrays.copyOf(dst, len);
    }

    /** Pure-Java compress, bypassing the native path. For benchmarking. */
    static byte[] compressJava(byte[] src) {
        byte[] dst = new byte[maxCompressedLength(src.length)];
        int len = compressJavaImpl(src, 0, src.length, dst, 0, 1);
        return Arrays.copyOf(dst, len);
    }

    // ── Decompress ────────────────────────────────────────────────────────────

    /**
     * Decompress an LZ4 block from {@code src[srcOff..srcOff+srcLen)} into
     * {@code dst[dstOff..dstOff+dstLen)}.
     *
     * <p>Uses the native lz4 library when available, otherwise the pure-Java implementation.
     *
     * @return number of bytes written into dst
     * @throws LZ4Exception on malformed input
     */
    public static int decompress(byte[] src, int srcOff, int srcLen,
                                 byte[] dst, int dstOff, int dstLen) {
        if (NativeLZ4.AVAILABLE) {
            int n = NativeLZ4.decompress(src, srcOff, srcLen, dst, dstOff, dstLen);
            if (n >= 0) return n;
            throw new LZ4Exception("native LZ4 decompress failed (error " + n + ")");
        }
        return decompressJavaImpl(src, srcOff, srcLen, dst, dstOff, dstLen);
    }

    private static int decompressJavaImpl(byte[] src, int srcOff, int srcLen,
                                          byte[] dst, int dstOff, int dstLen) {
        int ip     = srcOff;
        int srcEnd = srcOff + srcLen;
        int op     = dstOff;
        int dstEnd = dstOff + dstLen;

        while (ip < srcEnd) {
            int token  = src[ip++] & 0xFF;
            int litLen = token >>> 4;
            int mex    = token & 0xF;

            if (litLen == 15) {
                int b;
                do {
                    if (ip >= srcEnd) throw new LZ4Exception("truncated literal length");
                    b = src[ip++] & 0xFF;
                    litLen += b;
                } while (b == 255);
            }
            if (op + litLen > dstEnd) throw new LZ4Exception("output overflow in literals");
            if (ip + litLen > srcEnd) throw new LZ4Exception("input underflow in literals");
            System.arraycopy(src, ip, dst, op, litLen);
            ip += litLen;
            op += litLen;

            if (ip >= srcEnd) break; // last sequence has no match

            if (ip + 2 > srcEnd) throw new LZ4Exception("truncated match offset");
            int offset = (src[ip] & 0xFF) | ((src[ip + 1] & 0xFF) << 8);
            ip += 2;
            if (offset == 0) throw new LZ4Exception("zero match offset");

            int matchLen = MIN_MATCH + mex;
            if (mex == 15) {
                int b;
                do {
                    if (ip >= srcEnd) throw new LZ4Exception("truncated match length");
                    b = src[ip++] & 0xFF;
                    matchLen += b;
                } while (b == 255);
            }

            int matchSrc = op - offset;
            if (matchSrc < dstOff) throw new LZ4Exception("match before buffer start");
            if (op + matchLen > dstEnd) throw new LZ4Exception("output overflow in match");
            copyMatch(dst, matchSrc, op, matchLen);
            op += matchLen;
        }
        return op - dstOff;
    }

    /** Convenience: decompresses into a freshly allocated buffer of {@code decompressedSize}. */
    public static byte[] decompress(byte[] src, int decompressedSize) {
        byte[] dst = new byte[decompressedSize];
        int n = decompress(src, 0, src.length, dst, 0, decompressedSize);
        return n == decompressedSize ? dst : Arrays.copyOf(dst, n);
    }

    /** Pure-Java decompress, bypassing the native path. For benchmarking. */
    static byte[] decompressJava(byte[] src, int decompressedSize) {
        byte[] dst = new byte[decompressedSize];
        int n = decompressJavaImpl(src, 0, src.length, dst, 0, decompressedSize);
        return n == decompressedSize ? dst : Arrays.copyOf(dst, n);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** 5-byte multiply-shift hash (matches lz4.c). */
    static int hash5(byte[] b, int p) {
        int v = (int) INT_LE.get(b, p) ^ ((b[p + 4] & 0xFF) << 24);
        return (v * 0x9E3779B9) >>> (32 - HASH_BITS);
    }

    static int get4(byte[] b, int p) {
        return (int) INT_LE.get(b, p);
    }

    private static long getLong(byte[] b, int p) {
        return (long) LONG_LE.get(b, p);
    }

    private static void insert(int[] head, int[] tail, byte[] src, int pos) {
        int h = hash5(src, pos);
        tail[pos & WINDOW_MASK] = head[h];
        head[h] = pos;
    }

    private static int extend(byte[] src, int sv, int pos, int maxMatch) {
        int len = MIN_MATCH;
        while (len + 8 <= maxMatch) {
            long diff = getLong(src, sv + len) ^ getLong(src, pos + len);
            if (diff != 0) return len + (Long.numberOfTrailingZeros(diff) >>> 3);
            len += 8;
        }
        while (len < maxMatch && src[sv + len] == src[pos + len]) len++;
        return len;
    }

    private static int emitSequence(byte[] src, int litStart, int litLen,
                                    int matchExtra, int matchDist,
                                    byte[] dst, int op) {
        dst[op++] = (byte) ((Math.min(litLen, 15) << 4) | Math.min(matchExtra, 15));
        if (litLen >= 15)  op = writeOverflow(dst, op, litLen - 15);
        if (litLen > 0)  { System.arraycopy(src, litStart, dst, op, litLen); op += litLen; }
        if (matchDist > 0) {
            dst[op++] = (byte)  matchDist;
            dst[op++] = (byte) (matchDist >>> 8);
            if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
        }
        return op;
    }

    private static int writeOverflow(byte[] dst, int op, int rem) {
        for (; rem >= 255; rem -= 255) dst[op++] = (byte) 255;
        dst[op++] = (byte) rem;
        return op;
    }

    /** Copy match bytes, handling overlap (offset < matchLen). */
    private static void copyMatch(byte[] buf, int src, int dst, int len) {
        for (int i = 0; i < len; i++) buf[dst + i] = buf[src + i];
    }

    private LZ4() {}
}
