package me.bechberger.femtolz4;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Pure-Java LZ4 block compressor and decompressor.
 *
 * <p>Implements both {@link LZ4.Compressor} and {@link LZ4.Decompressor}.
 * Each instance owns its own hash/DP tables; constructing one allocates them,
 * and reusing the same instance across calls avoids repeated allocation.
 *
 * <p>Obtain instances via {@link LZ4#compressor(int)} or {@link LZ4#decompressor()}
 * rather than constructing directly.
 */
public class LZ4Java implements LZ4.Compressor, LZ4.Decompressor {

    static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle INT_LE  = MethodHandles.byteArrayViewVarHandle(int[].class,  ByteOrder.LITTLE_ENDIAN);
    static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    static final int WINDOW_SIZE    = LZ4.WINDOW_SIZE;
    private static final int WINDOW_MASK     = WINDOW_SIZE - 1;
    private static final int HASH_BITS       = 16;
    private static final int HASH_SIZE       = 1 << HASH_BITS;
    private static final int MIN_MATCH       = 4;
    private static final int PADDING         = 5;
    /* LZ4 spec: last match must START >= MFLIMIT bytes before end of input. */
    private static final int MFLIMIT         = 12;
    private static final int NIL             = Integer.MIN_VALUE;

    /* 12-bit fast tables: long[4096] storing (v4<<32|pos) (32 KB).
       Sentinel = srcOff-WINDOW_SIZE-1 in low 32 bits, guarantees (pos-sentinel)>WINDOW_SIZE.
       v4 fingerprint avoids src[] read on most hash-collision misses. */
    private static final int HASH_BITS_FAST  = 12;
    private static final int HASH_SIZE_FAST  = 1 << HASH_BITS_FAST;

    /** Chain marker for the optimal-parse mode (see {@link LZ4Optimal}). */
    static final int OPTIMAL_CHAIN = Integer.MAX_VALUE;

    /**
     * Compression level: 0 = 2-way fast, 1 = fast, 2+ = chain depth.
     */
    private final int maxChain;

    /* Per-instance hash tables — allocated once at construction. */
    private final long[] fastHead;
    private final long[] fast2Head;
    private final int[]  chainHead;
    private final long[] chainTail;

    /**
     * Create a pure-Java compressor/decompressor at the given chain depth.
     *
     * @param maxChain 0 = 2-way fast, 1 = fast, 2–256 = chain depth,
     *                 {@link #OPTIMAL_CHAIN} = optimal parsing
     */
    public LZ4Java(int maxChain) {
        this.maxChain  = maxChain;
        fastHead   = (maxChain == 1)  ? new long[HASH_SIZE_FAST]     : null;
        fast2Head  = (maxChain == 0)  ? new long[HASH_SIZE_FAST * 2] : null;
        chainHead  = (maxChain >= 2 && maxChain != OPTIMAL_CHAIN) ? new int[HASH_SIZE]    : null;
        chainTail  = (maxChain >= 2 && maxChain != OPTIMAL_CHAIN) ? new long[WINDOW_SIZE] : null;
    }

    /**
     * Count how many of 8 evenly-spaced 4-byte windows have the same value as
     * the window 4 positions earlier (offset-4 repeat pattern).
     *
     * @return number of repeated-pattern probes in [0, 8]
     */
    static int countRepeatedSamples(byte[] src, int srcOff, int srcLen) {
        int count = 0;
        int step  = Math.max(1, srcLen / 8);
        int end   = srcOff + srcLen;
        for (int i = 0; i < 8; i++) {
            int pos = srcOff + i * step;
            if (pos + 8 > end) break;
            if ((int) INT_LE.get(src, pos) == (int) INT_LE.get(src, pos + 4)) count++;
        }
        return count;
    }

    // ── LZ4.Compressor implementation ────────────────────────────────────────

    @Override
    public int compress(byte[] src, int srcOff, int srcLen,
                        byte[] dst, int dstOff, int maxDestLen) {
        return compressJavaImpl(src, srcOff, srcLen, dst, dstOff, maxChain, LZ4.SAMPLE_COUNT_UNKNOWN);
    }

    // ── LZ4.Decompressor implementation ──────────────────────────────────────

    @Override
    public int decompress(byte[] src, int srcOff, byte[] dst, int dstOff, int originalLen) {
        decompressJavaImpl(src, srcOff, src.length - srcOff, dst, dstOff, originalLen, dstOff);
        return originalLen;
    }

    // ── Static convenience shims (delegate to a one-shot instance) ────────────

    /** Equivalent to lz4-java's {@code factory.fastCompressor()}. */
    public static LZ4.Compressor fastCompressor() { return LZ4.compressor(LZ4.LEVEL_FAST); }

    /** Equivalent to lz4-java's {@code factory.highCompressor()}. */
    public static LZ4.Compressor highCompressor() { return LZ4.compressor(LZ4.LEVEL_DEFAULT); }

    /**
     * Equivalent to lz4-java's {@code factory.highCompressor(level)}.
     *
     * @param level chain depth from 1 to 256
     * @deprecated Use {@link LZ4#compressor(int)}.
     */
    @Deprecated
    public static LZ4.Compressor highCompressor(int level) { return LZ4.compressHigh(level); }

    /** Equivalent to lz4-java's {@code factory.fastDecompressor()}. */
    public static LZ4.Decompressor fastDecompressor() { return LZ4.decompress(); }

    /** Pure-Java compress at chain=1, bypassing native. */
    public static byte[] compressJava(byte[] src) { return compressJava(src, 1); }

    /** Pure-Java compress, bypassing native. */
    public static byte[] compressJava(byte[] src, int chain) {
        int maxLen = LZ4.maxCompressedLength(src.length);
        byte[] dst = new byte[maxLen];
        int len = new LZ4Java(chain).compressJavaImpl(src, 0, src.length, dst, 0, chain, LZ4.SAMPLE_COUNT_UNKNOWN);
        return Arrays.copyOf(dst, len);
    }

    /** Pure-Java compress with offsets — no allocation. */
    public static int compressJava(byte[] src, int srcOff, int srcLen,
                                   byte[] dst, int dstOff, int chain) {
        return new LZ4Java(chain).compressJavaImpl(src, srcOff, srcLen, dst, dstOff, chain, LZ4.SAMPLE_COUNT_UNKNOWN);
    }

    /** Pure-Java decompress, bypassing native. */
    public static byte[] decompressJava(byte[] src, int decompressedSize) {
        byte[] dst = new byte[decompressedSize];
        int n = decompressJavaImpl(src, 0, src.length, dst, 0, decompressedSize, 0);
        // Full-buffer output is the norm: skip the extra array + full copy.
        return n == decompressedSize ? dst : Arrays.copyOf(dst, n);
    }

    /**
     * Pure-Java decompress with match lower bound — used for multi-block streaming
     * where the decompressed history starts at {@code matchLowerBound}.
     */
    public static int decompressJavaWithMatchLowerBound(byte[] src, int srcOff, int srcLen,
                                                        byte[] dst, int dstOff, int dstLen,
                                                        int matchLowerBound) {
        return decompressJavaImpl(src, srcOff, srcLen, dst, dstOff, dstLen, matchLowerBound);
    }

    /*
     * Chain compressor (maxChain >= 2).
     * head[h] = most-recent position at hash h.
     * tail[pos & MASK] = packed (value<<32|nextPos) — avoids a cold src[sv] load per chain step.
     */
    int compressJavaImpl(byte[] src, int srcOff, int srcLen,
                         byte[] dst, int dstOff, int chain,
                         int repeatedSamplesHint) {
        /* No legal match can improve blocks this short. Avoid both JNI and
           clearing a 32-512 KiB hash table just to emit one literal run. */
        if (srcLen > 0 && srcLen <= 6) {
            dst[dstOff] = (byte) (srcLen << 4);
            copyLiterals(src, srcOff, dst, dstOff + 1, srcLen);
            return srcLen + 1;
        }
        if (chain == OPTIMAL_CHAIN) {
            return LZ4Optimal.compress(src, srcOff, srcLen, dst, dstOff);
        }
        if (chain <= 0) {
            return compressFast2Way(src, srcOff, srcLen, dst, dstOff);
        }
        if (chain == 1) {
            return compressFast(src, srcOff, srcLen, dst, dstOff);
        }
        int repeatedSamples = repeatedSamplesHint != LZ4.SAMPLE_COUNT_UNKNOWN
            ? repeatedSamplesHint
            : (srcLen >= LZ4.X86_NATIVE_CHAIN_SAMPLE_MIN
                ? countRepeatedSamples(src, srcOff, srcLen) : 0);
        boolean recoverMixedBoundary = repeatedSamples >= 2 && repeatedSamples < 6;
        if (chain == 2) {
            return compressChain2(src, srcOff, srcLen, dst, dstOff, recoverMixedBoundary);
        }
        if (srcLen == 0) return 0;

        int[]  head    = chainHead;
        long[] tail    = chainTail;
        Arrays.fill(head, NIL);

        int op        = dstOff;
        int litStart  = srcOff;
        int pos       = srcOff;
        int srcEnd    = srcOff + srcLen;
        int safeEnd   = srcEnd - PADDING;
        int safeMain  = srcEnd - MFLIMIT;       /* last match start ≤ srcEnd-MFLIMIT (spec) */
        int missBytes = 0;
        int skipCtr   = 2 << 6;

        while (pos <= safeMain) {
            /* In incompressible regions, skip positions but still probe the
               landing position. A continue here would permanently disable
               matching once a block accumulated 128 misses. */
            if (missBytes >= 128) {
                int step = (skipCtr >> 6) + 1;
                if (skipCtr < (17 << 6)) skipCtr++;
                pos += step;
                if (pos > safeMain) break;
                missBytes = recoverMixedBoundary ? 125 : missBytes + step;
            }

            int pos4 = (int) INT_LE.get(src, pos);
            int h    = (pos4 * 0x9E3779B9) >>> (32 - HASH_BITS);
            int limit     = pos - WINDOW_SIZE;
            int chainLeft = maxChain;
            int bestLen   = 0;
            int bestDist  = 0;

            int prev = head[h];
            tail[pos & WINDOW_MASK] = ((long) pos4 << 32) | (prev & 0xFFFFFFFFL);
            head[h] = pos;

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
                int len = extendMatch(src, sv, pos, maxMatch);
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
            // Long matches (≥8 bytes) are rarely improved by one position of lookahead.
            boolean lazyProbed = false;
            if (matchLen >= MIN_MATCH && matchLen < 8 && pos < safeMain) {
                int lp   = pos + 1;
                int lp4  = (int) INT_LE.get(src, lp);
                int lh   = (lp4 * 0x9E3779B9) >>> (32 - HASH_BITS);
                int llimit    = lp - WINDOW_SIZE;
                int lchainLeft = Math.min(maxChain, 2);
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
                    int len = extendMatch(src, sv, lp, maxMatch);
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
                dst[op++] = token(litLen, matchExtra);
                if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
                op = copyLiterals(src, litStart, dst, op, litLen);
                SHORT_LE.set(dst, op, (short) matchDist); op += 2;
                if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
                litStart = pos + matchLen;
                int insertEnd = litStart < safeEnd + 1 ? litStart : safeEnd + 1;
                // If lazy probed but lost, pos+1 was already inserted; start at pos+2
                // to avoid reinserting it (which can create a self-link in the chain).
                int insertStart = pos + 1 + (lazyProbed ? 1 : 0);
                /* Long matches: JFR shows this insertion loop at ~90% of chain-mode
                   compress on long-match data — it costs O(matchLen) hash inserts
                   for a single emitted match. Widen the stride for long matches;
                   skipped positions are never written to head/tail together, so
                   chain integrity is kept, and long repeats are re-found via the
                   far end anyway. */
                int iStep = matchLen <= 128 ? 2 : (matchLen <= 1024 ? 8 : 32);
                for (int ip = insertStart; ip < insertEnd; ip += iStep) {
                    int ip4 = (int) INT_LE.get(src, ip);
                    int h2  = (ip4 * 0x9E3779B9) >>> (32 - HASH_BITS);
                    int prev2 = head[h2];
                    tail[ip & WINDOW_MASK] = ((long) ip4 << 32) | (prev2 & 0xFFFFFFFFL);
                    head[h2] = ip;
                }
                pos = litStart;
            } else {
                missBytes++;
                pos++;
            }
        }
        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = token(litLen, 0);
            if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
            op = copyLiterals(src, litStart, dst, op, litLen);
        }
        return op - dstOff;
    }

    /* chain=2: unrolled 2-probe variant with back-to-back tail[] loads for OOO overlap. */
    private int compressChain2(byte[] src, int srcOff, int srcLen,
                               byte[] dst, int dstOff, boolean recoverMixedBoundary) {
        if (srcLen == 0) return 0;

        int[]  head    = chainHead;
        long[] tail    = chainTail;
        Arrays.fill(head, NIL);

        int op        = dstOff;
        int litStart  = srcOff;
        int pos       = srcOff;
        int srcEnd    = srcOff + srcLen;
        int safeEnd   = srcEnd - PADDING;
        int safeMain  = srcEnd - MFLIMIT;       /* last match start ≤ srcEnd-MFLIMIT (spec) */
        int missBytes = 0;
        int skipCtr   = 2 << 6;

        while (pos <= safeMain) {
            /* In incompressible regions, skip positions but probe the landing
               position so a later compressible region can recover. */
            if (missBytes >= 128) {
                int step = (skipCtr >> 6) + 1;
                if (skipCtr < (17 << 6)) skipCtr++;
                pos += step;
                if (pos > safeMain) break;
                missBytes = recoverMixedBoundary ? 125 : missBytes + step;
            }

            int pos4  = (int) INT_LE.get(src, pos);
            int h     = (pos4 * 0x9E3779B9) >>> (32 - HASH_BITS);
            int limit = pos - WINDOW_SIZE;

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

            if (sv1 > limit) {
                int sv4_1 = (int)(tslot1 >>> 32);
                if (sv4_1 == pos4) {
                    int len = extendMatch(src, sv1, pos, safeEnd - pos);
                    if (len > bestLen) { bestLen = len; bestDist = pos - sv1; }
                }

                if (sv2 > limit && (bestLen == 0 || bestLen < safeEnd - pos)) {
                    int sv4_2 = (int)(tslot2 >>> 32);
                    if (sv4_2 == pos4 && (bestLen == 0 || src[sv2 + bestLen] == src[pos + bestLen])) {
                        int len = extendMatch(src, sv2, pos, safeEnd - pos);
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
                        int len = extendMatch(src, lsv1, lp, safeEnd - lp);
                        if (len > lazyLen) { lazyLen = len; lazyDist = lp - lsv1; }
                    }

                    if (lsv2 > llimit && (lazyLen == 0 || lazyLen < safeEnd - lp)) {
                        int lsv4_2 = (int)(ltslot2 >>> 32);
                        if (lsv4_2 == lp4 && (lazyLen == 0 || src[lsv2 + lazyLen] == src[lp + lazyLen])) {
                            int len = extendMatch(src, lsv2, lp, safeEnd - lp);
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
                dst[op++] = token(litLen, matchExtra);
                if (litLen >= 15)    op = writeOverflow(dst, op, litLen - 15);
                op = copyLiterals(src, litStart, dst, op, litLen);
                SHORT_LE.set(dst, op, (short) matchDist); op += 2;
                if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
                litStart = pos + matchLen;
                int insertEnd   = litStart < safeEnd + 1 ? litStart : safeEnd + 1;
                int insertStart = pos + 1 + (lazyProbed ? 1 : 0);
                for (int ip = insertStart; ip < insertEnd; ip += 2) {
                    int ip4 = (int) INT_LE.get(src, ip);
                    int h2  = (ip4 * 0x9E3779B9) >>> (32 - HASH_BITS);
                    int prev2 = head[h2];
                    tail[ip & WINDOW_MASK] = ((long) ip4 << 32) | (prev2 & 0xFFFFFFFFL);
                    head[h2] = ip;
                }
                pos = litStart;
            } else {
                missBytes++;
                pos++;
            }
        }
        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = token(litLen, 0);
            if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
            op = copyLiterals(src, litStart, dst, op, litLen);
        }
        return op - dstOff;
    }

    /* maxChain=0: 2-way associative long[] table, 2 slots per bucket (LRU).
       Sentinel = srcOff-WINDOW_SIZE-1 in low 32 bits. v4 fingerprint avoids src[] read on misses. */
    private int compressFast2Way(byte[] src, int srcOff, int srcLen,
                                 byte[] dst, int dstOff) {
        if (srcLen == 0) return 0;
        long[] head = fast2Head;
        long sentinel = (long)(srcOff - WINDOW_SIZE - 1) & 0xFFFFFFFFL;
        Arrays.fill(head, sentinel);

        int op        = dstOff;
        int litStart  = srcOff;
        int pos       = srcOff;
        int srcEnd    = srcOff + srcLen;
        int safeEnd   = srcEnd - PADDING;
        int safeEnd2  = srcEnd - MFLIMIT + 1;   /* pos < safeEnd2 ⟹ pos ≤ srcEnd-MFLIMIT (spec) */
        int missBytes = 0;
        int skipCtr   = 2 << 6;

        int v4 = (pos < safeEnd2) ? (int) INT_LE.get(src, pos) : 0;
        int h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);

        outer:
        while (pos < safeEnd2) {
            int bi = h << 1;

            long s0 = head[bi], s1 = head[bi + 1];
            head[bi + 1] = s0;
            head[bi]     = ((long) v4 << 32) | (pos & 0xFFFFFFFFL);

            /* Speculatively compute pos+1 hash and load its bucket */
            int v4_1 = (int) INT_LE.get(src, pos + 1);
            int h1   = (v4_1 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);

            int matchSv = -1, matchLen = 0;
            int sv0 = (int) s0;
            if ((pos - sv0) < WINDOW_SIZE && (int)(s0 >>> 32) == v4) {
                int len = extendMatch(src, sv0, pos, safeEnd - pos);
                if (len >= MIN_MATCH) { matchSv = sv0; matchLen = len; }
            }
            int sv1 = (int) s1;
            if (sv1 != sv0 && (pos - sv1) < WINDOW_SIZE && (int)(s1 >>> 32) == v4) {
                if (matchLen == 0 || src[sv1 + matchLen] == src[pos + matchLen]) {
                    int len = extendMatch(src, sv1, pos, safeEnd - pos);
                    if (len > matchLen) { matchSv = sv1; matchLen = len; }
                }
            }

            if (matchLen >= MIN_MATCH) {
                missBytes = 0;
                skipCtr   = 2 << 6;
                int litLen     = pos - litStart;
                int matchExtra = matchLen - MIN_MATCH;
                int matchDist  = pos - matchSv;
                dst[op++] = token(litLen, matchExtra);
                if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
                op = copyLiterals(src, litStart, dst, op, litLen);
                SHORT_LE.set(dst, op, (short) matchDist); op += 2;
                if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
                litStart = pos + matchLen;
                pos = litStart;
                if (pos >= safeEnd2) break;
                v4 = (int) INT_LE.get(src, pos);
                h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                continue;
            }

            int bi1 = h1 << 1;
            long ss0 = head[bi1], ss1 = head[bi1 + 1];
            head[bi1 + 1] = ss0;
            head[bi1]     = ((long) v4_1 << 32) | ((pos + 1) & 0xFFFFFFFFL);
            pos++;
            if (pos >= safeEnd2) { pos = srcEnd; break; }

            matchSv = -1; matchLen = 0;
            int ssv0 = (int) ss0;
            if ((pos - ssv0) < WINDOW_SIZE && (int)(ss0 >>> 32) == v4_1) {
                int len = extendMatch(src, ssv0, pos, safeEnd - pos);
                if (len >= MIN_MATCH) { matchSv = ssv0; matchLen = len; }
            }
            int ssv1 = (int) ss1;
            if (ssv1 != ssv0 && (pos - ssv1) < WINDOW_SIZE && (int)(ss1 >>> 32) == v4_1) {
                if (matchLen == 0 || src[ssv1 + matchLen] == src[pos + matchLen]) {
                    int len = extendMatch(src, ssv1, pos, safeEnd - pos);
                    if (len > matchLen) { matchSv = ssv1; matchLen = len; }
                }
            }

            if (matchLen >= MIN_MATCH) {
                missBytes = 0;
                skipCtr   = 2 << 6;
                int litLen     = pos - litStart;
                int matchExtra = matchLen - MIN_MATCH;
                int matchDist  = pos - matchSv;
                dst[op++] = token(litLen, matchExtra);
                if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
                op = copyLiterals(src, litStart, dst, op, litLen);
                SHORT_LE.set(dst, op, (short) matchDist); op += 2;
                if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
                litStart = pos + matchLen;
                pos = litStart;
                if (pos >= safeEnd2) break;
                v4 = (int) INT_LE.get(src, pos);
                h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                continue;
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
            if (pos >= safeEnd2) { pos = srcEnd; break; }
            v4 = (int) INT_LE.get(src, pos);
            h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
        }

        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = token(litLen, 0);
            if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
            op = copyLiterals(src, litStart, dst, op, litLen);
        }
        return op - dstOff;
    }

    /* chain=1: long[4096] storing (v4<<32|pos). The table is NOT cleared between
       calls: stale slots fail the v4 fingerprint check with probability ~1-2^-32,
       and a candidate is only accepted after bounds re-checks (sv in the current
       block, offset in window) and re-reading the 4 bytes at sv, which fully
       determines match validity (extendMatch never re-verifies the first 4).
       Saves a 32 KiB fill per call (10.5% of samples at chain=1 on json-10m). */
    private int compressFast(byte[] src, int srcOff, int srcLen,
                             byte[] dst, int dstOff) {
        if (srcLen == 0) return 0;
        long[] head = fastHead;

        int op        = dstOff;
        int litStart  = srcOff;
        int pos       = srcOff;
        int srcEnd    = srcOff + srcLen;
        int safeEnd   = srcEnd - PADDING;
        int safeEnd2  = srcEnd - MFLIMIT + 1;   /* pos < safeEnd2 ⟹ pos ≤ srcEnd-MFLIMIT (spec) */
        int missBytes = 0;
        int skipCtr   = 2 << 6;

        int v4 = (pos < safeEnd2) ? (int) INT_LE.get(src, pos) : 0;
        int h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);

        while (pos < safeEnd2) {
            long slot = head[h];
            head[h] = ((long) v4 << 32) | (pos & 0xFFFFFFFFL);

            /* Speculatively load pos+1 slot while checking pos — hides second load latency. */
            int v4_1 = (int) INT_LE.get(src, pos + 1);
            int h1   = (v4_1 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
            long slot1 = head[h1];

            int sv = (int) slot;
            if ((int)(slot >>> 32) == v4 && sv >= srcOff
                    && pos - sv >= 1 && pos - sv < WINDOW_SIZE
                    && (int) INT_LE.get(src, sv) == v4) {
                int maxMatch = safeEnd - pos;
                int len = extendMatch(src, sv, pos, maxMatch);

                if (len >= MIN_MATCH) {
                    missBytes = 0;
                    skipCtr   = 2 << 6;
                    int litLen     = pos - litStart;
                    int matchExtra = len - MIN_MATCH;
                    int matchDist  = pos - sv;
                    dst[op++] = token(litLen, matchExtra);
                    if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
                    op = copyLiterals(src, litStart, dst, op, litLen);
                    SHORT_LE.set(dst, op, (short) matchDist); op += 2;
                    if (matchExtra >= 15) op = writeOverflow(dst, op, matchExtra - 15);
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

            head[h1] = ((long) v4_1 << 32) | (pos & 0xFFFFFFFFL);

            int sv1 = (int) slot1;
            if ((int)(slot1 >>> 32) == v4_1 && sv1 >= srcOff
                    && pos - sv1 >= 1 && pos - sv1 < WINDOW_SIZE
                    && (int) INT_LE.get(src, sv1) == v4_1) {
                int maxMatch1 = safeEnd - pos;
                int len1 = extendMatch(src, sv1, pos, maxMatch1);

                if (len1 >= MIN_MATCH) {
                    missBytes = 0;
                    skipCtr   = 2 << 6;
                    int litLen1     = pos - litStart;
                    int matchExtra1 = len1 - MIN_MATCH;
                    int matchDist1  = pos - sv1;
                    dst[op++] = token(litLen1, matchExtra1);
                    if (litLen1 >= 15) op = writeOverflow(dst, op, litLen1 - 15);
                    op = copyLiterals(src, litStart, dst, op, litLen1);
                    SHORT_LE.set(dst, op, (short) matchDist1); op += 2;
                    if (matchExtra1 >= 15) op = writeOverflow(dst, op, matchExtra1 - 15);
                    litStart = pos + len1;
                    pos = litStart;
                    if (pos >= safeEnd2) break;
                    v4 = (int) INT_LE.get(src, pos);
                    h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                    continue;
                }
            }

            if (missBytes < 128) {
                missBytes += 2;
                pos++;
            } else {
                int step = (skipCtr >> 6) + 1;
                if (skipCtr < (17 << 6)) skipCtr++;
                missBytes += step;
                pos += step;
            }
            if (pos >= safeEnd2) { pos = srcEnd; break; }
            v4 = (int) INT_LE.get(src, pos);
            h  = (v4 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
        }

        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = token(litLen, 0);
            if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
            op = copyLiterals(src, litStart, dst, op, litLen);
        }
        return op - dstOff;
    }

    static int decompressJavaImpl(byte[] src, int srcOff, int srcLen,
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
            if (iLitLen == 0) {
                // Dense match chains (e.g. JSON) have mostly empty literal runs;
                // don't pay the wild copy's 32 bytes of memory traffic for nothing.
            } else if (litLen <= 16 && op + 16 <= dstEnd && ip + 16 <= srcEnd) {
                /* Wild copy: two fixed 8-byte moves cover any 0-16 byte literal run
                   without per-length branching (JFR data: 95%+ of runs are <= 16 bytes).
                   Overshoot bytes stay within dstEnd and are overwritten by the
                   following sequence; the tail margin keeps reads within srcEnd. */
                LONG_LE.set(dst, op,     (long) LONG_LE.get(src, ip));
                LONG_LE.set(dst, op + 8, (long) LONG_LE.get(src, ip + 8));
                ip += iLitLen;
                op += iLitLen;
            } else if (iLitLen != 0) {
                System.arraycopy(src, ip, dst, op, iLitLen);
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
            if (offset >= 8 && iMatchLen <= 16 && op + 16 <= dstEnd) {
                // Wild copy for the dominant case: two fixed 8-byte moves replace
                // the variable-length arraycopy dispatch. Overshoot stays within
                // dstEnd and is overwritten by subsequent output.
                LONG_LE.set(dst, op,     (long) LONG_LE.get(dst, matchSrc));
                LONG_LE.set(dst, op + 8, (long) LONG_LE.get(dst, matchSrc + 8));
            } else if (offset >= iMatchLen) {
                // Non-overlapping: arraycopy is a JVM intrinsic — faster than
                // the hand-unrolled VarHandle ladder for all lengths because it
                // avoids per-element bounds checks and VarHandle dispatch overhead.
                System.arraycopy(dst, matchSrc, dst, op, iMatchLen);
            } else {
                copyMatch(dst, matchSrc, op, iMatchLen);
            }
            op += iMatchLen;
        }
        return op - dstOff;
    }

    static int copyLiterals(byte[] src, int srcPos, byte[] dst, int dstPos, int litLen) {
        if (litLen == 0) {
            // Common on dense match chains; the wild copy would still move 32B.
            return dstPos;
        }
        if (litLen <= 16 && srcPos + 16 <= src.length && dstPos + 16 <= dst.length) {
            /* Wild copy: two fixed 8-byte moves cover any 0-16 byte literal run
               without per-length branching; overshoot bytes are rewritten by the
               following token/match output (guards keep overshoot in bounds). */
            LONG_LE.set(dst, dstPos,     (long) LONG_LE.get(src, srcPos));
            LONG_LE.set(dst, dstPos + 8, (long) LONG_LE.get(src, srcPos + 8));
            return dstPos + litLen;
        }
        /* Longer runs (and the in-bounds-guard-failing tails): arraycopy is a
           vectorized intrinsic and beats a hand-unrolled VarHandle ladder whose
           every access pays dispatch + bounds checks. */
        System.arraycopy(src, srcPos, dst, dstPos, litLen);
        return dstPos + litLen;
    }

    static int writeOverflow(byte[] dst, int op, int rem) {
        if (rem >= 255) {
            // Vectorized fill beats a 255-per-iteration byte loop on long runs
            // (JFR: ~6% of text-like compress at chain=1 came from this loop).
            int n = rem / 255;
            Arrays.fill(dst, op, op + n, (byte) 255);
            op += n;
            rem -= n * 255;
        }
        dst[op++] = (byte) rem;
        return op;
    }

    static byte token(int litLen, int matchExtra) {
        return (byte) (((litLen < 15 ? litLen : 15) << 4) | (matchExtra < 15 ? matchExtra : 15));
    }

    /**
     * Extend a match starting at sv vs pos in src, beginning at len=MIN_MATCH.
     * Returns the total match length. maxMatch = safeEnd - pos.
     * Uses 16-byte XOR steps for long matches (helps AArch64 NEON pipelining).
     */
    static int extendMatch(byte[] src, int sv, int pos, int maxMatch) {
        int len = MIN_MATCH;
        /* Short-first tier: real-world (JFR) match probes resolve in <= 16B far
           more often than not - exit via a 16-byte block before committing to
           32-byte strides (which waste 4 loads per rejected fast-path probe). */
        if (len + 16 <= maxMatch) {
            long d1 = (long) LONG_LE.get(src, sv + len)     ^ (long) LONG_LE.get(src, pos + len);
            long d2 = (long) LONG_LE.get(src, sv + len + 8) ^ (long) LONG_LE.get(src, pos + len + 8);
            if ((d1 | d2) != 0L) {
                if (d1 != 0L) { len += Long.numberOfTrailingZeros(d1)  >>> 3; return len; }
                len += 8 + (Long.numberOfTrailingZeros(d2) >>> 3);
                return len;
            }
            len += 16;
        }
        while (len + 32 <= maxMatch) {
            long d1 = (long) LONG_LE.get(src, sv + len)      ^ (long) LONG_LE.get(src, pos + len);
            long d2 = (long) LONG_LE.get(src, sv + len + 8)  ^ (long) LONG_LE.get(src, pos + len + 8);
            long d3 = (long) LONG_LE.get(src, sv + len + 16) ^ (long) LONG_LE.get(src, pos + len + 16);
            long d4 = (long) LONG_LE.get(src, sv + len + 24) ^ (long) LONG_LE.get(src, pos + len + 24);
            if ((d1 | d2 | d3 | d4) != 0L) {
                if (d1 != 0L) { len += Long.numberOfTrailingZeros(d1) >>> 3; return len; }
                if (d2 != 0L) { len += 8  + (Long.numberOfTrailingZeros(d2) >>> 3); return len; }
                if (d3 != 0L) { len += 16 + (Long.numberOfTrailingZeros(d3) >>> 3); return len; }
                len += 24 + (Long.numberOfTrailingZeros(d4) >>> 3);
                return len;
            }
            len += 32;
        }
        if (len + 16 <= maxMatch) {
            long d1 = (long) LONG_LE.get(src, sv + len)     ^ (long) LONG_LE.get(src, pos + len);
            long d2 = (long) LONG_LE.get(src, sv + len + 8) ^ (long) LONG_LE.get(src, pos + len + 8);
            if ((d1 | d2) != 0L) {
                if (d1 != 0L) { len += Long.numberOfTrailingZeros(d1)  >>> 3; return len; }
                len += 8 + (Long.numberOfTrailingZeros(d2) >>> 3);
                return len;
            }
            len += 16;
        }
        if (len + 8 <= maxMatch) {
            long diff = (long) LONG_LE.get(src, sv + len) ^ (long) LONG_LE.get(src, pos + len);
            if (diff != 0L) { len += Long.numberOfTrailingZeros(diff) >>> 3; return len; }
            len += 8;
        }
        while (len < maxMatch && src[sv + len] == src[pos + len]) len++;
        return len;
    }

    /** Copy match bytes, handling overlap (offset < matchLen). */
    static void copyMatch(byte[] buf, int src, int dst, int len) {
        int offset = dst - src;
        if (offset == 1) {
            Arrays.fill(buf, dst, dst + len, buf[src]);
        } else if (offset == 2) {
            int s0 = buf[src] & 0xFF, s1 = buf[src + 1] & 0xFF;
            long v2 = s0 | (s1 << 8);
            long pattern = v2 | (v2 << 16) | (v2 << 32) | (v2 << 48);
            int d = dst, end = dst + len;
            while (d + 8 <= end) { LONG_LE.set(buf, d, pattern); d += 8; }
            while (d < end) { buf[d] = buf[d - offset]; d++; }
        } else if (offset == 4) {
            long v4 = (int) INT_LE.get(buf, src) & 0xFFFFFFFFL;
            long pattern = v4 | (v4 << 32);
            int d = dst, end = dst + len;
            while (d + 8 <= end) { LONG_LE.set(buf, d, pattern); d += 8; }
            while (d < end) { buf[d] = buf[d - offset]; d++; }
        } else if (offset == 8) {
            /* 8-byte lattice (timestamps, pointers, doubles in real binary
               payloads): pure-store loop — no store->load forwarding chain. */
            long pattern = (long) LONG_LE.get(buf, src);
            int d = dst, end = dst + len;
            while (d + 8 <= end) { LONG_LE.set(buf, d, pattern); d += 8; }
            while (d < end) { buf[d] = buf[d - offset]; d++; }
        } else if (offset == 16) {
            long p0 = (long) LONG_LE.get(buf, src);
            long p1 = (long) LONG_LE.get(buf, src + 8);
            int d = dst, end = dst + len;
            while (d + 16 <= end) {
                LONG_LE.set(buf, d,     p0);
                LONG_LE.set(buf, d + 8, p1);
                d += 16;
            }
            while (d < end) { buf[d] = buf[d - offset]; d++; }
        } else {
            /* Any offset >= 3 with offset < len: prime the first `offset` bytes
               (phase-aligned tile), then grow geometrically via arraycopy. Every
               step reads only already-written output, and the copy length is a
               multiple of offset until the final partial step, so the pattern
               phase is preserved. arraycopy is a vectorized intrinsic and the
               step count is logarithmic in len — no store->load forwarding chain
               like a fixed 8/16-byte lockstep loop has for offsets < 16. */
            System.arraycopy(buf, src, buf, dst, offset);
            int written = offset;
            while (written < len) {
                int k = written < len - written ? written : len - written;
                System.arraycopy(buf, dst, buf, dst + written, k);
                written += k;
            }
        }
    }

}
