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
    /** Chain probes per position. Higher = better ratio, slower. */
    private static final int ATTEMPTS      = 1024;
    /** DP chunk length (positions); literals flow across via deferred flush. */
    private static final int CHUNK         = 1 << 20;

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
                    int bestLen = MIN_MATCH - 1;         // record-improvement threshold
                    int attemptsLeft = ATTEMPTS;
                    for (int sv = prev; sv > limit && attemptsLeft-- > 0; ) {
                        long tslot = tail[sv & (LZ4.WINDOW_SIZE - 1)];
                        int  sv4   = (int) (tslot >>> 32);
                        int  next  = (int) tslot;
                        if (sv4 == pos4
                                && (bestLen < MIN_MATCH
                                    || src[sv + bestLen] == src[pos + bestLen])) {
                            int len = LZ4Java.extendMatch(src, sv, pos, maxMatch);
                            len = Math.min(len, cEnd - pos);         // chunk clamp
                            if (len > bestLen) {
                                if (poolSize == pool.length) {
                                    long[] np = new long[pool.length + (pool.length >>> 1) + 64];
                                    System.arraycopy(pool, 0, np, 0, pool.length);
                                    pool = np;
                                }
                                pool[poolSize++] = ((long) len << 32) | (pos - sv);
                                bestLen = len;
                                if (len >= maxMatch) break;          // can't improve
                            }
                        }
                        sv = next;
                    }
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
}
