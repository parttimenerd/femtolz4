package me.bechberger.femtolz4;

import java.util.Arrays;

/**
 * Optimal-parse LZ4 block compressor ("compress as much as possible"): valid
 * LZ4 block format, ratio-maximizing parse chosen by a per-position backward
 * dynamic program over a full 64 KiB window hash-chain search.
 *
 * <p>Model: for every position the cheapest encoding is
 * <pre>
 *   price[pos] = min( price[pos+1] + 1,                        // one literal byte
 *                     min over matches m: cost(m.len) + price[pos + m.len] )
 *   cost(len)  = 2 (offset) + extra length bytes + ~1 (token share)
 * </pre>
 * Candidates per position are the strictly length-improving matches found by
 * walking the hash chain (up to {@link #ATTEMPTS} probes), exactly like the
 * {@code lz4opt} reference. Matches may cross internal chunk boundaries
 * backward (offsets stay inside the 64 KiB window, so the stream stays
 * spec-valid), but never extend past the current chunk's end — a ~0 ratio
 * price paid at each 1 MiB boundary.
 *
 * <p>Spec rules enforced: a match may only start at {@code pos <= srcEnd-12}
 * (MFLIMIT) and must end by {@code srcEnd-5} (LASTLITERALS); the final
 * sequence is always literals-only (flushed after the DP choice walk).
 *
 * <p>Slow and memory-hungry by design (~28 B/byte input); use via
 * {@link LZ4#LEVEL_OPTIMAL} when ratio matters more than speed.
 */
final class LZ4Optimal {

    private static final int MIN_MATCH     = 4;
    private static final int MFLIMIT       = 12;
    private static final int LAST_LITERALS = 5;
    private static final int PADDING       = 5;
    private static final int HASH_BITS     = 16;
    private static final int HASH_SIZE     = 1 << HASH_BITS;
    private static final int NIL           = Integer.MIN_VALUE;
    /** Chain probes per position outside a long match. Higher = better ratio, slower. */
    private static final int ATTEMPTS      = 1024;
    /** Shifted candidates at least this long skip alternative search entirely. */
    private static final int LONG_SHIFT    = 128;
    /** Probes for alternatives when the shifted candidate is shorter than LONG_SHIFT. */
    private static final int EXTRA_ATTEMPTS = 32;
    /** DP chunk length (positions); literals flow across via deferred flush.
        4 MiB chunks keep chunk-boundary match splits rare (multi-megabyte runs
        otherwise pay ~3 B per 1 MiB split) at ~64 MB working set. */
    private static final int CHUNK         = 4 << 20;

    private LZ4Optimal() {}

    static int compress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff) {
        if (srcLen == 0) return 0;
        int srcEnd  = srcOff + srcLen;
        int safeEnd = srcEnd - PADDING;
        int safeMain = srcEnd - MFLIMIT;                 // last legal match start
        int matchEndCap = srcEnd - LAST_LITERALS;        // matches must end by here

        int[]  head = new int[HASH_SIZE];                // v4 -> newest position
        Arrays.fill(head, NIL);
        long[] tail = new long[LZ4.WINDOW_SIZE];         // pos ring: (v4<<32)|prevPos

        int op = dstOff;
        int litStart = srcOff;                           // pending literals cross chunks

        /* Run-shift shortcut state, carried across chunks: when the 4-gram at pos
           repeats the one at pos-1, (shiftOff, shiftTrue-1) is a guaranteed
           candidate without walking the chain again. */
        int shiftTrue = 0, shiftOff = 0;

        for (int cStart = srcOff; cStart < srcEnd; ) {
            int cLen = Math.min(CHUNK, srcEnd - cStart);
            int cEnd = cStart + cLen;

            int[] price      = new int[cLen + 1];
            int[] choiceLen  = new int[cLen + 1];
            int[] choiceOff  = new int[cLen + 1];
            int[] candStart  = new int[cLen + 2];        // index into pool per local pos
            long[] pool      = new long[Math.max(16, cLen)]; // packed (len,off); grown on demand
            int    poolSize  = 0;

            // forward pass: insert + gather record-improving candidates
            int lastMatchPos = Math.min(cEnd, safeMain + 1); // match starts must be < this
            for (int pos = cStart; pos < lastMatchPos; pos++) {
                int pos4   = (int) LZ4Java.INT_LE.get(src, pos);
                int h      = (pos4 * 0x9E3779B9) >>> (32 - HASH_BITS);
                int limit  = pos - LZ4.WINDOW_SIZE;
                int prev   = head[h];
                tail[pos & (LZ4.WINDOW_SIZE - 1)] = ((long) pos4 << 32) | (prev & 0xFFFFFFFFL);
                head[h]    = pos;
                candStart[pos - cStart] = poolSize;

                int maxMatch = Math.min(safeEnd, matchEndCap) - pos;
                if (maxMatch >= MIN_MATCH) {
                    int lenCap = Math.min(maxMatch, cEnd - pos); // room left in chunk
                    int bestLen = MIN_MATCH - 1;         // record-improvement threshold
                    int bestOff = 0;
                    int trueLen = 0;
                    int attempts = ATTEMPTS;
                    /* Universal shift guarantee: if (shiftOff, shiftTrue) matched at
                       pos-1, then (shiftOff, shiftTrue-1) matches here — the bytes
                       are equal by construction. Long shifts skip the walk; short
                       shifts still search a few alternatives (ratio), but at a
                       fraction of the probe budget (speed). */
                    if (shiftTrue > MIN_MATCH) {
                        int len = (int) Math.min((long) shiftTrue - 1, (long) lenCap);
                        if (len >= MIN_MATCH) {
                            if (poolSize == pool.length) pool = grow(pool);
                            pool[poolSize++] = ((long) len << 32) | shiftOff;
                            bestLen = len;
                            bestOff = shiftOff;
                            trueLen = shiftTrue - 1;
                            if (len >= LONG_SHIFT) {
                                shiftTrue--;         // consume one byte of the run
                                candStart[pos - cStart + 1] = poolSize;
                                continue;
                            }
                            attempts = EXTRA_ATTEMPTS;
                        } else {
                            shiftTrue = 0;
                        }
                    }
                    int attemptsLeft = attempts;
                    for (int sv = prev; sv > limit && attemptsLeft-- > 0; ) {
                        long tslot = tail[sv & (LZ4.WINDOW_SIZE - 1)];
                        int  sv4   = (int) (tslot >>> 32);
                        int  next  = (int) tslot;
                        if (sv4 == pos4
                                && (bestLen < MIN_MATCH
                                    || (bestLen < lenCap
                                        && src[sv + bestLen] == src[pos + bestLen]))) {
                            int len = LZ4Java.extendMatch(src, sv, pos, maxMatch);
                            if (len > bestLen) {
                                if (poolSize == pool.length) pool = grow(pool);
                                pool[poolSize++] = ((long) Math.min(len, lenCap) << 32) | (pos - sv);
                                bestLen = len;                      // TRUE len drives the walk
                                bestOff = pos - sv;
                                trueLen = len;
                                if (len >= lenCap) break;            // can't improve
                            }
                        }
                        sv = next;
                    }
                    /* Seed the shift shortcut for the next position. */
                    if (bestLen >= MIN_MATCH) {
                        shiftTrue = trueLen;
                        shiftOff  = bestOff;
                    } else {
                        shiftTrue = 0;
                    }
                } else {
                    shiftTrue = 0;
                }
                candStart[pos - cStart + 1] = poolSize;
            }
            for (int p = lastMatchPos; p <= cEnd; p++) candStart[p - cStart + 1] = poolSize;
            // backward DP over local positions
            price[cLen] = 0;
            for (int P = cLen - 1; P >= 0; P--) {
                int bp = price[P + 1] + 1;               // literal
                int bl = 0, bo = 0;
                for (int c = candStart[P], ce = candStart[P + 1]; c < ce; c++) {
                    long cand = pool[c];
                    int len = (int) (cand >>> 32);
                    int cost = matchCost(len) + price[P + len];
                    if (cost < bp) { bp = cost; bl = len; bo = (int) cand; }
                }
                price[P] = bp; choiceLen[P] = bl; choiceOff[P] = bo;
            }

            // forward emission following DP choices
            int pos = cStart;
            while (pos < cEnd) {
                int l = choiceLen[pos - cStart];
                if (l == 0) { pos++; continue; }
                int litLen = pos - litStart;
                dst[op++] = LZ4Java.token(litLen, l - MIN_MATCH);
                if (litLen >= 15) op = LZ4Java.writeOverflow(dst, op, litLen - 15);
                op = LZ4Java.copyLiterals(src, litStart, dst, op, litLen);
                int off = choiceOff[pos - cStart];
                LZ4Java.SHORT_LE.set(dst, op, (short) off); op += 2;
                int extra = l - MIN_MATCH;
                if (extra >= 15) op = LZ4Java.writeOverflow(dst, op, extra - 15);
                litStart = pos + l;
                pos = litStart;
            }
            cStart = cEnd;
        }

        // trailing literals-only sequence (spec); spans pending bytes since the last match
        int litLen = srcEnd - litStart;
        if (litLen > 0) {
            dst[op++] = LZ4Java.token(litLen, 0);
            if (litLen >= 15) op = LZ4Java.writeOverflow(dst, op, litLen - 15);
            op = LZ4Java.copyLiterals(src, litStart, dst, op, litLen);
        }
        return op - dstOff;
    }

    /** Encoded byte price of a match: 2 offset bytes + ~1 token share + length overflow. */
    private static int matchCost(int len) {
        int e = len - MIN_MATCH;
        int eb = e >= 15 ? 1 + (e - 15) / 255 : 0;
        return 3 + eb;
    }

    private static long[] grow(long[] pool) {
        long[] np = new long[pool.length + (pool.length >>> 1) + 64];
        System.arraycopy(pool, 0, np, 0, pool.length);
        return np;
    }
}
