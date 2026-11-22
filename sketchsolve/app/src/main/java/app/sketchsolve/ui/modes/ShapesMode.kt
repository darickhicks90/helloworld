package app.sketchsolve.ui.modes

import android.app.AlertDialog
import android.graphics.*
import app.sketchsolve.MainActivity
import app.sketchsolve.ink.Box
import app.sketchsolve.ink.Geom
import app.sketchsolve.ink.Pt
import app.sketchsolve.ink.Stroke
import app.sketchsolve.math.*
import app.sketchsolve.recog.Cand
import app.sketchsolve.shapes.*
import app.sketchsolve.ui.*
import org.json.JSONObject
import kotlin.math.*

/** Draw a shape, bracket what you know, circle what you want. */
class ShapesMode(app: MainActivity) : DrawingMode(app) {
    override val id = "shape"
    override val title = "Shapes"
    override val padTitle = "NOTEPAD"
    override val tools = listOf(
        ToolSpec("shape", "✎", "1 Shape", Ink.PENCIL),
        ToolSpec("mark", "[ ]", "2 Known", Ink.KNOWN),
        ToolSpec("find", "◯", "3 Find", Ink.FIND)) + editTools()

    var typeOverride: String? = null
    val manual = HashMap<String, HashMap<String, Double>>()
    var manualTarget: String? = null
    var wholeChoice: String? = null
    var autoSwitched = false
    private val cache = HashMap<String, List<Cand>>()
    private val parsed = HashMap<String, List<LabeledEq>>()

    class Analysis(val hasShapeInk: Boolean, val rec: Recognised?, val model: ShapeModel?, val ann: Shapes.Annotation?, val known: Map<String, Double>,
                   val source: Map<String, String>, val target: String?, val whole: Boolean, val result: SolveResult?)
    var A: Analysis? = null

    init { paper.tool = "shape" }

    override fun kindFor(tool: String) = when (tool) { "mark" -> "mark"; "find" -> "find"; else -> "shape" }

    override fun onStroke(s: Stroke, paper: PaperView) {
        if (s.kind == "find") manualTarget = null
        if (s.kind == "mark" && Geom.loopiness(s.pts).first) manualTarget = null
        if (s.kind == "shape") {
            app.main.removeCallbacks(autoSwitch)
            app.main.postDelayed(autoSwitch, 1400)
        }
    }

    private val autoSwitch = Runnable {
        val a = A ?: return@Runnable
        if (paper.tool != "shape" || autoSwitched || a.model == null || (a.rec?.confidence ?: 0.0) < 0.6) return@Runnable
        if (paper.strokes.any { it.kind != "shape" }) return@Runnable
        autoSwitched = true
        paper.tool = "mark"
        if (app.mode === this) app.buildTray(this)
        app.toast("Looks like a ${a.model.def.name.lowercase()} ✓  Now bracket what you know and write the numbers.")
        app.scheduleSave()
    }

    override fun styleFor(s: Stroke): InkStyle = when (s.kind) {
        "find" -> InkStyle(Ink.FIND, 2.6f)
        "mark" -> if (A?.ann?.finds?.any { it.stroke?.id == s.id } == true) InkStyle(Ink.FIND, 2.6f) else InkStyle(Ink.KNOWN, 2.6f)
        else -> InkStyle(Ink.PENCIL, 2.8f)
    }

    private fun eqsFor(type: String) = parsed.getOrPut(type) { Shapes.DEFS.getValue(type).eqs.map { LabeledEq(Expr.parse(it, true) as Node.Eq, it) } }

    override fun refresh() {
        val strokes = snapshot()
        val type0 = typeOverride
        val man = HashMap(manual[type0 ?: ""] ?: emptyMap<String, Double>())
        val mt = manualTarget; val wc = wholeChoice
        val manAll = manual.mapValues { HashMap(it.value) }
        app.analyze(id, { compute(strokes, type0, manAll, mt, wc) }) { a -> A = a; render(a) }
    }

    private fun compute(strokes: List<Stroke>, typeOv: String?, manAll: Map<String, Map<String, Double>>, mt: String?, wc: String?): Analysis {
        val shapeStrokes = strokes.filter { it.kind == "shape" }
        if (shapeStrokes.isEmpty()) return Analysis(false, null, null, null, emptyMap(), emptyMap(), null, false, null)
        val rec = Shapes.recognise(shapeStrokes.map { it.pts })
        val type = typeOv ?: rec?.type ?: return Analysis(true, null, null, null, emptyMap(), emptyMap(), null, false, null)
        val B = Geom.bbox(shapeStrokes.flatMap { it.pts })
        val model = Shapes.buildModel(type, if (rec?.type == type) rec.fit else null, B)
        val ann = synchronized(cache) { Shapes.annotate(model, strokes.filter { it.kind == "mark" }, strokes.filter { it.kind == "find" }, cache) }
        val known = HashMap(model.def.fixed); val source = HashMap<String, String>()
        for ((v, x) in ann.values) { known[v] = x; source[v] = "ink" }
        for ((v, x) in manAll[type] ?: emptyMap()) { known[v] = x; source[v] = "typed" }
        val names = model.def.vars.map { it.name }
        var whole = false
        val target: String? = when {
            mt != null && mt in names -> mt
            ann.target?.whole == true -> { whole = true; if (wc != null && wc in model.whole) wc else model.whole.firstOrNull() }
            else -> ann.target?.part?.v
        }
        val result = target?.let {
            val domains = model.def.vars.associate { v -> v.name to if (v.kind == "angle") Domain.ANGLE else Domain.POSITIVE } + model.def.fixed.keys.associateWith { Domain.ANGLE }
            Solver.solveFor(eqsFor(type), known, it, domains, Domain.POSITIVE)
        }
        return Analysis(true, rec, model, ann, known, source, target, whole, result)
    }

    fun varName(v: String) = when (v) { "Surface" -> "Surface area"; "Lateral" -> "Side area"; "theta" -> "θ"; else -> Expr.nameOut(v) }
    fun varUnit(m: ShapeModel, v: String): String {
        val kind = m.def.vars.firstOrNull { it.name == v }?.kind ?: if (m.def.fixed.containsKey(v)) "angle" else "length"
        return when (kind) { "angle" -> "°"; "area" -> " sq units"; "volume" -> " cubic units"; else -> "" }
    }

    /* ---------------- drawing on the paper ---------------- */
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun drawBelow(c: Canvas, paper: PaperView) {
        val m = A?.model ?: return
        guide.color = Ink.GUIDE
        guide.alpha = if (m.guideOnly) 230 else 110
        guide.strokeWidth = if (m.guideOnly) 2f else 1.5f
        val dash = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        for (o in m.outline) {
            guide.pathEffect = if (m.guideOnly) dash else null
            when (o) {
                is Outline.Poly -> { val p = Path(); p.moveTo(o.pts[0].x.toFloat(), o.pts[0].y.toFloat()); o.pts.drop(1).forEach { p.lineTo(it.x.toFloat(), it.y.toFloat()) }; p.close(); c.drawPath(p, guide) }
                is Outline.Line -> { if (o.dash) guide.pathEffect = dash; c.drawLine(o.a.x.toFloat(), o.a.y.toFloat(), o.b.x.toFloat(), o.b.y.toFloat(), guide) }
                is Outline.Ell -> {
                    val r = RectF((o.c.x - o.rx).toFloat(), (o.c.y - o.ry).toFloat(), (o.c.x + o.rx).toFloat(), (o.c.y + o.ry).toFloat())
                    if (o.backDash) { c.drawArc(r, 0f, 180f, false, guide); guide.pathEffect = dash; c.drawArc(r, 180f, 180f, false, guide) } else c.drawOval(r, guide)
                }
            }
        }
        guide.pathEffect = DashPathEffect(floatArrayOf(4f, 5f), 0f); guide.alpha = 190; guide.strokeWidth = 1.5f
        for (p in m.parts) if (p is Part.Seg && p.guide) c.drawLine(p.a.x.toFloat(), p.a.y.toFloat(), p.b.x.toFloat(), p.b.y.toFloat(), guide)
        guide.pathEffect = null
        for (p in m.parts) if (p is Part.Angle && p.right) {
            val u = (p.p - p.o).norm(); val w = (p.q - p.o).norm(); val s = 12.0
            val path = Path()
            path.moveTo((p.o.x + u.x * s).toFloat(), (p.o.y + u.y * s).toFloat())
            path.lineTo((p.o.x + (u.x + w.x) * s).toFloat(), (p.o.y + (u.y + w.y) * s).toFloat())
            path.lineTo((p.o.x + w.x * s).toFloat(), (p.o.y + w.y * s).toFloat())
            c.drawPath(path, guide)
        }
    }

    override fun drawAbove(c: Canvas, paper: PaperView) {
        val a = A ?: return
        val m = a.model ?: return
        guide.pathEffect = null; guide.strokeWidth = 9f
        for (p in m.parts) {
            val isT = a.target == p.v
            val isK = a.known.containsKey(p.v) && !m.def.fixed.containsKey(p.v)
            if (!isT && !isK) continue
            guide.color = if (isT) Ink.FIND else Ink.KNOWN
            guide.alpha = 70
            when (p) {
                is Part.Seg -> c.drawLine(p.a.x.toFloat(), p.a.y.toFloat(), p.b.x.toFloat(), p.b.y.toFloat(), guide)
                is Part.Angle -> {
                    val a0 = Math.toDegrees(atan2(p.p.y - p.o.y, p.p.x - p.o.x)); var dd = Math.toDegrees(atan2(p.q.y - p.o.y, p.q.x - p.o.x)) - a0
                    while (dd > 180) dd -= 360; while (dd < -180) dd += 360
                    c.drawArc(RectF((p.o.x - 22).toFloat(), (p.o.y - 22).toFloat(), (p.o.x + 22).toFloat(), (p.o.y + 22).toFloat()), a0.toFloat(), dd.toFloat(), false, guide)
                }
                is Part.Rim -> c.drawOval(RectF((p.c.x - p.rx).toFloat(), (p.c.y - p.ry).toFloat(), (p.c.x + p.rx).toFloat(), (p.c.y + p.ry).toFloat()), guide)
            }
        }
    }

    /* ---------------- labels & pad ---------------- */
    private fun render(a: Analysis) {
        val labels = ArrayList<PaperLabel>()
        val m = a.model
        val solved = a.result?.takeIf { it.status == Status.SOLVED || it.status == Status.GIVEN }?.value
        if (m != null) {
            val shown = HashSet<String>()
            for (p in m.parts) {
                if (!p.showsLabel || !shown.add(p.v)) continue
                val fixed = m.def.fixed[p.v]
                val v = a.known[p.v]
                val (text, style) = when {
                    fixed != null -> "${varName(p.v)} = ${Expr.fmt(fixed)}°" to PaperLabel.FIXED
                    a.target == p.v && solved != null -> "${varName(p.v)} ${sign(solved)} ${Expr.fmt(solved)}${varUnit(m, p.v)}" to PaperLabel.ANSWER
                    a.target == p.v -> "${varName(p.v)} = ?" to PaperLabel.TARGET
                    v != null -> "${varName(p.v)} = ${Expr.fmt(v)}${varUnit(m, p.v)}" to PaperLabel.KNOWN
                    else -> varName(p.v) to PaperLabel.PLAIN
                }
                labels += PaperLabel(p.v, text, p.labelPos, style)
            }
            val t = a.target
            if (t != null && t in m.whole && t !in shown) {
                labels += PaperLabel(t, if (solved != null) "${varName(t)} ${sign(solved)} ${Expr.fmt(solved)}${varUnit(m, t)}" else "${varName(t)} = ?",
                    Pt(m.box.cx, max(28.0, m.box.y0 - 20)), if (solved != null) PaperLabel.ANSWER else PaperLabel.TARGET)
            }
        }
        paper.labels = labels
        paper.invalidate()
        renderPad(a)
    }

    private fun sign(v: Double) = if (abs(v - Math.round(v)) < 1e-9 * max(1.0, abs(v))) "=" else "≈"

    override fun onLabelTap(label: PaperLabel) {
        val m = A?.model ?: return
        val v = label.id
        if (m.def.fixed.containsKey(v)) { app.toast("This is the right angle (90°). Choose “Triangle” in the shape list if it isn't."); return }
        val d = m.def.vars.firstOrNull { it.name == v } ?: return
        val items = arrayOf("Type its value", "◯ Find this", "Clear typed value")
        AlertDialog.Builder(app).setTitle("${varName(v)} — ${d.desc}").setItems(items) { _, i ->
            when (i) {
                0 -> askText(varName(v), "value", A?.known?.get(v)?.let { Expr.fmt(it).replace("−", "-") } ?: "", true) { setManual(v, it) }
                1 -> { manualTarget = v; app.scheduleSave(); refresh() }
                2 -> setManual(v, "")
            }
        }.show()
    }

    private fun setManual(v: String, raw: String) {
        val type = A?.model?.type ?: return
        val map = manual.getOrPut(type) { HashMap() }
        if (raw.isBlank()) map.remove(v)
        else {
            val x = Expr.tryParse(raw)?.let { runCatching { Expr.evaluate(it) }.getOrNull() }
            if (x == null || !x.isFinite()) { app.toast("“$raw” isn't a number"); return }
            map[v] = x
            if (manualTarget == v) manualTarget = null
        }
        app.scheduleSave(); refresh()
    }

    private fun renderPad(a: Analysis) {
        if (app.mode !== this) return
        val pad = app.pad
        pad.clear()
        pad.heading("Shape")
        val m = a.model
        if (m == null) {
            if (a.hasShapeInk) pad.text("I can't tell what shape this is yet. Keep drawing, or pick it from the list.")
            else {
                pad.text("1  Draw a shape.  2  Bracket what you know and write the numbers.  3  Circle what you want.")
                pad.muted("Triangles, rectangles, squares, parallelograms, trapezoids, regular polygons, circles, ellipses, cubes, boxes, cylinders, cones, spheres and pyramids. For an angle, draw a small arc inside the corner. Circle the whole shape for its area or volume.")
            }
            pad.button("Pick the shape from a list…") { pickType() }
            return
        }
        pad.answer(m.def.name)
        val how = if (typeOverride != null) "chosen from the list" else if (m.guideOnly) "guide shape drawn over your sketch" else "recognised from your drawing"
        pad.muted(how)
        pad.button("Not right? Pick the shape…") { pickType() }
        m.def.note?.let { pad.muted(it) }
        for ((v, x) in m.def.fixed) pad.valueRow(varName(v), "right angle", "${Expr.fmt(x)}°", false, false, {}, {}, enabled = false)
        for (d in m.def.vars) {
            val v = a.known[d.name]
            pad.valueRow(varName(d.name), d.desc, v?.let { Expr.fmt(it).replace("−", "-") } ?: "", a.target == d.name, a.source[d.name] == "ink",
                { setManual(d.name, it) },
                { manualTarget = if (manualTarget == d.name && a.target == d.name) null else d.name; app.scheduleSave(); refresh() })
        }
        pad.heading("Answer")
        val ann = a.ann!!
        if (a.target == null) {
            pad.muted(if (a.known.keys.any { it !in m.def.fixed }) "Now circle what you want to find (step 3), or tap ◯ next to it." else "Bracket what you know and write the numbers, then circle what you want to find.")
        } else {
            if (a.whole) pad.chips(m.whole.map { varName(it) }, Ink.FIND, { i -> wholeChoice = m.whole[i]; app.scheduleSave(); refresh() }, m.whole.indexOf(a.target))
            pad.result(a.result!!, { varName(it) }, { varUnit(m, it) },
                multiNote = if (m.type.contains("riangle")) "Two different triangles match these measurements." else "",
                noSolutionNote = if (m.type.contains("riangle")) "Those measurements can't make a triangle (two sides must add up to more than the third)." else "")
        }
        for (i in ann.issues) pad.warn(when (i) {
            is Shapes.Issue.BracketNoNumber -> "The bracket on ${varName(i.v)} has no number next to it."
            is Shapes.Issue.BracketUnmatched -> "A bracket isn't lined up with any side. Draw it alongside a side, or as a small arc inside a corner."
            is Shapes.Issue.Unreadable -> "I couldn't read “${i.text}” for ${varName(i.v)}. Tap its label to type it."
            is Shapes.Issue.Stray -> "I couldn't tell which part “${i.text}” belongs to. Write it right next to its bracket."
            Shapes.Issue.FindNowhere -> "Your circle isn't around any part of the shape. Circle a side, a corner, or the whole shape."
        })
    }

    private fun pickType() {
        val names = listOf("Auto: recognise my drawing") + Shapes.TYPES.map { Shapes.DEFS.getValue(it).name }
        AlertDialog.Builder(app).setTitle("Which shape is it?").setItems(names.toTypedArray()) { _, i ->
            chooseType(if (i == 0) null else Shapes.TYPES[i - 1])
        }.show()
    }

    /** Pick a shape type (null = recognise automatically). A different shape replaces the drawing. */
    fun chooseType(type: String?) {
        manualTarget = null
        typeOverride = type
        if (type != null && A?.rec?.type != type) redrawAs(type)
        app.scheduleSave(); refresh()
    }

    /**
     * Swap the drawing for a clean version of the chosen shape, in the same
     * place and size (or centred on the page if nothing was drawn). Brackets,
     * numbers and circles belonged to the old shape, so they go too.
     * One tap of Undo brings the old drawing back.
     */
    private fun redrawAs(type: String) {
        val old = paper.strokes.filter { it.kind == "shape" }.flatMap { it.pts }
        val box = if (old.size > 2) Geom.bbox(old).let { b ->
            // keep sensible proportions for shapes that need them
            if (b.w < 60 || b.h < 60) Box(b.cx - 120, b.cy - 90, b.cx + 120, b.cy + 90) else b
        } else {
            val w = paper.widthDp.toDouble(); val h = paper.heightDp.toDouble()
            Box(w / 2 - 150, h / 2 - 110, w / 2 + 150, h / 2 + 110)
        }
        val model = Shapes.buildModel(type, null, box)
        val strokes = ArrayList<Stroke>()
        var id = (paper.strokes.maxOfOrNull { it.id } ?: 0) + 1
        // points every few dp along straight edges, so smoothing keeps them straight
        fun add(pts: List<Pt>) {
            val dense = ArrayList<Pt>()
            for (k in 1 until pts.size) {
                val a = pts[k - 1]; val b = pts[k]
                val n = maxOf(1, (a.dist(b) / 4).toInt())
                for (j in 0 until n) dense += a + (b - a) * (j.toDouble() / n)
            }
            dense += pts.last()
            strokes += Stroke(id++, "shape", dense)
        }
        for (o in model.outline) when (o) {
            is Outline.Poly -> add(o.pts + o.pts.first())
            is Outline.Line -> if (!o.dash) add(listOf(o.a, o.b))     // hidden edges stay as the dashed guide
            is Outline.Ell -> {
                val to = if (o.backDash) 180 else 360                    // only the front half of a hidden back edge
                add((0..to step 6).map { a -> val r = Math.toRadians(a.toDouble()); Pt(o.c.x + o.rx * cos(r), o.c.y + o.ry * sin(r)) })
            }
        }
        paper.setStrokesUndoable(strokes)
        app.toast("Redrawn as a ${model.def.name.lowercase()}. Undo brings your drawing back.")
    }

    override fun onChanged(paper: PaperView) {
        if (paper.strokes.none { it.kind == "shape" }) autoSwitched = false
        super.onChanged(paper)
    }

    override fun save(): JSONObject {
        val man = JSONObject()
        for ((t, m) in manual) man.put(t, JSONObject(m as Map<*, *>))
        return JSONObject().put("strokes", strokesJson()).put("type", typeOverride ?: "").put("manual", man)
            .put("target", manualTarget ?: "").put("whole", wholeChoice ?: "").put("auto", autoSwitched).put("tool", paper.tool)
    }

    override fun load(o: JSONObject) {
        typeOverride = o.optString("type").ifEmpty { null }
        manualTarget = o.optString("target").ifEmpty { null }
        wholeChoice = o.optString("whole").ifEmpty { null }
        autoSwitched = o.optBoolean("auto")
        paper.tool = o.optString("tool", "shape").ifEmpty { "shape" }
        o.optJSONObject("manual")?.let { man -> man.keys().forEach { t -> val mo = man.getJSONObject(t); manual[t] = HashMap(mo.keys().asSequence().associateWith { mo.getDouble(it) }) } }
        loadStrokes(o)
    }
}
