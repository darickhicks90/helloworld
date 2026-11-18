package app.foxbox.ios

import app.foxbox.audio.Core
import app.foxbox.audio.Engine
import app.foxbox.audio.Limiter
import app.foxbox.audio.Math
import app.foxbox.audio.Reverb
import app.foxbox.audio.locked
import app.foxbox.audio.setSampleRate
import app.foxbox.seq.Theory
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.set
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.max

/**
 * The iOS audio host: mixes the shared core's engines, the flower rack and
 * the reverb exactly as the Android host does, for AVAudioEngine's render
 * callback (see AudioHost.swift). Everything the UI does to the sound goes
 * through [post] and runs between blocks on the audio thread.
 */
object IosAudio {
    private const val BLOCK = 64
    val transport get() = Core.transport

    @Volatile var active: Array<Engine> = arrayOf(Core.gusts, Core.minds)
        private set
    @Volatile var masterVolume = 0.7f
    @Volatile var peak = 0f

    private val lock = Any()
    private var pending = ArrayList<() -> Unit>()
    private var running = ArrayList<() -> Unit>()

    fun post(fn: () -> Unit) { locked(lock) { pending.add(fn) } }

    /** Call once with the session's sample rate before the engine starts. */
    fun configure(sampleRate: Double) { setSampleRate(sampleRate.toInt()) }

    /** Choose which blocks play (ids such as "gusts", "pickup"). */
    fun setActive(ids: List<String>) {
        val list = ids.mapNotNull { id -> Core.engines.firstOrNull { it.id == id } }
        post {
            Core.engines.forEach { if (it !in list) it.allOff() }
            active = list.toTypedArray()
            transport.engines = list
        }
    }

    private val mixL = FloatArray(BLOCK); private val mixR = FloatArray(BLOCK)
    private val eL = FloatArray(BLOCK); private val eR = FloatArray(BLOCK)
    private val revL = FloatArray(BLOCK); private val revR = FloatArray(BLOCK)
    private val chL = Array(3) { FloatArray(BLOCK) }; private val chR = Array(3) { FloatArray(BLOCK) }
    private val busL = Array(3) { FloatArray(BLOCK) }; private val busR = Array(3) { FloatArray(BLOCK) }
    private val reverb = Reverb()
    private val limiter = Limiter()

    /** Render `frames` into two (non-interleaved) channel buffers. Called on the audio thread. */
    fun render(left: CPointer<FloatVar>, right: CPointer<FloatVar>, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            block(n)
            for (i in 0 until n) { left[done + i] = mixL[i]; right[done + i] = mixR[i] }
            done += n
        }
    }

    private fun drain() {
        val todo = locked(lock) { val t = pending; pending = running; running = t; t }
        for (i in todo.indices) todo[i]()
        todo.clear()
    }

    private fun block(n: Int) {
        drain()
        val t = transport
        t.process(n)
        mixL.fill(0f); mixR.fill(0f); revL.fill(0f); revR.fill(0f)
        for (k in 0 until 3) { chL[k].fill(0f, 0, n); chR[k].fill(0f, 0, n) }
        val rack = Core.flowers
        val chains = rack.chains
        val routes = rack.blockRoute
        val act = active
        for (k in act.indices) {
            val e = act[k]
            e.seq?.advance(n)
            eL.fill(0f, 0, n); eR.fill(0f, 0, n)
            e.render(eL, eR, n)
            val v = e.volume.value; val s = e.reverb.value * v
            val c = routes[e.id] ?: -1
            val dl = if (c >= 0 && chains[c] != null) chL[c] else mixL
            val dr = if (c >= 0 && chains[c] != null) chR[c] else mixR
            val pn = e.livePan
            val pl = v * minOf(1f, 1f - pn); val pr = v * minOf(1f, 1f + pn)
            for (i in 0 until n) {
                dl[i] += eL[i] * pl; dr[i] += eR[i] * pr
                revL[i] += eL[i] * s; revR[i] += eR[i] * s
            }
        }
        for (ch in 0 until 3) { busL[ch].fill(0f, 0, n); busR[ch].fill(0f, 0, n) }
        t.renderAudio(busL, busR, n, t.blockStartTick)
        for (ch in 0 until 3) {
            val c = rack.channelRoute[ch]
            val dl = if (c >= 0 && chains[c] != null) chL[c] else mixL
            val dr = if (c >= 0 && chains[c] != null) chR[c] else mixR
            val bl = busL[ch]; val br = busR[ch]
            for (i in 0 until n) { dl[i] += bl[i] * 0.9f; dr[i] += br[i] * 0.9f }
        }
        for (k in 0 until 3) if (chains[k] != null) {
            rack.processChain(k, chL[k], chR[k], n)
            val l = chL[k]; val r = chR[k]
            for (i in 0 until n) { mixL[i] += l[i]; mixR[i] += r[i] }
        }
        reverb.process(revL, revR, mixL, mixR, n)
        rack.processAudition(mixL, mixR, n)
        t.renderClick(mixL, mixR, n)
        limiter.process(mixL, mixR, n, masterVolume)
        var pk = peak * 0.97f
        for (i in 0 until n) pk = max(pk, abs(mixL[i]))
        peak = pk
    }

    // ------------------------------------------------------------ what the Swift UI calls

    fun engineIds(): List<String> = Core.engines.map { it.id }
    fun title(id: String): String = Core.engine(id).title

    fun noteOn(id: String, key: Int, note: Int, vel: Float) {
        val e = Core.engine(id)
        post { e.noteOn(key, note, vel); transport.recNoteOn(e, key, note, vel) }
    }
    fun noteOff(id: String, key: Int) {
        val e = Core.engine(id)
        post { e.noteOff(key); transport.recNoteOff(e, key) }
    }
    fun allOff(id: String) { val e = Core.engine(id); post { e.allOff() } }

    /** The XY pad: x, y in 0..1 (y = 1 at the top); `touching` false when the finger lifts. */
    fun setXy(id: String, x: Float, y: Float, touching: Boolean) {
        val xy = Core.engine(id).xy
        xy.x = x.coerceIn(0f, 1f); xy.y = y.coerceIn(0f, 1f)
        if (touching) xy.touching = true else xy.release()
    }
    fun xyX(id: String) = Core.engine(id).xy.let { if (it.touching) it.x else it.restX() }
    fun xyY(id: String) = Core.engine(id).xy.let { if (it.touching) it.y else it.yParam.norm }
    fun xyXName(id: String) = Core.engine(id).xy.xParam.name
    fun xyYName(id: String) = Core.engine(id).xy.yParam.name

    // key and chords
    fun scaleNames(): List<String> = Theory.SCALES.map { it.name }
    fun noteName(n: Int) = Theory.noteName(n)
    fun noteNameOct(n: Int) = Theory.noteNameOct(n)
    fun chordCount(scale: Int) = Theory.SCALES[Math.floorMod(scale, Theory.SCALES.size)].steps.size
    private fun chord(scale: Int, root: Int, octave: Int, degree: Int, seventh: Boolean) =
        Theory.chord(Theory.SCALES[Math.floorMod(scale, Theory.SCALES.size)], Math.floorMod(root, 12), 48 + octave * 12, degree, seventh)
    fun chordNotes(scale: Int, root: Int, octave: Int, degree: Int, seventh: Boolean): List<Int> = chord(scale, root, octave, degree, seventh).notes.toList()
    fun chordName(scale: Int, root: Int, octave: Int, degree: Int, seventh: Boolean) = chord(scale, root, octave, degree, seventh).name
    fun chordRoman(scale: Int, root: Int, octave: Int, degree: Int, seventh: Boolean) = chord(scale, root, octave, degree, seventh).roman

    // transport
    fun play() = post { if (!transport.playing) transport.play() }
    fun stop() = post { transport.stop() }
    val playing get() = transport.playing
    var bpm: Float
        get() = transport.bpm
        set(v) { transport.bpm = v.coerceIn(40f, 220f) }
    fun position(): String = if (!transport.playing) "stopped" else "${transport.bar + 1}.${transport.beat + 1}"

    // pads (Pickup, Minds)
    fun padName(id: String, i: Int): String = when (id) {
        "pickup" -> Core.pickup.names.getOrElse(i) { "Pad ${i + 1}" }
        "minds" -> app.foxbox.engines.Minds.DRUMS.getOrElse(i) { "" }
        else -> ""
    }
    fun isPads(id: String) = id == "pickup" || id == "minds"
}
