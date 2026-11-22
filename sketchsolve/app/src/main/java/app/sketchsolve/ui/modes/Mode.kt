package app.sketchsolve.ui.modes

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import app.sketchsolve.MainActivity
import app.sketchsolve.ink.Stroke
import app.sketchsolve.ui.*
import org.json.JSONObject

data class ToolSpec(val id: String, val icon: String, val caption: String, val tint: Int, val hold: Boolean = false)

/** One tab of the app: what it shows on the paper and on the legal pad. */
abstract class Mode(val app: MainActivity) {
    abstract val id: String
    abstract val title: String
    abstract val padTitle: String
    abstract val tools: List<ToolSpec>
    abstract fun view(): View
    abstract fun refresh()
    open fun isActiveTool(tool: String) = false
    open fun onTool(tool: String) {}
    /** For press-and-hold tools (push to talk). */
    open fun onToolHold(tool: String, down: Boolean) {}
    open fun save(): JSONObject = JSONObject()
    open fun load(o: JSONObject) {}

    fun askText(title: String, hint: String, initial: String = "", number: Boolean = false, onOk: (String) -> Unit) {
        val et = EditText(app).apply {
            setText(initial); this.hint = hint; setSelection(initial.length)
            inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
        }
        val wrap = FrameLayout(app).apply { setPadding(app.dp(20), app.dp(8), app.dp(20), 0); addView(et) }
        AlertDialog.Builder(app).setTitle(title).setView(wrap)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }
}

/** A mode that has its own sheet of paper to draw on. */
abstract class DrawingMode(app: MainActivity) : Mode(app), PaperHost {
    val paper by lazy { PaperView(app).also { it.host = this } }
    override fun view(): View = paper
    override fun isActiveTool(tool: String) = paper.tool == tool

    override fun onTool(tool: String) {
        when (tool) {
            "undo" -> paper.undo()
            "redo" -> paper.redo()
            "clear" -> AlertDialog.Builder(app).setMessage("Clear this page?").setPositiveButton("Clear") { _, _ -> paper.clear() }.setNegativeButton("Keep", null).show()
            else -> paper.tool = tool
        }
    }

    override fun onChanged(paper: PaperView) { refresh(); app.scheduleSave() }

    protected fun editTools() = listOf(
        ToolSpec("erase", "⌫", "Erase", 0xFF8A5A2B.toInt()),
        ToolSpec("undo", "↶", "Undo", 0xFF2E2A26.toInt()),
        ToolSpec("redo", "↷", "Redo", 0xFF2E2A26.toInt()),
        ToolSpec("clear", "✕", "Clear", 0xFF9B3434.toInt()))

    fun strokesJson() = MainActivity.strokesToJson(paper.strokes)
    fun loadStrokes(o: JSONObject) { o.optJSONArray("strokes")?.let { paper.replaceStrokes(MainActivity.strokesFromJson(it)) } }
    fun snapshot(): List<Stroke> = paper.strokes.map { it.copy() }
}
