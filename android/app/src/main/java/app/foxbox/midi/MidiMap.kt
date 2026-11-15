package app.foxbox.midi

import android.content.Context
import org.json.JSONObject

/**
 * MIDI CC mappings, kept on the device. A mapping links a controller
 * (channel + CC number) to a target:
 *   "param:<block>.<param>"  a block parameter
 *   "xy:x" / "xy:y"          the current XY pad
 *   "block:volume" / "block:reverb"  the current block's mix
 *   "transport:play" / "transport:stop" / "transport:record"
 */
object MidiMap {
    private lateinit var c: Context
    val map = LinkedHashMap<String, String>()
    /** Target waiting for the next CC (MIDI learn), or null. */
    @Volatile var learning: String? = null

    fun init(context: Context) {
        c = context.applicationContext
        val o = runCatching { JSONObject(c.getSharedPreferences("midi", Context.MODE_PRIVATE).getString("map", "{}")!!) }.getOrDefault(JSONObject())
        if (o.length() == 0) standard() else o.keys().forEach { map[it] = o.getString(it) }
    }

    private fun persist() = c.getSharedPreferences("midi", Context.MODE_PRIVATE).edit().putString("map", JSONObject(map as Map<*, *>).toString()).apply()

    fun key(ch: Int, cc: Int) = "$ch:$cc"

    /** A sensible starting point for most keyboards and pad controllers (any channel = 16). */
    fun standard() {
        map.clear()
        map[key(16, 1)] = "xy:y"; map[key(16, 74)] = "xy:x"; map[key(16, 71)] = "xy:x"
        map[key(16, 7)] = "block:volume"; map[key(16, 91)] = "block:reverb"
        map[key(16, 115)] = "transport:play"; map[key(16, 116)] = "transport:stop"; map[key(16, 117)] = "transport:record"
        persist()
    }

    fun target(ch: Int, cc: Int): String? = map[key(ch, cc)] ?: map[key(16, cc)]

    /** Called for every CC; returns true if it was used for learning. */
    fun learn(ch: Int, cc: Int): Boolean {
        val t = learning ?: return false
        map.entries.removeAll { it.value == t }
        map[key(ch, cc)] = t
        learning = null
        persist()
        return true
    }

    fun remove(k: String) { map.remove(k); persist() }
    fun describe(k: String): String { val (ch, cc) = k.split(":"); return "CC $cc" + if (ch == "16") " (any channel)" else " (ch ${ch.toInt() + 1})" }
}
