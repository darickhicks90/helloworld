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
 * The great hall's sound effects and rumbles, dark fantasy with a streak of horror: struck
 * bells a little out of true, whispers, growls, choirs sliding off key, all in a stone room's
 * echo, with grit on the hard hits. Every sound is synthesised once, written as a small WAV in the cache and loaded into a SoundPool
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
                val f = java.io.File(dir, "v3_" + s.name.lowercase() + ".wav")
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

    /** A swell: rises and falls (a whoosh, a breath, a choir). */
    private fun swell(b: Buf, at: Float, dur: Float, f0: Float, f1: Float, wave: Int, vol: Float, peak: Float = 0.5f) {
        val s0 = len(at); val n = len(dur); var ph = 0.0; var hold = 0f; var holdN = 0; var lp = 0f
        for (i in 0 until n) {
            val k = s0 + i; if (k >= b.a.size) break
            val t = i / n.toFloat(); val f = f0 + (f1 - f0) * t
            ph += f / rate
            val v = when (wave) {
                2 -> sin(2.0 * PI * ph).toFloat()
                1 -> { val p = (ph % 1.0).toFloat(); 1f - 4f * kotlin.math.abs(p - 0.5f) }
                else -> { if (holdN <= 0) { hold = noiseRnd.nextFloat() * 2f - 1f; holdN = maxOf(1, (rate / maxOf(f, 40f)).toInt()) }; holdN--; lp += (hold - lp) * 0.3f; lp }
            }
            val env = if (t < peak) t / peak else (1f - t) / (1f - peak)
            b.a[k] += v * vol * env * env
        }
    }
    /** A bell: inharmonic partials (struck metal, a little out of true), each dying at its own pace. */
    private fun bell(b: Buf, at: Float, f: Float, vol: Float, dur: Float = 1.2f) {
        val ratios = floatArrayOf(1f, 2.02f, 2.76f, 4.07f, 5.43f, 8.93f); val vols = floatArrayOf(1f, 0.55f, 0.45f, 0.25f, 0.2f, 0.1f)
        for (i in ratios.indices) tone(b, at, dur, f * ratios[i], f * ratios[i] * 0.998f, 2, vol * vols[i], 3f + i * 1.6f)
    }
    /** A growl: a low buzzing throat, its loudness fluttering. */
    private fun growl(b: Buf, at: Float, dur: Float, f0: Float, f1: Float, vol: Float) {
        val s0 = len(at); val n = len(dur); var ph = 0.0; var flut = 0f
        for (i in 0 until n) {
            val k = s0 + i; if (k >= b.a.size) break
            val t = i / n.toFloat(); val f = f0 + (f1 - f0) * t
            ph += f / rate
            var v = 0f; for (h in 1..7) v += (sin(2.0 * PI * ph * h).toFloat()) / h
            if (i % 300 == 0) flut = 0.5f + noiseRnd.nextFloat() * 0.5f
            val env = minOf(1f, t * 8f) * (1f - t)
            b.a[k] += v * vol * env * flut
        }
    }
    /** A whisper: breathy noise, its colour sliding. */
    private fun whisper(b: Buf, at: Float, dur: Float, vol: Float) {
        val s0 = len(at); val n = len(dur); var lp = 0f; var lp2 = 0f
        for (i in 0 until n) {
            val k = s0 + i; if (k >= b.a.size) break
            val t = i / n.toFloat(); val x = noiseRnd.nextFloat() * 2f - 1f
            val c = 0.15f + 0.35f * (0.5f + 0.5f * sin(t * 9f + sin(t * 23f)))
            lp += (x - lp) * c; lp2 += (lp - lp2) * c
            val env = sin(PI * t).toFloat() * (0.6f + 0.4f * sin(t * 31f))
            b.a[k] += (lp - lp2) * vol * 2.5f * env
        }
    }
    /** A stone room: four combs and two all-passes (old-fashioned reverb). */
    private fun reverb(a: FloatArray, mix: Float) {
        if (mix <= 0f) return
        val out = FloatArray(a.size)
        for ((d, g) in listOf(557 to 0.8f, 593 to 0.79f, 641 to 0.78f, 677 to 0.77f)) {
            val buf = FloatArray(d); var idx = 0; var lp = 0f
            for (i in a.indices) { val y = buf[idx]; lp += (y - lp) * 0.45f; buf[idx] = a[i] + lp * g; idx = (idx + 1) % d; out[i] += y * 0.25f }
        }
        for (d in intArrayOf(113, 37)) {
            val buf = FloatArray(d); var idx = 0
            for (i in out.indices) { val bv = buf[idx]; val y = -out[i] * 0.6f + bv; buf[idx] = out[i] + bv * 0.6f; idx = (idx + 1) % d; out[i] = y }
        }
        for (i in a.indices) a[i] = a[i] * (1f - mix * 0.4f) + out[i] * mix
    }

    private fun render(s: S): ShortArray {
        val d = when (s) {
            S.DOOR_GRIND -> 2.4f
            S.DOOR_OPEN, S.LEGENDARY, S.EXOTIC, S.PERISH, S.SLAM, S.BOSS_RUNE -> 2.0f
            S.LEVEL_UP, S.STARFALL, S.REVIVE, S.FRIEND_DOWN, S.FRIEND_JOIN, S.HEX, S.SPELL, S.DUEL, S.THUNDER, S.MEND -> 1.4f
            S.DOOR_UNLOCK, S.COUNTER, S.SHATTER, S.ORB, S.PICKUP, S.FRIEND_SKILL, S.BLINK, S.IMP_DIE, S.HURT_BIG -> 0.9f
            else -> 0.55f
        }
        val b = Buf(len(d))
        when (s) {
            // you, hurt: a thud in the body, a breath knocked out
            S.HURT -> { tone(b, 0f, 0.22f, 150f, 55f, 2, 0.7f, 6f); tone(b, 0f, 0.08f, 1400f, 300f, 3, 0.35f, 10f); swell(b, 0.02f, 0.25f, 600f, 300f, 3, 0.25f, 0.2f) }
            S.HURT_BIG -> { tone(b, 0f, 0.45f, 110f, 35f, 2, 0.9f, 4f); tone(b, 0f, 0.15f, 1800f, 200f, 3, 0.5f, 8f); tone(b, 0f, 0.5f, 233f, 220f, 2, 0.25f, 3f); tone(b, 0f, 0.5f, 247f, 233f, 2, 0.25f, 3f) }
            // the spark: an eldritch crackle with a sour fifth under it
            S.ZAP -> { tone(b, 0f, 0.16f, 300f, 950f, 2, 0.35f, 4f); tone(b, 0f, 0.16f, 424f, 1340f, 2, 0.2f, 4f); tone(b, 0f, 0.12f, 5000f, 2500f, 3, 0.22f, 7f) }
            S.THUNDER -> { tone(b, 0f, 0.08f, 4000f, 1500f, 3, 0.8f, 10f); tone(b, 0.03f, 1.3f, 300f, 40f, 3, 0.8f, 2.2f); tone(b, 0.03f, 1.2f, 55f, 32f, 2, 0.6f, 2f) }
            S.STOMP -> { tone(b, 0f, 0.45f, 75f, 28f, 2, 1f, 4f); tone(b, 0f, 0.2f, 500f, 120f, 3, 0.45f, 8f) }
            S.SPRINT -> swell(b, 0f, 0.4f, 900f, 2400f, 3, 0.4f, 0.35f)
            S.FIREDASH -> { swell(b, 0f, 0.5f, 400f, 2200f, 3, 0.5f, 0.3f); repeat(8) { tone(b, it * 0.05f, 0.03f, 3000f, 1500f, 3, 0.25f, 10f) } }
            // imps: a wet thwack and a squeal; dying, a screech that drowns in a gurgle
            S.IMP_HIT -> { tone(b, 0f, 0.08f, 400f, 120f, 2, 0.5f, 7f); tone(b, 0f, 0.05f, 2500f, 900f, 3, 0.3f, 9f); tone(b, 0.01f, 0.1f, 1500f, 1900f, 1, 0.12f, 6f) }
            S.IMP_DIE -> { tone(b, 0f, 0.35f, 1800f, 500f, 1, 0.25f, 3f); tone(b, 0f, 0.35f, 1900f, 470f, 1, 0.2f, 3f); growl(b, 0.15f, 0.5f, 90f, 45f, 0.25f) }
            S.PICKUP -> bell(b, 0f, 1175f, 0.22f, 0.8f)
            // treasures: a dark choir swelling up, bells over it
            S.LEGENDARY -> { for ((i, f) in floatArrayOf(220f, 261.6f, 329.6f, 440f).withIndex()) { swell(b, 0f, 1.8f, f, f * 1.003f, 2, 0.14f, 0.35f); swell(b, 0.05f, 1.75f, f * 1.006f, f * 1.009f, 1, 0.06f, 0.35f) }; floatArrayOf(880f, 1047f, 1319f, 1760f).forEachIndexed { i, f -> bell(b, 0.3f + i * 0.16f, f, 0.12f) } }
            S.EXOTIC -> { swell(b, 0f, 1.9f, 55f, 55f, 2, 0.35f, 0.4f); swell(b, 0f, 1.9f, 77.8f, 77.8f, 2, 0.2f, 0.4f); floatArrayOf(622f, 698f, 784f, 880f, 988f, 1109f).forEachIndexed { i, f -> bell(b, i * 0.14f, f, 0.1f) }; whisper(b, 0.2f, 1.4f, 0.2f) }
            S.LEVEL_UP -> { floatArrayOf(440f, 523f, 659f, 880f).forEachIndexed { i, f -> bell(b, i * 0.12f, f, 0.15f, 1f) }; swell(b, 0.3f, 1f, 220f, 220f, 2, 0.2f, 0.3f) }
            // companions: warm low horns
            S.FRIEND_JOIN -> { floatArrayOf(196f, 294f, 392f).forEachIndexed { i, f -> swell(b, i * 0.15f, 1f, f, f, 1, 0.2f, 0.25f) }; bell(b, 0.4f, 784f, 0.1f) }
            S.FRIEND_SKILL -> { swell(b, 0f, 0.7f, 200f, 800f, 2, 0.3f, 0.6f); swell(b, 0f, 0.7f, 300f, 1200f, 2, 0.15f, 0.6f); whisper(b, 0f, 0.6f, 0.2f) }
            S.FRIEND_HURT -> { tone(b, 0f, 0.15f, 900f, 450f, 1, 0.3f, 5f); tone(b, 0f, 0.1f, 200f, 80f, 2, 0.4f, 6f) }
            S.FRIEND_DOWN -> { floatArrayOf(392f, 349f, 311f, 262f).forEachIndexed { i, f -> bell(b, i * 0.22f, f, 0.15f, 0.9f) }; swell(b, 0.2f, 1.1f, 98f, 92f, 2, 0.2f, 0.3f) }
            S.REVIVE -> { swell(b, 0f, 1.3f, 147f, 294f, 2, 0.3f, 0.7f); floatArrayOf(587f, 698f, 880f).forEachIndexed { i, f -> bell(b, 0.5f + i * 0.12f, f, 0.12f) } }
            // spells: a whisper and a cold bell; hexes: a tritone drone that won't resolve
            S.SPELL -> { whisper(b, 0f, 1f, 0.35f); bell(b, 0.05f, 659f, 0.12f); swell(b, 0f, 1.1f, 165f, 175f, 2, 0.2f, 0.5f) }
            S.HEX -> { swell(b, 0f, 1.3f, 110f, 104f, 2, 0.35f, 0.3f); swell(b, 0f, 1.3f, 155.6f, 147f, 2, 0.3f, 0.3f); whisper(b, 0.1f, 1.1f, 0.3f) }
            S.STARFALL -> { for (i in 0 until 6) bell(b, i * 0.1f, 1760f - i * 180f, 0.08f, 0.6f); tone(b, 0.65f, 0.7f, 700f, 40f, 3, 0.6f, 3f); tone(b, 0.65f, 0.6f, 70f, 30f, 2, 0.6f, 3f) }
            S.MEND -> { floatArrayOf(440f, 523f, 659f, 784f).forEachIndexed { i, f -> tone(b, i * 0.09f, 0.7f, f, f, 1, 0.2f, 5f) }; swell(b, 0f, 1f, 220f, 220f, 2, 0.12f, 0.3f) }
            S.BLINK -> { swell(b, 0f, 0.5f, 3000f, 400f, 3, 0.35f, 0.8f); tone(b, 0.38f, 0.3f, 1200f, 300f, 2, 0.2f, 5f) }
            // the boss's rune: a throat far too deep, and a choir sliding out of tune
            S.BOSS_RUNE -> { growl(b, 0f, 1.6f, 48f, 41f, 0.45f); swell(b, 0f, 1.8f, 220f, 207f, 2, 0.18f, 0.4f); swell(b, 0f, 1.8f, 233f, 246f, 2, 0.18f, 0.4f); whisper(b, 0.2f, 1.4f, 0.25f) }
            S.COUNTER -> { bell(b, 0f, 330f, 0.3f, 0.9f); tone(b, 0f, 0.06f, 3000f, 800f, 3, 0.4f, 10f) }
            S.SLAM -> { tone(b, 0f, 1.6f, 70f, 22f, 2, 1f, 2.5f); tone(b, 0f, 0.8f, 700f, 50f, 3, 0.7f, 3f); growl(b, 0f, 1.2f, 40f, 30f, 0.25f) }
            // something dies for good: a falling wail and the dark closing over it
            S.PERISH -> { swell(b, 0f, 1.8f, 880f, 196f, 2, 0.25f, 0.2f); swell(b, 0f, 1.8f, 932f, 207f, 2, 0.2f, 0.2f); tone(b, 0f, 1.8f, 2000f, 100f, 3, 0.3f, 1.5f); growl(b, 0.4f, 1.4f, 55f, 35f, 0.2f) }
            S.BURN -> { repeat(10) { tone(b, it * 0.04f, 0.03f, 3500f, 1200f, 3, 0.25f, 10f) }; swell(b, 0f, 0.45f, 300f, 200f, 3, 0.3f, 0.3f) }
            S.ORB -> { bell(b, 0f, 1568f, 0.15f, 0.8f); bell(b, 0.08f, 2349f, 0.08f, 0.7f) }
            S.DUEL -> { for (i in 0 until 3) tone(b, i * 0.22f, 0.3f, 80f, 45f, 2, 0.8f, 5f); swell(b, 0f, 1.3f, 98f, 98f, 1, 0.25f, 0.3f); swell(b, 0f, 1.3f, 147f, 147f, 1, 0.18f, 0.3f) }
            S.SHATTER -> { tone(b, 0f, 0.4f, 5000f, 2000f, 3, 0.45f, 5f); for (i in 0 until 5) bell(b, i * 0.04f, 2000f + i * 370f, 0.06f, 0.4f) }
            S.FETCH -> bell(b, 0f, 1397f, 0.15f, 0.5f)
            S.DOOR_UNLOCK -> {
                tone(b, 0f, 0.05f, 3000f, 800f, 3, 0.7f, 9f); tone(b, 0.02f, 0.35f, 90f, 40f, 2, 0.8f, 5f)
                tone(b, 0.1f, 0.12f, 2200f, 400f, 3, 0.4f, 8f); bell(b, 0.12f, 440f, 0.2f); whisper(b, 0.15f, 0.6f, 0.2f)
            }
            S.DOOR_GRIND -> {
                tone(b, 0f, 2.2f, 70f, 52f, 3, 0.55f, 0.6f); growl(b, 0f, 2.2f, 41f, 37f, 0.3f); tone(b, 0f, 2.2f, 300f, 220f, 3, 0.18f, 0.8f)
                val r = kotlin.random.Random(11); repeat(26) { tone(b, r.nextFloat() * 2f, 0.04f, 2000f + r.nextFloat() * 2000f, 600f, 3, 0.25f + r.nextFloat() * 0.2f, 10f) }
            }
            S.DOOR_OPEN -> {
                tone(b, 0f, 1.2f, 70f, 24f, 2, 1f, 3f); tone(b, 0f, 0.5f, 900f, 80f, 3, 0.6f, 4f)
                floatArrayOf(440f, 523f, 659f, 880f, 1047f, 1319f, 1760f).forEachIndexed { i, f -> bell(b, 0.2f + i * 0.1f, f, 0.08f, 1.2f) }
                for (f in floatArrayOf(110f, 165f, 220f)) swell(b, 0.2f, 1.7f, f, f, 2, 0.15f, 0.4f)
            }
        }
        // grit: every sound goes through some dirt (hard hits the most, bells and choirs the least)
        val dirt = when (s) {
            S.HURT, S.HURT_BIG, S.STOMP, S.SLAM, S.IMP_HIT, S.IMP_DIE, S.THUNDER, S.SHATTER, S.BURN, S.FIREDASH, S.DOOR_GRIND, S.DOOR_UNLOCK, S.PERISH, S.FRIEND_HURT, S.ZAP, S.BOSS_RUNE -> 0.8f
            S.LEGENDARY, S.EXOTIC, S.LEVEL_UP, S.MEND, S.ORB, S.PICKUP, S.FETCH, S.FRIEND_JOIN, S.REVIVE, S.DOOR_OPEN -> 0.2f
            else -> 0.45f
        }
        grit(b.a, dirt)
        // a stone hall round every sound; more round the big and the strange
        reverb(b.a, when (s) { S.IMP_HIT, S.ZAP, S.FETCH, S.PICKUP -> 0.18f; S.HURT, S.FRIEND_HURT, S.SPRINT, S.FIREDASH, S.BURN -> 0.25f; else -> 0.45f })
        // fade the very end so nothing clicks off
        val fade = minOf(b.a.size, len(0.05f)); for (i in 0 until fade) b.a[b.a.size - 1 - i] *= i / fade.toFloat()
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
