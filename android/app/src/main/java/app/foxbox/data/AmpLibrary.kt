package app.foxbox.data

import android.content.Context
import android.net.Uri
import app.foxbox.audio.SR
import org.json.JSONObject
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Amp captures (Neural Amp Modeler .nam files) and cabinet impulse responses
 * for the audio tracks: the ones that come with the app (assets/amps, assets/cabs)
 * and the ones the person imports (kept in the app's files).
 */
object AmpLibrary {
    const val DEFAULT_AMP = "JCM2000 Crunch"
    const val DEFAULT_CAB = "Brick 4x12"
    /** Longest cabinet impulse used (about 43 ms at 48 kHz: the body of any cab). */
    private const val MAX_TAPS = 2048

    class Item(val name: String, val bundled: Boolean)
    class Capture(val cfg: IntArray, val weights: FloatArray, val trim: Float, val rate: Int)

    private lateinit var app: Context
    private val ampDir get() = File(app.filesDir, "amps").apply { mkdirs() }
    private val cabDir get() = File(app.filesDir, "cabs").apply { mkdirs() }
    fun init(c: Context) { app = c.applicationContext }

    private fun assetNames(dir: String, ext: String) =
        (runCatching { app.assets.list(dir) }.getOrNull() ?: emptyArray()).filter { it.endsWith(ext) }.map { it.removeSuffix(ext) }.sorted()

    fun amps(): List<Item> = assetNames("amps", ".nam").map { Item(it, true) } +
        (ampDir.listFiles()?.filter { it.name.endsWith(".nam") }?.map { Item(it.name.removeSuffix(".nam"), false) }?.sortedBy { it.name } ?: emptyList())

    fun cabs(): List<Item> = assetNames("cabs", ".wav").map { Item(it, true) } +
        (cabDir.listFiles()?.filter { it.name.endsWith(".wav") }?.map { Item(it.name.removeSuffix(".wav"), false) }?.sortedBy { it.name } ?: emptyList())

    /** Credits and licence for the bundled captures. */
    fun credits(): String = runCatching { app.assets.open("amps/CREDITS.txt").bufferedReader().readText() }.getOrDefault("")

    private fun ampText(name: String): String {
        val f = File(ampDir, "$name.nam")
        return if (f.exists()) f.readText() else app.assets.open("amps/$name.nam").bufferedReader().readText()
    }

    fun loadAmp(name: String): Capture = parse(JSONObject(ampText(name)))

    /**
     * A .nam file's shape and weights, in the form the native runner takes. The
     * classic WaveNet and LSTM captures are supported; newer formats are refused
     * with a message (rather than played wrongly).
     */
    fun parse(d: JSONObject): Capture {
        val arch = d.optString("architecture")
        val c = d.getJSONObject("config")
        val w = d.getJSONArray("weights")
        val weights = FloatArray(w.length()) { w.getDouble(it).toFloat() }
        val cfg = ArrayList<Int>()
        when (arch) {
            "WaveNet" -> {
                if (c.has("head") && !c.isNull("head")) throw IllegalArgumentException("This capture uses a newer NAM format (A2) that 5IY can't play yet")
                val layers = c.getJSONArray("layers")
                val act0 = layers.getJSONObject(0).opt("activation")
                val actName = if (act0 is JSONObject) act0.optString("type") else act0?.toString() ?: "Tanh"
                val act = when (actName) { "Tanh" -> 5; "ReLU" -> 1; "Hardtanh" -> 2; "Sigmoid" -> 3; "LeakyReLU" -> 4; "Fasttanh" -> 5; else -> throw IllegalArgumentException("Unknown activation $actName") }
                cfg += listOf(0, act, layers.length())
                for (i in 0 until layers.length()) {
                    val la = layers.getJSONObject(i)
                    val known = setOf("input_size", "condition_size", "head_size", "channels", "kernel_size", "dilations", "activation", "gated", "head_bias")
                    val extra = la.keys().asSequence().filter { it !in known }.toList()
                    if (extra.isNotEmpty()) throw IllegalArgumentException("This capture uses a newer NAM format that 5IY can't play yet")
                    val dil = la.getJSONArray("dilations")
                    cfg += listOf(la.getInt("input_size"), la.getInt("condition_size"), la.getInt("head_size"), la.getInt("channels"), la.getInt("kernel_size"),
                        if (la.optBoolean("gated")) 1 else 0, if (la.optBoolean("head_bias")) 1 else 0, dil.length())
                    for (k in 0 until dil.length()) cfg += dil.getInt(k)
                }
            }
            "LSTM" -> cfg += listOf(1, c.getInt("num_layers"), c.getInt("input_size"), c.getInt("hidden_size"))
            else -> throw IllegalArgumentException("$arch captures aren't supported yet")
        }
        // level the capture to about -18 dB when it says how loud it is
        val loud = d.optJSONObject("metadata")?.optDouble("loudness", Double.NaN) ?: Double.NaN
        val trim = if (loud.isNaN()) 1f else 10.0.pow((-18.0 - loud) / 20.0).toFloat().coerceIn(0.05f, 8f)
        return Capture(cfg.toIntArray(), weights, trim, d.optInt("sample_rate", 48000))
    }

    /** A cabinet impulse at the app's sample rate: trimmed, faded and levelled. */
    fun loadCab(name: String): FloatArray {
        val f = File(cabDir, "$name.wav")
        val raw = if (f.exists()) AudioFiles.readWav(f) else {
            val tmp = File(app.cacheDir, "cab.wav"); app.assets.open("cabs/$name.wav").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            AudioFiles.readWav(tmp).also { tmp.delete() }
        } ?: throw IllegalArgumentException("Couldn't read the cab $name")
        // skip any silence before the impulse starts
        val peak = raw.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        if (peak <= 0f) throw IllegalArgumentException("$name is silent")
        val start = maxOf(0, raw.indexOfFirst { kotlin.math.abs(it) > peak * 0.05f } - 8)
        val n = minOf(MAX_TAPS * SR / 48000, raw.size - start).coerceAtLeast(1)
        val h = FloatArray(n) { raw[start + it] }
        val fade = n / 4
        for (i in 0 until fade) h[n - 1 - i] *= i / fade.toFloat()
        // unity loudness for a broad signal
        val e = sqrt(h.fold(0.0) { a, v -> a + v * v }).toFloat()
        if (e > 0f) for (i in h.indices) h[i] = h[i] / e * 0.7f
        return h
    }

    /** Copy a .nam file in (after checking it can be played). Returns its name. */
    fun importAmp(uri: Uri, displayName: String?): String {
        val text = app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: throw IllegalArgumentException("Couldn't open that file")
        val d = runCatching { JSONObject(text) }.getOrNull() ?: throw IllegalArgumentException("That isn't a .nam capture")
        parse(d)
        val name = ProjectStore.safe(d.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotBlank() } ?: displayName?.substringBeforeLast('.') ?: "Capture")
        File(ampDir, "$name.nam").writeText(text)
        return name
    }

    /** Bring in an impulse response (any audio file), kept as a WAV. Returns its name. */
    fun importCab(uri: Uri, displayName: String?): String {
        val pcm = AudioFiles.decode(app, uri)
        if (pcm.isEmpty()) throw IllegalArgumentException("No audio in that file")
        val name = ProjectStore.safe(displayName?.substringBeforeLast('.') ?: "Cab")
        AudioFiles.writeWav(File(cabDir, "$name.wav"), pcm.copyOf(minOf(pcm.size, SR)), 32)
        return name
    }

    fun delete(item: Item, amp: Boolean) { if (!item.bundled) File(if (amp) ampDir else cabDir, item.name + if (amp) ".nam" else ".wav").delete() }
}
