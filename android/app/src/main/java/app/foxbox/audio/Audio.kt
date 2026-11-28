package app.foxbox.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
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

    // The engines, transport and flower rack live in the shared core (also used by the iOS app).
    val transport = Core.transport
    val gusts = Core.gusts
    val reflections = Core.reflections
    val leaves = Core.leaves
    val minds = Core.minds
    val bumble = Core.bumble
    val pickup = Core.pickup
    /** Every engine FoxBox has (one per block type). */
    val engines: List<Engine> = Core.engines
    fun engine(id: String): Engine = Core.engine(id)
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
    val flowers = Core.flowers

    // ---------------------------------------------------------------- the Fire Escape's lane inserts
    /** Every lane: each block type (in [engines] order), then the three audio tracks. */
    val laneIds: List<String> = engines.map { it.id } + listOf("track0", "track1", "track2")
    private val laneNames = engines.map { it.title } + listOf("Red track", "Yellow track", "Blue track")
    /** Filter, compressors (with sidechain) and Wraith on each lane. */
    val laneFx: Array<app.foxbox.fx.LaneFx> = Array(laneIds.size) { app.foxbox.fx.LaneFx(laneNames) }
    fun laneFx(id: String) = laneFx[laneIds.indexOf(id).coerceAtLeast(0)]
    /** The amp and cab on each audio track. */
    val amps = Array(3) { AmpRig(it) }
    /** Pitch classes in the project's scale (bit n = pitch class n), for Wraith. */
    @Volatile var scaleMask = 0xAB5
    /** Lanes muted on the Fire Escape (and its mixer): silenced at their fader. */
    val laneMuted = BooleanArray(laneIds.size)

    // ---------------------------------------------------------------- the tuner's ear
    /** What Noodler listens to: -1 nothing, 0..2 the input (1, 2, both). Written to [noodleRing] (mono). */
    @Volatile var noodleSource = -1
    val noodleRing = FloatArray(16384)
    @Volatile var noodleWrite = 0
    /** What the tuner listens to: -1 nothing, 0..2 the input (1, 2, both), 100 + n the block at engines[n]. */
    @Volatile var tunerSource = -1
    /** The last few thousand samples of it (mono), written by the audio thread. */
    val tunerRing = FloatArray(8192)
    @Volatile var tunerWrite = 0
    private fun tunerTap(l: FloatArray, r: FloatArray?, n: Int) {
        var w = tunerWrite
        for (i in 0 until n) { tunerRing[w] = if (r != null) (l[i] + r[i]) * 0.5f else l[i]; w = (w + 1) and 8191 }
        tunerWrite = w
    }
    private val engineIndex = HashMap<Engine, Int>().apply { engines.forEachIndexed { i, e -> put(e, i) } }
    private val laneL = Array(engines.size) { FloatArray(BLOCK) }; private val laneR = Array(engines.size) { FloatArray(BLOCK) }
    /** Each lane's sound this block (mono, before its inserts): what a sidechain listens to. */
    private val keyBuf = Array(laneIds.size) { FloatArray(BLOCK) }
    private val keyLive = BooleanArray(laneIds.size)

    @Volatile var masterVolume = 0.7f
    @Volatile var peak = 0f
    /** Set to capture the master output (see data.AudioFiles.Tape). */
    @Volatile var tap: ((FloatArray, FloatArray, Int) -> Unit)? = null
    /** Output latency estimate in ms, for display. */
    @Volatile var latencyMs = 0f

    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val mixL = FloatArray(BLOCK); private val mixR = FloatArray(BLOCK)
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
    /** Tracks whose input is heard live through their strip and fader (the Mon switch in Clibbins). */
    val liveMonitor = BooleanArray(3)
    /** Each audio track's dynamics and effects, and the master's. */
    val stripFx = Array(3) { StripFx(false) }
    val masterFx = StripFx(true)
    /** Set while leading a jam: the finished mix is copied here for the broadcast. */
    @Volatile var jamRing: app.foxbox.jam.JamRing? = null
    val liveInput = IntArray(3)
    private val inL = FloatArray(BLOCK); private val inR = FloatArray(BLOCK)
    private val liveL = FloatArray(BLOCK); private val liveR = FloatArray(BLOCK)
    /** The tape's own guard when live inputs are added after the master limiter. */
    private val tapeLimiter = Limiter()
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
        val E = engines.size
        keyLive.fill(false)
        // every block renders into its own buffer first, so any lane can key another's compressor
        for (k in act.indices) {
            val e = act[k]
            val idx = engineIndex[e] ?: continue
            e.seq?.advance(n)
            val bl = laneL[idx]; val br = laneR[idx]
            bl.fill(0f, 0, n); br.fill(0f, 0, n)
            e.render(bl, br, n)
            val v = e.volume.value; val key = keyBuf[idx]
            for (i in 0 until n) key[i] = (bl[i] + br[i]) * 0.5f * v
            keyLive[idx] = true
        }
        // audio tracks: their takes, plus live input on monitored tracks, then the amp
        for (ch in 0 until 3) { audioBus[ch].fill(0f, 0, n); audioBusR[ch].fill(0f, 0, n) }
        transport.renderAudio(audioBus, audioBusR, n, transport.blockStartTick)
        val li = liveIn
        if (li != null) li.readMonitor(inL, inR, n) else { inL.fill(0f, 0, n); inR.fill(0f, 0, n) }
        val ns = noodleSource
        if (ns in 0..2 && li != null) {
            var w = noodleWrite
            for (i in 0 until n) { noodleRing[w] = when (ns) { 0 -> inL[i]; 1 -> inR[i]; else -> (inL[i] + inR[i]) * 0.5f }; w = (w + 1) and 16383 }
            noodleWrite = w
        }
        val ts = tunerSource
        if (ts in 0..2 && li != null) { if (ts == 0) tunerTap(inL, null, n) else if (ts == 1) tunerTap(inR, null, n) else tunerTap(inL, inR, n) }
        else if (ts >= 100) { val k = ts - 100; if (k < E && keyLive[k]) tunerTap(laneL[k], laneR[k], n) }
        for (ch in 0 until 3) {
            val bl = audioBus[ch]; val br = audioBusR[ch]
            if (li != null && liveMonitor[ch]) {
                if (laneFx[E + ch].stereo.on) for (i in 0 until n) { bl[i] += inL[i]; br[i] += inR[i] }   // a stereo track: input 1 left, input 2 right
                else {
                    val src = liveInput[ch]
                    for (i in 0 until n) { val v = when (src) { 0 -> inL[i]; 1 -> inR[i]; else -> (inL[i] + inR[i]) * 0.5f }; bl[i] += v; br[i] += v }
                }
            }
            amps[ch].process(bl, br, n)
            val key = keyBuf[E + ch]
            for (i in 0 until n) key[i] = (bl[i] + br[i]) * 0.5f
            keyLive[E + ch] = true
        }
        val bpm = transport.bpm; val tk = transport.blockStartTick; val mask = scaleMask
        for (k in act.indices) {
            val e = act[k]
            val idx = engineIndex[e] ?: continue
            val eL = laneL[idx]; val eR = laneR[idx]
            val fx = laneFx[idx]
            if (fx.active) fx.process(eL, eR, n, keyFor(fx.compKey), keyFor(fx.bandKey), bpm, tk, mask) else fx.wraith.idle()
            val v = e.volume.value; val s = e.reverb.value * v
            // Blocks tied to a daisy chain go through that chain's flower.
            val c = routes[e.id] ?: -1
            val dl = if (c >= 0 && chains[c] != null) chL[c] else mixL
            val dr = if (c >= 0 && chains[c] != null) chR[c] else mixR
            val pn = (e.livePan + fx.pan.value).coerceIn(-1f, 1f)
            val mv = if (laneMuted[idx]) 0f else v
            val pl = mv * minOf(1f, 1f - pn); val pr = mv * minOf(1f, 1f + pn)
            var pk = e.meter * 0.985f
            for (i in 0 until n) {
                dl[i] += eL[i] * pl; dr[i] += eR[i] * pr
                revL[i] += eL[i] * s; revR[i] += eR[i] * s
                pk = max(pk, max(abs(eL[i] * pl), abs(eR[i] * pr)))
            }
            e.meter = pk
        }
        // Audio tracks: lane inserts, then each track's dynamics and effects strip, then its fader.
        for (ch in 0 until 3) {
            val bl = audioBus[ch]; val br = audioBusR[ch]
            val fx = laneFx[E + ch]
            if (fx.active) fx.process(bl, br, n, keyFor(fx.compKey), keyFor(fx.bandKey), bpm, tk, mask) else fx.wraith.idle()
            stripFx[ch].process(bl, br, n, revL, revR)
            val c = rack.channelRoute[ch]
            val g = if (laneMuted[E + ch]) 0f else channelLevel[ch]
            val tp = fx.pan.value
            if (tp != 0f) { val gl = minOf(1f, 1f - tp); val gr = minOf(1f, 1f + tp); for (i in 0 until n) { bl[i] *= gl; br[i] *= gr } }
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
        masterFx.process(mixL, mixR, n)
        transport.renderClick(mixL, mixR, n)
        if (testTone) for (i in 0 until n) { val v = kotlin.math.sin(TAU * tonePh) * 0.12f; tonePh = (tonePh + 440f / SRF) % 1f; mixL[i] += v; mixR[i] += v }
        limiter.process(mixL, mixR, n, masterVolume)
        // a jam over the internet: the leader's mix goes out from here
        jamRing?.write(mixL, mixR, n)
        var pk = peak * 0.97f
        for (i in 0 until n) pk = max(pk, abs(mixL[i]))
        peak = pk
        // Armed tracks that aren't monitored still go onto the tape (not the speakers, so a mic can't
        // feed back); monitored ones are already in the mix.
        val tp = tap
        if (li != null && tp != null) {
            var any = false
            for (i in 0 until n) { liveL[i] = 0f; liveR[i] = 0f }
            for (ch in 0 until 3) if (liveArm[ch] && !liveMonitor[ch]) {
                any = true
                val src = liveInput[ch]; val g = channelLevel[ch]
                var pk = channelPeak[ch] * 0.95f
                for (i in 0 until n) {
                    val v = (when (src) { 0 -> inL[i]; 1 -> inR[i]; else -> (inL[i] + inR[i]) * 0.5f }) * g
                    liveL[i] += v; liveR[i] += v; pk = max(pk, abs(v))
                }
                channelPeak[ch] = pk
            }
            if (any) {
                val mv = masterVolume
                for (i in 0 until n) { liveL[i] = mixL[i] + liveL[i] * mv; liveR[i] = mixR[i] + liveR[i] * mv }
                tapeLimiter.process(liveL, liveR, n, 1f)
                tp(liveL, liveR, n)
            } else tp(mixL, mixR, n)
        } else tp?.invoke(mixL, mixR, n)
    }

    private val silent = FloatArray(BLOCK)
    /** A sidechain source's sound this block (silence if that lane isn't playing); null means "itself". */
    private fun keyFor(k: Int): FloatArray? = if (k < 0) null else if (k < keyLive.size && keyLive[k]) keyBuf[k] else silent

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
