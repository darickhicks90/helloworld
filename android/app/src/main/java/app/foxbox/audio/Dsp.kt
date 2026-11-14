package app.foxbox.audio

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
        var p = w - 1 - d.coerceIn(0f, (buf.size - 3).toFloat())
        while (p < 0) p += buf.size
        val i = p.toInt(); val f = p - i
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

/** Gentle peak limiter with a soft-clip safety stage. */
class Limiter {
    private var env = 0f
    fun process(l: FloatArray, r: FloatArray, n: Int, gain: Float) {
        for (i in 0 until n) {
            val a = max(abs(l[i]), abs(r[i])) * gain
            env = if (a > env) a else env * 0.9995f
            val g = if (env > 0.89f) 0.89f / env else 1f
            l[i] = soft(l[i] * gain * g); r[i] = soft(r[i] * gain * g)
        }
    }
    private fun soft(x: Float) = if (x > 1f) 1f else if (x < -1f) -1f else x * (1.5f - 0.5f * x * x)
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
