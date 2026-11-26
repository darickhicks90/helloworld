package app.gemmadesk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.gemmadesk.data.Html
import app.gemmadesk.data.Item
import app.gemmadesk.data.Page
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The design screen: every item on the page drawn as it will look, at its
 * real position and size. Tap to select, drag to move, drag a corner or edge
 * handle to resize. Items snap to a 4 px grid and to each other's edges and
 * centres (pink guide lines show what they lined up with). One finger on an
 * empty spot scrolls; two fingers zoom.
 */
class DesignCanvas(ctx: Context) : View(ctx) {
    var page: Page = Page(name = "Page")
        private set
    var selectedId: String? = null
        private set

    /** Called while dragging (commit = false) and when a gesture ends (commit = true, for undo). */
    var onChange: (Page, Boolean) -> Unit = { _, _ -> }
    var onSelect: (Item?) -> Unit = {}
    var onEditText: (Item) -> Unit = {}
    var onLongPress: (Item) -> Unit = {}

    private val d = resources.displayMetrics.density
    private var zoom = 1f          // on top of "fit the frame width"
    private var panX = 0f
    private var panY = 0f
    // fit the frame's width, but no bigger than real size (1 CSS px = 1 dp), so a phone page looks like a phone
    private fun scale() = max(0.05f, min((width - 2 * d * 16) / page.frame.w, d)) * zoom
    private fun ox() = (width - page.frame.w * scale()) / 2f + panX
    private fun oy() = d * 16 + panY
    fun toView(x: Float, y: Float) = floatArrayOf(ox() + x * scale(), oy() + y * scale())
    private fun toPage(vx: Float, vy: Float) = floatArrayOf((vx - ox()) / scale(), (vy - oy()) / scale())

    fun setPage(p: Page, keepSelection: Boolean = true) {
        page = p
        if (!keepSelection || p.items.none { it.id == selectedId }) select(null)
        invalidate()
    }

    fun select(id: String?) {
        selectedId = id
        onSelect(page.items.firstOrNull { it.id == id })
        invalidate()
    }

    fun selected() = page.items.firstOrNull { it.id == selectedId }
    fun resetView() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    /** Scroll so the item is in view (e.g. after adding it). */
    fun reveal(i: Item) {
        val top = toView(0f, i.y.toFloat())[1]; val bottom = toView(0f, i.bottom().toFloat())[1]
        if (top < d * 16) panY += d * 16 - top else if (bottom > height - d * 16) panY -= bottom - (height - d * 16)
        clampPan(); invalidate()
    }
    /** Where the middle of the visible area is, in page pixels (to place new items). */
    fun visibleCentre(): IntArray { val p = toPage(width / 2f, height / 3f); return intArrayOf(p[0].toInt(), max(0f, p[1]).toInt()) }

    /* ------------------------------ drawing ------------------------------ */

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3D8B.toInt(); strokeWidth = d }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x553D5AFE; strokeWidth = d; pathEffect = DashPathEffect(floatArrayOf(d * 6, d * 4), 0f) }
    private val guides = ArrayList<FloatArray>()  // x1,y1,x2,y2 in page px

    private val painter = PagePainter()
    private fun color(c: String, fallback: Int) = PagePainter.color(c, fallback)

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFFE9ECF3.toInt())
        val s = scale()
        val f = page.frame
        val ph = page.height()
        val (x0, y0) = toView(0f, 0f).let { it[0] to it[1] }
        // the page with a soft shadow
        fill.color = 0x22000000; c.drawRoundRect(x0 + 2 * d, y0 + 3 * d, x0 + f.w * s + 2 * d, y0 + ph * s + 3 * d, 4 * d, 4 * d, fill)
        fill.color = color(page.background, Color.WHITE); c.drawRect(x0, y0, x0 + f.w * s, y0 + ph * s, fill)
        // where the first screenful ends
        if (ph > f.h) { c.drawLine(x0, y0 + f.h * s, x0 + f.w * s, y0 + f.h * s, dash); text.color = 0x993D5AFE.toInt(); text.textSize = 10 * d; text.typeface = Typeface.DEFAULT; c.drawText("end of first screen", x0 + 4 * d, y0 + f.h * s - 4 * d, text) }

        c.save()
        c.clipRect(x0, y0, x0 + f.w * s, y0 + ph * s)
        c.translate(x0, y0); c.scale(s, s)
        for (i in page.items) painter.drawItem(c, i)
        c.restore()

        // guides while dragging
        for (g in guides) { val a = toView(g[0], g[1]); val b = toView(g[2], g[3]); c.drawLine(a[0], a[1], b[0], b[1], guidePaint) }

        // selection outline, handles and size
        selected()?.let { i ->
            val a = toView(i.x.toFloat(), i.y.toFloat()); val b = toView(i.right().toFloat(), i.bottom().toFloat())
            stroke.color = C.ACCENT; stroke.strokeWidth = 2 * d
            c.drawRect(a[0], a[1], b[0], b[1], stroke)
            for ((hx, hy) in handles(i)) {
                val p = toView(hx, hy)
                fill.color = Color.WHITE; c.drawCircle(p[0], p[1], 7 * d, fill)
                stroke.strokeWidth = 2 * d; c.drawCircle(p[0], p[1], 7 * d, stroke)
            }
            val label = "${i.w} × ${i.h}   (${i.x}, ${i.y})"
            text.textSize = 11 * d; text.typeface = Typeface.DEFAULT_BOLD
            val tw = text.measureText(label)
            val ly = if (a[1] > 26 * d) a[1] - 8 * d else b[1] + 20 * d
            fill.color = C.ACCENT; c.drawRoundRect(a[0], ly - 14 * d, a[0] + tw + 12 * d, ly + 5 * d, 4 * d, 4 * d, fill)
            text.color = Color.WHITE; c.drawText(label, a[0] + 6 * d, ly, text)
        }
    }

    private fun handles(i: Item): List<Pair<Float, Float>> {
        val l = i.x.toFloat(); val t = i.y.toFloat(); val r = i.right().toFloat(); val b = i.bottom().toFloat()
        val mx = (l + r) / 2; val my = (t + b) / 2
        return listOf(l to t, r to t, l to b, r to b, mx to t, mx to b, l to my, r to my)
    }

    /* ------------------------------ touch ------------------------------ */

    private enum class Mode { NONE, MOVE, RESIZE, PAN }
    private var mode = Mode.NONE
    private var handle = -1
    private var startX = 0f; private var startY = 0f
    private var start: Item? = null
    private var moved = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val before = toPage(det.focusX, det.focusY)
            zoom = (zoom * det.scaleFactor).coerceIn(0.4f, 4f)
            val after = toView(before[0], before[1])
            panX += det.focusX - after[0]; panY += det.focusY - after[1]
            mode = Mode.PAN; clampPan(); invalidate(); return true
        }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { hit(e.x, e.y)?.let { select(it.id); onEditText(it) }; return true }
        override fun onLongPress(e: MotionEvent) { if (!moved) hit(e.x, e.y)?.let { select(it.id); onLongPress(it) } }
    })

    /** The top-most item under a point (later items are on top). */
    private fun hit(vx: Float, vy: Float): Item? {
        val p = toPage(vx, vy); val slop = 6 * d / scale()
        return page.items.lastOrNull { p[0] >= it.x - slop && p[0] <= it.right() + slop && p[1] >= it.y - slop && p[1] <= it.bottom() + slop }
    }
    private fun hitHandle(vx: Float, vy: Float): Int {
        val i = selected() ?: return -1
        handles(i).forEachIndexed { k, (hx, hy) -> val p = toView(hx, hy); if (abs(p[0] - vx) < 22 * d && abs(p[1] - vy) < 22 * d) return k }
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e); gestures.onTouchEvent(e)
        if (e.pointerCount > 1) { if (mode == Mode.MOVE || mode == Mode.RESIZE) finishGesture(); mode = Mode.PAN; startX = e.x; startY = e.y; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = e.x; startY = e.y; moved = false
                handle = hitHandle(e.x, e.y)
                val under = hit(e.x, e.y)
                mode = when {
                    handle >= 0 -> Mode.RESIZE
                    under != null -> { if (under.id != selectedId) select(under.id); Mode.MOVE }
                    else -> Mode.PAN
                }
                start = selected()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - startX; val dy = e.y - startY
                if (!moved && abs(dx) < 4 * d && abs(dy) < 4 * d) return true
                moved = true
                when (mode) {
                    Mode.PAN -> { panX += dx; panY += dy; startX = e.x; startY = e.y; clampPan(); invalidate() }
                    Mode.MOVE -> start?.let { s -> drag(s, dx / scale(), dy / scale(), -1) }
                    Mode.RESIZE -> start?.let { s -> drag(s, dx / scale(), dy / scale(), handle) }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved && mode == Mode.PAN && e.actionMasked == MotionEvent.ACTION_UP) select(null)
                finishGesture()
            }
        }
        return true
    }

    private fun finishGesture() {
        if ((mode == Mode.MOVE || mode == Mode.RESIZE) && moved) onChange(page, true)
        guides.clear(); mode = Mode.NONE; start = null; invalidate()
    }

    private fun clampPan() {
        val s = scale()
        val maxDown = height * 0.5f
        val minUp = -(page.height() * s - height * 0.5f).coerceAtLeast(0f)
        panY = panY.coerceIn(min(minUp, 0f), maxDown)
        val slack = max(0f, (page.frame.w * s - width) / 2f + width * 0.3f)
        panX = panX.coerceIn(-slack - width * 0.3f, slack + width * 0.3f)
    }

    /**
     * Move (handle = -1) or resize the item [s] by (dx, dy) page pixels,
     * snapping to the grid and to other items, inside the frame width.
     */
    private fun drag(s: Item, dx: Float, dy: Float, handle: Int) {
        guides.clear()
        val W = page.frame.w
        val others = page.items.filter { it.id != s.id }
        val xs = (others.flatMap { listOf(it.x, it.right(), (it.x + it.right()) / 2) } + listOf(0, W, W / 2, 16, W - 16, 24, W - 24)).distinct()
        val ys = others.flatMap { listOf(it.y, it.bottom(), (it.y + it.bottom()) / 2) }.distinct()
        val tol = 6 * d / scale()
        fun snap(v: Float, targets: List<Int>, vertical: Boolean): Int {
            val best = targets.minByOrNull { abs(it - v) }
            if (best != null && abs(best - v) <= tol) {
                guides += if (vertical) floatArrayOf(best.toFloat(), 0f, best.toFloat(), page.height().toFloat()) else floatArrayOf(0f, best.toFloat(), W.toFloat(), best.toFloat())
                return best
            }
            return (Math.round(v / 4f) * 4)
        }
        var n = s
        if (handle < 0) {
            // move: try snapping the left edge, centre and right edge; keep whichever is closest
            val nx = s.x + dx; val ny = s.y + dy
            val cand = listOf(0f, s.w / 2f, s.w.toFloat()).map { off -> off to (xs.minByOrNull { abs(it - (nx + off)) } ?: Int.MAX_VALUE) }
                .minByOrNull { (off, t) -> abs(t - (nx + off)) }!!
            val x = if (abs(cand.second - (nx + cand.first)) <= tol) { guides += floatArrayOf(cand.second.toFloat(), 0f, cand.second.toFloat(), page.height().toFloat()); (cand.second - cand.first).toInt() } else Math.round(nx / 4f) * 4
            val candY = listOf(0f, s.h / 2f, s.h.toFloat()).map { off -> off to (ys.minByOrNull { abs(it - (ny + off)) } ?: Int.MAX_VALUE) }
                .minByOrNull { (off, t) -> abs(t - (ny + off)) }!!
            val y = if (abs(candY.second - (ny + candY.first)) <= tol) { guides += floatArrayOf(0f, candY.second.toFloat(), W.toFloat(), candY.second.toFloat()); (candY.second - candY.first).toInt() } else Math.round(ny / 4f) * 4
            n = s.copy(x = x.coerceIn(0, max(0, W - s.w)), y = max(0, y))
        } else {
            // resize from a handle: 0 TL, 1 TR, 2 BL, 3 BR, 4 T, 5 B, 6 L, 7 R
            var l = s.x; var t = s.y; var r = s.right(); var b = s.bottom()
            val minW = if (s.type == "divider") 8 else 16; val minH = if (s.type == "divider") 1 else 12
            if (handle in setOf(0, 2, 6)) l = snap(s.x + dx, xs, true).coerceIn(0, r - minW)
            if (handle in setOf(1, 3, 7)) r = snap(s.right() + dx, xs, true).coerceIn(l + minW, W)
            if (handle in setOf(0, 1, 4)) t = snap(s.y + dy, ys, false).coerceIn(0, b - minH)
            if (handle in setOf(2, 3, 5)) b = snap(s.bottom() + dy, ys, false).coerceAtLeast(t + minH)
            n = s.copy(x = l, y = t, w = r - l, h = b - t)
        }
        page = page.copy(items = page.items.map { if (it.id == s.id) n else it })
        onChange(page, false)
        invalidate()
    }
}
