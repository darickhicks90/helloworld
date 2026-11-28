package app.foxbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.foxbox.audio.Analysis
import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.audio.NoteSegmenter
import app.foxbox.audio.TakeAnalyzer
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
    fun init(c: Context) {
        prefs = c.getSharedPreferences("noodler", Context.MODE_PRIVATE)
        // detection got calmer by default: move everyone onto it once
        if (prefs.getInt("detv", 0) < 2) { resetDetection(); prefs.edit().putInt("detv", 2).apply() }
    }

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString("mode", "CAPTURE")!!) }.getOrDefault(Mode.CAPTURE).let { if (it == Mode.FORGE) Mode.CAPTURE else it }
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
    /** How sure the pitch must be before it counts (0.5 catches more, 0.95 only very clear notes). */
    var confidence: Float
        get() = prefs.getFloat("conf", 0.8f)
        set(v) = prefs.edit().putFloat("conf", v.coerceIn(0.5f, 0.95f)).apply()
    /** How long a note must hold before it counts (ms). */
    var settleMs: Int
        get() = prefs.getInt("settle", 80)
        set(v) = prefs.edit().putInt("settle", v.coerceIn(20, 250)).apply()
    /** How far the voice must move, steadily, to change note (cents): higher holds on through slides and vibrato. */
    var stickCents: Int
        get() = prefs.getInt("stick", 75)
        set(v) = prefs.edit().putInt("stick", v.coerceIn(50, 95)).apply()
    /** The shortest note kept (ms). */
    var shortestMs: Int
        get() = prefs.getInt("shortest", 130)
        set(v) = prefs.edit().putInt("shortest", v.coerceIn(40, 400)).apply()
    fun resetDetection() { confidence = 0.8f; settleMs = 80; stickCents = 75; shortestMs = 130 }
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
    /** How loud the input is right now, and the loudest it has been this time (dBFS). */
    @Volatile var levelDb = -120f; private set
    @Volatile var peakDb = -120f; private set
    /** Forge's count-in: how many steady beats in a row it has heard so far (0..4). */
    @Volatile var beatsLit = 0; private set
    /** Forge's count-in: a word about the count (e.g. that it wasn't steady), or "". */
    @Volatile var countHint = ""; private set
    /** Forge: the tempo the count set (0 until it has). */
    val countBpm get() = if (beatMs > 0) 60000.0 / beatMs else 0.0
    /** Forge: when the idea's first bar starts, in the listener's clock (ms), and that clock now. */
    val downbeat get() = downbeatMs
    fun clockMs() = (System.nanoTime() - t0) / 1e6
    @Volatile private var t0 = System.nanoTime()

    /** The notes caught so far (newest last), for the screen. */
    fun caught(): List<Int> = synchronized(notes) { notes.filter { it.endMs - it.startMs > 40 || it === notes.last() }.map { it.note } }

    /** What Noodle Forge made of the count and the idea. Nothing is changed until it's kept. */
    class ForgeResult(
        val bpm: Int, val notes: List<Clip.Note>, val lengthTicks: Int,
        /** Scales the idea could be in (root, scale index), the best guess first. */
        val cands: List<Pair<Int, Int>>,
        val sung: Set<Int>,
        /** Every note of a scale was there and the key was clear. */
        val sure: Boolean,
        /** How out of tune it was, in cents (+ sharp, - flat); it has been put right. */
        val cents: Int,
        val engine: Engine,
    )
    /** Forge finished: the result, or null with a reason. On the main thread. */
    var onForged: ((ForgeResult?, String) -> Unit)? = null
    /** Capture finished: the clip (or null) and what to tell the person. On the main thread. */
    var onCaptured: ((Clip?, String) -> Unit)? = null

    private var thread: Thread? = null
    private var runMode = Mode.CAPTURE
    private var target: Engine = Audio.gusts

    // every look the listener takes (about every 10 ms), for working out the notes carefully at the end
    private const val MAXF = 36000
    private val fT = DoubleArray(MAXF); private val fM = FloatArray(MAXF); private val fDb = FloatArray(MAXF)
    @Volatile private var fN = 0
    /** The take, at half the sample rate (up to 2 minutes), and when it started on the listener's clock. */
    private var take = FloatArray(0)
    @Volatile private var takeN = 0
    private var takeStartMs = -1.0
    /** The careful listen-back: how far through it is (0..1), and whether it's running. */
    @Volatile var working = false; private set
    @Volatile var workProgress = 0f; private set

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

    /** Start listening, in `as` mode (default: the mode chosen in settings). */
    fun start(asMode: Mode = mode) {
        if (active) return
        runMode = asMode; target = targetEngine()
        notes.clear(); onsets.clear(); phase = 0; beatMs = 0.0; downbeatMs = 0.0
        beatsLit = 0; countHint = ""; levelDb = -120f; peakDb = -120f; fN = 0
        if (asMode != Mode.LIVE && take.isEmpty()) take = FloatArray((SR / 2) * 120)
        takeN = 0; takeStartMs = -1.0
        t0 = System.nanoTime()
        Audio.noodleSource = input
        Studio.noodlerListening = true
        active = true
        when (runMode) {
            Mode.CAPTURE -> Studio.message("Noodler is listening: sing or play your idea, one note at a time. Tap NOODLE again to finish.")
            Mode.LIVE -> Studio.message("Noodler live: ${target.title} follows your voice. Tap NOODLE to stop.")
            Mode.FORGE -> {}
        }
        thread = Thread({ listen() }, "Noodler").apply { start() }
        changed()
    }

    /** Stop listening and throw away what was heard. */
    fun cancel() {
        if (!active) return
        active = false
        thread?.join(500); thread = null
        Audio.noodleSource = -1
        Studio.noodlerListening = false
        noteNow = -1
        changed()
    }

    fun stop() {
        if (!active) return
        active = false
        thread?.join(500); thread = null
        Audio.noodleSource = -1
        Studio.noodlerListening = false
        noteNow = -1
        if (runMode == Mode.LIVE) { Studio.message("Noodler stopped"); changed(); return }
        // the careful listen-back takes a moment: off the main thread, with its progress for the screen
        working = true; workProgress = 0f
        changed()
        Thread({
            try { if (runMode == Mode.CAPTURE) finishCapture() else finishForge() }
            catch (e: Exception) { main.post { if (runMode == Mode.CAPTURE) onCaptured?.invoke(null, "Something went wrong working out the notes. Try again.") else onForged?.invoke(null, "Something went wrong working out the notes. Try again.") } }
            finally { working = false; changed() }
        }, "Noodler listen-back").start()
    }

    private fun changed() = main.post { onChange?.invoke(); Studio.changed() }

    // ------------------------------------------------------------------ listening (its own thread)

    private fun listen() {
        val tracker = PitchTrack(2048)
        val win = FloatArray(1024)
        var cand = -1; var candFrames = 0
        var cur = -1; var curStart = 0.0; var silentFrames = 0
        var envFloor = 0f; var lastOnset = -1e9
        val mask = Audio.scaleMask
        val conf = confidence
        val settleLooks = if (runMode == Mode.LIVE) (settleMs / 20).coerceAtLeast(2) else (settleMs / 10).coerceAtLeast(2)
        // how far off concert pitch the person is overall (everything a little flat or sharp),
        // as the average of where pitches sit between semitones (on a circle, so +0.5 and -0.5 meet)
        var tx = 0.0; var ty = 0.0
        var rp = Audio.noodleWrite
        while (active) {
            val now = (System.nanoTime() - t0) / 1e6
            // the newest ~43 ms, at half the sample rate
            val w = Audio.noodleWrite
            for (i in 0 until 1024) { val a = Audio.noodleRing[(w - 2048 + i * 2) and 16383]; val b = Audio.noodleRing[(w - 2047 + i * 2) and 16383]; win[i] = (a + b) * 0.5f }
            var e = 0f; for (v in win) e += v * v
            val rms = sqrt(e / win.size)
            val db = if (rms > 1e-6f) 20f * log10(rms) else -120f
            levelDb = db; if (db > peakDb) peakDb = db
            // keep the take (Forge: from the end of the count) at half the sample rate
            if (runMode != Mode.LIVE && (runMode != Mode.FORGE || phase == 1)) {
                var avail = (w - rp) and 16383
                if (avail > 16000) { rp = (w - 16000) and 16383; avail = 16000 }
                if (takeStartMs < 0) takeStartMs = now - avail * 1000.0 / SR
                var k = takeN
                while (avail >= 2 && k < take.size) { take[k++] = (Audio.noodleRing[rp] + Audio.noodleRing[(rp + 1) and 16383]) * 0.5f; rp = (rp + 2) and 16383; avail -= 2 }
                takeN = k
            } else rp = w
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
                if (hz > 0f && tracker.clarity > conf) {
                    raw = PitchTrack.midi(hz) + 12 * octave
                    val f = raw - Math.round(raw)
                    tx = tx * 0.998 + cos(2 * PI * f); ty = ty * 0.998 + sin(2 * PI * f)
                    val off = if (hypot(tx, ty) > 3.0) (atan2(ty, tx) / (2 * PI)).toFloat() else 0f
                    val m = raw - off
                    n = if (runMode == Mode.FORGE || !snap) Math.round(m) else PitchTrack.nearestInScale(m, mask)
                }
            }
            // remember the look (the notes are worked out properly from these at the end)
            if (fN < MAXF) { fT[fN] = now; fM[fN] = raw; fDb[fN] = db; fN++ }
            // on screen: a new note has to hold a moment before it shows (live play answers quicker)
            if (n >= 0 && n == cand) candFrames++ else { cand = n; candFrames = if (n >= 0) 1 else 0 }
            if (n >= 0) silentFrames = 0 else silentFrames++
            if (candFrames >= settleLooks && cand != cur) {
                if (cur >= 0) noteOff(cur, now)
                cur = cand; curStart = now
                noteOn(cur, now, ((db + 50f) / 40f).coerceIn(0.35f, 1f))
            } else if (cur >= 0 && silentFrames >= 6) { noteOff(cur, now); cur = -1 }
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

    /** Four claps in a steady rhythm set the beat. Lights one more beat each time; uneven starts it over. */
    private fun checkCount() {
        // the run of steady beats at the end: each gap within 18% of the run's first gap
        val o = onsets
        var run = 1
        if (o.size >= 2) {
            val g0 = o[o.size - 1] - o[o.size - 2]
            if (g0 in 250.0..1600.0) {
                run = 2
                var i = o.size - 2
                while (run < 4 && i >= 1) {
                    val g = o[i] - o[i - 1]
                    if (kotlin.math.abs(g - g0) < g0 * 0.18) { run++; i-- } else break
                }
            }
        }
        val prev = beatsLit
        countHint = if (prev >= 2 && run <= prev) "Not quite steady. Counting again from here." else ""
        beatsLit = run
        if (run >= 4) {
            val last = o.takeLast(4)
            val mean = (last[3] - last[0]) / 3.0
            beatMs = mean; downbeatMs = last[3] + mean; phase = 1
            countHint = ""
        }
        changed()
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
    private var lastCents = 0
    /** Work out the notes from the looks taken since `fromMs`, carefully, and make them the notes. */
    private fun settleNotes(fromMs: Double, mask: Int) {
        val r = if (takeN > SR) {
            // the careful way: listen to the whole take again
            val tr = TakeAnalyzer.track(take, takeN, SR / 2f, takeStartMs, sensitivity, confidence, octave) { workProgress = it * 0.95f }
            var s = 0; while (s < tr.n && tr.t[s] < fromMs) s++
            NoteSegmenter.segment(tr.t.copyOfRange(s, tr.n), tr.m.copyOfRange(s, tr.n), tr.db.copyOfRange(s, tr.n), tr.n - s, mask,
                minMs = shortestMs.toDouble(), settleMs = settleMs.toDouble(), stick = stickCents / 100f)
        } else {
            val n = fN
            var s = 0; while (s < n && fT[s] < fromMs) s++
            NoteSegmenter.segment(fT.copyOfRange(s, n), fM.copyOfRange(s, n), fDb.copyOfRange(s, n), n - s, mask,
                minMs = shortestMs.toDouble(), settleMs = settleMs.toDouble(), stick = stickCents / 100f)
        }
        workProgress = 1f
        lastCents = r.cents
        synchronized(notes) { notes.clear(); for (x in r.notes) notes += N(x.note, x.startMs, x.endMs, x.vel) }
    }
    private fun tunedWords() = when {
        lastCents <= -8 -> " You were about ${-lastCents} cents flat, so it was tuned up to fit."
        lastCents >= 8 -> " You were about $lastCents cents sharp, so it was tuned down to fit."
        else -> ""
    }

    private fun finishCapture() {
        settleNotes(0.0, if (snap) Audio.scaleMask else 0)
        val list = synchronized(notes) { notes.toList() }
        if (list.isEmpty()) { val why = nothingHeard(); main.post { onCaptured?.invoke(null, "Didn't catch any notes. $why") }; return }
        val tuned = tunedWords()
        val bpm = Studio.project.bpm.toDouble()
        val msPerTick = 60000.0 / bpm / Ticks.PPQ
        // start on the beat the first note fell in
        val first = list.first().startMs
        val grid = when (quantize) { 1 -> Ticks.STEP; 2 -> Ticks.STEP * 2; else -> 1 }
        val (cn, len) = clipNotes(first - 20.0, msPerTick, grid)
        main.post {
            val clip = Studio.addNoodleClip(target, cn, len)
            onCaptured?.invoke(clip, if (clip != null) "Caught ${cn.size} notes. They're in the clip \"${clip.name}\" on ${target.title}.$tuned" else "Caught ${cn.size} notes, but ${target.title} has no room for another clip. Delete one and try again.")
        }
    }

    /** Why nothing was caught, in words that help: was it too quiet? */
    fun nothingHeard(): String {
        val pk = peakDb
        return when {
            pk < -90f -> "The input was silent the whole time. Check that the right input is chosen (hold NOODLE) and that 5 Eye Yeti may use the microphone."
            pk < sensitivity -> "The loudest sound was ${pk.toInt()} dB, but Noodler only listens to sounds above ${sensitivity.toInt()} dB. Sing closer, or lower the threshold (hold NOODLE)."
            else -> "It heard sound but no clear notes. Sing or play one note at a time, held steady, without chords or lots of noise."
        }
    }

    private fun finishForge() {
        fun fail(why: String) { main.post { onForged?.invoke(null, why) } }
        if (phase == 0 || beatMs <= 0.0) return fail("The count didn't land. Tap 4 steady beats first, then your idea.")
        settleNotes(downbeatMs - beatMs * 0.5, 0)
        val all = synchronized(notes) { notes.toList() }
        if (all.isEmpty()) return fail("Got the beat, but no notes after it. " + nothingHeard())
        val bpm = Math.round(60000.0 / beatMs).toInt().coerceIn(40, 220)
        val cents = lastCents
        // the key: which pitch classes the idea dwelt on
        val chroma = DoubleArray(12)
        for (nn in all) chroma[Math.floorMod(nn.note, 12)] += (nn.endMs - nn.startMs).coerceAtLeast(60.0)
        val (root, minor, conf) = Analysis.key(chroma)
        val scale = Theory.SCALES.indexOfFirst { it.name == if (minor) "Minor" else "Major" }.coerceAtLeast(0)
        // start the clip on the bar the idea started in
        val barMs = beatMs * 4
        var startMs = downbeatMs
        while (all.first().startMs - startMs > barMs - beatMs * 0.25) startMs += barMs
        val msPerTick = 60000.0 / bpm / Ticks.PPQ
        synchronized(notes) { notes.clear(); notes.addAll(all) }
        val (cn, len) = clipNotes(startMs, msPerTick, Ticks.STEP)
        // matching scales ignores stray notes (a pitch class held for under 6% of the idea)
        val share = DoubleArray(12); for (nn in all) share[Math.floorMod(nn.note, 12)] += nn.endMs - nn.startMs
        val tot = share.sum().coerceAtLeast(1.0)
        val sung = (0 until 12).filter { share[it] / tot >= 0.06 }.toSet().ifEmpty { all.map { Math.floorMod(it.note, 12) }.toSet() }
        val sure = sung.size >= 7 && conf >= 0.3f
        // the guess first, then every other scale that holds all the notes
        // the key guess first when it holds everything sung, then the rest
        val fitsAll = { r: Int, si: Int -> sung.all { pc -> Theory.SCALES[si].steps.any { (r + it) % 12 == pc } } }
        val cands = ((if (fitsAll(root, scale)) listOf(root to scale) else emptyList()) + candidates(all)).distinct()
        val res = ForgeResult(bpm, cn, len, cands, sung, sure && cands.firstOrNull() == (root to scale), cents, target)
        main.post { onForged?.invoke(res, "") }
    }

    /**
     * Scales for the idea, best first: every scale (any root) holding all the notes that
     * were sung come first; then, so there's always a choice, the nearest misses (scales
     * leaving out the least of what was sung). Within those: ones whose root was sung
     * (especially first or last), with the fifth and root held a while, and everyday
     * scales before rarer ones.
     */
    private fun candidates(list: List<N>): List<Pair<Int, Int>> {
        val dur = DoubleArray(12)
        for (nn in list) dur[Math.floorMod(nn.note, 12)] += (nn.endMs - nn.startMs).coerceAtLeast(60.0)
        val total = dur.sum().coerceAtLeast(1.0)
        val sung = (0 until 12).filter { dur[it] / total >= 0.06 }
        val firstPc = list.firstOrNull()?.let { Math.floorMod(it.note, 12) } ?: -1
        val lastPc = list.lastOrNull()?.let { Math.floorMod(it.note, 12) } ?: -1
        class C(val root: Int, val si: Int, val miss: Double, val score: Double)
        val out = ArrayList<C>()
        for ((si, sc) in Theory.SCALES.withIndex()) for (root in 0 until 12) {
            val pcs = sc.steps.map { (root + it) % 12 }.toSet()
            val miss = sung.filter { it !in pcs }.sumOf { dur[it] } / total
            var score = 0.0
            if (root in sung) score += 2.0
            if (root == firstPc) score += 1.0
            if (root == lastPc) score += 1.5
            score += dur[(root + 7) % 12] / total + dur[root] / total * 1.5
            score -= si * 0.12
            score -= (sc.steps.size - sung.size) * 0.05
            out += C(root, si, miss, score)
        }
        val fits = out.filter { it.miss < 1e-9 }.sortedByDescending { it.score }
        val near = out.filter { it.miss >= 1e-9 }.sortedWith(compareBy<C> { Math.round(it.miss * 20) }.thenByDescending { it.score })
        return (fits.take(14) + near.take((10 - fits.size.coerceAtMost(14)).coerceAtLeast(0))).map { it.root to it.si }
    }
}
