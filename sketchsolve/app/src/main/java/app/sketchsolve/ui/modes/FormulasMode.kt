package app.sketchsolve.ui.modes

import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import app.sketchsolve.MainActivity
import app.sketchsolve.data.Formula
import app.sketchsolve.data.Formulas
import app.sketchsolve.math.*
import app.sketchsolve.ui.*

/** The formula library: index cards on the paper, the solver on the legal pad. */
class FormulasMode(app: MainActivity) : Mode(app) {
    override val id = "lib"
    override val title = "Formulas"
    override val padTitle = "FORMULA SOLVER"
    override val tools = emptyList<ToolSpec>()

    private var query = ""
    private var cat = "all"
    private var open: Formula? = null
    private val inputs = HashMap<String, HashMap<String, String>>()
    private val targets = HashMap<String, String>()
    private val parsed = HashMap<String, Node.Eq>()
    private lateinit var list: LinearLayout
    private var root: View? = null

    private fun ast(f: Formula) = parsed.getOrPut(f.id) { Expr.parse(f.eq, true) as Node.Eq }

    override fun view(): View {
        root?.let { return it }
        val frame = FrameLayout(app)
        frame.addView(PaperView(app).apply { tool = "none" })
        val scroll = ScrollView(app)
        val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; setPadding(app.dp(20), app.dp(30), app.dp(20), app.dp(30)) }
        val search = EditText(app).apply {
            hint = "Search: ohm, volume, interest, triangle…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            background = GradientDrawable().apply { cornerRadius = app.dp(10f); setColor(0xEEFFFFFF.toInt()); setStroke(app.dp(1), 0x55406090) }
            setPadding(app.dp(12), app.dp(8), app.dp(12), app.dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim().lowercase(); renderList() }
            })
        }
        col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val chipBox = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        col.addView(chipBox)
        val catIds = listOf("all") + Formulas.CATEGORIES.map { it.id }
        val catNames = listOf("All") + Formulas.CATEGORIES.map { "${it.icon} ${it.name}" }
        fun chips() { chipBox.removeAllViews(); Pad(app, chipBox).chips(catNames, 0xFF2B4C7E.toInt(), { i -> cat = catIds[i]; chips(); renderList() }, catIds.indexOf(cat)) }
        chips()
        list = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        scroll.addView(col)
        frame.addView(scroll)
        renderList()
        root = frame
        return frame
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        for (c in Formulas.CATEGORIES) {
            if (cat != "all" && cat != c.id) continue
            val items = Formulas.LIST.filter { f -> f.cat == c.id && (query.isEmpty() || (f.name + " " + f.eq + " " + f.vars.joinToString(" ") { it.desc + " " + it.unit }).lowercase().contains(query)) }
            if (items.isEmpty()) continue
            list.addView(TextView(app).apply {
                text = "${c.icon}  ${c.name.uppercase()}"; setTextColor(0xFF3E5C8A.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.06f; setPadding(0, app.dp(16), 0, app.dp(4))
            })
            val grid = FlowRow(app)
            for (f in items) grid.addView(card(f))
            list.addView(grid)
        }
        if (list.childCount == 0) list.addView(TextView(app).apply { text = "No formulas match that search."; setPadding(0, app.dp(16), 0, 0) })
    }

    private fun card(f: Formula): View {
        val sel = open?.id == f.id
        val col = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = app.dp(4f); setColor(if (sel) 0xFFFFF7D6.toInt() else 0xFFFFFFFF.toInt()); setStroke(app.dp(if (sel) 2 else 1), if (sel) Ink.FIND else 0x44000000) }
            setPadding(app.dp(12), app.dp(8), app.dp(12), app.dp(10))
            elevation = app.dp(2f)
            layoutParams = android.view.ViewGroup.LayoutParams(app.dp(250), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { open = f; renderList(); renderPad() }
        }
        col.addView(View(app).apply { setBackgroundColor(0x66E05555) }, LinearLayout.LayoutParams(app.dp(226), app.dp(1)))
        col.addView(TextView(app).apply { text = f.name; setTextColor(Ink.TEXT); typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, app.dp(4), 0, 0); maxWidth = app.dp(226) })
        col.addView(TextView(app).apply { text = Expr.show(ast(f)); setTextColor(0xFF2B4C7E.toInt()); typeface = Ink.serif; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); maxWidth = app.dp(226) })
        return col
    }

    private fun target(f: Formula) = targets[f.id] ?: Expr.vars(ast(f).lhs).firstOrNull() ?: f.vars[0].name

    private fun domain(f: Formula, v: String): Domain {
        val unit = f.vars.first { it.name == v }.unit
        return when {
            unit == "°" -> Domain.ANGLE
            f.cat in listOf("geo2", "geo3", "chem", "money", "elec", "trig") -> Domain.POSITIVE
            else -> Domain.REAL
        }
    }

    override fun refresh() = renderPad()

    private fun renderPad() {
        if (app.mode !== this) return
        val pad = app.pad
        pad.clear()
        val f = open
        if (f == null) {
            pad.heading("Formula solver")
            pad.text("Pick a formula card on the left.")
            pad.muted("${Formulas.LIST.size} formulas: flat shapes, solids, trigonometry, algebra, electrical, physics, chemistry, money and everyday conversions.")
            return
        }
        val inp = inputs.getOrPut(f.id) { HashMap(f.vars.filter { it.default != null }.associate { it.name to Expr.fmt(it.default!!) }) }
        val t = target(f)
        pad.heading(f.name)
        pad.math(Expr.show(ast(f)))
        f.note?.let { pad.muted(it) }
        pad.muted("Fill in what you know. Tap ◯ next to what you want.")
        for (v in f.vars) {
            pad.valueRow(Expr.nameOut(v.name) + if (v.unit.isNotEmpty()) " (${v.unit})" else "", v.desc, if (v.name == t) "" else inp[v.name] ?: "", v.name == t, false,
                { s -> if (s.isBlank()) inp.remove(v.name) else inp[v.name] = s; renderPad() },
                { targets[f.id] = v.name; renderPad() })
        }
        val knowns = HashMap<String, Double>()
        for ((k, raw) in inp) {
            if (k == t) continue
            Expr.tryParse(raw)?.let { n -> runCatching { Expr.evaluate(n, deg = app.settings.deg) }.getOrNull()?.takeIf { it.isFinite() }?.let { knowns[k] = it } }
        }
        pad.heading("Answer")
        if (knowns.isEmpty()) pad.muted("Fill in the values you know.")
        else {
            val r = Solver.solveFor(listOf(LabeledEq(ast(f), f.name)), knowns, t, f.vars.associate { it.name to domain(f, it.name) }, deg = app.settings.deg)
            val unit = { v: String -> f.vars.firstOrNull { it.name == v }?.unit?.let { if (it.isEmpty()) "" else if (it == "°") "°" else " $it" } ?: "" }
            pad.result(r, { Expr.nameOut(it) }, unit)
        }
        pad.button("✎ Use on the Equations page") {
            val eq = app.modes.first { it is EquationsMode } as EquationsMode
            eq.addTyped(listOf(f.eq) + inp.filter { it.key != t && it.value.isNotBlank() }.map { "${it.key} = ${it.value}" }, t)
            app.select(eq)
        }
    }
}
