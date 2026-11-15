package app.foxbox.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The poetry stone: a single, finely detailed boulder with soft shading,
 * mineral speckles, a crack and thick moss on its crown. Tap it on the clip
 * launcher for a line of Romantic poetry. Painted once per size and cached.
 */
object PoemStone {
    private val TONES = intArrayOf(0xFFF1ECE2.toInt(), 0xFFD9D2C4.toInt(), 0xFFBDB4A4.toInt(), 0xFF9C9282.toInt(), 0xFF7A7062.toInt(), 0xFF584F44.toInt(), 0xFF2E2922.toInt())
    private val MOSS = intArrayOf(0xFFD6E08A.toInt(), 0xFFA8C25A.toInt(), 0xFF7C9C3E.toInt(), 0xFF56732C.toInt(), 0xFF35491E.toInt())
    private var cache: Bitmap? = null
    private var key = ""

    fun draw(c: Canvas, l: Float, t: Float, w: Float, h: Float, cell: Float) {
        val k = "${w.toInt()}x${h.toInt()}:$cell"
        var b = cache
        if (b == null || key != k) { b = render(w, h, cell); cache = b; key = k }
        c.drawBitmap(b, l, t, null)
    }

    private fun hash(a: Int, b: Int): Int { var x = a * 73856093 xor b * 19349663; x = x xor (x ushr 13); x *= 0x5bd1e995; return x xor (x ushr 15) }

    private fun render(w: Float, h: Float, cell: Float): Bitmap {
        val cols = (w / cell).toInt(); val rows = (h / cell).toInt()
        val bmp = Bitmap.createBitmap((cols * cell).toInt() + 1, (rows * cell).toInt() + 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun px(x: Int, y: Int, col: Int) = c.fill(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, col)
        val cx = cols / 2f; val cy = rows * 0.56f; val rx = cols / 2f - 2; val ry = rows * 0.42f
        val inside = Array(rows) { BooleanArray(cols) }
        val nx = Array(rows) { FloatArray(cols) }; val ny = Array(rows) { FloatArray(cols) }
        for (y in 0 until rows) for (x in 0 until cols) {
            val dx = (x + 0.5f - cx) / rx; val dy = (y + 0.5f - cy) / ry
            val a = atan2(dy, dx)
            // a weathered boulder: rounder on top, flatter where it sits in the ground
            var lim = 1f - 0.07f * sin(2 * a + 0.7f) - 0.05f * sin(3 * a + 2.1f) - 0.03f * sin(7 * a + 0.4f)
            if (dy > 0.55f) lim -= (dy - 0.55f) * 0.9f
            if (sqrt(dx * dx + dy * dy) <= lim) { inside[y][x] = true; nx[y][x] = dx; ny[y][x] = dy }
        }
        fun ins(x: Int, y: Int) = y in 0 until rows && x in 0 until cols && inside[y][x]
        // contact shadow in the grass
        for (y in 0 until rows) for (x in 0 until cols) if (!ins(x, y) && (ins(x - 2, y - 2) || ins(x - 1, y - 3))) px(x, y, 0x66101808)
        for (y in 0 until rows) for (x in 0 until cols) if (ins(x, y)) {
            val edge = !ins(x - 1, y) || !ins(x + 1, y) || !ins(x, y - 1) || !ins(x, y + 1)
            if (edge) { px(x, y, TONES[6]); continue }
            val dx = nx[y][x]; val dy = ny[y][x]
            val d = sqrt(dx * dx + dy * dy)
            // light from the upper left, a dome of form shading, and fine grain
            var lv = -(dx * 0.55f + dy * 0.8f) * 0.9f + (1f - d) * 0.75f + (Math.floorMod(hash(x, y), 11) - 5) * 0.03f
            if (!ins(x + 2, y) || !ins(x, y + 2)) lv -= 0.35f // reflected-dark rim
            val i = when { lv > 0.95f -> 0; lv > 0.7f -> 1; lv > 0.45f -> 2; lv > 0.15f -> 3; lv > -0.2f -> 4; else -> 5 }
            // ordered dithering between neighbouring tones
            val dith = (x + y * 2) % 4 == 0 && i < 5 && lv - (when (i) { 0 -> 0.95f; 1 -> 0.7f; 2 -> 0.45f; 3 -> 0.15f; else -> -0.2f }) < 0.06f
            px(x, y, TONES[if (dith) i + 1 else i])
        }
        // mineral speckles
        for (k in 0 until cols * rows / 18) {
            val hh = hash(k, 99); val x = Math.floorMod(hh, cols); val y = Math.floorMod(hh ushr 9, rows)
            if (ins(x - 1, y) && ins(x + 1, y) && ins(x, y - 1) && ins(x, y + 1)) px(x, y, if (k % 4 == 0) TONES[0] else TONES[5])
        }
        // a crack running down the face
        var x = (cx + cols * 0.12f).toInt(); var y = (cy - ry * 0.2f).toInt()
        repeat((rows * 0.45f).toInt()) { if (ins(x, y) && ins(x + 1, y)) { px(x, y, TONES[6]); px(x + 1, y, TONES[3]) }; y++; if (it % 3 == 1) x += if (it % 2 == 0) 1 else -1 }
        // thick moss over the crown and down the left shoulder
        for (yy in 0 until rows) for (xx in 0 until cols) if (ins(xx, yy)) {
            val dx = nx[yy][xx]; val dy = ny[yy][xx]
            val cover = -dy * 1.1f - dx * 0.35f + (Math.floorMod(hash(xx * 7, yy * 13), 100) / 100f) * 0.5f
            if (cover > 0.9f) {
                val shade = when { dy < -0.85f || cover > 1.35f -> 0; cover > 1.15f -> 1; cover > 1.0f -> 2; else -> 3 }
                px(xx, yy, MOSS[shade])
                if (!ins(xx, yy - 1)) px(xx, yy - 1, MOSS[1]) // moss lifts above the outline
                if (Math.floorMod(hash(xx, yy * 3), 9) == 0) px(xx, yy, MOSS[4])
            }
        }
        // a few strands of moss hanging over the edge
        for (k in 0 until 6) {
            val sx = (cx - rx * 0.6f + k * rx * 0.22f).toInt()
            var sy = -1
            for (yy in 0 until rows) if (ins(sx, yy)) { sy = yy; break }
            if (sy < 0) continue
            for (j in 0 until 2 + k % 3) px(sx, sy - j - 1, if (j == 0) MOSS[2] else MOSS[1])
        }
        // grass growing up against its foot
        for (k in 0 until cols / 2) {
            val bx = (k * 2 + Math.floorMod(hash(k, 5), 2))
            var by = -1
            for (yy in rows - 1 downTo 0) if (ins(bx, yy)) { by = yy; break }
            if (by < 0) continue
            val len = 2 + Math.floorMod(hash(k, 6), 5)
            for (j in 0 until len) px(bx, by + 1 - j, if (j == len - 1) 0xFFC2E07A.toInt() else if (j == 0) 0xFF2F5A2C.toInt() else 0xFF5A973F.toInt())
        }
        return bmp
    }
}
