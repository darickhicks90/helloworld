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

    // ---- palette
    private val ink = rgb(14, 12, 20)
    private val iron = rgb(30, 28, 38)
    private val ironHi = rgb(74, 70, 88)
    private val ironMid = rgb(46, 43, 58)
    private val rust = rgb(112, 58, 40)
    private val mortar = rgb(52, 40, 44)
    private val warm = rgb(255, 196, 110)
    private val warmDeep = rgb(240, 140, 60)

    fun render(): IntArray {
        px = IntArray(W * H)
        seed = 0x1234567; bulbs.clear()
        wall()
        val groundY = 206
        ground(groundY)
        // windows: two columns, three floors (the fire escape serves the left column)
        val floors = intArrayOf(12, 82, 152)
        windowAt(70, floors[0], lit = true, kind = 0)
        windowAt(70, floors[1], lit = false, kind = 1)
        windowAt(70, floors[2], lit = true, kind = 2)
        windowAt(262, floors[0] + 4, lit = false, kind = 1)
        windowAt(262, floors[1] + 4, lit = true, kind = 3)
        door(300, groundY)
        drainpipe(222)
        acUnit(268, floors[1] + 4 + 52)
        meterBox(350, 150)
        lampLight(318, groundY - 52)
        fireEscape(intArrayOf(floors[0], floors[1]), groundY)
        vignette()
        return px
    }

    // ------------------------------------------------------------------ the wall

    private fun wall() {
        val bw = 10; val bh = 4
        val bricks = intArrayOf(rgb(122, 52, 42), rgb(136, 60, 46), rgb(110, 46, 40), rgb(146, 70, 52), rgb(98, 44, 40), rgb(128, 64, 54))
        for (row in 0..H / bh) {
            val y0 = row * bh
            val off = if (row % 2 == 0) 0 else bw / 2
            var col = -1
            while (col * bw - off < W) {
                val x0 = col * bw + off
                val h = hash(col, row)
                var base = bricks[h % bricks.size]
                if (h % 23 == 0) base = rgb(70, 40, 40)          // sooty brick
                if (h % 31 == 1) base = rgb(160, 92, 70)         // a newer, paler one
                for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) {
                    val lx = x - x0; val ly = y - y0
                    var c = if (lx == bw - 1 || ly == bh - 1) mortar else base
                    if (c != mortar) {
                        // top edge catches light, bottom edge sits in shadow; a little grain
                        if (ly == 0) c = mix(c, rgb(190, 120, 96), 0.25f)
                        if (ly == bh - 2) c = shade(c, 0.86f)
                        val n = hash(x * 7, y * 13) % 100
                        if (n < 7) c = shade(c, 0.82f) else if (n > 95) c = mix(c, rgb(196, 140, 120), 0.3f)
                        // chipped corners
                        if ((lx == 0 || lx == bw - 2) && ly == 0 && h % 7 == 0) c = mortar
                    }
                    set(x, y, c)
                }
                col++
            }
        }
        // night: cool at the top, with grime and water stains running down
        for (y in 0 until H) for (x in 0 until W) {
            var c = get(x, y)
            val top = (1f - y / H.toFloat())
            c = mix(c, rgb(28, 30, 70), 0.42f * top + 0.18f)
            set(x, y, c)
        }
        for (k in 0 until 18) {
            val x = rnd() % W; val y0 = rnd() % 120; val len = 20 + rnd() % 80; val w = 1 + rnd() % 3
            for (y in y0 until minOf(H, y0 + len)) for (dx in 0 until w) blend(x + dx, y, rgb(20, 16, 24), 0.18f * (1f - (y - y0) / len.toFloat()))
        }
    }

    private fun ground(gy: Int) {
        for (y in gy until H) for (x in 0 until W) {
            val n = hash(x, y * 3) % 100
            var c = mix(rgb(34, 32, 44), rgb(22, 20, 30), (y - gy) / (H - gy).toFloat())
            if (n < 6) c = rgb(48, 46, 60) else if (n > 96) c = rgb(16, 14, 22)
            set(x, y, c)
        }
        hline(0, W, gy, rgb(60, 56, 70)); hline(0, W, gy + 1, rgb(26, 24, 34))
        // a puddle catching the window light
        for (y in gy + 6 until gy + 15) for (x in 40 until 160) {
            val e = ((x - 100) / 58f).let { it * it } + ((y - gy - 10) / 4.5f).let { it * it }
            if (e < 1f) blend(x, y, rgb(30, 36, 76), 0.75f * (1f - e * 0.5f))
        }
        // reflections of the lit window above (broken by ripples)
        for (y in gy + 7 until gy + 13) for (x in 80 until 112) if ((y - gy) % 2 == 1 && hash(x, y) % 5 != 0) blend(x, y, warm, 0.45f)
        for (y in gy + 8 until gy + 12) for (x in 125 until 140) if ((y - gy) % 2 == 0 && hash(x, y) % 4 != 0) blend(x, y, rgb(160, 180, 230), 0.25f)
    }

    // ------------------------------------------------------------------ windows

    private fun windowAt(x0: Int, y0: Int, lit: Boolean, kind: Int) {
        val w = 40; val h = 46
        // stone lintel and sill
        rect(x0 - 3, y0 - 5, w + 6, 4, rgb(150, 140, 130)); hline(x0 - 3, x0 + w + 2, y0 - 5, rgb(196, 186, 172)); hline(x0 - 3, x0 + w + 2, y0 - 2, rgb(96, 88, 84))
        rect(x0 - 4, y0 + h, w + 8, 4, rgb(150, 140, 130)); hline(x0 - 4, x0 + w + 3, y0 + h, rgb(200, 190, 176)); hline(x0 - 4, x0 + w + 3, y0 + h + 3, rgb(70, 62, 62))
        // frame
        rect(x0 - 1, y0 - 1, w + 2, h + 2, ink)
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
            val t = (y - y0) / h.toFloat()
            set(x, y, if (lit) mix(warm, warmDeep, t * 0.9f) else mix(rgb(34, 42, 86), rgb(16, 18, 40), t))
        }
        if (lit) {
            when (kind) {
                0 -> { // curtains drawn back, a lamp and a plant
                    for (y in y0 until y0 + h) { for (x in x0 until x0 + 8 + (y - y0) / 8) set(x, y, mix(rgb(170, 60, 70), rgb(110, 30, 50), ((x - x0) % 3) / 3f)); for (x in x0 + w - 8 - (y - y0) / 8 until x0 + w) set(x, y, mix(rgb(170, 60, 70), rgb(110, 30, 50), ((x - x0) % 3) / 3f)) }
                    rect(x0 + 18, y0 + 28, 6, 12, rgb(80, 44, 36)); rect(x0 + 14, y0 + 20, 14, 9, rgb(255, 236, 180)); rect(x0 + 15, y0 + 21, 12, 7, rgb(255, 248, 214))
                    for (k in 0 until 9) { val lx = x0 + 28 + rnd() % 8; val ly = y0 + 26 + rnd() % 10; set(lx, ly, rgb(40, 90, 50)); set(lx + 1, ly, rgb(60, 120, 64)) }
                    rect(x0 + 29, y0 + 36, 6, 6, rgb(150, 80, 50))
                }
                2 -> { // half-drawn blind and a silhouette at a desk
                    for (y in y0 until y0 + 18) if (y % 3 != 2) hline(x0, x0 + w - 1, y, rgb(222, 196, 150)) else hline(x0, x0 + w - 1, y, rgb(180, 150, 110))
                    rect(x0 + 10, y0 + 26, 9, 12, rgb(60, 36, 40)); rect(x0 + 11, y0 + 21, 7, 6, rgb(60, 36, 40)); rect(x0 + 6, y0 + 36, 28, 3, rgb(50, 30, 30))
                    rect(x0 + 24, y0 + 30, 8, 6, rgb(120, 200, 230))   // a little screen glow
                }
                else -> { // a band's practice room: amp and a guitar on the wall
                    rect(x0 + 4, y0 + 28, 14, 16, rgb(40, 30, 30)); rect(x0 + 5, y0 + 30, 12, 9, rgb(90, 80, 70)); hline(x0 + 5, x0 + 16, y0 + 29, rgb(200, 180, 140))
                    for (k in 0..10) { set(x0 + 26 + k / 3, y0 + 10 + k, rgb(130, 60, 30)) }
                    rect(x0 + 25, y0 + 20, 7, 9, rgb(170, 90, 40)); rect(x0 + 26, y0 + 22, 5, 5, rgb(200, 120, 60))
                    rect(x0 + 30, y0 + 34, 7, 3, rgb(60, 40, 40))
                }
            }
            // window glow spilling onto the bricks
            for (y in y0 - 14 until y0 + h + 22) for (x in x0 - 22 until x0 + w + 22) {
                if (x in x0 - 1..x0 + w && y in y0 - 1..y0 + h) continue
                val dx = (x - (x0 + w / 2)) / (w * 0.95f); val dy = (y - (y0 + h / 2)) / (h * 0.9f)
                val d = dx * dx + dy * dy
                if (d < 1f) blend(x, y, warm, 0.22f * (1f - d))
            }
        } else {
            // a dark pane with the moon's reflection and closed blinds behind
            for (y in y0 + 2 until y0 + h) if (y % 4 == 0) hline(x0 + 1, x0 + w - 2, y, rgb(30, 34, 64))
            for (k in 0 until 6) line(x0 + 6 + k, y0 + 4, x0 + 2 + k, y0 + 14, rgb(120, 140, 190))
        }
        // mullions (sash window)
        rect(x0, y0 + h / 2 - 1, w, 3, rgb(232, 224, 210)); hline(x0, x0 + w - 1, y0 + h / 2 + 1, rgb(150, 140, 130))
        vline(x0 + w / 2, y0, y0 + h - 1, rgb(232, 224, 210))
        rect(x0, y0, w, 2, rgb(232, 224, 210)); rect(x0, y0 + h - 2, w, 2, rgb(210, 200, 186)); vline(x0, y0, y0 + h - 1, rgb(232, 224, 210)); vline(x0 + w - 1, y0, y0 + h - 1, rgb(200, 190, 176))
    }

    private fun door(x0: Int, gy: Int) {
        val w = 30; val h = 50; val y0 = gy - h
        rect(x0 - 3, y0 - 3, w + 6, h + 3, rgb(60, 56, 60))
        for (y in y0 until gy) for (x in x0 until x0 + w) set(x, y, mix(rgb(40, 70, 64), rgb(26, 44, 42), (x - x0) / w.toFloat()))
        for (y in y0 + 4 until gy - 4 step 9) { hline(x0 + 3, x0 + w - 4, y, rgb(24, 40, 38)); hline(x0 + 3, x0 + w - 4, y + 1, rgb(60, 96, 88)) }
        rect(x0 + w - 7, y0 + 26, 3, 4, rgb(200, 170, 90))
        // a hand-painted sign: "5IY" (the studio's back door)
        rect(x0 + 4, y0 + 8, 22, 9, rgb(230, 210, 160)); rect(x0 + 5, y0 + 9, 20, 7, rgb(250, 236, 196))
        val sx = x0 + 8; val sy = y0 + 10
        // 5
        hline(sx, sx + 3, sy, rgb(170, 40, 40)); vline(sx, sy, sy + 2, rgb(170, 40, 40)); hline(sx, sx + 3, sy + 2, rgb(170, 40, 40)); vline(sx + 3, sy + 2, sy + 4, rgb(170, 40, 40)); hline(sx, sx + 3, sy + 4, rgb(170, 40, 40))
        // I
        vline(sx + 6, sy, sy + 4, rgb(170, 40, 40))
        // Y
        set(sx + 9, sy, rgb(170, 40, 40)); set(sx + 13, sy, rgb(170, 40, 40)); set(sx + 10, sy + 1, rgb(170, 40, 40)); set(sx + 12, sy + 1, rgb(170, 40, 40)); vline(sx + 11, sy + 2, sy + 4, rgb(170, 40, 40))
    }

    private fun lampLight(x: Int, y: Int) {
        // a caged bulb over the door, and its cone of light
        rect(x - 4, y - 2, 9, 2, iron); rect(x - 2, y, 5, 4, rgb(255, 240, 190)); set(x, y + 1, rgb(255, 255, 240))
        vline(x - 3, y, y + 4, iron); vline(x + 3, y, y + 4, iron)
        for (yy in y - 30 until 206) for (xx in x - 70 until x + 70) {
            val dy = (yy - y).toFloat(); if (dy < -30) continue
            val spread = if (dy > 0) 8 + dy * 0.75f else 26f
            val dx = kotlin.math.abs(xx - x) / spread
            val d = if (dy < 0) (dx * dx + (dy / 30f) * (dy / 30f)) else dx
            if (d < 1f) blend(xx, yy, rgb(255, 214, 140), 0.16f * (1f - d) * (if (dy > 0) (1f - dy / 120f).coerceAtLeast(0f) else 1f))
        }
    }

    private fun drainpipe(x: Int) {
        for (y in 0 until 206) {
            set(x, y, rgb(40, 44, 56)); set(x + 1, y, rgb(84, 92, 110)); set(x + 2, y, rgb(60, 66, 82)); set(x + 3, y, rgb(30, 32, 44))
            if (y % 38 == 10) { rect(x - 1, y, 6, 3, rgb(26, 26, 34)); hline(x - 1, x + 4, y, rgb(90, 96, 110)) }
            if (y % 17 == 3) set(x + 2, y, rust)
        }
        // the shoe at the bottom
        rect(x - 2, 200, 8, 6, rgb(40, 44, 56)); hline(x - 2, x + 5, 200, rgb(84, 92, 110))
    }

    private fun acUnit(x: Int, y: Int) {
        shadowRect(x + 3, y + 3, 34, 18, 0.6f)
        rect(x, y, 34, 18, rgb(170, 172, 176)); hline(x, x + 33, y, rgb(214, 216, 220)); hline(x, x + 33, y + 17, rgb(110, 112, 118))
        for (yy in y + 3 until y + 15 step 2) hline(x + 3, x + 20, yy, rgb(110, 112, 120))
        for (k in 0 until 12) { val a = k * 0.523f; set(x + 27 + (kotlin.math.cos(a) * 4).toInt(), y + 9 + (kotlin.math.sin(a) * 4).toInt(), rgb(90, 92, 100)) }
        rect(x + 26, y + 8, 3, 3, rgb(70, 72, 80))
        // bracket and a drip stain
        line(x + 4, y + 18, x + 12, y + 26, iron); line(x + 30, y + 18, x + 22, y + 26, iron)
        for (yy in y + 18 until y + 60) blend(x + 17, yy, rgb(16, 16, 28), 0.3f * (1f - (yy - y - 18) / 42f))
    }

    private fun meterBox(x: Int, y: Int) {
        rect(x, y, 18, 24, rgb(90, 96, 92)); hline(x, x + 17, y, rgb(150, 156, 150)); vline(x + 17, y, y + 23, rgb(56, 60, 58))
        rect(x + 4, y + 5, 10, 8, rgb(30, 34, 40)); rect(x + 6, y + 7, 6, 3, rgb(140, 200, 150))
        vline(x + 8, y + 24, 205, rgb(56, 60, 58)); vline(x + 9, y + 24, 205, rgb(100, 106, 102))
        vline(x + 8, 0, y - 1, rgb(56, 60, 58)); vline(x + 9, 0, y - 1, rgb(100, 106, 102))
    }

    // ------------------------------------------------------------------ the fire escape

    private fun fireEscape(floors: IntArray, gy: Int) {
        val left = 40; val right = 140
        val landings = floors.map { it + 46 + 4 }      // a landing just under each window sill
        // shadows first, so the iron sits on top of them
        for (ly in landings) shadowRect(left + 5, ly + 5, right - left, 4, 0.55f)
        for (k in landings.indices) {
            val ly = landings[k]
            // the railing's shadow
            for (x in left + 4 until right + 4 step 3) for (y in ly - 14 + 4 until ly + 4) blend(x, y, ink, 0.25f)
        }
        // the flight from the floor above (off the top of the wall) down to the top landing
        stair(right - 14, -30, left + 14, landings[0] - 1)
        // stairs between landings (alternating direction), each with its stringers and treads
        for (k in 0 until landings.size - 1) {
            val top = landings[k]; val bot = landings[k + 1]
            val goRight = k % 2 == 1
            val x0 = if (goRight) left + 12 else right - 12
            val x1 = if (goRight) right - 14 else left + 14
            stair(x0, top + 3, x1, bot - 1)
        }
        for (k in landings.indices) landing(left, right, landings[k], top = k == 0)
        // the drop ladder from the lowest landing, stopping above the ground
        val ly = landings.last()
        val lx = right - 16
        val ladderEnd = gy - 26
        for (y in ly + 4 until ladderEnd) { set(lx, y, iron); set(lx + 1, y, ironHi); set(lx + 8, y, iron); set(lx + 9, y, ironHi) }
        for (y in ly + 8 until ladderEnd step 5) { hline(lx, lx + 9, y, iron); hline(lx + 1, lx + 8, y - 1, ironMid) }
        for (y in ly + 4 until ladderEnd) blend(lx + 12, y + 3, ink, 0.25f)
        // a cat on the lower landing, plants on both
        cat(left + 22, landings[1] - 1)
        plants(left + 54, landings[0] - 1)
        plants(left + 74, landings[1] - 1)
        // string lights draped along the top railing
        val y0 = landings[0] - 14
        var x = left + 2
        while (x < right - 2) {
            val sag = (kotlin.math.sin((x - left) / (right - left).toFloat() * 3.14159f * 3) * 3).toInt().let { kotlin.math.abs(it) }
            set(x, y0 + 2 + sag, rgb(20, 20, 24))
            if ((x - left) % 9 == 4) {
                val col = listOf(rgb(255, 210, 120), rgb(255, 120, 110), rgb(140, 220, 255), rgb(170, 255, 150))[((x - left) / 9) % 4]
                set(x, y0 + 3 + sag, col); set(x, y0 + 4 + sag, shade(col, 0.8f))
                for (dy in -3..4) for (dx in -3..3) { val d = (dx * dx + dy * dy) / 12f; if (d < 1f) blend(x + dx, y0 + 3 + sag + dy, col, 0.18f * (1f - d)) }
                bulbs += intArrayOf(x, y0 + 3 + sag, col)
            }
            x++
        }
    }

    private fun landing(left: Int, right: Int, y: Int, top: Boolean) {
        // the platform: a slatted grate seen from just above
        rect(left, y, right - left, 4, iron)
        hline(left, right - 1, y, ironHi)
        for (x in left + 1 until right - 1) if (x % 2 == 0) { set(x, y + 1, ironMid); set(x, y + 2, ink) }
        hline(left, right - 1, y + 3, ink)
        // brackets into the wall
        for (bx in listOf(left + 6, right - 8)) { line(bx, y + 4, bx + 6, y + 14, iron); line(bx + 1, y + 4, bx + 7, y + 14, ironMid) }
        // the railing: a top rail and balusters
        val ry = y - 14
        hline(left, right - 1, ry, ironHi); hline(left, right - 1, ry + 1, iron)
        hline(left, right - 1, ry + 7, iron)
        for (x in left until right step 3) { vline(x, ry + 1, y - 1, iron); if (x % 6 == 0) set(x, ry + 4, ironMid) }
        vline(left, ry, y, ironHi); vline(right - 1, ry, y, ironHi)
        // rust spots
        for (k in 0 until 6) set(left + rnd() % (right - left), y + rnd() % 3, rust)
    }

    private fun stair(x0: Int, y0: Int, x1: Int, y1: Int) {
        val dir = if (x1 > x0) 1 else -1
        // two stringers
        line(x0, y0, x1, y1, iron); line(x0, y0 + 1, x1, y1 + 1, ironHi)
        line(x0 + 4 * dir, y0 - 3, x1 + 4 * dir, y1 - 3, iron)
        // treads
        val steps = 9
        for (s in 1 until steps) {
            val t = s / steps.toFloat()
            val x = (x0 + (x1 - x0) * t).toInt(); val y = (y0 + (y1 - y0) * t).toInt()
            hline(x, x + 5 * dir, y - 1, ironMid); hline(x, x + 5 * dir, y, ink)
        }
        // a handrail
        line(x0, y0 - 12, x1, y1 - 12, ironHi)
        for (s in 0..4) { val t = s / 4f; val x = (x0 + (x1 - x0) * t).toInt(); val y = (y0 + (y1 - y0) * t).toInt(); vline(x, y - 12, y, iron) }
    }

    private fun cat(x: Int, y: Int) {
        val c = rgb(18, 16, 22)
        // sitting, seen from the side, tail curled over the edge
        rect(x, y - 7, 7, 7, c); rect(x + 1, y - 9, 5, 2, c)
        rect(x + 4, y - 13, 5, 5, c); set(x + 4, y - 14, c); set(x + 8, y - 14, c)
        set(x + 6, y - 11, rgb(190, 230, 110)); set(x + 8, y - 11, rgb(190, 230, 110))
        catEyes = intArrayOf(x + 6, y - 11)
        for (k in 0 until 6) set(x - 1 - k / 2, y - 1 + k, c)
        set(x - 3, y + 5, c); set(x - 2, y + 6, c)
    }

    private fun plants(x: Int, y: Int) {
        rect(x, y - 5, 8, 5, rgb(176, 90, 56)); hline(x - 1, x + 8, y - 5, rgb(200, 116, 76)); hline(x, x + 7, y - 1, rgb(120, 58, 40))
        val leaf = listOf(rgb(60, 120, 64), rgb(86, 150, 76), rgb(40, 90, 50))
        for (k in 0 until 16) { val lx = x + rnd() % 9 - 1; val ly = y - 6 - rnd() % 9; set(lx, ly, leaf[k % 3]); if (k % 3 == 0) set(lx + 1, ly - 1, leaf[(k + 1) % 3]) }
        set(x + 3, y - 13, rgb(240, 120, 150)); set(x + 6, y - 12, rgb(250, 220, 120))
    }

    private fun vignette() {
        for (y in 0 until H) for (x in 0 until W) {
            val dx = (x - W / 2f) / (W / 2f); val dy = (y - H / 2f) / (H / 2f)
            val d = (dx * dx * 0.6f + dy * dy * 0.4f)
            if (d > 0.35f) set(x, y, shade(get(x, y), 1f - (d - 0.35f) * 0.45f))
        }
    }
}
