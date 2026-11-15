package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.blocks.Blocks
import app.foxbox.seq.AudioClip
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport
import kotlin.math.hypot

/** What the stone field shows (built from the Studio every frame). */
class StoneModel(
    val columns: List<Col>,
    val flowers: List<FlowerSlot>,
    val chains: List<List<FloatArray>>,
    val playing: Boolean,
    val sceneQueued: Boolean,
    val armedAny: Boolean,
    val audioRec: Transport.Rec,
    val beat: Int,
    val status: String,
    val canAddChannel: Boolean,
) {
    /** kind: 0 block, 1 audio channel. */
    class Col(val id: String, val title: String, val glyph: String, val kind: Int, val armed: Boolean, val accent: Int, val cells: List<Cell?>)
    class Cell(val id: String, val name: String, val queued: Boolean, val playing: Boolean, val muted: Boolean, val volume: Float, val seed: Int)
    class FlowerSlot(val id: String, val name: String, val variant: Int, val bloom: Float, val chain: Int)
}

/** Draws the field of etched stones; records hit areas for touch. */
object StoneField {
    const val K_CLIP = 0; const val K_TAKE = 1; const val K_FLOWER = 2; const val K_CHANNEL = 3
    const val K_PLAY = 4; const val K_STOP = 5; const val K_REC = 6; const val K_BACK = 7; const val K_PLUS = 8; const val K_BLOCK = 9
    /** Daisy-chain tracer colours: red, yellow, blue. */
    val CHAIN = intArrayOf(0xFFD9605A.toInt(), 0xFFF0B84A.toInt(), 0xFF4A8FC8.toInt())
    const val ROWS = 4

    class Hit(val id: String, val kind: Int, val l: Float, val t: Float, val r: Float, val b: Float) {
        val cx get() = (l + r) / 2; val cy get() = (t + b) / 2
        fun has(x: Float, y: Float) = x in l..r && y in t..b
    }

    private val head by lazy { Px.text(9f, headFont = true) }
    private val name by lazy { Px.text(12f) }
    private val big by lazy { Px.text(15f, headFont = true) }
    private val small by lazy { Px.text(10f) }

    fun g() = Px.u * 0.6f

    fun draw(c: Canvas, w: Float, h: Float, m: StoneModel, time: Float, selected: String?, hits: MutableList<Hit>) {
        hits.clear()
        val g = g()
        c.drawBitmap(Stones.grass(w.toInt(), h.toInt(), g), 0f, 0f, null)
        val pad = Px.dp(8f)
        // ---- top row: back, transport, status
        val topH = Px.dp(46f)
        var x = pad
        val blink = (time * 3).toInt() % 2 == 0
        fun topStone(id: String, kind: Int, label: String, wd: Float, seed: Int, glow: Int, tones: Stones.Tones = Stones.GRANITE) {
            stone(c, x, pad, x + wd, pad + topH, seed, tones, 255)
            Stones.engrave(c, label, x + wd / 2, pad + topH / 2 - g, big, tones, glow)
            hits += Hit(id, kind, x, pad, x + wd, pad + topH)
            x += wd + Px.dp(6f)
        }
        topStone("back", K_BACK, "◀", Px.dp(52f), 11, 0, Stones.SLATE)
        topStone("play", K_PLAY, "▶ PLAY", Px.dp(118f), 21, if (m.playing) 0xFF2F9A93.toInt() else if (m.sceneQueued && blink) 0xFF3FB8AF.toInt() else 0)
        topStone("stop", K_STOP, "■ STOP", Px.dp(118f), 32, 0)
        val recLit = m.audioRec == Transport.Rec.RECORDING || ((m.audioRec == Transport.Rec.ARMED || m.armedAny) && blink)
        topStone("rec", K_REC, if (m.audioRec == Transport.Rec.ARMED) "● ${m.beat}" else "● REC", Px.dp(118f), 43, if (recLit) 0xFFD0402F.toInt() else 0)
        val sl = x; val sr = w - pad
        if (sr - sl > Px.dp(80f)) {
            stone(c, sl, pad, sr, pad + topH, 57, Stones.SANDSTONE, 255)
            small.textSize = Px.dp(11f)
            while (small.measureText(m.status) > sr - sl - Px.dp(24f) && small.textSize > Px.dp(7f)) small.textSize -= Px.dp(0.5f)
            Stones.engrave(c, m.status, (sl + sr) / 2, pad + topH / 2 - g, small, Stones.SANDSTONE)
        }

        // ---- the grid of stones: one column per block, then per audio channel
        val gardenH = h * 0.24f
        val top = pad + topH + Px.dp(10f)
        val bottom = h - gardenH
        val cols = m.columns.size + if (m.canAddChannel) 1 else 0
        val gap = Px.dp(7f)
        val colW = minOf(Px.dp(150f), (w - pad * 2 - gap * (cols - 1)) / cols.coerceAtLeast(1))
        val headH = Px.dp(32f)
        val cellH = (bottom - top - headH - gap * ROWS) / ROWS
        var cx = (w - (colW * cols + gap * (cols - 1))) / 2
        for ((ci, col) in m.columns.withIndex()) {
            val tones = if (col.kind == 1) Stones.SLATE else Stones.GRANITE
            stone(c, cx, top, cx + colW, top + headH, 100 + ci * 7, tones, 255)
            head.textSize = Px.dp(8f)
            Stones.engrave(c, col.title.uppercase(), cx + colW / 2 + (if (col.kind == 1) Px.dp(6f) else 0f), top + headH / 2 - g, head, tones)
            if (col.kind == 1) {
                // an inlaid gem in the channel's colour; it glows when armed
                val gx = cx + Px.dp(14f); val gy = top + headH / 2 - g
                val lit = col.armed && (m.audioRec == Transport.Rec.RECORDING || blink)
                with(Iso) { c.disc(gx, gy, 3f, g, tones.line); c.disc(gx, gy, 2f, g, if (lit) Iso.lighten(col.accent, 0.35f) else Iso.darken(col.accent, 0.25f)) }
                if (col.armed) c.fill(gx - 3 * g, gy + 4 * g, gx + 3 * g, gy + 5 * g, col.accent)
                hits += Hit(col.id, K_CHANNEL, cx, top, cx + colW, top + headH)
            } else hits += Hit(col.id, K_BLOCK, cx, top, cx + colW, top + headH)
            for (r in 0 until ROWS) {
                val t = top + headH + gap + r * (cellH + gap)
                val cell = col.cells.getOrNull(r)
                if (cell == null) { hollow(c, cx + Px.dp(6f), t + Px.dp(4f), cx + colW - Px.dp(6f), t + cellH - Px.dp(4f), g); continue }
                val alpha = ((if (cell.muted) 0.35f else 0.4f + 0.6f * cell.volume) * 255).toInt()
                val st = Stones.kind(cell.seed)
                val lift = if (cell.playing && (time * 4).toInt() % 2 == 0) -g else 0f
                stone(c, cx, t + lift, cx + colW, t + cellH + lift, cell.seed, st, alpha)
                val glow = when { cell.playing && cell.queued -> Pal.ORANGE_D; cell.playing -> Pal.ORANGE; cell.queued -> 0xFF22706B.toInt(); else -> 0 }
                val gs = g * 1.1f
                Stones.glyph(c, col.glyph, cx + Px.dp(10f), t + lift + cellH / 2 - 4 * gs, gs, st, glow)
                name.textSize = Px.dp(12f)
                val label = cell.name.take(14)
                while (name.measureText(label) > colW - Px.dp(40f) && name.textSize > Px.dp(8f)) name.textSize -= Px.dp(0.5f)
                Stones.engrave(c, label, cx + colW / 2 + Px.dp(10f), t + lift + cellH / 2 - g, name, st, glow)
                if (cell.muted) { small.textSize = Px.dp(9f); Stones.engrave(c, "muted", cx + colW - Px.dp(22f), t + cellH - Px.dp(10f), small, st) }
                if (cell.queued) for (k in 0..2) c.fill(cx + Px.dp(10f) + k * g * 2, t + lift + Px.dp(7f), cx + Px.dp(10f) + k * g * 2 + g, t + lift + Px.dp(7f) + g, if (cell.playing) Pal.ORANGE else 0xFF22706B.toInt())
                hits += Hit(cell.id, if (col.kind == 1) K_TAKE else K_CLIP, cx, t, cx + colW, t + cellH)
            }
            cx += colW + gap
        }
        if (m.canAddChannel) {
            stone(c, cx, top, cx + colW, top + headH, 333, Stones.SLATE, 190)
            head.textSize = Px.dp(8f)
            Stones.engrave(c, "+ AUDIO", cx + colW / 2, top + headH / 2 - g, head, Stones.SLATE)
            hits += Hit("plus", K_PLUS, cx, top, cx + colW, top + headH)
        }

        // ---- the garden: planted flowers among the grass
        val gy = h - gardenH
        for ((i, f) in m.flowers.take(5).withIndex()) {
            val fx = w * (0.18f + i * 0.16f); val fy = gy + gardenH * 0.42f
            c.flower(fx, fy, 1.4f, f.bloom, f.variant, time, f.chain >= 0 && m.playing)
            if (f.chain >= 0) with(Iso) { c.disc(fx, fy + gardenH * 0.45f, 3f, g * 1.5f, Pal.alpha(CHAIN[f.chain], 0.85f), 0.5f) }
            small.textSize = Px.dp(9f); small.color = Pal.CREAM
            if (selected == f.id) c.textCentered(f.name, fx, h - Px.dp(6f), small)
            hits += Hit(f.id, K_FLOWER, fx - Px.dp(28f), fy - Px.dp(30f), fx + Px.dp(28f), fy + Px.dp(40f))
        }
        if (m.flowers.isEmpty()) { small.textSize = Px.dp(11f); small.color = Pal.CREAM; c.textCentered("Plant flowers from the Flowers page, then drag a line from one through stones", w / 2, gy + gardenH / 2, small) }

        // ---- daisy chains
        for ((k, path) in m.chains.withIndex()) tracer(c, path.map { floatArrayOf(it[0] * w, it[1] * h) }, CHAIN[k % 3], g * 1.5f, time, 0.9f)
    }

    private val paint by lazy { android.graphics.Paint() }
    fun stone(c: Canvas, l: Float, t: Float, r: Float, b: Float, seed: Int, tones: Stones.Tones, alpha: Int) {
        val bmp = Stones.bitmap((r - l).toInt(), (b - t).toInt() + (g() * 2).toInt(), g(), seed, tones)
        paint.alpha = alpha
        c.drawBitmap(bmp, l, t, paint)
        Stones.tufts(c, l, r, b + g(), g(), seed)
    }

    /** An empty slot: a shallow dimple in the turf. */
    private fun hollow(c: Canvas, l: Float, t: Float, r: Float, b: Float, g: Float) {
        c.fill(l + g * 2, t, r - g * 2, t + g, Stones.GRASS[3]); c.fill(l, t + g * 2, l + g, b - g * 2, Stones.GRASS[3])
        c.fill(l + g * 2, b - g, r - g * 2, b, Stones.GRASS[0]); c.fill(r - g, t + g * 2, r, b - g * 2, Stones.GRASS[0])
    }

    fun tracer(c: Canvas, pts: List<FloatArray>, col: Int, g: Float, t: Float, a: Float) {
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
                c.fill(Iso.snap(x, g), Iso.snap(y, g), Iso.snap(x, g) + g * s, Iso.snap(y, g) + g * s, Pal.alpha(if (near) Pal.CREAM else col, a))
                next += g * 2.5f
            }
            d += len
        }
    }
}

/**
 * The stones: perform the song. Each block's clips and each audio channel's
 * takes are etched stones lying in the grass; planted flowers grow in the
 * garden along the bottom.
 *  - Tap a stone to queue it (and select it: drag its fader for volume).
 *    Double-tap to mute.
 *  - ▶ PLAY starts the queued stones (when the current loop comes round),
 *    ■ STOP stops, ● REC counts in two bars and records the armed channels.
 *  - Tap a channel's name stone to arm it; + AUDIO adds a channel.
 *  - Drag a line from a flower through stones to make a daisy chain (up to 3).
 *  - Hold a stone until it clicks, then drag it to R.I.P. to lay it to rest.
 */
@SuppressLint("ViewConstructor")
class ValleyScreen(c: Context, private val host: Host) : PixelView(c) {
    var backToStudio: (() -> Unit)? = null
    private val t0 = System.nanoTime()
    private val hits = ArrayList<StoneField.Hit>()
    private val ripP = Px.text(11f, Pal.CREAM, headFont = true)
    private val small = Px.text(10f, Pal.CREAM)

    init { animated = true }

    private fun model(): StoneModel {
        val t = Audio.transport
        val p = Studio.project
        val cols = ArrayList<StoneModel.Col>()
        for (b in Studio.blocks) {
            val cells = Studio.clipsFor(b.engine).take(StoneField.ROWS).map { cl ->
                StoneModel.Cell(cl.id, cl.name, Studio.isQueued(cl.id), Studio.isPlaying(cl.id), cl.muted, cl.volume, cl.id.hashCode())
            }
            cols += StoneModel.Col(b.id, b.title, b.id, 0, false, Pal.accent(b.id), cells)
        }
        for (ch in 0 until AudioClip.CHANNELS) if (p.planes[ch]) {
            val cells = Studio.audioClipsOn(ch).takeLast(StoneField.ROWS).map { a ->
                StoneModel.Cell(a.id, a.name, Studio.isQueued(a.id), Studio.isPlaying(a.id), a.muted, a.volume, a.id.hashCode())
            }
            cols += StoneModel.Col(ch.toString(), Studio.CHANNEL_NAMES[ch], "audio", 1, Studio.armed[ch], Iso.PLANES[ch], cells)
        }
        val flowers = Studio.valleyFlowers.map { f -> StoneModel.FlowerSlot(f.id, f.name, f.variant, f.bloom, Studio.chainIndex(f.id)) }
        val chains = p.chains.map { it.path.toList() } + (if (chainPath.size > 1) listOf(chainPath.map { floatArrayOf(it[0] / width, it[1] / height) }) else emptyList())
        val status = when {
            t.audioRec == Transport.Rec.ARMED -> "Count-in… ${t.beat + 1}"
            t.audioRec == Transport.Rec.RECORDING -> "Recording: tap ● REC to stop"
            Studio.sceneQueued -> "Next scene in ${Math.ceil(maxOf(0.0, (t.queuedAt - t.tick) / Ticks.PPQ)).toInt()} beats"
            t.playing -> "${t.bar + 1}.${t.beat + 1}  ·  ${p.bpm.toInt()} BPM"
            cols.all { it.cells.isEmpty() } -> "Record clips in the studio, or tap + AUDIO and ● REC"
            else -> "Tap stones to queue them, then ▶ PLAY"
        }
        return StoneModel(cols, flowers, chains, t.playing, Studio.sceneQueued, Studio.armed.any { it }, t.audioRec, (t.beat + 1).toInt(), status, p.planes.any { !it })
    }

    override fun paint(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val time = (System.nanoTime() - t0) / 1e9f
        StoneField.draw(c, w, h, model(), time, selected, hits)
        drawFader(c, w, h)
        if (burying != null) {
            val sh = minOf(h / 6f, Px.dp(120f))
            stone[0] = w / 2 - sh * 0.8f; stone[1] = h - Px.dp(8f) - sh * 1.2f; stone[2] = w / 2 + sh * 0.8f; stone[3] = h - Px.dp(8f)
            with(Iso) { c.tombstone(w / 2, h - Px.dp(8f), sh, overStone(), ripP) }
            c.dot(curX - Px.u, curY - Px.u, Pal.CREAM, 3)
        }
    }

    // ------------------------------------------------------------------ selection fader

    private val fader = FloatArray(4)
    private var faderTop = 0f; private var faderH = 1f
    private fun drawFader(c: Canvas, w: Float, h: Float) {
        fader.fill(0f)
        val id = selected ?: return
        val hit = hits.firstOrNull { it.id == id && (it.kind == StoneField.K_CLIP || it.kind == StoneField.K_TAKE || it.kind == StoneField.K_FLOWER) } ?: return
        val isFlower = hit.kind == StoneField.K_FLOWER
        val value = if (isFlower) Studio.project.flowers.firstOrNull { it.id == id }?.bloom ?: 0f else Studio.volumeOf(id)
        val fh = Px.dp(96f)
        var fx = hit.r + Px.dp(4f)
        if (fx > w - Px.dp(30f)) fx = hit.l - Px.dp(26f)
        val top = (hit.cy - fh / 2).coerceIn(Px.dp(8f), h - fh - Px.dp(8f))
        // a groove cut in a slim stone, with a pebble for the knob
        StoneField.stone(c, fx - Px.dp(4f), top - Px.dp(6f), fx + Px.dp(22f), top + fh + Px.dp(6f), 777, Stones.SLATE, 245)
        c.fill(fx + Px.dp(7f), top + Px.dp(6f), fx + Px.dp(11f), top + fh - Px.dp(6f), Stones.SLATE.line)
        val ky = top + fh - Px.dp(6f) - value * (fh - Px.dp(12f))
        c.fill(fx + Px.dp(7f), ky, fx + Px.dp(11f), top + fh - Px.dp(6f), if (isFlower) Pal.ROSE else Pal.AQUA)
        c.pixelBox(fx - Px.dp(2f), ky - Px.dp(6f), fx + Px.dp(20f), ky + Px.dp(6f), Stones.SANDSTONE.light, border = Stones.SANDSTONE.line, shade = false)
        small.color = Pal.CREAM
        c.textCentered(if (isFlower) "bloom" else "vol", fx + Px.dp(9f), top - Px.dp(12f), small)
        fader[0] = fx - Px.dp(14f); fader[1] = top - Px.dp(14f); fader[2] = fx + Px.dp(32f); fader[3] = top + fh + Px.dp(14f)
        faderTop = top + Px.dp(6f); faderH = fh - Px.dp(12f)
    }

    // ------------------------------------------------------------------ touch

    private var downHit: StoneField.Hit? = null
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
        if (moved || hh.kind !in listOf(StoneField.K_CLIP, StoneField.K_TAKE, StoneField.K_FLOWER, StoneField.K_CHANNEL)) return@Runnable
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
                    if (burying == null && hh != null && hh.kind == StoneField.K_FLOWER) {
                        chainFlower = hh.id; chainPath.clear(); chainClips.clear()
                        chainPath += floatArrayOf(hh.cx, hh.cy)
                    }
                }
                if (chainFlower != null) {
                    val last = chainPath.last()
                    if (hypot(x - last[0], y - last[1]) > Px.dp(6f)) chainPath += floatArrayOf(x, y)
                    hitAt(x, y)?.let { if (it.kind == StoneField.K_CLIP || it.kind == StoneField.K_TAKE) chainClips += it.id }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val b = burying
                when {
                    faderDrag -> { faderDrag = false; Studio.save() }
                    b != null -> {
                        if (overStone()) { if (buryKind == StoneField.K_CHANNEL) Studio.removePlane(b.toInt()) else Studio.bury(b) }
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

    private fun tap(hh: StoneField.Hit?) {
        if (hh == null) { selected = null; return }
        when (hh.kind) {
            StoneField.K_BACK -> { Studio.save(); backToStudio?.invoke() }
            StoneField.K_PLAY -> Studio.playScene()
            StoneField.K_STOP -> Studio.stopScene()
            StoneField.K_REC -> Studio.recordAudio()
            StoneField.K_PLUS -> Studio.addPlane()
            StoneField.K_CHANNEL -> Studio.toggleArm(hh.id.toInt())
            StoneField.K_BLOCK -> Studio.message(Blocks.type(hh.id)?.blurb ?: "")
            else -> {
                val now = System.currentTimeMillis()
                val double = lastTapId == hh.id && now - lastTapAt < 300
                pendingTap?.let { removeCallbacks(it) }; pendingTap = null
                if (double) {
                    lastTapId = null
                    if (hh.kind == StoneField.K_FLOWER) { Studio.clearChain(hh.id); Studio.message("Daisy chain undone") } else Studio.toggleMute(hh.id)
                    return
                }
                lastTapId = hh.id; lastTapAt = now
                val single = Runnable { pendingTap = null; selected = hh.id; if (hh.kind != StoneField.K_FLOWER) Studio.toggleQueued(hh.id) }
                pendingTap = single
                postDelayed(single, 260)
            }
        }
    }

    override fun onDetachedFromWindow() { pendingTap?.let { removeCallbacks(it) }; removeCallbacks(longPress); super.onDetachedFromWindow() }
}
