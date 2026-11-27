package app.foxbox.data

import android.content.Context
import app.foxbox.fx.Wraith
import org.json.JSONObject
import java.io.File

/** Wraith presets: a handful that come with the app, plus the person's own (kept as small JSON files). */
object WraithPresets {
    class Preset(val name: String, val params: JSONObject, val factory: Boolean, val file: File? = null)

    private lateinit var dir: File
    fun init(c: Context) { dir = File(c.filesDir, "wraith").apply { mkdirs() } }

    private fun p(name: String, vararg kv: Pair<String, Number>) = Preset(name, JSONObject().apply { kv.forEach { (k, v) -> put(k, v.toDouble()) } }, true)

    /** Choices are indexes (see Wraith's lists); notes are MIDI numbers. */
    private val factory = listOf(
        p("Haunted Choir", "on" to 1, "low" to 45, "high" to 81, "fb" to 0.82, "mix" to 0.5, "sens" to -36, "dwell" to 2.2, "focus" to 0.7, "follow" to 0.8,
            "pitch" to 1, "pitchMix" to 0.3, "tone" to 1, "toneAmt" to 0.4, "rhythm" to 0, "delay" to 0, "verb" to 3, "verbSize" to 0.8, "verbMix" to 0.55),
        p("Bloom Pad", "on" to 1, "low" to 40, "high" to 76, "fb" to 0.74, "mix" to 0.6, "sens" to -42, "dwell" to 4.0, "focus" to 0.45, "follow" to 0.6,
            "pitch" to 0, "tone" to 1, "toneAmt" to 0.6, "rhythm" to 3, "rhythmRate" to 7, "rhythmDepth" to 0.5, "delay" to 1, "delayTime" to 5, "delayFb" to 0.4, "delayMix" to 0.3, "verb" to 2, "verbSize" to 0.7, "verbMix" to 0.45),
        p("Feedback Strings", "on" to 1, "low" to 52, "high" to 88, "fb" to 0.95, "mix" to 0.45, "sens" to -30, "dwell" to 1.2, "focus" to 0.9, "follow" to 0.9,
            "pitch" to 0, "tone" to 2, "toneAmt" to 0.4, "rhythm" to 0, "delay" to 2, "delayTime" to 3, "delayFb" to 0.35, "delayMix" to 0.25, "verb" to 2, "verbSize" to 0.5, "verbMix" to 0.35),
        p("Shimmer Ghost", "on" to 1, "low" to 48, "high" to 84, "fb" to 0.78, "mix" to 0.55, "sens" to -38, "dwell" to 2.8, "focus" to 0.65, "follow" to 0.7,
            "pitch" to 5, "pitchMix" to 0.45, "tone" to 0, "rhythm" to 0, "delay" to 0, "verb" to 4, "verbSize" to 0.9, "verbMix" to 0.6),
        p("Pulse Engine", "on" to 1, "low" to 36, "high" to 72, "fb" to 0.88, "mix" to 0.55, "sens" to -34, "dwell" to 3.0, "focus" to 0.55, "follow" to 0.5,
            "pitch" to 2, "pitchMix" to 0.35, "tone" to 4, "toneAmt" to 0.45, "rhythm" to 2, "rhythmRate" to 1, "rhythmDepth" to 0.85, "delay" to 4, "delayTime" to 5, "delayFb" to 0.3, "delayMix" to 0.3, "verb" to 1, "verbSize" to 0.4, "verbMix" to 0.3),
        p("Broken Radio", "on" to 1, "low" to 50, "high" to 86, "fb" to 0.7, "mix" to 0.5, "sens" to -32, "dwell" to 1.0, "focus" to 0.5, "follow" to 0.8,
            "pitch" to 0, "tone" to 3, "toneAmt" to 0.7, "rhythm" to 4, "rhythmRate" to 2, "rhythmDepth" to 0.6, "delay" to 1, "delayTime" to 4, "delayFb" to 0.55, "delayMix" to 0.35, "verb" to 1, "verbSize" to 0.3, "verbMix" to 0.25),
        p("Deep Drone", "on" to 1, "low" to 28, "high" to 60, "fb" to 1.0, "mix" to 0.5, "sens" to -44, "dwell" to 7.0, "focus" to 0.8, "follow" to 0.3,
            "pitch" to 2, "pitchMix" to 0.5, "tone" to 1, "toneAmt" to 0.7, "rhythm" to 5, "rhythmRate" to 8, "rhythmDepth" to 0.4, "delay" to 0, "verb" to 3, "verbSize" to 1.0, "verbMix" to 0.5),
    )

    fun list(): List<Preset> = factory + (dir.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name }?.mapNotNull { f ->
        runCatching { val o = JSONObject(f.readText()); Preset(o.optString("name", f.nameWithoutExtension), o.getJSONObject("params"), false, f) }.getOrNull()
    } ?: emptyList())

    /** Factory presets set what they name (anything else goes back to its default); your own set everything. */
    fun apply(w: Wraith, p: Preset) {
        w.p.all.values.forEach { it.set(if (p.params.has(it.id)) p.params.getDouble(it.id).toFloat() else it.def) }
    }

    fun save(w: Wraith, name: String) {
        val f = File(dir, ProjectStore.safe(name) + ".json")
        f.writeText(JSONObject().put("name", name).put("params", w.p.save()).toString())
    }

    fun delete(p: Preset) { p.file?.delete() }
}
