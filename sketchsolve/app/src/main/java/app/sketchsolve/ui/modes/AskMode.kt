package app.sketchsolve.ui.modes

import android.app.AlertDialog
import android.view.View
import android.widget.CheckBox
import android.widget.ProgressBar
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
        if (speech == null) {
            if (!down) return
            when {
                app.speechLoading -> app.toast("The speech model is still loading. Try again in a moment.")
                app.models.speechReady() != null -> app.toast("Speech couldn't start on this phone. You can still type with ⌨.")
                else -> needSpeechModel()
            }
            return
        }
        if (down) {
            try { speech.start() } catch (e: Exception) { app.toast("Couldn't use the microphone: ${e.message}"); return }
            listening = true
            show(WorkingView.Page.Busy("…", "Listening — let go when you've finished."))
        } else if (listening) {
            listening = false
            val started = System.currentTimeMillis()
            var done = false
            show(WorkingView.Page.Busy("…", "Writing down what you said…"))
            // show it's working: count the seconds
            app.main.postDelayed(object : Runnable {
                override fun run() {
                    if (done || current !is WorkingView.Page.Busy) return
                    show(WorkingView.Page.Busy("…", "Writing down what you said… ${(System.currentTimeMillis() - started) / 1000}s"))
                    app.main.postDelayed(this, 1000)
                }
            }, 1000)
            speech.stop { text ->
                app.main.post {
                    done = true
                    if (text.isBlank()) show(WorkingView.Page.Failed("(nothing heard)", "I didn't catch that. Hold 🎤 the whole time you talk, and speak a little closer."))
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
        app.settingsPanel?.invoke()
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
        val mm = app.models
        if (needsSetup()) {
            // until the models are in place, the notepad is where they're set up
            pad.heading("On-device models")
            renderModels(pad)
        } else if (c !is WorkingView.Page.Word) {
            pad.heading("Ask anything")
            pad.text("Hold 🎤 and say the problem, or tap ⌨ to type it.")
            pad.muted("e.g. “A train leaves at 60 mph…”, “What is 15% of 80?”, “Solve x squared minus 4 equals 0”.")
            pad.muted("Models and the formula library are under ⚙ Settings.")
        }
        if (history.isNotEmpty()) {
            pad.heading("Earlier questions")
            for (q in history.reversed().take(8)) pad.button(if (q.length > 60) q.take(57) + "…" else q) { ask(q) }
        }
    }


    /** True while a model is missing or still downloading. */
    fun needsSetup(): Boolean {
        val mm = app.models
        return mm.languageModel() == null || mm.speechReady() == null ||
            mm.wanted().any { mm.progress(it).state !in setOf(ModelManager.State.NONE, ModelManager.State.READY) } || importing != null
    }

    /** The models' status, downloads and choices: on the notepad during setup, and in ⚙ Settings. */
    fun renderModels(pad: app.sketchsolve.ui.Pad) {
        val mm = app.models
        var active = false
        for (m in mm.wanted()) {
            val p = mm.progress(m)
            when (p.state) {
                ModelManager.State.READY -> pad.text("✓ ${m.title} — ${m.purpose}")
                ModelManager.State.NONE -> pad.muted("• ${m.title} — ${m.purpose} (${size(m.bytes)})")
                ModelManager.State.FAILED -> { pad.warn("${m.title}: ${p.message}"); pad.button("Try again") { mm.clearFailure(m); startDownload(listOf(m)) } }
                else -> {
                    active = true
                    val pct = if (p.total > 0) (100 * p.done / p.total).toInt() else 0
                    pad.text("⇣ ${m.title}: ${if (p.state == ModelManager.State.VERIFYING) p.message else "$pct% — ${size(p.done)} of ${size(p.total)}"}")
                    if (p.state != ModelManager.State.RUNNING && p.message.isNotEmpty() && p.state != ModelManager.State.VERIFYING) pad.muted(p.message)
                    if (p.needsOk) {
                        pad.button("Allow the download…") { mm.play.askToContinue(app) }
                        if (asked.add(m.id)) mm.play.askToContinue(app)
                    }
                    pad.add(ProgressBar(app, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = if (p.total > 0) (1000 * p.done / p.total).toInt() else 0; isIndeterminate = p.state == ModelManager.State.VERIFYING })
                    if (p.state != ModelManager.State.VERIFYING) pad.button("Cancel") { mm.cancel(m); refresh() }
                }
            }
        }
        if (active) {
            pad.muted(if (mm.wanted().any { mm.viaPlay(it) }) "Downloading from Google Play. It carries on if you leave the app." else "The download carries on if you leave the app or the screen turns off. Progress is also in the notification bar.")
            watchDownloads()
        }
        val d = mm.device()
        pad.muted("This device: ${d.name} · ${if (d.ramGb > 0.5) "%.0f GB memory · ".format(d.ramGb) else ""}${"%.0f".format(d.freeGb)} GB free")
        pad.button("Choose different models…") { app.showModelSetup() }
        networkProblem?.let { pad.warn(it) }
        importing?.let { pad.text("⇣ Importing: $it") }
        val missing = mm.wanted().filter { mm.progress(it).state == ModelManager.State.NONE }
        if (missing.isNotEmpty()) {
            pad.add(CheckBox(app).apply { text = "Only download on Wi-Fi"; isChecked = wifiOnly; setOnCheckedChangeListener { _, v -> wifiOnly = v; app.scheduleSave() } })
            pad.button("⇣ Download (${size(missing.sumOf { it.bytes })})") { startDownload(missing) }
            pad.muted("A one-time download. After this the app works with no internet connection.")
        }
        if (mm.wanted().any { !mm.isReady(it) }) {
            pad.button("Downloaded the files yourself? Import…") { app.pickModelFile() }
            pad.button("How to download them yourself") { app.showTutorial("manual") }
        }
    }

    /** The formula library folder (in ⚙ Settings). */
    fun renderLibrary(pad: app.sketchsolve.ui.Pad) {
        val lib = app.library
        pad.muted(if (app.libraryName == null) "Pick a folder of formula packs (plain .txt files) to teach the app new kinds of maths." else "Folder: ${app.libraryName} — ${lib.size} formulas from ${lib.map { it.pack }.distinct().size} packs.")
        app.libraryProblems.take(5).forEach { pad.warn("${it.file} line ${it.line}: ${it.message}") }
        pad.button(if (app.libraryName == null) "Choose a folder…" else "Change folder…") { app.pickLibraryFolder() }
        if (app.libraryName != null) pad.button("Reload the library") { app.loadLibrary() }
    }

    private fun size(b: Long) = if (b > 1e9) "%.1f GB".format(b / 1e9) else "%d MB".format(b / 1_000_000)

    private var networkProblem: String? = null
    /** Models we've already shown Google Play's "allow download?" prompt for. */
    private val asked = HashSet<String>()
    private var importing: String? = null

    fun startDownload(list: List<ModelSpec>) {
        val mm = app.models
        // check the phone can find the download site first, so a blocked network is explained, not silent
        Thread {
            // installed from Google Play: Play delivers the models it carries for this phone
            val fromPlay = list.filter { mm.play.offers(it) }
            val fromWeb = list - fromPlay.toSet()
            val problem = if (fromWeb.isEmpty()) null else mm.canReachDownloadSite()
            app.main.post {
                networkProblem = problem
                for (m in fromPlay) mm.enqueueFromPlay(m)?.let { app.toast(it) }
                if (problem != null) { refresh(); app.showTutorial("manual"); return@post }
                for (m in fromWeb) {
                    val err = mm.enqueue(m, wifiOnly)
                    if (err != null) { app.toast(err); break }
                }
                if (fromWeb.isNotEmpty() && wifiOnly && !mm.onWifi()) app.toast("It will start when you're on Wi-Fi (or untick “Only download on Wi-Fi”).")
                refresh()
            }
        }.start()
    }

    /** Install a model file picked from the phone's storage. */
    fun importModel(uri: android.net.Uri) {
        importing = "Starting…"; refresh()
        Thread {
            try {
                val m = app.models.importFile(uri) { done, total -> app.main.post { importing = "${100 * done / total}% checked and copied"; refresh() } }
                app.main.post { importing = null; app.modelsChanged(); app.toast("${m.title} is installed."); refresh() }
            } catch (e: Exception) {
                app.main.post { importing = null; AlertDialog.Builder(app).setTitle("Couldn't import that file").setMessage(e.message).setPositiveButton("OK", null).show(); refresh() }
            }
        }.start()
    }

    /** Pick up downloads still running (e.g. after the app was closed). */
    fun resumeWatching() { if (app.models.wanted().any { app.models.progress(it).state !in setOf(ModelManager.State.NONE, ModelManager.State.READY, ModelManager.State.FAILED) }) watchDownloads() }

    private var watching = false
    /** While a download runs: refresh the notepad every second and check finished files. */
    private fun watchDownloads() {
        if (watching) return
        watching = true
        app.main.postDelayed(object : Runnable {
            override fun run() {
                val mm = app.models
                val pending = mm.wanted().filter { mm.progress(it).state in setOf(ModelManager.State.QUEUED, ModelManager.State.RUNNING, ModelManager.State.PAUSED, ModelManager.State.VERIFYING) }
                for (m in pending) if (mm.progress(m).state == ModelManager.State.VERIFYING) Thread { if (mm.finishIfDone(m)) app.main.post { app.modelsChanged(); app.toast("${m.title} is ready."); refresh() } }.start()
                refresh()
                if (pending.isEmpty()) { watching = false; return }
                app.main.postDelayed(this, 1000)
            }
        }, 1000)
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
