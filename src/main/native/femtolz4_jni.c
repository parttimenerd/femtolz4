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

/* ── Thread-local compress state ─────────────────────────────────────────── */

/* chain=1 fast path: generation-tagged uint32_t table (16 KB).
   No reset between calls — generation counter avoids memset. */
static _Thread_local uint32_t *tl_htab = NULL;
static _Thread_local uint16_t  tl_gen  = 0;

static uint32_t *get_htab(void)
{
    if (!tl_htab) {
        tl_htab = (uint32_t *)calloc(LZ4_HASH_SIZE_FAST, sizeof(uint32_t));
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

/* ── Decompress ─────────────────────────────────────────────────────────── */

#define D_MIN_MATCH 4

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
        /* __builtin_memcpy for small literals: compiler emits inline SIMD stores,
           avoiding the PLT call + vzeroupper transition penalty. */
        if (lit_len <= 16) {
            __builtin_memcpy(dst + op, src + ip, (size_t)lit_len);
        } else if (lit_len <= 32) {
            __builtin_memcpy(dst + op,      src + ip,      16);
            __builtin_memcpy(dst + op + 16, src + ip + 16, (size_t)lit_len - 16);
        } else if (lit_len <= 64) {
            __builtin_memcpy(dst + op,      src + ip,      32);
            __builtin_memcpy(dst + op + 32, src + ip + 32, (size_t)lit_len - 32);
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
            /* No overlap: bulk copy. Use __builtin_memcpy for small sizes so
               the compiler emits inline SIMD stores instead of a PLT call. */
            if (match_len <= 16) {
                __builtin_memcpy(dst + op, dst + ms, (size_t)match_len);
            } else if (match_len <= 32) {
                __builtin_memcpy(dst + op,      dst + ms,      16);
                __builtin_memcpy(dst + op + 16, dst + ms + 16, (size_t)match_len - 16);
            } else if (match_len <= 64) {
                __builtin_memcpy(dst + op,      dst + ms,      32);
                __builtin_memcpy(dst + op + 32, dst + ms + 32, (size_t)match_len - 32);
            } else {
                memcpy(dst + op, dst + ms, (size_t)match_len);
            }
        } else if (offset == 1) {
            /* Run of one repeated byte: fill is fastest. */
            memset(dst + op, dst[ms], (size_t)match_len);
        } else {
            /* Overlap: copy `offset` bytes at a time so earlier output
               bytes are replicated forward (like a SIMD splat). */
            int64_t i = 0;
            while (i + offset <= match_len) {
                memcpy(dst + op + i, dst + ms + i, (size_t)offset);
                i += offset;
            }
            memcpy(dst + op + i, dst + ms + i, (size_t)(match_len - i));
        }
        op += (int)match_len;
    }
    return op - dst_off;
}

/* ── JNI entry points ───────────────────────────────────────────────────── */

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
    int result;
    if (max_chain == 1) {
        uint32_t *htab = get_htab();
        if (!htab) { result = 0; goto done; }
        result = lz4_compress_fast(
                            (const uint8_t *)(src + src_off),
                            (uint8_t *)(dst + dst_off),
                            src_len, htab, ++tl_gen);
    } else {
        lz4_stream_t *s = get_stream();
        if (!s) { result = 0; goto done; }
        lz4_init(s);
        result = lz4_compress_block(s,
                     (const uint8_t *)(src + src_off),
                     (uint8_t *)(dst + dst_off),
                     src_len, max_chain);
    }
    done:
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
