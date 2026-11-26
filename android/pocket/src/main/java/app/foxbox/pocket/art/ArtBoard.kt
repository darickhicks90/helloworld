package app.foxbox.pocket.art

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import app.foxbox.notes.ColorPick
import app.foxbox.ui.Px
import app.foxbox.ui.Snes
import app.foxbox.ui.SnesDialog
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 5IY Pocket's drawing board: brushes, fill and shapes; pictures brought in as layers (placed,
 * sized and cropped to a shape); a lasso and a magic lasso to pick out parts of pictures and
 * drawings and remove them, keep only them, or lift them onto their own layer; any canvas size and
 * shape; frame-by-frame animation with onion skins, playback and export (MP4, PNG sequence, sprite
 * sheet); and a 4K asset mode (transparent, shown at true size in a 4K frame).
 */
@SuppressLint("ViewConstructor")
class ArtBoard(private val a: Activity, val doc: ArtDoc, private val host: Host) : View(a) {
    interface Host {
        /** Let the person choose a picture; `then` gets it (already sized to fit memory). */
        fun pickImage(then: (Bitmap) -> Unit)
        /** The board is finished (it has saved). */
        fun closed()
    }

    private val d = Px.dp(1f)
    private var frame = 0
    private var active = 0

    // ---- pixels for the frame on screen: one bitmap per layer (rebuilt from its steps)
    private val pix = HashMap<Layer, Bitmap>()
    private fun bmpOf(l: Layer): Bitmap = pix.getOrPut(l) { Bitmap.createBitmap(doc.w, doc.h, Bitmap.Config.ARGB_8888).also { doc.replay(l.cel(frame), it) } }
    private fun rebuild(l: Layer) { doc.replay(l.cel(frame), bmpOf(l)) ; thumbs.remove(frame) }
    private fun rebuildAll() { for (l in doc.layers) rebuild(l); onionDirty = true }
    private fun dropPixels() { pix.values.forEach { it.recycle() }; pix.clear() }

    // ---- undo
    private abstract class Act { abstract fun undo(); abstract fun redo() }
    private val history = ArrayList<Act>(); private val redos = ArrayList<Act>()
    private fun did(x: Act) { history += x; redos.clear(); if (history.size > 150) history.removeAt(0); dirty = true }
    private var dirty = false

    private fun addOp(l: Layer, op: Op) {
        val f = frame; val cel = l.cel(f)
        cel += op
        if (f == frame) { val b = bmpOf(l); val c = Canvas(b); c.translate(doc.ox, doc.oy); doc.apply(c, b, op); thumbs.remove(f) }
        did(object : Act() {
            override fun undo() { l.cel(f).remove(op); if (f == frame) rebuild(l) else thumbs.remove(f) }
            override fun redo() { l.cel(f) += op; if (f == frame) rebuild(l) else thumbs.remove(f) }
        })
    }

    // ---- tools
    private var tool = Tool.PEN
    private var color = 0xFF1B1B1F.toInt()
    private val toolSize = floatArrayOf(10f, 6f, 36f, 80f, 40f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f)
    private val toolOpacity = floatArrayOf(1f, 0.85f, 0.45f, 0.35f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
    private var smooth = 0.4f
    private var fillTol = 32
    private var magicTol = 36
    private val sizeMax get() = max(200f, max(doc.w, doc.h) / 10f)
    private val recent = ArrayList<Int>()
    private val SWATCHES = intArrayOf(
        0xFF1B1B1F.toInt(), 0xFF4A4A52.toInt(), 0xFF9A9AA4.toInt(), 0xFFFFFFFF.toInt(), 0xFF23306E.toInt(), 0xFF3F7FD0.toInt(),
        0xFF3FB8AF.toInt(), 0xFF22706B.toInt(), 0xFF6FA84A.toInt(), 0xFF2E5240.toInt(), 0xFFF6C26B.toInt(), 0xFFE8B828.toInt(),
        0xFFE8742C.toInt(), 0xFFB8521B.toInt(), 0xFFD9786A.toInt(), 0xFFC0392B.toInt(), 0xFF7A2630.toInt(), 0xFF6448B5.toInt(),
    )

    // ---- what's in progress
    private var live = FloatArray(512); private var liveN = 0
    private var lastX = 0f; private var lastY = 0f; private var lastT = 0L; private var lastPr = 1f
    private var sel: Selection? = null
    private var selLoop: FloatArray? = null
    private var busy: String? = null
    private var placing: Bitmap? = null
    private val placeRect = RectF()
    private var moveDx = 0f; private var moveDy = 0f
    private var onion = true
    private var onionDirty = true
    private var onionPrev: Bitmap? = null; private var onionNext: Bitmap? = null
    private var timeline = doc.frames > 1 || doc.kind == ArtDoc.KIND_4K_FRAME
    private var guides = doc.is4k
    private var playing = false
    private var playFrames: List<Bitmap> = emptyList()
    private var playAt = 0
    private val main = Handler(Looper.getMainLooper())

    // ---- view
    private val view = Matrix(); private val inv = Matrix()
    private var fit = 1f
    private val board = RectF()

    // ---- layout
    private val topBar = RectF(); private val rail = RectF(); private val panel = RectF(); private val tl = RectF(); private val ctx = RectF()
    private val bDone = RectF(); private val bUndo = RectF(); private val bRedo = RectF(); private val bZoom = RectF(); private val bLayers = RectF(); private val bMore = RectF()
    private val toolRects = Array(Tool.NAMES.size) { RectF() }
    private val bColor = RectF(); private val sizeBar = RectF(); private val opBar = RectF(); private val smoothBar = RectF()
    private val swatchRects = Array(12) { RectF() }
    private val tPrev = RectF(); private val tPlay = RectF(); private val tNext = RectF(); private val tAdd = RectF(); private val tOnion = RectF(); private val tFps = RectF(); private val tStrip = RectF()
    private val ctxKeys = Array(7) { RectF() }
    private var ctxLabels: List<String> = emptyList()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Px.body; color = Snes.INK; textAlign = Paint.Align.CENTER }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) }
    private val checker: Paint by lazy {
        val t = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val c = Canvas(t); c.drawColor(0xFFFFFFFF.toInt()); val q = Paint().apply { color = 0xFFD8D8DE.toInt() }
        c.drawRect(0f, 0f, 8f, 8f, q); c.drawRect(8f, 8f, 16f, 16f, q)
        Paint().apply { shader = BitmapShader(t, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }
    private val tint = Paint().apply { colorFilter = PorterDuffColorFilter(0x664FC3F7, PorterDuff.Mode.SRC_IN) }
    private val onionPrevP = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(0xFFD84040.toInt(), PorterDuff.Mode.SRC_IN); alpha = 70 }
    private val onionNextP = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(0xFF3FA060.toInt(), PorterDuff.Mode.SRC_IN); alpha = 55 }

    init {
        if (doc.layers.isEmpty()) doc.layers += Layer("Layer 1", true)
        isFocusable = true
    }

    // =====================================================================================
    // Layout
    // =====================================================================================

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = arrange(w.toFloat(), h.toFloat())

    private fun arrange(w: Float, h: Float) {
        topBar.set(0f, 0f, w, 50 * d)
        val tlH = if (timeline) 62 * d else 0f
        if (w > h) {
            rail.set(0f, topBar.bottom, 112 * d, h)
            panel.set(w - 170 * d, topBar.bottom, w, h)
            board.set(rail.right, topBar.bottom, panel.left, h - tlH)
            tl.set(rail.right, h - tlH, panel.left, h)
            val rows = (Tool.NAMES.size + 1) / 2
            val th = (rail.height() - 8 * d) / rows
            toolRects.forEachIndexed { i, r -> val col = i % 2; val row = i / 2; r.set(rail.left + 4 * d + col * 52 * d, rail.top + 4 * d + row * th, rail.left + 4 * d + col * 52 * d + 50 * d, rail.top + 4 * d + (row + 1) * th - 3 * d) }
            var y = panel.top + 10 * d
            bColor.set(panel.left + 10 * d, y, panel.right - 10 * d, y + 40 * d); y += 50 * d
            val sw = (panel.width() - 20 * d) / 6
            swatchRects.forEachIndexed { i, r -> val cx = panel.left + 10 * d + (i % 6) * sw; val cy = y + (i / 6) * (sw + 2 * d); r.set(cx + 2 * d, cy, cx + sw - 2 * d, cy + sw - 4 * d) }
            y += sw * 2 + 16 * d
            sizeBar.set(panel.left + 12 * d, y + 30 * d, panel.right - 12 * d, y + 60 * d); y += 92 * d
            opBar.set(panel.left + 12 * d, y + 18 * d, panel.right - 12 * d, y + 44 * d); y += 64 * d
            smoothBar.set(panel.left + 12 * d, y + 18 * d, panel.right - 12 * d, y + 44 * d)
        } else {
            val toolH = 96 * d; val ctrlH = 140 * d
            rail.set(0f, h - toolH, w, h)
            panel.set(0f, rail.top - ctrlH, w, rail.top)
            tl.set(0f, panel.top - tlH, w, panel.top)
            board.set(0f, topBar.bottom, w, tl.top)
            val per = (Tool.NAMES.size + 1) / 2
            val tw = w / per
            toolRects.forEachIndexed { i, r -> val row = i / per; val col = i % per; r.set(col * tw + 2 * d, rail.top + 4 * d + row * 46 * d, (col + 1) * tw - 2 * d, rail.top + 4 * d + row * 46 * d + 42 * d) }
            bColor.set(panel.left + 10 * d, panel.top + 10 * d, panel.left + 70 * d, panel.top + 70 * d)
            val sw = (w * 0.45f) / 6
            swatchRects.forEachIndexed { i, r -> val cx = panel.left + 80 * d + (i % 6) * sw; val cy = panel.top + 8 * d + (i / 6) * sw; r.set(cx + 2 * d, cy + 2 * d, cx + sw - 2 * d, cy + sw - 2 * d) }
            sizeBar.set(panel.left + 12 * d, panel.top + 100 * d, w * 0.5f - 8 * d, panel.top + 126 * d)
            opBar.set(w * 0.5f + 8 * d, panel.top + 100 * d, w - 12 * d, panel.top + 126 * d)
            smoothBar.set(panel.left + 90 * d + w * 0.45f, panel.top + 30 * d, w - 12 * d, panel.top + 56 * d)
        }
        // the timeline: ◀ ▶ ▷ | frames | + onion fps
        val k = tl.height() - 12 * d
        tPrev.set(tl.left + 6 * d, tl.top + 6 * d, tl.left + 6 * d + k, tl.bottom - 6 * d)
        tPlay.set(tPrev.right + 4 * d, tPrev.top, tPrev.right + 4 * d + k * 1.2f, tPrev.bottom)
        tNext.set(tPlay.right + 4 * d, tPrev.top, tPlay.right + 4 * d + k, tPrev.bottom)
        tFps.set(tl.right - 6 * d - k * 1.4f, tPrev.top, tl.right - 6 * d, tPrev.bottom)
        tOnion.set(tFps.left - 4 * d - k * 1.4f, tPrev.top, tFps.left - 4 * d, tPrev.bottom)
        tAdd.set(tOnion.left - 4 * d - k, tPrev.top, tOnion.left - 4 * d, tPrev.bottom)
        tStrip.set(tNext.right + 8 * d, tPrev.top, tAdd.left - 8 * d, tPrev.bottom)
        // the top bar (the right corner is left for the radio and notebook keys)
        val bw = min(88 * d, (w - app.foxbox.ui.TopKeys.WIDTH) / 7.4f)
        bDone.set(6 * d, 6 * d, 6 * d + bw, topBar.bottom - 6 * d)
        bUndo.set(bDone.right + 6 * d, bDone.top, bDone.right + 6 * d + bw * 0.8f, bDone.bottom)
        bRedo.set(bUndo.right + 4 * d, bDone.top, bUndo.right + 4 * d + bw * 0.8f, bDone.bottom)
        val right = w - app.foxbox.ui.TopKeys.WIDTH - 4 * d
        bMore.set(right - bw * 0.8f, bDone.top, right, bDone.bottom)
        bLayers.set(bMore.left - 6 * d - bw, bDone.top, bMore.left - 6 * d, bDone.bottom)
        bZoom.set(bLayers.left - 6 * d - bw * 0.7f, bDone.top, bLayers.left - 6 * d, bDone.bottom)
        // the context bar (selection / placing) along the top of the board
        ctx.set(board.left + 8 * d, board.top + 6 * d, board.right - 8 * d, board.top + 46 * d)
        fitView()
    }

    private fun fitView() {
        if (board.width() <= 0) return
        fit = min((board.width() - 24 * d) / doc.w, (board.height() - 24 * d) / doc.h)
        view.reset(); view.postScale(fit, fit)
        view.postTranslate(board.centerX() - doc.w * fit / 2, board.centerY() - doc.h * fit / 2)
    }
    private fun scaleNow(): Float { val v = FloatArray(9); view.getValues(v); return v[Matrix.MSCALE_X] }

    // =====================================================================================
    // Drawing the screen
    // =====================================================================================

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF4A4650.toInt())
        c.save(); c.clipRect(board)
        if (playing && playFrames.isNotEmpty()) drawPlayback(c) else drawCanvas(c)
        c.restore()
        drawUi(c)
        if (guides && doc.kind == ArtDoc.KIND_4K_ASSET && !playing) drawUhdInset(c)
        busy?.let { b ->
            p.color = 0xCC000000.toInt(); c.drawRoundRect(board.centerX() - 120 * d, board.centerY() - 26 * d, board.centerX() + 120 * d, board.centerY() + 26 * d, 10 * d, 10 * d, p)
            txt.color = Color.WHITE; txt.textSize = 14 * d; c.drawText(b, board.centerX(), board.centerY() + 5 * d, txt)
        }
    }

    private fun drawCanvas(c: Canvas) {
        c.save(); c.concat(view)
        val k = scaleNow()
        p.color = 0x55000000; c.drawRect(6f / k, 8f / k, doc.w + 6f / k, doc.h + 8f / k, p)
        // the paper (or a checkerboard where it's see-through)
        if (doc.bg == 0) { c.save(); c.scale(1f / k, 1f / k); c.drawRect(0f, 0f, doc.w * k, doc.h * k, checker); c.restore() }
        else { p.color = doc.bg; c.drawRect(0f, 0f, doc.w.toFloat(), doc.h.toFloat(), p) }
        bmpP.isFilterBitmap = k < 3f
        // onion skins: the frame before (red) and after (green)
        if (timeline && onion && doc.frames > 1) {
            if (onionDirty) buildOnion()
            onionPrev?.let { c.drawBitmap(it, null, RectF(0f, 0f, doc.w.toFloat(), doc.h.toFloat()), onionPrevP) }
            onionNext?.let { c.drawBitmap(it, null, RectF(0f, 0f, doc.w.toFloat(), doc.h.toFloat()), onionNextP) }
        }
        for ((i, l) in doc.layers.withIndex()) {
            if (!l.visible) continue
            val b = bmpOf(l)
            val x = xf
            if (x != null && x.layer === l) { drawXfLayer(c, l, b, x); continue }
            if (i == active && liveN > 0 && (Tool.isBrush(tool) || Tool.isShape(tool))) {
                c.saveLayerAlpha(0f, 0f, doc.w.toFloat(), doc.h.toFloat(), (l.opacity * 255).toInt())
                c.drawBitmap(b, 0f, 0f, bmpP)
                c.save(); c.translate(doc.ox, doc.oy); doc.stroke(c, liveStroke()); c.restore()
                c.restore()
            } else { bmpP.alpha = (l.opacity * 255).toInt(); c.drawBitmap(b, 0f, 0f, bmpP); bmpP.alpha = 255 }
        }
        // a picture being placed
        placing?.let { pb -> c.save(); c.translate(doc.ox, doc.oy); c.drawBitmap(pb, null, placeRect, bmpP)
            dash.color = 0xFFFFC107.toInt(); dash.strokeWidth = 2.5f / k; c.drawRect(placeRect, dash); c.restore() }
        // the selection: tinted, with a dashed box
        sel?.let { s -> c.save(); c.translate(doc.ox, doc.oy); c.drawBitmap(s.mask, s.l.toFloat(), s.t.toFloat(), tint)
            dash.color = 0xFF4FC3F7.toInt(); dash.strokeWidth = 2f / k; c.drawRect(s.r.toRectF(), dash); c.restore() }
        // the loop being drawn
        if (liveN > 2 && (tool == Tool.LASSO || tool == Tool.MAGIC)) {
            dash.color = if (tool == Tool.MAGIC) 0xFFFFC107.toInt() else 0xFF4FC3F7.toInt(); dash.strokeWidth = 2.5f / k
            val path = android.graphics.Path(); path.moveTo(live[0] + doc.ox, live[1] + doc.oy)
            for (i in 3 until liveN step 3) path.lineTo(live[i] + doc.ox, live[i + 1] + doc.oy)
            c.drawPath(path, dash)
        }
        // 4K frame guides: title-safe (90%) and action-safe (93%)
        if (guides && doc.kind == ArtDoc.KIND_4K_FRAME) {
            dash.color = 0x88FFFFFF.toInt(); dash.strokeWidth = 2f / k
            for (f in floatArrayOf(0.93f, 0.9f)) { val mx = doc.w * (1 - f) / 2; val my = doc.h * (1 - f) / 2; c.drawRect(mx, my, doc.w - mx, doc.h - my, dash) }
        }
        c.restore()
        // the size, in pixels, under the board's top edge
        txt.color = 0xCCFFFFFF.toInt(); txt.textSize = 11 * d; txt.textAlign = Paint.Align.LEFT
        val label = "${doc.w} × ${doc.h} px" + (if (doc.kind == ArtDoc.KIND_4K_ASSET) " · 4K asset" else if (doc.kind == ArtDoc.KIND_4K_FRAME) " · 4K" else "") +
            (if (doc.frames > 1) " · frame ${frame + 1}/${doc.frames}" else "")
        c.drawText(label, board.left + 10 * d, board.bottom - 8 * d, txt)
        txt.textAlign = Paint.Align.CENTER
        drawContextBar(c)
        xf?.let { drawXfHandles(c, it) }
    }

    private fun android.graphics.Rect.toRectF() = RectF(this)

    /** An asset, at its true size inside a 4K frame (bottom right of the board). */
    private fun drawUhdInset(c: Canvas) {
        val iw = min(200 * d, board.width() * 0.32f); val ih = iw * ArtDoc.UHD_H / ArtDoc.UHD_W
        val r = RectF(board.right - iw - 10 * d, board.bottom - ih - 26 * d, board.right - 10 * d, board.bottom - 26 * d)
        p.color = 0xFF000000.toInt(); c.drawRect(r.left - 2 * d, r.top - 2 * d, r.right + 2 * d, r.bottom + 2 * d, p)
        p.color = 0xFF2A2A36.toInt(); c.drawRect(r, p)
        val s = iw / ArtDoc.UHD_W
        val aw = doc.w * s; val ah = doc.h * s
        val ar = RectF(r.centerX() - aw / 2, r.centerY() - ah / 2, r.centerX() + aw / 2, r.centerY() + ah / 2)
        c.save(); c.clipRect(r)
        for (l in doc.layers) if (l.visible) { bmpP.alpha = (l.opacity * 255).toInt(); c.drawBitmap(bmpOf(l), null, ar, bmpP) }
        bmpP.alpha = 255; c.restore()
        txt.color = 0xDDFFFFFF.toInt(); txt.textSize = 10 * d
        c.drawText("in a 4K frame: ${(doc.w * 100f / ArtDoc.UHD_W).roundToInt()}% wide", r.centerX(), r.bottom + 14 * d, txt)
    }

    private fun drawPlayback(c: Canvas) {
        val b = playFrames[playAt % playFrames.size]
        val s = min((board.width() - 24 * d) / b.width, (board.height() - 24 * d) / b.height)
        val w = b.width * s; val h = b.height * s
        val r = RectF(board.centerX() - w / 2, board.centerY() - h / 2, board.centerX() + w / 2, board.centerY() + h / 2)
        if (doc.bg == 0) c.drawRect(r, checker)
        c.drawBitmap(b, null, r, bmpP)
    }

    private fun key(c: Canvas, r: RectF, label: String, lit: Boolean = false, lamp: Int = Snes.PURPLE) {
        if (r.width() <= 0) return
        p.color = Snes.INK; c.drawRoundRect(r, 6 * d, 6 * d, p)
        p.color = if (lit) lamp else Snes.FACE; c.drawRoundRect(r.left + 2 * d, r.top + 2 * d, r.right - 2 * d, r.bottom - 3 * d, 5 * d, 5 * d, p)
        p.color = 0x40FFFFFF; c.drawRect(r.left + 5 * d, r.top + 3 * d, r.right - 5 * d, r.top + 5 * d, p)
        txt.color = if (lit) Color.WHITE else Snes.INK
        txt.textSize = min(r.height() * 0.42f, 15 * d)
        while (txt.measureText(label) > r.width() - 8 * d && txt.textSize > 6 * d) txt.textSize -= d
        c.drawText(label, r.centerX(), r.centerY() + txt.textSize * 0.35f, txt)
    }

    private fun drawUi(c: Canvas) {
        p.color = Snes.SHADE; c.drawRect(topBar, p); p.color = Snes.FACE; c.drawRect(topBar.left, topBar.top, topBar.right, topBar.bottom - 2 * d, p)
        p.color = Snes.SHADE; c.drawRect(rail, p); c.drawRect(panel, p)
        p.color = Snes.FACE; c.drawRect(rail.left + 2 * d, rail.top + 2 * d, rail.right - 2 * d, rail.bottom - 2 * d, p); c.drawRect(panel.left + 2 * d, panel.top + 2 * d, panel.right - 2 * d, panel.bottom - 2 * d, p)
        key(c, bDone, "Done", true, Snes.GREEN)
        key(c, bUndo, "Undo", history.isNotEmpty(), Snes.YELLOW)
        key(c, bRedo, "Redo", redos.isNotEmpty(), Snes.YELLOW)
        val z = scaleNow() / fit
        key(c, bZoom, if (abs(z - 1f) < 0.05f) "Fit" else "${if (z >= 10) z.toInt().toString() else "%.1f".format(z)}×")
        key(c, bLayers, "Layer ${active + 1}/${doc.layers.size}", true, Snes.PURPLE)
        key(c, bMore, "☰", false)
        toolRects.forEachIndexed { i, r -> key(c, r, Tool.NAMES[i], i == tool, when (i) { Tool.ERASER -> Snes.RED; Tool.LASSO, Tool.MAGIC -> 0xFF2E86C1.toInt(); else -> Snes.PURPLE }) }
        // colour
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
        // the three sliders change with the tool: size / opacity / smoothing, or tolerance
        txt.color = Snes.INK; txt.textAlign = Paint.Align.LEFT; txt.textSize = 12 * d
        when (tool) {
            Tool.FILL -> { c.drawText("Fill reaches: ${tolName(fillTol)}", sizeBar.left, sizeBar.top - 8 * d, txt); slider(c, sizeBar, fillTol / 120f) }
            Tool.MAGIC -> { c.drawText("Magic: ${tolName(magicTol)}", sizeBar.left, sizeBar.top - 8 * d, txt); slider(c, sizeBar, magicTol / 120f) }
            Tool.LASSO, Tool.MOVE, Tool.PICK -> { c.drawText(when (tool) { Tool.LASSO -> "Draw round what to select"; Tool.MOVE -> "Drag to move · corners resize · ⟳ knob turns"; else -> "Tap to pick a colour" }, sizeBar.left, sizeBar.top - 8 * d, txt) }
            else -> {
                val size = toolSize[tool]
                c.drawText("Size: ${size.toInt()} px", sizeBar.left, sizeBar.top - 8 * d, txt)
                slider(c, sizeBar, sizeNorm(size))
                txt.textSize = 11 * d
                c.drawText("Opacity ${(toolOpacity[tool] * 100).toInt()}%", opBar.left, opBar.top - 6 * d, txt)
                slider(c, opBar, toolOpacity[tool])
                c.drawText("Smoothing ${(smooth * 100).toInt()}%", smoothBar.left, smoothBar.top - 6 * d, txt)
                slider(c, smoothBar, smooth)
            }
        }
        txt.textAlign = Paint.Align.CENTER
        if (timeline) drawTimeline(c)
    }

    private fun tolName(t: Int) = when { t < 16 -> "exact"; t < 40 -> "close colours"; t < 70 -> "similar colours"; else -> "loose" }

    private fun slider(c: Canvas, r: RectF, v: Float) {
        p.color = Snes.INK; c.drawRoundRect(r.left, r.centerY() - 4 * d, r.right, r.centerY() + 4 * d, 4 * d, 4 * d, p)
        p.color = Snes.PURPLE; c.drawRoundRect(r.left + d, r.centerY() - 3 * d, r.left + d + (r.width() - 2 * d) * v.coerceIn(0f, 1f), r.centerY() + 3 * d, 3 * d, 3 * d, p)
        val x = r.left + r.width() * v.coerceIn(0f, 1f)
        p.color = Snes.INK; c.drawCircle(x, r.centerY(), 10 * d, p); p.color = Snes.HI; c.drawCircle(x, r.centerY(), 8 * d, p)
    }
    private fun sizeNorm(s: Float) = (ln(s) / ln(sizeMax)).coerceIn(0f, 1f)
    private fun sizeFrom(v: Float) = exp(v.coerceIn(0f, 1f) * ln(sizeMax)).coerceIn(1f, sizeMax)
    private fun lum(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    // ---- the timeline
    private val thumbs = HashMap<Int, Bitmap>()
    private val thumbRects = ArrayList<Pair<RectF, Int>>()
    private fun thumb(f: Int): Bitmap = thumbs.getOrPut(f) { Exporter.frame(doc, f, 72 * d / max(doc.w, doc.h), transparent = false) }

    private fun drawTimeline(c: Canvas) {
        p.color = 0xFF2B2830.toInt(); c.drawRect(tl, p)
        key(c, tPrev, "◀"); key(c, tPlay, if (playing) "■" else "▶", playing, Snes.GREEN); key(c, tNext, "▶|")
        key(c, tAdd, "+ frame"); key(c, tOnion, "Onion", onion, 0xFFD84040.toInt()); key(c, tFps, "${doc.fps} fps")
        thumbRects.clear()
        val th = tStrip.height(); val tw = th * doc.w / doc.h.toFloat()
        val per = max(1, ((tStrip.width() + 4 * d) / (tw + 4 * d)).toInt())
        val first = (frame - per / 2).coerceIn(0, max(0, doc.frames - per))
        c.save(); c.clipRect(tStrip)
        for (k in 0 until min(per, doc.frames - first)) {
            val f = first + k
            val r = RectF(tStrip.left + k * (tw + 4 * d), tStrip.top, tStrip.left + k * (tw + 4 * d) + tw, tStrip.bottom)
            c.drawBitmap(thumb(f), null, r, bmpP)
            p.style = Paint.Style.STROKE; p.strokeWidth = if (f == frame) 3 * d else 1 * d; p.color = if (f == frame) Snes.YELLOW else 0xFF6E6C78.toInt(); c.drawRect(r, p); p.style = Paint.Style.FILL
            txt.textSize = 9 * d; txt.color = Snes.INK; c.drawText("${f + 1}", r.left + 8 * d, r.bottom - 4 * d, txt)
            thumbRects += r to f
        }
        c.restore()
    }

    // ---- the context bar: selection or placing actions
    private fun drawContextBar(c: Canvas) {
        ctxLabels = when {
            xf != null -> listOf("Apply", "Reset", "", "", "", "", "Cancel")
            placing != null -> listOf("Place", "Size…", "Shape…", "Fit", "Fill", "Cancel")
            sel != null -> listOf("Remove", "Keep only", "To new layer", "Turn / size", if (selLoop != null) "Tighter" else "", if (selLoop != null) "Looser" else "", "Cancel")
            else -> emptyList()
        }
        if (ctxLabels.isEmpty()) { ctxKeys.forEach { it.setEmpty() }; return }
        val shown = ctxLabels.indices.filter { ctxLabels[it].isNotEmpty() }
        val bw = (ctx.width() - 6 * d * (shown.size - 1)) / shown.size
        ctxKeys.forEach { it.setEmpty() }
        shown.forEachIndexed { k, i -> ctxKeys[i].set(ctx.left + k * (bw + 6 * d), ctx.top, ctx.left + k * (bw + 6 * d) + bw, ctx.bottom) }
        for (i in shown) key(c, ctxKeys[i], ctxLabels[i], i == 0, if (placing != null) Snes.GREEN else Snes.RED)
    }

    // =====================================================================================
    // Touch
    // =====================================================================================

    private enum class Mode { NONE, DRAW, GESTURE, UI, SLIDER, PLACE, XF }
    private var mode = Mode.NONE
    private var dragBar: RectF? = null
    private var downT = 0L
    private var gSpan = 0f; private var gX = 0f; private var gY = 0f
    private var uiTarget: RectF? = null
    private val pt = FloatArray(2)
    /** Screen → art space. */
    private fun toArt(x: Float, y: Float): FloatArray { view.invert(inv); pt[0] = x; pt[1] = y; inv.mapPoints(pt); pt[0] -= doc.ox; pt[1] -= doc.oy; return pt }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (busy != null) return true
        if (playing && e.actionMasked == MotionEvent.ACTION_DOWN && board.contains(e.x, e.y)) { stopPlay(); return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x; val y = e.y
                downT = SystemClock.uptimeMillis()
                val ctxHit = ctxKeys.firstOrNull { !it.isEmpty && it.contains(x, y) }
                dragBar = if (ctxHit == null) listOf(sizeBar, opBar, smoothBar).firstOrNull { RectF(it.left - 12f, it.top - 16f, it.right + 12f, it.bottom + 16f).contains(x, y) } else null
                mode = when {
                    ctxHit != null -> { uiTarget = ctxHit; Mode.UI }
                    dragBar != null -> { setSlider(x); Mode.SLIDER }
                    board.contains(x, y) && xf != null -> { xfDown(x, y); Mode.XF }
                    board.contains(x, y) && tool == Tool.MOVE && placing == null && sel == null -> { startLayerXf(); if (xf != null) { xfDown(x, y); Mode.XF } else Mode.NONE }
                    board.contains(x, y) && placing != null -> { gX = x; gY = y; Mode.PLACE }
                    board.contains(x, y) -> { startDraw(e); Mode.DRAW }
                    else -> { uiTarget = (listOf(bDone, bUndo, bRedo, bZoom, bLayers, bMore, bColor, tPrev, tPlay, tNext, tAdd, tOnion, tFps) + toolRects + swatchRects + thumbRects.map { it.first }).firstOrNull { it.contains(x, y) }; Mode.UI }
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && mode == Mode.XF) { xfTwoStart(e) } else if (e.pointerCount == 2 && (mode == Mode.DRAW || mode == Mode.NONE || mode == Mode.PLACE)) {
                if (mode == Mode.DRAW) { liveN = 0; moveDx = 0f; moveDy = 0f }
                if (mode != Mode.PLACE) mode = Mode.GESTURE
                gSpan = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); gX = (e.getX(0) + e.getX(1)) / 2; gY = (e.getY(0) + e.getY(1)) / 2
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> { moveDraw(e); invalidate() }
                Mode.SLIDER -> { setSlider(e.x); invalidate() }
                Mode.PLACE -> { movePlace(e); invalidate() }
                Mode.XF -> { xfMove(e); invalidate() }
                Mode.GESTURE -> if (e.pointerCount >= 2) {
                    val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); val fx = (e.getX(0) + e.getX(1)) / 2; val fy = (e.getY(0) + e.getY(1)) / 2
                    var f = if (gSpan > 10f) span / gSpan else 1f
                    f = f.coerceIn(fit * 0.5f / scaleNow(), fit * 24f / scaleNow())
                    view.postScale(f, f, fx, fy); view.postTranslate(fx - gX, fy - gY)
                    gSpan = span; gX = fx; gY = fy
                    invalidate()
                }
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.XF && e.pointerCount == 2) { val keep = 1 - e.actionIndex; xfDrag = XF_MOVE; xfLast[0] = e.getX(keep); xfLast[1] = e.getY(keep) } else if ((mode == Mode.GESTURE || mode == Mode.PLACE) && e.pointerCount == 2) {
                val keep = 1 - e.actionIndex; gX = e.getX(keep); gY = e.getY(keep); gSpan = 0f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    Mode.DRAW -> if (e.actionMasked == MotionEvent.ACTION_UP) endDraw(e) else { liveN = 0; moveDx = 0f; moveDy = 0f }
                    Mode.UI -> uiTarget?.let { t -> if (t.contains(e.x, e.y)) press(t, SystemClock.uptimeMillis() - downT > 450) }
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
        when (tool) {
            Tool.FILL -> if (r === sizeBar) fillTol = (v * 120).toInt()
            Tool.MAGIC -> if (r === sizeBar) magicTol = (v * 120).toInt().coerceAtLeast(6)
            Tool.LASSO, Tool.MOVE, Tool.PICK -> {}
            else -> when (r) { sizeBar -> toolSize[tool] = sizeFrom(v); opBar -> toolOpacity[tool] = v.coerceAtLeast(0.05f); else -> smooth = v }
        }
    }

    private fun liveAdd(x: Float, y: Float, pr: Float) {
        if (liveN + 3 > live.size) live = live.copyOf(live.size * 2)
        live[liveN++] = x; live[liveN++] = y; live[liveN++] = pr
    }
    private fun liveStroke() = Op.Stroke(tool, color, toolSize[tool], toolOpacity[tool], live.copyOf(liveN))

    private fun startDraw(e: MotionEvent) {
        val q = toArt(e.x, e.y)
        val l = doc.layers[active]
        if (!l.visible && tool != Tool.PICK) { toast("This layer is hidden: show it in Layers to draw on it"); return }
        liveN = 0; moveDx = 0f; moveDy = 0f
        lastX = q[0]; lastY = q[1]; lastT = e.eventTime; lastPr = pressure(e, 0f)
        when (tool) {
            Tool.PICK -> pick(q[0], q[1])
            Tool.FILL -> {}
            Tool.MOVE -> {}
            else -> { liveAdd(q[0], q[1], lastPr); if (Tool.isShape(tool)) liveAdd(q[0], q[1], 1f) }
        }
        if (tool == Tool.LASSO || tool == Tool.MAGIC) sel = null
    }

    private fun pressure(e: MotionEvent, speed: Float) =
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) e.pressure.coerceIn(0.05f, 1f) else (1f - speed * 0.35f).coerceIn(0.35f, 1f)

    private fun moveDraw(e: MotionEvent) {
        val q0 = toArt(e.x, e.y)
        when (tool) {
            Tool.PICK -> { pick(q0[0], q0[1]); return }
            Tool.MOVE -> { moveDx = q0[0] - lastX; moveDy = q0[1] - lastY; return }
            Tool.FILL -> return
        }
        if (liveN == 0) return
        if (Tool.isShape(tool)) { live[3] = q0[0]; live[4] = q0[1]; return }
        val k = if (tool == Tool.LASSO || tool == Tool.MAGIC) 1f else 1f - smooth * 0.85f
        fun add(x: Float, y: Float, t: Long) {
            val q = toArt(x, y)
            val nx = lastX + (q[0] - lastX) * k; val ny = lastY + (q[1] - lastY) * k
            val dist = hypot(nx - lastX, ny - lastY)
            if (dist < 0.8f) return
            val speed = dist * scaleNow() / (t - lastT).coerceAtLeast(1)
            val pr = lastPr + (pressure(e, speed) - lastPr) * 0.3f
            liveAdd(nx, ny, pr); lastX = nx; lastY = ny; lastT = t; lastPr = pr
        }
        for (h in 0 until e.historySize) add(e.getHistoricalX(0, h), e.getHistoricalY(0, h), e.getHistoricalEventTime(h))
        add(e.x, e.y, e.eventTime)
    }

    private fun endDraw(e: MotionEvent) {
        val l = doc.layers[active]
        when (tool) {
            Tool.PICK -> return
            Tool.FILL -> { val q = toArt(e.x, e.y); addOp(l, Op.Fill(q[0], q[1], color, fillTol)); noteColor(); return }
            Tool.MOVE -> {
                if (abs(moveDx) > 0.5f || abs(moveDy) > 0.5f) addOp(l, Op.Shift(moveDx, moveDy))
                moveDx = 0f; moveDy = 0f; return
            }
            Tool.LASSO, Tool.MAGIC -> {
                val n = liveN / 3
                if (n < 4) { liveN = 0; return }
                val pts = FloatArray(n * 2) { i -> live[(i / 2) * 3 + (i % 2)] }
                liveN = 0
                makeSelection(pts)
                return
            }
        }
        if (liveN == 0) return
        if (!Tool.isShape(tool)) { val q = toArt(e.x, e.y); if (hypot(q[0] - lastX, q[1] - lastY) > 0.8f) liveAdd(q[0], q[1], lastPr) }
        val s = liveStroke(); liveN = 0
        addOp(l, s)
        if (tool != Tool.ERASER) noteColor()
    }

    private fun noteColor() { recent.remove(color); recent.add(0, color); if (recent.size > 6) recent.removeAt(recent.size - 1) }

    private fun makeSelection(pts: FloatArray) {
        selLoop = if (tool == Tool.MAGIC) pts else null
        if (tool == Tool.LASSO) { sel = Select.lasso(pts, pts.size, doc.w, doc.h, doc.ox, doc.oy); invalidate(); return }
        runMagic(pts, magicTol)
    }

    private fun runMagic(pts: FloatArray, tol: Int) {
        val layerBmp = bmpOf(doc.layers[active])
        busy = "Finding the object…"; invalidate()
        Thread {
            val s = runCatching { Select.magic(layerBmp, doc.bg, pts, pts.size, doc.ox, doc.oy, tol) }.getOrNull()
            main.post {
                busy = null
                if (s == null) toast("Nothing stands out inside that loop: try drawing it closer, or Looser")
                sel = s; invalidate()
            }
        }.start()
    }

    /** The eyedropper: the colour of the picture under the finger (visible layers over the paper). */
    private fun pick(x: Float, y: Float) {
        val px = (x + doc.ox).toInt(); val py = (y + doc.oy).toInt()
        if (px !in 0 until doc.w || py !in 0 until doc.h) return
        val base = if (doc.bg == 0) Color.WHITE else doc.bg
        var r = Color.red(base).toFloat(); var gg = Color.green(base).toFloat(); var b = Color.blue(base).toFloat()
        for (l in doc.layers) {
            if (!l.visible) continue
            val c = bmpOf(l).getPixel(px, py); val al = Color.alpha(c) / 255f * l.opacity
            r = r * (1 - al) + Color.red(c) * al; gg = gg * (1 - al) + Color.green(c) * al; b = b * (1 - al) + Color.blue(c) * al
        }
        color = Color.rgb(r.toInt(), gg.toInt(), b.toInt())
    }

    // ---- placing a picture
    fun beginPlace(b: Bitmap) {
        stopPlay(); sel = null
        placing = b
        fitPlace(contain = true)
        invalidate()
    }
    private fun fitPlace(contain: Boolean) {
        val b = placing ?: return
        val s = if (contain) min(doc.w / b.width.toFloat(), doc.h / b.height.toFloat()) else max(doc.w / b.width.toFloat(), doc.h / b.height.toFloat())
        val w = b.width * s; val h = b.height * s
        placeRect.set(doc.w / 2f - w / 2 - doc.ox, doc.h / 2f - h / 2 - doc.oy, doc.w / 2f + w / 2 - doc.ox, doc.h / 2f + h / 2 - doc.oy)
    }
    private fun movePlace(e: MotionEvent) {
        val k = scaleNow()
        if (e.pointerCount >= 2) {
            val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); val fx = (e.getX(0) + e.getX(1)) / 2; val fy = (e.getY(0) + e.getY(1)) / 2
            if (gSpan > 10f) {
                val f = span / gSpan
                val cx = placeRect.centerX() + (fx - gX) / k; val cy = placeRect.centerY() + (fy - gY) / k
                val w = (placeRect.width() * f).coerceIn(8f, ArtDoc.MAX_SIDE * 2f); val h = w * placeRect.height() / placeRect.width()
                placeRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            }
            gSpan = span; gX = fx; gY = fy
        } else {
            placeRect.offset((e.x - gX) / k, (e.y - gY) / k); gX = e.x; gY = e.y
        }
    }
    private fun placeSize() {
        SnesDialog.text(a, "Size in pixels (width × height)", "${placeRect.width().roundToInt()} × ${placeRect.height().roundToInt()}") { s ->
            val nums = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
            if (nums.isEmpty()) return@text
            val w = nums[0].coerceIn(4, ArtDoc.MAX_SIDE * 2).toFloat()
            // one number keeps the picture's shape; two set both
            val h = (nums.getOrNull(1)?.toFloat() ?: (w * placeRect.height() / placeRect.width())).coerceIn(4f, ArtDoc.MAX_SIDE * 2f)
            val cx = placeRect.centerX(); val cy = placeRect.centerY()
            placeRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2); invalidate()
        }
    }
    private fun placeShape() {
        val shapes = listOf("Original" to 0f, "Square 1:1" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "3:2" to 1.5f, "16:9" to 16f / 9, "9:16" to 9f / 16, "Same as the canvas" to doc.w / doc.h.toFloat())
        SnesDialog.choose(a, "Crop the picture to a shape", shapes.map { it.first }) { i ->
            val b = placing ?: return@choose
            val ratio = shapes[i].second
            if (ratio == 0f) { placeRect.set(placeRect.left, placeRect.top, placeRect.right, placeRect.top + placeRect.width() * b.height / b.width); invalidate(); return@choose }
            // a centred crop of the picture to the shape, keeping its width on the canvas
            val cur = b.width / b.height.toFloat()
            val (cw, ch) = if (cur > ratio) (b.height * ratio).toInt() to b.height else b.width to (b.width / ratio).toInt()
            val cropped = Bitmap.createBitmap(b, (b.width - cw) / 2, (b.height - ch) / 2, max(1, cw), max(1, ch))
            placing = cropped
            val w = placeRect.width(); val h = w / ratio
            val cx = placeRect.centerX(); val cy = placeRect.centerY()
            placeRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2); invalidate()
        }
    }
    private fun commitPlace() {
        val b = placing ?: return
        placing = null
        val id = ArtDoc.newId()
        doc.saveBitmap(id, b)
        val op = Op.Image(id, placeRect.left, placeRect.top, placeRect.width(), placeRect.height())
        if (doc.layers.size < doc.maxLayers) {
            // its own layer, above the current one, so it can be hidden, faded or cut out separately
            val l = Layer("Picture", false); val at = active + 1
            doc.layers.add(at, l); active = at; l.cel(0) += op; rebuild(l)
            did(object : Act() {
                override fun undo() { doc.layers.remove(l); pix.remove(l)?.recycle(); active = active.coerceAtMost(doc.layers.size - 1); thumbs.clear() }
                override fun redo() { doc.layers.add(at.coerceAtMost(doc.layers.size), l); rebuild(l) }
            })
        } else addOp(doc.layers[active], op)
        thumbs.clear(); invalidate()
    }

    // ---- turning and resizing (a whole layer, or what's selected)

    /** What's being turned and resized: a layer (piece == null) or a selected piece of it. */
    private class Xf(val layer: Layer, val piece: Bitmap?, val sel: Selection?, val base: RectF) {
        var tx = 0f; var ty = 0f; var s = 1f; var rot = 0f
    }
    private var xf: Xf? = null
    private var xfDrag = 0
    private val XF_MOVE = 1; private val XF_SCALE = 2; private val XF_ROTATE = 3; private val XF_TWO = 4
    private val xfLast = FloatArray(2)
    private var xfStartAngle = 0f; private var xfStartRot = 0f; private var xfStartDist = 1f; private var xfStartS = 1f
    private var xfSpan = 0f; private var xfAng = 0f; private var xfMidX = 0f; private var xfMidY = 0f

    /** Art space → art space for the current turn / size / move (about the middle of the box). */
    private fun xfMatrix(x: Xf): Matrix = Matrix().apply {
        val cx = x.base.centerX(); val cy = x.base.centerY()
        setTranslate(-cx, -cy); postScale(x.s, x.s); postRotate(x.rot); postTranslate(cx + x.tx, cy + x.ty)
    }
    /** Art point → screen point. */
    private fun artToScreen(pts: FloatArray) { for (i in pts.indices step 2) { pts[i] += doc.ox; pts[i + 1] += doc.oy }; view.mapPoints(pts) }

    private fun startLayerXf() {
        if (xf != null) return
        val l = doc.layers[active]
        if (!l.visible) { toast("This layer is hidden: show it in Layers first"); return }
        // the box hugs what's drawn on the layer
        val b = bmpOf(l); val w = b.width; val h = b.height
        val step = max(1, max(w, h) / 1024)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) { if (row[x] ushr 24 != 0) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }; x += step }
            y += step
        }
        if (maxX < 0) { toast("Nothing on this layer to turn or resize"); return }
        xf = Xf(l, null, null, RectF(minX - doc.ox, minY - doc.oy, maxX + step - doc.ox, maxY + step - doc.oy))
        invalidate()
    }

    private fun startSelXf() {
        val s = sel ?: return
        val l = doc.layers[active]
        val piece = Select.cut(bmpOf(l), s, doc.ox, doc.oy)
        xf = Xf(l, piece, s, RectF(s.l.toFloat(), s.t.toFloat(), (s.l + s.mask.width).toFloat(), (s.t + s.mask.height).toFloat()))
        sel = null; selLoop = null
        invalidate()
    }

    private fun drawXfLayer(c: Canvas, l: Layer, b: Bitmap, x: Xf) {
        val m = xfMatrix(x)
        bmpP.alpha = (l.opacity * 255).toInt()
        if (x.piece == null) {
            c.save(); c.translate(doc.ox, doc.oy); c.concat(m); c.translate(-doc.ox, -doc.oy); c.drawBitmap(b, 0f, 0f, bmpP); c.restore()
        } else {
            val s = x.sel!!
            // the layer with a hole where the piece came from, then the piece where it's going
            c.saveLayerAlpha(0f, 0f, doc.w.toFloat(), doc.h.toFloat(), bmpP.alpha)
            c.drawBitmap(b, 0f, 0f, null)
            c.drawBitmap(s.mask, s.l + doc.ox, s.t + doc.oy, holeP)
            c.restore()
            c.save(); c.translate(doc.ox, doc.oy); c.concat(m); c.drawBitmap(x.piece, s.l.toFloat(), s.t.toFloat(), bmpP); c.restore()
        }
        bmpP.alpha = 255
    }
    private val holeP = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    /** The box's corners, the middle of its top edge, its centre and the rotate knob, on screen. */
    private fun xfScreen(x: Xf): FloatArray {
        val b = x.base
        val pts = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom, b.centerX(), b.top, b.centerX(), b.centerY())
        xfMatrix(x).mapPoints(pts); artToScreen(pts)
        // the knob: out from the top edge, away from the middle
        var dx = pts[8] - pts[10]; var dy = pts[9] - pts[11]; val len = hypot(dx, dy).coerceAtLeast(1f)
        dx /= len; dy /= len
        return pts + floatArrayOf(pts[8] + dx * 34 * d, pts[9] + dy * 34 * d)
    }

    private fun drawXfHandles(c: Canvas, x: Xf) {
        val q = xfScreen(x)
        dash.color = 0xFFFFC107.toInt(); dash.strokeWidth = 2 * d
        val path = android.graphics.Path(); path.moveTo(q[0], q[1]); path.lineTo(q[2], q[3]); path.lineTo(q[4], q[5]); path.lineTo(q[6], q[7]); path.close()
        c.drawPath(path, dash)
        p.color = 0xFFFFC107.toInt(); p.strokeWidth = 2 * d; c.drawLine(q[8], q[9], q[12], q[13], p)
        for (k in 0 until 4) { p.color = Snes.INK; c.drawRect(q[k * 2] - 9 * d, q[k * 2 + 1] - 9 * d, q[k * 2] + 9 * d, q[k * 2 + 1] + 9 * d, p); p.color = Color.WHITE; c.drawRect(q[k * 2] - 7 * d, q[k * 2 + 1] - 7 * d, q[k * 2] + 7 * d, q[k * 2 + 1] + 7 * d, p) }
        p.color = Snes.INK; c.drawCircle(q[12], q[13], 13 * d, p); p.color = 0xFFFFC107.toInt(); c.drawCircle(q[12], q[13], 11 * d, p)
        txt.color = Snes.INK; txt.textSize = 13 * d; c.drawText("⟳", q[12], q[13] + 5 * d, txt)
        // how it's turned and sized, under the board's top
        val deg = ((x.rot % 360f) + 360f) % 360f
        txt.color = Color.WHITE; txt.textSize = 13 * d
        c.drawText("${"%.0f".format(if (deg > 180f) deg - 360f else deg)}°  ·  ${(x.s * 100).roundToInt()}%", board.centerX(), ctx.bottom + 22 * d, txt)
    }

    private fun xfDown(sx: Float, sy: Float) {
        val x = xf ?: return
        val q = xfScreen(x)
        val cx = q[10]; val cy = q[11]
        xfLast[0] = sx; xfLast[1] = sy
        when {
            hypot(sx - q[12], sy - q[13]) < 28 * d -> { xfDrag = XF_ROTATE; xfStartAngle = Math.toDegrees(kotlin.math.atan2((sy - cy).toDouble(), (sx - cx).toDouble())).toFloat(); xfStartRot = x.rot }
            (0 until 4).any { hypot(sx - q[it * 2], sy - q[it * 2 + 1]) < 28 * d } -> { xfDrag = XF_SCALE; xfStartDist = hypot(sx - cx, sy - cy).coerceAtLeast(1f); xfStartS = x.s }
            else -> xfDrag = XF_MOVE
        }
    }

    private fun xfTwoStart(e: MotionEvent) {
        xfDrag = XF_TWO
        xfSpan = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
        xfAng = Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
        xfMidX = (e.getX(0) + e.getX(1)) / 2; xfMidY = (e.getY(0) + e.getY(1)) / 2
    }

    private fun xfMove(e: MotionEvent) {
        val x = xf ?: return
        val k = scaleNow()
        when (xfDrag) {
            XF_MOVE -> { x.tx += (e.x - xfLast[0]) / k; x.ty += (e.y - xfLast[1]) / k; xfLast[0] = e.x; xfLast[1] = e.y }
            XF_SCALE -> { val q = xfScreen(x); x.s = (xfStartS * hypot(e.x - q[10], e.y - q[11]) / xfStartDist).coerceIn(0.05f, 20f) }
            XF_ROTATE -> {
                val q = xfScreen(x)
                val a = Math.toDegrees(kotlin.math.atan2((e.y - q[11]).toDouble(), (e.x - q[10]).toDouble())).toFloat()
                var rot = xfStartRot + (a - xfStartAngle)
                // any angle, with a gentle catch at the right angles and 45s
                val near = (rot / 45f).roundToInt() * 45f
                if (abs(rot - near) < 2.5f) rot = near
                x.rot = rot
            }
            XF_TWO -> if (e.pointerCount >= 2) {
                val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                val ang = Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                x.s = (x.s * span / xfSpan).coerceIn(0.05f, 20f)
                var da = ang - xfAng; if (da > 180f) da -= 360f; if (da < -180f) da += 360f
                x.rot += da
                x.tx += (mx - xfMidX) / k; x.ty += (my - xfMidY) / k
                xfSpan = span; xfAng = ang; xfMidX = mx; xfMidY = my
            }
        }
    }

    private fun applyXf() {
        val x = xf ?: return
        xf = null
        if (x.tx == 0f && x.ty == 0f && x.s == 1f && x.rot % 360f == 0f) { invalidate(); return }
        val l = x.layer
        if (x.piece == null) {
            val v = FloatArray(9); xfMatrix(x).getValues(v)
            addOp(l, Op.Transform(floatArrayOf(v[0], v[1], v[2], v[3], v[4], v[5])))
        } else {
            // the piece leaves its place and comes back turned and resized, all one step
            val s = x.sel!!
            val mid = ArtDoc.newId(); doc.saveBitmap(mid, s.mask)
            val pid = ArtDoc.newId(); doc.saveBitmap(pid, x.piece)
            val w = s.mask.width * x.s; val h = s.mask.height * x.s
            val cx = x.base.centerX() + x.tx; val cy = x.base.centerY() + x.ty
            val hole = Op.Mask(mid, s.l, s.t, false)
            val img = Op.Image(pid, cx - w / 2, cy - h / 2, w, h, x.rot)
            val f = frame
            l.cel(f) += hole; l.cel(f) += img; rebuild(l)
            did(object : Act() {
                override fun undo() { l.cel(f).remove(img); l.cel(f).remove(hole); if (f == frame) rebuild(l) else thumbs.remove(f) }
                override fun redo() { l.cel(f) += hole; l.cel(f) += img; if (f == frame) rebuild(l) else thumbs.remove(f) }
            })
        }
        thumbs.remove(frame); onionDirty = true; invalidate()
    }

    // ---- selection actions
    private fun selAction(which: Int) {
        val s = sel ?: return
        val l = doc.layers[active]
        when (which) {
            0, 1 -> { val id = ArtDoc.newId(); doc.saveBitmap(id, s.mask); addOp(l, Op.Mask(id, s.l, s.t, keep = which == 1)) }
            2 -> {
                if (doc.layers.size >= doc.maxLayers) { toast("Layers are full (${doc.maxLayers}): merge or delete one first"); return }
                // lift: copy the selected pixels to a new layer, and take them out of this one
                val piece = Select.cut(bmpOf(l), s, doc.ox, doc.oy)
                val pid = ArtDoc.newId(); doc.saveBitmap(pid, piece)
                val mid = ArtDoc.newId(); doc.saveBitmap(mid, s.mask)
                val nl = Layer("Cut-out", l.animated); val at = active + 1
                val f = frame
                val img = Op.Image(pid, s.l.toFloat(), s.t.toFloat(), s.mask.width.toFloat(), s.mask.height.toFloat())
                val mask = Op.Mask(mid, s.l, s.t, false)
                fun liftIn() { doc.layers.add(at.coerceAtMost(doc.layers.size), nl); nl.cel(f) += img; l.cel(f) += mask; if (f == frame) { rebuild(nl); rebuild(l) } }
                liftIn(); active = at
                did(object : Act() {
                    override fun undo() { doc.layers.remove(nl); pix.remove(nl)?.recycle(); nl.cel(f).remove(img); l.cel(f).remove(mask); if (f == frame) rebuild(l); active = active.coerceAtMost(doc.layers.size - 1); thumbs.clear() }
                    override fun redo() { liftIn() }
                })
            }
            3 -> { startSelXf(); return }
            4 -> { selLoop?.let { magicTol = (magicTol * 0.6f).toInt().coerceAtLeast(6); runMagic(it, magicTol) }; return }
            5 -> { selLoop?.let { magicTol = (magicTol * 1.6f).toInt().coerceAtMost(120); runMagic(it, magicTol) }; return }
        }
        sel = null; selLoop = null; thumbs.remove(frame); invalidate()
    }

    // =====================================================================================
    // Buttons and menus
    // =====================================================================================

    private fun press(t: RectF, long: Boolean) {
        val ci = ctxKeys.indexOf(t)
        if (ci >= 0 && !t.isEmpty) {
            if (xf != null) when (ci) { 0 -> applyXf(); 1 -> { xf?.let { it.tx = 0f; it.ty = 0f; it.s = 1f; it.rot = 0f }; invalidate() }; else -> { xf = null; invalidate() } }
            else if (placing != null) when (ci) { 0 -> commitPlace(); 1 -> placeSize(); 2 -> placeShape(); 3 -> { fitPlace(true); invalidate() }; 4 -> { fitPlace(false); invalidate() }; else -> { placing = null; invalidate() } }
            else when (ci) { 6 -> { sel = null; selLoop = null; invalidate() }; else -> selAction(ci) }
            return
        }
        thumbRects.firstOrNull { it.first === t }?.let { (_, f) -> if (long) frameMenu(f) else goFrame(f); return }
        when (t) {
            bDone -> finish()
            bUndo -> { history.removeLastOrNull()?.let { it.undo(); redos += it }; onionDirty = true }
            bRedo -> { redos.removeLastOrNull()?.let { it.redo(); history += it }; onionDirty = true }
            bZoom -> if (long) fitView() else zoomStep()
            bLayers -> layersMenu()
            bMore -> moreMenu()
            bColor -> colorPicker()
            tPrev -> goFrame((frame - 1 + doc.frames) % doc.frames)
            tNext -> goFrame((frame + 1) % doc.frames)
            tPlay -> if (playing) stopPlay() else startPlay()
            tAdd -> addFrame(copy = long)
            tOnion -> { onion = !onion; invalidate() }
            tFps -> fpsMenu()
            else -> {
                val ti = toolRects.indexOf(t)
                if (ti >= 0) { tool = ti; if (ti != Tool.MOVE) xf = null; if (ti != Tool.LASSO && ti != Tool.MAGIC) { sel = null; selLoop = null }; if (ti == Tool.MOVE) startLayerXf(); return }
                val si = swatchRects.indexOf(t)
                if (si >= 0) (recent + SWATCHES.toList()).distinct().getOrNull(si)?.let { color = it; if (tool == Tool.ERASER || tool == Tool.PICK) tool = Tool.PEN }
            }
        }
    }

    private fun zoomStep() {
        val z = scaleNow() / fit
        val next = listOf(2f, 4f, 8f, 16f, 24f).firstOrNull { it > z * 1.05f }
        if (next == null) { fitView(); return }
        val f = next * fit / scaleNow()
        view.postScale(f, f, board.centerX(), board.centerY())
    }

    private fun moreMenu() {
        val dlg = SnesDialog(a, doc.title)
        dlg.item("Bring in a picture…", Snes.GREEN) { host.pickImage { beginPlace(it) } }
        dlg.item("Canvas size and shape…", Snes.YELLOW) { canvasMenu() }
        dlg.item("Paper: ${bgName()}", Snes.PURPLE) { bgMenu() }
        dlg.item(if (timeline) "Hide the animation timeline" else "Animate (frames, onion skin, playback)", Snes.accent(1)) { timeline = !timeline; arrange(width.toFloat(), height.toFloat()); invalidate() }
        if (doc.is4k) dlg.item(if (guides) "Hide 4K guides" else "Show 4K guides", Snes.accent(2)) { guides = !guides; invalidate() }
        dlg.item("Export…", Snes.accent(3)) { exportMenu() }
        dlg.key("Close", Snes.PURPLE, then = null).show()
    }

    private fun bgName() = when (doc.bg) { 0 -> "see-through"; Color.WHITE -> "white"; else -> "colour" }
    private fun bgMenu() {
        val opts = listOf("White", "Cream paper", "See-through (for assets)", "Black", "The current colour")
        SnesDialog.choose(a, "Paper", opts) { i ->
            val old = doc.bg
            val nb = when (i) { 0 -> Color.WHITE; 1 -> 0xFFF7EFDC.toInt(); 2 -> 0; 3 -> Color.BLACK; else -> color }
            doc.bg = nb; thumbs.clear(); onionDirty = true
            did(object : Act() { override fun undo() { doc.bg = old; thumbs.clear() }; override fun redo() { doc.bg = nb; thumbs.clear() } })
            invalidate()
        }
    }

    private fun canvasMenu() {
        val presets: List<Pair<String, Pair<Int, Int>>> = if (doc.is4k) listOf(
            "4K frame (3840 × 2160)" to (3840 to 2160), "Character, full height (1080 × 2160)" to (1080 to 2160),
            "Character, half height (540 × 1080)" to (540 to 1080), "Prop (1024 × 1024)" to (1024 to 1024),
            "Wide prop (2048 × 1024)" to (2048 to 1024), "Icon or UI piece (512 × 512)" to (512 to 512),
            "Tall 4K (2160 × 3840)" to (2160 to 3840),
        ) else listOf(
            "Landscape 4:3 (2000 × 1500)" to (2000 to 1500), "Portrait 3:4 (1500 × 2000)" to (1500 to 2000),
            "Square (1600 × 1600)" to (1600 to 1600), "Wide 16:9 (1920 × 1080)" to (1920 to 1080),
            "Tall 9:16 (1080 × 1920)" to (1080 to 1920), "Photo 3:2 (2400 × 1600)" to (2400 to 1600),
        )
        SnesDialog.choose(a, "Canvas: now ${doc.w} × ${doc.h}", presets.map { it.first } + "Exact size…") { i ->
            if (i < presets.size) resizeCanvas(presets[i].second.first, presets[i].second.second)
            else SnesDialog.text(a, "Canvas size in pixels (width × height, up to ${ArtDoc.MAX_SIDE})", "${doc.w} × ${doc.h}") { s ->
                val n = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
                if (n.size >= 2) resizeCanvas(n[0], n[1]) else toast("Type two numbers, like 1920 × 1080")
            }
        }
    }

    /** A new canvas size; the drawing stays centred (nothing is stretched). */
    private fun resizeCanvas(nw0: Int, nh0: Int) {
        val nw = nw0.coerceIn(16, ArtDoc.MAX_SIDE); val nh = nh0.coerceIn(16, ArtDoc.MAX_SIDE)
        val ow = doc.w; val oh = doc.h; val oox = doc.ox; val ooy = doc.oy
        fun set(w: Int, h: Int, x: Float, y: Float) { doc.w = w; doc.h = h; doc.ox = x; doc.oy = y; dropPixels(); thumbs.clear(); onionDirty = true; fitView(); invalidate() }
        val nox = oox + (nw - ow) / 2f; val noy = ooy + (nh - oh) / 2f
        set(nw, nh, nox, noy)
        did(object : Act() { override fun undo() { set(ow, oh, oox, ooy) }; override fun redo() { set(nw, nh, nox, noy) } })
    }

    // ---- layers
    private fun layersMenu() {
        val dlg = SnesDialog(a, "Layers (top first)")
        for (i in doc.layers.indices.reversed()) {
            val l = doc.layers[i]
            dlg.item("${l.name}${if (!l.animated && timeline) " · still" else ""}${if (!l.visible) " (hidden)" else ""}${if (l.opacity < 1f) " · ${(l.opacity * 100).toInt()}%" else ""}", Snes.accent(i), i == active) { layerMenu(i) }
        }
        if (doc.layers.size < doc.maxLayers) dlg.item("+ New layer", Snes.GREEN) {
            val l = Layer("Layer ${doc.layers.size + 1}", true); val at = active + 1
            doc.layers.add(at, l); active = at
            did(object : Act() {
                override fun undo() { doc.layers.remove(l); pix.remove(l)?.recycle(); active = active.coerceAtMost(doc.layers.size - 1) }
                override fun redo() { doc.layers.add(at.coerceAtMost(doc.layers.size), l) }
            })
            invalidate()
        }
        dlg.key("Close", Snes.PURPLE, then = null).show()
    }

    private fun layerMenu(i: Int) {
        val l = doc.layers[i]
        val dlg = SnesDialog(a, l.name)
        dlg.item("Draw on this layer", Snes.GREEN, i == active) { active = i; invalidate() }
        dlg.item("Turn and resize this layer", Snes.accent(2)) { active = i; sel = null; selLoop = null; tool = Tool.MOVE; startLayerXf(); invalidate() }
        dlg.item(if (l.visible) "Hide" else "Show", Snes.YELLOW) { l.visible = !l.visible; thumbs.clear(); invalidate() }
        dlg.item("Opacity: ${(l.opacity * 100).toInt()}%", Snes.PURPLE) {
            val steps = listOf(1f, 0.75f, 0.5f, 0.25f)
            SnesDialog.choose(a, "${l.name} opacity", steps.map { "${(it * 100).toInt()}%" }) { k -> l.opacity = steps[k]; thumbs.clear(); invalidate() }
        }
        dlg.item(if (l.animated) "Make it a still layer (the same on every frame)" else "Animate this layer (its own drawing per frame)", Snes.accent(1)) { toggleAnimated(l) }
        dlg.item("Rename", Snes.accent(2)) { SnesDialog.text(a, "Layer name", l.name) { n -> if (n.isNotBlank()) { l.name = n.take(24); invalidate() } } }
        if (i < doc.layers.size - 1) dlg.item("Move up", Snes.accent(3)) { swapLayers(i, i + 1) }
        if (i > 0) dlg.item("Move down", Snes.accent(0)) { swapLayers(i, i - 1) }
        dlg.item("Clear (this frame)", Snes.RED) {
            val f = frame; val old = ArrayList(l.cel(f))
            l.cel(f).clear(); rebuild(l)
            did(object : Act() { override fun undo() { l.cel(f).addAll(old); if (f == frame) rebuild(l) }; override fun redo() { l.cel(f).clear(); if (f == frame) rebuild(l) } })
            invalidate()
        }
        if (doc.layers.size > 1) dlg.item("Delete this layer", Snes.RED) {
            val at = doc.layers.indexOf(l)
            doc.layers.remove(l); pix.remove(l)?.recycle(); active = active.coerceAtMost(doc.layers.size - 1); thumbs.clear()
            did(object : Act() {
                override fun undo() { doc.layers.add(at.coerceAtMost(doc.layers.size), l); thumbs.clear() }
                override fun redo() { doc.layers.remove(l); pix.remove(l)?.recycle(); active = active.coerceAtMost(doc.layers.size - 1); thumbs.clear() }
            })
            invalidate()
        }
        dlg.key("Back", Snes.PURPLE, then = null).show()
    }

    private fun toggleAnimated(l: Layer) {
        val oldCels = l.cels.map { ArrayList(it) }; val wasAnimated = l.animated
        if (l.animated) { val keep = ArrayList(l.cel(frame)); l.cels.clear(); l.animated = false; l.cel(0).addAll(keep) }
        else { val base = ArrayList(l.cel(0)); l.cels.clear(); l.animated = true; for (f in 0 until doc.frames) l.cel(f).addAll(base) }
        val newCels = l.cels.map { ArrayList(it) }; val nowAnimated = l.animated
        fun put(c: List<List<Op>>, an: Boolean) { l.cels.clear(); l.animated = an; c.forEach { l.cels.add(ArrayList(it)) }; rebuild(l); thumbs.clear() }
        rebuild(l); thumbs.clear()
        did(object : Act() { override fun undo() { put(oldCels, wasAnimated) }; override fun redo() { put(newCels, nowAnimated) } })
        invalidate()
    }

    private fun swapLayers(i: Int, j: Int) {
        java.util.Collections.swap(doc.layers, i, j)
        if (active == i) active = j else if (active == j) active = i
        thumbs.clear()
        did(object : Act() { override fun undo() { java.util.Collections.swap(doc.layers, i, j); thumbs.clear() }; override fun redo() { java.util.Collections.swap(doc.layers, i, j); thumbs.clear() } })
        invalidate()
    }

    private fun colorPicker() {
        val v = ColorPick(a, color) { color = it; if (tool == Tool.ERASER || tool == Tool.PICK) tool = Tool.PEN; invalidate() }
        SnesDialog(a, "Colour").view(LinearLayout(a).apply { addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Px.dp(220))) })
            .key("Done", Snes.GREEN, then = null).show()
    }

    // ---- frames
    private fun goFrame(f: Int) {
        if (f == frame) return
        sel = null; selLoop = null
        frame = f.coerceIn(0, doc.frames - 1)
        for (l in doc.layers) if (l.animated) rebuild(l)
        onionDirty = true; invalidate()
    }

    private fun addFrame(copy: Boolean) {
        val at = frame + 1; val from = frame
        doc.insertFrame(at, if (copy) from else null)
        thumbs.clear()
        did(object : Act() {
            override fun undo() { doc.deleteFrame(at); thumbs.clear(); if (frame >= doc.frames) frame = doc.frames - 1; for (l in doc.layers) if (l.animated) rebuild(l); onionDirty = true }
            override fun redo() { doc.insertFrame(at, if (copy) from else null); thumbs.clear(); onionDirty = true }
        })
        goFrame(at)
        if (!timeline) { timeline = true; arrange(width.toFloat(), height.toFloat()) }
        toast(if (copy) "Frame ${at + 1}: a copy of frame ${from + 1}" else "Frame ${at + 1} (hold + frame for a copy)")
    }

    private fun frameMenu(f: Int) {
        val dlg = SnesDialog(a, "Frame ${f + 1}")
        dlg.item("Duplicate", Snes.GREEN) { goFrame(f); addFrame(copy = true) }
        dlg.item("New blank frame after it", Snes.GREEN) { goFrame(f); addFrame(copy = false) }
        if (f > 0) dlg.item("Move earlier", Snes.YELLOW) { moveFrame(f, f - 1) }
        if (f < doc.frames - 1) dlg.item("Move later", Snes.YELLOW) { moveFrame(f, f + 1) }
        if (doc.frames > 1) dlg.item("Delete", Snes.RED) {
            val removed = doc.deleteFrame(f)
            thumbs.clear(); frame = frame.coerceAtMost(doc.frames - 1); for (l in doc.layers) if (l.animated) rebuild(l); onionDirty = true
            did(object : Act() {
                override fun undo() { doc.frames++; for ((l, cel) in removed) l.cels.add(f.coerceAtMost(l.cels.size), cel); thumbs.clear(); for (l in doc.layers) if (l.animated) rebuild(l); onionDirty = true }
                override fun redo() { doc.deleteFrame(f); thumbs.clear(); frame = frame.coerceAtMost(doc.frames - 1); for (l in doc.layers) if (l.animated) rebuild(l); onionDirty = true }
            })
            invalidate()
        }
        dlg.key("Close", Snes.PURPLE, then = null).show()
    }

    private fun moveFrame(i: Int, j: Int) {
        fun swap() { for (l in doc.layers) if (l.animated) { l.cel(max(i, j)); java.util.Collections.swap(l.cels, i, j) }; thumbs.clear(); for (l in doc.layers) if (l.animated) rebuild(l); onionDirty = true }
        swap(); frame = j; for (l in doc.layers) if (l.animated) rebuild(l)
        did(object : Act() { override fun undo() { swap() }; override fun redo() { swap() } })
        invalidate()
    }

    private fun fpsMenu() {
        val opts = listOf(6, 8, 12, 15, 24, 30)
        SnesDialog.choose(a, "Frames per second", opts.map { "$it fps" + when (it) { 12 -> " (classic hand-drawn)"; 24 -> " (film)"; else -> "" } }) { i -> doc.fps = opts[i]; dirty = true; invalidate() }
    }

    private fun buildOnion() {
        onionDirty = false
        onionPrev?.recycle(); onionNext?.recycle(); onionPrev = null; onionNext = null
        if (doc.frames < 2) return
        val s = min(1f, 1024f / max(doc.w, doc.h))
        fun silhouette(f: Int): Bitmap {
            val out = Bitmap.createBitmap(max(1, (doc.w * s).toInt()), max(1, (doc.h * s).toInt()), Bitmap.Config.ARGB_8888)
            val tmp = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888); val c = Canvas(out)
            for (l in doc.layers) if (l.visible && l.animated) { doc.replay(l.cel(f), tmp, s); c.drawBitmap(tmp, 0f, 0f, null) }
            tmp.recycle(); return out
        }
        if (frame > 0) onionPrev = silhouette(frame - 1)
        if (frame < doc.frames - 1) onionNext = silhouette(frame + 1)
    }

    // ---- playback
    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            playAt = (playAt + 1) % max(1, playFrames.size); invalidate()
            main.postDelayed(this, (1000L / doc.fps))
        }
    }
    private fun startPlay() {
        if (doc.frames < 2) { toast("Add a frame first (+ frame)"); return }
        busy = "Getting the frames ready…"; invalidate()
        val s = min(1f, min(board.width() / doc.w, board.height() / doc.h))
        Thread {
            val list = (0 until doc.frames).map { Exporter.frame(doc, it, s) }
            main.post { busy = null; playFrames = list; playAt = frame; playing = true; main.postDelayed(tick, 1000L / doc.fps); invalidate() }
        }.start()
    }
    private fun stopPlay() {
        if (!playing) return
        playing = false; main.removeCallbacks(tick)
        playFrames.forEach { it.recycle() }; playFrames = emptyList(); invalidate()
    }

    // ---- export
    private fun exportMenu() {
        val opts = mutableListOf("This frame as a PNG${if (doc.bg == 0) " (see-through)" else ""}")
        if (doc.frames > 1) { opts += "Video (MP4)"; opts += "PNG sequence (a zip of numbered frames)"; opts += "Sprite sheet (all frames in one PNG)" }
        SnesDialog.choose(a, "Export ${doc.w} × ${doc.h}", opts) { i ->
            stopPlay()
            busy = "Exporting…"; invalidate()
            val name = ArtDocNames.safe(doc.title)
            Thread {
                val msg = runCatching {
                    val cache = File(a.cacheDir, "art-export").apply { mkdirs() }
                    when (i) {
                        0 -> { val f = File(cache, "$name.png"); Exporter.png(doc, frame, f); save(f, "$name frame ${frame + 1}.png", "image/png") }
                        1 -> {
                            val f = File(cache, "$name.mp4")
                            // short animations loop for at least three seconds
                            val loops = max(1, (3 * doc.fps + doc.frames - 1) / doc.frames)
                            val size = Exporter.mp4(doc, f, loops) { k, n -> main.post { busy = "Encoding video… $k/$n"; invalidate() } } ?: return@runCatching "This phone can't encode video at that size"
                            save(f, "$name.mp4", "video/mp4") + if (size.first != doc.w) " (at ${size.first} × ${size.second}, the most this phone's encoder allows)" else ""
                        }
                        2 -> { val f = File(cache, "$name frames.zip"); Exporter.pngSequence(doc, f) { k -> main.post { busy = "Frame $k of ${doc.frames}…"; invalidate() } }; save(f, "$name frames.zip", "application/zip") }
                        else -> {
                            val sheet = Exporter.spriteSheet(doc) ?: return@runCatching "Too big for one sheet (over 8192 px): use the PNG sequence"
                            val f = File(cache, "$name sheet.png"); f.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }; sheet.recycle()
                            save(f, "$name sheet.png", "image/png")
                        }
                    }
                }.getOrElse { "Couldn't export (${it.message})" }
                main.post { busy = null; toast(msg); invalidate() }
            }.start()
        }
    }
    private fun save(f: File, name: String, mime: String): String {
        val uri = app.foxbox.kit.Export.saveFile("Drawings", name, mime, f)
        f.delete()
        return if (uri != null) "Saved to ${app.foxbox.kit.Export.root}/Drawings" else "Couldn't save $name"
    }

    // ---- saving and closing
    /** Saves the drawing and the picture shown on the notebook page. */
    fun saveNow(): Bitmap {
        doc.save()
        dirty = false
        return Exporter.frame(doc, 0, min(1f, 1200f / max(doc.w, doc.h)), transparent = false)
    }

    private var finished = false
    private fun finish() {
        if (finished) return
        finished = true
        stopPlay()
        host.closed()
    }

    fun release() { stopPlay(); dropPixels(); thumbs.values.forEach { it.recycle() }; thumbs.clear(); onionPrev?.recycle(); onionNext?.recycle() }

    /** Back: first leave placing or a selection, then close. */
    fun back() {
        when {
            playing -> stopPlay()
            xf != null -> { xf = null; invalidate() }
            placing != null -> { placing = null; invalidate() }
            sel != null -> { sel = null; selLoop = null; invalidate() }
            else -> finish()
        }
    }

    private fun toast(s: String) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show() }
}
