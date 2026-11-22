package app.foxbox.audio

import kotlin.concurrent.Volatile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** Small, allocation-free DSP building blocks shared by all engines. */

/** Sample rate: set once from the device's native rate before audio starts (see Audio.configure). */
var SR = 48000; private set
var SRF = 48000f; private set
fun setSampleRate(rate: Int) { SR = rate; SRF = rate.toFloat() }
const val TAU = (2 * PI).toFloat()

fun mtof(note: Float) = 440f * 2f.pow((note - 69f) / 12f)
fun semis(st: Float) = 2f.pow(st / 12f)
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Fast xorshift noise / random numbers (audio thread only). */
class Rng(seed: Int = 0x2545F491) {
    private var s = if (seed == 0) 1 else seed
    fun next(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        return (s ushr 8) / 16777216f
    }
    fun bi() = next() * 2f - 1f
}

/** Hann window lookup for grains and fades. */
object Hann {
    const val N = 1024
    val table = FloatArray(N + 1) { (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / N)).toFloat() }
    fun at(phase: Float): Float = table[(phase.coerceIn(0f, 1f) * N).toInt()]
}

/** Linear-attack, exponential decay/release envelope. */
class Adsr {
    var attack = 0.01f; var decay = 0.3f; var sustain = 0.7f; var release = 0.5f
    var level = 0f; private set
    private var stage = 0 // 0 idle, 1 attack, 2 decay/sustain, 3 release
    private var aInc = 0f; private var dCoef = 0f; private var rCoef = 0f
    val active get() = stage != 0

    fun gateOn() {
        aInc = 1f / max(1f, attack * SRF)
        dCoef = exp(-1f / max(1f, decay * SRF / 4f))
        stage = 1
    }
    fun gateOff() {
        if (stage == 0) return
        rCoef = exp(-1f / max(1f, release * SRF / 4f))
        stage = 3
    }
    fun kill() { stage = 0; level = 0f }
    fun next(): Float {
        when (stage) {
            1 -> { level += aInc; if (level >= 1f) { level = 1f; stage = 2 } }
            2 -> level = sustain + (level - sustain) * dCoef
            3 -> { level *= rCoef; if (level < 0.0001f) kill() }
        }
        return level
    }
}

/** Zero-delay-feedback state variable filter (Simper). Set coefficients at control rate. */
class Svf {
    private var g = 0f; private var k = 1f; private var a1 = 0f; private var a2 = 0f; private var a3 = 0f
    private var ic1 = 0f; private var ic2 = 0f
    var band = 0f; private set
    var high = 0f; private set

    /** cutoff in Hz, res 0..1 */
    fun set(cutoff: Float, res: Float) {
        g = tan(PI * cutoff.coerceIn(20f, SRF * 0.45f) / SRF).toFloat()
        k = 2f - 2f * res.coerceIn(0f, 0.97f)
        a1 = 1f / (1f + g * (g + k)); a2 = g * a1; a3 = g * a2
    }
    /** Returns low-pass; band/high available after the call. */
    fun process(x: Float): Float {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1; ic2 = 2f * v2 - ic2
        band = v1; high = x - k * v1 - v2
        return v2
    }
    fun reset() { ic1 = 0f; ic2 = 0f }
}

/** One-pole low-pass, handy for smoothing and tone controls. */
class OnePole {
    private var a = 0f; var z = 0f
    fun set(cutoff: Float) { a = exp(-TAU * cutoff.coerceIn(1f, SRF * 0.45f) / SRF) }
    fun process(x: Float): Float { z = x + (z - x) * a; return z }
}

/** Band-limited oscillator (polyBLEP). Shapes: 0 saw, 1 square, 2 triangle, 3 sine. */
class Osc {
    var phase = 0f
    private var tri = 0f
    private fun blep(t: Float, dt: Float): Float = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1f }
        t > 1f - dt -> { val x = (t - 1f) / dt; x * x + x + x + 1f }
        else -> 0f
    }
    fun next(shape: Int, inc: Float): Float {
        val dt = inc.coerceIn(1e-6f, 0.5f)
        val t = phase
        val out = when (shape) {
            0 -> 2f * t - 1f - blep(t, dt)
            1, 2 -> {
                var sq = if (t < 0.5f) 1f else -1f
                sq += blep(t, dt); sq -= blep((t + 0.5f) % 1f, dt)
                if (shape == 1) sq else { tri = dt * 4f * sq + (1f - dt * 4f) * tri; tri }
            }
            else -> sin(TAU * t)
        }
        phase += dt; if (phase >= 1f) phase -= 1f
        return out
    }
}

/** Fractional delay line. */
class DelayLine(maxSamples: Int) {
    private val buf = FloatArray(maxSamples + 2)
    private var w = 0
    fun write(x: Float) { buf[w] = x; w++; if (w >= buf.size) w = 0 }
    /** Read `d` samples behind the most recent write. */
    fun read(d: Float): Float {
        val dd = if (d.isNaN()) 0f else d.coerceIn(0f, (buf.size - 3).toFloat())
        var p = w - 1 - dd
        while (p < 0) p += buf.size
        // float rounding can land exactly on the end of the buffer: wrap it
        var i = p.toInt(); if (i >= buf.size) { i -= buf.size; p -= buf.size }
        val f = (p - i).coerceIn(0f, 1f)
        val a = buf[i]; val b = buf[if (i + 1 >= buf.size) 0 else i + 1]
        return a + (b - a) * f
    }
    fun clear() = buf.fill(0f)
}

/** Compact stereo reverb (Freeverb-style combs + allpasses). */
class Reverb {
    private val combL = intArrayOf(1214, 1293, 1390, 1476).map { Comb(it) }
    private val combR = intArrayOf(1237, 1316, 1413, 1499).map { Comb(it) }
    private val apL = intArrayOf(605, 480).map { Allpass(it) }
    private val apR = intArrayOf(628, 503).map { Allpass(it) }
    var room = 0.84f; var damp = 0.3f

    private inner class Comb(n: Int) {
        val b = FloatArray(n); var i = 0; var store = 0f
        fun p(x: Float): Float {
            val o = b[i]; store = o * (1 - damp) + store * damp
            b[i] = x + store * room; if (++i >= b.size) i = 0; return o
        }
    }
    private inner class Allpass(n: Int) {
        val b = FloatArray(n); var i = 0
        fun p(x: Float): Float { val o = b[i]; b[i] = x + o * 0.5f; if (++i >= b.size) i = 0; return o - x }
    }
    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        for (s in 0 until n) {
            val x = (inL[s] + inR[s]) * 0.015f
            var l = 0f; var r = 0f
            for (c in combL) l += c.p(x)
            for (c in combR) r += c.p(x)
            for (a in apL) l = a.p(l)
            for (a in apR) r = a.p(r)
            outL[s] += l; outR[s] += r
        }
    }
}

/**
 * Master output guard: DC blocker, an optional speaker high-pass, and a transparent look-ahead
 * peak limiter that keeps everything under 0 dBFS without ever turning anything up.
 *
 * The limiter only ever reduces gain (never above 1, no make-up gain, no saturation), so
 * anything that already fits passes through bit-for-bit (apart from the DC blocker). When a
 * peak would cross the ceiling, the gain eases down over the 1.5 ms look-ahead before the peak
 * arrives (so it never clips and never clicks), holds, and recovers smoothly: quick after a
 * lone transient, slower when the music sits against the ceiling, so it doesn't pump.
 *
 * Peaks are measured between samples too (a cheap 2x interpolation), so what reaches the
 * speaker or the converter stays under the ceiling after reconstruction as well.
 *
 * Phone speakers can't reproduce much below ~150 Hz, and their smart amps protect the driver by
 * turning the whole output down when it is pushed hard for a long time. So on the built-in
 * speaker, sub-bass is removed before the limiter and the ceiling sits 1 dB lower.
 */
class Limiter {
    // DC blocker state (one-pole high-pass at ~8 Hz)
    private var dxL = 0f; private var dyL = 0f; private var dxR = 0f; private var dyR = 0f
    // speaker high-pass: two cascaded biquads (Butterworth, 4th order)
    private val hpL1 = Biquad(); private val hpL2 = Biquad(); private val hpR1 = Biquad(); private val hpR2 = Biquad()
    private var hpHz = 0f
    private var dcR = 0f; private var rate = 0

    // look-ahead: the signal waits LA samples while the gain gets ready for what's coming
    private val dl = FloatArray(MAX_LA); private val dr = FloatArray(MAX_LA)
    // the gain each sample needs, and a running minimum over the look-ahead (monotonic queue)
    private val qVal = FloatArray(MAX_LA); private val qIdx = IntArray(MAX_LA)
    private var qHead = 0; private var qLen = 0
    // moving average of the released gain over the look-ahead (so the gain moves smoothly)
    private val avg = FloatArray(MAX_LA); private var avgSum = 0.0
    private var la = 64; private var pos = 0; private var count = 0
    private var held = 1f
    private var relFast = 0f; private var relSlow = 0f; private var slowEnv = 1f; private var slowK = 0f
    // the last three samples of each side, for the between-samples estimate
    private var a1 = 0f; private var a2 = 0f; private var a3 = 0f; private var b1 = 0f; private var b2 = 0f; private var b3 = 0f

    /** True while the sound comes out of the phone's own speaker. */
    @Volatile var speaker = false
    /** A lower ceiling than the default (linear, e.g. 0.708 for -3 dBFS); 0 = the default. */
    @Volatile var ceilingOverride = 0f
    /** How far the gain is turned down right now (1 = untouched), for meters. */
    var reduction = 1f; private set

    private fun setup() {
        rate = SR
        dcR = exp(-TAU * 8f / SRF)
        la = (SRF * 0.0015f).toInt().coerceIn(16, MAX_LA - 1)
        relFast = exp(-1f / (0.06f * SRF))     // after a lone peak: about 60 ms
        relSlow = exp(-1f / (0.6f * SRF))      // when it keeps pushing: up to 600 ms
        slowK = 1f - exp(-1f / (0.5f * SRF))
        dl.fill(0f); dr.fill(0f); avg.fill(1f); avgSum = la.toDouble()
        qHead = 0; qLen = 0; pos = 0; count = 0; held = 1f; slowEnv = 1f; a1 = 0f; a2 = 0f; a3 = 0f; b1 = 0f; b2 = 0f; b3 = 0f
    }

    fun process(l: FloatArray, r: FloatArray, n: Int, gain: Float) {
        if (rate != SR) setup()
        val spk = speaker
        if (spk && hpHz != SPEAKER_HP) { hpHz = SPEAKER_HP; hpL1.highPass(SPEAKER_HP); hpL2.highPass(SPEAKER_HP); hpR1.highPass(SPEAKER_HP); hpR2.highPass(SPEAKER_HP) }
        val ceiling = if (ceilingOverride > 0f) ceilingOverride else if (spk) CEILING_SPEAKER else CEILING
        val g0 = gain.coerceIn(0f, 1f)   // the master fader only ever turns down
        var minG = 1f
        for (i in 0 until n) {
            // DC blocker
            var x = l[i]; var y = x - dxL + dcR * dyL; dxL = x; dyL = y; var a = y * g0
            x = r[i]; y = x - dxR + dcR * dyR; dxR = x; dyR = y; var b = y * g0
            if (spk) { a = hpL2.process(hpL1.process(a)); b = hpR2.process(hpR1.process(b)) }
            // the peak at this sample and just before it (between samples, by cubic interpolation)
            val ma = (9f * (a2 + a1) - a3 - a) * 0.0625f; val mb = (9f * (b2 + b1) - b3 - b) * 0.0625f
            a3 = a2; a2 = a1; a1 = a; b3 = b2; b2 = b1; b1 = b
            val pk = max(max(abs(a), abs(b)), max(abs(ma), abs(mb)))
            val need = if (pk > ceiling) ceiling / pk else 1f
            // running minimum of what's needed over the look-ahead window
            val idx = count
            while (qLen > 0 && qVal[(qHead + qLen - 1) % MAX_LA] >= need) qLen--
            val t = (qHead + qLen) % MAX_LA; qVal[t] = need; qIdx[t] = idx; qLen++
            while (idx - qIdx[qHead] > la) { qHead = (qHead + 1) % MAX_LA; qLen-- }
            val target = qVal[qHead]
            // release: down at once, back up smoothly, slower when it keeps leaning on the ceiling
            slowEnv += (target - slowEnv) * slowK
            held = if (target < held) target else {
                val k = if (slowEnv < 0.9f) relSlow else relFast
                target + (held - target) * k
            }
            // smooth the gain over the look-ahead (this is what keeps peaks under the ceiling)
            val ap = idx % la
            avgSum += held - avg[ap]; avg[ap] = held
            var g = (avgSum / la).toFloat()
            if (g > 1f) g = 1f
            if (g < minG) minG = g
            // out comes the sample from the start of the window
            val dp = pos
            val oa = dl[dp] * g; val ob = dr[dp] * g
            dl[dp] = a; dr[dp] = b
            pos = if (pos + 1 >= la) 0 else pos + 1
            count++
            if (count >= 1 shl 30) { count -= 1 shl 29; for (j in 0 until qLen) qIdx[(qHead + j) % MAX_LA] -= 1 shl 29 }
            // a last guard that never acts in practice (rounding)
            l[i] = oa.coerceIn(-ceiling, ceiling); r[i] = ob.coerceIn(-ceiling, ceiling)
        }
        // keep the running sum from drifting
        if (count and 0xFFFF < n) { var s = 0.0; for (j in 0 until la) s += avg[j]; avgSum = s }
        reduction = minG
        if (!spk && hpHz != 0f) { hpHz = 0f; hpL1.reset(); hpL2.reset(); hpR1.reset(); hpR2.reset() }
    }

    companion object {
        const val SPEAKER_HP = 110f
        /** Just under 0 dBFS (−0.1 dB), measured between samples too. */
        const val CEILING = 0.9886f
        /** On the phone's own speaker, 1 dB lower (its amplifier is happier). */
        const val CEILING_SPEAKER = 0.881f
        private const val MAX_LA = 512
    }
}

/** Direct-form-I biquad; only the high-pass design is needed so far. */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    /** Butterworth high-pass (Q = 0.707). */
    fun highPass(hz: Float) {
        val w = TAU * hz / SRF; val cs = kotlin.math.cos(w); val al = sin(w) / (2f * 0.7071f)
        val a0 = 1f + al
        b0 = (1f + cs) / 2f / a0; b1 = -(1f + cs) / a0; b2 = b0; a1 = -2f * cs / a0; a2 = (1f - al) / a0
    }
    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
}

/** Real-time pitch shifter: two crossfaded read heads over a delay line. */
class PitchShifter {
    private val line = DelayLine(SR / 2)
    private var phase = 0f
    fun process(x: Float, ratio: Float, window: Float): Float {
        line.write(x)
        val win = max(64f, window * SRF)
        phase += (1f - ratio) / win
        phase -= floor(phase)
        val p2 = (phase + 0.5f) % 1f
        return line.read(phase * win) * sin(PI.toFloat() * phase) + line.read(p2 * win) * sin(PI.toFloat() * p2)
    }
}

fun clamp01(v: Float) = min(1f, max(0f, v))
