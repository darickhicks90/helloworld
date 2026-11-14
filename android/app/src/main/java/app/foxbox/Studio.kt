package app.foxbox

import android.os.Handler
import android.os.Looper
import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.audio.XyState
import app.foxbox.data.AudioFiles
import app.foxbox.data.Project
import app.foxbox.data.ProjectStore
import app.foxbox.engines.SampleEngine
import app.foxbox.fx.Flower
import app.foxbox.midi.MidiInput
import app.foxbox.seq.Clip
import app.foxbox.seq.Theory
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's controller: the open project, which page is in front, note and
 * chord routing, recording, clips, flowers, the Valley and saving. UI code
 * calls it on the main thread; anything that touches sound is forwarded to
 * the audio thread with Audio.post.
 */
object Studio : Transport.Listener, MidiInput.Sink {
    const val FOX = "fox"
    const val FLOWERS = "flowers"
    /** Most clips an engine can hold (keeps room for every critter in the Valley). */
    const val MAX_CLIPS = 3
    /** Most flowers planted in the Valley at once. */
    const val MAX_PLANTED = 5

    private val main = Handler(Looper.getMainLooper())
    private val t get() = Audio.transport

    lateinit var project: Project
        private set
    /** Pages on the rail: the engines, then Flowers. */
    val pages: List<String> = Audio.engines.map { it.id } + FLOWERS
    var page: String = "gusts"
        private set
    /** Engine in front (on the Flowers page: the last engine). */
    var current: Engine = Audio.gusts
        private set
    /** Melodic engine the play surface uses on pages without their own sound. */
    var melodic: Engine = Audio.gusts
        private set
    var flower: Flower? = null
        private set
    var auditioning = false
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
        Audio.flowers.active = emptyList(); Audio.flowers.audition = null; auditioning = false
        p.flowers.forEach { it.active = false }
        if (p.flowers.isEmpty()) p.flowers += Flower(name = "Dewdrop")
        // Older projects may have more than fit in the Valley: keep the first few there.
        Audio.engines.forEach { e -> p.clips.filter { it.engine == e.id && it.inValley }.drop(MAX_CLIPS).forEach { it.inValley = false } }
        p.flowers.filter { it.inValley }.drop(MAX_PLANTED).forEach { it.inValley = false }
        flower = p.flowers.first()
        applyKey()
        page = "gusts"; current = Audio.gusts; melodic = Audio.gusts
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

    fun select(pageId: String) {
        page = pageId
        Audio.engines.firstOrNull { it.id == pageId }?.let { e ->
            current = e
            if (e !== Audio.minds) melodic = e
        }
        if (pageId != FLOWERS && auditioning) setAudition(false)
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
        applyKey(); save(); changed()
    }
    private fun applyKey() {
        Audio.leaves.droneNote = 48 + project.root
        Audio.bumble.scale = scale; Audio.bumble.root = project.root
    }

    // ------------------------------------------------------------------ playing notes

    /** Engine the chord selector and strum pad play. */
    val target: Engine get() = if (page == FLOWERS || current === Audio.minds) melodic else current

    fun noteOn(e: Engine, key: Int, note: Int, vel: Float) = Audio.post { e.noteOn(key, note, vel); t.recNoteOn(e, key, note, vel) }
    fun noteOff(e: Engine, key: Int) = Audio.post { e.noteOff(key); t.recNoteOff(e, key) }
    fun expr(e: Engine, key: Int, kind: Int, v: Float) = Audio.post { e.expr(key, kind, v) }

    fun chordOn(degree: Int, keyBase: Int, vel: Float = 0.8f) {
        val e = target
        if (e === Audio.bumble) {
            // Bumble follows the chord: the sequence moves to it, or play its root.
            Audio.bumble.chordDegree = degree
            if (!Audio.bumble.enabled.on) noteOn(e, keyBase, Audio.bumble.noteFor(0), vel)
            changed(); return
        }
        chord(degree).notes.forEachIndexed { i, n -> noteOn(e, keyBase + i, n, vel) }
    }
    fun chordOff(keyBase: Int) { val e = target; for (i in 0 until 4) noteOff(e, keyBase + i) }

    fun setXy(xy: XyState, x: Float, y: Float, touching: Boolean) { xy.x = x; xy.y = y; xy.touching = touching }
    /** The XY pad shown on the current page. */
    val pad: XyState get() = if (page == FLOWERS) (flower ?: project.flowers.first()).xy else current.xy

    // ------------------------------------------------------------------ transport

    fun togglePlay() = Audio.post { if (t.playing) t.stop() else t.play() }
    fun recordProject() = Audio.post { t.record(null, project.loopBars * Ticks.BAR) }
    fun recordEngine(e: Engine) {
        val recording = t.rec != Transport.Rec.OFF
        if (!recording && clipsFor(e).size >= MAX_CLIPS) return message("${e.title} already has $MAX_CLIPS clips. Delete one (long-press it) to record another.")
        Audio.post { t.record(e, barsFor(e) * Ticks.BAR) }
    }
    fun barsFor(e: Engine) = clipBars[e.id] ?: 2
    fun setBarsFor(e: Engine, bars: Int) { clipBars[e.id] = bars.coerceIn(1, 16); changed() }

    fun setBpm(bpm: Float) { project.bpm = bpm.coerceIn(40f, 220f); t.bpm = project.bpm; save(); changed() }
    fun setLoopBars(b: Int) { project.loopBars = b.coerceIn(1, 16); save(); changed() }
    fun setMetronome(on: Boolean) { project.metronome = on; t.metronome = on; save(); changed() }
    fun setQuantize(on: Boolean) { project.quantize = on; t.quantize = on; save(); changed() }

    override fun onChanged() = changed()

    override fun onRecorded(clips: List<Clip>) {
        main.post {
            val full = clips.filter { c -> project.clips.count { it.engine == c.engine } >= MAX_CLIPS }
            if (full.isNotEmpty()) {
                Audio.post { full.forEach { t.stopClip(it.id) } }
                message(full.joinToString { engine(it.engine).title } + " already full ($MAX_CLIPS clips each), so those parts weren't kept")
            }
            (clips - full.toSet()).forEach { c ->
                val e = engine(c.engine)
                val n = project.clips.count { it.engine == c.engine } + 1
                c.name = "${e.title} $n"; c.variant = n - 1
                project.clips += c
                ProjectStore.saveClipMidi(project, c)
            }
            if (clips.size > full.size) message("Recorded " + (clips - full.toSet()).joinToString { it.name })
            save(); changed()
        }
    }

    // ------------------------------------------------------------------ clips

    fun engine(id: String): Engine = Audio.engines.first { it.id == id }
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
        project.clips.remove(c); dropLinks(c.id)
        save(); changed()
    }

    fun renameClip(c: Clip, name: String) { c.name = name.ifBlank { c.name }; save(); changed() }

    fun sendToValley(c: Clip) {
        c.inValley = true
        message("${c.name} is waiting in the Valley"); save(); changed()
    }

    // ------------------------------------------------------------------ flowers

    fun selectFlower(f: Flower) { flower = f; if (auditioning) { f.prepare(); Audio.flowers.audition = f }; changed() }

    fun newFlower(): Flower {
        val f = Flower(name = listOf("Dewdrop", "Poppy", "Bluebell", "Clover", "Foxglove", "Marigold", "Thistle", "Lily")[project.flowers.size % 8])
        f.variant = project.flowers.size
        project.flowers += f; selectFlower(f); save()
        return f
    }

    fun duplicateFlower(f: Flower) {
        val copy = Flower.fromJson(f.toJson().put("id", java.util.UUID.randomUUID().toString().take(8)).put("name", f.name + " II").put("valley", false))
        copy.active = false
        project.flowers += copy; selectFlower(copy); save()
    }

    fun renameFlower(f: Flower, name: String) { f.name = name.ifBlank { f.name }; save(); changed() }

    fun deleteFlower(f: Flower) {
        if (project.flowers.size <= 1) return message("Keep at least one flower")
        project.flowers.remove(f); dropLinks(f.id); f.active = false; syncRack()
        if (flower === f) flower = project.flowers.first()
        save(); changed()
    }

    fun plantFlower(f: Flower) {
        if (!f.inValley && project.flowers.count { it.inValley } >= MAX_PLANTED) return message("The Valley has room for $MAX_PLANTED flowers. Remove one first.")
        if (!f.inValley) f.inValley = true
        message("${f.name} is planted in the Valley"); save(); changed()
    }

    /** Hear the flower being edited at full bloom. */
    fun setAudition(on: Boolean) {
        auditioning = on
        val f = flower
        if (on && f != null) { f.prepare(); Audio.flowers.audition = f } else Audio.flowers.audition = null
        changed()
    }

    private fun syncRack() { Audio.flowers.active = project.flowers.filter { it.active } }

    fun setBloom(f: Flower, b: Float) { f.bloom = b.coerceIn(0f, 1f) }

    // ------------------------------------------------------------------ Valley

    val valleyClips get() = project.clips.filter { it.inValley }
    val valleyFlowers get() = project.flowers.filter { it.inValley }

    fun link(a: String, b: String) {
        if (a == b || project.links.any { (it.first == a && it.second == b) || (it.first == b && it.second == a) }) return
        project.links += a to b; save(); changed()
    }
    fun unlink(l: Pair<String, String>) { project.links.remove(l); save(); changed() }
    private fun dropLinks(id: String) {
        project.links.removeAll { it.first == id || it.second == id }
        project.nextSolo.remove(id)
    }

    /** Tap a critter or flower to add it to (or remove it from) the next scene on its own. */
    fun toggleInNextScene(id: String) {
        if (!project.nextSolo.remove(id)) project.nextSolo += id
        save(); changed()
    }

    /** Everything drawn into the next scene. */
    fun nextScene(): Set<String> {
        val ids = HashSet<String>(project.nextSolo)
        project.links.forEach { ids += it.first; ids += it.second }
        val valley = (valleyClips.map { it.id } + valleyFlowers.map { it.id }).toSet()
        return ids.intersect(valley)
    }

    fun removeFromValley(id: String) {
        project.clips.firstOrNull { it.id == id }?.let { c -> c.inValley = false; Audio.post { t.stopClip(c.id) } }
        project.flowers.firstOrNull { it.id == id }?.let { f -> f.inValley = false; f.active = false; syncRack() }
        dropLinks(id); save(); changed()
    }

    val sceneQueued get() = t.queuedAt >= 0

    /**
     * Tap the kitsune: queue the scene you drew. It starts when the current
     * scene's loop comes round (or straight away if nothing is playing).
     * Tap again while queued to cancel; with nothing drawn, the music stops
     * at the end of the loop.
     */
    fun foxTapped() {
        if (sceneQueued) { Audio.post { t.cancelScene() }; message("Next scene cancelled"); return }
        val ids = nextScene()
        if (ids.isEmpty()) {
            val everyFlower = project.flowers.toList()
            if (t.playing) { Audio.post { t.queueScene(emptyList(), true, Runnable { everyFlower.forEach { it.active = false }; Audio.flowers.active = emptyList() }) }; message("The music will stop at the end of this loop") }
            else message("Draw lines between critters and flowers (or tap them) to build the next scene")
            return
        }
        val clips = valleyClips.filter { it.id in ids }
        val flowers = valleyFlowers.filter { it.id in ids }
        flowers.forEach { it.prepare() }
        val pairs = clips.map { it to engine(it.engine) }
        val drawn = project.links.toList(); val solo = project.nextSolo.toSet()
        val everyFlower = project.flowers.toList()
        Audio.post {
            t.queueScene(pairs, false, Runnable {
                // Runs on the audio thread at the moment the scene changes.
                everyFlower.forEach { it.active = it in flowers }
                Audio.flowers.active = flowers
                main.post {
                    project.sceneLinks.clear(); project.sceneLinks += drawn
                    project.sceneIds.clear(); project.sceneIds += ids
                    project.links.removeAll(drawn); project.nextSolo.removeAll(solo)
                    save(); changed()
                }
            })
        }
        changed()
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

    fun setSample(e: SampleEngine, data: FloatArray, name: String) {
        e.setSample(data, name)
        if (e === Audio.leaves) Audio.leaves.source.set(0f)
        Thread { ProjectStore.saveSample(project, e.id, data) }.start()
        message("Loaded $name into ${e.title}")
        save(); changed()
    }

    // ------------------------------------------------------------------ MIDI input (any thread)

    private val midiHeld = ConcurrentHashMap<Int, Pair<MidiInput.Dev, Int>>()
    private fun midiKey(d: MidiInput.Dev, ch: Int, note: Int) = 0x20000000 or ((d.id and 0xFF) shl 12) or (ch shl 8) or note
    private val padKeys = ConcurrentHashMap<Int, Int>()
    private fun padChordKey(d: MidiInput.Dev, i: Int) = 0x31000000 or ((d.id and 0xFF) shl 8) or (i shl 3)

    override fun noteOn(dev: MidiInput.Dev, ch: Int, note: Int, vel: Float) {
        if (dev.role == MidiInput.Role.PADS) {
            val i = Math.floorMod(note - 36, 16)
            val key = 0x30000000 or (dev.id shl 8) or note
            padKeys[key] = i
            if (page == "minds") noteOn(Audio.minds, key, 36 + i % 8, vel)
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
            if (page != "minds") { if (i < 7) chordOff(padChordKey(dev, i)) else noteOff(target, key) }
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

    /** CC1 and CC74 move the current XY pad's Y and X targets. */
    override fun cc(dev: MidiInput.Dev, ch: Int, cc: Int, v: Float) {
        val xy = pad
        when (cc) { 1 -> xy.yParam.norm = v; 74, 71 -> xy.xParam.norm = v }
    }

    override fun devicesChanged() = changed()
}
