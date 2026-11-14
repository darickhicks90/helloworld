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
        "gusts" -> AQUA; "reflections" -> AQUA_D; "leaves" -> ORANGE_D; "minds" -> ORANGE; "bumble" -> HONEY; else -> BROWN
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
        "gusts" -> cloud; "reflections" -> drop; "leaves" -> leaf; "minds" -> mushroom; "bumble" -> bee; else -> bloom
    }
    private val BEE = listOf("..aa...aa..", ".aWWa.aWWa.", "..aaDDDaa..", "..DhhhhhD..", ".DDDDDDDDD.", ".DhWhhhWhD.", ".DDDDDDDDD.", "..DhhhhhD..", "...DDDDD...")

    // Valley critters (one per engine), with colour variants per clip.
    private val CRITTERS = mapOf(
        "gusts" to listOf("...WWWW.....", "..WWWWWW.WW.", ".WWWWWWWWWWW", "WWWDWWWWDWWW", "WWWWWooWWWWW", ".aaaaaaaaaa."),
        "reflections" to listOf("....A....", "....A....", "...AAA...", "..AAAAA..", ".AaAAAAA.", ".aAAAAAA.", "AADAAADAA", "AAAAooAAA", ".AAAAAAA.", "..qAAAq.."),
        "leaves" to listOf("....gGGg....", "..gGGOGGGg..", ".gGGGGGGGOg.", "gGOGGGGGGGGg", "GGGGDGGGDGGG", "GGGGGGooGGOG", ".GGOGGGGGGG.", "..GGGGGGGG..", ".....BB.....", ".....BB.....", "....bBBb...."),
        "minds" to listOf("...OOOOOO...", "..OWOOOOWO..", ".OOOOWOOOOO.", "OOWOOOOOOWOO", "OOOOOOOOOOOO", "...WWWWWW...", "...WDWWDW...", "...WWooWW...", "...WWWWWW...", "....wwww...."),
        "bumble" to listOf("..aa...aa..", ".aWWa.aWWa.", "..aaDDDaa..", "..DhhhhhD..", ".DDDDDDDDD.", ".DhDhhhDhD.", ".DDDDDDDDD.", "..DhhoohD..", "...DDDDD..."),
    )
    private val VARIANTS = listOf(
        emptyMap(),
        mapOf('O' to Pal.AQUA, 'A' to Pal.ORANGE, 'a' to Pal.ORANGE_L, 'B' to Pal.AQUA_D, 'W' to Pal.CREAM),
        mapOf('O' to Pal.BROWN_L, 'A' to Pal.AQUA_D, 'a' to Pal.AQUA, 'B' to Pal.ORANGE_D, 'W' to Pal.PAPER),
        mapOf('O' to Pal.ORANGE_D, 'A' to Pal.AQUA_L, 'a' to Pal.CREAM, 'B' to Pal.BROWN, 'W' to Pal.AQUA_L),
    )
    private val critterCache = HashMap<String, Sprite>()
    fun critter(engineId: String, variant: Int): Sprite = critterCache.getOrPut("$engineId$variant") {
        Sprite(CRITTERS[engineId] ?: CRITTERS.getValue("minds"), VARIANTS[Math.floorMod(variant, VARIANTS.size)])
    }

}

/** Colours for flower variants: petal, petal highlight. */
val FLOWER_COLORS = listOf(Pal.ORANGE to Pal.ORANGE_L, Pal.AQUA to Pal.AQUA_L, Pal.ROSE to Pal.CREAM, Pal.CREAM to Pal.SUN, Pal.ORANGE_D to Pal.SUN)

/**
 * A pixel flower: a closed bud at bloom 0 opening to full petals at 1.
 * `s` = art pixels per flower pixel.
 */
fun Canvas.flower(cx: Float, cy: Float, s: Float, bloom: Float, variant: Int, time: Float, sparkle: Boolean) {
    val u = Px.u * s
    val (petal, hi) = FLOWER_COLORS[Math.floorMod(variant, FLOWER_COLORS.size)]
    // Stem and leaves
    for (k in 0 until 9) dot(cx - u / 2, cy + k * u, Pal.GREEN_D, s.toInt().coerceAtLeast(1))
    dot(cx + u * 0.5f, cy + u * 4, Pal.GREEN, (s * 2).toInt().coerceAtLeast(1)); dot(cx - u * 2.5f, cy + u * 6, Pal.GREEN, (s * 2).toInt().coerceAtLeast(1))
    if (bloom < 0.08f) {
        // Closed bud
        for (k in -2..1) dot(cx - u, cy + k * u, petal, (s * 2).toInt().coerceAtLeast(1))
        dot(cx - u * 1.5f, cy + u, Pal.GREEN, s.toInt().coerceAtLeast(1)); dot(cx + u * 0.5f, cy + u, Pal.GREEN, s.toInt().coerceAtLeast(1))
    } else {
        val r = u * (1.5f + bloom * 4.5f)
        val petals = 6
        val spin = time * 0.3f * bloom
        for (i in 0 until petals) {
            val a = spin + i * 6.283f / petals
            val px = cx + kotlin.math.cos(a) * r; val py = cy + kotlin.math.sin(a) * r
            val n = (s * (1.5f + bloom * 1.5f)).toInt().coerceAtLeast(1)
            dot(px - n * Px.u / 2, py - n * Px.u / 2, petal, n)
            dot(px - n * Px.u / 4, py - n * Px.u / 4, hi, (n / 2).coerceAtLeast(1))
        }
        dot(cx - u, cy - u, Pal.HONEY, (s * 2).toInt().coerceAtLeast(1))
    }
    if (sparkle) for (i in 0 until 6) {
        val a = time * 2f + i * 1.047f
        val rr = u * (6f + 3f * kotlin.math.sin(time * 5 + i))
        if (kotlin.math.sin(time * 9f + i * 2.3f) > 0.2f) dot(cx + kotlin.math.cos(a) * rr, cy + kotlin.math.sin(a) * rr * 0.8f, if (i % 2 == 0) Pal.CREAM else Pal.SUN)
    }
}
