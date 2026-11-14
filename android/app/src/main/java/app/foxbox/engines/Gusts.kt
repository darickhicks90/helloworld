package app.foxbox.engines

import app.foxbox.audio.Adsr
import app.foxbox.audio.Engine
import app.foxbox.audio.Fmt
import app.foxbox.audio.Osc
import app.foxbox.audio.Rng
import app.foxbox.audio.SRF
import app.foxbox.audio.Svf
import app.foxbox.audio.TAU
import app.foxbox.audio.Voice
import app.foxbox.audio.VoicePool
import app.foxbox.audio.mtof
import app.foxbox.audio.semis
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Gusts — virtual analog subtractive synth (wind theme). */
class Gusts : Engine("gusts", "Gusts") {
    override val xName = "Cutoff"
    override val yName = "Vibrato"
    override val springY: Float? = 0f

    private val waves = listOf("Saw", "Square", "Triangle", "Sine")

    init { p.section("Oscillators") }
    private val wave1 = p.choice("wave1", "Osc 1 Wave", waves, 0)
    private val wave2 = p.choice("wave2", "Osc 2 Wave", waves, 1)
    private val oscMix = p.num("oscMix", "Osc Mix", 0f, 1f, 0.35f, Fmt.PCT)
    private val detune = p.num("detune", "Detune", 0f, 50f, 9f, Fmt.CT)
    private val sub = p.num("sub", "Sub Osc", 0f, 1f, 0.3f, Fmt.PCT)
    private val noise = p.num("noise", "Noise Level", 0f, 1f, 0.12f, Fmt.PCT)
    private val noiseRes = p.num("noiseRes", "Noise Resonance", 0f, 1f, 0.5f, Fmt.PCT)

    init { p.section("Filter") }
    private val cutoff = p.num("cutoff", "Cutoff", 60f, 16000f, 2200f, Fmt.HZ, exp = true)
    private val res = p.num("res", "Resonance", 0f, 1f, 0.25f, Fmt.PCT)
    private val envAmt = p.num("envAmt", "Env Amount", 0f, 1f, 0.45f, Fmt.PCT)
    private val fDecay = p.num("fDecay", "Filter Decay", 0.02f, 4f, 0.5f, Fmt.SEC, exp = true)
    private val keyTrack = p.num("keyTrack", "Key Tracking", 0f, 1f, 0.5f, Fmt.PCT)

    init { p.section("Amp Envelope") }
    private val atk = p.num("attack", "Attack", 0.001f, 4f, 0.01f, Fmt.SEC, exp = true)
    private val dec = p.num("decay", "Decay", 0.01f, 4f, 0.45f, Fmt.SEC, exp = true)
    private val sus = p.num("sustain", "Sustain", 0f, 1f, 0.6f, Fmt.PCT)
    private val rel = p.num("release", "Release", 0.01f, 8f, 0.6f, Fmt.SEC, exp = true)

    init { p.section("Modulation") }
    private val lfoRate = p.num("lfoRate", "LFO Rate", 0.05f, 14f, 4.5f, Fmt.HZ, exp = true)
    private val vibDepth = p.num("vibrato", "Vibrato Depth", 0f, 50f, 4f, Fmt.CT)
    private val lfoCut = p.num("lfoCut", "LFO > Cutoff", 0f, 1f, 0.1f, Fmt.PCT)
    private val glide = p.num("glide", "Glide", 0f, 1f, 0f, Fmt.SEC)

    init { mixParams(0.75f, 0.2f); y = 0f }

    private inner class V : Voice() {
        val o1 = Osc(); val o2 = Osc(); var subPh = 0f
        val lp = Svf(); val bp = Svf(); val amp = Adsr()
        var freq = 440f; var target = 440f; var fenv = 0f
        override val active get() = amp.active
    }

    private val pool = VoicePool(10) { V() }
    private val rng = Rng(77)
    private var lfoPh = 0f
    private var lastFreq = 0f

    override fun noteOn(key: Int, note: Int, vel: Float) {
        val v = pool.start(key, note, vel)
        v.target = mtof(note.toFloat())
        v.freq = if (glide.value > 0f && lastFreq > 0f) lastFreq else v.target
        lastFreq = v.target
        v.amp.attack = atk.value; v.amp.decay = dec.value; v.amp.sustain = sus.value; v.amp.release = rel.value
        v.amp.gateOn(); v.fenv = 1f
        noted(note)
    }

    override fun noteOff(key: Int) { pool.find(key)?.amp?.gateOff() }

    override fun expr(key: Int, kind: Int, v: Float) {
        val voice = pool.find(key) ?: return
        when (kind) { BEND -> voice.bend = v; PRESSURE -> voice.pressure = v; SLIDE -> voice.slide = v }
    }

    override fun allOff() = pool.voices.forEach { it.amp.kill() }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        val w1 = wave1.index; val w2 = wave2.index
        val mix = oscMix.value; val dt = semis(detune.value / 100f)
        val subL = sub.value * 0.6f; val nz = noise.value * (1f + noiseRes.value * 3f); val nq = 0.3f + noiseRes.value * 0.65f
        val base = cutoff.value; val q = res.value * 0.95f
        val env = envAmt.value * 6f; val kt = keyTrack.value
        val fdc = exp(-1f / (fDecay.value * SRF / 4f))
        val lfoInc = lfoRate.value / SRF
        val vib = (vibDepth.value + y * 40f) / 1200f
        val lc = lfoCut.value + y * 0.6f
        val xyOct = (x - 0.5f) * 6f
        val gl = if (glide.value > 0f) exp(-1f / (glide.value * SRF * 0.3f)) else 0f
        var count = 0
        for (v in pool.voices) {
            if (!v.active) continue
            count++
            var lph = lfoPh
            for (i in 0 until n) {
                val lfo = sin(TAU * lph); lph += lfoInc; if (lph >= 1f) lph -= 1f
                if (i and 15 == 0) {
                    v.freq = v.target + (v.freq - v.target) * gl.pow(16)
                    val oct = env * v.fenv + kt * (v.note - 60) / 12f + xyOct + v.pressure * 1.5f + (v.slide - 0.5f) * 3f + lfo * lc * 2f
                    v.lp.set(base * 2f.pow(oct), q)
                    v.bp.set(v.freq * 2f, nq)
                }
                v.fenv *= fdc
                val f = v.freq * semis(v.bend) * (1f + lfo * vib)
                val inc = f / SRF
                val o = v.o1.next(w1, inc * dt) * (1f - mix) + v.o2.next(w2, inc / dt) * mix
                v.subPh += inc * 0.5f; if (v.subPh >= 1f) v.subPh -= 1f
                v.bp.process(rng.bi()); val breath = v.bp.band * nz
                val s = v.lp.process(o * 0.5f + sin(TAU * v.subPh) * subL + breath)
                val out = s * v.amp.next() * v.vel * 0.32f * (1f + v.pressure * 0.5f)
                l[i] += out; r[i] += out
            }
        }
        lfoPh = (lfoPh + lfoInc * n) % 1f
        activeVoices = count
    }
}
