package com.example.ipixelclock.led

import android.graphics.Bitmap
import com.example.ipixelclock.font.PixelFonts
import com.example.ipixelclock.render.PixelCanvas

/**
 * The one page [IPixelHub] draws for itself.
 *
 * The driver is copied verbatim between the apps that talk to a panel, and its
 * `disableWithGoodbye()` calls `LedPages.screenOff()` — without that sign-off
 * frame a panel keeps displaying whatever it was holding when the link dropped,
 * which looks exactly like a crash. So this object exists to satisfy the driver,
 * and nothing else: every other page in this app is composed by `FrameRenderer`.
 *
 * (In yacht-compass this file is a 500-line renderer of navigation pages. Here
 * it is the sign-off notice alone.)
 */
object LedPages {

    private const val ORANGE = 0xFFFF9000.toInt()

    /**
     * The orange "SCREEN OFF" notice, centred, stepping down through smaller
     * layouts until one fits the panel — a 32x16 module cannot carry the phrase
     * on one line, and a single-row panel can only carry "OFF".
     */
    fun screenOff(w: Int, h: Int): Bitmap {
        val canvas = PixelCanvas(w, h)
        canvas.clear()

        val big = PixelFonts.SYSTEM.shortest      // 5x7
        val small = PixelFonts.NARROW.shortest    // 3x5

        val full = "SCREEN OFF"
        val top = "SCREEN"
        val tail = "OFF"

        when {
            big.measure(full) <= w && h >= big.height ->
                big.draw(canvas, full, (w - big.measure(full)) / 2, (h - big.height) / 2, ORANGE)

            h >= big.height * 2 + 1 && big.measure(top) <= w -> {
                val y = (h - (big.height * 2 + 1)) / 2
                big.draw(canvas, top, (w - big.measure(top)) / 2, y, ORANGE)
                big.draw(canvas, tail, (w - big.measure(tail)) / 2, y + big.height + 1, ORANGE)
            }

            small.measure(full) <= w && h >= small.height ->
                small.draw(canvas, full, (w - small.measure(full)) / 2, (h - small.height) / 2, ORANGE)

            h >= small.height * 2 + 1 && small.measure(top) <= w -> {
                val y = (h - (small.height * 2 + 1)) / 2
                small.draw(canvas, top, (w - small.measure(top)) / 2, y, ORANGE)
                small.draw(canvas, tail, (w - small.measure(tail)) / 2, y + small.height + 1, ORANGE)
            }

            else ->
                small.draw(canvas, tail, (w - small.measure(tail)) / 2, (h - small.height) / 2, ORANGE)
        }

        return canvas.toBitmap()
    }
}
