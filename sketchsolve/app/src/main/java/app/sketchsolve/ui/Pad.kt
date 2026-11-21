package app.sketchsolve.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import app.sketchsolve.math.*

/** Builds the contents of the legal pad (or a card) one row at a time. */
class Pad(val ctx: Context, val box: LinearLayout) {
    fun clear() = box.removeAllViews()

    private fun tv(text: CharSequence, size: Float, color: Int, face: Typeface, top: Int = 2): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = face
        setPadding(0, ctx.dp(top), 0, ctx.dp(2))
        box.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun heading(text: String) = tv(text, 13f, Ink.MUTED, Typeface.DEFAULT_BOLD, 10).apply { letterSpacing = 0.08f; isAllCaps = true }
    fun text(text: CharSequence) = tv(text, 15f, Ink.TEXT, Ink.hand)
    fun muted(text: CharSequence) = tv(text, 13.5f, Ink.MUTED, Typeface.DEFAULT)
    fun warn(text: CharSequence) = tv("⚠ $text", 13.5f, Ink.WARN, Typeface.DEFAULT)
    fun math(text: CharSequence) = tv(text, 18f, Ink.TEXT, Ink.serif)
    fun answer(text: CharSequence) = tv(text, 26f, Ink.FIND, Ink.serifBold)

    fun status(text: String, ok: Boolean) {
        val v = TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (ok) Ink.KNOWN else Ink.WARN)
            background = GradientDrawable().apply { cornerRadius = ctx.dp(14f); setColor(if (ok) 0xFFDDF3E6.toInt() else 0xFFFFE9C9.toInt()) }
            setPadding(ctx.dp(10), ctx.dp(3), ctx.dp(10), ctx.dp(3))
        }
        box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(6) })
    }

    fun step(small: String, main: CharSequence) {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = ctx.dp(8f); setColor(0x22FFFFFF); setStroke(ctx.dp(1), 0x33A08F50) }
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(5))
        }
        col.addView(TextView(ctx).apply { text = small; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); setTextColor(Ink.MUTED) })
        col.addView(TextView(ctx).apply { text = main; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Ink.TEXT); typeface = Ink.serif })
        box.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(5) })
    }

    fun chips(items: List<String>, color: Int = Ink.KNOWN, onTap: ((Int) -> Unit)? = null, selected: Int = -1) {
        val row = FlowRow(ctx)
        items.forEachIndexed { i, s ->
            row.addView(TextView(ctx).apply {
                text = s
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Ink.serifBold
                val on = i == selected
                setTextColor(if (on) 0xFFFFFFFF.toInt() else color)
                background = GradientDrawable().apply { cornerRadius = ctx.dp(14f); setColor(if (on) color else 0x22FFFFFF); setStroke(ctx.dp(1.4f).toInt(), color) }
                setPadding(ctx.dp(10), ctx.dp(3), ctx.dp(10), ctx.dp(3))
                if (onTap != null) setOnClickListener { onTap(i) }
            })
        }
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
    }

    fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(ctx).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Ink.TEXT)
            background = GradientDrawable().apply { cornerRadius = ctx.dp(10f); setColor(0xFFFFFDF0.toInt()); setStroke(ctx.dp(1), 0x66806A30) }
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            minHeight = ctx.dp(40); minimumHeight = ctx.dp(40)
            setOnClickListener { onClick() }
        }
        box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(6) })
        return b
    }

    /** name | description | value box | ◯ find */
    fun valueRow(name: String, desc: String, value: String, isTarget: Boolean, fromInk: Boolean, onValue: (String) -> Unit, onFind: () -> Unit, enabled: Boolean = true) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(4), ctx.dp(1), ctx.dp(2), ctx.dp(1))
            if (isTarget) background = GradientDrawable().apply { cornerRadius = ctx.dp(8f); setColor(0x33C2255C) }
            else if (value.isNotEmpty()) background = GradientDrawable().apply { cornerRadius = ctx.dp(8f); setColor(0x2214864F) }
        }
        val label = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        label.addView(TextView(ctx).apply { text = name; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Ink.serifBold; setTextColor(Ink.TEXT); maxLines = 1 })
        label.addView(TextView(ctx).apply { text = desc; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); setTextColor(Ink.MUTED); maxLines = 2 })
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val et = EditText(ctx).apply {
            setText(value)
            hint = if (isTarget) "find" else "—"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSingleLine = true
            isEnabled = enabled && !isTarget
            setTextColor(if (fromInk) Ink.KNOWN else Ink.TEXT)
            background = GradientDrawable().apply { cornerRadius = ctx.dp(6f); setColor(0xCCFFFFFF.toInt()); setStroke(ctx.dp(1), if (fromInk) Ink.KNOWN else 0x55806A30) }
            setPadding(ctx.dp(6), ctx.dp(3), ctx.dp(6), ctx.dp(3))
            setOnEditorActionListener { v, _, _ -> onValue(v.text.toString()); v.clearFocus(); true }
            setOnFocusChangeListener { v, has -> if (!has) { val t = (v as EditText).text.toString(); if (t != value) onValue(t) } }
        }
        row.addView(et, LinearLayout.LayoutParams(ctx.dp(70), ctx.dp(34)))
        row.addView(TextView(ctx).apply {
            text = "◯"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isTarget) 0xFFFFFFFF.toInt() else Ink.FIND)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (isTarget) Ink.FIND else 0x00000000); setStroke(ctx.dp(1.5f).toInt(), Ink.FIND) }
            contentDescription = "Find $desc"
            setOnClickListener { onFind() }
        }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { leftMargin = ctx.dp(6) })
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(3) })
    }

    fun add(v: View) = box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    /* ---------------- solver results ---------------- */
    fun result(r: SolveResult, name: (String) -> String = { Expr.nameOut(it) }, unit: (String) -> String = { "" }, steps: Boolean = true, multiNote: String = "", noSolutionNote: String = "") {
        val t = name(r.target)
        fun eqSign(v: Double) = if (abs(v - Math.round(v)) < 1e-9 * kotlin.math.max(1.0, abs(v))) "=" else "≈"
        when (r.status) {
            Status.GIVEN -> { status("✓ You gave this", true); answer("$t = ${Expr.fmt(r.value)}${unit(r.target)}") }
            Status.SOLVED -> {
                status("✓ Solved", true)
                answer("$t ${eqSign(r.value)} ${Expr.fmt(r.value)}${unit(r.target)}")
                if (steps) stepsOf(r.steps, name, unit)
            }
            Status.MULTIPLE -> {
                status("✓ ${if (r.values.size > 4) "Many" else r.values.size.toString()} possible answers", true)
                answer(r.values.take(4).joinToString("  or  ") { "$t ${eqSign(it)} ${Expr.fmt(it)}${unit(r.target)}" })
                muted("More than one value fits what you gave. $multiNote".trim())
            }
            Status.UNDERDETERMINED -> {
                status("✋ Not enough information", false)
                text("I can't pin down $t from what's here.")
                if (r.need.isNotEmpty()) { muted("Give me any ONE of these as well:"); chips(r.need.map(name)) }
                else text("You need at least two more values. Still unknown: ${r.unknowns.joinToString(", ") { name(it) }}.")
            }
            Status.NO_EQUATION -> { status("✋ Not enough information", false); text("$t isn't connected to anything else, so there's nothing to work it out from.") }
            Status.INCONSISTENT -> {
                status("⚠ These values don't agree", false)
                text("With the values given, this can't be true:")
                for (c in r.conflicts) math(Expr.show(c))
                muted("Check for a misread number.")
            }
            Status.NO_SOLUTION -> {
                status("⚠ No answer fits", false)
                text("No real value of $t works" + (r.eq?.let { " in ${Expr.show(it)}" } ?: "") + ".")
                if (noSolutionNote.isNotEmpty()) muted(noSolutionNote)
            }
        }
    }

    fun stepsOf(steps: List<Step>, name: (String) -> String, unit: (String) -> String) {
        for (s in steps) {
            if (s.system != null) { step("solved these together", s.system.joinToString("\n") { Expr.show(it) }); continue }
            val re = s.rearranged?.takeIf { it !is Node.Num }
            val sign = if (abs(s.value - Math.round(s.value)) < 1e-9 * kotlin.math.max(1.0, abs(s.value))) "=" else "≈"
            val body = if (re != null) "${name(s.v)} = ${if (s.plusMinus && s.value < 0) "−" else ""}${Expr.show(re)} $sign ${Expr.fmt(s.value)}${unit(s.v)}"
            else "${name(s.v)} = ${Expr.fmt(s.value)}${unit(s.v)}"
            step("from ${Expr.show(s.eq)}", body)
        }
    }

    private fun abs(x: Double) = kotlin.math.abs(x)
}

/** A simple wrapping row for chips. */
class FlowRow(ctx: Context) : ViewGroup(ctx) {
    private val gap = ctx.dp(6)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: Int.MAX_VALUE
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lpw = c.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
            c.measure(if (lpw > 0) MeasureSpec.makeMeasureSpec(lpw, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = kotlin.math.max(rowH, c.measuredHeight)
        }
        setMeasuredDimension(if (maxW == Int.MAX_VALUE) x else maxW, y + rowH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = kotlin.math.max(rowH, c.measuredHeight)
        }
    }
}
