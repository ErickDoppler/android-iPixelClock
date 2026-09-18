package com.example.ipixelclock.font

import com.example.ipixelclock.render.PixelCanvas

/**
 * A bitmap font for an LED matrix.
 *
 * Glyphs are column bit-masks with bit 0 at the top row, the same encoding
 * `LedPages.FONT` in the yacht-compass driver uses — an `Int` per column covers
 * anything up to 32 rows, which is every panel in the driver's type table. A
 * glyph's width is simply its array length, so fonts are proportional for free
 * and a colon can take one column while a digit takes five.
 *
 * Anti-aliased system typefaces are useless at this size: at 14 rows a TrueType
 * digit turns to mush, and there is no room for a grey ramp on a panel whose
 * pixels are 5 mm apart. Every set here is authored pixel by pixel.
 */
class PixelFont(
    /** Stable id used by the settings JSON and the web UI. */
    val id: String,
    /** Rows the glyphs occupy. */
    val height: Int,
    /** Blank columns inserted between glyphs. */
    val spacing: Int,
    private val glyphs: Map<Char, IntArray>,
    /** Drawn in place of a character the font does not have. */
    private val fallback: Char = '?',
    /** Fixed-width digits: every 0-9 padded to the widest, so a clock does not
     *  jitter when a 1 replaces an 8. Almost always what you want. */
    val monospaceDigits: Boolean = true
) {

    /** The widest digit, which fixed-width digit cells are padded to. */
    val digitWidth: Int = run {
        var w = 0
        for (c in '0'..'9') glyphs[c]?.let { if (it.size > w) w = it.size }
        w
    }

    fun has(ch: Char): Boolean = glyphs.containsKey(ch)

    fun glyph(ch: Char): IntArray? =
        glyphs[ch] ?: glyphs[ch.uppercaseChar()] ?: glyphs[fallback]

    /** Advance for one character, including the digit padding. */
    fun charWidth(ch: Char): Int {
        val g = glyph(ch) ?: return 0
        return if (monospaceDigits && ch in '0'..'9') digitWidth else g.size
    }

    /** Width of a string in pixels, excluding the trailing spacing. */
    fun measure(s: String): Int {
        if (s.isEmpty()) return 0
        var w = 0
        for (ch in s) w += charWidth(ch) + spacing
        return (w - spacing).coerceAtLeast(0)
    }

    /**
     * Draws [s] with its top-left at ([x], [y]).
     *
     * [coverage] scales the alpha of every pixel, which is how the transition
     * effects fade a whole string; [colorAt] lets a caller colour per pixel for
     * the gradient and rainbow modes, and defaults to the flat [color].
     */
    fun draw(
        canvas: PixelCanvas,
        s: String,
        x: Int,
        y: Int,
        color: Int,
        coverage: Float = 1f,
        colorAt: ((x: Int, y: Int) -> Int)? = null
    ) {
        var cx = x
        for (ch in s) {
            drawGlyph(canvas, ch, cx, y, color, coverage, colorAt)
            cx += charWidth(ch) + spacing
        }
    }

    /** One character. Digits are centred in their fixed-width cell. */
    fun drawGlyph(
        canvas: PixelCanvas,
        ch: Char,
        x: Int,
        y: Int,
        color: Int,
        coverage: Float = 1f,
        colorAt: ((x: Int, y: Int) -> Int)? = null
    ) {
        val g = glyph(ch) ?: return
        val cell = charWidth(ch)
        val offset = (cell - g.size) / 2
        for (col in g.indices) {
            val bits = g[col]
            if (bits == 0) continue
            for (row in 0 until height) {
                if (bits and (1 shl row) == 0) continue
                val px = x + offset + col
                val py = y + row
                canvas.blendA(px, py, colorAt?.invoke(px, py) ?: color, coverage)
            }
        }
    }

    /**
     * Renders one character into its own small mask, which is what the digit
     * transitions manipulate: they need to scroll, dissolve and rotate a glyph
     * independently of the canvas it eventually lands on.
     *
     * Returns a [cell] x [height] boolean grid, row-major.
     */
    fun mask(ch: Char): GlyphMask {
        val g = glyph(ch) ?: IntArray(0)
        val cell = charWidth(ch)
        val offset = (cell - g.size) / 2
        val bits = BooleanArray(cell * height)
        for (col in g.indices) {
            for (row in 0 until height) {
                if (g[col] and (1 shl row) != 0) {
                    val x = offset + col
                    if (x in 0 until cell) bits[row * cell + x] = true
                }
            }
        }
        return GlyphMask(ch, cell, height, bits)
    }

    /** A rasterised glyph the effects can push around. */
    class GlyphMask(
        val ch: Char,
        val width: Int,
        val height: Int,
        val bits: BooleanArray
    ) {
        fun at(x: Int, y: Int): Boolean =
            x in 0 until width && y in 0 until height && bits[y * width + x]

        /** Lit pixels, for the effects that need a budget (dust, shatter). */
        val litCount: Int by lazy { bits.count { it } }
    }

    /**
     * A copy with every pixel spread onto a coarser grid, one lit dot per
     * source pixel and [pitch] - 1 blank rows and columns between.
     *
     * This is how the DOTMATRIX family is built: the shapes are a font that is
     * already known to read well, and the visible pitch between the dots is the
     * whole effect. Deriving it beats drawing it — there is no chance of the
     * dotted 8 disagreeing with the solid one.
     */
    fun dotted(pitch: Int): PixelFont {
        if (pitch <= 1) return this
        val newHeight = (height - 1) * pitch + 1
        if (newHeight > 32) return this
        val out = HashMap<Char, IntArray>(glyphs.size)
        for ((ch, cols) in glyphs) {
            val wide = IntArray((cols.size - 1).coerceAtLeast(0) * pitch + 1)
            for (c in cols.indices) {
                var bits = 0
                for (row in 0 until height) {
                    if (cols[c] and (1 shl row) != 0) bits = bits or (1 shl (row * pitch))
                }
                wide[c * pitch] = bits
            }
            out[ch] = wide
        }
        return PixelFont(
            id = "$id-dot$pitch",
            height = newHeight,
            spacing = spacing * pitch,
            glyphs = out,
            fallback = fallback,
            monospaceDigits = monospaceDigits
        )
    }

    /**
     * An integer-scaled copy. Nearest-neighbour by whole multiples only —
     * fractional scaling on a 7-row font produces broken stems, so the renderer
     * would rather pick a smaller authored size than a scaled ugly one.
     */
    fun scaled(factor: Int): PixelFont {
        // A column is a single Int, so 32 rows is the hard ceiling — which is
        // also the tallest panel in the driver's type table.
        if (factor <= 1 || height * factor > 32) return this
        val out = HashMap<Char, IntArray>(glyphs.size)
        for ((ch, cols) in glyphs) {
            val wide = IntArray(cols.size * factor)
            for (c in cols.indices) {
                var bits = 0
                for (row in 0 until height) {
                    if (cols[c] and (1 shl row) != 0) {
                        for (r in 0 until factor) bits = bits or (1 shl (row * factor + r))
                    }
                }
                for (f in 0 until factor) wide[c * factor + f] = bits
            }
            out[ch] = wide
        }
        return PixelFont(
            id = id,
            height = height * factor,
            spacing = spacing * factor,
            glyphs = out,
            fallback = fallback,
            monospaceDigits = monospaceDigits
        )
    }
}
