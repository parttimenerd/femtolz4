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

/* 16-bit hash table: 65536 entries (= WINDOW_SIZE), used by the chain compressor.
   Wider than 13 bits reduces hash collisions: on large binary inputs the profiler
   showed ~39% of chain-8 CPU time spent rejecting unrelated candidates sharing
   a 13-bit bucket. */
#define LZ4_HASH_BITS 16
#define LZ4_HASH_SIZE (1 << LZ4_HASH_BITS)

/* Fast-path (chain=1) hash table: 12-bit, uint64_t[4096] = 32 KiB.
   32 KiB fits in the L1 data cache (32 KiB on Zen2/Zen3) — eliminates htab
   load misses which were the dominant stall at 13-bit (64 KiB > L1).
   Collision rate increase from 13→12 bits costs ~0.10x ratio but gains ~14% speed.
   Each slot stores: bits[63:32] = 4-byte value at position, bits[31:0] = position.
   Negative position (high bit set) = empty.  Reset with 0x80 sentinel. */
#define LZ4_HASH_BITS_FAST 12
#define LZ4_HASH_SIZE_FAST (1 << LZ4_HASH_BITS_FAST)
#define LZ4_HTAB_FAST_BYTES (LZ4_HASH_SIZE_FAST * sizeof(uint64_t))

/*
 * Chain tail: uint64_t[WINDOW_SIZE] = 512 KiB.
 * Each slot tail[pos & MASK] stores the link FROM pos TO its predecessor:
 *   bits[63:32] = 4-byte src value at the predecessor position (rejection filter)
 *   bits[31:0]  = predecessor absolute position (NIL = 0x80808080 sentinel)
 * Storing the absolute position (not a relative delta) avoids stale-slot
 * corruption: when tail[pos & MASK] is overwritten by a later position that
 * maps to the same slot, the stored absolute position fails the window check
 * (sv <= limit) and the chain walk terminates cleanly.
 * Storing the predecessor's 4-byte value avoids a cold src[sv] load on rejection.
 * NIL sentinel: head[] is initialised to -1 (all 0xFF bytes), and an entry with
 * prevPos < 0 (high bit set) is treated as end-of-chain.
 */
typedef struct {
    int      head[LZ4_HASH_SIZE];
    uint64_t tail[WINDOW_SIZE];
} lz4_stream_t;

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

/* chain=1 fast-path: uses a caller-provided uint64_t[LZ4_HASH_SIZE_FAST] table
 * (64 KiB), storing bits[63:32] = 4-byte src value and bits[31:0] = position.
 * Sentinel: reset table bytes to 0x80 before use (negative position = empty).
 */
int lz4_compress_fast(const uint8_t *src, uint8_t *dst,
                      int src_len, uint64_t *htab);

#endif /* LZ4_H */
