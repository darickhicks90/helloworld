package app.gemmadesk.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.ln

/** A document in the library. */
data class Doc(val id: String, val name: String, val kind: String, val pages: Int, val passages: Int, val added: Long, val bytes: Long, val note: String = "") {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("kind", kind).put("pages", pages).put("passages", passages)
        .put("added", added).put("bytes", bytes).put("note", note)
    companion object {
        fun fromJson(o: JSONObject) = Doc(o.getString("id"), o.getString("name"), o.optString("kind"), o.optInt("pages"), o.optInt("passages"),
            o.optLong("added"), o.optLong("bytes"), o.optString("note"))
    }
}

/** A passage: about a paragraph or two from one page of a document. */
data class Passage(val doc: Doc, val page: Int, val text: String, val score: Double = 0.0) {
    /** "NEC 2023.pdf, page 112" */
    fun where() = "${doc.name}${if (doc.kind == "pdf") ", page $page" else if (doc.pages > 1) ", part $page" else ""}"
}

/**
 * The library: manuals, code books and other references the user adds.
 * Each document's text is extracted once and split into passages; questions
 * are answered from the passages that best match (BM25 keyword ranking, which
 * suits technical text like "210.8(A)" or "torque spec"), all on the phone.
 */
class Library(private val dir: File) {
    init { File(dir, "text").mkdirs() }
    private val listFile = File(dir, "docs.json")
    val docs = ArrayList<Doc>()
    init {
        if (listFile.exists()) runCatching { JSONArray(listFile.readText()) }.getOrNull()?.let { a -> for (i in 0 until a.length()) docs += Doc.fromJson(a.getJSONObject(i)) }
    }
    private fun saveList() = listFile.writeAtomically(JSONArray().apply { docs.forEach { put(it.toJson()) } }.toString(1))
    private fun passagesFile(id: String) = File(dir, "text/$id.json")

    /* ---------------- adding documents ---------------- */

    class Unreadable(msg: String) : Exception(msg)

    /** Add a document from the phone (PDF, text, Markdown, HTML, Word .docx or EPUB). Blocks; off the main thread. */
    fun add(ctx: Context, uri: Uri, progress: (String) -> Unit = {}): Doc {
        val cr = ctx.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        var size = 0L
        runCatching { cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) } } }
        val mime = cr.getType(uri) ?: ""
        val kind = when {
            name.endsWith(".pdf", true) || mime == "application/pdf" -> "pdf"
            name.endsWith(".docx", true) -> "docx"
            name.endsWith(".epub", true) -> "epub"
            name.endsWith(".html", true) || name.endsWith(".htm", true) || mime == "text/html" -> "html"
            name.endsWith(".md", true) || name.endsWith(".txt", true) || name.endsWith(".csv", true) || mime.startsWith("text/") -> "text"
            else -> throw Unreadable("“$name” isn't a kind of file the library can read. Use PDF, text, Markdown, HTML, Word (.docx) or EPUB.")
        }
        val open = { cr.openInputStream(uri) ?: throw Unreadable("Couldn't open “$name”.") }
        val pages: List<String> = when (kind) {
            "pdf" -> Extract.pdf(ctx, open, progress)
            "docx" -> listOf(Extract.docx(open()))
            "epub" -> Extract.epub(open())
            "html" -> listOf(Extract.html(open().bufferedReader().use { it.readText() }))
            else -> Extract.textParts(open().bufferedReader().use { it.readText() })
        }
        return addText(name, kind, pages, size)
    }

    /** Store already-extracted text (one string per page or part). */
    fun addText(name: String, kind: String, pages: List<String>, bytes: Long = 0): Doc {
        val passages = JSONArray()
        var n = 0
        pages.forEachIndexed { i, t -> for (chunk in chunks(t)) { passages.put(JSONObject().put("p", i + 1).put("t", chunk)); n++ } }
        if (n == 0) throw Unreadable("“$name” has no readable text. If it's a scanned document (pictures of pages), the library can't read it yet.")
        val doc = Doc(newId(), name, kind, pages.size, n, System.currentTimeMillis(), bytes,
            pages.count { it.isBlank() }.let { blank -> if (blank > pages.size / 4 && kind == "pdf") "$blank of ${pages.size} pages had no text (scanned pictures?)" else "" })
        passagesFile(doc.id).writeAtomically(passages.toString())
        docs.removeAll { it.name == name }.also { }   // re-adding a file replaces the old copy
        docs += doc; saveList()
        index = null
        return doc
    }

    fun remove(d: Doc) { docs.removeAll { it.id == d.id }; passagesFile(d.id).delete(); saveList(); index = null }

    /** Split a page into passages of ~1,000 characters, at paragraph or sentence ends, overlapping a little. */
    fun chunks(text: String, size: Int = 1000): List<String> {
        val t = text.replace("\r", "").replace(Regex("[ \t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start < t.length) {
            var end = minOf(t.length, start + size)
            if (end < t.length) {
                val window = t.substring(start, end)
                val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf('\n'), window.lastIndexOf(' ')).firstOrNull { it > size / 2 } ?: -1
                if (cut > 0) end = start + cut + 1
            }
            out += t.substring(start, end).trim()
            if (end >= t.length) break
            // overlap by the last sentence or so, so an answer split across passages is still found
            val back = t.substring(start, end).lastIndexOf(". ", end - start - 2).let { if (it > (end - start) - 220) start + it + 2 else end - 120 }
            start = maxOf(back, start + 1)
        }
        return out.filter { it.isNotBlank() }
    }

    /* ---------------- searching ---------------- */

    private class Index(val passages: List<Pair<Doc, JSONObject>>, val tf: List<Map<String, Int>>, val len: IntArray, val df: Map<String, Int>, val avg: Double)
    @Volatile private var index: Index? = null

    @Synchronized private fun index(): Index {
        index?.let { return it }
        val ps = ArrayList<Pair<Doc, JSONObject>>()
        for (d in docs) {
            val a = runCatching { JSONArray(passagesFile(d.id).readText()) }.getOrNull() ?: continue
            for (i in 0 until a.length()) ps += d to a.getJSONObject(i)
        }
        val tf = ps.map { (_, o) -> terms(o.getString("t")).groupingBy { it }.eachCount() }
        val df = HashMap<String, Int>()
        tf.forEach { m -> m.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val len = IntArray(tf.size) { tf[it].values.sum() }
        return Index(ps, tf, len, df, if (len.isEmpty()) 1.0 else len.average()).also { index = it }
    }

    /** Load the index ahead of time (it takes a moment for a big code book). */
    fun warm() { runCatching { index() } }

    /** The passages that best match [query], best first. */
    fun search(query: String, n: Int = 6, onlyDoc: String? = null): List<Passage> {
        val ix = index()
        val q = terms(query).distinct()
        if (q.isEmpty() || ix.passages.isEmpty()) return emptyList()
        val N = ix.passages.size.toDouble()
        val k1 = 1.2; val b = 0.75
        val phrase = query.lowercase().replace(Regex("[^a-z0-9.() ]"), " ").replace(Regex("\\s+"), " ").trim()
        val scores = ArrayList<Pair<Int, Double>>()
        for (i in ix.passages.indices) {
            if (onlyDoc != null && ix.passages[i].first.id != onlyDoc) continue
            val m = ix.tf[i]
            var s = 0.0
            for (t in q) {
                val f = m[t] ?: continue
                val df = ix.df[t] ?: continue
                val idf = ln(1 + (N - df + 0.5) / (df + 0.5))
                s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * ix.len[i] / ix.avg))
            }
            if (s <= 0) continue
            // a bonus when the exact words (or a section number) appear together
            val text = ix.passages[i].second.getString("t").lowercase()
            if (phrase.length > 6 && text.contains(phrase)) s *= 1.5
            Regex("\\b\\d{2,4}\\.\\d+(?:\\([a-z0-9]+\\))*").findAll(query.lowercase()).forEach { if (text.contains(it.value)) s *= 1.6 }
            scores += i to s
        }
        return scores.sortedByDescending { it.second }.take(n).map { (i, s) -> val (d, o) = ix.passages[i]; Passage(d, o.getInt("p"), o.getString("t"), s) }
    }

    fun passageCount() = docs.sumOf { it.passages }

    companion object {
        private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "be", "it", "this", "that", "with", "as", "at", "by",
            "from", "what", "which", "how", "do", "does", "i", "my", "me", "can", "should", "shall", "will", "if", "when", "where", "there", "their", "they", "you",
            "your", "we", "our", "not", "no", "yes", "was", "were", "has", "have", "had", "any", "all", "about", "into", "than", "then", "so", "such", "these", "those", "its")

        /** Words for searching: lower case, light stemming; section numbers like 210.8(a) kept whole too. */
        fun terms(text: String): List<String> {
            val out = ArrayList<String>()
            val lower = text.lowercase()
            Regex("\\b\\d{1,4}\\.\\d+[a-z]?(?:\\([a-z0-9]+\\))*").findAll(lower).forEach { out += it.value }
            Regex("[a-z0-9]+").findAll(lower).forEach { m ->
                val w = m.value
                if (w in STOP || (w.length < 2 && !w[0].isDigit())) return@forEach
                out += stem(w)
            }
            return out
        }

        fun stem(w: String): String = when {
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
            w.length > 4 && w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("sses")) -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
            else -> w
        }
    }
}

/** Getting plain text out of the kinds of files people download. */
object Extract {
    /** One string per page. Encrypted / DRM-protected PDFs can't be read. */
    fun pdf(ctx: Context, open: () -> InputStream, progress: (String) -> Unit): List<String> {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
        val doc = try { open().use { com.tom_roush.pdfbox.pdmodel.PDDocument.load(it) } }
            catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw Library.Unreadable("This PDF is password-protected. Open it in its own app, or use an unprotected copy.") }
        doc.use { d ->
            if (d.isEncrypted && !d.currentAccessPermission.canExtractContent()) throw Library.Unreadable("This PDF is copy-protected, so its text can't be read. Copy-protected (DRM) code books usually only open in the publisher's own app.")
            val strip = com.tom_roush.pdfbox.text.PDFTextStripper()
            val n = d.numberOfPages
            return (1..n).map { i ->
                if (i % 10 == 0 || i == n) progress("Reading page $i of $n…")
                strip.startPage = i; strip.endPage = i
                runCatching { strip.getText(d) }.getOrDefault("")
            }
        }
    }

    /** Split plain text into parts at headings or every ~6,000 characters (so answers can say where). */
    fun textParts(t: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (line in t.lines()) {
            val heading = line.startsWith("#") || (line.isNotBlank() && line == line.uppercase() && line.any { it.isLetter() } && line.length < 80)
            if ((heading && sb.length > 1500) || sb.length > 6000) { parts += sb.toString(); sb.clear() }
            sb.append(line).append('\n')
        }
        if (sb.isNotBlank()) parts += sb.toString()
        return parts
    }

    fun html(h: String): String = h
        .replace(Regex("<(script|style)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<(br|/p|/div|/h[1-6]|/li|/tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \t]+"), " ").replace(Regex("\n\\s*\n\\s*\n+"), "\n\n").trim()

    fun docx(input: InputStream): String {
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "word/document.xml") {
                    val xml = z.readBytes().toString(Charsets.UTF_8)
                    return html(xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), "\t"))
                }
            }
        }
        throw Library.Unreadable("This Word file has no text.")
    }

    fun epub(input: InputStream): List<String> {
        val parts = ArrayList<Pair<String, String>>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".xhtml", true) || e.name.endsWith(".html", true) || e.name.endsWith(".htm", true)) parts += e.name to html(z.readBytes().toString(Charsets.UTF_8))
            }
        }
        return parts.sortedBy { it.first }.map { it.second }.filter { it.isNotBlank() }
    }
}
