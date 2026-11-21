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
 * The Wildwood: a forest of 9 x 8 screens (about ten times the great hall), made fresh for each
 * level from a seed. A maze of paths (a random spanning tree with a few extra loops) runs between
 * the screens. Gates on the way to the exit are locked; keys lie elsewhere, always where you can
 * reach them before the gate they open (key i sits behind i gates at most). The exit itself is an
 * old root door that takes a key too.
 */
internal class WildLevel(val depth: Int, seed: Long, val bossLevel: Boolean) {
    val w = 9; val h = 8; val n = w * h
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

    companion object {
        const val ROCK = 1; const val SHRUB = 2
        const val TREE = 9; const val STUMP = 10; const val LOG = 11; const val MUSHROOM = 12; const val EXIT = 13
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
        val keyCount = minOf(4, 2 + depth / 3)
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
            if (region[o] == region[r] && rnd.nextFloat() < 0.22f) open(r, d)
        }
        for (r in 0 until n) doors[r] = (0 until 4).filter { sides[r][it] }.joinToString("") { "WENS"[it].toString() }
        // 5. keys: key i somewhere in region i, far from the start, never on the start or exit
        val used = HashSet<Int>(); used += start; used += exit
        for (i in 0 until keyCount) {
            val reg = minOf(i, gateRooms.size)
            val cands = (0 until n).filter { region[it] == reg && it !in used }.ifEmpty { (0 until n).filter { region[it] <= reg && it !in used } }
            // prefer dead ends and far corners
            val score = cands.associateWith { dist[it] + (if (doors[it].length == 1) 6 else 0) + rnd.nextInt(6) }
            val best = cands.maxByOrNull { score.getValue(it) } ?: start
            used += best
            keys += intArrayOf(best, 0, 0)
        }
        // 6. scenery for each screen
        for (r in 0 until n) furnish(r)
        for (k in keys) { val spot = clearSpot(k[0]); k[1] = spot.first; k[2] = spot.second }
        for (r in 0 until n) names[r] = A[rnd.nextInt(A.size)] + " " + B[rnd.nextInt(B.size)]
        names[start] = "THE WOOD'S EDGE"; names[exit] = if (bossLevel) "THE HEART OF THE WOOD" else "THE ROOT DOOR"
    }

    /** The corridors from each open side to the middle, kept clear. */
    private fun inCorridor(r: Int, l: Int, t: Int, rr: Int, b: Int): Boolean {
        val s = sides[r]
        fun hit(cl: Int, ct: Int, cr: Int, cb: Int) = rr > cl && l < cr && b > ct && t < cb
        if (hit(92, 76, 148, 132)) return true                      // the middle
        if (s[0] && hit(0, 78, 120, 130)) return true
        if (s[1] && hit(120, 78, 240, 130)) return true
        if (s[2] && hit(96, 28, 144, 104)) return true
        if (s[3] && hit(96, 104, 144, 176)) return true
        return false
    }
    private fun free(r: Int, l: Int, t: Int, rr: Int, b: Int, pad: Int): Boolean {
        if (l < 8 || rr > 232 || t < 36 || b > 168) return false
        if (inCorridor(r, l, t, rr, b)) return false
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
            // the root door at the top; for the boss, an open arena
            props[r] += intArrayOf(EXIT, 104, 36, 136, 44)
            if (!bossLevel) { repeat(3) { place(r, TREE, 12, 6) }; repeat(2) { place(r, SHRUB, 16, 8) } }
            else { repeat(4) { place(r, MUSHROOM, 8, 5, area = intArrayOf(10, 130, 230, 166)) } }
            return
        }
        when (if (r == start) 1 else rnd.nextInt(7)) {
            0 -> { repeat(5 + rnd.nextInt(4)) { place(r, TREE, 12, 6) }; repeat(2) { place(r, SHRUB, 16, 8) } }       // a grove
            1 -> { repeat(2) { place(r, TREE, 12, 6) }; repeat(3) { place(r, SHRUB, 16, 8) }; repeat(2) { place(r, MUSHROOM, 8, 5) } }  // a glade
            2 -> {   // a pond
                val pl = 20 + rnd.nextInt(30); val pt = 44 + rnd.nextInt(20); val pw = 40 + rnd.nextInt(24); val ph = 18 + rnd.nextInt(10)
                val side = if (rnd.nextBoolean()) 0 else 1
                val x0 = if (side == 0) pl else 240 - pl - pw
                if (!inCorridor(r, x0, pt, x0 + pw, pt + ph)) pools[r] += intArrayOf(x0, pt, x0 + pw, pt + ph)
                repeat(3) { place(r, TREE, 12, 6) }; repeat(2) { place(r, SHRUB, 16, 8) }
            }
            3 -> { repeat(6) { place(r, MUSHROOM, 8, 5, pad = 3) }; repeat(3) { place(r, TREE, 12, 6) }; place(r, STUMP, 12, 6) }   // fungus
            4 -> { repeat(3) { place(r, ROCK, 14, 8) }; repeat(2) { place(r, STUMP, 12, 6) }; place(r, LOG, 28, 7); repeat(2) { place(r, TREE, 12, 6) } }  // old fall
            5 -> { repeat(6) { place(r, SHRUB, 16, 8, pad = 3) }; repeat(3) { place(r, TREE, 12, 6) } }   // thicket
            else -> { repeat(8 + rnd.nextInt(4)) { place(r, TREE, 12, 6, pad = 5) } }    // deep wood
        }
    }

    /** A clear spot for a key: off the paths if possible, never in anything. */
    private fun clearSpot(r: Int): Pair<Int, Int> {
        repeat(60) {
            val x = 24 + rnd.nextInt(192); val y = 52 + rnd.nextInt(108)
            if (free(r, x - 6, y - 6, x + 6, y + 6, 4) || (it > 40 && props[r].none { p -> x + 6 > p[1] && x - 6 < p[3] && y + 6 > p[2] && y - 6 < p[4] } && pools[r].none { p -> x + 6 > p[0] && x - 6 < p[2] && y + 6 > p[1] && y - 6 < p[3] })) return x to y
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
    val name: String
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
