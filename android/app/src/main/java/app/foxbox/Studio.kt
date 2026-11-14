package app.foxbox

import android.os.Handler
import android.os.Looper
import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.data.AudioFiles
import app.foxbox.data.Project
import app.foxbox.data.ProjectStore
import app.foxbox.midi.MidiInput
import app.foxbox.seq.Clip
import app.foxbox.seq.Theory
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's controller: the open project, which engine is in front, note
 * and chord routing, recording, clips, the Valley and saving. UI code calls
 * these functions on the main thread; anything that touches sound is
 * forwarded to the audio thread with Audio.post.
 */
object Studio : Transport.Listener, MidiInput.Sink {
    private val main = Handler(Looper.getMainLooper())
    private val t get() = Audio.transport

    lateinit var project: Project
        private set
    val tabs: List<Engine> = listOf(Audio.gusts, Audio.reflections, Audio.leaves, Audio.minds, Audio.teeth)
    var current: Engine = Audio.gusts
        private set
    /** Melodic instrument the chord/strum surface plays (Teeth's page plays the last one). */
    var melodic: Engine = Audio.gusts
        private set
    /** Bars for the next clip recorded on each engine. */
    val clipBars = HashMap<String, Int>()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    var onMessage: ((String) -> Unit)? = null
    fun listen(fn: () -> Unit) = listeners.add(fn)
    fun unlisten(fn: () -> Unit) = listeners.remove(fn)
    fun changed() { main.post { listeners.forEach { it() } } }
    override fun message(text: String) { main.post { onMessage?.invoke(text) } }

    init { t.listener = this }

    // ------------------------------------------------------------------ projects

    fun open(p: Project) {
        Audio.post { t.stop() }
        project = p
        p.applyEngines()
        Audio.leaves.droneNote = 48 + p.root
        current = Audio.gusts; melodic = Audio.gusts
        changed()
    }

    private val saver = Runnable {
        if (!::project.isInitialized) return@Runnable
        project.captureEngines(); ProjectStore.save(project)
    }
    fun save(now: Boolean = false) {
        main.removeCallbacks(saver)
        if (now) saver.run() else main.postDelayed(saver, 800)
    }

    fun select(e: Engine) {
        current = e
        if (e.isInstrument && e !== Audio.minds) melodic = e
        changed()
    }

    // ------------------------------------------------------------------ key & scale

    val scale get() = Theory.SCALES[project.scale]
    private val base get() = 48 + project.octave * 12
    fun chord(degree: Int) = Theory.chord(scale, project.root, base, degree, project.seventh)
    fun scaleNote(i: Int) = Theory.degree(scale, project.root, base, i)
    fun isRoot(note: Int) = Math.floorMod(note - project.root, 12) == 0

    fun setKey(root: Int = project.root, scale: Int = project.scale, seventh: Boolean = project.seventh, octave: Int = project.octave) {
        project.root = Math.floorMod(root, 12); project.scale = Math.floorMod(scale, Theory.SCALES.size)
        project.seventh = seventh; project.octave = octave.coerceIn(-2, 2)
        Audio.leaves.droneNote = 48 + project.root
        save(); changed()
    }

    // ------------------------------------------------------------------ playing notes

    /** The engine that notes from the play surface go to. */
    val target: Engine get() = if (current === Audio.teeth) melodic else current

    fun noteOn(e: Engine, key: Int, note: Int, vel: Float) = Audio.post {
        e.noteOn(key, note, vel); t.recNoteOn(e, key, note, vel)
    }
    fun noteOff(e: Engine, key: Int) = Audio.post { e.noteOff(key); t.recNoteOff(e, key) }
    fun expr(e: Engine, key: Int, kind: Int, v: Float) = Audio.post { e.expr(key, kind, v) }

    fun chordOn(degree: Int, keyBase: Int, vel: Float = 0.8f) {
        val e = target
        chord(degree).notes.forEachIndexed { i, n -> noteOn(e, keyBase + i, n, vel) }
    }
    fun chordOff(keyBase: Int) { val e = target; for (i in 0 until 4) noteOff(e, keyBase + i) }

    fun setXy(e: Engine, x: Float, y: Float, touching: Boolean) { e.x = x; e.y = y; e.touching = touching }

    // ------------------------------------------------------------------ transport

    fun togglePlay() = Audio.post { if (t.playing) t.stop() else t.play() }
    fun recordProject() = Audio.post { t.record(null, project.loopBars * Ticks.BAR) }
    fun recordEngine(e: Engine) = Audio.post { t.record(e, barsFor(e) * Ticks.BAR) }
    fun barsFor(e: Engine) = clipBars[e.id] ?: 2
    fun setBarsFor(e: Engine, bars: Int) { clipBars[e.id] = bars.coerceIn(1, 16); changed() }

    fun setBpm(bpm: Float) { project.bpm = bpm.coerceIn(40f, 220f); t.bpm = project.bpm; save(); changed() }
    fun setLoopBars(b: Int) { project.loopBars = b.coerceIn(1, 16); save(); changed() }
    fun setMetronome(on: Boolean) { project.metronome = on; t.metronome = on; save(); changed() }
    fun setQuantize(on: Boolean) { project.quantize = on; t.quantize = on; save(); changed() }

    override fun onChanged() = changed()

    override fun onRecorded(clips: List<Clip>) {
        main.post {
            clips.forEach { c ->
                val e = engine(c.engine)
                val n = project.clips.count { it.engine == c.engine } + 1
                c.name = "${e.title} $n"; c.variant = n - 1
                project.clips += c
                ProjectStore.saveClipMidi(project, c)
            }
            message("Recorded " + clips.joinToString { it.name })
            save(); changed()
        }
    }

    // ------------------------------------------------------------------ clips

    fun engine(id: String): Engine = Audio.all.first { it.id == id }
    fun clipsFor(e: Engine) = project.clips.filter { it.engine == e.id }
    fun isPlaying(c: Clip) = c.id in t.playing_ids

    fun toggleClip(c: Clip) {
        val e = engine(c.engine)
        Audio.post { if (c.id in t.playing_ids) t.stopClip(c.id) else t.launch(c, e) }
    }

    fun setClipBars(c: Clip, bars: Int) {
        c.lengthTicks = bars.coerceIn(1, 16) * Ticks.BAR
        if (isPlaying(c)) { val e = engine(c.engine); Audio.post { t.launch(c, e) } }
        ProjectStore.saveClipMidi(project, c); save(); changed()
    }

    fun deleteClip(c: Clip) {
        Audio.post { t.stopClip(c.id) }
        project.clips.remove(c)
        project.links.removeAll { it.first == c.id || it.second == c.id }
        save(); changed()
    }

    fun renameClip(c: Clip, name: String) { c.name = name.ifBlank { c.name }; save(); changed() }

    fun sendToValley(c: Clip) {
        if (!c.inValley) {
            c.inValley = true
            val i = project.clips.count { it.inValley }
            c.vx = 0.12f + (i % 6) * 0.15f; c.vy = 0.25f + ((i / 6) % 3) * 0.22f
        }
        message("${c.name} is waiting in the Valley")
        save(); changed()
    }

    // ------------------------------------------------------------------ Valley

    val valleyClips get() = project.clips.filter { it.inValley }

    fun link(a: String, b: String) {
        if (a == b || project.links.any { (it.first == a && it.second == b) || (it.first == b && it.second == a) }) return
        project.links += a to b; save(); changed()
    }
    fun unlink(l: Pair<String, String>) { project.links.remove(l); save(); changed() }
    fun removeFromValley(c: Clip) {
        c.inValley = false
        project.links.removeAll { it.first == c.id || it.second == c.id }
        Audio.post { t.stopClip(c.id) }
        save(); changed()
    }

    /** Clips connected to the fox through links. */
    fun foxGroup(): List<Clip> {
        val seen = HashSet<String>(); val queue = ArrayDeque(listOf(FOX))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (!seen.add(n)) continue
            project.links.forEach { (a, b) -> if (a == n) queue += b else if (b == n) queue += a }
        }
        return valleyClips.filter { it.id in seen }
    }

    /** Tap the fox: start every linked clip together on the next bar, or stop them. */
    fun foxTapped() {
        val group = foxGroup()
        if (group.isEmpty()) return message("Draw a line from the fox to a clip first")
        val anyPlaying = group.any { isPlaying(it) }
        val pairs = group.map { it to engine(it.engine) }
        Audio.post {
            if (anyPlaying) pairs.forEach { t.stopClip(it.first.id) }
            else {
                if (!t.playing) t.play()
                val at = Math.ceil(maxOf(0.0, t.tick) / Ticks.BAR) * Ticks.BAR
                pairs.forEach { (c, e) -> t.launch(c, e, at) }
            }
        }
    }

    // ------------------------------------------------------------------ audio files

    private var tape: AudioFiles.Tape? = null
    val taping get() = tape != null

    fun toggleTape() {
        val tp = tape
        if (tp == null) {
            tape = AudioFiles.Tape(ProjectStore.newTapeFile(project)); message("Recording audio…")
        } else {
            tape = null
            Thread {
                val f = tp.stop()
                ProjectStore.exportTape(project, f)
                message("Saved ${f.name} to Music/FoxBox/${project.name}/Audio")
            }.start()
        }
        changed()
    }

    fun setSample(e: Engine, data: FloatArray, name: String) {
        when (e) {
            Audio.reflections -> Audio.reflections.setSample(data, name)
            Audio.leaves -> { Audio.leaves.loaded = data; Audio.leaves.loadedName = name; Audio.leaves.source.set(2f) }
            else -> return
        }
        Thread { ProjectStore.saveSample(project, e.id, data) }.start()
        message("Loaded $name into ${e.title}")
        save(); changed()
    }

    // ------------------------------------------------------------------ MIDI input (any thread)

    private val midiHeld = java.util.concurrent.ConcurrentHashMap<Int, Pair<MidiInput.Dev, Int>>()
    private fun midiKey(d: MidiInput.Dev, ch: Int, note: Int) = 0x20000000 or ((d.id and 0xFF) shl 12) or (ch shl 8) or note
    private val padKeys = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private fun padChordKey(d: MidiInput.Dev, i: Int) = 0x31000000 or ((d.id and 0xFF) shl 8) or (i shl 3)

    override fun noteOn(dev: MidiInput.Dev, ch: Int, note: Int, vel: Float) {
        if (dev.role == MidiInput.Role.PADS) {
            val i = Math.floorMod(note - 36, 16)
            val key = 0x30000000 or (dev.id shl 8) or note
            padKeys[key] = i
            if (current === Audio.minds) noteOn(Audio.minds, key, 36 + i % 8, vel)
            else if (i < 7) chordOn(i, padChordKey(dev, i), vel)
            else noteOn(target, key, scaleNote(i - 7), vel)
            return
        }
        val key = midiKey(dev, ch, note)
        midiHeld[key] = dev to ch
        noteOn(target, key, note, vel)
    }

    override fun noteOff(dev: MidiInput.Dev, ch: Int, note: Int) {
        if (dev.role == MidiInput.Role.PADS) {
            val key = 0x30000000 or (dev.id shl 8) or note
            val i = padKeys.remove(key) ?: return
            if (current !== Audio.minds) { if (i < 7) chordOff(padChordKey(dev, i)) else noteOff(target, key) }
            return
        }
        val key = midiKey(dev, ch, note)
        midiHeld.remove(key)
        noteOff(target, key)
    }

    override fun expr(dev: MidiInput.Dev, ch: Int, note: Int, kind: Int, v: Float) {
        val e = target
        if (note >= 0) return expr(e, midiKey(dev, ch, note), kind, v)
        val all = dev.role == MidiInput.Role.MPE && ch == 0
        midiHeld.forEach { (k, dc) -> if (dc.first === dev && (all || dc.second == ch)) expr(e, k, kind, v) }
    }

    override fun cc(dev: MidiInput.Dev, ch: Int, cc: Int, v: Float) {
        val e = if (current === Audio.minds) return else current
        when (cc) { 1 -> e.y = v; 74, 71 -> e.x = v }
    }

    override fun devicesChanged() = changed()

    const val FOX = "fox"
}
