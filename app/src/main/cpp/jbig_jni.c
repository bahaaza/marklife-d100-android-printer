/*
 * jbig_jni.c — JNI bridge between JbigEncoder.kt and libjbig (jbigkit 2.1).
 *
 * Encoding parameters mirror the Node.js reference:
 *   pbmtojbg -q -s 128 -m 127 -p 0 -o 0 -
 *
 *  l0     = 128  (stripe height, -s)
 *  mx     = 127  (Mx parameter, -m)
 *  order  = 0    (no JBG_DELAY_AT etc., -o 0)
 *  options= 0     (equivalent to pbmtojbg -p 0 -o 0)
 *  my     = 0    (default)
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "jbig.h"

#define TAG "D100JbigEncoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---------------------------------------------------------------------------
 * Growing output buffer fed via the jbig callback.
 * --------------------------------------------------------------------------- */
typedef struct {
    unsigned char *data;
    size_t         size;
    size_t         capacity;
    int            failed;
} OutBuf;

static void jbig_output_cb(unsigned char *start, size_t len, void *arg)
{
    OutBuf *buf = (OutBuf *)arg;

    if (buf->failed) {
        return;
    }

    if (buf->size + len > buf->capacity) {
        size_t new_cap = buf->capacity == 0 ? 8192 : buf->capacity * 2;
        while (new_cap < buf->size + len) new_cap *= 2;

        unsigned char *tmp = (unsigned char *)realloc(buf->data, new_cap);
        if (!tmp) {
            LOGE("Out of memory growing JBIG output buffer");
            buf->failed = 1;
            return;
        }
        buf->data     = tmp;
        buf->capacity = new_cap;
    }

    memcpy(buf->data + buf->size, start, len);
    buf->size += len;
}

/* ---------------------------------------------------------------------------
 * Java_com_marklife_d100printer_JbigEncoder_encode
 *
 * JNI signature: (encode([BII)[B)
 *
 * @param pixels  1-bpp packed rows; each row is ceil(width/8) bytes, MSB first.
 * @param width   Image width in pixels.
 * @param height  Image height in pixels.
 * @return        Raw JBIG1 BIE bytes, or null on failure.
 * --------------------------------------------------------------------------- */
JNIEXPORT jbyteArray JNICALL
Java_com_marklife_d100printer_JbigEncoder_encode(
        JNIEnv  *env,
        jobject  thiz,
        jbyteArray pixels_array,
        jint     width,
        jint     height)
{
    (void)thiz;

    if (!pixels_array || width <= 0 || height <= 0) {
        LOGE("encode(): invalid arguments (pixels=%p, width=%d, height=%d)",
             (void *)pixels_array, width, height);
        return NULL;
    }

    jsize arr_len = (*env)->GetArrayLength(env, pixels_array);
    jbyte *pixels = (*env)->GetByteArrayElements(env, pixels_array, NULL);
    if (!pixels) {
        LOGE("encode(): GetByteArrayElements failed");
        return NULL;
    }

    size_t row_bytes = ((size_t)width + 7u) / 8u;
    size_t expected  = row_bytes * (size_t)height;

    if ((size_t)arr_len < expected) {
        LOGE("encode(): pixel buffer too small (%d bytes, need %zu)", arr_len, expected);
        (*env)->ReleaseByteArrayElements(env, pixels_array, pixels, JNI_ABORT);
        return NULL;
    }

    /* jbg_enc_init expects an array of plane pointers (1 plane for monochrome). */
    unsigned char *planes[1] = { (unsigned char *)pixels };

    struct jbg_enc_state state;
    OutBuf out = { NULL, 0, 0, 0 };

    jbg_enc_init(&state,
                 (unsigned long)width,
                 (unsigned long)height,
                 1,            /* planes */
                 planes,
                 jbig_output_cb,
                 &out);

    /*
     * jbg_enc_options(s, order, options, l0, mx, my)
     *
     * Matches: pbmtojbg -q -s 128 -m 127 -p 0 -o 0 -
     *  order   = 0
     *  options = 0
     *  l0      = 128
     *  mx      = 127
     *  my      = -1 (library default, same as pbmtojbg)
     */
    jbg_enc_options(&state,
                    0,
                    0,
                    128,
                    127,
                    -1);

    jbg_enc_out(&state);
    jbg_enc_free(&state);

    (*env)->ReleaseByteArrayElements(env, pixels_array, pixels, JNI_ABORT);

    if (out.failed) {
        LOGE("encode(): JBIG output buffer allocation failed");
        free(out.data);
        return NULL;
    }

    if (!out.data || out.size == 0) {
        LOGE("encode(): JBIG encoding produced no output");
        free(out.data);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)out.size);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)out.size,
                                   (const jbyte *)out.data);
    }

    free(out.data);
    return result;
}
