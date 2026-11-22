package app.sketchsolve.llm

import android.app.Activity
import android.content.Context
import com.google.android.play.core.aipacks.AiPackManagerFactory
import com.google.android.play.core.aipacks.model.AiPackErrorCode
import com.google.android.play.core.aipacks.model.AiPackStatus
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The state of one Google Play download pack. */
data class PackInfo(val status: Int, val done: Long, val total: Long, val error: Int = 0)

/** Where model packs come from. The real one is Google Play; tests use a fake. */
interface PackSource {
    /** Current states. Blocks. Throws if Google Play can't deliver packs to this install. */
    fun states(packs: List<String>): Map<String, PackInfo>
    fun fetch(packs: List<String>, onError: (Exception) -> Unit)
    /** Folder holding a downloaded pack's files, or null if it isn't downloaded. */
    fun assetsPath(pack: String): String?
    fun remove(pack: String)
    fun cancel(packs: List<String>)
    fun listen(onChange: (String, PackInfo) -> Unit)
    /** Show Google Play's "download over mobile data?" / "allow download?" prompt. */
    fun askToContinue(activity: Activity): Boolean
}

/** Google Play's on-device AI delivery (works when the app was installed from Google Play). */
class GooglePlayPackSource(ctx: Context) : PackSource {
    private val pm = AiPackManagerFactory.getInstance(ctx.applicationContext)

    override fun states(packs: List<String>): Map<String, PackInfo> =
        com.google.android.gms.tasks.Tasks.await(pm.getPackStates(packs), 20, TimeUnit.SECONDS)
            .packStates().mapValues { (_, s) -> PackInfo(s.status(), s.bytesDownloaded(), s.totalBytesToDownload(), s.errorCode()) }

    override fun fetch(packs: List<String>, onError: (Exception) -> Unit) {
        pm.fetch(packs).addOnFailureListener { onError(it) }
    }

    override fun assetsPath(pack: String): String? = pm.getPackLocation(pack)?.assetsPath()
    override fun remove(pack: String) { pm.removePack(pack) }
    override fun cancel(packs: List<String>) { pm.cancel(packs) }
    override fun listen(onChange: (String, PackInfo) -> Unit) {
        pm.registerListener { s -> onChange(s.name(), PackInfo(s.status(), s.bytesDownloaded(), s.totalBytesToDownload(), s.errorCode())) }
    }

    override fun askToContinue(activity: Activity): Boolean = pm.showConfirmationDialog(object : androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>() {
        override fun launch(input: androidx.activity.result.IntentSenderRequest, options: androidx.core.app.ActivityOptionsCompat?) {
            activity.startIntentSenderForResult(input.intentSender, 13, input.fillInIntent, input.flagsMask, input.flagsValues, 0)
        }
        override fun unregister() {}
        override val contract = androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    })
}

/**
 * Models delivered by Google Play as on-device AI packs.
 *
 * Play allows at most 1.5 GB per pack, so a big model is split into parts,
 * one pack each; the app joins the parts back into one file, checks its
 * SHA-256, then removes the packs so the model isn't stored twice.
 *
 * Packs are device-targeted (app/device_targeting_config.xml): phones with
 * lots of memory get Gemma E4B, others get Gemma E2B. A pack that holds
 * nothing for this phone means "not offered here", and the app downloads
 * that model from the internet instead.
 *
 * The pack layout here must match the build's preparePlayPacks task.
 */
class PlayPacks(private val src: PackSource?) {
    companion object {
        /** Bytes per part (Play allows 1.5 GB per pack). Tests make it smaller. */
        var CHUNK = 1_400_000_000L
        /** Model id → pack name prefix and asset folder. */
        val PREFIX = mapOf("gemma4-e4b" to "e4b", "gemma4-e2b" to "e2b", "whisper-small-en" to "whisper_small", "whisper-base-en" to "whisper_base")

        fun parts(bytes: Long) = ((bytes + CHUNK - 1) / CHUNK).toInt()
        fun packs(m: ModelSpec): List<String> {
            val p = PREFIX[m.id] ?: return emptyList()
            val n = parts(m.bytes)
            return if (n == 1) listOf(p) else (1..n).map { "${p}_$it" }
        }
        /** A part's path inside its pack's assets folder. */
        fun partPath(m: ModelSpec, i: Int) = "${PREFIX[m.id]}/${m.fileName}.part$i"
    }

    private val cache = ConcurrentHashMap<String, PackInfo>()
    private val offered = ConcurrentHashMap<String, Boolean>()
    private val errors = ConcurrentHashMap<String, String>()
    @Volatile private var listening = false

    private fun listen() {
        val s = src ?: return
        if (listening) return
        listening = true
        runCatching { s.listen { n, st -> cache[n] = st } }
    }

    /** Does Google Play carry this model for this phone? Blocks; call off the main thread. */
    fun offers(m: ModelSpec): Boolean {
        offered[m.id]?.let { return it }
        val names = packs(m)
        if (src == null || names.isEmpty()) return false
        listen()
        val ok = try {
            val st = src.states(names)
            cache.putAll(st)
            names.withIndex().all { (i, n) ->
                val s = st[n] ?: return@all false
                // a pack with no files for this phone downloads (almost) nothing
                if (s.status == AiPackStatus.COMPLETED) src.assetsPath(n)?.let { File(it, partPath(m, i + 1)).exists() } == true
                else s.total > 100_000L
            }
        } catch (e: Exception) { false } // not installed from Play, no Play Store, offline…
        offered[m.id] = ok
        return ok
    }

    fun fetch(m: ModelSpec) {
        errors.remove(m.id)
        listen()
        src?.fetch(packs(m)) { e -> errors[m.id] = "Google Play couldn't start the download: ${e.message ?: e.javaClass.simpleName}" }
    }

    /** Refresh the states after the app restarts. Blocks. */
    fun refresh(m: ModelSpec) { listen(); runCatching { src?.states(packs(m))?.let { cache.putAll(it) } } }

    fun progress(m: ModelSpec): ModelManager.Progress {
        errors[m.id]?.let { return ModelManager.Progress(ModelManager.State.FAILED, 0, m.bytes, it) }
        val st = packs(m).map { cache[it] }
        if (st.any { it == null }) return ModelManager.Progress(ModelManager.State.QUEUED, 0, m.bytes, "Asking Google Play…")
        val s = st.filterNotNull()
        val total = s.sumOf { it.total }.takeIf { it > 0 } ?: m.bytes
        val done = s.sumOf { if (it.status == AiPackStatus.COMPLETED) it.total else it.done }
        fun has(x: Int) = s.any { it.status == x }
        return when {
            has(AiPackStatus.FAILED) -> ModelManager.Progress(ModelManager.State.FAILED, done, total, errorText(s.first { it.status == AiPackStatus.FAILED }.error))
            s.all { it.status == AiPackStatus.COMPLETED } -> ModelManager.Progress(ModelManager.State.VERIFYING, total, total, "Putting the parts together…")
            has(AiPackStatus.REQUIRES_USER_CONFIRMATION) -> ModelManager.Progress(ModelManager.State.PAUSED, done, total, "Google Play needs your OK to download this.", needsOk = true)
            has(AiPackStatus.WAITING_FOR_WIFI) -> ModelManager.Progress(ModelManager.State.PAUSED, done, total, "Waiting for Wi-Fi. You can allow mobile data instead.", needsOk = true)
            has(AiPackStatus.CANCELED) -> ModelManager.Progress(ModelManager.State.NONE, 0, m.bytes)
            has(AiPackStatus.DOWNLOADING) || has(AiPackStatus.TRANSFERRING) -> ModelManager.Progress(ModelManager.State.RUNNING, done, total)
            else -> ModelManager.Progress(ModelManager.State.QUEUED, done, total, "Waiting for Google Play…")
        }
    }

    private fun errorText(code: Int) = when (code) {
        AiPackErrorCode.NETWORK_ERROR -> "No connection to Google Play. Connect to the internet and try again."
        AiPackErrorCode.INSUFFICIENT_STORAGE -> "Not enough free space."
        AiPackErrorCode.ACCESS_DENIED, AiPackErrorCode.APP_NOT_OWNED, AiPackErrorCode.UNRECOGNIZED_INSTALLATION ->
            "Google Play couldn't confirm this copy of the app. Reinstall it from Google Play, or download the files yourself."
        AiPackErrorCode.API_NOT_AVAILABLE -> "This phone's Google Play Store is too old. Update it and try again."
        else -> "Google Play couldn't download it (code $code). Try again."
    }

    fun clearFailure(m: ModelSpec) { errors.remove(m.id) }

    fun cancel(m: ModelSpec) { runCatching { src?.cancel(packs(m)) }; packs(m).forEach { cache.remove(it) }; errors.remove(m.id) }

    fun askToContinue(activity: Activity) = runCatching { src?.askToContinue(activity) == true }.getOrDefault(false)

    /**
     * Join the downloaded parts into [out], checking the SHA-256. Each pack is
     * removed once copied, so the phone never holds much more than one copy
     * (a damaged result has to be downloaded again anyway).
     * Returns false (and deletes [out]) if a part is missing or the result is damaged.
     */
    fun assemble(m: ModelSpec, out: File, progress: (Long) -> Unit = {}): Boolean {
        val s = src ?: return false
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        val names = packs(m)
        out.outputStream().buffered(1 shl 20).use { o ->
            for ((i, n) in names.withIndex()) {
                val f = s.assetsPath(n)?.let { File(it, partPath(m, i + 1)) }
                if (f == null || !f.exists()) { o.close(); out.delete(); errors[m.id] = "A downloaded part was missing. Please download it again."; return false }
                f.inputStream().buffered(1 shl 20).use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val k = input.read(buf); if (k < 0) break; o.write(buf, 0, k); md.update(buf, 0, k); done += k; progress(done) }
                }
                // copied: free the pack's space now, so the phone never holds two full copies
                runCatching { s.remove(n) }; cache.remove(n)
            }
        }
        val ok = done == m.bytes && md.digest().joinToString("") { "%02x".format(it) } == m.sha256
        if (!ok) { out.delete(); errors[m.id] = "The download from Google Play was damaged. Please download it again." }
        return ok
    }
}
