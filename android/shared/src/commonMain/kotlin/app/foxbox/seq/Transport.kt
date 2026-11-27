package app.foxbox.seq

import app.foxbox.audio.Math
import kotlin.concurrent.Volatile

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
 * called through the host's post (Audio.post on Android); the @Volatile fields are safe to read anywhere.
 */
class Transport {
    enum class Rec { OFF, ARMED, RECORDING }

    interface Listener {
        fun onRecorded(clips: List<Clip>)
        fun onChanged()
        /** An audio take finished (called on the audio thread). */
        fun onAudioTakeEnded() {}
        /** A sampler mic take finished (audio thread); ticks are where it started and ended. */
        fun onSamplerTakeEnded(startTick: Double, endTick: Double) {}
        /** A free-length performance finished on the Fire Escape: the clips and the tick they started at. */
        fun onPerformance(clips: List<Clip>, startTick: Double) {}
    }

    @Volatile var bpm = 110f
    @Volatile var metronome = true
    @Volatile var quantize = false
    /** Audio take on the armed biplanes: OFF, counting in (ARMED) or RECORDING. */
    @Volatile var audioRec = Rec.OFF; private set
    @Volatile var audioRecStart = 0.0; private set
    /** Set by the Studio while a take is being recorded. */
    @Volatile var audioIn: TakeInput? = null
    /** Input + output latency to compensate when lining takes up (frames). */
    @Volatile var latencyFrames = 0
    @Volatile var playing = false; private set
    @Volatile var tick = 0.0; private set
    @Volatile var rec = Rec.OFF; private set
    /** Engine being recorded, or null when the whole project is recording. */
    @Volatile var recTarget: Engine? = null; private set
    @Volatile var recStart = 0.0; private set
    @Volatile var recLen = 0; private set
    /** Clips currently looping (snapshot, safe to read from the UI). */
    @Volatile var playing_ids: Set<String> = emptySet(); private set

    /** Current Clibbins scene: when it started and how long its loop is (0 = no scene). */
    @Volatile var sceneStart = 0.0; private set
    @Volatile var sceneLen = 0; private set
    /** When the queued scene will start (-1 = nothing queued). */
    @Volatile var queuedAt = -1.0; private set
    private var queued: List<Pair<Clip, Engine>>? = null
    private var queuedLen = 0
    private var queuedStop = false
    private var onSwitch: (() -> Unit)? = null

    var listener: Listener? = null
    var engines: List<Engine> = emptyList()

    private val players = ArrayList<ClipPlayer>()
    private val audioPlayers = ArrayList<AudioClipPlayer>()
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

    // ---------------------------------------------------------------- the Fire Escape (arrangement)

    /** The arrangement being played (the Fire Escape is open), or null. Set through the host's post. */
    var arrangement: ArrangementPlayer? = null
        set(v) { field?.releaseAll(); field = v }
    /** The Fire Escape's loop region, in ticks (loops while [loopOn]). */
    @Volatile var loopOn = false
    @Volatile var loopStart = 0.0
    @Volatile var loopEnd = 0.0

    /** Start playing from tick `t` (the Fire Escape's playhead). */
    fun playFrom(t: Double) {
        if (playing) return
        arrangement?.releaseAll()
        tick = t; playing = true; changed()
    }

    /** Move the playhead while playing (a tap on the Fire Escape's ruler). */
    fun seek(t: Double) {
        arrangement?.releaseAll()
        engines.forEach { it.allOff() }
        tick = t; changed()
    }

    /** True while recording a free-length performance (no bar rounding). */
    @Volatile var freeRec = false; private set

    /**
     * Record a performance of any length on the Fire Escape: from `from` (with one
     * bar of count-in if stopped) or from now if already playing, until pressed again
     * or stopped. Its length is exactly as long as it was played.
     */
    fun recordFree(target: Engine?, from: Double) {
        when (rec) {
            Rec.RECORDING -> { finishFree(); return }
            Rec.ARMED -> { rec = Rec.OFF; freeRec = false; changed(); return }
            Rec.OFF -> {}
        }
        recTarget = target; recLen = Int.MAX_VALUE / 2; freeRec = true
        if (!playing) { arrangement?.releaseAll(); tick = from - Ticks.BAR; playing = true; recStart = from }
        else recStart = if (tick < from) from else tick + 1
        rec = Rec.ARMED
        changed()
    }

    private fun finishFree() {
        val end = maxOf(Ticks.STEP, Math.ceil(tick - recStart).toInt())
        val made = ArrayList<Clip>()
        for ((e, tk) in takes) {
            tk.open.values.forEach { tk.notes += it.copy(dur = maxOf(Ticks.STEP / 4, end - it.tick)) }
            val notes = tk.notes.filter { it.tick < end }.sortedBy { it.tick }
            if (notes.isEmpty()) continue
            made += Clip(engine = e.id, name = "", lengthTicks = end, notes = notes.toMutableList(), xy = tk.xy.filter { it.tick < end }.toMutableList())
        }
        takes.clear(); rec = Rec.OFF; freeRec = false
        if (made.isNotEmpty()) listener?.onPerformance(made, recStart)
        changed()
    }

    /** Audio recorded on the Fire Escape: no count-in rounding, no bar rounding. */
    @Volatile var audioFree = false; private set

    /** Record the armed audio tracks from `from` (one bar of count-in if stopped), or from now if playing. Again to stop. */
    fun recordAudioFree(from: Double) {
        if (audioRec != Rec.OFF) { endAudioTake(); return }
        audioFree = true
        if (!playing) { arrangement?.releaseAll(); tick = from - Ticks.BAR; playing = true; audioRecStart = from }
        else audioRecStart = if (tick < from) from else tick + 0.1 * bpm / 60.0 * Ticks.PPQ
        audioRec = Rec.ARMED
        changed()
    }

    fun stop() {
        if (rec == Rec.RECORDING && freeRec) finishFree()
        freeRec = false
        arrangement?.releaseAll()
        players.forEach { it.releaseAll() }
        players.clear(); audioPlayers.clear(); syncIds()
        if (audioRec != Rec.OFF) endAudioTake()
        if (chopRec != Rec.OFF) { chopRec = Rec.OFF; onChopStop?.invoke() }
        if (sampRec == Rec.RECORDING) { sampEnd = tick; sampIn?.markEnd(latencyFrames); endSamplerTake() }
        else if (sampRec == Rec.ARMED) { sampRec = Rec.OFF; listener?.onSamplerTakeEnded(-1.0, -1.0) }
        engines.forEach { it.allOff() }
        playing = false; rec = Rec.OFF; takes.clear(); tick = 0.0
        queued = null; queuedAt = -1.0; onSwitch = null; sceneLen = 0; sceneStart = 0.0
        changed()
    }

    /** Arm recording for `target` (null = all engines) for `lenTicks`. Calling again while recording ends it early. */
    fun record(target: Engine?, lenTicks: Int) {
        if (freeRec) return
        when (rec) {
            Rec.RECORDING -> { recLen = maxOf(Ticks.BAR, nextBar(tick - recStart).toInt()); finish(); return }
            Rec.ARMED -> { rec = Rec.OFF; changed(); return }
            Rec.OFF -> {}
        }
        recTarget = target; recLen = lenTicks
        if (!playing) {
            // Always a one-bar count-in (it clicks whether or not the metronome is on), in Jam and Clips alike.
            tick = -Ticks.BAR.toDouble()
            playing = true; recStart = 0.0
        } else {
            // Already playing: start on a bar line with at least three beats of clicks first.
            val next = nextBar(tick)
            recStart = if (next - tick < Ticks.BAR * 0.75) next + Ticks.BAR else next
        }
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
        audioPlayers.removeAll { it.clip.id == id }
        syncIds(); changed()
    }

    // ---------------------------------------------------------------- Clibbins scenes

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
    fun queueScene(clips: List<Pair<Clip, Engine>>, stopAfter: Boolean, switch: (() -> Unit)?, audio: List<AudioClip> = emptyList()) {
        val startNow = !playing
        if (startNow && !stopAfter) { tick = 0.0; playing = true; sceneLen = 0 }
        queued = clips; queuedAudio = audio; queuedStop = stopAfter; onSwitch = switch
        val audioTicks = audio.maxOfOrNull { (it.seconds * bpm / 60.0 * Ticks.PPQ).toInt() } ?: 0
        queuedLen = maxOf(clips.maxOfOrNull { it.first.lengthTicks } ?: 0, audioTicks).let { if (it <= 0) Ticks.BAR else (Math.ceil(it / Ticks.BAR.toDouble()) * Ticks.BAR).toInt() }
        queuedAt = if (startNow) 0.0 else sceneBoundary()
        changed()
    }

    fun cancelScene() { queued = null; onSwitch = null; queuedAt = -1.0; changed() }

    private fun switchScene() {
        val at = queuedAt
        val next = queued ?: return
        players.forEach { it.releaseAll() }; players.clear(); audioPlayers.clear()
        for ((c, e) in next) players += ClipPlayer(c, e, at, slot++)
        for (a in queuedAudio) audioPlayers += AudioClipPlayer(a, at)
        queuedAudio = emptyList()
        sceneStart = at; sceneLen = if (next.isEmpty()) 0 else queuedLen
        val sw = onSwitch
        queued = null; onSwitch = null; queuedAt = -1.0
        syncIds()
        sw?.invoke()
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
        blockStartTick = t0
        val t1 = t0 + n * bpm * Ticks.PPQ / 60.0 / SR
        if (rec == Rec.ARMED && t1 > recStart) { rec = Rec.RECORDING; takes.clear(); changed() }
        if (audioRec == Rec.ARMED && t1 > audioRecStart) { audioRec = Rec.RECORDING; audioIn?.markStart(latencyFrames); changed() }
        if (chopRec == Rec.ARMED && t1 > chopStart) {
            chopRec = Rec.RECORDING
            val off = ((chopStart - t0) / (bpm * Ticks.PPQ / 60.0 / SR)).toInt().coerceIn(0, n - 1)
            onChopStart?.invoke(off); changed()
        }
        if (sampRec == Rec.ARMED && t1 > sampStart) {
            sampRec = Rec.RECORDING
            // the start tick falls inside this block: count back the frames after it
            val after = ((t1 - sampStart) / (bpm * Ticks.PPQ / 60.0 / SR)).toInt().coerceIn(0, n)
            sampIn?.markStart(latencyFrames + after); changed()
        }
        if (sampRec == Rec.RECORDING && sampEnd >= 0 && t1 >= sampEnd) {
            val after = ((t1 - sampEnd) / (bpm * Ticks.PPQ / 60.0 / SR)).toInt().coerceAtLeast(0)
            sampIn?.markEnd(latencyFrames + after)
            endSamplerTake()
        }
        if (queued != null && t1 > queuedAt) { switchScene(); if (!playing) return }
        // Index loops below: no iterator allocation on the audio thread.

        var s = ceil(t0 / Ticks.STEP).toLong()
        while (s * Ticks.STEP < t1) { step(s); s++ }

        for (i in players.indices) players[i].process(t0, t1)
        val arr = arrangement
        arr?.process(t0, t1)
        // Each block takes the pan of the clips playing on it.
        for (i in engines.indices) { engines[i].panSum = 0f; engines[i].panCount = 0 }
        for (i in players.indices) { val p = players[i]; if (!p.clip.muted) { p.engine.panSum += p.clip.pan; p.engine.panCount++ } }
        arr?.addPan(t0)
        for (i in engines.indices) { val e = engines[i]; e.livePan = if (e.panCount > 0) e.panSum / e.panCount else 0f }

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
            if (!freeRec && t1 >= recStart + recLen) finish()
        }
        tick = t1
        // the Fire Escape's loop region (not while recording, so a take runs on)
        if (arr != null && loopOn && loopEnd > loopStart && t1 >= loopEnd && rec == Rec.OFF && audioRec == Rec.OFF) {
            arr.releaseAll()
            tick = loopStart + (t1 - loopEnd)
        }
    }

    private fun step(s: Long) {
        // the Studio's sequencers (step patterns, drum patterns) stay quiet while the Fire Escape plays the song
        if (s >= 0 && arrangement == null) for (i in engines.indices) engines[i].onStep(s)
        val counting = audioRec == Rec.ARMED || rec == Rec.ARMED || (sampRec == Rec.ARMED && s < 0) || chopRec == Rec.ARMED
        if ((counting || (metronome && (rec != Rec.OFF || audioRec != Rec.OFF))) && Math.floorMod(s, 4L) == 0L) click(Math.floorMod(s, 16L) == 0L)
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

    private fun syncIds() { playing_ids = (players.map { it.clip.id } + audioPlayers.map { it.clip.id }).toSet() }
    private var queuedAudio: List<AudioClip> = emptyList()

    // ---------------------------------------------------------------- Pickup rhythm chop

    /** Rhythm chop pass: OFF, counting in (ARMED) or playing the source (RECORDING). */
    @Volatile var chopRec = Rec.OFF; private set
    @Volatile var chopStart = 0.0; private set
    /** Audio thread: the source starts playing `offset` frames into this block. */
    var onChopStart: ((Int) -> Unit)? = null
    /** Audio thread: the pass was stopped by the transport. */
    var onChopStop: (() -> Unit)? = null

    /** Two bars of count-in (from bar 1 if stopped, else from the next bar line), then the chop pass. Again to cancel. */
    fun chopCountIn() {
        if (chopRec != Rec.OFF) { chopRec = Rec.OFF; onChopStop?.invoke(); changed(); return }
        if (!playing) { tick = -2.0 * Ticks.BAR; playing = true; chopStart = 0.0 }
        else chopStart = nextBar(tick) + 2.0 * Ticks.BAR
        chopRec = Rec.ARMED
        changed()
    }

    /** The chop pass has finished (the source ran out or was stopped). */
    fun chopDone() { if (chopRec != Rec.OFF) { chopRec = Rec.OFF; changed() } }

    // ---------------------------------------------------------------- sampler mic takes (Reflections, Leaves)

    /** Sampler take: OFF, counting in / waiting for its start (ARMED) or RECORDING. */
    @Volatile var sampRec = Rec.OFF; private set
    @Volatile var sampStart = 0.0; private set
    /** Where the take will end (-1 = until pressed again). */
    @Volatile var sampEnd = -1.0; private set
    @Volatile var sampQuantize = false; private set
    /** The input recording the take (set by the Studio). */
    @Volatile var sampIn: TakeInput? = null

    /**
     * Start or stop a sampler take. From stop: one bar of count-in, then record
     * from bar 1 until pressed again. While playing: `quantize` starts on the next
     * bar line (at least 150 ms away, so the input is warmed up) and ends on a
     * whole number of bars; otherwise it records from now until pressed again.
     */
    fun recordSampler(quantize: Boolean) {
        when (sampRec) {
            Rec.ARMED -> { sampRec = Rec.OFF; sampEnd = -1.0; listener?.onSamplerTakeEnded(-1.0, -1.0); changed(); return }
            Rec.RECORDING -> {
                if (!sampQuantize) { sampEnd = tick; return }
                // End on whole bars from the start: a press just after a bar line (within a beat) ends at that line,
                // otherwise the take runs on to the next bar line.
                val len = tick - sampStart
                val k = Math.floor(len / Ticks.BAR)
                sampEnd = sampStart + Ticks.BAR * (if (k >= 1 && len - k * Ticks.BAR <= Ticks.PPQ) k else k + 1)
                changed(); return
            }
            Rec.OFF -> {}
        }
        sampQuantize = quantize; sampEnd = -1.0
        if (!playing) { tick = -Ticks.BAR.toDouble(); playing = true; sampStart = 0.0 }
        else if (quantize) {
            val lead = 0.15 * bpm / 60.0 * Ticks.PPQ
            sampStart = Math.ceil((tick + lead) / Ticks.BAR) * Ticks.BAR
        } else sampStart = tick
        sampRec = Rec.ARMED
        changed()
    }

    private fun endSamplerTake() {
        val s = sampStart; val e = sampEnd
        sampRec = Rec.OFF; sampEnd = -1.0
        listener?.onSamplerTakeEnded(s, e)
        changed()
    }

    // ---------------------------------------------------------------- audio takes

    /** Start a take: two bars of count-in, then record until [endAudioTake]. */
    fun recordAudio() {
        if (audioRec != Rec.OFF) { endAudioTake(); return }
        audioFree = false
        if (!playing) { tick = -2.0 * Ticks.BAR; playing = true; audioRecStart = 0.0 }
        else audioRecStart = nextBar(tick) + 2.0 * Ticks.BAR
        audioRec = Rec.ARMED
        changed()
    }

    private fun endAudioTake() {
        if (audioRec == Rec.RECORDING) audioIn?.markEnd(latencyFrames)
        audioRec = Rec.OFF
        listener?.onAudioTakeEnded()
        audioFree = false
        changed()
    }

    fun launchAudio(clip: AudioClip, at: Double? = null) {
        audioPlayers.removeAll { it.clip.id == clip.id }
        if (!playing) { tick = 0.0; playing = true }
        audioPlayers += AudioClipPlayer(clip, at ?: if (tick <= 0.0) 0.0 else nextBar(tick))
        syncIds(); changed()
    }

    /** Render playing audio clips into the three channel buses. */
    fun renderAudio(busL: Array<FloatArray>, busR: Array<FloatArray>, n: Int, t0: Double) {
        if (!playing) return
        val tpf = bpm * Ticks.PPQ / 60.0 / app.foxbox.audio.SR
        for (i in audioPlayers.indices) { val p = audioPlayers[i]; val ch = p.clip.channel.coerceIn(0, busL.size - 1); p.render(busL[ch], busR[ch], n, t0, tpf) }
        arrangement?.renderAudio(busL, busR, n, t0, tpf)
    }
    @Volatile var blockStartTick = 0.0; private set
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

/** An input being recorded into a take: the transport marks where the take starts and ends. */
interface TakeInput {
    fun markStart(latencyFrames: Int)
    fun markEnd(latencyFrames: Int)
}
