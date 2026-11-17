package app.foxbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.foxbox.audio.Audio
import app.foxbox.audio.AudioPrefs
import app.foxbox.audio.Engine
import app.foxbox.audio.SR
import app.foxbox.audio.XyState
import app.foxbox.blocks.Block
import app.foxbox.blocks.Blocks
import app.foxbox.data.AudioFiles
import app.foxbox.data.AudioIn
import app.foxbox.data.Banks
import app.foxbox.engines.Chop
import app.foxbox.data.DaisyChain
import app.foxbox.data.Project
import app.foxbox.data.ProjectStore
import app.foxbox.data.Store
import app.foxbox.engines.Pickup
import app.foxbox.engines.SampleEngine
import app.foxbox.fx.Flower
import app.foxbox.fx.FlowerRack
import app.foxbox.midi.MidiInput
import app.foxbox.midi.MidiMap
import app.foxbox.seq.AudioClip
import app.foxbox.seq.Clip
import app.foxbox.seq.NoteSeq
import app.foxbox.seq.Theory
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil

/**
 * The app's controller: the open project, the session of blocks, which page
 * is in front, note and chord routing, recording (MIDI clips and audio
 * takes), Clibbins (queue, mixer, daisy chains), undo and saving. UI code
 * calls it on the main thread; anything that touches sound is forwarded to
 * the audio thread with Audio.post.
 */
object Studio : Transport.Listener, MidiInput.Sink {
    const val FLOWERS = "flowers"
    /** Most clips a block can hold (keeps room for every sprite in Clibbins). */
    const val MAX_CLIPS = 3
    /** Most flowers planted in Clibbins at once. */
    const val MAX_PLANTED = 5
    const val MAX_CHAINS = 3
    val CHANNEL_NAMES = listOf("Red", "Yellow", "Blue")

    private val main = Handler(Looper.getMainLooper())
    private val t get() = Audio.transport
    lateinit var app: Context

    lateinit var project: Project
        private set
    val isOpen get() = ::project.isInitialized
    /** Pages on the rail: the session's blocks, then Flowers. */
    val pages: List<String> get() = project.blocks + FLOWERS
    var page: String = "gusts"
        private set
    /** Block in front (on the Flowers page: the last block shown). */
    var current: Engine = Audio.gusts
        private set
    /** Melodic block the play surface uses on pages without their own notes. */
    var melodic: Engine = Audio.gusts
        private set
    var flower: Flower? = null
        private set
    var auditioning = false
        private set
    /** Bars for the next clip recorded on each block. */
    val clipBars = HashMap<String, Int>()
    /** Biplanes armed for the next audio take. */
    val armed = BooleanArray(AudioClip.CHANNELS)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    var onMessage: ((String) -> Unit)? = null
    /** Asks the activity for the record-audio permission, then runs the callback. */
    var needMic: ((() -> Unit) -> Unit)? = null
    fun listen(fn: () -> Unit) = listeners.add(fn)
    fun unlisten(fn: () -> Unit) = listeners.remove(fn)
    fun changed() { main.post { listeners.forEach { it() } } }
    override fun message(text: String) { main.post { onMessage?.invoke(text) } }

    init { t.listener = this }

    fun init(c: Context) { app = c.applicationContext }

    // ------------------------------------------------------------------ projects

    fun open(p: Project) {
        Audio.post { t.stop() }
        project = p
        p.applyEngines()
        Audio.flowers.audition = null; auditioning = false
        if (p.flowers.isEmpty()) p.flowers += Flower(name = "Dewdrop")
        Blocks.types.forEach { b -> p.clips.filter { it.engine == b.id && it.inClibbins }.drop(MAX_CLIPS).forEach { it.inClibbins = false } }
        p.flowers.filter { it.inClibbins }.drop(MAX_PLANTED).forEach { it.inClibbins = false }
        flower = p.flowers.first()
        armed.fill(false)
        // The three audio tracks always exist (like tracks in a DAW).
        p.planes.fill(true)
        applyKey()
        syncRack()
        val first = p.blocks.firstOrNull() ?: "gusts"
        page = first; current = Audio.engine(first)
        melodic = p.blocks.map { Audio.engine(it) }.firstOrNull { Blocks.get(it.id)?.melodic == true } ?: Audio.gusts
        history.clear(); historyAt = -1; snapshot()
        changed()
    }

    private val saver = Runnable {
        if (!isOpen) return@Runnable
        project.captureEngines(); snapshot(); ProjectStore.save(project)
    }
    fun save(now: Boolean = false) {
        main.removeCallbacks(saver)
        if (now) saver.run() else main.postDelayed(saver, 800)
    }

    fun select(pageId: String) {
        page = pageId
        if (pageId != FLOWERS) {
            val e = Audio.engine(pageId)
            current = e
            if (Blocks.get(e.id)?.melodic == true) melodic = e
        }
        if (pageId != FLOWERS && auditioning) setAudition(false)
        changed()
    }

    // ------------------------------------------------------------------ undo / redo

    private val history = ArrayList<String>()
    private var historyAt = -1
    private var restoring = false
    val canUndo get() = historyAt > 0
    val canRedo get() = historyAt < history.size - 1

    private fun snapshot() {
        if (restoring) return
        val s = project.toJson().apply { remove("modified") }.toString()
        if (historyAt >= 0 && history[historyAt] == s) return
        while (history.size > historyAt + 1) history.removeAt(history.size - 1)
        history += s; historyAt = history.size - 1
        if (history.size > 40) { history.removeAt(0); historyAt-- }
    }

    fun undo() { if (canUndo) restore(--historyAt) else message("Nothing to undo") }
    fun redo() { if (canRedo) restore(++historyAt) else message("Nothing to redo") }

    private fun restore(i: Int) {
        restoring = true
        try {
            val keepPage = page
            Audio.post { t.stop() }
            project.fromJson(JSONObject(history[i]))
            project.applyEngines()
            applyKey(); syncRack()
            if (flower !in project.flowers) flower = project.flowers.firstOrNull()
            select(if (keepPage in pages) keepPage else pages.first())
            ProjectStore.save(project)
        } finally { restoring = false }
        changed()
    }

    // ------------------------------------------------------------------ blocks

    val blocks: List<Block> get() = project.blocks.mapNotNull { Blocks.get(it) }
    val canAddBlock get() = project.blocks.size < Blocks.MAX
    /** Blocks that could be added: built in or owned, not already in the session. */
    fun addableBlocks() = Blocks.all.filter { it.id !in project.blocks && Store.owns(it.type.productId) }

    fun addBlock(id: String) {
        if (!canAddBlock) return message("A session holds ${Blocks.MAX} blocks. Remove one first.")
        if (id in project.blocks) return
        project.blocks += id
        Audio.setActive(project.blocks)
        select(id); save(); changed()
    }

    /** Remove a block from the session. Its clips are kept and come back with it. */
    fun removeBlock(id: String) {
        if (project.blocks.size <= 1) return message("Keep at least one block")
        val e = Audio.engine(id)
        Audio.post { project.clips.filter { it.engine == id }.forEach { t.stopClip(it.id) }; e.allOff() }
        project.blocks.remove(id)
        project.queued.removeAll(project.clips.filter { it.engine == id }.map { it.id }.toSet())
        Audio.setActive(project.blocks)
        syncRack()
        if (page == id) select(project.blocks.first())
        if (melodic === e) melodic = blocks.firstOrNull { it.melodic }?.engine ?: Audio.gusts
        message("${e.title} was laid to rest"); save(); changed()
    }

    fun moveBlock(id: String, to: Int) {
        if (!project.blocks.remove(id)) return
        project.blocks.add(to.coerceIn(0, project.blocks.size), id)
        save(); changed()
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
        NoteSeq.scale = scale; NoteSeq.root = project.root
    }

    // ------------------------------------------------------------------ playing notes

    /** Block the chord selector and strum pad play. */
    val target: Engine get() = if (page == FLOWERS || Blocks.get(current.id)?.melodic != true) melodic else current

    fun noteOn(e: Engine, key: Int, note: Int, vel: Float) = Audio.post { e.noteOn(key, note, vel); t.recNoteOn(e, key, note, vel) }
    fun noteOff(e: Engine, key: Int) = Audio.post { e.noteOff(key); t.recNoteOff(e, key) }
    fun expr(e: Engine, key: Int, kind: Int, v: Float) = Audio.post { e.expr(key, kind, v) }

    fun chordOn(degree: Int, keyBase: Int, vel: Float = 0.8f) {
        val e = target
        val seq = e.seq
        if (seq != null && seq.enabled.on) {
            // A running step sequence follows the chord you tap.
            seq.chordDegree = degree; changed(); return
        }
        if (e === Audio.bumble) { noteOn(e, keyBase, chord(degree).notes.first(), vel); return }
        chord(degree).notes.forEachIndexed { i, n -> noteOn(e, keyBase + i, n, vel) }
    }
    fun chordOff(keyBase: Int) { val e = target; for (i in 0 until 4) noteOff(e, keyBase + i) }

    fun setXy(xy: XyState, x: Float, y: Float, touching: Boolean) {
        xy.x = x; xy.y = y
        if (touching) xy.touching = true else { xy.release(); save() }
    }
    /** The XY pad shown on the current page. */
    val pad: XyState get() = if (page == FLOWERS) (flower ?: project.flowers.first()).xy else current.xy

    // ------------------------------------------------------------------ transport

    fun play() = Audio.post { if (!t.playing) t.play() }
    fun stop() = Audio.post { t.stop() }
    fun togglePlay() = Audio.post { if (t.playing) t.stop() else t.play() }

    /** MIDI record: records the block in front for its clip length. Again to finish early. */
    fun recordMidi() {
        val e = if (page == FLOWERS) melodic else current
        val recording = t.rec != Transport.Rec.OFF
        if (!recording && clipsFor(e).size >= MAX_CLIPS) return message("${e.title} already has $MAX_CLIPS clips. Lay one to rest (hold it, drag to R.I.P.) to record another.")
        Audio.post { t.record(e, barsFor(e) * Ticks.BAR) }
    }
    fun barsFor(e: Engine) = clipBars[e.id] ?: 2
    fun setBarsFor(e: Engine, bars: Int) { clipBars[e.id] = bars.coerceIn(1, 16); changed() }

    fun setBpm(bpm: Float) { project.bpm = bpm.coerceIn(40f, 220f); t.bpm = project.bpm; save(); changed() }
    fun setMetronome(on: Boolean) { project.metronome = on; t.metronome = on; save(); changed() }

    override fun onChanged() { main.post { tapeFollowsTransport() }; changed() }

    // ------------------------------------------------------------------ the master track: mixdown to tape

    /** While the master track is armed, playing records the whole mix; stopping exports it as the song. */
    var masterArmed = false
        private set

    fun toggleMasterArm() {
        masterArmed = !masterArmed
        message(if (masterArmed) "Master armed: press play and FoxBox records the mix to tape. Stop to export the song." else "Master disarmed")
        tapeFollowsTransport(); changed()
    }

    private var monitorIn: AudioIn? = null

    /**
     * Live input for armed tracks: while the tape is rolling (or a take is
     * recording), what comes in on each armed Red/Yellow/Blue track goes on
     * its meter and into the mixdown.
     */
    fun refreshLive() {
        if (!isOpen) return
        for (ch in 0 until 3) { Audio.liveArm[ch] = armed[ch]; Audio.liveInput[ch] = AudioPrefs.planeInput(ch) }
        val take = t.audioIn
        if (take != null) { monitorIn?.close(); monitorIn = null; Audio.liveIn = take; return }
        val need = tape != null && armed.any { it }
        if (need && monitorIn == null) {
            if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                message("Allow the microphone (tap ● Rec once) so armed tracks can be mixed down"); return
            }
            monitorIn = try { AudioIn(app, AudioPrefs.inputDevice, keep = false) } catch (e: Exception) { message(e.message ?: "Couldn't open the audio input"); null }
        } else if (!need) { monitorIn?.close(); monitorIn = null }
        Audio.liveIn = monitorIn
    }

    private fun tapeFollowsTransport() {
        if (!isOpen) return
        val rolling = masterArmed && t.playing
        if (rolling && tape == null) {
            tape = AudioFiles.Tape(ProjectStore.newTapeFile(project)); message("Mixing down to tape…"); changed()
        } else if (!rolling && tape != null) {
            val tp = tape!!; tape = null
            Thread {
                val f = tp.stop()
                ProjectStore.exportTape(project, f)
                message("Song exported: ${f.name} in ${ProjectStore.location.label}/${project.name}/Audio")
            }.start()
            changed()
        }
        refreshLive()
    }

    /** Rename a clip or take. */
    fun renameItem(id: String, name: String) {
        if (name.isBlank()) return
        clipById(id)?.let { it.name = name.trim() }
        takeById(id)?.let { it.name = name.trim() }
        save(); changed()
    }
    fun nameOf(id: String) = clipById(id)?.name ?: takeById(id)?.name ?: ""

    fun panOf(id: String) = clipById(id)?.pan ?: takeById(id)?.pan ?: 0f
    fun setPan(id: String, v: Float) {
        val p = v.coerceIn(-1f, 1f).let { if (kotlin.math.abs(it) < 0.06f) 0f else it }
        clipById(id)?.pan = p; takeById(id)?.pan = p
    }

    override fun onRecorded(clips: List<Clip>) {
        main.post {
            val full = clips.filter { c -> project.clips.count { it.engine == c.engine } >= MAX_CLIPS }
            if (full.isNotEmpty()) {
                Audio.post { full.forEach { t.stopClip(it.id) } }
                message(full.joinToString { engine(it.engine).title } + " already full ($MAX_CLIPS clips each), so those parts weren't kept")
            }
            val kept = clips - full.toSet()
            kept.forEach { c ->
                val e = engine(c.engine)
                val n = project.clips.count { it.engine == c.engine } + 1
                c.name = "${e.title} $n"; c.variant = n - 1; c.inClibbins = true
                project.clips += c
                ProjectStore.saveClipMidi(project, c)
            }
            if (kept.isNotEmpty()) message("Recorded " + kept.joinToString { it.name })
            save(); changed()
        }
    }

    // ------------------------------------------------------------------ audio takes (the biplanes)

    val audioRecording get() = t.audioRec != Transport.Rec.OFF
    fun audioClipsOn(ch: Int) = project.audioClips.filter { it.channel == ch }
    fun secondsOn(ch: Int) = audioClipsOn(ch).sumOf { it.seconds.toDouble() }.toFloat()

    fun addPlane(): Int {
        val ch = project.planes.indexOfFirst { !it }
        if (ch < 0) { message("All three audio channels are in use"); return -1 }
        project.planes[ch] = true; armed[ch] = true
        message("${CHANNEL_NAMES[ch]} channel added and armed: tap REC to record")
        save(); changed(); return ch
    }

    fun removePlane(ch: Int) {
        project.planes[ch] = false; armed[ch] = false
        val gone = audioClipsOn(ch)
        Audio.post { gone.forEach { t.stopClip(it.id) } }
        message("${CHANNEL_NAMES[ch]} channel removed. Its takes come back if you add it again.")
        syncRack(); save(); changed()
    }

    /** The master fader: the level of everything FoxBox plays. */
    fun setAudioMaster(v: Float) { project.audioMaster = v.coerceIn(0f, 1f); Audio.masterVolume = project.audioMaster }

    fun toggleArm(ch: Int) { if (project.planes[ch]) { armed[ch] = !armed[ch]; refreshLive(); changed() } }

    /** Water tower / audio rec: 2 bars of count-in, then record the armed biplanes. Again to stop. */
    fun recordAudio() {
        if (audioRecording) { Audio.post { t.recordAudio() }; return }
        if (armed.none { it }) {
            val ch = project.planes.indexOfFirst { it }.takeIf { it >= 0 } ?: addPlane()
            if (ch < 0) return
            armed[ch] = true
        }
        val full = armed.indices.filter { armed[it] && secondsOn(it) >= AudioClip.MAX_SECONDS - 1 }
        if (full.isNotEmpty()) return message(full.joinToString { CHANNEL_NAMES[it] } + " already holds 6 minutes. Lay a take to rest first.")
        val ask = needMic
        if (ask != null) ask { startTake() } else startTake()
    }

    private fun startTake() {
        monitorIn?.close(); monitorIn = null; Audio.liveIn = null
        val input = try { AudioIn(app, AudioPrefs.inputDevice) } catch (e: Exception) {
            return message(e.message ?: "Couldn't open the audio input")
        }
        val latency = Audio.latencyMs + 12f + AudioPrefs.offsetMs
        Audio.post {
            t.audioIn = input
            t.latencyFrames = (latency * SR / 1000f).toInt().coerceAtLeast(0)
            t.recordAudio()
        }
        Audio.liveIn = input
        message("Two bars of count-in, then recording " + armed.indices.filter { armed[it] }.joinToString { CHANNEL_NAMES[it] })
        changed()
    }

    override fun onAudioTakeEnded() {
        val input = t.audioIn ?: return
        t.audioIn = null
        Audio.liveIn = null
        val chans = armed.indices.filter { armed[it] && project.planes[it] }
        val bpm = project.bpm
        Thread {
            val takes = input.finish()
            val barFrames = SR * 60.0 / bpm * 4
            val made = ArrayList<AudioClip>()
            for (ch in chans) {
                val raw = takes[AudioPrefs.planeInput(ch).coerceIn(0, 2)]
                if (raw.size < SR / 4) continue
                // Round up to whole bars so takes loop in time, within the channel's 6 minutes.
                val room = ((AudioClip.MAX_SECONDS - secondsOn(ch)) * SR).toInt()
                var len = (ceil(raw.size / barFrames) * barFrames).toInt()
                while (len > room && len > barFrames) len -= barFrames.toInt()
                if (len <= 0 || room <= 0) { message("${CHANNEL_NAMES[ch]} is full (6 minutes)"); continue }
                val data = raw.copyOf(minOf(len, room))
                val n = audioClipsOn(ch).size + made.count { it.channel == ch } + 1
                val c = AudioClip(channel = ch, name = "${CHANNEL_NAMES[ch]} take $n").apply { this.data = data }
                ProjectStore.saveTake(project, c)
                made += c
            }
            main.post {
                project.audioClips += made
                made.forEach { project.queued += it.id }
                if (made.isNotEmpty()) message("Recorded " + made.joinToString { it.name } + " (queued in Clibbins)")
                else message("No audio came in. Check the input in Audio settings.")
                refreshLive()
                save(); changed()
            }
        }.start()
    }

    // ------------------------------------------------------------------ clips

    fun engine(id: String): Engine = Audio.engine(id)
    fun clipsFor(e: Engine) = project.clips.filter { it.engine == e.id }
    fun isPlaying(id: String) = id in t.playing_ids
    fun isPlaying(c: Clip) = isPlaying(c.id)

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
        project.clips.remove(c); dropFromClibbins(c.id)
        save(); changed()
    }

    fun deleteTake(c: AudioClip) {
        Audio.post { t.stopClip(c.id) }
        project.audioClips.remove(c); dropFromClibbins(c.id)
        ProjectStore.deleteTake(project, c)
        save(); changed()
    }

    fun renameClip(c: Clip, name: String) { c.name = name.ifBlank { c.name }; save(); changed() }

    fun sendToClibbins(c: Clip) {
        c.inClibbins = true; save(); changed()
    }

    // ------------------------------------------------------------------ flowers

    fun selectFlower(f: Flower) { flower = f; if (auditioning) Audio.flowers.audition = f; changed() }

    fun newFlower(): Flower {
        val f = Flower(name = listOf("Dewdrop", "Poppy", "Bluebell", "Clover", "Foxglove", "Marigold", "Thistle", "Lily")[project.flowers.size % 8])
        f.variant = project.flowers.size
        project.flowers += f; selectFlower(f); save()
        return f
    }

    fun duplicateFlower(f: Flower) {
        val copy = Flower.fromJson(f.toJson().put("id", java.util.UUID.randomUUID().toString().take(8)).put("name", f.name + " II").put("valley", false))
        project.flowers += copy; selectFlower(copy); save()
    }

    fun renameFlower(f: Flower, name: String) { f.name = name.ifBlank { f.name }; save(); changed() }

    fun deleteFlower(f: Flower) {
        if (project.flowers.size <= 1) return message("Keep at least one flower")
        project.flowers.remove(f); dropFromClibbins(f.id)
        if (flower === f) flower = project.flowers.first()
        save(); changed()
    }

    fun plantFlower(f: Flower) {
        if (!f.inClibbins && project.flowers.count { it.inClibbins } >= MAX_PLANTED) return message("The garden has room for $MAX_PLANTED flowers. Remove one first.")
        f.inClibbins = true
        message("${f.name} is planted in the garden: in Clibbins, drag a line from it through clips to make a daisy chain"); save(); changed()
    }

    /** Hear the flower being edited on everything, at full bloom. */
    fun setAudition(on: Boolean) {
        auditioning = on
        Audio.flowers.audition = if (on) flower else null
        changed()
    }

    fun setBloom(f: Flower, b: Float) { f.bloom = b.coerceIn(0f, 1f) }

    // ------------------------------------------------------------------ Clibbins

    val clibbinsClips get() = project.clips.filter { it.engine in project.blocks }
    val clibbinsFlowers get() = project.flowers.filter { it.inClibbins }
    val clibbinsTakes get() = project.audioClips.filter { project.planes[it.channel] }

    fun isQueued(id: String) = id in project.queued
    fun toggleQueued(id: String) { if (!project.queued.remove(id)) project.queued += id; save(); changed() }

    fun clipById(id: String) = project.clips.firstOrNull { it.id == id }
    fun takeById(id: String) = project.audioClips.firstOrNull { it.id == id }
    fun volumeOf(id: String) = clipById(id)?.volume ?: takeById(id)?.volume ?: 1f
    fun mutedOf(id: String) = clipById(id)?.muted ?: takeById(id)?.muted ?: false
    fun setVolume(id: String, v: Float) {
        val x = v.coerceIn(0f, 1f)
        clipById(id)?.volume = x; takeById(id)?.volume = x
        save()
    }
    fun toggleMute(id: String) {
        clipById(id)?.let { it.muted = !it.muted; message(it.name + if (it.muted) " muted" else " unmuted") }
        takeById(id)?.let { it.muted = !it.muted; message(it.name + if (it.muted) " muted" else " unmuted") }
        save(); changed()
    }

    /** Lay a Clibbins item to rest: clips and takes are deleted, flowers go back to the Flowers page. */
    fun bury(id: String) {
        clipById(id)?.let { c -> deleteClip(c); message("${c.name} was laid to rest"); return }
        takeById(id)?.let { c -> deleteTake(c); message("${c.name} was laid to rest"); return }
        project.flowers.firstOrNull { it.id == id }?.let { f -> f.inClibbins = false; dropFromClibbins(f.id); message("${f.name} went back to the Flowers page"); save(); changed() }
    }

    private fun dropFromClibbins(id: String) {
        project.queued.remove(id)
        project.chains.removeAll { it.flowerId == id }
        project.chains.forEach { it.clipIds.remove(id) }
        syncRack()
    }

    /** A daisy chain: the line drawn from a flower ties every clip it passed through to that flower. */
    fun makeChain(flowerId: String, clipIds: List<String>, path: List<FloatArray>) {
        project.chains.removeAll { it.flowerId == flowerId }
        if (clipIds.isEmpty()) { syncRack(); save(); changed(); return }
        if (project.chains.size >= MAX_CHAINS) return message("The garden holds $MAX_CHAINS daisy chains. Double-tap a flower to undo its chain.")
        project.chains += DaisyChain(flowerId, clipIds.distinct().toMutableList(), path.toMutableList())
        val f = project.flowers.first { it.id == flowerId }
        message("${f.name} now colours " + clipIds.distinct().mapNotNull { clipById(it)?.name ?: takeById(it)?.name }.joinToString())
        syncRack(); save(); changed()
    }

    fun clearChain(flowerId: String) {
        if (project.chains.removeAll { it.flowerId == flowerId }) { syncRack(); save(); changed() }
    }

    fun chainIndex(flowerId: String) = project.chains.indexOfFirst { it.flowerId == flowerId }

    /** Route blocks and audio channels through their daisy chains' flowers. */
    private fun syncRack() {
        val rack = Audio.flowers
        val old = rack.chains
        val chains = arrayOfNulls<FlowerRack.Chain>(3)
        val routes = HashMap<String, Int>()
        val channel = IntArray(3) { -1 }
        project.chains.take(3).forEachIndexed { k, ch ->
            val f = project.flowers.firstOrNull { it.id == ch.flowerId && it.inClibbins } ?: return@forEachIndexed
            chains[k] = old.firstOrNull { it?.flower === f } ?: FlowerRack.Chain(f)
            ch.clipIds.forEach { id ->
                clipById(id)?.let { if (it.engine in project.blocks) routes.putIfAbsent(it.engine, k) }
                takeById(id)?.let { if (channel[it.channel] < 0) channel[it.channel] = k }
            }
        }
        Audio.post { rack.chains = chains; rack.blockRoute = routes; rack.channelRoute = channel }
    }

    /** Shuffle: everything in Clibbins moves to new spots. */
    fun shuffleLayout() { project.layoutSeed = kotlin.random.Random.nextInt(1, 1_000_000); save(); changed() }

    val sceneQueued get() = t.queuedAt >= 0

    /** The roof: play what's queued. Starts now if stopped, or when the current loop comes round. */
    fun playScene() {
        if (sceneQueued) { Audio.post { t.cancelScene() }; message("Next scene cancelled"); return }
        val clips = clibbinsClips.filter { it.id in project.queued }
        val takes = clibbinsTakes.filter { it.id in project.queued }
        if (clips.isEmpty() && takes.isEmpty()) {
            if (!t.playing) { play(); message("Playing. Tap clips to queue them, then tap ▶ Play again.") }
            else message("Tap clips to queue them first")
            return
        }
        val pairs = clips.map { it to engine(it.engine) }
        Audio.post { t.queueScene(pairs, false, null, takes) }
        if (t.playing) message("Next scene starts when this loop comes round")
        changed()
    }

    /**
     * Going to Clibbins: the music keeps playing, but block sequencers that
     * aren't latched switch off so they don't play over the scenes.
     */
    fun enterClibbins() {
        val off = Audio.active.filter { e -> e.seqSwitch?.on == true && e.seqLatch?.on != true }
        if (off.isEmpty()) return
        off.forEach { it.seqSwitch?.set(0f) }
        Audio.post { off.forEach { it.allOff() } }
        message("Sequencers off: " + off.joinToString { it.title } + " (latch a sequencer to keep it running in Clibbins)")
        save(); changed()
    }

    /** The wall: stop everything. */
    fun stopScene() = Audio.post { t.stop() }

    // ------------------------------------------------------------------ audio files

    private var tape: AudioFiles.Tape? = null
    val taping get() = tape != null

    fun setSample(e: SampleEngine, data: FloatArray, name: String) {
        e.setSample(data, name)
        if (e === Audio.reflections) Audio.reflections.sampleBpm = 0f   // a new sample isn't tied to a tempo until a looper take sets it
        if (e === Audio.leaves) Audio.leaves.source.set(0f)
        Thread { ProjectStore.saveSample(project, e.id, data) }.start()
        message("Loaded $name into ${e.title}")
        save(); changed()
    }

    // ------------------------------------------------------------------ sampler mic takes (Reflections, Leaves)

    /** The sampler currently recording from the mic, or null. */
    @Volatile var samplerTarget: SampleEngine? = null
        private set
    private var samplerIn: AudioIn? = null

    /**
     * The Mic button on Reflections / Leaves. From stop: a 4-beat count-in, then
     * record until pressed again. While the clock runs, Reflections records as a
     * looper (starts on a quarter note, ends on whole bars, comes back as a
     * tempo-synced loop); Leaves records from the press. Press again to stop.
     */
    fun samplerMic(e: SampleEngine) {
        val cur = samplerTarget
        if (cur != null) {
            if (cur === e) Audio.post { t.recordSampler(false) } else message("${cur.title} is recording from the mic: stop it first")
            return
        }
        val input = try { AudioIn(app, AudioPrefs.inputDevice) } catch (ex: Exception) { return message(ex.message ?: "Couldn't open the microphone") }
        samplerIn = input; samplerTarget = e
        val looper = e === Audio.reflections
        val latency = Audio.latencyMs + 12f + AudioPrefs.offsetMs
        val running = t.playing
        Audio.post {
            t.sampIn = input
            t.latencyFrames = (latency * SR / 1000f).toInt().coerceAtLeast(0)
            t.recordSampler(looper)
        }
        message(when {
            !running -> "Count-in… recording into ${e.title} from bar 1. Tap Mic to stop."
            looper -> "Recording a loop from the next beat. Tap Mic to stop: it ends on a whole bar."
            else -> "Recording into ${e.title}. Tap Mic to stop."
        })
        changed()
    }

    override fun onSamplerTakeEnded(startTick: Double, endTick: Double) {
        val input = samplerIn ?: return
        val e = samplerTarget ?: return
        samplerIn = null; t.sampIn = null
        val looper = t.sampQuantize && e === Audio.reflections
        val bpm = t.bpm
        Thread {
            val raw = try { input.finish()[2] } catch (_: Exception) { FloatArray(0) }
            main.post { samplerTarget = null; changed() }
            if (startTick < 0 || endTick < 0) return@Thread message("Mic take cancelled")
            if (raw.size < SR / 10) return@Thread message("No audio came in. Check the input in Audio settings.")
            val data = if (looper) raw.copyOf(((endTick - startTick) * SR * 60.0 / (bpm * Ticks.PPQ)).toInt().coerceAtLeast(1)) else raw
            // 3 ms fades at the edges so the start and the loop point don't click
            val fade = minOf(data.size / 2, (SR * 0.003f).toInt())
            for (i in 0 until fade) { val g = i / fade.toFloat(); data[i] *= g; data[data.size - 1 - i] *= g }
            AudioFiles.normalize(data)
            main.post { if (looper) applyLoopTake(data, bpm, startTick, endTick) else setSample(e, data, "Mic take") }
        }.start()
    }

    /** A Reflections looper take: tempo-synced time stretch, looping, played back right away by a new clip. */
    private fun applyLoopTake(data: FloatArray, bpm: Float, startTick: Double, endTick: Double) {
        val r = Audio.reflections
        val len = (endTick - startTick).toInt()
        val bars = (len / Ticks.BAR).coerceAtLeast(1)
        setSample(r, data, "Loop $bars bar" + if (bars > 1) "s" else "")
        r.sampleBpm = bpm
        val root = scaleNote(0)
        mapOf("stretch" to 1f, "loop" to 1f, "speed" to 1f, "start" to 0f, "end" to 1f, "reverse" to 0f, "root" to root.toFloat(), "tune" to 0f)
            .forEach { (k, v) -> r.p.all[k]?.set(v) }
        if (clipsFor(r).size >= MAX_CLIPS) { message("Loop ready on ${Theory.noteNameOct(root)}. Reflections already has $MAX_CLIPS clips, so it isn't playing on its own."); return }
        val clip = Clip(engine = r.id, name = "", lengthTicks = len, notes = mutableListOf(Clip.Note(0, len, root, 0.9f)))
        val n = clipsFor(r).size + 1
        clip.name = "Loop $n"; clip.variant = n - 1; clip.inClibbins = true
        project.clips += clip
        ProjectStore.saveClipMidi(project, clip)
        // Keep the loop in phase with where it was recorded.
        Audio.post { if (t.playing) t.launch(clip, r, startTick + ceil((t.tick - startTick) / len) * len) }
        message("Loop recorded: ${clip.name} plays it in time (note ${Theory.noteNameOct(root)} plays the original)")
        save(); changed()
    }

    // ------------------------------------------------------------------ Pickup pads

    @Volatile var selectedPad = 0
    fun selectPad(i: Int) { selectedPad = i.coerceIn(0, Pickup.PADS - 1); changed() }
    /** A pad pressed on screen; [releasePad] when the finger lifts (pads set to Hold stop then). */
    fun hitPad(i: Int, vel: Float) {
        val k = 0x32000000 or i
        noteOn(Audio.pickup, k, 36 + i, vel)
    }
    fun releasePad(i: Int) = noteOff(Audio.pickup, 0x32000000 or i)
    /** Write a pad's sound into the project (no export copy). */
    private fun storePad(i: Int, data: FloatArray?) {
        val f = java.io.File(project.samplesDir, "pickup_pad$i.wav")
        if (data == null) f.delete() else { project.samplesDir.mkdirs(); AudioFiles.writeWav(f, data) }
    }

    /** Load a bank onto the pads (call on the main thread). */
    fun applyBank(bank: Banks.Bank) {
        val pu = Audio.pickup
        val padParams = pu.p.all.values.filter { Regex("^p\\d+[a-z]+$").matches(it.id) }
        if (bank.builtIn) {
            pu.kitId = bank.kit!!
            pu.resetKit()
            padParams.forEach { it.set(it.def) }
            Thread { for (i in 0 until Pickup.PADS) storePad(i, null) }.start()
            message("Loaded the ${bank.name} bank"); save(); changed(); return
        }
        message("Loading ${bank.name}…")
        Thread {
            val full = runCatching { Banks.load(bank) }.getOrNull() ?: return@Thread message("Couldn't read that bank")
            main.post {
                for (i in 0 until Pickup.PADS) full.samples.getOrNull(i)?.let { pu.setPadSample(i, it, full.names.getOrElse(i) { "Pad ${i + 1}" }) }
                padParams.forEach { p -> p.set(if (full.params.has(p.id)) full.params.getDouble(p.id).toFloat() else p.def) }
                Thread { for (i in 0 until Pickup.PADS) full.samples.getOrNull(i)?.let { storePad(i, it) } }.start()
                message("Loaded the ${full.name} bank"); save(); changed()
            }
        }.start()
    }

    /** Put several sounds onto pads, starting at `first`. */
    fun fillPads(first: Int, sounds: List<Pair<String, FloatArray>>) {
        val pu = Audio.pickup
        val n = minOf(sounds.size, Pickup.PADS - first)
        for (k in 0 until n) {
            val (name, d) = sounds[k]
            pu.setPadSample(first + k, d, name)
            listOf("start" to 0f, "rev" to 0f).forEach { (id, v) -> pu.p.all["p${first + k}$id"]?.set(v) }
            pu.padEnd[first + k].set(1f)
        }
        val saved = (0 until n).map { first + it to sounds[it].second }
        Thread { saved.forEach { (i, d) -> storePad(i, d) } }.start()
        save(); changed()
        if (n < sounds.size) message("${sounds.size} sounds, but only $n pads from pad ${first + 1}: the rest were left out")
    }

    private var chopUndo: Pair<Array<FloatArray>, Array<String>>? = null
    val canUndoChop get() = chopUndo != null

    /** Chop the selected pad's sound across the pads from there on. Modes: "equal4/8/16", "hits", "beat", "halfbeat", "bar". */
    fun chopPad(pad: Int, mode: String) {
        val pu = Audio.pickup
        val d = pu.samples[pad]
        val room = Pickup.PADS - pad
        val starts = when (mode) {
            "equal4" -> Chop.equal(d, 4)
            "equal8" -> Chop.equal(d, 8)
            "equal16" -> Chop.equal(d, 16)
            "hits" -> Chop.transients(d, room)
            "beat" -> Chop.beats(d, project.bpm, 1f, room)
            "halfbeat" -> Chop.beats(d, project.bpm, 0.5f, room)
            else -> Chop.beats(d, project.bpm, 4f, room)
        }
        val slices = Chop.slices(d, starts)
        if (slices.size < 2) return message("Couldn't find places to cut that sound. Try equal slices.")
        chopUndo = pu.samples.copyOf() to pu.names.copyOf()
        val base = pu.names[pad].take(10)
        fillPads(pad, slices.mapIndexed { k, s -> "$base ${k + 1}" to s })
        message("Chopped into ${minOf(slices.size, room)} pads from pad ${pad + 1}" + if (slices.size > room) " (${slices.size - room} didn't fit)" else "")
    }

    // ---- rhythm chop: tap pads in time while the source plays to place chops

    /** Taps in this pass: (frame in the source, pad). */
    private val chopMarks = ArrayList<Pair<Int, Int>>()
    /** Pad tapped during the count-in: it gets the slice from the very start. */
    private var chopFirstPad = -1
    /** Snap taps to the grid: 0 off, else steps of 1/16 notes (1, 2, 4). */
    var chopSnap = 0
        private set
    fun setChopSnap(steps: Int) { chopSnap = steps; changed() }
    val chopMarksView: List<Pair<Int, Int>> get() = synchronized(chopMarks) { chopMarks.toList() } +
        (if (chopFirstPad >= 0) listOf(Audio.pickup.chopTrimStart to chopFirstPad) else emptyList())

    /** Move the chop source's start or end handle (frames). */
    fun setChopTrim(start: Int, end: Int) {
        val pu = Audio.pickup; val n = pu.chopSrc.size
        val minLen = SR / 20
        val s = start.coerceIn(0, maxOf(0, n - minLen)); val e = end.coerceIn(minOf(n, s + minLen), n)
        pu.chopTrimStart = s; pu.chopTrimEnd = e
    }

    fun setChopSource(d: FloatArray, name: String) {
        Audio.pickup.setChopSource(d, name)
        synchronized(chopMarks) { chopMarks.clear() }; chopFirstPad = -1
        message("Loaded $name to chop. Press Chop, then tap pads in time."); changed()
    }

    /** Start the pass (two bars of count-in, then the source plays), or stop it early. */
    fun rhythmChop() {
        val pu = Audio.pickup
        if (t.chopRec == Transport.Rec.RECORDING) { val end = pu.chopPos; Audio.post { pu.stopChop(); t.chopDone() }; finishRhythmChop(end); return }
        if (t.chopRec == Transport.Rec.ARMED) { Audio.post { t.chopCountIn() }; return }   // cancels
        if (pu.chopSrc.size < SR / 10) return message("Load a sample to chop first")
        synchronized(chopMarks) { chopMarks.clear() }; chopFirstPad = -1
        pu.onChopEnded = { main.post { finishRhythmChop(pu.chopTrimEnd) } }
        t.onChopStart = { off -> pu.startChop(off) }
        t.onChopStop = { val e = pu.chopPos; pu.stopChop(); if (e > 0) main.post { finishRhythmChop(e) } }
        Audio.post { t.chopCountIn() }
        message("Two bars of count-in… then tap a pad wherever a new chop should start")
        changed()
    }

    /** A pad tapped during the pass. */
    fun chopTap(pad: Int) {
        val pu = Audio.pickup
        when (t.chopRec) {
            Transport.Rec.ARMED -> chopFirstPad = pad
            Transport.Rec.RECORDING -> {
                // Once every pad holds a chop, the next tap ends the last one and stops the pass.
                val used = chopMarksView.map { it.second }.toSet()
                if (used.size >= Pickup.PADS) { val end = pu.chopPos; Audio.post { pu.stopChop(); t.chopDone() }; finishRhythmChop(end); return }
                // what you hear lags the play position by the output latency, and the touch itself by ~10 ms
                var f = pu.chopPos - ((Audio.latencyMs + 10f) * SR / 1000f).toInt()
                if (chopSnap > 0) {
                    val grid = SR * 60.0 / project.bpm / 4 * chopSnap
                    f = (Math.round(f / grid) * grid).toInt()
                }
                f = f.coerceIn(pu.chopTrimStart, maxOf(pu.chopTrimStart, pu.chopTrimEnd - 1))
                synchronized(chopMarks) { chopMarks += f to pad }
                if ((used + pad).size >= Pickup.PADS) message("All 16 pads have a chop: tap any pad to end the last one")
            }
            else -> {}
        }
        changed()
    }

    private fun finishRhythmChop(endFrame: Int) {
        val pu = Audio.pickup
        val src = pu.chopSrc
        val marks = (chopMarksView).sortedBy { it.first }.distinctBy { it.first }
        if (marks.isEmpty()) { message("No pads tapped, so nothing was chopped"); changed(); return }
        val end = endFrame.coerceIn(1, minOf(src.size, maxOf(1, pu.chopTrimEnd)))
        val starts = marks.map { it.first }.toIntArray()
        val bounds = starts + end
        chopUndo = pu.samples.copyOf() to pu.names.copyOf()
        val base = pu.chopSrcName.take(10).ifEmpty { "Chop" }
        var made = 0
        for (k in marks.indices) {
            val a = bounds[k]; val b = bounds[k + 1].coerceAtMost(src.size)
            if (b - a < SR / 100) continue
            val s = Chop.slices(src.copyOfRange(a, b), intArrayOf(0)).firstOrNull() ?: continue
            val pad = marks[k].second
            pu.setPadSample(pad, s, "$base ${k + 1}")
            pu.p.all["p${pad}start"]?.set(0f); pu.p.all["p${pad}rev"]?.set(0f); pu.padEnd[pad].set(1f)
            Thread { storePad(pad, s) }.start()
            made++
        }
        message("Chopped $made slice" + (if (made == 1) "" else "s") + " onto the pads you tapped (Chop… → Undo to go back)")
        save(); changed()
    }

    fun undoChop() {
        val (s, n) = chopUndo ?: return message("Nothing to undo")
        chopUndo = null
        val pu = Audio.pickup
        for (i in 0 until Pickup.PADS) pu.setPadSample(i, s[i], n[i])
        Thread { for (i in 0 until Pickup.PADS) storePad(i, s[i]) }.start()
        message("Chop undone"); save(); changed()
    }

    fun setPadSample(i: Int, data: FloatArray, name: String) {
        Audio.pickup.setPadSample(i, data, name)
        Thread { ProjectStore.saveSample(project, "pickup_pad$i", data) }.start()
        message("Loaded $name onto pad ${i + 1}")
        save(); changed()
    }

    // ------------------------------------------------------------------ MIDI input (any thread)

    private val midiHeld = ConcurrentHashMap<Int, Pair<MidiInput.Dev, Int>>()
    private fun midiKey(d: MidiInput.Dev, ch: Int, note: Int) = 0x20000000 or ((d.id and 0xFF) shl 12) or (ch shl 8) or note
    private val padKeys = ConcurrentHashMap<Int, Int>()
    private fun padChordKey(d: MidiInput.Dev, i: Int) = 0x31000000 or ((d.id and 0xFF) shl 8) or (i shl 3)

    override fun noteOn(dev: MidiInput.Dev, ch: Int, note: Int, vel: Float) {
        if (!isOpen) return
        if (dev.role == MidiInput.Role.PADS) {
            val i = Math.floorMod(note - 36, 16)
            val key = 0x30000000 or (dev.id shl 8) or note
            padKeys[key] = i
            when {
                page == "minds" -> noteOn(Audio.minds, key, 36 + i % 8, vel)
                page == "pickup" -> hitPad(i, vel)
                i < 7 -> chordOn(i, padChordKey(dev, i), vel)
                else -> noteOn(target, key, scaleNote(i - 7), vel)
            }
            return
        }
        if (page == "pickup" || page == "minds") { noteOn(current, midiKey(dev, ch, note), note, vel); return }
        val key = midiKey(dev, ch, note)
        midiHeld[key] = dev to ch
        noteOn(target, key, note, vel)
    }

    override fun noteOff(dev: MidiInput.Dev, ch: Int, note: Int) {
        if (!isOpen) return
        if (dev.role == MidiInput.Role.PADS) {
            val key = 0x30000000 or (dev.id shl 8) or note
            val i = padKeys.remove(key) ?: return
            if (page == "pickup") releasePad(i)
            else if (page != "minds") { if (i < 7) chordOff(padChordKey(dev, i)) else noteOff(target, key) }
            return
        }
        val key = midiKey(dev, ch, note)
        midiHeld.remove(key)
        noteOff(if (page == "pickup" || page == "minds") current else target, key)
    }

    override fun expr(dev: MidiInput.Dev, ch: Int, note: Int, kind: Int, v: Float) {
        if (!isOpen) return
        val e = target
        if (note >= 0) return expr(e, midiKey(dev, ch, note), kind, v)
        val all = dev.role == MidiInput.Role.MPE && ch == 0
        midiHeld.forEach { (k, dc) -> if (dc.first === dev && (all || dc.second == ch)) expr(e, k, kind, v) }
    }

    /** CCs go through the MIDI map (MIDI learn takes the next one). */
    override fun cc(dev: MidiInput.Dev, ch: Int, cc: Int, v: Float) {
        if (!isOpen) return
        if (MidiMap.learn(ch, cc)) { message("Learned CC $cc"); changed(); return }
        val target = MidiMap.target(ch, cc) ?: return
        when {
            target == "xy:x" -> pad.xParam.norm = v
            target == "xy:y" -> pad.yParam.norm = v
            target == "block:volume" -> current.volume.norm = v
            target == "block:reverb" -> current.reverb.norm = v
            target.startsWith("transport:") -> if (v >= 0.5f) main.post {
                when (target) { "transport:play" -> play(); "transport:stop" -> stop(); "transport:record" -> recordMidi() }
            }
            target.startsWith("param:") -> {
                val (blockId, paramId) = target.removePrefix("param:").split(".", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                Audio.engines.firstOrNull { it.id == blockId }?.p?.all?.get(paramId)?.norm = v
            }
        }
    }

    override fun realtime(status: Int) {
        if (!isOpen) return
        main.post { when (status) { 0xFA, 0xFB -> play(); 0xFC -> stop() } }
    }

    override fun devicesChanged() = changed()
}
