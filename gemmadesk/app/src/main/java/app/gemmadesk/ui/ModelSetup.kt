package app.gemmadesk.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import app.gemmadesk.MainActivity
import app.gemmadesk.llm.ModelSpec
import app.gemmadesk.llm.ModelStore

/** Choose, download or import the Gemma model, with this phone's details. */
class ModelSetup(private val a: MainActivity) {
    private val ms = a.models
    private val body = a.column(18)
    private var wifiOnly = true
    private var problem: String? = null
    private var importing: String? = null
    private lateinit var dialog: AlertDialog
    private val tick = object : Runnable { override fun run() { if (!dialog.isShowing) return; render(); a.main.postDelayed(this, 1000) } }

    fun show() {
        dialog = AlertDialog.Builder(a).setView(ScrollView(a).apply { addView(body) }).setPositiveButton("Done", null).create()
        render()
        dialog.setOnDismissListener { a.immersive() }
        dialog.show()
        a.main.postDelayed(tick, 1000)
    }

    private fun size(b: Long) = "%.1f GB".format(b / 1e9)

    private fun render() {
        body.removeAllViews()
        body.add(a.label("Gemma on this phone", 20f, C.INK, true))
        body.add(a.label("Gemma runs entirely on your phone: your ledger, calendar and designs never leave it. " +
            "The model is a one-time download; after that no internet is needed.", 14f, C.MUTED), top = 4)
        val ram = ms.ramGb()
        body.add(a.label("This phone: ${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL} · ${"%.0f".format(ram)} GB memory · ${"%.0f".format(ms.freeBytes() / 1e9)} GB free", 13f, C.MUTED), top = 8)
        val rec = ms.recommended()
        for (m in ModelStore.ALL) body.add(card(m, m == rec), top = 12)
        problem?.let { body.add(a.label("⚠ $it", 14f, C.WARN), top = 10) }
        importing?.let { body.add(a.label("⇣ Importing: $it", 14f, C.ACCENT, true), top = 10) }
        body.add(CheckBox(a).apply { text = "Only download on Wi-Fi"; isChecked = wifiOnly; setOnCheckedChangeListener { _, v -> wifiOnly = v } }, top = 8)

        body.add(a.label("Already have the file?", 15f, C.INK, true), top = 14)
        body.add(a.label("If you downloaded Gemma for SketchSolve (or in Chrome, or on a computer), it may still be in your Downloads folder. " +
            "Import it here; the app checks the file before using it. (Android doesn't let one app read another app's private files, so it can't use SketchSolve's copy directly.)", 13f, C.MUTED), top = 2)
        body.add(a.button("Import a Gemma file…") { importFile() }, LinearLayout.LayoutParams.WRAP_CONTENT, top = 6)
    }

    private fun card(m: ModelSpec, recommended: Boolean): LinearLayout {
        val c = a.card()
        val head = a.row()
        head.add(a.label(m.title, 17f, C.INK, true), LinearLayout.LayoutParams.WRAP_CONTENT)
        if (recommended) head.add(a.label("Recommended for this phone", 11f, C.GOOD, true).apply { setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3)); background = rounded(0xFFE3F5EC.toInt(), a.dpf(8f)) }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 8)
        c.add(head)
        c.add(a.label("${m.note} ${size(m.bytes)} download.", 14f, C.MUTED), top = 2)
        val p = ms.progress(m)
        when (p.state) {
            ModelStore.State.READY -> {
                val inUse = ms.current() == m
                c.add(a.label(if (inUse) "✓ Installed and in use" else "✓ Installed", 14f, C.GOOD, true), top = 6)
                if (!inUse) c.add(a.button("Use this one", small = true) { ms.choose(m); a.modelChanged(); render() }, LinearLayout.LayoutParams.WRAP_CONTENT, top = 6)
            }
            ModelStore.State.NONE -> {
                val r = a.row()
                r.add(a.button("Download", primary = recommended, small = true) { download(m) }, LinearLayout.LayoutParams.WRAP_CONTENT)
                r.add(a.button("Copy link", small = true) {
                    (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(m.fileName, m.url)); a.toast("Link copied")
                }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 8)
                r.add(a.button("Open in browser", small = true) { runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.url))) } }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 8)
                c.add(r, top = 8)
            }
            ModelStore.State.FAILED -> {
                c.add(a.label("⚠ ${p.message}", 14f, C.WARN), top = 6)
                c.add(a.button("Try again", small = true) { download(m) }, LinearLayout.LayoutParams.WRAP_CONTENT, top = 6)
            }
            else -> {
                val pct = if (p.total > 0) (100 * p.done / p.total).toInt() else 0
                c.add(a.label(if (p.state == ModelStore.State.CHECKING) p.message else "⇣ $pct% — ${size(p.done)} of ${size(p.total)}${if (p.message.isNotEmpty()) " · ${p.message}" else ""}", 14f, C.ACCENT, true), top = 6)
                c.add(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = if (p.total > 0) (1000 * p.done / p.total).toInt() else 0; isIndeterminate = p.state == ModelStore.State.CHECKING }, top = 4)
                if (p.state == ModelStore.State.CHECKING) Thread { if (ms.finishIfDone(m)) a.main.post { a.modelChanged(); a.toast("${m.title} is ready.") } }.start()
                else c.add(a.button("Cancel", small = true) { ms.cancel(m); render() }, LinearLayout.LayoutParams.WRAP_CONTENT, top = 6)
            }
        }
        return c
    }

    private fun download(m: ModelSpec) {
        Thread {
            val prob = ms.checkSite()
            a.main.post {
                problem = prob
                if (prob == null) ms.download(m, wifiOnly)?.let { problem = it }
                render()
            }
        }.start()
    }

    private fun importFile() {
        a.pickFile { uri ->
            importing = "starting…"; render()
            Thread {
                try {
                    val m = ms.importFile(uri) { done, total -> a.main.post { importing = "${100 * done / total}% checked and copied"; render() } }
                    a.main.post { importing = null; a.modelChanged(); a.toast("${m.title} is installed."); render() }
                } catch (e: Exception) {
                    a.main.post { importing = null; problem = e.message; render() }
                }
            }.start()
        }
    }
}
