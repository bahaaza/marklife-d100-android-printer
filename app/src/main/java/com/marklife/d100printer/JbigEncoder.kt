package com.marklife.d100printer

/**
 * JNI wrapper for JBIG1 encoding via libjbig (jbigkit).
 *
 * The native library is compiled from the jbigkit source files that must be
 * placed in `app/src/main/cpp/jbigkit/` before building.  See README.md for
 * the one-liner to obtain them.
 *
 * The parameters passed to the underlying jbg_enc_options() call match the
 * pbmtojbg invocation used by the Node.js reference implementation:
 *
 *   pbmtojbg -q -s 128 -m 127 -p 0 -o 0 -
 *
 * Meaning: stripe height 128, Mx=127, no progressive layers, order flags = 0.
 */
object JbigEncoder {

    init {
        System.loadLibrary("jbig_encoder")
    }

    /**
     * Encodes a 1-bpp packed pixel array into a JBIG1 BIE byte stream.
     *
     * @param pixels  Packed 1-bit rows, MSB = leftmost pixel, each row padded to
     *                the next full byte.  Size must be `ceil(width/8) * height`.
     * @param width   Image width in pixels.
     * @param height  Image height in pixels (≤ 255 for a single D100 block).
     * @return        Raw JBIG1 BIE bytes ready to embed in a `1F 28 4A` block.
     */
    @JvmStatic
    external fun encode(pixels: ByteArray, width: Int, height: Int): ByteArray
}
