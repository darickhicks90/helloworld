package app.foxbox.pocket.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Selections, as masks in art space: white where selected, clear elsewhere, covering only the
 * box around the selection (`l`, `t` is where the mask sits).
 */
class Selection(val mask: Bitmap, val l: Int, val t: Int) {
    val r get() = Rect(l, t, l + mask.width, t + mask.height)
}

object Select {
    /** A freehand loop: everything inside it. `pts` are art-space x, y pairs. */
    fun lasso(pts: FloatArray, n: Int, maxW: Int, maxH: Int, ox: Float, oy: Float): Selection? {
        if (n < 6) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n step 2) { minX = min(minX, pts[i]); maxX = max(maxX, pts[i]); minY = min(minY, pts[i + 1]); maxY = max(maxY, pts[i + 1]) }
        // stay on the canvas (art space shifted by the origin)
        val l = max(minX.toInt() - 1, (-ox).toInt()); val t = max(minY.toInt() - 1, (-oy).toInt())
        val r = min(maxX.toInt() + 2, (maxW - ox).toInt()); val b = min(maxY.toInt() + 2, (maxH - oy).toInt())
        if (r - l < 2 || b - t < 2) return null
        val m = Bitmap.createBitmap(r - l, b - t, Bitmap.Config.ARGB_8888)
        val path = Path()
        path.moveTo(pts[0] - l, pts[1] - t)
        for (i in 2 until n step 2) path.lineTo(pts[i] - l, pts[i + 1] - t)
        path.close()
        Canvas(m).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return Selection(m, l, t)
    }

    /**
     * The magic lasso: loop roughly around an object and it finds the object itself. The colours
     * along the loop are taken as the background; anything inside the loop that can be reached
     * from the loop through background-coloured pixels is background, and the rest is the object
     * (so an outlined drawing keeps its insides, even where they match the paper). `layer` is the
     * layer's pixels on the canvas (w × h); `ox`, `oy` the art-space origin; `tol` 10 (tight) … 80 (loose).
     */
    fun magic(layer: Bitmap, bg: Int, pts: FloatArray, n: Int, ox: Float, oy: Float, tol: Int): Selection? {
        val loop = lasso(pts, n, layer.width, layer.height, ox, oy) ?: return null
        val bw = loop.mask.width; val bh = loop.mask.height
        // work at up to 1024 px across for speed, then scale the result back up
        val s = min(1f, 1024f / max(bw, bh))
        val w = max(2, (bw * s).toInt()); val h = max(2, (bh * s).toInt())
        // the layer's pixels inside the loop's box, over the paper colour (so clear pixels read as paper)
        val crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(crop).apply {
            drawColor(if (bg == 0) Color.WHITE else bg)
            scale(s, s); translate(-(loop.l + ox), -(loop.t + oy))
            drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(w * h); crop.getPixels(px, 0, w, 0, 0, w, h); crop.recycle()
        val inLoop = Bitmap.createScaledBitmap(loop.mask, w, h, true)
        val lm = IntArray(w * h); inLoop.getPixels(lm, 0, w, 0, 0, w, h); inLoop.recycle()
        val inside = BooleanArray(w * h) { Color.alpha(lm[it]) > 127 }

        // the background colours: sampled along the loop's edge (pixels inside whose neighbour is outside)
        val samples = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!inside[i]) continue
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 || !inside[i - 1] || !inside[i + 1] || !inside[i - w] || !inside[i + w]
            if (edge) samples += px[i]
        }
        if (samples.isEmpty()) return loop
        // a few representative colours (coarse buckets, the most common first)
        val buckets = HashMap<Int, IntArray>()
        for (c in samples) {
            val key = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
            val acc = buckets.getOrPut(key) { IntArray(4) }
            acc[0] += Color.red(c); acc[1] += Color.green(c); acc[2] += Color.blue(c); acc[3]++
        }
        val palette = buckets.values.sortedByDescending { it[3] }.filter { it[3] * 50 >= samples.size }.take(8)
            .map { intArrayOf(it[0] / it[3], it[1] / it[3], it[2] / it[3]) }
        fun isBg(c: Int): Boolean {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            for (p in palette) if (abs(r - p[0]) + abs(g - p[1]) + abs(b - p[2]) <= tol * 2) return true
            return false
        }
        // flood the background in from the loop's edge
        val background = BooleanArray(w * h)
        val stack = IntArrayStack()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!inside[i]) { background[i] = true; continue }
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 || !inside[i - 1] || !inside[i + 1] || !inside[i - w] || !inside[i + w]
            if (edge && isBg(px[i])) { background[i] = true; stack.push(i) }
        }
        while (stack.size > 0) {
            val i = stack.pop(); val x = i % w; val y = i / w
            fun visit(j: Int) { if (!background[j] && inside[j] && isBg(px[j])) { background[j] = true; stack.push(j) } }
            if (x > 0) visit(i - 1); if (x < w - 1) visit(i + 1); if (y > 0) visit(i - w); if (y < h - 1) visit(i + w)
        }
        // the object, grown by a pixel to take in its soft (anti-aliased) edge
        val obj = BooleanArray(w * h) { inside[it] && !background[it] }
        val grown = obj.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (obj[i]) continue
            if ((x > 0 && obj[i - 1]) || (x < w - 1 && obj[i + 1]) || (y > 0 && obj[i - w]) || (y < h - 1 && obj[i + w])) grown[i] = inside[i]
        }
        if (grown.none { it }) return null
        val out = IntArray(w * h) { if (grown[it]) Color.WHITE else 0 }
        val small = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val full = if (w == bw && h == bh) small else Bitmap.createScaledBitmap(small, bw, bh, true).also { small.recycle() }
        loop.mask.recycle()
        return Selection(full, loop.l, loop.t)
    }

    /** The pixels of `layer` under the selection, as their own picture (for lifting onto a new layer). */
    fun cut(layer: Bitmap, sel: Selection, ox: Float, oy: Float): Bitmap {
        val out = Bitmap.createBitmap(sel.mask.width, sel.mask.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(layer, -(sel.l + ox), -(sel.t + oy), null)
        c.drawBitmap(sel.mask, 0f, 0f, Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) })
        return out
    }
}

/** A growable stack of ints (no boxing). */
class IntArrayStack {
    private var a = IntArray(4096)
    var size = 0; private set
    fun push(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun pop(): Int = a[--size]
}
