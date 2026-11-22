package app.sketchsolve.ui

import android.content.Context
import android.graphics.*
import android.text.*
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import app.sketchsolve.math.*
import app.sketchsolve.word.*
import kotlin.math.*

/**
 * Writes a solved word problem out on graph paper, like a tutor would:
 * the problem with its numbers highlighted, what each letter means, a
 * diagram, the equations (with letters, then with numbers), the working,
 * and a boxed answer with a "checked" stamp.
 */
class WorkingView(ctx: Context) : View(ctx) {
    sealed class Page {
        object Empty : Page()
        data class Busy(val question: String, val status: String) : Page()
        data class Simple(val question: String, val math: String, val result: SolveResult?, val error: String?) : Page()
        data class Word(val question: String, val answer: WordAnswer) : Page()
        data class Failed(val question: String, val message: String) : Page()
    }

    var page: Page = Page.Empty
        set(v) { field = v; requestLayout(); invalidate() }

    private val d = resources.displayMetrics.density
    private val margin = 34f
    private val palette = intArrayOf(0xFF2F6FD0.toInt(), 0xFF14864F.toInt(), 0xFFB35C00.toInt(), 0xFF7B3FB8.toInt(), 0xFF00838F.toInt(), 0xFF8D6E63.toInt())

    private fun tp(size: Float, face: Typeface, color: Int = Ink.PENCIL) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; this.color = color }
    private val handBody = tp(19f, Ink.hand)
    private val handHead = tp(15f, Ink.handBold, 0xFF3E5C8A.toInt())
    private val mathPaint = tp(24f, Ink.serif)
    private val answerPaint = tp(30f, Ink.serifBold, Ink.FIND)
    private val smallPaint = tp(14f, Typeface.DEFAULT, Ink.MUTED)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Something to draw: measured with a width, then drawn at a top y. */
    private abstract inner class Block { abstract fun height(w: Float): Float; abstract fun draw(c: Canvas, x: Float, y: Float, w: Float) }

    private inner class TextBlock(val text: CharSequence, val paint: TextPaint, val indent: Float = 0f, val gapAfter: Float = 8f) : Block() {
        private var lay: StaticLayout? = null
        private fun layout(w: Float): StaticLayout {
            val wi = max(50, (w - indent).toInt())
            lay?.let { if (it.width == wi) return it }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, wi).setLineSpacing(4f, 1f).build().also { lay = it }
        }
        override fun height(w: Float) = layout(w).height + gapAfter
        override fun draw(c: Canvas, x: Float, y: Float, w: Float) { c.save(); c.translate(x + indent, y); layout(w).draw(c); c.restore() }
    }

    private inner class AnswerBlock(val text: String, val sentence: String?, val stamp: String, val good: Boolean) : Block() {
        override fun height(w: Float) = 92f + if (sentence != null) 26f else 0f
        override fun draw(c: Canvas, x: Float, y: Float, w: Float) {
            val tw = answerPaint.measureText(text)
            val boxW = min(w, tw + 48f)
            line.color = Ink.FIND; line.strokeWidth = 2.6f
            wobblyRect(c, RectF(x, y + 8, x + boxW, y + 62), 7)
            c.drawText(text, x + 24, y + 46, answerPaint)
            // stamp
            c.save()
            c.rotate(-6f, x + boxW + 70, y + 36)
            val sp = tp(15f, Typeface.DEFAULT_BOLD, if (good) Ink.KNOWN else Ink.WARN)
            val sw = sp.measureText(stamp) + 20
            line.color = sp.color; line.strokeWidth = 2f
            c.drawRoundRect(RectF(x + boxW + 24, y + 22, x + boxW + 24 + sw, y + 50), 6f, 6f, line)
            c.drawText(stamp, x + boxW + 34, y + 42, sp)
            c.restore()
            if (sentence != null) c.drawText(sentence, x + 4, y + 92, handBody)
        }
    }

    private inner class DiagramBlock(val dia: Diagram, val names: Map<String, Quantity>) : Block() {
        override fun height(w: Float) = when (dia) { is Diagram.Table -> 34f + 30f * dia.rows.size + 20f; is Diagram.Shape -> 170f; else -> 130f }
        override fun draw(c: Canvas, x: Float, y: Float, w: Float) = drawDiagram(c, dia, x, y, min(w, 560f), names)
    }

    private val blocks = ArrayList<Block>()

    private fun rebuild() {
        blocks.clear()
        when (val p = page) {
            Page.Empty -> {
                blocks += TextBlock("Ask a question", tp(26f, Ink.handBold, Ink.TEXT), gapAfter = 10f)
                blocks += TextBlock("Hold the microphone and say it, or type it.\n\nTry:\n•  “Two trains leave stations 300 miles apart and head toward each other at 70 and 50 miles per hour. When do they meet?”\n•  “What is 15 percent of 80?”\n•  “x squared plus 3 equals 12”", handBody)
            }
            is Page.Busy -> {
                heading("The question"); blocks += TextBlock(p.question, handBody)
                blocks += TextBlock("✎  ${p.status}", tp(18f, Ink.handBold, Ink.SELECT), gapAfter = 12f)
            }
            is Page.Failed -> {
                heading("The question"); blocks += TextBlock(p.question, handBody)
                blocks += TextBlock("⚠ ${p.message}", tp(17f, Ink.hand, Ink.WARN))
            }
            is Page.Simple -> {
                heading("The question"); blocks += TextBlock(p.question, handBody)
                heading("Written as maths"); blocks += TextBlock(Expr.tryParse(p.math)?.let { Expr.show(it) } ?: p.math, mathPaint)
                val r = p.result
                if (p.error != null) blocks += TextBlock("⚠ ${p.error}", tp(17f, Ink.hand, Ink.WARN))
                if (r != null) addResult(r, null, emptyMap())
            }
            is Page.Word -> buildWord(p)
        }
    }

    private fun heading(t: String) { blocks += TextBlock(t.uppercase(), handHead, gapAfter = 2f) }

    private fun buildWord(p: Page.Word) {
        val a = p.answer
        val s = a.setup
        val colorOf = HashMap<String, Int>()
        s.givens.forEachIndexed { i, g -> colorOf[g.name] = palette[i % palette.size] }
        // the problem, with each used number highlighted in its letter's colour
        heading("The question")
        val q = SpannableStringBuilder(p.question)
        for (g in s.givens) {
            val quote = g.quote ?: continue
            val at = p.question.indexOf(quote, ignoreCase = true)
            if (at < 0) continue
            val col = colorOf.getValue(g.name)
            q.setSpan(BackgroundColorSpan((col and 0x00FFFFFF) or 0x33000000), at, at + quote.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            q.setSpan(ForegroundColorSpan(col), at, at + quote.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        blocks += TextBlock(q, handBody, gapAfter = 12f)

        heading("What we know")
        for (g in s.givens) {
            val t = SpannableStringBuilder()
            val name = Expr.nameOut(g.name)
            t.append(name, ForegroundColorSpan(colorOf.getValue(g.name)), 0)
            t.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, 0)
            t.append(" = ${Expr.fmt(g.value ?: Double.NaN)}${unitText(g.unit)}")
            if (g.meaning.isNotBlank()) t.append("    (${g.meaning})", ForegroundColorSpan(Ink.MUTED), 0)
            blocks += TextBlock(t, mathPaint, indent = 14f, gapAfter = 2f)
        }
        heading("Let")
        for (u in s.unknowns) {
            val t = SpannableStringBuilder()
            val name = Expr.nameOut(u.name)
            t.append(name, ForegroundColorSpan(Ink.FIND), 0)
            t.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, 0)
            t.append(" = ${u.meaning.ifBlank { "the unknown" }}${if (u.unit.isNotBlank()) ", in ${u.unit}" else ""}")
            blocks += TextBlock(t, handBody, indent = 14f, gapAfter = 2f)
        }
        s.diagram?.let { heading("Picture it"); blocks += DiagramBlock(it, s.all.associateBy { q2 -> q2.name }) }

        heading(if (s.equations.size > 1) "The equations" else "The equation")
        val values = s.givens.mapNotNull { g -> g.value?.let { g.name to (Node.Num(it) as Node) } }.toMap()
        for (e in s.equations) {
            val n = Expr.tryParse(e, words = true) ?: continue
            blocks += TextBlock(Expr.show(n), mathPaint, indent = 14f, gapAfter = 0f)
            val filled = Expr.show(substitute(n, values))
            if (filled != Expr.show(n)) blocks += TextBlock("→  $filled", tp(20f, Ink.serif, Ink.MUTED), indent = 30f, gapAfter = 6f)
        }
        val r = a.result
        if (r != null) addResult(r, s, colorOf)
        if (a.issues.isNotEmpty()) {
            heading("Check")
            for (i in a.issues) blocks += TextBlock((if (i.severity == Issue.Severity.ERROR) "✗ " else "• ") + i.message,
                tp(15f, Typeface.DEFAULT, if (i.severity == Issue.Severity.NOTE) Ink.MUTED else Ink.WARN), indent = 14f, gapAfter = 2f)
        }
    }

    private fun addResult(r: SolveResult, s: Setup?, colorOf: Map<String, Int>) {
        val name = { v: String -> Expr.nameOut(v) }
        val unit = { v: String -> unitText(s?.quantity(v)?.unit ?: "") }
        val steps = r.steps.filter { it.v != "__Q" }
        if (steps.isNotEmpty()) {
            heading("Working")
            for (st in steps) {
                if (st.system != null) { blocks += TextBlock("Solve the equations together:", handBody, indent = 14f, gapAfter = 2f); continue }
                val re = st.rearranged?.takeIf { it !is Node.Num }
                val lin = if (re == null && s != null) linearStep(st, s) else null
                if (lin != null) { for (l in lin) blocks += TextBlock(l, mathPaint, indent = 14f, gapAfter = 4f); continue }
                val body = if (re != null) "${name(st.v)} = ${Expr.show(re)} = ${Expr.fmt(st.value)}${unit(st.v)}" else "${name(st.v)} = ${Expr.fmt(st.value)}${unit(st.v)}"
                blocks += TextBlock(body, mathPaint, indent = 14f, gapAfter = 4f)
            }
        }
        val ok = r.status == Status.SOLVED || r.status == Status.GIVEN || r.status == Status.MULTIPLE
        heading("Answer")
        val conf = (page as? Page.Word)?.answer?.confidence
        if (ok) {
            val vals = r.values.take(3).joinToString(" or ") { "${Expr.fmt(it)}${unit(r.target)}" }
            val meaning = s?.quantity(r.target)?.meaning?.takeIf { it.isNotBlank() }
            val good = conf == null || conf == Confidence.HIGH
            blocks += AnswerBlock("${if (r.target == "__Q") "" else name(r.target) + " = "}$vals",
                meaning?.let { "${it.replaceFirstChar { c -> c.uppercase() }}: $vals." },
                if (good) "✓ CHECKED" else "PLEASE CHECK", good)
        } else {
            val msg = when (r.status) {
                Status.UNDERDETERMINED, Status.NO_EQUATION -> "Not enough information to find ${name(r.target)}." + if (r.need.isNotEmpty()) " Knowing any one of ${r.need.joinToString(", ") { name(it) }} would settle it." else ""
                Status.INCONSISTENT -> "These facts contradict each other, so there's no answer."
                else -> "No value of ${name(r.target)} fits all of the facts."
            }
            blocks += TextBlock("✋ $msg", tp(19f, Ink.handBold, Ink.WARN))
        }
    }

    /** For an equation where the unknown appears more than once but linearly: collect it, then divide. */
    private fun linearStep(st: Step, s: Setup): List<String>? {
        val known = HashMap<String, Double>()
        s.givens.forEach { g -> g.value?.let { known[g.name] = it } }
        val r = (page as? Page.Word)?.answer?.result?.env ?: return null
        for ((k, v) in r) if (k != st.v) known.putIfAbsent(k, v)
        val names = (known.keys + st.v).toList()
        val idx = names.withIndex().associate { it.value to it.index }
        val f = { x: Double ->
            val env = DoubleArray(names.size) { i -> if (names[i] == st.v) x else known.getValue(names[i]) }
            Expr.compile(st.eq.lhs, idx, true)(env) - Expr.compile(st.eq.rhs, idx, true)(env)
        }
        val f0 = f(0.0); val f1 = f(1.0); val f2 = f(2.0)
        if (!f0.isFinite() || !f1.isFinite() || !f2.isFinite()) return null
        if (abs(f2 - 2 * f1 + f0) > 1e-9 * max(1.0, abs(f1))) return null   // not linear
        val a = f1 - f0
        if (abs(a) < 1e-12) return null
        val name = Expr.nameOut(st.v)
        val unit = unitText(s.quantity(st.v)?.unit ?: "")
        return listOf("${Expr.fmt(a)}$name = ${Expr.fmt(-f0)}", "$name = ${Expr.fmt(-f0)} ÷ ${Expr.fmt(a)} = ${Expr.fmt(st.value)}$unit")
    }

    private fun unitText(u: String) = when {
        u.isBlank() -> ""
        u.trim() == "%" -> "%"
        u.trim() == "$" -> " dollars"
        else -> " ${u.trim()}"
    }

    private fun substitute(n: Node, values: Map<String, Node>) = Expr.tidy(subst(n, values))
    private fun subst(n: Node, m: Map<String, Node>): Node = when (n) {
        is Node.Var -> m[n.name] ?: n
        is Node.Neg -> Node.Neg(subst(n.a, m))
        is Node.Op -> Node.Op(n.op, subst(n.a, m), subst(n.b, m))
        is Node.Fn -> Node.Fn(n.name, subst(n.a, m))
        is Node.Eq -> Node.Eq(subst(n.lhs, m), subst(n.rhs, m))
        is Node.Num -> n
    }

    /* ---------------- diagrams, drawn by hand ---------------- */
    private var seed = 1
    private fun jitter(): Float { seed = seed * 1103515245 + 12345; return (((seed ushr 16) and 0x7fff) / 32767f - 0.5f) * 2.2f }

    private fun wobblyLine(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val p = Path(); p.moveTo(x0, y0)
        val mx = (x0 + x1) / 2 + jitter(); val my = (y0 + y1) / 2 + jitter()
        p.quadTo(mx, my, x1 + jitter() * 0.4f, y1 + jitter() * 0.4f)
        c.drawPath(p, line)
    }

    private fun wobblyRect(c: Canvas, r: RectF, s: Int) {
        seed = s
        wobblyLine(c, r.left, r.top, r.right, r.top); wobblyLine(c, r.right, r.top, r.right, r.bottom)
        wobblyLine(c, r.right, r.bottom, r.left, r.bottom); wobblyLine(c, r.left, r.bottom, r.left, r.top)
    }

    private fun arrow(c: Canvas, x0: Float, y: Float, x1: Float) {
        wobblyLine(c, x0, y, x1, y)
        val dir = sign(x1 - x0)
        wobblyLine(c, x1, y, x1 - dir * 10, y - 6); wobblyLine(c, x1, y, x1 - dir * 10, y + 6)
    }

    /** Text for a diagram cell: a known name shows its value; an expression is tidied. */
    private fun label(s: String, names: Map<String, Quantity>): String {
        val q = names[s.trim()]
        if (q != null) return if (q.value != null) "${Expr.fmt(q.value)}${unitText(q.unit)}" else Expr.nameOut(q.name)
        return Expr.tryParse(s, words = true)?.let { Expr.show(it) } ?: s
    }

    private fun drawDiagram(c: Canvas, dia: Diagram, x: Float, y: Float, w: Float, names: Map<String, Quantity>) {
        seed = 7
        line.color = Ink.PENCIL; line.strokeWidth = 2.2f
        val small = tp(15f, Ink.hand)
        when (dia) {
            is Diagram.Motion -> {
                val y0 = y + 70
                fill.color = Ink.PENCIL
                wobblyLine(c, x + 10, y0, x + w - 10, y0)
                c.drawCircle(x + 10, y0, 4f, fill); c.drawCircle(x + w - 10, y0, 4f, fill)
                dia.gap?.let {
                    line.color = Ink.GUIDE
                    wobblyLine(c, x + 10, y0 + 22, x + w - 10, y0 + 22)
                    wobblyLine(c, x + 10, y0 + 16, x + 10, y0 + 28); wobblyLine(c, x + w - 10, y0 + 16, x + w - 10, y0 + 28)
                    val t = label(it, names); c.drawText(t, x + w / 2 - small.measureText(t) / 2, y0 + 44, small)
                    line.color = Ink.PENCIL
                }
                dia.movers.take(3).forEachIndexed { i, m ->
                    val fromLeft = m.from != "right"
                    val sx = if (fromLeft) x + 20 else x + w - 20
                    val ex = if (m.direction == "left") sx - w * 0.3f else sx + w * 0.3f
                    val col = palette[i % palette.size]
                    line.color = col; small.color = col
                    val yy = y0 - 18 - i * 34
                    arrow(c, sx, yy, ex)
                    val t = "${m.label}: ${label(m.speed, names)}"
                    c.drawText(t, if (fromLeft) sx else sx - small.measureText(t), yy - 8, small)
                }
                small.color = Ink.PENCIL; line.color = Ink.PENCIL
            }
            is Diagram.Table -> {
                val cols = max(1, max(dia.headers.size, dia.rows.maxOfOrNull { it.size } ?: 1))
                val cw = w / cols
                val rows = listOf(dia.headers) + dia.rows
                rows.forEachIndexed { r, row ->
                    val yy = y + r * 30f
                    for (k in 0 until cols) {
                        val t = row.getOrNull(k)?.let { if (r == 0) it else label(it, names) } ?: ""
                        val p = if (r == 0) tp(14f, Ink.handBold, 0xFF3E5C8A.toInt()) else small
                        c.drawText(t.take(22), x + k * cw + 8, yy + 21, p)
                    }
                    wobblyLine(c, x, yy + 30, x + cols * cw, yy + 30)
                }
                for (k in 1 until cols) wobblyLine(c, x + k * cw, y + 2, x + k * cw, y + rows.size * 30f)
            }
            is Diagram.Bar -> {
                val total = dia.parts.size.coerceAtLeast(1)
                val y0 = y + 30
                var xx = x
                dia.parts.forEachIndexed { i, (lab, v) ->
                    val pw = (w - 20) / total
                    fill.color = (palette[i % palette.size] and 0x00FFFFFF) or 0x30000000
                    c.drawRect(xx, y0, xx + pw, y0 + 46, fill)
                    line.color = palette[i % palette.size]
                    wobblyRect(c, RectF(xx, y0, xx + pw, y0 + 46), 3 + i)
                    small.color = palette[i % palette.size]
                    c.drawText(lab.take(24), xx + 6, y0 + 20, small)
                    c.drawText(label(v, names).take(24), xx + 6, y0 + 40, small)
                    xx += pw
                }
                small.color = Ink.PENCIL; line.color = Ink.PENCIL
                dia.total?.let {
                    wobblyLine(c, x, y0 + 60, xx, y0 + 60)
                    val t = "whole: ${label(it, names)}"; c.drawText(t, x + (xx - x) / 2 - small.measureText(t) / 2, y0 + 82, small)
                }
            }
            is Diagram.NumberLine -> {
                val y0 = y + 60
                arrow(c, x + 5, y0, x + w - 5)
                dia.points.take(6).forEachIndexed { i, (lab, v) ->
                    val px = x + 30 + i * (w - 60) / max(1, dia.points.size - 1).toFloat()
                    wobblyLine(c, px, y0 - 8, px, y0 + 8)
                    small.color = palette[i % palette.size]
                    c.drawText(lab, px - small.measureText(lab) / 2, y0 - 16, small)
                    val vv = label(v, names); if (vv != lab) c.drawText(vv, px - small.measureText(vv) / 2, y0 + 28, small)
                }
                small.color = Ink.PENCIL
            }
            is Diagram.Shape -> drawShape(c, dia, x, y, w, names, small)
        }
    }

    private fun drawShape(c: Canvas, dia: Diagram.Shape, x: Float, y: Float, w: Float, names: Map<String, Quantity>, small: TextPaint) {
        val labels = dia.labels.mapValues { label(it.value, names) }
        fun lab(key: String, alt: String) = labels[key] ?: labels[alt]
        val bw = min(w * 0.55f, 240f); val bh = 110f
        val x0 = x + 40; val y0 = y + 20
        when (dia.type.lowercase()) {
            "circle" -> {
                val r = 60f; val cx = x0 + 80; val cy = y0 + 70
                c.drawCircle(cx, cy, r, line)
                line.color = Ink.GUIDE; wobblyLine(c, cx, cy, cx + r, cy); line.color = Ink.PENCIL
                (lab("radius", "r"))?.let { c.drawText(it, cx + 8, cy - 6, small) }
                (lab("diameter", "d"))?.let { c.drawText("d = $it", cx - r, cy + r + 22, small) }
            }
            "triangle", "righttriangle" -> {
                val p = Path(); p.moveTo(x0, y0 + bh + 20); p.lineTo(x0 + bw, y0 + bh + 20); p.lineTo(x0, y0 + 10); p.close()
                c.drawPath(p, line)
                lab("base", "b")?.let { c.drawText(it, x0 + bw / 2 - 20, y0 + bh + 42, small) }
                lab("height", "h")?.let { c.drawText(it, x0 - 36, y0 + bh / 2 + 10, small) }
                lab("hypotenuse", "c")?.let { c.drawText(it, x0 + bw / 2 + 10, y0 + bh / 2, small) }
            }
            else -> {
                val r = RectF(x0, y0 + 10, x0 + bw, y0 + 10 + bh)
                wobblyRect(c, r, 11)
                lab("width", "w")?.let { c.drawText(it, r.centerX() - small.measureText(it) / 2, r.bottom + 22, small) }
                (lab("length", "l") ?: lab("height", "h"))?.let { c.drawText(it, r.right + 8, r.centerY(), small) }
                lab("area", "A")?.let { c.drawText("area $it", r.centerX() - 30, r.centerY() + 6, small) }
            }
        }
    }

    /* ---------------- view plumbing ---------------- */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        rebuild()
        val inner = w / d - 2 * margin
        var h = 30f
        for (b in blocks) h += b.height(inner)
        val minH = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, max((h * d + 40 * d).toInt(), if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) 0 else minH))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(d, d)
        val wDp = width / d
        // graph paper
        val sheet = RectF(6f, 0f, wDp - 6f, height / d - 6f)
        fill.color = 0x30000000; canvas.drawRect(sheet.left + 2, sheet.top + 4, sheet.right + 3, sheet.bottom + 5, fill)
        fill.color = Ink.PAPER; canvas.drawRect(sheet, fill)
        val g = Paint()
        var x = sheet.left + 20; var i = 1
        while (x < sheet.right) { g.color = if (i % 5 == 0) Ink.GRID_MAJOR else Ink.GRID; g.strokeWidth = if (i % 5 == 0) 0.9f else 0.6f; canvas.drawLine(x, sheet.top, x, sheet.bottom, g); x += 20; i++ }
        var y = sheet.top + 20; i = 1
        while (y < sheet.bottom) { g.color = if (i % 5 == 0) Ink.GRID_MAJOR else Ink.GRID; g.strokeWidth = if (i % 5 == 0) 0.9f else 0.6f; canvas.drawLine(sheet.left, y, sheet.right, y, g); y += 20; i++ }
        // the write-up
        var top = 30f
        val inner = wDp - 2 * margin
        for (b in blocks) { b.draw(canvas, margin, top, inner); top += b.height(inner) }
        canvas.restore()
    }
}
