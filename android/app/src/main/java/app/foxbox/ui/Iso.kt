package app.foxbox.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Isometric pixel art for the Valley and the block icons. Everything is
 * drawn from flat shapes on the art-pixel grid (2:1 slopes), with no faces:
 * clouds, rowboats, trees, mushrooms, beehive boxes, pickup trucks, the
 * cabin, the village transport, biplanes, the tombstone and the little fox.
 * `g` is the size of one art pixel in screen pixels.
 */
object Iso {
    /** Outlines are warm dark brown, like the XY pad sprites. */
    const val INK = 0xFF3D2414.toInt()
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val path = Path()

    fun snap(v: Float, g: Float) = Math.round(v / g) * g

    /** Filled polygon from screen points. */
    fun Canvas.poly(color: Int, vararg p: Float) {
        path.reset(); path.moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        path.close(); paint.color = color; drawPath(path, paint)
    }

    /** Pixel disc (rows of runs) centred at (cx, cy), radius r art pixels, squashed vertically by `sy`. */
    fun Canvas.disc(cx: Float, cy: Float, r: Float, g: Float, color: Int, sy: Float = 1f, topHalf: Boolean = false) {
        val rows = (r * sy).toInt().coerceAtLeast(1)
        val x0 = snap(cx, g); val y0 = snap(cy, g)
        for (j in -rows..(if (topHalf) 0 else rows)) {
            val k = j / sy
            val half = sqrt((r * r - k * k).coerceAtLeast(0f))
            if (half <= 0f) continue
            val hw = Math.round(half).coerceAtLeast(1) * g
            fill(x0 - hw, y0 + j * g, x0 + hw, y0 + (j + 1) * g, color)
        }
    }

    class Box(val top: Int, val left: Int, val right: Int)

    /**
     * An isometric box. (x, y) is its front-bottom corner on screen; w runs up
     * to the right, d up to the left, h up; all in art pixels.
     */
    fun Canvas.isoBox(x: Float, y: Float, g: Float, w: Float, d: Float, h: Float, c: Box, outline: Boolean = true) {
        val fx = snap(x, g); val fy = snap(y, g)
        val rx = fx + 2 * w * g; val ry = fy - w * g
        val lx = fx - 2 * d * g; val ly = fy - d * g
        val bx = rx + lx - fx; val by = ry + ly - fy
        val up = h * g
        if (outline) for ((ox, oy) in OFFS) poly(INK, lx + ox * g, ly + oy * g, fx + ox * g, fy + oy * g, rx + ox * g, ry + oy * g,
            rx + ox * g, ry - up + oy * g, bx + ox * g, by - up + oy * g, lx + ox * g, ly - up + oy * g)
        poly(c.left, lx, ly, fx, fy, fx, fy - up, lx, ly - up)
        poly(c.right, fx, fy, rx, ry, rx, ry - up, fx, fy - up)
        poly(c.top, fx, fy - up, rx, ry - up, bx, by - up, lx, ly - up)
    }
    val OFFS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    /** Screen point on a box's surface: (u along w, v along d, z up) from its front-bottom corner. */
    fun at(x: Float, y: Float, g: Float, u: Float, v: Float, z: Float) = floatArrayOf(snap(x, g) + (2 * u - 2 * v) * g, snap(y, g) - (u + v + z) * g)

    /** A quad on the w-face (right face): u0..u1 along w, z0..z1 up. */
    fun Canvas.rightQuad(x: Float, y: Float, g: Float, u0: Float, u1: Float, z0: Float, z1: Float, color: Int) {
        val a = at(x, y, g, u0, 0f, z0); val b = at(x, y, g, u1, 0f, z0); val c = at(x, y, g, u1, 0f, z1); val d = at(x, y, g, u0, 0f, z1)
        poly(color, a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1])
    }
    /** A quad on the d-face (left face): v0..v1 along d, z0..z1 up. */
    fun Canvas.leftQuad(x: Float, y: Float, g: Float, v0: Float, v1: Float, z0: Float, z1: Float, color: Int) {
        val a = at(x, y, g, 0f, v0, z0); val b = at(x, y, g, 0f, v1, z0); val c = at(x, y, g, 0f, v1, z1); val d = at(x, y, g, 0f, v0, z1)
        poly(color, a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1])
    }

    /** A gable roof sitting on a box of (w, d) whose walls are h tall; ridge runs along w. */
    fun Canvas.gableRoof(x: Float, y: Float, g: Float, w: Float, d: Float, h: Float, rise: Float, front: Int, back: Int, gable: Int, over: Float = 1f) {
        val f = at(x, y, g, -over, -over, h); val r = at(x, y, g, w + over, -over, h)
        val l = at(x, y, g, -over, d + over, h); val b = at(x, y, g, w + over, d + over, h)
        val rl = at(x, y, g, -over, d / 2, h + rise); val rr = at(x, y, g, w + over, d / 2, h + rise)
        for ((ox, oy) in OFFS) poly(INK, f[0] + ox * g, f[1] + oy * g, r[0] + ox * g, r[1] + oy * g, rr[0] + ox * g, rr[1] + oy * g, b[0] + ox * g, b[1] + oy * g, l[0] + ox * g, l[1] + oy * g, rl[0] + ox * g, rl[1] + oy * g)
        poly(back, l[0], l[1], b[0], b[1], rr[0], rr[1], rl[0], rl[1])
        val gf = at(x, y, g, 0f, 0f, h); val gl = at(x, y, g, 0f, d, h); val gt = at(x, y, g, 0f, d / 2, h + rise - over * 0.5f)
        poly(gable, gf[0], gf[1], gl[0], gl[1], gt[0], gt[1])
        poly(front, f[0], f[1], r[0], r[1], rr[0], rr[1], rl[0], rl[1])
        // ridge highlight
        poly(lighten(front, 0.25f), rl[0], rl[1], rr[0], rr[1], rr[0], rr[1] + g, rl[0], rl[1] + g)
    }

    fun lighten(c: Int, k: Float): Int {
        val r = (c shr 16 and 255); val gg = (c shr 8 and 255); val b = (c and 255)
        return (c and 0xFF000000.toInt()) or ((r + ((255 - r) * k).toInt()) shl 16) or ((gg + ((255 - gg) * k).toInt()) shl 8) or (b + ((255 - b) * k).toInt())
    }
    fun darken(c: Int, k: Float): Int {
        val r = (c shr 16 and 255); val gg = (c shr 8 and 255); val b = (c and 255)
        return (c and 0xFF000000.toInt()) or (((r * (1 - k)).toInt()) shl 16) or (((gg * (1 - k)).toInt()) shl 8) or ((b * (1 - k)).toInt())
    }
    fun box(base: Int) = Box(lighten(base, 0.22f), darken(base, 0.28f), base)

    /** Soft shadow on the ground under an object. */
    fun Canvas.shadow(cx: Float, cy: Float, r: Float, g: Float) = disc(cx, cy, r, g, Pal.alpha(0xFF2C5A30.toInt(), 0.28f), 0.45f)

    /** Draws in art pixels relative to (cx, by): x to the right, y up is negative. Front-facing 3/4 view, SNES RPG style. */
    class Pen(val c: Canvas, cx: Float, by: Float, val g: Float) {
        val ox = snap(cx, g); val oy = snap(by, g)
        fun r(l: Float, t: Float, rr: Float, b: Float, col: Int) = c.fill(ox + l * g, oy + t * g, ox + rr * g, oy + b * g, col)
        /** A rectangle with a one-pixel ink outline. */
        fun o(l: Float, t: Float, rr: Float, b: Float, col: Int) { r(l - 1, t - 1, rr + 1, b + 1, INK); r(l, t, rr, b, col) }
        fun d(x: Float, y: Float, rad: Float, col: Int, sy: Float = 1f, top: Boolean = false) = with(Iso) { c.disc(ox + x * g, oy + y * g, rad, g, col, sy, top) }
        fun p(col: Int, vararg pts: Float) { val a = FloatArray(pts.size) { if (it % 2 == 0) ox + pts[it] * g else oy + pts[it] * g }; with(Iso) { c.poly(col, *a) } }
        /** Polygon with an ink outline. */
        fun po(col: Int, vararg pts: Float) {
            for ((dx, dy) in OFFS) { val a = FloatArray(pts.size) { if (it % 2 == 0) ox + (pts[it] + dx) * g else oy + (pts[it] + dy) * g }; with(Iso) { c.poly(INK, *a) } }
            p(col, *pts)
        }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, col: Int) {
            val n = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)).toInt().coerceAtLeast(1)
            for (k in 0..n) { val f = k / n.toFloat(); r(Math.round(x0 + (x1 - x0) * f).toFloat(), Math.round(y0 + (y1 - y0) * f).toFloat(), Math.round(x0 + (x1 - x0) * f) + 1f, Math.round(y0 + (y1 - y0) * f) + 1f, col) }
        }
        /** A cottage: wall w wide and wallH tall, with a pitched roof seen from the front. */
        fun house(x: Float, w: Float, wallH: Float, roofH: Float, wall: Int, roof: Int, y: Float = 0f) {
            val hw = w / 2
            o(x - hw, y - wallH, x + hw, y, wall)
            r(x + hw - 2, y - wallH, x + hw, y, darken(wall, 0.15f))
            po(roof, x - hw - 2, y - wallH, x + hw + 2, y - wallH, x + hw - 1, y - wallH - roofH, x - hw + 1, y - wallH - roofH)
            var k = y - wallH - 3
            while (k > y - wallH - roofH) { r(x - hw + 1, k, x + hw - 1, k + 1, darken(roof, 0.22f)); k -= 3 }
            r(x - hw + 1, y - wallH - roofH, x + hw - 1, y - wallH - roofH + 1, lighten(roof, 0.3f))
            r(x - hw - 2, y - wallH, x + hw + 2, y - wallH + 1, darken(roof, 0.4f))
        }
    }

    // ------------------------------------------------------------------ palettes (variants)

    // Everything below uses the XY pads' palette: cream, aqua, sun, orange, warm greens and brown.
    private val CLOUDS = listOf(
        intArrayOf(0xFFFFFDF5.toInt(), 0xFFF7EFDC.toInt(), 0xFF8FD9D1.toInt(), 0xFF22706B.toInt()),
        intArrayOf(0xFFFFF1DE.toInt(), 0xFFF6C9A4.toInt(), 0xFFF4A259.toInt(), 0xFFB8521B.toInt()),
        intArrayOf(0xFFFFFDF5.toInt(), 0xFFDAF0E4.toInt(), 0xFF8FD9D1.toInt(), 0xFF22706B.toInt()),
        intArrayOf(0xFFFFF7D6.toInt(), 0xFFF3DE96.toInt(), 0xFFF6C26B.toInt(), 0xFFB8822A.toInt()),
    )
    private val HULLS = listOf(0xFFB8521B.toInt(), 0xFF22706B.toInt(), 0xFFF7EFDC.toInt(), 0xFF7A4A2A.toInt())
    private val TREES = listOf(
        intArrayOf(0xFFA9CF6A.toInt(), 0xFF6FA84A.toInt(), 0xFF3F7A3A.toInt()),
        intArrayOf(0xFFF4A259.toInt(), 0xFFE8742C.toInt(), 0xFFB8521B.toInt()),
        intArrayOf(0xFFF2A0B0.toInt(), 0xFFD9786A.toInt(), 0xFFA8584E.toInt()),
        intArrayOf(0xFFF6C26B.toInt(), 0xFFD9A03A.toInt(), 0xFF9C6A22.toInt()),
    )
    private val CAPS = listOf(0xFFE8742C.toInt(), 0xFFB8521B.toInt(), 0xFF3FB8AF.toInt(), 0xFFD9786A.toInt())
    private val HIVES = listOf(0xFFF7EFDC.toInt(), 0xFFD9A03A.toInt(), 0xFF8FD9D1.toInt(), 0xFFF4A259.toInt())
    private val TRUCKS = listOf(0xFFE8742C.toInt(), 0xFF3FB8AF.toInt(), 0xFFF7EFDC.toInt(), 0xFFA8774C.toInt())
    val PLANES = intArrayOf(0xFFD9605A.toInt(), 0xFFF0B84A.toInt(), 0xFF4A8FC8.toInt())

    // ------------------------------------------------------------------ the tombstone

    /** A 50%-opacity outlined tombstone reading R.I.P., grass on each side. `hot` = something is over it. */
    fun Canvas.tombstone(cx: Float, bottom: Float, height: Float, hot: Boolean, text: Paint) {
        val g = (height / 22f).coerceAtLeast(2f)
        val alpha = if (hot) 0.85f else 0.5f
        val w = 8f; val h = 15f
        val l = snap(cx - w * g, g); val r = snap(cx + w * g, g); val b = snap(bottom - 3 * g, g); val tt = snap(b - h * g, g)
        val stroke = Pal.alpha(0xFFF7EFDC.toInt(), alpha)
        val inside = Pal.alpha(if (hot) 0xFFB8521B.toInt() else 0xFF3D2414.toInt(), alpha * 0.45f)
        // body with a rounded top
        fill(l + g, tt + 3 * g, r - g, b, inside)
        disc((l + r) / 2, tt + 4 * g, w - 1, g, inside, 0.5f)
        fill(l, tt + 3 * g, l + g, b, stroke); fill(r - g, tt + 3 * g, r, b, stroke)
        for (i in 0..16) {
            val a = Math.PI * i / 16
            val px = (l + r) / 2 - (kotlin.math.cos(a) * (w - 0.5f) * g).toFloat(); val py = tt + 4 * g - (kotlin.math.sin(a) * 4 * g).toFloat()
            fill(snap(px, g), snap(py, g), snap(px, g) + g, snap(py, g) + g, stroke)
        }
        fill(l - 2 * g, b, r + 2 * g, b + g, stroke)
        text.color = stroke
        // size the words to fit inside the stone
        text.textSize = g * 3.4f
        while (text.measureText("R.I.P.") > (r - l) - 4 * g && text.textSize > 6f) text.textSize -= 1f
        textCentered("R.I.P.", (l + r) / 2, tt + 8.5f * g, text)
        // grass bits on each side
        val grass = Pal.alpha(0xFF6FA84A.toInt(), alpha + 0.1f)
        for (side in listOf(-1f, 1f)) for (k in 0 until 5) {
            val gx = (if (side < 0) l - 3 * g else r + g) + side * k * 1.5f * g
            val gh = (2 + (k * 7) % 4) * g
            fill(snap(gx, g), snap(b - gh + g, g), snap(gx, g) + g, b + g, grass)
        }
    }

    // ------------------------------------------------------------------ small icons for the block rail

    val iconHive by lazy { Sprite(listOf(".DDDDDDDD.", "DwwwwwwwwD", ".hhhhhhhh.", ".hhhhhhhh.", ".hDDDDDDh.", ".rrrrrrrr.", ".hhhhhhhh.", ".hhh..hhh.", ".BB....BB."), mapOf('h' to 0xFFE2B455.toInt(), 'r' to 0xFFB8822A.toInt())) }
    val iconTruck by lazy { Sprite(listOf("....OOOO....", "....OaaO....", "OOOOOaaOOOO.", "OOOOOOOOOOOw", "rrrrrrrrrrrr", ".DD.....DD..", ".DD.....DD.."), mapOf('a' to 0xFF8FD9D1.toInt())) }
    val iconCloud by lazy { Sprite(listOf("...WWW....", "..WWWWW.W.", ".WWWWWWWWW", "WWWWWWWWWW", "aaaaaaaaaa"), outline = Pal.AQUA_D) }
    val iconBoat by lazy { Sprite(listOf("....b.....", "....b.....", "BBBBBBBBBB", ".BrrrrrrB.", "..BBBBBB..", "aaaaaaaaaa"), mapOf('r' to 0xFF5A3A22.toInt())) }
    val iconTree by lazy { Sprite(listOf("..GGGG..", ".GgGGGG.", "GgGGGGGG", "GGGGGGqG", ".GGGGqG.", "...BB...", "...BB..."), mapOf('q' to 0xFF3F7A3A.toInt())) }
    val iconMushroom by lazy { Sprite(listOf("..rrrr....", ".rOOOOr...", "rOOOOOOr..", "..WW..rr..", "..WW.rOOr.", "..WW..WW..", ".WWWW.WW.."), mapOf('r' to 0xFFB8521B.toInt())) }
    val iconFlower by lazy { Sprite(listOf("..o.o..", ".oOoOo.", "oOOWOOo", ".oOoOo.", "..oGo..", "...G...", "..GG...")) }
    /** The rail button for the clip launcher: a few black boxes in the grass. */
    val iconStones by lazy { Sprite(listOf("kkkk......", "kWWk..kkkk", "kkkk..kWWk", "......kkkk", ".kkkk.....", ".kWWk.kkkk", ".kkkk.kWWk", "G.G.G.kkkk"), mapOf('k' to 0xFF0B0B0B.toInt(), 'W' to 0xFFFFFFFF.toInt(), 'G' to 0xFF6FA84A.toInt()), outline = null) }
    val iconValley by lazy { Sprite(listOf("......aa.....", "....aaaaaa...", "..GGG....GG..", ".GGGGG..GGGG.", "GGgGGGGGGGgGG", "GGGGGGgGGGGGG"), outline = null) }
}
