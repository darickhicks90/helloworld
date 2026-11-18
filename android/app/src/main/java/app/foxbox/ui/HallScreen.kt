package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs

/**
 * The great hall: what you get for poking the eye. A handheld-colour-style
 * screen (160×144, scaled up in whole pixels) of six all-white rooms, walked
 * between through the doorways at their edges. The five-eyed creature on his
 * dais swears at you, then fights: he throws bolts, sends out his imps, and
 * casts runes (bind, venom, mirror) that you counter by tracing them on the XY
 * pad. The firefly follows the XY pad; L lays short-lived walls where it flies,
 * R arcs magic from it into nearby imps. It has nothing to do with the music.
 */
@SuppressLint("ViewConstructor")
class HallScreen(c: Context) : PixelView(c) {
    private companion object {
        const val W = 160; const val H = 144
        // the hall: whites and pale greys only
        const val WHITE = 0xFFF8F8F8.toInt(); const val PALE = 0xFFE0E0E8.toInt(); const val MID = 0xFFB8B8C8.toInt(); const val DARK = 0xFF707088.toInt(); const val INK = 0xFF181820.toInt()
        // the handheld's shell and keys
        // the handheld: the same grey console plastic and coloured keys as the app's parameter panels
        val SHELL = Snes.FACE; val SHELL_D = Snes.SHADE; val SHELL_L = Snes.HI
        const val BEZEL = 0xFF3A3A48.toInt(); const val KEY = 0xFF2A2A34.toInt()
        val RANT = listOf(
            "#@%&!! WHO POKED MY EYE?!",
            "YOU! YEAH, YOU! I SAW THAT. WITH ALL FIVE OF THEM.",
            "DO YOU KNOW HOW HARD IT IS TO BLINK FIVE @#\$% EYES?!",
            "I WAS JUST LYING IN THE GRASS. MINDING MY OWN @#\$%& BUSINESS.",
            "GET OUT OF MY HALL BEFORE I #@%& FORGET MY MANNERS!",
        )
        val MUTTER = listOf("#@%&!", "\$%@#!", "&*#@!!", "@#\$%...", "HMPH.")
    }

    private val screen = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val sc = Canvas(screen)
    private val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val font = Paint().apply { isAntiAlias = false; typeface = Px.head; textSize = 8f; color = INK }
    private val yeti: Bitmap? = runCatching { c.assets.open("images/hall_yeti.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    private val t0 = System.nanoTime()

    // ---- the traveller: original 16×16 sprite, 4 facings × 2 steps (side frames face left; right is mirrored)
    private val pal = mapOf('K' to INK, 'S' to 0xFFF0B888.toInt(), 'H' to 0xFF8A4A20.toInt(), 'C' to 0xFF38A048.toInt(), 'T' to 0xFF3868D0.toInt(), 'P' to 0xFF404050.toInt(), 'W' to WHITE)
    private fun spr(rows: List<String>) = Sprite(rows, pal, outline = null)
    private val headDown = listOf(
        ".....KKKKKK.....", "....KCCCCCCK....", "...KCCCCCCCCK...", "...KHHHHHHHHK...", "...KHSSSSSSHK...",
        "...KSKSSSSKSK...", "...KSSSSSSSSK...", "....KSSKKSSK....", "...KTTTTTTTTK...", "..KSKTTTTTTKSK..", "..KSKTTTTTTKSK..", "...KKTTTTTTKK...")
    private val headUp = listOf(
        ".....KKKKKK.....", "....KCCCCCCK....", "...KCCCCCCCCK...", "...KHHHHHHHHK...", "...KHHHHHHHHK...",
        "...KHHHHHHHHK...", "...KHHHHHHHHK...", "....KHHHHHHK....", "...KTTTTTTTTK...", "..KSKTTTTTTKSK..", "..KSKTTTTTTKSK..", "...KKTTTTTTKK...")
    private val headSide = listOf(
        "......KKKKK.....", ".....KCCCCCK....", "....KCCCCCCCK...", "....KCHHHHHHK...", "...KSSSHHHHHK...",
        "...KSKSSHHHHK...", "...KSSSSSHHHK...", "....KKSSSHHK....", ".....KTTTTTK....", ".....KTSSTTK....", ".....KTSSTTK....", ".....KTTTTTK....")
    private val legsStand = listOf("....KPPKKPPK....", "....KPPKKPPK....", "....KKK..KKK....", "................")
    private val legsStep = listOf("....KPPKKPPK....", "...KPPK..KPPK...", "...KKK....KKK...", "................")
    private val legsSide = listOf(".....KPPPPK.....", ".....KPPPPK.....", ".....KKKKKK.....", "................")
    private val legsSideStep = listOf(".....KPPKPPK....", "....KPPK.KPPK...", "....KKK...KKK...", "................")
    /** [facing][frame]: 0 down, 1 up, 2 left, 3 right (mirrored left). */
    private val hero = arrayOf(
        arrayOf(spr(headDown + legsStand), spr(headDown + legsStep)),
        arrayOf(spr(headUp + legsStand), spr(headUp + legsStep)),
        arrayOf(spr(headSide + legsSide), spr(headSide + legsSideStep)),
    )

    // ---- state
    private var hx = 72f; private var hy = 112f
    private var facing = 1; private var walkT = 0f
    private var dirX = 0; private var dirY = 0
    private var talking = true; private var line = 0; private var shown = 0f
    /** The XY pad flies the firefly; during a spell it's where you draw the counter-rune. */
    private var flyX = 120f; private var flyY = 60f; private var flyTX = 120f; private var flyTY = 60f
    /** Back to the music (set by the activity: Clibbins). */
    var onExit: (() -> Unit)? = null
    private var lastNs = System.nanoTime()

    init { animated = true; isFocusable = true }

    // =====================================================================================
    // The game: six white rooms, the creature on his dais with his army of imps, runes to
    // counter by drawing them, and the firefly (L: short-lived walls, R: arcing magic).
    // =====================================================================================

    private class Room(val name: String, val kind: Int, val blocks: List<RectF>)
    private val rooms = arrayOf(
        arrayOf(
            Room("WEST GALLERY", 1, listOf(RectF(30f, 62f, 44f, 78f), RectF(116f, 62f, 130f, 78f), RectF(30f, 102f, 44f, 118f), RectF(116f, 102f, 130f, 118f))),
            Room("THRONE ROOM", 0, emptyList()),
            Room("EAST GALLERY", 2, listOf(RectF(60f, 66f, 100f, 90f))),
        ),
        arrayOf(
            Room("LIBRARY", 3, listOf(RectF(34f, 70f, 64f, 82f), RectF(96f, 70f, 126f, 82f), RectF(34f, 104f, 64f, 116f), RectF(96f, 104f, 126f, 116f))),
            Room("ENTRY HALL", 4, listOf(RectF(38f, 60f, 50f, 72f), RectF(110f, 60f, 122f, 72f))),
            Room("CRYPT", 5, listOf(RectF(22f, 56f, 48f, 68f), RectF(112f, 56f, 138f, 68f), RectF(22f, 98f, 48f, 110f), RectF(112f, 98f, 138f, 110f), RectF(68f, 76f, 92f, 88f))),
        ),
    )
    private var rx = 1; private var ry = 0
    private val room get() = rooms[ry][rx]
    private val roomId get() = ry * 3 + rx
    private val throne get() = rx == 1 && ry == 0
    private var bannerT = 0f

    private enum class Phase { INTRO, FIGHT, WON, LOST }
    private var phase = Phase.INTRO

    // the traveller
    private val maxHp = 6
    private var hp = maxHp; private var invulnT = 0f
    private var paraT = 0f; private var poisonT = 0f; private var poisonTick = 0f; private var invertT = 0f
    private val inverted get() = invertT > 0f

    // the creature
    private val bossMax = 120
    private var bossHp = bossMax; private var bossHurtT = 0f
    private var bossShotT = 3f; private var spellT = 6f; private var summonT = 4f

    // spells: the rune he puts on your screen, and your attempt on the XY pad
    private val runeNames = listOf("BIND", "VENOM", "MIRROR")
    private val runes = listOf(
        listOf(0.5f to 0.06f, 0.94f to 0.9f, 0.06f to 0.9f, 0.5f to 0.06f),                        // a triangle: paralyses
        listOf(0.05f to 0.2f, 0.35f to 0.82f, 0.5f to 0.2f, 0.65f to 0.82f, 0.95f to 0.2f),         // a zig-zag: poisons
        listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f, 0.9f to 0.9f, 0.1f to 0.1f),               // an hourglass: inverts
    )
    private val runeClosed = booleanArrayOf(true, false, true)
    private var castRune = -1; private var castT = 0f
    private val stroke = ArrayList<FloatArray>()
    private var lastScore = -1f; private var scoreT = 0f
    private var msg = ""; private var msgT = 0f
    private val NEED = 0.62f

    // the imps, the shots, the firefly's walls and arcs
    private class Minion(var x: Float, var y: Float, val room: Int) { var hp = 2; var cd = 2f + kotlin.random.Random.nextFloat() * 2f; var hurtT = 0f; var step = 0f }
    private class Shot(var x: Float, var y: Float, val vx: Float, val vy: Float, val boss: Boolean) { var life = 4f }
    private class Wall(val x: Float, val y: Float, val room: Int) { var life = 2.2f }
    private class Arc(val x1: Float, val y1: Float, val x2: Float, val y2: Float) { var life = 0.25f }
    private val minions = ArrayList<Minion>()
    private val shots = ArrayList<Shot>()
    private val walls = ArrayList<Wall>()
    private val arcs = ArrayList<Arc>()
    private class Burst(val x: Float, val y: Float) { var life = 0.3f }
    private val bursts = ArrayList<Burst>()
    private val SPLASH = 14f
    // when the XY pad lets go, the firefly bumbles along after the traveller
    private var flyFreeT = 99f; private var bumA = 0f
    private var wallT = 0f; private var zapCd = 0f
    private val rnd = kotlin.random.Random(System.nanoTime())

    private fun hasDoor(d: Int) = when (d) { 0 -> rx > 0; 1 -> rx < 2; 2 -> ry > 0; else -> ry < 1 }   // W, E, N, S

    private fun rectHit(l: Float, t: Float, r: Float, b: Float): Boolean {
        if (throne) {
            if (r > dais.left + 4 && l < dais.right - 4 && t < dais.bottom - 2 && b > dais.top) return true
            if (pillars.any { r > it.left && l < it.right && t < it.bottom && b > it.bottom - 10 }) return true
        }
        return room.blocks.any { r > it.left && l < it.right && t < it.bottom && b > it.top }
    }

    private fun blocked(x: Float, y: Float): Boolean {
        val l = x + 3; val r = x + 13; val t = y + 10; val b = y + 15
        if (l < 4 && !(hasDoor(0) && t >= 70 && b <= 104)) return true
        if (r > W - 4 && !(hasDoor(1) && t >= 70 && b <= 104)) return true
        if (t < 30 && !(hasDoor(2) && l >= 64 && r <= 96)) return true
        if (b > H - 2 && !(hasDoor(3) && l >= 64 && r <= 96)) return true
        return rectHit(l, t, r, b)
    }

    private fun wallAt(x: Float, y: Float, rm: Int) = walls.any { it.room == rm && x > it.x - WALL && x < it.x + WALL && y > it.y - WALL && y < it.y + WALL }
    private val WALL = 5f   // half the width of a wall block

    private fun enterRoom(nx: Int, ny: Int, px: Float, py: Float) {
        rx = nx; ry = ny; hx = px; hy = py; bannerT = 1.8f
        shots.clear()
        if (castRune >= 0) { castRune = -1; stroke.clear(); spellT = 4f }
        val id = roomId
        if (!throne && phase == Phase.FIGHT && minions.count { it.room == id } < 2) repeat(2 + rnd.nextInt(2)) { spawnMinion(id, null) }
    }

    private fun spawnMinion(id: Int, at: Pair<Float, Float>?) {
        var x = at?.first ?: (20f + rnd.nextFloat() * 120f); var y = at?.second ?: (40f + rnd.nextFloat() * 90f)
        repeat(20) {
            if (at != null) return@repeat
            if (kotlin.math.hypot(x - (hx + 8), y - (hy + 9)) > 50f && !rectHit(x - 4, y - 4, x + 4, y + 4)) return@repeat
            x = 20f + rnd.nextFloat() * 120f; y = 40f + rnd.nextFloat() * 90f
        }
        minions += Minion(x, y, id)
    }

    private fun hurt(n: Int) {
        if (invulnT > 0f || phase != Phase.FIGHT) return
        hp -= n; invulnT = 1f
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (hp <= 0) { hp = 0; phase = Phase.LOST; castRune = -1; msg = ""; paraT = 0f; poisonT = 0f; invertT = 0f; stroke.clear() }
    }

    private fun restart() {
        hp = maxHp; invulnT = 0f; paraT = 0f; poisonT = 0f; invertT = 0f
        bossHp = bossMax; bossShotT = 3f; spellT = 6f; summonT = 4f; castRune = -1
        minions.clear(); shots.clear(); walls.clear(); arcs.clear(); stroke.clear()
        rx = 1; ry = 1; hx = 72f; hy = 100f; phase = Phase.FIGHT; bannerT = 1.8f
        msg = "FIGHT!"; msgT = 1.2f
    }

    private fun update(dt: Float) {
        // the firefly drifts after where the XY pad points, with a little wobble
        flyFreeT += dt
        val following = xyPointer < 0 && flyFreeT > 1.2f
        if (following) {
            // a lazy wandering orbit round the traveller's head, never quite still
            bumA += dt * 1.4f + (rnd.nextFloat() - 0.5f) * dt * 8f
            val tm = (System.nanoTime() - t0) / 1e9f
            val rad = 13f + 5f * kotlin.math.sin(tm * 0.8f)
            flyTX = (hx + 8 + kotlin.math.cos(bumA) * rad + kotlin.math.sin(tm * 2.3f) * 3f).coerceIn(4f, W - 4f)
            flyTY = (hy + 2 + kotlin.math.sin(bumA) * rad * 0.7f + kotlin.math.cos(tm * 1.9f) * 3f).coerceIn(12f, H - 4f)
        }
        val k = minOf(1f, dt * if (following) 2.2f else 4f)
        flyX += (flyTX - flyX) * k; flyY += (flyTY - flyY) * k
        if (talking) shown += dt * 28f
        bannerT -= dt; msgT -= dt; scoreT -= dt; bossHurtT -= dt; zapCd -= dt
        arcs.removeAll { it.life -= dt; it.life <= 0f }
        bursts.removeAll { it.life -= dt; it.life <= 0f }
        walls.removeAll { it.life -= dt; it.life <= 0f }
        val fighting = phase == Phase.FIGHT && !talking
        // moving (not while paralysed)
        // MIRROR flips the D-pad (and the XY pad, below)
        val ex = if (inverted) -dirX else dirX; val ey = if (inverted) -dirY else dirY
        if (paraT <= 0f && (ex != 0 || ey != 0) && phase != Phase.LOST) {
            facing = when { ey > 0 -> 0; ey < 0 -> 1; ex < 0 -> 2; else -> 3 }
            val sp = 46f * dt
            val nx = hx + ex * sp; val ny = hy + ey * sp
            if (!blocked(nx, hy)) hx = nx
            if (!blocked(hx, ny)) hy = ny
            walkT += dt
        } else walkT = 0f
        // through a doorway into the next room
        when {
            hx + 8 < 0 && hasDoor(0) -> enterRoom(rx - 1, ry, W - 14f, hy)
            hx + 8 > W && hasDoor(1) -> enterRoom(rx + 1, ry, -2f, hy)
            hy + 12 < 18 && hasDoor(2) -> enterRoom(rx, ry - 1, hx, H - 18f)
            hy + 15 > H + 4 && hasDoor(3) -> enterRoom(rx, ry + 1, hx, 22f)
        }
        if (!fighting) return
        invulnT -= dt; paraT -= dt; invertT -= dt
        if (poisonT > 0f) { poisonT -= dt; poisonTick -= dt; if (poisonTick <= 0f) { poisonTick = 1.5f; invulnT = 0f; hurt(1) } }
        val px = hx + 8; val py = hy + 9
        // L held: the firefly lays short-lived walls wherever it flies
        if (selDown) { wallT -= dt; if (wallT <= 0f) { wallT = 0.05f; if (walls.none { it.room == roomId && kotlin.math.abs(it.x - flyX) < 3f && kotlin.math.abs(it.y - flyY) < 3f } && walls.size < 90) walls += Wall(flyX, flyY, roomId) } }
        // the creature: ranged attacks, summons and spells (from his throne room)
        if (throne) {
            if (castRune < 0) {
                bossShotT -= dt
                if (bossShotT <= 0f) {
                    bossShotT = 2.1f + rnd.nextFloat() * 0.8f
                    val sx = 80f; val sy = 40f; val d = kotlin.math.hypot(px - sx, py - sy).coerceAtLeast(1f)
                    shots += Shot(sx, sy, (px - sx) / d * 58f, (py - sy) / d * 58f, true)
                }
                spellT -= dt
                if (spellT <= 0f) { castRune = rnd.nextInt(runes.size); castT = 7f; stroke.clear(); lastScore = -1f }
            } else {
                castT -= dt
                if (castT <= 0f) {
                    // too slow: the spell lands
                    when (castRune) { 0 -> { paraT = 3f; msg = "BOUND!" }; 1 -> { poisonT = 6f; poisonTick = 1.5f; msg = "POISONED!" }; else -> { invertT = 7f; msg = "MIRRORED!" } }
                    msgT = 1.4f; castRune = -1; spellT = 9f + rnd.nextFloat() * 3f
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
            summonT -= dt
            if (summonT <= 0f) {
                summonT = 9f
                if (minions.count { it.room == roomId } < 5) { spawnMinion(roomId, 50f to 94f); spawnMinion(roomId, 110f to 94f) }
            }
        }
        // the imps chase you, throw pebbles, and bump into walls
        for (m in minions) {
            if (m.room != roomId) continue
            m.hurtT -= dt
            val dx = px - m.x; val dy = py - m.y; val d = kotlin.math.hypot(dx, dy).coerceAtLeast(0.01f)
            if (d > 7f) {
                val nx = m.x + dx / d * 16f * dt; val ny = m.y + dy / d * 16f * dt
                if (!rectHit(nx - 3, m.y - 3, nx + 3, m.y + 3) && !wallAt(nx, m.y, roomId)) m.x = nx
                if (!rectHit(m.x - 3, ny - 3, m.x + 3, ny + 3) && !wallAt(m.x, ny, roomId)) m.y = ny
                m.step += dt
            } else hurt(1)
            m.cd -= dt
            if (m.cd <= 0f && d < 80f) { m.cd = 2.5f + rnd.nextFloat() * 2f; shots += Shot(m.x, m.y, dx / d * 46f, dy / d * 46f, false) }
        }
        // shots: walls stop them; they sting
        val it = shots.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.vx * dt; s.y += s.vy * dt; s.life -= dt
            if (s.life <= 0f || s.x < -4 || s.x > W + 4 || s.y < 0 || s.y > H + 4) { it.remove(); continue }
            if (wallAt(s.x, s.y, roomId)) { it.remove(); continue }
            if (kotlin.math.abs(s.x - px) < 5f && kotlin.math.abs(s.y - py) < 7f) { hurt(1); it.remove() }
        }
    }

    /** R: arcing magic from the firefly to up to three imps near it. */
    private fun zap() {
        if (phase != Phase.FIGHT || talking || zapCd > 0f) return
        zapCd = 0.45f
        var fromX = flyX; var fromY = flyY
        val hit = minions.filter { it.room == roomId && kotlin.math.hypot(it.x - flyX, it.y - flyY) < 32f }.sortedBy { kotlin.math.hypot(it.x - flyX, it.y - flyY) }.take(3)
        val struck = HashSet<Minion>()
        // every strike bursts: imps caught in the blast are hurt too
        fun burst(x: Float, y: Float) {
            bursts += Burst(x, y)
            for (m in minions) if (m.room == roomId && kotlin.math.hypot(m.x - x, m.y - y) < SPLASH && struck.add(m)) { m.hp--; m.hurtT = 0.3f }
        }
        burst(flyX, flyY)
        for (m in hit) { arcs += Arc(fromX, fromY, m.x, m.y); fromX = m.x; fromY = m.y; if (struck.add(m)) { m.hp--; m.hurtT = 0.3f }; burst(m.x, m.y) }
        minions.removeAll { it.hp <= 0 }
    }

    // ---- recognising the drawn rune
    private fun resample(pts: List<FloatArray>, n: Int): Array<FloatArray> {
        var total = 0f
        for (i in 1 until pts.size) total += kotlin.math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        val step = (total / (n - 1)).coerceAtLeast(1e-6f)
        val out = ArrayList<FloatArray>(); out += pts[0]
        var acc = 0f; var prev = pts[0]; var i = 1
        while (i < pts.size && out.size < n) {
            val cur = pts[i]; val seg = kotlin.math.hypot(cur[0] - prev[0], cur[1] - prev[1])
            if (acc + seg >= step && seg > 0f) {
                val t = (step - acc) / seg
                val q = floatArrayOf(prev[0] + (cur[0] - prev[0]) * t, prev[1] + (cur[1] - prev[1]) * t)
                out += q; prev = q; acc = 0f
            } else { acc += seg; prev = cur; i++ }
        }
        while (out.size < n) out += pts.last()
        return out.toTypedArray()
    }
    private fun normalise(a: Array<FloatArray>): Array<FloatArray> {
        val minX = a.minOf { it[0] }; val maxX = a.maxOf { it[0] }; val minY = a.minOf { it[1] }; val maxY = a.maxOf { it[1] }
        // each axis to its own size (a rune drawn wide on the pad still counts), but a near-flat axis isn't blown up
        val m = maxOf(maxX - minX, maxY - minY).coerceAtLeast(1e-3f)
        val sx = maxOf(maxX - minX, 0.3f * m); val sy = maxOf(maxY - minY, 0.3f * m)
        val cx = a.sumOf { it[0].toDouble() }.toFloat() / a.size; val cy = a.sumOf { it[1].toDouble() }.toFloat() / a.size
        return Array(a.size) { floatArrayOf((a[it][0] - cx) / sx, (a[it][1] - cy) / sy) }
    }
    /** 0..1: how much the stroke looks like the rune (either direction; any start point on closed runes). */
    private fun similarity(drawn: List<FloatArray>, rune: Int): Float {
        if (drawn.size < 6) return 0f
        val n = 40
        val tpl = normalise(resample(runes[rune].map { floatArrayOf(it.first, it.second) }, n))
        val st = normalise(resample(drawn, n))
        var best = Float.MAX_VALUE
        for (rev in 0..1) {
            val s = if (rev == 0) st else st.reversedArray()
            val shifts = if (runeClosed[rune]) n else 1
            for (k in 0 until shifts) {
                var d = 0f
                for (i in 0 until n) { val a = s[(i + k) % n]; val b = tpl[i]; d += kotlin.math.hypot(a[0] - b[0], a[1] - b[1]) }
                best = minOf(best, d / n)
            }
        }
        return (1f - best / 0.32f).coerceIn(0f, 1f)
    }

    private fun finishStroke() {
        if (castRune < 0 || stroke.size < 4) { stroke.clear(); return }
        val s = similarity(stroke, castRune)
        lastScore = s; scoreT = 1.4f
        if (s >= NEED) {
            bossHp -= 30; bossHurtT = 0.7f; msg = "COUNTERED!"; msgT = 1.4f
            castRune = -1; spellT = 9f + rnd.nextFloat() * 3f
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (bossHp <= 0) { bossHp = 0; phase = Phase.WON; minions.clear(); shots.clear(); paraT = 0f; poisonT = 0f; invertT = 0f }
        }
        stroke.clear()
    }

    // ---- the throne room's layout (screen pixels)
    private val dais = RectF(44f, 34f, 116f, 86f)
    private val pillars = listOf(RectF(12f, 30f, 28f, 130f), RectF(132f, 30f, 148f, 130f))

    // ---- drawing the little screen
    private fun box(l: Float, t: Float, r: Float, b: Float, col: Int) { p.color = col; sc.drawRect(l, t, r, b, p) }
    private val line1 = Paint().apply { isAntiAlias = false; strokeWidth = 1f; style = Paint.Style.STROKE }
    private val line2 = Paint().apply { isAntiAlias = false; strokeWidth = 2f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }

    private fun drawRoom(time: Float) {
        val crypt = room.kind == 5
        box(0f, 0f, W.toFloat(), H.toFloat(), if (crypt) PALE else WHITE)
        // back wall: big white blocks with pale mortar and a frieze
        box(0f, 0f, W.toFloat(), 30f, if (crypt) MID else PALE)
        for (row in 0 until 3) for (k in 0 until 11) {
            val off = if (row % 2 == 0) 0f else 8f
            box(k * 16f - off + 1, row * 9f + 1, k * 16f - off + 15, row * 9f + 8, if (crypt) PALE else WHITE)
        }
        box(0f, 27f, W.toFloat(), 30f, MID); box(0f, 30f, W.toFloat(), 31f, DARK)
        // floor tiles
        for (ty in 0 until 8) for (tx in 0 until 10) {
            val x0 = tx * 16f; val y0 = 31f + ty * 16f
            box(x0, y0, x0 + 16, y0 + 1, if (crypt) MID else PALE); box(x0, y0, x0 + 1, y0 + 16, if (crypt) MID else PALE)
            if ((tx + ty) % 2 == 0) box(x0 + 2, y0 + 2, x0 + 4, y0 + 4, if (crypt) MID else PALE)
        }
        // side walls and the bottom ledge, with gaps where the doors are
        for (side in 0..1) {
            val x0 = if (side == 0) 0f else W - 4f
            box(x0, 31f, x0 + 4, H.toFloat(), MID); box(if (side == 0) 3f else W - 4f, 31f, if (side == 0) 4f else W - 3f, H.toFloat(), DARK)
            if (hasDoor(side)) {
                box(x0, 68f, x0 + 4, 106f, if (crypt) PALE else WHITE)
                box(x0, 67f, x0 + 4, 68f, DARK); box(x0, 106f, x0 + 4, 107f, DARK)
                // a little arrow on the floor pointing out
                val ax = if (side == 0) 8f else W - 12f; val dir = if (side == 0) -1f else 1f
                if ((time * 2).toInt() % 2 == 0) for (k in 0 until 3) box(ax + dir * k, 86f - (2 - k), ax + dir * k + 1, 88f + (2 - k), MID)
            }
        }
        box(0f, H - 3f, W.toFloat(), H.toFloat(), MID)
        if (hasDoor(3)) { box(64f, H - 3f, 96f, H.toFloat(), if (crypt) PALE else WHITE) }
        if (hasDoor(2)) {
            // an archway in the back wall
            box(62f, 4f, 98f, 31f, DARK); box(64f, 6f, 96f, 31f, INK)
            box(66f, 8f, 94f, 31f, 0xFF303040.toInt())
            box(62f, 2f, 98f, 6f, MID)
        }
        when (room.kind) {
            0 -> {
                for (wx in floatArrayOf(36f, 116f)) { box(wx, 2f, wx + 8, 24f, MID); box(wx + 1, 3f, wx + 7, 24f, WHITE); box(wx + 3, 3f, wx + 5, 24f, PALE) }
                box(64f, 86f, 96f, H.toFloat(), PALE); box(66f, 86f, 94f, H.toFloat(), WHITE)
                for (y in 88 until H step 6) box(68f, y.toFloat(), 92f, y + 1f, PALE)
                box(dais.left, dais.bottom - 6, dais.right, dais.bottom, MID); box(dais.left, dais.bottom - 6, dais.right, dais.bottom - 5, DARK)
                box(dais.left + 4, dais.bottom - 12, dais.right - 4, dais.bottom - 6, PALE); box(dais.left + 4, dais.bottom - 12, dais.right - 4, dais.bottom - 11, MID)
                box(dais.left + 8, dais.top, dais.right - 8, dais.bottom - 12, WHITE)
                for (pl in pillars) {
                    box(pl.left - 2, 30f, pl.right + 2, 34f, MID); box(pl.left - 2, 30f, pl.right + 2, 31f, DARK)
                    box(pl.left, 34f, pl.right, pl.bottom - 4, WHITE)
                    box(pl.left, 34f, pl.left + 1, pl.bottom - 4, MID); box(pl.right - 1, 34f, pl.right, pl.bottom - 4, MID)
                    for (f in 1 until 4) box(pl.left + f * 4f, 36f, pl.left + f * 4f + 1, pl.bottom - 6, PALE)
                    box(pl.left - 2, pl.bottom - 4, pl.right + 2, pl.bottom, MID); box(pl.left - 2, pl.bottom - 1, pl.right + 2, pl.bottom, DARK)
                }
            }
            1 -> {
                // paintings on the wall, statues on plinths
                for ((k, px) in floatArrayOf(20f, 120f).withIndex()) {
                    box(px, 6f, px + 22, 24f, DARK); box(px + 2, 8f, px + 20, 22f, WHITE)
                    if (k == 0) { box(px + 4, 16f, px + 18, 20f, MID); box(px + 8, 11f, px + 12, 16f, MID) } else { box(px + 6, 10f, px + 16, 20f, PALE); box(px + 9, 13f, px + 11, 15f, INK); box(px + 13, 13f, px + 15, 15f, INK) }
                }
                for (b in room.blocks) {
                    box(b.left, b.top + 6, b.right, b.bottom, MID); box(b.left + 1, b.top + 7, b.right - 1, b.bottom - 1, PALE)
                    // a little bust
                    box(b.left + 4, b.top - 6, b.right - 4, b.top + 6, WHITE); box(b.left + 4, b.top - 6, b.left + 5, b.top + 6, MID)
                    box(b.left + 5, b.top - 2, b.left + 6, b.top - 1, DARK); box(b.right - 6, b.top - 2, b.right - 5, b.top - 1, DARK)
                }
            }
            2 -> {
                val b = room.blocks[0]
                box(b.left, b.top, b.right, b.bottom, MID); box(b.left + 2, b.top + 2, b.right - 2, b.bottom - 2, PALE)
                box(b.left + 4, b.top + 4, b.right - 4, b.bottom - 4, 0xFFD8E4F0.toInt())
                val ph = (time * 3).toInt() % 3
                for (k in 0 until 3) { val r = 3f + ((k + ph) % 3) * 4f; box(b.centerX() - r, b.centerY() - 1, b.centerX() + r, b.centerY(), WHITE) }
                box(b.centerX() - 2, b.top - 6, b.centerX() + 2, b.centerY(), WHITE); box(b.centerX() - 1, b.top - 10, b.centerX() + 1, b.top - 6, PALE)
                for (wx in floatArrayOf(20f, 132f)) { box(wx, 2f, wx + 8, 24f, MID); box(wx + 1, 3f, wx + 7, 24f, WHITE) }
            }
            3 -> {
                // shelves along the back wall
                for (sx in 0 until 2) {
                    val x0 = if (sx == 0) 8f else 102f
                    box(x0, 2f, x0 + 50, 30f, DARK)
                    for (shelf in 0 until 3) {
                        val y0 = 4f + shelf * 9f
                        for (k in 0 until 11) box(x0 + 2 + k * 4f, y0 + (k % 3), x0 + 5 + k * 4f, y0 + 8, if ((k + shelf) % 3 == 0) MID else if ((k + shelf) % 3 == 1) WHITE else PALE)
                    }
                }
                for (b in room.blocks) {
                    box(b.left, b.top, b.right, b.bottom, MID); box(b.left + 1, b.top + 1, b.right - 1, b.bottom - 3, WHITE)
                    box(b.left + 6, b.top + 3, b.left + 14, b.top + 7, PALE); box(b.right - 10, b.top + 2, b.right - 6, b.top + 6, DARK)
                }
            }
            4 -> {
                box(64f, 31f, 96f, H - 3f, PALE); box(66f, 31f, 94f, H - 3f, WHITE)
                for (y in 34 until H step 6) box(68f, y.toFloat(), 92f, y + 1f, PALE)
                for (b in room.blocks) {
                    // braziers with a little white flame
                    box(b.left, b.top + 4, b.right, b.bottom, MID); box(b.left + 2, b.top + 6, b.right - 2, b.bottom - 2, DARK)
                    val f = (time * 8).toInt() % 2
                    box(b.left + 3, b.top - 2 - f, b.right - 3, b.top + 4, WHITE); box(b.left + 5, b.top - 5 + f, b.right - 5, b.top, PALE)
                }
            }
            5 -> {
                for (b in room.blocks) {
                    box(b.left, b.top, b.right, b.bottom, DARK); box(b.left + 1, b.top + 1, b.right - 1, b.bottom - 3, MID)
                    box(b.left + 3, b.top + 3, b.right - 3, b.top + 5, PALE)
                }
                // cobwebs in the corners
                for (k in 0 until 6) { box(4f + k * 2, 31f, 5f + k * 2, 31f + (6 - k) * 2, MID); box(W - 5f - k * 2, 31f, W - 4f - k * 2, 31f + (6 - k) * 2, MID) }
            }
        }
    }

    private val src = Rect(); private val dst = RectF()
    private fun drawYeti(time: Float) {
        if (!throne) return
        val bmp = yeti ?: return
        val frame: Int; var dx = 0f; var dy = 0f
        when {
            phase == Phase.WON -> { frame = 1; dy = 2f }
            bossHurtT > 0f -> { frame = 5; dx = if ((time * 30).toInt() % 2 == 0) -2f else 2f; if ((time * 20).toInt() % 2 == 0) return }
            castRune >= 0 -> { frame = 3; dx = if ((time * 10).toInt() % 2 == 0) -1f else 1f }
            talking -> { val k = (time * 5).toInt(); frame = intArrayOf(3, 5, 3, 0)[k % 4]; dx = if (k % 2 == 0) -1f else 1f }
            else -> {
                val cycle = time % 7f
                frame = if (cycle > 5.6f) 4 else if (cycle > 5.2f) 1 else 0
                dy = if ((time * 1.6f).toInt() % 2 == 0) 0f else 1f
            }
        }
        val x = 52f + dx; val y = dais.top - 26f + dy
        box(x + 14, y + 54, x + 42, y + 56, PALE)
        src.set(frame * 56, 0, frame * 56 + 56, 56); dst.set(x, y, x + 56, y + 56)
        sc.drawBitmap(bmp, src, dst, p)
        if (phase == Phase.FIGHT && castRune < 0 && !talking && (time % 4f) < 1.2f) {
            val word = MUTTER[((time / 4f).toInt()) % MUTTER.size]
            val bw = word.length * 8f + 6; val bx = x + 40; val by = y + 2
            box(bx - 1, by - 1, bx + bw + 1, by + 12, INK); box(bx, by, bx + bw, by + 11, WHITE)
            sc.drawText(word, bx + 3, by + 9, font)
        }
    }

    private fun drawHero(time: Float) {
        if (invulnT > 0f && phase == Phase.FIGHT && (time * 16).toInt() % 2 == 0) return
        val moving = (dirX != 0 || dirY != 0) && paraT <= 0f
        val f = if (moving && (walkT * 6f).toInt() % 2 == 1) 1 else 0
        val s = hero[if (facing == 3) 2 else facing][f]
        val x = hx.toInt().toFloat(); val y = hy.toInt().toFloat()
        box(x + 4, y + 14, x + 12, y + 16, PALE)
        src.set(0, 0, s.w, s.h)
        if (facing == 3) { sc.save(); sc.scale(-1f, 1f, x + 8, y + 8); dst.set(x, y, x + 16, y + 16); sc.drawBitmap(s.bmp, src, dst, p); sc.restore() }
        else { dst.set(x, y, x + 16, y + 16); sc.drawBitmap(s.bmp, src, dst, p) }
        if (paraT > 0f) {
            // bound: purple rings of rune-light
            val c = 0xFF6048B0.toInt()
            box(x + 1, y + 6, x + 15, y + 7, c); box(x + 1, y + 11, x + 15, y + 12, c)
            if ((time * 6).toInt() % 2 == 0) box(x + 7, y - 4, x + 9, y - 2, c)
        }
        if (poisonT > 0f) {
            val c = 0xFF48A048.toInt(); val k = (time * 3) % 1f
            box(x + 3, y - 2 - k * 6, x + 5, y - k * 6, c); box(x + 11, y + 2 - ((k + 0.5f) % 1f) * 6, x + 13, y + 4 - ((k + 0.5f) % 1f) * 6, c)
        }
    }

    /** The imps: original little horned scamps, two frames of scurrying. */
    private fun drawMinions() {
        for (m in minions) {
            if (m.room != roomId) continue
            val x = m.x.toInt() - 4f; val y = m.y.toInt() - 4f
            val body = if (m.hurtT > 0f) WHITE else DARK
            val f = (m.step * 8).toInt() % 2
            box(x + 1, y + 7, x + 7, y + 8, PALE)                         // shadow
            box(x, y, x + 1, y + 2, INK); box(x + 7, y, x + 8, y + 2, INK) // horns
            box(x + 1, y + 1, x + 7, y + 6, INK); box(x + 2, y + 2, x + 6, y + 5, body)
            box(x + 2, y + 3, x + 3, y + 4, WHITE); box(x + 5, y + 3, x + 6, y + 4, WHITE)
            box(x + 1 + f, y + 6, x + 3 + f, y + 8, INK); box(x + 5 - f, y + 6, x + 7 - f, y + 8, INK)
        }
    }

    private fun drawShots(time: Float) {
        for (s in shots) {
            if (s.boss) {
                val r = if ((time * 12).toInt() % 2 == 0) 3f else 2f
                box(s.x - r - 1, s.y - r - 1, s.x + r + 1, s.y + r + 1, INK); box(s.x - r, s.y - r, s.x + r, s.y + r, 0xFF6048B0.toInt()); box(s.x - 1, s.y - 1, s.x, s.y, WHITE)
            } else { box(s.x - 2, s.y - 2, s.x + 2, s.y + 2, INK); box(s.x - 1, s.y - 1, s.x + 1, s.y + 1, MID) }
        }
    }

    private fun drawWalls(time: Float) {
        for (w in walls) {
            if (w.room != roomId) continue
            if (w.life < 0.5f && (time * 16).toInt() % 2 == 0) continue
            box(w.x - WALL, w.y - WALL, w.x + WALL, w.y + WALL, 0xFFB09020.toInt()); box(w.x - WALL + 1, w.y - WALL + 1, w.x + WALL - 1, w.y + WALL - 1, 0xFFF8E070.toInt())
        }
    }

    private fun drawArcs() {
        line1.color = 0xFFFFF27A.toInt()
        for (a in arcs) {
            var x0 = a.x1; var y0 = a.y1
            for (k in 1..5) {
                val t = k / 5f
                val jx = if (k < 5) rnd.nextFloat() * 6 - 3 else 0f; val jy = if (k < 5) rnd.nextFloat() * 6 - 3 else 0f
                val x1 = a.x1 + (a.x2 - a.x1) * t + jx
                val y1 = a.y1 + (a.y2 - a.y1) * t + jy - 6f * kotlin.math.sin(t * Math.PI.toFloat())
                sc.drawLine(x0, y0, x1, y1, line1); x0 = x1; y0 = y1
            }
            box(a.x2 - 2, a.y2 - 2, a.x2 + 2, a.y2 + 2, WHITE)
        }
        // the blasts: a ring of sparks opening out to the splash radius
        for (b in bursts) {
            val r = SPLASH * (1f - b.life / 0.3f).coerceIn(0.2f, 1f)
            for (k in 0 until 10) {
                val a = k * (Math.PI.toFloat() * 2f / 10f)
                val x = b.x + kotlin.math.cos(a) * r; val y = b.y + kotlin.math.sin(a) * r
                box(x - 1, y - 1, x + 1, y + 1, if (k % 2 == 0) 0xFFFFF27A.toInt() else WHITE)
            }
        }
    }

    /** The firefly the XY pad flies about. */
    private fun drawFly(time: Float) {
        val x = flyX + kotlin.math.sin(time * 9f) * 1.5f; val y = flyY + kotlin.math.cos(time * 7f) * 1.5f
        val glow = (time * 6f).toInt() % 2 == 0
        p.color = 0x55F0E060; sc.drawRect(x - 3, y - 3, x + 3, y + 3, p)
        p.color = if (glow) 0xFFFFF27A.toInt() else 0xFFE8D040.toInt(); sc.drawRect(x - 1, y - 1, x + 1, y + 1, p)
        if (selDown) { p.color = 0x88F8E070.toInt(); sc.drawRect(x - 4, y - 4, x + 4, y + 4, p) }
    }

    private fun heart(x: Float, y: Float, col: Int) {
        val rows = arrayOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...")
        for (r in rows.indices) for (k in 0 until 7) if (rows[r][k] == 'X') box(x + k, y + r, x + k + 1, y + r + 1, col)
    }

    private fun drawHud(time: Float) {
        if (phase == Phase.INTRO) return
        box(0f, 0f, W.toFloat(), 10f, INK)
        for (k in 0 until maxHp) heart(2f + k * 8f, 2f, if (k < hp) 0xFFE04848.toInt() else DARK)
        // status letters
        var sx = 54f
        fun tag(t: String, col: Int) { box(sx, 1f, sx + 9, 9f, col); font.color = WHITE; sc.drawText(t, sx + 1, 9f, font); font.color = INK; sx += 11 }
        if (paraT > 0f) tag("B", 0xFF6048B0.toInt())
        if (poisonT > 0f) tag("V", 0xFF48A048.toInt())
        if (invertT > 0f) tag("M", 0xFFD07020.toInt())
        // his health
        box(96f, 2f, 158f, 8f, DARK); box(97f, 3f, 97f + 60f * bossHp / bossMax, 7f, if (bossHurtT > 0f && (time * 20).toInt() % 2 == 0) WHITE else 0xFFB040C0.toInt())
    }

    /** The rune he puts on your screen: trace it on the XY pad before the bar runs out. */
    private fun drawRune(time: Float) {
        if (castRune < 0 || !throne) return
        val l = 44f; val t = 18f; val r = 116f; val b = 92f
        box(l - 1, t - 1, r + 1, b + 1, INK); box(l, t, r, b, WHITE); box(l + 1, t + 1, r - 1, b - 1, 0xFFF0ECF8.toInt())
        font.color = 0xFF6048B0.toInt(); sc.drawText(runeNames[castRune], l + (r - l - runeNames[castRune].length * 8) / 2, t + 10, font); font.color = INK
        val gl = l + 12; val gt = t + 14; val gs = 48f
        line2.color = if ((time * 4).toInt() % 2 == 0) 0xFF6048B0.toInt() else 0xFF8068D0.toInt()
        val pts = runes[castRune]
        for (k in 1 until pts.size) sc.drawLine(gl + pts[k - 1].first * gs, gt + pts[k - 1].second * gs, gl + pts[k].first * gs, gt + pts[k].second * gs, line2)
        // your stroke, as it maps from the pad
        if (stroke.size > 1 && xyRect.width() > 0) {
            line1.color = 0xFFE04848.toInt()
            fun mx(v: Float) = gl + ((v - xyRect.left) / xyRect.width()) * gs
            fun my(v: Float) = gt + ((v - xyRect.top) / xyRect.height()) * gs
            for (k in 1 until stroke.size) sc.drawLine(mx(stroke[k - 1][0]), my(stroke[k - 1][1]), mx(stroke[k][0]), my(stroke[k][1]), line1)
        }
        // the time left, and how close the last try was
        box(l + 4, b - 6, r - 4, b - 3, PALE); box(l + 4, b - 6, l + 4 + (r - l - 8) * (castT / 7f).coerceIn(0f, 1f), b - 3, 0xFF6048B0.toInt())
        if (scoreT > 0f && lastScore >= 0f) {
            val s = "${(lastScore * 100).toInt()}%"
            box(r - 30, t + 14, r - 2, t + 24, if (lastScore >= NEED) 0xFF48A048.toInt() else 0xFFE04848.toInt())
            font.color = WHITE; sc.drawText(s, r - 28, t + 23, font); font.color = INK
        }
    }

    private fun drawTalk(time: Float) {
        if (!talking) return
        box(2f, 100f, W - 2f, H - 2f, INK); box(4f, 102f, W - 4f, H - 4f, WHITE); box(6f, 104f, W - 6f, H - 6f, INK); box(7f, 105f, W - 7f, H - 7f, WHITE)
        val text = RANT[line]
        val n = minOf(text.length, shown.toInt())
        val lines = wrap(text.take(n), 18)
        lines.take(3).forEachIndexed { i, s -> sc.drawText(s, 11f, 116f + i * 10f, font) }
        if (n >= text.length && (time * 2).toInt() % 2 == 0) { box(W - 16f, H - 13f, W - 10f, H - 12f, INK); box(W - 15f, H - 12f, W - 11f, H - 11f, INK); box(W - 14f, H - 11f, W - 12f, H - 10f, INK) }
    }
    private fun wrap(s: String, cols: Int): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (word in s.split(' ')) {
            if (cur.isEmpty()) cur = word
            else if (cur.length + 1 + word.length <= cols) cur += " $word"
            else { out += cur; cur = word }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun banner(text: String, y: Float) {
        val bw = text.length * 8f + 8; val bx = (W - bw) / 2
        box(bx - 1, y - 1, bx + bw + 1, y + 13, INK); box(bx, y, bx + bw, y + 12, WHITE)
        sc.drawText(text, bx + 4, y + 10, font)
    }

    private fun drawOverlays(time: Float) {
        if (bannerT > 0f && castRune < 0) banner(room.name, 14f)
        if (msgT > 0f && msg.isNotEmpty()) banner(msg, 96f)
        if (phase == Phase.WON) { banner("HE YIELDS!", 50f); if ((time * 2).toInt() % 2 == 0) banner("A: AGAIN", 66f) }
        if (phase == Phase.LOST) { banner("YOU FELL...", 50f); if ((time * 2).toInt() % 2 == 0) banner("A: TRY AGAIN", 66f) }
    }

    // ---- the handheld around the screen, with the D-pad and A / B
    private val pad = RectF(); private val aKey = RectF(); private val bKey = RectF()
    private val shellP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val invertP = Paint().apply {
        isFilterBitmap = false
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
    }
    private val label = Px.text(13f, 0xFFE8E8F0.toInt(), headFont = true)

    override fun paint(c: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f); lastNs = now
        val time = (now - t0) / 1e9f
        update(dt)
        drawRoom(time); drawYeti(time)
        drawWalls(time)
        drawMinions()
        drawHero(time)
        drawShots(time)
        drawArcs()
        drawFly(time)
        drawHud(time)
        drawRune(time)
        drawOverlays(time)
        drawTalk(time)


        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(SHELL)
        shellP.color = SHELL_L; c.drawRect(0f, 0f, w, Px.dp(5f), shellP)
        shellP.color = SHELL_D; c.drawRect(0f, h - Px.dp(5f), w, h, shellP)
        // the four coloured lamps along the top, like the parameter panels
        for (k in 0 until 4) { shellP.color = Snes.INK; c.drawRect(w / 2 - Px.dp(40f) + k * Px.dp(22f), Px.dp(9f), w / 2 - Px.dp(28f) + k * Px.dp(22f), Px.dp(21f), shellP); shellP.color = Snes.ACCENTS[k]; c.drawRect(w / 2 - Px.dp(38.5f) + k * Px.dp(22f), Px.dp(10.5f), w / 2 - Px.dp(29.5f) + k * Px.dp(22f), Px.dp(19.5f), shellP) }
        // the screen, as large as whole pixels allow, with room for the XY pad underneath
        val scale = maxOf(1f, kotlin.math.floor(minOf(w * 0.5f / W, h * 0.6f / H)))
        val sw = W * scale; val sh = H * scale
        val sl = (w - sw) / 2; val st = Px.dp(30f)
        shellP.color = BEZEL; c.drawRoundRect(sl - Px.dp(16f), st - Px.dp(12f), sl + sw + Px.dp(16f), st + sh + Px.dp(12f), Px.dp(10f), Px.dp(10f), shellP)
        shellP.color = Snes.RED; c.drawCircle(sl - Px.dp(8f), st + sh * 0.3f, Px.dp(3f), shellP)
        src.set(0, 0, W, H); dst.set(sl, st, sl + sw, st + sh)
        // MIRROR: the whole screen turns to its negative
        c.drawBitmap(screen, src, dst, if (inverted && (invertT > 0.6f || (time * 10).toInt() % 2 == 0)) invertP else p)
        screenRect.set(sl, st, sl + sw, st + sh)

        // XY pad, centred under the screen
        val xt = st + sh + Px.dp(24f); val xb = h - Px.dp(14f)
        val xw = minOf(sw * 0.62f, (xb - xt) * 1.8f)
        xyRect.set(w / 2 - xw / 2, xt, w / 2 + xw / 2, xb)
        if (xyRect.height() > Px.dp(30f)) {
            shellP.color = Snes.INK; c.drawRect(xyRect.left - Px.dp(2f), xyRect.top - Px.dp(2f), xyRect.right + Px.dp(2f), xyRect.bottom + Px.dp(2f), shellP)
            shellP.color = 0xFF2A2B40.toInt(); c.drawRect(xyRect, shellP)
            shellP.color = 0x22FFFFFF
            for (k in 1 until 6) { val fx = xyRect.left + xyRect.width() * k / 6f; c.drawRect(fx, xyRect.top, fx + 1, xyRect.bottom, shellP) }
            for (k in 1 until 3) { val fy = xyRect.top + xyRect.height() * k / 3f; c.drawRect(xyRect.left, fy, xyRect.right, fy + 1, shellP) }
            if (castRune >= 0 && throne) {
                // drawing a counter-rune: the pad shows your stroke
                shellP.color = Snes.RED
                for (k in 1 until stroke.size) c.drawCircle(stroke[k][0], stroke[k][1], Px.dp(3f), shellP)
                label.textSize = Px.dp(10f); label.color = 0x88FFFFFF.toInt()
                c.textCentered("DRAW THE RUNE", xyRect.centerX(), xyRect.top + Px.dp(12f), label)
            } else {
                var fx = (flyTX - 4f) / (W - 8f); var fy = (flyTY - 4f) / (H - 8f)
                if (inverted) { fx = 1f - fx; fy = 1f - fy }
                val dx = xyRect.left + xyRect.width() * fx; val dy = xyRect.top + xyRect.height() * fy
                shellP.color = Snes.YELLOW; c.drawCircle(dx, dy, Px.dp(6f), shellP)
                shellP.color = 0xFFFFFFFF.toInt(); c.drawCircle(dx - Px.dp(2f), dy - Px.dp(2f), Px.dp(2f), shellP)
            }
        }

        // back to the music: top left
        exitRect.set(Px.dp(10f), Px.dp(8f), Px.dp(116f), Px.dp(40f))
        pill(c, exitRect, "◀ Music", Snes.GREEN, exitDown)

        // D-pad, left, with the L button above it
        val ds = minOf(Px.dp(150f), sl - Px.dp(40f)).coerceAtLeast(Px.dp(96f))
        val dcx = sl / 2; val dcy = h * 0.62f
        pad.set(dcx - ds / 2, dcy - ds / 2, dcx + ds / 2, dcy + ds / 2)
        selRect.set(dcx - Px.dp(46f), pad.top - Px.dp(58f), dcx + Px.dp(46f), pad.top - Px.dp(28f))
        pill(c, selRect, "L  WALL", Snes.PURPLE, selDown)
        val arm = ds / 3
        shellP.color = SHELL_D; c.drawCircle(dcx, dcy, ds * 0.62f, shellP)
        shellP.color = KEY
        c.drawRoundRect(dcx - arm / 2, dcy - ds / 2, dcx + arm / 2, dcy + ds / 2, Px.dp(5f), Px.dp(5f), shellP)
        c.drawRoundRect(dcx - ds / 2, dcy - arm / 2, dcx + ds / 2, dcy + arm / 2, Px.dp(5f), Px.dp(5f), shellP)
        val tri = arm * 0.22f
        for ((ax, ay) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
            val lit = (ax.toInt() == dirX && ay.toInt() == dirY && (dirX != 0 || dirY != 0))
            shellP.color = if (lit) 0xFF8A8A98.toInt() else 0xFF4A4A56.toInt()
            c.drawCircle(dcx + ax * ds * 0.36f, dcy + ay * ds * 0.36f, tri, shellP)
        }
        // A and B, right, on a slant, with the R button above them
        val r = minOf(Px.dp(34f), (w - sl - sw) / 6f).coerceAtLeast(Px.dp(24f))
        val rcx = sl + sw + (w - sl - sw) / 2
        aKey.set(rcx + r * 0.3f, h * 0.55f - r, rcx + r * 2.3f, h * 0.55f + r)
        bKey.set(rcx - r * 2.1f, h * 0.69f - r, rcx - r * 0.1f, h * 0.69f + r)
        startRect.set(rcx - Px.dp(46f), aKey.top - Px.dp(58f), rcx + Px.dp(46f), aKey.top - Px.dp(28f))
        pill(c, startRect, "R  ZAP", Snes.GREEN, startDown)
        shellP.color = SHELL_D; c.drawRoundRect(bKey.left - r * 0.4f, aKey.top - r * 0.3f, aKey.right + r * 0.4f, bKey.bottom + r * 0.3f, r, r, shellP)
        for ((k, s) in listOf(aKey to "A", bKey to "B")) {
            val col = if (s == "A") Snes.RED else Snes.YELLOW
            val down = (s == "A" && aDown) || (s == "B" && bDown)
            shellP.color = Snes.INK; c.drawCircle(k.centerX() + Px.dp(2f), k.centerY() + Px.dp(3f), r, shellP)
            shellP.color = Snes.INK; c.drawCircle(k.centerX(), k.centerY(), r, shellP)
            shellP.color = if (down) darker(col) else col; c.drawCircle(k.centerX(), k.centerY(), r - Px.dp(2f), shellP)
            shellP.color = 0x55FFFFFF; c.drawCircle(k.centerX() - r * 0.3f, k.centerY() - r * 0.3f, r * 0.28f, shellP)
            label.textSize = Px.dp(13f); label.color = textOn(col)
            c.textCentered(s, k.centerX(), k.centerY(), label)
        }
    }

    private val screenRect = RectF(); private val xyRect = RectF(); private val exitRect = RectF(); private val selRect = RectF(); private val startRect = RectF()
    private var exitDown = false; private var selDown = false; private var startDown = false
    private fun darker(c: Int): Int { val r = (c shr 16 and 255) * 3 / 4; val g = (c shr 8 and 255) * 3 / 4; val b = (c and 255) * 3 / 4; return (0xFF shl 24) or (r shl 16) or (g shl 8) or b }

    /** A small console key with a coloured face and an ink outline. */
    private fun pill(c: Canvas, r: RectF, text: String, col: Int, down: Boolean) {
        val rad = r.height() / 2
        shellP.color = Snes.INK; c.drawRoundRect(r.left, r.top, r.right, r.bottom + Px.dp(2f), rad, rad, shellP)
        shellP.color = if (down) darker(col) else col; c.drawRoundRect(r.left + Px.dp(2f), r.top + Px.dp(2f), r.right - Px.dp(2f), r.bottom - Px.dp(1f), rad, rad, shellP)
        label.textSize = Px.dp(if (text.length <= 2) 13f else 10f); label.color = textOn(col)
        c.textCentered(text, r.centerX(), r.centerY(), label)
    }

    // ---- touch: the D-pad holds a direction; A and B are taps
    private var padPointer = -1
    private var xyPointer = -1
    private fun aimFly(x: Float, y: Float) {
        var fx = ((x - xyRect.left) / xyRect.width()).coerceIn(0f, 1f); var fy = ((y - xyRect.top) / xyRect.height()).coerceIn(0f, 1f)
        if (castRune >= 0 && throne) {
            // tracing the rune (mirrored too, if he's mirrored you)
            if (inverted) { fx = 1f - fx; fy = 1f - fy }
            val px = xyRect.left + fx * xyRect.width(); val py = xyRect.top + fy * xyRect.height()
            val last = stroke.lastOrNull()
            if (last == null || kotlin.math.hypot(px - last[0], py - last[1]) > 2f) stroke += floatArrayOf(px, py)
            return
        }
        if (inverted) { fx = 1f - fx; fy = 1f - fy }
        flyFreeT = 0f
        flyTX = 4f + fx * (W - 8f)
        flyTY = 4f + fy * (H - 8f)
    }
    private var aDown = false; private var bDown = false

    private fun near(k: RectF, x: Float, y: Float) = abs(x - k.centerX()) < k.width() * 0.75f && abs(y - k.centerY()) < k.height() * 0.75f

    private fun steer(x: Float, y: Float) {
        val dx = x - pad.centerX(); val dy = y - pad.centerY()
        if (abs(dx) < pad.width() * 0.08f && abs(dy) < pad.height() * 0.08f) { dirX = 0; dirY = 0; return }
        if (abs(dx) > abs(dy)) { dirX = if (dx > 0) 1 else -1; dirY = 0 } else { dirY = if (dy > 0) 1 else -1; dirX = 0 }
    }

    private fun pressA() {
        if (talking) {
            if (shown < RANT[line].length) shown = RANT[line].length.toFloat()
            else if (line < RANT.size - 1) { line++; shown = 0f } else endTalk()
        } else if (phase == Phase.WON || phase == Phase.LOST) restart()
    }
    private fun pressB() { if (talking) endTalk() }
    /** When he's done shouting, the fight starts. */
    private fun endTalk() {
        talking = false
        if (phase == Phase.INTRO) { phase = Phase.FIGHT; msg = "FIGHT!"; msgT = 1.4f; spellT = 6f; bossShotT = 2f; summonT = 1f }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i); val y = e.getY(i)
                when {
                    exitRect.contains(x, y) -> { exitDown = true; performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                    selRect.contains(x, y) -> { selDown = true; wallT = 0f; performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                    startRect.contains(x, y) -> { startDown = true; zap(); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                    xyRect.contains(x, y) -> { xyPointer = e.getPointerId(i); stroke.clear(); aimFly(x, y) }
                    x < pad.right + pad.width() * 0.3f && y > pad.top - pad.height() * 0.3f && y < pad.bottom + pad.height() * 0.3f -> { padPointer = e.getPointerId(i); steer(x, y) }
                    near(aKey, x, y) -> { aDown = true; pressA(); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                    near(bKey, x, y) -> { bDown = true; pressB(); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val k = e.findPointerIndex(padPointer)
                if (k >= 0) steer(e.getX(k), e.getY(k))
                val q = e.findPointerIndex(xyPointer)
                if (q >= 0) aimFly(e.getX(q), e.getY(q))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(i) == padPointer) { padPointer = -1; dirX = 0; dirY = 0 }
                else if (e.getPointerId(i) == xyPointer) { xyPointer = -1; finishStroke() }
                else { aDown = false; bDown = false; selDown = false; startDown = false }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (exitDown && e.actionMasked == MotionEvent.ACTION_UP && exitRect.contains(e.x, e.y)) onExit?.invoke()
                if (xyPointer >= 0) finishStroke()
                padPointer = -1; xyPointer = -1; dirX = 0; dirY = 0; aDown = false; bDown = false; exitDown = false; selDown = false; startDown = false
            }
        }
        return true
    }
}
