package app.foxbox.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * A plain, soft tone for hearing notes back while setting up a song (Noodle Forge):
 * a warm sine with a touch of its octave, like a mellow electric piano, so scales and
 * sung ideas are easy to judge. Eight voices; nothing is allocated on the audio thread.
 */
class PreviewVoice {
    private val ids = IntArray(8) { -1 }
    private val ph = FloatArray(8); private val inc = FloatArray(8)
    private val amp = FloatArray(8); private val vel = FloatArray(8)
    private val on = BooleanArray(8); private val age = IntArray(8)
    var volume = 0.5f

    fun noteOn(id: Int, note: Int, v: Float) {
        var k = ids.indexOf(id)
        if (k < 0) k = (0 until 8).firstOrNull { amp[it] < 1e-4f && !on[it] } ?: (0 until 8).maxBy { age[it] }
        ids[k] = id; ph[k] = 0f; inc[k] = (440f * 2f.pow((note - 69) / 12f)) / SRF
        vel[k] = v.coerceIn(0.2f, 1f); on[k] = true; age[k] = 0; amp[k] = 0f
    }

    fun noteOff(id: Int) { val k = ids.indexOf(id); if (k >= 0) on[k] = false }
    fun allOff() { for (k in 0 until 8) on[k] = false }

    fun render(l: FloatArray, r: FloatArray, n: Int) {
        val att = 1f / (0.006f * SRF)
        val decay = exp(-1f / (0.9f * SRF))
        val rel = exp(-1f / (0.12f * SRF))
        for (k in 0 until 8) {
            if (!on[k] && amp[k] < 1e-4f) continue
            var a = amp[k]; var p = ph[k]; val dp = inc[k]; val g = vel[k] * volume * 0.3f
            var t = age[k]
            for (i in 0 until n) {
                a = if (on[k]) (if (t < 0.006f * SRF) (a + att).coerceAtMost(1f) else a * decay + 0.45f * (1f - decay)) else a * rel
                val s = sin(2f * PI.toFloat() * p) + 0.18f * sin(4f * PI.toFloat() * p) * a
                val v = s * a * g
                l[i] += v; r[i] += v
                p += dp; if (p >= 1f) p -= 1f
                t++
            }
            amp[k] = a; ph[k] = p; age[k] = t
        }
    }
}
