package app.foxbox.json

/**
 * The JSON the sound core saves and loads with. On Android this *is*
 * android's org.json (a typealias, so the app's project code passes the same
 * objects straight through); on iOS it is the small stand-in in nonJvmMain.
 */
expect class JSONObject() {
    fun put(name: String, value: Any?): JSONObject
    fun put(name: String, value: Boolean): JSONObject
    fun put(name: String, value: Int): JSONObject
    fun put(name: String, value: Long): JSONObject
    fun put(name: String, value: Double): JSONObject
    fun has(name: String): Boolean
    fun remove(name: String): Any?
    fun length(): Int
    fun getString(name: String): String
    fun optString(name: String): String
    fun optString(name: String, fallback: String): String
    fun getInt(name: String): Int
    fun optInt(name: String): Int
    fun optInt(name: String, fallback: Int): Int
    fun getDouble(name: String): Double
    fun optDouble(name: String): Double
    fun optDouble(name: String, fallback: Double): Double
    fun getBoolean(name: String): Boolean
    fun optBoolean(name: String): Boolean
    fun optBoolean(name: String, fallback: Boolean): Boolean
    fun getJSONArray(name: String): JSONArray
    fun optJSONArray(name: String): JSONArray?
    fun getJSONObject(name: String): JSONObject
    fun optJSONObject(name: String): JSONObject?
}

expect class JSONArray() {
    constructor(items: Collection<*>)
    fun put(value: Any?): JSONArray
    fun put(value: Int): JSONArray
    fun put(value: Double): JSONArray
    fun put(value: Boolean): JSONArray
    fun length(): Int
    fun getInt(index: Int): Int
    fun getDouble(index: Int): Double
    fun getString(index: Int): String
    fun getBoolean(index: Int): Boolean
    fun getJSONArray(index: Int): JSONArray
    fun getJSONObject(index: Int): JSONObject
    fun optJSONArray(index: Int): JSONArray?
}
