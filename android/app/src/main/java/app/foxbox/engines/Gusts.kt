package app.foxbox.engines

import app.foxbox.audio.EnvParams
import app.foxbox.audio.Engine
import app.foxbox.audio.Fmt
import app.foxbox.audio.Osc
import app.foxbox.audio.Rng
import app.foxbox.audio.SRF
import app.foxbox.audio.Svf
import app.foxbox.audio.TAU
import app.foxbox.audio.Voice
import app.foxbox.audio.VoicePool
import app.foxbox.audio.VoiceTail
import app.foxbox.audio.mtof
import app.foxbox.audio.semis
import kotlin.math.exp
import kotlin.math.sin

/** Gusts — virtual analog subtractive synth (wind theme). */
class Gusts : Engine("gusts", "Gusts") {
    private val waves = listOf("Saw", "Square", "Triangle", "Sine")

    init { p.section("Oscillators") }
    private val wave1 = p.choice("wave1", "Osc 1 Wave", waves, 0)
    private val wave2 = p.choice("wave2", "Osc 2 Wave", waves, 1)
    private val oscMix = p.num("oscMix", "Osc Mix", 0f, 1f, 0.35f, Fmt.PCT)
    private val detune = p.num("detune", "Detune", 0f, 50f, 9f, Fmt.CT)
    private val sub = p.num("sub", "Sub Osc", 0f, 1f, 0.3f, Fmt.PCT)
    private val noise = p.num("noise", "Noise Level", 0f, 1f, 0.12f, Fmt.PCT)
    private val noiseRes = p.num("noiseRes", "Noise Resonance", 0f, 1f, 0.5f, Fmt.PCT)

    private val env = EnvParams(p, cutoff = 2200f, res = 0.25f, envAmt = 0.45f, attack = 0.01f, decay = 0.45f, sustain = 0.6f, release = 0.6f)

    init { p.section("Modulation") }
    private val lfoRate = p.num("lfoRate", "LFO Rate", 0.05f, 14f, 4.5f, Fmt.HZ, exp = true)
    private val lfoCut = p.num("lfoCut", "LFO > Cutoff", 0f, 1f, 0.1f, Fmt.PCT)
    private val glide = p.num("glide", "Glide", 0f, 1f, 0f, Fmt.SEC)
    private val expr = expressionParams()

    init { finishParams("cutoff", "vibrato", 0.75f, 0.2f) }

    private inner class V : Voice() {
        val o1 = Osc(); val o2 = Osc(); var subPh = 0f
        val bp = Svf(); val tail = VoiceTail()
        var freq = 440f; var target = 440f
        override val active get() = tail.active
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
        v.tail.start(env, xy)
        noted(note)
    }
    override fun noteOff(key: Int) { pool.find(key)?.tail?.release(env, xy) }
    override fun expr(key: Int, kind: Int, v: Float) { pool.find(key)?.expr(kind, v) }
    override fun allOff() = pool.voices.forEach { it.tail.kill() }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        xy.begin()
        val w1 = wave1.index; val w2 = wave2.index
        val mix = v(oscMix); val dt = semis(v(detune) / 100f)
        val subL = v(sub) * 0.6f; val nz = v(noise) * (1f + v(noiseRes) * 3f); val nq = 0.3f + v(noiseRes) * 0.65f
        val lfoInc = v(lfoRate) / SRF
        val vib = v(expr.second) * 0.03f + 0.0005f
        val lc = v(lfoCut)
        val bend = v(expr.first)
        val gl = if (glide.value > 0f) exp(-16f / (v(glide) * SRF * 0.3f)) else 0f
        var count = 0
        for (vi in pool.voices.indices) {
            val vo = pool.voices[vi]
            if (!vo.active) continue
            count++
            var lph = lfoPh
            for (i in 0 until n) {
                val lfo = sin(TAU * lph); lph += lfoInc; if (lph >= 1f) lph -= 1f
                if (i and 15 == 0) {
                    vo.freq = vo.target + (vo.freq - vo.target) * gl
                    vo.tail.update(env, xy, vo.note, vo.pressure * 1.5f + (vo.slide - 0.5f) * 3f + lfo * lc * 2f)
                    vo.bp.set(vo.freq * 2f, nq)
                }
                val f = vo.freq * semis(vo.bend + bend) * (1f + lfo * vib)
                val inc = f / SRF
                val o = vo.o1.next(w1, inc * dt) * (1f - mix) + vo.o2.next(w2, inc / dt) * mix
                vo.subPh += inc * 0.5f; if (vo.subPh >= 1f) vo.subPh -= 1f
                vo.bp.process(rng.bi())
                val out = vo.tail.process(o * 0.5f + sin(TAU * vo.subPh) * subL + vo.bp.band * nz) * vo.vel * 0.32f * (1f + vo.pressure * 0.5f)
                l[i] += out; r[i] += out
            }
        }
        lfoPh = (lfoPh + lfoInc * n) % 1f
        activeVoices = count
    }
}
