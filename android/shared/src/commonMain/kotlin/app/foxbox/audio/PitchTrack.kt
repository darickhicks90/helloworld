package app.foxbox.audio

import kotlin.math.abs
import kotlin.math.ln

/**
 * Finds the pitch of a single voice or instrument (YIN). Works on a window of
 * samples you give it, at the rate you say; everything it needs is made up front,
 * so it's safe on the audio thread. 0 Hz means nothing clear was there.
 */
class PitchTrack(private val maxWindow: Int) {
    private val d = FloatArray(maxWindow / 2 + 2)
    /** How sure the last answer was (0..1, higher is surer). */
    var clarity = 0f; private set

    /** The pitch of `buf[start until start + n]` (n ≤ the window size), in Hz, at `rate` samples a second. */
    fun detect(buf: FloatArray, start: Int, n: Int, rate: Float, minHz: Float = 60f, maxHz: Float = 1200f): Float {
        val tauMax = minOf(n / 2, (rate / minHz).toInt(), d.size - 2)
        val tauMin = (rate / maxHz).toInt().coerceAtLeast(2)
        if (tauMax <= tauMin + 2) { clarity = 0f; return 0f }
        val w = n - tauMax
        for (tau in 1..tauMax) {
            var s = 0f
            var i = start
            val e = start + w
            while (i < e) { val x = buf[i] - buf[i + tau]; s += x * x; i++ }
            d[tau] = s
        }
        var run = 0f; d[0] = 1f
        for (tau in 1..tauMax) { run += d[tau]; d[tau] = if (run > 0f) d[tau] * tau / run else 1f }
        var best = -1
        var tau = tauMin
        while (tau < tauMax) {
            if (d[tau] < 0.15f) { while (tau + 1 < tauMax && d[tau + 1] < d[tau]) tau++; best = tau; break }
            tau++
        }
        if (best < 0) {
            var mn = 1f
            for (t in tauMin until tauMax) if (d[t] < mn) { mn = d[t]; best = t }
            if (mn > 0.35f) { clarity = 1f - mn; return 0f }
        }
        clarity = 1f - d[best]
        val a = d[best - 1]; val b = d[best]; val c = d[minOf(best + 1, tauMax)]
        val den = a - 2 * b + c
        val shift = if (abs(den) > 1e-9f) 0.5f * (a - c) / den else 0f
        return rate / (best + shift)
    }

    companion object {
        /** MIDI note (fractional) for a frequency. */
        fun midi(hz: Float) = 69f + 12f * (ln(hz / 440f) / ln(2f))
        /** The nearest note to `m` that is in `mask` (bit n = pitch class n); every note if the mask is empty. */
        fun nearestInScale(m: Float, mask: Int): Int {
            val r = Math.round(m)
            if (mask and 0xFFF == 0 || mask and 0xFFF == 0xFFF) return r
            var best = r; var bd = Float.MAX_VALUE
            for (k in r - 6..r + 6) if ((mask shr Math.floorMod(k, 12)) and 1 == 1) { val dd = abs(k - m); if (dd < bd) { bd = dd; best = k } }
            return best
        }
    }
}
