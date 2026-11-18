package app.foxbox.engines

import app.foxbox.audio.Math
import app.foxbox.audio.locked
import kotlin.concurrent.Volatile

import app.foxbox.audio.Core
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
import app.foxbox.json.JSONArray
import app.foxbox.json.JSONObject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pickup — a 16-pad sampler. Record or load a sound to each pad, or chop one
 * long sample across the pads. Plays clean by default; Vintage mode adds
 * sample-rate and bit-depth reduction with saturation for a gritty
 * drum-machine sound. Has a pad step sequencer with swing.
 */
class Pickup : Engine("pickup", "Pickup") {
    companion object {
        const val PADS = 16
        const val STEPS = 16
        /** The default kit: realistic drums synthesized at start-up. */
        val STUDIO_NAMES = listOf("Kick", "Snare", "Clap", "Closed Hat", "Open Hat", "Low Tom", "Mid Tom", "High Tom",
            "Rim", "Cowbell", "Shaker", "Crash", "Ride", "Conga", "Sub Bass", "Snap")
        /** The original lo-fi kit, kept as the "Classic Box" bank. */
        val CLASSIC_NAMES = listOf("Kick", "Snare", "Clap", "Closed Hat", "Open Hat", "Low Tom", "Mid Tom", "High Tom",
            "Rim", "Cowbell", "Shaker", "Crash", "Blip", "Stab", "Bass", "Noise")
        const val KIT_STUDIO = "studio"
        const val KIT_CLASSIC = "classic"
        private val cache = HashMap<String, Array<FloatArray>>()
        /** The built-in kits' samples (made once, then shared). */
        fun kit(id: String): Array<FloatArray> = locked(cache) {
            cache.getOrPut(id) { Array(PADS) { if (id == KIT_CLASSIC) DrumKits.classic(it) else DrumKits.studio(it) } }
        }
        fun kitNames(id: String) = if (id == KIT_CLASSIC) CLASSIC_NAMES else STUDIO_NAMES
    }

    init { p.section("Vintage") }
    /** Clean playback, or the gritty Vintage character (rate, bits, saturation). */
    val quality = p.choice("quality", "Quality", listOf("Clean", "Vintage"), 0)
    private val rate = p.num("rate", "Sample Rate", 8000f, 48000f, 26040f, Fmt.HZ, exp = true)
    private val bits = p.num("bits", "Bit Depth", 6f, 16f, 12f, step = 1f)
    private val warmth = p.num("warmth", "Saturation", 0f, 1f, 0.25f, Fmt.PCT)
    private val pitch = p.num("pitch", "Master Pitch", -12f, 12f, 0f, Fmt.ST)
    init { p.section("Pad Sequencer") }
    val seqOn = p.toggle("padSeq", "Pad Sequencer", false)
    val latch = p.toggle("padLatch", "Sequencer Latch")
    override val seqSwitch get() = seqOn
    override val seqLatch get() = latch
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

    // Added after the XY pad's targets were fixed, so older projects keep their XY choices.
    /** Where each pad's sound ends (0..1 of the sample) and whether it plays only while held. */
    val padEnd: List<Param> = (0 until PADS).map { i -> p.section("Pad ${i + 1}"); p.num("p${i}end", "Pad ${i + 1} End", 0.05f, 1f, 1f, Fmt.PCT) }
    val padHold: List<Param> = (0 until PADS).map { i -> p.section("Pad ${i + 1}"); p.toggle("p${i}hold", "Pad ${i + 1} Hold") }

    /** Which built-in kit fills pads that have no sound of their own. */
    @Volatile var kitId = KIT_STUDIO
    @Volatile var samples: Array<FloatArray> = kit(KIT_STUDIO).copyOf(); private set
    val names = Array(PADS) { STUDIO_NAMES[it] }
    /** pattern[pad][step] velocity (0 = off). */
    val pattern = Array(PADS) { FloatArray(STEPS) }
    @Volatile var currentStep = -1
    val hits = EventRing(64)

    // ---- rhythm chop: the source sample plays in time while you tap pads to place chops
    @Volatile var chopSrc: FloatArray = FloatArray(0); private set
    @Volatile var chopSrcName = ""; private set
    fun setChopSource(d: FloatArray, name: String) { chopSrc = d; chopSrcName = name; chopTrimStart = 0; chopTrimEnd = d.size }
    /** The part of the source the pass plays (frames): trimmed with the handles on the chop screen. */
    @Volatile var chopTrimStart = 0
    @Volatile var chopTrimEnd = 0
    /** Frames of the source played so far in this pass (-1 = not playing). */
    @Volatile var chopPos = -1; private set
    private var chopOffset = 0
    /** Audio thread: the pass reached the end of the source. */
    var onChopEnded: (() -> Unit)? = null
    fun startChop(offset: Int) { chopOffset = offset; chopPos = chopTrimStart.coerceIn(0, maxOf(0, chopSrc.size - 1)) }
    fun stopChop() { chopPos = -1 }

    fun setPadSample(i: Int, data: FloatArray, name: String) {
        val copy = samples.copyOf(); copy[i] = data; samples = copy; names[i] = name
    }
    fun resetKit() { samples = kit(kitId).copyOf(); val n = kitNames(kitId); for (i in 0 until PADS) names[i] = n[i] }

    private inner class V {
        val tail = VoiceTail(); var data = FloatArray(1); var pos = 0.0; var inc = 1.0; var end = 0.0
        var gain = 1f; var pl = 1f; var pr = 1f; var hold = 0f; var holdLeft = 0f
        var lo = 0.0; var hi = 0.0; var key = 0
    }
    private val voices = Array(PADS) { V() }
    private val pend = IntArray(32); private val pendVel = FloatArray(32); private val pendDelay = FloatArray(32); private var pending = 0
    private var holdL = 0f; private var holdR = 0f; private var holdPhase = 0f

    private fun fire(pad: Int, vel: Float, record: Boolean) {
        val d = samples[pad]; val pp = pads[pad]
        val v = voices[pad]
        v.data = d
        val rev = pp.reverse.on
        // play the region between Start and End (reversed: from End back to Start)
        val a = (pp.start.value * (d.size - 1)).toDouble()
        val b = (maxOf(pp.start.value + 0.01f, padEnd[pad].value).coerceAtMost(1f) * (d.size - 2)).toDouble().coerceAtLeast(a + 1)
        v.lo = a; v.hi = b
        v.inc = semis(pp.tune.value + v(pitch)).toDouble() * (if (rev) -1.0 else 1.0)
        v.pos = if (rev) b else a
        v.end = if (rev) a else b
        v.gain = vel * pp.level.value
        val pan = pp.pan.value
        v.pl = if (pan > 0f) 1f - pan else 1f; v.pr = if (pan < 0f) 1f + pan else 1f
        v.holdLeft = pp.decay.value * SRF
        v.tail.kill(); v.tail.start(env, xy)
        hits.push(pad.toFloat(), vel)
        noted(36 + pad)
        if (record) Core.transport.recHit(this, 36 + pad, vel)
    }

    override fun noteOn(key: Int, note: Int, vel: Float) { val pad = Math.floorMod(note - 36, PADS); fire(pad, vel, record = false); voices[pad].key = key }
    /** Pads set to Hold stop when released; the others play out. */
    override fun noteOff(key: Int) { for (i in voices.indices) { val v = voices[i]; if (v.key == key && padHold[i].on && v.tail.active) { v.key = 0; v.tail.release(env, xy) } } }
    override fun allOff() { pending = 0; voices.forEach { it.tail.kill() } }

    override fun onStep(step: Long) {
        val len = length.value.toInt().coerceIn(1, STEPS)
        val idx = (step % len).toInt()
        currentStep = idx
        if (!seqOn.on) return
        val stepFrames = SRF * 60f / Core.transport.bpm / 4f
        val delay = if (idx % 2 == 1) swing.value * stepFrames * 0.5f else 0f
        for (pad in 0 until PADS) {
            val vel = pattern[pad][idx]
            if (vel > 0f && pending < pend.size) { pend[pending] = pad; pendVel[pending] = vel; pendDelay[pending] = delay; pending++ }
        }
    }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        xy.begin()
        if (chopPos >= 0) {
            val src = chopSrc; var pos = chopPos; val stopAt = chopTrimEnd.coerceIn(1, src.size)
            for (k in chopOffset until n) {
                if (pos >= stopAt) { chopPos = -1; Core.transport.chopDone(); onChopEnded?.invoke(); break }
                val x = src[pos++] * 0.8f; l[k] += x; r[k] += x
            }
            chopOffset = 0
            if (chopPos >= 0) chopPos = pos
        }
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
                if (v.pos >= v.lo && v.pos <= v.hi && ip < d.size - 1) { val f = (v.pos - ip).toFloat(); x = d[ip] + (d[ip + 1] - d[ip]) * f } else if (v.holdLeft > 0f) v.holdLeft = 0f
                v.pos += v.inc
                if (v.holdLeft > 0f) { v.holdLeft -= 1f; if (v.holdLeft <= 0f) v.tail.release(env, xy) }
                v.tail.stereo(x * v.pl, x * v.pr)
                l[k] += v.tail.outL * v.gain; r[k] += v.tail.outR * v.gain
            }
            if (v.pos !in v.lo..v.hi && v.tail.amp.level < 0.0005f) v.tail.kill()
        }
        activeVoices = count
        if (quality.index == 0) return
        // Vintage character: sample-and-hold decimation, bit reduction and saturation.
        for (k in 0 until n) {
            holdPhase += 1f
            if (holdPhase >= step) { holdPhase -= step; holdL = round(l[k] * levels) / levels; holdR = round(r[k] * levels) / levels }
            l[k] = tanh(holdL * sat) / sat * 1.2f; r[k] = tanh(holdR * sat) / sat * 1.2f
        }
    }

    override fun saveExtra(o: JSONObject) {
        o.put("pattern", JSONArray().apply { pattern.forEach { row -> put(JSONArray(row.map { it.toDouble() })) } })
        o.put("names", JSONArray(names.toList()))
        o.put("kit", kitId)
    }
    override fun loadExtra(o: JSONObject?) {
        pattern.forEach { it.fill(0f) }
        val a = o?.optJSONArray("pattern")
        // Projects from before the new kit keep the sounds they were made with.
        kitId = o?.optString("kit", if (a != null) KIT_CLASSIC else KIT_STUDIO) ?: KIT_STUDIO
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
}
