package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The great hall: what you get for poking the eye. A handheld-colour-style
 * screen (240×176, scaled up) of seven white rooms, walked between through the
 * doorways at their edges, with pools you can't wade (shots fly over them) and
 * rocks, shrubs and pillars to hide behind.
 *
 * The five-eyed creature on his dais swears at you, then fights: bolts, his imps,
 * and runes (bind, venom, mirror) you counter by tracing them on the XY pad. At
 * his last 100 he slams the back wall (it cracks), comes down off the dais and
 * chases you, now and then casting the mirror. Beat him and the crack leads to
 * his treasury.
 *
 * The firefly follows the XY pad (and bumbles after you when it's let go).
 * Vital Spark (R): magic arcs from it into imps (and him). Consecrate (hold L):
 * the firefly draws a holy line that stands for 10 seconds and stops imps,
 * him and every shot; close it round an enemy and it is cursed.
 */
@SuppressLint("ViewConstructor")
class HallScreen(c: Context) : PixelView(c) {
    private companion object {
        const val W = 240; const val H = 176
        /** Everything moves at nine tenths of the first version's pace. */
        const val SLOW = 0.9f
        const val WHITE = 0xFFF8F8F8.toInt(); const val PALE = 0xFFE0E0E8.toInt(); const val MID = 0xFFB8B8C8.toInt(); const val DARK = 0xFF707088.toInt(); const val INK = 0xFF181820.toInt()
        const val WATER = 0xFFC4DCF0.toInt(); const val WATER_D = 0xFF8CA8C8.toInt()
        const val LEAF = 0xFFBCD8AC.toInt(); const val LEAF_D = 0xFF7C9C74.toInt()
        const val GOLD = 0xFFF0CC50.toInt(); const val GOLD_D = 0xFFA88420.toInt()
        const val RUNE = 0xFF6048B0.toInt(); const val HOLY = 0xFFF8E070.toInt(); const val HOLY_D = 0xFFB09020.toInt()
        val SHELL = Snes.FACE; val SHELL_D = Snes.SHADE; val SHELL_L = Snes.HI
        const val BEZEL = 0xFF3A3A48.toInt(); const val KEY = 0xFF2A2A34.toInt()
        val RANT = listOf(
            "#@%&!! WHO POKED MY EYE?!",
            "YOU! YEAH, YOU! I SAW THAT. WITH ALL FIVE OF THEM.",
            "DO YOU KNOW HOW HARD IT IS TO BLINK FIVE @#\$% EYES?!",
            "I WAS JUST LYING IN THE GRASS. MINDING MY OWN @#\$%& BUSINESS.",
            "GET OUT OF MY HALL BEFORE I #@%& FORGET MY MANNERS!",
        )
        val DEFEAT = listOf(
            "#@%&... FINE. FINE! YOU WIN.",
            "AND DON'T GO THROUGH THAT CRACK. THERE'S NOTHING IN THERE. NOTHING!",
        )
        val CHEST_LINES = listOf(
            "INSIDE: GOLD, GEMS, AND A FOLDED NOTE...",
            "IT SAYS: 'STOP POKING MY EYE.'",
        )
        val MUTTER = listOf("#@%&!", "\$%@#!", "&*#@!!", "@#\$%...", "HMPH.")
        // props: [kind] → how tall it stands above its footprint (shots hit the whole height)
        const val PILLAR = 0; const val ROCK = 1; const val SHRUB = 2; const val STATUE = 3; const val FOUNTAIN = 4
        const val TABLE = 5; const val BRAZIER = 6; const val TOMB = 7; const val CHEST = 8
        fun propH(k: Int) = when (k) { PILLAR -> 70f; ROCK -> 12f; SHRUB -> 14f; STATUE -> 28f; FOUNTAIN -> 18f; TABLE -> 8f; BRAZIER -> 14f; TOMB -> 14f; else -> 12f }
        const val BOSS_MAX = 400; const val LAST = 100
    }

    private val screen = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val sc = Canvas(screen)
    private val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val font = Paint().apply { isAntiAlias = false; typeface = Px.head; textSize = 8f; color = INK }
    private val yeti: Bitmap? = runCatching { c.assets.open("images/hall_yeti.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    private val t0 = System.nanoTime()
    private val rnd = kotlin.random.Random(System.nanoTime())

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
    private var hx = 112f; private var hy = 136f
    private var facing = 1; private var walkT = 0f
    private var dirX = 0; private var dirY = 0
    private var talking = true; private var line = 0; private var shown = 0f
    private var talkLines = RANT
    /** The XY pad flies the firefly; during a spell it's where you trace the counter-rune. */
    private var flyX = 180f; private var flyY = 80f; private var flyTX = 180f; private var flyTY = 80f
    private var flyFreeT = 99f; private var bumA = 0f
    /** Back to the music (set by the activity: Clibbins). */
    var onExit: (() -> Unit)? = null
    private var lastNs = System.nanoTime()

    init { animated = true; isFocusable = true }

    // =====================================================================================
    // The rooms. Doors sit mid-wall: sides at y 84..120, top and bottom at x 104..136.
    // =====================================================================================

    private class Prop(val kind: Int, val foot: RectF) { val body = RectF(foot.left, foot.bottom - propH(kind), foot.right, foot.bottom) }
    private class Room(val name: String, val kind: Int, val doors: String, val props: List<Prop>, val pools: List<RectF>)
    private fun pr(k: Int, l: Int, t: Int, r: Int, b: Int) = Prop(k, RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()))
    private fun pool(l: Int, t: Int, r: Int, b: Int) = RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())

    /** Keyed (row + 1) * 3 + column: the treasury sits above the throne room, behind the crack. */
    private val rooms = mapOf(
        1 to Room("TREASURY", 6, "S", listOf(pr(PILLAR, 44, 72, 60, 80), pr(PILLAR, 180, 72, 196, 80), pr(CHEST, 108, 62, 132, 72)), emptyList()),
        3 to Room("WEST GALLERY", 1, "ES",
            listOf(pr(STATUE, 36, 60, 52, 70), pr(STATUE, 84, 60, 100, 70), pr(STATUE, 140, 60, 156, 70), pr(STATUE, 188, 60, 204, 70),
                pr(SHRUB, 40, 132, 56, 140), pr(SHRUB, 184, 132, 200, 140), pr(ROCK, 60, 152, 74, 160)),
            listOf(pool(92, 96, 148, 120))),
        4 to Room("THRONE ROOM", 0, "WES",
            listOf(pr(PILLAR, 44, 72, 60, 80), pr(PILLAR, 180, 72, 196, 80), pr(PILLAR, 44, 150, 60, 158), pr(PILLAR, 180, 150, 196, 158),
                pr(ROCK, 74, 146, 88, 154), pr(ROCK, 152, 146, 166, 154)),
            listOf(pool(70, 112, 98, 128), pool(142, 112, 170, 128))),
        5 to Room("EAST GALLERY", 2, "WS",
            listOf(pr(FOUNTAIN, 100, 72, 140, 90), pr(SHRUB, 56, 56, 72, 64), pr(SHRUB, 168, 56, 184, 64), pr(ROCK, 150, 152, 164, 160), pr(ROCK, 70, 100, 84, 108)),
            listOf(pool(36, 124, 68, 144), pool(172, 124, 204, 144))),
        6 to Room("LIBRARY", 3, "EN",
            listOf(pr(TABLE, 36, 76, 76, 82), pr(TABLE, 164, 76, 204, 82), pr(TABLE, 36, 136, 76, 142), pr(TABLE, 164, 136, 204, 142),
                pr(ROCK, 86, 150, 100, 158), pr(SHRUB, 190, 100, 204, 108)),
            listOf(pool(100, 104, 140, 120))),
        7 to Room("ENTRY HALL", 4, "WEN",
            listOf(pr(BRAZIER, 60, 52, 72, 60), pr(BRAZIER, 168, 52, 180, 60), pr(BRAZIER, 60, 152, 72, 160), pr(BRAZIER, 168, 152, 180, 160),
                pr(SHRUB, 82, 96, 98, 104), pr(SHRUB, 142, 96, 158, 104)),
            listOf(pool(36, 128, 60, 148), pool(180, 128, 204, 148))),
        8 to Room("CRYPT", 5, "WN",
            listOf(pr(TOMB, 48, 62, 80, 72), pr(TOMB, 160, 62, 192, 72), pr(TOMB, 48, 142, 80, 152), pr(TOMB, 160, 142, 192, 152), pr(ROCK, 90, 150, 104, 158)),
            listOf(pool(104, 100, 136, 124))),
    )
    private var rx = 1; private var ry = 0
    private val roomId get() = (ry + 1) * 3 + rx
    private val room get() = rooms.getValue(roomId)
    private val throne get() = roomId == 4
    private var bannerT = 0f

    private enum class Phase { INTRO, FIGHT, WON, LOST }
    private var phase = Phase.INTRO

    // the traveller
    private val maxHp = 6
    private var hp = maxHp; private var invulnT = 0f
    private var paraT = 0f; private var poisonT = 0f; private var poisonTick = 0f; private var invertT = 0f
    private val inverted get() = invertT > 0f

    // the creature
    private enum class Stage { DAIS, SLAM, CHASE }
    private var stage = Stage.DAIS; private var slamT = 0f
    private var bossHp = BOSS_MAX; private var bossHurtT = 0f
    private var bx = 120f; private var by = 64f          // his feet
    private var bossShotT = 3f; private var spellT = 6f; private var summonT = 4f
    private var bossPara = 0f; private var bossWeak = 0f; private var bossConf = 0f
    private var bWanderT = 0f; private var bwx = 0f; private var bwy = 0f
    private var crackOpen = false; private var shakeT = 0f
    private var chestOpen = false
    private val dais = RectF(84f, 34f, 156f, 86f)

    // spells: the rune he puts on your screen, and your attempt on the XY pad
    private val runeNames = listOf("BIND", "VENOM", "MIRROR")
    private val runes = listOf(
        listOf(0.5f to 0.06f, 0.94f to 0.9f, 0.06f to 0.9f, 0.5f to 0.06f),                        // a triangle: paralyses
        listOf(0.05f to 0.2f, 0.35f to 0.82f, 0.5f to 0.2f, 0.65f to 0.82f, 0.95f to 0.2f),         // a zig-zag: poisons
        listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f, 0.9f to 0.9f, 0.1f to 0.1f),               // an hourglass: mirrors
    )
    private val runeClosed = booleanArrayOf(true, false, true)
    private var castRune = -1; private var castT = 0f
    private val stroke = ArrayList<FloatArray>()
    private var lastScore = -1f; private var scoreT = 0f
    private var msg = ""; private var msgT = 0f
    private val NEED = 0.62f

    // imps, shots, holy lines, sparks, balls of light
    private class Minion(var x: Float, var y: Float, val room: Int) {
        var hp = 2; var cd = 2f + kotlin.random.Random.nextFloat() * 2f; var hurtT = 0f; var step = 0f
        var para = 0f; var weak = 0f; var conf = 0f; var wt = 0f; var wx = 0f; var wy = 0f
    }
    private class Shot(var x: Float, var y: Float, val vx: Float, val vy: Float, val boss: Boolean, val weak: Boolean) { var life = 5f }
    private class Ward(val pts: FloatArray, val room: Int, val closed: Boolean) { var life = 10f }
    private class Arc(val x1: Float, val y1: Float, val x2: Float, val y2: Float) { var life = 0.25f }
    private class Burst(val x: Float, val y: Float) { var life = 0.3f }
    private class Orb(val x: Float, val y: Float, val room: Int) { var life = 14f }
    private val minions = ArrayList<Minion>()
    private val shots = ArrayList<Shot>()
    private val wards = ArrayList<Ward>()
    private val drawing = ArrayList<Float>(); private var drawLen = 0f; private var wasL = false
    private val arcs = ArrayList<Arc>()
    private val bursts = ArrayList<Burst>()
    private val orbs = ArrayList<Orb>()
    private var orbT = 18f
    private var zapCd = 0f
    private val SPLASH = 14f

    private fun hasDoor(d: Int): Boolean =
        room.doors.contains("WENS"[d]) || (d == 2 && throne && crackOpen && phase == Phase.WON)

    /** Is this box of floor taken? (pools only stop feet, not shots) */
    private fun solid(l: Float, t: Float, r: Float, b: Float, walk: Boolean = true): Boolean {
        if (throne && phase != Phase.WON && r > dais.left + 4 && l < dais.right - 4 && t < dais.bottom - 2 && b > dais.top) return true
        for (pp in room.props) { val f = pp.foot; if (r > f.left && l < f.right && t < f.bottom && b > f.top) return true }
        if (walk) for (w in room.pools) if (r > w.left + 2 && l < w.right - 2 && t < w.bottom - 2 && b > w.top + 2) return true
        return false
    }

    private fun blocked(x: Float, y: Float): Boolean {
        val l = x + 3; val r = x + 13; val t = y + 10; val b = y + 15
        if (l < 4 && !(hasDoor(0) && t >= 84 && b <= 120)) return true
        if (r > W - 4 && !(hasDoor(1) && t >= 84 && b <= 120)) return true
        if (t < 30 && !(hasDoor(2) && l >= 104 && r <= 136)) return true
        if (b > H - 3 && !(hasDoor(3) && l >= 104 && r <= 136)) return true
        return solid(l, t, r, b)
    }
    private fun impSolid(x: Float, y: Float) = x - 3 < 4 || x + 3 > W - 4 || y - 3 < 32 || y + 3 > H - 3 || solid(x - 3, y - 3, x + 3, y + 3)
    private fun bossSolid(x: Float, y: Float) = x - 10 < 4 || x + 10 > W - 4 || y - 6 < 32 || y > H - 3 || solid(x - 10, y - 6, x + 10, y)
    private fun shotStopped(x: Float, y: Float) = room.props.any { it.body.contains(x, y) }

    // ---- consecrate: segments and shapes
    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        val d = (bx - ax) * (dy - cy) - (by - ay) * (dx - cx)
        if (d == 0f) return false
        val u = ((cx - ax) * (dy - cy) - (cy - ay) * (dx - cx)) / d
        val v = ((cx - ax) * (by - ay) - (cy - ay) * (bx - ax)) / d
        return u in 0f..1f && v in 0f..1f
    }
    private fun wardBlocks(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        for (w in wards) {
            if (w.room != roomId) continue
            val q = w.pts; var i = 0
            while (i + 3 < q.size) { if (cross(x0, y0, x1, y1, q[i], q[i + 1], q[i + 2], q[i + 3])) return true; i += 2 }
        }
        return false
    }
    private fun inside(q: FloatArray, x: Float, y: Float): Boolean {
        var c = false; val n = q.size / 2; var j = n - 1
        for (i in 0 until n) {
            val xi = q[i * 2]; val yi = q[i * 2 + 1]; val xj = q[j * 2]; val yj = q[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** L let go: the drawn line stands for ten seconds; a closed shape curses whoever is inside. */
    private fun finishWard() {
        val n = drawing.size / 2
        if (n >= 3) {
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until n) { val x = drawing[i * 2]; val y = drawing[i * 2 + 1]; minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
            val sx = drawing[0]; val sy = drawing[1]
            val closed = n >= 10 && hypot(drawing[drawing.size - 2] - sx, drawing[drawing.size - 1] - sy) < 20f && maxX - minX > 14f && maxY - minY > 14f
            if (closed) { drawing += sx; drawing += sy }
            val pts = drawing.toFloatArray()
            val mine = wards.filter { it.room == roomId }
            if (mine.size >= 2) wards.remove(mine.first())
            wards += Ward(pts, roomId, closed)
            if (closed && phase == Phase.FIGHT) {
                var hit = 0
                for (m in minions) if (m.room == roomId && inside(pts, m.x, m.y)) { curse(m); hit++ }
                minions.removeAll { it.hp <= 0 }
                if (throne && stage != Stage.SLAM && inside(pts, bx, by - 20)) curseBoss()
                else if (hit > 0) { msg = if (hit == 1) "CONSECRATED!" else "CONSECRATED x$hit!"; msgT = 1.2f }
            }
        }
        drawing.clear(); drawLen = 0f
    }
    private fun curse(m: Minion) {
        when (rnd.nextInt(4)) {
            0 -> m.para = 5f
            1 -> m.weak = 6f
            2 -> m.conf = 6f
            else -> { m.hp -= 2; m.hurtT = 0.4f; bursts += Burst(m.x, m.y) }
        }
    }
    private fun curseBoss() {
        when (rnd.nextInt(4)) {
            0 -> { bossPara = 4f; msg = "HE IS BOUND!" }
            1 -> { bossWeak = 6f; msg = "HE IS WEAKENED!" }
            2 -> { bossConf = 6f; msg = "HE IS CONFUSED!" }
            else -> { msg = "SMITTEN!"; bursts += Burst(bx, by - 24); damageBoss(20) }
        }
        msgT = 1.4f
    }

    private fun enterRoom(nx: Int, ny: Int, px: Float, py: Float) {
        rx = nx; ry = ny; hx = px; hy = py; bannerT = 1.8f
        shots.clear(); drawing.clear(); drawLen = 0f
        if (castRune >= 0) { castRune = -1; stroke.clear(); spellT = 4f }
        val id = roomId
        if (room.kind != 6 && !throne && phase == Phase.FIGHT && minions.count { it.room == id } < 2) repeat(2 + rnd.nextInt(2)) { spawnMinion(null) }
    }

    private fun spawnMinion(at: Pair<Float, Float>?) {
        var x = at?.first ?: 0f; var y = at?.second ?: 0f
        if (at == null) {
            for (k in 0 until 40) {
                x = 20f + rnd.nextFloat() * (W - 40f); y = 44f + rnd.nextFloat() * (H - 60f)
                if (hypot(x - (hx + 8), y - (hy + 9)) > 60f && !impSolid(x, y)) break
            }
        }
        minions += Minion(x, y, roomId)
    }

    private fun spawnOrb() {
        for (k in 0 until 40) {
            val x = 20f + rnd.nextFloat() * (W - 40f); val y = 44f + rnd.nextFloat() * (H - 60f)
            if (hypot(x - (hx + 8), y - (hy + 9)) > 40f && !impSolid(x, y) && !shotStopped(x, y)) { orbs += Orb(x, y, roomId); return }
        }
    }

    private fun hurt(n: Int) {
        if (invulnT > 0f || phase != Phase.FIGHT) return
        hp -= n; invulnT = 1f
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (hp <= 0) { hp = 0; phase = Phase.LOST; castRune = -1; msg = ""; paraT = 0f; poisonT = 0f; invertT = 0f; stroke.clear() }
    }

    private fun damageBoss(n: Int) {
        if (phase != Phase.FIGHT || stage == Stage.SLAM) return
        bossHp -= n; bossHurtT = 0.5f
        if (stage == Stage.DAIS && bossHp <= LAST) {
            // his last stand: he slams the wall behind him, then comes for you
            bossHp = LAST; stage = Stage.SLAM; slamT = 0f; castRune = -1; stroke.clear(); shots.clear()
            msg = "HE'S FURIOUS!"; msgT = 1.6f
        } else if (stage == Stage.CHASE && bossHp <= 0) {
            bossHp = 0; phase = Phase.WON; minions.clear(); shots.clear(); castRune = -1
            paraT = 0f; poisonT = 0f; invertT = 0f; bossPara = 0f; bossConf = 0f; bossWeak = 0f
            talking = true; talkLines = DEFEAT; line = 0; shown = 0f
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun restart() {
        hp = maxHp; invulnT = 0f; paraT = 0f; poisonT = 0f; invertT = 0f
        bossHp = BOSS_MAX; stage = Stage.DAIS; bx = 120f; by = 64f; bossPara = 0f; bossWeak = 0f; bossConf = 0f
        bossShotT = 3f; spellT = 6f; summonT = 4f; castRune = -1; crackOpen = false; chestOpen = false
        minions.clear(); shots.clear(); wards.clear(); drawing.clear(); arcs.clear(); bursts.clear(); orbs.clear(); stroke.clear()
        rx = 1; ry = 1; hx = 112f; hy = 120f; phase = Phase.FIGHT; bannerT = 1.8f; talking = false; orbT = 18f
        msg = "FIGHT!"; msgT = 1.2f
    }

    private fun fire(sx: Float, sy: Float, tx: Float, ty: Float, speed: Float, boss: Boolean, weak: Boolean, conf: Boolean) {
        var a = atan2(ty - sy, tx - sx)
        if (conf) a = rnd.nextFloat() * 6.2832f else if (weak) a += (rnd.nextFloat() - 0.5f) * 1.8f
        shots += Shot(sx, sy, cos(a) * speed * SLOW, sin(a) * speed * SLOW, boss, weak)
    }

    private fun landSpell() {
        when (castRune) { 0 -> { paraT = 3f; msg = "BOUND!" }; 1 -> { poisonT = 6f; poisonTick = 1.5f; msg = "POISONED!" }; else -> { invertT = 7f; msg = "MIRRORED!" } }
        msgT = 1.4f; castRune = -1; spellT = (if (stage == Stage.CHASE) 10f else 9f) + rnd.nextFloat() * 3f
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun update(dt: Float) {
        val tm = (System.nanoTime() - t0) / 1e9f
        // the firefly: after the XY pad, or bumbling round the traveller's head once it's let go
        flyFreeT += dt
        val following = xyPointer < 0 && flyFreeT > 1.2f
        if (following) {
            bumA += (dt * 1.4f + (rnd.nextFloat() - 0.5f) * dt * 8f) * SLOW
            val rad = 13f + 5f * sin(tm * 0.8f)
            flyTX = (hx + 8 + cos(bumA) * rad + sin(tm * 2.3f) * 3f).coerceIn(4f, W - 4f)
            flyTY = (hy + 2 + sin(bumA) * rad * 0.7f + cos(tm * 1.9f) * 3f).coerceIn(12f, H - 4f)
        }
        val k = minOf(1f, dt * SLOW * if (following) 2.2f else if (selDown) 8f else 4f)
        flyX += (flyTX - flyX) * k; flyY += (flyTY - flyY) * k

        if (talking) shown += dt * 28f
        bannerT -= dt; msgT -= dt; scoreT -= dt; bossHurtT -= dt; zapCd -= dt; shakeT -= dt
        arcs.removeAll { it.life -= dt; it.life <= 0f }
        bursts.removeAll { it.life -= dt; it.life <= 0f }
        wards.removeAll { it.life -= dt; it.life <= 0f }

        // consecrate: holding L, the firefly draws
        if (selDown) {
            val n = drawing.size
            if (n == 0) { drawing += flyX; drawing += flyY }
            else {
                val d = hypot(flyX - drawing[n - 2], flyY - drawing[n - 1])
                if (d > 2.5f && drawLen + d < 320f) { drawing += flyX; drawing += flyY; drawLen += d }
            }
            wasL = true
        } else if (wasL) { wasL = false; finishWard() }

        // walking (not while bound); MIRROR flips the D-pad
        val ex = if (inverted) -dirX else dirX; val ey = if (inverted) -dirY else dirY
        if (paraT <= 0f && (ex != 0 || ey != 0) && phase != Phase.LOST) {
            facing = when { ey > 0 -> 0; ey < 0 -> 1; ex < 0 -> 2; else -> 3 }
            val sp = 46f * SLOW * dt
            val nx = hx + ex * sp; val ny = hy + ey * sp
            if (!blocked(nx, hy)) hx = nx
            if (!blocked(hx, ny)) hy = ny
            walkT += dt
        } else walkT = 0f
        when {
            hx + 8 < 0 && hasDoor(0) -> enterRoom(rx - 1, ry, W - 14f, hy)
            hx + 8 > W && hasDoor(1) -> enterRoom(rx + 1, ry, -2f, hy)
            hy + 12 < 18 && hasDoor(2) -> enterRoom(rx, ry - 1, hx, H - 18f)
            hy + 15 > H + 4 && hasDoor(3) -> enterRoom(rx, ry + 1, hx, 22f)
        }

        if (phase != Phase.FIGHT || talking) return
        invulnT -= dt; paraT -= dt; invertT -= dt
        if (poisonT > 0f) { poisonT -= dt; poisonTick -= dt; if (poisonTick <= 0f) { poisonTick = 1.5f; invulnT = 0f; hurt(1) } }
        val px = hx + 8; val py = hy + 9

        // balls of light, now and then, one at a time
        orbT -= dt
        if (orbT <= 0f) { orbT = 22f + rnd.nextFloat() * 14f; if (room.kind != 6 && orbs.none { it.room == roomId }) spawnOrb() }
        orbs.removeAll { it.life -= dt; it.life <= 0f }
        orbs.firstOrNull { it.room == roomId && hypot(it.x - px, it.y - (hy + 10)) < 10f }?.let {
            orbs.remove(it); bursts += Burst(it.x, it.y)
            if (hp < maxHp) { hp = minOf(maxHp, hp + 2); msg = "+2 HEARTS"; msgT = 1.2f }
        }

        if (throne) updateBoss(dt, px, py)
        if (phase != Phase.FIGHT) return

        // the imps: chase you, throw pebbles, stopped by holy lines
        for (m in minions) {
            if (m.room != roomId) continue
            m.hurtT -= dt; m.para -= dt; m.weak -= dt; m.conf -= dt
            if (m.para > 0f) continue
            val dx = px - m.x; val dy = py - m.y; val d = hypot(dx, dy).coerceAtLeast(0.01f)
            var ux = dx / d; var uy = dy / d
            if (m.conf > 0f) {
                m.wt -= dt
                if (m.wt <= 0f) { val a = rnd.nextFloat() * 6.2832f; m.wx = cos(a); m.wy = sin(a); m.wt = 0.7f }
                ux = m.wx; uy = m.wy
            }
            if (d > 7f || m.conf > 0f) {
                val sp = 16f * SLOW * dt
                val nx = m.x + ux * sp; val ny = m.y + uy * sp
                if (!impSolid(nx, m.y) && !wardBlocks(m.x, m.y, nx, m.y)) m.x = nx
                if (!impSolid(m.x, ny) && !wardBlocks(m.x, m.y, m.x, ny)) m.y = ny
                m.step += dt
            }
            if (d <= 7f && m.weak <= 0f) hurt(1)
            m.cd -= dt
            if (m.cd <= 0f && d < 100f) { m.cd = 2.5f + rnd.nextFloat() * 2f; fire(m.x, m.y, px, py, 46f, false, m.weak > 0f, m.conf > 0f) }
        }

        // shots: holy lines and anything standing stop them; they fly over water
        val iter = shots.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            val nx = s.x + s.vx * dt; val ny = s.y + s.vy * dt
            s.life -= dt
            if (wardBlocks(s.x, s.y, nx, ny)) { bursts += Burst(nx, ny); iter.remove(); continue }
            s.x = nx; s.y = ny
            if (s.life <= 0f || s.x < -4 || s.x > W + 4 || s.y < 0 || s.y > H + 4 || shotStopped(s.x, s.y)) { iter.remove(); continue }
            if (s.x > hx + 3 && s.x < hx + 13 && s.y > hy + 2 && s.y < hy + 15) { if (!s.weak) hurt(1); iter.remove() }
        }
    }

    private fun updateBoss(dt: Float, px: Float, py: Float) {
        bossPara -= dt; bossWeak -= dt; bossConf -= dt
        when (stage) {
            Stage.DAIS -> {
                if (castRune >= 0) { castT -= dt; if (castT <= 0f) landSpell() }
                else if (bossPara <= 0f) {
                    bossShotT -= dt
                    if (bossShotT <= 0f) { bossShotT = 2.1f + rnd.nextFloat() * 0.8f; fire(bx, by - 30, px, py, 58f, true, bossWeak > 0f, bossConf > 0f) }
                    spellT -= dt
                    if (spellT <= 0f) { castRune = rnd.nextInt(runes.size); castT = 7f; stroke.clear(); lastScore = -1f }
                }
                summonT -= dt
                if (summonT <= 0f && bossPara <= 0f) {
                    summonT = 9f
                    if (minions.count { it.room == roomId } < 5) { spawnMinion(96f to 100f); spawnMinion(144f to 100f) }
                }
            }
            Stage.SLAM -> {
                slamT += dt
                if (slamT > 0.9f && !crackOpen) { crackOpen = true; shakeT = 0.6f; msg = "CRACK!"; msgT = 1.2f; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                if (slamT > 1.6f) by = 64f + (108f - 64f) * ((slamT - 1.6f) / 0.6f).coerceAtMost(1f)
                if (slamT > 2.2f) { stage = Stage.CHASE; by = 108f; spellT = 5f; msg = "LAST STAND!"; msgT = 1.6f; shakeT = 0.3f }
            }
            Stage.CHASE -> {
                if (castRune >= 0) { castT -= dt; if (castT <= 0f) landSpell(); return }
                if (bossPara > 0f) return
                spellT -= dt
                if (spellT <= 0f) { castRune = 2; castT = 7f; stroke.clear(); lastScore = -1f; return }
                val dx = px - bx; val dy = (py + 4) - by; val d = hypot(dx, dy).coerceAtLeast(0.01f)
                var mx = dx / d; var my = dy / d
                if (bossConf > 0f || bWanderT > 0f) {
                    bWanderT -= dt
                    if (bossConf > 0f && bWanderT <= 0f) { val a = rnd.nextFloat() * 6.2832f; bwx = cos(a); bwy = sin(a); bWanderT = 0.8f }
                    mx = bwx; my = bwy
                }
                val sp = 19f * SLOW * dt
                val nx = bx + mx * sp; val ny = by + my * sp
                var moved = false
                if (!bossSolid(nx, by) && !wardBlocks(bx, by, nx, by)) { bx = nx; moved = true }
                if (!bossSolid(bx, ny) && !wardBlocks(bx, by, bx, ny)) { by = ny; moved = true }
                if (!moved && bWanderT <= 0f) { val sgn = if (rnd.nextBoolean()) 1f else -1f; bwx = -my * sgn; bwy = mx * sgn; bWanderT = 0.7f }
                if (bossWeak <= 0f && hypot(bx - px, (by - 6) - (hy + 12)) < 14f) hurt(1)
            }
        }
    }

    /** R, Vital Spark: magic arcs from the firefly through up to three imps (and him), each strike bursting. */
    private fun zap() {
        if (phase != Phase.FIGHT || talking || zapCd > 0f) return
        zapCd = 0.45f
        var fromX = flyX; var fromY = flyY
        val hit = minions.filter { it.room == roomId && hypot(it.x - flyX, it.y - flyY) < 32f }.sortedBy { hypot(it.x - flyX, it.y - flyY) }.take(3)
        val struck = HashSet<Minion>()
        fun burst(x: Float, y: Float) {
            bursts += Burst(x, y)
            for (m in minions) if (m.room == roomId && hypot(m.x - x, m.y - y) < SPLASH && struck.add(m)) { m.hp--; m.hurtT = 0.3f }
        }
        burst(flyX, flyY)
        for (m in hit) { arcs += Arc(fromX, fromY, m.x, m.y); fromX = m.x; fromY = m.y; if (struck.add(m)) { m.hp--; m.hurtT = 0.3f }; burst(m.x, m.y) }
        minions.removeAll { it.hp <= 0 }
        if (throne && stage != Stage.SLAM && hypot(bx - flyX, by - 24 - flyY) < 34f) {
            arcs += Arc(flyX, flyY, bx, by - 24); bursts += Burst(bx, by - 24); damageBoss(5)
        }
    }

    // ---- recognising the drawn rune
    private fun resample(pts: List<FloatArray>, n: Int): Array<FloatArray> {
        var total = 0f
        for (i in 1 until pts.size) total += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        val step = (total / (n - 1)).coerceAtLeast(1e-6f)
        val out = ArrayList<FloatArray>(); out += pts[0]
        var acc = 0f; var prev = pts[0]; var i = 1
        while (i < pts.size && out.size < n) {
            val cur = pts[i]; val seg = hypot(cur[0] - prev[0], cur[1] - prev[1])
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
                for (i in 0 until n) { val a = s[(i + k) % n]; val b = tpl[i]; d += hypot(a[0] - b[0], a[1] - b[1]) }
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
            castRune = -1; spellT = (if (stage == Stage.CHASE) 10f else 9f) + rnd.nextFloat() * 3f
            msg = "COUNTERED!"; msgT = 1.4f
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            damageBoss(40)
        }
        stroke.clear()
    }

    // =====================================================================================
    // Drawing the little screen
    // =====================================================================================

    private fun box(l: Float, t: Float, r: Float, b: Float, col: Int) { p.color = col; sc.drawRect(l, t, r, b, p) }
    private val line1 = Paint().apply { isAntiAlias = false; strokeWidth = 1f; style = Paint.Style.STROKE }
    private val line2 = Paint().apply { isAntiAlias = false; strokeWidth = 2f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val glowFill = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL; color = 0x33F8E070 }
    private val wardPath = Path()

    private fun window(x: Float) { box(x, 2f, x + 8, 24f, MID); box(x + 1, 3f, x + 7, 24f, WHITE); box(x + 3, 3f, x + 5, 24f, PALE) }

    private fun drawRoom(time: Float) {
        val crypt = room.kind == 5
        val floor = if (crypt) PALE else WHITE; val grout = if (crypt) MID else PALE
        box(0f, 0f, W.toFloat(), H.toFloat(), floor)
        // back wall
        box(0f, 0f, W.toFloat(), 30f, if (crypt) MID else PALE)
        for (row in 0 until 3) for (k in 0 until W / 16 + 1) {
            val off = if (row % 2 == 0) 0f else 8f
            box(k * 16f - off + 1, row * 9f + 1, k * 16f - off + 15, row * 9f + 8, if (crypt) PALE else WHITE)
        }
        box(0f, 27f, W.toFloat(), 30f, MID); box(0f, 30f, W.toFloat(), 31f, DARK)
        // floor tiles
        for (ty in 0 until (H - 31) / 16 + 1) for (tx in 0 until W / 16) {
            val x0 = tx * 16f; val y0 = 31f + ty * 16f
            box(x0, y0, x0 + 16, y0 + 1, grout); box(x0, y0, x0 + 1, y0 + 16, grout)
            if ((tx + ty) % 2 == 0) box(x0 + 2, y0 + 2, x0 + 4, y0 + 4, grout)
        }
        // side walls and the bottom ledge, with gaps where the doors are
        for (side in 0..1) {
            val x0 = if (side == 0) 0f else W - 4f
            box(x0, 31f, x0 + 4, H.toFloat(), MID); box(if (side == 0) 3f else W - 4f, 31f, if (side == 0) 4f else W - 3f, H.toFloat(), DARK)
            if (hasDoor(side)) {
                box(x0, 82f, x0 + 4, 122f, floor); box(x0, 81f, x0 + 4, 82f, DARK); box(x0, 122f, x0 + 4, 123f, DARK)
                val ax = if (side == 0) 8f else W - 12f; val dir = if (side == 0) -1f else 1f
                if ((time * 2).toInt() % 2 == 0) for (k in 0 until 3) box(ax + dir * k, 100f - (2 - k), ax + dir * k + 1, 102f + (2 - k), MID)
            }
        }
        box(0f, H - 3f, W.toFloat(), H.toFloat(), MID)
        if (hasDoor(3)) box(104f, H - 3f, 136f, H.toFloat(), floor)
        if (hasDoor(2) && !throne) {
            box(102f, 4f, 138f, 31f, DARK); box(104f, 6f, 136f, 31f, INK); box(106f, 8f, 134f, 31f, 0xFF303040.toInt()); box(102f, 2f, 138f, 6f, MID)
        }
        when (room.kind) {
            0 -> {
                window(56f); window(176f)
                box(104f, 86f, 136f, H.toFloat(), PALE); box(106f, 86f, 134f, H.toFloat(), WHITE)
                for (y in 88 until H step 6) box(108f, y.toFloat(), 132f, y + 1f, PALE)
                box(dais.left, dais.bottom - 6, dais.right, dais.bottom, MID); box(dais.left, dais.bottom - 6, dais.right, dais.bottom - 5, DARK)
                box(dais.left + 4, dais.bottom - 12, dais.right - 4, dais.bottom - 6, PALE); box(dais.left + 4, dais.bottom - 12, dais.right - 4, dais.bottom - 11, MID)
                box(dais.left + 8, dais.top, dais.right - 8, dais.bottom - 12, WHITE)
                if (crackOpen) {
                    if (phase == Phase.WON) {
                        // the crack, broken through: a ragged hole with a glint of gold beyond
                        box(110f, 3f, 128f, 31f, INK); box(106f, 9f, 134f, 31f, INK); box(104f, 16f, 136f, 31f, INK)
                        box(112f, 6f, 126f, 31f, 0xFF303040.toInt()); box(108f, 12f, 132f, 31f, 0xFF303040.toInt())
                        if ((time * 3).toInt() % 3 == 0) box(119f, 20f, 121f, 22f, GOLD)
                    } else {
                        val xs = floatArrayOf(119f, 121f, 117f, 120f, 123f, 119f, 116f, 120f)
                        for (k in xs.indices) box(xs[k], 2f + k * 3.5f, xs[k] + 2, 6f + k * 3.5f, INK)
                        box(112f, 14f, 117f, 15f, INK); box(123f, 21f, 129f, 22f, INK)
                    }
                }
            }
            1 -> {
                for ((k, px) in floatArrayOf(30f, 108f, 186f).withIndex()) {
                    box(px, 6f, px + 24, 24f, DARK); box(px + 2, 8f, px + 22, 22f, WHITE)
                    when (k) {
                        0 -> { box(px + 4, 16f, px + 20, 20f, MID); box(px + 9, 11f, px + 13, 16f, MID) }
                        1 -> { box(px + 6, 10f, px + 18, 20f, PALE); box(px + 9, 13f, px + 11, 15f, INK); box(px + 13, 13f, px + 15, 15f, INK) }
                        else -> { box(px + 4, 18f, px + 20, 20f, LEAF_D); box(px + 10, 10f, px + 14, 18f, LEAF) }
                    }
                }
            }
            2 -> { window(30f); window(84f); window(148f); window(202f) }
            3 -> {
                for (x0 in floatArrayOf(8f, 146f)) {
                    box(x0, 2f, x0 + 86, 30f, DARK)
                    for (shelf in 0 until 3) {
                        val y0 = 4f + shelf * 9f
                        for (k in 0 until 21) box(x0 + 2 + k * 4f, y0 + (k % 3), x0 + 5 + k * 4f, y0 + 8, if ((k + shelf) % 3 == 0) MID else if ((k + shelf) % 3 == 1) WHITE else PALE)
                    }
                }
            }
            4 -> {
                box(104f, 31f, 136f, H - 3f, PALE); box(106f, 31f, 134f, H - 3f, WHITE)
                for (y in 34 until H step 6) box(108f, y.toFloat(), 132f, y + 1f, PALE)
                for (bxn in floatArrayOf(40f, 188f)) { box(bxn, 2f, bxn + 12, 22f, RUNE); box(bxn + 2, 22f, bxn + 6, 26f, RUNE); box(bxn + 6, 22f, bxn + 10, 24f, RUNE) }
            }
            5 -> for (k in 0 until 6) { box(4f + k * 2, 31f, 5f + k * 2, 31f + (6 - k) * 2, MID); box(W - 5f - k * 2, 31f, W - 4f - k * 2, 31f + (6 - k) * 2, MID) }
            6 -> {
                window(28f); window(204f)
                for (bxn in floatArrayOf(84f, 148f)) { box(bxn, 2f, bxn + 10, 22f, GOLD_D); box(bxn + 1, 3f, bxn + 9, 20f, GOLD) }
                for ((gx, gy) in listOf(60f to 120f, 176f to 128f, 92f to 152f, 150f to 146f, 196f to 92f)) {
                    box(gx - 8, gy - 2, gx + 8, gy, GOLD_D); box(gx - 6, gy - 4, gx + 6, gy - 2, GOLD); box(gx - 3, gy - 6, gx + 3, gy - 4, GOLD)
                    if (((time * 2).toInt() + gx.toInt()) % 4 == 0) box(gx - 1, gy - 9, gx + 1, gy - 7, WHITE)
                }
            }
        }
        // pools: water you can't wade, with ripples
        for (w in room.pools) {
            box(w.left + 2, w.top, w.right - 2, w.bottom, WATER_D); box(w.left, w.top + 2, w.right, w.bottom - 2, WATER_D)
            box(w.left + 2, w.top + 1, w.right - 2, w.bottom - 1, WATER); box(w.left + 1, w.top + 2, w.right - 1, w.bottom - 2, WATER)
            val ph = (time * 0.8f) % 1f
            val ry1 = w.top + 4 + ph * (w.height() - 8); val ry2 = w.top + 4 + ((ph + 0.5f) % 1f) * (w.height() - 8)
            box(w.left + 4, ry1, w.left + w.width() * 0.45f, ry1 + 1, WHITE); box(w.left + w.width() * 0.5f, ry2, w.right - 4, ry2 + 1, WHITE)
        }
    }

    private fun drawProp(pp: Prop, time: Float) {
        val f = pp.foot; val b = pp.body
        when (pp.kind) {
            PILLAR -> {
                box(f.left - 2, b.top, f.right + 2, b.top + 4, MID); box(f.left - 2, b.top, f.right + 2, b.top + 1, DARK)
                box(f.left, b.top + 4, f.right, f.bottom - 4, WHITE)
                box(f.left, b.top + 4, f.left + 1, f.bottom - 4, MID); box(f.right - 1, b.top + 4, f.right, f.bottom - 4, MID)
                for (k in 1 until 4) box(f.left + k * 4f, b.top + 6, f.left + k * 4f + 1, f.bottom - 6, PALE)
                box(f.left - 2, f.bottom - 4, f.right + 2, f.bottom, MID); box(f.left - 2, f.bottom - 1, f.right + 2, f.bottom, DARK)
            }
            ROCK -> {
                box(f.left + 2, b.top, f.right - 2, f.bottom, DARK); box(f.left, b.top + 3, f.right, f.bottom, DARK)
                box(f.left + 3, b.top + 1, f.right - 3, f.bottom - 1, MID); box(f.left + 1, b.top + 4, f.right - 1, f.bottom - 1, MID)
                box(f.left + 3, b.top + 2, f.left + 7, b.top + 4, PALE); box(f.right - 5, f.bottom - 4, f.right - 2, f.bottom - 2, DARK)
            }
            SHRUB -> {
                box(f.left + 3, b.top, f.right - 3, f.bottom, LEAF_D); box(f.left, b.top + 4, f.right, f.bottom - 1, LEAF_D)
                box(f.left + 4, b.top + 1, f.right - 4, f.bottom - 1, LEAF); box(f.left + 1, b.top + 5, f.right - 1, f.bottom - 2, LEAF)
                box(f.left + 5, b.top + 3, f.left + 7, b.top + 5, WHITE); box(f.right - 6, b.top + 7, f.right - 4, b.top + 9, LEAF_D)
            }
            STATUE -> {
                box(f.left, f.bottom - 12, f.right, f.bottom, MID); box(f.left + 1, f.bottom - 11, f.right - 1, f.bottom - 1, PALE)
                box(f.left + 2, b.top, f.right - 2, f.bottom - 12, MID); box(f.left + 3, b.top + 1, f.right - 3, f.bottom - 13, WHITE)
                box(f.left + 5, b.top + 5, f.left + 6, b.top + 6, DARK); box(f.right - 6, b.top + 5, f.right - 5, b.top + 6, DARK)
            }
            FOUNTAIN -> {
                val cx = f.centerX()
                box(f.left, f.top + 4, f.right, f.bottom, MID); box(f.left + 2, f.top + 6, f.right - 2, f.bottom - 2, WATER)
                val ph = (time * 3).toInt() % 3
                for (k in 0 until 3) { val r = 3f + ((k + ph) % 3) * 5f; box(cx - r, f.centerY() + 2, cx + r, f.centerY() + 3, WHITE) }
                box(cx - 3, f.top - 10, cx + 3, f.centerY() + 2, MID); box(cx - 2, f.top - 9, cx + 2, f.centerY() + 2, WHITE)
                box(cx - 1, f.top - 14 + ph, cx + 1, f.top - 10, WATER_D)
            }
            TABLE -> {
                box(f.left + 2, b.top + 4, f.left + 4, f.bottom, DARK); box(f.right - 4, b.top + 4, f.right - 2, f.bottom, DARK)
                box(f.left, b.top - 2, f.right, b.top + 4, DARK); box(f.left + 1, b.top - 1, f.right - 1, b.top + 3, PALE)
                box(f.left + 6, b.top - 1, f.left + 14, b.top + 2, WHITE); box(f.right - 10, b.top - 3, f.right - 6, b.top, MID)
            }
            BRAZIER -> {
                box(f.left + 3, f.top, f.right - 3, f.bottom, DARK); box(f.left, b.top + 4, f.right, f.top + 2, DARK); box(f.left + 1, b.top + 5, f.right - 1, f.top + 1, MID)
                val k = (time * 8).toInt() % 2
                box(f.left + 2, b.top - 2 - k, f.right - 2, b.top + 5, WHITE); box(f.left + 4, b.top - 6 + k, f.right - 4, b.top, PALE)
            }
            TOMB -> {
                box(f.left, b.top, f.right, f.bottom, DARK); box(f.left + 1, b.top + 1, f.right - 1, f.bottom - 4, MID)
                box(f.left + 3, b.top + 3, f.right - 3, b.top + 5, PALE)
            }
            CHEST -> {
                if (!chestOpen) {
                    box(f.left, b.top, f.right, f.bottom, GOLD_D); box(f.left + 1, b.top + 1, f.right - 1, f.bottom - 1, GOLD)
                    box(f.left, b.top + 4, f.right, b.top + 5, GOLD_D); box(f.centerX() - 2, b.top + 3, f.centerX() + 2, b.top + 7, INK)
                    if ((time * 2).toInt() % 2 == 0) box(f.right - 4, b.top - 3, f.right - 2, b.top - 1, WHITE)
                } else {
                    box(f.left, b.top - 8, f.right, b.top, GOLD_D); box(f.left + 1, b.top - 7, f.right - 1, b.top - 1, GOLD)
                    box(f.left, b.top, f.right, f.bottom, GOLD_D); box(f.left + 2, b.top + 1, f.right - 2, b.top + 4, GOLD)
                    box(f.left + 4, b.top + 1, f.left + 7, b.top + 3, 0xFFE04848.toInt()); box(f.right - 8, b.top + 1, f.right - 5, b.top + 3, 0xFF48A0E0.toInt())
                    if ((time * 3).toInt() % 2 == 0) box(f.centerX() - 1, b.top - 12, f.centerX() + 1, b.top - 10, WHITE)
                }
            }
        }
    }

    private val src = Rect(); private val dst = RectF()
    private fun drawBoss(time: Float) {
        val bmp = yeti ?: return
        val frame: Int; var dx = 0f; var dy = 0f
        when {
            phase == Phase.WON -> { frame = 1; dy = 2f }
            stage == Stage.SLAM -> {
                if (slamT < 0.9f) { frame = 3; dx = if ((time * 20).toInt() % 2 == 0) -1f else 1f }
                else { frame = 0; if (slamT > 1.6f) dy = -sin(((slamT - 1.6f) / 0.6f).coerceIn(0f, 1f) * Math.PI.toFloat()) * 18f }
            }
            bossHurtT > 0f -> { frame = 5; dx = if ((time * 30).toInt() % 2 == 0) -2f else 2f; if ((time * 20).toInt() % 2 == 0) return }
            castRune >= 0 -> { frame = 3; dx = if ((time * 10).toInt() % 2 == 0) -1f else 1f }
            talking -> { val k = (time * 5).toInt(); frame = intArrayOf(3, 5, 3, 0)[k % 4]; dx = if (k % 2 == 0) -1f else 1f }
            stage == Stage.CHASE && bossPara <= 0f -> { val k = (time * 4).toInt(); frame = if (k % 2 == 0) 0 else 1; dy = if (k % 2 == 0) 0f else 1f }
            else -> {
                val cycle = time % 7f
                frame = if (cycle > 5.6f) 4 else if (cycle > 5.2f) 1 else 0
                dy = if ((time * 1.6f).toInt() % 2 == 0) 0f else 1f
            }
        }
        val x = bx - 28 + dx; val y = by - 56 + dy
        box(bx - 14, by - 2, bx + 14, by, PALE)
        src.set(frame * 56, 0, frame * 56 + 56, 56); dst.set(x, y, x + 56, y + 56)
        sc.drawBitmap(bmp, src, dst, p)
        // what Consecrate did to him
        val hx0 = bx; val hy0 = y + 2
        if (bossPara > 0f) { box(x + 14, y + 22, x + 42, y + 23, RUNE); box(x + 14, y + 34, x + 42, y + 35, RUNE) }
        if (bossConf > 0f) for (k in 0 until 3) { val a = time * 4f + k * 2.1f; box(hx0 + cos(a) * 12 - 1, hy0 + sin(a) * 3 - 1, hx0 + cos(a) * 12 + 1, hy0 + sin(a) * 3 + 1, GOLD) }
        if (bossWeak > 0f) { val k = (time * 2) % 1f; box(x + 18, y + 10 + k * 10, x + 20, y + 12 + k * 10, DARK); box(x + 38, y + 14 + k * 8, x + 40, y + 16 + k * 8, DARK) }
        if (phase == Phase.FIGHT && stage == Stage.DAIS && castRune < 0 && !talking && (time % 4f) < 1.2f) {
            val word = MUTTER[((time / 4f).toInt()) % MUTTER.size]
            val bw = word.length * 8f + 6; val bxx = x + 40; val byy = y + 2
            box(bxx - 1, byy - 1, bxx + bw + 1, byy + 12, INK); box(bxx, byy, bxx + bw, byy + 11, WHITE)
            sc.drawText(word, bxx + 3, byy + 9, font)
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
            box(x + 1, y + 6, x + 15, y + 7, RUNE); box(x + 1, y + 11, x + 15, y + 12, RUNE)
            if ((time * 6).toInt() % 2 == 0) box(x + 7, y - 4, x + 9, y - 2, RUNE)
        }
        if (poisonT > 0f) {
            val c = 0xFF48A048.toInt(); val k = (time * 3) % 1f
            box(x + 3, y - 2 - k * 6, x + 5, y - k * 6, c); box(x + 11, y + 2 - ((k + 0.5f) % 1f) * 6, x + 13, y + 4 - ((k + 0.5f) % 1f) * 6, c)
        }
    }

    /** The imps: original little horned scamps, two frames of scurrying, with marks for their curses. */
    private fun drawMinion(m: Minion, time: Float) {
        val x = m.x.toInt() - 4f; val y = m.y.toInt() - 4f
        val body = if (m.hurtT > 0f) WHITE else DARK
        val f = if (m.para > 0f) 0 else (m.step * 8).toInt() % 2
        box(x + 1, y + 7, x + 7, y + 8, PALE)
        box(x, y, x + 1, y + 2, INK); box(x + 7, y, x + 8, y + 2, INK)
        box(x + 1, y + 1, x + 7, y + 6, INK); box(x + 2, y + 2, x + 6, y + 5, body)
        box(x + 2, y + 3, x + 3, y + 4, WHITE); box(x + 5, y + 3, x + 6, y + 4, WHITE)
        box(x + 1 + f, y + 6, x + 3 + f, y + 8, INK); box(x + 5 - f, y + 6, x + 7 - f, y + 8, INK)
        if (m.para > 0f) { box(x - 1, y + 2, x + 9, y + 3, RUNE); box(x - 1, y + 5, x + 9, y + 6, RUNE) }
        if (m.conf > 0f) { val a = time * 6f; box(m.x + cos(a) * 5 - 1, y - 3, m.x + cos(a) * 5 + 1, y - 1, GOLD) }
        if (m.weak > 0f) box(x + 3, y - 3 + (time * 4) % 2f, x + 5, y - 1 + (time * 4) % 2f, MID)
    }

    private fun drawOrb(o: Orb, time: Float) {
        if (o.life < 3f && (time * 10).toInt() % 2 == 0) return
        val bob = sin(time * 3f + o.x) * 1.5f
        val r = if ((time * 5).toInt() % 2 == 0) 5f else 4f
        p.color = 0x44FFF8C0; sc.drawRect(o.x - r - 2, o.y - r - 2 + bob, o.x + r + 2, o.y + r + 2 + bob, p)
        box(o.x - 3, o.y - 4 + bob, o.x + 3, o.y + 4 + bob, HOLY); box(o.x - 4, o.y - 3 + bob, o.x + 4, o.y + 3 + bob, HOLY)
        box(o.x - 2, o.y - 3 + bob, o.x + 2, o.y + 3 + bob, WHITE); box(o.x - 2, o.y - 2 + bob, o.x, o.y + bob, 0xFFFFFFFF.toInt())
        box(o.x - 1, o.y + 6, o.x + 1, o.y + 7, PALE)
    }

    // depth sorting: whoever stands lower on the screen is in front (so you can hide behind a pillar)
    private class Ent { var y = 0f; var type = 0; var idx = 0 }
    private val ents = Array(96) { Ent() }; private var entN = 0
    private val entOrder = Comparator<Ent> { a, b -> a.y.compareTo(b.y) }
    private fun addEnt(y: Float, type: Int, idx: Int) { if (entN < ents.size) { val e = ents[entN++]; e.y = y; e.type = type; e.idx = idx } }
    private fun drawScene(time: Float) {
        entN = 0
        room.props.forEachIndexed { i, pp -> addEnt(pp.foot.bottom, 0, i) }
        addEnt(hy + 15, 1, 0)
        minions.forEachIndexed { i, m -> if (m.room == roomId) addEnt(m.y + 4, 2, i) }
        if (throne) addEnt(by, 3, 0)
        orbs.forEachIndexed { i, o -> if (o.room == roomId) addEnt(o.y + 4, 4, i) }
        java.util.Arrays.sort(ents, 0, entN, entOrder)
        for (k in 0 until entN) {
            val e = ents[k]
            when (e.type) {
                0 -> drawProp(room.props[e.idx], time)
                1 -> drawHero(time)
                2 -> drawMinion(minions[e.idx], time)
                3 -> drawBoss(time)
                else -> drawOrb(orbs[e.idx], time)
            }
        }
    }

    private fun drawWards(time: Float) {
        for (w in wards) {
            if (w.room != roomId) continue
            if (w.life < 2f && (time * 12).toInt() % 2 == 0) continue
            val q = w.pts
            if (w.closed) {
                wardPath.reset(); wardPath.moveTo(q[0], q[1])
                var i = 2; while (i + 1 < q.size) { wardPath.lineTo(q[i], q[i + 1]); i += 2 }
                wardPath.close(); sc.drawPath(wardPath, glowFill)
            }
            line2.color = HOLY_D
            var i = 0; while (i + 3 < q.size) { sc.drawLine(q[i], q[i + 1] + 1, q[i + 2], q[i + 3] + 1, line2); i += 2 }
            line2.color = HOLY
            i = 0; while (i + 3 < q.size) { sc.drawLine(q[i], q[i + 1], q[i + 2], q[i + 3], line2); i += 2 }
        }
        // the line being drawn right now
        if (drawing.size >= 4) {
            line1.color = if ((time * 10).toInt() % 2 == 0) HOLY else WHITE
            var i = 0; while (i + 3 < drawing.size) { sc.drawLine(drawing[i], drawing[i + 1], drawing[i + 2], drawing[i + 3], line1); i += 2 }
            box(drawing[0] - 2, drawing[1] - 2, drawing[0] + 2, drawing[1] + 2, HOLY_D)
        }
    }

    private fun drawShots(time: Float) {
        for (s in shots) {
            if (s.boss) {
                val r = if ((time * 12).toInt() % 2 == 0) 3f else 2f
                box(s.x - r - 1, s.y - r - 1, s.x + r + 1, s.y + r + 1, INK); box(s.x - r, s.y - r, s.x + r, s.y + r, if (s.weak) MID else RUNE); box(s.x - 1, s.y - 1, s.x, s.y, WHITE)
            } else { box(s.x - 2, s.y - 2, s.x + 2, s.y + 2, if (s.weak) MID else INK); box(s.x - 1, s.y - 1, s.x + 1, s.y + 1, if (s.weak) PALE else MID) }
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
                val y1 = a.y1 + (a.y2 - a.y1) * t + jy - 6f * sin(t * Math.PI.toFloat())
                sc.drawLine(x0, y0, x1, y1, line1); x0 = x1; y0 = y1
            }
            box(a.x2 - 2, a.y2 - 2, a.x2 + 2, a.y2 + 2, WHITE)
        }
        for (b in bursts) {
            val r = SPLASH * (1f - b.life / 0.3f).coerceIn(0.2f, 1f)
            for (k in 0 until 10) {
                val a = k * (Math.PI.toFloat() * 2f / 10f)
                val x = b.x + cos(a) * r; val y = b.y + sin(a) * r
                box(x - 1, y - 1, x + 1, y + 1, if (k % 2 == 0) 0xFFFFF27A.toInt() else WHITE)
            }
        }
    }

    private fun drawFly(time: Float) {
        val x = flyX + sin(time * 9f) * 1.5f; val y = flyY + cos(time * 7f) * 1.5f
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
        var sx = 54f
        fun tag(t: String, col: Int) { box(sx, 1f, sx + 9, 9f, col); font.color = WHITE; sc.drawText(t, sx + 1, 9f, font); font.color = INK; sx += 11 }
        if (paraT > 0f) tag("B", RUNE)
        if (poisonT > 0f) tag("V", 0xFF48A048.toInt())
        if (invertT > 0f) tag("M", 0xFFD07020.toInt())
        // his health, with a notch where his last stand begins
        val l = 140f; val r = 236f
        box(l, 2f, r, 8f, DARK)
        val fill = (r - l - 2) * bossHp / BOSS_MAX
        box(l + 1, 3f, l + 1 + fill, 7f, if (bossHurtT > 0f && (time * 20).toInt() % 2 == 0) WHITE else if (stage == Stage.DAIS) 0xFFB040C0.toInt() else 0xFFE04848.toInt())
        val notch = l + 1 + (r - l - 2) * LAST / BOSS_MAX
        box(notch, 1f, notch + 1, 9f, WHITE)
    }

    /** The rune he puts on your screen: trace it on the XY pad before the bar runs out. */
    private fun drawRune(time: Float) {
        if (castRune < 0 || !throne) return
        val l = 84f; val t = 20f; val r = 156f; val b = 94f
        box(l - 1, t - 1, r + 1, b + 1, INK); box(l, t, r, b, WHITE); box(l + 1, t + 1, r - 1, b - 1, 0xFFF0ECF8.toInt())
        font.color = RUNE; sc.drawText(runeNames[castRune], l + (r - l - runeNames[castRune].length * 8) / 2, t + 10, font); font.color = INK
        val gl = l + 12; val gt = t + 14; val gs = 48f
        line2.color = if ((time * 4).toInt() % 2 == 0) RUNE else 0xFF8068D0.toInt()
        val pts = runes[castRune]
        for (k in 1 until pts.size) sc.drawLine(gl + pts[k - 1].first * gs, gt + pts[k - 1].second * gs, gl + pts[k].first * gs, gt + pts[k].second * gs, line2)
        if (stroke.size > 1 && xyRect.width() > 0) {
            line1.color = 0xFFE04848.toInt()
            fun mx(v: Float) = gl + ((v - xyRect.left) / xyRect.width()) * gs
            fun my(v: Float) = gt + ((v - xyRect.top) / xyRect.height()) * gs
            for (k in 1 until stroke.size) sc.drawLine(mx(stroke[k - 1][0]), my(stroke[k - 1][1]), mx(stroke[k][0]), my(stroke[k][1]), line1)
        }
        box(l + 4, b - 6, r - 4, b - 3, PALE); box(l + 4, b - 6, l + 4 + (r - l - 8) * (castT / 7f).coerceIn(0f, 1f), b - 3, RUNE)
        if (scoreT > 0f && lastScore >= 0f) {
            val s = "${(lastScore * 100).toInt()}%"
            box(r - 30, t + 14, r - 2, t + 24, if (lastScore >= NEED) 0xFF48A048.toInt() else 0xFFE04848.toInt())
            font.color = WHITE; sc.drawText(s, r - 28, t + 23, font); font.color = INK
        }
    }

    private fun drawTalk(time: Float) {
        if (!talking) return
        val top = H - 44f
        box(2f, top, W - 2f, H - 2f, INK); box(4f, top + 2, W - 4f, H - 4f, WHITE); box(6f, top + 4, W - 6f, H - 6f, INK); box(7f, top + 5, W - 7f, H - 7f, WHITE)
        val text = talkLines[line]
        val n = minOf(text.length, shown.toInt())
        wrap(text.take(n), 27).take(3).forEachIndexed { i, s -> sc.drawText(s, 11f, top + 16 + i * 10f, font) }
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
        val bw = text.length * 8f + 8; val bxx = (W - bw) / 2
        box(bxx - 1, y - 1, bxx + bw + 1, y + 13, INK); box(bxx, y, bxx + bw, y + 12, WHITE)
        sc.drawText(text, bxx + 4, y + 10, font)
    }

    private fun drawOverlays(time: Float) {
        if (bannerT > 0f && castRune < 0) banner(room.name, 14f)
        if (msgT > 0f && msg.isNotEmpty() && !talking) banner(msg, H - 58f)
        if (talking) return
        if (phase == Phase.LOST) { banner("YOU FELL...", 70f); if ((time * 2).toInt() % 2 == 0) banner("A: TRY AGAIN", 86f) }
        if (phase == Phase.WON && chestOpen && (time * 2).toInt() % 2 == 0) banner("A: PLAY AGAIN", 86f)
        if (phase == Phase.WON && roomId == 1 && !chestOpen && nearChest()) banner("A: OPEN", 90f)
    }
    private fun nearChest() = hypot(hx + 8 - 120f, hy + 12 - 74f) < 26f

    // =====================================================================================
    // The handheld around the screen: D-pad and L on the left; R, A / B and the XY pad on the right
    // =====================================================================================
    private val pad = RectF(); private val aKey = RectF(); private val bKey = RectF()
    private val shellP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Px.text(13f, 0xFFE8E8F0.toInt(), headFont = true)
    private val invertP = Paint().apply {
        isFilterBitmap = false
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
    }

    override fun paint(c: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f); lastNs = now
        val time = (now - t0) / 1e9f
        update(dt)
        drawRoom(time)
        drawWards(time)
        drawScene(time)
        drawShots(time)
        drawArcs()
        drawFly(time)
        drawHud(time)
        drawRune(time)
        drawOverlays(time)
        drawTalk(time)

        val w = width.toFloat(); val h = height.toFloat()
        val portrait = h > w * 1.1f
        c.drawColor(SHELL)
        shellP.color = SHELL_L; c.drawRect(0f, 0f, w, Px.dp(5f), shellP)
        shellP.color = SHELL_D; c.drawRect(0f, h - Px.dp(5f), w, h, shellP)
        for (k in 0 until 4) { shellP.color = Snes.INK; c.drawRect(w / 2 - Px.dp(40f) + k * Px.dp(22f), Px.dp(8f), w / 2 - Px.dp(28f) + k * Px.dp(22f), Px.dp(18f), shellP); shellP.color = Snes.ACCENTS[k]; c.drawRect(w / 2 - Px.dp(38.5f) + k * Px.dp(22f), Px.dp(9.5f), w / 2 - Px.dp(29.5f) + k * Px.dp(22f), Px.dp(16.5f), shellP) }

        // the screen: as big as the sides allow (whole pixels once it's big enough to matter)
        val st = Px.dp(32f)
        var scale = if (portrait) (w - Px.dp(28f)) / W else minOf(w * 0.6f / W, (h - st - Px.dp(16f)) / H)
        if (scale >= 3f) scale = kotlin.math.floor(scale)
        val sw = W * scale; val sh = H * scale
        val sl = (w - sw) / 2
        shellP.color = BEZEL; c.drawRoundRect(sl - Px.dp(10f), st - Px.dp(10f), sl + sw + Px.dp(10f), st + sh + Px.dp(10f), Px.dp(10f), Px.dp(10f), shellP)
        shellP.color = Snes.RED; c.drawCircle(sl - Px.dp(5f), st + sh * 0.3f, Px.dp(2.5f), shellP)
        src.set(0, 0, W, H)
        var ox = 0f; var oy = 0f
        if (shakeT > 0f) { ox = (rnd.nextFloat() - 0.5f) * scale * 4; oy = (rnd.nextFloat() - 0.5f) * scale * 4 }
        dst.set(sl + ox, st + oy, sl + sw + ox, st + sh + oy)
        c.save(); c.clipRect(sl, st, sl + sw, st + sh)
        c.drawBitmap(screen, src, dst, if (inverted && (invertT > 0.6f || (time * 10).toInt() % 2 == 0)) invertP else p)
        c.restore()
        screenRect.set(sl, st, sl + sw, st + sh)

        exitRect.set(Px.dp(10f), Px.dp(6f), Px.dp(116f), Px.dp(36f))
        val pw = Px.dp(150f)
        if (!portrait) {
            val colW = sl - Px.dp(14f)
            // left column: L, then the D-pad
            val dcx = colW / 2 + Px.dp(4f)
            val pwl = minOf(pw, colW - Px.dp(8f))
            selRect.set(dcx - pwl / 2, Px.dp(50f), dcx + pwl / 2, Px.dp(80f))
            val ds = minOf(Px.dp(150f), colW - Px.dp(24f), h - Px.dp(130f)).coerceAtLeast(Px.dp(80f))
            val dcy = (Px.dp(80f) + h) / 2
            pad.set(dcx - ds / 2, dcy - ds / 2, dcx + ds / 2, dcy + ds / 2)
            // right column: R, A and B, then the XY pad
            val rcx = sl + sw + Px.dp(10f) + colW / 2
            startRect.set(rcx - pwl / 2, Px.dp(50f), rcx + pwl / 2, Px.dp(80f))
            val r = minOf(Px.dp(22f), colW / 6f).coerceAtLeast(Px.dp(14f))
            val aby = Px.dp(96f) + r
            aKey.set(rcx + r * 0.3f, aby - r - Px.dp(3f), rcx + r * 2.3f, aby + r - Px.dp(3f))
            bKey.set(rcx - r * 2.3f, aby - r + Px.dp(3f), rcx - r * 0.3f, aby + r + Px.dp(3f))
            val xt = aby + r + Px.dp(18f); val xb = h - Px.dp(14f)
            val xw = minOf(colW - Px.dp(8f), (xb - xt) * 1.3f)
            xyRect.set(rcx - xw / 2, xt, rcx + xw / 2, xb)
        } else {
            // tall screens: the controls in a row under the screen
            val top = st + sh + Px.dp(20f)
            val third = w / 3
            selRect.set(third / 2 - minOf(pw, third - Px.dp(8f)) / 2, top, third / 2 + minOf(pw, third - Px.dp(8f)) / 2, top + Px.dp(30f))
            startRect.set(w - third / 2 - minOf(pw, third - Px.dp(8f)) / 2, top, w - third / 2 + minOf(pw, third - Px.dp(8f)) / 2, top + Px.dp(30f))
            val ds = minOf(third - Px.dp(16f), Px.dp(140f))
            val dcy = top + Px.dp(46f) + ds / 2
            pad.set(third / 2 - ds / 2, dcy - ds / 2, third / 2 + ds / 2, dcy + ds / 2)
            val r = minOf(Px.dp(22f), third / 6f)
            val rcx = w - third / 2
            aKey.set(rcx + r * 0.3f, dcy - r * 2f, rcx + r * 2.3f, dcy)
            bKey.set(rcx - r * 2.3f, dcy - r * 0.6f, rcx - r * 0.3f, dcy + r * 1.4f)
            val xt = top; val xb = h - Px.dp(14f)
            xyRect.set(third + Px.dp(6f), xt, 2 * third - Px.dp(6f), xb)
        }
        pill(c, exitRect, "◀ Music", Snes.GREEN, exitDown)
        pill(c, selRect, "L CONSECRATE", Snes.PURPLE, selDown)
        pill(c, startRect, "R VITAL SPARK", Snes.GREEN, startDown)

        if (xyRect.height() > Px.dp(30f)) {
            shellP.color = Snes.INK; c.drawRect(xyRect.left - Px.dp(2f), xyRect.top - Px.dp(2f), xyRect.right + Px.dp(2f), xyRect.bottom + Px.dp(2f), shellP)
            shellP.color = 0xFF2A2B40.toInt(); c.drawRect(xyRect, shellP)
            shellP.color = 0x22FFFFFF
            for (k in 1 until 6) { val fx = xyRect.left + xyRect.width() * k / 6f; c.drawRect(fx, xyRect.top, fx + 1, xyRect.bottom, shellP) }
            for (k in 1 until 4) { val fy = xyRect.top + xyRect.height() * k / 4f; c.drawRect(xyRect.left, fy, xyRect.right, fy + 1, shellP) }
            if (castRune >= 0 && throne) {
                shellP.color = Snes.RED
                for (k in 1 until stroke.size) c.drawCircle(stroke[k][0], stroke[k][1], Px.dp(3f), shellP)
                label.textSize = Px.dp(9f); label.color = 0x88FFFFFF.toInt()
                c.textCentered("DRAW THE RUNE", xyRect.centerX(), xyRect.top + Px.dp(12f), label)
            } else {
                var fx = (flyTX - 4f) / (W - 8f); var fy = (flyTY - 4f) / (H - 8f)
                if (inverted) { fx = 1f - fx; fy = 1f - fy }
                val dx = xyRect.left + xyRect.width() * fx.coerceIn(0f, 1f); val dy = xyRect.top + xyRect.height() * fy.coerceIn(0f, 1f)
                shellP.color = if (xyPointer >= 0) Snes.YELLOW else 0x88E8B828.toInt(); c.drawCircle(dx, dy, Px.dp(6f), shellP)
                shellP.color = 0xFFFFFFFF.toInt(); c.drawCircle(dx - Px.dp(2f), dy - Px.dp(2f), Px.dp(2f), shellP)
                label.textSize = Px.dp(8f); label.color = 0x66FFFFFF
                c.textCentered("FIREFLY", xyRect.centerX(), xyRect.bottom - Px.dp(10f), label)
            }
        }

        // D-pad
        val dcx = pad.centerX(); val dcy = pad.centerY(); val ds = pad.width()
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
        // A and B
        val r = aKey.width() / 2
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
        label.textSize = Px.dp(when { text.length <= 2 -> 13f; text.length <= 8 -> 10f; else -> 8f })
        while (label.measureText(text) > r.width() - Px.dp(14f) && label.textSize > Px.dp(5f)) label.textSize -= Px.dp(0.5f)
        label.color = textOn(col)
        c.textCentered(text, r.centerX(), r.centerY(), label)
    }

    // ---- touch: the D-pad holds a direction; A and B are taps; L is held; the XY pad flies (or traces)
    private var padPointer = -1
    private var selPointer = -1
    private var xyPointer = -1
    private fun aimFly(x: Float, y: Float) {
        var fx = ((x - xyRect.left) / xyRect.width()).coerceIn(0f, 1f); var fy = ((y - xyRect.top) / xyRect.height()).coerceIn(0f, 1f)
        if (inverted) { fx = 1f - fx; fy = 1f - fy }
        if (castRune >= 0 && throne) {
            val px = xyRect.left + fx * xyRect.width(); val py = xyRect.top + fy * xyRect.height()
            val last = stroke.lastOrNull()
            if (last == null || hypot(px - last[0], py - last[1]) > 2f) stroke += floatArrayOf(px, py)
            return
        }
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
            if (shown < talkLines[line].length) shown = talkLines[line].length.toFloat()
            else if (line < talkLines.size - 1) { line++; shown = 0f } else endTalk()
        } else if (phase == Phase.LOST) restart()
        else if (phase == Phase.WON) {
            if (roomId == 1 && !chestOpen && nearChest()) {
                chestOpen = true; hp = maxHp; bursts += Burst(120f, 58f)
                talking = true; talkLines = CHEST_LINES; line = 0; shown = 0f
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } else if (chestOpen) restart()
        }
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
                    exitRect.contains(x, y) -> { exitDown = true; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    selRect.contains(x, y) -> { selDown = true; selPointer = e.getPointerId(i); drawing.clear(); drawLen = 0f; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    startRect.contains(x, y) -> { startDown = true; zap(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    xyRect.contains(x, y) -> { xyPointer = e.getPointerId(i); stroke.clear(); aimFly(x, y) }
                    near(aKey, x, y) -> { aDown = true; pressA(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    near(bKey, x, y) -> { bDown = true; pressB(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    abs(x - pad.centerX()) < pad.width() * 0.8f && abs(y - pad.centerY()) < pad.height() * 0.8f -> { padPointer = e.getPointerId(i); steer(x, y) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val k = e.findPointerIndex(padPointer)
                if (k >= 0) steer(e.getX(k), e.getY(k))
                val q = e.findPointerIndex(xyPointer)
                if (q >= 0) aimFly(e.getX(q), e.getY(q))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(i)
                if (id == padPointer) { padPointer = -1; dirX = 0; dirY = 0 }
                else if (id == xyPointer) { xyPointer = -1; finishStroke() }
                else if (id == selPointer) { selPointer = -1; selDown = false }
                else { aDown = false; bDown = false; startDown = false }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (exitDown && e.actionMasked == MotionEvent.ACTION_UP && exitRect.contains(e.x, e.y)) onExit?.invoke()
                if (xyPointer >= 0) finishStroke()
                padPointer = -1; xyPointer = -1; selPointer = -1; dirX = 0; dirY = 0; aDown = false; bDown = false; exitDown = false; selDown = false; startDown = false
            }
        }
        return true
    }
}
