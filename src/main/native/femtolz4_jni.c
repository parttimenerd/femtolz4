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

/* ── Thread-local compress stream ────────────────────────────────────────── */

/* One stream per thread, initialised lazily.  lz4_init() resets only head[],
   so repeated calls within the same thread skip the initial malloc and only
   pay the head[] memset (~32 KB) once per compress call. */
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

JNIEXPORT jint JNICALL
Java_me_bechberger_femtolz4_NativeLZ4_compress(JNIEnv *env, jclass cls,
    jbyteArray jSrc, jint src_off, jint src_len,
    jbyteArray jDst, jint dst_off, jint dst_len,
    jint max_chain)
{
    (void)cls;
    lz4_stream_t *s = get_stream();
    if (!s) return 0;
    lz4_init(s); /* reset head[]; tail[] needs no init (written before read) */

    jbyte *src = (*env)->GetPrimitiveArrayCritical(env, jSrc, NULL);
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, jDst, NULL);
    int result = lz4_compress_block(s,
                     (const uint8_t *)(src + src_off),
                     (uint8_t *)(dst + dst_off),
                     src_len, max_chain);
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
