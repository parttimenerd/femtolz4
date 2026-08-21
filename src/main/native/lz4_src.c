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
#define ROTL32(x, r)   (((x) << (r)) | ((x) >> (32 - (r))))

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

/* 5-byte multiply-shift hash.  Folding in the 5th byte improves distribution
   on binary/heap data compared to a plain 4-byte hash. */
FORCE_INLINE uint32_t lz4__hash(const uint8_t *p)
{
    uint32_t v;
    memcpy(&v, p, 4);
    v ^= (uint32_t)p[4] << 24;
    return (v * 0x9E3779B9u) >> (32 - HASH_BITS);
}

/* 4-byte hash for the chain=1 fast path: one fewer load, ~2x faster. */
FORCE_INLINE uint32_t lz4__hash4(const uint8_t *p)
{
    uint32_t v;
    memcpy(&v, p, 4);
    return (v * 0x9E3779B9u) >> (32 - LZ4_HASH_BITS_FAST);
}

/* Compute hash directly from an already-loaded uint32_t value. */
FORCE_INLINE uint32_t lz4__hash4v(uint32_t v)
{
    return (v * 0x9E3779B9u) >> (32 - LZ4_HASH_BITS_FAST);
}

/* Push position into the hash chain for src[pos].
   Caller must ensure pos + 5 <= src_len (5 bytes needed by lz4__hash). */
FORCE_INLINE void lz4__insert(lz4_stream_t *s, const uint8_t *src, int pos)
{
    uint32_t h             = lz4__hash(src + pos);
    s->tail[pos & WINDOW_MASK] = s->head[h];
    s->head[h]             = pos;
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

/*
 * Fast-path for max_chain==1: flat hash table (head[] only, no tail[]).
 * Uses the full 13-bit / 5-byte hash to keep match quality.
 */
static int lz4__flat_match(lz4_stream_t *s, const uint8_t *src,
                            int pos, int src_len, int *out_dist)
{
    int max_match = (src_len - PADDING_LITERALS) - pos;
    if (max_match < MIN_MATCH) {
        if (pos + 5 <= src_len) s->head[lz4__hash(src + pos)] = pos;
        return 0;
    }

    int      limit = MAX(pos - WINDOW_SIZE, NIL);
    uint32_t h     = lz4__hash(src + pos);
    uint32_t pos4;
    memcpy(&pos4, src + pos, 4);

    int sv     = s->head[h];
    s->head[h] = pos;

    if (sv <= limit) { *out_dist = 0; return 0; }

    uint32_t sv4;
    memcpy(&sv4, src + sv, 4);
    if (sv4 != pos4) { *out_dist = 0; return 0; }

    int len = lz4__extend_match(src, sv, pos, max_match);
    *out_dist = pos - sv;
    return len;
}

/* Insert pos and find its best match in one pass (single hash computation). */
static int lz4__insert_and_match(lz4_stream_t *s, const uint8_t *src,
                                  int pos, int src_len, int max_chain,
                                  int *out_dist)
{
    int max_match = (src_len - PADDING_LITERALS) - pos;
    if (max_match < MIN_MATCH) {
        if (pos + 5 <= src_len) lz4__insert(s, src, pos);
        return 0;
    }

    int      limit     = MAX(pos - WINDOW_SIZE, NIL);
    int      chain_len = max_chain;
    uint32_t h         = lz4__hash(src + pos);
    uint32_t pos4;
    memcpy(&pos4, src + pos, 4);

    /* Insert pos into the chain before walking it. */
    s->tail[pos & WINDOW_MASK] = s->head[h];
    s->head[h]                 = pos;

    int best_len  = 0;
    int best_dist = 0;

    /* Walk from the second entry (pos was just inserted at head). */
    for (int sv = s->tail[pos & WINDOW_MASK]; sv > limit; sv = s->tail[sv & WINDOW_MASK]) {
        uint32_t sv4;
        memcpy(&sv4, src + sv, 4);
        if (__builtin_expect(sv4 != pos4 || src[sv + best_len] != src[pos + best_len], 1)) {
            if (--chain_len == 0) break;
            continue;
        }
        int len = lz4__extend_match(src, sv, pos, max_match);
        if (len > best_len) {
            best_len = len; best_dist = pos - sv;
            if (len == max_match) break;
        }
        if (--chain_len == 0) break;
    }
    *out_dist = best_dist;
    return best_len;
}

/* ── Public API ─────────────────────────────────────────────────────────── */

void lz4_init(lz4_stream_t *s)
{
    memset(s, 0xff, sizeof(*s));
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
HOT int lz4_compress_fast(const uint8_t *src, uint8_t *dst,
                      int src_len, uint64_t *htab)
{
    if (src_len == 0) return 0;
    int op        = 0;
    int lit_start = 0;
    int pos       = 0;
    int safe_end  = src_len - PADDING_LITERALS;

    uint32_t pos4;
    memcpy(&pos4, src + pos, 4);
    uint32_t h = lz4__hash4v(pos4);

    while (pos < safe_end - 1) {
        uint64_t slot = htab[h];
        htab[h]       = ((uint64_t)pos4 << 32) | (uint32_t)pos;

        int32_t sv = (int32_t)(uint32_t)slot;
        if (sv > pos - (int)WINDOW_SIZE && (uint32_t)(slot >> 32) == pos4) {
            int max_match = safe_end - pos;
            int match_len  = lz4__extend_match(src, sv, pos, max_match);
            int match_dist = pos - sv;

            if (match_len >= MIN_MATCH) {
                int match_extra = match_len - MIN_MATCH;
                lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, match_extra);
                { uint16_t off16 = (uint16_t)match_dist; memcpy(dst + op, &off16, 2); }
                op += 2;
                lz4__emit_match_overflow(dst, &op, match_extra);
                lit_start = pos + match_len;
                pos = lit_start;
                if (__builtin_expect(pos >= safe_end - 1, 0)) break;
                memcpy(&pos4, src + pos, 4);
                h = lz4__hash4v(pos4);
                __builtin_prefetch(htab + h, 0, 3);
                continue;
            }
        }

        pos++;
        if (__builtin_expect(pos >= safe_end - 1, 0)) { pos = src_len; break; }
        memcpy(&pos4, src + pos, 4);
        h = lz4__hash4v(pos4);
        __builtin_prefetch(htab + h, 0, 3);
    }

    if (lit_start != src_len)
        lz4__emit_literals(dst, &op, src, lit_start, src_len - lit_start, 0);
    return op;
}

#if defined(__x86_64__) || defined(_M_X64)
__attribute__((target("avx2")))
#endif
HOT int lz4_compress_block(lz4_stream_t *s,
                       const uint8_t *src, uint8_t *dst,
                       int src_len, int max_chain)
{
    int op        = 0;
    int lit_start = 0;
    int pos       = 0;
    int skip      = 1; /* skip acceleration: step = (skip>>6)+1, grows with consecutive misses */

    while (pos < src_len) {
        int match_dist = 0;
        int match_len;

        /* chain=1: flat hash table (head[] only, no tail[]), 12-bit hash, no lazy. */
        if (max_chain == 1) {
            match_len = lz4__flat_match(s, src, pos, src_len, &match_dist);
        } else {
            match_len = lz4__insert_and_match(s, src, pos, src_len, max_chain, &match_dist);
        }

        /* ── lazy matching (chain > 1 only) ─────────────────────────────── */
        if (match_len >= MIN_MATCH && max_chain > 1 && pos + 1 < src_len) {
            int lazy_dist = 0;
            int lazy_len  = lz4__insert_and_match(s, src, pos + 1, src_len, max_chain, &lazy_dist);
            if (lazy_len > match_len) {
                pos++;
                match_len  = lazy_len;
                match_dist = lazy_dist;
            }
        }

        /* ── emit sequence or advance ── */
        if (match_len >= MIN_MATCH) {
            skip = 1; /* reset skip counter */
            int match_extra = match_len - MIN_MATCH;

            lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, match_extra);
            { uint16_t off16 = (uint16_t)match_dist; memcpy(dst + op, &off16, 2); }
            op += 2;
            lz4__emit_match_overflow(dst, &op, match_extra);

            lit_start = pos + match_len;

            int stride = (max_chain == 1) ? 2 : 1;
            int insert_from = pos + 1;
            while (insert_from < lit_start) {
                if (max_chain == 1)
                    s->head[lz4__hash(src + insert_from)] = insert_from;
                else
                    lz4__insert(s, src, insert_from);
                insert_from += stride;
            }
            pos = lit_start;
        } else {
            if (max_chain == 1) {
                pos += (skip >> 6) + 1;
                if (pos > src_len) pos = src_len;
                if (skip < (17 << 6)) skip++;
            } else {
                pos++;
            }
        }
    }

    /* Final literal run — no match follows, so the match nibble is 0. */
    if (lit_start != pos)
        lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, 0);

    return op;
}

int lz4_compress(const uint8_t *src, uint8_t *dst, int src_len, int max_chain)
{
    lz4_stream_t *s = (lz4_stream_t *)malloc(sizeof(lz4_stream_t));
    if (!s) return 0;
    lz4_init(s);
    int n = lz4_compress_block(s, src, dst, src_len, max_chain);
    free(s);
    return n;
}

/* ── LZ4 frame helpers ─────────────────────────────────────────────────── */

/* xxHash-32 (seed=0).  Used only for the 1-byte header checksum.
 * Spec: https://github.com/Cyan4973/xxHash/blob/dev/doc/xxhash_spec.md */
static uint32_t lz4__xxhash32(const uint8_t *data, int len)
{
    static const uint32_t PRIME1 = 0x9E3779B1u;
    static const uint32_t PRIME2 = 0x85EBCA77u;
    static const uint32_t PRIME3 = 0xC2B2AE3Du;
    static const uint32_t PRIME4 = 0x27D4EB2Fu;
    static const uint32_t PRIME5 = 0x165667B1u;

    const uint8_t *p   = data;
    const uint8_t *end = data + len;
    uint32_t h = (uint32_t)len + PRIME5; /* seed = 0 */

    for (; p + 4 <= end; p += 4) {
        uint32_t lane;
        memcpy(&lane, p, 4);
        h += lane * PRIME3;
        h  = ROTL32(h, 17) * PRIME4;
    }
    for (; p < end; p++) {
        h += (uint32_t)*p * PRIME5;
        h  = ROTL32(h, 11) * PRIME1;
    }

    h ^= h >> 15; h *= PRIME2;
    h ^= h >> 13; h *= PRIME3;
    h ^= h >> 16;
    return h;
}

int lz4_frame_write_header(uint8_t *dst)
{
    dst[0] = 0x04; dst[1] = 0x22; dst[2] = 0x4D; dst[3] = 0x18; /* magic */
    dst[4] = 0x60; /* FLG */
    dst[5] = 0x50; /* BD  */
    dst[6] = (uint8_t)((lz4__xxhash32(dst + 4, 2) >> 8) & 0xFF); /* HC */
    return 7;
}

int lz4_frame_block_store(uint8_t *size_field, int comp_len, int src_len)
{
    int      store_raw = (comp_len >= src_len);
    uint32_t field     = store_raw
                         ? ((uint32_t)src_len | 0x80000000u)
                         : (uint32_t)comp_len;
    memcpy(size_field, &field, 4);
    return store_raw;
}

int lz4_frame_write_footer(uint8_t *dst)
{
    dst[0] = dst[1] = dst[2] = dst[3] = 0x00;
    return 4;
}
