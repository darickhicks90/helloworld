package app.foxbox.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The great hall's scenery in finer detail, baked once per room into a bitmap (and re-baked when
 * the room changes, e.g. the wall cracks): bevelled marble tiles with veins, chips and cracks;
 * brick walls with a moulded cornice; soft shadows along the walls and under everything standing;
 * stone-rimmed pools; carpets, rugs, banners, paintings, bookcases, ivy, alcoves with skulls,
 * cobwebs, coins and gems. Then, every frame, the living bits: torch flames and their glow, dust
 * drifting in the window light, ripples and glints on the water; and a soft vignette round it all.
 */
internal class HallRoomArt {
    class Spec(
        val id: Int, val kind: Int, val doors: BooleanArray, val throne: Boolean,
        val props: List<Pair<Int, RectF>>, val pools: List<RectF>, val crack: Int, val dais: RectF,
        val rivers: List<RectF> = emptyList(), val bridges: List<RectF> = emptyList(),
        /** A stretch of the open wood: no walls of its own (its hedgerows are props). */
        val open: Boolean = false,
    )

    private companion object {
        const val W = 240; const val H = 176
        const val WHITE = 0xFFF8F8F8.toInt(); const val PALE = 0xFFE0E0E8.toInt(); const val MID = 0xFFB8B8C8.toInt(); const val DARK = 0xFF707088.toInt(); const val INK = 0xFF181820.toInt()
        const val WATER = 0xFFC4DCF0.toInt(); const val WATER_D = 0xFF8CA8C8.toInt()
        const val LEAF = 0xFFBCD8AC.toInt(); const val LEAF_D = 0xFF7C9C74.toInt()
        const val GOLD = 0xFFF0CC50.toInt(); const val GOLD_D = 0xFFA88420.toInt()
        const val RUNE = 0xFF6048B0.toInt()
        // prop kinds (as in HallScreen)
        const val PILLAR = 0; const val ROCK = 1; const val SHRUB = 2; const val STATUE = 3; const val FOUNTAIN = 4
        const val TABLE = 5; const val BRAZIER = 6; const val TOMB = 7; const val CHEST = 8
        const val TREE = 9; const val THICKET = 14; const val PALISADE = 22
        /** A screen of the Wildwood. */
        const val FOREST = 10
    }

    private val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private var c: Canvas = Canvas()
    private var baked: Bitmap? = null
    private var bakedKey = -1
    private var spec: Spec? = null

    private fun box(l: Float, t: Float, r: Float, b: Float, col: Int) { p.color = col; c.drawRect(l, t, r, b, p) }
    private fun box(l: Int, t: Int, r: Int, b: Int, col: Int) = box(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), col)
    private fun px(x: Int, y: Int, col: Int) = box(x, y, x + 1, y + 1, col)
    /** A small deterministic hash, so the same tile always gets the same chip. */
    private fun h(a: Int, b: Int, k: Int): Int { var x = a * 73856093 xor b * 19349663 xor k * 83492791; x = x xor (x ushr 13); x *= 0x5bd1e995; x = x xor (x ushr 15); return x and 0x7FFFFFFF }

    /** The room's still scenery, baked for this state. */
    fun background(key: Int, make: () -> Spec): Bitmap {
        val b = baked ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { baked = it }
        if (key != bakedKey) { bakedKey = key; val s = make(); spec = s; c = Canvas(b); b.eraseColor(0); bake(s) }
        return b
    }

    // =====================================================================================
    private fun bake(s: Spec) {
        if (s.kind == FOREST) { forest(s); return }
        val crypt = s.kind == 5
        floor(s, crypt)
        when (s.kind) { 0 -> carpet(104, 86, 136, H, 0xFF9A2434.toInt(), 0xFF6E1626.toInt()); 4 -> carpet(104, 31, 136, H - 3, 0xFF2E4A8C.toInt(), 0xFF1E3366.toInt()); 3 -> rug(104, 128, 158, 166) }
        if (s.kind == 6) treasureFloor(s)
        if (crypt) bones(s)
        wall(s, crypt)
        sides(s, crypt)
        ambient(s)
        for (w in s.pools) pool(w, s.kind)
        for ((k, f) in s.props) shadow(k, f)
        decor(s, crypt)
    }

    private fun floor(s: Spec, crypt: Boolean) {
        val a = if (crypt) 0xFFDCDCE4.toInt() else 0xFFF6F6FA.toInt()
        val b = if (crypt) 0xFFD2D2DC.toInt() else 0xFFEEEEF4.toInt()
        val hi = if (crypt) 0xFFE8E8EE.toInt() else 0xFFFFFFFF.toInt()
        val lo = if (crypt) 0xFFC6C6D2.toInt() else 0xFFE2E2EA.toInt()
        val grout = if (crypt) MID else 0xFFD4D4DE.toInt()
        val vein = if (crypt) 0xFFBCBCCA.toInt() else 0xFFDADAE6.toInt()
        box(0, 31, W, H, a)
        for (ty in 0 until (H - 31) / 16 + 1) for (tx in 0 until W / 16) {
            val x0 = tx * 16; val y0 = 31 + ty * 16; val v = h(tx, ty, s.id)
            box(x0, y0, x0 + 16, y0 + 16, if (v % 5 == 0) b else a)
            box(x0 + 1, y0 + 1, x0 + 16, y0 + 2, hi); box(x0 + 1, y0 + 1, x0 + 2, y0 + 16, hi)
            box(x0 + 1, y0 + 15, x0 + 16, y0 + 16, lo); box(x0 + 15, y0 + 1, x0 + 16, y0 + 16, lo)
            box(x0, y0, x0 + 16, y0 + 1, grout); box(x0, y0, x0 + 1, y0 + 16, grout)
            px(x0, y0, if (crypt) DARK else MID)
            // marble veins wandering across some tiles
            if ((v / 7) % 3 == 0) {
                var x = x0 + 3 + v % 9; var y = y0 + 3 + (v / 11) % 9
                for (k in 0 until 9) { px(x, y, vein); x += if ((v shr k) and 1 == 0) 1 else 0; y += if ((v shr (k + 3)) and 1 == 0) 1 else -1; x = x.coerceIn(x0 + 2, x0 + 14); y = y.coerceIn(y0 + 2, y0 + 14); x++ ; if (x > x0 + 14) break }
            }
            // hairline cracks and chipped corners
            if ((v / 13) % (if (crypt) 8 else 21) == 0) {
                var x = x0 + 4 + v % 6; var y = y0 + 4 + (v / 5) % 6
                for (k in 0 until 6) { px(x, y, if (crypt) 0xFFA4A4B4.toInt() else MID); if (k % 2 == 0) x++ else y++ }
            }
            if ((v / 17) % 11 == 0) { px(x0 + 1, y0 + 1, grout); px(x0 + 2, y0 + 1, grout); px(x0 + 1, y0 + 2, grout) }
            // moss creeping along the crypt's grout
            if (crypt && v % 4 == 0) { val my = y0 + 2 + v % 12; px(x0, my, LEAF_D); px(x0 + 1, my + 1, LEAF_D); px(x0, my + 2, LEAF) }
        }
    }

    /** A runner carpet with a gold border and a diamond pattern. */
    private fun carpet(l: Int, t: Int, r: Int, b: Int, col: Int, dark: Int) {
        box(l - 1, t, r + 1, b, 0x30000000)
        box(l, t, r, b, GOLD_D); box(l + 1, t, r - 1, b, GOLD); box(l + 3, t, r - 3, b, dark); box(l + 4, t, r - 4, b, col)
        var y = t + 6
        while (y < b - 4) {
            val cx = (l + r) / 2
            for (k in 0..4) { box(cx - k, y - 4 + k, cx + k + 1, y - 3 + k, dark); box(cx - k, y + 4 - k, cx + k + 1, y + 5 - k, dark) }
            px(cx, y, GOLD); px(cx - 1, y, GOLD_D); px(cx + 1, y, GOLD_D)
            for (x in l + 6 until r - 5 step 4) px(x, y + 9, GOLD_D)
            y += 18
        }
    }

    private fun rug(l: Int, t: Int, r: Int, b: Int) {
        box(l - 1, t + 1, r + 1, b + 2, 0x28000000)
        box(l, t, r, b, 0xFF5A2A2A.toInt()); box(l + 2, t + 2, r - 2, b - 2, 0xFF8A3A34.toInt()); box(l + 4, t + 4, r - 4, b - 4, 0xFF5A2A2A.toInt()); box(l + 5, t + 5, r - 5, b - 5, 0xFF9A4A3A.toInt())
        val cx = (l + r) / 2; val cy = (t + b) / 2
        for (k in 0..8) { box(cx - k * 2, cy - (8 - k), cx + k * 2 + 1, cy - (8 - k) + 1, 0xFFC89A50.toInt()); box(cx - k * 2, cy + (8 - k), cx + k * 2 + 1, cy + (8 - k) + 1, 0xFFC89A50.toInt()) }
        box(cx - 3, cy - 1, cx + 4, cy + 2, 0xFFE8C878.toInt())
        for (x in l until r step 2) { px(x, t - 1, 0xFFC8B890.toInt()); px(x, b, 0xFFC8B890.toInt()) }
    }

    private fun treasureFloor(s: Spec) {
        for (i in 0 until 46) {
            val v = h(i, 7, s.id); val x = 8 + v % (W - 16); val y = 36 + (v / W) % (H - 44)
            if (s.pools.any { it.contains(x.toFloat(), y.toFloat()) }) continue
            when (v % 9) {
                0 -> { px(x, y, 0xFFE04848.toInt()); px(x + 1, y, 0xFFF08888.toInt()) }
                1 -> { px(x, y, 0xFF48A0E0.toInt()); px(x + 1, y, 0xFF98D0F8.toInt()) }
                2 -> { px(x, y, 0xFF48C070.toInt()) }
                else -> { box(x, y, x + 3, y + 2, GOLD_D); box(x, y, x + 2, y + 1, GOLD) }
            }
        }
    }

    private fun bones(s: Spec) {
        for (i in 0 until 7) {
            val v = h(i, 3, s.id); val x = 12 + v % (W - 30); val y = 40 + (v / W) % (H - 52)
            if (s.pools.any { it.contains(x.toFloat(), y.toFloat()) } || s.props.any { it.second.contains(x.toFloat(), y.toFloat()) }) continue
            if (i % 3 == 0) {
                // a little skull
                box(x, y, x + 5, y + 4, 0xFFF0EEE4.toInt()); box(x + 1, y + 4, x + 4, y + 5, 0xFFD8D4C8.toInt())
                px(x + 1, y + 1, INK); px(x + 3, y + 1, INK); px(x + 2, y + 3, DARK)
            } else {
                box(x + 1, y, x + 6, y + 1, 0xFFF0EEE4.toInt()); px(x, y - 1, 0xFFF0EEE4.toInt()); px(x, y + 1, 0xFFF0EEE4.toInt()); px(x + 6, y - 1, 0xFFF0EEE4.toInt()); px(x + 6, y + 1, 0xFFF0EEE4.toInt())
                box(x + 1, y + 1, x + 6, y + 2, 0xFFC8C4B8.toInt())
            }
        }
    }

    private fun wall(s: Spec, crypt: Boolean) {
        val tones = if (crypt) intArrayOf(PALE, 0xFFD4D4DE.toInt(), 0xFFC8C8D4.toInt()) else intArrayOf(WHITE, 0xFFF0F0F6.toInt(), 0xFFE6E6EE.toInt())
        val mortar = if (crypt) MID else PALE
        box(0, 0, W, 31, mortar)
        for (row in 0 until 3) for (k in 0 until W / 16 + 1) {
            val off = if (row % 2 == 0) 0 else 8
            val l = k * 16 - off + 1; val t = row * 9 + 1; val r = l + 14; val b = t + 7
            val v = h(k, row, s.id + 31)
            box(l, t, r, b, tones[v % 3])
            box(l, t, r, t + 1, if (crypt) 0xFFE8E8EE.toInt() else 0xFFFFFFFF.toInt())
            box(l, b - 1, r, b, if (crypt) 0xFFB4B4C4.toInt() else 0xFFD8D8E2.toInt())
            if (v % 17 == 0) { px(l + 4, t + 2, DARK); px(l + 5, t + 3, DARK); px(l + 5, t + 4, DARK); px(l + 6, t + 5, DARK) }
            if (v % 23 == 0) { px(r - 1, t, mortar); px(r - 2, t, mortar); px(r - 1, t + 1, mortar) }
        }
        // ceiling shadow and the moulded cornice along the foot of the wall
        box(0, 0, W, 1, 0x30000000); box(0, 1, W, 2, 0x18000000)
        box(0, 24, W, 27, if (crypt) 0xFFB0B0C0.toInt() else 0xFFD0D0DC.toInt()); box(0, 24, W, 25, if (crypt) PALE else WHITE)
        box(0, 27, W, 30, MID)
        for (x in 0 until W step 6) { box(x + 1, 27, x + 4, 29, if (crypt) PALE else 0xFFDCDCE6.toInt()); box(x + 1, 29, x + 4, 30, DARK) }
        box(0, 30, W, 31, DARK)
        if (s.doors[2] && !s.throne) {
            // an archway up into the dark: keystone, jambs, steps falling away
            box(100, 2, 140, 31, DARK); box(102, 3, 138, 31, MID); box(104, 6, 136, 31, INK)
            for (k in 0 until 4) box(106 + k, 8 + k * 5, 134 - k, 13 + k * 5, if (k % 2 == 0) 0xFF303040.toInt() else 0xFF262634.toInt())
            box(116, 1, 124, 6, PALE); box(117, 2, 123, 5, WHITE)
            box(102, 2, 106, 4, PALE); box(134, 2, 138, 4, PALE)
            box(104, 29, 136, 31, 0xFF404052.toInt())
        }
    }

    private fun sides(s: Spec, crypt: Boolean) {
        for (side in 0..1) {
            val x0 = if (side == 0) 0 else W - 4
            box(x0, 31, x0 + 4, H, MID); box(if (side == 0) 3 else W - 4, 31, if (side == 0) 4 else W - 3, H, DARK)
            box(if (side == 0) 1 else W - 3, 31, if (side == 0) 2 else W - 2, H, PALE)
            for (y in 40 until H step 24) box(x0, y, x0 + 4, y + 1, DARK)
            if (s.doors[side]) { box(x0, 82, x0 + 4, 122, if (crypt) PALE else WHITE); box(x0, 81, x0 + 4, 82, DARK); box(x0, 122, x0 + 4, 123, DARK) }
        }
        box(0, H - 3, W, H, MID); box(0, H - 3, W, H - 2, DARK)
        if (s.doors[3]) box(104, H - 3, 136, H, if (crypt) PALE else WHITE)
        if (crypt) for (k in 0 until 6) { box(4 + k * 2, 31, 5 + k * 2, 31 + (6 - k) * 2, MID); box(W - 5 - k * 2, 31, W - 4 - k * 2, 31 + (6 - k) * 2, MID) }
    }

    /** Soft shadows where the floor meets the walls. */
    private fun ambient(s: Spec) {
        val a = intArrayOf(0x26000000, 0x1A000000, 0x10000000, 0x08000000, 0x04000000)
        for (k in a.indices) {
            box(4, 31 + k, W - 4, 32 + k, a[k])
            box(4 + k, 31, 5 + k, H - 3, a[k]); box(W - 5 - k, 31, W - 4 - k, H - 3, a[k])
        }
        for (k in 0 until 3) box(4, H - 4 - k, W - 4, H - 3 - k, a[k + 1])
    }

    /** A stone-rimmed pool: a deep shaded edge under the rim, lighter water toward you, and lilies. */
    private fun pool(w: RectF, kind: Int) {
        val l = w.left.toInt(); val t = w.top.toInt(); val r = w.right.toInt(); val b = w.bottom.toInt()
        box(l - 1, t + 1, r + 2, b + 3, 0x22000000)
        box(l + 1, t - 1, r - 1, b + 1, MID); box(l - 1, t + 1, r + 1, b - 1, MID); box(l, t, r, b, MID)
        box(l + 1, t - 1, r - 1, t, 0xFFD8D8E2.toInt()); box(l - 1, t + 1, l, b - 1, 0xFFC8C8D4.toInt())
        box(l + 1, b, r - 1, b + 1, DARK); box(r, t + 1, r + 1, b - 1, DARK)
        box(l + 2, t + 1, r - 2, b - 1, WATER); box(l + 1, t + 2, r - 1, b - 2, WATER)
        box(l + 2, t + 1, r - 2, t + 4, WATER_D); box(l + 1, t + 2, l + 3, b - 2, WATER_D)
        box(l + 3, t + 4, r - 2, t + 5, 0xFFA8C0DC.toInt())
        box(l + 3, b - 3, r - 3, b - 1, 0xFFD8EAF8.toInt())
        if (kind == 1 || kind == 2) {
            val v = h(l, t, kind)
            for (i in 0 until 2) {
                val x = l + 5 + (v shr (i * 4)) % maxOf(1, r - l - 12); val y = t + 6 + (v shr (i * 5 + 2)) % maxOf(1, b - t - 10)
                box(x, y, x + 5, y + 3, LEAF_D); box(x + 1, y, x + 4, y + 2, LEAF); px(x + 2, y + 1, LEAF_D)
                if (i == 0) { px(x + 3, y - 1, 0xFFF0A0C0.toInt()); px(x + 4, y - 1, 0xFFF8D0E0.toInt()) }
            }
        }
    }

    /** The soft shadow everything standing casts (light from the windows, up and to the left). */
    private fun shadow(k: Int, f: RectF) {
        val l = f.left.toInt(); val r = f.right.toInt(); val b = f.bottom.toInt()
        val reach = when (k) { PILLAR -> 6; STATUE -> 5; FOUNTAIN -> 4; else -> 3 }
        for (i in 0 until reach) {
            val inset = i * 2 - 1
            box(l + inset + 2, b - 1 + i, r - inset + 3, b + i, if (i == 0) 0x30000000 else 0x1C000000)
        }
    }

    // =====================================================================================
    private fun window(x: Int) {
        box(x - 1, 2, x + 9, 25, MID); box(x, 2, x + 8, 24, 0xFFDCEBFA.toInt())
        box(x, 2, x + 8, 3, MID); px(x, 3, MID); px(x + 7, 3, MID)
        box(x, 14, x + 8, 24, 0xFFEAF2FC.toInt()); box(x + 1, 4, x + 3, 12, 0xFFF4FAFF.toInt())
        box(x + 3, 3, x + 5, 24, 0xFFA0A0B4.toInt()); box(x, 12, x + 8, 13, 0xFFA0A0B4.toInt())
        box(x - 2, 24, x + 10, 26, PALE); box(x - 2, 26, x + 10, 27, DARK)
        // its light falling across the floor
        for (y in 31 until 112) {
            val k = (y - 31) / 3
            p.color = if (y % 2 == 0) 0x10FFF0B0 else 0x0AFFF0B0
            c.drawRect((x + k).toFloat(), y.toFloat(), (x + k + 10).toFloat(), (y + 1).toFloat(), p)
        }
    }

    private fun banner(x: Int, col: Int, dark: Int) {
        box(x - 2, 1, x + 14, 3, GOLD_D); box(x - 1, 1, x + 13, 2, GOLD)
        box(x, 3, x + 12, 22, dark); box(x + 1, 3, x + 11, 21, col)
        for (k in 0 until 6) { box(x + k, 22 + k / 2, x + k + 1, 23 + k / 2, dark); box(x + 11 - k, 22 + k / 2, x + 12 - k, 23 + k / 2, dark) }
        box(x + 1, 4, x + 2, 21, GOLD_D); box(x + 10, 4, x + 11, 21, GOLD_D)
        // the five eyes, in gold thread
        val cx = x + 6
        for ((ex, ey) in listOf(0 to 7, -3 to 10, 3 to 10, -2 to 14, 2 to 14)) { box(cx + ex - 1, ey, cx + ex + 1, ey + 2, GOLD); px(cx + ex, ey + 1, INK) }
    }

    private fun painting(x: Int, which: Int) {
        box(x - 1, 5, x + 25, 25, 0x30000000)
        box(x, 4, x + 24, 24, GOLD_D); box(x + 1, 5, x + 23, 23, GOLD); box(x + 2, 6, x + 22, 22, GOLD_D)
        val l = x + 3; val t = 7; val r = x + 21; val b = 21
        when (which) {
            0 -> {
                for (y in t until b) box(l, y, r, y + 1, if (y < 12) 0xFF9CC4E8.toInt() else if (y < 15) 0xFFC8DCF0.toInt() else LEAF)
                box(l + 12, 9, l + 15, 12, GOLD); box(l, 15, l + 8, 17, LEAF_D); box(l + 6, 14, l + 14, 16, LEAF_D); box(l, 18, r, b, 0xFF6A8C5A.toInt())
            }
            1 -> {
                box(l, t, r, b, 0xFF3A3448.toInt()); box(l + 5, 9, l + 13, 20, 0xFFC8A890.toInt()); box(l + 4, 17, l + 14, 21, 0xFF5A4A6A.toInt())
                for ((ex, ey) in listOf(9 to 11, 7 to 13, 11 to 13, 8 to 15, 10 to 15)) px(l + ex, ey, INK)
            }
            else -> {
                box(l, t, r, b, 0xFF4A3A30.toInt()); box(l, 18, r, b, 0xFF6A5040.toInt())
                box(l + 6, 11, l + 12, 18, 0xFF5A80B0.toInt()); box(l + 7, 9, l + 11, 11, 0xFF5A80B0.toInt())
                px(l + 8, 8, 0xFFE04848.toInt()); px(l + 10, 7, 0xFFF0A040.toInt()); px(l + 9, 8, LEAF_D)
                box(l + 13, 16, l + 16, 18, 0xFFE0C048.toInt())
            }
        }
        box(x + 8, 25, x + 16, 27, GOLD_D); box(x + 9, 25, x + 15, 26, GOLD)
    }

    private fun sconce(x: Int, y: Int) {
        box(x - 1, y + 2, x + 2, y + 9, DARK); box(x - 3, y + 2, x + 4, y + 4, DARK); box(x - 2, y + 2, x + 3, y + 3, MID)
        box(x - 1, y + 9, x + 2, y + 10, INK)
    }

    private fun decor(s: Spec, crypt: Boolean) {
        when (s.kind) {
            0 -> {
                window(56); window(176)
                banner(18, RUNE, 0xFF40307A.toInt()); banner(210, RUNE, 0xFF40307A.toInt())
                sconce(96, 8); sconce(144, 8)
                val d = s.dais
                val l = d.left.toInt(); val t = d.top.toInt(); val r = d.right.toInt(); val b = d.bottom.toInt()
                box(l - 1, b - 5, r + 2, b + 2, 0x2A000000)
                box(l, b - 6, r, b, MID); box(l, b - 6, r, b - 5, 0xFFF0F0F6.toInt()); box(l, b - 1, r, b, DARK)
                for (x in l + 4 until r step 8) box(x, b - 4, x + 1, b - 1, PALE)
                box(l + 4, b - 12, r - 4, b - 6, PALE); box(l + 4, b - 12, r - 4, b - 11, WHITE); box(l + 4, b - 7, r - 4, b - 6, DARK)
                box(l + 8, t, r - 8, b - 12, WHITE); box(l + 8, t, r - 8, t + 1, 0xFFFFFFFF.toInt())
                box(l + 8, b - 13, r - 8, b - 12, GOLD_D)
                // the dais carpet, purple with gold edging
                box(l + 14, t + 2, r - 14, b - 12, 0xFF40307A.toInt()); box(l + 15, t + 2, r - 15, b - 13, RUNE)
                box(l + 15, t + 2, l + 16, b - 13, GOLD); box(r - 16, t + 2, r - 15, b - 13, GOLD)
                if (s.crack == 2) {
                    box(110, 3, 128, 31, INK); box(106, 9, 134, 31, INK); box(104, 16, 136, 31, INK)
                    box(112, 6, 126, 31, 0xFF303040.toInt()); box(108, 12, 132, 31, 0xFF303040.toInt())
                    for (k in 0 until 8) { val v = h(k, 1, 4); box(100 + v % 40, 26 + v % 5, 102 + v % 40, 28 + v % 5, MID) }
                } else if (s.crack == 1) {
                    val xs = intArrayOf(119, 121, 117, 120, 123, 119, 116, 120)
                    for (k in xs.indices) box(xs[k], 2 + k * 7 / 2, xs[k] + 2, 6 + k * 7 / 2, INK)
                    box(112, 14, 117, 15, INK); box(123, 21, 129, 22, INK); box(110, 10, 112, 11, INK); box(128, 6, 131, 7, INK)
                }
            }
            1 -> { painting(30, 0); painting(108, 1); painting(186, 2); sconce(72, 8); sconce(168, 8) }
            2 -> {
                for (x in intArrayOf(30, 84, 148, 202)) window(x)
                sconce(118, 8)
                // ivy trailing down from the cornice
                for (i in 0 until 26) {
                    val v = h(i, 9, s.id); val x = 4 + v % (W - 8); val len = 4 + v % 9
                    for (y in 24 until 24 + len) { px(x + (if ((y + i) % 4 < 2) 0 else 1), y, if (y % 3 == 0) LEAF else LEAF_D) }
                    px(x - 1, 24 + len / 2, LEAF); px(x + 2, 24 + len - 2, LEAF)
                }
            }
            3 -> {
                val wood = 0xFF5A3E2A.toInt(); val woodL = 0xFF7A5638.toInt()
                val spines = intArrayOf(0xFF8A3A3A.toInt(), 0xFF3A5A8A.toInt(), 0xFF4A7A4A.toInt(), 0xFF8A6A3A.toInt(), 0xFF6A4A7A.toInt(), 0xFFC8B890.toInt(), 0xFF3A3A48.toInt())
                for (x0 in intArrayOf(8, 146)) {
                    box(x0 - 1, 1, x0 + 87, 31, INK); box(x0, 2, x0 + 86, 30, wood)
                    for (shelf in 0 until 3) {
                        val y0 = 4 + shelf * 9
                        var x = x0 + 2
                        while (x < x0 + 83) {
                            val v = h(x, shelf, s.id); val wdt = 2 + v % 3; val ht = 5 + v % 3
                            if (v % 11 == 0) { x += 3; continue }
                            val col = spines[v % spines.size]
                            box(x, y0 + 8 - ht, x + wdt, y0 + 8, col); box(x, y0 + 8 - ht, x + 1, y0 + 8, 0x30FFFFFF)
                            if (wdt > 2) px(x + 1, y0 + 9 - ht + 1, GOLD)
                            x += wdt + 1
                        }
                        box(x0, y0 + 8, x0 + 86, y0 + 9, woodL)
                    }
                    box(x0 - 1, 29, x0 + 87, 31, woodL)
                }
                // a ladder leaning on the left case
                for (y in 4 until 31) { px(84, y, woodL); px(90, y, woodL); if (y % 4 == 0) box(84, y, 91, y + 1, woodL) }
            }
            4 -> {
                banner(40, RUNE, 0xFF40307A.toInt()); banner(188, RUNE, 0xFF40307A.toInt())
                sconce(72, 8); sconce(168, 8)
            }
            5 -> {
                for (x in intArrayOf(36, 196)) {
                    box(x - 7, 6, x + 7, 24, 0xFF505062.toInt()); box(x - 6, 7, x + 6, 24, 0xFF3A3A4A.toInt()); box(x - 5, 5, x + 5, 7, 0xFF505062.toInt())
                    box(x - 3, 15, x + 3, 20, 0xFFF0EEE4.toInt()); box(x - 2, 20, x + 2, 22, 0xFFD8D4C8.toInt())
                    px(x - 2, 17, INK); px(x - 1, 17, INK); px(x + 1, 17, INK); px(x + 2, 17, INK); px(x, 19, DARK)
                }
                // cobwebs in the corners
                for (side in 0..1) {
                    val x0 = if (side == 0) 4 else W - 5; val dir = if (side == 0) 1 else -1
                    for (k in 0 until 4) { val a = k * 0.4f; for (r in 0 until 14) px(x0 + (dir * cos(a) * r).toInt(), 31 + (sin(a) * r * 0.9f).toInt(), 0x88F8F8FF.toInt()) }
                    for (r in intArrayOf(5, 9, 13)) for (k in 0 until r) { val a = k / r.toFloat() * 1.3f; px(x0 + (dir * cos(a) * r).toInt(), 31 + (sin(a) * r * 0.9f).toInt(), 0x66F8F8FF) }
                }
                for ((x, y) in CANDLES5) { box(x - 1, y - 4, x + 1, y, 0xFFF0EEE4.toInt()); box(x - 2, y, x + 2, y + 1, 0xFFD8D4C8.toInt()); px(x, y - 5, INK) }
            }
            6 -> {
                window(28); window(204)
                sconce(60, 8); sconce(180, 8)
                for (bxn in intArrayOf(84, 148)) {
                    box(bxn - 1, 1, bxn + 11, 23, GOLD_D); box(bxn, 2, bxn + 10, 22, GOLD); box(bxn + 1, 3, bxn + 3, 20, 0xFFFFF0A0.toInt())
                    box(bxn + 3, 6, bxn + 7, 10, 0xFFE04848.toInt()); px(bxn + 4, 7, 0xFFF8B0B0.toInt())
                }
                for ((gx, gy) in GOLD_PILES) {
                    for (k in 0 until 6) box(gx - 9 + k, gy - k, gx + 9 - k, gy - k + 1, if (k % 2 == 0) GOLD_D else GOLD)
                    box(gx - 9, gy, gx + 9, gy + 1, 0x30000000)
                    for (i in 0 until 8) { val v = h(gx, gy, i); px(gx - 7 + v % 14, gy - 1 - (v / 14) % 4, if (i % 3 == 0) 0xFFFFF0A0.toInt() else GOLD_D) }
                    px(gx - 2, gy - 3, 0xFFE04848.toInt()); px(gx + 3, gy - 2, 0xFF48A0E0.toInt())
                }
            }
        }
    }

    private val CANDLES5 = listOf(84 to 70, 156 to 70, 84 to 150, 156 to 150)
    private val GOLD_PILES = listOf(60 to 120, 212 to 150, 92 to 152, 150 to 146, 196 to 92)

    // =====================================================================================
    // every frame: flames, dust in the light, the water moving
    // =====================================================================================
    private val glow = Paint().apply { isAntiAlias = false }
    private fun flame(cv: Canvas, x: Float, y: Float, time: Float, seed: Int, big: Boolean = false) {
        val f = ((time * 9f).toInt() + seed) % 3
        val r = if (big) 12f else 9f
        glow.color = 0x1CFFC060; cv.drawCircle(x, y - 1, r, glow)
        glow.color = 0x22FFD890; cv.drawCircle(x, y - 1, r * 0.55f, glow)
        glow.color = 0xFFE05020.toInt(); cv.drawRect(x - 2, y - 2 - f, x + 2, y + 2, glow)
        glow.color = 0xFFF8B030.toInt(); cv.drawRect(x - 1, y - 3 - (f + 1) % 3, x + 1, y + 1, glow)
        glow.color = 0xFFFFF4C0.toInt(); cv.drawRect(x - 1, y - 1, x, y + 1, glow)
    }

    fun overlay(cv: Canvas, time: Float) {
        val s = spec ?: return
        if (s.kind == FOREST) { forestLife(cv, time, s); return }
        // torch flames
        val torches = when (s.kind) {
            0 -> listOf(96 to 8, 144 to 8); 1 -> listOf(72 to 8, 168 to 8); 2 -> listOf(118 to 8); 4 -> listOf(72 to 8, 168 to 8); 6 -> listOf(60 to 8, 180 to 8)
            else -> emptyList()
        }
        torches.forEachIndexed { i, (x, y) -> flame(cv, x + 0.5f, y.toFloat(), time, i) }
        if (s.kind == 5) CANDLES5.forEachIndexed { i, (x, y) ->
            val f = ((time * 7f).toInt() + i) % 3
            glow.color = 0x1CFFC060; cv.drawCircle(x.toFloat(), y - 6f, 6.5f, glow)
            glow.color = 0xFFF8B030.toInt(); cv.drawRect(x - 0.5f - (if (f == 1) 0.5f else 0f), y - 8f - f * 0.5f, x + 0.5f, y - 5f, glow)
            glow.color = 0xFFFFF4C0.toInt(); cv.drawRect(x - 0.5f, y - 6f, x + 0.5f, y - 5f, glow)
        }
        // dust drifting in the window light
        val wins = when (s.kind) { 0 -> intArrayOf(56, 176); 2 -> intArrayOf(30, 84, 148, 202); 6 -> intArrayOf(28, 204); else -> IntArray(0) }
        for ((wi, wx) in wins.withIndex()) for (i in 0 until 5) {
            val ph = (time * 0.05f + i * 0.21f + wi * 0.13f) % 1f
            val y = 34f + ph * 74f; val x = wx + (y - 31f) / 3f + 5f + sin(time * 0.7f + i * 2f + wi) * 4f
            glow.color = if ((i + wi) % 2 == 0) 0x90FFF8D8.toInt() else 0x60FFFFFF
            cv.drawRect(x, y, x + 1, y + 1, glow)
        }
        // ripples and glints on the pools
        for (w in s.pools) {
            val ph = (time * 0.8f) % 1f
            val y1 = w.top + 5 + ph * (w.height() - 9); val y2 = w.top + 5 + ((ph + 0.5f) % 1f) * (w.height() - 9)
            glow.color = 0xCCFFFFFF.toInt()
            cv.drawRect(w.left + 4, y1, w.left + w.width() * 0.45f, y1 + 1, glow); cv.drawRect(w.left + w.width() * 0.5f, y2, w.right - 4, y2 + 1, glow)
            val g = ((time * 1.7f).toInt() + w.left.toInt()) % 5
            if (g < 2) { glow.color = 0xFFFFFFFF.toInt(); val gx = w.left + 6 + abs(sin(time * 0.3f + w.top)) * (w.width() - 12); cv.drawRect(gx, w.bottom - 4, gx + 1, w.bottom - 3, glow) }
        }
    }

    // =====================================================================================
    // The Wildwood: soft grass with tufts and flowers, dirt paths from each way out to the middle,
    // a wall of canopy and trunks along the top, hedges down the sides, ponds with reeds and lilies.
    // =====================================================================================
    private val G1 = 0xFF5E9A34.toInt(); private val G2 = 0xFF4E8A30.toInt(); private val G3 = 0xFF7AB02E.toInt(); private val G4 = 0xFF2E6232.toInt()
    private val DIRT = 0xFFCCAA72.toInt(); private val DIRT_D = 0xFFA8844E.toInt(); private val DIRT_L = 0xFFE0C490.toInt()
    private val CAN_D = 0xFF16343A.toInt(); private val CAN = 0xFF2E6E3E.toInt(); private val CAN_L = 0xFF5E9A30.toInt(); private val CAN_H = 0xFFB6D446.toInt()
    private val BARK = 0xFF6E3A2E.toInt(); private val BARK_D = 0xFF2A1418.toInt()

    private fun forest(s: Spec) {
        box(0, 0, W, H, G1)
        // dappled patches of lighter and darker grass
        for (ty in 0 until H / 8 + 1) for (tx in 0 until W / 8) {
            val v = h(tx, ty, s.id)
            when (v % 9) { 0, 1 -> box(tx * 8, ty * 8, tx * 8 + 8, ty * 8 + 8, G2); 2 -> box(tx * 8 + 2, ty * 8 + 2, tx * 8 + 7, ty * 8 + 6, G3) }
            if (v % 9 == 0) for (k in 0 until 4) px(tx * 8 + (v shr (k + 3)) % 8, ty * 8 + (v shr (k + 7)) % 8, G1)
        }
        val cx = 120; val cy = 104
        // tufts and flowers on the grass
        for (i in 0 until 150) {
            val v = h(i, 3, s.id); val x = 6 + v % (W - 12); val y = 34 + (v shr 9) % (H - 40)
            when (v % 11) {
                0 -> { px(x, y, 0xFFF8F0F0.toInt()); px(x + 1, y, 0xFFF8F0F0.toInt()); px(x, y - 1, 0xFFF8F0F0.toInt()); px(x + 1, y - 1, 0xFFF8E060.toInt()) }
                1 -> { px(x, y, 0xFFE878A0.toInt()); px(x + 1, y - 1, 0xFFE878A0.toInt()); px(x, y - 1, 0xFFF8D0E0.toInt()) }
                2 -> { px(x, y, 0xFF8090F0.toInt()); px(x, y - 1, 0xFFB0C0FF.toInt()) }
                else -> { px(x, y, G4); px(x + 2, y, G4); px(x + 1, y - 1, G3); px(x + 1, y, G2) }
            }
        }
        for (w in s.rivers) riverBank(w)
        for (w in s.rivers) riverWater(w)
        for (w in s.pools) pond(w, s.id)
        for (b in s.bridges) bridge(b)
        for ((k, f) in s.props) when (k) { THICKET -> thicket(f, s.id); PALISADE -> palisade(f); else -> shadow(k, f) }
        if (s.open) return
        // the canopy along the top: a wall of round crowns over trunks
        box(0, 0, W, 31, CAN_D)
        for (k in 0 until 16) {
            val x = k * 16 + (h(k, 1, s.id) % 8) - 4
            if (s.doors[2] && x in 92..148) continue
            box(x + 6, 18, x + 11, 31, BARK_D); box(x + 7, 18, x + 10, 31, BARK); px(x + 7, 22, BARK_D)
        }
        for (k in 0 until 22) {
            val x = k * 11 + (h(k, 2, s.id) % 7) - 3; val y = 4 + (h(k, 4, s.id) % 10); val r = 9 + (h(k, 5, s.id) % 5)
            if (s.doors[2] && x in 98..142) continue
            p.color = 0xFF12161A.toInt(); c.drawCircle(x.toFloat(), y.toFloat(), r + 1f, p)
            p.color = CAN; c.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), p)
            p.color = CAN_L; c.drawCircle(x - r * 0.25f, y - r * 0.3f, r * 0.6f, p)
            p.color = CAN_H; c.drawCircle(x - r * 0.4f, y - r * 0.45f, r * 0.22f, p)
            // notched leaf edges below, specks of light above
            for (q in 0 until 5) { val a = 0.4f + q * 0.55f; px((x + Math.cos(a.toDouble()) * (r - 1)).toInt(), (y + Math.sin(a.toDouble()) * (r - 1)).toInt(), CAN_D) }
            for (q in 0 until 3) { val v = h(k, q, s.id); px(x - r / 2 + v % maxOf(1, r), y - r / 2 + (v shr 5) % maxOf(1, r / 2), if (q == 0) 0xFFB8E080.toInt() else CAN_H) }
        }
        if (s.doors[2]) {
            // a dark way through the trees
            box(100, 0, 140, 31, 0xFF142A16.toInt()); box(106, 0, 134, 31, 0xFF1E3A20.toInt()); box(110, 0, 130, 31, 0xFF2A4C2A.toInt())
            for (k in 0 until 4) box(106, k * 7, 134, k * 7 + 2, 0x30000000)
        }
        // the canopy's shadow on the grass
        for (k in 0 until 5) box(0, 31 + k, W, 32 + k, intArrayOf(0x40000000, 0x30000000, 0x20000000, 0x12000000, 0x08000000)[k])
        // hedges down the sides and along the bottom
        for (side in 0..1) {
            val x0 = if (side == 0) 0 else W - 6
            for (y in 28 until H step 7) {
                if (s.doors[side] && y in 80..120) continue
                p.color = CAN; c.drawCircle(x0 + 3f, y.toFloat(), 5f, p); p.color = CAN_L; c.drawCircle(x0 + 2f, y - 1.5f, 2.5f, p)
            }
        }
        for (x in 0 until W step 7) {
            if (s.doors[3] && x in 98..138) continue
            p.color = CAN; c.drawCircle(x.toFloat(), H.toFloat(), 5f, p); p.color = CAN_L; c.drawCircle(x - 1f, H - 2f, 2.5f, p)
        }
    }

    /** A river: muddy banks with reeds, deep water in the middle, lighter at the edges. */
    private fun riverBank(w: RectF) {
        val horiz = w.width() >= w.height()
        p.color = 0xFF4A6A30.toInt(); c.drawRect(w.left - (if (horiz) 0 else 3), w.top - (if (horiz) 3 else 0), w.right + (if (horiz) 0 else 3), w.bottom + (if (horiz) 3 else 0), p)
    }
    private fun riverWater(w: RectF) {
        val l = w.left.toInt(); val t = w.top.toInt(); val r = w.right.toInt(); val b = w.bottom.toInt()
        val horiz = r - l >= b - t
        box(l, t, r, b, 0xFF3A6A8A.toInt())
        if (horiz) { box(l, t + 3, r, b - 3, 0xFF24485E.toInt()); box(l, t, r, t + 1, 0xFF6CA2C2.toInt()); box(l, b - 1, r, b, 0xFF5A88A8.toInt()) }
        else { box(l + 3, t, r - 3, b, 0xFF24485E.toInt()); box(l, t, l + 1, b, 0xFF6CA2C2.toInt()); box(r - 1, t, r, b, 0xFF5A88A8.toInt()) }
        // reeds along the banks
        val n = maxOf(r - l, b - t) / 14
        for (k in 0 until n) {
            val v = h(k, l + t, 5)
            if (horiz) { val x = l + k * 14 + v % 8; box(x, t - 5, x + 1, t + 1, 0xFF3E6A28.toInt()); box(x + 2, b - 1, x + 3, b + 4, 0xFF3E6A28.toInt()); if (v % 3 == 0) box(x, t - 6, x + 1, t - 4, 0xFF7A4A24.toInt()) }
            else { val y = t + k * 14 + v % 8; box(l - 4, y, l + 1, y + 1, 0xFF3E6A28.toInt()); box(r - 1, y + 2, r + 4, y + 3, 0xFF3E6A28.toInt()) }
        }
    }
    /** A plank bridge with rails, the water running under it. */
    private fun bridge(b: RectF) {
        val l = b.left.toInt(); val t = b.top.toInt(); val r = b.right.toInt(); val bt = b.bottom.toInt()
        val horiz = r - l >= bt - t     // the river runs this way; the planks run across it
        val wood = 0xFF8A6238.toInt(); val woodD = 0xFF5A3A20.toInt(); val woodL = 0xFFB08850.toInt()
        box(l, t, r, bt, woodD)
        if (horiz) {
            // a bridge over a river running left-right: planks laid left-right, rails top and bottom... walking crosses up-down
            var x = l + 1; while (x < r - 1) { box(x, t + 1, minOf(x + 4, r - 1), bt - 1, wood); box(x, t + 1, minOf(x + 4, r - 1), t + 2, woodL); x += 5 }
            box(l - 1, t - 2, l + 2, bt + 2, woodD); box(r - 2, t - 2, r + 1, bt + 2, woodD)
            box(l - 1, t - 3, l + 2, t - 1, woodL); box(r - 2, t - 3, r + 1, t - 1, woodL)
        } else {
            var y = t + 1; while (y < bt - 1) { box(l + 1, y, r - 1, minOf(y + 3, bt - 1), wood); box(l + 1, y, r - 1, y + 1, woodL); y += 4 }
            box(l - 2, t - 1, r + 2, t + 2, woodD); box(l - 2, bt - 2, r + 2, bt + 1, woodD)
            box(l - 3, t - 1, l - 1, t + 2, woodL); box(r + 1, t - 1, r + 3, t + 2, woodL)
        }
        for (k in 0 until 3) box(l, bt + k, r, bt + k + 1, intArrayOf(0x40000000, 0x26000000, 0x10000000)[k])
    }
    /** A palisade of sharpened logs, lashed with rope. */
    private fun palisade(f: RectF) {
        val l = f.left.toInt(); val t = f.top.toInt(); val r = f.right.toInt(); val b = f.bottom.toInt()
        val horiz = r - l >= b - t
        val wood = 0xFF7A5232.toInt(); val woodD = 0xFF4A301A.toInt(); val woodL = 0xFFA07448.toInt()
        if (horiz) {
            var x = l; while (x < r) {
                box(x, t - 8, x + 4, b, woodD); box(x + 1, t - 7, x + 3, b - 1, wood); box(x + 1, t - 7, x + 2, b - 1, woodL)
                box(x + 1, t - 10, x + 3, t - 8, woodD); box(x + 2, t - 11, x + 3, t - 10, woodD)
                x += 4
            }
            box(l, t - 3, r, t - 2, 0xFFC8B078.toInt())
        } else {
            var y = t; while (y < b) { box(l, y, r, y + 4, woodD); box(l + 1, y + 1, r - 1, y + 3, wood); box(l + 1, y + 1, r - 1, y + 2, woodL); y += 4 }
        }
        for (k in 0 until 2) box(l, b + k, r, b + k + 1, intArrayOf(0x40000000, 0x20000000)[k])
    }
    /** Dense thicket: a wall of leaf clusters, darker toward the ground, shadow along its foot. */
    private fun thicket(f: RectF, id: Int) {
        val l = f.left.toInt(); val t = f.top.toInt(); val r = f.right.toInt(); val b = f.bottom.toInt()
        box(l, t, r, b, CAN_D)
        var y = t + 2
        while (y < b + 2) {
            var x = l + ((y * 7) % 5)
            while (x < r + 2) {
                val v = h(x, y, id)
                val rr = 3 + v % 3; val deep = (y - t) / maxOf(1f, (b - t).toFloat())
                p.color = 0xFF12161A.toInt(); c.drawCircle(x.toFloat(), y.toFloat(), rr + 0.8f, p)
                p.color = if (deep > 0.7f) CAN_D else CAN; c.drawCircle(x.toFloat(), y.toFloat(), rr.toFloat(), p)
                p.color = CAN_L; c.drawCircle(x - rr * 0.3f, y - rr * 0.35f, rr * 0.55f, p)
                if (v % 3 == 0) px(x - rr / 2, y - rr / 2, CAN_H)
                if (v % 11 == 0) { px(x + 1, y, 0xFFE04848.toInt()) }
                x += 5 + v % 3
            }
            y += 5
        }
        for (k in 0 until 3) box(l, b + k, r, b + k + 1, intArrayOf(0x40000000, 0x26000000, 0x10000000)[k])
    }
    private fun pond(w: RectF, id: Int) {
        val l = w.left; val t = w.top; val r = w.right; val b = w.bottom
        // a soft, muddy shore, wobbling round the water
        for (k in 0 until 14) {
            val a = k * 0.449f; val ex = (l + r) / 2 + Math.cos(a.toDouble()).toFloat() * (r - l) * 0.46f; val ey = (t + b) / 2 + Math.sin(a.toDouble()).toFloat() * (b - t) * 0.44f
            p.color = 0xFF4A6A30.toInt(); c.drawCircle(ex, ey, 6f + h(k, 3, id) % 5, p)
        }
        p.color = 0xFF5A7A3A.toInt(); c.drawOval(l - 3, t - 2, r + 3, b + 3, p)
        p.color = 0xFF24485E.toInt(); c.drawOval(l, t, r, b, p)
        p.color = 0xFF3A6A8A.toInt(); c.drawOval(l + 3, t + 3, r - 3, b - 2, p)
        p.color = 0xFF4E84A6.toInt(); c.drawOval(l + 8, t + 7, r - 6, b - 4, p)
        p.color = 0xFF6CA2C2.toInt(); c.drawOval(l + (r - l) * 0.3f, b - (b - t) * 0.3f, r - 10, b - 4, p)
        // reeds and rushes round the edge
        for (k in 0 until ((r - l) / 10).toInt()) {
            val v = h(k, 9, id); val a = (v % 628) / 100f
            val ex = (l + r) / 2 + Math.cos(a.toDouble()).toFloat() * (r - l) * 0.5f; val ey = (t + b) / 2 + Math.sin(a.toDouble()).toFloat() * (b - t) * 0.5f
            for (q in 0 until 3) { box(ex + q * 2f, ey - 6f - (q % 2) * 2, ex + q * 2f + 1, ey + 1, 0xFF3E6A28.toInt()); if (q == 1) box(ex + q * 2f, ey - 8f, ex + q * 2f + 1, ey - 5f, 0xFF7A4A24.toInt()) }
        }
        val v = h(l.toInt(), t.toInt(), id)
        // lilies and reeds
        for (i in 0 until 3) {
            val x = (l + 6 + (v shr (i * 4)) % maxOf(1, (r - l - 14).toInt())).toInt(); val y = (t + 5 + (v shr (i * 5 + 2)) % maxOf(1, (b - t - 10).toInt())).toInt()
            box(x, y, x + 5, y + 3, LEAF_D); box(x + 1, y, x + 4, y + 2, 0xFF7CB84C.toInt()); if (i == 0) { px(x + 2, y - 1, 0xFFF8B0D0.toInt()); px(x + 3, y - 1, WHITE) }
        }
        for (i in 0 until 4) { val x = (l - 2 + i * 3).toInt(); box(x, (b - 8 - i % 2 * 3).toInt(), x + 1, b.toInt() + 1, 0xFF4E7A30.toInt()); px(x, (b - 9 - i % 2 * 3).toInt(), 0xFF8A5A30.toInt()) }
    }

    /** Leaves drifting down, fireflies, and the odd sunbeam. */
    private fun forestLife(cv: Canvas, time: Float, s: Spec) {
        for (i in 0 until 7) {
            val ph = (time * 0.07f + i * 0.143f + (s.id % 7) * 0.05f) % 1f
            val x = (i * 37 + s.id * 13) % W + sin(time * 1.3f + i) * 10f; val y = 20f + ph * (H - 20f)
            glow.color = if (i % 3 == 0) 0xFFE8B040.toInt() else if (i % 3 == 1) 0xFF9AC850.toInt() else 0xFFD07030.toInt()
            val flip = (time * 3f + i).toInt() % 2
            cv.drawRect(x, y, x + 2f - flip, y + 1f + flip, glow)
        }
        // the current: glints drifting downstream
        for ((ri, w) in s.rivers.withIndex()) {
            val horiz = w.width() >= w.height(); val len = if (horiz) w.width() else w.height()
            for (i in 0 until (len / 12).toInt() + 1) {
                val ph = (time * 0.35f + i * 0.13f + ri * 0.3f) % 1f
                glow.color = if (i % 2 == 0) 0xAAD0ECF8.toInt() else 0x80FFFFFF.toInt()
                if (horiz) { val x = w.left + ph * w.width(); val y = w.top + 3 + (i * 5 % 9); cv.drawRect(x, y, x + 3, y + 1, glow) }
                else { val y = w.top + ph * w.height(); val x = w.left + 3 + (i * 5 % 9); cv.drawRect(x, y, x + 1, y + 3, glow) }
            }
        }
        // mist lying on the water, drifting and breathing
        for ((wi, w) in s.pools.withIndex()) for (i in 0 until 9) {
            val ph = (time * 0.04f + i * 0.111f + wi * 0.37f) % 1f
            val fx = w.left - 14f + ph * (w.width() + 28f); val fy = w.top + (i * 37 % 100) / 100f * w.height() + sin(time * 0.5f + i) * 3f
            val a = (0x16 + 0x10 * sin(time * 0.7f + i * 1.3f)).toInt().coerceIn(6, 40)
            glow.color = (a shl 24) or 0xE8F0F4; cv.drawOval(fx - 14f, fy - 4f, fx + 14f, fy + 4f, glow)
            glow.color = ((a / 2) shl 24) or 0xE8F0F4; cv.drawOval(fx - 22f, fy - 6f, fx + 22f, fy + 6f, glow)
        }
        // fireflies wandering about, gathering over the water
        val flies = 14 + s.pools.size * 8
        for (i in 0 until flies) {
            val w = if (i >= 14 && s.pools.isNotEmpty()) s.pools[(i - 14) % s.pools.size] else null
            val bx = w?.centerX() ?: (16f + (i * 53 + s.id * 7) % 208); val by = w?.centerY() ?: (44f + (i * 29 + s.id * 3) % 118)
            val rx = w?.width()?.times(0.6f) ?: 26f; val ry = w?.height()?.times(0.7f) ?: 16f
            val x = bx + sin(time * (0.3f + (i % 5) * 0.07f) + i * 1.7f) * rx + sin(time * 1.3f + i) * 3f
            val y = by + cos(time * (0.25f + (i % 4) * 0.06f) + i * 2.3f) * ry - 4f
            val pulse = ((time * (0.9f + (i % 3) * 0.3f) + i * 0.37f) % 2f)
            if (pulse > 1.4f) continue
            val k = 1f - abs(pulse - 0.7f) / 0.7f
            glow.color = ((0x38 * k).toInt() shl 24) or 0xF8F070; cv.drawCircle(x, y, 3.5f, glow)
            glow.color = ((0xFF * k).toInt().coerceIn(80, 255) shl 24) or 0xF8F8A0; cv.drawRect(x, y, x + 1f, y + 1f, glow)
        }
        // shafts of light through the canopy
        for (k in 0 until 2) {
            val x = 40f + ((s.id * 61 + k * 97) % 160)
            glow.color = 0x10FFF8C0
            for (y in 31 until H step 2) cv.drawRect(x + (y - 31) * 0.35f, y.toFloat(), x + 14f + (y - 31) * 0.35f, y + 1f, glow)
        }
    }

    // =====================================================================================
    private var vig: Bitmap? = null
    /** A soft dithered darkening toward the corners of the screen. */
    fun vignette(cv: Canvas) {
        val v = vig ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { b ->
            val bayer = intArrayOf(0, 2, 3, 1)
            for (y in 0 until H) for (x in 0 until W) {
                val dx = (x - W / 2f) / (W / 2f); val dy = (y - H / 2f) / (H / 2f)
                val d = kotlin.math.sqrt(dx * dx * 0.8f + dy * dy * 0.9f)
                val k = ((d - 0.72f) / 0.45f).coerceIn(0f, 1f)
                val lvl = (k * 4f).toInt() + (if (bayer[(x and 1) + (y and 1) * 2] < ((k * 4f) % 1f) * 4f) 1 else 0)
                if (lvl > 0) b.setPixel(x, y, (minOf(lvl, 4) * 0x0E shl 24) or 0x101020)
            }
            vig = b
        }
        cv.drawBitmap(v, 0f, 0f, p)
    }
}
