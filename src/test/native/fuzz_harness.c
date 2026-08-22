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
        int tok = src[ip++], mex = tok & 0xF, b;
        /* int64_t: same overflow guard as femtolz4_jni.c's femto_decompress. */
        int64_t ll = tok >> 4;
        if (ll == 15) { do { if (ip >= src_len) return -1; b = src[ip++]; ll += b; } while (b == 255); }
        if ((int64_t)op + ll > (int64_t)dst_len || (int64_t)ip + ll > (int64_t)src_len) return -2;
        memcpy(dst + op, src + ip, (size_t)ll); ip += (int)ll; op += (int)ll;
        if (ip >= src_len) break;
        if (ip + 2 > src_len) return -3;
        int off = (int)src[ip] | ((int)src[ip+1] << 8); ip += 2;
        if (off == 0) return -4;
        int64_t ml = 4 + mex;
        if (mex == 15) { do { if (ip >= src_len) return -5; b = src[ip++]; ml += b; } while (b == 255); }
        int ms = op - off;
        if (ms < 0 || (int64_t)op + ml > (int64_t)dst_len) return -6;
        if (off >= ml) {
            memcpy(dst + op, dst + ms, (size_t)ml);
        } else if (off == 1) {
            memset(dst + op, dst[ms], (size_t)ml);
        } else {
            int64_t i = 0;
            while (i + off <= ml) { memcpy(dst+op+i, dst+ms+i, (size_t)off); i += off; }
            memcpy(dst+op+i, dst+ms+i, (size_t)(ml-i));
        }
        op += (int)ml;
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
        uint64_t *htab = (uint64_t *)malloc(LZ4_HASH_SIZE_FAST * sizeof(uint64_t));
        if (!htab) { free(comp); free(decomp); return -99; }
        memset(htab, 0x80, LZ4_HASH_SIZE_FAST * sizeof(uint64_t));
        comp_len = lz4_compress_fast(src, comp, src_len, htab);
        free(htab);
    } else {
        lz4_stream_t *s = (lz4_stream_t *)malloc(sizeof(lz4_stream_t));
        if (!s) { free(comp); free(decomp); return -99; }
        memset(s, 0xff, sizeof(*s));
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

    /* ── Truncated valid compressed data (exhaustive: every offset) ── */
    fill_random(buf, 1024, &seed);
    {
        uint64_t *htab = (uint64_t *)malloc(LZ4_HASH_SIZE_FAST * sizeof(uint64_t));
        memset(htab, 0x80, LZ4_HASH_SIZE_FAST * sizeof(uint64_t));
        int clen = lz4_compress_fast(buf, buf2, 1024, htab);
        free(htab);
        for (int trunc = 0; trunc < clen; trunc++) {
            char label[80];
            snprintf(label, sizeof(label), "truncated clen=%d trunc=%d", clen, trunc);
            /* Every prefix of a valid compressed block must either be
               rejected cleanly (negative return) or, if accepted, must not
               read/write out of bounds — never crash. */
            failures += run_invalid_decomp(buf2, trunc, 1024, label);
            /* Also try with an undersized destination buffer, forcing the
               overflow checks to trigger before any input-underflow checks. */
            failures += run_invalid_decomp(buf2, trunc, 16, label);
        }
    }

    /* ── Crafted malicious inputs: length-field integer overflow attempts ──
     * A token with lit_len/mex nibble == 15 is followed by a chain of 0xFF
     * continuation bytes. A very long chain pushes the accumulated length
     * close to or past INT_MAX; the decompressor must reject it via the
     * int64_t-based bounds checks rather than wrapping to a small/negative
     * value and proceeding with an out-of-bounds copy.
     */
    {
        uint8_t *eviltoken = (uint8_t *)malloc(BLOCK_SIZE);
        if (eviltoken) {
            /* token=0xFF (lit_len nibble=15, match_extra nibble=15), then a
               long run of 0xFF length-overflow bytes, no terminator. */
            eviltoken[0] = 0xFF;
            memset(eviltoken + 1, 0xFF, BLOCK_SIZE - 1);
            failures += run_invalid_decomp(eviltoken, BLOCK_SIZE, 4096, "evil-literal-overflow");
            failures += run_invalid_decomp(eviltoken, BLOCK_SIZE, 16,   "evil-literal-overflow-small-dst");

            /* token=0x0F (0 literals, match_extra nibble=15) + offset=1 +
               long 0xFF chain for match_len overflow. */
            eviltoken[0] = 0x0F;
            eviltoken[1] = 0x01; eviltoken[2] = 0x00; /* offset = 1 */
            memset(eviltoken + 3, 0xFF, BLOCK_SIZE - 3);
            failures += run_invalid_decomp(eviltoken, BLOCK_SIZE, 4096, "evil-match-overflow");
            failures += run_invalid_decomp(eviltoken, BLOCK_SIZE, 16,   "evil-match-overflow-small-dst");

            free(eviltoken);
        }
    }

    /* ── Crafted malicious inputs: match offset pointing before dst start ── */
    {
        /* 1 literal 'A', then a match with a huge offset — matchSrc must be
           rejected as before the output buffer start, not wrap/underflow. */
        uint8_t evil[] = { 0x11, 'A', (uint8_t)0xFF, (uint8_t)0xFF };
        failures += run_invalid_decomp(evil, sizeof(evil), 4096, "evil-huge-offset");
    }

    free(buf); free(buf2);

    if (failures == 0) { printf("All tests passed.\n"); return 0; }
    fprintf(stderr, "%d test(s) FAILED.\n", failures); return 1;
}
