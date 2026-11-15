package app.foxbox.seq

import app.foxbox.audio.SR
import org.json.JSONObject
import java.util.UUID

/** A recorded audio take on one of the three mono Valley channels (the biplanes). */
class AudioClip(
    val id: String = UUID.randomUUID().toString().take(8),
    val channel: Int,
    var name: String,
) {
    /** Mono samples at the app's sample rate. Stored as a WAV next to the project. */
    @Volatile var data: FloatArray = FloatArray(0)
    @Volatile var volume = 1f
    @Volatile var muted = false
    val seconds get() = data.size / SR.toFloat()

    fun toJson() = JSONObject().apply { put("id", id); put("ch", channel); put("name", name); put("volume", volume.toDouble()); put("muted", muted) }

    companion object {
        const val CHANNELS = 3
        /** Most audio per channel: 6 minutes (18 minutes per project). */
        const val MAX_SECONDS = 6 * 60
        fun fromJson(o: JSONObject) = AudioClip(o.getString("id"), o.getInt("ch"), o.optString("name", "Take")).apply {
            volume = o.optDouble("volume", 1.0).toFloat(); muted = o.optBoolean("muted")
        }
    }
}

/** Loops an audio clip from its start tick (runs on the audio thread). */
class AudioClipPlayer(val clip: AudioClip, val startTick: Double) {
    private var pos = 0
    private var started = false

    /** Add this block of audio into `out` (the clip's channel bus). */
    fun render(out: FloatArray, n: Int, t0: Double, ticksPerFrame: Double) {
        val d = clip.data
        if (d.isEmpty()) return
        var first = 0
        if (!started) {
            if (t0 + n * ticksPerFrame <= startTick) return
            first = maxOf(0, ((startTick - t0) / ticksPerFrame).toInt())
            started = true; pos = 0
        }
        if (clip.muted) { pos = (pos + n - first) % d.size; return }
        val g = clip.volume
        for (i in first until n) {
            out[i] += d[pos] * g
            if (++pos >= d.size) pos = 0
        }
    }
}
