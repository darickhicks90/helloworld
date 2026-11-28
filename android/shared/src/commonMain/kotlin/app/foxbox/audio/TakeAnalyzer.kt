package app.foxbox.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The careful listen-back after a take: goes through the recorded audio again, every
 * 10 ms, with a longer window than the live listener (steadier on low notes), and only
 * trusts a pitch where the sound stands clearly above the take's own background noise.
 * Gives the pitch track for [NoteSegmenter].
 */
object TakeAnalyzer {
    class Track(val t: DoubleArray, val m: FloatArray, val db: FloatArray, val n: Int)

    /**
     * `audio` mono at `rate`, starting at `startMs` on the listener's clock. `sensDb` the loudness a
     * sound needs, `confidence` how clear its pitch must be (0..1), `octave` shift. `progress` gets 0..1.
     */
    fun track(audio: FloatArray, len: Int, rate: Float, startMs: Double, sensDb: Float, confidence: Float, octave: Int,
              progress: (Float) -> Unit = {}): Track {
        val hop = (rate / 100f).toInt().coerceAtLeast(1)
        val win = 1536
        val frames = ((len - win) / hop).coerceAtLeast(0)
        val t = DoubleArray(frames); val m = FloatArray(frames) { Float.NaN }; val db = FloatArray(frames)
        // loudness first, to find the take's background level
        for (f in 0 until frames) {
            val o = f * hop
            var e = 0f; for (i in o until o + win) e += audio[i] * audio[i]
            val rms = sqrt(e / win)
            db[f] = if (rms > 1e-6f) 20f * log10(rms) else -120f
            t[f] = startMs + (o + win) * 1000.0 / rate
        }
        val sorted = db.copyOf(); sorted.sort()
        // the background: the quietest moments. A sound must stand well clear of it, but never so far
        // that a take with hardly any silence (all singing) loses its own notes
        fun pct(p: Float) = if (frames > 0) sorted[(frames * p).toInt().coerceAtMost(frames - 1)] else -120f
        val gate = maxOf(sensDb, minOf(pct(0.03f) + 9f, pct(0.5f) - 6f))
        val tracker = PitchTrack(2048)
        for (f in 0 until frames) {
            if (f % 50 == 0) progress(f.toFloat() / frames.coerceAtLeast(1))
            if (db[f] < gate) continue
            val hz = tracker.detect(audio, f * hop, win, rate, 65f, 1100f)
            if (hz > 0f && tracker.clarity >= confidence) m[f] = PitchTrack.midi(hz) + 12 * octave
        }
        progress(1f)
        return Track(t, m, db, frames)
    }
}
