package app.foxbox.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The notebook's memo books: pages of ruled lines holding handwritten-then-typeset text,
 * and sketch windows. Each book is one JSON file; each sketch is its own JSON (the
 * strokes) plus a PNG of the finished picture.
 */
object Notes {
    /**
     * Ruled lines on a page: tall enough to write several words across with a finger. The type
     * is sized to the page's width, so a line holds the same words on every screen shape.
     */
    const val LINES = 10
    /** Bumped when the text size or line count changes, so older books get re-wrapped once. */
    const val LAYOUT = 3
    /** Width ÷ height of a page in the PDF export (on screen the pad fills the upright phone). */
    const val ASPECT = 0.63f
    /** Cover colours, in order for new books: oxblood, navy, forest, mustard, black. */
    val COVERS = intArrayOf(0xFF7A2630.toInt(), 0xFF27355E.toInt(), 0xFF2E5240.toInt(), 0xFFB8862C.toInt(), 0xFF26242A.toInt())

    class Sketch(val id: String, var line: Int, var x: Float, var w: Float, var lines: Int, val square: Boolean) {
        fun toJson() = JSONObject().put("id", id).put("line", line).put("x", x.toDouble()).put("w", w.toDouble()).put("lines", lines).put("square", square)
        fun covers(l: Int) = l in line until line + lines
        companion object {
            fun from(o: JSONObject) = Sketch(o.getString("id"), o.getInt("line"), o.getDouble("x").toFloat(), o.getDouble("w").toFloat(), o.getInt("lines"), o.optBoolean("square"))
        }
    }

    class Page(val id: String = newId(), val lines: Array<String> = Array(LINES) { "" }, val sketches: MutableList<Sketch> = ArrayList()) {
        val empty get() = sketches.isEmpty() && lines.all { it.isBlank() }
        fun text() = lines.joinToString("\n").trimEnd()
        fun toJson() = JSONObject().put("id", id).put("lines", JSONArray(lines.toList())).put("sketches", JSONArray(sketches.map { it.toJson() }))
        fun snapshot() = Page(id, lines.copyOf(), sketches.map { Sketch(it.id, it.line, it.x, it.w, it.lines, it.square) }.toMutableList())
        companion object {
            fun from(o: JSONObject): Page {
                val arr = o.optJSONArray("lines") ?: JSONArray()
                val ls = Array(LINES) { i -> if (i < arr.length()) arr.optString(i) else "" }
                val sk = o.optJSONArray("sketches")?.let { a -> (0 until a.length()).map { Sketch.from(a.getJSONObject(it)) }.toMutableList() } ?: ArrayList()
                return Page(o.getString("id"), ls, sk)
            }
            /** A page saved with more lines than a page has now becomes several pages. */
            fun split(o: JSONObject): List<Page> {
                val arr = o.optJSONArray("lines") ?: JSONArray()
                if (arr.length() <= LINES) return listOf(from(o))
                val sk = o.optJSONArray("sketches")?.let { a -> (0 until a.length()).map { Sketch.from(a.getJSONObject(it)) } } ?: emptyList()
                return (0 until (arr.length() + LINES - 1) / LINES).map { c ->
                    val ls = Array(LINES) { i -> arr.optString(c * LINES + i, "") }
                    val mine = sk.filter { it.line / LINES == c }.mapNotNull { s ->
                        val line = s.line - c * LINES; val n = minOf(s.lines, LINES - line)
                        if (n < 3) null else Sketch(s.id, line, s.x, s.w, n, s.square)
                    }.toMutableList()
                    Page(if (c == 0) o.getString("id") else newId(), ls, mine)
                }
            }
        }
    }

    class Book(val id: String, var name: String, var cover: Int, val pages: MutableList<Page>, val bookmarks: MutableList<String>, var current: Int, var layout: Int = LAYOUT) {
        val page get() = pages[current.coerceIn(0, pages.size - 1)]
        fun toJson() = JSONObject().put("id", id).put("name", name).put("cover", cover).put("current", current)
            .put("pages", JSONArray(pages.map { it.toJson() })).put("bookmarks", JSONArray(bookmarks)).put("layout", layout)
        companion object {
            fun from(o: JSONObject): Book {
                val ps = ArrayList<Page>()
                val arr = o.getJSONArray("pages")
                for (k in 0 until arr.length()) ps += Page.split(arr.getJSONObject(k))
                if (ps.isEmpty()) ps += Page()
                val bm = o.optJSONArray("bookmarks")?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() } ?: ArrayList()
                return Book(o.getString("id"), o.optString("name", "Memo"), o.optInt("cover", COVERS[0]), ps, bm, o.optInt("current", 0), o.optInt("layout", 0))
            }
        }
    }

    private lateinit var dir: File
    val books = ArrayList<Book>()
    var book: Book? = null; private set

    fun newId() = UUID.randomUUID().toString().take(12)

    fun init(c: Context) {
        if (::dir.isInitialized) return
        dir = File(c.filesDir, "notebooks").apply { mkdirs() }
        val index = runCatching { JSONObject(File(dir, "books.json").readText()) }.getOrNull()
        val order = index?.optJSONArray("order")
        if (order != null) for (i in 0 until order.length()) {
            runCatching { books += Book.from(JSONObject(File(dir, order.getString(i) + ".json").readText())) }
        }
        if (books.isEmpty()) books += Book(newId(), "Memo book", COVERS[0], mutableListOf(Page()), ArrayList(), 0)
        book = books.firstOrNull { it.id == index?.optString("current") } ?: books.first()
    }

    fun select(b: Book) { book = b; saveIndex() }

    fun create(name: String): Book {
        val b = Book(newId(), name.ifBlank { "Memo book" }, COVERS[books.size % COVERS.size], mutableListOf(Page()), ArrayList(), 0)
        books += b; book = b; save(b); saveIndex(); return b
    }

    fun delete(b: Book) {
        b.pages.forEach { p -> p.sketches.forEach { deleteSketchFiles(it.id) } }
        File(dir, b.id + ".json").delete()
        books.remove(b)
        if (books.isEmpty()) books += Book(newId(), "Memo book", COVERS[0], mutableListOf(Page()), ArrayList(), 0).also { save(it) }
        book = books.first(); saveIndex()
    }

    fun save(b: Book = book!!) {
        runCatching { File(dir, b.id + ".json").writeText(b.toJson().toString()) }
        saveIndex()
    }
    private fun saveIndex() {
        runCatching { File(dir, "books.json").writeText(JSONObject().put("order", JSONArray(books.map { it.id })).put("current", book?.id).toString()) }
    }

    // ---- sketches: strokes as JSON, the picture as PNG
    fun sketchData(id: String): JSONObject? = runCatching { JSONObject(File(dir, "sketch_$id.json").readText()) }.getOrNull()
    fun saveSketch(id: String, data: JSONObject, picture: Bitmap) {
        runCatching { File(dir, "sketch_$id.json").writeText(data.toString()) }
        runCatching { File(dir, "sketch_$id.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        // the page shows the new picture straight away
        thumbs[id] = picture
    }
    fun sketchPng(id: String): ByteArray? = runCatching { File(dir, "sketch_$id.png").readBytes() }.getOrNull()
    fun deleteSketchFiles(id: String) { File(dir, "sketch_$id.json").delete(); File(dir, "sketch_$id.png").delete(); thumbs.remove(id) }

    private val thumbs = HashMap<String, Bitmap?>()
    /** The finished picture, scaled down for the page (null until something has been drawn). */
    fun thumb(id: String): Bitmap? = thumbs.getOrPut(id) {
        val f = File(dir, "sketch_$id.png")
        if (!f.exists()) null else runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull()
    }
}
