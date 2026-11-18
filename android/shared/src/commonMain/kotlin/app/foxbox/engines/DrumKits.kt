package app.foxbox.engines

import app.foxbox.audio.Rng
import app.foxbox.audio.SR
import app.foxbox.audio.SRF
import app.foxbox.audio.Svf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pickup's built-in kits, synthesized at start-up (no recorded samples are
 * shipped). [studio] is the default kit: layered, filtered synthesis aimed at
 * natural-sounding drums. [classic] is the original lo-fi kit.
 */
object DrumKits {
    private fun buf(sec: Float) = FloatArray((SR * sec).toInt())
    private fun normalize(d: FloatArray, peak: Float = 0.9f): FloatArray {
        var m = 0f; for (v in d) m = maxOf(m, abs(v))
        if (m > 1e-6f) { val g = peak / m; for (i in d.indices) d[i] *= g }
        // 2 ms fade-out so nothing clicks at the end
        val f = minOf(d.size, (SR * 0.002f).toInt()); for (i in 0 until f) d[d.size - 1 - i] *= i / f.toFloat()
        return d
    }

    /** A small room: a few early reflections and a soft diffuse tail, mixed in quietly. */
    private fun room(d: FloatArray, amount: Float, seed: Int): FloatArray {
        val rnd = Rng(seed)
        val out = d.copyOf()
        val taps = intArrayOf(7, 11, 17, 23, 31, 41).map { (it * SRF / 1000f).toInt() }
        val gains = floatArrayOf(0.5f, 0.4f, 0.32f, 0.26f, 0.2f, 0.15f)
        for (t in taps.indices) for (i in taps[t] until d.size) out[i] += d[i - taps[t]] * gains[t] * amount * (if (t % 2 == 0) 1f else -1f)
        val len = minOf(d.size, (SR * 0.25f).toInt())
        val ir = FloatArray(len) { rnd.bi() * exp(-it / (SRF * 0.05f)) * 0.02f }
        var i = 0
        while (i < d.size) { val x = d[i]; if (abs(x) > 0.02f) { for (k in 0 until len step 3) if (i + k < out.size) out[i + k] += x * ir[k] * amount * 0.9f }; i += 3 }
        return out
    }

    /** A bank of detuned square partials: the metallic core of hats and cymbals. */
    private fun metal(n: Int, base: Float, ratios: FloatArray): FloatArray {
        val out = FloatArray(n); val ph = FloatArray(ratios.size)
        for (i in 0 until n) {
            var s = 0f
            for (k in ratios.indices) { ph[k] += base * ratios[k] / SRF; if (ph[k] >= 1f) ph[k] -= 1f; s += if (ph[k] < 0.5f) 1f else -1f }
            out[i] = s / ratios.size
        }
        return out
    }
    private val METAL = floatArrayOf(1f, 1.342f, 1.677f, 2.213f, 2.597f, 3.811f)

    fun studio(i: Int): FloatArray {
        val rnd = Rng(500 + i)
        return when (i) {
            0 -> { // Kick: pitch-swept body, beater click and a little drive
                val d = buf(0.55f); var ph = 0.0
                val hp = Svf().apply { set(1800f, 0.1f) }
                for (k in d.indices) {
                    val t = k / SRF
                    val f = 46.0 + 120.0 * exp(-t * 28.0) + 40.0 * exp(-t * 140.0)
                    ph += 2 * PI * f / SR
                    val body = sin(ph) * exp(-t * 5.2) * (1 - exp(-t * 900.0))
                    hp.process(rnd.bi()); val click = hp.high * exp(-t * 320f) * 0.35f
                    d[k] = (tanh(body * 1.6) / 1.2).toFloat() + click
                }
                normalize(d)
            }
            1 -> { // Snare: two drum-head modes plus filtered snare-wire noise, in a small room
                val d = buf(0.42f); var p1 = 0.0; var p2 = 0.0
                val bp = Svf().apply { set(4200f, 0.35f) }; val hp = Svf().apply { set(1200f, 0.1f) }
                for (k in d.indices) {
                    val t = k / SRF
                    p1 += 2 * PI * (182.0 * (1 + 0.15 * exp(-t * 60.0))) / SR; p2 += 2 * PI * 331.0 / SR
                    val head = (sin(p1) * 0.7 * exp(-t * 22.0) + sin(p2) * 0.35 * exp(-t * 30.0)).toFloat()
                    val n = rnd.bi(); bp.process(n); hp.process(n)
                    val wires = (bp.band * 1.4f + hp.high * 0.5f) * (exp(-t * 13f) * 0.8f + exp(-t * 45f) * 0.6f)
                    d[k] = head + wires * 0.9f
                }
                normalize(room(d, 0.35f, 11))
            }
            2 -> { // Clap: four quick bursts then a short diffuse tail, band-limited around 1.2 kHz
                val d = buf(0.45f)
                val bp = Svf().apply { set(1250f, 0.45f) }; val bp2 = Svf().apply { set(2600f, 0.3f) }
                val starts = floatArrayOf(0f, 0.011f, 0.021f, 0.034f)
                for (k in d.indices) {
                    val t = k / SRF
                    var env = 0f
                    for ((j, s) in starts.withIndex()) if (t >= s) env = maxOf(env, exp(-(t - s) * (if (j == 3) 16f else 180f)))
                    val n = rnd.bi(); bp.process(n); bp2.process(n)
                    d[k] = (bp.band * 1.2f + bp2.band * 0.6f) * env
                }
                normalize(room(d, 0.5f, 12))
            }
            3, 4 -> { // Hats: metallic partials, high-passed, with a crisp noise top
                val sec = if (i == 3) 0.09f else 0.65f
                val m = metal((SR * sec).toInt(), 410f, METAL)
                val hp = Svf().apply { set(7200f, 0.2f) }; val hp2 = Svf().apply { set(7200f, 0.2f) }; val nh = Svf().apply { set(9000f, 0.1f) }
                val dec = if (i == 3) 55f else 4.6f
                normalize(FloatArray(m.size) { k ->
                    val t = k / SRF
                    hp.process(m[k]); hp2.process(hp.high); nh.process(rnd.bi())
                    (hp2.high * 0.8f + nh.high * 0.35f) * exp(-t * dec) * (1 - exp(-t * 3000f))
                }, 0.8f)
            }
            5, 6, 7 -> { // Toms: pitch-dropping head with an overtone and a stick attack, in the room
                val f0 = floatArrayOf(92f, 132f, 186f)[i - 5]
                val d = buf(0.7f); var p1 = 0.0; var p2 = 0.0
                val hp = Svf().apply { set(2500f, 0.1f) }
                for (k in d.indices) {
                    val t = k / SRF
                    val bend = 1 + 0.35 * exp(-t * 18.0)
                    p1 += 2 * PI * f0 * bend / SR; p2 += 2 * PI * f0 * 1.58 * bend / SR
                    hp.process(rnd.bi())
                    d[k] = (sin(p1) * exp(-t * 6.5) + 0.3 * sin(p2) * exp(-t * 11.0)).toFloat() + hp.high * exp(-t * 200f) * 0.25f
                }
                normalize(room(d, 0.3f, 20 + i))
            }
            8 -> { // Rim: a hard woody click with two short resonances
                val d = buf(0.09f); var p1 = 0.0; var p2 = 0.0
                for (k in d.indices) {
                    val t = k / SRF
                    p1 += 2 * PI * 1720.0 / SR; p2 += 2 * PI * 455.0 / SR
                    d[k] = ((sin(p1) * 0.6 + sin(p2) * 0.5) * exp(-t * 70.0)).toFloat() + rnd.bi() * exp(-t * 900f) * 0.5f
                }
                normalize(d)
            }
            9 -> { // Cowbell: two band-passed square tones with a short metallic decay
                val d = buf(0.45f); var a = 0f; var b = 0f
                val bp = Svf().apply { set(900f, 0.55f) }
                for (k in d.indices) {
                    val t = k / SRF
                    a += 562f / SRF; if (a >= 1f) a -= 1f; b += 838f / SRF; if (b >= 1f) b -= 1f
                    val sq = (if (a < 0.5f) 1f else -1f) + (if (b < 0.5f) 1f else -1f)
                    bp.process(sq)
                    d[k] = bp.band * (exp(-t * 9f) * 0.8f + exp(-t * 40f) * 0.6f)
                }
                normalize(d, 0.8f)
            }
            10 -> { // Shaker: two grainy swishes of high noise
                val d = buf(0.22f)
                val bp = Svf().apply { set(6500f, 0.3f) }
                for (k in d.indices) {
                    val t = k / SRF
                    val env = sin(PI.toFloat() * minOf(1f, t / 0.07f)).let { if (t < 0.07f) it else 0f } * 0.8f +
                        (if (t > 0.09f) sin(PI.toFloat() * minOf(1f, (t - 0.09f) / 0.11f)) else 0f)
                    bp.process(rnd.bi() * (0.6f + 0.4f * rnd.next()))
                    d[k] = bp.band * env
                }
                normalize(d, 0.7f)
            }
            11, 12 -> { // Crash (wash) and Ride (bell and ping)
                val crash = i == 11
                val sec = if (crash) 2.2f else 1.8f
                val m = metal((SR * sec).toInt(), if (crash) 520f else 690f, METAL)
                val m2 = metal(m.size, if (crash) 733f else 1021f, METAL)
                val hp = Svf().apply { set(if (crash) 3800f else 5200f, 0.15f) }
                val bell = Svf().apply { set(2900f, 0.7f) }; val nh = Svf().apply { set(6000f, 0.1f) }
                normalize(FloatArray(m.size) { k ->
                    val t = k / SRF
                    val x = m[k] + m2[k] * 0.7f
                    hp.process(x); bell.process(x); nh.process(rnd.bi())
                    if (crash) (hp.high * 0.6f + nh.high * 0.5f) * (exp(-t * 1.6f) * (1 - exp(-t * 400f)))
                    else hp.high * 0.45f * exp(-t * 2.2f) + bell.band * 0.5f * exp(-t * 3.5f) + nh.high * 0.15f * exp(-t * 30f)
                }, 0.75f)
            }
            13 -> { // Conga: slapped skin, slight pitch bend, warm decay
                val d = buf(0.45f); var p1 = 0.0
                val bp = Svf().apply { set(1600f, 0.3f) }
                for (k in d.indices) {
                    val t = k / SRF
                    p1 += 2 * PI * 318.0 * (1 + 0.08 * exp(-t * 30.0)) / SR
                    bp.process(rnd.bi())
                    d[k] = (sin(p1) * exp(-t * 9.0)).toFloat() + bp.band * exp(-t * 140f) * 0.6f
                }
                normalize(room(d, 0.25f, 31))
            }
            14 -> { // Sub bass hit on A (55 Hz), softly saturated
                val d = buf(1.3f); var p1 = 0.0
                for (k in d.indices) {
                    val t = k / SRF
                    p1 += 2 * PI * 55.0 * (1 + 0.5 * exp(-t * 60.0)) / SR
                    d[k] = (tanh(sin(p1) * 1.8) * exp(-t * 2.2) * (1 - exp(-t * 600.0))).toFloat()
                }
                normalize(d)
            }
            else -> { // Snap: a short bright crack with a hint of tone
                val d = buf(0.16f); var p1 = 0.0
                val bp = Svf().apply { set(2300f, 0.5f) }
                for (k in d.indices) {
                    val t = k / SRF
                    p1 += 2 * PI * 1450.0 / SR
                    bp.process(rnd.bi())
                    d[k] = bp.band * exp(-t * 55f) * 1.3f + (sin(p1) * exp(-t * 90.0) * 0.2).toFloat()
                }
                normalize(room(d, 0.4f, 33))
            }
        }
    }

    /** The original lo-fi kit (the "Classic Box" bank). */
    fun classic(i: Int): FloatArray {
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
