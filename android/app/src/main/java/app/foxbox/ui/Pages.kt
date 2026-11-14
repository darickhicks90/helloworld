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
import app.foxbox.fx.Flower
import app.foxbox.seq.Theory

/** Keeps a child square, at most 42% of the screen width. */
class SquareBox(c: Context, child: View) : FrameLayout(c) {
    init { addView(child, LayoutParams(MATCH, MATCH)) }
    override fun onMeasure(w: Int, h: Int) {
        val size = minOf(MeasureSpec.getSize(h), (resources.displayMetrics.widthPixels * 0.42f).toInt())
        super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
    }
}

/** XY pad | chord selector | strum pad — the layout for melodic engines. */
@SuppressLint("ViewConstructor")
class InstrumentPage(c: Context, id: String, host: Host, right: ((ChordButtons) -> View)? = null) : LinearLayout(c) {
    val chords = ChordPanel(c)
    init {
        orientation = HORIZONTAL
        setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(4))
        val accent = Pal.accent(id)
        chords.chords.accent = accent
        addView(SquareBox(c, XyPad(c, id) { xy, isX -> host.chooseXyTarget(xy, isX) }), lp(WRAP, MATCH))
        addView(chords, lp(Px.dp(200), MATCH).apply { leftMargin = Px.dp(6) })
        val r = right?.invoke(chords.chords) ?: StrumPad(c, chords.chords).apply { this.accent = accent }
        addView(r, lp(0, MATCH, 1f).apply { leftMargin = Px.dp(6) })
    }
}

/** Bumble's right column: the honeycomb step sequencer, or the strum pad. */
@SuppressLint("ViewConstructor")
class BumbleRight(c: Context, chords: ChordButtons) : LinearLayout(c) {
    private val bee = Audio.bumble
    private val comb = Honeycomb(c)
    private val strum = StrumPad(c, chords).apply { accent = Pal.HONEY }
    private val seqBtn = Btn(c, "Sequencer", Pal.HONEY)
    private val modeBtn = Btn(c, "Keys", Pal.AQUA)
    init {
        orientation = VERTICAL
        seqBtn.onClick = { bee.enabled.set(if (bee.enabled.on) 0f else 1f); Studio.save(); refresh() }
        modeBtn.onClick = { val keys = strum.visibility != VISIBLE; strum.visibility = if (keys) VISIBLE else GONE; comb.visibility = if (keys) GONE else VISIBLE; refresh() }
        val steps = Stepper(c, "Steps", 90, { bee.length.value.toInt().toString() }) { bee.length.set(bee.length.value + it); Studio.save() }
        val rate = Stepper(c, "Rate", 90, { bee.rate.text() }) { bee.rate.set(Math.floorMod(bee.rate.index + it, 3).toFloat()); Studio.save() }
        addView(row(c, seqBtn, steps, rate, modeBtn), lp(MATCH, Px.dp(44)))
        addView(comb, lp(MATCH, 0, 1f)); addView(strum, lp(MATCH, 0, 1f))
        strum.visibility = GONE
        onStudio { refresh() }
    }
    private fun refresh() {
        seqBtn.on = bee.enabled.on; seqBtn.text = if (bee.enabled.on) "Seq ON" else "Seq OFF"
        modeBtn.text = if (strum.visibility == VISIBLE) "Steps" else "Keys"
        seqBtn.requestLayout(); modeBtn.requestLayout(); comb.invalidate()
    }

    /** Hex cells: 8 scale-degree rows, then accent and slide rows. */
    private inner class Honeycomb(c: Context) : PixelView(c) {
        private val lab = Px.text(11f, Pal.BROWN_D, align = Paint.Align.LEFT)
        private val labW get() = Px.dp(44f)
        init { animated = true }
        override fun paint(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.pixelBox(0f, 0f, w, h, Pal.CREAM, shade = false)
            val rows = 10
            val cw = (w - labW - u * 2) / 16; val rh = (h - u * 4) / (rows + 0.5f)
            val len = bee.length.value.toInt()
            for (r in 0 until rows) {
                val ty = u * 2 + r * rh
                val name = when { r < 8 -> Theory.noteName(bee.noteFor(7 - r)); r == 8 -> "Acc"; else -> "Slide" }
                c.drawText(name, Px.dp(6f), ty + rh / 2 + Px.dp(4f), lab)
                for (s in 0 until 16) {
                    val st = bee.steps[s]
                    val x0 = labW + s * cw; val y0 = ty + (if (s % 2 == 1) rh / 2 else 0f)
                    val lit = when { r < 8 -> st.on && st.degree == 7 - r; r == 8 -> st.accent; else -> st.slide }
                    val cur = Audio.transport.playing && bee.playStep == s
                    val col = when {
                        lit && cur -> Pal.ORANGE
                        lit -> if (r < 8) Pal.HONEY else if (r == 8) Pal.ORANGE_D else Pal.AQUA
                        s >= len -> Pal.alpha(Pal.PAPER, 0.4f)
                        cur -> Pal.SUN
                        else -> Pal.PAPER
                    }
                    hex(c, x0 + u, y0 + u, cw - u * 2, rh - u, col)
                }
            }
        }
        private fun hex(c: Canvas, x: Float, y: Float, w: Float, h: Float, col: Int) {
            val cut = minOf(w, h) * 0.25f
            c.fill(x + cut, y, x + w - cut, y + h, col)
            c.fill(x, y + cut, x + w, y + h - cut, col)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_DOWN || e.x < labW) return true
            val cw = (width - labW - u * 2) / 16; val rh = (height - u * 4) / 10.5f
            val s = ((e.x - labW) / cw).toInt().coerceIn(0, 15)
            val r = ((e.y - u * 2 - (if (s % 2 == 1) rh / 2 else 0f)) / rh).toInt().coerceIn(0, 9)
            val st = bee.steps[s]
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
                    on = f === Studio.flower; icon = Sprites.bloom
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
            if (name == "Rhythm") panel.addView(GateEditor(context, f), lp(Px.dp(74) * cols, Px.dp(46)))
            strip.addView(panel, lp(WRAP, WRAP).apply { rightMargin = Px.dp(6) })
        }
        editor.addView(HorizontalScrollView(context).apply { addView(ScrollView(context).apply { addView(strip) }) }, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Tap steps to toggle the flower's rhythmic gate. */
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
