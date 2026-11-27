package app.foxbox.notes

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import app.foxbox.ui.Px
import app.foxbox.ui.Snes
import app.foxbox.ui.SnesDialog
import app.foxbox.ui.Tv
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The notebook: a pocket memo pad on the desk. Write on a line with your finger or a stylus and
 * a moment later it's set in a handwritten font on that line. Tap (without writing) to put the
 * cursor somewhere; what you write on that line next goes in at the cursor, and "Type" opens
 * the keyboard there. The eraser takes out whole words. Draw a box and it becomes a sketch window
 * (4:3 wide, square, or 3:4 tall) without lines; tap it to draw.
 *
 * Turn pages by swiping up or down from the spiral or the bottom edge, or with two fingers
 * anywhere. Top left: memo books (and export); top middle: bookmarks; top right: search.
 */
@SuppressLint("ViewConstructor")
class NotebookView(private val a: Activity, private val close: () -> Unit, private val openSketch: (Notes.Sketch) -> Unit) : View(a) {
    private val art = PageArt(a)
    private val book get() = Notes.book!!
    private val page get() = book.page
    private val pageNo get() = book.current

    // ---- geometry
    private val pad = RectF(); private val header = RectF()
    private var g = PageGeo(RectF())
    private val bookBtn = RectF(); private val markBtn = RectF(); private val findBtn = RectF()
    private val tools = Array(5) { RectF() }   // pen/eraser, type, page, undo, close

    // ---- paints
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = PageArt.INK }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Px.body; color = 0xFFF2E6CC.toInt() }
    private val toolP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = art.hand; color = 0xFF5A6684.toInt(); textAlign = Paint.Align.CENTER }
    private val path = Path()

    // ---- writing
    private var eraser = false
    private val pending = ArrayList<FloatArray>()      // strokes waiting to be read: x, y, t triples (page coordinates)
    private var cur: FloatArrayList? = null
    private val t0 = SystemClock.uptimeMillis()
    private var reading = false
    private var caretLine = -1; private var caretIdx = 0; private var caretPage = -1
    private var highlight: String? = null
    private val undo = ArrayList<Triple<String, Int, Notes.Page>>()
    private val recognize = Runnable { readPending() }

    // ---- page turning
    private var flipFrom = -1; private var flipTo = -1; private var flipStart = 0L
    private val FLIP_MS = 300L

    private class FloatArrayList { var a = FloatArray(96); var n = 0
        fun add(x: Float, y: Float, t: Float) { if (n + 3 > a.size) a = a.copyOf(a.size * 2); a[n++] = x; a[n++] = y; a[n++] = t }
        fun done() = a.copyOf(n) }

    init {
        isFocusable = true; isFocusableInTouchMode = true
        Handwriting.onReady = { post { if (pending.isNotEmpty()) readPending(); invalidate() } }
        Handwriting.prepare()
    }

    override fun onDetachedFromWindow() { Handwriting.onReady = null; removeCallbacks(recognize); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // close up on a classic pocket memo pad (taller than wide, about 3.5 by 5.5), leaving the
        // top strip clear for the notebook and radio keys
        val m = maxOf(Px.dp(6f), minOf(w, h) * 0.025f)
        val top = Px.dp(54f)
        var pw = w - m * 2; var ph = pw / Notes.ASPECT
        if (ph > h - top - m) { ph = h - top - m; pw = ph * Notes.ASPECT }
        val y0 = top + (h - top - m - ph) / 2
        pad.set((w - pw) / 2, y0, (w + pw) / 2, y0 + ph)
        header.set(pad.left, pad.top, pad.right, pad.top + minOf(ph * 0.06f, pw * 0.13f))
        g = PageGeo(RectF(pad.left, header.bottom, pad.right, pad.bottom))
        val hh = header.height()
        bookBtn.set(pad.left, pad.top, pad.left + pw * 0.4f, header.bottom)
        markBtn.set(pad.centerX() - hh * 0.9f, pad.top, pad.centerX() + hh * 0.9f, header.bottom)
        findBtn.set(pad.right - pw * 0.2f, pad.top, pad.right, header.bottom)
        val slot = pw / tools.size
        for (i in tools.indices) tools[i].set(pad.left + i * slot, g.toolTop, pad.left + (i + 1) * slot, pad.bottom)
        art.sizeText(g)
        rewrapOld()
    }

    /** Books written with smaller type (or more lines a page) are re-wrapped once to the current size. */
    private fun rewrapOld() {
        for (b in Notes.books) {
            if (b.layout >= Notes.LAYOUT) continue
            var pi = 0
            while (pi < b.pages.size) { for (li in 0 until Notes.LINES) reflow(pi, li, b); pi++ }
            b.layout = Notes.LAYOUT
            Notes.save(b)
        }
    }

    // =====================================================================================
    // Drawing
    // =====================================================================================

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Tv.woodPaint)
        // the pad's shadow on the desk, and the back cover peeking out
        p.color = 0x55000000; c.drawRoundRect(pad.left + Px.dp(4f), pad.top + Px.dp(6f), pad.right + Px.dp(4f), pad.bottom + Px.dp(6f), Px.dp(6f), Px.dp(6f), p)
        p.color = darker(book.cover); c.drawRoundRect(pad.left - Px.dp(3f), pad.top, pad.right + Px.dp(3f), pad.bottom + Px.dp(3f), Px.dp(6f), Px.dp(6f), p)
        // the sheets under this one, and this one (turning, if a turn is under way)
        p.color = 0xFFE8E0CC.toInt(); c.drawRect(g.paper.left, g.paper.top, g.paper.right, g.paper.bottom + Px.dp(2f), p)
        val now = SystemClock.uptimeMillis()
        if (flipFrom >= 0) {
            val t = ((now - flipStart) / FLIP_MS.toFloat()).coerceIn(0f, 1f)
            val e = t * t * (3 - 2 * t)
            if (flipTo > flipFrom) {
                // forward: the next sheet is underneath; this one lifts up over the spiral
                drawSheet(c, flipTo)
                c.save(); c.scale(1f, 1f - e, 0f, g.paper.top); drawSheet(c, flipFrom); shade(c, e); c.restore()
            } else {
                // back: the previous sheet comes down over this one
                drawSheet(c, flipFrom)
                c.save(); c.scale(1f, e, 0f, g.paper.top); drawSheet(c, flipTo); shade(c, 1 - e); c.restore()
            }
            if (t >= 1f) { book.current = flipTo; flipFrom = -1; Notes.save() }
            postInvalidateOnAnimation()
        } else drawSheet(c, pageNo, live = true)
        drawHeader(c)
        drawRail(c)
        drawTools(c)
        if (caretPage == pageNo && caretLine >= 0) postInvalidateDelayed(500)
    }

    private fun shade(c: Canvas, k: Float) { p.color = ((k * 0.35f * 255).toInt() shl 24); c.drawRect(g.paper, p) }

    private fun drawSheet(c: Canvas, idx: Int, live: Boolean = false) {
        val pg = book.pages.getOrNull(idx) ?: return
        val now = android.os.SystemClock.uptimeMillis()
        art.draw(c, g, pg, idx + 1, if (live) highlight else null, playMs = if (live) now else -1L, loaded = { postInvalidate() })
        // animated sketches on this page keep playing
        if (live) pg.sketches.firstNotNullOfOrNull { s -> Notes.animation(s.id) { postInvalidate() } }?.let { an -> postInvalidateDelayed((1000f / an.fps).toLong().coerceIn(16L, 500L)) }
        if (book.bookmarks.contains(pg.id)) {
            // a ribbon hanging over the top of a bookmarked page
            val x = g.textR - g.pw * 0.06f; val w = g.pw * 0.045f
            path.reset(); path.moveTo(x, g.paper.top); path.lineTo(x + w, g.paper.top); path.lineTo(x + w, g.paper.top + g.lineH * 2.2f)
            path.lineTo(x + w / 2, g.paper.top + g.lineH * 1.7f); path.lineTo(x, g.paper.top + g.lineH * 2.2f); path.close()
            p.color = 0xFFC0392B.toInt(); c.drawPath(path, p)
        }
        if (!live) return
        // ink not yet read
        ink.strokeWidth = maxOf(Px.dp(2f), g.lineH * 0.07f)
        for (s in pending) strokePath(c, s, s.size)
        cur?.let { strokePath(c, it.a, it.n) }
        // the cursor
        if (caretPage == idx && caretLine >= 0 && (SystemClock.uptimeMillis() / 500) % 2 == 0L) {
            val s = pg.lines[caretLine]
            val x = g.textL + art.width(s, caretIdx)
            p.color = PageArt.INK; c.drawRect(x - Px.dp(1f), g.lineTop(caretLine) + g.lineH * 0.15f, x + Px.dp(1f), g.lineTop(caretLine + 1) - g.lineH * 0.05f, p)
        }
    }

    private fun strokePath(c: Canvas, a: FloatArray, n: Int) {
        if (n < 3) return
        path.reset(); path.moveTo(a[0] + g.paper.left, a[1] + g.paper.top)
        var i = 3
        while (i + 1 < n) { path.lineTo(a[i] + g.paper.left, a[i + 1] + g.paper.top); i += 3 }
        if (n == 3) path.lineTo(a[0] + g.paper.left + 0.1f, a[1] + g.paper.top)
        c.drawPath(path, ink)
    }

    private fun drawHeader(c: Canvas) {
        // the cover band: leather with a stitched edge
        p.color = book.cover; c.drawRect(header, p)
        p.color = 0x18FFFFFF; c.drawRect(header.left, header.top, header.right, header.top + header.height() * 0.3f, p)
        p.color = 0x55FFFFFF; p.strokeWidth = Px.dp(1f)
        var x = header.left + Px.dp(6f)
        while (x < header.right - Px.dp(6f)) { c.drawLine(x, header.top + Px.dp(4f), x + Px.dp(4f), header.top + Px.dp(4f), p); x += Px.dp(8f) }
        // book name (top left)
        val hh = header.height()
        label.textSize = hh * 0.42f; label.textAlign = Paint.Align.LEFT; label.color = 0xFFF2E6CC.toInt()
        val name = book.name + "  ▾"
        var nm = name
        while (label.measureText(nm) > bookBtn.width() - Px.dp(14f) && nm.length > 4) nm = nm.dropLast(4) + "…▾"
        c.drawText(nm, bookBtn.left + Px.dp(10f), header.centerY() + hh * 0.08f, label)
        // bookmark ribbon (top middle)
        val marked = book.bookmarks.contains(page.id)
        val rx = markBtn.centerX(); val rw = hh * 0.32f
        path.reset(); path.moveTo(rx - rw, header.top + hh * 0.15f); path.lineTo(rx + rw, header.top + hh * 0.15f); path.lineTo(rx + rw, header.bottom - hh * 0.05f)
        path.lineTo(rx, header.bottom - hh * 0.3f); path.lineTo(rx - rw, header.bottom - hh * 0.05f); path.close()
        p.style = if (marked) Paint.Style.FILL else Paint.Style.STROKE; p.strokeWidth = Px.dp(2f); p.color = if (marked) 0xFFE0453A.toInt() else 0xCCF2E6CC.toInt()
        c.drawPath(path, p); p.style = Paint.Style.FILL
        // search (top right): a magnifying glass
        val fx = findBtn.centerX() + findBtn.width() * 0.1f; val fy = header.centerY() - hh * 0.05f; val fr = hh * 0.22f
        p.style = Paint.Style.STROKE; p.strokeWidth = Px.dp(2.5f); p.color = 0xFFF2E6CC.toInt()
        c.drawCircle(fx, fy, fr, p); c.drawLine(fx + fr * 0.7f, fy + fr * 0.7f, fx + fr * 1.6f, fy + fr * 1.6f, p); p.style = Paint.Style.FILL
        // a glued pad: the sheets' edges caught in a strip of glue under the cover band
        p.color = 0xFFD8CDB4.toInt(); c.drawRect(pad.left, header.bottom, pad.right, header.bottom + hh * 0.16f, p)
        p.color = 0x33000000
        for (k in 1..3) c.drawRect(pad.left, header.bottom + hh * 0.04f * k, pad.right, header.bottom + hh * 0.04f * k + Px.dp(0.7f), p)
        p.color = 0x22000000; c.drawRect(pad.left, header.bottom, pad.right, header.bottom + Px.dp(2f), p)
    }

    /** The page rail in the left margin: a groove with grip ridges and arrows, to swipe pages up or down. */
    private fun drawRail(c: Canvas) {
        val l = g.paper.left + g.pw * 0.02f; val r = g.marginX - g.pw * 0.02f
        val t = g.firstRule + g.lineH * 0.2f; val b = g.toolTop - g.lineH * 0.2f
        val held = mode == Mode.SWIPE && downX < g.marginX
        p.color = if (held) 0x30E39A9A else 0x1423306E; c.drawRoundRect(l, t, r, b, (r - l) / 2, (r - l) / 2, p)
        p.color = 0x2A23306E
        var y = t + (r - l)
        while (y < b - (r - l)) { c.drawRect(l + (r - l) * 0.3f, y, r - (r - l) * 0.3f, y + Px.dp(1f), p); y += Px.dp(7f) }
        // ▲ next page, ▼ the one before
        val cx = (l + r) / 2; val a = (r - l) * 0.28f
        p.color = 0x6623306E
        path.reset(); path.moveTo(cx, t + a * 0.6f); path.lineTo(cx + a, t + a * 1.9f); path.lineTo(cx - a, t + a * 1.9f); path.close(); c.drawPath(path, p)
        path.reset(); path.moveTo(cx, b - a * 0.6f); path.lineTo(cx + a, b - a * 1.9f); path.lineTo(cx - a, b - a * 1.9f); path.close(); c.drawPath(path, p)
    }

    private fun drawTools(c: Canvas) {
        val th = tools[0].height()
        toolP.textSize = th * 0.42f
        val names = arrayOf(if (eraser) "Eraser" else "Pen", "Type", "${pageNo + 1} / ${book.pages.size}", "Undo", "Close")
        for (i in tools.indices) {
            val r = tools[i]
            if (i == 0) { p.color = if (eraser) 0x33E39A9A else 0x2223306E; c.drawRoundRect(r.left + th * 0.15f, r.top + th * 0.12f, r.right - th * 0.15f, r.bottom - th * 0.12f, th * 0.3f, th * 0.3f, p) }
            toolP.color = if (i == 2) 0xFF8090A8.toInt() else 0xFF5A6684.toInt()
            c.drawText(names[i], r.centerX(), r.centerY() + toolP.textSize * 0.35f, toolP)
        }
        val status = when (Handwriting.state) {
            Handwriting.State.DOWNLOADING -> "getting handwriting ready (one time)…"
            Handwriting.State.FAILED -> "handwriting needs a one-time download: connect, then reopen"
            else -> if (reading) "reading…" else null
        }
        if (status != null) {
            // just above the tool row (not over its labels), shrunk to fit the page
            toolP.textSize = g.lineH * 0.42f; toolP.color = 0xFF9A7A50.toInt()
            val room = pad.width() * 0.9f
            if (toolP.measureText(status) > room) toolP.textSize *= room / toolP.measureText(status)
            c.drawText(status, pad.centerX(), g.toolTop - g.lineH * 0.14f, toolP)
        }
    }

    private fun darker(c: Int): Int { val r = (c shr 16 and 255) * 2 / 3; val gg = (c shr 8 and 255) * 2 / 3; val b = (c and 255) * 2 / 3; return (0xFF shl 24) or (r shl 16) or (gg shl 8) or b }

    // =====================================================================================
    // Touch
    // =====================================================================================

    private enum class Mode { NONE, INK, ERASE, SWIPE, EDGE, BUTTON, SKETCH, TWO }
    private var mode = Mode.NONE
    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var twoY = 0f
    private var button: RectF? = null
    private var touchSketch: Notes.Sketch? = null
    private var longFired = false
    private val longPress = Runnable { touchSketch?.let { longFired = true; sketchMenu(it) } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (flipFrom >= 0) return true
        val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; downT = SystemClock.uptimeMillis(); longFired = false
                highlight = null
                mode = when {
                    header.contains(x, y) -> { button = listOf(bookBtn, markBtn, findBtn).firstOrNull { it.contains(x, y) }; Mode.BUTTON }
                    y >= g.toolTop -> Mode.EDGE
                    y < g.firstRule && y >= header.bottom - header.height() * 0.4f -> Mode.SWIPE
                    // the page rail down the left margin: swipe up for the next page, down for the last
                    x < g.marginX && y > g.paper.top && y < g.toolTop -> Mode.SWIPE
                    !pad.contains(x, y) -> Mode.SWIPE
                    else -> {
                        val sk = sketchAt(x, y)
                        if (sk != null) { touchSketch = sk; postDelayed(longPress, 500); Mode.SKETCH }
                        else if (eraser) { pushUndo(); eraseAt(x, y); Mode.ERASE }
                        else { removeCallbacks(recognize); cur = FloatArrayList().apply { add(x - g.paper.left, y - g.paper.top, (downT - t0).toFloat()) }; Mode.INK }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                cur = null; removeCallbacks(longPress)
                mode = Mode.TWO; twoY = (e.getY(0) + e.getY(1)) / 2
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.INK -> {
                    val s = cur ?: return true
                    for (h in 0 until e.historySize) s.add(e.getHistoricalX(0, h) - g.paper.left, e.getHistoricalY(0, h) - g.paper.top, (e.getHistoricalEventTime(h) - t0).toFloat())
                    s.add(e.x - g.paper.left, e.y - g.paper.top, (e.eventTime - t0).toFloat())
                    invalidate()
                }
                Mode.ERASE -> { eraseAt(e.x, e.y); invalidate() }
                Mode.SKETCH -> if (hypot(e.x - downX, e.y - downY) > Px.dp(10f)) removeCallbacks(longPress)
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.TWO && e.pointerCount == 2) {
                val ay = (e.getY(0) + e.getY(1)) / 2
                if (abs(ay - twoY) > pad.height() * 0.08f) turn(if (ay < twoY) 1 else -1)
                mode = Mode.NONE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val dy = e.y - downY
                val tap = hypot(e.x - downX, dy) < Px.dp(12f)
                when (mode) {
                    Mode.BUTTON -> if (tap) when (button) { bookBtn -> booksMenu(); markBtn -> bookmarksMenu(); findBtn -> search(); else -> {} }
                    Mode.EDGE -> if (abs(dy) > pad.height() * 0.06f) turn(if (dy < 0) 1 else -1) else if (tap) tool(tools.indexOfFirst { it.contains(downX, downY) })
                    Mode.SWIPE -> if (abs(dy) > pad.height() * 0.04f) turn(if (dy < 0) 1 else -1)
                    Mode.SKETCH -> if (tap && !longFired) touchSketch?.let { openSketch(it) }
                    Mode.INK -> { if (e.actionMasked == MotionEvent.ACTION_UP) finishStroke() else cur = null }
                    Mode.ERASE -> Notes.save()
                    else -> {}
                }
                mode = Mode.NONE; touchSketch = null
                invalidate()
            }
        }
        return true
    }

    private fun tool(i: Int) {
        when (i) {
            0 -> { eraser = !eraser }
            1 -> typeHere()
            3 -> undoLast()
            4 -> close()
        }
        invalidate()
    }

    private fun turn(d: Int) {
        flushPending()
        val to = pageNo + d
        if (to < 0) return
        if (to >= book.pages.size) {
            if (page.empty) { toast("This is the last page: write something to start a new one"); return }
            book.pages += Notes.Page()
        }
        caretLine = -1
        flipFrom = pageNo; flipTo = to; flipStart = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Extra items for the memo-book menu (5IY Pocket adds pictures and drawing boards there). */
    var extraMenu: ((SnesDialog) -> Unit)? = null

    /**
     * Makes a picture window on the page: on this page's first free stretch of empty lines, or
     * on a new page right after it. Returns the new window (already saved).
     */
    fun placeWindow(square: Boolean, tall: Boolean = false): Notes.Sketch {
        flushPending()
        val want = if (tall) 7 else if (square) 6 else 5
        fun fits(p: Notes.Page, l0: Int, n: Int) = (l0 until l0 + n).all { p.lines[it].isBlank() } && p.sketches.none { l0 < it.line + it.lines && it.line < l0 + n }
        var p = page; var line0 = -1; var n = want
        search@ for (nn in want downTo 4) for (l in 0..Notes.LINES - nn) if (fits(p, l, nn)) { line0 = l; n = nn; break@search }
        pushUndo()
        if (line0 < 0) { val np = Notes.Page(); book.pages.add(book.current + 1, np); goTo(book, book.current + 1); p = np; line0 = 0; n = want }
        val s0 = Notes.Sketch("", 0, 0f, 0f, 0, square && !tall, tall)
        val wf = if (g.textW > 0f) minOf(1f, (n * g.lineH - g.lineH * 0.24f) / s0.ratio / g.textW) else 0.8f
        val s = Notes.Sketch(Notes.newId(), line0, 0f, wf, n, square && !tall, tall)
        p.sketches += s; Notes.save(); invalidate()
        return s
    }

    private fun goTo(b: Notes.Book, idx: Int) {
        flushPending()
        if (b !== book) Notes.select(b)
        b.current = idx.coerceIn(0, b.pages.size - 1)
        caretLine = -1; Notes.save(); invalidate()
    }

    private fun sketchAt(x: Float, y: Float): Notes.Sketch? {
        val r = RectF()
        return page.sketches.firstOrNull { g.sketchRect(it, r).contains(x, y) }
    }

    // =====================================================================================
    // Handwriting
    // =====================================================================================

    private fun finishStroke() {
        val s = cur?.done() ?: return
        cur = null
        var len = 0f
        var i = 3
        while (i + 1 < s.size) { len += hypot(s[i] - s[i - 3], s[i + 1] - s[i - 2]); i += 3 }
        val dur = s[s.size - 1] - s[2]
        if (pending.isEmpty() && len < Px.dp(10f) && dur < 350) { placeCaret(s[0] + g.paper.left, s[1] + g.paper.top); return }
        if (crossOut(s)) return
        if (isCheck(s)) { writeCheck(s); return }
        box(s)?.let { makeSketch(it); return }
        pending += s
        removeCallbacks(recognize); postDelayed(recognize, 750)
    }

    // ---- crossing out: a line through words (or a scribble over them) takes them out

    /**
     * True if the stroke crossed out typed words (they're removed) or scribbled over handwriting
     * that hasn't been read yet (those strokes are dropped). A straight strike-through only counts
     * over typed words, so writing a t's crossbar or a dash next to your words still writes.
     */
    private fun crossOut(s: FloatArray): Boolean {
        val n = s.size / 3
        if (n < 3) return false
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var len = 0f; var turns = 0; var lastDir = 0
        for (k in 0 until n) {
            val x = s[k * 3]; val y = s[k * 3 + 1]
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            if (k > 0) {
                val dx = x - s[k * 3 - 3]; len += hypot(dx, y - s[k * 3 - 2])
                val dir = if (dx > 1.5f) 1 else if (dx < -1.5f) -1 else 0
                if (dir != 0 && lastDir != 0 && dir != lastDir) turns++
                if (dir != 0) lastDir = dir
            }
        }
        val w = maxX - minX; val h = maxY - minY
        val lh = g.lineH
        if (w < Px.dp(12f)) return false
        val straight = h <= lh * 0.55f && len <= w * 1.35f
        val scribble = turns >= 3 && len >= w * 2.2f && h <= lh * 1.3f
        if (!straight && !scribble) return false
        // which line: the stroke's middle, on screen
        val cy = g.paper.top + (minY + maxY) / 2
        if (cy < g.firstRule || cy >= g.toolTop) return false
        val line = g.lineAt(cy)
        val l0 = g.paper.left + minX; val l1 = g.paper.left + maxX
        // typed words it covers (at least half of each word)
        var hit = false
        if (page.sketches.none { it.covers(line) }) {
            val text = page.lines[line]
            val keep = StringBuilder(); var i = 0; var removed = false
            while (i < text.length) {
                val st = i
                while (i < text.length && text[i] == ' ') i++
                val ws = i
                while (i < text.length && text[i] != ' ') i++
                if (ws >= i) { keep.append(text, st, i); break }
                val x0 = g.textL + art.width(text, ws); val x1 = g.textL + art.width(text, i)
                val cover = (minOf(x1, l1) - maxOf(x0, l0)) / (x1 - x0).coerceAtLeast(1f)
                if (cover >= 0.5f) removed = true else keep.append(text, st, i)
            }
            if (removed) {
                pushUndo()
                page.lines[line] = keep.toString().replace(Regex("(?<=\\S) {2,}"), " ").trimEnd().let { if (text.startsWith(" ")) it else it.trimStart() }
                if (caretPage == pageNo && caretLine == line) caretIdx = caretIdx.coerceAtMost(page.lines[line].length)
                hit = true
            }
        }
        // a scribble over handwriting still waiting to be read wipes those strokes
        if (scribble) {
            val before = pending.size
            pending.removeAll { p ->
                var a = Float.MAX_VALUE; var b = -Float.MAX_VALUE; var t = Float.MAX_VALUE; var bt = -Float.MAX_VALUE; var k = 0
                while (k + 1 < p.size) { a = minOf(a, p[k]); b = maxOf(b, p[k]); t = minOf(t, p[k + 1]); bt = maxOf(bt, p[k + 1]); k += 3 }
                val ox = minOf(b, maxX) - maxOf(a, minX); val oy = minOf(bt, maxY) - maxOf(t, minY)
                ox > (b - a) * 0.5f && oy > 0f
            }
            if (pending.size != before) hit = true
        }
        if (hit) { Notes.save(); invalidate() }
        return hit
    }

    // ---- checkmarks: written as one stroke, a short dip then a long rise to the right

    /**
     * A tick, told apart from an "r" or a "v": it dips down (a little to the right), then rises
     * further than it fell, to the right, ending well above where it started.
     */
    private fun isCheck(s: FloatArray): Boolean {
        val n = s.size / 3
        if (n < 4) return false
        var low = 0
        for (k in 1 until n) if (s[k * 3 + 1] > s[low * 3 + 1]) low = k
        if (low == 0 || low == n - 1) return false
        val sx = s[0]; val sy = s[1]; val lx = s[low * 3]; val ly = s[low * 3 + 1]; val ex = s[(n - 1) * 3]; val ey = s[(n - 1) * 3 + 1]
        val down = hypot(lx - sx, ly - sy); val up = hypot(ex - lx, ey - ly)
        val h = maxOf(ly - minOf(sy, ey), 1f)
        if (h < g.lineH * 0.3f || h > g.lineH * 2.2f) return false
        // the dip: downwards, not backwards much
        if (ly - sy < h * 0.2f || lx - sx < -h * 0.25f) return false
        // the rise: longer than the dip, clearly to the right and steep
        if (up < down * 1.3f || ex - lx < h * 0.25f || ly - ey < h * 0.6f) return false
        // ends well above where it began (an "r" comes back to about its starting height)
        if (sy - ey < h * 0.35f) return false
        // and the rise is fairly straight (an r's shoulder curves over)
        var path = 0f
        for (k in low + 1 until n) path += hypot(s[k * 3] - s[k * 3 - 3], s[k * 3 + 1] - s[k * 3 - 2])
        return path <= up * 1.3f
    }

    private fun writeCheck(s: FloatArray) {
        flushPending()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var k = 0
        while (k + 1 < s.size) { minX = minOf(minX, s[k]); minY = minOf(minY, s[k + 1]); maxY = maxOf(maxY, s[k + 1]); k += 3 }
        val line = g.lineAt(g.paper.top + (minY + maxY) / 2)
        pushUndo()
        insert(pageNo, line, g.paper.left + minX, "✓")
        Notes.save(); invalidate()
    }

    /** If this stroke is a drawn box (closed, four corners, hugging its edges), its bounds on screen. */
    private fun box(s: FloatArray): RectF? {
        val n = s.size / 3
        if (n < 12) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (k in 0 until n) { val x = s[k * 3]; val y = s[k * 3 + 1]; l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y) }
        val w = r - l; val h = b - t; val m = minOf(w, h)
        if (w < g.textW * 0.25f || h < g.lineH * 2.2f) return null
        if (hypot(s[0] - s[(n - 1) * 3], s[1] - s[(n - 1) * 3 + 1]) > m * 0.3f) return null
        var edge = 0f
        val near = BooleanArray(4)
        for (k in 0 until n) {
            val x = s[k * 3]; val y = s[k * 3 + 1]
            edge += minOf(minOf(x - l, r - x), minOf(y - t, b - y))
            if (hypot(x - l, y - t) < m * 0.22f) near[0] = true
            if (hypot(x - r, y - t) < m * 0.22f) near[1] = true
            if (hypot(x - l, y - b) < m * 0.22f) near[2] = true
            if (hypot(x - r, y - b) < m * 0.22f) near[3] = true
        }
        if (edge / n / m > 0.08f || !near.all { it }) return null
        return RectF(l + g.paper.left, t + g.paper.top, r + g.paper.left, b + g.paper.top)
    }

    private fun makeSketch(b: RectF) {
        // the box's shape picks the window: wide (4:3), square, or tall (3:4)
        val tall = b.height() > b.width() * 1.15f
        val square = !tall && b.width() < b.height() * 1.15f
        val ratio = if (tall) 4f / 3f else if (square) 1f else 0.75f
        val line0 = g.lineAt(b.top + g.lineH * 0.3f)
        var w = minOf(b.width(), g.textW)
        var lines = Math.ceil((w * ratio / g.lineH).toDouble()).toInt().coerceAtLeast(3)
        if (line0 + lines > Notes.LINES) { lines = Notes.LINES - line0; if (lines < 3) { toast("No room for a sketch here: draw the box higher up"); return } }
        w = minOf(w, (lines * g.lineH - g.lineH * 0.24f) / ratio)
        var x = ((b.left - g.textL) / g.textW).coerceIn(0f, 1f)
        val wf = w / g.textW
        if (x + wf > 1f) x = 1f - wf
        if (page.sketches.any { line0 < it.line + it.lines && it.line < line0 + lines }) { toast("That overlaps another sketch"); return }
        if ((line0 until line0 + lines).any { page.lines[it].isNotBlank() }) { toast("Draw the box over empty lines"); return }
        pushUndo()
        page.sketches += Notes.Sketch(Notes.newId(), line0, x, wf, lines, square, tall)
        Notes.save(); invalidate()
    }

    private fun flushPending() { if (pending.isNotEmpty()) { removeCallbacks(recognize); readPending() } }

    private fun readPending() {
        if (pending.isEmpty() || reading) return
        if (Handwriting.state != Handwriting.State.READY) { Handwriting.prepare(); invalidate(); return }
        val strokes = pending.toList()
        var t = Float.MAX_VALUE; var b = -Float.MAX_VALUE; var l = Float.MAX_VALUE
        for (s in strokes) { var k = 0; while (k + 1 < s.size) { l = minOf(l, s[k]); t = minOf(t, s[k + 1]); b = maxOf(b, s[k + 1]); k += 3 } }
        val pageIdx = pageNo
        val line = g.lineAt(g.paper.top + (t + b) / 2)
        val xAt = l + g.paper.left
        val target = book.pages[pageIdx]
        val before = target.lines[line].let { s -> if (caretPage == pageIdx && caretLine == line) s.take(caretIdx) else s.take(indexForX(s, xAt)) }
        reading = true; invalidate()
        Handwriting.recognize(strokes, before, g.textW, g.lineH * 3) { text ->
            post {
                reading = false
                pending.removeAll(strokes.toSet())
                if (!text.isNullOrBlank() && book.pages.getOrNull(pageIdx) === target) { pushUndo(); insert(pageIdx, line, xAt, text) }
                Notes.save(); invalidate()
                if (pending.isNotEmpty()) { removeCallbacks(recognize); postDelayed(recognize, 400) }
            }
        }
    }

    /** Where on a line a word written at screen x goes: before the first word that starts after it. */
    private fun indexForX(s: String, x: Float): Int {
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) break
            if (g.textL + art.width(s, i) > x) return i
            while (i < s.length && s[i] != ' ') i++
        }
        return s.length
    }

    private fun insert(pageIdx: Int, line0: Int, x: Float, raw: String) {
        val pg = book.pages[pageIdx]
        var line = line0
        // a line under a sketch can't hold words: use the first free line after it
        pg.sketches.firstOrNull { it.covers(line) }?.let { line = it.line + it.lines }
        if (line >= Notes.LINES) { toast("No room on this page there"); return }
        val s = pg.lines[line]
        val t = raw.trim()
        val atCaret = caretPage == pageIdx && caretLine == line
        var idx = if (atCaret) caretIdx.coerceIn(0, s.length) else indexForX(s, x)
        var lead = ""
        // the first words on an empty line keep roughly where they were written (an indent, a heading)
        if (s.isBlank() && !atCaret) {
            val sp = art.width(" ").coerceAtLeast(1f)
            lead = " ".repeat(((x - g.textL) / sp).toInt().coerceIn(0, 40).let { if (it < 3) 0 else it })
            idx = 0
        }
        val left = if (s.isBlank()) lead else s.substring(0, idx)
        val right = if (s.isBlank()) "" else s.substring(idx)
        val sepL = if (left.isEmpty() || left.endsWith(" ") || t.first() in ".,;:!?)'\"") "" else " "
        val sepR = if (right.isEmpty() || right.startsWith(" ") || right.first() in ".,;:!?)") "" else " "
        pg.lines[line] = left + sepL + t + sepR + right
        caretPage = pageIdx; caretLine = line; caretIdx = (left + sepL + t).length
        reflow(pageIdx, line)
    }

    /** Words that run past the edge move down to the next free line (and on to the next page). */
    private fun reflow(pageIdx: Int, line0: Int, b: Notes.Book = book) {
        var pi = pageIdx; var li = line0
        repeat(400) {
            val pg = b.pages[pi]; val s = pg.lines[li]
            if (art.width(s) <= g.textW) return
            var cut = s.length
            while (cut > 0 && art.width(s, cut) > g.textW) cut = s.lastIndexOf(' ', cut - 1).let { if (it <= 0) 0 else it }
            if (cut <= 0 || s.substring(0, cut).isBlank()) { cut = s.length; while (cut > 1 && art.width(s, cut) > g.textW) cut-- }
            val head = s.substring(0, cut).trimEnd(); val tail = s.substring(cut).trimStart()
            pg.lines[li] = head
            val moveCaret = b === book && caretPage == pi && caretLine == li && caretIdx > head.length
            val shift = cut + (s.length - cut - s.substring(cut).trimStart().length)
            var np = pi; var nl = li + 1
            while (true) {
                if (nl >= Notes.LINES) { np++; nl = 0; if (np >= b.pages.size) b.pages += Notes.Page() }
                if (b.pages[np].sketches.none { it.covers(nl) }) break
                nl++
            }
            val nxt = b.pages[np].lines[nl]
            b.pages[np].lines[nl] = if (nxt.isBlank()) tail else "$tail ${nxt.trimStart()}"
            if (moveCaret) { caretPage = np; caretLine = nl; caretIdx = (caretIdx - shift).coerceIn(0, tail.length) }
            pi = np; li = nl
        }
    }

    private fun placeCaret(x: Float, y: Float) {
        val line = g.lineAt(y)
        if (page.sketches.any { it.covers(line) }) return
        val s = page.lines[line]
        var best = s.length; var bd = Float.MAX_VALUE
        for (i in 0..s.length) { val d = abs(g.textL + art.width(s, i) - x); if (d < bd) { bd = d; best = i } }
        caretPage = pageNo; caretLine = line; caretIdx = best
        invalidate()
    }

    // ---- the eraser: whole words
    private fun eraseAt(x: Float, y: Float) {
        val px = x - g.paper.left; val py = y - g.paper.top
        val r = Px.dp(14f)
        pending.removeAll { s -> var k = 0; var hit = false; while (k + 1 < s.size) { if (hypot(s[k] - px, s[k + 1] - py) < r) { hit = true; break }; k += 3 }; hit }
        if (y < g.firstRule || y >= g.toolTop) return
        val line = g.lineAt(y)
        if (page.sketches.any { it.covers(line) }) return
        val s = page.lines[line]
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i] == ' ') i++
            val st = i
            while (i < s.length && s[i] != ' ') i++
            if (st >= i) break
            val x0 = g.textL + art.width(s, st); val x1 = g.textL + art.width(s, i)
            if (x >= x0 - Px.dp(4f) && x <= x1 + Px.dp(4f)) {
                val left = s.substring(0, st).trimEnd(); val right = s.substring(i).trimStart()
                page.lines[line] = if (left.isEmpty()) right else if (right.isEmpty()) left else "$left $right"
                if (caretPage == pageNo && caretLine == line) caretIdx = caretIdx.coerceAtMost(page.lines[line].length)
                return
            }
        }
    }

    private fun pushUndo() {
        undo += Triple(book.id, pageNo, page.snapshot())
        if (undo.size > 40) undo.removeAt(0)
    }
    private fun undoLast() {
        val (bid, idx, snap) = undo.removeLastOrNull() ?: return toast("Nothing to undo")
        val b = Notes.books.firstOrNull { it.id == bid } ?: return
        val pg = b.pages.getOrNull(idx) ?: return
        for (i in 0 until Notes.LINES) pg.lines[i] = snap.lines[i]
        pg.sketches.clear(); pg.sketches.addAll(snap.sketches)
        goTo(b, idx)
    }

    // ---- the keyboard, at the cursor
    private fun typeHere() {
        if (caretPage != pageNo || caretLine < 0) {
            val last = (Notes.LINES - 1 downTo 0).firstOrNull { page.lines[it].isNotBlank() } ?: -1
            var l = (last + 1).coerceAtMost(Notes.LINES - 1)
            if (last >= 0 && art.width(page.lines[last]) < g.textW * 0.8f) l = last
            while (l < Notes.LINES && page.sketches.any { it.covers(l) }) l++
            if (l >= Notes.LINES) return toast("This page is full")
            caretPage = pageNo; caretLine = l; caretIdx = page.lines[l].length
        }
        requestFocus()
        (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
    }

    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { typed(text.toString()); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { repeat(beforeLength.coerceAtLeast(1)) { backspace() }; return true }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> backspace()
                    KeyEvent.KEYCODE_ENTER -> typed("\n")
                    else -> event.unicodeChar.takeIf { it > 0 }?.let { typed(it.toChar().toString()) }
                }
                return true
            }
        }
    }

    private fun typed(t: String) {
        if (caretPage != pageNo || caretLine < 0) return
        pushUndoTyping()
        for (ch in t) {
            val s = page.lines[caretLine]
            if (ch == '\n') {
                // split the line: the rest starts the next free line
                val rest = s.substring(caretIdx.coerceAtMost(s.length)).trimStart()
                page.lines[caretLine] = s.substring(0, caretIdx.coerceAtMost(s.length)).trimEnd()
                var l = caretLine + 1
                while (l < Notes.LINES && page.sketches.any { it.covers(l) }) l++
                if (l >= Notes.LINES) { page.lines[caretLine] = s; break }
                page.lines[l] = if (page.lines[l].isBlank()) rest else (rest + " " + page.lines[l]).trim()
                caretLine = l; caretIdx = 0
                reflow(pageNo, l)
            } else {
                val i = caretIdx.coerceIn(0, s.length)
                page.lines[caretLine] = s.substring(0, i) + ch + s.substring(i)
                caretIdx = i + 1
                reflow(pageNo, caretLine)
            }
        }
        if (caretPage != pageNo) goTo(book, caretPage)
        Notes.save(); invalidate()
    }
    private var lastTypeUndo = 0L
    private fun pushUndoTyping() { val now = SystemClock.uptimeMillis(); if (now - lastTypeUndo > 1500) pushUndo(); lastTypeUndo = now }
    private fun backspace() {
        if (caretPage != pageNo || caretLine < 0) return
        pushUndoTyping()
        val s = page.lines[caretLine]
        if (caretIdx > 0) { page.lines[caretLine] = s.removeRange(caretIdx - 1, caretIdx); caretIdx-- }
        else {
            var l = caretLine - 1
            while (l >= 0 && page.sketches.any { it.covers(l) }) l--
            if (l < 0) return
            val prev = page.lines[l].trimEnd()
            page.lines[l] = if (prev.isEmpty()) s else if (s.isBlank()) prev else "$prev $s"
            page.lines[caretLine] = ""
            caretLine = l; caretIdx = prev.length
            reflow(pageNo, l)
        }
        Notes.save(); invalidate()
    }

    // =====================================================================================
    // Menus: memo books (top left), bookmarks (top middle), search (top right)
    // =====================================================================================

    private fun booksMenu() {
        flushPending()
        val d = SnesDialog(a, "Memo books")
        Notes.books.forEachIndexed { i, b -> d.item(b.name + "  (${b.pages.count { !it.empty }} pages)", Snes.accent(i), b === book) { goTo(b, b.current) } }
        d.item("+ New memo book", Snes.GREEN) {
            SnesDialog.text(a, "Name the new memo book", "Memo ${Notes.books.size + 1}") { name -> Notes.create(name); caretLine = -1; invalidate() }
        }
        d.item("Rename \"${book.name}\"", Snes.YELLOW) { SnesDialog.text(a, "Rename", book.name) { name -> if (name.isNotBlank()) { book.name = name; Notes.save(); invalidate() } } }
        d.item("Export \"${book.name}\"…", Snes.PURPLE) { exportMenu() }
        extraMenu?.invoke(d)
        d.item("Delete \"${book.name}\"", Snes.RED) {
            SnesDialog.ask(a, "Delete \"${book.name}\"?", "Every page and sketch in it goes. This can't be undone.",
                "Keep it" to null, "Delete" to { Notes.delete(book); caretLine = -1; undo.clear(); invalidate() })
        }
        d.key("Close", Snes.PURPLE, then = null).show()
    }

    private fun exportMenu() {
        val formats = NoteExport.Format.values()
        SnesDialog.choose(a, "Export \"${book.name}\" as", formats.map { it.label }) { i ->
            val f = formats[i]; val b = book
            Thread {
                val uri = runCatching {
                    app.foxbox.kit.Export.save("Notebooks", app.foxbox.kit.Export.safe(b.name) + "." + f.ext, f.mime, NoteExport.build(a, b, f))
                }.getOrNull()
                a.runOnUiThread {
                    if (uri == null) return@runOnUiThread toast("Couldn't save the file")
                    a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = f.mime; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, b.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Saved. Share ${b.name}?"))
                }
            }.start()
        }
    }

    private fun snippet(pg: Notes.Page) = pg.lines.firstOrNull { it.isNotBlank() }?.trim()?.take(28) ?: if (pg.sketches.isNotEmpty()) "(sketch)" else "(empty)"

    private fun bookmarksMenu() {
        val d = SnesDialog(a, "Bookmarks")
        val here = page.id
        if (book.bookmarks.contains(here)) d.item("Remove the bookmark from page ${pageNo + 1}", Snes.RED) { book.bookmarks.remove(here); Notes.save(); invalidate() }
        else d.item("Bookmark page ${pageNo + 1}", Snes.RED) { book.bookmarks += here; Notes.save(); invalidate() }
        book.bookmarks.mapNotNull { id -> book.pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } }.sorted().forEach { i ->
            d.item("p. ${i + 1}   ${snippet(book.pages[i])}", Snes.YELLOW, i == pageNo) { goTo(book, i) }
        }
        d.key("Close", Snes.PURPLE, then = null).show()
    }

    private fun search() {
        flushPending()
        SnesDialog.text(a, "Search your notebooks", highlight ?: "") { q ->
            val term = q.trim()
            if (term.isEmpty()) return@text
            val hits = ArrayList<Pair<Notes.Book, Int>>()
            for (b in Notes.books) b.pages.forEachIndexed { i, pg -> if (pg.lines.any { it.contains(term, ignoreCase = true) }) hits += b to i }
            if (hits.isEmpty()) return@text toast("No \"$term\" in any memo book")
            SnesDialog.choose(a, "\"$term\": ${hits.size} page" + if (hits.size > 1) "s" else "", hits.map { (b, i) ->
                val line = b.pages[i].lines.first { it.contains(term, ignoreCase = true) }.trim()
                val k = line.indexOf(term, ignoreCase = true)
                "${b.name} · p.${i + 1}: " + line.substring((k - 10).coerceAtLeast(0)).take(30)
            }) { n -> val (b, i) = hits[n]; goTo(b, i); highlight = term; invalidate() }
        }
    }

    private fun sketchMenu(s: Notes.Sketch) {
        SnesDialog.ask(a, "Sketch", "Open it to draw, or take it off the page.",
            "Remove" to { pushUndo(); page.sketches.remove(s); Notes.save(); invalidate() },
            "Open" to { openSketch(s) })
    }

    private fun toast(s: String) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show() }

    /** The sketch editor closed: its picture may have changed. */
    fun refresh() = invalidate()
}
