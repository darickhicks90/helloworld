package app.foxbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.foxbox.audio.Analysis
import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.audio.PitchTrack
import app.foxbox.audio.SR
import app.foxbox.seq.Clip
import app.foxbox.seq.Theory
import app.foxbox.seq.Ticks
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Noodler: audio to MIDI. Sing or play one note at a time into the mic and it
 * follows the pitch, note by note.
 *  - Capture: turns what you noodle into a clip on a block (in the project's scale
 *    unless set to every semitone).
 *  - Live: plays a melodic block as you sing, there and then (headphones needed, so it
 *    doesn't hear the block itself).
 *  - Noodle Forge: count in four even beats (claps, taps, anything), then sing or play
 *    an idea; the count sets the tempo and the idea sets the key and scale, and the idea
 *    becomes a clip. It changes the project's settings, so it asks first.
 */
object Noodler {
    enum class Mode(val label: String) { CAPTURE("Capture to a clip"), LIVE("Live: play a block by voice"), FORGE("Noodle Forge (set tempo & scale)") }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    fun init(c: Context) { prefs = c.getSharedPreferences("noodler", Context.MODE_PRIVATE) }

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString("mode", "CAPTURE")!!) }.getOrDefault(Mode.CAPTURE)
        set(v) = prefs.edit().putString("mode", v.name).apply()
    /** Snap notes to the project's scale (on by default; Noodle Forge always ignores it). */
    var snap: Boolean
        get() = prefs.getBoolean("snap", true)
        set(v) = prefs.edit().putBoolean("snap", v).apply()
    /** Which input it listens to: 0 input 1, 1 input 2, 2 both. */
    var input: Int
        get() = prefs.getInt("input", 2)
        set(v) = prefs.edit().putInt("input", v).apply()
    /** How loud a sound must be to count (dBFS). */
    var sensitivity: Float
        get() = prefs.getFloat("sens", -42f)
        set(v) = prefs.edit().putFloat("sens", v).apply()
    var octave: Int
        get() = prefs.getInt("octave", 0)
        set(v) = prefs.edit().putInt("octave", v.coerceIn(-2, 2)).apply()
    /** Capture's grid: 0 off, 1 sixteenths, 2 eighths. */
    var quantize: Int
        get() = prefs.getInt("quant", 1)
        set(v) = prefs.edit().putInt("quant", v).apply()
    /** The block it plays or writes to ("" = the melodic block in front). */
    var block: String
        get() = prefs.getString("block", "") ?: ""
        set(v) = prefs.edit().putString("block", v).apply()

    @Volatile var active = false; private set
    /** The note it hears right now (-1 none), for the key's display. */
    @Volatile var noteNow = -1; private set
    /** Forge: 0 counting in, 1 hearing the idea. */
    @Volatile var phase = 0; private set
    var onChange: (() -> Unit)? = null
    /**
     * Noodle Forge couldn't tell the key from so few notes: the activity shows the
     * song set-up wizard with these candidates (root, scale index), best first.
     */
    var onChooseScale: ((candidates: List<Pair<Int, Int>>, idea: Clip?, sung: Set<Int>) -> Unit)? = null

    private var thread: Thread? = null
    private var runMode = Mode.CAPTURE
    private var target: Engine = Audio.gusts

    private class N(var note: Int, val startMs: Double, var endMs: Double, val vel: Float) { var pSum = 0.0; var pW = 0.0 }
    private val notes = ArrayList<N>()
    private val onsets = ArrayList<Double>()
    private var beatMs = 0.0
    private var downbeatMs = 0.0

    private const val KEY = 0x7E000000

    fun targetEngine(): Engine {
        val id = block
        return if (id.isNotEmpty() && Studio.isOpen && id in Studio.project.blocks) Audio.engine(id) else Studio.melodic
    }

    fun start() {
        if (active) return
        runMode = mode; target = targetEngine()
        notes.clear(); onsets.clear(); phase = 0; beatMs = 0.0
        Audio.noodleSource = input
        Studio.noodlerListening = true
        active = true
        Studio.message(when (runMode) {
            Mode.CAPTURE -> "Noodler: sing or play your idea for ${target.title}. Tap Noodler again when you're done."
            Mode.LIVE -> "Noodler live: ${target.title} follows your voice. Tap Noodler to stop."
            Mode.FORGE -> "Noodle Forge: count in by clapping or tapping 4 even beats, then sing or play your idea. Tap Noodler when you're done."
        })
        thread = Thread({ listen() }, "Noodler").apply { start() }
        changed()
    }

    fun stop() {
        if (!active) return
        active = false
        thread?.join(500); thread = null
        Audio.noodleSource = -1
        Studio.noodlerListening = false
        noteNow = -1
        when (runMode) {
            Mode.CAPTURE -> finishCapture()
            Mode.FORGE -> finishForge()
            Mode.LIVE -> Studio.message("Noodler stopped")
        }
        changed()
    }

    private fun changed() = main.post { onChange?.invoke(); Studio.changed() }

    // ------------------------------------------------------------------ listening (its own thread)

    private fun listen() {
        val tracker = PitchTrack(2048)
        val win = FloatArray(1024)
        val t0 = System.nanoTime()
        var cand = -1; var candFrames = 0
        var cur = -1; var curStart = 0.0; var silentFrames = 0
        var envFloor = 0f; var lastOnset = -1e9
        val mask = Audio.scaleMask
        // how far off concert pitch the person is overall (everything a little flat or sharp),
        // as the average of where pitches sit between semitones (on a circle, so +0.5 and -0.5 meet)
        var tx = 0.0; var ty = 0.0
        while (active) {
            val now = (System.nanoTime() - t0) / 1e6
            // the newest ~43 ms, at half the sample rate
            val w = Audio.noodleWrite
            for (i in 0 until 1024) { val a = Audio.noodleRing[(w - 2048 + i * 2) and 16383]; val b = Audio.noodleRing[(w - 2047 + i * 2) and 16383]; win[i] = (a + b) * 0.5f }
            var e = 0f; for (v in win) e += v * v
            val rms = sqrt(e / win.size)
            val db = if (rms > 1e-6f) 20f * log10(rms) else -120f
            // Forge's count-in: sudden jumps in loudness are its beats
            if (runMode == Mode.FORGE && phase == 0) {
                if (db > sensitivity && db - envFloor > 9f && now - lastOnset > 160) { lastOnset = now; onsets += now; checkCount() }
                envFloor = if (db < envFloor) db else envFloor + (db - envFloor) * 0.04f
                if (phase == 0) { sleep(); continue }
            }
            val voiced = db > sensitivity
            var n = -1
            var raw = Float.NaN
            if (voiced) {
                val hz = tracker.detect(win, 0, 1024, SR / 2f, 60f, 1200f)
                if (hz > 0f && tracker.clarity > 0.72f) {
                    raw = PitchTrack.midi(hz) + 12 * octave
                    val f = raw - Math.round(raw)
                    tx = tx * 0.998 + cos(2 * PI * f); ty = ty * 0.998 + sin(2 * PI * f)
                    val off = if (hypot(tx, ty) > 3.0) (atan2(ty, tx) / (2 * PI)).toFloat() else 0f
                    val m = raw - off
                    n = if (runMode == Mode.FORGE || !snap) Math.round(m) else PitchTrack.nearestInScale(m, mask)
                }
            }
            // a new note has to hold for two looks (about 20 ms) before it counts
            if (n >= 0 && n == cand) candFrames++ else { cand = n; candFrames = if (n >= 0) 1 else 0 }
            if (n >= 0) silentFrames = 0 else silentFrames++
            if (candFrames >= 2 && cand != cur) {
                if (cur >= 0) noteOff(cur, now)
                cur = cand; curStart = now
                noteOn(cur, now, ((db + 50f) / 40f).coerceIn(0.35f, 1f))
            } else if (cur >= 0 && silentFrames >= 3) { noteOff(cur, now); cur = -1 }
            // remember the note's true pitch, to tune the whole idea at the end
            if (cur >= 0 && !raw.isNaN() && runMode != Mode.LIVE) synchronized(notes) {
                notes.lastOrNull()?.let { if (it.note == cur) { it.pSum += raw; it.pW += 1.0 } }
            }
            noteNow = cur
            sleep()
        }
        if (cur >= 0) noteOff(cur, (System.nanoTime() - t0) / 1e6)
    }

    private fun sleep() { try { Thread.sleep(10) } catch (_: InterruptedException) {} }

    private fun noteOn(note: Int, ms: Double, vel: Float) {
        if (runMode == Mode.LIVE) { val e = target; Studio.noteOn(e, KEY, note, vel) }
        else synchronized(notes) { notes += N(note, ms, ms + 50, vel) }
    }

    private fun noteOff(note: Int, ms: Double) {
        if (runMode == Mode.LIVE) { val e = target; Studio.noteOff(e, KEY) }
        else synchronized(notes) { notes.lastOrNull { it.note == note }?.endMs = ms }
    }

    /** Four claps in a steady rhythm set the beat. */
    private fun checkCount() {
        if (onsets.size < 4) { Studio.message("Count in: ${onsets.size}…"); return }
        val last = onsets.takeLast(4)
        val gaps = (1..3).map { last[it] - last[it - 1] }
        val mean = gaps.average()
        if (mean in 300.0..1500.0 && gaps.all { kotlin.math.abs(it - mean) < mean * 0.18 }) {
            beatMs = mean; downbeatMs = last[3] + mean; phase = 1
            Studio.message("Got it: about ${(60000 / mean).toInt()} BPM. Now your idea…")
            changed()
        } else Studio.message("Count in: keep the 4 beats even…")
    }

    // ------------------------------------------------------------------ turning it into a clip

    private fun clipNotes(startMs: Double, msPerTick: Double, grid: Int): Pair<List<Clip.Note>, Int> {
        val list = synchronized(notes) { notes.filter { it.endMs - it.startMs > 40 }.toList() }
        val out = ArrayList<Clip.Note>()
        for (nn in list) {
            var t = ((nn.startMs - startMs) / msPerTick).toInt()
            if (t < 0) continue
            if (grid > 1) t = Math.round(t / grid.toFloat()) * grid
            val d = maxOf(Ticks.STEP / 2, ((nn.endMs - nn.startMs) / msPerTick).toInt())
            out += Clip.Note(t, d, nn.note.coerceIn(0, 127), nn.vel)
        }
        val end = out.maxOfOrNull { it.tick + it.dur } ?: Ticks.BAR
        val len = ((end + Ticks.BAR - 1) / Ticks.BAR).coerceAtLeast(1) * Ticks.BAR
        return out to len
    }

    /**
     * If everything was a little flat or sharp, find by how much (from every note's average
     * true pitch, weighted by how long it was held) and move the whole idea onto the right
     * notes. Returns a few words saying what was done, or "" if it was already in tune.
     */
    private fun retune(list: List<N>, mask: Int?): String {
        var sx = 0.0; var sy = 0.0
        for (nn in list) if (nn.pW > 0) {
            val p = nn.pSum / nn.pW; val f = p - Math.round(p)
            val w = (nn.endMs - nn.startMs).coerceAtLeast(40.0)
            sx += w * cos(2 * PI * f); sy += w * sin(2 * PI * f)
        }
        if (sx == 0.0 && sy == 0.0) return ""
        val off = atan2(sy, sx) / (2 * PI)
        for (nn in list) if (nn.pW > 0) {
            val p = (nn.pSum / nn.pW - off).toFloat()
            nn.note = if (mask != null) PitchTrack.nearestInScale(p, mask) else Math.round(p)
        }
        val cents = Math.round(off * 100).toInt()
        return when {
            cents <= -8 -> " You were about ${-cents} cents flat, so it was tuned up to fit."
            cents >= 8 -> " You were about $cents cents sharp, so it was tuned down to fit."
            else -> ""
        }
    }

    private fun finishCapture() {
        val list = synchronized(notes) { notes.toList() }
        if (list.isEmpty()) return Studio.message("Noodler didn't hear any notes. Try a little louder, or raise the sensitivity (hold Noodler).")
        val tuned = retune(list, if (snap) Audio.scaleMask else null)
        val bpm = Studio.project.bpm.toDouble()
        val msPerTick = 60000.0 / bpm / Ticks.PPQ
        // start on the beat the first note fell in
        val first = list.first().startMs
        val grid = when (quantize) { 1 -> Ticks.STEP; 2 -> Ticks.STEP * 2; else -> 1 }
        val (cn, len) = clipNotes(first - 20.0, msPerTick, grid)
        main.post {
            Studio.addNoodleClip(target, cn, len)?.let { Studio.message("Noodled ${cn.size} notes into ${it.name}.$tuned") }
        }
    }

    private fun finishForge() {
        if (phase == 0 || beatMs <= 0.0) return Studio.message("Noodle Forge needs a steady count of 4 first. Nothing was changed.")
        val list = synchronized(notes) { notes.filter { it.startMs >= downbeatMs - beatMs * 0.25 }.toList() }
        if (list.isEmpty()) return Studio.message("Noodle Forge heard the count but no idea. Nothing was changed.")
        val bpm = (60000.0 / beatMs).toFloat().coerceIn(40f, 220f)
        val tuned = retune(list, null)
        // the key: which pitch classes the idea dwelt on
        val chroma = DoubleArray(12)
        for (nn in list) chroma[Math.floorMod(nn.note, 12)] += (nn.endMs - nn.startMs).coerceAtLeast(60.0)
        val (root, minor, conf) = Analysis.key(chroma)
        val scale = Theory.SCALES.indexOfFirst { it.name == if (minor) "Minor" else "Major" }.coerceAtLeast(0)
        val msPerTick = 60000.0 / bpm / Ticks.PPQ
        synchronized(notes) { notes.clear(); notes.addAll(list) }
        val (cn, len) = clipNotes(downbeatMs, msPerTick, Ticks.STEP)
        val sung = list.map { Math.floorMod(it.note, 12) }.toSet()
        // not every note of a scale was sung (or played), so more than one scale fits: let the person choose
        val unsure = sung.size < 7 || conf < 0.3f
        val cands = if (unsure) candidates(list) else emptyList()
        main.post {
            Studio.setBpm(bpm.let { Math.round(it).toFloat() })
            val clip = Studio.addNoodleClip(target, cn, len)
            if (unsure && cands.isNotEmpty() && onChooseScale != null) {
                onChooseScale?.invoke(cands, clip, sung)
                Studio.message("Noodle Forge: ${Math.round(bpm)} BPM. Now choose the scale.$tuned")
            } else {
                Studio.setKey(root = root, scale = scale)
                Studio.message("Noodle Forge: ${Math.round(bpm)} BPM, ${Theory.NOTE_NAMES[root]} ${Theory.SCALES[scale].name}. Your idea is on ${target.title}.$tuned")
            }
        }
    }

    /**
     * Every scale (any root) that holds all the notes that were sung, best first: ones
     * whose root was sung (especially first or last), longest-held notes on strong
     * degrees, and the everyday scales before the rarer ones.
     */
    private fun candidates(list: List<N>): List<Pair<Int, Int>> {
        val dur = DoubleArray(12)
        for (nn in list) dur[Math.floorMod(nn.note, 12)] += (nn.endMs - nn.startMs).coerceAtLeast(60.0)
        val sung = (0 until 12).filter { dur[it] > 0 }
        val firstPc = list.firstOrNull()?.let { Math.floorMod(it.note, 12) } ?: -1
        val lastPc = list.lastOrNull()?.let { Math.floorMod(it.note, 12) } ?: -1
        val total = dur.sum().coerceAtLeast(1.0)
        val out = ArrayList<Triple<Int, Int, Double>>()
        for ((si, sc) in Theory.SCALES.withIndex()) for (root in 0 until 12) {
            val pcs = sc.steps.map { (root + it) % 12 }.toSet()
            if (!sung.all { it in pcs }) continue
            var score = 0.0
            if (root in sung) score += 2.0
            if (root == firstPc) score += 1.0
            if (root == lastPc) score += 1.5
            // the fifth and third held a while are good signs
            score += dur[(root + 7) % 12] / total + dur[root] / total * 1.5
            score -= si * 0.12        // everyday scales first (Major, Minor, then the modes)
            score -= (sc.steps.size - sung.size) * 0.05
            out += Triple(root, si, score)
        }
        return out.sortedByDescending { it.third }.take(14).map { it.first to it.second }
    }
}
