#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "lz4.h"

#ifdef __AVX2__
#  include <immintrin.h>
#endif

#define PADDING_LITERALS 5
#define MFLIMIT          12
#define WINDOW_MASK      (WINDOW_SIZE - 1)
#define MIN_MATCH        4
#define HASH_BITS        LZ4_HASH_BITS
#define NIL              (-1)

#define MIN(a, b)  ((a) < (b) ? (a) : (b))
#define MAX(a, b)  ((a) > (b) ? (a) : (b))

#define FORCE_INLINE static inline __attribute__((always_inline))
#define HOT          __attribute__((hot))

/* Unaligned little-endian load/store helpers.
   memcpy-based: compiler emits a single mov/movq on all supported targets. */
FORCE_INLINE uint32_t read32(const void *p) { uint32_t v; memcpy(&v, p, 4); return v; }
FORCE_INLINE uint64_t read64(const void *p) { uint64_t v; memcpy(&v, p, 8); return v; }
FORCE_INLINE void     write16(void *p, uint16_t v) { memcpy(p, &v, 2); }

/* Write overflow bytes for a length field that exceeded 15 (the nibble cap). */
FORCE_INLINE void lz4__write_length_overflow(uint8_t * restrict dst, int *op, int len)
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
FORCE_INLINE void lz4__emit_literals(uint8_t * restrict dst, int *op,
                                const uint8_t * restrict src, int lit_start,
                                int lit_len, int match_extra)
{
    dst[(*op)++] = (uint8_t)((MIN(lit_len, 15) << 4) | MIN(match_extra, 15));
    if (lit_len >= 15)
        lz4__write_length_overflow(dst, op, lit_len);
    if (lit_len > 0) {
        uint8_t       *d = dst + *op;
        const uint8_t *s = src + lit_start;
        if (lit_len <= 16) {
            __builtin_memcpy(d, s, lit_len);
        } else if (lit_len <= 32) {
            /* Two fixed 16-byte copies, overlapping in the middle — no variable-size copy. */
            __builtin_memcpy(d,               s,               16);
            __builtin_memcpy(d + lit_len - 16, s + lit_len - 16, 16);
        } else if (lit_len <= 64) {
            __builtin_memcpy(d,               s,               32);
            __builtin_memcpy(d + lit_len - 32, s + lit_len - 32, 32);
        } else {
            memcpy(d, s, lit_len);
        }
        *op += lit_len;
    }
}

/* 4-byte multiply-shift hash for the chain path. */
FORCE_INLINE uint32_t lz4__hash(const uint8_t *p)
{
    return (read32(p) * 0x9E3779B9u) >> (32 - HASH_BITS);
}

/* Compute hash directly from an already-loaded uint32_t value. */
FORCE_INLINE uint32_t lz4__hash4v(uint32_t v)
{
    return (v * 0x9E3779B9u) >> (32 - LZ4_HASH_BITS_FAST);
}

/* Push position into the hash chain for src[pos], given pre-computed hash h.
   Tail slot: bits[63:32] = 4-byte src value at prev (rejection filter, avoids
   cold src[prev] load), bits[31:0] = prev as signed int32 (NIL = negative). */
FORCE_INLINE void lz4__insert_at_hash(lz4_stream_t *s, const uint8_t *src, int pos, uint32_t h)
{
    int      prev = s->head[h];
    uint64_t slot;
    if (prev >= 0 && prev > pos - (int)WINDOW_SIZE) {
        slot = ((uint64_t)read32(src + prev) << 32) | (uint32_t)prev;
    } else {
        slot = (uint64_t)(uint32_t)(int32_t)-1;   /* NIL: negative prevPos = end of chain */
    }
    s->tail[pos & WINDOW_MASK] = slot;
    s->head[h]                 = pos;
}

/* Push position into the hash chain for src[pos], computing the hash internally. */
FORCE_INLINE void lz4__insert(lz4_stream_t *s, const uint8_t *src, int pos)
{
    lz4__insert_at_hash(s, src, pos, lz4__hash(src + pos));
}

/*
 * Scalar match extension: 8-byte XOR steps then byte-by-byte tail.
 * Used as the generic path and as the tail for the AVX2 path.
 */
FORCE_INLINE int lz4__extend_match(const uint8_t * restrict src, int sv, int pos, int max_match)
{
    int len = MIN_MATCH;
    while (len + 8 <= max_match) {
        uint64_t diff = read64(src + sv + len) ^ read64(src + pos + len);
        if (diff)
            return len + (__builtin_ctzll(diff) >> 3);
        len += 8;
    }
    while (len < max_match && src[sv + len] == src[pos + len]) ++len;
    return len;
}

/*
 * AVX2 match extension: 32-byte YMM steps, 16-byte XMM steps, then scalar tail.
 * Defined with its own target("avx2") attribute so the compiler defines __AVX2__
 * within this function's translation unit — unlike applying the attribute only to
 * callers, which does NOT propagate the preprocessor macro to called functions.
 */
#if defined(__x86_64__) || defined(_M_X64)
__attribute__((target("avx2")))
static inline int lz4__extend_match_avx2(const uint8_t * restrict src, int sv, int pos, int max_match)
{
    int len = MIN_MATCH;
    while (len + 32 <= max_match) {
        __m256i sv32  = _mm256_loadu_si256((const __m256i *)(src + sv  + len));
        __m256i pos32 = _mm256_loadu_si256((const __m256i *)(src + pos + len));
        uint32_t mask = (uint32_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(sv32, pos32));
        if (mask != 0xFFFFFFFFu) { return len + _tzcnt_u32(~mask); }
        len += 32;
        __builtin_prefetch(src + sv  + len + 64, 0, 0);
        __builtin_prefetch(src + pos + len + 64, 0, 0);
    }
    while (len + 16 <= max_match) {
        __m128i sv16  = _mm_loadu_si128((const __m128i *)(src + sv  + len));
        __m128i pos16 = _mm_loadu_si128((const __m128i *)(src + pos + len));
        uint32_t mask = (uint32_t)_mm_movemask_epi8(_mm_cmpeq_epi8(sv16, pos16));
        if ((uint32_t)mask != 0xFFFFu) { return len + _tzcnt_u32(~mask); }
        len += 16;
    }
    while (len + 8 <= max_match) {
        uint64_t diff = read64(src + sv + len) ^ read64(src + pos + len);
        if (diff)
            return len + (__builtin_ctzll(diff) >> 3);
        len += 8;
    }
    while (len < max_match && src[sv + len] == src[pos + len]) ++len;
    return len;
}
#endif

/* On non-x86 there is no AVX2 variant; alias to the scalar path. */
#if !(defined(__x86_64__) || defined(_M_X64))
#  define lz4__extend_match_avx2 lz4__extend_match
#endif

/* Insert pos and find its best match in one pass (single hash computation). */
FORCE_INLINE int lz4__insert_and_match(lz4_stream_t *s, const uint8_t * restrict src,
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
    uint32_t pos4      = read32(src + pos);
    uint32_t h         = (pos4 * 0x9E3779B9u) >> (32 - HASH_BITS);

    /* Insert pos into the chain before walking it. */
    {
        int prev = s->head[h];
        uint64_t slot;
        if (prev >= 0 && prev > limit) {
            slot = ((uint64_t)read32(src + prev) << 32) | (uint32_t)prev;
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
HOT int lz4_compress_fast(const uint8_t * restrict src, uint8_t * restrict dst,
                      int src_len, uint64_t * restrict htab)
{
    if (src_len == 0) return 0;
    int op        = 0;
    int lit_start = 0;
    int pos       = 0;
    int safe_end  = src_len - PADDING_LITERALS;
    int loop_end  = src_len - MFLIMIT + 1;   /* pos < loop_end ⟹ pos ≤ srcLen-MFLIMIT (spec) */

    if (src_len < 4) goto emit_tail;       /* too short for any match */

    {
    uint32_t pos4 = read32(src + pos);
    uint32_t h    = lz4__hash4v(pos4);

    /* Adaptive skip for incompressible regions.
       After 128 consecutive miss-bytes, activate skip mode: advance by step
       without inserting skipped positions into htab (faster on random data).
       skip_ctr is a packed counter: step = (skip_ctr >> 6) + 1, max ~17. */
    int miss_bytes = 0;
    int skip_ctr   = 2 << 6;

    while (pos < loop_end) {
        if (__builtin_expect(miss_bytes >= 128, 0)) {
            int step = (skip_ctr >> 6) + 1;
            if (skip_ctr < (17 << 6)) skip_ctr++;
            pos += step;
            miss_bytes += step;
            if (pos >= loop_end) break;
            pos4 = read32(src + pos);
            h    = lz4__hash4v(pos4);
        }

        uint64_t slot = htab[h];
        htab[h]       = ((uint64_t)pos4 << 32) | (uint32_t)pos;

        /* Speculatively load pos+1 while we evaluate pos. */
        uint32_t pos4_1 = 0;
        uint32_t h1     = 0;
        if (__builtin_expect(pos + 1 < loop_end, 1)) {
            pos4_1 = read32(src + pos + 1);
            h1     = lz4__hash4v(pos4_1);
            __builtin_prefetch(htab + h1, 0, 3);
            if (__builtin_expect(pos + 2 < loop_end, 1)) {
                uint32_t pos4_2 = read32(src + pos + 2);
                __builtin_prefetch(htab + lz4__hash4v(pos4_2), 0, 3);
            }
        }

        /* Unsigned distance check: (uint32_t)(pos-sv)-1 < WINDOW_SIZE-1 handles
           sentinel (0x80808080…) naturally — wraps to a huge value, fails check. */
        uint32_t sv         = (uint32_t)slot;
        uint32_t match_dist = (uint32_t)pos - sv;
        if (match_dist - 1 < (uint32_t)(WINDOW_SIZE - 1) && (uint32_t)(slot >> 32) == pos4) {
            int max_match = safe_end - pos;
            int match_len = lz4__extend_match_avx2(src, (int)sv, pos, max_match);

            if (match_len >= MIN_MATCH) {
                miss_bytes = 0;
                skip_ctr   = 2 << 6;
                int match_extra = match_len - MIN_MATCH;
                lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, match_extra);
                write16(dst + op, (uint16_t)match_dist);
                op += 2;
                if (match_extra >= 15) lz4__write_length_overflow(dst, &op, match_extra);
                lit_start = pos + match_len;
                pos = lit_start;
                if (__builtin_expect(pos >= loop_end, 0)) break;
                pos4 = read32(src + pos);
                h    = lz4__hash4v(pos4);
                __builtin_prefetch(htab + h, 0, 3);
                continue;
            }
        }

        pos++;
        if (__builtin_expect(pos >= loop_end, 0)) {
            pos4 = read32(src + pos);
            h    = lz4__hash4v(pos4);
            break;
        }
        pos4 = pos4_1;
        h    = h1;

        uint64_t slot1      = htab[h];
        htab[h]             = ((uint64_t)pos4 << 32) | (uint32_t)pos;

        uint32_t sv1         = (uint32_t)slot1;
        uint32_t match_dist1 = (uint32_t)pos - sv1;
        if (match_dist1 - 1 < (uint32_t)(WINDOW_SIZE - 1) && (uint32_t)(slot1 >> 32) == pos4) {
            int max_match1 = safe_end - pos;
            int match_len1 = lz4__extend_match_avx2(src, (int)sv1, pos, max_match1);

            if (match_len1 >= MIN_MATCH) {
                miss_bytes = 0;
                skip_ctr   = 2 << 6;
                int match_extra1 = match_len1 - MIN_MATCH;
                lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, match_extra1);
                write16(dst + op, (uint16_t)match_dist1);
                op += 2;
                if (match_extra1 >= 15) lz4__write_length_overflow(dst, &op, match_extra1);
                lit_start = pos + match_len1;
                pos = lit_start;
                if (__builtin_expect(pos >= loop_end, 0)) break;
                pos4 = read32(src + pos);
                h    = lz4__hash4v(pos4);
                __builtin_prefetch(htab + h, 0, 3);
                continue;
            }
        }

        /* Both pos and pos+1 missed — advance pos+1 → pos+2 for next iteration. */
        miss_bytes += 2;
        pos++;
        pos4 = read32(src + pos);       /* safe: pos < loop_end < src_len - 4 */
        h    = lz4__hash4v(pos4);
    }

    if (pos < safe_end) {
        /* Handle the final position that the loop skipped (pos == loop_end).
           Insert but don't match — too close to end for a safe match extension. */
        if (pos + 5 <= src_len) {
            pos4        = read32(src + pos);
            h           = lz4__hash4v(pos4);
            htab[h]     = ((uint64_t)pos4 << 32) | (uint32_t)pos;
        }
        pos = src_len;
    }
    }

emit_tail:
    if (lit_start != src_len)
        lz4__emit_literals(dst, &op, src, lit_start, src_len - lit_start, 0);
    return op;
}

static int lz4__compress_block_chain(lz4_stream_t *s,
                                     const uint8_t * restrict src, uint8_t * restrict dst,
                                     int src_len, int max_chain)
{
    int op        = 0;
    int lit_start = 0;
    int pos       = 0;
    int safe_end  = src_len - PADDING_LITERALS;
    int miss_bytes = 0;
    int skip_ctr   = 2 << 6;

    while (pos < src_len) {
        /* Adaptive skip: in incompressible runs, jump forward without inserting
           skipped positions — mirrors the Java compressJavaImpl skip logic. */
        if (__builtin_expect(miss_bytes >= 128, 0)) {
            int step = (skip_ctr >> 6) + 1;
            if (skip_ctr < (17 << 6)) skip_ctr++;
            pos += step;
            miss_bytes += step;
            if (pos >= src_len) { pos = src_len; break; }
        }

        int match_dist = 0;
        int match_len  = lz4__insert_and_match(s, src, pos, src_len, max_chain, &match_dist);

        int lazy_probed = 0;
        /* Lazy matching: probe pos+1 only when match is short enough to benefit.
           Long matches (≥8 bytes) are rarely improved by one position of lookahead. */
        if (match_len >= MIN_MATCH && match_len < 8 && pos + 1 < src_len) {
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
            miss_bytes = 0;
            skip_ctr   = 2 << 6;
            int match_extra = match_len - MIN_MATCH;
            lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, match_extra);
            write16(dst + op, (uint16_t)match_dist);
            op += 2;
            if (match_extra >= 15) lz4__write_length_overflow(dst, &op, match_extra);
            lit_start = pos + match_len;
            int insert_end = lit_start < safe_end + 1 ? lit_start : safe_end + 1;
            /* If lazy_probed but lost, pos+1 was already inserted; start at pos+2
               to avoid a self-link in the chain. */
            int insert_start = pos + 1 + (lazy_probed ? 1 : 0);
            for (int ip = insert_start; ip < insert_end; ip += 2)
                lz4__insert(s, src, ip);
            pos = lit_start;
        } else {
            miss_bytes++;
            pos++;
        }
    }

    /* Final literal run — no match follows, so the match nibble is 0. */
    if (lit_start != pos)
        lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, 0);

    return op;
}

static int lz4__compress_dispatch(lz4_stream_t *s,
                                   const uint8_t * restrict src, uint8_t * restrict dst,
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

/* General block encoder: chain=1 defers to lz4_compress_fast(), chain>1 uses
 * lz4__compress_block_chain() via lz4__compress_dispatch(). */
#if defined(__x86_64__) || defined(_M_X64)
__attribute__((target("avx2")))
#endif
HOT int lz4_compress_block(lz4_stream_t *s,
                       const uint8_t * restrict src, uint8_t * restrict dst,
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
