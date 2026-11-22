package app.fiveiy.kids

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.foxbox.audio.SR
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The sampler's microphone: a count-in (four ticks, the first one higher), then it records until
 * the child taps again, it has been quiet for 3 seconds in a row, or it reaches 10 seconds.
 * The quiet ends are trimmed off and the take is evened out so it plays back at a good level.
 */
object Recorder {
    enum class State { IDLE, COUNT_IN, RECORDING }
    @Volatile var state = State.IDLE; private set
    /** During the count-in: 4, 3, 2, 1. */
    @Volatile var beat = 0; private set
    /** Seconds recorded so far, and how loud the microphone is now (0..1), for the button. */
    @Volatile var seconds = 0f; private set
    @Volatile var level = 0f; private set
    @Volatile private var stopAsked = false

    const val MAX_SECONDS = 10f
    const val QUIET_SECONDS = 3f
    private const val QUIET = 0.012f      // about -38 dBFS: a quiet room, not a voice
    private const val BEAT_MS = 500L       // 120 BPM

    /** Tap while counting in or recording: stop (a count-in that's stopped records nothing). */
    fun stop() { stopAsked = true }

    /** Starts the count-in and the recording; `done` gets the new sample (null if nothing was heard). */
    @SuppressLint("MissingPermission")
    fun start(done: (FloatArray?) -> Unit) {
        if (state != State.IDLE) return
        stopAsked = false; seconds = 0f; level = 0f
        state = State.COUNT_IN
        Thread({
            var result: FloatArray? = null
            try {
                // the count-in
                for (b in 4 downTo 1) {
                    if (stopAsked) break
                    beat = b; Sound.tick(b == 4)
                    Thread.sleep(BEAT_MS)
                }
                if (!stopAsked) result = record()
            } catch (_: Throwable) { result = null }
            state = State.IDLE; beat = 0; level = 0f
            done(result)
        }, "LittleYetiMic").start()
    }

    @SuppressLint("MissingPermission")
    private fun record(): FloatArray? {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(min, 8192))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val cap = (SR * MAX_SECONDS).toInt()
        val buf = FloatArray(cap)
        val chunk = FloatArray(1024)
        var len = 0; var quiet = 0; var heard = false
        state = State.RECORDING
        rec.startRecording()
        try {
            while (!stopAsked && len < cap) {
                val n = rec.read(chunk, 0, min(chunk.size, cap - len), AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                System.arraycopy(chunk, 0, buf, len, n); len += n
                val lv = rms(chunk, n)
                level = min(1f, lv * 6f); seconds = len / SR.toFloat()
                if (lv < QUIET) quiet += n else { quiet = 0; heard = true }
                // quiet for 3 seconds in a row: that's the end
                if (quiet >= SR * QUIET_SECONDS) break
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        if (!heard) return null
        return tidy(buf, len)
    }

    /** Trim the quiet ends (keeping a breath before the sound), even out the level and fade the edges. */
    private fun tidy(buf: FloatArray, len: Int): FloatArray? {
        val win = SR / 100
        fun loud(at: Int): Boolean { var m = 0f; for (i in at until min(len, at + win)) m = max(m, abs(buf[i])); return m > QUIET * 2.5f }
        var s = 0; while (s < len && !loud(s)) s += win
        var e = len; while (e > s && !loud(e - win)) e -= win
        if (e - s < SR / 20) return null
        s = max(0, s - SR / 50); e = min(len, e + SR / 10)
        val out = buf.copyOfRange(s, e)
        var peak = 0f; for (v in out) peak = max(peak, abs(v))
        val g = if (peak > 1e-4f) min(0.9f / peak, 30f) else 1f
        val fade = SR / 200
        for (i in out.indices) {
            var v = out[i] * g
            if (i < fade) v *= i / fade.toFloat()
            if (i > out.size - fade) v *= (out.size - i) / fade.toFloat()
            out[i] = v
        }
        return out
    }
}
