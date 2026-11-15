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

    val GRANITE = Tones(0xFFEEF0EE.toInt(), 0xFFC9CDD2.toInt(), 0xFF9AA0A8.toInt(), 0xFF5E6170.toInt(), 0xFF2B2A33.toInt())
    val SANDSTONE = Tones(0xFFF4EBDD.toInt(), 0xFFD9C8B0.toInt(), 0xFFAD9A82.toInt(), 0xFF6E5F52.toInt(), 0xFF33291F.toInt())
    val SLATE = Tones(0xFFDCE6E4.toInt(), 0xFFA9BAB8.toInt(), 0xFF768A8C.toInt(), 0xFF46545C.toInt(), 0xFF1F272C.toInt())
    private val KINDS = listOf(GRANITE, SANDSTONE, SLATE)
    private val MOSS = intArrayOf(0xFFB5D96A.toInt(), 0xFF6DAA45.toInt(), 0xFF3E7A34.toInt())
    /** Grass tones (GBC style): highlight, light, mid, dark. */
    val GRASS = intArrayOf(0xFFB5D96A.toInt(), 0xFF6DAA45.toInt(), 0xFF3E7A34.toInt(), 0xFF1E3B1E.toInt())

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
        val cols = (wPx / g).toInt() - 1; val rows = (hPx / g).toInt() - 2
        if (cols < 4 || rows < 4) return
        fun px(x: Int, y: Int, col: Int) = c.fill(x * g, y * g, (x + 1) * g, (y + 1) * g, col)
        // rounded, chipped silhouette: an inset per row, with a little noise
        val ins = IntArray(rows) { y ->
            val edge = minOf(y, rows - 1 - y)
            val round = when (edge) { 0 -> 3; 1 -> 1; 2 -> 1; else -> 0 }
            round + (if (Math.floorMod(hash(seed, y), 7) == 0) 1 else 0)
        }
        // drop shadow on the grass
        for (y in 1 until rows) for (x in ins[y] + 1 until cols - ins[y] + 1) px(x, y + 1, 0x55102A12)
        for (y in 0 until rows) {
            val l = ins[y]; val r = cols - 1 - ins[y]
            for (x in l..r) {
                val edgeX = x == l || x == r; val edgeY = y == 0 || y == rows - 1 || x < ins.getOrElse(y - 1) { 99 } || x > cols - 1 - ins.getOrElse(y - 1) { 99 } ||
                    x < ins.getOrElse(y + 1) { 99 } || x > cols - 1 - ins.getOrElse(y + 1) { 99 }
                val col = when {
                    edgeX || edgeY -> t.line
                    y == 1 || x == l + 1 -> t.hi                      // lit top-left rim
                    y >= rows - 3 || x >= r - 1 -> t.dark            // shaded bottom-right
                    y == 2 || x == l + 2 -> t.light
                    else -> t.mid
                }
                px(x, y, col)
            }
        }
        // speckles and a few cracks
        for (k in 0 until (cols * rows) / 24) {
            val h = hash(seed + 17, k)
            val x = Math.floorMod(h, cols - 6) + 3; val y = Math.floorMod(h ushr 8, rows - 6) + 3
            if (x <= ins[y] + 2 || x >= cols - 3 - ins[y]) continue
            px(x, y, if (k % 3 == 0) t.light else t.dark)
        }
        if (seed % 4 == 1) { var x = cols / 2 + Math.floorMod(seed, 5) - 2; for (y in rows - 3 downTo rows - 7) { px(x, y, t.dark); if (y % 2 == 0) x++ } }
        // moss creeping over the top-left edge
        if (moss) for (k in 0 until cols / 2) {
            val x = ins[0] + 1 + k; if (x >= cols - 3) break
            val depth = 1 + Math.floorMod(hash(seed, k + 99), 3) - (k / 4)
            for (y in 0 until depth.coerceAtLeast(0)) px(x, y, MOSS[minOf(y, 2)])
        }
    }

    /** Grass blades growing up over a stone's bottom edge, so it sits in the turf. */
    fun tufts(c: Canvas, l: Float, r: Float, b: Float, g: Float, seed: Int) {
        val cols = ((r - l) / g).toInt()
        var x = 2 + Math.floorMod(seed, 5)
        var k = 0
        while (x < cols - 2) {
            val h = 2 + Math.floorMod(hash(seed, k), 3)
            for (j in 0 until h) {
                val col = if (j == h - 1) GRASS[0] else if (j == 0) GRASS[3] else GRASS[2]
                c.fill(l + x * g, b - (j + 1) * g, l + (x + 1) * g, b - j * g, col)
                if (j < h - 1 && k % 2 == 0) c.fill(l + (x + 1) * g, b - (j + 1) * g, l + (x + 2) * g, b - j * g, GRASS[2])
            }
            x += 3 + Math.floorMod(hash(seed, k + 7), 9); k++
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
        val col = mapOf('#' to GRASS[2], 'o' to GRASS[0], 'O' to GRASS[2], 'p' to 0xFF9AA0A8.toInt(), 'P' to 0xFF5E6170.toInt(), 'w' to 0xFFF7EFDC.toInt(), 'y' to 0xFFF6C26B.toInt())
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
        // darker band where the ground meets the garden strip is drawn by the screen
        grassBmp = bmp
        return bmp
    }
}
