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
            col.addView(row(c, seqBtn, viewBtn).apply { seqBtn.layoutParams = lp(0, Px.dp(42), 1f); viewBtn.layoutParams = lp(0, Px.dp(42), 1f) }, lp(MATCH, WRAP))
        }
        addView(col, lp(Px.dp(200), MATCH).apply { leftMargin = Px.dp(6) })
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
 * Pickup: truck-dashboard XY pad | 16 pads (MPC style, pad 1 bottom left) |
 * the selected pad's editor (load, record, tune, decay…) or its pattern.
 */
@SuppressLint("ViewConstructor")
class PickupPage(c: Context, private val host: Host) : LinearLayout(c) {
    private val pu = Audio.pickup
    private val pads = PadGrid(c)
    private val right = FrameLayout(c)
    private val editor = LinearLayout(c).apply { orientation = VERTICAL }
    private val pattern = PatternPanel(c)
    private val modeBtn = Btn(c, "Pattern", Pal.ROSE)
    private val nameP = Px.text(13f, Pal.BROWN_D)
    private var builtFor = -1
    private var recBtn: Btn? = null

    init {
        orientation = HORIZONTAL
        setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(4))
        addView(SquareBox(c, XyPad(c, "pickup") { xy, isX -> host.chooseXyTarget(xy, isX) }), lp(WRAP, MATCH))
        addView(SquareBox(c, pads), lp(WRAP, MATCH).apply { leftMargin = Px.dp(6) })
        val col = LinearLayout(c).apply { orientation = VERTICAL }
        modeBtn.onClick = { val showPattern = pattern.visibility != VISIBLE; pattern.visibility = if (showPattern) VISIBLE else GONE; editor.visibility = if (showPattern) GONE else VISIBLE; refresh() }
        col.addView(modeBtn, lp(MATCH, Px.dp(42)))
        right.addView(ScrollView(c).apply { addView(editor) }, FrameLayout.LayoutParams(MATCH, MATCH))
        right.addView(pattern, FrameLayout.LayoutParams(MATCH, MATCH))
        pattern.visibility = GONE
        col.addView(right, lp(MATCH, 0, 1f))
        addView(col, lp(0, MATCH, 1f).apply { leftMargin = Px.dp(6) })
        onStudio { refresh() }
    }

    private fun refresh() {
        val i = Studio.selectedPad
        if (i != builtFor) buildEditor(i)
        modeBtn.text = if (pattern.visibility == VISIBLE) "Pad ${i + 1}: sound" else "Pad ${i + 1}: pattern"
        modeBtn.requestLayout(); modeBtn.invalidate()
        recBtn?.let { b -> val on = host.micTarget() == "pad$i"; b.on = on; b.blink = on; b.text = if (on) "■ Stop" else "● Rec"; b.requestLayout() }
        pads.invalidate(); pattern.invalidate()
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
        val flow = Flow(c)
        listOf(pad.tune, pad.level, pad.decay, pad.start, pad.pan, pad.reverse).forEach { flow.addView(Knob(c, it, Pal.ROSE)) }
        editor.addView(flow, lp(MATCH, WRAP))
    }

    /** 4×4 pads; pad 1 is bottom left. Velocity follows how high on the pad you hit. */
    private inner class PadGrid(c: Context) : PixelView(c) {
        private val flash = LongArray(Pickup.PADS)
        private val down = HashMap<Int, Int>()
        private val numP = Px.text(10f, Pal.BROWN, headFont = true, align = Paint.Align.LEFT)
        private val nmP = Px.text(11f, Pal.BROWN_D)
        init { animated = true }
        private fun padAt(x: Float, y: Float): Int { val col = (x / width * 4).toInt().coerceIn(0, 3); val r = (y / height * 4).toInt().coerceIn(0, 3); return (3 - r) * 4 + col }
        override fun paint(c: Canvas) {
            val cw = width / 4f; val ch = height / 4f
            c.fill(0f, 0f, width.toFloat(), height.toFloat(), 0xFF3A3532.toInt())
            val now = System.currentTimeMillis()
            val step = pu.currentStep
            for (i in 0 until Pickup.PADS) {
                if (Audio.transport.playing && step >= 0 && pu.seqOn.on && pu.pattern[i][step] > 0f && flash[i] < now - 90) flash[i] = now
                val col = i % 4; val r = 3 - i / 4
                val l = col * cw; val t = r * ch
                val lit = now - flash[i] < 120 || i in down.values
                val sel = i == Studio.selectedPad
                c.pixelBox(l + u * 2, t + u * 2, l + cw - u * 2, t + ch - u * 2, if (lit) Pal.ORANGE_L else if (sel) 0xFFE2D6C0.toInt() else 0xFFC9C0B2.toInt(), border = if (sel) Pal.ORANGE_D else Pal.BROWN_D)
                c.drawText("${i + 1}", l + u * 4, t + u * 4 + Px.dp(10f), numP)
                c.textCentered(pu.names[i].take(9), l + cw / 2, t + ch * 0.62f, nmP)
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
                    Studio.hitPad(p, vel); flash[p] = System.currentTimeMillis()
                    if (p != Studio.selectedPad) Studio.selectPad(p)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> down.remove(e.getPointerId(i))
                MotionEvent.ACTION_CANCEL -> down.clear()
            }
            return true
        }
    }

    /** The selected pad's 16 steps (tap: off → hit → accent → off), plus the pad sequencer's switches. */
    private inner class PatternPanel(c: Context) : LinearLayout(c) {
        private val seqBtn = Btn(c, "Seq", Pal.ROSE)
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
            seqBtn.onClick = { pu.seqOn.set(if (pu.seqOn.on) 0f else 1f); Studio.save(); update() }
            val len = Stepper(c, "Steps", 84, { pu.length.value.toInt().toString() }) { pu.length.set(pu.length.value + it); Studio.save() }
            val clear = Btn(c, "Clear pad").apply { onClick = { pu.pattern[Studio.selectedPad].fill(0f); Studio.save(); steps.invalidate() } }
            addView(row(c, seqBtn, len, clear), lp(MATCH, Px.dp(44)))
            addView(steps, lp(MATCH, 0, 1f))
            addView(row(c, Knob(c, pu.swing, Pal.ROSE)), lp(MATCH, WRAP))
            update()
        }
        private fun update() { seqBtn.on = pu.seqOn.on; seqBtn.text = if (pu.seqOn.on) "Seq ON" else "Seq OFF"; seqBtn.requestLayout(); seqBtn.invalidate() }
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
        val sig = Studio.project.flowers.joinToString { it.id + it.name + it.inValley } + Studio.flower?.id
        if (sig != listSig) {
            listSig = sig
            list.removeAllViews()
            list.addView(Btn(context, "+ New flower", Pal.ORANGE).apply { onClick = { Studio.newFlower() } }, lp(MATCH, Px.dp(42)))
            Studio.project.flowers.forEach { f ->
                list.addView(Btn(context, f.name + if (f.inValley) " ✿" else "", Pal.accent("flowers")).apply {
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
