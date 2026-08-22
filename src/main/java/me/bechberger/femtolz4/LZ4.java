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
    private static final int HASH_BITS      = 16;
    private static final int HASH_SIZE      = 1 << HASH_BITS;
    private static final int MIN_MATCH   = 4;
    private static final int PADDING     = 5;
    private static final int NIL         = Integer.MIN_VALUE;

    private static final VarHandle INT_LE  = MethodHandles.byteArrayViewVarHandle(int[].class,  ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /* On AArch64 (Apple M-series, AWS Graviton, etc.) the Java JIT generates tighter
       code than clang -O3 for the fast-path inner loop: measured ~40% faster on M4.
       Use the Java path for chain=1 compress on ARM to avoid the native overhead. */
    private static final boolean IS_AARCH64 =
        System.getProperty("os.arch", "").toLowerCase().contains("aarch64");

    /* 12-bit fast table (long[4096] = 32 KB) fits in L1 on all platforms.
       13-bit (64 KB) was tested on M4/128KB-L1 but still hurts: source data
       working set pushes the combined footprint past L1 effective capacity. */
    private static final int HASH_BITS_FAST = 12;
    private static final int HASH_SIZE_FAST = 1 << HASH_BITS_FAST;

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
     * 2-way associative fast-path table: long[HASH_SIZE_FAST * 2].
     * Each bucket holds 2 slots (LRU eviction). Gives ~2.60x ratio vs chain=1's 2.59x
     * at ~880 MB/s vs chain=1's 1530 MB/s — useful middle-ground between maxChain=1 and maxChain=2.
     * Activated by maxChain=0 (compressJavaImpl dispatch).
     */
    private static final ThreadLocal<long[]> TL_FAST2_HEAD = ThreadLocal.withInitial(() -> {
        long[] t = new long[HASH_SIZE_FAST * 2];
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
     * <p>Uses the native lz4 library when available; falls back to the pure-Java
     * implementation on platforms without a native build.  Both paths honour
     * {@code maxChain}: 1 selects the fast single-probe path; values ≥2 select
     * the hash-chain path with lazy matching up to the given chain depth.
     *
     * <p>{@code maxChain} caps how many previous candidate matches are inspected
     * for each input position.  Lower values are faster; higher values usually
     * compress better.
     *
     * @param maxChain hash-chain depth: 0 = 2-way fast (885 MB/s), 1 = fastest (1530 MB/s),
     *                 2+ = hash-chain with lazy (595 MB/s at chain=2, 470 at chain=8)
     * @return number of bytes written into dst
     */
    public static int compress(byte[] src, int srcOff, int srcLen,
                               byte[] dst, int dstOff, int maxChain) {
        if (srcLen == 0) return 0;
        /* maxChain=0 (2-way associative fast path) is Java-only; native doesn't know this mode. */
        if (NativeLZ4.AVAILABLE && !(IS_AARCH64) && maxChain != 0) {
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
     * load — instead of also loading src[sv] (random L3 miss).
     */
    private static int compressJavaImpl(byte[] src, int srcOff, int srcLen,
                                        byte[] dst, int dstOff, int maxChain) {
        if (maxChain <= 0) {
            return compressFast2Way(src, srcOff, srcLen, dst, dstOff);
        }
        if (maxChain == 1) {
            return compressFast(src, srcOff, srcLen, dst, dstOff);
        }
        if (maxChain == 2) {
            return compressChain2(src, srcOff, srcLen, dst, dstOff);
        }
        if (srcLen == 0) return 0;

        int[]  head    = TL_CHAIN_HEAD.get();
        long[] tail    = TL_CHAIN_TAIL.get();
        Arrays.fill(head, NIL);

        int op        = dstOff;
        int litStart  = srcOff;
        int pos       = srcOff;
        int srcEnd    = srcOff + srcLen;
        int safeEnd   = srcEnd - PADDING;
        int safeMain  = safeEnd - 1;
        int missBytes = 0;
        int skipCtr   = 2 << 6;

        while (pos <= safeMain) {
            int pos4 = (int) INT_LE.get(src, pos);
            int h    = (pos4 * 0x9E3779B9) >>> (32 - HASH_BITS);
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

                if (sv4 != pos4 || (bestLen > 0 && src[sv + bestLen] != src[pos + bestLen])) {
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

            // Lazy matching: try pos+1 only when match is short enough to benefit.
            // Long matches (≥64 bytes) are rarely improved by one position of lookahead.
            boolean lazyProbed = false;
            if (matchLen >= MIN_MATCH && matchLen < 8 && pos < safeMain) {
                int lp   = pos + 1;
                int lp4  = (int) INT_LE.get(src, lp);
                int lh   = (lp4 * 0x9E3779B9) >>> (32 - HASH_BITS);
                int llimit    = lp - WINDOW_SIZE;
                int lchainLeft = Math.min(maxChain, 2);  /* cheap lookahead: 2 probes max */
                int lazyLen   = 0;
                int lazyDist  = 0;

                int lprev = head[lh];
                tail[lp & WINDOW_MASK] = ((long) lp4 << 32) | (lprev & 0xFFFFFFFFL);
                head[lh] = lp;
                lazyProbed = true;

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
                    lazyProbed = false;  // lazy won: pos advanced, insert loop starts normally
                    matchLen  = lazyLen;
                    matchDist = lazyDist;
                }
            }

            if (matchLen >= MIN_MATCH) {
                missBytes = 0;
                skipCtr   = 2 << 6;
                int litLen     = pos - litStart;
                int matchExtra = matchLen - MIN_MATCH;
                // Inline emit sequence
                dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4) | (matchExtra < 15 ? matchExtra : 15));
                if (litLen >= 15)    op = writeOverflow(dst, op, litLen - 15);
                if (litLen > 0) {
                    // Inline copyLiterals
                    if (litLen <= 32) {
                        if (litLen >= 16) {
                            LONG_LE.set(dst, op,           (long) LONG_LE.get(src, litStart));
                            LONG_LE.set(dst, op + 8,       (long) LONG_LE.get(src, litStart + 8));
                            LONG_LE.set(dst, op + litLen - 16, (long) LONG_LE.get(src, litStart + litLen - 16));
                            LONG_LE.set(dst, op + litLen - 8,  (long) LONG_LE.get(src, litStart + litLen - 8));
                        } else if (litLen >= 8) {
                            LONG_LE.set(dst, op,               (long) LONG_LE.get(src, litStart));
                            LONG_LE.set(dst, op + litLen - 8,  (long) LONG_LE.get(src, litStart + litLen - 8));
                        } else if (litLen >= 4) {
                            INT_LE.set(dst, op,               (int) INT_LE.get(src, litStart));
                            INT_LE.set(dst, op + litLen - 4,  (int) INT_LE.get(src, litStart + litLen - 4));
                        } else {
                            for (int ci = 0; ci < litLen; ci++) dst[op + ci] = src[litStart + ci];
                        }
                    } else {
                        System.arraycopy(src, litStart, dst, op, litLen);
                    }
                    op += litLen;
                }
                dst[op++] = (byte)  matchDist;
                dst[op++] = (byte) (matchDist >>> 8);
                if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
                litStart = pos + matchLen;
                int insertEnd = litStart < safeEnd + 1 ? litStart : safeEnd + 1;
                // If lazy probed but lost, pos+1 was already inserted; start at pos+3
                // to avoid reinserting it (which can create a self-link in the chain).
                int insertStart = pos + 1 + (lazyProbed ? 2 : 0);
                for (int ip = insertStart; ip < insertEnd; ip += 2) {
                    int ip4 = (int) INT_LE.get(src, ip);
                    int h2  = (ip4 * 0x9E3779B9) >>> (32 - HASH_BITS);
                    int prev2 = head[h2];
                    tail[ip & WINDOW_MASK] = ((long) ip4 << 32) | (prev2 & 0xFFFFFFFFL);
                    head[h2] = ip;
                }
                pos = litStart;
            } else {
                if (missBytes < 128) {
                    missBytes++;
                    pos++;
                } else {
                    int step = (skipCtr >> 6) + 1;
                    if (skipCtr < (17 << 6)) skipCtr++;
                    missBytes += step;
                    pos += step;
                }
            }
        }
        // pos is now past safeMain — just advance to srcEnd (tail bytes become literals)
        if (pos < srcEnd) pos = srcEnd;

        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4));
            if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
            op = copyLiterals(src, litStart, dst, op, litLen);
        }
        return op - dstOff;
    }

    /*
     * Unrolled chain=2 compressor. Probes exactly 2 chain entries (the head entry
     * plus one step), with both tail[] loads issued back-to-back so out-of-order
     * execution can overlap them. Lazy probe also limited to 2 chain entries.
     * Semantically identical to compressJavaImpl with maxChain=2.
     */
    private static int compressChain2(byte[] src, int srcOff, int srcLen,
                                      byte[] dst, int dstOff) {
        if (srcLen == 0) return 0;

        int[]  head    = TL_CHAIN_HEAD.get();
        long[] tail    = TL_CHAIN_TAIL.get();
        Arrays.fill(head, NIL);

        int op        = dstOff;
        int litStart  = srcOff;
        int pos       = srcOff;
        int srcEnd    = srcOff + srcLen;
        int safeEnd   = srcEnd - PADDING;
        int safeMain  = safeEnd - 1;
        int missBytes = 0;
        int skipCtr   = 2 << 6;

        while (pos <= safeMain) {
            int pos4  = (int) INT_LE.get(src, pos);
            int h     = (pos4 * 0x9E3779B9) >>> (32 - HASH_BITS);
            int limit = pos - WINDOW_SIZE;

            // Insert pos into chain head
            int prev = head[h];
            tail[pos & WINDOW_MASK] = ((long) pos4 << 32) | (prev & 0xFFFFFFFFL);
            head[h] = pos;

            // Speculatively load both tail entries before doing comparisons,
            // giving out-of-order execution maximum overlap on the two loads.
            int  sv1   = prev;
            long tslot1 = (sv1 > limit) ? tail[sv1 & WINDOW_MASK] : 0L;
            int  sv2   = (int) tslot1;
            long tslot2 = (sv1 > limit && sv2 > limit) ? tail[sv2 & WINDOW_MASK] : 0L;

            int bestLen  = 0;
            int bestDist = 0;

            // Evaluate candidate sv1
            if (sv1 > limit) {
                int sv4_1 = (int)(tslot1 >>> 32);
                if (sv4_1 == pos4) {
                    int maxMatch = safeEnd - pos;
                    int len = MIN_MATCH;
                    while (len + 8 <= maxMatch) {
                        long diff = (long) LONG_LE.get(src, sv1 + len)
                                  ^ (long) LONG_LE.get(src, pos + len);
                        if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                        len += 8;
                    }
                    while (len < maxMatch && src[sv1 + len] == src[pos + len]) len++;
                    if (len > bestLen) { bestLen = len; bestDist = pos - sv1; }
                }

                // Evaluate candidate sv2 (second step in chain)
                if (sv2 > limit && (bestLen == 0 || bestLen < safeEnd - pos)) {
                    int sv4_2 = (int)(tslot2 >>> 32);
                    if (sv4_2 == pos4 && (bestLen == 0 || src[sv2 + bestLen] == src[pos + bestLen])) {
                        int maxMatch = safeEnd - pos;
                        int len = MIN_MATCH;
                        while (len + 8 <= maxMatch) {
                            long diff = (long) LONG_LE.get(src, sv2 + len)
                                      ^ (long) LONG_LE.get(src, pos + len);
                            if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                            len += 8;
                        }
                        while (len < maxMatch && src[sv2 + len] == src[pos + len]) len++;
                        if (len > bestLen) { bestLen = len; bestDist = pos - sv2; }
                    }
                }
            }

            int matchLen  = bestLen;
            int matchDist = bestDist;

            // Lazy probe at pos+1 only for very short matches — long matches (≥8 bytes) are
            // rarely improved by one position of lookahead and lazy costs ~15% speed on JFR.
            boolean lazyProbed = false;
            if (matchLen >= MIN_MATCH && matchLen < 8 && pos < safeMain) {
                int lp    = pos + 1;
                int lp4   = (int) INT_LE.get(src, lp);
                int lh    = (lp4 * 0x9E3779B9) >>> (32 - HASH_BITS);
                int llimit = lp - WINDOW_SIZE;

                int lprev = head[lh];
                tail[lp & WINDOW_MASK] = ((long) lp4 << 32) | (lprev & 0xFFFFFFFFL);
                head[lh] = lp;
                lazyProbed = true;

                int   lsv1    = lprev;
                long  ltslot1 = (lsv1 > llimit) ? tail[lsv1 & WINDOW_MASK] : 0L;
                int   lsv2    = (int) ltslot1;
                long  ltslot2 = (lsv1 > llimit && lsv2 > llimit) ? tail[lsv2 & WINDOW_MASK] : 0L;

                int lazyLen  = 0;
                int lazyDist = 0;

                if (lsv1 > llimit) {
                    int lsv4_1 = (int)(ltslot1 >>> 32);
                    if (lsv4_1 == lp4) {
                        int maxMatch = safeEnd - lp;
                        int len = MIN_MATCH;
                        while (len + 8 <= maxMatch) {
                            long diff = (long) LONG_LE.get(src, lsv1 + len)
                                      ^ (long) LONG_LE.get(src, lp  + len);
                            if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                            len += 8;
                        }
                        while (len < maxMatch && src[lsv1 + len] == src[lp + len]) len++;
                        if (len > lazyLen) { lazyLen = len; lazyDist = lp - lsv1; }
                    }

                    if (lsv2 > llimit && (lazyLen == 0 || lazyLen < safeEnd - lp)) {
                        int lsv4_2 = (int)(ltslot2 >>> 32);
                        if (lsv4_2 == lp4 && (lazyLen == 0 || src[lsv2 + lazyLen] == src[lp + lazyLen])) {
                            int maxMatch = safeEnd - lp;
                            int len = MIN_MATCH;
                            while (len + 8 <= maxMatch) {
                                long diff = (long) LONG_LE.get(src, lsv2 + len)
                                          ^ (long) LONG_LE.get(src, lp  + len);
                                if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                                len += 8;
                            }
                            while (len < maxMatch && src[lsv2 + len] == src[lp + len]) len++;
                            if (len > lazyLen) { lazyLen = len; lazyDist = lp - lsv2; }
                        }
                    }
                }

                if (lazyLen > matchLen) {
                    pos++;
                    lazyProbed = false;
                    matchLen  = lazyLen;
                    matchDist = lazyDist;
                }
            }

            if (matchLen >= MIN_MATCH) {
                missBytes = 0;
                skipCtr   = 2 << 6;
                int litLen     = pos - litStart;
                int matchExtra = matchLen - MIN_MATCH;
                dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4) | (matchExtra < 15 ? matchExtra : 15));
                if (litLen >= 15)    op = writeOverflow(dst, op, litLen - 15);
                if (litLen > 0) {
                    if (litLen <= 32) {
                        if (litLen >= 16) {
                            LONG_LE.set(dst, op,           (long) LONG_LE.get(src, litStart));
                            LONG_LE.set(dst, op + 8,       (long) LONG_LE.get(src, litStart + 8));
                            LONG_LE.set(dst, op + litLen - 16, (long) LONG_LE.get(src, litStart + litLen - 16));
                            LONG_LE.set(dst, op + litLen - 8,  (long) LONG_LE.get(src, litStart + litLen - 8));
                        } else if (litLen >= 8) {
                            LONG_LE.set(dst, op,               (long) LONG_LE.get(src, litStart));
                            LONG_LE.set(dst, op + litLen - 8,  (long) LONG_LE.get(src, litStart + litLen - 8));
                        } else if (litLen >= 4) {
                            INT_LE.set(dst, op,               (int) INT_LE.get(src, litStart));
                            INT_LE.set(dst, op + litLen - 4,  (int) INT_LE.get(src, litStart + litLen - 4));
                        } else {
                            for (int ci = 0; ci < litLen; ci++) dst[op + ci] = src[litStart + ci];
                        }
                    } else {
                        System.arraycopy(src, litStart, dst, op, litLen);
                    }
                    op += litLen;
                }
                dst[op++] = (byte)  matchDist;
                dst[op++] = (byte) (matchDist >>> 8);
                if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
                litStart = pos + matchLen;
                int insertEnd   = litStart < safeEnd + 1 ? litStart : safeEnd + 1;
                int insertStart = pos + 1 + (lazyProbed ? 2 : 0);
                for (int ip = insertStart; ip < insertEnd; ip += 2) {
                    int ip4 = (int) INT_LE.get(src, ip);
                    int h2  = (ip4 * 0x9E3779B9) >>> (32 - HASH_BITS);
                    int prev2 = head[h2];
                    tail[ip & WINDOW_MASK] = ((long) ip4 << 32) | (prev2 & 0xFFFFFFFFL);
                    head[h2] = ip;
                }
                pos = litStart;
            } else {
                /* Adaptive skip for incompressible regions: after 128 consecutive miss-bytes,
                   apply yawkat-style exponential skip to avoid O(n) chain walks per byte. */
                if (missBytes < 128) {
                    missBytes++;
                    pos++;
                } else {
                    int step = (skipCtr >> 6) + 1;
                    if (skipCtr < (17 << 6)) skipCtr++;
                    missBytes += step;
                    pos += step;
                }
            }
        }
        if (pos < srcEnd) pos = srcEnd;

        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4));
            if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
            op = copyLiterals(src, litStart, dst, op, litLen);
        }
        return op - dstOff;
    }

    /*
     * 2-way associative fast path (maxChain=0).
     * Each bucket holds 2 slots. On lookup, check both; on insert, shift old slot0→slot1,
     * new entry→slot0 (LRU eviction). Gives ~2.60x ratio vs chain=1's 2.59x at ~880 MB/s.
     * Table: long[HASH_SIZE_FAST * 2] = 64KB. Reset to sentinel before each block.
     */
    private static int compressFast2Way(byte[] src, int srcOff, int srcLen,
                                        byte[] dst, int dstOff) {
        if (srcLen == 0) return 0;
        long[] head = TL_FAST2_HEAD.get();
        Arrays.fill(head, FAST_SENTINEL);

        int op        = dstOff;
        int litStart  = srcOff;
        int pos       = srcOff;
        int srcEnd    = srcOff + srcLen;
        int safeEnd   = srcEnd - PADDING;
        int safeEnd2  = safeEnd - 1;
        int missBytes = 0;
        int skipCtr   = 2 << 6;

        if (pos < safeEnd2) {
            int v4 = (int) INT_LE.get(src, pos);
            int h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);

            outer:
            while (pos < safeEnd2) {
                int bi = h << 1;

                /* ── Step A: evaluate pos ─────────────────────────── */
                long s0 = head[bi], s1 = head[bi + 1];
                /* Insert: shift s0→s1, new entry→s0 */
                head[bi + 1] = s0;
                head[bi]     = ((long) v4 << 32) | (pos & 0xFFFFFFFFL);

                /* Speculatively compute pos+1 hash */
                int v4_1 = (int) INT_LE.get(src, pos + 1);
                int h1   = (v4_1 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);

                /* Try slot 0 */
                int sv = (int) s0;
                int matchSv = -1, matchLen = 0;
                if (sv > pos - WINDOW_SIZE & (int)(s0 >>> 32) == v4) {
                    int mm = safeEnd - pos, len = MIN_MATCH;
                    while (len + 8 <= mm) {
                        long diff = (long) LONG_LE.get(src, sv + len) ^ (long) LONG_LE.get(src, pos + len);
                        if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                        len += 8;
                    }
                    while (len < mm && src[sv + len] == src[pos + len]) len++;
                    if (len >= MIN_MATCH) { matchSv = sv; matchLen = len; }
                }
                /* Try slot 1 only if slot 0 missed or slot 1 might be better */
                int sv1 = (int) s1;
                if (sv1 != sv && sv1 > pos - WINDOW_SIZE & (int)(s1 >>> 32) == v4) {
                    if (matchLen == 0 || src[sv1 + matchLen] == src[pos + matchLen]) {
                        int mm = safeEnd - pos, len = MIN_MATCH;
                        while (len + 8 <= mm) {
                            long diff = (long) LONG_LE.get(src, sv1 + len) ^ (long) LONG_LE.get(src, pos + len);
                            if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                            len += 8;
                        }
                        while (len < mm && src[sv1 + len] == src[pos + len]) len++;
                        if (len > matchLen) { matchSv = sv1; matchLen = len; }
                    }
                }

                if (matchLen >= MIN_MATCH) {
                    missBytes = 0;
                    skipCtr   = 2 << 6;
                    int litLen     = pos - litStart;
                    int matchExtra = matchLen - MIN_MATCH;
                    int matchDist  = pos - matchSv;
                    dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4)
                                       | (matchExtra < 15 ? matchExtra : 15));
                    if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
                    if (litLen > 0) {
                        if (litLen <= 32) {
                            if (litLen >= 16) {
                                LONG_LE.set(dst, op,           (long) LONG_LE.get(src, litStart));
                                LONG_LE.set(dst, op + 8,       (long) LONG_LE.get(src, litStart + 8));
                                LONG_LE.set(dst, op + litLen - 16, (long) LONG_LE.get(src, litStart + litLen - 16));
                                LONG_LE.set(dst, op + litLen - 8,  (long) LONG_LE.get(src, litStart + litLen - 8));
                            } else if (litLen >= 8) {
                                LONG_LE.set(dst, op,               (long) LONG_LE.get(src, litStart));
                                LONG_LE.set(dst, op + litLen - 8,  (long) LONG_LE.get(src, litStart + litLen - 8));
                            } else if (litLen >= 4) {
                                INT_LE.set(dst, op,               (int) INT_LE.get(src, litStart));
                                INT_LE.set(dst, op + litLen - 4,  (int) INT_LE.get(src, litStart + litLen - 4));
                            } else {
                                for (int i = 0; i < litLen; i++) dst[op + i] = src[litStart + i];
                            }
                        } else {
                            System.arraycopy(src, litStart, dst, op, litLen);
                        }
                        op += litLen;
                    }
                    dst[op++] = (byte) matchDist;
                    dst[op++] = (byte) (matchDist >>> 8);
                    if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
                    litStart = pos + matchLen;
                    pos = litStart;
                    if (pos >= safeEnd2) break outer;
                    v4 = (int) INT_LE.get(src, pos);
                    h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                    continue outer;
                }

                /* ── Step A miss: evaluate pos+1 ── */
                int bi1 = h1 << 1;
                long ss0 = head[bi1], ss1 = head[bi1 + 1];
                head[bi1 + 1] = ss0;
                head[bi1]     = ((long) v4_1 << 32) | ((pos + 1) & 0xFFFFFFFFL);
                pos++;
                if (pos >= safeEnd2) { pos = srcEnd; break outer; }

                int svA = (int) ss0;
                matchSv = -1; matchLen = 0;
                if (svA > pos - WINDOW_SIZE & (int)(ss0 >>> 32) == v4_1) {
                    int mm = safeEnd - pos, len = MIN_MATCH;
                    while (len + 8 <= mm) {
                        long diff = (long) LONG_LE.get(src, svA + len) ^ (long) LONG_LE.get(src, pos + len);
                        if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                        len += 8;
                    }
                    while (len < mm && src[svA + len] == src[pos + len]) len++;
                    if (len >= MIN_MATCH) { matchSv = svA; matchLen = len; }
                }
                int svB = (int) ss1;
                if (svB != svA && svB > pos - WINDOW_SIZE & (int)(ss1 >>> 32) == v4_1) {
                    if (matchLen == 0 || src[svB + matchLen] == src[pos + matchLen]) {
                        int mm = safeEnd - pos, len = MIN_MATCH;
                        while (len + 8 <= mm) {
                            long diff = (long) LONG_LE.get(src, svB + len) ^ (long) LONG_LE.get(src, pos + len);
                            if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                            len += 8;
                        }
                        while (len < mm && src[svB + len] == src[pos + len]) len++;
                        if (len > matchLen) { matchSv = svB; matchLen = len; }
                    }
                }

                if (matchLen >= MIN_MATCH) {
                    missBytes = 0;
                    skipCtr   = 2 << 6;
                    int litLen     = pos - litStart;
                    int matchExtra = matchLen - MIN_MATCH;
                    int matchDist  = pos - matchSv;
                    dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4)
                                       | (matchExtra < 15 ? matchExtra : 15));
                    if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
                    if (litLen > 0) {
                        if (litLen <= 32) {
                            if (litLen >= 16) {
                                LONG_LE.set(dst, op,                (long) LONG_LE.get(src, litStart));
                                LONG_LE.set(dst, op + 8,            (long) LONG_LE.get(src, litStart + 8));
                                LONG_LE.set(dst, op + litLen - 16,  (long) LONG_LE.get(src, litStart + litLen - 16));
                                LONG_LE.set(dst, op + litLen - 8,   (long) LONG_LE.get(src, litStart + litLen - 8));
                            } else if (litLen >= 8) {
                                LONG_LE.set(dst, op,                (long) LONG_LE.get(src, litStart));
                                LONG_LE.set(dst, op + litLen - 8,   (long) LONG_LE.get(src, litStart + litLen - 8));
                            } else if (litLen >= 4) {
                                INT_LE.set(dst, op,                (int) INT_LE.get(src, litStart));
                                INT_LE.set(dst, op + litLen - 4,   (int) INT_LE.get(src, litStart + litLen - 4));
                            } else {
                                for (int i = 0; i < litLen; i++) dst[op + i] = src[litStart + i];
                            }
                        } else {
                            System.arraycopy(src, litStart, dst, op, litLen);
                        }
                        op += litLen;
                    }
                    dst[op++] = (byte) matchDist;
                    dst[op++] = (byte) (matchDist >>> 8);
                    if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
                    litStart = pos + matchLen;
                    pos = litStart;
                    if (pos >= safeEnd2) break outer;
                    v4 = (int) INT_LE.get(src, pos);
                    h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                    continue outer;
                }

                /* Both pos and pos+1 missed — apply adaptive skip. */
                if (missBytes < 128) {
                    missBytes += 2;
                    pos++;
                } else {
                    int step = (skipCtr >> 6) + 1;
                    if (skipCtr < (17 << 6)) skipCtr++;
                    missBytes += step;
                    pos += step;
                }
                if (pos >= safeEnd2) { pos = srcEnd; break outer; }
                v4 = (int) INT_LE.get(src, pos);
                h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
            }
        }

        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = (byte) ((litLen < 15 ? litLen : 15) << 4);
            if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
            op = copyLiterals(src, litStart, dst, op, litLen);
        }
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

        int op        = dstOff;
        int litStart  = srcOff;
        int pos       = srcOff;
        int srcEnd    = srcOff + srcLen;
        int safeEnd   = srcEnd - PADDING;
        int safeEnd2  = safeEnd - 1;
        /* Skip counter for incompressible regions.
           After 128 consecutive miss-bytes, activate yawkat-style skip (no insert of skipped
           positions) to achieve 10x+ throughput on uncompressible data. Resets on any match.
           Below the threshold the 2-step loop runs exactly as before — zero cost on JFR data. */
        int missBytes = 0;
        int skipCtr   = 2 << 6;  // yawkat-style packed counter; step = (skipCtr >> 6) + 1

        if (pos < safeEnd2) {
            int v4 = (int) INT_LE.get(src, pos);
            int h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);

            while (pos < safeEnd2) {
                /* ── Step A: evaluate pos ─────────────────────────── */
                long slot = head[h];
                head[h] = ((long) v4 << 32) | (pos & 0xFFFFFFFFL);

                int sv = (int) slot;
                if (sv > pos - WINDOW_SIZE & (int)(slot >>> 32) == v4) {
                    int maxMatch = safeEnd - pos;
                    int len = MIN_MATCH;
                    while (len + 8 <= maxMatch) {
                        long diff = (long) LONG_LE.get(src, sv + len) ^ (long) LONG_LE.get(src, pos + len);
                        if (diff != 0) { len += Long.numberOfTrailingZeros(diff) >>> 3; break; }
                        len += 8;
                    }
                    while (len < maxMatch && src[sv + len] == src[pos + len]) len++;

                    if (len >= MIN_MATCH) {
                        missBytes = 0;
                        skipCtr   = 2 << 6;
                        int litLen     = pos - litStart;
                        int matchExtra = len - MIN_MATCH;
                        int matchDist  = pos - sv;
                        dst[op++] = (byte) (((litLen < 15 ? litLen : 15) << 4)
                                           | (matchExtra < 15 ? matchExtra : 15));
                        if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
                        if (litLen > 0) {
                            if (litLen <= 32) {
                                if (litLen >= 16) {
                                    LONG_LE.set(dst, op,           (long) LONG_LE.get(src, litStart));
                                    LONG_LE.set(dst, op + 8,       (long) LONG_LE.get(src, litStart + 8));
                                    LONG_LE.set(dst, op + litLen - 16, (long) LONG_LE.get(src, litStart + litLen - 16));
                                    LONG_LE.set(dst, op + litLen - 8,  (long) LONG_LE.get(src, litStart + litLen - 8));
                                } else if (litLen >= 8) {
                                    LONG_LE.set(dst, op,               (long) LONG_LE.get(src, litStart));
                                    LONG_LE.set(dst, op + litLen - 8,  (long) LONG_LE.get(src, litStart + litLen - 8));
                                } else if (litLen >= 4) {
                                    INT_LE.set(dst, op,               (int) INT_LE.get(src, litStart));
                                    INT_LE.set(dst, op + litLen - 4,  (int) INT_LE.get(src, litStart + litLen - 4));
                                } else {
                                    for (int i = 0; i < litLen; i++) dst[op + i] = src[litStart + i];
                                }
                            } else {
                                System.arraycopy(src, litStart, dst, op, litLen);
                            }
                            op += litLen;
                        }
                        dst[op++] = (byte) matchDist;
                        dst[op++] = (byte) (matchDist >>> 8);
                        if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
                        litStart = pos + len;
                        pos = litStart;
                        if (pos >= safeEnd2) break;
                        v4 = (int) INT_LE.get(src, pos);
                        h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                        continue;
                    }
                }

                /* ── Step A miss: compute pos+1 hash, advance to pos+1 ── */
                int v4_1 = (int) INT_LE.get(src, pos + 1);
                int h1   = (v4_1 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                pos++;
                if (pos >= safeEnd2) { pos = srcEnd; break; }

                /* ── Step B: evaluate pos+1 using hash computed above ── */
                long slot1 = head[h1];
                head[h1] = ((long) v4_1 << 32) | (pos & 0xFFFFFFFFL);

                int sv1 = (int) slot1;
                if (sv1 > pos - WINDOW_SIZE & (int)(slot1 >>> 32) == v4_1) {
                    int maxMatch1 = safeEnd - pos;
                    int len1 = MIN_MATCH;
                    while (len1 + 8 <= maxMatch1) {
                        long diff1 = (long) LONG_LE.get(src, sv1 + len1) ^ (long) LONG_LE.get(src, pos + len1);
                        if (diff1 != 0) { len1 += Long.numberOfTrailingZeros(diff1) >>> 3; break; }
                        len1 += 8;
                    }
                    while (len1 < maxMatch1 && src[sv1 + len1] == src[pos + len1]) len1++;

                    if (len1 >= MIN_MATCH) {
                        missBytes = 0;
                        skipCtr   = 2 << 6;
                        int litLen1     = pos - litStart;
                        int matchExtra1 = len1 - MIN_MATCH;
                        int matchDist1  = pos - sv1;
                        dst[op++] = (byte) (((litLen1 < 15 ? litLen1 : 15) << 4)
                                           | (matchExtra1 < 15 ? matchExtra1 : 15));
                        if (litLen1 >= 15) op = writeOverflow(dst, op, litLen1 - 15);
                        if (litLen1 > 0) {
                            if (litLen1 <= 32) {
                                if (litLen1 >= 16) {
                                    LONG_LE.set(dst, op,               (long) LONG_LE.get(src, litStart));
                                    LONG_LE.set(dst, op + 8,           (long) LONG_LE.get(src, litStart + 8));
                                    LONG_LE.set(dst, op + litLen1 - 16, (long) LONG_LE.get(src, litStart + litLen1 - 16));
                                    LONG_LE.set(dst, op + litLen1 - 8,  (long) LONG_LE.get(src, litStart + litLen1 - 8));
                                } else if (litLen1 >= 8) {
                                    LONG_LE.set(dst, op,                (long) LONG_LE.get(src, litStart));
                                    LONG_LE.set(dst, op + litLen1 - 8,  (long) LONG_LE.get(src, litStart + litLen1 - 8));
                                } else if (litLen1 >= 4) {
                                    INT_LE.set(dst, op,                (int) INT_LE.get(src, litStart));
                                    INT_LE.set(dst, op + litLen1 - 4,  (int) INT_LE.get(src, litStart + litLen1 - 4));
                                } else {
                                    for (int i = 0; i < litLen1; i++) dst[op + i] = src[litStart + i];
                                }
                            } else {
                                System.arraycopy(src, litStart, dst, op, litLen1);
                            }
                            op += litLen1;
                        }
                        dst[op++] = (byte) matchDist1;
                        dst[op++] = (byte) (matchDist1 >>> 8);
                        if (matchExtra1 >= 15) op = writeOverflow(dst, op, matchExtra1 - 15);
                        litStart = pos + len1;
                        pos = litStart;
                        if (pos >= safeEnd2) break;
                        v4 = (int) INT_LE.get(src, pos);
                        h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                        continue;
                    }
                }

                /* Both pos and pos+1 missed — check threshold for skip activation. */
                if (missBytes < 128) {
                    /* Still in compressible-data mode: exact 2-step (no skip). */
                    missBytes += 2;
                    pos++;
                } else {
                    /* Incompressible region: yawkat-style skip (no insert of skipped positions). */
                    int step = (skipCtr >> 6) + 1;
                    if (skipCtr < (17 << 6)) skipCtr++;
                    missBytes += step;
                    pos += step;
                }
                if (pos >= safeEnd2) { pos = srcEnd; break; }
                v4 = (int) INT_LE.get(src, pos);
                h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
            }
        }

        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = (byte) ((litLen < 15 ? litLen : 15) << 4);
            if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
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

    /** Pure-Java compress with offsets — no allocation. For JNI crossover benchmarking. */
    static int compressJava(byte[] src, int srcOff, int srcLen,
                            byte[] dst, int dstOff, int maxChain) {
        return compressJavaImpl(src, srcOff, srcLen, dst, dstOff, maxChain);
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
        if (NativeLZ4.AVAILABLE && !IS_AARCH64) {
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
            int iLitLen = (int) litLen;
            /* Fast paths for the common small literal lengths (JFR data: 95%+ are 1-3 bytes). */
            if (iLitLen == 1) {
                dst[op++] = src[ip++];
            } else if (iLitLen == 2) {
                dst[op] = src[ip]; dst[op + 1] = src[ip + 1]; op += 2; ip += 2;
            } else if (iLitLen == 3) {
                dst[op] = src[ip]; dst[op + 1] = src[ip + 1]; dst[op + 2] = src[ip + 2];
                op += 3; ip += 3;
            } else if (iLitLen != 0) {
                if (iLitLen <= 32) {
                    if (iLitLen >= 16) {
                        LONG_LE.set(dst, op,              (long) LONG_LE.get(src, ip));
                        LONG_LE.set(dst, op + 8,          (long) LONG_LE.get(src, ip + 8));
                        LONG_LE.set(dst, op + iLitLen - 16, (long) LONG_LE.get(src, ip + iLitLen - 16));
                        LONG_LE.set(dst, op + iLitLen - 8,  (long) LONG_LE.get(src, ip + iLitLen - 8));
                    } else if (iLitLen >= 8) {
                        LONG_LE.set(dst, op,                (long) LONG_LE.get(src, ip));
                        LONG_LE.set(dst, op + iLitLen - 8,  (long) LONG_LE.get(src, ip + iLitLen - 8));
                    } else if (iLitLen >= 4) {
                        INT_LE.set(dst, op,                (int) INT_LE.get(src, ip));
                        INT_LE.set(dst, op + iLitLen - 4,  (int) INT_LE.get(src, ip + iLitLen - 4));
                    } else {
                        for (int ci = 0; ci < iLitLen; ci++) dst[op + ci] = src[ip + ci];
                    }
                } else {
                    System.arraycopy(src, ip, dst, op, iLitLen);
                }
                ip += iLitLen;
                op += iLitLen;
            }

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
            int iMatchLen = (int) matchLen;
            if (offset >= iMatchLen) {
                if (iMatchLen <= 64) {
                    if (iMatchLen >= 32) {
                        LONG_LE.set(dst, op,               (long) LONG_LE.get(dst, matchSrc));
                        LONG_LE.set(dst, op + 8,           (long) LONG_LE.get(dst, matchSrc + 8));
                        LONG_LE.set(dst, op + 16,          (long) LONG_LE.get(dst, matchSrc + 16));
                        LONG_LE.set(dst, op + 24,          (long) LONG_LE.get(dst, matchSrc + 24));
                        LONG_LE.set(dst, op + iMatchLen - 32, (long) LONG_LE.get(dst, matchSrc + iMatchLen - 32));
                        LONG_LE.set(dst, op + iMatchLen - 24, (long) LONG_LE.get(dst, matchSrc + iMatchLen - 24));
                        LONG_LE.set(dst, op + iMatchLen - 16, (long) LONG_LE.get(dst, matchSrc + iMatchLen - 16));
                        LONG_LE.set(dst, op + iMatchLen - 8,  (long) LONG_LE.get(dst, matchSrc + iMatchLen - 8));
                    } else if (iMatchLen >= 16) {
                        LONG_LE.set(dst, op,               (long) LONG_LE.get(dst, matchSrc));
                        LONG_LE.set(dst, op + 8,           (long) LONG_LE.get(dst, matchSrc + 8));
                        LONG_LE.set(dst, op + iMatchLen - 16, (long) LONG_LE.get(dst, matchSrc + iMatchLen - 16));
                        LONG_LE.set(dst, op + iMatchLen - 8,  (long) LONG_LE.get(dst, matchSrc + iMatchLen - 8));
                    } else if (iMatchLen >= 8) {
                        LONG_LE.set(dst, op,                 (long) LONG_LE.get(dst, matchSrc));
                        LONG_LE.set(dst, op + iMatchLen - 8, (long) LONG_LE.get(dst, matchSrc + iMatchLen - 8));
                    } else if (iMatchLen >= 4) {
                        INT_LE.set(dst, op,                 (int) INT_LE.get(dst, matchSrc));
                        INT_LE.set(dst, op + iMatchLen - 4, (int) INT_LE.get(dst, matchSrc + iMatchLen - 4));
                    } else {
                        for (int ci = 0; ci < iMatchLen; ci++) dst[op + ci] = dst[matchSrc + ci];
                    }
                } else {
                    System.arraycopy(dst, matchSrc, dst, op, iMatchLen);
                }
            } else {
                copyMatch(dst, matchSrc, op, iMatchLen);
            }
            op += iMatchLen;
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
     * Uses overlapping 8-byte VarHandle stores for sizes ≤ 64 — avoids arraycopy
     * intrinsic overhead for common literal run sizes.
     */
    private static int copyLiterals(byte[] src, int srcPos, byte[] dst, int dstPos, int litLen) {
        if (litLen <= 64) {
            if (litLen >= 32) {
                LONG_LE.set(dst, dstPos,      (long) LONG_LE.get(src, srcPos));
                LONG_LE.set(dst, dstPos + 8,  (long) LONG_LE.get(src, srcPos + 8));
                LONG_LE.set(dst, dstPos + 16, (long) LONG_LE.get(src, srcPos + 16));
                LONG_LE.set(dst, dstPos + 24, (long) LONG_LE.get(src, srcPos + 24));
                LONG_LE.set(dst, dstPos + litLen - 32, (long) LONG_LE.get(src, srcPos + litLen - 32));
                LONG_LE.set(dst, dstPos + litLen - 24, (long) LONG_LE.get(src, srcPos + litLen - 24));
                LONG_LE.set(dst, dstPos + litLen - 16, (long) LONG_LE.get(src, srcPos + litLen - 16));
                LONG_LE.set(dst, dstPos + litLen - 8,  (long) LONG_LE.get(src, srcPos + litLen - 8));
            } else if (litLen >= 16) {
                LONG_LE.set(dst, dstPos,     (long) LONG_LE.get(src, srcPos));
                LONG_LE.set(dst, dstPos + 8, (long) LONG_LE.get(src, srcPos + 8));
                LONG_LE.set(dst, dstPos + litLen - 16, (long) LONG_LE.get(src, srcPos + litLen - 16));
                LONG_LE.set(dst, dstPos + litLen - 8,  (long) LONG_LE.get(src, srcPos + litLen - 8));
            } else if (litLen >= 8) {
                LONG_LE.set(dst, dstPos,                 (long) LONG_LE.get(src, srcPos));
                LONG_LE.set(dst, dstPos + litLen - 8, (long) LONG_LE.get(src, srcPos + litLen - 8));
            } else {
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
        if (offset == 1) {
            Arrays.fill(buf, dst, dst + len, buf[src]);
        } else if (offset == 2) {
            /* Broadcast 2-byte pattern: replicate via 8-byte stores. */
            int s0 = buf[src] & 0xFF, s1 = buf[src + 1] & 0xFF;
            long v2 = s0 | (s1 << 8);
            long pattern = v2 | (v2 << 16) | (v2 << 32) | (v2 << 48);
            int d = dst, end = dst + len;
            while (d + 8 <= end) { LONG_LE.set(buf, d, pattern); d += 8; }
            while (d < end) { buf[d] = buf[d - offset]; d++; }
        } else if (offset == 4) {
            /* Broadcast 4-byte pattern. */
            long v4 = (int) INT_LE.get(buf, src) & 0xFFFFFFFFL;
            long pattern = v4 | (v4 << 32);
            int d = dst, end = dst + len;
            while (d + 8 <= end) { LONG_LE.set(buf, d, pattern); d += 8; }
            while (d < end) { buf[d] = buf[d - offset]; d++; }
        } else if (offset >= 8) {
            /* Non-overlapping 8-byte strides: src and dst both advance by 8 per iteration.
               After src catches up by offset bytes, it reads previously-written output
               (= the original pattern repeated), so the copy is correct. */
            int d = dst, end = dst + len;
            while (d + 8 <= end) {
                LONG_LE.set(buf, d, (long) LONG_LE.get(buf, src));
                src += 8; d += 8;
            }
            while (d < end) { buf[d++] = buf[src++]; }
        } else {
            /* General case (offset 3..7): advance src in lockstep with dst. */
            int d = dst, end = dst + len;
            while (d < end) { buf[d++] = buf[src++]; }
        }
    }

    private LZ4() {}
}
