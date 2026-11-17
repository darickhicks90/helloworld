package app.foxbox.data

import android.content.Context
import android.net.Uri
import app.foxbox.audio.Audio
import app.foxbox.engines.Pickup
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pickup sample banks: 16 pad sounds with their names and pad settings.
 * Two banks are built in (the Studio Kit and the Classic Box); your own are
 * saved as .foxbank files (a zip of bank.json plus pads/pad<N>.wav), which
 * can be shared and imported. A plain zip of WAV files also imports as a bank.
 */
object Banks {
    class Bank(
        val name: String,
        val names: List<String>,
        /** Pad sounds (null = leave that pad as it is). Empty for built-in kits, which are made on demand. */
        val samples: List<FloatArray?>,
        /** Per-pad settings by param id (p0tune, p3end, …). */
        val params: JSONObject,
        val kit: String? = null,
        val file: File? = null,
    ) { val builtIn get() = kit != null }

    private lateinit var root: File
    fun init(c: Context) { root = File(c.filesDir, "banks").apply { mkdirs() } }

    val builtIns = listOf(
        Bank("Studio Kit", Pickup.STUDIO_NAMES, emptyList(), JSONObject(), kit = Pickup.KIT_STUDIO),
        Bank("Classic Box", Pickup.CLASSIC_NAMES, emptyList(), JSONObject(), kit = Pickup.KIT_CLASSIC),
    )

    /** Built-in banks first, then yours by name. Reads only each bank's name and pad names. */
    fun list(): List<Bank> = builtIns + (root.listFiles()?.filter { it.name.endsWith(".foxbank") }?.sortedBy { it.name.lowercase() }
        ?.mapNotNull { f -> runCatching { readMeta(f) }.getOrNull() } ?: emptyList())

    private fun padParams(): JSONObject {
        val p = Audio.pickup
        return JSONObject().apply { p.p.all.values.filter { Regex("^p\\d+[a-z]+$").matches(it.id) }.forEach { put(it.id, it.value.toDouble()) } }
    }

    /** The pads as they are now. */
    fun capture(name: String): Bank {
        val p = Audio.pickup
        return Bank(name, p.names.toList(), p.samples.map { it }, padParams())
    }

    fun save(name: String): Bank {
        val b = capture(name)
        val f = File(root, ProjectStore.safe(name) + ".foxbank")
        f.writeBytes(zip(b))
        return Bank(b.name, b.names, b.samples, b.params, file = f)
    }

    fun delete(b: Bank) { b.file?.delete() }

    /** Read a bank file in full (its sounds too). */
    fun load(b: Bank): Bank = b.file?.let { read(it.readBytes(), it) } ?: b

    fun zip(b: Bank): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("bank.json"))
            z.write(JSONObject().apply {
                put("app", "FoxBox"); put("version", 1); put("name", b.name)
                put("names", JSONArray(b.names)); put("params", b.params)
            }.toString().toByteArray())
            z.closeEntry()
            b.samples.forEachIndexed { i, d ->
                if (d == null) return@forEachIndexed
                val tmp = File.createTempFile("foxbank", ".wav"); AudioFiles.writeWav(tmp, d, 16)
                z.putNextEntry(ZipEntry("pads/pad${i + 1}.wav")); z.write(tmp.readBytes()); z.closeEntry(); tmp.delete()
            }
        }
        return bos.toByteArray()
    }

    private fun readMeta(f: File): Bank {
        ZipInputStream(f.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "bank.json") {
                    val m = JSONObject(String(z.readBytes()))
                    val n = m.optJSONArray("names") ?: JSONArray()
                    return Bank(m.optString("name", f.nameWithoutExtension), (0 until n.length()).map { n.optString(it) }, emptyList(), m.optJSONObject("params") ?: JSONObject(), file = f)
                }
            }
        }
        return Bank(f.nameWithoutExtension, emptyList(), emptyList(), JSONObject(), file = f)
    }

    /**
     * A .foxbank, or any zip of WAV files (they go onto pads 1–16 in name
     * order, named after their files).
     */
    fun read(bytes: ByteArray, file: File? = null, fallbackName: String = "Imported bank"): Bank {
        var meta: JSONObject? = null
        val padWavs = HashMap<Int, FloatArray>()
        val loose = ArrayList<Pair<String, FloatArray>>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val data = z.readBytes()
                val base = e.name.substringAfterLast('/')
                when {
                    e.name == "bank.json" -> meta = JSONObject(String(data))
                    base.endsWith(".wav", true) && !base.startsWith("._") -> {
                        val tmp = File.createTempFile("foxbank", ".wav"); tmp.writeBytes(data)
                        val pcm = AudioFiles.readWav(tmp); tmp.delete()
                        if (pcm != null) {
                            val m = Regex("^pads/pad(\\d+)\\.wav$").find(e.name)
                            if (m != null) padWavs[m.groupValues[1].toInt() - 1] = pcm else loose += base.substringBeforeLast('.') to pcm
                        }
                    }
                }
            }
        }
        val m = meta
        if (m != null) {
            val n = m.optJSONArray("names") ?: JSONArray()
            val names = (0 until Pickup.PADS).map { n.optString(it, "Pad ${it + 1}") }
            return Bank(m.optString("name", fallbackName), names, (0 until Pickup.PADS).map { padWavs[it] }, m.optJSONObject("params") ?: JSONObject(), file = file)
        }
        if (loose.isEmpty()) throw IllegalArgumentException("No WAV files or FoxBox bank found in that file")
        val sorted = loose.sortedBy { it.first.lowercase() }.take(Pickup.PADS)
        val names = (0 until Pickup.PADS).map { sorted.getOrNull(it)?.first?.take(16) ?: "Pad ${it + 1}" }
        return Bank(fallbackName, names, (0 until Pickup.PADS).map { sorted.getOrNull(it)?.second }, JSONObject(), file = file)
    }

    /** Import a shared bank (or zip of WAVs) into your banks. */
    fun import(c: Context, uri: Uri, name: String): Bank {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Couldn't read the file")
        val b = read(bytes, fallbackName = name)
        val f = File(root, ProjectStore.safe(b.name) + ".foxbank")
        f.writeBytes(zip(b))
        return Bank(b.name, b.names, b.samples, b.params, file = f)
    }

    /** Write a bank to the export folder for sharing; returns a content Uri. */
    fun export(b: Bank): Uri? = ProjectStore.export(null, "Banks", ProjectStore.safe(b.name) + ".foxbank", "application/zip", zip(load(b)))
}
