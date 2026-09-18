package com.example.ipixelclock.led

import android.content.Context
import android.util.Log

/**
 * Picks the frame encoding a given panel is actually fastest with, and
 * remembers it.
 *
 * The driver ships with the configuration measured on a 96x16 E15, where the
 * live-canvas path (opcode 0x0000 behind DIY mode) is the clear winner — about
 * 10 ms a frame against roughly 500 ms for the stored-image path. That is in the
 * driver's own header and it is correct for that panel.
 *
 * It is not universal. Measured here on a 144x16 (type 135, MCU 21.17 /
 * BLE 1.13), the same code gives:
 *
 *   0x0000 live canvas    1.0 fps   (about 900 ms a frame, acknowledged, no errors)
 *   0x0002 raw RGB888     0.6 fps
 *   0x0002 PNG            8.1 fps
 *
 * — the exact inverse. The transfer is ~30 ms either way, so this is the panel's
 * own processing, not the radio, and no amount of tuning on the phone side
 * changes it. The only sound answer is to measure rather than assume.
 *
 * So: on first sight of a panel size, try each candidate for a few seconds, keep
 * the fastest, and never probe that size again. The driver's compiled defaults
 * stay exactly as they were tuned; this only chooses between them.
 */
class PanelTuning(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One measured configuration. */
    data class Config(val frameMode: Int, val writeWithResponse: Boolean) {

        val label: String
            get() {
                val path = when (frameMode) {
                    IPixelHub.FRAME_CAMERA -> "live 0x0000"
                    IPixelHub.FRAME_RAW -> "raw RGB 0x0002"
                    else -> "PNG 0x0002"
                }
                return path + if (writeWithResponse) "" else ", no-response"
            }

        fun serialise(): String = "$frameMode:${if (writeWithResponse) 1 else 0}"

        companion object {
            fun parse(s: String?): Config? {
                val parts = s?.split(':') ?: return null
                if (parts.size != 2) return null
                val mode = parts[0].toIntOrNull() ?: return null
                return Config(mode, parts[1] == "1")
            }
        }
    }

    /**
     * What to try, in the order the driver's own notes rank them. The probe
     * stops early the moment one is comfortably fast, so a panel that behaves
     * like the E15 is on its best setting within a few seconds and never sees
     * the rest.
     */
    val candidates: List<Config> = listOf(
        Config(IPixelHub.FRAME_CAMERA, writeWithResponse = false),
        // Both write modes for the PNG path. Measured on the 144x16, dropping
        // the response took the transfer from 77-108 ms to 5-16 ms for the same
        // frame. The panel claws most of it back in its own processing, so the
        // end-to-end gain is small — but it is a gain, and it is free, and on a
        // panel that is not the bottleneck it would be the whole difference.
        Config(IPixelHub.FRAME_PNG, writeWithResponse = false),
        Config(IPixelHub.FRAME_PNG, writeWithResponse = true),
        Config(IPixelHub.FRAME_RAW, writeWithResponse = true)
    )

    private fun key(w: Int, h: Int) = "${w}x$h"

    /** The remembered best for this panel size, or null if never probed. */
    fun stored(w: Int, h: Int): Config? = Config.parse(prefs.getString(key(w, h), null))

    fun remember(w: Int, h: Int, config: Config, fps: Double) {
        Log.i(TAG, "best for ${key(w, h)}: ${config.label} at ${fmt1(fps)} fps")
        prefs.edit()
            .putString(key(w, h), config.serialise())
            .putFloat(key(w, h) + ".fps", fps.toFloat())
            .apply()
    }

    fun storedFps(w: Int, h: Int): Float = prefs.getFloat(key(w, h) + ".fps", 0f)

    fun forget(w: Int, h: Int) {
        prefs.edit().remove(key(w, h)).remove(key(w, h) + ".fps").apply()
    }

    companion object {
        private const val TAG = "PanelTuning"
        private const val PREFS = "ipixel_tuning"

        /**
         * Locale-fixed number formatting. The default locale would render "7,3"
         * on a Ukrainian or German device, which reads as wrong in an
         * English-language readout and is worse still if it ever reaches JSON.
         */
        fun fmt1(v: Number): String = String.format(java.util.Locale.US, "%.1f", v.toDouble())

        fun fmt2(v: Number): String = String.format(java.util.Locale.US, "%.2f", v.toDouble())

        /** Above this, stop probing — the panel is already keeping up. */
        const val GOOD_ENOUGH_FPS = 9.0

        /** How long each candidate gets. */
        const val PROBE_MS = 6000L

        /** ...unless it has already produced this many frames, which at a
         *  healthy rate happens long before the timer. */
        const val PROBE_FRAMES = 40
    }
}
