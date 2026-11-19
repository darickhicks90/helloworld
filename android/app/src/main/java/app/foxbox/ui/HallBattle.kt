package app.foxbox.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** What the traveller can find (imps drop it now and then) and equip: a weapon and a charm, plus herbs. */
enum class Loot(val label: String, val kind: Int, val lo: Int, val hi: Int, val move: String) {
    BONE_CLUB("BONE CLUB", 0, 10, 15, "SMASH"),
    RUSTY_SWORD("RUSTY SWORD", 0, 12, 18, "SLASH"),
    RUNE_BLADE("RUNE BLADE", 0, 14, 20, "RUNE CUT"),     // parries cut deeper
    FROST_WAND("FROST WAND", 0, 9, 13, "FROST"),         // may chill his next blow
    EMBER("EMBER CHARM", 1, 18, 26, "FIREBOLT"),
    MOSS("MOSS CHARM", 1, 30, 30, "MEND"),
    THORN("THORN CHARM", 1, 0, 0, "THORNS"),             // blows that land are paid back in part
    ECHO("ECHO CHARM", 1, 0, 0, "TWIN STRIKE"),          // the weapon twice, a little lighter
    HERB("HERB", 2, 35, 35, "HEAL");
    val weapon get() = kind == 0
    val charm get() = kind == 1
}

class Bag {
    val owned = LinkedHashSet<Loot>()
    var herbs = 0
    var weapon: Loot? = null
    var charm: Loot? = null
    /** Adds a find; a first weapon or charm is equipped straight away. Returns what to say. */
    fun add(l: Loot): String {
        if (l == Loot.HERB) { herbs++; return "FOUND A HERB" }
        if (l in owned) { herbs++; return "A SPARE: TRADED FOR A HERB" }
        owned += l
        if (l.weapon && weapon == null) { weapon = l; return "FOUND ${l.label}! EQUIPPED" }
        if (l.charm && charm == null) { charm = l; return "FOUND ${l.label}! EQUIPPED" }
        return "FOUND ${l.label}! (A: BAG)"
    }
    fun clear() { owned.clear(); herbs = 0; weapon = null; charm = null }
}

/** Rune shapes, as points on a unit square (closed ones end where they start). */
object RuneShapes {
    val TRI = listOf(0.5f to 0.06f, 0.94f to 0.9f, 0.06f to 0.9f, 0.5f to 0.06f)
    val ZIG = listOf(0.05f to 0.2f, 0.35f to 0.82f, 0.5f to 0.2f, 0.65f to 0.82f, 0.95f to 0.2f)
    val HOUR = listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f, 0.9f to 0.9f, 0.1f to 0.1f)
    /** The duel: a five-pointed star in one stroke (a little harder than the others). */
    val STAR = listOf(0.5f to 0.02f, 0.79f to 0.95f, 0.03f to 0.37f, 0.97f to 0.37f, 0.21f to 0.95f, 0.5f to 0.02f)
    // easy follow-ups (block) and hard ones (parry)
    val CHECK = listOf(0.08f to 0.5f, 0.38f to 0.92f, 0.95f to 0.08f)
    val ARCH = listOf(0.05f to 0.92f, 0.5f to 0.08f, 0.95f to 0.92f)
    val BOLT = listOf(0.72f to 0.02f, 0.25f to 0.52f, 0.7f to 0.5f, 0.28f to 0.98f)
    val COIL = listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.9f to 0.9f, 0.2f to 0.9f, 0.2f to 0.35f, 0.65f to 0.35f, 0.65f to 0.65f)
    fun closed(r: List<Pair<Float, Float>>) = r.first() == r.last()
}

/**
 * The duel: what the STAR rune does when it lands. A turn-based fight in the style of the old
 * monster-raising RPGs, drawn in richer 16-bit colours. The traveller picks a move (what's on the
 * menu depends on the equipped weapon and charm, and herbs carried); then the creature strikes and
 * its sigil appears: trace it to soften the blow, then trace the easy rune beside it to block, or
 * the hard one to parry and strike back. Three exchanges, then back to the hall.
 */
class HallBattle(private val yeti: Bitmap?, private val font: Paint, private val bag: Bag, private val host: Host) {
    interface Host {
        var playerHp: Int
        val playerMax: Int
        val bossHp: Int
        val bossMax: Int
        /** Damage to the creature; true if that ends the duel (his last stand begins, or he falls). */
        fun hitBoss(n: Int): Boolean
        fun score(stroke: List<FloatArray>, rune: List<Pair<Float, Float>>): Float
        fun buzz()
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
    private var chill = false
    private var heroHurtT = 0f; private var bossHurtT = 0f; private var shake = 0f
    private val rnd = kotlin.random.Random(System.nanoTime())

    private class Attack(val name: String, val sigil: List<Pair<Float, Float>>, val dmg: Int)
    private val attacks = listOf(Attack("CLAW RAKE", RuneShapes.TRI, 22), Attack("FIVE-EYED ROAR", RuneShapes.ZIG, 18), Attack("GROUND QUAKE", RuneShapes.HOUR, 26))
    private var atk = attacks[0]
    private var easy = RuneShapes.CHECK; private var hard = RuneShapes.BOLT
    private var lastScore = -1f; private var scoreT = 0f
    private val NEED = 0.62f

    /** The XY pad traces runes only while one is asked for. */
    val wantsRune get() = st == St.DEF1 || st == St.DEF2

    private fun say(s: String, then: () -> Unit) { text = s; st = St.TEXT; t = 0f; after = then }

    private data class Opt(val label: String, val act: () -> Unit)
    private fun options(): List<Opt> {
        val w = bag.weapon
        val out = ArrayList<Opt>()
        out += Opt(if (w != null) w.move else "PUNCH") { strike(w, 1f) }
        bag.charm?.let { ch ->
            when (ch) {
                Loot.EMBER -> out += Opt("FIREBOLT") { val d = rnd.nextInt(ch.lo, ch.hi + 1); hit(d, "A FIREBOLT SCORCHES HIM! $d") }
                Loot.MOSS -> out += Opt("MEND") { host.playerHp = minOf(host.playerMax, host.playerHp + ch.lo); say("MOSS KNITS YOU BACK UP. +${ch.lo}") { attack() } }
                Loot.ECHO -> out += Opt("TWIN STRIKE") { strike(w, 0.7f, twice = true) }
                Loot.THORN -> out += Opt("BRISTLE") { guard = true; say("THORNS BRISTLE ALL OVER YOU.") { attack() } }
                else -> {}
            }
        }
        if (bag.herbs > 0) out += Opt("HERB x${bag.herbs}") { bag.herbs--; host.playerHp = minOf(host.playerMax, host.playerHp + Loot.HERB.lo); say("YOU CHEW A HERB. +${Loot.HERB.lo}") { attack() } }
        out += Opt("GUARD") { guard = true; say("YOU BRACE YOURSELF.") { attack() } }
        return out
    }

    private fun strike(w: Loot?, k: Float, twice: Boolean = false) {
        val lo = w?.lo ?: 6; val hi = w?.hi ?: 9
        var total = 0
        repeat(if (twice) 2 else 1) { total += (rnd.nextInt(lo, hi + 1) * k).toInt() }
        if (w == Loot.FROST_WAND && rnd.nextFloat() < 0.4f) chill = true
        val verb = w?.move ?: "PUNCH"
        hit(total, "$verb! $total DAMAGE" + if (chill) " - HE'S CHILLED" else "")
    }

    private fun hit(d: Int, line: String) {
        bossHurtT = 0.6f; shake = 0.25f; host.buzz()
        val over = host.hitBoss(d)
        say(line) { if (over) finish("THE DUEL BREAKS OFF!") else attack() }
    }

    private fun attack() {
        atk = attacks[rnd.nextInt(attacks.size)]
        easy = if (rnd.nextBoolean()) RuneShapes.CHECK else RuneShapes.ARCH
        hard = if (rnd.nextBoolean()) RuneShapes.BOLT else RuneShapes.COIL
        text = "HE READIES ${atk.name}!"
        st = St.ATTACK; t = 0f
    }

    private fun finish(s: String) { say(s) { st = St.END; done = true } }

    /** How a blow lands: 0 not countered, 1 softened, 2 blocked, 3 parried. */
    private fun land(how: Int) {
        val base = (atk.dmg * (if (chill) 0.6f else 1f) * (if (guard && how < 2) 0.75f else 1f)).toInt()
        chill = false
        val taken = when (how) { 0 -> base; 1 -> base / 2; else -> 0 }
        val thorns = bag.charm == Loot.THORN
        if (taken > 0) { host.playerHp = maxOf(0, host.playerHp - taken); heroHurtT = 0.6f; shake = 0.3f; host.buzz() }
        val back = when {
            how == 3 -> 12 + (if (bag.weapon == Loot.RUNE_BLADE) 10 else 0) + (if (thorns) 6 else 0)
            thorns && taken > 0 -> taken / 2
            else -> 0
        }
        guard = false
        val line = when (how) {
            0 -> "${atk.name} HITS YOU! $taken"
            1 -> "SOFTENED! YOU TAKE $taken"
            2 -> "BLOCKED!"
            else -> "PARRIED! YOU STRIKE BACK FOR $back"
        } + if (how < 3 && back > 0) " (THORNS: $back)" else ""
        round++
        if (back > 0) { bossHurtT = 0.6f; val over = host.hitBoss(back); if (over) return say(line) { finish("THE DUEL BREAKS OFF!") } }
        say(line) {
            when {
                host.playerHp <= 0 -> { st = St.END; done = true }
                round >= ROUNDS -> finish("HE SHAKES YOU LOOSE. BACK TO THE HALL!")
                else -> { st = St.MENU; cursor = 0 }
            }
        }
    }

    // ---- input
    fun up() { if (st == St.MENU) cursor = (cursor + options().size - 1) % options().size }
    fun down() { if (st == St.MENU) cursor = (cursor + 1) % options().size }
    fun pressA() {
        when (st) {
            St.INTRO -> { st = St.MENU; cursor = 0 }
            St.MENU -> options().getOrNull(cursor)?.act?.invoke()
            St.TEXT -> if (t > 0.25f) after?.invoke()
            St.ATTACK -> if (t > 0.4f) { st = St.DEF1; t = 0f; lastScore = -1f }
            else -> {}
        }
    }
    fun onStroke(s: List<FloatArray>) {
        if (s.size < 4) return
        when (st) {
            St.DEF1 -> {
                val k = host.score(s, atk.sigil); lastScore = k; scoreT = 1.2f
                if (k >= NEED) { st = St.DEF2; t = 0f; lastScore = -1f; host.buzz() }
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
        t += dt; heroHurtT -= dt; bossHurtT -= dt; shake -= dt; scoreT -= dt
        when (st) {
            St.INTRO -> if (t > 2.2f) { st = St.MENU; cursor = 0 }
            St.ATTACK -> if (t > 1.4f) { st = St.DEF1; t = 0f; lastScore = -1f }
            St.DEF1 -> if (t > limit1) land(0)
            St.DEF2 -> if (t > limit2) land(1)
            else -> {}
        }
    }
    private val limit1 get() = if (guard) 7f else 5f
    private val limit2 get() = 4.5f

    // =====================================================================================
    // The duel's art: 16-bit, richer than the hall's whites
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

    /** A window in the old style: black, a white double rule. */
    private fun window(c: Canvas, l: Float, tt: Float, r: Float, b: Float) {
        box(c, l, tt, r, b, 0xFFF8F8F8.toInt()); box(c, l + 1, tt + 1, r - 1, b - 1, 0xFF101018.toInt())
        box(c, l + 2, tt + 2, r - 2, b - 2, 0xFFB8B8C8.toInt()); box(c, l + 3, tt + 3, r - 3, b - 3, 0xFF101018.toInt())
    }

    private fun bar(c: Canvas, l: Float, tt: Float, w: Float, v: Float, col: Int) {
        box(c, l, tt, l + w, tt + 5, 0xFF2A2A34.toInt())
        box(c, l + 1, tt + 1, l + 1 + (w - 2) * v.coerceIn(0f, 1f), tt + 4, col)
    }

    fun draw(c: Canvas, time: Float, strokeNorm: List<FloatArray>) {
        val W = 240f; val H = 176f
        c.save()
        if (shake > 0f) c.translate((rnd.nextFloat() - 0.5f) * 4f, (rnd.nextFloat() - 0.5f) * 3f)
        // sky: dusk bands over a ruined arena
        val sky = intArrayOf(0xFF1A1030.toInt(), 0xFF2A1848.toInt(), 0xFF40205A.toInt(), 0xFF5A2C66.toInt(), 0xFF7A3C6A.toInt(), 0xFF9A5468.toInt())
        for (k in sky.indices) box(c, 0f, k * 13f, W, k * 13f + 13f, sky[k])
        for (k in 0 until 18) { val sx = (k * 53 % 240).toFloat(); val sy = (k * 29 % 50).toFloat(); if ((time * 2 + k).toInt() % 5 != 0) box(c, sx, sy, sx + 1, sy + 1, 0xFFE8E0F8.toInt()) }
        // broken arches on the horizon
        for (k in 0 until 5) {
            val ax = 6f + k * 50f
            box(c, ax, 46f, ax + 8, 80f, 0xFF2A1C3A.toInt()); box(c, ax + 30, 50f, ax + 38, 80f, 0xFF2A1C3A.toInt())
            box(c, ax, 42f, ax + 38, 48f, 0xFF33224A.toInt()); box(c, ax + 8, 48f, ax + 30, 52f, 0xFF33224A.toInt())
        }
        // the arena floor, in perspective
        box(c, 0f, 78f, W, H, 0xFF4A3A40.toInt())
        for (k in 0 until 6) { val y = 78f + k * k * 3.2f; box(c, 0f, y, W, y + 1, 0xFF3A2C32.toInt()) }
        for (k in -8..8) { val x0 = 120f + k * 14f; val x1 = 120f + k * 46f; line.color = 0xFF3A2C32.toInt(); line.strokeWidth = 1f; c.drawLine(x0, 78f, x1, H, line) }
        // torches either side
        for (tx in floatArrayOf(8f, 226f)) {
            box(c, tx, 60f, tx + 6, 96f, 0xFF2A1C1C.toInt()); box(c, tx + 1, 61f, tx + 5, 95f, 0xFF5A3A2A.toInt())
            val f = (time * 9).toInt() % 3
            box(c, tx - 1, 50f - f, tx + 7, 60f, 0xFFE8742C.toInt()); box(c, tx + 1, 46f + f, tx + 5, 58f, 0xFFF8D050.toInt()); box(c, tx + 2, 52f, tx + 4, 58f, 0xFFFFF8D0.toInt())
        }
        // the creature, large, right
        yeti?.let { bmp ->
            if (!(bossHurtT > 0f && (time * 20).toInt() % 2 == 0)) {
                val fr = when { st == St.ATTACK || st == St.DEF1 || st == St.DEF2 -> 3; bossHurtT > 0f -> 5; else -> if ((time * 1.5f).toInt() % 6 == 5) 4 else 0 }
                val bob = if ((time * 1.6f).toInt() % 2 == 0) 0f else 2f
                box(c, 134f, 114f, 222f, 118f, 0x55000000)
                src.set(fr * 56, 0, fr * 56 + 56, 56); dst.set(122f, 6f + bob, 234f, 118f + bob)
                c.drawBitmap(bmp, src, dst, p)
            }
        }
        // the traveller, from behind, left
        if (!(heroHurtT > 0f && (time * 20).toInt() % 2 == 0)) {
            box(c, 26f, 122f, 74f, 126f, 0x55000000)
            src.set(0, 0, heroBack.w, heroBack.h); dst.set(26f, 66f, 26f + heroBack.w * 2, 66f + heroBack.h * 2)
            c.drawBitmap(heroBack.bmp, src, dst, p)
        }
        // status windows
        window(c, 4f, 4f, 116f, 26f)
        font.color = 0xFFF8F8F8.toInt(); c.drawText("FIVE-EYED ONE", 9f, 14f, font)
        bar(c, 9f, 17f, 102f, host.bossHp / host.bossMax.toFloat(), 0xFFC040C0.toInt())
        window(c, 4f, 28f, 116f, 50f)
        c.drawText("YOU  ${host.playerHp}/${host.playerMax}", 9f, 38f, font)
        bar(c, 9f, 41f, 102f, host.playerHp / host.playerMax.toFloat(), if (host.playerHp < host.playerMax / 4) 0xFFE04848.toInt() else 0xFF48C058.toInt())
        // the message / menu window
        window(c, 4f, 128f, 236f, 172f)
        when (st) {
            St.MENU -> {
                val opts = options()
                opts.forEachIndexed { i, o ->
                    val x = 12f + (i % 2) * 112f; val y = 142f + (i / 2) * 14f
                    if (i == cursor && (time * 3).toInt() % 2 == 0) c.drawText(">", x - 7f, y, font)
                    font.color = if (i == cursor) 0xFFF8E070.toInt() else 0xFFF8F8F8.toInt()
                    c.drawText(o.label.take(13), x + 2f, y, font); font.color = 0xFFF8F8F8.toInt()
                }
            }
            St.DEF1, St.DEF2 -> {
                val s = if (st == St.DEF1) "TRACE HIS SIGIL TO SOFTEN!" else "EASY: BLOCK   HARD: PARRY!"
                c.drawText(s, 10f, 146f, font)
                val lim = if (st == St.DEF1) limit1 else limit2
                bar(c, 10f, 154f, 220f, 1f - t / lim, 0xFFF8E070.toInt())
            }
            else -> wrap(text, 27).take(3).forEachIndexed { i, s -> c.drawText(s, 10f, 142f + i * 11f, font) }
        }
        // the rune prompt(s)
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
        c.restore()
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
}
