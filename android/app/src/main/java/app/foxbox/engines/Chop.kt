package app.foxbox.engines

import app.foxbox.audio.SR
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cutting one sample into pieces for Pickup's pads (for flipping a sample).
 * Each function returns slice start frames, in order, starting at 0.
 */
object Chop {
    /** `n` equal slices. */
    fun equal(d: FloatArray, n: Int): IntArray = IntArray(n) { (d.size.toLong() * it / n).toInt() }

    /** One slice per `beats` beats at `bpm` (a loop recorded or cut to the clock). */
    fun beats(d: FloatArray, bpm: Float, beats: Float, max: Int): IntArray {
        val len = (SR * 60.0 / bpm * beats).toInt().coerceAtLeast(SR / 50)
        return IntArray(minOf(max, (d.size + len - 1) / len)) { it * len }
    }

    /**
     * Slices at hits: an onset is where the short-term energy jumps well above
     * the recent average. `sensitivity` 0..1 (higher finds quieter hits).
     * At most `max` slices; the strongest onsets win, kept at least 60 ms apart.
     */
    fun transients(d: FloatArray, max: Int, sensitivity: Float = 0.5f): IntArray {
        val hop = SR / 200                      // 5 ms frames
        val frames = d.size / hop
        if (frames < 4) return intArrayOf(0)
        val e = FloatArray(frames) { f -> var s = 0f; val o = f * hop; for (k in o until minOf(d.size, o + hop)) s += d[k] * d[k]; sqrt(s / hop) }
        val ratio = 1.4f + (1f - sensitivity.coerceIn(0f, 1f)) * 2.6f
        val peak = e.maxOrNull() ?: 0f
        val floor = peak * 0.02f * (1.2f - sensitivity)
        val cand = ArrayList<Pair<Int, Float>>()   // frame, strength
        var avg = e[0]
        for (f in 1 until frames) {
            val rise = e[f] - e[f - 1]
            if (e[f] > floor && e[f] > avg * ratio && rise > 0f) cand += f to rise
            avg = avg * 0.9f + e[f] * 0.1f
        }
        val minGap = 12                            // 60 ms
        val chosen = ArrayList<Int>()
        for ((f, _) in cand.sortedByDescending { it.second }) {
            if (chosen.size >= max - 1) break
            if (f < minGap / 2) continue
            if (chosen.none { abs(it - f) < minGap }) chosen += f
        }
        // Back each onset up to where the sound starts rising, so the attack isn't cut.
        val starts = chosen.map { f -> var g = f; while (g > 0 && e[g - 1] < e[g] && f - g < 4) g--; g * hop }.sorted()
        return (listOf(0) + starts).distinct().toIntArray()
    }

    /** Cut `d` at `starts` into separate sounds, each with short fades so they don't click. */
    fun slices(d: FloatArray, starts: IntArray): List<FloatArray> {
        val fadeIn = SR / 1000; val fadeOut = SR / 250
        return starts.indices.mapNotNull { i ->
            val a = starts[i].coerceIn(0, d.size); val b = (if (i + 1 < starts.size) starts[i + 1] else d.size).coerceIn(a, d.size)
            if (b - a < SR / 100) return@mapNotNull null
            d.copyOfRange(a, b).also { s ->
                for (k in 0 until minOf(fadeIn, s.size)) s[k] *= k / fadeIn.toFloat()
                for (k in 0 until minOf(fadeOut, s.size)) s[s.size - 1 - k] *= k / fadeOut.toFloat()
            }
        }
    }
}
