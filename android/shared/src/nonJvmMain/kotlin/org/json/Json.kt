package org.json

/**
 * A small stand-in for Android's org.json (JSONObject / JSONArray), so the
 * shared core's save/load code runs unchanged on iOS. Numbers are kept as
 * Double (or Long when whole), like the original's behaviour for our data.
 */
class JSONException(msg: String) : RuntimeException(msg)

class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(text: String) : this() {
        val v = Parser(text).value()
        if (v !is JSONObject) throw JSONException("Not a JSON object")
        map.putAll(v.map)
    }

    fun put(name: String, value: Any?): JSONObject { map[name] = wrap(value); return this }
    fun put(name: String, value: Boolean): JSONObject { map[name] = value; return this }
    fun put(name: String, value: Int): JSONObject { map[name] = value.toLong(); return this }
    fun put(name: String, value: Long): JSONObject { map[name] = value; return this }
    fun put(name: String, value: Double): JSONObject { map[name] = value; return this }
    fun has(name: String) = map.containsKey(name)
    fun isNull(name: String) = map[name] == null
    fun remove(name: String): Any? = map.remove(name)
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun length() = map.size
    fun opt(name: String): Any? = map[name]
    fun get(name: String): Any = map[name] ?: throw JSONException("No value for $name")

    fun getString(name: String): String = get(name).let { if (it is String) it else it.toString() }
    fun optString(name: String): String = optString(name, "")
    fun optString(name: String, fallback: String): String = map[name]?.let { if (it is String) it else it.toString() } ?: fallback
    fun getInt(name: String): Int = num(get(name), name).toInt()
    fun optInt(name: String): Int = optInt(name, 0)
    fun optInt(name: String, fallback: Int): Int = (map[name] as? Number)?.toInt() ?: (map[name] as? String)?.toDoubleOrNull()?.toInt() ?: fallback
    fun getLong(name: String): Long = num(get(name), name).toLong()
    fun optLong(name: String): Long = optLong(name, 0L)
    fun optLong(name: String, fallback: Long): Long = (map[name] as? Number)?.toLong() ?: fallback
    fun getDouble(name: String): Double = num(get(name), name)
    fun optDouble(name: String): Double = optDouble(name, Double.NaN)
    fun optDouble(name: String, fallback: Double): Double = (map[name] as? Number)?.toDouble() ?: (map[name] as? String)?.toDoubleOrNull() ?: fallback
    fun getBoolean(name: String): Boolean = when (val v = get(name)) { is Boolean -> v; is String -> v.equals("true", true); else -> throw JSONException("$name is not a boolean") }
    fun optBoolean(name: String): Boolean = optBoolean(name, false)
    fun optBoolean(name: String, fallback: Boolean): Boolean = when (val v = map[name]) { is Boolean -> v; is String -> v.equals("true", true); else -> fallback }
    fun getJSONObject(name: String): JSONObject = get(name) as? JSONObject ?: throw JSONException("$name is not an object")
    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject
    fun getJSONArray(name: String): JSONArray = get(name) as? JSONArray ?: throw JSONException("$name is not an array")
    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray

    override fun toString(): String = StringBuilder().also { write(it) }.toString()
    internal fun write(sb: StringBuilder) {
        sb.append('{')
        var first = true
        for ((k, v) in map) { if (!first) sb.append(','); first = false; quote(k, sb); sb.append(':'); writeValue(v, sb) }
        sb.append('}')
    }
}

class JSONArray() {
    private val list = ArrayList<Any?>()
    constructor(items: Collection<*>) : this() { items.forEach { list += wrap(it) } }
    constructor(text: String) : this() {
        val v = Parser(text).value()
        if (v !is JSONArray) throw JSONException("Not a JSON array")
        list.addAll(v.list)
    }
    fun put(value: Any?): JSONArray { list += wrap(value); return this }
    fun put(value: Int): JSONArray { list += value.toLong(); return this }
    fun put(value: Double): JSONArray { list += value; return this }
    fun put(value: Boolean): JSONArray { list += value; return this }
    fun length() = list.size
    fun opt(i: Int): Any? = list.getOrNull(i)
    fun get(i: Int): Any = list.getOrNull(i) ?: throw JSONException("No value at $i")
    fun getInt(index: Int): Int = num(get(index), "[$index]").toInt()
    fun optInt(i: Int): Int = optInt(i, 0)
    fun optInt(i: Int, fallback: Int): Int = (opt(i) as? Number)?.toInt() ?: fallback
    fun getLong(i: Int): Long = num(get(i), "[$i]").toLong()
    fun getDouble(index: Int): Double = num(get(index), "[$index]")
    fun optDouble(i: Int): Double = optDouble(i, Double.NaN)
    fun optDouble(i: Int, fallback: Double): Double = (opt(i) as? Number)?.toDouble() ?: fallback
    fun getBoolean(index: Int): Boolean = when (val v = get(index)) { is Boolean -> v; is String -> v.equals("true", true); else -> throw JSONException("[$index] is not a boolean") }
    fun optBoolean(i: Int): Boolean = optBoolean(i, false)
    fun optBoolean(i: Int, fallback: Boolean): Boolean = (opt(i) as? Boolean) ?: fallback
    fun getString(index: Int): String = get(index).let { if (it is String) it else it.toString() }
    fun optString(i: Int): String = optString(i, "")
    fun optString(i: Int, fallback: String): String = opt(i)?.let { if (it is String) it else it.toString() } ?: fallback
    fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray ?: throw JSONException("[$index] is not an array")
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray
    fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject ?: throw JSONException("[$index] is not an object")
    fun optJSONObject(i: Int): JSONObject? = opt(i) as? JSONObject

    override fun toString(): String = StringBuilder().also { write(it) }.toString()
    internal fun write(sb: StringBuilder) {
        sb.append('[')
        list.forEachIndexed { i, v -> if (i > 0) sb.append(','); writeValue(v, sb) }
        sb.append(']')
    }
}

private fun num(v: Any, name: String): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: throw JSONException("$name is not a number")
    else -> throw JSONException("$name is not a number")
}

private fun wrap(v: Any?): Any? = when (v) {
    null -> null
    is JSONObject, is JSONArray, is String, is Boolean -> v
    is Int -> v.toLong(); is Short -> v.toLong(); is Byte -> v.toLong(); is Long -> v
    is Float -> v.toDouble(); is Double -> v
    is Collection<*> -> JSONArray(v)
    is Array<*> -> JSONArray(v.toList())
    is BooleanArray -> JSONArray(v.toList())
    is IntArray -> JSONArray(v.toList())
    is FloatArray -> JSONArray(v.map { it.toDouble() })
    is DoubleArray -> JSONArray(v.toList())
    is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), x) } }
    else -> v.toString()
}

private fun writeValue(v: Any?, sb: StringBuilder) {
    when (v) {
        null -> sb.append("null")
        is JSONObject -> v.write(sb)
        is JSONArray -> v.write(sb)
        is String -> quote(v, sb)
        is Boolean -> sb.append(v)
        is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v.toString())
        is Number -> sb.append(v.toString())
        else -> quote(v.toString(), sb)
    }
}

private fun quote(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) when (c) {
        '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u" + c.code.toString(16).padStart(4, '0')) else sb.append(c)
    }
    sb.append('"')
}

private class Parser(private val s: String) {
    private var i = 0
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun value(): Any? {
        ws()
        if (i >= s.length) throw JSONException("Unexpected end")
        return when (val c = s[i]) {
            '{' -> obj(); '[' -> arr(); '"' -> str()
            't' -> { expect("true"); true }; 'f' -> { expect("false"); false }; 'n' -> { expect("null"); null }
            else -> if (c == '-' || c.isDigit()) number() else throw JSONException("Unexpected '$c' at $i")
        }
    }
    private fun expect(w: String) { if (!s.startsWith(w, i)) throw JSONException("Expected $w at $i"); i += w.length }
    private fun obj(): JSONObject {
        val o = JSONObject(); i++; ws()
        if (s[i] == '}') { i++; return o }
        while (true) {
            ws(); val k = str(); ws()
            if (s[i] != ':') throw JSONException("Expected : at $i"); i++
            o.put(k, value()); ws()
            when (s[i]) { ',' -> i++; '}' -> { i++; return o }; else -> throw JSONException("Expected , or } at $i") }
        }
    }
    private fun arr(): JSONArray {
        val a = JSONArray(); i++; ws()
        if (s[i] == ']') { i++; return a }
        while (true) {
            a.put(value()); ws()
            when (s[i]) { ',' -> i++; ']' -> { i++; return a }; else -> throw JSONException("Expected , or ] at $i") }
        }
    }
    private fun str(): String {
        if (s[i] != '"') throw JSONException("Expected string at $i")
        i++
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
                else -> sb.append(c)
            }
        }
    }
    private fun number(): Any {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(st, i)
        return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
    }
}
