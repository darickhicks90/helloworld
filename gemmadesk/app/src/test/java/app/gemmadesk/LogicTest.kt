package app.gemmadesk

import app.gemmadesk.assist.Assistant
import app.gemmadesk.assist.DateWords
import app.gemmadesk.assist.Designer
import app.gemmadesk.assist.Reader
import app.gemmadesk.data.*
import app.gemmadesk.llm.LanguageModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Gemma stand-in: returns canned JSON and records the prompt. */
open class FakeModel(var reply: String, var text: String = "", val replies: ArrayDeque<String> = ArrayDeque()) : LanguageModel {
    var lastPrompt = ""
    val prompts = ArrayList<String>()
    override fun generateJson(prompt: String, schema: String, maxTokens: Int, temperature: Float): String { lastPrompt = prompt; prompts += prompt; JSONObject(schema); return replies.removeFirstOrNull() ?: reply }
    override fun generateText(prompt: String, maxTokens: Int, temperature: Float): String { lastPrompt = prompt; prompts += prompt; return text }
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

    private fun bakery() = Designer.tidy(JSONObject(AppTest.BAKERY), Page(name = "Home", frame = Frame.PHONE))

    @Test fun mockupOutlineReadsLikeAPerson() {
        val o = app.gemmadesk.assist.Builder.outline(bakery())
        assertTrue(o, o.contains("full-width band"))
        assertTrue(o, o.contains("a row of 2 side by side"))
        assertTrue(o, o.contains("button \"Order for pickup\""))
        assertTrue(o, o.indexOf("Rosa's Bakery") < o.indexOf("Order for pickup"))
    }

    @Test fun gemmasBuildIsCheckedAgainstTheMockup() {
        val p = bakery()
        val texts = p.items.filter { it.text.isNotBlank() && it.type != "box" }.flatMap { it.text.split('\n') }
        val good = "```html\n<!doctype html><html><head><style>body{background:#fff8f0}.b{background:#5d4037}.c{background:#C62828;color:#ffffff}.i{background:#E8D5C4}</style>" +
            "<link rel=stylesheet href=https://fonts.example/x.css></head><body>" + texts.joinToString("") { "<p>${it.replace("'", "&#39;")}</p>" } +
            "<input placeholder=\"x\"><img src=\"https://tracker.example/p.png\"><button onclick=\"steal()\">x</button><script>alert(1)</script></body></html>\n```"
        val r = app.gemmadesk.assist.Builder.check(p, good)
        assertTrue(r.missingText.toString(), r.missingText.isEmpty())
        assertTrue(r.complete)
        assertFalse(r.html.contains("<script")); assertFalse(r.html.contains("onclick")); assertFalse(r.html.contains("tracker.example")); assertFalse(r.html.contains("<link"))
        assertEquals(setOf("scripts", "event handlers", "external stylesheets or fonts", "pictures loaded from the internet"), r.removed.toSet())
        // a page that dropped a line and was cut off
        val bad = app.gemmadesk.assist.Builder.check(p, "<!doctype html><html><body><h1>Rosa's Bakery</h1><p>Baked fresh every morning</p>")
        assertFalse(bad.complete); assertFalse(bad.ok)
        assertTrue(bad.missingText.contains("Order for pickup"))
        // the prompt carries the outline and the rules
        val m = FakeModel("", text = good)
        app.gemmadesk.assist.Builder.build(m, p)
        assertTrue(m.lastPrompt.contains("No JavaScript") && m.lastPrompt.contains("Order for pickup"))
    }

    @Test fun mockupsGoToClaudeAndComeBack() {
        val p = bakery(); val pr = Project(name = "Rosa's Bakery", pages = listOf(p))
        val brief = Handoff.brief(pr, p, Handoff.defaultRequest(p))
        assertTrue(brief.contains("```json") && brief.contains("gemma-desk-mockup") && brief.contains("Outline"))
        val back = Handoff.parse(brief)!!
        assertEquals("Rosa's Bakery", back.name)
        assertEquals(p.items.map { it.copy(id = "") }, back.pages[0].items.map { it.copy(id = "") })
        // a bare JSON reply in the format, as Claude might write one
        val claude = """Here you go:
```json
{"format":"gemma-desk-mockup","version":1,"project":"Cafe","page":{"name":"Menu","frame":{"name":"Phone","w":390,"h":844},"background":"#FFFFFF",
 "items":[{"type":"heading","x":24,"y":24,"w":342,"h":40,"text":"Menu","fontSize":28,"bold":true},{"type":"rocket","x":0,"y":0,"w":10,"h":10},{"type":"button","x":300,"y":80,"w":200,"h":48,"text":"Order"}]}}
```"""
        val c = Handoff.parse(claude)!!
        assertEquals("Cafe", c.name); assertEquals(2, c.pages[0].items.size)
        assertTrue("kept inside the frame", c.pages[0].items.all { it.right() <= 390 })
        assertNull(Handoff.parse("no mockup here"))
    }

    @Test fun overlappingItemsArePulledApart() {
        val items = listOf(
            Item(type = "box", x = 0, y = 0, w = 390, h = 60, background = "#000000"),
            Item(type = "link", x = 16, y = 20, w = 100, h = 24, text = "Home"),
            Item(type = "link", x = 16, y = 20, w = 100, h = 24, text = "About"),
            Item(type = "text", x = 16, y = 80, w = 300, h = 40, text = "Hello"))
        val out = Designer.unoverlap(items)
        val home = out[1]; val about = out[2]; val hello = out[3]
        assertTrue(about.y >= home.bottom())
        assertTrue("text below moved down too", hello.y >= about.bottom())
        assertTrue("the band grew to hold them", out[0].bottom() >= about.bottom())
    }

    private val NEC = listOf(
        "210.8 Ground-Fault Circuit-Interrupter Protection for Personnel. (A) Dwelling Units. All 125-volt through 250-volt receptacles installed in the locations specified in 210.8(A)(1) through (A)(11) shall have ground-fault circuit-interrupter protection for personnel. (1) Bathrooms (2) Garages and accessory buildings (5) Basements (6) Kitchens where the receptacles are installed to serve the countertop surfaces.",
        "240.4(D) Small Conductors. Unless specifically permitted, the overcurrent protection shall not exceed: (3) 18 AWG copper 7 amperes; (5) 14 AWG copper 15 amperes; (6) 12 AWG copper 20 amperes; (7) 10 AWG copper 30 amperes.",
        "Table 310.16 Ampacities of insulated conductors. Copper 60°C column: 14 AWG 15, 12 AWG 20, 10 AWG 30, 8 AWG 40.")

    @Test fun libraryFindsTheRightPassage() {
        val dir = tmp()
        val lib = Library(dir)
        lib.addText("NEC sample.pdf", "pdf", NEC)
        lib.addText("Dishwasher manual.txt", "text", listOf("To clean the filter, twist it counter-clockwise and lift it out. Rinse under warm water. Clean the filter every month."))
        assertEquals("240.4(D) Small Conductors", lib.search("What size breaker for 10 AWG copper?", 3)[0].text.take(25))
        assertTrue(lib.search("Do kitchen countertop receptacles need GFCI?", 2)[0].text.startsWith("210.8"))
        assertEquals("Dishwasher manual.txt", lib.search("how do I clean the dishwasher filter", 1)[0].doc.name)
        assertTrue(lib.search("210.8(A)", 1)[0].text.startsWith("210.8"))
        // long text is split into passages that overlap a little
        val long = (1..60).joinToString(" ") { "Sentence number $it explains one step of the procedure." }
        val parts = lib.chunks(long)
        assertTrue(parts.size >= 3); assertTrue(parts.all { it.length <= 1000 })
        // survives a restart
        assertEquals(2, Library(dir).docs.size)
        assertEquals("240.4(D) Small Conductors", Library(dir).search("10 AWG copper breaker", 1)[0].text.take(25))
    }

    @Test fun answersQuoteTheirSourcesAndAreChecked() {
        val lib = Library(tmp())
        lib.addText("NEC sample.pdf", "pdf", NEC)
        val m = FakeModel("""{"found":true,"answer":"A 30 A breaker. 240.4(D)(7) limits 10 AWG copper to 30 amperes.","sources":[{"n":1,"quote":"10 AWG copper 30 amperes"},{"n":2,"quote":"10 AWG copper 35 amperes"}],"search_terms":[]}""")
        val a = Reader(m, lib).ask("What size breaker for 10 AWG copper?")
        assertTrue(a.found)
        assertTrue(m.lastPrompt.contains("[1] (NEC sample.pdf, page 2)"))
        assertTrue(a.sources[0].quoteFound)
        assertFalse("a misquote is caught", a.sources[1].quoteFound)
        assertTrue(a.notes.any { it.contains("couldn't be found") })
        // not covered: Gemma suggests words, the app searches again once
        val lib2 = Library(tmp()); lib2.addText("Dishwasher manual.txt", "text", listOf("To clean the filter, twist it counter-clockwise and lift it out. Rinse under warm water."), 0)
        lib2.addText("Dryer manual.txt", "text", listOf("Lint screen: pull the screen straight up and wipe off lint after every load."), 0)
        val m2 = FakeModel("", replies = ArrayDeque(listOf(
            """{"found":false,"answer":"The passages don't mention lint.","sources":[],"search_terms":["lint screen"]}""",
            """{"found":true,"answer":"Pull the lint screen straight up and wipe it after every load.","sources":[{"n":1,"quote":"pull the screen straight up and wipe off lint"}],"search_terms":[]}""")))
        val b = Reader(m2, lib2).ask("how do I clean the filter on the dryer")
        assertTrue(b.found); assertEquals("Dryer manual.txt", b.sources[0].passage.doc.name)
        assertTrue(b.notes.any { it.contains("Searched again") })
    }

    @Test fun textStaysReadableAndGapsClose() {
        val items = listOf(
            Item(type = "box", x = 0, y = 0, w = 390, h = 64, background = "#5D4037"),
            Item(type = "heading", x = 24, y = 12, w = 300, h = 40, text = "Rosa's", color = "#1F2430"),
            Item(type = "text", x = 24, y = 300, w = 300, h = 40, text = "Far below", color = "#EEEEEE"))
        val r = Designer.readable(items, "#FFFFFF")
        assertEquals("white on the brown band", "#FFFFFF", r[1].color)
        assertEquals("dark on the white page", "#1F2430", r[2].color)
        val t = Designer.tighten(r)
        assertEquals("gap closed to 24 px", 64 + 24, t[2].y)
    }
}
