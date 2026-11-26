package app.gemmadesk.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Colours and small helpers for building the screens in code. */
object C {
    const val INK = 0xFF1F2430.toInt()
    const val MUTED = 0xFF6B7489.toInt()
    const val LINE = 0xFFE3E7EF.toInt()
    const val BG = 0xFFF6F7FA.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val ACCENT = 0xFF3D5AFE.toInt()
    const val ACCENT_SOFT = 0xFFE8ECFF.toInt()
    const val NAVY = 0xFF1E2A4A.toInt()
    const val GOOD = 0xFF1B8A5A.toInt()
    const val BAD = 0xFFC0392B.toInt()
    const val WARN = 0xFFB26A00.toInt()
    const val WARN_SOFT = 0xFFFFF4E0.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.label(text: CharSequence, size: Float = 15f, color: Int = C.INK, bold: Boolean = false) = TextView(this).apply {
    this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/** A filled (primary) or outlined button. */
fun Context.button(text: String, primary: Boolean = false, small: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 13f else 15f)
    setTextColor(if (primary) Color.WHITE else C.ACCENT)
    typeface = Typeface.DEFAULT_BOLD
    minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
    setPadding(dp(if (small) 12 else 18), dp(if (small) 6 else 10), dp(if (small) 12 else 18), dp(if (small) 6 else 10))
    val bg = if (primary) rounded(C.ACCENT, dpf(10f)) else rounded(C.CARD, dpf(10f), dp(1), 0xFFC7CFF5.toInt())
    background = RippleDrawable(ColorStateList.valueOf(0x22000000), bg, null)
    stateListAnimator = null
    setOnClickListener { onClick() }
}

fun Context.field(hint: String, text: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) = EditText(this).apply {
    this.hint = hint; setText(text); inputType = type; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTextColor(C.INK); setHintTextColor(0xFF9AA3B5.toInt())
    background = rounded(C.CARD, dpf(10f), dp(1), C.LINE)
    setPadding(dp(12), dp(10), dp(12), dp(10))
}

fun Context.column(pad: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)) }
fun Context.row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }

fun Context.card() = column(14).apply { background = rounded(C.CARD, dpf(14f), dp(1), C.LINE); elevation = dpf(1f) }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun LinearLayout.add(v: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, top: Int = 0, left: Int = 0, right: Int = 0): View {
    addView(v, LinearLayout.LayoutParams(w, h, weight).apply { topMargin = context.dp(top); leftMargin = context.dp(left); rightMargin = context.dp(right) })
    return v
}
fun Context.space(h: Int) = View(this).apply { minimumHeight = dp(h) }

fun Context.confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(yes) { _, _ -> onYes() }.setNegativeButton("Cancel", null).show()
}

/** A row of choices where one is selected (like a segmented control). */
class Segments(ctx: Context, private val names: List<String>, selected: Int, private val dark: Boolean = false, private val onPick: (Int) -> Unit) : LinearLayout(ctx) {
    private val views = ArrayList<TextView>()
    var selected = selected
        private set
    init {
        orientation = HORIZONTAL
        background = rounded(0xFFE9ECF3.toInt(), ctx.dpf(10f))
        setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        names.forEachIndexed { i, n ->
            val t = ctx.label(n, 14f, C.INK, true).apply { gravity = Gravity.CENTER; setPadding(ctx.dp(14), ctx.dp(7), ctx.dp(14), ctx.dp(7)); setOnClickListener { select(i); onPick(i) } }
            views += t; addView(t, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        select(selected)
    }
    fun select(i: Int) {
        selected = i
        views.forEachIndexed { k, t -> t.background = if (k == i) rounded(C.CARD, context.dpf(8f)) else null; t.setTextColor(if (k == i) C.INK else if (dark) 0xCCFFFFFF.toInt() else C.MUTED) }
    }
}
