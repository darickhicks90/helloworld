package app.sketchsolve.ui.modes

import android.app.AlertDialog
import app.sketchsolve.MainActivity
import app.sketchsolve.data.Formulas
import app.sketchsolve.ink.Geom
import app.sketchsolve.ink.Pt
import app.sketchsolve.ink.Stroke
import app.sketchsolve.math.*
import app.sketchsolve.recog.*
import app.sketchsolve.ui.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Write equations; circle a letter, a piece, or a whole line to solve it. */
class EquationsMode(app: MainActivity) : DrawingMode(app) {
    override val id = "eq"
    override val title = "Equations"
    override val padTitle = "WHAT I READ · ANSWER"
    override val tools = listOf(
        ToolSpec("pen", "✎", "Write", Ink.PENCIL),
        ToolSpec("select", "◯", "Circle", Ink.SELECT)) + editTools()

    val overrides = HashMap<String, String>()
    data class Typed(val text: String, val words: Boolean)
    val typed = ArrayList<Typed>()
    var chipTarget: String? = null
    private val cache = HashMap<String, List<Cand>>()

    class ParsedLine(val line: Line?, val typedIndex: Int, val text: String, val ast: Node?, val err: String?)
    class Circled(val line: ParsedLine, val text: String, val keys: Set<String>, val ast: Node?, val err: String?)
    sealed class Res {
        data class Msg(val text: String, val warn: Boolean) : Res()
        data class Var(val name: String, val r: SolveResult) : Res()
        data class ExprR(val ast: Node, val r: SolveResult) : Res()
        data class EqR(val results: List<SolveResult>) : Res()
        data class Check(val ok: Boolean, val l: Double, val r: Double, val ast: Node) : Res()
        data class Calc(val ast: Node, val value: Double) : Res()
        data class Auto(val results: List<SolveResult>) : Res()
    }
    class Analysis(val lines: List<ParsedLine>, val sel: Stroke?, val circled: Circled?, val circledIds: Set<Int>, val result: Res?)

    var A: Analysis? = null

    init { paper.tool = "pen" }

    override fun kindFor(tool: String) = if (tool == "select") "select" else "ink"

    override fun onStroke(s: Stroke, paper: PaperView) {
        if (s.kind == "ink" && looksLikeSelection(s, paper.strokes)) s.kind = "select"
        if (s.kind == "select") { paper.mutate { list -> list.removeAll { it.kind == "select" && it !== s } }; chipTarget = null }
    }

    private fun looksLikeSelection(s: Stroke, strokes: List<Stroke>): Boolean {
        val (closed, sd, _) = Geom.loopiness(s.pts)
        if (sd < 40 || !closed) return false
        return strokes.any { o -> o !== s && o.kind == "ink" && o.pts.count { Geom.pointInPoly(it, s.pts) }.toDouble() / o.pts.size >= 0.6 }
    }

    override fun styleFor(s: Stroke): InkStyle = when {
        s.kind == "select" -> InkStyle(Ink.SELECT, 2.4f, dash = true)
        A?.circledIds?.contains(s.id) == true -> InkStyle(Ink.SELECT, 2.8f)
        else -> InkStyle(Ink.PENCIL, 2.6f)
    }

    override fun refresh() {
        val strokes = snapshot()
        val ov = HashMap(overrides)
        val ty = typed.toList()
        val chip = chipTarget
        val deg = app.settings.deg
        app.analyze(id, { compute(strokes, ov, ty, chip, deg) }) { a -> A = a; render(a) }
    }

    /* ---------------- analysis (background thread) ---------------- */
    private fun parse(text: String, words: Boolean): Pair<Node?, String?> = try { Expr.parse(text, words) to null } catch (e: MathSyntaxError) { null to e.message }

    private fun compute(strokes: List<Stroke>, ov: Map<String, String>, ty: List<Typed>, chip: String?, deg: Boolean): Analysis {
        val ink = strokes.filter { it.kind == "ink" }
        val sel = strokes.lastOrNull { it.kind == "select" }
        val byId = strokes.associateBy { it.id }
        val (lines, _) = synchronized(cache) { Layout.analyze(ink, Layout.Opts(ov, null, cache)) }
        val parsed = lines.map { l -> val (a, e) = parse(l.text, false); ParsedLine(l, -1, l.text, a, e) } +
            ty.mapIndexed { i, t -> val (a, e) = parse(t.text, t.words); ParsedLine(null, i, t.text, a, e) }
        var circled: Circled? = null
        val circledIds = HashSet<Int>()
        if (sel != null) {
            var best: Pair<ParsedLine, List<Sym>>? = null
            for (pl in parsed) {
                val l = pl.line ?: continue
                val inside = l.syms.filter { sym ->
                    val pts = sym.strokeIds.flatMap { byId[it]?.pts ?: emptyList() }
                    pts.isNotEmpty() && pts.count { Geom.pointInPoly(it, sel.pts) }.toDouble() / pts.size >= 0.5
                }
                if (inside.isNotEmpty() && (best == null || inside.size > best.second.size)) best = pl to inside
            }
            if (best != null) {
                val text = best.first.text
                val (s, e) = Layout.balanceRange(text, best.second.minOf { it.start }, best.second.maxOf { it.end })
                val piece = text.substring(s, e)
                val inRange = best.first.line!!.syms.filter { it.start >= s && it.end <= e }
                inRange.forEach { circledIds.addAll(it.strokeIds) }
                val cleaned = piece.trim('+', '*', '/', '=').trimEnd('-').ifEmpty { piece }
                val (a, err) = parse(cleaned, false)
                circled = Circled(best.first, piece, inRange.map { it.key }.toSet(), a, err)
            }
        }
        return Analysis(parsed, sel, circled, circledIds, solve(parsed, sel, circled, chip, deg))
    }

    private fun solve(lines: List<ParsedLine>, sel: Stroke?, circled: Circled?, chip: String?, deg: Boolean): Res? {
        val eqs = lines.mapIndexedNotNull { i, l -> (l.ast as? Node.Eq)?.let { LabeledEq(it, "line ${i + 1}") } }
        fun solveFor(t: String, extra: List<LabeledEq> = emptyList()) = Solver.solveFor(eqs + extra, emptyMap(), t, deg = deg)
        if (chip != null) return Res.Var(chip, solveFor(chip))
        if (sel != null && circled == null) return Res.Msg("The circle doesn't go around any writing. Draw it around a letter, a piece of an equation, or a whole line.", true)
        if (circled != null) {
            val ast = circled.ast ?: return Res.Msg("I can't read the circled part (“${circled.text}”): ${circled.err}. Tap a symbol to fix it.", true)
            if (ast is Node.Var && !Expr.isConst(ast.name)) return Res.Var(ast.name, solveFor(ast.name))
            if (ast is Node.Eq) {
                val vs = Expr.vars(ast).toList()
                if (vs.isEmpty()) {
                    val l = Expr.evaluate(ast.lhs, deg = deg); val r = Expr.evaluate(ast.rhs, deg = deg)
                    return Res.Check(abs(l - r) <= 1e-9 * (abs(l) + abs(r) + 1), l, r, ast)
                }
                val extra = if (circled.text == circled.line.text) emptyList() else listOf(LabeledEq(ast, "circled"))
                return Res.EqR(vs.map { solveFor(it, extra) })
            }
            val q = LabeledEq(Node.Eq(Node.Var("__Q"), ast), "circled")
            return Res.ExprR(ast, solveFor("__Q", listOf(q)))
        }
        if (eqs.isEmpty()) {
            val last = lines.lastOrNull { it.ast != null && it.ast !is Node.Eq }
            if (last != null && Expr.vars(last.ast!!).isEmpty()) return Res.Calc(last.ast, Expr.evaluate(last.ast, deg = deg))
            return null
        }
        val given = directlyGiven(lines, deg)
        val unknown = eqs.flatMap { Expr.vars(it.eq) }.distinct().filter { it !in given }
        if (unknown.isEmpty()) return null
        return Res.Auto(unknown.take(8).map { solveFor(it) })
    }

    private fun directlyGiven(lines: List<ParsedLine>, deg: Boolean): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (l in lines) {
            val a = l.ast as? Node.Eq ?: continue
            if (a.lhs is Node.Var && Expr.vars(a.rhs).isEmpty()) {
                val v = Expr.evaluate(a.rhs, deg = deg)
                if (v.isFinite()) out[(a.lhs as Node.Var).name] = v
            }
        }
        return out
    }

    /* ---------------- rendering ---------------- */
    private fun render(a: Analysis) {
        val labels = ArrayList<PaperLabel>()
        for (pl in a.lines) {
            val l = pl.line ?: continue
            for (sym in l.syms) if (sym.role == null) labels += PaperLabel("sym:${sym.key}", sym.label, Pt(sym.box.cx, sym.box.y0 - 7), PaperLabel.SMALL)
        }
        val sel = a.sel
        if (sel != null && a.result != null) {
            val b = Geom.bbox(sel.pts)
            var bad = false
            fun short(r: SolveResult?): String {
                if (r == null) return ""
                return when (r.status) {
                    Status.SOLVED, Status.GIVEN -> "${Expr.nameOut(r.target)} ${sign(r.value)} ${Expr.fmt(r.value)}"
                    Status.MULTIPLE -> "${Expr.nameOut(r.target)} = " + r.values.take(3).joinToString(" or ") { Expr.fmt(it) }
                    Status.INCONSISTENT -> { bad = true; "values don't agree" }
                    Status.NO_SOLUTION -> { bad = true; "no answer fits" }
                    else -> { bad = true; "not enough info" }
                }
            }
            val text = when (val r = a.result) {
                is Res.Var -> short(r.r)
                is Res.ExprR -> if (r.r.ok) "= " + r.r.values.joinToString(" or ") { Expr.fmt(it) } else short(r.r)
                is Res.EqR -> short(r.results.firstOrNull { it.ok } ?: r.results.firstOrNull())
                is Res.Check -> { bad = !r.ok; if (r.ok) "true ✓" else "not true" }
                else -> ""
            }
            if (text.isNotEmpty()) {
                val below = b.y1 + 30 < paper.heightDp
                labels += PaperLabel("answer", text, Pt(b.cx, if (below) b.y1 + 16 else b.y0 - 16), if (bad) PaperLabel.WARN else PaperLabel.ANSWER)
            }
        }
        paper.labels = labels
        paper.invalidate()
        renderPad(a)
    }

    private fun sign(v: Double) = if (abs(v - Math.round(v)) < 1e-9 * max(1.0, abs(v))) "=" else "≈"

    private fun renderPad(a: Analysis) {
        if (app.mode !== this) return
        val pad = app.pad
        pad.clear()
        pad.heading("What I read")
        if (a.lines.isEmpty()) {
            pad.text("Write an equation on the paper, then circle what you want to solve for.")
            pad.muted("Try 2x + 3 = 11 and circle the x. Circle a piece like 3 + 4 to work it out. Put known values on their own lines, like y = 5.")
        }
        for (pl in a.lines) {
            val l = pl.line
            if (l != null) {
                val inCircle = a.circled?.line === pl
                pad.chips(l.syms.map { if (it.label == "·") "·" else it.label }, if (inCircle) Ink.SELECT else Ink.PENCIL, { i -> symMenu(l.syms[i]) })
            } else {
                pad.muted("typed:")
            }
            if (pl.ast != null) {
                pad.math(Expr.show(pl.ast))
                val vs = Expr.vars(pl.ast).toList()
                if (vs.isNotEmpty()) {
                    pad.muted("tap a letter to solve for it:")
                    pad.chips(vs.map { Expr.nameOut(it) }, Ink.FIND, { i -> chipTarget = if (chipTarget == vs[i]) null else vs[i]; refresh() }, vs.indexOf(chipTarget))
                }
            } else pad.warn("${pl.err} (read as “${pl.text}”)")
            if (pl.typedIndex >= 0) pad.button("Remove this typed line") { typed.removeAt(pl.typedIndex); app.scheduleSave(); refresh() }
        }
        pad.button("⌨  Type a line instead") {
            askText("Type an equation", "e.g. y = 2x + 1") { t -> if (t.isNotEmpty()) { typed += Typed(t, false); app.scheduleSave(); refresh() } }
        }
        pad.heading("Answer")
        renderAnswer(pad, a)
    }

    private fun renderAnswer(pad: Pad, a: Analysis) {
        val known = directlyGiven(a.lines, app.settings.deg).keys
        fun suggest(target: String) {
            val list = Formulas.LIST.filter { f -> f.vars.any { it.name == target } && f.vars.filter { it.name != target }.all { it.name in known || it.default != null } }.take(4)
            if (list.isEmpty()) return
            pad.muted("Formulas that use ${Expr.nameOut(target)}:")
            for (f in list) pad.button("${f.name}: ${Expr.show(Expr.parse(f.eq, true))}") { typed += Typed(f.eq, true); app.scheduleSave(); refresh() }
        }
        when (val r = a.result) {
            null -> pad.muted("Nothing to solve yet.")
            is Res.Msg -> if (r.warn) pad.warn(r.text) else pad.text(r.text)
            is Res.Calc -> { pad.status("✓ Worked out", true); pad.answer("${Expr.show(r.ast)} ${sign(r.value)} ${Expr.fmt(r.value)}") }
            is Res.Check -> { pad.status(if (r.ok) "✓ True" else "✗ Not true", r.ok); pad.math(Expr.show(r.ast)); pad.muted("Left side ${Expr.fmt(r.l)}, right side ${Expr.fmt(r.r)}.") }
            is Res.Var -> { pad.result(r.r); if (r.r.status == Status.UNDERDETERMINED || r.r.status == Status.NO_EQUATION) suggest(r.name) }
            is Res.ExprR -> {
                if (r.r.ok) {
                    pad.status("✓ Worked out the circled part", true)
                    pad.answer("${Expr.show(r.ast)} ${sign(r.r.value)} ${r.r.values.joinToString(" or ") { Expr.fmt(it) }}")
                    pad.stepsOf(r.r.steps.filter { it.v != "__Q" }, { Expr.nameOut(it) }, { "" })
                } else pad.result(r.r)
            }
            is Res.EqR -> {
                val ok = r.results.filter { it.ok }
                if (ok.isNotEmpty()) ok.forEach { pad.result(it) } else { pad.result(r.results[0]); suggest(r.results[0].target) }
            }
            is Res.Auto -> {
                val conflict = r.results.firstOrNull { it.status == Status.INCONSISTENT }
                if (conflict != null) { pad.result(conflict); return }
                val ok = r.results.filter { it.status == Status.SOLVED || it.status == Status.MULTIPLE }
                val bad = r.results.filter { it.status != Status.SOLVED && it.status != Status.MULTIPLE }
                if (ok.isNotEmpty()) {
                    pad.status("✓ Quick answer", true)
                    for (o in ok) pad.answer(o.values.joinToString(" or ") { "${Expr.nameOut(o.target)} ${sign(it)} ${Expr.fmt(it)}" })
                }
                if (bad.isNotEmpty()) pad.muted("${if (ok.isNotEmpty()) "Not enough information yet for" else "Not enough information to solve for"} ${bad.joinToString(", ") { Expr.nameOut(it.target) }}. Circle one to see what's missing.")
                pad.muted("Circle a letter to see the working.")
            }
        }
    }

    /** Fix a misread symbol: choose another reading, split it, type it, or erase it. */
    private fun symMenu(sym: Sym) {
        if (sym.role != null) {
            AlertDialog.Builder(app).setTitle("This is read as a ${sym.role}.").setItems(arrayOf("Erase it")) { _, _ -> paper.removeStrokes(sym.strokeIds) }.show()
            return
        }
        val alts = LinkedHashSet<String>()
        sym.cands.take(8).forEach { alts += it.label }
        listOf("x", "1", "2", "7", "+", "-", "=", "(", ")").forEach { if (alts.size < 14) alts += it }
        val options = alts.toList()
        val items = options.map { if (it == sym.label) "$it   (current)" else it } +
            listOfNotNull("Something else…", if (sym.strokeIds.size > 1) "These are separate symbols" else null, if (sym.overridden) "Use my handwriting again" else null, "Erase it")
        AlertDialog.Builder(app).setTitle("I read “${sym.label}”. It's actually…").setItems(items.toTypedArray()) { _, i ->
            val choice = items[i]
            when {
                i < options.size -> choose(sym, options[i])
                choice == "Something else…" -> askText("What is this symbol?", "e.g. V") { t -> if (t.isNotEmpty()) choose(sym, t.take(3)) }
                choice == "These are separate symbols" -> choose(sym, Layout.SPLIT)
                choice == "Use my handwriting again" -> { overrides.remove(sym.key); app.scheduleSave(); refresh() }
                choice == "Erase it" -> paper.removeStrokes(sym.strokeIds)
            }
        }.show()
    }

    private fun choose(sym: Sym, label: String) {
        overrides[sym.key] = label
        if (app.settings.learn && label != Layout.SPLIT) {
            val pts = sym.strokeIds.mapNotNull { id -> paper.strokes.firstOrNull { it.id == id }?.pts }
            if (pts.isNotEmpty()) { Recognizer.addUserSample(label, pts); synchronized(cache) { cache.clear() }; app.saveSamples() }
        }
        app.scheduleSave()
        refresh()
    }

    fun addTyped(lines: List<String>, target: String?) {
        for (l in lines) typed += Typed(l, true)
        chipTarget = target
        app.scheduleSave()
    }

    override fun save(): JSONObject = JSONObject().put("strokes", strokesJson())
        .put("overrides", JSONObject(overrides as Map<*, *>))
        .put("typed", JSONArray().apply { typed.forEach { put(JSONObject().put("text", it.text).put("words", it.words)) } })

    override fun load(o: JSONObject) {
        o.optJSONObject("overrides")?.let { ov -> ov.keys().forEach { k -> overrides[k] = ov.getString(k) } }
        o.optJSONArray("typed")?.let { arr -> for (i in 0 until arr.length()) arr.getJSONObject(i).let { typed += Typed(it.getString("text"), it.optBoolean("words")) } }
        loadStrokes(o)
    }
}
