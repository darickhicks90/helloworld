package app.foxbox.audio

import org.json.JSONObject

/**
 * Base for every FoxBox engine. Engines are only ever called on the audio
 * thread (via [Audio.post] or the transport), except for [p] and the XY
 * values, which are safe to set from anywhere.
 */
abstract class Engine(val id: String, val title: String) {
    val p = Params()

    /** XY pad position (0..1). Instruments use it as MPE-style expression. */
    @Volatile var x = 0.5f
    @Volatile var y = 0.5f
    /** True while a finger is on the XY pad (clip automation yields to it). */
    @Volatile var touching = false
    /** Set by a playing clip each block when it drives the XY values. */
    @Volatile var automated = false
    /** Neutral XY position the pad springs back to (null = stays put). */
    open val springX: Float? = 0.5f
    open val springY: Float? = 0.5f
    abstract val xName: String
    abstract val yName: String

    /** For animations: counts notes started, and the last note. */
    @Volatile var noteCount = 0
    @Volatile var lastNote = 60
    @Volatile var activeVoices = 0

    open val isInstrument = true

    // Every instrument has a volume and reverb send at the end of its params.
    lateinit var volume: Param
    lateinit var reverb: Param
    protected fun mixParams(vol: Float = 0.8f, rev: Float = 0.2f) {
        p.section("Mix")
        reverb = p.num("reverb", "Reverb Send", 0f, 1f, rev, Fmt.PCT)
        volume = p.num("volume", "Volume", 0f, 1f, vol, Fmt.PCT)
    }

    abstract fun noteOn(key: Int, note: Int, vel: Float)
    abstract fun noteOff(key: Int)
    /** Per-note MPE expression: bend in semitones, pressure and slide 0..1. */
    open fun expr(key: Int, kind: Int, v: Float) {}
    open fun allOff() {}
    /** Called by the transport on every 16th-note step while playing. */
    open fun onStep(step: Long) {}
    /** Add this engine's output into l/r. */
    abstract fun render(l: FloatArray, r: FloatArray, n: Int)

    /** Extra per-project state beyond params (patterns, sample names...). */
    open fun saveExtra(o: JSONObject) {}
    open fun loadExtra(o: JSONObject?) {}

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
}

/** Allocates voices: reuses a free one, otherwise steals the oldest. */
class VoicePool<V : Voice>(count: Int, make: () -> V) {
    val voices: List<V> = List(count) { make() }
    private var clock = 0L

    fun start(key: Int, note: Int, vel: Float): V {
        val v = voices.firstOrNull { it.key == key && it.active }
            ?: voices.firstOrNull { !it.active }
            ?: voices.minBy { it.age }
        v.key = key; v.note = note; v.vel = vel; v.age = ++clock; v.resetExpr()
        return v
    }
    fun find(key: Int): V? = voices.firstOrNull { it.key == key && it.active }
    fun countActive() = voices.count { it.active }
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
