package app.sketchsolve

import android.app.DownloadManager
import android.content.Context
import android.os.Looper
import app.sketchsolve.llm.ModelManager
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
        ask.startDownload(listOf(ModelManager.WHISPER_BASE, ModelManager.GEMMA_E2B))
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
        assertEquals(2, dm.requestCount)
    }

    @Test fun notEnoughSpaceIsExplained() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.models.deviceOverride = ModelManager.DeviceInfo("Full phone", "chip", 8.0, 1.0, 8, "15")
        assertNotNull(a.models.enqueue(ModelManager.GEMMA_E2B, true))
    }
}
