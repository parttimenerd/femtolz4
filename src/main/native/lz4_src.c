#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "lz4.h"

/*
 * LZ4 frame format  https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md
 *
 * Header:  [magic 4B][FLG 1B][BD 1B][HC 1B]
 * Blocks:  [size 4B LE][data]  (size high bit = 1 → stored uncompressed)
 * Footer:  [0x00000000 4B]
 *
 * FLG 0x60 — version=01, block_independent=1, no checksums
 * BD  0x50 — block_maxsize=5 (256 KB)
 * HC       — (xxhash32(FLG+BD) >> 8) & 0xFF
 */

/* Byte-swap a 16-bit value (used on big-endian for the match offset field). */
#define SWAP16(x) ((uint16_t)((x) >> 8 | (x) << 8))

/* Left-rotate a 32-bit value.  Compilers emit a single ROL instruction. */
#define ROTL32(x, r) (((x) << (r)) | ((x) >> (32 - (r))))

#define PADDING_LITERALS 5
#define WINDOW_MASK      (WINDOW_SIZE - 1)
#define MIN_MATCH        4
#define HASH_BITS        16
#define NIL              (-1)
#define MIN_LOOKAHEAD    (PADDING_LITERALS + MIN_MATCH + 2)

#define MIN(a, b) ((a) < (b) ? (a) : (b))
#define MAX(a, b) ((a) > (b) ? (a) : (b))

/* ── Primitives ─────────────────────────────────────────────────────────── */

/* Bulk-copy len bytes using 8-byte stores.  dst must have 8 bytes of slack. */
static void lz4__copy(uint8_t *dst, const uint8_t *src, int len)
{
    if (len == 0) return;
    *(uint64_t *)dst = *(const uint64_t *)src;
    for (int i = 8; i < len; i += 8)
        *(uint64_t *)(dst + i) = *(const uint64_t *)(src + i);
}

/* Write overflow bytes for a length field that exceeded 15 (the nibble cap). */
static void lz4__write_length_overflow(uint8_t *dst, int *op, int len)
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
 *              low  nibble = match length - MIN_MATCH (capped at 15)
 * If either value overflows 15, extra bytes follow (255... then remainder).
 */
static void lz4__emit_literals(uint8_t *dst, int *op,
                                const uint8_t *src, int lit_start,
                                int lit_len, int match_extra)
{
    dst[(*op)++] = (uint8_t)((MIN(lit_len, 15) << 4) | MIN(match_extra, 15));
    if (lit_len >= 15)
        lz4__write_length_overflow(dst, op, lit_len);
    if (lit_len > 0) {
        lz4__copy(dst + *op, src + lit_start, lit_len);
        *op += lit_len;
    }
}

/* Write match-length overflow bytes (called when match_extra >= 15). */
static void lz4__emit_match_overflow(uint8_t *dst, int *op, int match_extra)
{
    if (match_extra >= 15)
        lz4__write_length_overflow(dst, op, match_extra);
}

/* 5-byte multiply-shift hash.  Folding in the 5th byte improves distribution
   on binary/heap data compared to a plain 4-byte hash. */
static uint32_t lz4__hash(const uint8_t *p)
{
    uint32_t v;
    memcpy(&v, p, 4);
    v ^= (uint32_t)p[4] << 24;
    return (v * 0x9E3779B9u) >> (32 - HASH_BITS);
}

/* Push position into the hash chain for src[pos]. */
static void lz4__insert(lz4_stream_t *s, const uint8_t *src, int pos)
{
    uint32_t h = lz4__hash(src + pos);
    s->tail[pos & WINDOW_MASK] = s->head[h];
    s->head[h] = pos;
}

/* ── Public API ─────────────────────────────────────────────────────────── */

void lz4_init(lz4_stream_t *s)
{
    /* tail is always written before it is read, so only head needs init. */
    memset(s->head, 0xff, sizeof(s->head));
}

int lz4_compress_block(lz4_stream_t *s,
                       const uint8_t *src, uint8_t *dst,
                       int src_len, int max_chain)
{
    int op        = 0;
    int lit_start = 0;
    int pos       = 0;

    while (pos < src_len) {
        int match_len  = 0;
        int match_dist = 0;
        int max_match  = (src_len - PADDING_LITERALS) - pos;

        /* ── hash-chain search ── */
        if (max_match >= MIN_LOOKAHEAD - PADDING_LITERALS) {
            int      limit     = MAX(pos - WINDOW_SIZE, NIL);
            int      chain_len = max_chain;
            uint32_t h         = lz4__hash(src + pos);
            uint32_t pos4;
            memcpy(&pos4, src + pos, 4);

            for (int sv = s->head[h]; sv > limit; sv = s->tail[sv & WINDOW_MASK]) {
                uint32_t sv4;
                memcpy(&sv4, src + sv, 4);

                /* Quick filter: first 4 bytes and the byte at current best
                   length must both match before we invest in full extension. */
                if (sv4 != pos4 || src[sv + match_len] != src[pos + match_len]) {
                    if (--chain_len == 0) break;
                    continue;
                }

                /* Extend 8 bytes at a time via XOR.  A nonzero XOR locates
                   the first differing byte via ctz (LE) or clz (BE). */
                int len = MIN_MATCH;
                while (len + 8 <= max_match) {
                    uint64_t sv8, pos8;
                    memcpy(&sv8,  src + sv  + len, 8);
                    memcpy(&pos8, src + pos + len, 8);
                    uint64_t diff = sv8 ^ pos8;
                    if (diff) {
#ifdef LZ4_LITTLE_ENDIAN
                        len += __builtin_ctzll(diff) >> 3;
#else
                        len += __builtin_clzll(diff) >> 3;
#endif
                        goto done_extend;
                    }
                    len += 8;
                }
                while (len < max_match && src[sv + len] == src[pos + len])
                    ++len;
                done_extend:

                if (len > match_len) {
                    match_len  = len;
                    match_dist = pos - sv;
                    if (len == max_match) break;
                }

                if (--chain_len == 0) break;
            }
        }

        /* ── emit sequence or advance ── */
        if (match_len >= MIN_MATCH) {
            int match_extra = match_len - MIN_MATCH;

            lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, match_extra);
#ifdef LZ4_LITTLE_ENDIAN
            *(uint16_t *)(dst + op) = (uint16_t)match_dist;
#else
            *(uint16_t *)(dst + op) = SWAP16((uint16_t)match_dist);
#endif
            op += 2;
            lz4__emit_match_overflow(dst, &op, match_extra);

            lit_start = pos + match_len;

            /* Stride-2 at max_chain==1: covers the same search space with
               half the insert cost on long matches. */
            int stride = (max_chain == 1) ? 2 : 1;
            while (pos < lit_start) { lz4__insert(s, src, pos); pos += stride; }
            pos = lit_start;
        } else {
            lz4__insert(s, src, pos++);
        }
    }

    /* Final literal run — no match follows, so match nibble is 0. */
    if (lit_start != pos)
        lz4__emit_literals(dst, &op, src, lit_start, pos - lit_start, 0);

    return op;
}

int lz4_compress(const uint8_t *src, uint8_t *dst, int src_len, int max_chain)
{
    lz4_stream_t s;
    lz4_init(&s);
    return lz4_compress_block(&s, src, dst, src_len, max_chain);
}

/* ── LZ4 frame helpers ─────────────────────────────────────────────────── */

/* xxHash-32 (seed=0).  Used only for the 2-byte header checksum input. */
static uint32_t lz4__xxhash32(const uint8_t *data, int len)
{
    static const uint32_t PRIME1 = 0x9E3779B1u;
    static const uint32_t PRIME2 = 0x85EBCA77u;
    static const uint32_t PRIME3 = 0xC2B2AE3Du;
    static const uint32_t PRIME4 = 0x27D4EB2Fu;
    static const uint32_t PRIME5 = 0x165667B1u;

    const uint8_t *p   = data;
    const uint8_t *end = data + len;
    uint32_t h = (uint32_t)len + PRIME5;  /* seed = 0 */

    for (; p + 4 <= end; p += 4) {
        uint32_t lane; memcpy(&lane, p, 4);
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
    dst[4] = 0x60;  /* FLG */
    dst[5] = 0x50;  /* BD  */
    dst[6] = (uint8_t)((lz4__xxhash32(dst + 4, 2) >> 8) & 0xFF);  /* HC */
    return 7;
}

int lz4_frame_block_store(uint8_t *size_field, int comp_len, int src_len)
{
    int      store_raw = (comp_len >= src_len);
    uint32_t field     = store_raw
                         ? ((uint32_t)src_len | 0x80000000u)
                         : (uint32_t)comp_len;
#ifdef LZ4_LITTLE_ENDIAN
    memcpy(size_field, &field, 4);
#else
    size_field[0] = (uint8_t)(field);
    size_field[1] = (uint8_t)(field >>  8);
    size_field[2] = (uint8_t)(field >> 16);
    size_field[3] = (uint8_t)(field >> 24);
#endif
    return store_raw;
}

int lz4_frame_write_footer(uint8_t *dst)
{
    dst[0] = dst[1] = dst[2] = dst[3] = 0x00;
    return 4;
}
