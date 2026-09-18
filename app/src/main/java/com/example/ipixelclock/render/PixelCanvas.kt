package com.example.ipixelclock.render

import android.graphics.Bitmap

/**
 * An ARGB pixel buffer sized to the panel, with the handful of primitives an
 * LED matrix actually needs.
 *
 * Panels here are tiny — 96x16 is 1536 pixels — so everything works on a plain
 * [IntArray] and nothing is worth optimising further. What matters instead is
 * alpha: the clock face has to fade over a moving background, so every draw
 * call goes through [blend] rather than overwriting, and effects can carry a
 * 0..1 coverage value per pixel.
 *
 * Colours are 0xAARRGGBB throughout, matching Android's own convention and the
 * `LedPages` palette the driver's goodbye frame uses.
 */
class PixelCanvas(val width: Int, val height: Int) {

    val pixels = IntArray(width * height)

    val lastX get() = width - 1
    val lastY get() = height - 1

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

    // ------------------------------------------------------------- clearing

    fun clear(color: Int = BLACK) {
        java.util.Arrays.fill(pixels, color)
    }

    fun copyFrom(other: PixelCanvas) {
        if (other.width == width && other.height == height) {
            System.arraycopy(other.pixels, 0, pixels, 0, pixels.size)
        }
    }

    // -------------------------------------------------------------- reading

    fun get(x: Int, y: Int): Int =
        if (inBounds(x, y)) pixels[y * width + x] else 0

    // -------------------------------------------------------------- writing

    /** Replaces the pixel outright, alpha and all. */
    fun set(x: Int, y: Int, color: Int) {
        if (inBounds(x, y)) pixels[y * width + x] = color
    }

    /** Source-over blend, honouring the source alpha. The common path. */
    fun blend(x: Int, y: Int, color: Int) {
        if (!inBounds(x, y)) return
        val sa = (color ushr 24) and 0xFF
        if (sa == 0) return
        val i = y * width + x
        if (sa == 255) {
            pixels[i] = color
            return
        }
        val dst = pixels[i]
        val ia = 255 - sa
        val r = (((color ushr 16) and 0xFF) * sa + ((dst ushr 16) and 0xFF) * ia) / 255
        val g = (((color ushr 8) and 0xFF) * sa + ((dst ushr 8) and 0xFF) * ia) / 255
        val b = ((color and 0xFF) * sa + (dst and 0xFF) * ia) / 255
        pixels[i] = ALPHA_MASK or (r shl 16) or (g shl 8) or b
    }

    /** Blend scaled by an extra 0..1 coverage — how effects fade a glyph in. */
    fun blendA(x: Int, y: Int, color: Int, coverage: Float) {
        if (coverage <= 0f) return
        if (coverage >= 1f) {
            blend(x, y, color)
            return
        }
        val a = (((color ushr 24) and 0xFF) * coverage).toInt().coerceIn(0, 255)
        blend(x, y, (color and 0x00FFFFFF) or (a shl 24))
    }

    /** Additive blend — glows, sparks, and anything that should read as light. */
    fun add(x: Int, y: Int, color: Int, intensity: Float = 1f) {
        if (!inBounds(x, y) || intensity <= 0f) return
        val i = y * width + x
        val dst = pixels[i]
        val r = (((dst ushr 16) and 0xFF) + ((color ushr 16) and 0xFF) * intensity).toInt()
        val g = (((dst ushr 8) and 0xFF) + ((color ushr 8) and 0xFF) * intensity).toInt()
        val b = ((dst and 0xFF) + (color and 0xFF) * intensity).toInt()
        pixels[i] = ALPHA_MASK or
            (r.coerceAtMost(255) shl 16) or
            (g.coerceAtMost(255) shl 8) or
            b.coerceAtMost(255)
    }

    // ----------------------------------------------------------- primitives

    fun hline(y: Int, x0: Int, x1: Int, color: Int) {
        val a = minOf(x0, x1)
        val b = maxOf(x0, x1)
        for (x in a..b) blend(x, y, color)
    }

    fun vline(x: Int, y0: Int, y1: Int, color: Int) {
        val a = minOf(y0, y1)
        val b = maxOf(y0, y1)
        for (y in a..b) blend(x, y, color)
    }

    fun fillRect(x0: Int, y0: Int, w: Int, h: Int, color: Int) {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) blend(x, y, color)
    }

    fun rect(x0: Int, y0: Int, w: Int, h: Int, color: Int) {
        hline(y0, x0, x0 + w - 1, color)
        hline(y0 + h - 1, x0, x0 + w - 1, color)
        vline(x0, y0, y0 + h - 1, color)
        vline(x0 + w - 1, y0, y0 + h - 1, color)
    }

    /** Bresenham, for the backgrounds that draw actual lines. */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            blend(x, y, color)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    // ----------------------------------------------------------- whole-frame

    /** Multiplies every pixel's RGB — the software half of the brightness
     *  control, which smooths out panels whose hardware levels are coarse. */
    fun scaleBrightness(factor: Float) {
        if (factor >= 0.999f) return
        val f = factor.coerceIn(0f, 1f)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (((p ushr 16) and 0xFF) * f).toInt()
            val g = (((p ushr 8) and 0xFF) * f).toInt()
            val b = ((p and 0xFF) * f).toInt()
            pixels[i] = ALPHA_MASK or (r shl 16) or (g shl 8) or b
        }
    }

    /** Fades the whole buffer towards black — the trail behind matrix rain,
     *  starfields and anything else that leaves a wake. */
    fun fadeToBlack(amount: Float) {
        if (amount <= 0f) return
        val keep = (1f - amount).coerceIn(0f, 1f)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (((p ushr 16) and 0xFF) * keep).toInt()
            val g = (((p ushr 8) and 0xFF) * keep).toInt()
            val b = ((p and 0xFF) * keep).toInt()
            pixels[i] = ALPHA_MASK or (r shl 16) or (g shl 8) or b
        }
    }

    /** The panel wants an opaque bitmap; the driver reads RGB and drops alpha. */
    fun toBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    /** Row-major RGB888 — the same layout `IPixelHub.rgb888` produces, used to
     *  push preview frames down the WebSocket without going through a Bitmap. */
    fun toRgb888(): ByteArray {
        val out = ByteArray(width * height * 3)
        var o = 0
        for (p in pixels) {
            out[o++] = (p shr 16).toByte()
            out[o++] = (p shr 8).toByte()
            out[o++] = p.toByte()
        }
        return out
    }

    companion object {
        const val ALPHA_MASK = 0xFF000000.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val TRANSPARENT = 0

        /** Packs a colour with an explicit 0..255 alpha. */
        fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or
                ((g and 0xFF) shl 8) or (b and 0xFF)

        /** Replaces a colour's alpha, keeping its RGB. */
        fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

        /** Linear interpolation between two ARGB colours. */
        fun lerp(a: Int, b: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            val aa = ((a ushr 24) and 0xFF) + (((b ushr 24 and 0xFF) - (a ushr 24 and 0xFF)) * f).toInt()
            val ar = ((a ushr 16) and 0xFF) + (((b ushr 16 and 0xFF) - (a ushr 16 and 0xFF)) * f).toInt()
            val ag = ((a ushr 8) and 0xFF) + (((b ushr 8 and 0xFF) - (a ushr 8 and 0xFF)) * f).toInt()
            val ab = (a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * f).toInt()
            return argb(aa, ar, ag, ab)
        }

        /** HSV with h in degrees, s/v in 0..1 — the rainbow and gradient modes. */
        fun hsv(h: Float, s: Float, v: Float, alpha: Int = 255): Int {
            val hh = ((h % 360f) + 360f) % 360f / 60f
            val c = v * s
            val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
            val m = v - c
            val (r, g, b) = when (hh.toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return argb(
                alpha,
                ((r + m) * 255).toInt(),
                ((g + m) * 255).toInt(),
                ((b + m) * 255).toInt()
            )
        }
    }
}
