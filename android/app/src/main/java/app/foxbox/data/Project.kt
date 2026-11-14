package app.foxbox.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import app.foxbox.audio.Audio
import app.foxbox.fx.Flower
import app.foxbox.seq.Clip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A FoxBox project: tempo, key, clips, Valley links and every engine's
 * settings. Lives in its own folder with project.json, samples/, midi/
 * and audio/. MIDI clips and audio are also exported to Music/FoxBox.
 */
class Project(val dir: File) {
    var name = "New Song"
    var bpm = 110f
    var root = 0
    var scale = 1
    var seventh = false
    var octave = 0
    var loopBars = 4
    var metronome = true
    var quantize = true
    var modified = System.currentTimeMillis()
    val clips = mutableListOf<Clip>()
    /** Valley: lines drawn for the next scene, items added to it on their own, and the playing scene's lines. */
    val links = mutableListOf<Pair<String, String>>()
    val nextSolo = mutableSetOf<String>()
    val sceneLinks = mutableListOf<Pair<String, String>>()
    val sceneIds = mutableSetOf<String>()
    /** Effects the player has designed (Flowers). */
    val flowers = mutableListOf<Flower>()
    var engineState = JSONObject()

    val samplesDir get() = File(dir, "samples")
    val midiDir get() = File(dir, "midi")
    val audioDir get() = File(dir, "audio")

    fun toJson() = JSONObject().apply {
        put("name", name); put("bpm", bpm.toDouble()); put("root", root); put("scale", scale)
        put("seventh", seventh); put("octave", octave); put("loopBars", loopBars)
        put("metronome", metronome); put("quantize", quantize); put("modified", modified)
        put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })
        put("flowers", JSONArray().apply { flowers.forEach { put(it.toJson()) } })
        put("links", JSONArray().apply { links.forEach { put(JSONArray(listOf(it.first, it.second))) } })
        put("nextSolo", JSONArray(nextSolo.toList()))
        put("engines", engineState)
    }

    fun fromJson(o: JSONObject) {
        name = o.optString("name", name); bpm = o.optDouble("bpm", 110.0).toFloat()
        root = o.optInt("root"); scale = o.optInt("scale", 1); seventh = o.optBoolean("seventh")
        octave = o.optInt("octave"); loopBars = o.optInt("loopBars", 4)
        metronome = o.optBoolean("metronome", true); quantize = o.optBoolean("quantize", true)
        modified = o.optLong("modified", modified)
        val known = Audio.engines.map { it.id }.toSet()
        clips.clear(); o.optJSONArray("clips")?.let { a -> for (i in 0 until a.length()) Clip.fromJson(a.getJSONObject(i)).let { if (it.engine in known) clips += it } }
        flowers.clear(); o.optJSONArray("flowers")?.let { a -> for (i in 0 until a.length()) flowers += Flower.fromJson(a.getJSONObject(i)) }
        links.clear(); o.optJSONArray("links")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { if (it.getString(0) != "fox" && it.getString(1) != "fox") links += it.getString(0) to it.getString(1) } }
        nextSolo.clear(); o.optJSONArray("nextSolo")?.let { a -> for (i in 0 until a.length()) nextSolo += a.getString(i) }
        sceneLinks.clear(); sceneIds.clear()
        engineState = o.optJSONObject("engines") ?: JSONObject()
    }

    /** Capture every engine's params + extra state. */
    fun captureEngines() {
        engineState = JSONObject().apply {
            Audio.engines.forEach { e -> put(e.id, JSONObject().apply { put("params", e.p.save()); e.saveExtra(this) }) }
        }
    }

    /** Push this project's settings into the engines. */
    fun applyEngines() {
        Audio.engines.forEach { e ->
            val o = engineState.optJSONObject(e.id)
            e.p.load(o?.optJSONObject("params"))
            e.loadExtra(o)
        }
        listOf(Audio.reflections, Audio.leaves).forEach { e ->
            val data = AudioFiles.readWav(File(samplesDir, "${e.id}.wav"))
            if (data != null) e.setSample(data, engineState.optJSONObject(e.id)?.optString("sampleName", "Sample") ?: "Sample")
            else e.resetSample()
        }
        Audio.transport.bpm = bpm; Audio.transport.metronome = metronome; Audio.transport.quantize = quantize
    }
}

object ProjectStore {
    private lateinit var root: File
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        root = File(app.filesDir, "projects").apply { mkdirs() }
    }

    fun list(): List<Project> = (root.listFiles()?.toList() ?: emptyList())
        .filter { File(it, "project.json").exists() }
        .mapNotNull { d -> runCatching { Project(d).apply { fromJson(JSONObject(File(d, "project.json").readText())) } }.getOrNull() }
        .sortedByDescending { it.modified }

    fun create(name: String): Project {
        val p = Project(File(root, UUID.randomUUID().toString().take(12)))
        p.name = name.ifBlank { "New Song" }
        p.dir.mkdirs()
        Audio.engines.forEach { e -> e.p.load(null); e.loadExtra(null) }
        p.captureEngines()
        save(p)
        return p
    }

    fun save(p: Project) {
        p.modified = System.currentTimeMillis()
        p.dir.mkdirs()
        val tmp = File(p.dir, "project.json.tmp")
        tmp.writeText(p.toJson().toString())
        tmp.renameTo(File(p.dir, "project.json"))
    }

    fun delete(p: Project) = p.dir.deleteRecursively()

    fun rename(p: Project, name: String) { p.name = name; save(p) }

    // ------------------------------------------------------------------ files

    fun saveSample(p: Project, engineId: String, data: FloatArray) {
        val f = File(p.samplesDir, "$engineId.wav")
        AudioFiles.writeWav(f, data)
        exportToMusic(p, "Audio", "${engineId}-sample-${stamp()}.wav", "audio/x-wav", f.readBytes())
    }

    /** Write a clip's MIDI file into the project and to Music/FoxBox. */
    fun saveClipMidi(p: Project, clip: Clip) = runCatching {
        val bytes = clip.toMidiFile(p.bpm, channel = Audio.engines.indexOfFirst { it.id == clip.engine }.coerceAtLeast(0))
        val name = safe("${clip.name}.mid")
        File(p.midiDir.apply { mkdirs() }, name).writeBytes(bytes)
        exportToMusic(p, "MIDI", name, "audio/midi", bytes)
    }.isSuccess

    fun newTapeFile(p: Project) = File(p.audioDir.apply { mkdirs() }, "foxbox-${stamp()}.wav")
    fun exportTape(p: Project, f: File) = exportToMusic(p, "Audio", f.name, "audio/x-wav", f.readBytes())

    /** Copy into the shared Music/FoxBox/<project>/<kind> folder so it is visible on the device. */
    private fun exportToMusic(p: Project, kind: String, name: String, mime: String, bytes: ByteArray): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/FoxBox/${safe(p.name)}/$kind")
        }
        val uri = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        true
    }.getOrDefault(false)

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifEmpty { "FoxBox" }
    private fun stamp() = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
}
