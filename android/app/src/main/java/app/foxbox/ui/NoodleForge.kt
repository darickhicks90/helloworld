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
import app.foxbox.seq.Clip
import app.foxbox.seq.Theory
import app.foxbox.seq.Ticks

/**
 * Noodle Forge: its own full-screen mode for starting a song from a quick idea.
 * Tap four steady beats (that's the tempo), sing or play the idea (that's the
 * scale), then choose the scale and keep it. Nothing in the song changes until
 * "Keep it" is tapped. The ✕ closes it at any time.
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
    private val keys = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val middle = FrameLayout(a)
    private val play = Handler(Looper.getMainLooper())

    init {
        root.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(middle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = Px.dp(98); bottomMargin = Px.dp(64); leftMargin = Px.dp(16); rightMargin = Px.dp(16)
        })
        root.addView(keys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Px.dp(48), Gravity.BOTTOM).apply { bottomMargin = Px.dp(10) })
        val x = Btn(a, "✕", Pal.ROSE).apply { onClick = { close() } }
        root.addView(x, FrameLayout.LayoutParams(Px.dp(40), Px.dp(32), Gravity.TOP or Gravity.END).apply { topMargin = Px.dp(6); rightMargin = Px.dp(8) })
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
                    "1. Tap, clap or knock 4 steady beats. That sets the tempo.\n" +
                    "2. Straight after, sing or play your idea, one note at a time, and tap Done.\n" +
                    "3. Pick the scale that sounds right and tap Keep it.\n\n" +
                    "Your song's tempo and scale are set, and your idea is saved as a clip. Nothing in your song changes until you tap Keep it.")
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

    // ------------------------------------------------------------------ states

    private fun go(s: St) {
        st = s; stSince = System.nanoTime()
        keys.removeAllViews(); middle.removeAllViews()
        fun key(text: String, accent: Int, w: Int = 150, fn: () -> Unit) =
            keys.addView(Btn(a, text, accent).apply { onClick = fn }, LinearLayout.LayoutParams(Px.dp(w), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = Px.dp(6); rightMargin = Px.dp(6) })
        when (s) {
            St.READY -> key("Start", Pal.GREEN, 180) { stopPlaying(); Noodler.start(Noodler.Mode.FORGE); go(St.COUNT) }
            St.COUNT -> key("Start over", Pal.ORANGE) { restart() }
            St.IDEA -> {
                key("Start over", Pal.ORANGE) { restart() }
                key("✓ Done", Pal.GREEN) { go(St.WORKING); Noodler.stop() }
            }
            St.WORKING -> {}
            St.RESULT -> {
                val r = result!!
                key("Try again", Pal.ORANGE) { restart() }
                key("▶ My idea", Pal.AQUA) { playIdea(r) }
                key("✓ Keep it", Pal.GREEN) { keep(r) }
                val grid = ScaleGrid(a, r.cands, r.sung, initial = 0,
                    onPick = { i -> picked = i; art.invalidate() },
                    noteOn = { id, n -> val e = r.engine; Audio.post { e.noteOn(0x7D000040 + id, n, 0.75f) } },
                    noteOff = { id -> val e = r.engine; Audio.post { e.noteOff(0x7D000040 + id) } })
                middle.addView(ScrollView(a).apply { addView(grid); isVerticalScrollBarEnabled = false })
            }
            St.FAIL -> key("Try again", Pal.GREEN, 180) { restart() }
            St.DONE -> key("Start making music", Pal.GREEN, 220) { close() }
        }
        art.animated = true
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
            if (clip != null) "Your idea is the clip \"${clip.name}\" on ${r.engine.title}" else "${r.engine.title} had no room for your idea's clip")
        go(St.DONE)
    }

    private fun playIdea(r: Noodler.ForgeResult) {
        stopPlaying()
        val e = r.engine
        val msPerTick = 60000.0 / r.bpm / Ticks.PPQ
        r.notes.take(64).forEachIndexed { k, n ->
            val on = (n.tick * msPerTick).toLong(); val off = on + (n.dur * msPerTick).toLong().coerceAtLeast(80)
            play.postDelayed({ Audio.post { e.noteOn(0x7D000010 + k, n.note, n.vel) } }, on)
            play.postDelayed({ Audio.post { e.noteOff(0x7D000010 + k) } }, off)
        }
    }

    private fun stopPlaying() {
        play.removeCallbacksAndMessages(null)
        val e = result?.engine ?: return
        Audio.post { for (k in 0 until 80) e.noteOff(0x7D000010 + k) }
    }

    // ------------------------------------------------------------------ the picture

    @SuppressLint("ViewConstructor")
    private inner class ForgeView : PixelView(a) {
        private val t0 = System.nanoTime()
        private val title = Px.text(20f, Pal.CREAM, headFont = true, align = Paint.Align.LEFT)
        private val step = Px.text(11f, 0xFFB8A6C0.toInt(), headFont = true, align = Paint.Align.LEFT)
        private val head = Px.text(17f, Pal.CREAM, headFont = true)
        private val body = Px.text(14f, 0xFFE8DCEA.toInt())
        private val big = Px.text(64f, Pal.SUN, headFont = true)
        private val small = Px.text(12f, Pal.CREAM, headFont = true)
        private val bg = intArrayOf(0xFF15101B.toInt(), 0xFF18121E.toInt(), 0xFF1C1420.toInt(), 0xFF211621.toInt(), 0xFF291821.toInt(), 0xFF361B1F.toInt(), 0xFF4A201B.toInt(), 0xFF642917.toInt())

        override fun paint(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val time = (System.nanoTime() - t0) / 1e9f
            // the forge: dark up top, ember glow along the bottom, sparks drifting up
            c.bands(0f, 0f, w, h, bg)
            for (k in 0 until 36) {
                val sx = ((k * 7919) % 1000) / 1000f * w
                val sp = 0.25f + ((k * 131) % 100) / 160f
                val life = ((time * sp + k * 0.137f) % 1f)
                val y = h - life * h * 0.75f
                val x = sx + kotlin.math.sin(time * 1.3f + k) * Px.dp(6f)
                val col = if (life < 0.3f) 0xFFFFD27A.toInt() else if (life < 0.65f) 0xFFF08A3A.toInt() else 0xFF8A3A2A.toInt()
                c.fill(x, y, x + u * 2, y + u * 2, col)
            }
            // the title, flickering like the key that opened it
            c.fill(0f, 0f, w, Px.dp(44f), 0xCC0E0A12.toInt())
            c.fill(0f, Px.dp(44f), w, Px.dp(44f) + u, 0xFF6A3A7A.toInt())
            title.color = NoodlerKey.flicker(time)
            c.drawText("NOODLE FORGE", Px.dp(16f), Px.dp(30f), title)
            val stepText = when (st) {
                St.READY -> "A new song from a quick idea"
                St.COUNT -> "Step 1 of 3 · The beat"
                St.IDEA -> "Step 2 of 3 · Your idea"
                St.WORKING -> "Working it out…"
                St.RESULT -> "Step 3 of 3 · The scale"
                St.FAIL -> "That one didn't take"
                St.DONE -> "Your song is set up"
            }
            c.drawText(stepText, Px.dp(16f) + title.measureText("NOODLE FORGE") + Px.dp(16f), Px.dp(29f), step)
            // where you are in the set-up
            val at = when (st) { St.READY -> 0; St.COUNT -> 1; St.IDEA, St.WORKING -> 2; St.RESULT, St.FAIL -> 3; St.DONE -> 4 }
            val names = listOf("Beat", "Idea", "Scale")
            val pw = Px.dp(62f); var px = w - Px.dp(64f) - pw * 3
            for ((i, nm) in names.withIndex()) {
                val done = at > i + 1; val now = at == i + 1
                c.fill(px, Px.dp(16f), px + pw - Px.dp(6f), Px.dp(30f), if (now) Pal.ORANGE else if (done) Pal.GREEN else 0xFF2E2430.toInt())
                small.color = if (now || done) Pal.CREAM else 0xFF6A5A70.toInt(); small.textSize = Px.dp(10f)
                c.textCentered((if (done) "✓ " else "") + nm, px + (pw - Px.dp(6f)) / 2, Px.dp(23f), small)
                px += pw
            }
            small.textSize = Px.dp(12f)

            val since = (System.nanoTime() - stSince) / 1e9f
            when (st) {
                St.READY -> ready(c, w, h)
                St.COUNT -> {
                    if (Noodler.phase == 1) { post { if (st == St.COUNT) go(St.IDEA) } }
                    count(c, w, h, since)
                }
                St.IDEA -> idea(c, w, h, since)
                St.WORKING -> c.textCentered("Hammering it into shape…", w / 2, h / 2, head)
                St.RESULT -> resultHead(c, w)
                St.DONE -> done(c, w, h)
                St.FAIL -> {
                    c.textCentered("Hmm, that didn't take.", w / 2, Px.dp(86f), head)
                    wrap(c, failWhy, w / 2, Px.dp(124f), minOf(w - Px.dp(48f), Px.dp(560f)), body, Px.dp(22f))
                }
            }
        }

        private fun ready(c: Canvas, w: Float, h: Float) {
            c.textCentered("Hum it, and we'll build the song around it.", w / 2, Px.dp(76f), head)
            val steps = listOf("1" to "Tap, clap or knock\n4 steady beats", "2" to "Sing or play\nyour idea", "3" to "Pick the scale\nand keep it")
            val bw = minOf(Px.dp(190f), (w - Px.dp(64f)) / 3f); val gap = Px.dp(14f)
            val total = bw * 3 + gap * 2
            val top = Px.dp(104f); val bh = minOf(Px.dp(120f), h - top - Px.dp(84f))
            for ((i, sp) in steps.withIndex()) {
                val l = (w - total) / 2 + i * (bw + gap)
                c.pixelBox(l, top, l + bw, top + bh, 0xFF2A2033.toInt(), border = 0xFF0C080F.toInt())
                big.textSize = Px.dp(30f); big.color = Pal.SUN
                c.textCentered(sp.first, l + bw / 2, top + bh * 0.3f, big)
                body.textSize = Px.dp(13f)
                sp.second.split('\n').forEachIndexed { j, line -> c.textCentered(line, l + bw / 2, top + bh * 0.62f + j * Px.dp(18f), body) }
            }
            body.textSize = Px.dp(14f)
        }

        private fun count(c: Canvas, w: Float, h: Float, since: Float) {
            c.textCentered("Tap, clap or knock 4 steady beats", w / 2, Px.dp(76f), head)
            val lit = Noodler.beatsLit
            val sz = minOf(Px.dp(76f), (h - Px.dp(260f)).coerceAtLeast(Px.dp(44f))); val gap = Px.dp(18f)
            val l0 = (w - sz * 4 - gap * 3) / 2; val top = Px.dp(108f)
            for (k in 0 until 4) {
                val l = l0 + k * (sz + gap)
                val on = k < lit
                if (on) c.fill(l - Px.dp(6f), top - Px.dp(6f), l + sz + Px.dp(6f), top + sz + Px.dp(6f), 0x55F4A259)
                c.pixelBox(l, top, l + sz, top + sz, if (on) Pal.ORANGE else 0xFF2E2430.toInt(), border = 0xFF0C080F.toInt())
                big.textSize = sz * 0.5f; big.color = if (on) Pal.CREAM else 0xFF5A4A60.toInt()
                c.textCentered("${k + 1}", l + sz / 2, top + sz / 2, big)
            }
            val hint = Noodler.countHint.ifEmpty { quietHint(since) }
            if (hint.isNotEmpty()) { small.color = Pal.SUN; c.textCentered(hint, w / 2, top + sz + Px.dp(26f), small) }
            meter(c, w, h)
        }

        private fun idea(c: Canvas, w: Float, h: Float, since: Float) {
            c.textCentered("Now sing or play your idea. Tap Done when you've finished.", w / 2, Px.dp(76f), head)
            // the tempo the count set, with its beat pulsing along
            val bpm = Noodler.countBpm
            val beat = 60000.0 / bpm.coerceAtLeast(1.0)
            val pos = (Noodler.clockMs() - Noodler.downbeat) / beat
            val inBar = Math.floorMod(kotlin.math.floor(pos).toInt(), 4)
            val frac = (pos - kotlin.math.floor(pos)).toFloat()
            val lx = Px.dp(28f); val top = Px.dp(104f)
            small.color = 0xFFB8A6C0.toInt(); small.textAlign = Paint.Align.LEFT
            c.drawText("TEMPO", lx, top + Px.dp(10f), small)
            big.textSize = Px.dp(38f); big.color = Pal.SUN; big.textAlign = Paint.Align.LEFT
            c.drawText("${Math.round(bpm)}", lx, top + Px.dp(52f), big)
            small.color = Pal.CREAM
            c.drawText("BPM", lx + big.measureText("${Math.round(bpm)}") + Px.dp(6f), top + Px.dp(52f), small)
            big.textAlign = Paint.Align.CENTER; small.textAlign = Paint.Align.CENTER
            for (k in 0 until 4) {
                val bx = lx + k * Px.dp(22f)
                val on = k == inBar && pos >= 0
                val s = if (on) Px.dp(16f) * (1f - frac * 0.4f) else Px.dp(10f)
                c.fill(bx + (Px.dp(16f) - s) / 2, top + Px.dp(70f) + (Px.dp(16f) - s) / 2, bx + (Px.dp(16f) + s) / 2, top + Px.dp(70f) + (Px.dp(16f) + s) / 2,
                    if (on) (if (k == 0) Pal.ORANGE else Pal.SUN) else 0xFF4A3A50.toInt())
            }
            // the note it hears now
            val n = Noodler.noteNow
            big.textSize = Px.dp(64f); big.color = if (n >= 0) Pal.SUN else 0xFF4A3A50.toInt()
            c.textCentered(if (n >= 0) Theory.NOTE_NAMES[Math.floorMod(n, 12)] + (n / 12 - 1) else "…", w / 2, top + Px.dp(44f), big)
            // the notes caught so far
            val trail = Noodler.caught().takeLast(12)
            val cw = Px.dp(36f); val tl = (w - trail.size * cw) / 2; val ty = top + Px.dp(96f)
            for ((i, m) in trail.withIndex()) {
                c.pixelBox(tl + i * cw, ty, tl + i * cw + cw - Px.dp(4f), ty + Px.dp(28f), if (i == trail.size - 1) Pal.ORANGE_L else Pal.PAPER, shade = false)
                small.color = Pal.BROWN_D
                c.textCentered(Theory.NOTE_NAMES[Math.floorMod(m, 12)], tl + i * cw + (cw - Px.dp(4f)) / 2, ty + Px.dp(14f), small)
            }
            if (trail.isEmpty()) { small.color = 0xFFB8A6C0.toInt(); c.textCentered("Notes you sing or play show up here", w / 2, ty + Px.dp(14f), small) }
            val hint = quietHint(since)
            if (hint.isNotEmpty()) { small.color = Pal.SUN; c.textCentered(hint, w / 2, ty + Px.dp(46f), small) }
            meter(c, w, h)
        }

        private fun done(c: Canvas, w: Float, h: Float) {
            head.color = Pal.SUN
            c.textCentered("Your song is set up!", w / 2, Px.dp(92f), head)
            head.color = Pal.CREAM
            big.textSize = Px.dp(34f); big.color = Pal.SUN
            if (doneText.size >= 2) {
                c.textCentered(doneText[0] + "  ·  " + doneText[1], w / 2, Px.dp(146f), big)
                body.textSize = Px.dp(14f)
                c.textCentered(doneText[2], w / 2, Px.dp(190f), body)
            }
        }

        private fun resultHead(c: Canvas, w: Float) {
            val r = result ?: return
            val (root, si) = r.cands[picked.coerceIn(0, r.cands.size - 1)]
            head.color = Pal.SUN
            c.textCentered("Forged!  ${r.bpm} BPM · ${Theory.NOTE_NAMES[root]} ${Theory.SCALES[si].name}", w / 2, Px.dp(64f), head)
            head.color = Pal.CREAM
            val tune = when {
                r.cents <= -8 -> "You were ${-r.cents} cents flat, so your idea was tuned up. "
                r.cents >= 8 -> "You were ${r.cents} cents sharp, so your idea was tuned down. "
                else -> ""
            }
            val how = if (r.sure) "Tap a scale's name to choose it, and play its notes to hear it."
                else "Your idea fits more than one scale. Tap a name to choose it, and play its notes to hear it."
            small.color = 0xFFE8DCEA.toInt()
            c.textFit(tune + how, w / 2, Px.dp(86f), w - Px.dp(40f), small)
        }

        /** After a few seconds of hearing nothing loud enough, say so. */
        private fun quietHint(since: Float): String =
            if (since > 3f && Noodler.peakDb < Noodler.sensitivity) "Can't hear you yet. Get closer to the mic, or lower the threshold in Noodler's settings." else ""

        /** How loud the mic is, with the line a sound has to cross to count. */
        private fun meter(c: Canvas, w: Float, h: Float) {
            val mw = minOf(Px.dp(320f), w * 0.5f); val l = (w - mw) / 2; val y = h - Px.dp(84f)
            small.color = 0xFFB8A6C0.toInt(); small.textAlign = Paint.Align.RIGHT
            c.drawText("MIC", l - Px.dp(8f), y + Px.dp(10f), small); small.textAlign = Paint.Align.CENTER
            c.fill(l, y, l + mw, y + Px.dp(12f), 0xFF0C080F.toInt())
            fun xOf(db: Float) = l + mw * ((db + 70f) / 64f).coerceIn(0f, 1f)
            val lv = Noodler.levelDb
            c.fill(l + u, y + u, xOf(lv), y + Px.dp(12f) - u, if (lv > Noodler.sensitivity) Pal.GREEN_L else 0xFF5A6A40.toInt())
            val tx = xOf(Noodler.sensitivity)
            c.fill(tx - u / 2, y - Px.dp(3f), tx + u / 2, y + Px.dp(15f), Pal.CREAM)
        }

        private fun wrap(c: Canvas, s: String, cx: Float, top: Float, maxW: Float, p: Paint, lineH: Float) {
            var line = ""; var y = top
            for (word in s.split(' ')) {
                val t = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(t) > maxW && line.isNotEmpty()) { c.drawText(line, cx, y, p); y += lineH; line = word } else line = t
            }
            if (line.isNotEmpty()) c.drawText(line, cx, y, p)
        }
    }
}
