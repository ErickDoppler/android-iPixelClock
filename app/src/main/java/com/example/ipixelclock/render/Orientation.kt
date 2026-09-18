package com.example.ipixelclock.render

/**
 * How the panel is physically mounted.
 *
 * The panel's own framebuffer is always its physical WxH — a 96x16 module is
 * 96x16 to the firmware however it is screwed to the wall. Orientation is
 * therefore a transform applied at the very end, after the whole scene has been
 * composed.
 *
 * The important half is what happens *before* that: at 90 and 270 degrees the
 * scene is composed on a canvas with width and height swapped, so a 96x16 panel
 * hung **vertically** is drawn as a tall 16x96 scene. That is what makes a
 * vertical mount actually work — stacked digits, rain that falls down the long
 * axis, portrait backgrounds — rather than a landscape layout turned on its side
 * and squashed.
 */
enum class Rotation(val degrees: Int, val label: String) {
    DEG_0(0, "0°"),
    DEG_90(90, "90°"),
    DEG_180(180, "180°"),
    DEG_270(270, "270°");

    /** True when the logical canvas has width and height swapped. */
    val swapsAxes: Boolean get() = this == DEG_90 || this == DEG_270

    companion object {
        fun ofDegrees(d: Int): Rotation =
            entries.firstOrNull { it.degrees == ((d % 360) + 360) % 360 } ?: DEG_0
    }
}

/**
 * The full mounting description: a rotation plus the two mirror toggles, for
 * panels facing away from the viewer or seen in a reflection.
 */
data class Orientation(
    val rotation: Rotation = Rotation.DEG_0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false
) {

    /** Canvas width to compose at, given the panel's physical width/height. */
    fun logicalWidth(panelW: Int, panelH: Int): Int =
        if (rotation.swapsAxes) panelH else panelW

    /** Canvas height to compose at, given the panel's physical width/height. */
    fun logicalHeight(panelW: Int, panelH: Int): Int =
        if (rotation.swapsAxes) panelW else panelH

    val isIdentity: Boolean
        get() = rotation == Rotation.DEG_0 && !mirrorH && !mirrorV

    /**
     * Maps the logical scene in [src] onto the panel's physical buffer.
     *
     * [dst] must already be the panel's physical size; [src] must be the
     * logical size this orientation reports. Returns [dst].
     */
    fun apply(src: PixelCanvas, dst: PixelCanvas): PixelCanvas {
        if (isIdentity && src.width == dst.width && src.height == dst.height) {
            dst.copyFrom(src)
            return dst
        }
        val dw = dst.width
        val dh = dst.height
        for (dy in 0 until dh) {
            for (dx in 0 until dw) {
                // Undo the mirrors first, then the rotation, so a destination
                // pixel can name exactly one source pixel. Reading backwards
                // like this leaves no gaps, which a forward scatter would.
                var x = if (mirrorH) dw - 1 - dx else dx
                var y = if (mirrorV) dh - 1 - dy else dy
                val sx: Int
                val sy: Int
                when (rotation) {
                    Rotation.DEG_0 -> { sx = x; sy = y }
                    Rotation.DEG_90 -> { sx = y; sy = dw - 1 - x }
                    Rotation.DEG_180 -> { sx = dw - 1 - x; sy = dh - 1 - y }
                    Rotation.DEG_270 -> { sx = dh - 1 - y; sy = x }
                }
                dst.pixels[dy * dw + dx] =
                    if (sx in 0 until src.width && sy in 0 until src.height) {
                        src.pixels[sy * src.width + sx]
                    } else {
                        PixelCanvas.BLACK
                    }
            }
        }
        return dst
    }
}
