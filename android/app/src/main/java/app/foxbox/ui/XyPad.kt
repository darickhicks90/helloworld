package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The XY pad on the left of each engine page. It plays the role of MPE
 * expression (or Teeth's effect control) and shows a themed animation that
 * reacts to your finger and to the notes being played.
 */
@SuppressLint("ViewConstructor")
class XyPad(c: Context, val engine: Engine) : PixelView(c) {
    private val theme: Theme = when (engine.id) {
        "gusts" -> Wind(); "reflections" -> Pond(); "leaves" -> Branch(); else -> Jaw()
    }
    private val label = Px.text(11f, Pal.CREAM, align = Paint.Align.LEFT)
    private var pointer = -1
    private var t0 = System.nanoTime()

    init { animated = true }

    override fun paint(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val time = (System.nanoTime() - t0) / 1e9f
        c.save(); c.clipRect(u, u, w - u, h - u)
        theme.draw(c, w, h, time)
        // Crosshair + marker
        val mx = engine.x * w; val my = (1 - engine.y) * h
        var k = 0f
        while (k < h) { c.dot(mx, k, Pal.alpha(Pal.BROWN_D, 0.35f)); k += u * 3 }
        k = 0f
        while (k < w) { c.dot(k, my, Pal.alpha(Pal.BROWN_D, 0.35f)); k += u * 3 }
        theme.marker(c, mx, my, time)
        c.restore()
        c.pixelBox(0f, 0f, w, h, 0, shade = false)
        tag(c, "Y  ${engine.yName}", u * 3, u * 3)
        tag(c, "X  ${engine.xName}", u * 3, h - u * 3 - Px.dp(18f))
    }

    private fun tag(c: Canvas, s: String, x: Float, y: Float) {
        if (s.isBlank()) return
        c.fill(x, y, x + label.measureText(s) + Px.dp(10f), y + Px.dp(18f), Pal.alpha(Pal.BROWN_D, 0.8f))
        c.drawText(s, x + Px.dp(5f), y + Px.dp(13f), label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (pointer < 0) {
                pointer = e.getPointerId(i); parent?.requestDisallowInterceptTouchEvent(true); move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> { val p = e.findPointerIndex(pointer); if (p >= 0) move(e.getX(p), e.getY(p)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(i) == pointer) {
                    pointer = -1
                    Studio.setXy(engine, engine.springX ?: engine.x, engine.springY ?: engine.y, false)
                }
        }
        return true
    }

    private fun move(px: Float, py: Float) {
        val x = (px / width).coerceIn(0f, 1f); val y = (1 - py / height).coerceIn(0f, 1f)
        Studio.setXy(engine, x, y, true)
        theme.touch(px, py)
    }

    private abstract class Theme {
        abstract fun draw(c: Canvas, w: Float, h: Float, t: Float)
        open fun marker(c: Canvas, x: Float, y: Float, t: Float) = c.spriteCentered(Sprites.flower, x, y, Px.u * 2)
        open fun touch(x: Float, y: Float) {}
    }

    private fun notePulse(lastCount: IntArray): Boolean {
        val n = engine.noteCount
        val hit = n != lastCount[0]; lastCount[0] = n; return hit
    }

    // ------------------------------------------------------------------ Gusts: wind
    private inner class Wind : Theme() {
        val parts = Array(80) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), 0.5f + Random.nextFloat()) }
        val clouds = Array(3) { floatArrayOf(Random.nextFloat(), 0.08f + Random.nextFloat() * 0.3f, 0.3f + Random.nextFloat() * 0.5f) }
        val last = IntArray(1); var gust = 0f; var gustY = 0.5f
        override fun draw(c: Canvas, w: Float, h: Float, t: Float) {
            c.bands(0f, 0f, w, h, intArrayOf(Pal.AQUA_L, 0xFF9FDED7.toInt(), 0xFFB2E5DD.toInt(), 0xFFC6EBE2.toInt(), 0xFFDAF0E4.toInt(), Pal.CREAM))
            if (notePulse(last)) { gust = 1f; gustY = 1f - ((engine.lastNote - 36) / 60f).coerceIn(0f, 1f) }
            gust *= 0.96f
            val speed = 0.002f + engine.activeVoices * 0.0015f + engine.y * 0.01f + gust * 0.01f
            clouds.forEach { cl ->
                cl[0] += speed * 0.15f * cl[2]; if (cl[0] > 1.2f) cl[0] = -0.3f
                c.sprite(Sprites.cloud, cl[0] * w, cl[1] * h, Px.u * 2)
            }
            val fy = 1f - engine.y
            for (p in parts) {
                val turb = sin(p[0] * 9f + t * 1.3f) * 0.004f + cos(p[1] * 7f - t) * 0.003f
                if (engine.touching) p[1] += (fy - p[1]) * 0.02f
                p[1] += turb + (gustY - p[1]) * gust * 0.01f
                p[0] += speed * p[2] * (1f + (engine.x - 0.5f))
                if (p[0] > 1.05f) { p[0] = -0.05f; p[1] = Random.nextFloat() }
                val len = (2 + speed * 400 * p[2]).toInt().coerceAtMost(9)
                val col = if (gust > 0.3f && kotlin.math.abs(p[1] - gustY) < 0.15f) Pal.ORANGE else Pal.CREAM
                for (k in 0 until len) c.dot(p[0] * w - k * Px.u, p[1] * h, col)
            }
            var x = 0f
            while (x < w) {
                val y1 = h * 0.86f + sin(x * 0.01f + 3) * h * 0.04f
                c.fill(x, (y1 / Px.u).toInt() * Px.u, x + Px.u, h, Pal.BROWN_L)
                x += Px.u
            }
        }
        override fun marker(c: Canvas, x: Float, y: Float, t: Float) = c.spriteCentered(Sprites.critter("gusts", 0), x, y, Px.u * 1.5f)
    }

    // ------------------------------------------------------------------ Reflections: pond
    private inner class Pond : Theme() {
        val ripples = ArrayList<FloatArray>() // x, y, age, strength
        val last = IntArray(1); var lastTouch = 0f
        override fun touch(x: Float, y: Float) {
            val now = System.nanoTime() / 1e9f
            if (now - lastTouch > 0.12f) { ripples += floatArrayOf(x / width, y / height, 0f, 0.8f); lastTouch = now }
        }
        override fun draw(c: Canvas, w: Float, h: Float, t: Float) {
            val hz = h * 0.28f
            c.bands(0f, 0f, w, hz, intArrayOf(Pal.CREAM, 0xFFF7E7C9.toInt(), 0xFFF2CFA2.toInt(), Pal.ORANGE_L))
            c.bands(0f, hz, w, h, intArrayOf(Pal.AQUA, 0xFF37AAA1.toInt(), 0xFF2F9A93.toInt(), 0xFF28857F.toInt(), Pal.AQUA_D))
            // Darker water as the filter closes.
            c.fill(0f, hz, w, h, Pal.alpha(Pal.BROWN_D, (1f - engine.y) * 0.35f))
            if (notePulse(last)) ripples += floatArrayOf(0.2f + Random.nextFloat() * 0.6f, 0.45f + Random.nextFloat() * 0.4f, 0f, 1f)
            for (k in 0 until 12) {
                val sx = ((k * 97 + t * 30 * (1 + k % 3)) % (w + 40)) - 20
                c.fill((sx / Px.u).toInt() * Px.u, hz + Px.u * (3 + (k * 7) % 30), (sx / Px.u).toInt() * Px.u + Px.u * (2 + k % 3), hz + Px.u * (4 + (k * 7) % 30), Pal.CREAM)
            }
            val it = ripples.iterator()
            while (it.hasNext()) {
                val r = it.next(); r[2] += 0.016f
                if (r[2] > 1.6f) { it.remove(); continue }
                for (ring in 0 until 3) {
                    val a = r[2] - ring * 0.15f
                    if (a <= 0f) continue
                    val rx = a * w * 0.25f * r[3]; val ry = rx * 0.3f
                    val n = (rx / Px.u).toInt().coerceIn(8, 80)
                    for (i in 0 until n) {
                        val ang = i * 6.283f / n
                        c.dot(r[0] * w + cos(ang) * rx, r[1] * h + sin(ang) * ry, if (ring == 0) Pal.CREAM else Pal.AQUA_L)
                    }
                }
            }
            // Reeds sway with the pitch bend.
            val sway = (engine.x - 0.5f) * 10f
            for (side in 0..1) for (i in 0 until 5) {
                val bx = if (side == 0) Px.u * (3 + i * 3) else w - Px.u * (3 + i * 3)
                val top = h * (0.35f + (i % 3) * 0.08f)
                var y = h
                while (y > top) { c.dot(bx + sway * (1 - (y - top) / (h - top)) * Px.u, y, Pal.BROWN); y -= Px.u }
                c.dot(bx + sway * Px.u - Px.u / 2, top, Pal.ORANGE_D, 2)
            }
        }
        override fun marker(c: Canvas, x: Float, y: Float, t: Float) = c.spriteCentered(Sprites.drop, x, y, Px.u * 1.5f)
    }

    // ------------------------------------------------------------------ Leaves: one branch
    private inner class Branch : Theme() {
        val stageColors = listOf(Pal.AQUA_D to Pal.AQUA, Pal.AQUA to Pal.AQUA_L, Pal.ORANGE_L to Pal.CREAM, Pal.ORANGE to Pal.ORANGE_L, Pal.ORANGE_D to Pal.ORANGE, Pal.BROWN_L to Pal.ORANGE_L)
        val leafSprites = stageColors.map { (a, b) -> Sprite(listOf("..oo", ".oOO", "oOO.", "OO..", "D..."), mapOf('O' to a, 'o' to b)) }
        val leaves = ArrayList<Leaf>()
        var read = 0L
        fun by(x: Float, w: Float, h: Float) = h * (0.62f - 0.18f * x / w) + sin(x / w * 5f) * h * 0.04f
        override fun draw(c: Canvas, w: Float, h: Float, t: Float) {
            c.bands(0f, 0f, w, h, intArrayOf(Pal.CREAM, 0xFFF5EAD2.toInt(), 0xFFF2E4C7.toInt(), Pal.PAPER))
            c.fill(0f, h - Px.u * 6, w, h, Pal.BROWN)
            // New grains become new leaves.
            val ring = Audio.leaves.grains
            val written = ring.written
            if (written - read > ring.a.size) read = written - ring.a.size
            var budget = 14
            while (read < written && budget-- > 0) {
                val idx = (read % ring.a.size).toInt()
                val life = 0.3f + Random.nextFloat() * 0.8f
                leaves += Leaf(ring.a[idx].coerceIn(0.02f, 0.98f), if (Random.nextBoolean()) -1f else 1f, t, life, Random.nextBoolean())
                read++
            }
            read = maxOf(read, written - 4)
            if (leaves.size > 160) leaves.subList(0, leaves.size - 160).clear()
            // The branch itself.
            var x = 0f
            while (x < w) {
                val th = (5f - 3f * x / w)
                val y = by(x, w, h)
                c.fill(x, y - th * Px.u, x + Px.u, y + th * Px.u, Pal.BROWN)
                c.fill(x, y - th * Px.u, x + Px.u, y - (th - 1) * Px.u, Pal.BROWN_L)
                x += Px.u
            }
            // Leaves: grow, turn from aqua to orange to brown, then vanish — each on its own clock.
            val it = leaves.iterator()
            while (it.hasNext()) {
                val l = it.next()
                val k = (t - l.born) / l.life
                if (k >= 1f) { it.remove(); continue }
                val lx = l.t * w
                val ly = by(lx, w, h) + l.side * Px.u * 4
                val grow = (k / 0.25f).coerceAtMost(1f)
                val stage = (k * leafSprites.size).toInt().coerceIn(0, leafSprites.size - 1)
                val s = leafSprites[stage]
                val scale = Px.u * (0.6f + grow * 1.2f)
                c.sprite(s, lx - s.w * scale / 2, if (l.side < 0) ly - s.h * scale else ly, scale, flip = l.flip)
            }
        }
        override fun marker(c: Canvas, x: Float, y: Float, t: Float) {
            c.spriteCentered(Sprites.flower, x, by(x, width.toFloat(), height.toFloat()), Px.u * 2)
            c.spriteCentered(Sprites.flower, x, y, Px.u * 1.5f, alpha = 150)
        }
    }

    // ------------------------------------------------------------------ Teeth: gingham + jaw
    private inner class Jaw : Theme() {
        override fun draw(c: Canvas, w: Float, h: Float, t: Float) {
            val teeth = Audio.teeth
            val on = teeth.biting
            c.fill(0f, 0f, w, h, Pal.CREAM)
            val cell = Px.u * 8
            val col = Pal.alpha(if (on) Pal.ORANGE else Pal.AQUA, if (on) 0.3f else 0.22f)
            var x = 0f
            while (x < w) { c.fill(x, 0f, x + cell, h, col); x += cell * 2 }
            var y = 0f
            while (y < h) { c.fill(0f, y, w, y + cell, col); y += cell * 2 }
            val step = teeth.currentStep
            if (on && Audio.transport.playing && step >= 0 && teeth.bites[step]) c.fill(0f, 0f, w, h, Pal.alpha(Pal.ORANGE, 0.15f))
            // Jaw strip with the bite pattern.
            val tw = (w - Px.u * 4) / 16
            for (i in 0 until 16) {
                val l = Px.u * 2 + i * tw
                val cur = Audio.transport.playing && i == step
                val fillC = when { teeth.bites[i] && cur -> Pal.AQUA_L; teeth.bites[i] -> Pal.CREAM; else -> Pal.BROWN }
                c.fill(l, Px.u * 2 + (if (cur) Px.u * 2 else 0f), l + tw - Px.u, Px.u * 9 + (if (cur) Px.u * 2 else 0f), fillC)
            }
        }
        override fun marker(c: Canvas, x: Float, y: Float, t: Float) =
            c.spriteCentered(Sprites.acorn, x, y - (if (Audio.teeth.biting) (sin(t * 12) + 1) * Px.u else 0f), Px.u * 2)
    }
}

private class Leaf(val t: Float, val side: Float, val born: Float, val life: Float, val flip: Boolean)
