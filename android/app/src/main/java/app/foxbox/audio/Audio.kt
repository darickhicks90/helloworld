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
    val pickup = app.foxbox.engines.Pickup()
    /** Every engine FoxBox has (one per block type). */
    val engines: List<Engine> = listOf(gusts, reflections, leaves, minds, bumble, pickup)
    fun engine(id: String): Engine = engines.first { it.id == id }
    /** Engines of the blocks in the current session (audio-thread snapshot). */
    @Volatile var active: Array<Engine> = arrayOf(gusts, minds)
        private set

    /** Choose which blocks are in the session. */
    fun setActive(ids: List<String>) {
        val list = ids.mapNotNull { id -> engines.firstOrNull { it.id == id } }
        post {
            engines.forEach { if (it !in list) it.allOff() }
            active = list.toTypedArray()
            transport.engines = list
        }
    }
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
    /** Set from the output route: protects the phone's speaker (see [Limiter]). */
    var onSpeaker: Boolean
        get() = limiter.speaker
        set(v) { limiter.speaker = v }
    private val burst get() = DeviceAudio.burst

    init {
        transport.engines = active.toList()
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

    private val chL = Array(3) { FloatArray(BLOCK) }; private val chR = Array(3) { FloatArray(BLOCK) }
    private val audioBus = Array(3) { FloatArray(BLOCK) }
    /** Level of each audio channel (the biplanes). */
    val channelLevel = floatArrayOf(0.9f, 0.9f, 0.9f)
    /** Live input for armed tracks while mixing down (set by the Studio). */
    @Volatile var liveIn: app.foxbox.data.AudioIn? = null
    /** Which tracks are armed, and which input each takes (0 in 1, 1 in 2, 2 both). */
    val liveArm = BooleanArray(3)
    val liveInput = IntArray(3)
    private val inL = FloatArray(BLOCK); private val inR = FloatArray(BLOCK)
    private val liveL = FloatArray(BLOCK); private val liveR = FloatArray(BLOCK)
    private fun softClip(x: Float) = if (x > 1f) 1f else if (x < -1f) -1f else x
    /** Meter for each audio track (peak, linear). */
    val channelPeak = FloatArray(3)
    private val audioBusR = Array(3) { FloatArray(BLOCK) }

    private fun block(n: Int) {
        while (true) (queue.poll() ?: break).run()
        transport.process(n)
        mixL.fill(0f); mixR.fill(0f); revL.fill(0f); revR.fill(0f)
        for (k in 0 until 3) { chL[k].fill(0f, 0, n); chR[k].fill(0f, 0, n) }
        val rack = flowers
        val chains = rack.chains
        val routes = rack.blockRoute
        val act = active
        for (k in act.indices) {
            val e = act[k]
            e.seq?.advance(n)
            eL.fill(0f, 0, n); eR.fill(0f, 0, n)
            e.render(eL, eR, n)
            val v = e.volume.value; val s = e.reverb.value * v
            // Blocks tied to a daisy chain go through that chain's flower.
            val c = routes[e.id] ?: -1
            val dl = if (c >= 0 && chains[c] != null) chL[c] else mixL
            val dr = if (c >= 0 && chains[c] != null) chR[c] else mixR
            val pn = e.livePan
            val pl = v * minOf(1f, 1f - pn); val pr = v * minOf(1f, 1f + pn)
            for (i in 0 until n) {
                dl[i] += eL[i] * pl; dr[i] += eR[i] * pr
                revL[i] += eL[i] * s; revR[i] += eR[i] * s
            }
        }
        // Audio channels (mono takes).
        for (ch in 0 until 3) { audioBus[ch].fill(0f, 0, n); audioBusR[ch].fill(0f, 0, n) }
        transport.renderAudio(audioBus, audioBusR, n, transport.blockStartTick)
        // The three audio tracks feed the master audio track (its level and meter);
        // a track on a daisy chain reaches it through that chain's flower.
        for (ch in 0 until 3) {
            val c = rack.channelRoute[ch]
            val g = channelLevel[ch]; val bl = audioBus[ch]; val br = audioBusR[ch]
            val dl = if (c >= 0 && chains[c] != null) chL[c] else mixL
            val dr = if (c >= 0 && chains[c] != null) chR[c] else mixR
            var pk = channelPeak[ch] * 0.95f
            for (i in 0 until n) { val l = bl[i] * g; val r = br[i] * g; dl[i] += l; dr[i] += r; pk = max(pk, max(abs(l), abs(r))) }
            channelPeak[ch] = pk
        }
        for (k in 0 until 3) if (chains[k] != null) {
            rack.processChain(k, chL[k], chR[k], n)
            val l = chL[k]; val r = chR[k]
            for (i in 0 until n) { mixL[i] += l[i]; mixR[i] += r[i] }
        }
        reverbFx.process(revL, revR, mixL, mixR, n)
        rack.processAudition(mixL, mixR, n)
        transport.renderClick(mixL, mixR, n)
        if (testTone) for (i in 0 until n) { val v = kotlin.math.sin(TAU * tonePh) * 0.12f; tonePh = (tonePh + 440f / SRF) % 1f; mixL[i] += v; mixR[i] += v }
        limiter.process(mixL, mixR, n, masterVolume)
        var pk = peak * 0.97f
        for (i in 0 until n) pk = max(pk, abs(mixL[i]))
        peak = pk
        // Live input on armed tracks: shown on their meters, and mixed into the tape
        // (not the speakers, so a mic can't feed back).
        val li = liveIn
        val tp = tap
        if (li != null) {
            li.readMonitor(inL, inR, n)
            var any = false
            for (i in 0 until n) { liveL[i] = 0f; liveR[i] = 0f }
            for (ch in 0 until 3) if (liveArm[ch]) {
                any = true
                val src = liveInput[ch]; val g = channelLevel[ch]
                var pk = channelPeak[ch] * 0.95f
                for (i in 0 until n) {
                    val v = (when (src) { 0 -> inL[i]; 1 -> inR[i]; else -> (inL[i] + inR[i]) * 0.5f }) * g
                    liveL[i] += v; liveR[i] += v; pk = max(pk, abs(v))
                }
                channelPeak[ch] = pk
            }
            if (any && tp != null) {
                val mv = masterVolume
                for (i in 0 until n) { liveL[i] = softClip(mixL[i] + liveL[i] * mv); liveR[i] = softClip(mixR[i] + liveR[i] * mv) }
                tp(liveL, liveR, n)
            } else tp?.invoke(mixL, mixR, n)
        } else tp?.invoke(mixL, mixR, n)
    }

    // ---------------------------------------------------------------- device output

    /** Dropouts counted by the device since the stream opened. */
    @Volatile var xruns = 0
    /** True while the audio test is stepping through buffer sizes. */
    @Volatile var testing = false
    /** A quiet test tone for the audio test. */
    @Volatile var testTone = false
    private var tonePh = 0f
    val isNative get() = native

    /** Restart the output (after changing device or buffer settings). */
    fun restart() { stop(); start(); DeviceAudio.am?.let { DeviceAudio.updateRoute(it) } }

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
        native = NativeAudio.available && runCatching { NativeAudio.nOpen(SR, AudioPrefs.outputDevice, AudioPrefs.bufferFrames) }.getOrDefault(-1).let { rate ->
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
                    if (NativeAudio.nOpen(SR, AudioPrefs.outputDevice, AudioPrefs.bufferFrames) != SR) { NativeAudio.nClose(); return@runCatching }
                    lastXRuns = 0
                }
                val burst = NativeAudio.nBurst().coerceAtLeast(16)
                val xr = NativeAudio.nXRuns()
                val size = NativeAudio.nBufferSize()
                xruns = xr
                // Only the "Automatic" buffer setting adapts; a chosen size is kept.
                if (AudioPrefs.bufferFrames == 0 && !testing) {
                    if (xr > lastXRuns) {
                        lastXRuns = xr; quiet = 0
                        if (size < burst * 8) NativeAudio.nSetBufferSize(size + burst)
                    } else if (++quiet > 80 && size > burst * 2) {
                        quiet = 0; NativeAudio.nSetBufferSize(size - burst)
                    }
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
    var am: android.media.AudioManager? = null; private set

    fun configure(c: android.content.Context) {
        val am = c.applicationContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val first = this.am == null
        this.am = am
        AudioPrefs.init(c)
        setSampleRate(AudioPrefs.sampleRate)
        am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.let { if (it in 16..2048) burst = it }
        // Follow the output route so the speaker guard is on only for the built-in speaker.
        updateRoute(am)
        if (first) am.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = updateRoute(am)
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = updateRoute(am)
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    /** True when sound goes to the phone's own speaker: a chosen output of that type, or no headphones/USB/Bluetooth attached. */
    fun updateRoute(am: android.media.AudioManager) {
        val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val chosen = outs.firstOrNull { it.id == AudioPrefs.outputDevice }
        val external = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
            android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL, android.media.AudioDeviceInfo.TYPE_HDMI,
        ) + (if (android.os.Build.VERSION.SDK_INT >= 31) setOf(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER) else emptySet())
        Audio.onSpeaker = if (chosen != null) chosen.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                          else outs.none { it.type in external }
    }
}
