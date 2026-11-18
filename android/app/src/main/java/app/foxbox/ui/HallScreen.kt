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
 * screen (160×144, scaled up in whole pixels) of an all-white hall where the
 * five-eyed creature stands on his dais and swears at you. A little traveller
 * walks about under the D-pad; A talks (or moves his rant along), B closes
 * the box. Back leaves. It has nothing to do with the music side of the app.
 */
@SuppressLint("ViewConstructor")
class HallScreen(c: Context) : PixelView(c) {
    private companion object {
        const val W = 160; const val H = 144
        // the hall: whites and pale greys only
        const val WHITE = 0xFFF8F8F8.toInt(); const val PALE = 0xFFE0E0E8.toInt(); const val MID = 0xFFB8B8C8.toInt(); const val DARK = 0xFF707088.toInt(); const val INK = 0xFF181820.toInt()
        // the handheld's shell and keys
        const val SHELL = 0xFF2BA39B.toInt(); const val SHELL_D = 0xFF1C7A74.toInt(); const val SHELL_L = 0xFF5CC6BE.toInt()
        const val BEZEL = 0xFF3A3A48.toInt(); const val KEY = 0xFF2A2A34.toInt(); const val AB = 0xFF8E2A4A.toInt(); const val AB_L = 0xFFB8466A.toInt()
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
    private var lastNs = System.nanoTime()

    init { animated = true; isFocusable = true }

    // ---- the hall layout (screen pixels)
    private val dais = RectF(44f, 34f, 116f, 86f)
    private val pillars = listOf(RectF(12f, 30f, 28f, 130f), RectF(132f, 30f, 148f, 130f))
    private fun blocked(x: Float, y: Float): Boolean {
        val l = x + 3; val r = x + 13; val t = y + 10; val b = y + 15
        if (l < 4 || r > W - 4 || t < 30 || b > H - 2) return true
        if (r > dais.left + 4 && l < dais.right - 4 && t < dais.bottom - 2 && b > dais.top) return true
        return pillars.any { r > it.left && l < it.right && t < it.bottom && b > it.bottom - 10 }
    }

    private fun update(dt: Float) {
        if (dirX != 0 || dirY != 0) {
            facing = when { dirY > 0 -> 0; dirY < 0 -> 1; dirX < 0 -> 2; else -> 3 }
            val sp = 46f * dt
            val nx = hx + dirX * sp; val ny = hy + dirY * sp
            if (!blocked(nx, hy)) hx = nx
            if (!blocked(hx, ny)) hy = ny
            walkT += dt
        } else walkT = 0f
        if (talking) shown += dt * 28f
    }

    // ---- drawing the little screen
    private fun box(l: Float, t: Float, r: Float, b: Float, col: Int) { p.color = col; sc.drawRect(l, t, r, b, p) }

    private fun drawHall(time: Float) {
        box(0f, 0f, W.toFloat(), H.toFloat(), WHITE)
        // back wall: big white blocks with pale mortar, a frieze, and two tall windows of light
        box(0f, 0f, W.toFloat(), 30f, PALE)
        for (row in 0 until 3) for (k in 0 until 11) {
            val off = if (row % 2 == 0) 0f else 8f
            box(k * 16f - off + 1, row * 9f + 1, k * 16f - off + 15, row * 9f + 8, WHITE)
        }
        box(0f, 27f, W.toFloat(), 30f, MID); box(0f, 30f, W.toFloat(), 31f, DARK)
        for (wx in floatArrayOf(36f, 116f)) { box(wx, 2f, wx + 8, 24f, MID); box(wx + 1, 3f, wx + 7, 24f, WHITE); box(wx + 3, 3f, wx + 5, 24f, PALE) }
        // floor: white tiles with pale grout, a darker run of carpet up the middle to the dais
        for (ty in 0 until 8) for (tx in 0 until 10) {
            val x0 = tx * 16f; val y0 = 31f + ty * 16f
            box(x0, y0, x0 + 16, y0 + 1, PALE); box(x0, y0, x0 + 1, y0 + 16, PALE)
            if ((tx + ty) % 2 == 0) box(x0 + 2, y0 + 2, x0 + 4, y0 + 4, PALE)
        }
        box(64f, 86f, 96f, H.toFloat(), PALE); box(66f, 86f, 94f, H.toFloat(), WHITE)
        for (y in 88 until H step 6) box(68f, y.toFloat(), 92f, y + 1f, PALE)
        // the dais: three white steps
        box(dais.left, dais.bottom - 6, dais.right, dais.bottom, MID); box(dais.left, dais.bottom - 6, dais.right, dais.bottom - 5, DARK)
        box(dais.left + 4, dais.bottom - 12, dais.right - 4, dais.bottom - 6, PALE); box(dais.left + 4, dais.bottom - 12, dais.right - 4, dais.bottom - 11, MID)
        box(dais.left + 8, dais.top, dais.right - 8, dais.bottom - 12, WHITE)
        // pillars: white columns with grey fluting and capitals
        for (pl in pillars) {
            box(pl.left - 2, 30f, pl.right + 2, 34f, MID); box(pl.left - 2, 30f, pl.right + 2, 31f, DARK)
            box(pl.left, 34f, pl.right, pl.bottom - 4, WHITE)
            box(pl.left, 34f, pl.left + 1, pl.bottom - 4, MID); box(pl.right - 1, 34f, pl.right, pl.bottom - 4, MID)
            for (f in 1 until 4) box(pl.left + f * 4f, 36f, pl.left + f * 4f + 1, pl.bottom - 6, PALE)
            box(pl.left - 2, pl.bottom - 4, pl.right + 2, pl.bottom, MID); box(pl.left - 2, pl.bottom - 1, pl.right + 2, pl.bottom, DARK)
        }
    }

    private val src = Rect(); private val dst = RectF()
    private fun drawYeti(time: Float) {
        val bmp = yeti ?: return
        // idle: arms out, breathing (a pixel up and down), now and then a scratch of the belly.
        // ranting: fists in the air, shrugging, shaking with rage.
        val frame: Int; var dx = 0f; var dy = 0f
        if (talking) {
            val k = (time * 5).toInt()
            frame = intArrayOf(3, 5, 3, 0)[k % 4]
            dx = if (k % 2 == 0) -1f else 1f
        } else {
            val cycle = time % 7f
            frame = if (cycle > 5.6f) 4 else if (cycle > 5.2f) 1 else 0
            dy = if ((time * 1.6f).toInt() % 2 == 0) 0f else 1f
        }
        val x = 52f + dx; val y = dais.top - 26f + dy
        // his shadow on the dais
        box(x + 14, y + 54, x + 42, y + 56, PALE)
        src.set(frame * 56, 0, frame * 56 + 56, 56); dst.set(x, y, x + 56, y + 56)
        sc.drawBitmap(bmp, src, dst, p)
        // a little cloud of curses over his head when he isn't talking
        if (!talking && (time % 4f) < 1.6f) {
            val word = MUTTER[((time / 4f).toInt()) % MUTTER.size]
            val bw = word.length * 8f + 6; val bx = x + 40; val by = y - 4
            box(bx - 1, by - 1, bx + bw + 1, by + 12, INK); box(bx, by, bx + bw, by + 11, WHITE)
            box(bx + 2, by + 11, bx + 6, by + 14, INK); box(bx + 3, by + 11, bx + 5, by + 13, WHITE)
            sc.drawText(word, bx + 3, by + 9, font)
        }
    }

    private fun drawHero() {
        val moving = dirX != 0 || dirY != 0
        val f = if (moving && (walkT * 6f).toInt() % 2 == 1) 1 else 0
        val s = hero[if (facing == 3) 2 else facing][f]
        val x = hx.toInt().toFloat(); val y = hy.toInt().toFloat()
        box(x + 4, y + 14, x + 12, y + 16, PALE)
        src.set(0, 0, s.w, s.h)
        if (facing == 3) { sc.save(); sc.scale(-1f, 1f, x + 8, y + 8); dst.set(x, y, x + 16, y + 16); sc.drawBitmap(s.bmp, src, dst, p); sc.restore() }
        else { dst.set(x, y, x + 16, y + 16); sc.drawBitmap(s.bmp, src, dst, p) }
    }

    /** The talk box along the bottom, typed out a letter at a time, word-wrapped to 18 columns. */
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

    // ---- the handheld around the screen, with the D-pad and A / B
    private val pad = RectF(); private val aKey = RectF(); private val bKey = RectF()
    private val shellP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Px.text(13f, 0xFFE8E8F0.toInt(), headFont = true)

    override fun paint(c: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f); lastNs = now
        val time = (now - t0) / 1e9f
        update(dt)
        drawHall(time); drawYeti(time)
        // whoever is lower on screen is drawn in front
        drawHero()
        drawTalk(time)

        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(SHELL)
        shellP.color = SHELL_L; c.drawRect(0f, 0f, w, Px.dp(6f), shellP)
        shellP.color = SHELL_D; c.drawRect(0f, h - Px.dp(6f), w, h, shellP)
        // the screen, as large as whole pixels allow, between the controls
        val scale = maxOf(1f, kotlin.math.floor(minOf(w * 0.56f / W, h * 0.86f / H)))
        val sw = W * scale; val sh = H * scale
        val sl = (w - sw) / 2; val st = (h - sh) / 2
        shellP.color = BEZEL; c.drawRoundRect(sl - Px.dp(18f), st - Px.dp(16f), sl + sw + Px.dp(18f), st + sh + Px.dp(16f), Px.dp(12f), Px.dp(12f), shellP)
        // power lamp
        shellP.color = 0xFFE84848.toInt(); c.drawCircle(sl - Px.dp(9f), st + sh * 0.3f, Px.dp(3f), shellP)
        src.set(0, 0, W, H); dst.set(sl, st, sl + sw, st + sh)
        c.drawBitmap(screen, src, dst, p)

        // D-pad, left
        val ds = minOf(Px.dp(150f), sl - Px.dp(40f)).coerceAtLeast(Px.dp(96f))
        val dcx = sl / 2; val dcy = h * 0.58f
        pad.set(dcx - ds / 2, dcy - ds / 2, dcx + ds / 2, dcy + ds / 2)
        val arm = ds / 3
        shellP.color = SHELL_D; c.drawCircle(dcx, dcy, ds * 0.62f, shellP)
        shellP.color = KEY
        c.drawRoundRect(dcx - arm / 2, dcy - ds / 2, dcx + arm / 2, dcy + ds / 2, Px.dp(5f), Px.dp(5f), shellP)
        c.drawRoundRect(dcx - ds / 2, dcy - arm / 2, dcx + ds / 2, dcy + arm / 2, Px.dp(5f), Px.dp(5f), shellP)
        shellP.color = 0xFF44444F.toInt()
        val tri = arm * 0.22f
        for ((ax, ay) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
            val lit = (ax.toInt() == dirX && ay.toInt() == dirY && (dirX != 0 || dirY != 0))
            shellP.color = if (lit) 0xFF6A6A78.toInt() else 0xFF44444F.toInt()
            c.drawCircle(dcx + ax * ds * 0.36f, dcy + ay * ds * 0.36f, tri, shellP)
        }
        // A and B, right, on a slant
        val r = minOf(Px.dp(34f), (w - sl - sw) / 6f).coerceAtLeast(Px.dp(24f))
        val rcx = sl + sw + (w - sl - sw) / 2
        aKey.set(rcx + r * 0.3f, h * 0.48f - r, rcx + r * 2.3f, h * 0.48f + r)
        bKey.set(rcx - r * 2.1f, h * 0.62f - r, rcx - r * 0.1f, h * 0.62f + r)
        shellP.color = SHELL_D; c.drawRoundRect(bKey.left - r * 0.4f, aKey.top - r * 0.3f, aKey.right + r * 0.4f, bKey.bottom + r * 0.3f, r, r, shellP)
        for ((k, s) in listOf(aKey to "A", bKey to "B")) {
            shellP.color = 0xFF5A1A30.toInt(); c.drawCircle(k.centerX() + Px.dp(2f), k.centerY() + Px.dp(3f), r, shellP)
            shellP.color = if ((s == "A" && aDown) || (s == "B" && bDown)) AB else AB_L; c.drawCircle(k.centerX(), k.centerY(), r, shellP)
            shellP.color = AB; c.drawCircle(k.centerX() + r * 0.12f, k.centerY() + r * 0.15f, r * 0.82f, shellP)
            label.textSize = Px.dp(13f)
            c.textCentered(s, k.centerX(), k.centerY(), label)
        }
    }

    // ---- touch: the D-pad holds a direction; A and B are taps
    private var padPointer = -1
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
            else if (line < RANT.size - 1) { line++; shown = 0f } else { talking = false }
        } else {
            // talk to him: face the dais from close by
            val cx = hx + 8; val cy = hy + 8
            if (cy < dais.bottom + 16 && cx > dais.left - 6 && cx < dais.right + 6) { talking = true; line = 0; shown = 0f }
        }
    }
    private fun pressB() { if (talking) talking = false }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i); val y = e.getY(i)
                when {
                    x < pad.right + pad.width() * 0.3f && y > pad.top - pad.height() * 0.3f && y < pad.bottom + pad.height() * 0.3f -> { padPointer = e.getPointerId(i); steer(x, y) }
                    near(aKey, x, y) -> { aDown = true; pressA(); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                    near(bKey, x, y) -> { bDown = true; pressB(); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val k = e.findPointerIndex(padPointer)
                if (k >= 0) steer(e.getX(k), e.getY(k))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(i) == padPointer) { padPointer = -1; dirX = 0; dirY = 0 } else { aDown = false; bDown = false }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { padPointer = -1; dirX = 0; dirY = 0; aDown = false; bDown = false }
        }
        return true
    }
}
