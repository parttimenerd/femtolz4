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

/* chain=1 fast path: 8 KB uint16_t table storing low-16 position deltas.
   Zeroed once at thread-init time; no reset needed between blocks. */
static _Thread_local uint16_t *tl_htab = NULL;

/* chain≥2: full stream (head[] + tail[]), reset (memset head[]) each call. */
static _Thread_local lz4_stream_t *tl_stream = NULL;

static uint16_t *get_htab(void)
{
    if (!tl_htab) {
        tl_htab = (uint16_t *)calloc(LZ4_HASH_SIZE_FAST, sizeof(uint16_t));
    }
    return tl_htab;
}

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
        int lit_len = token >> 4;
        int mex     = token & 0xF;
        int b;

        if (__builtin_expect(lit_len == 15, 0)) {
            do {
                if (ip >= src_end) return -1;
                b = src[ip++];
                lit_len += b;
            } while (b == 255);
        }
        if (op + lit_len > dst_end) return -2;
        if (ip + lit_len > src_end) return -3;
        memcpy(dst + op, src + ip, lit_len);
        ip += lit_len;
        op += lit_len;

        if (ip >= src_end) break; /* last sequence has no match */

        if (ip + 2 > src_end) return -4;
        int offset = (int)src[ip] | ((int)src[ip + 1] << 8);
        ip += 2;
        if (offset == 0) return -5;

        int match_len = D_MIN_MATCH + mex;
        if (__builtin_expect(mex == 15, 0)) {
            do {
                if (ip >= src_end) return -6;
                b = src[ip++];
                match_len += b;
            } while (b == 255);
        }

        int ms = op - offset;
        if (ms < dst_off)             return -7;
        if (op + match_len > dst_end) return -8;
        if (offset >= match_len) {
            /* No overlap: bulk copy. */
            memcpy(dst + op, dst + ms, match_len);
        } else if (offset == 1) {
            /* Run of one repeated byte: fill is fastest. */
            memset(dst + op, dst[ms], match_len);
        } else {
            /* Overlap: copy `offset` bytes at a time so earlier output
               bytes are replicated forward (like a SIMD splat). */
            int i = 0;
            while (i + offset <= match_len) {
                memcpy(dst + op + i, dst + ms + i, offset);
                i += offset;
            }
            memcpy(dst + op + i, dst + ms + i, match_len - i);
        }
        op += match_len;
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
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, jDst, NULL);
    int result;
    if (max_chain == 1) {
        uint16_t *htab = get_htab();
        if (!htab) { result = 0; goto done; }
        memset(htab, 0, LZ4_HASH_SIZE_FAST * sizeof(uint16_t));
        result = lz4_compress_fast(
                            (const uint8_t *)(src + src_off),
                            (uint8_t *)(dst + dst_off),
                            src_len, htab);
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
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, jDst, NULL);
    int result = femto_decompress((const uint8_t *)src, src_off, src_len,
                                  (uint8_t *)dst,       dst_off, dst_len);
    (*env)->ReleasePrimitiveArrayCritical(env, jDst, dst, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, jSrc, src, JNI_ABORT);
    return result;
}
