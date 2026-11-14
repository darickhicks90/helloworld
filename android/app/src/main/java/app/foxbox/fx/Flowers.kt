package app.foxbox.fx

import app.foxbox.audio.Audio
import app.foxbox.audio.DelayLine
import app.foxbox.audio.Fmt
import app.foxbox.audio.OnePole
import app.foxbox.audio.Param
import app.foxbox.audio.Params
import app.foxbox.audio.PitchShifter
import app.foxbox.audio.Reverb
import app.foxbox.audio.Rng
import app.foxbox.audio.SRF
import app.foxbox.audio.TAU
import app.foxbox.audio.XyState
import app.foxbox.audio.semis
import app.foxbox.seq.Ticks
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.sin

/**
 * A Flower is an effect you design: modulation, reverb and rhythmic
 * effects blended together, plus a 4-slot modulation matrix. Planted in the
 * Valley, its bloom (0..1) sets how much of the effect you hear.
 */
class Flower(val id: String = UUID.randomUUID().toString().take(8), var name: String = "Flower") {
    val p = Params()
    private val divisions = listOf("1/32", "1/16", "1/8", "1/8.", "1/4", "1/4.", "1/2")
    private val divSteps = floatArrayOf(0.5f, 1f, 2f, 3f, 4f, 6f, 8f)

    init { p.section("Modulation") }
    val modType = p.choice("modType", "Mod Type", listOf("Chorus", "Flanger"), 0)
    val modRate = p.num("modRate", "Mod Rate", 0.05f, 10f, 0.6f, Fmt.HZ, exp = true)
    val modDepth = p.num("modDepth", "Mod Depth", 0f, 1f, 0.5f, Fmt.PCT)
    val modFb = p.num("modFb", "Mod Feedback", 0f, 0.9f, 0.3f, Fmt.PCT)
    val modMix = p.num("modMix", "Mod Mix", 0f, 1f, 0.5f, Fmt.PCT)
    val tremDepth = p.num("trem", "Tremolo Depth", 0f, 1f, 0f, Fmt.PCT)
    val tremRate = p.choice("tremRate", "Tremolo Rate", divisions, 2)
    val pitch = p.num("pitch", "Pitch Shift", -12f, 12f, 12f, Fmt.ST, step = 1f)
    val pitchMix = p.num("pitchMix", "Pitch Mix", 0f, 1f, 0f, Fmt.PCT)

    init { p.section("Reverb") }
    val revSize = p.num("revSize", "Reverb Size", 0f, 1f, 0.75f, Fmt.PCT)
    val revDamp = p.num("revDamp", "Reverb Damping", 0f, 1f, 0.4f, Fmt.PCT)
    val revMix = p.num("revMix", "Reverb Mix", 0f, 1f, 0.45f, Fmt.PCT)

    init { p.section("Rhythm") }
    val delayTime = p.choice("delayTime", "Delay Time", divisions, 3)
    val delayFb = p.num("delayFb", "Delay Feedback", 0f, 0.95f, 0.45f, Fmt.PCT)
    val delayTone = p.num("delayTone", "Delay Tone", 300f, 12000f, 3500f, Fmt.HZ, exp = true)
    val delayMix = p.num("delayMix", "Delay Mix", 0f, 1f, 0.35f, Fmt.PCT)
    val gateDepth = p.num("gateDepth", "Gate Depth", 0f, 1f, 0f, Fmt.PCT)
    val gateLen = p.num("gateLen", "Gate Length", 0.1f, 1f, 0.5f, Fmt.PCT)

    init { p.section("Output") }
    val level = p.num("level", "Effect Level", 0f, 1f, 0.9f, Fmt.PCT)

    /** Params the matrix (and XY pad) can move. */
    val destinations: List<Param> = p.all.values.filter { !it.isChoice }

    init { p.section("Mod Matrix") }
    val lfo1Rate = p.num("lfo1Rate", "LFO 1 Rate", 0.05f, 12f, 0.8f, Fmt.HZ, exp = true)
    val lfo1Shape = p.choice("lfo1Shape", "LFO 1 Shape", listOf("Sine", "Triangle", "Square", "Random"), 0)
    val lfo2Rate = p.choice("lfo2Rate", "LFO 2 Sync", listOf("1/4", "1/2", "1 bar", "2 bars", "4 bars"), 2)
    val slots = (1..4).map { k ->
        Triple(
            p.choice("src$k", "Mod $k Source", SOURCES, if (k == 1) 3 else 0),
            p.choice("dst$k", "Mod $k Target", destinations.map { it.name }, destinations.indexOf(if (k == 1) revMix else modDepth)),
            p.num("amt$k", "Mod $k Amount", -1f, 1f, if (k == 1) 0.4f else 0f),
        )
    }

    val xy = XyState(p, "revMix", "delayMix")
    val gate = BooleanArray(16) { it % 4 != 3 }

    /** 0 = closed bud, 1 = full bloom. Set from the Valley. */
    @Volatile var bloom = 0f
    /** Activated by the fox (linked + playing). */
    @Volatile var active = false
    var inValley = false
    var vx = 0.5f
    var vy = 0.5f
    var variant = 0

    @Volatile internal var dsp: FlowerDsp? = null

    /** Allocate the audio side ahead of time (call from the UI thread). */
    fun prepare() { if (dsp == null) dsp = FlowerDsp(this) }

    fun divSteps(choice: Param) = divSteps[choice.index.coerceIn(0, divSteps.size - 1)]

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("params", p.save()); put("gate", JSONArray(gate.toList()))
        put("valley", inValley); put("vx", vx.toDouble()); put("vy", vy.toDouble()); put("bloom", bloom.toDouble()); put("variant", variant)
    }

    companion object {
        val SOURCES = listOf("Off", "LFO 1", "LFO 2", "Bloom", "Bar Ramp", "Random")
        fun fromJson(o: JSONObject) = Flower(o.getString("id"), o.optString("name", "Flower")).apply {
            p.load(o.optJSONObject("params"))
            o.optJSONArray("gate")?.let { a -> for (i in 0 until minOf(16, a.length())) gate[i] = a.getBoolean(i) }
            inValley = o.optBoolean("valley"); vx = o.optDouble("vx", 0.5).toFloat(); vy = o.optDouble("vy", 0.5).toFloat()
            bloom = o.optDouble("bloom", 0.0).toFloat(); variant = o.optInt("variant")
        }
    }
}

/** The audio side of a flower (created the first time it plays). */
internal class FlowerDsp(private val f: Flower) {
    private val modL = DelayLine((SRF * 0.06f).toInt()); private val modR = DelayLine((SRF * 0.06f).toInt())
    private val psL = PitchShifter(); private val psR = PitchShifter()
    private val dlL = DelayLine((SRF * 2.6f).toInt()); private val dlR = DelayLine((SRF * 2.6f).toInt())
    private val toneL = OnePole(); private val toneR = OnePole()
    private val reverb = Reverb()
    private val wetL = FloatArray(1024); private val wetR = FloatArray(1024)
    private val revL = FloatArray(1024); private val revR = FloatArray(1024)
    private val rng = Rng(17)
    private var lfo1 = 0f; private var modPh = 0f; private var fbL = 0f; private var fbR = 0f
    private var sh = 0f; private var shBeat = -1L
    private var amount = 0f; private var gateG = 1f
    private val vals = HashMap<Param, Float>()

    val silent get() = amount < 0.0005f

    /** Value of a param after XY and the modulation matrix. */
    private fun m(p: Param) = vals[p] ?: f.xy.v(p)

    private fun modulate(bloom: Float) {
        f.xy.begin()
        vals.clear()
        val t = Audio.transport
        val tick = maxOf(0.0, t.tick)
        lfo1 = (lfo1 + f.lfo1Rate.value * 64 / SRF) % 1f
        val l1 = when (f.lfo1Shape.index) {
            0 -> sin(TAU * lfo1); 1 -> 1f - 4f * kotlin.math.abs(lfo1 - 0.5f); 2 -> if (lfo1 < 0.5f) 1f else -1f
            else -> sh
        }
        val bars = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)[f.lfo2Rate.index]
        val l2 = sin(TAU * ((tick / (Ticks.BAR * bars)) % 1.0).toFloat())
        val beat = (tick / Ticks.PPQ).toLong()
        if (beat != shBeat) { shBeat = beat; sh = rng.bi() }
        val ramp = ((tick % Ticks.BAR) / Ticks.BAR).toFloat()
        for ((src, dst, amt) in f.slots) {
            val s = when (src.index) { 1 -> l1; 2 -> l2; 3 -> bloom; 4 -> ramp; 5 -> sh; else -> continue }
            val target = f.destinations[dst.index.coerceIn(0, f.destinations.size - 1)]
            val base = vals[target]?.let { (it - target.min) / (target.max - target.min) } ?: (f.xy.v(target) - target.min) / (target.max - target.min)
            vals[target] = target.valueAt(base + s * amt.value)
        }
    }

    /** Process the master mix in place. `want` = how much of the effect (bloom × level). */
    fun process(l: FloatArray, r: FloatArray, n: Int, want: Float, bloom: Float) {
        modulate(bloom)
        val t = Audio.transport
        val stepFrames = SRF * 60f / t.bpm / 4f
        val ratio = semis(m(f.pitch)); val pm = m(f.pitchMix)
        val flanger = f.modType.index == 1
        val base = if (flanger) 0.002f * SRF else 0.016f * SRF
        val depth = m(f.modDepth) * (if (flanger) 0.004f else 0.009f) * SRF
        val mInc = m(f.modRate) / SRF; val mfb = m(f.modFb); val mm = m(f.modMix)
        val trem = m(f.tremDepth)
        val tremSteps = f.divSteps(f.tremRate)
        val dTime = minOf(SRF * 2.5f, f.divSteps(f.delayTime) * stepFrames)
        val dfb = m(f.delayFb); val dm = m(f.delayMix)
        toneL.set(m(f.delayTone)); toneR.set(m(f.delayTone))
        reverb.room = 0.7f + m(f.revSize) * 0.28f; reverb.damp = m(f.revDamp)
        val rm = m(f.revMix)
        val gd = m(f.gateDepth); val gl = m(f.gateLen)
        val tick = t.tick
        for (i in 0 until n) {
            var xl = l[i]; var xr = r[i]
            if (pm > 0.001f) { xl += (psL.process(xl, ratio, 0.06f) - xl) * pm; xr += (psR.process(xr, ratio, 0.06f) - xr) * pm }
            modPh += mInc; if (modPh >= 1f) modPh -= 1f
            val lfo = sin(TAU * modPh)
            modL.write(xl + fbL * mfb); modR.write(xr + fbR * mfb)
            fbL = modL.read(base + depth * (0.5f + 0.5f * lfo)); fbR = modR.read(base + depth * (0.5f - 0.5f * lfo))
            xl = xl * (1f - mm * 0.5f) + fbL * mm * 0.7f; xr = xr * (1f - mm * 0.5f) + fbR * mm * 0.7f
            if (trem > 0f && t.playing) {
                val ph = (((tick + i * t.bpm * Ticks.PPQ / 60.0 / SRF) / (Ticks.STEP * tremSteps)) % 1.0).toFloat()
                val g = 1f - trem * (0.5f + 0.5f * sin(TAU * ph)); xl *= g; xr *= g
            }
            val dl = dlL.read(dTime); val dr = dlR.read(dTime)
            dlL.write(xl + toneL.process(dl) * dfb); dlR.write(xr + toneR.process(dr) * dfb)
            xl += dl * dm; xr += dr * dm
            wetL[i] = xl; wetR[i] = xr
            revL[i] = 0f; revR[i] = 0f
        }
        reverb.process(wetL, wetR, revL, revR, n)
        for (i in 0 until n) {
            amount += (want - amount) * 0.002f
            val stepPos = if (t.playing && t.tick >= 0) ((t.tick / Ticks.STEP) + i / stepFrames) else -1.0
            val closed = if (stepPos < 0) 0f else {
                val idx = Math.floorMod(stepPos.toLong(), 16L).toInt()
                if (!f.gate[idx] || (stepPos % 1.0) > gl) 1f else 0f
            }
            gateG += ((1f - gd * closed) - gateG) * 0.01f
            val pl = wetL[i] + revL[i] * rm * 3f; val pr = wetR[i] + revR[i] * rm * 3f
            val g = 1f - amount * (1f - gateG)
            l[i] = (l[i] * (1f - amount) + pl * amount) * g
            r[i] = (r[i] * (1f - amount) + pr * amount) * g
        }
    }
}

/** Runs the active flowers (and the one being auditioned) over the master mix. */
class FlowerRack {
    /** Flowers the Valley has activated. */
    @Volatile var active: List<Flower> = emptyList()
    /** The flower being edited in the studio, heard at full bloom while Audition is on. */
    @Volatile var audition: Flower? = null
    private val running = ArrayList<Flower>()

    fun process(l: FloatArray, r: FloatArray, n: Int) {
        val aud = audition
        val act = active
        // Keep processing flowers that are fading out so tails don't cut off.
        for (f in act) if (f !in running) running += f
        if (aud != null && aud !in running) running += aud
        val it = running.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val want = when { f === aud -> f.level.value; f in act && f.active -> f.bloom * f.level.value; else -> 0f }
            val d = f.dsp ?: FlowerDsp(f).also { f.dsp = it }
            if (want == 0f && d.silent) { it.remove(); continue }
            d.process(l, r, n, want, if (f === aud) 1f else f.bloom)
        }
    }
}
