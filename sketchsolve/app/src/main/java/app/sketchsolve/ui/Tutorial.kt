package app.sketchsolve.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import app.sketchsolve.MainActivity
import app.sketchsolve.ink.Pt
import app.sketchsolve.ui.modes.*
import kotlin.math.*

/**
 * The guided tour: one page per feature, each with a drawn illustration,
 * short steps, and a "Try it" button that opens the tab with an example.
 */
class Tutorial(private val app: MainActivity, private val startAt: String? = null, private val onFinish: () -> Unit = {}) {

    data class Page(val id: String, val art: String, val title: String, val steps: List<String>, val tryLabel: String? = null, val tryIt: (() -> Unit)? = null, val extra: ((LinearLayout) -> Unit)? = null)

    private val pages: List<Page> = listOf(
        Page("welcome", "welcome", "Welcome to SketchSolve", listOf(
            "Write or draw maths on the graph paper, then circle what you want to know.",
            "Answers are written out on the yellow notepad, step by step.",
            "If there isn't enough information, it tells you — and says what's missing.",
            "Everything works offline once it's set up.")),
        Page("ask", "ask", "Ask: say it or type it", listOf(
            "Hold the 🎤 token and say your question, then let go.",
            "Sums and equations (“x squared plus three equals twelve”) are solved straight away.",
            "Word problems are read by the on-device AI, checked, and written out: the numbers highlighted, what each letter means, a picture, the equation, the working and a boxed answer.",
            "✓ CHECKED means every number and unit was checked. PLEASE CHECK means look over the setup — tap “Edit the equations” to fix it."),
            "Try a word problem") { val m = mode<AskMode>(); app.select(m); m.ask("Ann can paint a fence in 6 hours and Ben in 4 hours. How long does it take them working together?") },
        Page("eq", "equations", "Equations: write, then circle", listOf(
            "Write one equation per line, leaving a gap between lines.",
            "Draw a loop around a letter to solve for it, around a piece (3 + 4) to work it out, or around a whole line.",
            "Put known values on their own lines, like R = 4. Several lines are solved together.",
            "Exponents: small and raised. Fractions: a line with numbers above and below. Write 2x for 2 × x."),
            "Try it: circle the x") { val m = mode<EquationsMode>(); m.paper.replaceStrokes(SampleInk.equation()); m.paper.tool = "pen"; app.select(m); app.toast("Draw a loop around the x.") },
        Page("fix", "fix", "When it misreads your writing", listOf(
            "Every symbol it read is shown on the notepad. Tap one to fix it.",
            "Choose the right symbol, say two strokes are separate, or type it.",
            "Each fix teaches it your handwriting (Teach & help → Learn from my corrections).",
            "You can also teach it symbols on purpose on the Teach & help tab.")),
        Page("shape", "shapes", "Shapes: draw · bracket · circle", listOf(
            "1  Shape: draw the outline. Shaky lines are fine — it straightens them.",
            "2  Known: draw a bracket beside a side and write its number next to it. For an angle, draw a small arc inside the corner.",
            "3  Find: circle the side or corner you want. Circle the whole shape for its area, perimeter, volume or surface area.",
            "Wrong shape? Tap “Not right? Pick the shape…” on the notepad."),
            "Try it: label this triangle") { val m = mode<ShapesMode>(); m.paper.replaceStrokes(SampleInk.triangle()); m.paper.tool = "mark"; app.select(m); app.toast("Draw a bracket under the bottom side and write 9 next to it.") },
        Page("circuit", "circuits", "Circuits: draw · values · find", listOf(
            "1  Draw the schematic with the parts shown here. Join parts with wires; a dot marks a junction.",
            "2  Values: write each number next to its part — ohms for resistors and lamps, volts for batteries, µF for capacitors.",
            "3  Find: circle a part for its current, voltage and power, or circle everything for the totals.",
            "No battery? Leave two wire ends open to get the resistance between them."),
            "Try it: add the values") { val m = mode<CircuitsMode>(); m.paper.replaceStrokes(SampleInk.seriesCircuit()); m.paper.tool = "mark"; app.select(m); app.toast("Write 12 by the battery, 4 and 2 by the resistors, then circle one.") },
        Page("lib", "formulas", "Formulas and your library", listOf(
            "The Formulas tab has 112 formulas: geometry, trigonometry, algebra, electrical, physics, chemistry, money and conversions.",
            "Tap a card, fill in what you know, and tap ◯ next to what you want.",
            "Add your own: put formula packs (.txt files) in a folder and pick it on the Ask tab. They appear under “My library” and the AI uses them for word problems.",
            "Copy the example pack below into a .txt file to get started."),
            extra = { box ->
                box.addView(TextView(app).apply {
                    text = SampleInk.SAMPLE_PACK.lines().take(9).joinToString("\n") + "\n…"
                    typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); setTextColor(Ink.TEXT)
                    background = GradientDrawable().apply { cornerRadius = app.dp(6f); setColor(0xFFF6F3E8.toInt()); setStroke(app.dp(1), 0x33000000) }
                    setPadding(app.dp(10), app.dp(8), app.dp(10), app.dp(8))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = app.dp(6) })
                box.addView(smallButton("Copy the example pack") {
                    (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SketchSolve pack", SampleInk.SAMPLE_PACK))
                    app.toast("Copied. Paste it into a new .txt file in your library folder.")
                })
            }),
        Page("setup", "setup", "Last step: the offline AI", listOf(
            "Word problems and push-to-talk use AI models that run on this device.",
            "SketchSolve checks your device and recommends the right ones — you can choose others.",
            "It's a one-time download (about 2.8 GB). After that, no internet is needed and nothing you say or write leaves the device.",
            "Equations, Shapes, Circuits and Formulas work right now, without the download.")),
        Page("manual", "manual", "Download the models yourself", listOf(
            "Use this if the download in the app doesn't start (for example “Unable to resolve host”).",
            "1  First try: turn off any VPN or ad blocker, set Settings → Connections → More connection settings → Private DNS to Automatic, or switch to another Wi-Fi or mobile data. Then tap Download again.",
            "2  Or download the files below in a browser — on this phone, or on a computer.",
            "3  On a computer, move them to the phone: USB cable into the Download folder, Google Drive, or Quick Share.",
            "4  On the Ask tab tap “Import…” and pick each file. The app checks it's the right, undamaged file and installs it."),
            extra = { box -> manualLinks(box) })
    )

    private inline fun <reified T : Mode> mode(): T = app.modes.first { it is T } as T

    // the manual-download page is shown on its own, not as part of the tour
    private val shown = if (startAt == "manual") pages.filter { it.id == "manual" } else pages.filter { it.id != "manual" }
    private var index = maxOf(0, shown.indexOfFirst { it.id == startAt })

    private fun manualLinks(box: LinearLayout) {
        val mm = app.models
        val (lang, speech) = mm.fits()
        fun row(m: app.sketchsolve.llm.ModelSpec, note: String) {
            val col = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = app.dp(8f); setColor(0xFFFFFFFF.toInt()); setStroke(app.dp(1), 0x33000000) }
                setPadding(app.dp(10), app.dp(6), app.dp(10), app.dp(8))
            }
            col.addView(TextView(app).apply { text = "${m.title} — ${m.fileName} (${"%.2f".format(m.bytes / 1e9)} GB)" + if (mm.isReady(m)) "  ✓ installed" else ""; setTextColor(Ink.TEXT); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
            if (note.isNotEmpty()) col.addView(TextView(app).apply { text = note; setTextColor(Ink.MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) })
            col.addView(TextView(app).apply { text = m.url; setTextColor(0xFF2B4C7E.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextIsSelectable(true) })
            val btns = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(smallButton("Copy link") {
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(m.fileName, m.url))
                app.toast("Link copied.")
            })
            btns.addView(smallButton("Open in browser") {
                try { app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(m.url))) } catch (e: Exception) { app.toast("No browser found.") }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = app.dp(8) })
            col.addView(btns)
            box.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = app.dp(6) })
        }
        box.addView(TextView(app).apply { text = "WORD PROBLEMS — pick one"; setTextColor(Ink.MUTED); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(0, app.dp(8), 0, 0) })
        for (f in lang) row(f.model, if (f.recommended) "Recommended for this device" else "")
        box.addView(TextView(app).apply { text = "PUSH TO TALK — pick one"; setTextColor(Ink.MUTED); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(0, app.dp(8), 0, 0) })
        for (f in speech) row(f.model, if (f.recommended) "Recommended for this device" else "")
        box.addView(smallButton("⇣  Import a downloaded file…") { dialog.dismiss(); app.pickModelFile() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = app.dp(10) })
    }
    private lateinit var dialog: Dialog
    private lateinit var art: Art
    private lateinit var body: LinearLayout
    private lateinit var dots: TextView
    private lateinit var back: Button
    private lateinit var next: Button

    private fun smallButton(text: String, onClick: () -> Unit) = Button(app).apply {
        this.text = text; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Ink.TEXT)
        background = GradientDrawable().apply { cornerRadius = app.dp(10f); setColor(0xFFFFFDF0.toInt()); setStroke(app.dp(1), 0x66806A30) }
        setPadding(app.dp(14), app.dp(6), app.dp(14), app.dp(6))
        setOnClickListener { onClick() }
    }

    fun show() {
        dialog = Dialog(app).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            background = LegalPadDrawable(app.resources.displayMetrics.density)
            setPadding(app.dp(18), app.dp(26), app.dp(18), app.dp(14))
        }
        art = Art(app)
        root.addView(art, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val right = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; setPadding(app.dp(28), app.dp(4), app.dp(6), 0) }
        body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        right.addView(ScrollView(app).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(smallButton("Skip") { finish() })
        dots = TextView(app).apply { gravity = Gravity.CENTER; setTextColor(Ink.MUTED) }
        nav.addView(dots, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        back = smallButton("Back") { if (index > 0) { index--; render() } }
        next = smallButton("Next") { if (index < shown.size - 1) { index++; render() } else finish() }
        nav.addView(back); nav.addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = app.dp(8) })
        right.addView(nav)
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = app.resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.92).toInt(), (dm.heightPixels * 0.9).toInt())
        dialog.setOnCancelListener { app.getSharedPreferences("sketchsolve", Context.MODE_PRIVATE).edit().putBoolean("tourSeen", true).apply() }
        render()
        dialog.show()
    }

    private fun finish() {
        app.getSharedPreferences("sketchsolve", Context.MODE_PRIVATE).edit().putBoolean("tourSeen", true).apply()
        dialog.dismiss()
        onFinish()
    }

    private fun render() {
        val p = shown[index]
        art.kind = p.art
        body.removeAllViews()
        val pad = Pad(app, body)
        pad.answer(p.title)
        for (s in p.steps) pad.text(s)
        p.extra?.invoke(body)
        if (p.tryIt != null) body.addView(smallButton("▶  ${p.tryLabel}") { app.getSharedPreferences("sketchsolve", Context.MODE_PRIVATE).edit().putBoolean("tourSeen", true).apply(); dialog.dismiss(); p.tryIt.invoke() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = app.dp(10) })
        dots.text = if (shown.size == 1) "" else if (app.resources.configuration.screenHeightDp < 480) "${index + 1} of ${shown.size}" else shown.indices.joinToString(" ") { if (it == index) "●" else "○" }
        back.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        next.text = if (index == shown.size - 1) "Done" else "Next"
    }

    /** Hand-drawn illustration for a tutorial page. */
    class Art(ctx: Context) : View(ctx) {
        var kind = "welcome"
            set(v) { field = v; invalidate() }
        private val d = resources.displayMetrics.density
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 2.6f }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ink.hand; textSize = 15f; color = Ink.PENCIL }

        private fun path(c: Canvas, pts: List<Pt>, color: Int, w: Float = 2.6f, dash: Boolean = false) {
            if (pts.size < 2) return
            ink.color = color; ink.strokeWidth = w
            ink.pathEffect = if (dash) DashPathEffect(floatArrayOf(7f, 5f), 0f) else null
            val p = Path(); p.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (q in pts.drop(1)) p.lineTo(q.x.toFloat(), q.y.toFloat())
            c.drawPath(p, ink); ink.pathEffect = null
        }
        private fun strokes(c: Canvas, list: List<app.sketchsolve.ink.Stroke>, color: Int) { for (s in list) path(c, s.pts, color) }
        private fun tag(c: Canvas, text: String, x: Float, y: Float, bg: Int, fg: Int = Color.WHITE) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ink.serifBold; textSize = 14f; color = fg }
            val w = p.measureText(text) + 16
            fill.color = bg; c.drawRoundRect(x - w / 2, y - 12, x + w / 2, y + 10, 11f, 11f, fill)
            c.drawText(text, x - w / 2 + 8, y + 4, p)
        }
        private fun label(c: Canvas, t: String, x: Float, y: Float, color: Int = Ink.PENCIL, size: Float = 15f) { txt.color = color; txt.textSize = size; c.drawText(t, x, y, txt) }

        override fun onDraw(canvas: Canvas) {
            val wDp = width / d; val hDp = height / d
            canvas.save(); canvas.scale(d, d)
            // a sheet of graph paper
            val sheet = RectF(4f, 4f, wDp - 4, hDp - 4)
            fill.color = 0x30000000; canvas.drawRect(sheet.left + 2, sheet.top + 4, sheet.right + 3, sheet.bottom + 5, fill)
            fill.color = Ink.PAPER; canvas.drawRect(sheet, fill)
            val g = Paint().apply { strokeWidth = 0.6f }
            var x = sheet.left + 18; var i = 1
            while (x < sheet.right) { g.color = if (i % 5 == 0) Ink.GRID_MAJOR else Ink.GRID; canvas.drawLine(x, sheet.top, x, sheet.bottom, g); x += 18; i++ }
            var y = sheet.top + 18; i = 1
            while (y < sheet.bottom) { g.color = if (i % 5 == 0) Ink.GRID_MAJOR else Ink.GRID; canvas.drawLine(sheet.left, y, sheet.right, y, g); y += 18; i++ }
            // fit a 400 × 300 drawing into the sheet
            val s = min((wDp - 20) / 400f, (hDp - 20) / 300f)
            canvas.translate((wDp - 400 * s) / 2, (hDp - 300 * s) / 2)
            canvas.scale(s, s)
            when (kind) {
                "welcome" -> {
                    strokes(canvas, SampleInk.write("2x+3=11", 20.0, 40.0, 40.0), Ink.PENCIL)
                    path(canvas, SampleInk.ellipse(84.0, 60.0, 27.0, 32.0), Ink.SELECT, dash = true)
                    tag(canvas, "x = 4", 84f, 118f, Ink.SELECT)
                    path(canvas, SampleInk.poly(listOf(Pt(200.0, 270.0), Pt(380.0, 270.0), Pt(270.0, 150.0))), Ink.PENCIL)
                    path(canvas, SampleInk.line(Pt(200.0, 286.0), Pt(380.0, 286.0)), Ink.KNOWN)
                    label(canvas, "9", 285f, 300f, Ink.KNOWN, 18f)
                    path(canvas, SampleInk.ellipse(338.0, 205.0, 22.0, 22.0), Ink.FIND)
                    tag(canvas, "a ≈ 6.9", 338f, 150f, Ink.FIND)
                    label(canvas, "write · circle · solved", 20f, 260f, Ink.MUTED, 16f)
                }
                "ask" -> {
                    fill.color = 0xFFE9D3AE.toInt(); canvas.drawCircle(46f, 60f, 30f, fill)
                    label(canvas, "🎤", 32f, 70f, Ink.FIND, 26f)
                    fill.color = Color.WHITE
                    canvas.drawRoundRect(96f, 22f, 392f, 104f, 14f, 14f, fill)
                    ink.color = Ink.GUIDE; ink.strokeWidth = 1.4f; canvas.drawRoundRect(96f, 22f, 392f, 104f, 14f, 14f, ink)
                    label(canvas, "“Ann paints a fence in 6 hours", 108f, 50f)
                    label(canvas, " and Ben in 4. How long", 108f, 72f)
                    label(canvas, " working together?”", 108f, 94f)
                    label(canvas, "LET", 20f, 140f, 0xFF3E5C8A.toInt(), 13f)
                    label(canvas, "t = hours working together", 20f, 160f, Ink.FIND)
                    label(canvas, "t/6 + t/4 = 1", 20f, 196f, Ink.PENCIL, 22f)
                    ink.color = Ink.FIND; ink.strokeWidth = 2.6f; canvas.drawRect(20f, 216f, 150f, 260f, ink)
                    label(canvas, "t = 2.4 h", 32f, 246f, Ink.FIND, 22f)
                    ink.color = Ink.KNOWN; ink.strokeWidth = 2f
                    canvas.save(); canvas.rotate(-6f, 230f, 240f); canvas.drawRoundRect(170f, 224f, 290f, 252f, 6f, 6f, ink)
                    label(canvas, "✓ CHECKED", 180f, 244f, Ink.KNOWN, 15f); canvas.restore()
                }
                "equations" -> {
                    strokes(canvas, SampleInk.write("2x+3=11", 30.0, 70.0, 54.0), Ink.PENCIL)
                    path(canvas, SampleInk.ellipse(117.0, 97.0, 34.0, 42.0), Ink.SELECT, dash = true)
                    tag(canvas, "x = 4", 117f, 168f, Ink.SELECT)
                    strokes(canvas, SampleInk.write("y=x^2", 30.0, 210.0, 40.0), Ink.PENCIL)
                    label(canvas, "← loop around what you want", 190f, 104f, Ink.SELECT, 15f)
                }
                "fix" -> {
                    strokes(canvas, SampleInk.write("2z+1", 30.0, 30.0, 46.0), Ink.PENCIL)
                    val chips = listOf("2", "z", "+", "1")
                    chips.forEachIndexed { k, ch ->
                        val cx = 40f + k * 48f
                        ink.color = if (k == 1) Ink.WARN else Ink.PENCIL; ink.strokeWidth = 1.6f
                        canvas.drawCircle(cx, 130f, 17f, ink); label(canvas, ch, cx - 5, 136f, ink.color, 18f)
                    }
                    fill.color = Color.WHITE; canvas.drawRoundRect(70f, 160f, 290f, 280f, 10f, 10f, fill)
                    ink.color = Ink.GUIDE; ink.strokeWidth = 1.2f; canvas.drawRoundRect(70f, 160f, 290f, 280f, 10f, 10f, ink)
                    label(canvas, "I read “z”. It's actually…", 82f, 184f, Ink.MUTED, 14f)
                    listOf("x", "2", "y", "7").forEachIndexed { k, ch -> ink.color = if (k == 0) Ink.KNOWN else Ink.GUIDE; canvas.drawRect(86f + k * 46, 198f, 122f + k * 46, 232f, ink); label(canvas, ch, 98f + k * 46, 222f, ink.color, 18f) }
                    label(canvas, "These are separate symbols", 82f, 262f, Ink.MUTED, 13f)
                }
                "shapes" -> {
                    path(canvas, SampleInk.poly(listOf(Pt(40.0, 250.0), Pt(300.0, 250.0), Pt(130.0, 50.0))), Ink.PENCIL)
                    path(canvas, SampleInk.line(Pt(40.0, 272.0), Pt(300.0, 272.0)), Ink.KNOWN); label(canvas, "9", 165f, 296f, Ink.KNOWN, 20f)
                    path(canvas, SampleInk.line(Pt(22.0, 238.0), Pt(112.0, 56.0)), Ink.KNOWN); label(canvas, "6", 36f, 150f, Ink.KNOWN, 20f)
                    path(canvas, SampleInk.ellipse(40.0, 250.0, 46.0, 46.0, -60.0, 0.0, 10), Ink.KNOWN); label(canvas, "50°", 92f, 236f, Ink.KNOWN, 16f)
                    path(canvas, SampleInk.ellipse(222.0, 150.0, 30.0, 30.0), Ink.FIND)
                    tag(canvas, "a ≈ 6.9", 300f, 120f, Ink.FIND)
                    label(canvas, "1 draw", 320f, 200f, Ink.PENCIL); label(canvas, "2 bracket + number", 320f, 222f, Ink.KNOWN); label(canvas, "3 circle", 320f, 244f, Ink.FIND)
                }
                "circuits" -> drawSymbols(canvas)
                "formulas" -> {
                    for (k in 0..2) {
                        val cx = 20f + k * 16; val cy = 30f + k * 22
                        fill.color = Color.WHITE; canvas.drawRect(cx, cy, cx + 200, cy + 70, fill)
                        ink.color = 0x55000000; ink.strokeWidth = 1f; canvas.drawRect(cx, cy, cx + 200, cy + 70, ink)
                        ink.color = 0x88E05555.toInt(); canvas.drawLine(cx, cy + 14, cx + 200, cy + 14, ink)
                    }
                    label(canvas, "Ohm's law", 60f, 106f, Ink.PENCIL, 15f); label(canvas, "V = I·R", 60f, 128f, 0xFF2B4C7E.toInt(), 20f)
                    fill.color = 0xFFE8B04B.toInt(); canvas.drawRoundRect(260f, 150f, 380f, 240f, 8f, 8f, fill)
                    canvas.drawRoundRect(260f, 138f, 310f, 160f, 6f, 6f, fill)
                    label(canvas, "my packs", 280f, 205f, Ink.TEXT, 16f)
                    label(canvas, "optics.txt", 60f, 220f, Ink.MUTED, 14f); label(canvas, "electronics.txt", 60f, 242f, Ink.MUTED, 14f)
                }
                "manual" -> {
                    ink.color = Ink.PENCIL; ink.strokeWidth = 3f
                    canvas.drawRoundRect(20f, 60f, 170f, 160f, 8f, 8f, ink)          // laptop screen
                    canvas.drawLine(5f, 172f, 185f, 172f, ink)
                    label(canvas, "browser", 60f, 115f, Ink.MUTED, 15f)
                    path(canvas, SampleInk.line(Pt(195.0, 120.0), Pt(270.0, 120.0)), Ink.KNOWN, 4f)
                    path(canvas, listOf(Pt(255.0, 106.0), Pt(272.0, 120.0), Pt(255.0, 134.0)), Ink.KNOWN, 4f)
                    label(canvas, "USB · Drive · Quick Share", 150f, 200f, Ink.KNOWN, 14f)
                    canvas.drawRoundRect(290f, 40f, 380f, 230f, 14f, 14f, ink)       // phone
                    label(canvas, "Import…", 300f, 140f, Ink.FIND, 16f)
                    label(canvas, "checked ✓", 298f, 166f, Ink.KNOWN, 14f)
                }
                "setup" -> {
                    ink.color = Ink.PENCIL; ink.strokeWidth = 3f
                    canvas.drawRoundRect(60f, 40f, 180f, 270f, 18f, 18f, ink)
                    path(canvas, SampleInk.line(Pt(120.0, 80.0), Pt(120.0, 180.0)), Ink.KNOWN, 4f)
                    path(canvas, listOf(Pt(95.0, 155.0), Pt(120.0, 185.0), Pt(145.0, 155.0)), Ink.KNOWN, 4f)
                    label(canvas, "once", 98f, 230f, Ink.KNOWN, 18f)
                    label(canvas, "Gemma 4 — word problems", 210f, 110f, Ink.PENCIL, 15f)
                    label(canvas, "Whisper — your voice", 210f, 140f, Ink.PENCIL, 15f)
                    label(canvas, "then offline, private", 210f, 200f, Ink.FIND, 17f)
                }
            }
            canvas.restore()
        }

        /** The circuit symbols SketchSolve understands, as a cheat sheet. */
        private fun drawSymbols(c: Canvas) {
            fun row(k: Int) = 30f + k * 52f
            val names = listOf("Wire + junction", "Resistor (zigzag)", "Resistor (box)", "Battery / cell", "Capacitor", "Lamp")
            names.forEachIndexed { k, n -> label(c, n, 200f, row(k) + 6, Ink.PENCIL, 15f) }
            // wire + junction
            path(c, SampleInk.line(Pt(20.0, row(0).toDouble()), Pt(170.0, row(0).toDouble())), Ink.PENCIL)
            path(c, SampleInk.line(Pt(95.0, row(0).toDouble()), Pt(95.0, row(0) + 22.0)), Ink.PENCIL)
            fill.color = Ink.PENCIL; c.drawCircle(95f, row(0), 4f, fill)
            // zigzag
            path(c, SampleInk.zigWire(Pt(20.0, row(1).toDouble()), Pt(170.0, row(1).toDouble()), 9.0, 6), Ink.PENCIL)
            // box resistor
            path(c, SampleInk.line(Pt(20.0, row(2).toDouble()), Pt(65.0, row(2).toDouble())), Ink.PENCIL)
            path(c, SampleInk.poly(listOf(Pt(65.0, row(2) - 10.0), Pt(125.0, row(2) - 10.0), Pt(125.0, row(2) + 10.0), Pt(65.0, row(2) + 10.0))), Ink.PENCIL)
            path(c, SampleInk.line(Pt(125.0, row(2).toDouble()), Pt(170.0, row(2).toDouble())), Ink.PENCIL)
            // battery: long + and short −
            path(c, SampleInk.line(Pt(20.0, row(3).toDouble()), Pt(88.0, row(3).toDouble())), Ink.PENCIL)
            path(c, SampleInk.line(Pt(88.0, row(3) - 20.0), Pt(88.0, row(3) + 20.0), 6), Ink.PENCIL)
            path(c, SampleInk.line(Pt(102.0, row(3) - 10.0), Pt(102.0, row(3) + 10.0), 4), Ink.PENCIL)
            path(c, SampleInk.line(Pt(102.0, row(3).toDouble()), Pt(170.0, row(3).toDouble())), Ink.PENCIL)
            label(c, "+", 78f, row(3) - 22, Ink.FIND, 15f); label(c, "−", 104f, row(3) - 14, Ink.FIND, 15f)
            label(c, "long plate is +", 200f, row(3) + 24, Ink.MUTED, 12f)
            // capacitor: two equal plates
            path(c, SampleInk.line(Pt(20.0, row(4).toDouble()), Pt(88.0, row(4).toDouble())), Ink.PENCIL)
            path(c, SampleInk.line(Pt(88.0, row(4) - 16.0), Pt(88.0, row(4) + 16.0), 5), Ink.PENCIL)
            path(c, SampleInk.line(Pt(102.0, row(4) - 16.0), Pt(102.0, row(4) + 16.0), 5), Ink.PENCIL)
            path(c, SampleInk.line(Pt(102.0, row(4).toDouble()), Pt(170.0, row(4).toDouble())), Ink.PENCIL)
            // lamp: circle with an X
            path(c, SampleInk.line(Pt(20.0, row(5).toDouble()), Pt(77.0, row(5).toDouble())), Ink.PENCIL)
            path(c, SampleInk.ellipse(95.0, row(5).toDouble(), 18.0, 18.0, 0.0, 360.0), Ink.PENCIL)
            path(c, SampleInk.line(Pt(83.0, row(5) - 12.0), Pt(107.0, row(5) + 12.0), 5), Ink.PENCIL)
            path(c, SampleInk.line(Pt(107.0, row(5) - 12.0), Pt(83.0, row(5) + 12.0), 5), Ink.PENCIL)
            path(c, SampleInk.line(Pt(113.0, row(5).toDouble()), Pt(170.0, row(5).toDouble())), Ink.PENCIL)
            label(c, "a plain circle = voltage source", 200f, row(5) + 24, Ink.MUTED, 12f)
        }
    }
}
