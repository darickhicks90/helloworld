package app.sketchsolve.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import app.sketchsolve.ink.Geom
import app.sketchsolve.ink.Pt
import app.sketchsolve.ink.Stroke
import kotlin.math.*

/** A tappable label drawn on the paper. Coordinates are in dp. */
data class PaperLabel(val id: String, val text: String, val at: Pt, val style: Int) {
    companion object { const val PLAIN = 0; const val KNOWN = 1; const val TARGET = 2; const val ANSWER = 3; const val FIXED = 4; const val SMALL = 5; const val WARN = 6 }
}

data class InkStyle(val color: Int, val width: Float = 2.6f, val dash: Boolean = false)

interface PaperHost {
    fun kindFor(tool: String): String?
    fun onStroke(s: Stroke, paper: PaperView) {}
    fun onChanged(paper: PaperView)
    fun styleFor(s: Stroke): InkStyle
    fun drawBelow(c: Canvas, paper: PaperView) {}
    fun drawAbove(c: Canvas, paper: PaperView) {}
    fun onLabelTap(label: PaperLabel) {}
}

/**
 * A sheet of graph paper you can write on. Strokes are stored in dp so the
 * recognisers behave the same on every screen density.
 */
class PaperView(ctx: Context, private val showPad: Boolean = true) : View(ctx) {
    var host: PaperHost? = null
    var strokes: MutableList<Stroke> = ArrayList()
        private set
    var tool = "pen"
    var labels: List<PaperLabel> = emptyList()
        set(v) { field = v; invalidate() }

    private val undoStack = ArrayDeque<List<Stroke>>()
    private val redoStack = ArrayDeque<List<Stroke>>()
    private var current: MutableList<Pt>? = null
    private var currentKind: String? = null
    private var downTime = 0L
    private var stylusSeen = false
    private var erasing = false
    private val density = resources.displayMetrics.density

    val widthDp get() = width / density
    val heightDp get() = height / density

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelRects = HashMap<String, RectF>()

    companion object { private var nextId = 1; fun bumpId(n: Int) { nextId = max(nextId, n + 1) } }

    /* ---------------- drawing ---------------- */
    val sheet: RectF get() = RectF(6f, if (showPad) 4f else 0f, widthDp - 6f, heightDp - 6f)

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(density, density)
        drawSheet(canvas)
        canvas.save()
        canvas.clipRect(sheet)
        host?.drawBelow(canvas, this)
        for (s in strokes) drawInk(canvas, s.pts, host?.styleFor(s) ?: InkStyle(Ink.PENCIL))
        current?.let { pts -> drawInk(canvas, pts, host?.styleFor(Stroke(-1, currentKind ?: "ink", pts)) ?: InkStyle(Ink.PENCIL)) }
        host?.drawAbove(canvas, this)
        drawLabels(canvas)
        canvas.restore()
        canvas.restore()
    }

    private fun drawSheet(c: Canvas) {
        val r = sheet
        // shadow on the desk
        fill.color = 0x30000000
        c.drawRect(r.left + 2f, r.top + 4f, r.right + 3f, r.bottom + 5f, fill)
        fill.color = 0x18000000
        c.drawRect(r.left + 4f, r.top + 7f, r.right + 6f, r.bottom + 8f, fill)
        fill.color = Ink.PAPER
        c.drawRect(r, fill)
        // grid
        val g = Paint().apply { strokeWidth = 0.6f; color = Ink.GRID }
        val step = 20f
        var x = r.left + step
        var i = 1
        while (x < r.right) { g.color = if (i % 5 == 0) Ink.GRID_MAJOR else Ink.GRID; g.strokeWidth = if (i % 5 == 0) 0.9f else 0.6f; c.drawLine(x, r.top, x, r.bottom, g); x += step; i++ }
        var y = r.top + (if (showPad) 16f else 0f) + step
        i = 1
        while (y < r.bottom) { g.color = if (i % 5 == 0) Ink.GRID_MAJOR else Ink.GRID; g.strokeWidth = if (i % 5 == 0) 0.9f else 0.6f; c.drawLine(r.left, y, r.right, y, g); y += step; i++ }
        if (showPad) {
            // the pad's glued edge and a binder clip
            fill.shader = LinearGradient(0f, r.top, 0f, r.top + 16f, 0xFF3E5C8A.toInt(), 0xFF2D4469.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(r.left, r.top, r.right, r.top + 16f, fill)
            fill.shader = null
            val cx = r.centerX()
            fill.color = 0x55000000
            c.drawRoundRect(cx - 44f, r.top - 3f, cx + 46f, r.top + 22f, 4f, 4f, fill)
            fill.shader = LinearGradient(0f, r.top - 5f, 0f, r.top + 20f, 0xFF4A4F57.toInt(), 0xFF1F2328.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(cx - 46f, r.top - 5f, cx + 44f, r.top + 20f, 4f, 4f, fill)
            fill.shader = null
            val wire = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f; color = 0xFFB9C0C8.toInt() }
            c.drawArc(cx - 30f, r.top - 20f, cx - 4f, r.top + 6f, 180f, 180f, false, wire)
            c.drawArc(cx + 2f, r.top - 20f, cx + 28f, r.top + 6f, 180f, 180f, false, wire)
        }
    }

    fun drawInk(c: Canvas, pts: List<Pt>, st: InkStyle) {
        if (pts.isEmpty()) return
        inkPaint.color = st.color
        inkPaint.strokeWidth = st.width
        inkPaint.pathEffect = if (st.dash) DashPathEffect(floatArrayOf(7f, 5f), 0f) else null
        val path = Path()
        path.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
        if (pts.size < 3) for (p in pts) path.lineTo(p.x.toFloat(), p.y.toFloat())
        else {
            for (i in 1 until pts.size - 1) {
                val mx = (pts[i].x + pts[i + 1].x) / 2; val my = (pts[i].y + pts[i + 1].y) / 2
                path.quadTo(pts[i].x.toFloat(), pts[i].y.toFloat(), mx.toFloat(), my.toFloat())
            }
            path.lineTo(pts.last().x.toFloat(), pts.last().y.toFloat())
        }
        c.drawPath(path, inkPaint)
        inkPaint.pathEffect = null
    }

    private fun drawLabels(c: Canvas) {
        labelRects.clear()
        for (l in labels) {
            val small = l.style == PaperLabel.SMALL
            labelText.textSize = if (small) 10f else 13.5f
            labelText.typeface = if (small) Typeface.MONOSPACE else Ink.serifBold
            val w = labelText.measureText(l.text) + if (small) 4f else 16f
            val h = if (small) 13f else 22f
            val r = RectF((l.at.x - w / 2).toFloat(), (l.at.y - h / 2).toFloat(), (l.at.x + w / 2).toFloat(), (l.at.y + h / 2).toFloat())
            if (!small) {
                val (bg, border, fg) = when (l.style) {
                    PaperLabel.KNOWN -> Triple(0xFFE2F5EA.toInt(), Ink.KNOWN, Ink.KNOWN)
                    PaperLabel.TARGET -> Triple(0xFFFCE6EE.toInt(), Ink.FIND, Ink.FIND)
                    PaperLabel.ANSWER -> Triple(Ink.FIND, Ink.FIND, Color.WHITE)
                    PaperLabel.WARN -> Triple(0xFFFFF0D6.toInt(), Ink.WARN, Ink.WARN)
                    PaperLabel.FIXED -> Triple(0xFFF4F4EE.toInt(), Ink.GUIDE, Ink.MUTED)
                    else -> Triple(Color.WHITE, Ink.GUIDE, Ink.TEXT)
                }
                fill.color = 0x30000000
                c.drawRoundRect(RectF(r.left + 1f, r.top + 2f, r.right + 1f, r.bottom + 2f), h / 2, h / 2, fill)
                fill.color = bg
                c.drawRoundRect(r, h / 2, h / 2, fill)
                inkPaint.color = border; inkPaint.strokeWidth = 1.4f
                c.drawRoundRect(r, h / 2, h / 2, inkPaint)
                labelText.color = fg
                labelRects[l.id] = RectF(r.left - 6, r.top - 6, r.right + 6, r.bottom + 6)
            } else labelText.color = 0x997A7466.toInt()
            val fm = labelText.fontMetrics
            c.drawText(l.text, l.at.x.toFloat(), (l.at.y - (fm.ascent + fm.descent) / 2).toFloat(), labelText)
        }
    }

    /* ---------------- input ---------------- */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val toolType = e.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) stylusSeen = true
        // palm rejection: once a stylus is used, fingers don't draw
        if (stylusSeen && toolType == MotionEvent.TOOL_TYPE_FINGER) return true
        val eraserTip = toolType == MotionEvent.TOOL_TYPE_ERASER
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (tool == "erase" || eraserTip) { erasing = true; snapshot(); eraseAt(pt(e.x, e.y)); return true }
                val kind = host?.kindFor(tool) ?: return true
                current = arrayListOf(pt(e.x, e.y)); currentKind = kind
                downTime = e.eventTime
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (erasing) {
                    for (h in 0 until e.historySize) eraseAt(pt(e.getHistoricalX(h), e.getHistoricalY(h)))
                    eraseAt(pt(e.x, e.y)); return true
                }
                val cur = current ?: return true
                for (h in 0 until e.historySize) add(cur, pt(e.getHistoricalX(h), e.getHistoricalY(h)))
                add(cur, pt(e.x, e.y))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (erasing) { erasing = false; changed(); return true }
                val cur = current ?: return true
                current = null
                val quick = e.eventTime - downTime < 350 && Geom.pathLen(cur) < 6
                if (quick) {
                    val hit = labels.firstOrNull { labelRects[it.id]?.contains(cur[0].x.toFloat(), cur[0].y.toFloat()) == true }
                    if (hit != null) { invalidate(); host?.onLabelTap(hit); return true }
                }
                if (cur.size == 1) cur.add(Pt(cur[0].x + 0.5, cur[0].y + 0.5))
                snapshot()
                val s = Stroke(nextId++, currentKind ?: "ink", cur)
                strokes.add(s)
                host?.onStroke(s, this)
                changed()
            }
            MotionEvent.ACTION_CANCEL -> { current = null; erasing = false; invalidate() }
        }
        return true
    }

    private fun pt(x: Float, y: Float) = Pt((x / density).toDouble(), (y / density).toDouble())
    private fun add(cur: MutableList<Pt>, p: Pt) { if (cur.last().dist(p) >= 0.6) cur.add(p) }

    private fun eraseAt(p: Pt) {
        val before = strokes.size
        strokes = strokes.filterNot { s -> (0 until s.pts.size).any { i -> Geom.distPtSeg(p, s.pts[i], s.pts[min(i + 1, s.pts.size - 1)]) < 12 } }.toMutableList()
        if (strokes.size != before) invalidate()
    }

    private fun snapshot() {
        undoStack.addLast(strokes.toList())
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun changed() { invalidate(); host?.onChanged(this) }

    fun undo() { if (undoStack.isEmpty()) return; redoStack.addLast(strokes.toList()); strokes = undoStack.removeLast().toMutableList(); changed() }
    fun redo() { if (redoStack.isEmpty()) return; undoStack.addLast(strokes.toList()); strokes = redoStack.removeLast().toMutableList(); changed() }
    fun clear() { if (strokes.isEmpty()) return; snapshot(); strokes = ArrayList(); changed() }
    fun removeStrokes(ids: Collection<Int>) { snapshot(); strokes = strokes.filter { it.id !in ids }.toMutableList(); changed() }
    fun replaceStrokes(list: List<Stroke>) { strokes = list.toMutableList(); for (s in list) bumpId(s.id); changed() }
    /** Change strokes without recording undo (e.g. dropping an old circle). */
    fun mutate(f: (MutableList<Stroke>) -> Unit) { f(strokes) }
}
