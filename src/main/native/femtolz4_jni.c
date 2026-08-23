/*
 * JNI bindings for femtolz4.
 *
 * Compress uses the minimal lz4 implementation via a thread-local stream,
 * avoiding per-call malloc and memset overhead.
 * Decompress is a C port of LZ4.java's decompress() method.
 */

/* Pull in the minimal lz4 implementation directly. */
#include "lz4_src.c"

#include <jni.h>
#include <stdint.h>
#include <string.h>

/* chain=1 fast path: uint64_t[LZ4_HASH_SIZE_FAST] table (64 KB).
   Each slot: bits[63:32] = 4-byte src value, bits[31:0] = position.
   Reset to sentinel 0x80 per call so stale entries never match across calls. */
static _Thread_local uint64_t *tl_htab = NULL;

static uint64_t *get_htab(void)
{
    if (!tl_htab) {
        tl_htab = (uint64_t *)malloc(LZ4_HASH_SIZE_FAST * sizeof(uint64_t));
        if (tl_htab)
            memset(tl_htab, 0x80, LZ4_HASH_SIZE_FAST * sizeof(uint64_t));
    }
    return tl_htab;
}

/* chain>=2: full stream (head[] + tail[]), reset each call. */
static _Thread_local lz4_stream_t *tl_stream = NULL;

static lz4_stream_t *get_stream(void)
{
    if (!tl_stream)
        tl_stream = (lz4_stream_t *)malloc(sizeof(lz4_stream_t));
    return tl_stream;
}

#define D_MIN_MATCH 4

/*
 * Compile femto_decompress as AVX2 on x86-64 so the compiler never emits
 * vzeroupper when transitioning between the inline YMM copies below and the
 * surrounding scalar control flow.  On other architectures the attribute is
 * a no-op.
 */
#if defined(__x86_64__) || defined(_M_X64)
__attribute__((target("avx2")))
#endif
static int femto_decompress(const uint8_t *src, int src_off, int src_len,
                             uint8_t *dst,       int dst_off, int dst_len)
{
    int ip      = src_off;
    int src_end = src_off + src_len;
    int op      = dst_off;
    int dst_end = dst_off + dst_len;

    while (ip < src_end) {
        int token   = src[ip++];
        /* int64_t accumulators: a crafted/truncated block with many 0xFF
           continuation bytes must not be able to overflow a 32-bit length
           into a negative value and slip past the bounds checks below. */
        int64_t lit_len = token >> 4;
        int mex     = token & 0xF;
        int b;

        if (__builtin_expect(lit_len == 15, 0)) {
            do {
                if (ip >= src_end) return -1;
                b = src[ip++];
                lit_len += b;
            } while (b == 255);
        }
        if ((int64_t)op + lit_len > (int64_t)dst_end) return -2;
        if ((int64_t)ip + lit_len > (int64_t)src_end) return -3;
        /* __builtin_memcpy cascade: compiler emits inline YMM stores throughout,
           no vzeroupper+PLT call.  Literal runs > 128 bytes are rare in typical
           heap data, so the final memcpy is not on the hot path. */
        if (lit_len <= 16) {
            __builtin_memcpy(dst + op, src + ip, (size_t)lit_len);
        } else if (lit_len <= 32) {
            __builtin_memcpy(dst + op,      src + ip,      16);
            __builtin_memcpy(dst + op + 16, src + ip + 16, (size_t)lit_len - 16);
        } else if (lit_len <= 64) {
            __builtin_memcpy(dst + op,      src + ip,      32);
            __builtin_memcpy(dst + op + 32, src + ip + 32, (size_t)lit_len - 32);
        } else if (lit_len <= 128) {
            __builtin_memcpy(dst + op,      src + ip,       64);
            __builtin_memcpy(dst + op + 64, src + ip + 64, (size_t)lit_len - 64);
        } else {
            memcpy(dst + op, src + ip, (size_t)lit_len);
        }
        ip += (int)lit_len;
        op += (int)lit_len;

        if (ip >= src_end) break; /* last sequence has no match */

        if (ip + 2 > src_end) return -4;
        int offset = (int)src[ip] | ((int)src[ip + 1] << 8);
        ip += 2;
        if (offset == 0) return -5;

        int64_t match_len = D_MIN_MATCH + mex;
        if (__builtin_expect(mex == 15, 0)) {
            do {
                if (ip >= src_end) return -6;
                b = src[ip++];
                match_len += b;
            } while (b == 255);
        }

        int ms = op - offset;
        if (ms < dst_off)                          return -7;
        if ((int64_t)op + match_len > (int64_t)dst_end) return -8;
        if (offset >= match_len) {
            /* No overlap: inline YMM copies up to 128 bytes; larger fall back
               to memcpy (still within the avx2 target, so no vzeroupper). */
            if (match_len <= 16) {
                __builtin_memcpy(dst + op, dst + ms, (size_t)match_len);
            } else if (match_len <= 32) {
                __builtin_memcpy(dst + op,      dst + ms,      16);
                __builtin_memcpy(dst + op + 16, dst + ms + 16, (size_t)match_len - 16);
            } else if (match_len <= 64) {
                __builtin_memcpy(dst + op,      dst + ms,      32);
                __builtin_memcpy(dst + op + 32, dst + ms + 32, (size_t)match_len - 32);
            } else if (match_len <= 128) {
                __builtin_memcpy(dst + op,      dst + ms,       64);
                __builtin_memcpy(dst + op + 64, dst + ms + 64, (size_t)match_len - 64);
            } else {
                memcpy(dst + op, dst + ms, (size_t)match_len);
            }
        } else if (offset == 1) {
            /* Run of one repeated byte: fill is fastest. */
            memset(dst + op, dst[ms], (size_t)match_len);
        } else {
            /* Overlapping match (offset < match_len, offset >= 2):
               Build up a 16-byte tile by doubling the pattern, then
               blast forward with 16-byte copies.  The key property:
               after each copy step dst[op..op+copied-1] = the tiled
               pattern, so reading from dst[op+copied-16..] is valid. */
            uint8_t *d  = dst + op;
            int64_t rem = match_len;
            int64_t c   = 0;

            /* Prime: copy the first `offset` bytes from source. */
            __builtin_memcpy(d, dst + ms, (size_t)offset);
            c = offset;

            /* Double until c >= 8 or we'd exceed rem. */
            while (c < 8 && c + c <= rem) {
                __builtin_memcpy(d + c, d, (size_t)c);
                c += c;
            }
            /* Extend to 8 if not there yet (and rem allows). */
            if (c < 8 && rem >= 8) {
                __builtin_memcpy(d + c, d, (size_t)(8 - c));
                c = 8;
            }
            /* Extend to 16. */
            if (c < 16 && rem >= 16) {
                __builtin_memcpy(d + c, d, (size_t)(16 - c));
                c = 16;
            }

            /* Blast: 16-byte copies reading from the previous 16 bytes. */
            while (c + 16 <= rem) {
                __builtin_memcpy(d + c, d + c - 16, 16);
                c += 16;
            }

            /* Tail: fill the remaining < 16 bytes from the start of the tile. */
            if (c < rem) {
                /* c >= 8 here (either via the extend steps or because offset >= 8),
                   or c == offset if rem < 8; in both cases d[0..min(c,16)-1] holds
                   a valid tiled pattern, so mod-wrapping via (rem-c) is safe. */
                __builtin_memcpy(d + c, d, (size_t)(rem - c));
            }
        }
        op += (int)match_len;
    }
    return op - dst_off;
}

/*
 * JNI_OnLoad: verify that the CPU supports every SIMD/ISA feature compiled in.
 * Returns JNI_ERR if a required feature is absent — System.load() will then
 * throw UnsatisfiedLinkError, and NativeLZ4.AVAILABLE stays false so the
 * Java fallback is used instead of crashing with SIGILL.
 *
 * On x86-64 (amd64) we require: SSE2, AVX2, FMA, BMI1, BMI2, POPCNT.
 * On AArch64, NEON is mandatory — no runtime check needed.
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm; (void)reserved;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
    unsigned int eax, ebx, ecx, edx;

    /* ── Leaf 1: SSE2, POPCNT, FMA, XSAVE ── */
    __asm__ volatile("cpuid" : "=a"(eax),"=b"(ebx),"=c"(ecx),"=d"(edx)
                             : "a"(1), "c"(0));
    if (!(edx & (1u << 26))) return JNI_ERR;  /* SSE2 (EDX bit 26) */
    if (!(ecx & (1u << 23))) return JNI_ERR;  /* POPCNT (ECX bit 23) */
    if (!(ecx & (1u << 12))) return JNI_ERR;  /* FMA (ECX bit 12) */
    if (!(ecx & (1u << 27))) return JNI_ERR;  /* XSAVE/XRESTORE (ECX bit 27) — needed for AVX2 */

    /* ── OS must have enabled YMM state (XCR0 bits 2:1) ── */
    unsigned int xcr0_lo;
    __asm__ volatile("xgetbv" : "=a"(xcr0_lo) : "c"(0) : "edx");
    if ((xcr0_lo & 0x6u) != 0x6u) return JNI_ERR;

    /* ── Leaf 7 sub-leaf 0: AVX2, BMI1, BMI2 ── */
    __asm__ volatile("cpuid" : "=a"(eax),"=b"(ebx),"=c"(ecx),"=d"(edx)
                             : "a"(7), "c"(0));
    if (!(ebx & (1u <<  3))) return JNI_ERR;  /* BMI1 (EBX bit 3) */
    if (!(ebx & (1u <<  5))) return JNI_ERR;  /* AVX2 (EBX bit 5) */
    if (!(ebx & (1u <<  8))) return JNI_ERR;  /* BMI2 (EBX bit 8) */

    (void)eax; (void)ecx; (void)edx;
#endif
    /* ARM NEON is mandatory on AArch64 — no runtime check needed. */
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_me_bechberger_femtolz4_NativeLZ4_compress(JNIEnv *env, jclass cls,
    jbyteArray jSrc, jint src_off, jint src_len,
    jbyteArray jDst, jint dst_off, jint dst_len,
    jint max_chain)
{
    (void)cls;
    jbyte *src = (*env)->GetPrimitiveArrayCritical(env, jSrc, NULL);
    if (!src) return 0;
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, jDst, NULL);
    if (!dst) {
        (*env)->ReleasePrimitiveArrayCritical(env, jSrc, src, JNI_ABORT);
        return 0;
    }
    uint64_t     *htab = max_chain == 1 ? get_htab() : NULL;
    lz4_stream_t *s    = max_chain == 1 ? NULL       : get_stream();
    if ((max_chain == 1 && !htab) || (max_chain != 1 && !s)) {
        (*env)->ReleasePrimitiveArrayCritical(env, jDst, dst, 0);
        (*env)->ReleasePrimitiveArrayCritical(env, jSrc, src, JNI_ABORT);
        return 0;
    }
    int result = lz4__compress_dispatch(s,
                     (const uint8_t *)(src + src_off),
                     (uint8_t *)(dst + dst_off),
                     src_len, max_chain, htab);
    (*env)->ReleasePrimitiveArrayCritical(env, jDst, dst, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, jSrc, src, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL
Java_me_bechberger_femtolz4_NativeLZ4_decompress(JNIEnv *env, jclass cls,
    jbyteArray jSrc, jint src_off, jint src_len,
    jbyteArray jDst, jint dst_off, jint dst_len)
{
    (void)cls;
    jbyte *src = (*env)->GetPrimitiveArrayCritical(env, jSrc, NULL);
    if (!src) return -1;
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, jDst, NULL);
    if (!dst) {
        (*env)->ReleasePrimitiveArrayCritical(env, jSrc, src, JNI_ABORT);
        return -1;
    }
    int result = femto_decompress((const uint8_t *)src, src_off, src_len,
                                  (uint8_t *)dst,       dst_off, dst_len);
    (*env)->ReleasePrimitiveArrayCritical(env, jDst, dst, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, jSrc, src, JNI_ABORT);
    return result;
}
