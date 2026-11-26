package app.gemmadesk.ui

import android.app.AlertDialog
import android.net.Uri
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.gemmadesk.MainActivity
import app.gemmadesk.assist.Reader
import app.gemmadesk.data.Doc
import app.gemmadesk.data.Passage

/**
 * Library: manuals, code books and other references you download, kept on
 * the phone. Ask in plain words; Gemma answers only from your documents and
 * shows the exact passages (and pages) it used.
 */
class LibraryTab(private val a: MainActivity) : FrameLayout(a) {
    private val scroll = ScrollView(a)
    private val body = a.column(14)
    private val answerBox = a.column()
    private val docsBox = a.column()
    private var importing: String? = null
    private val question = a.field("Ask your library… e.g. “What size breaker for a 10 AWG copper circuit?”").apply {
        imeOptions = EditorInfo.IME_ACTION_SEND; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { ask(); true } else false }
    }

    init {
        scroll.addView(body); addView(scroll)
        val c = a.card()
        c.add(a.label("Ask your library", 16f, C.INK, true))
        val r = a.row()
        r.add(question, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        r.add(a.button("Ask", primary = true) { ask() }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 8)
        c.add(r, top = 8)
        val r2 = a.row()
        r2.add(a.button("Just search (instant)", small = true) { searchOnly() }, LinearLayout.LayoutParams.WRAP_CONTENT)
        c.add(r2, top = 8)
        c.add(a.label("Gemma answers only from your documents and shows where each answer came from. Always check the passage before relying on it for code or safety work.", 12.5f, C.MUTED), top = 6)
        body.add(c)
        body.add(answerBox, top = 10)
        body.add(docsBox, top = 12)
    }

    fun refresh() { renderDocs() }

    /* ---------------- asking ---------------- */

    private fun ask() {
        val q = question.text.toString().trim()
        if (q.isEmpty()) return
        if (a.library.docs.isEmpty()) { a.toast("Add a document first."); return }
        a.withModel("Searching your library…", { m -> Reader(m, a.library).ask(q) { a.setBusyText(it) } }) { ans, err ->
            if (ans == null) { a.toast("Couldn't answer: ${err?.message}"); return@withModel }
            showAnswer(ans)
        }
    }

    private fun searchOnly() {
        val q = question.text.toString().trim()
        if (q.isEmpty()) return
        val hits = a.library.search(q, 10)
        answerBox.removeAllViews()
        val c = a.card()
        c.add(a.label(if (hits.isEmpty()) "No passages match “$q”." else "Passages matching “$q”", 16f, C.INK, true))
        for ((i, p) in hits.withIndex()) c.add(passageRow(i + 1, p, null, null), top = 8)
        answerBox.add(c)
    }

    fun showAnswer(ans: Reader.Answer) {
        answerBox.removeAllViews()
        val c = a.card().apply { background = rounded(0xFFF4F6FF.toInt(), a.dpf(14f), a.dp(1), 0xFFC7CFF5.toInt()) }
        c.add(a.label(ans.question, 14f, C.MUTED, true))
        if (!ans.found) c.add(a.label("Your library doesn't seem to answer this.", 15f, C.WARN, true), top = 6)
        c.add(a.label(ans.text, 16f, C.INK).apply { setTextIsSelectable(true) }, top = 6)
        for (n in ans.notes) c.add(a.label("⚠ $n", 13f, C.WARN), top = 6)
        if (ans.sources.isNotEmpty()) {
            c.add(a.label("Sources", 13f, C.MUTED, true), top = 12)
            ans.sources.forEachIndexed { i, s -> c.add(passageRow(i + 1, s.passage, s.quote, s.quoteFound), top = 6) }
        }
        if (ans.searched.isNotEmpty()) {
            val more = a.column()
            more.visibility = GONE
            c.add(a.button("Show everything it read (${ans.searched.size} passage${if (ans.searched.size == 1) "" else "s"})", small = true) { more.visibility = if (more.visibility == GONE) VISIBLE else GONE }, LinearLayout.LayoutParams.WRAP_CONTENT, top = 10)
            ans.searched.forEachIndexed { i, p -> more.add(passageRow(i + 1, p, null, null), top = 6) }
            c.add(more)
        }
        answerBox.add(c)
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    private fun passageRow(n: Int, p: Passage, quote: String?, found: Boolean?): LinearLayout {
        val r = a.column().apply { setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8)); background = rounded(C.CARD, a.dpf(10f), a.dp(1), C.LINE) }
        r.add(a.label("[$n] ${p.where()}", 13.5f, C.ACCENT, true))
        if (quote != null && quote.isNotBlank()) r.add(a.label((if (found == true) "✓ " else "⚠ not found in the passage: ") + "“${quote.take(300)}”", 13.5f, if (found == true) C.INK else C.WARN), top = 2)
        else r.add(a.label(p.text.take(220).replace("\n", " ") + "…", 13f, C.MUTED), top = 2)
        r.setOnClickListener { showPassage(p, quote) }
        return r
    }

    private fun showPassage(p: Passage, quote: String?) {
        val s = SpannableString(p.text)
        if (!quote.isNullOrBlank()) {
            val i = p.text.lowercase().indexOf(quote.lowercase().take(60))
            if (i >= 0) s.setSpan(BackgroundColorSpan(0x66FFD54F), i, minOf(p.text.length, i + quote.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val tv = TextView(a).apply { text = s; textSize = 15f; setTextColor(C.INK); setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(12)); setTextIsSelectable(true) }
        AlertDialog.Builder(a).setTitle(p.where()).setView(ScrollView(a).apply { addView(tv) }).setPositiveButton("Close", null)
            .setOnDismissListener { a.immersive() }.show()
    }

    /* ---------------- documents ---------------- */

    private fun renderDocs() {
        docsBox.removeAllViews()
        val lib = a.library
        val head = a.row()
        head.add(a.label("Documents (${lib.docs.size})", 16f, C.INK, true), 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        head.add(a.button("+ Add documents", primary = true, small = true) { a.pickFiles { add(it) } }, LinearLayout.LayoutParams.WRAP_CONTENT)
        docsBox.add(head)
        importing?.let { docsBox.add(a.label("⇣ $it", 14f, C.ACCENT, true), top = 8) }
        if (lib.docs.isEmpty()) {
            docsBox.add(a.card().apply {
                add(a.label("Your library is empty", 15f, C.INK, true))
                add(a.label("Add PDFs, Word files, EPUB books, web pages or text files: equipment and appliance manuals, code books, " +
                    "data sheets, your own notes. You can also share a file to Gemma Desk from your browser or Files app.\n\n" +
                    "Code books: a PDF you're licensed to use works if its text can be copied. Copy-protected (DRM) editions, " +
                    "like many publisher apps, can't be read by other apps. Scanned pages without text can't be read yet.", 13.5f, C.MUTED), top = 4)
            }, top = 8)
            return
        }
        val list = a.card().apply { setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4)) }
        for (d in lib.docs.sortedBy { it.name.lowercase() }) list.add(docRow(d))
        docsBox.add(list, top = 8)
        docsBox.add(a.label("Long-press a document to remove it.", 12f, C.MUTED), top = 6)
    }

    private fun docRow(d: Doc): LinearLayout {
        val r = a.column().apply { setPadding(0, a.dp(8), 0, a.dp(8)) }
        r.add(a.label(d.name, 15f, C.INK, true))
        r.add(a.label("${d.kind.uppercase()} · ${d.pages} ${if (d.kind == "pdf") "pages" else "parts"} · ${d.passages} passages${if (d.bytes > 0) " · %.1f MB".format(d.bytes / 1e6) else ""}", 12.5f, C.MUTED))
        if (d.note.isNotBlank()) r.add(a.label("⚠ ${d.note}", 12.5f, C.WARN))
        r.setOnLongClickListener { a.confirm("Remove “${d.name}”?", "It will no longer be used for answers.", "Remove") { a.library.remove(d); renderDocs() }; true }
        return r
    }

    /** Add files picked or shared from another app, one after another, in the background. */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Thread {
            var ok = 0; val problems = ArrayList<String>()
            uris.forEachIndexed { i, u ->
                a.main.post { importing = "Adding ${i + 1} of ${uris.size}…"; renderDocs() }
                try { a.library.add(a, u) { msg -> a.main.post { importing = "Adding ${i + 1} of ${uris.size}: $msg"; renderDocs() } }; ok++ }
                catch (e: Exception) { problems += e.message ?: e.javaClass.simpleName }
            }
            a.library.warm()
            a.main.post {
                importing = null; renderDocs()
                if (problems.isEmpty()) a.toast("Added $ok document${if (ok == 1) "" else "s"}.")
                else AlertDialog.Builder(a).setTitle(if (ok > 0) "Added $ok; some couldn't be read" else "Couldn't add that").setMessage(problems.joinToString("\n\n"))
                    .setPositiveButton("OK", null).setOnDismissListener { a.immersive() }.show()
            }
        }.start()
    }
}
