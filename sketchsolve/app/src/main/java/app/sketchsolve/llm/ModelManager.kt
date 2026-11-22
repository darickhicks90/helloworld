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
        val bigOk = !known || d.ramGb >= 11.0
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

    class Cancelled : Exception("cancelled")

    /**
     * Download (or resume) a model. [progress] gets (bytesDone, bytesTotal).
     * Blocks; call from a background thread. Throws on failure.
     */
    fun download(m: ModelSpec, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val target = file(m)
        if (isReady(m)) { progress(m.bytes, m.bytes); return }
        val part = File(dir, m.fileName + ".part")
        if (freeBytes() < m.bytes - part.length() + 200_000_000L) throw IllegalStateException("Not enough free space: ${m.title} needs ${"%.1f".format(m.bytes / 1e9)} GB.")
        var url = URL(m.url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 8) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code != 200 && code != 206) throw IllegalStateException("Download failed (HTTP $code).")
            if (code == 200 && part.length() > 0) part.delete()   // server ignored the resume request
            break
        }
        RandomAccessFile(part, "rw").use { out ->
            out.seek(part.length())
            var done = part.length()
            val buf = ByteArray(1 shl 16)
            conn.inputStream.use { input ->
                var last = 0L
                while (true) {
                    if (cancelled()) throw Cancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 2_000_000) { progress(done, m.bytes); last = done }
                }
            }
            progress(done, m.bytes)
        }
        conn.disconnect()
        if (part.length() != m.bytes) throw IllegalStateException("The download was incomplete. Try again to resume it.")
        if (sha256(part) != m.sha256) { part.delete(); throw IllegalStateException("The downloaded file was damaged and has been removed. Please try again.") }
        if (!part.renameTo(target)) throw IllegalStateException("Couldn't save ${m.fileName}.")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete(m: ModelSpec) { file(m).delete(); File(dir, m.fileName + ".part").delete() }
}
