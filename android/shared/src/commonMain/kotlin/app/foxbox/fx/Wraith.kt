package app.foxbox.fx

import app.foxbox.audio.Fmt
import app.foxbox.audio.Params
import app.foxbox.audio.PitchShifter
import app.foxbox.audio.SR
import app.foxbox.audio.SRF
import app.foxbox.audio.mtof
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Wraith: a feedback loop that only sings in key.
 *
 * The lane's sound is fed into a loop of resonant filters, one on every note of
 * the project's scale between Low note and High note; everything between those
 * notes is filtered away. When the lane's sound rises past Sensitivity the loop
 * opens (like a sidechain opening a gate) and starts to feed back, and Dwell sets
 * how long it keeps singing after the sound falls away. Follow leans the loop
 * towards the notes actually being played, so it blooms on and around them.
 *
 * The colour section (pitch, tone, rhythm, delay, reverb) works on what comes
 * out of the loop and never feeds back into it, so it changes the sound but not
 * the loop's behaviour. Runs on the audio thread; nothing is allocated there.
 */
class Wraith {
    val p = Params()

    init { p.section("Wraith") }
    val on = p.toggle("on", "Wraith")
    val low = p.num("low", "Low Note", 24f, 96f, 45f, Fmt.NOTE, step = 1f)
    val high = p.num("high", "High Note", 36f, 108f, 81f, Fmt.NOTE, step = 1f)
    val feedback = p.num("fb", "Feedback", 0f, 1f, 0.72f, Fmt.PCT)
    val mix = p.num("mix", "Mix", 0f, 1f, 0.45f, Fmt.PCT)
    val sens = p.num("sens", "Sensitivity", -60f, -6f, -34f, Fmt.DB)
    val dwell = p.num("dwell", "Dwell", 0.05f, 8f, 1.4f, Fmt.SEC, exp = true)
    val focus = p.num("focus", "Focus", 0f, 1f, 0.6f, Fmt.PCT)
    val follow = p.num("follow", "Follow", 0f, 1f, 0.7f, Fmt.PCT)

    init { p.section("Wraith Colour") }
    val pitch = p.choice("pitch", "Pitch", listOf("Off", "Octave up", "Octave down", "Fifth up", "Fourth down", "Up & down"))
    val pitchMix = p.num("pitchMix", "Pitch Mix", 0f, 1f, 0.5f, Fmt.PCT)
    val tone = p.choice("tone", "Tone", listOf("Clear", "Warm", "Bright", "Lo-fi", "Radio"))
    val toneAmt = p.num("toneAmt", "Tone Amount", 0f, 1f, 0.5f, Fmt.PCT)
    val rhythm = p.choice("rhythm", "Rhythm", listOf("Off", "Tremolo", "Chop", "Swell", "Wah", "Pan"))
    val rhythmRate = p.choice("rhythmRate", "Rhythm Rate", RATE_NAMES, 2)
    val rhythmDepth = p.num("rhythmDepth", "Rhythm Depth", 0f, 1f, 0.7f, Fmt.PCT)
    val delay = p.choice("delay", "Delay", listOf("Off", "Tape", "Ping-pong", "Dotted", "Triplet taps"))
    val delayTime = p.choice("delayTime", "Delay Time", RATE_NAMES, 3)
    val delayFb = p.num("delayFb", "Delay Feedback", 0f, 0.9f, 0.45f, Fmt.PCT)
    val delayMix = p.num("delayMix", "Delay Mix", 0f, 1f, 0.35f, Fmt.PCT)
    val verb = p.choice("verb", "Reverb", listOf("Off", "Room", "Hall", "Cathedral", "Shimmer"), 2)
    val verbSize = p.num("verbSize", "Reverb Size", 0f, 1f, 0.6f, Fmt.PCT)
    val verbMix = p.num("verbMix", "Reverb Mix", 0f, 1f, 0.4f, Fmt.PCT)

    fun reset() { p.all.values.forEach { it.set(it.def) } }

    /** How open the loop is right now (0..1), for the screen. */
    var openness = 0f; private set

    // ------------------------------------------------------------------ the loop's filters, one per scale note

    private class Res {
        var g = 0f; var k = 0.1f; var a1 = 0f; var a2 = 0f; var a3 = 0f; var ic1 = 0f; var ic2 = 0f
        fun set(hz: Float, q: Float) {
            g = tan(PI * hz.coerceIn(20f, SRF * 0.45f) / SRF).toFloat(); k = 1f / q
            a1 = 1f / (1f + g * (g + k)); a2 = g * a1; a3 = g * a2
        }
        /** Band pass, unity gain at the centre. */
        fun run(x: Float): Float {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1; ic2 = 2f * v2 - ic2
            return v1 * k
        }
        fun clear() { ic1 = 0f; ic2 = 0f }
    }

    private val loopRes = Array(MAX_BANDS) { Res() }
    private val heardRes = Array(MAX_BANDS) { Res() }
    private val heard = FloatArray(MAX_BANDS)
    private val weight = FloatArray(MAX_BANDS)
    private var bands = 0
    private var bandSig = -1L
    private val edgeLo = Zdf(); private val edgeHi = Zdf()

    private fun layout(mask: Int) {
        val lo = low.value.toInt(); val hi = max(lo + 1, high.value.toInt())
        val q = 6f + focus.value * focus.value * 70f
        val sig = (mask.toLong() shl 40) or (lo.toLong() shl 28) or (hi.toLong() shl 16) or (q * 10).toLong()
        if (sig == bandSig) return
        bandSig = sig
        val m = if (mask and 0xFFF == 0) 0xFFF else mask
        var n = 0
        for (note in lo..hi) {
            if (n >= MAX_BANDS) break
            if ((m shr (note % 12)) and 1 == 0) continue
            val hz = mtof(note.toFloat())
            if (hz > SRF * 0.4f) break
            loopRes[n].set(hz, q); heardRes[n].set(hz, q * 0.5f)
            n++
        }
        // new filters start quiet (no burst when the scale or range changes)
        for (b in n until bands) { loopRes[b].clear(); heardRes[b].clear(); heard[b] = 0f }
        bands = n
        edgeLo.set(mtof(lo - 1f), 0.1f); edgeHi.set(mtof(hi + 1f), 0.1f)
    }

    // ------------------------------------------------------------------ colour

    private val shiftA = PitchShifter(); private val shiftB = PitchShifter()
    private val toneLp = Zdf(); private val toneHp = Zdf(); private var holdV = 0f; private var holdN = 0
    private val wah = Zdf()
    private val dlL = FloatArray(SR * 4 + 4); private val dlR = FloatArray(SR * 4 + 4); private var dw = 0
    private val tapeLp = Zdf(); private var tapeWow = 0.0
    private val fdn = Array(8) { FloatArray((SR * 0.24f).toInt() + 8) }
    private val fdnPos = IntArray(8); private val fdnLp = FloatArray(8)
    private val fdnLen = IntArray(8)
    private val fdnOut = FloatArray(8)
    private val shimmer = PitchShifter()
    private var fdnSig = -1f
    private val fdnGain = FloatArray(8)

    private fun readDl(buf: FloatArray, d: Float): Float {
        val n = buf.size
        var pos = dw - d
        while (pos < 0) pos += n
        val i = pos.toInt(); val f = pos - i
        val a = buf[i % n]; val b = buf[(i + 1) % n]
        return a + (b - a) * f
    }

    private fun setupVerb() {
        val type = verb.index; val size = verbSize.value
        val sig = type * 10f + size
        if (sig == fdnSig) return
        fdnSig = sig
        val scale = when (type) { 1 -> 0.35f; 2 -> 0.75f; 3 -> 1f; else -> 0.8f } * (0.55f + 0.45f * size)
        val decay = when (type) { 1 -> 0.5f; 2 -> 2.6f; 3 -> 7f; else -> 5f } * (0.4f + 1.2f * size)
        for (k in 0 until 8) {
            fdnLen[k] = min(fdn[k].size - 2, (PRIMES[k] * scale * SRF / 48000f).toInt().coerceAtLeast(64))
            fdnGain[k] = 10f.pow(-3f * fdnLen[k] / (decay * SRF))
        }
    }

    // ------------------------------------------------------------------ state

    private var inEnv = 0f
    private var gate = 0f
    private var holdLeft = 0
    private var y1 = 0f
    private var dcX = 0f; private var dcY = 0f
    private var chop = 1f

    /** Called while switched off: lets the loop fall silent so switching it on doesn't burst. */
    fun idle() {
        if (gate == 0f && y1 == 0f) return
        gate = 0f; y1 = 0f; inEnv = 0f
        for (b in 0 until MAX_BANDS) { loopRes[b].clear(); heardRes[b].clear(); heard[b] = 0f }
        dlL.fill(0f); dlR.fill(0f); fdn.forEach { it.fill(0f) }; fdnLp.fill(0f)
    }

    /**
     * Process one block in place: dry stays, the loop's colourised output is added
     * at Mix. `tick` is the transport position (for rhythm and delay sync) and
     * `scaleMask` has bit n set for each pitch class n (0 = C) in the scale.
     */
    fun process(l: FloatArray, r: FloatArray, n: Int, bpm: Float, tick: Double, scaleMask: Int) {
        layout(scaleMask)
        setupVerb()
        val nb = bands
        val th = sens.value
        val atk = exp(-1f / (0.004f * SRF))
        val envRel = exp(-1f / (0.06f * SRF))
        val holdLen = (dwell.value * SRF).toInt()
        val fade = exp(-1f / (0.18f * SRF))
        val heardRel = exp(-1f / ((0.15f + dwell.value * 0.5f) * SRF))
        // past about 70% the loop sings on its own (the saturation keeps it in bounds)
        val fbMax = 1.12f * sqrt(feedback.value)
        val fol = follow.value
        val wet = mix.value
        // colour settings
        val pType = pitch.index; val pMix = pitchMix.value
        val tType = tone.index; val tAmt = toneAmt.value
        val rType = rhythm.index; val rDepth = rhythmDepth.value; val rBeats = RATES[rhythmRate.index]
        val dType = delay.index; val dFb = delayFb.value; val dMix = delayMix.value
        val beatSec = 60f / bpm.coerceIn(20f, 400f)
        val dSamp = (RATES[delayTime.index] * beatSec * SRF).coerceIn(32f, dlL.size - 8f)
        val vType = verb.index; val vMix = verbMix.value
        when (tType) { 1 -> toneLp.set(2400f - 1400f * tAmt, 0.1f); 3 -> toneLp.set(5200f - 3200f * tAmt, 0.2f); 4 -> { toneLp.set(3000f, 0.35f); toneHp.set(500f + 300f * tAmt, 0.35f) }; 2 -> toneHp.set(2200f, 0.1f) }
        tapeLp.set(3800f, 0.05f)
        val ticksPerSample = bpm * 96.0 / 60.0 / SRF
        var tk = tick

        var i = 0
        while (i < n) {
            // every 32 samples: how strongly each note is being played sets how much it feeds back
            if ((i and 31) == 0) {
                var top = 1e-5f
                for (b in 0 until nb) if (heard[b] > top) top = heard[b]
                for (b in 0 until nb) { val w = sqrt(heard[b] / top); weight[b] = (1f - fol) + fol * w }
            }
            val x = (l[i] + r[i]) * 0.5f
            // the gate: opens when the sound passes Sensitivity, closes over Dwell
            val ax = abs(x)
            inEnv = if (ax > inEnv) atk * inEnv + (1 - atk) * ax else envRel * inEnv + (1 - envRel) * ax
            val db = if (inEnv > 1e-6f) 20f * log10(inEnv) else -120f
            // past Sensitivity it opens fully and stays open for Dwell after the sound falls away, then fades
            val target = ((db - th) / 6f).coerceIn(0f, 1f)
            if (target > 0.5f) holdLeft = holdLen
            else if (holdLeft > 0) holdLeft--
            val want = if (holdLeft > 0) 1f else target
            gate = if (want > gate) gate + (want - gate) * 0.01f else gate * fade + want * (1 - fade)
            // which notes are being played
            for (b in 0 until nb) {
                val h = abs(heardRes[b].run(x))
                heard[b] = if (h > heard[b]) 0.9f * heard[b] + 0.1f * h else heardRel * heard[b] + (1 - heardRel) * h
            }
            // the loop: the sound plus what came round last time, through the scale's filters
            val s = x * 0.6f + y1 * fbMax * gate
            var y = 0f
            for (b in 0 until nb) y += loopRes[b].run(s) * weight[b]
            y = tanh(y * 1.3f) / 1.3f
            // keep it centred
            val d = y - dcX + 0.995f * dcY; dcX = y; dcY = d; y = d
            y1 = y

            // ---- colour (never goes back into the loop)
            // the range's edges are shaped outside the loop (inside, their phase would detune it)
            var c = edgeHi.run(edgeLo.run(y, 1), 0) * 1.6f
            if (pType > 0) {
                val sh = when (pType) {
                    1 -> shiftA.process(c, 2f, 0.06f)
                    2 -> shiftA.process(c, 0.5f, 0.09f)
                    3 -> shiftA.process(c, 1.4983f, 0.06f)
                    4 -> shiftA.process(c, 0.7491f, 0.08f)
                    else -> (shiftA.process(c, 2f, 0.06f) + shiftB.process(c, 0.5f, 0.09f)) * 0.6f
                }
                c += (sh - c) * pMix
            }
            when (tType) {
                1 -> { val w = toneLp.run(c, 0); c = tanh(w * (1f + 2f * tAmt)) / (1f + tAmt) }
                2 -> c += toneHp.run(c, 1) * tAmt * 1.5f
                3 -> {
                    // fewer bits, fewer samples
                    val hold = 1 + (tAmt * 14f).toInt()
                    if (holdN++ % hold == 0) { val bits = 16f - 11f * tAmt; val q = 2f.pow(bits - 1); holdV = (c * q).toInt() / q }
                    c = toneLp.run(holdV, 0)
                }
                4 -> c = tanh(toneHp.run(toneLp.run(c, 0), 1) * (2f + 4f * tAmt)) * 0.5f
            }
            var cl = c; var cr = c
            if (rType > 0) {
                val ph = ((tk / 96.0 / rBeats) % 1.0).toFloat()
                when (rType) {
                    1 -> { val g = 1f - rDepth * (0.5f - 0.5f * cos(2f * PI.toFloat() * ph)); cl *= g; cr *= g }
                    2 -> { val g = if (ph < 0.5f) 1f else 1f - rDepth; chop += (g - chop) * 0.004f; cl *= chop; cr *= chop }
                    3 -> { val g = 1f - rDepth * (1f - ph * ph); cl *= g; cr *= g }
                    4 -> { wah.set(300f * 10f.pow(rDepth * (0.5f - 0.5f * cos(2f * PI.toFloat() * ph))), 0.75f); val w = wah.run(c, 2); cl = w; cr = w }
                    5 -> { val pn = rDepth * sin(2f * PI.toFloat() * ph); cl *= min(1f, 1f - pn); cr *= min(1f, 1f + pn) }
                }
            }
            // delay
            if (dType > 0) {
                var el: Float; var er: Float
                when (dType) {
                    1 -> {
                        tapeWow += 0.6 / SRF
                        val wob = (sin(tapeWow * 2 * PI) * 0.0025 * dSamp).toFloat()
                        el = tapeLp.run(readDl(dlL, dSamp + wob), 0); er = el
                        dlL[dw] = tanh(cl + el * dFb); dlR[dw] = dlL[dw]
                    }
                    2 -> { el = readDl(dlL, dSamp); er = readDl(dlR, dSamp); dlL[dw] = (cl + cr) * 0.5f + er * dFb; dlR[dw] = el * dFb }
                    3 -> { el = readDl(dlL, dSamp * 0.75f); er = readDl(dlR, dSamp); dlL[dw] = cl + el * dFb; dlR[dw] = cr + er * dFb }
                    else -> {
                        val t1 = readDl(dlL, dSamp / 3f); val t2 = readDl(dlL, dSamp * 2f / 3f); val t3 = readDl(dlL, dSamp)
                        el = t1 * 0.8f + t3 * 0.6f; er = t2 * 0.7f + t3 * 0.6f
                        dlL[dw] = (cl + cr) * 0.5f + t3 * dFb; dlR[dw] = dlL[dw]
                    }
                }
                cl += el * dMix; cr += er * dMix
            }
            dw++; if (dw >= dlL.size) dw = 0
            // reverb: eight lines mixed by a Hadamard matrix
            if (vType > 0) {
                val inj = (cl + cr) * 0.25f
                for (k in 0 until 8) { val buf = fdn[k]; var rp = fdnPos[k] - fdnLen[k]; if (rp < 0) rp += buf.size; fdnOut[k] = buf[rp] }
                hadamard(fdnOut)
                var ol = 0f; var or = 0f
                val shimmerIn = if (vType == 4) shimmer.process(fdnOut[0] * 0.5f, 2f, 0.07f) else 0f
                for (k in 0 until 8) {
                    val buf = fdn[k]
                    fdnLp[k] += (fdnOut[k] - fdnLp[k]) * 0.55f
                    buf[fdnPos[k]] = if (vType == 4 && k < 2) inj + (fdnLp[k] * 0.7f + shimmerIn * 0.3f) * fdnGain[k] else inj + fdnLp[k] * fdnGain[k]
                    fdnPos[k]++; if (fdnPos[k] >= buf.size) fdnPos[k] = 0
                    if (k and 1 == 0) ol += fdnOut[k] else or += fdnOut[k]
                }
                cl = cl * (1f - 0.5f * vMix) + ol * 0.35f * vMix
                cr = cr * (1f - 0.5f * vMix) + or * 0.35f * vMix
            }
            // a soft ceiling on what Wraith adds, whatever the settings
            l[i] += tanh(cl) * wet; r[i] += tanh(cr) * wet
            tk += ticksPerSample
            i++
        }
        openness = gate
    }

    private fun hadamard(v: FloatArray) {
        var h = 1
        while (h < 8) {
            var i = 0
            while (i < 8) { for (j in i until i + h) { val a = v[j]; val b = v[j + h]; v[j] = a + b; v[j + h] = a - b }; i += h * 2 }
            h *= 2
        }
        val s = 0.35355339f
        for (k in 0 until 8) v[k] *= s
    }

    companion object {
        const val MAX_BANDS = 32
        /** Rhythm and delay divisions, in beats. */
        val RATE_NAMES = listOf("1/32", "1/16", "1/8", "1/8 dot", "1/8 trip", "1/4", "1/4 dot", "1/2", "1 bar")
        val RATES = floatArrayOf(0.125f, 0.25f, 0.5f, 0.75f, 1f / 3f, 1f, 1.5f, 2f, 4f)
        private val PRIMES = intArrayOf(1499, 1873, 2311, 2753, 3209, 3697, 4201, 4729)
    }
}
