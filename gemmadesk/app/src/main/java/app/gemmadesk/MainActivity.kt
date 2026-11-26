package app.gemmadesk

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.gemmadesk.data.Calendar
import app.gemmadesk.data.Ledger
import app.gemmadesk.data.Projects
import app.gemmadesk.llm.GemmaModel
import app.gemmadesk.llm.LanguageModel
import app.gemmadesk.llm.ModelStore
import app.gemmadesk.ui.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    val models by lazy { ModelStore(this) }
    val ledger by lazy { Ledger(File(filesDir, "ledger.json")) }
    val calendar by lazy { Calendar(File(filesDir, "calendar.json")) }
    val projects by lazy { Projects(File(filesDir, "projects")) }
    val library by lazy { app.gemmadesk.data.Library(File(filesDir, "library")) }

    lateinit var everyday: EverydayTab
    lateinit var make: MakeTab
    lateinit var libraryTab: LibraryTab
    private lateinit var content: FrameLayout
    private lateinit var tabs: Segments
    private lateinit var modelChip: TextView
    private lateinit var busy: LinearLayout
    private lateinit var busyText: TextView

    /* ---------- Gemma ---------- */
    private var gemma: GemmaModel? = null
    /** Tests put a stand-in model here. */
    var testModel: LanguageModel? = null

    fun model(): LanguageModel? {
        testModel?.let { return it }
        gemma?.let { return it }
        val spec = models.current() ?: return null
        return GemmaModel(models.file(spec), cacheDir).also { gemma = it }
    }

    fun modelChanged() { gemma?.close(); gemma = null; updateChip() }

    /**
     * Run Gemma work in the background with a "working" banner, then hand the
     * result back on the main thread. Asks for the model first if it's missing.
     */
    fun <T> withModel(status: String, work: (LanguageModel) -> T, done: (T?, Throwable?) -> Unit) {
        val m = model() ?: run { showModelSetup(); return }
        showBusy(status)
        worker.execute {
            val r = runCatching { work(m) }
            main.post { hideBusy(); done(r.getOrNull(), r.exceptionOrNull()) }
        }
    }

    fun showBusy(status: String) { busyText.text = status; busy.visibility = View.VISIBLE }
    fun setBusyText(status: String) = main.post { busyText.text = status }
    fun hideBusy() { busy.visibility = View.GONE }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val root = column().apply { setBackgroundColor(C.BG) }

        // top bar: name, tabs, model status
        val narrow = resources.configuration.screenWidthDp < 600
        val head = column().apply { setBackgroundColor(C.NAVY); setPadding(dp(16), dp(10), dp(12), dp(10)) }
        val bar = row()
        bar.add(label("Gemma Desk", 19f, 0xFFFFFFFF.toInt(), true), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        tabs = Segments(this, listOf("Everyday", "Make", "Library"), 0, dark = true) { show(it) }
        tabs.background = rounded(0x33FFFFFF, dpf(10f))
        bar.add(View(this), 0, 1, 1f)
        if (!narrow) { bar.add(tabs, dp(330)); bar.add(View(this), 0, 1, 1f) }
        modelChip = label("", 12f, 0xFFFFFFFF.toInt(), true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6)); background = rounded(0x33FFFFFF, dpf(14f))
            setOnClickListener { showModelSetup() }
        }
        bar.add(modelChip, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        head.add(bar)
        if (narrow) head.add(tabs, top = 8)
        root.add(head)

        // busy banner
        busy = row().apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(C.ACCENT_SOFT); visibility = View.GONE }
        busy.add(ProgressBar(this).apply { isIndeterminate = true }, dp(22), dp(22))
        busyText = label("", 14f, C.NAVY, true)
        busy.add(busyText, 0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f, left = 10)
        root.add(busy)

        content = FrameLayout(this)
        root.add(content, h = 0, weight = 1f)
        setContentView(root)
        immersive()

        everyday = EverydayTab(this)
        make = MakeTab(this)
        libraryTab = LibraryTab(this)
        show(getSharedPreferences("gemmadesk", MODE_PRIVATE).getInt("tab", 0))
        updateChip()
        Thread { runCatching { app.gemmadesk.data.Reminders.scheduleAll(this) } }.start()
        if (models.current() == null && !getSharedPreferences("gemmadesk", MODE_PRIVATE).getBoolean("setupSeen", false)) main.post { showModelSetup() }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    /** A file shared from another app goes into the library. */
    private fun handleShare(i: Intent?) {
        val uris = when (i?.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> return
        }
        if (uris.isEmpty()) {
            // shared text (e.g. a web page's words) becomes a small document
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val title = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared note ${java.time.LocalDate.now()}"
            runCatching { library.addText(title, "text", listOf(text)) }.onSuccess { toast("Added “$title” to the library.") }
            show(2); return
        }
        show(2)
        libraryTab.add(uris)
        setIntent(Intent())
    }

    fun show(i: Int) {
        tabs.select(i)
        content.removeAllViews()
        content.addView(when (i) { 0 -> everyday; 1 -> make; else -> libraryTab })
        when (i) { 0 -> everyday.refresh(); 1 -> make.refresh(); else -> { libraryTab.refresh(); Thread { library.warm() }.start() } }
        getSharedPreferences("gemmadesk", MODE_PRIVATE).edit().putInt("tab", i).apply()
    }

    private fun updateChip() {
        val m = models.current()
        modelChip.text = if (m == null) "⚠ Set up Gemma" else "✦ ${m.title}"
    }

    fun showModelSetup() {
        getSharedPreferences("gemmadesk", MODE_PRIVATE).edit().putBoolean("setupSeen", true).apply()
        ModelSetup(this).show()
    }

    /* ---------- files from other apps ---------- */
    private var pickCallback: ((Uri) -> Unit)? = null
    fun pickFile(type: String = "*/*", then: (Uri) -> Unit) {
        pickCallback = then
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type), 21) }
        catch (e: Exception) { toast("This phone can't pick files.") }
    }
    private var pickManyCallback: ((List<Uri>) -> Unit)? = null
    /** Pick one or more documents for the library. */
    fun pickFiles(then: (List<Uri>) -> Unit) {
        pickManyCallback = then
        val types = arrayOf("application/pdf", "text/*", "application/epub+zip", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, types).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 22) }
        catch (e: Exception) { toast("This phone can't pick files.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 21) { if (resultCode == RESULT_OK && uri != null) pickCallback?.invoke(uri); pickCallback = null }
        if (requestCode == 22 && resultCode == RESULT_OK && data != null) {
            val list = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
            pickManyCallback?.invoke(list)
        }
    }

    /** Share a file made in cache/export with another app (email, Drive, Files…). */
    fun share(f: File, mime: String, subject: String) {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, app.gemmadesk.data.ExportProvider.uri(f))
            .putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, subject))
    }

    /** Share several files at once (e.g. a mockup picture and its brief), with a message. */
    fun shareMany(files: List<File>, text: String, subject: String) {
        val uris = ArrayList(files.map { app.gemmadesk.data.ExportProvider.uri(it) })
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri(subject, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        startActivity(Intent.createChooser(i, subject))
    }

    /* ---------- full screen ---------- */
    fun immersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }

    override fun onResume() {
        super.onResume()
        // a model download may have finished while the app was closed
        worker.execute { for (m in ModelStore.ALL) if (models.finishIfDone(m)) main.post { modelChanged(); toast("${m.title} is ready.") } }
    }

    override fun onPause() { super.onPause(); if (::make.isInitialized) make.saveNow() }
    override fun onDestroy() { super.onDestroy(); gemma?.close() }
}
