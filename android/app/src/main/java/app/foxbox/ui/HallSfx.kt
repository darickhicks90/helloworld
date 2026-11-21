package app.foxbox.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The great hall's sound effects and rumbles. Every sound is synthesised once (little chiptune
 * blips, noise bursts, arpeggios), written as a small WAV in the cache and loaded into a SoundPool
 * (a few voices at most, so the music engine keeps its own); the vibrations are short waveforms
 * for hits, slams and fanfares.
 */
class HallSfx(private val ctx: Context) {
    enum class S {
        HURT, HURT_BIG, ZAP, THUNDER, STOMP, SPRINT, FIREDASH, IMP_HIT, IMP_DIE, PICKUP, LEGENDARY, EXOTIC,
        LEVEL_UP, FRIEND_JOIN, FRIEND_SKILL, FRIEND_HURT, FRIEND_DOWN, REVIVE, SPELL, HEX, STARFALL, MEND,
        BLINK, BOSS_RUNE, COUNTER, SLAM, PERISH, BURN, ORB, DUEL, SHATTER, FETCH,
        DOOR_UNLOCK, DOOR_GRIND, DOOR_OPEN,
    }
    private val rate = 22050
    @Volatile private var pool: SoundPool? = null
    private val ids = HashMap<S, Int>()
    private val loaded = HashSet<Int>()
    var muted = false

    private fun build(): SoundPool {
        pool?.let { return it }
        val sp = SoundPool.Builder().setMaxStreams(6)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        pool = sp
        sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        // render and load off the main thread
        Thread {
            val dir = java.io.File(ctx.cacheDir, "hall_sfx").apply { mkdirs() }
            for (s in S.values()) runCatching {
                if (pool !== sp) return@Thread
                val f = java.io.File(dir, "v2_" + s.name.lowercase() + ".wav")
                if (!f.exists() || f.length() < 64) writeWav(f, render(s))
                val id = sp.load(f.path, 1)
                synchronized(ids) { if (pool === sp) ids[s] = id }
            }
        }.apply { isDaemon = true }.start()
        return sp
    }

    fun play(s: S, vol: Float = 1f) {
        if (muted) return
        runCatching {
            val sp = build()
            val id = synchronized(ids) { ids[s] } ?: return
            if (!synchronized(loaded) { id in loaded }) return
            val v = 0.6f * vol
            sp.play(id, v, v, if (s == S.LEGENDARY || s == S.EXOTIC || s == S.LEVEL_UP || s == S.SLAM) 2 else 1, 0, 1f)
        }
    }

    /** Warm up (render and load) before the first sound is needed. */
    fun prepare() { runCatching { build() } }

    fun release() { runCatching { pool?.release() }; pool = null; synchronized(ids) { ids.clear() }; synchronized(loaded) { loaded.clear() } }

    private fun writeWav(f: java.io.File, pcm: ShortArray) {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        for (v in pcm) b.putShort(v)
        f.writeBytes(b.array())
    }

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
            S.DOOR_GRIND -> 2.0f
            S.DOOR_OPEN -> 1.6f
            S.LEGENDARY, S.EXOTIC, S.LEVEL_UP, S.PERISH, S.SLAM, S.STARFALL -> 1.1f
            S.DOOR_UNLOCK -> 0.7f
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
            S.DOOR_UNLOCK -> {
                // a heavy bolt drawn in stone: a crack, a thunk, a ring of the rune
                tone(b, 0f, 0.05f, 3000f, 800f, 3, 0.7f, 9f); tone(b, 0.02f, 0.3f, 110f, 45f, 0, 0.7f, 5f, 0.3f)
                tone(b, 0.1f, 0.12f, 2200f, 400f, 3, 0.4f, 8f); tone(b, 0.12f, 0.5f, 880f, 870f, 2, 0.18f, 3f); tone(b, 0.12f, 0.5f, 1318f, 1300f, 2, 0.1f, 3f)
            }
            S.DOOR_GRIND -> {
                // two slabs dragged over stone: a low rough grind with grit cracking in it
                tone(b, 0f, 2f, 70f, 52f, 3, 0.55f, 0.6f); tone(b, 0f, 2f, 41f, 37f, 0, 0.35f, 0.5f, 0.2f); tone(b, 0f, 2f, 300f, 220f, 3, 0.18f, 0.8f)
                val r = kotlin.random.Random(11); repeat(26) { tone(b, r.nextFloat() * 1.9f, 0.04f, 2000f + r.nextFloat() * 2000f, 600f, 3, 0.25f + r.nextFloat() * 0.2f, 10f) }
            }
            S.DOOR_OPEN -> {
                // the slabs boom into their frame; the starlight rings out behind them
                tone(b, 0f, 0.9f, 80f, 28f, 1, 0.9f, 3f); tone(b, 0f, 0.5f, 900f, 80f, 3, 0.6f, 4f)
                floatArrayOf(523f, 784f, 1047f, 1319f, 1568f, 2093f, 2637f).forEachIndexed { i, f -> tone(b, 0.18f + i * 0.09f, 0.9f, f, f * 1.003f, 2, 0.13f, 2.5f) }
                tone(b, 0.2f, 1.3f, 196f, 196f, 2, 0.18f, 1.5f)
            }
        }
        // grit: every sound goes through some dirt (hard hits the most, chimes the least)
        val dirt = when (s) {
            S.HURT, S.HURT_BIG, S.STOMP, S.SLAM, S.IMP_HIT, S.IMP_DIE, S.THUNDER, S.SHATTER, S.BURN, S.FIREDASH, S.DOOR_GRIND, S.DOOR_UNLOCK, S.PERISH, S.COUNTER, S.FRIEND_HURT, S.ZAP -> 1f
            S.LEGENDARY, S.EXOTIC, S.LEVEL_UP, S.MEND, S.ORB, S.PICKUP, S.FETCH, S.FRIEND_JOIN, S.REVIVE -> 0.35f
            else -> 0.65f
        }
        grit(b.a, dirt)
        // limiter
        return ShortArray(b.a.size) { (kotlin.math.tanh(b.a[it] * 1.3) * 26500).toInt().toShort() }
    }
    /**
     * Dirt: an uneven (tube-like) overdrive, a crunch of lowered sample rate and bit depth mixed
     * in, and a grain of noise that rides the sound's own loudness.
     */
    private fun grit(a: FloatArray, k: Float) {
        val r = kotlin.random.Random(3)
        val hold = if (k > 0.8f) 3 else 2
        val levels = if (k > 0.8f) 24f else 48f
        var held = 0f; var env = 0f; var lp = 0f
        for (i in a.indices) {
            val x = a[i]
            env = maxOf(kotlin.math.abs(x), env * 0.9985f)
            val drive = 1f + 2.2f * k
            val sat = kotlin.math.tanh((x * drive + 0.25f * k * x * x).toDouble()).toFloat()
            if (i % hold == 0) held = kotlin.math.round(sat * levels) / levels
            val n = r.nextFloat() * 2f - 1f; lp += (n - lp) * 0.35f
            a[i] = sat * (1f - 0.45f * k) + held * (0.45f * k) + lp * env * 0.22f * k
        }
    }
}
