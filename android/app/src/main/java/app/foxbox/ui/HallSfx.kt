package app.foxbox.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The great hall's sound effects and rumbles. Every sound is synthesised once (little chiptune
 * blips, noise bursts, arpeggios) into its own static AudioTrack, so playing one is just a rewind;
 * the vibrations are short waveforms for hits, slams and fanfares.
 */
class HallSfx(private val ctx: Context) {
    enum class S {
        HURT, HURT_BIG, ZAP, THUNDER, STOMP, SPRINT, FIREDASH, IMP_HIT, IMP_DIE, PICKUP, LEGENDARY, EXOTIC,
        LEVEL_UP, FRIEND_JOIN, FRIEND_SKILL, FRIEND_HURT, FRIEND_DOWN, REVIVE, SPELL, HEX, STARFALL, MEND,
        BLINK, BOSS_RUNE, COUNTER, SLAM, PERISH, BURN, ORB, DUEL, SHATTER, FETCH,
    }
    private val rate = 22050
    private val tracks = HashMap<S, AudioTrack>()
    private var ready = false
    var muted = false

    private fun build() {
        if (ready) return
        ready = true
        for (s in S.values()) runCatching {
            val pcm = render(s)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            t.write(pcm, 0, pcm.size)
            tracks[s] = t
        }
    }

    fun play(s: S, vol: Float = 1f) {
        if (muted) return
        runCatching {
            build()
            val t = tracks[s] ?: return
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.setVolume(0.5f * vol)
            t.play()
        }
    }

    fun release() { tracks.values.forEach { runCatching { it.release() } }; tracks.clear(); ready = false }

    // ---- vibration
    private val vib: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }
    /** Timings (ms, off/on alternating, starting with a pause) and their strengths 0..255. */
    fun buzz(timings: LongArray, amps: IntArray) {
        runCatching {
            val v = vib ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            else @Suppress("DEPRECATION") v.vibrate(timings, -1)
        }
    }
    fun rumbleHit() = buzz(longArrayOf(0, 45), intArrayOf(0, 170))
    fun rumbleBig() = buzz(longArrayOf(0, 70, 40, 120), intArrayOf(0, 255, 0, 200))
    fun rumbleSlam() = buzz(longArrayOf(0, 220, 60, 90, 40, 60), intArrayOf(0, 255, 0, 180, 0, 110))
    fun rumbleSoft() = buzz(longArrayOf(0, 25), intArrayOf(0, 90))
    fun rumbleFanfare() = buzz(longArrayOf(0, 30, 50, 30, 50, 70), intArrayOf(0, 120, 0, 160, 0, 220))
    fun rumbleFade() = buzz(longArrayOf(0, 300, 30, 300, 30, 400), intArrayOf(0, 200, 0, 130, 0, 60))

    // ---- synthesis
    private class Buf(n: Int) { val a = FloatArray(n) }
    private fun len(sec: Float) = (sec * rate).toInt()
    private val noiseRnd = kotlin.random.Random(7)

    /** A note: square / triangle / noise, with a pitch glide and an exponential decay. */
    private fun tone(b: Buf, at: Float, dur: Float, f0: Float, f1: Float, wave: Int, vol: Float, decay: Float = 6f, duty: Float = 0.5f) {
        val s0 = len(at); val n = len(dur)
        var ph = 0.0
        var hold = 0f; var holdN = 0
        for (i in 0 until n) {
            val k = s0 + i; if (k >= b.a.size) break
            val t = i / n.toFloat()
            val f = f0 + (f1 - f0) * t
            ph += f / rate
            val p = (ph % 1.0).toFloat()
            val v = when (wave) {
                0 -> if (p < duty) 1f else -1f
                1 -> 1f - 4f * kotlin.math.abs(p - 0.5f)
                2 -> sin(2.0 * PI * ph).toFloat()
                else -> { if (holdN <= 0) { hold = noiseRnd.nextFloat() * 2f - 1f; holdN = maxOf(1, (rate / maxOf(f, 40f)).toInt()) }; holdN--; hold }
            }
            val env = exp(-decay * t) * minOf(1f, i / (rate * 0.003f))
            b.a[k] += v * vol * env
        }
    }

    private fun render(s: S): ShortArray {
        val d = when (s) {
            S.LEGENDARY, S.EXOTIC, S.LEVEL_UP, S.PERISH, S.SLAM, S.STARFALL -> 1.1f
            S.FRIEND_JOIN, S.FRIEND_DOWN, S.REVIVE, S.MEND, S.HEX, S.SPELL, S.DUEL, S.BOSS_RUNE, S.THUNDER -> 0.7f
            else -> 0.35f
        }
        val b = Buf(len(d))
        when (s) {
            S.HURT -> { tone(b, 0f, 0.16f, 220f, 70f, 0, 0.5f, 5f, 0.3f); tone(b, 0f, 0.12f, 900f, 300f, 3, 0.35f, 8f) }
            S.HURT_BIG -> { tone(b, 0f, 0.3f, 160f, 40f, 0, 0.6f, 4f, 0.25f); tone(b, 0f, 0.25f, 1200f, 200f, 3, 0.5f, 6f) }
            S.ZAP -> { tone(b, 0f, 0.14f, 600f, 1800f, 0, 0.3f, 4f, 0.25f); tone(b, 0.02f, 0.12f, 3000f, 2000f, 3, 0.18f, 6f) }
            S.THUNDER -> { tone(b, 0f, 0.6f, 900f, 60f, 3, 0.7f, 3f); tone(b, 0f, 0.4f, 90f, 40f, 0, 0.4f, 3f) }
            S.STOMP -> { tone(b, 0f, 0.28f, 120f, 35f, 1, 0.8f, 4f); tone(b, 0f, 0.12f, 400f, 100f, 3, 0.35f, 9f) }
            S.SPRINT -> tone(b, 0f, 0.25f, 500f, 2400f, 3, 0.25f, 3f)
            S.FIREDASH -> { tone(b, 0f, 0.35f, 300f, 2600f, 3, 0.35f, 2.5f); tone(b, 0.03f, 0.3f, 180f, 90f, 0, 0.25f, 3f, 0.3f) }
            S.IMP_HIT -> tone(b, 0f, 0.07f, 900f, 500f, 0, 0.25f, 6f)
            S.IMP_DIE -> { tone(b, 0f, 0.18f, 700f, 120f, 0, 0.3f, 4f); tone(b, 0.02f, 0.1f, 2000f, 600f, 3, 0.2f, 7f) }
            S.PICKUP -> { tone(b, 0f, 0.08f, 988f, 988f, 0, 0.25f, 3f); tone(b, 0.08f, 0.16f, 1319f, 1319f, 0, 0.25f, 4f) }
            S.LEGENDARY -> { val n = floatArrayOf(523f, 659f, 784f, 1047f, 1319f, 1568f); n.forEachIndexed { i, f -> tone(b, i * 0.08f, 0.5f, f, f, 0, 0.18f, 4f) }; tone(b, 0.5f, 0.6f, 2093f, 2093f, 1, 0.2f, 4f) }
            S.EXOTIC -> { for (i in 0 until 10) { val f = 600f * Math.pow(1.122, (i * 3 % 7).toDouble()).toFloat(); tone(b, i * 0.06f, 0.4f, f, f * 1.01f, 2, 0.16f, 3f) }; tone(b, 0f, 1f, 220f, 330f, 2, 0.15f, 2f) }
            S.LEVEL_UP -> { val n = floatArrayOf(392f, 523f, 659f, 784f, 1047f); n.forEachIndexed { i, f -> tone(b, i * 0.07f, 0.3f, f, f, 0, 0.2f, 4f, 0.25f) }; tone(b, 0.35f, 0.7f, 1047f, 1047f, 1, 0.3f, 3f) }
            S.FRIEND_JOIN -> { floatArrayOf(784f, 988f, 1175f, 1568f).forEachIndexed { i, f -> tone(b, i * 0.09f, 0.25f, f, f, 1, 0.3f, 4f) } }
            S.FRIEND_SKILL -> { tone(b, 0f, 0.4f, 400f, 1600f, 2, 0.3f, 3f); tone(b, 0.1f, 0.3f, 1600f, 1200f, 0, 0.12f, 4f, 0.125f) }
            S.FRIEND_HURT -> tone(b, 0f, 0.12f, 1200f, 600f, 1, 0.3f, 5f)
            S.FRIEND_DOWN -> { floatArrayOf(784f, 659f, 523f, 392f).forEachIndexed { i, f -> tone(b, i * 0.13f, 0.3f, f, f * 0.97f, 1, 0.3f, 4f) } }
            S.REVIVE -> { tone(b, 0f, 0.7f, 300f, 1200f, 2, 0.3f, 2f); floatArrayOf(1047f, 1319f, 1568f).forEachIndexed { i, f -> tone(b, 0.25f + i * 0.08f, 0.3f, f, f, 1, 0.2f, 4f) } }
            S.SPELL -> { tone(b, 0f, 0.6f, 330f, 660f, 2, 0.25f, 2f); tone(b, 0f, 0.6f, 495f, 990f, 2, 0.2f, 2f) }
            S.HEX -> { tone(b, 0f, 0.6f, 330f, 110f, 0, 0.25f, 2f, 0.2f); tone(b, 0f, 0.6f, 349f, 116f, 2, 0.25f, 2f) }
            S.STARFALL -> { for (i in 0 until 6) tone(b, i * 0.1f, 0.25f, 2400f - i * 250f, 300f, 0, 0.15f, 4f, 0.25f); tone(b, 0.55f, 0.5f, 900f, 50f, 3, 0.5f, 3f) }
            S.MEND -> { floatArrayOf(659f, 784f, 988f, 1319f).forEachIndexed { i, f -> tone(b, i * 0.1f, 0.4f, f, f, 2, 0.25f, 3f) } }
            S.BLINK -> tone(b, 0f, 0.25f, 2400f, 300f, 2, 0.3f, 3f)
            S.BOSS_RUNE -> { tone(b, 0f, 0.7f, 110f, 104f, 0, 0.3f, 1.5f, 0.3f); tone(b, 0f, 0.7f, 165f, 156f, 2, 0.3f, 1.5f) }
            S.COUNTER -> { floatArrayOf(523f, 659f, 784f).forEach { f -> tone(b, 0f, 0.35f, f, f, 0, 0.17f, 3f) } }
            S.SLAM -> { tone(b, 0f, 1f, 90f, 25f, 1, 0.9f, 3f); tone(b, 0f, 0.6f, 600f, 60f, 3, 0.6f, 3f) }
            S.PERISH -> { tone(b, 0f, 1.1f, 2000f, 100f, 3, 0.35f, 1.5f); tone(b, 0f, 1.1f, 220f, 55f, 2, 0.35f, 1.5f) }
            S.BURN -> tone(b, 0f, 0.2f, 2500f, 800f, 3, 0.25f, 5f)
            S.ORB -> { tone(b, 0f, 0.3f, 1319f, 1319f, 2, 0.3f, 4f); tone(b, 0.06f, 0.3f, 1976f, 1976f, 2, 0.2f, 4f) }
            S.DUEL -> { tone(b, 0f, 0.7f, 147f, 147f, 0, 0.3f, 1.5f, 0.3f); tone(b, 0.15f, 0.5f, 220f, 220f, 0, 0.2f, 2f, 0.3f); tone(b, 0.3f, 0.4f, 294f, 294f, 0, 0.2f, 2f, 0.3f) }
            S.SHATTER -> { tone(b, 0f, 0.25f, 4000f, 1500f, 3, 0.4f, 5f); tone(b, 0f, 0.15f, 2637f, 2637f, 1, 0.2f, 6f) }
            S.FETCH -> { tone(b, 0f, 0.1f, 1568f, 1760f, 1, 0.25f, 4f); tone(b, 0.09f, 0.12f, 2093f, 2093f, 1, 0.2f, 4f) }
        }
        // soft limiter
        return ShortArray(b.a.size) { (kotlin.math.tanh(b.a[it] * 1.2) * 26000).toInt().toShort() }
    }
}
