package app.gemmadesk.llm

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.security.MessageDigest

/** A Gemma model file. */
data class ModelSpec(val id: String, val title: String, val note: String, val url: String, val fileName: String, val bytes: Long, val sha256: String)

/**
 * Finds, downloads or imports the Gemma model. Downloaded once, then the app
 * never needs the internet. Every file's size and SHA-256 are checked before use.
 */
class ModelStore(private val ctx: Context) {
    companion object {
        val E2B = ModelSpec("gemma4-e2b", "Gemma 4 E2B", "Faster. Good for most phones.",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            "gemma-4-E2B-it.litertlm", 2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
        val E4B = ModelSpec("gemma4-e4b", "Gemma 4 E4B", "Better designs and fewer mistakes. Needs about 12 GB of memory.",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            "gemma-4-E4B-it.litertlm", 3_659_530_240L, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0")
        val ALL = listOf(E4B, E2B)
    }

    val dir: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("gemmadesk", Context.MODE_PRIVATE)

    fun file(m: ModelSpec) = File(dir, m.fileName)
    fun isReady(m: ModelSpec) = file(m).let { it.exists() && it.length() == m.bytes }

    /** Tests can pretend to be another phone. */
    var ramOverrideGb: Double? = null
    fun ramGb(): Double = ramOverrideGb ?: ActivityManager.MemoryInfo().also {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }.totalMem / 1e9
    fun freeBytes(): Long = StatFs(dir.absolutePath).availableBytes

    /** E4B on phones with about 12 GB of memory (they report about 11), E2B otherwise. */
    fun recommended(): ModelSpec = if (ramGb() >= 10.0) E4B else E2B

    /** The model to use: the chosen one if present, else any installed one. */
    fun current(): ModelSpec? {
        val chosen = ALL.firstOrNull { it.id == prefs.getString("model", null) }
        return chosen?.takeIf { isReady(it) } ?: ALL.firstOrNull { isReady(it) }
    }
    fun choose(m: ModelSpec) = prefs.edit().putString("model", m.id).apply()

    /* ---------- download with Android's download manager ---------- */

    enum class State { NONE, RUNNING, PAUSED, CHECKING, FAILED, READY }
    data class Progress(val state: State, val done: Long, val total: Long, val message: String = "")

    private val dm get() = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private fun key(m: ModelSpec) = "dl_${m.id}"
    private fun temp(m: ModelSpec) = File(dir, m.fileName + ".download")
    @Volatile private var failure: String? = null
    @Volatile private var checking = false

    /** Can the phone find the download site? Off the main thread. Returns a problem, or null. */
    fun checkSite(): String? = try {
        java.net.InetAddress.getByName("huggingface.co"); null
    } catch (e: java.net.UnknownHostException) {
        "This phone can't reach the download site. A VPN, an ad blocker, Private DNS or a filtered network can cause this. " +
            "You can also download the file yourself (in Chrome or on a computer) and import it."
    } catch (e: Exception) { null }

    fun download(m: ModelSpec, wifiOnly: Boolean): String? {
        if (isReady(m)) return null
        if (freeBytes() < m.bytes + 300_000_000L) return "Not enough free space: ${m.title} needs ${"%.1f".format((m.bytes + 300_000_000L) / 1e9)} GB."
        cancel(m)
        failure = null
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("Gemma Desk: ${m.title}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(temp(m)))
            .setAllowedOverMetered(!wifiOnly)
        return try { prefs.edit().putLong(key(m), dm.enqueue(req)).apply(); null } catch (e: Exception) { "Couldn't start the download: ${e.message}" }
    }

    fun progress(m: ModelSpec): Progress {
        if (isReady(m)) return Progress(State.READY, m.bytes, m.bytes)
        if (checking) return Progress(State.CHECKING, m.bytes, m.bytes, "Checking the file…")
        failure?.let { return Progress(State.FAILED, 0, m.bytes, it) }
        val id = prefs.getLong(key(m), -1L)
        if (id < 0) return Progress(State.NONE, 0, m.bytes)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return Progress(State.NONE, 0, m.bytes)
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> Progress(State.CHECKING, m.bytes, m.bytes, "Checking the file…")
                DownloadManager.STATUS_FAILED -> Progress(State.FAILED, done, m.bytes, "The download failed. Try again, or download it yourself and import it.")
                DownloadManager.STATUS_PAUSED -> Progress(State.PAUSED, done, m.bytes, "Waiting for a connection (or Wi-Fi).")
                else -> Progress(State.RUNNING, done, m.bytes)
            }
        }
        return Progress(State.NONE, 0, m.bytes)
    }

    /** If a download finished, check it and put it in place. Off the main thread. */
    fun finishIfDone(m: ModelSpec): Boolean {
        if (isReady(m) || checking || progress(m).state != State.CHECKING) return false
        checking = true
        try {
            val t = temp(m)
            prefs.edit().remove(key(m)).apply()
            if (!(t.exists() && t.length() == m.bytes && sha256(t) == m.sha256)) { t.delete(); failure = "The downloaded file was damaged. Please try again."; return false }
            return t.renameTo(file(m)).also { if (it) choose(m) }
        } finally { checking = false }
    }

    fun cancel(m: ModelSpec) {
        val id = prefs.getLong(key(m), -1L)
        if (id >= 0) runCatching { dm.remove(id) }
        prefs.edit().remove(key(m)).apply()
        temp(m).delete()
    }

    /**
     * Install a model file the user downloaded themselves. Works out which
     * model it is from its size or name and checks the SHA-256 while copying.
     * Blocks; off the main thread.
     */
    fun importFile(uri: Uri, progress: (Long, Long) -> Unit): ModelSpec {
        val cr = ctx.contentResolver
        var size = -1L; var name = ""
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.SIZE, android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { if (!c.isNull(0)) size = c.getLong(0); name = c.getString(1) ?: "" }
            }
        }
        if (uri.scheme == "file") uri.path?.let { File(it) }?.let { if (size < 0) size = it.length(); if (name.isEmpty()) name = it.name }
        val m = ALL.firstOrNull { it.bytes == size } ?: ALL.firstOrNull { it.fileName.equals(name, true) }
            ?: throw IllegalStateException("“$name” isn't a Gemma model file. Expected ${ALL.joinToString(" or ") { it.fileName }}.")
        if (size >= 0 && size != m.bytes) throw IllegalStateException("“$name” is the wrong size. It may not have finished downloading.")
        if (freeBytes() < m.bytes + 100_000_000L) throw IllegalStateException("Not enough free space to import ${m.title}.")
        val tmp = File(dir, m.fileName + ".import")
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        (cr.openInputStream(uri) ?: throw IllegalStateException("Couldn't open “$name”.")).use { input ->
            tmp.outputStream().buffered(1 shl 20).use { out ->
                val buf = ByteArray(1 shl 16); var last = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (done - last > 16_000_000) { progress(done, m.bytes); last = done }
                }
            }
        }
        if (done != m.bytes || md.digest().joinToString("") { "%02x".format(it) } != m.sha256) {
            tmp.delete(); throw IllegalStateException("“$name” is damaged or incomplete. Download it again.")
        }
        file(m).delete()
        if (!tmp.renameTo(file(m))) throw IllegalStateException("Couldn't save ${m.fileName}.")
        choose(m)
        return m
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
