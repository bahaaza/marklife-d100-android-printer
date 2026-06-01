package com.marklife.d100printer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Renders each page of a PDF into a label-sized ARGB_8888 Bitmap.
 *
 * Each page is scaled proportionally to fit within [widthDots] × [heightDots]
 * and centred on a white canvas — matching the "contain + centre" behaviour of
 * the Node.js reference implementation.
 */
class PdfPageRenderer(private val resolver: ContentResolver) {

    companion object {
        private const val DEFAULT_WIDTH_DOTS = 800
        private const val DEFAULT_HEIGHT_DOTS = 1200
    }

    /**
     * Opens [uri], renders every page and returns a list of Bitmaps.
     * The caller is responsible for calling [Bitmap.recycle] when done.
     *
     * @param uri       Content or file URI pointing to a PDF document.
     * @param widthDots  Target canvas width  in dots (default 800 = 100 mm @ 203 dpi).
     * @param heightDots Target canvas height in dots (default 1200 = 150 mm @ 203 dpi).
     */
    fun renderAllPages(
        uri: Uri,
        widthDots: Int = DEFAULT_WIDTH_DOTS,
        heightDots: Int = DEFAULT_HEIGHT_DOTS,
    ): List<Bitmap> {
        val pfd: ParcelFileDescriptor = openPdfDescriptor(uri)

        return pfd.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount <= 0) {
                    throw IllegalArgumentException("PDF has no pages.")
                }

                (0 until renderer.pageCount).map { i ->
                    renderer.openPage(i).use { page ->
                        renderPage(page, widthDots, heightDots)
                    }
                }
            }
        }
    }

    fun renderFirstPagePreview(
        uri: Uri,
        widthDots: Int,
        heightDots: Int,
    ): Bitmap {
        val pfd: ParcelFileDescriptor = openPdfDescriptor(uri)
        return pfd.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount <= 0) {
                    throw IllegalArgumentException("PDF has no pages.")
                }
                renderer.openPage(0).use { page ->
                    renderPage(page, widthDots, heightDots)
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    private fun renderPage(page: PdfRenderer.Page, widthDots: Int, heightDots: Int): Bitmap {
        // PDF page dimensions are in points (1/72 inch).
        // Scale so the page fits inside the label while preserving aspect ratio.
        val pageW = page.width.toFloat()
        val pageH = page.height.toFloat()
        val scale = minOf(widthDots / pageW, heightDots / pageH)

        val scaledW = (pageW * scale).toInt().coerceAtLeast(1)
        val scaledH = (pageH * scale).toInt().coerceAtLeast(1)

        // Render the PDF page into a temporary bitmap.
        val pageBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
        val pageCanvas = Canvas(pageBitmap)
        pageCanvas.drawColor(Color.WHITE)
        page.render(
            pageBitmap,
            Rect(0, 0, scaledW, scaledH),
            null,
            PdfRenderer.Page.RENDER_MODE_FOR_PRINT
        )

        // Place the rendered page on a full-label-size white canvas, centred.
        val labelBitmap = Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888)
        val labelCanvas = Canvas(labelBitmap)
        labelCanvas.drawColor(Color.WHITE)
        val offsetX = ((widthDots  - scaledW) / 2).toFloat()
        val offsetY = ((heightDots - scaledH) / 2).toFloat()
        labelCanvas.drawBitmap(pageBitmap, offsetX, offsetY, null)

        pageBitmap.recycle()
        return labelBitmap
    }

    private fun openPdfDescriptor(uri: Uri): ParcelFileDescriptor {
        return if (uri.scheme == "file") {
            val path = uri.path ?: throw IllegalArgumentException("Cannot open file URI without path: $uri")
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            resolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Cannot open PDF URI: $uri")
        }
    }
}
