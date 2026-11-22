package app.sketchsolve.ui.modes

import android.app.AlertDialog
import android.view.View
import android.widget.CheckBox
import android.widget.ScrollView
import app.sketchsolve.MainActivity
import app.sketchsolve.llm.*
import app.sketchsolve.math.*
import app.sketchsolve.ui.*
import app.sketchsolve.word.*
import org.json.JSONArray
import org.json.JSONObject

/** Say or type a question; get it written out and solved on the page. */
class AskMode(app: MainActivity) : Mode(app) {
    override val id = "ask"
    override val title = "Ask"
    override val padTitle = "NOTEPAD"
    override val tools = listOf(
        ToolSpec("talk", "🎤", "Hold to talk", Ink.FIND, hold = true),
        ToolSpec("type", "⌨", "Type", Ink.PENCIL),
        ToolSpec("clearpage", "✕", "New page", 0xFF9B3434.toInt()))

    private val working by lazy { WorkingView(app) }
    private val scroll by lazy { ScrollView(app).apply { addView(working); isFillViewport = true } }
    override fun view(): View = scroll

    private val history = ArrayList<String>()
    private var current: WorkingView.Page = WorkingView.Page.Empty
    private var listening = false
    private var wifiOnly = true
    private val downloads = HashMap<String, String>()   // model id → progress text
    @Volatile private var cancelDownload = false

    override fun onTool(tool: String) {
        when (tool) {
            "type" -> askText("Ask a question", "e.g. What is 15% of 80?") { if (it.isNotBlank()) ask(it) }
            "clearpage" -> show(WorkingView.Page.Empty)
            "talk" -> app.toast("Hold the button down while you talk.")
        }
    }

    override fun onToolHold(tool: String, down: Boolean) {
        if (tool != "talk") return
        val speech = app.speech
        if (speech == null) { if (down) needSpeechModel(); return }
        if (down) {
            listening = true
            show(WorkingView.Page.Busy("…", "Listening — let go when you've finished."))
            speech.start()
        } else if (listening) {
            listening = false
            show(WorkingView.Page.Busy("…", "Writing down what you said…"))
            speech.stop { text ->
                app.main.post {
                    if (text.isBlank()) show(WorkingView.Page.Failed("(nothing heard)", "I didn't catch that. Hold the button and speak a little closer."))
                    else ask(text)
                }
            }
        }
    }

    private fun needSpeechModel() {
        app.toast("Download the speech model first (on the notepad).")
        refresh()
    }

    private fun show(p: WorkingView.Page) { current = p; working.page = p; scroll.scrollTo(0, 0); refresh() }

    fun ask(question: String) {
        if (history.lastOrNull() != question) history += question
        if (history.size > 30) history.removeAt(0)
        app.scheduleSave()
        val spoken = SpokenMath.interpret(question)
        when (spoken.kind) {
            SpokenMath.Kind.EXPRESSION, SpokenMath.Kind.EQUATION -> show(solveMath(question, spoken))
            SpokenMath.Kind.WORD_PROBLEM -> solveWord(question)
        }
    }

    private fun solveMath(question: String, s: SpokenMath.Result): WorkingView.Page {
        val node = Expr.tryParse(s.text) ?: return WorkingView.Page.Simple(question, s.text, null, "I couldn't turn that into maths.")
        val deg = app.settings.deg
        return if (node is Node.Eq) {
            val vs = Expr.vars(node).toList()
            if (vs.isEmpty()) WorkingView.Page.Simple(question, s.text, null, if (Expr.evaluate(node.lhs, deg = deg) == Expr.evaluate(node.rhs, deg = deg)) null else "Those two sides aren't equal.")
            else WorkingView.Page.Simple(question, s.text, Solver.solveFor(listOf(LabeledEq(node)), emptyMap(), vs[0], deg = deg), null)
        } else {
            WorkingView.Page.Simple(question, s.text, Solver.solveFor(listOf(LabeledEq(Node.Eq(Node.Var("__Q"), node))), emptyMap(), "__Q", deg = deg), null)
        }
    }

    private fun solveWord(question: String) {
        val model = app.gemma()
        if (model == null) {
            show(WorkingView.Page.Failed(question, "Word problems need the on-device AI model. Download it on the notepad (one time, about 2.6 GB). After that everything works offline."))
            return
        }
        show(WorkingView.Page.Busy(question, "Reading the problem…"))
        val lib = app.library
        val deg = app.settings.deg
        app.analyze(id, {
            WordPipeline(model) { lib }.solve(question, deg) { status -> app.main.post { if (current is WorkingView.Page.Busy) show(WorkingView.Page.Busy(question, status)) } }
                ?.let { WorkingView.Page.Word(question, it) as WorkingView.Page }
                ?: WorkingView.Page.Failed(question, "I couldn't set this problem up. Try saying it another way, or type it.")
        }) { page -> show(page) }
    }

    override fun refresh() {
        if (app.mode !== this) return
        val pad = app.pad
        pad.clear()
        val c = current
        if (c is WorkingView.Page.Word) {
            val a = c.answer
            pad.heading("How sure?")
            when (a.confidence) {
                Confidence.HIGH -> pad.status("✓ Checked", true)
                Confidence.MEDIUM -> pad.status("Probably right — please check", false)
                Confidence.LOW -> pad.status("Please check the setup", false)
            }
            pad.muted("The AI only sets the problem up. SketchSolve checks every number against your question, checks the units, and does the maths itself.")
            for ((alt, value) in a.alternatives) {
                pad.text("Another reading gives ${a.setup.ask} = ${Expr.fmt(value)}.")
                pad.button("Use that reading instead") { show(WorkingView.Page.Word(c.question, WordSolver.check(c.question, alt, app.settings.deg))) }
            }
            pad.button("✎ Edit the equations") { editEquations(c) }
        }
        pad.heading("On-device models")
        val mm = app.models
        for (m in mm.wanted()) {
            val st = downloads[m.id]
            when {
                mm.isReady(m) -> pad.text("✓ ${m.title} — ${m.purpose}")
                st != null -> { pad.text("⇣ ${m.title}: $st"); pad.button("Cancel") { cancelDownload = true } }
                else -> pad.muted("• ${m.title} — ${m.purpose} (${size(m.bytes)})")
            }
        }
        val missing = mm.wanted().filter { !mm.isReady(it) && downloads[it.id] == null }
        if (missing.isNotEmpty()) {
            if (mm.ramGb() > 0.5 && mm.ramGb() < ModelManager.GEMMA_E2B.minRamGb - 0.5) pad.warn("This device has ${"%.1f".format(mm.ramGb())} GB of memory. The word-problem model works best with 6 GB or more, so it may be slow here.")
            pad.add(CheckBox(app).apply { text = "Only download on Wi-Fi"; isChecked = wifiOnly; setOnCheckedChangeListener { _, v -> wifiOnly = v } })
            pad.button("⇣ Download (${size(missing.sumOf { it.bytes })})") { download(missing) }
            pad.muted("A one-time download. After this the app works with no internet connection.")
        }
        pad.heading("My formula library")
        val lib = app.library
        pad.muted(if (app.libraryName == null) "Pick a folder of formula packs (plain .txt files) to teach the app new kinds of maths." else "Folder: ${app.libraryName} — ${lib.size} formulas from ${lib.map { it.pack }.distinct().size} packs.")
        app.libraryProblems.take(5).forEach { pad.warn("${it.file} line ${it.line}: ${it.message}") }
        pad.button(if (app.libraryName == null) "Choose a folder…" else "Change folder…") { app.pickLibraryFolder() }
        if (app.libraryName != null) pad.button("Reload the library") { app.loadLibrary() }
        if (history.isNotEmpty()) {
            pad.heading("Earlier questions")
            for (q in history.reversed().take(8)) pad.button(if (q.length > 60) q.take(57) + "…" else q) { ask(q) }
        }
    }

    private fun size(b: Long) = if (b > 1e9) "%.1f GB".format(b / 1e9) else "%d MB".format(b / 1_000_000)

    private fun download(list: List<ModelSpec>) {
        val mm = app.models
        if (wifiOnly && !mm.onWifi()) { app.toast("Connect to Wi-Fi, or untick “Only download on Wi-Fi”."); return }
        cancelDownload = false
        Thread {
            for (m in list) {
                try {
                    mm.download(m, { done, total -> app.main.post { downloads[m.id] = "${(100 * done / total)}% of ${size(total)}"; refresh() } }, { cancelDownload })
                    app.main.post { downloads.remove(m.id); refresh() }
                } catch (e: Exception) {
                    app.main.post { downloads.remove(m.id); if (e !is ModelManager.Cancelled) app.toast(e.message ?: "Download failed"); refresh() }
                    return@Thread
                }
            }
            app.main.post { app.modelsChanged(); refresh() }
        }.start()
    }

    private fun editEquations(c: WorkingView.Page.Word) {
        askText("Equations (one per line, use ; between)", "v_A*t + v_B*t = d", c.answer.setup.equations.joinToString("; ")) { text ->
            val eqs = text.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (eqs.isNotEmpty()) show(WorkingView.Page.Word(c.question, WordSolver.check(c.question, c.answer.setup.copy(equations = eqs), app.settings.deg)))
        }
    }

    override fun save(): JSONObject = JSONObject().put("history", JSONArray(history)).put("wifiOnly", wifiOnly)
    override fun load(o: JSONObject) {
        o.optJSONArray("history")?.let { a -> for (i in 0 until a.length()) history += a.getString(i) }
        wifiOnly = o.optBoolean("wifiOnly", true)
    }
}
