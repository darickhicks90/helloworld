package app.foxbox.pocket.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** The drawing board's tools. */
object Tool {
    const val PEN = 0; const val PENCIL = 1; const val MARKER = 2; const val AIR = 3; const val ERASER = 4
    const val FILL = 5; const val LINE = 6; const val BOX = 7; const val OVAL = 8; const val PICK = 9
    const val LASSO = 10; const val MAGIC = 11; const val MOVE = 12
    val NAMES = arrayOf("Pen", "Pencil", "Marker", "Air", "Eraser", "Fill", "Line", "Box", "Oval", "Pick", "Lasso", "Magic", "Move")
    fun isBrush(t: Int) = t <= ERASER
    fun isShape(t: Int) = t == LINE || t == BOX || t == OVAL
}

/**
 * One step of a drawing. A drawing is kept as its steps (not as pixels), so undo is exact, a long
 * animation doesn't have to sit in memory, and every picture can be rebuilt at any size.
 * Coordinates are in "art space"; the document's origin places art space on the canvas.
 */
sealed class Op {
    class Stroke(val tool: Int, val color: Int, val size: Float, val opacity: Float, val pts: FloatArray) : Op()
    /** Flood fill from (x, y) with a colour; `tol` how different a neighbour may be (0..255). */
    class Fill(val x: Float, val y: Float, val color: Int, val tol: Int) : Op()
    /** A picture (stored as a PNG in the drawing's folder) drawn into a rectangle. */
    class Image(val id: String, val l: Float, val t: Float, val w: Float, val h: Float) : Op()
    /** A selection: remove what's inside it (keep = false) or everything outside it (keep = true). */
    class Mask(val id: String, val l: Int, val t: Int, val keep: Boolean) : Op()
    /** Move everything drawn so far on this layer. */
    class Shift(val dx: Float, val dy: Float) : Op()
}

class Layer(var name: String, var animated: Boolean) {
    var visible = true
    var opacity = 1f
    /** Animated layers have one list of steps per frame; still layers (backgrounds) one for all. */
    val cels = ArrayList<MutableList<Op>>()
    fun cel(frame: Int): MutableList<Op> {
        val i = if (animated) frame else 0
        while (cels.size <= i) cels.add(ArrayList())
        return cels[i]
    }
}

/**
 * A drawing: its size, what kind it is (a sketch, a 4K animation frame, or an asset for a 4K
 * project), its background, its layers and frames, and the speed it plays at.
 */
class ArtDoc(val dir: File) {
    var w = 2000; var h = 1500
    /** Art space → canvas (changes when the canvas is resized around its middle). */
    var ox = 0f; var oy = 0f
    /** 0 = transparent (assets), otherwise the paper colour. */
    var bg = Color.WHITE
    var kind = KIND_SKETCH
    var fps = 12
    var frames = 1
    var title = "Drawing"
    val layers = ArrayList<Layer>()

    companion object {
        const val KIND_SKETCH = "sketch"
        const val KIND_4K_FRAME = "4k-frame"
        const val KIND_4K_ASSET = "4k-asset"
        /** The full 4K frame the assets are made for. */
        const val UHD_W = 3840; const val UHD_H = 2160
        const val MAX_SIDE = 4096
        fun newId() = java.util.UUID.randomUUID().toString().take(12)
    }

    val is4k get() = kind != KIND_SKETCH
    /** How many layers memory allows (each full-size layer is w × h × 4 bytes). */
    val maxLayers get() = if (w.toLong() * h > 6_000_000L) 4 else 6

    // ---------------------------------------------------------------- frames
    fun insertFrame(at: Int, copyOf: Int?) {
        for (l in layers) if (l.animated) {
            while (l.cels.size < frames) l.cels.add(ArrayList())
            val src = copyOf?.let { ArrayList(l.cel(it)) } ?: ArrayList()
            l.cels.add(at.coerceIn(0, l.cels.size), src)
        }
        frames++
    }
    fun deleteFrame(at: Int): List<Pair<Layer, MutableList<Op>>> {
        val removed = ArrayList<Pair<Layer, MutableList<Op>>>()
        for (l in layers) if (l.animated && at < l.cels.size) removed += l to l.cels.removeAt(at)
        frames = max(1, frames - 1)
        return removed
    }

    // ---------------------------------------------------------------- pictures and masks on disk
    private val imgDir get() = File(dir, "img").apply { mkdirs() }
    private val bmpCache = object : LruCache<String, Bitmap>(96 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    fun saveBitmap(id: String, b: Bitmap) { File(imgDir, "$id.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmpCache.put(id, b) }
    fun bitmap(id: String): Bitmap? = bmpCache.get(id) ?: runCatching { BitmapFactory.decodeFile(File(imgDir, "$id.png").path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }.getOrNull()?.also { bmpCache.put(id, it) }

    // ---------------------------------------------------------------- drawing the steps
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    private val r = RectF(); private val ri = Rect()

    /** Rebuild a cel: `bmp` is the canvas (w × h, or scaled by `scale` for previews). */
    fun replay(ops: List<Op>, bmp: Bitmap, scale: Float = 1f) {
        bmp.eraseColor(0)
        val c = Canvas(bmp)
        c.scale(scale, scale); c.translate(ox, oy)
        for (op in ops) apply(c, bmp, op, scale)
    }

    fun apply(c: Canvas, bmp: Bitmap, op: Op, scale: Float = 1f) {
        when (op) {
            is Op.Stroke -> stroke(c, op)
            is Op.Image -> bitmap(op.id)?.let { b -> r.set(op.l, op.t, op.l + op.w, op.t + op.h); ip.xfermode = null; c.drawBitmap(b, null, r, ip) }
            is Op.Mask -> bitmap(op.id)?.let { m ->
                r.set(op.l.toFloat(), op.t.toFloat(), (op.l + m.width).toFloat(), (op.t + m.height).toFloat())
                if (op.keep) {
                    // clear everything outside the mask's box, then keep only what the mask covers inside it
                    sp.reset(); sp.xfermode = clear
                    val big = 1e6f
                    c.drawRect(-big, -big, big, r.top, sp); c.drawRect(-big, r.bottom, big, big, sp)
                    c.drawRect(-big, r.top, r.left, r.bottom, sp); c.drawRect(r.right, r.top, big, r.bottom, sp)
                    ip.xfermode = dstIn; c.drawBitmap(m, null, r, ip)
                } else { ip.xfermode = dstOut; c.drawBitmap(m, null, r, ip) }
                ip.xfermode = null
            }
            is Op.Shift -> {
                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                bmp.eraseColor(0)
                val cc = Canvas(bmp)
                cc.drawBitmap(copy, op.dx * scale, op.dy * scale, null)
                copy.recycle()
            }
            is Op.Fill -> fill(bmp, ((op.x + ox) * scale).toInt(), ((op.y + oy) * scale).toInt(), op.color, op.tol)
        }
    }

    fun stroke(c: Canvas, s: Op.Stroke) {
        val n = s.pts.size / 3
        if (n == 0) return
        sp.reset(); sp.isAntiAlias = true; sp.style = Paint.Style.STROKE; sp.strokeCap = Paint.Cap.ROUND; sp.strokeJoin = Paint.Join.ROUND
        val alpha = (s.opacity * 255).toInt().coerceIn(1, 255)
        val layered = s.tool != Tool.ERASER && alpha < 255
        if (layered) c.saveLayerAlpha(-1e6f, -1e6f, 1e6f, 1e6f, alpha)
        sp.color = s.color or 0xFF000000.toInt()
        if (s.tool == Tool.ERASER) { sp.xfermode = clear; sp.color = 0xFF000000.toInt(); if (alpha < 255) sp.alpha = alpha }
        if (s.tool == Tool.AIR) sp.maskFilter = BlurMaskFilter(max(1f, s.size * 0.45f), BlurMaskFilter.Blur.NORMAL)
        if (s.tool == Tool.MARKER) sp.strokeCap = Paint.Cap.SQUARE
        val q = s.pts
        if (Tool.isShape(s.tool)) {
            sp.strokeWidth = s.size
            val x0 = q[0]; val y0 = q[1]; val x1 = q[(n - 1) * 3]; val y1 = q[(n - 1) * 3 + 1]
            when (s.tool) {
                Tool.LINE -> c.drawLine(x0, y0, x1, y1, sp)
                Tool.BOX -> c.drawRect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), sp)
                else -> c.drawOval(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), sp)
            }
        } else if (n == 1) {
            sp.style = Paint.Style.FILL
            c.drawCircle(q[0], q[1], s.size * width(s.tool, q[2]) / 2, sp)
        } else for (i in 1 until n) {
            sp.strokeWidth = max(0.6f, s.size * width(s.tool, (q[i * 3 + 2] + q[i * 3 - 1]) / 2))
            c.drawLine(q[i * 3 - 3], q[i * 3 - 2], q[i * 3], q[i * 3 + 1], sp)
        }
        if (layered) c.restore()
    }

    /** How pressure (or, with a finger, speed) shapes each brush. */
    fun width(t: Int, pr: Float) = when (t) { Tool.PEN -> 0.3f + 0.7f * pr; Tool.PENCIL -> 0.6f + 0.4f * pr; else -> 1f }

    /** Scanline flood fill of the area around (x, y) whose colour is within `tol` of the seed's. */
    private fun fill(bmp: Bitmap, x: Int, y: Int, color: Int, tol: Int) {
        val w = bmp.width; val h = bmp.height
        if (x !in 0 until w || y !in 0 until h) return
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val seed = px[y * w + x]
        val fillC = color or 0xFF000000.toInt()
        if (seed == fillC) return
        fun near(c: Int): Boolean {
            if (c == seed) return true
            return abs(Color.alpha(c) - Color.alpha(seed)) <= tol && abs(Color.red(c) - Color.red(seed)) <= tol &&
                abs(Color.green(c) - Color.green(seed)) <= tol && abs(Color.blue(c) - Color.blue(seed)) <= tol
        }
        val done = BooleanArray(w * h)
        val stack = IntArrayStack()
        stack.push(y * w + x)
        while (stack.size > 0) {
            val i = stack.pop()
            if (done[i]) continue
            var lx = i % w; val yy = i / w; var rx = lx
            while (lx > 0 && !done[yy * w + lx - 1] && near(px[yy * w + lx - 1])) lx--
            while (rx < w - 1 && !done[yy * w + rx + 1] && near(px[yy * w + rx + 1])) rx++
            for (xx in lx..rx) {
                val k = yy * w + xx; done[k] = true; px[k] = fillC
                if (yy > 0) { val u = k - w; if (!done[u] && near(px[u])) stack.push(u) }
                if (yy < h - 1) { val d = k + w; if (!done[d] && near(px[d])) stack.push(d) }
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    // ---------------------------------------------------------------- saving
    fun toJson(): JSONObject = JSONObject().put("v", 1).put("w", w).put("h", h).put("ox", ox.toDouble()).put("oy", oy.toDouble())
        .put("bg", bg).put("kind", kind).put("fps", fps).put("frames", frames).put("title", title)
        .put("layers", JSONArray(layers.map { l ->
            JSONObject().put("name", l.name).put("animated", l.animated).put("visible", l.visible).put("opacity", l.opacity.toDouble())
                .put("cels", JSONArray(l.cels.map { cel -> JSONArray(cel.map { opJson(it) }) }))
        }))

    private fun opJson(op: Op): JSONObject = when (op) {
        is Op.Stroke -> JSONObject().put("k", "s").put("t", op.tool).put("c", op.color).put("s", op.size.toDouble()).put("o", op.opacity.toDouble())
            .put("p", JSONArray().apply { op.pts.forEach { v -> put((v * 10).toInt() / 10.0) } })
        is Op.Fill -> JSONObject().put("k", "f").put("x", op.x.toDouble()).put("y", op.y.toDouble()).put("c", op.color).put("tol", op.tol)
        is Op.Image -> JSONObject().put("k", "i").put("id", op.id).put("l", op.l.toDouble()).put("t", op.t.toDouble()).put("w", op.w.toDouble()).put("h", op.h.toDouble())
        is Op.Mask -> JSONObject().put("k", "m").put("id", op.id).put("l", op.l).put("t", op.t).put("keep", op.keep)
        is Op.Shift -> JSONObject().put("k", "sh").put("dx", op.dx.toDouble()).put("dy", op.dy.toDouble())
    }

    private fun opFrom(o: JSONObject): Op? = when (o.optString("k", "s")) {
        "s" -> o.getJSONArray("p").let { pa -> Op.Stroke(o.getInt("t"), o.getInt("c"), o.getDouble("s").toFloat(), o.getDouble("o").toFloat(), FloatArray(pa.length()) { pa.getDouble(it).toFloat() }) }
        "f" -> Op.Fill(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getInt("c"), o.optInt("tol", 24))
        "i" -> Op.Image(o.getString("id"), o.getDouble("l").toFloat(), o.getDouble("t").toFloat(), o.getDouble("w").toFloat(), o.getDouble("h").toFloat())
        "m" -> Op.Mask(o.getString("id"), o.getInt("l"), o.getInt("t"), o.optBoolean("keep"))
        "sh" -> Op.Shift(o.getDouble("dx").toFloat(), o.getDouble("dy").toFloat())
        else -> null
    }

    fun save() { dir.mkdirs(); File(dir, "doc.json").writeText(toJson().toString()) }

    fun load(): Boolean {
        val o = runCatching { JSONObject(File(dir, "doc.json").readText()) }.getOrNull() ?: return false
        w = o.getInt("w"); h = o.getInt("h"); ox = o.optDouble("ox", 0.0).toFloat(); oy = o.optDouble("oy", 0.0).toFloat()
        bg = o.optInt("bg", Color.WHITE); kind = o.optString("kind", KIND_SKETCH); fps = o.optInt("fps", 12)
        frames = o.optInt("frames", 1).coerceAtLeast(1); title = o.optString("title", "Drawing")
        layers.clear()
        val la = o.getJSONArray("layers")
        for (i in 0 until la.length()) {
            val lo = la.getJSONObject(i)
            val l = Layer(lo.optString("name", "Layer ${i + 1}"), lo.optBoolean("animated", true))
            l.visible = lo.optBoolean("visible", true); l.opacity = lo.optDouble("opacity", 1.0).toFloat()
            val cels = lo.optJSONArray("cels") ?: JSONArray()
            for (k in 0 until cels.length()) {
                val ca = cels.getJSONArray(k)
                l.cels.add((0 until ca.length()).mapNotNull { opFrom(ca.getJSONObject(it)) }.toMutableList())
            }
            layers += l
        }
        return true
    }

    /** Bring in a drawing made on the notebook's original drawing board (its strokes are the same). */
    fun importOldSketch(o: JSONObject) {
        w = o.optInt("w", 2000); h = o.optInt("h", 1500)
        val arr = o.optJSONArray("layers") ?: return
        // the old board's tools: pen 0 … eraser 4 are the same; line/box/oval/pick were 5–8
        fun tool(t: Int) = when (t) { 5 -> Tool.LINE; 6 -> Tool.BOX; 7 -> Tool.OVAL; else -> t }
        for (i in 0 until arr.length()) {
            val lo = arr.getJSONObject(i)
            val l = Layer(lo.optString("name", "Layer ${i + 1}"), false)
            l.visible = lo.optBoolean("visible", true); l.opacity = lo.optDouble("opacity", 1.0).toFloat()
            val ops = lo.optJSONArray("ops") ?: JSONArray(); val cel = l.cel(0)
            for (k in 0 until ops.length()) {
                val so = ops.getJSONObject(k); val pa = so.getJSONArray("p")
                cel += Op.Stroke(tool(so.getInt("t")), so.getInt("c"), so.getDouble("s").toFloat(), so.getDouble("o").toFloat(), FloatArray(pa.length()) { pa.getDouble(it).toFloat() })
            }
            layers += l
        }
    }
}
