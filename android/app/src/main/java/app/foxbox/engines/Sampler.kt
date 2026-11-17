package app.foxbox.engines

import app.foxbox.audio.EnvParams
import app.foxbox.audio.Engine
import app.foxbox.audio.EventRing
import app.foxbox.audio.Fmt
import app.foxbox.audio.Hann
import app.foxbox.audio.Rng
import app.foxbox.audio.SR
import app.foxbox.audio.SRF
import app.foxbox.audio.TAU
import app.foxbox.audio.Voice
import app.foxbox.audio.VoicePool
import app.foxbox.audio.VoiceTail
import app.foxbox.audio.mtof
import app.foxbox.audio.semis
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sampling engine shared by Reflections and Leaves: a sample slot,
 * region, root/tune, de-clicked reading, a grain engine and the shared
 * amp + filter envelopes. Subclasses decide how voices turn the sample into
 * sound.
 */
abstract class SampleEngine(id: String, title: String, attack: Float, release: Float) : Engine(id, title) {
    init { p.section("Sample") }
    protected val root = p.num("root", "Root Note", 24f, 96f, 60f, Fmt.NOTE, step = 1f)
    /** The note that plays the sample at its original pitch. */
    val rootNote get() = root.value.toInt()
    protected val tune = p.num("tune", "Fine Tune", -12f, 12f, 0f, Fmt.ST)
    protected val start = p.num("start", "Sample Start", 0f, 1f, 0f, Fmt.PCT)
    protected val end = p.num("end", "Sample End", 0f, 1f, 1f, Fmt.PCT)
    protected val reverse = p.toggle("reverse", "Reverse")

    @Volatile var sample: FloatArray = defaultSample(); private set
    @Volatile var sampleName = "Built-in"; private set
    fun setSample(data: FloatArray, name: String) { sample = if (data.size > 64) data else FloatArray(64); sampleName = name }
    fun resetSample() { sample = defaultSample(); sampleName = "Built-in" }
    protected abstract fun defaultSample(): FloatArray

    /** Short ramp at region edges so nothing clicks. */
    protected val declick get() = 0.005f * SRF

    /** Read with linear interpolation and edge ramps inside [s, e). Returns 0 outside. */
    protected fun read(d: FloatArray, pos: Double, s: Int, e: Int): Float {
        if (pos < s || pos >= e - 1) return 0f
        val i = pos.toInt(); val f = (pos - i).toFloat()
        val edge = min(pos - s, e - 1 - pos).toFloat()
        val ramp = if (edge < declick) edge / declick else 1f
        return (d[i] + (d[i + 1] - d[i]) * f) * ramp
    }

    /** Region in frames, set by [region]: always at least 256 frames long and inside the sample. */
    protected var regS = 0
    protected var regE = 1
    protected fun region(d: FloatArray) {
        val last = d.size - 1
        val span = minOf(256, last)
        var a = (min(v(start), v(end)) * last).toInt().coerceIn(0, last - span)
        val b = (max(v(start), v(end)) * last).toInt().coerceIn(a + span, last)
        if (b - a < span) a = maxOf(0, b - span)
        regS = a; regE = b
    }

    // ------------------------------------------------------------------ grains (shared)

    protected class Grain {
        var owner = -1; var data = FloatArray(1); var pos = 0.0; var inc = 1.0
        var len = 1; var age = 0; var gl = 0f; var gr = 0f; var s = 0; var e = 1
    }
    protected val grains = Array(96) { Grain() }

    protected fun spawnGrain(owner: Int, d: FloatArray, pos: Double, inc: Double, len: Int, gl: Float, gr: Float, s: Int, e: Int): Boolean {
        val g = grains.firstOrNull { it.owner < 0 } ?: return false
        g.owner = owner; g.data = d; g.pos = pos; g.inc = inc; g.len = max(32, len); g.age = 0; g.gl = gl; g.gr = gr; g.s = s; g.e = e
        return true
    }

    /** Add the grains belonging to `owner` into the buffers. */
    protected fun renderGrains(owner: Int, bl: FloatArray, br: FloatArray, n: Int) {
        for (g in grains) {
            if (g.owner != owner) continue
            for (i in 0 until n) {
                if (g.age >= g.len) { g.owner = -1; break }
                val w = Hann.at(g.age / g.len.toFloat())
                val x = read(g.data, g.pos, g.s, g.e) * w
                bl[i] += x * g.gl; br[i] += x * g.gr
                g.pos += g.inc; g.age++
            }
        }
    }
    protected fun killGrains(owner: Int) = grains.forEach { if (it.owner == owner) it.owner = -1 }

    // ------------------------------------------------------------------ voices + tail (shared)

    protected val env = EnvParams(p, cutoff = 16000f, res = 0.1f, envAmt = 0f, attack = attack, decay = 2f, sustain = 1f, release = release, fSustain = 1f)
    protected abstract inner class SVoice : Voice() {
        val tail = VoiceTail()
        var index = 0
        override val active get() = tail.active
    }
    protected abstract val pool: VoicePool<out SVoice>
    protected val bl = FloatArray(1024)
    protected val br = FloatArray(1024)

    override fun noteOff(key: Int) { pool.find(key)?.tail?.release(env, xy) }
    override fun expr(key: Int, kind: Int, v: Float) { pool.find(key)?.expr(kind, v) }
    override fun allOff() { pool.voices.forEach { it.tail.kill(); killGrains(it.index) } }

    protected abstract fun fill(v: SVoice, n: Int)
    protected open fun before(n: Int) {}
    protected open fun filterMod(v: SVoice): Float = 0f

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        xy.begin()
        before(n)
        var count = 0
        for (vi in pool.voices.indices) {
            val v = pool.voices[vi]
            if (!v.active) { killGrains(v.index); continue }
            count++
            bl.fill(0f, 0, n); br.fill(0f, 0, n)
            fill(v, n)
            val gain = v.vel * (0.7f + v.pressure * 0.6f)
            for (i in 0 until n) {
                if (i and 15 == 0) v.tail.update(env, xy, v.note, filterMod(v) + (v.slide - 0.5f) * 2f)
                v.tail.stereo(bl[i], br[i])
                l[i] += v.tail.outL * gain; r[i] += v.tail.outR * gain
            }
        }
        activeVoices = count
    }

    override fun saveExtra(o: JSONObject) { super.saveExtra(o); o.put("sampleName", sampleName) }
}

/**
 * Reflections — sampler (water theme). Classic resampling playback, or
 * Time Stretch: overlapping grains so pitch and speed are independent.
 */
class Reflections : SampleEngine("reflections", "Reflections", 0.003f, 0.4f) {
    private val loop = p.toggle("loop", "Loop")
    init { p.section("Time Stretch") }
    private val stretch = p.toggle("stretch", "Time Stretch")
    private val speed = p.num("speed", "Stretch Speed", 0.25f, 2f, 1f, Fmt.X, exp = true)
    private val grainSize = p.num("grain", "Stretch Grain", 0.03f, 0.2f, 0.08f, Fmt.SEC, exp = true)
    private val bendVib = expressionParams()
    override val seq = app.foxbox.seq.NoteSeq(this, p)
    init { finishParams("bend", "cutoff", 0.8f, 0.25f) }

    protected inner class V : SVoice() {
        var pos = 0.0; var data = FloatArray(1); var s = 0; var e = 1; var rev = false; var looping = false
        var stretching = false; var hop = 0f; var vibPh = 0f
    }
    override val pool = VoicePool(12) { V() }.also { p -> p.voices.forEachIndexed { i, v -> v.index = i } }
    private val rng = Rng(3)

    override fun defaultSample() = droplet()

    /** Tempo the sample was recorded at (a looper take); time stretch then follows the clock. 0 = not synced. */
    @Volatile var sampleBpm = 0f
    override fun saveExtra(o: JSONObject) { super.saveExtra(o); o.put("sampleBpm", sampleBpm.toDouble()) }
    override fun loadExtra(o: JSONObject?) { super.loadExtra(o); sampleBpm = o?.optDouble("sampleBpm", 0.0)?.toFloat() ?: 0f }

    override fun noteOn(key: Int, note: Int, vel: Float) {
        val d = sample
        val v = pool.start(key, note, vel) as V
        killGrains(v.index)
        region(d)
        v.data = d; v.s = regS; v.e = regE; v.rev = reverse.on; v.looping = loop.on; v.stretching = stretch.on
        v.pos = if (v.rev) v.e - 2.0 else v.s.toDouble()
        v.hop = 0f
        v.tail.start(env, xy)
        noted(note)
    }

    override fun fill(v: SVoice, n: Int) {
        v as V
        val ratio = semis(v.note - v(root) + v(tune) + v.bend + v(bendVib.first)).toDouble()
        val vib = v(bendVib.second) * 0.03f
        val dir = if (v.rev) -1.0 else 1.0
        if (!v.stretching) {
            for (i in 0 until n) {
                if (v.pos >= v.e - 1 || v.pos < v.s) {
                    if (!v.looping) { v.tail.release(env, xy); break }
                    v.pos = if (v.rev) v.e - 2 - (v.s - v.pos) else v.s + (v.pos - (v.e - 1))
                    if (v.pos < v.s || v.pos >= v.e - 1) v.pos = if (v.rev) v.e - 2.0 else v.s.toDouble()
                }
                val x = read(v.data, v.pos, v.s, v.e)
                bl[i] = x; br[i] = x
                v.vibPh += 5f / SRF; if (v.vibPh > 1f) v.vibPh -= 1f
                v.pos += ratio * dir * (1f + sin(TAU * v.vibPh) * vib)
            }
            return
        }
        // Time stretch: 4 overlapping Hann grains, each pitched by `ratio`, read head moves at `speed`.
        val len = (v(grainSize) * SRF).toInt()
        val hop = len / 4f
        v.hop -= n
        while (v.hop <= 0f) {
            val jitter = rng.bi() * len * 0.05
            spawnGrain(v.index, v.data, (v.pos + jitter).coerceIn(v.s.toDouble(), maxOf(v.s.toDouble(), v.e - 2.0)), ratio * dir, len, 0.5f, 0.5f, v.s, v.e)
            v.hop += hop
        }
        val sync = sampleBpm.let { if (it > 0f) app.foxbox.audio.Audio.transport.bpm / it else 1f }
        v.pos += v(speed) * sync * n * dir
        if (v.pos >= v.e - 1 || v.pos < v.s) {
            if (v.looping) v.pos = if (v.rev) v.e - 2.0 else v.s.toDouble() else v.tail.release(env, xy)
        }
        renderGrains(v.index, bl, br, n)
    }

    companion object {
        /** Built-in kalimba-like droplet, root C4. */
        fun droplet(): FloatArray {
            val f = mtof(60f); val n = (SR * 2.6f).toInt()
            var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
            return FloatArray(n) { i ->
                val t = i / SRF
                val drop = 1 + 0.04 * exp(-t * 35.0)
                p1 += 2 * Math.PI * f * drop / SR; p2 += 2 * Math.PI * f * 3.01 * drop / SR; p3 += 2 * Math.PI * f * 5.43 / SR
                (Math.sin(p1) * exp(-t * 2.1) + 0.32 * Math.sin(p2) * exp(-t * 6.0) + 0.14 * Math.sin(p3) * exp(-t * 11.0)).toFloat() * 0.8f
            }
        }
    }
}

/** Leaves — granular synth (plant theme) on the shared sampling engine. */
class Leaves : SampleEngine("leaves", "Leaves", 0.25f, 1.4f) {
    init { p.section("Grains") }
    val source = p.choice("source", "Source", listOf("Own Sample", "Reflections"), 0)
    val drone = p.toggle("drone", "Drone")
    private val position = p.num("position", "Position", 0f, 1f, 0.3f, Fmt.PCT)
    private val spray = p.num("spray", "Position Spray", 0f, 1f, 0.12f, Fmt.PCT)
    private val scan = p.num("scan", "Scan Speed", -1f, 1f, 0f)
    private val size = p.num("size", "Grain Size", 0.01f, 0.5f, 0.11f, Fmt.SEC, exp = true)
    private val density = p.num("density", "Density", 1f, 90f, 22f, Fmt.HZ, exp = true)
    private val pitchSpray = p.num("pitchSpray", "Pitch Spray", 0f, 12f, 0f, Fmt.ST)
    private val octave = p.num("octave", "Octave Chance", 0f, 1f, 0f, Fmt.PCT)
    private val width = p.num("width", "Stereo Width", 0f, 1f, 0.6f, Fmt.PCT)
    private val bendVib = expressionParams()
    override val seq = app.foxbox.seq.NoteSeq(this, p, defaultOctave = -1)
    init { finishParams("position", "density", 0.75f, 0.4f) }

    /** Grain events for the tree animation: a = position (0..1), b = pitch in semitones. */
    val grainEvents = EventRing(128)
    @Volatile var reflectionsSample: () -> FloatArray = { sample }
    @Volatile var droneNote = 48

    protected inner class V : SVoice() { var countdown = 0f }
    override val pool = VoicePool(8) { V() }.also { p -> p.voices.forEachIndexed { i, v -> v.index = i } }
    private val rng = Rng(9)
    private var scanPos = 0f
    private var data = FloatArray(1)

    override fun defaultSample() = moss()

    override fun noteOn(key: Int, note: Int, vel: Float) {
        val v = pool.start(key, note, vel) as V
        v.countdown = 0f
        v.tail.start(env, xy)
        noted(note)
    }

    override fun before(n: Int) {
        data = if (source.index == 1) reflectionsSample() else sample
        scanPos = (scanPos + v(scan) * n / SRF * 0.05f + 1f) % 1f
        val dv = pool.find(DRONE)
        if (drone.on && dv == null) noteOn(DRONE, droneNote, 0.6f) else if (!drone.on && dv != null) noteOff(DRONE)
    }

    override fun fill(v: SVoice, n: Int) {
        v as V
        val d = data
        region(d)
        val dens = v(density) * (if (v.pressure > 0f) 0.4f + v.pressure * 1.6f else 1f)
        v.countdown -= n
        var guard = 0
        while (v.countdown <= 0f && guard++ < 8) {
            var st = v.note - v(root) + v(tune) + v.bend + v(bendVib.first)
            if (v(pitchSpray) > 0f) st += Math.round(rng.bi() * v(pitchSpray))
            if (rng.next() < v(octave)) st += if (rng.next() < 0.5f) -12 else 12
            val len = (v(size) * SRF).toInt()
            var pos = v(position) + scanPos + rng.bi() * v(spray) * 0.5f
            pos -= kotlin.math.floor(pos)
            val at = (regS + pos * (regE - regS)).toDouble()
            val amp = 0.55f / sqrt(max(1f, dens * v(size)))
            val pan = rng.bi() * v(width)
            val inc = semis(st).toDouble() * (if (reverse.on) -1.0 else 1.0) * (1f + rng.bi() * v(bendVib.second) * 0.02f)
            if (spawnGrain(v.index, d, at, inc, len, amp * (1 - pan) * 0.7f, amp * (1 + pan) * 0.7f, regS, regE)) grainEvents.push(pos, st)
            v.countdown += SRF / dens * (0.6f + rng.next() * 0.8f)
        }
        renderGrains(v.index, bl, br, n)
    }

    private fun moss(): FloatArray {
        val notes = floatArrayOf(48f, 55f, 60f, 63f, 67f, 70f, 74f).map { mtof(it) }
        val ph = DoubleArray(notes.size) { it * 0.9 }
        var nz = 0f
        val rnd = Rng(5)
        return FloatArray(SR * 6) { i ->
            val t = i / SRF
            var s = 0.0
            for (k in notes.indices) {
                ph[k] += 2 * Math.PI * notes[k] * (1 + 0.002 * Math.sin(t * (0.7 + k * 0.3))) / SR
                val e = 0.5 + 0.5 * Math.sin(t * (0.5 + k * 0.23) + k)
                s += (Math.sin(ph[k]) + 0.3 * Math.sin(2 * ph[k]) + 0.12 * Math.sin(3 * ph[k])) * e
            }
            nz = nz * 0.97f + rnd.bi() * 0.03f
            (s * 0.11 + nz * 0.3 * (0.5 + 0.5 * Math.sin(t * 2.1))).toFloat()
        }
    }

    companion object { const val DRONE = 0x7FFF0001 }
}
