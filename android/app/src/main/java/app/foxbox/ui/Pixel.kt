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
    // Valley extras: meadow greens and sunset tones.
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

object Px {
    var density = 1f; private set
    /** One "art pixel" in screen pixels. */
    var u = 3f; private set
    lateinit var head: Typeface; private set
    lateinit var body: Typeface; private set

    fun init(c: Context) {
        density = c.resources.displayMetrics.density
        u = (3 * density).coerceAtLeast(2f)
        head = runCatching { Typeface.createFromAsset(c.assets, "fonts/PressStart2P-Regular.ttf") }.getOrDefault(Typeface.MONOSPACE)
        body = runCatching { Typeface.createFromAsset(c.assets, "fonts/PixelifySans.ttf") }.getOrDefault(Typeface.MONOSPACE)
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
    /** The small kitsune for the Valley (tails are drawn separately). */
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

/**
 * An ornate pixel flower with an ink outline: a closed bud at bloom 0,
 * opening to two rings of petals around a seeded centre at bloom 1.
 * `s` = art pixels per flower pixel.
 */
fun Canvas.flower(cx: Float, cy: Float, s: Float, bloom: Float, variant: Int, time: Float, sparkle: Boolean) {
    val u = Px.u * s
    val gx = (cx / u).toInt(); val gy = (cy / u).toInt()
    val sc = FLOWER_SCHEMES[Math.floorMod(variant, FLOWER_SCHEMES.size)]
    val (o, oh, inn, cen) = listOf(sc[0], sc[1], sc[2], sc[3])
    fun shape(px: (Int, Int, Int) -> Unit) {
        for (k in 1..10) px(0, k, Pal.GREEN_D)
        for ((dx, dy) in listOf(1 to 5, 2 to 5, 2 to 4, 3 to 4, -1 to 7, -2 to 7, -2 to 6, -3 to 6)) px(dx, dy, Pal.GREEN)
        px(3, 4, Pal.GREEN_L); px(-3, 6, Pal.GREEN_L)
        if (bloom < 0.08f) {
            px(0, -3, oh); px(-1, -2, o); px(0, -2, o); px(1, -2, o); px(-1, -1, o); px(0, -1, oh); px(1, -1, o)
            px(-1, 0, Pal.GREEN); px(0, 0, Pal.GREEN_D); px(1, 0, Pal.GREEN)
            return
        }
        val r = 1.5f + bloom * 4.5f
        val steps = maxOf(2, r.toInt())
        for (i in 0 until 8) {
            val a = i * Math.PI.toFloat() / 4
            val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
            for (t in 0 until steps) {
                val d = r * 0.35f + t * (r * 0.65f / steps)
                val x = ca * d; val y = sa * d
                px(Math.round(x), Math.round(y), if (t == steps - 1) oh else o)
                px(Math.round(x - sa * 0.9f), Math.round(y + ca * 0.9f), o)
            }
        }
        if (bloom > 0.35f) for (i in 0 until 6) {
            val a = i * Math.PI.toFloat() / 3 + Math.PI.toFloat() / 6
            for (t in 0..1) { val d = r * 0.55f * (0.5f + t * 0.5f); px(Math.round(kotlin.math.cos(a) * d), Math.round(kotlin.math.sin(a) * d), inn) }
        }
        for (dx in -1..1) for (dy in -1..1) px(dx, dy, cen)
        if (bloom > 0.6f) { val seed = if (cen == Pal.BROWN) Pal.SUN else Pal.BROWN; px(-1, -1, seed); px(1, 1, seed); px(1, -1, seed); px(-1, 1, seed) }
        px(0, 0, if (cen == 0xFFFFF6E5.toInt()) Pal.SUN else 0xFFFFF6E5.toInt())
    }
    // Ink outline first, then colours on top.
    shape { x, y, _ ->
        fill((gx + x - 1) * u, (gy + y) * u, (gx + x + 2) * u, (gy + y + 1) * u, INK)
        fill((gx + x) * u, (gy + y - 1) * u, (gx + x + 1) * u, (gy + y + 2) * u, INK)
    }
    shape { x, y, c -> fill((gx + x) * u, (gy + y) * u, (gx + x + 1) * u, (gy + y + 1) * u, c) }
    if (sparkle) for (i in 0 until 6) {
        val a = time * 2f + i * 1.047f
        val rr = u * (8f + 3f * kotlin.math.sin(time * 5 + i))
        if (kotlin.math.sin(time * 9f + i * 2.3f) > 0.2f) dot(cx + kotlin.math.cos(a) * rr, cy + kotlin.math.sin(a) * rr * 0.8f, if (i % 2 == 0) Pal.CREAM else FLOWER_SCHEMES[i % FLOWER_SCHEMES.size][1])
    }
}
