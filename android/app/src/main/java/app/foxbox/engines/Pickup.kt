package app.foxbox.engines

import app.foxbox.audio.Audio
import app.foxbox.audio.EnvParams
import app.foxbox.audio.Engine
import app.foxbox.audio.EventRing
import app.foxbox.audio.Fmt
import app.foxbox.audio.Param
import app.foxbox.audio.Rng
import app.foxbox.audio.SR
import app.foxbox.audio.SRF
import app.foxbox.audio.VoiceTail
import app.foxbox.audio.semis
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pickup — a vintage-style 16-pad sampler. Record or load a sound to each
 * pad; playback is resampled and run through sample-rate and bit-depth
 * reduction for that classic gritty drum-machine sound. Has a pad step
 * sequencer with swing.
 */
class Pickup : Engine("pickup", "Pickup") {
    companion object {
        const val PADS = 16
        const val STEPS = 16
        val DEFAULT_NAMES = listOf("Kick", "Snare", "Clap", "Closed Hat", "Open Hat", "Low Tom", "Mid Tom", "High Tom",
            "Rim", "Cowbell", "Shaker", "Crash", "Blip", "Stab", "Bass", "Noise")
    }

    init { p.section("Vintage") }
    private val rate = p.num("rate", "Sample Rate", 8000f, 48000f, 26040f, Fmt.HZ, exp = true)
    private val bits = p.num("bits", "Bit Depth", 6f, 16f, 12f, step = 1f)
    private val warmth = p.num("warmth", "Saturation", 0f, 1f, 0.25f, Fmt.PCT)
    private val pitch = p.num("pitch", "Master Pitch", -12f, 12f, 0f, Fmt.ST)
    init { p.section("Pad Sequencer") }
    val seqOn = p.toggle("padSeq", "Pad Sequencer", true)
    val swing = p.num("swing", "Swing", 0f, 0.6f, 0.12f, Fmt.PCT)
    val length = p.num("padSteps", "Steps", 1f, 16f, 16f, step = 1f)

    private val env = EnvParams(p, cutoff = 16000f, res = 0.1f, envAmt = 0f, attack = 0.001f, decay = 3f, sustain = 1f, release = 0.2f, fSustain = 1f)

    class Pad(val tune: Param, val level: Param, val pan: Param, val decay: Param, val start: Param, val reverse: Param)
    val pads: List<Pad> = (0 until PADS).map { i ->
        p.section("Pad ${i + 1}")
        Pad(
            p.num("p${i}tune", "Pad ${i + 1} Tune", -24f, 24f, 0f, Fmt.ST, step = 1f),
            p.num("p${i}level", "Pad ${i + 1} Level", 0f, 1f, 0.8f, Fmt.PCT),
            p.num("p${i}pan", "Pad ${i + 1} Pan", -1f, 1f, 0f),
            p.num("p${i}decay", "Pad ${i + 1} Decay", 0.02f, 4f, 4f, Fmt.SEC, exp = true),
            p.num("p${i}start", "Pad ${i + 1} Start", 0f, 0.9f, 0f, Fmt.PCT),
            p.toggle("p${i}rev", "Pad ${i + 1} Reverse"),
        )
    }
    init { finishParams("cutoff", "rate", 0.85f, 0.08f) }

    @Volatile var samples: Array<FloatArray> = Array(PADS) { kitSound(it) }; private set
    val names = Array(PADS) { DEFAULT_NAMES[it] }
    /** pattern[pad][step] velocity (0 = off). */
    val pattern = Array(PADS) { FloatArray(STEPS) }
    @Volatile var currentStep = -1
    val hits = EventRing(64)

    fun setPadSample(i: Int, data: FloatArray, name: String) {
        val copy = samples.copyOf(); copy[i] = data; samples = copy; names[i] = name
    }
    fun resetKit() { samples = Array(PADS) { kitSound(it) }; for (i in 0 until PADS) names[i] = DEFAULT_NAMES[i] }

    private inner class V {
        val tail = VoiceTail(); var data = FloatArray(1); var pos = 0.0; var inc = 1.0; var end = 0.0
        var gain = 1f; var pl = 1f; var pr = 1f; var hold = 0f; var holdLeft = 0f
    }
    private val voices = Array(PADS) { V() }
    private val pend = IntArray(32); private val pendVel = FloatArray(32); private val pendDelay = FloatArray(32); private var pending = 0
    private var holdL = 0f; private var holdR = 0f; private var holdPhase = 0f

    private fun fire(pad: Int, vel: Float, record: Boolean) {
        val d = samples[pad]; val pp = pads[pad]
        val v = voices[pad]
        v.data = d
        val rev = pp.reverse.on
        val startF = (pp.start.value * (d.size - 1)).toDouble()
        v.inc = semis(pp.tune.value + v(pitch)).toDouble() * (if (rev) -1.0 else 1.0)
        v.pos = if (rev) d.size - 2.0 - startF else startF
        v.end = if (rev) 0.0 else d.size - 2.0
        v.gain = vel * pp.level.value
        val pan = pp.pan.value
        v.pl = if (pan > 0f) 1f - pan else 1f; v.pr = if (pan < 0f) 1f + pan else 1f
        v.holdLeft = pp.decay.value * SRF
        v.tail.kill(); v.tail.start(env, xy)
        hits.push(pad.toFloat(), vel)
        noted(36 + pad)
        if (record) Audio.transport.recHit(this, 36 + pad, vel)
    }

    override fun noteOn(key: Int, note: Int, vel: Float) = fire(Math.floorMod(note - 36, PADS), vel, record = false)
    override fun noteOff(key: Int) {}
    override fun allOff() { pending = 0; voices.forEach { it.tail.kill() } }

    override fun onStep(step: Long) {
        val len = length.value.toInt().coerceIn(1, STEPS)
        val idx = (step % len).toInt()
        currentStep = idx
        if (!seqOn.on) return
        val stepFrames = SRF * 60f / Audio.transport.bpm / 4f
        val delay = if (idx % 2 == 1) swing.value * stepFrames * 0.5f else 0f
        for (pad in 0 until PADS) {
            val vel = pattern[pad][idx]
            if (vel > 0f && pending < pend.size) { pend[pending] = pad; pendVel[pending] = vel; pendDelay[pending] = delay; pending++ }
        }
    }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        xy.begin()
        var i = 0
        while (i < pending) {
            pendDelay[i] -= n
            if (pendDelay[i] <= 0f) { fire(pend[i], pendVel[i], record = true); pending--; pend[i] = pend[pending]; pendVel[i] = pendVel[pending]; pendDelay[i] = pendDelay[pending] } else i++
        }
        val step = SRF / v(rate)         // sample-and-hold length in output samples
        val levels = 2f.pow(v(bits)) / 2f
        val sat = 1f + v(warmth) * 4f
        var count = 0
        for (vi in voices.indices) {
            val v = voices[vi]
            if (!v.tail.active) continue
            count++
            val d = v.data
            for (k in 0 until n) {
                if (k and 15 == 0) v.tail.update(env, xy, 60, 0f)
                var x = 0f
                val ip = v.pos.toInt()
                if (ip in 0 until d.size - 1) { val f = (v.pos - ip).toFloat(); x = d[ip] + (d[ip + 1] - d[ip]) * f } else if (v.holdLeft > 0f) v.holdLeft = 0f
                v.pos += v.inc
                if (v.holdLeft > 0f) { v.holdLeft -= 1f; if (v.holdLeft <= 0f) v.tail.release(env, xy) }
                v.tail.stereo(x * v.pl, x * v.pr)
                l[k] += v.tail.outL * v.gain; r[k] += v.tail.outR * v.gain
            }
            if (v.pos !in 0.0..(d.size - 1.0) && v.tail.amp.level < 0.0005f) v.tail.kill()
        }
        // Vintage character: sample-and-hold decimation, bit reduction and saturation.
        for (k in 0 until n) {
            holdPhase += 1f
            if (holdPhase >= step) { holdPhase -= step; holdL = round(l[k] * levels) / levels; holdR = round(r[k] * levels) / levels }
            l[k] = tanh(holdL * sat) / sat * 1.2f; r[k] = tanh(holdR * sat) / sat * 1.2f
        }
        activeVoices = count
    }

    override fun saveExtra(o: JSONObject) {
        o.put("pattern", JSONArray().apply { pattern.forEach { row -> put(JSONArray(row.map { it.toDouble() })) } })
        o.put("names", JSONArray(names.toList()))
    }
    override fun loadExtra(o: JSONObject?) {
        pattern.forEach { it.fill(0f) }
        val a = o?.optJSONArray("pattern")
        if (a == null) { defaultBeat(); resetKit(); return }
        for (pd in 0 until minOf(PADS, a.length())) { val row = a.getJSONArray(pd); for (s in 0 until minOf(STEPS, row.length())) pattern[pd][s] = row.getDouble(s).toFloat() }
        o.optJSONArray("names")?.let { n -> for (k in 0 until minOf(PADS, n.length())) names[k] = n.getString(k) }
    }

    private fun defaultBeat() {
        for (s in intArrayOf(0, 7, 10)) pattern[0][s] = 1f
        for (s in intArrayOf(4, 12)) pattern[1][s] = 0.9f
        for (s in 0 until 16 step 2) pattern[3][s] = if (s % 4 == 0) 0.8f else 0.55f
        pattern[4][14] = 0.6f
    }
    init { defaultBeat() }

    /** The built-in kit: classic drum-machine sounds synthesized at start-up. */
    private fun kitSound(i: Int): FloatArray {
        val rnd = Rng(100 + i)
        fun make(sec: Float, f: (Float, Int) -> Float): FloatArray = FloatArray((SR * sec).toInt()) { k -> f(k / SRF, k) }
        var ph = 0.0
        return when (i) {
            0 -> make(0.6f) { t, _ -> ph += 2 * PI * (48 + 110 * exp(-t * 30.0)) / SR; (sin(ph) * exp(-t * 6.0) * 1.1).toFloat() }
            1 -> make(0.35f) { t, _ -> ph += 2 * PI * 190 / SR; ((sin(ph) * 0.5 * exp(-t * 18.0)) + rnd.bi() * 0.6 * exp(-t * 14.0)).toFloat() }
            2 -> make(0.4f) { t, _ -> val burst = if (t < 0.03f) (if ((t * 300).toInt() % 3 == 0) 1.0 else 0.3) else 1.0; (rnd.bi() * burst * exp(-t * 11.0) * 0.8).toFloat() }
            3 -> { var hp = 0f; make(0.08f) { t, _ -> val x = rnd.bi(); val o = x - hp; hp = x * 0.6f; o * exp(-t * 60f) * 0.5f } }
            4 -> { var hp = 0f; make(0.5f) { t, _ -> val x = rnd.bi(); val o = x - hp; hp = x * 0.6f; o * exp(-t * 6f) * 0.45f } }
            5, 6, 7 -> { val f = floatArrayOf(90f, 130f, 180f)[i - 5]; make(0.5f) { t, _ -> ph += 2 * PI * f * (1 + 0.6 * exp(-t * 20.0)) / SR; (sin(ph) * exp(-t * 7.0)).toFloat() } }
            8 -> make(0.06f) { t, _ -> ph += 2 * PI * 1700 / SR; ((sin(ph) * 0.7 + rnd.bi() * 0.3) * exp(-t * 80.0)).toFloat() }
            9 -> { var p2 = 0.0; make(0.3f) { t, _ -> ph += 2 * PI * 540 / SR; p2 += 2 * PI * 800 / SR; ((if (sin(ph) > 0) 0.4 else -0.4) + (if (sin(p2) > 0) 0.4 else -0.4)).toFloat() * exp(-t * 12f) } }
            10 -> make(0.15f) { t, _ -> rnd.bi() * 0.4f * sin(PI.toFloat() * minOf(1f, t / 0.15f)) }
            11 -> { var hp = 0f; make(1.4f) { t, _ -> val x = rnd.bi(); val o = x - hp; hp = x * 0.4f; o * exp(-t * 2.5f) * 0.45f } }
            12 -> make(0.12f) { t, _ -> ph += 2 * PI * (1800 * exp(-t * 25.0) + 300) / SR; (sin(ph) * exp(-t * 20.0) * 0.6).toFloat() }
            13 -> { val notes = doubleArrayOf(220.0, 261.6, 329.6, 392.0); val phs = DoubleArray(4); make(0.6f) { t, _ -> var s = 0.0; for (k in 0..3) { phs[k] += 2 * PI * notes[k] / SR; s += (phs[k] / (2 * PI) % 1.0) * 2 - 1 }; (s * 0.2 * exp(-t * 5.0)).toFloat() } }
            14 -> make(0.7f) { t, _ -> ph += 2 * PI * 55 / SR; (tanh(sin(ph) * 3) * 0.6 * exp(-t * 3.0)).toFloat() }
            else -> make(1.0f) { t, k -> (if (k % 997 < 3) rnd.bi() else rnd.bi() * 0.05f) * exp(-t * 2f) }
        }
    }
}
