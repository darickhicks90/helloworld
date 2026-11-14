package app.foxbox.engines

import app.foxbox.audio.Audio
import app.foxbox.audio.DelayLine
import app.foxbox.audio.Engine
import app.foxbox.audio.Fmt
import app.foxbox.audio.OnePole
import app.foxbox.audio.PitchShifter
import app.foxbox.audio.SR
import app.foxbox.audio.SRF
import app.foxbox.audio.TAU
import app.foxbox.audio.semis
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sin

/**
 * Teeth — rhythmic master-bus mangler. Pitch shift, tempo-synced delay and
 * flanger/ring-mod are mixed together, played from the XY pad and chopped
 * by a 16-step gate pattern. It bites while you touch the pad, while a
 * Teeth clip plays, or all the time when the jaw is locked.
 */
class Teeth : Engine("teeth", "Teeth") {
    override val isInstrument = false
    override val springX: Float? = null
    override val springY: Float? = null
    override val xName get() = p[TARGETS[xTarget.index]].name
    override val yName get() = p[TARGETS[yTarget.index]].name

    private val divisions = listOf("1/32", "1/16", "1/16.", "1/8t", "1/8", "1/8.", "1/4", "1/2")
    private val divSteps = floatArrayOf(0.5f, 1f, 1.5f, 4f / 3f, 2f, 3f, 4f, 8f)

    init { p.section("Bite") }
    val locked = p.toggle("locked", "Lock Jaw")
    val gateMode = p.choice("gateMode", "Gate Mode", listOf("Open", "Pattern"), 1)
    private val gateLen = p.num("gateLen", "Gate Length", 0.1f, 1f, 0.6f, Fmt.PCT)
    private val wet = p.num("wet", "Wet Level", 0f, 1f, 0.85f, Fmt.PCT)
    private val duck = p.num("duck", "Dry Ducking", 0f, 1f, 0.35f, Fmt.PCT)

    init { p.section("Pitch Shift") }
    private val pitch = p.num("pitch", "Pitch Shift", -24f, 24f, 7f, Fmt.ST)
    private val window = p.num("window", "Grain Window", 0.015f, 0.25f, 0.06f, Fmt.SEC, exp = true)
    private val pitchMix = p.num("pitchMix", "Pitch Mix", 0f, 1f, 0.6f, Fmt.PCT)

    init { p.section("Delay") }
    private val division = p.choice("division", "Delay Time", divisions, 5)
    private val feedback = p.num("feedback", "Feedback", 0f, 0.95f, 0.5f, Fmt.PCT)
    private val tone = p.num("tone", "Delay Tone", 300f, 14000f, 3500f, Fmt.HZ, exp = true)
    private val chain = p.num("chain", "Pitch > Delay", 0f, 1f, 0.35f, Fmt.PCT)
    private val delayMix = p.num("delayMix", "Delay Mix", 0f, 1f, 0.6f, Fmt.PCT)

    init { p.section("Modulation") }
    private val flRate = p.num("flRate", "Flanger Rate", 0.05f, 20f, 0.45f, Fmt.HZ, exp = true)
    private val flDepth = p.num("flDepth", "Flanger Depth", 0f, 1f, 0.6f, Fmt.PCT)
    private val flFb = p.num("flFb", "Flanger Feedback", 0f, 0.92f, 0.55f, Fmt.PCT)
    private val ring = p.num("ring", "Ring Mod Amount", 0f, 1f, 0f, Fmt.PCT)
    private val ringFreq = p.num("ringFreq", "Ring Mod Freq", 20f, 2500f, 110f, Fmt.HZ, exp = true)
    private val modMix = p.num("modMix", "Mod Mix", 0f, 1f, 0.5f, Fmt.PCT)

    init { p.section("XY Pad") }
    val xTarget = p.choice("xTarget", "X Axis", TARGETS.map { it }, TARGETS.indexOf("pitch"))
    val yTarget = p.choice("yTarget", "Y Axis", TARGETS.map { it }, TARGETS.indexOf("feedback"))

    /** The 16-step bite pattern. */
    val bites = booleanArrayOf(true, false, false, true, false, false, true, false, true, false, true, true, false, false, true, false)
    @Volatile var biting = false; private set
    @Volatile var currentStep = -1

    private val psL = PitchShifter(); private val psR = PitchShifter()
    private val dlL = DelayLine(SR * 5 / 2); private val dlR = DelayLine(SR * 5 / 2)
    private val toneL = OnePole(); private val toneR = OnePole()
    private val flL = DelayLine(SR / 20); private val flR = DelayLine(SR / 20)
    private var fbL = 0f; private var fbR = 0f; private var flOutL = 0f; private var flOutR = 0f
    private var lfoPh = 0f; private var ringPh = 0f
    private var gate = 1f; private var gateTarget = 1f; private var gateClose = -1f
    private var wetG = 0f; private var dryG = 1f

    override fun noteOn(key: Int, note: Int, vel: Float) {}
    override fun noteOff(key: Int) {}
    override fun render(l: FloatArray, r: FloatArray, n: Int) {}

    /** Value of a param, overridden by the XY pad while it is in control. */
    private fun v(param: app.foxbox.audio.Param, xyOn: Boolean): Float {
        if (xyOn) {
            if (TARGETS[xTarget.index] == param.id) return valueAt(param, x)
            if (TARGETS[yTarget.index] == param.id) return valueAt(param, y)
        }
        return param.value
    }
    private fun valueAt(param: app.foxbox.audio.Param, n: Float): Float =
        if (param.exp) param.min * Math.pow((param.max / param.min).toDouble(), n.toDouble()).toFloat() else param.min + n * (param.max - param.min)

    override fun onStep(step: Long) {
        val idx = (step % 16).toInt()
        currentStep = idx
        if (gateMode.index == 1 && biting) {
            gateTarget = if (bites[idx]) 1f else 0f
            gateClose = if (bites[idx]) SRF * 60f / Audio.transport.bpm / 4f * gateLen.value else -1f
        } else { gateTarget = 1f; gateClose = -1f }
    }

    /** Process the master mix in place. */
    fun process(l: FloatArray, r: FloatArray, n: Int) {
        val auto = automated; automated = false
        val xyOn = touching || auto
        biting = locked.on || xyOn
        if (!biting && wetG < 0.0005f) { wetG = 0f; dryG = 1f; return }
        if (!Audio.transport.playing || gateMode.index == 0) { gateTarget = 1f; gateClose = -1f }

        val ratio = semis(v(pitch, xyOn)); val win = v(window, xyOn); val pm = v(pitchMix, xyOn)
        val steps = divSteps[v(division, xyOn).toInt().coerceIn(0, divSteps.size - 1)]
        val dSamples = minOf(SRF * 2.4f, steps * SRF * 60f / Audio.transport.bpm / 4f)
        val fb = v(feedback, xyOn); val ch = v(chain, xyOn); val dm = v(delayMix, xyOn)
        toneL.set(v(tone, xyOn)); toneR.set(v(tone, xyOn))
        val lfoInc = v(flRate, xyOn) / SRF; val depth = v(flDepth, xyOn) * 0.0035f * SRF
        val ffb = v(flFb, xyOn); val rg = v(ring, xyOn); val rInc = v(ringFreq, xyOn) / SRF; val mm = v(modMix, xyOn)
        val wetTarget = if (biting) v(wet, xyOn) else 0f
        val dk = duck.value
        for (i in 0 until n) {
            if (gateClose >= 0f) { gateClose -= 1f; if (gateClose < 0f) gateTarget = 0f }
            gate += (gateTarget - gate) * 0.004f
            wetG += (wetTarget - wetG) * 0.002f
            dryG += ((if (biting) 1f - dk * gate else 1f) - dryG) * 0.004f
            val inL = l[i]; val inR = r[i]
            val pL = psL.process(inL, ratio, win); val pR = psR.process(inR, ratio, win)
            val dOutL = dlL.read(dSamples); val dOutR = dlR.read(dSamples)
            fbL = toneL.process(dOutL) * fb; fbR = toneR.process(dOutR) * fb
            dlL.write(inL + pL * ch + fbL); dlR.write(inR + pR * ch + fbR)
            val lfo = sin(TAU * lfoPh); lfoPh += lfoInc; if (lfoPh >= 1f) lfoPh -= 1f
            flL.write(inL + flOutL * ffb); flR.write(inR + flOutR * ffb)
            flOutL = flL.read(0.004f * SRF + lfo * depth * 0.5f + depth * 0.5f)
            flOutR = flR.read(0.004f * SRF - lfo * depth * 0.5f + depth * 0.5f)
            val rm = 1f - rg + rg * sin(TAU * ringPh); ringPh += rInc; if (ringPh >= 1f) ringPh -= 1f
            val wL = pL * pm + dOutL * dm + flOutL * rm * mm
            val wR = pR * pm + dOutR * dm + flOutR * rm * mm
            l[i] = inL * dryG + wL * wetG * gate
            r[i] = inR * dryG + wR * wetG * gate
        }
    }

    override fun saveExtra(o: JSONObject) { o.put("bites", JSONArray(bites.toList())) }
    override fun loadExtra(o: JSONObject?) {
        val a = o?.optJSONArray("bites") ?: return
        for (i in 0 until minOf(16, a.length())) bites[i] = a.getBoolean(i)
    }

    companion object {
        val TARGETS = listOf("pitch", "window", "pitchMix", "division", "feedback", "tone", "chain", "delayMix", "flRate", "flDepth", "ring", "ringFreq", "modMix", "wet", "gateLen")
    }
}
