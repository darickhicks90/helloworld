package app.gemmadesk.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** Writes a file safely: a crash mid-write never leaves a half-written file. */
fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) { delete(); tmp.renameTo(this) }
}

fun newId(): String = UUID.randomUUID().toString().take(12)

/* ======================= Ledger ======================= */

/** One line in the ledger. Money is kept in cents so totals are exact. */
data class Entry(
    val id: String = newId(),
    val date: LocalDate,
    val description: String,
    /** positive = money in, negative = money out */
    val cents: Long,
    val category: String = "",
    val account: String = "Main",
) {
    fun toJson() = JSONObject().put("id", id).put("date", date.toString()).put("description", description)
        .put("cents", cents).put("category", category).put("account", account)
    companion object {
        fun fromJson(o: JSONObject) = Entry(o.getString("id"), LocalDate.parse(o.getString("date")), o.optString("description"),
            o.getLong("cents"), o.optString("category"), o.optString("account", "Main"))
    }
}

/** Format cents as money: 1234 → "$12.34", -500 → "−$5.00". */
fun money(cents: Long, sign: Boolean = false): String {
    val a = kotlin.math.abs(cents)
    val s = "$" + "%,d".format(a / 100) + "." + "%02d".format(a % 100)
    return when { cents < 0 -> "−$s"; sign && cents > 0 -> "+$s"; else -> s }
}

/** Parse "12.34", "$1,200", "-5" into cents. */
fun parseCents(text: String): Long? {
    val t = text.trim().replace("$", "").replace(",", "").replace("−", "-")
    val d = t.toBigDecimalOrNull() ?: return null
    return d.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
}

class Ledger(private val file: File) {
    val entries = ArrayList<Entry>()
    init { load() }

    private fun load() {
        entries.clear()
        if (!file.exists()) return
        runCatching { JSONArray(file.readText()) }.getOrNull()?.let { a -> for (i in 0 until a.length()) runCatching { entries += Entry.fromJson(a.getJSONObject(i)) } }
        sort()
    }
    private fun sort() = entries.sortWith(compareByDescending<Entry> { it.date }.thenBy { it.description })
    fun save() = file.writeAtomically(JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString(1))

    fun add(e: Entry) { entries += e; sort(); save() }
    fun update(e: Entry) { val i = entries.indexOfFirst { it.id == e.id }; if (i >= 0) entries[i] = e else entries += e; sort(); save() }
    fun remove(id: String) { entries.removeAll { it.id == id }; save() }

    fun balance(account: String? = null) = entries.filter { account == null || it.account == account }.sumOf { it.cents }
    fun accounts() = (listOf("Main") + entries.map { it.account }).distinct()
    fun categories() = entries.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

    /** Totals for a question like "how much on gas this month": computed here, never by the AI. */
    fun total(from: LocalDate, to: LocalDate, category: String? = null, search: String? = null, account: String? = null): Pair<Long, Int> {
        val list = entries.filter { e ->
            !e.date.isBefore(from) && !e.date.isAfter(to) &&
                (category.isNullOrBlank() || e.category.equals(category, true)) &&
                (search.isNullOrBlank() || e.description.contains(search, true) || e.category.contains(search, true)) &&
                (account.isNullOrBlank() || e.account.equals(account, true))
        }
        return list.sumOf { it.cents } to list.size
    }

    fun toCsv(): String {
        fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder("Date,Description,Category,Account,Amount\n")
        for (e in entries.sortedBy { it.date }) sb.append("${e.date},${q(e.description)},${q(e.category)},${q(e.account)},${java.math.BigDecimal(e.cents).movePointLeft(2)}\n")
        return sb.toString()
    }
}

/* ======================= Calendar ======================= */

data class Event(
    val id: String = newId(),
    val title: String,
    val date: LocalDate,
    /** null = all day */
    val start: LocalTime? = null,
    val minutes: Int = 60,
    val place: String = "",
    val notes: String = "",
    /** minutes before the start to remind; null = no reminder */
    val remind: Int? = 30,
) {
    fun startsAt(): LocalDateTime = date.atTime(start ?: LocalTime.of(9, 0))
    fun toJson() = JSONObject().put("id", id).put("title", title).put("date", date.toString()).put("start", start?.toString() ?: JSONObject.NULL)
        .put("minutes", minutes).put("place", place).put("notes", notes).put("remind", remind ?: JSONObject.NULL)
    companion object {
        fun fromJson(o: JSONObject) = Event(o.getString("id"), o.getString("title"), LocalDate.parse(o.getString("date")),
            if (o.isNull("start")) null else LocalTime.parse(o.getString("start")), o.optInt("minutes", 60), o.optString("place"), o.optString("notes"),
            if (o.isNull("remind")) null else o.optInt("remind"))
    }
}

class Calendar(private val file: File) {
    val events = ArrayList<Event>()
    init {
        if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrNull()?.let { a -> for (i in 0 until a.length()) runCatching { events += Event.fromJson(a.getJSONObject(i)) } }
        sort()
    }
    private fun sort() = events.sortWith(compareBy<Event> { it.date }.thenBy { it.start ?: LocalTime.MIN })
    fun save() = file.writeAtomically(JSONArray().apply { events.forEach { put(it.toJson()) } }.toString(1))
    fun add(e: Event) { events += e; sort(); save() }
    fun update(e: Event) { val i = events.indexOfFirst { it.id == e.id }; if (i >= 0) events[i] = e else events += e; sort(); save() }
    fun remove(id: String) { events.removeAll { it.id == id }; save() }
    fun on(d: LocalDate) = events.filter { it.date == d }
    fun upcoming(from: LocalDateTime, n: Int = 5) = events.filter { !it.startsAt().isBefore(from.toLocalDate().atStartOfDay()) }.take(n)
    /** Events that overlap [e] on the same day (to warn about double-booking). */
    fun clashes(e: Event): List<Event> {
        val s = e.start ?: return emptyList()
        val a = s.toSecondOfDay(); val b = a + e.minutes * 60
        return on(e.date).filter { o -> o.id != e.id && o.start != null && o.start.toSecondOfDay() < b && o.start.toSecondOfDay() + o.minutes * 60 > a }
    }
}
