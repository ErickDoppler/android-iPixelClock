package com.example.ipixelclock.font

/**
 * Builds a [PixelFont] from ASCII art.
 *
 * The fonts could be written as column-bit hex tables — `LedPages` does, and so
 * do SYSTEM and NARROW, which are carried over from the driver and left in their
 * proven numeric form. For anything authored here that would be a mistake: a
 * 14-row glyph is a 14-bit number per column, nobody can read a design out of
 * `0x3F1E`, and a single wrong nibble is invisible in review and subtle on the
 * panel.
 *
 * So the new families are drawn. What is in the source is the shape:
 *
 * ```
 * '7' to """
 *     #########
 *     #########
 *            ##
 *           ##
 *          ##
 *     """
 * ```
 *
 * `#`, `X`, `*` and `@` are lit; every other character, space included, is not.
 * Leading and trailing blank lines are dropped, so the art can sit on its own
 * lines. Parsing happens once, at class-load, into exactly the same column-bit
 * arrays the hand-written tables use — the runtime cost is nil and the review
 * cost drops to "does it look like a seven".
 */
internal object PixelFontArt {

    private const val LIT = "#X*@"

    /**
     * One family cut. Every glyph is padded to the tallest, so art can be
     * written at its natural height and short glyphs (a colon, a full stop)
     * do not need packing rows by hand.
     *
     * [baseline] shifts a glyph down when it is shorter than the cut — 0 pins it
     * to the top, which is what a colon wants; `null` centres it, which is what
     * a full stop and a hyphen want.
     */
    fun font(
        id: String,
        spacing: Int = 1,
        monospaceDigits: Boolean = true,
        fallback: Char = '?',
        /**
         * Whether to strip blank columns from a glyph's edges, making the font
         * proportional. True for nearly everything.
         *
         * SEGMENT turns it off, because a seven-segment cell is a fixed grid of
         * lamps: its `1` is the two right-hand segments lit and nothing else, so
         * it belongs hard against the right of the cell. Trimmed and then
         * centred it floats in the middle, which no LCD has ever done.
         */
        trim: Boolean = true,
        /**
         * Glyphs to flip horizontally after drawing.
         *
         * For the MATRIX family, whose whole character is that several numerals
         * run backwards. Drawing them reversed by hand would work, but then the
         * art no longer looks like the digit it is and a reader cannot tell a
         * deliberate mirror from a mistake. Drawing them forwards and naming the
         * ones that flip says what is going on.
         */
        mirror: Set<Char> = emptySet(),
        art: List<Pair<Char, String>>
    ): PixelFont {
        val parsed = art.map { (ch, s) -> ch to rows(s) }
        val height = parsed.maxOfOrNull { it.second.size } ?: 0
        require(height in 1..32) { "font $id: height $height is outside 1..32" }

        val glyphs = HashMap<Char, IntArray>(parsed.size)
        for ((ch, lines) in parsed) {
            val cols = columns(lines, height, ch, id, trim)
            glyphs[ch] = if (ch in mirror) cols.reversedArray() else cols
        }
        return PixelFont(
            id = id,
            height = height,
            spacing = spacing,
            glyphs = glyphs,
            fallback = fallback,
            monospaceDigits = monospaceDigits
        )
    }

    /** Art to lines, with the blank lines around it trimmed off. */
    private fun rows(art: String): List<String> {
        val lines = art.split('\n').toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        if (lines.isEmpty()) return listOf("")
        // Strip the common indentation so the art can be indented to match the
        // surrounding Kotlin without shifting every glyph right.
        val indent = lines.filter { it.isNotBlank() }
            .minOfOrNull { line -> line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0) } ?: 0
        return lines.map { if (it.length >= indent) it.substring(indent) else "" }
    }

    /**
     * Lines to column bit-masks, bit 0 = top row.
     *
     * A glyph shorter than the cut is vertically centred, biased upwards, which
     * is what a hyphen and a full stop want and is harmless for anything that is
     * already full height.
     */
    private fun columns(
        lines: List<String>,
        height: Int,
        ch: Char,
        id: String,
        trim: Boolean
    ): IntArray {
        val width = lines.maxOfOrNull { it.length } ?: 0
        if (width == 0) return IntArray(0)
        require(width <= 64) { "font $id: glyph '$ch' is $width columns wide" }

        val top = (height - lines.size) / 2
        val out = IntArray(width)
        for (r in lines.indices) {
            val line = lines[r]
            val row = r + top
            for (c in line.indices) {
                if (LIT.indexOf(line[c]) >= 0) out[c] = out[c] or (1 shl row)
            }
        }
        if (!trim) return out

        // Trim columns that are blank on both edges: the art is a grid, but the
        // font is proportional and a '1' should not carry the width of an '8'
        // unless monospaceDigits puts it back.
        var first = 0
        var last = out.size - 1
        while (first <= last && out[first] == 0) first++
        while (last >= first && out[last] == 0) last--
        // A wholly blank glyph is the space: keep the width it was drawn at,
        // since that width is the whole point of it.
        if (first > last) return IntArray(width)
        return out.copyOfRange(first, last + 1)
    }
}
