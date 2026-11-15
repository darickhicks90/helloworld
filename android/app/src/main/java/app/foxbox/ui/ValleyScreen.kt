package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.seq.AudioClip
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport
import kotlin.math.hypot

/** What the clip launcher shows (built from the Studio every frame). */
class LaunchModel(
    val clips: List<Box>,
    val channels: List<Channel>,
    val flowers: List<FlowerSlot>,
    val chains: List<List<FloatArray>>,
    val playing: Boolean,
    val sceneQueued: Boolean,
    val armedAny: Boolean,
    val audioRec: Transport.Rec,
    val beat: Int,
    val status: String,
    val canAddChannel: Boolean,
    /** Shuffle seed: where things lie. */
    val seed: Int = 0,
    /** A line of poetry and its poet, shown at the top while set. */
    val quote: Pair<String, String>? = null,
) {
    class Box(val id: String, val name: String, val block: String, val accent: Int, val queued: Boolean, val playing: Boolean, val muted: Boolean, val volume: Float)
    class Channel(val ch: Int, val name: String, val accent: Int, val armed: Boolean, val takes: List<Box>)
    class FlowerSlot(val id: String, val name: String, val variant: Int, val bloom: Float, val chain: Int)
}

/**
 * Draws the clip launcher: plain black boxes with white text over close-up
 * grass. MIDI clips are scattered irregularly (each keeps its place); audio
 * takes sit in tidy rows, one per channel; planted flowers grow along the
 * bottom. Records hit areas for touch.
 */
object ClipField {
    const val K_CLIP = 0; const val K_TAKE = 1; const val K_FLOWER = 2; const val K_CHANNEL = 3
    const val K_PLAY = 4; const val K_STOP = 5; const val K_REC = 6; const val K_BACK = 7; const val K_PLUS = 8
    const val K_SHUFFLE = 9; const val K_POEM = 10
    const val POEM_ID = "poem-stone"
    /** Daisy-chain tracer colours: red, yellow, blue. */
    val CHAIN = intArrayOf(0xFFE0533F.toInt(), 0xFFF0C23B.toInt(), 0xFF4A8FD8.toInt())
    private const val BLACK = 0xF20B0B0B.toInt()
    private const val EDGE = 0xFF3A3A3A.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREY = 0xFFA8A8A8.toInt()
    private const val QUEUED = 0xFF3FB8AF.toInt()
    private const val PLAYING = 0xFFE8742C.toInt()

    class Hit(val id: String, val kind: Int, val l: Float, val t: Float, val r: Float, val b: Float) {
        val cx get() = (l + r) / 2; val cy get() = (t + b) / 2
        fun has(x: Float, y: Float) = x in l..r && y in t..b
    }

    private val txt by lazy { Px.text(13f, WHITE) }
    private val sub by lazy { Px.text(9f, GREY) }
    private val big by lazy { Px.text(14f, WHITE) }

    /** Where each clip box lies (kept between frames so boxes don't jump). */
    private class Spot(val x: Float, val y: Float, val w: Float, val h: Float, val tilt: Float)
    private val spots = HashMap<String, Spot>()
    private var spotSig = ""

    private var seed = 0
    private fun hash(s: String, k: Int): Float { var x = (s.hashCode() xor (seed * 0x27d4eb2d)) * 31 + k * 977; x = x xor (x ushr 13); x *= 0x5bd1e995; x = x xor (x ushr 15); return Math.floorMod(x, 10000) / 10000f }

    fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, alpha: Float = 1f, edge: Int = EDGE) {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val u = Px.u * 0.5f
        c.fill(l + u, t, r - u, b, Pal.alpha(BLACK, a / 255f * 0.95f))
        c.fill(l, t + u, l + u, b - u, Pal.alpha(BLACK, a / 255f * 0.95f)); c.fill(r - u, t + u, r, b - u, Pal.alpha(BLACK, a / 255f * 0.95f))
        // one-pixel border with notched corners
        c.fill(l + u, t, r - u, t + u, Pal.alpha(edge, a / 255f)); c.fill(l + u, b - u, r - u, b, Pal.alpha(edge, a / 255f))
        c.fill(l, t + u, l + u, b - u, Pal.alpha(edge, a / 255f)); c.fill(r - u, t + u, r, b - u, Pal.alpha(edge, a / 255f))
    }

    private fun label(c: Canvas, s: String, cx: Float, cy: Float, p: Paint, color: Int, maxW: Float) {
        p.color = color
        val base = p.textSize
        while (p.measureText(s) > maxW && p.textSize > Px.dp(7f)) p.textSize -= Px.dp(0.5f)
        c.textCentered(s, cx, cy, p)
        p.textSize = base
    }

    fun draw(c: Canvas, w: Float, h: Float, m: LaunchModel, time: Float, selected: String?, hits: MutableList<Hit>) {
        hits.clear()
        Grass.draw(c, w.toInt(), h.toInt(), Px.u * 0.5f)
        val pad = Px.dp(8f)
        val blink = (time * 3).toInt() % 2 == 0

        // ---- top bar: back, transport, + audio, status
        val topH = Px.dp(42f)
        var x = pad
        fun top(id: String, kind: Int, text: String, wd: Float, color: Int, lit: Boolean = false) {
            box(c, x, pad, x + wd, pad + topH, 1f, if (lit) color else EDGE)
            label(c, text, x + wd / 2, pad + topH / 2, big, if (lit) color else WHITE, wd - Px.dp(10f))
            hits += Hit(id, kind, x, pad, x + wd, pad + topH)
            x += wd + Px.dp(6f)
        }
        top("back", K_BACK, "◀", Px.dp(46f), WHITE)
        top("play", K_PLAY, "▶ Play", Px.dp(96f), QUEUED, m.playing || (m.sceneQueued && blink))
        top("stop", K_STOP, "■ Stop", Px.dp(96f), WHITE)
        val recLit = m.audioRec == Transport.Rec.RECORDING || ((m.audioRec == Transport.Rec.ARMED || m.armedAny) && blink)
        top("rec", K_REC, if (m.audioRec == Transport.Rec.ARMED) "● ${m.beat}" else "● Rec", Px.dp(96f), 0xFFFF4A3A.toInt(), recLit)
        if (m.canAddChannel) top("plus", K_PLUS, "+ Audio", Px.dp(96f), WHITE)
        top("shuffle", K_SHUFFLE, "⤨ Shuffle", Px.dp(110f), WHITE)
        val sr = w - pad
        if (sr - x > Px.dp(80f)) {
            box(c, x, pad, sr, pad + topH, 0.85f)
            label(c, m.status, (x + sr) / 2, pad + topH / 2, txt, WHITE, sr - x - Px.dp(16f))
        }

        // ---- audio rows along the bottom; everything else is scattered over the grass above
        val rowH = Px.dp(36f); val rowGap = Px.dp(5f)
        val audioH = if (m.channels.isEmpty()) 0f else m.channels.size * (rowH + rowGap)
        val audioTop = h - pad - audioH
        val fieldTop = pad + topH + Px.dp(10f); val fieldBottom = h - pad
        // the audio rows' corner is kept clear; the grass beside them is fair game
        val audioRect = if (audioH > 0f) floatArrayOf(0f, audioTop - Px.dp(8f), w * 0.58f, h) else null
        val items = ArrayList<Triple<String, Float, Float>>()
        m.clips.forEach { items += Triple(it.id, Px.dp(124f + hash(it.id, 1) * 40f), Px.dp(46f + hash(it.id, 2) * 10f)) }
        m.flowers.forEach { items += Triple(it.id, Px.dp(66f), Px.dp(108f)) }
        items += Triple(POEM_ID, Px.dp(128f), Px.dp(96f))
        val sig = "${w.toInt()}x${h.toInt()}:${audioTop.toInt()}:${m.seed}:" + items.joinToString(",") { it.first }
        if (sig != spotSig) { seed = m.seed; place(items, pad, fieldTop, w - pad, fieldBottom, audioRect); spotSig = sig }

        // the poetry stone
        spots[POEM_ID]?.let { sp ->
            PoemStone.draw(c, sp.x, sp.y, sp.w, sp.h, Px.u * 0.4f)
            hits += Hit(POEM_ID, K_POEM, sp.x, sp.y, sp.x + sp.w, sp.y + sp.h)
        }
        // flowers growing in the grass, with blades in front of their stems
        for (f in m.flowers) {
            val sp = spots[f.id] ?: continue
            val fx = sp.x + sp.w / 2; val fy = sp.y + sp.h * 0.24f
            c.flower(fx, fy, 1.45f, f.bloom, f.variant, time, f.chain >= 0 && m.playing)
            Grass.tuft(c, fx, sp.y + sp.h, Px.u * 0.5f, f.id.hashCode())
            if (f.chain >= 0) c.fill(fx - Px.dp(10f), sp.y + sp.h + Px.dp(2f), fx + Px.dp(10f), sp.y + sp.h + Px.dp(5f), CHAIN[f.chain])
            if (selected == f.id) { box(c, fx - Px.dp(44f), sp.y + sp.h + Px.dp(7f), fx + Px.dp(44f), sp.y + sp.h + Px.dp(25f), 0.9f); label(c, f.name, fx, sp.y + sp.h + Px.dp(16f), sub, WHITE, Px.dp(80f)) }
            hits += Hit(f.id, K_FLOWER, sp.x, sp.y, sp.x + sp.w, sp.y + sp.h)
        }

        // ---- MIDI clips: scattered, irregular, each keeps its spot until you shuffle
        for (b in m.clips) {            val s = spots[b.id] ?: continue
            val alpha = if (b.muted) 0.35f else 0.45f + 0.55f * b.volume
            val pulse = if (b.playing && blink) 1f else 0f
            c.save(); c.rotate(s.tilt, s.x + s.w / 2, s.y + s.h / 2)
            box(c, s.x, s.y - pulse, s.x + s.w, s.y + s.h - pulse, alpha, if (selected == b.id) WHITE else if (b.playing) PLAYING else if (b.queued) QUEUED else EDGE)
            // the block's colour as a small square, the clip name, the block name
            c.fill(s.x + Px.dp(8f), s.y + Px.dp(8f), s.x + Px.dp(14f), s.y + Px.dp(14f), Pal.alpha(b.accent, alpha))
            val ta = maxOf(alpha, 0.75f)
            label(c, b.name, s.x + s.w / 2 + Px.dp(4f), s.y + s.h * 0.42f, txt, Pal.alpha(WHITE, ta), s.w - Px.dp(28f))
            label(c, b.block.uppercase() + if (b.muted) " · MUTED" else "", s.x + s.w / 2 + Px.dp(4f), s.y + s.h * 0.75f, sub, Pal.alpha(GREY, ta), s.w - Px.dp(28f))
            if (b.queued || b.playing) c.fill(s.x + Px.dp(3f), s.y + Px.dp(6f), s.x + Px.dp(5f), s.y + s.h - Px.dp(6f), if (b.playing) PLAYING else QUEUED)
            c.restore()
            hits += Hit(b.id, K_CLIP, s.x, s.y, s.x + s.w, s.y + s.h)
        }
        if (m.clips.isEmpty()) label(c, "Record clips in the studio and they'll land here", w / 2, (fieldTop + fieldBottom) / 2, txt, WHITE, w - pad * 4)

        // ---- audio takes: tidy rows, one per channel
        for ((i, ch) in m.channels.withIndex()) {
            val t = audioTop + i * (rowH + rowGap)
            val hw = Px.dp(96f)
            box(c, pad, t, pad + hw, t + rowH, 1f, if (ch.armed) ch.accent else EDGE)
            val lit = ch.armed && (m.audioRec == Transport.Rec.RECORDING || blink)
            with(Iso) { c.disc(pad + Px.dp(14f), t + rowH / 2, 2.5f, Px.u * 0.8f, if (lit) ch.accent else Iso.darken(ch.accent, 0.45f)) }
            label(c, ch.name + if (ch.armed) " ●" else "", pad + hw / 2 + Px.dp(8f), t + rowH / 2, txt, WHITE, hw - Px.dp(30f))
            hits += Hit(ch.ch.toString(), K_CHANNEL, pad, t, pad + hw, t + rowH)
            var tx = pad + hw + Px.dp(6f)
            val tw = Px.dp(118f)
            for (tk in ch.takes) {
                if (tx + tw > w - pad) break
                val alpha = if (tk.muted) 0.35f else 0.45f + 0.55f * tk.volume
                box(c, tx, t, tx + tw, t + rowH, alpha, if (selected == tk.id) WHITE else if (tk.playing) PLAYING else if (tk.queued) QUEUED else EDGE)
                if (tk.queued || tk.playing) c.fill(tx + Px.dp(3f), t + Px.dp(6f), tx + Px.dp(5f), t + rowH - Px.dp(6f), if (tk.playing) PLAYING else QUEUED)
                label(c, tk.name + if (tk.muted) " (muted)" else "", tx + tw / 2, t + rowH / 2, txt, Pal.alpha(WHITE, maxOf(alpha, 0.75f)), tw - Px.dp(14f))
                hits += Hit(tk.id, K_TAKE, tx, t, tx + tw, t + rowH)
                tx += tw + Px.dp(6f)
            }
            if (ch.takes.isEmpty()) label(c, "arm it, then ● Rec", tx + Px.dp(70f), t + rowH / 2, sub, GREY, Px.dp(140f))
        }

        // ---- daisy chains
        for ((k, path) in m.chains.withIndex()) tracer(c, path.map { floatArrayOf(it[0] * w, it[1] * h) }, CHAIN[k % 3], Px.u * 0.8f, time)
        // ---- a line of poetry from the stone, in white across the top
        m.quote?.let { (line, poet) ->
            val qt = pad + topH + Px.dp(6f)
            box(c, pad, qt, w - pad, qt + Px.dp(40f), 0.9f)
            label(c, line.replace(" / ", "  ·  "), w / 2, qt + Px.dp(14f), txt, WHITE, w - pad * 4)
            label(c, "— $poet", w / 2, qt + Px.dp(31f), sub, WHITE, w - pad * 4)
        }

    }

    /**
     * Scatter items over the field: for each one, try many random spots and
     * keep the one furthest from everything already placed (spread out, but
     * random), never overlapping when there's room. Same seed, same layout.
     */
    private fun place(items: List<Triple<String, Float, Float>>, l: Float, t: Float, r: Float, b: Float, keepClear: FloatArray?) {
        spots.clear()
        val placed = ArrayList<FloatArray>()
        val blocked = listOfNotNull(keepClear)
        val gap = Px.dp(12f)
        for ((id, bw, bh) in items) {
            fun free(cd: FloatArray) = (placed + blocked).none { it[0] < cd[2] + gap && cd[0] < it[2] + gap && it[1] < cd[3] + gap && cd[1] < it[3] + gap }
            fun clearance(cd: FloatArray): Float {
                var best = Float.MAX_VALUE
                val cx = (cd[0] + cd[2]) / 2; val cy = (cd[1] + cd[3]) / 2
                for (p in placed) best = minOf(best, hypot(cx - (p[0] + p[2]) / 2, cy - (p[1] + p[3]) / 2))
                // the field's edges count too, so things don't hug them
                best = minOf(best, (cx - l) * 1.6f, (r - cx) * 1.6f, (cy - t) * 1.6f, (b - cy) * 1.6f)
                return best
            }
            var best: FloatArray? = null; var bestScore = -1f
            for (k in 0 until 40) {
                val x = l + hash(id, 10 + k) * (r - l - bw).coerceAtLeast(1f)
                val y = t + hash(id, 90 + k) * (b - t - bh).coerceAtLeast(1f)
                val cd = floatArrayOf(x, y, x + bw, y + bh)
                val score = clearance(cd) + if (free(cd)) 100000f else 0f
                if (score > bestScore) { best = cd; bestScore = score }
            }
            if (best != null && !free(best)) {
                val step = Px.dp(12f)
                var y = t
                search@ while (y + bh <= b) {
                    var x = l
                    while (x + bw <= r) { val cd = floatArrayOf(x, y, x + bw, y + bh); if (free(cd)) { best = cd; break@search }; x += step }
                    y += step
                }
            }
            val s = best ?: continue
            placed += s
            spots[id] = Spot(s[0], s[1], bw, bh, if (id.length == 8) (hash(id, 3) - 0.5f) * 7f else 0f)
        }
    }

    fun tracer(c: Canvas, pts: List<FloatArray>, col: Int, g: Float, t: Float) {
        if (pts.size < 2) return
        var total = 0f
        for (i in 1 until pts.size) total += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        val headPos = (t * Px.dp(160f)) % total.coerceAtLeast(1f)
        var d = 0f; var next = 0f
        for (i in 1 until pts.size) {
            val ax = pts[i - 1][0]; val ay = pts[i - 1][1]; val bx = pts[i][0]; val by = pts[i][1]
            val len = hypot(bx - ax, by - ay)
            while (next <= d + len) {
                val f = if (len > 0) (next - d) / len else 0f
                val x = ax + (bx - ax) * f; val y = ay + (by - ay) * f
                val near = kotlin.math.abs(next - headPos) < g * 8
                val s = if (near) 2 else 1
                c.fill(Iso.snap(x, g), Iso.snap(y, g), Iso.snap(x, g) + g * s, Iso.snap(y, g) + g * s, if (near) WHITE else col)
                next += g * 2.5f
            }
            d += len
        }
    }
}

/**
 * The clip launcher (opened from the rail). Black boxes over close-up grass:
 *  - Tap a box to queue it (and select it: drag its fader for volume).
 *    Double-tap to mute.
 *  - ▶ Play starts the queued clips (when the current loop comes round),
 *    ■ Stop stops, ● Rec counts in two bars and records the armed channels.
 *  - + Audio adds a channel; tap a channel's name box to arm it.
 *  - Drag a line from a flower through boxes to make a daisy chain (up to 3).
 *  - Hold a box until it clicks, then drag it to R.I.P. to lay it to rest.
 */
@SuppressLint("ViewConstructor")
class ValleyScreen(c: Context, private val host: Host) : PixelView(c) {
    var backToStudio: (() -> Unit)? = null
    private val t0 = System.nanoTime()
    private val hits = ArrayList<ClipField.Hit>()
    private val ripP = Px.text(11f, Pal.CREAM, headFont = true)
    private val small = Px.text(10f, 0xFFFFFFFF.toInt())

    init { animated = true }
    private var poem: Pair<String, String>? = null
    private var poemAt = 0L

    private fun model(): LaunchModel {
        val t = Audio.transport
        val p = Studio.project
        val clips = Studio.valleyClips.map { cl ->
            LaunchModel.Box(cl.id, cl.name, Studio.engine(cl.engine).title, Pal.accent(cl.engine), Studio.isQueued(cl.id), Studio.isPlaying(cl.id), cl.muted, cl.volume)
        }
        val channels = (0 until AudioClip.CHANNELS).filter { p.planes[it] }.map { ch ->
            LaunchModel.Channel(ch, Studio.CHANNEL_NAMES[ch], Iso.PLANES[ch], Studio.armed[ch], Studio.audioClipsOn(ch).map { a ->
                LaunchModel.Box(a.id, a.name, "audio", Iso.PLANES[ch], Studio.isQueued(a.id), Studio.isPlaying(a.id), a.muted, a.volume)
            })
        }
        val flowers = Studio.valleyFlowers.map { f -> LaunchModel.FlowerSlot(f.id, f.name, f.variant, f.bloom, Studio.chainIndex(f.id)) }
        val chains = p.chains.map { it.path.toList() } + (if (chainPath.size > 1) listOf(chainPath.map { floatArrayOf(it[0] / width, it[1] / height) }) else emptyList())
        val status = when {
            t.audioRec == Transport.Rec.ARMED -> "Count-in… ${t.beat + 1}"
            t.audioRec == Transport.Rec.RECORDING -> "Recording: tap ● Rec to stop"
            Studio.sceneQueued -> "Next scene in ${Math.ceil(maxOf(0.0, (t.queuedAt - t.tick) / Ticks.PPQ)).toInt()} beats"
            t.playing -> "${t.bar + 1}.${t.beat + 1}  ·  ${p.bpm.toInt()} BPM"
            clips.isEmpty() && channels.isEmpty() -> "Record clips in the studio, or tap + Audio"
            else -> "Tap clips to queue them, then ▶ Play"
        }
        val q = poem.takeIf { System.currentTimeMillis() - poemAt < 20_000 }
        return LaunchModel(clips, channels, flowers, chains, t.playing, Studio.sceneQueued, Studio.armed.any { it }, t.audioRec, (t.beat + 1).toInt(), status, p.planes.any { !it }, p.layoutSeed, q)
    }

    override fun paint(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val time = (System.nanoTime() - t0) / 1e9f
        ClipField.draw(c, w, h, model(), time, selected, hits)
        drawFader(c, w, h)
        if (burying != null) {
            val sh = minOf(h / 3.2f, Px.dp(190f))
            stone[0] = w / 2 - sh * 0.6f; stone[1] = h - Px.dp(8f) - sh * 1.1f; stone[2] = w / 2 + sh * 0.6f; stone[3] = h - Px.dp(8f)
            with(Iso) { c.tombstone(w / 2, h - Px.dp(8f), sh, overStone(), ripP) }
            c.dot(curX - Px.u, curY - Px.u, 0xFFFFFFFF.toInt(), 3)
        }
    }

    // ------------------------------------------------------------------ selection fader

    private val fader = FloatArray(4)
    private var faderTop = 0f; private var faderH = 1f
    private fun drawFader(c: Canvas, w: Float, h: Float) {
        fader.fill(0f)
        val id = selected ?: return
        val hit = hits.firstOrNull { it.id == id && (it.kind == ClipField.K_CLIP || it.kind == ClipField.K_TAKE || it.kind == ClipField.K_FLOWER) } ?: return
        val isFlower = hit.kind == ClipField.K_FLOWER
        val value = if (isFlower) Studio.project.flowers.firstOrNull { it.id == id }?.bloom ?: 0f else Studio.volumeOf(id)
        val fh = Px.dp(96f)
        var fx = hit.r + Px.dp(6f)
        if (fx > w - Px.dp(30f)) fx = hit.l - Px.dp(28f)
        val top = (hit.cy - fh / 2).coerceIn(Px.dp(8f), h - fh - Px.dp(8f))
        ClipField.box(c, fx - Px.dp(4f), top - Px.dp(6f), fx + Px.dp(22f), top + fh + Px.dp(6f), 1f, 0xFFFFFFFF.toInt())
        c.fill(fx + Px.dp(7f), top + Px.dp(6f), fx + Px.dp(11f), top + fh - Px.dp(6f), 0xFF3A3A3A.toInt())
        val ky = top + fh - Px.dp(6f) - value * (fh - Px.dp(12f))
        c.fill(fx + Px.dp(7f), ky, fx + Px.dp(11f), top + fh - Px.dp(6f), if (isFlower) Pal.ROSE else Pal.AQUA)
        c.fill(fx - Px.dp(1f), ky - Px.dp(4f), fx + Px.dp(19f), ky + Px.dp(4f), 0xFFFFFFFF.toInt())
        small.color = 0xFFFFFFFF.toInt()
        ClipField.box(c, fx - Px.dp(8f), top - Px.dp(26f), fx + Px.dp(26f), top - Px.dp(10f), 1f)
        c.textCentered(if (isFlower) "bloom" else "vol", fx + Px.dp(9f), top - Px.dp(18f), small)
        fader[0] = fx - Px.dp(14f); fader[1] = top - Px.dp(14f); fader[2] = fx + Px.dp(32f); fader[3] = top + fh + Px.dp(14f)
        faderTop = top + Px.dp(6f); faderH = fh - Px.dp(12f)
    }

    // ------------------------------------------------------------------ touch

    private var downHit: ClipField.Hit? = null
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
        if (moved || hh.kind !in listOf(ClipField.K_CLIP, ClipField.K_TAKE, ClipField.K_FLOWER, ClipField.K_CHANNEL)) return@Runnable
        burying = hh.id; buryKind = hh.kind; selected = null
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun hitAt(x: Float, y: Float) = hits.lastOrNull { it.has(x, y) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; curX = x; curY = y; moved = false
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
                    if (burying == null && hh != null && hh.kind == ClipField.K_FLOWER) {
                        chainFlower = hh.id; chainPath.clear(); chainClips.clear()
                        chainPath += floatArrayOf(hh.cx, hh.cy)
                    }
                }
                if (chainFlower != null) {
                    val last = chainPath.last()
                    if (hypot(x - last[0], y - last[1]) > Px.dp(6f)) chainPath += floatArrayOf(x, y)
                    hitAt(x, y)?.let { if (it.kind == ClipField.K_CLIP || it.kind == ClipField.K_TAKE) chainClips += it.id }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val b = burying
                when {
                    faderDrag -> { faderDrag = false; Studio.save() }
                    b != null -> {
                        if (overStone()) { if (buryKind == ClipField.K_CHANNEL) Studio.removePlane(b.toInt()) else Studio.bury(b) }
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

    private fun tap(hh: ClipField.Hit?) {
        if (hh == null) { selected = null; return }
        when (hh.kind) {
            ClipField.K_BACK -> { Studio.save(); backToStudio?.invoke() }
            ClipField.K_PLAY -> Studio.playScene()
            ClipField.K_STOP -> Studio.stopScene()
            ClipField.K_REC -> Studio.recordAudio()
            ClipField.K_PLUS -> Studio.addPlane()
            ClipField.K_SHUFFLE -> { selected = null; Studio.shuffleLayout() }
            ClipField.K_POEM -> { poem = Poems.random(); poemAt = System.currentTimeMillis() }
            ClipField.K_CHANNEL -> Studio.toggleArm(hh.id.toInt())
            else -> {
                val now = System.currentTimeMillis()
                val double = lastTapId == hh.id && now - lastTapAt < 300
                pendingTap?.let { removeCallbacks(it) }; pendingTap = null
                if (double) {
                    lastTapId = null
                    if (hh.kind == ClipField.K_FLOWER) { Studio.clearChain(hh.id); Studio.message("Daisy chain undone") } else Studio.toggleMute(hh.id)
                    return
                }
                lastTapId = hh.id; lastTapAt = now
                val single = Runnable { pendingTap = null; selected = hh.id; if (hh.kind != ClipField.K_FLOWER) Studio.toggleQueued(hh.id) }
                pendingTap = single
                postDelayed(single, 260)
            }
        }
    }

    override fun onDetachedFromWindow() { pendingTap?.let { removeCallbacks(it) }; removeCallbacks(longPress); super.onDetachedFromWindow() }
}
