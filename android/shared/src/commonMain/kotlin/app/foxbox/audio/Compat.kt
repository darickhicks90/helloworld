package app.foxbox.audio

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The few JVM helpers the sound core used, written in plain Kotlin so the same
 * core builds for Android and iOS. Files in other packages import these
 * explicitly (an explicit import wins over java.lang on Android).
 */
object Math {
    const val PI = kotlin.math.PI
    fun floorMod(a: Int, b: Int): Int { val m = a % b; return if (m != 0 && (m xor b) < 0) m + b else m }
    fun floorMod(a: Long, b: Long): Long { val m = a % b; return if (m != 0L && (m xor b) < 0L) m + b else m }
    fun floorDiv(a: Int, b: Int): Int { val q = a / b; return if ((a % b != 0) && ((a xor b) < 0)) q - 1 else q }
    fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a xor b) < 0L)) q - 1 else q }
    fun floor(x: Double) = kotlin.math.floor(x)
    fun ceil(x: Double) = kotlin.math.ceil(x)
    fun round(x: Float): Int = if (x.isNaN()) 0 else kotlin.math.floor(x + 0.5f).toInt()
    fun round(x: Double): Long = if (x.isNaN()) 0L else kotlin.math.floor(x + 0.5).toLong()
    fun sin(x: Double) = kotlin.math.sin(x)
    fun cos(x: Double) = kotlin.math.cos(x)
    fun pow(a: Double, b: Double): Double = a.pow(b)
    fun abs(x: Float) = kotlin.math.abs(x)
    fun abs(x: Int) = kotlin.math.abs(x)
}

/** A short random id (8 hex digits), as the app has always used for clips, takes and flowers. */
fun newId(): String {
    val hex = "0123456789abcdef"
    return CharArray(8) { hex[kotlin.random.Random.nextInt(16)] }.concatToString()
}

/**
 * The subset of String.format the core uses: %d, %s, %%, and %.Nf (fixed decimals).
 * Shadows the JVM-only stdlib version inside this package.
 */
fun String.format(vararg args: Any?): String {
    val out = StringBuilder(); var ai = 0; var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%' || i == length - 1) { out.append(c); i++; continue }
        var j = i + 1
        if (this[j] == '%') { out.append('%'); i = j + 1; continue }
        var decimals = -1
        if (this[j] == '.') { j++; var n = 0; while (j < length && this[j].isDigit()) { n = n * 10 + (this[j] - '0'); j++ }; decimals = n }
        val type = if (j < length) this[j] else 's'
        val a = args.getOrNull(ai++)
        out.append(when (type) {
            'f' -> fixed((a as Number).toDouble(), if (decimals < 0) 6 else decimals)
            'd' -> (a as Number).toLong().toString()
            else -> a.toString()
        })
        i = j + 1
    }
    return out.toString()
}

/** `x` with exactly `d` decimals, rounded half up. */
fun fixed(x: Double, d: Int): String {
    if (x.isNaN()) return "NaN"
    var p = 1L; repeat(d) { p *= 10 }
    val neg = x < 0
    val v = (abs(x) * p).roundToLong()
    val whole = v / p; val frac = v % p
    val s = if (d == 0) "$whole" else "$whole." + frac.toString().padStart(d, '0')
    return if (neg && v != 0L) "-$s" else s
}

/** Listeners that can be added and removed while being called (copy on write). */
class CopyOnWriteArrayList<T> : Iterable<T> {
    @Volatile private var items: List<T> = emptyList()
    fun add(x: T): Boolean { items = items + x; return true }
    fun remove(x: T): Boolean { val before = items.size; items = items - x; return items.size != before }
    fun clear() { items = emptyList() }
    val size get() = items.size
    override fun iterator(): Iterator<T> = items.iterator()
}

/** A growing byte buffer (stands in for ByteArrayOutputStream). */
class ByteArrayOutputStream {
    private var buf = ByteArray(256); private var n = 0
    private fun room(k: Int) { if (n + k > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + k)) }
    fun write(b: Int) { room(1); buf[n++] = b.toByte() }
    fun write(b: ByteArray) { room(b.size); b.copyInto(buf, n); n += b.size }
    fun size() = n
    fun toByteArray() = buf.copyOf(n)
}

/** Run `block` holding `lock` (synchronized on Android, a spin lock on iOS). */
expect inline fun <T> locked(lock: Any, block: () -> T): T
