package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.engines.Pickup
import app.foxbox.fx.Flower
import app.foxbox.seq.NoteSeq
import app.foxbox.seq.Theory

/** Keeps a child square, at most 42% of the screen width. */
class SquareBox(c: Context, child: View) : FrameLayout(c) {
    init { addView(child, LayoutParams(MATCH, MATCH)) }
    override fun onMeasure(w: Int, h: Int) {
        val size = minOf(MeasureSpec.getSize(h), (resources.displayMetrics.widthPixels * 0.42f).toInt())
        super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
    }
}

/**
 * Melodic blocks: XY pad | note selector (chords) with the step-sequencer
 * switches at its foot | strum pad or step sequencer.
 */
@SuppressLint("ViewConstructor")
class InstrumentPage(c: Context, id: String, host: Host) : LinearLayout(c) {
    val chords = ChordPanel(c)
    private val engine = Audio.engine(id)
    private val seq: NoteSeq? = engine.seq
    private val strum: StrumPad
    private val steps: SeqPanel?
    private val seqBtn = Btn(c, "Seq", Pal.accent(id))
    private val viewBtn = Btn(c, "Steps", Pal.AQUA)
    private val latchBtn = Btn(c, "Latch", Pal.SUN)

    init {
        orientation = HORIZONTAL
        setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(4))
        val accent = Pal.accent(id)
        chords.chords.accent = accent
        addView(SquareBox(c, XyPad(c, id) { xy, isX -> host.chooseXyTarget(xy, isX) }), lp(WRAP, MATCH))
        val col = LinearLayout(c).apply { orientation = VERTICAL }
        col.addView(chords, lp(MATCH, 0, 1f))
        if (seq != null) {
            seqBtn.onClick = { seq.enabled.set(if (seq.enabled.on) 0f else 1f); if (seq.enabled.on) showSteps(true); Studio.save(); refresh() }
            viewBtn.onClick = { showSteps(steps?.visibility != VISIBLE) }
            latchBtn.onClick = { seq.latch.set(if (seq.latch.on) 0f else 1f); Studio.save(); refresh() }
            chords.footer(seqBtn, latchBtn, viewBtn)
        }
        addView(col, lp(Px.dp(264), MATCH).apply { leftMargin = Px.dp(6) })
        val right = FrameLayout(c)
        strum = StrumPad(c, chords.chords).apply { this.accent = accent }
        right.addView(strum, FrameLayout.LayoutParams(MATCH, MATCH))
        steps = seq?.let { SeqPanel(c, it, accent).also { p -> right.addView(p, FrameLayout.LayoutParams(MATCH, MATCH)) } }
        addView(right, lp(0, MATCH, 1f).apply { leftMargin = Px.dp(6) })
        showSteps(seq?.enabled?.on == true)
        onStudio { refresh() }
    }

    private fun showSteps(on: Boolean) {
        val s = steps ?: return
        s.visibility = if (on) VISIBLE else GONE
        strum.visibility = if (on) GONE else VISIBLE
        refresh()
    }

    private fun refresh() {
        val s = seq ?: return
        seqBtn.on = s.enabled.on; seqBtn.text = if (s.enabled.on) "Seq ON" else "Seq OFF"
        latchBtn.on = s.latch.on; latchBtn.invalidate()
        viewBtn.text = if (steps?.visibility == VISIBLE) "Strum" else "Steps"
        seqBtn.requestLayout(); viewBtn.requestLayout(); seqBtn.invalidate(); viewBtn.invalidate()
    }
}

/** A melodic block's 16-step sequencer: scale-degree rows (relative to the tapped chord), accents and slides. */
@SuppressLint("ViewConstructor")
class SeqPanel(c: Context, private val seq: NoteSeq, accent: Int) : LinearLayout(c) {
    private val grid = Grid(c, accent)
    init {
        orientation = VERTICAL
        val steps = Stepper(c, "Steps", 90, { seq.length.value.toInt().toString() }) { seq.length.set(seq.length.value + it); Studio.save() }
        val rate = Stepper(c, "Rate", 90, { seq.rate.text() }) { seq.rate.set(Math.floorMod(seq.rate.index + it, 3).toFloat()); Studio.save() }
        val oct = Stepper(c, "Octave", 90, { seq.octave.value.toInt().toString() }) { seq.octave.set(seq.octave.value + it); Studio.save() }
        val gate = Stepper(c, "Gate", 90, { seq.gate.text() }) { seq.gate.set(seq.gate.value + it * 0.05f); Studio.save() }
        val clear = Btn(c, "Clear").apply { onClick = { seq.clear(); Studio.save(); grid.invalidate() } }
        addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row(c, steps, rate, oct, gate, clear)) }, lp(MATCH, Px.dp(44)))
        addView(grid, lp(MATCH, 0, 1f))
    }

    /** Cells: 8 scale-degree rows, then accent and slide rows. */
    private inner class Grid(c: Context, private val accent: Int) : PixelView(c) {
        private val lab = Px.text(11f, Pal.BROWN_D, align = Paint.Align.LEFT)
        private val labW get() = Px.dp(44f)
        init { animated = true }
        override fun paint(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.pixelBox(0f, 0f, w, h, Pal.CREAM, shade = false)
            val rows = 10
            val cw = (w - labW - u * 2) / 16; val rh = (h - u * 4) / rows
            val len = seq.length.value.toInt()
            for (r in 0 until rows) {
                val ty = u * 2 + r * rh
                val name = when { r < 8 -> Theory.noteName(seq.noteFor(7 - r)); r == 8 -> "Acc"; else -> "Slide" }
                c.drawText(name, Px.dp(6f), ty + rh / 2 + Px.dp(4f), lab)
                for (s in 0 until 16) {
                    val st = seq.steps[s]
                    val x0 = labW + s * cw
                    val lit = when { r < 8 -> st.on && st.degree == 7 - r; r == 8 -> st.accent; else -> st.slide }
                    val cur = Audio.transport.playing && seq.playStep == s
                    val col = when {
                        lit && cur -> Pal.ORANGE
                        lit -> if (r < 8) accent else if (r == 8) Pal.ORANGE_D else Pal.AQUA
                        s >= len -> Pal.alpha(Pal.PAPER, 0.4f)
                        cur -> Pal.SUN
                        s % 4 == 0 -> Pal.PAPER
                        else -> Pal.alpha(Pal.PAPER, 0.7f)
                    }
                    c.fill(x0 + u, ty + u, x0 + cw - u, ty + rh - u, col)
                }
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_DOWN || e.x < labW) return true
            val cw = (width - labW - u * 2) / 16; val rh = (height - u * 4) / 10f
            val s = ((e.x - labW) / cw).toInt().coerceIn(0, 15)
            val r = ((e.y - u * 2) / rh).toInt().coerceIn(0, 9)
            val st = seq.steps[s]
            when {
                r < 8 -> { val d = 7 - r; if (st.on && st.degree == d) st.on = false else { st.on = true; st.degree = d } }
                r == 8 -> st.accent = !st.accent
                else -> st.slide = !st.slide
            }
            Studio.save(); invalidate()
            return true
        }
    }
}

/**
 * Pickup: truck-dashboard XY pad | 16 pads (classic 4x4 sampler layout, pad 1 bottom left) |
 * the selected pad's editor (load, record, tune, decay…) or its pattern.
 */
@SuppressLint("ViewConstructor")
class PickupPage(c: Context, private val host: Host) : LinearLayout(c) {
    private val pu = Audio.pickup
    private val pads = PadGrid(c)
    private val right = FrameLayout(c)
    private val editor = LinearLayout(c).apply { orientation = VERTICAL }
    private val pattern = PatternPanel(c)
    private val soundTab = Btn(c, "Sound", Pal.ROSE)
    private val patternTab = Btn(c, "Pattern", Pal.ROSE)
    private val chopTab = Btn(c, "Chop", Pal.ROSE)
    private val chopPanel = RhythmChopPanel(c)
    /** 0 the selected pad's sound, 1 its pattern, 2 the rhythm chop screen. */
    private var mode = 0
    private lateinit var editorScroll: ScrollView
    private val qualityBtn = Btn(c, "Clean", Pal.AQUA)
    private val seqBtn = Btn(c, "Sequencer", Pal.ORANGE, 120)
    private val latchBtn = Btn(c, "Latch", Pal.SUN)
    private val nameP = Px.text(13f, Pal.BROWN_D)
    private var builtFor = -1
    private var recBtn: Btn? = null

    init {
        orientation = HORIZONTAL
        setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(4))
        addView(SquareBox(c, XyPad(c, "pickup") { xy, isX -> host.chooseXyTarget(xy, isX) }), lp(WRAP, MATCH))
        addView(SquareBox(c, pads), lp(WRAP, MATCH).apply { leftMargin = Px.dp(6) })
        val col = LinearLayout(c).apply { orientation = VERTICAL }
        soundTab.onClick = { showMode(0) }; patternTab.onClick = { showMode(1) }; chopTab.onClick = { showMode(2) }
        // Like Minds: one switch that stops or starts the whole pad sequencer.
        seqBtn.onClick = { pu.seqOn.set(if (pu.seqOn.on) 0f else 1f); if (!pu.seqOn.on) Audio.post { pu.allOff() }; Studio.save(); Studio.changed() }
        latchBtn.onClick = { pu.latch.set(if (pu.latch.on) 0f else 1f); Studio.save(); Studio.changed() }
        col.addView(row(c, seqBtn, latchBtn).apply { seqBtn.layoutParams = lp(0, Px.dp(44), 1f); latchBtn.layoutParams = lp(Px.dp(80), Px.dp(44)) }, lp(MATCH, WRAP))
        qualityBtn.onClick = { pu.quality.set(if (pu.quality.index == 0) 1f else 0f); Studio.save(); Studio.changed() }
        col.addView(row(c, soundTab, patternTab, chopTab, qualityBtn).apply {
            soundTab.layoutParams = lp(0, Px.dp(42), 1f); patternTab.layoutParams = lp(0, Px.dp(42), 1.15f); chopTab.layoutParams = lp(0, Px.dp(42), 0.9f); qualityBtn.layoutParams = lp(0, Px.dp(42), 1.1f)
        }, lp(MATCH, WRAP))
        editorScroll = ScrollView(c).apply { addView(editor) }
        right.addView(editorScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        right.addView(pattern, FrameLayout.LayoutParams(MATCH, MATCH))
        right.addView(chopPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        pattern.visibility = GONE; chopPanel.visibility = GONE
        col.addView(right, lp(MATCH, 0, 1f))
        addView(col, lp(0, MATCH, 1f).apply { leftMargin = Px.dp(6) })
        onStudio { refresh() }
    }

    private fun refresh() {
        val i = Studio.selectedPad
        if (i != builtFor) buildEditor(i)
        soundTab.on = mode == 0; patternTab.on = mode == 1; chopTab.on = mode == 2
        listOf(soundTab, patternTab, chopTab).forEach { it.invalidate() }
        chopPanel.refresh()
        seqBtn.on = pu.seqOn.on; seqBtn.text = if (pu.seqOn.on) "Sequencer ON" else "Sequencer OFF"; seqBtn.requestLayout(); seqBtn.invalidate()
        latchBtn.on = pu.latch.on; latchBtn.invalidate()
        val vintage = pu.quality.index == 1
        qualityBtn.on = vintage; qualityBtn.text = if (vintage) "Vintage" else "Clean"; qualityBtn.requestLayout(); qualityBtn.invalidate()
        recBtn?.let { b -> val on = host.micTarget() == "pad$i"; b.on = on; b.blink = on; b.text = if (on) "■ Stop" else "● Rec"; b.requestLayout() }
        pads.invalidate(); pattern.invalidate()
    }

    private fun showMode(m: Int) {
        mode = m
        editorScroll.visibility = if (m == 0) VISIBLE else GONE
        pattern.visibility = if (m == 1) VISIBLE else GONE
        chopPanel.visibility = if (m == 2) VISIBLE else GONE
        refresh()
    }

    /** True while a rhythm chop pass is counting in or playing: pad taps place chops instead of playing. */
    private val chopping get() = mode == 2 && Audio.transport.chopRec != app.foxbox.seq.Transport.Rec.OFF

    /**
     * The rhythm chop screen: load a sample, press Chop, and after two bars of
     * count-in the sample plays in time. Tap a pad wherever a new chop should
     * start; that slice (up to the next tap) lands on the pad you tapped.
     */
    private inner class RhythmChopPanel(c: Context) : LinearLayout(c) {
        private val chopBtn = Btn(c, "● Chop", Pal.ORANGE_D)
        private val useBtn = Btn(c, "Use pad", Pal.ROSE)
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
                override fun paint(c: Canvas) { c.pixelBox(0f, 0f, width.toFloat(), height.toFloat(), Pal.CREAM, shade = false); c.textCentered(if (pu.chopSrc.isEmpty()) "Rhythm chop" else "${pu.chopSrcName} · %.2fs (drag S and E to trim)".format((pu.chopTrimEnd - pu.chopTrimStart) / app.foxbox.audio.SRF), width / 2f, height / 2f, nameP) }
            }
            addView(title, lp(MATCH, Px.dp(28)))
            addView(wave, lp(MATCH, 0, 1f))
            val load = Btn(c, "Load sample…", Pal.AQUA).apply { onClick = { host.pickAudio("chopsrc") } }
            useBtn.onClick = { val i = Studio.selectedPad; Studio.setChopSource(pu.samples[i], pu.names[i]) }
            addView(row(c, load, useBtn).apply { load.layoutParams = lp(0, Px.dp(40), 1f); useBtn.layoutParams = lp(0, Px.dp(40), 1f) }, lp(MATCH, WRAP))
            chopBtn.onClick = { Studio.rhythmChop() }
            val undo = Btn(c, "Undo").apply { onClick = { Studio.undoChop() } }
            addView(row(c, chopBtn, snap, undo).apply { chopBtn.layoutParams = lp(0, Px.dp(44), 1.3f); snap.layoutParams = lp(0, Px.dp(44), 1f); undo.layoutParams = lp(Px.dp(64), Px.dp(44)) }, lp(MATCH, WRAP))
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

    private fun buildEditor(i: Int) {
        builtFor = i
        editor.removeAllViews()
        val c = context
        val pad = pu.pads[i]
        val title = object : PixelView(c) {
            override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(Px.dp(200), w), Px.dp(30))
            override fun paint(c: Canvas) { c.pixelBox(0f, 0f, width.toFloat(), height.toFloat(), Pal.CREAM, shade = false); c.textCentered("Pad ${i + 1} · ${pu.names[i]}", width / 2f, height / 2f, nameP) }
        }
        editor.addView(title, lp(MATCH, Px.dp(30)))
        val load = Btn(c, "Load to pad", Pal.AQUA).apply { onClick = { host.pickAudio("pad$i") } }
        recBtn = Btn(c, "● Rec", Pal.ORANGE_D).apply { onClick = { host.toggleMic("pad$i") } }
        editor.addView(row(c, load, recBtn!!).apply { load.layoutParams = lp(0, Px.dp(42), 1f); recBtn!!.layoutParams = lp(0, Px.dp(42), 1f) }, lp(MATCH, WRAP))
        val chop = Btn(c, "Chop…", Pal.ORANGE).apply { onClick = { host.chopMenu(i) } }
        val banks = Btn(c, "Banks…", Pal.HONEY).apply { onClick = { host.bankMenu() } }
        editor.addView(row(c, chop, banks).apply { chop.layoutParams = lp(0, Px.dp(42), 1f); banks.layoutParams = lp(0, Px.dp(42), 1f) }, lp(MATCH, WRAP))
        val flow = Flow(c)
        listOf(pad.tune, pad.level, pad.decay, pad.start, pu.padEnd[i], pad.pan, pad.reverse, pu.padHold[i]).forEach { flow.addView(Knob(c, it, Pal.ROSE)) }
        editor.addView(flow, lp(MATCH, WRAP))
    }

    /** 4×4 pads; pad 1 is bottom left. Velocity follows how high on the pad you hit. */
    private inner class PadGrid(c: Context) : PixelView(c) {
        private val flash = LongArray(Pickup.PADS)
        private val down = HashMap<Int, Int>()
        private val numP = Px.text(10f, Pal.BROWN, headFont = true, align = Paint.Align.LEFT)
        private val PLATE = 0xFFC8C4CC.toInt(); private val PLATE_D = 0xFF8C8794.toInt(); private val WELL = 0xFF5A5664.toInt()
        private val PAD = 0xFFDCD8E0.toInt(); private val PAD_SEL = 0xFFEDE9F2.toInt(); private val SHADOW = 0xFF34303C.toInt()
        private val OUTLINE = 0xFF26222E.toInt(); private val HILITE = 0xFFFFFFFF.toInt(); private val EDGE_D = 0xFFA7A2B0.toInt()
        private val ROW_GEM = intArrayOf(0xFFE04848.toInt(), 0xFFF2C230.toInt(), 0xFF4CB050.toInt(), 0xFF4870D8.toInt())
        private val ROW_LIT = intArrayOf(0xFFE8746C.toInt(), 0xFFE8B83C.toInt(), 0xFF68B860.toInt(), 0xFF6A8CE0.toInt())
        private val nmP = Px.text(11f, Pal.BROWN_D)
        init { animated = true }
        private fun padAt(x: Float, y: Float): Int { val col = (x / width * 4).toInt().coerceIn(0, 3); val r = (y / height * 4).toInt().coerceIn(0, 3); return (3 - r) * 4 + col }
        override fun paint(c: Canvas) {
            val cw = width / 4f; val ch = height / 4f
            val w = width.toFloat(); val h = height.toFloat()
            // A 16-bit console faceplate: two-tone grey plate with a recessed well.
            c.fill(0f, 0f, w, h, PLATE_D)
            c.fill(u, u, w - u, h - u, PLATE)
            c.fill(u * 2, u * 2, w - u * 2, h - u * 2, WELL)
            val now = System.currentTimeMillis()
            val step = pu.currentStep
            for (i in 0 until Pickup.PADS) {
                if (Audio.transport.playing && step >= 0 && pu.seqOn.on && pu.pattern[i][step] > 0f && flash[i] < now - 90) flash[i] = now
                val col = i % 4; val r = 3 - i / 4
                val l = col * cw + u * 3; val t = r * ch + u * 3; val rr = (col + 1) * cw - u * 3; val b = (r + 1) * ch - u * 3
                val lit = now - flash[i] < 120 || i in down.values
                val sel = i == Studio.selectedPad
                val face = if (lit) ROW_LIT[i / 4] else if (sel) PAD_SEL else PAD
                // drop shadow, then a chunky rounded key: light top-left edge, dark bottom-right edge
                val press = if (lit) u else 0f
                c.fill(l + u * 2, t + u * 2, rr + u, b + u, SHADOW)
                c.fill(l + u + press, t + press, rr - u + press, b + press, OUTLINE); c.fill(l + press, t + u + press, rr + press, b - u + press, OUTLINE)
                c.fill(l + u * 2 + press, t + u + press, rr - u * 2 + press, b - u + press, face); c.fill(l + u + press, t + u * 2 + press, rr - u + press, b - u * 2 + press, face)
                c.fill(l + u * 2 + press, t + u + press, rr - u * 2 + press, t + u * 2 + press, HILITE)
                c.fill(l + u + press, t + u * 2 + press, l + u * 2 + press, b - u * 3 + press, HILITE)
                c.fill(l + u * 3 + press, b - u * 2 + press, rr - u * 2 + press, b - u + press, EDGE_D)
                c.fill(rr - u * 2 + press, t + u * 3 + press, rr - u + press, b - u * 2 + press, EDGE_D)
                // coloured row gem in the corner, like the face buttons on a pad controller
                val gx = rr - u * 6 + press; val gy = t + u * 4 + press
                c.fill(gx - u, gy - u, gx + u * 3, gy + u * 3, OUTLINE); c.fill(gx, gy, gx + u * 2, gy + u * 2, ROW_GEM[i / 4]); c.fill(gx, gy, gx + u, gy + u, HILITE)
                if (sel) { c.fill(l + press, b + press - u * 0.5f, rr + press, b + press + u * 0.5f, Pal.ORANGE) }
                numP.color = if (lit) Pal.CREAM else 0xFF4A4458.toInt()
                c.drawText("${i + 1}", l + u * 4 + press, t + u * 4 + Px.dp(9f) + press, numP)
                nmP.color = if (lit) Pal.CREAM else 0xFF2E2A38.toInt()
                nmP.textSize = Px.dp(11f)
                c.textFit(pu.names[i], (l + rr) / 2 + press, t + (b - t) * 0.64f + press, rr - l - u * 6, nmP, 8f)
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val i = e.actionIndex
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val x = e.getX(i); val y = e.getY(i)
                    val p = padAt(x, y); down[e.getPointerId(i)] = p
                    val ch = height / 4f
                    val vel = 0.45f + 0.55f * (1f - (y % ch) / ch)
                    if (chopping) { Studio.chopTap(p); flash[p] = System.currentTimeMillis(); return true }
                    Studio.hitPad(p, vel); flash[p] = System.currentTimeMillis()
                    if (p != Studio.selectedPad) Studio.selectPad(p)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> down.remove(e.getPointerId(i))?.let { Studio.releasePad(it) }
                MotionEvent.ACTION_CANCEL -> { down.values.forEach { Studio.releasePad(it) }; down.clear() }
            }
            return true
        }
    }

    /** The selected pad's 16 steps (tap: off → hit → accent → off), plus the pad sequencer's switches. */
    private inner class PatternPanel(c: Context) : LinearLayout(c) {
        private val steps = object : PixelView(c) {
            init { animated = true }
            override fun paint(c: Canvas) {
                val pad = Studio.selectedPad
                val len = pu.length.value.toInt()
                val cw = width / 8f; val ch = height / 2f
                for (s in 0 until 16) {
                    val l = (s % 8) * cw; val t = (s / 8) * ch
                    val v = pu.pattern[pad][s]
                    val cur = Audio.transport.playing && pu.currentStep == s
                    val col = when { s >= len -> Pal.alpha(Pal.PAPER, 0.35f); v >= 0.95f -> Pal.ORANGE_D; v > 0f -> Pal.ROSE; cur -> Pal.SUN; s % 4 == 0 -> Pal.PAPER; else -> Pal.CREAM }
                    c.pixelBox(l + u, t + u, l + cw - u, t + ch - u, col, border = if (cur) Pal.ORANGE else Pal.BROWN_D)
                }
            }
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
                val s = ((e.y / height * 2).toInt().coerceIn(0, 1)) * 8 + (e.x / width * 8).toInt().coerceIn(0, 7)
                val row = pu.pattern[Studio.selectedPad]
                row[s] = when { row[s] <= 0f -> 0.8f; row[s] < 0.95f -> 1f; else -> 0f }
                Studio.save(); invalidate(); return true
            }
        }
        init {
            orientation = VERTICAL
            val len = Stepper(c, "Steps", 84, { pu.length.value.toInt().toString() }) { pu.length.set(pu.length.value + it); Studio.save() }
            val clear = Btn(c, "Clear pad").apply { onClick = { pu.pattern[Studio.selectedPad].fill(0f); Studio.save(); steps.invalidate() } }
            addView(row(c, len, clear), lp(MATCH, Px.dp(44)))
            addView(steps, lp(MATCH, 0, 1f))
            addView(row(c, Knob(c, pu.swing, Pal.ROSE)), lp(MATCH, WRAP))
        }
    }
}

/** Flowers page: XY garden | your flowers | the selected flower's effects and matrix. */
@SuppressLint("ViewConstructor")
class FlowersPage(c: Context, private val host: Host) : LinearLayout(c) {
    private val list = LinearLayout(c).apply { orientation = VERTICAL }
    private val editor = FrameLayout(c)
    private var builtFor: Flower? = null
    private var listSig = ""

    init {
        orientation = HORIZONTAL
        setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(4))
        addView(SquareBox(c, XyPad(c, Studio.FLOWERS) { xy, isX -> host.chooseXyTarget(xy, isX) }), lp(WRAP, MATCH))
        addView(ScrollView(c).apply { addView(list) }, lp(Px.dp(170), MATCH).apply { leftMargin = Px.dp(6) })
        addView(editor, lp(0, MATCH, 1f).apply { leftMargin = Px.dp(6) })
        onStudio { refresh() }
    }

    private fun refresh() {
        val sig = Studio.project.flowers.joinToString { it.id + it.name + it.inClibbins } + Studio.flower?.id
        if (sig != listSig) {
            listSig = sig
            list.removeAllViews()
            list.addView(Btn(context, "+ New flower", Pal.ORANGE).apply { onClick = { Studio.newFlower() } }, lp(MATCH, Px.dp(42)))
            Studio.project.flowers.forEach { f ->
                list.addView(Btn(context, f.name + if (f.inClibbins) " ✿" else "", Pal.accent("flowers")).apply {
                    on = f === Studio.flower; icon = Iso.iconFlower
                    onClick = { Studio.selectFlower(f) }
                    onLong = { host.flowerMenu(f) }
                }, lp(MATCH, Px.dp(42)))
            }
        }
        val f = Studio.flower ?: return
        if (f !== builtFor) buildEditor(f)
    }

    private fun buildEditor(f: Flower) {
        builtFor = f
        editor.removeAllViews()
        val strip = LinearLayout(context).apply { orientation = HORIZONTAL }
        val accent = Pal.accent("flowers")
        f.p.sections.forEach { (name, params) ->
            if (name == "XY Pad") return@forEach
            val panel = Panel(context, name, accent = accent)
            val flow = Flow(context)
            params.forEach { flow.addView(Knob(context, it, accent)) }
            val cols = if (name == "Mod Matrix") 3 else (params.size + 1) / 2
            panel.addView(flow, lp(Px.dp(74) * cols, WRAP))
            if (name == "Gate") panel.addView(GateEditor(context, f), lp(Px.dp(74) * cols, Px.dp(46)))
            strip.addView(panel, lp(WRAP, WRAP).apply { rightMargin = Px.dp(6) })
        }
        editor.addView(HorizontalScrollView(context).apply { addView(ScrollView(context).apply { addView(strip) }) }, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Tap steps to shape the flower's rhythmic gate. */
    private class GateEditor(c: Context, val f: Flower) : PixelView(c) {
        init { animated = true }
        override fun paint(c: Canvas) {
            val sw = width / 16f
            val t = Audio.transport
            val cur = if (t.playing && t.tick >= 0) ((t.tick / app.foxbox.seq.Ticks.STEP).toLong() % 16).toInt() else -1
            for (i in 0 until 16) {
                val col = when { f.gate[i] && i == cur -> Pal.ORANGE; f.gate[i] -> Pal.BROWN; i == cur -> Pal.SUN; else -> Pal.PAPER }
                c.pixelBox(i * sw + u, u * 2, (i + 1) * sw - u, height - u * 2, col, shade = false)
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { val i = (e.x / width * 16).toInt().coerceIn(0, 15); f.gate[i] = !f.gate[i]; Studio.save() }
            return true
        }
    }
}
