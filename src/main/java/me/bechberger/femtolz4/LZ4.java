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

    /*
     * Fast-path hash table: long[8192] storing packed (value<<32|pos).
     * bits[63:32] = 4-byte src value at pos, bits[31:0] = position as signed int.
     * Sentinel: 0x8080808080808080L — negative position (high bit of low word set) = empty.
     * Reset with FAST_SENTINEL fill before each block.
     */
    private static final long FAST_SENTINEL = 0x8080808080808080L;
    private static final ThreadLocal<long[]> TL_FAST_HEAD = ThreadLocal.withInitial(() -> {
        long[] t = new long[HASH_SIZE_FAST];
        Arrays.fill(t, FAST_SENTINEL);
        return t;
    });

    /*
     * Chain-path tables: reused across calls to avoid allocation per block.
     * head[] must be filled with NIL before each call.
     * tail[] need not be initialized: only slots reachable from a valid head[] entry are read.
     */
    private static final ThreadLocal<int[]>  TL_CHAIN_HEAD = ThreadLocal.withInitial(() -> new int[HASH_SIZE]);
    private static final ThreadLocal<long[]> TL_CHAIN_TAIL = ThreadLocal.withInitial(() -> new long[WINDOW_SIZE]);

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
        * <p>{@code maxChain} is the pure-Java compressor's search-effort limit: it caps
        * how many previous candidate matches are inspected for each input position.
        * Lower values are faster, higher values usually compress better.
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

    /*
     * Chain compressor (maxChain >= 2).
     *
     * head[h]  = int position of most-recent entry at hash h (NIL = empty)
     * tail[pos & WINDOW_MASK] = long packed as (value<<32)|nextPos
     *   bits[63:32] = 4-byte src value at nextPos (avoids cold src[sv] load)
     *   bits[31:0]  = nextPos as signed int (NIL sentinel when head[h]=NIL)
     *
     * On every chain walk step we only load tail[sv & MASK] — one 64-bit
     * L2-resident read — instead of also loading src[sv] (random L3 miss).
     */
    private static int compressJavaImpl(byte[] src, int srcOff, int srcLen,
                                        byte[] dst, int dstOff, int maxChain) {
        if (maxChain == 1) {
            return compressFast(src, srcOff, srcLen, dst, dstOff);
        }
        if (srcLen == 0) return 0;

        int[]  head    = TL_CHAIN_HEAD.get();
        long[] tail    = TL_CHAIN_TAIL.get();
        Arrays.fill(head, NIL);

        int op       = dstOff;
        int litStart = srcOff;
        int pos      = srcOff;
        int srcEnd   = srcOff + srcLen;
        int safeEnd  = srcEnd - PADDING;
        int safeMain = safeEnd - 1; // pos must be <= safeMain to safely read 5 bytes + do lazy at pos+1

        while (pos <= safeMain) {
            int pos4 = (int) INT_LE.get(src, pos);
            int v    = pos4 ^ ((src[pos + 4] & 0xFF) << 24);
            int h    = (v * 0x9E3779B9) >>> (32 - HASH_BITS);
            int limit     = pos - WINDOW_SIZE;
            int chainLeft = maxChain;
            int bestLen   = 0;
            int bestDist  = 0;

            // Insert pos first
            int prev = head[h];
            tail[pos & WINDOW_MASK] = ((long) pos4 << 32) | (prev & 0xFFFFFFFFL);
            head[h] = pos;

            // Walk chain from second entry
            for (int sv = prev; sv > limit; ) {
                long tslot = tail[sv & WINDOW_MASK];
                int  sv4   = (int)(tslot >>> 32);
                int  next  = (int) tslot;

                if (sv4 != pos4 || src[sv + bestLen] != src[pos + bestLen]) {
                    if (--chainLeft == 0) break;
                    sv = next;
                    if (sv <= limit) break;
                    continue;
                }
                int maxMatch = safeEnd - pos;
                int len = MIN_MATCH;
                while (len + 8 <= maxMatch) {
                    long diff = (long) LONG_LE.get(src, sv + len)
                              ^ (long) LONG_LE.get(src, pos + len);
                    if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                    len += 8;
                }
                while (len < maxMatch && src[sv + len] == src[pos + len]) len++;
                if (len > bestLen) {
                    bestLen = len; bestDist = pos - sv;
                    if (len == maxMatch) break;
                }
                if (--chainLeft == 0) break;
                sv = next;
                if (sv <= limit) break;
            }

            int matchLen  = bestLen;
            int matchDist = bestDist;

            // Lazy matching: try pos+1 if it might do better (pos+1 must also be <= safeMain)
            if (matchLen >= MIN_MATCH && pos < safeMain) {
                int lp   = pos + 1;
                int lp4  = (int) INT_LE.get(src, lp);
                int lv   = lp4 ^ ((src[lp + 4] & 0xFF) << 24);
                int lh   = (lv * 0x9E3779B9) >>> (32 - HASH_BITS);
                int llimit    = lp - WINDOW_SIZE;
                int lchainLeft = maxChain;
                int lazyLen   = 0;
                int lazyDist  = 0;

                int lprev = head[lh];
                tail[lp & WINDOW_MASK] = ((long) lp4 << 32) | (lprev & 0xFFFFFFFFL);
                head[lh] = lp;

                for (int sv = lprev; sv > llimit; ) {
                    long tslot = tail[sv & WINDOW_MASK];
                    int  sv4   = (int)(tslot >>> 32);
                    int  next  = (int) tslot;

                    if (sv4 != lp4 || src[sv + lazyLen] != src[lp + lazyLen]) {
                        if (--lchainLeft == 0) break;
                        sv = next;
                        if (sv <= llimit) break;
                        continue;
                    }
                    int maxMatch = safeEnd - lp;
                    int len = MIN_MATCH;
                    while (len + 8 <= maxMatch) {
                        long diff = (long) LONG_LE.get(src, sv + len)
                                  ^ (long) LONG_LE.get(src, lp + len);
                        if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                        len += 8;
                    }
                    while (len < maxMatch && src[sv + len] == src[lp + len]) len++;
                    if (len > lazyLen) {
                        lazyLen = len; lazyDist = lp - sv;
                        if (len == maxMatch) break;
                    }
                    if (--lchainLeft == 0) break;
                    sv = next;
                    if (sv <= llimit) break;
                }
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
                int insertEnd = litStart < safeEnd + 1 ? litStart : safeEnd + 1;
                for (int ip = pos + 1; ip < insertEnd; ip++) {
                    int ip4 = (int) INT_LE.get(src, ip);
                    int v2  = ip4 ^ ((src[ip + 4] & 0xFF) << 24);
                    int h2  = (v2 * 0x9E3779B9) >>> (32 - HASH_BITS);
                    int prev2 = head[h2];
                    tail[ip & WINDOW_MASK] = ((long) ip4 << 32) | (prev2 & 0xFFFFFFFFL);
                    head[h2] = ip;
                }
                pos = litStart;
            } else {
                pos++;
            }
        }
        // pos is now past safeMain — just advance to srcEnd (tail bytes become literals)
        if (pos < srcEnd) pos = srcEnd;

        int litLen = srcEnd - litStart;
        if (litLen > 0) op = emitSequence(src, litStart, litLen, 0, 0, dst, op);
        return op - dstOff;
    }

    /**
     * chain=1 fast path using a thread-local long[] hash table.
     * Each slot: bits[63:32] = 4-byte src value, bits[31:0] = position (signed).
     * Sentinel 0x8080808080808080L = empty (negative position).
     * Table is filled with sentinel before each call.
     */
    private static int compressFast(byte[] src, int srcOff, int srcLen,
                                    byte[] dst, int dstOff) {
        if (srcLen == 0) return 0;
        long[] head = TL_FAST_HEAD.get();
        Arrays.fill(head, FAST_SENTINEL);

        int op       = dstOff;
        int litStart = srcOff;
        int pos      = srcOff;
        int srcEnd   = srcOff + srcLen;
        int safeEnd  = srcEnd - PADDING;
        int safeEnd2 = safeEnd - 1;

        if (pos < safeEnd2) {
            int v4 = (int) INT_LE.get(src, pos);
            int h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);

            while (pos < safeEnd2) {
                long slot = head[h];
                head[h] = ((long) v4 << 32) | (pos & 0xFFFFFFFFL);

                int sv = (int) slot;
                if (sv > pos - WINDOW_SIZE && (int)(slot >>> 32) == v4) {
                    int maxMatch = safeEnd - pos;
                    int len = MIN_MATCH;
                    while (len + 8 <= maxMatch) {
                        long diff = (long) LONG_LE.get(src, sv + len) ^ (long) LONG_LE.get(src, pos + len);
                        if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                        len += 8;
                    }
                    while (len < maxMatch && src[sv + len] == src[pos + len]) len++;

                    if (len >= MIN_MATCH) {
                        int litLen     = pos - litStart;
                        int matchExtra = len - MIN_MATCH;
                        int matchDist  = pos - sv;
                        dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4)
                                           | (matchExtra < 15 ? matchExtra : 15));
                        if (litLen >= 15) {
                            int rem = litLen - 15;
                            while (rem >= 255) { dst[op++] = (byte) 255; rem -= 255; }
                            dst[op++] = (byte) rem;
                        }
                        if (litLen > 0) { op = copyLiterals(src, litStart, dst, op, litLen); }
                        dst[op++] = (byte) matchDist;
                        dst[op++] = (byte) (matchDist >>> 8);
                        if (matchExtra >= 15) {
                            int rem = matchExtra - 15;
                            while (rem >= 255) { dst[op++] = (byte) 255; rem -= 255; }
                            dst[op++] = (byte) rem;
                        }
                        litStart = pos + len;
                        pos = litStart;
                        if (pos >= safeEnd2) break;
                        v4 = (int) INT_LE.get(src, pos);
                        h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                        continue;
                    }
                }

                pos++;
                if (pos >= safeEnd2) { pos = srcEnd; break; }
                v4 = (int) INT_LE.get(src, pos);
                h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
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
            op = copyLiterals(src, litStart, dst, op, litLen);
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
        return decompressJavaImpl(src, srcOff, srcLen, dst, dstOff, dstLen, dstOff);
    }

    private static int decompressJavaImpl(byte[] src, int srcOff, int srcLen,
                                          byte[] dst, int dstOff, int dstLen) {
        return decompressJavaImpl(src, srcOff, srcLen, dst, dstOff, dstLen, dstOff);
    }

    static int decompressJavaWithMatchLowerBound(byte[] src, int srcOff, int srcLen,
                                                 byte[] dst, int dstOff, int dstLen,
                                                 int matchLowerBound) {
        return decompressJavaImpl(src, srcOff, srcLen, dst, dstOff, dstLen, matchLowerBound);
    }

    private static int decompressJavaImpl(byte[] src, int srcOff, int srcLen,
                                          byte[] dst, int dstOff, int dstLen,
                                          int matchLowerBound) {
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
            if (matchSrc < matchLowerBound) throw new LZ4Exception("match before buffer start");
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

    /**
     * Copy litLen bytes from src[srcPos] to dst[dstPos], returning dstPos+litLen.
     * For small copies, uses overlapping 8-byte VarHandle stores which the JIT
     * lowers to a couple of 64-bit stores — cheaper than the arraycopy intrinsic
     * setup cost for sizes ≤ 32.
     */
    private static int copyLiterals(byte[] src, int srcPos, byte[] dst, int dstPos, int litLen) {
        if (litLen <= 32) {
            if (litLen >= 16) {
                // Write first 16 bytes
                LONG_LE.set(dst, dstPos,     (long) LONG_LE.get(src, srcPos));
                LONG_LE.set(dst, dstPos + 8, (long) LONG_LE.get(src, srcPos + 8));
                // Write last 16 bytes overlapping if needed
                LONG_LE.set(dst, dstPos + litLen - 16, (long) LONG_LE.get(src, srcPos + litLen - 16));
                LONG_LE.set(dst, dstPos + litLen - 8,  (long) LONG_LE.get(src, srcPos + litLen - 8));
            } else if (litLen >= 8) {
                LONG_LE.set(dst, dstPos,                 (long) LONG_LE.get(src, srcPos));
                LONG_LE.set(dst, dstPos + litLen - 8, (long) LONG_LE.get(src, srcPos + litLen - 8));
            } else {
                // 1-7 bytes: write first 4, then last 4 overlapping (or fewer for tiny)
                if (litLen >= 4) {
                    INT_LE.set(dst, dstPos,              (int) INT_LE.get(src, srcPos));
                    INT_LE.set(dst, dstPos + litLen - 4, (int) INT_LE.get(src, srcPos + litLen - 4));
                } else {
                    for (int i = 0; i < litLen; i++) dst[dstPos + i] = src[srcPos + i];
                }
            }
        } else {
            System.arraycopy(src, srcPos, dst, dstPos, litLen);
        }
        return dstPos + litLen;
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
