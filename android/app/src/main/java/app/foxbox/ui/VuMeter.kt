package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import app.foxbox.audio.Audio
import kotlin.math.log10

/**
 * The block's level on an edgewise VU meter, like the upright ones set into old radio and hi-fi
 * fronts: an amber-lit scale in a chrome bezel, a needle that rises and falls with VU-style
 * ballistics, a red zone at the top and a peak lamp that holds for a moment.
 */
@SuppressLint("ViewConstructor")
class VuMeter(c: Context, private val blockId: String) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Px.head; textAlign = Paint.Align.CENTER }
    private val win = RectF(); private val bezel = RectF()
    private var shown = 0f; private var last = SystemClock.uptimeMillis(); private var peakUntil = 0L
    private var faceShader: Shader? = null

    private companion object {
        /** dB marks on the scale: 0 VU sits at −6 dBFS. */
        val MARKS = intArrayOf(-20, -10, -7, -5, -3, -1, 0, 1, 2, 3)
        const val LO = -22f; const val HI = 4f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val d = Px.dp(1f)
        bezel.set(1.5f * d, 3 * d, w - 1.5f * d, h - 3 * d)
        win.set(bezel.left + 4 * d, bezel.top + 22 * d, bezel.right - 4 * d, bezel.bottom - 24 * d)
        faceShader = LinearGradient(win.left, 0f, win.right, 0f, intArrayOf(0xFFE9C77A.toInt(), 0xFFFFE9B0.toInt(), 0xFFE9C77A.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    private fun yFor(db: Float) = win.bottom - 6f - (win.height() - 12f) * ((db - LO) / (HI - LO)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val d = Px.dp(1f)
        val now = SystemClock.uptimeMillis(); val dt = ((now - last) / 1000f).coerceIn(0f, 0.1f); last = now
        val lin = runCatching { Audio.engine(blockId).meter }.getOrDefault(0f)
        val vu = if (lin < 1e-5f) LO else (20f * log10(lin) + 6f)          // dBFS → VU (0 VU at −6 dBFS)
        // needle ballistics: about 300 ms to settle, a little quicker up than down
        val k = if (vu > shown) 1f - kotlin.math.exp(-dt / 0.12f) else 1f - kotlin.math.exp(-dt / 0.3f)
        shown += (vu.coerceIn(LO, HI) - shown) * k
        if (vu >= 1f) peakUntil = now + 900

        // chrome bezel and the dark recess
        p.shader = LinearGradient(0f, bezel.top, 0f, bezel.bottom, intArrayOf(0xFFEDEFF3.toInt(), 0xFFA9ADB6.toInt(), 0xFFD9DCE2.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(bezel, 8 * d, 8 * d, p); p.shader = null
        p.color = 0xFF1A1714.toInt(); c.drawRoundRect(bezel.left + 3 * d, bezel.top + 3 * d, bezel.right - 3 * d, bezel.bottom - 3 * d, 6 * d, 6 * d, p)
        // peak lamp up top, VU at the bottom
        val lx = bezel.centerX()
        p.color = if (now < peakUntil) 0xFFFF4A3A.toInt() else 0xFF4A2220.toInt(); c.drawCircle(lx, bezel.top + 12 * d, 4 * d, p)
        p.color = 0x55FFFFFF; c.drawCircle(lx - 1.2f * d, bezel.top + 10.8f * d, 1.3f * d, p)
        t.textSize = minOf(8 * d, bezel.width() * 0.3f); t.color = 0xFFE9C77A.toInt()
        c.drawText("VU", lx, bezel.bottom - 9 * d, t)
        // the lit scale
        p.shader = faceShader; c.drawRect(win, p); p.shader = null
        val redTop = yFor(0f)
        p.color = 0x55C8342C; c.drawRect(win.left, win.top, win.right, redTop, p)
        t.textSize = minOf(7 * d, win.width() * 0.36f)
        for (m in MARKS) {
            val y = yFor(m.toFloat())
            val red = m > 0
            p.color = if (red) 0xFFB02A22.toInt() else 0xFF3A2A18.toInt()
            val major = m == -20 || m == -10 || m == -5 || m == 0 || m == 3
            c.drawRect(win.left, y - 0.6f * d, win.left + win.width() * (if (major) 0.32f else 0.2f), y + 0.6f * d, p)
            if (major) { t.color = p.color; c.drawText(if (m > 0) "+$m" else "$m", win.centerX() + win.width() * 0.18f, y + t.textSize * 0.38f, t) }
        }
        // the needle: a thin black blade across the window with its shadow
        val ny = yFor(shown)
        p.color = 0x33000000; c.drawRect(win.left, ny + 1.5f * d, win.right, ny + 2.5f * d, p)
        p.color = 0xFF141210.toInt(); c.drawRect(win.left, ny - 0.8f * d, win.right, ny + 0.8f * d, p)
        p.color = 0xFFC8342C.toInt(); c.drawRect(win.right - win.width() * 0.22f, ny - 0.8f * d, win.right, ny + 0.8f * d, p)
        // glass
        p.color = 0x22FFFFFF; c.drawRect(win.left, win.top, win.left + win.width() * 0.35f, win.bottom, p)
        if (isShown) postInvalidateOnAnimation()
    }
}
