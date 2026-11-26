package app.gemmadesk

import app.gemmadesk.assist.Assistant
import app.gemmadesk.assist.Designer
import app.gemmadesk.data.Frame
import app.gemmadesk.data.Page
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Writes the exact prompts the app sends to Gemma, so they can be run on the
 * real model (tools/run_gemma.py) and the replies checked (GemmaRepliesTest).
 */
class GemmaPromptsTest {
    companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 11, 26)
        val DESIGNS = listOf(
            Triple("bakery", Frame.PHONE, "A home page for a bakery called Rosa's: the name at the top, a big photo, three cards for bread, cakes and coffee, opening hours, and an Order for pickup button."),
            Triple("signin", Frame.PHONE, "A sign-in screen for a fitness app called FitTrack: logo text, email and password fields, a Sign in button, a 'Forgot password?' link and a 'Create account' link."),
            Triple("plumber", Frame.DESKTOP, "A landing page for Ace Plumbing: a navigation bar, a big headline with a Call now button, three services (repairs, installs, emergencies), a customer quote, and contact details at the bottom."))
        val ASKS = listOf(
            "Paid \$45 for gas yesterday",
            "Got my paycheck today, \$1,850",
            "Lunch with Sam 12.50 and coffee 4.75",
            "Dentist next Tuesday at 3pm",
            "Team meeting tomorrow at 10:30 for an hour on Zoom",
            "How much did I spend on groceries this month?",
            "What's on my calendar next week?",
            "Rent \$1,200 on December 1st")
        val LIBRARY = listOf(
            "210.8 Ground-Fault Circuit-Interrupter Protection for Personnel. (A) Dwelling Units. All 125-volt through 250-volt receptacles installed in the locations specified in 210.8(A)(1) through (A)(11) and supplied by single-phase branch circuits rated 150 volts or less to ground shall have ground-fault circuit-interrupter protection for personnel. (1) Bathrooms (2) Garages and accessory buildings (3) Outdoors (4) Crawl spaces (5) Basements (6) Kitchens where the receptacles are installed to serve the countertop surfaces (7) Sinks, where receptacles are installed within 1.8 m (6 ft) from the top inside edge of the bowl of the sink.",
            "240.4(D) Small Conductors. Unless specifically permitted in 240.4(E) or (G), the overcurrent protection shall not exceed that required by (D)(1) through (D)(7) after any correction factors for ambient temperature and number of conductors have been applied. (5) 14 AWG Copper. 15 amperes. (6) 12 AWG Aluminum and Copper-Clad Aluminum. 15 amperes. (7) 12 AWG Copper. 20 amperes. 10 AWG Copper. 30 amperes.",
            "406.4(D) Replacements. Replacement of receptacles shall comply with 406.4(D)(1) through (D)(7). (2) Non-Grounding-Type Receptacles. Where attachment to an equipment grounding conductor does not exist in the receptacle enclosure, a non-grounding-type receptacle may be replaced with a ground-fault circuit-interrupter type of receptacle, marked 'No Equipment Ground'.")
        val QUESTIONS = listOf("Do I need GFCI for receptacles near a laundry sink?", "What's the biggest breaker I can put on 12 gauge copper wire?",
            "Can I replace a two-prong outlet with a three-prong one if there's no ground wire?", "How deep do I bury PVC conduit under a driveway?")
    }

    @Test fun writePrompts() {
        val dir = File("build/eval").apply { mkdirs() }
        File(dir, "prompts.jsonl").printWriter().use { w ->
            for ((id, frame, d) in DESIGNS) w.println(JSONObject().put("id", "design-$id").put("prompt", Designer.newPrompt(d, Page(name = "Home", frame = frame))).put("schema", Designer.SCHEMA).put("max", 3000).toString())
            val edit = Designer.tidy(JSONObject(AppTest.BAKERY), Page(name = "Home", frame = Frame.PHONE))
            w.println(JSONObject().put("id", "edit-bakery").put("prompt", Designer.editPrompt("Make the Order for pickup button green and put it right under the photo.", edit)).put("schema", Designer.SCHEMA).put("max", 3000).toString())
            w.println(JSONObject().put("id", "build-bakery").put("prompt", app.gemmadesk.assist.Builder.prompt(edit)).put("max", 5000).toString())
            val lib = app.gemmadesk.data.Library(kotlin.io.path.createTempDirectory("lib").toFile())
            lib.addText("NEC sample.pdf", "pdf", LIBRARY)
            for ((k, q) in QUESTIONS.withIndex()) w.println(JSONObject().put("id", "lib-$k").put("prompt", app.gemmadesk.assist.Reader.prompt(q, lib.search(q, 6))).put("schema", app.gemmadesk.assist.Reader.SCHEMA).put("max", 900).toString())
            ASKS.forEachIndexed { i, t -> w.println(JSONObject().put("id", "ask-$i").put("prompt", Assistant.prompt(t, TODAY, listOf("Groceries", "Fuel", "Dining"), listOf("Main"))).put("schema", Assistant.SCHEMA).put("max", 700).toString()) }
        }
    }
}
