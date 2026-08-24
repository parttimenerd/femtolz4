package me.bechberger.femtolz4;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Pure-Java LZ4 block compressor and decompressor.
 *
 * <p>All compress/decompress logic lives here. {@link LZ4} is the public dispatch layer.
 */
public final class LZ4Java {

    static final VarHandle INT_LE  = MethodHandles.byteArrayViewVarHandle(int[].class,  ByteOrder.LITTLE_ENDIAN);
    static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    static final int WINDOW_SIZE    = LZ4.WINDOW_SIZE;
    private static final int WINDOW_MASK     = WINDOW_SIZE - 1;
    private static final int HASH_BITS       = 16;
    private static final int HASH_SIZE       = 1 << HASH_BITS;
    private static final int MIN_MATCH       = 4;
    private static final int PADDING         = 5;
    private static final int NIL             = Integer.MIN_VALUE;

    /* 12-bit fast tables: long[4096] storing (v4<<32|pos) (32 KB).
       Sentinel = srcOff-WINDOW_SIZE-1 in low 32 bits, guarantees (pos-sentinel)>WINDOW_SIZE.
       v4 fingerprint avoids src[] read on most hash-collision misses. */
    private static final int HASH_BITS_FAST  = 12;
    private static final int HASH_SIZE_FAST  = 1 << HASH_BITS_FAST;

    /* Thread-local tables — reused per call to avoid allocation. */
    private static final ThreadLocal<long[]> TL_FAST_HEAD  = ThreadLocal.withInitial(() -> new long[HASH_SIZE_FAST]);

    /* 2-way associative: two slots per bucket (LRU). Slightly better ratio than chain=1. */
    private static final ThreadLocal<long[]> TL_FAST2_HEAD = ThreadLocal.withInitial(() -> new long[HASH_SIZE_FAST * 2]);
    /* Chain tables: head[] filled NIL before each block; tail[] only read from valid heads. */
    private static final ThreadLocal<int[]>  TL_CHAIN_HEAD = ThreadLocal.withInitial(() -> new int[HASH_SIZE]);
    private static final ThreadLocal<long[]> TL_CHAIN_TAIL = ThreadLocal.withInitial(() -> new long[WINDOW_SIZE]);

    static final ThreadLocal<byte[]> TL_DST   = ThreadLocal.withInitial(() -> new byte[0]);
    private static final ThreadLocal<byte[]> TL_DECOMP = ThreadLocal.withInitial(() -> new byte[0]);

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

    /** Equivalent to lz4-java's {@code factory.fastCompressor()}. */
    public static LZ4.Compressor fastCompressor() { return LZ4.compress(); }

    /** Equivalent to lz4-java's {@code factory.highCompressor()}. */
    public static LZ4.Compressor highCompressor() { return LZ4.compressHigh(); }

    /**
     * Equivalent to lz4-java's {@code factory.highCompressor(level)}.
     *
     * @param level HC level from {@value LZ4#HC_MIN_LEVEL} to {@value LZ4#HC_MAX_LEVEL}
     */
    public static LZ4.Compressor highCompressor(int level) { return LZ4.compressHigh(level); }

    /** Equivalent to lz4-java's {@code factory.fastDecompressor()}. */
    public static LZ4.Decompressor fastDecompressor() { return LZ4.decompress(); }

    /** Pure-Java compress at chain=1, bypassing native. For benchmarking. */
    public static byte[] compressJava(byte[] src) {
        return compressJava(src, 1);
    }

    /** Pure-Java compress, bypassing native. For benchmarking. */
    public static byte[] compressJava(byte[] src, int maxChain) {
        int maxLen = LZ4.maxCompressedLength(src.length);
        byte[] dst = new byte[maxLen];
        int len = compressJavaImpl(src, 0, src.length, dst, 0, maxChain);
        return Arrays.copyOf(dst, len);
    }

    /** Pure-Java compress with offsets — no allocation. For benchmarking. */
    public static int compressJava(byte[] src, int srcOff, int srcLen,
                                   byte[] dst, int dstOff, int maxChain) {
        return compressJavaImpl(src, srcOff, srcLen, dst, dstOff, maxChain);
    }

    /** Pure-Java decompress, bypassing native. For benchmarking. */
    public static byte[] decompressJava(byte[] src, int decompressedSize) {
        byte[] dst = TL_DECOMP.get();
        if (dst.length < decompressedSize) {
            dst = new byte[decompressedSize];
            TL_DECOMP.set(dst);
        }
        int n = decompressJavaImpl(src, 0, src.length, dst, 0, decompressedSize, 0);
        return Arrays.copyOf(dst, n);
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
    static int compressJavaImpl(byte[] src, int srcOff, int srcLen,
                                byte[] dst, int dstOff, int maxChain) {
        return compressJavaImpl(src, srcOff, srcLen, dst, dstOff, maxChain, LZ4.SAMPLE_COUNT_UNKNOWN);
    }

    static int compressJavaImpl(byte[] src, int srcOff, int srcLen,
                                byte[] dst, int dstOff, int maxChain,
                                int repeatedSamplesHint) {
        /* No legal match can improve blocks this short. Avoid both JNI and
           clearing a 32-512 KiB hash table just to emit one literal run. */
        if (srcLen > 0 && srcLen <= 6) {
            dst[dstOff] = (byte) (srcLen << 4);
            copyLiterals(src, srcOff, dst, dstOff + 1, srcLen);
            return srcLen + 1;
        }
        if (maxChain <= 0) {
            return compressFast2Way(src, srcOff, srcLen, dst, dstOff);
        }
        if (maxChain == 1) {
            return compressFast(src, srcOff, srcLen, dst, dstOff);
        }
        int repeatedSamples = repeatedSamplesHint != LZ4.SAMPLE_COUNT_UNKNOWN
            ? repeatedSamplesHint
            : (srcLen >= LZ4.X86_NATIVE_CHAIN_SAMPLE_MIN
                ? countRepeatedSamples(src, srcOff, srcLen) : 0);
        boolean recoverMixedBoundary = repeatedSamples >= 2 && repeatedSamples < 6;
        if (maxChain == 2) {
            return compressChain2(src, srcOff, srcLen, dst, dstOff, recoverMixedBoundary);
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
    private static int compressChain2(byte[] src, int srcOff, int srcLen,
                                      byte[] dst, int dstOff, boolean recoverMixedBoundary) {
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
    private static int compressFast2Way(byte[] src, int srcOff, int srcLen,
                                        byte[] dst, int dstOff) {
        if (srcLen == 0) return 0;
        long[] head = TL_FAST2_HEAD.get();
        long sentinel = (long)(srcOff - WINDOW_SIZE - 1) & 0xFFFFFFFFL;
        Arrays.fill(head, sentinel);

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
                    dst[op++] = (byte) matchDist;
                    dst[op++] = (byte) (matchDist >>> 8);
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
                    dst[op++] = (byte) matchDist;
                    dst[op++] = (byte) (matchDist >>> 8);
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
        }

        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = token(litLen, 0);
            if (litLen >= 15) op = writeOverflow(dst, op, litLen - 15);
            op = copyLiterals(src, litStart, dst, op, litLen);
        }
        return op - dstOff;
    }

    /* chain=1: long[4096] storing (v4<<32|pos). Sentinel low 32 bits = srcOff-WINDOW_SIZE-1.
       Window check first (cheap), then v4 fingerprint check avoids src[] read on most misses. */
    private static int compressFast(byte[] src, int srcOff, int srcLen,
                                    byte[] dst, int dstOff) {
        if (srcLen == 0) return 0;
        long[] head = TL_FAST_HEAD.get();
        long sentinel = (long)(srcOff - WINDOW_SIZE - 1) & 0xFFFFFFFFL;
        Arrays.fill(head, sentinel);

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

            while (pos < safeEnd2) {
                long slot = head[h];
                head[h] = ((long) v4 << 32) | (pos & 0xFFFFFFFFL);

                /* Speculatively load pos+1 slot while checking pos — hides second load latency. */
                int v4_1 = (int) INT_LE.get(src, pos + 1);
                int h1   = (v4_1 * 0x9E3779B9) >>> (32 - HASH_BITS_FAST);
                long slot1 = head[h1];

                int sv = (int) slot;
                if ((pos - sv) < WINDOW_SIZE && (int)(slot >>> 32) == v4) {
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

                pos++;
                if (pos >= safeEnd2) { pos = srcEnd; break; }

                head[h1] = ((long) v4_1 << 32) | (pos & 0xFFFFFFFFL);

                int sv1 = (int) slot1;
                if ((pos - sv1) < WINDOW_SIZE && (int)(slot1 >>> 32) == v4_1) {
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
        if (litLen >= 16) {
            if (litLen <= 32) {
                LONG_LE.set(dst, dstPos,      (long) LONG_LE.get(src, srcPos));
                LONG_LE.set(dst, dstPos + 8,  (long) LONG_LE.get(src, srcPos + 8));
                LONG_LE.set(dst, dstPos + litLen - 16, (long) LONG_LE.get(src, srcPos + litLen - 16));
                LONG_LE.set(dst, dstPos + litLen - 8,  (long) LONG_LE.get(src, srcPos + litLen - 8));
            } else if (litLen <= 64) {
                LONG_LE.set(dst, dstPos,           (long) LONG_LE.get(src, srcPos));
                LONG_LE.set(dst, dstPos + 8,       (long) LONG_LE.get(src, srcPos + 8));
                LONG_LE.set(dst, dstPos + 16,      (long) LONG_LE.get(src, srcPos + 16));
                LONG_LE.set(dst, dstPos + 24,      (long) LONG_LE.get(src, srcPos + 24));
                LONG_LE.set(dst, dstPos + litLen - 32, (long) LONG_LE.get(src, srcPos + litLen - 32));
                LONG_LE.set(dst, dstPos + litLen - 24, (long) LONG_LE.get(src, srcPos + litLen - 24));
                LONG_LE.set(dst, dstPos + litLen - 16, (long) LONG_LE.get(src, srcPos + litLen - 16));
                LONG_LE.set(dst, dstPos + litLen - 8,  (long) LONG_LE.get(src, srcPos + litLen - 8));
            } else {
                System.arraycopy(src, srcPos, dst, dstPos, litLen);
            }
        } else if (litLen >= 8) {
            LONG_LE.set(dst, dstPos,               (long) LONG_LE.get(src, srcPos));
            LONG_LE.set(dst, dstPos + litLen - 8,  (long) LONG_LE.get(src, srcPos + litLen - 8));
        } else if (litLen >= 4) {
            INT_LE.set(dst, dstPos,               (int) INT_LE.get(src, srcPos));
            INT_LE.set(dst, dstPos + litLen - 4,  (int) INT_LE.get(src, srcPos + litLen - 4));
        } else {
            for (int i = 0; i < litLen; i++) dst[dstPos + i] = src[srcPos + i];
        }
        return dstPos + litLen;
    }

    static int writeOverflow(byte[] dst, int op, int rem) {
        for (; rem >= 255; rem -= 255) dst[op++] = (byte) 255;
        dst[op++] = (byte) rem;
        return op;
    }

    private static byte token(int litLen, int matchExtra) {
        return (byte) (((litLen < 15 ? litLen : 15) << 4) | (matchExtra < 15 ? matchExtra : 15));
    }

    /**
     * Extend a match starting at sv vs pos in src, beginning at len=MIN_MATCH.
     * Returns the total match length. maxMatch = safeEnd - pos.
     * Uses 16-byte XOR steps for long matches (helps AArch64 NEON pipelining).
     */
    private static int extendMatch(byte[] src, int sv, int pos, int maxMatch) {
        int len = MIN_MATCH;
        while (len + 16 <= maxMatch) {
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
        } else if (offset >= 8) {
            /* src advances in lockstep — after offset bytes it reads previously-written output. */
            int d = dst, end = dst + len;
            while (d + 16 <= end) {
                LONG_LE.set(buf, d,     (long) LONG_LE.get(buf, src));
                LONG_LE.set(buf, d + 8, (long) LONG_LE.get(buf, src + 8));
                src += 16; d += 16;
            }
            while (d + 8 <= end) { LONG_LE.set(buf, d, (long) LONG_LE.get(buf, src)); src += 8; d += 8; }
            while (d < end) { buf[d++] = buf[src++]; }
        } else {
            /* Offset 3..7: prime an 8-byte tile, then blast forward in 8-byte strides. */
            int d   = dst;
            int end = dst + len;
            for (int i = 0; i < offset; i++) buf[d + i] = buf[src + i];
            int c = offset;
            while (c < 8 && c + c <= len) { System.arraycopy(buf, d, buf, d + c, c); c += c; }
            if (c < 8 && d + 8 <= end) { System.arraycopy(buf, d, buf, d + c, 8 - c); c = 8; }
            d += c;
            while (d + 8 <= end) { LONG_LE.set(buf, d, (long) LONG_LE.get(buf, d - 8)); d += 8; }
            while (d < end) { buf[d] = buf[d - offset]; d++; }
        }
    }

    private LZ4Java() {}
}