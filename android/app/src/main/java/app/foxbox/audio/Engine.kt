package app.foxbox.audio

import org.json.JSONObject

/**
 * Base for every FoxBox instrument. Engines run on the audio thread (via
 * [Audio.post] or the transport); params and the XY pad are safe to set
 * from anywhere.
 */
abstract class Engine(val id: String, val title: String) {
    val p = Params()
    /** The XY pad. Created by [finishParams] once all params exist. */
    lateinit var xy: XyState
        private set
    fun v(param: Param) = xy.v(param)

    /** For animations. */
    @Volatile var noteCount = 0
    @Volatile var lastNote = 60
    @Volatile var activeVoices = 0
    /** Pan from the clips playing on this block (-1..1), set by the transport each block. */
    @Volatile var livePan = 0f
    var panSum = 0f; var panCount = 0

    lateinit var volume: Param
    lateinit var reverb: Param

    /** Adds the mix params and the XY pad (call at the end of the subclass's init). */
    protected fun finishParams(xDefault: String, yDefault: String, vol: Float = 0.8f, rev: Float = 0.2f) {
        p.section("Mix")
        reverb = p.num("reverb", "Reverb Send", 0f, 1f, rev, Fmt.PCT)
        volume = p.num("volume", "Volume", 0f, 1f, vol, Fmt.PCT)
        xy = XyState(p, xDefault, yDefault)
    }

    /** Pitch bend and vibrato params every melodic engine offers to its XY pad. */
    protected fun expressionParams(): Pair<Param, Param> {
        p.section("Expression")
        return p.num("bend", "Pitch Bend", -2f, 2f, 0f, Fmt.ST) to p.num("vibrato", "Vibrato", 0f, 1f, 0f, Fmt.PCT)
    }

    /** The block's step sequencer, if it has one (melodic blocks). */
    open val seq: app.foxbox.seq.NoteSeq? = null
    /** The block's sequencer on/off switch and its latch (keeps it running in Clibbins), if it has a sequencer. */
    open val seqSwitch: Param? get() = seq?.enabled
    open val seqLatch: Param? get() = seq?.latch

    abstract fun noteOn(key: Int, note: Int, vel: Float)
    abstract fun noteOff(key: Int)
    /** Per-note MPE expression: bend in semitones, pressure and slide 0..1. */
    open fun expr(key: Int, kind: Int, v: Float) {}
    open fun allOff() {}
    /** Called by the transport on every 16th-note step while playing. */
    open fun onStep(step: Long) { seq?.onStep(step) }
    /** Add this engine's output into l/r. Call xy.begin() first. */
    abstract fun render(l: FloatArray, r: FloatArray, n: Int)

    open fun saveExtra(o: JSONObject) { seq?.save(o) }
    open fun loadExtra(o: JSONObject?) { seq?.load(o) }

    protected fun noted(note: Int) { noteCount++; lastNote = note }

    companion object {
        const val BEND = 0
        const val PRESSURE = 1
        const val SLIDE = 2
    }
}

/** A voice in a polyphonic engine. */
abstract class Voice {
    var key = -1
    var note = 60
    var vel = 1f
    var age = 0L
    var bend = 0f
    var pressure = 0f
    var slide = 0.5f
    abstract val active: Boolean
    fun resetExpr() { bend = 0f; pressure = 0f; slide = 0.5f }
    fun expr(kind: Int, v: Float) { when (kind) { Engine.BEND -> bend = v; Engine.PRESSURE -> pressure = v; Engine.SLIDE -> slide = v } }
}

/** Allocates voices: reuses a free one, otherwise steals the oldest. */
class VoicePool<V : Voice>(count: Int, make: () -> V) {
    val voices: List<V> = List(count) { make() }
    private var clock = 0L

    fun start(key: Int, note: Int, vel: Float): V {
        var v: V? = find(key)
        if (v == null) for (i in voices.indices) if (!voices[i].active) { v = voices[i]; break }
        if (v == null) { v = voices[0]; for (i in voices.indices) if (voices[i].age < v!!.age) v = voices[i] }
        val chosen = v!!
        chosen.key = key; chosen.note = note; chosen.vel = vel; chosen.age = ++clock; chosen.resetExpr()
        return chosen
    }
    fun find(key: Int): V? {
        for (i in voices.indices) { val v = voices[i]; if (v.key == key && v.active) return v }
        return null
    }
}

/** Lock-free ring of recent events for animations (audio thread writes, UI reads). */
class EventRing(size: Int = 64) {
    val a = FloatArray(size)
    val b = FloatArray(size)
    @Volatile var written = 0L
    fun push(x: Float, y: Float) {
        val i = (written % a.size).toInt()
        a[i] = x; b[i] = y; written++
    }
}
