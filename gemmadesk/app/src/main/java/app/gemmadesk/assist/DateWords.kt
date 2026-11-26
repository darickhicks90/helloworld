package app.gemmadesk.assist

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * Reads dates and times in plain words ("next Tuesday", "tomorrow at 3pm",
 * "Dec 3rd", "12/3 at 15:30") with ordinary code, so the app can double-check
 * what Gemma understood. Returns null when it can't tell.
 */
object DateWords {
    private val DAYS = DayOfWeek.values().associateBy { it.name.lowercase() } +
        DayOfWeek.values().associateBy { it.name.lowercase().take(3) } + mapOf("tues" to DayOfWeek.TUESDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "weds" to DayOfWeek.WEDNESDAY)
    private val MONTHS = Month.values().associateBy { it.name.lowercase() } + Month.values().associateBy { it.name.lowercase().take(3) } + mapOf("sept" to Month.SEPTEMBER)

    fun date(text: String, today: LocalDate): LocalDate? {
        val t = " " + text.lowercase().replace(Regex("[,.!?]"), " ") + " "
        when {
            Regex("\\btoday\\b|\\btonight\\b|\\bthis (morning|afternoon|evening)\\b").containsMatchIn(t) -> return today
            Regex("\\bday after tomorrow\\b").containsMatchIn(t) -> return today.plusDays(2)
            Regex("\\btomorrow\\b").containsMatchIn(t) -> return today.plusDays(1)
            Regex("\\byesterday\\b").containsMatchIn(t) -> return today.minusDays(1)
        }
        Regex("\\bin (\\d+) (day|week)s?\\b").find(t)?.let { m -> val n = m.groupValues[1].toLong(); return if (m.groupValues[2] == "day") today.plusDays(n) else today.plusWeeks(n) }
        Regex("\\b(\\d+) days? ago\\b").find(t)?.let { return today.minusDays(it.groupValues[1].toLong()) }
        // "December 3rd", "dec 3", "3 december"
        val mon = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
        Regex("\\b($mon)\\s+(\\d{1,2})(st|nd|rd|th)?\\b(?:\\s+(\\d{4}))?").find(t)?.let { m -> return dayOf(MONTHS[m.groupValues[1]]!!, m.groupValues[2].toInt(), m.groupValues[4].toIntOrNull(), today) }
        Regex("\\b(\\d{1,2})(st|nd|rd|th)?\\s+(?:of\\s+)?($mon)\\b(?:\\s+(\\d{4}))?").find(t)?.let { m -> return dayOf(MONTHS[m.groupValues[3]]!!, m.groupValues[1].toInt(), m.groupValues[4].toIntOrNull(), today) }
        // 12/3 or 12/3/2026 (month first, as in the US)
        Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").find(t)?.let { m ->
            val mo = m.groupValues[1].toInt(); val d = m.groupValues[2].toInt()
            if (mo in 1..12) return dayOf(Month.of(mo), d, m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }, today)
        }
        Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").find(t)?.let { return runCatching { LocalDate.parse(it.value) }.getOrNull() }
        // weekdays: "next tuesday", "last friday", "on monday", "this friday"
        val day = DAYS.keys.sortedByDescending { it.length }.joinToString("|")
        Regex("\\b(next|last|this|on|coming)?\\s*($day)\\b").find(t)?.let { m ->
            val dow = DAYS[m.groupValues[2]]!!
            return when (m.groupValues[1]) {
                "last" -> today.with(TemporalAdjusters.previous(dow))
                "next" -> if (today.dayOfWeek == dow) today.plusWeeks(1) else today.with(TemporalAdjusters.next(dow))
                else -> today.with(TemporalAdjusters.nextOrSame(dow))
            }
        }
        return null
    }

    /** A day in a month; with no year, the next such day (or this year's, if within the last two months, for spending). */
    private fun dayOf(m: Month, d: Int, year: Int?, today: LocalDate): LocalDate? = runCatching {
        if (year != null) return LocalDate.of(year, m, d)
        val thisYear = LocalDate.of(today.year, m, d)
        if (!thisYear.isBefore(today.minusMonths(2))) thisYear else thisYear.plusYears(1)
    }.getOrNull()

    /** "3pm", "3:30 pm", "15:00", "noon", "at 9" (9 → 9 AM unless it reads as evening). */
    fun time(text: String): LocalTime? {
        val t = " " + text.lowercase() + " "
        if (Regex("\\bnoon\\b|\\bmidday\\b").containsMatchIn(t)) return LocalTime.NOON
        if (Regex("\\bmidnight\\b").containsMatchIn(t)) return LocalTime.MIDNIGHT
        Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)").find(t)?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h !in 1..12 || min > 59) return null
            val pm = m.groupValues[3].startsWith("p")
            if (h == 12) h = 0
            return LocalTime.of(h + if (pm) 12 else 0, min)
        }
        Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(t)?.let { return LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("\\bat (\\d{1,2})\\b(?!\\s*(?:/|-|st|nd|rd|th|%|dollars|bucks))").find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h !in 1..12) return null
            val evening = Regex("\\b(evening|tonight|night|dinner|afternoon)\\b").containsMatchIn(t)
            // "at 3" most likely means the afternoon; "at 9" the morning
            return LocalTime.of(if (evening || h in 1..6) (h % 12) + 12 else h % 12, 0)
        }
        return null
    }

    /** Every amount of money or plain number written in the text, in cents. */
    fun amounts(text: String): Set<Long> {
        val out = HashSet<Long>()
        Regex("(?<![\\w.:/])\\$?\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?(?![\\d/:]|\\s*(?:a\\.?m|p\\.?m|st\\b|nd\\b|rd\\b|th\\b))\\s*(k\\b)?").findAll(text.lowercase()).forEach { m ->
            val whole = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@forEach
            val frac = m.groupValues[2].let { if (it.isEmpty()) 0L else (it + "0").take(2).toLong() }
            var c = whole * 100 + frac
            if (m.groupValues[3] == "k") c *= 1000
            out += c
        }
        // a few spoken amounts
        val words = mapOf("a dollar" to 100L, "one dollar" to 100L, "ten bucks" to 1000L, "twenty bucks" to 2000L, "a hundred" to 10000L, "one hundred" to 10000L, "a thousand" to 100000L)
        for ((w, c) in words) if (text.lowercase().contains(w)) out += c
        return out
    }
}
