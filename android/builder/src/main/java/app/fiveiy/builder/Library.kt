package app.fiveiy.builder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Everything in the world folder the Builder can use: sprites by category, sounds and music.
 * It looks at the folder every two seconds (while the Builder is open) and picks up new,
 * changed and removed files, so you can drop art in and use it straight away. Files that can't
 * be used (too big, not a picture, broken) are skipped and listed as problems; nothing in the
 * folder can crash the Builder.
 */
class Library(private val ctx: Context, val ws: Workspace) {
    class Asset(val cat: String, val name: String, val id: String, val size: Long, val modified: Long, val fileName: String = name, val density: Int = 1) {
        var bmp: Bitmap? = null
        var frames = 1
        /** 4 for a four-direction sheet (rows: down, left, right, up), else 1. */
        var dirs = 1
        var file: java.io.File? = null      // sounds: a private copy for the sound pool
        var soundId = 0
        val key get() = "$cat/$name"
        /** One frame in the picture's own pixels... */
        val srcW get() = bmp?.let { it.width / frames } ?: 16
        val srcH get() = bmp?.let { it.height / dirs } ?: 16
        /** ...and in the world's (a detail sprite, name@4x.png, has 4 picture pixels to each world pixel). */
        val frameW get() = maxOf(1, srcW / density)
        val frameH get() = maxOf(1, srcH / density)
    }

    companion object {
        val SPRITES = listOf("tiles", "walls", "props", "decor", "characters", "kiosks", "items")
        val FOLDERS = SPRITES.map { "sprites/$it" } + listOf("sounds", "music", "worlds", "packs")
        const val MAX_PX = 2048; const val MAX_IMG = 8L shl 20; const val MAX_SND = 1L shl 20; const val MAX_MUSIC = 20L shl 20
        private val FRAMES = Regex("_f(\\d{1,2})$")
        private val DENSITY = Regex("@([1-8])x$")
        fun folderOf(cat: String) = if (cat in SPRITES) "sprites/$cat" else cat
        fun extsOf(cat: String) = when (cat) { "sounds" -> setOf("wav", "ogg"); "music" -> setOf("ogg", "mp3", "wav"); else -> setOf("png") }
    }

    /** cat → name → asset, only touched on the main thread. */
    val assets = HashMap<String, LinkedHashMap<String, Asset>>().apply { for (c in SPRITES + listOf("sounds", "music")) put(c, LinkedHashMap()) }
    val problems = LinkedHashMap<String, String>()
    var onChanged: ((added: List<String>, removed: List<String>, first: Boolean) -> Unit)? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var scanned = false
    private val pool: SoundPool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val loadedSounds = HashSet<Int>()
    init { pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loadedSounds += id } }

    fun get(key: String?): Asset? { if (key == null) return null; val c = key.substringBefore('/'); return assets[c]?.get(key.substringAfter('/')) }
    fun list(cat: String): List<Asset> = assets[cat]?.values?.toList() ?: emptyList()

    fun start() { if (running) return; running = true; tick() }
    fun stop() { running = false; main.removeCallbacksAndMessages(null) }
    /** Look now (after adding a file ourselves). */
    fun rescan() { io.execute { scan() } }

    private fun tick() {
        if (!running) return
        io.execute { scan(); main.postDelayed({ tick() }, 2000) }
    }

    // ---- on the worker thread: list every folder, load what's new or changed
    private val seen = HashMap<String, Pair<Long, Long>>()
    /** Files that failed, by signature, so a broken file isn't re-read every two seconds. */
    private val failed = HashMap<String, Pair<Pair<Long, Long>, String>>()
    private fun scan() {
        val found = HashMap<String, Asset>(); val bad = HashMap<String, String>()
        for (cat in SPRITES + listOf("sounds", "music")) {
            val dirId = runCatching { ws.dir(folderOf(cat)) }.getOrNull() ?: continue
            val entries = runCatching { ws.list(dirId) }.getOrNull() ?: continue
            for (e in entries) {
                if (e.isDir || e.name.startsWith(".")) continue
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (ext == "txt" || ext == "json") continue
                val stem = e.name.substringBeforeLast('.')
                val density = if (cat in SPRITES) DENSITY.find(stem)?.groupValues?.get(1)?.toIntOrNull() ?: 1 else 1
                val base = stem.replace(DENSITY, "")
                val key = "$cat/$base"
                if (ext !in extsOf(cat)) { bad["${folderOf(cat)}/${e.name}"] = "not a ${extsOf(cat).joinToString("/").uppercase()} file"; continue }
                // the same sprite at two densities: the more detailed one wins
                if ((found[key]?.density ?: 0) >= density) continue
                found[key] = Asset(cat, base, e.id, e.size, e.modified, e.name, density)
            }
        }
        // packs: packs/<pack>/<category>/name.png, listed as "<pack>/name" in that category
        runCatching { ws.dir("packs") }.getOrNull()?.let { packsId ->
            for (pk in runCatching { ws.list(packsId) }.getOrNull().orEmpty()) {
                if (!pk.isDir || pk.name.startsWith(".")) continue
                val pname = pk.name.lowercase().replace(Regex("[^a-z0-9_]+"), "_")
                val sub = runCatching { ws.list(pk.id) }.getOrNull().orEmpty()
                for (cd in sub) {
                    val cat = cd.name.lowercase()
                    if (!cd.isDir || cat !in SPRITES) continue
                    for (e in runCatching { ws.list(cd.id) }.getOrNull().orEmpty()) {
                        if (e.isDir || e.name.startsWith(".")) continue
                        val ext = e.name.substringAfterLast('.', "").lowercase()
                        if (ext != "png") continue
                        val stem = e.name.substringBeforeLast('.')
                        val density = DENSITY.find(stem)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val base = "$pname/" + stem.replace(DENSITY, "")
                        val key = "$cat/$base"
                        if ((found[key]?.density ?: 0) >= density) continue
                        found[key] = Asset(cat, base, e.id, e.size, e.modified, "packs/${pk.name}/${cd.name}/${e.name}", density)
                    }
                }
            }
        }
        // load only what's new or changed since the last look
        val fresh = ArrayList<Asset>()
        for ((key, a) in found) {
            val sig = (a.size + a.fileName.hashCode().toLong() * 7919L) to a.modified
            if (seen[key] == sig) continue
            failed[key]?.let { (fs, msg) -> if (fs == sig) { bad["${folderOf(a.cat)}/${a.name}"] = msg; null } else null }
            if (failed[key]?.first == sig) continue
            val err = runCatching { load(a) }.exceptionOrNull()?.let { it.message ?: "couldn't be read" } ?: loadError(a)
            if (err != null) { bad["${folderOf(a.cat)}/${a.name}"] = err; failed[key] = sig to err; continue }
            failed.remove(key); seen[key] = sig; fresh += a
        }
        val gone = seen.keys.filter { it !in found }
        gone.forEach { seen.remove(it) }
        main.post { apply(fresh, gone, bad) }
    }

    private fun loadError(a: Asset): String? = when {
        a.cat in SPRITES && a.bmp == null -> "not a picture Android can read"
        else -> null
    }

    private fun load(a: Asset) {
        when (a.cat) {
            "sounds" -> {
                if (a.size > MAX_SND) throw IllegalStateException("bigger than 1 MB (keep sound effects short)")
                val bytes = ws.read(a.id, MAX_SND) ?: throw IllegalStateException("bigger than 1 MB")
                val f = java.io.File(ctx.cacheDir, "snd_" + a.name + "_" + a.modified + "." + "bin"); f.writeBytes(bytes); a.file = f
            }
            "music" -> if (a.size > MAX_MUSIC) throw IllegalStateException("bigger than 20 MB")
            else -> {
                if (a.size > MAX_IMG) throw IllegalStateException("bigger than 8 MB")
                val bytes = ws.read(a.id, MAX_IMG) ?: throw IllegalStateException("bigger than 8 MB")
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth <= 0) throw IllegalStateException("not a PNG Android can read")
                if (o.outWidth > MAX_PX || o.outHeight > MAX_PX || o.outWidth.toLong() * o.outHeight > 4_000_000L) throw IllegalStateException("${o.outWidth} x ${o.outHeight} px is too big (at most $MAX_PX px a side, 4 million pixels)")
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    ?: throw IllegalStateException("not a PNG Android can read")
                val n = FRAMES.find(a.name)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                a.frames = if (bmp.width % n == 0 && bmp.width / n > 0) n else 1
                a.dirs = if ("_4dir" in a.name && bmp.height % 4 == 0) 4 else 1
                a.bmp = bmp
            }
        }
    }

    // ---- back on the main thread
    private fun apply(fresh: List<Asset>, gone: List<String>, bad: Map<String, String>) {
        val added = ArrayList<String>()
        for (a in fresh) {
            val m = assets[a.cat] ?: continue
            val old = m[a.name]
            if (old == null) added += a.key
            if (a.cat == "sounds") {
                old?.soundId?.takeIf { it != 0 }?.let { pool.unload(it) }
                a.file?.let { f -> a.soundId = runCatching { pool.load(f.path, 1) }.getOrDefault(0) }
            }
            m[a.name] = a
            problems.keys.removeAll { it.startsWith(Library.folderOf(a.cat) + "/" + a.name) }
        }
        for (k in gone) { val c = k.substringBefore('/'); assets[c]?.remove(k.substringAfter('/'))?.soundId?.takeIf { it != 0 }?.let { pool.unload(it) } }
        // keep each palette in name order
        for ((c, m) in assets) { val sorted = m.values.sortedBy { it.name }; m.clear(); sorted.forEach { m[it.name] = it } }
        problems.clear(); problems.putAll(bad)
        val first = !scanned; scanned = true
        if (fresh.isNotEmpty() || gone.isNotEmpty() || first) onChanged?.invoke(added, gone, first)
    }

    // ---- sound and music
    fun play(key: String?, vol: Float = 1f) {
        val a = get(key) ?: return
        if (a.soundId != 0 && a.soundId in loadedSounds) pool.play(a.soundId, vol, vol, 1, 0, 1f)
    }
    private var player: MediaPlayer? = null
    var playing: String? = null; private set
    /** Loops this track (or stops, for null). Errors just stop it. */
    fun music(key: String?) {
        if (key == playing) return
        runCatching { player?.release() }; player = null; playing = null
        val a = get(key) ?: return
        playing = key
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(ctx, ws.uri(a.id)); isLooping = true; setVolume(0.7f, 0.7f)
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ -> problems["music/${a.name}"] = "couldn't be played"; playing = null; true }
                prepareAsync()
            }
        }.onFailure { problems["music/${a.name}"] = "couldn't be played"; playing = null }
    }
    fun release() { stop(); runCatching { player?.release() }; player = null; playing = null; runCatching { pool.release() }; io.shutdown() }
}
