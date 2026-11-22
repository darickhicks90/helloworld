package app.sketchsolve.llm

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A model file the app downloads once, then uses offline. */
data class ModelSpec(val id: String, val title: String, val purpose: String, val url: String, val fileName: String, val bytes: Long, val sha256: String, val minRamGb: Int)

/**
 * Downloads the on-device models on first use: resumable, checksum-verified,
 * optionally Wi-Fi only. After that the app never needs the internet.
 */
class ModelManager(private val ctx: Context) {
    companion object {
        // Gemma 4 (Apache 2.0) from Google's LiteRT community page; Whisper (MIT) from whisper.cpp.
        val GEMMA_E2B = ModelSpec("gemma4-e2b", "Gemma 4 (E2B)", "Understands word problems",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            "gemma-4-E2B-it.litertlm", 2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", 6)
        val GEMMA_E4B = ModelSpec("gemma4-e4b", "Gemma 4 (E4B)", "Understands word problems — most accurate",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            "gemma-4-E4B-it.litertlm", 3_659_530_240L, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", 8)
        val WHISPER_SMALL = ModelSpec("whisper-small-en", "Whisper small (English)", "Push-to-talk speech",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin",
            "ggml-small.en-q5_1.bin", 190_098_681L, "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30", 4)
        val WHISPER_BASE = ModelSpec("whisper-base-en", "Whisper base (English)", "Push-to-talk speech (lighter)",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin",
            "ggml-base.en-q5_1.bin", 59_721_011L, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f", 2)
    }

    val LANGUAGE = listOf(GEMMA_E2B, GEMMA_E4B)
    val SPEECH = listOf(WHISPER_BASE, WHISPER_SMALL)

    /** What we know about this phone or tablet. */
    data class DeviceInfo(val name: String, val chip: String, val ramGb: Double, val freeGb: Double, val cores: Int, val android: String)

    /** Tests can pretend to be a particular device. */
    var deviceOverride: DeviceInfo? = null

    fun device(): DeviceInfo {
        deviceOverride?.let { return it }
        val chip = if (android.os.Build.VERSION.SDK_INT >= 31)
            listOf(android.os.Build.SOC_MANUFACTURER, android.os.Build.SOC_MODEL).filter { it.isNotBlank() && it != android.os.Build.UNKNOWN }.joinToString(" ")
        else android.os.Build.HARDWARE
        val maker = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return DeviceInfo("$maker ${android.os.Build.MODEL}".trim(), chip.ifBlank { "unknown chip" }, ramGb(), freeBytes() / 1e9,
            Runtime.getRuntime().availableProcessors(), android.os.Build.VERSION.RELEASE ?: "")
    }

    /** How a model suits this device. */
    data class Fit(val model: ModelSpec, val recommended: Boolean, val usable: Boolean, val note: String)

    fun fits(): Pair<List<Fit>, List<Fit>> {
        val d = device()
        val known = d.ramGb > 0.5
        fun space(m: ModelSpec) = isReady(m) || d.freeGb * 1e9 > m.bytes + 300_000_000L
        val bigOk = !known || d.ramGb >= 10.0 // 12 GB phones report about 11; matches the Play device group
        val llm = LANGUAGE.map { m ->
            when {
                isReady(m) -> Fit(m, false, true, "Already downloaded.")
                !space(m) -> Fit(m, false, false, "Needs ${"%.1f".format(m.bytes / 1e9 + 0.3)} GB of free space; this device has ${"%.1f".format(d.freeGb)} GB.")
                m == GEMMA_E4B && known && d.ramGb < m.minRamGb - 0.5 -> Fit(m, false, false, "Needs about ${m.minRamGb} GB of memory; this device has ${"%.1f".format(d.ramGb)} GB.")
                m == GEMMA_E4B -> Fit(m, bigOk, true, "Most accurate on tricky wording. About twice as slow, and uses more battery.")
                known && d.ramGb < 5.5 -> Fit(m, true, true, "Works, but this device has only ${"%.1f".format(d.ramGb)} GB of memory, so answers will be slow.")
                else -> Fit(m, !bigOk, true, "Fast and accurate on everyday word problems. Best for most phones.")
            }
        }
        val speech = SPEECH.map { m ->
            when {
                isReady(m) -> Fit(m, false, true, "Already downloaded.")
                !space(m) -> Fit(m, false, false, "Not enough free space.")
                m == WHISPER_SMALL -> Fit(m, !known || d.ramGb >= 5.5, true, "Hears numbers and maths words more accurately.")
                else -> Fit(m, known && d.ramGb < 5.5, true, "Smaller and quicker; fine in a quiet room.")
            }
        }
        // if nothing is marked recommended (e.g. one is already downloaded), recommend the first usable
        fun fix(l: List<Fit>) = if (l.any { it.recommended }) l else l.mapIndexed { i, f -> if (i == l.indexOfFirst { it.usable }) f.copy(recommended = true) else f }
        return fix(llm) to fix(speech)
    }

    private val prefs = ctx.getSharedPreferences("sketchsolve", Context.MODE_PRIVATE)
    var chosenLanguage: ModelSpec
        get() = LANGUAGE.firstOrNull { it.id == prefs.getString("llm", null) } ?: fits().first.firstOrNull { it.recommended }?.model ?: GEMMA_E2B
        set(v) { prefs.edit().putString("llm", v.id).apply() }
    var chosenSpeech: ModelSpec
        get() = SPEECH.firstOrNull { it.id == prefs.getString("speech", null) } ?: fits().second.firstOrNull { it.recommended }?.model ?: WHISPER_BASE
        set(v) { prefs.edit().putString("speech", v.id).apply() }

    /** The language model to use: the chosen one if downloaded, otherwise any downloaded one. */
    fun languageModel(): ModelSpec? = chosenLanguage.takeIf { isReady(it) } ?: LANGUAGE.firstOrNull { isReady(it) }
    fun speechReady(): ModelSpec? = chosenSpeech.takeIf { isReady(it) } ?: SPEECH.firstOrNull { isReady(it) }

    val dir: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    fun file(m: ModelSpec) = File(dir, m.fileName)
    fun isReady(m: ModelSpec) = file(m).let { it.exists() && it.length() == m.bytes }

    fun ramGb(): Double {
        deviceOverride?.let { return it.ramGb }
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1e9
    }

    fun wanted(): List<ModelSpec> = listOf(chosenLanguage, chosenSpeech)

    fun freeBytes(): Long = deviceOverride?.let { (it.freeGb * 1e9).toLong() } ?: StatFs(dir.absolutePath).availableBytes

    fun onWifi(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !cm.isActiveNetworkMetered || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /* ---------------- downloading with Android's DownloadManager ----------------
     * The system download manager keeps going when the screen is off or the
     * app is closed, resumes after the network drops, shows progress in the
     * notification bar, and honours "Wi-Fi only". The app then checks the
     * file's size and SHA-256 before using it. */

    enum class State { NONE, QUEUED, RUNNING, PAUSED, VERIFYING, FAILED, READY }
    data class Progress(val state: State, val done: Long, val total: Long, val message: String = "", val needsOk: Boolean = false)

    private val dm get() = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
    private fun idKey(m: ModelSpec) = "dl_${m.id}"
    private fun tempName(m: ModelSpec) = "models/${m.fileName}.download"
    private fun tempFile(m: ModelSpec) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, tempName(m))
    private val verifying = java.util.Collections.synchronizedSet(HashSet<String>())
    private val failures = java.util.concurrent.ConcurrentHashMap<String, String>()

    /* ---------------- Google Play delivery ----------------
     * When the app came from Google Play, the models come from Google Play too
     * (see PlayPacks). Otherwise, or for a model Play doesn't carry for this
     * phone, they come from the internet as below. */

    /** Tests set this before first use. */
    var packSourceOverride: PackSource? = null
    val play: PlayPacks by lazy { PlayPacks(packSourceOverride ?: runCatching { GooglePlayPackSource(ctx) }.getOrNull()) }
    private fun playKey(m: ModelSpec) = "play_${m.id}"
    fun viaPlay(m: ModelSpec) = prefs.getBoolean(playKey(m), false)

    /** Download from Google Play. Call after play.offers(m) said yes. */
    fun enqueueFromPlay(m: ModelSpec): String? {
        if (isReady(m)) return null
        val need = m.bytes + PlayPacks.CHUNK + 300_000_000L // the model plus one part while joining
        if (freeBytes() < need) return "Not enough free space for ${m.title}: it needs ${"%.1f".format(need / 1e9)} GB."
        cancel(m)
        prefs.edit().putBoolean(playKey(m), true).apply()
        play.fetch(m)
        return null
    }

    /** After a restart, ask Google Play where its downloads are. Blocks. */
    fun refreshPlay() { for (m in LANGUAGE + SPEECH) if (viaPlay(m) && !isReady(m)) play.refresh(m) }

    /** Start (or keep) a download. Returns an error message, or null if it was queued. */
    fun enqueue(m: ModelSpec, wifiOnly: Boolean): String? {
        if (isReady(m)) return null
        if (viaPlay(m)) { play.cancel(m); prefs.edit().remove(playKey(m)).apply() }
        if (prefs.getLong(idKey(m), -1L) >= 0 && progress(m).state in setOf(State.QUEUED, State.RUNNING, State.PAUSED)) return null
        val need = m.bytes + 300_000_000L
        if (freeBytes() < need) return "Not enough free space for ${m.title}: it needs ${"%.1f".format(need / 1e9)} GB."
        failures.remove(m.id)
        tempFile(m).delete()
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(m.url))
            .setTitle("SketchSolve: ${m.title}")
            .setDescription(m.purpose)
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, null, tempName(m))
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        return try { prefs.edit().putLong(idKey(m), dm.enqueue(req)).apply(); null }
        catch (e: Exception) { "Couldn't start the download: ${e.message}" }
    }

    fun progress(m: ModelSpec): Progress {
        if (isReady(m)) return Progress(State.READY, m.bytes, m.bytes)
        if (m.id in verifying) return Progress(State.VERIFYING, m.bytes, m.bytes, "Checking the file…")
        failures[m.id]?.let { return Progress(State.FAILED, 0, m.bytes, it) }
        if (viaPlay(m)) return play.progress(m)
        val id = prefs.getLong(idKey(m), -1L)
        if (id < 0) return Progress(State.NONE, 0, m.bytes)
        dm.query(android.app.DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) { prefs.edit().remove(idKey(m)).apply(); return Progress(State.NONE, 0, m.bytes) }
            val status = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: m.bytes
            val reason = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON))
            return when (status) {
                android.app.DownloadManager.STATUS_PENDING -> Progress(State.QUEUED, done, total, "Waiting to start…")
                android.app.DownloadManager.STATUS_RUNNING -> Progress(State.RUNNING, done, total)
                android.app.DownloadManager.STATUS_PAUSED -> Progress(State.PAUSED, done, total, pausedReason(reason))
                android.app.DownloadManager.STATUS_SUCCESSFUL -> Progress(State.VERIFYING, total, total, "Checking the file…")
                android.app.DownloadManager.STATUS_FAILED -> Progress(State.FAILED, done, total, failedReason(reason))
                else -> Progress(State.QUEUED, done, total, "Waiting to start…")
            }
        }
        return Progress(State.NONE, 0, m.bytes)
    }

    private fun pausedReason(r: Int) = when (r) {
        android.app.DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi."
        android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a connection."
        android.app.DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection dropped; it will retry."
        else -> "Paused."
    }

    private fun failedReason(r: Int) = when (r) {
        android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free space."
        android.app.DownloadManager.ERROR_HTTP_DATA_ERROR, android.app.DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "The server didn't send the file. Try again later."
        android.app.DownloadManager.ERROR_CANNOT_RESUME -> "The download couldn't resume. Try again."
        android.app.DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage isn't available."
        else -> "The download failed (code $r). Try again."
    }

    /**
     * If a download has finished, check it and move it into place. Call from a
     * background thread. Returns true when the model became ready.
     */
    fun finishIfDone(m: ModelSpec): Boolean {
        if (isReady(m)) return false
        if (progress(m).state != State.VERIFYING || !verifying.add(m.id)) return false
        if (viaPlay(m)) try {
            val tmp = File(dir, m.fileName + ".joining")
            if (!play.assemble(m, tmp)) { prefs.edit().remove(playKey(m)).apply(); failures[m.id] = play.progress(m).message.ifEmpty { "The download was damaged. Please try again." }; play.clearFailure(m); return false }
            prefs.edit().remove(playKey(m)).apply()
            file(m).delete()
            if (!tmp.renameTo(file(m))) { failures[m.id] = "Couldn't save ${m.fileName}."; return false }
            return true
        } finally { verifying.remove(m.id) }
        try {
            val tmp = tempFile(m)
            val ok = tmp.exists() && tmp.length() == m.bytes && sha256(tmp) == m.sha256
            prefs.edit().remove(idKey(m)).apply()
            if (!ok) { tmp.delete(); failures[m.id] = "The downloaded file was damaged and has been removed. Please try again."; return false }
            file(m).delete()
            if (!tmp.renameTo(file(m))) { failures[m.id] = "Couldn't save ${m.fileName}."; return false }
            return true
        } finally { verifying.remove(m.id) }
    }

    fun cancel(m: ModelSpec) {
        if (viaPlay(m)) { play.cancel(m); prefs.edit().remove(playKey(m)).apply() }
        val id = prefs.getLong(idKey(m), -1L)
        if (id >= 0) runCatching { dm.remove(id) }
        prefs.edit().remove(idKey(m)).apply()
        failures.remove(m.id)
        tempFile(m).delete()
    }

    fun clearFailure(m: ModelSpec) { failures.remove(m.id); play.clearFailure(m) }

    /** Tests can skip the address lookup. */
    var skipNetworkCheck = false

    /** Can this phone look up the download site's address? (Run off the main thread.) */
    fun canReachDownloadSite(): String? = if (skipNetworkCheck) null else try {
        for (h in listOf("huggingface.co", "us.aws.cdn.hf.co")) java.net.InetAddress.getByName(h)
        null
    } catch (e: java.net.UnknownHostException) {
        "This phone can't look up the download site (${e.message?.substringAfter('"')?.substringBefore('"') ?: "huggingface.co"}). " +
            "A VPN, an ad blocker, Private DNS, or a filtered Wi-Fi network can cause this. Try another network, or download the files yourself and import them."
    } catch (e: Exception) { null }

    /**
     * Install a model file the user downloaded themselves (e.g. on a computer,
     * then copied to the phone). Works out which model it is from its size,
     * checks its SHA-256 while copying, and only then puts it in place.
     * Returns the model installed. Blocks; call from a background thread.
     */
    fun importFile(uri: android.net.Uri, progress: (Long, Long) -> Unit): ModelSpec {
        val cr = ctx.contentResolver
        var size = -1L; var name = ""
        cr.query(uri, arrayOf(android.provider.OpenableColumns.SIZE, android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) { if (!c.isNull(0)) size = c.getLong(0); name = c.getString(1) ?: "" }
        }
        if (uri.scheme == "file") uri.path?.let { p -> File(p).let { f -> if (size < 0) size = f.length(); if (name.isEmpty()) name = f.name } }
        if (name.isEmpty()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val all = LANGUAGE + SPEECH
        val m = all.firstOrNull { it.bytes == size } ?: all.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
            ?: throw IllegalStateException("“$name” isn't one of the SketchSolve model files. Expected one of: ${all.joinToString(", ") { it.fileName }}.")
        if (size >= 0 && size != m.bytes) throw IllegalStateException("“$name” is the wrong size (${size} bytes; ${m.fileName} should be ${m.bytes}). It may not have finished downloading.")
        if (freeBytes() < m.bytes + 100_000_000L) throw IllegalStateException("Not enough free space to import ${m.title}.")
        val tmp = File(dir, m.fileName + ".import")
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        (cr.openInputStream(uri) ?: throw IllegalStateException("Couldn't open “$name”.")).use { input ->
            tmp.outputStream().buffered(1 shl 20).use { out ->
                val buf = ByteArray(1 shl 16)
                var last = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (done - last > 8_000_000) { progress(done, m.bytes); last = done }
                }
            }
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (done != m.bytes || hash != m.sha256) { tmp.delete(); throw IllegalStateException("“$name” is damaged or incomplete. Download it again and re-import it.") }
        cancel(m)
        file(m).delete()
        if (!tmp.renameTo(file(m))) throw IllegalStateException("Couldn't save ${m.fileName}.")
        return m
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete(m: ModelSpec) { cancel(m); file(m).delete() }
}
