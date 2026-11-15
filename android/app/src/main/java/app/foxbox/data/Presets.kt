package app.foxbox.data

import android.content.Context
import android.net.Uri
import app.foxbox.audio.Audio
import app.foxbox.blocks.Block
import app.foxbox.engines.Pickup
import app.foxbox.engines.SampleEngine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Block presets. A preset is a .foxpreset file (a zip) holding the block's
 * settings and, for samplers, its sample(s), so it can be shared whole.
 */
object Presets {
    class Preset(val name: String, val blockId: String, val params: JSONObject, val extra: JSONObject,
                 val samples: Map<String, FloatArray> = emptyMap(), val factory: Boolean = false, val file: File? = null)

    private lateinit var root: File
    fun init(c: Context) { root = File(c.filesDir, "presets").apply { mkdirs() } }
    private fun dir(blockId: String) = File(root, blockId).apply { mkdirs() }

    fun list(blockId: String): List<Preset> =
        factory(blockId) + (dir(blockId).listFiles()?.filter { it.name.endsWith(".foxpreset") }?.sortedBy { it.name }?.mapNotNull { runCatching { read(it.readBytes(), it) }.getOrNull() } ?: emptyList())

    /** Snapshot a block's current sound. */
    fun capture(block: Block, name: String): Preset {
        val e = block.engine
        val extra = JSONObject().also { e.saveExtra(it) }
        val samples = HashMap<String, FloatArray>()
        when (e) {
            is SampleEngine -> samples["sample"] = e.sample
            is Pickup -> for (i in 0 until Pickup.PADS) samples["pad$i"] = e.samples[i]
        }
        return Preset(name, block.id, e.p.save(), extra, samples)
    }

    fun saveUser(block: Block, name: String): Preset {
        val p = capture(block, name)
        val f = File(dir(block.id), ProjectStore.safe(name) + ".foxpreset")
        f.writeBytes(zip(p))
        return Preset(p.name, p.blockId, p.params, p.extra, p.samples, file = f)
    }

    fun delete(p: Preset) { p.file?.delete() }

    /** Load a preset into its block (call from the main thread). */
    fun apply(block: Block, p: Preset) {
        val e = block.engine
        // Only the params the preset names change (factory presets leave the XY pad and patterns alone).
        p.params.keys().forEach { k -> e.p.all[k]?.set(p.params.getDouble(k).toFloat()) }
        if (!p.factory) e.loadExtra(p.extra)
        when (e) {
            is SampleEngine -> p.samples["sample"]?.let { e.setSample(it, p.name) }
            is Pickup -> for (i in 0 until Pickup.PADS) p.samples["pad$i"]?.let { e.setPadSample(i, it, e.names[i]) }
        }
    }

    fun zip(p: Preset): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("preset.json"))
            z.write(JSONObject().apply { put("name", p.name); put("block", p.blockId); put("params", p.params); put("extra", p.extra); put("app", "FoxBox"); put("version", 1) }.toString().toByteArray())
            z.closeEntry()
            for ((k, data) in p.samples) {
                val tmp = File.createTempFile("foxpreset", ".wav"); AudioFiles.writeWav(tmp, data, 16)
                z.putNextEntry(ZipEntry("samples/$k.wav")); z.write(tmp.readBytes()); z.closeEntry(); tmp.delete()
            }
        }
        return bos.toByteArray()
    }

    fun read(bytes: ByteArray, file: File? = null): Preset {
        var meta: JSONObject? = null
        val samples = HashMap<String, FloatArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val data = z.readBytes()
                when {
                    e.name == "preset.json" -> meta = JSONObject(String(data))
                    e.name.startsWith("samples/") && e.name.endsWith(".wav") -> {
                        val tmp = File.createTempFile("foxpreset", ".wav"); tmp.writeBytes(data)
                        AudioFiles.readWav(tmp)?.let { samples[e.name.removePrefix("samples/").removeSuffix(".wav")] = it }
                        tmp.delete()
                    }
                }
            }
        }
        val m = meta ?: throw IllegalArgumentException("Not a FoxBox preset")
        return Preset(m.getString("name"), m.getString("block"), m.getJSONObject("params"), m.optJSONObject("extra") ?: JSONObject(), samples, file = file)
    }

    /** Import a shared preset file into the library. Returns it, or throws. */
    fun import(c: Context, uri: Uri): Preset {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Couldn't read the file")
        val p = read(bytes)
        if (Audio.engines.none { it.id == p.blockId }) throw IllegalArgumentException("This preset is for a block FoxBox doesn't have")
        val f = File(dir(p.blockId), ProjectStore.safe(p.name) + ".foxpreset")
        f.writeBytes(bytes)
        return Preset(p.name, p.blockId, p.params, p.extra, p.samples, file = f)
    }

    /** Export for sharing; returns a content Uri. */
    fun export(p: Preset): Uri? = ProjectStore.export(null, "Presets", ProjectStore.safe(p.name) + ".foxpreset", "application/zip", zip(p))

    // ------------------------------------------------------------------ factory sounds

    private fun f(blockId: String, name: String, vararg values: Pair<String, Double>): Preset {
        val e = Audio.engine(blockId)
        val params = JSONObject().apply {
            e.p.all.values.filter { !it.id.startsWith("xy") && !it.id.startsWith("seq") && it.id != "padSeq" && it.id != "on" }.forEach { put(it.id, it.def.toDouble()) }
            values.forEach { (k, v) -> put(k, v) }
        }
        return Preset(name, blockId, params, JSONObject().put("keep", true), factory = true)
    }

    fun factory(blockId: String): List<Preset> = when (blockId) {
        "gusts" -> listOf(
            f("gusts", "Breeze Pad", "wave1" to 0.0, "wave2" to 2.0, "oscMix" to 0.5, "detune" to 14.0, "cutoff" to 1400.0, "res" to 0.2, "attack" to 0.6, "decay" to 1.2, "sustain" to 0.8, "release" to 2.0, "envAmt" to 0.2, "lfoRate" to 0.3),
            f("gusts", "Squall Lead", "wave1" to 1.0, "wave2" to 0.0, "detune" to 6.0, "cutoff" to 3200.0, "res" to 0.45, "envAmt" to 0.6, "decay" to 0.3, "sustain" to 0.5, "release" to 0.3, "glide" to 0.08),
            f("gusts", "Chimney Bass", "sub" to 0.8, "cutoff" to 380.0, "res" to 0.4, "envAmt" to 0.7, "decay" to 0.25, "sustain" to 0.2, "release" to 0.15),
        )
        "reflections" -> listOf(
            f("reflections", "Clear Water"),
            f("reflections", "Slow Stretch", "stretch" to 1.0, "speed" to 0.5, "grain" to 0.12, "attack" to 0.3, "release" to 1.5),
            f("reflections", "Muffled Keys", "cutoff" to 2500.0, "res" to 0.25, "release" to 0.8),
        )
        "leaves" -> listOf(
            f("leaves", "Dew Cloud", "density" to 40.0, "size" to 0.2, "spray" to 0.3, "reverb" to 0.6),
            f("leaves", "Seed Rain", "density" to 8.0, "size" to 0.05, "pitchSpray" to 7.0, "octave" to 0.3),
            f("leaves", "Drone Moss", "drone" to 1.0, "density" to 30.0, "size" to 0.35, "attack" to 2.0, "release" to 4.0),
        )
        "minds" -> listOf(
            f("minds", "Steady", "generative" to 0.0, "chaos" to 0.0, "glitch" to 0.0),
            f("minds", "Restless", "generative" to 0.8, "chaos" to 0.5, "glitch" to 0.4, "mutate" to 0.5),
            f("minds", "Crushed", "crush" to 0.5, "cutoff" to 6000.0),
        )
        "bumble" -> listOf(
            f("bumble", "Acid Line", "ftype" to 4.0, "res" to 0.8, "envAmt" to 0.8, "cutoff" to 300.0, "decay" to 0.2, "accent" to 0.8, "overdrive" to 0.5),
            f("bumble", "Sub Rumble", "w1" to 0.0, "l2" to 0.0, "sub" to 0.9, "subOct" to 1.0, "ftype" to 1.0, "cutoff" to 300.0, "res" to 0.1),
            f("bumble", "Growl", "w1" to 2.0, "w2" to 3.0, "semi2" to -12.0, "ftype" to 1.0, "cutoff" to 700.0, "res" to 0.5, "dist" to 0.5, "lfo2Filter" to 0.3, "lfo2" to 3.0),
        )
        "pickup" -> listOf(
            f("pickup", "SP Grit", "rate" to 26040.0, "bits" to 12.0, "warmth" to 0.3),
            f("pickup", "MPC Warm", "rate" to 40000.0, "bits" to 12.0, "warmth" to 0.15),
            f("pickup", "Crunchy", "rate" to 12000.0, "bits" to 8.0, "warmth" to 0.5),
        )
        else -> emptyList()
    }
}
