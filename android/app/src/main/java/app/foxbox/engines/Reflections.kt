package app.foxbox.engines

import app.foxbox.audio.Adsr
import app.foxbox.audio.Engine
import app.foxbox.audio.Fmt
import app.foxbox.audio.SR
import app.foxbox.audio.SRF
import app.foxbox.audio.Svf
import app.foxbox.audio.TAU
import app.foxbox.audio.Voice
import app.foxbox.audio.VoicePool
import app.foxbox.audio.mtof
import app.foxbox.audio.semis
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Reflections — a sampler (water theme). Plays a mono sample chromatically
 * with start/end, loop and reverse. Every region start, end and loop seam
 * gets a short ramp so playback never clicks.
 */
class Reflections : Engine("reflections", "Reflections") {
    override val xName = "Pitch Bend"
    override val yName = "Filter Sweep"
    override val springY: Float? = 1f

    init { p.section("Sample") }
    private val root = p.num("root", "Root Note", 24f, 96f, 60f, Fmt.NOTE, step = 1f)
    private val tune = p.num("tune", "Fine Tune", -12f, 12f, 0f, Fmt.ST)
    private val start = p.num("start", "Sample Start", 0f, 1f, 0f, Fmt.PCT)
    private val end = p.num("end", "Sample End", 0f, 1f, 1f, Fmt.PCT)
    private val loop = p.toggle("loop", "Loop")
    private val reverse = p.toggle("reverse", "Reverse")

    init { p.section("Filter") }
    private val cutoff = p.num("cutoff", "Cutoff", 120f, 20000f, 16000f, Fmt.HZ, exp = true)
    private val res = p.num("res", "Resonance", 0f, 1f, 0.1f, Fmt.PCT)

    init { p.section("Envelope") }
    private val atk = p.num("attack", "Attack", 0.002f, 3f, 0.004f, Fmt.SEC, exp = true)
    private val dec = p.num("decay", "Decay", 0.01f, 6f, 6f, Fmt.SEC, exp = true)
    private val sus = p.num("sustain", "Sustain", 0f, 1f, 1f, Fmt.PCT)
    private val rel = p.num("release", "Release", 0.01f, 8f, 0.35f, Fmt.SEC, exp = true)

    init { p.section("Modulation") }
    private val vibRate = p.num("vibRate", "Vibrato Rate", 0.1f, 10f, 4.5f, Fmt.HZ, exp = true)
    private val vibDepth = p.num("vibDepth", "Vibrato Depth", 0f, 50f, 5f, Fmt.CT)

    init { mixParams(0.8f, 0.25f); y = 1f }

    /** The sample (mono, 48 kHz). Swap it with [setSample]. */
    @Volatile var sample: FloatArray = droplet(); private set
    @Volatile var sampleName = "Droplet"; private set

    fun setSample(data: FloatArray, name: String) { sample = data; sampleName = name }

    private inner class V : Voice() {
        val amp = Adsr(); var pos = 0.0; var data = FloatArray(1)
        var s = 0; var e = 1; var rev = false; var looping = false; var vibPh = 0f
        override val active get() = amp.active
    }

    private val pool = VoicePool(12) { V() }
    private val filtL = Svf()
    private val declick = 0.005f * SR

    override fun noteOn(key: Int, note: Int, vel: Float) {
        val d = sample
        val v = pool.start(key, note, vel)
        v.data = d
        val a = (min(start.value, end.value) * (d.size - 1)).toInt()
        val b = maxOf(a + 64, (maxOf(start.value, end.value) * (d.size - 1)).toInt()).coerceAtMost(d.size - 1)
        v.s = a; v.e = b; v.rev = reverse.on; v.looping = loop.on
        v.pos = if (v.rev) b - 1.0 else a.toDouble()
        v.amp.attack = atk.value; v.amp.decay = dec.value; v.amp.sustain = sus.value; v.amp.release = rel.value
        v.amp.gateOn()
        noted(note)
    }

    override fun noteOff(key: Int) { pool.find(key)?.amp?.gateOff() }
    override fun expr(key: Int, kind: Int, v: Float) {
        val voice = pool.find(key) ?: return
        when (kind) { BEND -> voice.bend = v; PRESSURE -> voice.pressure = v; SLIDE -> voice.slide = v }
    }
    override fun allOff() = pool.voices.forEach { it.amp.kill() }

    override fun render(l: FloatArray, r: FloatArray, n: Int) {
        val rootN = root.value; val tn = tune.value
        val xyBend = (x - 0.5f) * 4f
        filtL.set(cutoff.value * 2f.pow((y - 1f) * 7f), res.value * 0.9f)
        val vInc = vibRate.value / SRF; val vd = vibDepth.value / 1200f
        var count = 0
        val tmp = scratch
        tmp.fill(0f, 0, n)
        for (v in pool.voices) {
            if (!v.active) continue
            count++
            val d = v.data
            val rate = semis(v.note - rootN + tn + v.bend + xyBend) * (if (v.rev) -1.0 else 1.0)
            for (i in 0 until n) {
                val inc = rate * (1f + sin(TAU * v.vibPh) * vd)
                v.vibPh += vInc; if (v.vibPh >= 1f) v.vibPh -= 1f
                if (v.pos >= v.e || v.pos < v.s) {
                    if (!v.looping) { v.amp.kill(); break }
                    v.pos = if (v.rev) v.e - (v.s - v.pos) else v.s + (v.pos - v.e)
                    if (v.pos < v.s || v.pos >= v.e) v.pos = if (v.rev) v.e - 1.0 else v.s.toDouble()
                }
                val ip = v.pos.toInt(); val f = (v.pos - ip).toFloat()
                val smp = d[ip] + (d[minOf(ip + 1, d.size - 1)] - d[ip]) * f
                // De-click: fade in/out over a few ms at both region edges.
                val edge = min(v.pos - v.s, v.e - v.pos).toFloat()
                val ramp = if (edge < declick) edge / declick else 1f
                tmp[i] += smp * ramp * v.amp.next() * v.vel * (0.7f + v.pressure * 0.6f)
                v.pos += inc
            }
        }
        for (i in 0 until n) { val o = filtL.process(tmp[i]) * 0.8f; l[i] += o; r[i] += o }
        activeVoices = count
    }
    private val scratch = FloatArray(1024)

    override fun saveExtra(o: JSONObject) { o.put("sampleName", sampleName) }

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
