package com.example.ipixelclock.render

import com.example.ipixelclock.led.IPixelHub

/**
 * Where a rendered frame goes.
 *
 * The app is deliberately usable with no iPixel hardware at all: it installs,
 * runs, previews and configures on a bare phone, and only actually needs a panel
 * when you want light in the room. That is what this interface buys — the render
 * pipeline asks its target for a size and hands it frames, and neither it nor
 * anything above it knows whether there is a Bluetooth panel on the other end.
 *
 * Two implementations: [LivePanel] wrapping the BLE driver, and [SimulatedPanel]
 * which is a size and nothing else. Previews are fed from the pipeline either
 * way, so the web UI and the in-app preview look and behave identically.
 */
interface PanelTarget {

    /** Physical panel width in pixels; 0 while nothing is resolved. */
    val panelWidth: Int

    /** Physical panel height in pixels; 0 while nothing is resolved. */
    val panelHeight: Int

    /** True only for a real panel that is connected and taking frames. */
    val isLive: Boolean

    /** One line for the UI: "SIMULATED 96x16", "CONNECTING...", and so on. */
    val status: String

    /** Whether the pipeline should keep producing frames at all. */
    val wantsFrames: Boolean

    /** Panel brightness 0..100, where the target can honour it in hardware. */
    fun setBrightness(pct: Int)
}

/** A real panel, driven by [IPixelHub]. */
class LivePanel(val hub: IPixelHub) : PanelTarget {

    @Volatile
    var lastStatus: String = "STARTING..."

    override val panelWidth: Int get() = hub.width
    override val panelHeight: Int get() = hub.height
    override val isLive: Boolean get() = hub.isEnabled && hub.width > 0 && hub.height > 0
    override val status: String get() = lastStatus
    override val wantsFrames: Boolean get() = hub.isEnabled

    override fun setBrightness(pct: Int) {
        hub.setBrightness(pct)
    }
}

/**
 * No panel. The pipeline renders at the configured size and the frames go only
 * to the previews, so every font, effect and background is tunable with the
 * Bluetooth radio switched off entirely.
 */
class SimulatedPanel(
    @Volatile var width: Int = DEFAULT_W,
    @Volatile var height: Int = DEFAULT_H
) : PanelTarget {

    override val panelWidth: Int get() = width
    override val panelHeight: Int get() = height
    override val isLive: Boolean get() = false
    override val status: String get() = "SIMULATED ${width}×$height"
    override val wantsFrames: Boolean get() = true

    override fun setBrightness(pct: Int) {
        // Nothing to dim. The renderer still applies its software scaling, so
        // the preview shows what the brightness setting would look like.
    }

    companion object {
        const val DEFAULT_W = 96
        const val DEFAULT_H = 16

        /**
         * The panel sizes worth offering when there is no panel to ask.
         *
         * The landscape entries are the driver's own device-type table, which
         * is the authoritative list of what iPixel firmware reports. The
         * portrait entries are the same modules hung as a vertical banner —
         * a 144x16 strip on its end is a 16x144 scene. With a real panel you
         * get there with the rotation control instead, since the firmware
         * always reports its native landscape size; this list is for designing
         * a banner layout before the hardware is on the wall.
         */
        val PRESETS: List<Pair<Int, Int>> = listOf(
            96 to 16,    // type 132, the tuned default
            144 to 16,   // type 135
            192 to 16,   // type 136
            64 to 16,    // type 131
            32 to 16,    // type 130
            32 to 32,    // type 129
            64 to 64,    // type 128
            64 to 20,    // type 133
            48 to 24,    // type 137
            64 to 32,    // type 138
            96 to 32,    // types 139 / 141
            128 to 32,   // types 134 / 140
            160 to 32,   // type 142
            192 to 32,   // type 143
            256 to 32,   // type 144
            320 to 32,   // type 145
            384 to 32,   // type 146
            448 to 32,   // type 147

            // The same modules hung as a vertical banner.
            16 to 96,
            16 to 144,
            16 to 192,
            16 to 64,
            16 to 32,
            32 to 96,
            32 to 128,
            32 to 64,
            20 to 64,
            24 to 48
        )
    }
}
