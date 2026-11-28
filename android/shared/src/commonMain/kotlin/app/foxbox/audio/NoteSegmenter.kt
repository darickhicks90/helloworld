package app.foxbox.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a pitch track (one look every ~10 ms) into notes, after the fact, taking its
 * time to get it right rather than jumping at every wobble:
 *  - finds how far the whole take sits off concert pitch and allows for it;
 *  - smooths the pitch (a median, so a stray octave jump or a crack vanishes);
 *  - only lands on a note once the voice has settled on it for a while, and only
 *    leaves it once it has clearly, steadily moved (slides and vibrato stay one note);
 *  - splits a held pitch where the voice dips and comes back (a new syllable or pluck);
 *  - drops scraps too short to be meant, and joins pieces of the same note.
 */
object NoteSegmenter {
    class Note(val note: Int, val startMs: Double, val endMs: Double, val vel: Float)
    /** The notes, and how far off concert pitch the take was (cents; + sharp). */
    class Result(val notes: List<Note>, val cents: Int)

    /**
     * `t` times (ms), `m` pitch as a fractional MIDI note (NaN where there was no clear
     * pitch), `db` loudness; `n` frames. `mask`: the scale to land on (0 = any semitone).
     */
    /**
     * `settleMs`: how long the voice must hold a note before it counts; `stick`: how far
     * (in semitones) it must move, steadily, before it changes note; `minMs`: the
     * shortest note kept.
     */
    fun segment(t: DoubleArray, m: FloatArray, db: FloatArray, n: Int, mask: Int = 0, minMs: Double = 110.0, settleMs: Double = 60.0, stick: Float = 0.7f): Result {
        if (n < 3) return Result(emptyList(), 0)
        // 1. tuning: where the steady parts sit between semitones (on a circle, so +0.5 and -0.5 meet)
        var sx = 0.0; var sy = 0.0
        for (i in 1 until n) {
            val a = m[i]; val b = m[i - 1]
            if (a.isNaN() || b.isNaN() || abs(a - b) > 0.12f) continue
            val f = a - kotlin.math.round(a)
            sx += cos(2 * PI * f); sy += sin(2 * PI * f)
        }
        val off = if (sx * sx + sy * sy > 4.0) (atan2(sy, sx) / (2 * PI)).toFloat() else 0f
        // 2. a median of 7 looks, voiced only where most of them are
        val c = FloatArray(n) { Float.NaN }
        val win = FloatArray(7)
        for (i in 0 until n) {
            var k = 0
            for (j in i - 3..i + 3) if (j in 0 until n && !m[j].isNaN()) win[k++] = m[j] - off
            if (k >= 4 && !m[i].isNaN()) { win.sort(0, k); c[i] = win[k / 2] }
        }
        fun land(x: Float): Int = if (mask and 0xFFF == 0 || mask and 0xFFF == 0xFFF) kotlin.math.round(x).toInt() else PitchTrack.nearestInScale(x, mask)

        // 3. walk it: settle before starting a note, move off only when clearly gone
        class Seg(val note: Int, val s: Int, var e: Int, val split: Boolean)
        val segs = ArrayList<Seg>()
        var cur: Seg? = null
        var cand = -1; var candStart = 0; var candN = 0
        var away = -1; var awayStart = 0; var awayN = 0
        var gap = 0
        var peak = -120f; var dipped = false
        val settle = (settleMs / 10.0).toInt().coerceAtLeast(2)   // looks on one note before it counts
        val leave = settle + 1                                      // looks clearly elsewhere before moving
        for (i in 0 until n) {
            val x = c[i]
            if (x.isNaN()) {
                gap++
                if (cur != null && gap >= 6) { segs += cur; cur = null }
                cand = -1; candN = 0; away = -1; awayN = 0
                continue
            }
            gap = 0
            val q = land(x)
            val cu = cur
            if (cu == null) {
                if (q == cand && abs(x - q) < 0.45f) candN++ else { cand = q; candStart = i; candN = 1 }
                if (candN >= settle) { cur = Seg(q, candStart, i, false); peak = db[i]; dipped = false; candN = 0 }
                continue
            }
            // a dip in loudness and back: a new syllable or pluck on the same pitch
            if (db[i] > peak) peak = db[i]
            if (db[i] < peak - 10f) dipped = true
            if (dipped && db[i] > peak - 4f && i - cu.s > settle) {
                cu.e = i - 1; segs += cu; cur = Seg(cu.note, i, i, true); peak = db[i]; dipped = false; away = -1; awayN = 0
                continue
            }
            if (abs(x - cu.note) < stick) { cu.e = i; away = -1; awayN = 0; continue }
            if (q == away) awayN++ else { away = q; awayStart = i; awayN = 1 }
            if (awayN >= leave) {
                cu.e = awayStart - 1; segs += cu
                cur = Seg(q, awayStart, i, false); peak = db[i]; dipped = false; away = -1; awayN = 0
            }
        }
        cur?.let { segs += it }

        // 4. join pieces of the same note split by a crack, then drop scraps
        val joined = ArrayList<Seg>()
        for (s in segs) {
            val last = joined.lastOrNull()
            if (last != null && last.note == s.note && !s.split && t[s.s] - t[last.e] < 80.0) last.e = s.e else joined += s
        }
        val out = ArrayList<Note>()
        for (s in joined) {
            val a = t[s.s]; val b = t[s.e] + 10.0
            if (b - a < minMs) continue
            var lv = 0f; var k = 0
            for (i in s.s..s.e) if (!c[i].isNaN()) { lv += db[i]; k++ }
            val vel = if (k > 0) ((lv / k + 50f) / 40f).coerceIn(0.35f, 1f) else 0.7f
            out += Note(s.note, a, b, vel)
        }
        return Result(out, kotlin.math.round(off * 100).toInt())
    }
}
