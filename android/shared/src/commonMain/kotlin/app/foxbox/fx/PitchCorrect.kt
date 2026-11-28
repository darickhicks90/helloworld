package app.foxbox.fx

import app.foxbox.audio.PitchShifter
import app.foxbox.audio.PitchTrack
import app.foxbox.audio.SRF
import kotlin.math.exp
import kotlin.math.pow

/**
 * Pitch correction: follows the pitch of a single voice or instrument and pulls
 * it onto the nearest note of the project's scale (or the nearest semitone).
 * Retune sets how fast: instant gives the hard, stepped robot sound; slower keeps
 * slides and vibrato. Runs on the audio thread; nothing is allocated there.
 */
class PitchCorrect {
    // the voice, a quarter of the sample rate, for the pitch tracker
    private val ring = FloatArray(1024)
    private var rw = 0
    private var acc = 0f; private var accN = 0
    private var hop = 0
    private val window = FloatArray(512)
    private val tracker = PitchTrack(512)
    private val shiftL = PitchShifter(); private val shiftR = PitchShifter()
    /** Semitones the voice is being moved right now (smoothed). */
    private var shift = 0f
    private var want = 0f
    /** The note it's heading for, for the screen (-1 none). */
    var target = -1; private set
    var detected = 0f; private set

    /**
     * `speed` seconds to reach the note (0 = at once), `mix` of the corrected sound, `mask` the
     * scale (0 = every semitone). `sens` 0..1: how readily it trusts a pitch it hears (higher
     * corrects breathier, less clear sounds too). `gateDb`: quieter than this is left alone.
     * `strength` 0..1: how much of the way to the note it pulls. `tolCents`: notes already
     * this close are left as they are (keeps a natural sound).
     */
    fun process(l: FloatArray, r: FloatArray, n: Int, speed: Float, mix: Float, mask: Int,
                sens: Float = 0.6f, gateDb: Float = -60f, strength: Float = 1f, tolCents: Float = 0f) {
        val clarityMin = 0.9f - sens * 0.5f
        val gate = 10f.pow(gateDb / 10f)
        val rate = SRF / 4f
        val k = if (speed <= 0.0015f) 0f else exp(-1f / (speed * SRF))
        for (i in 0 until n) {
            acc += (l[i] + r[i]) * 0.5f; accN++
            if (accN == 4) { ring[rw] = acc / 4f; rw = (rw + 1) and 1023; acc = 0f; accN = 0; hop++ }
            if (hop >= 64) {
                // about every 5 ms: where is the voice, and where should it be?
                hop = 0
                for (j in 0 until 512) window[j] = ring[(rw - 512 + j) and 1023]
                var e = 0f; for (j in 0 until 512) e += window[j] * window[j]
                val hz = if (e / 512f > gate) tracker.detect(window, 0, 512, rate, 70f, 1000f) else 0f
                detected = hz
                if (hz > 0f && tracker.clarity > clarityMin) {
                    val m = PitchTrack.midi(hz)
                    val t = PitchTrack.nearestInScale(m, mask)
                    target = t
                    val off = t - m
                    want = if (kotlin.math.abs(off) * 100f <= tolCents) 0f else off * strength
                } else { target = -1; want = 0f }
            }
            shift = if (k == 0f) want else shift * k + want * (1f - k)
            val ratio = 2f.pow(shift / 12f)
            val a = l[i]; val b = r[i]
            val ca = shiftL.process(a, ratio, 0.025f); val cb = shiftR.process(b, ratio, 0.025f)
            l[i] = a + (ca - a) * mix; r[i] = b + (cb - b) * mix
        }
    }
}
