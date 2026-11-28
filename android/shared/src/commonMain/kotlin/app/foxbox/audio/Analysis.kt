package app.foxbox.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Listening to a whole piece of audio: its tempo (from how often new sounds start)
 * and its key (from which pitch classes ring most), plus finding the key of a set
 * of notes (for Noodle Forge). Runs off the audio thread.
 */
object Analysis {
    class Result(val bpm: Float, val root: Int, val minor: Boolean, val confidence: Float)

    /** The tempo and key of mono audio at `rate`. Uses up to the first 90 seconds that aren't silent. */
    fun analyze(mono: FloatArray, rate: Int): Result {
        // skip silence at the start
        var start = 0
        while (start < mono.size && abs(mono[start]) < 0.01f) start++
        val end = minOf(mono.size, start + rate * 90)
        val nfft = 2048; val hop = 512
        val frames = maxOf(0, (end - start - nfft) / hop)
        val re = FloatArray(nfft); val im = FloatArray(nfft)
        val win = FloatArray(nfft) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / nfft) }
        val prev = FloatArray(nfft / 2)
        val flux = FloatArray(frames)
        val chroma = DoubleArray(12)
        // which pitch class each bin belongs to (55 Hz .. 2 kHz)
        val pc = IntArray(nfft / 2) { k -> val hz = k * rate.toFloat() / nfft; if (hz < 100f || hz > 2000f) -1 else Math.floorMod(Math.round(PitchTrack.midi(hz)), 12) }
        for (f in 0 until frames) {
            val o = start + f * hop
            for (i in 0 until nfft) { re[i] = mono[o + i] * win[i]; im[i] = 0f }
            fft(re, im)
            var s = 0f
            for (k in 1 until nfft / 2) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val m = ln(1f + 100f * mag)
                val d = m - prev[k]; if (d > 0f) s += d
                prev[k] = m
                // the key: how much each pitch class rings (peaks only, so leakage between bins doesn't smear it)
                if (pc[k] >= 0 && k > 0 && k < nfft / 2 - 1) {
                    val ml = sqrt(re[k - 1] * re[k - 1] + im[k - 1] * im[k - 1]); val mr = sqrt(re[k + 1] * re[k + 1] + im[k + 1] * im[k + 1])
                    if (mag > ml && mag >= mr) {
                        // the peak's true frequency, between bins (parabola through the log magnitudes)
                        val la = ln(ml + 1e-9f); val lb = ln(mag + 1e-9f); val lc = ln(mr + 1e-9f)
                        val den = la - 2 * lb + lc
                        val off = if (abs(den) > 1e-9f) (0.5f * (la - lc) / den).coerceIn(-0.5f, 0.5f) else 0f
                        val hz = (k + off) * rate.toFloat() / nfft
                        chroma[Math.floorMod(Math.round(PitchTrack.midi(hz)), 12)] += mag.toDouble()
                    }
                }
            }
            flux[f] = s
        }
        val bpm = tempo(flux, rate.toFloat() / hop)
        val (root, minor, conf) = key(chroma)
        return Result(bpm, root, minor, conf)
    }

    /** Tempo from an onset-strength curve sampled `fps` times a second. */
    private fun tempo(onset: FloatArray, fps: Float): Float {
        if (onset.size < 64) return 120f
        // take away the slow trend so only the hits are left
        val o = FloatArray(onset.size)
        var avg = onset[0]
        for (i in onset.indices) { avg += (onset[i] - avg) * 0.05f; o[i] = max(0f, onset[i] - avg) }
        val minLag = (fps * 60f / 200f).toInt(); val maxLag = (fps * 60f / 55f).toInt().coerceAtMost(o.size / 2)
        val ac = FloatArray(maxLag + 2)
        for (lag in minLag..maxLag) { var s = 0f; for (i in 0 until o.size - lag) s += o[i] * o[i + lag]; ac[lag] = s / (o.size - lag) }
        var best = minLag; var bs = -1f
        for (lag in minLag..maxLag) {
            val bpm = 60f * fps / lag
            // prefer tempos near 120 a little, and reward a lag whose double also lines up
            val pref = exp(-0.5f * (ln(bpm / 118f) / 0.55f).let { it * it })
            val dbl = if (lag * 2 <= maxLag) ac[lag * 2] * 0.5f else 0f
            val sc = (ac[lag] + dbl) * pref
            if (sc > bs) { bs = sc; best = lag }
        }
        // refine between lags
        val a = ac[best - 1]; val b = ac[best]; val c = ac[minOf(best + 1, maxLag)]
        val den = a - 2 * b + c
        val shift = if (abs(den) > 1e-9f) 0.5f * (a - c) / den else 0f
        return 60f * fps / (best + shift.coerceIn(-0.5f, 0.5f))
    }

    // Krumhansl–Kessler key profiles
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** The best key for a pitch-class weighting: root, minor or not, and how clearly it won (0..1). */
    fun key(chroma: DoubleArray): Triple<Int, Boolean, Float> {
        fun corr(p: DoubleArray, r: Int): Double {
            val mx = chroma.average(); val mp = p.average()
            var num = 0.0; var dx = 0.0; var dp = 0.0
            for (i in 0 until 12) { val x = chroma[(i + r) % 12] - mx; val y = p[i] - mp; num += x * y; dx += x * x; dp += y * y }
            return if (dx <= 0 || dp <= 0) 0.0 else num / sqrt(dx * dp)
        }
        var best = 0; var minor = false; var bc = -2.0; var second = -2.0
        for (r in 0 until 12) for ((m, prof) in listOf(false to MAJOR, true to MINOR)) {
            val c = corr(prof, r)
            if (c > bc) { second = bc; bc = c; best = r; minor = m } else if (c > second) second = c
        }
        return Triple(best, minor, ((bc - second) * 4).toFloat().coerceIn(0f, 1f))
    }

    /** In-place radix-2 FFT (size a power of two). */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
