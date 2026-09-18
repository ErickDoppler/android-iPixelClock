package com.example.ipixelclock.fx

import com.example.ipixelclock.font.PixelFont
import com.example.ipixelclock.render.PixelCanvas

/**
 * An effect played on one character cell while its digit changes.
 *
 * Effects work on [PixelFont.GlyphMask] rather than drawing through the font,
 * because most of them need to push a glyph's pixels around independently of
 * where it will land — scroll it off the top, scatter it, dissolve it one pixel
 * at a time. The mask is a plain boolean grid, so that is all cheap.
 *
 * Everything is a pure function of `(progress, cell, pixel)`. Nothing keeps
 * state between frames: at 5–12 fps a stateful particle system would stutter
 * visibly whenever the panel skipped a frame, whereas a function of progress
 * lands exactly where it should whenever it happens to be asked.
 */
interface DigitTransition {

    /** Stable id, stored in the settings and sent in `/api/schema`. */
    val id: String

    /** What the picker calls it. */
    val label: String

    /** Draws the cell. [TransitionContext.progress] runs 0..1. */
    fun draw(c: TransitionContext)
}

/**
 * Everything an effect needs to draw one cell, and the primitives to do it with.
 *
 * Coordinates handed to [put] are cell-local: (0, 0) is the cell's top-left, and
 * anything outside the cell is clipped, so an effect can scroll a glyph out
 * without worrying about its neighbours.
 */
class TransitionContext(
    val canvas: PixelCanvas,
    val font: PixelFont,
    /** Cell origin on the canvas. */
    val cellX: Int,
    val cellY: Int,
    val cellW: Int,
    val cellH: Int,
    /** The character being replaced; null on the first paint. */
    val prev: Char?,
    val next: Char,
    /** 0 at the moment of change, 1 when settled. */
    val progress: Float,
    val color: Int,
    /** Face-wide opacity from the visibility policy. */
    val coverage: Float,
    val colorAt: ((Int, Int) -> Int)?,
    /** Stable per cell, so noise does not reshuffle every frame. */
    val seed: Int
) {

    /** Blends one cell-local pixel, clipped to the cell. */
    fun put(x: Int, y: Int, alpha: Float = 1f) {
        if (x < 0 || y < 0 || x >= cellW || y >= cellH) return
        val px = cellX + x
        val py = cellY + y
        canvas.blendA(px, py, colorAt?.invoke(px, py) ?: color, alpha * coverage)
    }

    /** Blends with an explicit colour — the glitch and burn-in effects. */
    fun putColor(x: Int, y: Int, c: Int, alpha: Float = 1f) {
        if (x < 0 || y < 0 || x >= cellW || y >= cellH) return
        canvas.blendA(cellX + x, cellY + y, c, alpha * coverage)
    }

    fun mask(ch: Char): PixelFont.GlyphMask = font.mask(ch)

    val nextMask: PixelFont.GlyphMask get() = mask(next)

    val prevMask: PixelFont.GlyphMask? get() = prev?.let { mask(it) }

    /** Horizontal offset that centres a mask in the cell. */
    fun centre(m: PixelFont.GlyphMask): Int = (cellW - m.width) / 2

    /** Draws a mask shifted by ([dx], [dy]) at [alpha]. */
    fun drawMask(m: PixelFont.GlyphMask, dx: Int = 0, dy: Int = 0, alpha: Float = 1f) {
        if (alpha <= 0f) return
        val ox = centre(m) + dx
        for (gy in 0 until m.height) {
            for (gx in 0 until m.width) {
                if (m.at(gx, gy)) put(ox + gx, gy + dy, alpha)
            }
        }
    }

    /**
     * A stable pseudo-random 0..1 for a pixel of this cell's current change.
     * Deterministic in the seed, so the same pixel scatters the same way for
     * the whole transition instead of twitching every frame.
     */
    fun noise(a: Int, b: Int = 0): Float {
        var h = seed * -0x61c88647
        h = h xor (a + -0x61c88647 + (h shl 6) + (h ushr 2))
        h = h xor (b + -0x7a143589 + (h shl 6) + (h ushr 2))
        h = h xor (h ushr 15)
        return ((h ushr 8) and 0xFFFF) / 65535f
    }
}

/** The registry the settings and `/api/schema` read from. */
object Transitions {

    val all: List<DigitTransition> = listOf(
        Switch, Fade, ScrollUp, ScrollDown, ScrollLeft, ScrollRight,
        Flip, PageList, Grow, Shrink, MatrixTrace, Assemble, Dissolve,
        Glitch, Typewriter, Ripple, BurnIn, Wipe, Shatter, Jump, RandomPick
    )

    private val byId = all.associateBy { it.id }

    fun of(id: String?): DigitTransition = byId[id] ?: Fade

    /** `(id, label)` pairs for the picker. */
    fun options(): List<Pair<String, String>> = all.map { it.id to it.label }
}
