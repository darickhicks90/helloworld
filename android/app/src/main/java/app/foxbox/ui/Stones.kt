package app.foxbox.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Etched stones lying in the grass, in a detailed Game Boy Color style:
 * every surface uses a four-tone palette, stones get chipped edges, speckles
 * and the odd patch of moss, and the grass is built from 8×8 tiles.
 * Stone and grass bitmaps are painted once and cached.
 */
object Stones {
    /** Four tones, light to dark, plus an outline. */
    class Tones(val hi: Int, val light: Int, val mid: Int, val dark: Int, val line: Int)

    // Warm, faded, sepia-toned river stones.
    val GRANITE = Tones(0xFFEDE4D3.toInt(), 0xFFC8BBA5.toInt(), 0xFF9C8F7B.toInt(), 0xFF66594A.toInt(), 0xFF33281E.toInt())
    val SANDSTONE = Tones(0xFFF2E2C4.toInt(), 0xFFD6BC92.toInt(), 0xFFAE9068.toInt(), 0xFF72593E.toInt(), 0xFF38281A.toInt())
    val SLATE = Tones(0xFFDCDCCF.toInt(), 0xFFADB0A2.toInt(), 0xFF7E8475.toInt(), 0xFF4E5448.toInt(), 0xFF262A22.toInt())
    private val KINDS = listOf(GRANITE, SANDSTONE, SLATE)
    private val MOSS = intArrayOf(0xFFB9B26A.toInt(), 0xFF8A8A48.toInt(), 0xFF5C6034.toInt())
    /** A faded late-summer meadow: straw, olive, moss and shadow. */
    val GRASS = intArrayOf(0xFFD8C890.toInt(), 0xFFA59F62.toInt(), 0xFF77773F.toInt(), 0xFF46482A.toInt())

    fun kind(seed: Int) = KINDS[Math.floorMod(seed, KINDS.size)]
    private fun hash(a: Int, b: Int): Int { var x = a * 73856093 xor b * 19349663; x = x xor (x ushr 13); x *= 0x5bd1e995; return x xor (x ushr 15) }

    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 96
    }

    /** A stone of w×h screen pixels, drawn on a g-pixel grid, with its drop shadow. */
    fun bitmap(w: Int, h: Int, g: Float, seed: Int, tones: Tones = kind(seed), moss: Boolean = seed % 3 == 0): Bitmap = cache.getOrPut("$w:$h:$g:$seed:${tones.hashCode()}:$moss") {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(4), h.coerceAtLeast(4), Bitmap.Config.ARGB_8888)
        paintStone(Canvas(bmp), w, h, g, seed, tones, moss)
        bmp
    }

    private fun paintStone(c: Canvas, wPx: Int, hPx: Int, g: Float, seed: Int, t: Tones, moss: Boolean) {
        val cols = (wPx / g).toInt(); val rows = (hPx / g).toInt() - 2
        if (cols < 6 || rows < 6) return
        fun px(x: Int, y: Int, col: Int) = c.fill(x * g, y * g, (x + 1) * g, (y + 1) * g, col)
        // an irregular pebble outline: an ellipse bent by a few slow waves
        val cx = cols / 2f; val cy = rows / 2f; val rx = cols / 2f - 1.5f; val ry = rows / 2f - 1.5f
        val p1 = Math.floorMod(hash(seed, 1), 628) / 100f; val p2 = Math.floorMod(hash(seed, 2), 628) / 100f; val p3 = Math.floorMod(hash(seed, 3), 628) / 100f
        val a1 = 0.05f + Math.floorMod(hash(seed, 4), 8) / 100f
        val mask = BooleanArray(cols * rows)
        val nx = FloatArray(cols * rows); val ny = FloatArray(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            val dx = (x + 0.5f - cx) / rx; val dy = (y + 0.5f - cy) / ry
            val a = kotlin.math.atan2(dy, dx)
            val lim = 1f - a1 - a1 * kotlin.math.sin(2 * a + p1) - 0.05f * kotlin.math.sin(3 * a + p2) - 0.03f * kotlin.math.sin(5 * a + p3)
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d <= lim) { mask[y * cols + x] = true; nx[y * cols + x] = dx; ny[y * cols + x] = dy }
        }
        fun inside(x: Int, y: Int) = x in 0 until cols && y in 0 until rows && mask[y * cols + x]
        // soft shadow pressed into the meadow
        for (y in 0 until rows) for (x in 0 until cols) if (inside(x - 1, y - 2) && !inside(x, y)) px(x, y, 0x553A2A12)
        for (y in 0 until rows) for (x in 0 until cols) if (inside(x, y)) {
            val edge = !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1)
            val dx = nx[y * cols + x]; val dy = ny[y * cols + x]
            val nearEdge = !inside(x + 2, y) || !inside(x, y + 2)
            val light = -(dx * 0.55f + dy * 0.85f) + (1f - kotlin.math.sqrt(dx * dx + dy * dy)) * 0.5f + (Math.floorMod(hash(seed + x, y), 9) - 4) * 0.025f
            val col = when {
                edge -> t.line
                nearEdge && dx + dy > 0.2f -> t.dark
                light > 0.55f -> t.hi
                light > 0.15f -> t.light
                light > -0.35f -> t.mid
                else -> t.dark
            }
            px(x, y, col)
        }
        // speckles, and a hairline crack on some
        for (k in 0 until (cols * rows) / 26) {
            val h = hash(seed + 17, k)
            val x = Math.floorMod(h, cols); val y = Math.floorMod(h ushr 8, rows)
            if (!inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1)) continue
            px(x, y, if (k % 3 == 0) t.light else t.dark)
        }
        if (seed % 4 == 1) { var x = (cx + Math.floorMod(seed, 5) - 2).toInt(); var y = (cy + 1).toInt(); repeat(5) { if (inside(x, y) && inside(x + 1, y) && inside(x, y + 1)) px(x, y, t.dark); y++; if (it % 2 == 0) x++ } }
        // moss on the upper-left shoulder
        if (moss) for (y in 0 until rows) for (x in 0 until cols) if (inside(x, y)) {
            val dx = nx[y * cols + x]; val dy = ny[y * cols + x]
            if (dy < -0.45f && dx < 0.15f && Math.floorMod(hash(seed + 5, x * 31 + y), 5) < 3) px(x, y, MOSS[Math.floorMod(x + y, 3)])
        }
        // grass blades grow up over the bottom edge so the stone sits in the meadow
        var x = 2 + Math.floorMod(seed, 4)
        var k = 0
        while (x < cols - 2) {
            var by = -1
            for (y in rows - 1 downTo 0) if (inside(x, y)) { by = y; break }
            if (by > 0) {
                val h = 2 + Math.floorMod(hash(seed, k), 3)
                for (j in 0 until h + 1) px(x, by + 1 - j, if (j == h) GRASS[0] else if (j == 0) GRASS[3] else GRASS[2])
                if (k % 2 == 0) px(x + 1, by, GRASS[2])
            }
            x += 2 + Math.floorMod(hash(seed, k + 7), 7); k++
        }
    }

    /** Text carved into stone: dark cut with a light lower lip; `glow` lights the cut. */
    fun engrave(c: Canvas, text: String, x: Float, y: Float, p: Paint, t: Tones, glow: Int = 0) {
        val o = maxOf(1f, Px.u * 0.4f)
        p.color = t.hi; c.textCentered(text, x + o, y + o, p)
        p.color = if (glow != 0) glow else t.line; c.textCentered(text, x, y, p)
    }

    /** Small carved glyphs for each block and for audio. */
    private val GLYPHS = mapOf(
        "gusts" to listOf("..####.", ".#....#", "......#", ".####..", "#......", "#....#.", ".####.."),
        "reflections" to listOf(".......", "#..#..#", ".##.##.", ".......", "#..#..#", ".##.##.", "......."),
        "leaves" to listOf("....###", "..##..#", ".#...#.", ".#..#..", "#.##...", "##.....", "#......"),
        "minds" to listOf(".#####.", "#.....#", "#.###.#", "#.#.#.#", "#.#...#", "#.####.", "#......"),
        "bumble" to listOf("..###..", ".#...#.", "#.....#", "#.....#", "#.....#", ".#...#.", "..###.."),
        "pickup" to listOf("##.##.#", "##.##.#", ".......", "##.##.#", "##.##.#", ".......", "##.##.#"),
        "audio" to listOf("...#...", "...#..#", ".#.#..#", ".#.##.#", "##.##.#", "##.####", "#######"),
    )

    fun glyph(c: Canvas, id: String, x: Float, y: Float, g: Float, t: Tones, glow: Int = 0) {
        val rows = GLYPHS[id] ?: GLYPHS.getValue("audio")
        for ((j, row) in rows.withIndex()) for ((i, ch) in row.withIndex()) if (ch == '#') {
            c.fill(x + i * g + g * 0.5f, y + j * g + g * 0.5f, x + (i + 1) * g + g * 0.5f, y + (j + 1) * g + g * 0.5f, t.hi)
            c.fill(x + i * g, y + j * g, x + (i + 1) * g, y + (j + 1) * g, if (glow != 0) glow else t.line)
        }
    }

    // ------------------------------------------------------------------ grass

    private var grassBmp: Bitmap? = null

    /** A field of 8×8 grass tiles: plain, blades, clover, pebbles and tiny daisies, picked by hash. */
    fun grass(w: Int, h: Int, g: Float): Bitmap {
        grassBmp?.let { if (it.width == w && it.height == h) return it }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.fill(0f, 0f, w.toFloat(), h.toFloat(), GRASS[1])
        val tiles = listOf(
            listOf("........", "........", "........", "......#.", "........", "........", "........", "........"),
            listOf("........", ".#...#..", ".#..##..", "##..#...", "........", "....#..#", "...##..#", "...#..##"),
            listOf("........", "..oo....", ".oOOo...", "..oo.#..", "...##...", "........", "......#.", "........"),
            listOf("........", "........", "...pp...", "..pPPp..", "...pp...", "......p.", ".....pPp", "........"),
            listOf("........", "...w....", "..wyw...", "...w....", "...#....", "........", ".#......", "........"),
            listOf("#.......", "#..#....", "...#....", "........", ".....#..", "....##..", ".....#..", "........"),
        )
        val col = mapOf('#' to GRASS[2], 'o' to GRASS[0], 'O' to GRASS[3], 'p' to 0xFFC8BBA5.toInt(), 'P' to 0xFF66594A.toInt(), 'w' to 0xFFEDE4D3.toInt(), 'y' to 0xFFC98A4B.toInt())
        val tw = (w / (g * 8)).toInt() + 1; val th = (h / (g * 8)).toInt() + 1
        for (ty in 0 until th) for (tx in 0 until tw) {
            val hh = Math.floorMod(hash(tx, ty), 100)
            val tile = tiles[when { hh < 55 -> 0; hh < 80 -> 1; hh < 88 -> 2; hh < 96 -> 5; hh < 98 -> 3; else -> 4 }]
            for (y in 0 until 8) for (x in 0 until 8) {
                val ch = tile[y][x]; if (ch == '.') continue
                val l = (tx * 8 + x) * g; val t = (ty * 8 + y) * g
                c.fill(l, t, l + g, t + g, col.getValue(ch))
            }
        }
        // film grain and a stepped vignette, like a faded photograph
        val gr = Math.max(1f, g)
        var y = 0f
        var row = 0
        while (y < h) {
            var xx = 0f
            var col2 = 0
            while (xx < w) {
                val hh = Math.floorMod(hash(col2 + 1000, row + 77), 100)
                if (hh < 5) c.fill(xx, y, xx + gr, y + gr, 0x22140A00)
                else if (hh < 8) c.fill(xx, y, xx + gr, y + gr, 0x22FFF4DC)
                xx += gr; col2++
            }
            y += gr; row++
        }
        val cell = g * 6
        var vy = 0f
        while (vy < h) {
            var vx = 0f
            while (vx < w) {
                val dx = (vx + cell / 2) / w * 2 - 1; val dy = (vy + cell / 2) / h * 2 - 1
                val d = kotlin.math.sqrt(dx * dx * 0.8f + dy * dy)
                val step = ((d - 0.75f) / 0.12f).toInt().coerceIn(0, 4)
                if (step > 0) c.fill(vx, vy, vx + cell, vy + cell, (step * 0x12 shl 24) or 0x2A1A0E)
                vx += cell
            }
            vy += cell
        }
        // a warm sepia wash over everything
        c.fill(0f, 0f, w.toFloat(), h.toFloat(), 0x14C98A4B)
        grassBmp = bmp
        return bmp
    }
}
