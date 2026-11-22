package app.fiveiy.kids

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import app.foxbox.ui.Pal
import app.foxbox.ui.Px
import app.foxbox.ui.Sprites
import app.foxbox.ui.Tv
import app.foxbox.ui.bands
import app.foxbox.ui.dot
import app.foxbox.ui.fill
import app.foxbox.ui.pixelBox
import app.foxbox.ui.sprite
import app.foxbox.ui.spriteCentered
import app.foxbox.ui.textCentered
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The XY pad as a little TV, the same pictures as 5 Eye Yeti's studio: the synth's windy sky,
 * the drums' dashboard at dusk, and the sampler's pond. The picture moves with the dot and
 * puffs, ripples or revs with every note.
 */
class TvScreen(private val kind: Int, private val inst: () -> Instrument) {
    private val u get() = Px.u
    private var lastHits = 0
    private fun pulse(): Boolean { val n = inst().hits; val hit = n != lastHits; lastHits = n; return hit }

    // ---- the glass (rebuilt when the size changes)
    private var gw = 0f; private var gh = 0f
    private val glass = Path(); private val bezel = Path(); private val glare = Path(); private val edge = Path()
    private val scanP = Paint().apply { color = 0x1A000000 }
    private val vignetteP = Paint(); private val glareP = Paint(Paint.ANTI_ALIAS_FLAG); private val rollP = Paint()
    private val bezelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tv.BEZEL }
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF3A332D.toInt() }
    private fun build(w: Float, h: Float) {
        gw = w; gh = h
        val inset = u * 1.5f; val rad = minOf(w, h) * 0.06f + u * 2
        glass.reset(); glass.addRoundRect(inset, inset, w - inset, h - inset, rad, rad, Path.Direction.CW)
        bezel.reset(); bezel.fillType = Path.FillType.EVEN_ODD
        bezel.addRect(0f, 0f, w, h, Path.Direction.CW); bezel.addRoundRect(inset, inset, w - inset, h - inset, rad, rad, Path.Direction.CW)
        edge.reset(); edge.addRoundRect(inset, inset, w - inset, h - inset, rad, rad, Path.Direction.CW)
        edgeP.strokeWidth = u * 0.5f
        vignetteP.shader = RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.72f, intArrayOf(0, 0, 0x30000000, 0x70000000), floatArrayOf(0f, 0.55f, 0.85f, 1f), Shader.TileMode.CLAMP)
        glare.reset(); glare.moveTo(inset, inset); glare.lineTo(w * 0.55f, inset); glare.quadTo(w * 0.3f, h * 0.12f, inset, h * 0.42f); glare.close()
        glareP.shader = LinearGradient(0f, 0f, w * 0.35f, h * 0.3f, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        rollP.shader = LinearGradient(0f, 0f, 0f, u * 10, intArrayOf(0x00FFFFFF, 0x0EFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }

    /** Draw into `r`. `x`, `y`: the dot, 0..1 (y up); `touching`: a finger is on it. */
    fun draw(c: Canvas, r: RectF, t: Float, x: Float, y: Float, touching: Boolean, overlay: (Canvas, Float, Float) -> Unit) {
        val w = r.width(); val h = r.height()
        if (w != gw || h != gh) build(w, h)
        c.save(); c.translate(r.left, r.top)
        c.save(); c.clipPath(glass)
        when (kind) { 0 -> wind(c, w, h, t, x, y, touching); 1 -> dashboard(c, w, h, t, x, y); else -> pond(c, w, h, t, x, y, touching) }
        overlay(c, w, h)
        // crosshair and cursor
        val mx = x * w; val my = (1 - y) * h
        var k = 0f
        while (k < h) { c.dot(mx, k, Pal.alpha(Pal.CREAM, 0.3f)); k += u * 3 }
        k = 0f
        while (k < w) { c.dot(k, my, Pal.alpha(Pal.CREAM, 0.3f)); k += u * 3 }
        marker(c, mx, my)
        // scanlines, dim corners, glare and a slow roll bar
        k = 0f
        while (k < h) { c.drawRect(0f, k, w, k + u * 0.5f, scanP); k += u * 1.5f }
        c.drawRect(0f, 0f, w, h, vignetteP)
        c.drawPath(glare, glareP)
        val roll = ((t * 0.08f) % 1f) * (h + u * 20) - u * 10
        c.save(); c.translate(0f, roll); c.drawRect(0f, 0f, w, u * 10, rollP); c.restore()
        c.restore()
        c.drawPath(bezel, bezelP); c.drawPath(edge, edgeP)
        c.restore()
    }

    private fun marker(c: Canvas, x: Float, y: Float) {
        when (kind) {
            0 -> c.spriteCentered(Sprites.cloud, x, y, u * 1.3f)
            2 -> c.spriteCentered(Sprites.drop, x, y, u * 1.6f)
            else -> {
                val g = u; val r = g * 4
                for (q in 0 until 4) { val sx = if (q and 1 == 0) -1f else 1f; val sy = if (q < 2) -1f else 1f
                    val cx = x + sx * r; val cy = y + sy * r
                    c.fill(minOf(cx, cx - sx * g * 2), cy - g / 2, maxOf(cx, cx - sx * g * 2), cy + g / 2, Pal.CREAM)
                    c.fill(cx - g / 2, minOf(cy, cy - sy * g * 2), cx + g / 2, maxOf(cy, cy - sy * g * 2), Pal.CREAM)
                }
                c.fill(x - g, y - g, x + g, y + g, Pal.ORANGE)
            }
        }
    }

    // ------------------------------------------------------------------ the synth: wind across the sky
    private val parts = Array(70) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), 0.5f + Random.nextFloat()) }
    private val clouds = Array(3) { floatArrayOf(Random.nextFloat(), 0.08f + Random.nextFloat() * 0.3f, 0.3f + Random.nextFloat() * 0.5f) }
    private var gust = 0f; private var gustY = 0.5f
    private fun wind(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float, touching: Boolean) {
        c.bands(0f, 0f, w, h, intArrayOf(Pal.AQUA_L, 0xFF9FDED7.toInt(), 0xFFB2E5DD.toInt(), 0xFFC6EBE2.toInt(), 0xFFDAF0E4.toInt(), Pal.CREAM))
        if (pulse()) { gust = 1f; gustY = 0.2f + Random.nextFloat() * 0.5f }
        gust *= 0.96f
        val speed = 0.002f + y * 0.006f + gust * 0.01f
        for (cl in clouds) {
            cl[0] += speed * 0.15f * cl[2]; if (cl[0] > 1.2f) cl[0] = -0.3f
            c.sprite(Sprites.cloud, cl[0] * w, cl[1] * h, u * 2)
        }
        for (p in parts) {
            p[1] += sin(p[0] * 9f + t * 1.3f) * 0.004f + cos(p[1] * 7f - t) * 0.003f
            if (touching) p[1] += ((1 - y) - p[1]) * 0.02f
            p[1] += (gustY - p[1]) * gust * 0.01f
            p[0] += speed * p[2] * (0.5f + x)
            if (p[0] > 1.05f) { p[0] = -0.05f; p[1] = Random.nextFloat() }
            val len = (2 + speed * 400 * p[2]).toInt().coerceAtMost(9)
            val col = if (gust > 0.3f && abs(p[1] - gustY) < 0.15f) Pal.ORANGE else Pal.CREAM
            for (k in 0 until len) c.dot(p[0] * w - k * u, p[1] * h, col)
        }
        var xx = 0f
        while (xx < w) { val y1 = h * 0.86f + sin(xx * 0.01f + 3) * h * 0.04f; c.fill(xx, (y1 / u).toInt() * u, xx + u, h, Pal.BROWN_L); xx += u }
    }

    // ------------------------------------------------------------------ the sampler: a pond
    private val ripples = ArrayList<FloatArray>()
    private var lastDrop = 0f
    private fun pond(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float, touching: Boolean) {
        val hz = h * 0.28f
        c.bands(0f, 0f, w, hz, intArrayOf(Pal.CREAM, 0xFFF7E7C9.toInt(), 0xFFF2CFA2.toInt(), Pal.ORANGE_L))
        c.bands(0f, hz, w, h, intArrayOf(Pal.AQUA, 0xFF37AAA1.toInt(), 0xFF2F9A93.toInt(), 0xFF28857F.toInt(), Pal.AQUA_D))
        if (pulse()) ripples += floatArrayOf(0.2f + Random.nextFloat() * 0.6f, 0.45f + Random.nextFloat() * 0.4f, 0f, 1f)
        if (touching && t - lastDrop > 0.15f) { ripples += floatArrayOf(x, 1 - y, 0f, 0.7f); lastDrop = t }
        for (k in 0 until 12) {
            val sx = ((k * 97 + t * 30 * (1 + k % 3)) % (w + 40)) - 20
            val sy = hz + u * (3 + (k * 7) % 30)
            c.fill((sx / u).toInt() * u, sy, (sx / u).toInt() * u + u * (2 + k % 3), sy + u, Pal.CREAM)
        }
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next(); r[2] += 0.016f
            if (r[2] > 1.6f) { it.remove(); continue }
            for (ring in 0 until 3) {
                val a = r[2] - ring * 0.15f
                if (a <= 0f) continue
                val rx = a * w * 0.25f * r[3]; val ry = rx * 0.3f
                val n = (rx / u).toInt().coerceIn(8, 80)
                for (i in 0 until n) { val ang = i * 6.283f / n; c.dot(r[0] * w + cos(ang) * rx, r[1] * h + sin(ang) * ry, if (ring == 0) Pal.CREAM else Pal.AQUA_L) }
            }
        }
        val sway = (x - 0.5f) * 10f
        for (side in 0..1) for (i in 0 until 5) {
            val bx = if (side == 0) u * (3 + i * 3) else w - u * (3 + i * 3)
            val top = h * (0.35f + (i % 3) * 0.08f)
            var yy = h
            while (yy > top) { c.dot(bx + sway * (1 - (yy - top) / (h - top)) * u, yy, Pal.BROWN); yy -= u }
            c.dot(bx + sway * u - u / 2, top, Pal.ORANGE_D, 2)
        }
    }

    // ------------------------------------------------------------------ the drums: a dashboard at dusk
    private var needle = 0f
    private val gaugeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val small = Px.text(8f, 0xFF2A221E.toInt(), headFont = true)
    private fun dashboard(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float) {
        val g = u
        c.bands(0f, 0f, w, h * 0.42f, intArrayOf(0xFF2E3550.toInt(), 0xFF51466A.toInt(), 0xFF8C5A6E.toInt(), 0xFFD9806A.toInt(), 0xFFF2B07A.toInt()))
        c.fill(0f, h * 0.36f, w, h * 0.42f, 0xFF3B3346.toInt())
        val vp = w * (0.35f + x * 0.3f)
        for (k in 0 until 6) {
            val ph = ((t * (0.6f + y)) + k / 6f) % 1f
            val yy = h * 0.37f + ph * h * 0.05f
            c.fill(vp - g * (1 + ph * 4), yy, vp + g * (1 + ph * 4), yy + g, Pal.alpha(0xFFF6E7B8.toInt(), ph))
        }
        c.fill(0f, h * 0.42f, w, h, 0xFF4A3F38.toInt())
        c.fill(0f, h * 0.42f, w, h * 0.42f + g * 2, 0xFF2A221E.toInt())
        for (yy in 0 until 6) c.fill(0f, h * 0.48f + yy * h * 0.09f, w, h * 0.48f + yy * h * 0.09f + g, 0xFF544840.toInt())
        if (pulse()) needle = 1f
        needle *= 0.94f
        gauge(c, w * 0.28f, h * 0.62f, h * 0.15f, 0.15f + y * 0.7f + needle * 0.15f, "RPM")
        gauge(c, w * 0.72f, h * 0.62f, h * 0.15f, 0.1f + x * 0.8f, "MPH")
        c.pixelBox(w * 0.38f, h * 0.80f, w * 0.62f, h * 0.93f, 0xFF2A221E.toInt(), border = 0xFF1A1512.toInt(), shade = false)
        c.fill(w * 0.40f, h * 0.83f, w * 0.60f, h * 0.88f, 0xFF7FD9A8.toInt())
        val bars = (needle * 8).toInt()
        for (k in 0 until bars) c.fill(w * 0.41f + k * g * 2, h * 0.87f - g * (1 + k % 3), w * 0.41f + k * g * 2 + g, h * 0.87f, 0xFF2A5A44.toInt())
    }
    private fun gauge(c: Canvas, cx: Float, cy: Float, r: Float, v: Float, label: String) {
        val g = u
        gaugeP.color = 0xFF1A1512.toInt(); c.drawCircle(cx, cy, r + g, gaugeP)
        gaugeP.color = 0xFFEDE3CC.toInt(); c.drawCircle(cx, cy, r, gaugeP)
        gaugeP.color = 0xFFF7EFDC.toInt(); c.drawCircle(cx, cy, r - g * 2, gaugeP)
        for (k in 0..8) { val a = Math.toRadians(-210.0 + 240.0 * k / 8); c.dot(cx + (cos(a) * r * 0.8f).toFloat(), cy + (sin(a) * r * 0.8f).toFloat(), 0xFF2A221E.toInt()) }
        val a = Math.toRadians(-210.0 + 240.0 * v.coerceIn(0f, 1f))
        var k = 0f
        while (k < r * 0.75f) { c.dot(cx + (cos(a) * k).toFloat(), cy + (sin(a) * k).toFloat(), 0xFFD8473A.toInt()); k += g * 0.7f }
        small.textSize = Px.dp(8f); c.textCentered(label, cx, cy + r * 0.45f, small)
    }
}
