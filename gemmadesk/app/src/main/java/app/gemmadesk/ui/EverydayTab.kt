package app.gemmadesk.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.provider.CalendarContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import app.gemmadesk.MainActivity
import app.gemmadesk.assist.Assistant
import app.gemmadesk.data.Entry
import app.gemmadesk.data.Event
import app.gemmadesk.data.ExportProvider
import app.gemmadesk.data.Reminders
import app.gemmadesk.data.money
import app.gemmadesk.data.parseCents
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everyday: a ledger and a calendar, with Gemma to fill them in from plain
 * words. Everything Gemma proposes is shown on a card first; nothing is saved
 * until you tap Save.
 */
class EverydayTab(private val a: MainActivity) : FrameLayout(a) {
    private val scroll = ScrollView(a)
    private val body = a.column(14)
    private val proposalBox = a.column()
    private val input = a.field("Tell Gemma… e.g. “Paid $45 for gas yesterday” or “Dentist next Tuesday at 3pm”").apply {
        imeOptions = EditorInfo.IME_ACTION_SEND; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
    }
    private var section = 0
    private val sectionBox = a.column()
    private val segments = Segments(a, listOf("Ledger", "Calendar"), 0) { section = it; renderSection() }
    private val month = MonthView(a).apply { busyDays = { ym -> a.calendar.events.filter { YearMonth.from(it.date) == ym }.groupingBy { it.date }.eachCount() } }
    private var ledgerFilter: ((Entry) -> Boolean)? = null
    private var ledgerFilterName = ""

    companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
        val DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
        fun time(t: LocalTime?) = t?.let { "%d:%02d %s".format(if (it.hour % 12 == 0) 12 else it.hour % 12, it.minute, if (it.hour < 12) "AM" else "PM") } ?: "All day"
        fun day(d: LocalDate) = (if (d.year == LocalDate.now().year) DAY else DAY_YEAR).format(d)
    }

    init {
        scroll.addView(body)
        addView(scroll)
        val ask = a.card()
        ask.add(a.label("Ask Gemma", 16f, C.INK, true))
        val r = a.row()
        r.add(input, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        r.add(a.button("Send", primary = true) { send() }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 8)
        ask.add(r, top = 8)
        ask.add(a.label("Record spending or income, add appointments, or ask “How much did I spend on groceries this month?”. " +
            "Tip: tap the microphone on your keyboard to say it instead of typing.", 12.5f, C.MUTED), top = 6)
        body.add(ask)
        body.add(proposalBox, top = 10)
        body.add(segments, top = 12)
        body.add(sectionBox, top = 10)
        month.onPick = { renderSection() }
    }

    fun refresh() { renderSection() }

    /* ------------------------- Gemma ------------------------- */

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        a.withModel("Gemma is reading that…", { m -> Assistant(m).ask(text, a.ledger, a.calendar) }) { p, err ->
            if (p == null) { a.toast("Gemma couldn't answer: ${err?.message}"); return@withModel }
            input.setText("")
            showProposal(p)
        }
    }

    fun showProposal(p: Assistant.Proposal) {
        proposalBox.removeAllViews()
        val c = a.card().apply { background = rounded(0xFFF4F6FF.toInt(), a.dpf(14f), a.dp(1), 0xFFC7CFF5.toInt()) }
        val dismiss = { proposalBox.removeAllViews() }
        when (p) {
            is Assistant.Proposal.AddEntries -> {
                c.add(a.label(if (p.entries.size == 1) "Add to the ledger?" else "Add ${p.entries.size} entries to the ledger?", 16f, C.INK, true))
                for (e in p.entries) c.add(entryRow(e, null), top = 6)
                notes(c, p.notes)
                c.add(actions(listOf(
                    "Save" to { p.entries.forEach { a.ledger.add(it) }; dismiss(); a.toast("Saved to the ledger."); section = 0; segments.select(0); renderSection() },
                    "Edit" to { if (p.entries.size == 1) editEntry(p.entries[0], isNew = true) { dismiss() } else { p.entries.forEach { a.ledger.add(it) }; dismiss(); renderSection(); a.toast("Saved. Tap an entry to change it.") } },
                    "Dismiss" to dismiss)), top = 10)
            }
            is Assistant.Proposal.AddEvent -> {
                val e = p.event
                c.add(a.label("Add to the calendar?", 16f, C.INK, true))
                c.add(a.label(e.title, 18f, C.INK, true), top = 6)
                c.add(a.label("${day(e.date)} · ${time(e.start)}${if (e.start != null) " (${e.minutes} min)" else ""}${if (e.place.isNotBlank()) " · ${e.place}" else ""}", 15f, C.INK), top = 2)
                c.add(a.label(if (e.remind == null) "No reminder" else "Reminder ${remindText(e.remind)} before", 13f, C.MUTED), top = 2)
                if (p.clashes.isNotEmpty()) c.add(a.label("⚠ Overlaps ${p.clashes.joinToString { "${it.title} at ${time(it.start)}" }}", 13.5f, C.WARN), top = 6)
                notes(c, p.notes)
                c.add(actions(listOf(
                    "Save" to { saveEvent(e); dismiss() },
                    "Edit" to { editEvent(e, isNew = true) { dismiss() } },
                    "Dismiss" to dismiss)), top = 10)
            }
            is Assistant.Proposal.Total -> {
                val what = listOfNotNull(p.category, p.search).firstOrNull()
                val verb = if (p.cents < 0) "spent" else if (p.cents > 0) "received" else "spent"
                c.add(a.label("You $verb ${money(kotlin.math.abs(p.cents))}${what?.let { " on $it" } ?: ""}", 20f, C.INK, true))
                c.add(a.label("${day(p.from)} – ${day(p.to)} · ${p.count} ${if (p.count == 1) "entry" else "entries"} · added up by the app from your ledger", 13f, C.MUTED), top = 2)
                c.add(actions(listOf(
                    "Show them" to {
                        ledgerFilter = { e -> !e.date.isBefore(p.from) && !e.date.isAfter(p.to) && (p.category == null || e.category.equals(p.category, true)) && (p.search == null || e.description.contains(p.search, true) || e.category.contains(p.search, true)) }
                        ledgerFilterName = "${what ?: "Everything"}, ${day(p.from)} – ${day(p.to)}"
                        section = 0; segments.select(0); renderSection()
                    },
                    "Dismiss" to dismiss)), top = 10)
            }
            is Assistant.Proposal.Events -> {
                c.add(a.label(if (p.events.isEmpty()) "Nothing on the calendar ${day(p.from)} – ${day(p.to)}." else "On the calendar, ${day(p.from)} – ${day(p.to)}:", 16f, C.INK, true))
                for (e in p.events) c.add(eventRow(e), top = 6)
                c.add(actions(listOf("Dismiss" to dismiss)), top = 10)
            }
            is Assistant.Proposal.Reply -> {
                c.add(a.label(p.text, 15.5f, C.INK))
                notes(c, p.notes)
                c.add(actions(listOf("Dismiss" to dismiss)), top = 10)
            }
        }
        proposalBox.addView(c)
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    private fun notes(c: LinearLayout, notes: List<String>) = notes.forEach { c.add(a.label("⚠ $it", 13.5f, C.WARN), top = 6) }
    private fun actions(list: List<Pair<String, () -> Unit>>) = a.row().apply {
        list.forEachIndexed { i, (n, f) -> add(a.button(n, primary = i == 0, small = true) { f() }, LinearLayout.LayoutParams.WRAP_CONTENT, left = if (i == 0) 0 else 8) }
    }

    /* ------------------------- sections ------------------------- */

    private fun renderSection() {
        sectionBox.removeAllViews()
        if (section == 0) renderLedger() else renderCalendar()
    }

    private fun renderLedger() {
        val led = a.ledger
        val today = LocalDate.now()
        val (monthCents, _) = led.total(today.withDayOfMonth(1), today)
        val monthIn = led.entries.filter { YearMonth.from(it.date) == YearMonth.from(today) && it.cents > 0 }.sumOf { it.cents }
        val monthOut = led.entries.filter { YearMonth.from(it.date) == YearMonth.from(today) && it.cents < 0 }.sumOf { it.cents }
        val sum = a.card()
        val top = a.row()
        top.add(a.column().apply {
            add(a.label("Balance", 13f, C.MUTED))
            add(a.label(money(led.balance()), 26f, if (led.balance() < 0) C.BAD else C.INK, true))
        }, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        top.add(a.column().apply {
            gravity = Gravity.END
            add(a.label("This month", 13f, C.MUTED).apply { gravity = Gravity.END })
            add(a.label("in ${money(monthIn)}  ·  out ${money(-monthOut)}", 14f, C.INK).apply { gravity = Gravity.END })
            add(a.label("net ${money(monthCents, sign = true)}", 14f, if (monthCents < 0) C.BAD else C.GOOD, true).apply { gravity = Gravity.END })
        }, LinearLayout.LayoutParams.WRAP_CONTENT)
        sum.add(top)
        // spending by category this month
        val cats = led.entries.filter { YearMonth.from(it.date) == YearMonth.from(today) && it.cents < 0 }.groupBy { it.category.ifBlank { "Other" } }
            .mapValues { (_, l) -> -l.sumOf { it.cents } }.entries.sortedByDescending { it.value }.take(5)
        if (cats.isNotEmpty()) {
            val max = cats.first().value.toFloat()
            sum.add(a.label("Spending by category this month", 13f, C.MUTED), top = 10)
            for ((k, v) in cats) sum.add(Bar(a, k, money(v), v / max), top = 4)
        }
        val acts = a.row()
        acts.add(a.button("+ Entry", primary = true, small = true) { editEntry(Entry(date = today, description = "", cents = 0), isNew = true) {} }, LinearLayout.LayoutParams.WRAP_CONTENT)
        acts.add(a.button("Export CSV", small = true) { exportCsv() }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 8)
        sum.add(acts, top = 12)
        sectionBox.add(sum)

        var list = led.entries.toList()
        ledgerFilter?.let { f ->
            list = list.filter(f)
            val fr = a.row().apply { setPadding(0, a.dp(10), 0, 0) }
            fr.add(a.label("Showing: $ledgerFilterName (${money(list.sumOf { it.cents }, sign = true)})", 13.5f, C.ACCENT, true), 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            fr.add(a.button("Show all", small = true) { ledgerFilter = null; renderSection() }, LinearLayout.LayoutParams.WRAP_CONTENT)
            sectionBox.add(fr)
        }
        if (list.isEmpty()) { sectionBox.add(a.label(if (ledgerFilter != null) "No entries match." else "No entries yet. Tell Gemma what you spent, or tap + Entry.", 14f, C.MUTED), top = 12); return }
        var lastMonth: YearMonth? = null
        var card: LinearLayout? = null
        for (e in list.take(300)) {
            val ym = YearMonth.from(e.date)
            if (ym != lastMonth) {
                lastMonth = ym
                sectionBox.add(a.label("${ym.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())} ${ym.year}", 13f, C.MUTED, true), top = 14)
                card = a.card().apply { setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4)) }
                sectionBox.add(card, top = 4)
            }
            card!!.add(entryRow(e) { editEntry(e, isNew = false) {} })
        }
        if (list.size > 300) sectionBox.add(a.label("Showing the latest 300. Export CSV for everything.", 12f, C.MUTED), top = 8)
    }

    private fun entryRow(e: Entry, onClick: (() -> Unit)?): View {
        val r = a.row().apply { setPadding(0, a.dp(8), 0, a.dp(8)) }
        val left = a.column()
        left.add(a.label(e.description.ifBlank { "(no description)" }, 15f, C.INK, true))
        left.add(a.label(listOf(day(e.date), e.category, e.account.takeIf { it != "Main" }).filterNot { it.isNullOrBlank() }.joinToString(" · "), 12.5f, C.MUTED))
        r.add(left, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        r.add(a.label(money(e.cents, sign = true), 16f, if (e.cents < 0) C.INK else C.GOOD, true), LinearLayout.LayoutParams.WRAP_CONTENT, left = 8)
        onClick?.let { f -> r.setOnClickListener { f() } }
        return r
    }

    private fun renderCalendar() {
        val c = a.card()
        val head = a.row()
        val title = a.label(month.title(), 17f, C.INK, true)
        head.add(a.button("‹", small = true) { month.month = month.month.minusMonths(1); title.text = month.title() }, LinearLayout.LayoutParams.WRAP_CONTENT)
        head.add(title.apply { gravity = Gravity.CENTER }, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        head.add(a.button("Today", small = true) { month.month = YearMonth.now(); month.selected = LocalDate.now(); renderSection() }, LinearLayout.LayoutParams.WRAP_CONTENT, right = 6)
        head.add(a.button("›", small = true) { month.month = month.month.plusMonths(1); title.text = month.title() }, LinearLayout.LayoutParams.WRAP_CONTENT)
        c.add(head)
        (month.parent as? LinearLayout)?.removeView(month)
        c.add(month, top = 8)
        sectionBox.add(c)

        val dayCard = a.card()
        val dh = a.row()
        dh.add(a.label(day(month.selected), 16f, C.INK, true), 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        dh.add(a.button("+ Appointment", primary = true, small = true) { editEvent(Event(title = "", date = month.selected, start = LocalTime.of(9, 0)), isNew = true) {} }, LinearLayout.LayoutParams.WRAP_CONTENT)
        dayCard.add(dh)
        val evs = a.calendar.on(month.selected)
        if (evs.isEmpty()) dayCard.add(a.label("Nothing planned.", 14f, C.MUTED), top = 8)
        for (e in evs) dayCard.add(eventRow(e).apply { setOnClickListener { editEvent(e, isNew = false) {} } }, top = 4)
        sectionBox.add(dayCard, top = 10)

        val up = a.calendar.upcoming(java.time.LocalDateTime.now(), 6).filter { it.date != month.selected }
        if (up.isNotEmpty()) {
            sectionBox.add(a.label("Coming up", 13f, C.MUTED, true), top = 14)
            val uc = a.card().apply { setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4)) }
            for (e in up) uc.add(eventRow(e).apply { setOnClickListener { month.month = YearMonth.from(e.date); month.selected = e.date; renderSection() } })
            sectionBox.add(uc, top = 4)
        }
    }

    private fun eventRow(e: Event): LinearLayout {
        val r = a.row().apply { setPadding(0, a.dp(8), 0, a.dp(8)) }
        r.add(View(a).apply { background = rounded(C.ACCENT, a.dpf(2f)) }, a.dp(4), a.dp(36), right = 10)
        val col = a.column()
        col.add(a.label(e.title, 15f, C.INK, true))
        col.add(a.label("${day(e.date)} · ${time(e.start)}${if (e.place.isNotBlank()) " · ${e.place}" else ""}", 12.5f, C.MUTED))
        r.add(col, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        return r
    }

    /* ------------------------- editing ------------------------- */

    private fun pickDate(d: LocalDate, then: (LocalDate) -> Unit) =
        DatePickerDialog(a, { _, y, m, dd -> then(LocalDate.of(y, m + 1, dd)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()

    private fun editEntry(e: Entry, isNew: Boolean, after: () -> Unit) {
        val f = a.column(18)
        var date = e.date
        val dateBtn = a.button(day(date), small = true) {}
        dateBtn.setOnClickListener { pickDate(date) { date = it; dateBtn.text = day(it) } }
        val desc = a.field("What was it?", e.description)
        val amount = a.field("Amount, e.g. 12.50", if (e.cents == 0L) "" else java.math.BigDecimal(kotlin.math.abs(e.cents)).movePointLeft(2).toPlainString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val dir = Segments(a, listOf("Money out", "Money in"), if (e.cents > 0) 1 else 0) {}
        val cat = AutoCompleteTextView(a).apply {
            hint = "Category (e.g. Groceries)"; setText(e.category); threshold = 1
            setAdapter(ArrayAdapter(a, android.R.layout.simple_dropdown_item_1line, (a.ledger.categories() + listOf("Groceries", "Fuel", "Dining", "Rent", "Utilities", "Salary", "Shopping", "Health", "Transport")).distinct()))
            background = rounded(C.CARD, a.dpf(10f), a.dp(1), C.LINE); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        }
        val acct = a.field("Account", e.account)
        f.add(a.label("Date", 13f, C.MUTED)); f.add(dateBtn, LinearLayout.LayoutParams.WRAP_CONTENT, top = 2)
        f.add(desc, top = 10); f.add(amount, top = 8); f.add(dir, top = 8); f.add(cat, top = 8); f.add(acct, top = 8)
        val b = AlertDialog.Builder(a).setTitle(if (isNew) "New entry" else "Edit entry").setView(ScrollView(a).apply { addView(f) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
        if (!isNew) b.setNeutralButton("Delete") { _, _ -> a.confirm("Delete this entry?", e.description, "Delete") { a.ledger.remove(e.id); renderSection() } }
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = parseCents(amount.text.toString())
                if (c == null || c <= 0) { amount.error = "Enter an amount"; return@setOnClickListener }
                val n = e.copy(date = date, description = desc.text.toString().trim(), cents = if (dir.selected == 0) -c else c,
                    category = cat.text.toString().trim(), account = acct.text.toString().trim().ifBlank { "Main" })
                if (isNew) a.ledger.add(n) else a.ledger.update(n)
                dlg.dismiss(); after(); renderSection()
            }
        }
        dlg.setOnDismissListener { a.immersive() }
        dlg.show()
    }

    private fun remindText(m: Int) = when { m == 0 -> "at the time"; m % 1440 == 0 -> "${m / 1440} day${if (m / 1440 > 1) "s" else ""}"; m % 60 == 0 -> "${m / 60} hour${if (m / 60 > 1) "s" else ""}"; else -> "$m min" }

    private fun saveEvent(e: Event, isNew: Boolean = true) {
        if (isNew) a.calendar.add(e) else a.calendar.update(e)
        Reminders.schedule(a, e)
        if (e.remind != null && android.os.Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5)
        month.month = YearMonth.from(e.date); month.selected = e.date
        section = 1; segments.select(1); renderSection()
        a.toast("Saved: ${e.title}, ${day(e.date)} ${time(e.start)}")
    }

    private fun editEvent(e: Event, isNew: Boolean, after: () -> Unit) {
        val f = a.column(18)
        var date = e.date; var start = e.start
        val title = a.field("What is it?", e.title)
        val dateBtn = a.button(day(date), small = true) {}
        dateBtn.setOnClickListener { pickDate(date) { date = it; dateBtn.text = day(it) } }
        val allDay = CheckBox(a).apply { text = "All day"; isChecked = start == null }
        val timeBtn = a.button(time(start ?: LocalTime.of(9, 0)), small = true) {}
        timeBtn.setOnClickListener { val t = start ?: LocalTime.of(9, 0); TimePickerDialog(a, { _, h, m -> start = LocalTime.of(h, m); timeBtn.text = time(start); allDay.isChecked = false }, t.hour, t.minute, false).show() }
        allDay.setOnCheckedChangeListener { _, v -> timeBtn.isEnabled = !v; if (!v && start == null) start = LocalTime.of(9, 0) }
        timeBtn.isEnabled = start != null
        val mins = a.field("Minutes", e.minutes.toString(), InputType.TYPE_CLASS_NUMBER)
        val place = a.field("Where (optional)", e.place)
        val notes = a.field("Notes (optional)", e.notes)
        val remindOpts = listOf(null, 0, 10, 30, 60, 120, 1440)
        val remind = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, remindOpts.map { if (it == null) "No reminder" else "Remind ${remindText(it)}${if (it == 0) "" else " before"}" })
            setSelection(remindOpts.indexOf(e.remind).coerceAtLeast(0))
        }
        f.add(title)
        val dr = a.row(); dr.add(dateBtn, LinearLayout.LayoutParams.WRAP_CONTENT); dr.add(timeBtn, LinearLayout.LayoutParams.WRAP_CONTENT, left = 8); dr.add(allDay, LinearLayout.LayoutParams.WRAP_CONTENT, left = 8)
        f.add(dr, top = 10)
        f.add(a.label("How long (minutes)", 13f, C.MUTED), top = 10); f.add(mins, top = 2)
        f.add(place, top = 8); f.add(notes, top = 8); f.add(remind, top = 8)
        val b = AlertDialog.Builder(a).setTitle(if (isNew) "New appointment" else "Edit appointment").setView(ScrollView(a).apply { addView(f) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
        if (!isNew) b.setNeutralButton("More…", null)
        val dlg = b.create()
        fun current() = e.copy(title = title.text.toString().trim(), date = date, start = if (allDay.isChecked) null else start,
            minutes = mins.text.toString().toIntOrNull()?.coerceIn(5, 1440) ?: 60, place = place.text.toString().trim(), notes = notes.text.toString().trim(),
            remind = remindOpts[remind.selectedItemPosition])
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (title.text.isBlank()) { title.error = "Give it a name"; return@setOnClickListener }
                saveEvent(current(), isNew); dlg.dismiss(); after()
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                AlertDialog.Builder(a).setItems(arrayOf("Add to my phone's calendar", "Delete")) { _, which ->
                    if (which == 0) addToPhoneCalendar(current())
                    else a.confirm("Delete this appointment?", e.title, "Delete") { a.calendar.remove(e.id); Reminders.cancel(a, e.id); dlg.dismiss(); renderSection() }
                }.show()
            }
        }
        dlg.setOnDismissListener { a.immersive() }
        dlg.show()
    }

    /** Hand the appointment to Samsung/Google Calendar (the user confirms it there). */
    private fun addToPhoneCalendar(e: Event) {
        val zone = ZoneId.systemDefault()
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, e.title).putExtra(CalendarContract.Events.EVENT_LOCATION, e.place).putExtra(CalendarContract.Events.DESCRIPTION, e.notes)
        if (e.start == null) i.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.date.atStartOfDay(zone).toInstant().toEpochMilli())
        else i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startsAt().atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.startsAt().plusMinutes(e.minutes.toLong()).atZone(zone).toInstant().toEpochMilli())
        runCatching { a.startActivity(i) }.onFailure { a.toast("No calendar app found.") }
    }

    private fun exportCsv() {
        val f = File(ExportProvider.dir(a), "ledger-${LocalDate.now()}.csv")
        f.writeText(a.ledger.toCsv())
        a.share(f, "text/csv", "Ledger ${LocalDate.now()}")
    }

    /** A labelled bar for the category chart. */
    private class Bar(ctx: android.content.Context, val name: String, val value: String, val frac: Float) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val d = resources.displayMetrics.density
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (22 * d).toInt())
        override fun onDraw(c: android.graphics.Canvas) {
            val labelW = 110 * d; val valW = 80 * d
            p.textSize = 12.5f * d; p.color = C.INK; p.textAlign = android.graphics.Paint.Align.LEFT
            c.drawText(name.take(16), 0f, 15 * d, p)
            val bw = (width - labelW - valW).coerceAtLeast(10f)
            p.color = 0xFFE9ECF3.toInt(); c.drawRoundRect(labelW, 5 * d, labelW + bw, 17 * d, 6 * d, 6 * d, p)
            p.color = C.ACCENT; c.drawRoundRect(labelW, 5 * d, labelW + bw * frac.coerceIn(0.03f, 1f), 17 * d, 6 * d, 6 * d, p)
            p.color = C.INK; p.textAlign = android.graphics.Paint.Align.RIGHT; c.drawText(value, width.toFloat(), 15 * d, p)
        }
    }
}
