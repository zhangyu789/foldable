package com.duo.foldable

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Apply horizontal Gaussian blur to the right region of a Bitmap (left stays sharp).
 *
 * @param blurEdge Normalized x where blur starts (0~1); blur begins at x >= blurEdge
 * @param radiusPx Max blur radius in pixels (relative to source image)
 */
object GaussianBlur {

    fun blurRightEdge(src: Bitmap, radiusPx: Float, blurEdge: Float) {
        if (radiusPx < 0.5f) return
        val w = src.width
        val h = src.height
        if (w <= 2 || h <= 2) return

        val edgeX = (blurEdge.coerceIn(0f, 1f) * (w - 1)).roundToInt()
        if (edgeX >= w - 1) return

        // Downsample for speed: process right region only
        val regionW = w - edgeX
        val scale = 4
        val sw = max(1, regionW / scale)
        val sh = max(1, h / scale)
        val small = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(src, edgeX, 0, regionW, h),
            sw, sh, true
        )

        val radius = max(1, (radiusPx / scale).roundToInt().coerceAtMost(12))
        boxBlurHorizontal(small, radius)
        boxBlurHorizontal(small, radius) // Two passes approximate Gaussian

        val restored = Bitmap.createScaledBitmap(small, regionW, h, true)
        small.recycle()

        // Gradient blend by distance from left boundary to avoid a hard cut
        val fade = max(1, (regionW * 0.2f).roundToInt())
        val srcPixels = IntArray(regionW * h)
        val blurPixels = IntArray(regionW * h)
        src.getPixels(srcPixels, 0, regionW, edgeX, 0, regionW, h)
        restored.getPixels(blurPixels, 0, regionW, 0, 0, regionW, h)
        restored.recycle()

        for (y in 0 until h) {
            val row = y * regionW
            for (x in 0 until regionW) {
                val t = if (x < fade) x.toFloat() / fade else 1f
                val i = row + x
                srcPixels[i] = lerpColor(srcPixels[i], blurPixels[i], t)
            }
        }
        src.setPixels(srcPixels, 0, regionW, edgeX, 0, regionW, h)
    }

    /** Simple horizontal box blur (in-place). */
    private fun boxBlurHorizontal(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val div = radius * 2 + 1

        for (y in 0 until h) {
            val row = y * w
            var r = 0
            var g = 0
            var b = 0
            var a = 0
            for (i in -radius..radius) {
                val p = pix[row + i.coerceIn(0, w - 1)]
                a += (p ushr 24) and 0xff
                r += (p ushr 16) and 0xff
                g += (p ushr 8) and 0xff
                b += p and 0xff
            }
            for (x in 0 until w) {
                out[row + x] =
                    ((a / div) shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
                val pOut = pix[row + (x - radius).coerceIn(0, w - 1)]
                val pIn = pix[row + (x + radius + 1).coerceIn(0, w - 1)]
                a += ((pIn ushr 24) and 0xff) - ((pOut ushr 24) and 0xff)
                r += ((pIn ushr 16) and 0xff) - ((pOut ushr 16) and 0xff)
                g += ((pIn ushr 8) and 0xff) - ((pOut ushr 8) and 0xff)
                b += (pIn and 0xff) - (pOut and 0xff)
            }
        }
        bmp.setPixels(out, 0, w, 0, 0, w, h)
    }

    private fun lerpColor(c0: Int, c1: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val a = ((c0 ushr 24) and 0xff) + ((((c1 ushr 24) and 0xff) - ((c0 ushr 24) and 0xff)) * u).roundToInt()
        val r = ((c0 ushr 16) and 0xff) + ((((c1 ushr 16) and 0xff) - ((c0 ushr 16) and 0xff)) * u).roundToInt()
        val g = ((c0 ushr 8) and 0xff) + ((((c1 ushr 8) and 0xff) - ((c0 ushr 8) and 0xff)) * u).roundToInt()
        val b = (c0 and 0xff) + (((c1 and 0xff) - (c0 and 0xff)) * u).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
