package app.gemmadesk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import app.gemmadesk.assist.Assistant
import app.gemmadesk.assist.Designer
import app.gemmadesk.data.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Checks real Gemma replies (from tools/run_gemma.py) the way the app would, and draws the designs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
class GemmaRepliesTest {
    @Test fun checkRealReplies() {
        val path = System.getProperty("gemmaOutputs")
        assumeTrue(path != null && File(path).exists())
        val out = File("build/eval").apply { mkdirs() }
        val report = StringBuilder()
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("gemmadesk", 0).edit().putBoolean("setupSeen", true).apply()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val today = GemmaPromptsTest.TODAY
        val led = Ledger(File(out, "ledger.json").apply { delete() })
        led.add(Entry(date = today.minusDays(3), description = "Weekly groceries", cents = -8642, category = "Groceries"))
        led.add(Entry(date = today.minusDays(10), description = "Market", cents = -3120, category = "Groceries"))
        val cal = Calendar(File(out, "cal.json").apply { delete() })
        cal.add(Event(title = "Gym", date = today.plusDays(5), start = java.time.LocalTime.of(15, 30)))
        for (line in File(path!!).readLines()) {
            val o = JSONObject(line); val id = o.getString("id"); val text = o.getString("text")
            report.append("=== $id (${o.optDouble("seconds")} s)\n")
            if (id.startsWith("design") || id.startsWith("edit")) {
                val frame = if (id.contains("plumber")) Frame.DESKTOP else Frame.PHONE
                val base = if (id.startsWith("edit")) Designer.tidy(JSONObject(AppTest.BAKERY), Page(name = "Home", frame = frame)) else Page(name = "Home", frame = frame)
                val json = app.gemmadesk.llm.extractJson(text)?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (json == null) { report.append("NOT JSON: ${text.take(300)}\n"); continue }
                val p = Designer.tidy(json, base)
                report.append("${p.items.size} items, page height ${p.height()}\n")
                for (i in p.items) report.append("  ${i.type} (${i.x},${i.y}) ${i.w}x${i.h} fs${i.fontSize} ${i.color}/${i.background} \"${i.text.take(50).replace("\n", " ")}\"\n")
                // overlapping text items are the usual layout mistake: count them
                val texts = p.items.filter { it.type != "box" }
                val overlaps = texts.indices.sumOf { i -> (i + 1 until texts.size).count { j -> val x = texts[i]; val y = texts[j]; x.x < y.right() && y.x < x.right() && x.y < y.bottom() && y.y < x.bottom() } }
                report.append("overlapping items: $overlaps\n")
                File(out, "$id.html").writeText(Html.page(p))
                a.show(1); a.make.open(Project(name = id, pages = listOf(p)))
                repeat(20) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle() }
                val v = a.window.decorView
                val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
                FileOutputStream(File(out, "$id.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                val ask = GemmaPromptsTest.ASKS[id.removePrefix("ask-").toInt()]
                val json = app.gemmadesk.llm.extractJson(text)?.let { runCatching { JSONObject(it) }.getOrNull() }
                report.append("Q: $ask\nraw: ${text.replace("\n", " ").take(400)}\n")
                if (json == null) { report.append("NOT JSON\n"); continue }
                report.append("-> ${Assistant(FakeModel("")).check(ask, json, led, cal, today)}\n")
            }
        }
        File(out, "gemma-report.txt").writeText(report.toString())
    }
}
