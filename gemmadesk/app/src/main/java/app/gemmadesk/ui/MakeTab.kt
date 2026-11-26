package app.gemmadesk.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.gemmadesk.MainActivity
import app.gemmadesk.assist.Designer
import app.gemmadesk.data.ExportProvider
import app.gemmadesk.data.Frame
import app.gemmadesk.data.Html
import app.gemmadesk.data.Item
import app.gemmadesk.data.Page
import app.gemmadesk.data.Project
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Make: websites and app screens. Gemma lays out a page from a description
 * (the "target design"); you then move and resize every item on the design
 * screen, edit words and colours, preview the real HTML, and export it.
 */
class MakeTab(private val a: MainActivity) : FrameLayout(a) {
    private var project: Project? = null
    private var pageIndex = 0
    val canvas = DesignCanvas(a)
    private val undo = ArrayList<Page>()
    private val redo = ArrayList<Page>()
    private var committed: Page? = null
    private val props = a.column(12)
    private lateinit var prompt: EditText
    private lateinit var askBtn: TextView
    private lateinit var pageBtn: TextView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    /** On phones the properties panel shows only while an item is selected. */
    private var propsScroll: ScrollView? = null

    private val wide get() = resources.configuration.screenWidthDp >= 700

    init {
        canvas.onChange = { p, commit -> if (commit) commit(p) else updatePage(p, save = false) }
        canvas.onSelect = { renderProps() }
        canvas.onEditText = { editText(it) }
        canvas.onLongPress = { itemMenu(it) }
    }

    fun refresh() { if (project == null) showProjects() else showEditor() }

    private fun page(): Page = project!!.pages[pageIndex]

    /* =========================== projects =========================== */

    private fun showProjects() {
        removeAllViews()
        val body = a.column(16)
        val head = a.row()
        head.add(a.label("Websites & apps", 20f, C.INK, true), 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        head.add(a.button("+ New", primary = true, small = true) { newProject() }, LinearLayout.LayoutParams.WRAP_CONTENT)
        body.add(head)
        body.add(a.label("Describe a page and Gemma lays it out. Then drag items to move them, drag the corner dots to resize, and export real HTML.", 13.5f, C.MUTED), top = 4)
        val list = a.projects.all()
        if (list.isEmpty()) body.add(a.card().apply {
            add(a.label("No projects yet", 16f, C.INK, true))
            add(a.label("Tap + New to start a website or an app screen.", 14f, C.MUTED), top = 4)
        }, top = 14)
        for (p in list) {
            val c = a.card()
            c.add(a.label(p.name, 17f, C.INK, true))
            c.add(a.label("${p.pages.size} page${if (p.pages.size == 1) "" else "s"} · ${p.pages.firstOrNull()?.frame?.name ?: ""} · ${p.pages.sumOf { it.items.size }} items", 13f, C.MUTED), top = 2)
            c.setOnClickListener { open(p) }
            c.setOnLongClickListener {
                AlertDialog.Builder(a).setTitle(p.name).setItems(arrayOf("Rename", "Delete")) { _, w ->
                    if (w == 0) askText("Rename project", p.name) { n -> a.projects.save(p.copy(name = n)); showProjects() }
                    else a.confirm("Delete “${p.name}”?", "This can't be undone.", "Delete") { a.projects.delete(p); showProjects() }
                }.show(); true
            }
            body.add(c, top = 10)
        }
        addView(ScrollView(a).apply { addView(body) })
    }

    private fun newProject() {
        val f = a.column(18)
        val name = a.field("Name, e.g. Rosa's Bakery")
        val kind = Segments(a, listOf("Phone app", "Website", "Tablet"), 0) {}
        val desc = a.field("Optional: describe the first page for Gemma", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply { minLines = 3; gravity = Gravity.TOP }
        f.add(name); f.add(a.label("Screen", 13f, C.MUTED), top = 10); f.add(kind, top = 2); f.add(desc, top = 10)
        f.add(a.label("e.g. “A home page for a bakery: logo, a big photo, three cards for bread, cakes and coffee, opening hours, and an Order button.”", 12.5f, C.MUTED), top = 4)
        val dlg = AlertDialog.Builder(a).setTitle("New project").setView(ScrollView(a).apply { addView(f) })
            .setPositiveButton("Create", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim().ifEmpty { "Untitled" }
                val frame = listOf(Frame.PHONE, Frame.DESKTOP, Frame.TABLET)[kind.selected]
                val p = Project(name = n, pages = listOf(Page(name = "Home", frame = frame)))
                a.projects.save(p)
                dlg.dismiss()
                open(p)
                val d = desc.text.toString().trim()
                if (d.isNotEmpty()) { prompt.setText(d); runGemma(fresh = true) }
            }
        }
        dlg.setOnDismissListener { a.immersive() }
        dlg.show()
    }

    fun open(p: Project) {
        project = p; pageIndex = 0
        resetHistory()
        showEditor()
    }

    private fun resetHistory() { undo.clear(); redo.clear(); committed = project?.pages?.getOrNull(pageIndex); committed?.let { canvas.setPage(it, keepSelection = false) } }

    /* =========================== editor =========================== */

    private fun showEditor() {
        removeAllViews()
        val root = a.column()
        // toolbar
        val bar = a.row().apply { setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6)); setBackgroundColor(C.CARD) }
        bar.add(a.button("‹ Projects", small = true) { saveNow(); project = null; showProjects() }, LinearLayout.LayoutParams.WRAP_CONTENT)
        pageBtn = a.button("${page().name} ▾", small = true) { pageMenu() }
        bar.add(pageBtn, LinearLayout.LayoutParams.WRAP_CONTENT, left = 6)
        bar.add(View(a), 0, 1, 1f)
        undoBtn = a.button("Undo", small = true) { doUndo() }; redoBtn = a.button("Redo", small = true) { doRedo() }
        bar.add(undoBtn, LinearLayout.LayoutParams.WRAP_CONTENT); bar.add(redoBtn, LinearLayout.LayoutParams.WRAP_CONTENT, left = 4)
        bar.add(a.button("Preview", small = true) { preview() }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 6)
        bar.add(a.button("Export", primary = true, small = true) { export() }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 6)
        root.add(HorizontalScrollView(a).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(bar); setBackgroundColor(C.CARD) })

        // Gemma
        val g = a.row().apply { setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(6)); setBackgroundColor(C.CARD) }
        prompt = a.field("Describe the page, or what to change…").apply { maxLines = 3 }
        g.add(prompt, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        askBtn = a.button("✦ Design", primary = true, small = true) { runGemma(fresh = page().items.isEmpty()) }
        g.add(askBtn, LinearLayout.LayoutParams.WRAP_CONTENT, left = 6)
        root.add(g)
        root.add(View(a).apply { setBackgroundColor(C.LINE) }, h = 1)

        val palette = palette()
        propsScroll = null
        if (wide) {
            val mid = a.row(Gravity.TOP)
            mid.add(ScrollView(a).apply { addView(palette) }, a.dp(92), ViewGroup.LayoutParams.MATCH_PARENT)
            mid.add(canvas.detach(), 0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            mid.add(ScrollView(a).apply { addView(props.detach()); setBackgroundColor(C.CARD) }, a.dp(290), ViewGroup.LayoutParams.MATCH_PARENT)
            root.add(mid, h = 0, weight = 1f)
        } else {
            root.add(canvas.detach(), h = 0, weight = 1f)
            root.add(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(palette); setBackgroundColor(C.CARD) })
            propsScroll = ScrollView(a).apply { addView(props.detach()); setBackgroundColor(C.CARD) }
            root.add(propsScroll!!, h = a.dp(230))
        }
        addView(root)
        canvas.setPage(page())
        renderProps()
        updateButtons()
    }

    private fun View.detach(): View { (parent as? ViewGroup)?.removeView(this); return this }

    private fun palette(): LinearLayout {
        val p = if (wide) a.column(8) else a.row().apply { setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6)) }
        if (wide) p.add(a.label("Add", 12f, C.MUTED, true))
        for (t in Item.TYPES) {
            val b = a.button(Item.LABELS[t] ?: t, small = true) { addItem(t) }
            if (wide) p.add(b, top = 6) else p.add(b, ViewGroup.LayoutParams.WRAP_CONTENT, right = 6)
        }
        return p
    }

    private fun addItem(type: String) {
        val (cx, cy) = canvas.visibleCentre().let { it[0] to it[1] }
        val W = page().frame.w
        var item = Item.create(type, 16, cy, W)
        item = item.copy(x = ((cx - item.w / 2).coerceIn(0, maxOf(0, W - item.w)) + 2) / 4 * 4, y = (cy + 2) / 4 * 4)
        commit(page().copy(items = page().items + item))
        canvas.select(item.id)
        canvas.reveal(item)
    }

    /* ----- changes, undo, saving ----- */

    private fun updatePage(p: Page, save: Boolean) {
        val pr = project ?: return
        project = pr.copy(pages = pr.pages.toMutableList().also { it[pageIndex] = p })
        if (canvas.page !== p) canvas.setPage(p)
        if (save) scheduleSave()
    }

    /** A finished change: remember the previous version for Undo. */
    fun commit(p: Page) {
        committed?.let { if (it != p) { undo += it; if (undo.size > 100) undo.removeAt(0); redo.clear() } }
        committed = p
        updatePage(p, save = true)
        renderProps(); updateButtons()
    }

    private fun doUndo() {
        val prev = undo.removeLastOrNull() ?: return
        committed?.let { redo += it }
        committed = prev; updatePage(prev, true); renderProps(); updateButtons()
    }
    private fun doRedo() {
        val next = redo.removeLastOrNull() ?: return
        committed?.let { undo += it }
        committed = next; updatePage(next, true); renderProps(); updateButtons()
    }
    private fun updateButtons() {
        if (!::undoBtn.isInitialized) return
        undoBtn.alpha = if (undo.isEmpty()) 0.4f else 1f; redoBtn.alpha = if (redo.isEmpty()) 0.4f else 1f
        askBtn.text = if (page().items.isEmpty()) "✦ Design" else "✦ Change"
        pageBtn.text = "${page().name} ▾"
    }

    private val saver = Runnable { saveNow() }
    private fun scheduleSave() { a.main.removeCallbacks(saver); a.main.postDelayed(saver, 800) }
    fun saveNow() { a.main.removeCallbacks(saver); project?.let { a.projects.save(it) } }

    /* ----- Gemma ----- */

    private fun runGemma(fresh: Boolean) {
        val text = prompt.text.toString().trim()
        if (text.isEmpty()) { prompt.error = if (fresh) "Describe the page first" else "Say what to change"; return }
        val before = page()
        a.withModel(if (fresh) "Gemma is laying out the page… (this can take a minute or two)" else "Gemma is changing the page…", { m ->
            val d = Designer(m)
            if (fresh) d.design(text, before.copy(items = emptyList())) else d.edit(text, before)
        }) { result, err ->
            if (result == null) { a.toast(err?.message?.let { "Gemma couldn't do that: $it" } ?: "Gemma didn't return a layout. Try describing it differently."); return@withModel }
            if (project == null || page().id != before.id) return@withModel
            commit(result.copy(name = before.name, frame = before.frame))
            canvas.select(null); canvas.resetView()
            prompt.setText("")
            a.toast("Gemma placed ${result.items.size} items. Drag to adjust, or tap Undo to go back.")
        }
    }

    /* ----- properties ----- */

    private fun selected(): Item? = canvas.selected()
    private fun change(f: (Item) -> Item) {
        val s = selected() ?: return
        commit(page().copy(items = page().items.map { if (it.id == s.id) f(it) else it }))
    }

    private val swatches = listOf("#FFFFFF", "#1F2430", "#6B7489", "#F2F4F8", "#3D5AFE", "#1E2A4A", "#00897B", "#2E7D32", "#F9A825", "#EF6C00", "#C62828", "#AD1457", "#6A1B9A", "#FFF3E0", "#E8F5E9", "#E3F2FD")

    private fun renderProps() {
        props.removeAllViews()
        val s = selected()
        propsScroll?.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s == null) { pageProps(props); return }
        val head = a.row()
        head.add(a.label(Item.LABELS[s.type] ?: s.type, 16f, C.INK, true), 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        head.add(a.button("⋯", small = true) { itemMenu(s) }, LinearLayout.LayoutParams.WRAP_CONTENT)
        props.add(head)
        if (s.type != "divider" && s.type != "box") {
            props.add(a.label(if (s.type == "input") "Placeholder" else if (s.type == "image") "Describe the picture" else "Words", 12f, C.MUTED), top = 8)
            val t = a.field("", s.text, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply { maxLines = 5 }
            t.setOnFocusChangeListener { _, has -> if (!has && t.text.toString() != selected()?.text) change { it.copy(text = t.text.toString()) } }
            t.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(x: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(x: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                // live update without filling the undo history; committed when focus leaves
                override fun afterTextChanged(e: Editable?) { val cur = selected() ?: return; if (cur.text != e.toString()) updatePage(page().copy(items = page().items.map { if (it.id == cur.id) it.copy(text = e.toString()) else it }), save = true) }
            })
            props.add(t, top = 2)
            val r = a.row()
            r.add(a.label("Size", 12f, C.MUTED), LinearLayout.LayoutParams.WRAP_CONTENT)
            r.add(a.button("−", small = true) { change { it.copy(fontSize = (it.fontSize - 2).coerceAtLeast(8)) } }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 6)
            r.add(a.label("${s.fontSize}", 14f, C.INK, true).apply { gravity = Gravity.CENTER }, a.dp(34))
            r.add(a.button("+", small = true) { change { it.copy(fontSize = (it.fontSize + 2).coerceAtMost(120)) } }, LinearLayout.LayoutParams.WRAP_CONTENT)
            r.add(a.button(if (s.bold) "B ✓" else "B", small = true) { change { it.copy(bold = !it.bold) } }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 10)
            props.add(r, top = 8)
            if (s.type in setOf("heading", "text", "link")) props.add(Segments(a, listOf("Left", "Centre", "Right"), listOf("left", "center", "right").indexOf(s.align).coerceAtLeast(0)) { i -> change { it.copy(align = listOf("left", "center", "right")[i]) } }, top = 8)
            props.add(a.label("Text colour", 12f, C.MUTED), top = 10)
            props.add(swatchRow(s.color, allowNone = false) { c -> change { it.copy(color = c) } }, top = 2)
        }
        props.add(a.label(if (s.type == "divider") "Line colour" else "Background", 12f, C.MUTED), top = 10)
        props.add(swatchRow(s.background, allowNone = s.type != "divider") { c -> change { it.copy(background = c) } }, top = 2)
        if (s.type !in setOf("divider", "link", "heading", "text")) {
            val r = a.row()
            r.add(a.label("Corners", 12f, C.MUTED), LinearLayout.LayoutParams.WRAP_CONTENT)
            r.add(a.button("−", small = true) { change { it.copy(radius = (it.radius - 4).coerceAtLeast(0)) } }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 6)
            r.add(a.label("${s.radius}", 14f, C.INK, true).apply { gravity = Gravity.CENTER }, a.dp(34))
            r.add(a.button("+", small = true) { change { it.copy(radius = (it.radius + 4).coerceAtMost(200)) } }, LinearLayout.LayoutParams.WRAP_CONTENT)
            props.add(r, top = 8)
        }
        if (s.type in setOf("button", "link")) {
            props.add(a.label("Goes to (a web address, or another page like about.html)", 12f, C.MUTED), top = 10)
            val l = a.field("https://… or page name", s.link, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            l.setOnFocusChangeListener { _, has -> if (!has) change { it.copy(link = linkFor(l.text.toString().trim())) } }
            props.add(l, top = 2)
        }
        // exact numbers
        props.add(a.label("Position and size (px)", 12f, C.MUTED), top = 10)
        val nums = a.row()
        for ((k, v) in listOf("X" to s.x, "Y" to s.y, "W" to s.w, "H" to s.h)) {
            val e = a.field(k, v.toString(), InputType.TYPE_CLASS_NUMBER).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6)) }
            e.setOnFocusChangeListener { _, has ->
                if (has) return@setOnFocusChangeListener
                val n = e.text.toString().toIntOrNull() ?: return@setOnFocusChangeListener
                change { when (k) { "X" -> it.copy(x = n.coerceIn(0, page().frame.w - it.w)); "Y" -> it.copy(y = n.coerceAtLeast(0)); "W" -> it.copy(w = n.coerceIn(8, page().frame.w - it.x)); else -> it.copy(h = n.coerceAtLeast(1)) } }
            }
            nums.add(a.column().apply { add(a.label(k, 11f, C.MUTED)); add(e) }, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, right = 4)
        }
        props.add(nums, top = 2)
        props.add(a.label("Tip: double-tap an item to change its words; long-press for more.", 12f, C.MUTED), top = 10)
    }

    /** "about" → "about.html" if that's a page in this project. */
    private fun linkFor(t: String): String {
        if (t.isEmpty()) return ""
        val pg = project?.pages?.firstOrNull { it.name.equals(t, true) || Html.pageFile(it).equals(t, true) }
        return pg?.let { Html.pageFile(it) } ?: t
    }

    private fun swatchRow(current: String, allowNone: Boolean, pick: (String) -> Unit): View {
        val r = a.row()
        val all = (if (allowNone) listOf("") else emptyList()) + swatches
        for (c in all) {
            val v = View(a).apply {
                val col = if (c.isEmpty()) Color.WHITE else Color.parseColor(c)
                background = rounded(col, a.dpf(14f), a.dp(if (c.equals(current, true)) 3 else 1), if (c.equals(current, true)) C.ACCENT else 0xFFC9D0DC.toInt())
                contentDescription = if (c.isEmpty()) "None" else c
                setOnClickListener { pick(c) }
            }
            r.add(v, a.dp(28), a.dp(28), right = 6)
            if (c.isEmpty()) r.add(a.label("none", 10f, C.MUTED).apply { setPadding(0, 0, a.dp(6), 0) }, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val more = a.button("#", small = true) { askText("Colour (hex, e.g. #FF8800)", current) { t -> val h = if (t.startsWith("#")) t else "#$t"; if (Html.safeColor(h, "") != "") pick(h.uppercase()) else a.toast("That isn't a colour like #FF8800.") } }
        r.add(more, LinearLayout.LayoutParams.WRAP_CONTENT)
        return HorizontalScrollView(a).apply { addView(r); isHorizontalScrollBarEnabled = false }
    }

    private fun pageProps(props: LinearLayout) {
        val p = page()
        props.add(a.label("Page: ${p.name}", 16f, C.INK, true))
        props.add(a.label("${p.frame.name} · ${p.frame.w} px wide · ${p.items.size} items", 13f, C.MUTED), top = 2)
        props.add(a.label("Screen size", 12f, C.MUTED), top = 10)
        props.add(Segments(a, Frame.ALL.map { it.name }, Frame.ALL.indexOfFirst { it.w == p.frame.w }.coerceAtLeast(0)) { i -> resizeFrame(Frame.ALL[i]) }, top = 2)
        props.add(a.label("Page background", 12f, C.MUTED), top = 10)
        props.add(swatchRow(p.background, allowNone = false) { c -> commit(page().copy(background = c)) }, top = 2)
        props.add(a.label(if (p.items.isEmpty()) "Describe the page above and tap ✦ Design, or add items with the buttons." else
            "Tap an item to select it. Drag to move; drag the dots to resize. Pinch to zoom, drag empty space to scroll.", 13f, C.MUTED), top = 12)
    }

    private fun pageSettings() {
        val col = a.column(16)
        pageProps(col)
        AlertDialog.Builder(a).setView(ScrollView(a).apply { addView(col) }).setPositiveButton("Done", null).setOnDismissListener { a.immersive(); renderProps() }.show()
    }

    /** Change the screen size, scaling the layout so it still fits. */
    private fun resizeFrame(f: Frame) {
        val p = page()
        if (f.w == p.frame.w) return
        val k = f.w.toDouble() / p.frame.w
        commit(p.copy(frame = f, items = p.items.map { i ->
            val nx = (i.x * k).toInt(); val nw = (i.w * k).toInt().coerceAtLeast(8)
            i.copy(x = nx, w = minOf(nw, f.w - nx), y = (i.y * k).toInt(), h = if (i.type in setOf("text", "heading", "button", "input", "link", "divider")) i.h else (i.h * k).toInt())
        }))
        canvas.resetView()
    }

    private fun itemMenu(i: Item) {
        AlertDialog.Builder(a).setTitle(Item.LABELS[i.type]).setItems(arrayOf("Edit words", "Duplicate", "Bring to front", "Send to back", "Centre on the page", "Full width", "Delete")) { _, w ->
            val items = page().items
            when (w) {
                0 -> editText(i)
                1 -> { val c = i.copy(id = app.gemmadesk.data.newId(), y = i.y + i.h + 12); commit(page().copy(items = items + c)); canvas.select(c.id) }
                2 -> commit(page().copy(items = items.filter { it.id != i.id } + i))
                3 -> commit(page().copy(items = listOf(i) + items.filter { it.id != i.id }))
                4 -> change { it.copy(x = (page().frame.w - it.w) / 2) }
                5 -> change { it.copy(x = 16, w = page().frame.w - 32) }
                6 -> { commit(page().copy(items = items.filter { it.id != i.id })); canvas.select(null) }
            }
        }.setOnDismissListener { a.immersive() }.show()
    }

    private fun editText(i: Item) {
        if (i.type in setOf("divider", "box")) return
        askText(if (i.type == "input") "Placeholder" else "Words", i.text, multi = true) { t -> change { it.copy(text = t) } }
    }

    private fun askText(title: String, value: String, multi: Boolean = false, then: (String) -> Unit) {
        val f = a.field("", value, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        val box = a.column(18).apply { add(f) }
        AlertDialog.Builder(a).setTitle(title).setView(box).setPositiveButton("OK") { _, _ -> then(f.text.toString()) }.setNegativeButton("Cancel", null)
            .setOnDismissListener { a.immersive() }.show()
        f.requestFocus()
    }

    /* ----- pages ----- */

    private fun pageMenu() {
        val pr = project ?: return
        val names = pr.pages.mapIndexed { i, p -> (if (i == pageIndex) "✓ " else "    ") + p.name } + listOf("+ Add a page", "Rename this page", "Duplicate this page", "Delete this page", "Page settings (screen size, background)")
        AlertDialog.Builder(a).setTitle(pr.name).setItems(names.toTypedArray()) { _, w ->
            val n = pr.pages.size
            when {
                w < n -> { saveNow(); pageIndex = w; resetHistory(); showEditor() }
                w == n -> askText("New page name", "About") { t -> val np = Page(name = t.ifBlank { "Page" }, frame = page().frame, background = page().background)
                    project = pr.copy(pages = pr.pages + np); pageIndex = project!!.pages.size - 1; saveNow(); resetHistory(); showEditor() }
                w == n + 1 -> askText("Rename page", page().name) { t -> commit(page().copy(name = t.ifBlank { page().name })) }
                w == n + 2 -> { val cp = page().copy(id = app.gemmadesk.data.newId(), name = page().name + " copy", items = page().items.map { it.copy(id = app.gemmadesk.data.newId()) })
                    project = pr.copy(pages = pr.pages + cp); pageIndex = project!!.pages.size - 1; saveNow(); resetHistory(); showEditor() }
                w == n + 4 -> pageSettings()
                else -> if (n == 1) a.toast("A project needs at least one page.") else a.confirm("Delete “${page().name}”?", "This can't be undone.", "Delete") {
                    project = pr.copy(pages = pr.pages.filterIndexed { i, _ -> i != pageIndex }); pageIndex = 0; saveNow(); resetHistory(); showEditor() }
            }
        }.setOnDismissListener { a.immersive() }.show()
    }

    /* ----- preview and export ----- */

    private fun preview() {
        val html = Html.page(page(), project)
        val dlg = Dialog(a, android.R.style.Theme_Material_Light_NoActionBar)
        val col = a.column()
        val bar = a.row().apply { setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8)); setBackgroundColor(C.NAVY) }
        bar.add(a.label("Preview: ${page().name}.html", 15f, Color.WHITE, true), 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val web = WebView(a)
        var showingCode = false
        val code = TextView(a).apply { text = html; typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setTextColor(C.INK); setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12)); setTextIsSelectable(true) }
        val codeScroll = ScrollView(a).apply { addView(code); visibility = View.GONE; setBackgroundColor(0xFFF6F7FA.toInt()) }
        bar.add(a.button("HTML", small = true) { showingCode = !showingCode; codeScroll.visibility = if (showingCode) View.VISIBLE else View.GONE; web.visibility = if (showingCode) View.GONE else View.VISIBLE }, LinearLayout.LayoutParams.WRAP_CONTENT)
        bar.add(a.button("Close", small = true) { dlg.dismiss() }, LinearLayout.LayoutParams.WRAP_CONTENT, left = 6)
        col.add(bar)
        val frame = FrameLayout(a)
        frame.addView(web); frame.addView(codeScroll)
        col.add(frame, h = 0, weight = 1f)
        col.add(a.label("This is the real HTML, drawn by the phone's web browser engine.", 12f, C.MUTED).apply { setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6)) })
        web.settings.javaScriptEnabled = true  // only for the tiny "scale to fit" script in our own page
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        dlg.setContentView(col)
        dlg.setOnDismissListener { a.immersive() }
        dlg.show()
    }

    private fun export() {
        saveNow()
        val pr = project ?: return
        AlertDialog.Builder(a).setTitle("Export").setItems(arrayOf("Share this page (.html)", "Share the whole ${if (pr.pages.size > 1) "site" else "project"} (.zip)", "Copy this page's HTML")) { _, w ->
            when (w) {
                0 -> { val f = File(ExportProvider.dir(a), Html.pageFile(page())); f.writeText(Html.page(page(), pr)); a.share(f, "text/html", "${pr.name}: ${page().name}") }
                1 -> { val f = zip(pr); a.share(f, "application/zip", pr.name) }
                else -> { (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("HTML", Html.page(page(), pr))); a.toast("HTML copied.") }
            }
        }.setOnDismissListener { a.immersive() }.show()
    }

    /** Every page as an .html file; the first page is also index.html. */
    fun zip(pr: Project): File {
        val name = pr.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "site" }
        val f = File(ExportProvider.dir(a), "$name.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            val used = HashSet<String>()
            fun put(n: String, b: ByteArray) { if (used.add(n)) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
            pr.pages.forEachIndexed { i, p ->
                val html = Html.page(p, pr).toByteArray()
                if (i == 0) put("index.html", html)
                var fn = Html.pageFile(p); var k = 2
                while (fn in used && fn != "index.html") fn = Html.pageFile(p).removeSuffix(".html") + "-${k++}.html"
                put(fn, html)
            }
        }
        return f
    }
}
