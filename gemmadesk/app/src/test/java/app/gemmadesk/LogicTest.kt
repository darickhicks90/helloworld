package app.gemmadesk

import app.gemmadesk.assist.Assistant
import app.gemmadesk.assist.DateWords
import app.gemmadesk.assist.Designer
import app.gemmadesk.data.*
import app.gemmadesk.llm.LanguageModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Gemma stand-in: returns canned JSON and records the prompt. */
open class FakeModel(var reply: String) : LanguageModel {
    var lastPrompt = ""
    override fun generateJson(prompt: String, schema: String, maxTokens: Int, temperature: Float): String { lastPrompt = prompt; JSONObject(schema); return reply }
}

class LogicTest {
    private val today = LocalDate.of(2026, 11, 26) // a Thursday
    private fun tmp() = kotlin.io.path.createTempDirectory("gd").toFile()

    @Test fun datesAndTimesInPlainWords() {
        assertEquals(today.plusDays(1), DateWords.date("dentist tomorrow at 3pm", today))
        assertEquals(today.minusDays(1), DateWords.date("Paid $45 for gas yesterday", today))
        assertEquals(LocalDate.of(2026, 12, 1), DateWords.date("next Tuesday", today))
        assertEquals(LocalDate.of(2026, 11, 27), DateWords.date("on friday", today))
        assertEquals(LocalDate.of(2026, 11, 20), DateWords.date("last friday", today))
        assertEquals(LocalDate.of(2026, 12, 3), DateWords.date("Dec 3rd lunch", today))
        assertEquals(LocalDate.of(2026, 12, 3), DateWords.date("on 12/3", today))
        assertNull(DateWords.date("bought groceries", today))
        assertEquals(LocalTime.of(15, 0), DateWords.time("dentist at 3pm"))
        assertEquals(LocalTime.of(9, 30), DateWords.time("meeting 9:30 am"))
        assertEquals(LocalTime.of(15, 0), DateWords.time("coffee at 3"))
        assertEquals(LocalTime.of(19, 0), DateWords.time("dinner at 7"))
        assertEquals(LocalTime.NOON, DateWords.time("lunch at noon"))
        assertNull(DateWords.time("dentist next tuesday"))
    }

    @Test fun amountsWrittenInTheText() {
        assertEquals(setOf(4500L), DateWords.amounts("Paid $45 for gas"))
        assertEquals(setOf(1250L, 120000L), DateWords.amounts("lunch 12.50 and rent $1,200"))
        assertEquals(setOf(4000L), DateWords.amounts("paid $40 at 3pm on 12/3"))
        assertEquals(setOf(250000L), DateWords.amounts("got a 2.5k bonus"))
    }

    @Test fun spendingIsCheckedAgainstWhatWasWritten() {
        val d = tmp(); val led = Ledger(File(d, "l.json")); val cal = Calendar(File(d, "c.json"))
        val good = JSONObject("""{"action":"add_entries","reply":"ok","entries":[{"date":"2026-11-25","description":"Gas","amount":45,"direction":"out","category":"Fuel"}]}""")
        val p = Assistant(FakeModel("")).check("Paid $45 for gas yesterday", good, led, cal, today) as Assistant.Proposal.AddEntries
        assertEquals(-4500L, p.entries[0].cents); assertEquals(today.minusDays(1), p.entries[0].date); assertTrue(p.notes.toString(), p.notes.isEmpty())
        // Gemma invents a different amount and the wrong day: flagged and corrected
        val bad = JSONObject("""{"action":"add_entries","reply":"ok","entries":[{"date":"2026-11-20","description":"Gas","amount":54,"direction":"out","category":"Fuel"}]}""")
        val q = Assistant(FakeModel("")).check("Paid $45 for gas yesterday", bad, led, cal, today) as Assistant.Proposal.AddEntries
        assertEquals(today.minusDays(1), q.entries[0].date)
        assertTrue(q.notes.toString(), q.notes.any { it.contains("isn't in what you wrote") })
        // income
        val inc = JSONObject("""{"action":"add_entries","reply":"","entries":[{"date":"2026-11-26","description":"Paycheck","amount":1200,"direction":"in","category":"Salary"}]}""")
        assertEquals(120000L, (Assistant(FakeModel("")).check("got paid $1,200 today", inc, led, cal, today) as Assistant.Proposal.AddEntries).entries[0].cents)
    }

    @Test fun appointmentTimesAreReadAgainByCode() {
        val d = tmp(); val led = Ledger(File(d, "l.json")); val cal = Calendar(File(d, "c.json"))
        cal.add(Event(title = "Gym", date = LocalDate.of(2026, 12, 1), start = LocalTime.of(15, 30), minutes = 60))
        val o = JSONObject("""{"action":"add_event","reply":"","event":{"title":"Dentist","date":"2026-12-02","start":"03:00","minutes":60,"place":"","remind_minutes":30}}""")
        val p = Assistant(FakeModel("")).check("Dentist next Tuesday at 3pm", o, led, cal, today) as Assistant.Proposal.AddEvent
        assertEquals(LocalDate.of(2026, 12, 1), p.event.date)
        assertEquals(LocalTime.of(15, 0), p.event.start)
        assertEquals("clash with the gym", 1, p.clashes.size)
        assertTrue(p.notes.size >= 2)
    }

    @Test fun totalsAreAddedUpByTheApp() {
        val d = tmp(); val led = Ledger(File(d, "l.json")); val cal = Calendar(File(d, "c.json"))
        led.add(Entry(date = today.minusDays(3), description = "Shell", cents = -4000, category = "Fuel"))
        led.add(Entry(date = today.minusDays(1), description = "BP gas", cents = -3550, category = "Fuel"))
        led.add(Entry(date = today.minusDays(40), description = "old gas", cents = -9999, category = "Fuel"))
        led.add(Entry(date = today, description = "Groceries", cents = -8000, category = "Groceries"))
        // Gemma only gives the range and the category; it never does the sum
        val o = JSONObject("""{"action":"ledger_total","reply":"","query":{"from":"2026-11-01","to":"2026-11-26","category":"fuel"}}""")
        val t = Assistant(FakeModel("")).check("how much did I spend on gas this month", o, led, cal, today) as Assistant.Proposal.Total
        assertEquals(-7550L, t.cents); assertEquals(2, t.count)
        // saved and loaded back exactly
        assertEquals(4, Ledger(File(d, "l.json")).entries.size)
        assertTrue(led.toCsv().contains("-35.50"))
    }

    @Test fun gemmaLayoutsAreTidiedToFitTheScreen() {
        val page = Page(name = "Home", frame = Frame.PHONE)
        val o = JSONObject("""{"background":"#FFF8F0","items":[
          {"type":"heading","x":20,"y":30,"w":500,"h":20,"text":"Rosa's Bakery, fresh bread every morning","fontSize":30},
          {"type":"weird","x":-40,"y":100,"w":100,"h":50,"text":""},
          {"type":"button","x":300,"y":200,"w":160,"h":48,"text":"Order","color":"red; background:url(x)","background":"#C62828"}]}""")
        val p = Designer.tidy(o, page)
        assertEquals("#FFF8F0", p.background)
        for (i in p.items) assertTrue("$i fits", i.x >= 0 && i.right() <= 390)
        assertEquals("box", p.items[1].type)
        assertTrue("heading grew to fit its words", p.items[0].h >= 2 * 30 * 1.3)
        assertEquals("unsafe colour replaced", "#FFFFFF", p.items[2].color)
        assertEquals("on the 4 px grid", 0, p.items.sumOf { it.x % 4 + it.y % 4 })
    }

    @Test fun designFromAFakeGemmaAndEditKeepsIds() {
        val page = Page(name = "Home", frame = Frame.PHONE)
        val m = FakeModel("""{"background":"#FFFFFF","items":[{"type":"heading","x":24,"y":40,"w":342,"h":40,"text":"Hello"},{"type":"button","x":24,"y":120,"w":160,"h":48,"text":"Go"}]}""")
        val p = Designer(m).design("a hello page", page)!!
        assertTrue(m.lastPrompt.contains("390 px wide"))
        val id = p.items[1].id
        m.reply = """{"background":"#FFFFFF","items":[{"id":"${p.items[0].id}","type":"heading","x":24,"y":40,"w":342,"h":40,"text":"Hello"},{"id":"$id","type":"button","x":24,"y":120,"w":342,"h":56,"text":"Go now","background":"#2E7D32"}]}"""
        val e = Designer(m).edit("make the button full width and green", p)!!
        assertTrue(m.lastPrompt.contains(id))
        assertEquals(id, e.items[1].id); assertEquals("#2E7D32", e.items[1].background)
    }

    @Test fun htmlMatchesTheDesignAndIsSafe() {
        val p = Page(name = "My Home", frame = Frame.PHONE, items = listOf(
            Item(type = "heading", x = 24, y = 40, w = 342, h = 40, text = "<script>alert(1)</script> & co", fontSize = 28, bold = true),
            Item(type = "button", x = 24, y = 120, w = 160, h = 48, text = "Order", background = "#3D5AFE", color = "#FFFFFF", radius = 10, link = "javascript:alert(1)"),
            Item(type = "link", x = 24, y = 200, w = 100, h = 24, text = "About", link = "about.html")))
        val h = Html.page(p)
        assertTrue(h.contains("left:24px;top:40px;width:342px;height:40px;"))
        assertTrue(h.contains("width:390px"))
        assertFalse("text is escaped", h.contains("<script>alert"))
        assertTrue(h.contains("&lt;script&gt;"))
        assertFalse("unsafe links dropped", h.contains("javascript:"))
        assertTrue(h.contains("href=\"about.html\""))
        assertEquals("my-home.html", Html.pageFile(p))
        // round trip through storage
        val back = Project.fromJson(Project(name = "X", pages = listOf(p)).toJson())
        assertEquals(p.items, back.pages[0].items)
    }
}
