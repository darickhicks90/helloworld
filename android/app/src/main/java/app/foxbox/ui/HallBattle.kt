package app.foxbox.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Loot is rare, and comes in tiers: common, rare, epic, legendary and exotic (exotics change how one
 * of your own abilities works). Each find is either for moving about the hall (FIELD, three can be
 * worn) or for the duel (DUEL, three can be worn); herbs heal and spirit blooms revive a companion.
 */
/** Where a piece of loot is worn (top level: an enum's entries can't use its own companion). */
private const val LOOT_FIELD = 0
private const val LOOT_DUEL = 1
private const val LOOT_EAT = 2

/** How rare a find is: its name, the colour it's shown in, and how often it turns up. */
enum class Rarity(val label: String, val color: Int, val weight: Float) {
    COMMON("COMMON", 0xFF8A8A9A.toInt(), 55f),
    RARE("RARE", 0xFF3C7CE0.toInt(), 30f),
    EPIC("EPIC", 0xFF9A44D0.toInt(), 11f),
    LEGENDARY("LEGENDARY", 0xFFE8A020.toInt(), 3f),
    EXOTIC("EXOTIC", 0xFF20C0B0.toInt(), 1.2f),
}

enum class Loot(val label: String, val mode: Int, val about: String, val rarity: Rarity, val dropped: Boolean = true) {
    // ---- for the hall
    WIND_BOOTS("WIND BOOTS", LOOT_FIELD, "SPRINT RECHARGES TWICE AS FAST", Rarity.COMMON),
    EMBER_RING("EMBER RING", LOOT_FIELD, "SPARK BURNS: MORE DAMAGE, REACH", Rarity.RARE),
    FROST_RING("FROST RING", LOOT_FIELD, "STOMP FREEZES; SHATTERS FROZEN", Rarity.LEGENDARY),
    STORM_RING("STORM RING", LOOT_FIELD, "SPARK CHAINS TO FIVE", Rarity.EPIC),
    WARD_STONE("WARD STONE", LOOT_FIELD, "CONSECRATE LASTS LONGER", Rarity.COMMON),
    LANTERN("LANTERN", LOOT_FIELD, "MORE BALLS OF LIGHT", Rarity.COMMON),
    FEATHER_CLOAK("FEATHER CLOAK", LOOT_FIELD, "BLINK: SHIELD AND SWIFTNESS", Rarity.RARE),
    IRON_SKIN("IRON SKIN", LOOT_FIELD, "TAKE A FIFTH LESS DAMAGE", Rarity.RARE),
    LODESTONE("LODESTONE", LOOT_FIELD, "LOOT TWICE AS LIKELY", Rarity.EPIC),
    /** His own axe, taken when he falls: worn in the hall, the stomp cleaves; in a duel, strikes bite deep. */
    FIVE_EYED_AXE("FIVE-EYED AXE", LOOT_FIELD, "STOMP CLEAVES; DUEL STRIKES +15", Rarity.LEGENDARY, dropped = false),
    /** In the treasure chest: two legendary charms for the Vital Spark. */
    STARHEART("STARHEART", LOOT_FIELD, "SPARK CHAINS TO 6; NOVAS BURST", Rarity.LEGENDARY, dropped = false),
    THUNDER_CROWN("THUNDER CROWN", LOOT_FIELD, "EVERY 4TH SPARK: SKY LIGHTNING", Rarity.LEGENDARY, dropped = false),
    GLEANERS_LAMP("GLEANER'S LAMP", LOOT_FIELD, "YOUR LIGHT FETCHES LOOT", Rarity.LEGENDARY),
    /** Hers, taken when she falls. */
    BLOSSOM_DAGGER("BLOSSOM DAGGER", LOOT_FIELD, "SPARK HITS HEAL YOU A LITTLE", Rarity.LEGENDARY, dropped = false),
    // ---- exotics: each trades one of your abilities for something stranger
    RUNECASTER("RUNECASTER", LOOT_FIELD, "BLINK OPENS A BOOK OF 6 RUNES", Rarity.EXOTIC),
    FLAMEDANCER("FLAMEDANCER", LOOT_FIELD, "SPRINT: LONG, BURNING FIREWALL", Rarity.EXOTIC),
    // ---- for the duel
    EMBERBRAND("EMBERBRAND", LOOT_DUEL, "STRIKES SET HIM BURNING", Rarity.RARE),
    FROSTFANG("FROSTFANG", LOOT_DUEL, "STRIKES MAY FREEZE HIS TURN", Rarity.RARE),
    STORMCALLER("STORMCALLER", LOOT_DUEL, "THUNDER: A HUGE BOLT, ONCE", Rarity.EPIC),
    VENOM_THORN("VENOM THORN", LOOT_DUEL, "PARRIES POISON HIM", Rarity.COMMON),
    AEGIS_SHARD("AEGIS SHARD", LOOT_DUEL, "SOFTEN MORE; BLOCKS HEAL", Rarity.RARE),
    MIRROR_CHARM("MIRROR CHARM", LOOT_DUEL, "PARRIES RETURN THE BLOW", Rarity.EPIC),
    SUN_LOCKET("SUN LOCKET", LOOT_DUEL, "HEAL A LITTLE EACH TURN", Rarity.COMMON),
    RUNE_LENS("RUNE LENS", LOOT_DUEL, "MORE TIME TO TRACE", Rarity.COMMON),
    ECHO_BELL("ECHO BELL", LOOT_DUEL, "ECHO: STRIKE TWICE", Rarity.EPIC),
    HERB("HERB", LOOT_EAT, "HEALS 35", Rarity.COMMON, dropped = false),
    /** Brings a fallen companion back where it lies. */
    BLOOM("SPIRIT BLOOM", LOOT_EAT, "REVIVES YOUR COMPANION", Rarity.RARE, dropped = false);
    val consumable get() = mode == LOOT_EAT
    companion object {
        const val FIELD = LOOT_FIELD; const val DUEL = LOOT_DUEL
        val fieldLoot get() = values().filter { it.mode == FIELD }
        val duelLoot get() = values().filter { it.mode == DUEL }
    }
}

class Bag {
    val owned = ArrayList<Loot>()
    val worn = LinkedHashSet<Loot>()
    var herbs = 0
    var blooms = 0
    val CAPACITY = 24
    fun wearing(l: Loot) = l in worn
    fun wornCount(mode: Int) = worn.count { it.mode == mode }
    /** Adds a find; it's worn straight away if there's a free slot. Returns what to say. */
    fun add(l: Loot): String {
        if (l == Loot.HERB) { herbs = minOf(herbs + 1, 9); return "FOUND A HERB" }
        if (l == Loot.BLOOM) { blooms = minOf(blooms + 1, 5); return "A SPIRIT BLOOM! (REVIVES)" }
        if (l in owned) { herbs = minOf(herbs + 1, 9); return "A SPARE ${l.label}: TRADED FOR A HERB" }
        if (owned.size >= CAPACITY) return "YOUR BAG IS FULL"
        owned += l
        val tier = if (l.rarity.ordinal >= Rarity.LEGENDARY.ordinal) l.rarity.label + "! " else ""
        if (wornCount(l.mode) < 3) { worn += l; return "$tier${l.label}! WORN: ${l.about}" }
        return "$tier${l.label}! (START TO WEAR)"
    }
    /** Wear or take off; false if that kind's three slots are full. */
    fun toggle(l: Loot): Boolean {
        if (l in worn) { worn -= l; return true }
        if (wornCount(l.mode) >= 3) return false
        worn += l; return true
    }
    fun clear() { owned.clear(); worn.clear(); herbs = 0; blooms = 0 }
}

/** Rune shapes, as points on a unit square (closed ones end where they start). */
object RuneShapes {
    val TRI = listOf(0.5f to 0.06f, 0.94f to 0.9f, 0.06f to 0.9f, 0.5f to 0.06f)
    val ZIG = listOf(0.05f to 0.2f, 0.35f to 0.82f, 0.5f to 0.2f, 0.65f to 0.82f, 0.95f to 0.2f)
    val HOUR = listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f, 0.9f to 0.9f, 0.1f to 0.1f)
    /** The duel: a five-pointed star in one stroke (a little harder than the others). */
    val STAR = listOf(0.5f to 0.02f, 0.79f to 0.95f, 0.03f to 0.37f, 0.97f to 0.37f, 0.21f to 0.95f, 0.5f to 0.02f)
    val CHECK = listOf(0.08f to 0.5f, 0.38f to 0.92f, 0.95f to 0.08f)
    val ARCH = listOf(0.05f to 0.92f, 0.5f to 0.08f, 0.95f to 0.92f)
    val BOLT = listOf(0.72f to 0.02f, 0.25f to 0.52f, 0.7f to 0.5f, 0.28f to 0.98f)
    val COIL = listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.9f to 0.9f, 0.2f to 0.9f, 0.2f to 0.35f, 0.65f to 0.35f, 0.65f to 0.65f)
    /** Her blessing: a leaf (closed), point up, point down. */
    val LEAF = listOf(0.5f to 0.02f, 0.88f to 0.4f, 0.5f to 0.98f, 0.12f to 0.4f, 0.5f to 0.02f)
    fun closed(r: List<Pair<Float, Float>>) = r.first() == r.last()
}

/**
 * The duel: what the STAR rune does when it lands. A turn-based fight in the style of the old
 * monster-raising RPGs, fought right there in the hall, with the creature drawn in the title
 * screen's detail. Pick a move (from what's worn for the duel, and herbs); then he strikes and
 * his sigil appears: trace it to soften the blow, then the easy rune to block, or the hard one to
 * parry and strike back. His blows are heavy. Three exchanges, then back to the hall.
 */
class HallBattle(
    private val pose: Bitmap?, private val poses: List<Rect>,
    private val font: Paint, private val bag: Bag, private val host: Host,
    /** The second foe (the Blossom): her own attacks, plant-like effects, and "she". */
    private val queen: Boolean = false,
    /** Where her raised (blessing) hand is in the picture, as fractions of it. */
    private val handX: Float = 0.9f, private val handY: Float = 0.03f,
) {
    interface Host {
        var playerHp: Int
        val playerMax: Int
        val bossHp: Int
        val bossMax: Int
        /** Damage to the creature; true if that ends the duel (his last stand begins, or he falls). */
        fun hitBoss(n: Int): Boolean
        fun score(stroke: List<FloatArray>, rune: List<Pair<Float, Float>>): Float
        fun buzz()
        /** Your striking power (less with a big party along). */
        val power: Float get() = 1f
        /** The hall behind the duel. */
        fun backdrop(time: Float)
    }

    private enum class St { INTRO, MENU, TEXT, ATTACK, DEF1, DEF2, END }
    private var st = St.INTRO
    private var t = 0f
    var done = false; private set
    private var round = 0
    private val ROUNDS = 3
    private var text = "THE CREATURE PULLS YOU INTO A DUEL!"
    private var after: (() -> Unit)? = null
    private var cursor = 0
    private var guard = false
    private var frozen = false
    private var burn = 0; private var poison = 0
    private var thunderUsed = false
    private var heroHurtT = 0f; private var bossHurtT = 0f; private var shake = 0f
    private val rnd = kotlin.random.Random(System.nanoTime())
    private fun worn(l: Loot) = bag.wearing(l)

    private class Attack(val name: String, val sigil: List<Pair<Float, Float>>, val dmg: Int, val fx: Int, val pose: Int)
    // twice as heavy as before
    private val attacks = if (queen) listOf(
        Attack("THORN DAGGER", RuneShapes.TRI, 46, FX_CLAW, 1),
        Attack("PETAL STORM", RuneShapes.ZIG, 38, FX_ROAR, 3),
        Attack("VINE SURGE", RuneShapes.HOUR, 50, FX_QUAKE, 2),
    ) else listOf(
        Attack("CLAW RAKE", RuneShapes.TRI, 44, FX_CLAW, 1),
        Attack("FIVE-EYED ROAR", RuneShapes.ZIG, 36, FX_ROAR, 3),
        Attack("GROUND QUAKE", RuneShapes.HOUR, 52, FX_QUAKE, 2),
    )
    private var atk = attacks[0]
    private var easy = RuneShapes.CHECK; private var hard = RuneShapes.BOLT
    private var lastScore = -1f; private var scoreT = 0f
    private val NEED = 0.62f

    // ---- screen effects
    private companion object {
        const val FX_CLAW = 1; const val FX_ROAR = 2; const val FX_QUAKE = 3
        const val FX_HIT = 4; const val FX_FIRE = 5; const val FX_ICE = 6; const val FX_BOLT = 7; const val FX_HEAL = 8; const val FX_POISON = 9
    }
    private var fx = 0; private var fxT = 0f
    private fun effect(k: Int) { fx = k; fxT = 0f }

    // ---- particles, floating numbers and flashes: something is always glittering in a duel
    /** kind 0: a square spark; 1: a soft glow; 2: a streak along its path; 3: a twinkling star. */
    private class Pt(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val col: Int, val size: Float, val g: Float, val kind: Int) { val max = life }
    private val pts = ArrayList<Pt>()
    private class Pop(val text: String, val x: Float, var y: Float, val col: Int, val big: Boolean) { var life = 1.2f }
    private val pops = ArrayList<Pop>()
    private var flash = 0f; private var flashCol = 0xFFFFFF
    private var ambT = 0f
    /** Where things are, in the little screen's units: his chest and the traveller. */
    private val BX = 180f; private val BY = 72f; private val HX = 50f; private val HY = 96f
    private fun spray(x: Float, y: Float, n: Int, speed: Float, cols: IntArray, kind: Int = 0, g: Float = 80f, life: Float = 0.6f, size: Float = 2f, up: Float = 0f) {
        repeat(n) {
            val a = rnd.nextFloat() * 6.2832f; val v = speed * (0.35f + rnd.nextFloat() * 0.65f)
            pts += Pt(x, y, cos(a) * v, sin(a) * v - up, life * (0.6f + rnd.nextFloat() * 0.5f), cols[rnd.nextInt(cols.size)], size * (0.6f + rnd.nextFloat() * 0.7f), g, kind)
        }
        if (pts.size > 420) pts.subList(0, pts.size - 420).clear()
    }
    private fun pop(text: String, x: Float, y: Float, col: Int, big: Boolean = false) { pops += Pop(text, x, y, col, big) }
    private fun flashOf(col: Int, a: Float) { flashCol = col; flash = maxOf(flash, a) }
    private val SPARK = intArrayOf(0xFFFFFFFF.toInt(), 0xFFF8E070.toInt(), 0xFFF8B030.toInt())
    private val FIRE = intArrayOf(0xFFF8D050.toInt(), 0xFFF88030.toInt(), 0xFFE04020.toInt())
    private val FROST = intArrayOf(0xFFFFFFFF.toInt(), 0xFFA8E8FF.toInt(), 0xFF68C8F0.toInt())
    private val HEALC = intArrayOf(0xFF78F088.toInt(), 0xFFC8FFC8.toInt(), 0xFFFFFFFF.toInt())
    private val BLOOD = intArrayOf(0xFFE04848.toInt(), 0xFFF87878.toInt(), 0xFFFFFFFF.toInt())
    private val VENOM = intArrayOf(0xFF9AE050.toInt(), 0xFF60B030.toInt(), 0xFFD0F890.toInt())
    private val SHIELD = intArrayOf(0xFF8FD3F4.toInt(), 0xFFFFFFFF.toInt(), 0xFF4878E0.toInt())
    private val bossCols get() = if (queen) intArrayOf(0xFFF080B0.toInt(), 0xFFFFE0F0.toInt(), 0xFF68C058.toInt()) else intArrayOf(0xFFB060E0.toInt(), 0xFFF0D0FF.toInt(), 0xFF6040A0.toInt())
    private var bossPose = 0

    val wantsRune get() = st == St.DEF1 || st == St.DEF2

    private fun say(s: String, then: () -> Unit) { text = s; st = St.TEXT; t = 0f; after = then }
    /** Her duel speaks of "her". */
    private fun her(s: String) = if (!queen) s else s.replace("THE CREATURE", "THE BLOSSOM").replace(Regex("\\bHE\\b"), "SHE").replace(Regex("\\bHIS\\b"), "HER").replace(Regex("\\bHIM\\b"), "HER")

    private data class Opt(val label: String, val act: () -> Unit)
    private fun options(): List<Opt> {
        val out = ArrayList<Opt>()
        out += Opt("STRIKE") { strike(1f) }
        if (worn(Loot.ECHO_BELL)) out += Opt("ECHO STRIKE") { strike(0.7f, twice = true) }
        if (worn(Loot.STORMCALLER) && !thunderUsed) out += Opt("THUNDER") {
            thunderUsed = true; effect(FX_BOLT); flashOf(0xFFFFF0, 0.8f); spray(BX, BY - 20, 40, 150f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF8F070.toInt(), 0xFF8FD3F4.toInt()), kind = 2, g = 0f, life = 0.7f); val d = rnd.nextInt(34, 46); hit(d, "THUNDER FALLS ON HIM! $d")
        }
        if (bag.herbs > 0) out += Opt("HERB x${bag.herbs}") { bag.herbs--; heal(Loot.HERB.let { 35 }); say("YOU CHEW A HERB. +35") { attack() } }
        out += Opt("GUARD") { guard = true; spray(HX, HY, 16, 40f, SHIELD, kind = 3, g = 0f, life = 0.7f); say("YOU BRACE YOURSELF.") { attack() } }
        out += Opt("RUN") { run() }
        return out
    }

    private fun heal(n: Int) {
        host.playerHp = minOf(host.playerMax, host.playerHp + n); effect(FX_HEAL)
        spray(HX, HY + 10, 22, 30f, HEALC, kind = 3, g = -50f, life = 1f, size = 2.5f); pop("+$n", HX + 4, HY - 20, 0xFF78F088.toInt())
    }
    /** Flee: about half the time you slip away (more often when he's hurt or you're guarding); otherwise he cuts you off. */
    private var runs = 0
    private fun run() {
        val chance = 0.45f + (if (host.bossHp < host.bossMax / 2) 0.15f else 0f) - runs * 0.1f
        runs++
        spray(HX, HY + 26, 18, 50f, intArrayOf(0xFFD8D0C0.toInt(), 0xFFB0A890.toInt(), 0xFFFFFFFF.toInt()), kind = 1, g = -20f, life = 0.8f, size = 4f)
        if (rnd.nextFloat() < chance) {
            for (i in 0 until 14) pts += Pt(HX + rnd.nextFloat() * 30f, HY - 20f + rnd.nextFloat() * 50f, -260f, 0f, 0.4f, 0xFFFFFFFF.toInt(), 1.5f, 0f, 2)
            flashOf(0xFFFFFF, 0.3f); pop("ESCAPED!", HX + 6, HY - 30, 0xFFF8E070.toInt(), true)
            finish("YOU SLIP AWAY BACK INTO THE HALL!")
        } else {
            spray(HX + 20, HY - 10, 12, 60f, BLOOD, kind = 2, g = 0f, life = 0.5f); pop("BLOCKED!", HX + 6, HY - 30, 0xFFE04848.toInt(), true)
            shake = 0.3f; host.buzz()
            say(her("HE CUTS OFF YOUR ESCAPE!")) { attack() }
        }
    }

    private fun strike(k: Float, twice: Boolean = false) {
        var total = 0
        repeat(if (twice) 2 else 1) { total += ((rnd.nextInt(10, 15) + if (worn(Loot.FIVE_EYED_AXE)) 15 else 0) * k * host.power).toInt() }
        val tags = ArrayList<String>()
        val crit = rnd.nextFloat() < 0.15f
        if (crit) {
            total = (total * 1.6f).toInt(); tags += "CRITICAL"
            flashOf(0xFFFFFF, 0.6f); spray(BX, BY, 30, 160f, SPARK, kind = 3, g = 0f, life = 0.9f, size = 3f); pop("CRITICAL!", BX - 30, BY - 50, 0xFFF8E070.toInt(), true)
        }
        // a slash across him, sparks off the blade, a ring of light
        for (i in 0 until 10) { val u = i / 9f; pts += Pt(BX - 34 + u * 68, BY - 30 + u * 56, 0f, 0f, 0.35f + u * 0.1f, 0xFFFFFFFF.toInt(), 3f, 0f, 1) }
        spray(BX, BY, 26, 120f, SPARK, kind = 2, g = 120f, life = 0.6f)
        if (twice) spray(BX + 10, BY - 14, 16, 100f, SPARK, kind = 2, g = 120f, life = 0.5f)
        if (worn(Loot.EMBERBRAND)) spray(BX, BY, 24, 70f, FIRE, kind = 1, g = -60f, life = 0.9f, size = 3f)
        if (worn(Loot.FROSTFANG)) spray(BX, BY, 20, 90f, FROST, kind = 3, g = 0f, life = 0.8f)
        when {
            worn(Loot.EMBERBRAND) -> { burn = 3; tags += "HE'S BURNING"; effect(FX_FIRE) }
            worn(Loot.FROSTFANG) -> effect(FX_ICE)
            else -> effect(FX_HIT)
        }
        if (worn(Loot.FROSTFANG) && rnd.nextFloat() < 0.35f) { frozen = true; tags += "FROZEN SOLID" }
        hit(total, "YOU STRIKE! $total" + if (tags.isNotEmpty()) " - " + tags.joinToString(", ") else "")
    }

    private fun hit(d: Int, line: String) {
        bossHurtT = 0.6f; shake = 0.25f; host.buzz()
        pop("-$d", BX + 6, BY - 34, 0xFFFFFFFF.toInt(), d >= 30)
        spray(BX, BY + 6, 10, 60f, bossCols, kind = 0, g = 140f, life = 0.7f)
        val over = host.hitBoss(d)
        say(line) { if (over) finish("THE DUEL BREAKS OFF!") else attack() }
    }

    /** His turn: burns and poison tick, then (unless he's frozen) he winds up a blow. */
    private fun attack() {
        var tick = 0
        if (burn > 0) { burn--; tick += 6; spray(BX, BY + 10, 18, 50f, FIRE, kind = 1, g = -70f, life = 0.9f, size = 3f) }
        if (poison > 0) { poison--; tick += 8; spray(BX, BY + 10, 14, 40f, VENOM, kind = 1, g = -40f, life = 1f, size = 3f) }
        if (tick > 0) {
            pop("-$tick", BX + 10, BY - 30, 0xFFF8A030.toInt())
        }
        if (tick > 0 && host.hitBoss(tick)) return finish("THE DUEL BREAKS OFF!")
        if (frozen) {
            frozen = false; round++
            spray(BX, BY, 30, 80f, FROST, kind = 3, g = 0f, life = 1f, size = 3f); flashOf(0xA8E8FF, 0.35f)
            return say((if (tick > 0) "HE SMOULDERS ($tick). " else "") + "HE'S FROZEN AND CAN'T MOVE!") { nextTurn() }
        }
        atk = attacks[rnd.nextInt(attacks.size)]
        easy = if (rnd.nextBoolean()) RuneShapes.CHECK else RuneShapes.ARCH
        hard = if (rnd.nextBoolean()) RuneShapes.BOLT else RuneShapes.COIL
        text = (if (tick > 0) "HE TAKES $tick FROM HIS WOUNDS. " else "") + "HE READIES ${atk.name}!"
        bossPose = 3
        st = St.ATTACK; t = 0f
    }

    private fun nextTurn() {
        when {
            host.playerHp <= 0 -> { st = St.END; done = true }
            round >= ROUNDS -> finish("HE SHAKES YOU LOOSE. BACK TO THE HALL!")
            else -> {
                if (worn(Loot.SUN_LOCKET)) heal(6)
                st = St.MENU; cursor = 0; bossPose = 0
            }
        }
    }

    private fun finish(s: String) { say(s) { st = St.END; done = true } }

    /** How a blow lands: 0 not countered, 1 softened, 2 blocked, 3 parried. */
    private fun land(how: Int) {
        val base = (atk.dmg * (if (guard && how < 2) 0.75f else 1f)).toInt()
        val softK = if (worn(Loot.AEGIS_SHARD)) 0.3f else 0.5f
        val taken = when (how) { 0 -> base; 1 -> (base * softK).toInt(); else -> 0 }
        bossPose = atk.pose
        effect(atk.fx)
        when (atk.fx) {
            FX_CLAW -> spray(HX + 10, HY - 10, 20, 140f, bossCols, kind = 2, g = 60f, life = 0.5f)
            FX_ROAR -> spray(BX - 12, BY - 30, 36, 160f, bossCols, kind = if (queen) 1 else 3, g = 0f, life = 1f, size = 3f)
            else -> spray(HX + 20, HY + 28, 30, 140f, intArrayOf(0xFF8A7A6A.toInt(), 0xFF6A5A50.toInt(), 0xFFD8D0C0.toInt()), kind = 0, g = 260f, life = 0.9f, size = 3f, up = 120f)
        }
        if (taken > 0) {
            host.playerHp = maxOf(0, host.playerHp - taken); heroHurtT = 0.7f; shake = if (atk.fx == FX_QUAKE) 0.7f else 0.4f; host.buzz()
            spray(HX, HY, 26, 120f, BLOOD, kind = 2, g = 160f, life = 0.6f); spray(HX, HY, 14, 60f, bossCols, kind = 1, g = 0f, life = 0.7f, size = 3f)
            pop("-$taken", HX + 8, HY - 32, 0xFFE04848.toInt(), taken >= 40); flashOf(0xE02020, if (how == 0) 0.45f else 0.25f)
        }
        when (how) {
            2 -> { spray((HX + BX) / 2 - 30, HY - 10, 30, 110f, SHIELD, kind = 3, g = 0f, life = 0.8f, size = 3f); pop("BLOCK!", HX + 8, HY - 32, 0xFF8FD3F4.toInt(), true); flashOf(0x8FD3F4, 0.3f) }
            3 -> { spray((HX + BX) / 2, HY - 20, 40, 170f, SPARK, kind = 2, g = 0f, life = 0.7f); spray(BX, BY, 20, 90f, SPARK, kind = 3, g = 0f, life = 0.8f, size = 3f); pop("PARRY!", (HX + BX) / 2 - 16, HY - 44, 0xFFF8B030.toInt(), true); flashOf(0xFFFFFF, 0.55f) }
        }
        if (how == 2 && worn(Loot.AEGIS_SHARD)) host.playerHp = minOf(host.playerMax, host.playerHp + 8)
        val back = if (how == 3) (if (worn(Loot.MIRROR_CHARM)) atk.dmg else 14) else 0
        if (how == 3 && worn(Loot.VENOM_THORN)) poison = 3
        guard = false
        val line = when (how) {
            0 -> "${atk.name} HITS YOU! $taken"
            1 -> "SOFTENED! YOU TAKE $taken"
            2 -> "BLOCKED!" + if (worn(Loot.AEGIS_SHARD)) " THE SHARD MENDS YOU" else ""
            else -> "PARRIED! YOU STRIKE BACK FOR $back" + if (poison > 0) ", POISONED" else ""
        }
        round++
        if (back > 0) { bossHurtT = 0.6f; if (host.hitBoss(back)) return say(line) { finish("THE DUEL BREAKS OFF!") } }
        say(line) { nextTurn() }
    }

    // ---- input
    fun up() { if (st == St.MENU) cursor = (cursor + options().size - 1) % options().size }
    fun down() { if (st == St.MENU) cursor = (cursor + 1) % options().size }
    fun pressA() {
        when (st) {
            St.INTRO -> { st = St.MENU; cursor = 0 }
            St.MENU -> options().getOrNull(cursor)?.act?.invoke()
            St.TEXT -> if (t > 0.3f) after?.invoke()
            St.ATTACK -> if (t > 0.5f) { st = St.DEF1; t = 0f; lastScore = -1f }
            else -> {}
        }
    }
    fun onStroke(s: List<FloatArray>) {
        if (s.size < 4) return
        when (st) {
            St.DEF1 -> {
                val k = host.score(s, atk.sigil); lastScore = k; scoreT = 1.2f
                if (k >= NEED) { st = St.DEF2; t = 0f; lastScore = -1f; host.buzz(); spray(120f, 90f, 30, 110f, intArrayOf(0xFFE04848.toInt(), 0xFFFFFFFF.toInt(), 0xFFF8E070.toInt()), kind = 3, g = 0f, life = 0.7f, size = 3f); pop("SOFTENED", 92f, 64f, 0xFFF8E070.toInt()) }
            }
            St.DEF2 -> {
                val e = host.score(s, easy); val h = host.score(s, hard)
                lastScore = maxOf(e, h); scoreT = 1.2f
                if (h >= NEED && h >= e) land(3) else if (e >= NEED) land(2)
            }
            else -> {}
        }
    }

    fun update(dt: Float) {
        t += dt; heroHurtT -= dt; bossHurtT -= dt; shake -= dt; scoreT -= dt; fxT += dt; flash -= dt * 1.6f
        val pi = pts.iterator()
        while (pi.hasNext()) { val q = pi.next(); q.life -= dt; if (q.life <= 0f) { pi.remove(); continue }; q.vy += q.g * dt; q.x += q.vx * dt; q.y += q.vy * dt; q.vx *= 1f - 1.5f * dt }
        pops.removeAll { it.life -= dt; it.y -= 22f * dt; it.life <= 0f }
        // the air of the duel: drifting motes (petals for her), never still
        ambT -= dt
        if (ambT <= 0f) {
            ambT = 0.09f
            val c = bossCols
            pts += Pt(rnd.nextFloat() * 240f, if (queen) -4f else 130f, (rnd.nextFloat() - 0.5f) * 20f, if (queen) 26f + rnd.nextFloat() * 20f else -16f - rnd.nextFloat() * 16f, 3.5f, c[rnd.nextInt(c.size)] and 0x00FFFFFF or (0x90 shl 24), if (queen) 2.5f else 1.5f, 0f, if (queen) 1 else 3)
            // his aura, licking up round him
            pts += Pt(BX - 30f + rnd.nextFloat() * 60f, BY + 40f, 0f, -30f - rnd.nextFloat() * 30f, 0.9f, c[rnd.nextInt(c.size)] and 0x00FFFFFF or (0x70 shl 24), 3f, 0f, 1)
        }
        // winding up: power drawn into him from all round
        if (st == St.ATTACK && rnd.nextFloat() < 0.8f) {
            val a = rnd.nextFloat() * 6.2832f; val c = bossCols
            pts += Pt(BX + cos(a) * 60f, BY + sin(a) * 50f, -cos(a) * 120f, -sin(a) * 100f, 0.45f, c[rnd.nextInt(c.size)], 2f, 0f, 2)
        }
        if (st == St.INTRO && t < 0.1f && pts.size < 50) { flashOf(0xFFFFFF, 0.7f); for (i in 0 until 40) { val a = i * 0.157f; pts += Pt(120f, 88f, cos(a) * 300f, sin(a) * 220f, 0.5f, 0xFFFFFFFF.toInt(), 1.5f, 0f, 2) } }
        when (st) {
            St.INTRO -> if (t > 2.2f) { st = St.MENU; cursor = 0 }
            St.ATTACK -> if (t > 1.5f) { st = St.DEF1; t = 0f; lastScore = -1f }
            St.DEF1 -> if (t > limit1) land(0)
            St.DEF2 -> if (t > limit2) land(1)
            else -> {}
        }
    }
    private val lens get() = if (worn(Loot.RUNE_LENS)) 1.4f else 1f
    private val limit1 get() = (if (guard) 7f else 5f) * lens
    private val limit2 get() = 4.5f * lens

    // =====================================================================================
    // Drawing: the hall behind, the windows and runes in the little screen; the creature and the
    // big effects in full detail over it (drawHiRes)
    // =====================================================================================
    private val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val line = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.SQUARE }
    private val src = Rect(); private val dst = RectF()
    private fun box(c: Canvas, l: Float, tt: Float, r: Float, b: Float, col: Int) { p.color = col; c.drawRect(l, tt, r, b, p) }

    /** The traveller from behind, larger and shaded (24×29). */
    private val heroBack = Sprite(listOf(
        "........KKKKKKKK........",
        "......KKccCCCCccKK......",
        ".....KcCCCCCCCCCCcK.....",
        "....KcCCCWWCCCCCCCcK....",
        "....KCCCCCCCCCCCCCCK....",
        "...KKKKKKKKKKKKKKKKKK...",
        "....KHHHHHHHHHHHHHHK....",
        "....KHhHHHHHHHHHHhHK....",
        "....KHHhHHHHHHHHhHHK....",
        ".....KHHHHHHHHHHHHK.....",
        ".....KSKHHHHHHHHKSK.....",
        "......KKSSSSSSSSKK......",
        "....KKTTTTTTTTTTTTKK....",
        "...KTTTTTTTTTTTTTTTTK...",
        "..KTTtTTTTTTTTTTTTtTTK..",
        "..KTTtTTTTTKKTTTTTtTTK..",
        ".KSKTtTTTTTKKTTTTTtTKSK.",
        ".KSKTtTTTTTTTTTTTTtTKSK.",
        ".KSKTtTTTTTTTTTTTTtTKSK.",
        ".KSKKKKKKKYKKKKKKKKKKSK.",
        "..KK.KPPPPPPPPPPPPK.KK..",
        ".....KPPPPPpPPPPPPK.....",
        ".....KPPPPKKKPPPPPK.....",
        ".....KPPPPK.KPPPPPK.....",
        ".....KPpPPK.KPPpPPK.....",
        ".....KPPPPK.KPPPPPK.....",
        "....KBBBBBK.KBBBBBK.....",
        "....KBbBBBK.KBBBbBBK....",
        "....KKKKKKK.KKKKKKKK....",
    ), mapOf('K' to 0xFF141420.toInt(), 'C' to 0xFF3FA84E.toInt(), 'c' to 0xFF2A7A3A.toInt(), 'W' to 0xFFB8F0B0.toInt(),
        'H' to 0xFF9A5A28.toInt(), 'h' to 0xFF6E3C18.toInt(), 'S' to 0xFFF0B888.toInt(), 'T' to 0xFF3868D0.toInt(), 't' to 0xFF2A4A9A.toInt(),
        'Y' to 0xFFE8C048.toInt(), 'P' to 0xFF4A4A5C.toInt(), 'p' to 0xFF34343F.toInt(), 'B' to 0xFF7A4A2A.toInt(), 'b' to 0xFFA06A3A.toInt()), outline = null)

    private fun wrap(s: String, cols: Int): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (wd in s.split(' ')) { if (cur.isEmpty()) cur = wd else if (cur.length + 1 + wd.length <= cols) cur += " $wd" else { out += cur; cur = wd } }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun window(c: Canvas, l: Float, tt: Float, r: Float, b: Float) {
        box(c, l, tt, r, b, 0xFFF8F8F8.toInt()); box(c, l + 1, tt + 1, r - 1, b - 1, 0xFF101018.toInt())
        box(c, l + 2, tt + 2, r - 2, b - 2, 0xFFB8B8C8.toInt()); box(c, l + 3, tt + 3, r - 3, b - 3, 0xFF101018.toInt())
    }

    private fun bar(c: Canvas, l: Float, tt: Float, w: Float, v: Float, col: Int) {
        box(c, l, tt, l + w, tt + 5, 0xFF2A2A34.toInt())
        box(c, l + 1, tt + 1, l + 1 + (w - 2) * v.coerceIn(0f, 1f), tt + 4, col)
    }

    /** The little screen: the hall (dimmed) and the traveller. The creature, the effects and the windows go on top (drawHiRes). */
    fun draw(c: Canvas, time: Float) {
        host.backdrop(time)
        box(c, 0f, 0f, 240f, 176f, 0x30101020)               // the hall dims around the duel
        box(c, 0f, 120f, 240f, 176f, 0x33000000)
        if (!(heroHurtT > 0f && (time * 20).toInt() % 2 == 0)) {
            box(c, 26f, 122f, 74f, 126f, 0x55000000)
            src.set(0, 0, heroBack.w, heroBack.h); dst.set(26f, 66f, 26f + heroBack.w * 2, 66f + heroBack.h * 2)
            c.drawBitmap(heroBack.bmp, src, dst, p)
        }
    }

    /** The windows, menu and runes, in the little screen's 240×176 units (drawn scaled, over the creature). */
    private fun drawUi(c: Canvas, time: Float, strokeNorm: List<FloatArray>) {
        // one compact status window, clear of his axe
        window(c, 4f, 4f, 100f, 38f)
        font.color = 0xFFF8F8F8.toInt(); c.drawText("FIVE-EYED", 9f, 13f, font)
        bar(c, 9f, 15f, 86f, host.bossHp / host.bossMax.toFloat(), 0xFFC040C0.toInt())
        c.drawText("YOU ${host.playerHp}", 9f, 29f, font)
        bar(c, 9f, 31f, 86f, host.playerHp / host.playerMax.toFloat(), if (host.playerHp < host.playerMax / 4) 0xFFE04848.toInt() else 0xFF48C058.toInt())
        window(c, 4f, 128f, 236f, 172f)
        when (st) {
            St.MENU -> {
                val opts = options()
                opts.forEachIndexed { i, o ->
                    val x = 12f + (i % 2) * 112f; val y = 142f + (i / 2) * 12f
                    if (i == cursor && (time * 3).toInt() % 2 == 0) c.drawText(">", x - 7f, y, font)
                    font.color = if (i == cursor) 0xFFF8E070.toInt() else 0xFFF8F8F8.toInt()
                    c.drawText(o.label.take(13), x + 2f, y, font); font.color = 0xFFF8F8F8.toInt()
                }
            }
            St.DEF1, St.DEF2 -> {
                val s = if (st == St.DEF1) her("TRACE HIS SIGIL TO SOFTEN!") else "EASY: BLOCK   HARD: PARRY!"
                c.drawText(s, 10f, 146f, font)
                val lim = if (st == St.DEF1) limit1 else limit2
                bar(c, 10f, 154f, 220f, 1f - t / lim, 0xFFF8E070.toInt())
            }
            else -> wrap(her(text), 27).take(3).forEachIndexed { i, s -> c.drawText(s, 10f, 142f + i * 11f, font) }
        }
        if (st == St.DEF1) runeBox(c, 64f, 54f, 112f, atk.sigil, atk.name, 0xFFE04848.toInt(), strokeNorm, time)
        if (st == St.DEF2) {
            runeBox(c, 10f, 54f, 106f, easy, "BLOCK", 0xFF48C058.toInt(), strokeNorm, time)
            runeBox(c, 124f, 54f, 106f, hard, "PARRY", 0xFFF8B030.toInt(), strokeNorm, time)
        }
        if (scoreT > 0f && lastScore >= 0f && wantsRune) {
            val s = "${(lastScore * 100).toInt()}%"
            box(c, 104f, 112f, 136f, 124f, if (lastScore >= NEED) 0xFF48A048.toInt() else 0xFFE04848.toInt())
            c.drawText(s, 108f, 122f, font)
        }
        font.color = 0xFFF8F8F8.toInt()
    }

    private fun runeBox(c: Canvas, l: Float, tt: Float, w: Float, rune: List<Pair<Float, Float>>, label: String, col: Int, stroke: List<FloatArray>, time: Float) {
        val hgt = 70f
        window(c, l, tt, l + w, tt + hgt)
        font.color = col; c.drawText(label.take(13), l + 8f, tt + 12f, font); font.color = 0xFFF8F8F8.toInt()
        val gs = hgt - 24f; val gl = l + (w - gs) / 2; val gt = tt + 18f
        line.strokeWidth = 2f; line.color = if ((time * 4).toInt() % 2 == 0) col else 0xFFF8F8F8.toInt()
        for (k in 1 until rune.size) c.drawLine(gl + rune[k - 1].first * gs, gt + rune[k - 1].second * gs, gl + rune[k].first * gs, gt + rune[k].second * gs, line)
        if (stroke.size > 1) {
            line.strokeWidth = 1f; line.color = 0xFF8FD3F4.toInt()
            for (k in 1 until stroke.size) c.drawLine(gl + stroke[k - 1][0] * gs, gt + stroke[k - 1][1] * gs, gl + stroke[k][0] * gs, gt + stroke[k][1] * gs, line)
        }
    }

    /** Crisp pixels, toned down a little to sit in the dimmed hall. */
    private val hp = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private var feetCache: Pair<Int, Int>? = null
    /** Where his feet are in the picture (the left and right of the bottom rows). */
    private fun feet(b: Bitmap, r: Rect): Pair<Int, Int> = feetCache ?: run {
        var l = r.width(); var rr = 0
        for (y in r.bottom - 6 until r.bottom) for (x in r.left until r.right) if ((b.getPixel(x, y) ushr 24) > 0) { l = minOf(l, x - r.left); rr = maxOf(rr, x - r.left) }
        (if (rr > l) l to rr else 0 to r.width()).also { feetCache = it }
    }
    private val fxp = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val path = Path()

    /**
     * The creature in the title screen's detail, and the big effects, over the little screen.
     * [r] is where the little screen is on the display; positions are in its 240×176 units.
     */
    fun drawHiRes(c: Canvas, r: RectF, time: Float, strokeNorm: List<FloatArray>) {
        val k = r.width() / 240f
        fun X(x: Float) = r.left + x * k
        fun Y(y: Float) = r.top + y * k
        c.save(); c.clipRect(r)
        if (shake > 0f) c.translate((rnd.nextFloat() - 0.5f) * (if (fx == FX_QUAKE) 10f else 5f) * k, (rnd.nextFloat() - 0.5f) * 3f * k)
        // the creature, front on to you: crouched, arms out to grab, axe raised (no idle wobble)
        val bmp = pose
        if (bmp != null && poses.isNotEmpty() && !(bossHurtT > 0f && (time * 20).toInt() % 2 == 0)) {
            val s = poses[0]
            val big = fx in FX_CLAW..FX_QUAKE && fxT < 0.5f
            val lunge = if (big) sin(Math.PI.toFloat() * (fxT / 0.5f)) else 0f
            val wind = if (st == St.ATTACK) 0.03f * sin(time * 18f) else 0f
            val hTarget = (if (queen) 128f else 108f) * (1f + 0.12f * lunge + wind)
            val sc = hTarget / s.height()
            val (fl, fr) = feet(bmp, s)
            // stood by his feet (not the picture's middle, which the axe pulls left), on the hall floor
            val footX = 182f - 40f * lunge; val baseY = 126f + 4f * lunge
            val left = footX - (fl + fr) / 2f * sc
            fxp.style = Paint.Style.FILL
            fxp.color = 0x6E707088; c.drawOval(X(footX - 30f), Y(baseY - 3f), X(footX + 30f), Y(baseY + 3f), fxp)
            fxp.color = 0x9646465C.toInt()
            for (fx_ in floatArrayOf(left + (fl + 10) * sc, left + (fr - 10) * sc)) c.drawOval(X(fx_ - 10f), Y(baseY - 2f), X(fx_ + 10f), Y(baseY + 1f), fxp)
            dst.set(X(left), Y(baseY - hTarget), X(left + s.width() * sc), Y(baseY))
            c.drawBitmap(bmp, s, dst, hp)
            if (queen) {
                // the blessing hand glows; brighter as she readies a blow
                val gx = dst.left + dst.width() * handX; val gy = dst.top + dst.height() * handY
                val pulse = 0.5f + 0.5f * sin(time * 3f) + (if (st == St.ATTACK) 0.6f else 0f)
                fxp.style = Paint.Style.FILL
                for ((rr, a) in listOf(26f to 0.18f, 16f to 0.3f, 8f to 0.55f)) { fxp.color = ((a * pulse.coerceAtMost(1.2f) * 200).toInt().coerceIn(0, 255) shl 24) or 0xFFF0A0; c.drawCircle(gx, gy, rr * k, fxp) }
                for (i in 0 until 6) { val a = time * 1.5f + i * 1.047f; fxp.color = 0xCCFFFFFF.toInt(); c.drawCircle(gx + cos(a) * 20f * k, gy + sin(a) * 12f * k, 1.2f * k, fxp) }
            }
            if (bossHurtT > 0f) { fxp.color = 0x55FFFFFF; c.drawRect(dst, fxp) }
        }
        drawFx(c, ::X, ::Y, k, time)
        drawParticles(c, ::X, ::Y, k, time)
        // the windows and runes, over everything
        c.save(); c.translate(r.left, r.top); c.scale(k, k)
        drawUi(c, time, strokeNorm)
        c.restore()
        c.restore()
    }

    private fun drawFx(c: Canvas, X: (Float) -> Float, Y: (Float) -> Float, k: Float, time: Float) {
        val e = fxT
        when (fx) {
            FX_CLAW -> if (e < 0.7f) {
                // three raking slashes across the traveller, and a white flash
                val a = (1f - e / 0.7f)
                fxp.color = ((a * 180).toInt() shl 24) or 0xFFFFFF; c.drawRect(X(0f), Y(0f), X(240f), Y(176f), fxp.apply { style = Paint.Style.FILL })
                fxp.style = Paint.Style.STROKE
                for (i in 0 until 3) {
                    val prog = ((e - i * 0.06f) / 0.25f).coerceIn(0f, 1f)
                    val x0 = 20f + i * 16f; val y0 = 60f; val x1 = x0 + 60f; val y1 = y0 + 70f
                    fxp.strokeWidth = 7f * k; fxp.color = (((a * 255).toInt()) shl 24) or (if (queen) 0x58B848 else 0xE04848)
                    c.drawLine(X(x0), Y(y0), X(x0 + (x1 - x0) * prog), Y(y0 + (y1 - y0) * prog), fxp)
                    fxp.strokeWidth = 2.5f * k; fxp.color = (((a * 255).toInt()) shl 24) or 0xFFFFFF
                    c.drawLine(X(x0), Y(y0), X(x0 + (x1 - x0) * prog), Y(y0 + (y1 - y0) * prog), fxp)
                }
                fxp.style = Paint.Style.FILL
            }
            FX_ROAR -> if (e < 1.1f) {
                if (queen) for (i in 0 until 26) {
                    val px = (i * 47 % 240).toFloat() + sin(e * 6f + i) * 10f; val py = -10f + (e * 200f + i * 23) % 190f
                    fxp.style = Paint.Style.FILL; fxp.color = (((1f - e / 1.1f) * 255).toInt() shl 24) or (if (i % 3 == 0) 0xFFFFFF else 0xF080B0)
                    c.drawOval(X(px), Y(py), X(px + 4f), Y(py + 2.5f), fxp)
                }
                // rings of sound from his mouth, and a purple pulse over everything
                fxp.color = (((0.35f * (1f - e / 1.1f)) * 255).toInt() shl 24) or (if (queen) 0xF080B0 else 0x7A2AA0)
                c.drawRect(X(0f), Y(0f), X(240f), Y(176f), fxp)
                fxp.style = Paint.Style.STROKE
                for (i in 0 until 4) {
                    val rr = (e * 220f - i * 30f); if (rr <= 0f) continue
                    fxp.strokeWidth = (4f - i * 0.6f) * k; fxp.color = (((1f - e / 1.1f) * 220).toInt() shl 24) or (if (queen) 0xFFE8F4 else 0xF0D0FF)
                    c.drawCircle(X(168f), Y(40f), rr * k, fxp)
                }
                fxp.style = Paint.Style.FILL
            }
            FX_QUAKE -> if (e < 1.2f) {
                // the floor splits and rocks fall
                val a = 1f - e / 1.2f
                fxp.style = Paint.Style.STROKE; fxp.strokeWidth = (if (queen) 4f else 2.5f) * k; fxp.color = ((a * 255).toInt() shl 24) or (if (queen) 0x3A8A3A else 0x2A2030)
                path.reset(); path.moveTo(X(170f), Y(122f))
                var x = 170f; var y = 122f
                for (i in 0 until 7) { x -= 18f + (i * 37 % 11); y += if (i % 2 == 0) 6f else -3f; path.lineTo(X(x), Y(y)) }
                c.drawPath(path, fxp)
                fxp.style = Paint.Style.FILL
                for (i in 0 until 14) {
                    val rx = (i * 53 % 230).toFloat() + 5f; val ry = -10f + (e * 260f + i * 17) % 190f
                    fxp.color = ((a * 255).toInt() shl 24) or (if (queen) (if (i % 2 == 0) 0xF080B0 else 0xFFF0F6) else (if (i % 2 == 0) 0x6A5A50 else 0x8A7A6A))
                    c.drawRect(X(rx), Y(ry), X(rx + 4f + i % 3), Y(ry + 4f + i % 2), fxp)
                }
            }
            FX_HIT -> if (e < 0.4f) burst(c, X(180f), Y(70f), k, e / 0.4f, 0xFFFFFF, 0xF8E070)
            FX_FIRE -> if (e < 0.8f) {
                burst(c, X(180f), Y(70f), k, e / 0.8f, 0xF8A030, 0xE04020)
                for (i in 0 until 12) {
                    val a = i * 0.52f + e * 3f; val rr = 10f + e * 50f
                    fxp.color = (((1f - e / 0.8f) * 255).toInt() shl 24) or (if (i % 2 == 0) 0xF8D050 else 0xE85020)
                    c.drawCircle(X(180f + cos(a) * rr * 0.6f), Y(70f + sin(a) * rr * 0.8f - e * 30f), (3f - e * 2f).coerceAtLeast(1f) * k, fxp)
                }
            }
            FX_ICE -> if (e < 0.8f) {
                burst(c, X(180f), Y(70f), k, e / 0.8f, 0xD8F8FF, 0x68C8F0)
                fxp.style = Paint.Style.STROKE; fxp.strokeWidth = 2f * k
                for (i in 0 until 8) {
                    val a = i * 0.785f; val r0 = 6f; val r1 = 6f + 34f * (e / 0.8f)
                    fxp.color = (((1f - e / 0.8f) * 255).toInt() shl 24) or 0xA8E8FF
                    c.drawLine(X(180f + cos(a) * r0), Y(70f + sin(a) * r0), X(180f + cos(a) * r1), Y(70f + sin(a) * r1), fxp)
                }
                fxp.style = Paint.Style.FILL
            }
            FX_BOLT -> if (e < 0.7f) {
                fxp.color = (((1f - e / 0.7f) * 160).toInt() shl 24) or 0xFFFFFF; c.drawRect(X(0f), Y(0f), X(240f), Y(176f), fxp)
                fxp.style = Paint.Style.STROKE; fxp.strokeWidth = 4f * k; fxp.color = (((1f - e / 0.7f) * 255).toInt() shl 24) or 0xF8F070
                path.reset(); path.moveTo(X(186f), Y(0f))
                var y = 0f; var x = 186f; var i = 0
                while (y < 70f) { y += 12f; x += if (i++ % 2 == 0) -10f else 12f; path.lineTo(X(x), Y(y)) }
                c.drawPath(path, fxp); fxp.style = Paint.Style.FILL
            }
            FX_HEAL -> if (e < 0.9f) {
                for (i in 0 until 10) {
                    val px = 30f + (i * 37 % 40); val py = 120f - e * 70f - (i * 13 % 30)
                    fxp.color = (((1f - e / 0.9f) * 255).toInt() shl 24) or 0x78F088
                    c.drawRect(X(px), Y(py), X(px + 2f), Y(py + 6f), fxp); c.drawRect(X(px - 2f), Y(py + 2f), X(px + 4f), Y(py + 4f), fxp)
                }
            }
            else -> {}
        }
    }

    private fun drawParticles(c: Canvas, X: (Float) -> Float, Y: (Float) -> Float, k: Float, time: Float) {
        fxp.style = Paint.Style.FILL
        for (q in pts) {
            val f = (q.life / q.max).coerceIn(0f, 1f)
            val a = ((q.col ushr 24) * f).toInt().coerceIn(0, 255)
            fxp.color = (a shl 24) or (q.col and 0xFFFFFF)
            val s = q.size * k
            when (q.kind) {
                0 -> c.drawRect(X(q.x) - s / 2, Y(q.y) - s / 2, X(q.x) + s / 2, Y(q.y) + s / 2, fxp)
                1 -> { c.drawCircle(X(q.x), Y(q.y), s * (0.6f + f * 0.6f), fxp) }
                2 -> { fxp.style = Paint.Style.STROKE; fxp.strokeWidth = maxOf(1f, s * 0.6f); c.drawLine(X(q.x), Y(q.y), X(q.x - q.vx * 0.04f), Y(q.y - q.vy * 0.04f), fxp); fxp.style = Paint.Style.FILL }
                else -> {
                    // a twinkle: a little cross that winks
                    val r = s * (if (((time * 14).toInt() + q.x.toInt()) % 2 == 0) 1.6f else 1f)
                    c.drawRect(X(q.x) - r, Y(q.y) - s * 0.3f, X(q.x) + r, Y(q.y) + s * 0.3f, fxp)
                    c.drawRect(X(q.x) - s * 0.3f, Y(q.y) - r, X(q.x) + s * 0.3f, Y(q.y) + r, fxp)
                }
            }
        }
        if (flash > 0f) { fxp.color = ((flash.coerceAtMost(1f) * 150).toInt() shl 24) or flashCol; c.drawRect(X(0f), Y(0f), X(240f), Y(176f), fxp) }
        // the numbers, bouncing up and fading
        val oldSize = font.textSize; val oldCol = font.color
        for (pp in pops) {
            val bounce = if (pp.life > 0.95f) (1.2f - pp.life) * 4f else 1f
            val ts = (if (pp.big) 14f else 10f) * k * (0.7f + 0.3f * bounce.coerceAtMost(1f))
            font.textSize = ts
            val a = (pp.life / 0.4f).coerceIn(0f, 1f)
            font.color = ((a * 255).toInt() shl 24) or 0x101018
            c.drawText(pp.text, X(pp.x) + k, Y(pp.y) + k, font)
            font.color = ((a * 255).toInt() shl 24) or (pp.col and 0xFFFFFF)
            c.drawText(pp.text, X(pp.x), Y(pp.y), font)
        }
        font.textSize = oldSize; font.color = oldCol
    }

    private fun burst(c: Canvas, x: Float, y: Float, k: Float, e: Float, core: Int, edge: Int) {
        val a = ((1f - e) * 255).toInt().coerceIn(0, 255)
        fxp.style = Paint.Style.FILL
        fxp.color = (a shl 24) or edge; c.drawCircle(x, y, (8f + e * 30f) * k, fxp)
        fxp.color = (a shl 24) or core; c.drawCircle(x, y, (5f + e * 14f) * k, fxp)
    }
}
