package app.foxbox.seq

import app.foxbox.audio.Engine
import app.foxbox.audio.Math
import app.foxbox.json.JSONArray
import app.foxbox.json.JSONObject

/**
 * One clip laid on the Fire Escape: which lane it's on (a block's id, or
 * "track0".."track2" for the audio tracks), what it plays (a clip or take id),
 * where it starts, how long it runs and how far into its source it begins.
 * A MIDI clip repeats to fill its length; an audio take plays once.
 */
class ArrItem(
    val id: String = app.foxbox.audio.newId(),
    var lane: String,
    var ref: String,
    var start: Int,
    var length: Int,
    var offset: Int = 0,
) {
    val end get() = start + length
    fun copy(newStart: Int = start, newLane: String = lane) = ArrItem(lane = newLane, ref = ref, start = newStart, length = length, offset = offset)
    fun toJson() = JSONObject().apply { put("id", id); put("lane", lane); put("ref", ref); put("start", start); put("len", length); put("off", offset) }

    companion object {
        fun fromJson(o: JSONObject) = ArrItem(o.getString("id"), o.getString("lane"), o.getString("ref"), o.getInt("start"), o.getInt("len"), o.optInt("off"))
    }
}

/** A song laid out along the Fire Escape, with an optional loop region and the grid it snaps to. */
class Arrangement {
    val items = mutableListOf<ArrItem>()
    var loopOn = false
    var loopStart = 0
    var loopEnd = Ticks.BAR * 4
    /** 0 free, 1 quarter notes, 2 half notes, 3 bars. */
    var snap = 3
    /** Where ▶ starts from. */
    var playhead = 0
    /** Lanes muted on the Fire Escape. */
    val muted = mutableSetOf<String>()

    val lengthTicks get() = items.maxOfOrNull { it.end } ?: 0

    fun toJson() = JSONObject().apply {
        put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        put("loopOn", loopOn); put("loopStart", loopStart); put("loopEnd", loopEnd); put("snap", snap); put("playhead", playhead)
        put("muted", JSONArray().apply { muted.forEach { put(it) } })
    }

    fun load(o: JSONObject?) {
        items.clear(); muted.clear()
        if (o == null) { loopOn = false; loopStart = 0; loopEnd = Ticks.BAR * 4; snap = 3; playhead = 0; return }
        o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) items += ArrItem.fromJson(a.getJSONObject(i)) }
        loopOn = o.optBoolean("loopOn"); loopStart = o.optInt("loopStart"); loopEnd = o.optInt("loopEnd", Ticks.BAR * 4)
        snap = o.optInt("snap", 3); playhead = o.optInt("playhead")
        o.optJSONArray("muted")?.let { a -> for (i in 0 until a.length()) muted += a.getString(i) }
    }

    companion object {
        val SNAP_NAMES = listOf("Free", "1/4", "1/2", "Bar")
        val SNAP_TICKS = intArrayOf(1, Ticks.PPQ, Ticks.PPQ * 2, Ticks.BAR)
        fun snapTo(t: Int, snap: Int): Int {
            val g = SNAP_TICKS[snap.coerceIn(0, 3)]
            return if (g <= 1) t else Math.round(t / g.toFloat()) * g
        }
    }
}

/**
 * Plays the Fire Escape on the audio thread: notes from the MIDI items into their
 * blocks, and the audio items into their tracks. Holds an immutable snapshot (a new
 * player is built whenever the arrangement changes).
 */
class ArrangementPlayer(private val midi: Array<Midi>, private val audio: Array<Audio>) {
    class Midi(val clip: Clip, val engine: Engine, val start: Int, val end: Int, val offset: Int) {
        val notes = clip.notes.sortedBy { it.tick }.toTypedArray()
        val xy = clip.xy.sortedBy { it.tick }.toTypedArray()
        val len = clip.lengthTicks.coerceAtLeast(Ticks.STEP)
    }
    class Audio(val clip: AudioClip, val start: Int, val end: Int, val offset: Int)

    private val offKey = IntArray(512); private val offAt = DoubleArray(512); private val offEngine = arrayOfNulls<Engine>(512)
    private var offs = 0

    /** Process the transport's tick range [t0, t1). */
    fun process(t0: Double, t1: Double) {
        var i = 0
        while (i < offs) {
            if (offAt[i] < t1) { offEngine[i]?.noteOff(offKey[i]); offs--; offKey[i] = offKey[offs]; offAt[i] = offAt[offs]; offEngine[i] = offEngine[offs] } else i++
        }
        for (k in midi.indices) {
            val m = midi[k]
            if (t1 <= m.start || t0 >= m.end) continue
            // the item's own time: where in the clip (repeating) this block falls
            val a = maxOf(t0, m.start.toDouble()) - m.start + m.offset
            val b = minOf(t1, m.end.toDouble()) - m.start + m.offset
            var loop = Math.floorDiv(a.toLong(), m.len.toLong())
            while (loop * m.len < b) {
                val base = loop * m.len
                for (n in m.notes.indices) {
                    val note = m.notes[n]
                    val t = (base + note.tick).toDouble()
                    if (t < a || t >= b || m.clip.muted) continue
                    val key = 0x50000000 or ((k and 0x3FF) shl 16) or (n and 0xFFFF)
                    m.engine.noteOn(key, note.note, note.vel * m.clip.volume)
                    if (offs < offKey.size) {
                        // notes end with the item, at the latest
                        offKey[offs] = key; offEngine[offs] = m.engine
                        offAt[offs] = minOf(m.start - m.offset + t + maxOf(1, note.dur), m.end.toDouble() - 0.5)
                        offs++
                    }
                }
                loop++
            }
            val pad = m.engine.xy
            if (m.xy.isNotEmpty() && !pad.touching) {
                val local = (b.toLong() % m.len).toInt()
                var cur = m.xy[m.xy.size - 1]
                for (p in m.xy.indices) { val pt = m.xy[p]; if (pt.tick <= local) cur = pt else break }
                pad.x = cur.x; pad.y = cur.y; pad.automated = true
            }
        }
    }

    /** Blocks take the pan of the items playing on them. */
    fun addPan(t: Double) {
        for (m in midi) if (t >= m.start && t < m.end && !m.clip.muted) { m.engine.panSum += m.clip.pan; m.engine.panCount++ }
    }

    /** Add the audio items into their track buses for the block starting at `t0`. */
    fun renderAudio(busL: Array<FloatArray>, busR: Array<FloatArray>, n: Int, t0: Double, ticksPerFrame: Double) {
        val t1 = t0 + n * ticksPerFrame
        for (a in audio) {
            if (t1 <= a.start || t0 >= a.end || a.clip.muted) continue
            val d = a.clip.data
            if (d.isEmpty()) continue
            val ch = a.clip.channel.coerceIn(0, busL.size - 1)
            val g = a.clip.volume; val p = a.clip.pan
            val gl = g * minOf(1f, 1f - p); val gr = g * minOf(1f, 1f + p)
            val l = busL[ch]; val r = busR[ch]
            for (i in 0 until n) {
                val t = t0 + i * ticksPerFrame
                if (t < a.start || t >= a.end) continue
                val pos = ((t - a.start + a.offset) / ticksPerFrame).toInt()
                if (pos < 0 || pos >= d.size) continue
                // a short fade at the item's edges, so cuts never click
                val edge = minOf(t - a.start, a.end - t) / ticksPerFrame
                val f = if (edge < 96) (edge / 96).toFloat() else 1f
                val v = d[pos] * f
                l[i] += v * gl; r[i] += v * gr
            }
        }
    }

    fun releaseAll() {
        for (k in 0 until offs) offEngine[k]?.noteOff(offKey[k])
        offs = 0
        for (m in midi) m.engine.xy.automated = false
    }
}
