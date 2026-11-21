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
 * The great hall's sound effects and rumbles: crackling electricity for the spark, cracks for
 * hits, knocks when you're hit, glitter for spells, a choir for consecration, breath and twinkle
 * for healing, plucked strings and soft pads in a dark minor key, crisp air for every swing and dash, real weight under the blows, all in a big
 * stone room's echo. Every sound is synthesised once, written as a small WAV in the cache and loaded into a SoundPool
 * (a few voices at most, so the music engine keeps its own); the vibrations are short waveforms
 * for hits, slams and fanfares.
 */
class HallSfx(private val ctx: Context) {
    enum class S {
        HURT, HURT_BIG, ZAP, THUNDER, STOMP, SPRINT, FIREDASH, IMP_HIT, IMP_DIE, PICKUP, LEGENDARY, EXOTIC,
        LEVEL_UP, FRIEND_JOIN, FRIEND_SKILL, FRIEND_HURT, FRIEND_DOWN, REVIVE, SPELL, HEX, STARFALL, MEND,
        BLINK, BOSS_RUNE, COUNTER, SLAM, PERISH, BURN, ORB, DUEL, SHATTER, FETCH,
        DOOR_UNLOCK, DOOR_GRIND, DOOR_OPEN, WORSHIP,
    }
    private val rate = 44100
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
                val f = java.io.File(dir, "v5_" + s.name.lowercase() + ".wav")
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

    // ---- synthesis: plucked strings, struck glass and metal, soft pads, air, and weight
    private class Buf(n: Int) { val a = FloatArray(n) }
    private fun len(sec: Float) = (sec * rate).toInt()
    private val noiseRnd = kotlin.random.Random(7)

    /** A plucked string (Karplus-Strong): a harp, a kalimba, a koto, depending on [bright]. */
    private fun pluck(b: Buf, at: Float, f: Float, vol: Float, dur: Float = 1.2f, bright: Float = 0.5f) {
        val n = maxOf(2, (rate / f).toInt()); val line = FloatArray(n)
        var lp = 0f
        for (i in 0 until n) { val x = noiseRnd.nextFloat() * 2f - 1f; lp += (x - lp) * (0.25f + bright * 0.6f); line[i] = lp }
        val s0 = len(at); val total = len(dur); var idx = 0
        val damp = 0.996f - (1f - bright) * 0.006f
        for (i in 0 until total) {
            val k = s0 + i; if (k >= b.a.size) break
            val cur = line[idx]; val nxt = line[(idx + 1) % n]
            line[idx] = (cur + nxt) * 0.5f * damp
            idx = (idx + 1) % n
            val env = minOf(1f, i / (rate * 0.002f))
            b.a[k] += cur * vol * env
        }
    }
    /** A soft pad: three voices a hair apart, slow to come and go. */
    private fun pad(b: Buf, at: Float, dur: Float, f: Float, vol: Float, f1: Float = f) {
        val s0 = len(at); val n = len(dur)
        var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
        for (i in 0 until n) {
            val k = s0 + i; if (k >= b.a.size) break
            val t = i / n.toFloat(); val ff = f + (f1 - f) * t
            p1 += ff / rate; p2 += ff * 1.004 / rate; p3 += ff * 0.997 / rate
            val v = (sin(2 * PI * p1) + sin(2 * PI * p2) * 0.7 + sin(2 * PI * p3 * 2) * 0.25).toFloat()
            val env = sin(PI * t).toFloat().let { it * it }
            b.a[k] += v * vol * env * 0.5f
        }
    }
    /** Air: noise through a resonant band-pass that sweeps (a swish, a whoosh, a slash). */
    private fun swish(b: Buf, at: Float, dur: Float, f0: Float, f1: Float, vol: Float, attack: Float = 0.15f, q: Float = 0.6f) {
        val s0 = len(at); val n = len(dur)
        var low = 0f; var band = 0f
        for (i in 0 until n) {
            val k = s0 + i; if (k >= b.a.size) break
            val t = i / n.toFloat()
            val fc = f0 * Math.pow((f1 / f0).toDouble(), t.toDouble()).toFloat()
            val fq = (2 * sin(PI * minOf(fc, rate * 0.24f) / rate)).toFloat()
            val x = noiseRnd.nextFloat() * 2f - 1f
            low += fq * band; val high = x - low - q * band; band += fq * high
            val env = if (t < attack) t / attack else exp(-(t - attack) * 5f / (1f - attack))
            b.a[k] += band * vol * env
        }
    }
    /** Weight: a falling sine with a click on its front (a footfall, a blow, a boom). */
    private fun thud(b: Buf, at: Float, f0: Float, f1: Float, dur: Float, vol: Float) {
        val s0 = len(at); val n = len(dur); var ph = 0.0
        for (i in 0 until n) {
            val k = s0 + i; if (k >= b.a.size) break
            val t = i / n.toFloat(); val f = f1 + (f0 - f1) * exp(-t * 6f)
            ph += f / rate
            val env = exp(-t * 4f) * minOf(1f, i / (rate * 0.0015f))
            b.a[k] += (sin(2 * PI * ph).toFloat()) * vol * env
            if (i < len(0.004f)) b.a[k] += (noiseRnd.nextFloat() * 2f - 1f) * vol * 0.5f * (1f - i / len(0.004f).toFloat())
        }
    }
    /** A note (kept for the odd pure tone). */
    private fun tone(b: Buf, at: Float, dur: Float, f0: Float, f1: Float, vol: Float, decay: Float = 6f) {
        val s0 = len(at); val n = len(dur); var ph = 0.0
        for (i in 0 until n) { val k = s0 + i; if (k >= b.a.size) break; val t = i / n.toFloat(); ph += (f0 + (f1 - f0) * t) / rate
            b.a[k] += sin(2 * PI * ph).toFloat() * vol * exp(-decay * t) * minOf(1f, i / (rate * 0.003f)) }
    }
    /** A great stone room (Freeverb-style: eight damped combs, four all-passes). */
    private fun reverb(a: FloatArray, mix: Float, size: Float = 0.84f) {
        if (mix <= 0f) return
        val out = FloatArray(a.size)
        for (d in intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)) {
            val buf = FloatArray(d); var idx = 0; var lp = 0f
            for (i in a.indices) { val y = buf[idx]; lp = y * 0.6f + lp * 0.4f; buf[idx] = a[i] + lp * size; idx = (idx + 1) % d; out[i] += y * 0.125f }
        }
        for (d in intArrayOf(556, 441, 341, 225)) {
            val buf = FloatArray(d); var idx = 0
            for (i in out.indices) { val bv = buf[idx]; val y = -out[i] + bv; buf[idx] = out[i] + bv * 0.5f; idx = (idx + 1) % d; out[i] = y }
        }
        for (i in a.indices) a[i] = a[i] * (1f - mix * 0.3f) + out[i] * mix
    }

    /** Electricity: a dense crackle of tiny sparks, with a thin buzz under it. */
    private fun crackle(b: Buf, at: Float, dur: Float, vol: Float, density: Float = 0.25f) {
        val s0 = len(at); val n = len(dur); var spark = 0f; var ph = 0.0; var hp = 0f; var prev = 0f
        for (i in 0 until n) {
            val k = s0 + i; if (k >= b.a.size) break
            val t = i / n.toFloat()
            if (noiseRnd.nextFloat() < density * (1f - t * 0.6f) * 0.02f) spark = 0.6f + noiseRnd.nextFloat() * 0.6f
            spark *= 0.93f
            val x = (noiseRnd.nextFloat() * 2f - 1f) * spark
            hp = x - prev + hp * 0.3f; prev = x     // keep it bright: only the hiss of each spark
            ph += 180.0 / rate
            val buzz = (if ((ph % 1.0) < 0.5) 1f else -1f) * 0.08f * (1f - t)
            b.a[k] += (hp + buzz * (0.5f + 0.5f * spark)) * vol * minOf(1f, (1f - t) * 4f)
        }
    }
    /** A crack: a split-second snap of high noise over a short knock of weight. */
    private fun crack(b: Buf, at: Float, vol: Float, big: Boolean = false) {
        swish(b, at, if (big) 0.09f else 0.05f, 7000f, 2500f, vol, 0.02f, 1.6f)
        thud(b, at, if (big) 260f else 360f, 120f, if (big) 0.18f else 0.08f, vol * 0.55f)
    }
    /** A knock: wood struck hard (two of its modes) and a dull thump. */
    private fun knock(b: Buf, at: Float, f: Float, vol: Float) {
        val s0 = len(at); val n = len(0.22f)
        for (i in 0 until n) { val k = s0 + i; if (k >= b.a.size) break; val t = i / rate.toFloat()
            b.a[k] += ((sin(2 * PI * f * t) * exp(-t * 28f) + sin(2 * PI * f * 2.62 * t) * 0.45 * exp(-t * 45f)).toFloat()) * vol * minOf(1f, i / (rate * 0.0008f)) }
        swish(b, at, 0.03f, 2200f, 900f, vol * 0.4f, 0.05f, 1.2f)
    }
    /** Glitter: a shower of tiny bright pinpricks (not bells: each a blip, gone at once). */
    private fun glitter(b: Buf, at: Float, dur: Float, count: Int, vol: Float, lo: Float = 2500f, hi: Float = 7000f, fall: Boolean = false) {
        for (j in 0 until count) {
            val t0 = at + noiseRnd.nextFloat() * dur
            val u = (t0 - at) / dur
            val f = if (fall) hi - (hi - lo) * u + noiseRnd.nextFloat() * 400f else lo + noiseRnd.nextFloat() * (hi - lo)
            val s0 = len(t0); val n = len(0.035f + noiseRnd.nextFloat() * 0.03f)
            for (i in 0 until n) { val k = s0 + i; if (k >= b.a.size) break; val t = i / n.toFloat()
                b.a[k] += (sin(2 * PI * f * i / rate).toFloat()) * vol * (1f - t) * (1f - t) * minOf(1f, i / (rate * 0.001f)) }
        }
    }
    /** A breath: soft air, in and out. */
    private fun breath(b: Buf, at: Float, dur: Float, vol: Float) = swish(b, at, dur, 700f, 1800f, vol, 0.45f, 0.25f)
    /** A choir: voices on "aah" (bright buzz through the vowel's formants), swelling and fading. */
    private fun choir(b: Buf, at: Float, dur: Float, notes: FloatArray, vol: Float) {
        val s0 = len(at); val n = len(dur)
        val forms = floatArrayOf(730f, 1090f, 2440f); val gains = floatArrayOf(1f, 0.5f, 0.25f)
        val low = FloatArray(3); val band = FloatArray(3)
        val ph = DoubleArray(notes.size * 2)
        for (i in 0 until n) {
            val k = s0 + i; if (k >= b.a.size) break
            val t = i / n.toFloat()
            var src = 0f
            for ((j, f) in notes.withIndex()) for (v in 0..1) {
                val vib = 1.0 + 0.006 * sin(2 * PI * (5.2 + v * 0.7) * i / rate)
                val idx = j * 2 + v; ph[idx] += f * (if (v == 0) 1.0 else 1.005) * vib / rate
                src += ((ph[idx] % 1.0) * 2.0 - 1.0).toFloat()
            }
            src /= notes.size
            var out = 0f
            for (q in 0..2) { val fq = (2 * sin(PI * forms[q] / rate)).toFloat(); low[q] += fq * band[q]; val high = src - low[q] - 0.25f * band[q]; band[q] += fq * high; out += band[q] * gains[q] }
            val env = sin(PI * t).toFloat()
            b.a[k] += out * vol * env * 0.35f
        }
    }

    // D minor, mostly
    private val D3 = 146.8f; private val F3 = 174.6f; private val A3 = 220f; private val D4 = 293.7f; private val E4 = 329.6f; private val F4 = 349.2f
    private val G4 = 392f; private val A4 = 440f; private val Bb4 = 466.2f; private val C5 = 523.3f; private val D5 = 587.3f; private val E5 = 659.3f
    private val F5 = 698.5f; private val G5 = 784f; private val A5 = 880f; private val C6 = 1046.5f; private val D6 = 1174.7f; private val A6 = 1760f

    private fun render(s: S): ShortArray {
        val d = when (s) {
            S.DOOR_GRIND -> 2.8f
            S.DOOR_OPEN, S.LEGENDARY, S.EXOTIC, S.PERISH, S.SLAM, S.BOSS_RUNE, S.REVIVE, S.WORSHIP -> 2.6f
            S.LEVEL_UP, S.STARFALL, S.FRIEND_DOWN, S.FRIEND_JOIN, S.HEX, S.SPELL, S.DUEL, S.THUNDER, S.MEND -> 1.9f
            S.DOOR_UNLOCK, S.COUNTER, S.SHATTER, S.ORB, S.PICKUP, S.FRIEND_SKILL, S.BLINK, S.IMP_DIE, S.HURT_BIG, S.FETCH -> 1.3f
            else -> 0.8f
        }
        val b = Buf(len(d))
        when (s) {
            // getting hit: a hard knock (and the view shakes)
            S.HURT -> { knock(b, 0f, 170f, 0.9f); thud(b, 0f, 120f, 55f, 0.2f, 0.6f) }
            S.HURT_BIG -> { knock(b, 0f, 130f, 1f); knock(b, 0.07f, 110f, 0.7f); thud(b, 0f, 90f, 35f, 0.45f, 0.9f) }
            // the spark: electricity crackling
            S.ZAP -> crackle(b, 0f, 0.2f, 0.9f, 0.45f)
            S.THUNDER -> { crack(b, 0f, 1f, true); crackle(b, 0f, 0.6f, 0.7f, 0.5f); swish(b, 0.03f, 1.4f, 500f, 70f, 0.8f, 0.08f, 0.4f); thud(b, 0.02f, 80f, 28f, 1.2f, 0.7f) }
            S.STOMP -> { thud(b, 0f, 110f, 34f, 0.5f, 1f); swish(b, 0f, 0.35f, 900f, 180f, 0.4f, 0.05f, 0.7f) }
            S.SPRINT -> swish(b, 0f, 0.32f, 500f, 3200f, 0.45f, 0.35f, 0.5f)
            S.FIREDASH -> { swish(b, 0f, 0.5f, 300f, 2600f, 0.5f, 0.3f, 0.5f); crackle(b, 0.05f, 0.4f, 0.4f, 0.3f) }
            // hitting something: a crack
            S.IMP_HIT -> crack(b, 0f, 0.9f)
            S.IMP_DIE -> { crack(b, 0f, 0.9f, true); swish(b, 0.02f, 0.3f, 2400f, 280f, 0.4f, 0.05f, 0.8f) }
            S.COUNTER -> { crack(b, 0f, 1f, true); thud(b, 0f, 150f, 50f, 0.35f, 0.7f) }
            S.SHATTER -> { crack(b, 0f, 1f, true); repeat(6) { crack(b, 0.03f + it * 0.03f, 0.25f) } }
            // finds: a pluck or two, a little glitter
            S.PICKUP -> { pluck(b, 0f, D5, 0.3f, 1f, 0.7f); pluck(b, 0.08f, A5, 0.26f, 1f, 0.7f) }
            S.FETCH -> pluck(b, 0f, A5, 0.28f, 1f, 0.7f)
            S.LEGENDARY -> { pad(b, 0f, 2.4f, D3, 0.22f); pad(b, 0f, 2.4f, A3, 0.16f); floatArrayOf(D5, F5, A5, D6).forEachIndexed { i, f -> pluck(b, 0.1f + i * 0.12f, f, 0.24f, 1.6f, 0.75f) }; glitter(b, 0.5f, 1.2f, 40, 0.1f) }
            S.EXOTIC -> { pad(b, 0f, 2.4f, D3 / 2, 0.3f); pad(b, 0f, 2.4f, A3 / 2, 0.18f); glitter(b, 0f, 2f, 90, 0.1f, 1800f, 6500f); swish(b, 0f, 1.2f, 200f, 4000f, 0.15f, 0.9f, 0.3f) }
            S.LEVEL_UP -> { floatArrayOf(D5, E5, F5, A5, D6).forEachIndexed { i, f -> pluck(b, i * 0.09f, f, 0.24f, 1.4f, 0.75f) }; glitter(b, 0.3f, 0.9f, 30, 0.09f) }
            S.FRIEND_JOIN -> { floatArrayOf(G4, D5, G5).forEachIndexed { i, f -> pluck(b, i * 0.12f, f, 0.26f, 1.4f, 0.6f) }; pad(b, 0f, 1.6f, G4 / 2, 0.18f) }
            S.FRIEND_SKILL -> { swish(b, 0f, 0.5f, 400f, 4500f, 0.35f, 0.6f, 0.4f); glitter(b, 0.15f, 0.6f, 30, 0.1f) }
            S.FRIEND_HURT -> knock(b, 0f, 260f, 0.6f)
            S.FRIEND_DOWN -> { floatArrayOf(D5, C5, A4, F4).forEachIndexed { i, f -> pluck(b, i * 0.22f, f, 0.24f, 1.2f, 0.4f) }; pad(b, 0.1f, 1.7f, D3, 0.2f, D3 * 0.97f) }
            S.REVIVE -> { breath(b, 0f, 1.4f, 0.4f); pad(b, 0f, 2.4f, D3, 0.22f, A3); glitter(b, 0.4f, 1.4f, 50, 0.09f) }
            // spells: glittery
            S.SPELL -> { glitter(b, 0f, 1f, 60, 0.12f); swish(b, 0f, 0.9f, 300f, 2400f, 0.18f, 0.6f, 0.3f) }
            S.HEX -> { glitter(b, 0f, 1.2f, 50, 0.11f, 1200f, 4000f, fall = true); pad(b, 0f, 1.6f, D3, 0.28f); pad(b, 0f, 1.6f, 207.7f, 0.22f) }
            S.STARFALL -> { glitter(b, 0f, 0.6f, 50, 0.12f, 2500f, 8000f, fall = true); thud(b, 0.6f, 120f, 35f, 0.8f, 0.9f); swish(b, 0.6f, 0.6f, 1500f, 200f, 0.45f, 0.05f, 0.6f) }
            S.BLINK -> { swish(b, 0f, 0.4f, 4000f, 500f, 0.3f, 0.7f, 0.4f); glitter(b, 0.25f, 0.3f, 18, 0.1f) }
            S.BOSS_RUNE -> { pad(b, 0f, 2.4f, D3 / 2, 0.45f); pad(b, 0f, 2.4f, 77.8f, 0.35f); glitter(b, 0.1f, 2f, 50, 0.07f, 1000f, 3500f, fall = true); swish(b, 0.2f, 1.8f, 120f, 400f, 0.2f, 0.5f, 0.3f) }
            // healing: a breath and a twinkle
            S.MEND -> { breath(b, 0f, 0.9f, 0.45f); glitter(b, 0.15f, 0.9f, 26, 0.09f, 3000f, 7500f) }
            S.ORB -> { breath(b, 0f, 0.6f, 0.35f); glitter(b, 0.1f, 0.6f, 14, 0.09f, 3500f, 8000f) }
            // consecration: a choir, as if in worship
            S.WORSHIP -> { choir(b, 0f, 1.7f, floatArrayOf(D4, 370f, A4, D5), 0.7f); glitter(b, 0.3f, 1.2f, 14, 0.04f) }
            S.SLAM -> { thud(b, 0f, 90f, 22f, 1.8f, 1f); swish(b, 0f, 1f, 1200f, 90f, 0.6f, 0.03f, 0.6f); crack(b, 0f, 0.7f, true) }
            S.PERISH -> { pad(b, 0f, 2.4f, A3, 0.3f, D3); pad(b, 0f, 2.4f, 233f, 0.2f, 155.6f); glitter(b, 0f, 2f, 60, 0.07f, 800f, 5000f, fall = true); swish(b, 0f, 2f, 3000f, 100f, 0.3f, 0.1f, 0.4f) }
            S.BURN -> { crackle(b, 0f, 0.5f, 0.5f, 0.25f); swish(b, 0f, 0.5f, 1500f, 500f, 0.3f, 0.2f, 0.5f) }
            S.DUEL -> { for (i in 0 until 3) thud(b, i * 0.24f, 95f, 45f, 0.35f, 0.9f); pad(b, 0f, 1.8f, D3 / 2, 0.3f); pad(b, 0f, 1.8f, A3 / 2, 0.22f) }
            S.DOOR_UNLOCK -> { crack(b, 0f, 0.9f, true); thud(b, 0.02f, 120f, 40f, 0.5f, 0.9f); glitter(b, 0.1f, 0.5f, 20, 0.08f) }
            S.DOOR_GRIND -> { swish(b, 0f, 2.6f, 260f, 140f, 0.8f, 0.2f, 0.25f); thud(b, 0f, 60f, 35f, 2.6f, 0.4f); val r = kotlin.random.Random(11); repeat(24) { crack(b, r.nextFloat() * 2.4f, 0.12f + r.nextFloat() * 0.1f) } }
            S.DOOR_OPEN -> { thud(b, 0f, 85f, 26f, 1.4f, 1f); pad(b, 0.1f, 2.4f, D3, 0.25f); pad(b, 0.1f, 2.4f, A3, 0.2f); pad(b, 0.1f, 2.4f, 370f, 0.14f)
                floatArrayOf(D5, 740f, A5, D6).forEachIndexed { i, f -> pluck(b, 0.3f + i * 0.12f, f, 0.22f, 1.6f, 0.8f) }; glitter(b, 0.4f, 1.8f, 70, 0.08f) }
        }
        reverb(b.a, when (s) { S.IMP_HIT, S.ZAP, S.HURT, S.HURT_BIG, S.FRIEND_HURT, S.COUNTER -> 0.15f; S.WORSHIP -> 0.6f; S.SPRINT, S.FIREDASH, S.BURN, S.FETCH, S.PICKUP -> 0.3f; else -> 0.45f })
        // fade the tail so nothing clicks off; a clean, soft-knee limiter
        val fade = minOf(b.a.size, len(0.08f)); for (i in 0 until fade) b.a[b.a.size - 1 - i] *= i / fade.toFloat()
        var peak = 0.0001f; for (v in b.a) peak = maxOf(peak, kotlin.math.abs(v))
        val target = when (s) { S.ZAP -> 0.5f; S.IMP_HIT, S.SPRINT -> 0.6f; S.PICKUP, S.FETCH, S.MEND, S.ORB -> 0.6f; else -> 0.8f }
        val g = target / peak
        return ShortArray(b.a.size) { (kotlin.math.tanh(b.a[it] * g * 1.1) * 27000).toInt().toShort() }
    }
}
