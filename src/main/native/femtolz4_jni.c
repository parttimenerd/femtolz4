/*
 * JNI bindings for femtolz4.
 *
 * Compress uses the existing lz4.c implementation (lz4_compress).
 * Decompress is a direct C port of LZ4.java's decompress() method.
 *
 * No external dependencies — lz4.c is compiled in directly.
 */

/* Pull in the minimal lz4 implementation directly */
#include "lz4_src.c"

#include <jni.h>
#include <stdint.h>
#include <string.h>

/* ── Decompress ────────────────────────────────────────────────────────────── */

#define D_MIN_MATCH 4

static int femto_decompress(const uint8_t *src, int src_off, int src_len,
                             uint8_t *dst,       int dst_off, int dst_len)
{
    int ip     = src_off;
    int src_end = src_off + src_len;
    int op     = dst_off;
    int dst_end = dst_off + dst_len;

    while (ip < src_end) {
        int token  = src[ip++];
        int lit_len = token >> 4;
        int mex    = token & 0xF;
        int b;

        if (lit_len == 15) {
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

        if (ip >= src_end) break;

        if (ip + 2 > src_end) return -4;
        int offset = (int)src[ip] | ((int)src[ip+1] << 8);
        ip += 2;
        if (offset == 0) return -5;

        int match_len = D_MIN_MATCH + mex;
        if (mex == 15) {
            do {
                if (ip >= src_end) return -6;
                b = src[ip++];
                match_len += b;
            } while (b == 255);
        }

        int match_src = op - offset;
        if (match_src < dst_off) return -7;
        if (op + match_len > dst_end) return -8;
        /* byte-by-byte copy handles overlap (offset < match_len) */
        for (int i = 0; i < match_len; i++)
            dst[op + i] = dst[match_src + i];
        op += match_len;
    }
    return op - dst_off;
}

/* ── JNI entry points ─────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_me_bechberger_femtolz4_NativeLZ4_compress(JNIEnv *env, jclass cls,
    jbyteArray jSrc, jint src_off, jint src_len,
    jbyteArray jDst, jint dst_off, jint dst_len)
{
    (void)cls;
    jbyte *src = (*env)->GetPrimitiveArrayCritical(env, jSrc, NULL);
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, jDst, NULL);
    int result = lz4_compress((const uint8_t *)(src + src_off),
                              (uint8_t *)(dst + dst_off),
                              src_len, 1);
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
