package app.foxbox.engines

import app.foxbox.audio.Audio
import app.foxbox.audio.EnvParams
import app.foxbox.audio.Engine
import app.foxbox.audio.Fmt
import app.foxbox.audio.Osc
import app.foxbox.audio.SRF
import app.foxbox.audio.TAU
import app.foxbox.audio.VoiceTail
import app.foxbox.audio.mtof
import app.foxbox.audio.semis
import app.foxbox.seq.Theory
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Bumble — a buzzy mono bass with a 16-step sequencer (bumblebee theme).
 * Steps are scale degrees relative to the chord you tap, so the line
 * follows your chords.
 */
class Bumble : Engine("bumble", "Bumble") {
    init { p.section("Oscillator") }
    private val shape = p.num("shape", "Saw > Square", 0f, 1f, 0.2f, Fmt.PCT)
    private val sub = p.num("sub", "Sub Osc", 0f, 1f, 0.5f, Fmt.PCT)
    private val drive = p.num("drive", "Drive", 0f, 1f, 0.3f, Fmt.PCT)
    private val glide = p.num("glide", "Glide", 0.005f, 0.5f, 0.06f, Fmt.SEC, exp = true)
    private val accentAmt = p.num("accent", "Accent", 0f, 1f, 0.5f, Fmt.PCT)

    private val env = EnvParams(p, cutoff = 520f, res = 0.55f, envAmt = 0.55f, attack = 0.002f, decay = 0.25f, sustain = 0.35f, release = 0.12f, fDecay = 0.22f, fSustain = 0f)

    init { p.section("Sequencer") }
    val enabled = p.toggle("seqOn", "Sequencer", true)
    val length = p.num("steps", "Steps", 1f, 16f, 16f, step = 1f)
    val rate = p.choice("rate", "Rate", listOf("1/16", "1/8", "1/4"), 0)
    private val gateLen = p.num("gate", "Gate Length", 0.1f, 1f, 0.55f, Fmt.PCT)
    val octave = p.num("octave", "Octave", -2f, 1f, -1f, step = 1f)
    private val bendVib = expressionParams()
    init { finishParams("cutoff", "res", 0.8f, 0.05f) }

    class Step(var on: Boolean, var degree: Int, var accent: Boolean, var slide: Boolean)
    val steps = Array(16) { Step(false, 0, false, false) }
    private fun defaultLine() {
        val line = intArrayOf(0, -1, 0, 4, -1, 0, 2, -1, 0, -1, 0, 4, 5, -1, 4, 2)
        for (i in 0 until 16) steps[i].let { it.on = line[i] >= 0; it.degree = maxOf(0, line[i]); it.accent = i % 4 == 0; it.slide = i == 12 }
    }
    init { defaultLine() }
    /** Key and current chord, set by the Studio. */
    @Volatile var scale: Theory.Scale = Theory.SCALES[1]
    @Volatile var root = 0
    @Volatile var chordDegree = 0
    @Volatile var playStep = -1

    private val o1 = Osc()
    private var subPh = 0f
    private val tail = VoiceTail()
    private var freq = 55f; private var target = 55f
    private var vel = 0.8f; private var accentOn = false; private var note = 36
    private var gateLeft = -1f
    private var lfoPh = 0f
    private val held = ArrayList<IntArray>() // [key, note]

    fun noteFor(degree: Int) = Theory.degree(scale, root, 36 + octave.value.toInt() * 12, chordDegree + degree)

    private fun start(n: Int, velocity: Float, accent: Boolean, legato: Boolean) {
        note = n; target = mtof(n.toFloat()); vel = velocity; accentOn = accent
        if (!legato || !tail.active) { freq = target.takeIf { !tail.active } ?: freq; tail.start(env, xy) }
        noted(n)
    }

    override fun noteOn(key: Int, note: Int, vel: Float) {
        held.removeAll { it[0] == key }; held += intArrayOf(key, note)
        start(note, vel, false, held.size > 1)
    }
    override fun noteOff(key: Int) {
        held.removeAll { it[0] == key }
        if (held.isNotEmpty()) start(held.last()[1], vel, false, true) else if (gateLeft < 0f) tail.release(env, xy)
    }
    override fun allOff() { held.clear(); gateLeft = -1f; tail.kill() }

    override fun onStep(step: Long) {
        if (!enabled.on) { playStep = -1; return }
        val div = 1 shl rate.index
        if (step % div != 0L) return
        val len = length.value.toInt().coerceIn(1, 16)
        val i = ((step / div) % len).toInt()
        playStep = i
        val st = steps[i]
        val prev = steps[Math.floorMod(i - 1, len)]
        if (!st.on) return
        val n = noteFor(st.degree)
        val stepFrames = SRF * 60f / Audio.transport.bpm / 4f * div
        val legato = prev.on && prev.slide && gateLeft > 0f
        if (gateLeft > 0f && !legato) Audio.transport.recNoteOff(this, SEQ_KEY)
        start(n, if (st.accent) 1f else 0.7f, st.accent, legato)
        gateLeft = stepFrames * (if (st.slide) 1.05f else v(gateLen))
        if (!legato) Audio.transport.recNoteOn(this, SEQ_KEY, n, if (st.accent) 1f else 0.7f)
    }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        xy.begin()
        if (gateLeft > 0f) {
            gateLeft -= n
            if (gateLeft <= 0f) { gateLeft = -1f; Audio.transport.recNoteOff(this, SEQ_KEY); if (held.isEmpty()) tail.release(env, xy) }
        }
        if (!tail.active) { activeVoices = 0; return }
        activeVoices = 1
        val sh = v(shape); val subL = v(sub) * 0.8f; val drv = 1f + v(drive) * 6f
        val gl = exp(-1f / (v(glide) * SRF * 0.3f))
        val acc = if (accentOn) v(accentAmt) else 0f
        val bend = semis(v(bendVib.first)); val vib = v(bendVib.second) * 0.03f
        for (i in 0 until n) {
            if (i and 15 == 0) tail.update(env, xy, note, acc * 1.5f)
            freq = target + (freq - target) * gl
            lfoPh += 5.5f / SRF; if (lfoPh >= 1f) lfoPh -= 1f
            val f = freq * bend * (1f + sin(TAU * lfoPh) * vib)
            val inc = f / SRF
            val saw = o1.next(0, inc)
            val sq = if (o1.phase < 0.5f) 1f else -1f
            subPh += inc * 0.5f; if (subPh >= 1f) subPh -= 1f
            var x = (saw * (1f - sh) + sq * sh) * 0.5f + sin(TAU * subPh) * subL
            x *= drv; x /= (1f + abs(x))
            val out = tail.process(x) * vel * (0.45f + acc * 0.3f)
            l[i] += out; r[i] += out
        }
    }

    override fun saveExtra(o: JSONObject) {
        o.put("steps", JSONArray().apply { steps.forEach { put(JSONArray(listOf(it.on, it.degree, it.accent, it.slide))) } })
    }
    override fun loadExtra(o: JSONObject?) {
        val a = o?.optJSONArray("steps") ?: return defaultLine()
        for (i in 0 until minOf(16, a.length())) a.getJSONArray(i).let { steps[i].on = it.getBoolean(0); steps[i].degree = it.getInt(1); steps[i].accent = it.getBoolean(2); steps[i].slide = it.getBoolean(3) }
    }

    companion object { const val SEQ_KEY = 0x7FFF0100 }
}
