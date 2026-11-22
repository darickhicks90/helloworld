package app.fiveiy.kids

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.foxbox.ui.Pal
import app.foxbox.ui.Px
import app.foxbox.ui.pixelBox
import app.foxbox.ui.Tv
import app.foxbox.ui.fill
import app.foxbox.ui.textCentered
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Little Yeti's one screen. Three instruments on the tabs (synth, drums, sampler), each with:
 * the silly-effects XY pad on the left (it always springs back home), its few controls in the
 * middle, and eight big keys (or drum pads) along the bottom. No words: everything is a symbol.
 */
@SuppressLint("ViewConstructor")
class KidsView(c: Context, private val host: Host) : View(c) {
    interface Host {
        /** Ask for the microphone, then run `then` if allowed. */
        fun withMic(then: () -> Unit)
        fun sampleRecorded(d: FloatArray)
        fun changed()
    }

    /** 0 synth, 1 drums, 2 sampler. */
    var page = 0; set(v) { field = v; invalidate() }

    private val d = Px.dp(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val noteP = Px.text(26f, Pal.BROWN_D, headFont = true)
    private val bigP = Px.text(48f, Pal.CREAM, headFont = true)
    private val path = Path()

    // ---- layout
    private val tabs = Array(3) { RectF() }
    private val rootDown = RectF(); private val rootUp = RectF(); private val rootBox = RectF()
    private val xy = RectF(); private val panel = RectF(); private val keysR = RectF()
    private val keys = Array(8) { RectF() }
    private val waves = Array(4) { RectF() }
    private val filterTrack = RectF(); private val filterCol = RectF(); private val envBtn = RectF()
    private val pitchTrack = RectF(); private val pitchCol = RectF()
    private val adsr = RectF()
    private val recBtn = RectF(); private val sampleStrip = RectF()
    private val hits = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutAll(w.toFloat(), h.toFloat())

    private fun layoutAll(w: Float, h: Float) {
        val pad = 10 * d
        val topH = (h * 0.16f).coerceIn(48 * d, 62 * d)
        val tw = topH * 1.22f
        for (i in 0 until 3) tabs[i].set(pad + i * (tw + 8 * d), pad, pad + i * (tw + 8 * d) + tw, pad + topH)
        rootUp.set(w - pad - 56 * d, pad, w - pad, pad + topH)
        rootBox.set(rootUp.left - 6 * d - 78 * d, pad, rootUp.left - 6 * d, pad + topH)
        rootDown.set(rootBox.left - 6 * d - 56 * d, pad, rootBox.left - 6 * d, pad + topH)
        val keysH = (h * 0.27f).coerceIn(80 * d, 150 * d)
        keysR.set(pad, h - pad - keysH, w - pad, h - pad)
        val kw = (keysR.width() - 7 * 6 * d) / 8
        for (i in 0 until 8) keys[i].set(keysR.left + i * (kw + 6 * d), keysR.top, keysR.left + i * (kw + 6 * d) + kw, keysR.bottom)
        val midT = pad + topH + pad; val midB = keysR.top - pad
        val side = min(midB - midT, w * 0.34f)
        xy.set(pad, midT, pad + side, midT + side)
        panel.set(xy.right + pad, midT, w - pad, midB)
        val ph = panel.height(); val inner = 8 * d
        // the filter column (all three pages): sun at the top, cloud at the bottom, envelope button underneath
        // a slider column: the slider runs the full height (sun at the top, cloud at the bottom);
        // the filter's envelope button sits beside it, so the slider stays long even on small phones
        fun column(col: RectF, track: RectF, left: Float, withEnv: Boolean) {
            val sw = 50 * d
            col.set(left, panel.top + inner, left + sw + (if (withEnv) 58 * d else 0f), panel.bottom - inner)
            val cx = col.left + sw / 2
            track.set(cx - 13 * d, col.top + 26 * d, cx + 13 * d, col.bottom - 26 * d)
            if (withEnv) { val bh = min(56 * d, col.height()); envBtn.set(col.left + sw + 4 * d, col.centerY() - bh / 2, col.right, col.centerY() + bh / 2) }
        }
        when (page) {
            0 -> {
                val g = min((ph - inner * 2 - 6 * d) / 2, min(96 * d, panel.width() * 0.14f))
                for (i in 0 until 4) {
                    val x = panel.left + inner + (i % 2) * (g + 6 * d); val y = panel.top + inner + (i / 2) * (g + 6 * d) + (ph - inner * 2 - 2 * g - 6 * d) / 2
                    waves[i].set(x, y, x + g, y + g)
                }
                column(filterCol, filterTrack, waves[1].right + 12 * d, true)
                adsr.set(filterCol.right + 12 * d, panel.top + inner, panel.right - inner, panel.bottom - inner)
            }
            1 -> {
                column(pitchCol, pitchTrack, panel.left + inner, false)
                column(filterCol, filterTrack, pitchCol.right + 12 * d, true)
                adsr.set(filterCol.right + 12 * d, panel.top + inner, panel.right - inner, panel.bottom - inner)
            }
            else -> {
                val s = min(ph - inner * 2 - 26 * d, (panel.width() - 66 * d) * 0.32f)
                recBtn.set(panel.left + inner, panel.top + inner, panel.left + inner + s, panel.top + inner + s)
                sampleStrip.set(recBtn.left, recBtn.bottom + 6 * d, recBtn.right, panel.bottom - inner)
                column(filterCol, filterTrack, recBtn.right + 12 * d, true)
                adsr.set(filterCol.right + 12 * d, panel.top + inner, panel.right - inner, panel.bottom - inner)
            }
        }
    }

    /** 5 Eye Yeti's cottage-style box: a cream (or lit) face with a dark brown pixel border. */
    private fun plateOf(c: Canvas, l: Float, t: Float, r: Float, b: Float, face: Int = Pal.CREAM) = c.pixelBox(l, t, r, b, face)
    private val INK = Pal.BROWN_D
    private val tvs = arrayOf(TvScreen(0) { Sound.synth }, TvScreen(1) { Sound.drums }, TvScreen(2) { Sound.sampler })
    private val t0 = System.nanoTime()

    private val inst get() = when (page) { 0 -> Sound.synth; 1 -> Sound.drums; else -> Sound.sampler }
    private val shape get() = if (page == 2) Sound.sampler.shape else Sound.synth.shape

    // ------------------------------------------------------------------ drawing

    // the studio's warm, faded colours (not bright toy colours)
    private val KEY_COLORS = intArrayOf(Pal.ROSE, Pal.ORANGE_L, Pal.HONEY, Pal.GREEN_L, Pal.AQUA_L, Pal.AQUA, 0xFFB08FA8.toInt(), Pal.ROSE)
    private val pressedKeys = BooleanArray(8)
    private val padFlash = FloatArray(8)
    private var dotX = 0f; private var dotY = 0f; private var dotHeld = false

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        layoutAll(w, h)
        Tv.wood(c, 0f, 0f, w, h)
        // tabs: a wave (synth), a drum, a microphone (sampler)
        for (i in 0 until 3) {
            val r = tabs[i]; val on = page == i
            plateOf(c, r.left, r.top, r.right, r.bottom, if (on) Pal.ORANGE else Pal.CREAM)
            val ic = if (on) Pal.CREAM else INK
            when (i) { 0 -> waveIcon(c, r, 3, ic); 1 -> drumIcon(c, r, ic); else -> micIcon(c, r, ic) }
        }
        // the root note: ◀ C ▶ (always a major scale)
        plateOf(c, rootDown.left, rootDown.top, rootDown.right, rootDown.bottom); arrow(c, rootDown, -1)
        plateOf(c, rootUp.left, rootUp.top, rootUp.right, rootUp.bottom); arrow(c, rootUp, 1)
        plateOf(c, rootBox.left, rootBox.top, rootBox.right, rootBox.bottom, Pal.PAPER)
        noteP.textSize = 24 * d
        c.textCentered(Sound.NOTE_NAMES[Sound.root], rootBox.centerX(), rootBox.centerY(), noteP)
        drawXy(c)
        plateOf(c, panel.left, panel.top, panel.right, panel.bottom, Pal.PAPER)
        when (page) {
            0 -> { for (i in 0 until 4) { val r = waves[i]; val on = Sound.synth.wave == i; plateOf(c, r.left, r.top, r.right, r.bottom, if (on) Pal.AQUA else Pal.CREAM); waveIcon(c, r, i, if (on) Pal.CREAM else INK) }
                filterColumn(c); drawAdsr(c) }
            1 -> { pitchColumn(c); filterColumn(c); drawBeats(c) }
            else -> { drawRec(c); filterColumn(c); drawAdsr(c) }
        }
        drawKeys(c)
        // the dot springs home when let go
        if (!dotHeld) { dotX *= 0.75f; dotY *= 0.75f; if (abs(dotX) < 0.002f) dotX = 0f; if (abs(dotY) < 0.002f) dotY = 0f }
        for (i in 0 until 8) padFlash[i] *= 0.88f
        postInvalidateOnAnimation()
    }

    private fun arrow(c: Canvas, r: RectF, dir: Int) {
        path.reset(); val cx = r.centerX(); val cy = r.centerY(); val s = 11 * d
        path.moveTo(cx + dir * s, cy); path.lineTo(cx - dir * s * 0.7f, cy - s); path.lineTo(cx - dir * s * 0.7f, cy + s); path.close()
        paint.color = INK; c.drawPath(path, paint)
    }

    private fun drawXy(c: Canvas) {
        val r = xy
        val pitch = page != 0
        val t = (System.nanoTime() - t0) / 1e9f
        // where the dot shows on the screen, 0..1 (it can reach 16 dp from each edge)
        val sx = 0.5f + dotX * (r.width() / 2 - 16 * d) / r.width(); val sy = 0.5f + dotY * (r.height() / 2 - 16 * d) / r.height()
        tvs[page].draw(c, r, t, sx, sy, dotHeld) { cc, w, h ->
            // the four effects, as pictures at the edges (cream, with a soft shadow so they read on any picture)
            val s = min(w, h) * 0.09f
            for (pass in 0..1) {
                val col = if (pass == 0) Pal.alpha(INK, 0.45f) else Pal.alpha(Pal.CREAM, 0.95f)
                val o = if (pass == 0) 1.5f * d else 0f
                if (pitch) waveSym(cc, s * 1.6f + o, h / 2 + o, s * 1.2f, 1, col) else bubbles(cc, s * 1.5f + o, h / 2 + o, s, col)
                if (pitch) waveSym(cc, w - s * 1.6f + o, h / 2 + o, s * 1.2f, 4, col) else waveSym(cc, w - s * 1.6f + o, h / 2 + o, s * 1.1f, 3, col)
                echoSym(cc, w / 2 + o, s * 1.4f + o, s, col)
                zigzag(cc, w / 2 + o, h - s * 1.4f + o, s, col)
            }
        }
        val fx = inst.fx
        fx.x = if (dotHeld) dotX else 0f; fx.y = if (dotHeld) dotY else 0f
        fx.pitchMode = pitch
    }

    private fun filterColumn(c: Canvas) {
        val col = filterCol; val t = filterTrack
        sun(c, t.centerX(), t.top - 13 * d, 9 * d, Pal.HONEY)
        cloud(c, t.centerX(), t.bottom + 14 * d, 8 * d, Pal.BROWN_L)
        slider(c, t, inst.filter, Pal.AQUA)
        val on = inst.filterEnv
        plateOf(c, envBtn.left, envBtn.top, envBtn.right, envBtn.bottom, if (on) Pal.GREEN else Pal.CREAM)
        envIcon(c, envBtn, if (on) Pal.CREAM else INK)
    }

    private fun pitchColumn(c: Canvas) {
        val col = pitchCol; val t = pitchTrack
        // small and high at the top, big and low at the bottom
        paint.color = Pal.DUSK; c.drawCircle(t.centerX(), t.top - 13 * d, 5 * d, paint)
        c.drawCircle(t.centerX(), t.bottom + 14 * d, 9 * d, paint)
        slider(c, t, Sound.drums.pitch, Pal.DUSK)
    }

    private fun slider(c: Canvas, t: RectF, v: Float, color: Int) {
        c.fill(t.left + 8 * d, t.top, t.right - 8 * d, t.bottom, INK)
        val y = t.bottom - v * t.height()
        c.fill(t.left + 10 * d, y, t.right - 10 * d, t.bottom - 2 * d, color)
        plateOf(c, t.left - 8 * d, y - 13 * d, t.right + 8 * d, y + 13 * d, Pal.CREAM)
        c.fill(t.left - 2 * d, y - d, t.right + 2 * d, y + d, Pal.BROWN)
    }

    // the envelope graph: three big handles to drag (attack top, decay/sustain corner, release end)
    private val A = 0; private val DS = 1; private val R = 2
    private fun nLog(v: Float, lo: Float, hi: Float) = (ln(v / lo) / ln(hi / lo)).coerceIn(0f, 1f)
    private fun fromLog(n: Float, lo: Float, hi: Float) = lo * Math.pow((hi / lo).toDouble(), n.coerceIn(0f, 1f).toDouble()).toFloat()
    private val handle = FloatArray(6)
    private fun adsrPoints() {
        val s = shape; val g = adsr; val m = 18 * d
        val x0 = g.left + m; val gw = g.width() - 2 * m; val top = g.top + m; val bot = g.bottom - m
        val xa = x0 + gw * 0.25f * (0.08f + 0.92f * nLog(s.attack, Shape.A_MIN, Shape.A_MAX))
        val xd = xa + gw * 0.25f * (0.08f + 0.92f * nLog(s.decay, Shape.D_MIN, Shape.D_MAX))
        val ys = bot - (bot - top) * s.sustain
        val xs = xd + gw * 0.16f
        val xr = xs + gw * 0.34f * (0.08f + 0.92f * nLog(s.release, Shape.R_MIN, Shape.R_MAX))
        handle[0] = xa; handle[1] = top; handle[2] = xd; handle[3] = ys; handle[4] = xr; handle[5] = bot
        hold = xs
    }
    private var hold = 0f
    private fun drawAdsr(c: Canvas) {
        val g = adsr
        c.fill(g.left, g.top, g.right, g.bottom, Tv.BEZEL)
        adsrPoints()
        val m = 18 * d; val x0 = g.left + m; val bot = g.bottom - m
        path.reset(); path.moveTo(x0, bot); path.lineTo(handle[0], handle[1]); path.lineTo(handle[2], handle[3]); path.lineTo(hold, handle[3]); path.lineTo(handle[4], bot)
        val fillPath = Path(path); fillPath.lineTo(x0, bot); fillPath.close()
        paint.color = Pal.alpha(Pal.AQUA, 0.3f); c.drawPath(fillPath, paint)
        stroke.color = Pal.AQUA_L; stroke.strokeWidth = 4 * d; c.drawPath(path, stroke)
        val cols = intArrayOf(Pal.ORANGE, Pal.HONEY, Pal.GREEN_L)
        for (k in 0 until 3) {
            val x = handle[k * 2]; val y = handle[k * 2 + 1]
            paint.color = INK; c.drawCircle(x, y, 13 * d, paint)
            paint.color = cols[k]; c.drawCircle(x, y, 10 * d, paint)
            paint.color = 0x80FFFFFF.toInt(); c.drawCircle(x - 3 * d, y - 3 * d, 3 * d, paint)
        }
    }

    /** The drums' panel: a flash for each pad as it's hit. */
    private fun drawBeats(c: Canvas) {
        val g = adsr
        c.fill(g.left, g.top, g.right, g.bottom, Tv.BEZEL)
        val cw = g.width() / 4; val ch = g.height() / 2
        for (i in 0 until 8) {
            val cx = g.left + cw * (i % 4 + 0.5f); val cy = g.top + ch * (i / 4 + 0.5f)
            val r = min(cw, ch) * (0.22f + 0.2f * padFlash[i])
            paint.color = Pal.alpha(KEY_COLORS[i], 0.25f + 0.75f * padFlash[i]); c.drawCircle(cx, cy, r, paint)
        }
    }

    private val REC = 0xFFC4513F.toInt()   // a faded tape-deck red
    private fun drawRec(c: Canvas) {
        val r = recBtn; val st = Recorder.state
        val cx = r.centerX(); val cy = r.centerY(); val rad = min(r.width(), r.height()) / 2
        paint.color = INK; c.drawCircle(cx, cy, rad, paint)
        val t = System.nanoTime() / 1e9f
        val red = if (st == Recorder.State.RECORDING) Pal.alpha(REC, 0.75f + 0.25f * sin(t * 8f)) else REC
        paint.color = red; c.drawCircle(cx, cy, rad - 5 * d, paint)
        paint.color = 0x40FFFFFF; c.drawCircle(cx - rad * 0.25f, cy - rad * 0.25f, rad * 0.3f, paint)
        when (st) {
            Recorder.State.COUNT_IN -> { bigP.textSize = rad * 0.9f; c.textCentered("${Recorder.beat}", cx, cy, bigP) }
            Recorder.State.RECORDING -> {
                // how loud, and how long (the ring fills up to 10 seconds)
                paint.color = Pal.CREAM; c.drawCircle(cx, cy, rad * (0.2f + 0.35f * Recorder.level), paint)
                stroke.color = Pal.CREAM; stroke.strokeWidth = 6 * d
                hits.set(cx - rad + 10 * d, cy - rad + 10 * d, cx + rad - 10 * d, cy + rad - 10 * d)
                c.drawArc(hits, -90f, 360f * (Recorder.seconds / Recorder.MAX_SECONDS).coerceIn(0f, 1f), false, stroke)
            }
            else -> { paint.color = Pal.CREAM; c.drawCircle(cx, cy, rad * 0.28f, paint) }
        }
        // the recording, as a little wave
        val s = sampleStrip
        if (s.height() > 10 * d) {
            c.fill(s.left, s.top, s.right, s.bottom, Tv.BEZEL)
            val pk = Sound.sampler.peaks; val bw = s.width() / pk.size
            paint.color = Pal.AQUA_L
            for (i in pk.indices) { val hh = max(d, pk[i] * (s.height() - 6 * d)); c.drawRect(s.left + i * bw, s.centerY() - hh / 2, s.left + i * bw + max(d, bw - d), s.centerY() + hh / 2, paint) }
        }
    }

    private fun drawKeys(c: Canvas) {
        for (i in 0 until 8) {
            val r = keys[i]; val down = if (page == 1) padFlash[i] > 0.6f else pressedKeys[i]
            val col = KEY_COLORS[i]
            val off = if (down) 3 * d else 0f
            plateOf(c, r.left, r.top + off, r.right, r.bottom, if (down) brighten(col) else col)
            if (page == 1) padIcon(c, i, r.centerX(), r.centerY() + off, min(r.width(), r.height()) * 0.28f)
            else if (i == 0 || i == 7) star(c, r.centerX(), r.bottom - r.height() * 0.25f + off, min(r.width(), r.height()) * 0.12f)
        }
    }

    private fun brighten(c: Int): Int {
        fun up(v: Int) = (v + (255 - v) * 0.4f).toInt()
        return (0xFF shl 24) or (up(c shr 16 and 0xFF) shl 16) or (up(c shr 8 and 0xFF) shl 8) or up(c and 0xFF)
    }

    // ------------------------------------------------------------------ symbols

    /** Waveform symbols: 0 sine, 1 triangle, 2 square, 3 saw. */
    private fun waveIcon(c: Canvas, r: RectF, kind: Int, color: Int) {
        val w = r.width() * 0.56f; val h = r.height() * 0.3f; val l = r.centerX() - w / 2; val cy = r.centerY()
        path.reset()
        when (kind) {
            0 -> { for (k in 0..40) { val x = l + w * k / 40; val y = cy - h * sin(2 * PI * k / 40).toFloat(); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) } }
            1 -> { path.moveTo(l, cy); path.lineTo(l + w * 0.25f, cy - h); path.lineTo(l + w * 0.75f, cy + h); path.lineTo(l + w, cy) }
            2 -> { path.moveTo(l, cy + h); path.lineTo(l, cy - h); path.lineTo(l + w / 2, cy - h); path.lineTo(l + w / 2, cy + h); path.lineTo(l + w, cy + h); path.lineTo(l + w, cy - h) }
            else -> { path.moveTo(l, cy + h); path.lineTo(l + w / 2, cy - h); path.lineTo(l + w / 2, cy + h); path.lineTo(l + w, cy - h); path.lineTo(l + w, cy + h) }
        }
        stroke.color = color; stroke.strokeWidth = 4 * d; c.drawPath(path, stroke)
    }
    private fun drumIcon(c: Canvas, r: RectF, color: Int) {
        val cx = r.centerX(); val cy = r.centerY() + 4 * d; val w = r.width() * 0.26f; val h = r.height() * 0.2f
        stroke.color = color; stroke.strokeWidth = 3.5f * d
        hits.set(cx - w, cy - h - h * 0.4f, cx + w, cy - h + h * 0.4f); c.drawOval(hits, stroke)
        c.drawLine(cx - w, cy - h, cx - w, cy + h * 0.8f, stroke); c.drawLine(cx + w, cy - h, cx + w, cy + h * 0.8f, stroke)
        hits.set(cx - w, cy + h * 0.8f - h * 0.4f, cx + w, cy + h * 0.8f + h * 0.4f); c.drawArc(hits, 0f, 180f, false, stroke)
        c.drawLine(cx - w * 0.3f, cy - h * 2.2f, cx + w * 0.1f, cy - h * 1.2f, stroke); c.drawLine(cx + w * 0.6f, cy - h * 2.3f, cx + w * 0.3f, cy - h * 1.2f, stroke)
    }
    private fun micIcon(c: Canvas, r: RectF, color: Int) {
        val cx = r.centerX(); val cy = r.centerY() - 4 * d; val s = r.height() * 0.17f
        paint.color = color; hits.set(cx - s * 0.6f, cy - s * 1.3f, cx + s * 0.6f, cy + s * 0.6f); c.drawRoundRect(hits, s * 0.6f, s * 0.6f, paint)
        stroke.color = color; stroke.strokeWidth = 3 * d
        hits.set(cx - s, cy - s * 0.6f, cx + s, cy + s * 1.1f); c.drawArc(hits, 0f, 180f, false, stroke)
        c.drawLine(cx, cy + s * 1.1f, cx, cy + s * 1.7f, stroke); c.drawLine(cx - s * 0.6f, cy + s * 1.7f, cx + s * 0.6f, cy + s * 1.7f, stroke)
    }
    private fun envIcon(c: Canvas, r: RectF, color: Int) {
        val w = r.width() * 0.6f; val h = r.height() * 0.42f; val l = r.centerX() - w / 2; val b = r.centerY() + h / 2
        path.reset(); path.moveTo(l, b); path.lineTo(l + w * 0.15f, b - h); path.quadTo(l + w * 0.3f, b - h * 0.3f, l + w * 0.6f, b - h * 0.35f); path.lineTo(l + w * 0.75f, b - h * 0.35f); path.quadTo(l + w * 0.85f, b, l + w, b)
        stroke.color = color; stroke.strokeWidth = 3.5f * d; c.drawPath(path, stroke)
    }
    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.color = color; c.drawCircle(cx, cy, r * 0.55f, paint)
        stroke.color = color; stroke.strokeWidth = 2.5f * d
        for (k in 0 until 8) { val a = k * PI / 4; c.drawLine(cx + cos(a).toFloat() * r * 0.8f, cy + sin(a).toFloat() * r * 0.8f, cx + cos(a).toFloat() * r * 1.2f, cy + sin(a).toFloat() * r * 1.2f, stroke) }
    }
    private fun cloud(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.color = color
        c.drawCircle(cx - r * 0.8f, cy + r * 0.2f, r * 0.6f, paint); c.drawCircle(cx, cy - r * 0.15f, r * 0.8f, paint); c.drawCircle(cx + r * 0.8f, cy + r * 0.2f, r * 0.6f, paint)
        c.drawRect(cx - r * 0.8f, cy + r * 0.1f, cx + r * 0.8f, cy + r * 0.8f, paint)
    }
    private fun bubbles(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = 2.5f * d
        c.drawCircle(cx, cy + s * 0.6f, s * 0.55f, stroke); c.drawCircle(cx + s * 0.4f, cy - s * 0.4f, s * 0.38f, stroke); c.drawCircle(cx - s * 0.2f, cy - s * 1.1f, s * 0.25f, stroke)
    }
    /** A wavy line: `cycles` across (1 = big and slow, 4 = small and fast). */
    private fun waveSym(c: Canvas, cx: Float, cy: Float, s: Float, cycles: Int, color: Int) {
        path.reset(); val w = s * 1.6f; val amp = s * (if (cycles <= 1) 0.7f else 0.35f)
        for (k in 0..32) { val x = cx - w / 2 + w * k / 32; val y = cy - amp * sin(2 * PI * cycles * k / 32).toFloat(); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        stroke.color = color; stroke.strokeWidth = (if (cycles <= 1) 4f else 2.5f) * d; c.drawPath(path, stroke)
    }
    private fun echoSym(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        paint.color = color; c.drawCircle(cx - s * 0.8f, cy, s * 0.22f, paint)
        stroke.color = color
        for (k in 1..3) { stroke.strokeWidth = (3.2f - k * 0.6f) * d; stroke.alpha = 255 - k * 55; hits.set(cx - s * 0.8f - s * 0.45f * k, cy - s * 0.45f * k, cx - s * 0.8f + s * 0.45f * k, cy + s * 0.45f * k); c.drawArc(hits, -50f, 100f, false, stroke) }
        stroke.alpha = 255
    }
    private fun zigzag(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset(); val w = s * 1.6f
        for (k in 0..6) { val x = cx - w / 2 + w * k / 6; val y = cy + (if (k % 2 == 0) -s * 0.35f else s * 0.35f); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        stroke.color = color; stroke.strokeWidth = 2.5f * d; stroke.strokeJoin = Paint.Join.MITER; c.drawPath(path, stroke); stroke.strokeJoin = Paint.Join.ROUND
    }
    private fun star(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        for (k in 0 until 10) { val a = -PI / 2 + k * PI / 5; val rr = if (k % 2 == 0) r else r * 0.45f; val x = cx + (cos(a) * rr).toFloat(); val y = cy + (sin(a) * rr).toFloat(); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close(); paint.color = Pal.CREAM; c.drawPath(path, paint)
    }
    /** Drum pad pictures: kick, snare, clap, closed hat, open hat, low tom, high tom, cowbell. */
    private fun padIcon(c: Canvas, i: Int, cx: Float, cy: Float, s: Float) {
        val ink = INK
        paint.color = ink; stroke.color = ink; stroke.strokeWidth = 3 * d
        when (i) {
            0 -> { c.drawCircle(cx, cy, s, paint); paint.color = 0x55FFFFFF; c.drawCircle(cx, cy, s * 0.45f, paint) }
            1 -> { hits.set(cx - s, cy - s * 0.5f, cx + s, cy + s * 0.5f); c.drawRect(hits, stroke); for (k in 0..3) { val x = cx - s + s * 2 * k / 3; c.drawLine(x, cy - s * 0.5f, x + s * 0.3f, cy + s * 0.5f, stroke) } }
            2 -> { hits.set(cx - s, cy - s * 0.6f, cx - s * 0.1f, cy + s * 0.6f); c.drawOval(hits, paint); hits.set(cx + s * 0.1f, cy - s * 0.6f, cx + s, cy + s * 0.6f); c.drawOval(hits, paint)
                   for (k in -1..1) c.drawLine(cx + k * s * 0.35f, cy - s * 0.8f, cx + k * s * 0.6f, cy - s * 1.2f, stroke) }
            3 -> { hits.set(cx - s, cy - s * 0.3f, cx + s, cy); c.drawOval(hits, paint); hits.set(cx - s, cy, cx + s, cy + s * 0.3f); c.drawOval(hits, paint); c.drawLine(cx, cy - s, cx, cy + s, stroke) }
            4 -> { hits.set(cx - s, cy - s * 0.7f, cx + s, cy - s * 0.35f); c.drawOval(hits, paint); hits.set(cx - s, cy + s * 0.35f, cx + s, cy + s * 0.7f); c.drawOval(hits, paint); c.drawLine(cx, cy - s, cx, cy + s, stroke) }
            5 -> { c.drawCircle(cx, cy, s * 0.85f, stroke); c.drawCircle(cx, cy, s * 0.15f, paint) }
            6 -> { c.drawCircle(cx, cy, s * 0.5f, stroke); c.drawCircle(cx, cy, s * 0.12f, paint) }
            else -> { path.reset(); path.moveTo(cx - s * 0.45f, cy - s * 0.8f); path.lineTo(cx + s * 0.45f, cy - s * 0.8f); path.lineTo(cx + s * 0.8f, cy + s * 0.8f); path.lineTo(cx - s * 0.8f, cy + s * 0.8f); path.close(); c.drawPath(path, paint) }
        }
    }

    // ------------------------------------------------------------------ touch

    private sealed class Grab {
        class Key(val i: Int) : Grab()
        object Xy : Grab()
        class Slider(val pitch: Boolean) : Grab()
        class Handle(val k: Int) : Grab()
        object None : Grab()
    }
    private val grabs = HashMap<Int, Grab>()
    private var xyPointer = -1

    private fun keyAt(x: Float, y: Float): Int { for (i in 0 until 8) if (keys[i].contains(x, y)) return i; return -1 }

    private fun press(id: Int, i: Int) {
        if (page == 1) { Sound.drums.hit(i); padFlash[i] = 1f }
        else {
            val semis = Sound.root + Sound.MAJOR[i]
            if (page == 0) Sound.synth.noteOn(id * 16 + i, 60 + semis) else Sound.sampler.noteOn(id * 16 + i, (if (Sound.root > 6) Sound.root - 12 else Sound.root) + Sound.MAJOR[i])
            pressedKeys[i] = true
        }
    }
    private fun releaseKey(id: Int, i: Int) {
        if (page == 0) Sound.synth.noteOff(id * 16 + i) else if (page == 2) Sound.sampler.noteOff(id * 16 + i)
        pressedKeys[i] = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex; val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx)
                grabs[id] = down(id, x, y)
            }
            MotionEvent.ACTION_MOVE -> for (k in 0 until e.pointerCount) {
                val id = e.getPointerId(k); move(id, grabs[id] ?: continue, e.getX(k), e.getY(k))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_CANCEL) (0 until e.pointerCount).map { e.getPointerId(it) } else listOf(e.getPointerId(e.actionIndex))
                for (id in ids) {
                    when (val g = grabs.remove(id)) {
                        is Grab.Key -> releaseKey(id, g.i)
                        is Grab.Xy -> { dotHeld = false; xyPointer = -1 }
                        is Grab.Slider, is Grab.Handle -> host.changed()
                        else -> {}
                    }
                }
            }
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float): Grab {
        keyAt(x, y).takeIf { it >= 0 }?.let { press(id, it); return Grab.Key(it) }
        if (xy.contains(x, y) && xyPointer < 0) { xyPointer = id; dotHeld = true; setDot(x, y); return Grab.Xy }
        for (i in 0 until 3) if (tabs[i].contains(x, y)) { if (page != i) { allUp(); for (f in arrayOf(Sound.synth.fx, Sound.drums.fx, Sound.sampler.fx)) { f.x = 0f; f.y = 0f }; page = i; layoutAll(width.toFloat(), height.toFloat()); tap(); host.changed() }; return Grab.None }
        if (rootDown.contains(x, y)) { Sound.root = (Sound.root + 11) % 12; tap(); host.changed(); return Grab.None }
        if (rootUp.contains(x, y)) { Sound.root = (Sound.root + 1) % 12; tap(); host.changed(); return Grab.None }
        val inFilter = hits.apply { set(filterTrack); inset(-24 * d, -16 * d) }.contains(x, y)
        if (inFilter) { setSlider(false, y); return Grab.Slider(false) }
        if (page == 1 && hits.apply { set(pitchTrack); inset(-24 * d, -16 * d) }.contains(x, y)) { setSlider(true, y); return Grab.Slider(true) }
        if (envBtn.contains(x, y)) { inst.filterEnv = !inst.filterEnv; tap(); host.changed(); return Grab.None }
        if (page == 0) for (i in 0 until 4) if (waves[i].contains(x, y)) { Sound.synth.wave = i; tap(); host.changed(); return Grab.None }
        if (page == 2 && recBtn.contains(x, y)) { recTap(); return Grab.None }
        if (page != 1 && adsr.contains(x, y)) {
            adsrPoints()
            var best = -1; var bd = 44 * d
            for (k in 0 until 3) { val dd = hypot(x - handle[k * 2], y - handle[k * 2 + 1]); if (dd < bd) { bd = dd; best = k } }
            if (best >= 0) return Grab.Handle(best)
        }
        return Grab.None
    }

    private fun move(id: Int, g: Grab, x: Float, y: Float) {
        when (g) {
            is Grab.Key -> {
                // sliding along the keys plays each one in turn
                val k = keyAt(x, y)
                if (k >= 0 && k != g.i) { releaseKey(id, g.i); press(id, k); grabs[id] = Grab.Key(k) }
            }
            is Grab.Xy -> setDot(x, y)
            is Grab.Slider -> setSlider(g.pitch, y)
            is Grab.Handle -> dragHandle(g.k, x, y)
            else -> {}
        }
    }

    private fun setDot(x: Float, y: Float) {
        dotX = ((x - xy.centerX()) / (xy.width() / 2 - 16 * d)).coerceIn(-1f, 1f)
        dotY = (-(y - xy.centerY()) / (xy.height() / 2 - 16 * d)).coerceIn(-1f, 1f)
    }

    private fun setSlider(pitch: Boolean, y: Float) {
        val t = if (pitch) pitchTrack else filterTrack
        val v = ((t.bottom - y) / t.height()).coerceIn(0f, 1f)
        if (pitch) Sound.drums.pitch = v else inst.filter = v
    }

    private fun dragHandle(k: Int, x: Float, y: Float) {
        val s = shape; val g = adsr; val m = 18 * d
        val x0 = g.left + m; val gw = g.width() - 2 * m; val top = g.top + m; val bot = g.bottom - m
        adsrPoints()
        fun norm(dx: Float, span: Float) = ((dx / span - 0.08f) / 0.92f).coerceIn(0f, 1f)
        when (k) {
            A -> s.attack = fromLog(norm(x - x0, gw * 0.25f), Shape.A_MIN, Shape.A_MAX)
            DS -> { s.decay = fromLog(norm(x - handle[0], gw * 0.25f), Shape.D_MIN, Shape.D_MAX); s.sustain = ((bot - y) / (bot - top)) }
            R -> s.release = fromLog(norm(x - hold, gw * 0.34f), Shape.R_MIN, Shape.R_MAX)
        }
    }

    private fun recTap() {
        if (Recorder.state != Recorder.State.IDLE) { Recorder.stop(); return }
        host.withMic {
            Recorder.start { take -> post { if (take != null) { Sound.sampler.setSample(take); host.sampleRecorded(take) } else noSound() } }
        }
        tap()
    }

    /** Nothing was heard: the button gives a little shake. */
    private fun noSound() { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    private fun allUp() {
        for ((id, g) in grabs) if (g is Grab.Key) releaseKey(id, g.i)
        grabs.clear(); dotHeld = false; xyPointer = -1
    }

    private fun tap() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}
