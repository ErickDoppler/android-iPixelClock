package com.example.ipixelclock.msg

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * The ways a custom message can arrive on the panel.
 *
 * Read [MessageEffect] first for the two shapes — travelling and paged — and for
 * why nothing here counts frames.
 *
 * The in-place effects share a common rhythm: arrive, hold, leave. The arrival
 * and departure fractions differ per effect but the hold in the middle is the
 * point — a message that is never simply *there*, still and readable, cannot be
 * read at all on a display that is refreshing five times a second.
 */

// ---------------------------------------------------------------- the easings

private fun smooth(t: Float): Float {
    val p = t.coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

/** Overshoots and comes back — the little bit of life in a "pop". */
private fun overshoot(t: Float): Float {
    val p = t.coerceIn(0f, 1f)
    val s = 1.70158f
    val q = p - 1f
    return q * q * ((s + 1f) * q + s) + 1f
}

/** A spring settling: big first swing, each one smaller. */
private fun springy(t: Float): Float {
    val p = t.coerceIn(0f, 1f)
    if (p >= 1f) return 1f
    return 1f - (exp(-6.0 * p) * cos(10.0 * p)).toFloat()
}

/**
 * Maps a step's progress onto the arrive / hold / leave rhythm.
 *
 * Returns -1..0..1: negative while arriving (magnitude is how far from
 * arrived), 0 throughout the hold, positive while leaving.
 */
private fun phase(p: Float, inFrac: Float, outFrac: Float): Float {
    val t = p.coerceIn(0f, 1f)
    if (t < inFrac) return -(1f - t / inFrac)
    if (t > 1f - outFrac) return (t - (1f - outFrac)) / outFrac
    return 0f
}

/** Nearest-neighbour draw of the page scaled about its own centre. */
private fun drawScaled(c: MessageContext, scale: Float, alpha: Float) {
    if (alpha <= 0f || scale <= 0.02f) return
    val art = c.art
    val cx = c.canvas.width / 2f
    val cy = c.baseY + art.height / 2f
    val halfW = (art.width * scale / 2f).toInt() + 1
    val halfH = (art.height * scale / 2f).toInt() + 1
    for (dy in -halfH..halfH) {
        for (dx in -halfW..halfW) {
            val sx = floor(dx / scale + art.width / 2f).toInt()
            val sy = floor(dy / scale + art.height / 2f).toInt()
            if (art.at(sx, sy)) c.put((cx + dx).toInt(), (cy + dy).toInt(), alpha)
        }
    }
}

// ------------------------------------------------------------ the travellers

/**
 * The marquee, left and right.
 *
 * Travel is the panel plus the text, so the line always enters from wholly off
 * one edge and leaves wholly off the other — no part of a message is ever on
 * screen at the start or the end of its pass.
 *
 * Horizontal only. The vertical pair are [Roll], and the comment there says why.
 */
private class Marquee(
    override val id: String,
    override val label: String,
    /** -1 travels right to left, +1 left to right. */
    private val dx: Int
) : MessageEffect {

    override val paged = false

    override fun stepMs(speed: Int, m: MessageMetrics): Long =
        ((m.canvasWidth + m.artWidth) * 1000f / MessageSpeed.pxPerSec(speed))
            .toLong().coerceAtLeast(400L)

    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val travel = c.canvas.width + c.art.width
        val x = if (dx < 0) c.canvas.width - (travel * p).toInt()
        else -c.art.width + (travel * p).toInt()
        c.drawArt(x, c.baseY)
    }
}

/**
 * Rolls a page in from one edge, holds it, and rolls it out of the other.
 *
 * Not a vertical marquee, which is what this started as and what it should not
 * be: a 16-row strip is only thirty-odd pixels of vertical travel, so a
 * continuous roll crossed it in a third of a second and was gone before it could
 * be read. Worse, moving a line of text vertically does nothing about its being
 * too *wide*, so a long message was simply clipped with no way to see the rest.
 *
 * Arriving, holding and leaving fixes both: the hold is what makes it readable,
 * and being paged means a long message is broken up rather than cut off.
 */
private class Roll(
    override val id: String,
    override val label: String,
    /** True to travel upward: in from below, out through the top. */
    private val up: Boolean
) : MessageEffect {

    override fun draw(c: MessageContext) {
        val ph = phase(c.progress, 0.25f, 0.25f)
        if (ph == 0f) {
            c.drawArt()
            return
        }
        // Exactly far enough to be off the edge, and no further. Travelling a
        // full panel-plus-text in each direction looks the same at the ends but
        // parks the line out of sight for a quarter of the step, which on a
        // 16-row panel was half a second of blank display twice a page.
        val below = c.canvas.height - c.baseY
        val above = -(c.baseY + c.art.height)
        val off = if (ph < 0f) {
            ((if (up) below else above) * smooth(-ph)).toInt()
        } else {
            ((if (up) above else below) * smooth(ph)).toInt()
        }
        c.drawArt(c.baseX, c.baseY + off)
    }
}

/** A marquee that bobs, each column a little behind the one before it. */
private object Wave : MessageEffect {
    override val id = "wave"
    override val label = "Wave scroll"
    override val paged = false

    override fun stepMs(speed: Int, m: MessageMetrics): Long =
        ((m.canvasWidth + m.artWidth) * 1000f / MessageSpeed.pxPerSec(speed))
            .toLong().coerceAtLeast(400L)

    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val travel = c.canvas.width + c.art.width
        val x0 = c.canvas.width - (travel * p).toInt()
        // Whatever vertical room the text is not using, up to two rows.
        val amp = ((c.canvas.height - c.art.height) / 2).coerceIn(1, 2)
        c.art.forEachLit { ax, ay ->
            val bob = (sin((ax * 0.22f) + p * 14f) * amp).toInt()
            c.put(x0 + ax, c.baseY + ay + bob)
        }
    }
}

/**
 * A news ticker: the marquee, with the text sliding out from behind the panel's
 * own edges rather than simply appearing at them.
 */
private object Ticker : MessageEffect {
    override val id = "ticker"
    override val label = "News ticker"
    override val paged = false

    override fun stepMs(speed: Int, m: MessageMetrics): Long =
        ((m.canvasWidth + m.artWidth + GAP * 2) * 1000f / MessageSpeed.pxPerSec(speed))
            .toLong().coerceAtLeast(400L)

    private const val GAP = 6

    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val travel = c.canvas.width + c.art.width + GAP * 2
        val x0 = c.canvas.width - (travel * p).toInt()

        // A marker block ahead of and behind the line, like the separator on a
        // stock ticker, so a repeat reads as a new pass rather than a stutter.
        for (y in c.baseY until c.baseY + c.art.height) {
            c.put(x0 - GAP, y, 0.8f)
            c.put(x0 + c.art.width + GAP, y, 0.8f)
        }

        c.art.forEachLit { ax, ay ->
            val x = x0 + ax
            // Dim the outermost columns so the line reads as passing behind a
            // bezel instead of being cut off by one.
            val edge = when {
                x <= 0 || x >= c.canvas.lastX -> 0.35f
                x == 1 || x == c.canvas.lastX - 1 -> 0.7f
                else -> 1f
            }
            c.put(x, c.baseY + ay, edge)
        }
    }
}

// ----------------------------------------------------------- the in-place set

private object Cut : MessageEffect {
    override val id = "switch"
    override val label = "Straight cut"
    override fun draw(c: MessageContext) = c.drawArt()
}

private object Fade : MessageEffect {
    override val id = "fade"
    override val label = "Fade"
    override fun draw(c: MessageContext) {
        val ph = phase(c.progress, 0.22f, 0.22f)
        c.drawArt(alpha = 1f - abs(ph))
    }
}

private object Pop : MessageEffect {
    override val id = "pop"
    override val label = "Pop"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        when {
            p < 0.28f -> drawScaled(c, overshoot(p / 0.28f).coerceAtLeast(0.05f), 1f)
            p > 0.86f -> {
                val q = (p - 0.86f) / 0.14f
                drawScaled(c, 1f - smooth(q), 1f - q)
            }
            else -> c.drawArt()
        }
    }
}

private object Zoom : MessageEffect {
    override val id = "zoom"
    override val label = "Zoom through"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        when {
            p < 0.3f -> {
                val q = smooth(p / 0.3f)
                drawScaled(c, 0.08f + q * 0.92f, q)
            }
            p > 0.75f -> {
                val q = (p - 0.75f) / 0.25f
                drawScaled(c, 1f + q * 2.2f, 1f - smooth(q))
            }
            else -> c.drawArt()
        }
    }
}

private object Wipe : MessageEffect {
    override val id = "wipe"
    override val label = "Wipe"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        // Revealed left to right, then cleared the same way, so the message
        // reads as being written and rubbed out rather than flicked on and off.
        val from: Int
        val to: Int
        when {
            p < 0.3f -> { from = 0; to = (c.art.width * smooth(p / 0.3f)).toInt() }
            p > 0.75f -> { from = (c.art.width * smooth((p - 0.75f) / 0.25f)).toInt(); to = c.art.width }
            else -> { from = 0; to = c.art.width }
        }
        c.art.forEachLit { ax, ay ->
            if (ax in from until to) c.put(c.baseX + ax, c.baseY + ay)
        }
    }
}

/** A bright bar sweeps across, leaving the text lit behind it. */
private object Scan : MessageEffect {
    override val id = "scan"
    override val label = "Scan bar"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val w = c.canvas.width
        var barX = -99
        var from = 0
        var to = c.art.width

        when {
            p < 0.35f -> {
                val q = p / 0.35f
                barX = (q * w).toInt()
                to = ((barX - c.baseX)).coerceIn(0, c.art.width)
            }
            p > 0.78f -> {
                val q = (p - 0.78f) / 0.22f
                barX = (w - q * w).toInt()
                from = 0
                to = ((barX - c.baseX)).coerceIn(0, c.art.width)
            }
        }

        c.art.forEachLit { ax, ay ->
            if (ax in from until to) c.put(c.baseX + ax, c.baseY + ay)
        }

        if (barX > -99) {
            for (y in 0 until c.canvas.height) {
                c.put(barX, y, 0.95f)
                c.put(barX - 1, y, 0.35f)
                c.put(barX + 1, y, 0.35f)
            }
        }
    }
}

/** Horizontal bands arrive from alternating sides and close up. */
private object Blinds : MessageEffect {
    override val id = "blinds"
    override val label = "Blinds"
    override fun draw(c: MessageContext) {
        val ph = phase(c.progress, 0.3f, 0.25f)
        val away = smooth(abs(ph))
        val band = 3
        val reach = c.canvas.width
        c.art.forEachLit { ax, ay ->
            val dir = if ((ay / band) % 2 == 0) 1 else -1
            // Arriving slides in, leaving slides back out the way it came.
            val shift = (away * reach * dir).toInt() * (if (ph > 0f) -1 else 1)
            c.put(c.baseX + ax + shift, c.baseY + ay, 1f - away * 0.4f)
        }
    }
}

private object Typewriter : MessageEffect {
    override val id = "typewriter"
    override val label = "Typewriter"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val n = c.art.chars.size
        if (n == 0) return
        // Typed over the first six tenths, then it simply stands there — the
        // hold is what makes it readable.
        val typed = if (p >= 0.6f) n else ((p / 0.6f) * n).toInt().coerceIn(0, n)

        for (i in 0 until typed) {
            val placed = c.art.chars[i]
            for (gy in 0 until c.art.height) {
                for (gx in 0 until placed.width) {
                    if (c.art.at(placed.x + gx, gy)) {
                        c.put(c.baseX + placed.x + gx, c.baseY + gy)
                    }
                }
            }
        }

        // The cursor blinks on the real clock, not on progress: a caret that
        // slowed down with the effect would not read as a caret.
        if (typed < n && (c.elapsedMs / 260L) % 2L == 0L) {
            val at = c.art.chars[typed]
            for (gy in 0 until c.art.height) {
                for (gx in 0 until at.width.coerceAtMost(3)) {
                    c.put(c.baseX + at.x + gx, c.baseY + gy, 0.55f)
                }
            }
        }
    }
}

/** Characters drop into place one after another. */
private object Stack : MessageEffect {
    override val id = "stack"
    override val label = "Drop in"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val n = c.art.chars.size
        if (n == 0) return
        val ph = phase(p, 0.45f, 0.2f)

        for (i in 0 until n) {
            val placed = c.art.chars[i]
            // Each character starts a little after the one before, so the line
            // assembles left to right rather than landing as a slab.
            val stagger = i.toFloat() / n
            val local = if (ph < 0f) {
                ((1f - abs(ph)) - stagger * 0.6f) / (1f - stagger * 0.6f).coerceAtLeast(0.05f)
            } else {
                1f - ph
            }
            val t = springy(local.coerceIn(0f, 1f))
            val dy = ((1f - t) * -(c.canvas.height)).toInt()
            if (local <= 0f) continue
            for (gy in 0 until c.art.height) {
                for (gx in 0 until placed.width) {
                    if (c.art.at(placed.x + gx, gy)) {
                        c.put(c.baseX + placed.x + gx, c.baseY + gy + dy)
                    }
                }
            }
        }
    }
}

/** The two halves converge from opposite edges, then part again. */
private object Split : MessageEffect {
    override val id = "split"
    override val label = "Split apart"
    override fun draw(c: MessageContext) {
        val ph = phase(c.progress, 0.3f, 0.25f)
        val away = smooth(abs(ph))
        val mid = c.art.width / 2
        val reach = c.canvas.width
        c.art.forEachLit { ax, ay ->
            val dir = if (ax < mid) -1 else 1
            c.put(c.baseX + ax + (away * reach * dir).toInt(), c.baseY + ay, 1f - away * 0.3f)
        }
    }
}

/** Every pixel flies in from somewhere else and settles into the glyph. */
private object Dust : MessageEffect {
    override val id = "dust"
    override val label = "Assemble from dust"
    override fun draw(c: MessageContext) {
        val ph = phase(c.progress, 0.4f, 0.3f)
        val away = smooth(abs(ph))
        if (away <= 0.001f) {
            c.drawArt()
            return
        }
        var i = 0
        c.art.forEachLit { ax, ay ->
            i++
            val ox = (c.noise(i, 1) - 0.5f) * c.canvas.width * 1.4f
            val oy = (c.noise(i, 2) - 0.5f) * c.canvas.height * 2.2f
            c.put(
                c.baseX + ax + (ox * away).toInt(),
                c.baseY + ay + (oy * away).toInt(),
                1f - away * 0.55f
            )
        }
    }
}

/** Lands whole, then breaks up and falls away. */
private object Shatter : MessageEffect {
    override val id = "shatter"
    override val label = "Shatter"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        if (p < 0.6f) {
            c.drawArt(alpha = if (p < 0.08f) p / 0.08f else 1f)
            return
        }
        val q = (p - 0.6f) / 0.4f
        var i = 0
        c.art.forEachLit { ax, ay ->
            i++
            val vx = (c.noise(i, 3) - 0.5f) * 2.4f
            // Thrown up, then pulled down: the arc is what makes it break
            // rather than simply disperse.
            val vy = -(0.4f + c.noise(i, 4) * 1.1f)
            val t = q * c.canvas.height
            c.put(
                c.baseX + ax + (vx * t).toInt(),
                c.baseY + ay + (vy * t + 0.09f * t * t).toInt(),
                1f - q
            )
        }
    }
}

/** Falling streams freeze into the characters, column by column. */
private object MatrixResolve : MessageEffect {
    override val id = "matrix"
    override val label = "Matrix resolve"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val h = c.canvas.height
        for (ax in 0 until c.art.width) {
            // Each column settles at its own moment, spread over the arrival.
            val settleAt = 0.12f + c.noise(ax, 7) * 0.4f
            val x = c.baseX + ax
            if (p >= settleAt) {
                val leaving = if (p > 0.82f) (p - 0.82f) / 0.18f else 0f
                for (ay in 0 until c.art.height) {
                    if (c.art.at(ax, ay)) c.put(x, c.baseY + ay, 1f - leaving)
                }
                continue
            }
            // Still falling: a bright head with a short tail behind it.
            val speed = 1.4f + c.noise(ax, 8) * 2.2f
            val head = ((p * speed * h * 3f) + c.noise(ax, 9) * h).toInt() % (h * 2) - h / 2
            for (t in 0 until 5) {
                val y = head - t
                if (y in 0 until h) {
                    c.putColor(x, y, if (t == 0) 0xFFCFFFE0.toInt() else 0xFF2BFF88.toInt(),
                        if (t == 0) 0.95f else 0.55f - t * 0.11f)
                }
            }
        }
    }
}

private object Glitch : MessageEffect {
    override val id = "glitch"
    override val label = "Glitch"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val ph = phase(p, 0.3f, 0.25f)
        val chaos = abs(ph)
        if (chaos <= 0.001f) {
            c.drawArt()
            return
        }
        val band = 3
        // The slice offsets change a few times a second rather than every
        // frame, which is what reads as a broken signal instead of noise.
        val tick = (c.elapsedMs / 90L).toInt()
        c.art.forEachLit { ax, ay ->
            val slice = ay / band
            val shift = ((c.noise(slice, tick) - 0.5f) * 14f * chaos).toInt()
            val x = c.baseX + ax + shift
            val y = c.baseY + ay
            if (chaos > 0.2f) {
                c.putColor(x - 1, y, 0xFFFF0040.toInt(), 0.5f * chaos)
                c.putColor(x + 1, y, 0xFF00FFE0.toInt(), 0.5f * chaos)
            }
            c.put(x, y, 1f - 0.25f * chaos)
        }
    }
}

private object Flash : MessageEffect {
    override val id = "flash"
    override val label = "Flash"
    override fun draw(c: MessageContext) {
        // Six hard blinks across the step, on the step's own clock so the speed
        // dial changes how fast it strobes.
        val blinks = 6
        val slot = (c.progress.coerceIn(0f, 0.999f) * blinks * 2).toInt()
        if (slot % 2 == 0) c.drawArt()
    }
}

/** Slides in from the left, overshoots, settles, then leaves to the right. */
private object Bounce : MessageEffect {
    override val id = "bounce"
    override val label = "Bounce in"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val x = when {
            p < 0.35f -> {
                val t = springy(p / 0.35f)
                (-c.art.width + (c.baseX + c.art.width) * t).toInt()
            }
            p > 0.8f -> {
                val t = smooth((p - 0.8f) / 0.2f)
                (c.baseX + (c.canvas.width - c.baseX) * t).toInt()
            }
            else -> c.baseX
        }
        c.drawArt(x, c.baseY)
    }
}

/** Always there, with a band of extra brightness running through it. */
private object Ripple : MessageEffect {
    override val id = "ripple"
    override val label = "Ripple"
    override fun draw(c: MessageContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val edge = 1f - abs(phase(p, 0.12f, 0.12f))
        val span = c.art.width + 12f
        // Three passes of the band per showing.
        val head = ((p * 3f) % 1f) * span - 6f
        c.art.forEachLit { ax, ay ->
            val d = abs(ax - head)
            val lift = if (d < 5f) (1f - d / 5f) else 0f
            c.put(c.baseX + ax, c.baseY + ay, (0.45f + 0.55f * lift) * edge)
        }
    }
}

// ------------------------------------------------------------------ registry

/** The catalogue the settings and `/api/schema` read from. */
object MessageEffects {

    private val ScrollLeft: MessageEffect = Marquee("scroll-left", "Scroll left", -1)
    private val ScrollRight: MessageEffect = Marquee("scroll-right", "Scroll right", 1)
    private val ScrollUp: MessageEffect = Roll("scroll-up", "Roll up", up = true)
    private val ScrollDown: MessageEffect = Roll("scroll-down", "Roll down", up = false)

    val all: List<MessageEffect> = listOf(
        ScrollLeft, ScrollRight, ScrollUp, ScrollDown, Wave, Ticker,
        Cut, Fade, Pop, Zoom, Wipe, Scan, Blinds, Typewriter, Stack, Split,
        Dust, Shatter, MatrixResolve, Glitch, Flash, Bounce, Ripple
    )

    private val byId = all.associateBy { it.id }

    fun of(id: String?): MessageEffect = byId[id] ?: ScrollLeft

    /** `(id, label)` pairs for the picker. */
    fun options(): List<Pair<String, String>> = all.map { it.id to it.label }
}
