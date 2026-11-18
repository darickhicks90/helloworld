package app.foxbox.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Basic dynamics and effects for one audio track (or, with `master`, the master
 * bus): a noise gate (tracks), a compressor, a three-band EQ, drive, an echo and
 * a reverb send (tracks), or stereo width (master). Every setting starts neutral,
 * so a fresh strip passes the sound through untouched. Runs on the audio thread.
 */
class StripFx(val master: Boolean) {
    val p = Params()

    init { p.section("Dynamics") }
    val gate: Param? = if (!master) p.num("gate", "Gate", -80f, -20f, -80f, Fmt.DB) else null
    val thresh = p.num("thresh", "Threshold", -40f, 0f, 0f, Fmt.DB)
    val ratio = p.num("ratio", "Ratio", 1f, 12f, 4f, Fmt.X, exp = true)
    val attack = p.num("attack", "Attack", 0.001f, 0.08f, 0.01f, Fmt.SEC, exp = true)
    val release = p.num("release", "Release", 0.03f, 0.8f, 0.15f, Fmt.SEC, exp = true)
    val makeup = p.num("makeup", "Makeup", 0f, 18f, 0f, Fmt.DB)
    init { p.section("EQ") }
    val low = p.num("low", "Low", -12f, 12f, 0f, Fmt.DB)
    val mid = p.num("mid", "Mid", -12f, 12f, 0f, Fmt.DB)
    val high = p.num("high", "High", -12f, 12f, 0f, Fmt.DB)
    init { p.section("Effects") }
    val drive = p.num("drive", "Drive", 0f, 1f, 0f, Fmt.PCT)
    val echo: Param? = if (!master) p.num("echo", "Echo", 0f, 1f, 0f, Fmt.PCT) else null
    val echoTime: Param? = if (!master) p.num("echoTime", "Echo Time", 0.06f, 1f, 0.32f, Fmt.SEC, exp = true) else null
    val echoFb: Param? = if (!master) p.num("echoFb", "Echo Feedback", 0f, 0.85f, 0.35f, Fmt.PCT) else null
    val reverb: Param? = if (!master) p.num("reverb", "Reverb Send", 0f, 1f, 0f, Fmt.PCT) else null
    val width: Param? = if (master) p.num("width", "Width", 0f, 2f, 1f, Fmt.X) else null

    /** Back to neutral. */
    fun reset() { p.all.values.forEach { it.set(it.def) } }

    /** Gain reduction in dB right now (for a meter). */
    var reduction = 0f; private set

    // ---- EQ: low shelf 120 Hz, peak 1 kHz, high shelf 6 kHz (RBJ biquads), per side
    private class Bq { var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f; var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        fun run(x: Float): Float { val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y }
        fun copy(o: Bq) { b0 = o.b0; b1 = o.b1; b2 = o.b2; a1 = o.a1; a2 = o.a2 } }
    private val eqL = Array(3) { Bq() }; private val eqR = Array(3) { Bq() }
    private var eqSig = Float.NaN

    private fun shelf(q: Bq, hz: Float, db: Float, highShelf: Boolean) {
        val a = 10f.pow(db / 40f); val w = TAU * hz / SRF; val cs = cos(w); val sn = sin(w)
        val al = sn / 2f * sqrt(2f); val sa = 2f * sqrt(a) * al
        val b0: Float; val b1: Float; val b2: Float; val a0: Float; val a1: Float; val a2: Float
        if (!highShelf) {
            b0 = a * ((a + 1) - (a - 1) * cs + sa); b1 = 2 * a * ((a - 1) - (a + 1) * cs); b2 = a * ((a + 1) - (a - 1) * cs - sa)
            a0 = (a + 1) + (a - 1) * cs + sa; a1 = -2 * ((a - 1) + (a + 1) * cs); a2 = (a + 1) + (a - 1) * cs - sa
        } else {
            b0 = a * ((a + 1) + (a - 1) * cs + sa); b1 = -2 * a * ((a - 1) + (a + 1) * cs); b2 = a * ((a + 1) + (a - 1) * cs - sa)
            a0 = (a + 1) - (a - 1) * cs + sa; a1 = 2 * ((a - 1) - (a + 1) * cs); a2 = (a + 1) - (a - 1) * cs - sa
        }
        q.b0 = b0 / a0; q.b1 = b1 / a0; q.b2 = b2 / a0; q.a1 = a1 / a0; q.a2 = a2 / a0
    }
    private fun peak(q: Bq, hz: Float, db: Float, qv: Float) {
        val a = 10f.pow(db / 40f); val w = TAU * hz / SRF; val cs = cos(w); val al = sin(w) / (2f * qv)
        val a0 = 1 + al / a
        q.b0 = (1 + al * a) / a0; q.b1 = -2 * cs / a0; q.b2 = (1 - al * a) / a0; q.a1 = -2 * cs / a0; q.a2 = (1 - al / a) / a0
    }

    // ---- dynamics and echo state
    private var env = 0f; private var gateGain = 1f
    private val dlL = DelayLine((SR * 1.05f).toInt()); private val dlR = DelayLine((SR * 1.05f).toInt())

    /** Process `n` frames in place; tracks also add their reverb send into revL / revR. */
    fun process(l: FloatArray, r: FloatArray, n: Int, revL: FloatArray? = null, revR: FloatArray? = null) {
        // EQ coefficients follow the knobs
        val lo = low.value; val mi = mid.value; val hi = high.value
        val sig = lo * 31f + mi * 7f + hi
        if (sig != eqSig) {
            eqSig = sig
            shelf(eqL[0], 120f, lo, false); peak(eqL[1], 1000f, mi, 0.8f); shelf(eqL[2], 6000f, hi, true)
            for (k in 0 until 3) eqR[k].copy(eqL[k])
        }
        val eqOn = abs(lo) > 0.05f || abs(mi) > 0.05f || abs(hi) > 0.05f
        val th = thresh.value; val compOn = th < -0.1f
        val rt = ratio.value
        val atk = exp(-1f / (attack.value * SRF)); val rel = exp(-1f / (release.value * SRF))
        val mk = if (makeup.value > 0.05f) 10f.pow(makeup.value / 20f) else 1f
        val gt = gate?.value ?: -80f; val gateOn = gt > -79.5f
        val gOpen = exp(-1f / (0.002f * SRF)); val gClose = exp(-1f / (0.08f * SRF))
        val dr = drive.value; val dg = 1f + dr * 8f
        val ec = echo?.value ?: 0f; val ed = (echoTime?.value ?: 0.3f) * SRF; val efb = echoFb?.value ?: 0f
        val rv = reverb?.value ?: 0f
        val wd = width?.value ?: 1f
        var red = 0f
        for (i in 0 until n) {
            var a = l[i]; var b = r[i]
            if (eqOn) { for (k in 0 until 3) { a = eqL[k].run(a); b = eqR[k].run(b) } }
            if (compOn || gateOn) {
                val x = maxOf(abs(a), abs(b))
                env = if (x > env) atk * env + (1 - atk) * x else rel * env + (1 - rel) * x
                val db = if (env > 1e-6f) 20f * log10(env) else -120f
                if (gateOn) { val target = if (db > gt) 1f else 0f; gateGain = if (target > gateGain) gOpen * gateGain + (1 - gOpen) * target else gClose * gateGain + (1 - gClose) * target; a *= gateGain; b *= gateGain }
                if (compOn && db > th) {
                    val over = db - th; val cut = over * (1f - 1f / rt)
                    val g = 10f.pow(-cut / 20f); a *= g; b *= g; if (cut > red) red = cut
                }
            }
            a *= mk; b *= mk
            if (dr > 0.001f) { val ya = a * dg; val yb = b * dg; a = ya / (1f + abs(ya)) * (1f + dr * 0.5f); b = yb / (1f + abs(yb)) * (1f + dr * 0.5f) }
            if (ec > 0.001f) {
                val ea = dlL.read(ed); val eb = dlR.read(ed)
                dlL.write(a + ea * efb); dlR.write(b + eb * efb)
                a += ea * ec; b += eb * ec
            }
            if (master && abs(wd - 1f) > 0.01f) { val m = (a + b) * 0.5f; val s = (a - b) * 0.5f * wd; a = m + s; b = m - s }
            l[i] = a; r[i] = b
            if (revL != null && revR != null && rv > 0.001f) { revL[i] += a * rv; revR[i] += b * rv }
        }
        reduction = red
    }
}
