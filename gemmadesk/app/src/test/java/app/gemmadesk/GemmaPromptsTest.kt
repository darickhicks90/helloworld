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
    }

    @Test fun writePrompts() {
        val dir = File("build/eval").apply { mkdirs() }
        File(dir, "prompts.jsonl").printWriter().use { w ->
            for ((id, frame, d) in DESIGNS) w.println(JSONObject().put("id", "design-$id").put("prompt", Designer.newPrompt(d, Page(name = "Home", frame = frame))).put("schema", Designer.SCHEMA).put("max", 3000).toString())
            val edit = Designer.tidy(JSONObject(AppTest.BAKERY), Page(name = "Home", frame = Frame.PHONE))
            w.println(JSONObject().put("id", "edit-bakery").put("prompt", Designer.editPrompt("Make the Order for pickup button green and put it right under the photo.", edit)).put("schema", Designer.SCHEMA).put("max", 3000).toString())
            ASKS.forEachIndexed { i, t -> w.println(JSONObject().put("id", "ask-$i").put("prompt", Assistant.prompt(t, TODAY, listOf("Groceries", "Fuel", "Dining"), listOf("Main"))).put("schema", Assistant.SCHEMA).put("max", 700).toString()) }
        }
    }
}
