package com.example.ipixelclock.bg

import com.example.ipixelclock.render.PixelCanvas

/**
 * A background layer, drawn under the clock face.
 *
 * Two kinds live behind this interface and the distinction matters:
 *
 *  - **Parametric** effects (plasma, waves, scanlines) are pure functions of
 *    absolute time. They ignore [BgContext.dtMs] entirely and cannot drift.
 *  - **Simulated** effects (matrix rain, fire, Conway) carry state and advance
 *    it by [BgContext.dtMs], which is *real elapsed milliseconds*, never a frame
 *    count. The panel delivers anywhere from 1 to 12 frames a second and the
 *    rate wanders, so anything advancing per frame would run at a different
 *    speed on every panel — the same rule the digit effects follow.
 *
 * Everything is sized to the canvas it is handed, so a vertical banner gets a
 * background composed tall rather than a landscape one rotated.
 */
interface Background {

    /** Stable id, stored in the settings and sent in `/api/schema`. */
    val id: String

    /** What the picker calls it. */
    val label: String

    /** Called when the effect is selected or the canvas size changes. */
    fun reset(width: Int, height: Int) {}

    /** Advances simulation. Parametric effects leave this alone. */
    fun update(ctx: BgContext) {}

    /** Draws the layer. The canvas arrives cleared to black. */
    fun draw(ctx: BgContext)
}

/** Everything an effect needs, plus the helpers most of them want. */
class BgContext(
    val canvas: PixelCanvas,
    /** Wall clock, for the parametric effects. */
    val nowMs: Long,
    /** Real elapsed milliseconds since the previous frame. */
    val dtMs: Long,
    /** 0..1 from the speed slider. */
    val speed: Float,
    /** 0..1 from the intensity slider — density, brightness, how busy. */
    val intensity: Float,
    val primary: Int,
    val secondary: Int
) {
    val width get() = canvas.width
    val height get() = canvas.height

    /** Seconds elapsed, wrapped so a Float can still resolve a millisecond.
     *  Epoch millis in a Float quantise to ~128 ms steps and freeze an
     *  animation solid; the colour modes were bitten by exactly that. */
    val t: Float get() = ((nowMs % 86_400_000L) / 1000.0).toFloat()

    /** Seconds elapsed scaled by the speed slider, for the parametric effects. */
    fun time(base: Float): Float = t * base * (0.15f + speed * 1.85f)

    /** Real seconds since the last frame, scaled by the speed slider. */
    fun step(base: Float): Float = (dtMs / 1000f) * base * (0.15f + speed * 1.85f)
}

/**
 * Holds the selected effect and swaps it when the settings or the canvas
 * change. One instance lives on the render thread.
 */
class BackgroundEngine {

    private var current: Background = Backgrounds.of("off")
    private var currentId: String = "off"
    private var lastW = 0
    private var lastH = 0

    fun render(
        canvas: PixelCanvas,
        id: String,
        nowMs: Long,
        dtMs: Long,
        speed: Int,
        intensity: Int,
        primary: Int,
        secondary: Int
    ) {
        if (id != currentId) {
            current = Backgrounds.of(id)
            currentId = id
            lastW = 0 // force a reset below
        }
        if (canvas.width != lastW || canvas.height != lastH) {
            lastW = canvas.width
            lastH = canvas.height
            current.reset(canvas.width, canvas.height)
        }
        if (currentId == "off") return

        val ctx = BgContext(
            canvas = canvas,
            nowMs = nowMs,
            dtMs = dtMs,
            speed = (speed.coerceIn(0, 100)) / 100f,
            intensity = (intensity.coerceIn(0, 100)) / 100f,
            primary = primary,
            secondary = secondary
        )
        current.update(ctx)
        current.draw(ctx)
    }
}

/** The registry the settings and `/api/schema` read from. */
object Backgrounds {

    val all: List<Background> by lazy {
        listOf(
            Off, Solid, Gradient,
            MatrixRain, Starfield, StarFlight,
            RollingRoad, SeaWaves, Flight, Pipes,
            Plasma, Fire, Rain, Snow,
            Scanlines, Aurora, Equalizer, Life, Circuit
        )
    }

    private val byId by lazy { all.associateBy { it.id } }

    fun of(id: String?): Background = byId[id] ?: Off

    /** `(id, label)` pairs for the picker. */
    fun options(): List<Pair<String, String>> = all.map { it.id to it.label }
}
