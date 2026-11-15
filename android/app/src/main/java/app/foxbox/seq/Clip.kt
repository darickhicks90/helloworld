package app.foxbox.seq

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.roundToInt

/** A recorded MIDI loop for one engine: notes plus XY-pad automation. */
class Clip(
    val id: String = UUID.randomUUID().toString().take(8),
    val engine: String,
    var name: String,
    var lengthTicks: Int,
    val notes: MutableList<Note> = mutableListOf(),
    val xy: MutableList<XyPoint> = mutableListOf(),
) {
    data class Note(val tick: Int, val dur: Int, val note: Int, val vel: Float)
    data class XyPoint(val tick: Int, val x: Float, val y: Float)

    var inValley = false
    var variant = 0
    /** Valley mixer: clip volume (scales note velocity) and mute. */
    @Volatile var volume = 1f
    @Volatile var muted = false

    val bars get() = lengthTicks / Ticks.BAR.toFloat()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("engine", engine); put("name", name); put("len", lengthTicks)
        put("valley", inValley); put("variant", variant); put("volume", volume.toDouble()); put("muted", muted)
        put("notes", JSONArray().apply { notes.forEach { put(JSONArray(listOf(it.tick, it.dur, it.note, it.vel.toDouble()))) } })
        put("xy", JSONArray().apply { xy.forEach { put(JSONArray(listOf(it.tick, it.x.toDouble(), it.y.toDouble()))) } })
    }

    companion object {
        fun fromJson(o: JSONObject) = Clip(o.getString("id"), o.getString("engine"), o.getString("name"), o.getInt("len")).apply {
            inValley = o.optBoolean("valley"); variant = o.optInt("variant")
            volume = o.optDouble("volume", 1.0).toFloat(); muted = o.optBoolean("muted")
            val n = o.getJSONArray("notes")
            for (i in 0 until n.length()) n.getJSONArray(i).let { notes += Note(it.getInt(0), it.getInt(1), it.getInt(2), it.getDouble(3).toFloat()) }
            val a = o.optJSONArray("xy") ?: JSONArray()
            for (i in 0 until a.length()) a.getJSONArray(i).let { xy += XyPoint(it.getInt(0), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
        }
    }

    /** Standard MIDI File (format 0): notes, with XY automation as CC74 (X) and CC1 (Y). */
    fun toMidiFile(bpm: Float, channel: Int = 0): ByteArray {
        data class Ev(val tick: Int, val order: Int, val bytes: ByteArray)
        val evs = mutableListOf<Ev>()
        val ch = channel and 15
        notes.forEach {
            val v = (it.vel * 127).roundToInt().coerceIn(1, 127)
            evs += Ev(it.tick, 1, byteArrayOf((0x90 or ch).toByte(), it.note.toByte(), v.toByte()))
            evs += Ev(it.tick + it.dur, 0, byteArrayOf((0x80 or ch).toByte(), it.note.toByte(), 0))
        }
        xy.forEach {
            evs += Ev(it.tick, 2, byteArrayOf((0xB0 or ch).toByte(), 74, (it.x * 127).roundToInt().coerceIn(0, 127).toByte()))
            evs += Ev(it.tick, 2, byteArrayOf((0xB0 or ch).toByte(), 1, (it.y * 127).roundToInt().coerceIn(0, 127).toByte()))
        }
        evs.sortWith(compareBy({ it.tick }, { it.order }))

        val track = ByteArrayOutputStream()
        fun vlq(v: Int) {
            var buf = v and 0x7F; var x = v ushr 7
            while (x > 0) { buf = (buf shl 8) or ((x and 0x7F) or 0x80); x = x ushr 7 }
            while (true) { track.write(buf and 0xFF); if (buf and 0x80 != 0) buf = buf ushr 8 else break }
        }
        val usPerBeat = (60_000_000 / bpm).roundToInt()
        vlq(0); track.write(byteArrayOf(0xFF.toByte(), 0x51, 3, (usPerBeat shr 16).toByte(), (usPerBeat shr 8).toByte(), usPerBeat.toByte()))
        var last = 0
        evs.forEach { vlq(it.tick - last); last = it.tick; track.write(it.bytes) }
        vlq(maxOf(0, lengthTicks - last)); track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))

        val out = ByteArrayOutputStream()
        fun int32(v: Int) = out.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        out.write("MThd".toByteArray()); int32(6)
        out.write(byteArrayOf(0, 0, 0, 1, (Ticks.PPQ shr 8).toByte(), Ticks.PPQ.toByte()))
        out.write("MTrk".toByteArray()); int32(track.size()); out.write(track.toByteArray())
        return out.toByteArray()
    }
}

object Ticks {
    const val PPQ = 96
    const val STEP = PPQ / 4
    const val BAR = PPQ * 4
}

/**
 * Plays one clip in a loop, locked to the transport. Built and run on the
 * audio thread; holds an immutable snapshot of the clip's events.
 */
class ClipPlayer(val clip: Clip, val engine: app.foxbox.audio.Engine, val startTick: Double, slot: Int) {
    private val on = clip.notes.sortedBy { it.tick }
    private val xy = clip.xy.sortedBy { it.tick }
    private val len = clip.lengthTicks.coerceAtLeast(Ticks.STEP)
    private val keyBase = 0x40000000 or ((slot and 0x3FF) shl 16)
    private val offKey = IntArray(64); private val offAt = DoubleArray(64); private var offs = 0
    var stopping = false

    /** Process the global tick range [t0, t1). */
    fun process(t0: Double, t1: Double) {
        // Note-offs that are due.
        var i = 0
        while (i < offs) {
            if (offAt[i] < t1) { engine.noteOff(offKey[i]); offs--; offKey[i] = offKey[offs]; offAt[i] = offAt[offs] } else i++
        }
        if (stopping || t1 <= startTick) return
        val a = maxOf(t0, startTick) - startTick
        val b = t1 - startTick
        val loopA = Math.floorDiv(a.toLong(), len.toLong())
        var loop = loopA
        while (loop * len < b) {
            val base = loop * len
            for (idx in on.indices) {
                val n = on[idx]
                val t = (base + n.tick).toDouble()
                if (t >= a && t < b) {
                    if (clip.muted) continue
                    val key = keyBase or (idx and 0xFFFF)
                    engine.noteOn(key, n.note, n.vel * clip.volume)
                    if (offs < offKey.size) { offKey[offs] = key; offAt[offs] = startTick + t + maxOf(1, n.dur); offs++ }
                }
            }
            loop++
        }
        val pad = engine.xy
        if (xy.isNotEmpty() && !pad.touching) {
            val local = (b.toLong() % len).toInt()
            var cur = xy[xy.size - 1]
            for (i in xy.indices) { val pt = xy[i]; if (pt.tick <= local) cur = pt else break }
            pad.x = cur.x; pad.y = cur.y; pad.automated = true
        }
    }

    fun releaseAll() {
        for (k in 0 until offs) engine.noteOff(offKey[k])
        offs = 0
    }
}
