package app.sketchsolve

import app.sketchsolve.llm.ModelManager
import app.sketchsolve.llm.ModelSpec
import app.sketchsolve.llm.PackInfo
import app.sketchsolve.llm.PackSource
import app.sketchsolve.llm.PlayPacks
import com.google.android.play.core.aipacks.model.AiPackStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Pretends to be Google Play: holds packs' files and "downloads" them on fetch. */
class FakePlay(private val root: File) : PackSource {
    /** pack → (path inside assets → bytes); a pack with no files is an empty variant for this phone. */
    val packs = HashMap<String, Map<String, ByteArray>>()
    val done = HashSet<String>()
    val removed = ArrayList<String>()
    var fetched = ArrayList<String>()
    var broken = false
    private var listener: ((String, PackInfo) -> Unit)? = null

    private fun info(p: String): PackInfo {
        val size = packs[p]?.values?.sumOf { it.size.toLong() } ?: 0L
        return when {
            p in done -> PackInfo(AiPackStatus.COMPLETED, size, size)
            p in fetched -> PackInfo(AiPackStatus.DOWNLOADING, size / 3, size)
            else -> PackInfo(AiPackStatus.NOT_INSTALLED, 0, size)
        }
    }
    override fun states(packs: List<String>): Map<String, PackInfo> {
        if (broken) throw IllegalStateException("not installed from Google Play")
        return packs.associateWith { info(it) }
    }
    override fun fetch(packs: List<String>, onError: (Exception) -> Unit) { fetched.addAll(packs); packs.forEach { listener?.invoke(it, info(it)) } }
    /** Finish the downloads that were asked for. */
    fun finish() {
        for (p in fetched) {
            for ((path, bytes) in packs[p] ?: emptyMap()) File(root, "$p/assets/$path").apply { parentFile.mkdirs(); writeBytes(bytes) }
            done.add(p); listener?.invoke(p, info(p))
        }
    }
    override fun assetsPath(pack: String) = if (pack in done) File(root, "$pack/assets").path else null
    override fun remove(pack: String) { removed.add(pack); done.remove(pack); File(root, pack).deleteRecursively() }
    override fun cancel(packs: List<String>) { fetched.removeAll(packs.toSet()) }
    override fun listen(onChange: (String, PackInfo) -> Unit) { listener = onChange }
    override fun askToContinue(activity: android.app.Activity) = false
}

class PlayPacksTest {
    private val saved = PlayPacks.CHUNK
    private lateinit var root: File
    @Before fun small() { PlayPacks.CHUNK = 1_000_000L; root = kotlin.io.path.createTempDirectory("play").toFile() }
    @After fun restore() { PlayPacks.CHUNK = saved; root.deleteRecursively() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val data = ByteArray(2_500_000) { (it * 31 + it / 7).toByte() }
    private val spec = ModelSpec("gemma4-e2b", "Gemma test", "", "", "model.litertlm", data.size.toLong(), sha(data), 1)

    /** Split like the build's preparePlayPacks task does. */
    private fun publish(play: FakePlay, m: ModelSpec, bytes: ByteArray) {
        val names = PlayPacks.packs(m)
        for ((i, n) in names.withIndex()) {
            val from = i * PlayPacks.CHUNK.toInt()
            play.packs[n] = mapOf(PlayPacks.partPath(m, i + 1) to bytes.copyOfRange(from, minOf(bytes.size, from + PlayPacks.CHUNK.toInt())))
        }
    }

    @Test fun bigModelsAreSplitIntoPacksPlayAccepts() {
        PlayPacks.CHUNK = 1_400_000_000L
        assertEquals(listOf("e4b_1", "e4b_2", "e4b_3"), PlayPacks.packs(ModelManager.GEMMA_E4B))
        assertEquals(listOf("e2b_1", "e2b_2"), PlayPacks.packs(ModelManager.GEMMA_E2B))
        assertEquals(listOf("whisper_small"), PlayPacks.packs(ModelManager.WHISPER_SMALL))
        assertTrue("each pack under Play's 1.5 GB", PlayPacks.CHUNK < 1_500_000_000L)
        // what a 12 GB phone downloads must stay under Play's 4 GB per device
        val large = ModelManager.GEMMA_E4B.bytes + ModelManager.WHISPER_SMALL.bytes + 80_000_000L
        assertTrue(large < 4_000_000_000L)
    }

    @Test fun downloadsThePartsAndJoinsThemBack() {
        val play = FakePlay(root); publish(play, spec, data)
        val pp = PlayPacks(play)
        assertEquals(3, PlayPacks.packs(spec).size)
        assertTrue(pp.offers(spec))
        pp.fetch(spec)
        assertEquals(ModelManager.State.RUNNING, pp.progress(spec).state)
        play.finish()
        assertEquals(ModelManager.State.VERIFYING, pp.progress(spec).state)
        val out = File(root, "model.joined")
        assertTrue(pp.assemble(spec, out))
        assertArrayEquals(data, out.readBytes())
        assertEquals("packs freed after joining", PlayPacks.packs(spec), play.removed)
    }

    @Test fun aDamagedPartIsCaught() {
        val play = FakePlay(root)
        publish(play, spec, data.copyOf().also { it[1_500_000] = (it[1_500_000] + 1).toByte() })
        val pp = PlayPacks(play)
        pp.fetch(spec); play.finish()
        val out = File(root, "model.joined")
        assertFalse(pp.assemble(spec, out))
        assertFalse(out.exists())
        assertEquals(ModelManager.State.FAILED, pp.progress(spec).state)
        assertTrue(pp.progress(spec).message, pp.progress(spec).message.contains("damaged"))
    }

    @Test fun modelsPlayDoesNotCarryForThisPhoneComeFromTheInternet() {
        val play = FakePlay(root)
        PlayPacks.packs(spec).forEach { play.packs[it] = emptyMap() } // the empty variant: another device group's model
        assertFalse(PlayPacks(play).offers(spec))
        // a copy not installed from Google Play
        val sideloaded = FakePlay(root).apply { broken = true; publish(this, spec, data) }
        assertFalse(PlayPacks(sideloaded).offers(spec))
        assertFalse(PlayPacks(null).offers(spec))
    }
}
