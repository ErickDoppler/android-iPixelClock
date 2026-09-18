package com.example.ipixelclock.render

import com.example.ipixelclock.bg.BackgroundEngine
import com.example.ipixelclock.face.ClockFace
import com.example.ipixelclock.face.ColorModes
import com.example.ipixelclock.settings.Settings

/**
 * Composites one frame.
 *
 * The order is fixed and the reasoning behind it is worth stating, because it is
 * what makes a vertical mount work properly rather than approximately:
 *
 *  1. pick the **logical** canvas — the panel's size, axes swapped at 90/270
 *  2. background draws
 *  3. the clock face draws over it, at whatever opacity the visibility policy says
 *  4. the info row draws
 *  5. software brightness
 *  6. the orientation transform maps the logical scene onto the panel's
 *     **physical** buffer
 *
 * Everything from 2 to 4 only ever asks the canvas for its own width and height,
 * so none of it knows or cares how the panel is hung. A 96x16 module mounted
 * vertically is composed as a tall 16x96 scene and gets the tall layout for free.
 *
 * Not thread-safe: the service calls [render] from the hub thread only.
 */
class FrameRenderer {

    private val face = ClockFace()

    private var logical: PixelCanvas? = null
    private var physical: PixelCanvas? = null

    /**
     * The composed scene before the orientation transform — i.e. the panel as a
     * person standing in front of it sees it.
     *
     * This, not [render]'s physical buffer, is what the previews show. On a
     * vertically mounted panel the physical buffer is a 144x16 strip carrying a
     * sideways clock, which is correct to send and useless to look at; the
     * preview's job is to answer "what will be on the wall".
     */
    val scene: PixelCanvas?
        get() = logical

    /** Wall-clock of the last frame, for the effects' delta timing. */
    private var lastFrameMs = 0L

    /** Set by the service so the sun-driven policies have something to go on. */
    @Volatile
    var sunriseMs: Long? = null

    @Volatile
    var sunsetMs: Long? = null

    /**
     * Renders into the panel's physical buffer and returns it, or null when
     * there is nothing sensible to draw (no size resolved yet).
     *
     * The returned canvas is reused between calls — copy it if you need to keep
     * it. The service does exactly that when fanning frames out to previews.
     */
    fun render(
        settings: Settings,
        panelW: Int,
        panelH: Int,
        nowMs: Long
    ): PixelCanvas? {
        if (panelW <= 0 || panelH <= 0) return null

        val orientation = Orientation(
            rotation = Rotation.ofDegrees(settings.rotation),
            mirrorH = settings.mirrorH,
            mirrorV = settings.mirrorV
        )

        val lw = orientation.logicalWidth(panelW, panelH)
        val lh = orientation.logicalHeight(panelW, panelH)

        val scene = logical.let {
            if (it != null && it.width == lw && it.height == lh) it
            else PixelCanvas(lw, lh).also { c -> logical = c }
        }
        val out = physical.let {
            if (it != null && it.width == panelW && it.height == panelH) it
            else PixelCanvas(panelW, panelH).also { c -> physical = c }
        }

        val dtMs = if (lastFrameMs == 0L) 0L else (nowMs - lastFrameMs).coerceIn(0L, 1000L)
        lastFrameMs = nowMs

        scene.clear()

        // The display switch blanks the output without tearing the service or
        // the Bluetooth link down — flipping it back on is then instant.
        if (!settings.displayOn) {
            return orientation.apply(scene, out)
        }

        // 2. Background. Phase 3 hangs the effect engine here; until then the
        //    layer is black, which is exactly the "off" effect.
        drawBackground(scene, settings, nowMs, dtMs)

        // 3. The clock face.
        val coverage = face.visibility(settings, nowMs, sunriseMs, sunsetMs)
        face.draw(scene, settings, nowMs, coverage, reservedBottom = infoRowHeight(scene, settings))

        // 4. The info row. Phase 5 fills this in.
        drawInfoRow(scene, settings, nowMs)

        // 5. Software brightness, on top of the panel's own hardware dimming.
        scene.scaleBrightness(ColorModes.softwareBrightness(effectiveBrightness(settings, nowMs)))

        // 6. Onto the panel.
        return orientation.apply(scene, out)
    }

    /**
     * The brightness to actually use: the flat setting, or the day/night pair
     * when auto mode is on and the sun times are known.
     */
    fun effectiveBrightness(settings: Settings, nowMs: Long): Int {
        if (!settings.brightnessAuto) return settings.brightness
        val rise = sunriseMs
        val set = sunsetMs
        if (rise == null || set == null) return settings.brightness
        return if (nowMs in rise..set) settings.brightnessDay else settings.brightnessNight
    }

    // ------------------------------------------------------------ the layers

    private val background = BackgroundEngine()

    private fun drawBackground(
        canvas: PixelCanvas,
        settings: Settings,
        nowMs: Long,
        dtMs: Long
    ) {
        background.render(
            canvas = canvas,
            id = settings.background,
            nowMs = nowMs,
            dtMs = dtMs,
            speed = settings.backgroundSpeed,
            intensity = settings.backgroundIntensity,
            // The background's own colours, not the face's — retuning the clock
            // gradient must not repaint the wall behind it.
            primary = settings.backgroundColor,
            secondary = settings.backgroundColor2
        )
    }

    private fun infoRowHeight(canvas: PixelCanvas, settings: Settings): Int {
        // Phase 5: a second zone only exists where there is room for one, which
        // in practice means the 32-row panels and vertical mounts.
        val wants = settings.showDate || settings.showWeather ||
            settings.showSunrise || settings.showSunset
        return if (wants && canvas.height >= 21) 6 else 0
    }

    private fun drawInfoRow(canvas: PixelCanvas, settings: Settings, nowMs: Long) {
        // Phase 5.
    }

}
