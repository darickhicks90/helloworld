package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.audio.XyState
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The XY pad on the left of each page. X and Y each drive a parameter you
 * pick (tap the axis label). The themed scene reacts to your finger and to
 * the notes being played.
 */
@SuppressLint("ViewConstructor")
class XyPad(c: Context, private val themeId: String, private val pickAxis: (XyState, Boolean) -> Unit) : PixelView(c) {
    private val theme: Theme = when (themeId) {
        "gusts" -> Wind(); "reflections" -> Pond(); "leaves" -> Tree(); "bumble" -> Bee(); "pickup" -> Dashboard(); "minds" -> Wind(); else -> Garden()
    }
    private val label = Px.text(11f, Pal.CREAM, align = Paint.Align.LEFT)
    private var pointer = -1
    private val t0 = System.nanoTime()
    private val xy get() = Studio.pad
    private val eng get() = Audio.engines.firstOrNull { it.id == themeId }
    private val xTag = FloatArray(4); private val yTag = FloatArray(4); private val modeTag = FloatArray(4)
    private var lastTap = 0L; private var downAt = 0L; private var downX = 0f; private var downY = 0f

    init { animated = true }

    override fun paint(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val time = (System.nanoTime() - t0) / 1e9f
        val pad = xy
        val dx = if (pad.touching) pad.x else pad.restX()
        val dy = if (pad.touching) pad.y else pad.restY()
        c.save(); c.clipRect(u, u, w - u, h - u)
        theme.draw(c, w, h, time, dx, dy)
        val mx = dx * w; val my = (1 - dy) * h
        var k = 0f
        while (k < h) { c.dot(mx, k, Pal.alpha(Pal.BROWN_D, 0.3f)); k += u * 3 }
        k = 0f
        while (k < w) { c.dot(k, my, Pal.alpha(Pal.BROWN_D, 0.3f)); k += u * 3 }
        theme.marker(c, mx, my, time)
        c.restore()
        c.pixelBox(0f, 0f, w, h, 0, shade = false)
        if (!pad.stay.on) {
            // home marker
            val hx = pad.homeX.value * w; val hy = (1 - pad.homeY.value) * h
            c.fill(hx - u * 2, hy, hx + u * 3, hy + u, Pal.alpha(Pal.CREAM, 0.7f)); c.fill(hx, hy - u * 2, hx + u, hy + u * 3, Pal.alpha(Pal.CREAM, 0.7f))
        }
        tag(c, "Y ▾ ${pad.yParam.name}  ${pad.yParam.text()}", u * 3, u * 3, yTag)
        tag(c, "X ▾ ${pad.xParam.name}  ${pad.xParam.text()}", u * 3, h - u * 3 - Px.dp(20f), xTag)
        val m = if (pad.stay.on) "STAY" else "HOME"
        tag(c, m, w - u * 3 - label.measureText(m) - Px.dp(12f), u * 3, modeTag)
    }

    private fun tag(c: Canvas, s: String, x: Float, y: Float, box: FloatArray) {
        val r = x + label.measureText(s) + Px.dp(12f)
        c.fill(x, y, r, y + Px.dp(20f), Pal.alpha(Pal.BROWN_D, 0.85f))
        c.drawText(s, x + Px.dp(6f), y + Px.dp(14f), label)
        box[0] = x; box[1] = y; box[2] = r; box[3] = y + Px.dp(20f)
    }
    private fun inBox(b: FloatArray, x: Float, y: Float) = x in b[0]..b[2] && y in b[1]..b[3]

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (pointer < 0) {
                val x = e.getX(i); val y = e.getY(i)
                if (inBox(xTag, x, y)) { pickAxis(xy, true); return true }
                if (inBox(yTag, x, y)) { pickAxis(xy, false); return true }
                if (inBox(modeTag, x, y)) {
                    val pad = xy
                    pad.stay.set(if (pad.stay.on) 0f else 1f)
                    app.foxbox.Studio.message(if (pad.stay.on) "XY stays where you leave it. Double-tap the crosshair to set home." else "XY springs back home when you let go")
                    app.foxbox.Studio.save(); return true
                }
                val now = System.currentTimeMillis()
                val pad = xy
                val cx = (if (pad.touching) pad.x else pad.restX()) * width; val cy = (1 - (if (pad.touching) pad.y else pad.restY())) * height
                if (now - lastTap < 320 && kotlin.math.hypot(x - cx, y - cy) < Px.dp(48f) && pad.stay.on) {
                    pad.setHome(); app.foxbox.Studio.message("Home set here"); app.foxbox.Studio.save(); lastTap = 0L; return true
                }
                downAt = now; downX = x; downY = y
                pointer = e.getPointerId(i); parent?.requestDisallowInterceptTouchEvent(true); move(x, y)
            }
            MotionEvent.ACTION_MOVE -> { val p = e.findPointerIndex(pointer); if (p >= 0) move(e.getX(p), e.getY(p)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(i) == pointer) {
                    if (pointer >= 0 && System.currentTimeMillis() - downAt < 250) lastTap = System.currentTimeMillis()
                    pointer = -1
                    val pad = xy
                    Studio.setXy(pad, pad.x, pad.y, false)
                }
        }
        return true
    }

    private fun move(px: Float, py: Float) {
        Studio.setXy(xy, (px / width).coerceIn(0f, 1f), (1 - py / height).coerceIn(0f, 1f), true)
        theme.touch(px, py)
    }

    private abstract inner class Theme {
        val last = IntArray(1)
        fun pulse(): Boolean { val n = eng?.noteCount ?: 0; val hit = n != last[0]; last[0] = n; return hit }
        abstract fun draw(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float)
        open fun marker(c: Canvas, x: Float, y: Float, t: Float) = c.spriteCentered(Sprites.flower, x, y, Px.u * 2)
        open fun touch(x: Float, y: Float) {}
    }

    // ------------------------------------------------------------------ Gusts: wind across the sky
    private inner class Wind : Theme() {
        val parts = Array(80) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), 0.5f + Random.nextFloat()) }
        val clouds = Array(3) { floatArrayOf(Random.nextFloat(), 0.08f + Random.nextFloat() * 0.3f, 0.3f + Random.nextFloat() * 0.5f) }
        var gust = 0f; var gustY = 0.5f
        override fun draw(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float) {
            c.bands(0f, 0f, w, h, intArrayOf(Pal.AQUA_L, 0xFF9FDED7.toInt(), 0xFFB2E5DD.toInt(), 0xFFC6EBE2.toInt(), 0xFFDAF0E4.toInt(), Pal.CREAM))
            val e = eng
            if (pulse()) { gust = 1f; gustY = 1f - (((e?.lastNote ?: 60) - 36) / 60f).coerceIn(0f, 1f) }
            gust *= 0.96f
            val speed = 0.002f + (e?.activeVoices ?: 0) * 0.0015f + y * 0.006f + gust * 0.01f
            clouds.forEach { cl ->
                cl[0] += speed * 0.15f * cl[2]; if (cl[0] > 1.2f) cl[0] = -0.3f
                c.sprite(Sprites.cloud, cl[0] * w, cl[1] * h, Px.u * 2)
            }
            for (p in parts) {
                p[1] += sin(p[0] * 9f + t * 1.3f) * 0.004f + cos(p[1] * 7f - t) * 0.003f
                if (xy.touching) p[1] += ((1 - y) - p[1]) * 0.02f
                p[1] += (gustY - p[1]) * gust * 0.01f
                p[0] += speed * p[2] * (0.5f + x)
                if (p[0] > 1.05f) { p[0] = -0.05f; p[1] = Random.nextFloat() }
                val len = (2 + speed * 400 * p[2]).toInt().coerceAtMost(9)
                val col = if (gust > 0.3f && kotlin.math.abs(p[1] - gustY) < 0.15f) Pal.ORANGE else Pal.CREAM
                for (k in 0 until len) c.dot(p[0] * w - k * Px.u, p[1] * h, col)
            }
            var xx = 0f
            while (xx < w) { val y1 = h * 0.86f + sin(xx * 0.01f + 3) * h * 0.04f; c.fill(xx, (y1 / Px.u).toInt() * Px.u, xx + Px.u, h, Pal.BROWN_L); xx += Px.u }
        }
        override fun marker(c: Canvas, x: Float, y: Float, t: Float) = c.spriteCentered(Iso.iconCloud, x, y, Px.u * 1.2f)
    }

    // ------------------------------------------------------------------ Reflections: pond
    private inner class Pond : Theme() {
        val ripples = ArrayList<FloatArray>()
        var lastTouch = 0f
        override fun touch(x: Float, y: Float) {
            val now = System.nanoTime() / 1e9f
            if (now - lastTouch > 0.12f) { ripples += floatArrayOf(x / width, y / height, 0f, 0.8f); lastTouch = now }
        }
        override fun draw(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float) {
            val hz = h * 0.28f
            c.bands(0f, 0f, w, hz, intArrayOf(Pal.CREAM, 0xFFF7E7C9.toInt(), 0xFFF2CFA2.toInt(), Pal.ORANGE_L))
            c.bands(0f, hz, w, h, intArrayOf(Pal.AQUA, 0xFF37AAA1.toInt(), 0xFF2F9A93.toInt(), 0xFF28857F.toInt(), Pal.AQUA_D))
            if (pulse()) ripples += floatArrayOf(0.2f + Random.nextFloat() * 0.6f, 0.45f + Random.nextFloat() * 0.4f, 0f, 1f)
            for (k in 0 until 12) {
                val sx = ((k * 97 + t * 30 * (1 + k % 3)) % (w + 40)) - 20
                val sy = hz + Px.u * (3 + (k * 7) % 30)
                c.fill((sx / Px.u).toInt() * Px.u, sy, (sx / Px.u).toInt() * Px.u + Px.u * (2 + k % 3), sy + Px.u, Pal.CREAM)
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
                    for (i in 0 until n) { val ang = i * 6.283f / n; c.dot(r[0] * w + cos(ang) * rx, r[1] * h + sin(ang) * ry, if (ring == 0) Pal.CREAM else Pal.AQUA_L) }
                }
            }
            val sway = (x - 0.5f) * 10f
            for (side in 0..1) for (i in 0 until 5) {
                val bx = if (side == 0) Px.u * (3 + i * 3) else w - Px.u * (3 + i * 3)
                val top = h * (0.35f + (i % 3) * 0.08f)
                var yy = h
                while (yy > top) { c.dot(bx + sway * (1 - (yy - top) / (h - top)) * Px.u, yy, Pal.BROWN); yy -= Px.u }
                c.dot(bx + sway * Px.u - Px.u / 2, top, Pal.ORANGE_D, 2)
            }
        }
        override fun marker(c: Canvas, x: Float, y: Float, t: Float) = c.spriteCentered(Sprites.drop, x, y, Px.u * 1.5f)
    }

    // ------------------------------------------------------------------ Leaves: a branching tree
    private inner class Tree : Theme() {
        val segs = ArrayList<FloatArray>()      // x1, y1, x2, y2, thickness
        val spots = ArrayList<FloatArray>()     // x, y, normalX, normalY
        var builtW = 0f; var builtH = 0f
        val stages = listOf(Pal.GREEN_D to Pal.GREEN, Pal.GREEN to Pal.GREEN_L, Pal.GREEN_L to Pal.CREAM, Pal.SUN to Pal.CREAM, Pal.ORANGE to Pal.ORANGE_L, Pal.ORANGE_D to Pal.ORANGE, Pal.BROWN_L to Pal.ORANGE_L)
        val sprites = stages.map { (a, b) -> Sprite(listOf("..oo", ".oOO", "oOO.", "OO..", "D..."), mapOf('O' to a, 'o' to b)) }
        inner class Leaf(val x: Float, val y: Float, val born: Float, val life: Float, val flip: Boolean, val stageOffset: Int)
        val leaves = ArrayList<Leaf>()
        var read = 0L

        fun build(w: Float, h: Float) {
            segs.clear(); spots.clear(); builtW = w; builtH = h
            val rnd = Random(11)
            fun branch(x: Float, y: Float, ang: Float, len: Float, th: Float, depth: Int) {
                val x2 = x + cos(ang) * len; val y2 = y + sin(ang) * len
                segs += floatArrayOf(x, y, x2, y2, th)
                if (depth >= 1) {
                    val steps = (len / (Px.u * 3)).toInt().coerceAtLeast(2)
                    for (k in 1..steps) { val f = k / steps.toFloat(); spots += floatArrayOf(x + (x2 - x) * f, y + (y2 - y) * f, -sin(ang), cos(ang)) }
                }
                if (depth < 3) {
                    val kids = if (depth == 0) 6 else 3
                    for (k in 0 until kids) {
                        val f = 0.25f + 0.7f * (k + rnd.nextFloat() * 0.5f) / kids
                        val side = if (k % 2 == 0) -1 else 1
                        branch(x + (x2 - x) * f, y + (y2 - y) * f, ang + side * (0.45f + rnd.nextFloat() * 0.5f), len * (0.42f + rnd.nextFloat() * 0.18f) * (1.1f - f * 0.4f), (th * 0.6f).coerceAtLeast(1f), depth + 1)
                    }
                }
            }
            branch(-Px.u * 2, h * 0.82f, -0.42f, w * 0.95f, 5f, 0)
        }

        override fun draw(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float) {
            if (w != builtW || h != builtH) build(w, h)
            c.bands(0f, 0f, w, h, intArrayOf(Pal.CREAM, 0xFFF5EAD2.toInt(), 0xFFF2E4C7.toInt(), Pal.PAPER))
            c.fill(0f, h - Px.u * 5, w, h, Pal.BROWN)
            for (s in segs) {
                val n = (hypot(s[2] - s[0], s[3] - s[1]) / Px.u).toInt().coerceAtLeast(1)
                val th = s[4].toInt().coerceAtLeast(1)
                for (k in 0..n) {
                    val f = k / n.toFloat()
                    c.dot(s[0] + (s[2] - s[0]) * f - th * Px.u / 2, s[1] + (s[3] - s[1]) * f - th * Px.u / 2, Pal.BROWN, th)
                }
            }
            // Each grain sprouts a leaf somewhere on the tree, spread across every twig.
            val ring = Audio.leaves.grainEvents
            val written = ring.written
            if (written - read > ring.a.size) read = written - ring.a.size
            var budget = 16
            while (read < written && budget-- > 0 && spots.isNotEmpty()) {
                val idx = (read % ring.a.size).toInt()
                val a = ring.a[idx].coerceIn(0f, 1f)
                val center = (a * (spots.size - 1)).toInt()
                val spread = (spots.size * 0.18f).toInt().coerceAtLeast(1)
                val sp = spots[(center + Random.nextInt(-spread, spread + 1)).coerceIn(0, spots.size - 1)]
                val off = (if (Random.nextBoolean()) 1 else -1) * Px.u * (1f + Random.nextFloat() * 4f)
                leaves += Leaf(sp[0] + sp[2] * off, sp[1] + sp[3] * off, t, 0.25f + Random.nextFloat() * 0.7f, Random.nextBoolean(), Random.nextInt(2))
                read++
            }
            read = maxOf(read, written - 4)
            if (leaves.size > 220) leaves.subList(0, leaves.size - 220).clear()
            val it = leaves.iterator()
            while (it.hasNext()) {
                val l = it.next()
                val k = (t - l.born) / l.life
                if (k >= 1f) { it.remove(); continue }
                val grow = (k / 0.2f).coerceAtMost(1f)
                val st = ((k * sprites.size).toInt() + l.stageOffset).coerceIn(0, sprites.size - 1)
                val spr = sprites[st]
                val sc = Px.u * (0.5f + grow * 1.1f)
                c.sprite(spr, l.x - spr.w * sc / 2, l.y - spr.h * sc / 2, sc, flip = l.flip)
            }
        }
    }

    // ------------------------------------------------------------------ Bumble: a bee over the meadow
    private inner class Bee : Theme() {
        var bx = 0.5f; var by = 0.5f
        val trail = ArrayList<FloatArray>()
        val pollen = ArrayList<FloatArray>()
        override fun draw(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float) {
            c.bands(0f, 0f, w, h * 0.7f, intArrayOf(Pal.AQUA_L, 0xFFC6EBE2.toInt(), Pal.CREAM, Pal.SUN))
            c.bands(0f, h * 0.7f, w, h, intArrayOf(Pal.GREEN_L, Pal.GREEN, Pal.GREEN_D))
            for (i in 0 until 7) c.flower(w * (0.08f + i * 0.14f), h * (0.74f + (i % 2) * 0.06f), 0.7f, 0.6f + 0.4f * sin(t + i), i, t, false)
            // Hive
            val hx = w - Px.u * 14; val hy = Px.u * 4
            for (r in 0 until 5) c.fill(hx + Px.u * (if (r % 2 == 0) 1 else 0), hy + r * Px.u * 2, hx + Px.u * (if (r % 2 == 0) 9 else 10), hy + r * Px.u * 2 + Px.u * 2, if (r % 2 == 0) Pal.HONEY else Pal.ORANGE_D)
            // The bee follows the pad with a little lag and a wobble.
            val tx = x; val ty = 1 - y
            bx += (tx - bx) * 0.08f; by += (ty - by) * 0.08f
            trail += floatArrayOf(bx * w, by * h + sin(t * 12) * Px.u)
            if (trail.size > 40) trail.removeAt(0)
            trail.forEachIndexed { i, p -> if (i % 3 == 0) c.dot(p[0], p[1], Pal.alpha(Pal.BROWN_D, i / 60f)) }
            if (pulse()) repeat(6) { pollen += floatArrayOf(bx * w, by * h, Random.nextFloat() * 2 - 1, Random.nextFloat() * -2, 1f) }
            val pit = pollen.iterator()
            while (pit.hasNext()) {
                val p = pit.next(); p[0] += p[2] * Px.u * 0.6f; p[1] += p[3] * Px.u * 0.6f; p[3] += 0.08f; p[4] -= 0.03f
                if (p[4] <= 0f) pit.remove() else c.dot(p[0], p[1], Pal.SUN)
            }
            val wing = if ((t * 20).toInt() % 2 == 0) 0f else Px.u
            c.spriteCentered(Sprites.bee, bx * w, by * h + sin(t * 12) * Px.u - wing, Px.u * 2)
        }
        override fun marker(c: Canvas, x: Float, y: Float, t: Float) = c.dot(x - Px.u, y - Px.u, Pal.HONEY, 2)
    }

    // ------------------------------------------------------------------ Pickup: a 1990s truck dashboard, the key is the cursor
    private inner class Dashboard : Theme() {
        var needle = 0f
        override fun draw(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float) {
            val g = Px.u
            // windshield: the road ahead at dusk
            c.bands(0f, 0f, w, h * 0.42f, intArrayOf(0xFF2E3550.toInt(), 0xFF51466A.toInt(), 0xFF8C5A6E.toInt(), 0xFFD9806A.toInt(), 0xFFF2B07A.toInt()))
            c.fill(0f, h * 0.36f, w, h * 0.42f, 0xFF3B3346.toInt())
            val vp = w * (0.35f + x * 0.3f)
            for (k in 0 until 6) {
                val ph = ((t * (0.6f + y)) + k / 6f) % 1f
                val yy = h * 0.37f + ph * h * 0.05f
                c.fill(vp - g * (1 + ph * 4), yy, vp + g * (1 + ph * 4), yy + g, Pal.alpha(0xFFF6E7B8.toInt(), ph))
            }
            // dash
            c.fill(0f, h * 0.42f, w, h, 0xFF4A3F38.toInt())
            c.fill(0f, h * 0.42f, w, h * 0.42f + g * 2, 0xFF2A221E.toInt())
            for (yy in 0 until 6) c.fill(0f, h * 0.48f + yy * h * 0.09f, w, h * 0.48f + yy * h * 0.09f + g, 0xFF544840.toInt())
            // gauges
            val e = eng
            if (pulse()) needle = 1f
            needle *= 0.94f
            gauge(c, w * 0.28f, h * 0.62f, h * 0.15f, 0.15f + y * 0.7f + needle * 0.15f, "RPM")
            gauge(c, w * 0.72f, h * 0.62f, h * 0.15f, 0.1f + x * 0.8f, "MPH")
            // radio with a lit display
            c.pixelBox(w * 0.38f, h * 0.80f, w * 0.62f, h * 0.93f, 0xFF2A221E.toInt(), border = 0xFF1A1512.toInt(), shade = false)
            c.fill(w * 0.40f, h * 0.83f, w * 0.60f, h * 0.88f, 0xFF7FD9A8.toInt())
            val bars = (e?.activeVoices ?: 0).coerceAtMost(8)
            for (k in 0 until bars) c.fill(w * 0.41f + k * g * 2, h * 0.87f - g * (1 + k % 3), w * 0.41f + k * g * 2 + g, h * 0.87f, 0xFF2A5A44.toInt())
            // ignition slot
            c.fill(w * 0.86f, h * 0.84f, w * 0.86f + g * 4, h * 0.84f + g * 2, 0xFF1A1512.toInt())
        }
        private fun gauge(c: Canvas, cx: Float, cy: Float, r: Float, v: Float, label: String) {
            val g = Px.u
            with(Iso) { c.disc(cx, cy, r / g + 1, g, 0xFF1A1512.toInt()); c.disc(cx, cy, r / g, g, 0xFFEDE3CC.toInt()); c.disc(cx, cy, r / g - 2, g, 0xFFF7EFDC.toInt()) }
            for (k in 0..8) { val a = Math.toRadians(-210.0 + 240.0 * k / 8); c.dot(cx + (cos(a) * r * 0.8f).toFloat(), cy + (sin(a) * r * 0.8f).toFloat(), 0xFF2A221E.toInt()) }
            val a = Math.toRadians(-210.0 + 240.0 * v.coerceIn(0f, 1f))
            var k = 0f
            while (k < r * 0.75f) { c.dot(cx + (cos(a) * k).toFloat(), cy + (sin(a) * k).toFloat(), 0xFFD8473A.toInt()); k += g * 0.7f }
            small.textSize = Px.dp(8f); c.textCentered(label, cx, cy + r * 0.45f, small)
        }
        private val small = Px.text(8f, 0xFF2A221E.toInt(), headFont = true)
        override fun marker(c: Canvas, x: Float, y: Float, t: Float) = c.spriteCentered(key, x, y, Px.u * 1.4f)
        /** A car key: black rubber head with lock/unlock buttons and a ring hole, and a cut steel blade. */
        private val key = Sprite(listOf(
            "..kkkkkk...........",
            ".kKKKKKKk..........",
            "kKKoKKrKKk.........",
            "kKKKKKKKKsssssssss.",
            "kKKbKKbKKsSSSSSSSSs",
            "kKKKKKKKKsSsSSsSsS.",
            "kKKcKKKKKsss.s..s..",
            ".kKKKKKKk..........",
            "..kkkkkk...........",
        ), mapOf('k' to 0xFF141414.toInt(), 'K' to 0xFF2E2E30.toInt(), 'o' to 0xFF7A7A80.toInt(), 'r' to 0xFFD8473A.toInt(),
            'b' to 0xFF5A5A60.toInt(), 'c' to 0xFF101010.toInt(), 's' to 0xFF8E9399.toInt(), 'S' to 0xFFD4D8DD.toInt()))
    }

    // ------------------------------------------------------------------ Flowers: the flower being designed
    private inner class Garden : Theme() {
        override fun draw(c: Canvas, w: Float, h: Float, t: Float, x: Float, y: Float) {
            c.bands(0f, 0f, w, h * 0.72f, intArrayOf(0xFFF3D9B1.toInt(), Pal.SUN, Pal.DUSK, Pal.ROSE))
            c.bands(0f, h * 0.72f, w, h, intArrayOf(Pal.GREEN, Pal.GREEN_D, Pal.GREEN_DD))
            val f = Studio.flower ?: return
            val bloom = if (Studio.auditioning) 1f else 0.35f
            c.flower(w / 2, h * 0.45f, 3.2f, bloom, f.variant, t, Studio.auditioning)
            for (i in 0 until 5) c.flower(w * (0.1f + i * 0.2f), h * 0.8f, 0.8f, 0.3f + 0.3f * sin(t * 0.7f + i), i + f.variant, t, false)
        }
    }
}
