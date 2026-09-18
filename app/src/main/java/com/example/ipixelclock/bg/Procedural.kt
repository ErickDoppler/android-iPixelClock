package com.example.ipixelclock.bg

import com.example.ipixelclock.render.PixelCanvas
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The generated backgrounds.
 *
 * A panel is 2304 pixels at 144x16, so nothing here needs to be clever about
 * cost — a per-pixel loop over the whole canvas is nothing. What they do need to
 * be careful about is *shape*: an effect designed for a 16:9 screen reads as
 * noise on a strip sixteen pixels tall, and as something else again on a
 * vertical banner. Each one asks the canvas for its own proportions and adapts.
 *
 * The other constraint is that the clock has to stay readable on top. These are
 * deliberately dim: the intensity slider tops out below full brightness, and
 * most effects sit in the darker half of their palette.
 */

// ------------------------------------------------------------------ the plain

object Off : Background {
    override val id = "off"
    override val label = "None (black)"
    override fun draw(ctx: BgContext) {}
}

object Solid : Background {
    override val id = "solid"
    override val label = "Solid colour"
    override fun draw(ctx: BgContext) {
        ctx.canvas.clear(dim(ctx.primary, 0.25f + ctx.intensity * 0.75f))
    }
}

object Gradient : Background {
    override val id = "gradient"
    override val label = "Gradient"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        // Runs along the longer axis, so a strip gets a horizontal sweep and a
        // banner a vertical one without anyone having to set an angle.
        val horizontal = c.width >= c.height
        val span = (if (horizontal) c.width else c.height).coerceAtLeast(1)
        val k = 0.25f + ctx.intensity * 0.75f
        for (y in 0 until c.height) {
            for (x in 0 until c.width) {
                val t = (if (horizontal) x else y).toFloat() / span
                c.set(x, y, dim(PixelCanvas.lerp(ctx.primary, ctx.secondary, t), k))
            }
        }
    }
}

// ------------------------------------------------------------------ the rain

/**
 * Falling code. Each column has its own head position and speed; the head is
 * near-white, the tail fades to black over a handful of rows.
 */
object MatrixRain : Background {
    override val id = "matrix-rain"
    override val label = "Matrix rain"

    private var head = FloatArray(0)
    private var rate = FloatArray(0)
    private var len = IntArray(0)

    override fun reset(width: Int, height: Int) {
        head = FloatArray(width)
        rate = FloatArray(width)
        len = IntArray(width)
        for (x in 0 until width) respawn(x, height, seeded = true)
    }

    private fun respawn(x: Int, height: Int, seeded: Boolean = false) {
        head[x] = if (seeded) -(rnd(x, 1) * height * 2f) else -(rnd(x, head[x].toInt()) * 6f)
        rate[x] = 6f + rnd(x, 2) * 14f
        len[x] = (height * (0.35f + rnd(x, 3) * 0.6f)).toInt().coerceAtLeast(3)
    }

    override fun update(ctx: BgContext) {
        val dt = ctx.step(1f)
        for (x in 0 until ctx.width) {
            head[x] += rate[x] * dt
            if (head[x] - len[x] > ctx.height) respawn(x, ctx.height)
        }
    }

    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        // Density: below full intensity some columns simply never light.
        val density = 0.25f + ctx.intensity * 0.75f
        for (x in 0 until c.width) {
            if (rnd(x, 7) > density) continue
            val h = head[x]
            for (i in 0 until len[x]) {
                val y = (h - i).toInt()
                if (y < 0 || y >= c.height) continue
                val f = 1f - i.toFloat() / len[x]
                val v = (f * f * 255).toInt()
                val col = when (i) {
                    0 -> PixelCanvas.argb(255, 200, 255, 220)   // the bright head
                    1 -> PixelCanvas.argb(255, 120, 255, 160)
                    else -> PixelCanvas.argb(255, 0, v, (v * 0.35f).toInt())
                }
                c.set(x, y, dim(col, 0.35f + ctx.intensity * 0.65f))
            }
        }
    }
}

// -------------------------------------------------------------- the starfields

object Starfield : Background {
    override val id = "starfield"
    override val label = "Stars, twinkling"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val count = (c.width * c.height * (0.02f + ctx.intensity * 0.10f)).toInt().coerceAtLeast(4)
        val t = ctx.time(1.4f)
        for (i in 0 until count) {
            val x = (rnd(i, 11) * c.width).toInt().coerceIn(0, c.width - 1)
            val y = (rnd(i, 13) * c.height).toInt().coerceIn(0, c.height - 1)
            // Each star twinkles on its own phase, so they never pulse together.
            val phase = rnd(i, 17) * 6.283f
            val v = (0.35f + 0.65f * (0.5f + 0.5f * sin(t + phase)))
            val lvl = (v * 255 * (0.4f + ctx.intensity * 0.6f)).toInt()
            c.set(x, y, PixelCanvas.argb(255, lvl, lvl, (lvl * 1.0f).toInt().coerceAtMost(255)))
        }
    }
}

/** Stars streaking outward from the centre — the jump to lightspeed. */
object StarFlight : Background {
    override val id = "star-flight"
    override val label = "Star flight"

    private var z = FloatArray(0)
    private var n = 0

    override fun reset(width: Int, height: Int) {
        n = ((width * height) / 8).coerceIn(40, 300)
        // Spread the starting depths evenly instead of randomly, so the field is
        // populated at every distance from the first frame rather than clumping.
        z = FloatArray(n) { 0.06f + (it.toFloat() / n) * 0.94f }
    }

    override fun update(ctx: BgContext) {
        val dt = ctx.step(0.5f)
        for (i in 0 until n) {
            z[i] -= dt
            if (z[i] <= 0.05f) z[i] = 1f
        }
    }

    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val cx = c.width / 2f
        val cy = c.height / 2f
        val active = (n * (0.35f + ctx.intensity * 0.65f)).toInt()
        val k = 0.45f + ctx.intensity * 0.55f
        for (i in 0 until active) {
            // Direction is fixed per star; only the depth changes, which is what
            // makes them travel straight outward rather than wander. Biased along
            // x, because on a wide strip a symmetric spread sends most of them
            // off the top and bottom edges immediately.
            val ax = (rnd(i, 19) - 0.5f) * 2.4f
            val ay = (rnd(i, 23) - 0.5f) * 0.7f
            val x = cx + ax / z[i] * cx * 0.5f
            val y = cy + ay / z[i] * cy
            if (x < 0 || y < 0 || x >= c.width || y >= c.height) continue
            val v = ((1f - z[i]) * 255 * k).toInt().coerceIn(0, 255)
            if (v < 8) continue
            // The trail, which is what reads as speed at this size.
            val zb = (z[i] + 0.10f)
            val px = cx + ax / zb * cx * 0.5f
            val py = cy + ay / zb * cy
            c.line(px.toInt(), py.toInt(), x.toInt(), y.toInt(),
                PixelCanvas.argb(255, (v * 0.45f).toInt(), (v * 0.45f).toInt(), (v * 0.6f).toInt()))
            c.set(x.toInt(), y.toInt(), PixelCanvas.argb(255, v, v, (v + 40).coerceAtMost(255)))
        }
    }
}

// ------------------------------------------------------------- the travelling

/**
 * A road running to a vanishing point, with the surface and a dashed centre
 * line scrolling toward the viewer.
 *
 * Drawn by filling the road *surface* rather than plotting two edge pixels. On
 * a strip sixteen rows tall the edges alone land on columns 0 and 143 and the
 * effect is invisible, which is what the first version did.
 */
object RollingRoad : Background {
    override val id = "rolling-road"
    override val label = "Rolling road"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val horizon = c.height * 0.28f
        val t = ctx.time(0.9f)
        val cx = c.width / 2f
        val k = 0.3f + ctx.intensity * 0.7f

        for (y in 0 until c.height) {
            val d = (y - horizon) / (c.height - horizon).coerceAtLeast(1f)
            if (d <= 0.015f) continue
            // Half-width grows from nothing at the horizon to most of the panel
            // at the bottom edge.
            val half = (c.width * 0.08f + c.width * 0.42f * d)
            val left = (cx - half).toInt().coerceAtLeast(0)
            val right = (cx + half).toInt().coerceAtMost(c.width - 1)

            // The surface, darkening toward the horizon.
            val road = (34 * d * k).toInt().coerceIn(0, 255)
            for (x in left..right) {
                c.set(x, y, PixelCanvas.argb(255, road, road, (road * 1.25f).toInt().coerceAtMost(255)))
            }

            // 1/d is the perspective: bands bunch up toward the horizon.
            val phase = (t + 1f / (d * 1.8f)) % 1f
            val v = (255 * d * k).toInt().coerceIn(0, 255)

            // Verges.
            c.set(left, y, PixelCanvas.argb(255, v, (v * 0.5f).toInt(), 0))
            c.set(right, y, PixelCanvas.argb(255, v, (v * 0.5f).toInt(), 0))

            // Dashed centre line.
            if (phase < 0.45f) {
                val wLine = (1 + (half * 0.06f)).toInt()
                for (dx in -wLine..wLine) {
                    c.set((cx + dx).toInt(), y, PixelCanvas.argb(255, v, v, (v * 0.7f).toInt()))
                }
            }
        }
    }
}

object SeaWaves : Background {
    override val id = "sea-waves"
    override val label = "Sea waves"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val t = ctx.time(1.1f)
        val amp = c.height * (0.15f + ctx.intensity * 0.3f)
        val mid = c.height * 0.6f
        for (x in 0 until c.width) {
            // Three summed sines: one wave looks mechanical, three look like water.
            val s = sin(x * 0.18f + t) + 0.5f * sin(x * 0.37f - t * 1.7f) +
                0.25f * sin(x * 0.07f + t * 0.6f)
            val surface = mid + s * amp * 0.4f
            for (y in surface.toInt() until c.height) {
                if (y < 0) continue
                val depth = (y - surface) / (c.height - surface + 1f)
                val v = ((1f - depth) * 180 * (0.35f + ctx.intensity * 0.65f)).toInt()
                    .coerceIn(0, 255)
                c.set(x, y, PixelCanvas.argb(255, 0, (v * 0.5f).toInt(), v))
            }
            val sy = surface.toInt()
            if (sy in 0 until c.height) {
                c.set(x, sy, PixelCanvas.argb(255, 140, 220, 255))
            }
        }
    }
}

/** A tunnel of rings rushing past — the view from the cockpit. */
object Flight : Background {
    override val id = "flight"
    override val label = "Tunnel flight"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val cx = c.width / 2f
        val cy = c.height / 2f
        val t = ctx.time(0.7f)
        val rings = 9
        val k = 0.3f + ctx.intensity * 0.7f
        for (i in 0 until rings) {
            val z = ((t + i.toFloat() / rings) % 1f)
            // Near rings are bright and large; far ones dim and small.
            val rx = z * z * c.width * 0.75f
            val ry = z * z * c.height * 0.95f
            val v = ((1f - z) * 255 * k).toInt().coerceIn(0, 255)
            if (v < 6) continue
            val col = PixelCanvas.argb(255, (v * 0.35f).toInt(), (v * 0.85f).toInt(), v)
            // Ellipse, not a circle: a 144x16 strip needs the ring stretched to
            // its own proportions or it collapses to a vertical bar. Stepped by
            // arc length so the far side is not a dotted line.
            val steps = (6.283f * max(rx, ry)).toInt().coerceIn(24, 360)
            for (s in 0 until steps) {
                val a = s * 6.283f / steps
                c.set((cx + cos(a) * rx).toInt(), (cy + sin(a) * ry).toInt(), col)
            }
        }
    }
}

/**
 * Pipes that grow, turn and start again.
 *
 * Keeps its own trail buffer rather than fading the canvas. The renderer clears
 * the canvas at the top of every frame, so an effect that tried to accumulate
 * on it would draw a single pixel and nothing else — which is exactly what this
 * did before.
 */
object Pipes : Background {
    override val id = "pipes"
    override val label = "Pipes"

    private var trail = IntArray(0)
    private var x = 0
    private var y = 0
    private var dir = 0
    private var hue = 0f
    private var since = 0f
    private var w = 0
    private var h = 0

    override fun reset(width: Int, height: Int) {
        w = width; h = height
        trail = IntArray(width * height)
        x = width / 2; y = height / 2; dir = 0; hue = 0f; since = 0f
    }

    override fun update(ctx: BgContext) {
        if (w == 0 || h == 0) return
        // Fade the whole trail a little each frame, by real elapsed time so the
        // tail is the same length whatever the panel's rate.
        val keep = 1f - (ctx.dtMs / 1000f) * (0.5f + (1f - ctx.intensity) * 2.0f)
        val k = keep.coerceIn(0f, 1f)
        for (i in trail.indices) {
            val c = trail[i]
            if (c == 0) continue
            val r = (((c ushr 16) and 0xFF) * k).toInt()
            val g = (((c ushr 8) and 0xFF) * k).toInt()
            val b = ((c and 0xFF) * k).toInt()
            trail[i] = if (r + g + b < 6) 0 else PixelCanvas.argb(255, r, g, b)
        }

        since += ctx.step(22f)
        var steps = since.toInt().coerceAtMost(8)
        since -= steps
        while (steps-- > 0) advance()
    }

    private fun advance() {
        // Turn now and then; otherwise carry straight on. On a strip only
        // sixteen rows tall, turning too often just makes a scribble.
        if (rnd(x * 31 + y * 17, dir) < 0.14f) {
            dir = (dir + if (rnd(x, y) < 0.5f) 1 else 3) % 4
        }
        when (dir) {
            0 -> x++
            1 -> y++
            2 -> x--
            else -> y--
        }
        if (x < 0 || y < 0 || x >= w || y >= h) {
            x = x.coerceIn(0, w - 1)
            y = y.coerceIn(0, h - 1)
            dir = (dir + 2) % 4      // bounce off the edge rather than teleport
            hue = (hue + 47f) % 360f
        }
        trail[y * w + x] = PixelCanvas.hsv(hue, 0.85f, 1f)
    }

    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val k = 0.35f + ctx.intensity * 0.6f
        for (i in trail.indices) {
            val v = trail[i]
            if (v == 0) continue
            c.set(i % w, i / w, dim(v, k))
        }
    }
}

// ------------------------------------------------------------- the elemental

object Plasma : Background {
    override val id = "plasma"
    override val label = "Plasma"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val t = ctx.time(0.6f)
        for (y in 0 until c.height) {
            for (x in 0 until c.width) {
                val v = sin(x * 0.12f + t) +
                    sin(y * 0.22f - t * 0.8f) +
                    sin((x + y) * 0.09f + t * 1.3f) +
                    sin(sqrt((x * x + y * y).toFloat()) * 0.15f - t)
                val hue = (v + 4f) / 8f * 360f
                c.set(x, y, PixelCanvas.hsv(hue, 0.85f, 0.15f + ctx.intensity * 0.45f))
            }
        }
    }
}

object Fire : Background {
    override val id = "fire"
    override val label = "Fire"

    private var heat = IntArray(0)
    private var w = 0
    private var h = 0
    private var carry = 0f

    override fun reset(width: Int, height: Int) {
        w = width; h = height
        heat = IntArray(width * height)
        carry = 0f
    }

    override fun update(ctx: BgContext) {
        // A cellular simulation has to advance in whole steps, so accumulate
        // real time and take as many as it has earned. That keeps the flames
        // burning at the same speed whatever the panel manages.
        carry += ctx.step(26f)
        var steps = carry.toInt().coerceAtMost(4)
        carry -= steps
        while (steps-- > 0) burn(ctx)
    }

    private fun burn(ctx: BgContext) {
        if (w == 0 || h == 0) return
        // Seed the bottom row.
        for (x in 0 until w) {
            heat[(h - 1) * w + x] =
                if (rnd(x, (ctx.nowMs / 37).toInt()) < 0.72f) (180 + (rnd(x, 9) * 75).toInt()) else 0
        }
        // Propagate upward with a sideways wobble and a cooling term.
        for (y in 0 until h - 1) {
            for (x in 0 until w) {
                val below = (y + 1) * w + ((x + (rnd(x, y) * 3).toInt() - 1 + w) % w)
                val cool = (rnd(x * 7 + y, 3) * 42).toInt()
                heat[y * w + x] = (heat[below] - cool).coerceAtLeast(0)
            }
        }
    }

    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val k = 0.35f + ctx.intensity * 0.65f
        for (y in 0 until c.height) {
            for (x in 0 until c.width) {
                val v = heat[y * w + x]
                if (v <= 8) continue
                val r = (v * 1.2f).toInt().coerceAtMost(255)
                val g = (v - 90).coerceAtLeast(0).let { (it * 1.6f).toInt().coerceAtMost(255) }
                val b = (v - 200).coerceAtLeast(0).let { (it * 3f).toInt().coerceAtMost(255) }
                c.set(x, y, dim(PixelCanvas.argb(255, r, g, b), k))
            }
        }
    }
}

object Rain : Background {
    override val id = "rain"
    override val label = "Rain"

    private var y = FloatArray(0)
    private var sp = FloatArray(0)
    private var xs = IntArray(0)
    private var n = 0

    override fun reset(width: Int, height: Int) {
        n = (width / 3).coerceIn(6, 90)
        y = FloatArray(n) { rnd(it, 3) * height }
        sp = FloatArray(n) { 14f + rnd(it, 5) * 22f }
        xs = IntArray(n) { (rnd(it, 7) * width).toInt().coerceIn(0, width - 1) }
    }

    override fun update(ctx: BgContext) {
        val dt = ctx.step(1f)
        for (i in 0 until n) {
            y[i] += sp[i] * dt
            if (y[i] > ctx.height + 3) {
                y[i] = -2f
                xs[i] = (rnd(i, (ctx.nowMs / 101).toInt()) * ctx.width).toInt()
                    .coerceIn(0, ctx.width - 1)
            }
        }
    }

    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val active = (n * (0.25f + ctx.intensity * 0.75f)).toInt()
        val v = (120 + ctx.intensity * 110).toInt()
        for (i in 0 until active) {
            val top = y[i].toInt()
            for (d in 0..2) {
                val yy = top - d
                if (yy in 0 until c.height) {
                    val f = 1f - d * 0.32f
                    c.set(xs[i], yy, PixelCanvas.argb(255, (v * 0.45f * f).toInt(),
                        (v * 0.7f * f).toInt(), (v * f).toInt()))
                }
            }
        }
    }
}

object Snow : Background {
    override val id = "snow"
    override val label = "Snow"

    private var y = FloatArray(0)
    private var x = FloatArray(0)
    private var sp = FloatArray(0)
    private var n = 0

    override fun reset(width: Int, height: Int) {
        n = ((width * height) / 30).coerceIn(6, 90)
        y = FloatArray(n) { rnd(it, 3) * height }
        x = FloatArray(n) { rnd(it, 5) * width }
        sp = FloatArray(n) { 2.5f + rnd(it, 7) * 5f }
    }

    override fun update(ctx: BgContext) {
        val dt = ctx.step(1f)
        for (i in 0 until n) {
            y[i] += sp[i] * dt
            // Drift: a slow sine per flake, so they wander instead of dropping.
            x[i] += sin(ctx.t * 0.7f + i) * dt * 2.2f
            if (y[i] > ctx.height) { y[i] = -1f; x[i] = rnd(i, (ctx.nowMs / 97).toInt()) * ctx.width }
            if (x[i] < 0) x[i] += ctx.width
            if (x[i] >= ctx.width) x[i] -= ctx.width
        }
    }

    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val active = (n * (0.25f + ctx.intensity * 0.75f)).toInt()
        val v = (150 + ctx.intensity * 105).toInt()
        for (i in 0 until active) {
            c.set(x[i].toInt(), y[i].toInt(), PixelCanvas.argb(255, v, v, v))
        }
    }
}

// -------------------------------------------------------------- the graphical

object Scanlines : Background {
    override val id = "scanlines"
    override val label = "Scan bar"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val t = ctx.time(0.35f)
        val vertical = c.height >= c.width
        val span = (if (vertical) c.height else c.width)
        val pos = ((t % 1f) * span)
        val tail = (span * (0.12f + ctx.intensity * 0.3f)).coerceAtLeast(2f)
        for (i in 0 until span) {
            var d = pos - i
            if (d < 0) d += span
            if (d > tail) continue
            val f = (1f - d / tail)
            val col = dim(PixelCanvas.lerp(ctx.secondary, ctx.primary, f), f * f)
            if (vertical) c.hline(i, 0, c.width - 1, col) else c.vline(i, 0, c.height - 1, col)
        }
    }
}

object Aurora : Background {
    override val id = "aurora"
    override val label = "Aurora"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val t = ctx.time(0.35f)
        for (x in 0 until c.width) {
            val band = sin(x * 0.07f + t) + 0.5f * sin(x * 0.13f - t * 0.7f)
            val centre = c.height * (0.45f + band * 0.18f)
            val thick = c.height * (0.2f + ctx.intensity * 0.35f)
            for (y in 0 until c.height) {
                val d = abs(y - centre) / thick
                if (d > 1f) continue
                val f = (1f - d) * (1f - d)
                val hue = 120f + sin(x * 0.05f + t * 0.5f) * 60f
                c.set(x, y, PixelCanvas.hsv(hue, 0.8f, f * (0.25f + ctx.intensity * 0.5f)))
            }
        }
    }
}

object Equalizer : Background {
    override val id = "equalizer"
    override val label = "Equalizer"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val t = ctx.time(1.6f)
        val barW = max(2, c.width / 24)
        var x = 0
        var b = 0
        while (x < c.width) {
            // No microphone: the bars are summed sines at unrelated rates, which
            // is enough to read as music from across a room.
            val v = (0.5f + 0.5f * sin(t + b * 1.7f)) * 0.6f +
                (0.5f + 0.5f * sin(t * 1.9f + b * 0.6f)) * 0.4f
            val hgt = (v * c.height * (0.35f + ctx.intensity * 0.65f)).toInt()
            for (y in c.height - hgt until c.height) {
                if (y < 0) continue
                val f = (c.height - y).toFloat() / c.height.coerceAtLeast(1)
                c.set(x.coerceAtMost(c.width - 1), y,
                    PixelCanvas.hsv(120f - f * 120f, 0.9f, 0.3f + ctx.intensity * 0.45f))
                for (d in 1 until barW) {
                    if (x + d < c.width) {
                        c.set(x + d, y, PixelCanvas.hsv(120f - f * 120f, 0.9f,
                            0.3f + ctx.intensity * 0.45f))
                    }
                }
            }
            x += barW + 1
            b++
        }
    }
}

/** Conway, seeded afresh whenever it stalls. */
object Life : Background {
    override val id = "life"
    override val label = "Game of life"

    private var cur = BooleanArray(0)
    private var next = BooleanArray(0)
    private var w = 0
    private var h = 0
    private var carry = 0f
    private var stale = 0

    override fun reset(width: Int, height: Int) {
        w = width; h = height
        cur = BooleanArray(w * h); next = BooleanArray(w * h)
        seed()
    }

    private fun seed() {
        for (i in cur.indices) cur[i] = rnd(i, (System.nanoTime() and 0xFFFF).toInt()) < 0.32f
        stale = 0
    }

    override fun update(ctx: BgContext) {
        carry += ctx.step(7f)
        var steps = carry.toInt().coerceAtMost(3)
        carry -= steps
        while (steps-- > 0) tick()
    }

    private fun tick() {
        if (w == 0 || h == 0) return
        var changed = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    // Wraps at the edges: on a strip sixteen rows tall a bounded
                    // board dies almost immediately.
                    val nx = (x + dx + w) % w
                    val ny = (y + dy + h) % h
                    if (cur[ny * w + nx]) n++
                }
                val alive = cur[y * w + x]
                val born = if (alive) (n == 2 || n == 3) else n == 3
                next[y * w + x] = born
                if (born != alive) changed++
            }
        }
        System.arraycopy(next, 0, cur, 0, cur.size)
        if (changed < cur.size / 200) stale++ else stale = 0
        if (stale > 12) seed()
    }

    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val col = dim(ctx.primary, 0.3f + ctx.intensity * 0.6f)
        for (y in 0 until c.height) {
            for (x in 0 until c.width) {
                if (cur[y * w + x]) c.set(x, y, col)
            }
        }
    }
}

/** Traces on a board, lighting up as current finds a path. */
object Circuit : Background {
    override val id = "circuit"
    override val label = "Circuit traces"
    override fun draw(ctx: BgContext) {
        val c = ctx.canvas
        val t = ctx.time(0.5f)
        val pitch = max(3, c.height / 4)
        val faint = dim(ctx.primary, 0.12f)
        // The static board: a lattice of traces with occasional pads.
        for (y in 0 until c.height) {
            for (x in 0 until c.width) {
                val onTrace = (x % pitch == 0) || (y % pitch == 0)
                if (onTrace) c.set(x, y, faint)
            }
        }
        // The pulse: a wavefront sweeping across, brightening what it touches.
        val front = ((t % 1f) * (c.width + c.height))
        for (y in 0 until c.height) {
            for (x in 0 until c.width) {
                if (!((x % pitch == 0) || (y % pitch == 0))) continue
                val d = abs((x + y) - front)
                if (d > 6f) continue
                val f = 1f - d / 6f
                c.set(x, y, dim(PixelCanvas.lerp(ctx.primary, ctx.secondary, f),
                    f * (0.35f + ctx.intensity * 0.65f)))
            }
        }
    }
}

// ---------------------------------------------------------------- the helpers

/** Scales a colour's RGB, keeping it opaque. Used to hold every effect below
 *  full brightness so the clock stays readable on top of it. */
private fun dim(color: Int, k: Float): Int {
    val f = k.coerceIn(0f, 1f)
    return PixelCanvas.argb(
        255,
        (((color ushr 16) and 0xFF) * f).toInt(),
        (((color ushr 8) and 0xFF) * f).toInt(),
        ((color and 0xFF) * f).toInt()
    )
}

/**
 * A stable pseudo-random 0..1 from two ints.
 *
 * Deterministic on purpose: an effect that wants a star in the same place every
 * frame gets it without storing an array, and one that wants a fresh value asks
 * with a changing second argument.
 */
private fun rnd(a: Int, b: Int): Float {
    var h = a * -0x61c88647
    h = h xor (b + -0x61c88647 + (h shl 6) + (h ushr 2))
    h = h xor (h ushr 15)
    h *= -0x7a143589
    h = h xor (h ushr 13)
    return ((h ushr 8) and 0xFFFF) / 65535f
}
