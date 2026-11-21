package app.foxbox.ui

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Wildwood: a forest of 6 x 5 screens (about four times the great hall), made fresh for each
 * level from a seed. A maze of paths (a random spanning tree with a few extra loops) runs between
 * the screens. Gates on the way to the exit are locked; keys lie elsewhere, always where you can
 * reach them before the gate they open (key i sits behind i gates at most). The exit itself is an
 * old root door that takes a key too.
 */
internal class WildLevel(val depth: Int, seed: Long, val bossLevel: Boolean) {
    val w = 6; val h = 5; val n = w * h
    private val rnd = kotlin.random.Random(seed)
    /** Each screen's open sides, as "WENS" letters (0 W, 1 E, 2 N, 3 S). */
    val doors = Array(n) { "" }
    /** Locked sides: room * 4 + side (both sides of each gate are listed). */
    val locked = HashSet<Int>()
    /** Keys: room, x, y. */
    val keys = ArrayList<IntArray>()
    var start = 0; var exit = 0
    /** Props per room: kind, left, top, right, bottom (kinds as in HallScreen). */
    val props = Array(n) { ArrayList<IntArray>() }
    val pools = Array(n) { ArrayList<IntArray>() }
    val names = Array(n) { "" }
    private val special = HashMap<Int, String>()
    /** Rivers (water you can't wade) and the bridges over them, per screen: left, top, right, bottom. */
    val rivers = Array(n) { ArrayList<IntArray>() }
    val bridges = Array(n) { ArrayList<IntArray>() }
    /** The fort (no fighting), and the monsters in it: room, x, y, role, kind. */
    var fort = -1
    /** Every screen of the fort (a block of up to 3 x 2, open inside, walled round). */
    val fortRooms = HashSet<Int>()
    /** Each screen's shape: the ways through it (kept clear), the open cells, and whether it's left as it is. */
    private val ways = Array(n) { ArrayList<IntArray>() }
    /** Each screen's ground, cell by cell (M_*): the forest and rock and water you can't cross. */
    val mass = Array(n) { ByteArray(GX * GY) }
    private val core = Array(n) { BooleanArray(GX * GY) }
    private val shaped = BooleanArray(n)
    fun massAt(r: Int, x: Float, y: Float): Int { val gx = (x / CW).toInt(); val gy = (y / CH).toInt(); return if (gx < 0 || gy < 0 || gx >= GX || gy >= GY) 0 else mass[r][gy * GX + gx].toInt() }
    /** The key that opens the stone door (the last key); the others open wooden gates. */
    fun isStone(i: Int) = i == keys.size - 1
    val npcs = ArrayList<IntArray>()
    /** Fog banks: screens lying under fog (each bank two to four screens), where shades drift. */
    val fog = HashSet<Int>()

    companion object {
        const val ROCK = 1; const val SHRUB = 2
        const val TREE = 9; const val STUMP = 10; const val LOG = 11; const val MUSHROOM = 12; const val EXIT = 13; const val THICKET = 14
        const val GRAVE = 15; const val RUIN_PILLAR = 16; const val RUIN_WALL = 17; const val DEADTREE = 18
        const val TENT = 19; const val CAMPFIRE = 20; const val STALL = 21; const val PALISADE = 22
        // village things in the fort
        const val WELL = 23; const val CRATES = 24; const val BARREL = 25; const val HAY = 26; const val LANTERN = 27
        const val BENCH = 28; const val CART = 29; const val SIGNPOST = 30; const val FLOWERBED = 31; const val LAUNDRY = 32
        // the grander ruins: a paved plaza (walkable), standing columns, an arch, a statue, fallen drums
        const val PLAZA = 33; const val COLUMN = 34; const val ARCH = 35; const val RUIN_STATUE = 36; const val FALLEN_COLUMN = 37
        /** Rock you can't climb: the wood's walls, with the thickets and ponds. */
        const val CLIFF = 38
        /** A house in the fort. */
        const val BUILDING = 39
        /** The shaping grid: 40 x 35 cells of 6 x 5. */
        const val CW = 6; const val CH = 5; const val GX = 40; const val GY = 35
        /** What fills a cell of the grid: open ground, deep forest, rock, water. */
        const val M_OPEN = 0; const val M_FOREST = 1; const val M_ROCK = 2; const val M_WATER = 3
        /** Fort folk: 0 the toad (herbs), 1 the moth-witch (spirit blooms), 2 a mercenary (its kind in [4]), 3 a guard, 4 townsfolk. */
        const val NPC_TOAD = 0; const val NPC_MOTH = 1; const val NPC_MERC = 2; const val NPC_GUARD = 3; const val NPC_FOLK = 4
        /** Where rivers cross a screen: a band along y = 151 and one along x = 52, meeting at the corner pond. */
        const val RIV_Y = 150; const val RIV_X = 52; const val RIV_HALF = 9
        val DX = intArrayOf(-1, 1, 0, 0); val DY = intArrayOf(0, 0, -1, 1); val OPP = intArrayOf(1, 0, 3, 2)
        private val A = listOf("MOSSY", "FERN", "BRAMBLE", "WHISPER", "HOLLOW", "TANGLE", "DEW", "AMBER", "THORN", "MIST", "ROOT", "LANTERN", "OWL", "BRACKEN", "SILVER", "GLOAM")
        private val B = listOf("GLADE", "HOLLOW", "THICKET", "DELL", "COPSE", "GROVE", "BOWER", "WEALD", "SPINNEY", "CLEARING", "DINGLE", "BOG")
    }

    private val sides = Array(n) { BooleanArray(4) }
    private fun nb(r: Int, d: Int): Int { val x = r % w + DX[d]; val y = r / w + DY[d]; return if (x < 0 || y < 0 || x >= w || y >= h) -1 else y * w + x }
    private fun open(r: Int, d: Int) { val o = nb(r, d); if (o < 0) return; sides[r][d] = true; sides[o][OPP[d]] = true }

    init {
        // 1. a maze: a random depth-first spanning tree, starting from the bottom row
        start = (h - 1) * w + rnd.nextInt(w)
        val seen = BooleanArray(n); val stack = ArrayList<Int>(); stack += start; seen[start] = true
        val parent = IntArray(n) { -1 }; val parentDir = IntArray(n) { -1 }
        while (stack.isNotEmpty()) {
            val r = stack.last()
            val opts = (0 until 4).filter { d -> val o = nb(r, d); o >= 0 && !seen[o] }
            if (opts.isEmpty()) { stack.removeAt(stack.size - 1); continue }
            val d = opts[rnd.nextInt(opts.size)]; val o = nb(r, d)
            open(r, d); seen[o] = true; parent[o] = r; parentDir[o] = d; stack += o
        }
        // 2. the exit: the screen farthest from the start along the paths
        val dist = IntArray(n) { -1 }; dist[start] = 0
        val q = ArrayDeque<Int>(); q += start
        while (q.isNotEmpty()) { val r = q.removeFirst(); for (d in 0 until 4) if (sides[r][d]) { val o = nb(r, d); if (dist[o] < 0) { dist[o] = dist[r] + 1; q += o } } }
        exit = (0 until n).maxByOrNull { dist[it] }!!
        // 3. gates along the way: keys = gates + 1 (the root door takes one too)
        val keyCount = minOf(3, 2 + depth / 5)
        val gates = keyCount - 1
        val path = ArrayList<Int>(); run { var r = exit; while (r >= 0) { path.add(0, r); r = parent[r] } }
        val gateRooms = ArrayList<Int>()   // the room on the far side of each gate
        for (i in 1..gates) {
            val at = (path.size * i / (gates + 1)).coerceIn(1, path.size - 1)
            val r = path[at]
            if (r !in gateRooms) gateRooms += r
        }
        for (r in gateRooms) { val d = parentDir[r]; val pr = parent[r]; locked += pr * 4 + d; locked += r * 4 + OPP[d] }
        // which "region" each screen is in: how many gates lie between it and the start
        val region = IntArray(n)
        fun regionOf(r: Int): Int { var k = 0; var x = r; while (parent[x] >= 0) { if (x in gateRooms) k++; x = parent[x] }; return k }
        for (r in 0 until n) region[r] = regionOf(r)
        // 4. a few extra paths, only within a region (so the gates still matter)
        for (r in 0 until n) for (d in 1..3 step 2) {
            val o = nb(r, d); if (o < 0 || sides[r][d]) continue
            if (region[o] == region[r] && o != exit && r != exit && rnd.nextFloat() < 0.22f) open(r, d)
        }
        for (r in 0 until n) doors[r] = (0 until 4).filter { sides[r][it] }.joinToString("") { "WENS"[it].toString() }
        // 5. keys: key i somewhere in region i, far from the start, never on the start or exit
        val used = HashSet<Int>(); used += start; used += exit
        for (i in 0 until keyCount) {
            val reg = minOf(i, gateRooms.size)
            val cands = (0 until n).filter { region[it] == reg && it !in used }.ifEmpty { (0 until n).filter { region[it] <= reg && it !in used } }
            // prefer dead ends and far corners
            // not tucked away at the far ends: a middling walk, easy to come across
            val mid = (cands.maxOfOrNull { dist[it] } ?: 0) / 2
            val score = cands.associateWith { -kotlin.math.abs(dist[it] - mid) * 2 + (if (doors[it].length == 1) 2 else 0) + rnd.nextInt(4) }
            val best = cands.maxByOrNull { score.getValue(it) } ?: start
            used += best
            keys += intArrayOf(best, 0, 0)
        }
        // 6. scenery for each screen, a fort near the start, rivers from pond to pond
        for (r in 0 until n) furnish(r)
        // the fort: the biggest block of screens (3 x 2 at best) all in one region, never the start or exit
        run {
            var best: List<Int>? = null; var bestScore = -1e9f
            for ((bw, bh) in listOf(3 to 2, 2 to 3, 2 to 2, 2 to 1, 1 to 2, 1 to 1)) {
                for (oy in 0..h - bh) for (ox in 0..w - bw) {
                    val block = ArrayList<Int>(); for (y in oy until oy + bh) for (x in ox until ox + bw) block += y * w + x
                    if (block.any { it == start || it == exit }) continue
                    val reg = region[block[0]]; if (block.any { region[it] != reg }) continue
                    val score = -reg * 10f - block.minOf { dist[it] } * 0.5f + rnd.nextFloat()
                    if (score > bestScore) { bestScore = score; best = block }
                }
                if (best != null) break
            }
            best?.let { b ->
                fortRooms += b; fort = b[b.size / 2]
                // open the fort inside: no hedges between its screens
                for (r in b) for (d in 0 until 4) { val o = nb(r, d); if (o in fortRooms) open(r, d) }
                for (r in 0 until n) doors[r] = (0 until 4).filter { sides[r][it] }.joinToString("") { "WENS"[it].toString() }
                furnishFort(b)
            }
        }
        repeat(if (depth >= 3) 2 else 1) { river() }
        for (r in 0 until n) if (shaped[r] && r !in fortRooms) fillMass(r)
        for (k in keys) { val spot = clearSpot(k[0]); k[1] = spot.first; k[2] = spot.second }
        for (r in 0 until n) names[r] = A[rnd.nextInt(A.size)] + " " + B[rnd.nextInt(B.size)]
        for ((r, nm) in special) names[r] = nm
        names[start] = "THE WOOD'S EDGE"; names[exit] = if (bossLevel) "THE HEART OF THE WOOD" else "THE ROOT DOOR"
        if (fort >= 0) { val fn = listOf("THE STOCKADE", "BRAMBLE FORT", "THE WARDEN'S CAMP", "MOSSWALL KEEP")[rnd.nextInt(4)]; for (r in fortRooms) names[r] = fn }
        // 7. fog banks, drawn last so the rest of the wood comes out the same
        repeat(if (depth >= 2) 2 else 1) {
            val no = HashSet<Int>().apply { add(start); add(exit); for (f in fortRooms) { add(f); for (d in 0 until 4) nb(f, d).let { if (it >= 0) add(it) } } }
            val cands = (0 until n).filter { it !in no && it !in fog }
            if (cands.isEmpty()) return@repeat
            val bank = arrayListOf(cands[rnd.nextInt(cands.size)])
            val size = 2 + rnd.nextInt(3)
            var tries = 0
            while (bank.size < size && tries++ < 30) {
                val r = bank[rnd.nextInt(bank.size)]; val o = nb(r, rnd.nextInt(4))
                if (o >= 0 && o !in no && o !in bank) bank += o
            }
            fog += bank
            if (bank.size >= 2) for (r in bank) if (special[r] == null) names[r] = listOf("MISTY ", "FOGBOUND ", "MURKY ", "SHROUDED ")[rnd.nextInt(4)] + B[rnd.nextInt(B.size)]
        }
    }

    /**
     * A screen's shape, carved out of solid wood: one or two clearings (wide, or tight), winding
     * passages of changing width from each way in to a clearing, sometimes two ways round between
     * clearings (leaving a mass standing in the middle), then the edges rounded off. What's left is
     * a third to a half of the screen: forest mostly, here and there rock or water.
     * [size]: 0 any, 1 roomy, 2 an arena, 3 narrow ways only. [keep]: kept clear too.
     */
    private fun shape(r: Int, size: Int, keep: IntArray?) {
        val w0 = ways[r]; w0.clear()
        shaped[r] = true
        val open = BooleanArray(GX * GY); val cr = core[r]; java.util.Arrays.fill(cr, false)
        fun carve(x: Float, y: Float, rad: Float, isCore: Boolean) {
            val g0 = ((x - rad) / CW).toInt().coerceAtLeast(0); val g1 = ((x + rad) / CW).toInt().coerceAtMost(GX - 1)
            val h0 = ((y - rad) / CH).toInt().coerceAtLeast(0); val h1 = ((y + rad) / CH).toInt().coerceAtMost(GY - 1)
            for (gy in h0..h1) for (gx in g0..g1) { val cx = gx * CW + CW / 2f; val cy = gy * CH + CH / 2f
                val dd = (cx - x) * (cx - x) + (cy - y) * (cy - y)
                if (dd < rad * rad) { open[gy * GX + gx] = true; if (isCore && dd < (rad * 0.6f) * (rad * 0.6f)) cr[gy * GX + gx] = true } }
        }
        fun ellipse(x: Float, y: Float, rx: Float, ry: Float) {
            for (gy in 0 until GY) for (gx in 0 until GX) { val dx = (gx * CW + CW / 2f - x) / rx; val dy = (gy * CH + CH / 2f - y) / ry
                val wob = 1f + 0.18f * kotlin.math.sin(kotlin.math.atan2(dy, dx) * 3f + x) // a less perfect rim
                if (dx * dx + dy * dy < wob * wob) { open[gy * GX + gx] = true; if (dx * dx + dy * dy < 0.4f) cr[gy * GX + gx] = true } }
        }
        /** A winding passage: a curve from a to b, its width wandering. */
        fun passage(ax: Float, ay: Float, bx: Float, by: Float, bendK: Float) {
            val mx = (ax + bx) / 2; val my = (ay + by) / 2; val dx = bx - ax; val dy = by - ay; val L = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val cx = mx - dy / L * bendK; val cy = my + dx / L * bendK
            val wA = 11f + rnd.nextFloat() * 6f; val wB = 11f + rnd.nextFloat() * 9f; val ph = rnd.nextFloat() * 6f
            val steps = (L / 2f).toInt().coerceAtLeast(8)
            for (i in 0..steps) {
                val t = i / steps.toFloat(); val u = 1 - t
                val x = u * u * ax + 2 * u * t * cx + t * t * bx; val y = u * u * ay + 2 * u * t * cy + t * t * by
                val rad = wA + (wB - wA) * t + 3f * kotlin.math.sin(t * 7f + ph)
                carve(x, y, rad, true)
                if (i % 4 == 0) w0 += intArrayOf((x - rad * 0.7f).toInt(), (y - rad * 0.7f).toInt(), (x + rad * 0.7f).toInt(), (y + rad * 0.7f).toInt())
            }
        }
        // clearings
        val cl = ArrayList<FloatArray>()
        when (size) {
            2 -> cl += floatArrayOf(120f, 100f, 100f, 62f)
            3 -> cl += floatArrayOf(120f + rnd.nextInt(-20, 21), 100f + rnd.nextInt(-14, 15), 22f, 18f)
            else -> {
                val big = if (size == 1) 62f + rnd.nextInt(24) else 34f + rnd.nextInt(44)
                cl += floatArrayOf(120f + rnd.nextInt(-40, 41), 98f + rnd.nextInt(-26, 27), big, big * (0.6f + rnd.nextFloat() * 0.2f))
                if (rnd.nextFloat() < 0.65f) {
                    // a second clearing, off to one side
                    for (k in 0 until 10) {
                        val x = 40f + rnd.nextInt(160); val y = 36f + rnd.nextInt(104)
                        if (kotlin.math.hypot(x - cl[0][0], (y - cl[0][1]) * 1.4f) > cl[0][2] + 50f) { val rr = 26f + rnd.nextInt(30); cl += floatArrayOf(x, y, rr, rr * 0.7f); break }
                    }
                }
            }
        }
        for (c in cl) ellipse(c[0], c[1], c[2], c[3])
        // passages: each way in winds to the nearest clearing
        val doorsXY = listOf(floatArrayOf(0f, 104f), floatArrayOf(240f, 104f), floatArrayOf(120f, 0f), floatArrayOf(120f, 176f))
        for (d in 0 until 4) {
            if (!sides[r][d]) continue
            val (ex, ey) = doorsXY[d].let { it[0] to it[1] }
            // straight in a little at first, so it meets the next screen's way
            val ix = ex + (if (d == 0) 14f else if (d == 1) -14f else 0f); val iy = ey + (if (d == 2) 12f else if (d == 3) -12f else 0f)
            passage(ex, ey, ix, iy, 0f)
            val c = cl.minByOrNull { kotlin.math.hypot(it[0] - ix, it[1] - iy) }!!
            passage(ix, iy, c[0], c[1], (rnd.nextFloat() - 0.5f) * 110f)
        }
        if (cl.size > 1) {
            // between the clearings: one way round, sometimes both ways round a mass left standing
            val bend = 30f + rnd.nextFloat() * 40f
            passage(cl[0][0], cl[0][1], cl[1][0], cl[1][1], bend)
            if (rnd.nextFloat() < 0.5f) passage(cl[0][0], cl[0][1], cl[1][0], cl[1][1], -bend)
        }
        keep?.let { k -> val c = cl.minByOrNull { kotlin.math.hypot(it[0] - (k[0] + k[2]) / 2f, it[1] - (k[1] + k[3]) / 2f) }!!; passage((k[0] + k[2]) / 2f, (k[1] + k[3]) / 2f, c[0], c[1], 20f) }
        keep?.let { k -> for (gy in 0 until GY) for (gx in 0 until GX) { val cx = gx * CW + CW / 2f; val cy = gy * CH + CH / 2f; if (cx > k[0] && cx < k[2] && cy > k[1] && cy < k[3]) { open[gy * GX + gx] = true; cr[gy * GX + gx] = true } }; w0 += k }
        if (size == 2) java.util.Arrays.fill(open, true)
        // round the edges off: twice, a cell goes with the majority round it (the core stays open)
        repeat(2) {
            val o2 = open.copyOf()
            for (gy in 0 until GY) for (gx in 0 until GX) {
                var c = 0; for (oy in -1..1) for (ox in -1..1) { val x = gx + ox; val y = gy + oy; if (x < 0 || y < 0 || x >= GX || y >= GY || open[y * GX + x]) c++ }
                val i = gy * GX + gx
                o2[i] = cr[i] || c >= 5
            }
            System.arraycopy(o2, 0, open, 0, open.size)
        }
        // too closed in? open pockets off the edges; too open? leave a stand of trees out in it
        val total = GX * GY
        fun closed() = open.count { !it }
        var guard = 0
        while (closed() > total * 0.45f && guard++ < 40) {
            val i = (0 until total).filter { !open[it] && listOf(-1, 1, -GX, GX).any { o -> it + o in 0 until total && open[it + o] } }.let { if (it.isEmpty()) -1 else it[rnd.nextInt(it.size)] }
            if (i < 0) break
            carve((i % GX) * CW + CW / 2f, (i / GX) * CH + CH / 2f, 12f + rnd.nextFloat() * 10f, false)
        }
        guard = 0
        while (closed() < total * 0.28f && guard++ < 30) {
            val x = 20f + rnd.nextInt(200); val y = 24f + rnd.nextInt(128); val rad = 10f + rnd.nextFloat() * 12f
            var hitsCore = false
            for (gy in 0 until GY) for (gx in 0 until GX) { val dd = kotlin.math.hypot(gx * CW + CW / 2f - x, gy * CH + CH / 2f - y); if (dd < rad + 8f && cr[gy * GX + gx]) hitsCore = true }
            if (hitsCore) continue
            for (gy in 0 until GY) for (gx in 0 until GX) if (kotlin.math.hypot(gx * CW + CW / 2f - x, gy * CH + CH / 2f - y) < rad) open[gy * GX + gx] = false
        }
        // anything open you couldn't reach from the ways in is closed up
        val seen = BooleanArray(total); val q = ArrayDeque<Int>()
        for (i in 0 until total) if (cr[i] && open[i]) { seen[i] = true; q += i }
        while (q.isNotEmpty()) { val i = q.removeFirst(); val gx = i % GX; val gy = i / GX
            for ((ox, oy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) { val x = gx + ox; val y = gy + oy; if (x < 0 || y < 0 || x >= GX || y >= GY) continue; val j = y * GX + x; if (!seen[j] && open[j]) { seen[j] = true; q += j } } }
        val ms = mass[r]
        for (i in 0 until total) ms[i] = if (open[i] && seen[i]) M_OPEN.toByte() else M_FOREST.toByte()
        if (w0.isEmpty()) w0 += intArrayOf(cl[0][0].toInt() - 10, cl[0][1].toInt() - 8, cl[0][0].toInt() + 10, cl[0][1].toInt() + 8)
    }
    /** Is this box on the open ground of its screen? */
    private fun onOpen(r: Int, l: Int, t: Int, rr: Int, b: Int): Boolean {
        if (!shaped[r]) return true
        val ms = mass[r]
        for (gy in (t / CH).coerceIn(0, GY - 1)..((b - 1) / CH).coerceIn(0, GY - 1)) for (gx in (l / CW).coerceIn(0, GX - 1)..((rr - 1) / CW).coerceIn(0, GX - 1)) if (ms[gy * GX + gx].toInt() != M_OPEN) return false
        return true
    }
    /**
     * What the closed ground is made of: deep forest mostly, a stand of rock or a pool here and
     * there; water where a river runs into it; big trees along the forest's edge.
     */
    private fun fillMass(r: Int) {
        val ms = mass[r]
        val ph = rnd.nextFloat() * 6.28f; val ph2 = rnd.nextFloat() * 6.28f
        val rockK = if (rnd.nextFloat() < 0.4f) 1.45f else 9f; val waterK = if (rnd.nextFloat() < 0.45f) 1.5f else 9f
        for (gy in 0 until GY) for (gx in 0 until GX) {
            val i = gy * GX + gx; if (ms[i].toInt() == M_OPEN) continue
            val v = kotlin.math.sin(gx * 0.31f + ph) + kotlin.math.sin(gy * 0.37f + ph2) + kotlin.math.sin((gx - gy) * 0.17f)
            val w = kotlin.math.sin(gx * 0.27f + ph2) + kotlin.math.sin(gy * 0.29f + ph) + kotlin.math.sin((gx + gy) * 0.19f)
            ms[i] = (if (w > waterK) M_WATER else if (v > rockK) M_ROCK else M_FOREST).toByte()
        }
        // where a river crosses: water all the way (bridges stay open)
        for (gy in 0 until GY) for (gx in 0 until GX) {
            val l = gx * CW; val t = gy * CH; val rr = l + CW; val b = t + CH
            val i = gy * GX + gx
            if (bridges[r].any { p -> rr > p[0] && l < p[2] && b > p[1] && t < p[3] }) { ms[i] = M_OPEN.toByte(); continue }
            if (rivers[r].any { p -> rr > p[0] && l < p[2] && b > p[1] && t < p[3] }) ms[i] = if (rivers[r].any { p -> l >= p[0] && rr <= p[2] && t >= p[1] && b <= p[3] }) M_OPEN.toByte() else if (ms[i].toInt() == M_OPEN) M_OPEN.toByte() else M_WATER.toByte()
        }
        // nothing left standing in the wood (but a tree's own roots)
        props[r].removeAll { p -> p[0] != EXIT && p[0] != TREE && !onOpen(r, p[1], p[2], p[3], p[4]) }
        // big trees along the forest's lower edge, their feet in it
        var made = 0
        for (k in 0 until 80) {
            if (made >= 4 + rnd.nextInt(4)) break
            val gx = 1 + rnd.nextInt(GX - 2); val gy = 2 + rnd.nextInt(GY - 4)
            val i = gy * GX + gx
            if (ms[i].toInt() != M_FOREST || ms[i + GX].toInt() != M_OPEN || ms[i - 1].toInt() != M_FOREST || ms[i + 1].toInt() != M_FOREST) continue
            val x = gx * CW + 3; val y = gy * CH + 4
            if (props[r].any { p -> p[0] == TREE && kotlin.math.abs(p[1] - x) < 20 && kotlin.math.abs(p[4] - y) < 14 }) continue
            props[r] += intArrayOf(TREE, x - 6, y - 4, x + 6, y); made++
        }
    }
    private fun inCorridor(r: Int, l: Int, t: Int, rr: Int, b: Int): Boolean = ways[r].any { w -> rr > w[0] && l < w[2] && b > w[1] && t < w[3] }
    private fun free(r: Int, l: Int, t: Int, rr: Int, b: Int, pad: Int): Boolean {
        if (l < 8 || rr > 232 || t < 36 || b > 168) return false
        if (inCorridor(r, l, t, rr, b)) return false
        if (!onOpen(r, l, t, rr, b)) return false
        if (shaped[r]) { val cr = core[r]; for (gy in ((t - pad) / CH).coerceIn(0, GY - 1)..((b + pad) / CH).coerceIn(0, GY - 1)) for (gx in ((l - pad) / CW).coerceIn(0, GX - 1)..((rr + pad) / CW).coerceIn(0, GX - 1)) if (cr[gy * GX + gx]) return false }
        for (p in props[r]) if (rr + pad > p[1] && l - pad < p[3] && b + pad > p[2] && t - pad < p[4]) return false
        for (p in pools[r]) if (rr + pad > p[0] && l - pad < p[2] && b + pad > p[1] && t - pad < p[3]) return false
        return true
    }
    private fun place(r: Int, kind: Int, fw: Int, fh: Int, tries: Int = 30, pad: Int = 6, area: IntArray? = null): Boolean {
        repeat(tries) {
            val ax = area?.get(0) ?: 10; val ay = area?.get(1) ?: 38; val bxx = area?.get(2) ?: 230; val byy = area?.get(3) ?: 166
            if (bxx - ax - fw <= 0 || byy - ay - fh <= 0) return false
            val l = ax + rnd.nextInt(bxx - ax - fw); val t = ay + rnd.nextInt(byy - ay - fh)
            if (free(r, l, t, l + fw, t + fh, pad)) { props[r] += intArrayOf(kind, l, t, l + fw, t + fh); return true }
        }
        return false
    }

    private fun furnish(r: Int) {
        if (r == exit) {
            // the great stone door, against a wall with no way through it; for the boss, an open arena
            val sd = sides[r]
            val door = when { !sd[2] -> intArrayOf(96, 38, 144, 50); !sd[3] -> intArrayOf(96, 146, 144, 158); !sd[0] -> intArrayOf(14, 70, 62, 82); else -> intArrayOf(178, 70, 226, 82) }
            shape(r, if (bossLevel) 2 else 1, intArrayOf(door[0] - 8, door[1] - 6, door[2] + 8, door[3] + 18))
            props[r] += intArrayOf(EXIT, door[0], door[1], door[2], door[3])
            if (!bossLevel) { repeat(3) { place(r, TREE, 12, 6) }; repeat(2) { place(r, SHRUB, 16, 8) } }
            else { repeat(4) { place(r, MUSHROOM, 8, 5, area = intArrayOf(10, 130, 230, 166)) } }
            return
        }
        val type = if (r == start) 1 else rnd.nextInt(12)
        shape(r, when (type) { 10, 11, 1 -> 1; 8, 9 -> 3; else -> 0 }, null)
        when (type) {
            10 -> { graveyard(r); special[r] = "THE " + listOf("BARROWS", "GRAVEYARD", "BONE GARDEN", "OLD CHURCHYARD")[rnd.nextInt(4)]; return }
            11 -> { ruins(r); special[r] = listOf("FALLEN SHRINE", "BROKEN HALL", "OLD RUINS", "TUMBLED TOWER")[rnd.nextInt(4)]; return }
            0 -> { repeat(5 + rnd.nextInt(4)) { place(r, TREE, 12, 6) }; repeat(2) { place(r, SHRUB, 16, 8) } }       // a grove
            1 -> { repeat(2) { place(r, TREE, 12, 6) }; repeat(3) { place(r, SHRUB, 16, 8) }; repeat(2) { place(r, MUSHROOM, 8, 5) } }  // a glade
            2, 7 -> {   // a lake (or two), as big as the ways through allow
                repeat(2) { lake(r) }
                repeat(2) { place(r, TREE, 12, 6) }; place(r, SHRUB, 16, 8)
            }
            8, 9 -> { repeat(2) { place(r, MUSHROOM, 8, 5, pad = 3) } }      // a narrow way: just paths through the trees
            3 -> { repeat(6) { place(r, MUSHROOM, 8, 5, pad = 3) }; repeat(3) { place(r, TREE, 12, 6) }; place(r, STUMP, 12, 6) }   // fungus
            4 -> { repeat(3) { place(r, ROCK, 14, 8) }; repeat(2) { place(r, STUMP, 12, 6) }; place(r, LOG, 28, 7); repeat(2) { place(r, TREE, 12, 6) } }  // old fall
            5 -> { repeat(6) { place(r, SHRUB, 16, 8, pad = 3) }; repeat(3) { place(r, TREE, 12, 6) } }   // thicket
            else -> { repeat(8 + rnd.nextInt(4)) { place(r, TREE, 12, 6, pad = 5) } }    // deep wood
        }
    }

    /** Rows of leaning headstones, a dead tree or two, bones in the grass. */
    private fun graveyard(r: Int) {
        for (row in 0 until 4) for (col in 0 until 7) {
            if (rnd.nextFloat() < 0.35f) continue
            val x = 22 + col * 30 + rnd.nextInt(-3, 4); val y = 48 + row * 32 + rnd.nextInt(-2, 3)
            if (free(r, x, y, x + 10, y + 6, 2)) props[r] += intArrayOf(GRAVE, x, y, x + 10, y + 6)
        }
        repeat(2) { place(r, DEADTREE, 10, 6) }
        repeat(2) { place(r, ROCK, 14, 8) }
    }
    /**
     * The ruins of something great: a paved plaza (walkable), a colonnade along it, a broken arch,
     * a statue looking out over it, columns fallen in drums, crumbling walls and rubble round about.
     */
    private fun ruins(r: Int) {
        // the colonnade first (the plaza goes down last so nothing else is kept off it)
        for (row in 0..1) {
            val y = if (row == 0) 56 else 140
            var x = 26
            while (x < 214) { if (rnd.nextFloat() < 0.8f) { val tall = rnd.nextFloat() < 0.65f; if (free(r, x, y, x + 10, y + 7, 1)) props[r] += intArrayOf(if (tall) COLUMN else RUIN_PILLAR, x, y, x + 10, y + 7) }; x += 24 }
        }
        place(r, ARCH, 34, 8, pad = 4, area = intArrayOf(20, 40, 220, 80)) || place(r, ARCH, 34, 8, pad = 4)
        place(r, RUIN_STATUE, 14, 9, pad = 6)
        repeat(2) { place(r, FALLEN_COLUMN, 30, 8, pad = 4) }
        repeat(2) { val w0 = 24 + rnd.nextInt(30); place(r, RUIN_WALL, w0, 8, pad = 6) }
        repeat(3) { place(r, ROCK, 14, 8) }
        repeat(2) { place(r, TREE, 12, 6) }
        props[r] += intArrayOf(PLAZA, 30, 64, 210, 140)
    }
    /**
     * The fort: a palisade round the whole block (gaps where the ways in are, a guard at each), and
     * inside a little village: the merchants' tents and stall round a fire, a well, carts, crates,
     * barrels, hay, benches, lanterns, flower beds, washing on a line; townsfolk going about.
     */
    private fun furnishFort(block: List<Int>) {
        val xs = block.map { it % w }; val ys = block.map { it / w }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val kinds = listOf(0, 1, 3, 0, 1, 3, 2).shuffled(rnd)
        val heart = fort
        for (r in block) {
            props[r].clear(); pools[r].clear(); shaped[r] = false; java.util.Arrays.fill(mass[r], 0)
            // the fort keeps its ways straight: in from each side to the middle
            ways[r].clear(); ways[r] += intArrayOf(92, 76, 148, 132)
            if (sides[r][0]) ways[r] += intArrayOf(0, 78, 120, 130); if (sides[r][1]) ways[r] += intArrayOf(120, 78, 240, 130)
            if (sides[r][2]) ways[r] += intArrayOf(96, 28, 144, 104); if (sides[r][3]) ways[r] += intArrayOf(96, 104, 144, 176)
            val sd = sides[r]; val cx = r % w; val cy = r / w
            fun wall(l: Int, t: Int, rr: Int, b: Int) { props[r] += intArrayOf(PALISADE, l, t, rr, b) }
            fun guard(x: Int, y: Int) { npcs += intArrayOf(r, x, y, NPC_GUARD, listOf(2, 1, 2, 3)[rnd.nextInt(4)]) }
            // the outer walls only: north, south, west, east, each with a gap (and a guard) where a way leads out
            if (cy == minY) { if (sd[2]) { wall(0, 34, 98, 42); wall(142, 34, 240, 42); guard(92, 56); guard(148, 56) } else wall(0, 34, 240, 42) }
            if (cy == maxY) { if (sd[3]) { wall(0, 162, 98, 170); wall(142, 162, 240, 170); guard(92, 156); guard(148, 156) } else wall(0, 162, 240, 170) }
            if (cx == minX) { if (sd[0]) { wall(0, 42, 8, 78); wall(0, 130, 8, 162); guard(22, 76); guard(22, 136) } else wall(0, 42, 8, 162) }
            if (cx == maxX) { if (sd[1]) { wall(232, 42, 240, 78); wall(232, 130, 240, 162); guard(218, 76); guard(218, 136) } else wall(232, 42, 240, 162) }
            if (r == heart) {
                // the market: two tents, the stall, the fire, and the folk who trade
                props[r] += intArrayOf(TENT, 24, 54, 58, 70); props[r] += intArrayOf(TENT, 182, 54, 216, 70)
                props[r] += intArrayOf(CAMPFIRE, 62, 140, 74, 146)
                props[r] += intArrayOf(STALL, 160, 140, 198, 148)
                props[r] += intArrayOf(LANTERN, 84, 62, 88, 66); props[r] += intArrayOf(LANTERN, 152, 62, 156, 66)
                place(r, BUILDING, 52, 14, pad = 8, area = intArrayOf(60, 38, 180, 74))
                npcs += intArrayOf(r, 179, 138, NPC_TOAD, 0)
                npcs += intArrayOf(r, 40, 150, NPC_MOTH, 0)
                npcs += intArrayOf(r, 66, 98, NPC_MERC, kinds[0]); npcs += intArrayOf(r, 176, 98, NPC_MERC, kinds[1])
                continue
            }
            // every other screen: a corner of the village, a house or two first
            repeat(1 + rnd.nextInt(2)) { place(r, BUILDING, 44 + rnd.nextInt(3) * 8, 14, pad = 10) }
            val pick = (r * 7 + rnd.nextInt(5)) % 4
            when (pick) {
                0 -> { place(r, WELL, 18, 10, pad = 8); repeat(2) { place(r, BENCH, 16, 5) }; repeat(3) { place(r, FLOWERBED, 20, 6, pad = 3) }; repeat(2) { place(r, LANTERN, 4, 4) } }
                1 -> { place(r, CART, 26, 10, pad = 6); repeat(3) { place(r, CRATES, 12, 8, pad = 2) }; repeat(4) { place(r, BARREL, 7, 6, pad = 2) }; repeat(2) { place(r, HAY, 14, 8, pad = 3) } }
                2 -> { place(r, TENT, 34, 16); repeat(2) { place(r, LAUNDRY, 3, 3, pad = 30) }; place(r, CAMPFIRE, 12, 6); repeat(2) { place(r, BENCH, 16, 5) }; place(r, BARREL, 7, 6) }
                else -> { repeat(4) { place(r, FLOWERBED, 20, 6, pad = 3) }; place(r, SIGNPOST, 6, 4); repeat(2) { place(r, HAY, 14, 8) }; place(r, TREE, 12, 6); repeat(2) { place(r, LANTERN, 4, 4) } }
            }
            if (rnd.nextFloat() < 0.5f) { place(r, CRATES, 12, 8); place(r, BARREL, 7, 6) }
            if (rnd.nextFloat() < 0.4f) npcs += intArrayOf(r, 120, 104, NPC_MERC, kinds[2 + rnd.nextInt(3)])
            // townsfolk, going about their business
            repeat(2 + rnd.nextInt(2)) {
                for (k in 0 until 20) {
                    val x = 30 + rnd.nextInt(180); val y = 56 + rnd.nextInt(96)
                    if (props[r].none { p -> x + 8 > p[1] && x - 8 < p[3] && y + 4 > p[2] && y - 8 < p[4] }) { npcs += intArrayOf(r, x, y, NPC_FOLK, rnd.nextInt(6)); break }
                }
            }
        }
        // a few folk in the market too
        repeat(2) { npcs += intArrayOf(heart, 100 + rnd.nextInt(40), 118 + rnd.nextInt(20), NPC_FOLK, rnd.nextInt(6)) }
    }
    /** The ways through a screen (always kept open; a river crossing one gets a bridge). */
    private fun lanes(r: Int): List<IntArray> = ways[r].ifEmpty { listOf(intArrayOf(96, 80, 144, 128)) }
    /** A river from a pond to another pond some five screens away, under the hedges between screens. */
    private fun river() {
        val bad = HashSet<Int>().apply { addAll(fortRooms) }
        val a = (0 until n).filter { it !in bad && rivers[it].isEmpty() }.let { if (it.isEmpty()) return else it[rnd.nextInt(it.size)] }
        // the grid's own neighbours (rivers don't need doors)
        val dist = IntArray(n) { -1 }; val par = IntArray(n) { -1 }; dist[a] = 0
        val q = ArrayDeque<Int>(); q += a
        while (q.isNotEmpty()) { val r = q.removeFirst(); for (d in (0 until 4).shuffled(rnd)) { val o = nb(r, d); if (o >= 0 && dist[o] < 0 && o !in bad && rivers[o].isEmpty()) { dist[o] = dist[r] + 1; par[o] = r; q += o } } }
        val far = (0 until n).filter { dist[it] in 4..6 }.ifEmpty { (0 until n).filter { dist[it] >= 2 } }
        if (far.isEmpty()) return
        val b = far[rnd.nextInt(far.size)]
        val path = ArrayList<Int>(); run { var r = b; while (r >= 0) { path.add(0, r); r = par[r] } }
        fun sideTo(r: Int, o: Int) = (0 until 4).first { nb(r, it) == o }
        for ((i, r) in path.withIndex()) {
            val ins = if (i > 0) sideTo(r, path[i - 1]) else -1
            val outs = if (i < path.size - 1) sideTo(r, path[i + 1]) else -1
            val segs = ArrayList<IntArray>()
            fun hseg(x0: Int, x1: Int) = intArrayOf(minOf(x0, x1), RIV_Y - RIV_HALF, maxOf(x0, x1), RIV_Y + RIV_HALF)
            fun vseg(y0: Int, y1: Int) = intArrayOf(RIV_X - RIV_HALF, minOf(y0, y1), RIV_X + RIV_HALF, maxOf(y0, y1))
            fun toCorner(sd: Int) = when (sd) { 0 -> hseg(0, RIV_X + RIV_HALF); 1 -> hseg(RIV_X - RIV_HALF, 240); 2 -> vseg(28, RIV_Y + RIV_HALF); else -> vseg(RIV_Y - RIV_HALF, 176) }
            if (ins >= 0 && outs >= 0 && setOf(ins, outs) == setOf(0, 1)) segs += hseg(0, 240)
            else if (ins >= 0 && outs >= 0 && setOf(ins, outs) == setOf(2, 3)) segs += vseg(28, 176)
            else { if (ins >= 0) segs += toCorner(ins); if (outs >= 0) segs += toCorner(outs) }
            // the ponds it runs between, in the corner
            if (i == 0 || i == path.size - 1) pondAt(r, 12, 130, 94, 172)
            // cut a bridge wherever it crosses a way through
            for (sg in segs) {
                var parts = listOf(sg)
                for (ln in lanes(r)) {
                    val next = ArrayList<IntArray>()
                    for (pp in parts) {
                        val hit = pp[2] > ln[0] && pp[0] < ln[2] && pp[3] > ln[1] && pp[1] < ln[3]
                        if (!hit) { next += pp; continue }
                        val horiz = pp[2] - pp[0] >= pp[3] - pp[1]
                        if (horiz) {
                            val bl = maxOf(pp[0], ln[0] - 2); val br = minOf(pp[2], ln[2] + 2)
                            bridges[r] += intArrayOf(bl, pp[1] - 2, br, pp[3] + 2)
                            if (bl > pp[0]) next += intArrayOf(pp[0], pp[1], bl, pp[3]); if (br < pp[2]) next += intArrayOf(br, pp[1], pp[2], pp[3])
                        } else {
                            val bt = maxOf(pp[1], ln[1] - 2); val bb = minOf(pp[3], ln[3] + 2)
                            bridges[r] += intArrayOf(pp[0] - 2, bt, pp[2] + 2, bb)
                            if (bt > pp[1]) next += intArrayOf(pp[0], pp[1], pp[2], bt); if (bb < pp[3]) next += intArrayOf(pp[0], bb, pp[2], pp[3])
                        }
                    }
                    parts = next
                }
                rivers[r] += parts
            }
            // nothing stands in the water
            props[r].removeAll { p -> p[0] != EXIT && (rivers[r] + pools[r]).any { w -> p[3] + 2 > w[0] && p[1] - 2 < w[2] && p[4] + 2 > w[1] && p[2] - 2 < w[3] } }
        }
    }

    /** The biggest lake that fits beside the ways through (it tries a few sizes and spots). */
    private fun lake(r: Int) {
        var best: IntArray? = null; var bestA = 0
        for (tries in 0 until 160) {
            val w0 = 70 + rnd.nextInt(160); val h0 = 36 + rnd.nextInt(76)
            if (w0 * h0 <= bestA) continue
            val l = 8 + rnd.nextInt(maxOf(1, 224 - w0)); val t = 36 + rnd.nextInt(maxOf(1, 132 - h0))
            if (l + w0 > 232 || t + h0 > 168) continue
            if (inCorridor(r, l - 3, t - 3, l + w0 + 3, t + h0 + 3)) continue
            if (!onOpen(r, l, t, l + w0, t + h0)) continue
            if (pools[r].any { l + w0 + 8 > it[0] && l - 8 < it[2] && t + h0 + 8 > it[1] && t - 8 < it[3] }) continue
            if (props[r].any { l + w0 > it[1] && l < it[3] && t + h0 > it[2] && t < it[4] }) continue
            best = intArrayOf(l, t, l + w0, t + h0); bestA = w0 * h0
        }
        best?.let { pondAt(r, it[0], it[1], it[2], it[3]) }
    }
    /**
     * A pond filling this box: a main oval and a few overlapping lobes pushing into its corners, so
     * the shore wanders like a real one. Each lobe is an oval inscribed in its own box (in [pools]).
     */
    private fun pondAt(r: Int, l: Int, t: Int, rr: Int, b: Int) {
        val w0 = rr - l; val h0 = b - t
        // never over a passage
        fun onWay(q: IntArray): Boolean { if (!shaped[r]) return ways[r].any { w -> q[2] > w[0] && q[0] < w[2] && q[3] > w[1] && q[1] < w[3] }
            val cr = core[r]; for (gy in (q[1] / CH).coerceIn(0, GY - 1)..((q[3] - 1) / CH).coerceIn(0, GY - 1)) for (gx in (q[0] / CW).coerceIn(0, GX - 1)..((q[2] - 1) / CW).coerceIn(0, GX - 1)) if (cr[gy * GX + gx]) return true; return false }
        val main = intArrayOf(l + w0 / 10, t + h0 / 10, rr - w0 / 10, b - h0 / 10)
        if (!onWay(main)) pools[r] += main
        repeat(3 + rnd.nextInt(3)) {
            val lw = (w0 * (0.35f + rnd.nextFloat() * 0.3f)).toInt(); val lh = (h0 * (0.4f + rnd.nextFloat() * 0.35f)).toInt()
            val lx = l + rnd.nextInt(maxOf(1, w0 - lw + 1)); val ly = t + rnd.nextInt(maxOf(1, h0 - lh + 1))
            val q = intArrayOf(lx, ly, lx + lw, ly + lh); if (!onWay(q)) pools[r] += q
        }
    }
    /** A narrow passage: thicket everywhere but a winding way, about 28 wide, between the open sides. */
    private fun passage(r: Int) {
        val s = sides[r]
        val bend = rnd.nextInt(-16, 17)
        // its ways, for keys and bridges
        ways[r].clear(); ways[r] += intArrayOf(108 + bend, 90, 132 + bend, 116)
        if (s[0]) { ways[r] += intArrayOf(0, 92, 60, 116); ways[r] += intArrayOf(58, 92 + bend / 2, 118 + bend, 116 + bend / 2) }
        if (s[1]) { ways[r] += intArrayOf(180, 92, 240, 116); ways[r] += intArrayOf(122 + bend, 92 - bend / 2, 182, 116 - bend / 2) }
        if (s[2]) { ways[r] += intArrayOf(108, 28, 132, 62); ways[r] += intArrayOf(108 + bend / 2, 62, 132 + bend, 98) }
        if (s[3]) { ways[r] += intArrayOf(108, 142, 132, 176); ways[r] += intArrayOf(108 + bend / 2, 106, 132 + bend, 142) }
        fun open(l: Int, t: Int, rr: Int, b: Int): Boolean {
            val m = 2
            fun hit(cl: Int, ct: Int, cr: Int, cb: Int) = rr + m > cl && l - m < cr && b + m > ct && t - m < cb
            if (hit(106 + bend, 88, 134 + bend, 118)) return true
            if (s[0] && (hit(0, 90, 60, 118) || hit(56, 90 + bend / 2, 120 + bend, 118 + bend / 2))) return true
            if (s[1] && (hit(180, 90, 240, 118) || hit(120 + bend, 90 - bend / 2, 184, 118 - bend / 2))) return true
            if (s[2] && (hit(106, 28, 134, 64) || hit(106 + bend / 2, 60, 134 + bend, 100))) return true
            if (s[3] && (hit(106, 140, 134, 176) || hit(106 + bend / 2, 104, 134 + bend, 144))) return true
            return false
        }
        val cell = 12
        for (y in 34 until 170 step cell) {
            var runStart = -1
            for (x in 4..236 step cell) {
                val solidHere = x < 232 && !open(x, y, x + cell, y + cell)
                if (solidHere && runStart < 0) runStart = x
                if ((!solidHere || x >= 232) && runStart >= 0) {
                    props[r] += intArrayOf(THICKET, runStart, y, minOf(x, 236), minOf(y + cell, 172)); runStart = -1
                }
            }
        }
    }

    /** A clear spot for a key: off the paths if possible, never in anything. */
    private fun clearSpot(r: Int): Pair<Int, Int> {
        val wet = rivers[r] + pools[r]
        fun clear(x: Int, y: Int) = onOpen(r, x - 7, y - 7, x + 7, y + 7) && props[r].none { p -> p[0] != PLAZA && x + 6 > p[1] && x - 6 < p[3] && y + 6 > p[2] && y - 6 < p[4] } && wet.none { p -> x + 7 > p[0] && x - 7 < p[2] && y + 7 > p[1] && y - 7 < p[3] }
        val ln = lanes(r)
        repeat(80) {
            val l = ln[rnd.nextInt(ln.size)]
            val x = l[0] + 10 + rnd.nextInt(maxOf(1, l[2] - l[0] - 20)); val y = maxOf(44, l[1] + 10) + rnd.nextInt(maxOf(1, minOf(166, l[3] - 10) - maxOf(44, l[1] + 10)))
            if (x in 14..226 && y in 44..166 && clear(x, y)) return x to y
        }
        repeat(60) {
            val x = 24 + rnd.nextInt(192); val y = 52 + rnd.nextInt(108)
            if (free(r, x - 6, y - 6, x + 6, y + 6, 4) || (it > 40 && onOpen(r, x - 7, y - 7, x + 7, y + 7) && props[r].none { p -> p[0] != PLAZA && x + 6 > p[1] && x - 6 < p[3] && y + 6 > p[2] && y - 6 < p[4] } && pools[r].none { p -> x + 6 > p[0] && x - 6 < p[2] && y + 6 > p[1] && y - 6 < p[3] })) return x to y
        }
        return 120 to 104
    }
}

/**
 * The beast at the heart of the wood: made fresh from a seed, never the same twice. A cluster of
 * swollen lobes (bark, moss, fungus, flesh or coral), wet and glossy; eyes scattered over it that
 * follow you and blink; tendrils that writhe; a mouth that opens to attack. Each one gets three or
 * four attacks from a pool: spore rings, aimed volleys, root eruptions, a lunge, a brood of imps,
 * and a tendril sweep. Every attack is telegraphed: it swells and its eyes flare first.
 */
internal class WildBeast(seed: Long, val depth: Int) {
    interface Host {
        fun hurt(n: Int)
        fun shoot(x: Float, y: Float, a: Float, speed: Float)
        fun summon(x: Float, y: Float)
        fun roots(x0: Float, y0: Float, x1: Float, y1: Float)
        fun shake(t: Float)
        fun solid(x: Float, y: Float): Boolean
        fun say(s: String)
        fun roar()
    }

    private val rnd = kotlin.random.Random(seed)
    var x = 120f; var y = 100f          // its feet
    val maxHp = 380 + depth * 40
    var hp = maxHp
    var hurtT = 0f
    var dead = false; var deathT = 0f

    private class Lobe(val dx: Float, val dy: Float, val r: Float, val ph: Float)
    private class Eye(val dx: Float, val dy: Float, val r: Float, val ph: Float, val slit: Boolean)
    private class Tendril(val a: Float, val len: Float, val thick: Float, val ph: Float, val back: Boolean)
    private val lobes = ArrayList<Lobe>()
    private val eyes = ArrayList<Eye>()
    private val tendrils = ArrayList<Tendril>()
    /** Skin: 0 bark, 1 moss, 2 fungus, 3 flesh, 4 coral. */
    val skin = rnd.nextInt(5)
    private val pal: IntArray
    private val eyeCol: Int
    private val accent: Int
    var name: String
    /** 0 spore ring, 1 aimed volley, 2 roots, 3 lunge, 4 brood, 5 sweep. */
    private val moves: List<Int>
    val size: Float

    init {
        // palettes: outline, dark, mid, light, gloss
        pal = when (skin) {
            0 -> intArrayOf(0xFF1E140C.toInt(), 0xFF4A3020.toInt(), 0xFF7A5434.toInt(), 0xFFA8805A.toInt(), 0xFFE0C8A0.toInt())
            1 -> intArrayOf(0xFF0E1E10.toInt(), 0xFF2A5A2A.toInt(), 0xFF4C8C3C.toInt(), 0xFF8CC060.toInt(), 0xFFD8F8B0.toInt())
            2 -> intArrayOf(0xFF20101C.toInt(), 0xFF6A3A5A.toInt(), 0xFFA0608A.toInt(), 0xFFD898B8.toInt(), 0xFFFFE8F4.toInt())
            3 -> intArrayOf(0xFF240A10.toInt(), 0xFF7A2A34.toInt(), 0xFFB8505A.toInt(), 0xFFE89090.toInt(), 0xFFFFE0E0.toInt())
            else -> intArrayOf(0xFF0A1820.toInt(), 0xFF1E5A6A.toInt(), 0xFF2E9AA0.toInt(), 0xFF78D8C8.toInt(), 0xFFE0FFF8.toInt())
        }
        eyeCol = listOf(0xFFF8E060.toInt(), 0xFFF06030.toInt(), 0xFF80F0F0.toInt(), 0xFFD060F0.toInt(), 0xFFB0F060.toInt())[rnd.nextInt(5)]
        accent = when (skin) { 0 -> 0xFF5AA040.toInt(); 1 -> 0xFFF0D060.toInt(); 2 -> 0xFFF8F0E0.toInt(); 3 -> 0xFF6A2050.toInt(); else -> 0xFFF0A0C0.toInt() }
        size = 0.9f + rnd.nextFloat() * 0.35f + minOf(depth, 30) * 0.004f
        // the body: a big core and a ring of smaller lobes leaning on it
        lobes += Lobe(0f, -20f * size, 17f * size, 0f)
        val nl = 3 + rnd.nextInt(4)
        for (i in 0 until nl) {
            val a = -Math.PI.toFloat() / 2 + (i - (nl - 1) / 2f) * (2.6f / nl) + (rnd.nextFloat() - 0.5f) * 0.4f
            val d = (12f + rnd.nextFloat() * 6f) * size
            lobes += Lobe(cos(a) * d * 1.2f, -20f * size + sin(a) * d * 0.9f, (7f + rnd.nextFloat() * 7f) * size, rnd.nextFloat() * 6f)
        }
        lobes += Lobe(-9f * size, -8f * size, 9f * size, 1f); lobes += Lobe(9f * size, -8f * size, 9f * size, 2f)
        lobes.sortBy { it.dy }
        // eyes, scattered over the lobes
        val ne = 1 + rnd.nextInt(7)
        for (i in 0 until ne) {
            val lb = lobes[rnd.nextInt(lobes.size)]
            val a = rnd.nextFloat() * 6.2832f; val d = lb.r * 0.4f * rnd.nextFloat()
            eyes += Eye(lb.dx + cos(a) * d, lb.dy + sin(a) * d * 0.6f, (if (i == 0) 4.5f else 2f + rnd.nextFloat() * 2.5f) * size, rnd.nextFloat() * 9f, rnd.nextBoolean())
        }
        val nt = 4 + rnd.nextInt(6)
        for (i in 0 until nt) {
            val a = rnd.nextFloat() * 6.2832f
            tendrils += Tendril(a, (16f + rnd.nextFloat() * 20f) * size, (2.5f + rnd.nextFloat() * 2f) * size, rnd.nextFloat() * 6f, sin(a) < 0f)
        }
        moves = (0..5).shuffled(rnd).take(3 + rnd.nextInt(2))
        val first = when (skin) { 0 -> listOf("OLD", "GNARLED", "HOLLOW"); 1 -> listOf("MOSS", "MIRE", "GREEN"); 2 -> listOf("SPORE", "BLIGHT", "PALE"); 3 -> listOf("RED", "SWOLLEN", "HUNGRY"); else -> listOf("BRINE", "REEF", "SOFT") }
        val last = listOf("MAW", "MOTHER", "HEART", "KNOT", "BLOOM", "WARDEN", "GRUB", "TANGLE")
        name = first[rnd.nextInt(first.size)] + " " + last[rnd.nextInt(last.size)]
    }

    // ---- behaviour
    private var st = 0           // 0 roam, 1 wind-up, 2 act, 3 recover
    val winding get() = st == 1
    private var stT = 2.5f
    private var move = 0
    private var vx = 0f; private var vy = 0f
    private var lungeX = 0f; private var lungeY = 0f
    private var volley = 0; private var volleyT = 0f
    private var open = 0f        // the mouth
    private var lookX = 0f; private var lookY = 0f
    var para = 0f; var weak = 0f; var conf = 0f
    val cy get() = y - 20f * size
    val radius get() = 22f * size
    private var swell = 0f

    fun update(dt: Float, px: Float, py: Float, h: Host) {
        hurtT -= dt; para -= dt; weak -= dt; conf -= dt
        if (dead) { deathT += dt; return }
        lookX = px; lookY = py
        open = (open - dt * 2f).coerceAtLeast(0f)
        swell = (swell - dt * 2f).coerceAtLeast(0f)
        if (para > 0f) return
        val enraged = hp < maxHp / 3
        stT -= dt * (if (enraged) 1.35f else 1f)
        val dx = px - x; val dy = py - y; val d = hypot(dx, dy).coerceAtLeast(0.01f)
        when (st) {
            0 -> {
                // lumber about, keeping a middling distance
                val want = if (d > 70f) 1f else if (d < 40f) -0.6f else 0.2f
                val sp = 18f * (if (enraged) 1.4f else 1f)
                var mx = dx / d * want; var my = dy / d * want
                if (conf > 0f) { mx = cos(stT * 3f); my = sin(stT * 2f) }
                val nx = x + mx * sp * dt; val ny = y + my * sp * dt
                if (!h.solid(nx, y)) x = nx
                if (!h.solid(x, ny)) y = ny
                // brushing up against it hurts
                if (d < radius * 0.8f && weak <= 0f) h.hurt(14)
                if (stT <= 0f) { st = 1; move = moves[rnd.nextInt(moves.size)]; stT = if (enraged) 0.6f else 0.9f; h.roar() }
            }
            1 -> { swell = 1f; if (stT <= 0f) { st = 2; act(px, py, h); stT = when (move) { 1 -> 1.2f; 3 -> 0.7f; else -> 0.5f } } }
            2 -> {
                open = 1f
                when (move) {
                    1 -> { volleyT -= dt; if (volleyT <= 0f && volley > 0) { volley--; volleyT = 0.18f; h.shoot(x, cy, atan2(py - cy, px - x) + (rnd.nextFloat() - 0.5f) * (if (weak > 0f) 1.4f else 0.25f), 100f) } }
                    3 -> {
                        val nx = x + lungeX * dt; val ny = y + lungeY * dt
                        if (!h.solid(nx, ny)) { x = nx; y = ny } else stT = 0f
                        if (d < radius && weak <= 0f) h.hurt(24)
                    }
                    5 -> if (d < radius + 26f && weak <= 0f) h.hurt(18)
                }
                if (stT <= 0f) { st = 3; stT = if (enraged) 0.8f else 1.3f }
            }
            else -> if (stT <= 0f) { st = 0; stT = (if (enraged) 1.6f else 2.6f) + rnd.nextFloat() * 1.5f }
        }
    }

    private fun act(px: Float, py: Float, h: Host) {
        when (move) {
            0 -> { val n = 10 + depth / 2; val off = rnd.nextFloat(); for (i in 0 until n) h.shoot(x, cy, (i + off) * 6.2832f / n, 62f); h.shake(0.15f) }
            1 -> { volley = 5 + depth / 3; volleyT = 0f }
            2 -> {
                // roots burst up toward you along three lines
                val a0 = atan2(py - y, px - x)
                for (k in -1..1) { val a = a0 + k * 0.45f; h.roots(x + cos(a) * 14f, y + sin(a) * 8f, x + cos(a) * 110f, y + sin(a) * 80f) }
                h.shake(0.2f)
            }
            3 -> { val a = atan2(py - y, px - x); lungeX = cos(a) * 150f; lungeY = sin(a) * 120f; h.shake(0.1f) }
            4 -> { repeat(2) { val a = rnd.nextFloat() * 6.2832f; h.summon(x + cos(a) * 30f, y + sin(a) * 16f) }; h.say("IT SHEDS A BROOD!") }
            else -> { h.shake(0.35f) }
        }
    }

    fun hurtBy(n: Int) { if (dead) return; hp -= n; hurtT = 0.3f; if (hp <= 0) { hp = 0; dead = true; deathT = 0f } }
    fun hits(px: Float, py: Float, r: Float) = hypot(px - x, (py - cy) * 1.2f) < radius + r

    // =====================================================================================
    // Drawing: tendrils behind, the lobes (each a shaded, glossy ball with its skin's texture),
    // tendrils in front, eyes, and the mouth.
    // =====================================================================================
    private fun shaded(c: Canvas, p: Paint, cx: Float, cy0: Float, r: Float, flash: Boolean, seed: Float) {
        if (r < 1f) return
        p.color = pal[0]; c.drawCircle(cx, cy0, r + 1f, p)
        p.color = if (flash) 0xFFFFFFFF.toInt() else pal[1]; c.drawCircle(cx, cy0, r, p)
        if (flash) return
        p.color = pal[2]; c.drawCircle(cx - r * 0.12f, cy0 - r * 0.14f, r * 0.84f, p)
        p.color = pal[3]; c.drawCircle(cx - r * 0.3f, cy0 - r * 0.34f, r * 0.48f, p)
        // texture, by skin
        val k = (seed * 97f).toInt()
        when (skin) {
            0 -> { p.color = pal[1]; for (i in 0 until (r / 3f).toInt()) { val ox = ((k * (i + 3)) % 17 - 8) / 8f * r * 0.7f; c.drawRect(cx + ox, cy0 - r * 0.5f + (i % 3) * 3, cx + ox + 1, cy0 + r * 0.4f, p) } }
            1 -> { for (i in 0 until 6) { val a = i * 1.1f + seed; p.color = if (i % 2 == 0) accent else pal[3]; c.drawRect(cx + cos(a) * r * 0.6f, cy0 - r * 0.6f + sin(a) * r * 0.25f, cx + cos(a) * r * 0.6f + 2, cy0 - r * 0.6f + sin(a) * r * 0.25f + 1, p) } }
            2 -> { for (i in 0 until 4) { val a = i * 1.7f + seed; p.color = accent; c.drawCircle(cx + cos(a) * r * 0.5f, cy0 + sin(a) * r * 0.45f, maxOf(1f, r * 0.13f), p) } }
            3 -> { p.color = pal[1]; for (i in 0 until 3) { val a = i * 2.1f + seed; c.drawLine(cx + cos(a) * r * 0.2f, cy0 + sin(a) * r * 0.2f, cx + cos(a + 0.5f) * r * 0.8f, cy0 + sin(a + 0.5f) * r * 0.7f, p) } }
            else -> { p.color = pal[1]; for (i in 0 until 5) { val a = i * 1.3f + seed; c.drawRect(cx + cos(a) * r * 0.55f, cy0 + sin(a) * r * 0.5f, cx + cos(a) * r * 0.55f + 1, cy0 + sin(a) * r * 0.5f + 1, p) } }
        }
        // the wet gloss
        p.color = pal[4]; c.drawCircle(cx - r * 0.38f, cy0 - r * 0.44f, maxOf(1f, r * 0.17f), p)
        p.color = 0xFFFFFFFF.toInt(); c.drawRect(cx - r * 0.45f, cy0 - r * 0.52f, cx - r * 0.45f + 1, cy0 - r * 0.52f + 1, p)
    }

    private fun tendril(c: Canvas, p: Paint, t: Tendril, time: Float, flash: Boolean) {
        val bx = x + cos(t.a) * 12f * size; val by = cy + sin(t.a) * 9f * size + 6f
        val reach = if (st == 2 && move == 5) 1.8f else 1f
        val segs = 9
        for (i in segs downTo 0) {
            val u = i / segs.toFloat()
            val wave = sin(time * 3f + t.ph + u * 4f) * 6f * u
            val a = t.a + wave * 0.05f
            val px = bx + cos(a) * t.len * u * reach + cos(a + 1.57f) * wave
            val py = by + sin(a) * t.len * u * 0.6f * reach + sin(a + 1.57f) * wave * 0.6f
            val r = t.thick * (1f - u * 0.7f)
            p.color = pal[0]; c.drawCircle(px, py, r + 1f, p)
            p.color = if (flash) 0xFFFFFFFF.toInt() else if (i % 2 == 0) pal[2] else pal[1]; c.drawCircle(px, py, r, p)
            if (!flash && r > 1.5f) { p.color = pal[3]; c.drawRect(px - r * 0.5f, py - r * 0.6f, px - r * 0.5f + 1, py - r * 0.6f + 1, p) }
            if (i == segs && !flash) { p.color = accent; c.drawCircle(px, py, maxOf(1.5f, r * 0.9f), p) }
        }
    }

    fun draw(c: Canvas, p: Paint, time: Float) {
        val flash = hurtT > 0f && (time * 20).toInt() % 2 == 0
        if (dead) {
            // it withers and sinks into the moss
            val k = (deathT / 2f).coerceIn(0f, 1f)
            c.save(); c.translate(x, y); c.scale(1f + k * 0.3f, 1f - k); c.translate(-x, -y)
        }
        // the shadow, and a dark stain of roots under it
        p.color = 0x50000000; c.drawOval(x - radius * 1.2f, y - 5f, x + radius * 1.2f, y + 5f, p)
        val breathe = 1f + 0.04f * sin(time * 2.2f) + 0.1f * swell
        for (t in tendrils) if (t.back) tendril(c, p, t, time, flash)
        for (lb in lobes) {
            val pul = 1f + 0.05f * sin(time * 2.6f + lb.ph)
            shaded(c, p, x + lb.dx * breathe, y + lb.dy * breathe, lb.r * pul * breathe, flash, lb.ph + lb.dx)
        }
        // veins of light pulsing over it when it winds up
        if (st == 1 && !flash) { p.color = eyeCol; for (lb in lobes) c.drawRect(x + lb.dx - 1, y + lb.dy - lb.r * 0.3f, x + lb.dx + 1, y + lb.dy + lb.r * 0.4f, p) }
        for (t in tendrils) if (!t.back) tendril(c, p, t, time, flash)
        // the mouth: a dark gash with teeth, gaping when it attacks
        val mo = open.coerceIn(0f, 1f)
        val mx = x; val my = y - 10f * size
        p.color = pal[0]; c.drawOval(mx - 8f * size, my - 1f - mo * 4f, mx + 8f * size, my + 2f + mo * 5f, p)
        if (mo > 0.1f) {
            p.color = 0xFF400818.toInt(); c.drawOval(mx - 7f * size, my - mo * 3f, mx + 7f * size, my + 1f + mo * 4f, p)
            p.color = 0xFFF8F0E0.toInt(); for (i in -3..3) { c.drawRect(mx + i * 2f * size, my - mo * 3f, mx + i * 2f * size + 1, my - mo * 3f + 2, p); c.drawRect(mx + i * 2f * size + 1, my + mo * 4f - 1, mx + i * 2f * size + 2, my + mo * 4f + 1, p) }
        }
        // the eyes, following you, blinking now and then
        for (e in eyes) {
            val ex = x + e.dx * breathe; val ey = y + e.dy * breathe
            val blink = ((time + e.ph) % 4.5f) < 0.15f || para > 0f
            p.color = pal[0]; c.drawCircle(ex, ey, e.r + 1f, p)
            if (blink) { p.color = pal[1]; c.drawCircle(ex, ey, e.r, p); p.color = pal[0]; c.drawRect(ex - e.r, ey, ex + e.r, ey + 1, p); continue }
            p.color = if (st == 1 || st == 2) 0xFFFFFFFF.toInt() else eyeCol; c.drawCircle(ex, ey, e.r, p)
            val a = atan2(lookY - ey, lookX - ex); val off = e.r * 0.35f
            p.color = 0xFF100808.toInt()
            if (e.slit) c.drawRect(ex + cos(a) * off - maxOf(0.5f, e.r * 0.18f), ey + sin(a) * off - e.r * 0.7f, ex + cos(a) * off + maxOf(0.5f, e.r * 0.18f), ey + sin(a) * off + e.r * 0.7f, p)
            else c.drawCircle(ex + cos(a) * off, ey + sin(a) * off, maxOf(1f, e.r * 0.45f), p)
            p.color = 0xFFFFFFFF.toInt(); c.drawRect(ex - e.r * 0.5f, ey - e.r * 0.55f, ex - e.r * 0.5f + 1, ey - e.r * 0.55f + 1, p)
        }
        // telegraph: what's coming
        if (st == 1) {
            p.color = 0x66E04848
            when (move) {
                3 -> { val a = atan2(lookY - y, lookX - x); for (i in 1..8) c.drawRect(x + cos(a) * i * 12f - 1, y + sin(a) * i * 9f - 1, x + cos(a) * i * 12f + 1, y + sin(a) * i * 9f + 1, p) }
                5 -> c.drawOval(x - radius - 26f, y - 14f, x + radius + 26f, y + 14f, p)
                else -> {}
            }
        }
        if (dead) c.restore()
    }

    /** Is a point within its bulk (for walking into it). */
    fun body(px: Float, py: Float) = abs(px - x) < radius * 0.9f && py > y - 8f && py < y + 4f
    @Suppress("unused") private fun len(a: Float, b: Float) = sqrt(a * a + b * b)
}
