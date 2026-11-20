package app.fiveiy.builder

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Your world folder, chosen once with the system folder picker (so it can live anywhere you
 * like: Documents, Downloads, a folder you reach from a computer over USB). Everything goes
 * through the Storage Access Framework, so the Builder needs no storage permission and can only
 * touch the folder you picked.
 */
class Workspace(private val ctx: Context, val tree: Uri) {
    class Entry(val name: String, val id: String, val mime: String, val size: Long, val modified: Long) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
    }
    private val res get() = ctx.contentResolver
    val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    fun list(dirId: String): List<Entry> {
        val out = ArrayList<Entry>()
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        res.query(q, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            while (c.moveToNext()) out += Entry(c.getString(0) ?: "", c.getString(1) ?: continue, c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
        }
        return out
    }

    private val dirCache = HashMap<String, String>()
    /** The folder at this path ("sprites/props"), made if it isn't there. */
    fun dir(path: String, create: Boolean = true): String? {
        dirCache[path]?.let { return it }
        var id = rootId
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            val found = list(id).firstOrNull { it.isDir && it.name.equals(part, ignoreCase = true) }
            id = when {
                found != null -> found.id
                create -> DocumentsContract.createDocument(res, uri(id), Document.MIME_TYPE_DIR, part)?.let { DocumentsContract.getDocumentId(it) } ?: return null
                else -> return null
            }
        }
        dirCache[path] = id
        return id
    }

    fun read(id: String, limit: Long): ByteArray? = res.openInputStream(uri(id))?.use { s ->
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384); var total = 0L
        while (true) { val n = s.read(buf); if (n < 0) break; total += n; if (total > limit) return null; out.write(buf, 0, n) }
        out.toByteArray()
    }

    /** Writes a file in this folder, replacing one of the same name. */
    fun write(dirId: String, name: String, mime: String, bytes: ByteArray) {
        val existing = list(dirId).firstOrNull { !it.isDir && it.name == name }
        val target = existing?.let { uri(it.id) } ?: DocumentsContract.createDocument(res, uri(dirId), mime, name) ?: return
        val os = runCatching { res.openOutputStream(target, "wt") }.getOrNull() ?: res.openOutputStream(target, "w")
        os?.use { it.write(bytes) }
    }

    fun delete(id: String) { runCatching { DocumentsContract.deleteDocument(res, uri(id)) } }

    /** Copies in what the folder doesn't have yet from the bundled starter kit (never overwrites). */
    fun installStarter(onlyReadmes: Boolean = false): Int {
        var n = 0
        fun walk(asset: String, path: String) {
            val kids = ctx.assets.list(asset) ?: return
            if (kids.isEmpty()) return
            val dirId = dir(path) ?: return
            val have = list(dirId).map { it.name }.toHashSet()
            for (k in kids) {
                val a = "$asset/$k"; val sub = ctx.assets.list(a)
                if (sub != null && sub.isNotEmpty()) { walk(a, if (path.isEmpty()) k else "$path/$k"); continue }
                if (k in have || (onlyReadmes && k != "README.txt")) continue
                val bytes = ctx.assets.open(a).use { it.readBytes() }
                write(dirId, k, mimeOf(k), bytes); n++
            }
        }
        walk("starter", "")
        for (d in Library.FOLDERS) dir(d)
        return n
    }

    companion object {
        fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; "mp3" -> "audio/mpeg"; "json" -> "application/json"; "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
