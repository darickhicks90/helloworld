package app.gemmadesk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.gemmadesk.assist.Assistant
import app.gemmadesk.assist.Designer
import app.gemmadesk.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AppTest {
    private val out = File("build/screens").apply { mkdirs() }
    private fun settle() = repeat(30) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle() }
    private fun launch(): MainActivity {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("gemmadesk", 0).edit().putBoolean("setupSeen", true).apply()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also { settle() }
    }
    private fun shot(a: MainActivity, name: String) {
        val v = a.window.decorView; v.requestLayout(); settle()
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun click(root: View, text: String) = (views(root).first { it is TextView && it.text.toString() == text } as TextView).performClick()

    private fun touch(v: View, action: Int, x: Float, y: Float, down: Long) =
        v.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0))
    private fun drag(v: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x0, y0, t)
        for (k in 1..10) touch(v, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * k / 10, y0 + (y1 - y0) * k / 10, t)
        touch(v, MotionEvent.ACTION_UP, x1, y1, t); settle()
    }

    companion object {
        // the kind of layout Gemma returns for "a home page for a bakery"
        const val BAKERY = """{"background":"#FFF8F0","items":[
 {"type":"box","x":0,"y":0,"w":390,"h":64,"text":"","background":"#5D4037"},
 {"type":"heading","x":24,"y":14,"w":240,"h":36,"text":"Rosa's Bakery","fontSize":24,"color":"#FFF8F0"},
 {"type":"image","x":16,"y":80,"w":358,"h":200,"text":"Fresh loaves on a wooden table","background":"#E8D5C4","radius":16},
 {"type":"heading","x":24,"y":300,"w":342,"h":40,"text":"Baked fresh every morning","fontSize":26,"color":"#3E2723"},
 {"type":"text","x":24,"y":348,"w":342,"h":64,"text":"Sourdough, pastries and coffee, made by hand in our kitchen since 1998.","fontSize":16,"color":"#5D4037"},
 {"type":"box","x":16,"y":432,"w":171,"h":120,"text":"","background":"#FFFFFF","radius":14},
 {"type":"text","x":32,"y":448,"w":139,"h":88,"text":"Bread\nSourdough, rye and baguettes","fontSize":15,"bold":false,"color":"#3E2723"},
 {"type":"box","x":203,"y":432,"w":171,"h":120,"text":"","background":"#FFFFFF","radius":14},
 {"type":"text","x":219,"y":448,"w":139,"h":88,"text":"Cakes\nBirthday cakes to order","fontSize":15,"color":"#3E2723"},
 {"type":"text","x":24,"y":572,"w":342,"h":44,"text":"Open Tue–Sun, 7am – 3pm · 12 Mill Street","fontSize":14,"color":"#6D4C41"},
 {"type":"button","x":24,"y":632,"w":342,"h":52,"text":"Order for pickup","fontSize":17,"bold":true,"color":"#FFFFFF","background":"#C62828","radius":12},
 {"type":"input","x":24,"y":708,"w":342,"h":44,"text":"Your email for weekly specials","fontSize":15,"background":"#FFFFFF","radius":8}]}"""
    }

    @Test @Config(qualifiers = "w412dp-h915dp-port-xxhdpi") fun phoneEverydayFromGemmaToLedger() {
        val a = launch()
        val today = LocalDate.now()
        a.ledger.add(Entry(date = today.minusDays(2), description = "Weekly groceries", cents = -8642, category = "Groceries"))
        a.ledger.add(Entry(date = today.minusDays(5), description = "Paycheck", cents = 185000, category = "Salary"))
        a.ledger.add(Entry(date = today.minusDays(1), description = "Coffee with Sam", cents = -1150, category = "Dining"))
        a.show(0); settle()
        // Gemma reads "Paid $45 for gas yesterday"; the card waits for Save
        val o = JSONObject("""{"action":"add_entries","reply":"","entries":[{"date":"${today.minusDays(1)}","description":"Gas","amount":45,"direction":"out","category":"Fuel"}]}""")
        val p = Assistant(FakeModel("")).check("Paid $45 for gas yesterday", o, a.ledger, a.calendar, today)
        a.everyday.showProposal(p); settle()
        shot(a, "phone-1-everyday-proposal")
        assertEquals(3, a.ledger.entries.size)
        click(a.everyday, "Save"); settle()
        assertEquals(4, a.ledger.entries.size)
        assertEquals(-4500L, a.ledger.entries.first { it.description == "Gas" }.cents)
        shot(a, "phone-2-ledger")
        // the whole round trip through a (fake) Gemma on the background thread
        a.testModel = FakeModel("""{"action":"add_event","reply":"","event":{"title":"Dentist","date":"${today.plusDays(3)}","start":"15:00","minutes":45,"place":"Dr. Lee","remind_minutes":60}}""")
        val input = views(a.everyday).first { it is android.widget.EditText } as android.widget.EditText
        input.setText("Dentist in 3 days at 3pm at Dr. Lee"); click(a.everyday, "Send")
        repeat(20) { settle() }
        click(a.everyday, "Save"); settle()
        assertEquals(LocalTime.of(15, 0), a.calendar.events.single().start)
        shot(a, "phone-3-calendar")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-port-xxhdpi") fun phoneDesignScreenMovesAndResizes() {
        val a = launch()
        val page = Designer.tidy(JSONObject(BAKERY), Page(name = "Home", frame = Frame.PHONE))
        val proj = Project(name = "Rosa's Bakery", pages = listOf(page))
        a.projects.save(proj)
        a.show(1); settle()
        a.make.open(proj); settle()
        shot(a, "phone-4-design")
        val c = a.make.canvas
        val btn = c.page.items.first { it.type == "button" }
        // drag the button down 40 px (page pixels)
        val from = c.toView(btn.x + btn.w / 2f, btn.y + btn.h / 2f)
        val to = c.toView(btn.x + btn.w / 2f, btn.y + btn.h / 2f + 40)
        drag(c, from[0], from[1], to[0], to[1])
        val moved = c.page.items.first { it.id == btn.id }
        assertEquals(btn.x, moved.x)
        assertTrue("moved ~40px: ${moved.y} vs ${btn.y}", kotlin.math.abs(moved.y - (btn.y + 40)) <= 8)
        assertEquals(c.selectedId, btn.id)
        // drag its bottom-right corner to make it smaller
        val corner = c.toView(moved.right().toFloat(), moved.bottom().toFloat())
        val corner2 = c.toView(moved.right() - 100f, moved.bottom() + 20f)
        drag(c, corner[0], corner[1], corner2[0], corner2[1])
        val sized = c.page.items.first { it.id == btn.id }
        assertTrue("narrower: ${sized.w}", kotlin.math.abs(sized.w - (moved.w - 100)) <= 8)
        assertTrue("taller: ${sized.h}", sized.h > moved.h)
        shot(a, "phone-5-design-selected")
        // undo twice brings it back; the project was saved
        click(a.make, "Undo"); click(a.make, "Undo"); settle()
        assertEquals(btn, c.page.items.first { it.id == btn.id })
        a.make.saveNow()
        assertEquals(page.items.size, a.projects.all().first().pages[0].items.size)
        // the exported site
        val z = java.util.zip.ZipFile(a.make.zip(a.projects.all().first()))
        assertNotNull(z.getEntry("index.html")); assertNotNull(z.getEntry("home.html"))
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi") fun tabletScreens() {
        val a = launch()
        val today = LocalDate.now()
        a.calendar.add(Event(title = "Team meeting", date = today, start = LocalTime.of(10, 0), place = "Zoom"))
        a.calendar.add(Event(title = "Dentist", date = today.plusDays(4), start = LocalTime.of(15, 0), place = "Dr. Lee"))
        a.show(0); settle()
        shot(a, "tablet-1-everyday")
        val page = Designer.tidy(JSONObject(BAKERY), Page(name = "Home", frame = Frame.PHONE))
        val proj = Project(name = "Rosa's Bakery", pages = listOf(page))
        a.show(1); settle(); a.make.open(proj); settle()
        a.make.canvas.select(page.items.first { it.type == "button" }.id); settle()
        shot(a, "tablet-2-design")
        a.make.refresh(); a.show(1); settle()
        // the model setup sheet
        a.showModelSetup(); settle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val v = d.window!!.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        FileOutputStream(File(out, "tablet-3-model.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
