package com.example.ipixelclock.web

import com.example.ipixelclock.face.ColorModes
import com.example.ipixelclock.bg.Backgrounds
import com.example.ipixelclock.font.PixelFonts
import com.example.ipixelclock.fx.Transitions
import com.example.ipixelclock.msg.MessageEffects
import com.example.ipixelclock.render.SimulatedPanel
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the web routes are allowed to do.
 *
 * Deliberately an interface implemented by the service rather than a class that
 * reaches into it: the server runs on its own threads and this is the whole of
 * the surface it can touch, which keeps "a browser asked for something" and "the
 * panel is being driven" from tangling.
 */
interface Api {

    /** Full picture: connection, panel, settings, weather, server. */
    fun state(): JSONObject

    /** Applies a settings patch and returns the new state. */
    fun patchSettings(patch: JSONObject): JSONObject

    /** `{"on": true|false}` — blanks the panel without stopping the service. */
    fun power(body: JSONObject): JSONObject

    /** `{"value": 0..100}` or `{"auto": bool, "day": n, "night": n}`. */
    fun brightness(body: JSONObject): JSONObject

    /** `{"action": "scan"|"reconnect"|"forget"|"simulate", ...}`. */
    fun detect(body: JSONObject): JSONObject

    /**
     * `{"action": "show"|"cancel"}` — plays the custom message now, or stops
     * the one playing. A settings patch may ride along in the same body, so
     * SHOW NOW picks up text the box has not blurred yet.
     */
    fun message(body: JSONObject): JSONObject

    /** The current frame as a PNG, for browsers that cannot hold a socket. */
    fun previewPng(): ByteArray?

    /** City search, proxied so the browser never talks to a third party. */
    fun geocode(query: String): JSONObject

    /**
     * The catalogue the UI builds itself from: fonts, colour modes,
     * transitions, backgrounds, panel presets. Sending it as data means adding
     * a font in Kotlin makes it appear in the picker with no HTML change, and an
     * option is never offered that will not fit the panel that is attached.
     */
    fun schema(): JSONObject = Schema.build()
}

/** Builds the static half of `/api/schema`. */
object Schema {

    fun build(): JSONObject = JSONObject().apply {
        put("fonts", fonts())
        put("colorModes", pairs(ColorModes.MODES))
        // Straight from the effect registry, so adding one in Kotlin puts it in
        // the picker with no HTML change.
        put("transitions", pairs(Transitions.options()))
        put("backgrounds", pairs(Backgrounds.options()))
        put("messageEffects", pairs(MessageEffects.options()))
        put("mediaMotions", pairs(MEDIA_MOTIONS))
        put("visibilities", pairs(VISIBILITIES))
        put("verticalStyles", pairs(VERTICAL_STYLES))
        put("dateFormats", pairs(DATE_FORMATS))
        put("panelPresets", presets())
        put("rotations", JSONArray(listOf(0, 90, 180, 270)))
    }

    private fun fonts(): JSONArray {
        val arr = JSONArray()
        for (f in PixelFonts.families) {
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("name", f.name)
                    .put("blurb", f.blurb)
                    .put("heights", JSONArray(f.heights))
            )
        }
        return arr
    }

    private fun presets(): JSONArray {
        val arr = JSONArray()
        for ((w, h) in SimulatedPanel.PRESETS) {
            val shape = if (h > w) " (banner)" else ""
            arr.put(
                JSONObject().put("w", w).put("h", h)
                    .put("label", "${w}×$h$shape")
            )
        }
        return arr
    }

    private fun pairs(list: List<Pair<String, String>>): JSONArray {
        val arr = JSONArray()
        for ((id, label) in list) arr.put(JSONObject().put("id", id).put("label", label))
        return arr
    }

    /** How a vertical banner arranges the time. */
    val VERTICAL_STYLES: List<Pair<String, String>> = listOf(
        "pairs" to "Two digits a row (13 / 45)",
        "digits" to "One digit a row, with a divider"
    )

    val MEDIA_MOTIONS: List<Pair<String, String>> = listOf(
        "static" to "Static, centred",
        "fit" to "Fit",
        "cover" to "Cover",
        "scroll-h" to "Scroll horizontally",
        "scroll-v" to "Scroll vertically",
        "bounce" to "Bounce around",
        "zoom" to "Slow zoom",
        "playlist" to "Cross-fade playlist"
    )

    val VISIBILITIES: List<Pair<String, String>> = listOf(
        "always" to "Always on",
        "duty" to "Hide periodically",
        "day" to "Daytime only",
        "night" to "Night only",
        "schedule" to "On a schedule"
    )

    val DATE_FORMATS: List<Pair<String, String>> = listOf(
        "DMY" to "31.12",
        "MDY" to "12.31",
        "YMD" to "12-31",
        "WEEKDAY" to "Weekday"
    )
}
