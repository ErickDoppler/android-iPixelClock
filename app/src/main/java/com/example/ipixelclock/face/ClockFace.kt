package com.example.ipixelclock.face

import com.example.ipixelclock.font.PixelFont
import com.example.ipixelclock.font.PixelFonts
import com.example.ipixelclock.render.PixelCanvas
import com.example.ipixelclock.settings.Settings
import java.util.Calendar
import java.util.Locale

/**
 * Lays out and draws the time.
 *
 * Layout is driven entirely by the *logical* canvas the renderer hands over, so
 * it never has to know how the panel is mounted: a 96x16 panel hung vertically
 * arrives here as a 16x96 canvas and falls into the tall layout by itself.
 *
 * Phase 1 draws a flat, single-colour face with no transition. Colour modes live
 * in [ColorModes] and the per-digit effects in `fx/` (phase 2); the seams for
 * both are already here — [colorAt] and the per-glyph draw loop.
 */
class ClockFace {

    /** What the face decided to draw, so the info row can avoid colliding. */
    var lastBounds: IntArray = intArrayOf(0, 0, 0, 0)
        private set

    /**
     * Draws the clock onto [canvas].
     *
     * [coverage] is the face's overall opacity, which the visibility policy uses
     * to fade it out rather than snapping it off.
     */
    fun draw(
        canvas: PixelCanvas,
        settings: Settings,
        nowMs: Long,
        coverage: Float = 1f,
        reservedBottom: Int = 0
    ) {
        if (coverage <= 0f) return

        val avail = (canvas.height - reservedBottom).coerceAtLeast(1)
        val text = formatTime(settings, nowMs)

        // One line if it fits. It will not on a vertically mounted panel — a
        // 96x16 hung on its end arrives here as a 16-wide canvas, where even the
        // 3-column narrow font needs 19 columns for HH:MM — so there the time
        // stacks instead of running off the edge.
        val single = pickFont(settings, text, canvas.width, avail)
        if (single != null) {
            val w = single.measure(text)
            val x = ((canvas.width - w) / 2).coerceAtLeast(0)
            val y = ((avail - single.height) / 2).coerceAtLeast(0)
            lastBounds = intArrayOf(x, y, w, single.height)
            val colorer = ColorModes.painter(settings, canvas, x, y, w, single.height, nowMs)
            single.draw(canvas, text, x, y, settings.colorPrimary, coverage, colorer)
            return
        }
        drawStacked(canvas, settings, nowMs, coverage, avail)
    }

    /**
     * The tall layout: HH over MM (over SS), each centred on its own row.
     *
     * This is what a vertically mounted panel gets, and it is why orientation
     * swaps the canvas axes rather than just rotating the finished frame — the
     * layout has to be able to see that it is tall before it can respond to it.
     */
    private fun drawStacked(
        canvas: PixelCanvas,
        settings: Settings,
        nowMs: Long,
        coverage: Float,
        avail: Int
    ) {
        val lines = timeParts(settings, nowMs)
        val gap = 2
        val family = PixelFonts.family(settings.fontFamily)

        // Widest cut whose widest line fits across, and whose stack fits down.
        var chosen: PixelFont? = null
        for (cut in family.cuts + PixelFonts.NARROW.cuts) {
            val candidates = ArrayList<PixelFont>(2)
            val factor = minOf(
                canvas.width / maxOf(1, cut.measure(lines[0])),
                (avail - gap * (lines.size - 1)) / maxOf(1, cut.height * lines.size)
            )
            if (factor >= 2) candidates.add(cut.scaled(factor))
            candidates.add(cut)
            for (f in candidates) {
                val widest = lines.maxOf { f.measure(it) }
                val tall = f.height * lines.size + gap * (lines.size - 1)
                if (widest <= canvas.width && tall <= avail) {
                    chosen = f
                    break
                }
            }
            if (chosen != null) break
        }
        val font = chosen ?: PixelFonts.NARROW.shortest

        val totalH = font.height * lines.size + gap * (lines.size - 1)
        var y = ((avail - totalH) / 2).coerceAtLeast(0)
        val widest = lines.maxOf { font.measure(it) }
        lastBounds = intArrayOf((canvas.width - widest) / 2, y, widest, totalH)

        val colorer = ColorModes.painter(
            settings, canvas, (canvas.width - widest) / 2, y, widest, totalH, nowMs
        )
        for (line in lines) {
            val x = ((canvas.width - font.measure(line)) / 2).coerceAtLeast(0)
            font.draw(canvas, line, x, y, settings.colorPrimary, coverage, colorer)
            y += font.height + gap
        }
    }

    /** The time split into the rows the stacked layout draws. */
    private fun timeParts(settings: Settings, nowMs: Long): List<String> {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = if (settings.hour24) cal.get(Calendar.HOUR_OF_DAY)
        else cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val out = arrayListOf(
            String.format(Locale.US, "%02d", hour),
            String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))
        )
        if (settings.showSeconds) {
            out.add(String.format(Locale.US, "%02d", cal.get(Calendar.SECOND)))
        }
        return out
    }

    /**
     * The largest cut of the chosen family that fits both the height available
     * and the panel's width — a 144-column panel can carry a 14-row face with
     * seconds, a 32-column one cannot, and dropping to a shorter cut is far
     * better than running the digits off the edge.
     */
    private fun pickFont(
        settings: Settings,
        text: String,
        maxWidth: Int,
        maxHeight: Int
    ): PixelFont? {
        val family = PixelFonts.family(settings.fontFamily)
        // The chosen family first, then NARROW as the fallback: at 3 columns a
        // digit it fits where nothing else will.
        for (cut in family.cuts + PixelFonts.NARROW.cuts) {
            if (cut.height > maxHeight) continue
            val factor = minOf(maxHeight / cut.height, maxWidth / maxOf(1, cut.measure(text)))
            if (factor >= 2) {
                val scaled = cut.scaled(factor)
                if (scaled.measure(text) <= maxWidth && scaled.height <= maxHeight) return scaled
            }
            if (cut.measure(text) <= maxWidth) return cut
        }
        // Null means "no single line fits"; the caller stacks instead.
        return null
    }

    /**
     * The time string. Seconds are dropped silently when the panel is too narrow
     * to carry them — better a correct HH:MM than a clipped HH:MM:SS.
     */
    fun formatTime(settings: Settings, nowMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs

        var hour = if (settings.hour24) {
            cal.get(Calendar.HOUR_OF_DAY)
        } else {
            cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        }
        if (hour < 0) hour = 0

        val hh = if (settings.leadingZero || hour >= 10) {
            String.format(Locale.US, "%02d", hour)
        } else {
            hour.toString()
        }
        val mm = String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))

        // A blinking colon must not change the string's width, or every other
        // frame would re-centre the whole face by half a pixel and shimmer.
        val sep = if (settings.blinkColon && (nowMs / 500L) % 2L == 1L) ' ' else ':'

        return if (settings.showSeconds) {
            val ss = String.format(Locale.US, "%02d", cal.get(Calendar.SECOND))
            "$hh$sep$mm$sep$ss"
        } else {
            "$hh$sep$mm"
        }
    }

    /**
     * Whether the face should be visible now, as a 0..1 coverage so the caller
     * can cross-fade. [sunriseMs] and [sunsetMs] may be null when there is no
     * location to compute them from, in which case the sun-driven policies fall
     * back to always-on rather than leaving a blank panel.
     */
    fun visibility(
        settings: Settings,
        nowMs: Long,
        sunriseMs: Long?,
        sunsetMs: Long?
    ): Float = when (settings.visibility) {

        "duty" -> {
            val show = settings.dutyShowSeconds.coerceAtLeast(1) * 1000L
            val hide = settings.hideSeconds.coerceAtLeast(0) * 1000L
            if (hide == 0L) 1f else {
                val phase = (nowMs % (show + hide))
                if (phase < show) fadeEdges(phase, show) else 0f
            }
        }

        "day" -> if (sunriseMs == null || sunsetMs == null) 1f
        else if (nowMs in sunriseMs..sunsetMs) 1f else 0f

        "night" -> if (sunriseMs == null || sunsetMs == null) 1f
        else if (nowMs in sunriseMs..sunsetMs) 0f else 1f

        "schedule" -> {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val from = settings.scheduleFrom
            val to = settings.scheduleTo
            // A window that wraps past midnight is the normal case for a
            // bedside clock, so handle from > to rather than rejecting it.
            val inside = if (from <= to) mins in from..to else mins >= from || mins <= to
            if (inside) 1f else 0f
        }

        else -> 1f
    }

    /** Eases the duty-cycle face in and out over its last/first half second. */
    private fun fadeEdges(phase: Long, span: Long): Float {
        val edge = 500L
        if (span <= edge * 2) return 1f
        return when {
            phase < edge -> phase / edge.toFloat()
            phase > span - edge -> (span - phase) / edge.toFloat()
            else -> 1f
        }
    }
}
