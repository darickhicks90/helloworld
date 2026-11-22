package app.sketchsolve

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import app.sketchsolve.ink.Pt
import app.sketchsolve.ink.Stroke
import app.sketchsolve.recog.Recognizer
import app.sketchsolve.ui.*
import app.sketchsolve.ui.modes.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * SketchSolve: a sheet of graph paper on a desk. Write maths, draw shapes or
 * circuit schematics, and circle what you want to know.
 */
class MainActivity : Activity() {
    lateinit var paperFrame: FrameLayout
    lateinit var pad: Pad
    lateinit var padTitle: TextView
    private lateinit var tray: LinearLayout
    private val tabs = ArrayList<StickyTab>()
    lateinit var modes: List<Mode>
    var mode: Mode? = null
        private set
    val settings = Settings()

    private val worker = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    private var saveScheduled = false

    class Settings { var deg = true; var learn = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density

        val root = FrameLayout(this).apply { background = DeskDrawable(d) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(10), dp(8)) }
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // tool tray (a groove in the desk)
        tray = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        val trayScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x40000000); setStroke(dp(1), 0x30FFFFFF) }
            addView(tray)
        }
        row.addView(trayScroll, LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(6) })

        // paper column: sticky-note tabs over a pad of graph paper
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(18), 0, 0, 0) }
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }
        col.addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        paperFrame = FrameLayout(this)
        col.addView(paperFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = -dp(4) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // the yellow legal pad on the right
        val padCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = LegalPadDrawable(d) }
        padTitle = TextView(this).apply {
            setTextColor(0xFFFFEFD9.toInt()); textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
            setPadding(dp(30), dp(1), dp(8), 0)
        }
        padCol.addView(padTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        val padBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(6), dp(12), dp(20)) }
        val padScroll = ScrollView(this).apply { addView(padBox) }
        padCol.addView(padScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val padW = minOf(300, (resources.configuration.screenWidthDp * 0.31).toInt()).coerceAtLeast(220)
        row.addView(padCol, LinearLayout.LayoutParams(dp(padW), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(12); topMargin = dp(10) })
        pad = Pad(this, padBox)

        setContentView(root)
        immersive()

        loadSamples()
        modelsChanged()
        loadLibrary()
        modes = listOf(AskMode(this), EquationsMode(this), ShapesMode(this), CircuitsMode(this), FormulasMode(this), TeachMode(this))
        val colors = listOf(0xFFFF9F8A.toInt(), 0xFFFFE27A.toInt(), 0xFFFFB3CC.toInt(), 0xFF9EE6D8.toInt(), 0xFFB3CEFF.toInt(), 0xFFFFC98A.toInt())
        val tilts = listOf(1.5f, -2f, 1.5f, -1f, 2f, -1.5f)
        modes.forEachIndexed { i, m ->
            val t = StickyTab(this, m.title, colors[i], tilts[i])
            t.setOnClickListener { select(m) }
            tabs += t
            tabRow.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = -dp(2) })
        }
        val state = loadState()
        for (m in modes) state?.optJSONObject(m.id)?.let { m.load(it) }
        state?.optJSONObject("settings")?.let { settings.deg = it.optBoolean("deg", true); settings.learn = it.optBoolean("learn", true) }
        val start = modes.firstOrNull { it.id == state?.optString("tab") } ?: modes[0]
        select(start)
        for (m in modes) if (m !== start) m.refresh()
    }

    private fun immersive() {
        window.setDecorFitsSystemWindowsCompat()
    }

    private fun android.view.Window.setDecorFitsSystemWindowsCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    fun select(m: Mode) {
        mode = m
        tabs.forEachIndexed { i, t -> t.selectedTab = modes[i] === m }
        paperFrame.removeAllViews()
        paperFrame.addView(m.view(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildTray(m)
        padTitle.text = m.padTitle
        m.refresh()
        scheduleSave()
    }

    fun buildTray(m: Mode) {
        tray.removeAllViews()
        for (spec in m.tools) {
            val tok = ToolToken(this, spec.id, spec.icon, spec.caption, spec.tint)
            tok.active = m.isActiveTool(spec.id)
            if (spec.hold) {
                tok.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> { tok.active = true; v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); requestMicThen { m.onToolHold(spec.id, true) } }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { tok.active = false; m.onToolHold(spec.id, false) }
                    }
                    true
                }
            } else tok.setOnClickListener { m.onTool(spec.id); buildTray(m) }
            tray.addView(tok)
        }
    }

    /* -------- on-device models -------- */
    val models by lazy { app.sketchsolve.llm.ModelManager(this) }
    private var gemmaModel: app.sketchsolve.llm.GemmaModel? = null
    var speech: app.sketchsolve.llm.SpeechInput? = null
        private set

    /** Gemma, if its file is downloaded (loaded lazily on first use). */
    /** Tests can put a stand-in model here. */
    var testModel: app.sketchsolve.llm.LanguageModel? = null

    fun gemma(): app.sketchsolve.llm.LanguageModel? {
        testModel?.let { return it }
        gemmaModel?.let { return it }
        val spec = app.sketchsolve.llm.ModelManager.GEMMA_E2B
        if (!models.isReady(spec)) return null
        return app.sketchsolve.llm.GemmaModel(models.file(spec), cacheDir).also { gemmaModel = it }
    }

    fun modelsChanged() {
        speech?.close(); speech = null
        val ws = models.speechModel()
        if (models.isReady(ws)) speech = app.sketchsolve.llm.WhisperSpeech.create(this, models.file(ws))
    }

    private var pendingMic: (() -> Unit)? = null
    fun requestMicThen(action: () -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { action(); return }
        pendingMic = action
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) toast("Push to talk needs the microphone.")
        pendingMic = null   // the button was released while the dialog was up; press again to talk
    }

    /* -------- formula library folder -------- */
    var library: List<app.sketchsolve.word.LibraryEntry> = emptyList()
        private set
    var libraryProblems: List<app.sketchsolve.word.PackProblem> = emptyList()
        private set
    var libraryName: String? = null
        private set

    fun pickLibraryFolder() {
        try { startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE), 11) }
        catch (e: Exception) { toast("This device can't pick folders.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 11 && resultCode == RESULT_OK && uri != null) {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            getSharedPreferences("sketchsolve", MODE_PRIVATE).edit().putString("libraryUri", uri.toString()).apply()
            loadLibrary()
            mode?.refresh()
        }
    }

    /** Read every .txt / .sspack file in the chosen folder as a formula pack. */
    fun loadLibrary() {
        val s = getSharedPreferences("sketchsolve", MODE_PRIVATE).getString("libraryUri", null) ?: return
        val tree = android.net.Uri.parse(s)
        val entries = ArrayList<app.sketchsolve.word.LibraryEntry>()
        val problems = ArrayList<app.sketchsolve.word.PackProblem>()
        try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(tree)
            libraryName = docId.substringAfterLast(':').ifEmpty { docId }
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            contentResolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    if (!name.endsWith(".txt", true) && !name.endsWith(".sspack", true)) continue
                    val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    val text = contentResolver.openInputStream(fileUri)?.bufferedReader()?.use { it.readText() } ?: continue
                    entries += app.sketchsolve.word.Library.parsePack(text, name, problems)
                }
            }
        } catch (e: Exception) {
            problems += app.sketchsolve.word.PackProblem(libraryName ?: "folder", 0, "Couldn't read the folder: ${e.message}")
        }
        library = entries; libraryProblems = problems
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /* -------- background analysis: newest request wins -------- */
    private val generation = HashMap<String, Int>()
    fun <T> analyze(key: String, work: () -> T, apply: (T) -> Unit) {
        val gen = (generation[key] ?: 0) + 1
        generation[key] = gen
        worker.execute {
            val res = try { work() } catch (e: Exception) { android.util.Log.e("SketchSolve", "analysis failed", e); null }
            main.post { if (generation[key] == gen && res != null) apply(res) }
        }
    }

    /* -------- saving -------- */
    fun scheduleSave() {
        if (saveScheduled) return
        saveScheduled = true
        main.postDelayed({ saveScheduled = false; saveState() }, 600)
    }

    private fun saveState() {
        val o = JSONObject()
        o.put("tab", mode?.id)
        o.put("settings", JSONObject().put("deg", settings.deg).put("learn", settings.learn))
        for (m in modes) o.put(m.id, m.save())
        try { File(filesDir, "state.json").writeText(o.toString()) } catch (e: Exception) { }
    }

    private fun loadState(): JSONObject? = try { JSONObject(File(filesDir, "state.json").readText()) } catch (e: Exception) { null }

    fun saveSamples() {
        val arr = JSONArray()
        for ((label, strokes) in Recognizer.userSamples) arr.put(JSONObject().put("label", label).put("strokes", strokesToJson(strokes.map { Stroke(0, "", it) })))
        try { File(filesDir, "samples.json").writeText(arr.toString()) } catch (e: Exception) { }
    }

    private fun loadSamples() {
        try {
            val arr = JSONArray(File(filesDir, "samples.json").readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                Recognizer.addUserSample(o.getString("label"), strokesFromJson(o.getJSONArray("strokes")).map { it.pts })
            }
        } catch (e: Exception) { }
    }

    override fun onPause() { super.onPause(); saveState() }

    companion object {
        fun strokesToJson(list: List<Stroke>): JSONArray {
            val arr = JSONArray()
            for (s in list) {
                val pts = JSONArray()
                for (p in s.pts) { pts.put(Math.round(p.x * 10) / 10.0); pts.put(Math.round(p.y * 10) / 10.0) }
                arr.put(JSONObject().put("id", s.id).put("kind", s.kind).put("pts", pts))
            }
            return arr
        }

        fun strokesFromJson(arr: JSONArray): List<Stroke> = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("pts")
            Stroke(o.optInt("id", i), o.optString("kind", "ink"), (0 until p.length() / 2).map { Pt(p.getDouble(2 * it), p.getDouble(2 * it + 1)) })
        }
    }
}
