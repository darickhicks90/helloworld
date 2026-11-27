package app.foxbox.fx

import app.foxbox.audio.Fmt
import app.foxbox.audio.Params
import app.foxbox.audio.SRF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * The inserts on one lane of the Fire Escape (a block, or an audio track): a
 * filter (low, high or band pass), a compressor, a three-band compressor and
 * Wraith. The two compressors can listen to another lane (`sources` names them,
 * after "Self") so that lane's sound pushes this one down: a sidechain.
 * Everything starts off, so a fresh lane passes its sound through untouched.
 * Runs on the audio thread; buffers are made here, never while processing.
 */
class LaneFx(val sources: List<String>) {
    val p = Params()
    private val keys = listOf("Self") + sources

    init { p.section("Filter") }
    val fOn = p.toggle("fOn", "Filter")
    val fMode = p.choice("fMode", "Type", listOf("Low pass", "High pass", "Band pass"))
    val fCut = p.num("fCut", "Cutoff", 30f, 18000f, 1200f, Fmt.HZ, exp = true)
    val fRes = p.num("fRes", "Resonance", 0f, 1f, 0.25f, Fmt.PCT)
    val fMix = p.num("fMix", "Filter Mix", 0f, 1f, 1f, Fmt.PCT)

    init { p.section("Compressor") }
    val cOn = p.toggle("cOn", "Compressor")
    val cSide = p.choice("cSide", "Sidechain", keys)
    val cThresh = p.num("cThresh", "Threshold", -48f, 0f, -18f, Fmt.DB)
    val cRatio = p.num("cRatio", "Ratio", 1f, 20f, 4f, Fmt.X, exp = true)
    val cAttack = p.num("cAttack", "Attack", 0.0005f, 0.1f, 0.005f, Fmt.SEC, exp = true)
    val cRelease = p.num("cRelease", "Release", 0.02f, 1.2f, 0.18f, Fmt.SEC, exp = true)
    val cMakeup = p.num("cMakeup", "Makeup", 0f, 24f, 0f, Fmt.DB)

    init { p.section("Multiband") }
    val mOn = p.toggle("mOn", "Multiband")
    val mSide = p.choice("mSide", "Sidechain", keys)
    val mLowX = p.num("mLowX", "Low / Mid", 60f, 800f, 220f, Fmt.HZ, exp = true)
    val mHighX = p.num("mHighX", "Mid / High", 1000f, 9000f, 2800f, Fmt.HZ, exp = true)
    val mLowT = p.num("mLowT", "Low Threshold", -48f, 0f, -20f, Fmt.DB)
    val mMidT = p.num("mMidT", "Mid Threshold", -48f, 0f, -20f, Fmt.DB)
    val mHighT = p.num("mHighT", "High Threshold", -48f, 0f, -20f, Fmt.DB)
    val mRatio = p.num("mRatio", "Ratio", 1f, 20f, 3f, Fmt.X, exp = true)
    val mAttack = p.num("mAttack", "Attack", 0.0005f, 0.1f, 0.008f, Fmt.SEC, exp = true)
    val mRelease = p.num("mRelease", "Release", 0.02f, 1.2f, 0.2f, Fmt.SEC, exp = true)
    val mLowG = p.num("mLowG", "Low Gain", -12f, 12f, 0f, Fmt.DB)
    val mMidG = p.num("mMidG", "Mid Gain", -12f, 12f, 0f, Fmt.DB)
    val mHighG = p.num("mHighG", "High Gain", -12f, 12f, 0f, Fmt.DB)

    /** The experimental scale feedback loop, with its own settings (and presets). */
    val wraith = Wraith()

    /** Which lane the compressor / multiband listen to: -1 for itself, else an index into `sources`. */
    val compKey get() = cSide.index - 1
    val bandKey get() = mSide.index - 1
    val active get() = fOn.on || cOn.on || mOn.on || wraith.on.on

    /** Gain reduction right now, dB (compressor, and the deepest band), for meters. */
    var compReduction = 0f; private set
    var bandReduction = 0f; private set

    fun reset() { p.all.values.forEach { it.set(it.def) }; wraith.reset() }

    // ------------------------------------------------------------------ filter

    private val svfL = Zdf(); private val svfR = Zdf()
    private var fCutNow = -1f

    private fun filter(l: FloatArray, r: FloatArray, n: Int) {
        val target = fCut.value; val res = fRes.value; val mode = fMode.index; val mix = fMix.value
        if (fCutNow < 0f) fCutNow = target
        var i = 0
        while (i < n) {
            // glide the cutoff every 16 samples (no zipper noise when it's swept)
            fCutNow += (target - fCutNow) * 0.25f
            svfL.set(fCutNow, res); svfR.set(fCutNow, res)
            val e = minOf(n, i + 16)
            while (i < e) {
                val a = svfL.run(l[i], mode); val b = svfR.run(r[i], mode)
                l[i] += (a - l[i]) * mix; r[i] += (b - r[i]) * mix
                i++
            }
        }
    }

    // ------------------------------------------------------------------ compressor

    private var cEnv = 0f; private var cGr = 0f

    private fun compress(l: FloatArray, r: FloatArray, n: Int, key: FloatArray?) {
        val th = cThresh.value; val ratio = cRatio.value; val mk = 10f.pow(cMakeup.value / 20f)
        val atk = coef(cAttack.value); val rel = coef(cRelease.value)
        var env = cEnv; var gr = cGr; var deepest = 0f
        for (i in 0 until n) {
            val x = if (key != null) abs(key[i]) else max(abs(l[i]), abs(r[i]))
            env = if (x > env) atk * env + (1 - atk) * x else rel * env + (1 - rel) * x
            val want = reduction(toDb(env), th, ratio)
            // gain changes glide too: down at the attack speed, back up at the release speed
            gr = if (want > gr) atk * gr + (1 - atk) * want else rel * gr + (1 - rel) * want
            if (gr > deepest) deepest = gr
            val g = fromDb(-gr) * mk
            l[i] *= g; r[i] *= g
        }
        cEnv = env; cGr = gr; compReduction = deepest
    }

    // ------------------------------------------------------------------ multiband (Linkwitz-Riley crossovers)

    private val xl = Split(); private val xr = Split(); private val xk = Split()
    private val bEnv = FloatArray(3); private val bGr = FloatArray(3)
    private val lo = FloatArray(3); private val ro = FloatArray(3); private val ko = FloatArray(3)
    private val bTh = FloatArray(3); private val bGain = FloatArray(3)
    private var xSig = Float.NaN

    private fun multiband(l: FloatArray, r: FloatArray, n: Int, key: FloatArray?) {
        val sig = mLowX.value * 7f + mHighX.value
        if (sig != xSig) {
            xSig = sig
            val f1 = mLowX.value; val f2 = maxOf(mHighX.value, f1 * 2f)
            xl.set(f1, f2); xr.set(f1, f2); xk.set(f1, f2)
        }
        val th = bTh; th[0] = mLowT.value; th[1] = mMidT.value; th[2] = mHighT.value
        val gains = bGain; gains[0] = fromDb(mLowG.value); gains[1] = fromDb(mMidG.value); gains[2] = fromDb(mHighG.value)
        val ratio = mRatio.value; val atk = coef(mAttack.value); val rel = coef(mRelease.value)
        var deepest = 0f
        for (i in 0 until n) {
            xl.run(l[i], lo); xr.run(r[i], ro)
            if (key != null) xk.run(key[i], ko)
            var outL = 0f; var outR = 0f
            for (b in 0 until 3) {
                val x = if (key != null) abs(ko[b]) else max(abs(lo[b]), abs(ro[b]))
                var env = bEnv[b]
                env = if (x > env) atk * env + (1 - atk) * x else rel * env + (1 - rel) * x
                bEnv[b] = env
                val want = reduction(toDb(env), th[b], ratio)
                var gr = bGr[b]
                gr = if (want > gr) atk * gr + (1 - atk) * want else rel * gr + (1 - rel) * want
                bGr[b] = gr
                if (gr > deepest) deepest = gr
                val g = fromDb(-gr) * gains[b]
                outL += lo[b] * g; outR += ro[b] * g
            }
            l[i] = outL; r[i] = outR
        }
        bandReduction = deepest
    }

    // ------------------------------------------------------------------

    /**
     * Process one block in place. `compKeyBuf` / `bandKeyBuf` are the chosen
     * sidechain lanes' sound (mono), or null to listen to this lane itself.
     */
    fun process(l: FloatArray, r: FloatArray, n: Int, compKeyBuf: FloatArray?, bandKeyBuf: FloatArray?, bpm: Float, tick: Double, scaleMask: Int) {
        if (fOn.on) filter(l, r, n) else fCutNow = -1f
        if (cOn.on) compress(l, r, n, compKeyBuf) else { cEnv = 0f; cGr = 0f; compReduction = 0f }
        if (mOn.on) multiband(l, r, n, bandKeyBuf) else bandReduction = 0f
        if (wraith.on.on) wraith.process(l, r, n, bpm, tick, scaleMask) else wraith.idle()
    }

    private fun coef(sec: Float) = exp(-1f / (sec * SRF))

    companion object {
        /** dB of gain reduction for a level, with a 6 dB soft knee. */
        fun reduction(db: Float, th: Float, ratio: Float): Float {
            val knee = 6f; val over = db - th
            val slope = 1f - 1f / ratio
            return when {
                over <= -knee / 2 -> 0f
                over >= knee / 2 -> over * slope
                else -> slope * (over + knee / 2) * (over + knee / 2) / (2 * knee)
            }
        }
        fun toDb(x: Float) = if (x > 1e-6f) 20f * log10(x) else -120f
        fun fromDb(db: Float) = 10f.pow(db / 20f)
    }
}

/** A zero-delay-feedback state variable filter giving low, high or band pass (band at unity gain). */
class Zdf {
    private var g = 0f; private var k = 1f; private var a1 = 0f; private var a2 = 0f; private var a3 = 0f
    private var ic1 = 0f; private var ic2 = 0f
    fun set(hz: Float, res: Float) {
        g = tan(PI * hz.coerceIn(10f, SRF * 0.45f) / SRF).toFloat()
        k = 2f - 1.96f * res.coerceIn(0f, 1f)
        a1 = 1f / (1f + g * (g + k)); a2 = g * a1; a3 = g * a2
    }
    /** mode 0 low, 1 high, 2 band. */
    fun run(x: Float, mode: Int): Float {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1; ic2 = 2f * v2 - ic2
        return when (mode) { 0 -> v2; 1 -> x - k * v1 - v2; else -> v1 * k }
    }
    fun reset() { ic1 = 0f; ic2 = 0f }
}

/** A biquad with Butterworth low- and high-pass designs (two in a row make a Linkwitz-Riley crossover). */
class Bq {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    fun set(hz: Float, high: Boolean) {
        val w = (2.0 * PI * hz.coerceIn(10f, SRF * 0.45f) / SRF)
        val cs = cos(w).toFloat(); val al = (sin(w) / (2.0 * 0.70710678)).toFloat()
        val a0 = 1f + al
        if (high) { b0 = (1f + cs) / 2f / a0; b1 = -(1f + cs) / a0 } else { b0 = (1f - cs) / 2f / a0; b1 = (1f - cs) / a0 }
        b2 = b0; a1 = -2f * cs / a0; a2 = (1f - al) / a0
    }
    fun run(x: Float): Float { val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y }
}

/** Splits a signal into low, mid and high with Linkwitz-Riley (24 dB/oct) crossovers that add back up flat. */
class Split {
    private val lp1a = Bq(); private val lp1b = Bq(); private val hp1a = Bq(); private val hp1b = Bq()
    private val lp2a = Bq(); private val lp2b = Bq(); private val hp2a = Bq(); private val hp2b = Bq()
    // the low band goes through the same all-pass as the other two, so the three line up
    private val apLp = Bq(); private val apLp2 = Bq(); private val apHp = Bq(); private val apHp2 = Bq()
    fun set(f1: Float, f2: Float) {
        lp1a.set(f1, false); lp1b.set(f1, false); hp1a.set(f1, true); hp1b.set(f1, true)
        lp2a.set(f2, false); lp2b.set(f2, false); hp2a.set(f2, true); hp2b.set(f2, true)
        apLp.set(f2, false); apLp2.set(f2, false); apHp.set(f2, true); apHp2.set(f2, true)
    }
    fun run(x: Float, out: FloatArray) {
        val low = lp1b.run(lp1a.run(x))
        val rest = hp1b.run(hp1a.run(x))
        out[0] = apLp2.run(apLp.run(low)) + apHp2.run(apHp.run(low))
        out[1] = lp2b.run(lp2a.run(rest))
        out[2] = hp2b.run(hp2a.run(rest))
    }
}
