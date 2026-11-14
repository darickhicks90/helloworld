package app.foxbox.engines

import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.audio.EventRing
import app.foxbox.audio.Fmt
import app.foxbox.audio.Param
import app.foxbox.audio.Rng
import app.foxbox.audio.SR
import app.foxbox.audio.SRF
import app.foxbox.audio.Svf
import app.foxbox.audio.TAU
import app.foxbox.audio.semis
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sin

/**
 * Minds — glitchy generative drums. Eight synthesized drums play a 16-step
 * pattern you can draw by hand or grow from a seed; Chaos, Glitch and
 * Mutate bend it while it plays. The sequencer can be switched off.
 */
class Minds : Engine("minds", "Minds") {
    override val xName = ""
    override val yName = ""

    companion object {
        val DRUMS = listOf("Kick", "Snare", "Hat", "Click", "Zap", "Crunch", "Metal", "Ghost")
        const val STEPS = 16
        private val DENSITY = floatArrayOf(0.28f, 0.16f, 0.6f, 0.3f, 0.13f, 0.15f, 0.1f, 0.16f)
    }

    init { p.section("Sequencer") }
    val enabled = p.toggle("on", "Sequencer", true)
    val density = p.num("density", "Density", 0f, 1f, 0.5f, Fmt.PCT)
    val swing = p.num("swing", "Swing", 0f, 0.6f, 0f, Fmt.PCT)
    val mutate = p.num("mutate", "Mutation", 0f, 1f, 0f, Fmt.PCT)
    val chaos = p.num("chaos", "Chaos", 0f, 1f, 0.2f, Fmt.PCT)
    val glitch = p.num("glitch", "Glitch", 0f, 1f, 0.15f, Fmt.PCT)
    val crush = p.num("crush", "Bitcrush", 0f, 1f, 0f, Fmt.PCT)

    class DrumParams(val tune: Param, val decay: Param, val tone: Param, val level: Param)
    val drumParams: List<DrumParams> = DRUMS.mapIndexed { i, name ->
        p.section(name)
        DrumParams(
            p.num("d${i}tune", "$name Tune", -12f, 12f, 0f, Fmt.ST, step = 1f),
            p.num("d${i}decay", "$name Decay", 0.25f, 2.5f, 1f, Fmt.X, exp = true),
            p.num("d${i}tone", "$name Tone", 0f, 1f, 0.5f, Fmt.PCT),
            p.num("d${i}level", "$name Level", 0f, 1f, 0.8f, Fmt.PCT),
        )
    }
    init { mixParams(0.85f, 0.08f) }

    /** pattern[drum][step] = velocity (0 = off). Edited from the UI. */
    val pattern = Array(DRUMS.size) { FloatArray(STEPS) }
    @Volatile var seed = 1234
    @Volatile var currentStep = -1
    /** Hit events for the UI: a = drum index, b = velocity. */
    val hits = EventRing(64)

    private val rng = Rng(31)
    private val drums = List(DRUMS.size) { Drum(it) }

    init { generate(seed) }

    // ------------------------------------------------------------------ patterns

    fun generate(newSeed: Int) {
        seed = newSeed
        val r = Rng(newSeed * 7919 + 1)
        val d = density.value
        for (v in DRUMS.indices) for (s in 0 until STEPS) {
            var pr = DENSITY[v] * (0.3f + d * 1.6f)
            if (v == 0 && s % 4 == 0) pr += 0.35f
            if (v == 1 && (s == 4 || s == 12)) pr += 0.6f
            if (v == 2 && s % 2 == 0) pr += 0.2f
            pattern[v][s] = if (r.next() < pr) round((0.35f + r.next() * 0.65f) * 100f) / 100f else 0f
        }
        pattern[0][0] = 1f
    }

    fun toggleStep(drum: Int, step: Int) { pattern[drum][step] = if (pattern[drum][step] > 0f) 0f else 0.9f }
    fun clear() = pattern.forEach { it.fill(0f) }

    private fun mutateNow(amount: Float) {
        for (v in DRUMS.indices) for (s in 0 until STEPS)
            if (rng.next() < amount * 0.15f) pattern[v][s] = if (pattern[v][s] > 0f) 0f else 0.3f + rng.next() * 0.6f
    }

    // ------------------------------------------------------------------ sequencing

    private val pendDrum = IntArray(64); private val pendVel = FloatArray(64)
    private val pendDelay = FloatArray(64); private val pendPitch = FloatArray(64)
    private var pending = 0
    private var stutterLeft = 0; private var stutterSrc = 0; private var stutterDiv = 2

    private fun queue(drum: Int, vel: Float, delay: Float, pitch: Float) {
        if (pending >= pendDrum.size) return
        pendDrum[pending] = drum; pendVel[pending] = vel; pendDelay[pending] = delay; pendPitch[pending] = pitch; pending++
    }

    override fun onStep(step: Long) {
        val idx = (step % STEPS).toInt()
        currentStep = idx
        if (!enabled.on) return
        val stepFrames = SRF * 60f / Audio.transport.bpm / 4f
        val swingDelay = if (idx % 2 == 1) swing.value * stepFrames * 0.5f else 0f
        val ch = chaos.value; val gl = glitch.value
        if (stutterLeft <= 0 && rng.next() < gl * 0.06f) { stutterLeft = 1 + (rng.next() * 3).toInt(); stutterSrc = (idx + 15) % STEPS; stutterDiv = 2 + (rng.next() * 4).toInt() }
        val src = if (stutterLeft > 0) stutterSrc else idx
        for (v in DRUMS.indices) {
            var vel = pattern[v][src]
            if (vel > 0f && rng.next() < ch * 0.18f) vel = 0f
            if (vel == 0f && rng.next() < ch * 0.05f) vel = 0.25f + rng.next() * 0.3f
            if (vel <= 0f) continue
            val hitsN = if (stutterLeft > 0) stutterDiv else if (rng.next() < ch * 0.12f + gl * 0.05f) 2 + (rng.next() * 3).toInt() else 1
            val jitter = rng.bi() * ch * stepFrames * 0.12f
            for (h in 0 until hitsN) {
                var pitch = 0f
                if (stutterLeft > 0) pitch += h * 2f
                if (rng.next() < gl * 0.08f) pitch += (if (rng.next() < 0.5f) -1 else 1) * (5 + (rng.next() * 8).toInt())
                queue(v, vel * if (h == 0) 1f else 0.75f, maxOf(0f, swingDelay + jitter + h * stepFrames / hitsN), pitch)
            }
        }
        if (stutterLeft > 0) stutterLeft--
        if (idx == STEPS - 1 && mutate.value > 0f) mutateNow(mutate.value)
    }

    /** Live and clip-playback hits (recording of live hits is done by the caller). */
    override fun noteOn(key: Int, note: Int, vel: Float) {
        val v = Math.floorMod(note - 36, DRUMS.size)
        fire(v, vel, 0f, record = false)
    }
    override fun noteOff(key: Int) {}
    override fun allOff() { pending = 0; drums.forEach { it.active = false } }

    private fun fire(v: Int, vel: Float, pitch: Float, record: Boolean) {
        drums[v].trigger(vel, pitch)
        hits.push(v.toFloat(), vel)
        noted(36 + v)
        if (record) Audio.transport.recHit(this, 36 + v, vel)
    }

    // ------------------------------------------------------------------ audio

    private var crushHoldL = 0f; private var crushCount = 0
    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        var i = 0
        while (i < pending) {
            pendDelay[i] -= n
            if (pendDelay[i] <= 0f) {
                fire(pendDrum[i], pendVel[i], pendPitch[i], record = true)
                pending--
                pendDrum[i] = pendDrum[pending]; pendVel[i] = pendVel[pending]; pendDelay[i] = pendDelay[pending]; pendPitch[i] = pendPitch[pending]
            } else i++
        }
        val c = crush.value
        val levels = if (c > 0.01f) Math.pow(2.0, (16f - c * 13f).toDouble()).toFloat() / 2f else 0f
        val down = 1 + (c * c * 30f).toInt()
        var count = 0
        for (s in 0 until n) {
            var o = 0f
            for (d in drums) if (d.active) o += d.next()
            if (levels > 0f) {
                if (crushCount++ % down == 0) crushHoldL = round(o * levels) / levels
                o = crushHoldL
            }
            l[s] += o; r[s] += o
        }
        for (d in drums) if (d.active) count++
        activeVoices = count
    }

    private inner class Drum(val type: Int) {
        var active = false
        private var t = 0; private var env = 0f; private var dec = 0.999f
        private var ph = 0f; private var ph2 = 0f; private var tune = 1f; private var tone = 0.5f
        private val f = Svf(); private var hold = 0f; private var holdN = 8; private var gain = 1f

        fun trigger(vel: Float, pitch: Float) {
            val dp = drumParams[type]
            tune = semis(dp.tune.value + pitch); tone = dp.tone.value; gain = dp.level.value
            val base = floatArrayOf(0.11f, 0.06f, 0.025f, 0.006f, 0.04f, 0.04f, 0.07f, 0.09f)[type]
            dec = exp(-1f / (base * dp.decay.value * SRF))
            t = 0; env = vel; ph = 0f; ph2 = 0f; active = true
            when (type) {
                1 -> f.set(1300f + tone * 3000f, 0.2f)
                2 -> f.set(5000f + tone * 6000f, 0.3f)
                3 -> f.set((1500f + rng.next() * 3500f) * tune, 0.92f)
                5 -> { f.set(1500f + tone * 7000f, 0.1f); holdN = 4 + ((1f - tone) * 40f).toInt() }
                6 -> f.set(2400f + tone * 2400f, 0.6f)
                7 -> f.set((180f + rng.next() * 260f) * tune * 1.4f, 0.95f)
            }
        }

        fun next(): Float {
            val nz = rng.bi()
            val out = when (type) {
                0 -> { // Kick: pitch-swept sine plus click
                    val fr = 45f * tune * (1f + 3.5f * exp(-t / (0.025f * SRF)))
                    ph += fr / SRF; if (ph >= 1f) ph -= 1f
                    val s = sin(TAU * ph) * (1f + tone * 2f)
                    s / (1f + kotlin.math.abs(s) * 0.5f) + nz * exp(-t / (0.002f * SRF)) * tone * 0.5f
                }
                1 -> { // Snare: tone body + filtered noise
                    ph += 190f * tune / SRF; if (ph >= 1f) ph -= 1f
                    f.process(nz); f.high * (0.4f + tone * 0.6f) + sin(TAU * ph) * (1f - tone) * 0.8f
                }
                2 -> { f.process(nz); f.high * 0.6f } // Hat: high-passed noise
                3 -> { f.process(nz); f.band * 3f } // Click: resonant noise ping
                4 -> { // Zap: fast falling sine
                    val fr = 70f * tune + 2400f * tune * exp(-t / (0.015f * SRF * (0.5f + tone)))
                    ph += fr / SRF; if (ph >= 1f) ph -= 1f; sin(TAU * ph) * 0.6f
                }
                5 -> { if (t % holdN == 0) hold = round(nz * 3f) / 3f; f.process(hold) * 0.5f } // Crunch: sample & hold noise
                6 -> { // Metal: ring-modulated squares
                    ph += 540f * tune / SRF; if (ph >= 1f) ph -= 1f
                    ph2 += 805f * tune / SRF; if (ph2 >= 1f) ph2 -= 1f
                    f.process((if (ph < 0.5f) 1f else -1f) * (if (ph2 < 0.5f) 1f else -1f)); f.band * 0.8f
                }
                else -> { f.process(nz); f.band * 4f } // Ghost: resonant noise tom
            }
            t++
            env *= dec
            if (env < 0.0005f) active = false
            return out * env * gain * 0.45f
        }
    }

    override fun saveExtra(o: JSONObject) {
        o.put("seed", seed)
        o.put("pattern", JSONArray().apply { pattern.forEach { row -> put(JSONArray(row.map { it.toDouble() })) } })
    }
    override fun loadExtra(o: JSONObject?) {
        val a = o?.optJSONArray("pattern")
        if (a == null) { generate(1234); return }
        seed = o.optInt("seed", 1234)
        for (v in 0 until minOf(a.length(), DRUMS.size)) {
            val row = a.getJSONArray(v)
            for (s in 0 until minOf(row.length(), STEPS)) pattern[v][s] = row.getDouble(s).toFloat()
        }
    }
}
