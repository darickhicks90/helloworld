package app.foxbox.engines

import app.foxbox.audio.Adsr
import app.foxbox.audio.Engine
import app.foxbox.audio.EventRing
import app.foxbox.audio.Fmt
import app.foxbox.audio.Hann
import app.foxbox.audio.Rng
import app.foxbox.audio.SR
import app.foxbox.audio.SRF
import app.foxbox.audio.Svf
import app.foxbox.audio.Voice
import app.foxbox.audio.VoicePool
import app.foxbox.audio.mtof
import app.foxbox.audio.semis
import kotlin.math.max
import kotlin.math.sqrt

/** Leaves — granular synth (plant theme). Hold notes to grow clouds of grains. */
class Leaves : Engine("leaves", "Leaves") {
    override val xName = "Grain Position"
    override val yName = "Density"
    override val springX: Float? = null
    override val springY: Float? = null

    init { p.section("Source") }
    val source = p.choice("source", "Source", listOf("Moss", "Reflections", "Loaded File"), 0)
    val drone = p.toggle("drone", "Drone")

    init { p.section("Grains") }
    private val position = p.num("position", "Position", 0f, 1f, 0.3f, Fmt.PCT)
    private val spray = p.num("spray", "Position Spray", 0f, 1f, 0.12f, Fmt.PCT)
    private val scan = p.num("scan", "Scan Speed", -1f, 1f, 0f)
    private val size = p.num("size", "Grain Size", 0.01f, 0.5f, 0.11f, Fmt.SEC, exp = true)
    private val density = p.num("density", "Density", 1f, 90f, 22f, Fmt.HZ, exp = true)

    init { p.section("Pitch & Space") }
    private val pitchSpray = p.num("pitchSpray", "Pitch Spray", 0f, 12f, 0f, Fmt.ST)
    private val octave = p.num("octave", "Octave Chance", 0f, 1f, 0f, Fmt.PCT)
    private val width = p.num("width", "Stereo Width", 0f, 1f, 0.6f, Fmt.PCT)
    private val cutoff = p.num("cutoff", "Cutoff", 200f, 18000f, 9000f, Fmt.HZ, exp = true)

    init { p.section("Envelope") }
    private val atk = p.num("attack", "Attack", 0.005f, 5f, 0.25f, Fmt.SEC, exp = true)
    private val rel = p.num("release", "Release", 0.05f, 10f, 1.4f, Fmt.SEC, exp = true)

    init { mixParams(0.75f, 0.4f); x = 0.5f; y = 0.5f }

    /** Grain events for the branch animation: a = position (0..1), b = pitch in semitones. */
    val grains = EventRing(128)
    @Volatile var reflectionsSample: () -> FloatArray = { moss }
    @Volatile var loaded: FloatArray? = null
    @Volatile var loadedName = ""
    @Volatile var droneNote = 48

    private val moss = makeMoss()
    private fun buffer(): FloatArray = when (source.index) { 1 -> reflectionsSample(); 2 -> loaded ?: moss; else -> moss }

    private inner class V : Voice() {
        val env = Adsr(); var countdown = 0f
        override val active get() = env.active
    }
    private inner class Grain {
        var on = false; var data = FloatArray(1); var pos = 0.0; var inc = 1.0
        var len = 1; var age = 0; var gl = 0f; var gr = 0f
    }

    private val pool = VoicePool(8) { V() }
    private val gpool = Array(64) { Grain() }
    private val rng = Rng(9)
    private var scanPos = 0f
    private val fl = Svf(); private val fr = Svf()
    private val bufL = FloatArray(1024); private val bufR = FloatArray(1024)

    override fun noteOn(key: Int, note: Int, vel: Float) {
        val v = pool.start(key, note, vel)
        v.env.attack = atk.value; v.env.decay = 1f; v.env.sustain = 1f; v.env.release = rel.value
        v.env.gateOn(); v.countdown = 0f
        noted(note)
    }
    override fun noteOff(key: Int) { pool.find(key)?.env?.gateOff() }
    override fun expr(key: Int, kind: Int, v: Float) {
        val voice = pool.find(key) ?: return
        when (kind) { BEND -> voice.bend = v; PRESSURE -> voice.pressure = v; SLIDE -> voice.slide = v }
    }
    override fun allOff() { pool.voices.forEach { it.env.kill() }; gpool.forEach { it.on = false } }

    private fun spawn(v: V, data: FloatArray) {
        val g = gpool.firstOrNull { !it.on } ?: return
        var st = v.note - 60 + v.bend
        if (pitchSpray.value > 0f) st += Math.round(rng.bi() * pitchSpray.value)
        if (rng.next() < octave.value) st += if (rng.next() < 0.5f) -12 else 12
        val inc = semis(st).toDouble()
        val len = (size.value * SRF).toInt().coerceAtLeast(64)
        var pos = position.value + scanPos + (x - 0.5f) + (v.slide - 0.5f) * 0.5f + rng.bi() * spray.value * 0.5f
        pos -= kotlin.math.floor(pos)
        val maxStart = max(0.0, data.size - len * inc - 2)
        g.on = true; g.data = data; g.inc = inc; g.len = len; g.age = 0
        g.pos = minOf(pos * data.size.toDouble(), maxStart)
        val overlap = max(1f, density.value * size.value)
        val amp = v.env.level * v.vel * 0.55f / sqrt(overlap)
        val pan = rng.bi() * width.value
        g.gl = amp * (1f - pan) * 0.7f; g.gr = amp * (1f + pan) * 0.7f
        grains.push(pos, st)
    }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        val data = buffer()
        scanPos = (scanPos + scan.value * n / SRF * 0.05f + 1f) % 1f
        // Drone: a self-playing note.
        val dv = pool.find(DRONE)
        if (drone.on && dv == null) noteOn(DRONE, droneNote, 0.6f) else if (!drone.on && dv != null) noteOff(DRONE)

        val densMul = 0.25f + y * 2.25f
        var count = 0
        for (v in pool.voices) {
            if (!v.active) continue
            count++
            for (i in 0 until n) v.env.next()
            v.countdown -= n
            var guard = 0
            while (v.countdown <= 0f && guard++ < 8) {
                spawn(v, data)
                val d = density.value * densMul * (if (v.pressure > 0f) 0.4f + v.pressure * 1.6f else 1f)
                v.countdown += SRF / d * (0.6f + rng.next() * 0.8f)
            }
        }
        bufL.fill(0f, 0, n); bufR.fill(0f, 0, n)
        for (g in gpool) {
            if (!g.on) continue
            val d = g.data
            for (i in 0 until n) {
                val ip = g.pos.toInt()
                if (ip >= d.size - 1 || g.age >= g.len) { g.on = false; break }
                val f = (g.pos - ip).toFloat()
                val s = (d[ip] + (d[ip + 1] - d[ip]) * f) * Hann.at(g.age / g.len.toFloat())
                bufL[i] += s * g.gl; bufR[i] += s * g.gr
                g.pos += g.inc; g.age++
            }
        }
        fl.set(cutoff.value, 0.1f); fr.set(cutoff.value, 0.1f)
        for (i in 0 until n) { l[i] += fl.process(bufL[i]); r[i] += fr.process(bufR[i]) }
        activeVoices = count
    }

    private fun makeMoss(): FloatArray {
        val notes = floatArrayOf(48f, 55f, 60f, 63f, 67f, 70f, 74f).map { mtof(it) }
        val ph = DoubleArray(notes.size) { it * 0.9 }
        var nz = 0f
        val rnd = Rng(5)
        val out = FloatArray(SR * 6) { i ->
            val t = i / SRF
            var s = 0.0
            for (k in notes.indices) {
                ph[k] += 2 * Math.PI * notes[k] * (1 + 0.002 * Math.sin(t * (0.7 + k * 0.3))) / SR
                val env = 0.5 + 0.5 * Math.sin(t * (0.5 + k * 0.23) + k)
                s += (Math.sin(ph[k]) + 0.3 * Math.sin(2 * ph[k]) + 0.12 * Math.sin(3 * ph[k])) * env
            }
            nz = nz * 0.97f + rnd.bi() * 0.03f
            (s * 0.11 + nz * 0.3 * (0.5 + 0.5 * Math.sin(t * 2.1))).toFloat()
        }
        return out
    }

    companion object { const val DRONE = 0x7FFF0001 }
}
