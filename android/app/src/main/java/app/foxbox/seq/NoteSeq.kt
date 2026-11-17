package app.foxbox.seq

import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.audio.Fmt
import app.foxbox.audio.Params
import app.foxbox.audio.SRF
import org.json.JSONArray
import org.json.JSONObject

/**
 * A 16-step note sequencer that any melodic block can carry. Steps are scale
 * degrees relative to the chord you tap, so lines follow your chords.
 * Runs on the audio thread (onStep from the transport, advance per block).
 */
class NoteSeq(private val engine: Engine, p: Params, defaultOctave: Int = 0, defaultOn: Boolean = false) {
    init { p.section("Step Sequencer") }
    val enabled = p.toggle("seqOn", "Step Sequencer", defaultOn)
    /** Keep running when you leave the Studio for Clibbins. */
    val latch = p.toggle("seqLatch", "Seq Latch")
    val length = p.num("seqSteps", "Steps", 1f, 16f, 16f, step = 1f)
    val rate = p.choice("seqRate", "Rate", listOf("1/16", "1/8", "1/4"), 0)
    val gate = p.num("seqGate", "Gate Length", 0.1f, 1f, 0.55f, Fmt.PCT)
    val octave = p.num("seqOct", "Seq Octave", -2f, 2f, defaultOctave.toFloat(), step = 1f)

    class Step(var on: Boolean = false, var degree: Int = 0, var accent: Boolean = false, var slide: Boolean = false)
    val steps = Array(16) { Step() }
    @Volatile var playStep = -1
    /** Degree of the chord the sequence is built on (set by tapping a chord). */
    @Volatile var chordDegree = 0
    var defaultLine: IntArray? = null

    private var gateLeft = -1f
    private var playing = false

    fun noteFor(degree: Int) = Theory.degree(scale, root, 48 + octave.value.toInt() * 12, chordDegree + degree)

    fun onStep(step: Long) {
        if (!enabled.on) { playStep = -1; return }
        val div = 1 shl rate.index
        if (step % div != 0L) return
        val len = length.value.toInt().coerceIn(1, 16)
        val i = ((step / div) % len).toInt()
        playStep = i
        val st = steps[i]
        if (!st.on) return
        val prev = steps[Math.floorMod(i - 1, len)]
        val legato = prev.on && prev.slide && playing
        val n = noteFor(st.degree)
        val vel = if (st.accent) 1f else 0.72f
        val stepFrames = SRF * 60f / Audio.transport.bpm / 4f * div
        // Slides start the new note before releasing the old one (legato on mono blocks).
        engine.noteOn(KEY_A + (i and 1), n, vel)
        Audio.transport.recNoteOn(engine, KEY_A + (i and 1), n, vel)
        if (playing) {
            val old = KEY_A + ((i + 1) and 1)
            engine.noteOff(old); Audio.transport.recNoteOff(engine, old)
        }
        playing = true
        gateLeft = stepFrames * (if (st.slide) 1.05f else gate.value)
        if (legato) gateLeft = maxOf(gateLeft, stepFrames * 0.5f)
    }

    /** Called once per audio block. */
    fun advance(n: Int) {
        if (!playing) return
        if (!Audio.transport.playing) { release(); return }
        gateLeft -= n
        if (gateLeft <= 0f) release()
    }

    private fun release() {
        for (k in 0..1) { engine.noteOff(KEY_A + k); Audio.transport.recNoteOff(engine, KEY_A + k) }
        playing = false; gateLeft = -1f
    }

    fun clear() = steps.forEach { it.on = false; it.accent = false; it.slide = false }

    fun save(o: JSONObject) {
        o.put("seq", JSONArray().apply { steps.forEach { put(JSONArray(listOf(it.on, it.degree, it.accent, it.slide))) } })
    }
    fun load(o: JSONObject?) {
        val a = o?.optJSONArray("seq") ?: o?.optJSONArray("steps")
        if (a == null) {
            clear()
            defaultLine?.let { line -> for (i in 0 until 16) steps[i].let { it.on = line[i] >= 0; it.degree = maxOf(0, line[i]); it.accent = i % 4 == 0 } }
            return
        }
        for (i in 0 until minOf(16, a.length())) a.getJSONArray(i).let { steps[i].on = it.getBoolean(0); steps[i].degree = it.getInt(1); steps[i].accent = it.getBoolean(2); steps[i].slide = it.getBoolean(3) }
    }

    companion object {
        const val KEY_A = 0x7FFF0100
        /** Key for every sequencer (set by the Studio when the key changes). */
        @Volatile var scale: Theory.Scale = Theory.SCALES[1]
        @Volatile var root = 0
    }
}
