package com.example.ipixelclock.settings

import org.json.JSONObject

/**
 * Everything the clock can be told to do, in one object.
 *
 * It is deliberately one flat-ish JSON document rather than a pile of
 * SharedPreferences keys: the web UI, the in-app WebView and the renderer all
 * need the same picture, and a single blob means a settings change is one atomic
 * write, one broadcast and one re-render. The JSON shape is also exactly what
 * `GET /api/state` returns and what `POST /api/settings` patches, so there is no
 * mapping layer anywhere.
 *
 * Every field carries a default that is safe on a bare phone with no panel, no
 * network and no location.
 */
data class Settings(

    // ------------------------------------------------------------- the panel

    /** Master output switch. Off blanks the panel but keeps the service up. */
    val displayOn: Boolean = true,

    /** 0..100. Sent to the panel's own 0x8004 command and applied in software. */
    val brightness: Int = 80,

    /** Follow sunrise/sunset between [brightnessDay] and [brightnessNight]. */
    val brightnessAuto: Boolean = false,
    val brightnessDay: Int = 90,
    val brightnessNight: Int = 25,

    /** Mounting: 0/90/180/270 plus the two mirrors. */
    val rotation: Int = 0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,

    /** Size to render at when no panel is connected. */
    val simulatedWidth: Int = 96,
    val simulatedHeight: Int = 16,

    /** Manual override when a panel connects but does not report its type. */
    val manualWidth: Int = 0,
    val manualHeight: Int = 0,

    // -------------------------------------------------------- the clock face

    /** Font family id from `PixelFonts.families`. */
    val fontFamily: String = "system",

    /** 24-hour clock. False shows 1..12. */
    val hour24: Boolean = true,

    /** Append seconds to the time. Costs width; off by default on 96 columns. */
    val showSeconds: Boolean = false,

    /** Blink the colon once a second. */
    val blinkColon: Boolean = true,

    /** Pad the hour with a leading zero below 10. */
    val leadingZero: Boolean = true,

    /** Colour mode: solid | gradient | gradient-animated | rainbow | per-digit. */
    val colorMode: String = "solid",
    val colorPrimary: Int = 0xFF00F0FF.toInt(),
    val colorSecondary: Int = 0xFFFF2BD1.toInt(),
    /** Gradient direction in degrees; 0 = left to right. */
    val gradientAngle: Int = 0,
    /** Degrees per second for the animated colour modes. */
    val colorSpeed: Int = 30,

    /** Transition effect id run when a digit changes. */
    val transition: String = "fade",
    val transitionMs: Int = 400,

    /**
     * How the time is arranged when the scene is taller than it is wide — a
     * panel hung as a vertical banner.
     *
     * "pairs" stacks HH over MM, two digits to a row. "digits" gives every digit
     * its own row with a divider between the groups, which on a 16-column banner
     * lets each digit be twice the size.
     */
    val verticalStyle: String = "pairs",

    /** Visibility: always | duty | day | night | schedule. */
    val visibility: String = "always",
    /** For "duty": show the face for this long, then hide it for [hideSeconds]. */
    val dutyShowSeconds: Int = 50,
    val hideSeconds: Int = 10,
    /** For "schedule": minutes past midnight. */
    val scheduleFrom: Int = 7 * 60,
    val scheduleTo: Int = 23 * 60,

    // --------------------------------------------------------- the background

    /** Background effect id; "off" leaves it black. */
    val background: String = "off",

    /**
     * The background's own colours, deliberately separate from the face's.
     *
     * Reusing [colorSecondary] meant retuning the clock's gradient silently
     * repainted the wall behind it. [backgroundColor] is the solid fill and the
     * gradient's start; [backgroundColor2] is the gradient's end. Effects with
     * an identity of their own — fire, matrix rain — keep their own palette and
     * ignore both.
     */
    val backgroundColor: Int = 0xFF101822.toInt(),
    val backgroundColor2: Int = 0xFF2B0B3A.toInt(),
    /** Effect-specific knobs, kept opaque so effects can add their own. */
    val backgroundParams: Map<String, Any> = emptyMap(),
    val backgroundSpeed: Int = 50,
    val backgroundIntensity: Int = 50,

    /** Media id from the library, when [background] is "media". */
    val mediaId: String = "",
    /** static | fit | cover | scroll-h | scroll-v | bounce | zoom | playlist. */
    val mediaMotion: String = "static",

    // ---------------------------------------------------------- the info row

    val showDate: Boolean = true,
    /** strftime-ish: DMY | MDY | YMD | WEEKDAY. */
    val dateFormat: String = "DMY",
    val showWeather: Boolean = false,
    val showSunrise: Boolean = false,
    val showSunset: Boolean = false,
    val metricUnits: Boolean = true,

    /** Manual city. Wins over any device location fix when set. */
    val cityName: String = "",
    val cityLat: Double = Double.NaN,
    val cityLon: Double = Double.NaN,

    // ------------------------------------------------------------- the system

    /** Preferred TCP port. Cannot be below 1024 — Android forbids it. */
    val port: Int = 8080,
    val startOnBoot: Boolean = false,
    /** Floor between frames, ms. Lower is smoother and costs battery. */
    val frameIntervalMs: Int = 80
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("displayOn", displayOn)
        put("brightness", brightness)
        put("brightnessAuto", brightnessAuto)
        put("brightnessDay", brightnessDay)
        put("brightnessNight", brightnessNight)
        put("rotation", rotation)
        put("mirrorH", mirrorH)
        put("mirrorV", mirrorV)
        put("simulatedWidth", simulatedWidth)
        put("simulatedHeight", simulatedHeight)
        put("manualWidth", manualWidth)
        put("manualHeight", manualHeight)

        put("fontFamily", fontFamily)
        put("hour24", hour24)
        put("showSeconds", showSeconds)
        put("blinkColon", blinkColon)
        put("leadingZero", leadingZero)
        put("colorMode", colorMode)
        put("colorPrimary", colorPrimary)
        put("colorSecondary", colorSecondary)
        put("gradientAngle", gradientAngle)
        put("colorSpeed", colorSpeed)
        put("transition", transition)
        put("transitionMs", transitionMs)
        put("verticalStyle", verticalStyle)
        put("visibility", visibility)
        put("dutyShowSeconds", dutyShowSeconds)
        put("hideSeconds", hideSeconds)
        put("scheduleFrom", scheduleFrom)
        put("scheduleTo", scheduleTo)

        put("background", background)
        put("backgroundColor", backgroundColor)
        put("backgroundColor2", backgroundColor2)
        put("backgroundParams", JSONObject(backgroundParams))
        put("backgroundSpeed", backgroundSpeed)
        put("backgroundIntensity", backgroundIntensity)
        put("mediaId", mediaId)
        put("mediaMotion", mediaMotion)

        put("showDate", showDate)
        put("dateFormat", dateFormat)
        put("showWeather", showWeather)
        put("showSunrise", showSunrise)
        put("showSunset", showSunset)
        put("metricUnits", metricUnits)
        put("cityName", cityName)
        put("cityLat", if (cityLat.isNaN()) JSONObject.NULL else cityLat)
        put("cityLon", if (cityLon.isNaN()) JSONObject.NULL else cityLon)

        put("port", port)
        put("startOnBoot", startOnBoot)
        put("frameIntervalMs", frameIntervalMs)
    }

    /**
     * Returns a copy with any keys present in [patch] applied. Unknown keys are
     * ignored, so an older web UI talking to a newer service degrades quietly
     * rather than wiping fields it has never heard of.
     */
    fun patched(patch: JSONObject): Settings = Settings(
        displayOn = patch.optBoolean("displayOn", displayOn),
        brightness = patch.optInt("brightness", brightness).coerceIn(0, 100),
        brightnessAuto = patch.optBoolean("brightnessAuto", brightnessAuto),
        brightnessDay = patch.optInt("brightnessDay", brightnessDay).coerceIn(0, 100),
        brightnessNight = patch.optInt("brightnessNight", brightnessNight).coerceIn(0, 100),
        rotation = normaliseRotation(patch.optInt("rotation", rotation)),
        mirrorH = patch.optBoolean("mirrorH", mirrorH),
        mirrorV = patch.optBoolean("mirrorV", mirrorV),
        simulatedWidth = patch.optInt("simulatedWidth", simulatedWidth).coerceIn(8, 512),
        simulatedHeight = patch.optInt("simulatedHeight", simulatedHeight).coerceIn(4, 128),
        manualWidth = patch.optInt("manualWidth", manualWidth).coerceIn(0, 512),
        manualHeight = patch.optInt("manualHeight", manualHeight).coerceIn(0, 128),

        fontFamily = patch.optString("fontFamily", fontFamily),
        hour24 = patch.optBoolean("hour24", hour24),
        showSeconds = patch.optBoolean("showSeconds", showSeconds),
        blinkColon = patch.optBoolean("blinkColon", blinkColon),
        leadingZero = patch.optBoolean("leadingZero", leadingZero),
        colorMode = patch.optString("colorMode", colorMode),
        colorPrimary = optColor(patch, "colorPrimary", colorPrimary),
        colorSecondary = optColor(patch, "colorSecondary", colorSecondary),
        gradientAngle = patch.optInt("gradientAngle", gradientAngle),
        colorSpeed = patch.optInt("colorSpeed", colorSpeed).coerceIn(0, 360),
        transition = patch.optString("transition", transition),
        transitionMs = patch.optInt("transitionMs", transitionMs).coerceIn(0, 5000),
        verticalStyle = patch.optString("verticalStyle", verticalStyle),
        visibility = patch.optString("visibility", visibility),
        dutyShowSeconds = patch.optInt("dutyShowSeconds", dutyShowSeconds).coerceIn(1, 3600),
        hideSeconds = patch.optInt("hideSeconds", hideSeconds).coerceIn(0, 3600),
        scheduleFrom = patch.optInt("scheduleFrom", scheduleFrom).coerceIn(0, 1439),
        scheduleTo = patch.optInt("scheduleTo", scheduleTo).coerceIn(0, 1439),

        background = patch.optString("background", background),
        backgroundColor = optColor(patch, "backgroundColor", backgroundColor),
        backgroundColor2 = optColor(patch, "backgroundColor2", backgroundColor2),
        backgroundParams = optParams(patch, backgroundParams),
        backgroundSpeed = patch.optInt("backgroundSpeed", backgroundSpeed).coerceIn(0, 100),
        backgroundIntensity = patch.optInt("backgroundIntensity", backgroundIntensity).coerceIn(0, 100),
        mediaId = patch.optString("mediaId", mediaId),
        mediaMotion = patch.optString("mediaMotion", mediaMotion),

        showDate = patch.optBoolean("showDate", showDate),
        dateFormat = patch.optString("dateFormat", dateFormat),
        showWeather = patch.optBoolean("showWeather", showWeather),
        showSunrise = patch.optBoolean("showSunrise", showSunrise),
        showSunset = patch.optBoolean("showSunset", showSunset),
        metricUnits = patch.optBoolean("metricUnits", metricUnits),
        cityName = patch.optString("cityName", cityName),
        cityLat = optDouble(patch, "cityLat", cityLat),
        cityLon = optDouble(patch, "cityLon", cityLon),

        // Below 1024 is a privileged port: the bind is refused on Android, so
        // there is no point letting anyone ask for one.
        port = patch.optInt("port", port).coerceIn(1024, 65535),
        startOnBoot = patch.optBoolean("startOnBoot", startOnBoot),
        frameIntervalMs = patch.optInt("frameIntervalMs", frameIntervalMs).coerceIn(30, 5000)
    )

    companion object {

        fun fromJson(json: JSONObject): Settings = Settings().patched(json)

        private fun normaliseRotation(d: Int): Int {
            val r = ((d % 360) + 360) % 360
            return when {
                r < 45 || r >= 315 -> 0
                r < 135 -> 90
                r < 225 -> 180
                else -> 270
            }
        }

        /** Accepts both an int and a "#rrggbb" string, which is what the
         *  browser's colour input hands back. */
        private fun optColor(o: JSONObject, key: String, fallback: Int): Int {
            if (!o.has(key)) return fallback
            val v = o.opt(key)
            return when (v) {
                is Number -> v.toInt() or 0xFF000000.toInt()
                is String -> {
                    val hex = v.removePrefix("#")
                    try {
                        when (hex.length) {
                            6 -> hex.toLong(16).toInt() or 0xFF000000.toInt()
                            8 -> hex.toLong(16).toInt()
                            else -> fallback
                        }
                    } catch (_: Exception) {
                        fallback
                    }
                }
                else -> fallback
            }
        }

        private fun optDouble(o: JSONObject, key: String, fallback: Double): Double {
            if (!o.has(key) || o.isNull(key)) return if (o.has(key)) Double.NaN else fallback
            val d = o.optDouble(key, Double.NaN)
            return if (d.isNaN()) fallback else d
        }

        private fun optParams(o: JSONObject, fallback: Map<String, Any>): Map<String, Any> {
            val p = o.optJSONObject("backgroundParams") ?: return fallback
            val out = HashMap<String, Any>()
            val keys = p.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                p.opt(k)?.let { out[k] = it }
            }
            return out
        }
    }
}
