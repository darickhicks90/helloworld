package app.foxbox

import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.audio.SR
import app.foxbox.data.AudioIn
import app.foxbox.data.ProjectStore
import app.foxbox.seq.ArrItem
import app.foxbox.seq.Arrangement
import app.foxbox.seq.ArrangementPlayer
import app.foxbox.seq.AudioClip
import app.foxbox.seq.Clip
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport
import android.os.Handler
import android.os.Looper

/**
 * The Fire Escape: where a song is laid out in time. Each block in the session
 * and each audio track has a lane; clips and takes are laid on the lanes from the
 * crate (or recorded straight in, any length), moved, stretched, copied and pasted,
 * snapping to quarter notes, half notes or bars. Volume and pan are the very same
 * settings as in Clibbins (a clip's fader there is its fader here). UI code calls
 * this on the main thread; sound changes go to the audio thread with Audio.post.
 */
object FireEscape {
    class Lane(val id: String, val title: String, val engine: Engine?, val channel: Int) {
        val isTrack get() = channel >= 0
    }

    private val t get() = Audio.transport
    private val main = Handler(Looper.getMainLooper())
    val arr: Arrangement get() = Studio.project.arrangement
    /** True while the Fire Escape is on screen (its arrangement is what plays). */
    var open = false; private set

    /** Lanes, top to bottom: the session's blocks, then the audio tracks. */
    fun lanes(): List<Lane> = Studio.project.blocks.map { Lane(it, Audio.engine(it).title, Audio.engine(it), -1) } +
        (0 until AudioClip.CHANNELS).filter { Studio.project.planes[it] }.map { Lane("track$it", Studio.CHANNEL_NAMES[it] + " track", null, it) }

    fun lane(id: String) = lanes().firstOrNull { it.id == id }

    // ------------------------------------------------------------------ what an item plays

    fun clipOf(item: ArrItem): Clip? = Studio.clipById(item.ref)
    fun takeOf(item: ArrItem): AudioClip? = Studio.takeById(item.ref)
    fun nameOf(item: ArrItem) = clipOf(item)?.name ?: takeOf(item)?.name ?: "?"
    private val ticksPerFrame get() = Studio.project.bpm * Ticks.PPQ / 60.0 / SR
    /** How long a take is, in ticks at the current tempo. */
    fun takeTicks(c: AudioClip) = (c.data.size * ticksPerFrame).toInt()
    /** The longest an item can run (audio can't play past the end of its take). */
    fun maxLength(item: ArrItem): Int = takeOf(item)?.let { takeTicks(it) - item.offset } ?: Int.MAX_VALUE / 4

    /** The clips and takes that can go on a lane: a block's clips (and performances), or a track's takes. */
    fun crate(lane: Lane): List<Pair<String, String>> =
        if (lane.isTrack) Studio.project.audioClips.filter { it.channel == lane.channel }.map { it.id to it.name }
        else Studio.project.clips.filter { it.engine == lane.id && !it.jam }.map { it.id to it.name }

    // ------------------------------------------------------------------ editing

    var selected: String? = null
    /** The lane Rec, Keys, FX, the crate and Paste work on. */
    var selectedLane: String? = null
    fun selectedLaneObj() = selectedLane?.let { lane(it) }
    private var clipboard: List<ArrItem> = emptyList()
    val canPaste get() = clipboard.isNotEmpty()

    private fun snap(tick: Int) = Arrangement.snapTo(tick, arr.snap).coerceAtLeast(0)
    fun item(id: String?) = arr.items.firstOrNull { it.id == id }

    /** Lay a clip or take from the crate on a lane at `tick`. */
    fun place(ref: String, laneId: String, tick: Int): ArrItem? {
        val len = Studio.clipById(ref)?.lengthTicks ?: Studio.takeById(ref)?.let { takeTicks(it) } ?: return null
        val it = ArrItem(lane = laneId, ref = ref, start = snap(tick), length = len.coerceAtLeast(Ticks.STEP))
        arr.items += it; selected = it.id
        changed(); return it
    }

    fun move(item: ArrItem, tick: Int, laneId: String = item.lane) {
        item.start = snap(tick)
        if (laneId != item.lane && fits(item, laneId)) item.lane = laneId
        changed(save = false)
    }

    fun resize(item: ArrItem, length: Int) {
        val end = snap(item.start + length)
        item.length = (end - item.start).coerceIn(Ticks.STEP, maxLength(item))
        changed(save = false)
    }

    /** Trim the front: the item starts later and plays from further into its source. */
    fun trimStart(item: ArrItem, tick: Int) {
        val ns = snap(tick).coerceIn(item.start - item.offset, item.end - Ticks.STEP)
        val d = ns - item.start
        item.start = ns; item.offset += d; item.length -= d
        changed(save = false)
    }

    /** An item can move to another lane of the same block, or between audio tracks of… the same track. */
    private fun fits(item: ArrItem, laneId: String): Boolean {
        val c = clipOf(item); val a = takeOf(item)
        return (c != null && c.engine == laneId) || (a != null && laneId == "track${a.channel}")
    }

    fun delete(item: ArrItem) { arr.items.remove(item); if (selected == item.id) selected = null; changed() }

    fun duplicate(item: ArrItem) { val c = item.copy(newStart = item.end); arr.items += c; selected = c.id; changed() }

    /** Split an item in two at `tick`. */
    fun split(item: ArrItem, tick: Int) {
        val at = snap(tick)
        if (at <= item.start || at >= item.end) return Studio.message("Put the playhead inside the clip to split it")
        val right = ArrItem(lane = item.lane, ref = item.ref, start = at, length = item.end - at, offset = item.offset + (at - item.start))
        item.length = at - item.start
        arr.items += right; selected = right.id
        changed()
    }

    fun copy(item: ArrItem) { clipboard = listOf(item.copy()); Studio.message("Copied ${nameOf(item)}: tap a spot, then Paste") }

    /** Paste at `tick` (on `laneId` if it suits the clip, else on its own lane). */
    fun paste(laneId: String?, tick: Int) {
        if (clipboard.isEmpty()) return Studio.message("Copy a clip first")
        val first = clipboard.minOf { it.start }
        clipboard.forEach { c ->
            val n = c.copy(newStart = snap(tick) + (c.start - first))
            if (laneId != null && fits(n, laneId)) n.lane = laneId
            arr.items += n; selected = n.id
        }
        changed()
    }

    fun toggleMute(laneId: String) { if (!arr.muted.remove(laneId)) arr.muted += laneId; changed() }

    fun setSnap(s: Int) { arr.snap = s.coerceIn(0, 3); Studio.save(); Studio.changed() }

    /** The loop region: the selected item's span, or four bars from the playhead. */
    fun toggleLoop() {
        if (arr.loopOn) { arr.loopOn = false }
        else {
            val it = item(selected)
            if (it != null) { arr.loopStart = it.start; arr.loopEnd = it.end }
            else if (arr.loopEnd <= arr.loopStart || arr.loopStart != arr.playhead) { arr.loopStart = arr.playhead; arr.loopEnd = arr.playhead + Ticks.BAR * 4 }
            arr.loopOn = true
        }
        syncLoop(); Studio.save(); Studio.changed()
    }

    fun setLoop(a: Int, b: Int) { arr.loopStart = snap(minOf(a, b)); arr.loopEnd = maxOf(arr.loopStart + Ticks.PPQ, snap(maxOf(a, b))); syncLoop(); Studio.changed() }

    private fun syncLoop() { val a = arr; Audio.post { t.loopOn = a.loopOn; t.loopStart = a.loopStart.toDouble(); t.loopEnd = a.loopEnd.toDouble() } }

    /** Something changed: rebuild what the audio thread plays and save (now, or soon). */
    fun changed(save: Boolean = true) {
        rebuild()
        if (save) Studio.save()
        Studio.changed()
    }

    /** After a drag ends. */
    fun committed() { Studio.save(); Studio.changed() }

    // ------------------------------------------------------------------ playing

    private var builtSig = ""

    /** Build a fresh player from the arrangement for the audio thread (when on screen). */
    fun rebuild() {
        if (!open) return
        val lanes = lanes().associateBy { it.id }
        val midi = ArrayList<ArrangementPlayer.Midi>(); val audio = ArrayList<ArrangementPlayer.Audio>()
        for (it in arr.items) {
            if (it.lane in arr.muted) continue
            val lane = lanes[it.lane] ?: continue
            val c = clipOf(it); val a = takeOf(it)
            if (c != null && lane.engine != null) midi += ArrangementPlayer.Midi(c, lane.engine, it.start, it.end, it.offset)
            else if (a != null) audio += ArrangementPlayer.Audio(a, it.start, it.end, (it.offset).coerceAtLeast(0))
        }
        val p = ArrangementPlayer(midi.toTypedArray(), audio.toTypedArray())
        builtSig = signature()
        Audio.post { t.arrangement = p }
    }

    private fun signature() = arr.items.joinToString { it.id + it.lane + it.ref + it.start + it.length + it.offset } + arr.muted.joinToString() +
        Studio.project.blocks.joinToString() + Studio.project.bpm

    /** Keep the player in step after undo, a tempo change and the like (call on every Studio change). */
    fun sync() { if (open && signature() != builtSig) rebuild() }

    fun enter() {
        open = true
        selected = null
        Audio.post { t.stop() }
        rebuild(); syncLoop()
    }

    fun leave() {
        if (!open) return
        open = false
        Audio.post { t.stop(); t.arrangement = null; t.loopOn = false }
    }

    val playing get() = t.playing
    /** Where the music is now (the playhead while stopped). */
    val position: Double get() = if (t.playing && t.tick >= 0) t.tick else arr.playhead.toDouble()

    fun play() {
        if (t.playing) { stop(); return }
        val from = arr.playhead.toDouble()
        Audio.post { t.playFrom(from) }
        Studio.changed()
    }

    fun stop() {
        Audio.post { t.stop() }
        Studio.changed()
    }

    /** Back to the start (or to where the playhead was, if already there). */
    fun rewind() { setPlayhead(0) }

    fun setPlayhead(tick: Int) {
        arr.playhead = snap(tick)
        val at = arr.playhead.toDouble()
        if (t.playing) Audio.post { t.seek(at) }
        Studio.save(); Studio.changed()
    }

    // ------------------------------------------------------------------ recording (any length)

    val recording get() = t.freeRec || (t.audioFree && t.audioRec != Transport.Rec.OFF)

    /** Record on a lane: a block's performance (notes and pad moves), or a take on an audio track. Again to stop. */
    fun record(lane: Lane) {
        if (t.freeRec) { Audio.post { t.recordFree(null, 0.0) }; return }
        if (t.audioFree && t.audioRec != Transport.Rec.OFF) { Studio.recordAudio(0.0); return }
        val from = arr.playhead.toDouble()
        if (lane.isTrack) {
            Studio.armed.fill(false); Studio.armed[lane.channel] = true; Studio.refreshLive()
            Studio.recordAudio(from)
        } else {
            val e = lane.engine ?: return
            Audio.post { t.recordFree(e, from) }
            Studio.message(if (t.playing) "Recording ${e.title}: play it from the Studio keys, pads or a MIDI keyboard" else "One bar of count-in, then recording ${e.title}")
        }
        Studio.changed()
    }

    /** A block performance came back from the transport: keep it as a clip and lay it where it was played. */
    fun performance(clips: List<Clip>, startTick: Double) = main.post {
        for (c in clips) {
            val e = Audio.engine(c.engine)
            val n = Studio.project.clips.count { it.engine == c.engine && it.arr } + 1
            c.arr = true; c.name = "${e.title} take $n"
            Studio.project.clips += c
            runCatching { ProjectStore.saveClipMidi(Studio.project, c) }
            arr.items += ArrItem(lane = c.engine, ref = c.id, start = startTick.toInt().coerceAtLeast(0), length = c.lengthTicks)
        }
        if (clips.isNotEmpty()) Studio.message("Laid " + clips.joinToString { it.name } + " on the Fire Escape")
        changed()
    }

    /** An audio take on the Fire Escape finished: kept whole (no rounding to bars) and laid where it started. */
    fun takeEnded(input: AudioIn, chans: List<Int>, startTick: Double) {
        val tpf = ticksPerFrame
        Thread {
            val takes = input.finish()
            val made = ArrayList<AudioClip>()
            for (ch in chans) {
                val raw = takes[app.foxbox.audio.AudioPrefs.planeInput(ch).coerceIn(0, 2)]
                if (raw.size < SR / 4) continue
                val room = ((AudioClip.MAX_SECONDS - Studio.secondsOn(ch)) * SR).toInt()
                if (room <= 0) { Studio.message("${Studio.CHANNEL_NAMES[ch]} is full (6 minutes)"); continue }
                val n = Studio.project.audioClips.count { it.channel == ch && it.arr } + made.count { it.channel == ch } + 1
                val c = AudioClip(channel = ch, name = "${Studio.CHANNEL_NAMES[ch]} long take $n").apply { data = raw.copyOf(minOf(raw.size, room)); arr = true }
                ProjectStore.saveTake(Studio.project, c)
                made += c
            }
            main.post {
                for (c in made) {
                    Studio.project.audioClips += c
                    arr.items += ArrItem(lane = "track${c.channel}", ref = c.id, start = startTick.toInt().coerceAtLeast(0), length = (c.data.size * tpf).toInt().coerceAtLeast(Ticks.STEP))
                }
                Studio.message(if (made.isEmpty()) "No audio came in. Check the input in Audio settings." else "Laid " + made.joinToString { it.name } + " on the Fire Escape")
                Studio.refreshLive()
                changed()
            }
        }.start()
    }
}
