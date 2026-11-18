package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.audio.AudioPrefs
import app.foxbox.seq.AudioClip
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10

/** What Clibbins shows (built from the Studio every frame). */
class LaunchModel(
    /** MIDI clips and audio takes, all scattered over the grass. */
    val clips: List<Box>,
    val strips: List<Strip>,
    val flowers: List<FlowerSlot>,
    /** Daisy chains: flower id → the clips/takes it runs through (redrawn from where they lie). */
    val chains: List<Pair<String, List<String>>>,
    /** The chain being drawn right now (screen points). */
    val drawing: List<FloatArray>,
    val playing: Boolean,
    val sceneQueued: Boolean,
    val armedAny: Boolean,
    val audioRec: Transport.Rec,
    val beat: Int,
    val status: String,
    val seed: Int = 0,
    val quote: Pair<String, String>? = null,
    val master: Float = 0.75f,
    val masterPeak: Float = 0f,
    val masterArmed: Boolean = false,
    val taping: Boolean = false,
) {
    /** A clip box. `wave` is set for audio takes: the box is drawn in the shape of its waveform. */
    class Box(val id: String, val name: String, val block: String, val accent: Int, val queued: Boolean, val playing: Boolean, val muted: Boolean, val volume: Float,
              val wave: FloatArray? = null)
    class Strip(val ch: Int, val name: String, val accent: Int, val armed: Boolean, val peak: Float, val input: String)
    class FlowerSlot(val id: String, val name: String, val variant: Int, val bloom: Float, val chain: Int)
}

/**
 * Draws Clibbins: black boxes with white text over grass. MIDI
 * clips and audio takes (drawn as their waveforms in their track's colour)
 * are scattered irregularly; the small mixer in the corner has a vertical
 * strip per audio track plus the master. Records hit areas for touch.
 */
object ClipField {
    const val K_CLIP = 0; const val K_TAKE = 1; const val K_FLOWER = 2; const val K_ARM = 3
    const val K_PLAY = 4; const val K_STOP = 5; const val K_REC = 6; const val K_BACK = 7; const val K_INPUT = 8
    const val K_SHUFFLE = 9; const val K_POEM = 10; const val K_MASTER = 11; const val K_MASTER_ARM = 12
    const val POEM_ID = "poem-stone"
    /** Daisy-chain tracer colours: red, yellow, blue. */
    val CHAIN = intArrayOf(0xFFE0533F.toInt(), 0xFFF0C23B.toInt(), 0xFF4A8FD8.toInt())
    private const val BLACK = 0xF20B0B0B.toInt()
    private const val EDGE = 0xFF3A3A3A.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREY = 0xFFA8A8A8.toInt()
    private const val QUEUED = 0xFF3FB8AF.toInt()
    private const val PLAYING = 0xFFE8742C.toInt()
    private const val REC = 0xFFFF4A3A.toInt()

    /** The master fader's groove (l, t, r, b), for dragging. */
    val masterLane = FloatArray(4)
    /** Centre of every clip, take and flower this frame, for routing daisy chains. */
    private val centres = HashMap<String, FloatArray>()

    class Hit(val id: String, val kind: Int, val l: Float, val t: Float, val r: Float, val b: Float) {
        val cx get() = (l + r) / 2; val cy get() = (t + b) / 2
        fun has(x: Float, y: Float) = x in l..r && y in t..b
    }

    private val txt by lazy { Px.text(12f, WHITE) }
    private val sub by lazy { Px.text(9f, GREY) }
    private val big by lazy { Px.text(14f, WHITE) }

    private class Spot(val x: Float, val y: Float, val w: Float, val h: Float, val tilt: Float)
    private val spots = HashMap<String, Spot>()
    private var spotSig = ""
    private var seed = 0
    private fun hash(s: String, k: Int): Float { var x = (s.hashCode() xor (seed * 0x27d4eb2d)) * 31 + k * 977; x = x xor (x ushr 13); x *= 0x5bd1e995; x = x xor (x ushr 15); return Math.floorMod(x, 10000) / 10000f }

    fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, alpha: Float = 1f, edge: Int = EDGE, fill: Int = BLACK) {
        val a = alpha.coerceIn(0f, 1f)
        val u = Px.u * 0.5f
        c.fill(l + u, t, r - u, b, Pal.alpha(fill, a * 0.95f))
        c.fill(l, t + u, l + u, b - u, Pal.alpha(fill, a * 0.95f)); c.fill(r - u, t + u, r, b - u, Pal.alpha(fill, a * 0.95f))
        c.fill(l + u, t, r - u, t + u, Pal.alpha(edge, a)); c.fill(l + u, b - u, r - u, b, Pal.alpha(edge, a))
        c.fill(l, t + u, l + u, b - u, Pal.alpha(edge, a)); c.fill(r - u, t + u, r, b - u, Pal.alpha(edge, a))
    }

    fun label(c: Canvas, s: String, cx: Float, cy: Float, p: Paint, color: Int, maxW: Float, shadow: Boolean = false) {
        val base = p.textSize
        while (p.measureText(s) > maxW && p.textSize > Px.dp(6f)) p.textSize -= Px.dp(0.5f)
        if (shadow) { p.color = 0xE0000000.toInt(); c.textCentered(s, cx + Px.dp(1f), cy + Px.dp(1f), p) }
        p.color = color
        c.textCentered(s, cx, cy, p)
        p.textSize = base
    }

    /** Peak (linear) to 0..1 on a −48…+3 dB scale. */
    private fun meter(peak: Float): Float = if (peak < 1e-5f) 0f else ((20f * log10(peak) + 48f) / 51f).coerceIn(0f, 1f)
    private fun dbText(peak: Float) = if (peak < 1e-4f) "-∞" else "%.0f".format(20f * log10(peak))

    fun draw(c: Canvas, w: Float, h: Float, m: LaunchModel, time: Float, selected: String?, hits: MutableList<Hit>) {
        hits.clear(); centres.clear()
        Grass.draw(c, w.toInt(), h.toInt(), Px.u * 0.5f)
        val pad = Px.dp(8f)
        val blink = (time * 3).toInt() % 2 == 0

        // ---- top bar
        val topH = Px.dp(40f)
        var x = pad
        fun top(id: String, kind: Int, text: String, wd: Float, color: Int, lit: Boolean = false) {
            box(c, x, pad, x + wd, pad + topH, 1f, if (lit) color else EDGE)
            label(c, text, x + wd / 2, pad + topH / 2, big, if (lit) color else WHITE, wd - Px.dp(10f))
            hits += Hit(id, kind, x, pad, x + wd, pad + topH)
            x += wd + Px.dp(6f)
        }
        top("back", K_BACK, "◀", Px.dp(44f), WHITE)
        top("play", K_PLAY, "▶ Play", Px.dp(92f), QUEUED, m.playing || (m.sceneQueued && blink))
        top("stop", K_STOP, "■ Stop", Px.dp(92f), WHITE)
        val recLit = m.audioRec == Transport.Rec.RECORDING || ((m.audioRec == Transport.Rec.ARMED || m.armedAny) && blink)
        top("rec", K_REC, if (m.audioRec == Transport.Rec.ARMED) "● ${m.beat}" else "● Rec", Px.dp(92f), REC, recLit)
        top("shuffle", K_SHUFFLE, "⤨ Shuffle", Px.dp(106f), WHITE)
        val sr = w - pad
        if (sr - x > Px.dp(80f)) {
            box(c, x, pad, sr, pad + topH, 0.85f, if (m.taping) REC else EDGE)
            label(c, if (m.taping) "● Mixing down to tape  ·  ${m.status}" else m.status, (x + sr) / 2, pad + topH / 2, txt, if (m.taping) 0xFFFFB0A8.toInt() else WHITE, sr - x - Px.dp(16f))
        }

        // ---- the mixer: vertical strips for Red, Yellow, Blue and the Master
        val stripW = Px.dp(48f); val sg = Px.dp(4f)
        val mixW = stripW * 4 + sg * 5; val mixH = minOf(Px.dp(168f), h * 0.42f)
        val mixL = pad; val mixT = h - pad - mixH
        box(c, mixL, mixT, mixL + mixW, mixT + mixH, 0.88f)
        for (i in 0 until 4) {
            val sl = mixL + sg + i * (stripW + sg); val st = mixT + sg; val sb = mixT + mixH - sg
            val isMaster = i == 3
            val strip = m.strips.getOrNull(i)
            val accent = if (isMaster) WHITE else strip?.accent ?: WHITE
            box(c, sl, st, sl + stripW, sb, 1f, Pal.alpha(accent, 0.55f), 0xFF141414.toInt())
            sub.textSize = Px.dp(8f)
            label(c, if (isMaster) "Master" else strip?.name ?: "", sl + stripW / 2, st + Px.dp(9f), sub, accent, stripW - Px.dp(4f))
            val peak = if (isMaster) m.masterPeak else strip?.peak ?: 0f
            // dB meter
            val mt = st + Px.dp(18f); val mb = sb - Px.dp(50f)
            val ml = sl + Px.dp(6f); val mr = ml + Px.dp(7f)
            c.fill(ml, mt, mr, mb, 0xFF050505.toInt())
            val lv = meter(peak); val segs = 14
            val segH = (mb - mt) / segs
            for (k in 0 until segs) {
                val on = k < (lv * segs).toInt()
                val col = when { k >= segs - 2 -> REC; k >= segs - 5 -> 0xFFF0C23B.toInt(); else -> 0xFF7FD98F.toInt() }
                val sy = mb - (k + 1) * segH
                c.fill(ml + Px.dp(1f), sy + Px.dp(1f), mr - Px.dp(1f), sy + segH, if (on) col else 0xFF1C1C1C.toInt())
            }
            sub.textSize = Px.dp(7f)
            label(c, dbText(peak), sl + stripW / 2, mb + Px.dp(7f), sub, GREY, stripW - Px.dp(4f))
            if (isMaster) {
                // the master fader (global output)
                val gl = mr + Px.dp(10f); val gr = sl + stripW - Px.dp(8f)
                c.fill((gl + gr) / 2 - Px.dp(1f), mt, (gl + gr) / 2 + Px.dp(1f), mb, 0xFF3A3A3A.toInt())
                val ky = mb - (mb - mt) * m.master
                c.fill(gl, ky - Px.dp(3f), gr, ky + Px.dp(3f), WHITE)
                masterLane[0] = gl - Px.dp(8f); masterLane[1] = mt; masterLane[2] = gr + Px.dp(8f); masterLane[3] = mb
                hits += Hit("master", K_MASTER, gl - Px.dp(8f), mt, gr + Px.dp(8f), mb)
            } else {
                // a thin level line beside the meter, in the track's colour
                c.fill(mr + Px.dp(6f), mt, mr + Px.dp(8f), mb, Pal.alpha(accent, 0.35f))
            }
            // arm button
            val at = sb - Px.dp(40f)
            val armed = if (isMaster) m.masterArmed else strip?.armed == true
            val lit = armed && (blink || (isMaster && m.taping) || (!isMaster && m.audioRec == Transport.Rec.RECORDING))
            box(c, sl + Px.dp(4f), at, sl + stripW - Px.dp(4f), at + Px.dp(18f), 1f, if (armed) REC else EDGE, if (lit) 0xFF5A1410.toInt() else BLACK)
            label(c, if (isMaster) "● Tape" else "● Arm", sl + stripW / 2, at + Px.dp(9f), sub.apply { textSize = Px.dp(8f) }, if (armed) REC else WHITE, stripW - Px.dp(10f))
            hits += Hit(if (isMaster) "master-arm" else i.toString(), if (isMaster) K_MASTER_ARM else K_ARM, sl, at, sl + stripW, at + Px.dp(18f))
            // input configuration (tracks only)
            if (!isMaster && strip != null) {
                val it = sb - Px.dp(19f)
                box(c, sl + Px.dp(4f), it, sl + stripW - Px.dp(4f), it + Px.dp(16f), 1f)
                label(c, strip.input, sl + stripW / 2, it + Px.dp(8f), sub.apply { textSize = Px.dp(8f) }, GREY, stripW - Px.dp(10f))
                hits += Hit(i.toString(), K_INPUT, sl, it, sl + stripW, it + Px.dp(16f))
            }
        }

        // ---- everything else is scattered over the grass, clear of the mixer and the poetry banner
        val fieldTop = pad + topH + Px.dp(54f); val fieldBottom = h - pad
        val blocked = listOf(floatArrayOf(0f, mixT - Px.dp(6f), mixL + mixW + Px.dp(6f), h))
        val items = ArrayList<Triple<String, Float, Float>>()
        m.clips.forEach { items += Triple(it.id, Px.dp(100f + hash(it.id, 1) * 28f), Px.dp(38f + hash(it.id, 2) * 8f)) }
        items += Triple(POEM_ID, Px.dp(112f), Px.dp(84f))
        m.flowers.forEach { items += Triple(it.id, Px.dp(56f), Px.dp(98f)) }
        val sig = "${w.toInt()}x${h.toInt()}:${m.seed}:" + items.joinToString(",") { it.first }
        if (sig != spotSig) { seed = m.seed; place(items, pad, fieldTop, w - pad, fieldBottom, blocked); spotSig = sig }

        spots[POEM_ID]?.let { sp ->
            PoemStone.draw(c, sp.x, sp.y, sp.w, sp.h, Px.u * 0.5f)
            Grass.tuft(c, sp.x + sp.w * 0.3f, sp.y + sp.h * 0.97f, Px.u * 0.5f, 11); Grass.tuft(c, sp.x + sp.w * 0.72f, sp.y + sp.h * 0.98f, Px.u * 0.5f, 23)
            hits += Hit(POEM_ID, K_POEM, sp.x, sp.y, sp.x + sp.w, sp.y + sp.h)
        }
        for (f in m.flowers) {
            val sp = spots[f.id] ?: continue
            val fx = sp.x + sp.w / 2; val fy = sp.y + sp.h * 0.24f
            c.flower(fx, fy, 1.45f, f.bloom, f.variant, time, f.chain >= 0 && m.playing)
            Grass.tuft(c, fx, sp.y + sp.h, Px.u * 0.5f, f.id.hashCode())
            centres[f.id] = floatArrayOf(fx, fy)
            if (f.chain >= 0) c.fill(fx - Px.dp(10f), sp.y + sp.h + Px.dp(2f), fx + Px.dp(10f), sp.y + sp.h + Px.dp(5f), CHAIN[f.chain])
            if (selected == f.id) { box(c, fx - Px.dp(44f), sp.y + sp.h + Px.dp(7f), fx + Px.dp(44f), sp.y + sp.h + Px.dp(25f), 0.9f); label(c, f.name, fx, sp.y + sp.h + Px.dp(16f), sub.apply { textSize = Px.dp(9f) }, WHITE, Px.dp(80f)) }
            hits += Hit(f.id, K_FLOWER, sp.x, sp.y, sp.x + sp.w, sp.y + sp.h)
        }

        for (b in m.clips) {
            val s = spots[b.id] ?: continue
            val alpha = if (b.muted) 0.35f else 0.45f + 0.55f * b.volume
            val pulse = if (b.playing && blink) 1f else 0f
            val edge = if (selected == b.id) WHITE else if (b.playing) PLAYING else if (b.queued) QUEUED else if (b.wave != null) Pal.alpha(b.accent, 0.7f) else EDGE
            c.save(); c.rotate(s.tilt, s.x + s.w / 2, s.y + s.h / 2)
            val ta = maxOf(alpha, 0.75f)
            val wv = b.wave
            if (wv != null) {
                // an audio take: its waveform, in its track's colour, is the box
                box(c, s.x, s.y - pulse, s.x + s.w, s.y + s.h - pulse, alpha * 0.55f, edge)
                val n = wv.size; val mid = s.y + s.h / 2 - pulse; val amp = s.h * 0.46f
                val step = (s.w - Px.dp(6f)) / n
                for (k in 0 until n) { val a = wv[k] * amp; c.fill(s.x + Px.dp(3f) + k * step, mid - a, s.x + Px.dp(3f) + k * step + maxOf(1f, step * 0.8f), mid + a + 1, Pal.alpha(b.accent, alpha)) }
                label(c, b.name, s.x + s.w / 2, s.y + s.h * 0.42f, txt, Pal.alpha(WHITE, ta), s.w - Px.dp(12f), shadow = true)
                label(c, (b.block + if (b.muted) " · MUTED" else "").uppercase(), s.x + s.w / 2, s.y + s.h * 0.76f, sub.apply { textSize = Px.dp(8f) }, Pal.alpha(WHITE, ta), s.w - Px.dp(12f), shadow = true)
            } else {
                box(c, s.x, s.y - pulse, s.x + s.w, s.y + s.h - pulse, alpha, edge)
                c.fill(s.x + Px.dp(7f), s.y + Px.dp(7f), s.x + Px.dp(12f), s.y + Px.dp(12f), Pal.alpha(b.accent, alpha))
                label(c, b.name, s.x + s.w / 2 + Px.dp(4f), s.y + s.h * 0.4f, txt, Pal.alpha(WHITE, ta), s.w - Px.dp(26f))
                label(c, b.block.uppercase() + if (b.muted) " · MUTED" else "", s.x + s.w / 2 + Px.dp(4f), s.y + s.h * 0.75f, sub.apply { textSize = Px.dp(8f) }, Pal.alpha(GREY, ta), s.w - Px.dp(26f))
            }
            if (b.queued || b.playing) c.fill(s.x + Px.dp(3f), s.y + Px.dp(6f), s.x + Px.dp(5f), s.y + s.h - Px.dp(6f), if (b.playing) PLAYING else QUEUED)
            c.restore()
            hits += Hit(b.id, if (b.wave != null) K_TAKE else K_CLIP, s.x, s.y, s.x + s.w, s.y + s.h)
            centres[b.id] = floatArrayOf(s.x + s.w / 2, s.y + s.h / 2)
        }
        if (m.clips.isEmpty()) label(c, "Record clips in the studio, or arm a track and ● Rec", (mixL + mixW + w) / 2, (fieldTop + fieldBottom) / 2, txt, WHITE, w - mixW - pad * 4, shadow = true)

        // ---- daisy chains, routed from wherever things lie now
        for ((k, chain) in m.chains.withIndex()) tracer(c, (listOf(chain.first) + chain.second).mapNotNull { centres[it] }, CHAIN[k % 3], Px.u * 0.8f, time)
        if (m.drawing.size > 1) tracer(c, m.drawing, CHAIN[m.chains.size.coerceAtMost(2)], Px.u * 0.8f, time)

        // ---- a line of poetry, in white across the top (nothing is placed under it)
        m.quote?.let { (line, poet) ->
            val qt = pad + topH + Px.dp(6f)
            box(c, pad, qt, w - pad, qt + Px.dp(40f), 0.9f)
            label(c, line.replace(" / ", "  ·  "), w / 2, qt + Px.dp(14f), txt, WHITE, w - pad * 4)
            label(c, "— $poet", w / 2, qt + Px.dp(31f), sub.apply { textSize = Px.dp(9f) }, WHITE, w - pad * 4)
        }
    }

    /**
     * Scatter items over the field: for each one, try many random spots and
     * keep the one furthest from everything already placed (spread out, but
     * random), never overlapping when there's room. Same seed, same layout.
     */
    private fun place(items: List<Triple<String, Float, Float>>, l: Float, t: Float, r: Float, b: Float, blocked: List<FloatArray>) {
        spots.clear()
        val placed = ArrayList<FloatArray>()
        val gap = Px.dp(12f)
        for ((id, bw, bh) in items) {
            fun free(cd: FloatArray, gp: Float = gap) = (placed + blocked).none { it[0] < cd[2] + gp && cd[0] < it[2] + gp && it[1] < cd[3] + gp && cd[1] < it[3] + gp }
            fun clearance(cd: FloatArray): Float {
                var best = Float.MAX_VALUE
                val cx = (cd[0] + cd[2]) / 2; val cy = (cd[1] + cd[3]) / 2
                for (p in placed) best = minOf(best, hypot(cx - (p[0] + p[2]) / 2, cy - (p[1] + p[3]) / 2))
                return minOf(best, (cx - l) * 1.6f, (r - cx) * 1.6f, (cy - t) * 1.6f, (b - cy) * 1.6f)
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
                val step = Px.dp(8f)
                search@ for (gp in floatArrayOf(gap, gap / 2, 1f)) {
                    var y = t
                    while (y + bh <= b) {
                        var x = l
                        while (x + bw <= r) { val cd = floatArrayOf(x, y, x + bw, y + bh); if (free(cd, gp)) { best = cd; break@search }; x += step }
                        y += step
                    }
                }
            }
            var s = best ?: continue
            for (k in blocked) if (k[0] < s[2] && s[0] < k[2] && k[1] < s[3] && s[1] < k[3]) { val nx = k[2] + gap; s = floatArrayOf(nx, s[1], nx + bw, s[3]) }
            placed += s
            spots[id] = Spot(s[0], s[1], bw, bh, if (id != POEM_ID && bh < Px.dp(60f)) (hash(id, 3) - 0.5f) * 7f else 0f)
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
 * Clibbins (opened from the rail):
 *  - Tap a clip or take to queue it and select it: a volume fader, a pan
 *    slider and a rename button appear. Double-tap to mute.
 *  - ▶ Play starts the queued clips (when the current loop comes round),
 *    ■ Stop stops, ● Rec counts in two bars and records the armed tracks.
 *  - The mixer: arm and pick the input for each track; the master fader sets
 *    the overall level, and arming the master records the mix to tape while
 *    playing (stopping exports the song).
 *  - Drag a line from a flower through clips to make a daisy chain (up to 3).
 *  - Hold a clip until it clicks, then drag it to R.I.P. to lay it to rest.
 */
@SuppressLint("ViewConstructor")
class ClibbinsScreen(c: Context, private val host: Host) : PixelView(c) {
    var backToStudio: (() -> Unit)? = null
    private val t0 = System.nanoTime()
    private val hits = ArrayList<ClipField.Hit>()
    private val ripP = Px.text(11f, Pal.CREAM, headFont = true)
    private val small = Px.text(10f, 0xFFFFFFFF.toInt())

    init { animated = true }
    private var poem: Pair<String, String>? = null
    private var poemAt = 0L
    private val waves = HashMap<String, FloatArray>()
    /** A take's waveform, 48 peaks (computed once). */
    private fun wave(a: AudioClip): FloatArray {
        val d = a.data
        if (d.isEmpty()) return FloatArray(48) { 0.05f }
        return waves.getOrPut(a.id + d.size) {
            val n = 48; val per = maxOf(1, d.size / n)
            val out = FloatArray(n) { k -> var pk = 0f; var i = k * per; val e = minOf(d.size, i + per); while (i < e) { pk = maxOf(pk, kotlin.math.abs(d[i])); i += 8 }; pk }
            val mx = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
            for (k in out.indices) out[k] = (out[k] / mx).coerceAtLeast(0.05f)
            out
        }
    }

    private fun model(): LaunchModel {
        val t = Audio.transport
        val p = Studio.project
        val boxes = ArrayList<LaunchModel.Box>()
        Studio.clibbinsClips.forEach { cl ->
            boxes += LaunchModel.Box(cl.id, cl.name, Studio.engine(cl.engine).title, Pal.accent(cl.engine), Studio.isQueued(cl.id), Studio.isPlaying(cl.id), cl.muted, cl.volume)
        }
        p.audioClips.forEach { a ->
            boxes += LaunchModel.Box(a.id, a.name, Studio.CHANNEL_NAMES[a.channel] + " track", Iso.PLANES[a.channel], Studio.isQueued(a.id), Studio.isPlaying(a.id), a.muted, a.volume, wave(a))
        }
        val inputs = listOf("In 1", "In 2", "In 1+2")
        val strips = (0 until AudioClip.CHANNELS).map { ch -> LaunchModel.Strip(ch, Studio.CHANNEL_NAMES[ch], Iso.PLANES[ch], Studio.armed[ch], Audio.channelPeak[ch], inputs[AudioPrefs.planeInput(ch).coerceIn(0, 2)]) }
        val flowers = Studio.clibbinsFlowers.map { f -> LaunchModel.FlowerSlot(f.id, f.name, f.variant, f.bloom, Studio.chainIndex(f.id)) }
        val chains = p.chains.map { it.flowerId to it.clipIds.toList() }
        val status = when {
            t.audioRec == Transport.Rec.ARMED -> "Count-in… ${t.beat + 1}"
            t.audioRec == Transport.Rec.RECORDING -> "Recording: tap ● Rec to stop"
            Studio.sceneQueued -> "Next scene in ${Math.ceil(maxOf(0.0, (t.queuedAt - t.tick) / Ticks.PPQ)).toInt()} beats"
            t.playing -> "${t.bar + 1}.${t.beat + 1}  ·  ${p.bpm.toInt()} BPM"
            boxes.isEmpty() -> "Record clips in the studio, or arm a track and ● Rec"
            else -> "Tap clips to queue them, then ▶ Play"
        }
        val q = poem.takeIf { System.currentTimeMillis() - poemAt < 20_000 }
        return LaunchModel(boxes, strips, flowers, chains, chainPath.toList(), t.playing, Studio.sceneQueued, Studio.armed.any { it }, t.audioRec, (t.beat + 1).toInt(),
            status, p.layoutSeed, q, p.audioMaster, Audio.peak, Studio.masterArmed, Studio.taping)
    }

    override fun paint(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val time = (System.nanoTime() - t0) / 1e9f
        ClipField.draw(c, w, h, model(), time, selected, hits)
        drawEye(c, w, h)
        drawControls(c, w, h)
        if (burying != null) {
            val sh = minOf(h / 3.2f, Px.dp(190f))
            stone[0] = w / 2 - sh * 0.6f; stone[1] = h - Px.dp(8f) - sh * 1.1f; stone[2] = w / 2 + sh * 0.6f; stone[3] = h - Px.dp(8f)
            with(Iso) { c.tombstone(w / 2, h - Px.dp(8f), sh, overStone(), ripP) }
            c.dot(curX - Px.u, curY - Px.u, 0xFFFFFFFF.toInt(), 3)
        }
    }

    // ------------------------------------------------------------------ the eye in the grass

    /**
     * Every 15–45 s a realistic eye opens somewhere clear of everything else,
     * stares at you (with little darting glances), blinks now and then, and
     * after 5–15 s closes again.
     */
    private val rnd = kotlin.random.Random(System.nanoTime())
    private var eyeNext = System.currentTimeMillis() + 15_000L + rnd.nextLong(30_000L)
    private var eyeFrom = 0L; private var eyeUntil = 0L
    private var eyeX = 0f; private var eyeY = 0f; private var eyeW = 0f; private var eyeVariant = 0
    private var blinkAt = 0L
    private var lookX = 0f; private var lookY = 0f; private var lookNext = 0L

    private fun drawEye(c: Canvas, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        if (eyeUntil == 0L && now >= eyeNext) {
            if (placeEye(w, h)) {
                eyeFrom = now; eyeUntil = now + 5_000L + rnd.nextLong(10_000L)
                blinkAt = now + 1_500L + rnd.nextLong(2_500L); eyeVariant = rnd.nextInt(4)
            } else eyeNext = now + 5_000L
        }
        if (eyeUntil == 0L) return
        if (now >= eyeUntil + 400) { eyeUntil = 0L; eyeSore = false; eyeNext = now + 15_000L + rnd.nextLong(30_000L); return }
        // opening, closing, and blinks
        var open = when {
            now < eyeFrom + 400 -> (now - eyeFrom) / 400f
            now > eyeUntil -> 1f - (now - eyeUntil) / 400f
            else -> 1f
        }
        if (now >= blinkAt) {
            val k = (now - blinkAt) / 180f
            if (k < 1f) open *= kotlin.math.abs(1f - 2f * k) else blinkAt = now + 1_800L + rnd.nextLong(3_500L)
        }
        // mostly staring straight out at you, with small darting glances
        if (now >= lookNext) {
            val stare = rnd.nextInt(3) != 0
            lookX = if (stare) 0f else rnd.nextFloat() * 1.2f - 0.6f; lookY = if (stare) 0f else rnd.nextFloat() * 0.8f - 0.4f
            lookNext = now + 700L + rnd.nextLong(1_800L)
        }
        eyeOpen = open
        // a poked eye glares straight at you
        Eyeball.draw(c, eyeX, eyeY, eyeW, open, if (eyeSore) 0f else lookX, if (eyeSore) 0f else lookY, eyeVariant, eyeSore)
    }

    private var eyeSore = false
    private var eyeOpen = 0f
    private fun onEye(x: Float, y: Float): Boolean {
        if (eyeUntil == 0L || eyeOpen < 0.2f) return false
        val dx = (x - eyeX) / (eyeW * 0.75f); val dy = (y - eyeY) / (eyeW * 0.5f)
        return dx * dx + dy * dy <= 1f
    }

    /** Poked: the phone buzzes and the eye goes bloodshot (and stays open a while, glaring). */
    private fun pokeEye() {
        eyeSore = true
        eyeUntil = maxOf(eyeUntil, System.currentTimeMillis() + 4_000L)
        blinkAt = System.currentTimeMillis() + 2_500L
        buzz()
    }

    private fun buzz() {
        val v = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java)
        if (v == null || !v.hasVibrator()) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); return }
        if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 90, 60, 160), -1))
        else @Suppress("DEPRECATION") v.vibrate(250)
    }

    /** Find a spot clear of every clip, box, button and the poetry banner. */
    private fun placeEye(w: Float, h: Float): Boolean {
        eyeW = Px.dp(64f)
        val ew = eyeW * 1.4f; val eh = eyeW * 0.9f
        val pad = Px.dp(14f)
        val bannerBottom = Px.dp(8f) + Px.dp(40f) + Px.dp(54f)
        repeat(60) {
            val x = ew / 2 + rnd.nextFloat() * (w - ew); val y = bannerBottom + eh / 2 + rnd.nextFloat() * (h - bannerBottom - eh)
            val l = x - ew / 2 - pad; val r = x + ew / 2 + pad; val t = y - eh / 2 - pad; val b = y + eh / 2 + pad
            if (hits.none { it.l < r && l < it.r && it.t < b && t < it.b }) { eyeX = x; eyeY = y; return true }
        }
        return false
    }

    // ------------------------------------------------------------------ selection: volume, pan, rename

    private val fader = FloatArray(4); private val panBox = FloatArray(4); private val renameBox = FloatArray(4)
    private var faderTop = 0f; private var faderH = 1f; private var panL = 0f; private var panW = 1f
    private fun drawControls(c: Canvas, w: Float, h: Float) {
        fader.fill(0f); panBox.fill(0f); renameBox.fill(0f)
        val id = selected ?: return
        val hit = hits.firstOrNull { it.id == id && (it.kind == ClipField.K_CLIP || it.kind == ClipField.K_TAKE || it.kind == ClipField.K_FLOWER) } ?: return
        val isFlower = hit.kind == ClipField.K_FLOWER
        val white = 0xFFFFFFFF.toInt()
        // volume (or bloom), vertical, to the right
        val value = if (isFlower) Studio.project.flowers.firstOrNull { it.id == id }?.bloom ?: 0f else Studio.volumeOf(id)
        val fh = Px.dp(90f)
        var fx = hit.r + Px.dp(6f)
        if (fx > w - Px.dp(30f)) fx = hit.l - Px.dp(28f)
        val top = (hit.cy - fh / 2).coerceIn(Px.dp(8f), h - fh - Px.dp(8f))
        ClipField.box(c, fx - Px.dp(4f), top - Px.dp(6f), fx + Px.dp(22f), top + fh + Px.dp(6f), 1f, white)
        c.fill(fx + Px.dp(7f), top + Px.dp(6f), fx + Px.dp(11f), top + fh - Px.dp(6f), 0xFF3A3A3A.toInt())
        val ky = top + fh - Px.dp(6f) - value * (fh - Px.dp(12f))
        c.fill(fx + Px.dp(7f), ky, fx + Px.dp(11f), top + fh - Px.dp(6f), if (isFlower) Pal.ROSE else Pal.AQUA)
        c.fill(fx - Px.dp(1f), ky - Px.dp(4f), fx + Px.dp(19f), ky + Px.dp(4f), white)
        ClipField.box(c, fx - Px.dp(8f), top - Px.dp(24f), fx + Px.dp(26f), top - Px.dp(9f), 1f)
        small.color = white; c.textCentered(if (isFlower) "bloom" else "vol", fx + Px.dp(9f), top - Px.dp(16f), small)
        fader[0] = fx - Px.dp(14f); fader[1] = top - Px.dp(14f); fader[2] = fx + Px.dp(32f); fader[3] = top + fh + Px.dp(14f)
        faderTop = top + Px.dp(6f); faderH = fh - Px.dp(12f)
        if (isFlower) return
        // pan, horizontal, underneath
        val pw = maxOf(hit.r - hit.l, Px.dp(110f)); val pl = (hit.cx - pw / 2).coerceIn(Px.dp(8f), w - pw - Px.dp(8f))
        var pt = hit.b + Px.dp(8f)
        if (pt + Px.dp(24f) > h - Px.dp(4f)) pt = hit.t - Px.dp(32f)
        ClipField.box(c, pl, pt, pl + pw, pt + Px.dp(24f), 1f, white)
        c.fill(pl + Px.dp(12f), pt + Px.dp(11f), pl + pw - Px.dp(12f), pt + Px.dp(13f), 0xFF3A3A3A.toInt())
        c.fill(pl + pw / 2 - Px.dp(0.5f), pt + Px.dp(6f), pl + pw / 2 + Px.dp(0.5f), pt + Px.dp(18f), 0xFF6A6A6A.toInt())
        val pan = Studio.panOf(id)
        val kx = pl + Px.dp(12f) + (pan + 1f) / 2f * (pw - Px.dp(24f))
        c.fill(kx - Px.dp(4f), pt + Px.dp(5f), kx + Px.dp(4f), pt + Px.dp(19f), white)
        small.textSize = Px.dp(8f); small.color = 0xFFA8A8A8.toInt()
        c.textCentered("L", pl + Px.dp(6f), pt + Px.dp(12f), small); c.textCentered("R", pl + pw - Px.dp(6f), pt + Px.dp(12f), small)
        small.textSize = Px.dp(10f)
        panBox[0] = pl; panBox[1] = pt - Px.dp(6f); panBox[2] = pl + pw; panBox[3] = pt + Px.dp(30f)
        panL = pl + Px.dp(12f); panW = pw - Px.dp(24f)
        // rename, above
        val rw = Px.dp(76f); val rl = (hit.cx - rw / 2).coerceIn(Px.dp(8f), w - rw - Px.dp(8f))
        var rt = hit.t - Px.dp(28f)
        if (rt < Px.dp(56f)) rt = hit.b + Px.dp(38f)
        ClipField.box(c, rl, rt, rl + rw, rt + Px.dp(22f), 1f, white)
        small.color = white; c.textCentered("✎ Rename", rl + rw / 2, rt + Px.dp(11f), small)
        renameBox[0] = rl; renameBox[1] = rt; renameBox[2] = rl + rw; renameBox[3] = rt + Px.dp(22f)
    }

    // ------------------------------------------------------------------ touch

    private var downHit: ClipField.Hit? = null
    private var eyeTouch = false
    private var downX = 0f; private var downY = 0f; private var curX = 0f; private var curY = 0f
    private var moved = false
    private var burying: String? = null
    private val stone = FloatArray(4)
    private fun overStone() = curX in stone[0]..stone[2] && curY in stone[1]..stone[3]
    private var selected: String? = null
    private var drag = 0 // 1 volume, 2 pan, 3 master
    private var chainFlower: String? = null
    private val chainPath = ArrayList<FloatArray>()
    private val chainClips = LinkedHashSet<String>()
    private var lastTapId: String? = null
    private var lastTapAt = 0L
    private var pendingTap: Runnable? = null

    private val longPress = Runnable {
        val hh = downHit ?: return@Runnable
        if (moved || hh.kind !in listOf(ClipField.K_CLIP, ClipField.K_TAKE, ClipField.K_FLOWER)) return@Runnable
        burying = hh.id; selected = null
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun hitAt(x: Float, y: Float) = hits.lastOrNull { it.has(x, y) }
    private fun inside(b: FloatArray, x: Float, y: Float) = b[2] > b[0] && x in b[0]..b[2] && y in b[1]..b[3]

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; curX = x; curY = y; moved = false; drag = 0
                eyeTouch = onEye(x, y)
                if (eyeTouch) return true
                if (selected != null && inside(renameBox, x, y)) { selected?.let { host.renameItem(it) }; return true }
                if (selected != null && inside(fader, x, y)) { drag = 1; setFader(y); return true }
                if (selected != null && inside(panBox, x, y)) { drag = 2; setPan(x); return true }
                val ml = ClipField.masterLane
                if (x in ml[0]..ml[2] && y in ml[1] - Px.dp(6f)..ml[3] + Px.dp(6f)) { drag = 3; setMaster(y); return true }
                downHit = hitAt(x, y)
                if (downHit != null) postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                curX = x; curY = y
                if (eyeTouch) return true
                when (drag) { 1 -> { setFader(y); return true }; 2 -> { setPan(x); return true }; 3 -> { setMaster(y); return true } }
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
                if (eyeTouch) {
                    eyeTouch = false
                    val dx = x - downX; val dy = y - downY
                    when {
                        // swiped away to the right: everything stops, and off you go to answer for it
                        dx > Px.dp(60f) && abs(dy) < dx * 0.8f -> { eyeUntil = 0L; eyeSore = false; host.showHall() }
                        hypot(dx, dy) < Px.dp(14f) -> pokeEye()
                    }
                    return true
                }
                val b = burying
                when {
                    drag != 0 -> { drag = 0; Studio.save() }
                    b != null -> { if (overStone()) Studio.bury(b); burying = null }
                    chainFlower != null -> {
                        val w = width.toFloat(); val h = height.toFloat()
                        Studio.makeChain(chainFlower!!, chainClips.toList(), chainPath.map { floatArrayOf(it[0] / w, it[1] / h) })
                        chainFlower = null; chainPath.clear(); chainClips.clear()
                    }
                    !moved -> tap(downHit)
                }
                downHit = null
            }
            MotionEvent.ACTION_CANCEL -> { eyeTouch = false; removeCallbacks(longPress); burying = null; chainFlower = null; chainPath.clear(); drag = 0; downHit = null }
        }
        return true
    }

    private fun setMaster(y: Float) { val ml = ClipField.masterLane; Studio.setAudioMaster((ml[3] - y) / (ml[3] - ml[1]).coerceAtLeast(1f)) }
    private fun setPan(x: Float) { selected?.let { Studio.setPan(it, ((x - panL) / panW) * 2f - 1f) } }
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
            ClipField.K_SHUFFLE -> { selected = null; Studio.shuffleLayout() }
            ClipField.K_POEM -> { poem = Poems.random(); poemAt = System.currentTimeMillis() }
            ClipField.K_ARM -> Studio.toggleArm(hh.id.toInt())
            ClipField.K_INPUT -> host.channelInput(hh.id.toInt())
            ClipField.K_MASTER_ARM -> Studio.toggleMasterArm()
            ClipField.K_MASTER -> {}
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
