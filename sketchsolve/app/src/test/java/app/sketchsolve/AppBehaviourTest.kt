package app.sketchsolve

import android.app.DownloadManager
import android.content.Context
import android.os.Looper
import app.sketchsolve.llm.ModelManager
import app.sketchsolve.llm.PlayPacks
import app.sketchsolve.ui.SampleInk
import app.sketchsolve.ui.modes.AskMode
import app.sketchsolve.ui.modes.ShapesMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class AppBehaviourTest {
    private fun settle() = repeat(40) { Thread.sleep(25); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun choosingAnotherShapeReplacesTheDrawing() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sh = a.modes.first { it is ShapesMode } as ShapesMode
        a.select(sh)
        sh.paper.replaceStrokes(SampleInk.triangle())
        settle()
        assertEquals("triangle", sh.A?.rec?.type)
        val triangleIds = sh.paper.strokes.map { it.id }.toSet()
        sh.chooseType("cube")
        settle()
        assertTrue("old triangle strokes are gone", sh.paper.strokes.none { it.id in triangleIds })
        assertTrue(sh.paper.strokes.isNotEmpty())
        assertEquals("cube", sh.A?.model?.type)
        sh.paper.undo(); settle()
        assertEquals("undo brings the triangle back", triangleIds, sh.paper.strokes.map { it.id }.toSet())
    }

    @Test fun downloadIsHandedToTheSystemDownloadManager() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.models.deviceOverride = ModelManager.DeviceInfo("Test phone", "chip", 8.0, 50.0, 8, "15")
        val ask = a.modes.first { it is AskMode } as AskMode
        a.select(ask)
        a.models.skipNetworkCheck = true
        ask.startDownload(listOf(ModelManager.WHISPER_BASE, ModelManager.GEMMA_E2B))
        settle()
        val dm = shadowOf(a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
        assertEquals(2, dm.requestCount)
        val prefs = a.getSharedPreferences("sketchsolve", Context.MODE_PRIVATE)
        val ids = listOf("dl_whisper-base-en", "dl_gemma4-e2b").map { prefs.getLong(it, -1L) }
        assertTrue(ids.toString(), ids.all { it >= 0 })
        val urls = ids.map { shadowOf(dm.getRequest(it)).uri.toString() }
        assertTrue(urls.contains(ModelManager.WHISPER_BASE.url))
        assertTrue(urls.contains(ModelManager.GEMMA_E2B.url))
        val req = shadowOf(dm.getRequest(ids[1]))
        assertEquals("Wi-Fi only by default", false, req.allowedOverMetered)
        assertTrue(req.destination.toString().endsWith("models/gemma-4-E2B-it.litertlm.download"))
        // asking again doesn't start a second copy
        ask.startDownload(listOf(ModelManager.GEMMA_E2B))
        settle()
        assertEquals(2, dm.requestCount)
    }

    @Test fun googlePlayDeliversTheModelsItCarries() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.models.deviceOverride = ModelManager.DeviceInfo("Test phone", "chip", 8.0, 50.0, 8, "15")
        val play = FakePlay(kotlin.io.path.createTempDirectory("play").toFile())
        // this phone's group gets Gemma E2B from Play; Whisper base isn't carried for it
        PlayPacks.packs(ModelManager.GEMMA_E2B).forEach { play.packs[it] = mapOf("x" to ByteArray(2_000_000)) }
        PlayPacks.packs(ModelManager.WHISPER_BASE).forEach { play.packs[it] = emptyMap() }
        a.models.packSourceOverride = play
        a.models.skipNetworkCheck = true
        val ask = a.modes.first { it is AskMode } as AskMode
        a.select(ask)
        ask.startDownload(listOf(ModelManager.WHISPER_BASE, ModelManager.GEMMA_E2B))
        settle()
        assertEquals(PlayPacks.packs(ModelManager.GEMMA_E2B), play.fetched)
        assertTrue(a.models.viaPlay(ModelManager.GEMMA_E2B))
        assertEquals(ModelManager.State.RUNNING, a.models.progress(ModelManager.GEMMA_E2B).state)
        val dm = shadowOf(a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
        assertEquals("only Whisper comes from the internet", 1, dm.requestCount)
        // cancelling forgets the Play download
        a.models.cancel(ModelManager.GEMMA_E2B)
        assertFalse(a.models.viaPlay(ModelManager.GEMMA_E2B))
        assertEquals(ModelManager.State.NONE, a.models.progress(ModelManager.GEMMA_E2B).state)
    }

    private fun padText(a: MainActivity): String {
        val out = StringBuilder()
        fun walk(v: android.view.View) { if (v is android.widget.TextView) out.append(v.text).append('\n'); if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(a.pad.box); return out.toString()
    }

    @Test fun notepadClearsOnceTheModelsAreInPlaceAndSettingsHoldThem() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.models.deviceOverride = ModelManager.DeviceInfo("Test phone", "chip", 8.0, 50.0, 8, "15")
        val ask = a.modes.first { it is AskMode } as AskMode
        a.select(ask); settle()
        assertTrue("setup shown while models are missing", padText(a).contains("On-device models", ignoreCase = true))
        // put (empty, right-sized) model files in place
        for (m in listOf(ModelManager.GEMMA_E2B, ModelManager.WHISPER_BASE, ModelManager.WHISPER_SMALL))
            java.io.RandomAccessFile(a.models.file(m), "rw").use { it.setLength(m.bytes) }
        ask.refresh(); settle()
        val t = padText(a)
        assertFalse(t, t.contains("On-device models", ignoreCase = true))
        assertFalse(t, t.contains("formula library", ignoreCase = true) && t.contains("Choose a folder"))
        assertTrue(t, t.contains("Hold 🎤"))
        // ⚙ Settings shows the models and the library
        var shown = ""
        a.showSettings("AI models & voice") { p -> ask.renderModels(p); shown = "models" }
        settle()
        assertEquals("models", shown)
        assertNotNull(a.settingsPanel)
        a.showSettings("Formula library") { p -> ask.renderLibrary(p); shown = "library" }
        settle()
        assertEquals("library", shown)
        for (m in listOf(ModelManager.GEMMA_E2B, ModelManager.WHISPER_BASE, ModelManager.WHISPER_SMALL)) a.models.file(m).delete()
    }

    @Test fun importingAModelFileChecksAndInstallsIt() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.models.deviceOverride = ModelManager.DeviceInfo("Test phone", "chip", 8.0, 50.0, 8, "15")
        val real = java.io.File(System.getProperty("whisperFile") ?: "/nonexistent")
        if (real.exists()) {
            val m = a.models.importFile(android.net.Uri.fromFile(real)) { _, _ -> }
            assertEquals(ModelManager.WHISPER_BASE, m)
            assertTrue(a.models.isReady(ModelManager.WHISPER_BASE))
        }
        // a file that isn't a model is refused with an explanation
        val junk = java.io.File.createTempFile("holiday", ".jpg").apply { writeText("not a model") }
        val err = runCatching { a.models.importFile(android.net.Uri.fromFile(junk)) { _, _ -> } }.exceptionOrNull()
        assertTrue(err?.message ?: "", err?.message?.contains("isn't one of the SketchSolve model files") == true)
        // a model file with the right name but damaged contents is refused too
        val fake = java.io.File(junk.parentFile, "ggml-base.en-q5_1.bin").apply { writeText("damaged") }
        val err2 = runCatching { a.models.importFile(android.net.Uri.fromFile(fake)) { _, _ -> } }.exceptionOrNull()
        assertTrue(err2?.message ?: "", err2?.message?.contains("damaged") == true || err2?.message?.contains("wrong size") == true)
    }

    @Test fun notEnoughSpaceIsExplained() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.models.deviceOverride = ModelManager.DeviceInfo("Full phone", "chip", 8.0, 1.0, 8, "15")
        assertNotNull(a.models.enqueue(ModelManager.GEMMA_E2B, true))
    }
}
