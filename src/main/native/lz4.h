#ifndef LZ4_H
#define LZ4_H

#include <stdint.h>

/*
 * femtolz4 only ships little-endian native builds (darwin-aarch64, linux-amd64).
 *
 * Spec references:
 *   LZ4 block format: https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md
 *   LZ4 frame format: https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md
 */
#define LZ4_MAGIC 0x184C2102

#define BLOCK_SIZE  (1024 * 256)
#define EXCESS      (16 + (BLOCK_SIZE / 255))
#define WINDOW_SIZE (1 << 16)

/* 13-bit hash table: 8192 entries, used by the chain compressor. */
#define LZ4_HASH_BITS 13
#define LZ4_HASH_SIZE (1 << LZ4_HASH_BITS)

/* Fast-path (chain=1) hash table: 13-bit, uint64_t[8192] = 64 KB.
   Each slot stores: bits[63:32] = 4-byte value at position, bits[31:0] = position.
   Negative position (high bit set) = empty.  Reset with 0x80 sentinel.
   Storing the 4-byte value alongside pos avoids a cold src[sv] load on each probe. */
#define LZ4_HASH_BITS_FAST 13
#define LZ4_HASH_SIZE_FAST (1 << LZ4_HASH_BITS_FAST)
#define LZ4_HTAB_FAST_BYTES (LZ4_HASH_SIZE_FAST * sizeof(uint64_t))

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

/*
 * chain=1 fast-path: uses a caller-provided uint16_t[LZ4_HASH_SIZE_FAST] table
 * (8 KB), storing low 16 bits of position.  Table must be zeroed before the
 * first call; it need not be reset between independent blocks.
 */
int lz4_compress_fast(const uint8_t *src, uint8_t *dst,
                      int src_len, uint64_t *htab);

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
