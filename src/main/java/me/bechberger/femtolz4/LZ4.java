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
 *
 * @see <a href="https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md">LZ4 block format spec</a>
 */
public final class LZ4 {

    static final int WINDOW_SIZE = 1 << 16;
    private static final int WINDOW_MASK = WINDOW_SIZE - 1;
    private static final int HASH_BITS      = 13;
    private static final int HASH_SIZE      = 1 << HASH_BITS;
    private static final int HASH_BITS_FAST = 13;
    private static final int HASH_SIZE_FAST = 1 << HASH_BITS_FAST;
    private static final int MIN_MATCH   = 4;
    private static final int PADDING     = 5;
    private static final int NIL         = Integer.MIN_VALUE;

    private static final VarHandle INT_LE  = MethodHandles.byteArrayViewVarHandle(int[].class,  ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final sun.misc.Unsafe UNSAFE;
    private static final long BYTE_BASE;
    static {
        sun.misc.Unsafe u = null;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (sun.misc.Unsafe) f.get(null);
        } catch (Exception ignored) {}
        UNSAFE    = u;
        BYTE_BASE = u != null ? u.arrayBaseOffset(byte[].class) : 0L;
    }

    /*
     * Generation-tagged hash table for compressFast — avoids Arrays.fill per call.
     * Each int slot: bits[31:16] = generation tag, bits[15:0] = low 16 bits of position.
     * A slot is valid iff (slot >>> 16) == currentGen.  Generation wraps at 65536.
     * The stored position is recovered as: sv = (pos & ~0xFFFF) | (slot & 0xFFFF);
     *                                       if (sv >= pos) sv -= 0x10000;
     */
    private static final ThreadLocal<int[]> TL_FAST_HEAD = ThreadLocal.withInitial(() -> new int[HASH_SIZE_FAST]);
    private static final ThreadLocal<int[]> TL_FAST_GEN  = ThreadLocal.withInitial(() -> new int[]{0});

    /* Reusable compress output buffer — avoids allocation on every call. */
    private static final ThreadLocal<byte[]> TL_DST = ThreadLocal.withInitial(() -> new byte[0]);

    /* Reusable decompress output buffer — avoids allocation on every decompressJava call.
       Grows to the high-water mark; never shrinks so it survives multi-block streams. */
    private static final ThreadLocal<byte[]> TL_DECOMP = ThreadLocal.withInitial(() -> new byte[0]);

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
            int n = NativeLZ4.compress(src, srcOff, srcLen, dst, dstOff, dst.length - dstOff, maxChain);
            if (n > 0) return n;
        }
        return compressJavaImpl(src, srcOff, srcLen, dst, dstOff, maxChain);
    }

    private static int compressJavaImpl(byte[] src, int srcOff, int srcLen,
                                        byte[] dst, int dstOff, int maxChain) {
        if (maxChain == 1) {
            return compressFast(src, srcOff, srcLen, dst, dstOff);
        }

        int[] head = new int[HASH_SIZE];
        int[] tail = new int[WINDOW_SIZE];
        Arrays.fill(head, NIL);
        // tail needs no fill: chain walks start from head[h] which is NIL until
        // insert() is called, so uninitialized tail slots are never reached.

        int op       = dstOff;
        int litStart = srcOff;
        int pos      = srcOff;
        int srcEnd   = srcOff + srcLen;
        int safeEnd  = srcEnd - PADDING; // last position where we can hash safely

        while (pos < srcEnd) {
            int matchLen  = 0;
            int matchDist = 0;

            if (pos <= safeEnd - 2) {  // need 2 bytes slack for hash5's 5th byte
                long r = insertAndMatch(head, tail, src, pos, safeEnd - pos, maxChain);
                matchLen  = (int) (r >>> 32);
                matchDist = (int) r;
            }

            // Lazy matching: only at maxChain>1 (ratio mode).
            if (matchLen >= MIN_MATCH && pos <= safeEnd - 3) {
                int lazyPos = pos + 1;
                long r = insertAndMatch(head, tail, src, lazyPos, safeEnd - lazyPos, maxChain);
                int lazyLen  = (int) (r >>> 32);
                int lazyDist = (int) r;
                if (lazyLen > matchLen) {
                    pos++;
                    matchLen  = lazyLen;
                    matchDist = lazyDist;
                }
            }

            if (matchLen >= MIN_MATCH) {
                int litLen     = pos - litStart;
                int matchExtra = matchLen - MIN_MATCH;
                op = emitSequence(src, litStart, litLen, matchExtra, matchDist, dst, op);
                litStart = pos + matchLen;
                int insertFrom = pos + 1;
                int insertEnd  = litStart < safeEnd + 1 ? litStart : safeEnd + 1;
                while (insertFrom < insertEnd) { insert(head, tail, src, insertFrom); insertFrom++; }
                pos = litStart;
            } else {
                pos++;
            }
        }

        // final literal run — no match follows
        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            op = emitSequence(src, litStart, litLen, 0, 0, dst, op);
        }
        return op - dstOff;
    }

    /**
     * chain=1 fast path using a thread-local int[] hash table.
     * Each slot encodes (gen<<16)|(pos&0xFFFF); no fill needed between calls.
     * extend() and emitSequence() common-case are inlined to stay within JIT
     * inlining budget. Unsafe is used for unaligned int/long loads.
     */
    private static int compressFast(byte[] src, int srcOff, int srcLen,
                                    byte[] dst, int dstOff) {
        int[] head  = TL_FAST_HEAD.get();
        int[] genBox = TL_FAST_GEN.get();
        int gen = (genBox[0] + 1) & 0xFFFF;
        genBox[0] = gen;
        int genTag = gen << 16;   // bits[31:16] of a valid slot

        int op       = dstOff;
        int litStart = srcOff;
        int pos      = srcOff;
        int srcEnd   = srcOff + srcLen;
        int safeEnd  = srcEnd - PADDING;
        int safeEnd2 = safeEnd - 2;
        int skip     = 1;

        while (pos < srcEnd) {
            int matchLen  = 0;
            int matchDist = 0;

            if (pos <= safeEnd2) {
                int limit = pos - WINDOW_SIZE;
                /* 4-byte multiply-shift hash using Unsafe (little-endian, unaligned). */
                int v4 = UNSAFE != null
                    ? UNSAFE.getInt(src, BYTE_BASE + pos)
                    : (int) INT_LE.get(src, pos);
                int h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);

                int slot = head[h];
                /* Write new slot: genTag | low16(pos) */
                head[h] = genTag | (pos & 0xFFFF);

                /* Slot valid iff generation tag matches. */
                if ((slot >>> 16) == gen) {
                    int sv = (pos & ~0xFFFF) | (slot & 0xFFFF);
                    if (sv >= pos) sv -= 0x10000;
                    if (sv >= 0 && sv > limit) {
                        int sv4 = UNSAFE != null
                            ? UNSAFE.getInt(src, BYTE_BASE + sv)
                            : (int) INT_LE.get(src, sv);
                        if (sv4 == v4) {
                            /* Inlined extend(). */
                            int maxMatch = safeEnd - pos;
                            int len = MIN_MATCH;
                            while (len + 8 <= maxMatch) {
                                long svL  = UNSAFE != null
                                    ? UNSAFE.getLong(src, BYTE_BASE + sv  + len)
                                    : (long) LONG_LE.get(src, sv  + len);
                                long posL = UNSAFE != null
                                    ? UNSAFE.getLong(src, BYTE_BASE + pos + len)
                                    : (long) LONG_LE.get(src, pos + len);
                                long diff = svL ^ posL;
                                if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                                len += 8;
                            }
                            while (len < maxMatch && src[sv + len] == src[pos + len]) len++;
                            matchLen  = len;
                            matchDist = pos - sv;
                        }
                    }
                }
            }

            if (matchLen >= MIN_MATCH) {
                skip = 1;
                /* Inlined emitSequence() — common case (litLen<15, matchExtra<15). */
                int litLen     = pos - litStart;
                int matchExtra = matchLen - MIN_MATCH;
                dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4)
                                   | (matchExtra < 15 ? matchExtra : 15));
                if (litLen >= 15) {
                    int rem = litLen - 15;
                    while (rem >= 255) { dst[op++] = (byte) 255; rem -= 255; }
                    dst[op++] = (byte) rem;
                }
                if (litLen > 0) { System.arraycopy(src, litStart, dst, op, litLen); op += litLen; }
                dst[op++] = (byte) matchDist;
                dst[op++] = (byte) (matchDist >>> 8);
                if (matchExtra >= 15) {
                    int rem = matchExtra - 15;
                    while (rem >= 255) { dst[op++] = (byte) 255; rem -= 255; }
                    dst[op++] = (byte) rem;
                }
                litStart = pos + matchLen;
                pos      = litStart;
            } else {
                int step = (skip >> 6) + 1;
                pos += step;
                if (pos > srcEnd) pos = srcEnd;
                if (skip < (17 << 6)) skip++;
            }
        }

        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = (byte) ((litLen < 15 ? litLen : 15) << 4);
            if (litLen >= 15) {
                int rem = litLen - 15;
                while (rem >= 255) { dst[op++] = (byte) 255; rem -= 255; }
                dst[op++] = (byte) rem;
            }
            System.arraycopy(src, litStart, dst, op, litLen);
            op += litLen;
        }
        return op - dstOff;
    }

    /** Convenience: allocates (or reuses) an output buffer and returns a trimmed copy. */
    public static byte[] compress(byte[] src, int maxChain) {
        int maxLen = maxCompressedLength(src.length);
        byte[] dst = TL_DST.get();
        if (dst.length < maxLen) {
            dst = new byte[maxLen];
            TL_DST.set(dst);
        }
        int len = compress(src, 0, src.length, dst, 0, maxChain);
        return Arrays.copyOf(dst, len);
    }

    /** Pure-Java compress, bypassing the native path. For benchmarking. */
    static byte[] compressJava(byte[] src, int maxChain) {
        int maxLen = maxCompressedLength(src.length);
        byte[] dst = TL_DST.get();
        if (dst.length < maxLen) {
            dst = new byte[maxLen];
            TL_DST.set(dst);
        }
        int len = compressJavaImpl(src, 0, src.length, dst, 0, maxChain);
        return Arrays.copyOf(dst, len);
    }

    /** Pure-Java compress at chain=1, bypassing the native path. For benchmarking. */
    static byte[] compressJava(byte[] src) {
        return compressJava(src, 1);
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
            // Native signalled an error — fall through to pure-Java for correct diagnosis.
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
            /* long accumulators: a crafted/truncated block with many 0xFF
               continuation bytes must not be able to overflow a 32-bit
               length into a negative value and slip past the bounds
               checks below (see LZ4 block format spec, "Read the length"). */
            long litLen = token >>> 4;
            int  mex    = token & 0xF;

            if (litLen == 15) {
                int b;
                do {
                    if (ip >= srcEnd) throw new LZ4Exception("truncated literal length");
                    b = src[ip++] & 0xFF;
                    litLen += b;
                } while (b == 255);
            }
            if ((long) op + litLen > dstEnd) throw new LZ4Exception("output overflow in literals");
            if ((long) ip + litLen > srcEnd) throw new LZ4Exception("input underflow in literals");
            System.arraycopy(src, ip, dst, op, (int) litLen);
            ip += (int) litLen;
            op += (int) litLen;

            if (ip >= srcEnd) break; // last sequence has no match

            if (ip + 2 > srcEnd) throw new LZ4Exception("truncated match offset");
            int offset = (src[ip] & 0xFF) | ((src[ip + 1] & 0xFF) << 8);
            ip += 2;
            if (offset == 0) throw new LZ4Exception("zero match offset");

            long matchLen = MIN_MATCH + mex;
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
            if ((long) op + matchLen > dstEnd) throw new LZ4Exception("output overflow in match");
            copyMatch(dst, matchSrc, op, (int) matchLen);
            op += (int) matchLen;
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
        byte[] dst = TL_DECOMP.get();
        if (dst.length < decompressedSize) {
            dst = new byte[decompressedSize];
            TL_DECOMP.set(dst);
        }
        int n = decompressJavaImpl(src, 0, src.length, dst, 0, decompressedSize);
        return Arrays.copyOf(dst, n);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** 5-byte multiply-shift hash (matches lz4.c). */
    static int hash5(byte[] b, int p) {
        int v = (int) INT_LE.get(b, p) ^ ((b[p + 4] & 0xFF) << 24);
        return (v * 0x9E3779B9) >>> (32 - HASH_BITS);
    }

    static int get4(byte[] b, int p) {
        return UNSAFE != null
            ? UNSAFE.getInt(b, BYTE_BASE + p)
            : (int) INT_LE.get(b, p);
    }

    private static long getLong(byte[] b, int p) {
        return UNSAFE != null
            ? UNSAFE.getLong(b, BYTE_BASE + p)
            : (long) LONG_LE.get(b, p);
    }

    private static void insert(int[] head, int[] tail, byte[] src, int pos) {
        int h = hash5(src, pos);
        tail[pos & WINDOW_MASK] = head[h];
        head[h] = pos;
    }

    /**
     * Insert {@code position} into the hash chain and find its best match in
     * one pass (single hash computation). Mirrors the native
     * {@code lz4__insert_and_match} helper.
     *
     * @return {@code (matchLen << 32) | (matchDist & 0xFFFFFFFFL)}; matchLen is 0 if no match found
     */
    private static long insertAndMatch(int[] head, int[] tail, byte[] src,
                                        int position, int maxMatch, int maxChain) {
        int limit     = position - WINDOW_SIZE;
        int chainLeft = maxChain;
        int h         = hash5(src, position);
        // Insert position first so we walk from the second chain entry (single hash).
        tail[position & WINDOW_MASK] = head[h];
        head[h] = position;

        int bestLen  = 0;
        int bestDist = 0;
        for (int sv = tail[position & WINDOW_MASK]; sv > limit; sv = tail[sv & WINDOW_MASK]) {
            if (get4(src, sv) != get4(src, position)
                    || src[sv + bestLen] != src[position + bestLen]) {
                if (--chainLeft == 0) break;
                continue;
            }
            int len = extend(src, sv, position, maxMatch);
            if (len > bestLen) {
                bestLen  = len;
                bestDist = position - sv;
                if (len == maxMatch) break;
            }
            if (--chainLeft == 0) break;
        }
        return ((long) bestLen << 32) | (bestDist & 0xFFFFFFFFL);
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
        dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4) | (matchExtra < 15 ? matchExtra : 15));
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
        int offset = dst - src;
        if (offset >= len) {
            System.arraycopy(buf, src, buf, dst, len);
        } else if (offset == 1) {
            Arrays.fill(buf, dst, dst + len, buf[src]);
        } else {
            int i = 0;
            while (i + offset <= len) {
                System.arraycopy(buf, src + i, buf, dst + i, offset);
                i += offset;
            }
            if (i < len) System.arraycopy(buf, src + i, buf, dst + i, len - i);
        }
    }

    private LZ4() {}
}
