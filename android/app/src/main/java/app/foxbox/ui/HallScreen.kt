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
        val MUTTER2 = listOf("HUSH.", "BLOOM...", "TSK.", "GROW.", "DEAR ME.")
        val CH2_LINES = listOf(
            "A SCENT OF FLOWERS DRIFTS UP FROM THE THRONE ROOM...",
            "SOMEONE ELSE HAS COME FOR HIM.",
        )
        val RANT2 = listOf(
            "...SO. YOU ARE THE ONE WHO FELLED MY HUSBAND.",
            "HE WAS LOUD, AND RUDE, AND HE NEVER ONCE WATERED THE GARDEN. BUT HE WAS MINE.",
            "I AM THE BLOSSOM OF THE FIVE EYES. I BLESS WHAT GROWS, AND I PRUNE WHAT DOES NOT.",
            "HOLD STILL, LITTLE WEED.",
        )
        val DEFEAT2 = listOf(
            "...THE PETALS FALL... AS THEY ALWAYS DO.",
            "TAKE MY DAGGER. TEND SOMETHING WITH IT. PLEASE.",
        )
        // props: [kind] → how tall it stands above its footprint (shots hit the whole height)
        const val PILLAR = 0; const val ROCK = 1; const val SHRUB = 2; const val STATUE = 3; const val FOUNTAIN = 4
        const val TABLE = 5; const val BRAZIER = 6; const val TOMB = 7; const val CHEST = 8
        // the Wildwood's: trees, stumps, fallen logs, mushrooms, and the root door out
        const val TREE = 9; const val STUMP = 10; const val LOG = 11; const val MUSHROOM = 12; const val EXIT = 13
        /** Dense thicket (baked into the scenery): the walls of a narrow passage. */
        const val THICKET = 14
        const val GRAVE = 15; const val RUIN_PILLAR = 16; const val RUIN_WALL = 17; const val DEADTREE = 18
        const val TENT = 19; const val CAMPFIRE = 20; const val STALL = 21; const val PALISADE = 22
        const val WELL = 23; const val CRATES = 24; const val BARREL = 25; const val HAY = 26; const val LANTERN = 27
        const val BENCH = 28; const val CART = 29; const val SIGNPOST = 30; const val FLOWERBED = 31; const val LAUNDRY = 32
        const val PLAZA = 33; const val COLUMN = 34; const val ARCH = 35; const val RUIN_STATUE = 36; const val FALLEN_COLUMN = 37
        const val FOREST = 10
        const val CLIFF = 38; const val BUILDING = 39
        fun propH(k: Int) = when (k) { PILLAR -> 70f; ROCK -> 12f; SHRUB -> 14f; STATUE -> 28f; FOUNTAIN -> 18f; TABLE -> 8f; BRAZIER -> 14f; TOMB -> 14f; TREE -> 46f; STUMP -> 9f; LOG -> 9f; MUSHROOM -> 9f; EXIT -> 34f; THICKET -> 8f; GRAVE -> 12f; RUIN_PILLAR -> 26f; RUIN_WALL -> 12f; DEADTREE -> 40f; TENT -> 22f; CAMPFIRE -> 6f; STALL -> 16f; PALISADE -> 10f; WELL -> 18f; CRATES -> 14f; BARREL -> 10f; HAY -> 10f; LANTERN -> 18f; BENCH -> 6f; CART -> 14f; SIGNPOST -> 18f; FLOWERBED -> 4f; LAUNDRY -> 20f; PLAZA -> 0f; COLUMN -> 46f; ARCH -> 40f; RUIN_STATUE -> 30f; FALLEN_COLUMN -> 8f; CLIFF -> 14f; BUILDING -> 40f; EXIT -> 58f; else -> 12f }
        const val BOSS_MAX = 400; const val LAST = 100
        /** How long a seal on a behemoth waits for the strike before it fades (or the brainwash begins). */
        const val SEAL_TIME = 1.1f
    }

    private val screen = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val sc = Canvas(screen)
    private val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val font = Paint().apply { isAntiAlias = false; typeface = Px.head; textSize = 8f; color = INK }
    private val yeti: Bitmap? = runCatching { c.assets.open("images/hall_yeti_axe.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    /** The creature in the title screen's detail, for the duel (facing you, a swipe, a lunge, a roar). */
    private val duelPose: Bitmap? = runCatching { c.assets.open("images/duel_stance.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    private val duelRects: List<Rect> = duelPose?.let { listOf(Rect(0, 0, it.width, it.height)) } ?: emptyList()
    /** The second foe, the Blossom: her hall sheet (72 x 72 cells like his) and her duel pose. */
    /** The wood's trees: twelve bold variants, 64 x 64 each, roots on row 61 (greens, teal-greens, olive, one autumn). */
    private val treeSheet: Bitmap? = runCatching { c.assets.open("images/wild_trees.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    /** Which variant a tree is, by where it stands: oaks, limes and conifers often; autumn, birch and blossom now and then. */
    private val TREE_PICK = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 0, 1, 6, 7, 3, 9, 2, 10, 11)
    private val blossomSheet: Bitmap? = runCatching { c.assets.open("images/hall_blossom.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    private val blossomPose: Bitmap? = runCatching { c.assets.open("images/duel_blossom.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    private val blossomRects: List<Rect> = blossomPose?.let { listOf(Rect(0, 0, it.width, it.height)) } ?: emptyList()
    /** Sprites are always drawn with this (full opacity), never with the shared colour paint. */
    private val bmpP = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
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
    /** The traveller, in more detail (20 x 30): red beanie, green flannel, brown trousers, boots. [facing][frame]: 0 down, 1 up, 2 left (right is mirrored). */
    private val heroPal = mapOf('K' to 0xFF1A141E.toInt(), 'S' to 0xFFF4BE8E.toInt(), 's' to 0xFFCE8C66.toInt(), 'p' to 0xFFEC8C82.toInt(), 'H' to 0xFF4A2A1A.toInt(), 'J' to 0xFF6C4224.toInt(), 'j' to 0xFF966034.toInt(), 'D' to 0xFF2C1A12.toInt(), 'A' to 0xFFA85C2A.toInt(), 'a' to 0xFF783E1C.toInt(), 'Q' to 0xFFCC8040.toInt(), 'E' to 0xFF221A2C.toInt(), 'W' to 0xFFFAFAF6.toInt(), 'G' to 0xFF3E9248.toInt(), 'g' to 0xFF1E542E.toInt(), 'L' to 0xFF96CE78.toInt(), 'T' to 0xFFECECE4.toInt(), 'B' to 0xFF845632.toInt(), 'b' to 0xFF56361E.toInt(), 'O' to 0xFF3E281E.toInt(), 'o' to 0xFF78563C.toInt(), 'Y' to 0xFFE8C048.toInt(), 'V' to 0xFF784E28.toInt(), 'v' to 0xFF54341A.toInt(), 'R' to 0xFFB42E2E.toInt())
    /** [facing][0 stand, 1 step]: 0 down, 1 up, 2 left (right is mirrored). */
    private val hero = arrayOf(arrayOf(Sprite(listOf("......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHDJHHK....", "...KKHHHHHHHHKK...", "..KJHHHHHHHHHHJK..", "..KJJHJSSSSJHJJK..", "..KHHSDDSSDDSHHK..", "..KHHSWESSWESHHK..", "..KHHpEEssEEpHHK..", "..KHHASQAAQSAHHK..", "..KHHAQappaAAHHK..", "..KHHaAAAAQaAHHK..", "..KHHAaQAaAAAHHK..", "..KJHKAAAAaAKHJK..", "..KJHKKAaAAKKHJK..", "..KHDKKAAAAKKDHK..", ".KGHVVLGTTLGgGHGK.", "KKGDKgVVKgggKgDGK.", "vvGvgGGGVVGGgGGGK.", "VVGVgGLGgGVVgGLGK.", "VYLVbbbbYYbbbbgLK.", "VVSVBBBBbbBBBBKSK.", "KKKKBbBBBBBBbBKK..", "...KBBBBBBBBBBK...", "..KooooooooooooK..", "..KOOOOOOOOOOOOK..", "..KOOOOOOOOOOOOK..", "...KKKKKKKKKKKK..."), heroPal, outline = null), Sprite(listOf("......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHDJHHK....", "...KKHHHHHHHHKK...", "..KJHHHHHHHHHHJK..", "..KJJHJSSSSJHJJK..", "..KHHSDDSSDDSHHK..", "..KHHSWESSWESHHK..", "..KHHpEEssEEpHHK..", "..KHHASQAAQSAHHK..", "..KHHAQappaAAHHK..", "..KHHaAAAAQaAHHK..", "..KHHAaQAaAAAHHK..", "..KJHKAAAAaAKHJK..", "..KJHKKAaAAKKHJK..", "..KHDKKAAAAKKDHK..", ".KGHVVLGTTLGgGHGK.", "KKGDKgVVKgggKgDGK.", "vvGvgGGGVVGGgGGGK.", "VVGVgGLGgGVVgGLGK.", "VYLVbbbbYYbbbbgLK.", "VVSVBbBBbbBBBBKSK.", "KKKKBBBBBBBBBBKK..", "..KooooooBBBbBK...", "..KOOOOOOBBBBBK...", "..KOOOOOOooooooK..", "...KKKKKKOOOOOOK..", "........KOOOOOOK.."), heroPal, outline = null)), arrayOf(Sprite(listOf("......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHHHHHK....", "....KHHHHHjHHK....", "...KHHHHHHHHHHK...", "...KHJHHJHDJHHK...", "...KJHHJHHJHDJK...", "...KJHHJHDJHHJK...", "...KJHDJHHJDHJK...", "...KHHJHDJHHJDK...", "...KHDJHHJDHJHK...", "...KHHJDHJHHJHK...", "...KDJHHJDHJHHK...", "...KHJDHJHHJHHK...", "...KHJHHJHHJHDK...", "..KKJDHJHHJHHJKK..", ".KGGLHHHHHHHVVgGK.", ".KGgggKgggVVggKGK.", ".KGGGGgGVVgGGGgGK.", ".KGGLGVVLGgGLGgGK.", ".KLgbbbbbbbbbbKLK.", ".KSKBBBBbbBBBBKSK.", "..KKBbBBBBBBbBKK..", "...KBBBBBBBBBBK...", "..KooooooooooooK..", "..KOOOOOOOOOOOOK..", "..KOOOOOOOOOOOOK..", "...KKKKKKKKKKKK..."), heroPal, outline = null), Sprite(listOf("......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHHHHHK....", "....KHHHHHjHHK....", "...KHHHHHHHHHHK...", "...KHJHHJHDJHHK...", "...KJHHJHHJHDJK...", "...KJHHJHDJHHJK...", "...KJHDJHHJDHJK...", "...KHHJHDJHHJDK...", "...KHDJHHJDHJHK...", "...KHHJDHJHHJHK...", "...KDJHHJDHJHHK...", "...KHJDHJHHJHHK...", "...KHJHHJHHJHDK...", "..KKJDHJHHJHHJKK..", ".KGGLHHHHHHHVVgGK.", ".KGgggKgggVVggKGK.", ".KGGGGgGVVgGGGgGK.", ".KGGLGVVLGgGLGgGK.", ".KLgbbbbbbbbbbKLK.", ".KSKBbBBbbBBBBKSK.", "..KKBBBBBBBBBBKK..", "..KooooooBBBbBK...", "..KOOOOOOBBBBBK...", "..KOOOOOOooooooK..", "...KKKKKKOOOOOOK..", "........KOOOOOOK.."), heroPal, outline = null)), arrayOf(Sprite(listOf("......KKKKK.......", ".....KJjJJJK......", "....KHjHHHHHK.....", "...KKHHHHHHHKK....", "..KHHHHSSHHHHHK...", "..KDDDSSSHHHHHK...", "..KSWESSSHJJJJK...", ".KSSEESpSsHJJJK...", ".KsSSSSSSSHHJHK...", ".KpaAAAASHHHHHK...", ".KAAAAAaAHHHHHK...", ".KAAQAAAAHHDHHK...", "..KAAQAAKHHHHHK...", "..KAAAaAKHHHHHK...", "...KAAAK.KKHHHK...", "...KAAAKKKKHHHK...", "....KTTgGGGgGDK...", "....KTTKGGgKvvvK..", "....KGGgGGGgVVVK..", "....KLGgGLGgVVVK..", "....KbbbbbbbbVVK..", ".....KBBBBBKVVVK..", ".....KBBBBBKKKK...", ".....KBBBBbK......", "....KooooooK......", "....KOOOOOOK......", "....KOOOOOOK......", ".....KKKKKK......."), heroPal, outline = null), Sprite(listOf("......KKKKK.......", ".....KJjJJJK......", "....KHjHHHHHK.....", "...KKHHHHHHHKK....", "..KHHHHSSHHHHHK...", "..KDDDSSSHHHHHK...", "..KSWESSSHJJJJK...", ".KSSEESpSsHJJJK...", ".KsSSSSSSSHHJHK...", ".KpaAAAASHHHHHK...", ".KAAAAAaAHHHHHK...", ".KAAQAAAAHHDHHK...", "..KAAQAAKHHHHHK...", "..KAAAaAKHHHHHK...", "...KAAAK.KKHHHK...", "...KAAAKKKKHHHK...", "....KGGgGLGgGDK...", "....KGGKgggKvvvK..", "....KGGgGGGgVVVK..", "....KGGgGLGgVVVK..", "...KSbbbbbbbbVVK..", "....KBBBKbbbVVVK..", "....KBBBKbbbKKK...", "...KKBBBKbbbKKK...", "..KoooooKKOOOOOK..", "..KOOOOOKKOOOOOK..", "..KOOOOOKKOOOOOK..", "...KKKKK..KKKKK..."), heroPal, outline = null)))
    /** Standing still: breathing, a blink, the scarf stirring. */
    private val heroIdle = arrayOf(arrayOf(Sprite(listOf("......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHDJHHK....", "...KKHHHHHHHHKK...", "..KJHHHHHHHHHHJK..", "..KJJHJSSSSJHJJK..", "..KHHSDDSSDDSHHK..", "..KHHSWESSWESHHK..", "..KHHpEEssEEpHHK..", "..KHHASQAAQSAHHK..", "..KHHAQappaAAHHK..", "..KHHaAAAAQaAHHK..", "..KHHAaQAaAAAHHK..", "..KJHKAAAAaAKHJK..", "..KJHKKAaAAKKHJK..", "..KHDKKAAAAKKDHK..", ".KGHVVLGTTLGgGHGK.", "KKGDKgVVKgggKgDGK.", "vvGvgGGGVVGGgGGGK.", "VVGVgGLGgGVVgGLGK.", "VYLVbbbbYYbbbbgLK.", "VVSVBBBBbbBBBBKSK.", "KKKKBbBBBBBBbBKK..", "...KBBBBBBBBBBK...", "..KooooooooooooK..", "..KOOOOOOOOOOOOK..", "..KOOOOOOOOOOOOK..", "...KKKKKKKKKKKK..."), heroPal, outline = null), Sprite(listOf("..................", "......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHDJHHK....", "...KKHHHHHHHHKK...", "..KJHHHHHHHHHHJK..", "..KJJHJSSSSJHJJK..", "..KHHSDDSSDDSHHK..", "..KHHSWESSWESHHK..", "..KHHpEEssEEpHHK..", "..KHHASQAAQSAHHK..", "..KHHAQappaAAHHK..", "..KHHaAAAAQaAHHK..", "..KJHAaQAaAAAHJK..", "..KJHKAAaAaAKHJK..", "..KHDKKAAAAKKDHK..", ".KGHVVLGTTLGgGHGK.", "KKGDKgVVKgggKgDGK.", "vvGvgGGGVVGGgGGGK.", "VVGVgGLGgGVVgGLGK.", "VYLVbbbbYYbbbbgLK.", "VVSVBBBBbbBBBBKSK.", "KKKKBbBBBBBBbBKK..", "...KBBBBBBBBBBK...", "..KooooooooooooK..", "..KOOOOOOOOOOOOK..", "..KOOOOOOOOOOOOK..", "...KKKKKKKKKKKK..."), heroPal, outline = null), Sprite(listOf("......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHDJHHK....", "...KKHHHHHHHHKK...", "..KJHHHHHHHHHHJK..", "..KJJHJSSSSJHJJK..", "..KHHSDDSSDDSHHK..", "..KHHSEESSEESHHK..", "..KHHpSSssSSpHHK..", "..KHHASQAAQSAHHK..", "..KHHAQappaAAHHK..", "..KHHaAAAAQaAHHK..", "..KHHAaQAaAAAHHK..", "..KJHKAAAAaAKHJK..", "..KJHKKAaAAKKHJK..", "..KHDKKAAAAKKDHK..", ".KGHVVLGTTLGgGHGK.", "KKGDKgVVKgggKgDGK.", "vvGvgGGGVVGGgGGGK.", "VVGVgGLGgGVVgGLGK.", "VYLVbbbbYYbbbbgLK.", "VVSVBBBBbbBBBBKSK.", "KKKKBbBBBBBBbBKK..", "...KBBBBBBBBBBK...", "..KooooooooooooK..", "..KOOOOOOOOOOOOK..", "..KOOOOOOOOOOOOK..", "...KKKKKKKKKKKK..."), heroPal, outline = null), Sprite(listOf("......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHDJHHK....", "...KKHHHHHHHHKK...", "..KJHHHHHHHHHHJK..", "..KJJHJSSSSJHJJK..", "..KHHSDDSSDDSHHK..", "..KHHSWESSWESHHK..", "..KHHpEEssEEpHHK..", "..KHHASQAAQSAHHK..", "..KHHAQappaAAHHK..", "..KHHaAAAAQaAHHK..", "..KHHAaQAaAAAHHK..", "..KJHKAAAAaAKHJK..", "..KJHHKAaAAK.KJHK.", "..KHDDKAAAAKKKHDK.", ".KGGHVLGTTLGgGLHK.", "KKGgDgVVKgggKggDK.", "vvGvgGGGVVGGgGGGK.", "VVGVgGLGgGVVgGLGK.", "VYLVbbbbYYbbbbgLK.", "VVSVBBBBbbBBBBKSK.", "KKKKBbBBBBBBbBKK..", "...KBBBBBBBBBBK...", "..KooooooooooooK..", "..KOOOOOOOOOOOOK..", "..KOOOOOOOOOOOOK..", "...KKKKKKKKKKKK..."), heroPal, outline = null)), arrayOf(Sprite(listOf("......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHHHHHK....", "....KHHHHHjHHK....", "...KHHHHHHHHHHK...", "...KHJHHJHDJHHK...", "...KJHHJHHJHDJK...", "...KJHHJHDJHHJK...", "...KJHDJHHJDHJK...", "...KHHJHDJHHJDK...", "...KHDJHHJDHJHK...", "...KHHJDHJHHJHK...", "...KDJHHJDHJHHK...", "...KHJDHJHHJHHK...", "...KHJHHJHHJHDK...", "..KKJDHJHHJHHJKK..", ".KGGLHHHHHHHVVgGK.", ".KGgggKgggVVggKGK.", ".KGGGGgGVVgGGGgGK.", ".KGGLGVVLGgGLGgGK.", ".KLgbbbbbbbbbbKLK.", ".KSKBBBBbbBBBBKSK.", "..KKBbBBBBBBbBKK..", "...KBBBBBBBBBBK...", "..KooooooooooooK..", "..KOOOOOOOOOOOOK..", "..KOOOOOOOOOOOOK..", "...KKKKKKKKKKKK..."), heroPal, outline = null), Sprite(listOf("..................", "......KKKKKK......", ".....KJJjJJJK.....", "....KHHjHHHHHK....", "....KHHHHHjHHK....", "...KHHHHHHHHHHK...", "...KJHHJHHJHDJK...", "...KJHHJHDJHHJK...", "...KJHDJHHJDHJK...", "...KHHJHDJHHJDK...", "...KHDJHHJDHJHK...", "...KHHJDHJHHJHK...", "...KDJHHJDHJHHK...", "...KHJDHJHHJHHK...", "...KHJHHJHHJHDK...", "..KKJDHJHHJHHJKK..", ".KGGLHHHHHHHVVgGK.", ".KGgggKgggVVggKGK.", ".KGGGGgGVVgGGGgGK.", ".KGGLGVVLGgGLGgGK.", ".KLgbbbbbbbbbbKLK.", ".KSKBBBBbbBBBBKSK.", "..KKBbBBBBBBbBKK..", "...KBBBBBBBBBBK...", "..KooooooooooooK..", "..KOOOOOOOOOOOOK..", "..KOOOOOOOOOOOOK..", "...KKKKKKKKKKKK..."), heroPal, outline = null)), arrayOf(Sprite(listOf("......KKKKK.......", ".....KJjJJJK......", "....KHjHHHHHK.....", "...KKHHHHHHHKK....", "..KHHHHSSHHHHHK...", "..KDDDSSSHHHHHK...", "..KSWESSSHJJJJK...", ".KSSEESpSsHJJJK...", ".KsSSSSSSSHHJHK...", ".KpaAAAASHHHHHK...", ".KAAAAAaAHHHHHK...", ".KAAQAAAAHHDHHK...", "..KAAQAAKHHHHHK...", "..KAAAaAKHHHHHK...", "...KAAAK.KKHHHK...", "...KAAAKKKKHHHK...", "....KTTgGGGgGDK...", "....KTTKGGgKvvvK..", "....KGGgGGGgVVVK..", "....KLGgGLGgVVVK..", "....KbbbbbbbbVVK..", ".....KBBBBBKVVVK..", ".....KBBBBBKKKK...", ".....KBBBBbK......", "....KooooooK......", "....KOOOOOOK......", "....KOOOOOOK......", ".....KKKKKK......."), heroPal, outline = null), Sprite(listOf("..................", "......KKKKK.......", ".....KJjJJJK......", "....KHjHHHHHK.....", "...KKHHHHHHHKK....", "..KHHHHSSHHHHHK...", "..KDDDSSSHHHHHK...", "..KSWESSSHJJJJK...", ".KSSEESpSsHJJJK...", ".KsSSSSSSSHHJHK...", ".KpaAAAASHHHHHK...", ".KAAAAAaAHHDHHK...", ".KAAQAAAAHHHHHK...", "..KAAQAAKHHHHHK...", "..KAAAaAKKKHHHK...", "...KAAAKKKKHHHK...", "....KTTgGGGgGDK...", "....KTTKGGgKvvvK..", "....KGGgGGGgVVVK..", "....KLGgGLGgVVVK..", "....KbbbbbbbbVVK..", ".....KBBBBBKVVVK..", ".....KBBBBBKKKK...", ".....KBBBBbK......", "....KooooooK......", "....KOOOOOOK......", "....KOOOOOOK......", ".....KKKKKK......."), heroPal, outline = null), Sprite(listOf("......KKKKK.......", ".....KJjJJJK......", "....KHjHHHHHK.....", "...KKHHHHHHHKK....", "..KHHHHSSHHHHHK...", "..KDDDSSSHHHHHK...", "..KSEESSSHJJJJK...", ".KSSSSSpSsHJJJK...", ".KsSSSSSSSHHJHK...", ".KpaAAAASHHHHHK...", ".KAAAAAaAHHHHHK...", ".KAAQAAAAHHDHHK...", "..KAAQAAKHHHHHK...", "..KAAAaAKHHHHHK...", "...KAAAK.KKHHHK...", "...KAAAKKKKHHHK...", "....KTTgGGGgGDK...", "....KTTKGGgKvvvK..", "....KGGgGGGgVVVK..", "....KLGgGLGgVVVK..", "....KbbbbbbbbVVK..", ".....KBBBBBKVVVK..", ".....KBBBBBKKKK...", ".....KBBBBbK......", "....KooooooK......", "....KOOOOOOK......", "....KOOOOOOK......", ".....KKKKKK......."), heroPal, outline = null)))

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
        0 to Room("ARMORY", 4, "S", listOf(pr(STATUE, 40, 64, 56, 74), pr(STATUE, 76, 64, 92, 74), pr(STATUE, 148, 64, 164, 74), pr(STATUE, 184, 64, 200, 74),
            pr(BRAZIER, 60, 140, 72, 148), pr(BRAZIER, 168, 140, 180, 148), pr(ROCK, 100, 150, 114, 158)), listOf(pool(104, 96, 136, 116))),
        2 to Room("CHAPEL", 2, "S", listOf(pr(FOUNTAIN, 100, 70, 140, 88), pr(SHRUB, 44, 60, 60, 68), pr(SHRUB, 180, 60, 196, 68), pr(TOMB, 52, 132, 84, 142), pr(TOMB, 156, 132, 188, 142)),
            listOf(pool(28, 100, 60, 120), pool(180, 100, 212, 120))),
        1 to Room("TREASURY", 6, "S", listOf(pr(PILLAR, 44, 72, 60, 80), pr(PILLAR, 180, 72, 196, 80), pr(CHEST, 108, 62, 132, 72)), listOf(pool(160, 116, 200, 132))),
        3 to Room("WEST GALLERY", 1, "NES",
            listOf(pr(STATUE, 36, 60, 52, 70), pr(STATUE, 84, 60, 100, 70), pr(STATUE, 140, 60, 156, 70), pr(STATUE, 188, 60, 204, 70),
                pr(SHRUB, 40, 132, 56, 140), pr(SHRUB, 184, 132, 200, 140), pr(ROCK, 60, 152, 74, 160)),
            listOf(pool(92, 96, 148, 120))),
        4 to Room("THRONE ROOM", 0, "WES",
            listOf(pr(PILLAR, 44, 72, 60, 80), pr(PILLAR, 180, 72, 196, 80), pr(PILLAR, 44, 150, 60, 158), pr(PILLAR, 180, 150, 196, 158),
                pr(ROCK, 74, 146, 88, 154), pr(ROCK, 152, 146, 166, 154)),
            listOf(pool(70, 112, 98, 128), pool(142, 112, 170, 128))),
        5 to Room("EAST GALLERY", 2, "NWS",
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
    /** The Wildwood, while you're in it: the level's layout, and its screens as rooms. */
    private var wild: WildLevel? = null
    private var wildRooms: List<Room> = emptyList()
    private val inWild get() = wild != null
    /** In the wood the whole level is one open place (one "room", 100); rx, ry say which stretch of it you're in. */
    private val roomId get() = if (wild != null) 100 else 4
    private val room get() = if (wild != null) openRoom else hallRoom
    /**
     * The great hall is one open place now, three rooms by three, walked straight through: wide
     * arches between rooms that join, solid walls where they don't, and the treasury behind the
     * throne room's back wall (until it cracks). [zone] is the room you're standing in.
     */
    private val zone get() = (ry + 1) * 3 + rx
    private val zoneRoom get() = rooms.getValue(zone)
    private fun zoneOX(z: Int) = (z % 3) * 240f
    private fun zoneOY(z: Int) = (z / 3) * 176f
    private val TX = 240f; private val TY = 176f          // the throne room's corner
    private fun joined(a: Int, b: Int): Boolean {
        if (a == 1 || b == 1) return false
        val ra = rooms[a] ?: return false
        return when (b - a) { 1 -> a % 3 != 2 && 'E' in ra.doors; -1 -> a % 3 != 0 && 'W' in ra.doors; 3 -> 'S' in ra.doors; -3 -> 'N' in ra.doors; else -> false }
    }
    private fun hallOpenSides(z: Int) = booleanArrayOf(z % 3 != 0 && joined(z, z - 1), z % 3 != 2 && joined(z, z + 1), z >= 3 && joined(z, z - 3), z < 6 && joined(z, z + 3))
    private val hallRoom: Room by lazy {
        val props = ArrayList<Prop>(); val pools = ArrayList<RectF>()
        for ((z, r) in rooms) {
            val ox = zoneOX(z); val oy = zoneOY(z)
            for (pp in r.props) props += Prop(pp.kind, RectF(pp.foot.left + ox, pp.foot.top + oy, pp.foot.right + ox, pp.foot.bottom + oy))
            for (w in r.pools) pools += RectF(w.left + ox, w.top + oy, w.right + ox, w.bottom + oy)
        }
        Room("GREAT HALL", 0, "", props, pools)
    }
    /** The hall's walls: each room's top wall (an arch through it where it joins the room above) and its side walls where it doesn't join. */
    private val hallWalls: List<RectF> by lazy {
        val out = ArrayList<RectF>()
        for (z in rooms.keys) {
            val ox = zoneOX(z); val oy = zoneOY(z); val o = hallOpenSides(z)
            when {
                z == 4 -> { out += RectF(ox, oy, ox + 104, oy + 31); out += RectF(ox + 136, oy, ox + 240, oy + 31) }
                o[2] -> { out += RectF(ox, oy, ox + 72, oy + 31); out += RectF(ox + 168, oy, ox + 240, oy + 31) }
                else -> out += RectF(ox, oy, ox + 240, oy + 31)
            }
            if (!o[0]) out += RectF(ox, oy, ox + 4, oy + 176)
            if (!o[1]) out += RectF(ox + 236, oy, ox + 240, oy + 176)
        }
        out
    }
    /** The throne room's back wall where it cracks through to the treasury. */
    private val crackWall = RectF(TX + 104, TY, TX + 136, TY + 31)
    private val crackPassable get() = crackOpen && (phase == Phase.WON || chapter == 2)
    private var openRoom = Room("", 10, "WENS", emptyList(), emptyList())
    /** The world's size (the hall: one screen), the camera, and where new foes may appear. */
    private var WW = 240f; private var WH = 176f; private var topY = 32f
    private var camX = 0f; private var camY = 0f
    private var spL = 0f; private var spT = 0f; private var spR = 240f; private var spB = 176f
    /** The wood's props and water, sorted by stretch, so collisions only look nearby. */
    private var chunkProps: Array<List<Prop>> = emptyArray(); private var chunkPools: Array<List<RectF>> = emptyArray(); private var chunkPonds: Array<List<RectF>> = emptyArray()
    private var chunkExtra: Array<List<IntArray>> = emptyArray()
    /** Locked gates in the hedgerows: where, and the two edges they lock. */
    private class Gate(val r: RectF, val a: Int, val b: Int, val vertical: Boolean)
    private val gates = ArrayList<Gate>()
    private val chunkArt = LinkedHashMap<Int, HallRoomArt>()
    /** Which level of the wood (0: still in the hall), when the next beast waits, the seed of this level, keys in hand. */
    private var depth = 0; private var beastDepth = 0; private var wildSeed = 0L; private var keys = 0; private var stoneKeys = 0
    /** The stone door opening: time since it began (-1: shut), and where it stands. */
    private var doorT = -1f; private var doorX = 0f; private var doorY = 0f
    /** Screens of this level you've seen; keys found (by index); the beast, when it's here. */
    private val visited = HashSet<Int>(); private val keysTaken = HashSet<Int>(); private val gatesSeen = HashSet<Int>()
    private var beast: WildBeast? = null; private var beastRoom = -1; private var beastWon = false
    private val fortHere get() = wild?.let { ry * it.w + rx in it.fortRooms } == true
    private val beastHere get() = beast != null && beastRoom == roomId && !beast!!.dead
    private var forestOpen = false
    /**
     * Energy, built only by damage you deal yourself: four behemoths' worth fills it. Full, it
     * becomes Fury: for a few seconds every circle you draw, struck through with a line, cuts
     * down everything inside it; as many times as you can manage.
     */
    private var energy = 0f; private var furyT = 0f
    private val ENERGY_MAX = 96f
    private var furyRing: FloatArray? = null; private var furyRingT = 0f
    private fun charge(n: Int) {
        if (n <= 0 || furyT > 0f || phase != Phase.FIGHT) return
        energy += n
        if (energy >= ENERGY_MAX) {
            energy = 0f; furyT = 8f; furyRing = null
            flashT = 0.3f; shakeT = 0.3f
            say("FURY! CIRCLE THEM, THEN SLASH THROUGH, AS MANY TIMES AS YOU CAN!", 2.6f)
            sfx.play(HallSfx.S.EXOTIC); sfx.rumbleFanfare()
        }
    }
    /** Coins, from fallen foes and odd corners; spent at the forts. */
    private var coins = 0
    /** The fort's folk: a toad with herbs, a moth-witch with spirit blooms, monsters for hire. */
    private class Npc(val room: Int, var x: Float, var y: Float, val role: Int, val kind: Int) {
        var gone = false; val homeX = x; val homeY = y; var tx = x; var ty = y
        var waitT = kotlin.random.Random.nextFloat() * 3f; var moving = false; var face = 1f
        /** A guard's two moves: the air blast (cdA) and the ground stop (cdB); actT shows it acting. */
        var cdA = 0f; var cdB = 0f; var actT = 0f; var busy = 0
    }
    /**
     * The fort's folk: townsfolk wander between spots and stop to do something (sweep, chat, rest),
     * mercenaries shuffle about near their post, guards pace their gate. A guard holds back any foe
     * that comes near: an air blast that throws one back, or a stamp that stops all close by.
     */
    private fun updateNpcs(dt: Float) {
        for (n in npcs) {
            if (n.gone) continue
            if (n.role == WildLevel.NPC_TOAD || n.role == WildLevel.NPC_MOTH) continue
            n.actT -= dt; n.cdA -= dt; n.cdB -= dt
            val range = when (n.role) { WildLevel.NPC_FOLK -> 46f; WildLevel.NPC_GUARD -> 12f; else -> 14f }
            if (n.waitT > 0f) { n.waitT -= dt; n.moving = false }
            else {
                val dx = n.tx - n.x; val dy = n.ty - n.y; val d = hypot(dx, dy)
                if (d < 1.5f) {
                    n.waitT = if (n.role == WildLevel.NPC_FOLK) 1.5f + rnd.nextFloat() * 3.5f else 2f + rnd.nextFloat() * 4f
                    n.busy = rnd.nextInt(3)
                    for (k in 0 until 8) { val a = rnd.nextFloat() * 6.2832f; val rr = rnd.nextFloat() * range; val x = n.homeX + cos(a) * rr; val y = n.homeY + sin(a) * rr * 0.7f; if (!solid(x - 4, y - 3, x + 4, y + 1)) { n.tx = x; n.ty = y; break } }
                } else {
                    val sp = (if (n.role == WildLevel.NPC_FOLK) 16f else 10f) * dt
                    val nx = n.x + dx / d * minOf(sp, d); val ny = n.y + dy / d * minOf(sp, d)
                    if (solid(nx - 4, ny - 3, nx + 4, ny + 1)) { n.tx = n.x; n.ty = n.y } else { n.x = nx; n.y = ny; n.moving = true; if (abs(dx) > 0.5f) n.face = if (dx > 0f) 1f else -1f }
                }
            }
            if (n.role != WildLevel.NPC_GUARD || phase != Phase.FIGHT) continue
            val foes = minions.filter { !it.ally && it.hp > 0 && it.popT <= 0f && it.airT <= 0f && hypot(it.x - n.x, it.y - n.y) < 80f }
            if (foes.isEmpty()) continue
            val close = foes.filter { hypot(it.x - n.x, it.y - n.y) < 46f }
            if (close.size >= 2 && n.cdB <= 0f) {
                // the ground stop: a stamp, a ring through the earth, and everything near stands rooted
                n.cdB = 6f; n.actT = 0.5f
                for (m in close) { m.stun = maxOf(m.stun, 2.2f); m.slow = maxOf(m.slow, 3f); bursts += Burst(m.x, m.y) }
                rings += Ring(n.x, n.y); rings += Ring(n.x, n.y); shakeT = maxOf(shakeT, 0.15f)
                sfx.play(HallSfx.S.STOMP, 0.5f)
            } else if (n.cdA <= 0f) {
                // the air blast: the nearest one flung back the way it came
                val m = foes.minByOrNull { hypot(it.x - n.x, it.y - n.y) }!!
                n.cdA = 2.4f; n.actT = 0.4f; n.face = if (m.x > n.x) 1f else -1f
                val dx = m.x - n.x; val dy = m.y - n.y; val d = hypot(dx, dy).coerceAtLeast(1f)
                val k = if (m.kind == 2) 90f else 260f
                m.kx = dx / d * k; m.ky = dy / d * k; m.stun = maxOf(m.stun, 0.7f)
                hitImp(m, 1, true)
                arcs += Arc(n.x, n.y - 10, m.x, m.y - 6); fxs += Fx(FX_WALL, m.x, m.y - 4, 0.4f, 2f)
                sfx.play(HallSfx.S.SPRINT, 0.4f)
            }
        }
    }
    private val npcs = ArrayList<Npc>()
    private var offered: Npc? = null; private var offerT = 0f
    private val throne get() = wild == null && zone == 4
    private var bannerT = 0f

    private enum class Phase { INTRO, FIGHT, WON, LOST }
    private var phase = Phase.INTRO

    // the traveller
    /** Fragile: a few hits and you're done, so keep your distance and spark them from range. */
    private val BASE_HP = 60
    private var maxHp = BASE_HP
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
    /** 1: the five-eyed creature; 2: the Blossom, who comes once he has fallen. */
    private var chapter = 1
    /** Where the boss is (she can follow you out of the throne room in her last stand). */
    private var bossRoom = 4
    private val bossHere get() = roomId == bossRoom
    private fun bossOnScreen() = bx > camX - 6f && bx < camX + W + 6f && by > camY + 4f && by - 40f < camY + H
    /** Off screen, the boss can follow you but can't work anything on you: its spells wait, a spell being cast fizzles. */
    private fun holdBossSpells() {
        if (bossOnScreen()) return
        if (castRune >= 0) { castRune = -1; stroke.clear(); say("THE SPELL FIZZLES OUT OF SIGHT", 1.2f) }
        bossShotT = maxOf(bossShotT, 0.8f); spellT = maxOf(spellT, 1.5f); summonT = maxOf(summonT, 1.5f)
        seedT = maxOf(seedT, 1f); lashT = maxOf(lashT, 1f); mandalaT = maxOf(mandalaT, 1f)
    }
    /** Something big to hit here: the hall's boss (not mid-slam), or the beast of the wood; and where it stands. */
    private val bigHere get() = (bossHere && stage != Stage.SLAM) || beastHere
    private val gx get() = if (beastHere) beast!!.x else bx
    private val gy get() = if (beastHere) beast!!.y else by
    private var followT = 0f; private var entryX = 0f; private var entryY = 0f
    private val bossMax get() = if (chapter == 2) 450 else BOSS_MAX
    private val bossLast get() = if (chapter == 2) 120 else LAST
    /** Her garden: seeds that sprout into thorn flowers (they shoot petals and root you if you step close). */
    private class Seed(val x: Float, val y: Float, val room: Int) { var t = 0f; var shotT = 2.5f; var life = 16f; var rootCd = 0f }
    private val seeds = ArrayList<Seed>()
    /** Her vine lash: a line of sprouts, then thorny vines burst up along it. */
    private class Lash(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val room: Int) { var t = 0f; var hit = false }
    private val lashes = ArrayList<Lash>()
    /** Her sacred bloom: a turning geometric pattern hovering over the floor around you; it bursts when complete. */
    private class Mandala(val x: Float, val y: Float, val room: Int) { var t = 0f; var hit = false }
    private val mandalas = ArrayList<Mandala>()
    private var mandalaT = 9f
    private val MANDALA_R = 30f; private val MANDALA_CHARGE = 1.5f
    private var seedT = 5f; private var lashT = 7f
    private var savedFriend: Friend? = null
    private var loaded = false
    /** When he falls he perishes (fades to ash) and leaves his axe. */
    private var perishT = 0f; private var bossGone = false
    private val spiritX = 420f; private val spiritY = 112f
    private var spiritGift = false; private var spiritTalks = 0
    private fun nearSpirit() = wild == null && zone == 1 && hypot(hx + 8 - spiritX, hy + 12 - (spiritY + 8)) < 30f
    /** A: speak with the spirit. It heals and cleanses each time; the first time it gives a gift. */
    private fun speakToSpirit() {
        hp = maxHp; poisonT = 0f; paraT = 0f; invertT = 0f
        bursts += Burst(spiritX, spiritY - 6); bursts += Burst(hx + 8, hy + 8)
        eachComp { val fk = friend; if (fk != null && friendHere) frHp = fk.maxHp }
        talking = true; line = 0; shown = 0f
        if (!spiritGift) {
            spiritGift = true; talkLines = SPIRIT_FIRST
            val ex = Loot.values().filter { it.rarity == Rarity.EXOTIC && it !in bag.owned }
            msg = takeLoot(if (ex.isNotEmpty()) ex[rnd.nextInt(ex.size)] else rollLoot(0f)); msgT = 4f
        } else { talkLines = listOf(SPIRIT_AGAIN[spiritTalks % SPIRIT_AGAIN.size]); spiritTalks++; sfx.play(HallSfx.S.MEND, 0.7f) }
        sfx.rumbleSoft()
    }
    private val PERISH = 2.6f
    private val dais = RectF(324f, 210f, 396f, 262f)
    private val daisLocal = RectF(84f, 34f, 156f, 86f)

    // spells: the rune he puts on your screen, and your attempt on the XY pad
    private val runeNames get() = if (chapter == 2) listOf("ROOTS", "POLLEN", "DAZE", "DUEL", "BLESSING") else listOf("BIND", "VENOM", "MIRROR", "DUEL", "BLESSING")
    private val runes = listOf(
        listOf(0.5f to 0.06f, 0.94f to 0.9f, 0.06f to 0.9f, 0.5f to 0.06f),                        // a triangle: paralyses
        listOf(0.05f to 0.2f, 0.35f to 0.82f, 0.5f to 0.2f, 0.65f to 0.82f, 0.95f to 0.2f),         // a zig-zag: poisons
        listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f, 0.9f to 0.9f, 0.1f to 0.1f),               // an hourglass: mirrors
        RuneShapes.STAR,                                                                            // a star: pulls you into a duel
        RuneShapes.LEAF,                                                                            // a leaf: her blessing (she heals)
    )
    private val runeClosed = booleanArrayOf(true, false, true, true, true)
    private var castRune = -1; private var castT = 0f
    private val stroke = ArrayList<FloatArray>()
    private var lastScore = -1f; private var scoreT = 0f
    private var msg = ""; private var msgT = 0f
    private val NEED = 0.62f

    // imps, shots, holy lines, sparks, balls of light
    /** kind 0: an imp; 1: a stalker (circles, then lunges); 2: a behemoth (stagger, stun, banish with a circle). */
    private class Minion(var x: Float, var y: Float, var room: Int, val kind: Int = 0) {
        var hp = when (kind) { 1 -> 5; 2 -> 24; 3 -> 3; 4 -> 2; else -> 4 }
        /** Temperament, so no two act alike: pace, 0 rusher / 1 skirmisher (keeps back, shoots more) / 2 flanker, which side it flanks from. */
        val pace = 0.8f + kotlin.random.Random.nextFloat() * 0.45f
        val style = kotlin.random.Random.nextInt(3)
        val flank = (kotlin.random.Random.nextFloat() - 0.5f) * 1.8f
        /** A short whim: 0 hesitate, 1 sidestep, 2 lunge forward. */
        var mood = 0f; var moodKind = 0; var mx = 0f; var my = 0f
        var maxHp = hp
        /** Brainwashed: fights for you (in an ally's colours) until the level is done; [perm] if it's from your party. */
        var ally = false; var perm = false; var atkCd = 0f; var slot = -1; var supCd = 4f
        /** A drake: behemoth-class, but it comes down out of the sky, breathes fire and takes off again. */
        var drake = false; var airT = 0f; var flyCd = 6f; var breathCd = 3f
        /** Hired at a fort: fights for you this level, then goes its way. */
        var merc = false
        /** The behemoth: dazed (circle him now!), enraged, his slam wind-up and cool-down, rising from the floor. */
        var daze = 0f; var rage = 0f; var slamT = 0f; var slamCd = 3f; var popT = 0f
        /** The stalker: 0 prowling, 1 crouched to spring, 2 lunging, 3 recovering; its lunge and which way it circles. */
        var st = 0; var stT = 2f; var dvx = 0f; var dvy = 0f; var orbit = 1f; var lunged = false
        /** Facing (for the mirrored sprites); getting about: where it was, how long it's been stuck, a detour. */
        var face = 1f; var lx = 0f; var ly = 0f; var stuckT = 0f; var detT = 0f; var dtx = 0f; var dty = 0f
        var cd = 2f + kotlin.random.Random.nextFloat() * 2f; var hurtT = 0f; var step = 0f
        var kx = 0f; var ky = 0f; var stun = 0f    // knocked back by a stomp
        var para = 0f; var weak = 0f; var conf = 0f; var wt = 0f; var wx = 0f; var wy = 0f
        /** Frozen solid; on fire; hexed (slowed); the firewall's bite; the last fire-dash that hit it. */
        var ice = 0f; var burn = 0f; var burnTick = 0f; var slow = 0f; var fireCd = 0f; var dash = -1
    }
    private class Shot(var x: Float, var y: Float, val vx: Float, val vy: Float, val boss: Boolean, val weak: Boolean) { var life = 5f; var petal = false; var spore = false; var ally = false }
    /** A holy line (style 0), or a companion's wall: 1 thorns, 2 moonlight, 3 ice, 4 stone, 5 fire, 6 roots. */
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
    private fun xpNeed(l: Int) = (15 + 10 * l) * 10
    private var levelFxT = 0f
    private fun gainXp(n: Int) {
        if (lvl >= 20 || n <= 0) return
        xp += n
        while (lvl < 20 && xp >= xpNeed(lvl)) {
            xp -= xpNeed(lvl); lvl++; maxHp += 1; hp = minOf(maxHp, hp + 10); saveSoon = true
            levelFxT = 1.4f; msg = "LEVEL $lvl! YOUR FIREFLY GROWS"; msgT = 2.2f
            sfx.play(HallSfx.S.LEVEL_UP); sfx.rumbleFanfare()
        }
        if (lvl >= 20) xp = 0
    }
    private fun flySize() = 1f + 3f * (lvl - 1) / 19f
    private var impSfxT = 0f; private var burnSfxT = 0f
    /** Every hit on an imp comes through here: the damage, a flash, a blip, the experience. */
    private fun hitImp(m: Minion, n: Int, quiet: Boolean = false) {
        if (m.ally || m.hp <= 0 || n <= 0 || m.popT > 0f || m.airT > 0f || (m.kind == 4 && m.st == 2)) return
        gainXp(minOf(n, m.hp) * 3)
        m.hp -= n; m.hurtT = 0.3f
        if (m.kind == 2 && m.hp <= 0) {
            // staggered: he drops to his knees. Now's the time to circle him.
            m.hp = 0; m.daze = 5.5f; m.slamT = 0f; m.rage = 0f
            say("IT REELS! CIRCLE IT, THEN STRIKE A LINE THROUGH!", 2.2f); sfx.play(HallSfx.S.COUNTER); shakeT = maxOf(shakeT, 0.2f); sfx.rumbleBig()
            return
        }
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
        saveSoon = true
        if (l == Loot.RUNECASTER && bag.wearing(l)) return RUNE_HINT
        return s
    }
    private val RUNE_HINT = "RUNECASTER! CIRCLE YOURSELF WITH L, THEN TRACE UP TO THREE OF SIX RUNES ON THE PAD"

    // ---- effects: abilities, spells, the firewall, falling stars and lightning
    private class Fx(val type: Int, val x: Float, val y: Float, val max: Float, val a: Float = 0f) { var life = max }
    private val fxs = ArrayList<Fx>()
    private val FX_NOVA = 1; private val FX_NOVA_STAR = 2; private val FX_MOON = 3; private val FX_FROST = 4; private val FX_AEGIS = 5
    private val FX_SHATTER = 6; private val FX_MEND = 7; private val FX_HEX = 8; private val FX_REVIVE = 9; private val FX_JOIN = 10
    private val FX_PETALS = 16
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
    private val RUNE_TIME = 10f
    /** Spells cast since the book opened: up to three before it shuts. */
    private var runeCasts = 0
    private val RUNE_MAX = 3
    private var hasteT = 0f; private var bulwarkT = 0f
    private val tracing get() = (castRune >= 0 && bossHere) || runePromptT > 0f || battle?.wantsRune == true || wash != null
    private fun openRuneBook() {
        if (spellCd.all { it > 0f }) { say("EVERY RUNE IS RECHARGING", 1.4f); sfx.play(HallSfx.S.HURT, 0.3f); return }
        runePromptT = RUNE_TIME; runeCasts = 0; stroke.clear(); lastScore = -1f
        sfx.play(HallSfx.S.SPELL); sfx.rumbleSoft()
    }
    /** A traced rune: the best match among those ready, if it's good enough. */
    private fun runeStroke() {
        if (stroke.size < 4) { stroke.clear(); return }
        var best = -1; var bs = 0f
        for (k in 0 until 6) { if (spellCd[k] > 0f) continue; val sc0 = similarityTo(stroke, spellShapes[k], RuneShapes.closed(spellShapes[k])); if (sc0 > bs) { bs = sc0; best = k } }
        lastScore = bs; scoreT = 1.4f; stroke.clear()
        if (best < 0 || bs < NEED) { say("THE RUNE FIZZLES (${(bs * 100).toInt()}%)", 1.2f); sfx.play(HallSfx.S.HURT, 0.3f); return }
        spellCd[best] = spellCdMax[best]; runeCasts++
        castSpell(best)
        // the book stays open for up to three spells (each one buys a little more time)
        if (runeCasts >= RUNE_MAX || spellCd.all { it > 0f }) runePromptT = 0f
        else runePromptT = minOf(RUNE_TIME, runePromptT + 2.5f)
    }
    private fun castSpell(k: Int) {
        val px = hx + 8; val py = hy + 9
        val fk = friend
        when (k) {
            0 -> {   // HEX: every foe in the room weakened (no bite, wild aim) and slowed
                for (m in minions) if (m.room == roomId && !m.ally) { m.weak = 7f; m.slow = 7f }
                if (bigHere) bossWeak = maxOf(bossWeak, 5f)
                fxs += Fx(FX_HEX, px, py, 0.7f); flashT = 0.12f
                say("HEX! THEY WITHER", 1.4f); sfx.play(HallSfx.S.HEX)
            }
            1 -> {   // BIND: every foe held fast
                for (m in minions) if (m.room == roomId && !m.ally) { m.para = 3.5f; fxs += Fx(FX_BIND, m.x, m.y, 0.6f) }
                if (bigHere) { bossPara = maxOf(bossPara, 2f); fxs += Fx(FX_BIND, gx, gy - 20, 0.8f) }
                flashT = 0.1f; say("BIND! THEY CAN'T MOVE", 1.4f); sfx.play(HallSfx.S.BOSS_RUNE)
            }
            2 -> {   // STARFALL: a star on every foe
                var d = 0f
                for (m in minions) if (m.room == roomId && !m.ally) { stars += Star(m.x, m.y, m, d, false, 4); d += 0.07f }
                if (bigHere) for (j in 0 until 3) stars += Star(gx - 8 + j * 8, gy - 20 - j * 4, null, d + j * 0.1f, false, 8)
                say("STARFALL!", 1.4f); sfx.play(HallSfx.S.STARFALL); sfx.rumbleBig()
            }
            3 -> {   // HASTE: you and your companion quicker for a while
                hasteT = 9f; swiftT = maxOf(swiftT, 9f)
                fxs += Fx(FX_HASTE, px, py, 0.8f); eachComp { if (friendHere) fxs += Fx(FX_HASTE, frX, frY - 8, 0.8f) }
                say("HASTE!", 1.4f); sfx.play(HallSfx.S.SPRINT)
            }
            4 -> {   // BULWARK: you and your companion take half damage
                bulwarkT = 9f
                fxs += Fx(FX_AEGIS, px, py - 4, 0.6f); eachComp { if (friendHere) fxs += Fx(FX_AEGIS, frX, frY - 10, 0.6f) }
                say("BULWARK! HALF DAMAGE", 1.4f); sfx.play(HallSfx.S.STOMP, 0.7f)
            }
            else -> {   // MEND: a great heal for you both, and every ailment lifted
                hp = minOf(maxHp, hp + 60); poisonT = 0f; paraT = 0f; invertT = 0f
                fxs += Fx(FX_MEND, px, py, 1.2f)
                eachComp { val f2 = friend; if (f2 != null && friendHere) { frHp = minOf(f2.maxHp, frHp + f2.maxHp / 2); fxs += Fx(FX_MEND, frX, frY - 8, 1.2f) } }
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
        BRAMBLE("BRAMBLE", 450, 1.05f, 6, 2.8f, false, "THORN NOVA", 10f),
        PIP("PIP", 340, 1.2f, 6, 2.5f, true, "MOONFLASH", 10f),
        TOVE("TOVE", 410, 1.15f, 6, 2.6f, false, "FROST BREATH", 9f),
        GRUMBLE("GRUMBLE", 680, 0.9f, 8, 3.2f, false, "STONE AEGIS", 11f),
        EMBER("EMBER", 380, 1.15f, 7, 2.6f, false, "FLAME RING", 9f),
        SPROUT("SPROUT", 400, 1.25f, 5, 2.8f, false, "BLOOM FIELD", 12f),
    }
    /** Each companion's support: what its wall is made of, what its heal looks like. */
    private fun wallName(k: Friend) = when (k) { Friend.BRAMBLE -> "THORN HEDGE"; Friend.PIP -> "MOON VEIL"; Friend.TOVE -> "ICE WALL"; Friend.GRUMBLE -> "STONE WALL"; Friend.EMBER -> "FIRE WALL"; Friend.SPROUT -> "ROOT WALL" }
    private fun healName(k: Friend) = when (k) { Friend.BRAMBLE -> "HEALING SAP"; Friend.PIP -> "MOTE OF LIGHT"; Friend.TOVE -> "COOL MIST"; Friend.GRUMBLE -> "EARTH'S MEND"; Friend.EMBER -> "WARM EMBERS"; Friend.SPROUT -> "BLOSSOM DEW" }
    /** One companion's state. You travel with two; the code below works on "the current one" (cur). */
    private class Comp {
        var kind: Friend? = null; var wallCd = 4f; var healCd = 3f; var guardCd = 0f; var state = 0; var room = 3
        var x = 0f; var y = 0f; var hp = 0; var tx = 0f; var ty = 0f; var repick = 0f; var atk = 0f; var skill = 6f
        var hurt = 0f; var inv = 0f; var lunge = 0f; var lx = 0f; var ly = 0f; var face = 1; var step = 0f; var moving = false; var taunt = 0f
        /** Getting unstuck. */
        var stuck = 0f; var slx = 0f; var sly = 0f; var detT = 0f; var dtx = 0f; var dty = 0f
    }
    private val comps = arrayListOf(Comp(), Comp())
    private var cur = comps[0]
    /** Run something for each companion in turn (as "the current one"). */
    private inline fun eachComp(f: () -> Unit) { val keep = cur; for (c in comps) { if (c.kind == null) continue; cur = c; f() }; cur = keep }
    /** Make the companion nearest to (x, y), of those with you here, the current one. */
    private fun pickComp(x: Float, y: Float) {
        var best: Comp? = null; var bd = Float.MAX_VALUE
        for (c in comps) if (c.kind != null && c.state == 1 && c.room == roomId) { val d = hypot(c.x - x, c.y - y); if (d < bd) { bd = d; best = c } }
        if (best != null) cur = best
    }
    private var frWallCd: Float get() = cur.wallCd; set(v) { cur.wallCd = v }
    private var frHealCd: Float get() = cur.healCd; set(v) { cur.healCd = v }
    private var frGuardCd: Float get() = cur.guardCd; set(v) { cur.guardCd = v }
    /** The friend's size on screen (its 12 x 11 sprite, drawn twice as big). */
    private val FS = 1.5f
    private val STEP = 6f
    private var friend: Friend? get() = cur.kind; set(v) { cur.kind = v }
    /** 0 waiting to be found, 1 with you, 2 knocked out. */
    private var frState: Int get() = cur.state; set(v) { cur.state = v }
    private var frRoom: Int get() = cur.room; set(v) { cur.room = v }
    private var frX: Float get() = cur.x; set(v) { cur.x = v }
    private var frY: Float get() = cur.y; set(v) { cur.y = v }
    private var frHp: Int get() = cur.hp; set(v) { cur.hp = v }
    private var frTx: Float get() = cur.tx; set(v) { cur.tx = v }
    private var frTy: Float get() = cur.ty; set(v) { cur.ty = v }
    private var frRepick: Float get() = cur.repick; set(v) { cur.repick = v }
    private var frAtk: Float get() = cur.atk; set(v) { cur.atk = v }
    private var frSkill: Float get() = cur.skill; set(v) { cur.skill = v }
    private var frHurt: Float get() = cur.hurt; set(v) { cur.hurt = v }
    private var frInv: Float get() = cur.inv; set(v) { cur.inv = v }
    private var frLunge: Float get() = cur.lunge; set(v) { cur.lunge = v }
    private var frLx: Float get() = cur.lx; set(v) { cur.lx = v }
    private var frLy: Float get() = cur.ly; set(v) { cur.ly = v }
    private var frFace: Int get() = cur.face; set(v) { cur.face = v }
    private var frStep: Float get() = cur.step; set(v) { cur.step = v }
    private var frMoving: Boolean get() = cur.moving; set(v) { cur.moving = v }
    private var tauntT: Float get() = cur.taunt; set(v) { cur.taunt = v }
    private var shieldHits = 0; private var shieldT = 0f
    private var savedFriend2: Friend? = null
    private val friendHere get() = friend != null && frState == 1 && frRoom == roomId

    /** Your companion comes with you from the start of every match (the same one, once chosen). */
    private fun spawnFriend() {
        val all = Friend.values(); val k = savedFriend ?: all[rnd.nextInt(all.size)]
        savedFriend = k
        val k2 = savedFriend2?.takeIf { it != k } ?: all.filter { it != k }[rnd.nextInt(all.size - 1)]
        savedFriend2 = k2
        shieldHits = 0
        for ((i, kk) in listOf(k, k2).withIndex()) {
            cur = comps[i]
            friend = kk; frState = 1; frHp = kk.maxHp; frSkill = 6f + i * 3f; tauntT = 0f; frWallCd = 4f + i * 2f; frHealCd = 3f + i * 2f
            frRoom = roomId; placeFriendNear()
            fxs += Fx(FX_JOIN, frX, frY - 6, 0.8f)
        }
        cur = comps[0]
    }
    private fun frSolid(x: Float, y: Float): Boolean {
        if (x - 7 < 6 || x + 7 > WW - 6 || y - 4 < topY + 4 || y > WH - 4) return true
        if (friend?.flies == true) return false
        return solid(x - 7, y - 4, x + 7, y)
    }
    private fun frMove(tx: Float, ty: Float, sp: Float, dt: Float) {
        val c = cur
        var dx = tx - frX; var dy = ty - frY; val d = hypot(dx, dy)
        if (d < 1.5f) { frMoving = false; return }
        val st = minOf(d, sp * dt)
        var ux = dx / d; var uy = dy / d
        if (c.detT > 0f) { c.detT -= dt; ux = c.dtx; uy = c.dty }
        else if (friend?.flies != true && !clearPath(frX, frY, tx, ty, 1)) {
            // round the obstacle on the flow field (it leads toward you), then straight on
            steer(frX, frY, if (hypot(tx - fieldX, ty - fieldY) < 90f) fieldX else tx, if (hypot(tx - fieldX, ty - fieldY) < 90f) fieldY else ty, 1)
            ux = dir[0]; uy = dir[1]
        }
        val nx = frX + ux * st; val ny = frY + uy * st
        var moved = false
        if (!frSolid(nx, frY)) { frX = nx; moved = true }
        if (!frSolid(frX, ny)) { frY = ny; moved = true }
        if (abs(dx) > 0.5f) frFace = if (dx > 0) 1 else -1
        frMoving = moved
        if (hypot(frX - c.slx, frY - c.sly) < st * 0.3f) c.stuck += dt else c.stuck = 0f
        c.slx = frX; c.sly = frY
        if (c.stuck > 0.35f) {
            c.stuck = 0f
            val f1 = !frSolid(frX - uy * 8f, frY + ux * 8f); val f2 = !frSolid(frX + uy * 8f, frY - ux * 8f)
            val sgn = when { f1 && !f2 -> 1f; f2 && !f1 -> -1f; else -> if (rnd.nextBoolean()) 1f else -1f }
            c.dtx = -uy * sgn; c.dty = ux * sgn; c.detT = 0.5f + rnd.nextFloat() * 0.4f
        }
        if (moved) frStep += dt else frRepick = 0f
    }
    private fun frPatrolPoint(px: Float, py: Float, near: Float, far: Float) {
        for (i in 0 until 24) {
            val a = rnd.nextFloat() * 6.2832f; val d = near + rnd.nextFloat() * (far - near)
            val x = px + cos(a) * d; val y = py + sin(a) * d * 0.8f
            if (!frSolid(x, y) && !(bossHere && phase == Phase.FIGHT && hypot(x - bx, y - by) < 52f)) { frTx = x; frTy = y; return }
        }
        frTx = px; frTy = (py + 18f).coerceAtMost(WH - 8f)
    }
    private fun placeFriendNear() {
        val px = hx + 8; val py = hy + 13
        val sx = if (px < camX + W / 2f) 1f else -1f; val sy = if (py < camY + H / 2f + 10) 1f else -1f
        for (r in intArrayOf(16, 22, 28, 10)) {
            val x = px + sx * r; val y = py + sy * r * 0.4f
            if (!frSolid(x, y)) { frX = x; frY = y; frTx = x; frTy = y; return }
        }
        frX = px.coerceIn(14f, WW - 14f); frY = py.coerceIn(topY + 8f, WH - 8f); frTx = frX; frTy = frY
    }
    private fun hurtFriend(n: Int) {
        val k = friend ?: return
        if (frState != 1 || frInv > 0f || phase != Phase.FIGHT) return
        val n5 = kotlin.math.ceil(n * 0.5f).toInt()      // companions shrug off half of everything
        frHp -= if (bulwarkT > 0f) (n5 + 1) / 2 else n5; frInv = 0.8f; frHurt = 0.3f
        sfx.play(HallSfx.S.FRIEND_HURT, 0.7f)
        if (frHp <= 0) { frHp = 0; frState = 2; say("${k.label} IS KNOCKED OUT!", 2.4f); sfx.play(HallSfx.S.FRIEND_DOWN); sfx.rumbleSoft() }
    }
    private fun reviveFriend() {
        // the one that's down (here, if one is)
        comps.firstOrNull { it.kind != null && it.state == 2 && it.room == roomId }?.let { cur = it } ?: comps.firstOrNull { it.kind != null && it.state == 2 }?.let { cur = it }
        reviveCur(); cur = comps[0]
    }
    private fun reviveCur() {
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
        if (shieldT > 0f) { shieldT -= dt; if (shieldT <= 0f) shieldHits = 0 }
        for (c in comps) { if (c.kind == null) continue; cur = c; updateOneFriend(dt) }
        cur = comps[0]
    }
    private fun updateOneFriend(dt: Float) {
        val k = friend ?: return
        frHurt -= dt; frInv -= dt; frLunge -= dt; tauntT -= dt; frAtk -= dt * (if (hasteT > 0f) 1.6f else 1f); frWallCd -= dt; frHealCd -= dt; frGuardCd -= dt
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
        val bossNear = fighting && bigHere && hypot(frX - gx, frY - gy) < 50f
        var target: Minion? = null; var td = Float.MAX_VALUE
        if (fighting) for (m in minions) if (m.room == roomId && m.kind != 2 && !m.ally) { val d = hypot(m.x - frX, m.y - frY); if (d < td) { td = d; target = m } }
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
                    hitImp(t, maxOf(2, k.dmg * 3 / 5)); bursts += Burst(t.x, t.y); fxs += Fx(FX_STRIKE, t.x, t.y - 2, 0.35f, frFace.toFloat())
                    t.kx = (t.x - frX) * 6f; t.ky = (t.y - frY) * 6f; t.stun = 0.5f
                    sfx.play(HallSfx.S.STOMP, 0.5f); shakeT = maxOf(shakeT, 0.08f)
                    killDead()
                }
            }
            // all quiet: fall in, in a line behind you
            calm() -> {
                val (sx, sy) = lineSlot(comps.indexOf(cur), false, null)
                frMove(sx, sy, sp * (if (hypot(sx - frX, sy - frY) > 30f) 1.4f else 0.8f), dt)
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
        if (frHealCd <= 0f && (hp < maxHp - 8 || frHp < k.maxHp * 0.7f || minions.any { it.ally && it.room == roomId && it.hp in 1 until it.maxHp })) {
            frHealCd = 14f
            if (hp < maxHp - 8) { hp = minOf(maxHp, hp + 8); beams += Beam(frX, frY - 8, false); say("${healName(k)} +8", 1f) }
            if (frHp < k.maxHp) { frHp = minOf(k.maxHp, frHp + 10); fxs += Fx(FX_MEND, frX, frY - 8, 0.7f) }
            sfx.play(HallSfx.S.ORB, 0.6f)
        }
        // and the hurt thralls near it, a stream of light to each
        if (frHealCd > 3.5f) for (m in minions) if (m.ally && m.room == roomId && m.hp in 1 until m.maxHp && hypot(m.x - frX, m.y - frY) < 70f) {
            m.hp = minOf(m.maxHp, m.hp + 8); arcs += Arc(frX, frY - 8, m.x, m.y - 6); fxs += Fx(FX_MEND, m.x, m.y - 6, 0.5f)
        }
        // a wall between you and the nearest danger
        if (frWallCd <= 0f) {
            var tx = 0f; var ty = 0f; var td = 64f
            for (m in minions) if (m.room == roomId && !m.ally && m.para <= 0f && m.ice <= 0f) { val d = hypot(m.x - px, m.y - py); if (d < td) { td = d; tx = m.x; ty = m.y } }
            if (bossHere && stage == Stage.CHASE) { val d = hypot(bx - px, by - 6 - py); if (d < 80f && d < td + 20f) { td = d; tx = bx; ty = by - 6 } }
            for (sh in shots) { val d = hypot(sh.x - px, sh.y - py); if (d < 56f && d < td) { td = d; tx = sh.x; ty = sh.y } }
            if (td < 64f) {
                frWallCd = 4f
                val d = td.coerceAtLeast(1f); val ux = (tx - px) / d; val uy = (ty - py) / d
                val cx = px + ux * minOf(18f, d * 0.6f); val cy = py + uy * minOf(18f, d * 0.6f)
                val nx = -uy; val ny = ux; val half = 16f
                val seg = FloatArray(10) { i -> val t = (i / 2) / 4f * 2f - 1f; if (i % 2 == 0) cx + nx * half * t + ux * (1 - t * t) * 3f else cy + ny * half * t + uy * (1 - t * t) * 3f }
                val style = when (k) { Friend.BRAMBLE -> 1; Friend.PIP -> 2; Friend.TOVE -> 3; Friend.GRUMBLE -> 4; Friend.EMBER -> 5; Friend.SPROUT -> 6 }
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
        val bossClose = bossHere && stage == Stage.CHASE && hypot(bx - px, by - py) < 70f
        return when (k) {
            Friend.PIP -> near >= 2 || onYou || shots.count { hypot(it.x - px, it.y - py) < 70f } >= 2 || bossClose
            Friend.GRUMBLE -> (hp < maxHp * 0.6f && (near >= 1 || onYou || bossClose)) || near >= 3
            Friend.SPROUT -> hp < maxHp * 0.7f || near >= 2 || onYou
            else -> near >= 2 || onYou || bossClose
        }
    }
    private fun wrapAngle(a: Float): Float { var d = a; while (d > 3.1416f) d -= 6.2832f; while (d < -3.1416f) d += 6.2832f; return d }
    private fun useSkill(k: Friend, px: Float, py: Float) {
        frSkill = k.skillCd
        sfx.play(HallSfx.S.FRIEND_SKILL)
        fxs += Fx(FX_JOIN, frX, frY - 8, 0.7f); flashT = maxOf(flashT, 0.08f)
        say("${k.label}: ${k.skill}!", 1.2f)
        when (k) {
            Friend.BRAMBLE -> {
                fxs += Fx(FX_NOVA, frX, frY - 4, 0.55f); shakeT = 0.15f
                for (m in minions) if (m.room == roomId && !m.ally) {
                    val dx = m.x - frX; val dy = m.y - frY; val d = hypot(dx, dy)
                    if (d < 42f) { hitImp(m, 2, true); m.stun = 1.2f; val dd = d.coerceAtLeast(1f); m.kx = dx / dd * 140f; m.ky = dy / dd * 140f }
                }
                if (bigHere && hypot(gx - frX, gy - frY) < 48f) damageBoss(6)
            }
            Friend.PIP -> {
                fxs += Fx(FX_MOON, frX, frY - 10, 0.6f); flashT = 0.25f
                shots.removeAll { s0 -> (hypot(s0.x - frX, s0.y - frY) < 100f).also { if (it) bursts += Burst(s0.x, s0.y) } }
                for (m in minions) if (m.room == roomId && !m.ally && hypot(m.x - frX, m.y - frY) < 85f) m.conf = 4f
                if (bigHere && hypot(gx - frX, gy - frY) < 85f) bossConf = maxOf(bossConf, 2f)
            }
            Friend.TOVE -> {
                val near = minions.filter { it.room == roomId }.minByOrNull { hypot(it.x - frX, it.y - frY) }
                val a = when {
                    near != null -> atan2(near.y - frY, near.x - frX)
                    bossHere && stage == Stage.CHASE -> atan2(by - 8 - frY, bx - frX)
                    else -> if (frFace > 0) 0f else 3.1416f
                }
                frFace = if (cos(a) >= 0f) 1 else -1
                fxs += Fx(FX_FROST, frX + frFace * 4, frY - 6, 0.7f, a)
                for (m in minions) if (m.room == roomId && !m.ally) {
                    val dx = m.x - frX; val dy = m.y - frY
                    if (hypot(dx, dy) < 60f && abs(wrapAngle(atan2(dy, dx) - a)) < 0.55f) { m.ice = 2.6f; hitImp(m, 1, true) }
                }
                if (bigHere) {
                    val dx = gx - frX; val dy = gy - 10 - frY
                    if (hypot(dx, dy) < 64f && abs(wrapAngle(atan2(dy, dx) - a)) < 0.6f) { damageBoss(4); bossPara = maxOf(bossPara, 0.8f) }
                }
                sfx.play(HallSfx.S.SHATTER, 0.5f)
            }
            Friend.GRUMBLE -> {
                shieldHits = 2; shieldT = 10f; tauntT = 4f
                fxs += Fx(FX_AEGIS, px, py - 4, 0.6f); shakeT = 0.12f
                sfx.play(HallSfx.S.STOMP, 0.8f)
            }
            Friend.EMBER -> {
                // a ring of fire rolls out from it: everything close is set alight
                fxs += Fx(FX_NOVA, frX, frY - 4, 0.55f); fxs += Fx(FX_WALL, frX, frY, 0.6f, 5f); shakeT = 0.15f
                for (m in minions) if (m.room == roomId && !m.ally && hypot(m.x - frX, m.y - frY) < 54f) { hitImp(m, 3, true); m.burn = 3.5f; m.burnTick = 0.5f; bursts += Burst(m.x, m.y) }
                if (bigHere && hypot(gx - frX, gy - frY) < 60f) damageBoss(6)
                sfx.play(HallSfx.S.THUNDER, 0.4f)
            }
            Friend.SPROUT -> {
                // a field of blossoms: you and every friend mended, foes in it tangled and slowed
                hp = minOf(maxHp, hp + 30); beams += Beam(frX, frY - 8, false)
                for (m in minions) if (m.room == roomId) {
                    if (m.ally) m.hp = minOf(m.maxHp, m.hp + 20)
                    else if (hypot(m.x - frX, m.y - frY) < 64f) { m.slow = maxOf(m.slow, 5f); m.para = maxOf(m.para, 1.2f); fxs += Fx(FX_BIND, m.x, m.y, 0.5f) }
                }
                for (c in comps) { val ck = c.kind; if (ck != null && c !== cur && c.hp > 0) c.hp = minOf(ck.maxHp, c.hp + 40) }
                fxs += Fx(FX_MEND, frX, frY - 4, 0.8f); fxs += Fx(FX_WALL, frX, frY, 0.7f, 6f)
                sfx.play(HallSfx.S.ORB, 0.7f)
            }
        }
        killDead()
    }

    // ---- the companions: original 12×11 critters, two frames each, facing right
    /** The companions, 24 x 22 in the monster style: Bramble, Pip, Tove, Grumble; two frames each. */
    private val friendPal = mapOf('K' to 0xFF16121A.toInt(), 'W' to 0xFFFFFFFF.toInt(), 'Y' to 0xFFFFDC46.toInt(), 'y' to 0xFFC88C1E.toInt(), 'N' to 0xFF281814.toInt(), 'a' to 0xFF1A4622.toInt(), 'b' to 0xFF2E6E2E.toInt(), 'c' to 0xFF509A3A.toInt(), 'd' to 0xFF96CE60.toInt(), 'e' to 0xFF462C1C.toInt(), 'f' to 0xFF744C2C.toInt(), 'g' to 0xFFA47446.toInt(), 'h' to 0xFFD0A670.toInt(), 'i' to 0xFF788096.toInt(), 'j' to 0xFFB0BACE.toInt(), 'k' to 0xFFDEE6F4.toInt(), 'l' to 0xFFFFFFFF.toInt(), 'm' to 0xFF3A4042.toInt(), 'n' to 0xFF606864.toInt(), 'o' to 0xFF8C948A.toInt(), 'p' to 0xFFBCC2B4.toInt(), 'q' to 0xFF2878A0.toInt(), 'r' to 0xFF5ABEE6.toInt(), 's' to 0xFFAAECFF.toInt(), 't' to 0xFF781814.toInt(), 'u' to 0xFFCC3C1C.toInt(), 'v' to 0xFFF48428.toInt(), 'w' to 0xFFFFD65C.toInt(), 'C' to 0xFF96325A.toInt(), 'D' to 0xFFD65C8C.toInt(), 'E' to 0xFFF6A0C0.toInt(), 'F' to 0xFFFFE0EC.toInt(), 'B' to 0xFFC82832.toInt(), 'T' to 0xFFECE2C4.toInt(), 'G' to 0xFF46823C.toInt(), 'P' to 0xFFFAF0DC.toInt(), 'L' to 0xFFC4D660.toInt(), 'M' to 0xFF6E9634.toInt())
    private val friendRows = listOf(
        listOf(listOf("....KhhK..K........KhK....", ".....KgK.KgKKKK...KggK....", ".....KggKKeddccKK.KgKK....", ".....KggKddddccbbKKgKgK...", "......KgeTddccdbTKgKggK...", "...KKKKedddccccbbaKeeeK...", "..KdddccccccccbbadddccbK..", ".KdddcdcccccbbbadddcdcbaK.", ".KcdcccbbbbbBBaacdcccbTaK.", ".KccccbbaabaaaaaaaccbbaaK.", ".KbbbbbaaaTahhhggfabbaaaK.", "..KTaaaaaaahhhhgggfBBaaK..", ".KddccaBDahheegggeeeadccK.", "KdddccbacahhYKgggYKeadccbK", "KdcdcbbacaggggggffeeaccdbK", "KcccbbaacaggggffffeeacbbaK", "KbbbaaaacafffNNNNNeeabaaaK", ".KaaaaabbbafffTeTeeaBDaaK.", "..KKKTbbbbbaeeeeeeaaaTKK..", "....KbbbbBBbaaaaaaaaaK....", ".....KKaaaaaaaaaaaaKK.....", ".....KheaaaaaaaaaaeeK.....", ".....KggeeeeKKeeeeeeK.....", "......KeeeeK..KeeeeK......"), listOf("....KhhK..K........KhK....", ".....KgK.KgKKKK...KggK....", ".....KggKKeddccKK.KgKK....", ".....KggKddddccbbKKgKgK...", "......KgeTddccdbTKgKggK...", "...KKKKedddccccbbaKeeeK...", "..KdddccccccccbbadddccbK..", ".KdddcdcccccbbbadddcdcbaK.", ".KcdcccbbbbbBBaacdcccbTaK.", ".KccccbbaabaaaaaaaccbbaaK.", ".KbbbbbaaaTahhhggfabbaaaK.", "..KTaaaaaaahhhhgggfBBaaK..", ".KddccaBDahheegggeeeadccK.", "KdddccbacahhYKgggYKeadccbK", "KdcdcbbacaggggggffeeaccdbK", "KcccbbaacaggggffffeeacbbaK", "KbbbaaaacafffNNNNNeeabaaaK", ".KaaaaabbbafffTeTeeaBDaaK.", "..KKKTbbbbbaeeeeeeaaaTKK..", "....KbbbbBBbaaaaaaaaaK....", ".....KKaaaaaaaaaaaaKK.....", "......KeaaaaaaaaaaeK......", "......KgeeeeeeeeeeeK......", ".......KeeeeKKeeeeK.......")),
        listOf(listOf("......s............Ks.....", "K....KgK..........KgK....K", "FK....KgK........KgK....KF", "FFK...KggK......KggK...KFF", "EEEK..KgggKKKKKKgggK..KEEK", "KEEYY..KgKKhgggKggK..KYYEK", "KEEKYK..KhhhhgggegK.KEKYEK", "KEEEEEKKhhhhhgggfeKKEEEEEK", "KEEEEEEKhhhhhgggffKEEEEEEK", ".KEEEEChhWYYgggWYYeCEEEEK.", ".KEEEEChhYKYgggYKYeCEEEEK.", "..KEEEKhhYKYeeeYKYeKEEEK..", "...KKKKgggePyyTefeeKKKK...", ".....KKggePPPyTheeeKK.....", "....KgegePPPPTThheeegK....", "...KggKgePPTTThgheeKggK...", "..KfKKKfeTTgTThhheeKKKfK..", ".KfK...KeTTThhghheK...KfK.", "KfK....KehhhghhhheK....KfK", ".K......KehhhghheK......K.", ".........KKhhhhKK.........", "...........KKKK...........", "...........yy.yy..........", ".........................."), listOf("......s............Ks.....", ".....KgK..........KgK.....", "......KgK........KgK......", "......KggK......KggK......", "......KgggKKKKKKgggK......", ".......KgKKhgggKggK.......", "........KhhhhgggegK.......", ".......KhhhhhgggfeK.......", ".......KhhhhhgggffK.......", "......KhhWYYgggWYYeK......", "....KKKhhYKYgggYKYeKKK....", "...KEEKhhYKYeeeYKYeCEEK...", "..KEEECgggePyyTefeeCEEEK..", ".KEEEECggePPPyTheeeCEEEEK.", "KEEEEEKgePPPPTThheeCEEEEEK", "KDDDDgegePPTTThgheeegDDDDK", "KDDYYgefeTTgTThhheeeggYYDK", "KDDKYfKKeTTThhghheKKffKYDK", "DDKKfK.KehhhghhhheKKffKDDD", "DKKffK..KehhhghheK..KffKDD", "K.KfK....KKhhhhKK....KfKKD", "...K.......KKKK.......K..K", "...........yy.yy..........", "..........................")),
        listOf(listOf("..KKKKKK..........K.....K...", ".KsslssrK........KkK...KkK..", ".KssssrlK........KkK..KkqK..", ".KsssrrqK........KkqK.KkkK..", ".KssrrqqK........KkkkKkkkK..", "KsssrqqqjK.......KkkilikkK..", "KssrqkkjjK.......KkilkkikK..", "KlqqkkjjiK......KlilkkkjiiK.", "KkkkkkjjiK..KKKKKklkksqKjiK.", "KkkkkjjjiKKKlllriiikkqKjikK.", "KkkkjjjiiKllrllssssijiiilkjK", "KjjjjjiillllslkkissiissikjKK", "KjjjjiikllrllkkkissiisiiKKK.", ".KjjiiikklskkkkkirriiiiKWK..", "..KiiiikkkkkkrkjjiiiiiiK....", "...KKKKjkkkiisjjjriiiiiK....", ".......KjjilkijjisilkiK.....", ".......KiiilkiiiiiilkK......", "......KkkikkjiiiiikkjiK.....", "......KkkikkjiKiiikkjiK.....", ".......KjiKjiK.KjiKjiK......", ".......KiqKiqK.KiqKiqK......"), listOf("..KKKKKK..........K.....K...", ".KsslssrK........KkK...KkK..", ".KssssrlK........KkK..KkqK..", ".KsssrrqK........KkqK.KkkK..", ".KssrrqqK........KkkkKkkkK..", "KsssrqqqjK.......KkkilikkK..", "KssrqkkjjK.......KkilkkikK..", "KlqqkkjjiK......KlilkkkjiiK.", "KkkkkkjjiK..KKKKKklkksqKjiK.", "KkkkkjjjiKKKlllriiikkqKjikK.", "KkkkjjjiiKllrllssssijiiilkjK", "KjjjjjiillllslkkissiissikjKK", "KjjjjiikllrllkkkissiisiiKKK.", ".KjjiiikklskkkkkirriiiiKWK..", "..KiiiikkkkkkrkjjiiiiiiK....", "...KKKKjkkkijsjjjriiiiiK....", ".......KjjikijjjisikiiK.....", ".......KiilkjiiiiilkjK......", ".......KlilkjiiiiilkjK......", ".......KkikjiKKKiikjiK......", ".......KjijjiK.KjijjiK......", "........KiqqK...KiqqK.......")),
        listOf(listOf(".......KccccccK.......", "......KcccccccK.......", "......KcmmmmmmdK......", "......KcmmmmmmcK......", ".....KmdmooooomKE.....", "......KmoooooooK......", "......KmmsrmrsmK......", ".KKKKKmnnqnnnqnK.KKKK.", "KmcdcmomdmmmmmmoKccdmK", "KommmmpooooooooommmmoK", "KoooompooooroomomooooK", ".KKKKopmooorooomoKKKK.", "..KKKmpcmnrqrnmnnKKK..", ".KooompcmnnrnnnnmoooK.", ".KooompcmnnrnnnnmoooK.", "..KKKKbpmnnqnnnnKKKK..", ".KKKKKmpmnrrrnmmKKKKK.", "KooooKnpnnnrnmccKooooK", "KooooKnpnnnqnmccKooooK", ".KKKKKnpmnnrnmccKKKKK.", "KooooKnnnmrrrnmaoooooK", "KooooomnnnnqnnnmoooooK", "KoooooKmmmmmmmmKoooooK", "KnnnnEKmmmmmmmmKnnnnnK", "KnnnnnKommmmmmoKnnnnnK", ".KnnnKKnnnnnnnnKKnnnK.", "..KKK.KnnnKKnnnK.KKK..", "......KnnnKKnnnK......", "......KnnnKKnnnK......", ".....KKnnnKKnnnKK.....", "....KooooooooooooK....", "....KooooooooooooK....", "....KooooooooooooK....", ".....KKKKKKKKKKKK....."), listOf(".......KccccccK.......", "......KcccccccK.......", "......KcmmmmmmdK......", "......KcmmmmmmcK......", ".....KmdmooooomKE.....", "......KmoooooooK......", "......KmmsrmrsmK......", ".KKKKKmnnqnnnqnK.KKKK.", "KmcdcmomdmmmmmmoKccdmK", "KommmmpooooooooommmmoK", "KoooompooooroomomooooK", ".KKKKopmooorooomoKKKK.", "..KKKmpcmnrqrnmnnKKK..", ".KooompcmnnrnnnnmoooK.", ".KooompcmnnrnnnnmoooK.", "..KKKKbpmnnqnnnnKKKK..", ".KKKKKmpmnrrrnmmKKKKK.", "KooooKnpnnnrnmccKooooK", "KooooKnpnnnqnmccKooooK", "KKKKKKnpmnnrnmccKKKKK.", "oooooKnnnmrrrnmbKooooK", "oooooKnnnnnqnnnmKooooo", "oooooKKmmmmmmmmKKooooo", "nnnnnEKmmmmmmmmKKnnnnn", "nnnnnKKommmmmmoKKnnnnK", "KnnnK.KnnnnnnnnKKnnnK.", ".KKK..KnnnKKnnnK.KKK..", "......KnnnKKnnnK......", "......KnnnKKnnnK......", ".....KKnnnKKnnnKK.....", "....KooooooooooooK....", "....KooooooooooooK....", "....KoooooonnnnnnK....", ".....KKKKKKKKKKKK.....")),
        listOf(listOf("..............................", "..................K...........", "............K....KwK.K........", "...........KwK.K.KwKKwK.......", "...........KwKKwKKwKKwK.......", ".........K.KwKKwKKwwKwKKK.....", "........KwKKwwKwKKwwKwwtvKK...", "........KwKKwwKwwKwwwwwtvvuK..", "........KwwKwwwwwKKttttvvuutK.", "........KwwttttttvtvwvvvWKutK.", "..KKKK..KttwwwwvvvtvvvvuYKttw.", ".KwwvuKKwwwwwwvvtwtuuuuuuttKK.", ".vwvvuKvwwvvtwttttutwutttttt..", "wKvuuttvvtwtwwwwwwttttwttKK...", ".KttttvttwwwwwtwwvvvvtKKK.....", "..KKKKvttwwwwvvvvvvvvtvK......", "......KwvtttvvvvvvtttvvtK.....", "......KvvutvtttKttttvvutK.....", ".......KutKKutK.KutKKutK......", "........KK..KK...KK..KK......."), listOf("..............................", "......................K.......", "................K....KwK......", "..........K....KwK.K.KwK......", ".........KwK.K.KwKKwKKwK......", ".........KwKKwKKwKKwKwwKK.....", ".........KwKKwKwwKwwKwwtvKK...", "........KwwKwwKwwKwwtwwwtvuK..", "........KwwKwwKwwwKtwtttvuutK.", "........KwwtttwttttvwvvvWKutK.", "..KKKK..KttwwwwvvvtvvvvuYKttv.", ".KwwvuKKwwwwwwvvtwtuuuuuuttKK.", ".KwvvuKvwwvvtwttttutwutttttt..", ".wvuuttvvtwtwwwwwwttttwttKK...", ".vttttvttwwwwwtwwvvvvtKKK.....", "..KKKKvttwwwwvvvvvvvvtuK......", "......KKwtttvvvvvvtttvuK......", ".......KvutvttKKKtttvutK......", ".......KuttuttK.KuttuttK......", "........KKKKKK...KKKKKK.......")),
        listOf(listOf("..............KFFDK.KFEK..", "..............KEYDKKKDYK..", "..............KCCCdcKeK...", "...............KKKbaeK....", ".................KeegK....", "................KKKgeKK...", "...............KggKKhggKK.", "................KghhhggfeK", "...............KhehhggKWeK", "...............KhegggfKeeK", "..............KgheffffeeeN", "..............KgggeeeeeKK.", "............KK.KgfffeeK...", ".....K..KKKKggKKfffeeeK...", "....KPKKhhhhhgggeeeeeK....", "...KPPThhhhhhgPgfeeKK.....", "...KPTThhhhPggggfffeK.....", "....KTghhgPPPTThPfeeK.....", ".....KggPPPPTTThhheeK.....", ".....KfgTTTTPThhhheeK.....", "......KehhhhhhhhhheK......", "......KhgehhhhhhgeeK......", "......KhfeeKeeKhfefK......", "......KgfefKKKKgfefK......", "......KgfefK..KgfefK......", "......KfeeeK..KfeeeK......", "......KeeeeK..KeeeeK......", ".......dKKK....KKdK......."), listOf("..............KFFDK.KFEK..", "..............KEYDKKKDYK..", "..............KCCCdcKeK...", "...............KKKbaeK....", ".................KeegK....", "................KKKgeKK...", "...............KggKKhggKK.", "................KghhhggfeK", "...............KhehhggKWeK", "...............KhegggfKeeK", "..............KgheffffeeeN", "..............KgggeeeeeKK.", "............KK.KgfffeeK...", ".....K..KKKKggKKfffeeeK...", "....KPKKhhhhhgggeeeeeK....", "...KPPThhhhhhgPgfeeKK.....", "...KPTThhhhPggggfffeK.....", "....KTghhgPPPTThPfeeK.....", ".....KggPPPPTTThhheeK.....", ".....KfgTTTTPThhhheeK.....", "......KfhhhhhhhhhheK......", ".......KhehhhhhhheK.......", ".......KgeKKeeKKgeK.......", ".......KgeK.KK.KgeK.......", ".......KgeK....KgeK.......", ".......KfeK....KfeK.......", ".......KeeK....KeeK.......", "........dK......dK........"))
    )
    private val friendSprites = friendRows.map { frames -> frames.map { Sprite(it, friendPal, outline = null) } }
    private val friendFlash = friendRows.map { frames -> frames.map { rows -> Sprite(rows.map { r -> r.map { ch -> if (ch == '.') '.' else 'W' }.joinToString("") }, friendPal, outline = null) } }

    private fun hasDoor(d: Int): Boolean {
        val wl = wild
        if (wl != null) return room.doors.contains("WENS"[d]) && (ry * wl.w + rx) * 4 + d !in wl.locked
        return room.doors.contains("WENS"[d]) || (d == 2 && throne && crackOpen && (phase == Phase.WON || chapter == 2))
    }
    /** A gate on this side (the way is there, but locked). */
    private fun gateAt(d: Int): Boolean { val wl = wild ?: return false; return room.doors.contains("WENS"[d]) && (ry * wl.w + rx) * 4 + d in wl.locked }

    /** Is this box of floor taken? (pools only stop feet, not shots) */
    private fun solid(l: Float, t: Float, r: Float, b: Float, walk: Boolean = true): Boolean {
        val wl = wild
        if (wl != null) {
            for (g in gates) if (r > g.r.left && l < g.r.right && b > g.r.top && t < g.r.bottom) return true
            val c0 = (l / 240f).toInt().coerceIn(0, wl.w - 1); val c1 = (r / 240f).toInt().coerceIn(0, wl.w - 1)
            val r0 = (t / 176f).toInt().coerceIn(0, wl.h - 1); val r1 = (b / 176f).toInt().coerceIn(0, wl.h - 1)
            for (cy in r0..r1) for (cx in c0..c1) {
                val k = cy * wl.w + cx
                // the deep wood, rock and water of the ground grid (shots fly over water)
                run {
                    val ms = wl.mass[k]; val ox = cx * 240f; val oy = cy * 176f
                    val g0 = ((l - ox) / 6f).toInt().coerceIn(0, 39); val g1 = ((r - 0.01f - ox) / 6f).toInt().coerceIn(0, 39)
                    val h0 = ((t - oy) / 5f).toInt().coerceIn(0, 34); val h1 = ((b - 0.01f - oy) / 5f).toInt().coerceIn(0, 34)
                    if (r > ox && l < ox + 240f && b > oy && t < oy + 176f) for (gy in h0..h1) for (gx in g0..g1) { val m = ms[gy * 40 + gx].toInt(); if (m == 1 || m == 2 || (m == 3 && walk)) return true }
                }
                for (pp in chunkProps[k]) { if (pp.kind == PLAZA || pp.kind == SHRUB || pp.kind == MUSHROOM || pp.kind == FLOWERBED) continue; val f = pp.foot; if (r > f.left && l < f.right && t < f.bottom && b > f.top) return true }
                if (walk) {
                    for (w in chunkPonds[k]) if (ovalHit(w, l, t, r, b)) return true
                    for (w in chunkPools[k]) if (r > w.left + 2 && l < w.right - 2 && t < w.bottom - 2 && b > w.top + 2) return true
                }
            }
            return false
        }
        if (phase != Phase.WON && chapter == 1 && r > dais.left + 4 && l < dais.right - 4 && t < dais.bottom - 2 && b > dais.top) return true
        for (w in hallWalls) if (r > w.left && l < w.right && b > w.top && t < w.bottom) return true
        if (!crackPassable && r > crackWall.left && l < crackWall.right && b > crackWall.top && t < crackWall.bottom) return true
        // the empty corners of the grid (if any): solid
        if (rooms[((((t + b) / 2) / 176f).toInt().coerceIn(0, 2)) * 3 + (((l + r) / 2) / 240f).toInt().coerceIn(0, 2)] == null) return true
        for (pp in room.props) { if (pp.kind == SHRUB) continue; val f = pp.foot; if (r > f.left && l < f.right && t < f.bottom && b > f.top) return true }
        if (walk) for (w in room.pools) if (r > w.left + 2 && l < w.right - 2 && t < w.bottom - 2 && b > w.top + 2) return true
        return false
    }

    /** Does this box touch the oval inscribed in [w] (a lobe of a pond, its shore inset a little)? */
    private fun ovalHit(w: RectF, l: Float, t: Float, r: Float, b: Float): Boolean {
        val cx = w.centerX(); val cy = w.centerY(); val rx = w.width() / 2 - 2f; val ry = w.height() / 2 - 2f
        if (rx <= 0f || ry <= 0f) return false
        val qx = cx.coerceIn(l, r); val qy = cy.coerceIn(t, b)
        val dx = (qx - cx) / rx; val dy = (qy - cy) / ry
        return dx * dx + dy * dy < 1f
    }
    private fun blocked(x: Float, y: Float): Boolean {
        val l = x + 3; val r = x + 13; val t = y + 10; val b = y + 15
        return l < 4 || r > WW - 4 || t < 4 || b > WH - 3 || solid(l, t, r, b)
    }
    private fun impSolid(x: Float, y: Float) = foeSolid(x, y, 3f)
    private fun foeSolid(x: Float, y: Float, r: Float) = x - r < 4 || x + r > WW - 4 || y - 3 < topY || y + 3 > WH - 3 || solid(x - r, y - 3, x + r, y + 3)
    private fun bossSolid(x: Float, y: Float) = x - 10 < 4 || x + 10 > WW - 4 || y - 6 < topY || y > WH - 3 || solid(x - 10, y - 6, x + 10, y)
    private fun shotStopped(x: Float, y: Float): Boolean {
        val wl = wild ?: return room.props.any { it.body.contains(x, y) }
        val cx = (x / 240f).toInt().coerceIn(0, wl.w - 1); val cy = ((y + 40f) / 176f).toInt().coerceIn(0, wl.h - 1)
        val my = ((y + 6f) / 176f).toInt().coerceIn(0, wl.h - 1)
        val m = wl.massAt(my * wl.w + cx, x - cx * 240f, y + 6f - my * 176f)
        return m == 1 || m == 2 || chunkProps[cy * wl.w + cx].any { it.body.contains(x, y) } || gates.any { it.r.contains(x, y) }
    }

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
    /**
     * A sealed behemoth: a closed circle round a dazed one holds it for a moment. Strike a line
     * through the circle (or through him) and he's banished; leave it, and with the Runecaster
     * the brainwashing begins; without it, the seal fades.
     */
    private class Seal(val m: Minion, val pts: FloatArray) { var t = SEAL_TIME }
    private var seal: Seal? = null
    /** Did this stroke cut through the seal: across its ring twice, or right past him? */
    private fun cutsSeal(sl: Seal, q: FloatArray): Boolean {
        val n = q.size / 2; if (n < 2) return false
        var crossings = 0; var near = Float.MAX_VALUE
        for (i in 0 until n - 1) {
            val ax = q[i * 2]; val ay = q[i * 2 + 1]; val bx2 = q[i * 2 + 2]; val by2 = q[i * 2 + 3]
            var j = 0
            while (j + 3 < sl.pts.size) { if (cross(ax, ay, bx2, by2, sl.pts[j], sl.pts[j + 1], sl.pts[j + 2], sl.pts[j + 3])) crossings++; j += 2 }
            // how close the stroke passes to him
            val vx = bx2 - ax; val vy = by2 - ay; val ll = (vx * vx + vy * vy).coerceAtLeast(0.01f)
            val tt = (((sl.m.x - ax) * vx + (sl.m.y - 10 - ay) * vy) / ll).coerceIn(0f, 1f)
            near = minOf(near, hypot(ax + vx * tt - sl.m.x, ay + vy * tt - (sl.m.y - 10)))
        }
        return crossings >= 2 || (crossings >= 1 && near < 14f) || near < 8f
    }
    private fun updateSeal(dt: Float) {
        val sl = seal ?: return
        val m = sl.m
        if (m !in minions || m.room != roomId || m.daze <= 0f || m.ally) { seal = null; return }
        if (drawing.isNotEmpty()) return            // still drawing: the moment waits for you
        sl.t -= dt
        if (sl.t <= 0f) {
            seal = null
            if (bag.wearing(Loot.RUNECASTER) && wash == null) beginWash(m)
            else { wards.removeAll { it.pts === sl.pts }; say("THE SEAL FADES. CIRCLE HIM, THEN STRIKE THROUGH!", 1.8f) }
        }
    }

    private fun finishWard() {
        val n = drawing.size / 2
        // Fury: a circle marks them; a stroke through it cuts them down
        if (furyT > 0f && n >= 2) {
            val ring = furyRing
            if (ring != null && furyRingT > 0f) {
                var crossings = 0; var i = 0
                while (i + 3 < drawing.size) { var j = 0; while (j + 3 < ring.size) { if (cross(drawing[i], drawing[i + 1], drawing[i + 2], drawing[i + 3], ring[j], ring[j + 1], ring[j + 2], ring[j + 3])) crossings++; j += 2 }; i += 2 }
                if (crossings >= 2) {
                    var cut = 0
                    for (m in minions) if (m.room == roomId && !m.ally && m.hp > 0 && inside(ring, m.x, m.y)) { hitImp(m, 14); bursts += Burst(m.x, m.y - 4); fxs += Fx(FX_STRIKE, m.x, m.y - 4, 0.4f, 1f); cut++ }
                    if (bigHere && inside(ring, gx, gy - 20)) { damageBoss(40); cut++ }
                    arcs += Arc(drawing[0], drawing[1], drawing[drawing.size - 2], drawing[drawing.size - 1])
                    flashT = 0.15f; shakeT = maxOf(shakeT, 0.25f); sfx.play(HallSfx.S.THUNDER, 0.7f); sfx.rumbleBig()
                    say(if (cut > 0) "SLASH! x$cut" else "SLASH!", 1f)
                    furyRing = null; killDead(); minions.removeAll { it.kind == 2 && it.hp < 0 }
                    drawing.clear(); drawLen = 0f; return
                }
            }
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (q in 0 until n) { minX = minOf(minX, drawing[q * 2]); maxX = maxOf(maxX, drawing[q * 2]); minY = minOf(minY, drawing[q * 2 + 1]); maxY = maxOf(maxY, drawing[q * 2 + 1]) }
            if (n >= 10 && hypot(drawing[drawing.size - 2] - drawing[0], drawing[drawing.size - 1] - drawing[1]) < 20f && maxX - minX > 14f && maxY - minY > 14f) {
                drawing += drawing[0]; drawing += drawing[1]
                furyRing = drawing.toFloatArray(); furyRingT = 2f; sfx.play(HallSfx.S.SPELL, 0.6f)
            }
            drawing.clear(); drawLen = 0f; return
        }
        // a stroke through a fresh seal: banished
        seal?.let { sl ->
            if (n >= 2 && phase == Phase.FIGHT && cutsSeal(sl, drawing.toFloatArray())) {
                seal = null; wards.removeAll { it.pts === sl.pts }
                arcs += Arc(drawing[0], drawing[1], drawing[drawing.size - 2], drawing[drawing.size - 1])
                banish(sl.m); minions.removeAll { it.kind == 2 && it.hp < 0 }
                drawing.clear(); drawLen = 0f; return
            }
        }
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
            var friendIn = false; var inComp: Comp? = null
            if (closed) eachComp { if (!friendIn && friendHere && inside(pts, frX, frY - 6)) { friendIn = true; inComp = cur } }
            if (selfIn && phase == Phase.FIGHT) {
                wards.removeAt(wards.size - 1)
                if (bag.wearing(Loot.RUNECASTER)) openRuneBook()
                else { teleport(); if (friendIn) { cur = inComp!!; blinkFriend(true); cur = comps[0] } }
            } else if (friendIn && (phase == Phase.FIGHT || phase == Phase.WON)) {
                // a circle round your companion: it's whisked to safety, near you
                wards.removeAt(wards.size - 1)
                cur = inComp!!; blinkFriend(false); cur = comps[0]
            } else if (closed && phase == Phase.FIGHT) {
                var hit = 0
                // with the Runecaster, a circled foe can be brainwashed instead
                // a dazed behemoth in the circle is sealed first (see Seal): a moment to strike through, or to let it be
                val big = if (seal == null) minions.firstOrNull { it.room == roomId && !it.ally && it.kind == 2 && it.daze > 0f && inside(pts, it.x, it.y) } else null
                if (big != null) {
                    seal = Seal(big, pts); wards.last().life = maxOf(wards.last().life, SEAL_TIME + 0.5f)
                    fxs += Fx(FX_BIND, big.x, big.y - 10, 0.6f); sfx.play(HallSfx.S.BOSS_RUNE, 0.7f); sfx.rumbleSoft()
                    say(if (bag.wearing(Loot.RUNECASTER)) "SEALED! STRIKE THROUGH TO BANISH, OR WAIT TO BRAINWASH" else "SEALED! NOW STRIKE A LINE THROUGH IT!", 1.8f)
                    drawing.clear(); drawLen = 0f; return
                }
                val thrall = if (bag.wearing(Loot.RUNECASTER) && wash == null) minions.firstOrNull { it.room == roomId && !it.ally && it.hp >= 0 && it.popT <= 0f && it.kind != 2 && it.kind != 4 && inside(pts, it.x, it.y) } else null
                if (thrall != null) { wards.removeAt(wards.size - 1); beginWash(thrall); drawing.clear(); drawLen = 0f; return }
                for (m in minions) if (m.room == roomId && !m.ally && inside(pts, m.x, m.y)) {
                    if (m.kind == 2) { if (m.daze <= 0f) spurned(m); continue }
                    curse(m); hit++
                }
                minions.removeAll { it.kind == 2 && it.hp < 0 }
                seeds.removeAll { sd -> (sd.room == roomId && inside(pts, sd.x, sd.y)).also { if (it) { fxs += Fx(FX_PETALS, sd.x, sd.y - 6, 0.5f); hit++ } } }
                killDead()
                if (bigHere && inside(pts, gx, gy - 20)) curseBoss()
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
            if (bossHere && phase == Phase.FIGHT) near = minOf(near, hypot(bx - x, by - y) * 0.8f)
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
            val x = camX + 8f + rnd.nextFloat() * (W - 32f); val y = camY + 26f + rnd.nextFloat() * (H - 46f)
            if (blocked(x, y)) return@repeat
            var near = Float.MAX_VALUE
            for (m in minions) if (m.room == roomId) near = minOf(near, hypot(m.x - (x + 8), m.y - (y + 9)))
            for (sh in shots) near = minOf(near, hypot(sh.x - (x + 8), sh.y - (y + 9)) * 1.5f)
            if (bossHere && phase == Phase.FIGHT) near = minOf(near, hypot(bx - (x + 8), by - (y + 12)))
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
            if (m.ally) { if (m.hp < 0) it.remove(); continue }
            if (m.hp > 0 || m.kind == 2) continue
            it.remove()
            gainXp(5); sfx.play(HallSfx.S.IMP_DIE, 0.7f)
            if (inWild) { coins += (if (m.kind == 1 || m.kind == 3) 2 else 1 + rnd.nextInt(2)) + (if (bag.wearing(Loot.COIN_PURSE)) 1 else 0); fxs += Fx(FX_NOVA_STAR, m.x, m.y - 4, 0.3f) }
            // loot is rare: an everyday find from about one foe in fifty; better things far more seldom; a herb now and then
            val lk = if (bag.wearing(Loot.LODESTONE)) 2f else 1f
            val q = rnd.nextFloat()
            when {
                q < 0.02f * lk -> drops += Drop(m.x, m.y, m.room, rollTier(Rarity.COMMON))
                q < 0.026f * lk -> drops += Drop(m.x, m.y, m.room, rollTier(Rarity.RARE))
                q < 0.028f * lk -> drops += Drop(m.x, m.y, m.room, rollTier(Rarity.EPIC))
                q < 0.05f * lk -> drops += Drop(m.x, m.y, m.room, Loot.HERB)
            }
        }
    }
    /** Something of this rarity you don't have yet (or the nearest tier below that has one; at worst a herb). */
    private fun rollTier(r: Rarity): Loot {
        for (t in r.ordinal downTo 0) {
            val pool = Loot.values().filter { it.dropped && !it.consumable && it !in bag.owned && it.rarity.ordinal == t }
            if (pool.isNotEmpty()) return pool[rnd.nextInt(pool.size)]
        }
        return Loot.HERB
    }
    /** A boss's prize: one time in twenty an exotic, else something epic or rare. */
    private fun bossPrize(): Loot = when {
        rnd.nextFloat() < 0.05f * (if (bag.wearing(Loot.LODESTONE)) 1.5f else 1f) -> rollTier(Rarity.EXOTIC)
        rnd.nextFloat() < 0.4f -> rollTier(Rarity.EPIC)
        else -> rollTier(Rarity.RARE)
    }
    /** A behemoth-class beast's: legendary one time in a hundred, an epic or rare now and then. */
    private fun bigPrize(): Loot? {
        val lk = if (bag.wearing(Loot.LODESTONE)) 2f else 1f; val q = rnd.nextFloat()
        return when { q < 0.01f * lk -> rollTier(Rarity.LEGENDARY); q < 0.04f * lk -> rollTier(Rarity.EPIC); q < 0.1f * lk -> rollTier(Rarity.RARE); q < 0.2f -> Loot.HERB; else -> null }
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
        stompCd = 2.6f
        val cx = hx + 8; val cy = hy + 13
        val reach = if (bag.wearing(Loot.STOMP_STONE)) 52f else 44f
        rings += Ring(cx, cy); shakeT = 0.15f
        var shattered = 0
        for (m in minions) {
            if (m.room != roomId || m.ally) continue
            val dx = m.x - cx; val dy = m.y - cy; val d = hypot(dx, dy)
            if (d < reach) {
                val k = ((reach - d) / reach * 300f + 140f) * (if (m.kind == 2) 0.25f else 1f); val dd = d.coerceAtLeast(1f); m.kx = dx / dd * k; m.ky = dy / dd * k; m.stun = if (m.kind == 2) 0.15f else 0.6f
                if (m.kind == 1 && m.st != 0) { m.st = 3; m.stT = 0.8f }
                // the Frost Ring freezes them solid; stamp on one already frozen and it shatters
                if (bag.wearing(Loot.FROST_RING)) {
                    if (m.ice > 0f) { hitImp(m, pw(3), true); charge(3); m.ice = 0f; shattered++; fxs += Fx(FX_SHATTER, m.x, m.y, 0.4f) } else m.ice = 3f
                }
                if (bag.wearing(Loot.FIVE_EYED_AXE)) { hitImp(m, pw(2)); charge(2) }
            }
        }
        val trod = seeds.filter { it.room == roomId && hypot(it.x - cx, it.y - cy) < 40f }
        if (trod.isNotEmpty()) { seeds.removeAll(trod.toSet()); trod.forEach { fxs += Fx(FX_PETALS, it.x, it.y - 6, 0.5f) }; say(if (trod.size == 1) "TRAMPLED!" else "TRAMPLED x${trod.size}!", 1f) }
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

    // =====================================================================================
    // The Wildwood: level after level of forest, each made fresh. Find the keys, open the gates,
    // and take the root door down to the next. Every five to ten levels, a beast waits at the heart.
    // =====================================================================================
    // =====================================================================================
    // The encounter screen: where to begin (and a way back in to wherever you saved).
    // =====================================================================================
    private var choosing = true; private var chooseCur = 0; private var savedDepth = 0
    private fun encounters(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (savedDepth > 0) out += "CONTINUE" to "THE WILDWOOD, LEVEL $savedDepth"
        else if (chapter == 2) out += "CONTINUE" to "THE BLOSSOM AWAITS"
        out += "THE FIVE-EYED" to "THE GREAT HALL. HE IS NOT HAPPY."
        out += "THE BLOSSOM" to "HIS WIDOW, IN HER GARDEN OF THORNS"
        out += "THE WILDWOOD" to "FROM LEVEL 1: KEYS, GATES, THE ROOT DOOR"
        out += "A BEAST OF THE WOOD" to "STRAIGHT TO ONE OF ITS GUARDIANS"
        return out
    }
    private fun chooseNow() {
        val pick = encounters().getOrNull(chooseCur)?.first ?: return
        choosing = false; sfx.play(HallSfx.S.SPELL); sfx.rumbleSoft()
        when (pick) {
            "CONTINUE" -> if (savedDepth > 0) { forestOpen = true; startWild(savedDepth, fresh = false) } else restart(2)
            "THE FIVE-EYED" -> { restart(1); rx = 1; ry = 0; hx = TX + 112f; hy = TY + 136f; visited += 4; camX = (hx + 8 - W / 2f).coerceIn(0f, WW - W); camY = (hy + 12 - H / 2f).coerceIn(0f, WH - H); phase = Phase.INTRO; talking = true; talkLines = RANT; line = 0; shown = 0f; minions.clear(); placeFriendNear() }
            "THE BLOSSOM" -> restart(2)
            "THE WILDWOOD" -> { forestOpen = true; startWild(1) }
            else -> { forestOpen = true; val d = maxOf(5, savedDepth); beastDepth = d; startWild(d) }
        }
    }
    private fun drawChoose(time: Float) {
        drawRoom(time); room.props.forEach { drawProp(it, time) }
        p.color = 0xA0101018.toInt(); sc.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        val opts = encounters()
        font.color = GOLD; sc.drawText("CHOOSE YOUR ENCOUNTER", 40f, 24f, font)
        opts.forEachIndexed { i, (name, about) ->
            val y = 42f + i * 22f; val sel = i == chooseCur
            box(20f, y - 10, W - 20f, y + 10, if (sel) 0xFF2A2A40.toInt() else 0xFF181824.toInt())
            if (sel) { box(20f, y - 10, W - 20f, y - 9, GOLD); box(20f, y + 9, W - 20f, y + 10, GOLD) }
            font.color = if (sel) WHITE else MID; sc.drawText((if (sel && (time * 3).toInt() % 2 == 0) "> " else "  ") + name, 26f, y - 1, font)
            font.color = if (sel) GOLD else DARK; sc.drawText(about.take(34), 34f, y + 8, font)
        }
        font.color = DARK; sc.drawText("UP / DOWN, THEN A", 70f, H - 6f, font)
        font.color = INK
    }
    /** Into a new stretch of the wood: its name, and (the first time) what lives there. */
    private fun enterStretch(cx: Int, cy: Int) {
        val wl = wild ?: return
        rx = cx; ry = cy; bannerT = 1.8f
        val r = ry * wl.w + rx
        if (visited.add(100 + r)) {
            spL = rx * 240f; spT = ry * 176f; spR = spL + 240f; spB = spT + 176f
            val heart = (wl.bossLevel && r == wl.exit) || r in wl.fortRooms
            if (!heart && r != wl.start) {
                repeat(1 + rnd.nextInt(2) + minOf(2, depth / 5)) { val q = rnd.nextFloat(); spawnMinion(null, if (q < 0.25f) 1 else if (q < 0.47f) 3 else 0) }
                if (rnd.nextFloat() < minOf(0.12f, 0.05f + depth * 0.006f)) spawnBehemoth()
                if (r in wl.fog) repeat(2 + rnd.nextInt(2)) { spawnMinion(null, 4) }
            }
            if (r in wl.fog) say("FOG... SOMETHING MOVES IN IT", 1.6f)
        }
        if (fortHere && !wasFort) say("A FORT: NO FIGHTING HERE. SPEAK TO ITS FOLK WITH A (YOU HAVE $coins COINS)", 3f)
        wasFort = fortHere
    }
    private var wasFort = false
    private fun startWild(d: Int, fresh: Boolean = true) {
        depth = d
        if (beastDepth < depth) beastDepth = depth + 4 + rnd.nextInt(6)
        if (fresh || wildSeed == 0L) wildSeed = rnd.nextLong()
        val wl = WildLevel(depth, wildSeed, depth == beastDepth)
        wild = wl
        wildRooms = emptyList()
        // one open place: each stretch's props and water moved to where it lies; hedgerows where stretches don't join; gates where they're locked
        WW = wl.w * 240f; WH = wl.h * 176f; topY = 4f
        gates.clear(); chunkArt.clear()
        val extra = Array(wl.n) { ArrayList<IntArray>() }
        for (r in 0 until wl.n) for (d in 0 until 4) {
            val cx = r % wl.w; val cy = r / wl.w
            val o = when (d) { 0 -> if (cx > 0) r - 1 else -1; 1 -> if (cx < wl.w - 1) r + 1 else -1; 2 -> if (cy > 0) r - wl.w else -1; else -> if (cy < wl.h - 1) r + wl.w else -1 }
            val door = "WENS"[d] in wl.doors[r]; val lk = r * 4 + d in wl.locked
            if (o >= 0 && door && !lk) continue
            val t = if (o < 0) 14 else 9
            val vertical = d < 2
            fun band(a: Int, b: Int) { extra[r] += when (d) { 0 -> intArrayOf(THICKET, 0, a, t, b); 1 -> intArrayOf(THICKET, 240 - t, a, 240, b); 2 -> intArrayOf(THICKET, a, 0, b, t); else -> intArrayOf(THICKET, a, 176 - t, b, 176) } }
            if (lk) {
                if (vertical) { band(0, 82); band(122, 176) } else { band(0, 100); band(140, 240) }
                if (d == 1 || d == 3) {
                    val ox = cx * 240f; val oy = cy * 176f
                    gates += if (d == 1) Gate(RectF(ox + 240 - t, oy + 82, ox + 240 + t, oy + 122), r * 4 + d, o * 4 + WildLevel.OPP[d], true)
                    else Gate(RectF(ox + 100, oy + 176 - t, ox + 140, oy + 176 + t), r * 4 + d, o * 4 + WildLevel.OPP[d], false)
                }
            } else if (vertical) band(0, 176) else band(0, 240)
        }
        chunkExtra = Array(wl.n) { extra[it] }
        val allProps = ArrayList<Prop>(); val allPools = ArrayList<RectF>()
        chunkProps = Array(wl.n) { r ->
            val ox = (r % wl.w) * 240; val oy = (r / wl.w) * 176
            (wl.props[r] + extra[r]).map { pr(it[0], it[1] + ox, it[2] + oy, it[3] + ox, it[4] + oy) }.also { allProps += it }
        }
        chunkPools = Array(wl.n) { r ->
            val ox = (r % wl.w) * 240; val oy = (r / wl.w) * 176
            wl.rivers[r].map { pool(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }.also { allPools += it }
        }
        chunkPonds = Array(wl.n) { r ->
            val ox = (r % wl.w) * 240; val oy = (r / wl.w) * 176
            wl.pools[r].map { RectF((it[0] + ox).toFloat(), (it[1] + oy).toFloat(), (it[2] + ox).toFloat(), (it[3] + oy).toFloat()) }.also { allPools += it }
        }
        openRoom = Room("", FOREST, "WENS", allProps, allPools)
        hp = maxHp; invulnT = 0f; paraT = 0f; poisonT = 0f; invertT = 0f; keys = 0; stoneKeys = 0; doorT = -1f
        visited.clear(); keysTaken.clear(); gatesSeen.clear(); searched.clear()
        minions.clear(); shots.clear(); wards.clear(); drawing.clear(); arcs.clear(); bursts.clear(); orbs.clear(); stroke.clear(); drops.clear()
        seeds.clear(); lashes.clear(); mandalas.clear(); fires.clear(); stars.clear(); fxs.clear(); beams.clear()
        battle = null; bagOpen = false; castRune = -1; runePromptT = 0f; wash = null; carried = null
        beast = null; beastRoom = -1; beastWon = false
        bossRoom = -1; stage = Stage.DAIS; bossBurn = 0f; bossPara = 0f; bossWeak = 0f; bossConf = 0f
        phase = Phase.FIGHT; talking = false; perishT = 0f
        rx = wl.start % wl.w; ry = wl.start / wl.w; hx = rx * 240f + 112f; hy = ry * 176f + 110f
        camX = (hx + 8 - W / 2f).coerceIn(0f, WW - W); camY = (hy + 12 - H / 2f).coerceIn(0f, WH - H)
        if (blocked(hx, hy)) unstick()
        visited += 100 + wl.start
        npcs.clear(); offered = null
        for (n0 in wl.npcs) npcs += Npc(100, (n0[0] % wl.w) * 240f + n0[1], (n0[0] / wl.w) * 176f + n0[2], n0[3], n0[4])
        spawnFriend(); spawnParty()
        bannerT = 2.2f; fieldT = 0f; orbT = 18f
        say(if (wl.bossLevel) "LEVEL $depth. SOMETHING VAST STIRS AT THE HEART OF THE WOOD..." else "THE WILDWOOD, LEVEL $depth: FIND ${wl.keys.size} KEYS AND THE ROOT DOOR", 3.4f)
        sfx.play(HallSfx.S.SPELL); saveSoon = true
    }
    /** Through the root door: allies still standing join your party, and on to the next level. */
    private fun finishWildLevel() {
        val joined = minions.filter { it.ally && !it.perm && !it.merc && it.hp > 0 }.map { it.kind }
        for (k in joined) if (pool.size < 12) pool += k
        startWild(depth + 1)
        if (joined.isNotEmpty()) { say(joined.joinToString(", ") { kindName(it) } + (if (joined.size == 1) " JOINS" else " JOIN") + " YOUR PARTY! (BAG)", 3.4f); sfx.play(HallSfx.S.LEGENDARY); sfx.rumbleFanfare() }
    }
    private fun npcPrice(n: Npc) = when (n.role) { WildLevel.NPC_TOAD -> 8; WildLevel.NPC_MOTH -> 20; else -> when (n.kind) { 1 -> 18; 2 -> 40; 3 -> 16; else -> 12 } }
    private fun nearNpc() = npcs.firstOrNull { !it.gone && it.room == roomId && hypot(it.x - (hx + 8), it.y - (hy + 13)) < 22f }
    private fun npcPitch(n: Npc) = when (n.role) {
        WildLevel.NPC_TOAD -> "GRUBBLE THE TOAD: HERBS, 8 COINS. CROAK. (A AGAIN TO BUY)"
        WildLevel.NPC_MOTH -> "MOTHER DUSK: A SPIRIT BLOOM, 20 COINS. IT BRINGS THEM BACK. (A AGAIN)"
        else -> when (n.kind) {
            1 -> "A STALKER FOR HIRE: 18 COINS AND I HUNT FOR YOU TILL THE ROOT DOOR. (A AGAIN)"
            2 -> "A BEHEMOTH FOR HIRE: 40 COINS. I BREAK THINGS. (A AGAIN)"
            3 -> "A GAZER FOR HIRE: 16 COINS. I SEE EVERYTHING. (A AGAIN)"
            else -> "AN IMP FOR HIRE: 12 COINS, CHEAP AND NASTY. (A AGAIN)"
        }
    }
    /** Talk to fort folk: the first A hears the offer, a second (soon after) pays for it. */
    private val GUARD_LINES = listOf("GUARD: NOTHING GETS PAST THIS GATE.", "GUARD: I BLOW THEM BACK WHERE THEY CAME FROM.", "GUARD: STAY INSIDE THE WALL AND YOU'RE SAFE.", "GUARD: ONE STAMP AND THEY FREEZE IN THEIR TRACKS.")
    private val FOLK_LINES = listOf("THE WELL WATER'S SWEET TODAY.", "MIND THE WASHING, IT'S STILL WET.", "THE FOG'S COMING IN THICKER EVERY NIGHT...", "HAVE YOU SEEN A DRAKE? MY COUSIN HAS.", "THE TOAD'S HERBS FIXED MY TAIL RIGHT UP.", "HAY'S IN. GOOD YEAR FOR HAY.", "THEY SAY THE RUINS WERE A KING'S HALL ONCE.")
    private fun npcAct(n: Npc) {
        if (n.role == WildLevel.NPC_GUARD) { say(GUARD_LINES[rnd.nextInt(GUARD_LINES.size)], 2.2f); sfx.play(HallSfx.S.FETCH, 0.4f); return }
        if (n.role == WildLevel.NPC_FOLK) { say(FOLK_LINES[rnd.nextInt(FOLK_LINES.size)], 2.2f); sfx.play(HallSfx.S.FETCH, 0.4f); n.waitT = 2.5f; n.face = if (hx + 8 > n.x) 1f else -1f; return }
        if (offered !== n || offerT <= 0f) { offered = n; offerT = 4f; say(npcPitch(n) + " YOU HAVE $coins", 4f); sfx.play(HallSfx.S.FETCH, 0.5f); return }
        offered = null
        val price = npcPrice(n)
        if (coins < price) { say("NOT ENOUGH COINS ($coins OF $price)", 1.8f); sfx.play(HallSfx.S.HURT, 0.3f); return }
        when (n.role) {
            WildLevel.NPC_TOAD -> if (bag.herbs >= 9) { say("YOUR BAG HOLDS NO MORE HERBS", 1.6f); return } else { bag.herbs++; say("A HERB! (${bag.herbs} NOW)", 1.6f) }
            WildLevel.NPC_MOTH -> if (bag.blooms >= 5) { say("YOU CARRY ALL THE BLOOMS YOU CAN", 1.6f); return } else { bag.blooms++; say("A SPIRIT BLOOM! (${bag.blooms} NOW)", 1.6f) }
            else -> {
                n.gone = true
                minions += Minion(n.x, n.y, roomId, n.kind).also { makeAlly(it, false); it.merc = true }
                fxs += Fx(FX_JOIN, n.x, n.y - 8, 0.8f)
                say("THE ${kindName(n.kind)} FIGHTS FOR YOU UNTIL THE ROOT DOOR", 2.2f)
            }
        }
        coins -= price; sfx.play(HallSfx.S.PICKUP); sfx.rumbleSoft(); saveSoon = true
    }
    /** Nothing hostile here (no foes, no boss on the prowl). */
    private fun calm() = !bigHere && minions.none { it.room == roomId && !it.ally && it.hp > 0 }
    /**
     * A place in the line. Behind you when calm (companions, then thralls), each a step apart across
     * the way you face; or, for thralls when there's a fight, a front line a little way toward it.
     */
    private fun lineSlot(i: Int, front: Boolean, toward: Minion?): Pair<Float, Float> {
        val px = hx + 8; val py = hy + 13
        var fx = when (facing) { 2 -> -1f; 3 -> 1f; else -> 0f }; var fy = when (facing) { 0 -> 1f; 1 -> -1f; else -> 0f }
        if (front && toward != null) { val d = hypot(toward.x - px, toward.y - py).coerceAtLeast(1f); fx = (toward.x - px) / d; fy = (toward.y - py) / d }
        val n = if (front) maxOf(1, minions.count { it.ally && it.room == roomId && it.hp > 0 }) else comps.count { it.kind != null && it.state == 1 && it.room == roomId } + minions.count { it.ally && it.room == roomId && it.hp > 0 }
        val back = if (front) 26f else -20f
        val cx = px + fx * back; val cy = py + fy * back * 0.8f
        val k = i - (n - 1) / 2f
        val x = (cx - fy * k * 15f).coerceIn(14f, WW - 14f); val y = (cy + fx * k * 12f).coerceIn(topY + 12f, WH - 8f)
        return x to y
    }
    private fun kindName(k: Int) = when (k) { 1 -> "STALKER"; 2 -> "BEHEMOTH"; 3 -> "GAZER"; else -> "IMP" }
    private fun useExit() {
        val wl = wild ?: return
        when {
            wl.bossLevel && !beastWon -> say("THE BEAST'S ROOTS HOLD THE DOOR FAST.", 1.8f)
            doorT >= 0f -> {}
            stoneKeys <= 0 -> say(if (keys > 0) "A GREAT STONE DOOR. A WOODEN KEY WON'T TURN IT: FIND THE STONE KEY." else "A GREAT STONE DOOR. IT NEEDS THE STONE KEY.", 2f)
            else -> {
                stoneKeys--; doorT = 0f
                val e = chunkProps[wl.exit].first { it.kind == EXIT }; doorX = e.foot.centerX(); doorY = e.foot.bottom
                say("THE STONE KEY TURNS...", 2f); sfx.play(HallSfx.S.DOOR_UNLOCK); sfx.rumbleBig()
            }
        }
    }
    /** Keys underfoot; gates you walk up to (opened with a key if you have one). */
    private var gateMsgT = 0f
    /** The beast just fell: its brood and its spores go with it (cleared here, outside any loop over them). */
    private var beastFell = false
    private fun wildChecks(dt: Float) {
        val wl = wild ?: return
        gateMsgT -= dt; offerT -= dt
        if (doorT >= 0f) {
            val was = doorT; doorT += dt
            if (was < 0.6f && doorT >= 0.6f) { sfx.play(HallSfx.S.DOOR_GRIND); shakeT = maxOf(shakeT, 1.6f) }
            if (doorT in 0.6f..2.4f) { shakeT = maxOf(shakeT, 0.12f); if (rnd.nextFloat() < dt * 10f) bursts += Burst(doorX + (rnd.nextFloat() - 0.5f) * 50f, doorY - rnd.nextFloat() * 4f) }
            if (was < 2.4f && doorT >= 2.4f) { flashT = 0.35f; sfx.play(HallSfx.S.DOOR_OPEN); sfx.rumbleFanfare(); say("THE DOOR STANDS OPEN ON THE STARS.", 2f) }
            if (doorT >= 3.6f) { doorT = -1f; finishWildLevel(); return }
        }
        if (beastFell) { beastFell = false; minions.removeAll { it.room == roomId && !it.ally }; shots.clear(); lashes.clear() }
        val here = ry * wl.w + rx
        wl.keys.forEachIndexed { i, k ->
            val kx = (k[0] % wl.w) * 240f + k[1]; val ky = (k[0] / wl.w) * 176f + k[2]
            if (i !in keysTaken && hypot(kx - (hx + 8), ky - (hy + 12)) < 11f) {
                keysTaken += i; bursts += Burst(kx, ky); fxs += Fx(FX_NOVA_STAR, kx, ky, 0.5f)
                if (wl.isStone(i)) { stoneKeys++; say("THE STONE KEY! IT OPENS THE GREAT DOOR.", 2.4f); sfx.play(HallSfx.S.EXOTIC, 0.8f) }
                else { keys++; say("A WOODEN KEY! (${keysTaken.count { !wl.isStone(it) }}/${wl.keys.size - 1}) IT OPENS A GATE.", 2f); sfx.play(HallSfx.S.LEGENDARY, 0.8f) }
                sfx.rumbleSoft(); saveSoon = true
            }
        }
        // a gate in a hedgerow: opened with a key if you have one
        val gi = gates.iterator()
        while (gi.hasNext()) {
            val g = gi.next()
            val nearG = hx + 8 > g.r.left - 10 && hx + 8 < g.r.right + 10 && hy + 12 > g.r.top - 12 && hy + 12 < g.r.bottom + 12
            if (!nearG) continue
            gatesSeen += g.a; gatesSeen += g.b
            if (keys > 0) {
                keys--; gi.remove(); wl.locked -= g.a; wl.locked -= g.b
                bursts += Burst(g.r.centerX(), g.r.centerY()); fxs += Fx(FX_NOVA, g.r.centerX(), g.r.centerY(), 0.6f); shakeT = 0.2f
                say("THE GATE SWINGS OPEN!", 1.6f); sfx.play(HallSfx.S.COUNTER); sfx.rumbleBig(); saveSoon = true; fieldT = 0f
            } else if (gateMsgT <= 0f) { gateMsgT = 2.5f; say(if (stoneKeys > 0) "A LOCKED GATE. THE STONE KEY WON'T FIT: FIND A WOODEN ONE." else "A LOCKED GATE. FIND A WOODEN KEY.", 1.8f) }
        }
        // the beast: it rises when you reach the heart of the wood
        if (wl.bossLevel && !beastWon && beast == null && here == wl.exit) {
            beast = WildBeast(wildSeed xor depth.toLong(), depth).also { it.x = rx * 240f + 120f; it.y = ry * 176f + 104f; it.name = "BLOSSOM OF THE WOOD" }
            beastRoom = roomId; shakeT = 0.9f; hurtFlashT = 0.35f
            say("THE ${beast!!.name} AWAKENS!", 2.6f); sfx.play(HallSfx.S.SLAM); sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleSlam()
        }
        val b = beast
        if (b != null && b.dead && b.deathT > 2f && !beastWon) {
            beastWon = true
            drops += Drop(b.x, b.y, beastRoom, bossPrize()).also { it.life = 1e9f }
            drops += Drop(b.x + 14f, b.y + 4f, beastRoom, Loot.HERB).also { it.life = 1e9f }
            say("THE ${b.name} IS NO MORE. THE ROOT DOOR STANDS FREE.", 3f); sfx.play(HallSfx.S.LEGENDARY); sfx.rumbleFanfare()
            beast = null; saveSoon = true
        }
    }
    private val beastHost = object : WildBeast.Host {
        override fun hurt(n: Int) { this@HallScreen.hurt(n) }
        override fun shoot(x: Float, y: Float, a: Float, speed: Float) { shots += Shot(x, y, cos(a) * speed * SLOW, sin(a) * speed * SLOW, true, false).also { it.petal = true } }
        override fun summon(x: Float, y: Float) { if (minions.count { it.room == roomId && !it.ally } < 6) spawnMinion((x.coerceIn(16f, WW - 16f)) to (y.coerceIn(topY + 12f, WH - 12f))) }
        override fun roots(x0: Float, y0: Float, x1: Float, y1: Float) { lashes += Lash(x0, y0, x1.coerceIn(8f, WW - 8f), y1.coerceIn(topY + 4f, WH - 6f), roomId) }
        override fun shake(t: Float) { shakeT = maxOf(shakeT, t) }
        override fun solid(x: Float, y: Float) = bossSolid(x, y)
        override fun say(s: String) { this@HallScreen.say(s, 1.6f) }
        override fun roar() { sfx.play(HallSfx.S.BOSS_RUNE, 0.6f) }
    }
    private fun updateBeast(dt: Float, px: Float, py: Float) {
        val b = beast ?: return
        if (beastRoom != roomId) return
        // curses laid on "the boss" land on the beast
        if (bossPara > 0f) { b.para = maxOf(b.para, bossPara); bossPara = 0f }
        if (bossWeak > 0f) { b.weak = maxOf(b.weak, bossWeak); bossWeak = 0f }
        if (bossConf > 0f) { b.conf = maxOf(b.conf, bossConf); bossConf = 0f }
        bossFireCd -= dt
        if (bossBurn > 0f) { bossBurn -= dt; bossBurnTick -= dt; if (bossBurnTick <= 0f) { bossBurnTick = 0.7f; damageBoss(3) } }
        if (bossFireCd <= 0f && fires.any { it.room == roomId && abs(it.x - b.x) < b.radius && abs(it.y - b.y) < 6f }) { bossFireCd = 0.7f; damageBoss(5); bossBurn = 2.5f; bossBurnTick = 0.7f }
        b.update(dt, px, py + 4f, beastHost)
        eachComp { if (friendHere && !b.dead && hypot(frX - b.x, frY - b.y) < b.radius * 0.8f) hurtFriend(12) }
        for (m in minions) if (m.ally && m.room == roomId && !b.dead && hypot(m.x - b.x, m.y - b.y) < b.radius * 0.8f) hurtAlly(m, 10)
    }

    // =====================================================================================
    // Brainwashing (with the Runecaster): circle a foe and trace its runes, in order, before
    // time runs out. It fights for you, in an ally's colours, until the level's done; if it's
    // still standing when you leave through the root door, it joins your party for good.
    // =====================================================================================
    private class Wash(val m: Minion, val seq: List<Int>) { var i = 0; var t = 0f; var max = 0f }
    private var wash: Wash? = null
    private val WASH_SHAPES = listOf(RuneShapes.ZIG, RuneShapes.HOUR, RuneShapes.TRI, RuneShapes.ARCH, RuneShapes.COIL, RuneShapes.BOLT, RuneShapes.LEAF, RuneShapes.CHECK)
    private fun washTime(m: Minion) = (if (m.kind == 2) 3.8f else 4.6f) * (if (bag.wearing(Loot.RUNE_LENS)) 1.3f else 1f)
    private fun beginWash(m: Minion) {
        if (m.kind == 4) { say("A SHADE HAS NO MIND TO TAKE", 1.4f); return }
        if (minions.count { it.ally && !it.perm && !it.merc && it.hp >= 0 } >= 1) { say("YOUR MIND HOLDS ONLY ONE THRALL AT A TIME", 1.8f); return }
        val n = if (m.kind == 2) 4 else 3
        val seq = ArrayList<Int>(); while (seq.size < n) { val k = rnd.nextInt(WASH_SHAPES.size); if (seq.lastOrNull() != k) seq += k }
        wash = Wash(m, seq).also { it.t = washTime(m); it.max = it.t }
        m.para = 99f; stroke.clear(); runePromptT = 0f
        fxs += Fx(FX_HEX, m.x, m.y, 0.8f)
        msgT = 0f; sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleSoft()
    }
    private fun washStroke() {
        val w = wash ?: return
        if (stroke.size < 4) { stroke.clear(); return }
        val shape = WASH_SHAPES[w.seq[w.i]]
        val sc0 = similarityTo(stroke, shape, RuneShapes.closed(shape)); lastScore = sc0; scoreT = 1.2f; stroke.clear()
        if (sc0 < NEED) { sfx.play(HallSfx.S.HURT, 0.3f); return }
        w.i++; w.t = washTime(w.m); w.max = w.t
        fxs += Fx(FX_BIND, w.m.x, w.m.y, 0.5f); sfx.play(HallSfx.S.SPELL, 0.7f)
        if (w.i >= w.seq.size) {
            makeAlly(w.m, false); wash = null
            fxs += Fx(FX_JOIN, w.m.x, w.m.y - 6, 0.9f); fxs += Fx(FX_NOVA, w.m.x, w.m.y - 6, 0.6f); flashT = 0.15f
            say("THE ${kindName(w.m.kind)} IS YOURS UNTIL THE LEVEL'S END!", 2.4f); sfx.play(HallSfx.S.EXOTIC); sfx.rumbleFanfare()
        }
    }
    private fun updateWash(dt: Float) {
        val w = wash ?: return
        val m = w.m
        if (m !in minions || m.room != roomId || m.hp < 0 || (m.kind != 2 && m.hp == 0)) { wash = null; return }
        m.para = 99f
        w.t -= dt
        if (w.t <= 0f) {
            wash = null; m.para = 0f; m.conf = 0f
            if (m.kind == 2) m.daze = 0.01f      // it wakes, heals and rages
            say("THE SPELL BREAKS! IT SHAKES YOU OFF", 1.8f); sfx.play(HallSfx.S.HURT_BIG, 0.6f); sfx.rumbleHit()
        }
    }
    private fun allyHp(k: Int) = when (k) { 1 -> 50; 2 -> 120; 3 -> 34; else -> 40 }
    private fun makeAlly(m: Minion, perm: Boolean) {
        m.ally = true; m.perm = perm; m.maxHp = allyHp(m.kind); m.hp = m.maxHp
        m.para = 0f; m.weak = 0f; m.conf = 0f; m.slow = 0f; m.burn = 0f; m.ice = 0f; m.stun = 0f
        m.daze = 0f; m.rage = 0f; m.popT = 0f; m.slamT = 0f; m.st = 0; m.atkCd = 1f
    }
    /** An ally takes a blow; at nothing it vanishes (one from your party is back next level). */
    private fun hurtAlly(m: Minion, n: Int) {
        if (!m.ally || m.hurtT > 0f || m.hp <= 0 || phase != Phase.FIGHT) return
        m.hp -= n; m.hurtT = 0.5f
        if (m.hp <= 0) {
            m.hp = -1; bursts += Burst(m.x, m.y - 4); fxs += Fx(FX_PETALS, m.x, m.y - 6, 0.5f)
            say(if (m.perm) "YOUR ${kindName(m.kind)} FALLS (BACK NEXT LEVEL)" else "YOUR ${kindName(m.kind)} VANISHES...", 1.8f)
            sfx.play(HallSfx.S.FRIEND_DOWN, 0.6f)
        }
    }
    /** An ally's turn: fight the nearest foe (or the big one), else keep near you. */
    private fun updateAlly(m: Minion, dt: Float, px: Float, py: Float) {
        m.atkCd -= dt
        if (!m.merc) { m.supCd -= dt; if (m.supCd <= 0f) partySupport(m, px, py) }
        if (m.para > 0f || m.ice > 0f || m.stun > 0f || m.hp <= 0) return
        // the foe nearest you (thralls guard you; they don't roam off hunting)
        var t: Minion? = null; var td = 95f
        for (o in minions) if (o.room == roomId && !o.ally && o.hp > 0 && o.popT <= 0f) { val d = hypot(o.x - px, o.y - py); if (d < td) { td = d; t = o } }
        val big = bigHere && phase == Phase.FIGHT && hypot(gx - px, gy - py) < 110f
        val sp = when (m.kind) { 1 -> HERO_SPEED * 1.0f; 2 -> HERO_SPEED * 0.65f; else -> 16f * 1.1f * 1.4f } * SLOW * dt
        val k = if (m.kind == 2) 1 else 0
        val mine = minions.filter { it.ally && it.room == roomId && it.hp > 0 }
        val idx = mine.indexOf(m)
        // where it stands: in the front line toward the fight, or in the line behind you
        val (sx, sy) = if (t != null || big) lineSlot(idx, true, t) else lineSlot(comps.count { it.kind != null && it.state == 1 && it.room == roomId } + idx, false, null)
        val ds = hypot(sx - m.x, sy - m.y)
        if (ds > 3f) { chase(m, sx, sy, sp * (if (ds > 40f) 1.3f else 0.8f), k, ds > 60f, dt); m.step += dt }
        if (t == null && !big) return
        val tx = t?.x ?: gx; val ty = t?.y ?: (gy - 6)
        val d = hypot(tx - m.x, ty - m.y)
        m.face = if (tx > m.x) 1f else -1f
        if (m.atkCd > 0f) return
        // slow, steady blows from the line
        when (m.kind) {
            0, 3 -> if (d < 12f) { m.atkCd = 1.8f; if (t != null) hitImp(t, 1) else damageBoss(2); bursts += Burst(tx, ty) }
                else if (d < 110f) { m.atkCd = 2.6f; val a = atan2(ty - m.y, tx - m.x); shots += Shot(m.x, m.y, cos(a) * 96f * SLOW, sin(a) * 96f * SLOW, false, false).also { it.ally = true } }
            1 -> if (d < 30f) {
                // a short lunge out of the line and back
                m.atkCd = 2.2f; if (t != null) { hitImp(t, 2); t.kx = (t.x - m.x) * 5f; t.ky = (t.y - m.y) * 5f } else damageBoss(4)
                fxs += Fx(FX_STRIKE, tx, ty - 2, 0.3f, m.face)
            }
            else -> if (d < 36f) {
                m.atkCd = 3.6f
                rings += Ring(m.x, m.y + 2); shakeT = maxOf(shakeT, 0.12f); sfx.play(HallSfx.S.STOMP, 0.5f)
                for (o in minions) if (o.room == roomId && !o.ally && hypot(o.x - m.x, o.y - m.y) < 36f) { hitImp(o, 3); o.stun = 0.6f }
                if (big && hypot(gx - m.x, gy - m.y) < 48f) damageBoss(8)
            }
        }
    }
    /** A party member's support (imps, stalkers and gazers; behemoths and dragons only fight). */
    private fun partySupport(m: Minion, px: Float, py: Float) {
        when (m.kind) {
            0 -> {
                // ember mend: a warm stream to you, and one to the most hurt thrall
                val hurtAlly = minions.filter { it.ally && it.room == roomId && it.hp in 1 until it.maxHp }.minByOrNull { it.hp.toFloat() / it.maxHp }
                if (hp >= maxHp - 6 && hurtAlly == null) { m.supCd = 1.5f; return }
                m.supCd = 15f
                if (hp < maxHp) { hp = minOf(maxHp, hp + 6); beams += Beam(m.x, m.y - 6, false); fxs += Fx(FX_MEND, px, py - 4, 0.6f) }
                hurtAlly?.let { it.hp = minOf(it.maxHp, it.hp + 8); arcs += Arc(m.x, m.y - 6, it.x, it.y - 6); fxs += Fx(FX_MEND, it.x, it.y - 6, 0.5f) }
                say(if (m.perm) "IMP: EMBER MEND" else "THRALL: EMBER MEND", 1f); sfx.play(HallSfx.S.ORB, 0.5f)
            }
            1 -> {
                // the hunting howl: foes near you falter and slow; you're quickened
                val near = minions.filter { !it.ally && it.room == roomId && it.hp > 0 && hypot(it.x - px, it.y - py) < 90f }
                if (near.isEmpty()) { m.supCd = 1.5f; return }
                m.supCd = 10f
                for (o in near) { o.weak = maxOf(o.weak, 4f); o.slow = maxOf(o.slow, 4f) }
                swiftT = maxOf(swiftT, 3f); rings += Ring(m.x, m.y); fxs += Fx(FX_HEX, m.x, m.y, 0.6f)
                say(if (m.perm) "STALKER: HUNTING HOWL" else "THRALL: HUNTING HOWL", 1f); sfx.play(HallSfx.S.SPRINT, 0.6f)
            }
            3 -> {
                // warding glare: the nearest foe held, the shots round you burned away
                val t = minions.filter { !it.ally && it.room == roomId && it.hp > 0 }.minByOrNull { hypot(it.x - px, it.y - py) }
                if (t == null || hypot(t.x - px, t.y - py) > 100f) { m.supCd = 1.5f; return }
                m.supCd = 9f
                t.para = maxOf(t.para, 2f); fxs += Fx(FX_BIND, t.x, t.y, 0.5f); arcs += Arc(m.x, m.y - 12, t.x, t.y)
                shots.removeAll { !it.ally && hypot(it.x - px, it.y - py) < 50f }
                say(if (m.perm) "GAZER: WARDING GLARE" else "THRALL: WARDING GLARE", 1f); sfx.play(HallSfx.S.ZAP, 0.5f)
            }
            else -> m.supCd = 99f
        }
    }
    // ---- your party: foes who saw a level through with you, and which of them come along
    private val pool = ArrayList<Int>()
    private val partyOn = LinkedHashSet<Int>()
    /** Companions beyond your first: one more every two levels, up to five in all. */
    private val extraSlots get() = minOf(4, depth / 2)
    /** Each one along takes a tenth off your own blows. */
    private val powerK get() = (1f - 0.1f * partyOn.size).coerceAtLeast(0.6f)
    private fun pw(n: Int): Int { val v = n * powerK; val f = v.toInt(); return f + (if (rnd.nextFloat() < v - f) 1 else 0) }
    private fun spawnParty() {
        partyOn.removeAll { it >= pool.size }
        while (partyOn.size > extraSlots) partyOn.remove(partyOn.last())
        for (i in partyOn) spawnPartyMember(i)
    }
    private fun spawnPartyMember(i: Int) {
        val k = pool.getOrNull(i) ?: return
        val a = rnd.nextFloat() * 6.2832f
        var x = hx + 8 + cos(a) * 20f; var y = hy + 13 + sin(a) * 12f
        if (foeSolid(x, y, if (k == 2) 8f else 3f)) { x = hx + 8; y = hy + 16 }
        minions += Minion(x, y, roomId, k).also { makeAlly(it, true); it.slot = i }
        fxs += Fx(FX_JOIN, x, y - 6, 0.7f)
    }
    private fun toggleParty(i: Int) {
        if (i in partyOn) {
            partyOn -= i; minions.removeAll { it.perm && it.slot == i }
            say("${kindName(pool[i])} WAITS BEHIND. POWER ${(powerK * 100).toInt()}%", 1.8f)
        } else if (partyOn.size >= extraSlots) {
            say(if (extraSlots == 0) "NO ROOM YET: DEEPER LEVELS GROW YOUR PARTY" else "ROOM FOR $extraSlots MORE AT LEVEL $depth", 2f)
        } else {
            partyOn += i
            if (inWild && phase == Phase.FIGHT) spawnPartyMember(i)
            say("${kindName(pool[i])} COMES ALONG. YOUR POWER ${(powerK * 100).toInt()}%", 2f)
        }
        saveSoon = true
    }

    /** Into another room of the open hall: its name, and (the first time) whatever lurks there. */
    private fun enterZone(nx: Int, ny: Int) {
        rx = nx; ry = ny; bannerT = 1.8f
        val z = zone
        spL = zoneOX(z); spT = zoneOY(z); spR = spL + 240f; spB = spT + 176f
        if (!visited.add(z)) return
        if (z == 1 || z == 4 || phase != Phase.FIGHT) return
        if (minions.count { !it.ally && hypot(it.x - (hx + 8), it.y - (hy + 13)) < 200f } < 2) repeat(1 + rnd.nextInt(2)) { val r = rnd.nextFloat(); spawnMinion(null, if (r < 0.25f) 1 else if (r < 0.47f) 3 else 0) }
        if (!bossHere || stage != Stage.CHASE) if (minions.none { it.kind == 2 && !it.ally } && rnd.nextFloat() < 0.07f) spawnBehemoth()
    }
    private fun enterRoom(nx: Int, ny: Int, px: Float, py: Float) {
        val from = roomId
        rx = nx; ry = ny; hx = px; hy = py; bannerT = 1.8f
        visited += roomId
        // your allies come with you
        var k0 = 0
        for (m in minions) if (m.ally && m.room == from && m.hp > 0) {
            m.room = roomId; val a = k0++ * 1.3f
            m.x = (hx + 8 + cos(a) * 14f).coerceIn(12f, W - 12f); m.y = (hy + 13 + sin(a) * 9f).coerceIn(40f, H - 8f)
            if (foeSolid(m.x, m.y, if (m.kind == 2) 8f else 3f)) { m.x = hx + 8; m.y = hy + 14 }
            m.kx = 0f; m.ky = 0f; m.detT = 0f
        }
        wash = null
        if (blocked(hx, hy)) unstick()
        eachComp { if (frState == 1) { frRoom = roomId; placeFriendNear() } }
        if (chapter == 2 && phase == Phase.FIGHT && stage == Stage.CHASE && bossRoom != roomId) { followT = 2.2f; entryX = hx + 8; entryY = hy + 14 }
        shots.clear(); drawing.clear(); drawLen = 0f
        if (castRune >= 0) { castRune = -1; stroke.clear(); spellT = 4f }
        val id = roomId
        val heart = (wild?.let { it.bossLevel && ry * it.w + rx == it.exit } == true) || fortHere
        if (fortHere) say("A FORT: NO FIGHTING HERE. SPEAK TO ITS FOLK WITH A (YOU HAVE $coins COINS)", 3f)
        val more = if (inWild) minOf(3, depth / 3) else 0
        if (room.kind != 6 && !throne && !heart && phase == Phase.FIGHT && minions.count { it.room == id && !it.ally } < 2) repeat(1 + rnd.nextInt(2) + more / 2) { val r = rnd.nextFloat(); spawnMinion(null, if (r < 0.25f) 1 else if (r < 0.47f) 3 else 0) }
        // now and then, something far worse claws its way up through the floor
        val bigChance = if (inWild) minOf(0.12f, 0.05f + depth * 0.006f) else 0.07f
        if (room.kind != 6 && !throne && !heart && !bossHere && phase == Phase.FIGHT && minions.none { it.room == id && it.kind == 2 && !it.ally } && rnd.nextFloat() < bigChance) spawnBehemoth()
        fieldT = 0f
    }

    /** Moves you to the nearest open floor (arriving through a doorway onto something solid). */
    private fun unstick() {
        val x0 = hx; val y0 = hy
        for (r in 2..90 step 2) for (k in 0 until 16) {
            val a = k * 0.3927f; val x = x0 + cos(a) * r; val y = y0 + sin(a) * r
            if (x < 0f || x > WW - 16f || y < 14f || y > WH - 16f) continue
            if (!blocked(x, y)) { hx = x; hy = y; return }
        }
    }

    private fun spawnMinion(at: Pair<Float, Float>?, kind: Int = 0) {
        var x = at?.first ?: 0f; var y = at?.second ?: 0f
        if (at == null) {
            var ok = false
            for (k in 0 until 60) {
                x = spL + 20f + rnd.nextFloat() * (spR - spL - 40f); y = spT + 44f + rnd.nextFloat() * (spB - spT - 60f)
                if (hypot(x - (hx + 8), y - (hy + 9)) > 50f && !foeSolid(x, y, if (kind == 2) 8f else 3f)) { ok = true; break }
            }
            if (!ok) return
        }
        minions += Minion(x, y, roomId, kind).also { it.orbit = if (rnd.nextBoolean()) 1f else -1f; it.stT = 1.5f + rnd.nextFloat() * 2f }
    }
    /** The behemoth's entrance: the floor splits, two eyes burn in the dark, and up he comes with a roar. */
    private fun spawnBehemoth() {
        var x = 120f; var y = 100f
        for (k in 0 until 60) {
            x = spL + 24f + rnd.nextFloat() * (spR - spL - 48f); y = spT + 56f + rnd.nextFloat() * (spB - spT - 76f)
            if (hypot(x - (hx + 8), y - (hy + 12)) > 80f && !foeSolid(x, y, 8f)) break
        }
        if (foeSolid(x, y, 8f)) return
        if (rnd.nextFloat() < (if (inWild) 0.4f else 0.25f)) {
            // a drake instead: a shadow on the ground, growing, and a shriek from above
            minions += Minion(x, y, roomId, 2).also { it.drake = true; it.popT = DRAKE_DROP; it.slamCd = 2.5f; it.face = if (hx + 8 > x) 1f else -1f }
            say("A SHADOW SWEEPS OVER... LOOK UP!", 1.6f); sfx.play(HallSfx.S.SPRINT, 0.6f); sfx.rumbleSoft(); return
        }
        minions += Minion(x, y, roomId, 2).also { it.popT = BEH_POP; it.slamCd = 2.5f }
        say("SOMETHING STIRS BENEATH THE FLOOR...", 1.6f)
        sfx.play(HallSfx.S.SLAM, 0.5f); sfx.rumbleSoft()
    }
    private val BEH_POP = 2f
    private val BEH_HP = 24
    /** A dazed behemoth inside a closed circle: gone, in a pillar of light. */
    private fun banish(m: Minion) {
        m.hp = -1
        fxs += Fx(FX_NOVA, m.x, m.y - 8, 0.9f); fxs += Fx(FX_REVIVE, m.x, m.y - 10, 1.2f); bursts += Burst(m.x, m.y - 10)
        flashT = 0.25f; shakeT = 0.4f
        say("BANISHED!", 2f); sfx.play(HallSfx.S.PERISH); sfx.play(HallSfx.S.LEGENDARY, 0.7f); sfx.rumbleFanfare()
        gainXp(120); coins += 10
        bigPrize()?.let { drops += Drop(m.x, m.y, m.room, it) }
    }
    /** A circle round a behemoth still on his feet: he tears through it, and it only feeds him. */
    private fun spurned(m: Minion) {
        if (m.popT > 0f) return
        m.hp = minOf(BEH_HP, m.hp + 8); m.rage = maxOf(m.rage, 4f)
        fxs += Fx(FX_MEND, m.x, m.y - 12, 0.7f); shakeT = maxOf(shakeT, 0.15f)
        say("IT TEARS THE CIRCLE APART! STAGGER IT FIRST", 1.8f); sfx.play(HallSfx.S.SLAM, 0.6f)
    }

    private fun spawnOrb() {
        for (k in 0 until 40) {
            val x = camX + 20f + rnd.nextFloat() * (W - 40f); val y = camY + 44f + rnd.nextFloat() * (H - 60f)
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
        val n5 = kotlin.math.ceil(n * 0.9f).toInt()      // a touch gentler than before, now foes come in numbers
        val nn = if (bulwarkT > 0f) (n5 + 1) / 2 else n5
        hp -= ((if (bag.wearing(Loot.IRON_SKIN)) nn * 0.8f else nn.toFloat()) * (if (bag.wearing(Loot.THICK_HIDE)) 0.9f else 1f)).toInt(); invulnT = 1f; hurtFlashT = 0.3f
        if (n >= 25) { sfx.play(HallSfx.S.HURT_BIG); sfx.rumbleBig() } else { sfx.play(HallSfx.S.HURT); sfx.rumbleHit() }
        if (hp <= 0) { hp = 0; phase = Phase.LOST; castRune = -1; msg = ""; paraT = 0f; poisonT = 0f; invertT = 0f; stroke.clear(); sfx.play(HallSfx.S.FRIEND_DOWN); sfx.rumbleFade() }
    }

    private fun damageBoss(n: Int) {
        if (beastHere) {
            val b = beast!!
            if (phase != Phase.FIGHT || b.dead) return
            gainXp(minOf(n, b.hp)); b.hurtBy(n); bossHurtT = 0.5f
            if (b.dead) { gainXp(400); coins += 30; shakeT = 0.8f; flashT = 0.3f; sfx.play(HallSfx.S.PERISH); sfx.rumbleFade(); say("IT SHUDDERS... AND WITHERS!", 2f); beastFell = true }
            return
        }
        if (phase != Phase.FIGHT || stage == Stage.SLAM) return
        gainXp(minOf(n, maxOf(0, bossHp)))
        bossHp -= n; bossHurtT = 0.5f
        if (stage == Stage.DAIS && bossHp <= bossLast) {
            // his last stand: he slams the wall behind him, then comes for you
            bossHp = bossLast; stage = Stage.SLAM; slamT = 0f; castRune = -1; stroke.clear(); shots.clear()
            msg = "HE'S FURIOUS!"; msgT = 1.6f; sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleBig()
        } else if (stage == Stage.CHASE && bossHp <= 0) {
            bossHp = 0; phase = Phase.WON; minions.clear(); shots.clear(); castRune = -1
            paraT = 0f; poisonT = 0f; invertT = 0f; bossPara = 0f; bossConf = 0f; bossWeak = 0f
            talking = true; talkLines = if (chapter == 2) DEFEAT2 else DEFEAT; line = 0; shown = 0f
            seeds.clear(); lashes.clear(); mandalas.clear()
            bossBurn = 0f; fires.clear(); stars.clear()
            sfx.play(HallSfx.S.COUNTER); sfx.rumbleBig()
        }
    }

    /** A fresh match: from the first foe, or (a retry after she beat you) straight back into her fight. */
    private fun restart(ch: Int = 1) {
        wild = null; depth = 0; beast = null; wash = null; rx = 1; ry = 1
        WW = 720f; WH = 528f; topY = 32f; gates.clear(); chunkArt.clear()
        spL = 240f; spT = 352f; spR = 480f; spB = 528f; visited.clear()
        hp = maxHp; invulnT = 0f; paraT = 0f; poisonT = 0f; invertT = 0f
        perishT = 0f; bossGone = false; spiritGift = false; spiritTalks = 0
        chapter = 1; bossRoom = 4; followT = 0f; seeds.clear(); lashes.clear(); mandalas.clear()
        bossHp = bossMax; stage = Stage.DAIS; bx = TX + 120f; by = TY + 64f; bossPara = 0f; bossWeak = 0f; bossConf = 0f
        bossShotT = 3f; spellT = 6f; summonT = 4f; castRune = -1; crackOpen = false; chestOpen = false
        minions.clear(); shots.clear(); wards.clear(); drawing.clear(); arcs.clear(); bursts.clear(); orbs.clear(); stroke.clear(); drops.clear(); battle = null; bagOpen = false
        fires.clear(); stars.clear(); fxs.clear(); beams.clear(); carried = null; spellCd.fill(0f); bossBurn = 0f; zapCount = 0; runePromptT = 0f; hasteT = 0f; bulwarkT = 0f
        spawnFriend(); saveSoon = true
        rx = 1; ry = 1; hx = 352f; hy = 472f; visited += 7; phase = Phase.FIGHT; bannerT = 1.8f; talking = false; orbT = 18f
        camX = (hx + 8 - W / 2f).coerceIn(0f, WW - W); camY = (hy + 12 - H / 2f).coerceIn(0f, WH - H)
        msg = "FIGHT!"; msgT = 1.2f
        if (ch == 2) { chapter = 2; bossHp = bossMax; crackOpen = true; seedT = 5f; lashT = 7f; summonT = 6f; spellT = 7f; msg = "SHE IS WAITING."; msgT = 1.6f }
    }

    private fun fire(sx: Float, sy: Float, tx: Float, ty: Float, speed: Float, boss: Boolean, weak: Boolean, conf: Boolean) {
        var a = atan2(ty - sy, tx - sx)
        if (conf) a = rnd.nextFloat() * 6.2832f else if (weak) a += (rnd.nextFloat() - 0.5f) * 1.8f
        shots += Shot(sx, sy, cos(a) * speed * SLOW, sin(a) * speed * SLOW, boss, weak)
    }

    /** The DUEL rune landed: everything in the hall stops while the turn-based duel plays out. */
    private fun startDuel() {
        castRune = -1; stroke.clear(); spellT = 10f + rnd.nextFloat() * 3f
        val her = chapter == 2 && blossomPose != null
        battle = HallBattle(if (her) blossomPose else duelPose, if (her) blossomRects else duelRects, font, bag, object : HallBattle.Host {
            override var playerHp: Int
                get() = hp
                set(v) { hp = v }
            override val playerMax get() = maxHp
            override val bossHp get() = this@HallScreen.bossHp
            override val bossMax get() = this@HallScreen.bossMax
            override fun hitBoss(n: Int): Boolean { damageBoss(n); return stage != Stage.DAIS || phase != Phase.FIGHT }
            override fun score(stroke: List<FloatArray>, rune: List<Pair<Float, Float>>) = similarityTo(stroke, rune, RuneShapes.closed(rune))
            override fun buzz() { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            override val power get() = powerK
            override fun backdrop(time: Float) { drawRoom(time); room.props.forEach { drawProp(it, time) } }
        }, queen = her, handX = 0.70f, handY = 0.28f)
        sfx.play(HallSfx.S.DUEL); sfx.rumbleBig()
    }

    private fun landSpell() {
        if (castRune == 3) { startDuel(); return }
        if (castRune == 4) {
            // her blessing, uncountered: she mends, and the garden grows
            bossHp = minOf(bossMax, bossHp + 40); repeat(3) { plantNear(hx + 8, hy + 13, 20f, 56f) }
            fxs += Fx(FX_MEND, bx, by - 30, 1.2f); fxs += Fx(FX_PETALS, bx, by - 30, 1f)
            msg = "SHE IS BLESSED! +40"; msgT = 1.6f; castRune = -1; spellT = 9f + rnd.nextFloat() * 3f
            sfx.play(HallSfx.S.MEND); sfx.rumbleSoft(); return
        }
        val her = chapter == 2
        when (castRune) { 0 -> { paraT = 3f; msg = if (her) "ROOTED!" else "BOUND!" }; 1 -> { poisonT = 6f; poisonTick = 1.5f; msg = if (her) "POLLEN! POISONED!" else "POISONED!" }; else -> { invertT = 7f; msg = if (her) "DAZED!" else "MIRRORED!" } }
        msgT = 1.4f; castRune = -1; spellT = (if (stage == Stage.CHASE) 10f else 9f) + rnd.nextFloat() * 3f
        sfx.play(HallSfx.S.HEX); sfx.rumbleBig()
    }

    private var saveSoon = false
    // =====================================================================================
    // Getting about: a flow field over the room in 8-pixel cells, rebuilt a few times a second from
    // where you stand, so foes walk round the furniture to reach you instead of pressing into it.
    // Three of them: for imps and stalkers, for the behemoth (much broader), and for the boss.
    // =====================================================================================
    private val GW = 60; private val GH = 44; private val CELL = 8f
    private val fields = Array(3) { IntArray(GW * GH) }
    private val fieldOk = BooleanArray(3)
    private val cellOk = ByteArray(GW * GH)
    private val fieldQ = IntArray(GW * GH)
    private var fieldT = 0f; private var fieldX = 0f; private var fieldY = 0f
    /** Where the field's window starts (in the wood it follows you). */
    private var fieldOX = 0f; private var fieldOY = 0f
    private val dir = FloatArray(2)
    private fun solidK(k: Int, x: Float, y: Float) = when (k) { 0 -> foeSolid(x, y, 3f); 1 -> foeSolid(x, y, 8f); else -> bossSolid(x, y) }
    private fun field(k: Int): IntArray {
        val f = fields[k]
        if (fieldOk[k]) return f
        fieldOk[k] = true
        java.util.Arrays.fill(f, Int.MAX_VALUE); java.util.Arrays.fill(cellOk, 0.toByte())
        fun open(i: Int, j: Int): Boolean {
            val c = j * GW + i
            if (cellOk[c] == 0.toByte()) cellOk[c] = if (solidK(k, fieldOX + (i + 0.5f) * CELL, fieldOY + (j + 0.5f) * CELL)) 2 else 1
            return cellOk[c] == 1.toByte()
        }
        val si = ((fieldX - fieldOX) / CELL).toInt().coerceIn(0, GW - 1); val sj = ((fieldY - fieldOY) / CELL).toInt().coerceIn(0, GH - 1)
        var head = 0; var tail = 0
        f[sj * GW + si] = 0; fieldQ[tail++] = sj * GW + si
        while (head < tail) {
            val c = fieldQ[head++]; val i = c % GW; val j = c / GW
            for (oj in -1..1) for (oi in -1..1) {
                if (oi == 0 && oj == 0) continue
                val ni = i + oi; val nj = j + oj
                if (ni < 0 || nj < 0 || ni >= GW || nj >= GH) continue
                val n = nj * GW + ni
                if (f[n] != Int.MAX_VALUE || !open(ni, nj)) continue
                if (oi != 0 && oj != 0 && (!open(i + oi, j) || !open(i, j + oj))) continue   // no cutting corners
                f[n] = f[c] + 1; fieldQ[tail++] = n
            }
        }
        return f
    }
    /** Nothing in the way between here and there (for a body of this kind)? */
    private fun clearPath(x: Float, y: Float, tx: Float, ty: Float, k: Int): Boolean {
        val n = (hypot(tx - x, ty - y) / 4f).toInt()
        for (i in 1..n) { val t = i.toFloat() / (n + 1); if (solidK(k, x + (tx - x) * t, y + (ty - y) * t)) return false }
        return true
    }
    /** Which way to head (into dir): straight at you if the way is clear, else downhill on the flow field. */
    private fun steer(x: Float, y: Float, tx: Float, ty: Float, k: Int) {
        val dx = tx - x; val dy = ty - y; val d = hypot(dx, dy).coerceAtLeast(0.01f)
        dir[0] = dx / d; dir[1] = dy / d
        if (clearPath(x, y, tx, ty, k)) return
        val f = field(k)
        if (x < fieldOX || y < fieldOY || x >= fieldOX + GW * CELL || y >= fieldOY + GH * CELL) return
        val ci = ((x - fieldOX) / CELL).toInt().coerceIn(0, GW - 1); val cj = ((y - fieldOY) / CELL).toInt().coerceIn(0, GH - 1)
        var best = f[cj * GW + ci]; var bi = -1; var bj = -1
        for (oj in -1..1) for (oi in -1..1) {
            if (oi == 0 && oj == 0) continue
            val i = ci + oi; val j = cj + oj
            if (i < 0 || j < 0 || i >= GW || j >= GH) continue
            val v = f[j * GW + i]
            if (v < best) { best = v; bi = i; bj = j }
        }
        if (bi < 0) return
        val gx = fieldOX + (bi + 0.5f) * CELL - x; val gy = fieldOY + (bj + 0.5f) * CELL - y; val g = hypot(gx, gy).coerceAtLeast(0.01f)
        dir[0] = gx / g; dir[1] = gy / g
    }
    /** A step, sliding along whatever's in the way (holy lines included). */
    private fun walk(m: Minion, ux: Float, uy: Float, sp: Float, r: Float): Boolean {
        val nx = m.x + ux * sp; val ny = m.y + uy * sp
        var moved = false
        if (!foeSolid(nx, m.y, r) && !wardBlocks(m.x, m.y, nx, m.y)) { m.x = nx; moved = true }
        if (!foeSolid(m.x, ny, r) && !wardBlocks(m.x, m.y, m.x, ny)) { m.y = ny; moved = true }
        if (abs(ux) > 0.25f) m.face = if (ux > 0f) 1f else -1f
        return moved
    }
    /** Head for (tx, ty): by the flow field when it's you they're after, else straight; a sideways detour when stuck. */
    private fun chase(m: Minion, tx: Float, ty: Float, sp: Float, k: Int, useField: Boolean, dt: Float) {
        val r = if (k == 1) 8f else 3f
        if (m.detT > 0f) { m.detT -= dt; if (!walk(m, m.dtx, m.dty, sp, r)) m.detT = 0f; return }
        if (useField) steer(m.x, m.y, tx, ty, k)
        else {
            val dx = tx - m.x; val dy = ty - m.y; val d = hypot(dx, dy).coerceAtLeast(0.01f); dir[0] = dx / d; dir[1] = dy / d
            // a wall in the way of somewhere near you: the field round it, toward you, then straight on
            if (!clearPath(m.x, m.y, tx, ty, k) && hypot(tx - fieldX, ty - fieldY) < 90f) steer(m.x, m.y, fieldX, fieldY, k)
        }
        val ux = dir[0]; val uy = dir[1]
        walk(m, ux, uy, sp, r)
        if (hypot(m.x - m.lx, m.y - m.ly) < sp * 0.3f) m.stuckT += dt else m.stuckT = 0f
        m.lx = m.x; m.ly = m.y
        if (m.stuckT > 0.35f) {
            // stuck: slide off sideways, whichever side is free (the open one first), and a little further each time
            m.stuckT = 0f
            val free1 = !foeSolid(m.x - uy * 6f, m.y + ux * 6f, r); val free2 = !foeSolid(m.x + uy * 6f, m.y - ux * 6f, r)
            val sgn = when { free1 && !free2 -> 1f; free2 && !free1 -> -1f; else -> if (rnd.nextBoolean()) 1f else -1f }
            m.dtx = -uy * sgn; m.dty = ux * sgn; m.detT = 0.5f + rnd.nextFloat() * 0.5f; fieldT = minOf(fieldT, 0.05f)
        }
    }

    private val BEH_SLAM_R = 34f
    /** The behemoth: lumbers after you, slams the floor when you're close; beaten down, he kneels (dazed) for a few seconds. */
    private val DRAKE_DROP = 1.8f; private val DRAKE_AIR = 1.7f; private val DRAKE_R = 30f
    /** The drake crashing down: everything under it is hurt and knocked about. */
    private fun drakeLand(m: Minion, px: Float, py: Float) {
        rings += Ring(m.x, m.y + 2); rings += Ring(m.x, m.y + 2); bursts += Burst(m.x - 12, m.y + 2); bursts += Burst(m.x + 12, m.y + 2)
        shakeT = maxOf(shakeT, 0.6f); flashT = 0.06f; sfx.play(HallSfx.S.SLAM); sfx.rumbleSlam()
        if (hypot(px - m.x, py + 3 - m.y) < DRAKE_R && m.weak <= 0f) hurt(18)
        eachComp { if (friendHere && hypot(frX - m.x, frY - m.y) < DRAKE_R) hurtFriend(12) }
        for (o in minions) if (o !== m && o.room == roomId && hypot(o.x - m.x, o.y - m.y) < DRAKE_R) { if (o.ally) hurtAlly(o, 14) else { val d = hypot(o.x - m.x, o.y - m.y).coerceAtLeast(1f); o.kx = (o.x - m.x) / d * 160f; o.ky = (o.y - m.y) / d * 160f; o.stun = maxOf(o.stun, 0.6f) } }
    }
    /** The drake's own part; true while it's in the air (nothing else happens then). */
    private fun updateDrake(m: Minion, dt: Float, px: Float, py: Float): Boolean {
        if (m.popT > 0f) {
            // swooping down onto the spot under its shadow, which slides toward you a little
            val was = m.popT; m.popT -= dt
            if (was > 0.6f) { val dx = px - m.x; val dy = py + 3 - m.y; val d = hypot(dx, dy).coerceAtLeast(1f); val sp = minOf(d, 40f * dt); if (!foeSolid(m.x + dx / d * sp, m.y + dy / d * sp, 8f)) { m.x += dx / d * sp; m.y += dy / d * sp } }
            if (m.popT <= 0f) { m.popT = 0f; drakeLand(m, px, py); say("A DRAKE! STAGGER IT, THEN CIRCLE IT!", 2.2f) }
            return true
        }
        if (m.airT > 0f) {
            m.airT -= dt
            val dx = px - m.x; val dy = py + 3 - m.y; val d = hypot(dx, dy).coerceAtLeast(1f)
            if (m.airT > 0.5f) { val sp = minOf(d, HERO_SPEED * 2.2f * SLOW * dt); val nx = m.x + dx / d * sp; val ny = m.y + dy / d * sp; if (!foeSolid(nx, ny, 8f)) { m.x = nx; m.y = ny }; m.face = if (dx > 0f) 1f else -1f }
            if (m.airT <= 0f) { m.airT = 0f; drakeLand(m, px, py); m.slamCd = 1.5f }
            return true
        }
        if (m.daze > 0f || m.para > 0f || m.ice > 0f || m.stun > 0f) return false
        val dx = px - m.x; val dy = py - m.y; val d = hypot(dx, dy)
        m.flyCd -= dt; m.breathCd -= dt
        if (m.flyCd <= 0f && d > 45f) { m.flyCd = 6f + rnd.nextFloat() * 4f; m.airT = DRAKE_AIR; m.slamT = 0f; sfx.play(HallSfx.S.SPRINT, 0.7f); say("IT TAKES TO THE AIR!", 1f); return true }
        if (m.breathCd <= 0f && d in 28f..120f) {
            m.breathCd = (if (m.rage > 0f) 2.2f else 3.4f) + rnd.nextFloat() * 1.5f
            val a = atan2(py - (m.y - 18), px - m.x); m.face = if (dx > 0f) 1f else -1f
            for (k in -2..2) fire(m.x + m.face * 6, m.y - 18, m.x + cos(a + k * 0.18f) * 60f, m.y - 18 + sin(a + k * 0.18f) * 60f, 70f, false, m.weak > 0f, m.conf > 0f)
            fxs += Fx(FX_NOVA, m.x + m.face * 8, m.y - 18, 0.3f); sfx.play(HallSfx.S.THUNDER, 0.35f)
        }
        return false
    }
    private fun updateBehemoth(m: Minion, dt: Float, px: Float, py: Float) {
        if (m.drake && updateDrake(m, dt, px, py)) return
        if (m.popT > 0f) {
            val was = m.popT; m.popT -= dt
            if (was > 1.2f && m.popT <= 1.2f) {
                // he bursts up through the floor
                shakeT = 0.8f; hurtFlashT = 0.4f; flashT = 0.08f
                say("A BEHEMOTH! STAGGER IT, THEN CIRCLE IT!", 2.2f)
                sfx.play(HallSfx.S.SLAM); sfx.play(HallSfx.S.THUNDER, 0.6f); sfx.rumbleSlam()
            }
            return
        }
        if (m.daze > 0f) {
            if (wash?.m !== m && seal?.m !== m) m.daze -= dt
            if (m.daze <= 0f) {
                // too slow: he heals and rises in a fury
                m.hp = BEH_HP; m.rage = 10f; m.slamCd = 1.2f
                fxs += Fx(FX_MEND, m.x, m.y - 14, 0.9f); shakeT = 0.4f; hurtFlashT = 0.2f
                say("IT HEALS AND RISES, ENRAGED!", 1.8f); sfx.play(HallSfx.S.SLAM); sfx.rumbleBig()
            }
            return
        }
        m.rage -= dt
        if (m.para > 0f || m.ice > 0f) return
        val rage = m.rage > 0f
        val ty = py + 3
        val d = hypot(px - m.x, ty - m.y)
        if (m.slamT > 0f) {
            m.slamT -= dt
            if (m.slamT <= 0f) {
                rings += Ring(m.x, m.y + 2); bursts += Burst(m.x - 10, m.y + 2); bursts += Burst(m.x + 10, m.y + 2)
                shakeT = maxOf(shakeT, 0.35f); sfx.play(HallSfx.S.STOMP); sfx.rumbleSoft()
                if (d < BEH_SLAM_R && m.weak <= 0f) hurt(20)
                eachComp { if (friendHere && hypot(frX - m.x, frY - m.y) < BEH_SLAM_R) hurtFriend(14) }
                for (o in minions) if (o.ally && o.room == roomId && hypot(o.x - m.x, o.y - m.y) < BEH_SLAM_R) hurtAlly(o, 18)
                m.slamCd = (if (rage) 1.6f else 2.8f) + rnd.nextFloat() * 1.6f
            }
            return
        }
        m.mood -= dt
        if (m.mood <= 0f && d in 50f..130f && rnd.nextFloat() < dt * 0.2f) { m.mood = 0.9f; shakeT = maxOf(shakeT, 0.1f); sfx.play(HallSfx.S.STOMP, 0.4f) }
        m.slamCd -= dt
        if (m.slamCd <= 0f && d < BEH_SLAM_R && m.stun <= 0f) { m.slamT = if (rage) 0.6f else 0.9f; sfx.play(HallSfx.S.BOSS_RUNE, 0.5f); return }
        if (m.stun > 0f) return
        if (d > 9f) {
            val sp = HERO_SPEED * (if (rage) 0.82f else 0.62f) * m.pace * (if (m.mood > 0f) 1.9f else 1f) * SLOW * dt * (if (m.slow > 0f) 0.5f else 1f)
            if (m.conf > 0f) { m.wt -= dt; if (m.wt <= 0f) { val a = rnd.nextFloat() * 6.2832f; m.wx = cos(a); m.wy = sin(a); m.wt = 0.8f }; walk(m, m.wx, m.wy, sp, 8f) }
            else chase(m, px, ty, sp, 1, true, dt)
            m.step += dt * (if (rage) 1.3f else 1f)
        }
        if (d < 14f && m.weak <= 0f) hurt(22)
        eachComp { if (friendHere && hypot(frX - m.x, frY - 6 - m.y) < 15f && m.weak <= 0f) hurtFriend(16) }
    }
    /** The gazer: an eye on bat wings. It flies over anything, hangs back, spits three at a time, and blinks to a new spot. */
    private fun gazeSolid(x: Float, y: Float) = x < 10f || x > WW - 10f || y < topY + 12f || y > WH - 8f
    private fun updateGazer(m: Minion, dt: Float, px: Float, py: Float) {
        m.step += dt; m.stT -= dt
        if (m.para > 0f || m.stun > 0f || m.ice > 0f) return
        val dx = px - m.x; val dy = py - m.y; val d = hypot(dx, dy).coerceAtLeast(0.01f)
        if (m.st == 1) {
            if (m.stT <= 0f) {
                for (k in 0 until 24) { val a = rnd.nextFloat() * 6.2832f; val r = 45f + rnd.nextFloat() * 35f; val nx = px + cos(a) * r; val ny = py + sin(a) * r * 0.8f; if (!gazeSolid(nx, ny)) { m.x = nx; m.y = ny; break } }
                m.st = 2; m.stT = 0.35f; bursts += Burst(m.x, m.y - 12)
            }
            return
        }
        if (m.st == 2) { if (m.stT <= 0f) { m.st = 0; m.stT = 4.5f + rnd.nextFloat() * 4f }; return }
        val want = 58f + m.flank * 10f
        val k = ((d - want) / want).coerceIn(-1f, 1f)
        var mx = dx / d * k + (-dy / d) * m.orbit * 0.7f
        var my = dy / d * k + (dx / d) * m.orbit * 0.7f
        if (m.conf > 0f) { m.wt -= dt; if (m.wt <= 0f) { val a = rnd.nextFloat() * 6.2832f; m.wx = cos(a); m.wy = sin(a); m.wt = 0.7f }; mx = m.wx; my = m.wy }
        val ml = hypot(mx, my).coerceAtLeast(0.01f)
        val sp = HERO_SPEED * 0.7f * m.pace * SLOW * dt * (if (m.slow > 0f) 0.5f else 1f)
        val nx = m.x + mx / ml * sp; val ny = m.y + my / ml * sp
        if (!gazeSolid(nx, m.y)) m.x = nx else m.orbit = -m.orbit
        if (!gazeSolid(m.x, ny)) m.y = ny else m.orbit = -m.orbit
        if (rnd.nextFloat() < dt * 0.3f) m.orbit = -m.orbit
        m.face = if (dx > 0f) 1f else -1f
        m.cd -= dt
        if (m.cd <= 0f && d < 160f) {
            m.cd = 2.2f + rnd.nextFloat() * 1.8f
            val a = atan2(py - (m.y - 12), px - m.x)
            for (sp3 in -1..1) fire(m.x, m.y - 12, m.x + cos(a + sp3 * 0.32f) * 60f, m.y - 12 + sin(a + sp3 * 0.32f) * 60f, 78f, false, m.weak > 0f, m.conf > 0f)
            sfx.play(HallSfx.S.ZAP, 0.35f)
        }
        if (m.stT <= 0f && m.conf <= 0f) { m.st = 1; m.stT = 0.45f }
    }
    /**
     * A shade of the fog: weak and mindless (it can't be brainwashed). It drifts toward you,
     * fades out (st 1), is gone a moment (st 2, can't be hit), and fades back in somewhere else (st 3).
     */
    private fun updateShade(m: Minion, dt: Float, px: Float, py: Float) {
        m.stT -= dt; m.step += dt; m.cd -= dt
        val dx = px - m.x; val dy = py - m.y; val d = hypot(dx, dy).coerceAtLeast(0.01f)
        when (m.st) {
            0 -> {
                if (m.para <= 0f && m.stun <= 0f && m.ice <= 0f) {
                    val sp = HERO_SPEED * 0.55f * m.pace * SLOW * dt * (if (m.slow > 0f) 0.5f else 1f)
                    val wob = sin(m.step * 2f + m.flank * 3f)
                    val nx = m.x + (dx / d + -dy / d * wob * 0.6f) * sp; val ny = m.y + (dy / d + dx / d * wob * 0.6f) * sp
                    if (!foeSolid(nx, m.y, 3f)) m.x = nx; if (!foeSolid(m.x, ny, 3f)) m.y = ny
                    m.face = if (dx > 0f) 1f else -1f
                    if (d < 10f && m.cd <= 0f && m.weak <= 0f) { m.cd = 1.2f; hurt(7) }
                    eachComp { if (friendHere && m.cd <= 0f && hypot(frX - m.x, frY - 6 - m.y) < 10f) { m.cd = 1.2f; hurtFriend(4) } }
                }
                if (m.stT <= 0f) { m.st = 1; m.stT = 0.5f }
            }
            1 -> if (m.stT <= 0f) { m.st = 2; m.stT = 0.8f + rnd.nextFloat() * 1.4f }
            2 -> if (m.stT <= 0f) {
                for (k in 0 until 20) { val a = rnd.nextFloat() * 6.2832f; val r = 40f + rnd.nextFloat() * 50f; val nx = px + cos(a) * r; val ny = py + sin(a) * r * 0.8f; if (!foeSolid(nx, ny, 3f)) { m.x = nx; m.y = ny; break } }
                m.st = 3; m.stT = 0.5f
            }
            else -> if (m.stT <= 0f) { m.st = 0; m.stT = 2f + rnd.nextFloat() * 2.5f }
        }
    }
    /** The stalker: circles you at a distance, crouches (a red line shows where), springs, then has to catch its breath. */
    private fun updateStalker(m: Minion, dt: Float, px: Float, py: Float) {
        if (m.para > 0f || m.stun > 0f || m.ice > 0f) return
        val dx = px - m.x; val dy = py - m.y; val d = hypot(dx, dy).coerceAtLeast(0.01f)
        val sl = if (m.slow > 0f) 0.5f else 1f
        m.stT -= dt
        when (m.st) {
            0 -> {
                val sp = HERO_SPEED * 1.05f * m.pace * SLOW * dt * sl
                if (m.conf > 0f) { m.wt -= dt; if (m.wt <= 0f) { val a = rnd.nextFloat() * 6.2832f; m.wx = cos(a); m.wy = sin(a); m.wt = 0.6f }; walk(m, m.wx, m.wy, sp, 3f) }
                else if (d > 70f || !clearPath(m.x, m.y, px, py, 0)) chase(m, px, py, sp, 0, true, dt)
                else {
                    // round and round, drawing in
                    val a = kotlin.math.atan2(m.y - py, m.x - px) + m.orbit * 0.8f
                    val rr = maxOf(24f, 40f + m.flank * 7f - (3.5f - m.stT.coerceIn(0f, 3.5f)) * 3f)
                    chase(m, px + cos(a) * rr, py + sin(a) * rr * 0.8f, sp, 0, false, dt)
                }
                m.step += dt * 1.3f
                if (m.stT <= 0f && d < 95f && m.conf <= 0f && clearPath(m.x, m.y, px, py, 0)) {
                    m.st = 1; m.stT = 0.4f + rnd.nextFloat() * 0.35f; m.dvx = dx / d; m.dvy = dy / d; m.face = if (dx > 0f) 1f else -1f
                    sfx.play(HallSfx.S.BOSS_RUNE, 0.35f)
                }
                if (d < 8f && m.weak <= 0f) hurt(13)
            }
            1 -> if (m.stT <= 0f) {
                if (rnd.nextFloat() < 0.22f) { m.st = 0; m.stT = 0.5f + rnd.nextFloat() * 0.6f; m.orbit = -m.orbit }   // a feint
                else {
                    // re-aim at the last moment, sometimes
                    if (rnd.nextFloat() < 0.5f) { m.dvx = dx / d; m.dvy = dy / d }
                    m.st = 2; m.stT = 0.5f; m.lunged = false; sfx.play(HallSfx.S.SPRINT, 0.7f)
                }
            }
            2 -> {
                val sp = HERO_SPEED * 3.2f * SLOW * dt * sl
                val nx = m.x + m.dvx * sp; val ny = m.y + m.dvy * sp
                if (foeSolid(nx, ny, 3f) || wardBlocks(m.x, m.y, nx, ny)) m.stT = 0f else { m.x = nx; m.y = ny }
                if (!m.lunged && hypot(px - m.x, py - m.y) < 10f && m.weak <= 0f) { m.lunged = true; hurt(14) }
                eachComp { if (!m.lunged && friendHere && hypot(frX - m.x, frY - 6 - m.y) < 12f) { m.lunged = true; hurtFriend(10) } }
                if (!m.lunged) for (o in minions) if (o.ally && o.room == roomId && hypot(o.x - m.x, o.y - m.y) < 10f) { m.lunged = true; hurtAlly(o, 14); break }
                m.step += dt * 2f
                if (m.stT <= 0f) { m.st = 3; m.stT = 0.75f }
            }
            else -> if (m.stT <= 0f) { m.st = 0; m.stT = 1.6f + rnd.nextFloat() * 1.6f; if (rnd.nextFloat() < 0.4f) m.orbit = -m.orbit }
        }
    }

    private fun update(dt: Float) {
        if (!loaded) { loaded = true; loadGame() }
        if (choosing) return
        if (friend == null) spawnFriend()
        if (saveSoon) { saveSoon = false; saveGame() }
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
            flyTX = (hx + 8 + cos(bumA) * rad + sin(tm * 2.3f) * 3f).coerceIn(4f, WW - 4f)
            flyTY = (hy + 2 + sin(bumA) * rad * 0.7f + cos(tm * 1.9f) * 3f).coerceIn(12f, WH - 4f)
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
            val sp = HERO_SPEED * SLOW * dt * (if (sprintT > 0f) 2.4f else 1f) * (if (swiftT > 0f) 1.3f else 1f) * (if (bag.wearing(Loot.MOSS_BOOTS)) 1.08f else 1f)
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
                for (m in minions) if (m.room == roomId && !m.ally && m.dash != dashId && hypot(m.x - fx0, m.y - (hy + 9)) < 13f) { m.dash = dashId; hitImp(m, 2); charge(2); m.burn = 1.8f; m.burnTick = 0.7f; bursts += Burst(m.x, m.y) }
                if (bigHere && bossDash != dashId && hypot(gx - fx0, gy - 4 - fy0) < 16f) { bossDash = dashId; damageBoss(8); bossBurn = 2.5f; bossBurnTick = 0.7f }
                killDead()
            }
        }
        if (wild != null) {
            val wl = wild!!
            val cx = ((hx + 8) / 240f).toInt().coerceIn(0, wl.w - 1); val cy = ((hy + 12) / 176f).toInt().coerceIn(0, wl.h - 1)
            if (cx != rx || cy != ry) enterStretch(cx, cy)
        } else {
            val cx = ((hx + 8) / 240f).toInt().coerceIn(0, 2); val cy = ((hy + 12) / 176f).toInt().coerceIn(0, 2)
            if (cx != rx || cy - 1 != ry) enterZone(cx, cy - 1)
        }

        if (phase == Phase.WON && perishT > 0f && !bossGone) {
            perishT += dt
            if (perishT >= PERISH) {
                bossGone = true
                if (chapter == 2) {
                    drops += Drop(bx, by - 6, bossRoom, Loot.BLOSSOM_DAGGER).also { it.life = 1e9f }
                    chestOpen = false
                    msg = "SHE IS GONE. HER DAGGER REMAINS, AND THE CHEST HAS FILLED AGAIN."; msgT = 3f
                } else { drops += Drop(bx, by - 6, bossRoom, Loot.FIVE_EYED_AXE).also { it.life = 1e9f }; msg = "HE IS GONE. HIS AXE REMAINS."; msgT = 2.4f }
                saveSoon = true
            }
        }
        if (phase == Phase.WON) {
            // his axe (and anything else) can still be picked up after the fight
            drops.firstOrNull { it.room == roomId && hypot(it.x - (hx + 8), it.y - (hy + 11)) < 12f }?.let { d -> drops.remove(d); bursts += Burst(d.x, d.y); msg = takeLoot(d.item); msgT = 2.4f }
        }
        run {
            // the view follows you, easing a little
            var tx = (hx + 8 - W / 2f).coerceIn(0f, WW - W); var ty = (hy + 12 - H / 2f).coerceIn(0f, WH - H)
            // a boss's room is a room: the view holds still on it, and you leave by its sides
            val wl0 = wild
            if (wl0 != null && wl0.bossLevel && ry * wl0.w + rx == wl0.exit) { tx = rx * 240f; ty = ry * 176f }
            else if (wl0 == null && zone == 4) { tx = TX; ty = TY }
            camX += (tx - camX) * minOf(1f, dt * 10f); camY += (ty - camY) * minOf(1f, dt * 10f)
        }
        updateFriend(dt)
        if (chapter == 2 && phase == Phase.INTRO && throne && !talking && talkLines !== RANT2) { talking = true; talkLines = RANT2; line = 0; shown = 0f; sfx.play(HallSfx.S.BOSS_RUNE) }
        if (phase != Phase.FIGHT || talking) return
        invulnT -= dt; paraT -= dt; invertT -= dt
        if (poisonT > 0f) { poisonT -= dt; poisonTick -= dt; if (poisonTick <= 0f) { poisonTick = 1.5f; invulnT = 0f; hurt(8) } }
        val px = hx + 8; val py = hy + 9

        // balls of light, now and then, one at a time
        orbT -= dt
        if (orbT <= 0f) { orbT = (36f + rnd.nextFloat() * 20f) * (if (bag.wearing(Loot.LANTERN)) 0.5f else 1f); if ((wild != null || zone != 1) && orbs.none { it.room == roomId }) spawnOrb() }
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

        if (chapter == 2 && phase == Phase.FIGHT && stage == Stage.CHASE && !bossHere && followT > 0f) {
            followT -= dt
            if (followT <= 0f) {
                // her last stand: she comes after you, through the same door
                bossRoom = roomId; bx = entryX.coerceIn(16f, WW - 16f); by = entryY.coerceIn(44f, WH - 4f)
                repeat(40) { if (bossSolid(bx, by)) { bx += (TX + 120f - bx) * 0.12f; by += (TY + 112f - by) * 0.12f } }
                castRune = -1; fxs += Fx(FX_PETALS, bx, by - 20, 1f)
                msg = "SHE FOLLOWS YOU IN!"; msgT = 1.6f; sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleBig()
            }
        }
        if (bossHere) { if (chapter == 2) updateBlossom(dt, px, py) else updateBoss(dt, px, py) }
        if (inWild) { wildChecks(dt); updateBeast(dt, px, py) }
        updateWash(dt); updateSeal(dt)
        if (inWild) updateNpcs(dt)
        if (furyT > 0f) { furyT -= dt; furyRingT -= dt; if (furyT <= 0f) { furyRing = null; say("THE FURY FADES", 1.2f) } }
        updateGarden(dt, px, py)
        if (phase != Phase.FIGHT) return

        fieldT -= dt
        if (fieldT <= 0f) {
            fieldT = 0.25f; fieldOk.fill(false); fieldX = px; fieldY = py
            fieldOX = (px - GW * CELL / 2f).coerceIn(0f, maxOf(0f, WW - GW * CELL)); fieldOY = (py - GH * CELL / 2f).coerceIn(0f, maxOf(0f, WH - GH * CELL))
        }
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
            if (!m.ally && hypot(m.x - px, m.y - py) > 230f) continue     // far off: asleep
            if (m.ally) { updateAlly(m, dt, px, py); continue }
            if (m.kind == 2) { updateBehemoth(m, dt, px, py); continue }
            if (m.hp <= 0) continue
            if (m.kind == 4) { updateShade(m, dt, px, py); continue }
            if (m.kind == 1) { updateStalker(m, dt, px, py); continue }
            if (m.kind == 3) { updateGazer(m, dt, px, py); continue }
            if (m.para > 0f || m.stun > 0f || m.ice > 0f) continue
            pickComp(m.x, m.y)
            val dF = if (friendHere) hypot(frX - m.x, frY - 8 - m.y) else Float.MAX_VALUE
            val dH = hypot(px - m.x, py - m.y)
            var foe: Minion? = null; var dA = Float.MAX_VALUE
            for (o in minions) if (o.ally && o.room == roomId && o.hp > 0) { val dd = hypot(o.x - m.x, o.y - m.y); if (dd < dA) { dA = dd; foe = o } }
            val toAlly = foe != null && dA < minOf(dH, dF) * 0.8f
            val toFriend = !toAlly && friendHere && ((tauntT > 0f && dF < 70f) || dF < dH * 0.7f)
            val tx = if (toAlly) foe!!.x else if (toFriend) frX else px; val ty = if (toAlly) foe!!.y else if (toFriend) frY - 8 else py
            val dx = tx - m.x; val dy = ty - m.y; val d = hypot(dx, dy).coerceAtLeast(0.01f)
            var ux = dx / d; var uy = dy / d
            if (m.conf > 0f) {
                m.wt -= dt
                if (m.wt <= 0f) { val a = rnd.nextFloat() * 6.2832f; m.wx = cos(a); m.wy = sin(a); m.wt = 0.7f }
                ux = m.wx; uy = m.wy
            }
            // whims: now and then a pause, a sidestep or a sudden rush; and a dodge when your light comes close
            m.mood -= dt
            if (m.mood <= 0f && rnd.nextFloat() < dt * 0.45f) { m.moodKind = rnd.nextInt(3); m.mood = 0.25f + rnd.nextFloat() * 0.5f; val a = rnd.nextFloat() * 6.2832f; m.mx = cos(a); m.my = sin(a) }
            if (m.mood <= 0f && hypot(flyX - m.x, flyY - m.y) < 24f && rnd.nextFloat() < dt * 4f) { m.moodKind = 1; m.mood = 0.35f; val sg = if (rnd.nextBoolean()) 1f else -1f; m.mx = -uy * sg; m.my = ux * sg }
            if (d > 7f || m.conf > 0f) {
                // quicker than they were, and they find their way round things to get at you
                val sp = 16f * 1.1f * 1.25f * m.pace * SLOW * dt * (if (m.slow > 0f) 0.5f else 1f)
                when {
                    m.conf > 0f -> walk(m, ux, uy, sp, 3f)
                    m.mood > 0f && m.moodKind == 0 -> {}
                    m.mood > 0f && m.moodKind == 1 -> walk(m, m.mx, m.my, sp * 1.7f, 3f)
                    !toAlly && !toFriend && m.style == 1 && d < 46f -> walk(m, -ux, -uy, sp * 0.9f, 3f)     // a skirmisher keeps its distance
                    !toAlly && !toFriend && m.style == 2 && d > 34f -> {
                        // a flanker swings round to come at you from the side
                        val a = atan2(m.y - ty, m.x - tx) + m.flank
                        chase(m, tx + cos(a) * 24f, ty + sin(a) * 18f, sp * 1.1f, 0, false, dt)
                    }
                    else -> chase(m, tx, ty, sp * (if (m.mood > 0f && m.moodKind == 2) 1.9f else 1f), 0, !toFriend && !toAlly, dt)
                }
                m.step += dt
            }
            if (d <= (if (toFriend) 10f else 8f) && m.weak <= 0f && m.stun <= 0f) { if (toAlly) hurtAlly(foe!!, 10) else if (toFriend) hurtFriend(if (friend == Friend.GRUMBLE) 6 else 8) else hurt(16) }
            m.cd -= dt
            if (m.cd <= 0f && d < 140f) {
                m.cd = (1.5f + rnd.nextFloat() * 2f) * (if (m.style == 1) 0.65f else 1f)
                // aim a little ahead of you, or a little off, depending
                val lead = if (rnd.nextFloat() < 0.4f) 14f else 0f
                fire(m.x, m.y, tx + dirX * lead + (rnd.nextFloat() - 0.5f) * 10f, ty + dirY * lead + (rnd.nextFloat() - 0.5f) * 10f, 84f + rnd.nextFloat() * 20f, false, m.weak > 0f, m.conf > 0f)
            }
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
                if (tm0 != null) { if (tm0.hp > 0 && tm0 in minions) { hitImp(tm0, st.dmg, true); charge(st.dmg) } }
                else if (bossHere && phase == Phase.FIGHT) damageBoss(st.dmg)
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
            if (s.life <= 0f || s.x < -4 || s.x > WW + 4 || s.y < 0 || s.y > WH + 4 || shotStopped(s.x, s.y)) { iter.remove(); continue }
            if (s.ally) {
                // an ally's pebble: it flies at your foes
                val t = minions.firstOrNull { it.room == roomId && !it.ally && it.hp > 0 && abs(it.x - s.x) < 6f && abs(it.y - s.y) < 7f }
                if (t != null) { hitImp(t, 1, true); bursts += Burst(s.x, s.y); iter.remove(); killDead() }
                else if (bigHere && abs(gx - s.x) < 14f && s.y > gy - 40f && s.y < gy) { damageBoss(2); bursts += Burst(s.x, s.y); iter.remove() }
                continue
            }
            val hitAlly = minions.firstOrNull { it.ally && it.room == roomId && it.hp > 0 && abs(it.x - s.x) < 6f && abs(it.y - s.y) < 7f }
            if (hitAlly != null) { if (!s.weak) hurtAlly(hitAlly, if (s.boss) 12 else 8); iter.remove() }
            else if (s.x > hx + 3 && s.x < hx + 13 && s.y > hy + 2 && s.y < hy + 15) { if (!s.weak) hurt(if (s.petal) 8 else if (s.boss) 15 else 10); iter.remove() }
            else { var hitF = false; eachComp { if (!hitF && friendHere && abs(s.x - frX) < 8f && s.y > frY - 16f && s.y < frY + 1f) { if (!s.weak) hurtFriend(if (s.boss) 12 else 8); hitF = true } }; if (hitF) iter.remove() }
        }
    }

    private fun updateBoss(dt: Float, px: Float, py: Float) {
        bossPara -= dt; bossWeak -= dt; bossConf -= dt; bossFireCd -= dt
        if (bossBurn > 0f) { bossBurn -= dt; bossBurnTick -= dt; if (bossBurnTick <= 0f) { bossBurnTick = 0.7f; damageBoss(3); if (burnSfxT <= 0f) { burnSfxT = 0.3f; sfx.play(HallSfx.S.BURN, 0.5f) } } }
        if (phase != Phase.FIGHT) return
        holdBossSpells()
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
                    if (minions.count { it.room == roomId } < 5) { spawnMinion((TX + 96f) to (TY + 100f)); spawnMinion((TX + 144f) to (TY + 100f)) }
                }
            }
            Stage.SLAM -> {
                slamT += dt
                if (slamT > 0.9f && !crackOpen) { crackOpen = true; shakeT = 0.6f; msg = "CRACK!"; msgT = 1.2f; sfx.play(HallSfx.S.SLAM); sfx.rumbleSlam() }
                if (slamT > 1.6f) by = TY + 64f + (108f - 64f) * ((slamT - 1.6f) / 0.6f).coerceAtMost(1f)
                if (slamT > 2.2f) { stage = Stage.CHASE; by = TY + 108f; spellT = 5f; msg = "LAST STAND!"; msgT = 1.6f; shakeT = 0.3f; sfx.play(HallSfx.S.STOMP); sfx.rumbleBig() }
            }
            Stage.CHASE -> {
                if (castRune >= 0) { castT -= dt; if (castT <= 0f) landSpell(); return }
                if (bossPara > 0f) return
                spellT -= dt
                if (spellT <= 0f) { castRune = 2; castT = 7f; stroke.clear(); lastScore = -1f; runePromptT = 0f; sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleSoft(); return }
                // now and then, rarely, a bolt as he comes
                bossShotT -= dt
                if (bossShotT <= 0f) { bossShotT = 5f + rnd.nextFloat() * 4f; fire(bx, by - 30, px, py, 116f, true, bossWeak > 0f, bossConf > 0f) }
                steer(bx, by, px, py + 4, 2)
                var mx = dir[0]; var my = dir[1]
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
                eachComp { if (bossWeak <= 0f && friendHere && hypot(bx - frX, (by - 6) - (frY - 8)) < 18f) hurtFriend(22) }
                // walking through the firewall
                if (bossFireCd <= 0f && fires.any { it.room == roomId && abs(it.x - bx) < 11f && abs(it.y - by) < 6f }) { bossFireCd = 0.7f; damageBoss(5); bossBurn = 2.5f; bossBurnTick = 0.7f }
            }
        }
    }

    /** R, Vital Spark: magic arcs from the firefly through up to three imps (and him), each strike bursting. */
    private fun zap() {
        if (phase != Phase.FIGHT || talking || zapCd > 0f) return
        zapCd = 0.225f * (if (bag.wearing(Loot.QUICK_GLOVE)) 0.8f else 1f)
        var fromX = flyX; var fromY = flyY
        val star = bag.wearing(Loot.STARHEART)
        val reach = (if (bag.wearing(Loot.EMBER_RING)) 38f else 32f) + (if (star) 6f else 0f) + flySize() * 2f
        val bite = 2 + (if (bag.wearing(Loot.EMBER_RING)) 1 else 0) + (if (star) 1 else 0) + (if (bag.wearing(Loot.SPARK_FLINT)) 1 else 0)
        val chain = when { star -> 6; bag.wearing(Loot.STORM_RING) -> 5; else -> 3 }
        val splash = if (star) 20f else SPLASH
        val hit = minions.filter { it.room == roomId && !it.ally && hypot(it.x - flyX, it.y - flyY) < reach }.sortedBy { hypot(it.x - flyX, it.y - flyY) }.take(chain)
        val struck = HashSet<Minion>()
        fun burst(x: Float, y: Float) {
            bursts += Burst(x, y)
            if (star) fxs += Fx(FX_NOVA_STAR, x, y, 0.35f)
            for (m in minions) if (m.room == roomId && !m.ally && hypot(m.x - x, m.y - y) < splash && struck.add(m)) { val d0 = pw(bite); hitImp(m, d0, true); charge(d0) }
            seeds.removeAll { sd -> (sd.room == roomId && hypot(sd.x - x, sd.y - 6 - y) < splash).also { if (it) fxs += Fx(FX_PETALS, sd.x, sd.y - 6, 0.5f) } }
        }
        burst(flyX, flyY)
        for (m in hit) { arcs += Arc(fromX, fromY, m.x, m.y); fromX = m.x; fromY = m.y; if (struck.add(m)) { val d0 = pw(bite); hitImp(m, d0, true); charge(d0) }; burst(m.x, m.y) }
        if (bag.wearing(Loot.BLOSSOM_DAGGER) && struck.isNotEmpty() && hp < maxHp) { hp = minOf(maxHp, hp + minOf(2, (struck.size + 1) / 2)); fxs += Fx(FX_MEND, hx + 8, hy + 6, 0.4f) }
        if (bigHere && hypot(gx - flyX, gy - 24 - flyY) < 34f + flySize() * 2f) {
            arcs += Arc(flyX, flyY, gx, gy - 24); bursts += Burst(gx, gy - 24); val d1 = pw(if (star) 12 else 9); damageBoss(d1); charge(d1)
        }
        // touching a companion with the light mends it a little
        eachComp {
            val fk = friend
            if (fk != null && friendHere && frHp < fk.maxHp && hypot(frX - flyX, frY - 10 - flyY) < reach) {
                val heal = maxOf(2, fk.maxHp / 60)
                frHp = minOf(fk.maxHp, frHp + heal)
                arcs += Arc(flyX, flyY, frX, frY - 10); fxs += Fx(FX_MEND, frX, frY - 8, 0.5f)
                sfx.play(HallSfx.S.ORB, 0.5f)
            }
        }
        // brainwashed allies mend as well, at about half the rate a companion does
        var mended = false
        for (m in minions) if (m.ally && m.room == roomId && m.hp in 1 until m.maxHp && hypot(m.x - flyX, m.y - 6 - flyY) < reach) {
            m.hp = minOf(m.maxHp, m.hp + maxOf(1, (friend?.maxHp ?: 180) / 120)); mended = true
            arcs += Arc(flyX, flyY, m.x, m.y - 6); fxs += Fx(FX_MEND, m.x, m.y - 6, 0.4f)
        }
        if (mended) sfx.play(HallSfx.S.ORB, 0.4f)
        sfx.play(HallSfx.S.ZAP, 0.8f)
        // the Thunder Crown: every fourth spark, lightning out of the sky onto everything in the room
        if (bag.wearing(Loot.THUNDER_CROWN) && ++zapCount % 4 == 0) {
            var d = 0f
            for (m in minions) if (m.room == roomId && !m.ally) { stars += Star(m.x, m.y, m, d, true, 2); d += 0.05f }
            if (bigHere) stars += Star(gx, gy - 24, null, d, true, 10)
            sfx.play(HallSfx.S.THUNDER); flashT = 0.12f; shakeT = 0.2f; sfx.rumbleBig()
        }
        killDead()
    }

    // =====================================================================================
    // The Blossom: from her dais she throws fans of petals, sows seeds that grow into thorn
    // flowers, lashes vines along the floor at you, and raises her hand in BLESSING (trace the
    // leaf to break it, or she mends and the garden spreads). At her last stand she comes down in
    // a whirl of petals and follows you from room to room, leaving flowers where she walks.
    // =====================================================================================
    private fun petalFan(n: Int, spread: Float) {
        val sx = bx; val sy = by - 26
        val a0 = atan2((hy + 9) - sy, (hx + 8) - sx)
        for (i in 0 until n) {
            val a = if (bossConf > 0f) rnd.nextFloat() * 6.2832f else a0 + (i - (n - 1) / 2f) * spread / maxOf(1, n - 1) + (if (bossWeak > 0f) (rnd.nextFloat() - 0.5f) * 1.2f else 0f)
            shots += Shot(sx, sy, cos(a) * 72f * SLOW, sin(a) * 72f * SLOW, true, bossWeak > 0f).also { it.petal = true }
        }
        sfx.play(HallSfx.S.SPELL, 0.5f)
    }
    private fun plantNear(px: Float, py: Float, near: Float, far: Float) {
        if (seeds.count { it.room == roomId } >= 8) return
        repeat(20) {
            val a = rnd.nextFloat() * 6.2832f; val d = near + rnd.nextFloat() * (far - near)
            val x = px + cos(a) * d; val y = py + sin(a) * d * 0.7f
            if (!impSolid(x, y)) { seeds += Seed(x, y, roomId); return }
        }
    }
    private fun updateBlossom(dt: Float, px: Float, py: Float) {
        bossPara -= dt; bossWeak -= dt; bossConf -= dt; bossFireCd -= dt
        if (bossBurn > 0f) { bossBurn -= dt; bossBurnTick -= dt; if (bossBurnTick <= 0f) { bossBurnTick = 0.7f; damageBoss(3) } }
        if (phase != Phase.FIGHT) return
        holdBossSpells()
        seedT -= dt; lashT -= dt
        when (stage) {
            Stage.DAIS -> {
                if (castRune >= 0) { castT -= dt; if (castT <= 0f) landSpell(); return }
                if (bossPara > 0f) return
                bossShotT -= dt
                if (bossShotT <= 0f) { bossShotT = 2.6f + rnd.nextFloat(); petalFan(5, 0.9f) }
                if (seedT <= 0f) { seedT = 6.5f; repeat(2) { plantNear(hx + 8, hy + 13, 22f, 50f) } }
                mandalaT -= dt
                if (mandalaT <= 0f) { mandalaT = 11f + rnd.nextFloat() * 4f; mandalas += Mandala(hx + 8, hy + 13, roomId); say("A PATTERN BLOOMS BENEATH YOU!", 1.2f); sfx.play(HallSfx.S.BOSS_RUNE, 0.6f) }
                if (lashT <= 0f) {
                    lashT = 8f + rnd.nextFloat() * 3f
                    val dx = px - bx; val dy = (py + 4) - by; val d = hypot(dx, dy).coerceAtLeast(1f); val len = minOf(130f, d + 20f)
                    lashes += Lash(bx, by, bx + dx / d * len, by + dy / d * len, roomId); sfx.play(HallSfx.S.SPELL)
                }
                spellT -= dt
                if (spellT <= 0f) {
                    val r = rnd.nextFloat()
                    castRune = when { r < 0.2f -> 3; r < 0.5f -> 4; else -> rnd.nextInt(3) }
                    castT = if (castRune == 3) 8f else 7f; stroke.clear(); lastScore = -1f; runePromptT = 0f
                    sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleSoft()
                    if (castRune == 4) say("SHE RAISES HER HAND IN BLESSING...", 1.6f)
                }
                summonT -= dt
                if (summonT <= 0f) { summonT = 14f; if (minions.count { it.room == roomId } < 3) spawnMinion((TX + 120f) to (TY + 100f)) }
            }
            Stage.SLAM -> {
                val was = slamT; slamT += dt
                if (was < 0.6f && slamT >= 0.6f) { shakeT = 0.5f; fxs += Fx(FX_PETALS, bx, by - 30, 1.4f); flashT = 0.2f; say("SHE DESCENDS IN A WHIRL OF PETALS!", 1.6f); sfx.play(HallSfx.S.EXOTIC); sfx.rumbleBig() }
                if (slamT > 1.2f) by = TY + 64f + (108f - 64f) * ((slamT - 1.2f) / 0.8f).coerceAtMost(1f)
                if (slamT > 2.0f) { stage = Stage.CHASE; by = TY + 108f; spellT = 6f; seedT = 3f; msg = "LAST STAND! SHE WILL FOLLOW YOU"; msgT = 2f; sfx.play(HallSfx.S.STOMP); sfx.rumbleBig() }
            }
            Stage.CHASE -> {
                if (castRune >= 0) { castT -= dt; if (castT <= 0f) landSpell(); return }
                if (bossPara > 0f) return
                spellT -= dt
                if (spellT <= 0f) {
                    castRune = if (rnd.nextFloat() < 0.5f) 2 else 4; castT = 7f; stroke.clear(); lastScore = -1f; runePromptT = 0f
                    sfx.play(HallSfx.S.BOSS_RUNE); sfx.rumbleSoft(); if (castRune == 4) say("SHE RAISES HER HAND IN BLESSING...", 1.6f); return
                }
                bossShotT -= dt
                if (bossShotT <= 0f) { bossShotT = 5f + rnd.nextFloat() * 3f; petalFan(7, 1.3f) }
                if (seedT <= 0f) { seedT = 4f; if (seeds.count { it.room == roomId } < 8) seeds += Seed(bx, by + 2, roomId) }
                mandalaT -= dt
                if (mandalaT <= 0f) { mandalaT = 8f + rnd.nextFloat() * 3f; mandalas += Mandala(hx + 8, hy + 13, roomId); sfx.play(HallSfx.S.BOSS_RUNE, 0.6f) }
                steer(bx, by, px, py + 4, 2)
                var mx = dir[0]; var my = dir[1]
                if (bossConf > 0f || bWanderT > 0f) {
                    bWanderT -= dt
                    if (bossConf > 0f && bWanderT <= 0f) { val a = rnd.nextFloat() * 6.2832f; bwx = cos(a); bwy = sin(a); bWanderT = 0.8f }
                    mx = bwx; my = bwy
                }
                val sp = HERO_SPEED * 0.8f * SLOW * dt
                val nx = bx + mx * sp; val ny = by + my * sp
                var moved = false
                if (!bossSolid(nx, by) && !wardBlocks(bx, by, nx, by)) { bx = nx; moved = true }
                if (!bossSolid(bx, ny) && !wardBlocks(bx, by, bx, ny)) { by = ny; moved = true }
                if (!moved && bWanderT <= 0f) { val sgn = if (rnd.nextBoolean()) 1f else -1f; bwx = -my * sgn; bwy = mx * sgn; bWanderT = 0.7f }
                // her dagger, if she reaches you
                if (bossWeak <= 0f && hypot(bx - px, (by - 6) - (hy + 12)) < 13f) hurt(26)
                eachComp { if (bossWeak <= 0f && friendHere && hypot(bx - frX, (by - 6) - (frY - 7)) < 15f) hurtFriend(20) }
                if (bossFireCd <= 0f && fires.any { it.room == roomId && abs(it.x - bx) < 11f && abs(it.y - by) < 6f }) { bossFireCd = 0.7f; damageBoss(5); bossBurn = 2.5f; bossBurnTick = 0.7f }
            }
        }
    }
    /** Seeds sprouting into thorn flowers; vine lashes bursting along the floor. */
    private fun updateGarden(dt: Float, px: Float, py: Float) {
        val it = seeds.iterator()
        while (it.hasNext()) {
            val sd = it.next()
            if (sd.room != roomId) continue
            sd.t += dt; sd.life -= dt; sd.rootCd -= dt
            if (sd.life <= 0f || fires.any { f -> f.room == roomId && abs(f.x - sd.x) < 6f && abs(f.y - sd.y) < 6f }) { it.remove(); fxs += Fx(FX_PETALS, sd.x, sd.y - 6, 0.5f); continue }
            if (sd.t < 3f || phase != Phase.FIGHT) continue
            sd.shotT -= dt
            if (sd.shotT <= 0f) {
                sd.shotT = 3.5f
                val a0 = atan2(py - (sd.y - 9), px - sd.x)
                for (k in -1..1) shots += Shot(sd.x, sd.y - 9, cos(a0 + k * 0.28f) * 64f * SLOW, sin(a0 + k * 0.28f) * 64f * SLOW, false, false).also { s0 -> s0.petal = true }
            }
            if (sd.rootCd <= 0f && hypot(sd.x - px, sd.y - (hy + 14)) < 10f) { sd.rootCd = 2.5f; paraT = maxOf(paraT, 1f); hurt(6); say("THE THORNS HOLD YOU!", 1f) }
        }
        val mi = mandalas.iterator()
        while (mi.hasNext()) {
            val m = mi.next()
            m.t += dt
            if (m.t >= MANDALA_CHARGE && !m.hit) {
                m.hit = true; shakeT = maxOf(shakeT, 0.15f); flashT = maxOf(flashT, 0.1f); sfx.play(HallSfx.S.STARFALL, 0.6f)
                fxs += Fx(FX_PETALS, m.x, m.y - 8, 0.7f)
                if (m.room == roomId && phase == Phase.FIGHT) {
                    if (hypot((hx + 8 - m.x) / MANDALA_R, (hy + 13 - m.y) / (MANDALA_R * 0.55f)) < 1f) hurt(16)
                    eachComp { if (friendHere && hypot((frX - m.x) / MANDALA_R, (frY - m.y) / (MANDALA_R * 0.55f)) < 1f) hurtFriend(12) }
                }
            }
            if (m.t >= MANDALA_CHARGE + 0.5f) mi.remove()
        }
        val li = lashes.iterator()
        while (li.hasNext()) {
            val l = li.next()
            l.t += dt
            if (l.t >= 0.75f && !l.hit) {
                l.hit = true; shakeT = maxOf(shakeT, 0.12f); sfx.play(HallSfx.S.STOMP, 0.6f)
                if (l.room == roomId && phase == Phase.FIGHT) {
                    if (segDist(px, hy + 12, l) < 8f) hurt(14)
                    eachComp { if (friendHere && segDist(frX, frY - 4, l) < 9f) hurtFriend(10) }
                }
            }
            if (l.t >= 1.4f) li.remove()
        }
    }
    private fun segDist(x: Float, y: Float, l: Lash): Float {
        val dx = l.x1 - l.x0; val dy = l.y1 - l.y0; val L2 = (dx * dx + dy * dy).coerceAtLeast(0.01f)
        val t = (((x - l.x0) * dx + (y - l.y0) * dy) / L2).coerceIn(0f, 1f)
        return hypot(x - (l.x0 + dx * t), y - (l.y0 + dy * t))
    }
    private fun beginChapter2() {
        chapter = 2; phase = Phase.INTRO; bossRoom = 4; bossHp = bossMax; stage = Stage.DAIS; bx = TX + 120f; by = TY + 64f
        bossGone = false; perishT = 0f; castRune = -1; crackOpen = true; followT = 0f
        minions.clear(); shots.clear(); seeds.clear(); lashes.clear(); mandalas.clear(); stars.clear(); bossBurn = 0f; bossPara = 0f; bossWeak = 0f; bossConf = 0f
        hp = maxHp; spellT = 6f; bossShotT = 3f; summonT = 6f; seedT = 5f; lashT = 7f
        talking = true; talkLines = CH2_LINES; line = 0; shown = 0f
        saveSoon = true
    }

    // ---- saving: your level, your bag, your companion and how far you've come
    private fun saveGame() {
        runCatching {
            context.getSharedPreferences("hall_save", 0).edit()
                .putInt("lvl", lvl).putInt("xp", xp).putInt("maxHp", maxHp)
                .putString("owned", bag.owned.joinToString(",") { it.name }).putString("worn", bag.worn.joinToString(",") { it.name })
                .putInt("herbs", bag.herbs).putInt("blooms", bag.blooms)
                .putInt("chapter", if (chapter == 2 && !(phase == Phase.WON && bossGone)) 2 else if (chapter == 1 && phase == Phase.WON && chestOpen) 2 else 1)
                .putString("friend", savedFriend?.name ?: "").putString("friend2", savedFriend2?.name ?: "")
                .putInt("depth", depth).putInt("beastDepth", beastDepth).putLong("wildSeed", wildSeed).putBoolean("forest", forestOpen)
                .putInt("coins", coins)
                .putString("pool", pool.joinToString(",")).putString("party", partyOn.joinToString(",")).apply()
        }
    }
    private fun loadGame() {
        val sp = context.getSharedPreferences("hall_save", 0)
        if (!sp.contains("lvl")) return
        lvl = sp.getInt("lvl", 1).coerceIn(1, 20); xp = sp.getInt("xp", 0).coerceAtLeast(0); maxHp = BASE_HP + (lvl - 1); hp = maxHp
        bag.clear()
        sp.getString("owned", "")!!.split(',').mapNotNull { n -> Loot.values().firstOrNull { it.name == n } }.forEach { if (it !in bag.owned) bag.owned += it }
        sp.getString("worn", "")!!.split(',').mapNotNull { n -> Loot.values().firstOrNull { it.name == n } }.forEach { if (it in bag.owned && bag.wornCount(it.mode) < bag.SLOTS) bag.worn += it }
        bag.herbs = sp.getInt("herbs", 0).coerceIn(0, 9); bag.blooms = sp.getInt("blooms", 0).coerceIn(0, 5)
        savedFriend = Friend.values().firstOrNull { it.name == sp.getString("friend", "") }
        savedFriend2 = Friend.values().firstOrNull { it.name == sp.getString("friend2", "") }
        if (sp.getInt("chapter", 1) == 2) { chapter = 2; bossHp = bossMax; crackOpen = true; talkLines = RANT2; line = 0; shown = 0f }
        pool.clear(); sp.getString("pool", "")!!.split(',').mapNotNull { it.toIntOrNull() }.filter { it in 0..3 }.forEach { pool += it }
        partyOn.clear(); sp.getString("party", "")!!.split(',').mapNotNull { it.toIntOrNull() }.filter { it in pool.indices }.forEach { partyOn += it }
        forestOpen = sp.getBoolean("forest", false); beastDepth = sp.getInt("beastDepth", 0); wildSeed = sp.getLong("wildSeed", 0L)
        savedDepth = sp.getInt("depth", 0); coins = sp.getInt("coins", 0).coerceAtLeast(0)
        msg = "WELCOME BACK. LEVEL $lvl"; msgT = 2.4f
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
        if (wash != null) { washStroke(); return }
        if (runePromptT > 0f && !(castRune >= 0 && bossHere)) { runeStroke(); return }
        if (castRune < 0 || stroke.size < 4) { stroke.clear(); return }
        val s = similarity(stroke, castRune)
        lastScore = s; scoreT = 1.4f
        if (s >= NEED) {
            castRune = -1; spellT = (if (stage == Stage.CHASE) 10f else 9f) + rnd.nextFloat() * 3f
            msg = if (castRune == 4) "BLESSING BROKEN!" else "COUNTERED!"; msgT = 1.4f
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
        if (inWild) { drawOpenWood(time); return }
        p.color = 0xFF101018.toInt(); sc.drawRect(camX, camY, camX + W, camY + H, p)
        for ((z, r) in rooms) {
            val ox = zoneOX(z); val oy = zoneOY(z)
            if (ox > camX + W || ox + 240f < camX || oy > camY + H || oy + 176f < camY) continue
            val crack = if (z != 4 || !crackOpen) 0 else if (phase == Phase.WON || chapter == 2) 2 else 1
            val a = hallArt[z]
            val bg = a.background(z * 16 + crack * 4) {
                HallRoomArt.Spec(z, r.kind, booleanArrayOf(false, false, false, false), z == 4, r.props.map { it.kind to it.foot }, r.pools, crack, daisLocal, hallOpen = hallOpenSides(z))
            }
            sc.save(); sc.translate(ox, oy)
            sc.drawBitmap(bg, 0f, 0f, bmpP); a.overlay(sc, time)
            if (crack == 2 && (time * 3).toInt() % 3 == 0) box(119f, 20f, 121f, 22f, GOLD)
            if (r.kind == 6) for ((gx, gy) in GOLD_GLINTS) if (((time * 2).toInt() + gx.toInt()) % 4 == 0) { box(gx - 1, gy - 9, gx + 1, gy - 7, WHITE); box(gx - 2, gy - 8, gx + 2, gy - 8.5f, WHITE) }
            sc.restore()
        }
    }
    private val hallArt = Array(9) { HallRoomArt() }
    /** The open wood: each stretch in view, baked once and kept (a few at a time); its gates and keys. */
    private fun drawOpenWood(time: Float) {
        val wl = wild ?: return
        val c0 = (camX / 240f).toInt(); val c1 = ((camX + W - 1) / 240f).toInt().coerceAtMost(wl.w - 1)
        val r0 = (camY / 176f).toInt(); val r1 = ((camY + H - 1) / 176f).toInt().coerceAtMost(wl.h - 1)
        for (cy in r0..r1) for (cx in c0..c1) {
            val r = cy * wl.w + cx
            val a = chunkArt.getOrPut(r) { HallRoomArt() }
            chunkArt.remove(r); chunkArt[r] = a
            while (chunkArt.size > 8) chunkArt.remove(chunkArt.keys.first())
            fun rf(q: IntArray) = RectF(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())
            val bg = a.background(r + depth * 1000) {
                HallRoomArt.Spec(100 + r + depth * 1000, FOREST, booleanArrayOf(true, true, true, true), false,
                    (wl.props[r] + chunkExtra[r]).map { it[0] to RectF(it[1].toFloat(), it[2].toFloat(), it[3].toFloat(), it[4].toFloat()) },
                    wl.pools[r].map { rf(it) }, 0, dais, wl.rivers[r].map { rf(it) }, wl.bridges[r].map { rf(it) }, open = true, mass = wl.mass[r])
            }
            sc.save(); sc.translate(cx * 240f, cy * 176f)
            sc.drawBitmap(bg, 0f, 0f, bmpP); a.overlay(sc, time)
            sc.restore()
        }
        for (g in gates) {
            val wood = 0xFF7A5232.toInt(); val woodD = 0xFF4A301A.toInt(); val iron = 0xFF505064.toInt()
            val f = g.r; box(f.left, f.top, f.right, f.bottom, woodD)
            if (g.vertical) { var y = f.top + 1; while (y < f.bottom - 1) { box(f.left + 1, y, f.right - 1, y + 5, wood); y += 7 }; box(f.left, f.top + 8, f.right, f.top + 10, iron); box(f.left, f.bottom - 10, f.right, f.bottom - 8, iron) }
            else { var x = f.left + 1; while (x < f.right - 1) { box(x, f.top + 1, x + 5, f.bottom - 1, wood); x += 7 }; box(f.left + 8, f.top, f.left + 10, f.bottom, iron); box(f.right - 10, f.top, f.right - 8, f.bottom, iron) }
            box(f.centerX() - 3, f.centerY() - 3, f.centerX() + 3, f.centerY() + 3, INK); box(f.centerX() - 1, f.centerY() - 2, f.centerX() + 1, f.centerY() + 1, if ((time * 3).toInt() % 2 == 0) GOLD else GOLD_D)
        }
        wl.keys.forEachIndexed { i, k ->
            if (i in keysTaken) return@forEachIndexed
            val x = (k[0] % wl.w) * 240f + k[1]; val y0 = (k[0] / wl.w) * 176f + k[2]
            if (x < camX - 20 || x > camX + W + 20 || y0 < camY - 40 || y0 > camY + H + 20) return@forEachIndexed
            val y = y0 - 3f + sin(time * 3f + i) * 1.5f
            val stone = wl.isStone(i)
            val beam = if (stone) 0xA0C8F0 else 0xF8E070
            for (yy in 0 until 30 step 2) { p.color = ((0x30 - yy) shl 24) or beam; sc.drawRect(x - 2f, y - 6f - yy * 1.6f, x + 2f, y - 5f - yy * 1.6f, p) }
            p.color = 0x40000000; sc.drawOval(x - 5, y0 + 2f, x + 5, y0 + 5f, p)
            p.color = (0x30 shl 24) or beam; sc.drawCircle(x, y, 8f + sin(time * 4f) * 1.5f, p)
            if (stone) {
                // the stone key: a heavy grey key with a glowing blue rune in its bow
                val st = 0xFF9A948A.toInt(); val stL = 0xFFC8C2B4.toInt()
                box(x - 8, y - 5, x, y + 3, INK); box(x - 7, y - 4, x - 1, y + 2, st); box(x - 7, y - 4, x - 1, y - 3, stL); box(x - 5, y - 2, x - 3, y, if ((time * 4).toInt() % 2 == 0) 0xFFA0E0FF.toInt() else 0xFF6090D0.toInt())
                box(x - 1, y - 1.5f, x + 9, y + 1.5f, INK); box(x, y - 0.5f, x + 8, y + 0.5f, st)
                box(x + 3, y, x + 5, y + 4, INK); box(x + 6, y, x + 9, y + 3, INK); box(x + 3.5f, y + 0.5f, x + 4.5f, y + 3.5f, st); box(x + 6.5f, y + 0.5f, x + 8.5f, y + 2.5f, st)
            } else {
                // a wooden key, for a gate
                val wd = 0xFFB07E48.toInt(); val wdL = 0xFFD8A868.toInt()
                box(x - 6, y - 4, x, y + 2, INK); box(x - 5, y - 3, x - 1, y + 1, wd); box(x - 5, y - 3, x - 1, y - 2, wdL); box(x - 4, y - 2, x - 2, y, INK)
                box(x - 1, y - 1, x + 7, y + 1, INK); box(x, y - 0.5f, x + 6, y + 0.5f, wd)
                box(x + 3, y, x + 5, y + 3, INK); box(x + 5, y, x + 7, y + 2, INK); box(x + 3.5f, y, x + 4.5f, y + 2.5f, wd)
            }
        }
    }
    /** A screen of the wood: its baked scenery (with every path drawn, locked or not), then gates and keys. */
    private fun drawWildRoom(time: Float) {
        val wl = wild!!
        val key = (depth * 131 + (wildSeed and 0xFFFF).toInt()) * 128 + (roomId - 100)
        val bg = art.background(key) {
            val here0 = roomId - 100
            fun rf(a: IntArray) = RectF(a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), a[3].toFloat())
            HallRoomArt.Spec(roomId + depth * 1000, FOREST, booleanArrayOf(room.doors.contains('W'), room.doors.contains('E'), room.doors.contains('N'), room.doors.contains('S')), false, room.props.map { it.kind to it.foot },
                wl.pools[here0].map { rf(it) }, 0, dais, wl.rivers[here0].map { rf(it) }, wl.bridges[here0].map { rf(it) })
        }
        sc.drawBitmap(bg, 0f, 0f, bmpP)
        art.overlay(sc, time)
        for (d in 0 until 4) if (gateAt(d)) drawGate(d, time)
        // keys lying in the grass, glinting
        val here = ry * wl.w + rx
        wl.keys.forEachIndexed { i, k ->
            if (k[0] != here || i in keysTaken) return@forEachIndexed
            val x = k[1].toFloat(); val y = k[2].toFloat() - 3f + sin(time * 3f + i) * 1.5f
            p.color = 0x40000000; sc.drawOval(x - 5, k[2] + 2f, x + 5, k[2] + 5f, p)
            p.color = 0x30F8E070; sc.drawCircle(x, y, 8f + sin(time * 4f) * 1.5f, p)
            for (yy in 0 until 30 step 2) { p.color = ((0x30 - yy) shl 24) or 0xF8E070; sc.drawRect(x - 2f, y - 6f - yy * 1.6f, x + 2f, y - 5f - yy * 1.6f, p) }
            box(x - 5, y - 3, x - 1, y + 1, INK); box(x - 4, y - 2, x - 2, y, GOLD_D)
            box(x - 6, y - 4, x, y + 2, INK); box(x - 5, y - 3, x - 1, y + 1, GOLD); box(x - 4, y - 2, x - 2, y, INK)
            box(x - 1, y - 1, x + 7, y + 1, INK); box(x, y - 0.5f, x + 6, y + 0.5f, GOLD)
            box(x + 3, y, x + 5, y + 3, INK); box(x + 5, y, x + 7, y + 2, INK); box(x + 3.5f, y, x + 4.5f, y + 2.5f, GOLD)
            if ((time * 3 + i).toInt() % 3 == 0) { box(x - 4, y - 6, x - 3, y - 3, WHITE); box(x - 5.5f, y - 4.5f, x - 1.5f, y - 4.5f + 1, WHITE) }
        }
    }
    /** The heart of the wood's guardian: the Blossom herself, in her own frames (blessing, casting, hurt, a fast last stand). */
    private fun drawWoodBlossom(b: WildBeast, time: Float) {
        val bmp = blossomSheet ?: run { b.draw(sc, p, time); return }
        val frame = when {
            b.dead -> 5
            b.hurtT > 0f && (time * 20).toInt() % 2 == 0 -> if (b.hp < b.maxHp / 3) 8 else 5
            b.hp < b.maxHp / 3 -> 6 + ((time * 8f).toInt() % 4)
            b.winding -> 3
            else -> { val cyc = time % 6f; if (cyc > 5.5f) 4 else if (cyc > 5.1f) 1 else (time * 1.5f).toInt() % 3 }
        }
        val x = b.x; val y = b.y
        p.color = 0x50000000; sc.drawOval(x - 16, y - 3, x + 16, y + 3, p)
        if (b.dead) bmpP.alpha = ((1f - b.deathT / 2f) * 255).toInt().coerceIn(0, 255)
        src.set(frame * 72, 0, frame * 72 + 72, 72); dst.set(x - 36, y - 72, x + 36, y)
        sc.drawBitmap(bmp, src, dst, bmpP)
        bmpP.alpha = 255
        if (b.winding) for (i in 0 until 8) { val a = time * 4f + i * 0.785f; box(x + cos(a) * 18 - 1, y - 40 + sin(a) * 10 - 1, x + cos(a) * 18 + 1, y - 40 + sin(a) * 10 + 1, if (i % 2 == 0) 0xFFF080B0.toInt() else WHITE) }
        if (b.dead) for (i in 0 until 16) { val k = (b.deathT * 30f + i * 5f) % 50f; box(x - 12 + (i * 37 % 24), y - 10 - k, x - 10 + (i * 37 % 24), y - 8 - k, if (i % 2 == 0) WHITE else 0xFFF8C8DC.toInt()) }
    }
    /** A locked gate across a way out: a stout wooden gate, bound in iron, with a keyhole. */
    private fun drawGate(d: Int, time: Float) {
        val wood = 0xFF7A5232.toInt(); val woodD = 0xFF4A301A.toInt(); val iron = 0xFF505064.toInt()
        val glow = if ((time * 3).toInt() % 2 == 0) GOLD else GOLD_D
        if (d < 2) {
            val x = if (d == 0) 0f else W - 8f
            box(x, 80f, x + 8, 124f, woodD)
            for (k in 0 until 5) box(x + 1, 82f + k * 8.5f, x + 7, 88f + k * 8.5f, wood)
            box(x, 90f, x + 8, 92f, iron); box(x, 112f, x + 8, 114f, iron)
            box(x + 2, 99f, x + 6, 105f, INK); box(x + 3, 100f, x + 5, 103f, glow)
        } else {
            val y = if (d == 2) 22f else H - 8f
            box(100f, y, 140f, y + 9, woodD)
            for (k in 0 until 6) box(101f + k * 6.5f, y + 1, 106f + k * 6.5f, y + 8, wood)
            box(108f, y, 110f, y + 9, iron); box(130f, y, 132f, y + 9, iron)
            box(117f, y + 2, 123f, y + 8, INK); box(119f, y + 3, 121f, y + 6, glow)
        }
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
            TREE -> drawTree(f, v, time)
            STUMP -> {
                val bark = 0xFF6A4628.toInt(); val barkD = 0xFF3E2814.toInt()
                box(f.left, b.top + 2, f.right, f.bottom, INK); box(f.left + 1, b.top + 3, f.right - 1, f.bottom - 1, bark)
                box(f.left + 1, b.top + 3, f.left + 3, f.bottom - 1, 0xFF8A623A.toInt()); box(f.right - 3, b.top + 3, f.right - 1, f.bottom - 1, barkD)
                p.color = INK; sc.drawOval(f.left - 0.5f, b.top - 0.5f, f.right + 0.5f, b.top + 5.5f, p)
                p.color = 0xFFD8B078.toInt(); sc.drawOval(f.left + 0.5f, b.top + 0.5f, f.right - 0.5f, b.top + 4.5f, p)
                line1.color = 0xFFA88050.toInt(); sc.drawOval(f.centerX() - 3, b.top + 1.5f, f.centerX() + 3, b.top + 3.5f, line1)
                if (v % 2 == 0) { box(f.right - 4, b.top + 4, f.right - 1, b.top + 6, 0xFFE04848.toInt()); box(f.right - 3, b.top + 6, f.right - 2, b.top + 8, WHITE) }
            }
            LOG -> {
                val bark = 0xFF6A4628.toInt()
                box(f.left, b.top, f.right, f.bottom, INK); box(f.left + 1, b.top + 1, f.right - 1, f.bottom - 1, bark)
                box(f.left + 1, b.top + 1, f.right - 1, b.top + 3, 0xFF8A623A.toInt()); box(f.left + 1, f.bottom - 3, f.right - 1, f.bottom - 1, 0xFF3E2814.toInt())
                var xx = f.left + 5; while (xx < f.right - 6) { box(xx, b.top + 3, xx + 3, b.top + 4, 0xFF3E2814.toInt()); xx += 7 }
                box(f.right - 6, b.top, f.right, f.bottom, INK); box(f.right - 5, b.top + 1, f.right - 1, f.bottom - 1, 0xFFD8B078.toInt()); box(f.right - 4, b.top + 3, f.right - 2, b.top + 5, 0xFFA88050.toInt())
                box(f.left + 3, b.top - 1, f.left + 12, b.top + 2, LEAF_D); box(f.left + 4, b.top - 1, f.left + 10, b.top + 1, 0xFF7CB84C.toInt())
            }
            MUSHROOM -> {
                // a little cluster: a red cap with white spots, or a glowing blue one
                val glowy = v % 3 == 0
                val cap = if (glowy) 0xFF4AA8E0.toInt() else 0xFFE04040.toInt(); val capL = if (glowy) 0xFFA8E8FF.toInt() else 0xFFF87878.toInt()
                if (glowy) { p.color = 0x2878D8FF; sc.drawCircle(f.centerX(), b.top + 2, 9f + sin(time * 2f + v) * 1.5f, p) }
                for ((ox, s0) in listOf(-2f to 1f, 3f to 0.7f)) {
                    val cx = f.centerX() + ox; val top = b.top + (1 - s0) * 5
                    box(cx - 1.5f * s0, top + 4 * s0, cx + 1.5f * s0, f.bottom, INK); box(cx - 1f * s0, top + 4 * s0, cx + 1f * s0, f.bottom - 0.5f, 0xFFF0E8D8.toInt())
                    p.color = INK; sc.drawOval(cx - 5 * s0, top - 0.5f, cx + 5 * s0, top + 5 * s0, p)
                    p.color = cap; sc.drawOval(cx - 4 * s0, top, cx + 4 * s0, top + 4 * s0, p)
                    box(cx - 3 * s0, top + 0.5f, cx - 1 * s0, top + 1.5f, capL)
                    if (!glowy) { box(cx + 1 * s0, top + 1, cx + 2 * s0, top + 2, WHITE); box(cx - 2.5f * s0, top + 2, cx - 1.5f * s0, top + 3, WHITE) }
                }
            }
            THICKET, PALISADE, PLAZA, CLIFF -> {}
            BUILDING -> {
                // a house: plastered walls in a timber frame, a steep roof, a door, lit windows, smoke from the chimney
                val roofs = intArrayOf(0xFF9A3A2A.toInt(), 0xFFC8A050.toInt(), 0xFF4A5468.toInt(), 0xFF6A4A7A.toInt())
                val roof = roofs[(v and 0x7FFFFFFF) % 4]; val roofD = (roof and 0xFEFEFE shr 1) or (0xFF shl 24)
                val wall = 0xFFE8DCC0.toInt(); val wallD = 0xFFC8B898.toInt(); val beam = 0xFF5A3A20.toInt()
                val l = f.left; val r = f.right; val bot = f.bottom; val wt = bot - 24f; val top = b.top
                box(l, wt, r, bot, INK); box(l + 1, wt + 1, r - 1, bot - 1, wall); box(r - 6, wt + 1, r - 1, bot - 1, wallD)
                var bx0 = l + 1; while (bx0 < r - 2) { box(bx0, wt + 1, bx0 + 1.5f, bot - 1, beam); bx0 += 12 }
                box(l + 1, wt + 1, r - 1, wt + 2.5f, beam); box(l + 1, bot - 3, r - 1, bot - 1, 0xFF8A7A60.toInt())
                // the roof, eaves overhanging
                val rh = wt - top
                for (k in 0 until rh.toInt()) { val u = k / rh; val inset = (1f - u) * (f.width() * 0.22f)
                    box(l - 3 + inset, top + k, r + 3 - inset, top + k + 1, if (k % 3 == 0) roofD else roof) }
                box(l - 3, wt - 1, r + 3, wt + 1, INK); box(l + f.width() * 0.22f - 3, top - 1, r - f.width() * 0.22f + 3, top, INK)
                // a door and two windows, warm inside
                val dx = f.centerX() - 4
                box(dx, bot - 13, dx + 8, bot - 1, INK); box(dx + 1, bot - 12, dx + 7, bot - 1, 0xFF7A4A28.toInt()); box(dx + 5, bot - 7, dx + 6, bot - 6, GOLD)
                val glow = if ((time * 0.7f + v).toInt() % 7 == 0) 0xFFFFE0A0.toInt() else 0xFFF8C060.toInt()
                for (wx in floatArrayOf(l + 6, r - 14)) { box(wx, wt + 6, wx + 8, wt + 13, INK); box(wx + 1, wt + 7, wx + 7, wt + 12, glow); box(wx + 3.5f, wt + 7, wx + 4.5f, wt + 12, beam); box(wx + 1, wt + 9, wx + 7, wt + 10, beam) }
                // the chimney and its smoke
                val cx = r - f.width() * 0.3f
                box(cx, top + 2, cx + 5, top + 10, INK); box(cx + 1, top + 3, cx + 4, top + 10, 0xFF8A6050.toInt())
                for (i in 0 until 4) { val ph = (time * 0.5f + i * 0.25f + (v and 7) * 0.1f) % 1f; p.color = (((1f - ph) * 0x70).toInt() shl 24) or 0xD8D8E0
                    sc.drawCircle(cx + 2.5f + sin(time + i) * 2f + ph * 6f, top - ph * 22f, 2f + ph * 3f, p) }
            }
            WELL -> {
                val st = 0xFF8E8878.toInt(); val stD = 0xFF5A544A.toInt(); val stL = 0xFFB8B2A0.toInt(); val wood = 0xFF7A5232.toInt()
                p.color = INK; sc.drawOval(f.left - 1, f.top - 4, f.right + 1, f.bottom + 1, p)
                p.color = st; sc.drawOval(f.left, f.top - 3, f.right, f.bottom, p)
                p.color = 0xFF14202A.toInt(); sc.drawOval(f.left + 3, f.top - 2, f.right - 3, f.top + 3, p)
                for (k in 0 until 4) box(f.left + 1 + k * 4f, f.top + 3, f.left + 4 + k * 4f, f.top + 5, if (k % 2 == 0) stL else stD)
                // the roof on two posts, a rope and bucket
                box(f.left + 1, b.top - 4, f.left + 3, f.top + 1, wood); box(f.right - 3, b.top - 4, f.right - 1, f.top + 1, wood)
                for (k in 0 until 5) box(f.left - 2 + k, b.top - 8 + k, f.right + 2 - k, b.top - 7 + k, if (k % 2 == 0) 0xFF8A3A2A.toInt() else 0xFFA84A34.toInt())
                box(f.left + 3, b.top - 3, f.right - 3, b.top - 2, wood)
                line1.color = 0xFFC8B078.toInt(); val dip = sin(time * 1.2f + v) * 1f; sc.drawLine(f.centerX(), b.top - 2, f.centerX(), f.top - 1 + dip, line1)
                box(f.centerX() - 2, f.top - 2 + dip, f.centerX() + 2, f.top + 1 + dip, 0xFF5A3A20.toInt())
            }
            CRATES -> {
                val wood = 0xFF9A6A3A.toInt(); val woodD = 0xFF5A3A20.toInt(); val woodL = 0xFFC08E52.toInt()
                fun crate(l: Float, t: Float, sz: Float) { box(l, t, l + sz, t + sz, INK); box(l + 1, t + 1, l + sz - 1, t + sz - 1, wood); box(l + 1, t + 1, l + sz - 1, t + 2, woodL); line1.color = woodD; sc.drawLine(l + 1, t + 1, l + sz - 1, t + sz - 1, line1); sc.drawLine(l + sz - 1, t + 1, l + 1, t + sz - 1, line1) }
                crate(f.left, b.top + 4, 9f); crate(f.left + 7, b.top + 6, 8f); crate(f.left + 3, b.top - 3, 8f)
            }
            BARREL -> {
                val wood = 0xFF8A5A30.toInt(); val woodL = 0xFFB07E48.toInt(); val hoop = 0xFF4A4650.toInt()
                box(f.left, b.top, f.right, f.bottom, INK); box(f.left + 1, b.top + 1, f.right - 1, f.bottom - 1, wood); box(f.left + 1, b.top + 1, f.left + 3, f.bottom - 1, woodL)
                box(f.left, b.top + 2, f.right, b.top + 3, hoop); box(f.left, f.bottom - 3, f.right, f.bottom - 2, hoop)
                p.color = 0xFF5A3A20.toInt(); sc.drawOval(f.left + 1, b.top - 1, f.right - 1, b.top + 2, p)
            }
            HAY -> {
                val hay = 0xFFD8B450.toInt(); val hayD = 0xFFA88430.toInt(); val hayL = 0xFFF0D880.toInt()
                box(f.left, b.top + 1, f.right, f.bottom, INK); box(f.left + 1, b.top + 2, f.right - 1, f.bottom - 1, hay)
                for (k in 0 until 4) box(f.left + 1, b.top + 3 + k * 2f, f.right - 1, b.top + 4 + k * 2f, if (k % 2 == 0) hayD else hayL)
                box(f.left + 3, b.top + 1, f.left + 4, f.bottom, 0xFF7A5232.toInt()); box(f.right - 4, b.top + 1, f.right - 3, f.bottom, 0xFF7A5232.toInt())
                box(f.left - 1, b.top + 3, f.left, b.top + 4, hayL); box(f.right, b.top + 6, f.right + 1, b.top + 7, hayL)
            }
            LANTERN -> {
                box(f.centerX() - 1, b.top + 2, f.centerX() + 1, f.bottom, 0xFF3A3038.toInt())
                box(f.centerX() - 1, b.top, f.centerX() + 4, b.top + 1, 0xFF3A3038.toInt())
                val fl = 0.75f + 0.25f * sin(time * 9f + v)
                p.color = ((0x30 * fl).toInt() shl 24) or 0xFFC060; sc.drawCircle(f.centerX() + 3, b.top + 4, 10f, p)
                box(f.centerX() + 1, b.top + 1, f.centerX() + 6, b.top + 7, INK); box(f.centerX() + 2, b.top + 2, f.centerX() + 5, b.top + 6, if (fl > 0.9f) 0xFFFFF0A0.toInt() else 0xFFFFC040.toInt())
            }
            BENCH -> {
                val wood = 0xFF8A5A30.toInt(); val woodL = 0xFFB07E48.toInt()
                box(f.left, b.top, f.right, b.top + 3, INK); box(f.left + 1, b.top + 0.5f, f.right - 1, b.top + 2, wood); box(f.left + 1, b.top + 0.5f, f.right - 1, b.top + 1, woodL)
                box(f.left + 2, b.top + 3, f.left + 4, f.bottom, INK); box(f.right - 4, b.top + 3, f.right - 2, f.bottom, INK)
            }
            CART -> {
                val wood = 0xFF8A5A30.toInt(); val woodD = 0xFF5A3A20.toInt(); val woodL = 0xFFB07E48.toInt()
                box(f.left, b.top, f.right - 4, f.bottom - 4, INK); box(f.left + 1, b.top + 1, f.right - 5, f.bottom - 5, wood)
                for (k in 0 until 3) box(f.left + 1, b.top + 2 + k * 3f, f.right - 5, b.top + 3 + k * 3f, woodD)
                box(f.left + 1, b.top + 1, f.right - 5, b.top + 2, woodL)
                // a load of sacks and pumpkins
                p.color = 0xFFD8C890.toInt(); sc.drawOval(f.left + 3, b.top - 5, f.left + 11, b.top + 2, p); p.color = 0xFFE07830.toInt(); sc.drawOval(f.left + 10, b.top - 4, f.left + 17, b.top + 2, p)
                line1.color = woodD; sc.drawLine(f.right - 4, f.bottom - 6, f.right + 4, f.bottom - 3, line1)
                for (wx in listOf(f.left + 5, f.right - 9)) { p.color = INK; sc.drawCircle(wx, f.bottom - 3, 4f, p); p.color = woodL; sc.drawCircle(wx, f.bottom - 3, 3f, p); p.color = woodD; sc.drawCircle(wx, f.bottom - 3, 1f, p) }
            }
            SIGNPOST -> {
                val wood = 0xFF8A5A30.toInt(); val woodL = 0xFFB07E48.toInt()
                box(f.centerX() - 1, b.top + 2, f.centerX() + 1, f.bottom, INK)
                box(f.centerX() - 9, b.top + 2, f.centerX() + 7, b.top + 7, INK); box(f.centerX() - 8, b.top + 3, f.centerX() + 6, b.top + 6, wood); box(f.centerX() - 8, b.top + 3, f.centerX() + 6, b.top + 4, woodL)
                box(f.centerX() - 6, b.top + 9, f.centerX() + 9, b.top + 13, INK); box(f.centerX() - 5, b.top + 10, f.centerX() + 8, b.top + 12, wood)
            }
            FLOWERBED -> {
                box(f.left, f.top, f.right, f.bottom, 0xFF5A3A20.toInt()); box(f.left + 1, f.top + 1, f.right - 1, f.bottom - 1, 0xFF6E4A2A.toInt())
                for (k in 0 until (f.width() / 3).toInt()) { val fx0 = f.left + 1 + k * 3f; val c0 = listOf(0xFFF06090.toInt(), 0xFFF8E060.toInt(), 0xFFB070F0.toInt(), WHITE)[(k + v) and 3]
                    box(fx0 + 1, f.top - 2, fx0 + 2, f.top + 2, 0xFF3E7A30.toInt()); box(fx0, f.top - 3 + (k % 2), fx0 + 2, f.top - 1 + (k % 2), c0) }
            }
            LAUNDRY -> {
                val post = 0xFF5A3A20.toInt(); val x2 = f.left + 30f
                box(f.left, b.top, f.left + 2, f.bottom, post); box(x2, b.top, x2 + 2, f.bottom, post)
                line1.color = 0xFFC8C0A8.toInt(); sc.drawLine(f.left + 1, b.top + 1, x2 + 1, b.top + 1, line1)
                val sway = sin(time * 2f + v) * 1f
                val cols = listOf(0xFFE8E0D0.toInt(), 0xFF6080C8.toInt(), 0xFFC84848.toInt(), 0xFFE8D070.toInt())
                for (k in 0 until 4) { val cx0 = f.left + 4 + k * 7f; box(cx0 + sway * (k % 2), b.top + 2, cx0 + 5 + sway * (k % 2), b.top + 8 + (k % 2) * 2, cols[(k + v) and 3]) }
            }
            COLUMN -> {
                // an intact column, fluted, with its capital; ivy climbing
                val stone = 0xFFB4AE9E.toInt(); val stoneD = 0xFF6E685C.toInt(); val stoneL = 0xFFDCD6C6.toInt()
                val top = b.top
                box(f.left - 2, f.bottom - 4, f.right + 2, f.bottom, INK); box(f.left - 1, f.bottom - 3, f.right + 1, f.bottom - 1, stoneD)
                box(f.left - 2, top, f.right + 2, top + 4, INK); box(f.left - 1, top + 1, f.right + 1, top + 3, stoneL)
                box(f.left, top + 4, f.right, f.bottom - 3, INK); box(f.left + 1, top + 4, f.right - 1, f.bottom - 4, stone)
                box(f.left + 1, top + 4, f.left + 3, f.bottom - 4, stoneL); box(f.right - 3, top + 4, f.right - 1, f.bottom - 4, stoneD)
                var xx = f.left + 4; while (xx < f.right - 3) { box(xx, top + 5, xx + 1, f.bottom - 5, stoneD); xx += 3 }
                for (k in 0 until 6) box(f.left + (k % 2) * 4 + 1, f.bottom - 8 - k * 5f, f.left + (k % 2) * 4 + 3, f.bottom - 6 - k * 5f, 0xFF4E8A34.toInt())
            }
            ARCH -> {
                val stone = 0xFFB4AE9E.toInt(); val stoneD = 0xFF6E685C.toInt(); val stoneL = 0xFFDCD6C6.toInt()
                val l = f.left; val r = f.right; val top = b.top
                box(l, top + 8, l + 8, f.bottom, INK); box(l + 1, top + 9, l + 7, f.bottom - 1, stone); box(l + 1, top + 9, l + 3, f.bottom - 1, stoneL)
                box(r - 8, top + 14, r, f.bottom, INK); box(r - 7, top + 15, r - 1, f.bottom - 1, stone); box(r - 3, top + 15, r - 1, f.bottom - 1, stoneD)
                // the curve of the arch, broken off on the right
                for (k in 0 until 12) { val a = 3.1416f + k * 0.2f; val x = (l + r) / 2 + cos(a) * (r - l) / 2.2f; val y = top + 12 + sin(a) * 10f
                    box(x - 3, y - 3, x + 3, y + 3, INK); box(x - 2, y - 2, x + 2, y + 2, if (k % 3 == 0) stoneL else stone) }
                box(l + 2, top + 4, l + 9, top + 6, 0xFF4E8A34.toInt())
            }
            RUIN_STATUE -> {
                // a robed figure on a plinth, its face worn away, one arm raised
                val stone = 0xFFA8A49A.toInt(); val stoneD = 0xFF66625A.toInt(); val stoneL = 0xFFD4D0C4.toInt()
                val cx = f.centerX(); val top = b.top
                box(f.left, f.bottom - 8, f.right, f.bottom, INK); box(f.left + 1, f.bottom - 7, f.right - 1, f.bottom - 1, stoneD); box(f.left + 1, f.bottom - 7, f.right - 1, f.bottom - 6, stone)
                box(cx - 5, top + 8, cx + 5, f.bottom - 8, INK); box(cx - 4, top + 9, cx + 4, f.bottom - 8, stone); box(cx - 4, top + 9, cx - 2, f.bottom - 8, stoneL)
                for (k in 0 until 3) box(cx - 3 + k * 3f, top + 12, cx - 2 + k * 3f, f.bottom - 9, stoneD)
                p.color = INK; sc.drawCircle(cx, top + 5, 4f, p); p.color = stone; sc.drawCircle(cx, top + 5, 3f, p); box(cx - 1, top + 4, cx, top + 5, stoneL)
                box(cx + 4, top - 2, cx + 6, top + 10, INK); box(cx + 4.5f, top - 1, cx + 5.5f, top + 9, stone)
                box(cx - 5, top + 14, cx - 2, top + 16, 0xFF4E8A34.toInt())
            }
            FALLEN_COLUMN -> {
                val stone = 0xFFB4AE9E.toInt(); val stoneD = 0xFF6E685C.toInt(); val stoneL = 0xFFDCD6C6.toInt()
                var x = f.left
                for (k in 0 until 3) { val w0 = (f.width() / 3f) - 1
                    box(x, b.top, x + w0, f.bottom, INK); box(x + 1, b.top + 1, x + w0 - 1, f.bottom - 1, stone); box(x + 1, b.top + 1, x + w0 - 1, b.top + 2, stoneL); box(x + 1, f.bottom - 2, x + w0 - 1, f.bottom - 1, stoneD)
                    var yy = b.top + 3; while (yy < f.bottom - 2) { box(x + 1, yy, x + w0 - 1, yy + 0.7f, stoneD); yy += 2 }
                    x += w0 + 1 + (k % 2) }
                box(f.left + 4, b.top, f.left + 9, b.top + 2, 0xFF4E8A34.toInt())
            }
            GRAVE -> {
                val stone = 0xFF8A8A9A.toInt(); val stoneD = 0xFF5A5A6A.toInt(); val stoneL = 0xFFB4B4C4.toInt()
                // a mound before it, then the stone: rounded slab, cross or obelisk, leaning a little
                p.color = 0xFF4A5A2C.toInt(); sc.drawOval(f.left - 1, f.bottom - 4, f.right + 1, f.bottom + 2, p)
                when (v % 3) {
                    0 -> { box(f.left + 1, b.top + 2, f.right - 1, f.bottom - 1, INK); p.color = INK; sc.drawCircle(f.centerX(), b.top + 4, 4.5f, p)
                        box(f.left + 2, b.top + 3, f.right - 2, f.bottom - 2, stone); p.color = stone; sc.drawCircle(f.centerX(), b.top + 4, 3.5f, p)
                        box(f.left + 2, b.top + 3, f.left + 4, f.bottom - 2, stoneL); box(f.centerX() - 2, b.top + 5, f.centerX() + 2, b.top + 6, stoneD); box(f.centerX() - 2, b.top + 7, f.centerX() + 1, b.top + 8, stoneD) }
                    1 -> { box(f.centerX() - 2, b.top - 1, f.centerX() + 2, f.bottom - 1, INK); box(f.left, b.top + 2, f.right, b.top + 6, INK)
                        box(f.centerX() - 1, b.top, f.centerX() + 1, f.bottom - 2, stone); box(f.left + 1, b.top + 3, f.right - 1, b.top + 5, stone); box(f.centerX() - 1, b.top, f.centerX(), f.bottom - 2, stoneL) }
                    else -> { box(f.centerX() - 3, b.top - 3, f.centerX() + 3, f.bottom - 1, INK); box(f.centerX() - 2, b.top - 2, f.centerX() + 2, f.bottom - 2, stone); box(f.centerX() - 1, b.top - 5, f.centerX() + 1, b.top - 2, stone); box(f.centerX() - 2, b.top - 2, f.centerX() - 1, f.bottom - 2, stoneL) }
                }
                if (v % 2 == 0) { box(f.left + 2, f.bottom - 4, f.left + 6, f.bottom - 2, 0xFF4E8A34.toInt()); box(f.right - 4, b.top + 3, f.right - 2, b.top + 5, 0xFF6CA642.toInt()) }
            }
            RUIN_PILLAR -> {
                val stone = 0xFF9A9488.toInt(); val stoneD = 0xFF625C52.toInt(); val stoneL = 0xFFC4BEB0.toInt()
                val top = b.top + (v % 3) * 4f
                box(f.left - 2, f.bottom - 4, f.right + 2, f.bottom, INK); box(f.left - 1, f.bottom - 3, f.right + 1, f.bottom - 1, stoneD)
                box(f.left, top, f.right, f.bottom - 3, INK); box(f.left + 1, top + 1, f.right - 1, f.bottom - 4, stone)
                box(f.left + 1, top + 1, f.left + 3, f.bottom - 4, stoneL); box(f.right - 3, top + 1, f.right - 1, f.bottom - 4, stoneD)
                var xx = f.left + 4; while (xx < f.right - 3) { box(xx, top + 2, xx + 1, f.bottom - 5, stoneD); xx += 3 }
                // the broken top: jagged
                for (k in 0 until 4) box(f.left + 1 + k * 3, top - (k * 5 + v) % 4, f.left + 3 + k * 3, top + 1, stone)
                box(f.left + 2, top + 8, f.left + 4, top + 13, stoneD)
                // moss, and a fallen chunk
                box(f.left + 1, top, f.left + 5, top + 2, 0xFF4E8A34.toInt()); box(f.left + 3, f.bottom - 6, f.right - 2, f.bottom - 4, 0xFF4E8A34.toInt())
                if (v % 2 == 0) { box(f.right + 2, f.bottom - 4, f.right + 7, f.bottom, INK); box(f.right + 3, f.bottom - 3, f.right + 6, f.bottom - 1, stone) }
            }
            RUIN_WALL -> {
                val stone = 0xFF8E8878.toInt(); val stoneD = 0xFF5A544A.toInt(); val stoneL = 0xFFB8B2A0.toInt()
                box(f.left, b.top, f.right, f.bottom, INK)
                var row = 0; var yy = b.top + 1
                while (yy < f.bottom - 1) {
                    var xx = f.left + 1 + (if (row % 2 == 1) 3 else 0)
                    while (xx < f.right - 1) { box(xx, yy, minOf(xx + 5, f.right - 1), minOf(yy + 3, f.bottom - 1), if ((xx.toInt() + row) % 3 == 0) stoneL else stone); xx += 6 }
                    yy += 4; row++
                }
                // a crumbled top edge and ivy
                for (k in 0 until (f.width() / 5).toInt()) if ((v shr k) and 1 == 1) box(f.left + k * 5f, b.top - 2, f.left + k * 5f + 4, b.top, stoneD)
                for (k in 0 until 3) box(f.left + 3 + k * 7f, b.top + 1, f.left + 5 + k * 7f, b.top + 7, 0xFF3E7A30.toInt())
            }
            DEADTREE -> {
                val bark = 0xFF5A4A40.toInt(); val barkL = 0xFF7E6C5E.toInt()
                val cx = f.centerX(); val bot = f.bottom
                box(cx - 3, bot - 30, cx + 3, bot, INK); box(cx - 2, bot - 30, cx + 2, bot - 1, bark); box(cx - 2, bot - 30, cx - 1, bot - 1, barkL)
                box(cx - 6, bot - 3, cx + 6, bot, bark)
                line2.color = bark
                sc.drawLine(cx, bot - 26, cx - 10, bot - 36, line2); sc.drawLine(cx - 6, bot - 32, cx - 9, bot - 40, line2)
                sc.drawLine(cx, bot - 22, cx + 11, bot - 32, line2); sc.drawLine(cx + 7, bot - 28, cx + 12, bot - 38, line2)
                sc.drawLine(cx, bot - 29, cx + 2, bot - 40, line2)
                line1.color = bark; sc.drawLine(cx - 10, bot - 36, cx - 14, bot - 37, line1); sc.drawLine(cx + 11, bot - 32, cx + 15, bot - 30, line1)
                if (v % 2 == 0) { box(cx - 11, bot - 37, cx - 9, bot - 35, INK) }    // a crow? no: a knot
            }
            TENT -> {
                val can = 0xFFC8B48A.toInt(); val canD = 0xFF9A845C.toInt(); val stripe = 0xFF8A3A2A.toInt()
                val l = f.left; val r = f.right; val t = b.top; val bot = f.bottom
                for (k in 0 until (bot - t).toInt()) {
                    val u = k / (bot - t); val hw = (r - l) / 2f * u
                    box(f.centerX() - hw - 1, t + k, f.centerX() + hw + 1, t + k + 1, INK)
                    box(f.centerX() - hw, t + k, f.centerX(), t + k + 1, can); box(f.centerX(), t + k, f.centerX() + hw, t + k + 1, canD)
                    if (k % 6 == 3) box(f.centerX() - hw, t + k, f.centerX() + hw, t + k + 1, stripe)
                }
                box(f.centerX() - 3, bot - 10, f.centerX() + 3, bot, 0xFF2A2018.toInt())
                box(f.centerX(), t - 4, f.centerX() + 1, t, INK); box(f.centerX() + 1, t - 4, f.centerX() + 5, t - 2, stripe)
            }
            CAMPFIRE -> {
                val k = (time * 8).toInt() % 3
                p.color = 0x30FFA040; sc.drawCircle(f.centerX(), f.top, 18f, p); p.color = 0x28FFC060; sc.drawCircle(f.centerX(), f.top, 10f, p)
                for (i in 0 until 6) { val a = i * 1.047f; box(f.centerX() + cos(a) * 6 - 1.5f, f.centerY() + sin(a) * 2.5f - 1, f.centerX() + cos(a) * 6 + 1.5f, f.centerY() + sin(a) * 2.5f + 1, 0xFF6A6470.toInt()) }
                box(f.centerX() - 4, f.centerY() - 1, f.centerX() + 4, f.centerY() + 1, 0xFF4A301A.toInt())
                box(f.centerX() - 3, f.top - 6 + k, f.centerX() + 3, f.centerY(), 0xFFF07820.toInt()); box(f.centerX() - 2, f.top - 9 + k, f.centerX() + 2, f.centerY() - 1, 0xFFFFC040.toInt()); box(f.centerX() - 1, f.top - 7, f.centerX() + 1, f.centerY() - 1, 0xFFFFF4C0.toInt())
                for (i in 0 until 3) { val ph = (time * 0.8f + i * 0.33f) % 1f; box(f.centerX() + sin(time * 3 + i) * 3, f.top - 10 - ph * 18, f.centerX() + 1 + sin(time * 3 + i) * 3, f.top - 9 - ph * 18, 0xFFFFB040.toInt()) }
            }
            STALL -> {
                val wood = 0xFF7A5232.toInt(); val woodD = 0xFF4A301A.toInt()
                // an awning on poles, a counter with jars and bundles of herbs
                box(f.left + 1, b.top - 14, f.left + 3, f.bottom, woodD); box(f.right - 3, b.top - 14, f.right - 1, f.bottom, woodD)
                for (k in 0 until ((f.width()) / 6).toInt() + 1) box(f.left - 2 + k * 6f, b.top - 18, minOf(f.left + 4 + k * 6f, f.right + 2), b.top - 13, if (k % 2 == 0) 0xFF3E7A4A.toInt() else 0xFFD8C890.toInt())
                box(f.left - 2, b.top - 13, f.right + 2, b.top - 12, INK)
                box(f.left, b.top + 4, f.right, f.bottom, INK); box(f.left + 1, b.top + 5, f.right - 1, f.bottom - 1, wood); box(f.left + 1, b.top + 5, f.right - 1, b.top + 6, 0xFFA07448.toInt())
                for (k in 0 until 4) { val jx = f.left + 4 + k * 8f; box(jx, b.top - 1, jx + 4, b.top + 4, INK); box(jx + 1, b.top, jx + 3, b.top + 4, if (k % 2 == 0) 0xFF6AB04C.toInt() else 0xFF8CC8F0.toInt()) }
            }
            EXIT -> {
                // the great stone door: a carved frame, two slabs, and behind them a sky full of stars
                val cx = f.centerX(); val bot = f.bottom; val top = bot - 58f
                val sealed = wild?.bossLevel == true && !beastWon
                val k = if (doorT < 0f) 0f else ((doorT - 0.6f) / 1.8f).coerceIn(0f, 1f)
                val st = 0xFF8A8478.toInt(); val stD = 0xFF5A554C.toInt(); val stL = 0xFFB8B2A2.toInt(); val stK = 0xFF3A3630.toInt()
                val il = cx - 18; val ir = cx + 18; val it = top + 12; val ib = bot - 2
                // the stars, deep blue, slowly turning
                box(il, it, ir, ib, 0xFF080A1E.toInt()); box(il, it, ir, it + (ib - it) * 0.4f, 0xFF0C1030.toInt())
                p.color = 0x302040A0; sc.drawCircle(cx + sin(time * 0.2f) * 6f, it + 18f, 12f, p)
                for (i in 0 until 26) {
                    val sx = il + 1 + ((i * 37 + 11) % 34); val sy = it + 1 + ((i * 53 + 7) % (ib - it - 2).toInt().coerceAtLeast(1))
                    val tw = ((time * (1.5f + (i % 4) * 0.7f) + i).toInt() % 3)
                    box(sx, sy, sx + 1, sy + 1, if (tw == 0) WHITE else if (i % 3 == 0) 0xFFA0C0FF.toInt() else 0xFF6070B0.toInt())
                    if (i % 7 == 0 && tw == 0) { box(sx - 1, sy, sx + 2, sy + 1, 0x80FFFFFF.toInt()); box(sx, sy - 1, sx + 1, sy + 2, 0x80FFFFFF.toInt()) }
                }
                if (k > 0f) { p.color = (((k * 0x50).toInt()) shl 24) or 0xA0C0FF; sc.drawRect(il - 6, it - 4, ir + 6, ib + 6, p) }
                // the two slabs, sliding apart into the frame
                fun slab(l0: Float, r0: Float, left: Boolean) {
                    if (r0 - l0 < 0.5f) return
                    box(l0, it, r0, ib, stK); box(l0 + 0.5f, it + 1, r0 - 0.5f, ib, st)
                    val off = if (left) -k * 18f else k * 18f
                    var yy = it + 4; while (yy < ib - 3) { val gx = cx + off + (if (left) -9f else 9f)
                        if (gx in l0 + 2..r0 - 2) { box(gx - 4, yy, gx + 4, yy + 1, stD); box(gx - 0.5f, yy - 3, gx + 0.5f, yy + 4, stD) }
                        yy += 11 }
                    if (left) box(l0 + 0.5f, it + 1, l0 + 2, ib, stL) else box(r0 - 2, it + 1, r0 - 0.5f, ib, stD)
                }
                slab(il, cx - 18f * k, true); slab(cx + 18f * k, ir, false)
                if (k == 0f) {
                    // shut: a hairline of starlight down the seam; the keyhole glows if you hold the key
                    box(cx - 0.5f, it, cx + 0.5f, ib, if ((time * 2).toInt() % 2 == 0) 0xFF6070B0.toInt() else 0xFF3A4070.toInt())
                    val g = 0.5f + 0.5f * sin(time * 3f)
                    val kc = if (stoneKeys > 0) 0x60A0E0FF else 0x30F8E070
                    p.color = ((((kc ushr 24) * (0.6f + 0.4f * g)).toInt()) shl 24) or (kc and 0xFFFFFF); sc.drawCircle(cx, it + 26, 7f, p)
                    box(cx - 3, it + 22, cx + 3, it + 32, stK); box(cx - 1.5f, it + 23, cx + 1.5f, it + 26, if (stoneKeys > 0) 0xFFA0E0FF.toInt() else 0xFF20203A.toInt()); box(cx - 0.5f, it + 26, cx + 0.5f, it + 30, if (stoneKeys > 0) 0xFFA0E0FF.toInt() else 0xFF20203A.toInt())
                }
                // the frame: two great jambs and a lintel carved with runes
                for (jx in floatArrayOf(il - 9, ir + 1)) { box(jx, it - 2, jx + 8, bot, INK); box(jx + 1, it - 1, jx + 7, bot - 1, st); box(jx + 1, it - 1, jx + 3, bot - 1, stL); box(jx + 6, it - 1, jx + 7, bot - 1, stD)
                    var yy = it + 4; while (yy < bot - 4) { box(jx + 1, yy, jx + 7, yy + 1, stD); yy += 9 } }
                box(il - 12, top, ir + 12, it, INK); box(il - 11, top + 1, ir + 11, it - 1, st); box(il - 11, top + 1, ir + 11, top + 3, stL); box(il - 11, it - 3, ir + 11, it - 1, stD)
                val runeC = if (k > 0f || stoneKeys > 0) (if ((time * 4).toInt() % 2 == 0) 0xFFA0E0FF.toInt() else 0xFF6090D0.toInt()) else stD
                for (i in 0 until 7) { val rx = il - 7 + i * 8f; box(rx, top + 5, rx + 1, top + 9, runeC); box(rx + 2, top + 4 + (i % 3), rx + 3, top + 8, runeC); box(rx, top + 6 + (i % 2) * 2, rx + 3, top + 7 + (i % 2) * 2, runeC) }
                box(cx - 4, top - 4, cx + 4, top + 1, INK); box(cx - 3, top - 3, cx + 3, top, stL)       // the keystone
                // moss and a fern at the foot
                box(il - 12, top - 1, il - 2, top + 2, 0xFF4E8A34.toInt()); box(ir + 4, top, ir + 11, top + 2, 0xFF4E8A34.toInt()); box(il - 9, bot - 4, il - 4, bot, 0xFF4E8A34.toInt())
                if (sealed) {
                    line2.color = 0xFF3E7A30.toInt()
                    for (i in 0 until 5) sc.drawLine(il - 4, it + 6 + i * 8, ir + 4, it + 10 + i * 8 - (i % 2) * 10, line2)
                    for (i in 0 until 6) box(il + i * 6.5f, it + 9 + (i % 2) * 9, il + 1 + i * 6.5f, it + 10 + (i % 2) * 9, 0xFFE8E0C8.toInt())
                }
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

    /**
     * A tree of the wood, drawn in detail: roots flaring into the ground, a grooved trunk with a
     * knot, two limbs forking into a crown of seven to nine leaf clumps, each with an ink edge,
     * four tones, notched leaf edges and scattered highlights; dappled shade on the trunk; and,
     * tree by tree, hanging vines, fruit or blossom. Its look comes from where it stands (v).
     */
    private fun drawTree(f: RectF, v: Int, time: Float) {
        val cx = f.centerX(); val bot = f.bottom
        treeSheet?.let { ts ->
            val k = TREE_PICK[Math.floorMod(v + depth * 7, TREE_PICK.size)]
            src.set(k * 64, 0, k * 64 + 64, 64); dst.set(cx - 32f, bot - 61f, cx + 32f, bot + 3f)
            sc.drawBitmap(ts, src, dst, bmpP)
            return
        }
        val bark = 0xFF6A4628.toInt(); val barkD = 0xFF3E2814.toInt(); val barkL = 0xFF8A623A.toInt(); val barkH = 0xFFA8804E.toInt()
        // roots, flaring out and down into the grass
        for (side in intArrayOf(-1, 1)) {
            box(cx + side * 4f - (if (side < 0) 4 else 0), bot - 4, cx + side * 4f + (if (side > 0) 4 else 0), bot - 1, barkD)
            box(cx + side * 7f - (if (side < 0) 2 else 0), bot - 2, cx + side * 7f + (if (side > 0) 2 else 0), bot, barkD)
        }
        box(cx - 6, bot - 5, cx + 6, bot - 1, bark); box(cx - 1, bot - 3, cx + 1, bot, barkD)
        box(cx - 6, bot - 5, cx - 3, bot - 2, barkL)
        // the trunk, a little tapered, bark grooves, a knot hole
        box(cx - 5, bot - 26, cx + 5, bot - 3, INK); box(cx - 4, bot - 26, cx + 4, bot - 3, bark)
        box(cx - 4, bot - 26, cx - 2, bot - 3, barkL); box(cx - 3, bot - 24, cx - 2, bot - 6, barkH); box(cx + 2, bot - 26, cx + 4, bot - 3, barkD)
        for (k in 0 until 4) { val gx0 = cx - 3 + ((v shr k) and 3) * 2f; val gy0 = bot - 22 + k * 5f; box(gx0, gy0, gx0 + 1, gy0 + 4, barkD) }
        if (v % 3 != 0) { val ky = bot - 15f - (v % 5); box(cx, ky, cx + 3, ky + 3, INK); box(cx + 1, ky + 1, cx + 2, ky + 2, 0xFF1A1008.toInt()); box(cx - 1, ky - 1, cx + 3, ky, barkL) }
        // two limbs forking up into the crown
        line2.color = bark; sc.drawLine(cx - 1, bot - 24, cx - 8, bot - 31, line2); sc.drawLine(cx + 1, bot - 24, cx + 7, bot - 33, line2)
        val hue = v % 7
        val cD = when (hue) { 0 -> 0xFF7A3E16.toInt(); 1 -> 0xFF5E2856.toInt(); 2 -> 0xFF1A4A3A.toInt(); else -> 0xFF1E4A24.toInt() }
        val cM = when (hue) { 0 -> 0xFFB8642A.toInt(); 1 -> 0xFF9A4A86.toInt(); 2 -> 0xFF2A7058.toInt(); else -> 0xFF2E7232.toInt() }
        val cL = when (hue) { 0 -> 0xFFE8A040.toInt(); 1 -> 0xFFD880B8.toInt(); 2 -> 0xFF4A9A70.toInt(); else -> 0xFF4E9E40.toInt() }
        val cH = when (hue) { 0 -> 0xFFFFD880.toInt(); 1 -> 0xFFFFC8EC.toInt(); 2 -> 0xFF8AD0A0.toInt(); else -> 0xFF94D060.toInt() }
        val sway = sin(time * 1.2f + v) * 0.8f
        val ty = bot - 34f
        // the crown: clumps placed from v, back ones first
        val n = 7 + v % 3
        val cxs = FloatArray(n); val cys = FloatArray(n); val crs = FloatArray(n)
        for (i in 0 until n) {
            val a = -1.57f + (i - (n - 1) / 2f) * (3.4f / n) + ((v shr (i % 8)) and 3) * 0.08f
            val ring = if (i % 2 == 0) 10f else 6f
            cxs[i] = cx + cos(a) * ring * 1.15f + sway; cys[i] = ty + 4f + sin(a) * ring * 0.85f; crs[i] = 7.5f + ((v shr i) and 3)
        }
        // ink first, so clumps outline each other
        p.color = INK
        sc.drawCircle(cx + sway, ty, 14.5f, p)
        for (i in 0 until n) sc.drawCircle(cxs[i], cys[i], crs[i] + 1f, p)
        p.color = cD; sc.drawCircle(cx + sway, ty, 13.5f, p)
        for (i in 0 until n) {
            val x = cxs[i]; val y = cys[i]; val r = crs[i]
            p.color = cD; sc.drawCircle(x, y, r, p)
            p.color = cM; sc.drawCircle(x - r * 0.15f, y - r * 0.18f, r * 0.8f, p)
            p.color = cL; sc.drawCircle(x - r * 0.32f, y - r * 0.38f, r * 0.45f, p)
            // notched leaf edges along the lower rim, and highlight specks up top
            for (k in 0 until 5) {
                val a = 0.4f + k * 0.55f
                box(x + cos(a) * (r - 1f), y + sin(a) * (r - 1f), x + cos(a) * (r - 1f) + 1, y + sin(a) * (r - 1f) + 1, INK)
            }
            box(x - r * 0.45f, y - r * 0.55f, x - r * 0.45f + 1, y - r * 0.55f + 1, cH)
            box(x - r * 0.1f, y - r * 0.6f, x - r * 0.1f + 1, y - r * 0.6f + 1, cH)
            box(x - r * 0.6f, y - r * 0.2f, x - r * 0.6f + 1, y - r * 0.2f + 1, cH)
        }
        // the top clump catches the most light
        p.color = cL; sc.drawCircle(cx - 3 + sway, ty - 6, 6f, p); p.color = cH; sc.drawCircle(cx - 5 + sway, ty - 8, 2.2f, p)
        // dappled shade on the trunk under the crown
        for (k in 0 until 3) box(cx - 3 + k * 2f, bot - 25 + (k % 2) * 2f, cx - 2 + k * 2f, bot - 24 + (k % 2) * 2f, 0x60000000)
        // and something of its own
        when (v % 5) {
            0 -> for (k in 0 until 4) { val fx0 = cxs[k * 2 % n] + 2f; val fy0 = cys[k * 2 % n] + 2f; box(fx0, fy0, fx0 + 2, fy0 + 2, 0xFFE04848.toInt()); box(fx0, fy0, fx0 + 1, fy0 + 1, WHITE) }
            1 -> for (k in 0 until 3) { val vx = cxs[k + 1] ; val vy = cys[k + 1] + crs[k + 1] - 2; line1.color = 0xFF3E7A30.toInt(); sc.drawLine(vx, vy, vx + sin(time + k) * 1f, vy + 7f + k * 2f, line1); box(vx, vy + 4f, vx + 2, vy + 5f, 0xFF6AB04C.toInt()) }
            2 -> for (k in 0 until 5) { val bx0 = cxs[k % n] - 3f + k; val by0 = cys[k % n] - 1f; box(bx0, by0, bx0 + 2, by0 + 2, 0xFFFFE8F4.toInt()); box(bx0 + 0.5f, by0 + 0.5f, bx0 + 1.5f, by0 + 1.5f, 0xFFF080B0.toInt()) }
            else -> {}
        }
    }

    private val src = Rect(); private val dst = RectF()
    private fun drawBoss(time: Float) {
        val bmp = (if (chapter == 2) blossomSheet ?: yeti else yeti) ?: return
        val frame: Int; var dx = 0f; var dy = 0f
        when {
            phase == Phase.WON -> { if (bossGone) return; frame = 1; dy = 2f }
            stage == Stage.SLAM -> {
                if (slamT < 0.9f) { frame = 3; dx = if ((time * 20).toInt() % 2 == 0) -1f else 1f }
                else { frame = 0; if (slamT > 1.6f) dy = -sin(((slamT - 1.6f) / 0.6f).coerceIn(0f, 1f) * Math.PI.toFloat()) * 18f }
            }
            bossHurtT > 0f -> { frame = if (chapter == 2 && stage == Stage.CHASE) 8 else 5; dx = if ((time * 30).toInt() % 2 == 0) -2f else 2f; if ((time * 20).toInt() % 2 == 0) return }
            chapter == 2 && stage == Stage.CHASE -> { frame = 6 + ((time * 8f).toInt() % 4); if (castRune >= 0) dx = if ((time * 10).toInt() % 2 == 0) -1f else 1f }
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
            bmpP.alpha = ((1f - k) * 255).toInt().coerceIn(0, 255)
            if (!(k > 0.4f && (time * 18).toInt() % 3 == 0)) sc.drawBitmap(bmp, src, dst, bmpP)
            bmpP.alpha = 255
            for (i in 0 until 26) {
                val sx = x + 8 + (i * 37 % 40); val rise = (k * 70f + i * 3f) % 70f
                val col = if (chapter == 2) (if (i % 3 == 0) WHITE else if (i % 3 == 1) 0xFFF080B0.toInt() else 0xFFF8C8DC.toInt()) else if (i % 3 == 0) PALE else if (i % 3 == 1) MID else DARK
                if (rnd.nextFloat() < 1f - k * 0.6f) box(sx + sin(time * 3f + i) * 3f, y + 50 - rise, sx + 2 + sin(time * 3f + i) * 3f, y + 52 - rise, col)
            }
            return
        }
        sc.drawBitmap(bmp, src, dst, bmpP)
        if (bossBurn > 0f) drawBurn(bx, by - 2, 30f, 40f, time, 5)
        // what Consecrate did to him
        val hx0 = bx; val hy0 = y + 2
        if (bossPara > 0f) { box(x + 14, y + 22, x + 42, y + 23, RUNE); box(x + 14, y + 34, x + 42, y + 35, RUNE) }
        if (bossConf > 0f) for (k in 0 until 3) { val a = time * 4f + k * 2.1f; box(hx0 + cos(a) * 12 - 1, hy0 + sin(a) * 3 - 1, hx0 + cos(a) * 12 + 1, hy0 + sin(a) * 3 + 1, GOLD) }
        if (bossWeak > 0f) { val k = (time * 2) % 1f; box(x + 18, y + 10 + k * 10, x + 20, y + 12 + k * 10, DARK); box(x + 38, y + 14 + k * 8, x + 40, y + 16 + k * 8, DARK) }
        if (phase == Phase.FIGHT && stage == Stage.DAIS && castRune < 0 && !talking && (time % 4f) < 1.2f) {
            val mm = if (chapter == 2) MUTTER2 else MUTTER
            val word = mm[((time / 4f).toInt()) % mm.size]
            val bw = word.length * 8f + 6; val bxx = x + 40; val byy = y + 2
            box(bxx - 1, byy - 1, bxx + bw + 1, byy + 12, INK); box(bxx, byy, bxx + bw, byy + 11, WHITE)
            sc.drawText(word, bxx + 3, byy + 9, font)
        }
    }

    private fun drawHero(time: Float) {
        if (invulnT > 0f && phase == Phase.FIGHT && (time * 16).toInt() % 2 == 0) return
        val moving = (dirX != 0 || dirY != 0) && paraT <= 0f
        val dirI = if (facing == 3) 2 else facing
        val s = if (moving) hero[dirI][if ((walkT * 6f).toInt() % 2 == 1) 1 else 0] else {
            // idle: breathe in and out, blink every few seconds, the scarf stirs now and then
            val idle = heroIdle[dirI]; val cyc = time % 4.2f
            val k = when {
                cyc in 3.0f..3.16f && idle.size > 2 -> 2
                dirI == 0 && cyc in 1.6f..2.1f -> 3
                ((time * 1.6f).toInt() % 2 == 1) -> 1
                else -> 0
            }
            idle[k.coerceAtMost(idle.size - 1)]
        }
        val x = hx.toInt().toFloat(); val y = hy.toInt().toFloat()
        p.color = 0x40000000; sc.drawOval(x + 3, y + 13, x + 13, y + 17, p)
        // 28 tall, standing on the same feet as ever
        src.set(0, 0, s.w, s.h)
        if (facing == 3) { sc.save(); sc.scale(-1f, 1f, x + 8, y + 8); dst.set(x - 1, y - 12, x + 17, y + 16); sc.drawBitmap(s.bmp, src, dst, bmpP); sc.restore() }
        else { dst.set(x - 1, y - 12, x + 17, y + 16); sc.drawBitmap(s.bmp, src, dst, bmpP) }
        if (paraT > 0f) {
            box(x + 1, y + 6, x + 15, y + 7, RUNE); box(x + 1, y + 11, x + 15, y + 12, RUNE)
            if ((time * 6).toInt() % 2 == 0) box(x + 7, y - 4, x + 9, y - 2, RUNE)
        }
        if (poisonT > 0f) {
            val c = 0xFF48A048.toInt(); val k = (time * 3) % 1f
            box(x + 3, y - 2 - k * 6, x + 5, y - k * 6, c); box(x + 11, y + 2 - ((k + 0.5f) % 1f) * 6, x + 13, y + 4 - ((k + 0.5f) % 1f) * 6, c)
        }
    }

    /**
     * The monsters, in the look of the old monster-raising games: round, chunky bodies, big eyes, dark
     * outlines and soft four-step shading (built from shaded blobs: see android/art notes). Every
     * monster comes in five looks: as it is, struck (white), frozen, burning, and brainwashed (an
     * ally's teal and gold); the behemoth has a sixth, enraged.
     */
    private class MonSet(rows: List<List<String>>) {
        val norm = rows.map { Sprite(it, MON_PAL, outline = null) }
        val flash = rows.map { r -> Sprite(r.map { it.map { ch -> if (ch == '.') '.' else 'W' }.joinToString("") }, MON_PAL, outline = null) }
        val ice = rows.map { Sprite(it, MON_PAL + MON_ICE, outline = null) }
        val burn = rows.map { Sprite(it, MON_PAL + MON_BURN, outline = null) }
        val ally = rows.map { Sprite(it, MON_PAL + MON_ALLY, outline = null) }
        val rage = rows.map { Sprite(it, MON_PAL + MON_RAGE, outline = null) }
        fun pick(m: Minion, f: Int, time: Float) = when {
            m.hurtT > 0f && (time * 20).toInt() % 2 == 0 -> flash[f]
            m.ice > 0f -> ice[f]
            m.burn > 0f && (time * 10).toInt() % 2 == 0 -> burn[f]
            m.ally -> ally[f]
            m.rage > 0f && (time * 6).toInt() % 2 == 0 -> rage[f]
            else -> norm[f]
        }
    }
    /** Imp 16x16 (two hops), stalker 22x15 (run, run, crouch), behemoth 32x32 (walk, walk, slam, dazed). */
    private val impSet = MonSet(IMP_ROWS)
    private val stalkSet = MonSet(STALK_ROWS)
    private val behSet = MonSet(BEH_ROWS)
    private val gazeSet = MonSet(GAZE_ROWS)
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
    private fun drawShade(m: Minion, time: Float) {
        val a = when (m.st) { 1 -> m.stT / 0.5f; 2 -> 0f; 3 -> 1f - m.stT / 0.5f; else -> 1f }.coerceIn(0f, 1f) * (if (m.hurtT > 0f) 1f else 0.78f)
        if (a <= 0.02f) return
        val al = (a * 255).toInt()
        val x = m.x; val y = m.y
        // a wisp of a body, trailing smoke behind as it drifts
        for (i in 0 until 5) {
            val t = i / 4f; val sx = x - m.face * i * 2.5f + sin(time * 3f + i) * 1.5f; val sy = y - 8 + i * 1.5f
            p.color = ((al * (1f - t * 0.7f) * 0.8f).toInt() shl 24) or 0x0E0A16
            sc.drawOval(sx - 5 + t * 2, sy - 6 + t * 2, sx + 5 - t * 2, sy + 6 - t, p)
        }
        p.color = (al shl 24) or 0x08060E; sc.drawOval(x - 4, y - 16, x + 4, y - 6, p)
        // two cold eyes
        val ec = if (m.hurtT > 0f) 0xFFFFFF else 0xC8E0FF
        p.color = (al shl 24) or ec
        sc.drawRect(x - 2 + m.face, y - 12, x - 1 + m.face, y - 11, p); sc.drawRect(x + 1 + m.face, y - 12, x + 2 + m.face, y - 11, p)
    }
    private fun drawMinion(m: Minion, time: Float) {
        if (m.kind == 4) { drawShade(m, time); return }
        if (m.kind == 2) { drawBehemoth(m, time); return }
        if (m.kind == 1) { drawStalker(m, time); return }
        if (m.kind == 3) { drawGazer(m, time); return }
        // the imp: a round little devil that hops after you
        val held = m.para > 0f || m.stun > 0f || m.ice > 0f
        val f = if (held) 0 else (m.step * 6).toInt() % 2
        val x = m.x.toInt() - 8f; val y = m.y.toInt() + 5f - 16f
        allyRing(m, 7f, time)
        p.color = 0x40000000; sc.drawOval(m.x - 6, m.y + 3, m.x + 6, m.y + 6, p)
        val s = impSet.pick(m, f, time)
        sc.save(); if (m.face < 0f) sc.scale(-1f, 1f, m.x, 0f)
        src.set(0, 0, s.w, s.h); dst.set(x, y, x + 16, y + 16); sc.drawBitmap(s.bmp, src, dst, bmpP)
        sc.restore()
        if (m.ice > 0f) {
            // locked in a block of ice
            p.color = 0x55C8ECFF; sc.drawRect(x, y + 1, x + 16, y + 17, p)
            box(x, y + 1, x + 1, y + 9, WHITE); box(x + 1, y + 1, x + 8, y + 2, WHITE); box(x + 15, y + 7, x + 16, y + 17, 0xFF98C8E8.toInt())
            if ((time * 3).toInt() % 4 == 0) box(x + 3, y + 3, x + 4, y + 4, WHITE)
        }
        drawFoeMarks(m, y + 2, 14f, time)
        if (m.stun > 0f && (time * 8).toInt() % 2 == 0) { box(x + 3, y - 2, x + 5, y, WHITE); box(x + 11, y - 2, x + 13, y, WHITE) }
        drawAllyBar(m, y, time)
    }
    private val toadSpr = TOAD_ROWS.map { Sprite(it, MON_PAL, outline = null) }
    private val mothSpr = MOTH_ROWS.map { Sprite(it, MON_PAL, outline = null) }
    private val mercSets = listOf(IMP_ROWS, STALK_ROWS, BEH_ROWS, GAZE_ROWS).map { rows -> rows.map { Sprite(it, MON_PAL + MON_MERC, outline = null) } }
    /** Fort folk: idle, turning to face you, a price over their heads. */
    private fun drawNpc(n: Npc, time: Float) {
        val near = hypot(hx + 8 - n.x, hy + 13 - n.y) < 40f
        val face = if ((n.role <= WildLevel.NPC_MERC || near) && !n.moving) (if (hx + 8 < n.x) -1f else 1f) else n.face
        val ph = n.homeX * 0.13f + n.homeY * 0.07f
        val f = ((time * (if (n.moving) 6f else 1.6f) + ph).toInt()) % 2
        val sk = if (n.role == WildLevel.NPC_FOLK) n.kind else n.kind
        val set = mercSets[(if (n.role == WildLevel.NPC_FOLK) sk - 2 else sk).coerceIn(0, mercSets.size - 1)]
        val s = when {
            n.role == WildLevel.NPC_TOAD || (n.role == WildLevel.NPC_FOLK && sk == 0) -> toadSpr[f]
            n.role == WildLevel.NPC_MOTH || (n.role == WildLevel.NPC_FOLK && sk == 1) -> mothSpr[f]
            else -> set[f.coerceAtMost(set.size - 1)]
        }
        // idle: breathing, a sway, now and then a little hop or a look about
        val cyc = (time + ph) % 5f
        val hop = if (cyc in 2.0f..2.3f) -sin((cyc - 2f) / 0.3f * 3.14f) * 3f else 0f
        val moth = n.role == WildLevel.NPC_MOTH || (n.role == WildLevel.NPC_FOLK && sk == 1)
        // busy: a townsfolk stopped somewhere does a little something (a nod, a sweep, a stretch)
        val busyBob = if (n.role == WildLevel.NPC_FOLK && !n.moving) when (n.busy) { 0 -> abs(sin(time * 5f + ph)) * -1.5f; 1 -> 0f; else -> if ((time * 2 + ph).toInt() % 3 == 0) -2f else 0f } else 0f
        val bob = if (moth) sin(time * 3f) * 2f - 6f else hop + busyBob + (if ((time * 1.4f + ph).toInt() % 2 == 0) 0f else 1f) - (if (n.actT > 0f) 2f else 0f)
        val w = s.w.toFloat(); val h = s.h.toFloat()
        p.color = 0x40000000; sc.drawOval(n.x - w * 0.35f, n.y - 2, n.x + w * 0.35f, n.y + 2, p)
        val look = if (n.role == WildLevel.NPC_MERC && cyc in 3.2f..4.0f) -face else face
        sc.save(); if (look < 0f && !moth) sc.scale(-1f, 1f, n.x, 0f)
        src.set(0, 0, s.w, s.h); dst.set(n.x - w / 2, n.y - h + bob, n.x + w / 2, n.y + bob); sc.drawBitmap(s.bmp, src, dst, bmpP)
        // a townsfolk sweeping: a broom going side to side
        if (n.role == WildLevel.NPC_FOLK && !n.moving && n.busy == 1) { val sw = sin(time * 6f + ph) * 3f; line1.color = 0xFF8A5A30.toInt(); sc.drawLine(n.x + w * 0.3f, n.y - h * 0.5f, n.x + w * 0.45f + sw, n.y, line1); box(n.x + w * 0.45f + sw - 2, n.y - 2, n.x + w * 0.45f + sw + 2, n.y, 0xFFD8B450.toInt()) }
        sc.restore()
        if (n.role == WildLevel.NPC_GUARD) {
            // a guard: a spear and a red pennant, and a helm
            val sx = n.x + look * (w * 0.4f)
            line1.color = 0xFF6A4A2A.toInt(); sc.drawLine(sx, n.y - h - 6 + bob, sx, n.y + bob, line1)
            box(sx - 1, n.y - h - 9 + bob, sx + 1, n.y - h - 5 + bob, PALE)
            box(sx + (if (look > 0f) 1f else -5f), n.y - h - 4 + bob, sx + (if (look > 0f) 5f else -1f), n.y - h - 1 + bob, 0xFFC83030.toInt())
            box(n.x - 4, n.y - h + bob - 1, n.x + 4, n.y - h + bob + 2, 0xFF8890A0.toInt()); box(n.x - 3, n.y - h + bob - 1, n.x + 3, n.y - h + bob, 0xFFC8D0E0.toInt())
            if (n.actT > 0f) { line1.color = if ((time * 20).toInt() % 2 == 0) WHITE else 0xFFC8F0FF.toInt(); val rr = (0.5f - n.actT) * 50f; sc.drawOval(n.x - rr, n.y - rr * 0.45f, n.x + rr, n.y + rr * 0.45f, line1) }
        }
    }
    /** The gazer, hovering over its shadow; it fades out and in when it blinks. */
    private fun drawGazer(m: Minion, time: Float) {
        val bob = sin(time * 4f + m.flank * 3f) * 2f
        val fade = when (m.st) { 1 -> (m.stT / 0.45f).coerceIn(0f, 1f); 2 -> 1f - (m.stT / 0.35f).coerceIn(0f, 1f); else -> 1f }
        p.color = ((0x40 * fade).toInt() shl 24); sc.drawOval(m.x - 5, m.y + 2, m.x + 5, m.y + 5, p)
        allyRing(m, 6f, time)
        val f = if (m.para > 0f || m.ice > 0f) 0 else (time * 7f + m.flank).toInt().mod(2)
        val s = gazeSet.pick(m, f, time)
        val x = m.x.toInt() - 8f; val y = m.y.toInt() - 20f + bob
        sc.save(); if (m.face < 0f) sc.scale(-1f, 1f, m.x, 0f)
        bmpP.alpha = (fade * 255).toInt().coerceIn(0, 255)
        src.set(0, 0, s.w, s.h); dst.set(x, y, x + 16, y + 16); sc.drawBitmap(s.bmp, src, dst, bmpP)
        bmpP.alpha = 255
        sc.restore()
        if (m.ice > 0f) { p.color = 0x55C8ECFF; sc.drawRect(x, y, x + 16, y + 16, p) }
        drawFoeMarks(m, y, 12f, time)
        drawAllyBar(m, y, time)
    }
    /** A brainwashed foe: a teal ring under its feet, so you know it's yours. */
    private fun allyRing(m: Minion, r: Float, time: Float) {
        if (!m.ally) return
        line1.color = if ((time * 4).toInt() % 2 == 0) 0xFF5CD8B0.toInt() else 0xFFF8E070.toInt()
        sc.drawOval(m.x - r - 2, m.y + 2, m.x + r + 2, m.y + 7, line1)
    }
    /** An ally's health, when it's been hurt. */
    private fun drawAllyBar(m: Minion, top: Float, time: Float) {
        if (!m.ally || m.hp >= m.maxHp) return
        box(m.x - 8, top - 4, m.x + 8, top - 1, INK); box(m.x - 7, top - 3.5f, m.x - 7 + 14f * m.hp.coerceAtLeast(0) / m.maxHp, top - 1.5f, 0xFF5CD8B0.toInt())
    }

    /** Hex sigil, holds, burning, confusion: the marks any foe can carry. */
    private fun drawFoeMarks(m: Minion, top: Float, w: Float, time: Float) {
        if (m.slow > 0f) {
            val r = w * 0.6f + sin(time * 5f)
            for (i in 0 until 12) { val a = i * 0.5236f + time * 2f; box(m.x + cos(a) * r - 0.5f, m.y + 4 + sin(a) * r * 0.35f, m.x + cos(a) * r + 0.5f, m.y + 5 + sin(a) * r * 0.35f, if (i % 2 == 0) RUNE else 0xFFB070E0.toInt()) }
        }
        if (m.burn > 0f) drawBurn(m.x, m.y + 4, w * 0.8f, 11f, time, (m.hashCode() and 7))
        if (m.para > 0f) { box(m.x - w / 2 - 1, top + 4, m.x + w / 2 + 1, top + 5, RUNE); box(m.x - w / 2 - 1, m.y - 1, m.x + w / 2 + 1, m.y, RUNE) }
        if (m.conf > 0f) { val a = time * 6f; box(m.x + cos(a) * 6 - 1, top - 3, m.x + cos(a) * 6 + 1, top - 1, GOLD) }
        if (m.weak > 0f) box(m.x - 1, top - 3 + (time * 4) % 2f, m.x + 1, top - 1 + (time * 4) % 2f, MID)
        if (m.ice > 0f) { p.color = 0x55C8ECFF; sc.drawRect(m.x - w / 2 - 1, top - 1, m.x + w / 2 + 1, m.y + 5, p) }
    }
    /** The behemoth: rising through a burning crack in the floor, stomping, winding up a slam, or down on one knee. */
    private val drakeSet = MonSet(DRAKE_ROWS)
    private fun drawBehemoth(m: Minion, time: Float) {
        if (m.drake && (m.popT > 0f || m.airT > 0f)) {
            // in the air: its shadow on the ground, and the drake itself high above, wings up
            val h = if (m.popT > 0f) { val t = m.popT / DRAKE_DROP; t * t * 140f } else { val t = m.airT / DRAKE_AIR; sin(t * 3.1416f) * 110f }
            val k = (1f - h / 140f).coerceIn(0.2f, 1f)
            p.color = ((0x30 + (k * 0x40).toInt()) shl 24); sc.drawOval(m.x - 16 * k, m.y - 3 * k, m.x + 16 * k, m.y + 3 * k, p)
            if (h < 50f) { line1.color = 0xFFE04848.toInt(); sc.drawOval(m.x - DRAKE_R, m.y + 2 - DRAKE_R * 0.45f, m.x + DRAKE_R, m.y + 2 + DRAKE_R * 0.45f, line1) }
            val s = drakeSet.pick(m, 2, time)
            val x = m.x.toInt() - 20f; val y = m.y.toInt() + 4f - 34f - h
            sc.save(); if (m.face < 0f) sc.scale(-1f, 1f, m.x, 0f)
            src.set(0, 0, s.w, s.h); dst.set(x, y, x + 40, y + 34); sc.drawBitmap(s.bmp, src, dst, bmpP)
            sc.restore(); return
        }
        val x = m.x.toInt() - 16f; val feet = m.y.toInt() + 4f; val y = feet - 32f
        // the crack he came out of, still smouldering a moment
        if (m.popT > 0f) {
            val open = ((BEH_POP - m.popT) / (BEH_POP - 1.2f)).coerceIn(0f, 1f)
            val cw = 4f + 14f * open
            p.color = 0xFF120808.toInt(); sc.drawOval(m.x - cw, feet - 3f - 2f * open, m.x + cw, feet + 2f + 2f * open, p)
            for (i in -3..3) box(m.x + i * cw / 3.5f - 1, feet - 1 + abs(i) * 0.4f, m.x + i * cw / 3.5f + 1, feet + abs(i) * 0.4f, if ((time * 12 + i).toInt() % 2 == 0) 0xFFFF9628.toInt() else 0xFFFFE660.toInt())
            if (m.popT > 1.2f) {
                // two eyes burning down in the dark
                val glow = if ((time * 5).toInt() % 3 == 0) 0xFFFFFFFF.toInt() else 0xFFFFE660.toInt()
                if (open > 0.4f) { box(m.x - 4, feet - 1, m.x - 2, feet, glow); box(m.x + 2, feet - 1, m.x + 4, feet, glow) }
                return
            }
            // embers
            for (i in 0 until 6) { val ex = m.x + sin(i * 2.4f + time * 3f) * 12f; val ey = feet - ((time * 40f + i * 9f) % 36f); box(ex, ey, ex + 1, ey + 1, if (i % 2 == 0) 0xFFFF9628.toInt() else 0xFFFFE660.toInt()) }
        } else { p.color = 0x48000000; sc.drawOval(m.x - 12, feet - 3, m.x + 12, feet + 2, p) }
        // the slam: a red ring on the floor, filling as it comes
        if (m.slamT > 0f) {
            val k = 1f - m.slamT / (if (m.rage > 0f) 0.6f else 0.9f)
            p.color = ((0x30 + (k * 0x50).toInt()) shl 24) or 0xE02020; sc.drawOval(m.x - BEH_SLAM_R * k, m.y + 2 - BEH_SLAM_R * 0.45f * k, m.x + BEH_SLAM_R * k, m.y + 2 + BEH_SLAM_R * 0.45f * k, p)
            line1.color = 0xFFE04848.toInt(); sc.drawOval(m.x - BEH_SLAM_R, m.y + 2 - BEH_SLAM_R * 0.45f, m.x + BEH_SLAM_R, m.y + 2 + BEH_SLAM_R * 0.45f, line1)
        }
        // dazed: a golden ring to trace round him, pulsing
        if (m.daze > 0f) {
            val pulse = 0.5f + 0.5f * sin(time * 9f)
            line1.color = if (pulse > 0.5f) GOLD else 0xFFFFF4B0.toInt()
            val rr = 20f + pulse * 2f
            for (i in 0 until 24) if (i % 2 == 0) { val a0 = i * 0.2618f + time; val a1 = a0 + 0.2618f; sc.drawLine(m.x + cos(a0) * rr, m.y - 6 + sin(a0) * rr * 0.8f, m.x + cos(a1) * rr, m.y - 6 + sin(a1) * rr * 0.8f, line1) }
        }
        val f = when { m.daze > 0f -> 3; m.slamT > 0f -> 2; m.popT > 0f -> 0; else -> (m.step * 4).toInt() % 2 }
        val s = (if (m.drake) drakeSet else behSet).pick(m, f, time)
        allyRing(m, 13f, time)
        // rising: only what's above the floor shows
        val rise = if (m.popT > 0f) { val t = (m.popT / 1.2f).coerceIn(0f, 1f); t * t * 32f } else 0f
        sc.save()
        if (m.popT > 0f) sc.clipRect(x - 4, 0f, x + 36, feet + 1)
        if (m.face < 0f) sc.scale(-1f, 1f, m.x, 0f)
        if (m.drake) dst.set(x - 4, y - 2, x + 36, y + 32) else dst.set(x, y + rise, x + 32, y + 32 + rise)
        src.set(0, 0, s.w, s.h)
        sc.drawBitmap(s.bmp, src, dst, bmpP)
        sc.restore()
        if (m.popT > 0f) return
        if (m.rage > 0f) for (i in 0 until 2) { val sx = m.x - 6 + i * 12; val sy = y + 2 - ((time * 20f + i * 7f) % 10f); box(sx, sy, sx + 2, sy + 2, 0x88FFFFFF.toInt()) }
        if (m.daze > 0f) {
            for (i in 0 until 3) { val a = time * 4f + i * 2.09f; box(m.x + cos(a) * 9 - 1, y + 4 + sin(a) * 2, m.x + cos(a) * 9 + 1, y + 6 + sin(a) * 2, if (i % 2 == 0) GOLD else WHITE) }
            // how long he'll stay down
            box(m.x - 12, y - 4, m.x + 12, y - 1, INK); box(m.x - 11, y - 3.5f, m.x - 11 + 22f * (m.daze / 5.5f), y - 1.5f, GOLD)
        } else if (!m.ally && (m.hp < BEH_HP || m.hurtT > 0f)) {
            // the stagger bar: knock it down and he drops
            box(m.x - 12, y - 4, m.x + 12, y - 1, INK); box(m.x - 11, y - 3.5f, m.x - 11 + 22f * m.hp / BEH_HP, y - 1.5f, 0xFFF09030.toInt())
        }
        drawFoeMarks(m, y, 24f, time)
        drawAllyBar(m, y + 4, time)
        if (m.stun > 0f && m.daze <= 0f && (time * 8).toInt() % 2 == 0) { box(m.x - 5, y - 6, m.x - 3, y - 4, WHITE); box(m.x + 3, y - 6, m.x + 5, y - 4, WHITE) }
    }
    /** The stalker: low to the ground, a red line where it means to spring, afterimages as it goes. */
    private fun drawStalker(m: Minion, time: Float) {
        val feet = m.y.toInt() + 4f; val x = m.x.toInt() - 11f; val y = feet - 15f
        allyRing(m, 9f, time)
        p.color = 0x40000000; sc.drawOval(m.x - 8, feet - 2, m.x + 8, feet + 1, p)
        if (m.st == 1) {
            // the telegraph: a blinking red line along its spring
            line1.color = if ((time * 14).toInt() % 2 == 0) 0xFFFF3C3C.toInt() else 0x88FF3C3C.toInt()
            val len = HERO_SPEED * 3.2f * SLOW * 0.5f
            sc.drawLine(m.x, m.y + 1, m.x + m.dvx * len, m.y + 1 + m.dvy * len, line1)
        }
        val f = when { m.st == 1 -> 2; m.para > 0f || m.stun > 0f || m.ice > 0f || m.st == 3 -> 0; else -> (m.step * 8).toInt() % 2 }
        val s = stalkSet.pick(m, f, time)
        src.set(0, 0, s.w, s.h)
        if (m.st == 2) for (i in 1..3) {
            // afterimages behind the lunge
            val ax = x - m.dvx * i * 6f; val ay = y - m.dvy * i * 6f
            p.color = ((0x50 - i * 0x14) shl 24) or (if (m.ally) 0x22A08A else 0x2A2C5C); sc.drawRect(ax + 3, ay + 4, ax + 19, ay + 13, p)
        }
        sc.save()
        if (m.face < 0f) sc.scale(-1f, 1f, m.x, 0f)
        dst.set(x, y, x + 22, y + 15); sc.drawBitmap(s.bmp, src, dst, bmpP)
        sc.restore()
        if (m.st == 3 && (time * 6).toInt() % 2 == 0) box(m.x - 1, y - 4, m.x + 1, y - 2, WHITE)
        drawFoeMarks(m, y, 16f, time)
        drawAllyBar(m, y, time)
        if (m.stun > 0f && (time * 8).toInt() % 2 == 0) { box(m.x - 4, y - 4, m.x - 2, y - 2, WHITE); box(m.x + 2, y - 4, m.x + 4, y - 2, WHITE) }
    }

    /** Your companion: waiting to be found (with a ? over it), at your side, or knocked out on its back. */
    private fun drawFriend(time: Float) {
        val k = friend ?: return
        val ko = frState == 2
        val fl = k.flies && !ko
        val f0 = friendSprites[k.ordinal][0]
        val w = f0.w.toFloat(); val h = f0.h.toFloat()
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
            sc.drawBitmap(sp.bmp, src, dst, bmpP)
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
        val vx0 = camX - 40f; val vx1 = camX + W + 40f; val vy0 = camY - 20f; val vy1 = camY + H + 70f
        room.props.forEachIndexed { i, pp -> if (pp.kind != THICKET && pp.kind != PALISADE && pp.kind != PLAZA && pp.kind != CLIFF && pp.foot.right > vx0 && pp.foot.left < vx1 && pp.foot.bottom > vy0 && pp.foot.top < vy1) addEnt(pp.foot.bottom, 0, i) }
        addEnt(hy + 15, 1, 0)
        minions.forEachIndexed { i, m -> if (m.room == roomId) addEnt(m.y + 4, 2, i) }
        if (bossHere) addEnt(by, 3, 0)
        if (beast != null && beastRoom == roomId) addEnt(beast!!.y, 9, 0)
        npcs.forEachIndexed { i, n -> if (!n.gone && n.room == roomId) addEnt(n.y + 2, 10, i) }
        seeds.forEachIndexed { i, sd -> if (sd.room == roomId) addEnt(sd.y, 8, i) }
        orbs.forEachIndexed { i, o -> if (o.room == roomId) addEnt(o.y + 4, 4, i) }
        drops.forEachIndexed { i, d -> if (d.room == roomId) addEnt(d.y + 4, 5, i) }
        if (wild == null && spiritX in camX - 30f..camX + W + 30f && spiritY in camY - 30f..camY + H + 40f) addEnt(spiritY + 14, 6, 0)
        comps.forEachIndexed { i, c -> if (c.kind != null && c.room == roomId) addEnt(c.y, 7, i) }
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
                7 -> { cur = comps[e.idx]; drawFriend(time); cur = comps[0] }
                8 -> drawSeed(seeds[e.idx], time)
                9 -> beast?.let { drawWoodBlossom(it, time) }
                10 -> drawNpc(npcs[e.idx], time)
                else -> drawDrop(drops[e.idx], time)
            }
        }
        drawFogBanks(time)
    }
    /** Fog over the fog banks' screens: slow drifting sheets, thinner near you so you can still see. */
    private fun drawFogBanks(time: Float) {
        val wl = wild ?: return
        if (wl.fog.isEmpty()) return
        val c0 = (camX / 240f).toInt() - 1; val c1 = ((camX + W) / 240f).toInt() + 1
        val r0 = (camY / 176f).toInt() - 1; val r1 = ((camY + H) / 176f).toInt() + 1
        for (cy in r0..r1) for (cx in c0..c1) {
            if (cx < 0 || cy < 0 || cx >= wl.w || cy >= wl.h || (cy * wl.w + cx) !in wl.fog) continue
            val ox = cx * 240f; val oy = cy * 176f
            for (i in 0 until 14) {
                val sx = ox + ((i * 53 + time * (6f + i % 3 * 3f)) % 280f) - 20f
                val sy = oy + (i * 37 % 170) + sin(time * 0.4f + i) * 6f
                val near = hypot(sx - (hx + 8), sy - (hy + 10))
                val a = (0x58 * (near / 70f).coerceIn(0.25f, 1f)).toInt()
                p.color = (a shl 24) or 0xC8CCD8
                sc.drawOval(sx - 34, sy - 9, sx + 34, sy + 9, p)
            }
        }
    }

    /** A companion's wall: a hedge of thorns, a veil of moonlight, a wall of ice, a stack of stones. */
    private fun drawWall(w: Ward, time: Float) {
        val q = w.pts; val grow = ((w.max - w.life) / 0.35f).coerceIn(0f, 1f)
        // a dark line along its foot so it stands out on the white floor
        line2.color = 0x55203020; var j = 0; while (j + 3 < q.size) { sc.drawLine(q[j], q[j + 1] + 1.5f, q[j + 2], q[j + 3] + 1.5f, line2); j += 2 }
        var i = 0
        while (i + 3 < q.size) {
            val x0 = q[i]; val y0 = q[i + 1]; val x1 = q[i + 2]; val y1 = q[i + 3]
            val len = hypot(x1 - x0, y1 - y0); val n = maxOf(1, (len / 3f).toInt())
            for (k in 0..n) {
                val t = k / n.toFloat(); val x = x0 + (x1 - x0) * t; val y = y0 + (y1 - y0) * t
                val seed = ((x * 7 + y * 13).toInt() and 15)
                when (w.style) {
                    1 -> { val hh = (7f + (seed % 4)) * 1.3f * grow
                        box(x - 1.5f, y - hh, x + 1.5f, y + 1, LEAF_D); box(x - 0.5f, y - hh, x + 0.5f, y - 1, 0xFF4A6A3A.toInt())
                        if (seed % 3 == 0) { box(x + 1.5f, y - hh * 0.6f, x + 3, y - hh * 0.6f + 1, 0xFFE8DCC0.toInt()); box(x - 3, y - hh * 0.3f, x - 1.5f, y - hh * 0.3f + 1, 0xFFE8DCC0.toInt()) }
                        if (seed % 5 == 0) box(x - 1, y - hh - 1, x + 1, y - hh + 1, 0xFFF080B0.toInt()) }
                    2 -> { val hh = 16f * grow; val a = (0x60 + 0x40 * sin(time * 5f + seed)).toInt().coerceIn(0, 255)
                        p.color = (a shl 24) or 0xD8E8FF; sc.drawRect(x - 1.5f, y - hh, x + 1.5f, y + 1, p)
                        if ((seed + (time * 8).toInt()) % 6 == 0) box(x, y - hh * ((time * 0.7f + seed * 0.1f) % 1f), x + 1, y - hh * ((time * 0.7f + seed * 0.1f) % 1f) + 1, WHITE) }
                    3 -> { val hh = (9f + (seed % 5)) * 1.2f * grow
                        box(x - 2, y - hh, x + 2, y + 1, 0xFF98C8E8.toInt()); box(x - 1, y - hh + 1, x + 1, y, 0xFFD8F0FF.toInt()); box(x - 2, y - hh, x - 1, y - hh + 2, WHITE) }
                    5 -> { val hh = (8f + ((seed + (time * 12).toInt()) % 5)) * grow
                        box(x - 2, y - hh * 0.6f, x + 2, y + 1, 0xFFC83C1C.toInt()); box(x - 1.5f, y - hh, x + 1.5f, y - hh * 0.3f, 0xFFF48428.toInt()); box(x - 0.5f, y - hh * 0.8f, x + 0.5f, y - hh * 0.2f, 0xFFFFD65C.toInt()) }
                    6 -> { val hh = (8f + (seed % 4)) * 1.2f * grow
                        box(x - 1.5f, y - hh, x + 1.5f, y + 1, 0xFF56361E.toInt()); box(x - 0.5f, y - hh, x + 0.5f, y, 0xFF845632.toInt())
                        if (seed % 3 == 0) box(x - 1.5f, y - hh - 1.5f, x + 1.5f, y - hh + 1, 0xFFF6A0C0.toInt()) else if (seed % 3 == 1) box(x + 1, y - hh * 0.5f, x + 3, y - hh * 0.5f + 1, LEAF) }
                    else -> { val hh = 11f * grow
                        box(x - 2.5f, y - hh, x + 2.5f, y + 1, DARK); box(x - 2, y - hh + 0.5f, x + 2, y, MID); box(x - 2, y - hh + 0.5f, x + 1, y - hh + 1.5f, PALE)
                        if (seed % 4 == 0) box(x - 2, y - hh * 0.5f, x + 2, y - hh * 0.5f + 0.7f, DARK) }
                }
            }
            i += 2
        }
    }

    private fun j0(time: Float) = (time * 14).toInt() % 2 == 0
    private fun drawFury(time: Float) {
        if (furyT <= 0f) return
        val ring = furyRing ?: return
        line2.color = if ((time * 12).toInt() % 2 == 0) GOLD else 0xFFE04848.toInt()
        var i = 0; while (i + 3 < ring.size) { sc.drawLine(ring[i], ring[i + 1], ring[i + 2], ring[i + 3], line2); i += 2 }
    }
    private fun drawSeal(time: Float) {
        val sl = seal ?: return
        val m = sl.m; val k = (sl.t / SEAL_TIME).coerceIn(0f, 1f)
        line2.color = if ((time * 8).toInt() % 2 == 0) GOLD else WHITE
        sc.drawOval(m.x - 18, m.y - 22, m.x + 18, m.y + 6, line2)
        // the cut to make, flickering across him
        if ((time * 4).toInt() % 2 == 0) { line1.color = 0xFFE04848.toInt(); sc.drawLine(m.x - 22, m.y - 24, m.x + 22, m.y + 4, line1) }
        box(m.x - 12, m.y + 8, m.x + 12, m.y + 10, INK); box(m.x - 11, m.y + 8.5f, m.x - 11 + 22f * k, m.y + 9.5f, if (bag.wearing(Loot.RUNECASTER)) 0xFF5CD8B0.toInt() else GOLD)
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
            if (s.spore) {
                // a spore: a soft, pulsing seed-pod
                val r = 2.5f + sin(time * 14f + s.x) * 0.7f
                p.color = INK; sc.drawCircle(s.x, s.y, r + 1f, p); p.color = if (s.weak) MID else 0xFF9AD068.toInt(); sc.drawCircle(s.x, s.y, r, p)
                box(s.x - 1, s.y - 1, s.x, s.y, 0xFFF0FFD0.toInt())
            } else if (s.ally) {
                box(s.x - 2, s.y - 2, s.x + 2, s.y + 2, 0xFF14605A.toInt()); box(s.x - 1, s.y - 1, s.x + 1, s.y + 1, 0xFF5CD8B0.toInt())
            } else if (s.petal) {
                // a petal, tumbling
                val ph = time * 9f + s.x * 0.3f; val w = 1.5f + abs(sin(ph)) * 1.5f
                box(s.x - w - 0.5f, s.y - 1.5f, s.x + w + 0.5f, s.y + 1.5f, 0xFFB04878.toInt())
                box(s.x - w, s.y - 1f, s.x + w, s.y + 1f, if (s.weak) PALE else 0xFFF080B0.toInt()); box(s.x - 0.5f, s.y - 0.5f, s.x + 0.5f, s.y + 0.5f, WHITE)
            } else if (s.boss) {
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
    /**
     * The Vital Spark: a wispy sparkle glittering yellow and blue, as it first was, only bigger as
     * you level: a twinkling four-point star, strands of light curling off behind it, and glitter.
     */
    private fun drawFly(time: Float) {
        val s = flySize()
        val x = flyX + sin(time * 9f) * 1.2f; val y = flyY + cos(time * 7f) * 1.2f
        val YEL = 0xFFF27A; val BLU = 0x8CC8FF
        // wisps: strands of light trailing where it has been, curling
        val n = minOf(trailN, 6 + lvl / 2)
        for (strand in 0 until 2) for (i in 1 until n) {
            val a = 1f - i / n.toFloat()
            val wob = sin(time * 6f + i * 0.9f + strand * 3f) * (1.2f + i * 0.25f) * s * 0.6f
            val tx = trail[i * 2] + wob; val ty = trail[i * 2 + 1] + cos(time * 5f + i * 0.7f + strand * 2f) * s * 0.6f
            p.color = ((a * 170).toInt() shl 24) or (if ((i + strand) % 2 == 0) YEL else BLU)
            val r = (0.5f + 0.5f * a) * maxOf(1f, s * 0.6f)
            sc.drawRect(tx - r, ty - r, tx + r, ty + r, p)
        }
        // a soft glow
        p.color = 0x30000000 or YEL; sc.drawCircle(x, y, 4f * s, p)
        p.color = 0x28000000 or BLU; sc.drawCircle(x, y, 2.8f * s, p)
        // the twinkle: a four-point star breathing in and out, a smaller diagonal one turning
        val tw = 0.75f + 0.25f * sin(time * 7f)
        val arm = (3f + 1.5f * tw) * s
        p.color = 0xFF000000.toInt() or YEL
        sc.drawRect(x - arm, y - 0.5f * s.coerceAtLeast(1f) * 0.6f, x + arm, y + 0.5f * s.coerceAtLeast(1f) * 0.6f, p)
        sc.drawRect(x - 0.5f * s.coerceAtLeast(1f) * 0.6f, y - arm, x + 0.5f * s.coerceAtLeast(1f) * 0.6f, y + arm, p)
        line1.color = 0xCC000000.toInt() or BLU
        val d = arm * 0.55f; val rot = time * 1.3f
        for (k in 0 until 2) { val a = rot + k * 1.5708f + 0.785f; sc.drawLine(x - cos(a) * d, y - sin(a) * d, x + cos(a) * d, y + sin(a) * d, line1) }
        p.color = 0xFFFFFFFF.toInt(); val core = maxOf(1f, 0.9f * s); sc.drawRect(x - core, y - core, x + core, y + core, p)
        p.color = 0xFF000000.toInt() or YEL; sc.drawRect(x - core * 0.5f, y - core * 0.5f, x + core * 0.5f, y + core * 0.5f, p)
        // glitter: specks winking in and out around it, yellow and blue
        val specks = 5 + lvl
        for (i in 0 until specks) {
            val ph = (time * (1.3f + (i % 4) * 0.4f) + i * 0.37f) % 1f
            if (ph > 0.6f) continue
            val a = i * 2.39996f + time * 0.4f; val r = (2f + (i * 7 % 11) * 0.45f) * s
            val gx = x + cos(a) * r; val gy = y + sin(a) * r * 0.8f
            val c = if (i % 3 == 0) 0xFFFFFFFF.toInt() else (0xFF000000.toInt() or (if (i % 2 == 0) YEL else BLU))
            val sz = if (ph < 0.3f) 1f else 0.5f
            box(gx - sz, gy - sz, gx + sz, gy + sz, c)
            if (ph in 0.1f..0.25f && s >= 1.5f) { box(gx - 2 * sz, gy - 0.25f, gx + 2 * sz, gy + 0.25f, c); box(gx - 0.25f, gy - 2 * sz, gx + 0.25f, gy + 2 * sz, c) }
        }
        if (lvl >= 16) { line1.color = 0x66FFF4C0; sc.drawCircle(x, y, 5.5f * s + sin(time * 3f), line1) }
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

    /** A seed, then a sprout, then a thorn flower with a pink head that swells before it shoots. */
    private fun drawSeed(sd: Seed, time: Float) {
        val x = sd.x; val y = sd.y
        if (sd.life < 2f && (time * 10).toInt() % 2 == 0) return
        if (sd.t < 3f) {
            val g = sd.t / 3f; val h = 1f + g * 6f
            box(x - 1.5f, y - 1, x + 1.5f, y + 1, 0xFF7A5438.toInt())
            box(x - 0.5f, y - h, x + 0.5f, y, LEAF_D)
            box(x - 2.5f * g - 0.5f, y - h * 0.6f, x - 0.5f, y - h * 0.6f + 1, LEAF); box(x + 0.5f, y - h * 0.8f, x + 2.5f * g + 0.5f, y - h * 0.8f + 1, LEAF)
            return
        }
        val bob = sin(time * 3f + x) * 0.6f
        box(x - 4, y - 1, x + 4, y + 1, 0x40000000)
        box(x - 0.5f, y - 10, x + 0.5f, y, 0xFF3E6A30.toInt())
        for (k in 0 until 3) { val yy = y - 2.5f - k * 2.6f; val sgn = if (k % 2 == 0) -1f else 1f
            box(x + sgn * 0.5f, yy, x + sgn * 3.5f, yy + 1, LEAF_D); box(x + sgn * 3.5f, yy - 1, x + sgn * 4.5f, yy, 0xFFE8DCC0.toInt()) }
        val swell = if (sd.shotT < 0.5f) 1f else 0f
        val fx = x; val fy = y - 12 + bob; val r = 2.5f + swell
        for (k in 0 until 5) { val a = k * 1.2566f + time * 0.6f; box(fx + cos(a) * r - 1.5f, fy + sin(a) * r - 1.5f, fx + cos(a) * r + 1.5f, fy + sin(a) * r + 1.5f, if (k % 2 == 0) 0xFFF080B0.toInt() else 0xFFF8B8D0.toInt()) }
        box(fx - 1, fy - 1, fx + 1, fy + 1, 0xFFF8E070.toInt())
        if (swell > 0f) { p.color = 0x40F080B0; sc.drawCircle(fx, fy, 6f, p) }
    }
    /** A vine lash: first a line of sprouts (get out of the way), then thorny vines burst up along it. */
    /**
     * The mandala: a hexagram in a ring, a hexagon, six petal-circles and radial spokes, in gold,
     * rose and pale cyan, turning as it draws itself in, hovering a few pixels over the floor (its
     * shadow below), seen at the room's slant; when complete it flares and bursts.
     */
    private fun drawMandalas(time: Float) {
        for (m in mandalas) {
            if (m.room != roomId) continue
            val ch = (m.t / MANDALA_CHARGE).coerceIn(0f, 1f); val burst = ((m.t - MANDALA_CHARGE) / 0.5f).coerceIn(0f, 1f)
            val R = MANDALA_R * (1f + burst * 0.25f); val sq = 0.55f
            val lift = 5f + sin(time * 3f) * 1.2f
            val rot = time * 0.9f + m.x * 0.01f
            // its shadow on the floor
            p.color = (((0.18f * ch * (1f - burst)) * 255).toInt() shl 24) or 0x302040
            sc.drawOval(m.x - R, m.y - R * sq, m.x + R, m.y + R * sq, p)
            val cx = m.x; val cy = m.y - lift
            val a = ((if (m.hit) 1f - burst else 0.35f + 0.65f * ch) * 255).toInt().coerceIn(0, 255)
            fun pt(r: Float, ang: Float) = floatArrayOf(cx + cos(ang) * r, cy + sin(ang) * r * sq)
            fun seg(r0: Float, a0: Float, r1: Float, a1: Float, col: Int) { val p0 = pt(r0, a0); val p1 = pt(r1, a1); line1.color = (a shl 24) or (col and 0xFFFFFF); sc.drawLine(p0[0], p0[1], p1[0], p1[1], line1) }
            fun ring(r: Float, col: Int, n: Int = 32, upTo: Float = 1f) { var k = 0; while (k < (n * upTo).toInt()) { seg(r, rot + k * 6.2832f / n, r, rot + (k + 1) * 6.2832f / n, col); k++ } }
            val GOLDC = 0xF0CC50; val ROSE = 0xF080B0; val CYAN = 0x8CE0F8
            // it draws itself in: the outer ring, then the star, then the inner shapes
            ring(R, GOLDC, 36, (ch * 1.6f).coerceAtMost(1f))
            if (ch > 0.25f) ring(R * 0.92f, ROSE, 36, ((ch - 0.25f) * 2f).coerceAtMost(1f))
            if (ch > 0.35f) for (tri in 0..1) for (k in 0 until 3) {
                val a0 = rot + tri * 1.0472f + k * 2.0944f; val a1 = a0 + 2.0944f
                seg(R * 0.9f, a0, R * 0.9f, a1, if (tri == 0) GOLDC else CYAN)
            }
            if (ch > 0.5f) for (k in 0 until 6) { val a0 = -rot * 1.5f + k * 1.0472f; seg(R * 0.45f, a0, R * 0.45f, a0 + 1.0472f, ROSE); seg(R * 0.12f, a0, R * 0.45f, a0, CYAN) }
            if (ch > 0.65f) for (k in 0 until 6) {
                val a0 = rot + k * 1.0472f + 0.5236f; val c = pt(R * 0.62f, a0)
                line1.color = (a shl 24) or ROSE; sc.drawOval(c[0] - R * 0.2f, c[1] - R * 0.2f * sq, c[0] + R * 0.2f, c[1] + R * 0.2f * sq, line1)
            }
            // glinting nodes on the star's points, and a bright heart when it's about to go
            for (k in 0 until 6) { val q = pt(R * 0.9f, rot + k * 1.0472f); box(q[0] - 1, q[1] - 1, q[0] + 1, q[1] + 1, if ((time * 8 + k).toInt() % 2 == 0) WHITE else GOLD) }
            if (ch > 0.8f && !m.hit && (time * 14).toInt() % 2 == 0) { p.color = 0x60FFFFFF; sc.drawOval(cx - R * 0.2f, cy - R * 0.2f * sq, cx + R * 0.2f, cy + R * 0.2f * sq, p) }
            if (m.hit) {
                // the burst: a ring of light rising off it
                p.color = (((1f - burst) * 150).toInt() shl 24) or 0xFFF0C0
                sc.drawOval(cx - R, cy - R * sq - burst * 16f, cx + R, cy + R * sq - burst * 16f, p)
                for (k in 0 until 10) { val q = pt(R * (0.3f + burst * 0.8f), k * 0.628f + rot); box(q[0], q[1] - burst * 20f, q[0] + 1, q[1] - burst * 20f + 2, if (k % 2 == 0) GOLD else 0xFFF8C8DC.toInt()) }
            }
        }
    }

    private fun drawLashes(time: Float) {
        for (l in lashes) {
            if (l.room != roomId) continue
            val n = (hypot(l.x1 - l.x0, l.y1 - l.y0) / 5f).toInt().coerceAtLeast(1)
            for (k in 0..n) {
                val t = k / n.toFloat(); val x = l.x0 + (l.x1 - l.x0) * t; val y = l.y0 + (l.y1 - l.y0) * t
                if (l.t < 0.75f) {
                    if (t > l.t / 0.75f * 1.2f) continue
                    box(x - 1, y - 1 - (k % 2), x + 1, y, LEAF); box(x - 1.5f, y, x + 1.5f, y + 1, 0x557A5438)
                } else {
                    val grow = ((l.t - 0.75f) / 0.15f).coerceIn(0f, 1f) * (1f - ((l.t - 1.1f) / 0.3f).coerceIn(0f, 1f))
                    val h = (6f + (k * 7 % 5)) * grow
                    box(x - 1.5f, y - h, x + 1.5f, y + 1, 0xFF3E6A30.toInt()); box(x - 0.5f, y - h, x + 0.5f, y, LEAF)
                    if (k % 2 == 0) box(x + 1.5f, y - h * 0.6f, x + 3, y - h * 0.6f + 1, 0xFFE8DCC0.toInt())
                    if (k % 4 == 1 && grow > 0.5f) { box(x - 2, y - h - 2, x + 2, y - h + 1, 0xFFF080B0.toInt()); box(x - 0.5f, y - h - 1, x + 0.5f, y - h, 0xFFF8E070.toInt()) }
                }
            }
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
                FX_PETALS -> {
                    // a burst of petals, drifting as they fall
                    for (j in 0 until 22) {
                        val a = j * 0.2856f + j * 0.37f; val r = 4f + 44f * k * (0.6f + (j % 5) * 0.1f)
                        val px = f.x + cos(a) * r + sin(time * 4f + j) * 2f; val py = f.y + sin(a) * r * 0.6f + k * 14f
                        box(px - 1.5f, py - 1f, px + 1.5f, py + 1f, if (j % 3 == 0) WHITE else if (j % 3 == 1) 0xFFF080B0.toInt() else 0xFFF8C8DC.toInt())
                    }
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
                    val col = when (f.a.toInt()) { 1 -> LEAF; 2 -> 0xFFD8E8FF.toInt(); 3 -> 0xFFA8E0F8.toInt(); 5 -> if (j0(time)) 0xFFF48428.toInt() else 0xFFFFD65C.toInt(); 6 -> 0xFFF6A0C0.toInt(); else -> PALE }
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
                p.color = 0x5578E088; sc.drawCircle(mx, my, 3f, p)
                box(mx - 1.5f, my - 1.5f, mx + 1.5f, my + 1.5f, if (j % 2 == 0) 0xFF78E088.toInt() else 0xFFFFF27A.toInt())
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
        box(19f, 2f, 71f, 8f, INK); box(20f, 3f, 70f, 7f, 0xFFF080B0.toInt())
        val f = hp / maxHp.toFloat()
        box(20f, 3f, 20f + 50f * f, 7f, rarityCol(Rarity.EXOTIC, time))
        // your level, and the experience toward the next as a gold line under the health
        font.color = GOLD; sc.drawText("L$lvl", 74f, 9f, font); font.color = INK
        box(20f, 8f, 20f + 50f * (if (lvl >= 20) 1f else xp / xpNeed(lvl).toFloat()), 9f, GOLD)
        // energy toward Fury (or the Fury itself, burning down)
        box(100f, 2f, 124f, 8f, INK)
        if (furyT > 0f) box(101f, 3f, 101f + 22f * (furyT / 8f), 7f, if ((time * 10).toInt() % 2 == 0) GOLD else 0xFFE04848.toInt())
        else box(101f, 3f, 101f + 22f * (energy / ENERGY_MAX), 7f, if (energy > ENERGY_MAX * 0.9f && (time * 6).toInt() % 2 == 0) WHITE else 0xFFF0A030.toInt())
        var sx = 128f
        fun tag(t: String, col: Int) { box(sx, 1f, sx + 9, 9f, col); font.color = WHITE; sc.drawText(t, sx + 1, 9f, font); font.color = INK; sx += 11 }
        if (paraT > 0f) tag("B", RUNE)
        if (poisonT > 0f) tag("V", 0xFF48A048.toInt())
        if (invertT > 0f) tag("M", 0xFFD07020.toInt())
        if (shieldHits > 0) tag("S", DARK)
        if (hasteT > 0f) tag("H", 0xFF3CA0C8.toInt())
        if (bulwarkT > 0f) tag("W", GOLD_D)
        // your companions' health, one under the other
        comps.forEachIndexed { i, c ->
            cur = c
            val k = friend
            if (k != null && frState != 0) {
                val oy = i * 9f
                box(0f, 10f + oy, 74f, 19f + oy, INK)
                font.color = if (frState == 2) 0xFFE04848.toInt() else WHITE; sc.drawText(k.label.take(1), 2f, 18f + oy, font)
                box(11f, 12f + oy, 71f, 17f + oy, INK); box(12f, 13f + oy, 70f, 16f + oy, 0xFFF080B0.toInt())
                if (frState == 2) { if ((time * 2).toInt() % 2 == 0) { font.color = 0xFFE04848.toInt(); sc.drawText("K.O.", 26f, 18f + oy, font) } }
                else {
                    box(12f, 13f + oy, 12f + 58f * frHp / k.maxHp, 16f + oy, rarityCol(Rarity.EXOTIC, time + i * 0.4f))
                    if (frSkill <= 0f && (time * 3).toInt() % 2 == 0) box(68f, 13f + oy, 70f, 16f + oy, WHITE)
                }
                font.color = INK
            }
        }
        cur = comps[0]
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
        if (inWild) { drawWildHud(time); return }
        // his health, with a notch where his last stand begins
        val l = 140f; val r = 236f
        box(l, 2f, r, 8f, DARK)
        val fill = (r - l - 2) * bossHp / bossMax
        box(l + 1, 3f, l + 1 + fill, 7f, if (bossHurtT > 0f && (time * 20).toInt() % 2 == 0) WHITE else if (chapter == 2) (if (stage == Stage.DAIS) 0xFFE070A8.toInt() else 0xFFE04870.toInt()) else if (stage == Stage.DAIS) 0xFFB040C0.toInt() else 0xFFE04848.toInt())
        val notch = l + 1 + (r - l - 2) * bossLast / bossMax
        box(notch, 1f, notch + 1, 9f, WHITE)
    }

    /** The Runecaster's book: six runes; trace one on the XY pad before the bar runs out. */
    /** The brainwashing: its runes in a row, the one to trace now lit, and the time left for it. */
    private fun drawWash(time: Float) {
        val w = wash ?: return
        val n = w.seq.size; val cw = 40f
        val l = (W - n * cw) / 2f - 6; val t = 104f; val r = l + n * cw + 12; val b = t + 56f
        box(l - 1, t - 1, r + 1, b + 1, INK); box(l, t, r, b, 0xFF102A28.toInt()); box(l + 1, t + 1, r - 1, b - 1, 0xFF1A3E3A.toInt())
        font.color = 0xFF5CD8B0.toInt(); sc.drawText("BRAINWASH ${kindName(w.m.kind)}", l + 6, t + 10, font)
        for (i in 0 until n) {
            val cx = l + 6 + i * cw; val cy = t + 14
            val done = i < w.i; val now = i == w.i
            box(cx, cy, cx + cw - 4, cy + cw - 6, if (now) 0xFF2A6A60.toInt() else 0xFF14322E.toInt())
            line2.color = when { done -> 0xFF5CD8B0.toInt(); now -> if ((time * 4).toInt() % 2 == 0) GOLD else WHITE; else -> 0xFF4A7A72.toInt() }
            val pts = WASH_SHAPES[w.seq[i]]; val g = cw - 14
            for (j in 1 until pts.size) sc.drawLine(cx + 5 + pts[j - 1].first * g, cy + 3 + pts[j - 1].second * (g - 4), cx + 5 + pts[j].first * g, cy + 3 + pts[j].second * (g - 4), line2)
            if (done) { box(cx + cw - 12, cy + 2, cx + cw - 6, cy + 8, 0xFF5CD8B0.toInt()) }
        }
        box(l + 6, b - 6, r - 6, b - 3, 0xFF14322E.toInt()); box(l + 6, b - 6, l + 6 + (r - l - 12) * (w.t / w.max).coerceIn(0f, 1f), b - 3, if (w.t < 1.2f && (time * 8).toInt() % 2 == 0) 0xFFE04848.toInt() else GOLD)
        // spirals of light round the one being turned
        for (i in 0 until 8) { val a = time * 5f + i * 0.785f; val rr = 10f + (i % 2) * 3f; val mx0 = w.m.x - camX; val my0 = w.m.y - camY; box(mx0 + cos(a) * rr - 0.5f, my0 - 6 + sin(a) * rr * 0.6f - 0.5f, mx0 + cos(a) * rr + 0.5f, my0 - 6 + sin(a) * rr * 0.6f + 0.5f, if (i % 2 == 0) 0xFF5CD8B0.toInt() else GOLD) }
        font.color = INK
    }
    private fun drawRuneBook(time: Float) {
        if (runePromptT <= 0f || (castRune >= 0 && bossHere)) return
        val l = 14f; val t = 18f; val r = 226f; val b = 132f
        box(l - 1, t - 1, r + 1, b + 1, INK); box(l, t, r, b, WHITE); box(l + 1, t + 1, r - 1, b - 1, 0xFFF4F0E4.toInt())
        font.color = RUNE; sc.drawText("TRACE A RUNE", l + 6, t + 10, font)
        // the spells still to come this time: three pips
        for (k in 0 until RUNE_MAX) { val on = k < RUNE_MAX - runeCasts; val qx = l + 98f + k * 9f; box(qx, t + 4, qx + 6, t + 10, if (on) RUNE else PALE); if (on) box(qx + 1, t + 5, qx + 3, t + 7, WHITE) }
        font.color = if (runeCasts > 0) INK else MID; sc.drawText("${RUNE_MAX - runeCasts} LEFT", l + 128, t + 10, font); font.color = RUNE
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
        if (castRune < 0 || !bossHere) return
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

    /** In the wood: level, keys, power, the beast's health when it's here; and a map of the screens you've seen. */
    private fun drawWildHud(time: Float) {
        val wl = wild ?: return
        font.color = 0xFF9AD068.toInt(); sc.drawText("WOOD $depth", 152f, 9f, font)
        // coins, in the bar's second row
        box(152f, 11f, 159f, 18f, INK); box(153f, 12f, 158f, 17f, GOLD); box(154f, 13f, 155f, 15f, WHITE)
        font.color = GOLD; sc.drawText("$coins", 162f, 18f, font)
        // keys: gold if held, outline if still to find
        val nWood = wl.keys.size - 1; val woodFound = keysTaken.count { !wl.isStone(it) }
        for (i in 0 until nWood) {
            val kx = 190f + i * 9f
            box(kx, 2f, kx + 7, 8f, if (i < keys) 0xFFB07E48.toInt() else if (i < woodFound) DARK else 0xFF303040.toInt())
            box(kx + 1, 3f, kx + 3, 5f, INK)
        }
        run {
            val kx = 190f + nWood * 9f + 2f; val got = stoneKeys > 0; val found = keysTaken.any { wl.isStone(it) }
            box(kx, 1f, kx + 9, 9f, if (got) 0xFF9A948A.toInt() else if (found) DARK else 0xFF303040.toInt())
            box(kx + 2, 3f, kx + 5, 6f, if (got) 0xFFA0E0FF.toInt() else INK)
        }
        if (partyOn.isNotEmpty()) { font.color = 0xFFE08850.toInt(); sc.drawText("${(powerK * 100).toInt()}%", 120f, 9f, font) }
        font.color = INK
        val b = beast
        if (b != null && beastRoom == roomId) {
            box(60f, H - 14f, W - 60f, H - 6f, INK)
            box(61f, H - 13f, 61f + (W - 122f) * b.hp / b.maxHp, H - 7f, if (bossHurtT > 0f && (time * 20).toInt() % 2 == 0) WHITE else 0xFF9AD068.toInt())
            font.color = WHITE; sc.drawText(b.name, 62f, H - 16f, font); font.color = INK
        }
        // the map: screens seen, where you are (blinking), keys seen and not yet taken, gates, the door
        val mx = W - 4f - wl.w * 4f; val my = 12f
        box(mx - 2, my - 2, mx + wl.w * 4f + 1, my + wl.h * 4f + 1, 0x90101018.toInt())
        for (r in 0 until wl.n) {
            val id = 100 + r
            val cx = mx + (r % wl.w) * 4f; val cy = my + (r / wl.w) * 4f
            if (id !in visited) {
                // the wood you haven't seen, faintly, and where the keys lie
                box(cx, cy, cx + 3, cy + 3, 0xFF34403A.toInt())
                wl.keys.forEachIndexed { i, k -> if (k[0] == r && i !in keysTaken && (time * 3).toInt() % 2 == 0) box(cx + 1, cy + 1, cx + 2, cy + 2, GOLD) }
                continue
            }
            val here = r == ry * wl.w + rx
            box(cx, cy, cx + 3, cy + 3, if (here && (time * 4).toInt() % 2 == 0) GOLD else if (r == wl.exit) 0xFF9AD068.toInt() else 0xFFB8C8B0.toInt())
            val d = wl.doors[r]
            if ('E' in d) box(cx + 3, cy + 1, cx + 4, cy + 2, if (r * 4 + 1 in wl.locked) 0xFFE04848.toInt() else 0xFF708068.toInt())
            if ('S' in d) box(cx + 1, cy + 3, cx + 2, cy + 4, if (r * 4 + 3 in wl.locked) 0xFFE04848.toInt() else 0xFF708068.toInt())
            wl.keys.forEachIndexed { i, k -> if (k[0] == r && i !in keysTaken) box(cx + 1, cy + 1, cx + 2, cy + 2, GOLD) }
        }
    }
    private fun drawOverlays(time: Float) {
        if (bannerT > 0f && castRune < 0) banner(wild?.let { it.names[ry * it.w + rx] } ?: zoneRoom.name, 14f)
        if (msgT > 0f && msg.isNotEmpty() && !talking && wash == null) banner(msg, H - 58f)
        if (talking) return
        if (phase == Phase.LOST) { banner("YOU FELL...", 70f); if ((time * 2).toInt() % 2 == 0) banner("A: TRY AGAIN", 86f) }
        if (phase == Phase.WON && chestOpen && nearChest() && (time * 2).toInt() % 2 == 0) banner(if (chapter == 1) "A: CONTINUE" else "A: INTO THE WILDWOOD", 86f)
        if (nearSpirit()) banner("A: SPEAK", 90f)
        if (inWild && phase == Phase.FIGHT && msgT <= 0f) nearNpc()?.let { banner("A: TALK", H - 58f) }
        if (phase == Phase.WON && zone == 1 && wild == null && !chestOpen && nearChest() && !nearSpirit()) banner("A: OPEN", 90f)
        else if ((phase == Phase.FIGHT || phase == Phase.WON) && msgT <= 0f) reachable().takeIf { it >= 0 }?.let { banner("A: " + actLabel(room.props[it].kind), H - 58f) }
    }
    private fun nearChest() = wild == null && hypot(hx + 8 - 360f, hy + 12 - 74f) < 26f

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
        if (choosing) { drawChoose(time); return }
        val wx = true
        if (wx) { sc.save(); sc.translate(-camX.toInt().toFloat(), -camY.toInt().toFloat()) }
        drawRoom(time)
        drawFires(time)
        drawLashes(time)
        drawMandalas(time)
        drawWards(time)
        drawSeal(time)
        drawFury(time)
        drawRings()
        drawScene(time)
        drawFx(time)
        drawShots(time)
        drawArcs()
        drawFly(time)
        if (wx) sc.restore()
        art.vignette(sc)
        if (flashT > 0f) { p.color = (((flashT / 0.25f) * 0x80).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF; sc.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        if (hurtFlashT > 0f) {
            // a red edge round the screen when you're hurt
            p.color = (((hurtFlashT / 0.3f) * 0xA0).toInt().coerceIn(0, 255) shl 24) or 0xE02020
            sc.drawRect(0f, 0f, W.toFloat(), 3f, p); sc.drawRect(0f, H - 3f, W.toFloat(), H.toFloat(), p); sc.drawRect(0f, 3f, 3f, H - 3f, p); sc.drawRect(W - 3f, 3f, W.toFloat(), H - 3f, p)
        }
        if (furyT > 0f) { p.color = ((0x30 + ((time * 6).toInt() % 2) * 0x20) shl 24) or 0xF0A030; sc.drawRect(0f, 0f, W.toFloat(), 2f, p); sc.drawRect(0f, H - 2f, W.toFloat(), H.toFloat(), p); sc.drawRect(0f, 0f, 2f, H.toFloat(), p); sc.drawRect(W - 2f, 0f, W.toFloat(), H.toFloat(), p) }
        drawHud(time)
        drawRune(time)
        drawRuneBook(time)
        drawWash(time)
        drawOverlays(time)
        drawTalk(time)
    }

    /** The bag: weapon and charm worn, everything found; A equips (or eats a herb), B closes. */
    private fun drawBag(time: Float) {
        val l = 14f; val t = 12f; val r = W - 14f; val b = H - 12f
        val rows = bagRows()
        box(l - 1, t - 1, r + 1, b + 1, INK); box(l, t, r, b, WHITE); box(l + 2, t + 2, r - 2, b - 2, INK); box(l + 3, t + 3, r - 3, b - 3, WHITE)
        sc.drawText("BAG ${bag.owned.size}/${bag.CAPACITY}", l + 8, t + 13, font)
        font.color = RUNE; sc.drawText("L: SAVE", r - 62, b - 4 - 24, font); font.color = INK
        font.color = DARK
        sc.drawText("HALL ${bag.wornCount(Loot.FIELD)}/${bag.SLOTS}  DUEL ${bag.wornCount(Loot.DUEL)}/${bag.SLOTS}", l + 98, t + 13, font)
        font.color = INK
        box(l + 6, t + 17, r - 6, t + 18, MID)
        if (rows.isEmpty() && pool.isEmpty()) sc.drawText("NOTHING YET. LOOT IS RARE!", l + 10, t + 34, font)
        // nine rows at a time, scrolling with the cursor: your party first, then your things
        val view = 9; val np = pool.size + 1; val total = rows.size + np
        val first = (bagCursor - view + 1).coerceAtLeast(0).coerceAtMost((total - view).coerceAtLeast(0))
        for (i in first until minOf(total, first + view)) {
            val y = t + 31 + (i - first) * 11f
            if (i == bagCursor && (time * 3).toInt() % 2 == 0) sc.drawText(">", l + 6, y, font)
            if (i == 0) {
                box(l + 15, y - 8, l + 24, y + 1, 0xFFE07830.toInt()); font.color = WHITE; sc.drawText("R", l + 16, y, font)
                font.color = 0xFFE07830.toInt(); sc.drawText("THE RADIO", l + 28, y, font); font.color = MID; sc.drawText("A: OPEN", r - 54, y, font); font.color = INK
                continue
            }
            if (i < np) {
                val on = i - 1 in partyOn
                box(l + 15, y - 8, l + 24, y + 1, 0xFF22A08A.toInt()); font.color = WHITE; sc.drawText("P", l + 16, y, font)
                font.color = 0xFF22A08A.toInt(); sc.drawText(kindName(pool[i - 1]), l + 28, y, font)
                font.color = if (on) 0xFF48A048.toInt() else MID; sc.drawText(if (on) "ALONG" else "RESTS", r - 48, y, font); font.color = INK
                continue
            }
            val it = rows[i - np]
            val tag = when (it.mode) { Loot.FIELD -> "H"; Loot.DUEL -> "D"; else -> " " }
            if (!it.consumable) { box(l + 15, y - 8, l + 24, y + 1, if (it.mode == Loot.FIELD) 0xFF48A048.toInt() else RUNE); font.color = WHITE; sc.drawText(tag, l + 16, y, font); font.color = INK }
            font.color = if (it.consumable || it.rarity == Rarity.COMMON) INK else rarityCol(it.rarity, time)
            sc.drawText(when (it) { Loot.HERB -> "HERB x${bag.herbs} (EAT)"; Loot.BLOOM -> "SPIRIT BLOOM x${bag.blooms}"; else -> it.label }, l + 28, y, font)
            font.color = INK
            if (bag.wearing(it)) { font.color = 0xFF48A048.toInt(); sc.drawText("WORN", r - 42, y, font); font.color = INK }
        }
        // what the selected one does
        box(l + 6, b - 24, r - 6, b - 23, MID)
        if (bagCursor == 0) { font.color = DARK; sc.drawText("LISTEN WHILE YOU PLAY", l + 8, b - 4, font); font.color = INK }
        else if (bagCursor <= pool.size) {
            font.color = 0xFF22A08A.toInt(); sc.drawText("PARTY ${partyOn.size}/$extraSlots  POWER ${(powerK * 100).toInt()}%", l + 8, b - 14, font)
            font.color = DARK; sc.drawText("EACH ONE ALONG: -10% YOUR POWER", l + 8, b - 4, font); font.color = INK
        }
        rows.getOrNull(bagCursor - pool.size - 1)?.let {
            if (!it.consumable) { font.color = rarityCol(it.rarity, time); sc.drawText(it.rarity.label + if (it.mode == Loot.FIELD) " - HALL" else " - DUEL", l + 8, b - 14, font)
                font.color = if (dropAsk === it) 0xFFE04848.toInt() else MID; sc.drawText(if (dropAsk === it) "R AGAIN: DROP!" else "R: DROP", r - 90, b - 14, font) }
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
                c.textCentered(if (runePromptT > 0f && !(castRune >= 0 && bossHere)) "DRAW A RUNE" else "DRAW THE RUNE", xyRect.centerX(), xyRect.top + Px.dp(12f), label)
            } else {
                var fx = (flyTX - camX - 4f) / (W - 8f); var fy = (flyTY - camY - 4f) / (H - 8f)
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
        flyTX = camX + 4f + fx * (W - 8f)
        flyTY = camY + 4f + fy * (H - 8f)
    }
    private var aDown = false; private var bDown = false

    private fun near(k: RectF, x: Float, y: Float) = abs(x - k.centerX()) < k.width() * 0.75f && abs(y - k.centerY()) < k.height() * 0.75f

    private fun steer(x: Float, y: Float) {
        val dx = x - pad.centerX(); val dy = y - pad.centerY()
        val oldY = dirY
        if (abs(dx) < pad.width() * 0.08f && abs(dy) < pad.height() * 0.08f) { dirX = 0; dirY = 0; return }
        if (abs(dx) > abs(dy)) { dirX = if (dx > 0) 1 else -1; dirY = 0 } else { dirY = if (dy > 0) 1 else -1; dirX = 0 }
        // in the duel and the bag, up / down move the cursor
        if ((battle != null || bagOpen || choosing) && dirY != 0 && dirY != oldY) menuStep(dirY)
    }

    private fun pressA() {
        if (choosing) { chooseNow(); return }
        battle?.let { it.pressA(); return }
        if (bagOpen) { useBag(); return }
        if (talking) {
            if (shown < talkLines[line].length) shown = talkLines[line].length.toFloat()
            else if (line < talkLines.size - 1) { line++; shown = 0f } else endTalk()
        } else if (phase == Phase.LOST) { if (inWild) startWild(depth, fresh = false) else restart(chapter) }
        else if (phase == Phase.WON) {
            if (nearSpirit()) speakToSpirit()
            else if (zone == 1 && !chestOpen && nearChest()) {
                chestOpen = true; hp = maxHp; bursts += Burst(360f, 58f)
                val a = if (Loot.STARHEART in bag.owned) rollLoot(0f) else Loot.STARHEART
                val b = if (Loot.THUNDER_CROWN in bag.owned) rollLoot(0f) else Loot.THUNDER_CROWN
                takeLoot(a); takeLoot(b)
                talking = true; talkLines = CHEST_LINES + "BENEATH THE GOLD, TWO CHARMS: ${a.label} AND ${b.label}!"; line = 0; shown = 0f
            } else if (chestOpen && nearChest()) { if (chapter == 1) beginChapter2() else { forestOpen = true; startWild(1) } }
            else actOrSprint()
        } else if (phase == Phase.FIGHT) { val n0 = if (inWild) nearNpc() else null; if (n0 != null) npcAct(n0) else actOrSprint() }
    }

    // ---- A out and about: use what's in reach, or sprint
    private val actKinds = setOf(FOUNTAIN, TOMB, TABLE, STATUE, BRAZIER, SHRUB, STUMP, LOG, MUSHROOM, EXIT)
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
    private fun actLabel(kind: Int) = when (kind) { EXIT -> if (stoneKeys > 0) "UNLOCK" else "THE STONE DOOR"; MUSHROOM -> "PICK"; FOUNTAIN -> "DRINK"; TOMB -> "SEARCH"; TABLE -> "READ"; STATUE -> "LOOK"; BRAZIER -> "WARM UP"; else -> "RUMMAGE" }
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
            EXIT -> useExit()
            MUSHROOM -> if (key in searched) say("ONLY STALKS LEFT.") else {
                searched += key
                val r0 = rnd.nextFloat()
                if (r0 < 0.4f) { hp = minOf(maxHp, hp + 12); bursts += Burst(hx + 8, hy + 6); say("A GOOD MUSHROOM. +12 HP") }
                else if (r0 < 0.65f) { val c0 = 2 + rnd.nextInt(4); coins += c0; say("COINS UNDER THE CAPS! +$c0") }
                else { poisonT = 3f; poisonTick = 1.5f; say("A BAD ONE! YOU FEEL ILL") }
            }
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
                    r < 0.04f -> { drops += Drop(pp.foot.centerX(), pp.foot.bottom + 5, roomId, rollLoot(0.6f)); say("SOMETHING GLINTS INSIDE!") }
                    r < 0.45f -> { spawnMinion(pp.foot.centerX() to pp.foot.bottom + 8); say("SOMETHING CLIMBS OUT!") }
                    else -> say("ONLY DUST.")
                }
            }
            STATUE -> if (key in searched) say("AN OLD BUST. IT STARES.") else {
                searched += key
                if (rnd.nextFloat() < 0.03f) { drops += Drop(pp.foot.centerX(), pp.foot.bottom + 5, roomId, rollLoot(0.7f)); say("A LOOSE STONE! SOMETHING FALLS OUT.") }
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
        invulnT = maxOf(invulnT, sprintT + 0.15f)
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
    /** Thrown away for good (asked twice). */
    private var dropAsk: Loot? = null
    private fun bagDrop() {
        val l = bagRows().getOrNull(bagCursor - pool.size - 1) ?: return
        if (l.consumable) return
        if (dropAsk !== l) { dropAsk = l; sfx.play(HallSfx.S.PICKUP, 0.3f); return }
        bag.drop(l); dropAsk = null; say("DROPPED ${l.label}", 1.4f); sfx.play(HallSfx.S.SHATTER, 0.5f); saveSoon = true
        bagCursor = bagCursor.coerceAtMost((bagRows().size + pool.size).coerceAtLeast(0))
    }
    /** Set by the app: opens the radio. */
    var onRadio: (() -> Unit)? = null
    private fun useBag() {
        dropAsk = null
        if (bagCursor == 0) { bagOpen = false; onRadio?.invoke(); return }
        if (bagCursor <= pool.size) { toggleParty(bagCursor - 1); return }
        val l = bagRows().getOrNull(bagCursor - pool.size - 1) ?: return
        if (l == Loot.HERB) { if (hp < maxHp) { val hh = if (bag.wearing(Loot.HERB_POUCH)) 50 else 35; bag.herbs--; hp = minOf(maxHp, hp + hh); msg = "+$hh HP"; msgT = 1f; sfx.play(HallSfx.S.ORB) } }
        else if (l == Loot.BLOOM) reviveFriend()
        else if (!bag.toggle(l)) { msg = "TWO ALREADY WORN: TAKE ONE OFF FIRST"; msgT = 1.4f }
        else if (l == Loot.RUNECASTER && bag.wearing(l)) say(RUNE_HINT, 6f)
        bagCursor = bagCursor.coerceAtMost((bagRows().size + pool.size).coerceAtLeast(0))
    }
    private fun menuStep(d: Int) {
        if (choosing) { chooseCur = Math.floorMod(chooseCur + d, encounters().size); sfx.play(HallSfx.S.PICKUP, 0.3f); return }
        battle?.let { if (d < 0) it.up() else it.down(); return }
        if (bagOpen) { val n = bagRows().size + pool.size + 1; bagCursor = Math.floorMod(bagCursor + d, n); dropAsk = null }
    }
    /** When he's done shouting, the fight starts. */
    private fun endTalk() {
        talking = false
        if (phase == Phase.WON && !bossGone && perishT == 0f && (talkLines === DEFEAT || talkLines === DEFEAT2)) { perishT = 0.001f; shakeT = 0.4f; sfx.play(HallSfx.S.PERISH); sfx.rumbleFade() }
        if (phase == Phase.INTRO && (talkLines === RANT || talkLines === RANT2)) { phase = Phase.FIGHT; msg = "FIGHT!"; msgT = 1.4f; spellT = 6f; bossShotT = 2f; summonT = if (chapter == 2) 6f else 1f; if (friend == null) spawnFriend() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); sfx.prepare() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); saveGame(); sfx.release() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i); val y = e.getY(i)
                when {
                    exitRect.contains(x, y) -> { exitDown = true; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    bagRect.contains(x, y) -> { if (bagOpen) bagOpen = false else openBag(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    selRect.contains(x, y) && bagOpen -> { saveGame(); bagOpen = false; say("GAME SAVED", 1.6f); sfx.play(HallSfx.S.COUNTER); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    selRect.contains(x, y) -> { selDown = true; selPointer = e.getPointerId(i); drawing.clear(); drawLen = 0f; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    startRect.contains(x, y) -> { startDown = true; if (bagOpen) bagDrop() else zap(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
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


private val MON_PAL = mapOf('K' to 0xFF1C1428.toInt(), 'W' to 0xFFFFFFFF.toInt(), 'r' to 0xFFB0222E.toInt(), 'M' to 0xFF3C0A18.toInt(), 'X' to 0xFFFF4646.toInt(), 'Y' to 0xFFFFEC6E.toInt(), 'v' to 0xFF0E0C12.toInt(), 'p' to 0xFF1E1A26.toInt(), 'q' to 0xFF342E40.toInt(), 'P' to 0xFF5C5470.toInt(), 'm' to 0xFF141018.toInt(), 'n' to 0xFF26202E.toInt(), 'o' to 0xFF3C3448.toInt(), 'O' to 0xFF605670.toInt(), 'b' to 0xFF967850.toInt(), 'c' to 0xFFD2B888.toInt(), 'd' to 0xFFF0E2BE.toInt(), 'D' to 0xFFFFFAE8.toInt(), 'f' to 0xFF8C3C46.toInt(), 'g' to 0xFFC46468.toInt(), 'h' to 0xFFEC968C.toInt(), 'G' to 0xFFFFCEBE.toInt(), 'a' to 0xFF1E1E40.toInt(), 'j' to 0xFF2A2C5C.toInt(), 'e' to 0xFF383A6E.toInt(), 'E' to 0xFF6E76BE.toInt(), 's' to 0xFF143C50.toInt(), 't' to 0xFF1E6E82.toInt(), 'u' to 0xFF3CAAB4.toInt(), 'U' to 0xFFA0F0F0.toInt(), 'R' to 0xFF5A0C1C.toInt(), 'H' to 0xFFF07860.toInt(), 'A' to 0xFF283418.toInt(), 'k' to 0xFF282430.toInt(), 'l' to 0xFF3E384A.toInt(), 'T' to 0xFF585066.toInt(), 'V' to 0xFF7C728C.toInt(), 'B' to 0xFF465622.toInt(), 'C' to 0xFF6C8030.toInt(), 'F' to 0xFF9EB050.toInt(), 'L' to 0xFF968E60.toInt(), 'N' to 0xFFC4BA80.toInt(), 'Q' to 0xFFE2DAA6.toInt(), 'S' to 0xFFF6F0CE.toInt(), 'w' to 0xFF968496.toInt(), 'x' to 0xFFCEC0CC.toInt(), 'y' to 0xFFEEE6EC.toInt(), 'z' to 0xFFFFFCFA.toInt(), 'i' to 0xFFC82428.toInt(), 'I' to 0xFFFF8C46.toInt())
/** Mercenaries: leathery olive-brown, so they don't look like foes or thralls. */
private val MON_MERC = mapOf('v' to 0xFF3A3020.toInt(), 'a' to 0xFF3A3020.toInt(), 'm' to 0xFF3A3020.toInt(), 's' to 0xFF3A3020.toInt(), 'R' to 0xFF3A3020.toInt(), 'w' to 0xFF3A3020.toInt(), 'p' to 0xFF645434.toInt(), 'j' to 0xFF645434.toInt(), 'n' to 0xFF645434.toInt(), 't' to 0xFF645434.toInt(), 'r' to 0xFF645434.toInt(), 'x' to 0xFF645434.toInt(), 'q' to 0xFF8E7C4C.toInt(), 'e' to 0xFF8E7C4C.toInt(), 'o' to 0xFF8E7C4C.toInt(), 'u' to 0xFF8E7C4C.toInt(), 'h' to 0xFF8E7C4C.toInt(), 'y' to 0xFF8E7C4C.toInt(), 'P' to 0xFFC4B07A.toInt(), 'E' to 0xFFC4B07A.toInt(), 'O' to 0xFFC4B07A.toInt(), 'U' to 0xFFC4B07A.toInt(), 'H' to 0xFFC4B07A.toInt(), 'z' to 0xFFC4B07A.toInt())
private val MON_ICE = mapOf('v' to 0xFF4A7AB0.toInt(), 'a' to 0xFF4A7AB0.toInt(), 'm' to 0xFF4A7AB0.toInt(), 's' to 0xFF4A7AB0.toInt(), 'R' to 0xFF4A7AB0.toInt(), 'w' to 0xFF4A7AB0.toInt(), 'p' to 0xFF78A8D8.toInt(), 'j' to 0xFF78A8D8.toInt(), 'n' to 0xFF78A8D8.toInt(), 't' to 0xFF78A8D8.toInt(), 'r' to 0xFF78A8D8.toInt(), 'x' to 0xFF78A8D8.toInt(), 'q' to 0xFFA8D0F0.toInt(), 'e' to 0xFFA8D0F0.toInt(), 'o' to 0xFFA8D0F0.toInt(), 'u' to 0xFFA8D0F0.toInt(), 'h' to 0xFFA8D0F0.toInt(), 'y' to 0xFFA8D0F0.toInt(), 'P' to 0xFFE0F4FF.toInt(), 'E' to 0xFFE0F4FF.toInt(), 'O' to 0xFFE0F4FF.toInt(), 'U' to 0xFFE0F4FF.toInt(), 'H' to 0xFFE0F4FF.toInt(), 'z' to 0xFFE0F4FF.toInt())
private val MON_BURN = mapOf('v' to 0xFF8A2A10.toInt(), 'a' to 0xFF8A2A10.toInt(), 'm' to 0xFF8A2A10.toInt(), 's' to 0xFF8A2A10.toInt(), 'R' to 0xFF8A2A10.toInt(), 'w' to 0xFF8A2A10.toInt(), 'p' to 0xFFD05020.toInt(), 'j' to 0xFFD05020.toInt(), 'n' to 0xFFD05020.toInt(), 't' to 0xFFD05020.toInt(), 'r' to 0xFFD05020.toInt(), 'x' to 0xFFD05020.toInt(), 'q' to 0xFFF89040.toInt(), 'e' to 0xFFF89040.toInt(), 'o' to 0xFFF89040.toInt(), 'u' to 0xFFF89040.toInt(), 'h' to 0xFFF89040.toInt(), 'y' to 0xFFF89040.toInt(), 'P' to 0xFFFFE080.toInt(), 'E' to 0xFFFFE080.toInt(), 'O' to 0xFFFFE080.toInt(), 'U' to 0xFFFFE080.toInt(), 'H' to 0xFFFFE080.toInt(), 'z' to 0xFFFFE080.toInt())
private val MON_ALLY = mapOf('v' to 0xFF14605A.toInt(), 'a' to 0xFF14605A.toInt(), 'm' to 0xFF14605A.toInt(), 's' to 0xFF14605A.toInt(), 'R' to 0xFF14605A.toInt(), 'w' to 0xFF14605A.toInt(), 'p' to 0xFF22A08A.toInt(), 'j' to 0xFF22A08A.toInt(), 'n' to 0xFF22A08A.toInt(), 't' to 0xFF22A08A.toInt(), 'r' to 0xFF22A08A.toInt(), 'x' to 0xFF22A08A.toInt(), 'q' to 0xFF5CD8B0.toInt(), 'e' to 0xFF5CD8B0.toInt(), 'o' to 0xFF5CD8B0.toInt(), 'u' to 0xFF5CD8B0.toInt(), 'h' to 0xFF5CD8B0.toInt(), 'y' to 0xFF5CD8B0.toInt(), 'P' to 0xFFC8FFE0.toInt(), 'E' to 0xFFC8FFE0.toInt(), 'O' to 0xFFC8FFE0.toInt(), 'U' to 0xFFC8FFE0.toInt(), 'H' to 0xFFC8FFE0.toInt(), 'z' to 0xFFC8FFE0.toInt(), 'b' to 0xFFB08820.toInt(), 'c' to 0xFFE8C040.toInt(), 'd' to 0xFFF8E070.toInt(), 'D' to 0xFFFFF8C0.toInt(), 'f' to 0xFF6AB8A0.toInt(), 'g' to 0xFFA0E8C8.toInt(), 'G' to 0xFFE0FFF0.toInt(), 'i' to 0xFFE8C040.toInt(), 'I' to 0xFFFFF8C0.toInt())
private val MON_RAGE = mapOf('R' to 0xFF9A1010.toInt(), 'r' to 0xFFE02A20.toInt(), 'h' to 0xFFFF7050.toInt(), 'H' to 0xFFFFC080.toInt(), 'Y' to 0xFFFFFFFF.toInt())

private val IMP_ROWS = listOf(
    listOf("............K...", "...K.......KdK..", "..KdK.....KddK..", "...KdKKKKKddK..K", "K..KdvPPqqvdK.Ko", "oKKPKPPPqqqKpKoo", "ooKPPKKqqKKppmoK", "KmPPPYYKKYYppvmK", "KmqPqYXqqXYpvvmK", ".KqqqqqqppppvvK.", ".KqqqWMWMMWvvvK.", ".KpppvMMWMvvvvvK", "..KpvVVTTllvvKqK", "...KvTTllkkvK.K.", "..KqvvvkkvvvvK..", "..KDpvvKKvvvDK.."),
    listOf("..KdK.....KddK..", "...KdKKKKKddK..K", "K..KdKPPqqKdK.Ko", "oKKPKPPPqqqKpKoo", "omPPPKKPqKKpppmK", "KmPPPYYKKYYppvmK", "KqPPqYXqqXYppvvK", "KqqqqqqqppppvvvK", "KqqqqWMWMMWvvvvK", ".KpppvMMWMvvvvvK", "..KpvVVTTllvvKqK", "...KvTTllkkvK.K.", "....KKKkkKKK....", "...KPPqKKPPqK...", "..KqqqpvqqqpvK..", "..KDpvvKKpvvDK.."),
)

private val STALK_ROWS = listOf(
    listOf("KK............K....K..", "UuK.....K....KuK..KuK.", "utK....KuKK.KsuuKKKuK.", "tsK...KuuKuKusuuaauuK.", "KeeK.KKuuuuusEaaEeaajK", ".KeeKEEEEEeeaEEKKeKKjK", "..KaEEEEEeeaeEeeYWjYWa", "..KEEEEeeeeaeeeeXYjXaa", "..KeeeeeeejjaeejjjaEej", "..KjeeeaajjjajjjjaWMWM", "...KjjaEeaaaaaaaaaaWKW", "..KEaaaEeaaaaaaaKKKK.K", "..KeeaeejaKKaeejaK....", "..KjjaKjaK.KjajaK.....", "...KaKKaaK..KaaaK....."),
    listOf("KK............K....K..", "UuK.....K....KuK..KuK.", "utK....KuKK.KsuuKKKuK.", "tsK...KuuKuKusuuaauuK.", "KeeK.KKuuuuusEaaEeaajK", ".KeeKEEEEEeeaEEKKeKKjK", "..KaEEEEEeeaeEeeYWjYWa", "..KEEEEeeeeaeeeeXYjXaa", "..KeeeeeeejjaeejjjaEej", "..KjeeaajjjjajjjjaWMWM", "...KjaEeajaaaaaaaaaWKW", "...KaaEeaaaaaaKaaKKK.K", "...KaeejaKKaaaeejaK...", "...KjajaK.KjjaKjaK....", "....KaaaK..KaKKaaK...."),
    listOf("......................", "KK............K....K..", "UuK.....K....KuK..KuK.", "utK....KuKK.KsuuKKKuK.", "tsK...KuuKuKusuuaauuK.", "KeeK.KKuuuuusEaaEeaajK", ".KeeKEEEEEeeaEEKKeKKjK", "..KaEEEEEeeaeEeeXXjXXa", "..KEEEEeeeeaeeeeejjaaa", "..KeeeeaaejjaeejjjaEej", "..KjeeaEeajjajjjjaWMWM", "..KajjaEeaaaaaaaaaaWKW", "..KeaaeejaaaaaaaaaKK.K", "..KjjaKjaKKaaaKjaK....", "...KaKKaaK.KaKKaaK...."),
)

private val BEH_ROWS = listOf(
    listOf("..KDK......................K....", "...KdK....................KdK...", "...KddK.................KKddK...", "...KdddKKK...........KKKdddK....", "....KdddddKKKKKKKKKKKddddddK....", ".....KddddddRHHHhhhRddddddK.....", "......KKdddRHHHHhhhRddddKK......", "........KKKKHHHHhhhhRKKK........", "........KHRHKKHhhhhKKRRK........", "........KHHHHhhhhhrrrrRK........", ".......KhhhYYWhhhhWYYRRRK.......", "...KKKKRhhhYKYhhrrYKYRRRRK......", "..KHhhRHRhhhDhhrrrrRDRRRrRK.....", ".KHHHhrRRrrrDrrrrrRRDRRRrRKKK...", ".KHHhhrRHRrMMWWWMWWWMMRrrRHhhK..", "KHHHhhrRRHRrMMMMMMMMMRrrRHHHhrK.", "KHHhhhrRRHhRRMWMWMWMRrrrRHHhhrKK", "KhhhhrrRRhhRffffffffRrrRHHHhhrRR", "KhhhhrrRRhRGGGGGhhhggRrRHHhhhrRR", "KhhhrrRRRRGGGGGhhhhgggRRhhhhrrRR", "KrRRRRRRRGGGGGhhhhgggffRhhhhrrRR", ".KHHhhRRRhhhhhhhhggggffRhhhrrRRK", "KHHHhhrRRhhhhhhhggggfffRrrRRRRRK", "hHhhhrrRRhhhhhgggggffffRRRHHhhRK", "hhhhrrRRRRgggggggfffffRRRHHHhhrK", "KrrrRRRRRRRggggffffffRRRhHhhhrrR", ".KRRRRRhRRRRffffffffRRRRhhhhrrRR", ".KDKDKDrrrRRRRRRRRRRRRrrRrrrRRRK", "..K.K.KRRRRRRKKKKKKRRRRRRKRRRRK.", ".....KDDDddccK....KDDDddccDKDKDK", ".....KdddccbbK....KdddccbbK.K.K.", "......KbbbbbK......KbbbbbK......"),
    listOf("..KDK......................K....", "...KdK....................KdK...", "...KddK.................KKddK...", "...KdddKKK...........KKKdddK....", "....KdddddKKKKKKKKKKKddddddK....", ".....KddddddRHHHhhhRddddddK.....", "......KKdddRHHHHhhhRddddKK......", "........KKKKHHHHhhhhRKKK........", "........KHRHKKHhhhhKKRRK........", "........KHHHHhhhhhrrrrRK........", ".......KhhhYYWhhhhWYYRRRK.......", "......KRhhhYKYhhrrYKYRRRRKKKK...", ".....KHHRhhhDhhrrrrRDRRRrRHhhK..", "...KKKHHRrrrDrrrrrRRDRRRRHHHhrK.", "..KHhhRHHRrMMWWWMWWWMMRrRHHhhrK.", ".KHHHhrRHHRrMMMMMMMMMRrRHHHhhrRK", ".KHHhhrRHHhRRMWMWMWMRrrRHHhhhrRK", "KHHHhhrRRhhRffffffffRrrRhhhhrrRR", "KHHhhhrRRhRGGGGGhhhggRrRhhhhrrRR", "KhhhhrrRRRGGGGGhhhhgggRRhhhrrRRR", "KhhhhrrRRGGGGGhhhhgggffRrrRRRRRR", "KhhhrrRRRhhhhhhhhggggffRRRHHhhKK", "KrRRRRRRRhhhhhhhggggfffRRHHHhhrK", ".KHHhhRRRhhhhhgggggffffRhHhhhrrR", "KHHHhhrRrRgggggggfffffRRhhhhrrRR", "hHhhhrrRRRRggggffffffRRRRrrrRRRK", "hhhhrrRRRRRRffffffffRRRRRRRRRRK.", "KrrrRRRRhrRRRRRRRRRRRRrRRKDKDKDK", ".KRRRRKRRRRRRKKKKKKRRRRRRKK.K.K.", ".KDKDKDDDddccK....KDDDddccK.....", "..K.KKdddccbbK....KdddccbbK.....", "......KbbbbbK......KbbbbbK......"),
    listOf(".KDDDKDK.................KDRDKDK", "KHHRdRrK................KHRdRhrK", "KHHRddRK................KRddRrrK", "hhhRdddRKK...........KKKdddRrrRR", "KhhrRdddddKKKKKKKKKKKddddddRrRRK", "KrrrRRddddddRHHHhhhRddddddRrRRRK", ".KRRRRRRdddRHHHHhhhRddddRRRRRRK.", ".KHRRhrrKKKKHHHHhhhhRKKKHHhRRrK.", ".KhhhhrRKHRHKKHhhhhKKRRKhhhhrRK.", ".KhhhrrRKHHHHhhhhhrrrrRKhhhrrRK.", ".KhhrrRRhhhYYWhhhhWYYRRRRhrrRRK.", ".KrrrrRRhhhYKYhhrrYKYRRRRrrrRRK.", ".KrrrRRRRhhhDhhrrrrRDRRRrrrRRRK.", "..KRRRRRRrrrDrrrrrRRDRRRRRRRRK..", "...KRRRHHRrMMWWWMWWWMMRrrRRRK...", "....KRHHHHRrMMMMMMMMMRrrrRRK....", "....KhHHHHhRRMWMWMWMRrrrRRRK...K", "....KhhhhhhRffffffffRrrrRRRK..Kh", "....KhhhhhRGGGGGhhhggRrRRRRK.Khh", "....KhhhhRGGGGGhhhhgggRRRRRKKKhh", "....KhhhRGGGGGhhhhgggffRRRRRhhKh", "....KrhhRhhhhhhhhggggffRRRRRhhKK", ".....KrrRhhhhhhhggggfffRRRRhhhhK", ".....KrrRhhhhhgggggffffRRRKKKhhK", "......KrrRgggggggfffffRRRK...KK.", ".....KHRRRRggggffffffRRRRRK.....", ".....KhhRRRRffffffffRRRRRRK.....", ".....KhhhrRRRRRRRRRRRRrrRRK.....", "......KRRRRRRKKKKKKRRRRRRK......", ".....KDDDddccK....KDDDddccK.....", ".....KdddccbbK....KdddccbbK.....", "......KbbbbbK......KbbbbbK......"),
    listOf("................................", "................................", "................................", "................................", "...K............................", "..KDK......................K....", "...KdK....................KdK...", "...KddK.................KKddK...", "...KdddKKK...........KKKdddK....", "....KdddddKKKKKKKKKKKddddddK....", ".....KddddddRHHHhhhRddddddK.....", "......KKdddRHHHHhhhRddddKK......", "........KKdRHHHHhhhhRdKK........", ".......KRHRHHHHhhhhrrRRRK.......", "......KHRHHHHhhhhhrrrrRRrK......", ".....KHRhhhKhKhhhhrKrKRRRRK.....", ".....KHRhhhhKhhhrrrrKRRRRRK.....", "....KHHHRhhhDhhrrrrRDRRRrRRK....", "....KHHHRrrrDrrrrrRRDRRRrRRK....", "....KhHHHRrMMWWWMWWWMMRrRRRK...K", "....KhhhhhRrMMMMMMMMMRrrRRRK..Kh", "....KRhhhhRfRMWMWMWMfRrRRRRK.Khh", "...KHhRhhRGGffffffffggRRRRHhKKhh", "..KHHhhRRGGGGGhhhhgggffRRHHhhRKh", ".KHHHhhrRhhhhhhhhggggffRHHHhhrKK", ".KHHhhrrRhhhhhhhggggfffRHHhhrrRK", ".KRRRRRRRhhhhhgggggffffRhRRRRRRK", ".KHHhhrRRRgggggggfffffRRRHHhhrK.", "KhHhhrrRRRRggggffffffRRRhHhhrrRK", "KhhhrrRRbbRRffffffffRRbbhhhrrRRK", ".KrrRRRbdcbbRRRRRRRRbbccbrrRRRK.", "..KKRRKbbbbbRKKKKKKRbbbbbKRRKK.."),
)

private val DRAKE_ROWS = listOf(
    listOf("........................................", "............K...........................", "...........KdK............K.............", "....K.......KdK..........KdK.......K....", "...KrK......KddKKKKKKKKKKddK......Krr...", "...KrrK.....KdddaEEEeeeaddK......Krr....", "....KRrK.....KdddaEEeeadddK.....KRrK....", "....KRRrK....KaKKEEeeejaKKK...KKRrRK....", "....KRRRrKr..KeEeKeeejjKaaK..KrRrRK.....", ".....KRRRRRr.KeeYYeejjYYaaK.KrRRRRK.....", "....KRRRRRRRrKjeXYjjjjXYaaKKrRRRRRRK....", "...KRRRRRRRRRrKjjEEEeejaaKKrRRRRRRRRK...", "..KRRRRRRRRRRRrKEEeeejjaKKrRRRRRRRRRRK..", "..rrRrrrrRrrrRrreDejjjaDKrrRrrrRrrrrRrr.", "...KRRRRRRRRRMEEajKjaaKaejMRRRRRRRRRK...", "....KKRRRRRRMEEaeaaDaDaeajjMRRRRRRKK....", "......KRRRRMEEEaeDeeeeeDajjjMRRRRK......", ".......KKRMEEEEaeeeeeeeeajjjaMKKK.......", ".........KKEEEEEaaaaaaaajjjjaK..........", ".........KeEEEEEaDaaaaaDjjjaaaK........K", ".........KeeeeeaDDDDdddcajjaaaK.......Kd", ".........KeeeeaDDDDDdddccaaaaaK......Kdd", ".........KeeeaDDDDDdddcccbaaaaK.....Kddd", "..........KeeadDdddddcccbbaaaK.......KdK", ".........KajjaddbdbdbcbbbbaaaK.......KdK", ".........KEajacdddccccbbbbaaajK.....KeK.", ".........KEEajacccccbbbbbaaaejKK...KeeK.", "........KeeeeaaabbbbbbbbaaaejjaeKKKeeeK.", ".........KeejjaaKaaaaaaaaaejjaaajjjjjK..", ".........KjjjjaaKKKKKKKKeejjjaaKKKKKK...", ".........KajaaaaK......KjjjjaaK.........", "..........KKaaKKK......KajaaaaK.........", ".........KDKKDKKDK.....KDKaDKKDK........", "..........K..K..K.......K.KK..K........."),
    listOf("........................................", "............K...........................", "...........KdK............K.............", "....K.......KdK..........KdK.......K....", "...KrK......KddKKKKKKKKKKddK......Krr...", "...KrrK.....KdddaEEEeeeaddK......Krr....", "....KRrK.....KdddaEEeeadddK.....KRrK....", "....KRRrK....KaKKEEeeejaKKK...KKRrRK....", "....KRRRrKr..KeEeKeeejjKaaK..KrRrRK.....", ".....KRRRRRr.KeeYYeejjYYaaK.KrRRRRK.....", "....KRRRRRRRrKjeXYjjjjXYaaKKrRRRRRRK....", "...KRRRRRRRRRrKjjEEEeejaaKKrRRRRRRRRK...", "..KRRRRRRRRRRRrKEEeeejjaKKrRRRRRRRRRRK..", "..rrRrrrrRrrrRrreDejjjaDKrrRrrrRrrrrRrr.", "...KRRRRRRRRRMEEajKjaaKaejMRRRRRRRRRK...", "....KKRRRRRRMEEaeaaDaDaeajjMRRRRRRKK....", "......KRRRRMEEEaeDeeIeeDajjjMRRRRK......", ".......KKRMEEEEaeeeYeeeeajjjaMKKK.......", ".........KKEEEEEaaaaaaaajjjjaK..........", ".........KeEEEEEaDaaaaaDjjjaaaK........K", ".........KeeeeeaDDDDdddcajjaaaK.......Kd", ".........KeeeeaDDDDDdddccaaaaaK......Kdd", ".........KeeeaDDDDDdddcccbaaaaK.....Kddd", "..........KeeadDdddddcccbbaaaK.......KdK", "..........KjjaddbdbdbcbbbbaaaaK......KdK", ".........KEajacdddccccbbbbaaajK.....KeK.", ".........KEEajacccccbbbbbaaajjKK...KeeK.", ".........KEEeaaabbbbbbbbaaajjaaaKKKeeeK.", "........KeeeejaaaaaaaaaKaajjjaajjjjjjK..", ".........KeejjjaKKKKKKKKjjjjaaaKKKKKK...", ".........KjjjjaaK......KajaaaaK.........", ".........KajaaaaK.......KKaaKKK.........", ".........KDKaDKKDK.....KDKKDKKDK........", "..........K.KK..K.......K..K..K........."),
    listOf(".KrK........KdK..........KdK........Krr.", "..Krr.......KddKKKKKKKKKKddK.......KrK..", "..KrrK......KdddaEEEeeeaddK.......KrrK..", "..KrrrK......KdddaEEeeadddK......KrrK...", "...KrrrK.....KaKKEEeeejaKKK.....KrrrK...", "...KrrrrK....KeEeKeeejjKaaK....KrrrrK...", "....KrrrrK...KeeYYeejjYYaaK...KrrrrK....", "..KKRRRRRRr..KjeXYjjjjXYaaK..KrRRRRRKK..", ".KRRRRRRRRRr..KjjEEEeejaaK..KrRRRRRRRRK.", ".rRRRRRRRRRRK..KEEeeejjaK..KRRRRRRRRRRKr", "..KrrRrRRRRRrK.KeDejjjaDK.KRrRRRRRrRrr..", "...KRRRrRRRRRRr.KjKjaaKK.KrRRRRRRrRRK...", "....KRRRRrRrrRRKKaaDaDaKKRRRrrRrRRRK....", ".....KRRRRRRRRrMrDeeIeeDrMrRRRRRRRK.....", "....KRRRRRRRRMErrreYeeerrrMRRRRRRRRK....", "...KRRRRRRRrMrraeeeeeeeeajrrRrRRRRRRK...", "..KRRRrrRrrMEEEaeDeeeeeDajjjMRrrRrrRRK..", "...rrKKRRRMEEEEaeeeeeeeeajjjaMRRKKKKrr..", ".......KKKKEEEEEaaaaaaaajjjjaKKK........", ".........KeEEEEEaDaaaaaDjjjaaaK........K", ".........KeeeeeaDDDDdddcajjaaaK.......Kd", ".........KeeeeaDDDDDdddccaaaaaK......Kdd", ".........KeeeaDDDDDdddcccbaaaaK.....Kddd", "..........KeeadDdddddcccbbaaaK.......KdK", "..........KjjaddbdbdbcbbbbaaaK.......KdK", ".........KEajacdddccccbbbbaaajK.....KeK.", ".........KEEajacccccbbbbbaaaejKK...KeeK.", ".........KEEeaaabbbbbbbbaaaejjaeKKKeeeK.", "........KeeeejaaaaaaaaaaaaejjaaajjjjjK..", ".........KeejjjaKKKKKKKKeejjjaaKKKKKK...", ".........KjjjjaaK......KjjjjaaK.........", ".........KajaaaaK......KajaaaaK.........", ".........KDKaDKKDK.....KDKaDKKDK........", "..........K.KK..K.......K.KK..K........."),
    listOf("........................................", "........................................", "........................................", "........................................", "........................................", "........................................", "........................................", "........................................", "........................................", "........................................", "........................................", "............K...........................", "...........KdK............K.............", "............KdK..........KdK............", "............KddKKKKKKKKKKddK............", "............KdddaEEEeeeaddK.............", ".............KdddaEEeeadddK.............", ".............KaaaEEeeejaaaK.............", "............KaeEeeeeejjjaaaK............", "...........KEaeeKeKejjKaKaajK...........", "..........KEEajeeKjjjjaKaaajaK..........", "..........KEEEajjEEEeejaaajjaK..........", ".......KKKeEEEEaEEeeejjaajjaaaKKK......K", "....KKKRRMeeeeeaeeejjjaaajjaaaMRRKKK..Kd", "...KRRRRRMeeeeaDbjKjaaKbcaaaaaMRRRRRKKdd", "..KRRRRRRMeeeaDDDbbDbDbccbaaaaMRRRRRMddd", "...KRRRRRRMeeadDdddddcccbbaaaMRRRRRRKKdK", "...KRRRRRRMjjaddbdbdbcbbbbaaaMRRRRRK.KdK", "....KRRRRMEajacdddccccbbbbaaajMRRRRKKeK.", ".....KRRKKEEajacccccbbbbbaaaejKKRRKKeeK.", "......KK.KEEeaaabbbbbbbbaaaejjaeKKKeeeK.", "........KeeeejaaaaaaaaaaaaejjaaajjjjjK..", ".........KDejDjaDKKKKKKKDejDjaDKKKKKK...", ".........KjjjjaaK......KjjjjaaK.........")
)

private val GAZE_ROWS = listOf(
    listOf(".K............K.", "KoK..........KoK", "KoK..KKKKKKK.KoK", "KooKKKKzyyKKKooK", "ooomzzzKKKxxmooo", "oomzzzzyyyxxwmoo", "KomzzzyIKixxwmoK", "KomyyyiiKiiwwmoK", "oomyyyyiKiwwwmoo", "nnmxrxxxxwwwrmnn", "KKKKxrxxwwwwKKKK", "....KwwwwwwK....", ".....KWMWMWK....", "......KWKWK.....", ".......K.K......", "................"),
    listOf("................", "................", ".....KKKKKKK....", "....KKKzyyKKK...", "KKKKzzzKKKxxKKKK", "oomzzzzyyyxxwmoo", "oomzzzyIKixxwmoo", "KomyyyiiKiiwwmoK", "KomyyyyiKiwwwmoK", "oomxrxxxxwwwrmoo", "ooomxrxxwwwwmooo", "KooKKwwwwwwKKooK", "KnK..KWMWMWK.KnK", "KnK...KWKWK..KnK", ".K.....K.K....K.", "................"),
)

private val TOAD_ROWS = listOf(
    listOf("........................", "......K..........K......", "....KKCKK......KKCKK....", "...KFFFCBKKKKKKFFFCBK...", "..KCFKKKKAAFCACFKKKKAK..", "..KCCCYKBAAFCACCCKYBAK..", "..KBCBYKAAACCABCBKYAAK..", "..KABBAAAACCCBABBAAAAK..", "..KCAAAAABCCCCBAAAAAAK..", "..KCAAACCCCCBBBBBAAAAK..", ".KAAKdAAABBBBBBAAAAKAAK.", ".KFAdKKKKKKKKKKKKKKAAAK.", ".KCCAAddMMddAAAAAAAABAK.", ".KCCBALLAccccccALLDdAAK.", ".KCCCASSLLLLccccbDDdcAK.", ".KBBBAQQQQQQLLLbDDddcbK.", "..KBBANNNNNNNNLLdddcbbK.", "...KABALNNLLLLLLLccbbK..", "....KKAAAALLLLAAAAbbK...", ".....BBBKKKKKKKKBBBK...."),
    listOf("........................", "......K..........K......", "....KKCKK......KKCKK....", "...KFFFCBKKKKKKFFFCBK...", "..KCFKKKKAAFCACFKKKKAK..", "..KCCCYKBAAFCACCCKYBAK..", "..KBCBYKAAACCABCBKYAAK..", "..KABBAAAACCCBABBAAAAK..", "..KCAAAAABCCCCBAAAAAAK..", "..KCAAACCCCCBBBBBAAAAK..", "..KAddAAABBBBBBAAAAAAK..", ".KFAKddddAAABAAAAAAKAAK.", ".KCFAKKKKKKKKKKKKKKABAK.", ".KCCBALLMMcccccALLDdAAK.", ".KCCCASSLLLLccccbDDdcAK.", ".KBCCAQQQQQQLLLbDDddcbK.", "..KBBANNNNNNNNLLdddcbbK.", "...KBBALNNLLLLLLLccbbK..", "....KKAAAALLLLAAAAbbK...", ".....BBBKAAAAAAKBBBK...."),
)

private val MOTH_ROWS = listOf(
    listOf("K......................K", "DK....................KD", "DDK....oo.KKKK..oo...KDD", "dddK.....ooOoooo....Kddd", "ddddK...KOOOoonK..KKdddd", "dddddKKKOOOooonmKKdddddd", "dddddddKOOWKoKWmKddddddd", "KddddddbooKonnKmbddddddK", "KdddKYdbnonnnmmmbddKYddK", "KdddYKddbnnKKKmbdddYKddK", "KdddddddbmmmmmmbdddddddK", "KdddddddbOmmmmnbdddddddK", "KdddddddbOOoonnbdddddddK", ".KKcccccboooonmbcccccKK.", "...KKKKKbooonnmbKKKKK...", "........KoonnnmK..K.....", "........KonnnmmK.eY.....", "........KnnnmmmK..Ye....", "........KnnnmmmK..Y.....", ".........KmmmmK...K.....", "..........KmmK..........", "...........KK...........", "........................", "........................"),
    listOf("........................", "........................", ".......oo.KKKK..oo......", ".........ooOoooo........", "K.......KOOOoonK.......K", "dKK....KOOOooonmK....KKd", "dddKK..KOOWKoKWmK.KKKddd", "dddddKKKooKonnKmKKdddddd", "KdddKYdbnonnnmmmbddKYddK", "KdddYKddbnnKKKmbdddYKddK", "KdddddddbmmmmmmbdddddddK", "KdddddddbOmmmmnbdddddddK", "KdddddddbOOoonnbdddddddK", "KcccccccboooonmbcccccccK", ".KccccccbooonnmbccccccK.", ".KccccccboonnnmbccKcccK.", ".KccccKKKonnnmmKKeYcccK.", "..KKKK..KnnnmmmK..YeKK..", "........KnnnmmmK..Y.....", ".........KmmmmK...K.....", "..........KmmK..........", "...........KK...........", "........................", "........................"),
)
