package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.seq.Clip
import app.foxbox.seq.Ticks
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Valley: perform your clips. Each clip is a critter; draw lines from
 * the fox to critters (or critter to critter) and tap the fox to start
 * everything linked to it in unison. Tap a critter to preview it alone.
 */
@SuppressLint("ViewConstructor")
class ValleyScreen(c: Context, private val host: Host) : PixelView(c) {
    private val hud = Px.text(14f, Pal.CREAM)
    private val small = Px.text(11f, Pal.BROWN_D)
    private val hint = Px.text(13f, Pal.CREAM)
    private val title = Px.text(10f, Pal.CREAM, headFont = true)
    private val t0 = System.nanoTime()
    private val motes = Array(26) { floatArrayOf(Random.nextFloat(), Random.nextFloat() * 0.6f, Random.nextFloat() * 6f) }

    private val foxX = 0.5f; private val foxY = 0.66f

    // touch state
    private var from: String? = null
    private var downX = 0f; private var downY = 0f; private var curX = 0f; private var curY = 0f
    private var moving: Clip? = null
    private var dragged = false
    private val longPress = Runnable { val id = from; if (!dragged && id != null && id != Studio.FOX) { moving = Studio.valleyClips.firstOrNull { it.id == id }; performHapticFeedback(0) } }

    private data class Button(val label: String, val x: Float, val y: Float, val w: Float, val h: Float, val action: () -> Unit)
    private val buttons = ArrayList<Button>()

    init { animated = true; onStudio { invalidate() } }

    private fun pos(id: String): Pair<Float, Float>? =
        if (id == Studio.FOX) foxX * width to foxY * height
        else Studio.valleyClips.firstOrNull { it.id == id }?.let { it.vx * width to it.vy * height }

    // Beat clock: the transport when playing, otherwise free-running at the project tempo.
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
        // Links
        Studio.project.links.forEach { (a, b) ->
            val pa = pos(a) ?: return@forEach; val pb = pos(b) ?: return@forEach
            val live = (a == Studio.FOX || a in playing) && (b == Studio.FOX || b in playing)
            linkLine(c, pa.first, pa.second, pb.first, pb.second, if (live) Pal.ORANGE else Pal.CREAM, if (live) beatPh else -1f)
        }
        // Link being drawn
        val f = from
        if (f != null && dragged && moving == null) pos(f)?.let { linkLine(c, it.first, it.second, curX, curY, Pal.ORANGE_L, -1f) }

        deity(c, foxX * w, foxY * h, beatPh, barPh, time, Studio.foxGroup().any { it.id in playing })

        // Critters
        Studio.valleyClips.forEach { clip ->
            val on = clip.id in playing
            val s = Sprites.critter(clip.engine, clip.variant)
            val bob = if (on) -abs(sin(beatPh * PI.toFloat())) * Px.u * 4 else sin(time * 1.5f + clip.vx * 9) * Px.u
            val x = clip.vx * w; val y = clip.vy * h + bob
            if (on) c.fill(x - Px.u * 8, clip.vy * h + Px.u * 14, x + Px.u * 8, clip.vy * h + Px.u * 15, Pal.alpha(Pal.BROWN_D, 0.3f))
            c.spriteCentered(s, x, y, Px.u * 2.5f, alpha = if (moving === clip) 170 else 255)
            val label = clip.name
            val tw = small.measureText(label) + Px.dp(10f)
            c.fill(x - tw / 2, clip.vy * h + Px.u * 16, x + tw / 2, clip.vy * h + Px.u * 16 + Px.dp(16f), Pal.alpha(Pal.CREAM, 0.85f))
            c.textCentered(label, x, clip.vy * h + Px.u * 16 + Px.dp(8f), small)
        }

        // Basket for removing critters while moving one.
        if (moving != null) {
            c.pixelBox(Px.dp(12f), h - Px.dp(70f), Px.dp(130f), h - Px.dp(12f), Pal.BROWN)
            c.textCentered("✕ Remove", Px.dp(71f), h - Px.dp(41f), hud)
        }
        hudLayer(c, w, h)
    }

    private fun scenery(c: Canvas, w: Float, h: Float, time: Float) {
        c.bands(0f, 0f, w, h * 0.55f, intArrayOf(Pal.AQUA_L, 0xFFA6E1DA.toInt(), 0xFFBFE8E1.toInt(), 0xFFD6EFE6.toInt(), Pal.CREAM))
        // Distant hills, nearer hills, meadow
        val u = Px.u
        var x = 0f
        while (x < w) {
            val far = h * 0.42f + sin(x * 0.006f) * h * 0.05f + sin(x * 0.017f) * h * 0.02f
            c.fill(x, (far / u).toInt() * u, x + u, h, 0xFF6FB3AA.toInt())
            val near = h * 0.52f + sin(x * 0.009f + 2f) * h * 0.04f
            c.fill(x, (near / u).toInt() * u, x + u, h, Pal.BROWN_L)
            x += u
        }
        c.bands(0f, h * 0.58f, w, h, intArrayOf(0xFFB98A5C.toInt(), Pal.BROWN_L, 0xFF96663E.toInt(), Pal.BROWN))
        // A winding river
        var y = h * 0.6f
        while (y < h) {
            val rx = w * 0.18f + sin(y * 0.02f) * w * 0.05f + (y - h * 0.6f) * 0.15f
            c.fill(rx, y, rx + u * (6 + (y - h * 0.6f) / h * 30), y + u, Pal.AQUA)
            if (((y / u).toInt() + (time * 4).toInt()) % 7 == 0) c.fill(rx + u * 2, y, rx + u * 4, y + u, Pal.CREAM)
            y += u
        }
        // Flowers and drifting motes of light
        for (i in 0 until 22) c.sprite(Sprites.flower, ((i * 173) % 1000) / 1000f * w, h * (0.62f + ((i * 61) % 100) / 300f), u * 1.2f)
        motes.forEach { m ->
            val mx = ((m[0] + time * 0.01f * (1 + m[2] % 1)) % 1f) * w
            val my = m[1] * h + sin(time + m[2]) * u * 4
            if (sin(time * 2 + m[2] * 3) > 0f) c.dot(mx, my, if (m[2] > 3) Pal.ORANGE_L else Pal.CREAM)
        }
    }

    private fun linkLine(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, col: Int, pulse: Float) {
        val len = hypot(x2 - x1, y2 - y1)
        val n = (len / (Px.u * 3)).toInt().coerceAtLeast(1)
        for (i in 0..n) {
            val t = i / n.toFloat()
            val glow = pulse >= 0f && abs(t - pulse) < 0.08f
            c.dot(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, if (glow) Pal.CREAM else col, if (glow) 2 else 1)
        }
    }

    /** The fox spirit: sits still; aura pulses on the beat, tails sway once per bar, it breathes every two beats. */
    private fun deity(c: Canvas, cx: Float, cy: Float, beatPh: Float, barPh: Float, time: Float, awake: Boolean) {
        val u = Px.u
        val s = Sprites.deity
        val scale = u * 3
        val bw = s.w * scale; val bh = s.h * scale
        // Aura rings, one per beat.
        val r = bw * (0.55f + beatPh * 0.5f)
        val a = (1f - beatPh) * if (awake) 0.9f else 0.45f
        val n = (r / u).toInt().coerceIn(12, 140)
        for (i in 0 until n) {
            val ang = i * 2 * PI.toFloat() / n
            c.dot(cx + cos(ang) * r, cy + sin(ang) * r * 0.75f, Pal.alpha(if (awake) Pal.ORANGE_L else Pal.AQUA_L, a))
        }
        // Tails behind the body.
        for (tail in 0 until 3) {
            var ang = (-PI / 2 + (tail - 1) * 0.65).toFloat() + sin(barPh * 2 * PI.toFloat() + tail * 0.9f) * 0.22f
            var tx = cx + (tail - 1) * u * 4; var ty = cy + bh * 0.25f
            for (k in 0 until 16) {
                ang += (tail - 1) * 0.06f + sin(barPh * 2 * PI.toFloat() + k * 0.3f + tail) * 0.03f
                tx += cos(ang) * u * 2.2f; ty += sin(ang) * u * 2.2f
                val size = (5 - k / 4).coerceAtLeast(2)
                val col = if (k > 12) Pal.CREAM else if (k % 5 == 0) Pal.ORANGE_L else Pal.ORANGE
                c.dot(tx - size * u / 2, ty - size * u / 2, Pal.BROWN_D, size + 1)
                c.dot(tx - size * u / 2 + u / 2, ty - size * u / 2 + u / 2, col, size)
            }
        }
        // Breathing: rises by one art pixel every other beat.
        val breathe = if (((beats() / 2).toLong() % 2L) == 0L) 0f else -u
        c.sprite(s, cx - bw / 2, cy - bh / 2 + breathe, scale)
        // A glint above the brow on each downbeat.
        if (barPh < 0.08f) c.dot(cx - u, cy - bh / 2 - u * 3, Pal.CREAM, 2)
        // Rest stone
        c.fill(cx - bw * 0.6f, cy + bh / 2, cx + bw * 0.6f, cy + bh / 2 + u * 3, Pal.PAPER)
        c.fill(cx - bw * 0.6f, cy + bh / 2 + u * 3, cx + bw * 0.6f, cy + bh / 2 + u * 4, Pal.BROWN_D)
    }

    private fun hudLayer(c: Canvas, w: Float, h: Float) {
        buttons.clear()
        fun button(label: String, x: Float, y: Float, bw: Float, on: Boolean = false, action: () -> Unit) {
            c.pixelBox(x, y, x + bw, y + Px.dp(40f), if (on) Pal.ORANGE else Pal.CREAM)
            hud.color = if (on) Pal.CREAM else Pal.BROWN_D
            c.textCentered(label, x + bw / 2, y + Px.dp(20f), hud)
            buttons += Button(label, x, y, bw, Px.dp(40f), action)
        }
        val m = Px.dp(10f)
        button("◀ Studio", m, m, Px.dp(110f)) { Studio.save(); backToStudio?.invoke() }
        val t = Audio.transport
        button(if (t.playing) "■ Stop" else "▶ Play", m + Px.dp(118f), m, Px.dp(96f), t.playing) { Studio.togglePlay() }
        button("● Audio", w - m - Px.dp(110f), m, Px.dp(110f), Studio.taping) { Studio.toggleTape() }
        button("${Studio.project.bpm.toInt()} BPM", w - m - Px.dp(226f), m, Px.dp(108f)) { host.bpmDialog() }
        c.textCentered("THE VALLEY", w / 2, m + Px.dp(20f), title)
        val msg = if (Studio.valleyClips.isEmpty()) "Long-press a clip in the studio and choose “Send to Valley”"
        else "Drag from the fox to a critter to link it · tap the fox to play everything linked"
        c.fill(0f, h - Px.dp(26f), w, h, Pal.alpha(Pal.BROWN_D, 0.6f))
        hint.color = Pal.CREAM
        c.textCentered(msg, w / 2, h - Px.dp(13f), hint)
    }

    /** Set by the activity to return to the studio. */
    var backToStudio: (() -> Unit)? = null

    private fun nodeAt(x: Float, y: Float): String? {
        val r = Px.u * 14
        Studio.valleyClips.asReversed().forEach { if (hypot(it.vx * width - x, it.vy * height - y) < r) return it.id }
        if (hypot(foxX * width - x, foxY * height - y) < Px.u * 34) return Studio.FOX
        return null
    }

    private fun linkAt(x: Float, y: Float): Pair<String, String>? = Studio.project.links.firstOrNull { (a, b) ->
        val pa = pos(a) ?: return@firstOrNull false; val pb = pos(b) ?: return@firstOrNull false
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
                downX = e.x; downY = e.y; dragged = false; moving = null
                from = nodeAt(e.x, e.y)
                if (from != null) postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > Px.dp(10f)) { if (!dragged && moving == null) removeCallbacks(longPress); dragged = true }
                moving?.let { it.vx = (e.x / width).coerceIn(0.04f, 0.96f); it.vy = (e.y / height).coerceIn(0.15f, 0.9f) }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val src = from; val mv = moving
                if (mv != null) {
                    if (e.x < Px.dp(130f) && e.y > height - Px.dp(70f)) Studio.removeFromValley(mv) else Studio.save()
                } else if (src != null && dragged) {
                    nodeAt(e.x, e.y)?.let { dst -> if (dst != src) Studio.link(src, dst) }
                } else if (src != null) {
                    if (src == Studio.FOX) Studio.foxTapped() else Studio.valleyClips.firstOrNull { it.id == src }?.let { Studio.toggleClip(it) }
                } else linkAt(e.x, e.y)?.let { Studio.unlink(it) }
                from = null; moving = null; dragged = false
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); from = null; moving = null; dragged = false }
        }
        return true
    }
}
