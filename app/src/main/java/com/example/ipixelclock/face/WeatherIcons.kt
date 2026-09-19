package com.example.ipixelclock.face

import com.example.ipixelclock.data.WeatherService
import com.example.ipixelclock.render.PixelCanvas

/**
 * The weather icons, drawn as coloured pixel art.
 *
 * The first set was 7x7 single-colour column masks, which at that size cannot
 * tell a cloud from a blob — every icon read as the same grey lump. These are
 * 12x12 with a palette, so a sun is yellow with orange rays, a raincloud has a
 * white top and a dark underside with blue streaks below it, and a storm has a
 * bolt you can actually see.
 *
 * Authored as art with a one-character colour key, for the same reason the
 * fonts are: `0x3F1E` hides a mistake, a picture does not.
 *
 * ```
 *   .  nothing      y  sun          o  sun edge
 *   w  cloud top    g  cloud body   d  cloud base
 *   b  rain         c  drizzle      s  snow
 *   l  lightning    f  fog          q  unknown
 * ```
 */
object WeatherIcons {

    const val SIZE = 12

    private val PALETTE = mapOf(
        'y' to 0xFFFFD21E.toInt(),   // sun core
        'o' to 0xFFFF8A00.toInt(),   // sun rim and rays
        'w' to 0xFFEAF3FF.toInt(),   // sunlit cloud top
        'g' to 0xFF93A8C0.toInt(),   // cloud body
        'd' to 0xFF54687E.toInt(),   // cloud underside
        'b' to 0xFF35A0F0.toInt(),   // rain
        'c' to 0xFF9AD6FF.toInt(),   // drizzle
        's' to 0xFFFFFFFF.toInt(),   // snow
        'l' to 0xFFFFE24A.toInt(),   // lightning
        'f' to 0xFFB6C6D8.toInt(),   // fog
        'q' to 0xFF8FB6D6.toInt()    // unknown
    )

    private val ART: Map<WeatherService.Icon, List<String>> = mapOf(

        WeatherService.Icon.CLEAR to listOf(
            ".....yy.....",
            "..o..oo..o..",
            "...o.oo.o...",
            "....oyyo....",
            ".o.oyyyyo.o.",
            "oo.yyyyyy.oo",
            "oo.yyyyyy.oo",
            ".o.oyyyyo.o.",
            "....oyyo....",
            "...o.oo.o...",
            "..o..oo..o..",
            ".....yy....."
        ),

        WeatherService.Icon.PARTLY to listOf(
            ".........yy.",
            "..o......oo.",
            "...o...oyyyo",
            "......oyyyyy",
            "....wwwoyyyo",
            "..wwwwwwwoo.",
            ".wwwwwwwwww.",
            "wwwwwwwwwwww",
            "wggggggggggw",
            ".gggggggggg.",
            "..dddddddd..",
            "............"
        ),

        WeatherService.Icon.CLOUD to listOf(
            "............",
            "............",
            ".....wwww...",
            "...wwwwwwww.",
            "..wwwwwwwwww",
            ".wwwwwwwwwww",
            "wwwwwwwwwwww",
            "wggggggggggw",
            ".gggggggggg.",
            "..dddddddd..",
            "............",
            "............"
        ),

        WeatherService.Icon.FOG to listOf(
            "............",
            "....wwww....",
            "..wwwwwwww..",
            ".wwwwwwwwww.",
            "wwwwwwwwwwww",
            "wggggggggggw",
            "..dddddddd..",
            "............",
            ".ffffffffff.",
            "............",
            "..ffffffff..",
            "............"
        ),

        WeatherService.Icon.DRIZZLE to listOf(
            "............",
            "....wwww....",
            "..wwwwwwww..",
            ".wwwwwwwwww.",
            "wwwwwwwwwwww",
            "wggggggggggw",
            "..dddddddd..",
            "............",
            "..c...c...c.",
            "............",
            "....c...c...",
            "............"
        ),

        WeatherService.Icon.RAIN to listOf(
            "............",
            "....wwww....",
            "..wwwwwwww..",
            ".wwwwwwwwww.",
            "wwwwwwwwwwww",
            "wggggggggggw",
            "..dddddddd..",
            "...b..b..b..",
            "..b..b..b...",
            "...b..b..b..",
            "..b..b..b...",
            "............"
        ),

        WeatherService.Icon.SNOW to listOf(
            "............",
            "....wwww....",
            "..wwwwwwww..",
            ".wwwwwwwwww.",
            "wwwwwwwwwwww",
            "wggggggggggw",
            "..dddddddd..",
            "............",
            ".s.s..s..s.s",
            "..s....s....",
            ".s.s..s..s.s",
            "............"
        ),

        WeatherService.Icon.STORM to listOf(
            "............",
            "....wwww....",
            "..wwwwwwww..",
            ".wwwwwwwwww.",
            "wwwwwwwwwwww",
            "wddddddddddw",
            "..dddddddd..",
            "......ll....",
            ".....ll.....",
            "...llllll...",
            "......ll....",
            ".....ll....."
        ),

        WeatherService.Icon.UNKNOWN to listOf(
            "............",
            "....qqqq....",
            "...qq..qq...",
            "...qq..qq...",
            "........qq..",
            ".......qq...",
            "......qq....",
            ".....qq.....",
            ".....qq.....",
            "............",
            ".....qq.....",
            ".....qq....."
        )
    )

    /** Parsed once into a flat ARGB grid per icon; 0 means leave the pixel be. */
    private val PIXELS: Map<WeatherService.Icon, IntArray> = ART.mapValues { (icon, rows) ->
        val out = IntArray(SIZE * SIZE)
        for (y in rows.indices) {
            val row = rows[y]
            require(row.length == SIZE) { "icon $icon row $y is ${row.length} wide, not $SIZE" }
            for (x in row.indices) {
                PALETTE[row[x]]?.let { out[y * SIZE + x] = it }
            }
        }
        out
    }

    /** The largest whole multiple of [SIZE] that fits, or 0 when nothing does. */
    fun scaleFor(height: Int): Int = (height / SIZE).coerceAtMost(3)

    /**
     * Draws [icon] with its top-left at ([x0], [y0]).
     *
     * [coverage] carries the page fade, so the icon dims with the text rather
     * than popping in at full brightness ahead of it.
     */
    fun draw(
        canvas: PixelCanvas,
        icon: WeatherService.Icon,
        x0: Int,
        y0: Int,
        scale: Int,
        coverage: Float
    ) {
        val px = PIXELS[icon] ?: return
        val s = scale.coerceAtLeast(1)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val c = px[y * SIZE + x]
                if (c == 0) continue
                for (dy in 0 until s) {
                    for (dx in 0 until s) {
                        canvas.blendA(x0 + x * s + dx, y0 + y * s + dy, c, coverage)
                    }
                }
            }
        }
    }
}
