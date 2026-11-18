package app.foxbox.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.widget.LinearLayout
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.seq.Transport

/**
 * Pickup's chop pop-up: quick slices of the selected pad on the left, the
 * rhythm chop on the right. When a rhythm chop's count-in starts the pop-up
 * steps aside so the pads can be tapped; it comes back when the pass ends.
 */
object ChopPopup {
    fun show(a: Activity, host: Host, pad: Int) {
        val pu = Audio.pickup
        val d = SnesDialog(a, "Chop · pad ${pad + 1} (${pu.names[pad]})", wide = 0.94f)
        val body = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val title = Panel(a, "Slice onto pads ${pad + 1}–16", accent = Snes.YELLOW)
        val modes = listOf("4 equal slices" to "equal4", "8 equal slices" to "equal8", "16 equal slices" to "equal16",
            "At each hit" to "hits", "Every beat" to "beat", "Every half beat" to "halfbeat", "Every bar" to "bar")
        modes.forEachIndexed { k, (label, mode) ->
            title.addView(SnesItem(a, label, Snes.accent(k)).apply { onClick = { Studio.chopPad(pad, mode) } }, lp(MATCH, Px.dp(40)).apply { bottomMargin = Px.dp(2) })
        }
        title.addView(SnesItem(a, "Undo the last chop", Snes.RED).apply { onClick = { Studio.undoChop() } }, lp(MATCH, Px.dp(40)))
        left.addView(title, lp(MATCH, WRAP))
        body.addView(left, lp(Px.dp(210), WRAP))
        val rhythm = Panel(a, "Rhythm chop: tap pads in time", accent = Snes.RED)
        val dm = a.resources.displayMetrics
        rhythm.addView(RhythmChopPanel(a, host), lp(MATCH, (dm.heightPixels * 0.62f).toInt().coerceAtMost(Px.dp(380))))
        body.addView(rhythm, lp(0, WRAP, 1f).apply { leftMargin = Px.dp(8) })
        d.view(body)
        d.key("Close", Snes.RED, then = null)
        // step aside for the count-in and the pass; come back when it's done
        var last = Audio.transport.chopRec
        val watch: () -> Unit = {
            val now = Audio.transport.chopRec
            if (now != last) {
                if (last == Transport.Rec.OFF && now != Transport.Rec.OFF) d.dialog.hide()
                else if (now == Transport.Rec.OFF && d.dialog.window != null) d.dialog.show()
                last = now
            }
        }
        Studio.listen(watch)
        d.onDismiss { Studio.unlisten(watch) }
        d.show()
    }

    /**
     * The rhythm chop screen: load a sample, press Chop, and after two bars of
     * count-in the sample plays in time. Tap a pad wherever a new chop should
     * start; that slice (up to the next tap) lands on the pad you tapped.
     */
    class RhythmChopPanel(c: Context, private val host: Host) : LinearLayout(c) {
        private val pu = Audio.pickup
        private val nameP = Px.text(13f, Snes.INK)
        private val chopBtn = Btn(c, "● Chop", Snes.RED)
        private val useBtn = Btn(c, "Use pad", Snes.PURPLE)
        private val snap = Stepper(c, "Snap", 84, { when (Studio.chopSnap) { 1 -> "1/16"; 2 -> "1/8"; 4 -> "1/4"; else -> "Off" } }) { d ->
            val opts = intArrayOf(0, 1, 2, 4); val k = (opts.indexOf(Studio.chopSnap) + d).coerceIn(0, opts.size - 1); Studio.setChopSnap(opts[k])
        }
        private val wave = object : PixelView(c) {
            private var cacheFor: FloatArray? = null; private var cacheW = 0; private var peaks = FloatArray(0)
            private val lbl = Px.text(11f, Pal.CREAM); private val big = Px.text(16f, Pal.CREAM); private val num = Px.text(9f, Pal.CREAM, headFont = true)
            init { animated = true }
            override fun paint(c: Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                c.fill(0f, 0f, w, h, 0xFF26222E.toInt()); c.fill(u, u, w - u, h - u, 0xFF3A3646.toInt())
                val src = pu.chopSrc
                if (src.isEmpty()) { c.textCentered("Load a sample, or use a pad's sound", w / 2, h / 2, lbl); return }
                val cols = (w / u).toInt().coerceAtLeast(1)
                if (src !== cacheFor || cols != cacheW) {
                    cacheFor = src; cacheW = cols
                    peaks = FloatArray(cols) { k -> val a = (src.size.toLong() * k / cols).toInt(); val b = (src.size.toLong() * (k + 1) / cols).toInt(); var m = 0f; for (j in a until maxOf(a + 1, b).coerceAtMost(src.size)) m = maxOf(m, kotlin.math.abs(src[j])); m }
                }
                val mid = h / 2
                val ts = pu.chopTrimStart.toFloat() / src.size * w; val te = pu.chopTrimEnd.toFloat() / src.size * w
                for (k in 0 until cols) {
                    val x = k * u; val inside = x + u > ts && x < te
                    val ph = peaks[k] * (h / 2 - u * 3)
                    c.fill(x, mid - ph, x + u, mid + ph + u, if (inside) 0xFF9AA0C8.toInt() else 0xFF5C5870.toInt())
                }
                // trim handles: shaded outside, a flag at each edge you can drag
                c.fill(u, u, ts, h - u, Pal.alpha(0xFF000000.toInt(), 0.35f)); c.fill(te, u, w - u, h - u, Pal.alpha(0xFF000000.toInt(), 0.35f))
                for ((x, label, left) in listOf(Triple(ts, "S", true), Triple(te, "E", false))) {
                    c.fill(x - u, 0f, x + u, h, Pal.SUN)
                    val fx = if (left) x else x - Px.dp(16f)
                    c.fill(fx, h - Px.dp(18f), fx + Px.dp(16f), h - u, Pal.SUN)
                    c.textCentered(label, fx + Px.dp(8f), h - Px.dp(10f), num.apply { color = Pal.BROWN_D })
                }
                num.color = Pal.CREAM
                // chop markers in the colour of the tapped pad's row
                val rowCol = intArrayOf(0xFFE8746C.toInt(), 0xFFE8B83C.toInt(), 0xFF68B860.toInt(), 0xFF6A8CE0.toInt())
                for ((f, pad) in Studio.chopMarksView) {
                    val x = f.toFloat() / src.size * w
                    c.fill(x, u, x + u, h - u, rowCol[pad / 4])
                    c.fill(x, u, x + Px.dp(18f), u + Px.dp(14f), rowCol[pad / 4])
                    c.drawText("${pad + 1}", x + u * 2, u + Px.dp(11f), num)
                }
                val t = Audio.transport
                val pos = pu.chopPos
                if (pos >= 0) { val x = pos.toFloat() / src.size * w; c.fill(x - u, 0f, x + u, h, Pal.CREAM) }
                if (t.chopRec == app.foxbox.seq.Transport.Rec.ARMED) {
                    val beatsLeft = kotlin.math.ceil((t.chopStart - t.tick) / app.foxbox.seq.Ticks.PPQ).toInt().coerceAtLeast(1)
                    c.fill(0f, mid - Px.dp(16f), w, mid + Px.dp(16f), Pal.alpha(0xFF000000.toInt(), 0.55f))
                    c.textCentered("count-in… $beatsLeft", w / 2, mid, big)
                }
            }
            private var dragging = -1   // 0 start handle, 1 end handle
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                val src = pu.chopSrc
                if (src.isEmpty() || Audio.transport.chopRec != app.foxbox.seq.Transport.Rec.OFF) return true
                val f = (e.x / width * src.size).toInt()
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> dragging = if (kotlin.math.abs(f - pu.chopTrimStart) <= kotlin.math.abs(f - pu.chopTrimEnd)) 0 else 1
                    MotionEvent.ACTION_MOVE -> if (dragging == 0) Studio.setChopTrim(f, pu.chopTrimEnd) else if (dragging == 1) Studio.setChopTrim(pu.chopTrimStart, f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = -1; Studio.changed() }
                }
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate(); return true
            }
        }
        init {
            orientation = VERTICAL
            val title = object : PixelView(c) {
                override fun paint(c: Canvas) { c.fill(0f, 0f, width.toFloat(), height.toFloat(), Snes.FACE_D); c.textCentered(if (pu.chopSrc.isEmpty()) "Rhythm chop: load a sample" else "${pu.chopSrcName} · %.2fs (drag S and E to trim)".format((pu.chopTrimEnd - pu.chopTrimStart) / app.foxbox.audio.SRF), width / 2f, height / 2f, nameP) }
            }
            addView(title, lp(MATCH, Px.dp(28)))
            addView(wave, lp(MATCH, 0, 1f))
            val load = Btn(c, "Load sample…", Snes.GREEN).apply { onClick = { host.pickAudio("chopsrc") } }
            useBtn.onClick = { val i = Studio.selectedPad; Studio.setChopSource(pu.samples[i], pu.names[i]) }
            addView(row(c, load, useBtn).apply { load.layoutParams = lp(0, Px.dp(40), 1f); useBtn.layoutParams = lp(0, Px.dp(40), 1f) }, lp(MATCH, WRAP))
            chopBtn.onClick = { Studio.rhythmChop() }
            val undo = Btn(c, "Undo").apply { onClick = { Studio.undoChop() } }
            addView(row(c, chopBtn, snap, undo).apply { chopBtn.layoutParams = lp(0, Px.dp(44), 1.3f); snap.layoutParams = lp(0, Px.dp(44), 1f); undo.layoutParams = lp(Px.dp(64), Px.dp(44)) }, lp(MATCH, WRAP))
            onStudio { refresh() }
        }
        fun refresh() {
            val t = Audio.transport
            chopBtn.text = when (t.chopRec) { app.foxbox.seq.Transport.Rec.ARMED -> "count-in…"; app.foxbox.seq.Transport.Rec.RECORDING -> "■ Done"; else -> "● Chop" }
            chopBtn.on = t.chopRec != app.foxbox.seq.Transport.Rec.OFF; chopBtn.blink = t.chopRec == app.foxbox.seq.Transport.Rec.ARMED
            useBtn.text = "Use pad ${Studio.selectedPad + 1}"
            listOf(chopBtn, useBtn).forEach { it.requestLayout(); it.invalidate() }
            snap.invalidate()
            for (i in 0 until childCount) getChildAt(i).invalidate()
        }
    }

}
