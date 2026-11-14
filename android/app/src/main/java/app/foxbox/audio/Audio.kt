package app.foxbox.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import app.foxbox.engines.Gusts
import app.foxbox.engines.Leaves
import app.foxbox.engines.Minds
import app.foxbox.engines.Reflections
import app.foxbox.engines.Teeth
import app.foxbox.seq.Transport
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max

/**
 * The audio engine: owns the engines and the transport, mixes everything
 * and drives the device. All engine/transport changes go through [post]
 * so they run on the audio thread between blocks.
 */
object Audio {
    const val BLOCK = 64

    val transport = Transport()
    val gusts = Gusts()
    val reflections = Reflections()
    val leaves = Leaves()
    val minds = Minds()
    val teeth = Teeth()
    val instruments: List<Engine> = listOf(gusts, reflections, leaves, minds)
    val all: List<Engine> = instruments + teeth

    @Volatile var masterVolume = 0.7f
    @Volatile var peak = 0f
    /** Set to capture the master output (see data.Tape). */
    @Volatile var tap: ((FloatArray, FloatArray, Int) -> Unit)? = null

    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val mixL = FloatArray(BLOCK); private val mixR = FloatArray(BLOCK)
    private val eL = FloatArray(BLOCK); private val eR = FloatArray(BLOCK)
    private val revL = FloatArray(BLOCK); private val revR = FloatArray(BLOCK)
    private val reverbFx = Reverb()
    @Volatile private var reverbOn = true
    /** For offline tests. */
    fun reverbOff() { reverbOn = false }
    private val limiter = Limiter()

    init {
        transport.engines = all
        leaves.reflectionsSample = { reflections.sample }
    }

    /** Run `fn` on the audio thread before the next block. */
    fun post(fn: () -> Unit) { queue.add(Runnable(fn)) }

    /** Render `frames` of interleaved stereo into `out`. Usable offline too. */
    fun render(out: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            block(n)
            for (i in 0 until n) { out[(done + i) * 2] = mixL[i]; out[(done + i) * 2 + 1] = mixR[i] }
            done += n
        }
    }

    private fun block(n: Int) {
        while (true) (queue.poll() ?: break).run()
        transport.process(n)
        mixL.fill(0f); mixR.fill(0f); revL.fill(0f); revR.fill(0f)
        for (e in instruments) {
            eL.fill(0f, 0, n); eR.fill(0f, 0, n)
            e.render(eL, eR, n)
            val v = e.volume.value; val s = e.reverb.value * v
            for (i in 0 until n) {
                mixL[i] += eL[i] * v; mixR[i] += eR[i] * v
                revL[i] += eL[i] * s; revR[i] += eR[i] * s
            }
        }
        if (reverbOn) reverbFx.process(revL, revR, mixL, mixR, n)
        teeth.process(mixL, mixR, n)
        transport.renderClick(mixL, mixR, n)
        limiter.process(mixL, mixR, n, masterVolume)
        var pk = peak * 0.97f
        for (i in 0 until n) pk = max(pk, abs(mixL[i]))
        peak = pk
        tap?.invoke(mixL, mixR, n)
    }

    // ---------------------------------------------------------------- device output

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(max(min, BLOCK * 8 * 4 * 2))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val frames = BLOCK * 4
            val buf = FloatArray(frames * 2)
            track.play()
            while (running) {
                render(buf, frames)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
            track.stop(); track.release()
        }, "FoxBoxAudio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500); thread = null
    }
}
