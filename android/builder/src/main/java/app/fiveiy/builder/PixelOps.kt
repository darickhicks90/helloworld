package app.fiveiy.builder

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Picture maths for the studio: blending colours, smoothing jagged edges, upscaling pixel art
 * so its edges round off (Scale2x / EPX), and turning any picture into a sprite (fit, remove the
 * background, reduce the colours, outline).
 */
object PixelOps {
    fun a(c: Int) = c ushr 24; fun r(c: Int) = (c shr 16) and 255; fun g(c: Int) = (c shr 8) and 255; fun b(c: Int) = c and 255
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** src painted over dst at strength k (0..1, times src's own alpha). */
    fun over(dst: Int, src: Int, k: Float): Int {
        val sa = a(src) / 255f * k; if (sa <= 0f) return dst
        val da = a(dst) / 255f; val oa = sa + da * (1 - sa); if (oa <= 0f) return 0
        fun ch(s: Int, d: Int) = ((s * sa + d * da * (1 - sa)) / oa).toInt()
        return argb((oa * 255).toInt(), ch(r(src), r(dst)), ch(g(src), g(dst)), ch(b(src), b(dst)))
    }
    /** Mix two colours (alpha-aware) by t. */
    fun mix(c0: Int, c1: Int, t: Float): Int {
        val a0 = a(c0) / 255f; val a1 = a(c1) / 255f; val oa = a0 + (a1 - a0) * t
        if (oa <= 0.002f) return 0
        fun ch(x0: Int, x1: Int) = ((x0 * a0 * (1 - t) + x1 * a1 * t) / oa).toInt()
        return argb((oa * 255).toInt(), ch(r(c0), r(c1)), ch(g(c0), g(c1)), ch(b(c0), b(c1)))
    }
    /** The 1-2-1 weighted average around (x, y), alpha-aware. */
    fun blurAt(src: IntArray, w: Int, h: Int, x: Int, y: Int): Int {
        var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f; var wt = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
            val k = (if (dx == 0) 2f else 1f) * (if (dy == 0) 2f else 1f)
            val c = src[yy * w + xx]; val al = a(c) / 255f
            sa += al * k; sr += r(c) * al * k; sg += g(c) * al * k; sb += b(c) * al * k; wt += k
        }
        if (sa <= 0.001f) return 0
        return argb((sa / wt * 255).toInt(), (sr / sa).toInt(), (sg / sa).toInt(), (sb / sa).toInt())
    }
    fun differs(c0: Int, c1: Int) = kotlin.math.abs(a(c0) - a(c1)) > 40 || (a(c0) > 0 && a(c1) > 0 && kotlin.math.abs(r(c0) - r(c1)) + kotlin.math.abs(g(c0) - g(c1)) + kotlin.math.abs(b(c0) - b(c1)) > 60)

    /** Softens only where colours meet (edges and outlines), leaving flat areas crisp. */
    fun smoothEdges(c: IntArray, w: Int, h: Int, strength: Float = 0.55f, mask: BooleanArray? = null) {
        val src = c.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (mask != null && !mask[i]) continue
            val p = src[i]
            val edge = (x > 0 && differs(p, src[i - 1])) || (x < w - 1 && differs(p, src[i + 1])) || (y > 0 && differs(p, src[i - w])) || (y < h - 1 && differs(p, src[i + w]))
            if (edge) c[i] = mix(p, blurAt(src, w, h, x, y), strength)
        }
    }

    /** Scale2x (EPX): doubles pixel art, rounding off diagonal steps instead of making blocks. */
    fun scale2x(src: IntArray, w: Int, h: Int): IntArray {
        val o = IntArray(w * 2 * h * 2); val ow = w * 2
        for (y in 0 until h) for (x in 0 until w) {
            val p = src[y * w + x]
            val A = if (y > 0) src[(y - 1) * w + x] else p; val B = if (x < w - 1) src[y * w + x + 1] else p
            val C = if (x > 0) src[y * w + x - 1] else p; val D = if (y < h - 1) src[(y + 1) * w + x] else p
            o[(2 * y) * ow + 2 * x] = if (C == A && C != D && A != B) A else p
            o[(2 * y) * ow + 2 * x + 1] = if (A == B && A != C && B != D) B else p
            o[(2 * y + 1) * ow + 2 * x] = if (D == C && D != B && C != A) C else p
            o[(2 * y + 1) * ow + 2 * x + 1] = if (B == D && B != A && D != C) D else p
        }
        return o
    }
    fun nearest(src: IntArray, w: Int, h: Int, k: Int): IntArray { val o = IntArray(w * k * h * k); val ow = w * k; for (y in 0 until h * k) for (x in 0 until ow) o[y * ow + x] = src[(y / k) * w + x / k]; return o }
    /** Upscale by 2, 3 or 4: smoothly (Scale2x, then softened edges) or as plain blocks. */
    fun upscale(src: IntArray, w: Int, h: Int, k: Int, smooth: Boolean): IntArray {
        if (!smooth) return nearest(src, w, h, k)
        val out = when (k) {
            2 -> scale2x(src, w, h)
            4 -> scale2x(scale2x(src, w, h), w * 2, h * 2)
            3 -> scale3(src, w, h)
            else -> nearest(src, w, h, k)
        }
        smoothEdges(out, w * k, h * k, 0.5f)
        return out
    }
    /** ×3: Scale2x, then resampled to three (good enough, then smoothed). */
    private fun scale3(src: IntArray, w: Int, h: Int): IntArray {
        val big = scale2x(scale2x(src, w, h), w * 2, h * 2)   // ×4
        return boxDown(big, w * 4, h * 4, w * 3, h * 3)
    }

    /** Shrinks by averaging every source pixel that falls in each target pixel (alpha-aware). */
    fun boxDown(src: IntArray, sw: Int, sh: Int, tw: Int, th: Int): IntArray {
        val o = IntArray(tw * th)
        for (ty in 0 until th) {
            val y0 = ty * sh / th; val y1 = max(y0 + 1, (ty + 1) * sh / th)
            for (tx in 0 until tw) {
                val x0 = tx * sw / tw; val x1 = max(x0 + 1, (tx + 1) * sw / tw)
                var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
                for (y in y0 until y1) for (x in x0 until x1) { val c = src[y * sw + x]; val al = a(c) / 255f; sa += al; sr += r(c) * al; sg += g(c) * al; sb += b(c) * al; n++ }
                o[ty * tw + tx] = if (sa <= 0.001f) 0 else argb((sa / n * 255).toInt(), (sr / sa).toInt(), (sg / sa).toInt(), (sb / sa).toInt())
            }
        }
        return o
    }

    // ---------------- turning a picture into a sprite
    class Convert(var width: Int = 48, var detail: Int = 1, var colors: Int = 24, var background: Int = 30, var outline: Boolean = true, var punch: Int = 30, var smooth: Boolean = false)

    /** The picture, at most 512 px a side, as pixels (with the background around the subject cleared). */
    class Source(val px: IntArray, val w: Int, val h: Int)
    fun source(bmp: Bitmap): Source {
        val k = min(1f, 512f / max(bmp.width, bmp.height))
        val sw = max(1, (bmp.width * k).toInt()); val sh = max(1, (bmp.height * k).toInt())
        val full = IntArray(bmp.width * bmp.height); bmp.getPixels(full, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Source(if (k < 1f) boxDown(full, bmp.width, bmp.height, sw, sh) else full, sw, sh)
    }

    private fun removeBackground(px: IntArray, w: Int, h: Int, tol: Int) {
        if (tol <= 0) return
        val lim = tol * 4.4f
        val seen = BooleanArray(w * h); val stack = ArrayDeque<Int>()
        fun seed(i: Int) { if (!seen[i] && a(px[i]) > 0) { seen[i] = true; stack.addLast(i) } else if (a(px[i]) == 0) seen[i] = true }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        // flood inward from the border through pixels like the ones they started from
        val ref = IntArray(w * h) { -1 }
        for (i in stack) ref[i] = px[i]
        while (stack.isNotEmpty()) {
            val i = stack.removeLast(); val c = ref[i]
            val x = i % w; val y = i / w
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                if (j < 0 || seen[j]) continue
                val d = sqrt(((r(px[j]) - r(c)) * (r(px[j]) - r(c)) + (g(px[j]) - g(c)) * (g(px[j]) - g(c)) + (b(px[j]) - b(c)) * (b(px[j]) - b(c))).toFloat())
                if (d <= lim) { seen[j] = true; ref[j] = c; stack.addLast(j) }
            }
        }
        for (i in px.indices) if (ref[i] != -1) px[i] = 0
        // soften the cut edge a touch
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; if (a(px[i]) > 0 && ((x > 0 && ref[i - 1] != -1) || (x < w - 1 && ref[i + 1] != -1) || (y > 0 && ref[i - w] != -1) || (y < h - 1 && ref[i + w] != -1))) px[i] = (px[i] and 0xFFFFFF) or (160 shl 24) }
    }

    private fun punchUp(px: IntArray, amount: Int) {
        if (amount <= 0) return
        val s = 1f + amount / 100f * 0.8f; val ct = 1f + amount / 100f * 0.35f
        for (i in px.indices) {
            val c = px[i]; if (a(c) == 0) continue
            val l = r(c) * 0.3f + g(c) * 0.59f + b(c) * 0.11f
            fun f(v: Int) = ((l + (v - l) * s - 128f) * ct + 128f).toInt()
            px[i] = argb(a(c), f(r(c)), f(g(c)), f(b(c)))
        }
    }

    /** Median cut: the n colours that best stand for the picture. */
    fun palette(px: IntArray, n: Int): IntArray {
        val cols = ArrayList<Int>()
        val step = max(1, px.size / 30000)
        var i = 0; while (i < px.size) { if (a(px[i]) >= 128) cols += px[i]; i += step }
        if (cols.isEmpty()) return intArrayOf()
        var boxes = mutableListOf(cols)
        while (boxes.size < n) {
            val bx = boxes.maxByOrNull { bxx -> if (bxx.size < 2) -1 else range(bxx) } ?: break
            if (bx.size < 2) break
            val ch = channel(bx)
            bx.sortBy { (it shr (16 - ch * 8)) and 255 }
            val mid = bx.size / 2
            boxes.remove(bx); boxes.add(ArrayList(bx.subList(0, mid))); boxes.add(ArrayList(bx.subList(mid, bx.size)))
        }
        return boxes.filter { it.isNotEmpty() }.map { bx -> argb(255, bx.sumOf { r(it) } / bx.size, bx.sumOf { g(it) } / bx.size, bx.sumOf { b(it) } / bx.size) }.toIntArray()
    }
    private fun spread(bx: List<Int>, ch: Int): Int { var lo = 255; var hi = 0; for (c in bx) { val v = (c shr (16 - ch * 8)) and 255; lo = min(lo, v); hi = max(hi, v) }; return hi - lo }
    private fun range(bx: List<Int>) = max(spread(bx, 0), max(spread(bx, 1), spread(bx, 2)))
    private fun channel(bx: List<Int>): Int { val s = intArrayOf(spread(bx, 0), spread(bx, 1), spread(bx, 2)); return s.indices.maxByOrNull { s[it] } ?: 0 }
    fun nearestIn(pal: IntArray, c: Int): Int {
        var best = pal[0]; var bd = Int.MAX_VALUE
        for (p in pal) { val dr = r(p) - r(c); val dg = g(p) - g(c); val db = b(p) - b(c); val d = dr * dr * 3 + dg * dg * 4 + db * db * 2; if (d < bd) { bd = d; best = p } }
        return best
    }

    /** The whole recipe: fit to size, clear the background, punch up, reduce colours, outline, smooth. */
    fun convert(src: Source, outW: Int, outH: Int, o: Convert): IntArray {
        val px = src.px.copyOf()
        removeBackground(px, src.w, src.h, o.background)
        punchUp(px, o.punch)
        // fit inside outW x outH, keeping its shape, standing on the bottom edge
        val k = min(outW / src.w.toFloat(), outH / src.h.toFloat())
        val fw = max(1, (src.w * k).toInt()); val fh = max(1, (src.h * k).toInt())
        val small = boxDown(px, src.w, src.h, fw, fh)
        val out = IntArray(outW * outH); val ox = (outW - fw) / 2; val oy = outH - fh
        for (y in 0 until fh) for (x in 0 until fw) out[(y + oy) * outW + x + ox] = small[y * fw + x]
        // pixel art wants solid pixels; detail art keeps soft edges
        val crisp = !o.smooth
        for (i in out.indices) { val al = a(out[i]); if (crisp || o.detail == 1) out[i] = if (al < 110) 0 else out[i] or (0xFF shl 24) }
        if (o.colors in 2..64) {
            val pal = palette(out, o.colors)
            if (pal.isNotEmpty()) { val cache = HashMap<Int, Int>(); for (i in out.indices) { val c = out[i]; if (a(c) == 0) continue; val base = c or (0xFF shl 24); val q = cache.getOrPut(base) { nearestIn(pal, base) }; out[i] = (q and 0xFFFFFF) or (a(c) shl 24) } }
        }
        if (o.outline) {
            val t = max(1, o.detail); val ink = 0xFF201828.toInt()
            repeat(t) {
                val s = out.copyOf()
                for (y in 0 until outH) for (x in 0 until outW) {
                    val i = y * outW + x; if (a(s[i]) > 60) continue
                    if ((x > 0 && a(s[i - 1]) > 60) || (x < outW - 1 && a(s[i + 1]) > 60) || (y > 0 && a(s[i - outW]) > 60) || (y < outH - 1 && a(s[i + outW]) > 60)) out[i] = ink
                }
            }
        }
        if (o.smooth) smoothEdges(out, outW, outH, 0.5f)
        return out
    }
}
