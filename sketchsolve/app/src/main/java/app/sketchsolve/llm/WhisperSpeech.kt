package app.sketchsolve.llm

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * Push-to-talk with whisper.cpp, fully on the device: records 16 kHz mono
 * audio while the button is held, then transcribes it.
 */
class WhisperSpeech private constructor(private val handle: Long) : SpeechInput {
    companion object {
        private var loaded = false
        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeTranscribe(handle: Long, audio: FloatArray, prompt: String, threads: Int): String
        @JvmStatic private external fun nativeFree(handle: Long)

        /** Words Whisper should expect, so maths terms are heard correctly. */
        const val VOCABULARY = "Maths word problem. Solve for x. x squared plus 3 equals 12. 15 percent of 80. " +
            "The square root of 144. Divided by, times, minus, equals, per hour, miles per hour, kilometers, ohms, volts, amps, " +
            "radius, diameter, perimeter, area, volume, sine, cosine, tangent, degrees, fraction, three quarters."

        fun create(ctx: Context, model: File): WhisperSpeech? = try {
            if (!loaded) { System.loadLibrary("sketchsolve_whisper"); loaded = true }
            val h = nativeInit(model.absolutePath)
            if (h == 0L) null else WhisperSpeech(h)
        } catch (t: Throwable) { Log.e("SketchSolve", "speech unavailable", t); null }
    }

    private val rate = 16_000
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = java.io.ByteArrayOutputStream()
    @Volatile private var recording = false

    @SuppressLint("MissingPermission")   // the app asks for the microphone before calling start()
    override fun start() {
        if (recording) return
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        recorder = rec
        synchronized(pcm) { pcm.reset() }
        recording = true
        rec.startRecording()
        thread = Thread {
            val buf = ByteArray(4096)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
                if (pcm.size() > rate * 2 * 60) break   // one minute is plenty
            }
        }.also { it.start() }
    }

    override fun stop(onText: (String) -> Unit) {
        recording = false
        Thread {
            thread?.join(1000)
            recorder?.let { runCatching { it.stop() }; it.release() }
            recorder = null
            val bytes = synchronized(pcm) { pcm.toByteArray() }
            if (bytes.size < rate / 2) { onText(""); return@Thread }   // under a quarter of a second
            val samples = FloatArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val text = synchronized(this) { nativeTranscribe(handle, samples, VOCABULARY, threads) }
            onText(clean(text))
        }.start()
    }

    /** Whisper sometimes adds sound tags like [BLANK_AUDIO]; drop them. */
    private fun clean(t: String) = t.replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()

    override fun close() { synchronized(this) { nativeFree(handle) } }
}
