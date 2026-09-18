package com.example.ipixelclock.face

import com.example.ipixelclock.data.DataHub
import com.example.ipixelclock.data.WeatherService
import com.example.ipixelclock.font.PixelFont
import com.example.ipixelclock.font.PixelFonts
import com.example.ipixelclock.render.PixelCanvas
import com.example.ipixelclock.settings.Settings
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The page rotation: the panel shows the time, then cedes it briefly to the
 * date, the conditions and the sensor readings, then goes back.
 *
 * The schedule totals sixty seconds either way, which is the point — the cycle
 * stays locked to the minute instead of drifting across it:
 *
 * ```
 *   with telemetry     time 45s   date 5s   conditions 5s   telemetry 5s
 *   without            time 45s   date 5s   conditions 10s
 * ```
 *
 * Pages are computed from wall-clock time, not from a counter, so they land on
 * the same boundaries however many frames the panel managed in between — the
 * same discipline the digit effects and the backgrounds follow.
 *
 * All of this only happens when a readout is actually enabled and has data. A
 * clock with no network and no barometer shows the time and never rotates at
 * all, rather than cutting to a page that says "---".
 */
object InfoPages {

    enum class Page { TIME, DATE, CONDITIONS, TELEMETRY }

    /**
     * The rotation, resolved for what is actually available right now.
     *
     * The seconds come from the settings. A page that cannot be shown does not
     * shorten the cycle — it hands its slot to the conditions page if there is
     * one, and to the time otherwise. That is what keeps the total constant, so
     * a rotation tuned to sixty seconds stays locked to the minute whether or
     * not the network answered, instead of sliding every time the weather
     * lookup fails.
     */
    class Schedule(
        settings: Settings,
        val showDate: Boolean,
        val hasConditions: Boolean,
        val hasTelemetry: Boolean
    ) {
        private val timeMs = settings.infoTimeSeconds * 1000L
        private val dateMs = settings.infoDateSeconds * 1000L
        private val condMs = settings.infoConditionsSeconds * 1000L
        private val telemMs = settings.infoTelemetrySeconds * 1000L

        /** Seconds belonging to pages that have nothing to show. */
        private val orphan =
            (if (showDate) 0L else dateMs) +
                (if (hasConditions) 0L else condMs) +
                (if (hasTelemetry) 0L else telemMs)

        val conditionsSpan = if (hasConditions) condMs + orphan else 0L
        val timeSpan = timeMs + (if (hasConditions) 0L else orphan)
        val dateSpan = if (showDate) dateMs else 0L
        val telemetrySpan = if (hasTelemetry) telemMs else 0L

        /** Always the sum of all four settings, whatever is missing. */
        val cycleMs = timeMs + dateMs + condMs + telemMs

        val rotates get() = showDate || hasConditions || hasTelemetry

        fun spanOf(page: Page): Long = when (page) {
            Page.TIME -> timeSpan
            Page.DATE -> dateSpan
            Page.CONDITIONS -> conditionsSpan
            Page.TELEMETRY -> telemetrySpan
        }
    }

    /** Which page is due now. */
    fun pageAt(nowMs: Long, s: Schedule): Page {
        if (!s.rotates || s.cycleMs <= 0L) return Page.TIME

        val phase = nowMs % s.cycleMs
        var cursor = s.timeSpan
        if (phase < cursor) return Page.TIME

        if (s.showDate) {
            if (phase < cursor + s.dateSpan) return Page.DATE
            cursor += s.dateSpan
        }
        if (s.hasConditions) {
            if (phase < cursor + s.conditionsSpan) return Page.CONDITIONS
            cursor += s.conditionsSpan
        }
        if (s.hasTelemetry) {
            if (phase < cursor + s.telemetrySpan) return Page.TELEMETRY
        }
        return Page.TIME
    }

    /**
     * Eases a page in over its first moments so it does not snap into place.
     *
     * Takes milliseconds *since this page began*, which the caller tracks. An
     * earlier version took the absolute clock and did `nowMs % 1000`, which is
     * the phase within every second rather than within the page — so the date
     * and conditions dipped to black for 120 ms once a second. It read as a
     * flicker, which is exactly what a fade is supposed to avoid.
     */
    fun fadeIn(elapsedInPageMs: Long): Float {
        if (elapsedInPageMs >= FADE_MS) return 1f
        // Floored rather than starting at zero. A page change is always
        // detected on the frame that renders it, so elapsed is exactly 0 on
        // that frame — ramping from zero guaranteed one fully black frame at
        // every transition, four times a minute, which is the blink the fade
        // exists to prevent.
        val t = (elapsedInPageMs.coerceAtLeast(0L) / FADE_MS.toFloat())
        return FADE_FLOOR + (1f - FADE_FLOOR) * t
    }

    private const val FADE_MS = 180L

    /** Never fully dark: the first frame of a page starts here, not at zero. */
    private const val FADE_FLOOR = 0.35f

    // ----------------------------------------------------------- the drawing

    /**
     * Draws one of the non-time pages, centred, in the largest of the two
     * lettered faces that fits.
     *
     * The art fonts carry digits and punctuation only — a date needs letters
     * for the weekday and the conditions need a degree sign, so these pages use
     * SYSTEM and NARROW, which have them.
     */
    fun draw(
        canvas: PixelCanvas,
        page: Page,
        settings: Settings,
        data: DataHub,
        nowMs: Long,
        coverage: Float,
        elapsedInPageMs: Long,
        pageDurationMs: Long
    ) {
        val icon = if (page == Page.CONDITIONS) {
            data.weather?.let { WeatherService.icon(it.code) }
        } else null

        val line = when (page) {
            Page.DATE -> dateLine(settings, nowMs)
            Page.CONDITIONS -> conditionsLine(settings, data)
            Page.TELEMETRY -> telemetryLine(data)
            Page.TIME -> return
        }
        if (line.isEmpty()) return

        drawBigLine(canvas, settings, coverage, line, icon, elapsedInPageMs, pageDurationMs)
    }


    // ------------------------------------------------------------- the pages

    private fun dateLine(settings: Settings, nowMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val m = cal.get(Calendar.MONTH) + 1
        val dow = WEEKDAYS[(cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]

        val date = when (settings.dateFormat) {
            "MDY" -> String.format(Locale.US, "%02d.%02d", m, d)
            "YMD" -> String.format(Locale.US, "%02d-%02d", m, d)
            "WEEKDAY" -> return dow
            else -> String.format(Locale.US, "%02d.%02d", d, m)
        }
        return "$dow $date"
    }

    private fun telemetryLine(data: DataHub): String {
        val parts = ArrayList<String>(2)
        data.pressureHpa?.let { parts.add("${it.roundToInt()} HPA") }
        data.humidityPercent?.let { parts.add("$it% RH") }
        return parts.joinToString("  ")
    }

    private fun conditionsLine(settings: Settings, data: DataHub): String {
        val parts = ArrayList<String>(3)
        data.weather?.let { w ->
            val t = if (settings.metricUnits) w.temperatureC else w.temperatureC * 9 / 5 + 32
            parts.add("${t.roundToInt()}°" + if (settings.metricUnits) "C" else "F")
        }
        if (settings.showSunrise) data.sunriseMs?.let { parts.add("^" + hhmm(it)) }
        if (settings.showSunset) data.sunsetMs?.let { parts.add("v" + hhmm(it)) }
        return parts.joinToString("  ")
    }

    // ------------------------------------------------------------ the layout

    /**
     * One line, as large as the panel's height allows, scrolling when it is too
     * long to fit across.
     *
     * The face is SYSTEM scaled by a whole multiple rather than one of the art
     * families, because those carry digits and punctuation only — a weekday
     * needs letters, and mixing two faces inside one line looks like a mistake.
     * Whole multiples only: a fractionally scaled 7-row font has broken stems.
     */
    private fun drawBigLine(
        canvas: PixelCanvas,
        settings: Settings,
        coverage: Float,
        line: String,
        icon: WeatherService.Icon?,
        elapsedInPageMs: Long,
        pageDurationMs: Long
    ) {
        val font = bigFont(canvas.height)
        val iconScale = (canvas.height / ICON_H).coerceIn(1, 3)
        val iconW = if (icon != null) ICON_W * iconScale + font.spacing * 2 else 0

        val textW = font.measure(line)
        val total = textW + iconW
        val y = ((canvas.height - font.height) / 2).coerceAtLeast(0)

        val x0 = if (total <= canvas.width) {
            // Fits: just centre it.
            (canvas.width - total) / 2
        } else {
            // Does not fit: pace the travel so the whole line has been past the
            // edge by the time the page hands the panel back. A fixed speed
            // would either crawl and cut off the end, or race and be
            // unreadable — the page duration is what has to be satisfied.
            val overflow = total - canvas.width
            val hold = HOLD_MS.coerceAtMost(pageDurationMs / 4)
            val travel = (pageDurationMs - hold * 2).coerceAtLeast(400L)
            val t = ((elapsedInPageMs - hold).coerceIn(0L, travel)).toFloat() / travel
            -(overflow * t).toInt()
        }

        if (icon != null) {
            drawIcon(
                canvas, icon, x0,
                ((canvas.height - ICON_H * iconScale) / 2).coerceAtLeast(0),
                coverage, iconScale
            )
        }
        font.draw(canvas, line, x0 + iconW, y, settings.colorPrimary, coverage)
    }

    /** SYSTEM at the largest whole multiple that fits the panel's height. */
    private fun bigFont(height: Int): PixelFont {
        val base = PixelFonts.SYSTEM.shortest
        val factor = (height / base.height).coerceAtLeast(1)
        return if (factor >= 2) base.scaled(factor) else base
    }

    private const val HOLD_MS = 600L

    private fun hhmm(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    // ------------------------------------------------------------- the icons
    // 7x7, drawn as column bit-masks like the fonts. Deliberately few: at this
    // size the difference between drizzle and light rain is not drawable, so
    // the WMO codes collapse to eight shapes that are.

    private const val ICON_W = 7
    private const val ICON_H = 7

    private val ICONS: Map<WeatherService.Icon, IntArray> = mapOf(
        // A sun: centre disc with four spokes.
        WeatherService.Icon.CLEAR to intArrayOf(0x08, 0x2A, 0x1C, 0x77, 0x1C, 0x2A, 0x08),
        // Sun behind a cloud.
        WeatherService.Icon.PARTLY to intArrayOf(0x0A, 0x1C, 0x38, 0x7C, 0x7C, 0x7C, 0x38),
        WeatherService.Icon.CLOUD to intArrayOf(0x38, 0x7C, 0x7C, 0x7E, 0x7C, 0x7C, 0x38),
        // Fog: stacked bars.
        WeatherService.Icon.FOG to intArrayOf(0x2A, 0x2A, 0x2A, 0x2A, 0x2A, 0x2A, 0x2A),
        // Cloud with a light dotted fall.
        WeatherService.Icon.DRIZZLE to intArrayOf(0x1C, 0x3E, 0x3E, 0x7F, 0x3E, 0x5E, 0x1C),
        // Cloud with streaks.
        WeatherService.Icon.RAIN to intArrayOf(0x1C, 0x7E, 0x5E, 0x7F, 0x5E, 0x7E, 0x1C),
        // Cloud with flakes.
        WeatherService.Icon.SNOW to intArrayOf(0x1C, 0x7E, 0x6A, 0x7F, 0x6A, 0x7E, 0x1C),
        // Cloud with a bolt.
        WeatherService.Icon.STORM to intArrayOf(0x1C, 0x3E, 0x7E, 0x7F, 0x6C, 0x3E, 0x1C),
        WeatherService.Icon.UNKNOWN to intArrayOf(0x00, 0x02, 0x01, 0x51, 0x09, 0x06, 0x00)
    )

    private fun drawIcon(
        canvas: PixelCanvas,
        icon: WeatherService.Icon,
        x0: Int,
        y0: Int,
        coverage: Float,
        scale: Int = 1
    ) {
        val g = ICONS[icon] ?: return
        val color = when (icon) {
            WeatherService.Icon.CLEAR, WeatherService.Icon.PARTLY -> 0xFFFFC020.toInt()
            WeatherService.Icon.SNOW -> 0xFFDFF2FF.toInt()
            WeatherService.Icon.STORM -> 0xFFFFE040.toInt()
            else -> 0xFF90B8D8.toInt()
        }
        for (col in g.indices) {
            for (row in 0 until ICON_H) {
                if (g[col] and (1 shl row) == 0) continue
                // Scaled to match the line's font, so the icon does not sit as
                // a speck beside 14-row text.
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        canvas.blendA(
                            x0 + col * scale + dx,
                            y0 + row * scale + dy,
                            color, coverage
                        )
                    }
                }
            }
        }
    }

    private val WEEKDAYS = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
}
