package com.example.ipixelclock.font

/**
 * The font library.
 *
 * A [Family] is one typeface authored at one or more heights; the renderer asks
 * for the tallest cut that fits the panel and falls back down the list, so a
 * 96x16 panel gets the 14-row cut of a family and a 32-row one gets the 24-row
 * cut without the settings having to know anything about panel sizes.
 *
 * Every set is an original design. The named inspirations (the Matrix's falling
 * katakana, the Star Wars crawl titles, Star Trek's LCARS panels) are
 * trademarked typefaces that cannot be redistributed in an APK, so what is here
 * is drawn in that *style* rather than traced from them — which is also the only
 * way to get stems that land on exact pixel boundaries at 14 rows.
 *
 * Column-byte encoding, bit 0 = top row, same as the driver's own `LedPages`.
 */
object PixelFonts {

    /** One typeface, in the cuts it was authored at. */
    class Family(
        val id: String,
        val name: String,
        /** One line for the web UI's font picker. */
        val blurb: String,
        cuts: List<PixelFont>
    ) {
        /** Tallest first, so [fit] can take the first that qualifies. */
        val cuts: List<PixelFont> = cuts.sortedByDescending { it.height }

        val heights: List<Int> get() = cuts.map { it.height }

        val shortest: PixelFont get() = cuts.last()

        /**
         * The best cut for [maxHeight] rows: the tallest authored cut that fits,
         * integer-upscaled if even the shortest cut leaves more than double the
         * room spare. Null when nothing fits at all, which only happens on a
         * panel shorter than the shortest cut.
         */
        fun fit(maxHeight: Int): PixelFont? {
            cuts.firstOrNull { it.height <= maxHeight }?.let { best ->
                val factor = maxHeight / best.height
                return if (factor >= 2) best.scaled(factor) else best
            }
            return null
        }
    }

    // ---------------------------------------------------------------- SYSTEM

    /**
     * The classic 5x7, carried over verbatim from the yacht-compass panel
     * renderer where it has been legible on a real 96x16 matrix for a year.
     * It is the safety net: every other family falls back to this one.
     */
    private val SYSTEM_5X7 = PixelFont(
        id = "system-7",
        height = 7,
        spacing = 1,
        monospaceDigits = true,
        glyphs = mapOf(
            ' ' to intArrayOf(0x00, 0x00, 0x00),
            '!' to intArrayOf(0x5F),
            '%' to intArrayOf(0x23, 0x13, 0x08, 0x64, 0x62),
            '+' to intArrayOf(0x08, 0x08, 0x3E, 0x08, 0x08),
            ',' to intArrayOf(0x50, 0x30),
            '-' to intArrayOf(0x08, 0x08, 0x08, 0x08, 0x08),
            '.' to intArrayOf(0x60, 0x60),
            '/' to intArrayOf(0x20, 0x10, 0x08, 0x04, 0x02),
            '0' to intArrayOf(0x3E, 0x51, 0x49, 0x45, 0x3E),
            '1' to intArrayOf(0x00, 0x42, 0x7F, 0x40, 0x00),
            '2' to intArrayOf(0x42, 0x61, 0x51, 0x49, 0x46),
            '3' to intArrayOf(0x21, 0x41, 0x45, 0x4B, 0x31),
            '4' to intArrayOf(0x18, 0x14, 0x12, 0x7F, 0x10),
            '5' to intArrayOf(0x27, 0x45, 0x45, 0x45, 0x39),
            '6' to intArrayOf(0x3C, 0x4A, 0x49, 0x49, 0x30),
            '7' to intArrayOf(0x01, 0x71, 0x09, 0x05, 0x03),
            '8' to intArrayOf(0x36, 0x49, 0x49, 0x49, 0x36),
            '9' to intArrayOf(0x06, 0x49, 0x49, 0x29, 0x1E),
            ':' to intArrayOf(0x36, 0x36),
            '?' to intArrayOf(0x02, 0x01, 0x51, 0x09, 0x06),
            'A' to intArrayOf(0x7E, 0x11, 0x11, 0x11, 0x7E),
            'B' to intArrayOf(0x7F, 0x49, 0x49, 0x49, 0x36),
            'C' to intArrayOf(0x3E, 0x41, 0x41, 0x41, 0x22),
            'D' to intArrayOf(0x7F, 0x41, 0x41, 0x22, 0x1C),
            'E' to intArrayOf(0x7F, 0x49, 0x49, 0x49, 0x41),
            'F' to intArrayOf(0x7F, 0x09, 0x09, 0x09, 0x01),
            'G' to intArrayOf(0x3E, 0x41, 0x49, 0x49, 0x7A),
            'H' to intArrayOf(0x7F, 0x08, 0x08, 0x08, 0x7F),
            'I' to intArrayOf(0x41, 0x7F, 0x41),
            'J' to intArrayOf(0x20, 0x40, 0x41, 0x3F, 0x01),
            'K' to intArrayOf(0x7F, 0x08, 0x14, 0x22, 0x41),
            'L' to intArrayOf(0x7F, 0x40, 0x40, 0x40, 0x40),
            'M' to intArrayOf(0x7F, 0x02, 0x0C, 0x02, 0x7F),
            'N' to intArrayOf(0x7F, 0x04, 0x08, 0x10, 0x7F),
            'O' to intArrayOf(0x3E, 0x41, 0x41, 0x41, 0x3E),
            'P' to intArrayOf(0x7F, 0x09, 0x09, 0x09, 0x06),
            'Q' to intArrayOf(0x3E, 0x41, 0x51, 0x21, 0x5E),
            'R' to intArrayOf(0x7F, 0x09, 0x19, 0x29, 0x46),
            'S' to intArrayOf(0x46, 0x49, 0x49, 0x49, 0x31),
            'T' to intArrayOf(0x01, 0x01, 0x7F, 0x01, 0x01),
            'U' to intArrayOf(0x3F, 0x40, 0x40, 0x40, 0x3F),
            'V' to intArrayOf(0x1F, 0x20, 0x40, 0x20, 0x1F),
            'W' to intArrayOf(0x3F, 0x40, 0x38, 0x40, 0x3F),
            'X' to intArrayOf(0x63, 0x14, 0x08, 0x14, 0x63),
            'Y' to intArrayOf(0x07, 0x08, 0x70, 0x08, 0x07),
            'Z' to intArrayOf(0x61, 0x51, 0x49, 0x45, 0x43),
            '°' to intArrayOf(0x02, 0x05, 0x02),
            '|' to intArrayOf(0x7F),
            '^' to intArrayOf(0x04, 0x02, 0x7F, 0x02, 0x04),
            'v' to intArrayOf(0x10, 0x20, 0x7F, 0x20, 0x10)
        )
    )

    // ---------------------------------------------------------------- NARROW

    /**
     * The 3x5, also carried over. Proportional: ':' and '.' take one column,
     * which is what keeps a date-and-weather line inside 96 pixels.
     */
    private val NARROW_3X5 = PixelFont(
        id = "narrow-5",
        height = 5,
        spacing = 1,
        monospaceDigits = true,
        glyphs = mapOf(
            ' ' to intArrayOf(0x00, 0x00, 0x00),
            '0' to intArrayOf(0x1F, 0x11, 0x1F),
            '1' to intArrayOf(0x02, 0x1F, 0x00),
            '2' to intArrayOf(0x1D, 0x15, 0x17),
            '3' to intArrayOf(0x15, 0x15, 0x1F),
            '4' to intArrayOf(0x07, 0x04, 0x1F),
            '5' to intArrayOf(0x17, 0x15, 0x1D),
            '6' to intArrayOf(0x1F, 0x15, 0x1D),
            '7' to intArrayOf(0x01, 0x01, 0x1F),
            '8' to intArrayOf(0x1F, 0x15, 0x1F),
            '9' to intArrayOf(0x17, 0x15, 0x1F),
            ':' to intArrayOf(0x0A),
            '.' to intArrayOf(0x10),
            ',' to intArrayOf(0x18),
            '-' to intArrayOf(0x04, 0x04, 0x04),
            '/' to intArrayOf(0x18, 0x04, 0x03),
            '%' to intArrayOf(0x19, 0x04, 0x13),
            '+' to intArrayOf(0x04, 0x0E, 0x04),
            '°' to intArrayOf(0x03, 0x03),
            '?' to intArrayOf(0x01, 0x15, 0x07),
            'A' to intArrayOf(0x1E, 0x05, 0x1E),
            'B' to intArrayOf(0x1F, 0x15, 0x0A),
            'C' to intArrayOf(0x1F, 0x11, 0x11),
            'D' to intArrayOf(0x1F, 0x11, 0x0E),
            'E' to intArrayOf(0x1F, 0x15, 0x11),
            'F' to intArrayOf(0x1F, 0x05, 0x01),
            'G' to intArrayOf(0x1F, 0x11, 0x1D),
            'H' to intArrayOf(0x1F, 0x04, 0x1F),
            'I' to intArrayOf(0x11, 0x1F, 0x11),
            'J' to intArrayOf(0x08, 0x10, 0x0F),
            'K' to intArrayOf(0x1F, 0x04, 0x1B),
            'L' to intArrayOf(0x1F, 0x10, 0x10),
            'M' to intArrayOf(0x1F, 0x03, 0x1F),
            'N' to intArrayOf(0x1F, 0x06, 0x1F),
            'O' to intArrayOf(0x1F, 0x11, 0x1F),
            'P' to intArrayOf(0x1F, 0x05, 0x07),
            'Q' to intArrayOf(0x0F, 0x19, 0x1F),
            'R' to intArrayOf(0x1F, 0x05, 0x1A),
            'S' to intArrayOf(0x17, 0x15, 0x1D),
            'T' to intArrayOf(0x01, 0x1F, 0x01),
            'U' to intArrayOf(0x1F, 0x10, 0x1F),
            'V' to intArrayOf(0x0F, 0x10, 0x0F),
            'W' to intArrayOf(0x1F, 0x08, 0x1F),
            'X' to intArrayOf(0x1B, 0x04, 0x1B),
            'Y' to intArrayOf(0x03, 0x1C, 0x03),
            'Z' to intArrayOf(0x19, 0x15, 0x13)
        )
    )

    // ------------------------------------------------------------- registry

    val SYSTEM = Family(
        id = "system",
        name = "SYSTEM",
        blurb = "The stock 5x7 matrix face. Legible on anything, always fits.",
        cuts = listOf(SYSTEM_5X7)
    )

    val NARROW = Family(
        id = "narrow",
        name = "NARROW",
        blurb = "A 3x5 condensed face for squeezing a whole data line onto 96 columns.",
        cuts = listOf(NARROW_3X5)
    )

    val TERMINAL = Family(
        id = "terminal",
        name = "TERMINAL",
        blurb = "DOS slab. Square corners, two-pixel strokes, readable across a room.",
        cuts = listOf(ArtFonts.TERMINAL_14, SYSTEM_5X7)
    )

    val SEGMENT = Family(
        id = "segment",
        name = "SEGMENT",
        blurb = "Seven-segment, chamfered ends and a gap at the waist. Alarm-clock LCD.",
        cuts = listOf(ArtFonts.SEGMENT_14, NARROW_3X5)
    )

    val BLOCK = Family(
        id = "block",
        name = "BLOCK",
        blurb = "Three-pixel strokes and no daylight. The loudest face here.",
        cuts = listOf(ArtFonts.BLOCK_14, SYSTEM_5X7)
    )

    val HAIRLINE = Family(
        id = "hairline",
        name = "HAIRLINE",
        blurb = "One-pixel strokes, wide stance. Barely any LEDs lit, which suits a dark room.",
        cuts = listOf(ArtFonts.HAIRLINE_14, NARROW_3X5)
    )

    val GALACTIC = Family(
        id = "galactic",
        name = "GALACTIC",
        blurb = "Wide, heavy and squared off with chamfered corners. The opening-title look.",
        cuts = listOf(ArtFonts.GALACTIC_14, SYSTEM_5X7)
    )

    val STARSHIP = Family(
        id = "starship",
        name = "STARSHIP",
        blurb = "Tall, condensed and heavy with hard angular corners. The title-card look.",
        cuts = listOf(ArtFonts.STARSHIP_14, SYSTEM_5X7)
    )

    val CLASSY = Family(
        id = "classy",
        name = "CLASSY",
        blurb = "Serifed slab numerals from an old machine's front panel.",
        cuts = listOf(ArtFonts.CLASSY_14, SYSTEM_5X7)
    )

    val MATRIX = Family(
        id = "matrix",
        name = "MATRIX",
        blurb = "Rounded and geometric, slashed zero, with 2 3 5 9 running backwards.",
        cuts = listOf(ArtFonts.MATRIX_14, NARROW_3X5)
    )

    val ARCADE = Family(
        id = "arcade",
        name = "ARCADE",
        blurb = "Eight-bit score-counter numerals. Doubles to 16 rows without softening.",
        cuts = listOf(ArtFonts.ARCADE_8, NARROW_3X5)
    )

    /**
     * Round dots on a visible pitch, derived from the 5x7 rather than drawn:
     * the shapes are already known to read, and deriving them means the dotted
     * eight can never disagree with the solid one.
     */
    val DOTMATRIX = Family(
        id = "dotmatrix",
        name = "DOTMATRIX",
        blurb = "Single dots on a coarse pitch, like a station departure board.",
        cuts = listOf(SYSTEM_5X7.dotted(2), SYSTEM_5X7)
    )

    /** Every family, in picker order. */
    val families: List<Family> = listOf(
        SYSTEM, NARROW, TERMINAL, SEGMENT, BLOCK, HAIRLINE,
        GALACTIC, STARSHIP, CLASSY, MATRIX, ARCADE, DOTMATRIX
    )

    fun family(id: String?): Family =
        families.firstOrNull { it.id == id } ?: SYSTEM

    /**
     * The cut to use for [id] on a panel with [maxHeight] rows available,
     * falling back through NARROW to nothing rather than overflowing the panel.
     */
    fun fit(id: String?, maxHeight: Int): PixelFont =
        family(id).fit(maxHeight)
            ?: NARROW.fit(maxHeight)
            ?: NARROW_3X5
}
