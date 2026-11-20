package app.sketchsolve.ui.modes

import android.app.AlertDialog
import android.graphics.*
import app.sketchsolve.MainActivity
import app.sketchsolve.circuit.*
import app.sketchsolve.ink.Pt
import app.sketchsolve.ink.Stroke
import app.sketchsolve.math.*
import app.sketchsolve.recog.Cand
import app.sketchsolve.ui.*
import org.json.JSONObject
import kotlin.math.*

/** Draw a schematic, write values next to the parts, circle what you want. */
class CircuitsMode(app: MainActivity) : DrawingMode(app) {
    override val id = "circuit"
    override val title = "Circuits"
    override val padTitle = "CIRCUIT · ANSWER"
    override val tools = listOf(
        ToolSpec("shape", "✎", "1 Draw", Ink.PENCIL),
        ToolSpec("mark", "12", "2 Values", Ink.KNOWN),
        ToolSpec("find", "◯", "3 Find", Ink.FIND)) + editTools()

    /** Values typed on the pad: variable → value (e.g. "R1" → 220, "I_R2" → 0.5). */
    val typedValues = HashMap<String, Double>()
    var manualTarget: String? = null      // a component name, or "__whole"
    private val cache = HashMap<String, List<Cand>>()

    class Answer(val v: String, val r: SolveResult)
    class Analysis(val s: Schematic?, val reading: Circuit.Reading?, val known: Map<String, Double>, val targetComp: Component?, val whole: Boolean, val answers: List<Answer>)
    var A: Analysis? = null

    init { paper.tool = "shape" }

    override fun kindFor(tool: String) = when (tool) { "mark" -> "mark"; "find" -> "find"; else -> "shape" }
    override fun onStroke(s: Stroke, paper: PaperView) { if (s.kind == "find") manualTarget = null }

    override fun styleFor(s: Stroke) = when (s.kind) {
        "find" -> InkStyle(Ink.FIND, 2.6f)
        "mark" -> InkStyle(Ink.KNOWN, 2.6f)
        else -> InkStyle(Ink.PENCIL, 2.8f)
    }

    override fun refresh() {
        val strokes = snapshot()
        val tv = HashMap(typedValues)
        val mt = manualTarget
        app.analyze(id, { compute(strokes, tv, mt) }) { a -> A = a; render(a) }
    }

    private fun compute(strokes: List<Stroke>, typed: Map<String, Double>, mt: String?): Analysis {
        val draw = strokes.filter { it.kind == "shape" }
        val s = Circuit.recognise(draw) ?: return Analysis(null, null, emptyMap(), null, false, emptyList())
        val reading = synchronized(cache) { Circuit.annotate(s, strokes.filter { it.kind == "mark" }, strokes.filter { it.kind == "find" }, cache) }
        val known = HashMap(reading.values)
        for ((k, v) in typed) known[k] = v
        var comp = reading.target
        var whole = reading.whole
        if (mt == "__whole") { whole = true; comp = null }
        else if (mt != null) { s.components.firstOrNull { it.name == mt }?.let { comp = it; whole = false } }
        val sys = Circuit.equations(s)
        val targets = when {
            whole -> sys.totals
            comp != null -> (if (comp!!.name in known) emptyList() else listOf(comp!!.name)) + Circuit.quantities(comp!!)
            else -> emptyList()
        }
        val answers = targets.map { Answer(it, Circuit.solve(s, sys, known, it)) }
        return Analysis(s, reading, known, comp, whole, answers)
    }

    fun unit(v: String) = Circuit.unit(v, A?.s).let { if (it.isEmpty()) "" else " $it" }
    fun name(v: String) = Circuit.shortLabel(v)

    /* ---------------- paper ---------------- */
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawBelow(c: Canvas, paper: PaperView) {
        val a = A ?: return
        val s = a.s ?: return
        for (comp in s.components) {
            val isT = comp === a.targetComp || a.whole
            p.style = Paint.Style.FILL
            p.color = when { isT -> 0x22C2255C; a.known.containsKey(comp.name) -> 0x2214864F; else -> 0x163F7FD0 }
            val b = comp.body
            c.drawRoundRect(RectF((b.x0 - 6).toFloat(), (b.y0 - 6).toFloat(), (b.x1 + 6).toFloat(), (b.y1 + 6).toFloat()), 8f, 8f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f
            p.color = when { isT -> Ink.FIND; a.known.containsKey(comp.name) -> Ink.KNOWN; else -> Ink.GUIDE }
            p.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
            c.drawRoundRect(RectF((b.x0 - 6).toFloat(), (b.y0 - 6).toFloat(), (b.x1 + 6).toFloat(), (b.y1 + 6).toFloat()), 8f, 8f, p)
            p.pathEffect = null
            if (comp.isSource && comp.b != null) {
                p.style = Paint.Style.FILL; p.color = Ink.FIND; p.textSize = 13f; p.typeface = Typeface.DEFAULT_BOLD
                val bp = comp.b!!; val ap = comp.a ?: comp.center
                val dir = (bp - ap).norm()
                c.drawText("+", (bp.x + dir.x * 4 - dir.y * 10 - 4).toFloat(), (bp.y + dir.y * 4 + dir.x * 10 + 4).toFloat(), p)
            }
        }
        p.style = Paint.Style.FILL; p.color = Ink.PENCIL
        for (j in s.junctions) c.drawCircle(j.x.toFloat(), j.y.toFloat(), 3.2f, p)
    }

    private fun render(a: Analysis) {
        val labels = ArrayList<PaperLabel>()
        val s = a.s
        if (s != null) {
            for (comp in s.components) {
                val v = a.known[comp.name]
                val isT = comp === a.targetComp
                val main = a.answers.firstOrNull()
                val text = when {
                    isT && main != null && main.r.ok -> "${comp.name}: ${name(main.v)} ${sign(main.r.value)} ${Expr.fmt(main.r.value)}${unit(main.v)}"
                    isT -> "${comp.name} = ?"
                    v != null -> "${comp.name} = ${Expr.fmt(v)} ${comp.type.unit}"
                    else -> comp.name
                }
                val horizontalBody = comp.body.w >= comp.body.h
                val at = if (horizontalBody) Pt(comp.body.cx, comp.body.y0 - 18) else Pt(comp.body.x1 + 12 + text.length * 3.3, comp.body.cy)
                labels += PaperLabel("comp:${comp.name}", text, at, when {
                    isT && main != null && main.r.ok -> PaperLabel.ANSWER
                    isT -> PaperLabel.TARGET
                    v != null -> PaperLabel.KNOWN
                    else -> PaperLabel.PLAIN
                })
            }
            if (a.whole) {
                val t = a.answers.firstOrNull()
                val txt = if (t != null && t.r.ok) "${Circuit.label(t.v)} ${sign(t.r.value)} ${Expr.fmt(t.r.value)}${unit(t.v)}" else "Whole circuit = ?"
                labels += PaperLabel("whole", txt, Pt(s.box.cx, max(30.0, s.box.y0 - 26)), if (t != null && t.r.ok) PaperLabel.ANSWER else PaperLabel.TARGET)
            }
        }
        paper.labels = labels
        paper.invalidate()
        renderPad(a)
    }

    private fun sign(v: Double) = if (abs(v - Math.round(v)) < 1e-9 * max(1.0, abs(v))) "=" else "≈"

    override fun onLabelTap(label: PaperLabel) {
        val s = A?.s ?: return
        if (label.id == "whole") return
        val comp = s.components.firstOrNull { "comp:${it.name}" == label.id } ?: return
        val items = arrayOf("Type its value (${comp.type.unit})", "◯ Find its current, voltage and power", "Clear typed value")
        AlertDialog.Builder(app).setTitle("${comp.name} — ${comp.type.title.lowercase()}").setItems(items) { _, i ->
            when (i) {
                0 -> askText("${comp.name} (${comp.type.unit})", "value", A?.known?.get(comp.name)?.let { Expr.fmt(it).replace("−", "-") } ?: "", true) { setTyped(comp.name, it) }
                1 -> { manualTarget = comp.name; app.scheduleSave(); refresh() }
                2 -> setTyped(comp.name, "")
            }
        }.show()
    }

    private fun setTyped(v: String, raw: String) {
        if (raw.isBlank()) typedValues.remove(v)
        else {
            val x = Expr.tryParse(raw)?.let { runCatching { Expr.evaluate(it) }.getOrNull() }
            if (x == null || !x.isFinite()) { app.toast("“$raw” isn't a number"); return }
            typedValues[v] = x
        }
        app.scheduleSave(); refresh()
    }

    private fun renderPad(a: Analysis) {
        if (app.mode !== this) return
        val pad = app.pad
        pad.clear()
        pad.heading("Circuit")
        val s = a.s
        if (s == null || s.components.isEmpty()) {
            pad.text("1  Draw a schematic.  2  Write each value next to its part.  3  Circle a part, or the whole circuit.")
            pad.muted("I understand wires, resistors (zigzag or box), lamps (circle with an X), cells and batteries (long and short plates; the long plate is +), capacitors (two equal plates) and voltage sources (a circle).")
            pad.muted("Units: ohms for resistors and lamps, volts for batteries, µF for capacitors.")
            pad.muted("No battery? Leave two wire ends open and I'll work out the resistance between them.")
            if (s != null) pad.warn("I see wires but no parts yet.")
            return
        }
        pad.answer(s.topology)
        val counts = s.components.groupBy { it.type }.map { (t, l) -> "${l.size} ${t.title.lowercase()}${if (l.size > 1) "s" else ""}" }
        pad.muted(counts.joinToString(", "))
        for (comp in s.components.sortedBy { it.type.ordinal * 100 + (it.name.drop(1).toIntOrNull() ?: 0) }) {
            val v = a.known[comp.name]
            pad.valueRow("${comp.name} (${comp.type.unit})", comp.type.title, v?.let { Expr.fmt(it).replace("−", "-") } ?: "", comp === a.targetComp,
                a.reading?.values?.containsKey(comp.name) == true,
                { setTyped(comp.name, it) },
                { manualTarget = if (manualTarget == comp.name) null else comp.name; app.scheduleSave(); refresh() })
        }
        pad.button(if (a.whole) "◉ Whole circuit (selected)" else "◯ Whole circuit: totals") { manualTarget = if (manualTarget == "__whole") null else "__whole"; app.scheduleSave(); refresh() }
        pad.heading("Answer")
        if (a.targetComp == null && !a.whole) {
            pad.muted("Write the values next to the parts, then circle a part (or the whole circuit) with the Find tool, or tap ◯ in the list.")
        } else if (a.whole && a.answers.isEmpty()) {
            pad.warn("For totals I need exactly one battery, or two open wire ends to measure between.")
        } else {
            val ok = a.answers.filter { it.r.ok }
            if (ok.isNotEmpty()) {
                pad.status("✓ Solved", true)
                for (an in ok) {
                    pad.muted(Circuit.label(an.v))
                    pad.answer("${name(an.v)} ${sign(an.r.value)} ${an.r.values.joinToString(" or ") { Expr.fmt(it) }}${unit(an.v)}")
                }
            }
            val bad = a.answers.filter { !it.r.ok }
            val first = bad.firstOrNull()
            if (first != null && ok.isEmpty()) pad.result(first.r, { Circuit.shortLabel(it) }, { unit(it) }, steps = false,
                noSolutionNote = "If a capacitor or an open switch blocks the loop, no steady current can flow.")
            else if (first != null) pad.muted("Not enough information for: ${bad.joinToString(", ") { Circuit.label(it.v).lowercase() }}.")
            if (ok.isNotEmpty()) pad.muted("Directions: current flows out of the + side of the battery. Sizes are shown without signs.")
        }
        a.reading?.issues?.forEach { pad.warn(it) }
    }

    override fun save(): JSONObject = JSONObject().put("strokes", strokesJson()).put("typed", JSONObject(typedValues as Map<*, *>))
        .put("target", manualTarget ?: "").put("tool", paper.tool)

    override fun load(o: JSONObject) {
        manualTarget = o.optString("target").ifEmpty { null }
        paper.tool = o.optString("tool", "shape").ifEmpty { "shape" }
        o.optJSONObject("typed")?.let { t -> t.keys().forEach { typedValues[it] = t.getDouble(it) } }
        loadStrokes(o)
    }
}
