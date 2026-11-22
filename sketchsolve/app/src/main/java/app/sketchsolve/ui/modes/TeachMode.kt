package app.sketchsolve.ui.modes

import android.app.AlertDialog
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import app.sketchsolve.MainActivity
import app.sketchsolve.ink.Stroke
import app.sketchsolve.recog.Recognizer
import app.sketchsolve.ui.*

/** Teach the recogniser your handwriting; settings; how-to. */
class TeachMode(app: MainActivity) : Mode(app) {
    override val id = "teach"
    override val title = "Teach & help"
    override val padTitle = "HOW TO USE IT"
    override val tools = emptyList<ToolSpec>()
    override val tourPage = "welcome"
    private var root: View? = null
    private lateinit var counts: TextView
    private val labels = (Recognizer.LABELS.filter { it != "?" } + listOf("z", "r", "h", "k", "m", "V", "I", "R", "P", "A", "θ")).distinct()

    private val teachPad by lazy {
        PaperView(app, showPad = false).also {
            it.tool = "pen"
            it.host = object : PaperHost {
                override fun kindFor(tool: String) = "ink"
                override fun onChanged(paper: PaperView) {}
                override fun styleFor(s: Stroke) = InkStyle(Ink.PENCIL, 3.2f)
            }
        }
    }

    override fun view(): View {
        root?.let { return it }
        val frame = FrameLayout(app)
        frame.addView(PaperView(app).apply { tool = "none" })
        val scroll = ScrollView(app)
        val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; setPadding(app.dp(24), app.dp(34), app.dp(20), app.dp(20)) }
        val left = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(app).apply { text = "Write ONE symbol here:"; typeface = Ink.handBold; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Ink.TEXT) })
        val side = if (app.resources.configuration.screenHeightDp < 480) 180 else 230
        left.addView(teachPad, LinearLayout.LayoutParams(app.dp(side), app.dp(side)).apply { topMargin = app.dp(6) })
        row.addView(left)
        val right = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; setPadding(app.dp(20), 0, 0, 0) }
        val pad = Pad(app, right)
        pad.text("It is the symbol:")
        val spinner = Spinner(app).apply { adapter = ArrayAdapter(app, android.R.layout.simple_spinner_dropdown_item, labels) }
        pad.add(spinner)
        val custom = EditText(app).apply { hint = "…or type it (e.g. V)"; isSingleLine = true }
        pad.add(custom)
        pad.button("✓ Save this example") {
            val label = custom.text.toString().trim().ifEmpty { spinner.selectedItem as String }
            if (teachPad.strokes.isEmpty()) { app.toast("Write a symbol in the box first."); return@button }
            Recognizer.addUserSample(label, teachPad.strokes.map { it.pts })
            app.saveSamples()
            teachPad.clear(); custom.setText("")
            updateCounts()
            app.toast("Saved an example of “$label”.")
        }
        pad.button("Clear the box") { teachPad.clear() }
        pad.heading("Settings")
        right.addView(CheckBox(app).apply {
            text = "Angles in degrees (untick for radians)"; isChecked = app.settings.deg
            setOnCheckedChangeListener { _, c -> app.settings.deg = c; app.scheduleSave(); app.modes.forEach { if (it !is TeachMode) it.refresh() } }
        })
        right.addView(CheckBox(app).apply {
            text = "Learn from my corrections"; isChecked = app.settings.learn
            setOnCheckedChangeListener { _, c -> app.settings.learn = c; app.scheduleSave() }
        })
        pad.heading("My examples")
        counts = pad.muted("")
        pad.button("Forget all my examples") {
            AlertDialog.Builder(app).setMessage("Forget every handwriting example you've taught SketchSolve?")
                .setPositiveButton("Forget") { _, _ -> Recognizer.forgetUser(); app.saveSamples(); updateCounts() }.setNegativeButton("Keep", null).show()
        }
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scroll.addView(row)
        frame.addView(scroll)
        updateCounts()
        root = frame
        return frame
    }

    private fun updateCounts() {
        val c = Recognizer.sampleCounts()
        counts.text = if (c.isEmpty()) "No examples yet. A few examples of symbols it gets wrong help a lot." else c.entries.sortedBy { it.key }.joinToString("   ") { "${it.key} × ${it.value}" }
    }

    override fun refresh() {
        if (app.mode !== this) return
        val pad = app.pad
        pad.clear()
        pad.button("▶  Take the guided tour again") { app.showTutorial(null) }
        pad.heading("Equations")
        pad.text("Write neatly, one equation per line. Draw a loop around what you want:")
        pad.muted("• a letter to solve for it\n• a piece like 3 + 4 to work it out\n• a whole equation to solve it\nPut known values on their own lines (R = 4). Several lines are solved together.\nExponents: small and raised. Fractions: a line with numbers above and below. Write 2x for 2 × x.")
        pad.heading("Shapes")
        pad.muted("1  Shape: draw the outline. Shaky lines are fine.\n2  Known: a bracket beside a side (or a small arc in a corner), then its number. Writing ? marks what you want.\n3  Find: circle a side, a corner, or the whole shape for area, perimeter, volume or surface area.\nTap any label to type a value.")
        pad.heading("Circuits")
        pad.muted("1  Draw: wires, resistors (zigzag or box), lamps (circle with X), batteries (long + short plates; long is +), capacitors (two equal plates), sources (circle).\n2  Values: write each number next to its part (ohms, volts, µF).\n3  Find: circle a part for its current, voltage and power, or circle everything for totals.")
        pad.heading("Not enough information?")
        pad.muted("SketchSolve checks whether your values really pin the answer down. If not, it says so and lists which ONE extra value would do it. It also tells you when two answers fit, or when your values contradict each other.")
        pad.heading("Ask by voice")
        pad.muted("On the Ask page, hold the microphone and say your question, then let go. Say equations naturally (“x squared plus three equals twelve”) or whole word problems. Everything runs on this device.")
        pad.heading("Privacy")
        pad.muted("SketchSolve works offline. Your voice, writing and questions never leave the device. The only internet use is the one-time model download.")
        pad.heading("Open-source parts")
        pad.muted("Gemma 4 E2B by Google — Apache License 2.0.\nLiteRT-LM by Google — Apache License 2.0.\nwhisper.cpp and ggml by the ggml authors — MIT License.\nWhisper speech models by OpenAI — MIT License.\nFull license texts are in the app's source repository (THIRD_PARTY_NOTICES.md).")
        pad.heading("Stylus")
        pad.muted("When you use a stylus, touches from your palm are ignored. The eraser end of a stylus erases.")
    }
}
