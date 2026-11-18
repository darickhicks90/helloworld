package app.foxbox.engines

import app.foxbox.audio.Math
import kotlin.concurrent.Volatile

import app.foxbox.audio.Core
import app.foxbox.audio.Engine
import app.foxbox.audio.EventRing
import app.foxbox.audio.Fmt
import app.foxbox.audio.Param
import app.foxbox.audio.Rng
import app.foxbox.audio.SRF
import app.foxbox.audio.Svf
import app.foxbox.audio.TAU
import app.foxbox.audio.semis
import app.foxbox.json.JSONArray
import app.foxbox.json.JSONObject
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/**
 * Minds — drums with a generative brain. You draw a 16-step pattern; the
 * Generative knob (0–100%) blends in an evolving, seeded pattern plus
 * chaos, glitches and mutation. 0% plays exactly what you drew.
 */
class Minds : Engine("minds", "Minds") {
    companion object {
        val DRUMS = listOf("Kick", "Snare", "Hat", "Click", "Zap", "Crunch", "Metal", "Ghost")
        const val STEPS = 16
        private val DENSITY = floatArrayOf(0.28f, 0.16f, 0.6f, 0.3f, 0.13f, 0.15f, 0.1f, 0.16f)
        private val BASE_DECAY = floatArrayOf(0.11f, 0.06f, 0.025f, 0.006f, 0.04f, 0.04f, 0.07f, 0.09f)
    }

    init { p.section("Sequencer") }
    val enabled = p.toggle("on", "Sequencer", true)
    val latch = p.toggle("latch", "Sequencer Latch")
    override val seqSwitch get() = enabled
    override val seqLatch get() = latch
    val generative = p.num("generative", "Generative", 0f, 1f, 0.5f, Fmt.PCT)
    val density = p.num("density", "Density", 0f, 1f, 0.5f, Fmt.PCT)
    val swing = p.num("swing", "Swing", 0f, 0.6f, 0f, Fmt.PCT)
    val mutate = p.num("mutate", "Mutation", 0f, 1f, 0.3f, Fmt.PCT)
    val chaos = p.num("chaos", "Chaos", 0f, 1f, 0.3f, Fmt.PCT)
    val glitch = p.num("glitch", "Glitch", 0f, 1f, 0.25f, Fmt.PCT)

    init { p.section("Amp Envelope") }
    private val ampAttack = p.num("attack", "Amp Attack", 0.0005f, 0.05f, 0.0008f, Fmt.SEC, exp = true)
    private val ampDecay = p.num("decay", "Amp Decay", 0.25f, 3f, 1f, Fmt.X, exp = true)
    init { p.section("Filter") }
    private val cutoff = p.num("cutoff", "Cutoff", 80f, 18000f, 14000f, Fmt.HZ, exp = true)
    private val res = p.num("res", "Resonance", 0f, 1f, 0.1f, Fmt.PCT)
    private val envAmt = p.num("envAmt", "Filter Env Amount", -1f, 1f, 0f)
    private val fDecay = p.num("fDecay", "Filter Decay", 0.005f, 1f, 0.08f, Fmt.SEC, exp = true)
    private val crush = p.num("crush", "Bitcrush", 0f, 1f, 0f, Fmt.PCT)

    /**
     * Each drum's own sound. Tune, decay, tone and level are the originals; the
     * rest default to "off" so older projects and presets sound the same:
     * punch (a fast pitch drop at the start), snap (an attack click), noise (mixed
     * into the body), drive (saturation), cutoff (the drum's own low-pass) and pan.
     */
    class DrumParams(val tune: Param, val decay: Param, val tone: Param, val level: Param,
                     val punch: Param, val snap: Param, val noise: Param, val drive: Param, val cutoff: Param, val pan: Param) {
        val all get() = listOf(tune, decay, tone, level, punch, snap, noise, drive, cutoff, pan)
    }
    val drumParams: List<DrumParams> = DRUMS.mapIndexed { i, name ->
        p.section(name)
        DrumParams(
            p.num("d${i}tune", "$name Tune", -12f, 12f, 0f, Fmt.ST, step = 1f),
            p.num("d${i}decay", "$name Decay", 0.25f, 2.5f, 1f, Fmt.X, exp = true),
            p.num("d${i}tone", "$name Tone", 0f, 1f, 0.5f, Fmt.PCT),
            p.num("d${i}level", "$name Level", 0f, 1f, 0.8f, Fmt.PCT),
            p.num("d${i}punch", "$name Punch", 0f, 1f, 0f, Fmt.PCT),
            p.num("d${i}snap", "$name Snap", 0f, 1f, 0f, Fmt.PCT),
            p.num("d${i}noise", "$name Noise", 0f, 1f, 0f, Fmt.PCT),
            p.num("d${i}drive", "$name Drive", 0f, 1f, 0f, Fmt.PCT),
            p.num("d${i}cutoff", "$name Cutoff", 120f, 20000f, 20000f, Fmt.HZ, exp = true),
            p.num("d${i}pan", "$name Pan", -1f, 1f, 0f),
        )
    }
    /** The kit-wide sound (amp envelope, filter, crush): shown together in the Minds sound pop-up. */
    val kitParams: List<Param> get() = listOf(ampAttack, ampDecay, cutoff, res, envAmt, fDecay, crush)
    init { finishParams("chaos", "glitch", 0.85f, 0.08f) }

    /** Your hand-drawn pattern: pattern[drum][step] = velocity (0 = off). */
    val pattern = Array(DRUMS.size) { FloatArray(STEPS) }
    /** The generative layer, grown from a seed and mutated as it plays. */
    val generated = Array(DRUMS.size) { FloatArray(STEPS) }
    @Volatile var seed = 1234
    @Volatile var currentStep = -1
    val hits = EventRing(64)

    private val rng = Rng(31)
    private val drums = Array(DRUMS.size) { Drum(it) }

    init { fill(pattern, 1234); generate(4321) }

    private fun fill(target: Array<FloatArray>, s: Int) {
        val r = Rng(s * 7919 + 1)
        val d = density.value
        for (v in DRUMS.indices) for (st in 0 until STEPS) {
            var pr = DENSITY[v] * (0.3f + d * 1.6f)
            if (v == 0 && st % 4 == 0) pr += 0.35f
            if (v == 1 && (st == 4 || st == 12)) pr += 0.6f
            if (v == 2 && st % 2 == 0) pr += 0.2f
            target[v][st] = if (r.next() < pr) round((0.35f + r.next() * 0.65f) * 100f) / 100f else 0f
        }
        target[0][0] = 1f
    }

    /** Grow a new generative layer. */
    fun generate(newSeed: Int) { seed = newSeed; fill(generated, newSeed) }
    /** Copy the generative layer into your grid. */
    fun keepGenerated() { for (v in DRUMS.indices) generated[v].copyInto(pattern[v]) }
    fun toggleStep(drum: Int, step: Int) { pattern[drum][step] = if (pattern[drum][step] > 0f) 0f else 0.9f }
    fun clear() = pattern.forEach { it.fill(0f) }

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
        val gen = generative.value
        val stepFrames = SRF * 60f / Core.transport.bpm / 4f
        val swingDelay = if (idx % 2 == 1) swing.value * stepFrames * 0.5f else 0f
        val ch = chaos.value * gen; val gl = glitch.value * gen
        if (stutterLeft <= 0 && rng.next() < gl * 0.06f) { stutterLeft = 1 + (rng.next() * 3).toInt(); stutterSrc = (idx + 15) % STEPS; stutterDiv = 2 + (rng.next() * 4).toInt() }
        val src = if (stutterLeft > 0) stutterSrc else idx
        for (v in DRUMS.indices) {
            var vel = if (rng.next() < gen) generated[v][src] else pattern[v][src]
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
        val m = mutate.value * gen
        if (idx == STEPS - 1 && m > 0f) for (v in DRUMS.indices) for (s in 0 until STEPS)
            if (rng.next() < m * 0.15f) generated[v][s] = if (generated[v][s] > 0f) 0f else 0.3f + rng.next() * 0.6f
    }

    override fun noteOn(key: Int, note: Int, vel: Float) = fire(Math.floorMod(note - 36, DRUMS.size), vel, 0f, record = false)
    override fun noteOff(key: Int) {}
    override fun allOff() { pending = 0; drums.forEach { it.active = false } }

    private fun fire(v: Int, vel: Float, pitch: Float, record: Boolean) {
        drums[v].trigger(vel, pitch)
        hits.push(v.toFloat(), vel)
        noted(36 + v)
        if (record) Core.transport.recHit(this, 36 + v, vel)
    }

    // ------------------------------------------------------------------ audio

    private var crushHold = 0f; private var crushHoldR = 0f; private var crushCount = 0
    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        xy.begin()
        var i = 0
        while (i < pending) {
            pendDelay[i] -= n
            if (pendDelay[i] <= 0f) {
                fire(pendDrum[i], pendVel[i], pendPitch[i], record = true)
                pending--
                pendDrum[i] = pendDrum[pending]; pendVel[i] = pendVel[pending]; pendDelay[i] = pendDelay[pending]; pendPitch[i] = pendPitch[pending]
            } else i++
        }
        val c = v(crush)
        val levels = if (c > 0.01f) 2f.pow(16f - c * 13f) / 2f else 0f
        val down = 1 + (c * c * 30f).toInt()
        for (d in drums) if (d.active) d.prepare()
        for (s in 0 until n) {
            var oL = 0f; var oR = 0f
            for (d in drums) if (d.active) { val x = d.next(s); oL += x * d.gL; oR += x * d.gR }
            if (levels > 0f) {
                if (crushCount++ % down == 0) { crushHold = round(oL * levels) / levels; crushHoldR = round(oR * levels) / levels }
                oL = crushHold; oR = crushHoldR
            }
            l[s] += oL; r[s] += oR
        }
        var act = 0
        for (d in drums) if (d.active) act++
        activeVoices = act
    }

    private inner class Drum(val type: Int) {
        var active = false
        private var t = 0; private var env = 0f; private var peak = 0f; private var dec = 0.999f; private var atkInc = 1f; private var attacking = false
        private var fenv = 0f; private var fdec = 0.99f
        private var ph = 0f; private var ph2 = 0f; private var tune = 1f; private var tone = 0.5f
        private val f = Svf(); private val lp = Svf(); private var hold = 0f; private var holdN = 8; private var gain = 1f
        // the drum's own shaping (punch, snap, noise, drive, cutoff, pan)
        private val body = Svf(); private var bodyOn = false
        private var punch = 0f; private var pEnv = 0f; private var pDec = 0.99f
        private var snap = 0f; private var sEnv = 0f; private var sDec = 0.99f
        private var noiseAmt = 0f; private var drive = 0f
        var gL = 1f; var gR = 1f

        fun trigger(vel: Float, pitch: Float) {
            val dp = drumParams[type]
            tune = semis(dp.tune.value + pitch); tone = dp.tone.value; gain = dp.level.value
            punch = dp.punch.value; pEnv = 1f; pDec = exp(-1f / (0.022f * SRF))
            snap = dp.snap.value; sEnv = 1f; sDec = exp(-1f / (0.0025f * SRF))
            noiseAmt = dp.noise.value; drive = dp.drive.value
            val cut = dp.cutoff.value; bodyOn = cut < 19000f; if (bodyOn) body.set(cut, 0.15f)
            val pn = dp.pan.value; gL = minOf(1f, 1f - pn); gR = minOf(1f, 1f + pn)
            dec = exp(-1f / (BASE_DECAY[type] * dp.decay.value * v(ampDecay) * SRF))
            atkInc = 1f / (v(ampAttack) * SRF); attacking = true
            fdec = exp(-1f / (v(fDecay) * SRF)); fenv = 1f
            t = 0; env = 0f; peak = vel; ph = 0f; ph2 = 0f; active = true
            when (type) {
                1 -> f.set(1300f + tone * 3000f, 0.2f)
                2 -> f.set(5000f + tone * 6000f, 0.3f)
                3 -> f.set((1500f + rng.next() * 3500f) * tune, 0.92f)
                5 -> { f.set(1500f + tone * 7000f, 0.1f); holdN = 4 + ((1f - tone) * 40f).toInt() }
                6 -> f.set(2400f + tone * 2400f, 0.6f)
                7 -> f.set((180f + rng.next() * 260f) * tune * 1.4f, 0.95f)
            }
        }

        /** Control-rate: update the drum's filter from the filter envelope. */
        fun prepare() { lp.set(v(cutoff) * 2f.pow(v(envAmt) * fenv * 6f), v(res) * 0.9f) }

        fun next(@Suppress("UNUSED_PARAMETER") s: Int): Float {
            val nz = rng.bi()
            // punch: the pitch starts up to ~2 octaves high and drops within a few tens of ms
            val tn = if (punch > 0.001f) { val k = tune * (1f + punch * 3f * pEnv); pEnv *= pDec; k } else tune
            val out = when (type) {
                0 -> {
                    val fr = 45f * tn * (1f + 3.5f * exp(-t / (0.025f * SRF)))
                    ph += fr / SRF; if (ph >= 1f) ph -= 1f
                    val x = sin(TAU * ph) * (1f + tone * 2f)
                    x / (1f + kotlin.math.abs(x) * 0.5f) + nz * exp(-t / (0.002f * SRF)) * tone * 0.5f
                }
                1 -> { ph += 190f * tn / SRF; if (ph >= 1f) ph -= 1f; f.process(nz); f.high * (0.4f + tone * 0.6f) + sin(TAU * ph) * (1f - tone) * 0.8f }
                2 -> { f.process(nz); f.high * 0.6f }
                3 -> { f.process(nz); f.band * 3f }
                4 -> {
                    val fr = 70f * tn + 2400f * tn * exp(-t / (0.015f * SRF * (0.5f + tone)))
                    ph += fr / SRF; if (ph >= 1f) ph -= 1f; sin(TAU * ph) * 0.6f
                }
                5 -> { if (t % holdN == 0) hold = round(nz * 3f) / 3f; f.process(hold) * 0.5f }
                6 -> {
                    ph += 540f * tn / SRF; if (ph >= 1f) ph -= 1f
                    ph2 += 805f * tn / SRF; if (ph2 >= 1f) ph2 -= 1f
                    f.process((if (ph < 0.5f) 1f else -1f) * (if (ph2 < 0.5f) 1f else -1f)); f.band * 0.8f
                }
                else -> { f.process(nz); f.band * 4f }
            }
            var x = out
            if (noiseAmt > 0.001f) x += nz * noiseAmt * 0.7f
            if (snap > 0.001f) { x += nz * snap * sEnv * 1.6f; sEnv *= sDec }
            if (drive > 0.001f) { val g = 1f + drive * 9f; val y = x * g; x = y / (1f + kotlin.math.abs(y)) * (1f + drive * 0.6f) }
            if (bodyOn) x = body.process(x)
            t++
            if (attacking) { env += atkInc * peak; if (env >= peak) { env = peak; attacking = false } } else env *= dec
            fenv *= fdec
            if (!attacking && env < 0.0005f) active = false
            return lp.process(x) * env * gain * 0.45f
        }
    }

    override fun saveExtra(o: JSONObject) {
        o.put("seed", seed)
        o.put("pattern", JSONArray().apply { pattern.forEach { row -> put(JSONArray(row.map { it.toDouble() })) } })
        o.put("generated", JSONArray().apply { generated.forEach { row -> put(JSONArray(row.map { it.toDouble() })) } })
    }
    override fun loadExtra(o: JSONObject?) {
        fun read(key: String, into: Array<FloatArray>): Boolean {
            val a = o?.optJSONArray(key) ?: return false
            for (v in 0 until minOf(a.length(), DRUMS.size)) {
                val row = a.getJSONArray(v)
                for (s in 0 until minOf(row.length(), STEPS)) into[v][s] = row.getDouble(s).toFloat()
            }
            return true
        }
        seed = o?.optInt("seed", 4321) ?: 4321
        if (!read("pattern", pattern)) fill(pattern, 1234)
        if (!read("generated", generated)) generate(seed)
    }
}
