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

    private const val CYCLE_MS = 60_000L
    private const val TIME_MS = 45_000L
    private const val DATE_MS = 5_000L
    private const val SHORT_MS = 5_000L
    private const val LONG_MS = 10_000L

    /**
     * Which page is due now.
     *
     * [hasConditions] and [hasTelemetry] collapse the schedule when a source is
     * missing: with no telemetry the conditions page absorbs its five seconds,
     * and with neither the time simply holds the panel.
     */
    fun pageAt(
        nowMs: Long,
        showDate: Boolean,
        hasConditions: Boolean,
        hasTelemetry: Boolean
    ): Page {
        if (!showDate && !hasConditions && !hasTelemetry) return Page.TIME

        val phase = nowMs % CYCLE_MS
        var cursor = TIME_MS
        // Time always holds the first 45 s. Anything not shown hands its slot
        // back to the time rather than shortening the cycle, so the rotation
        // stays minute-aligned.
        if (phase < cursor) return Page.TIME

        if (showDate) {
            if (phase < cursor + DATE_MS) return Page.DATE
            cursor += DATE_MS
        }
        if (hasConditions) {
            val span = if (hasTelemetry) SHORT_MS else LONG_MS
            if (phase < cursor + span) return Page.CONDITIONS
            cursor += span
        }
        if (hasTelemetry) {
            if (phase < cursor + SHORT_MS) return Page.TELEMETRY
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
        coverage: Float
    ) {
        when (page) {
            Page.DATE -> drawLines(canvas, settings, coverage, dateLines(settings, nowMs))
            Page.CONDITIONS -> drawConditions(canvas, settings, data, coverage)
            Page.TELEMETRY -> drawLines(canvas, settings, coverage, telemetryLines(data))
            Page.TIME -> {}
        }
    }

    // ------------------------------------------------------------- the pages

    private fun dateLines(settings: Settings, nowMs: Long): List<String> {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val m = cal.get(Calendar.MONTH) + 1
        val y = cal.get(Calendar.YEAR)
        val dow = WEEKDAYS[(cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]

        val date = when (settings.dateFormat) {
            "MDY" -> String.format(Locale.US, "%02d.%02d", m, d)
            "YMD" -> String.format(Locale.US, "%02d-%02d", m, d)
            "WEEKDAY" -> dow
            else -> String.format(Locale.US, "%02d.%02d", d, m)
        }
        // Two lines where there is room: the weekday reads at a glance and the
        // numbers are what you actually wanted.
        return if (settings.dateFormat == "WEEKDAY") {
            listOf(dow, String.format(Locale.US, "%02d.%02d", d, m))
        } else {
            listOf(dow, date)
        }.also { if (y < 1980) return listOf(date) }
    }

    private fun telemetryLines(data: DataHub): List<String> {
        val out = ArrayList<String>(2)
        data.pressureHpa?.let { out.add("${it.roundToInt()} HPA") }
        data.humidityPercent?.let { out.add("$it% RH") }
        return out
    }

    private fun drawConditions(
        canvas: PixelCanvas,
        settings: Settings,
        data: DataHub,
        coverage: Float
    ) {
        val w = data.weather
        val lines = ArrayList<String>(2)
        if (w != null) {
            val t = if (settings.metricUnits) w.temperatureC else w.temperatureC * 9 / 5 + 32
            lines.add("${t.roundToInt()}°" + if (settings.metricUnits) "C" else "F")
        }
        if (settings.showSunrise || settings.showSunset) {
            val parts = ArrayList<String>(2)
            if (settings.showSunrise) data.sunriseMs?.let { parts.add("^" + hhmm(it)) }
            if (settings.showSunset) data.sunsetMs?.let { parts.add("v" + hhmm(it)) }
            if (parts.isNotEmpty()) lines.add(parts.joinToString(" "))
        }
        if (lines.isEmpty()) return

        // The icon sits to the left of the text when there is room for it.
        val icon = w?.let { WeatherService.icon(it.code) }
        drawLines(canvas, settings, coverage, lines, icon)
    }

    // ------------------------------------------------------------ the layout

    /**
     * Centres one or two lines, with an optional icon to their left. Picks the
     * larger face when both lines fit at 7 rows, otherwise the 3x5.
     */
    private fun drawLines(
        canvas: PixelCanvas,
        settings: Settings,
        coverage: Float,
        lines: List<String>,
        icon: WeatherService.Icon? = null
    ) {
        if (lines.isEmpty()) return
        val big = PixelFonts.SYSTEM.shortest
        val small = PixelFonts.NARROW.shortest
        val iconW = if (icon != null) ICON_W + 2 else 0

        val gap = 2
        val font: PixelFont = when {
            lines.size == 1 && big.measure(lines[0]) + iconW <= canvas.width &&
                big.height <= canvas.height -> big

            lines.size >= 2 &&
                lines.all { big.measure(it) + iconW <= canvas.width } &&
                big.height * 2 + gap <= canvas.height -> big

            else -> small
        }

        val block = font.height * lines.size + gap * (lines.size - 1)
        var y = ((canvas.height - block) / 2).coerceAtLeast(0)
        val widest = lines.maxOf { font.measure(it) }
        val total = widest + iconW
        val left = ((canvas.width - total) / 2).coerceAtLeast(0)

        if (icon != null) {
            drawIcon(canvas, icon, left, ((canvas.height - ICON_H) / 2).coerceAtLeast(0), coverage)
        }
        val textLeft = left + iconW
        for (line in lines) {
            val x = textLeft + (widest - font.measure(line)) / 2
            font.draw(canvas, line, x, y, settings.colorPrimary, coverage)
            y += font.height + gap
        }
    }

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
        coverage: Float
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
                if (g[col] and (1 shl row) != 0) {
                    canvas.blendA(x0 + col, y0 + row, color, coverage)
                }
            }
        }
    }

    private val WEEKDAYS = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
}
