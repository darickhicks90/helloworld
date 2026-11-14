package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.fx.Flower
import app.foxbox.seq.Ticks
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Valley: perform. Clips are critters and planted Flowers are buds.
 * Draw lines from the kitsune to critters and buds, then tap the kitsune to
 * start the linked clips together and wake the linked flowers. Drag up on a
 * sparkling flower to bloom it (more effect); drag it down to switch it off.
 */
@SuppressLint("ViewConstructor")
class ValleyScreen(c: Context, private val host: Host) : PixelView(c) {
    private val hud = Px.text(14f, Pal.CREAM)
    private val small = Px.text(11f, Pal.BROWN_D)
    private val hint = Px.text(13f, Pal.CREAM)
    private val title = Px.text(10f, Pal.CREAM, headFont = true)
    private val t0 = System.nanoTime()
    private val flies = Array(24) { floatArrayOf(Random.nextFloat(), 0.45f + Random.nextFloat() * 0.45f, Random.nextFloat() * 6f) }
    private val smoke = ArrayList<FloatArray>()

    private val foxX = 0.5f; private val foxY = 0.62f
    private val cabinX = 0.84f; private val cabinY = 0.47f

    private var from: String? = null
    private var downX = 0f; private var downY = 0f; private var curX = 0f; private var curY = 0f
    private var movingId: String? = null
    private var dragged = false
    private var blooming: Flower? = null
    private var bloomStart = 0f
    private val longPress = Runnable { val id = from; if (!dragged && id != null && id != Studio.FOX) { movingId = id; performHapticFeedback(0) } }

    private data class Button(val x: Float, val y: Float, val w: Float, val h: Float, val action: () -> Unit)
    private val buttons = ArrayList<Button>()
    var backToStudio: (() -> Unit)? = null

    init { animated = true; onStudio { invalidate() } }

    private fun nodePos(id: String): Pair<Float, Float>? = when (id) {
        Studio.FOX -> foxX * width to foxY * height
        else -> Studio.valleyClips.firstOrNull { it.id == id }?.let { it.vx * width to it.vy * height }
            ?: Studio.valleyFlowers.firstOrNull { it.id == id }?.let { it.vx * width to it.vy * height }
    }

    private fun beats(): Double {
        val t = Audio.transport
        return if (t.playing) maxOf(0.0, t.tick) / Ticks.PPQ else (System.nanoTime() - t0) / 1e9 * Studio.project.bpm / 60.0
    }

    override fun paint(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val beats = beats()
        val beatPh = (beats % 1.0).toFloat()
        val barPh = ((beats / 4.0) % 1.0).toFloat()
        val time = (System.nanoTime() - t0) / 1e9f
        scenery(c, w, h, time)

        val playing = Audio.transport.playing_ids
        fun live(id: String) = id == Studio.FOX || id in playing || Studio.valleyFlowers.any { it.id == id && it.active }
        Studio.project.links.forEach { (a, b) ->
            val pa = nodePos(a) ?: return@forEach; val pb = nodePos(b) ?: return@forEach
            val on = live(a) && live(b)
            linkLine(c, pa.first, pa.second, pb.first, pb.second, if (on) Pal.SUN else Pal.CREAM, if (on) beatPh else -1f)
        }
        val f = from
        if (f != null && dragged && movingId == null && blooming == null) nodePos(f)?.let { linkLine(c, it.first, it.second, curX, curY, Pal.ORANGE_L, -1f) }

        kitsune(c, foxX * w, foxY * h, beatPh, barPh, time, Studio.foxGroup().any { it in playing })

        Studio.valleyFlowers.forEach { fl ->
            val x = fl.vx * w; val y = fl.vy * h
            c.flower(x, y - Px.u * 4, 1.6f, fl.bloom, fl.variant, time, fl.active)
            if (fl.active) {
                // Bloom meter: off at the bottom, full bloom at the top.
                val top = y - Px.u * 16; val bot = y + Px.u * 14
                c.fill(x + Px.u * 11, top, x + Px.u * 13, bot, Pal.alpha(Pal.BROWN_D, 0.6f))
                c.fill(x + Px.u * 11, bot - (bot - top) * fl.bloom, x + Px.u * 13, bot, Pal.SUN)
            }
            tagLabel(c, fl.name + if (fl.active) "  ${(fl.bloom * 100).toInt()}%" else "", x, y + Px.u * 18)
        }

        Studio.valleyClips.forEach { clip ->
            val on = clip.id in playing
            val s = Sprites.critter(clip.engine, clip.variant)
            val bob = if (on) -abs(sin(beatPh * PI.toFloat())) * Px.u * 4 else sin(time * 1.5f + clip.vx * 9) * Px.u
            val x = clip.vx * w; val y = clip.vy * h
            if (on) c.fill(x - Px.u * 8, y + Px.u * 14, x + Px.u * 8, y + Px.u * 15, Pal.alpha(Pal.BROWN_D, 0.3f))
            c.spriteCentered(s, x, y + bob, Px.u * 2.5f, alpha = if (movingId == clip.id) 170 else 255)
            tagLabel(c, clip.name, x, y + Px.u * 16)
        }

        if (movingId != null) {
            c.pixelBox(Px.dp(12f), h - Px.dp(70f), Px.dp(130f), h - Px.dp(12f), Pal.BROWN)
            c.textCentered("✕ Remove", Px.dp(71f), h - Px.dp(41f), hud.apply { color = Pal.CREAM })
        }
        hudLayer(c, w, h)
    }

    private fun tagLabel(c: Canvas, s: String, x: Float, y: Float) {
        val tw = small.measureText(s) + Px.dp(10f)
        c.fill(x - tw / 2, y, x + tw / 2, y + Px.dp(16f), Pal.alpha(Pal.CREAM, 0.85f))
        c.textCentered(s, x, y + Px.dp(8f), small)
    }

    private fun scenery(c: Canvas, w: Float, h: Float, time: Float) {
        val u = Px.u
        // Sunset sky with a low sun.
        c.bands(0f, 0f, w, h * 0.5f, intArrayOf(0xFF4F8E9C.toInt(), 0xFF79AFA6.toInt(), 0xFFB9C9A0.toInt(), 0xFFF3D9B1.toInt(), Pal.SUN, 0xFFF3A56B.toInt(), Pal.DUSK))
        val sx = w * 0.3f; val sy = h * 0.42f
        for (y in -12..0) for (x in -12..12) if (x * x + y * y <= 144) c.dot(sx + x * u, sy + y * u, if (x * x + y * y > 100) Pal.ORANGE_L else Pal.SUN)
        var x = 0f
        while (x < w) {
            val far = h * 0.4f + sin(x * 0.006f) * h * 0.05f + sin(x * 0.017f) * h * 0.02f
            c.fill(x, (far / u).toInt() * u, x + u, h, 0xFF5E8A55.toInt())
            val near = h * 0.5f + sin(x * 0.009f + 2f) * h * 0.04f
            c.fill(x, (near / u).toInt() * u, x + u, h, Pal.GREEN_D)
            x += u
        }
        c.bands(0f, h * 0.56f, w, h, intArrayOf(Pal.GREEN, 0xFF62994A.toInt(), Pal.GREEN_D, Pal.GREEN_DD))
        // Long sunset light across the meadow.
        for (i in 0 until 6) c.fill(0f, h * (0.6f + i * 0.06f), w, h * (0.6f + i * 0.06f) + u, Pal.alpha(Pal.SUN, 0.12f))
        // River catching the sunset.
        var y = h * 0.56f
        while (y < h) {
            val rx = w * 0.14f + sin(y * 0.02f) * w * 0.04f + (y - h * 0.56f) * 0.12f
            val rw = u * (5 + (y - h * 0.56f) / h * 28)
            c.fill(rx, y, rx + rw, y + u, Pal.AQUA_D)
            if (((y / u).toInt() + (time * 4).toInt()) % 6 == 0) c.fill(rx + u * 2, y, rx + u * 5, y + u, Pal.ORANGE_L)
            y += u
        }
        // Grass tufts and wildflowers.
        for (i in 0 until 40) {
            val gx = ((i * 211) % 1000) / 1000f * w; val gy = h * (0.6f + ((i * 73) % 100) / 260f)
            c.dot(gx, gy, Pal.GREEN_L); c.dot(gx + u, gy - u, Pal.GREEN_L)
        }
        for (i in 0 until 14) c.sprite(Sprites.flower, ((i * 173 + 90) % 1000) / 1000f * w, h * (0.64f + ((i * 61) % 100) / 300f), u * 1.2f)
        // Cabin with chimney smoke.
        val cab = Sprites.cabin
        val cs = u * 2.2f
        val cx = cabinX * w - cab.w * cs / 2; val cy = cabinY * h - cab.h * cs
        c.sprite(cab, cx, cy, cs)
        if ((time * 3).toInt() % 2 == 0) c.fill(cx + 4 * cs, cy + 11 * cs, cx + 7 * cs, cy + 13 * cs, Pal.SUN)
        if (Random.nextFloat() < 0.06f) smoke += floatArrayOf(cx + 14.5f * cs, cy, 1f)
        val it = smoke.iterator()
        while (it.hasNext()) {
            val p = it.next(); p[1] -= u * 0.35f; p[0] += sin(time + p[1] * 0.05f) * u * 0.15f; p[2] -= 0.006f
            if (p[2] <= 0f) it.remove() else c.dot(p[0], p[1], Pal.alpha(Pal.CREAM, p[2] * 0.7f), 2)
        }
        // Fireflies.
        flies.forEach { m ->
            val mx = ((m[0] + time * 0.01f * (1 + m[2] % 1)) % 1f) * w
            val my = m[1] * h + sin(time + m[2]) * u * 4
            if (sin(time * 2 + m[2] * 3) > 0.2f) c.dot(mx, my, if (m[2] > 3) Pal.SUN else Pal.ORANGE_L)
        }
    }

    private fun linkLine(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, col: Int, pulse: Float) {
        val n = (hypot(x2 - x1, y2 - y1) / (Px.u * 3)).toInt().coerceAtLeast(1)
        for (i in 0..n) {
            val t = i / n.toFloat()
            val glow = pulse >= 0f && abs(t - pulse) < 0.08f
            c.dot(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, if (glow) Pal.CREAM else col, if (glow) 2 else 1)
        }
    }

    /**
     * Nine-tailed kitsune, sitting still. Synced to the tempo: a slow wave
     * runs through the tails once per bar, the fox-fire flares and the aura
     * pulses on each beat, and it breathes every two beats.
     */
    private fun kitsune(c: Canvas, cx: Float, cy: Float, beatPh: Float, barPh: Float, time: Float, awake: Boolean) {
        val u = Px.u
        val s = Sprites.kitsune
        val scale = u * 1.7f
        val bw = s.w * scale; val bh = s.h * scale
        // Aura on the beat.
        val r = bw * (0.9f + beatPh * 0.5f)
        val n = (r / u).toInt().coerceIn(12, 120)
        for (i in 0 until n) {
            val a = i * 2 * PI.toFloat() / n
            c.dot(cx + cos(a) * r, cy + sin(a) * r * 0.6f, Pal.alpha(if (awake) Pal.SUN else Pal.AQUA_L, (1f - beatPh) * if (awake) 0.8f else 0.4f))
        }
        // Nine tails fanned behind.
        val baseX = cx; val baseY = cy + bh * 0.3f
        for (tail in 0 until 9) {
            val spread = (tail - 4) / 4f
            var ang = (-PI / 2).toFloat() + spread * 1.25f
            val wave = sin((barPh - tail / 18f) * 2 * PI.toFloat()) * 0.18f
            var tx = baseX; var ty = baseY
            val segs = 13
            for (k in 0 until segs) {
                ang += spread * 0.05f + wave * 0.08f
                tx += cos(ang) * u * 2f; ty += sin(ang) * u * 2f
                val size = when { k < 4 -> 3; k < 9 -> 4; else -> 3 }
                val col = when { k >= segs - 2 -> Pal.CREAM; k % 4 == 0 -> Pal.ORANGE_L; else -> Pal.ORANGE }
                c.dot(tx - size * u / 2, ty - size * u / 2, Pal.BROWN_D, size + 1)
                c.dot(tx - size * u / 2 + u / 2, ty - size * u / 2 + u / 2, col, size)
            }
            // Fox-fire at the tip, flaring on the beat.
            val flare = (1f - beatPh) * if (awake) 1f else 0.5f
            if (sin(time * 11 + tail * 1.7f) > -0.3f) c.dot(tx - u, ty - u * (2 + flare * 2), if (flare > 0.5f) Pal.CREAM else Pal.AQUA_L, 2)
            c.dot(tx - u / 2, ty - u * (1 + flare), Pal.AQUA, 1)
        }
        val breathe = if (((beats() / 2).toLong() % 2L) == 0L) 0f else -u
        c.sprite(s, cx - bw / 2, cy - bh / 2 + breathe, scale)
        if (barPh < 0.08f) c.dot(cx - u, cy - bh / 2 - u * 2, Pal.AQUA_L, 2)
        c.fill(cx - bw * 0.8f, cy + bh / 2, cx + bw * 0.8f, cy + bh / 2 + u * 3, Pal.PAPER)
        c.fill(cx - bw * 0.8f, cy + bh / 2 + u * 3, cx + bw * 0.8f, cy + bh / 2 + u * 4, Pal.BROWN_D)
    }

    private fun hudLayer(c: Canvas, w: Float, h: Float) {
        buttons.clear()
        fun button(label: String, x: Float, y: Float, bw: Float, on: Boolean = false, action: () -> Unit) {
            c.pixelBox(x, y, x + bw, y + Px.dp(40f), if (on) Pal.ORANGE else Pal.CREAM)
            hud.color = if (on) Pal.CREAM else Pal.BROWN_D
            c.textCentered(label, x + bw / 2, y + Px.dp(20f), hud)
            buttons += Button(x, y, bw, Px.dp(40f), action)
        }
        val m = Px.dp(10f)
        val t = Audio.transport
        button("◀ Studio", m, m, Px.dp(110f)) { Studio.save(); backToStudio?.invoke() }
        button(if (t.playing) "■ Stop" else "▶ Play", m + Px.dp(118f), m, Px.dp(96f), t.playing) { Studio.togglePlay() }
        button("● Audio", w - m - Px.dp(110f), m, Px.dp(110f), Studio.taping) { Studio.toggleTape() }
        button("${Studio.project.bpm.toInt()} BPM", w - m - Px.dp(226f), m, Px.dp(108f)) { host.bpmDialog() }
        c.textCentered("THE VALLEY", w / 2, m + Px.dp(20f), title)
        val msg = when {
            Studio.valleyClips.isEmpty() && Studio.valleyFlowers.isEmpty() -> "Long-press a clip and choose “Send to Valley”, or plant a flower"
            Studio.valleyFlowers.any { it.active } -> "Drag up on a sparkling flower to bloom it · drag it down to switch it off"
            else -> "Draw lines from the kitsune to critters and flowers · tap the kitsune to begin"
        }
        c.fill(0f, h - Px.dp(26f), w, h, Pal.alpha(Pal.BROWN_D, 0.6f))
        c.textCentered(msg, w / 2, h - Px.dp(13f), hint)
    }

    private fun nodeAt(x: Float, y: Float): String? {
        val r = Px.u * 14
        Studio.valleyClips.asReversed().forEach { if (hypot(it.vx * width - x, it.vy * height - y) < r) return it.id }
        Studio.valleyFlowers.asReversed().forEach { if (hypot(it.vx * width - x, it.vy * height - y) < r * 1.3f) return it.id }
        if (hypot(foxX * width - x, foxY * height - y) < Px.u * 26) return Studio.FOX
        return null
    }

    private fun linkAt(x: Float, y: Float): Pair<String, String>? = Studio.project.links.firstOrNull { (a, b) ->
        val pa = nodePos(a) ?: return@firstOrNull false; val pb = nodePos(b) ?: return@firstOrNull false
        val dx = pb.first - pa.first; val dy = pb.second - pa.second
        val t = (((x - pa.first) * dx + (y - pa.second) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        hypot(pa.first + dx * t - x, pa.second + dy * t - y) < Px.dp(12f) && t in 0.1f..0.9f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        curX = e.x; curY = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                buttons.firstOrNull { e.x in it.x..(it.x + it.w) && e.y in it.y..(it.y + it.h) }?.let { it.action(); return true }
                downX = e.x; downY = e.y; dragged = false; movingId = null; blooming = null
                from = nodeAt(e.x, e.y)
                val fl = Studio.valleyFlowers.firstOrNull { it.id == from && it.active }
                if (fl != null) { blooming = fl; bloomStart = fl.bloom; return true }
                if (from != null) postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > Px.dp(10f)) { if (!dragged && movingId == null) removeCallbacks(longPress); dragged = true }
                blooming?.let { Studio.setBloom(it, bloomStart + (downY - e.y) / Px.dp(140f)); return true }
                movingId?.let { id ->
                    val vx = (e.x / width).coerceIn(0.04f, 0.96f); val vy = (e.y / height).coerceIn(0.2f, 0.88f)
                    Studio.valleyClips.firstOrNull { it.id == id }?.let { it.vx = vx; it.vy = vy }
                    Studio.valleyFlowers.firstOrNull { it.id == id }?.let { it.vx = vx; it.vy = vy }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val src = from; val mv = movingId
                when {
                    blooming != null -> Studio.save()
                    mv != null -> if (e.x < Px.dp(130f) && e.y > height - Px.dp(70f)) Studio.removeFromValley(mv) else Studio.save()
                    src != null && dragged -> nodeAt(e.x, e.y)?.let { dst -> if (dst != src) Studio.link(src, dst) }
                    src == Studio.FOX -> Studio.foxTapped()
                    src != null -> Studio.valleyClips.firstOrNull { it.id == src }?.let { Studio.toggleClip(it) }
                    else -> linkAt(e.x, e.y)?.let { Studio.unlink(it) }
                }
                from = null; movingId = null; dragged = false; blooming = null
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); from = null; movingId = null; dragged = false; blooming = null }
        }
        return true
    }
}
