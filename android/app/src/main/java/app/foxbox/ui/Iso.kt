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
    private val OFFS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

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
        val bob = if (active) Math.round(sin(t * 2.2f)) * g else 0f
        val y = cy + bob
        // ripples
        for (k in 0..1) {
            val rr = 10f + k * 3 + (if (active) (t * 2 % 3) else 0f)
            disc(cx, cy + g * 2, rr, g, Pal.alpha(0xFFBFE8E2.toInt(), 0.25f - k * 0.1f), 0.3f)
        }
        // hull seen three-quarter: a long pointed shape along the iso axis
        val a = at(cx, y, g, -6f, 0f, 0f); val b = at(cx, y, g, 6f, 0f, 0f)
        val bow = at(cx, y, g, 8f, 1.5f, 1f); val stern = at(cx, y, g, -7f, 1.5f, 1f)
        val tl = at(cx, y, g, -6f, 3f, 0f); val tr = at(cx, y, g, 6f, 3f, 0f)
        for ((ox, oy) in OFFS) poly(INK, a[0] + ox * g, a[1] + oy * g, b[0] + ox * g, b[1] + oy * g, bow[0] + ox * g, bow[1] + oy * g, tr[0] + ox * g, tr[1] + oy * g - 2 * g, tl[0] + ox * g, tl[1] + oy * g - 2 * g, stern[0] + ox * g, stern[1] + oy * g)
        poly(darken(hull, 0.3f), a[0], a[1], b[0], b[1], bow[0], bow[1], tr[0], tr[1] - 2 * g, tl[0], tl[1] - 2 * g, stern[0], stern[1])
        // side planks
        val s0 = at(cx, y, g, -6f, 0f, 1.5f); val s1 = at(cx, y, g, 6f, 0f, 1.5f)
        poly(hull, a[0], a[1], b[0], b[1], bow[0], bow[1], s1[0], s1[1], s0[0], s0[1], stern[0], stern[1])
        poly(lighten(hull, 0.3f), s0[0], s0[1], s1[0], s1[1], s1[0], s1[1] + g, s0[0], s0[1] + g)
        // inside and seats
        val i0 = at(cx, y, g, -5f, 1f, 1.5f); val i1 = at(cx, y, g, 5f, 1f, 1.5f); val i2 = at(cx, y, g, 5f, 2.5f, 1.5f); val i3 = at(cx, y, g, -5f, 2.5f, 1.5f)
        poly(0xFF5A3A22.toInt(), i0[0], i0[1], i1[0], i1[1], i2[0], i2[1], i3[0], i3[1])
        for (u in listOf(-2.5f, 2f)) { val p0 = at(cx, y, g, u, 1f, 1.6f); val p1 = at(cx, y, g, u + 1f, 2.5f, 1.6f); poly(0xFFC99A64.toInt(), p0[0], p0[1], p0[0] + 2 * g, p0[1] - g, p1[0], p1[1], p1[0] - 2 * g, p1[1] + g) }
        // oars
        val oarSwing = if (active) sin(t * 2.2f) * 2f else 0f
        for (side in listOf(-1f, 1f)) {
            val o0 = at(cx, y, g, 0f, 1.75f, 2f); val o1 = at(cx, y, g, 1f + oarSwing, 1.75f + side * 6f, 0f)
            var k = 0f
            while (k <= 1f) { val px = o0[0] + (o1[0] - o0[0]) * k; val py = o0[1] + (o1[1] - o0[1]) * k; fill(snap(px, g), snap(py, g), snap(px, g) + g, snap(py, g) + g, 0xFFD9B27C.toInt()); k += 0.08f }
        }
    }

    // ------------------------------------------------------------------ Leaves: tree

    fun Canvas.tree(cx: Float, cy: Float, g: Float, variant: Int, t: Float, active: Boolean) {
        val p = TREES[Math.floorMod(variant, TREES.size)]
        shadow(cx + 2 * g, cy, 9f, g)
        isoBox(cx - g, cy, g, 1.2f, 1.2f, 9f, box(0xFF7A4A2A.toInt()))
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
        val paintC = HIVES[Math.floorMod(variant, HIVES.size)]
        shadow(cx + 2 * g, cy, 10f, g)
        val x = cx - 2 * g
        isoBox(x, cy, g, 5f, 5f, 1.5f, box(0xFF6E4A2E.toInt()))           // stand
        val stories = 2 + variant % 2
        for (k in 0 until stories) {
            val z = 1.5f + k * 3.5f
            val p = at(x, cy, g, 0.3f, 0.3f, z)
            isoBox(p[0], p[1], g, 4.4f, 4.4f, 3.5f, box(if (k % 2 == 0) paintC else darken(paintC, 0.08f)))
            // hand-hold slot on each face
            val s = at(p[0], p[1], g, 1.6f, 0f, 2.2f)
            fill(snap(s[0], g), snap(s[1], g), snap(s[0], g) + 2 * g, snap(s[1], g) + g, darken(paintC, 0.45f))
        }
        val top = at(x, cy, g, 0f, 0f, 1.5f + stories * 3.5f)
        isoBox(top[0], top[1], g, 5f, 5f, 1.2f, box(0xFF8C8F93.toInt()))  // tin lid
        // entrance slot at the bottom
        rightQuad(at(x, cy, g, 0.3f, 0.3f, 1.5f)[0], at(x, cy, g, 0.3f, 0.3f, 1.5f)[1], g, 1f, 3.5f, 0.2f, 0.8f, 0xFF2A1C16.toInt())
        if (active) for (k in 0 until 6) {
            val a = t * (2f + k * 0.3f) + k
            val bx = cx + sin(a) * (6 + k) * g; val by = cy - (8 + sin(a * 1.7f) * 4 + k) * g
            fill(snap(bx, g), snap(by, g), snap(bx, g) + g, snap(by, g) + g, if ((t * 10 + k).toInt() % 2 == 0) 0xFF2A1C16.toInt() else 0xFFF0C23B.toInt())
        }
    }

    // ------------------------------------------------------------------ Pickup: pickup truck

    fun Canvas.truck(cx: Float, cy: Float, g: Float, variant: Int, t: Float, active: Boolean) {
        val body = TRUCKS[Math.floorMod(variant, TRUCKS.size)]
        val shake = if (active && (t * 14).toInt() % 2 == 0) -g else 0f
        val x = cx - 8 * g; val y = cy + 3 * g
        shadow(cx, cy + g, 13f, g)
        val o = at(x, y, g, 0f, 0f, 1.2f)
        val ox = o[0]; val oy = o[1] + shake
        val b = box(body)
        // bed (back), cab, hood (front): truck faces down-left
        val bed = at(ox, oy, g, 6f, 0f, 0f); isoBox(bed[0], bed[1], g, 7f, 5f, 3f, b)
        val inner = at(bed[0], bed[1], g, 0.6f, 0.6f, 3f); val in2 = at(bed[0], bed[1], g, 6.4f, 0.6f, 3f); val in3 = at(bed[0], bed[1], g, 6.4f, 4.4f, 3f); val in4 = at(bed[0], bed[1], g, 0.6f, 4.4f, 3f)
        poly(darken(body, 0.45f), inner[0], inner[1], in2[0], in2[1], in3[0], in3[1], in4[0], in4[1])
        val cab = at(ox, oy, g, 2.5f, 0f, 0f); isoBox(cab[0], cab[1], g, 3.5f, 5f, 6.5f, b)
        leftQuad(cab[0], cab[1], g, 0.6f, 4.4f, 3.6f, 6f, 0xFF9FD3DC.toInt())       // windshield
        rightQuad(cab[0], cab[1], g, 0.6f, 2.8f, 3.6f, 6f, 0xFF7FB5C0.toInt())      // side window
        isoBox(ox, oy, g, 2.5f, 5f, 3.5f, b)                                       // hood
        leftQuad(ox, oy, g, 0.5f, 1.6f, 1.6f, 2.6f, 0xFFFFF4C8.toInt())             // headlights
        leftQuad(ox, oy, g, 3.4f, 4.5f, 1.6f, 2.6f, 0xFFFFF4C8.toInt())
        leftQuad(ox, oy, g, 1.9f, 3.1f, 0.6f, 2.8f, 0xFF7C7F84.toInt())             // grille
        // wheels on the near side
        for (u in listOf(1.5f, 10.5f)) {
            val w = at(x, y, g, u, -0.2f, 1.3f)
            disc(w[0], w[1], 2.6f, g, INK, 1f); disc(w[0], w[1], 1.6f, g, 0xFF55504C.toInt(), 1f); disc(w[0], w[1], 0.7f, g, 0xFFB9B4AC.toInt(), 1f)
        }
        if (active) for (k in 0 until 3) {
            val ph = (t * 1.2f + k * 0.33f) % 1f
            val e = at(x, y, g, 13f, 1f, 1.5f)
            disc(e[0] + ph * 6 * g, e[1] - ph * 5 * g, 1f + ph * 2f, g, Pal.alpha(0xFFDDD6CA.toInt(), 1 - ph))
        }
    }

    // ------------------------------------------------------------------ cabin

    fun Canvas.cabin(cx: Float, cy: Float, g: Float, t: Float, smoke: Boolean) {
        val x = cx - 6 * g
        shadow(cx + 4 * g, cy - g, 17f, g)
        val logs = 0xFF8A5A36.toInt()
        isoBox(x, cy, g, 11f, 8f, 7f, box(logs))
        for (z in 1..6) { rightQuad(x, cy, g, 0f, 11f, z.toFloat(), z + 0.3f, darken(logs, 0.4f)); leftQuad(x, cy, g, 0f, 8f, z.toFloat(), z + 0.3f, darken(logs, 0.5f)) }
        rightQuad(x, cy, g, 6.5f, 8.5f, 0f, 5f, 0xFF4A2E1A.toInt())                    // door
        rightQuad(x, cy, g, 2f, 4.5f, 2.5f, 5f, 0xFFF6C26B.toInt())                    // lit window
        rightQuad(x, cy, g, 3.1f, 3.4f, 2.5f, 5f, 0xFF4A2E1A.toInt())
        leftQuad(x, cy, g, 2.5f, 5f, 2.5f, 5f, 0xFFE9A84A.toInt())
        val ch = at(x, cy, g, 8f, 5.5f, 7f)
        isoBox(ch[0], ch[1], g, 1.6f, 1.6f, 6f, box(0xFF8C8F93.toInt()))
        gableRoof(x, cy, g, 11f, 8f, 7f, 5f, 0xFF6E3F2A.toInt(), 0xFF5A3322.toInt(), darken(logs, 0.2f))
        val chTop = at(x, cy, g, 8.8f, 6.3f, 13.5f)
        isoBox(chTop[0], chTop[1] + g * 1.5f, g, 1.6f, 1.6f, 1.5f, box(0xFF8C8F93.toInt()))
        if (smoke) for (k in 0 until 6) {
            val ph = (t * 0.25f + k / 6f) % 1f
            disc(chTop[0] + (ph * 8 + sin(t + k) * 1.5f) * g, chTop[1] - (2 + ph * 18) * g, 1.2f + ph * 2.5f, g, Pal.alpha(0xFFEDE6DA.toInt(), 0.8f * (1 - ph)))
        }
    }

    // ------------------------------------------------------------------ the village: roof = play, wall = stop, water tower = record

    fun Canvas.villageRoof(cx: Float, cy: Float, g: Float, playing: Boolean, queued: Boolean, t: Float) {
        val x = cx - 4 * g
        shadow(cx + 2 * g, cy - g, 10f, g)
        isoBox(x, cy, g, 7f, 5f, 4f, box(0xFFE9DCC0.toInt()))
        leftQuad(x, cy, g, 1.5f, 3.2f, 0f, 3f, 0xFF6E4A2E.toInt())
        val lit = playing || (queued && (t * 3).toInt() % 2 == 0)
        val roof = if (lit) 0xFF3FB8AF.toInt() else 0xFF2F7F7A.toInt()
        gableRoof(x, cy, g, 7f, 5f, 4f, 4f, roof, darken(roof, 0.25f), 0xFFD8CBAE.toInt())
        // a play arrow painted on the roof
        val a = at(x, cy, g, 2.5f, 1.2f, 5.1f); val b = at(x, cy, g, 2.5f, 1.2f, 7.1f); val c = at(x, cy, g, 4.6f, 1.2f, 6.1f)
        poly(0xFFFFF6E5.toInt(), a[0], a[1], b[0], b[1], c[0], c[1])
    }

    fun Canvas.villageWall(cx: Float, cy: Float, g: Float, pressed: Boolean) {
        val x = cx - 6 * g
        shadow(cx + g, cy - g, 9f, g)
        val stone = if (pressed) 0xFFB9B4AC.toInt() else 0xFF9A958D.toInt()
        isoBox(x, cy, g, 7f, 1.6f, 4f, box(stone))
        for (z in listOf(1.3f, 2.6f)) rightQuad(x, cy, g, 0f, 7f, z, z + 0.25f, darken(stone, 0.35f))
        for ((u, z) in listOf(2f to 0f, 4.5f to 1.3f, 1.2f to 2.6f, 5.5f to 2.6f, 3.3f to 1.3f)) rightQuad(x, cy, g, u, u + 0.3f, z, z + 1.3f, darken(stone, 0.35f))
        // stop plaque
        rightQuad(x, cy, g, 2.6f, 4.4f, 1.0f, 3.2f, 0xFF7A4A2A.toInt())
        rightQuad(x, cy, g, 3.0f, 4.0f, 1.5f, 2.7f, 0xFFF7EFDC.toInt())
    }

    fun Canvas.waterTower(cx: Float, cy: Float, g: Float, armed: Boolean, recording: Boolean, t: Float) {
        shadow(cx, cy - g, 8f, g)
        val wood = 0xFF7A4A2A.toInt()
        for ((u, v) in listOf(0f to 0f, 4f to 0f, 0f to 4f, 4f to 4f)) {
            val p = at(cx - 4 * g, cy, g, u, v, 0f)
            fill(snap(p[0], g) - g, snap(p[1] - 9 * g, g), snap(p[0], g) + g, snap(p[1], g), INK)
            fill(snap(p[0], g), snap(p[1] - 9 * g, g), snap(p[0], g) + g, snap(p[1], g), wood)
        }
        val cross = at(cx - 4 * g, cy, g, 0f, 0f, 4f); val cross2 = at(cx - 4 * g, cy, g, 4f, 0f, 7f)
        var k = 0f
        while (k <= 1f) { fill(snap(cross[0] + (cross2[0] - cross[0]) * k, g), snap(cross[1] + (cross2[1] - cross[1]) * k, g), snap(cross[0] + (cross2[0] - cross[0]) * k, g) + g, snap(cross[1] + (cross2[1] - cross[1]) * k, g) + g, darken(wood, 0.2f)); k += 0.1f }
        // tank: a squat cylinder
        val tc = at(cx - 4 * g, cy, g, 2f, 2f, 9f)
        val tank = 0xFF9C6A44.toInt()
        for (z in 0..6) disc(tc[0], tc[1] - z * g, 5.5f, g, if (z == 0) INK else darken(tank, 0.15f), 0.5f)
        disc(tc[0], tc[1] - 7 * g, 5.5f, g, INK, 0.5f)
        disc(tc[0], tc[1] - 7 * g, 5f, g, lighten(tank, 0.2f), 0.5f)
        for (z in listOf(1, 3, 5)) fill(snap(tc[0] - 5 * g, g), snap(tc[1] - z * g, g), snap(tc[0] + 5 * g, g), snap(tc[1] - z * g, g) + g, darken(tank, 0.4f))
        // conical cap
        for (z in 0..3) disc(tc[0], tc[1] - (8 + z) * g, 5.5f - z * 1.4f, g, if (z == 0) 0xFF5A3322.toInt() else 0xFF6E3F2A.toInt(), 0.5f)
        // record light painted on the tank
        val blink = recording || (armed && (t * 4).toInt() % 2 == 0)
        disc(tc[0] + g, tc[1] - 3 * g, 2f, g, INK)
        disc(tc[0] + g, tc[1] - 3 * g, 1.4f, g, if (blink) 0xFFFF4A3A.toInt() else 0xFFA8382E.toInt())
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
