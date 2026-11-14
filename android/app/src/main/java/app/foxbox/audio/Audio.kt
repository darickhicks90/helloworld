package app.foxbox.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import app.foxbox.engines.Bumble
import app.foxbox.engines.Gusts
import app.foxbox.engines.Leaves
import app.foxbox.engines.Minds
import app.foxbox.engines.Reflections
import app.foxbox.fx.FlowerRack
import app.foxbox.seq.Transport
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max

/**
 * The audio engine: owns the engines, the transport and the flower rack,
 * mixes everything and drives the device with the lowest stable latency.
 * Changes to engines/transport go through [post] so they run between blocks.
 */
object Audio {
    const val BLOCK = 64

    val transport = Transport()
    val gusts = Gusts()
    val reflections = Reflections()
    val leaves = Leaves()
    val minds = Minds()
    val bumble = Bumble()
    val engines: List<Engine> = listOf(gusts, reflections, leaves, minds, bumble)
    val flowers = FlowerRack()

    @Volatile var masterVolume = 0.7f
    @Volatile var peak = 0f
    /** Set to capture the master output (see data.AudioFiles.Tape). */
    @Volatile var tap: ((FloatArray, FloatArray, Int) -> Unit)? = null
    /** Output latency estimate in ms, for display. */
    @Volatile var latencyMs = 0f

    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val mixL = FloatArray(BLOCK); private val mixR = FloatArray(BLOCK)
    private val eL = FloatArray(BLOCK); private val eR = FloatArray(BLOCK)
    private val revL = FloatArray(BLOCK); private val revR = FloatArray(BLOCK)
    private val reverbFx = Reverb()
    private val limiter = Limiter()
    private val burst get() = DeviceAudio.burst

    init {
        transport.engines = engines
        leaves.reflectionsSample = { reflections.sample }
    }

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
        for (k in engines.indices) {
            val e = engines[k]
            eL.fill(0f, 0, n); eR.fill(0f, 0, n)
            e.render(eL, eR, n)
            val v = e.volume.value; val s = e.reverb.value * v
            for (i in 0 until n) {
                mixL[i] += eL[i] * v; mixR[i] += eR[i] * v
                revL[i] += eL[i] * s; revR[i] += eR[i] * s
            }
        }
        reverbFx.process(revL, revR, mixL, mixR, n)
        flowers.process(mixL, mixR, n)
        transport.renderClick(mixL, mixR, n)
        limiter.process(mixL, mixR, n, masterVolume)
        var pk = peak * 0.97f
        for (i in 0 until n) pk = max(pk, abs(mixL[i]))
        peak = pk
        tap?.invoke(mixL, mixR, n)
    }

    // ---------------------------------------------------------------- device output

    /** Which output is running, for display. */
    @Volatile var outputName = "—"
        private set
    private var native = false
    private var thread: Thread? = null
    private var tuner: Thread? = null
    @Volatile private var running = false
    private val lock = Any()

    /** Start sound: AAudio (lowest latency) if possible, otherwise AudioTrack. */
    fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        native = NativeAudio.available && runCatching { NativeAudio.nOpen(SR) }.getOrDefault(-1).let { rate ->
            if (rate == SR) true else { if (rate > 0) NativeAudio.nClose(); false }
        }
        if (native) {
            outputName = if (NativeAudio.nExclusive()) "AAudio (exclusive)" else "AAudio"
            tuner = Thread({ tuneNative() }, "FoxBoxAudioTuner").apply { isDaemon = true; start() }
        } else {
            outputName = "AudioTrack"
            thread = Thread({ runAudioTrack() }, "FoxBoxAudio").apply { start() }
        }
    }

    fun stop() = synchronized(lock) {
        if (!running) return@synchronized
        running = false
        if (native) runCatching { NativeAudio.nClose() }
        tuner?.join(1000); tuner = null
        thread?.join(2000); thread = null
    }

    /**
     * Keep the AAudio buffer as small as the device allows: grow by one burst
     * after a glitch, shrink again after 20 quiet seconds, and reopen the
     * stream if the output device changes (e.g. headphones plugged in).
     */
    private fun tuneNative() {
        var lastXRuns = 0
        var quiet = 0
        while (running) {
            Thread.sleep(250)
            if (!running) break
            runCatching {
                if (NativeAudio.nDisconnected()) {
                    NativeAudio.nClose()
                    if (NativeAudio.nOpen(SR) != SR) { NativeAudio.nClose(); return@runCatching }
                    lastXRuns = 0
                }
                val burst = NativeAudio.nBurst().coerceAtLeast(16)
                val xr = NativeAudio.nXRuns()
                val size = NativeAudio.nBufferSize()
                if (xr > lastXRuns) {
                    lastXRuns = xr; quiet = 0
                    if (size < burst * 8) NativeAudio.nSetBufferSize(size + burst)
                } else if (++quiet > 80 && size > burst * 2) {
                    quiet = 0; NativeAudio.nSetBufferSize(size - burst)
                }
                latencyMs = NativeAudio.nBufferSize() * 1000f / SR
            }
        }
    }

    private fun runAudioTrack() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = try {
            val fmt = AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(max(min, burst * 8 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            outputName = "No audio output"; running = false; return
        }
        var bursts = 2
        runCatching { track.bufferSizeInFrames = burst * bursts }
        val buf = FloatArray(burst * 2)
        var lastUnderruns = 0
        var quiet = 0
        try {
            track.play()
            while (running) {
                render(buf, burst)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                val u = track.underrunCount
                if (u > lastUnderruns) {
                    lastUnderruns = u; quiet = 0
                    if (bursts < 8) { bursts++; track.bufferSizeInFrames = burst * bursts }
                } else if (++quiet > SR * 20 / burst && bursts > 2) { quiet = 0; bursts--; track.bufferSizeInFrames = burst * bursts }
                latencyMs = track.bufferSizeInFrames * 1000f / SR
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }; track.release()
        }
    }
}

/** The device's native output rate and burst size (read before the engines are created). */
object DeviceAudio {
    @Volatile var burst = 192; private set

    fun configure(c: android.content.Context) {
        val am = c.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.let { if (it in 22050..96000) setSampleRate(it) }
        am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.let { if (it in 16..2048) burst = it }
    }
}
