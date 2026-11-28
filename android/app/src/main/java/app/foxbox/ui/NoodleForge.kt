package app.foxbox.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.foxbox.Noodler
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.seq.Theory
import app.foxbox.seq.Ticks
import app.foxbox.ui.Snes.lamps
import app.foxbox.ui.Snes.plate

/**
 * Noodle Forge: a one-time set-up for a song, in its own full-screen wizard while
 * everything else waits. Knock four steady beats (the tempo), sing or play the idea
 * (the scale), choose the scale and keep it. Nothing in the song changes until
 * "Keep it". The ✕ leaves at any time.
 */
class NoodleForge(private val a: Activity) {
    private enum class St { READY, COUNT, IDEA, WORKING, RESULT, FAIL, DONE }
    private var st = St.READY
    private var stSince = System.nanoTime()
    private var result: Noodler.ForgeResult? = null
    private var failWhy = ""
    private var picked = 0
    private var doneText = listOf<String>()

    private val dialog = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val root = FrameLayout(a)
    private val art = ForgeView()
    private val keys = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private val middle = FrameLayout(a)
    private val play = Handler(Looper.getMainLooper())

    private val barH get() = Px.dp(44)
    private val keysH get() = Px.dp(56)

    init {
        root.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(middle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(keys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Px.dp(42), Gravity.BOTTOM).apply { bottomMargin = Px.dp(10); rightMargin = Px.dp(16); leftMargin = Px.dp(16) })
        val x = SnesKey(a, "✕", Snes.RED).apply { onClick = { close() } }
        root.addView(x, FrameLayout.LayoutParams(Px.dp(40), Px.dp(30), Gravity.TOP or Gravity.END).apply { topMargin = Px.dp(7); rightMargin = Px.dp(10) })
        dialog.setContentView(root)
        dialog.setOnCancelListener { close() }
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        Noodler.onForged = { res, why ->
            if (dialog.isShowing) {
                if (res != null) { result = res; picked = 0; go(St.RESULT) } else { failWhy = why; go(St.FAIL) }
            }
        }
        go(St.READY)
    }

    // everything else waits while the song is being set up
    private var radioWas = false
    private val monitorWas = BooleanArray(Studio.monitor.size)
    private fun suspendAll() {
        Studio.stop()
        Audio.post { for (e in Audio.engines) e.allOff() }
        radioWas = app.foxbox.radio.Radio.playing
        if (radioWas) app.foxbox.radio.Radio.stop()
        for (i in monitorWas.indices) { monitorWas[i] = Studio.monitor[i]; Studio.monitor[i] = false }
        Studio.refreshLive()
    }
    private fun resumeAll() {
        for (i in monitorWas.indices) Studio.monitor[i] = monitorWas[i]
        Studio.refreshLive()
        if (radioWas) app.foxbox.radio.Radio.play()
    }

    fun show() {
        suspendAll()
        dialog.show()
        @Suppress("DEPRECATION")
        dialog.window?.decorView?.systemUiVisibility = a.window.decorView.systemUiVisibility
        val prefs = a.getSharedPreferences("noodler", Activity.MODE_PRIVATE)
        if (!prefs.getBoolean("forge_intro", false)) {
            prefs.edit().putBoolean("forge_intro", true).apply()
            SnesDialog(a, "Welcome to Noodle Forge", wide = 0.8f)
                .message("Noodle Forge sets up your song from a quick idea. Everything else waits while you're here.\n\n" +
                    "1. Knock or tap 4 steady beats. That sets the tempo.\n" +
                    "2. Straight after, sing or play your idea, one note at a time, and tap Done.\n" +
                    "3. Pick the scale that sounds right and tap Keep it.\n\n" +
                    "Nothing in your song changes until you tap Keep it.")
                .key("Let's forge", Snes.GREEN, then = null).show()
        }
    }

    private fun close() {
        stopPlaying()
        if (Noodler.active) Noodler.cancel()
        Noodler.onForged = null
        art.animated = false
        dialog.dismiss()
        resumeAll()
    }

    // ------------------------------------------------------------------ the steps

    private fun go(s: St) {
        st = s; stSince = System.nanoTime()
        keys.removeAllViews(); middle.removeAllViews()
        fun key(text: String, color: Int, fn: () -> Unit) =
            keys.addView(SnesKey(a, text, color).apply { onClick = fn }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = Px.dp(10) })
        when (s) {
            St.READY -> key("Start", Snes.GREEN) { stopPlaying(); Noodler.start(Noodler.Mode.FORGE); go(St.COUNT) }
            St.COUNT -> key("Start over", Snes.YELLOW) { restart() }
            St.IDEA -> {
                key("Start over", Snes.YELLOW) { restart() }
                key("Done", Snes.GREEN) { go(St.WORKING); Noodler.stop() }
            }
            St.WORKING -> {}
            St.RESULT -> {
                val r = result!!
                key("Try again", Snes.YELLOW) { restart() }
                key("▶ My idea", Snes.PURPLE) { playIdea(r) }
                key("Keep it", Snes.GREEN) { keep(r) }
                val grid = ScaleGrid(a, r.cands, r.sung, initial = 0,
                    onPick = { i -> picked = i; art.invalidate() },
                    noteOn = { id, n -> Audio.post { Audio.preview.noteOn(0x7D000040 + id, n, 0.8f) } },
                    noteOff = { id -> Audio.post { Audio.preview.noteOff(0x7D000040 + id) } })
                // every scale, stacked top to bottom, the full width
                middle.addView(ScrollView(a).apply { addView(grid); isVerticalScrollBarEnabled = true },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        topMargin = barH + Px.dp(84); bottomMargin = keysH + Px.dp(4)
                        leftMargin = Px.dp(20); rightMargin = Px.dp(20)
                    })
            }
            St.FAIL -> key("Try again", Snes.GREEN) { restart() }
            St.DONE -> key("Start making music", Snes.GREEN) { close() }
        }
        // only the listening steps change by themselves (lights, the note, the meter)
        art.animated = s == St.COUNT || s == St.IDEA
        art.invalidate()
    }

    private fun restart() {
        stopPlaying()
        if (Noodler.active) Noodler.cancel()
        Noodler.start(Noodler.Mode.FORGE)
        go(St.COUNT)
    }

    private fun keep(r: Noodler.ForgeResult) {
        stopPlaying()
        val (root, si) = r.cands[picked.coerceIn(0, r.cands.size - 1)]
        Studio.setBpm(r.bpm.toFloat())
        Studio.setKey(root = root, scale = si)
        val clip = Studio.addNoodleClip(r.engine, r.notes, r.lengthTicks)
        Studio.project.forged = true
        Studio.save()
        doneText = listOf("${r.bpm} BPM", "${Theory.NOTE_NAMES[root]} ${Theory.SCALES[si].name}",
            if (clip != null) "Your idea is saved as the clip \"${clip.name}\" on ${r.engine.title}." else "${r.engine.title} had no room for your idea's clip.")
        go(St.DONE)
    }

    /** The idea as it was sung (tuned), on a plain soft tone. */
    private fun playIdea(r: Noodler.ForgeResult) {
        stopPlaying()
        val msPerTick = 60000.0 / r.bpm / Ticks.PPQ
        r.notes.take(96).forEachIndexed { k, n ->
            val on = (n.tick * msPerTick).toLong(); val off = on + (n.dur * msPerTick).toLong().coerceAtLeast(90)
            play.postDelayed({ Audio.post { Audio.preview.noteOn(0x7D000100 + k, n.note, n.vel) } }, on)
            play.postDelayed({ Audio.post { Audio.preview.noteOff(0x7D000100 + k) } }, off)
        }
    }

    private fun stopPlaying() {
        play.removeCallbacksAndMessages(null)
        Audio.post { Audio.preview.allOff() }
    }

    // ------------------------------------------------------------------ the picture

    @SuppressLint("ViewConstructor")
    private inner class ForgeView : PixelView(a) {
        private val title = Px.text(13f, Snes.INK, headFont = true, align = Paint.Align.LEFT)
        private val head = Px.text(16f, Snes.INK, headFont = true)
        private val body = Px.text(14f, Snes.INK)
        private val small = Px.text(12f, Snes.INK, headFont = true)
        private val lcd = Px.text(40f, Snes.YELLOW, headFont = true)

        override fun paint(c: Canvas) { with(Snes) {
            val w = width.toFloat(); val h = height.toFloat()
            val bh = barH.toFloat()
            c.fill(0f, 0f, w, h, DEEP)
            c.plate(0f, 0f, w, h, FACE)
            // the title bar, like every panel's, with the steps along it
            c.fill(u * 3, u * 3, w - u * 3, bh, FACE_D)
            c.fill(u * 3, bh, w - u * 3, bh + u, PURPLE)
            c.lamps(Px.dp(12f), bh / 2, Px.dp(8f))
            title.textSize = Px.dp(14f)
            c.drawText("NOODLE FORGE", Px.dp(68f), bh / 2 - (title.descent() + title.ascent()) / 2, title)
            val at = when (st) { St.READY -> 0; St.COUNT -> 1; St.IDEA, St.WORKING -> 2; St.RESULT, St.FAIL -> 3; St.DONE -> 4 }
            val names = listOf("1 Beat", "2 Idea", "3 Scale")
            val pw = Px.dp(76f); var px = w - Px.dp(64f) - pw * 3
            for ((i, nm) in names.withIndex()) {
                val done = at > i + 1; val now = at == i + 1
                c.plate(px, Px.dp(9f), px + pw - Px.dp(6f), bh - Px.dp(7f), if (now) HI else FACE)
                val lx = px + Px.dp(8f); val ly = (Px.dp(9f) + bh - Px.dp(7f)) / 2
                c.fill(lx, ly - Px.dp(4f), lx + Px.dp(8f), ly + Px.dp(4f), INK)
                c.fill(lx + u, ly - Px.dp(4f) + u, lx + Px.dp(8f) - u, ly + Px.dp(4f) - u, if (done) GREEN else if (now) YELLOW else SHADE)
                small.textSize = Px.dp(11f); small.color = if (now || done) INK else DEEP; small.textAlign = Paint.Align.LEFT
                c.drawText(nm, lx + Px.dp(13f), ly - (small.descent() + small.ascent()) / 2, small)
                px += pw
            }
            small.textAlign = Paint.Align.CENTER; small.color = INK

            val top = bh + Px.dp(14f); val bottom = h - keysH - Px.dp(6f)
            val since = (System.nanoTime() - stSince) / 1e9f
            when (st) {
                St.READY -> ready(c, w, top, bottom)
                St.COUNT -> {
                    if (Noodler.phase == 1) post { if (st == St.COUNT) go(St.IDEA) }
                    count(c, w, top, bottom, since)
                }
                St.IDEA -> idea(c, w, top, bottom, since)
                St.WORKING -> c.textCentered("Listening back carefully…", w / 2, (top + bottom) / 2, head)
                St.RESULT -> resultPanel(c, w, top, bottom)
                St.FAIL -> {
                    c.textCentered("That one didn't take.", w / 2, top + Px.dp(30f), head)
                    wrap(c, failWhy, w / 2, top + Px.dp(64f), minOf(w - Px.dp(64f), Px.dp(560f)), body, Px.dp(22f))
                }
                St.DONE -> {
                    c.textCentered("Your song is set up.", w / 2, top + Px.dp(30f), head)
                    if (doneText.size >= 3) {
                        window(c, w / 2 - Px.dp(170f), top + Px.dp(56f), w / 2 + Px.dp(170f), top + Px.dp(116f))
                        lcd.textSize = Px.dp(28f)
                        c.textCentered(doneText[0] + "  ·  " + doneText[1], w / 2, top + Px.dp(86f), lcd)
                        wrap(c, doneText[2], w / 2, top + Px.dp(150f), minOf(w - Px.dp(64f), Px.dp(560f)), body, Px.dp(22f))
                    }
                }
            }
        } }

        /** A dark backlit window, like the knobs' value windows. */
        private fun window(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
            c.fill(l, t, r, b, Snes.INK)
            c.fill(l + u * 2, t + u * 2, r - u * 2, b - u * 2, 0xFF1A1A24.toInt())
        }

        private fun ready(c: Canvas, w: Float, top: Float, bottom: Float) { with(Snes) {
            c.textCentered("Set up your song from a quick idea", w / 2, top + Px.dp(22f), head)
            val steps = listOf("Knock or tap\n4 steady beats", "Sing or play\nyour idea", "Pick the scale\nand keep it")
            val gap = Px.dp(14f)
            val bw = minOf(Px.dp(200f), (w - Px.dp(64f) - gap * 2) / 3f)
            val total = bw * 3 + gap * 2
            val t = top + Px.dp(48f); val bh = minOf(Px.dp(118f), bottom - t - Px.dp(8f))
            for ((i, sp) in steps.withIndex()) {
                val l = (w - total) / 2 + i * (bw + gap)
                c.plate(l, t, l + bw, t + bh, FACE)
                val s = Px.dp(22f)
                c.fill(l + bw / 2 - s / 2, t + Px.dp(12f), l + bw / 2 + s / 2, t + Px.dp(12f) + s, INK)
                c.fill(l + bw / 2 - s / 2 + u, t + Px.dp(12f) + u, l + bw / 2 + s / 2 - u, t + Px.dp(12f) + s - u, ACCENTS[i])
                small.color = HI; c.textCentered("${i + 1}", l + bw / 2, t + Px.dp(12f) + s / 2, small); small.color = INK
                body.textSize = Px.dp(14f)
                sp.split('\n').forEachIndexed { j, line -> c.textCentered(line, l + bw / 2, t + Px.dp(56f) + j * Px.dp(20f), body) }
            }
        } }

        private fun count(c: Canvas, w: Float, top: Float, bottom: Float, since: Float) { with(Snes) {
            c.textCentered("Knock or tap 4 steady beats", w / 2, top + Px.dp(22f), head)
            val lit = Noodler.beatsLit
            val sz = minOf(Px.dp(72f), (bottom - top - Px.dp(130f)).coerceAtLeast(Px.dp(40f))); val gap = Px.dp(18f)
            val l0 = (w - sz * 4 - gap * 3) / 2; val t = top + Px.dp(44f)
            for (k in 0 until 4) {
                val l = l0 + k * (sz + gap)
                c.plate(l, t, l + sz, t + sz, FACE)
                val m = sz * 0.22f
                c.fill(l + m, t + m, l + sz - m, t + sz - m, INK)
                c.fill(l + m + u, t + m + u, l + sz - m - u, t + sz - m - u, if (k < lit) ACCENTS[k] else DEEP)
                small.color = if (k < lit) HI else SHADE
                c.textCentered("${k + 1}", l + sz / 2, t + sz / 2, small)
            }
            small.color = INK
            val hint = Noodler.countHint.ifEmpty { quietHint(since) }
            if (hint.isNotEmpty()) { body.color = PURPLE_D; c.textCentered(hint, w / 2, t + sz + Px.dp(28f), body); body.color = INK }
            meter(c, w, bottom)
        } }

        private fun idea(c: Canvas, w: Float, top: Float, bottom: Float, since: Float) { with(Snes) {
            c.textCentered("Now sing or play your idea, then tap Done", w / 2, top + Px.dp(22f), head)
            val t = top + Px.dp(42f)
            // the tempo the knocks set, and the note heard right now
            window(c, Px.dp(24f), t, Px.dp(150f), t + Px.dp(66f))
            lcd.textSize = Px.dp(30f); lcd.color = YELLOW
            c.textCentered("${Math.round(Noodler.countBpm)}", Px.dp(87f), t + Px.dp(28f), lcd)
            small.color = SHADE; c.textCentered("BPM", Px.dp(87f), t + Px.dp(54f), small); small.color = INK
            val n = Noodler.noteNow
            window(c, w / 2 - Px.dp(70f), t, w / 2 + Px.dp(70f), t + Px.dp(66f))
            lcd.textSize = Px.dp(40f); lcd.color = if (n >= 0) GREEN else DEEP
            c.textCentered(if (n >= 0) Theory.NOTE_NAMES[Math.floorMod(n, 12)] else "–", w / 2, t + Px.dp(33f), lcd)
            lcd.color = YELLOW
            // the notes heard so far (a rough look; they're worked out properly when you tap Done)
            val trail = Noodler.caught().takeLast(14)
            val cw = Px.dp(32f); val tl = (w - trail.size * cw) / 2; val ty = t + Px.dp(80f)
            for ((i, m) in trail.withIndex()) {
                c.plate(tl + i * cw, ty, tl + i * cw + cw - Px.dp(4f), ty + Px.dp(28f), if (i == trail.size - 1) HI else FACE)
                c.textCentered(Theory.NOTE_NAMES[Math.floorMod(m, 12)], tl + i * cw + (cw - Px.dp(4f)) / 2, ty + Px.dp(14f), small)
            }
            if (trail.isEmpty()) { small.color = DEEP; c.textCentered("Notes you sing or play show up here", w / 2, ty + Px.dp(14f), small); small.color = INK }
            val hint = quietHint(since)
            if (hint.isNotEmpty()) { body.color = PURPLE_D; c.textCentered(hint, w / 2, ty + Px.dp(50f), body); body.color = INK }
            meter(c, w, bottom)
        } }

        private fun resultPanel(c: Canvas, w: Float, top: Float, bottom: Float) { with(Snes) {
            val r = result ?: return
            val (root, si) = r.cands[picked.coerceIn(0, r.cands.size - 1)]
            // the strip on top: what was forged, and how to choose
            val l = Px.dp(20f); val wr = l + Px.dp(220f)
            window(c, l, top - Px.dp(4f), wr, top + Px.dp(62f))
            lcd.textSize = Px.dp(20f); lcd.color = YELLOW
            c.textCentered("${r.bpm} BPM", (l + wr) / 2, top + Px.dp(14f), lcd)
            lcd.textSize = Px.dp(17f); lcd.color = GREEN
            textFitLcd(c, "${Theory.NOTE_NAMES[root]} ${Theory.SCALES[si].name}", (l + wr) / 2, top + Px.dp(42f), wr - l - Px.dp(12f))
            lcd.color = YELLOW
            val tune = when {
                r.cents <= -8 -> "You were ${-r.cents} cents flat, so your idea was tuned up. "
                r.cents >= 8 -> "You were ${r.cents} cents sharp, so your idea was tuned down. "
                else -> ""
            }
            val how = tune + "Scales for your idea, best first. Lit notes are the ones you used. Play a scale's notes to hear it, and tap its name to choose it."
            body.textSize = Px.dp(13f); body.textAlign = Paint.Align.LEFT
            wrap(c, how, wr + Px.dp(16f), top + Px.dp(10f), w - wr - Px.dp(40f), body, Px.dp(18f))
            body.textAlign = Paint.Align.CENTER; body.textSize = Px.dp(14f)
        } }

        private fun textFitLcd(c: Canvas, s: String, cx: Float, cy: Float, maxW: Float) {
            while (lcd.measureText(s) > maxW && lcd.textSize > Px.dp(10f)) lcd.textSize -= Px.dp(1f)
            c.textCentered(s, cx, cy, lcd)
        }

        /** After a few seconds of hearing nothing loud enough, say so. */
        private fun quietHint(since: Float): String =
            if (since > 3f && Noodler.peakDb < Noodler.sensitivity) "Can't hear you yet. Get closer to the mic, or lower the threshold (hold NOODLE)." else ""

        /** How loud the mic is, with the line a sound has to cross to count. */
        private fun meter(c: Canvas, w: Float, bottom: Float) { with(Snes) {
            val mw = minOf(Px.dp(300f), w * 0.45f); val l = (w - mw) / 2; val y = bottom - Px.dp(18f)
            small.textAlign = Paint.Align.RIGHT
            c.drawText("MIC", l - Px.dp(8f), y + Px.dp(10f), small); small.textAlign = Paint.Align.CENTER
            c.fill(l, y, l + mw, y + Px.dp(12f), INK)
            fun xOf(db: Float) = l + u + (mw - u * 2) * ((db + 70f) / 64f).coerceIn(0f, 1f)
            val lv = Noodler.levelDb
            c.fill(l + u, y + u, xOf(lv), y + Px.dp(12f) - u, if (lv > Noodler.sensitivity) GREEN else DEEP)
            val tx = xOf(Noodler.sensitivity)
            c.fill(tx - u / 2, y - Px.dp(3f), tx + u / 2, y + Px.dp(15f), RED)
        } }

        /** Word-wrapped text; returns the y below it. */
        private fun wrap(c: Canvas, s: String, x: Float, top: Float, maxW: Float, p: Paint, lineH: Float): Float {
            var line = ""; var y = top
            for (word in s.split(' ')) {
                val t = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(t) > maxW && line.isNotEmpty()) { c.drawText(line, x, y, p); y += lineH; line = word } else line = t
            }
            if (line.isNotEmpty()) { c.drawText(line, x, y, p); y += lineH }
            return y
        }
    }
}
