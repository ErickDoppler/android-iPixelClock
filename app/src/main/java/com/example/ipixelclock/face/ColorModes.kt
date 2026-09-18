package com.example.ipixelclock.face

import com.example.ipixelclock.render.PixelCanvas
import com.example.ipixelclock.settings.Settings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * How the clock face is coloured.
 *
 * A mode is just a function from a canvas pixel to a colour, which the font's
 * draw loop consults per lit pixel. That keeps gradients honest — the gradient
 * runs across the *glyphs* in panel space, so it looks continuous across the
 * whole time rather than restarting on every digit — and it costs nothing,
 * because a 96x16 face has a few hundred lit pixels at most.
 *
 * Returning null from [painter] means "flat colour", and the font takes its
 * faster path.
 */
object ColorModes {

    /** The ids the web UI offers, with their labels. */
    val MODES: List<Pair<String, String>> = listOf(
        "solid" to "Solid",
        "gradient" to "Gradient",
        "gradient-animated" to "Gradient (moving)",
        "rainbow" to "Rainbow cycle",
        "per-digit" to "Per digit"
    )

    /**
     * Builds the per-pixel colourer for the current mode, given where the face
     * was laid out ([x], [y], [w], [h] in canvas coordinates).
     */
    fun painter(
        settings: Settings,
        canvas: PixelCanvas,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        nowMs: Long
    ): ((Int, Int) -> Int)? {

        val a = settings.colorPrimary
        val b = settings.colorSecondary
        val span = (if (w > 0) w else canvas.width).toFloat()

        return when (settings.colorMode) {

            "gradient" -> {
                val rad = Math.toRadians(settings.gradientAngle.toDouble())
                val dx = cos(rad).toFloat()
                val dy = sin(rad).toFloat()
                // Project onto the gradient axis and normalise over the face's
                // own extent, so the full colour range is always used.
                val extent = (abs(dx) * w + abs(dy) * h).coerceAtLeast(1f)
                ({ px: Int, py: Int ->
                    val t = ((px - x) * dx + (py - y) * dy) / extent
                    PixelCanvas.lerp(a, b, t.coerceIn(0f, 1f))
                })
            }

            "gradient-animated" -> {
                // The gradient scrolls through the glyphs; one full cycle per
                // 360 degrees of colorSpeed.
                val phase = cyclePhase(nowMs, settings.colorSpeed / 60.0, 1.0)
                ({ px: Int, _: Int ->
                    val t = (((px - x) / span + phase) % 1f + 1f) % 1f
                    // Ping-pong so the two ends meet without a seam.
                    PixelCanvas.lerp(a, b, if (t < 0.5f) t * 2f else (1f - t) * 2f)
                })
            }

            "rainbow" -> {
                val phase = cyclePhase(nowMs, settings.colorSpeed.toDouble(), 360.0)
                ({ px: Int, _: Int ->
                    PixelCanvas.hsv(phase + (px - x) * 360f / span, 1f, 1f)
                })
            }

            "per-digit" -> {
                // Alternates the two colours glyph by glyph. Width per cell is
                // approximated from the face extent, which is exact for the
                // monospaced digits every font here uses.
                val cell = (span / 5f).coerceAtLeast(1f)
                ({ px: Int, _: Int ->
                    if ((((px - x) / cell).toInt()) % 2 == 0) a else b
                })
            }

            else -> null // solid: the font uses colorPrimary directly
        }
    }

    /**
     * A time-driven phase, wrapped into `0..period`, computed in double and
     * reduced *before* it reaches a float.
     *
     * The obvious `(nowMs / 1000f) * speed` is broken and not obviously so: a
     * Float carries 24 bits of mantissa, and epoch milliseconds are around
     * 1.77e9, where consecutive representable values are ~128 apart. The phase
     * then only moves every couple of minutes, so an "animated" gradient sits
     * perfectly still — and with frame dedupe on, the panel stops being sent
     * anything at all. Reducing modulo the period first keeps the number small
     * enough for a float to resolve every millisecond of it.
     */
    private fun cyclePhase(nowMs: Long, perSecond: Double, period: Double): Float {
        if (perSecond == 0.0 || period <= 0.0) return 0f
        val seconds = (nowMs % 86_400_000L) / 1000.0
        val phase = (seconds * perSecond) % period
        return (if (phase < 0) phase + period else phase).toFloat()
    }

    /**
     * The brightness factor for the software half of the dimming. Hardware
     * brightness does the heavy lifting; this smooths the bottom of the range,
     * where panels tend to quantise hard.
     */
    fun softwareBrightness(pct: Int): Float {
        val p = pct.coerceIn(0, 100) / 100f
        // Only start scaling in software below the point where the panel's own
        // levels get coarse, so a normal setting stays pixel-exact.
        return if (p >= 0.35f) 1f else (p / 0.35f).coerceIn(0.06f, 1f)
    }
}
