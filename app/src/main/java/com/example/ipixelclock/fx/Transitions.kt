package com.example.ipixelclock.fx

import com.example.ipixelclock.font.PixelFont
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The digit-change effects.
 *
 * A note on why these are written against progress rather than as animations:
 * an iPixel panel delivers somewhere between 1 and 12 frames a second depending
 * on its generation, and the rate wanders. An effect that advanced its own state
 * per frame would run at a different speed on every panel and stutter whenever
 * one was dropped. Every effect here is a pure function of a 0..1 progress
 * value, so a 400 ms transition takes 400 ms whether that is forty frames or
 * four.
 */

private fun smooth(t: Float): Float {
    val p = t.coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

/** Nearest-neighbour sample of a mask scaled about its centre. */
private fun drawScaled(
    c: TransitionContext,
    m: PixelFont.GlyphMask,
    scale: Float,
    alpha: Float
) {
    if (alpha <= 0f || scale <= 0.01f) return
    val ox = c.centre(m) + m.width / 2f
    val oy = m.height / 2f
    val halfW = (m.width * scale / 2f).roundToInt() + 1
    val halfH = (m.height * scale / 2f).roundToInt() + 1
    for (dy in -halfH..halfH) {
        for (dx in -halfW..halfW) {
            val sx = (dx / scale + m.width / 2f).toInt()
            val sy = (dy / scale + m.height / 2f).toInt()
            if (m.at(sx, sy)) c.put((ox + dx).toInt(), (oy + dy).toInt(), alpha)
        }
    }
}

// ------------------------------------------------------------------ the plain

object Switch : DigitTransition {
    override val id = "switch"
    override val label = "Instant switch"
    override fun draw(c: TransitionContext) = c.drawMask(c.nextMask)
}

object Fade : DigitTransition {
    override val id = "fade"
    override val label = "Fade"
    override fun draw(c: TransitionContext) {
        val p = smooth(c.progress)
        c.prevMask?.let { c.drawMask(it, alpha = 1f - p) }
        c.drawMask(c.nextMask, alpha = p)
    }
}

// --------------------------------------------------------------- the scrolls

private class Scroll(
    override val id: String,
    override val label: String,
    private val dx: Int,
    private val dy: Int
) : DigitTransition {
    override fun draw(c: TransitionContext) {
        val p = smooth(c.progress)
        val spanX = c.cellW * dx
        val spanY = c.cellH * dy
        c.prevMask?.let {
            c.drawMask(it, (spanX * p).toInt(), (spanY * p).toInt())
        }
        c.drawMask(
            c.nextMask,
            (-spanX * (1f - p)).toInt(),
            (-spanY * (1f - p)).toInt()
        )
    }
}

object ScrollUp : DigitTransition by Scroll("scroll-up", "Scroll up", 0, -1)
object ScrollDown : DigitTransition by Scroll("scroll-down", "Scroll down", 0, 1)
object ScrollLeft : DigitTransition by Scroll("scroll-left", "Scroll left", -1, 0)
object ScrollRight : DigitTransition by Scroll("scroll-right", "Scroll right", 1, 0)

// -------------------------------------------------------------- the mechanics

/**
 * Split-flap. The leaf squashes to nothing about the middle carrying the old
 * digit, then opens out carrying the new one — which is exactly what the real
 * board does, seen edge-on.
 */
object Flip : DigitTransition {
    override val id = "flip"
    override val label = "Split-flap"
    override fun draw(c: TransitionContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val squash = abs(cos(p * Math.PI)).toFloat()
        val m = if (p < 0.5f) (c.prevMask ?: c.nextMask) else c.nextMask
        val mid = m.height / 2f
        for (gy in 0 until m.height) {
            val y = (mid + (gy - mid) * squash).toInt()
            for (gx in 0 until m.width) {
                if (m.at(gx, gy)) c.put(c.centre(m) + gx, y)
            }
        }
        // The hinge line, which is what sells it as a mechanism.
        if (squash < 0.35f) {
            for (x in 0 until c.cellW) c.put(x, mid.toInt(), 0.45f)
        }
    }
}

/**
 * Rolls through the intervening digits like an odometer, rather than cutting
 * straight from 3 to 4.
 */
object PageList : DigitTransition {
    override val id = "page-list"
    override val label = "Odometer roll"
    override fun draw(c: TransitionContext) {
        val from = c.prev
        if (from == null || from !in '0'..'9' || c.next !in '0'..'9') {
            Fade.draw(c)
            return
        }
        val a = from - '0'
        val b = c.next - '0'
        val steps = ((b - a + 10) % 10).coerceAtLeast(1)
        val pos = smooth(c.progress) * steps
        val step = pos.toInt().coerceAtMost(steps - 1)
        val frac = pos - step

        val cur = '0' + ((a + step) % 10)
        val nxt = '0' + ((a + step + 1) % 10)
        val h = c.cellH
        c.drawMask(c.mask(cur), dy = (-h * frac).toInt())
        c.drawMask(c.mask(nxt), dy = (h - h * frac).toInt())
    }
}

object Grow : DigitTransition {
    override val id = "grow"
    override val label = "Grow"
    override fun draw(c: TransitionContext) {
        val p = smooth(c.progress)
        c.prevMask?.let { drawScaled(c, it, 1f - p * 0.6f, 1f - p) }
        drawScaled(c, c.nextMask, 0.15f + 0.85f * p, p)
    }
}

object Shrink : DigitTransition {
    override val id = "shrink"
    override val label = "Shrink in"
    override fun draw(c: TransitionContext) {
        val p = smooth(c.progress)
        // Kept close to 1: drawing is clipped to the cell so its neighbours
        // stay clean, and a glyph that starts at twice the size arrives as a
        // cropped slab of lit pixels rather than as a digit shrinking.
        c.prevMask?.let { drawScaled(c, it, 1f + p * 0.35f, 1f - p) }
        drawScaled(c, c.nextMask, 1.45f - 0.45f * p, p)
    }
}

/** A quick hop out and back, with the new digit landing. */
object Jump : DigitTransition {
    override val id = "jump"
    override val label = "Jump"
    override fun draw(c: TransitionContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val hop = (c.cellH * 0.6f).toInt().coerceAtLeast(2)
        if (p < 0.5f) {
            val t = p * 2f
            c.prevMask?.let { c.drawMask(it, dy = (-hop * t).toInt(), alpha = 1f - t) }
        } else {
            val t = (p - 0.5f) * 2f
            c.drawMask(c.nextMask, dy = (hop * (1f - t)).toInt(), alpha = t)
        }
    }
}

// ------------------------------------------------------------- the dissolves

/**
 * The falling-code trace: each column resolves at its own moment, streaming
 * bright noise until it does. This is the one that earns the MATRIX font.
 */
object MatrixTrace : DigitTransition {
    override val id = "matrix-trace"
    override val label = "Matrix trace"
    override fun draw(c: TransitionContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val m = c.nextMask
        val ox = c.centre(m)
        for (gx in 0 until m.width) {
            // Columns resolve top-down, staggered, and never all at once.
            val resolveAt = 0.25f + 0.55f * c.noise(gx, 1)
            if (p >= resolveAt) {
                val settle = ((p - resolveAt) / 0.25f).coerceAtMost(1f)
                for (gy in 0 until m.height) {
                    if (m.at(gx, gy)) c.put(ox + gx, gy, settle)
                }
                continue
            }
            // Still running: a bright head with a short tail behind it.
            val head = ((p / resolveAt) * (m.height + 3)).toInt()
            for (t in 0..3) {
                val y = head - t
                if (y < 0 || y >= m.height) continue
                if (c.noise(gx, y * 7 + 3) < 0.25f) continue
                c.put(ox + gx, y, if (t == 0) 1f else 0.55f - t * 0.15f)
            }
        }
        // The outgoing digit fades under the rain rather than vanishing.
        c.prevMask?.let { c.drawMask(it, alpha = (1f - p * 2.2f).coerceAtLeast(0f) * 0.5f) }
    }
}

/** The new digit's pixels converge from scattered positions. */
object Assemble : DigitTransition {
    override val id = "assemble"
    override val label = "Assemble from dust"
    override fun draw(c: TransitionContext) {
        val p = smooth(c.progress)
        val m = c.nextMask
        val ox = c.centre(m)
        val spread = (1f - p) * max(c.cellW, c.cellH) * 0.8f
        var i = 0
        for (gy in 0 until m.height) {
            for (gx in 0 until m.width) {
                if (!m.at(gx, gy)) continue
                i++
                val ax = (c.noise(i, 11) - 0.5f) * 2f * spread
                val ay = (c.noise(i, 23) - 0.5f) * 2f * spread
                c.put(ox + gx + ax.toInt(), gy + ay.toInt(), p)
            }
        }
        c.prevMask?.let { c.drawMask(it, alpha = (1f - p * 1.8f).coerceAtLeast(0f)) }
    }
}

/** The old digit's pixels wink out one by one as the new one's arrive. */
object Dissolve : DigitTransition {
    override val id = "dissolve"
    override val label = "Dissolve"
    override fun draw(c: TransitionContext) {
        val p = c.progress.coerceIn(0f, 1f)
        c.prevMask?.let { m ->
            val ox = c.centre(m)
            for (gy in 0 until m.height) {
                for (gx in 0 until m.width) {
                    if (m.at(gx, gy) && c.noise(gx * 31 + gy, 5) > p) c.put(ox + gx, gy)
                }
            }
        }
        val m = c.nextMask
        val ox = c.centre(m)
        for (gy in 0 until m.height) {
            for (gx in 0 until m.width) {
                if (m.at(gx, gy) && c.noise(gx * 17 + gy, 9) < p) c.put(ox + gx, gy)
            }
        }
    }
}

/** The old digit's pixels fall away and the new one fades up behind them. */
object Shatter : DigitTransition {
    override val id = "shatter"
    override val label = "Shatter"
    override fun draw(c: TransitionContext) {
        val p = c.progress.coerceIn(0f, 1f)
        c.prevMask?.let { m ->
            val ox = c.centre(m)
            var i = 0
            for (gy in 0 until m.height) {
                for (gx in 0 until m.width) {
                    if (!m.at(gx, gy)) continue
                    i++
                    val drift = (c.noise(i, 3) - 0.5f) * 4f * p
                    // Gravity: displacement goes as the square of elapsed time.
                    val fall = p * p * c.cellH * 1.6f * (0.6f + c.noise(i, 4))
                    c.put(ox + gx + drift.toInt(), gy + fall.toInt(), 1f - p)
                }
            }
        }
        c.drawMask(c.nextMask, alpha = smooth((p - 0.35f) / 0.65f))
    }
}

// ----------------------------------------------------------------- the reveals

object Typewriter : DigitTransition {
    override val id = "typewriter"
    override val label = "Typewriter"
    override fun draw(c: TransitionContext) {
        val p = smooth(c.progress)
        val m = c.nextMask
        val ox = c.centre(m)
        val upto = (m.width * p).roundToInt()
        c.prevMask?.let { c.drawMask(it, alpha = (1f - p * 2f).coerceAtLeast(0f)) }
        for (gy in 0 until m.height) {
            for (gx in 0 until upto.coerceAtMost(m.width)) {
                if (m.at(gx, gy)) c.put(ox + gx, gy)
            }
        }
        // The cursor, riding the reveal.
        if (p < 1f && upto < m.width) {
            for (y in 0 until m.height) c.put(ox + upto, y, 0.35f)
        }
    }
}

object Wipe : DigitTransition {
    override val id = "wipe"
    override val label = "Wipe"
    override fun draw(c: TransitionContext) {
        val p = smooth(c.progress)
        val line = (c.cellH * p).roundToInt()
        c.prevMask?.let { m ->
            val ox = c.centre(m)
            for (gy in line until m.height) {
                for (gx in 0 until m.width) if (m.at(gx, gy)) c.put(ox + gx, gy)
            }
        }
        val m = c.nextMask
        val ox = c.centre(m)
        for (gy in 0 until min(line, m.height)) {
            for (gx in 0 until m.width) if (m.at(gx, gy)) c.put(ox + gx, gy)
        }
        if (p in 0.02f..0.98f) {
            for (x in 0 until c.cellW) c.put(x, line, 0.5f)
        }
    }
}

object Ripple : DigitTransition {
    override val id = "ripple"
    override val label = "Ripple"
    override fun draw(c: TransitionContext) {
        val p = smooth(c.progress)
        val m = c.nextMask
        val ox = c.centre(m)
        val cx = m.width / 2f
        val cy = m.height / 2f
        val maxR = sqrt(cx * cx + cy * cy)
        val front = p * maxR * 1.25f
        c.prevMask?.let { pm ->
            val pox = c.centre(pm)
            for (gy in 0 until pm.height) {
                for (gx in 0 until pm.width) {
                    if (!pm.at(gx, gy)) continue
                    val d = sqrt((gx - cx) * (gx - cx) + (gy - cy) * (gy - cy))
                    if (d > front) c.put(pox + gx, gy)
                }
            }
        }
        for (gy in 0 until m.height) {
            for (gx in 0 until m.width) {
                if (!m.at(gx, gy)) continue
                val d = sqrt((gx - cx) * (gx - cx) + (gy - cy) * (gy - cy))
                if (d <= front) c.put(ox + gx, gy)
            }
        }
    }
}

// ----------------------------------------------------------------- the noisy

/** Bright flash on arrival, decaying back to the face colour. */
object BurnIn : DigitTransition {
    override val id = "burn-in"
    override val label = "Burn in"
    override fun draw(c: TransitionContext) {
        val p = smooth(c.progress)
        c.prevMask?.let { c.drawMask(it, alpha = (1f - p * 3f).coerceAtLeast(0f)) }
        val m = c.nextMask
        val ox = c.centre(m)
        val heat = (1f - p).coerceIn(0f, 1f)
        val white = 0xFFFFFFFF.toInt()
        for (gy in 0 until m.height) {
            for (gx in 0 until m.width) {
                if (!m.at(gx, gy)) continue
                c.put(ox + gx, gy, min(1f, p * 2f))
                if (heat > 0f) c.putColor(ox + gx, gy, white, heat)
            }
        }
    }
}

/** Sliced rows, offset, with the channels pulled apart. */
object Glitch : DigitTransition {
    override val id = "glitch"
    override val label = "Glitch"
    override fun draw(c: TransitionContext) {
        val p = c.progress.coerceIn(0f, 1f)
        val m = if (p < 0.55f && c.prevMask != null) c.prevMask!! else c.nextMask
        val ox = c.centre(m)
        // Violence peaks in the middle of the change and settles at both ends.
        val chaos = (1f - abs(p - 0.5f) * 2f)
        val band = (3 + (c.noise(0, 1) * 3).toInt())
        for (gy in 0 until m.height) {
            val slice = gy / band
            val shift = ((c.noise(slice, (p * 6).toInt()) - 0.5f) * 8f * chaos).toInt()
            for (gx in 0 until m.width) {
                if (!m.at(gx, gy)) continue
                val x = ox + gx + shift
                if (chaos > 0.25f) {
                    // Channel separation: the classic broken-signal tell.
                    c.putColor(x - 1, gy, 0xFFFF0040.toInt(), 0.55f * chaos)
                    c.putColor(x + 1, gy, 0xFF00FFE0.toInt(), 0.55f * chaos)
                }
                c.put(x, gy, 1f - 0.3f * chaos)
            }
        }
    }
}

/** Picks a different effect for every change. */
object RandomPick : DigitTransition {
    override val id = "random"
    override val label = "Random each time"

    private val pool: List<DigitTransition> by lazy {
        Transitions.all.filter { it !== RandomPick && it !== Switch }
    }

    override fun draw(c: TransitionContext) {
        val pick = pool[(c.noise(0, 0) * pool.size).toInt().coerceIn(0, pool.size - 1)]
        pick.draw(c)
    }
}
