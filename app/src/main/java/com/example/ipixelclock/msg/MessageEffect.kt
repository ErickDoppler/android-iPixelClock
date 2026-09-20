package com.example.ipixelclock.msg

import com.example.ipixelclock.render.PixelCanvas

/**
 * How a custom message arrives on the panel.
 *
 * Two shapes of effect, and the difference decides how long a showing lasts:
 *
 *  * **travelling** ([paged] false) — the text moves across the panel, so it can
 *    be any length and the duration falls out of the distance and the speed.
 *  * **paged** ([paged] true) — the text is revealed in place, so it has to fit;
 *    anything longer is broken into panel-width pages by [MessagePager] and the
 *    effect is replayed for each one.
 *
 * Every effect is a pure function of [MessageContext.progress], never of a frame
 * counter — the same rule the digit transitions and the backgrounds follow, and
 * for the same reason. This panel delivers between one and twelve frames a
 * second depending on the phone's radio, so a scroll that advanced a pixel per
 * frame would cross the display at wildly different speeds on different phones
 * and stutter whenever one was dropped.
 */
interface MessageEffect {

    /** Stable id, stored in the settings and sent in `/api/schema`. */
    val id: String

    /** What the picker calls it. */
    val label: String

    /** True when long text must be split into pages rather than scrolled. */
    val paged: Boolean get() = true

    /**
     * How long one step lasts — one page for a paged effect, the whole traverse
     * for a travelling one.
     */
    fun stepMs(speed: Int, m: MessageMetrics): Long = MessageSpeed.holdMs(speed)

    fun draw(c: MessageContext)
}

/** What an effect needs to work out how long it will take. */
class MessageMetrics(
    val artWidth: Int,
    val artHeight: Int,
    val canvasWidth: Int,
    val canvasHeight: Int
)

/**
 * The speed dial, in the two units the effects actually need.
 *
 * One slider drives both because they are the same intent: 0 is a slow crawl a
 * passer-by can read twice, 100 is a flick. Mapping it to pixels a second for
 * the travelling effects and to a page duration for the rest is what keeps a
 * marquee and a typewriter feeling like the same setting.
 */
object MessageSpeed {

    /** Columns a second for the travelling effects. */
    fun pxPerSec(speed: Int): Float {
        val s = speed.coerceIn(0, 100) / 100f
        return 9f + s * 111f
    }

    /** How long one page holds for the in-place effects. */
    fun holdMs(speed: Int): Long {
        val s = speed.coerceIn(0, 100) / 100f
        return (5400f - s * 4200f).toLong()
    }
}

/**
 * Everything an effect needs to draw one frame of a message, and the primitives
 * to do it with.
 *
 * Coordinates are canvas coordinates — a message owns the whole panel, so there
 * is no cell to be local to — and [PixelCanvas] clips for us.
 */
class MessageContext(
    val canvas: PixelCanvas,
    /** This page's text, already rasterised. */
    val art: MessageArt,
    val color: Int,
    /** 0..1 through this step. */
    val progress: Float,
    /** Milliseconds into this step, for the effects with a fixed-rate part. */
    val elapsedMs: Long,
    val stepMs: Long,
    /** Which page of the message, and how many there are. */
    val page: Int,
    val pageCount: Int,
    /** Which repetition of the whole message this is, 0-based. */
    val pass: Int,
    /** Stable across a step, so scatter does not reshuffle every frame. */
    val seed: Int
) {

    /** Top row of the text when it is vertically centred. */
    val baseY: Int = ((canvas.height - art.height) / 2).coerceAtLeast(0)

    /** Left column of the text when it is centred across. */
    val baseX: Int = (canvas.width - art.width) / 2

    fun put(x: Int, y: Int, alpha: Float = 1f) {
        canvas.blendA(x, y, color, alpha)
    }

    fun putColor(x: Int, y: Int, c: Int, alpha: Float = 1f) {
        canvas.blendA(x, y, c, alpha)
    }

    /** The whole page, shifted to ([x], [y]). */
    fun drawArt(x: Int = baseX, y: Int = baseY, alpha: Float = 1f) {
        if (alpha <= 0f) return
        art.forEachLit { ax, ay -> put(x + ax, y + ay, alpha) }
    }

    /**
     * A stable pseudo-random 0..1. Deterministic in the seed and the inputs, so
     * a scattered pixel takes the same path for the whole step instead of
     * twitching to a new one every frame.
     */
    fun noise(a: Int, b: Int = 0): Float {
        var h = seed * -0x61c88647
        h = h xor (a + -0x61c88647 + (h shl 6) + (h ushr 2))
        h = h xor (b + -0x7a143589 + (h shl 6) + (h ushr 2))
        h = h xor (h ushr 15)
        return ((h ushr 8) and 0xFFFF) / 65535f
    }
}
