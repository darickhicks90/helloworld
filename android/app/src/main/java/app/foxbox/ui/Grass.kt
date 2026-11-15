package app.foxbox.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.random.Random

/**
 * Close-up grass, as if you were lying about three feet away from it, in a
 * detailed retro video game style: hundreds of curved, tapering blades in
 * layers (dark and dense at the back, big and bright at the front), with
 * clover, seed heads and the odd fallen petal. Painted once per screen size
 * into a low-resolution pixel buffer, then scaled up with hard pixels.
 */
object Grass {
    private val SOIL = 0xFF14200F.toInt()
    /** Per layer, back to front: dark, mid, light, highlight. */
    private val LAYERS = arrayOf(
        intArrayOf(0xFF162B14.toInt(), 0xFF1F3A1B.toInt(), 0xFF2A4A22.toInt(), 0xFF35592A.toInt()),
        intArrayOf(0xFF1E3A1C.toInt(), 0xFF2D5528.toInt(), 0xFF3D6E30.toInt(), 0xFF4F863A.toInt()),
        intArrayOf(0xFF27492A.toInt(), 0xFF3A6E32.toInt(), 0xFF548E3E.toInt(), 0xFF74AD4C.toInt()),
        intArrayOf(0xFF2F5A2C.toInt(), 0xFF4A8438.toInt(), 0xFF6CA94A.toInt(), 0xFF9CCB5E.toInt()),
        intArrayOf(0xFF3A6A30.toInt(), 0xFF5A973F.toInt(), 0xFF84BF55.toInt(), 0xFFC2E07A.toInt()),
    )
    private var cached: Bitmap? = null
    private var cachedW = 0; private var cachedH = 0
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    fun draw(c: Canvas, w: Int, h: Int, cell: Float) {
        var b = cached
        if (b == null || cachedW != w || cachedH != h) { b = render(w, h, cell); cached = b; cachedW = w; cachedH = h }
        c.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
    }

    private fun render(w: Int, h: Int, cell: Float): Bitmap {
        val cw = (w / cell).toInt().coerceAtLeast(8); val ch = (h / cell).toInt().coerceAtLeast(8)
        val px = IntArray(cw * ch) { SOIL }
        fun put(x: Int, y: Int, c: Int) { if (x in 0 until cw && y in 0 until ch) px[y * cw + x] = c }
        val rnd = Random(42)
        // dark mottled ground between the roots
        repeat(cw * ch / 40) { put(rnd.nextInt(cw), rnd.nextInt(ch), if (rnd.nextBoolean()) 0xFF1B2A12.toInt() else 0xFF0E170A.toInt()) }
        for ((li, tones) in LAYERS.withIndex()) {
            val count = (cw * ch / 520) * (6 - li) / 3 + 40
            val scale = 0.55f + li * 0.22f
            repeat(count) {
                val x0 = rnd.nextFloat() * (cw + 20) - 10
                val root = rnd.nextFloat() * (ch + ch * 0.35f)
                val len = (ch * (0.18f + rnd.nextFloat() * 0.28f) * scale).coerceAtLeast(6f)
                val bend = (rnd.nextFloat() - 0.5f) * len * 0.9f
                val wid = (1.2f + rnd.nextFloat() * 1.8f) * scale
                blade(::put, x0, root, len, bend, wid, tones, rnd)
            }
            // clover and seed heads tucked in the middle layers
            if (li == 2 || li == 3) repeat(cw * ch / 9000 + 3) { clover(::put, rnd.nextInt(cw), rnd.nextInt(ch), tones, rnd) }
            if (li == 3) repeat(cw * ch / 14000 + 2) { seedHead(::put, rnd.nextInt(cw), rnd.nextInt(ch), rnd) }
        }
        // a few fallen petals and a ladybird, for life
        repeat(cw * ch / 16000 + 2) { val x = rnd.nextInt(cw); val y = rnd.nextInt(ch); put(x, y, 0xFFF4E8D0.toInt()); put(x + 1, y, 0xFFE9D9BC.toInt()) }
        val lx = (cw * 0.83f).toInt(); val ly = (ch * 0.31f).toInt()
        for (dy in -1..1) for (dx in -2..2) if (dx * dx + dy * dy * 3 <= 5) put(lx + dx, ly + dy, if (dx == 0 || (dx + dy) % 2 == 0 && dy == 0) 0xFF1A1010.toInt() else 0xFFC8321E.toInt())
        put(lx - 3, ly, 0xFF1A1010.toInt())
        val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, cw, 0, 0, cw, ch)
        return bmp
    }

    /** One blade: a tapering curve with a lit left edge, a dark right edge and a bright tip. */
    private fun blade(put: (Int, Int, Int) -> Unit, x0: Float, root: Float, len: Float, bend: Float, wid: Float, t: IntArray, rnd: Random) {
        val steps = len.toInt()
        for (s in 0..steps) {
            val f = s / len
            val x = x0 + bend * f * f
            val y = root - s
            val half = wid * (1f - f * 0.92f)
            val l = Math.round(x - half); val r = Math.round(x + half)
            for (xx in l..r) {
                val col = when {
                    f > 0.86f -> t[3]
                    xx == l -> t[2]
                    xx == r && r > l -> t[0]
                    else -> t[1]
                }
                put(xx, y.toInt(), col)
            }
            // a midrib catches the light partway up
            if (r - l >= 2 && s % 3 == 0 && f in 0.2f..0.8f) put(Math.round(x), y.toInt(), t[2])
        }
        if (rnd.nextInt(7) == 0) put(Math.round(x0 + bend), (root - steps).toInt(), t[3])
    }

    private fun clover(put: (Int, Int, Int) -> Unit, x: Int, y: Int, t: IntArray, rnd: Random) {
        for ((dx, dy) in listOf(-2 to -1, 2 to -1, 0 to 2)) for (yy in -1..1) for (xx in -1..1) if (xx * xx + yy * yy <= 1)
            put(x + dx + xx, y + dy + yy, if (yy < 0) t[3] else t[2])
        put(x, y, t[0]); put(x, y + 3, t[1]); put(x, y + 4, t[1])
        if (rnd.nextInt(3) == 0) { put(x - 2, y - 1, 0xFFE8F0C8.toInt()) }
    }

    private fun seedHead(put: (Int, Int, Int) -> Unit, x: Int, y: Int, rnd: Random) {
        for (k in 0..10) put(x, y + k, 0xFF7F8C3E.toInt())
        for (k in 0..4) { put(x - 1, y - k, 0xFFD8C890.toInt()); put(x + 1, y - k + 1, 0xFFB9A86A.toInt()); put(x, y - k - 1, 0xFFE8DCA8.toInt()) }
    }
}
