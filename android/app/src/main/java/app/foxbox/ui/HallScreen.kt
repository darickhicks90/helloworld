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
            "#@%&... MY FIVE EYES... THEY'RE GOING DIM...",
            "DON'T YOU DARE GO THROUGH THAT CRACK. AND DON'T TOUCH MY AXE...",
        )
        val CHEST_LINES = listOf(
            "INSIDE: GOLD, GEMS, AND A FOLDED NOTE...",
            "IT SAYS: 'STOP POKING MY EYE.'",
        )
        /** The water spirit in the treasury: the first meeting, and what it says after. */
        val SPIRIT_FIRST = listOf(
            "...AH. THE ONE WHO TOOK HIS EYES OFF ME.",
            "HE KEPT ME IN THIS BASIN FOR A HUNDRED WINTERS, POLISHING HIS GOLD.",
            "TAKE THIS, WITH MY THANKS. AND DRINK: THE WATER REMEMBERS YOU NOW.",
        )
        val SPIRIT_AGAIN = listOf(
            "THE WATER HEALS YOU. COME BACK WHENEVER YOU LIKE.",
            "LISTEN... THE IMPS ARE STILL OUT THERE. THEY MISS HIM. I DO NOT.",
            "TRACE YOUR RUNES SLOWLY. WATER IS PATIENT; SO SHOULD YOU BE.",
            "HIS AXE SUITS YOU. IT NEVER SUITED HIM.",
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
    private val yeti: Bitmap? = runCatching { c.assets.open("images/hall_yeti_axe.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    /** The creature in the title screen's detail, for the duel (facing you, a swipe, a lunge, a roar). */
    private val duelPose: Bitmap? = runCatching { c.assets.open("images/duel_stance.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    private val duelRects: List<Rect> = duelPose?.let { listOf(Rect(0, 0, it.width, it.height)) } ?: emptyList()
    private val t0 = System.nanoTime()
    private val rnd = kotlin.random.Random(System.nanoTime())
    /** Sound effects and rumbles; the finer scenery, baked per room. */
    private val sfx = HallSfx(c)
    private val art = HallRoomArt()

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
        1 to Room("TREASURY", 6, "S", listOf(pr(PILLAR, 44, 72, 60, 80), pr(PILLAR, 180, 72, 196, 80), pr(CHEST, 108, 62, 132, 72)), listOf(pool(160, 116, 200, 132))),
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
    private var maxHp = 100
    /** The traveller's finds, the duel when one is under way, and loot lying where an imp fell. */
    private val bag = Bag()
    private var battle: HallBattle? = null
    private var bagOpen = false; private var bagCursor = 0
    private class Drop(val x: Float, val y: Float, val room: Int, val item: Loot) { var life = 25f }
    private val drops = ArrayList<Drop>()
    private var stompCd = 0f
    private var sprintT = 0f; private var sprintCd = 0f; private var swiftT = 0f; private var interactCd = 0f
    /** Things in the rooms already searched (room:prop). */
    private val searched = HashSet<String>()
    private class Ring(val x: Float, val y: Float) { var life = 0.35f }
    private val rings = ArrayList<Ring>()
    /** The traveller's top speed (a quarter quicker than before); he chases at three quarters of it. */
    private val HERO_SPEED = 46f * 1.25f
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
    /** When he falls he perishes (fades to ash) and leaves his axe. */
    private var perishT = 0f; private var bossGone = false
    private val spiritX = 180f; private val spiritY = 112f
    private var spiritGift = false; private var spiritTalks = 0
    private fun nearSpirit() = roomId == 1 && hypot(hx + 8 - spiritX, hy + 12 - (spiritY + 8)) < 30f
    /** A: speak with the spirit. It heals and cleanses each time; the first time it gives a gift. */
    private fun speakToSpirit() {
        hp = maxHp; poisonT = 0f; paraT = 0f; invertT = 0f
        bursts += Burst(spiritX, spiritY - 6); bursts += Burst(hx + 8, hy + 8)
        val fk = friend; if (fk != null && friendHere) frHp = fk.maxHp
        talking = true; line = 0; shown = 0f
        if (!spiritGift) {
            spiritGift = true; talkLines = SPIRIT_FIRST
            val ex = Loot.values().filter { it.rarity == Rarity.EXOTIC && it !in bag.owned }
            msg = takeLoot(if (ex.isNotEmpty()) ex[rnd.nextInt(ex.size)] else rollLoot(0f)); msgT = 4f
        } else { talkLines = listOf(SPIRIT_AGAIN[spiritTalks % SPIRIT_AGAIN.size]); spiritTalks++; sfx.play(HallSfx.S.MEND, 0.7f) }
        sfx.rumbleSoft()
    }
    private val PERISH = 2.6f
    private val dais = RectF(84f, 34f, 156f, 86f)

    // spells: the rune he puts on your screen, and your attempt on the XY pad
    private val runeNames = listOf("BIND", "VENOM", "MIRROR", "DUEL")
    private val runes = listOf(
        listOf(0.5f to 0.06f, 0.94f to 0.9f, 0.06f to 0.9f, 0.5f to 0.06f),                        // a triangle: paralyses
        listOf(0.05f to 0.2f, 0.35f to 0.82f, 0.5f to 0.2f, 0.65f to 0.82f, 0.95f to 0.2f),         // a zig-zag: poisons
        listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f, 0.9f to 0.9f, 0.1f to 0.1f),               // an hourglass: mirrors
        RuneShapes.STAR,                                                                            // a star: pulls you into a duel
    )
    private val runeClosed = booleanArrayOf(true, false, true, true)
    private var castRune = -1; private var castT = 0f
    private val stroke = ArrayList<FloatArray>()
    private var lastScore = -1f; private var scoreT = 0f
    private var msg = ""; private var msgT = 0f
    private val NEED = 0.62f

    // imps, shots, holy lines, sparks, balls of light
    private class Minion(var x: Float, var y: Float, val room: Int) {
        var hp = 4; var cd = 2f + kotlin.random.Random.nextFloat() * 2f; var hurtT = 0f; var step = 0f
        var kx = 0f; var ky = 0f; var stun = 0f    // knocked back by a stomp
        var para = 0f; var weak = 0f; var conf = 0f; var wt = 0f; var wx = 0f; var wy = 0f
        /** Frozen solid; on fire; hexed (slowed); the firewall's bite; the last fire-dash that hit it. */
        var ice = 0f; var burn = 0f; var burnTick = 0f; var slow = 0f; var fireCd = 0f; var dash = -1
    }
    private class Shot(var x: Float, var y: Float, val vx: Float, val vy: Float, val boss: Boolean, val weak: Boolean) { var life = 5f }
    /** A holy line (style 0), or a companion's wall: 1 thorns, 2 moonlight, 3 ice, 4 stone. */
    private class Ward(val pts: FloatArray, val room: Int, val closed: Boolean, var life: Float = 10f, val style: Int = 0) { val max = life }
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

    // =====================================================================================
    // Experience: every point of damage done (yours and your companion's) counts. Each level
    // adds a little health, and the firefly grows (to four times its size at 20) and grows grander.
    // =====================================================================================
    private var lvl = 1; private var xp = 0
    private fun xpNeed(l: Int) = 15 + 10 * l
    private var levelFxT = 0f
    private fun gainXp(n: Int) {
        if (lvl >= 20 || n <= 0) return
        xp += n
        while (lvl < 20 && xp >= xpNeed(lvl)) {
            xp -= xpNeed(lvl); lvl++; maxHp += 2; hp = minOf(maxHp, hp + 15)
            levelFxT = 1.4f; msg = "LEVEL $lvl! YOUR FIREFLY GROWS"; msgT = 2.2f
            sfx.play(HallSfx.S.LEVEL_UP); sfx.rumbleFanfare()
        }
        if (lvl >= 20) xp = 0
    }
    private fun flySize() = 1f + 3f * (lvl - 1) / 19f
    private var impSfxT = 0f; private var burnSfxT = 0f
    /** Every hit on an imp comes through here: the damage, a flash, a blip, the experience. */
    private fun hitImp(m: Minion, n: Int, quiet: Boolean = false) {
        if (m.hp <= 0 || n <= 0) return
        gainXp(minOf(n, m.hp) * 3)
        m.hp -= n; m.hurtT = 0.3f
        if (!quiet && impSfxT <= 0f) { impSfxT = 0.06f; sfx.play(HallSfx.S.IMP_HIT, 0.6f) }
    }
    private fun say(s: String, t: Float) { msg = s; msgT = t }
    private fun takeLoot(l: Loot): String {
        val s = bag.add(l)
        when {
            l.consumable -> sfx.play(HallSfx.S.PICKUP)
            l.rarity == Rarity.EXOTIC -> { sfx.play(HallSfx.S.EXOTIC); sfx.rumbleFanfare() }
            l.rarity == Rarity.LEGENDARY -> { sfx.play(HallSfx.S.LEGENDARY); sfx.rumbleFanfare() }
            else -> sfx.play(HallSfx.S.PICKUP)
        }
        if (l == Loot.RUNECASTER && bag.wearing(l)) return RUNE_HINT
        return s
    }
    private val RUNE_HINT = "RUNECASTER! CIRCLE YOURSELF WITH L, THEN TRACE ONE OF SIX RUNES ON THE PAD"

    // ---- effects: abilities, spells, the firewall, falling stars and lightning
    private class Fx(val type: Int, val x: Float, val y: Float, val max: Float, val a: Float = 0f) { var life = max }
    private val fxs = ArrayList<Fx>()
    private val FX_NOVA = 1; private val FX_NOVA_STAR = 2; private val FX_MOON = 3; private val FX_FROST = 4; private val FX_AEGIS = 5
    private val FX_SHATTER = 6; private val FX_MEND = 7; private val FX_HEX = 8; private val FX_REVIVE = 9; private val FX_JOIN = 10
    private val FX_BIND = 11; private val FX_HASTE = 12; private val FX_HEALBEAM = 13; private val FX_WALL = 14; private val FX_STRIKE = 15
    /** A heal travelling from (x, y) to (x2, y2). */
    private class Beam(val x: Float, val y: Float, val toFriend: Boolean) { var life = 0.6f }
    private val beams = ArrayList<Beam>()
    private var flashT = 0f; private var hurtFlashT = 0f
    private class Fire(val x: Float, val y: Float, val room: Int) { var life = 4f }
    private val fires = ArrayList<Fire>()
    private class Star(val x: Float, val y: Float, val m: Minion?, var delay: Float, val bolt: Boolean, val dmg: Int) { var t = 0f }
    private val stars = ArrayList<Star>()
    private var dashId = 0; private var bossDash = -1
    private var bossBurn = 0f; private var bossBurnTick = 0f; private var bossFireCd = 0f
    private var zapCount = 0
    /** What the firefly is carrying back to you (with the Gleaner's Lamp). */
    private var carried: Loot? = null
    private val trail = FloatArray(24); private var trailN = 0; private var trailT = 0f

    // ---- the Runecaster: a circle drawn round yourself no longer blinks; it opens the rune book.
    // Trace one of six runes on the XY pad (before the time runs out) and it takes effect.
    private val SPELLS = listOf("HEX", "BIND", "STARFALL", "HASTE", "BULWARK", "MEND")
    private val SPELL_ABOUT = listOf("FOES WEAK", "FOES HELD", "FOES HIT", "ALL QUICK", "HALF HARM", "BIG HEAL")
    private val spellShapes = listOf(RuneShapes.ZIG, RuneShapes.HOUR, RuneShapes.BOLT, RuneShapes.ARCH, RuneShapes.COIL, RuneShapes.TRI)
    private val spellCdMax = floatArrayOf(14f, 16f, 18f, 15f, 16f, 22f)
    private val spellCd = FloatArray(6)
    private var runePromptT = 0f
    private val RUNE_TIME = 7f
    private var hasteT = 0f; private var bulwarkT = 0f
    private val tracing get() = (castRune >= 0 && throne) || runePromptT > 0f || battle?.wantsRune == true
    private fun openRuneBook() {
        if (spellCd.all { it > 0f }) { say("EVERY RUNE IS RECHARGING", 1.4f); sfx.play(HallSfx.S.HURT, 0.3f); return }
        runePromptT = RUNE_TIME; stroke.clear(); lastScore = -1f
        sfx.play(HallSfx.S.SPELL); sfx.rumbleSoft()
    }
    /** A traced rune: the best match among those ready, if it's good enough. */
    private fun runeStroke() {
        if (stroke.size < 4) { stroke.clear(); return }
        var best = -1; var bs = 0f
        for (k in 0 until 6) { if (spellCd[k] > 0f) continue; val sc0 = similarityTo(stroke, spellShapes[k], RuneShapes.closed(spellShapes[k])); if (sc0 > bs) { bs = sc0; best = k } }
        lastScore = bs; scoreT = 1.4f; stroke.clear()
        if (best < 0 || bs < NEED) { say("THE RUNE FIZZLES (${(bs * 100).toInt()}%)", 1.2f); sfx.play(HallSfx.S.HURT, 0.3f); return }
        runePromptT = 0f; spellCd[best] = spellCdMax[best]
        castSpell(best)
    }
    private fun castSpell(k: Int) {
        val px = hx + 8; val py = hy + 9
        val fk = friend
        when (k) {
            0 -> {   // HEX: every foe in the room weakened (no bite, wild aim) and slowed
                for (m in minions) if (m.room == roomId) { m.weak = 7f; m.slow = 7f }
                if (throne && stage != Stage.SLAM) bossWeak = maxOf(bossWeak, 5f)
                fxs += Fx(FX_HEX, px, py, 0.7f); flashT = 0.12f
                say("HEX! THEY WITHER", 1.4f); sfx.play(HallSfx.S.HEX)
            }
            1 -> {   // BIND: every foe held fast
                for (m in minions) if (m.room == roomId) { m.para = 3.5f; fxs += Fx(FX_BIND, m.x, m.y, 0.6f) }
                if (throne && stage != Stage.SLAM) { bossPara = maxOf(bossPara, 2f); fxs += Fx(FX_BIND, bx, by - 20, 0.8f) }
                flashT = 0.1f; say("BIND! THEY CAN'T MOVE", 1.4f); sfx.play(HallSfx.S.BOSS_RUNE)
            }
            2 -> {   // STARFALL: a star on every foe
                var d = 0f
                for (m in minions) if (m.room == roomId) { stars += Star(m.x, m.y, m, d, false, 4); d += 0.07f }
                if (throne && stage != Stage.SLAM) for (j in 0 until 3) stars += Star(bx - 8 + j * 8, by - 20 - j * 4, null, d + j * 0.1f, false, 8)
                say("STARFALL!", 1.4f); sfx.play(HallSfx.S.STARFALL); sfx.rumbleBig()
            }
            3 -> {   // HASTE: you and your companion quicker for a while
                hasteT = 9f; swiftT = maxOf(swiftT, 9f)
                fxs += Fx(FX_HASTE, px, py, 0.8f); if (fk != null && friendHere) fxs += Fx(FX_HASTE, frX, frY - 8, 0.8f)
                say("HASTE!", 1.4f); sfx.play(HallSfx.S.SPRINT)
            }
            4 -> {   // BULWARK: you and your companion take half damage
                bulwarkT = 9f
                fxs += Fx(FX_AEGIS, px, py - 4, 0.6f); if (fk != null && friendHere) fxs += Fx(FX_AEGIS, frX, frY - 10, 0.6f)
                say("BULWARK! HALF DAMAGE", 1.4f); sfx.play(HallSfx.S.STOMP, 0.7f)
            }
            else -> {   // MEND: a great heal for you both, and every ailment lifted
                hp = minOf(maxHp, hp + 60); poisonT = 0f; paraT = 0f; invertT = 0f
                fxs += Fx(FX_MEND, px, py, 1.2f)
                if (fk != null && friendHere) { frHp = minOf(fk.maxHp, frHp + fk.maxHp / 2); fxs += Fx(FX_MEND, frX, frY - 8, 1.2f) }
                say("MEND! +60", 1.4f); sfx.play(HallSfx.S.MEND)
            }
        }
        sfx.rumbleSoft()
    }

    // =====================================================================================
    // The companion: one of four, found waiting somewhere in the hall. It keeps 3 to 12 steps
    // from you, patrolling about; fights imps near you but keeps well clear of him, backs off when
    // hurt, and has one showy ability on a long cool-down. It can be knocked out, and lies where
    // it fell until a Spirit Bloom brings it back.
    // =====================================================================================
    private enum class Friend(val label: String, val maxHp: Int, val speed: Float, val dmg: Int, val atkCd: Float, val flies: Boolean, val skill: String, val skillCd: Float) {
        BRAMBLE("BRAMBLE", 180, 1.0f, 3, 5.5f, false, "THORN NOVA", 16f),
        PIP("PIP", 135, 1.15f, 3, 5f, true, "MOONFLASH", 16f),
        TOVE("TOVE", 165, 1.1f, 3, 5f, false, "FROST BREATH", 15f),
        GRUMBLE("GRUMBLE", 270, 0.85f, 4, 6.5f, false, "STONE AEGIS", 18f),
    }
    /** Each companion's support: what its wall is made of, what its heal looks like. */
    private fun wallName(k: Friend) = when (k) { Friend.BRAMBLE -> "THORN HEDGE"; Friend.PIP -> "MOON VEIL"; Friend.TOVE -> "ICE WALL"; Friend.GRUMBLE -> "STONE WALL" }
    private fun healName(k: Friend) = when (k) { Friend.BRAMBLE -> "HEALING SAP"; Friend.PIP -> "MOTE OF LIGHT"; Friend.TOVE -> "COOL MIST"; Friend.GRUMBLE -> "EARTH'S MEND" }
    private var frWallCd = 4f; private var frHealCd = 3f; private var frGuardCd = 0f
    /** The friend's size on screen (its 12 x 11 sprite, drawn twice as big). */
    private val FS = 2f
    private val STEP = 6f
    private var friend: Friend? = null
    /** 0 waiting to be found, 1 with you, 2 knocked out. */
    private var frState = 0
    private var frRoom = 3; private var frX = 0f; private var frY = 0f; private var frHp = 0
    private var frTx = 0f; private var frTy = 0f; private var frRepick = 0f; private var frAtk = 0f; private var frSkill = 6f
    private var frHurt = 0f; private var frInv = 0f; private var frLunge = 0f; private var frLx = 0f; private var frLy = 0f
    private var frFace = 1; private var frStep = 0f; private var frMoving = false
    private var tauntT = 0f; private var shieldHits = 0; private var shieldT = 0f
    private val friendHere get() = friend != null && frState == 1 && frRoom == roomId

    private fun spawnFriend() {
        val all = Friend.values(); val k = all[rnd.nextInt(all.size)]
        friend = k; frState = 0; frHp = k.maxHp; frSkill = 6f; tauntT = 0f; shieldHits = 0; frWallCd = 4f; frHealCd = 3f
        frRoom = intArrayOf(3, 5, 6, 7, 8)[rnd.nextInt(5)]
        val r = rooms.getValue(frRoom)
        frX = 120f; frY = 112f
        for (i in 0 until 80) {
            val x = 30f + rnd.nextFloat() * (W - 60f); val y = 50f + rnd.nextFloat() * (H - 70f)
            val clash = r.props.any { x + 8 > it.foot.left && x - 8 < it.foot.right && y + 3 > it.foot.top - 6 && y - 8 < it.foot.bottom } ||
                r.pools.any { x + 6 > it.left && x - 6 < it.right && y + 2 > it.top && y - 6 < it.bottom }
            if (!clash) { frX = x; frY = y; break }
        }
        frTx = frX; frTy = frY
    }
    private fun frSolid(x: Float, y: Float): Boolean {
        if (x - 7 < 6 || x + 7 > W - 6 || y - 4 < 36 || y > H - 4) return true
        if (friend?.flies == true) return false
        return solid(x - 7, y - 4, x + 7, y)
    }
    private fun frMove(tx: Float, ty: Float, sp: Float, dt: Float) {
        val dx = tx - frX; val dy = ty - frY; val d = hypot(dx, dy)
        if (d < 1.5f) { frMoving = false; return }
        val st = minOf(d, sp * dt)
        val nx = frX + dx / d * st; val ny = frY + dy / d * st
        var moved = false
        if (!frSolid(nx, frY)) { frX = nx; moved = true }
        if (!frSolid(frX, ny)) { frY = ny; moved = true }
        if (abs(dx) > 0.5f) frFace = if (dx > 0) 1 else -1
        frMoving = moved
        if (moved) frStep += dt else frRepick = 0f
    }
    private fun frPatrolPoint(px: Float, py: Float, near: Float, far: Float) {
        for (i in 0 until 24) {
            val a = rnd.nextFloat() * 6.2832f; val d = near + rnd.nextFloat() * (far - near)
            val x = px + cos(a) * d; val y = py + sin(a) * d * 0.8f
            if (!frSolid(x, y) && !(throne && phase == Phase.FIGHT && hypot(x - bx, y - by) < 52f)) { frTx = x; frTy = y; return }
        }
        frTx = px; frTy = (py + 18f).coerceAtMost(H - 8f)
    }
    private fun placeFriendNear() {
        val px = hx + 8; val py = hy + 13
        val sx = if (px < W / 2f) 1f else -1f; val sy = if (py < H / 2f + 10) 1f else -1f
        for (r in intArrayOf(16, 22, 28, 10)) {
            val x = px + sx * r; val y = py + sy * r * 0.4f
            if (!frSolid(x, y)) { frX = x; frY = y; frTx = x; frTy = y; return }
        }
        frX = px.coerceIn(14f, W - 14f); frY = py.coerceIn(40f, H - 8f); frTx = frX; frTy = frY
    }
    private fun hurtFriend(n: Int) {
        val k = friend ?: return
        if (frState != 1 || frInv > 0f || phase != Phase.FIGHT) return
        frHp -= if (bulwarkT > 0f) (n + 1) / 2 else n; frInv = 0.8f; frHurt = 0.3f
        sfx.play(HallSfx.S.FRIEND_HURT, 0.7f)
        if (frHp <= 0) { frHp = 0; frState = 2; say("${k.label} IS KNOCKED OUT!", 2.4f); sfx.play(HallSfx.S.FRIEND_DOWN); sfx.rumbleSoft() }
    }
    private fun reviveFriend() {
        val k = friend
        when {
            k == null || frState == 0 -> say("NO COMPANION YET", 1.4f)
            frState == 1 -> say("${k.label} IS FINE", 1.4f)
            frRoom != roomId -> say("${k.label} LIES IN ANOTHER ROOM", 1.8f)
            else -> {
                bag.blooms--; frState = 1; frHp = k.maxHp / 2; frInv = 2f; frSkill = 3f
                fxs += Fx(FX_REVIVE, frX, frY, 1.2f); say("${k.label} RISES AGAIN!", 1.8f)
                sfx.play(HallSfx.S.REVIVE); sfx.rumbleFanfare(); bagOpen = false
            }
        }
    }

    private fun updateFriend(dt: Float) {
        val k = friend ?: return
        frHurt -= dt; frInv -= dt; frLunge -= dt; tauntT -= dt; frAtk -= dt * (if (hasteT > 0f) 1.6f else 1f); frWallCd -= dt; frHealCd -= dt; frGuardCd -= dt
        if (shieldT > 0f) { shieldT -= dt; if (shieldT <= 0f) shieldHits = 0 }
        if (talking) return
        val px = hx + 8; val py = hy + 13
        if (frState == 0) {
            if (frRoom == roomId && hypot(frX - px, frY - py) < 26f && (phase == Phase.FIGHT || phase == Phase.WON)) {
                frState = 1; frSkill = 3f
                bag.add(Loot.BLOOM)
                say("${k.label} JOINS YOU! (${k.skill}) AND BRINGS A SPIRIT BLOOM", 3f)
                sfx.play(HallSfx.S.FRIEND_JOIN); sfx.rumbleSoft()
                fxs += Fx(FX_JOIN, frX, frY - 6, 0.8f)
            }
            return
        }
        if (frState != 1 || frRoom != roomId) return
        frSkill -= dt
        val dHero = hypot(frX - px, frY - py)
        val low = frHp < k.maxHp * 0.4f
        val sp = HERO_SPEED * SLOW * k.speed * (if (hasteT > 0f) 1.3f else 1f)
        val fighting = phase == Phase.FIGHT
        if (fighting) support(k, px, py)
        val bossNear = fighting && throne && stage != Stage.SLAM && hypot(frX - bx, frY - by) < 50f
        var target: Minion? = null; var td = Float.MAX_VALUE
        if (fighting) for (m in minions) if (m.room == roomId) { val d = hypot(m.x - frX, m.y - frY); if (d < td) { td = d; target = m } }
        val t = target
        when {
            // cautious: he is kept well clear of
            bossNear -> { val ax = frX - bx; val ay = frY - by; val d = hypot(ax, ay).coerceAtLeast(1f); frMove(frX + ax / d * 30f, frY + ay / d * 30f, sp * 1.3f, dt) }
            // never more than twelve steps behind
            dHero > 12 * STEP -> {
                frRepick -= dt
                if (frRepick <= 0f || hypot(frTx - px, frTy - py) > 9 * STEP) { frPatrolPoint(px, py, 4 * STEP, 7 * STEP); frRepick = 0.8f }
                frMove(frTx, frTy, sp * 1.5f, dt)
            }
            // now and then, when an imp is right on you and the blow is ready: one heavy strike
            t != null && !low && frAtk <= 0f && hypot(t.x - px, t.y - py) < 46f && td < 70f -> {
                if (td > 12f) frMove(t.x, t.y + 3, sp * 1.2f, dt)
                else {
                    frAtk = k.atkCd; frLunge = 0.25f; frLx = t.x; frLy = t.y
                    hitImp(t, k.dmg); bursts += Burst(t.x, t.y); fxs += Fx(FX_STRIKE, t.x, t.y - 2, 0.35f, frFace.toFloat())
                    t.kx = (t.x - frX) * 6f; t.ky = (t.y - frY) * 6f; t.stun = 0.5f
                    sfx.play(HallSfx.S.STOMP, 0.5f); shakeT = maxOf(shakeT, 0.08f)
                    killDead()
                }
            }
            // otherwise patrol about you, between three and twelve steps out (closer when hurt)
            else -> {
                frRepick -= dt
                val near = if (low) 3 * STEP else 3 * STEP + 4; val far = if (low) 6 * STEP else 10 * STEP
                val off = hypot(frTx - px, frTy - py)
                if (frRepick <= 0f || off > far + 10 || off < near - 4 || dHero < 3 * STEP) { frPatrolPoint(px, py, near, far); frRepick = 1.5f + rnd.nextFloat() * 1.8f }
                frMove(frTx, frTy, sp * (if (low) 1.1f else 0.6f), dt)
            }
        }
        if (fighting && frSkill <= 0f && wantsSkill(k, px, py)) useSkill(k, px, py)
    }
    /**
     * Support, which is most of what a companion does: walls thrown up between you and whatever's
     * coming, small heals carried to you on a stream of light, and (Pip) shots plucked from the air.
     */
    private fun support(k: Friend, px: Float, py: Float) {
        // a small heal for you (or itself) when either is hurt
        if (frHealCd <= 0f && (hp < maxHp - 8 || frHp < k.maxHp * 0.7f)) {
            frHealCd = 7.5f
            if (hp < maxHp - 8) { hp = minOf(maxHp, hp + 8); beams += Beam(frX, frY - 8, false); say("${healName(k)} +8", 1f) }
            if (frHp < k.maxHp) { frHp = minOf(k.maxHp, frHp + 10); fxs += Fx(FX_MEND, frX, frY - 8, 0.7f) }
            sfx.play(HallSfx.S.ORB, 0.6f)
        }
        // a wall between you and the nearest danger
        if (frWallCd <= 0f) {
            var tx = 0f; var ty = 0f; var td = 64f
            for (m in minions) if (m.room == roomId && m.para <= 0f && m.ice <= 0f) { val d = hypot(m.x - px, m.y - py); if (d < td) { td = d; tx = m.x; ty = m.y } }
            if (throne && stage == Stage.CHASE) { val d = hypot(bx - px, by - 6 - py); if (d < 80f && d < td + 20f) { td = d; tx = bx; ty = by - 6 } }
            for (sh in shots) { val d = hypot(sh.x - px, sh.y - py); if (d < 56f && d < td) { td = d; tx = sh.x; ty = sh.y } }
            if (td < 64f) {
                frWallCd = 9f
                val d = td.coerceAtLeast(1f); val ux = (tx - px) / d; val uy = (ty - py) / d
                val cx = px + ux * minOf(18f, d * 0.6f); val cy = py + uy * minOf(18f, d * 0.6f)
                val nx = -uy; val ny = ux; val half = 16f
                val seg = FloatArray(10) { i -> val t = (i / 2) / 4f * 2f - 1f; if (i % 2 == 0) cx + nx * half * t + ux * (1 - t * t) * 3f else cy + ny * half * t + uy * (1 - t * t) * 3f }
                val style = when (k) { Friend.BRAMBLE -> 1; Friend.PIP -> 2; Friend.TOVE -> 3; Friend.GRUMBLE -> 4 }
                wards += Ward(seg, roomId, false, 5.5f, style)
                fxs += Fx(FX_WALL, cx, cy, 0.5f, style.toFloat())
                say(wallName(k) + "!", 1f)
                sfx.play(if (k == Friend.TOVE) HallSfx.S.SHATTER else HallSfx.S.STOMP, 0.5f)
            }
        }
        // Pip plucks a shot out of the air now and then
        if (k == Friend.PIP && frGuardCd <= 0f) {
            shots.firstOrNull { hypot(it.x - px, it.y - py) < 26f }?.let { sh -> shots.remove(sh); frGuardCd = 2.5f; bursts += Burst(sh.x, sh.y); fxs += Fx(FX_SHATTER, sh.x, sh.y, 0.4f) }
        }
    }
    private fun wantsSkill(k: Friend, px: Float, py: Float): Boolean {
        val near = minions.count { it.room == roomId && hypot(it.x - frX, it.y - frY) < 46f }
        val onYou = minions.any { it.room == roomId && hypot(it.x - px, it.y - py) < 28f }
        val bossClose = throne && stage == Stage.CHASE && hypot(bx - px, by - py) < 70f
        return when (k) {
            Friend.PIP -> near >= 2 || onYou || shots.count { hypot(it.x - px, it.y - py) < 70f } >= 2 || bossClose
            Friend.GRUMBLE -> (hp < maxHp * 0.6f && (near >= 1 || onYou || bossClose)) || near >= 3
            else -> near >= 2 || onYou || bossClose
        }
    }
    private fun wrapAngle(a: Float): Float { var d = a; while (d > 3.1416f) d -= 6.2832f; while (d < -3.1416f) d += 6.2832f; return d }
    private fun useSkill(k: Friend, px: Float, py: Float) {
        frSkill = k.skillCd
        sfx.play(HallSfx.S.FRIEND_SKILL)
        say("${k.label}: ${k.skill}!", 1.2f)
        when (k) {
            Friend.BRAMBLE -> {
                fxs += Fx(FX_NOVA, frX, frY - 4, 0.55f); shakeT = 0.15f
                for (m in minions) if (m.room == roomId) {
                    val dx = m.x - frX; val dy = m.y - frY; val d = hypot(dx, dy)
                    if (d < 42f) { hitImp(m, 2, true); m.stun = 1.2f; val dd = d.coerceAtLeast(1f); m.kx = dx / dd * 140f; m.ky = dy / dd * 140f }
                }
                if (throne && stage != Stage.SLAM && hypot(bx - frX, by - frY) < 48f) damageBoss(6)
            }
            Friend.PIP -> {
                fxs += Fx(FX_MOON, frX, frY - 10, 0.6f); flashT = 0.25f
                shots.removeAll { s0 -> (hypot(s0.x - frX, s0.y - frY) < 100f).also { if (it) bursts += Burst(s0.x, s0.y) } }
                for (m in minions) if (m.room == roomId && hypot(m.x - frX, m.y - frY) < 85f) m.conf = 4f
                if (throne && stage != Stage.SLAM && hypot(bx - frX, by - frY) < 85f) bossConf = maxOf(bossConf, 2f)
            }
            Friend.TOVE -> {
                val near = minions.filter { it.room == roomId }.minByOrNull { hypot(it.x - frX, it.y - frY) }
                val a = when {
                    near != null -> atan2(near.y - frY, near.x - frX)
                    throne && stage == Stage.CHASE -> atan2(by - 8 - frY, bx - frX)
                    else -> if (frFace > 0) 0f else 3.1416f
                }
                frFace = if (cos(a) >= 0f) 1 else -1
                fxs += Fx(FX_FROST, frX + frFace * 4, frY - 6, 0.7f, a)
                for (m in minions) if (m.room == roomId) {
                    val dx = m.x - frX; val dy = m.y - frY
                    if (hypot(dx, dy) < 60f && abs(wrapAngle(atan2(dy, dx) - a)) < 0.55f) { m.ice = 2.6f; hitImp(m, 1, true) }
                }
                if (throne && stage != Stage.SLAM) {
                    val dx = bx - frX; val dy = by - 10 - frY
                    if (hypot(dx, dy) < 64f && abs(wrapAngle(atan2(dy, dx) - a)) < 0.6f) { damageBoss(4); bossPara = maxOf(bossPara, 0.8f) }
                }
                sfx.play(HallSfx.S.SHATTER, 0.5f)
            }
            Friend.GRUMBLE -> {
                shieldHits = 2; shieldT = 10f; tauntT = 4f
                fxs += Fx(FX_AEGIS, px, py - 4, 0.6f); shakeT = 0.12f
                sfx.play(HallSfx.S.STOMP, 0.8f)
            }
        }
        killDead()
    }

    // ---- the companions: original 12×11 critters, two frames each, facing right
    private val friendPal = mapOf(
        'K' to INK, 'G' to 0xFF5A8A3A.toInt(), 'g' to 0xFF8AB85A.toInt(), 'b' to 0xFFB08A60.toInt(), 'E' to INK, 'N' to 0xFF3A2A2A.toInt(),
        'O' to 0xFF8A6A4A.toInt(), 'o' to 0xFFC8A880.toInt(), 'W' to WHITE, 'Y' to 0xFFF8D048.toInt(),
        'F' to 0xFFE8F0F8.toInt(), 'f' to 0xFFB8D0E8.toInt(), 'C' to 0xFF68C8E8.toInt(),
        'S' to 0xFF9A9AAA.toInt(), 's' to 0xFF6A6A7A.toInt(), 'l' to 0xFFC8C8D4.toInt(), 'M' to 0xFF7C9C74.toInt(),
    )
    private val friendRows = listOf(
        listOf(
            listOf("...g.g.g....", "..gGgGgGg...", ".gGGgGGgGg..", "gGGGGGGGGbb.", "GGgGGGGGbbbb", "GGGGGGGbbEbb", "GgGGGGGbbbbN", ".GGGGGGbbbb.", "..KbbbbbbK..", "..Kb.KK.bK..", "..KK....KK.."),
            listOf("...g.g.g....", "..gGgGgGg...", ".gGGgGGgGg..", "gGGGGGGGGbb.", "GGgGGGGGbbbb", "GGGGGGGbbEbb", "GgGGGGGbbbbN", ".GGGGGGbbbb.", "..KbbbbbbK..", ".Kb.K..Kb.K.", ".KK......KK."),
        ),
        listOf(
            listOf("............", "KK........KK", "KOK.KKKK.KOK", "KOOKOOOOKOOK", ".KOOWWOWWOK.", ".KOWYKWYKWK.", ".KOOWWOWWOK.", ".KOOOoNoOOK.", "..KOoWWWoK..", "..KOoWWWoK..", "...KOoooK...", "....K..K...."),
            listOf("............", "....KKKK....", "...KOOOOK...", "..KOOWWOWWK.", ".KKOWYKWYKWK", "KOKOOWWOWWOK", "KOKOOoNoOOKO", "KOOKOoWWoKOK", ".KK.KoWWoK.K", "....KOoooK..", ".....K..K...", "............"),
        ),
        listOf(
            listOf(".......K..K.", "......KfKKfK", "KK....KFFFFK", "KCK...KFEFFN", "KCfK.KFFFFK.", ".KffKKFFFFK.", "..KfFFFFFfK.", "..KFFFFFFfK.", "..KFfFFFFfK.", "..KfK..KfK..", "..KK...KK..."),
            listOf(".......K..K.", "......KfKKfK", ".KK...KFFFFK", "KCCK..KFEFFN", "KCfK.KFFFFK.", ".KffKKFFFFK.", "..KfFFFFFfK.", "..KFFFFFFfK.", "..KFfFFFFfK.", ".KfK....KfK.", ".KK......KK."),
        ),
        listOf(
            listOf("...KKKKKK...", "..KlllSSSK..", "..KlElSEsK..", "..KSSSSSSK..", ".KKKSSSSKKK.", "KlSSKSSKSSsK", "KlSSSSSSSSsK", "KSMSSSSSSMsK", ".KKSSSSSSKK.", "..KSsK.KSsK.", "..KKKK.KKKK."),
            listOf("...KKKKKK...", "..KlllSSSK..", "..KlElSEsK..", "..KSSSSSSK..", ".KKKSSSSKKK.", "KlSSKSSKSSsK", "KlSSSSSSSSsK", "KSMSSSSSSMsK", ".KKSSSSSSKK.", ".KSsK...KSsK", ".KKKK...KKKK"),
        ),
    )
    private val friendSprites = friendRows.mapIndexed { i, frames -> frames.map { Sprite(it, if (i == 3) friendPal + ('E' to 0xFF68E8F8.toInt()) else friendPal, outline = null) } }
    private val friendFlash = friendRows.map { frames -> frames.map { rows -> Sprite(rows.map { r -> r.map { ch -> if (ch == '.') '.' else 'W' }.joinToString("") }, friendPal, outline = null) } }

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
            val mine = wards.filter { it.room == roomId && it.style == 0 }
            if (mine.size >= 2) wards.remove(mine.first())
            wards += Ward(pts, roomId, closed, if (bag.wearing(Loot.WARD_STONE)) 16f else 10f)
            val selfIn = closed && inside(pts, hx + 8, hy + 12)
            val friendIn = closed && friendHere && inside(pts, frX, frY - 6)
            if (selfIn && phase == Phase.FIGHT) {
                wards.removeAt(wards.size - 1)
                if (bag.wearing(Loot.RUNECASTER)) openRuneBook()
                else { teleport(); if (friendIn) { blinkFriend(true) } }
            } else if (friendIn && (phase == Phase.FIGHT || phase == Phase.WON)) {
                // a circle round your companion: it's whisked to safety, near you
                wards.removeAt(wards.size - 1)
                blinkFriend(false)
            } else if (closed && phase == Phase.FIGHT) {
                var hit = 0
                for (m in minions) if (m.room == roomId && inside(pts, m.x, m.y)) { curse(m); hit++ }
                killDead()
                if (throne && stage != Stage.SLAM && inside(pts, bx, by - 20)) curseBoss()
                else if (hit > 0) { msg = if (hit == 1) "CONSECRATED!" else "CONSECRATED x$hit!"; msgT = 1.2f }
                if (hit > 0) sfx.play(HallSfx.S.SPELL, 0.6f)
            }
        }
        drawing.clear(); drawLen = 0f
    }
    /** Your companion blinks to the safest spot near you (with you, if you blinked together). */
    private fun blinkFriend(withYou: Boolean) {
        val k = friend ?: return
        bursts += Burst(frX, frY - 8)
        val px = hx + 8; val py = hy + 13
        var bestX = frX; var bestY = frY; var best = -1f
        repeat(60) {
            val a = rnd.nextFloat() * 6.2832f; val d = (if (withYou) 14f else 18f) + rnd.nextFloat() * 22f
            val x = px + cos(a) * d; val y = py + sin(a) * d * 0.7f
            if (frSolid(x, y)) return@repeat
            var near = Float.MAX_VALUE
            for (m in minions) if (m.room == roomId) near = minOf(near, hypot(m.x - x, m.y - y))
            if (throne && phase == Phase.FIGHT) near = minOf(near, hypot(bx - x, by - y) * 0.8f)
            if (near > best) { best = near; bestX = x; bestY = y }
        }
        frX = bestX; frY = bestY; frTx = frX; frTy = frY; frInv = maxOf(frInv, 1f)
        bursts += Burst(frX, frY - 8); fxs += Fx(FX_JOIN, frX, frY - 8, 0.6f)
        if (!withYou) { say("${k.label} BLINKS TO SAFETY", 1.2f); sfx.play(HallSfx.S.BLINK) }
    }
    private val curseK get() = if (bag.wearing(Loot.WARD_STONE)) 1.5f else 1f
    private fun curse(m: Minion) {
        when (rnd.nextInt(4)) {
            0 -> m.para = 5f * curseK
            1 -> m.weak = 6f * curseK
            2 -> m.conf = 6f * curseK
            else -> { hitImp(m, 3); bursts += Burst(m.x, m.y) }
        }
    }
    /** The safest free spot: as far as can be from every imp, shot and the creature. */
    private fun teleport() {
        var bestX = hx; var bestY = hy; var best = -1f
        repeat(80) {
            val x = 8f + rnd.nextFloat() * (W - 32f); val y = 26f + rnd.nextFloat() * (H - 46f)
            if (blocked(x, y)) return@repeat
            var near = Float.MAX_VALUE
            for (m in minions) if (m.room == roomId) near = minOf(near, hypot(m.x - (x + 8), m.y - (y + 9)))
            for (sh in shots) near = minOf(near, hypot(sh.x - (x + 8), sh.y - (y + 9)) * 1.5f)
            if (throne && phase == Phase.FIGHT) near = minOf(near, hypot(bx - (x + 8), by - (y + 12)))
            if (near > best) { best = near; bestX = x; bestY = y }
        }
        bursts += Burst(hx + 8, hy + 9)
        hx = bestX; hy = bestY; invulnT = 1f
        if (bag.wearing(Loot.FEATHER_CLOAK)) { invulnT = 2f; swiftT = 3f }
        bursts += Burst(hx + 8, hy + 9)
        msg = "BLINK!"; msgT = 1f
        sfx.play(HallSfx.S.BLINK); sfx.rumbleSoft()
    }

    /** Imps at 0 go; now and then one leaves something behind. */
    private fun killDead() {
        val it = minions.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if (m.hp > 0) continue
            it.remove()
            gainXp(5); sfx.play(HallSfx.S.IMP_DIE, 0.7f)
            // loot is rare: about one imp in twenty (one in ten with the lodestone), and often just a herb
            if (rnd.nextFloat() < (if (bag.wearing(Loot.LODESTONE)) 0.1f else 0.05f)) drops += Drop(m.x, m.y, m.room, rollLoot(0.4f))
        }
    }
    /** A find: a herb (or, with a companion along, sometimes a spirit bloom), else something from a tier chosen by rarity. */
    private fun rollLoot(herbChance: Float): Loot {
        if (rnd.nextFloat() < herbChance) return if (friend != null && frState != 0 && rnd.nextFloat() < 0.3f) Loot.BLOOM else Loot.HERB
        val avail = Loot.values().filter { it.dropped && !it.consumable && it !in bag.owned }
        if (avail.isEmpty()) return Loot.HERB
        val tiers = Rarity.values().filter { r -> avail.any { it.rarity == r } }
        var roll = rnd.nextFloat() * tiers.sumOf { it.weight.toDouble() }.toFloat()
        var tier = tiers.last()
        for (t in tiers) { roll -= t.weight; if (roll <= 0f) { tier = t; break } }
        val pool = avail.filter { it.rarity == tier }
        return pool[rnd.nextInt(pool.size)]
    }
    /** B: a ground stomp that throws nearby imps back (and stuns them a moment); with his axe it cleaves them too. */
    private fun stomp() {
        if (phase != Phase.FIGHT || stompCd > 0f) return
        stompCd = 1.2f
        val cx = hx + 8; val cy = hy + 13
        rings += Ring(cx, cy); shakeT = 0.15f
        var shattered = 0
        for (m in minions) {
            if (m.room != roomId) continue
            val dx = m.x - cx; val dy = m.y - cy; val d = hypot(dx, dy)
            if (d < 40f) {
                val k = (40f - d) / 40f * 150f + 60f; val dd = d.coerceAtLeast(1f); m.kx = dx / dd * k; m.ky = dy / dd * k; m.stun = 0.6f
                // the Frost Ring freezes them solid; stamp on one already frozen and it shatters
                if (bag.wearing(Loot.FROST_RING)) {
                    if (m.ice > 0f) { hitImp(m, 3, true); m.ice = 0f; shattered++; fxs += Fx(FX_SHATTER, m.x, m.y, 0.4f) } else m.ice = 3f
                }
                if (bag.wearing(Loot.FIVE_EYED_AXE)) hitImp(m, 2)
            }
        }
        if (shattered > 0) { sfx.play(HallSfx.S.SHATTER); say(if (shattered == 1) "SHATTERED!" else "SHATTERED x$shattered!", 1.2f) }
        killDead()
        sfx.play(HallSfx.S.STOMP); sfx.rumbleSoft()
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
        if (friend != null && frState == 1) { frRoom = roomId; placeFriendNear() }
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
        if (shieldHits > 0) {
            shieldHits--; invulnT = 0.7f; fxs += Fx(FX_SHATTER, hx + 8, hy + 8, 0.4f); sfx.play(HallSfx.S.SHATTER, 0.7f); sfx.rumbleSoft()
            if (shieldHits == 0) say("THE STONE AEGIS CRUMBLES", 1.2f)
            return
        }
        val nn = if (bulwarkT > 0f) (n + 1) / 2 else n
        hp -= if (bag.wearing(Loot.IRON_SKIN)) (nn * 0.8f).toInt() else nn; invulnT = 1f; hurtFlashT = 0.3f
        if (n >= 25) { sfx.play(HallSfx.S.HURT_BIG); sfx.rumbleBig() } else { sfx.play(HallSfx.S.HURT); sfx.rumbleHit() }
        if (hp <= 0) { hp = 0; phase = Phase.LOST; castRune = -1; msg = ""; paraT = 0f; poisonT = 0f; invertT = 0f; stroke.clear(); sfx.play(HallSfx.S.FRIEND_DOWN); sfx.rumbleFade() }
    }

    private fun damageBoss(n: Int) {
        if (phase != Phase.FIGHT || stage == Stage.SLAM) return
        gainXp(minOf(n, maxOf(0, bossHp)))
        bossHp -= n; bossHurtT = 0.5f
        if (stage == Stage.DAIS && bossHp <= LAST) {
            // his last stand: he slams the wall behind him, then comes for you
            bossHp = LAST; stage = Stage.SLAM; slamT = 0f; castRune = -1; stroke.clear(); shots.clear()
            msg = "HE'S FURIOUS!"; msgT = 1.6f; sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleBig()
        } else if (stage == Stage.CHASE && bossHp <= 0) {
            bossHp = 0; phase = Phase.WON; minions.clear(); shots.clear(); castRune = -1
            paraT = 0f; poisonT = 0f; invertT = 0f; bossPara = 0f; bossConf = 0f; bossWeak = 0f
            talking = true; talkLines = DEFEAT; line = 0; shown = 0f
            bossBurn = 0f; fires.clear(); stars.clear()
            sfx.play(HallSfx.S.COUNTER); sfx.rumbleBig()
        }
    }

    private fun restart() {
        hp = maxHp; invulnT = 0f; paraT = 0f; poisonT = 0f; invertT = 0f
        perishT = 0f; bossGone = false; spiritGift = false; spiritTalks = 0
        bossHp = BOSS_MAX; stage = Stage.DAIS; bx = 120f; by = 64f; bossPara = 0f; bossWeak = 0f; bossConf = 0f
        bossShotT = 3f; spellT = 6f; summonT = 4f; castRune = -1; crackOpen = false; chestOpen = false
        minions.clear(); shots.clear(); wards.clear(); drawing.clear(); arcs.clear(); bursts.clear(); orbs.clear(); stroke.clear(); drops.clear(); battle = null; bagOpen = false
        fires.clear(); stars.clear(); fxs.clear(); beams.clear(); carried = null; spellCd.fill(0f); bossBurn = 0f; zapCount = 0; runePromptT = 0f; hasteT = 0f; bulwarkT = 0f
        spawnFriend()
        rx = 1; ry = 1; hx = 112f; hy = 120f; phase = Phase.FIGHT; bannerT = 1.8f; talking = false; orbT = 18f
        msg = "FIGHT!"; msgT = 1.2f
    }

    private fun fire(sx: Float, sy: Float, tx: Float, ty: Float, speed: Float, boss: Boolean, weak: Boolean, conf: Boolean) {
        var a = atan2(ty - sy, tx - sx)
        if (conf) a = rnd.nextFloat() * 6.2832f else if (weak) a += (rnd.nextFloat() - 0.5f) * 1.8f
        shots += Shot(sx, sy, cos(a) * speed * SLOW, sin(a) * speed * SLOW, boss, weak)
    }

    /** The DUEL rune landed: everything in the hall stops while the turn-based duel plays out. */
    private fun startDuel() {
        castRune = -1; stroke.clear(); spellT = 10f + rnd.nextFloat() * 3f
        battle = HallBattle(duelPose, duelRects, font, bag, object : HallBattle.Host {
            override var playerHp: Int
                get() = hp
                set(v) { hp = v }
            override val playerMax get() = maxHp
            override val bossHp get() = this@HallScreen.bossHp
            override val bossMax get() = BOSS_MAX
            override fun hitBoss(n: Int): Boolean { damageBoss(n); return stage != Stage.DAIS || phase != Phase.FIGHT }
            override fun score(stroke: List<FloatArray>, rune: List<Pair<Float, Float>>) = similarityTo(stroke, rune, RuneShapes.closed(rune))
            override fun buzz() { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            override fun backdrop(time: Float) { drawRoom(time); room.props.forEach { drawProp(it, time) } }
        })
        sfx.play(HallSfx.S.DUEL); sfx.rumbleBig()
    }

    private fun landSpell() {
        if (castRune == 3) { startDuel(); return }
        when (castRune) { 0 -> { paraT = 3f; msg = "BOUND!" }; 1 -> { poisonT = 6f; poisonTick = 1.5f; msg = "POISONED!" }; else -> { invertT = 7f; msg = "MIRRORED!" } }
        msgT = 1.4f; castRune = -1; spellT = (if (stage == Stage.CHASE) 10f else 9f) + rnd.nextFloat() * 3f
        sfx.play(HallSfx.S.HEX); sfx.rumbleBig()
    }

    private fun update(dt: Float) {
        battle?.let { b ->
            b.update(dt)
            if (b.done) {
                battle = null
                if (hp <= 0) { hp = 0; phase = Phase.LOST } else if (phase == Phase.FIGHT) { msg = "BACK IN THE HALL"; msgT = 1.4f }
            }
            return
        }
        if (bagOpen) return
        val tm = (System.nanoTime() - t0) / 1e9f
        // the firefly: after the XY pad, or bumbling round the traveller's head once it's let go
        // (with the Gleaner's Lamp, off to fetch whatever's lying about and bring it back to you)
        flyFreeT += dt
        val following = xyPointer < 0 && flyFreeT > 1.2f
        var fetching = false
        if (following && bag.wearing(Loot.GLEANERS_LAMP) && (phase == Phase.FIGHT || phase == Phase.WON) && !talking) {
            val carry = carried
            if (carry != null) {
                fetching = true; flyTX = hx + 8; flyTY = hy + 2
                if (hypot(flyX - (hx + 8), flyY - (hy + 4)) < 10f) { carried = null; say(takeLoot(carry), 2.4f); bursts += Burst(hx + 8, hy + 4) }
            } else drops.filter { it.room == roomId }.minByOrNull { hypot(it.x - flyX, it.y - flyY) }?.let { d ->
                fetching = true; flyTX = d.x; flyTY = d.y - 3
                if (hypot(flyX - d.x, flyY - (d.y - 3)) < 4f) { drops.remove(d); carried = d.item; sfx.play(HallSfx.S.FETCH) }
            }
        }
        if (following && !fetching) {
            bumA += (dt * 1.4f + (rnd.nextFloat() - 0.5f) * dt * 8f) * SLOW
            val rad = 13f + 5f * sin(tm * 0.8f) + flySize() * 2f
            flyTX = (hx + 8 + cos(bumA) * rad + sin(tm * 2.3f) * 3f).coerceIn(4f, W - 4f)
            flyTY = (hy + 2 + sin(bumA) * rad * 0.7f + cos(tm * 1.9f) * 3f).coerceIn(12f, H - 4f)
        }
        val k = minOf(1f, dt * SLOW * if (fetching) 4.5f else if (following) 2.2f else if (selDown) 8f else 4f)
        flyX += (flyTX - flyX) * k; flyY += (flyTY - flyY) * k
        trailT += dt
        if (trailT > 0.035f) { trailT = 0f; for (i in trail.size - 1 downTo 2) trail[i] = trail[i - 2]; trail[0] = flyX; trail[1] = flyY; trailN = minOf(trailN + 1, trail.size / 2) }

        if (talking) shown += dt * 28f
        bannerT -= dt; msgT -= dt; scoreT -= dt; bossHurtT -= dt; zapCd -= dt; shakeT -= dt
        arcs.removeAll { it.life -= dt; it.life <= 0f }
        bursts.removeAll { it.life -= dt; it.life <= 0f }
        rings.removeAll { it.life -= dt; it.life <= 0f }
        stompCd -= dt; sprintCd -= dt; sprintT -= dt; swiftT -= dt; interactCd -= dt
        levelFxT -= dt; flashT -= dt; hurtFlashT -= dt; impSfxT -= dt; burnSfxT -= dt
        fxs.removeAll { it.life -= dt; it.life <= 0f }
        fires.removeAll { it.life -= dt; it.life <= 0f }
        for (i in 0 until 6) spellCd[i] -= dt
        if (runePromptT > 0f) { runePromptT -= dt; if (runePromptT <= 0f) { stroke.clear(); say("THE RUNE BOOK CLOSES", 1.2f) } }
        hasteT -= dt; bulwarkT -= dt
        beams.removeAll { b -> b.life -= dt; if (b.life <= 0f) { val tx = if (b.toFriend) frX else hx + 8; val ty = if (b.toFriend) frY - 8 else hy + 6; fxs += Fx(FX_MEND, tx, ty, 0.6f) }; b.life <= 0f }
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
        var ex = if (inverted) -dirX else dirX; var ey = if (inverted) -dirY else dirY
        // a sprint with no direction held goes the way you're facing
        if (sprintT > 0f && ex == 0 && ey == 0) when (facing) { 0 -> ey = 1; 1 -> ey = -1; 2 -> ex = -1; else -> ex = 1 }
        if (paraT <= 0f && (ex != 0 || ey != 0) && phase != Phase.LOST) {
            facing = when { ey > 0 -> 0; ey < 0 -> 1; ex < 0 -> 2; else -> 3 }
            val sp = HERO_SPEED * SLOW * dt * (if (sprintT > 0f) 2.4f else 1f) * (if (swiftT > 0f) 1.3f else 1f)
            val nx = hx + ex * sp; val ny = hy + ey * sp
            if (!blocked(nx, hy)) hx = nx
            if (!blocked(hx, ny)) hy = ny
            walkT += dt
        } else walkT = 0f
        // the Flamedancer's dash: a wall of fire laid behind you, scorching whatever you run through
        if (sprintT > 0f && bag.wearing(Loot.FLAMEDANCER)) {
            val fx0 = hx + 8; val fy0 = hy + 14
            val last = fires.lastOrNull()
            if (last == null || last.room != roomId || hypot(last.x - fx0, last.y - fy0) > 5f) { fires += Fire(fx0, fy0, roomId); if (fires.size > 140) fires.removeAt(0) }
            if (phase == Phase.FIGHT) {
                for (m in minions) if (m.room == roomId && m.dash != dashId && hypot(m.x - fx0, m.y - (hy + 9)) < 13f) { m.dash = dashId; hitImp(m, 2); m.burn = 1.8f; m.burnTick = 0.7f; bursts += Burst(m.x, m.y) }
                if (throne && stage != Stage.SLAM && bossDash != dashId && hypot(bx - fx0, by - 4 - fy0) < 16f) { bossDash = dashId; damageBoss(8); bossBurn = 2.5f; bossBurnTick = 0.7f }
                killDead()
            }
        }
        when {
            hx + 8 < 0 && hasDoor(0) -> enterRoom(rx - 1, ry, W - 14f, hy)
            hx + 8 > W && hasDoor(1) -> enterRoom(rx + 1, ry, -2f, hy)
            hy + 12 < 18 && hasDoor(2) -> enterRoom(rx, ry - 1, hx, H - 18f)
            hy + 15 > H + 4 && hasDoor(3) -> enterRoom(rx, ry + 1, hx, 22f)
        }

        if (phase == Phase.WON && perishT > 0f && !bossGone) {
            perishT += dt
            if (perishT >= PERISH) { bossGone = true; drops += Drop(bx, by - 6, roomId, Loot.FIVE_EYED_AXE).also { it.life = 1e9f }; msg = "HE IS GONE. HIS AXE REMAINS."; msgT = 2.4f }
        }
        if (phase == Phase.WON) {
            // his axe (and anything else) can still be picked up after the fight
            drops.firstOrNull { it.room == roomId && hypot(it.x - (hx + 8), it.y - (hy + 11)) < 12f }?.let { d -> drops.remove(d); bursts += Burst(d.x, d.y); msg = takeLoot(d.item); msgT = 2.4f }
        }
        updateFriend(dt)
        if (phase != Phase.FIGHT || talking) return
        invulnT -= dt; paraT -= dt; invertT -= dt
        if (poisonT > 0f) { poisonT -= dt; poisonTick -= dt; if (poisonTick <= 0f) { poisonTick = 1.5f; invulnT = 0f; hurt(8) } }
        val px = hx + 8; val py = hy + 9

        // balls of light, now and then, one at a time
        orbT -= dt
        if (orbT <= 0f) { orbT = (22f + rnd.nextFloat() * 14f) * (if (bag.wearing(Loot.LANTERN)) 0.5f else 1f); if (room.kind != 6 && orbs.none { it.room == roomId }) spawnOrb() }
        orbs.removeAll { it.life -= dt; it.life <= 0f }
        orbs.firstOrNull { it.room == roomId && hypot(it.x - px, it.y - (hy + 10)) < 10f }?.let {
            orbs.remove(it); bursts += Burst(it.x, it.y); sfx.play(HallSfx.S.ORB)
            if (hp < maxHp) { hp = minOf(maxHp, hp + 35); msg = "+35 HP"; msgT = 1.2f }
        }

        drops.removeAll { it.life -= dt; it.life <= 0f }
        drops.firstOrNull { it.room == roomId && hypot(it.x - px, it.y - (hy + 11)) < 10f }?.let { d ->
            drops.remove(d); bursts += Burst(d.x, d.y)
            msg = takeLoot(d.item); msgT = 2.4f
        }

        if (throne) updateBoss(dt, px, py)
        if (phase != Phase.FIGHT) return

        // the imps: chase you (or your companion, if it's nearer, or taunting), throw pebbles, stopped by holy lines
        for (m in minions) {
            if (m.room != roomId) continue
            m.hurtT -= dt; m.para -= dt; m.weak -= dt; m.conf -= dt; m.stun -= dt; m.ice -= dt; m.slow -= dt; m.fireCd -= dt
            if (m.burn > 0f) {
                m.burn -= dt; m.burnTick -= dt
                if (m.burnTick <= 0f) { m.burnTick = 0.7f; hitImp(m, 1, true); if (burnSfxT <= 0f) { burnSfxT = 0.3f; sfx.play(HallSfx.S.BURN, 0.5f) } }
            }
            // crossing the firewall
            if (m.fireCd <= 0f && fires.any { it.room == roomId && abs(it.x - m.x) < 5f && abs(it.y - m.y) < 6f }) { m.fireCd = 0.7f; hitImp(m, 1, true); m.burn = 1.8f; m.burnTick = 0.7f }
            if (m.kx != 0f || m.ky != 0f) {
                val nx = m.x + m.kx * dt; val ny = m.y + m.ky * dt
                if (!impSolid(nx, m.y)) m.x = nx else m.kx = -m.kx * 0.3f
                if (!impSolid(m.x, ny)) m.y = ny else m.ky = -m.ky * 0.3f
                val f = kotlin.math.exp(-7f * dt); m.kx *= f; m.ky *= f
                if (abs(m.kx) + abs(m.ky) < 4f) { m.kx = 0f; m.ky = 0f }
            }
            if (m.para > 0f || m.stun > 0f || m.ice > 0f || m.hp <= 0) continue
            val dF = if (friendHere) hypot(frX - m.x, frY - 8 - m.y) else Float.MAX_VALUE
            val dH = hypot(px - m.x, py - m.y)
            val toFriend = friendHere && ((tauntT > 0f && dF < 70f) || dF < dH * 0.7f)
            val tx = if (toFriend) frX else px; val ty = if (toFriend) frY - 8 else py
            val dx = tx - m.x; val dy = ty - m.y; val d = hypot(dx, dy).coerceAtLeast(0.01f)
            var ux = dx / d; var uy = dy / d
            if (m.conf > 0f) {
                m.wt -= dt
                if (m.wt <= 0f) { val a = rnd.nextFloat() * 6.2832f; m.wx = cos(a); m.wy = sin(a); m.wt = 0.7f }
                ux = m.wx; uy = m.wy
            }
            if (d > 7f || m.conf > 0f) {
                val sp = 16f * 1.1f * SLOW * dt * (if (m.slow > 0f) 0.5f else 1f)
                val nx = m.x + ux * sp; val ny = m.y + uy * sp
                if (!impSolid(nx, m.y) && !wardBlocks(m.x, m.y, nx, m.y)) m.x = nx
                if (!impSolid(m.x, ny) && !wardBlocks(m.x, m.y, m.x, ny)) m.y = ny
                m.step += dt
            }
            if (d <= (if (toFriend) 12f else 8f) && m.weak <= 0f && m.stun <= 0f) { if (toFriend) hurtFriend(if (friend == Friend.GRUMBLE) 6 else 8) else hurt(12) }
            m.cd -= dt
            if (m.cd <= 0f && d < 100f) { m.cd = 2.5f + rnd.nextFloat() * 2f; fire(m.x, m.y, tx, ty, 92f, false, m.weak > 0f, m.conf > 0f) }
        }
        killDead()

        // falling stars (Starfall) and the Thunder Crown's lightning
        val si = stars.iterator()
        while (si.hasNext()) {
            val st = si.next()
            if (st.delay > 0f) { st.delay -= dt; continue }
            st.t += dt
            if (st.t >= (if (st.bolt) 0.14f else 0.32f)) {
                si.remove(); bursts += Burst(st.x, st.y); shakeT = maxOf(shakeT, 0.08f)
                val tm0 = st.m
                if (tm0 != null) { if (tm0.hp > 0 && tm0 in minions) hitImp(tm0, st.dmg, true) }
                else if (throne && phase == Phase.FIGHT) damageBoss(st.dmg)
            }
        }
        killDead()
        if (phase != Phase.FIGHT) return

        // shots: holy lines and anything standing stop them; they fly over water
        val iter = shots.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            val nx = s.x + s.vx * dt; val ny = s.y + s.vy * dt
            s.life -= dt
            if (wardBlocks(s.x, s.y, nx, ny)) { bursts += Burst(nx, ny); iter.remove(); continue }
            s.x = nx; s.y = ny
            if (s.life <= 0f || s.x < -4 || s.x > W + 4 || s.y < 0 || s.y > H + 4 || shotStopped(s.x, s.y)) { iter.remove(); continue }
            if (s.x > hx + 3 && s.x < hx + 13 && s.y > hy + 2 && s.y < hy + 15) { if (!s.weak) hurt(if (s.boss) 15 else 10); iter.remove() }
            else if (friendHere && abs(s.x - frX) < 10f && s.y > frY - 20f && s.y < frY + 1f) { if (!s.weak) hurtFriend(if (s.boss) 12 else 8); iter.remove() }
        }
    }

    private fun updateBoss(dt: Float, px: Float, py: Float) {
        bossPara -= dt; bossWeak -= dt; bossConf -= dt; bossFireCd -= dt
        if (bossBurn > 0f) { bossBurn -= dt; bossBurnTick -= dt; if (bossBurnTick <= 0f) { bossBurnTick = 0.7f; damageBoss(3); if (burnSfxT <= 0f) { burnSfxT = 0.3f; sfx.play(HallSfx.S.BURN, 0.5f) } } }
        if (phase != Phase.FIGHT) return
        when (stage) {
            Stage.DAIS -> {
                if (castRune >= 0) { castT -= dt; if (castT <= 0f) landSpell() }
                else if (bossPara <= 0f) {
                    bossShotT -= dt
                    if (bossShotT <= 0f) { bossShotT = 2.1f + rnd.nextFloat() * 0.8f; fire(bx, by - 30, px, py, 116f, true, bossWeak > 0f, bossConf > 0f) }
                    spellT -= dt
                    if (spellT <= 0f) { castRune = if (rnd.nextFloat() < 0.25f) 3 else rnd.nextInt(3); castT = if (castRune == 3) 8f else 7f; stroke.clear(); lastScore = -1f; runePromptT = 0f; sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleSoft() }
                }
                summonT -= dt
                if (summonT <= 0f && bossPara <= 0f) {
                    summonT = 9f
                    if (minions.count { it.room == roomId } < 5) { spawnMinion(96f to 100f); spawnMinion(144f to 100f) }
                }
            }
            Stage.SLAM -> {
                slamT += dt
                if (slamT > 0.9f && !crackOpen) { crackOpen = true; shakeT = 0.6f; msg = "CRACK!"; msgT = 1.2f; sfx.play(HallSfx.S.SLAM); sfx.rumbleSlam() }
                if (slamT > 1.6f) by = 64f + (108f - 64f) * ((slamT - 1.6f) / 0.6f).coerceAtMost(1f)
                if (slamT > 2.2f) { stage = Stage.CHASE; by = 108f; spellT = 5f; msg = "LAST STAND!"; msgT = 1.6f; shakeT = 0.3f; sfx.play(HallSfx.S.STOMP); sfx.rumbleBig() }
            }
            Stage.CHASE -> {
                if (castRune >= 0) { castT -= dt; if (castT <= 0f) landSpell(); return }
                if (bossPara > 0f) return
                spellT -= dt
                if (spellT <= 0f) { castRune = 2; castT = 7f; stroke.clear(); lastScore = -1f; runePromptT = 0f; sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleSoft(); return }
                // now and then, rarely, a bolt as he comes
                bossShotT -= dt
                if (bossShotT <= 0f) { bossShotT = 5f + rnd.nextFloat() * 4f; fire(bx, by - 30, px, py, 116f, true, bossWeak > 0f, bossConf > 0f) }
                val dx = px - bx; val dy = (py + 4) - by; val d = hypot(dx, dy).coerceAtLeast(0.01f)
                var mx = dx / d; var my = dy / d
                if (bossConf > 0f || bWanderT > 0f) {
                    bWanderT -= dt
                    if (bossConf > 0f && bWanderT <= 0f) { val a = rnd.nextFloat() * 6.2832f; bwx = cos(a); bwy = sin(a); bWanderT = 0.8f }
                    mx = bwx; my = bwy
                }
                val sp = HERO_SPEED * 0.75f * SLOW * dt
                val nx = bx + mx * sp; val ny = by + my * sp
                var moved = false
                if (!bossSolid(nx, by) && !wardBlocks(bx, by, nx, by)) { bx = nx; moved = true }
                if (!bossSolid(bx, ny) && !wardBlocks(bx, by, bx, ny)) { by = ny; moved = true }
                if (!moved && bWanderT <= 0f) { val sgn = if (rnd.nextBoolean()) 1f else -1f; bwx = -my * sgn; bwy = mx * sgn; bWanderT = 0.7f }
                // his touch in the last stand hurts a lot more (your companion, too)
                if (bossWeak <= 0f && hypot(bx - px, (by - 6) - (hy + 12)) < 14f) hurt(30)
                if (bossWeak <= 0f && friendHere && hypot(bx - frX, (by - 6) - (frY - 8)) < 18f) hurtFriend(22)
                // walking through the firewall
                if (bossFireCd <= 0f && fires.any { it.room == roomId && abs(it.x - bx) < 11f && abs(it.y - by) < 6f }) { bossFireCd = 0.7f; damageBoss(5); bossBurn = 2.5f; bossBurnTick = 0.7f }
            }
        }
    }

    /** R, Vital Spark: magic arcs from the firefly through up to three imps (and him), each strike bursting. */
    private fun zap() {
        if (phase != Phase.FIGHT || talking || zapCd > 0f) return
        zapCd = 0.45f
        var fromX = flyX; var fromY = flyY
        val star = bag.wearing(Loot.STARHEART)
        val reach = (if (bag.wearing(Loot.EMBER_RING)) 38f else 32f) + (if (star) 6f else 0f) + flySize() * 2f
        val bite = 1 + (if (bag.wearing(Loot.EMBER_RING)) 1 else 0) + (if (star) 1 else 0)
        val chain = when { star -> 6; bag.wearing(Loot.STORM_RING) -> 5; else -> 3 }
        val splash = if (star) 20f else SPLASH
        val hit = minions.filter { it.room == roomId && hypot(it.x - flyX, it.y - flyY) < reach }.sortedBy { hypot(it.x - flyX, it.y - flyY) }.take(chain)
        val struck = HashSet<Minion>()
        fun burst(x: Float, y: Float) {
            bursts += Burst(x, y)
            if (star) fxs += Fx(FX_NOVA_STAR, x, y, 0.35f)
            for (m in minions) if (m.room == roomId && hypot(m.x - x, m.y - y) < splash && struck.add(m)) hitImp(m, bite, true)
        }
        burst(flyX, flyY)
        for (m in hit) { arcs += Arc(fromX, fromY, m.x, m.y); fromX = m.x; fromY = m.y; if (struck.add(m)) hitImp(m, bite, true); burst(m.x, m.y) }
        if (throne && stage != Stage.SLAM && hypot(bx - flyX, by - 24 - flyY) < 34f + flySize() * 2f) {
            arcs += Arc(flyX, flyY, bx, by - 24); bursts += Burst(bx, by - 24); damageBoss(if (star) 8 else 5)
        }
        // touching your companion with the light mends it a little
        val fk = friend
        if (fk != null && friendHere && frHp < fk.maxHp && hypot(frX - flyX, frY - 10 - flyY) < reach) {
            val heal = maxOf(4, fk.maxHp / 30)
            frHp = minOf(fk.maxHp, frHp + heal)
            arcs += Arc(flyX, flyY, frX, frY - 10); fxs += Fx(FX_MEND, frX, frY - 8, 0.5f)
            sfx.play(HallSfx.S.ORB, 0.5f)
        }
        sfx.play(HallSfx.S.ZAP, 0.8f)
        // the Thunder Crown: every fourth spark, lightning out of the sky onto everything in the room
        if (bag.wearing(Loot.THUNDER_CROWN) && ++zapCount % 4 == 0) {
            var d = 0f
            for (m in minions) if (m.room == roomId) { stars += Star(m.x, m.y, m, d, true, 2); d += 0.05f }
            if (throne && stage != Stage.SLAM) stars += Star(bx, by - 24, null, d, true, 10)
            sfx.play(HallSfx.S.THUNDER); flashT = 0.12f; shakeT = 0.2f; sfx.rumbleBig()
        }
        killDead()
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
    private fun similarity(drawn: List<FloatArray>, rune: Int): Float = similarityTo(drawn, runes[rune], runeClosed[rune])
    private fun similarityTo(drawn: List<FloatArray>, shape: List<Pair<Float, Float>>, closed: Boolean): Float {
        if (drawn.size < 6) return 0f
        val n = 40
        val tpl = normalise(resample(shape.map { floatArrayOf(it.first, it.second) }, n))
        val st = normalise(resample(drawn, n))
        var best = Float.MAX_VALUE
        for (rev in 0..1) {
            val s = if (rev == 0) st else st.reversedArray()
            val shifts = if (closed) n else 1
            for (k in 0 until shifts) {
                var d = 0f
                for (i in 0 until n) { val a = s[(i + k) % n]; val b = tpl[i]; d += hypot(a[0] - b[0], a[1] - b[1]) }
                best = minOf(best, d / n)
            }
        }
        return (1f - best / 0.32f).coerceIn(0f, 1f)
    }

    private fun finishStroke() {
        battle?.let { b -> if (b.wantsRune) b.onStroke(stroke.toList()); stroke.clear(); return }
        if (runePromptT > 0f && !(castRune >= 0 && throne)) { runeStroke(); return }
        if (castRune < 0 || stroke.size < 4) { stroke.clear(); return }
        val s = similarity(stroke, castRune)
        lastScore = s; scoreT = 1.4f
        if (s >= NEED) {
            castRune = -1; spellT = (if (stage == Stage.CHASE) 10f else 9f) + rnd.nextFloat() * 3f
            msg = "COUNTERED!"; msgT = 1.4f
            sfx.play(HallSfx.S.COUNTER); sfx.rumbleSoft()
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

    /** The room: its still scenery baked in fine detail (see HallRoomArt), then what moves. */
    private fun drawRoom(time: Float) {
        val crack = if (!throne || !crackOpen) 0 else if (phase == Phase.WON) 2 else 1
        val key = roomId * 16 + crack * 4 + (if (hasDoor(2)) 2 else 0)
        val bg = art.background(key) {
            HallRoomArt.Spec(roomId, room.kind, booleanArrayOf(hasDoor(0), hasDoor(1), hasDoor(2), hasDoor(3)), throne, room.props.map { it.kind to it.foot }, room.pools, crack, dais)
        }
        sc.drawBitmap(bg, 0f, 0f, p)
        art.overlay(sc, time)
        for (side in 0..1) if (hasDoor(side) && (time * 2).toInt() % 2 == 0) {
            val ax = if (side == 0) 8f else W - 12f; val dir = if (side == 0) -1f else 1f
            for (k in 0 until 3) box(ax + dir * k, 100f - (2 - k), ax + dir * k + 1, 102f + (2 - k), MID)
        }
        if (crack == 2 && (time * 3).toInt() % 3 == 0) box(119f, 20f, 121f, 22f, GOLD)
        if (room.kind == 6) for ((gx, gy) in GOLD_GLINTS) if (((time * 2).toInt() + gx.toInt()) % 4 == 0) { box(gx - 1, gy - 9, gx + 1, gy - 7, WHITE); box(gx - 2, gy - 8, gx + 2, gy - 8.5f, WHITE) }
    }
    private val GOLD_GLINTS = listOf(60f to 120f, 212f to 150f, 92f to 152f, 150f to 146f, 196f to 92f)

    private fun drawProp(pp: Prop, time: Float) {
        val f = pp.foot; val b = pp.body; val v = f.left.toInt() * 31 + f.top.toInt() * 17
        when (pp.kind) {
            PILLAR -> {
                val l = f.left; val r = f.right; val top = b.top
                // abacus and capital
                box(l - 3, top, r + 3, top + 3, MID); box(l - 3, top, r + 3, top + 1, WHITE); box(l - 3, top + 2, r + 3, top + 3, DARK)
                box(l - 2, top + 3, r + 2, top + 5, PALE); box(l - 1, top + 5, r + 1, top + 6, MID)
                // the fluted shaft, lit from the left
                box(l, top + 6, r, f.bottom - 5, WHITE)
                var x = l + 2
                while (x < r - 2) { box(x, top + 7, x + 1, f.bottom - 6, if (x < (l + r) / 2) PALE else 0xFFD0D0DC.toInt()); x += 3 }
                box(l, top + 6, l + 1, f.bottom - 5, 0xFFFFFFFF.toInt()); box(r - 3, top + 6, r - 1, f.bottom - 5, 0xFFD8D8E2.toInt()); box(r - 1, top + 6, r, f.bottom - 5, MID)
                box(l, top + 9, r, top + 10, PALE)
                if (v % 3 == 0) { box(l + 3, top + 22, l + 4, top + 28, MID); box(l + 4, top + 27, l + 5, top + 31, MID) }
                // torus and plinth
                box(l - 1, f.bottom - 5, r + 1, f.bottom - 3, PALE); box(l - 1, f.bottom - 5, r + 1, f.bottom - 4, WHITE)
                box(l - 3, f.bottom - 3, r + 3, f.bottom, MID); box(l - 3, f.bottom - 3, r + 3, f.bottom - 2, PALE); box(l - 3, f.bottom - 1, r + 3, f.bottom, DARK)
            }
            ROCK -> {
                box(f.left + 2, b.top, f.right - 2, f.bottom, DARK); box(f.left, b.top + 3, f.right, f.bottom, DARK)
                box(f.left + 3, b.top + 1, f.right - 3, f.bottom - 1, MID); box(f.left + 1, b.top + 4, f.right - 1, f.bottom - 1, MID)
                box(f.left + 3, b.top + 1, f.centerX(), b.top + 4, PALE); box(f.left + 1, b.top + 4, f.left + 4, b.top + 7, PALE)
                box(f.left + 4, b.top + 2, f.left + 6, b.top + 3, WHITE)
                box(f.centerX(), b.top + 3, f.centerX() + 1, f.bottom - 3, DARK); box(f.centerX() + 1, b.top + 6, f.right - 3, b.top + 7, DARK)
                box(f.right - 5, f.bottom - 4, f.right - 2, f.bottom - 2, 0xFF888898.toInt())
                if (v % 2 == 0) { box(f.left + 2, b.top + 1, f.left + 7, b.top + 2, LEAF_D); box(f.left + 3, b.top, f.left + 5, b.top + 1, LEAF) }
            }
            SHRUB -> {
                box(f.left + 3, b.top, f.right - 3, f.bottom, LEAF_D); box(f.left, b.top + 4, f.right, f.bottom - 1, LEAF_D)
                box(f.left + 4, b.top + 1, f.right - 4, f.bottom - 2, LEAF); box(f.left + 1, b.top + 5, f.right - 1, f.bottom - 3, LEAF)
                val span = (f.width().toInt() - 5).coerceAtLeast(1)
                for (i in 0 until 7) {
                    val lx = f.left + 2 + (v * (i + 3)) % span; val ly = b.top + 2 + (v * (i + 5)) % 9
                    box(lx, ly, lx + 3, ly + 2, if (i % 2 == 0) 0xFFD4ECC4.toInt() else LEAF_D)
                }
                box(f.left + 5, b.top + 3, f.left + 7, b.top + 5, WHITE)
                if (v % 2 == 0) { box(f.left + 4, b.top + 6, f.left + 6, b.top + 8, 0xFFF0A0C0.toInt()); box(f.right - 6, b.top + 3, f.right - 4, b.top + 5, 0xFFF8F0A0.toInt()) }
                box(f.left + 1, f.bottom - 1, f.right - 1, f.bottom, 0xFF5A7A54.toInt())
                if ((time * 1.5f + v).toInt() % 5 == 0) box(f.right - 3, b.top + 2, f.right - 1, b.top + 3, LEAF)
            }
            STATUE -> {
                box(f.left, f.bottom - 12, f.right, f.bottom, MID); box(f.left + 1, f.bottom - 11, f.right - 1, f.bottom - 1, PALE)
                box(f.left - 1, f.bottom - 13, f.right + 1, f.bottom - 11, MID); box(f.left - 1, f.bottom - 13, f.right + 1, f.bottom - 12, WHITE)
                box(f.left + 4, f.bottom - 8, f.right - 4, f.bottom - 5, GOLD_D); box(f.left + 5, f.bottom - 7, f.right - 5, f.bottom - 6, GOLD)
                box(f.left, f.bottom - 1, f.right, f.bottom, DARK)
                val cx = f.centerX(); val top = b.top
                box(f.left + 1, top + 10, f.right - 1, f.bottom - 13, MID); box(f.left + 2, top + 10, f.right - 2, f.bottom - 14, WHITE)
                box(cx - 2, top + 8, cx + 2, top + 11, PALE)
                box(cx - 4, top, cx + 4, top + 9, MID); box(cx - 3, top + 1, cx + 3, top + 8, WHITE); box(cx + 1, top + 1, cx + 3, top + 8, PALE)
                box(cx - 2, top + 4, cx - 1, top + 5, DARK); box(cx + 1, top + 4, cx + 2, top + 5, DARK); box(cx - 1, top + 6, cx + 1, top + 7, PALE)
                if (v % 2 == 0) box(cx - 4, top, cx + 4, top + 2, LEAF_D) else box(cx - 3, top - 1, cx + 3, top + 1, PALE)
            }
            FOUNTAIN -> {
                val cx = f.centerX()
                box(f.left, f.top + 3, f.right, f.bottom, MID); box(f.left, f.top + 3, f.right, f.top + 4, WHITE); box(f.left, f.bottom - 1, f.right, f.bottom, DARK)
                box(f.left + 2, f.top + 5, f.right - 2, f.bottom - 3, WATER_D); box(f.left + 3, f.top + 7, f.right - 3, f.bottom - 3, WATER)
                val ph = (time * 3).toInt() % 3
                for (k in 0 until 3) { val r = 3f + ((k + ph) % 3) * 5f; box(cx - r, f.centerY() + 2, cx + r, f.centerY() + 3, WHITE) }
                box(cx - 3, f.top - 10, cx + 3, f.centerY() + 2, MID); box(cx - 2, f.top - 9, cx + 2, f.centerY() + 2, WHITE); box(cx + 1, f.top - 9, cx + 2, f.centerY() + 2, PALE)
                // the upper bowl, spilling over on both sides
                box(cx - 8, f.top - 12, cx + 8, f.top - 9, MID); box(cx - 7, f.top - 12, cx + 7, f.top - 11, WATER); box(cx - 8, f.top - 9, cx + 8, f.top - 8, DARK)
                for (i in 0 until 10) {
                    val t = (time * 1.2f + i * 0.1f) % 1f; val side = if (i % 2 == 0) -1 else 1
                    val dx = side * (7f + t * 6f); val dy = -11f + t * t * 20f
                    box(cx + dx, f.top + dy, cx + dx + 1, f.top + dy + 1, if (i % 3 == 0) WHITE else WATER_D)
                }
                box(cx - 1, f.top - 16 + ph, cx + 1, f.top - 12, WATER_D); box(cx, f.top - 18 + ph, cx + 1, f.top - 16 + ph, WHITE)
            }
            TABLE -> {
                val wood = 0xFF7A5638.toInt(); val woodD = 0xFF5A3E2A.toInt(); val woodL = 0xFF9A7250.toInt()
                box(f.left + 2, b.top + 4, f.left + 4, f.bottom, woodD); box(f.right - 4, b.top + 4, f.right - 2, f.bottom, woodD)
                box(f.left, b.top - 2, f.right, b.top + 4, woodD); box(f.left + 1, b.top - 2, f.right - 1, b.top + 3, wood); box(f.left + 1, b.top - 2, f.right - 1, b.top - 1, woodL)
                // an open book, a candle and a scroll
                box(f.left + 6, b.top - 3, f.left + 16, b.top + 1, WHITE); box(f.left + 11, b.top - 3, f.left + 12, b.top + 1, MID)
                for (k in 0 until 3) { box(f.left + 7, b.top - 2 + k, f.left + 10, b.top - 1.5f + k, MID); box(f.left + 13, b.top - 2 + k, f.left + 15, b.top - 1.5f + k, MID) }
                box(f.right - 9, b.top - 7, f.right - 7, b.top - 1, 0xFFF0EEE4.toInt()); box(f.right - 10, b.top - 1, f.right - 6, b.top, MID)
                val fl = (time * 8 + v).toInt() % 3
                p.color = 0x26FFC060; sc.drawCircle(f.right - 8, b.top - 8, 6.5f, p)
                box(f.right - 9, b.top - 10 - (fl % 2), f.right - 7, b.top - 7, 0xFFF8B030.toInt()); box(f.right - 8.5f, b.top - 9, f.right - 7.5f, b.top - 7, 0xFFFFF4C0.toInt())
                box(f.right - 20, b.top - 2, f.right - 13, b.top, 0xFFE8D8A8.toInt()); box(f.right - 20, b.top - 2, f.right - 19, b.top, 0xFFC8B888.toInt())
            }
            BRAZIER -> {
                box(f.left + 3, f.top, f.right - 3, f.bottom, DARK); box(f.left + 1, f.bottom - 2, f.right - 1, f.bottom, DARK)
                box(f.left, b.top + 4, f.right, f.top + 2, DARK); box(f.left + 1, b.top + 5, f.right - 1, f.top + 1, MID); box(f.left + 1, b.top + 5, f.right - 1, b.top + 6, PALE)
                val k = (time * 8).toInt() % 2
                p.color = 0x22FFA040; sc.drawCircle(f.centerX(), b.top - 2, 13f, p); p.color = 0x22FFC060; sc.drawCircle(f.centerX(), b.top - 2, 7f, p)
                box(f.left + 1, b.top + 3, f.right - 1, b.top + 5, 0xFFC83018.toInt()); box(f.left + 3, b.top + 3, f.left + 5, b.top + 4, 0xFFFFB040.toInt())
                box(f.left + 2, b.top - 2 - k, f.right - 2, b.top + 3, 0xFFF07820.toInt()); box(f.left + 4, b.top - 6 + k, f.right - 4, b.top + 1, 0xFFFFC040.toInt()); box(f.left + 5, b.top - 4, f.right - 5, b.top, 0xFFFFF4C0.toInt())
                for (i in 0 until 3) { val ph = (time * 0.9f + i * 0.33f + v * 0.01f) % 1f; val sx = f.centerX() + sin(time * 3 + i) * 3; box(sx, b.top - 6 - ph * 14, sx + 1, b.top - 5 - ph * 14, 0xFFFFB040.toInt()) }
            }
            TOMB -> {
                box(f.left, b.top + 2, f.right, f.bottom, DARK); box(f.left + 1, b.top + 3, f.right - 1, f.bottom - 1, MID)
                box(f.left - 1, b.top, f.right + 1, b.top + 4, DARK); box(f.left, b.top, f.right, b.top + 3, PALE); box(f.left, b.top, f.right, b.top + 1, WHITE)
                box(f.left + 4, b.top + 1, f.right - 4, b.top + 2, MID); box(f.left + 3, b.top + 1, f.left + 6, b.top + 2, DARK)
                var x = f.left + 3
                while (x < f.right - 5) { box(x, b.top + 6, x + 5, f.bottom - 3, 0xFF9898AC.toInt()); box(x, b.top + 6, x + 5, b.top + 7, DARK); x += 8 }
                if (v % 2 == 0) { box(f.right - 6, b.top + 5, f.right - 5, b.top + 9, DARK); box(f.right - 5, b.top + 8, f.right - 4, b.top + 11, DARK) }
                box(f.left, f.bottom - 2, f.left + 4, f.bottom - 1, LEAF_D)
            }
            CHEST -> {
                val wood = 0xFF8A5A30.toInt(); val woodD = 0xFF5A3A20.toInt(); val iron = 0xFF505064.toInt()
                if (!chestOpen) {
                    box(f.left, b.top, f.right, f.bottom, woodD); box(f.left + 1, b.top + 1, f.right - 1, f.bottom - 1, wood)
                    box(f.left + 1, b.top + 1, f.right - 1, b.top + 2, 0xFFAA7A48.toInt())
                    box(f.left, b.top + 4, f.right, b.top + 5, iron); box(f.left + 3, b.top, f.left + 5, f.bottom, iron); box(f.right - 5, b.top, f.right - 3, f.bottom, iron)
                    box(f.centerX() - 2, b.top + 3, f.centerX() + 2, b.top + 8, GOLD_D); box(f.centerX() - 1, b.top + 4, f.centerX() + 1, b.top + 6, GOLD); box(f.centerX() - 0.5f, b.top + 5, f.centerX() + 0.5f, b.top + 7, INK)
                    for (sx in floatArrayOf(f.left + 4, f.right - 4)) { box(sx - 0.5f, b.top + 2, sx + 0.5f, b.top + 3, GOLD); box(sx - 0.5f, f.bottom - 3, sx + 0.5f, f.bottom - 2, GOLD) }
                    if ((time * 2).toInt() % 2 == 0) box(f.right - 4, b.top - 3, f.right - 2, b.top - 1, WHITE)
                } else {
                    box(f.left, b.top - 8, f.right, b.top, woodD); box(f.left + 1, b.top - 7, f.right - 1, b.top - 1, wood); box(f.left, b.top - 5, f.right, b.top - 4, iron)
                    box(f.left, b.top, f.right, f.bottom, woodD); box(f.left + 1, b.top + 1, f.right - 1, f.bottom - 1, wood)
                    box(f.left + 2, b.top + 1, f.right - 2, b.top + 4, GOLD); box(f.left + 3, b.top, f.right - 3, b.top + 2, 0xFFFFF0A0.toInt())
                    box(f.left + 4, b.top + 1, f.left + 7, b.top + 3, 0xFFE04848.toInt()); box(f.right - 8, b.top + 1, f.right - 5, b.top + 3, 0xFF48A0E0.toInt())
                    box(f.left, b.top + 4, f.right, b.top + 5, iron)
                    p.color = 0x30FFE080; sc.drawRect(f.left - 2, b.top - 18, f.right + 2, b.top + 2, p)
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
            phase == Phase.WON -> { if (bossGone) return; frame = 1; dy = 2f }
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
        // each cell is 72×72: the 56×56 figure sits 8 in and 16 down, with room above for the axe head
        src.set(frame * 72, 0, frame * 72 + 72, 72); dst.set(x - 8, y - 16, x + 64, y + 56)
        if (phase == Phase.WON && perishT > 0f) {
            // perishing: he flickers, fades and blows away as ash
            val k = (perishT / PERISH).coerceIn(0f, 1f)
            p.alpha = ((1f - k) * 255).toInt().coerceIn(0, 255)
            if (!(k > 0.4f && (time * 18).toInt() % 3 == 0)) sc.drawBitmap(bmp, src, dst, p)
            p.alpha = 255
            for (i in 0 until 26) {
                val sx = x + 8 + (i * 37 % 40); val rise = (k * 70f + i * 3f) % 70f
                val col = if (i % 3 == 0) PALE else if (i % 3 == 1) MID else DARK
                if (rnd.nextFloat() < 1f - k * 0.6f) box(sx + sin(time * 3f + i) * 3f, y + 50 - rise, sx + 2 + sin(time * 3f + i) * 3f, y + 52 - rise, col)
            }
            return
        }
        sc.drawBitmap(bmp, src, dst, p)
        if (bossBurn > 0f) drawBurn(bx, by - 2, 30f, 40f, time, 5)
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

    /** The imps: original horned scamps, a little bigger now: bone horns, red eyes, a toothy grin, claws and a spaded tail. */
    private val impPal = mapOf('K' to INK, 'D' to 0xFF4A4060.toInt(), 'd' to 0xFF6A5A84.toInt(), 'l' to 0xFF9A8AB4.toInt(),
        'E' to 0xFFFF4A3A.toInt(), 'W' to WHITE, 'H' to 0xFFE8DCC0.toInt(), 'h' to 0xFFB0A488.toInt())
    private val impRows = listOf(
        listOf(
            "H..........H",
            "Hh........hH",
            ".hKKKKKKKKh.",
            ".KdDDDDDDdK.",
            ".KDEKDDKEDK.",
            ".KDDDDDDDDK.",
            "..KWKWKWKK..",
            ".KKdlllldKK.",
            "KhKDdlldDKhK",
            "..KDDDDDDK.K",
            "..KK.KK.KK.K",
            ".KK...KK..KK",
        ),
        listOf(
            "H..........H",
            "Hh........hH",
            ".hKKKKKKKKh.",
            ".KdDDDDDDdK.",
            ".KDEKDDKEDK.",
            ".KDDDDDDDDK.",
            "..KWKWKWKK..",
            "KKKdlllldKKK",
            "h.KDdlldDK.h",
            "..KDDDDDDKK.",
            "..KK.KK.KK.K",
            "...KK..KK.KK",
        ),
    )
    private val impSprites = impRows.map { Sprite(it, impPal, outline = null) }
    private val impFlash = impRows.map { rows -> Sprite(rows.map { r -> r.map { ch -> if (ch == '.') '.' else 'W' }.joinToString("") }, impPal, outline = null) }
    private val impBurn = impRows.map { Sprite(it, impPal + mapOf('D' to 0xFF8A3A20.toInt(), 'd' to 0xFFC05A28.toInt(), 'l' to 0xFFF0A040.toInt()), outline = null) }
    private val impIce = impRows.map { Sprite(it, impPal + mapOf('D' to 0xFF6A9AC8.toInt(), 'd' to 0xFF98C8E8.toInt(), 'l' to 0xFFD8F0FF.toInt(), 'E' to 0xFF3A5A9A.toInt()), outline = null) }
    /** Flames licking up over something on fire (bottom-centred), with embers drifting off. */
    private fun drawBurn(cx: Float, bottom: Float, w: Float, h: Float, time: Float, seed: Int) {
        p.color = 0x38FF7020; sc.drawRect(cx - w / 2 - 2, bottom - h - 3, cx + w / 2 + 2, bottom + 1, p)
        val n = maxOf(3, (w / 3).toInt())
        for (i in 0 until n) {
            val x = cx - w / 2 + (i + 0.5f) * w / n
            val fh = h * (0.4f + 0.6f * abs(sin(time * 11f + i * 2.3f + seed)))
            box(x - 1.5f, bottom - fh * 0.7f, x + 1.5f, bottom, 0xFFC83018.toInt())
            box(x - 1f, bottom - fh, x + 1f, bottom - fh * 0.2f, 0xFFF07820.toInt())
            box(x - 0.5f, bottom - fh * 0.55f, x + 0.5f, bottom - fh * 0.1f, 0xFFFFE070.toInt())
        }
        for (i in 0 until 3) {
            val ph = (time * 1.6f + i * 0.33f + seed * 0.1f) % 1f
            val ex = cx - w / 2 + ((i * 7 + seed) % maxOf(1, w.toInt())) + sin(time * 4f + i) * 2f
            box(ex, bottom - h - ph * 10, ex + 1, bottom - h - ph * 10 + 1, 0xFFFFB040.toInt())
        }
    }
    private fun drawMinion(m: Minion, time: Float) {
        val x = m.x.toInt() - 6f; val y = m.y.toInt() - 8f
        val f = if (m.para > 0f || m.stun > 0f || m.ice > 0f) 0 else (m.step * 8).toInt() % 2
        if (m.slow > 0f) {
            // the hex: a purple sigil turning beneath it
            val r = 7f + sin(time * 5f)
            for (i in 0 until 10) { val a = i * 0.628f + time * 2f; box(m.x + cos(a) * r - 0.5f, m.y + 4 + sin(a) * r * 0.4f, m.x + cos(a) * r + 0.5f, m.y + 5 + sin(a) * r * 0.4f, if (i % 2 == 0) RUNE else 0xFFB070E0.toInt()) }
        }
        box(x + 2, y + 11, x + 10, y + 13, PALE)
        val s = when {
            m.hurtT > 0f && (time * 20).toInt() % 2 == 0 -> impFlash[f]
            m.ice > 0f -> impIce[f]
            m.burn > 0f && (time * 10).toInt() % 2 == 0 -> impBurn[f]
            else -> impSprites[f]
        }
        src.set(0, 0, s.w, s.h); dst.set(x, y, x + 12, y + 12)
        sc.drawBitmap(s.bmp, src, dst, p)
        if (m.ice > 0f) {
            // locked in a block of ice
            p.color = 0x55C8ECFF; sc.drawRect(x - 1, y - 1, x + 13, y + 13, p)
            box(x - 1, y - 1, x, y + 7, WHITE); box(x, y - 1, x + 6, y, WHITE); box(x + 12, y + 5, x + 13, y + 13, 0xFF98C8E8.toInt()); box(x + 6, y + 12, x + 13, y + 13, 0xFF98C8E8.toInt())
            if ((time * 3).toInt() % 4 == 0) box(x + 2, y + 1, x + 3, y + 2, WHITE)
        }
        if (m.burn > 0f) drawBurn(m.x, y + 12, 10f, 11f, time, (m.hashCode() and 7))
        if (m.para > 0f) { box(x - 1, y + 4, x + 13, y + 5, RUNE); box(x - 1, y + 8, x + 13, y + 9, RUNE) }
        if (m.conf > 0f) { val a = time * 6f; box(m.x + cos(a) * 6 - 1, y - 3, m.x + cos(a) * 6 + 1, y - 1, GOLD) }
        if (m.weak > 0f) box(x + 5, y - 3 + (time * 4) % 2f, x + 7, y - 1 + (time * 4) % 2f, MID)
        if (m.stun > 0f && (time * 8).toInt() % 2 == 0) { box(x + 2, y - 4, x + 4, y - 2, WHITE); box(x + 8, y - 4, x + 10, y - 2, WHITE) }
    }

    /** Your companion: waiting to be found (with a ? over it), at your side, or knocked out on its back. */
    private fun drawFriend(time: Float) {
        val k = friend ?: return
        val ko = frState == 2
        val fl = k.flies && !ko
        val w = 12 * FS; val h = 11 * FS
        var x = frX - w / 2; var y = frY - h
        if (fl) y -= 8 + sin(time * 4f) * 2f
        if (frLunge > 0f) { val t = frLunge / 0.25f; x += (frLx - frX) * 0.5f * t; y += (frLy - frY) * 0.5f * t }
        p.color = 0x40000000; sc.drawOval(frX - 9, frY - 2.5f, frX + 9, frY + 1.5f, p)
        if (hasteT > 0f && frMoving) for (i in 1..3) { p.color = (0x30 - i * 8 shl 24) or 0xC8F0FF; sc.drawRect(x - frFace * i * 4f, y + 6, x + w - frFace * i * 4f, y + h - 4, p) }
        if (frInv > 0f && !ko && frHurt <= 0f && (time * 16).toInt() % 2 == 0 && frInv < 1.5f) { /* blinking after a hit */ }
        else {
            val f = when { ko -> 0; frMoving -> (frStep * 8).toInt() % 2; fl -> (time * 6).toInt() % 2; else -> if ((time * 1.5f).toInt() % 3 == 0) 1 else 0 }
            val sp = if (frHurt > 0f && (time * 20).toInt() % 2 == 0) friendFlash[k.ordinal][f] else friendSprites[k.ordinal][f]
            src.set(0, 0, sp.w, sp.h)
            sc.save()
            if (ko) sc.rotate(if (frFace > 0) -90f else 90f, frX, frY - h / 2)
            if (frFace < 0) sc.scale(-1f, 1f, frX, 0f)
            dst.set(x, y, x + w, y + h)
            sc.drawBitmap(sp.bmp, src, dst, p)
            sc.restore()
        }
        if (bulwarkT > 0f && !ko) { line1.color = if ((time * 6).toInt() % 2 == 0) GOLD else 0x88F0CC50.toInt(); sc.drawOval(frX - 13, y - 3, frX + 13, frY + 2, line1) }
        if (ko) for (i in 0 until 3) { val a = time * 3f + i * 2.09f; box(frX + cos(a) * 9 - 1, frY - 20 + sin(a) * 3, frX + cos(a) * 9 + 1, frY - 18 + sin(a) * 3, if (i % 2 == 0) GOLD else WHITE) }
        if (frState == 0) {
            val bob = sin(time * 3f) * 1.5f; val tx = frX - 4; val ty = y - 13 + bob
            box(tx - 1, ty - 1, tx + 9, ty + 10, INK); box(tx, ty, tx + 8, ty + 9, WHITE); box(tx + 3, ty + 9, tx + 5, ty + 11, INK)
            font.color = RUNE; sc.drawText(if (hypot(hx + 8 - frX, hy + 13 - frY) < 50f) "!" else "?", tx + 1, ty + 8, font); font.color = INK
        }
        if (frState == 1 && (frHurt > 0f || frInv > 0f || frHp < k.maxHp / 2)) {
            box(frX - 11, y - 5, frX + 11, y - 2, INK)
            box(frX - 10, y - 4.5f, frX - 10 + 20f * frHp / k.maxHp, y - 2.5f, if (frHp < k.maxHp * 0.4f) 0xFFE04848.toInt() else 0xFF58B0E0.toInt())
        }
    }

    /**
     * The water spirit: a figure of clear water rising out of its basin (head, glowing eyes, arms of
     * falling water), swaying, its edges rippling, droplets drifting up and rings spreading below.
     */
    private fun drawSpirit(time: Float) {
        val cx = spiritX; val base = spiritY + 14
        val bob = sin(time * 1.8f) * 1.5f
        val deep = 0xFF3A78C8.toInt(); val mid = 0xFF6AA8E8.toInt(); val light = 0xFFB8E0FF.toInt(); val foam = 0xFFF0FAFF.toInt()
        // rings spreading on the pool beneath
        for (k in 0 until 3) {
            val ph = ((time * 0.6f + k / 3f) % 1f); val rw = 6f + ph * 14f
            p.color = (((1f - ph) * 200).toInt() shl 24) or 0xE0F4FF
            sc.drawRect(cx - rw, base + 2, cx + rw, base + 3, p)
        }
        // the body: wider at the base, narrowing to the shoulders, each row swaying a little
        for (row in 0 until 30) {
            val y = base - row + bob * (row / 30f)
            val half = when { row < 8 -> 9f - row * 0.4f; row < 20 -> 6f - (row - 8) * 0.12f; else -> 4.5f + kotlin.math.sqrt(maxOf(0f, 25f - (row - 25f) * (row - 25f))) * 0.6f }
            val sway = sin(time * 2.4f + row * 0.35f) * 1.2f
            val l = cx - half + sway; val r = cx + half + sway
            box(l, y, r, y + 1, if (row % 5 == 0) mid else deep)
            box(l + 1, y, l + half * 0.8f, y + 1, mid)
            if ((row + (time * 8).toInt()) % 7 == 0) box(l + 1, y, l + 3, y + 1, light)
        }
        // arms of falling water
        for (side in -1..1 step 2) {
            for (k in 0 until 10) {
                val ax = cx + side * (6f + k * 0.9f) + sin(time * 2f + k) * 0.6f
                val ay = base - 20 + bob + k * 1.2f
                box(ax - 1, ay, ax + 1, ay + 1, if (k % 3 == 0) light else mid)
            }
        }
        // the face: two glowing eyes and a calm mouth
        val fy = base - 26 + bob
        box(cx - 4, fy, cx - 2, fy + 2, foam); box(cx + 2, fy, cx + 4, fy + 2, foam)
        if ((time * 1.3f).toInt() % 5 != 0) { box(cx - 4, fy + 1, cx - 3, fy + 2, deep); box(cx + 3, fy + 1, cx + 4, fy + 2, deep) }
        box(cx - 2, fy + 5, cx + 2, fy + 6, light)
        // droplets drifting up off it
        for (i in 0 until 6) {
            val ph = (time * 0.5f + i * 0.17f) % 1f
            val dx = cx + sin(i * 2.1f + time) * 8f; val dy = base - 8 - ph * 34f
            p.color = (((1f - ph) * 255).toInt() shl 24) or 0xB8E0FF
            sc.drawRect(dx, dy, dx + 1, dy + 2, p)
        }
        p.alpha = 255
    }

    /** Loot where an imp fell: a sack in the colour of its rarity; legendary and exotic finds send up a beam of light. */
    private fun drawDrop(d: Drop, time: Float) {
        if (d.life < 4f && (time * 10).toInt() % 2 == 0) return
        val x = d.x; val y = d.y
        val it = d.item
        val col = when (it) { Loot.HERB -> 0xFF6FA84A.toInt(); Loot.BLOOM -> 0xFFF080B0.toInt(); else -> rarityCol(it.rarity, time) }
        if (!it.consumable && it.rarity.ordinal >= Rarity.LEGENDARY.ordinal) {
            p.color = (0x30 shl 24) or (col and 0xFFFFFF); sc.drawRect(x - 3, y - 34, x + 3, y + 2, p)
            p.color = (0x40 shl 24) or (col and 0xFFFFFF); sc.drawRect(x - 1, y - 34, x + 1, y + 2, p)
            for (i in 0 until 3) { val ph = (time * 0.8f + i * 0.33f) % 1f; box(x - 3 + i * 3, y - ph * 30, x - 2 + i * 3, y - ph * 30 + 1, WHITE) }
        }
        if (it == Loot.FIVE_EYED_AXE) {
            // lying on its side: haft and the double head, glinting
            box(x - 14, y - 1, x + 10, y + 2, INK); box(x - 13, y, x + 9, y + 1, 0xFF8A5028.toInt())
            box(x + 6, y - 7, x + 14, y + 8, INK); box(x + 7, y - 6, x + 13, y - 1, 0xFFC4CCD6.toInt()); box(x + 7, y + 2, x + 13, y + 7, 0xFFC4CCD6.toInt())
            box(x + 9, y - 1, x + 11, y + 2, 0xFF404250.toInt())
            if ((time * 3).toInt() % 2 == 0) box(x + 12, y - 9, x + 14, y - 7, WHITE)
            return
        }
        if (it == Loot.BLOOM) {
            box(x - 1, y, x + 1, y + 4, LEAF_D)
            for ((ox, oy) in listOf(-2f to -2f, 2f to -2f, 0f to -4f, 0f to 0f)) box(x + ox - 1.5f, y + oy - 1.5f, x + ox + 1.5f, y + oy + 1.5f, col)
            box(x - 1, y - 3, x + 1, y - 1, 0xFFFFF0A0.toInt())
            if ((time * 3).toInt() % 3 == 0) box(x + 3, y - 6, x + 5, y - 4, WHITE)
            return
        }
        box(x - 4, y - 2, x + 4, y + 4, INK); box(x - 3, y - 1, x + 3, y + 3, col); box(x - 3, y - 1, x - 1, y + 1, (col and 0xFFFFFF) or 0x80000000.toInt())
        box(x - 2, y - 4, x + 2, y - 2, INK); box(x - 1, y - 3, x + 1, y - 2, GOLD)
        if ((time * 3).toInt() % 3 == 0) box(x + 3, y - 6, x + 5, y - 4, WHITE)
    }
    /** A rarity's colour; exotics shimmer between teal and violet. */
    private fun rarityCol(r: Rarity, time: Float): Int =
        if (r != Rarity.EXOTIC) r.color else if ((time * 4).toInt() % 3 == 0) 0xFFB060F0.toInt() else if ((time * 4).toInt() % 3 == 1) 0xFF20C0B0.toInt() else 0xFF60E0F0.toInt()

    /** The stomp's dust ring. */
    private fun drawRings() {
        for (r in rings) {
            val k = 1f - r.life / 0.35f; val rad = 6f + 34f * k
            for (i in 0 until 16) {
                val a = i * (Math.PI.toFloat() * 2f / 16f)
                val x = r.x + cos(a) * rad; val y = r.y + sin(a) * rad * 0.55f
                box(x - 1.5f, y - 1f, x + 1.5f, y + 1f, if (i % 2 == 0) MID else PALE)
            }
        }
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
        drops.forEachIndexed { i, d -> if (d.room == roomId) addEnt(d.y + 4, 5, i) }
        if (roomId == 1) addEnt(spiritY + 14, 6, 0)
        if (friend != null && frRoom == roomId) addEnt(frY, 7, 0)
        java.util.Arrays.sort(ents, 0, entN, entOrder)
        for (k in 0 until entN) {
            val e = ents[k]
            when (e.type) {
                0 -> drawProp(room.props[e.idx], time)
                1 -> drawHero(time)
                2 -> drawMinion(minions[e.idx], time)
                3 -> drawBoss(time)
                4 -> drawOrb(orbs[e.idx], time)
                6 -> drawSpirit(time)
                7 -> drawFriend(time)
                else -> drawDrop(drops[e.idx], time)
            }
        }
    }

    /** A companion's wall: a hedge of thorns, a veil of moonlight, a wall of ice, a stack of stones. */
    private fun drawWall(w: Ward, time: Float) {
        val q = w.pts; val grow = ((w.max - w.life) / 0.35f).coerceIn(0f, 1f)
        var i = 0
        while (i + 3 < q.size) {
            val x0 = q[i]; val y0 = q[i + 1]; val x1 = q[i + 2]; val y1 = q[i + 3]
            val len = hypot(x1 - x0, y1 - y0); val n = maxOf(1, (len / 3f).toInt())
            for (k in 0..n) {
                val t = k / n.toFloat(); val x = x0 + (x1 - x0) * t; val y = y0 + (y1 - y0) * t
                val seed = ((x * 7 + y * 13).toInt() and 15)
                when (w.style) {
                    1 -> { val hh = (5f + (seed % 4)) * grow
                        box(x - 1.5f, y - hh, x + 1.5f, y + 1, LEAF_D); box(x - 0.5f, y - hh, x + 0.5f, y - 1, 0xFF4A6A3A.toInt())
                        if (seed % 3 == 0) { box(x + 1.5f, y - hh * 0.6f, x + 3, y - hh * 0.6f + 1, 0xFFE8DCC0.toInt()); box(x - 3, y - hh * 0.3f, x - 1.5f, y - hh * 0.3f + 1, 0xFFE8DCC0.toInt()) }
                        if (seed % 5 == 0) box(x - 1, y - hh - 1, x + 1, y - hh + 1, 0xFFF080B0.toInt()) }
                    2 -> { val hh = 12f * grow; val a = (0x60 + 0x40 * sin(time * 5f + seed)).toInt().coerceIn(0, 255)
                        p.color = (a shl 24) or 0xD8E8FF; sc.drawRect(x - 1.5f, y - hh, x + 1.5f, y + 1, p)
                        if ((seed + (time * 8).toInt()) % 6 == 0) box(x, y - hh * ((time * 0.7f + seed * 0.1f) % 1f), x + 1, y - hh * ((time * 0.7f + seed * 0.1f) % 1f) + 1, WHITE) }
                    3 -> { val hh = (7f + (seed % 5)) * grow
                        box(x - 2, y - hh, x + 2, y + 1, 0xFF98C8E8.toInt()); box(x - 1, y - hh + 1, x + 1, y, 0xFFD8F0FF.toInt()); box(x - 2, y - hh, x - 1, y - hh + 2, WHITE) }
                    else -> { val hh = 8f * grow
                        box(x - 2.5f, y - hh, x + 2.5f, y + 1, DARK); box(x - 2, y - hh + 0.5f, x + 2, y, MID); box(x - 2, y - hh + 0.5f, x + 1, y - hh + 1.5f, PALE)
                        if (seed % 4 == 0) box(x - 2, y - hh * 0.5f, x + 2, y - hh * 0.5f + 0.7f, DARK) }
                }
            }
            i += 2
        }
    }

    private fun drawWards(time: Float) {
        for (w in wards) {
            if (w.room != roomId) continue
            if (w.life < 2f && (time * 12).toInt() % 2 == 0) continue
            val q = w.pts
            if (w.style != 0) { drawWall(w, time); continue }
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

    /** The firefly: it grows with your level, up to four times its size at 20, and grows grander as it goes. */
    private fun drawFly(time: Float) {
        val s = flySize()
        val x = flyX + sin(time * 2.1f) * 0.8f; val y = flyY + cos(time * 1.7f) * 0.8f
        val pulse = 0.5f + 0.5f * sin(time * 4f)
        val cool = lvl >= 14
        val tint = if (cool) 0xC8F0FF else 0xFFF0A0
        // a trail of light (from level 6)
        if (lvl >= 6) {
            val n = minOf(trailN, lvl - 4)
            for (i in 1 until n) {
                val a = 1f - i / n.toFloat()
                p.color = ((a * 120).toInt() shl 24) or tint
                sc.drawCircle(trail[i * 2], trail[i * 2 + 1], (0.9f * s * a + 0.5f), p)
            }
        }
        // the glow: soft rings, brighter toward the middle, breathing a little
        val rad = 2.2f * s
        p.color = ((0x18 + (pulse * 12).toInt()) shl 24) or tint; sc.drawCircle(x, y, rad * 2.6f, p)
        p.color = 0x30000000 or tint; sc.drawCircle(x, y, rad * 1.8f, p)
        p.color = 0x60000000 or tint; sc.drawCircle(x, y, rad * 1.25f, p)
        if (lvl >= 16) { line1.color = 0x88FFF4C0.toInt(); sc.drawCircle(x, y, rad * 2.2f + sin(time * 3f), line1) }
        if (lvl >= 17) {
            line1.color = 0x66FFF0A0
            for (i in 0 until 8) { val a = time * 1.2f + i * 0.785f; val r1 = rad * (if (i % 2 == 0) 3.2f else 2.6f); sc.drawLine(x + cos(a) * rad * 1.4f, y + sin(a) * rad * 1.4f, x + cos(a) * r1, y + sin(a) * r1, line1) }
        }
        // the ball itself: a bright body, a white-hot core, a glint
        p.color = if (cool) 0xFFB8E8FF.toInt() else 0xFFFFE070.toInt(); sc.drawCircle(x, y, rad, p)
        p.color = if (cool) 0xFFE8F8FF.toInt() else 0xFFFFF4C0.toInt(); sc.drawCircle(x, y, rad * 0.68f, p)
        p.color = 0xFFFFFFFF.toInt(); sc.drawCircle(x - rad * 0.12f, y - rad * 0.12f, maxOf(0.7f, rad * 0.38f), p)
        if (s >= 1.5f) { p.color = 0xFFFFFFFF.toInt(); sc.drawRect(x - rad * 0.5f, y - rad * 0.55f, x - rad * 0.5f + maxOf(1f, s * 0.5f), y - rad * 0.55f + maxOf(1f, s * 0.5f), p) }
        // motes circling it (12+), and at 20 it sparkles
        if (lvl >= 12) {
            val n = (lvl - 10) / 2
            for (i in 0 until n) { val a = time * 2.5f + i * 6.2832f / n; val mx = x + cos(a) * rad * 2.4f; val my = y + sin(a) * rad * 1.4f; box(mx - 0.7f, my - 0.7f, mx + 0.7f, my + 0.7f, if (i % 2 == 0) 0xFFFFF27A.toInt() else 0xFFC8F0FF.toInt()) }
        }
        if (lvl >= 20 && (time * 10).toInt() % 2 == 0) { val a = rnd.nextFloat() * 6.2832f; val r = rnd.nextFloat() * rad * 3f; box(x + cos(a) * r, y + sin(a) * r, x + cos(a) * r + 1, y + sin(a) * r + 1, WHITE) }
        if (selDown) { p.color = 0x88F8E070.toInt(); sc.drawRect(x - 4, y - 4, x + 4, y + 4, p) }
        if (levelFxT > 0f) {
            val k = 1f - levelFxT / 1.4f
            line1.color = if ((time * 12).toInt() % 2 == 0) GOLD else WHITE; sc.drawCircle(x, y, 4f + 34f * k, line1)
            for (i in 0 until 8) { val a = i * 0.785f + k * 3f; val r = 6f + 24f * k; box(x + cos(a) * r - 1, y + sin(a) * r - 1 - k * 6, x + cos(a) * r + 1, y + sin(a) * r + 1 - k * 6, GOLD) }
        }
        // what it's carrying back to you
        carried?.let { c0 ->
            val col = when (c0) { Loot.HERB -> 0xFF6FA84A.toInt(); Loot.BLOOM -> 0xFFF080B0.toInt(); else -> rarityCol(c0.rarity, time) }
            box(x - 0.5f, y + 2.2f * s, x + 0.5f, y + 2.2f * s + 3, DARK)
            val sy = y + 2.2f * s + 3
            box(x - 3, sy, x + 3, sy + 5, INK); box(x - 2, sy + 1, x + 2, sy + 4, col)
        }
    }

    /** The Flamedancer's firewall: a ragged line of flames where you ran, dying down as it goes. */
    private fun drawFires(time: Float) {
        for ((i, f) in fires.withIndex()) {
            if (f.room != roomId) continue
            val k = (f.life / 4f).coerceIn(0f, 1f)
            val hgt = (3f + 6f * k) * (0.65f + 0.35f * abs(sin(time * 13f + i * 1.7f)))
            box(f.x - 3, f.y, f.x + 3, f.y + 1.5f, 0x55301008)
            p.color = 0x30FF8020; sc.drawRect(f.x - 4, f.y - hgt - 2, f.x + 4, f.y + 1, p)
            box(f.x - 2, f.y - hgt * 0.6f, f.x + 2, f.y + 1, 0xFFC83018.toInt())
            box(f.x - 1.5f, f.y - hgt, f.x + 1, f.y, 0xFFF07820.toInt())
            box(f.x - 0.5f, f.y - hgt * 0.6f, f.x + 0.5f, f.y, 0xFFFFE070.toInt())
            if (((time * 8).toInt() + i) % 5 == 0) { val ey = f.y - hgt - 2 - (time * 20 + i * 3) % 6; box(f.x + sin(time + i) * 2, ey, f.x + sin(time + i) * 2 + 1, ey + 1, 0xFFFFC040.toInt()) }
        }
    }

    private fun drawFx(time: Float) {
        for (f in fxs) {
            val k = 1f - f.life / f.max
            when (f.type) {
                FX_NOVA -> {
                    val r = 6f + 38f * k
                    for (i in 0 until 18) {
                        val a = i * 0.349f + k; val c0 = cos(a); val s0 = sin(a)
                        line1.color = if (i % 2 == 0) LEAF_D else 0xFF4A6A3A.toInt()
                        sc.drawLine(f.x + c0 * (r - 6), f.y + s0 * (r - 6) * 0.6f, f.x + c0 * r, f.y + s0 * r * 0.6f, line1)
                        box(f.x + c0 * r - 1, f.y + s0 * r * 0.6f - 1, f.x + c0 * r + 1, f.y + s0 * r * 0.6f + 1, LEAF)
                    }
                    for (i in 0 until 8) { val a = i * 0.785f + 0.4f; val rr = r * 0.7f; box(f.x + cos(a) * rr, f.y + sin(a) * rr * 0.6f - k * 8, f.x + cos(a) * rr + 2, f.y + sin(a) * rr * 0.6f - k * 8 + 1, LEAF) }
                }
                FX_NOVA_STAR -> {
                    val r = 4f + 14f * k
                    for (i in 0 until 5) { val a = i * 1.2566f - 1.5708f + time; box(f.x + cos(a) * r - 1, f.y + sin(a) * r - 1, f.x + cos(a) * r + 1, f.y + sin(a) * r + 1, if (i % 2 == 0) 0xFFFFF27A.toInt() else WHITE) }
                }
                FX_MOON -> {
                    val r = 4f + 90f * k
                    for (i in 0 until 28) { val a = i * 0.2244f; box(f.x + cos(a) * r - 1, f.y + sin(a) * r * 0.7f - 1, f.x + cos(a) * r + 1, f.y + sin(a) * r * 0.7f + 1, if (i % 2 == 0) WHITE else 0xFFD8E8FF.toInt()) }
                    for (i in 0 until 6) { val ph = (k + i / 6f) % 1f; box(f.x - 20 + i * 8, f.y - 4 + ph * 20, f.x - 18 + i * 8, f.y - 3 + ph * 20, 0xFFE8D8C0.toInt()) }
                }
                FX_FROST -> {
                    for (i in 0 until 36) {
                        val ph = (i * 0.137f + k * 1.6f) % 1f; val spread = ((i * 7919) % 100) / 100f - 0.5f
                        val d = ph * 60f; val a = f.a + spread * (0.3f + ph * 0.8f)
                        val x = f.x + cos(a) * d; val y = f.y + sin(a) * d
                        box(x, y, x + (if (i % 3 == 0) 2 else 1), y + 1, if (i % 3 == 0) WHITE else if (i % 3 == 1) 0xFFA8E0F8.toInt() else 0xFF68C8E8.toInt())
                    }
                }
                FX_AEGIS -> {
                    val r = 6f + 18f * k
                    for (i in 0 until 6) { val a = i * 1.047f + k * 2f; val x = f.x + cos(a) * r; val y = f.y + sin(a) * r * 0.7f; box(x - 2, y - 2, x + 2, y + 2, DARK); box(x - 1, y - 2, x + 2, y + 1, MID); box(x - 1, y - 2, x, y - 1, PALE) }
                }
                FX_SHATTER -> {
                    for (i in 0 until 10) { val a = i * 0.628f; val r = 2f + 12f * k; box(f.x + cos(a) * r, f.y + sin(a) * r - k * 4, f.x + cos(a) * r + 2, f.y + sin(a) * r - k * 4 + 1, if (i % 2 == 0) WHITE else 0xFFA8E0F8.toInt()) }
                }
                FX_MEND -> {
                    p.color = (((1f - k) * 70).toInt() shl 24) or 0x78E088; sc.drawRect(f.x - 7, f.y - 14, f.x + 7, f.y + 6, p)
                    for (i in 0 until 12) { val ph = (i * 0.19f + k * 1.3f) % 1f; val x = f.x - 8 + (i * 37 % 17); val y = f.y + 6 - ph * 26; box(x, y, x + 1, y + 2, if (i % 3 == 0) 0xFFFFF27A.toInt() else 0xFF78E088.toInt()) }
                }
                FX_HEX -> {
                    val r = 8f + 110f * k
                    for (i in 0 until 32) { val a = i * 0.196f; box(f.x + cos(a) * r - 1, f.y + sin(a) * r * 0.6f, f.x + cos(a) * r + 1, f.y + sin(a) * r * 0.6f + 1, if (i % 2 == 0) RUNE else 0xFFB070E0.toInt()) }
                }
                FX_REVIVE -> {
                    p.color = (((1f - k) * 120).toInt() shl 24) or 0xFFF0A0; sc.drawRect(f.x - 5, 0f, f.x + 5, f.y + 2, p)
                    for (i in 0 until 10) { val ph = (i * 0.23f + k * 1.5f) % 1f; box(f.x - 6 + (i * 5 % 13), f.y - ph * 40, f.x - 5 + (i * 5 % 13), f.y - ph * 40 + 2, if (i % 2 == 0) WHITE else GOLD) }
                }
                FX_BIND -> {
                    // runic bands closing round it
                    val r = 14f - 8f * k
                    for (j in 0 until 12) { val a = j * 0.5236f + time * 3f; box(f.x + cos(a) * r - 0.7f, f.y + sin(a) * r * 0.5f - 0.7f, f.x + cos(a) * r + 0.7f, f.y + sin(a) * r * 0.5f + 0.7f, if (j % 2 == 0) GOLD else RUNE) }
                    line1.color = RUNE; sc.drawLine(f.x - r, f.y - 3, f.x + r, f.y - 3, line1); sc.drawLine(f.x - r, f.y + 2, f.x + r, f.y + 2, line1)
                }
                FX_HASTE -> {
                    for (j in 0 until 10) { val a = j * 0.628f + k * 6f; val r = 6f + 12f * k; box(f.x + cos(a) * r, f.y + sin(a) * r * 0.6f, f.x + cos(a) * r + 3, f.y + sin(a) * r * 0.6f + 1, if (j % 2 == 0) 0xFFC8F0FF.toInt() else WHITE) }
                }
                FX_WALL -> {
                    val col = when (f.a.toInt()) { 1 -> LEAF; 2 -> 0xFFD8E8FF.toInt(); 3 -> 0xFFA8E0F8.toInt(); else -> PALE }
                    for (j in 0 until 12) { val a = j * 0.5236f; val r = 3f + 16f * k; box(f.x + cos(a) * r - 1, f.y + sin(a) * r * 0.5f - 1 - k * 6, f.x + cos(a) * r + 1, f.y + sin(a) * r * 0.5f + 1 - k * 6, col) }
                }
                FX_STRIKE -> {
                    // a heavy swipe: three bright claw lines and a flash
                    val dir = if (f.a < 0) -1f else 1f
                    line2.color = if ((time * 30).toInt() % 2 == 0) WHITE else GOLD
                    for (j in -1..1) sc.drawLine(f.x - 6 * dir + j * 2, f.y - 6 + j * 3, f.x + 6 * dir + j * 2, f.y + 4 + j * 3 - k * 2, line2)
                    p.color = (((1f - k) * 120).toInt() shl 24) or 0xFFFFFF; sc.drawCircle(f.x, f.y, 4f + 8f * k, p)
                }
                FX_JOIN -> {
                    for (i in 0 until 8) { val a = i * 0.785f; val r = 4f + 16f * k; box(f.x + cos(a) * r - 1, f.y + sin(a) * r - 1, f.x + cos(a) * r + 1, f.y + sin(a) * r + 1, if (i % 2 == 0) GOLD else WHITE) }
                }
            }
        }
        // heals on their way: a stream of motes from your companion to whoever needs it
        for (b in beams) {
            val k = 1f - b.life / 0.6f
            val tx = if (b.toFriend) frX else hx + 8; val ty = if (b.toFriend) frY - 8 else hy + 6
            for (j in 0 until 7) {
                val t = (k * 1.4f - j * 0.08f).coerceIn(0f, 1f)
                val mx = b.x + (tx - b.x) * t + sin(t * 9f + j) * 3f; val my = b.y + (ty - b.y) * t - sin(t * 3.1416f) * 14f
                box(mx - 1, my - 1, mx + 1, my + 1, if (j % 2 == 0) 0xFF78E088.toInt() else 0xFFFFF27A.toInt())
            }
        }
        // Bulwark: a golden ring round you
        if (bulwarkT > 0f) { line1.color = if ((time * 6).toInt() % 2 == 0) GOLD else 0x88F0CC50.toInt(); sc.drawOval(hx - 1, hy - 2, hx + 17, hy + 17, line1) }
        if (hasteT > 0f && (dirX != 0 || dirY != 0)) for (i in 1..3) box(hx + 8 - dirX * i * 5f, hy + 4 + i, hx + 8 - dirX * i * 5f + 1, hy + 12 - i, 0x88C8F0FF.toInt())
        // the Stone Aegis: rocks circling you while it holds
        if (shieldHits > 0) for (i in 0 until shieldHits + 1) {
            val a = time * 4f + i * 6.2832f / (shieldHits + 1); val x = hx + 8 + cos(a) * 11f; val y = hy + 8 + sin(a) * 6f
            box(x - 2, y - 2, x + 2, y + 2, DARK); box(x - 1, y - 2, x + 2, y + 1, MID); box(x - 1, y - 2, x, y - 1, PALE)
        }
        // falling stars and lightning
        for (st in stars) {
            if (st.delay > 0f) continue
            if (st.bolt) {
                line2.color = if ((time * 30).toInt() % 2 == 0) WHITE else 0xFFFFF27A.toInt()
                var x0 = st.x + 4; var y0 = 12f
                for (j in 1..6) { val y1 = 12f + (st.y - 12f) * j / 6f; val x1 = st.x + (if (j < 6) (rnd.nextFloat() - 0.5f) * 10f else 0f); sc.drawLine(x0, y0, x1, y1, line2); x0 = x1; y0 = y1 }
            } else {
                val k = (st.t / 0.32f).coerceIn(0f, 1f)
                val sx = st.x - 40f * (1 - k); val sy = st.y - 120f * (1 - k)
                for (j in 1 until 7) { p.color = ((200 - j * 28) shl 24) or 0xFFF27A; sc.drawRect(sx - j * 1.3f, sy - j * 4f, sx - j * 1.3f + 1.5f, sy - j * 4f + 1.5f, p) }
                box(sx - 2, sy - 2, sx + 2, sy + 2, 0xFFFFF27A.toInt()); box(sx - 1, sy - 3, sx + 1, sy + 3, WHITE); box(sx - 3, sy - 1, sx + 3, sy + 1, WHITE)
            }
        }
    }

    private fun heart(x: Float, y: Float, col: Int) {
        val rows = arrayOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...")
        for (r in rows.indices) for (k in 0 until 7) if (rows[r][k] == 'X') box(x + k, y + r, x + k + 1, y + r + 1, col)
    }

    private fun drawHud(time: Float) {
        if (phase == Phase.INTRO) return
        box(0f, 0f, W.toFloat(), 10f, INK)
        // the traveller's health: a bar, green going to red
        font.color = WHITE; sc.drawText("HP", 2f, 9f, font); font.color = INK
        box(19f, 2f, 71f, 8f, DARK)
        val f = hp / maxHp.toFloat()
        val hc = when { f > 0.5f -> 0xFF48C058.toInt(); f > 0.25f -> 0xFFE8C048.toInt(); else -> if ((time * 6).toInt() % 2 == 0) 0xFFE04848.toInt() else 0xFFA02828.toInt() }
        box(20f, 3f, 20f + 50f * f, 7f, hc)
        // your level, and the experience toward the next as a gold line under the health
        font.color = GOLD; sc.drawText("L$lvl", 74f, 9f, font); font.color = INK
        box(20f, 8f, 20f + 50f * (if (lvl >= 20) 1f else xp / xpNeed(lvl).toFloat()), 9f, GOLD)
        var sx = 100f
        fun tag(t: String, col: Int) { box(sx, 1f, sx + 9, 9f, col); font.color = WHITE; sc.drawText(t, sx + 1, 9f, font); font.color = INK; sx += 11 }
        if (paraT > 0f) tag("B", RUNE)
        if (poisonT > 0f) tag("V", 0xFF48A048.toInt())
        if (invertT > 0f) tag("M", 0xFFD07020.toInt())
        if (shieldHits > 0) tag("S", DARK)
        if (hasteT > 0f) tag("H", 0xFF3CA0C8.toInt())
        if (bulwarkT > 0f) tag("W", GOLD_D)
        // your companion's health
        val k = friend
        if (k != null && frState != 0) {
            box(0f, 10f, 74f, 19f, INK)
            font.color = if (frState == 2) 0xFFE04848.toInt() else WHITE; sc.drawText(k.label.take(1), 2f, 18f, font)
            box(11f, 12f, 71f, 17f, DARK)
            if (frState == 2) { if ((time * 2).toInt() % 2 == 0) { font.color = 0xFFE04848.toInt(); sc.drawText("K.O.", 26f, 18f, font) } }
            else {
                box(12f, 13f, 12f + 58f * frHp / k.maxHp, 16f, 0xFF58B0E0.toInt())
                if (frSkill <= 0f && (time * 3).toInt() % 2 == 0) box(68f, 13f, 70f, 16f, WHITE)
            }
            font.color = INK
        }
        // the Runecaster's three runes, and how soon each is ready
        if (bag.wearing(Loot.RUNECASTER)) {
            box(76f, 10f, 150f, 21f, INK)
            for (i in 0 until 6) {
                val l = 78f + i * 12f; val ready = spellCd[i] <= 0f
                line1.color = if (ready) GOLD else DARK
                val pts = spellShapes[i]
                for (j in 1 until pts.size) sc.drawLine(l + pts[j - 1].first * 8f, 11f + pts[j - 1].second * 7f, l + pts[j].first * 8f, 11f + pts[j].second * 7f, line1)
                if (!ready) box(l, 19f, l + 9f * (1f - spellCd[i] / spellCdMax[i]).coerceIn(0f, 1f), 20f, GOLD)
            }
        }
        // his health, with a notch where his last stand begins
        val l = 140f; val r = 236f
        box(l, 2f, r, 8f, DARK)
        val fill = (r - l - 2) * bossHp / BOSS_MAX
        box(l + 1, 3f, l + 1 + fill, 7f, if (bossHurtT > 0f && (time * 20).toInt() % 2 == 0) WHITE else if (stage == Stage.DAIS) 0xFFB040C0.toInt() else 0xFFE04848.toInt())
        val notch = l + 1 + (r - l - 2) * LAST / BOSS_MAX
        box(notch, 1f, notch + 1, 9f, WHITE)
    }

    /** The Runecaster's book: six runes; trace one on the XY pad before the bar runs out. */
    private fun drawRuneBook(time: Float) {
        if (runePromptT <= 0f || (castRune >= 0 && throne)) return
        val l = 14f; val t = 18f; val r = 226f; val b = 132f
        box(l - 1, t - 1, r + 1, b + 1, INK); box(l, t, r, b, WHITE); box(l + 1, t + 1, r - 1, b - 1, 0xFFF4F0E4.toInt())
        font.color = RUNE; sc.drawText("DRAW A RUNE ON THE PAD", l + 6, t + 10, font)
        for (i in 0 until 6) {
            // two columns of three: foes on the left (red), friends on the right (green)
            val cx = l + 4 + (i / 3) * 104f; val cy = t + 15 + (i % 3) * 32f
            val ready = spellCd[i] <= 0f
            box(cx, cy, cx + 100, cy + 29, if (ready) WHITE else PALE)
            line2.color = if (!ready) MID else if (i < 3) 0xFFB04040.toInt() else 0xFF3C8048.toInt()
            val pts = spellShapes[i]; val g = 21f
            for (j in 1 until pts.size) sc.drawLine(cx + 4 + pts[j - 1].first * g, cy + 4 + pts[j - 1].second * g, cx + 4 + pts[j].first * g, cy + 4 + pts[j].second * g, line2)
            font.color = if (ready) INK else MID
            sc.drawText(SPELLS[i], cx + 30, cy + 12, font)
            font.color = if (ready) DARK else MID
            sc.drawText(if (ready) SPELL_ABOUT[i] else "WAIT ${spellCd[i].toInt() + 1}S", cx + 30, cy + 24, font)
        }
        font.color = INK
        box(l + 4, b - 6, r - 4, b - 3, PALE); box(l + 4, b - 6, l + 4 + (r - l - 8) * (runePromptT / RUNE_TIME).coerceIn(0f, 1f), b - 3, RUNE)
        if (stroke.size > 1 && xyRect.width() > 0) {
            line1.color = 0xFFE04848.toInt()
            fun mx(v: Float) = r - 30 + ((v - xyRect.left) / xyRect.width()) * 26
            fun my(v: Float) = t + 2 + ((v - xyRect.top) / xyRect.height()) * 11
            for (k in 1 until stroke.size) sc.drawLine(mx(stroke[k - 1][0]), my(stroke[k - 1][1]), mx(stroke[k][0]), my(stroke[k][1]), line1)
        }
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

    /** A message box, word-wrapped to fit the little screen (at most 27 letters a line). */
    private fun banner(text: String, y: Float) {
        val lines = wrap(text, 27).ifEmpty { return }
        val bw = lines.maxOf { it.length } * 8f + 8; val bxx = (W - bw) / 2
        val bh = lines.size * 11f + 2
        val top = y.coerceAtMost(H - 4f - bh)
        box(bxx - 1, top - 1, bxx + bw + 1, top + bh + 1, INK); box(bxx, top, bxx + bw, top + bh, WHITE)
        lines.forEachIndexed { i, l -> sc.drawText(l, bxx + 4, top + 10 + i * 11f, font) }
    }

    private fun drawOverlays(time: Float) {
        if (bannerT > 0f && castRune < 0) banner(room.name, 14f)
        if (msgT > 0f && msg.isNotEmpty() && !talking) banner(msg, H - 58f)
        if (talking) return
        if (phase == Phase.LOST) { banner("YOU FELL...", 70f); if ((time * 2).toInt() % 2 == 0) banner("A: TRY AGAIN", 86f) }
        if (phase == Phase.WON && chestOpen && nearChest() && (time * 2).toInt() % 2 == 0) banner("A: PLAY AGAIN", 86f)
        if (nearSpirit()) banner("A: SPEAK", 90f)
        if (phase == Phase.WON && roomId == 1 && !chestOpen && nearChest() && !nearSpirit()) banner("A: OPEN", 90f)
        else if ((phase == Phase.FIGHT || phase == Phase.WON) && msgT <= 0f) reachable().takeIf { it >= 0 }?.let { banner("A: " + actLabel(room.props[it].kind), H - 58f) }
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
        val b = battle
        if (b != null) b.draw(sc, time)
        else drawHall(time)
        if (bagOpen) drawBag(time)
        paintShell(c, time)
    }

    /** The stroke on the XY pad, as fractions of the pad, for the duel's rune windows. */
    private fun strokeNorm(): List<FloatArray> = if (xyRect.width() <= 0f) emptyList() else stroke.map { floatArrayOf((it[0] - xyRect.left) / xyRect.width(), (it[1] - xyRect.top) / xyRect.height()) }

    private fun drawHall(time: Float) {
        font.color = INK   // (the duel leaves it white)
        drawRoom(time)
        drawFires(time)
        drawWards(time)
        drawRings()
        drawScene(time)
        drawFx(time)
        drawShots(time)
        drawArcs()
        drawFly(time)
        art.vignette(sc)
        if (flashT > 0f) { p.color = (((flashT / 0.25f) * 0x80).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF; sc.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        if (hurtFlashT > 0f) {
            // a red edge round the screen when you're hurt
            p.color = (((hurtFlashT / 0.3f) * 0xA0).toInt().coerceIn(0, 255) shl 24) or 0xE02020
            sc.drawRect(0f, 0f, W.toFloat(), 3f, p); sc.drawRect(0f, H - 3f, W.toFloat(), H.toFloat(), p); sc.drawRect(0f, 3f, 3f, H - 3f, p); sc.drawRect(W - 3f, 3f, W.toFloat(), H - 3f, p)
        }
        drawHud(time)
        drawRune(time)
        drawRuneBook(time)
        drawOverlays(time)
        drawTalk(time)
    }

    /** The bag: weapon and charm worn, everything found; A equips (or eats a herb), B closes. */
    private fun drawBag(time: Float) {
        val l = 14f; val t = 12f; val r = W - 14f; val b = H - 12f
        val rows = bagRows()
        box(l - 1, t - 1, r + 1, b + 1, INK); box(l, t, r, b, WHITE); box(l + 2, t + 2, r - 2, b - 2, INK); box(l + 3, t + 3, r - 3, b - 3, WHITE)
        sc.drawText("BAG ${bag.owned.size}/${bag.CAPACITY}", l + 8, t + 13, font)
        font.color = DARK
        sc.drawText("HALL ${bag.wornCount(Loot.FIELD)}/3  DUEL ${bag.wornCount(Loot.DUEL)}/3", l + 98, t + 13, font)
        font.color = INK
        box(l + 6, t + 17, r - 6, t + 18, MID)
        if (rows.isEmpty()) sc.drawText("NOTHING YET. LOOT IS RARE!", l + 10, t + 34, font)
        // nine rows at a time, scrolling with the cursor
        val view = 9
        val first = (bagCursor - view + 1).coerceAtLeast(0).coerceAtMost((rows.size - view).coerceAtLeast(0))
        for (i in first until minOf(rows.size, first + view)) {
            val it = rows[i]; val y = t + 31 + (i - first) * 11f
            if (i == bagCursor && (time * 3).toInt() % 2 == 0) sc.drawText(">", l + 6, y, font)
            val tag = when (it.mode) { Loot.FIELD -> "H"; Loot.DUEL -> "D"; else -> " " }
            if (!it.consumable) { box(l + 15, y - 8, l + 24, y + 1, if (it.mode == Loot.FIELD) 0xFF48A048.toInt() else RUNE); font.color = WHITE; sc.drawText(tag, l + 16, y, font); font.color = INK }
            font.color = if (it.consumable || it.rarity == Rarity.COMMON) INK else rarityCol(it.rarity, time)
            sc.drawText(when (it) { Loot.HERB -> "HERB x${bag.herbs} (EAT)"; Loot.BLOOM -> "SPIRIT BLOOM x${bag.blooms}"; else -> it.label }, l + 28, y, font)
            font.color = INK
            if (bag.wearing(it)) { font.color = 0xFF48A048.toInt(); sc.drawText("WORN", r - 42, y, font); font.color = INK }
        }
        // what the selected one does
        box(l + 6, b - 24, r - 6, b - 23, MID)
        rows.getOrNull(bagCursor)?.let {
            if (!it.consumable) { font.color = rarityCol(it.rarity, time); sc.drawText(it.rarity.label + if (it.mode == Loot.FIELD) " - HALL" else " - DUEL", l + 8, b - 14, font) }
            font.color = DARK; sc.drawText(it.about.take(27), l + 8, b - 4, font); font.color = INK
        }
    }

    private fun paintShell(c: Canvas, time: Float) {
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
        battle?.drawHiRes(c, screenRect, time, strokeNorm())

        exitRect.set(Px.dp(10f), Px.dp(6f), Px.dp(116f), Px.dp(36f))
        val pw = Px.dp(150f)
        if (!portrait) {
            val colW = sl - Px.dp(14f)
            // left column: L, then the D-pad
            val dcx = colW / 2 + Px.dp(4f)
            val pwl = minOf(pw, colW - Px.dp(8f))
            selRect.set(dcx - pwl / 2, Px.dp(92f), dcx + pwl / 2, Px.dp(122f))
            val ds = minOf(Px.dp(150f), colW - Px.dp(24f), h - Px.dp(130f)).coerceAtLeast(Px.dp(80f))
            val dcy = (Px.dp(80f) + h) / 2
            // L sits just above the D-pad
            val padTop = dcy - minOf(Px.dp(150f), colW - Px.dp(24f), h - Px.dp(130f)).coerceAtLeast(Px.dp(80f)) / 2
            if (selRect.bottom > padTop - Px.dp(14f)) selRect.offset(0f, padTop - Px.dp(14f) - selRect.bottom)
            pad.set(dcx - ds / 2, dcy - ds / 2, dcx + ds / 2, dcy + ds / 2)
            // right column: R, A and B, then the XY pad
            val rcx = sl + sw + Px.dp(10f) + colW / 2
            // R sits higher, leaving more room between it and A / B (which keep their slant)
            startRect.set(rcx - pwl / 2, Px.dp(56f), rcx + pwl / 2, Px.dp(86f))
            val r = minOf(Px.dp(22f), colW / 6f).coerceAtLeast(Px.dp(14f))
            val aby = startRect.bottom + Px.dp(38f) + r
            aKey.set(rcx + r * 0.3f, aby - r - Px.dp(3f), rcx + r * 2.3f, aby + r - Px.dp(3f))
            bKey.set(rcx - r * 2.3f, aby - r + Px.dp(3f), rcx - r * 0.3f, aby + r + Px.dp(3f))
            val xt = aby + r + Px.dp(18f); val xb = h - Px.dp(14f)
            // the pad has the game screen's shape, so the firefly goes where your finger points
            val xw = minOf(colW - Px.dp(8f), (xb - xt) * W / H)
            val xh = xw * H / W; val xtop = xt + (xb - xt - xh) / 2
            xyRect.set(rcx - xw / 2, xtop, rcx + xw / 2, xtop + xh)
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
            val xw = minOf(third - Px.dp(12f), (xb - xt) * W / H); val xh = xw * H / W
            xyRect.set(w / 2 - xw / 2, xt, w / 2 + xw / 2, xt + xh)
        }
        bagRect.set(exitRect.right + Px.dp(8f), exitRect.top, minOf(exitRect.right + Px.dp(80f), sl - Px.dp(14f)).coerceAtLeast(exitRect.right + Px.dp(40f)), exitRect.bottom)
        pill(c, exitRect, "◀ Music", Snes.GREEN, exitDown)
        pill(c, bagRect, "START", Snes.YELLOW, bagOpen)
        pill(c, selRect, "L CONSECRATE", Snes.PURPLE, selDown)
        pill(c, startRect, "R VITAL SPARK", Snes.GREEN, startDown)

        if (xyRect.height() > Px.dp(30f)) {
            shellP.color = Snes.INK; c.drawRect(xyRect.left - Px.dp(2f), xyRect.top - Px.dp(2f), xyRect.right + Px.dp(2f), xyRect.bottom + Px.dp(2f), shellP)
            shellP.color = 0xFF2A2B40.toInt(); c.drawRect(xyRect, shellP)
            shellP.color = 0x22FFFFFF
            for (k in 1 until 6) { val fx = xyRect.left + xyRect.width() * k / 6f; c.drawRect(fx, xyRect.top, fx + 1, xyRect.bottom, shellP) }
            for (k in 1 until 4) { val fy = xyRect.top + xyRect.height() * k / 4f; c.drawRect(xyRect.left, fy, xyRect.right, fy + 1, shellP) }
            if (tracing) {
                shellP.color = Snes.RED
                for (k in 1 until stroke.size) c.drawCircle(stroke[k][0], stroke[k][1], Px.dp(3f), shellP)
                label.textSize = Px.dp(9f); label.color = 0x88FFFFFF.toInt()
                c.textCentered(if (runePromptT > 0f && !(castRune >= 0 && throne)) "DRAW A RUNE" else "DRAW THE RUNE", xyRect.centerX(), xyRect.top + Px.dp(12f), label)
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

    private val bagRect = RectF()
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
        if (tracing) {
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
        val oldY = dirY
        if (abs(dx) < pad.width() * 0.08f && abs(dy) < pad.height() * 0.08f) { dirX = 0; dirY = 0; return }
        if (abs(dx) > abs(dy)) { dirX = if (dx > 0) 1 else -1; dirY = 0 } else { dirY = if (dy > 0) 1 else -1; dirX = 0 }
        // in the duel and the bag, up / down move the cursor
        if ((battle != null || bagOpen) && dirY != 0 && dirY != oldY) menuStep(dirY)
    }

    private fun pressA() {
        battle?.let { it.pressA(); return }
        if (bagOpen) { useBag(); return }
        if (talking) {
            if (shown < talkLines[line].length) shown = talkLines[line].length.toFloat()
            else if (line < talkLines.size - 1) { line++; shown = 0f } else endTalk()
        } else if (phase == Phase.LOST) restart()
        else if (phase == Phase.WON) {
            if (nearSpirit()) speakToSpirit()
            else if (roomId == 1 && !chestOpen && nearChest()) {
                chestOpen = true; hp = maxHp; bursts += Burst(120f, 58f)
                val a = if (Loot.STARHEART in bag.owned) rollLoot(0f) else Loot.STARHEART
                val b = if (Loot.THUNDER_CROWN in bag.owned) rollLoot(0f) else Loot.THUNDER_CROWN
                takeLoot(a); takeLoot(b)
                talking = true; talkLines = CHEST_LINES + "BENEATH THE GOLD, TWO CHARMS: ${a.label} AND ${b.label}!"; line = 0; shown = 0f
            } else if (chestOpen && nearChest()) restart()
            else actOrSprint()
        } else if (phase == Phase.FIGHT) actOrSprint()
    }

    // ---- A out and about: use what's in reach, or sprint
    private val actKinds = setOf(FOUNTAIN, TOMB, TABLE, STATUE, BRAZIER, SHRUB)
    private fun reachable(): Int {
        val fx = hx + 8; val fy = hy + 13
        var best = -1; var bd = 12f
        room.props.forEachIndexed { i, pp ->
            if (pp.kind !in actKinds) return@forEachIndexed
            val f = pp.foot
            val dx = maxOf(f.left - fx, 0f, fx - f.right); val dy = maxOf(f.top - fy, 0f, fy - f.bottom)
            val d = hypot(dx, dy)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }
    private fun actLabel(kind: Int) = when (kind) { FOUNTAIN -> "DRINK"; TOMB -> "SEARCH"; TABLE -> "READ"; STATUE -> "LOOK"; BRAZIER -> "WARM UP"; else -> "RUMMAGE" }
    private val LORE = listOf(
        "\"HE HAS FIVE EYES AND NOT ONE OF THEM FORGETS.\"",
        "\"TRACE THE STAR WHEN HE DRAWS IT, OR DANCE.\"",
        "\"THE IMPS FEAR A STAMPING FOOT.\"",
        "\"WHAT IS HOLY CAN CARRY YOU, IF YOU CIRCLE IT.\"",
        "\"THE CRACK IN THE WALL HIDES HIS HOARD.\"",
        "\"A FRIEND WHO FALLS MAY BLOOM AGAIN.\"",
        "\"FIRE REMEMBERS THE PATH OF THE QUICK.\"",
    )
    private fun actOrSprint() {
        val i = reachable()
        if (i < 0) { sprint(); return }
        val pp = room.props[i]; val key = "$roomId:$i"
        when (pp.kind) {
            FOUNTAIN -> if (interactCd > 0f) say("THE WATER IS STILL. LATER.") else { interactCd = 30f; hp = minOf(maxHp, hp + 20); bursts += Burst(hx + 8, hy + 6); say("+20 HP. COLD AND CLEAR.") }
            BRAZIER -> if (interactCd > 0f) say("THE COALS ARE LOW. LATER.") else {
                interactCd = 20f; poisonT = 0f; paraT = 0f; invertT = 0f; hp = minOf(maxHp, hp + 8); say("WARMTH. YOUR AILMENTS BURN AWAY.")
            }
            TABLE -> if (key in searched) say(LORE[(i + roomId) % LORE.size]) else {
                searched += key; say(LORE[(i + roomId) % LORE.size])
                if (rnd.nextFloat() < 0.15f) drops += Drop(pp.foot.centerX(), pp.foot.bottom + 5, roomId, Loot.HERB)
            }
            TOMB -> if (key in searched) say("ALREADY EMPTY.") else {
                searched += key
                val r = rnd.nextFloat()
                when {
                    r < 0.3f -> { drops += Drop(pp.foot.centerX(), pp.foot.bottom + 5, roomId, rollLoot(0.3f)); say("SOMETHING GLINTS INSIDE!") }
                    r < 0.6f -> { spawnMinion(pp.foot.centerX() to pp.foot.bottom + 8); say("SOMETHING CLIMBS OUT!") }
                    else -> say("ONLY DUST.")
                }
            }
            STATUE -> if (key in searched) say("AN OLD BUST. IT STARES.") else {
                searched += key
                if (rnd.nextFloat() < 0.25f) { drops += Drop(pp.foot.centerX(), pp.foot.bottom + 5, roomId, rollLoot(0.6f)); say("A LOOSE STONE! SOMETHING FALLS OUT.") }
                else say("AN OLD BUST. ONE EYE IS LOOSE.")
            }
            else -> if (key in searched) say("NOTHING MORE IN HERE.") else {
                searched += key
                if (rnd.nextFloat() < 0.25f) { drops += Drop(pp.foot.centerX(), pp.foot.bottom + 5, roomId, Loot.HERB); say("A HERB IN THE LEAVES!") } else say("JUST LEAVES.")
            }
        }
    }
    private fun say(s: String) { msg = s; msgT = 2f }
    /** A short burst of speed (and a moment's dodge), with a short cool-down. */
    private fun sprint() {
        if (sprintCd > 0f || paraT > 0f) return
        val flame = bag.wearing(Loot.FLAMEDANCER)
        sprintT = if (flame) 0.56f else 0.28f
        sprintCd = (if (bag.wearing(Loot.WIND_BOOTS)) 0.6f else 1.2f) * (if (flame) 2f else 1f)
        invulnT = maxOf(invulnT, if (flame) 0.5f else 0.25f)
        rings += Ring(hx + 8, hy + 14); dashId++
        sfx.play(if (flame) HallSfx.S.FIREDASH else HallSfx.S.SPRINT)
    }
    /** B: closes the talk box or the bag; otherwise it's the ground stomp. */
    private fun pressB() {
        if (battle != null) return
        if (bagOpen) { bagOpen = false; return }
        if (talking) endTalk() else stomp()
    }
    private fun openBag() { if (battle == null && (phase == Phase.FIGHT || phase == Phase.WON)) { bagOpen = true; bagCursor = 0 } }
    private fun bagRows(): List<Loot> = (if (bag.herbs > 0) listOf(Loot.HERB) else emptyList()) + (if (bag.blooms > 0) listOf(Loot.BLOOM) else emptyList()) + bag.owned
    private fun useBag() {
        val l = bagRows().getOrNull(bagCursor) ?: return
        if (l == Loot.HERB) { if (hp < maxHp) { bag.herbs--; hp = minOf(maxHp, hp + 35); msg = "+35 HP"; msgT = 1f; sfx.play(HallSfx.S.ORB) } }
        else if (l == Loot.BLOOM) reviveFriend()
        else if (!bag.toggle(l)) { msg = "THREE ALREADY WORN"; msgT = 1.2f }
        else if (l == Loot.RUNECASTER && bag.wearing(l)) say(RUNE_HINT, 6f)
        bagCursor = bagCursor.coerceAtMost((bagRows().size - 1).coerceAtLeast(0))
    }
    private fun menuStep(d: Int) {
        battle?.let { if (d < 0) it.up() else it.down(); return }
        if (bagOpen) { val n = bagRows().size.coerceAtLeast(1); bagCursor = Math.floorMod(bagCursor + d, n) }
    }
    /** When he's done shouting, the fight starts. */
    private fun endTalk() {
        talking = false
        if (phase == Phase.WON && !bossGone && perishT == 0f && talkLines === DEFEAT) { perishT = 0.001f; shakeT = 0.4f; sfx.play(HallSfx.S.PERISH); sfx.rumbleFade() }
        if (phase == Phase.INTRO) { phase = Phase.FIGHT; msg = "FIGHT!"; msgT = 1.4f; spellT = 6f; bossShotT = 2f; summonT = 1f; if (friend == null) spawnFriend() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); sfx.prepare() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); sfx.release() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i); val y = e.getY(i)
                when {
                    exitRect.contains(x, y) -> { exitDown = true; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    bagRect.contains(x, y) -> { if (bagOpen) bagOpen = false else openBag(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
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
