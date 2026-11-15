package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.seq.AudioClip
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Valley: perform the song. Each block's clips live in their own part of
 * the valley (clouds, rowboats, trees, mushrooms, beehive boxes, pickup
 * trucks); audio takes are the chemtrails behind three biplanes.
 *
 *  - Tap a sprite or chemtrail to queue it (and select it: drag its fader for
 *    volume). Double-tap to mute it.
 *  - The village is the transport: the roof plays the queued scene, the wall
 *    stops, the water tower counts in two bars and records the armed biplanes.
 *  - Drag a line from a flower through clips to make a daisy chain (up to 3).
 *  - Hold a sprite until it clicks, then drag it to R.I.P. to lay it to rest.
 */
@SuppressLint("ViewConstructor")
class ValleyScreen(c: Context, private val host: Host) : PixelView(c) {
    var backToStudio: (() -> Unit)? = null
    private val hud = Px.text(14f, Pal.CREAM)
    private val small = Px.text(10f, Pal.CREAM)
    private val title = Px.text(10f, Pal.CREAM, headFont = true)
    private val ripP = Px.text(11f, Pal.CREAM, headFont = true)
    private val poemP = Px.text(13f, Pal.CREAM)
    private val poetP = Px.text(10f, Pal.SUN)
    private val t0 = System.nanoTime()
    private var poem: Pair<String, String>? = null
    private var poemAt = 0L

    // ------------------------------------------------------------------ layout (fractions of the screen)

    private val homes = mapOf(
        "gusts" to listOf(0.18f to 0.385f, 0.38f to 0.37f, 0.60f to 0.39f),
        "bumble" to listOf(0.05f to 0.56f, 0.12f to 0.58f, 0.19f to 0.555f),
        "reflections" to listOf(0.08f to 0.80f, 0.17f to 0.90f, 0.21f to 0.74f),
        "minds" to listOf(0.33f to 0.69f, 0.41f to 0.73f, 0.34f to 0.84f),
        "pickup" to listOf(0.53f to 0.69f, 0.64f to 0.72f, 0.75f to 0.69f),
        "leaves" to listOf(0.72f to 0.555f, 0.82f to 0.575f, 0.92f to 0.545f),
        "flowers" to listOf(0.45f to 0.85f, 0.52f to 0.82f, 0.59f to 0.86f, 0.66f to 0.83f, 0.73f to 0.87f),
    )
    private val lanes = floatArrayOf(0.12f, 0.20f, 0.28f)
    private val trailX0 = 0.23f
    private val planeX = 0.90f
    private val roofAt = 0.32f to 0.535f
    private val wallAt = 0.40f to 0.54f
    private val towerAt = 0.48f to 0.535f
    private val cabinAt = 0.88f to 0.94f

    /** Hit areas, rebuilt every frame: id → (cx, cy, radius). */
    private class Hit(val id: String, val kind: Int, val x: Float, val y: Float, val r: Float, val extra: Int = 0)
    private val hits = ArrayList<Hit>()
    private val trailHits = ArrayList<FloatArray>() // l, t, r, b, channel
    private val trailIds = ArrayList<String>()

    private fun g() = Px.u * 0.9f

    init { animated = true }

    // ------------------------------------------------------------------ drawing

    override fun paint(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val time = (System.nanoTime() - t0) / 1e9f
        val g = g()
        hits.clear(); trailHits.clear(); trailIds.clear()
        drawLand(c, w, h, time)
        with(Iso) {
            drawPlanes(c, w, h, g, time)
            // clouds (Gusts) sit in the sky, under the planes' lanes
            clipsFor("gusts").forEach { (clip, xy) -> sprite(c, clip.id, xy.first * w, xy.second * h, 18f * g) { c.cloud(xy.first * w, xy.second * h, g, clip.variant, time, Studio.isPlaying(clip)) } }
            // the floor, back to front
            val items = ArrayList<Pair<Float, () -> Unit>>()
            for (id in listOf("bumble", "reflections", "minds", "pickup", "leaves")) clipsFor(id).forEach { (clip, xy) ->
                val x = xy.first * w; val y = xy.second * h; val playing = Studio.isPlaying(clip)
                items += y to {
                    sprite(c, clip.id, x, y - 6 * g, 13f * g) {
                        when (id) {
                            "bumble" -> c.hives(x, y, g, clip.variant, time, playing)
                            "reflections" -> c.rowboat(x, y, g, clip.variant, time, playing)
                            "minds" -> c.mushrooms(x, y, g, clip.variant, time, playing)
                            "pickup" -> c.truck(x, y, g, clip.variant, time, playing)
                            else -> c.tree(x, y, g, clip.variant, time, playing)
                        }
                    }
                }
            }
            val t = Audio.transport
            items += roofAt.second * h to { c.villageRoof(roofAt.first * w, roofAt.second * h, g, t.playing, Studio.sceneQueued, time); hits += Hit("roof", K_ROOF, roofAt.first * w, roofAt.second * h - 6 * g, 11 * g) }
            items += wallAt.second * h to { c.villageWall(wallAt.first * w, wallAt.second * h, g, false); hits += Hit("wall", K_WALL, wallAt.first * w, wallAt.second * h - 3 * g, 10 * g) }
            items += towerAt.second * h to {
                c.waterTower(towerAt.first * w, towerAt.second * h, g, Studio.armed.any { it }, t.audioRec == Transport.Rec.RECORDING, time)
                hits += Hit("tower", K_TOWER, towerAt.first * w - 2 * g, towerAt.second * h - 14 * g, 12 * g)
                if (t.audioRec == Transport.Rec.ARMED) { hud.color = Pal.CREAM; c.textCentered("${t.beat + 1}", towerAt.first * w - 2 * g, towerAt.second * h - 32 * g, hud) }
            }
            items += cabinAt.second * h to { c.cabin(cabinAt.first * w, cabinAt.second * h, g, time, true); hits += Hit("cabin", K_CABIN, cabinAt.first * w, cabinAt.second * h - 10 * g, 16 * g) }
            Studio.valleyFlowers.forEachIndexed { i, f ->
                val xy = homes.getValue("flowers").getOrNull(i) ?: return@forEachIndexed
                val x = xy.first * w; val y = xy.second * h
                items += y + 8 * g to {
                    val k = Studio.chainIndex(f.id)
                    c.flower(x, y, 1.5f, f.bloom, f.variant, time, k >= 0 && Audio.transport.playing)
                    if (k >= 0) { c.disc(x, y + 17 * g, 3f, g, Pal.alpha(CHAIN[k], 0.85f), 0.5f) }
                    hits += Hit(f.id, K_FLOWER, x, y + 2 * g, 12 * g)
                }
            }
            items += fox.y * h to { fox.draw(c, w, h, time) }
            items.sortBy { it.first }
            items.forEach { it.second() }
            drawChains(c, w, h, g, time)
            drawSelection(c, w, h, g)
            if (burying != null) {
                val sh = minOf(h / 6f, Px.dp(120f))
                stone[0] = w / 2 - sh * 0.8f; stone[1] = h - Px.dp(8f) - sh * 1.2f; stone[2] = w / 2 + sh * 0.8f; stone[3] = h - Px.dp(8f)
                c.tombstone(w / 2, h - Px.dp(8f), sh, overStone(), ripP)
                c.dot(curX - g, curY - g, Pal.CREAM, 3)
            }
        }
        drawHud(c, w, h)
    }

    private inline fun sprite(c: Canvas, id: String, x: Float, y: Float, r: Float, draw: () -> Unit) {
        val vol = Studio.volumeOf(id); val muted = Studio.mutedOf(id)
        val alpha = ((if (muted) 0.3f else 0.35f + 0.65f * vol) * 255).toInt()
        if (id == burying) return
        if (alpha < 250) { c.saveLayerAlpha(x - r * 2.5f, y - r * 3f, x + r * 2.5f, y + r * 2f, alpha); draw(); c.restore() } else draw()
        hits += Hit(id, K_CLIP, x, y, r)
        marker(c, id, x, y - r * 1.25f)
    }

    /** Queued: a cream diamond above the sprite (blinking while the next scene waits). Playing: it glows orange. */
    private fun marker(c: Canvas, id: String, x: Float, y: Float) {
        val queued = Studio.isQueued(id); val playing = Studio.isPlaying(id)
        if (!queued && !playing) return
        val now = System.currentTimeMillis()
        if (queued && Studio.sceneQueued && (now / 250) % 2 == 0L) return
        val g = Px.u * 0.8f
        val col = if (playing && queued) Pal.ORANGE else if (playing) Pal.ORANGE_L else Pal.CREAM
        val bob = sin(now / 300.0).toFloat() * g
        for (k in 0..2) c.fill(Iso.snap(x - (2 - k) * g, g), Iso.snap(y + (k - 2) * g + bob, g), Iso.snap(x + (3 - k) * g, g), Iso.snap(y + (k - 1) * g + bob, g), Iso.INK)
        for (k in 0..1) c.fill(Iso.snap(x - (1 - k) * g, g), Iso.snap(y + (k - 1) * g + bob, g), Iso.snap(x + (2 - k) * g, g), Iso.snap(y + k * g + bob, g), col)
        if (Studio.mutedOf(id)) { small.color = Pal.CREAM; c.textCentered("muted", x, y - 3 * g, small) }
    }

    private fun clipsFor(engine: String): List<Pair<app.foxbox.seq.Clip, Pair<Float, Float>>> {
        val list = Studio.valleyClips.filter { it.engine == engine }
        val spots = homes[engine] ?: return emptyList()
        return list.take(spots.size).mapIndexed { i, clip -> clip to spots[i] }
    }

    private fun drawLand(c: Canvas, w: Float, h: Float, t: Float) {
        // A late-afternoon sky in muted, grown-up tones.
        c.bands(0f, 0f, w, h * 0.46f, intArrayOf(0xFF2F5D6B.toInt(), 0xFF3E7480.toInt(), 0xFF5C8F8E.toInt(), 0xFF8DAE9C.toInt(), 0xFFC8C29A.toInt(), 0xFFE7C58E.toInt(), 0xFFF0B97E.toInt()))
        val u = Px.u
        // far ridges with 2:1 slopes
        fun ridge(base: Float, amp: Float, step: Int, col: Int, seed: Int) {
            var x = 0f
            while (x < w) {
                val k = ((x / u).toInt() + seed)
                val y = base - amp * (0.5f + 0.5f * sin(k * 0.035f) * sin(k * 0.011f + seed))
                c.fill(x, (y / u).toInt() * u, x + u * step, h * 0.6f, col); x += u * step
            }
        }
        ridge(h * 0.44f, h * 0.10f, 1, 0xFF6F8C7A.toInt(), 3)
        ridge(h * 0.47f, h * 0.06f, 1, 0xFF5E7E5C.toInt(), 17)
        // meadow
        c.bands(0f, h * 0.46f, w, h, intArrayOf(0xFF7E9A55.toInt(), 0xFF6F8E4C.toInt(), 0xFF638345.toInt(), 0xFF57773F.toInt(), 0xFF4C6A39.toInt()))
        val rnd = Random(7)
        repeat(160) {
            val x = rnd.nextFloat() * w; val y = h * (0.5f + rnd.nextFloat() * 0.5f)
            c.fill((x / u).toInt() * u, (y / u).toInt() * u, (x / u).toInt() * u + u, (y / u).toInt() * u + u * (1 + rnd.nextInt(2)), if (rnd.nextBoolean()) 0xFF8DAA60.toInt() else 0xFF465F33.toInt())
        }
        // a dirt road up to the village
        val g = g()
        var yy = h * 0.55f
        while (yy < h * 0.66f) { val k = (yy - h * 0.55f) / (h * 0.11f); val cx = w * (0.40f + k * 0.06f); c.fill(Iso.snap(cx - (3 + k * 8) * g, g), Iso.snap(yy, g), Iso.snap(cx + (3 + k * 8) * g, g), Iso.snap(yy, g) + g, 0xFFA48A62.toInt()); yy += g }
        // pond (an iso ellipse) with a shimmer
        with(Iso) {
            c.disc(w * 0.13f, h * 0.84f, w * 0.13f / g, g, 0xFF3C5E4A.toInt(), 0.5f)
            c.disc(w * 0.13f, h * 0.84f, w * 0.13f / g - 1.5f, g, 0xFF3F7F86.toInt(), 0.5f)
            c.disc(w * 0.12f, h * 0.83f, w * 0.10f / g, g, 0xFF4F939A.toInt(), 0.45f)
        }
        for (k in 0 until 10) {
            val sx = ((k * 53 + t * 9 * (1 + k % 3)) % (w * 0.2f)) + w * 0.03f
            val sy = h * (0.79f + (k * 7 % 10) * 0.012f)
            c.fill((sx / u).toInt() * u, (sy / u).toInt() * u, (sx / u).toInt() * u + u * (2 + k % 3), (sy / u).toInt() * u + u, 0x88E6F2EE.toInt())
        }
    }

    // ------------------------------------------------------------------ biplanes and chemtrails

    private fun drawPlanes(c: Canvas, w: Float, h: Float, g: Float, t: Float) {
        val tr = Audio.transport
        val nextFree = Studio.project.planes.indexOfFirst { !it }
        for (ch in 0 until AudioClip.CHANNELS) {
            val ly = lanes[ch] * h
            if (!Studio.project.planes[ch]) {
                if (ch == nextFree) {
                    // the + in the sky: adds the next biplane
                    val px = w * trailX0 + Px.dp(18f)
                    c.pixelBox(px - Px.dp(18f), ly - Px.dp(16f), px + Px.dp(18f), ly + Px.dp(16f), Pal.alpha(Pal.CREAM, 0.85f))
                    hud.color = Pal.BROWN_D; c.textCentered("+", px, ly, hud)
                    hits += Hit("plus", K_PLUS, px, ly, Px.dp(22f))
                }
                continue
            }
            val bob = sin(t * 1.4f + ch) * g
            val px = w * planeX; val py = ly + bob
            val takes = Studio.audioClipsOn(ch)
            // trail segments, one per take, proportional to length
            val x0 = w * trailX0; val x1 = px - 14 * g
            val gap = 2 * g
            val total = takes.sumOf { it.seconds.toDouble() }.toFloat().coerceAtLeast(1f)
            val avail = x1 - x0 - gap * takes.size
            var x = x0
            val minW = 14 * g
            val widths = takes.map { maxOf(minW, avail * it.seconds / total) }
            val scale = if (widths.sum() > avail && widths.isNotEmpty()) avail / widths.sum() else 1f
            takes.forEachIndexed { i, take ->
                val sw = widths[i] * scale
                if (take.id != burying) drawTrail(c, take, x, x + sw, py + g, g, t, ch)
                trailHits += floatArrayOf(x, py - 4 * g, x + sw, py + 6 * g, ch.toFloat()); trailIds += take.id
                hits += Hit(take.id, K_TAKE, x + sw / 2, py + g, minOf(sw / 2, 10 * g))
                marker(c, take.id, x + sw / 2, py - 6 * g)
                x += sw + gap
            }
            // a fresh trail while recording
            if (Studio.armed[ch] && tr.audioRec == Transport.Rec.RECORDING) {
                val k = ((t * 3) % 1f)
                for (j in 0 until 8) Iso.run { c.disc(x1 - j * 3 * g - k * 3 * g, py + g, 1.6f, g, Pal.alpha(Iso.PLANES[ch], 0.7f - j * 0.08f)) }
            }
            if (ch.toString() != burying) {
                val spr = Iso.plane(ch, ((t * 18).toInt() % 2))
                c.spriteCentered(spr, px, py, g * 0.85f)
                if (Studio.armed[ch]) {
                    val on = tr.audioRec == Transport.Rec.RECORDING || (System.currentTimeMillis() / 300) % 2 == 0L
                    c.fill(Iso.snap(px - 4 * g, g), Iso.snap(py - 7 * g, g), Iso.snap(px - 2 * g, g), Iso.snap(py - 5 * g, g), if (on) 0xFFFF4A3A.toInt() else 0xFF7A2A22.toInt())
                    small.color = Pal.CREAM; c.textCentered("armed", px + 10 * g, py - 7 * g, small)
                }
            }
            hits += Hit(ch.toString(), K_PLANE, px, py, 13 * g)
        }
    }

    private fun drawTrail(c: Canvas, take: AudioClip, l: Float, r: Float, y: Float, g: Float, t: Float, ch: Int) {
        val muted = take.muted
        val vol = take.volume
        val playing = Studio.isPlaying(take.id)
        val tint = Iso.lighten(Iso.PLANES[ch], 0.75f)
        val a = if (muted) 0.25f else 0.35f + 0.6f * vol
        var x = l
        var k = 0
        val shift = if (playing) (t * 30f) % (3 * g) else 0f
        while (x < r) {
            val rad = 1.8f + 0.9f * sin((x + k * 13) * 0.07f)
            Iso.run { c.disc(x + shift.coerceAtMost(r - x), y, rad, g, Pal.alpha(if (k % 3 == 0) tint else 0xFFF7F3EA.toInt(), a)) }
            x += 3 * g; k++
        }
        if (playing) {
            // playhead running along the take
            val tr = Audio.transport
            val secs = take.seconds.coerceAtLeast(0.01f)
            val elapsed = ((tr.tick / Ticks.PPQ) * 60.0 / tr.bpm).toFloat()
            val p = (elapsed % secs) / secs
            c.fill(Iso.snap(l + (r - l) * p, g), Iso.snap(y - 3 * g, g), Iso.snap(l + (r - l) * p, g) + g, Iso.snap(y + 3 * g, g), Pal.ORANGE)
        }
        if (selected == take.id) { small.color = Pal.CREAM; c.textCentered(take.name, (l + r) / 2, y + 7 * g, small) }
    }

    // ------------------------------------------------------------------ daisy chains

    private fun drawChains(c: Canvas, w: Float, h: Float, g: Float, t: Float) {
        Studio.project.chains.forEachIndexed { k, chain -> tracer(c, chain.path.map { floatArrayOf(it[0] * w, it[1] * h) }, CHAIN[k % 3], g, t, 0.8f) }
        if (chainPath.size > 1) tracer(c, chainPath, CHAIN[Studio.chainIndex(chainFlower ?: "").takeIf { it >= 0 } ?: Studio.project.chains.size.coerceAtMost(2)], g, t, 1f)
    }

    /** A dotted line along the path with a bright tracer running from the flower. */
    private fun tracer(c: Canvas, pts: List<FloatArray>, col: Int, g: Float, t: Float, a: Float) {
        if (pts.size < 2) return
        var total = 0f
        for (i in 1 until pts.size) total += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        val head = (t * Px.dp(160f)) % total.coerceAtLeast(1f)
        var d = 0f; var next = 0f
        for (i in 1 until pts.size) {
            val ax = pts[i - 1][0]; val ay = pts[i - 1][1]; val bx = pts[i][0]; val by = pts[i][1]
            val len = hypot(bx - ax, by - ay)
            while (next <= d + len) {
                val f = if (len > 0) (next - d) / len else 0f
                val x = ax + (bx - ax) * f; val y = ay + (by - ay) * f
                val near = kotlin.math.abs(next - head) < g * 8
                c.fill(Iso.snap(x, g), Iso.snap(y, g), Iso.snap(x, g) + g * (if (near) 2 else 1), Iso.snap(y, g) + g * (if (near) 2 else 1), Pal.alpha(if (near) Pal.CREAM else col, a))
                next += g * 2.5f
            }
            d += len
        }
    }

    // ------------------------------------------------------------------ selection fader

    private val fader = FloatArray(4)
    private fun drawSelection(c: Canvas, w: Float, h: Float, g: Float) {
        fader.fill(0f)
        val id = selected ?: return
        val hit = hits.firstOrNull { it.id == id } ?: return
        val isFlower = hit.kind == K_FLOWER
        val value = if (isFlower) Studio.project.flowers.firstOrNull { it.id == id }?.bloom ?: 0f else Studio.volumeOf(id)
        val fh = Px.dp(84f)
        var fx = hit.x + hit.r + Px.dp(10f)
        if (fx > w - Px.dp(30f)) fx = hit.x - hit.r - Px.dp(24f)
        val top = (hit.y - fh / 2).coerceIn(Px.dp(8f), h - fh - Px.dp(8f))
        c.pixelBox(fx, top, fx + Px.dp(18f), top + fh, Pal.alpha(Pal.BROWN_D, 0.85f), border = Iso.INK, shade = false)
        val ky = top + fh - Px.dp(6f) - value * (fh - Px.dp(12f))
        c.fill(fx + Px.dp(6f), ky - (fh - Px.dp(12f)) * 0f, fx + Px.dp(12f), top + fh - Px.dp(6f), if (isFlower) Pal.ROSE else Pal.AQUA)
        c.pixelBox(fx - Px.dp(3f), ky - Px.dp(6f), fx + Px.dp(21f), ky + Px.dp(6f), Pal.CREAM, shade = false)
        small.color = Pal.CREAM
        c.textCentered(if (isFlower) "bloom" else "vol", fx + Px.dp(9f), top - Px.dp(8f), small)
        fader[0] = fx - Px.dp(14f); fader[1] = top - Px.dp(14f); fader[2] = fx + Px.dp(32f); fader[3] = top + fh + Px.dp(14f)
        faderTop = top + Px.dp(6f); faderH = fh - Px.dp(12f)
        val name = Studio.clipById(id)?.name ?: Studio.project.flowers.firstOrNull { it.id == id }?.name
        if (name != null) { small.color = Pal.CREAM; c.textCentered(name, hit.x, hit.y + hit.r + Px.dp(10f), small) }
    }
    private var faderTop = 0f; private var faderH = 1f

    // ------------------------------------------------------------------ HUD

    private fun drawHud(c: Canvas, w: Float, h: Float) {
        val m = Px.dp(10f)
        c.pixelBox(m, m, m + Px.dp(104f), m + Px.dp(40f), Pal.CREAM)
        hud.color = Pal.BROWN_D; c.textCentered("◀ Studio", m + Px.dp(52f), m + Px.dp(20f), hud)
        c.drawText("VALLEY", m + Px.dp(4f), m + Px.dp(62f), title.apply { textAlign = android.graphics.Paint.Align.LEFT })
        val t = Audio.transport
        val p = poem
        if (p != null && System.currentTimeMillis() - poemAt < 20_000) {
            val room = w - Px.dp(300f)
            val line = p.first.replace(" / ", " · ")
            poemP.textSize = Px.dp(13f)
            while (poemP.measureText(line) > room && poemP.textSize > Px.dp(8f)) poemP.textSize -= Px.dp(0.5f)
            c.textCentered(line, w / 2, m + Px.dp(12f), poemP)
            c.textCentered("— ${p.second}", w / 2, m + Px.dp(30f), poetP)
            return
        }
        val status = when {
            t.audioRec == Transport.Rec.ARMED -> "Count-in… ${t.beat + 1}"
            t.audioRec == Transport.Rec.RECORDING -> "Recording takes: tap the water tower to stop"
            Studio.sceneQueued -> "Next scene in ${Math.ceil(maxOf(0.0, (t.queuedAt - t.tick) / Ticks.PPQ)).toInt()} beats"
            t.playing -> "${t.bar + 1}.${t.beat + 1}  ·  ${Studio.project.bpm.toInt()} BPM"
            Studio.valleyClips.isEmpty() && Studio.valleyTakes.isEmpty() -> "Record clips in the studio, or add a biplane (+) and tap the water tower"
            else -> "Tap sprites to queue them, then tap the roof to play"
        }
        hud.color = Pal.CREAM
        c.textCentered(status, w / 2, m + Px.dp(18f), hud)
    }

    // ------------------------------------------------------------------ the little fox

    private val fox = Fox()
    private inner class Fox {
        var x = 0.55f; var y = 0.62f
        var tx = 0.55f; var ty = 0.62f
        var state = 0 // 0 walk, 1 sit, 2 scratch, 3 stare
        var until = 0f
        var flip = false
        private val spots = listOf(0.46f to 0.62f, 0.58f to 0.61f, 0.47f to 0.77f, 0.60f to 0.78f, 0.28f to 0.64f, 0.70f to 0.78f, 0.39f to 0.93f, 0.81f to 0.77f, 0.25f to 0.60f)
        fun draw(c: Canvas, w: Float, h: Float, t: Float) {
            val g = Px.u * 0.75f
            if (t > until) {
                state = if (state == 0) listOf(1, 2, 3).random() else 0
                if (state == 0) { val s = spots.random(); tx = s.first; ty = s.second }
                until = t + if (state == 0) 30f else 2.5f + Random.nextFloat() * 3f
            }
            if (state == 0) {
                val dx = tx - x; val dy = ty - y; val d = hypot(dx * w, dy * h)
                if (d < 3f) until = t
                else { val sp = Px.dp(18f) / 60f; x += dx * w / d * sp / w; y += dy * h / d * sp / h; flip = dx < 0 }
            }
            val sx = x * w; val sy = y * h
            val spr = when (state) {
                0 -> Iso.foxWalk[((t * 8).toInt()) % 2]
                1 -> Iso.foxSit
                2 -> Iso.foxScratch[((t * 10).toInt()) % 2]
                else -> Iso.foxStare
            }
            Iso.run { c.shadow(sx, sy, 4f, g) }
            c.sprite(spr, sx - spr.w * g / 2, sy - spr.h * g, g, flip = state != 3 && flip)
        }
    }

    // ------------------------------------------------------------------ touch

    private var downHit: Hit? = null
    private var downX = 0f; private var downY = 0f; private var curX = 0f; private var curY = 0f
    private var moved = false
    private var burying: String? = null
    private var buryKind = 0
    private val stone = FloatArray(4)
    private fun overStone() = curX in stone[0]..stone[2] && curY in stone[1]..stone[3]
    private var selected: String? = null
    private var faderDrag = false
    private var chainFlower: String? = null
    private val chainPath = ArrayList<FloatArray>()
    private val chainClips = LinkedHashSet<String>()
    private var lastTapId: String? = null
    private var lastTapAt = 0L
    private var pendingTap: Runnable? = null

    private val longPress = Runnable {
        val hh = downHit ?: return@Runnable
        if (moved || hh.kind !in listOf(K_CLIP, K_TAKE, K_FLOWER, K_PLANE)) return@Runnable
        burying = hh.id; buryKind = hh.kind; selected = null
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun hitAt(x: Float, y: Float): Hit? {
        // Smallest match wins so things in front are easy to grab.
        var best: Hit? = null
        for (hh in hits) {
            val d = hypot(x - hh.x, y - hh.y)
            if (d <= hh.r + Px.dp(6f) && (best == null || hh.r < best.r)) best = hh
        }
        if (best == null) for (i in trailHits.indices) { val b = trailHits[i]; if (x in b[0]..b[2] && y in b[1]..b[3]) return Hit(trailIds[i], K_TAKE, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2, 10f) }
        return best
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; curX = x; curY = y; moved = false
                val m = Px.dp(10f)
                if (x < m + Px.dp(104f) && y < m + Px.dp(40f) + m) { Studio.save(); backToStudio?.invoke(); return true }
                if (selected != null && x in fader[0]..fader[2] && y in fader[1]..fader[3]) { faderDrag = true; setFader(y); return true }
                downHit = hitAt(x, y)
                if (downHit != null) postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                curX = x; curY = y
                if (faderDrag) { setFader(y); return true }
                if (!moved && hypot(x - downX, y - downY) > Px.dp(10f)) {
                    moved = true
                    if (burying == null) removeCallbacks(longPress)
                    val hh = downHit
                    if (burying == null && hh != null && hh.kind == K_FLOWER) {
                        chainFlower = hh.id; chainPath.clear(); chainClips.clear()
                        chainPath += floatArrayOf(hh.x, hh.y)
                    }
                }
                if (chainFlower != null) {
                    val last = chainPath.last()
                    if (hypot(x - last[0], y - last[1]) > Px.dp(6f)) chainPath += floatArrayOf(x, y)
                    hitAt(x, y)?.let { if (it.kind == K_CLIP || it.kind == K_TAKE) chainClips += it.id }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val b = burying
                when {
                    faderDrag -> { faderDrag = false; Studio.save() }
                    b != null -> {
                        if (overStone()) { if (buryKind == K_PLANE) Studio.removePlane(b.toInt()) else Studio.bury(b) }
                        burying = null
                    }
                    chainFlower != null -> {
                        val w = width.toFloat(); val h = height.toFloat()
                        Studio.makeChain(chainFlower!!, chainClips.toList(), chainPath.map { floatArrayOf(it[0] / w, it[1] / h) })
                        chainFlower = null; chainPath.clear(); chainClips.clear()
                    }
                    !moved -> tap(downHit)
                }
                downHit = null
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); burying = null; chainFlower = null; chainPath.clear(); faderDrag = false; downHit = null }
        }
        return true
    }

    private fun setFader(y: Float) {
        val id = selected ?: return
        val v = (1f - (y - faderTop) / faderH).coerceIn(0f, 1f)
        val f = Studio.project.flowers.firstOrNull { it.id == id }
        if (f != null) Studio.setBloom(f, v) else Studio.setVolume(id, v)
    }

    private fun tap(hh: Hit?) {
        if (hh == null) { selected = null; return }
        when (hh.kind) {
            K_ROOF -> Studio.playScene()
            K_WALL -> Studio.stopScene()
            K_TOWER -> Studio.recordAudio()
            K_PLUS -> Studio.addPlane()
            K_PLANE -> Studio.toggleArm(hh.id.toInt())
            K_CABIN -> { poem = Poems.random(); poemAt = System.currentTimeMillis() }
            K_CLIP, K_TAKE, K_FLOWER -> {
                val now = System.currentTimeMillis()
                val double = lastTapId == hh.id && now - lastTapAt < 300
                pendingTap?.let { removeCallbacks(it) }; pendingTap = null
                if (double) {
                    lastTapId = null
                    if (hh.kind == K_FLOWER) { Studio.clearChain(hh.id); Studio.message("Daisy chain undone") } else Studio.toggleMute(hh.id)
                    return
                }
                lastTapId = hh.id; lastTapAt = now
                val single = Runnable { pendingTap = null; selected = hh.id; if (hh.kind != K_FLOWER) Studio.toggleQueued(hh.id) }
                pendingTap = single
                postDelayed(single, 260)
            }
        }
    }

    override fun onDetachedFromWindow() { pendingTap?.let { removeCallbacks(it) }; removeCallbacks(longPress); super.onDetachedFromWindow() }

    companion object {
        const val K_CLIP = 0; const val K_TAKE = 1; const val K_FLOWER = 2; const val K_PLANE = 3
        const val K_ROOF = 4; const val K_WALL = 5; const val K_TOWER = 6; const val K_CABIN = 7; const val K_PLUS = 8
        /** Daisy-chain tracer colours: red, yellow, blue. */
        val CHAIN = intArrayOf(0xFFE0533F.toInt(), 0xFFF0C23B.toInt(), 0xFF4A86D8.toInt())
    }
}
