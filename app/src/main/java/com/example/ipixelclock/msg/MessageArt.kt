package com.example.ipixelclock.msg

import com.example.ipixelclock.font.PixelFont

/**
 * A line of text rasterised once into a pixel mask the effects can push around.
 *
 * Built for the same reason [com.example.ipixelclock.fx.DigitTransition] works
 * on glyph masks rather than drawing through the font: most of these effects
 * need to move a pixel somewhere the font would never put it — scatter it,
 * shear it, drop it in from above — and a plain boolean grid makes that cheap.
 *
 * Built once per run rather than per frame. A long message can be a couple of
 * thousand columns wide, and rasterising it twelve times a second would be work
 * done on the render thread for no reason.
 */
class MessageArt(val text: String, val font: PixelFont) {

    /** One character and where it sits along the line. */
    class Placed(val ch: Char, val x: Int, val width: Int)

    val height: Int = font.height

    val chars: List<Placed>

    /** Total rendered width, excluding the trailing inter-character gap. */
    val width: Int

    private val bits: BooleanArray

    init {
        val placed = ArrayList<Placed>(text.length)
        var cursor = 0
        for (ch in text) {
            val w = font.charWidth(ch)
            placed.add(Placed(ch, cursor, w))
            cursor += w + font.spacing
        }
        chars = placed
        width = (cursor - font.spacing).coerceAtLeast(1)

        bits = BooleanArray(width * height)
        for (p in placed) {
            val m = font.mask(p.ch)
            for (gy in 0 until m.height) {
                if (gy >= height) break
                for (gx in 0 until m.width) {
                    if (!m.at(gx, gy)) continue
                    val px = p.x + gx
                    if (px in 0 until width) bits[gy * width + px] = true
                }
            }
        }
    }

    fun at(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && bits[y * width + x]

    /** Lit pixels, for the effects that need a particle budget. */
    val litCount: Int by lazy { bits.count { it } }

    /** Runs [body] for every lit pixel. The inner loop of most effects. */
    inline fun forEachLit(body: (x: Int, y: Int) -> Unit) {
        for (y in 0 until height) for (x in 0 until width) if (at(x, y)) body(x, y)
    }
}

/**
 * Splits a message into chunks that fit across the panel.
 *
 * The effects that reveal text in place — fade, pop, scan, typewriter — have
 * nothing sensible to do with a line wider than the display. Rather than
 * silently clipping it, or forcing every effect to grow a scrolling mode, a long
 * message becomes several pages shown in turn, which is what the original spec
 * called "pages listing" and is what a station board does with a long notice.
 *
 * Breaks on spaces where it can. A single word wider than the panel is cut
 * mid-word, because the alternative is showing nothing.
 */
object MessagePager {

    fun pages(text: String, font: PixelFont, maxWidth: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (maxWidth <= 0 || font.measure(trimmed) <= maxWidth) return listOf(trimmed)

        val out = ArrayList<String>()
        val words = trimmed.split(' ').filter { it.isNotEmpty() }
        var line = StringBuilder()

        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.measure(candidate) <= maxWidth) {
                line = StringBuilder(candidate)
                continue
            }
            if (line.isNotEmpty()) {
                out.add(line.toString())
                line = StringBuilder()
            }
            // The word alone may still not fit; cut it where it stops fitting.
            var rest = word
            while (font.measure(rest) > maxWidth && rest.length > 1) {
                var cut = rest.length
                while (cut > 1 && font.measure(rest.substring(0, cut)) > maxWidth) cut--
                out.add(rest.substring(0, cut))
                rest = rest.substring(cut)
            }
            if (rest.isNotEmpty()) line = StringBuilder(rest)
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return if (out.isEmpty()) listOf(trimmed) else out
    }
}
