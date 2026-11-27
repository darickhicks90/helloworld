package app.foxbox.ui

/**
 * The Fire Escape's backdrop: a 16-bit style pixel painting of a brick wall at
 * night, with an iron fire escape zig-zagging up it, lit windows, a drainpipe, an
 * air conditioner, string lights, plants and a cat. Painted once into a small
 * pixel buffer (no Android types, so it can be checked off the phone) and scaled
 * up with hard edges.
 */
object AlleyArt {
    /** The painting is made at this size, then reduced to [OUT_W] × [OUT_H] in a small palette. */
    const val W = 640
    const val H = 360
    const val OUT_W = 320
    const val OUT_H = 180

    /** String-light bulbs (x, y, colour) so the screen can make them twinkle. */
    val bulbs = ArrayList<IntArray>()
    /** Where the cat's eyes are (x, y), for an occasional blink. */
    var catEyes = intArrayOf(0, 0)

    private lateinit var px: IntArray
    private var seed = 0x1234567

    private fun rnd(): Int { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); return seed and 0x7FFFFFFF }
    private fun rf() = (rnd() % 10000) / 10000f
    private fun hash(x: Int, y: Int): Int { var h = x * 374761393 + y * 668265263; h = (h xor (h ushr 13)) * 1274126177; return (h xor (h ushr 16)) and 0x7FFFFFFF }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun r(c: Int) = (c shr 16) and 255
    private fun g(c: Int) = (c shr 8) and 255
    private fun b(c: Int) = c and 255
    private fun mix(a: Int, b0: Int, t: Float) = rgb((r(a) + (r(b0) - r(a)) * t).toInt(), (g(a) + (g(b0) - g(a)) * t).toInt(), (b(a) + (b(b0) - b(a)) * t).toInt())
    private fun shade(c: Int, k: Float) = rgb((r(c) * k).toInt(), (g(c) * k).toInt(), (b(c) * k).toInt())

    private fun set(x: Int, y: Int, c: Int) { if (x in 0 until W && y in 0 until H) px[y * W + x] = c }
    private fun get(x: Int, y: Int) = if (x in 0 until W && y in 0 until H) px[y * W + x] else 0
    private fun blend(x: Int, y: Int, c: Int, t: Float) { if (x in 0 until W && y in 0 until H) px[y * W + x] = mix(px[y * W + x], c, t) }
    private fun rect(x0: Int, y0: Int, w: Int, h: Int, c: Int) { for (y in y0 until y0 + h) for (x in x0 until x0 + w) set(x, y, c) }
    private fun hline(x0: Int, x1: Int, y: Int, c: Int) { for (x in minOf(x0, x1)..maxOf(x0, x1)) set(x, y, c) }
    private fun vline(x: Int, y0: Int, y1: Int, c: Int) { for (y in minOf(y0, y1)..maxOf(y0, y1)) set(x, y, c) }
    private fun line(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        var x = x0; var y = y0; val dx = kotlin.math.abs(x1 - x0); val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1; var err = dx + dy
        while (true) { set(x, y, c); if (x == x1 && y == y1) break; val e2 = 2 * err; if (e2 >= dy) { err += dy; x += sx }; if (e2 <= dx) { err += dx; y += sy } }
    }
    /** Darken a shape's shadow onto the wall: offset down-right, soft. */
    private fun shadowRect(x0: Int, y0: Int, w: Int, h: Int, k: Float) { for (y in y0 until y0 + h) for (x in x0 until x0 + w) { val c = get(x, y); set(x, y, shade(c, k)) } }

    // ---- palette (daylight)
    private val ink = rgb(22, 20, 28)
    private val iron = rgb(38, 36, 46)
    private val ironHi = rgb(104, 100, 118)
    private val ironMid = rgb(62, 58, 74)
    private val rust = rgb(132, 70, 44)
    private val sky = rgb(150, 196, 236)

    /** While set, drawing marks the sunlit wall's shadow (down and right) instead of drawing. */
    private var shadowing = false
    private lateinit var shaded: BooleanArray
    private const val SDX = 7
    private const val SDY = 9

    fun render(): IntArray {
        px = IntArray(W * H); shaded = BooleanArray(W * H)
        seed = 0x1234567; bulbs.clear(); catEyes = intArrayOf(-100, -100)
        bricks()
        sunlight()
        vignette()
        return retro()
    }

    /** The old-school look: half the resolution, every pixel snapped to a 17-colour palette with ordered dithering. */
    private val PALETTE = intArrayOf(
        // brick, dark to light, and two plums for the hard-fired ones
        rgb(70, 30, 34), rgb(96, 40, 40), rgb(120, 52, 46), rgb(142, 62, 50), rgb(162, 74, 56), rgb(180, 88, 62), rgb(198, 106, 74), rgb(216, 130, 92), rgb(234, 164, 122),
        rgb(84, 40, 48), rgb(104, 50, 56), rgb(126, 62, 64), rgb(150, 80, 70),
        // mortar, shadow to lit
        rgb(78, 72, 70), rgb(112, 104, 98), rgb(146, 138, 128), rgb(174, 166, 154), rgb(202, 194, 182), rgb(228, 222, 210),
    )
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    private fun retro(): IntArray {
        val out = IntArray(OUT_W * OUT_H)
        for (y in 0 until OUT_H) for (x in 0 until OUT_W) {
            // average the 2 × 2 block
            var rr = 0; var gg = 0; var bb = 0
            for (dy in 0..1) for (dx in 0..1) { val c = px[(y * 2 + dy) * W + x * 2 + dx]; rr += r(c); gg += g(c); bb += b(c) }
            rr /= 4; gg /= 4; bb /= 4
            // a little ordered dither before snapping to the palette
            val d = (BAYER[(y and 3) * 4 + (x and 3)] - 7.5f) * 0.9f
            val tr = rr + d; val tg = gg + d; val tb = bb + d
            var best = PALETTE[0]; var bd = Float.MAX_VALUE
            for (pc in PALETTE) {
                val er = r(pc) - tr; val eg = g(pc) - tg; val eb = b(pc) - tb
                val dist = er * er * 0.35f + eg * eg * 0.45f + eb * eb * 0.2f
                if (dist < bd) { bd = dist; best = pc }
            }
            out[y * OUT_W + x] = best
        }
        return out
    }

    /** Smooth value noise (0..1) at a given scale, for clay and mortar texture. */
    private fun vnoise(x: Float, y: Float, sc: Float, salt: Int): Float {
        val fx = x / sc; val fy = y / sc
        val ix = kotlin.math.floor(fx).toInt(); val iy = kotlin.math.floor(fy).toInt()
        val tx = fx - ix; val ty = fy - iy
        val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
        fun v(a: Int, b: Int) = (hash(a * 7 + salt, b * 13 - salt) % 1000) / 1000f
        val top = v(ix, iy) + (v(ix + 1, iy) - v(ix, iy)) * sx
        val bot = v(ix, iy + 1) + (v(ix + 1, iy + 1) - v(ix, iy + 1)) * sx
        return top + (bot - top) * sy
    }

    /**
     * Just brick, seen from about three feet away: five or six bricks across. Each has
     * its own clay and firing (darker at one end), a wavy, chipped arris, grain, sand
     * specks and little pits that catch the light; the mortar is recessed and gritty.
     */
    private fun bricks() {
        val bw = 180; val bh = 60; val joint = 11
        val clays = intArrayOf(rgb(170, 78, 56), rgb(184, 90, 62), rgb(156, 70, 52), rgb(194, 104, 74), rgb(150, 66, 52), rgb(176, 98, 76), rgb(164, 84, 64), rgb(142, 64, 50))
        val mortarBase = rgb(186, 176, 160)
        // the mortar everywhere first: sandy, with grains, small pebbles and a darker recess
        for (y in 0 until H) for (x in 0 until W) {
            val n = vnoise(x.toFloat(), y.toFloat(), 5f, 5) * 0.5f + vnoise(x.toFloat(), y.toFloat(), 1.6f, 9) * 0.5f
            var c = shade(mortarBase, 0.8f + n * 0.32f)
            val g = hash(x * 3 + 11, y * 5 + 7) % 100
            if (g < 12) c = shade(c, 0.8f) else if (g > 90) c = mix(c, rgb(238, 232, 218), 0.5f)
            // pebbles in the mix: two-pixel stones with a light top and a dark underside
            val pb = hash(x / 3 + 41, y / 3 + 17) % 90
            if (pb == 0) c = if (y % 3 == 0) mix(c, rgb(230, 224, 212), 0.6f) else if (y % 3 == 2) shade(c, 0.6f) else mix(c, rgb(150, 140, 130), 0.5f)
            if (g == 50) c = rgb(110, 100, 92)
            set(x, y, shade(c, 0.86f))   // recessed: always a touch darker than the brick faces
        }
        for (row in -1..H / bh + 1) {
            val y0 = row * bh
            val off = if (row and 1 == 0) 18 else 18 + bw / 2
            var col = -1
            while (col * bw - off < W + bw) {
                val x0 = col * bw - off
                val h = hash(col * 31 + 7, row * 17 + 3)
                var base = clays[h % clays.size]
                if (h % 13 == 0) base = rgb(116, 60, 52)
                if (h % 19 == 1) base = rgb(206, 136, 104)
                val flashLeft = (h shr 2) % 2 == 0                   // which end got the hotter fire
                val flash = 0.12f + ((h shr 5) % 10) / 80f
                val bwIn = bw - joint; val bhIn = bh - joint
                // the arris wobbles: each column's top and bottom edge, and each row's sides, by up to a pixel or two
                for (ly in 0 until bhIn) for (lx in 0 until bwIn) {
                    val x = x0 + lx; val y = y0 + ly
                    if (x !in 0 until W || y !in 0 until H) continue
                    // the arris wanders a little (smoothly), so edges aren't ruler-straight
                    val topEdge = (vnoise(lx.toFloat(), row * 7f, 14f, h and 127) * 2.2f).toInt()
                    val botEdge = bhIn - 1 - (vnoise(lx.toFloat(), row * 7f + 3, 14f, (h shr 3) and 127) * 2.2f).toInt()
                    val leftEdge = (vnoise(col * 5f, ly.toFloat(), 10f, (h shr 5) and 127) * 2.2f).toInt()
                    val rightEdge = bwIn - 1 - (vnoise(col * 5f + 2, ly.toFloat(), 10f, (h shr 7) and 127) * 2.2f).toInt()
                    // chips out of the corners and edges
                    val chipTL = h % 4 == 0 && lx + ly < 9
                    val chipBR = h % 5 == 2 && (bwIn - lx) + (bhIn - ly) < 11
                    val ce = (h shr 7) % (bwIn - 24) + 6
                    val chipEdge = h % 3 == 1 && ly < 3 + (5 - kotlin.math.abs(lx - ce - 6)).coerceAtLeast(0) / 2 && lx in ce..ce + 12
                    if (ly < topEdge || ly > botEdge || lx < leftEdge || lx > rightEdge || chipTL || chipBR || chipEdge) continue
                    // the clay: firing gradient, broad mottling and fine grain
                    val along = lx / bwIn.toFloat()
                    val fire = if (flashLeft) 1f - along else along
                    var c = shade(base, 1f - flash * fire * fire)
                    val m = vnoise(x.toFloat(), y.toFloat(), 18f, h and 255) - 0.5f
                    val m2 = vnoise(x.toFloat(), y.toFloat(), 6f, (h shr 4) and 255) - 0.5f
                    val f = vnoise(x.toFloat(), y.toFloat(), 2f, (h shr 8) and 255) - 0.5f
                    // grit: the sanded face catches light grain by grain
                    val gr = (hash(x * 17 + 3, y * 29 + 1) % 100 - 50) / 50f
                    c = shade(c, 1f + m * 0.18f + m2 * 0.1f + f * 0.12f + gr * 0.045f)
                    // the clay drifts in colour: plum where it fired hard, orange where it didn't
                    val hue = vnoise(x.toFloat(), y.toFloat(), 28f, (h shr 3) and 255)
                    c = if (hue > 0.6f) mix(c, rgb(120, 56, 62), (hue - 0.6f) * 0.7f) else if (hue < 0.3f) mix(c, rgb(214, 120, 72), (0.3f - hue) * 0.6f) else c
                    // soot that settled near the bed joints
                    if (ly > bhIn - 12) c = shade(c, 1f - (ly - (bhIn - 12)) * 0.009f * (vnoise(x.toFloat(), y.toFloat(), 6f, 33) + 0.5f))
                    val n = hash(x * 7 + 1, y * 13 + 5) % 1000
                    when {
                        n < 40 -> c = shade(c, 0.92f)
                        n < 48 -> c = mix(c, rgb(236, 182, 140), 0.22f)          // sand grains
                        n > 995 -> c = mix(c, rgb(250, 226, 196), 0.4f)
                    }
                    // shallow dents: a soft hollow, shadowed at the top and lit at the bottom
                    val dk = 1 + (h shr 4) % 4
                    for (dIdx in 0 until dk) {
                        val dcx = (hash(h, dIdx * 2) % (bwIn - 30)) + 15; val dcy = (hash(h, dIdx * 2 + 1) % (bhIn - 20)) + 10
                        val rr = 4 + hash(h, dIdx + 9) % 6
                        val ex = (lx - dcx) / (rr * 1.4f); val ey = (ly - dcy) / rr.toFloat()
                        val d = ex * ex + ey * ey
                        // a hollow: its upper wall is in shadow, its lower wall faces the light
                        if (d < 1f) { val k = (1f - d); c = if (ey < 0) shade(c, 1f - 0.28f * k * (-ey + 0.3f)) else mix(c, rgb(246, 200, 164), 0.3f * k * ey) }
                    }
                    // pores and pits: dark holes with a lit lower lip
                    val pit = hash(x / 2 + 101, y / 2 + 77) % 150 == 0
                    if (pit) c = shade(c, 0.5f)
                    if (!pit && hash(x / 2 + 101, (y - 1) / 2 + 77) % 150 == 0 && y % 2 == 0) c = mix(c, rgb(245, 200, 160), 0.35f)
                    if (hash(x + 303, y + 909) % 420 == 0) c = shade(c, 0.66f)
                    // flecks of grog: pale chips of fired clay in the body
                    if (hash(x / 2 + 55, y / 2 + 66) % 300 == 0) c = mix(c, rgb(226, 170, 130), 0.5f)
                    // a few dark iron spots
                    if (hash(x / 2 + 7, y / 2 + 991) % 500 == 0) c = mix(c, rgb(60, 34, 34), 0.55f)
                    // 3D: the top face catches the light, the bottom and right roll into shadow
                    val dTop = ly - topEdge; val dBot = botEdge - ly; val dLeft = lx - leftEdge; val dRight = rightEdge - lx
                    // a rounded arris: light spreads a few pixels down from the top, shadow up from the bottom
                    if (dTop < 4) c = mix(c, rgb(252, 214, 180), floatArrayOf(0.5f, 0.28f, 0.14f, 0.06f)[dTop])
                    if (dLeft < 3) c = mix(c, rgb(246, 196, 160), floatArrayOf(0.3f, 0.14f, 0.05f)[dLeft])
                    if (dBot < 5) c = shade(c, floatArrayOf(0.62f, 0.76f, 0.86f, 0.93f, 0.97f)[dBot])
                    if (dRight < 3) c = shade(c, floatArrayOf(0.72f, 0.86f, 0.95f)[dRight])
                    // a hairline crack on some
                    if (h % 5 == 3) { val cx = (h shr 9) % (bwIn - 30) + 15 + (kotlin.math.sin(ly * 0.45f + h) * 3f + kotlin.math.sin(ly * 1.3f) * 1f).toInt(); if (lx == cx && ly in 4 until bhIn - 4) c = shade(c, 0.55f); if (lx == cx + 1 && ly in 4 until bhIn - 4) c = mix(c, rgb(240, 196, 160), 0.25f) }
                    set(x, y, c)
                }
                // the brick's shadow falls into the joint below and to the right
                for (lx in 2 until bwIn + 2) for (k in 0 until 4) { val x = x0 + lx; val y = y0 + bhIn - 1 + k; if (x in 0 until W && y in 0 until H) set(x, y, shade(get(x, y), floatArrayOf(0.55f, 0.66f, 0.8f, 0.92f)[k])) }
                for (ly in 2 until bhIn) for (k in 0 until 3) { val x = x0 + bwIn - 1 + k; val y = y0 + ly; if (x in 0 until W && y in 0 until H) set(x, y, shade(get(x, y), floatArrayOf(0.62f, 0.78f, 0.9f)[k])) }
                col++
            }
        }
        // weathering: pale lime bloom in patches and a few faint rain streaks
        for (k in 0 until 6) {
            val cx = rnd() % W; val cy = rnd() % H; val r = 20 + rnd() % 40
            for (y in cy - r until cy + r) for (x in cx - r until cx + r) {
                val d = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (r * r).toFloat()
                if (d < 1f && hash(x, y) % 3 == 0) blend(x, y, rgb(232, 222, 204), 0.12f * (1f - d))
            }
        }
        for (k in 0 until 6) {
            val x = rnd() % W; val y0 = rnd() % 60; val len = 60 + rnd() % 140; val w = 3 + rnd() % 6
            for (y in y0 until minOf(H, y0 + len)) for (dx in 0 until w) if (hash(x + dx, y) % 4 != 0) blend(x + dx, y, rgb(70, 50, 46), 0.09f * (1f - (y - y0) / len.toFloat()))
        }
    }

    // ------------------------------------------------------------------ the wall, close up

    private fun wall() {
        val bw = 16; val bh = 6
        val bricks = intArrayOf(rgb(170, 78, 56), rgb(184, 90, 62), rgb(156, 70, 52), rgb(192, 104, 74), rgb(146, 66, 52), rgb(176, 96, 76), rgb(162, 84, 66))
        val mortarC = rgb(188, 176, 160)
        for (row in -1..H / bh + 1) {
            val y0 = row * bh
            val off = if (row and 1 == 0) 0 else bw / 2
            var col = -1
            while (col * bw - off < W + bw) {
                val x0 = col * bw - off
                val h = hash(col * 31 + 7, row * 17 + 3)
                var base = bricks[h % bricks.size]
                if (h % 19 == 0) base = rgb(118, 62, 52)            // a darker, overfired brick
                if (h % 29 == 1) base = rgb(204, 128, 96)           // a paler one
                val tilt = ((h shr 5) % 5 - 2) * 0.012f              // bricks aren't perfectly even
                for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) {
                    if (x !in 0 until W || y !in 0 until H) continue
                    val lx = x - x0; val ly = y - y0
                    val mortarRow = ly >= bh - 2; val mortarCol = lx >= bw - 2
                    var c: Int
                    if (mortarRow || mortarCol) {
                        // recessed mortar: shadowed under the brick above, sandy grain
                        val n = hash(x * 3, y * 5) % 100
                        c = if (ly == bh - 2 && !mortarCol) shade(mortarC, 0.62f) else if (lx == bw - 2 && !mortarRow) shade(mortarC, 0.72f) else mortarC
                        if (n < 18) c = shade(c, 0.88f) else if (n > 90) c = mix(c, rgb(230, 222, 206), 0.4f)
                    } else {
                        c = shade(base, 1f + tilt * (lx - bw / 2))
                        // the face: grain, little pits, a lit top edge and a shaded left/bottom
                        val n = hash(x * 7 + 1, y * 13 + 5) % 1000
                        if (n < 90) c = shade(c, 0.9f) else if (n < 120) c = shade(c, 0.78f) else if (n > 975) c = mix(c, rgb(240, 190, 150), 0.35f)
                        if (n in 400..408) c = shade(c, 0.55f)          // a pit
                        if (ly == 0) c = mix(c, rgb(246, 196, 160), 0.32f)
                        if (lx == 0) c = mix(c, rgb(236, 176, 140), 0.18f)
                        if (ly == bh - 3) c = shade(c, 0.86f)
                        if (lx == bw - 3) c = shade(c, 0.9f)
                        // a chipped corner now and then
                        if (h % 9 == 0 && lx <= 1 && ly <= 1) c = mortarC
                        if (h % 11 == 0 && lx >= bw - 4 && ly >= bh - 4) c = shade(mortarC, 0.8f)
                        // soft tonal blotches across the face
                        val blot = ((hash((x + 3) / 4, (y + 1) / 3) % 100) - 50) / 900f
                        c = shade(c, 1f + blot)
                    }
                    set(x, y, c)
                }
                col++
            }
        }
        // weathering: pale lime bloom and darker rain streaks
        for (k in 0 until 10) {
            val cx = rnd() % W; val cy = rnd() % H; val r = 10 + rnd() % 22
            for (y in cy - r until cy + r) for (x in cx - r until cx + r) {
                val d = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (r * r).toFloat()
                if (d < 1f && hash(x, y) % 3 == 0) blend(x, y, rgb(226, 214, 196), 0.18f * (1f - d))
            }
        }
        for (k in 0 until 14) {
            val x = rnd() % W; val y0 = rnd() % 90; val len = 30 + rnd() % 110; val w = 2 + rnd() % 4
            for (y in y0 until minOf(H, y0 + len)) for (dx in 0 until w) if (hash(x + dx, y) % 4 != 0) blend(x + dx, y, rgb(70, 50, 46), 0.14f * (1f - (y - y0) / len.toFloat()))
        }
    }

    /** Afternoon sun from the upper left: brighter there, a little cooler lower right. */
    private fun sunlight() {
        for (y in 0 until H) for (x in 0 until W) {
            val t = (x / W.toFloat()) * 0.6f + (y / H.toFloat()) * 0.4f
            var c = get(x, y)
            c = mix(c, rgb(255, 236, 200), 0.14f * (1f - t))
            c = mix(c, rgb(90, 96, 130), 0.10f * t)
            set(x, y, c)
        }
    }

    // ------------------------------------------------------------------ windows (big, in daylight)

    private fun windowAt(x0: Int, y0: Int, w: Int, h: Int, open: Boolean) {
        // stone lintel and sill with tooling marks
        stone(x0 - 6, y0 - 12, w + 12, 10)
        stone(x0 - 8, y0 + h, w + 16, 9)
        for (x in x0 - 8 until x0 + w + 8) blend(x, y0 + h + 9, ink, 0.45f)
        for (y in y0 + h + 10 until y0 + h + 14) for (x in x0 - 6 until x0 + w + 10) blend(x, y, ink, 0.25f)
        // deep reveal (the wall's thickness) in shadow on the left and top
        rect(x0 - 3, y0 - 2, w + 6, h + 4, rgb(70, 52, 50))
        for (y in y0 - 2 until y0 + h + 2) for (x in x0 - 3 until x0 + 2) set(x, y, rgb(96, 70, 62))
        // the glass: sky and clouds reflected, darker inside towards the bottom
        val gx = x0 + 3; val gy = y0 + 3; val gw = w - 6; val gh = h - 6
        for (y in gy until gy + gh) for (x in gx until gx + gw) {
            val t = (y - gy) / gh.toFloat()
            var c = mix(sky, rgb(70, 96, 130), t * 0.9f)
            // soft drifting clouds, mirrored in the glass
            val fx = x.toFloat(); val fy = y.toFloat()
            val cl = kotlin.math.sin(fx * 0.07f + fy * 0.03f) + kotlin.math.sin(fx * 0.031f - fy * 0.11f + 1.3f) * 0.8f + kotlin.math.sin(fx * 0.13f + fy * 0.07f + 2.1f) * 0.35f
            val k = ((cl - 0.7f) / 0.9f).coerceIn(0f, 1f) * (1f - t * 1.4f).coerceIn(0f, 1f)
            if (k > 0f) c = mix(c, rgb(248, 250, 253), k * 0.8f)
            if (k > 0.05f && k < 0.25f) c = mix(c, rgb(200, 214, 232), 0.25f)
            set(x, y, c)
        }
        // a bright glint across the glass
        for (k in 0 until 26) { set(gx + 8 + k, gy + 10 + k / 2, mix(get(gx + 8 + k, gy + 10 + k / 2), rgb(255, 255, 255), 0.65f)); set(gx + 9 + k, gy + 10 + k / 2, mix(get(gx + 9 + k, gy + 10 + k / 2), rgb(255, 255, 255), 0.4f)) }
        if (open) {
            // lower sash raised: the room shows, with a curtain lifting in the breeze and a plant
            val oy = gy + gh / 2 + 4
            for (y in oy until gy + gh) for (x in gx until gx + gw) set(x, y, mix(rgb(84, 64, 58), rgb(50, 38, 36), (y - oy) / (gy + gh - oy).toFloat()))
            for (y in oy until gy + gh) {
                val sway = (kotlin.math.sin((y - oy) / 5f) * 3).toInt()
                for (x in gx until gx + 18 + sway) set(x, y, mix(rgb(238, 232, 214), rgb(206, 196, 176), ((x - gx) % 4) / 4f))
            }
            val px0 = gx + gw / 2 + 4; val pw = minOf(14, gw / 2 - 10)
            rect(px0, gy + gh - 16, pw, 16, rgb(180, 96, 60)); hline(px0 - 1, px0 + pw, gy + gh - 16, rgb(210, 126, 86))
            val leaf = listOf(rgb(70, 140, 70), rgb(100, 170, 84), rgb(50, 110, 56))
            for (k in 0 until 50) { val lx = px0 - 4 + rnd() % (pw + 8); val ly = gy + gh - 18 - rnd() % 18; if (lx < gx + gw - 4 && ly > oy) { set(lx, ly, leaf[k % 3]); set(lx + 1, ly, leaf[(k + 1) % 3]) } }
        }
        // white-painted sash frames and glazing bars, with their own shading
        val paint = rgb(240, 236, 226); val paintS = rgb(196, 190, 178)
        val my = gy + gh / 2 + (if (open) 4 else 0)
        rect(gx - 1, my - 3, gw + 2, 6, paint); hline(gx - 1, gx + gw, my + 2, paintS)
        rect(gx - 1, gy - 1, gw + 2, 4, paint); rect(gx - 1, gy + gh - 3, gw + 2, 4, paint)
        rect(gx - 1, gy - 1, 4, gh + 2, paint); rect(gx + gw - 3, gy - 1, 4, gh + 2, paintS)
        rect(gx + gw / 2 - 1, gy, 3, my - gy, paint)
        if (!open) rect(gx + gw / 2 - 1, my, 3, gy + gh - my, paint)
        // peeling paint flecks
        for (k in 0 until 10) set(gx + rnd() % gw, gy + rnd() % gh, rgb(150, 140, 128))
        if (!open) {
            // half-lowered blinds in the lower pane
            for (y in my + 3 until my + 3 + (gy + gh - my) / 2) if ((y - my) % 3 != 0) hline(gx + 3, gx + gw - 4, y, rgb(226, 214, 190)) else hline(gx + 3, gx + gw - 4, y, rgb(178, 164, 140))
        }
    }

    private fun stone(x0: Int, y0: Int, w: Int, h: Int) {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
            var c = mix(rgb(214, 204, 186), rgb(170, 160, 146), (y - y0) / h.toFloat())
            val n = hash(x * 5, y * 9) % 100
            if (n < 12) c = shade(c, 0.9f) else if (n > 94) c = mix(c, rgb(240, 234, 222), 0.5f)
            if ((x - x0) % 5 == 0 && y < y0 + h - 2) c = shade(c, 0.95f)
            set(x, y, c)
        }
        hline(x0, x0 + w - 1, y0, rgb(244, 238, 226)); hline(x0, x0 + w - 1, y0 + h - 1, rgb(120, 110, 100))
    }

    private fun drainpipe(x: Int) {
        val cols = intArrayOf(rgb(56, 64, 70), rgb(120, 134, 140), rgb(170, 184, 188), rgb(110, 122, 128), rgb(72, 80, 88), rgb(44, 50, 56))
        for (y in 0 until H) {
            for (k in cols.indices) set(x + k, y, cols[k])
            if (hash(x, y) % 9 == 0) set(x + 2, y, rgb(140, 150, 150))
            if (y % 60 == 24) {
                rect(x - 2, y, 10, 5, rgb(48, 52, 60)); hline(x - 2, x + 7, y, rgb(140, 150, 156)); hline(x - 2, x + 7, y + 4, rgb(28, 30, 36))
                set(x - 1, y + 2, rgb(180, 186, 190)); set(x + 6, y + 2, rgb(180, 186, 190))
            }
            if (y % 23 == 5) set(x + 3, y, rust)
        }
        for (y in 0 until H) for (dx in 6 until 10) blend(x + dx, y, ink, 0.2f)
    }

    private fun acUnit(x: Int, y: Int) {
        val w = 52; val h = 26
        for (yy in y + 7 until y + 7 + h) for (xx in x + 6 until x + 6 + w) blend(xx, yy, ink, 0.38f)
        rect(x, y, w, h, rgb(214, 216, 218)); hline(x, x + w - 1, y, rgb(244, 246, 248)); hline(x, x + w - 1, y + h - 1, rgb(140, 142, 148)); vline(x + w - 1, y, y + h - 1, rgb(168, 170, 176))
        for (yy in y + 4 until y + h - 4 step 3) { hline(x + 4, x + 29, yy, rgb(140, 142, 150)); hline(x + 4, x + 29, yy + 1, rgb(236, 238, 240)) }
        for (k in 0 until 24) { val a = k * 0.2618f; for (r in 3..8 step 3) set(x + 41 + (kotlin.math.cos(a) * r).toInt(), y + 13 + (kotlin.math.sin(a) * r).toInt(), rgb(120, 122, 130)) }
        rect(x + 39, y + 11, 4, 4, rgb(90, 92, 100))
        line(x + 5, y + h, x + 16, y + h + 12, iron); line(x + w - 6, y + h, x + w - 17, y + h + 12, iron)
        for (yy in y + h until y + h + 60) if (hash(x, yy) % 3 != 0) blend(x + 26, yy, rgb(70, 50, 46), 0.25f * (1f - (yy - y - h) / 60f))
    }

    // ------------------------------------------------------------------ the fire escape, close up

    private fun ironSet(x: Int, y: Int, c: Int) {
        if (!shadowing) { set(x, y, c); return }
        val sx = x + SDX; val sy = y + SDY
        if (sx in 0 until W && sy in 0 until H && !shaded[sy * W + sx]) { shaded[sy * W + sx] = true; px[sy * W + sx] = shade(px[sy * W + sx], 0.58f) }
    }
    private fun irect(x0: Int, y0: Int, w: Int, h: Int, c: Int) { for (y in y0 until y0 + h) for (x in x0 until x0 + w) ironSet(x, y, c) }
    private fun ih(x0: Int, x1: Int, y: Int, c: Int) { for (x in minOf(x0, x1)..maxOf(x0, x1)) ironSet(x, y, c) }
    private fun iv(x: Int, y0: Int, y1: Int, c: Int) { for (y in minOf(y0, y1)..maxOf(y0, y1)) ironSet(x, y, c) }
    private fun iline(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        var x = x0; var y = y0; val dx = kotlin.math.abs(x1 - x0); val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1; var err = dx + dy
        while (true) { ironSet(x, y, c); if (x == x1 && y == y1) break; val e2 = 2 * err; if (e2 >= dy) { err += dy; x += sx }; if (e2 <= dx) { err += dx; y += sy } }
    }

    private fun fireEscape() {
        // the flight from the floor above, then the upper landing, the flight down, and the lower landing
        stair(176, 92, 262, -26)
        stair(40, 214, 168, 98)
        landing(-4, 256, 98, top = true)
        landing(-4, 256, 218, top = false)
    }

    /** Tone a window back into the wall: less contrast, a little of the brick's warmth. */
    private fun mute(x0: Int, y0: Int, w: Int, h: Int) {
        for (y in y0 - 13 until y0 + h + 14) for (x in x0 - 9 until x0 + w + 9) {
            val c = get(x, y)
            val l = (r(c) * 0.3f + g(c) * 0.55f + b(c) * 0.15f).toInt()
            set(x, y, mix(mix(c, rgb(l, l, l), 0.35f), rgb(128, 76, 62), 0.38f))
        }
    }

    private fun landing(left: Int, right: Int, y: Int, top: Boolean) {
        // landing: a thick grate with a front edge bar
        irect(left, y, right - left, 10, iron)
        ih(left, right - 1, y, ironHi); ih(left, right - 1, y + 1, ironMid)
        for (x in left + 1 until right - 1) if (x % 3 == 0) { iv(x, y + 2, y + 5, ink) }
        ih(left, right - 1, y + 9, ink)
        irect(left, y + 10, right - left, 4, ironMid); ih(left, right - 1, y + 13, ink)
        // brackets down into the wall
        for (bx in listOf(left + 22, left + (right - left) / 2, right - 34)) for (t in 0 until 3) iline(bx + t, y + 14, bx + 24 + t, y + 46, if (t == 1) ironHi else iron)
        // railing: thick top rail, mid rail and square balusters with a highlight on the sun side
        val ry = y - 40
        irect(left, ry, right - left, 5, iron); ih(left, right - 1, ry, ironHi); ih(left, right - 1, ry + 1, ironMid)
        irect(left, ry + 20, right - left, 3, iron); ih(left, right - 1, ry + 20, ironHi)
        var x = left
        while (x < right) { iv(x, ry + 5, y - 1, iron); iv(x + 1, ry + 5, y - 1, ironMid); iv(x + 2, ry + 5, y - 1, ink); if (!shadowing) { set(x + 1, ry + 6 + (x % 9), ironHi) }; x += 8 }
        irect(left, ry, 4, y - ry, iron); iv(left, ry, y, ironHi)
        irect(right - 4, ry, 4, y - ry, iron); iv(right - 4, ry, y, ironHi)
        if (!shadowing) {
            // rust blooms and flaking paint
            for (k in 0 until 40) { val rx = left + rnd() % (right - left); val rr = if (k % 2 == 0) ry + rnd() % 4 else y + rnd() % 8; set(rx, rr, rust); if (k % 3 == 0) set(rx + 1, rr, mix(rust, rgb(170, 100, 60), 0.5f)) }
            // just one plant
            if (top) pot(40, y - 1, 1)
        }
    }

    private fun stair(x0: Int, y0: Int, x1: Int, y1: Int) {
        // stringers (two deep channels) and chunky treads
        for (t in 0 until 4) iline(x0, y0 + t, x1, y1 + t, if (t == 0) ironHi else iron)
        for (t in 0 until 3) iline(x0 + 24, y0 - 12 + t, x1 + 24, y1 - 12 + t, if (t == 0) ironHi else iron)
        val steps = 10
        for (s in 1 until steps) {
            val f = s / steps.toFloat()
            val x = (x0 + (x1 - x0) * f).toInt(); val y = (y0 + (y1 - y0) * f).toInt()
            ih(x, x + 24, y - 5, ironHi); ih(x, x + 24, y - 4, iron); ih(x, x + 24, y - 3, iron); ih(x, x + 24, y - 2, ink)
        }
        // handrail with posts
        iline(x0 - 2, y0 - 36, x1 - 2, y1 - 36, ironHi); iline(x0 - 2, y0 - 35, x1 - 2, y1 - 35, iron); iline(x0 - 2, y0 - 34, x1 - 2, y1 - 34, iron)
        for (s in 0..5) { val f = s / 5f; val x = (x0 + (x1 - x0) * f).toInt(); val y = (y0 + (y1 - y0) * f).toInt(); iv(x - 2, y - 34, y, iron); iv(x - 1, y - 34, y, ironMid) }
    }

    private fun pot(x: Int, y: Int, kind: Int) {
        rect(x, y - 12, 16, 12, rgb(196, 104, 64)); hline(x - 1, x + 16, y - 12, rgb(226, 140, 96)); hline(x - 1, x + 16, y - 11, rgb(206, 120, 80))
        vline(x + 15, y - 10, y - 1, rgb(150, 76, 48)); hline(x, x + 15, y - 1, rgb(130, 64, 40))
        val leaf = listOf(rgb(70, 140, 70), rgb(110, 180, 90), rgb(46, 104, 54))
        when (kind) {
            1 -> for (k in 0 until 40) { val lx = x + 1 + rnd() % 15; val ly = y - 13 - rnd() % 16; set(lx, ly, leaf[k % 3]); set(lx, ly - 1, leaf[(k + 2) % 3]) }
            2 -> { for (k in 0 until 6) { vline(x + 3 + k * 2, y - 13 - (k % 3) * 5, y - 12, leaf[k % 3]) }; set(x + 4, y - 24, rgb(250, 210, 80)); set(x + 9, y - 22, rgb(250, 120, 140)); set(x + 13, y - 25, rgb(250, 210, 80)) }
            else -> for (k in 0 until 50) { val lx = x - 3 + rnd() % 22; val ly = y - 12 - rnd() % 20; set(lx, ly, leaf[k % 3]); if (k % 5 == 0) set(lx, ly, rgb(240, 90, 90)) }
        }
    }

    /** A cat asleep in the sun on the landing. */
    private fun cat(x: Int, y: Int) {
        val fur = rgb(214, 132, 60); val dark = rgb(160, 88, 40); val light = rgb(244, 186, 120)
        for (yy in y - 9 until y) for (xx in x until x + 22) {
            val dx = (xx - x - 11) / 11f; val dy = (yy - y + 4.5f) / 4.5f
            if (dx * dx + dy * dy < 1f) set(xx, yy, if ((xx + yy) % 5 == 0) dark else fur)
        }
        for (xx in x + 3 until x + 19) set(xx, y - 9, light)
        rect(x + 18, y - 10, 8, 7, fur); set(x + 19, y - 11, dark); set(x + 24, y - 11, dark); set(x + 20, y - 7, rgb(60, 40, 30)); set(x + 23, y - 7, rgb(60, 40, 30))
        for (k in 0 until 9) set(x - k, y - 2 + (k / 4), dark)
    }

    private fun vignette() {
        for (y in 0 until H) for (x in 0 until W) {
            val dx = (x - W / 2f) / (W / 2f); val dy = (y - H / 2f) / (H / 2f)
            val d = (dx * dx * 0.6f + dy * dy * 0.4f)
            if (d > 0.35f) set(x, y, shade(get(x, y), 1f - (d - 0.35f) * 0.45f))
        }
    }
}
