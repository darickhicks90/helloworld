package app.gemmadesk.assist

import app.gemmadesk.data.Calendar
import app.gemmadesk.data.Entry
import app.gemmadesk.data.Event
import app.gemmadesk.data.Ledger
import app.gemmadesk.data.money
import app.gemmadesk.llm.LanguageModel
import app.gemmadesk.llm.extractJson
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Everyday helper. You say what happened or what you need in plain
 * words; Gemma turns it into a ledger entry, an appointment or a question,
 * and the app checks it before anything is saved:
 *  - every amount must appear in what you wrote,
 *  - dates and times are read again by ordinary code, which wins if they disagree,
 *  - totals are added up by the app, never by the AI,
 *  - nothing is saved until you tap Save on the card.
 */
class Assistant(private val model: LanguageModel) {

    /** What Gemma proposes, already checked. */
    sealed class Proposal {
        abstract val notes: List<String>
        data class AddEntries(val entries: List<Entry>, override val notes: List<String>) : Proposal()
        data class AddEvent(val event: Event, val clashes: List<Event>, override val notes: List<String>) : Proposal()
        data class Total(val from: LocalDate, val to: LocalDate, val category: String?, val search: String?, val cents: Long, val count: Int, override val notes: List<String> = emptyList()) : Proposal()
        data class Events(val from: LocalDate, val to: LocalDate, val events: List<Event>, override val notes: List<String> = emptyList()) : Proposal()
        data class Reply(val text: String, override val notes: List<String> = emptyList()) : Proposal()
    }

    companion object {
        val SCHEMA = """
{"type":"object","properties":{
 "action":{"type":"string","enum":["add_entries","add_event","ledger_total","list_events","answer"]},
 "entries":{"type":"array","items":{"type":"object","properties":{
   "date":{"type":"string"},"description":{"type":"string"},"amount":{"type":"number"},
   "direction":{"type":"string","enum":["out","in"]},"category":{"type":"string"},"account":{"type":"string"}},
   "required":["date","description","amount","direction","category"],"additionalProperties":false}},
 "event":{"type":"object","properties":{
   "title":{"type":"string"},"date":{"type":"string"},"start":{"type":"string"},"minutes":{"type":"integer"},
   "place":{"type":"string"},"remind_minutes":{"type":"integer"}},"required":["title","date","start"],"additionalProperties":false},
 "query":{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"},"category":{"type":"string"},"search":{"type":"string"}},"required":["from","to"],"additionalProperties":false},
 "reply":{"type":"string"}},
"required":["action","reply"],"additionalProperties":false}
""".trimIndent()

        private val DAY = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.US)

        fun prompt(text: String, today: LocalDate, categories: List<String>, accounts: List<String>): String {
            val days = (-14..30).joinToString(", ") { val d = today.plusDays(it.toLong()); (if (it == 0) "TODAY " else "") + DAY.format(d) }
            return """
You are the helper in a personal ledger and calendar app. Turn the user's message into ONE action as JSON.

Today is ${DAY.format(today)}. Days around today: $days.

Actions:
- "add_entries": the user spent, paid, earned or received money. One entry per amount. "amount" is the number exactly as the user wrote it (positive). "direction" is "out" for spending/paying/bills, "in" for income/refunds/getting paid. "category" is one short word or two (e.g. Groceries, Fuel, Rent, Salary, Dining, Utilities, Shopping, Health, Transport). Existing categories: ${categories.joinToString(", ").ifEmpty { "none yet" }}. Accounts: ${accounts.joinToString(", ")} (use Main unless the user names one). Date is the day it happened (YYYY-MM-DD; today if not said).
- "add_event": an appointment, meeting or reminder. "date" YYYY-MM-DD, "start" HH:MM in 24-hour time ("" if all day), "minutes" how long (60 if not said), "place" if given, "remind_minutes" before it (30 if not said).
- "ledger_total": a question about how much was spent or earned. Give "from" and "to" (YYYY-MM-DD) and optionally "category" or a "search" word. Do NOT add anything up yourself.
- "list_events": a question about what is on the calendar. Give "from" and "to".
- "answer": anything else; put a short helpful answer in "reply".
Always write a short friendly "reply" saying what you understood. Never invent amounts, dates or times the user didn't give.

User: $text
""".trim()
        }
    }

    fun ask(text: String, ledger: Ledger, calendar: Calendar, today: LocalDate = LocalDate.now()): Proposal {
        val raw = model.generateJson(prompt(text, today, ledger.categories(), ledger.accounts()), SCHEMA, 700)
        val o = extractJson(raw)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Proposal.Reply("Sorry, I couldn't understand that. Try saying it another way.")
        return check(text, o, ledger, calendar, today)
    }

    /** Does the message say when at all ("last week", "on the 3rd", "a few days ago")? */
    private fun saysWhen(t: String) = Regex("\\b(week|month|ago|last|yesterday|today|tomorrow|day|morning|night|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\\d{1,2}(st|nd|rd|th))\\b|\\d{1,2}/\\d{1,2}", RegexOption.IGNORE_CASE).containsMatchIn(t)

    /** Check what Gemma proposed against what the user actually wrote. */
    fun check(text: String, o: JSONObject, ledger: Ledger, calendar: Calendar, today: LocalDate): Proposal {
        val reply = o.optString("reply")
        val codeDate = DateWords.date(text, today)
        fun date(s: String?, fallback: LocalDate): LocalDate = runCatching { LocalDate.parse(s?.trim()) }.getOrNull() ?: fallback
        return when (o.optString("action")) {
            "add_entries" -> {
                val written = DateWords.amounts(text)
                val notes = ArrayList<String>()
                val a = o.optJSONArray("entries")
                val list = ArrayList<Entry>()
                for (i in 0 until (a?.length() ?: 0)) {
                    val e = a!!.getJSONObject(i)
                    val cents = Math.round(e.optDouble("amount", 0.0) * 100)
                    if (cents <= 0) continue
                    if (cents !in written) notes += "${money(cents)} isn't in what you wrote. Please check the amount."
                    var d = date(e.optString("date"), today)
                    if (codeDate != null && d != codeDate) { notes += "Date set to ${codeDate} from your words."; d = codeDate }
                    if (codeDate == null && d != today && !saysWhen(text)) { notes += "You didn't say a date, so it's set to today."; d = today }
                    val out = e.optString("direction", "out") != "in"
                    list += Entry(date = d, description = e.optString("description").ifBlank { text.take(40) }.take(80),
                        cents = if (out) -cents else cents, category = e.optString("category").trim().take(30),
                        account = e.optString("account").trim().ifBlank { "Main" }.take(30))
                }
                if (list.isEmpty()) Proposal.Reply("I didn't find an amount to record. Try “Spent $12.50 on lunch”.")
                else {
                    // an amount the user wrote that no entry used
                    val used = list.map { kotlin.math.abs(it.cents) }.toSet()
                    val missed = written.filter { it !in used && it >= 100 }
                    if (missed.isNotEmpty() && list.size < written.size) notes += "You also wrote ${missed.joinToString { money(it) }}, which wasn't added."
                    Proposal.AddEntries(list, notes.distinct())
                }
            }
            "add_event" -> {
                val e = o.optJSONObject("event") ?: return Proposal.Reply(reply.ifBlank { "What's the appointment, and when?" })
                val notes = ArrayList<String>()
                var d = date(e.optString("date"), codeDate ?: today)
                if (codeDate != null && d != codeDate) { notes += "Date set to ${codeDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} $codeDate from your words."; d = codeDate }
                var t = runCatching { LocalTime.parse(e.optString("start").trim().let { if (it.length == 4) "0$it" else it }) }.getOrNull()
                val codeTime = DateWords.time(text)
                if (codeTime != null && t != codeTime) { if (t != null) notes += "Time set to $codeTime from your words."; t = codeTime }
                if (codeTime == null && t != null && !Regex("\\d").containsMatchIn(text.replace(Regex("\\$?\\d+(\\.\\d+)?\\s*(dollars|bucks)"), ""))) {
                    notes += "You didn't give a time, so it's all day."; t = null
                }
                if (d.isBefore(today)) notes += "That date is in the past."
                // a place Gemma made up (none of its words are in the message) is left out
                // (words that only repeat the title, like "Dentist office" for a dentist visit, don't count)
                val titleWords = e.optString("title").lowercase().split(Regex("[^a-z0-9]+")).toSet()
                val place = e.optString("place").trim().takeIf { pl -> pl.isNotEmpty() && pl.lowercase().split(Regex("[^a-z0-9]+")).any { w -> w.length > 2 && w !in titleWords && text.lowercase().contains(w) } } ?: ""
                val ev = Event(title = e.optString("title").ifBlank { text.take(40) }.take(80), date = d, start = t,
                    minutes = e.optInt("minutes", 60).coerceIn(5, 24 * 60), place = place.take(80),
                    remind = e.optInt("remind_minutes", 30).coerceIn(0, 7 * 24 * 60))
                Proposal.AddEvent(ev, calendar.clashes(ev), notes)
            }
            "ledger_total" -> {
                val q = o.optJSONObject("query") ?: JSONObject()
                var from = date(q.optString("from"), today.withDayOfMonth(1)); var to = date(q.optString("to"), today)
                if (to.isBefore(from)) { val x = from; from = to; to = x }
                val cat = q.optString("category").trim().takeIf { c -> c.isNotEmpty() && ledger.categories().any { it.equals(c, true) } }
                val search = q.optString("search").trim().takeIf { it.isNotEmpty() } ?: q.optString("category").trim().takeIf { it.isNotEmpty() && cat == null }
                val (cents, n) = ledger.total(from, to, cat, search)
                Proposal.Total(from, to, cat, search, cents, n)
            }
            "list_events" -> {
                val q = o.optJSONObject("query") ?: JSONObject()
                val from = date(q.optString("from"), codeDate ?: today); val to = date(q.optString("to"), from.plusDays(6))
                Proposal.Events(from, to, calendar.events.filter { !it.date.isBefore(from) && !it.date.isAfter(to) })
            }
            else -> Proposal.Reply(reply.ifBlank { "I'm not sure what you'd like. Try “Paid $40 for gas” or “Dentist next Tuesday at 3pm”." }, listOf("Written by Gemma. Check anything important."))
        }
    }
}
