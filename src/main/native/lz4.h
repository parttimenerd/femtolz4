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

#define SWAP32(x) ((uint32_t)( \
    (((x) & 0xFF000000u) >> 24) | \
    (((x) & 0x00FF0000u) >>  8) | \
    (((x) & 0x0000FF00u) <<  8) | \
    (((x) & 0x000000FFu) << 24) ))

#define BLOCK_SIZE  (1024*256)
#define EXCESS      (16+(BLOCK_SIZE/255))
#define WINDOW_SIZE (1<<16)

typedef struct {
    int head[1<<16];
    int tail[1<<16];
} lz4_stream_t;

void lz4_init(lz4_stream_t *s);

/* Compress src_len bytes from src into dst.
   dst must have capacity of at least src_len + EXCESS bytes.
   max_chain controls speed/ratio (1=fast, WINDOW_SIZE=best).
   Returns compressed size. */
int lz4_compress_block(lz4_stream_t *s,
                       const uint8_t *src, uint8_t *dst,
                       int src_len, int max_chain);

/* Stateless single-block compress (init + compress_block). */
int lz4_compress(const uint8_t *src, uint8_t *dst,
                 int src_len, int max_chain);

/* ── Standard LZ4 frame format ─────────────────────────────────────────── */

/* Write 7-byte frame header (magic + FLG + BD + HC) to dst. Returns 7. */
int lz4_frame_write_header(uint8_t *dst);

/* Write 4-byte block size field to size_field.
   Returns 1 if the caller should write the raw src_len bytes (incompressible),
   or 0 if the caller should write the comp_len compressed bytes. */
int lz4_frame_block_store(uint8_t *size_field, int comp_len, int src_len);

/* Write 4-byte end mark to dst. Returns 4. */
int lz4_frame_write_footer(uint8_t *dst);

#endif
