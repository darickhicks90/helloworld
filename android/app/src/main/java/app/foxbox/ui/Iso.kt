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
    const val INK = 0xFF2A1C16.toInt()
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
    fun Canvas.shadow(cx: Float, cy: Float, r: Float, g: Float) = disc(cx, cy, r, g, Pal.alpha(0xFF1E3A22.toInt(), 0.28f), 0.45f)

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

    private val CLOUDS = listOf(
        intArrayOf(0xFFFFFBF2.toInt(), 0xFFE9E2D2.toInt(), 0xFFB9C9C6.toInt()),
        intArrayOf(0xFFFFF0E0.toInt(), 0xFFF3CFB0.toInt(), 0xFFD89A7A.toInt()),
        intArrayOf(0xFFEEF3F6.toInt(), 0xFFC9D5DE.toInt(), 0xFF8EA1B3.toInt()),
        intArrayOf(0xFFFFF6D8.toInt(), 0xFFF2DFA2.toInt(), 0xFFC9A860.toInt()),
    )
    private val HULLS = listOf(0xFFB8521B.toInt(), 0xFF3F8C86.toInt(), 0xFFEDE3CC.toInt(), 0xFF7A4A2A.toInt())
    private val TREES = listOf(
        intArrayOf(0xFF8DBF5A.toInt(), 0xFF5E9444.toInt(), 0xFF35663A.toInt()),
        intArrayOf(0xFFF2A95A.toInt(), 0xFFD9772E.toInt(), 0xFF9C4A1E.toInt()),
        intArrayOf(0xFFF6C9D2.toInt(), 0xFFE39AAE.toInt(), 0xFFB0677E.toInt()),
        intArrayOf(0xFFB7C76A.toInt(), 0xFF7E944A.toInt(), 0xFF4D6234.toInt()),
    )
    private val CAPS = listOf(0xFFC2552E.toInt(), 0xFF8A5A34.toInt(), 0xFF4E9C95.toInt(), 0xFFB9718F.toInt())
    private val HIVES = listOf(0xFFF1E6CC.toInt(), 0xFFE2B455.toInt(), 0xFF7FC2BA.toInt(), 0xFFB98C5E.toInt())
    private val TRUCKS = listOf(0xFFE8742C.toInt(), 0xFF3FB8AF.toInt(), 0xFFEDE3CC.toInt(), 0xFF9C4A2A.toInt())
    val PLANES = intArrayOf(0xFFD8473A.toInt(), 0xFFF0C23B.toInt(), 0xFF3F7FCF.toInt())

    // ------------------------------------------------------------------ Gusts: cloud

    fun Canvas.cloud(cx: Float, cy: Float, g: Float, variant: Int, t: Float, active: Boolean) {
        val p = CLOUDS[Math.floorMod(variant, CLOUDS.size)]
        val drift = if (active) sin(t * 1.3f) * g else 0f
        val puffs = floatArrayOf(-7f, 1f, 5f, -2f, -3f, 7f, 3f, -4f, 7f, 7f, 0f, 6f, 12f, 2f, 4.5f)
        // underside shade, body, then a highlight on each puff
        var i = 0
        while (i < puffs.size) { disc(cx + puffs[i] * g + drift, cy + puffs[i + 1] * g + g * 1.5f, puffs[i + 2], g, p[2], 0.75f); i += 3 }
        i = 0
        while (i < puffs.size) { disc(cx + puffs[i] * g + drift, cy + puffs[i + 1] * g, puffs[i + 2], g, p[1], 0.75f); i += 3 }
        i = 0
        while (i < puffs.size) { disc(cx + (puffs[i] - 1) * g + drift, cy + (puffs[i + 1] - 1.5f) * g, puffs[i + 2] * 0.6f, g, p[0], 0.7f); i += 3 }
        if (active) for (k in 0 until 4) {
            val ph = (t * 0.6f + k * 0.25f) % 1f
            fill(snap(cx + (-8 + k * 5) * g, g), snap(cy + (6 + ph * 8) * g, g), snap(cx + (-8 + k * 5) * g, g) + g, snap(cy + (6 + ph * 8) * g, g) + g * 2, Pal.alpha(p[2], 1 - ph))
        }
    }

    // ------------------------------------------------------------------ Reflections: rowboat on the pond

    fun Canvas.rowboat(cx: Float, cy: Float, g: Float, variant: Int, t: Float, active: Boolean) {
        val hull = HULLS[Math.floorMod(variant, HULLS.size)]
        val bob = if (active && sin(t * 2.2f) > 0) -1f else 0f
        val pn = Pen(this, cx, cy, g)
        for (k in 0..1) pn.d(0f, 1f, 14f + k * 3 + (if (active) (t * 2 % 3) else 0f), Pal.alpha(0xFFCFEFF0.toInt(), 0.22f - k * 0.08f), 0.3f)
        val oar = if (active) sin(t * 2.2f) * 2f else 0f
        pn.line(-2f, -4f + bob, -9f - oar, 3f, 0xFFD9B27C.toInt()); pn.line(2f, -4f + bob, 9f + oar, 3f, 0xFFD9B27C.toInt())
        pn.d(0f, -2f + bob, 13f, INK, 0.4f)
        pn.d(0f, -2f + bob, 12f, darken(hull, 0.3f), 0.38f)
        pn.d(0f, -3f + bob, 12f, hull, 0.3f)
        pn.d(0f, -3.5f + bob, 10f, 0xFF5A3A22.toInt(), 0.22f)
        pn.r(-5f, -5f + bob, -4f, -2f + bob, 0xFFC99A64.toInt()); pn.r(3f, -5f + bob, 4f, -2f + bob, 0xFFC99A64.toInt())
        pn.r(-9f, -4f + bob, 9f, -3f + bob, lighten(hull, 0.35f))
    }

    // ------------------------------------------------------------------ Leaves: tree

    fun Canvas.tree(cx: Float, cy: Float, g: Float, variant: Int, t: Float, active: Boolean) {
        val p = TREES[Math.floorMod(variant, TREES.size)]
        shadow(cx + 2 * g, cy, 9f, g)
        Pen(this, cx, cy, g).apply { o(-1.5f, -9f, 1.5f, 0f, 0xFF7A4A2A.toInt()); r(0.5f, -9f, 1.5f, 0f, 0xFF5A3420.toInt()) }
        val sway = if (active) sin(t * 1.7f) * g else 0f
        val blobs = floatArrayOf(-5f, -13f, 6f, 5f, -14f, 6f, 0f, -19f, 7f, -2f, -10f, 5f, 4f, -9f, 4f)
        var i = 0
        while (i < blobs.size) { disc(cx + blobs[i] * g + sway, cy + (blobs[i + 1] + 1.5f) * g, blobs[i + 2] + 1, g, INK); i += 3 }
        i = 0
        while (i < blobs.size) { disc(cx + blobs[i] * g + sway, cy + (blobs[i + 1] + 1f) * g, blobs[i + 2], g, p[2]); i += 3 }
        i = 0
        while (i < blobs.size) { disc(cx + (blobs[i] - 0.5f) * g + sway, cy + blobs[i + 1] * g, blobs[i + 2] - 1, g, p[1]); i += 3 }
        i = 0
        while (i < blobs.size) { disc(cx + (blobs[i] - 2f) * g + sway, cy + (blobs[i + 1] - 2f) * g, blobs[i + 2] * 0.45f, g, p[0]); i += 3 }
        if (active) for (k in 0 until 5) {
            val ph = (t * 0.35f + k * 0.2f) % 1f
            val lx = cx + (-8 + k * 4 + sin(t * 2 + k) * 2) * g; val ly = cy + (-12 + ph * 13) * g
            fill(snap(lx, g), snap(ly, g), snap(lx, g) + g, snap(ly, g) + g, Pal.alpha(p[k % 2], 1 - ph * 0.6f))
        }
    }

    // ------------------------------------------------------------------ Minds: mushroom cluster

    fun Canvas.mushrooms(cx: Float, cy: Float, g: Float, variant: Int, t: Float, active: Boolean) {
        val cap = CAPS[Math.floorMod(variant, CAPS.size)]
        shadow(cx, cy, 10f, g)
        val pulse = if (active) (sin(t * 6f) * 0.5f + 0.5f) else 0f
        // dx, dy, cap radius, stem height (back to front)
        val items = floatArrayOf(4f, -2f, 4.5f, 7f, -5f, 0f, 5.5f, 9f, 1f, 2f, 3.5f, 4f)
        for (i in 0 until 3) {
            val dx = items[i * 4]; val dy = items[i * 4 + 1]; val r = items[i * 4 + 2]; val sh = items[i * 4 + 3]
            val bx = cx + dx * g; val by = cy + dy * g
            val sw = if (r > 5) 2f else 1.5f
            // stem with a soft shade on the right
            fill(snap(bx - (sw + 1) * g, g), snap(by - sh * g, g), snap(bx + (sw + 1) * g, g), snap(by, g) + g, INK)
            fill(snap(bx - sw * g, g), snap(by - sh * g, g), snap(bx + sw * g, g), snap(by, g), 0xFFEDE3CC.toInt())
            fill(snap(bx + (sw - 1) * g, g), snap(by - sh * g, g), snap(bx + sw * g, g), snap(by, g), 0xFFCDBF9F.toInt())
            val ty = by - sh * g
            val rr = r + if (i == 1) pulse * 0.8f else 0f
            // dome cap: ink rim, gills underneath, then the cap with a highlight
            disc(bx, ty + g, rr + 1, g, INK, 0.75f, topHalf = true)
            fill(snap(bx - rr * g, g), snap(ty, g), snap(bx + rr * g, g) + g, snap(ty, g) + 2 * g, INK)
            fill(snap(bx - (rr - 1) * g, g), snap(ty, g), snap(bx + (rr - 1) * g, g) + g, snap(ty, g) + g, 0xFFE2D4B4.toInt())
            disc(bx, ty, rr, g, darken(cap, 0.22f), 0.75f, topHalf = true)
            disc(bx - g * 0.5f, ty - g * 0.5f, rr - 1, g, cap, 0.7f, topHalf = true)
            disc(bx - g * rr * 0.35f, ty - g * rr * 0.4f, rr * 0.28f, g, lighten(cap, 0.4f), 0.8f)
            if (variant % 2 == 0) for (k in 0..2) fill(snap(bx + (k - 1) * rr * 0.5f * g, g), snap(ty - (1 + k % 2) * g * rr * 0.3f, g), snap(bx + (k - 1) * rr * 0.5f * g, g) + g, snap(ty - (1 + k % 2) * g * rr * 0.3f, g) + g, 0xFFF7EFDC.toInt())
        }
    }

    // ------------------------------------------------------------------ Bumble: beehive boxes

    fun Canvas.hives(cx: Float, cy: Float, g: Float, variant: Int, t: Float, active: Boolean) {
        val col = HIVES[Math.floorMod(variant, HIVES.size)]
        shadow(cx, cy, 10f, g)
        val pn = Pen(this, cx, cy, g)
        pn.o(-7f, -2f, 7f, 0f, 0xFF6E4A2E.toInt())
        val stories = 2 + variant % 2
        for (k in 0 until stories) {
            val y0 = -2f - k * 5
            pn.o(-6f, y0 - 5, 6f, y0, if (k % 2 == 0) col else darken(col, 0.06f))
            pn.r(-6f, y0 - 5, 6f, y0 - 4, lighten(col, 0.3f))
            pn.r(4f, y0 - 5, 6f, y0, darken(col, 0.18f))
            pn.r(-2f, y0 - 3, 2f, y0 - 2, darken(col, 0.5f))
        }
        val y1 = -2f - stories * 5
        pn.o(-7f, y1 - 2, 7f, y1, 0xFF8C8F93.toInt())
        pn.o(-7f, y1 - 5, 7f, y1 - 2, 0xFFB9BCC0.toInt())
        pn.r(-3f, -3f, 3f, -2f, INK)
        if (active) for (k in 0 until 6) {
            val a = t * (2f + k * 0.3f) + k
            val bx = cx + sin(a) * (7 + k) * g; val by = cy - (10 + sin(a * 1.7f) * 4 + k) * g
            fill(snap(bx, g), snap(by, g), snap(bx, g) + g, snap(by, g) + g, if ((t * 10 + k).toInt() % 2 == 0) INK else 0xFFF0C23B.toInt())
        }
    }

    // ------------------------------------------------------------------ Pickup: pickup truck (side view)

    fun Canvas.truck(cx: Float, cy: Float, g: Float, variant: Int, t: Float, active: Boolean) {
        val body = TRUCKS[Math.floorMod(variant, TRUCKS.size)]
        shadow(cx, cy, 16f, g)
        val shake = if (active && (t * 14).toInt() % 2 == 0) -1f else 0f
        val pn = Pen(this, cx, cy + shake * g, g)
        val light = lighten(body, 0.3f); val dark = darken(body, 0.3f)
        pn.o(1f, -10f, 16f, -4f, body); pn.r(1f, -10f, 16f, -9f, light)
        pn.r(2f, -12f, 15f, -10f, darken(body, 0.55f))                      // the open bed, seen from above
        pn.o(-6f, -18f, 2f, -4f, body); pn.r(-6f, -18f, 2f, -17f, light)    // cab
        pn.o(-5f, -16f, 1f, -11f, 0xFF9FD3DC.toInt()); pn.r(-4f, -15f, -3f, -12f, 0xFFF2FAFB.toInt())
        pn.o(-15f, -10f, -6f, -4f, body); pn.r(-15f, -10f, -6f, -9f, light) // hood
        pn.r(-15f, -6f, 16f, -4f, dark)
        pn.r(-15f, -9f, -13f, -7f, 0xFFFFF4C8.toInt())                     // headlight
        pn.r(-1f, -9f, 0f, -8f, dark)
        pn.o(-16f, -5f, -13f, -3f, 0xFFB9BCC0.toInt()); pn.o(15f, -5f, 17f, -3f, 0xFFB9BCC0.toInt())
        for (x in listOf(-9f, 9f)) { pn.d(x, -3f, 3.5f, INK); pn.d(x, -3f, 2.5f, 0xFF3A3A3A.toInt()); pn.d(x, -3f, 1.1f, 0xFFC9CCD0.toInt()) }
        if (active) for (k in 0 until 3) {
            val ph = (t * 1.2f + k * 0.33f) % 1f
            pn.d(18f + ph * 6, -4f - ph * 5, 1f + ph * 2f, Pal.alpha(0xFFDDD6CA.toInt(), 1 - ph))
        }
    }

    // ------------------------------------------------------------------ cabin

    fun Canvas.cabin(cx: Float, cy: Float, g: Float, t: Float, smoke: Boolean) {
        shadow(cx, cy, 16f, g)
        val pn = Pen(this, cx, cy, g)
        val logs = 0xFF8A5A36.toInt()
        pn.o(6f, -27f, 9f, -18f, 0xFF8C8F93.toInt()); pn.r(6f, -27f, 9f, -26f, 0xFFB9BCC0.toInt())
        pn.o(-13f, -11f, 13f, 0f, logs)
        for (y in listOf(-9f, -6f, -3f)) pn.r(-13f, y, 13f, y + 1, darken(logs, 0.35f))
        pn.r(11f, -11f, 13f, 0f, darken(logs, 0.2f))
        pn.house(0f, 26f, 0f, 11f, logs, 0xFF6E3F2A.toInt(), -11f)
        pn.o(-2f, -7f, 2f, 0f, 0xFF4A2E1A.toInt()); pn.r(1f, -4f, 2f, -3f, 0xFFF6C26B.toInt())
        pn.o(-10f, -8f, -5f, -4f, 0xFFF6C26B.toInt()); pn.r(-8f, -8f, -7f, -4f, 0xFF4A2E1A.toInt())
        pn.o(5f, -8f, 10f, -4f, 0xFFF6C26B.toInt()); pn.r(7f, -8f, 8f, -4f, 0xFF4A2E1A.toInt())
        if (smoke) for (k in 0 until 6) {
            val ph = (t * 0.25f + k / 6f) % 1f
            pn.d(7.5f + ph * 8 + sin(t + k) * 1.5f, -29f - ph * 18, 1.2f + ph * 2.5f, Pal.alpha(0xFFEDE6DA.toInt(), 0.8f * (1 - ph)))
        }
    }

    // ------------------------------------------------------------------ the village: the transport

    /** Hit spots (art px from the village anchor): x, y, radius. */
    val VILLAGE_PLAY = floatArrayOf(-26f, -8f, 16f)
    val VILLAGE_STOP = floatArrayOf(0f, -6f, 13f)
    val VILLAGE_REC = floatArrayOf(26f, -12f, 15f)

    /**
     * A walled hamlet in the middle of the valley. Its front row is the
     * transport, each with a big painted sign: the green-roofed house plays,
     * the stone wall with the red plaque stops, the water tower records.
     */
    fun Canvas.village(cx: Float, by: Float, g: Float, playing: Boolean, queued: Boolean, armed: Boolean, recording: Boolean, t: Float, label: Paint) {
        val pn = Pen(this, cx, by, g)
        val wood = 0xFF8A5A36.toInt(); val woodL = 0xFFB9875A.toInt()
        val blink = (t * 3).toInt() % 2 == 0
        // cobbled square
        pn.r(-45f, -47f, 45f, 0f, 0xFFB9A57E.toInt())
        var row = 0
        var y = -46f
        while (y < -1f) { var x = -44f + (row % 2) * 2; while (x < 44f) { pn.r(x, y, x + 1, y + 1, 0xFFA08C66.toInt()); x += 4 }; y += 2; row++ }
        // palisade on three sides
        pn.o(-46f, -51f, 46f, -47f, wood); var px = -46f; while (px <= 46f) { pn.r(px, -52f, px + 1, -51f, woodL); px += 3 }
        pn.o(-48f, -51f, -46f, 0f, wood); pn.o(46f, -51f, 48f, 0f, wood)
        // back row: homes, a well and trees
        pn.d(-40f, -44f, 4f, 0xFF35663A.toInt()); pn.d(40f, -44f, 4f, 0xFF35663A.toInt())
        pn.house(-28f, 14f, 7f, 7f, 0xFFF1E6CC.toInt(), 0xFFC2452E.toInt(), -30f)
        pn.o(-30f, -35f, -26f, -30f, 0xFF6E4A2E.toInt())
        pn.house(28f, 14f, 7f, 7f, 0xFFF1E6CC.toInt(), 0xFF3F6FB0.toInt(), -30f)
        pn.o(26f, -35f, 30f, -30f, 0xFF6E4A2E.toInt())
        pn.o(-5f, -36f, 5f, -31f, 0xFF9A958D.toInt()); pn.r(-4f, -35f, 4f, -34f, 0xFF3F7F86.toInt())
        pn.o(-6f, -43f, -5f, -36f, wood); pn.o(5f, -43f, 6f, -36f, wood); pn.o(-7f, -45f, 7f, -43f, 0xFFC2452E.toInt())

        // ▶ PLAY: a house with a green roof and a play arrow painted on it
        val px0 = VILLAGE_PLAY[0]
        val roof = if (playing) 0xFF4CC25A.toInt() else if (queued && blink) 0xFF8BE08F.toInt() else 0xFF2E8F45.toInt()
        pn.house(px0, 18f, 8f, 9f, 0xFFF1E6CC.toInt(), roof, -6f)
        pn.p(0xFFFFFFFF.toInt(), px0 - 3, -22f, px0 - 3, -15f, px0 + 4, -18.5f)
        pn.o(px0 - 2, -11f, px0 + 2, -6f, 0xFF6E4A2E.toInt())
        // ■ STOP: a stone wall with a red plaque
        pn.o(-10f, -14f, 10f, -2f, 0xFF9A958D.toInt())
        for (yy in listOf(-11f, -8f, -5f)) pn.r(-10f, yy, 10f, yy + 1, 0xFF6F6A63.toInt())
        for ((xx, yy) in listOf(-6f to -14f, 2f to -14f, -2f to -11f, 6f to -11f, -6f to -8f, 2f to -8f, -2f to -5f, 6f to -5f)) pn.r(xx, yy, xx + 1, yy + 3, 0xFF6F6A63.toInt())
        pn.r(-10f, -14f, 10f, -13f, 0xFFC9C4BC.toInt())
        pn.o(-4f, -12f, 4f, -4f, 0xFFC93A2E.toInt()); pn.r(-2f, -10f, 2f, -6f, 0xFFFFFFFF.toInt())
        // ● REC: the water tower with a red record light
        val rx = VILLAGE_REC[0]
        for (lx in listOf(rx - 6, rx + 5)) pn.o(lx, -16f, lx + 1, -2f, wood)
        pn.line(rx - 5, -14f, rx + 5, -4f, wood); pn.line(rx + 5, -14f, rx - 5, -4f, wood)
        pn.o(rx - 7f, -28f, rx + 7f, -16f, 0xFF9C6A44.toInt())
        for (yy in listOf(-25f, -21f, -18f)) pn.r(rx - 7f, yy, rx + 7f, yy + 1, 0xFF5A3A22.toInt())
        pn.po(0xFF6E3F2A.toInt(), rx - 9f, -28f, rx + 9f, -28f, rx, -35f)
        val lit = recording || (armed && blink)
        pn.d(rx, -22f, 3f, INK); pn.d(rx, -22f, 2.2f, if (lit) 0xFFFF3B30.toInt() else 0xFFA8382E.toInt())
        if (lit) pn.d(rx, -22f, 5f, Pal.alpha(0xFFFF3B30.toInt(), 0.25f))
        // low picket fence in front, with a gate in the middle
        for (seg in listOf(-46f to -14f, 14f to 46f)) { pn.r(seg.first, -2f, seg.second, -1f, woodL); var k = seg.first; while (k <= seg.second) { pn.o(k, -3f, k + 1, 0f, woodL); k += 4 } }
        // signs: big, painted, impossible to miss
        sign(pn, VILLAGE_PLAY[0], "▶ PLAY", if (playing) 0xFF4CC25A.toInt() else 0xFFF1E6CC.toInt(), label)
        sign(pn, VILLAGE_STOP[0], "■ STOP", 0xFFF1E6CC.toInt(), label)
        sign(pn, VILLAGE_REC[0], "● REC", if (lit) 0xFFFF6B5E.toInt() else 0xFFF1E6CC.toInt(), label)
    }

    private fun sign(pn: Pen, x: Float, text: String, board: Int, label: Paint) {
        pn.o(x - 0.5f, 6f, x + 0.5f, 10f, 0xFF5A3A22.toInt())
        pn.o(x - 10f, 1f, x + 10f, 7f, board)
        pn.r(x - 10f, 1f, x + 10f, 2f, lighten(board, 0.4f))
        label.color = 0xFF2A1C16.toInt(); label.textAlign = Paint.Align.CENTER
        pn.c.textCentered(text, pn.ox + x * pn.g, pn.oy + 4.2f * pn.g, label)
    }

    // ------------------------------------------------------------------ biplanes

    private val PLANE = listOf(
        "TT........................",
        "TTT.....WWWWWWWWWWWW......",
        "TTT.......s..cc.s.........",
        "TTTTBBBBBBBBBBBBBBBBBBB...",
        ".TBBBBBBBBBBBBBBBBBBBBBN..",
        "..bbbbbbbbbbbbbbbbbbbbbN..",
        "........wwwwwwwwwwww......",
        "...........s....s.........",
        "..........KK....KK........",
    )
    private val planeCache = HashMap<Int, Sprite>()
    fun plane(ch: Int, frame: Int): Sprite = planeCache.getOrPut(ch * 2 + frame) {
        val c = PLANES[ch]
        val rows = PLANE.mapIndexed { y, r ->
            val prop = if (frame == 0) y in 2..6 else y in 3..5
            if (prop) r.substring(0, 24) + "PP" else r
        }
        Sprite(rows, mapOf('B' to c, 'b' to darken(c, 0.3f), 'T' to darken(c, 0.2f), 'W' to lighten(c, 0.25f), 'w' to darken(c, 0.1f),
            's' to 0xFF7A4A2A.toInt(), 'c' to 0xFF2A1C16.toInt(), 'N' to 0xFFC9C3B8.toInt(), 'K' to 0xFF2A1C16.toInt(),
            'P' to if (frame == 0) 0xFF55504C.toInt() else 0xFFB9B4AC.toInt()), outline = INK)
    }

    // ------------------------------------------------------------------ the tombstone

    /** A 50%-opacity outlined tombstone reading R.I.P., grass on each side. `hot` = something is over it. */
    fun Canvas.tombstone(cx: Float, bottom: Float, height: Float, hot: Boolean, text: Paint) {
        val g = (height / 22f).coerceAtLeast(2f)
        val alpha = if (hot) 0.85f else 0.5f
        val w = 8f; val h = 15f
        val l = snap(cx - w * g, g); val r = snap(cx + w * g, g); val b = snap(bottom - 3 * g, g); val tt = snap(b - h * g, g)
        val stroke = Pal.alpha(0xFFF7EFDC.toInt(), alpha)
        val inside = Pal.alpha(if (hot) 0xFFB8521B.toInt() else 0xFF2A1C16.toInt(), alpha * 0.45f)
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
        textCentered("R.I.P.", (l + r) / 2, tt + 8 * g, text)
        // grass bits on each side
        val grass = Pal.alpha(0xFF6FA84A.toInt(), alpha + 0.1f)
        for (side in listOf(-1f, 1f)) for (k in 0 until 5) {
            val gx = (if (side < 0) l - 3 * g else r + g) + side * k * 1.5f * g
            val gh = (2 + (k * 7) % 4) * g
            fill(snap(gx, g), snap(b - gh + g, g), snap(gx, g) + g, b + g, grass)
        }
    }

    // ------------------------------------------------------------------ the little fox

    private val FOX_WALK = listOf(
        listOf("O.O......", "OOO.....W", "OOOOOOOOW", ".OOOOOOOO", ".D.D.D.D."),
        listOf("O.O......", "OOO.....W", "OOOOOOOOW", ".OOOOOOOO", "D...DD..D"),
    )
    private val FOX_SIT = listOf("O.O....", "OOO....", "WOO...W", ".OOO.OW", ".OOOOOO", ".OOOOO.", ".D.DD..")
    private val FOX_SCRATCH = listOf(
        listOf("O.O....", "OOOD...", "WOOD..W", ".OOO.OW", ".OOOOOO", ".OOOOO.", "...DD.."),
        listOf("O.O....", "OOO.D..", "WOO.D.W", ".OOO.OW", ".OOOOOO", ".OOOOO.", "...DD.."),
    )
    private val FOX_STARE = listOf("O...O", "OOOOO", "OkOkO", "WOWOW", ".WWW.", ".OOO.", "OOOOO", "OD.DO")
    private val foxColors = mapOf('O' to 0xFFE8742C.toInt(), 'W' to 0xFFF7EFDC.toInt(), 'D' to 0xFF3D2414.toInt(), 'k' to 0xFF2A1C16.toInt())
    val foxWalk by lazy { FOX_WALK.map { Sprite(it, foxColors, outline = INK) } }
    val foxSit by lazy { Sprite(FOX_SIT, foxColors, outline = INK) }
    val foxScratch by lazy { FOX_SCRATCH.map { Sprite(it, foxColors, outline = INK) } }
    val foxStare by lazy { Sprite(FOX_STARE, foxColors, outline = INK) }

    // ------------------------------------------------------------------ small icons for the block rail

    val iconHive by lazy { Sprite(listOf(".DDDDDDDD.", "DwwwwwwwwD", ".hhhhhhhh.", ".hhhhhhhh.", ".hDDDDDDh.", ".rrrrrrrr.", ".hhhhhhhh.", ".hhh..hhh.", ".BB....BB."), mapOf('h' to 0xFFE2B455.toInt(), 'r' to 0xFFB8822A.toInt())) }
    val iconTruck by lazy { Sprite(listOf("....OOOO....", "....OaaO....", "OOOOOaaOOOO.", "OOOOOOOOOOOw", "rrrrrrrrrrrr", ".DD.....DD..", ".DD.....DD.."), mapOf('a' to 0xFF9FD3DC.toInt())) }
    val iconCloud by lazy { Sprite(listOf("...WWW....", "..WWWWW.W.", ".WWWWWWWWW", "WWWWWWWWWW", "aaaaaaaaaa"), outline = Pal.AQUA_D) }
    val iconBoat by lazy { Sprite(listOf("....b.....", "....b.....", "BBBBBBBBBB", ".BrrrrrrB.", "..BBBBBB..", "aaaaaaaaaa"), mapOf('r' to 0xFF5A3A22.toInt())) }
    val iconTree by lazy { Sprite(listOf("..GGGG..", ".GgGGGG.", "GgGGGGGG", "GGGGGGqG", ".GGGGqG.", "...BB...", "...BB..."), mapOf('q' to 0xFF35663A.toInt())) }
    val iconMushroom by lazy { Sprite(listOf("..rrrr....", ".rOOOOr...", "rOOOOOOr..", "..WW..rr..", "..WW.rOOr.", "..WW..WW..", ".WWWW.WW.."), mapOf('r' to 0xFFB8521B.toInt())) }
    val iconFlower by lazy { Sprite(listOf("..o.o..", ".oOoOo.", "oOOWOOo", ".oOoOo.", "..oGo..", "...G...", "..GG...")) }
    val iconValley by lazy { Sprite(listOf("......aa.....", "....aaaaaa...", "..GGG....GG..", ".GGGGG..GGGG.", "GGgGGGGGGGgGG", "GGGGGGgGGGGGG"), outline = null) }
}
