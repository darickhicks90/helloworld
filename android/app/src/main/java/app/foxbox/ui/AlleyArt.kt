package app.foxbox.ui

/**
 * The Fire Escape's backdrop: a 16-bit style pixel painting of a brick wall at
 * night, with an iron fire escape zig-zagging up it, lit windows, a drainpipe, an
 * air conditioner, string lights, plants and a cat. Painted once into a small
 * pixel buffer (no Android types, so it can be checked off the phone) and scaled
 * up with hard edges.
 */
object AlleyArt {
    const val W = 400
    const val H = 225

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
        wall()
        sunlight()
        windowAt(46, 14, 92, 132, open = true)
        windowAt(296, -40, 84, 120, open = false)
        acUnit(300, 100)
        drainpipe(254)
        // the fire escape, its shadow first
        shadowing = true; fireEscape(); shadowing = false
        fireEscape()
        vignette()
        return px
    }

    // ------------------------------------------------------------------ the wall, close up

    private fun wall() {
        val bw = 22; val bh = 8
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
            rect(gx + 44, gy + gh - 22, 18, 22, rgb(180, 96, 60)); hline(gx + 43, gx + 62, gy + gh - 22, rgb(210, 126, 86))
            val leaf = listOf(rgb(70, 140, 70), rgb(100, 170, 84), rgb(50, 110, 56))
            for (k in 0 until 70) { val lx = gx + 38 + rnd() % 30; val ly = gy + gh - 24 - rnd() % 26; set(lx, ly, leaf[k % 3]); set(lx + 1, ly, leaf[(k + 1) % 3]) }
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
        for (yy in y + 9 until y + 9 + 34) for (xx in x + 7 until x + 7 + 66) blend(xx, yy, ink, 0.38f)
        rect(x, y, 66, 34, rgb(214, 216, 218)); hline(x, x + 65, y, rgb(244, 246, 248)); hline(x, x + 65, y + 33, rgb(140, 142, 148)); vline(x + 65, y, y + 33, rgb(168, 170, 176))
        for (yy in y + 5 until y + 29 step 3) { hline(x + 5, x + 38, yy, rgb(140, 142, 150)); hline(x + 5, x + 38, yy + 1, rgb(236, 238, 240)) }
        for (k in 0 until 24) { val a = k * 0.2618f; for (r in 4..10 step 3) set(x + 52 + (kotlin.math.cos(a) * r).toInt(), y + 16 + (kotlin.math.sin(a) * r).toInt(), rgb(120, 122, 130)) }
        rect(x + 50, y + 14, 5, 5, rgb(90, 92, 100))
        line(x + 6, y + 34, x + 20, y + 50, iron); line(x + 60, y + 34, x + 46, y + 50, iron)
        for (yy in y + 34 until y + 110) if (hash(x, yy) % 3 != 0) blend(x + 33, yy, rgb(70, 50, 46), 0.25f * (1f - (yy - y - 34) / 76f))
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
        val left = 6; val right = 236; val y = 178     // the landing's walking surface
        // stairs climbing from the landing up and off the top, to the right
        stair(150, y - 2, 236, -12)
        // landing: a thick grate with a front edge bar
        irect(left, y, right - left, 8, iron)
        ih(left, right - 1, y, ironHi); ih(left, right - 1, y + 1, ironMid)
        for (x in left + 1 until right - 1) if (x % 3 == 0) { iv(x, y + 2, y + 5, ink) }
        ih(left, right - 1, y + 7, ink)
        irect(left, y + 8, right - left, 3, ironMid); ih(left, right - 1, y + 10, ink)
        // brackets down into the wall
        for (bx in listOf(left + 18, right - 30)) for (t in 0 until 3) iline(bx + t, y + 11, bx + 22 + t, y + 44, if (t == 1) ironHi else iron)
        // railing: thick top rail, mid rail and square balusters with a highlight on the sun side
        val ry = y - 38
        irect(left, ry, right - left, 4, iron); ih(left, right - 1, ry, ironHi)
        irect(left, ry + 18, right - left, 2, iron); ih(left, right - 1, ry + 18, ironHi)
        var x = left
        while (x < right) { iv(x, ry + 4, y - 1, iron); iv(x + 1, ry + 4, y - 1, ironMid); if (!shadowing) { set(x, ry + 4 + (x % 7), ironHi) }; x += 7 }
        irect(left, ry, 4, y - ry, iron); iv(left, ry, y, ironHi)
        irect(right - 4, ry, 4, y - ry, iron); iv(right - 4, ry, y, ironHi)
        if (!shadowing) {
            // rust blooms and flaking paint
            for (k in 0 until 40) { val rx = left + rnd() % (right - left); val rr = if (k % 2 == 0) ry + rnd() % 4 else y + rnd() % 8; set(rx, rr, rust); if (k % 3 == 0) set(rx + 1, rr, mix(rust, rgb(170, 100, 60), 0.5f)) }
            // things on the landing in the sun
            pot(30, y - 1, 1); pot(52, y - 1, 2); pot(196, y - 1, 3)
            cat(92, y - 1)
            // a watering can
            rect(120, y - 12, 14, 12, rgb(90, 150, 130)); hline(120, 133, y - 12, rgb(140, 200, 180)); line(134, y - 9, 142, y - 15, rgb(90, 150, 130)); line(120, y - 11, 116, y - 15, rgb(70, 120, 104))
        }
    }

    private fun stair(x0: Int, y0: Int, x1: Int, y1: Int) {
        // stringers (two deep channels) and chunky treads
        for (t in 0 until 3) iline(x0, y0 + t, x1, y1 + t, if (t == 0) ironHi else iron)
        for (t in 0 until 3) iline(x0 + 22, y0 - 10 + t, x1 + 22, y1 - 10 + t, if (t == 0) ironHi else iron)
        val steps = 11
        for (s in 1 until steps) {
            val f = s / steps.toFloat()
            val x = (x0 + (x1 - x0) * f).toInt(); val y = (y0 + (y1 - y0) * f).toInt()
            ih(x, x + 22, y - 4, ironHi); ih(x, x + 22, y - 3, iron); ih(x, x + 22, y - 2, ink)
        }
        // handrail with posts
        iline(x0 - 2, y0 - 34, x1 - 2, y1 - 34, ironHi); iline(x0 - 2, y0 - 33, x1 - 2, y1 - 33, iron)
        for (s in 0..4) { val f = s / 4f; val x = (x0 + (x1 - x0) * f).toInt(); val y = (y0 + (y1 - y0) * f).toInt(); iv(x - 2, y - 33, y, iron); iv(x - 1, y - 33, y, ironMid) }
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
