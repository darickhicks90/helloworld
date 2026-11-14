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
 * The Valley: perform in scenes. Clips are critters and planted Flowers are
 * buds. Draw lines between what you want in the next scene (or tap single
 * items), then tap the kitsune: the scene changes when the current one
 * reaches the end of its loop. Drag up on a sparkling flower to bloom it;
 * drag it down to switch it off.
 */
@SuppressLint("ViewConstructor")
class ValleyScreen(c: Context, private val host: Host) : PixelView(c) {
    private val hud = Px.text(14f, Pal.CREAM)
    private val small = Px.text(9f, Pal.BROWN_D)
    private val hint = Px.text(13f, Pal.CREAM)
    private val title = Px.text(10f, Pal.CREAM, headFont = true)
    private val t0 = System.nanoTime()
    private val flies = Array(24) { floatArrayOf(Random.nextFloat(), 0.45f + Random.nextFloat() * 0.45f, Random.nextFloat() * 6f) }
    private val smoke = ArrayList<FloatArray>()

    private val foxX = 0.5f; private val foxY = 0.62f
    private val cabinX = 0.93f; private val cabinY = 0.9f
    private val cabinBox = FloatArray(4)
    private var poem: Pair<String, String>? = null
    private var poemAt = 0L
    private val poemP = Px.text(13f, Pal.CREAM)
    private val poetP = Px.text(10f, Pal.SUN)

    /**
     * Every critter has a home, so nothing overlaps: Gusts in the sky, Bumble
     * between sky and meadow, Reflections in the pond, Minds and Flowers on
     * the valley floor, and the cabin's corner kept clear.
     */
    private val homes = mapOf(
        "gusts" to listOf(0.16f to 0.27f, 0.36f to 0.21f, 0.68f to 0.24f),
        "bumble" to listOf(0.24f to 0.46f, 0.74f to 0.43f, 0.87f to 0.52f),
        "reflections" to listOf(0.10f to 0.79f, 0.18f to 0.86f, 0.25f to 0.78f),
        "minds" to listOf(0.35f to 0.75f, 0.43f to 0.85f, 0.33f to 0.86f),
        "flowers" to listOf(0.58f to 0.80f, 0.65f to 0.73f, 0.70f to 0.86f, 0.77f to 0.75f, 0.83f to 0.86f),
    )
    private val spots = HashMap<String, Pair<Float, Float>>()
    private var moveX = 0f; private var moveY = 0f

    private fun layout() {
        spots.clear()
        val byEngine = HashMap<String, Int>()
        Studio.valleyClips.forEach { c ->
            val i = byEngine.getOrDefault(c.engine, 0); byEngine[c.engine] = i + 1
            homes[c.engine]?.getOrNull(i)?.let { spots[c.id] = it }
        }
        Studio.valleyFlowers.forEachIndexed { i, f -> homes.getValue("flowers").getOrNull(i)?.let { spots[f.id] = it } }
    }

    private var from: String? = null
    private var downX = 0f; private var downY = 0f; private var curX = 0f; private var curY = 0f
    private var movingId: String? = null
    private var dragged = false
    private var blooming: Flower? = null
    private var bloomStart = 0f
    private val longPress = Runnable { val id = from; if (!dragged && id != null && id != Studio.FOX) { movingId = id; moveX = downX; moveY = downY; performHapticFeedback(0) } }

    private data class Button(val x: Float, val y: Float, val w: Float, val h: Float, val action: () -> Unit)
    private val buttons = ArrayList<Button>()
    var backToStudio: (() -> Unit)? = null

    init { animated = true; onStudio { invalidate() } }

    private fun nodePos(id: String): Pair<Float, Float>? = when (id) {
        Studio.FOX -> foxX * width to foxY * height
        movingId -> moveX to moveY
        else -> spots[id]?.let { it.first * width to it.second * height }
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
        layout()
        scenery(c, w, h, time)

        val playing = Audio.transport.playing_ids
        val project = Studio.project
        // The scene that is playing: solid lines with a pulse on the beat.
        project.sceneLinks.forEach { (a, b) ->
            val pa = nodePos(a) ?: return@forEach; val pb = nodePos(b) ?: return@forEach
            linkLine(c, pa.first, pa.second, pb.first, pb.second, Pal.SUN, if (Audio.transport.playing) beatPh else -1f, 1)
        }
        // The next scene being drawn: dotted lines.
        project.links.forEach { (a, b) ->
            val pa = nodePos(a) ?: return@forEach; val pb = nodePos(b) ?: return@forEach
            linkLine(c, pa.first, pa.second, pb.first, pb.second, Pal.CREAM, -1f, 2)
        }
        val f = from
        if (f != null && f != Studio.FOX && dragged && movingId == null && blooming == null) nodePos(f)?.let { linkLine(c, it.first, it.second, curX, curY, Pal.ORANGE_L, -1f, 2) }

        val queued = Studio.sceneQueued
        kitsune(c, foxX * w, foxY * h, beatPh, barPh, time, playing.isNotEmpty() || queued)
        val solo = project.nextSolo
        fun ring(x: Float, y: Float, r: Float) {
            if ((time * 3).toInt() % 2 == 0) return
            val n = 16
            for (i in 0 until n) { val a = i * 6.283f / n; c.dot(x + cos(a) * r, y + sin(a) * r, Pal.CREAM) }
        }

        Studio.valleyFlowers.forEach { fl ->
            val (x, y) = nodePos(fl.id) ?: return@forEach
            c.flower(x, y - Px.u * 2, 0.8f, fl.bloom, fl.variant, time, fl.active)
            if (fl.id in solo) ring(x, y, Px.u * 8)
            if (fl.active) {
                // Bloom meter: off at the bottom, full bloom at the top.
                val top = y - Px.u * 10; val bot = y + Px.u * 8
                c.fill(x + Px.u * 6, top, x + Px.u * 7, bot, Pal.alpha(Pal.BROWN_D, 0.6f))
                c.fill(x + Px.u * 6, bot - (bot - top) * fl.bloom, x + Px.u * 7, bot, Pal.SUN)
            }
            tagLabel(c, fl.name + if (fl.active) " ${(fl.bloom * 100).toInt()}%" else "", x, y + Px.u * 9)
        }

        Studio.valleyClips.forEach { clip ->
            val on = clip.id in playing
            val s = Sprites.critter(clip.engine, clip.variant)
            val (x, y0) = nodePos(clip.id) ?: return@forEach
            // Sky and air critters drift a little; the rest hop on the beat when playing.
            val drift = if (clip.engine == "gusts" || clip.engine == "bumble") sin(time * 1.3f + x * 0.01f) * Px.u else 0f
            val y = y0 + drift
            val bob = if (on) -abs(sin(beatPh * PI.toFloat())) * Px.u * 1.5f else 0f
            if (on) c.fill(x - Px.u * 2, y + Px.u * 3, x + Px.u * 2, y + Px.u * 3.5f, Pal.alpha(Pal.BROWN_D, 0.3f))
            if (clip.id in solo) ring(x, y, Px.u * 6)
            c.spriteCentered(s, x, y + bob, Px.u * 0.65f, alpha = if (movingId == clip.id) 170 else 255)
            tagLabel(c, clip.name, x, y + Px.u * 5)
        }

        if (movingId != null) {
            c.pixelBox(Px.dp(12f), h - Px.dp(70f), Px.dp(130f), h - Px.dp(12f), Pal.BROWN)
            c.textCentered("✕ Remove", Px.dp(71f), h - Px.dp(41f), hud.apply { color = Pal.CREAM })
        }
        hudLayer(c, w, h)
    }

    private fun tagLabel(c: Canvas, s: String, x: Float, y: Float) {
        val tw = small.measureText(s) + Px.dp(6f)
        c.fill(x - tw / 2, y, x + tw / 2, y + Px.dp(12f), Pal.alpha(Pal.CREAM, 0.75f))
        c.textCentered(s, x, y + Px.dp(6f), small)
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
        // A river winding down from the hills into a pond.
        var y = h * 0.53f
        while (y < h * 0.74f) {
            val k = (y - h * 0.53f) / (h * 0.21f)
            val rx = w * (0.26f - k * 0.08f) + sin(y * 0.03f) * w * 0.02f
            val rw = u * (3 + k * 6)
            c.fill(rx, y, rx + rw, y + u, Pal.AQUA_D)
            if (((y / u).toInt() + (time * 4).toInt()) % 6 == 0) c.fill(rx + u, y, rx + u * 3, y + u, Pal.ORANGE_L)
            y += u
        }
        val pcx = w * 0.18f; val pcy = h * 0.82f; val prx = w * 0.13f; val pry = h * 0.1f
        y = pcy - pry
        while (y < pcy + pry) {
            val dy = (y - pcy) / pry
            val half = prx * kotlin.math.sqrt(maxOf(0f, 1f - dy * dy))
            c.fill(((pcx - half) / u).toInt() * u, y, ((pcx + half) / u).toInt() * u, y + u, if (dy < -0.6f) Pal.AQUA else Pal.AQUA_D)
            y += u
        }
        for (k in 0 until 5) {
            val sx = pcx + sin(time * 0.6f + k * 1.7f) * prx * 0.6f
            val sy = pcy + (k - 2) * pry * 0.3f
            c.fill(sx, sy, sx + u * 3, sy + u, Pal.alpha(Pal.SUN, 0.7f))
        }
        // Grass tufts and wildflowers.
        for (i in 0 until 40) {
            val gx = ((i * 211) % 1000) / 1000f * w; val gy = h * (0.6f + ((i * 73) % 100) / 260f)
            c.dot(gx, gy, Pal.GREEN_L); c.dot(gx + u, gy - u, Pal.GREEN_L)
        }
        for (i in 0 until 14) c.sprite(Sprites.flower, ((i * 173 + 90) % 1000) / 1000f * w, h * (0.64f + ((i * 61) % 100) / 300f), u * 1.2f)
        // Cabin with chimney smoke.
        val cab = Sprites.cabin
        val cs = u * 1.1f
        val cx = cabinX * w - cab.w * cs / 2; val cy = cabinY * h - cab.h * cs
        c.sprite(cab, cx, cy, cs)
        cabinBox[0] = cx - u * 4; cabinBox[1] = cy - u * 6; cabinBox[2] = cx + cab.w * cs + u * 4; cabinBox[3] = cy + cab.h * cs + u * 2
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

    private fun linkLine(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, col: Int, pulse: Float, every: Int = 1) {
        val n = (hypot(x2 - x1, y2 - y1) / (Px.u * 3)).toInt().coerceAtLeast(1)
        for (i in 0..n) {
            if (i % every != 0) continue
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
        val p = poem
        if (p != null && System.currentTimeMillis() - poemAt < 20_000) {
            // A line of Romantic poetry, sized to fit between the buttons.
            val room = w - Px.dp(470f)
            val line = p.first.replace(" / ", " · ")
            poemP.textSize = Px.dp(13f)
            while (poemP.measureText(line) > room && poemP.textSize > Px.dp(8f)) poemP.textSize -= Px.dp(0.5f)
            c.textCentered(line, w / 2, m + Px.dp(14f), poemP)
            c.textCentered("— ${p.second}", w / 2, m + Px.dp(32f), poetP)
        } else c.textCentered("VALLEY", w / 2, m + Px.dp(20f), title)
        val q = Audio.transport.queuedAt
        if (q >= 0) {
            val beatsLeft = maxOf(0.0, (q - Audio.transport.tick) / Ticks.PPQ)
            val banner = if (Studio.nextScene().isEmpty() && Audio.transport.playing) "Ending in ${Math.ceil(beatsLeft).toInt()} beats" else "Next scene in ${Math.ceil(beatsLeft).toInt()} beats"
            val bw = hud.measureText(banner) + Px.dp(24f)
            c.pixelBox(w / 2 - bw / 2, m + Px.dp(30f), w / 2 + bw / 2, m + Px.dp(64f), Pal.SUN)
            hud.color = Pal.BROWN_D; c.textCentered(banner, w / 2, m + Px.dp(47f), hud)
        }
        val msg = when {
            Studio.valleyClips.isEmpty() && Studio.valleyFlowers.isEmpty() -> "Long-press a clip and choose “Send to Valley”, or plant a flower"
            q >= 0 -> "Scene queued · tap the kitsune again to cancel"
            Studio.valleyFlowers.any { it.active } -> "Drag up on a sparkling flower to bloom it · draw the next scene · tap the kitsune"
            else -> "Draw lines between what plays next (or tap single items) · tap the kitsune"
        }
        c.fill(0f, h - Px.dp(26f), w, h, Pal.alpha(Pal.BROWN_D, 0.6f))
        c.textCentered(msg, w / 2, h - Px.dp(13f), hint)
    }

    private fun nodeAt(x: Float, y: Float): String? {
        val r = Px.dp(22f)
        var best: String? = null; var bestD = r
        for ((id, p) in spots) { val d = hypot(p.first * width - x, p.second * height - y); if (d < bestD) { bestD = d; best = id } }
        if (best != null) return best
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
                if (fl != null) { blooming = fl; bloomStart = fl.bloom }
                if (from != null && from != Studio.FOX) postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > Px.dp(10f)) { if (!dragged && movingId == null) removeCallbacks(longPress); dragged = true }
                blooming?.let { if (dragged) { removeCallbacks(longPress); Studio.setBloom(it, bloomStart + (downY - e.y) / Px.dp(140f)) }; return true }
                if (movingId != null) { moveX = e.x; moveY = e.y }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val src = from; val mv = movingId
                when {
                    mv != null -> if (e.x < Px.dp(130f) && e.y > height - Px.dp(70f)) Studio.removeFromValley(mv)
                    blooming != null && dragged -> Studio.save()
                    src == Studio.FOX -> if (!dragged) Studio.foxTapped()
                    src != null && dragged -> nodeAt(e.x, e.y)?.let { dst -> if (dst != src && dst != Studio.FOX) Studio.link(src, dst) }
                    src != null -> Studio.toggleInNextScene(src)
                    !dragged && e.x in cabinBox[0]..cabinBox[2] && e.y in cabinBox[1]..cabinBox[3] -> { poem = Poems.random(); poemAt = System.currentTimeMillis() }
                    else -> linkAt(e.x, e.y)?.let { Studio.unlink(it) }
                }
                from = null; movingId = null; dragged = false; blooming = null
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); from = null; movingId = null; dragged = false; blooming = null }
        }
        return true
    }
}
