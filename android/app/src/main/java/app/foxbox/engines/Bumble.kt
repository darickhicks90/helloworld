package app.foxbox.engines

import app.foxbox.audio.Adsr
import app.foxbox.audio.EnvParams
import app.foxbox.audio.Engine
import app.foxbox.audio.Fmt
import app.foxbox.audio.Rng
import app.foxbox.audio.SRF
import app.foxbox.audio.Svf
import app.foxbox.audio.TAU
import app.foxbox.audio.mtof
import app.foxbox.audio.semis
import app.foxbox.seq.NoteSeq
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Bumble — a mono bass block in the spirit of a classic analog bass synth:
 * two oscillators with pulse width and sync, a sub oscillator, noise and
 * ring mod, Classic (12/24 dB, band, high) or Acid ladder filters, drive and
 * distortion, a mod envelope, two LFOs, glide, accent and a step sequencer.
 */
class Bumble : Engine("bumble", "Bumble") {
    private val waves = listOf("Sine", "Triangle", "Saw", "Pulse")

    init { p.section("Oscillator 1") }
    private val w1 = p.choice("w1", "Osc 1 Wave", waves, 2)
    private val pw1 = p.num("pw1", "Osc 1 Pulse Width", 0.05f, 0.95f, 0.5f, Fmt.PCT)
    init { p.section("Oscillator 2") }
    private val w2 = p.choice("w2", "Osc 2 Wave", waves, 3)
    private val semi2 = p.num("semi2", "Osc 2 Semitones", -12f, 12f, 0f, Fmt.ST, step = 1f)
    private val fine2 = p.num("fine2", "Osc 2 Fine", -50f, 50f, 7f, Fmt.CT)
    private val sync = p.toggle("sync", "Osc Sync")
    init { p.section("Mixer") }
    private val l1 = p.num("l1", "Osc 1 Level", 0f, 1f, 0.8f, Fmt.PCT)
    private val l2 = p.num("l2", "Osc 2 Level", 0f, 1f, 0.45f, Fmt.PCT)
    private val subL = p.num("sub", "Sub Level", 0f, 1f, 0.6f, Fmt.PCT)
    private val subOct = p.choice("subOct", "Sub Octave", listOf("-1", "-2"), 0)
    private val subWave = p.choice("subWave", "Sub Wave", listOf("Sine", "Square", "Narrow Pulse"), 1)
    private val noiseL = p.num("noise", "Noise Level", 0f, 1f, 0f, Fmt.PCT)
    private val ringL = p.num("ring", "Ring Mod Level", 0f, 1f, 0f, Fmt.PCT)
    init { p.section("Filter Type") }
    private val ftype = p.choice("ftype", "Filter Type", listOf("Classic LP12", "Classic LP24", "Band Pass", "High Pass", "Acid"), 1)
    private val overdrive = p.num("overdrive", "Overdrive", 0f, 1f, 0.25f, Fmt.PCT)
    private val dist = p.num("dist", "Distortion", 0f, 1f, 0.1f, Fmt.PCT)

    private val env = EnvParams(p, cutoff = 520f, res = 0.55f, envAmt = 0.55f, attack = 0.002f, decay = 0.25f, sustain = 0.35f, release = 0.12f, fDecay = 0.22f, fSustain = 0f)

    init { p.section("Mod Envelope") }
    private val modA = p.num("modA", "Mod Env Attack", 0.001f, 3f, 0.001f, Fmt.SEC, exp = true)
    private val modD = p.num("modD", "Mod Env Decay", 0.01f, 3f, 0.2f, Fmt.SEC, exp = true)
    private val modPitch = p.num("modPitch", "Mod Env > Pitch", -12f, 12f, 0f, Fmt.ST)
    private val modPw = p.num("modPw", "Mod Env > PW", -1f, 1f, 0f)
    init { p.section("LFOs") }
    private val lfo1Rate = p.num("lfo1", "LFO 1 Rate", 0.05f, 20f, 5f, Fmt.HZ, exp = true)
    private val lfo1Pitch = p.num("lfo1Pitch", "LFO 1 > Pitch", 0f, 100f, 0f, Fmt.CT)
    private val lfo2Rate = p.num("lfo2", "LFO 2 Rate", 0.05f, 20f, 0.6f, Fmt.HZ, exp = true)
    private val lfo2Filter = p.num("lfo2Filter", "LFO 2 > Filter", 0f, 1f, 0f, Fmt.PCT)
    private val lfo2Pw = p.num("lfo2Pw", "LFO 2 > PW", 0f, 1f, 0f, Fmt.PCT)
    init { p.section("Performance") }
    private val glide = p.num("glide", "Glide", 0.001f, 0.6f, 0.05f, Fmt.SEC, exp = true)
    private val accentAmt = p.num("accent", "Accent", 0f, 1f, 0.5f, Fmt.PCT)
    private val bendVib = expressionParams()
    override val seq = NoteSeq(this, p, defaultOctave = -1, defaultOn = true).apply {
        defaultLine = intArrayOf(0, -1, 0, 4, -1, 0, 2, -1, 0, -1, 0, 4, 5, -1, 4, 2)
        load(null)
    }
    init { finishParams("cutoff", "res", 0.8f, 0.05f) }

    // ------------------------------------------------------------------ voice (mono)

    private val amp = Adsr(); private val fenv = Adsr(); private val menv = Adsr()
    private val svf1 = Svf(); private val svf2 = Svf()
    private var a1 = 0f; private var a2 = 0f; private var a3 = 0f; private var a4 = 0f
    private var ph1 = 0f; private var ph2 = 0f; private var phSub = 0f; private var tri1 = 0f; private var tri2 = 0f
    private var lph1 = 0f; private var lph2 = 0f
    private val rng = Rng(5)
    private var freq = 55f; private var target = 55f
    private var vel = 0.8f; private var accentOn = false; private var note = 36
    private val held = ArrayList<IntArray>()

    private fun start(n: Int, velocity: Float, legato: Boolean) {
        note = n; target = mtof(n.toFloat()); vel = velocity; accentOn = velocity >= 0.95f
        if (!legato || !amp.active) {
            if (!amp.active) freq = target
            amp.attack = v(env.attack); amp.decay = v(env.decay); amp.sustain = v(env.sustain); amp.release = v(env.release)
            fenv.attack = v(env.fAttack); fenv.decay = v(env.fDecay); fenv.sustain = v(env.fSustain); fenv.release = v(env.fRelease)
            menv.attack = v(modA); menv.decay = v(modD); menv.sustain = 0f; menv.release = v(modD)
            amp.gateOn(); fenv.gateOn(); menv.gateOn()
        }
        noted(n)
    }
    private fun release() { amp.release = v(env.release); amp.gateOff(); fenv.gateOff(); menv.gateOff() }

    override fun noteOn(key: Int, note: Int, vel: Float) {
        held.removeAll { it[0] == key }; held += intArrayOf(key, note)
        start(note, vel, held.size > 1)
    }
    override fun noteOff(key: Int) {
        held.removeAll { it[0] == key }
        if (held.isNotEmpty()) start(held.last()[1], vel, true) else release()
    }
    override fun allOff() { held.clear(); amp.kill(); fenv.kill(); menv.kill() }

    private fun blep(t: Float, dt: Float): Float = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1f }
        t > 1f - dt -> { val x = (t - 1f) / dt; x * x + x + x + 1f }
        else -> 0f
    }
    /** One band-limited oscillator sample; `tri` holds the triangle integrator. */
    private fun osc(wave: Int, ph: Float, dt: Float, pw: Float, triState: FloatArray, idx: Int): Float = when (wave) {
        0 -> sin(TAU * ph)
        1 -> { var sq = if (ph < 0.5f) 1f else -1f; sq += blep(ph, dt); sq -= blep((ph + 0.5f) % 1f, dt); triState[idx] = dt * 4f * sq + (1f - dt * 4f) * triState[idx]; triState[idx] }
        2 -> 2f * ph - 1f - blep(ph, dt)
        else -> { var x = if (ph < pw) 1f else -1f; x += blep(ph, dt); x -= blep((ph - pw + 1f) % 1f, dt); x }
    }
    private val tri = FloatArray(2)

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        xy.begin()
        if (!amp.active) { activeVoices = 0; return }
        activeVoices = 1
        val wv1 = w1.index; val wv2 = w2.index
        val o2ratio = semis(v(semi2) + v(fine2) / 100f)
        val lv1 = v(l1); val lv2 = v(l2); val ls = v(subL); val ln = v(noiseL); val lr = v(ringL)
        val subDiv = if (subOct.index == 0) 0.5f else 0.25f
        val sw = subWave.index
        val od = 1f + v(overdrive) * 8f
        val ds = v(dist)
        val type = ftype.index
        val gl = exp(-1f / (v(glide) * SRF * 0.3f))
        val acc = if (accentOn) v(accentAmt) else 0f
        val bend = v(bendVib.first); val vib = v(bendVib.second) * 0.03f
        val l1Inc = v(lfo1Rate) / SRF; val l2Inc = v(lfo2Rate) / SRF
        val l1p = v(lfo1Pitch) / 1200f; val l2f = v(lfo2Filter); val l2pw = v(lfo2Pw)
        val mPitch = v(modPitch); val mPw = v(modPw)
        val cutBase = v(env.cutoff); val res = v(env.res); val eAmt = v(env.envAmt); val kt = v(env.keyTrack)
        for (i in 0 until n) {
            lph1 += l1Inc; if (lph1 >= 1f) lph1 -= 1f
            lph2 += l2Inc; if (lph2 >= 1f) lph2 -= 1f
            val lfo1 = sin(TAU * lph1); val lfo2 = sin(TAU * lph2)
            val me = menv.next(); val fe = fenv.next()
            if (i and 15 == 0) {
                val oct = eAmt * fe * 7f * (1f + acc) + kt * (note - 60) / 12f + lfo2 * l2f * 2f + acc * 0.8f
                val cut = (cutBase * 2f.pow(oct)).coerceIn(20f, SRF * 0.45f)
                when (type) {
                    4 -> { /* Acid ladder uses cut directly below */ }
                    else -> { svf1.set(cut, res * 0.9f); svf2.set(cut, res * 0.5f) }
                }
                acidG = 1f - exp(-TAU * cut / SRF)
            }
            freq = target + (freq - target) * gl
            val f = freq * semis(bend + me * mPitch) * (1f + lfo1 * l1p + sin(TAU * lph1 * 0.9f) * vib)
            val dt1 = (f / SRF).coerceIn(1e-6f, 0.45f)
            val dt2 = (f * o2ratio / SRF).coerceIn(1e-6f, 0.45f)
            val pw = (v(pw1) + me * mPw * 0.4f + lfo2 * l2pw * 0.4f).coerceIn(0.05f, 0.95f)
            val o1 = osc(wv1, ph1, dt1, pw, tri, 0)
            val o2 = osc(wv2, ph2, dt2, pw, tri, 1)
            ph1 += dt1
            if (ph1 >= 1f) { ph1 -= 1f; if (sync.on) ph2 = ph1 * (dt2 / dt1) }
            ph2 += dt2; if (ph2 >= 1f) ph2 -= 1f
            phSub += dt1 * subDiv; if (phSub >= 1f) phSub -= 1f
            val sub = when (sw) { 0 -> sin(TAU * phSub); 1 -> if (phSub < 0.5f) 1f else -1f; else -> if (phSub < 0.2f) 1f else -0.25f }
            var x = o1 * lv1 + o2 * lv2 + sub * ls * 0.8f + rng.bi() * ln * 0.5f + o1 * o2 * lr
            x = tanh(x * od * 0.5f) / (0.5f + 0.5f * od.coerceAtMost(2f))
            val y = when (type) {
                0 -> svf1.process(x)
                1 -> svf2.process(svf1.process(x))
                2 -> { svf1.process(x); svf1.band }
                3 -> { svf1.process(x); svf1.high }
                else -> acid(x, res)
            }
            var out = y * amp.next() * vel * (0.5f + acc * 0.3f)
            if (ds > 0f) { val d = 1f + ds * 10f; out = tanh(out * d) / tanh(d).coerceAtLeast(0.3f) * 0.6f }
            l[i] += out; r[i] += out
        }
    }

    private var acidG = 0.1f
    /** A 4-pole ladder with saturated feedback: squelchy and resonant. */
    private fun acid(x: Float, res: Float): Float {
        val k = res * 3.8f
        val u = tanh(x - k * a4)
        a1 += acidG * (u - a1); a2 += acidG * (a1 - a2); a3 += acidG * (a2 - a3); a4 += acidG * (a3 - a4)
        return a4 * (1f + k * 0.5f)
    }

}
