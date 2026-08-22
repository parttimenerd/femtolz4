#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "lz4.h"

#ifdef __ARM_NEON
#  include <arm_neon.h>
#endif
#ifdef __AVX2__
#  include <immintrin.h>
#endif

/*
 * LZ4 frame format  https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md
 *
 * Header:  [magic 4B][FLG 1B][BD 1B][HC 1B]
 * Blocks:  [size 4B LE][data]   (size high bit = 1 → stored uncompressed)
 * Footer:  [0x00000000 4B]
 *
 * FLG 0x60 — version=01, block_independent=1, no checksums
 * BD  0x50 — block_maxsize=5 (256 KB)
 * HC       — (xxhash32(FLG ‖ BD) >> 8) & 0xFF
 */

/* Left-rotate a 32-bit value.  Compilers emit a single ROL instruction. */

#define PADDING_LITERALS 5
#define WINDOW_MASK      (WINDOW_SIZE - 1)
#define MIN_MATCH        4
#define HASH_BITS        LZ4_HASH_BITS
#define NIL              (-1)

#define MIN(a, b)  ((a) < (b) ? (a) : (b))
#define MAX(a, b)  ((a) > (b) ? (a) : (b))

/* ── Primitives ─────────────────────────────────────────────────────────── */

#define FORCE_INLINE static inline __attribute__((always_inline))
#define HOT          __attribute__((hot))

/* Write overflow bytes for a length field that exceeded 15 (the nibble cap). */
FORCE_INLINE void lz4__write_length_overflow(uint8_t *dst, int *op, int len)
{
    int rem = len - 15;
    for (; rem >= 255; rem -= 255)
        dst[(*op)++] = 255;
    dst[(*op)++] = (uint8_t)rem;
}

/*
 * Write one LZ4 sequence token and its literal run.
 *
 * Token byte:  high nibble = literal count (capped at 15)
 *              low  nibble = match length − MIN_MATCH (capped at 15)
 * If either nibble overflows 15, extra bytes follow (255... then remainder).
 */
FORCE_INLINE void lz4__emit_literals(uint8_t *dst, int *op,
                                const uint8_t *src, int lit_start,
                                int lit_len, int match_extra)
{
    dst[(*op)++] = (uint8_t)((MIN(lit_len, 15) << 4) | MIN(match_extra, 15));
    if (lit_len >= 15)
        lz4__write_length_overflow(dst, op, lit_len);
    if (lit_len > 0) {
        /* __builtin_memcpy for small sizes: compiler emits inline AVX stores,
           avoiding the vzeroupper + memcpy@plt transition penalty after AVX2
           match extension. Larger copies fall back to libc memcpy normally. */
        if (lit_len <= 16) {
            __builtin_memcpy(dst + *op, src + lit_start, lit_len);
        } else if (lit_len <= 32) {
            __builtin_memcpy(dst + *op, src + lit_start, 16);
            __builtin_memcpy(dst + *op + 16, src + lit_start + 16, lit_len - 16);
        } else if (lit_len <= 64) {
            __builtin_memcpy(dst + *op,      src + lit_start,      32);
            __builtin_memcpy(dst + *op + 32, src + lit_start + 32, lit_len - 32);
        } else {
            memcpy(dst + *op, src + lit_start, lit_len);
        }
        *op += lit_len;
    }
}

/* Write match-length overflow bytes (called when match_extra >= 15). */
FORCE_INLINE void lz4__emit_match_overflow(uint8_t *dst, int *op, int match_extra)
{
    if (match_extra >= 15)
        lz4__write_length_overflow(dst, op, match_extra);
}

/* 4-byte multiply-shift hash for the chain path.
   The 5th-byte variant (previously used) adds a load with no measurable ratio benefit. */
FORCE_INLINE uint32_t lz4__hash(const uint8_t *p)
{
    uint32_t v;
    memcpy(&v, p, 4);
    return (v * 0x9E3779B9u) >> (32 - HASH_BITS);
}

/* Compute hash directly from an already-loaded uint32_t value. */
FORCE_INLINE uint32_t lz4__hash4v(uint32_t v)
{
    return (v * 0x9E3779B9u) >> (32 - LZ4_HASH_BITS_FAST);
}

/* Push position into the hash chain for src[pos].
   Caller must ensure pos + 4 <= src_len (4 bytes needed by lz4__hash).
   Tail slot: bits[63:32] = 4-byte src value at prev (rejection filter, avoids
   cold src[prev] load), bits[31:0] = prev as signed int32 (NIL = negative). */
FORCE_INLINE void lz4__insert(lz4_stream_t *s, const uint8_t *src, int pos)
{
    uint32_t h    = lz4__hash(src + pos);
    int      prev = s->head[h];
    uint64_t slot;
    if (prev >= 0 && prev > pos - (int)WINDOW_SIZE) {
        uint32_t prev4; memcpy(&prev4, src + prev, 4);
        slot = ((uint64_t)prev4 << 32) | (uint32_t)prev;
    } else {
        slot = (uint64_t)(uint32_t)(int32_t)-1;   /* NIL: negative prevPos = end of chain */
    }
    s->tail[pos & WINDOW_MASK] = slot;
    s->head[h]                 = pos;
}

/*
 * Extend a candidate match whose first MIN_MATCH bytes are already known to
 * be equal, returning the total match length (capped at max_match).  Uses
 * SIMD where available (NEON or AVX2, chosen at compile time per platform),
 * then 8-byte scalar steps, then a final byte-by-byte fragment.
 */
FORCE_INLINE int lz4__extend_match(const uint8_t *src, int sv, int pos, int max_match)
{
    int len = MIN_MATCH;
#ifdef __ARM_NEON
    while (len + 16 <= max_match) {
        uint8x16_t sv16  = vld1q_u8(src + sv  + len);
        uint8x16_t pos16 = vld1q_u8(src + pos + len);
        uint8x16_t eq    = vceqq_u8(sv16, pos16);
        uint32x4_t ne    = vreinterpretq_u32_u8(vmvnq_u8(eq));
        uint32_t ne0 = vgetq_lane_u32(ne, 0), ne1 = vgetq_lane_u32(ne, 1);
        uint32_t ne2 = vgetq_lane_u32(ne, 2), ne3 = vgetq_lane_u32(ne, 3);
        if (ne0 | ne1 | ne2 | ne3) {
            if      (ne0) len +=  0 + (__builtin_ctz(ne0) >> 3);
            else if (ne1) len +=  4 + (__builtin_ctz(ne1) >> 3);
            else if (ne2) len +=  8 + (__builtin_ctz(ne2) >> 3);
            else          len += 12 + (__builtin_ctz(ne3) >> 3);
            return len;
        }
        len += 16;
        __builtin_prefetch(src + sv  + len + 32, 0, 0);
        __builtin_prefetch(src + pos + len + 32, 0, 0);
    }
#elif defined(__AVX2__)
    while (len + 32 <= max_match) {
        __m256i sv32  = _mm256_loadu_si256((const __m256i *)(src + sv  + len));
        __m256i pos32 = _mm256_loadu_si256((const __m256i *)(src + pos + len));
        uint32_t mask = (uint32_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(sv32, pos32));
        if (mask != 0xFFFFFFFFu) { return len + __builtin_ctz(~mask); }
        len += 32;
        __builtin_prefetch(src + sv  + len + 64, 0, 0);
        __builtin_prefetch(src + pos + len + 64, 0, 0);
    }
    while (len + 16 <= max_match) {
        __m128i sv16  = _mm_loadu_si128((const __m128i *)(src + sv  + len));
        __m128i pos16 = _mm_loadu_si128((const __m128i *)(src + pos + len));
        int     mask  = _mm_movemask_epi8(_mm_cmpeq_epi8(sv16, pos16));
        if (mask != 0xFFFF) { return len + __builtin_ctz(~mask); }
        len += 16;
    }
#endif
    while (len + 8 <= max_match) {
        uint64_t sv8, pos8;
        memcpy(&sv8, src + sv + len, 8); memcpy(&pos8, src + pos + len, 8);
        uint64_t diff = sv8 ^ pos8;
        if (diff)
            return len + (__builtin_ctzll(diff) >> 3);
        len += 8;
    }
    while (len < max_match && src[sv + len] == src[pos + len]) ++len;
    return len;
}

/* Insert pos and find its best match in one pass (single hash computation). */
FORCE_INLINE int lz4__insert_and_match(lz4_stream_t *s, const uint8_t *src,
                                  int pos, int src_len, int max_chain,
                                  int *out_dist)
{
    int max_match = (src_len - PADDING_LITERALS) - pos;
    if (max_match < MIN_MATCH) {
        if (pos + 4 <= src_len) lz4__insert(s, src, pos);
        return 0;
    }

    int      limit     = MAX(pos - WINDOW_SIZE, NIL);
    int      chain_len = max_chain;
    uint32_t h         = lz4__hash(src + pos);
    uint32_t pos4;
    memcpy(&pos4, src + pos, 4);

    /* Insert pos into the chain before walking it. */
    {
        int prev = s->head[h];
        uint64_t slot;
        if (prev >= 0 && prev > limit) {
            uint32_t prev4; memcpy(&prev4, src + prev, 4);
            slot = ((uint64_t)prev4 << 32) | (uint32_t)prev;
        } else {
            slot = (uint64_t)(uint32_t)(int32_t)-1;
        }
        s->tail[pos & WINDOW_MASK] = slot;
        s->head[h] = pos;
    }

    int best_len  = 0;
    int best_dist = 0;

    /* Walk the chain: each slot stores (prev4 << 32) | prevPos. */
    uint64_t slot = s->tail[pos & WINDOW_MASK];
    for (;;) {
        int sv = (int32_t)(uint32_t)slot;
        if (sv < 0 || sv <= limit) break;          /* NIL or out of window */

        uint32_t sv4 = (uint32_t)(slot >> 32);
        if (sv4 == pos4 &&
            (best_len == 0 || src[sv + best_len] == src[pos + best_len])) {
            int len = lz4__extend_match(src, sv, pos, max_match);
            if (len > best_len) {
                best_len = len; best_dist = pos - sv;
                if (len == max_match) break;
            }
        }

        if (--chain_len == 0) break;
        slot = s->tail[sv & WINDOW_MASK];
    }
    *out_dist = best_dist;
    return best_len;
}

/* ── Public API ─────────────────────────────────────────────────────────── */

/*
 * Chain=1 fast-path using a uint64_t[LZ4_HASH_SIZE_FAST] table.
 * Each slot: bits[63:32] = 4-byte src value, bits[31:0] = position (int32).
 * Negative position (high bit set in low 32) = empty (sentinel 0x80808080...).
 * Storing the 4-byte value alongside pos avoids the cold src[sv] load on probe.
 * Table must be reset to 0x80 bytes before each call.
 */
#if defined(__x86_64__) || defined(_M_X64)
__attribute__((target("avx2")))
#endif
HOT int lz4_compress_fast(const uint8_t *src, uint8_t *dst,
                      int src_len, uint64_t *htab)
{
    if (src_len == 0) return 0;
    int op        = 0;
    int lit_start = 0;
    int pos       = 0;
    int safe_end  = src_len - PADDING_LITERALS;
    int loop_end  = safe_end - 1;          /* loop runs while pos < loop_end */

    if (src_len < 4) goto emit_tail;       /* too short for any match */

    {
    uint32_t pos4;
    memcpy(&pos4, src + pos, 4);
    uint32_t h = lz4__hash4v(pos4);

    while (pos < loop_end) {
        /* Step A: look up htab for pos, then speculatively start pos+1 lookup
           so its htab cache line has time to arrive before we need it. */
        uint64_t slot = htab[h];
        htab[h]       = ((uint64_t)pos4 << 32) | (uint32_t)pos;

        /* Speculatively prefetch pos+1 and pos+2 while we evaluate pos. */
        uint32_t pos4_1 = 0;
        uint32_t h1     = 0;
        if (__builtin_expect(pos + 1 < loop_end, 1)) {
            memcpy(&pos4_1, src + pos + 1, 4);
            h1 = lz4__hash4v(pos4_1);
            __builtin_prefetch(htab + h1, 0, 3);
            if (__builtin_expect(pos + 2 < loop_end, 1)) {
                uint32_t pos4_2; memcpy(&pos4_2, src + pos + 2, 4);
                __builtin_prefetch(htab + lz4__hash4v(pos4_2), 0, 3);
            }
        }

        /* Unsigned distance check: (uint32_t)(pos-sv)-1 < WINDOW_SIZE-1 handles
           sentinel (0x80808080…) naturally — wraps to a huge value, fails check. */
        uint32_t sv = (uint32_t)slot;
        uint32_t match_dist = (uint32_t)pos - sv;
        if (match_dist - 1 < (uint32_t)(WINDOW_SIZE - 1) && (uint32_t)(slot >> 32) == pos4) {
            int max_match = safe_end - pos;
            int match_len  = lz4__extend_match(src, (int)sv, pos, max_match);

            if (match_len >= MIN_MATCH) {
                int match_extra = match_len - MIN_MATCH;
                lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, match_extra);
                { uint16_t off16 = (uint16_t)match_dist; memcpy(dst + op, &off16, 2); }
                op += 2;
                lz4__emit_match_overflow(dst, &op, match_extra);
                lit_start = pos + match_len;
                pos = lit_start;
                if (__builtin_expect(pos >= loop_end, 0)) break;
                memcpy(&pos4, src + pos, 4);
                h = lz4__hash4v(pos4);
                __builtin_prefetch(htab + h, 0, 3);
                continue;
            }
        }

        /* Step B: miss at pos — advance to pos+1.  htab[h1] prefetch already
           issued above, so the cache line may already be in L1/L2. */
        pos++;
        if (__builtin_expect(pos >= loop_end, 0)) {
            /* Re-sync state for the exit path. */
            memcpy(&pos4, src + pos, 4);
            h = lz4__hash4v(pos4);
            break;
        }
        pos4 = pos4_1;
        h    = h1;

        /* Step B: evaluate pos (the old pos+1). */
        uint64_t slot1 = htab[h];
        htab[h]        = ((uint64_t)pos4 << 32) | (uint32_t)pos;

        uint32_t sv1 = (uint32_t)slot1;
        uint32_t match_dist1 = (uint32_t)pos - sv1;
        if (match_dist1 - 1 < (uint32_t)(WINDOW_SIZE - 1) && (uint32_t)(slot1 >> 32) == pos4) {
            int max_match1 = safe_end - pos;
            int match_len1  = lz4__extend_match(src, (int)sv1, pos, max_match1);

            if (match_len1 >= MIN_MATCH) {
                int match_extra1 = match_len1 - MIN_MATCH;
                lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, match_extra1);
                { uint16_t off16 = (uint16_t)match_dist1; memcpy(dst + op, &off16, 2); }
                op += 2;
                lz4__emit_match_overflow(dst, &op, match_extra1);
                lit_start = pos + match_len1;
                pos = lit_start;
                if (__builtin_expect(pos >= loop_end, 0)) break;
                memcpy(&pos4, src + pos, 4);
                h = lz4__hash4v(pos4);
                __builtin_prefetch(htab + h, 0, 3);
                continue;
            }
        }

        /* Both pos and pos+1 missed — advance pos+1 → pos+2 for next iteration. */
        pos++;
        memcpy(&pos4, src + pos, 4);       /* safe: pos < loop_end < src_len - 4 */
        h = lz4__hash4v(pos4);
    }

    if (pos < safe_end) {
        /* Handle the final position that the loop skipped (pos == loop_end).
           Insert but don't match — too close to end for a safe match extension. */
        if (pos + 5 <= src_len) {
            memcpy(&pos4, src + pos, 4);
            h = lz4__hash4v(pos4);
            htab[h] = ((uint64_t)pos4 << 32) | (uint32_t)pos;
        }
        pos = src_len;
    }
    } /* end of 4-byte read block */

emit_tail:
    if (lit_start != src_len)
        lz4__emit_literals(dst, &op, src, lit_start, src_len - lit_start, 0);
    return op;
}

static int lz4__compress_block_chain(lz4_stream_t *s,
                                     const uint8_t *src, uint8_t *dst,
                                     int src_len, int max_chain)
{
    int op        = 0;
    int lit_start = 0;
    int pos       = 0;
    int safe_end  = src_len - PADDING_LITERALS;

    /* ── chain>1: hash chain, lazy matching, no skip ── */
    while (pos < src_len) {
        int match_dist = 0;
        int match_len  = lz4__insert_and_match(s, src, pos, src_len, max_chain, &match_dist);

        int lazy_probed = 0;
        /* Lazy matching: probe pos+1 only when match is short enough to benefit.
           Long matches are rarely improved by one position of lookahead, so skip
           the second search when the current match already covers ≥64 bytes. */
        if (match_len >= MIN_MATCH && match_len < 64 && pos + 1 < src_len) {
            int lazy_dist  = 0;
            int lazy_chain = max_chain < 2 ? max_chain : 2;
            int lazy_len   = lz4__insert_and_match(s, src, pos + 1, src_len, lazy_chain, &lazy_dist);
            lazy_probed = 1;
            if (lazy_len > match_len) {
                pos++;
                lazy_probed = 0;  /* lazy won: pos advanced, insertion loop starts normally */
                match_len  = lazy_len;
                match_dist = lazy_dist;
            }
        }

        if (match_len >= MIN_MATCH) {
            int match_extra = match_len - MIN_MATCH;
            lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, match_extra);
            { uint16_t off16 = (uint16_t)match_dist; memcpy(dst + op, &off16, 2); }
            op += 2;
            lz4__emit_match_overflow(dst, &op, match_extra);
            lit_start = pos + match_len;
            int insert_end = lit_start < safe_end + 1 ? lit_start : safe_end + 1;
            /* If lazy_probed but lost, pos+1 was already inserted; skip it to
               avoid creating a self-link in the chain. */
            int insert_start = pos + 1 + (lazy_probed ? 2 : 0);
            for (int ip = insert_start; ip < insert_end; ip += 2)
                lz4__insert(s, src, ip);
            pos = lit_start;
        } else {
            pos++;
        }
    }

    /* Final literal run — no match follows, so the match nibble is 0. */
    if (lit_start != pos)
        lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, 0);

    return op;
}

static int lz4__compress_dispatch(lz4_stream_t *s,
                                   const uint8_t *src, uint8_t *dst,
                                   int src_len, int max_chain,
                                   uint64_t *htab)
{
    if (max_chain == 1) {
        if (!htab) return 0;
        memset(htab, 0x80, LZ4_HASH_SIZE_FAST * sizeof(uint64_t));
        return lz4_compress_fast(src, dst, src_len, htab);
    }

    memset(s->head, 0xff, sizeof(s->head));
    return lz4__compress_block_chain(s, src, dst, src_len, max_chain);
}

#if defined(__x86_64__) || defined(_M_X64)
__attribute__((target("avx2")))
#endif
/* General block encoder: chain=1 defers to lz4_compress_fast(), chain>1 uses
 * the hash-chain + lazy-matching path below. */
HOT int lz4_compress_block(lz4_stream_t *s,
                       const uint8_t *src, uint8_t *dst,
                       int src_len, int max_chain)
{
    uint64_t *htab = NULL;
    if (max_chain == 1) {
        htab = (uint64_t *)malloc(LZ4_HASH_SIZE_FAST * sizeof(uint64_t));
        if (!htab) return 0;
    }
    int result = lz4__compress_dispatch(s, src, dst, src_len, max_chain, htab);
    free(htab);
    return result;
}

int lz4_compress(const uint8_t *src, uint8_t *dst, int src_len, int max_chain)
{
    lz4_stream_t *s = (lz4_stream_t *)malloc(sizeof(lz4_stream_t));
    if (!s) return 0;
    uint64_t *htab = NULL;
    if (max_chain == 1) {
        htab = (uint64_t *)malloc(LZ4_HASH_SIZE_FAST * sizeof(uint64_t));
        if (!htab) {
            free(s);
            return 0;
        }
    }
    int n = lz4__compress_dispatch(s, src, dst, src_len, max_chain, htab);
    free(htab);
    free(s);
    return n;
}
