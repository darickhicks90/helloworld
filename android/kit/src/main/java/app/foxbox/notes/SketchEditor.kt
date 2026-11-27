package app.foxbox.notes

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import app.foxbox.ui.Px
import app.foxbox.ui.Snes
import app.foxbox.ui.SnesDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.exp

/**
 * The sketch window's drawing board. Brushes (pen, pencil, marker, airbrush), eraser, straight
 * line / box / oval, an eyedropper; colour, size, opacity and smoothing; up to four layers with
 * visibility and opacity; undo/redo; pinch to zoom (up to 16×) and two fingers to move around.
 *
 * Sizes are easy to read: a brush's size is in picture pixels with a name (Hairline … Huge), and
 * the dot next to it is exactly how big it will draw at the current zoom. Zoom is shown as how
 * many times closer than the whole picture ("Fit", 2×, 4× …): tap it to step in, long-press to fit.
 */
@SuppressLint("ViewConstructor")
class SketchEditor(private val a: Activity, private val sketch: Notes.Sketch, private val done: () -> Unit) : View(a) {
    private val cw = if (sketch.tall) 1500 else if (sketch.square) 1600 else 2000
    private val ch = if (sketch.tall) 2000 else if (sketch.square) 1600 else 1500

    // ---- the drawing: layers of strokes (kept as strokes, so undo is exact), each cached as a bitmap
    private class Stroke(val tool: Int, val color: Int, val size: Float, val opacity: Float, val pts: FloatArray)
    private inner class Layer(var name: String) {
        val ops = ArrayList<Stroke>()
        var visible = true; var opacity = 1f
        val bmp: Bitmap = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        fun rebuild() { bmp.eraseColor(0); val c = Canvas(bmp); ops.forEach { render(c, it) } }
    }
    private val layers = ArrayList<Layer>()
    private var active = 0
    private val MAX_LAYERS = 4

    private abstract class Act { abstract fun undo(); abstract fun redo() }
    private val history = ArrayList<Act>(); private val redos = ArrayList<Act>()
    private fun did(x: Act) { history += x; redos.clear(); if (history.size > 200) history.removeAt(0) }

    // ---- tools
    private companion object {
        const val PEN = 0; const val PENCIL = 1; const val MARKER = 2; const val AIR = 3; const val ERASER = 4
        const val LINE = 5; const val BOX = 6; const val OVAL = 7; const val PICK = 8
        val TOOL_NAMES = arrayOf("Pen", "Pencil", "Marker", "Air", "Eraser", "Line", "Box", "Oval", "Pick")
        val SWATCHES = intArrayOf(
            0xFF1B1B1F.toInt(), 0xFF4A4A52.toInt(), 0xFF9A9AA4.toInt(), 0xFFFFFFFF.toInt(), 0xFF23306E.toInt(), 0xFF3F7FD0.toInt(),
            0xFF3FB8AF.toInt(), 0xFF22706B.toInt(), 0xFF6FA84A.toInt(), 0xFF2E5240.toInt(), 0xFFF6C26B.toInt(), 0xFFE8B828.toInt(),
            0xFFE8742C.toInt(), 0xFFB8521B.toInt(), 0xFFD9786A.toInt(), 0xFFC0392B.toInt(), 0xFF7A2630.toInt(), 0xFF6448B5.toInt(),
            0xFFB06AC0.toInt(), 0xFFF2C7D0.toInt(), 0xFF8A5A34.toInt(), 0xFF5A3A22.toInt(), 0xFFF7EFDC.toInt(), 0xFFECDCB9.toInt(),
        )
        val SIZE_NAMES = listOf(2f to "Hairline", 5f to "Fine", 12f to "Medium", 28f to "Bold", 60f to "Broad", 130f to "Huge")
    }
    private var tool = PEN
    private var color = 0xFF1B1B1F.toInt()
    private val toolSize = floatArrayOf(10f, 6f, 36f, 80f, 40f, 10f, 10f, 10f, 10f)
    private val toolOpacity = floatArrayOf(1f, 0.85f, 0.45f, 0.35f, 1f, 1f, 1f, 1f, 1f)
    private var smooth = 0.4f
    private val recent = ArrayList<Int>()

    // ---- view: picture → screen
    private val view = Matrix(); private val inv = Matrix()
    private var zoom = 1f; private var fit = 1f
    private val board = RectF()

    // ---- the UI's rectangles
    private val topBar = RectF(); private val rail = RectF(); private val panel = RectF()
    private val bDone = RectF(); private val bUndo = RectF(); private val bRedo = RectF(); private val bZoom = RectF(); private val bLayers = RectF(); private val bShare = RectF()
    private val toolRects = Array(TOOL_NAMES.size) { RectF() }
    private val bColor = RectF(); private val sizeBar = RectF(); private val opBar = RectF(); private val smoothBar = RectF()
    private val swatchRects = Array(12) { RectF() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Px.body; color = Snes.INK; textAlign = Paint.Align.CENTER }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    init {
        load()
        if (layers.isEmpty()) layers += Layer("Layer 1")
        isFocusable = true
    }

    // =====================================================================================
    // Strokes
    // =====================================================================================

    private fun render(c: Canvas, s: Stroke) {
        val n = s.pts.size / 3
        if (n == 0) return
        sp.reset(); sp.isAntiAlias = true; sp.style = Paint.Style.STROKE; sp.strokeCap = Paint.Cap.ROUND; sp.strokeJoin = Paint.Join.ROUND
        val alpha = (s.opacity * 255).toInt().coerceIn(1, 255)
        val layer = s.tool != ERASER && (alpha < 255)
        if (layer) c.saveLayerAlpha(0f, 0f, cw.toFloat(), ch.toFloat(), alpha)
        sp.color = s.color or 0xFF000000.toInt()
        if (s.tool == ERASER) { sp.xfermode = clear; sp.color = 0xFF000000.toInt(); if (alpha < 255) sp.alpha = alpha }
        if (s.tool == AIR) sp.maskFilter = BlurMaskFilter(maxOf(1f, s.size * 0.45f), BlurMaskFilter.Blur.NORMAL)
        if (s.tool == MARKER) sp.strokeCap = Paint.Cap.SQUARE
        val q = s.pts
        when (s.tool) {
            LINE, BOX, OVAL -> {
                sp.strokeWidth = s.size
                val x0 = q[0]; val y0 = q[1]; val x1 = q[(n - 1) * 3]; val y1 = q[(n - 1) * 3 + 1]
                when (s.tool) {
                    LINE -> c.drawLine(x0, y0, x1, y1, sp)
                    BOX -> c.drawRect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), sp)
                    else -> c.drawOval(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), sp)
                }
            }
            else -> {
                if (n == 1) {
                    sp.style = Paint.Style.FILL
                    c.drawCircle(q[0], q[1], s.size * width(s.tool, q[2]) / 2, sp)
                } else for (i in 1 until n) {
                    sp.strokeWidth = maxOf(0.6f, s.size * width(s.tool, (q[i * 3 + 2] + q[i * 3 - 1]) / 2))
                    c.drawLine(q[i * 3 - 3], q[i * 3 - 2], q[i * 3], q[i * 3 + 1], sp)
                }
            }
        }
        if (layer) c.restore()
    }

    /** How pressure (or, with a finger, speed) shapes each brush. */
    private fun width(t: Int, pr: Float) = when (t) {
        PEN -> 0.3f + 0.7f * pr
        PENCIL -> 0.6f + 0.4f * pr
        else -> 1f
    }

    // ---- live stroke
    private var live: FloatArray = FloatArray(256); private var liveN = 0
    private var lastX = 0f; private var lastY = 0f; private var lastT = 0L; private var lastPr = 1f
    private fun liveAdd(x: Float, y: Float, pr: Float) {
        if (liveN + 3 > live.size) live = live.copyOf(live.size * 2)
        live[liveN++] = x; live[liveN++] = y; live[liveN++] = pr
    }
    private fun liveStroke() = Stroke(tool, color, toolSize[tool], toolOpacity[tool], live.copyOf(liveN))

    private fun commit() {
        if (liveN == 0) return
        val s = liveStroke(); liveN = 0
        val l = layers[active]
        l.ops += s; render(Canvas(l.bmp), s)
        did(object : Act() {
            override fun undo() { l.ops.remove(s); l.rebuild() }
            override fun redo() { l.ops += s; render(Canvas(l.bmp), s) }
        })
        if (tool != ERASER) { recent.remove(color); recent.add(0, color); if (recent.size > 6) recent.removeAt(recent.size - 1) }
    }

    // =====================================================================================
    // Layout and drawing
    // =====================================================================================

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val d = Px.dp(1f)
        topBar.set(0f, 0f, w.toFloat(), 50 * d)
        val landscape = w > h
        if (landscape) {
            rail.set(0f, topBar.bottom, 72 * d, h.toFloat())
            panel.set(w - 170 * d, topBar.bottom, w.toFloat(), h.toFloat())
            board.set(rail.right, topBar.bottom, panel.left, h.toFloat())
            val th = (rail.height() - 8 * d) / toolRects.size
            toolRects.forEachIndexed { i, r -> r.set(rail.left + 6 * d, rail.top + 4 * d + i * th, rail.right - 6 * d, rail.top + 4 * d + (i + 1) * th - 4 * d) }
            var y = panel.top + 10 * d
            bColor.set(panel.left + 10 * d, y, panel.right - 10 * d, y + 40 * d); y += 50 * d
            val sw = (panel.width() - 20 * d) / 6
            swatchRects.forEachIndexed { i, r -> val cx = panel.left + 10 * d + (i % 6) * sw; val cy = y + (i / 6) * (sw + 2 * d); r.set(cx + 2 * d, cy, cx + sw - 2 * d, cy + sw - 4 * d) }
            y += sw * 2 + 16 * d
            sizeBar.set(panel.left + 12 * d, y + 30 * d, panel.right - 12 * d, y + 60 * d); y += 92 * d
            opBar.set(panel.left + 12 * d, y + 18 * d, panel.right - 12 * d, y + 44 * d); y += 64 * d
            smoothBar.set(panel.left + 12 * d, y + 18 * d, panel.right - 12 * d, y + 44 * d)
        } else {
            // portrait: tools along the bottom, controls above them
            val toolH = 52 * d; val ctrlH = 150 * d
            rail.set(0f, h - toolH, w.toFloat(), h.toFloat())
            panel.set(0f, rail.top - ctrlH, w.toFloat(), rail.top)
            board.set(0f, topBar.bottom, w.toFloat(), panel.top)
            val tw = w / toolRects.size.toFloat()
            toolRects.forEachIndexed { i, r -> r.set(i * tw + 2 * d, rail.top + 4 * d, (i + 1) * tw - 2 * d, rail.bottom - 4 * d) }
            bColor.set(panel.left + 10 * d, panel.top + 10 * d, panel.left + 70 * d, panel.top + 70 * d)
            val sw = (w * 0.45f) / 6
            swatchRects.forEachIndexed { i, r -> val cx = panel.left + 80 * d + (i % 6) * sw; val cy = panel.top + 8 * d + (i / 6) * (sw); r.set(cx + 2 * d, cy + 2 * d, cx + sw - 2 * d, cy + sw - 2 * d) }
            val col = panel.left + 90 * d + w * 0.45f
            sizeBar.set(panel.left + 12 * d, panel.top + 100 * d, w * 0.5f - 8 * d, panel.top + 126 * d)
            opBar.set(w * 0.5f + 8 * d, panel.top + 100 * d, w - 12 * d, panel.top + 126 * d)
            smoothBar.set(col, panel.top + 30 * d, w - 12 * d, panel.top + 56 * d)
        }
        val bw = minOf(96 * d, (w - app.foxbox.ui.TopKeys.WIDTH) / 7.2f)
        bDone.set(6 * d, 6 * d, 6 * d + bw, topBar.bottom - 6 * d)
        bUndo.set(bDone.right + 8 * d, bDone.top, bDone.right + 8 * d + bw * 0.8f, bDone.bottom)
        bRedo.set(bUndo.right + 6 * d, bDone.top, bUndo.right + 6 * d + bw * 0.8f, bDone.bottom)
        // the top right corner is left for the radio and notebook keys
        val right = w - app.foxbox.ui.TopKeys.WIDTH - 4 * d
        bShare.set(right - bw, bDone.top, right, bDone.bottom)
        bLayers.set(bShare.left - 8 * d - bw, bDone.top, bShare.left - 8 * d, bDone.bottom)
        bZoom.set(bLayers.left - 8 * d - bw * 0.8f, bDone.top, bLayers.left - 8 * d, bDone.bottom)
        fitView()
    }

    private fun fitView() {
        fit = minOf((board.width() - Px.dp(24f)) / cw, (board.height() - Px.dp(24f)) / ch)
        zoom = 1f
        view.reset(); view.postScale(fit, fit)
        view.postTranslate(board.centerX() - cw * fit / 2, board.centerY() - ch * fit / 2)
    }
    private fun scaleNow(): Float { val v = FloatArray(9); view.getValues(v); return v[Matrix.MSCALE_X] }

    override fun onDraw(c: Canvas) {
        // the desk, the picture (white, with a soft shadow), the layers
        c.drawColor(0xFF4A4650.toInt())
        c.save(); c.clipRect(board)
        c.concat(view)
        val k = scaleNow()
        p.color = 0x55000000; c.drawRect(6f / k, 8f / k, cw + 6f / k, ch + 8f / k, p)
        p.color = Color.WHITE; c.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), p)
        bmpP.isFilterBitmap = k < 3f
        for ((i, l) in layers.withIndex()) {
            if (!l.visible) continue
            if (i == active && liveN > 0 && tool != PICK) {
                c.saveLayerAlpha(0f, 0f, cw.toFloat(), ch.toFloat(), (l.opacity * 255).toInt())
                c.drawBitmap(l.bmp, 0f, 0f, bmpP)
                render(c, liveStroke())
                c.restore()
            } else {
                bmpP.alpha = (l.opacity * 255).toInt(); c.drawBitmap(l.bmp, 0f, 0f, bmpP); bmpP.alpha = 255
            }
        }
        c.restore()
        drawUi(c)
    }

    private fun key(c: Canvas, r: RectF, label: String, lit: Boolean = false, lamp: Int = Snes.PURPLE) {
        val d = Px.dp(1f)
        p.color = Snes.INK; c.drawRoundRect(r, 6 * d, 6 * d, p)
        p.color = if (lit) lamp else Snes.FACE; c.drawRoundRect(r.left + 2 * d, r.top + 2 * d, r.right - 2 * d, r.bottom - 3 * d, 5 * d, 5 * d, p)
        p.color = 0x40FFFFFF; c.drawRect(r.left + 5 * d, r.top + 3 * d, r.right - 5 * d, r.top + 5 * d, p)
        txt.color = if (lit) Color.WHITE else Snes.INK
        txt.textSize = minOf(r.height() * 0.42f, Px.dp(15f))
        while (txt.measureText(label) > r.width() - 8 * d && txt.textSize > 6 * d) txt.textSize -= d
        c.drawText(label, r.centerX(), r.centerY() + txt.textSize * 0.35f, txt)
    }

    private fun sizeName(s: Float) = SIZE_NAMES.minByOrNull { abs(ln(it.first / s)) }!!.second

    private fun drawUi(c: Canvas) {
        val d = Px.dp(1f)
        p.color = Snes.SHADE; c.drawRect(topBar, p); p.color = Snes.FACE; c.drawRect(topBar.left, topBar.top, topBar.right, topBar.bottom - 2 * d, p)
        p.color = Snes.SHADE; c.drawRect(rail, p); c.drawRect(panel, p)
        p.color = Snes.FACE; c.drawRect(rail.left + 2 * d, rail.top + 2 * d, rail.right - 2 * d, rail.bottom - 2 * d, p); c.drawRect(panel.left + 2 * d, panel.top + 2 * d, panel.right - 2 * d, panel.bottom - 2 * d, p)
        key(c, bDone, "Done", true, Snes.GREEN)
        key(c, bUndo, "Undo", history.isNotEmpty(), Snes.YELLOW)
        key(c, bRedo, "Redo", redos.isNotEmpty(), Snes.YELLOW)
        val z = scaleNow() / fit
        key(c, bZoom, if (abs(z - 1f) < 0.05f) "Fit" else "${if (z >= 10) z.toInt().toString() else "%.1f".format(z)}×")
        key(c, bLayers, "Layer ${active + 1}/${layers.size}", true, Snes.PURPLE)
        key(c, bShare, "Save PNG", false)
        toolRects.forEachIndexed { i, r -> key(c, r, TOOL_NAMES[i], i == tool, if (i == ERASER) Snes.RED else Snes.PURPLE) }
        // colour: the current one large, with the recent ones beside it
        p.color = Snes.INK; c.drawRoundRect(bColor, 6 * d, 6 * d, p)
        p.color = color; c.drawRoundRect(bColor.left + 3 * d, bColor.top + 3 * d, bColor.right - 3 * d, bColor.bottom - 3 * d, 4 * d, 4 * d, p)
        txt.textSize = 11 * d; txt.color = if (lum(color) > 140) Snes.INK else Color.WHITE
        c.drawText("Colour…", bColor.centerX(), bColor.centerY() + 4 * d, txt)
        val list = (recent + SWATCHES.toList()).distinct().take(swatchRects.size)
        swatchRects.forEachIndexed { i, r ->
            val col = list.getOrNull(i) ?: return@forEachIndexed
            p.color = Snes.INK; c.drawRect(r, p); p.color = col; c.drawRect(r.left + 2 * d, r.top + 2 * d, r.right - 2 * d, r.bottom - 2 * d, p)
            if (col == color) { p.color = Snes.YELLOW; c.drawRect(r.left, r.bottom - 3 * d, r.right, r.bottom, p) }
        }
        // size: name and picture-pixels, and a dot drawn at the real size it will make at this zoom
        val size = toolSize[tool]
        txt.color = Snes.INK; txt.textAlign = Paint.Align.LEFT; txt.textSize = 12 * d
        c.drawText("Size: ${sizeName(size)} · ${size.toInt()} px", sizeBar.left, sizeBar.top - 8 * d, txt)
        slider(c, sizeBar, sizeNorm(size))
        val dot = (size * scaleNow() / 2).coerceIn(1f, 36 * d)
        p.color = color; c.drawCircle(sizeBar.right - dot, sizeBar.top - 26 * d + 0f, minOf(dot, 14 * d), p)
        txt.textSize = 11 * d
        c.drawText("Opacity ${(toolOpacity[tool] * 100).toInt()}%", opBar.left, opBar.top - 6 * d, txt)
        slider(c, opBar, toolOpacity[tool])
        c.drawText("Smoothing ${(smooth * 100).toInt()}%", smoothBar.left, smoothBar.top - 6 * d, txt)
        slider(c, smoothBar, smooth)
        txt.textAlign = Paint.Align.CENTER
    }

    private fun slider(c: Canvas, r: RectF, v: Float) {
        val d = Px.dp(1f)
        p.color = Snes.INK; c.drawRoundRect(r.left, r.centerY() - 4 * d, r.right, r.centerY() + 4 * d, 4 * d, 4 * d, p)
        p.color = Snes.PURPLE; c.drawRoundRect(r.left + d, r.centerY() - 3 * d, r.left + d + (r.width() - 2 * d) * v.coerceIn(0f, 1f), r.centerY() + 3 * d, 3 * d, 3 * d, p)
        val x = r.left + r.width() * v.coerceIn(0f, 1f)
        p.color = Snes.INK; c.drawCircle(x, r.centerY(), 10 * d, p); p.color = Snes.HI; c.drawCircle(x, r.centerY(), 8 * d, p)
    }

    // sizes run from 1 to 200 picture pixels on a log scale, so fine sizes get room on the slider
    private fun sizeNorm(s: Float) = (ln(s) / ln(200f)).coerceIn(0f, 1f)
    private fun sizeFrom(v: Float) = exp(v.coerceIn(0f, 1f) * ln(200f)).coerceIn(1f, 200f)
    private fun lum(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    // =====================================================================================
    // Touch
    // =====================================================================================

    private enum class Mode { NONE, DRAW, GESTURE, UI, SLIDER }
    private var mode = Mode.NONE
    private var dragBar: RectF? = null
    private var downT = 0L; private var downX = 0f; private var downY = 0f
    private var gSpan = 0f; private var gX = 0f; private var gY = 0f
    private var uiTarget: RectF? = null
    private val pt = FloatArray(2)

    private fun toPic(x: Float, y: Float): FloatArray { view.invert(inv); pt[0] = x; pt[1] = y; inv.mapPoints(pt); return pt }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x; val y = e.y
                downT = SystemClock.uptimeMillis(); downX = x; downY = y
                dragBar = listOf(sizeBar, opBar, smoothBar).firstOrNull { RectF(it.left - 12f, it.top - 16f, it.right + 12f, it.bottom + 16f).contains(x, y) }
                mode = when {
                    dragBar != null -> { setSlider(x); Mode.SLIDER }
                    board.contains(x, y) -> { startDraw(e); Mode.DRAW }
                    else -> { uiTarget = (listOf(bDone, bUndo, bRedo, bZoom, bLayers, bShare, bColor) + toolRects + swatchRects).firstOrNull { it.contains(x, y) }; Mode.UI }
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && (mode == Mode.DRAW || mode == Mode.NONE)) {
                // a second finger: this is a zoom / move, not a stroke (keep a long stroke that was already under way)
                if (mode == Mode.DRAW) { if (SystemClock.uptimeMillis() - downT < 250 || liveN < 24) liveN = 0 else commit() }
                mode = Mode.GESTURE
                gSpan = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); gX = (e.getX(0) + e.getX(1)) / 2; gY = (e.getY(0) + e.getY(1)) / 2
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> { moveDraw(e); invalidate() }
                Mode.SLIDER -> { setSlider(e.x); invalidate() }
                Mode.GESTURE -> if (e.pointerCount >= 2) {
                    val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); val fx = (e.getX(0) + e.getX(1)) / 2; val fy = (e.getY(0) + e.getY(1)) / 2
                    var f = if (gSpan > 10f) span / gSpan else 1f
                    val now = scaleNow()
                    f = f.coerceIn(fit * 0.5f / now, fit * 16f / now)
                    view.postScale(f, f, fx, fy); view.postTranslate(fx - gX, fy - gY)
                    gSpan = span; gX = fx; gY = fy
                    invalidate()
                }
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.GESTURE && e.pointerCount == 2) {
                val keep = 1 - e.actionIndex
                gX = e.getX(keep); gY = e.getY(keep)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    Mode.DRAW -> { if (e.actionMasked == MotionEvent.ACTION_UP) endDraw(e) else liveN = 0 }
                    Mode.UI -> {
                        val t = uiTarget
                        if (t != null && t.contains(e.x, e.y)) {
                            val long = SystemClock.uptimeMillis() - downT > 450
                            press(t, long)
                        }
                    }
                    else -> {}
                }
                mode = Mode.NONE; uiTarget = null; dragBar = null
                invalidate()
            }
        }
        return true
    }

    private fun setSlider(x: Float) {
        val r = dragBar ?: return
        val v = ((x - r.left) / r.width()).coerceIn(0f, 1f)
        when (r) {
            sizeBar -> toolSize[tool] = sizeFrom(v)
            opBar -> toolOpacity[tool] = v.coerceAtLeast(0.05f)
            else -> { smooth = v }
        }
    }

    private fun startDraw(e: MotionEvent) {
        val q = toPic(e.x, e.y)
        if (!layers[active].visible) { toast("This layer is hidden: show it in Layers to draw on it") }
        if (tool == PICK) { pick(q[0], q[1]); return }
        liveN = 0
        lastX = q[0]; lastY = q[1]; lastT = e.eventTime; lastPr = pressure(e, 0f)
        liveAdd(q[0], q[1], lastPr)
        if (tool == LINE || tool == BOX || tool == OVAL) liveAdd(q[0], q[1], 1f)
    }

    private fun pressure(e: MotionEvent, speed: Float): Float {
        return if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) e.pressure.coerceIn(0.05f, 1f)
        else (1f - speed * 0.35f).coerceIn(0.35f, 1f)   // a finger: quicker strokes come out thinner, like ink
    }

    private fun moveDraw(e: MotionEvent) {
        if (tool == PICK) { val q = toPic(e.x, e.y); pick(q[0], q[1]); return }
        if (liveN == 0) return
        if (tool == LINE || tool == BOX || tool == OVAL) {
            val q = toPic(e.x, e.y); live[3] = q[0]; live[4] = q[1]; return
        }
        val k = 1f - smooth * 0.85f
        fun add(x: Float, y: Float, t: Long) {
            val q = toPic(x, y)
            val nx = lastX + (q[0] - lastX) * k; val ny = lastY + (q[1] - lastY) * k
            val dist = hypot(nx - lastX, ny - lastY)
            if (dist < 0.8f) return
            val dt = (t - lastT).coerceAtLeast(1)
            val speed = dist * scaleNow() / dt          // screen pixels per ms
            val pr = lastPr + (pressure(e, speed) - lastPr) * 0.3f
            liveAdd(nx, ny, pr)
            lastX = nx; lastY = ny; lastT = t; lastPr = pr
        }
        for (h in 0 until e.historySize) add(e.getHistoricalX(0, h), e.getHistoricalY(0, h), e.getHistoricalEventTime(h))
        add(e.x, e.y, e.eventTime)
    }

    private fun endDraw(e: MotionEvent) {
        if (tool == PICK || liveN == 0) return
        if (tool != LINE && tool != BOX && tool != OVAL) { val q = toPic(e.x, e.y); if (hypot(q[0] - lastX, q[1] - lastY) > 0.8f) liveAdd(q[0], q[1], lastPr) }
        commit()
    }

    /** The eyedropper: the colour of the picture under the finger (all visible layers over white). */
    private fun pick(x: Float, y: Float) {
        val px = x.toInt(); val py = y.toInt()
        if (px !in 0 until cw || py !in 0 until ch) return
        var r = 255f; var gg = 255f; var b = 255f
        for (l in layers) {
            if (!l.visible) continue
            val c = l.bmp.getPixel(px, py)
            val al = Color.alpha(c) / 255f * l.opacity
            r = r * (1 - al) + Color.red(c) * al; gg = gg * (1 - al) + Color.green(c) * al; b = b * (1 - al) + Color.blue(c) * al
        }
        color = Color.rgb(r.toInt(), gg.toInt(), b.toInt())
        invalidate()
    }

    private fun press(t: RectF, long: Boolean) {
        when (t) {
            bDone -> finish()
            bUndo -> history.removeLastOrNull()?.let { it.undo(); redos += it }
            bRedo -> redos.removeLastOrNull()?.let { it.redo(); history += it }
            bZoom -> if (long) fitView() else zoomStep()
            bLayers -> layersMenu()
            bShare -> sharePng()
            bColor -> colorPicker()
            else -> {
                val ti = toolRects.indexOf(t)
                if (ti >= 0) { tool = ti; return }
                val si = swatchRects.indexOf(t)
                if (si >= 0) (recent + SWATCHES.toList()).distinct().getOrNull(si)?.let { color = it; if (tool == ERASER || tool == PICK) tool = PEN }
            }
        }
    }

    private fun zoomStep() {
        val z = scaleNow() / fit
        val next = listOf(2f, 4f, 8f, 16f).firstOrNull { it > z * 1.05f }
        if (next == null) { fitView(); return }
        val f = next * fit / scaleNow()
        view.postScale(f, f, board.centerX(), board.centerY())
    }

    // =====================================================================================
    // Layers, colour, saving
    // =====================================================================================

    private fun layersMenu() {
        val d = SnesDialog(a, "Layers (top first)")
        for (i in layers.indices.reversed()) {
            val l = layers[i]
            d.item("${l.name}${if (!l.visible) " (hidden)" else ""}${if (l.opacity < 1f) " · ${(l.opacity * 100).toInt()}%" else ""}", Snes.accent(i), i == active) { layerMenu(i) }
        }
        if (layers.size < MAX_LAYERS) d.item("+ New layer", Snes.GREEN) {
            val l = Layer("Layer ${layers.size + 1}"); val at = active + 1
            layers.add(at, l); active = at
            did(object : Act() {
                override fun undo() { layers.remove(l); active = active.coerceAtMost(layers.size - 1) }
                override fun redo() { layers.add(at.coerceAtMost(layers.size), l) }
            })
            invalidate()
        }
        d.key("Close", Snes.PURPLE, then = null).show()
    }

    private fun layerMenu(i: Int) {
        val l = layers[i]
        val d = SnesDialog(a, l.name)
        d.item("Draw on this layer", Snes.GREEN, i == active) { active = i; invalidate() }
        d.item(if (l.visible) "Hide" else "Show", Snes.YELLOW) { l.visible = !l.visible; invalidate() }
        d.item("Opacity: ${(l.opacity * 100).toInt()}%", Snes.PURPLE) {
            val steps = listOf(1f, 0.75f, 0.5f, 0.25f)
            SnesDialog.choose(a, "${l.name} opacity", steps.map { "${(it * 100).toInt()}%" }) { k -> l.opacity = steps[k]; invalidate() }
        }
        if (i < layers.size - 1) d.item("Move up", Snes.accent(1)) { swap(i, i + 1) }
        if (i > 0) d.item("Move down", Snes.accent(2)) { swap(i, i - 1) }
        d.item("Clear this layer", Snes.RED) {
            val old = ArrayList(l.ops)
            l.ops.clear(); l.rebuild()
            did(object : Act() {
                override fun undo() { l.ops.addAll(old); l.rebuild() }
                override fun redo() { l.ops.clear(); l.rebuild() }
            })
            invalidate()
        }
        if (layers.size > 1) d.item("Delete this layer", Snes.RED) {
            val at = layers.indexOf(l)
            layers.remove(l); active = active.coerceAtMost(layers.size - 1)
            did(object : Act() {
                override fun undo() { layers.add(at.coerceAtMost(layers.size), l) }
                override fun redo() { layers.remove(l); active = active.coerceAtMost(layers.size - 1) }
            })
            invalidate()
        }
        d.key("Back", Snes.PURPLE, then = null).show()
    }

    private fun swap(i: Int, j: Int) {
        java.util.Collections.swap(layers, i, j)
        if (active == i) active = j else if (active == j) active = i
        did(object : Act() {
            override fun undo() { java.util.Collections.swap(layers, i, j) }
            override fun redo() { java.util.Collections.swap(layers, i, j) }
        })
        invalidate()
    }

    private fun colorPicker() {
        val v = ColorPick(a, color) { color = it; if (tool == ERASER || tool == PICK) tool = PEN; invalidate() }
        SnesDialog(a, "Colour").view(LinearLayout(a).apply { addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Px.dp(220))) })
            .key("Done", Snes.GREEN, then = null).show()
    }

    /** The finished picture over white, at [scale] of full size. */
    private fun composite(scale: Float = 1f): Bitmap {
        val w = (cw * scale).toInt(); val h = (ch * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        val pp = Paint(Paint.FILTER_BITMAP_FLAG)
        layers.forEach { if (it.visible) { pp.alpha = (it.opacity * 255).toInt(); c.drawBitmap(it.bmp, 0f, 0f, pp) } }
        return out
    }

    private fun sharePng() {
        val bmp = composite()
        Thread {
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val uri = runCatching { app.foxbox.kit.Export.save("Sketches", "Sketch ${sketch.id}.png", "image/png", bytes) }.getOrNull()
            a.runOnUiThread {
                if (uri == null) return@runOnUiThread toast("Couldn't save the picture")
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Saved. Share the sketch?"))
            }
        }.start()
    }

    private var finished = false
    private fun finish() {
        if (finished) return
        finished = true
        val data = JSONObject().put("w", cw).put("h", ch).put("layers", JSONArray(layers.map { l ->
            JSONObject().put("name", l.name).put("visible", l.visible).put("opacity", l.opacity.toDouble()).put("ops", JSONArray(l.ops.map { s ->
                JSONObject().put("t", s.tool).put("c", s.color).put("s", s.size.toDouble()).put("o", s.opacity.toDouble())
                    .put("p", JSONArray().apply { s.pts.forEach { v -> put((v * 10).toInt() / 10.0) } })
            }))
        }))
        val pic = composite(0.5f)
        Notes.saveSketch(sketch.id, data, pic)
        layers.forEach { it.bmp.recycle() }
        done()
    }

    private fun load() {
        val o = Notes.sketchData(sketch.id) ?: return
        val arr = o.optJSONArray("layers") ?: return
        for (i in 0 until arr.length()) {
            val lo = arr.getJSONObject(i)
            val l = Layer(lo.optString("name", "Layer ${i + 1}"))
            l.visible = lo.optBoolean("visible", true); l.opacity = lo.optDouble("opacity", 1.0).toFloat()
            val ops = lo.optJSONArray("ops") ?: JSONArray()
            for (k in 0 until ops.length()) {
                val so = ops.getJSONObject(k)
                val pa = so.getJSONArray("p")
                l.ops += Stroke(so.getInt("t"), so.getInt("c"), so.getDouble("s").toFloat(), so.getDouble("o").toFloat(), FloatArray(pa.length()) { pa.getDouble(it).toFloat() })
            }
            l.rebuild()
            layers += l
        }
    }

    private fun toast(s: String) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show() }

    /** Back closes the editor, keeping the drawing. */
    fun close() = finish()
}

/** Hue strip and a saturation/brightness square, plus the palette as quick picks. */
@SuppressLint("ViewConstructor")
class ColorPick(c: android.content.Context, start: Int, private val changed: (Int) -> Unit) : View(c) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(start, it) }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sq = RectF(); private val hue = RectF(); private val prev = RectF()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val d = Px.dp(1f)
        sq.set(8 * d, 8 * d, w * 0.62f, h - 8 * d)
        hue.set(sq.right + 12 * d, 8 * d, sq.right + 40 * d, h - 8 * d)
        prev.set(hue.right + 12 * d, 8 * d, w - 8 * d, h * 0.5f)
    }
    override fun onDraw(c: Canvas) {
        p.shader = android.graphics.LinearGradient(sq.left, 0f, sq.right, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(sq, p)
        p.shader = android.graphics.LinearGradient(0f, sq.top, 0f, sq.bottom, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(sq, p)
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        p.shader = android.graphics.LinearGradient(0f, hue.top, 0f, hue.bottom, hues, null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(hue, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = Px.dp(2f); p.color = Color.WHITE
        c.drawCircle(sq.left + hsv[1] * sq.width(), sq.top + (1 - hsv[2]) * sq.height(), Px.dp(8f), p)
        val hy = hue.top + hsv[0] / 360f * hue.height()
        c.drawRect(hue.left - Px.dp(2f), hy - Px.dp(3f), hue.right + Px.dp(2f), hy + Px.dp(3f), p)
        p.style = Paint.Style.FILL; p.color = Color.HSVToColor(hsv); c.drawRect(prev, p)
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when {
            e.x <= sq.right + Px.dp(6f) -> { hsv[1] = ((e.x - sq.left) / sq.width()).coerceIn(0f, 1f); hsv[2] = 1f - ((e.y - sq.top) / sq.height()).coerceIn(0f, 1f) }
            e.x <= hue.right + Px.dp(6f) -> hsv[0] = (((e.y - hue.top) / hue.height()) * 360f).coerceIn(0f, 359.9f)
        }
        changed(Color.HSVToColor(hsv)); invalidate()
        return true
    }
}
