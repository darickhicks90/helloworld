package app.foxbox.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * A realistic eye that peers out of the grass in Clibbins: shaded sclera
 * with a few veins, a striated iris with a dark limbal ring, a pupil, wet
 * highlights, and skin lids with lashes that open, blink and close.
 * Drawn smoothly (not pixel art) so it looks uncannily real.
 */
object Eyeball {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val eye = Path(); private val skin = Path(); private val lid = Path()
    private val oval = RectF()
    private val IRISES = intArrayOf(0xFF5C7F3A.toInt(), 0xFF3F6E8C.toInt(), 0xFF7A5230.toInt(), 0xFF6E8C8A.toInt())

    /**
     * Draw an eye `w` wide centred at (cx, cy). `open` 0 = shut, 1 = wide open;
     * (lookX, lookY) in -1..1 nudge the iris; `variant` picks the iris colour.
     */
    fun draw(c: Canvas, cx: Float, cy: Float, w: Float, open: Float, lookX: Float, lookY: Float, variant: Int, bloodshot: Boolean = false) {
        val h = w * 0.62f
        val hw = w / 2
        val o = open.coerceIn(0f, 1f)
        // ---- the skin around the eye: a soft almond with a crease above and shadow below
        skin.reset()
        skin.moveTo(cx - hw * 1.28f, cy)
        skin.quadTo(cx, cy - h * 1.25f, cx + hw * 1.28f, cy)
        skin.quadTo(cx, cy + h * 1.0f, cx - hw * 1.28f, cy)
        skin.close()
        // a poked eye: the skin round it is sore and red
        val skinIn = if (bloodshot) 0xFFE89A8A.toInt() else 0xFFE2B49A.toInt(); val skinOut = if (bloodshot) 0xFFC8604E.toInt() else 0xFFC98F74.toInt()
        fill.shader = RadialGradient(cx, cy - h * 0.1f, hw * 1.3f, intArrayOf(skinIn, skinOut, 0x00B57F68), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(skin, fill)
        fill.shader = null
        line.color = 0x66704030; line.strokeWidth = maxOf(1f, w * 0.02f)
        lid.reset(); lid.moveTo(cx - hw * 0.95f, cy - h * 0.25f); lid.quadTo(cx, cy - h * 1.02f, cx + hw * 0.95f, cy - h * 0.25f)
        c.drawPath(lid, line) // crease

        // ---- the opening: upper lid lowers as the eye closes
        val upper = cy - h * 0.62f * o + h * 0.05f
        val lower = cy + h * 0.5f * maxOf(o, 0.25f)
        eye.reset()
        eye.moveTo(cx - hw, cy)
        eye.quadTo(cx - hw * 0.1f, upper - h * 0.25f * o, cx + hw, cy)
        eye.quadTo(cx + hw * 0.05f, lower, cx - hw, cy)
        eye.close()
        if (o > 0.04f) {
            c.save()
            c.clipPath(eye)
            // sclera, shaded towards the corners
            val sclera = if (bloodshot) intArrayOf(0xFFF6D6D0.toInt(), 0xFFEBA49C.toInt(), 0xFFB8443E.toInt()) else intArrayOf(0xFFFBF7F2.toInt(), 0xFFEFE2DA.toInt(), 0xFFC9A8A0.toInt())
            fill.shader = RadialGradient(cx, cy, hw, sclera, floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            oval.set(cx - hw, cy - h, cx + hw, cy + h); c.drawOval(oval, fill)
            fill.shader = null
            // a few veins creeping in from the corners
            line.color = if (bloodshot) 0xDDB01822.toInt() else 0x88C0404A.toInt(); line.strokeWidth = maxOf(0.8f, w * if (bloodshot) 0.014f else 0.008f)
            if (bloodshot) {
                // a red web of veins crawling in from every side, forking as they go
                for (k in 0 until 22) {
                    val a = k * 0.2856f + 0.3f
                    val sx = cx + cos(a) * hw * 1.02f; val sy = cy + sin(a) * h * 0.62f
                    val mx = cx + cos(a + 0.12f) * hw * 0.72f; val my = cy + sin(a + 0.12f) * h * 0.44f
                    val ex = cx + cos(a - 0.08f) * hw * (0.5f + (k % 4) * 0.04f); val ey = cy + sin(a - 0.08f) * h * 0.3f
                    lid.reset(); lid.moveTo(sx, sy); lid.quadTo(mx, my + (if (k % 2 == 0) -1 else 1) * h * 0.06f, ex, ey); c.drawPath(lid, line)
                    lid.reset(); lid.moveTo(mx, my); lid.lineTo(mx + cos(a + 1.1f) * hw * 0.12f, my + sin(a + 1.1f) * h * 0.1f); c.drawPath(lid, line)
                }
            }
            for (k in 0 until 6) {
                val side = if (k % 2 == 0) -1f else 1f
                val sy = cy + (k - 2.5f) * h * 0.08f
                lid.reset(); lid.moveTo(cx + side * hw * 0.98f, sy)
                lid.quadTo(cx + side * hw * 0.75f, sy + (if (k % 3 == 0) -1 else 1) * h * 0.12f, cx + side * hw * (0.5f + k * 0.03f), sy + h * 0.04f * (k - 2))
                c.drawPath(lid, line)
            }
            // iris
            val r = h * 0.44f
            val ix = cx + lookX * hw * 0.22f; val iy = cy + lookY * h * 0.12f
            val iris = IRISES[Math.floorMod(variant, IRISES.size)]
            fill.shader = RadialGradient(ix, iy, r, intArrayOf(0xFFC9A04A.toInt(), iris, dark(iris, 0.45f)), floatArrayOf(0.15f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(ix, iy, r, fill)
            fill.shader = null
            // fibres radiating from the pupil
            for (k in 0 until 56) {
                val a = k * (Math.PI * 2 / 56).toFloat()
                line.color = if (k % 3 == 0) 0x55FFF0C0 else 0x44000000; line.strokeWidth = maxOf(0.6f, w * 0.006f)
                c.drawLine(ix + cos(a) * r * 0.38f, iy + sin(a) * r * 0.38f, ix + cos(a + 0.05f) * r * 0.93f, iy + sin(a + 0.05f) * r * 0.93f, line)
            }
            // limbal ring and pupil
            line.color = 0xCC1A1410.toInt(); line.strokeWidth = maxOf(1.2f, r * 0.12f)
            c.drawCircle(ix, iy, r * 0.95f, line)
            fill.color = 0xFF0A0706.toInt(); c.drawCircle(ix, iy, r * 0.36f, fill)
            // the upper lid's shadow on the eyeball
            fill.shader = RadialGradient(cx, upper - h * 0.6f, h * 1.1f, intArrayOf(0x88301810.toInt(), 0x00301810), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            oval.set(cx - hw, cy - h, cx + hw, cy + h); c.drawOval(oval, fill)
            fill.shader = null
            // wet highlights
            fill.color = 0xEEFFFFFF.toInt(); c.drawCircle(ix - r * 0.35f, iy - r * 0.38f, r * 0.17f, fill)
            fill.color = 0x99FFFFFF.toInt(); c.drawCircle(ix + r * 0.32f, iy + r * 0.3f, r * 0.07f, fill)
            c.restore()
        }
        // ---- lid edges and lashes
        line.color = 0xFF2A1A14.toInt(); line.strokeWidth = maxOf(1.5f, w * 0.03f)
        lid.reset(); lid.moveTo(cx - hw, cy); lid.quadTo(cx - hw * 0.1f, upper - h * 0.25f * o, cx + hw, cy)
        c.drawPath(lid, line)
        line.color = 0xAA6E4030.toInt(); line.strokeWidth = maxOf(1f, w * 0.015f)
        lid.reset(); lid.moveTo(cx - hw, cy); lid.quadTo(cx + hw * 0.05f, lower, cx + hw, cy)
        c.drawPath(lid, line)
        line.color = 0xFF2A1A14.toInt(); line.strokeWidth = maxOf(1f, w * 0.012f)
        for (k in 1 until 10) {
            val t = k / 10f
            // a point on the upper lid curve and its outward lash
            val bx = (1 - t) * (1 - t) * (cx - hw) + 2 * (1 - t) * t * (cx - hw * 0.1f) + t * t * (cx + hw)
            val by = (1 - t) * (1 - t) * cy + 2 * (1 - t) * t * (upper - h * 0.25f * o) + t * t * cy
            val len = h * (0.16f + 0.08f * sin(t * 3.1f))
            c.drawLine(bx, by, bx + (t - 0.5f) * len * 0.9f, by - len, line)
        }
    }

    private fun dark(c: Int, k: Float): Int {
        val r = (c shr 16 and 255); val g = (c shr 8 and 255); val b = (c and 255)
        return (0xFF shl 24) or (((r * (1 - k)).toInt()) shl 16) or (((g * (1 - k)).toInt()) shl 8) or ((b * (1 - k)).toInt())
    }
}
