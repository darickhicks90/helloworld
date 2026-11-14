package app.foxbox.seq

import app.foxbox.audio.Engine
import app.foxbox.audio.SR
import app.foxbox.audio.TAU
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin

/**
 * The one clock for the whole app. Runs on the audio thread and handles
 * tempo, 16th-note steps, clip playback, recording (project-wide or a
 * single engine), count-in and the metronome. Public commands must be
 * called through Audio.post; the @Volatile fields are safe to read anywhere.
 */
class Transport {
    enum class Rec { OFF, ARMED, RECORDING }

    interface Listener {
        fun onRecorded(clips: List<Clip>)
        fun onChanged()
    }

    @Volatile var bpm = 110f
    @Volatile var metronome = true
    @Volatile var quantize = true
    @Volatile var playing = false; private set
    @Volatile var tick = 0.0; private set
    @Volatile var rec = Rec.OFF; private set
    /** Engine being recorded, or null when the whole project is recording. */
    @Volatile var recTarget: Engine? = null; private set
    @Volatile var recStart = 0.0; private set
    @Volatile var recLen = 0; private set
    /** Clips currently looping (snapshot, safe to read from the UI). */
    @Volatile var playing_ids: Set<String> = emptySet(); private set

    /** Current Valley scene: when it started and how long its loop is (0 = no scene). */
    @Volatile var sceneStart = 0.0; private set
    @Volatile var sceneLen = 0; private set
    /** When the queued scene will start (-1 = nothing queued). */
    @Volatile var queuedAt = -1.0; private set
    private var queued: List<Pair<Clip, Engine>>? = null
    private var queuedLen = 0
    private var queuedStop = false
    private var onSwitch: Runnable? = null

    var listener: Listener? = null
    var engines: List<Engine> = emptyList()

    private val players = ArrayList<ClipPlayer>()
    private var slot = 0

    private class Take {
        val notes = ArrayList<Clip.Note>()
        val open = HashMap<Int, Clip.Note>()
        val xy = ArrayList<Clip.XyPoint>()
        var lastX = -1f; var lastY = -1f
    }
    private val takes = HashMap<Engine, Take>()

    val bar get() = Math.floorDiv(tick.toLong(), Ticks.BAR.toLong())
    val beat get() = Math.floorMod(Math.floorDiv(tick.toLong(), Ticks.PPQ.toLong()), 4L)
    private fun nextBar(t: Double) = ceil(t / Ticks.BAR) * Ticks.BAR

    // ---------------------------------------------------------------- commands

    fun play() {
        if (playing) return
        tick = 0.0; playing = true; changed()
    }

    fun stop() {
        players.forEach { it.releaseAll() }
        players.clear(); syncIds()
        engines.forEach { it.allOff() }
        playing = false; rec = Rec.OFF; takes.clear(); tick = 0.0
        queued = null; queuedAt = -1.0; onSwitch = null; sceneLen = 0; sceneStart = 0.0
        changed()
    }

    /** Arm recording for `target` (null = all engines) for `lenTicks`. Calling again while recording ends it early. */
    fun record(target: Engine?, lenTicks: Int) {
        when (rec) {
            Rec.RECORDING -> { recLen = maxOf(Ticks.BAR, nextBar(tick - recStart).toInt()); finish(); return }
            Rec.ARMED -> { rec = Rec.OFF; changed(); return }
            Rec.OFF -> {}
        }
        recTarget = target; recLen = lenTicks
        if (!playing) {
            // Start with a one-bar count-in when the metronome is on.
            tick = if (metronome) -Ticks.BAR.toDouble() else 0.0
            playing = true; recStart = 0.0
        } else recStart = nextBar(tick)
        rec = Rec.ARMED
        changed()
    }

    /** Start a clip looping at the next bar (or at once if stopped). Replaces the same clip if already playing. */
    fun launch(clip: Clip, engine: Engine, at: Double? = null) {
        stopClip(clip.id)
        if (!playing) { tick = 0.0; playing = true }
        players += ClipPlayer(clip, engine, at ?: if (tick <= 0.0) 0.0 else nextBar(tick), slot++)
        syncIds(); changed()
    }

    fun stopClip(id: String) {
        val it = players.iterator()
        while (it.hasNext()) { val p = it.next(); if (p.clip.id == id) { p.releaseAll(); it.remove() } }
        syncIds(); changed()
    }

    // ---------------------------------------------------------------- Valley scenes

    /** When the current scene's loop next comes round (or the next bar if there is no scene). */
    fun sceneBoundary(): Double = when {
        !playing || tick < 0 -> 0.0
        sceneLen > 0 -> sceneStart + (Math.floor((tick - sceneStart) / sceneLen) + 1) * sceneLen
        else -> nextBar(tick + 1e-6)
    }

    /**
     * Queue the next scene: at the end of the current scene's loop every clip
     * stops, these clips start together, and `switch` runs (on this thread).
     * An empty list with `stopAfter` ends the music at that point.
     */
    fun queueScene(clips: List<Pair<Clip, Engine>>, stopAfter: Boolean, switch: Runnable?) {
        val startNow = !playing
        if (startNow && !stopAfter) { tick = 0.0; playing = true; sceneLen = 0 }
        queued = clips; queuedStop = stopAfter; onSwitch = switch
        queuedLen = clips.maxOfOrNull { it.first.lengthTicks } ?: Ticks.BAR
        queuedAt = if (startNow) 0.0 else sceneBoundary()
        changed()
    }

    fun cancelScene() { queued = null; onSwitch = null; queuedAt = -1.0; changed() }

    private fun switchScene() {
        val at = queuedAt
        val next = queued ?: return
        players.forEach { it.releaseAll() }; players.clear()
        for ((c, e) in next) players += ClipPlayer(c, e, at, slot++)
        sceneStart = at; sceneLen = if (next.isEmpty()) 0 else queuedLen
        val sw = onSwitch
        queued = null; onSwitch = null; queuedAt = -1.0
        syncIds()
        sw?.run()
        if (queuedStop) stop() else changed()
    }

    fun stopAllClips() {
        players.forEach { it.releaseAll() }; players.clear(); syncIds(); changed()
    }

    // ---------------------------------------------------------------- recording hooks (audio thread)

    private fun taking(e: Engine) = rec == Rec.RECORDING && (recTarget == null || recTarget === e)
    private fun rel() = (tick - recStart).toInt().coerceAtLeast(0)

    fun recNoteOn(e: Engine, key: Int, note: Int, vel: Float) {
        if (!taking(e)) return
        takes.getOrPut(e) { Take() }.open[key] = Clip.Note(rel(), 0, note, vel)
    }

    fun recNoteOff(e: Engine, key: Int) {
        if (!taking(e)) return
        val t = takes[e] ?: return
        val n = t.open.remove(key) ?: return
        t.notes += n.copy(dur = maxOf(Ticks.STEP / 4, rel() - n.tick))
    }

    /** One-shot hit (drums): fixed short length. */
    fun recHit(e: Engine, note: Int, vel: Float) {
        if (!taking(e)) return
        takes.getOrPut(e) { Take() }.notes += Clip.Note(rel(), Ticks.STEP / 2, note, vel)
    }

    // ---------------------------------------------------------------- processing

    fun process(n: Int) {
        if (!playing) return
        val t0 = tick
        val t1 = t0 + n * bpm * Ticks.PPQ / 60.0 / SR
        if (rec == Rec.ARMED && t1 > recStart) { rec = Rec.RECORDING; takes.clear(); changed() }
        if (queued != null && t1 > queuedAt) { switchScene(); if (!playing) return }
        // Index loops below: no iterator allocation on the audio thread.

        var s = ceil(t0 / Ticks.STEP).toLong()
        while (s * Ticks.STEP < t1) { step(s); s++ }

        for (i in players.indices) players[i].process(t0, t1)

        if (rec == Rec.RECORDING) {
            // Sample XY movement every 1/32 note.
            val half = Ticks.STEP / 2
            if (Math.floorDiv(t0.toLong(), half.toLong()) != Math.floorDiv(t1.toLong(), half.toLong())) {
                for (e in engines) if (taking(e)) {
                    val tk = takes.getOrPut(e) { Take() }
                    val xy = e.xy
                    if (xy.touching && (xy.x != tk.lastX || xy.y != tk.lastY)) {
                        tk.xy += Clip.XyPoint(rel(), xy.x, xy.y); tk.lastX = xy.x; tk.lastY = xy.y
                    }
                }
            }
            tick = t1
            if (t1 >= recStart + recLen) finish()
        }
        tick = t1
    }

    private fun step(s: Long) {
        if (s >= 0) for (i in engines.indices) engines[i].onStep(s)
        if (metronome && rec != Rec.OFF && Math.floorMod(s, 4L) == 0L) click(Math.floorMod(s, 16L) == 0L)
    }

    private fun finish() {
        val end = recLen
        val made = ArrayList<Clip>()
        for ((e, t) in takes) {
            t.open.values.forEach { t.notes += it.copy(dur = maxOf(Ticks.STEP / 4, end - it.tick)) }
            val notes = t.notes.filter { it.tick < end }.map {
                if (quantize) it.copy(tick = (Math.round(it.tick / Ticks.STEP.toFloat()) * Ticks.STEP) % end) else it
            }.sortedBy { it.tick }
            if (notes.isEmpty()) continue
            made += Clip(engine = e.id, name = "", lengthTicks = end, notes = notes.toMutableList(), xy = t.xy.filter { it.tick < end }.toMutableList())
        }
        takes.clear(); rec = Rec.OFF
        // Newly recorded clips keep looping right where recording ended.
        for (c in made) engines.firstOrNull { it.id == c.engine }?.let { e -> players += ClipPlayer(c, e, recStart + end, slot++) }
        syncIds()
        if (made.isNotEmpty()) listener?.onRecorded(made)
        changed()
    }

    private fun syncIds() { playing_ids = players.map { it.clip.id }.toSet() }
    private fun changed() = listener?.onChanged()

    // ---------------------------------------------------------------- metronome

    private var clickPhase = 0f; private var clickEnv = 0f; private var clickFreq = 1000f
    private val clickDecay = exp(-1f / (0.02f * SR))
    private fun click(accent: Boolean) { clickEnv = if (accent) 0.5f else 0.32f; clickFreq = if (accent) 1760f else 1175f; clickPhase = 0f }

    fun renderClick(l: FloatArray, r: FloatArray, n: Int) {
        if (clickEnv < 0.001f) return
        for (i in 0 until n) {
            val v = sin(TAU * clickPhase) * clickEnv
            clickPhase += clickFreq / SR; if (clickPhase >= 1f) clickPhase -= 1f
            clickEnv *= clickDecay
            l[i] += v; r[i] += v
        }
    }
}
