package app.foxbox.fx

import app.foxbox.audio.Math
import kotlin.concurrent.Volatile

import app.foxbox.audio.Core
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
import app.foxbox.audio.mtof
import app.foxbox.audio.semis
import app.foxbox.seq.Ticks
import app.foxbox.json.JSONArray
import app.foxbox.json.JSONObject
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh

/**
 * A Flower is a performance effect you design: pitch, resonators,
 * modulation (chorus/flanger, phaser, ring mod, tremolo, auto-pan,
 * decimator), delay, reverb with shimmer, and rhythmic stutter and gate,
 * all blended, with a 6-slot modulation matrix. In Clibbins its bloom
 * (0..1) sets how much of the effect you hear.
 */
class Flower(val id: String = app.foxbox.audio.newId(), var name: String = "Flower") {
    val p = Params()
    private val divisions = listOf("1/32", "1/16", "1/8", "1/8.", "1/4", "1/4.", "1/2")
    private val divSteps = floatArrayOf(0.5f, 1f, 2f, 3f, 4f, 6f, 8f)

    init { p.section("Pitch") }
    val pitch = p.num("pitch", "Pitch Shift", -12f, 12f, 12f, Fmt.ST, step = 1f)
    val pitchMix = p.num("pitchMix", "Pitch Mix", 0f, 1f, 0f, Fmt.PCT)
    init { p.section("Resonators") }
    val resMix = p.num("resMix", "Resonator Mix", 0f, 1f, 0f, Fmt.PCT)
    val resNote = p.num("resNote", "Resonator Pitch", 36f, 84f, 48f, Fmt.NOTE, step = 1f)
    val resChord = p.choice("resChord", "Resonator Chord", listOf("Unison", "Fifth", "Octave", "Minor", "Major"), 3)
    val resDecay = p.num("resDecay", "Resonator Decay", 0.5f, 0.995f, 0.95f)
    val resBright = p.num("resBright", "Resonator Brightness", 0f, 1f, 0.5f, Fmt.PCT)

    init { p.section("Modulation") }
    val modType = p.choice("modType", "Mod Type", listOf("Chorus", "Flanger"), 0)
    val modRate = p.num("modRate", "Mod Rate", 0.05f, 10f, 0.6f, Fmt.HZ, exp = true)
    val modDepth = p.num("modDepth", "Mod Depth", 0f, 1f, 0.5f, Fmt.PCT)
    val modFb = p.num("modFb", "Mod Feedback", 0f, 0.9f, 0.3f, Fmt.PCT)
    val modMix = p.num("modMix", "Mod Mix", 0f, 1f, 0.5f, Fmt.PCT)
    val phMix = p.num("phMix", "Phaser Mix", 0f, 1f, 0f, Fmt.PCT)
    val phRate = p.num("phRate", "Phaser Rate", 0.05f, 8f, 0.4f, Fmt.HZ, exp = true)
    val phFb = p.num("phFb", "Phaser Feedback", 0f, 0.9f, 0.5f, Fmt.PCT)
    val ring = p.num("ring", "Ring Mod", 0f, 1f, 0f, Fmt.PCT)
    val ringFreq = p.num("ringFreq", "Ring Mod Freq", 20f, 2000f, 220f, Fmt.HZ, exp = true)
    val decim = p.num("decim", "Decimator", 0f, 1f, 0f, Fmt.PCT)
    val tremDepth = p.num("trem", "Tremolo Depth", 0f, 1f, 0f, Fmt.PCT)
    val tremRate = p.choice("tremRate", "Tremolo Rate", divisions, 2)
    val panDepth = p.num("pan", "Auto-Pan Depth", 0f, 1f, 0f, Fmt.PCT)
    val panRate = p.choice("panRate", "Auto-Pan Rate", divisions, 4)

    init { p.section("Delay & Reverb") }
    val delayTime = p.choice("delayTime", "Delay Time", divisions, 3)
    val delayFb = p.num("delayFb", "Delay Feedback", 0f, 0.95f, 0.45f, Fmt.PCT)
    val delayTone = p.num("delayTone", "Delay Tone", 300f, 12000f, 3500f, Fmt.HZ, exp = true)
    val delayMix = p.num("delayMix", "Delay Mix", 0f, 1f, 0.35f, Fmt.PCT)
    val revSize = p.num("revSize", "Reverb Size", 0f, 1f, 0.75f, Fmt.PCT)
    val revDamp = p.num("revDamp", "Reverb Damping", 0f, 1f, 0.4f, Fmt.PCT)
    val revMix = p.num("revMix", "Reverb Mix", 0f, 1f, 0.45f, Fmt.PCT)
    val shimmer = p.num("shimmer", "Shimmer", 0f, 1f, 0f, Fmt.PCT)

    init { p.section("Stutter") }
    val stMix = p.num("stMix", "Stutter Mix", 0f, 1f, 0f, Fmt.PCT)
    val stRate = p.choice("stRate", "Stutter Rate", listOf("1/64", "1/32", "1/16", "1/8", "1/4"), 2)
    val stLen = p.choice("stLen", "Stutter Length", listOf("1/8", "1/4", "1/2", "1 bar"), 1)
    val stChance = p.num("stChance", "Stutter Chance", 0f, 1f, 0.5f, Fmt.PCT)
    val stFade = p.num("stFade", "Stutter Fade", 0f, 1f, 0.2f, Fmt.PCT)
    init { p.section("Gate") }
    val gateDepth = p.num("gateDepth", "Gate Depth", 0f, 1f, 0f, Fmt.PCT)
    val gateRate = p.choice("gateRate", "Gate Rate", listOf("1/32", "1/16", "1/8"), 1)
    val gateLen = p.num("gateLen", "Gate Length", 0.1f, 1f, 0.5f, Fmt.PCT)
    val gateSwing = p.num("gateSwing", "Gate Swing", 0f, 0.6f, 0f, Fmt.PCT)
    val gateSmooth = p.num("gateSmooth", "Gate Smoothing", 0f, 1f, 0.2f, Fmt.PCT)

    init { p.section("Output") }
    val level = p.num("level", "Effect Level", 0f, 1f, 0.9f, Fmt.PCT)

    // Pan: placement and movement in the stereo field (added after the others so saved matrices keep their targets).
    init { p.section("Pan") }
    val panPos = p.num("panPos", "Pan Position", -1f, 1f, 0f)
    val width = p.num("width", "Stereo Width", 0f, 2f, 1f, Fmt.X)
    val haas = p.num("haas", "Haas Spread", 0f, 30f, 0f, Fmt.NUM)
    val panShape = p.choice("panShape", "Auto-Pan Shape", listOf("Sine", "Triangle", "Square", "Random"), 0)
    init {
        // The auto-pan controls live with the rest of the pan section.
        p.sections["Modulation"]?.removeAll { it.id == "pan" || it.id == "panRate" }
        p.sections["Pan"]?.addAll(listOf(panDepth, panRate))
    }

    /** Params the matrix (and XY pad) can move. */
    val destinations: List<Param> = p.all.values.filter { !it.isChoice }

    init { p.section("Mod Matrix") }
    val lfo1Rate = p.num("lfo1Rate", "LFO 1 Rate", 0.05f, 12f, 0.8f, Fmt.HZ, exp = true)
    val lfo1Shape = p.choice("lfo1Shape", "LFO 1 Shape", listOf("Sine", "Triangle", "Square", "Random"), 0)
    val lfo2Rate = p.choice("lfo2Rate", "LFO 2 Sync", listOf("1/4", "1/2", "1 bar", "2 bars", "4 bars"), 2)
    val slots = (1..6).map { k ->
        Triple(
            p.choice("src$k", "Mod $k Source", SOURCES, if (k == 1) 3 else 0),
            p.choice("dst$k", "Mod $k Target", destinations.map { it.name }, destinations.indexOf(if (k == 1) revMix else modDepth)),
            p.num("amt$k", "Mod $k Amount", -1f, 1f, if (k == 1) 0.4f else 0f),
        )
    }

    val xy = XyState(p, "revMix", "delayMix")
    val gate = BooleanArray(16) { it % 4 != 3 }

    /** 0 = closed bud, 1 = full bloom. Set from Clibbins. */
    @Volatile var bloom = 0.6f
    var inClibbins = false
    var variant = 0

    fun divSteps(choice: Param) = divSteps[choice.index.coerceIn(0, divSteps.size - 1)]

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("params", p.save()); put("gate", JSONArray(gate.toList()))
        put("valley", inClibbins); put("bloom", bloom.toDouble()); put("variant", variant)
    }

    companion object {
        val SOURCES = listOf("Off", "LFO 1", "LFO 2", "Bloom", "Bar Ramp", "Random")
        fun fromJson(o: JSONObject) = Flower(o.getString("id"), o.optString("name", "Flower")).apply {
            p.load(o.optJSONObject("params"))
            o.optJSONArray("gate")?.let { a -> for (i in 0 until minOf(16, a.length())) gate[i] = a.getBoolean(i) }
            inClibbins = o.optBoolean("valley"); bloom = o.optDouble("bloom", 0.6).toFloat(); variant = o.optInt("variant")
        }
    }
}

/** One running copy of a flower's effect (each daisy chain gets its own). */
class FlowerDsp(private val f: Flower) {
    private val modL = DelayLine((SRF * 0.06f).toInt()); private val modR = DelayLine((SRF * 0.06f).toInt())
    private val psL = PitchShifter(); private val psR = PitchShifter(); private val shim = PitchShifter()
    private val dlL = DelayLine((SRF * 2.6f).toInt()); private val dlR = DelayLine((SRF * 2.6f).toInt())
    private val toneL = OnePole(); private val toneR = OnePole()
    private val reverb = Reverb()
    private val res = Array(3) { DelayLine((SRF / 30f).toInt()) }; private val resLp = FloatArray(3)
    private val apL = FloatArray(4); private val apR = FloatArray(4); private var phFbL = 0f; private var phFbR = 0f
    private val stBuf = Array(2) { FloatArray((SRF * 0.6f).toInt()) }
    private val wetL = FloatArray(1024); private val wetR = FloatArray(1024)
    private val revL = FloatArray(1024); private val revR = FloatArray(1024)
    private val rng = Rng(17)
    private var lfo1 = 0f; private var modPh = 0f; private var phPh = 0f; private var ringPh = 0f
    private var fbL = 0f; private var fbR = 0f
    private var sh = 0f; private var shBeat = -1L
    private var amount = 0f; private var gateG = 1f
    private var decHoldL = 0f; private var decHoldR = 0f; private var decCount = 0f
    private var stActive = false; private var stLenF = 1; private var stPos = 0; private var stWrite = 0; private var stSection = -1L
    private val vals = FloatArray(f.destinations.size)

    val silent get() = amount < 0.0005f

    private fun m(p: Param): Float {
        val i = f.destinations.indexOf(p)
        return if (i >= 0 && !vals[i].isNaN()) vals[i] else f.xy.v(p)
    }

    private fun modulate(bloom: Float) {
        f.xy.begin()
        vals.fill(Float.NaN)
        val t = Core.transport
        val tick = maxOf(0.0, t.tick)
        lfo1 = (lfo1 + f.lfo1Rate.value * 64 / SRF) % 1f
        val l1 = when (f.lfo1Shape.index) { 0 -> sin(TAU * lfo1); 1 -> 1f - 4f * abs(lfo1 - 0.5f); 2 -> if (lfo1 < 0.5f) 1f else -1f; else -> sh }
        val bars = K.BARS[f.lfo2Rate.index]
        val l2 = sin(TAU * ((tick / (Ticks.BAR * bars)) % 1.0).toFloat())
        val beat = (tick / Ticks.PPQ).toLong()
        if (beat != shBeat) { shBeat = beat; sh = rng.bi() }
        val ramp = ((tick % Ticks.BAR) / Ticks.BAR).toFloat()
        for (si in f.slots.indices) {
            val (src, dst, amt) = f.slots[si]
            val s = when (src.index) { 1 -> l1; 2 -> l2; 3 -> bloom; 4 -> ramp; 5 -> sh; else -> continue }
            val ti = dst.index.coerceIn(0, f.destinations.size - 1)
            val target = f.destinations[ti]
            val cur = if (vals[ti].isNaN()) f.xy.v(target) else vals[ti]
            val base = if (target.exp) (kotlin.math.ln(cur / target.min) / kotlin.math.ln(target.max / target.min)) else (cur - target.min) / (target.max - target.min)
            vals[ti] = target.valueAt(base + s * amt.value)
        }
    }

    /** Process `l`/`r` in place. `want` = how much of the effect (bloom × level). */
    fun process(l: FloatArray, r: FloatArray, n: Int, want: Float, bloom: Float) {
        modulate(bloom)
        val t = Core.transport
        val stepFrames = SRF * 60f / t.bpm / 4f
        val ratio = semis(m(f.pitch)); val pm = m(f.pitchMix)
        // Resonators: three combs tuned to a chord.
        val rm = m(f.resMix)
        val chord = K.CHORDS[f.resChord.index.coerceIn(0, K.CHORDS.size - 1)]
        val rn = m(f.resNote); for (k in 0 until 3) resPeriods[k] = SRF / mtof(rn + chord[k])
        val rfb = m(f.resDecay); val rdamp = 0.1f + (1f - m(f.resBright)) * 0.8f
        // Modulation
        val flanger = f.modType.index == 1
        val base = if (flanger) 0.002f * SRF else 0.016f * SRF
        val depth = m(f.modDepth) * (if (flanger) 0.004f else 0.009f) * SRF
        val mInc = m(f.modRate) / SRF; val mfb = m(f.modFb); val mm = m(f.modMix)
        val phm = m(f.phMix); val phInc = m(f.phRate) / SRF; val phfb = m(f.phFb)
        val rg = m(f.ring); val rInc = m(f.ringFreq) / SRF
        val dec = m(f.decim); val decStep = 1f + dec * dec * 30f; val decLevels = if (dec > 0.01f) Math.pow(2.0, (16f - dec * 12f).toDouble()).toFloat() / 2f else 0f
        val trem = m(f.tremDepth); val tremSteps = f.divSteps(f.tremRate)
        val panD = m(f.panDepth); val panSteps = f.divSteps(f.panRate); val shape = f.panShape.index
        val pos = m(f.panPos); val wid = m(f.width); val haasF = (m(f.haas) * SRF / 1000f)
        val dTime = minOf(SRF * 2.5f, f.divSteps(f.delayTime) * stepFrames)
        val dfb = m(f.delayFb); val dm = m(f.delayMix)
        toneL.set(m(f.delayTone)); toneR.set(m(f.delayTone))
        reverb.room = 0.7f + m(f.revSize) * 0.28f; reverb.damp = m(f.revDamp)
        val revMix = m(f.revMix); val shm = m(f.shimmer)
        val stm = m(f.stMix)
        val gd = m(f.gateDepth); val gl = m(f.gateLen); val gSw = m(f.gateSwing); val gSmooth = 0.002f + (1f - m(f.gateSmooth)) * 0.05f
        val gateDiv = K.GATE_DIV[f.gateRate.index]
        val tick0 = t.tick
        val tpf = t.bpm * Ticks.PPQ / 60.0 / SRF

        // Stutter: at the start of each section, maybe capture a slice and repeat it.
        val stSlice = (stepFrames * K.ST_RATE[f.stRate.index]).toInt().coerceIn(32, stBuf[0].size)
        val sectionTicks = Ticks.STEP * K.ST_LEN[f.stLen.index]
        if (stm > 0.001f && t.playing && tick0 >= 0) {
            val section = (tick0 / sectionTicks).toLong()
            if (section != stSection) { stSection = section; stActive = rng.next() < m(f.stChance); stLenF = stSlice; stPos = 0; stWrite = 0 }
        } else stActive = false

        for (i in 0 until n) {
            var xl = l[i]; var xr = r[i]
            if (pm > 0.001f) { xl += (psL.process(xl, ratio, 0.06f) - xl) * pm; xr += (psR.process(xr, ratio, 0.06f) - xr) * pm }
            if (rm > 0.001f) {
                val mono = (xl + xr) * 0.5f
                var acc = 0f
                for (k in 0 until 3) {
                    val y = res[k].read(resPeriods[k])
                    resLp[k] += (y - resLp[k]) * (1f - rdamp)
                    res[k].write(mono * 0.3f + resLp[k] * rfb)
                    acc += y
                }
                acc = tanh(acc * 0.6f)
                xl += (acc - xl) * rm; xr += (acc - xr) * rm
            }
            modPh += mInc; if (modPh >= 1f) modPh -= 1f
            val lfo = sin(TAU * modPh)
            if (mm > 0.001f) {
                modL.write(xl + fbL * mfb); modR.write(xr + fbR * mfb)
                fbL = modL.read(base + depth * (0.5f + 0.5f * lfo)); fbR = modR.read(base + depth * (0.5f - 0.5f * lfo))
                xl = xl * (1f - mm * 0.5f) + fbL * mm * 0.7f; xr = xr * (1f - mm * 0.5f) + fbR * mm * 0.7f
            }
            if (phm > 0.001f) {
                phPh += phInc; if (phPh >= 1f) phPh -= 1f
                val c = 0.35f + 0.6f * (0.5f + 0.5f * sin(TAU * phPh))
                var yl = xl + phFbL * phfb; var yr = xr + phFbR * phfb
                for (k in 0 until 4) {
                    val ol = -c * yl + apL[k]; apL[k] = yl + c * ol; yl = ol
                    val or = -c * yr + apR[k]; apR[k] = yr + c * or; yr = or
                }
                phFbL = yl; phFbR = yr
                xl = xl * (1f - phm * 0.5f) + yl * phm * 0.5f; xr = xr * (1f - phm * 0.5f) + yr * phm * 0.5f
            }
            if (rg > 0.001f) { val rmod = 1f - rg + rg * sin(TAU * ringPh); ringPh += rInc; if (ringPh >= 1f) ringPh -= 1f; xl *= rmod; xr *= rmod }
            if (decLevels > 0f) {
                decCount += 1f
                if (decCount >= decStep) { decCount -= decStep; decHoldL = round(xl * decLevels) / decLevels; decHoldR = round(xr * decLevels) / decLevels }
                xl = decHoldL; xr = decHoldR
            }
            if (t.playing) {
                val tk = tick0 + i * tpf
                if (trem > 0f) { val ph = ((tk / (Ticks.STEP * tremSteps)) % 1.0).toFloat(); val g = 1f - trem * (0.5f + 0.5f * sin(TAU * ph)); xl *= g; xr *= g }
                if (panD > 0f) {
                    val ph = ((tk / (Ticks.STEP * panSteps)) % 1.0).toFloat()
                    val wave = when (shape) { 1 -> 1f - 4f * abs(ph - 0.5f); 2 -> if (ph < 0.5f) 1f else -1f; 3 -> panRnd(tk, panSteps); else -> sin(TAU * ph) }
                    val pn = wave * panD; xl *= 1f - maxOf(0f, pn); xr *= 1f + minOf(0f, pn)
                }
            }
            val dl = dlL.read(dTime); val dr = dlR.read(dTime)
            dlL.write(xl + toneL.process(dl) * dfb); dlR.write(xr + toneR.process(dr) * dfb)
            xl += dl * dm; xr += dr * dm
            wetL[i] = xl; wetR[i] = xr
            val shimIn = if (shm > 0.001f) shim.process((xl + xr) * 0.5f, 2f, 0.08f) * shm else 0f
            revL[i] = shimIn; revR[i] = shimIn
        }
        // Reverb input is the wet signal (+shimmer); output is added on top.
        val inL = revL; val inR = revR
        for (i in 0 until n) { inL[i] += wetL[i]; inR[i] += wetR[i] }
        val outL = FloatArrayPool.a; val outR = FloatArrayPool.b
        outL.fill(0f, 0, n); outR.fill(0f, 0, n)
        reverb.process(inL, inR, outL, outR, n)

        for (i in 0 until n) {
            amount += (want - amount) * 0.002f
            var pl = wetL[i] + outL[i] * revMix * 3f
            var pr = wetR[i] + outR[i] * revMix * 3f
            // Stutter: record the first slice of the section, then repeat it.
            if (stActive) {
                if (stWrite < stLenF) { stBuf[0][stWrite] = pl; stBuf[1][stWrite] = pr; stWrite++ }
                else {
                    val fade = 1f - f.stFade.value * (stPos / stLenF.toFloat())
                    val edge = minOf(stPos, stLenF - stPos).coerceAtMost(64) / 64f
                    pl = pl * (1f - stm) + stBuf[0][stPos] * stm * fade * edge
                    pr = pr * (1f - stm) + stBuf[1][stPos] * stm * fade * edge
                    if (++stPos >= stLenF) stPos = 0
                }
            }
            // Gate (swingable, smoothed), applied to the processed signal.
            val stepPos = if (t.playing && t.tick >= 0) ((t.tick / (Ticks.STEP * gateDiv)) + i / (stepFrames * gateDiv)) else -1.0
            val closed = if (stepPos < 0) 0f else {
                val idx = Math.floorMod(stepPos.toLong(), 16L).toInt()
                val frac = (stepPos % 1.0).toFloat() - (if (idx % 2 == 1) gSw * 0.5f else 0f)
                if (!f.gate[idx] || frac < 0f || frac > gl) 1f else 0f
            }
            gateG += ((1f - gd * closed) - gateG) * gSmooth
            // Pan section: width (mid/side), Haas spread, then the flower's own position.
            if (wid != 1f) { val mid = (pl + pr) * 0.5f; val side = (pl - pr) * 0.5f * wid; pl = mid + side; pr = mid - side }
            if (haasF > 1f) pr = haasStep(pr, haasF.toInt())
            if (pos != 0f) { pl *= minOf(1f, 1f - pos); pr *= minOf(1f, 1f + pos) }
            val g = 1f - amount * (1f - gateG)
            l[i] = (l[i] * (1f - amount) + pl * amount) * g
            r[i] = (r[i] * (1f - amount) + pr * amount) * g
        }
    }

    private object FloatArrayPool { val a = FloatArray(1024); val b = FloatArray(1024) }
    private val resPeriods = FloatArray(3)
    private val haasBuf = FloatArray(2048); private var haasW = 0
    private fun haasStep(x: Float, d: Int): Float {
        haasBuf[haasW] = x
        var r = haasW - d; if (r < 0) r += haasBuf.size
        haasW++; if (haasW >= haasBuf.size) haasW = 0
        return haasBuf[r]
    }
    private var rndSeg = -1L; private var rndVal = 0f
    private fun panRnd(tk: Double, steps: Float): Float {
        val seg = (tk / (Ticks.STEP * steps)).toLong()
        if (seg != rndSeg) { rndSeg = seg; rndVal = rng.bi() }
        return rndVal
    }
    private object K {
        val BARS = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
        val CHORDS = arrayOf(floatArrayOf(0f, 0.08f, -0.08f), floatArrayOf(0f, 7f, 12f), floatArrayOf(0f, 12f, 24f), floatArrayOf(0f, 3f, 7f), floatArrayOf(0f, 4f, 7f))
        val GATE_DIV = floatArrayOf(0.5f, 1f, 2f)
        val ST_RATE = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
        val ST_LEN = floatArrayOf(2f, 4f, 8f, 16f)
    }
}

/**
 * Runs the flowers: the one being auditioned in the studio over the master
 * mix, and up to three daisy chains, each with its own copy of a flower
 * applied to the blocks and audio channels routed to it.
 */
class FlowerRack {
    class Chain(val flower: Flower) { val dsp = FlowerDsp(flower) }

    @Volatile var audition: Flower? = null
        set(v) { field = v; auditionDsp = v?.let { FlowerDsp(it) } }
    @Volatile private var auditionDsp: FlowerDsp? = null
    /** Daisy chains (index 0..2). */
    @Volatile var chains: Array<Chain?> = arrayOfNulls(3)
    /** Which chain each block (by engine id) and audio channel feeds, or -1. */
    @Volatile var blockRoute: Map<String, Int> = emptyMap()
    @Volatile var channelRoute = IntArray(3) { -1 }

    fun processChain(k: Int, l: FloatArray, r: FloatArray, n: Int) {
        val c = chains[k] ?: return
        c.dsp.process(l, r, n, c.flower.bloom * c.flower.level.value, c.flower.bloom)
    }

    fun processAudition(l: FloatArray, r: FloatArray, n: Int) {
        val f = audition ?: return
        auditionDsp?.process(l, r, n, f.level.value, 1f)
    }
}
