package app.fiveiy.builder

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A sprite: one picture per direction and frame, all the same size. Saved as a single PNG sheet
 * (frames across; for four-direction characters, rows down/left/right/up), named so the Builder
 * knows how to cut it up: name_4dir_f3.png.
 */
class PixDoc(var w: Int, var h: Int, var dirs: Int, frames: Int) {
    var cells: Array<ArrayList<IntArray>> = Array(dirs) { ArrayList<IntArray>().apply { repeat(frames) { add(IntArray(w * h)) } } }
    /** Picture pixels per world pixel: 1 for pixel art; 2 to 4 for detail sprites (saved as name@4x.png). */
    var density = 1
    val frames get() = cells[0].size
    fun cell(d: Int, f: Int) = cells[d.coerceIn(0, dirs - 1)][f.coerceIn(0, frames - 1)]
    fun copy(): PixDoc { val c = PixDoc(w, h, dirs, 1); c.density = density; c.cells = Array(dirs) { d -> ArrayList(cells[d].map { it.copyOf() }) }; return c }
    fun restore(o: PixDoc) { w = o.w; h = o.h; dirs = o.dirs; density = o.density; cells = Array(o.dirs) { d -> ArrayList(o.cells[d].map { it.copyOf() }) } }
    /** Up to a finer density: every picture enlarged (smoothly, or as blocks) so you can draw detail into it. */
    fun detail(k: Int, smooth: Boolean) {
        cells = Array(dirs) { d -> ArrayList(cells[d].map { PixelOps.upscale(it, w, h, k, smooth) }) }
        w *= k; h *= k; density *= k
    }

    fun toBitmap(): Bitmap {
        val b = Bitmap.createBitmap(w * frames, h * dirs, Bitmap.Config.ARGB_8888)
        for (d in 0 until dirs) for (f in 0 until frames) b.setPixels(cells[d][f], 0, w, f * w, d * h, w, h)
        return b
    }
    /** Every picture resized (anchored at the bottom centre, where it stands), or scaled. */
    fun resize(nw: Int, nh: Int) {
        val ox = (nw - w) / 2; val oy = nh - h
        cells = Array(dirs) { d -> ArrayList(cells[d].map { src -> IntArray(nw * nh).also { dst -> for (y in 0 until nh) for (x in 0 until nw) { val sx = x - ox; val sy = y - oy; if (sx in 0 until w && sy in 0 until h) dst[y * nw + x] = src[sy * w + sx] } } }) }
        w = nw; h = nh
    }
    fun scale(k: Float) {
        val nw = max(1, (w * k).toInt()); val nh = max(1, (h * k).toInt())
        cells = Array(dirs) { d -> ArrayList(cells[d].map { src -> IntArray(nw * nh).also { dst -> for (y in 0 until nh) for (x in 0 until nw) dst[y * nw + x] = src[min(h - 1, (y / k).toInt()) * w + min(w - 1, (x / k).toInt())] } }) }
        w = nw; h = nh
    }

    companion object {
        fun fromAsset(a: Library.Asset): PixDoc? {
            val b = a.bmp ?: return null
            val fw = a.srcW; val fh = a.srcH
            val d = PixDoc(fw, fh, a.dirs, a.frames); d.density = a.density
            for (dd in 0 until a.dirs) for (f in 0 until a.frames) b.getPixels(d.cells[dd][f], 0, fw, f * fw, dd * fh, fw, fh)
            return d
        }
    }
}

/** The drawing surface: zoom with two fingers (pinch) and pan; draw with one. */
@SuppressLint("ViewConstructor")
class PixelCanvas(ctx: Context, private val studio: PixelStudio) : View(ctx) {
    private val p = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val sp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val f = Paint()
    private val line = Paint().apply { style = Paint.Style.STROKE }
    var zoom = 0f; var panX = 0f; var panY = 0f
    private var bmp: Bitmap? = null
    private val t0 = System.nanoTime()

    fun fit() {
        val d = studio.doc
        if (width == 0) return
        zoom = max(1f, kotlin.math.floor(min((width - 40f) / d.w, (height - 40f) / d.h)))
        panX = (width - d.w * zoom) / 2; panY = (height - d.h * zoom) / 2
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit() }

    private fun cellBitmap(px: IntArray, w: Int, h: Int): Bitmap {
        val b = bmp?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp = it }
        b.setPixels(px, 0, w, 0, 0, w, h); return b
    }

    override fun onDraw(c: Canvas) {
        val d = studio.doc
        if (zoom <= 0f) fit()
        c.drawColor(0xFF14141C.toInt())
        val l = panX; val t = panY; val r = l + d.w * zoom; val b = t + d.h * zoom
        // transparency checkerboard
        val ck = max(zoom, 6f)
        c.save(); c.clipRect(l, t, r, b)
        var y = t; var row = 0
        while (y < b) { var x = l; var col = row
            while (x < r) { f.color = if (col % 2 == 0) 0xFF3A3A48.toInt() else 0xFF30303C.toInt(); c.drawRect(x, y, x + ck, y + ck, f); x += ck; col++ }
            y += ck; row++ }
        c.restore()
        val dst = RectF(l, t, r, b)
        // the frame before, faintly (onion skin)
        if (studio.onion && d.frames > 1) {
            p.alpha = 70; c.drawBitmap(cellBitmap(d.cell(studio.dir, (studio.frame - 1 + d.frames) % d.frames), d.w, d.h), null, dst, p); p.alpha = 255
        }
        c.drawBitmap(cellBitmap(studio.preview ?: d.cell(studio.dir, studio.frame), d.w, d.h), null, dst, p)
        if (studio.grid) {
            line.strokeWidth = 1f
            if (zoom >= 6f) {
                line.color = if (d.density > 1) 0x18FFFFFF else 0x33FFFFFF
                for (i in 0..d.w) c.drawLine(l + i * zoom, t, l + i * zoom, b, line)
                for (i in 0..d.h) c.drawLine(l, t + i * zoom, r, t + i * zoom, line)
            }
            // on a detail sprite, the world's pixels; on pixel art, every 8
            val step = if (d.density > 1) d.density else 8
            if (step * zoom >= 6f) {
                line.color = if (d.density > 1) 0x44FFFFFF else 0x66F0CC50
                if (d.w % step == 0) for (i in step until d.w step step) c.drawLine(l + i * zoom, t, l + i * zoom, b, line)
                if (d.h % step == 0) for (i in step until d.h step step) c.drawLine(l, t + i * zoom, r, t + i * zoom, line)
            }
        }
        if (studio.mirror) { line.color = 0xAA48C0F0.toInt(); line.strokeWidth = 2f; c.drawLine((l + r) / 2, t, (l + r) / 2, b, line) }
        line.color = 0xFF707088.toInt(); line.strokeWidth = 2f; c.drawRect(l - 1, t - 1, r + 1, b + 1, line)
        // a little live preview of the animation, at 2x (or 1x for big sprites)
        // (a detail sprite is previewed at its size in the world, smoothed, as the Builder shows it)
        val pk = if (d.density > 1) 3f / d.density else if (d.w * 3 < width / 3) 3f else if (d.w * 2 < width / 3) 2f else 1f
        val pw = d.w * pk; val ph = d.h * pk
        f.color = 0xFF2A2A36.toInt(); c.drawRect(width - pw - 16, 8f, width - 8f, ph + 16, f)
        val pf = if (d.frames > 1) ((System.nanoTime() - t0) / 1e9 * 6).toInt() % d.frames else 0
        val frameShown = if (d.dirs == 4 && d.frames == 3) RoomView.WALK3[((System.nanoTime() - t0) / 1e9 * 7).toInt() % 4] else pf
        c.drawBitmap(cellBitmap(d.cell(studio.dir, frameShown), d.w, d.h), null, RectF(width - pw - 12, 12f, width - 12f, 12 + ph), if (d.density > 1) sp else p)
        postInvalidateOnAnimation()
    }

    // ---- touch
    private var mode = 0       // 0 none, 1 drawing, 2 pinching
    private var startDist = 0f; private var startZoom = 0f; private var midX = 0f; private var midY = 0f
    private fun pixel(x: Float, y: Float) = intArrayOf(kotlin.math.floor((x - panX) / zoom).toInt(), kotlin.math.floor((y - panY) / zoom).toInt())

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { mode = 1; val q = pixel(e.x, e.y); studio.strokeStart(q[0], q[1], (e.x - panX) / zoom, (e.y - panY) / zoom) }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                if (mode == 1) studio.strokeCancel()
                mode = 2; startDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); startZoom = zoom
                midX = (e.getX(0) + e.getX(1)) / 2; midY = (e.getY(0) + e.getY(1)) / 2
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                1 -> { val q = pixel(e.x, e.y); studio.strokeMove(q[0], q[1], (e.x - panX) / zoom, (e.y - panY) / zoom) }
                2 -> if (e.pointerCount >= 2) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    val nz = (startZoom * d / max(1f, startDist)).coerceIn(1f, 80f)
                    // zoom about the fingers, then follow them
                    panX = mx - (midX - panX) * nz / zoom; panY = my - (midY - panY) * nz / zoom
                    zoom = nz; midX = mx; midY = my
                }
            }
            MotionEvent.ACTION_UP -> { if (mode == 1) studio.strokeEnd(); mode = 0 }
            MotionEvent.ACTION_CANCEL -> { if (mode == 1) studio.strokeCancel(); mode = 0 }
        }
        return true
    }
}

/**
 * The pixel studio: draw characters (four directions, a walk cycle), props, tiles and items at
 * any size, frame by frame, and save them straight into your world folder, where the Builder
 * picks them up.
 */
class PixelStudio(private val act: MainActivity, private val lib: Library, private val ws: Workspace, private val onClose: () -> Unit) {
    enum class Tool { PEN, ERASE, SMOOTH, FILL, LINE, BOX, OVAL, PICK, MOVE }
    var doc = PixDoc(24, 24, 4, 3)
    var dir = 0; var frame = 0
    var tool = Tool.PEN
    var color = 0xFF201828.toInt()
    var brush = 1
    /** Soft: round, feathered brushes (and fills that blend into their edges); hard: square pixels. */
    var soft = false
    var opacity = 1f
    var mirror = false; var grid = true; var onion = false
    var preview: IntArray? = null
    private var name = "new_character"; private var cat = "characters"; private var openedKey: String? = null
    private var dirty = false
    private val undo = ArrayDeque<PixDoc>(); private val redo = ArrayDeque<PixDoc>()
    private lateinit var canvas: PixelCanvas
    private lateinit var frameRow: LinearLayout
    private lateinit var toolRow: LinearLayout
    private lateinit var colorRow: LinearLayout
    private lateinit var title: TextView
    private val prefs = act.getSharedPreferences("studio", Context.MODE_PRIVATE)

    companion object {
        /** A 16-bit RPG palette: outlines, skin, hair, cloth, greenery, water, stone, gold, fire, magic. */
        val PALETTE = longArrayOf(
            0xFF201828, 0xFF3A3248, 0xFF5A5470, 0xFF8A86A0, 0xFFB8B8C8, 0xFFE0E0E8, 0xFFF8F8F8,
            0xFF6A3A20, 0xFF9A5A30, 0xFFC08048, 0xFFE8B080, 0xFFF4C8A0, 0xFFF8E0C8,
            0xFF3A2A20, 0xFF8A4A20, 0xFFC07030, 0xFFF0C850, 0xFFE0E0E8,
            0xFF7A1A2A, 0xFFB03030, 0xFFE04848, 0xFFF08888, 0xFFF080B0,
            0xFF8A3A10, 0xFFD06020, 0xFFF08020, 0xFFF8B040, 0xFFF8E070,
            0xFF1E4A28, 0xFF2E7A48, 0xFF4E9A40, 0xFF6CB04C, 0xFF9AD070, 0xFFC8E8A0,
            0xFF1E2A5A, 0xFF2A4A9A, 0xFF3C6CC8, 0xFF48A0E8, 0xFF8CC8F0, 0xFFC8E8F8,
            0xFF40205A, 0xFF6048B0, 0xFF8A68D0, 0xFFB090E8, 0xFF68E8F8,
            0xFF4A4A58, 0xFF6E6E80, 0xFF9A9AA8, 0xFFA88420, 0xFFF0CC50, 0x00000000,
        ).map { it.toInt() }.toIntArray()
        val TRANSPARENT = 0
        val SIZES = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)
    }

    private fun dp(v: Float) = v * act.resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()
    private fun btn(s: String, col: Int, click: () -> Unit) = TextView(act).apply {
        text = s; textSize = 12f; setTextColor(if (col == MainActivity.GOLD) MainActivity.INK else 0xFFFFFFFF.toInt()); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER; background = GradientDrawable().apply { cornerRadius = dp(7f); setColor(col) }; setPadding(dpi(10f), dpi(8f), dpi(10f), dpi(8f))
        setOnClickListener { click() }
    }
    private fun row() = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(4f), dpi(3f), dpi(4f), dpi(3f)) }
    private fun LinearLayout.put(v: View) = addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(4f) })
    private fun scroll(v: View) = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(v) }

    fun build(): View {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MainActivity.BG) }
        val top = row()
        top.put(btn("◀ BACK", MainActivity.GREY) { close() })
        top.put(btn("NEW", MainActivity.GREEN) { newDialog() })
        top.put(btn("OPEN", MainActivity.BLUE) { openDialog() })
        top.put(btn("SAVE", MainActivity.GOLD) { save(false) })
        top.put(btn("SAVE AS", MainActivity.PURPLE) { save(true) })
        top.put(btn("UNDO", MainActivity.GREY) { doUndo() })
        top.put(btn("REDO", MainActivity.GREY) { doRedo() })
        top.put(btn("SIZE", MainActivity.GREY) { sizeDialog() })
        top.put(btn("FIT", MainActivity.GREY) { canvas.fit() })
        root.addView(scroll(top))
        title = TextView(act).apply { setTextColor(MainActivity.GOLD); textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(dpi(8f), 0, 0, 0) }
        root.addView(title)
        canvas = PixelCanvas(act, this)
        root.addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        toolRow = row(); root.addView(scroll(toolRow))
        frameRow = row(); root.addView(scroll(frameRow))
        colorRow = row(); root.addView(scroll(colorRow))
        refreshAll()
        return root
    }

    fun open(key: String?) {
        val a = lib.get(key)
        val d = a?.let { PixDoc.fromAsset(it) }
        if (d != null && key != null) {
            doc = d; openedKey = key; cat = key.substringBefore('/')
            name = key.substringAfter('/').replace(Regex("_f\\d+$"), "").replace("_4dir", "")
        }
        dir = 0; frame = 0; undo.clear(); redo.clear(); dirty = false
        if (::canvas.isInitialized) { canvas.fit(); refreshAll() }
    }

    private fun refreshAll() { refreshTitle(); refreshTools(); refreshFrames(); refreshColors() }
    private fun refreshTitle() {
        title.text = "$cat/$name  " + (if (doc.density > 1) "${doc.w / doc.density}×${doc.h / doc.density} AT ×${doc.density} DETAIL (${doc.w}×${doc.h})" else "${doc.w}×${doc.h}") + (if (doc.dirs == 4) "  4 DIRECTIONS" else "") + "  ${doc.frames} FRAME" + (if (doc.frames > 1) "S" else "") + if (dirty) "  •" else ""
    }

    private fun refreshTools() {
        toolRow.removeAllViews()
        for ((t, s) in listOf(Tool.PEN to "PEN", Tool.ERASE to "ERASE", Tool.SMOOTH to "SMOOTH", Tool.FILL to "FILL", Tool.LINE to "LINE", Tool.BOX to "BOX", Tool.OVAL to "OVAL", Tool.PICK to "PICK", Tool.MOVE to "MOVE"))
            toolRow.put(btn(s, if (tool == t) MainActivity.GOLD else MainActivity.GREY) { tool = t; refreshTools() })
        toolRow.put(btn("SIZE $brush", MainActivity.GREY) { brush = SIZES[(SIZES.indexOf(brush) + 1) % SIZES.size]; refreshTools() })
        toolRow.put(btn(if (soft) "SOFT" else "HARD", if (soft) MainActivity.BLUE else MainActivity.GREY) { soft = !soft; refreshTools() })
        toolRow.put(btn("${(opacity * 100).toInt()}%", MainActivity.GREY) { opacity = when (opacity) { 1f -> 0.6f; 0.6f -> 0.35f; 0.35f -> 0.15f; else -> 1f }; refreshTools() })
        toolRow.put(btn("DETAIL", MainActivity.GOLD) { detailDialog() })
        toolRow.put(btn("SMOOTH EDGES", MainActivity.BLUE) { smoothDialog() })
        toolRow.put(btn("IMPORT IMAGE", MainActivity.GREEN) { act.pickImageForStudio() })
        toolRow.put(btn("MIRROR", if (mirror) MainActivity.BLUE else MainActivity.GREY) { mirror = !mirror; refreshTools() })
        toolRow.put(btn("GRID", if (grid) MainActivity.BLUE else MainActivity.GREY) { grid = !grid; refreshTools() })
        toolRow.put(btn("ONION", if (onion) MainActivity.BLUE else MainActivity.GREY) { onion = !onion; refreshTools() })
        toolRow.put(btn("OUTLINE", MainActivity.PURPLE) { outlineDialog() })
        toolRow.put(btn("FLIP", MainActivity.GREY) { edit { val c = doc.cell(dir, frame); val w = doc.w; for (y in 0 until doc.h) for (x in 0 until w / 2) { val a = c[y * w + x]; c[y * w + x] = c[y * w + w - 1 - x]; c[y * w + w - 1 - x] = a } } })
        toolRow.put(btn("CLEAR", MainActivity.RED) { edit { doc.cell(dir, frame).fill(0) } })
    }

    private inner class FrameThumb(val d: Int, val f: Int) : View(act) {
        private val p = Paint().apply { isFilterBitmap = false }
        private val fp = Paint()
        override fun onDraw(c: Canvas) {
            fp.color = if (d == dir && f == frame) MainActivity.GOLD else 0xFF2E2E3C.toInt(); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
            fp.color = 0xFF3A3A48.toInt(); c.drawRect(3f, 3f, width - 3f, height - 3f, fp)
            val b = Bitmap.createBitmap(doc.w, doc.h, Bitmap.Config.ARGB_8888); b.setPixels(doc.cell(d, f), 0, doc.w, 0, 0, doc.w, doc.h)
            val k = min((width - 8f) / doc.w, (height - 8f) / doc.h)
            c.drawBitmap(b, Rect(0, 0, doc.w, doc.h), RectF(width / 2f - doc.w * k / 2, height / 2f - doc.h * k / 2, width / 2f + doc.w * k / 2, height / 2f + doc.h * k / 2), p)
        }
    }

    private fun refreshFrames() {
        frameRow.removeAllViews()
        if (doc.dirs == 4) for ((d, s) in listOf(0 to "▼", 1 to "◀", 2 to "▶", 3 to "▲"))
            frameRow.put(btn(s, if (dir == d) MainActivity.GOLD else MainActivity.GREY) { dir = d; frame = frame.coerceAtMost(doc.frames - 1); refreshFrames(); refreshTitle() })
        for (f in 0 until doc.frames) {
            val v = FrameThumb(dir, f); v.setOnClickListener { frame = f; refreshFrames() }
            frameRow.addView(v, LinearLayout.LayoutParams(dpi(52f), dpi(52f)).apply { rightMargin = dpi(4f) })
        }
        frameRow.put(btn("+ FRAME", MainActivity.GREEN) { edit { for (d in 0 until doc.dirs) doc.cells[d].add(frame + 1, doc.cells[d][frame].copyOf()); frame++ } })
        if (doc.frames > 1) frameRow.put(btn("DEL FRAME", MainActivity.RED) { edit { for (d in 0 until doc.dirs) doc.cells[d].removeAt(frame); frame = frame.coerceAtMost(doc.frames - 1) } })
        if (doc.dirs == 4) {
            frameRow.put(btn("▶ TO ◀", MainActivity.PURPLE) { edit { for (f in 0 until doc.frames) mirrorInto(doc.cell(2, f), doc.cell(1, f)) }; act.toastIt("Left now mirrors right") })
            frameRow.put(btn("◀ TO ▶", MainActivity.PURPLE) { edit { for (f in 0 until doc.frames) mirrorInto(doc.cell(1, f), doc.cell(2, f)) }; act.toastIt("Right now mirrors left") })
        }
        frameRow.put(btn("COPY ▼ TO ALL", MainActivity.GREY) { edit { for (d in 1 until doc.dirs) for (f in 0 until doc.frames) doc.cell(0, f).copyInto(doc.cell(d, f)) } }.apply { visibility = if (doc.dirs == 4) View.VISIBLE else View.GONE })
    }
    private fun mirrorInto(src: IntArray, dst: IntArray) { val w = doc.w; for (y in 0 until doc.h) for (x in 0 until w) dst[y * w + x] = src[y * w + w - 1 - x] }

    private fun refreshColors() {
        colorRow.removeAllViews()
        fun swatch(c: Int, big: Boolean, click: () -> Unit, long: (() -> Unit)? = null) = object : View(act) {
            val fp = Paint()
            override fun onDraw(cv: Canvas) {
                fp.color = if (c == color) MainActivity.GOLD else 0xFF2E2E3C.toInt(); cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
                if (Color.alpha(c) == 0) { fp.color = 0xFFF8F8F8.toInt(); cv.drawRect(4f, 4f, width - 4f, height - 4f, fp); fp.color = 0xFFE04848.toInt(); fp.strokeWidth = 4f; cv.drawLine(4f, height - 4f, width - 4f, 4f, fp) }
                else { fp.color = c; cv.drawRect(4f, 4f, width - 4f, height - 4f, fp) }
            }
        }.also { v -> v.setOnClickListener { click() }; long?.let { l -> v.setOnLongClickListener { l(); true } }
            colorRow.addView(v, LinearLayout.LayoutParams(dpi(if (big) 52f else 34f), dpi(if (big) 52f else 40f)).apply { rightMargin = dpi(3f) }) }
        swatch(color, true, { colorDialog() })
        colorRow.put(btn("MIX", MainActivity.GREY) { colorDialog() })
        val recent = prefs.getString("recent", "")!!.split(',').mapNotNull { it.toLongOrNull()?.toInt() }
        for (c in recent) swatch(c, false, { color = c; refreshColors() })
        for (c in PALETTE) swatch(c, false, { color = c; if (tool == Tool.ERASE || tool == Tool.PICK) tool = Tool.PEN; refreshColors(); refreshTools() })
    }
    private fun remember(c: Int) {
        if (c in PALETTE) return
        val recent = (listOf(c) + prefs.getString("recent", "")!!.split(',').mapNotNull { it.toLongOrNull()?.toInt() }.filter { it != c }).take(12)
        prefs.edit().putString("recent", recent.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()
    }

    private fun colorDialog() {
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(10f), dpi(20f), 0) }
        val sw = View(act); box.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48f)))
        val hex = EditText(act).apply { inputType = InputType.TYPE_CLASS_TEXT; textSize = 15f }
        var cur = color
        fun show() { sw.setBackgroundColor(cur); if (!hex.hasFocus()) hex.setText(String.format("#%06X", cur and 0xFFFFFF)) }
        fun bar(label: String, max: Int, v: Int, on: (Int) -> Unit) {
            box.addView(TextView(act).apply { text = label; textSize = 12f })
            box.addView(SeekBar(act).apply { this.max = max; progress = v; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { on(p); show() } }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) })
        }
        bar("Hue", 360, hsv[0].toInt()) { hsv[0] = it.toFloat(); cur = Color.HSVToColor(hsv) }
        bar("Saturation", 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f; cur = Color.HSVToColor(hsv) }
        bar("Brightness", 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f; cur = Color.HSVToColor(hsv) }
        box.addView(TextView(act).apply { text = "Or type a hex colour:"; textSize = 12f }); box.addView(hex)
        if (Color.alpha(cur) == 0) cur = 0xFF808080.toInt()
        show()
        AlertDialog.Builder(act).setTitle("Mix a colour").setView(box).setPositiveButton("Use it") { _, _ ->
            val typed = runCatching { Color.parseColor(hex.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) }.getOrNull()
            color = if (typed != null && (typed and 0xFFFFFF) != (cur and 0xFFFFFF)) typed or (0xFF shl 24) else cur
            remember(color); if (tool == Tool.ERASE || tool == Tool.PICK) tool = Tool.PEN
            refreshColors(); refreshTools()
        }.setNegativeButton("Cancel", null).show()
    }

    // ---- drawing
    private var before: PixDoc? = null
    private var sx = 0; private var sy = 0; private var lx = 0; private var ly = 0
    private var moveBase: IntArray? = null

    private fun edit(f: () -> Unit) { push(); f(); dirty = true; refreshFrames(); refreshTitle() }
    private fun push() { undo.addLast(doc.copy()); while (undo.size > 60) undo.removeFirst(); redo.clear() }
    private fun doUndo() { val u = undo.removeLastOrNull() ?: return; redo.addLast(doc.copy()); doc.restore(u); clampSel(); refreshAll() }
    private fun doRedo() { val r = redo.removeLastOrNull() ?: return; undo.addLast(doc.copy()); doc.restore(r); clampSel(); refreshAll() }
    private fun clampSel() { dir = dir.coerceIn(0, doc.dirs - 1); frame = frame.coerceIn(0, doc.frames - 1) }

    /** One dab of the brush at (x, y): how much of each pixel it covers (0..1). */
    private fun dab(x: Float, y: Float, cover: (Int, Float) -> Unit) {
        val w = doc.w; val h = doc.h
        if (!soft) {
            val r = brush - 1; val x0 = kotlin.math.floor(x).toInt() - r / 2; val y0 = kotlin.math.floor(y).toInt() - r / 2
            for (yy in y0..y0 + r) for (xx in x0..x0 + r) if (xx in 0 until w && yy in 0 until h) { cover(yy * w + xx, 1f); if (mirror) cover(yy * w + w - 1 - xx, 1f) }
            return
        }
        val rad = max(0.8f, brush / 2f)
        for (yy in kotlin.math.floor(y - rad - 1).toInt()..kotlin.math.ceil(y + rad + 1).toInt()) for (xx in kotlin.math.floor(x - rad - 1).toInt()..kotlin.math.ceil(x + rad + 1).toInt()) {
            if (xx !in 0 until w || yy !in 0 until h) continue
            val d = hypot(xx + 0.5f - x, yy + 0.5f - y)
            val k = if (rad <= 1f) (1.5f - d).coerceIn(0f, 1f) else ((rad - d) / (rad * 0.6f)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            if (k > 0f) { cover(yy * w + xx, k); if (mirror) cover(yy * w + w - 1 - xx, k) }
        }
    }
    private var base: IntArray? = null
    private var cov: FloatArray? = null
    /** Paints (or erases) under the brush along a line, from the stroke's starting picture. */
    private fun paintLine(c: IntArray, x0: Float, y0: Float, x1: Float, y1: Float, erase: Boolean) {
        val bs = base ?: return; val cv = cov ?: return
        val dist = hypot(x1 - x0, y1 - y0); val step = max(0.35f, brush / 5f); val n = max(1, (dist / step).toInt())
        for (k in 0..n) {
            val t = k / n.toFloat()
            dab(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t) { i, a ->
                if (a <= cv[i]) return@dab
                cv[i] = a
                val strength = a * opacity
                c[i] = if (erase) { val al = (PixelOps.a(bs[i]) * (1 - strength)).toInt(); if (al <= 2) 0 else (bs[i] and 0xFFFFFF) or (al shl 24) } else PixelOps.over(bs[i], color, strength)
            }
        }
    }
    /** The smoothing brush: blends each pixel under it toward its neighbours. */
    private fun smoothAt(c: IntArray, x: Float, y: Float) {
        val src = c.copyOf()
        dab(x, y) { i, a -> c[i] = PixelOps.mix(src[i], PixelOps.blurAt(src, doc.w, doc.h, i % doc.w, i / doc.w), a * opacity * 0.6f) }
    }
    private fun put(c: IntArray, x: Int, y: Int, col: Int) {
        dab(x + 0.5f, y + 0.5f) { i, _ -> c[i] = col }
    }
    private fun lineOn(c: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, col: Int) {
        var x = x0; var y = y0; val dx = abs(x1 - x0); val dy = -abs(y1 - y0); val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1; var err = dx + dy
        while (true) { put(c, x, y, col); if (x == x1 && y == y1) break; val e2 = 2 * err; if (e2 >= dy) { err += dy; x += sx }; if (e2 <= dx) { err += dx; y += sy } }
    }
    private fun boxOn(c: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, col: Int) {
        lineOn(c, x0, y0, x1, y0, col); lineOn(c, x1, y0, x1, y1, col); lineOn(c, x1, y1, x0, y1, col); lineOn(c, x0, y1, x0, y0, col)
    }
    private fun ovalOn(c: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, col: Int) {
        val l = min(x0, x1); val r = max(x0, x1); val t = min(y0, y1); val b = max(y0, y1)
        val cx = (l + r) / 2f; val cy = (t + b) / 2f; val rx = max(0.5f, (r - l) / 2f); val ry = max(0.5f, (b - t) / 2f)
        for (y in t..b) for (x in l..r) {
            val d = ((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it }
            val dIn = ((x - cx) / max(0.5f, rx - 1)).let { it * it } + ((y - cy) / max(0.5f, ry - 1)).let { it * it }
            if (d <= 1.05f && (dIn > 1f || rx < 1.5f || ry < 1.5f)) put(c, x, y, col)
        }
    }
    private fun fill(c: IntArray, x: Int, y: Int, col: Int) {
        if (x !in 0 until doc.w || y !in 0 until doc.h) return
        val target = c[y * doc.w + x]; if (target == col) return
        val stack = ArrayDeque<Int>(); stack.addLast(y * doc.w + x)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast(); if (c[i] != target) continue
            c[i] = col; val px = i % doc.w; val py = i / doc.w
            if (px > 0) stack.addLast(i - 1); if (px < doc.w - 1) stack.addLast(i + 1); if (py > 0) stack.addLast(i - doc.w); if (py < doc.h - 1) stack.addLast(i + doc.w)
        }
        if (mirror) { val mx = doc.w - 1 - x; if (c[y * doc.w + mx] == target) fill(c, mx, y, col) }
    }
    /** A fill whose edge blends into what's around it. */
    private fun softFill(c: IntArray, x: Int, y: Int, col: Int) {
        val before = c.copyOf(); fill(c, x, y, col)
        val w = doc.w; val h = doc.h
        val mask = BooleanArray(w * h)
        for (i in c.indices) if (c[i] != before[i]) {
            val px = i % w; val py = i / w
            for (dy in -1..1) for (dx in -1..1) { val xx = px + dx; val yy = py + dy; if (xx in 0 until w && yy in 0 until h) mask[yy * w + xx] = true }
        }
        PixelOps.smoothEdges(c, w, h, 0.6f, mask)
    }

    private var fx = 0f; private var fy = 0f
    fun strokeStart(x: Int, y: Int, px: Float = x + 0.5f, py: Float = y + 0.5f) {
        before = doc.copy(); sx = x; sy = y; lx = x; ly = y; fx = px; fy = py
        val c = doc.cell(dir, frame)
        base = c.copyOf(); cov = FloatArray(c.size)
        when (tool) {
            Tool.PEN -> paintLine(c, px, py, px, py, false)
            Tool.ERASE -> paintLine(c, px, py, px, py, true)
            Tool.SMOOTH -> smoothAt(c, px, py)
            Tool.FILL -> if (opacity < 1f) { val f0 = c.copyOf(); if (soft) softFill(c, x, y, color) else fill(c, x, y, color); for (i in c.indices) if (c[i] != f0[i]) c[i] = PixelOps.mix(f0[i], c[i], opacity) } else if (soft) softFill(c, x, y, color) else fill(c, x, y, color)
            Tool.PICK -> if (x in 0 until doc.w && y in 0 until doc.h) { val v = c[y * doc.w + x]; if (Color.alpha(v) > 0) { color = v; remember(v); tool = Tool.PEN; refreshColors(); refreshTools() } }
            Tool.MOVE -> moveBase = c.copyOf()
            else -> {}
        }
    }
    fun strokeMove(x: Int, y: Int, px: Float = x + 0.5f, py: Float = y + 0.5f) {
        val c = doc.cell(dir, frame)
        when (tool) {
            Tool.PEN -> { paintLine(c, fx, fy, px, py, false); fx = px; fy = py; lx = x; ly = y; return }
            Tool.ERASE -> { paintLine(c, fx, fy, px, py, true); fx = px; fy = py; lx = x; ly = y; return }
            Tool.SMOOTH -> { if (hypot(px - fx, py - fy) >= max(0.5f, brush / 4f)) { smoothAt(c, px, py); fx = px; fy = py }; return }
            else -> {}
        }
        if (x == lx && y == ly) return
        when (tool) {
            Tool.LINE, Tool.BOX, Tool.OVAL -> {
                val pv = c.copyOf()
                when (tool) { Tool.LINE -> lineOn(pv, sx, sy, x, y, color); Tool.BOX -> boxOn(pv, sx, sy, x, y, color); else -> ovalOn(pv, sx, sy, x, y, color) }
                preview = pv
            }
            Tool.MOVE -> moveBase?.let { base ->
                val dx = x - sx; val dy = y - sy
                for (yy in 0 until doc.h) for (xx in 0 until doc.w) { val ox = xx - dx; val oy = yy - dy; c[yy * doc.w + xx] = if (ox in 0 until doc.w && oy in 0 until doc.h) base[oy * doc.w + ox] else 0 }
            }
            else -> {}
        }
        lx = x; ly = y
    }
    fun strokeEnd() {
        preview?.let { it.copyInto(doc.cell(dir, frame)) }; preview = null; moveBase = null; base = null; cov = null
        val b = before ?: return; before = null
        if (tool == Tool.PICK) return
        undo.addLast(b); while (undo.size > 60) undo.removeFirst(); redo.clear()
        dirty = true; refreshTitle(); frameRow.invalidate(); for (i in 0 until frameRow.childCount) frameRow.getChildAt(i).invalidate()
    }
    fun strokeCancel() { before?.let { doc.restore(it) }; before = null; preview = null; moveBase = null; base = null; cov = null }

    private fun detailDialog() {
        val opts = arrayOf("×2 detail, smoothed", "×3 detail, smoothed", "×4 detail, smoothed", "×2 as plain blocks", "×4 as plain blocks", "Back to pixel art (×1)")
        AlertDialog.Builder(act).setTitle("Draw finer detail: the sprite keeps its size in the world, with more pixels to paint")
            .setItems(opts) { _, i ->
                if (i == 5) {
                    if (doc.density == 1) return@setItems
                    val k = doc.density
                    edit { doc.cells = Array(doc.dirs) { d -> ArrayList(doc.cells[d].map { PixelOps.boxDown(it, doc.w, doc.h, doc.w / k, doc.h / k).also { o -> for (j in o.indices) if (PixelOps.a(o[j]) < 110) o[j] = 0 else o[j] = o[j] or (0xFF shl 24) } }) }; doc.w /= k; doc.h /= k; doc.density = 1 }
                } else {
                    val k = intArrayOf(2, 3, 4, 2, 4)[i]; val smooth = i < 3
                    if (doc.w * k * doc.frames > Library.MAX_PX || doc.h * k * doc.dirs > Library.MAX_PX) { act.toastIt("Too big for that much detail"); return@setItems }
                    edit { doc.detail(k, smooth) }
                    if (brush == 1) brush = k
                    soft = true; refreshTools()
                }
                canvas.fit(); refreshTitle()
            }.show()
    }

    private fun smoothDialog() {
        AlertDialog.Builder(act).setTitle("Smooth the edges (where colours meet; flat areas stay put)")
            .setItems(arrayOf("This frame, gently", "This frame, strongly", "Every frame and direction, gently")) { _, i ->
                edit {
                    val cells = if (i == 2) doc.cells.flatMap { it } else listOf(doc.cell(dir, frame))
                    cells.forEach { c -> PixelOps.smoothEdges(c, doc.w, doc.h, if (i == 1) 0.8f else 0.5f); if (i == 1) PixelOps.smoothEdges(c, doc.w, doc.h, 0.5f) }
                }
            }.show()
    }

    /** A picture from anywhere on the phone, turned into a sprite with a live preview. */
    fun importImage(uri: android.net.Uri) {
        val bmp = runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            act.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1; while (o.outWidth / sample > 1600 || o.outHeight / sample > 1600) sample *= 2
            act.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
        }.getOrNull()
        if (bmp == null) { act.toastIt("Couldn't open that picture"); return }
        val src = PixelOps.source(bmp)
        val o = PixelOps.Convert(width = 48, detail = 1, colors = 24, background = 30, outline = true, punch = 30, smooth = false)
        var outW = 0; var outH = 0; var out = IntArray(0)
        val pv = object : View(act) {
            val pp = Paint().apply { isFilterBitmap = false }; val fp = Paint()
            override fun onDraw(c: Canvas) {
                fp.color = 0xFF2E2E3C.toInt(); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
                if (outW == 0) return
                val b = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888); b.setPixels(out, 0, outW, 0, 0, outW, outH)
                val k = min((width - 16f) / outW, (height - 16f) / outH)
                // shown at its world size x some zoom: detail sprites smoothed, pixel art crisp
                val dw = outW * k; val dh = outH * k
                c.drawBitmap(b, null, RectF(width / 2 - dw / 2, height / 2 - dh / 2, width / 2 + dw / 2, height / 2 + dh / 2), pp)
            }
        }
        val info = TextView(act).apply { textSize = 12f }
        fun recompute() {
            val bw = o.width.coerceAtLeast(4); val bh = max(4, (bw * src.h / src.w.toFloat()).toInt())
            outW = (bw * o.detail).coerceAtMost(Library.MAX_PX); outH = (bh * o.detail).coerceAtMost(Library.MAX_PX)
            out = PixelOps.convert(src, outW, outH, o)
            info.text = "${bw} × ${bh} in the world" + (if (o.detail > 1) "  (${outW} × ${outH} picture pixels)" else "") + (if (o.colors in 2..64) "  ${o.colors} colours" else "  all colours")
            pv.invalidate()
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16f), dpi(6f), dpi(16f), 0) }
        box.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(220f)))
        box.addView(info)
        fun slider(label: String, lo: Int, hi: Int, v: Int, show: (Int) -> String, on: (Int) -> Unit) {
            val t = TextView(act).apply { textSize = 12f; text = "$label: ${show(v)}" }
            box.addView(t)
            box.addView(SeekBar(act).apply { max = hi - lo; progress = v - lo; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { t.text = "$label: ${show(p + lo)}"; on(p + lo) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { recompute() } }) })
        }
        slider("Size (width in world pixels)", 8, 128, o.width, { "$it" }) { o.width = it }
        slider("Detail", 1, 4, o.detail, { if (it == 1) "×1 pixel art" else "×$it smooth detail" }) { o.detail = it; if (it > 1) o.smooth = true }
        slider("Colours", 2, 65, o.colors, { if (it > 64) "all" else "$it" }) { o.colors = it }
        slider("Remove background", 0, 100, o.background, { if (it == 0) "off" else "$it" }) { o.background = it }
        slider("Punch (colour and contrast)", 0, 100, o.punch, { "$it" }) { o.punch = it }
        val outlineBox = android.widget.CheckBox(act).apply { text = "Dark outline"; isChecked = o.outline; setOnCheckedChangeListener { _, b -> o.outline = b; recompute() } }
        val smoothBox = android.widget.CheckBox(act).apply { text = "Smooth edges (organic)"; isChecked = o.smooth; setOnCheckedChangeListener { _, b -> o.smooth = b; recompute() } }
        box.addView(outlineBox); box.addView(smoothBox)
        recompute()
        AlertDialog.Builder(act).setTitle("Turn a picture into a sprite").setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("New sprite") { _, _ ->
                push(); val d = PixDoc(outW, outH, 1, 1); d.density = o.detail; out.copyInto(d.cell(0, 0))
                doc = d; dir = 0; frame = 0; openedKey = null; dirty = true; cat = if (cat == "tiles" || cat == "walls") "props" else cat
                name = "imported"; if (o.detail > 1) { soft = true; brush = o.detail }
                canvas.fit(); refreshAll()
            }
            .setNeutralButton("Into this frame") { _, _ ->
                // fitted inside the current frame at its own detail, standing on the bottom edge
                val oo = PixelOps.Convert(o.width, doc.density, o.colors, o.background, o.outline, o.punch, o.smooth)
                val pic = PixelOps.convert(src, doc.w, doc.h, oo)
                edit { val c = doc.cell(dir, frame); for (i in c.indices) if (PixelOps.a(pic[i]) > 0) c[i] = PixelOps.over(c[i], pic[i], 1f) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun outlineDialog() {
        AlertDialog.Builder(act).setTitle("Outline in the current colour")
            .setItems(arrayOf("This frame", "Every frame and direction")) { _, i ->
                edit { val cells = if (i == 0) listOf(doc.cell(dir, frame)) else doc.cells.flatMap { it }; cells.forEach { c -> repeat(doc.density) { outline(c, color) } } }
            }.show()
    }
    private fun outline(c: IntArray, col: Int) {
        val w = doc.w; val h = doc.h; val src = c.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(src[y * w + x]) != 0) continue
            if ((x > 0 && Color.alpha(src[y * w + x - 1]) > 0) || (x < w - 1 && Color.alpha(src[y * w + x + 1]) > 0) || (y > 0 && Color.alpha(src[(y - 1) * w + x]) > 0) || (y < h - 1 && Color.alpha(src[(y + 1) * w + x]) > 0)) c[y * w + x] = col
        }
    }

    // ---- new, open, size, save
    private fun spinner(items: List<String>, sel: Int = 0) = Spinner(act).apply { adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, items); setSelection(sel) }
    private fun cap(s: String) = TextView(act).apply { text = s; textSize = 13f; setPadding(0, dpi(10f), 0, dpi(2f)) }

    private fun newDialog() {
        val kinds = listOf("Character: 4 directions, walking" to "characters", "Monster or creature: 4 directions" to "characters", "Prop (stands on the floor)" to "props", "Decor" to "decor", "Kiosk" to "kiosks", "Item" to "items", "Floor tile (16 × 16)" to "tiles", "Wall tile (16 × 16)" to "walls")
        val sizes = listOf("16 × 16", "24 × 24  (characters)", "32 × 32", "32 × 48", "48 × 48", "64 × 64", "96 × 96", "128 × 128", "Custom…")
        val dims = listOf(16 to 16, 24 to 24, 32 to 32, 32 to 48, 48 to 48, 64 to 64, 96 to 96, 128 to 128)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(6f), dpi(20f), 0) }
        val kind = spinner(kinds.map { it.first }); box.addView(cap("What are you making?")); box.addView(kind)
        val size = spinner(sizes, 1); box.addView(cap("Size in pixels (zoom in to draw):")); box.addView(size)
        val cw = EditText(act).apply { hint = "width"; inputType = InputType.TYPE_CLASS_NUMBER }; val ch = EditText(act).apply { hint = "height"; inputType = InputType.TYPE_CLASS_NUMBER }
        val custom = LinearLayout(act).apply { addView(cw, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(ch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        box.addView(custom)
        val frames = spinner(listOf("1 frame (still)", "2 frames", "3 frames (walk: stand, step, step)", "4 frames", "6 frames", "8 frames"), 2)
        box.addView(cap("Frames (per direction):")); box.addView(frames)
        val det = spinner(listOf("×1: classic pixel art", "×2 detail", "×3 detail", "×4 detail: smooth, organic"), 0)
        box.addView(cap("Detail (more pixels per world pixel, drawn at the same size in the world):")); box.addView(det)
        val nm = EditText(act).apply { setText("my_sprite"); inputType = InputType.TYPE_CLASS_TEXT }
        box.addView(cap("Name:")); box.addView(nm)
        kind.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, i: Int, id: Long) {
                if (i >= 6) { size.setSelection(0); frames.setSelection(0) } else if (i <= 1) { size.setSelection(1); frames.setSelection(2) } else { size.setSelection(2); frames.setSelection(0) }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        AlertDialog.Builder(act).setTitle("New sprite").setView(android.widget.ScrollView(act).apply { addView(box) }).setPositiveButton("Make it") { _, _ ->
            val k = kind.selectedItemPosition
            var (w, h) = dims.getOrElse(size.selectedItemPosition) { (cw.text.toString().toIntOrNull() ?: 32) to (ch.text.toString().toIntOrNull() ?: 32) }
            if (k >= 6) { w = 16; h = 16 }
            w = w.coerceIn(4, Library.MAX_PX); h = h.coerceIn(4, Library.MAX_PX)
            val f = intArrayOf(1, 2, 3, 4, 6, 8)[frames.selectedItemPosition]
            val dk = det.selectedItemPosition + 1
            if (w * dk * f > Library.MAX_PX || h * dk * (if (k <= 1) 4 else 1) > Library.MAX_PX) { act.toastIt("Too big at that detail"); return@setPositiveButton }
            val d = PixDoc(w * dk, h * dk, if (k <= 1) 4 else 1, f); d.density = dk
            if (dk > 1) { soft = true; brush = dk } else { soft = false; brush = 1 }
            push(); doc = d; dir = 0; frame = 0; cat = kinds[k].second; openedKey = null; dirty = true
            name = nm.text.toString().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifEmpty { "my_sprite" }
            canvas.fit(); refreshAll()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun openDialog() {
        val all = Library.SPRITES.flatMap { c -> lib.list(c) }.filter { it.bmp != null }
        if (all.isEmpty()) { act.toastIt("No sprites in the folder yet"); return }
        AlertDialog.Builder(act).setTitle("Open a sprite (Save As keeps the original)").setItems(all.map { "${it.cat}/${it.name}  (${it.frameW}×${it.frameH})" }.toTypedArray()) { _, i ->
            push(); open(all[i].key)
        }.show()
    }

    private fun sizeDialog() {
        val opts = arrayOf("Canvas size (keeps the pixels, anchored at the feet)…", "Scale × 2", "Scale × 1.5", "Scale × 0.5", "Make it a 4-direction character", "Make it a single picture (keep ▼ only)")
        AlertDialog.Builder(act).setTitle("Size: ${doc.w} × ${doc.h}").setItems(opts) { _, i ->
            when (i) {
                0 -> {
                    val box = LinearLayout(act).apply { setPadding(dpi(20f), dpi(6f), dpi(20f), 0) }
                    val w = EditText(act).apply { setText(doc.w.toString()); inputType = InputType.TYPE_CLASS_NUMBER }; val h = EditText(act).apply { setText(doc.h.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
                    box.addView(w, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); box.addView(h, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    AlertDialog.Builder(act).setTitle("Width × height").setView(box).setPositiveButton("OK") { _, _ ->
                        val nw = (w.text.toString().toIntOrNull() ?: doc.w).coerceIn(4, Library.MAX_PX); val nh = (h.text.toString().toIntOrNull() ?: doc.h).coerceIn(4, Library.MAX_PX)
                        edit { doc.resize(nw, nh) }; canvas.fit()
                    }.setNegativeButton("Cancel", null).show()
                }
                1, 2, 3 -> { val k = floatArrayOf(2f, 1.5f, 0.5f)[i - 1]; if (doc.density > 1) { act.toastIt("Use DETAIL to change a detail sprite's resolution"); return@setItems }; if (doc.w * k > Library.MAX_PX || doc.h * k > Library.MAX_PX) act.toastIt("Too big") else { edit { doc.scale(k) }; canvas.fit() } }
                4 -> if (doc.dirs == 1) { edit { val base = doc.cells[0]; doc.cells = Array(4) { d -> ArrayList(base.map { it.copyOf() }) }; doc.dirs = 4; if (cat != "characters") cat = "characters" } }
                5 -> if (doc.dirs == 4) { edit { doc.cells = arrayOf(doc.cells[0]); doc.dirs = 1; dir = 0 } }
            }
            refreshAll()
        }.show()
    }

    private fun fileName() = name + (if (doc.dirs == 4) "_4dir" else "") + (if (doc.frames > 1) "_f${doc.frames}" else "") + (if (doc.density > 1) "@${doc.density}x" else "") + ".png"

    private fun save(askName: Boolean) {
        if (askName || openedKey == null && name == "new_character") {
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(6f), dpi(20f), 0) }
            val nm = EditText(act).apply { setText(name); inputType = InputType.TYPE_CLASS_TEXT }
            val cats = Library.SPRITES
            val cs = spinner(cats, cats.indexOf(cat).coerceAtLeast(0))
            box.addView(cap("Name:")); box.addView(nm); box.addView(cap("Folder:")); box.addView(cs)
            AlertDialog.Builder(act).setTitle("Save as").setView(box).setPositiveButton("Save") { _, _ ->
                name = nm.text.toString().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifEmpty { "my_sprite" }
                cat = cats[cs.selectedItemPosition]; openedKey = null; write()
            }.setNegativeButton("Cancel", null).show()
        } else write()
    }

    private fun write() {
        val bmp = doc.toBitmap()
        val bytes = java.io.ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        val file = fileName(); val c = cat; val old = openedKey
        Thread {
            val err = runCatching {
                val dirId = ws.dir(Library.folderOf(c)) ?: throw IllegalStateException("no folder")
                ws.write(dirId, file, "image/png", bytes)
                // the same sprite saved under a new frame count: replace the old sheet rather than leave a twin
                val oldName = old?.let { lib.get(it)?.fileName }
                if (old != null && old.substringBefore('/') == c && oldName != null && oldName != file) ws.list(dirId).firstOrNull { it.name == oldName }?.let { ws.delete(it.id) }
            }.exceptionOrNull()
            act.runOnUiThread {
                if (err != null) act.toastIt("Couldn't save: ${err.message}")
                else { openedKey = "$c/" + file.removeSuffix(".png").replace(Regex("@[1-8]x$"), ""); dirty = false; refreshTitle(); act.toastIt("Saved $c/$file"); lib.rescan() }
            }
        }.start()
    }

    private fun close() {
        if (!dirty) { onClose(); return }
        AlertDialog.Builder(act).setMessage("Leave without saving?").setPositiveButton("Save first") { _, _ -> save(false); onClose() }
            .setNegativeButton("Leave") { _, _ -> onClose() }.setNeutralButton("Stay", null).show()
    }
}
