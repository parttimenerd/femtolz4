/*
 * Standalone fuzz / round-trip harness for femtolz4.
 *
 * Build and run:
 *   # For Valgrind:
 *   gcc -g -O1 -I../../main/native -o fuzz_harness_vg fuzz_harness.c
 *   valgrind --tool=memcheck --error-exitcode=1 ./fuzz_harness_vg
 *
 *   # ASAN build:
 *   gcc -g -O1 -fsanitize=address,undefined -I../../main/native -o fuzz_harness_asan fuzz_harness.c
 *   ./fuzz_harness_asan
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "lz4_src.c"

#ifndef BLOCK_SIZE
#define BLOCK_SIZE (256 * 1024)
#endif

/* Inline decompressor — avoids JNI dependency. */
static int femto_decompress_local(const uint8_t *src, int src_len,
                                   uint8_t *dst, int dst_len)
{
    int ip = 0, op = 0;
    while (ip < src_len) {
        int tok = src[ip++], ll = tok >> 4, mex = tok & 0xF, b;
        if (ll == 15) { do { if (ip >= src_len) return -1; b = src[ip++]; ll += b; } while (b == 255); }
        if (op + ll > dst_len || ip + ll > src_len) return -2;
        memcpy(dst + op, src + ip, ll); ip += ll; op += ll;
        if (ip >= src_len) break;
        if (ip + 2 > src_len) return -3;
        int off = (int)src[ip] | ((int)src[ip+1] << 8); ip += 2;
        if (off == 0) return -4;
        int ml = 4 + mex;
        if (mex == 15) { do { if (ip >= src_len) return -5; b = src[ip++]; ml += b; } while (b == 255); }
        int ms = op - off;
        if (ms < 0 || op + ml > dst_len) return -6;
        if (off >= ml) {
            memcpy(dst + op, dst + ms, ml);
        } else if (off == 1) {
            memset(dst + op, dst[ms], ml);
        } else {
            int i = 0;
            while (i + off <= ml) { memcpy(dst+op+i, dst+ms+i, off); i += off; }
            memcpy(dst+op+i, dst+ms+i, ml-i);
        }
        op += ml;
    }
    return op;
}

static uint64_t xorshift64(uint64_t *s) {
    *s ^= *s << 13; *s ^= *s >> 7; *s ^= *s << 17; return *s;
}

static void fill_random(uint8_t *buf, int len, uint64_t *seed) {
    for (int i = 0; i < len; i++) buf[i] = (uint8_t)xorshift64(seed);
}

static int run_roundtrip(const uint8_t *src, int src_len, int max_chain, const char *label) {
    int max_comp = src_len + 16 + src_len / 255 + 1;
    if (max_comp < 8) max_comp = 8;
    uint8_t *comp   = (uint8_t *)malloc(max_comp);
    uint8_t *decomp = (uint8_t *)malloc(src_len + 1);
    if (!comp || !decomp) { free(comp); free(decomp); return -99; }

    int comp_len;
    if (max_chain == 1) {
        int *htab = (int *)malloc(LZ4_HASH_SIZE_FAST * sizeof(int));
        if (!htab) { free(comp); free(decomp); return -99; }
        memset(htab, 0x80, LZ4_HASH_SIZE_FAST * sizeof(int));
        comp_len = lz4_compress_fast(src, comp, src_len, htab);
        free(htab);
    } else {
        lz4_stream_t *s = (lz4_stream_t *)malloc(sizeof(lz4_stream_t));
        if (!s) { free(comp); free(decomp); return -99; }
        lz4_init(s);
        comp_len = lz4_compress_block(s, src, comp, src_len, max_chain);
        free(s);
    }

    if (src_len == 0) {
        /* empty input: comp_len==0 is valid, decompress should return 0 */
        free(comp); free(decomp);
        return 0;
    }

    if (comp_len <= 0) {
        fprintf(stderr, "FAIL [%s] compress returned %d\n", label, comp_len);
        free(comp); free(decomp); return 1;
    }

    int decomp_len = femto_decompress_local(comp, comp_len, decomp, src_len);
    if (decomp_len != src_len || memcmp(src, decomp, src_len) != 0) {
        fprintf(stderr, "FAIL [%s] round-trip mismatch: comp=%d decomp=%d src=%d\n",
                label, comp_len, decomp_len, src_len);
        free(comp); free(decomp); return 1;
    }

    free(comp); free(decomp);
    return 0;
}

static int run_invalid_decomp(const uint8_t *garbage, int len, int dst_len, const char *label) {
    (void)label;
    uint8_t *dst = (uint8_t *)malloc(dst_len + 1);
    if (!dst) return 0;
    femto_decompress_local(garbage, len, dst, dst_len); /* must not crash */
    free(dst);
    return 0;
}

int main(void) {
    int failures = 0;
    uint64_t seed = 0xdeadbeefcafeULL;

    static const int sizes[] = {0, 1, 3, 4, 15, 16, 17, 255, 256, 257,
                                  1024, 4096, 65536, BLOCK_SIZE, 2*BLOCK_SIZE};
    static const int nchain[] = {1, 2, 4, 8};

    uint8_t *buf  = (uint8_t *)malloc(2 * BLOCK_SIZE + 64);
    uint8_t *buf2 = (uint8_t *)malloc(2 * BLOCK_SIZE + 64);

    /* ── Fixed sizes ── */
    for (int si = 0; si < (int)(sizeof(sizes)/sizeof(sizes[0])); si++) {
        int sz = sizes[si];
        char label[80];

        fill_random(buf, sz, &seed);
        for (int ci = 0; ci < (int)(sizeof(nchain)/sizeof(nchain[0])); ci++) {
            snprintf(label, sizeof(label), "random sz=%d chain=%d", sz, nchain[ci]);
            failures += run_roundtrip(buf, sz, nchain[ci], label);
        }

        memset(buf, 0, sz);
        snprintf(label, sizeof(label), "zeros sz=%d", sz);
        failures += run_roundtrip(buf, sz, 1, label);
        failures += run_roundtrip(buf, sz, 8, label);

        memset(buf, 0xAA, sz);
        snprintf(label, sizeof(label), "0xAA sz=%d", sz);
        failures += run_roundtrip(buf, sz, 1, label);

        for (int i = 0; i < sz; i++) buf[i] = (uint8_t)i;
        snprintf(label, sizeof(label), "ascending sz=%d", sz);
        failures += run_roundtrip(buf, sz, 1, label);
        failures += run_roundtrip(buf, sz, 8, label);
    }

    /* ── Random sizes ── */
    for (int i = 0; i < 200; i++) {
        int sz = (int)(xorshift64(&seed) % (BLOCK_SIZE + 1));
        fill_random(buf, sz, &seed);
        char label[80];
        snprintf(label, sizeof(label), "rnd-size iter=%d sz=%d", i, sz);
        failures += run_roundtrip(buf, sz, 1, label);
        failures += run_roundtrip(buf, sz, 4, label);
    }

    /* ── Invalid decompress: random garbage ── */
    for (int i = 0; i < 500; i++) {
        int sz = (int)(xorshift64(&seed) % 1024) + 1;
        fill_random(buf, sz, &seed);
        char label[80];
        snprintf(label, sizeof(label), "invalid-decomp iter=%d sz=%d", i, sz);
        failures += run_invalid_decomp(buf, sz, 4096, label);
    }

    /* ── Truncated valid compressed data ── */
    fill_random(buf, 1024, &seed);
    {
        int *htab = (int *)malloc(LZ4_HASH_SIZE_FAST * sizeof(int));
        memset(htab, 0x80, LZ4_HASH_SIZE_FAST * sizeof(int));
        int clen = lz4_compress_fast(buf, buf2, 1024, htab);
        free(htab);
        for (int trunc = 1; trunc < clen; trunc += (clen / 20) + 1) {
            char label[80];
            snprintf(label, sizeof(label), "truncated clen=%d trunc=%d", clen, trunc);
            failures += run_invalid_decomp(buf2, trunc, 1024, label);
        }
    }

    free(buf); free(buf2);

    if (failures == 0) { printf("All tests passed.\n"); return 0; }
    fprintf(stderr, "%d test(s) FAILED.\n", failures); return 1;
}
