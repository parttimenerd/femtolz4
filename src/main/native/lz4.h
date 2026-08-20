#ifndef LZ4_H
#define LZ4_H

#include <stdint.h>

#if defined __BYTE_ORDER__ && defined __ORDER_LITTLE_ENDIAN__ && \
    __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#  define LZ4_LITTLE_ENDIAN
#endif

#ifdef LZ4_LITTLE_ENDIAN
#  define LZ4_MAGIC 0x184C2102
#else
#  define LZ4_MAGIC 0x02214C18
#endif

#define SWAP32(x) ((uint32_t)(          \
    (((x) & 0xFF000000u) >> 24) |       \
    (((x) & 0x00FF0000u) >>  8) |       \
    (((x) & 0x0000FF00u) <<  8) |       \
    (((x) & 0x000000FFu) << 24) ))

#define BLOCK_SIZE  (1024 * 256)
#define EXCESS      (16 + (BLOCK_SIZE / 255))
#define WINDOW_SIZE (1 << 16)

/* 13-bit hash table: 8192 entries (32 KB), fits in L1 cache. */
#define LZ4_HASH_BITS 13
#define LZ4_HASH_SIZE (1 << LZ4_HASH_BITS)

typedef struct {
    int head[LZ4_HASH_SIZE];
    int tail[WINDOW_SIZE];
} lz4_stream_t;

/* Reset the stream (must be called before each new input block). */
void lz4_init(lz4_stream_t *s);

/* Compress src_len bytes from src into dst.
   dst must have at least src_len + EXCESS bytes of capacity.
   max_chain controls speed/ratio (1 = fastest, WINDOW_SIZE = best ratio).
   Returns the compressed size. */
int lz4_compress_block(lz4_stream_t *s,
                       const uint8_t *src, uint8_t *dst,
                       int src_len, int max_chain);

/* Stateless single-block compress (allocates, inits, compresses, frees). */
int lz4_compress(const uint8_t *src, uint8_t *dst,
                 int src_len, int max_chain);

/* ── Standard LZ4 frame format ─────────────────────────────────────────── */

/* Write the 7-byte frame header (magic + FLG + BD + HC) to dst.
   Returns 7. */
int lz4_frame_write_header(uint8_t *dst);

/* Write the 4-byte block-size field to size_field.
   Returns 1 if the caller should copy the raw src_len bytes (incompressible),
   or 0 if the caller should copy the comp_len compressed bytes. */
int lz4_frame_block_store(uint8_t *size_field, int comp_len, int src_len);

/* Write the 4-byte end mark to dst.  Returns 4. */
int lz4_frame_write_footer(uint8_t *dst);

#endif /* LZ4_H */
