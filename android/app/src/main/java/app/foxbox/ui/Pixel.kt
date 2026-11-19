package app.foxbox.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

/** 16-bit cottagecore look: palette, fonts, sprites and pixel drawing helpers. */
object Pal {
    const val ORANGE = 0xFFE8742C.toInt()
    const val ORANGE_L = 0xFFF4A259.toInt()
    const val ORANGE_D = 0xFFB8521B.toInt()
    const val AQUA = 0xFF3FB8AF.toInt()
    const val AQUA_L = 0xFF8FD9D1.toInt()
    const val AQUA_D = 0xFF22706B.toInt()
    const val CREAM = 0xFFF7EFDC.toInt()
    const val PAPER = 0xFFECDCB9.toInt()
    const val BROWN_L = 0xFFA8774C.toInt()
    const val BROWN = 0xFF7A4A2A.toInt()
    const val BROWN_D = 0xFF3D2414.toInt()
    // Extras: meadow greens and sunset tones.
    const val GREEN_L = 0xFFA9CF6A.toInt()
    const val GREEN = 0xFF6FA84A.toInt()
    const val GREEN_D = 0xFF3F7A3A.toInt()
    const val GREEN_DD = 0xFF2C5A30.toInt()
    const val SUN = 0xFFF6C26B.toInt()
    const val DUSK = 0xFFE58A5A.toInt()
    const val ROSE = 0xFFD9786A.toInt()
    const val HONEY = 0xFFD9A03A.toInt()

    val KEYS = mapOf('O' to ORANGE, 'o' to ORANGE_L, 'r' to ORANGE_D, 'A' to AQUA, 'a' to AQUA_L, 'q' to AQUA_D,
        'W' to CREAM, 'w' to PAPER, 'b' to BROWN_L, 'B' to BROWN, 'D' to BROWN_D,
        'g' to GREEN_L, 'G' to GREEN, 'h' to HONEY)

    /** Accent colour for each engine. */
    fun accent(id: String) = when (id) {
        "gusts" -> AQUA; "reflections" -> AQUA_D; "leaves" -> ORANGE_D; "minds" -> ORANGE; "bumble" -> HONEY; "pickup" -> ROSE; else -> BROWN
    }

    fun alpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}

/**
 * Symbols added to the app's two pixel fonts (5IY-Head / 5IY-Body, renamed OFL
 * builds of Press Start 2P and Pixelify Sans), in the private-use area.
 */
object Glyph {
    const val NOTEBOOK = "\uE000"
    const val SAW = "\uE001"
    const val SQUARE = "\uE002"
    const val TRIANGLE = "\uE003"
    const val SINE = "\uE004"
    const val RADIO = "\uE005"
    private val waves = Regex("\\b(Saw|Square|Triangle|Sine)\\b")
    /** "Saw" → the saw symbol, and so on, wherever a waveform is written out. */
    fun waves(s: String): String = waves.replace(s) { when (it.value) { "Saw" -> SAW; "Square" -> SQUARE; "Triangle" -> TRIANGLE; else -> SINE } }
}

object Px {
    var density = 1f; private set
    /** One "art pixel" in screen pixels. */
    var u = 3f; private set
    lateinit var head: Typeface; private set
    lateinit var body: Typeface; private set

    fun init(c: Context) {
        density = c.resources.displayMetrics.density
        u = (3 * density).coerceAtLeast(2f)
        head = runCatching { Typeface.createFromAsset(c.assets, "fonts/5IY-Head.ttf") }.getOrDefault(Typeface.MONOSPACE)
        body = runCatching { Typeface.createFromAsset(c.assets, "fonts/5IY-Body.ttf") }.getOrDefault(Typeface.MONOSPACE)
    }

    fun dp(v: Float) = v * density
    fun dp(v: Int) = (v * density).toInt()

    fun text(size: Float, color: Int = Pal.BROWN_D, headFont: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (headFont) head else body; textSize = dp(size); this.color = color; textAlign = align }
}

/** A pixel-art sprite built from strings of palette letters ('.' = transparent). */
class Sprite(rows: List<String>, colors: Map<Char, Int> = emptyMap(), outline: Int? = Pal.BROWN_D) {
    val bmp: Bitmap
    val w: Int
    val h: Int

    init {
        val o = if (outline != null) 1 else 0
        h = rows.size + 2 * o
        w = rows.maxOf { it.length } + 2 * o
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val keys = Pal.KEYS + colors
        fun filled(x: Int, y: Int): Boolean {
            val r = rows.getOrNull(y - o) ?: return false
            val c = r.getOrNull(x - o) ?: return false
            return c != '.' && c != ' '
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (filled(x, y)) bmp.setPixel(x, y, keys[rows[y - o][x - o]] ?: Pal.BROWN_D)
            else if (outline != null && (filled(x - 1, y) || filled(x + 1, y) || filled(x, y - 1) || filled(x, y + 1))) bmp.setPixel(x, y, outline)
        }
    }

    fun recolor(map: Map<Char, Int>, rows: List<String>) = Sprite(rows, map)
}

private val spritePaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
private val fillPaint = Paint()
private val dst = RectF()
private val src = Rect()

/** Draw a sprite with its top-left at (x, y); `scale` = screen pixels per sprite pixel. */
fun Canvas.sprite(s: Sprite, x: Float, y: Float, scale: Float, flip: Boolean = false, alpha: Int = 255) {
    spritePaint.alpha = alpha
    src.set(0, 0, s.w, s.h)
    dst.set(x, y, x + s.w * scale, y + s.h * scale)
    if (flip) { save(); scale(-1f, 1f, dst.centerX(), dst.centerY()); drawBitmap(s.bmp, src, dst, spritePaint); restore() }
    else drawBitmap(s.bmp, src, dst, spritePaint)
}

fun Canvas.spriteCentered(s: Sprite, cx: Float, cy: Float, scale: Float, flip: Boolean = false, alpha: Int = 255) =
    sprite(s, cx - s.w * scale / 2, cy - s.h * scale / 2, scale, flip, alpha)

fun Canvas.fill(l: Float, t: Float, r: Float, b: Float, color: Int) {
    fillPaint.color = color
    drawRect(l, t, r, b, fillPaint)
}

/** One art-pixel block, snapped to the pixel grid. */
fun Canvas.dot(x: Float, y: Float, color: Int, n: Int = 1) {
    val u = Px.u
    val sx = (x / u).toInt() * u; val sy = (y / u).toInt() * u
    fill(sx, sy, sx + u * n, sy + u * n, color)
}

/** A 16-bit menu box with notched corners. */
fun Canvas.pixelBox(l: Float, t: Float, r: Float, b: Float, fill: Int, border: Int = Pal.BROWN_D, shade: Boolean = true) {
    val u = Px.u
    fill(l + u, t + u, r - u, b - u, fill)
    if (shade) fill(l + u, b - 2 * u, r - u, b - u, Pal.alpha(Pal.BROWN_D, 0.15f))
    fill(l + u, t, r - u, t + u, border); fill(l + u, b - u, r - u, b, border)
    fill(l, t + u, l + u, b - u, border); fill(r - u, t + u, r, b - u, border)
}

/** Flat stepped "16-bit" vertical gradient. */
fun Canvas.bands(l: Float, t: Float, r: Float, b: Float, colors: IntArray) {
    val u = Px.u
    val n = colors.size
    for (i in 0 until n) {
        val y0 = ((t + (b - t) * i / n) / u).toInt() * u
        val y1 = ((t + (b - t) * (i + 1) / n) / u).toInt() * u + u
        fill(l, y0, r, minOf(b, y1), colors[i])
    }
}

fun Canvas.textCentered(s: String, cx: Float, cy: Float, p: Paint) = drawText(s, cx, cy - (p.descent() + p.ascent()) / 2, p)

/**
 * Text that always fits its space: shrinks the paint (down to `minSp`) until
 * `s` is no wider than `maxW`, then shortens it with "…" if it still doesn't
 * fit. Draws vertically centred on `cy`; the paint's size is restored after.
 */
fun Canvas.textFit(s: String, x: Float, cy: Float, maxW: Float, p: Paint, minSp: Float = 8.5f) {
    val orig = p.textSize
    val t = fitText(s, maxW, p, minSp)
    textCentered(t, x, cy, p)
    p.textSize = orig
}

/** Shrinks `p` to fit `s` in `maxW` (not below `minSp`) and returns the text, shortened with "…" if needed. Leaves `p` resized. */
fun fitText(s: String, maxW: Float, p: Paint, minSp: Float = 8.5f): String {
    if (maxW <= 0f) return ""
    val min = Px.dp(minSp)
    while (p.measureText(s) > maxW && p.textSize > min) p.textSize = maxOf(min, p.textSize - Px.dp(0.5f))
    if (p.measureText(s) <= maxW) return s
    var t = s
    while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
    return "$t…"
}

/** Cream text on dark accents, dark text on light ones (yellow, pale aqua…), so labels stay readable. */
fun textOn(bg: Int): Int {
    val r = (bg shr 16 and 255); val g = (bg shr 8 and 255); val b = (bg and 255)
    return if (0.299 * r + 0.587 * g + 0.114 * b > 168) Pal.BROWN_D else Pal.CREAM
}

/** Pixel art library. */
object Sprites {
    private val FOX_HEAD = listOf(
        ".D............D.", ".DD..........DD.", ".DOD........DOD.", ".DOoD......DoOD.", ".DOOODDDDDDOOOD.",
        ".DOOOOOOOOOOOOD.", "DOOOOOOOOOOOOOOD", "DOODDOOOOOODDOOD", "DOOWDOOOOOOWDOOD", "DOWWWOOOOOOWWWOD",
        ".DWWWWOOOOWWWWD.", "..DWWWWWWWWWWD..", "...DWWWWWWWWD...", "....DDWDDWDD....", "......DDDD......",
    )
    val fox by lazy { Sprite(FOX_HEAD, outline = null) }
    val cloud by lazy { Sprite(listOf("...WWW....", "..WWWWW.W.", ".WWWWWWWWW", "WWWWWWWWWW", "WWWWWWWWWW", ".aaaaaaaa."), outline = Pal.AQUA_D) }
    val drop by lazy { Sprite(listOf("...A...", "...A...", "..AAA..", "..AAA..", ".AaAAA.", ".aAAAA.", ".AAAAA.", "..qAq..")) }
    val leaf by lazy { Sprite(listOf("......DD", "....OOOD", "..OOOOoD", ".OOOOoOO", ".OOOoOO.", ".OOoOO..", ".DoOO...", "D.......")) }
    val mushroom by lazy { Sprite(listOf("...OOOO...", "..OWOOWO..", ".OOOOOOOO.", "OWOOOWOOOO", "OOOOOOOOWO", "..BWWWWB..", "...WWWW...", "...WWWW...", "...WwwW...")) }
    val tooth by lazy { Sprite(listOf(".WWW..WWW.", "WWWWWWWWWW", "WWWWWWWWWW", "WWWWWWWWWW", ".WWWWWWWW.", ".WWW..WWW.", ".WW....WW.", "..W....W..")) }
    val acorn by lazy { Sprite(listOf("..b..", ".bBb.", "BBBBB", ".OOO.", ".OoO.", "..O..")) }
    val flower by lazy { Sprite(listOf(".o.", "oOo", ".o.", ".q."), outline = null) }

    val bee by lazy { Sprite(BEE) }
    val bloom by lazy { Sprite(listOf("..o.o..", ".oOoOo.", "oOOWOOo", ".oOoOo.", "..oGo..", "...G...", "..GG...")) }
    val cabin by lazy {
        Sprite(listOf(
            "..............bb....", "........rr....bb....", ".......rOOr...bb....", "......rOOOOr..bb....",
            ".....rOOOOOOr.bb....", "....rOOOOOOOOrbb....", "...rOOOOOOOOOOr.....", "..rOOOOOOOOOOOOr....",
            ".rOOOOOOOOOOOOOOr...", "rrrrrrrrrrrrrrrrrr..", ".BbBbBbBbBbBbBbBb...", ".BbBoooBbBbDDDbBb...",
            ".bBboooBbBbDDDBbB...", ".BbBbBbBbBbDoDbBb...", ".bBbBbBbBbBDDDBbB...", ".BBBBBBBBBBBBBBBB...",
        ))
    }
    /** The small kitsune for Clibbins (tails are drawn separately). */
    val kitsune by lazy {
        Sprite(listOf(
            ".D..........D.", ".DD........DD.", ".DoD......DoD.", ".DOoDDDDDDoOD.", ".DOOOOOOOOOOD.",
            "DOOOOOOOOOOOOD", "DOqDOOOOOOqDOD", "DOOOrOOOOrOOOD", ".DWWWOOOOWWWD.", "..DWWWWWWWWD..",
            "...DWWDDWWD...", "..DOWWWWWWOD..", ".DOOWWWWWWOOD.", ".DOOOWWWWOOOD.", "DOOOOOWWOOOOOD",
            "DOOOOOOOOOOOOD", "DWWDOOOOOODWWD", ".DDDDDDDDDDDD.",
        ), outline = null)
    }

    fun icon(pageId: String) = when (pageId) {
        "gusts" -> Iso.iconCloud; "reflections" -> Iso.iconBoat; "leaves" -> Iso.iconTree; "minds" -> Iso.iconMushroom
        "bumble" -> Iso.iconHive; "pickup" -> Iso.iconTruck; else -> Iso.iconFlower
    }
    private val BEE = listOf("..aa...aa..", ".aWWa.aWWa.", "..aaDDDaa..", "..DhhhhhD..", ".DDDDDDDDD.", ".DhWhhhWhD.", ".DDDDDDDDD.", "..DhhhhhD..", "...DDDDD...")

    // Game Boy Color JRPG-style critters (16x16), generated from a design file.
    private val INK = 0xFF201418.toInt()
    private val CRITTER_BASE = mapOf('k' to INK, 'w' to 0xFFFFFFFF.toInt(), 'm' to 0xFFE58A5A.toInt())
    private val CRITTERS = mapOf(
        "reflections" to listOf("................", ".......44.......", "......4224......", ".....421124.....", "....42112224....", "...4221222224...", "..422222222224..", "..42wk2222wk24..", ".422kk2222kk224.", ".42222m22m22224.", "4222222mm2222224", "4222222222222234", "4322222222222334", ".43333333333334.", "..444444444444..", "................"),
        "gusts" to listOf("................", "......1111......", "....11222211.11.", "...1222222221221", "..12222222222221", ".122222222222222", "122wk22222wk2222", "122kk22222kk2221", "12222m222m222221", "1222222mm2222231", ".12222222222231.", "..133333333331..", "...3.333333.3...", "..3...3..3...3..", "................", "................"),
        "minds" to listOf(".....111111.....", "...1122222211...", "..12ff22222ff21.", ".122ff222222221.", "1222222ff2222221", "12ff222ff222ff21", "1222222222222221", ".33333333333333.", "...5555555555...", "...5wk5555wk5...", "...5kk5555kk5...", "...55m5mm5m55...", "....55555555....", "....56655665....", "....66.66.66....", "................"),
        "bumble" to listOf("...k........k...", "....k......k....", ".....122221.....", "...1122222211...", "..122222222221..", "..12wk2222wk21..", "..12kk2222kk21..", "..122m2222m221..", "ww.1122mm2211.ww", "www.kkkkkkkk.www", "www1222222221www", ".ww.kkkkkkkk.ww.", "....12222221....", ".....kkkkkk.....", "......k..k......", "................"),
        "leaves" to listOf(".....111111.....", "...1122222211...", "..122223222221..", ".12f22222222f21.", "1222222f22222221", "12222wk2222wk221", "1232kk22222kk231", "12222m2mm2m22221", ".13222222f222231", "..133222222331..", "...1333333331...", "......5665......", "......5665......", ".....556655.....", "....55.66.55....", "................"),
    )
    /** Palette swaps per engine, like GBC monster variants. */
    private val PALETTES: Map<String, List<Map<Char, Int>>> = mapOf(
        "reflections" to listOf(mapOf('1' to 0xFFC9F2EC.toInt(), '2' to 0xFF3FB8AF.toInt(), '3' to 0xFF2E968F.toInt(), '4' to 0xFF22706B.toInt()), mapOf('1' to 0xFFFFE0B8.toInt(), '2' to 0xFFF4A259.toInt(), '3' to 0xFFE8742C.toInt(), '4' to 0xFFB8521B.toInt()), mapOf('1' to 0xFFE2F5B8.toInt(), '2' to 0xFF9ACD5A.toInt(), '3' to 0xFF6FA84A.toInt(), '4' to 0xFF3F7A3A.toInt()), mapOf('1' to 0xFFF4D5F2.toInt(), '2' to 0xFFC792D8.toInt(), '3' to 0xFF9B6BB8.toInt(), '4' to 0xFF6E4A8C.toInt())),
        "gusts" to listOf(mapOf('1' to 0xFF8FD9D1.toInt(), '2' to 0xFFFFFDF5.toInt(), '3' to 0xFFC6E2E2.toInt()), mapOf('1' to 0xFFF4A259.toInt(), '2' to 0xFFFFF1DE.toInt(), '3' to 0xFFF6C9A4.toInt()), mapOf('1' to 0xFF6F8FB0.toInt(), '2' to 0xFFE3ECF5.toInt(), '3' to 0xFFAFC2D8.toInt()), mapOf('1' to 0xFFE5B94A.toInt(), '2' to 0xFFFFF7D6.toInt(), '3' to 0xFFF3DE96.toInt())),
        "minds" to listOf(mapOf('1' to 0xFFB8521B.toInt(), '2' to 0xFFE8742C.toInt(), '3' to 0xFFF4A259.toInt(), 'f' to 0xFFFFF6E5.toInt(), '5' to 0xFFF7EFDC.toInt(), '6' to 0xFFC9A27A.toInt()), mapOf('1' to 0xFF22706B.toInt(), '2' to 0xFF3FB8AF.toInt(), '3' to 0xFF8FD9D1.toInt(), 'f' to 0xFFFFFDF5.toInt(), '5' to 0xFFF7EFDC.toInt(), '6' to 0xFFC9A27A.toInt()), mapOf('1' to 0xFF8C3B5A.toInt(), '2' to 0xFFD9607A.toInt(), '3' to 0xFFF2A0B0.toInt(), 'f' to 0xFFFFF6E5.toInt(), '5' to 0xFFF7EFDC.toInt(), '6' to 0xFFC9A27A.toInt()), mapOf('1' to 0xFF6E4A8C.toInt(), '2' to 0xFF9B6BB8.toInt(), '3' to 0xFFC792D8.toInt(), 'f' to 0xFFF6C26B.toInt(), '5' to 0xFFF7EFDC.toInt(), '6' to 0xFFC9A27A.toInt())),
        "bumble" to listOf(mapOf('1' to 0xFFB8822A.toInt(), '2' to 0xFFF6C26B.toInt(), 'w' to 0xFFE8F7F5.toInt()), mapOf('1' to 0xFFB8521B.toInt(), '2' to 0xFFF4A259.toInt(), 'w' to 0xFFE8F7F5.toInt()), mapOf('1' to 0xFF22706B.toInt(), '2' to 0xFF8FD9D1.toInt(), 'w' to 0xFFFFFDF5.toInt()), mapOf('1' to 0xFF8C3B5A.toInt(), '2' to 0xFFF2A0B0.toInt(), 'w' to 0xFFFFFDF5.toInt())),
        "leaves" to listOf(mapOf('1' to 0xFF2C5A30.toInt(), '2' to 0xFF6FA84A.toInt(), '3' to 0xFF3F7A3A.toInt(), 'f' to 0xFFE8742C.toInt(), '5' to 0xFF7A4A2A.toInt(), '6' to 0xFFA8774C.toInt()), mapOf('1' to 0xFF7A3B12.toInt(), '2' to 0xFFE8742C.toInt(), '3' to 0xFFB8521B.toInt(), 'f' to 0xFFF6C26B.toInt(), '5' to 0xFF7A4A2A.toInt(), '6' to 0xFFA8774C.toInt()), mapOf('1' to 0xFF3F7A3A.toInt(), '2' to 0xFFA9CF6A.toInt(), '3' to 0xFF6FA84A.toInt(), 'f' to 0xFFF2A0B0.toInt(), '5' to 0xFF7A4A2A.toInt(), '6' to 0xFFA8774C.toInt()), mapOf('1' to 0xFF8C5A12.toInt(), '2' to 0xFFF6C26B.toInt(), '3' to 0xFFE5A43A.toInt(), 'f' to 0xFFD9607A.toInt(), '5' to 0xFF7A4A2A.toInt(), '6' to 0xFFA8774C.toInt())),
    )
    private val critterCache = HashMap<String, Sprite>()
    fun critter(engineId: String, variant: Int): Sprite = critterCache.getOrPut("$engineId$variant") {
        val rows = CRITTERS[engineId] ?: CRITTERS.getValue("minds")
        val pals = PALETTES[engineId] ?: PALETTES.getValue("minds")
        val pal = pals[Math.floorMod(variant, pals.size)]
        // Unlisted shade letters fall back to the base colour.
        val colors = HashMap<Char, Int>(CRITTER_BASE).apply { putAll(pal); for (ch in "123456f") if (ch !in this) put(ch, pal['3'] ?: pal['2'] ?: INK) }
        Sprite(rows, colors, outline = INK)
    }

}

/** Flower colour schemes: outer petals, petal highlight, inner petals, centre. */
private val FLOWER_SCHEMES = listOf(
    intArrayOf(0xFFD9607A.toInt(), 0xFFF2A0B0.toInt(), 0xFFFFF6E5.toInt(), 0xFFF6C26B.toInt()),
    intArrayOf(0xFF3FB8AF.toInt(), 0xFF8FD9D1.toInt(), 0xFFFFFDF5.toInt(), 0xFFE8742C.toInt()),
    intArrayOf(0xFF9B6BB8.toInt(), 0xFFC792D8.toInt(), 0xFFF2A0B0.toInt(), 0xFFF6C26B.toInt()),
    intArrayOf(0xFFE8742C.toInt(), 0xFFF4A259.toInt(), 0xFFF6C26B.toInt(), 0xFF7A4A2A.toInt()),
    intArrayOf(0xFF6FA8DC.toInt(), 0xFFB9D8F2.toInt(), 0xFFFFFDF5.toInt(), 0xFFF6C26B.toInt()),
    intArrayOf(0xFFF6C26B.toInt(), 0xFFFFE9A8.toInt(), 0xFFE8742C.toInt(), 0xFF7A4A2A.toInt()),
    intArrayOf(0xFFF28CB1.toInt(), 0xFFFFD0E0.toInt(), 0xFFD9607A.toInt(), 0xFFFFF6E5.toInt()),
)
private const val INK = 0xFF201418.toInt()

private fun shade(c: Int, k: Float): Int {
    val r = (c shr 16 and 255); val g = (c shr 8 and 255); val b = (c and 255)
    return if (k >= 0) (0xFF shl 24) or ((r + ((255 - r) * k).toInt()) shl 16) or ((g + ((255 - g) * k).toInt()) shl 8) or (b + ((255 - b) * k).toInt())
    else (0xFF shl 24) or (((r * (1 + k)).toInt()) shl 16) or (((g * (1 + k)).toInt()) shl 8) or ((b * (1 + k)).toInt())
}

private const val FN = 64
private val flowerCache = object : LinkedHashMap<Long, Bitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Bitmap>?) = size > 80
}

/**
 * A flower drawn on a fine pixel grid: shaded petals (deep at the base,
 * light at the tips), a seeded centre, a curving stem and two veined leaves.
 * A closed bud at bloom 0, opening fully at bloom 1. `s` sets the size.
 * Rendered once per variant/bloom step and cached.
 */
fun Canvas.flower(cx: Float, cy: Float, s: Float, bloom: Float, variant: Int, time: Float, sparkle: Boolean) {
    val cell = maxOf(1f, Px.u * s * 0.5f)
    val bq = (bloom.coerceIn(0f, 1f) * 16).toInt()
    val key = (Math.floorMod(variant, 1000).toLong() shl 40) or (bq.toLong() shl 32) or (cell * 100).toInt().toLong()
    val bmp = flowerCache.getOrPut(key) { renderFlower(variant, bq / 16f, cell) }
    // the bitmap's (32, 20) cell is the flower's centre
    drawBitmap(bmp, cx - 32 * cell, cy - 20 * cell, null)
    if (sparkle) for (i in 0 until 6) {
        val a = time * 2f + i * 1.047f
        val rr = cell * (18f + 4f * kotlin.math.sin(time * 5 + i))
        if (kotlin.math.sin(time * 9f + i * 2.3f) > 0.2f) dot(cx + kotlin.math.cos(a) * rr, cy + kotlin.math.sin(a) * rr * 0.8f, if (i % 2 == 0) Pal.CREAM else FLOWER_SCHEMES[i % FLOWER_SCHEMES.size][1])
    }
}

private fun renderFlower(variant: Int, bloom: Float, cell: Float): Bitmap {
    val grid = IntArray(FN * FN)
    fun put(x: Int, y: Int, c: Int) { if (x in 0 until FN && y in 0 until FN) grid[y * FN + x] = c }
    val sc = FLOWER_SCHEMES[Math.floorMod(variant, FLOWER_SCHEMES.size)]
    val petal = sc[0]; val tip = sc[1]; val inner = sc[2]; val centre = sc[3]
    val ox = 32; val oy = 20
    val lean = (Math.floorMod(variant * 7, 5) - 2) * 0.35f
    // stem: a gentle curve with a lit edge
    val stemDark = 0xFF3F6A34.toInt(); val stemMid = 0xFF5E8C46.toInt(); val stemLight = 0xFF8DB866.toInt()
    for (k in 2..26) {
        val x = ox + Math.round(lean * kotlin.math.sin(k / 26f * 3.1f) * 2.5f)
        put(x, oy + k, stemMid); put(x + 1, oy + k, stemDark); if (k % 3 == 0) put(x, oy + k, stemLight)
    }
    // two leaves with a midrib
    fun leaf(baseY: Int, dir: Int, len: Int) {
        val bx = ox + Math.round(lean * kotlin.math.sin(baseY / 26f * 3.1f) * 2.5f)
        for (t in 0..len) {
            val f = t / len.toFloat()
            val wid = (kotlin.math.sin(f * Math.PI).toFloat() * 2.6f)
            val lx = bx + dir * (t + 1); val ly = oy + baseY - Math.round(f * len * 0.55f)
            for (w in -Math.round(wid)..Math.round(wid)) put(lx, ly + w, if (w < 0) stemLight else if (w > 0) stemDark else stemMid)
            put(lx, ly, if (t % 2 == 0) shade(stemLight, 0.25f) else stemMid)
        }
    }
    leaf(18, -1, 8); leaf(23, 1, 7)
    if (bloom < 0.08f) {
        // a closed bud wrapped in sepals
        for (y in -6..1) for (x in -3..3) {
            val dy = (y + 2.5f) / 4.2f; val dx = x / (3.2f * (1f - maxOf(0f, -dy) * 0.55f))
            if (dx * dx + dy * dy <= 1f) put(ox + x, oy + y, if (x < 0) shade(petal, 0.15f) else if (x > 1) shade(petal, -0.25f) else petal)
        }
        for (x in -3..3) put(ox + x, oy + 1 - kotlin.math.abs(x) / 2, if (x < 0) stemLight else stemMid)
    } else {
        val n = 5 + Math.floorMod(variant, 3)
        val len = 3.5f + bloom * 9f
        val wid = 1.6f + bloom * 2.6f
        val rot = Math.floorMod(variant * 13, 10) * 0.1f
        // back ring of petals (darker) then the front ring
        for (ring in 0..1) {
            if (ring == 0 && bloom < 0.45f) continue
            val l = if (ring == 0) len * 0.9f else len
            for (i in 0 until n) {
                val a = rot + (i + if (ring == 0) 0.5f else 0f) * (2 * Math.PI.toFloat() / n)
                val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a) * 0.82f
                val steps = (l * 2).toInt()
                for (t in 0..steps) {
                    val f = t / steps.toFloat()
                    val along = f * l
                    val half = wid * kotlin.math.sin(Math.PI.toFloat() * (0.15f + f * 0.85f)).coerceAtLeast(0.4f)
                    var w = -half
                    while (w <= half) {
                        val x = ox + Math.round(ca * along - sa / 0.82f * w * 0.82f)
                        val y = oy + Math.round(sa * along + ca * w * 0.82f)
                        val tone = when { f < 0.3f -> shade(petal, -0.3f); f > 0.78f -> tip; w > half * 0.45f -> shade(petal, -0.12f); else -> petal }
                        put(x, y, if (ring == 0) shade(tone, -0.22f) else tone)
                        w += 0.5f
                    }
                    if (ring == 1 && t % 2 == 0 && f in 0.25f..0.7f) put(ox + Math.round(ca * along), oy + Math.round(sa * along), shade(petal, -0.2f)) // vein
                }
            }
        }
        // inner petals and the seeded centre
        if (bloom > 0.35f) for (i in 0 until n) {
            val a = rot + (i + 0.5f) * (2 * Math.PI.toFloat() / n)
            for (t in 1..3) put(ox + Math.round(kotlin.math.cos(a) * t * (1f + bloom)), oy + Math.round(kotlin.math.sin(a) * t * (1f + bloom) * 0.82f), inner)
        }
        val cr = 1.6f + bloom * 1.6f
        for (y in -4..4) for (x in -4..4) {
            val d = kotlin.math.sqrt((x * x + y * y / 0.8f).toFloat())
            if (d <= cr) put(ox + x, oy + y, if ((x + y) % 2 == 0) shade(centre, -0.28f) else if (x < 0 && y < 0) shade(centre, 0.3f) else centre)
        }
    }
    // soft outline: each outside pixel next to a coloured one takes a darkened copy of it
    val out = grid.copyOf()
    for (y in 0 until FN) for (x in 0 until FN) if (grid[y * FN + x] == 0) {
        var nb = 0
        if (x > 0 && grid[y * FN + x - 1] != 0) nb = grid[y * FN + x - 1]
        else if (x < FN - 1 && grid[y * FN + x + 1] != 0) nb = grid[y * FN + x + 1]
        else if (y > 0 && grid[(y - 1) * FN + x] != 0) nb = grid[(y - 1) * FN + x]
        else if (y < FN - 1 && grid[(y + 1) * FN + x] != 0) nb = grid[(y + 1) * FN + x]
        if (nb != 0) out[y * FN + x] = shade(nb, -0.62f)
    }
    val bmp = Bitmap.createBitmap((FN * cell).toInt() + 1, (FN * cell).toInt() + 1, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    for (y in 0 until FN) for (x in 0 until FN) { val v = out[y * FN + x]; if (v != 0) c.fill(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, v) }
    return bmp
}
