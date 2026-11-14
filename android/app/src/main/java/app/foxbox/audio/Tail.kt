package app.foxbox.audio

import kotlin.math.pow

/**
 * The amp envelope, filter and filter envelope that every engine shares.
 * [EnvParams] adds the params; each voice owns a [VoiceTail].
 */
class EnvParams(p: Params, cutoff: Float = 4000f, res: Float = 0.2f, envAmt: Float = 0.3f,
                attack: Float = 0.005f, decay: Float = 0.4f, sustain: Float = 0.7f, release: Float = 0.4f,
                fDecay: Float = 0.4f, fSustain: Float = 0.2f) {
    init { p.section("Amp Envelope") }
    val attack = p.num("attack", "Amp Attack", 0.001f, 5f, attack, Fmt.SEC, exp = true)
    val decay = p.num("decay", "Amp Decay", 0.01f, 6f, decay, Fmt.SEC, exp = true)
    val sustain = p.num("sustain", "Amp Sustain", 0f, 1f, sustain, Fmt.PCT)
    val release = p.num("release", "Amp Release", 0.01f, 8f, release, Fmt.SEC, exp = true)
    init { p.section("Filter") }
    val cutoff = p.num("cutoff", "Cutoff", 40f, 18000f, cutoff, Fmt.HZ, exp = true)
    val res = p.num("res", "Resonance", 0f, 1f, res, Fmt.PCT)
    val envAmt = p.num("envAmt", "Filter Env Amount", -1f, 1f, envAmt)
    val keyTrack = p.num("keyTrack", "Key Tracking", 0f, 1f, 0.5f, Fmt.PCT)
    init { p.section("Filter Envelope") }
    val fAttack = p.num("fAttack", "Filter Attack", 0.001f, 5f, 0.005f, Fmt.SEC, exp = true)
    val fDecay = p.num("fDecay", "Filter Decay", 0.01f, 6f, fDecay, Fmt.SEC, exp = true)
    val fSustain = p.num("fSustain", "Filter Sustain", 0f, 1f, fSustain, Fmt.PCT)
    val fRelease = p.num("fRelease", "Filter Release", 0.01f, 8f, 0.5f, Fmt.SEC, exp = true)
}

class VoiceTail {
    val amp = Adsr()
    val fenv = Adsr()
    private val f1 = Svf()
    private val f2 = Svf()
    private val r1 = Svf()
    private val r2 = Svf()
    var outL = 0f; private set
    var outR = 0f; private set
    val active get() = amp.active

    fun start(e: EnvParams, xy: XyState) {
        amp.attack = xy.v(e.attack); amp.decay = xy.v(e.decay); amp.sustain = xy.v(e.sustain); amp.release = xy.v(e.release)
        fenv.attack = xy.v(e.fAttack); fenv.decay = xy.v(e.fDecay); fenv.sustain = xy.v(e.fSustain); fenv.release = xy.v(e.fRelease)
        amp.gateOn(); fenv.gateOn()
    }
    fun release(e: EnvParams, xy: XyState) {
        amp.release = xy.v(e.release); fenv.release = xy.v(e.fRelease)
        amp.gateOff(); fenv.gateOff()
    }
    fun kill() { amp.kill(); fenv.kill(); f1.reset(); f2.reset(); r1.reset(); r2.reset() }

    /** Control-rate filter update. `extraOct` adds modulation in octaves. */
    fun update(e: EnvParams, xy: XyState, note: Int, extraOct: Float) {
        val oct = xy.v(e.envAmt) * fenv.level * 7f + xy.v(e.keyTrack) * (note - 60) / 12f + extraOct
        val cut = xy.v(e.cutoff) * 2f.pow(oct)
        val r = xy.v(e.res)
        f1.set(cut, r * 0.6f); f2.set(cut, r * 0.92f); r1.set(cut, r * 0.6f); r2.set(cut, r * 0.92f)
    }

    /** Filter (24 dB) and amp envelope; also advances the filter envelope. */
    fun process(x: Float, steep: Boolean = true): Float {
        fenv.next()
        val y = f1.process(x)
        return (if (steep) f2.process(y) else y) * amp.next()
    }

    /** Stereo version: results in [outL] / [outR]. */
    fun stereo(l: Float, r: Float) {
        fenv.next()
        val a = amp.next()
        outL = f2.process(f1.process(l)) * a
        outR = r2.process(r1.process(r)) * a
    }
}
