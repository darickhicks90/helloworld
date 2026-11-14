package app.foxbox.audio

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Fmt { NUM, HZ, SEC, PCT, ST, CT, X, NOTE, CHOICE }

/**
 * One tweakable sound-design parameter. The audio thread reads [value];
 * the UI and MIDI set it. Listeners may fire from any thread.
 */
class Param(
    val id: String,
    val name: String,
    val min: Float,
    val max: Float,
    val def: Float,
    val exp: Boolean = false,
    val fmt: Fmt = Fmt.NUM,
    val choices: List<String> = emptyList(),
    val step: Float = 0f,
) {
    @Volatile var value = def
        private set
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    val isChoice get() = choices.isNotEmpty()
    val index get() = value.roundToInt()
    val on get() = value >= 0.5f

    fun set(v: Float) {
        var x = v.coerceIn(min, max)
        if (step > 0f) x = (x / step).roundToInt() * step
        if (x == value) return
        value = x
        listeners.forEach { it() }
    }

    /** The value this param would have at normalised position n (0..1). */
    fun valueAt(n: Float): Float {
        val c = n.coerceIn(0f, 1f)
        return if (exp) min * (max / min).pow(c) else min + c * (max - min)
    }

    var norm: Float
        get() = if (exp) (ln(value / min) / ln(max / min)) else (value - min) / (max - min)
        set(n) {
            val c = n.coerceIn(0f, 1f)
            set(if (exp) min * (max / min).pow(c) else min + c * (max - min))
        }

    fun text(): String = when (fmt) {
        Fmt.CHOICE -> choices.getOrElse(index) { "" }
        Fmt.HZ -> if (value >= 1000f) "%.1fk".format(value / 1000f) else "${value.roundToInt()}"
        Fmt.SEC -> if (value < 1f) "${(value * 1000).roundToInt()}ms" else "%.2fs".format(value)
        Fmt.PCT -> "${(value * 100).roundToInt()}%"
        Fmt.ST -> (if (value > 0) "+" else "") + (if (step > 0f) "${value.roundToInt()}" else "%.1f".format(value)) + "st"
        Fmt.CT -> "${value.roundToInt()}ct"
        Fmt.X -> "%.2f×".format(value)
        Fmt.NOTE -> NOTE_NAMES[Math.floorMod(index, 12)] + (Math.floorDiv(index, 12) - 1)
        Fmt.NUM -> if (kotlin.math.abs(value) >= 10f) "${value.roundToInt()}" else "%.2f".format(value)
    }
}

private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** An ordered set of params, grouped into named sections for the UI. */
class Params {
    val all = LinkedHashMap<String, Param>()
    val sections = LinkedHashMap<String, MutableList<Param>>()
    private var section = "Main"

    fun section(name: String) { section = name }

    private fun add(p: Param): Param {
        all[p.id] = p
        sections.getOrPut(section) { mutableListOf() }.add(p)
        return p
    }

    fun num(id: String, name: String, min: Float, max: Float, def: Float, fmt: Fmt = Fmt.NUM, exp: Boolean = false, step: Float = 0f) =
        add(Param(id, name, min, max, def, exp, fmt, step = step))

    fun choice(id: String, name: String, choices: List<String>, def: Int = 0) =
        add(Param(id, name, 0f, (choices.size - 1).toFloat(), def.toFloat(), fmt = Fmt.CHOICE, choices = choices, step = 1f))

    fun toggle(id: String, name: String, def: Boolean = false) = choice(id, name, listOf("Off", "On"), if (def) 1 else 0)

    operator fun get(id: String): Param = all.getValue(id)

    fun save(): JSONObject = JSONObject().apply { all.values.forEach { put(it.id, it.value.toDouble()) } }
    fun load(o: JSONObject?) {
        all.values.forEach { it.set(if (o != null && o.has(it.id)) o.getDouble(it.id).toFloat() else it.def) }
    }
}
