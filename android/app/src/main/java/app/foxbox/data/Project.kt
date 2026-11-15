package app.foxbox.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.foxbox.audio.Audio
import app.foxbox.blocks.Blocks
import app.foxbox.engines.SampleEngine
import app.foxbox.fx.Flower
import app.foxbox.seq.AudioClip
import app.foxbox.seq.Clip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A daisy chain in the Valley: a flower tied to the clips its line passed through. */
class DaisyChain(val flowerId: String, val clipIds: MutableList<String> = mutableListOf(), val path: MutableList<FloatArray> = mutableListOf()) {
    fun toJson() = JSONObject().apply {
        put("flower", flowerId); put("clips", JSONArray(clipIds))
        put("path", JSONArray().apply { path.forEach { put(JSONArray(listOf(it[0].toDouble(), it[1].toDouble()))) } })
    }
    companion object {
        fun fromJson(o: JSONObject) = DaisyChain(o.getString("flower")).apply {
            o.optJSONArray("clips")?.let { for (i in 0 until it.length()) clipIds += it.getString(i) }
            o.optJSONArray("path")?.let { for (i in 0 until it.length()) it.getJSONArray(i).let { p -> path += floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) } }
        }
    }
}

/**
 * A FoxBox project: tempo, key, the blocks in the session, clips, audio
 * takes, flowers, Valley state and every block's settings. Lives in its own
 * folder with project.json, samples/, midi/, audio/ and takes/.
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
    var modified = System.currentTimeMillis()
    /** Fox Box Blocks in this session, in rail order (max 6). */
    val blocks = mutableListOf<String>().apply { addAll(Blocks.DEFAULT_SESSION) }
    val clips = mutableListOf<Clip>()
    /** Audio takes on the three biplane channels. */
    val audioClips = mutableListOf<AudioClip>()
    /** Biplanes in the sky (channels 0..2 shown). */
    val planes = BooleanArray(AudioClip.CHANNELS)
    /** Clips queued for the next scene. */
    val queued = mutableSetOf<String>()
    /** Up to three daisy chains (flower → clips). */
    val chains = mutableListOf<DaisyChain>()
    val flowers = mutableListOf<Flower>()
    var engineState = JSONObject()
    /** Seed for where things lie on the clip launcher (Shuffle picks a new one). */
    var layoutSeed = 0
    /** Level of the master audio track. */
    var audioMaster = 0.9f

    val samplesDir get() = File(dir, "samples")
    val midiDir get() = File(dir, "midi")
    val audioDir get() = File(dir, "audio")
    val takesDir get() = File(dir, "takes")

    fun toJson() = JSONObject().apply {
        put("name", name); put("bpm", bpm.toDouble()); put("root", root); put("scale", scale)
        put("seventh", seventh); put("octave", octave); put("loopBars", loopBars)
        put("metronome", metronome); put("modified", modified)
        put("blocks", JSONArray(blocks))
        put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })
        put("audioClips", JSONArray().apply { audioClips.forEach { put(it.toJson()) } })
        put("planes", JSONArray(planes.toList()))
        put("queued", JSONArray(queued.toList()))
        put("chains", JSONArray().apply { chains.forEach { put(it.toJson()) } })
        put("flowers", JSONArray().apply { flowers.forEach { put(it.toJson()) } })
        put("engines", engineState); put("layoutSeed", layoutSeed); put("audioMaster", audioMaster.toDouble())
    }

    fun fromJson(o: JSONObject) {
        name = o.optString("name", name); bpm = o.optDouble("bpm", 110.0).toFloat()
        root = o.optInt("root"); scale = o.optInt("scale", 1); seventh = o.optBoolean("seventh")
        octave = o.optInt("octave"); loopBars = o.optInt("loopBars", 4)
        metronome = o.optBoolean("metronome", true)
        modified = o.optLong("modified", modified)
        blocks.clear()
        val b = o.optJSONArray("blocks")
        if (b != null) for (i in 0 until b.length()) b.getString(i).let { if (Blocks.type(it) != null && it !in blocks) blocks += it }
        else blocks += listOf("gusts", "reflections", "leaves", "minds", "bumble") // projects from before blocks
        val known = Audio.engines.map { it.id }.toSet()
        clips.clear(); o.optJSONArray("clips")?.let { a -> for (i in 0 until a.length()) Clip.fromJson(a.getJSONObject(i)).let { if (it.engine in known) clips += it } }
        audioClips.clear(); o.optJSONArray("audioClips")?.let { a -> for (i in 0 until a.length()) audioClips += AudioClip.fromJson(a.getJSONObject(i)) }
        planes.fill(false); o.optJSONArray("planes")?.let { a -> for (i in 0 until minOf(planes.size, a.length())) planes[i] = a.getBoolean(i) }
        queued.clear(); o.optJSONArray("queued")?.let { a -> for (i in 0 until a.length()) queued += a.getString(i) }
        chains.clear(); o.optJSONArray("chains")?.let { a -> for (i in 0 until minOf(3, a.length())) chains += DaisyChain.fromJson(a.getJSONObject(i)) }
        flowers.clear(); o.optJSONArray("flowers")?.let { a -> for (i in 0 until a.length()) flowers += Flower.fromJson(a.getJSONObject(i)) }
        engineState = o.optJSONObject("engines") ?: JSONObject()
        layoutSeed = o.optInt("layoutSeed"); audioMaster = o.optDouble("audioMaster", 0.9).toFloat()
    }

    /** Capture every block's params + extra state. */
    fun captureEngines() {
        engineState = JSONObject().apply {
            Audio.engines.forEach { e -> put(e.id, JSONObject().apply { put("params", e.p.save()); e.saveExtra(this) }) }
        }
    }

    /** Push this project's settings, samples and takes into the engines. */
    fun applyEngines() {
        Audio.engines.forEach { e ->
            val o = engineState.optJSONObject(e.id)
            e.p.load(o?.optJSONObject("params"))
            e.loadExtra(o)
        }
        listOf<SampleEngine>(Audio.reflections, Audio.leaves).forEach { e ->
            val data = AudioFiles.readWav(File(samplesDir, "${e.id}.wav"))
            if (data != null) e.setSample(data, engineState.optJSONObject(e.id)?.optString("sampleName", "Sample") ?: "Sample")
            else e.resetSample()
        }
        Audio.pickup.resetKit()
        val names = engineState.optJSONObject("pickup")?.optJSONArray("names")
        for (i in 0 until app.foxbox.engines.Pickup.PADS) {
            AudioFiles.readWav(File(samplesDir, "pickup_pad$i.wav"))?.let { Audio.pickup.setPadSample(i, it, names?.optString(i) ?: "Pad ${i + 1}") }
        }
        audioClips.forEach { c -> c.data = AudioFiles.readWav(File(takesDir, "${c.id}.wav")) ?: FloatArray(0) }
        Audio.transport.bpm = bpm; Audio.transport.metronome = metronome
        Audio.audioMaster = audioMaster
        Audio.setActive(blocks)
    }
}

object ProjectStore {
    private lateinit var root: File
    private lateinit var app: Context

    /** Where exported audio, MIDI and presets go. */
    enum class Location(val label: String) { MUSIC("Music/FoxBox"), DOWNLOADS("Downloads/FoxBox"), DOCUMENTS("Documents/FoxBox"), FOLDER("A folder you choose") }
    private val prefs get() = app.getSharedPreferences("files", Context.MODE_PRIVATE)
    var location: Location
        get() = runCatching { Location.valueOf(prefs.getString("loc", "MUSIC")!!) }.getOrDefault(Location.MUSIC)
        set(v) = prefs.edit().putString("loc", v.name).apply()
    var folderUri: Uri?
        get() = prefs.getString("tree", null)?.let { Uri.parse(it) }
        set(v) = prefs.edit().putString("tree", v?.toString()).apply()

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

    /** Copy a whole project (samples and takes included) under a new name. */
    fun duplicate(p: Project, name: String): Project {
        save(p)
        val dir = File(root, UUID.randomUUID().toString().take(12))
        p.dir.copyRecursively(dir)
        val copy = Project(dir).apply { fromJson(JSONObject(File(dir, "project.json").readText())); this.name = name }
        save(copy)
        return copy
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

    fun saveSample(p: Project, file: String, data: FloatArray) {
        val f = File(p.samplesDir, "$file.wav")
        AudioFiles.writeWav(f, data)
        export(p, "Audio", "$file-sample-${stamp()}.wav", "audio/x-wav", f.readBytes())
    }

    fun saveTake(p: Project, c: AudioClip) {
        val f = File(p.takesDir, "${c.id}.wav")
        AudioFiles.writeWav(f, c.data)
        export(p, "Audio", safe("${c.name}.wav"), "audio/x-wav", f.readBytes())
    }
    fun deleteTake(p: Project, c: AudioClip) { File(p.takesDir, "${c.id}.wav").delete() }

    /** Write a clip's MIDI file into the project and to the export location. */
    fun saveClipMidi(p: Project, clip: Clip) = runCatching {
        val bytes = clip.toMidiFile(p.bpm, channel = Audio.engines.indexOfFirst { it.id == clip.engine }.coerceAtLeast(0))
        val name = safe("${clip.name}.mid")
        File(p.midiDir.apply { mkdirs() }, name).writeBytes(bytes)
        export(p, "MIDI", name, "audio/midi", bytes)
    }.isSuccess

    fun newTapeFile(p: Project) = File(p.audioDir.apply { mkdirs() }, "foxbox-${stamp()}.wav")
    fun exportTape(p: Project, f: File) = export(p, "Audio", f.name, "audio/x-wav", f.readBytes())

    /** Save a file to the chosen export location. Returns a content Uri for sharing when possible. */
    fun export(p: Project?, kind: String, name: String, mime: String, bytes: ByteArray): Uri? = runCatching {
        val sub = if (p != null) "FoxBox/${safe(p.name)}/$kind" else "FoxBox/$kind"
        when (location) {
            Location.FOLDER -> {
                val tree = folderUri ?: return@runCatching null
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(app.contentResolver, parent, mime, (if (p != null) safe(p.name) + " - " else "") + name) ?: return@runCatching null
                app.contentResolver.openOutputStream(doc)?.use { it.write(bytes) }
                doc
            }
            else -> {
                val (collection, dirName) = when (location) {
                    Location.DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
                    Location.DOCUMENTS -> MediaStore.Files.getContentUri("external") to Environment.DIRECTORY_DOCUMENTS
                    else -> (if (mime.startsWith("audio")) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI) to
                        (if (mime.startsWith("audio")) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirName/$sub")
                }
                val uri = app.contentResolver.insert(collection, values) ?: return@runCatching null
                app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                uri
            }
        }
    }.getOrNull()

    fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifEmpty { "FoxBox" }
    fun stamp(): String = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
}
