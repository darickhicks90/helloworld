package app.fiveiy.kids

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.foxbox.audio.Adsr
import app.foxbox.audio.DelayLine
import app.foxbox.audio.Hann
import app.foxbox.audio.Limiter
import app.foxbox.audio.Osc
import app.foxbox.audio.Reverb
import app.foxbox.audio.SR
import app.foxbox.audio.SRF
import app.foxbox.audio.Svf
import app.foxbox.audio.TAU
import app.foxbox.audio.mtof
import app.foxbox.audio.semis
import app.foxbox.audio.setSampleRate
import app.foxbox.engines.DrumKits
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Little Eye Yeti's sound: a synth, eight drum pads and a sampler. Everything a child can change is
 * kept inside ranges that always sound musical, and the output goes through the same transparent
 * limiter as 5 Eye Yeti, so nothing is ever too loud (it never turns anything up either).
 */
object Sound {
    /** The major scale: the only scale Little Eye Yeti plays. Eight keys, root to root. */
    val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    @Volatile var root = 0
    lateinit var synth: Synth; private set
    lateinit var drums: Drums; private set
    lateinit var sampler: Sampler; private set
    private lateinit var all: Array<Instrument>
    private val limiter = Limiter()
    private lateinit var reverb: Reverb
    private val queue = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var running = false
    private var thread: Thread? = null
    /** Room for the largest block the device may ask for. */
    private const val BLOCK = 1024
    /** The device's own burst (frames per buffer): we render exactly that much at a time. */
    private var burst = 192
    private val l = FloatArray(BLOCK); private val r = FloatArray(BLOCK)
    private val rl = FloatArray(BLOCK); private val rr = FloatArray(BLOCK)
    private val tmpL = FloatArray(BLOCK); private val tmpR = FloatArray(BLOCK)
    private var ready = false

    // a count-in tick (for the sampler)
    @Volatile private var tickAmp = 0f
    private var tickPh = 0f; private var tickHz = 1000f; private var tickEnv = 0f

    fun init(rate: Int, framesPerBurst: Int = 192) {
        if (ready) return
        burst = framesPerBurst.coerceIn(64, BLOCK)
        setSampleRate(rate)
        // a little under full scale: five notes at once peak around -3 dB, and the limiter makes sure
        limiter.ceilingOverride = 0.708f
        reverb = Reverb().apply { room = 0.8f; damp = 0.4f }
        synth = Synth(); drums = Drums(); sampler = Sampler()
        all = arrayOf(synth, drums, sampler)
        ready = true
    }

    /** Run something on the audio thread (note on/off and the like). */
    fun post(r: Runnable) { queue.add(r) }

    fun tick(accent: Boolean) = post { tickEnv = 1f; tickHz = if (accent) 1500f else 1000f; tickPh = 0f; tickAmp = 0.35f }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "LittleEyeYetiAudio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(500); thread = null
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(fmt)
            // capacity to grow into (the size actually used is set below, as small as works)
            .setBufferSizeInBytes(max(min * 4, burst * 2 * 4 * 8))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val out = FloatArray(BLOCK * 2)
        runCatching { track.play() }
        // start small (two bursts, the least latency) and grow a burst at a time only if this
        // phone can't keep up (each underrun is a crackle): it settles where it plays cleanly
        val n = burst
        val cap = runCatching { track.bufferCapacityInFrames }.getOrDefault(n * 8)
        var size = runCatching { track.setBufferSizeInFrames(n * 2) }.getOrDefault(n * 2)
        var underruns = runCatching { track.underrunCount }.getOrDefault(0)
        while (running) {
            render(n)
            for (i in 0 until n) { out[i * 2] = l[i]; out[i * 2 + 1] = r[i] }
            if (track.write(out, 0, n * 2, AudioTrack.WRITE_BLOCKING) < 0) break
            val u = track.underrunCount
            if (u > underruns) { underruns = u; if (size < cap) size = track.setBufferSizeInFrames(minOf(cap, size + n)) }
        }
        runCatching { track.stop() }; track.release()
    }

    /** The last rendered block (for the offline test). */
    val outL get() = l
    val outR get() = r

    /** Render one block into l/r. */
    fun render(n: Int) {
        while (true) (queue.poll() ?: break).run()
        l.fill(0f); r.fill(0f); rl.fill(0f); rr.fill(0f)
        for (inst in all) {
            tmpL.fill(0f); tmpR.fill(0f)
            inst.render(tmpL, tmpR, n)
            val g = inst.level
            for (i in 0 until n) { tmpL[i] *= g; tmpR[i] *= g }
            inst.fx.process(tmpL, tmpR, n)
            val send = inst.reverbSend
            for (i in 0 until n) { l[i] += tmpL[i]; r[i] += tmpR[i]; rl[i] += tmpL[i] * send; rr[i] += tmpR[i] * send }
        }
        reverb.process(rl, rr, l, r, n)
        if (tickEnv > 0.001f) for (i in 0 until n) {
            val v = sin(TAU * tickPh) * tickAmp * tickEnv
            tickPh += tickHz / SRF; if (tickPh >= 1f) tickPh -= 1f
            tickEnv *= 0.9993f
            l[i] += v; r[i] += v
        }
        limiter.process(l, r, n, 1f)
    }

    @Volatile var speaker = false
        set(v) { field = v; limiter.speaker = v }
}

/** What every instrument shares: a silly-effects pad, a filter slider, and a sound to render. */
abstract class Instrument {
    val fx = SillyFx()
    /** The filter slider, 0..1: never fully closed, with a little resonance. */
    @Volatile var filter = 0.75f
    /** The envelope button next to the filter slider. */
    @Volatile var filterEnv = false
    open val reverbSend = 0.25f
    /** Each instrument's level, measured so five notes at once peak about 3 dB under full scale. */
    open val level = 0.5f
    /** Counts every note and hit (the XY screens react to it). */
    @Volatile var hits = 0
    abstract fun render(l: FloatArray, r: FloatArray, n: Int)

    /** Filter slider → cutoff in Hz: 220 Hz (dark but still clearly there) up to about 9 kHz (bright). */
    protected fun cutoff(): Float = 220f * 2f.pow(filter.coerceIn(0f, 1f) * 5.35f)
    protected val RES = 0.32f
}

/** The envelope a child draws (time in seconds), always inside the musical ranges. */
class Shape(a: Float, d: Float, s: Float, r: Float) {
    @Volatile var attack = a; set(v) { field = v.coerceIn(A_MIN, A_MAX) }
    @Volatile var decay = d; set(v) { field = v.coerceIn(D_MIN, D_MAX) }
    @Volatile var sustain = s; set(v) { field = v.coerceIn(S_MIN, 1f) }
    @Volatile var release = r; set(v) { field = v.coerceIn(R_MIN, R_MAX) }
    fun applyTo(e: Adsr) { e.attack = attack; e.decay = decay; e.sustain = sustain; e.release = release }
    companion object {
        const val A_MIN = 0.003f; const val A_MAX = 0.6f
        const val D_MIN = 0.05f; const val D_MAX = 1.0f
        const val S_MIN = 0.15f
        const val R_MIN = 0.05f; const val R_MAX = 1.2f
    }
}

/** The filter envelope behind the envelope button: quick, clear "bwow" that still sits in the music. */
private fun filterShape(e: Adsr) { e.attack = 0.003f; e.decay = 0.45f; e.sustain = 0.06f; e.release = 0.3f }

/** With the envelope button on, the filter starts low and sweeps up about four octaves, with more resonance: a clear "bwow". */
private fun swept(base: Float, fe: Float) = min(base * 0.4f * (1f + 15f * fe), 14000f)
private const val RES_ENV = 0.55f

// ------------------------------------------------------------------ the synth

class Synth : Instrument() {
    /** 0 sine, 1 triangle, 2 square, 3 saw (the order of the buttons). */
    @Volatile var wave = 3
    val shape = Shape(0.01f, 0.35f, 0.7f, 0.35f)
    private class V { val osc = Osc(); val sub = Osc(); val svf = Svf(); val amp = Adsr(); val fenv = Adsr(); var key = -1; var freq = 440f; var age = 0L }
    private val voices = Array(8) { V() }
    private var clock = 0L

    fun noteOn(key: Int, note: Int) { hits++; on(key, note) }
    private fun on(key: Int, note: Int) = Sound.post {
        val v = voices.firstOrNull { it.key == key && it.amp.active } ?: voices.firstOrNull { !it.amp.active } ?: voices.minBy { it.age }
        v.key = key; v.freq = mtof(note.toFloat()); v.age = ++clock
        shape.applyTo(v.amp); filterShape(v.fenv)
        v.amp.gateOn(); v.fenv.gateOn()
    }
    fun noteOff(key: Int) = Sound.post { voices.forEach { if (it.key == key) { it.amp.gateOff(); it.fenv.gateOff(); it.key = -1 } } }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        val shapeIdx = when (wave) { 0 -> 3; 1 -> 2; 2 -> 1; else -> 0 }
        // the square and saw are bright: a touch quieter so all four feel equally loud
        val lvl = when (wave) { 0 -> 0.24f; 1 -> 0.32f; 2 -> 0.17f; else -> 0.28f }
        val base = cutoff(); val env = filterEnv
        for (v in voices) {
            if (!v.amp.active) continue
            val inc = v.freq / SRF
            for (i in 0 until n) {
                if (i and 15 == 0) {
                    val fe = v.fenv.next()
                    if (env) v.svf.set(swept(base, fe), RES_ENV) else v.svf.set(base, RES)
                } else v.fenv.next()
                val o = v.osc.next(shapeIdx, inc) + v.sub.next(3, inc * 0.5f) * 0.25f
                val s = v.svf.process(o) * v.amp.next() * lvl
                l[i] += s; r[i] += s
            }
        }
    }
}

// ------------------------------------------------------------------ the drum pads

class Drums : Instrument() {
    /** Which drums from the studio kit: kick, snare, clap, closed hat, open hat, low tom, high tom, cowbell. */
    private val pick = intArrayOf(0, 1, 2, 3, 4, 5, 7, 9)
    private val samples: Array<FloatArray> = Array(8) { DrumKits.studio(pick[it]) }
    /** Evens the pads out (the hats are quiet in the studio kit). */
    private val gain = floatArrayOf(1f, 1f, 1f, 2.4f, 2f, 1f, 1f, 1f)
    /** The pitch slider, 0..1 (0.5 = as recorded): an octave down to an octave up. */
    @Volatile var pitch = 0.5f
    override val reverbSend = 0.15f
    override val level = 0.4f
    private class V { var pos = -1.0; var vel = 1f; val svf = Svf(); val fenv = Adsr() }
    private val voices = Array(8) { V() }
    init { filter = 1f }

    fun hit(pad: Int, vel: Float = 1f) { hits++; strike(pad, vel) }
    private fun strike(pad: Int, vel: Float) = Sound.post {
        val v = voices[pad]; v.pos = 0.0; v.vel = vel; v.svf.reset()
        filterShape(v.fenv); v.fenv.decay = 0.18f; v.fenv.gateOn()
        if (pad == 3) voices[4].pos = -1.0   // the closed hat chokes the open one
    }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        val ratio = 2.0.pow((pitch.coerceIn(0f, 1f) - 0.5) * 2.0) * fx.pitch
        val base = cutoff(); val env = filterEnv
        for (p in voices.indices) {
            val v = voices[p]; if (v.pos < 0) continue
            val d = samples[p]
            for (i in 0 until n) {
                val k = v.pos.toInt()
                if (k >= d.size - 1) { v.pos = -1.0; break }
                val f = (v.pos - k).toFloat()
                val x = d[k] + (d[k + 1] - d[k]) * f
                if (i and 15 == 0) { val fe = v.fenv.next(); if (env) v.svf.set(min(base * (0.2f + 8f * fe), 14000f), RES_ENV) else v.svf.set(base, RES) } else v.fenv.next()
                val s = v.svf.process(x) * v.vel * 0.6f * gain[p]
                l[i] += s; r[i] += s
                v.pos += ratio
            }
        }
    }
}

// ------------------------------------------------------------------ the sampler

/**
 * Plays the child's recording (up to 10 s) on the keys. Always time-stretched: every key plays the
 * whole recording in the same time, just higher or lower (overlapping grains, like Reflections).
 */
class Sampler : Instrument() {
    override val level = 0.43f
    val shape = Shape(0.005f, 0.6f, 0.9f, 0.3f)
    @Volatile var data: FloatArray = demo(); private set
    /** Peaks of the current sample, for drawing it. */
    @Volatile var peaks: FloatArray = peaksOf(data); private set
    fun setSample(d: FloatArray) { val p = peaksOf(d); Sound.post { voices.forEach { it.on = false }; data = d; peaks = p } }

    private class Grain { var start = 0.0; var t = 0; var on = false }
    private inner class V {
        var on = false; var key = -1; var head = 0.0; var ratio = 1.0; var age = 0L
        val g = arrayOf(Grain(), Grain()); var next = 0
        val amp = Adsr(); val fenv = Adsr(); val svf = Svf()
    }
    private val voices = Array(6) { V() }
    private var clock = 0L
    private val grain get() = (SRF * 0.07f).toInt()

    fun noteOn(key: Int, semitones: Int) { hits++; on(key, semitones) }
    private fun on(key: Int, semitones: Int) = Sound.post {
        val v = voices.firstOrNull { !it.on } ?: voices.minBy { it.age }
        v.on = true; v.key = key; v.head = 0.0; v.ratio = semis(semitones.toFloat()).toDouble(); v.age = ++clock
        v.g[0].on = false; v.g[1].on = false; v.next = 0; v.svf.reset()
        shape.applyTo(v.amp); filterShape(v.fenv); v.amp.gateOn(); v.fenv.gateOn()
    }
    fun noteOff(key: Int) = Sound.post { voices.forEach { if (it.key == key && it.on) { it.amp.gateOff(); it.fenv.gateOff(); it.key = -1 } } }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        val d = data; val len = d.size; val gl = grain; val hop = gl / 2
        val base = cutoff(); val env = filterEnv; val fxp = fx.pitch.toDouble()
        for (v in voices) {
            if (!v.on) continue
            val ratio = v.ratio * fxp
            for (i in 0 until n) {
                // a new grain every half grain, starting where the (unstretched) play head is now
                if (v.next <= 0) {
                    val gr = if (!v.g[0].on) v.g[0] else v.g[1]
                    gr.on = true; gr.start = v.head; gr.t = 0; v.next = hop
                }
                v.next--
                var s = 0f
                for (gr in v.g) {
                    if (!gr.on) continue
                    val p = gr.start + gr.t * ratio
                    val k = p.toInt()
                    if (k in 0 until len - 1) { val f = (p - k).toFloat(); s += (d[k] + (d[k + 1] - d[k]) * f) * Hann.at(gr.t / gl.toFloat()) }
                    if (++gr.t >= gl) gr.on = false
                }
                if (i and 15 == 0) { val fe = v.fenv.next(); if (env) v.svf.set(swept(base, fe), RES_ENV) else v.svf.set(base, RES) } else v.fenv.next()
                val a = v.amp.next()
                val o = v.svf.process(s) * a * 0.8f
                l[i] += o; r[i] += o
                v.head += 1.0
                // the end of the recording: let the last grains finish, then the voice is free
                if (v.head >= len) { v.amp.gateOff() }
                if (!v.amp.active || (v.head >= len + gl)) { v.on = false; break }
            }
        }
    }

    companion object {
        fun peaksOf(d: FloatArray, n: Int = 96): FloatArray {
            val per = max(1, d.size / n)
            return FloatArray(n) { k -> var m = 0f; var i = k * per; val e = min(d.size, i + per); while (i < e) { m = max(m, abs(d[i])); i += 4 }; m }
        }
        /** Before anything is recorded: a little sung "boop-bee-doo". */
        fun demo(): FloatArray {
            val out = FloatArray((SRF * 1.2f).toInt())
            var ph = 0f; var ph2 = 0f
            for (i in out.indices) {
                val t = i / SRF
                val f = when { t < 0.35f -> 330f; t < 0.7f -> 440f; else -> 392f }
                ph += f / SRF; ph2 += f * 2.01f / SRF
                val seg = (t % 0.35f) / 0.35f
                val e = min(1f, seg * 30f) * (1f - seg).pow(0.6f)
                out[i] = (sin(TAU * ph) * 0.6f + sin(TAU * ph2) * 0.15f) * e * 0.8f
            }
            return out
        }
    }
}

// ------------------------------------------------------------------ the silly effects (the XY pad)

/**
 * The XY pad's effects. The dot always springs back to the middle, where everything is dry.
 * Left: underwater (a slow, gurgly wah). Right: wobble (a seasick vibrato).
 * Up: space echo (bouncy repeats). Down: robot (crunchy bit-crushing).
 * On the drums and the sampler, left/right are monster (lower) and chipmunk (higher) instead.
 */
class SillyFx {
    /** Where the dot is, -1..1 each way (0, 0 = home). */
    @Volatile var x = 0f
    @Volatile var y = 0f
    /** Left/right changes pitch (drums, sampler) instead of underwater/wobble. */
    @Volatile var pitchMode = false
    /** The pitch the instrument should play at (pitch mode), smoothed. */
    var pitch = 1f; private set

    // Clean by design: every effect runs all the time (so none ever starts from stale state), and
    // each one's amount glides sample by sample (about 12 ms) and crossfades dry to wet, so moving
    // the dot, or the dot springing home, never clicks or steps.
    private var sx = 0f; private var sy = 0f
    private val glide = 1f - exp(-1f / (0.012f * SRF))
    private val wah = Svf(); private val wahR = Svf()
    private val vib = DelayLine(4096); private val vibR = DelayLine(4096)
    private val echo = DelayLine(SR); private val echoR = DelayLine(SR)
    private var lfo = 0f; private var lfo2 = 0f
    private var holdL = 0f; private var holdR = 0f; private var holdN = 0

    fun process(l: FloatArray, r: FloatArray, n: Int) {
        val tx = x; val ty = y; val pm = pitchMode
        val time = SRF * 0.33f; val base = SRF * 0.005f
        for (i in 0 until n) {
            sx += (tx - sx) * glide; sy += (ty - sy) * glide
            val left = if (pm) 0f else max(0f, -sx); val right = if (pm) 0f else max(0f, sx)
            val up = max(0f, sy); val down = max(0f, -sy)
            var a = l[i]; var b = r[i]
            // underwater: a slow resonant wah, crossfaded in (its filters always run)
            lfo += (1.2f + left * 2.5f) / SRF; if (lfo >= 1f) lfo -= 1f
            if (i and 15 == 0) { val hz = 350f + 1400f * (0.5f + 0.5f * sin(TAU * lfo)); val q = 0.55f + 0.3f * left; wah.set(hz, q); wahR.set(hz * 1.05f, q) }
            val wa = wah.process(a) * 1.2f; val wb = wahR.process(b) * 1.2f
            a += (wa - a) * left; b += (wb - b) * left
            // wobble: a vibrato from a modulated delay, always written, its depth and wet mix gliding
            lfo2 += (4.5f + right * 4f) / SRF; if (lfo2 >= 1f) lfo2 -= 1f
            vib.write(a); vibR.write(b)
            if (right > 0.0005f) {
                val dly = base + right * SRF * 0.004f * (1f + sin(TAU * lfo2))
                val wet = min(1f, right * 4f)
                a += (vib.read(dly) - a) * wet; b += (vibR.read(dly) - b) * wet
            }
            // robot: sample-and-hold crush, always tracking, mixed in by amount
            if (holdN <= 0) {
                // crunchy, not harsh: at most 6x fewer samples and no fewer than 6 bits
                val q = 2f.pow(10f - down * 4f)
                holdL = (a * q).toInt() / q; holdR = (b * q).toInt() / q; holdN = 1 + (down * 5f).toInt()
            }
            holdN--
            val cm = min(1f, down * 1.4f)
            a += (holdL - a) * cm; b += (holdR - b) * cm
            // space echo: always running, so its tail rings out after the finger lets go
            val ea = echo.read(time); val eb = echoR.read(time * 1.07f)
            val fb = 0.25f + up * 0.4f; val mix = up * 0.7f
            echo.write(a * mix + eb * fb); echoR.write(b * mix + ea * fb)
            l[i] = a + ea; r[i] = b + eb
        }
        if (abs(sx) < 1e-5f) sx = 0f; if (abs(sy) < 1e-5f) sy = 0f
        pitch = if (pm) 2f.pow(sx) else 1f   // an octave either way
    }
}

/** RMS of a block of samples. */
fun rms(d: FloatArray, n: Int): Float { var s = 0.0; for (i in 0 until n) s += d[i] * d[i]; return sqrt(s / max(1, n)).toFloat() }
