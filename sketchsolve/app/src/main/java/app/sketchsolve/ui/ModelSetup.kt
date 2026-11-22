package app.sketchsolve.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.sketchsolve.MainActivity
import app.sketchsolve.llm.ModelManager
import app.sketchsolve.llm.ModelSpec

/**
 * Model choices laid out for this device: what it has, what each model
 * costs in space and speed, which one we recommend, and which won't fit.
 */
class ModelSetup(private val app: MainActivity, private val onDownload: (ModelSpec, ModelSpec) -> Unit) {
    private val mm = app.models
    private var llm = mm.chosenLanguage
    private var speech = mm.chosenSpeech
    private lateinit var box: LinearLayout
    private var dialog: AlertDialog? = null

    fun show() {
        box = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; setPadding(app.dp(22), app.dp(8), app.dp(22), app.dp(8)) }
        render()
        dialog = AlertDialog.Builder(app)
            .setTitle("Set up the offline AI")
            .setView(ScrollView(app).apply { addView(box) })
            .setPositiveButton("Download") { _, _ -> onDownload(llm, speech) }
            .setNegativeButton("Later", null)
            .create()
        dialog!!.show()
        updateButton()
    }

    private fun gb(b: Long) = if (b >= 1e9) "%.1f GB".format(b / 1e9) else "%d MB".format(b / 1_000_000)

    private fun updateButton() {
        val need = listOf(llm, speech).filter { !mm.isReady(it) }.sumOf { it.bytes }
        val (l, s) = mm.fits()
        val usable = l.firstOrNull { it.model == llm }?.usable == true && s.firstOrNull { it.model == speech }?.usable == true
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = if (need == 0L) "Use these" else "Download ${gb(need)}"
            isEnabled = usable
        }
    }

    private fun render() {
        box.removeAllViews()
        val d = mm.device()
        val (lFits, sFits) = mm.fits()
        text("Your device", 12f, Ink.MUTED, Typeface.DEFAULT_BOLD)
        text(d.name, 17f, Ink.TEXT, Typeface.DEFAULT_BOLD)
        text(listOfNotNull(d.chip, if (d.ramGb > 0.5) "%.1f GB memory".format(d.ramGb) else null, "%.1f GB free space".format(d.freeGb), "Android ${d.android}").joinToString("  ·  "), 13f, Ink.MUTED)
        text("Everything runs on this device. This one download is the only time the app needs the internet.", 13f, Ink.MUTED, top = 6)
        text("WORD PROBLEMS", 12f, Ink.MUTED, Typeface.DEFAULT_BOLD, top = 14)
        for (f in lFits) card(f, f.model == llm) { llm = f.model }
        text("PUSH TO TALK", 12f, Ink.MUTED, Typeface.DEFAULT_BOLD, top = 14)
        for (f in sFits) card(f, f.model == speech) { speech = f.model }
        updateButton()
    }

    private fun text(t: String, size: Float, color: Int, face: Typeface = Typeface.DEFAULT, top: Int = 2) {
        box.addView(TextView(app).apply { text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = face; setPadding(0, app.dp(top), 0, app.dp(2)) })
    }

    private fun card(f: ModelManager.Fit, selected: Boolean, choose: () -> Unit) {
        val c = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(12), app.dp(8), app.dp(12), app.dp(10))
            background = GradientDrawable().apply {
                cornerRadius = app.dp(10f)
                setColor(if (!f.usable) 0xFFF1F1F1.toInt() else if (selected) 0xFFFFF7D6.toInt() else 0xFFFFFFFF.toInt())
                setStroke(app.dp(if (selected) 2 else 1), if (selected) Ink.FIND else 0x33000000)
            }
            alpha = if (f.usable) 1f else 0.6f
            if (f.usable) setOnClickListener { choose(); render() }
        }
        val title = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        title.addView(TextView(app).apply { text = (if (selected) "◉  " else "○  ") + f.model.title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD; setTextColor(Ink.TEXT) })
        if (f.recommended) title.addView(TextView(app).apply {
            text = "RECOMMENDED"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { cornerRadius = app.dp(8f); setColor(Ink.KNOWN) }
            setPadding(app.dp(7), app.dp(2), app.dp(7), app.dp(2))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = app.dp(8) })
        c.addView(title)
        c.addView(TextView(app).apply { text = "${gb(f.model.bytes)} download  ·  ${f.model.purpose}"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setTextColor(Ink.MUTED) })
        c.addView(TextView(app).apply { text = f.note; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setTextColor(if (f.usable) Ink.TEXT else Ink.WARN); setPadding(0, app.dp(3), 0, 0) })
        box.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = app.dp(6) })
    }
}
