package com.example.ipixelclock.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.example.ipixelclock.render.PixelCanvas

/**
 * What the panel looks like, drawn as a panel.
 *
 * A nearest-neighbour blow-up of the frame buffer would be accurate and look
 * nothing like the real thing: an LED matrix reads as separate points of light
 * on a dark board, with the gaps between them doing as much work as the lit
 * pixels. So this draws round dots on a pitch, unlit ones included, and puts a
 * soft bloom under the bright ones. It is the same look the web preview's canvas
 * produces, so the two agree.
 */
class PanelPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10141C.toInt() }
    private val boardPaint = Paint().apply { color = 0xFF07090F.toInt() }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var frame: IntArray = IntArray(0)
    private var frameW = 0
    private var frameH = 0

    /** Aspect the view sizes itself to; follows the panel once one is known. */
    private var aspect = 96f / 16f

    /** Hand a rendered frame in. Safe from any thread. */
    fun submit(canvas: PixelCanvas) {
        val w = canvas.width
        val h = canvas.height
        if (w <= 0 || h <= 0) return
        synchronized(this) {
            if (frame.size != w * h) frame = IntArray(w * h)
            System.arraycopy(canvas.pixels, 0, frame, 0, w * h)
            frameW = w
            frameH = h
        }
        val a = w.toFloat() / h
        if (a != aspect) {
            aspect = a
            post { requestLayout() }
        }
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Full width, height from the panel's shape — but capped, because a
        // vertical banner is 1:9 the other way and would otherwise push every
        // control off the screen. Portrait gets the tighter cap; the dots are
        // centred within whatever is left.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val ideal = (w / aspect).toInt()
        val screen = resources.displayMetrics.heightPixels
        val maxH = (screen * if (aspect < 1f) 0.26f else 0.34f).toInt()
        setMeasuredDimension(w, ideal.coerceIn(48, maxH))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boardPaint)

        val w: Int
        val h: Int
        val px: IntArray
        synchronized(this) {
            w = frameW
            h = frameH
            if (w <= 0 || h <= 0) return
            px = frame
        }

        // Fit the matrix inside the view, centred, on a whole-pixel pitch.
        val pitch = minOf(width.toFloat() / w, height.toFloat() / h)
        val ox = (width - pitch * w) / 2f
        val oy = (height - pitch * h) / 2f
        val radius = (pitch * 0.38f).coerceAtLeast(0.6f)
        val bloom = pitch * 1.05f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val cx = ox + pitch * (x + 0.5f)
                val cy = oy + pitch * (y + 0.5f)

                if (r + g + b < 12) {
                    // Unlit LEDs are not black: they are dark plastic, and
                    // showing them is what makes the grid read as a grid.
                    canvas.drawCircle(cx, cy, radius, offPaint)
                    continue
                }

                // Bloom first, so the dot sits on top of its own glow.
                if (r + g + b > 120 && pitch > 3f) {
                    bloomPaint.shader = RadialGradient(
                        cx, cy, bloom,
                        Color.argb(90, r, g, b), Color.argb(0, r, g, b),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(cx, cy, bloom, bloomPaint)
                }

                dotPaint.color = Color.rgb(r, g, b)
                canvas.drawCircle(cx, cy, radius, dotPaint)
            }
        }
    }
}
