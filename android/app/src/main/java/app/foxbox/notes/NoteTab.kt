package app.foxbox.notes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import app.foxbox.ui.Glyph
import app.foxbox.ui.Px
import kotlin.math.hypot

/**
 * The notebook's tab: a little memo-pad key with the pencil-and-paper symbol, floating over every
 * screen but the game. Tap it to open the notebook; drag it anywhere it isn't in the way
 * (it remembers where you leave it).
 */
@SuppressLint("ViewConstructor")
class NoteTab(c: Context, private val open: () -> Unit) : View(c) {
    private val prefs = c.getSharedPreferences("5iy", Context.MODE_PRIVATE)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Px.head; textAlign = Paint.Align.CENTER; color = 0xFF23306E.toInt() }
    private var downX = 0f; private var downY = 0f; private var startTX = 0f; private var startTY = 0f; private var dragging = false

    init {
        elevation = Px.dp(6f)
        post { place(prefs.getFloat("tabX", 1f), prefs.getFloat("tabY", 0.42f)) }
    }

    private fun place(fx: Float, fy: Float) {
        val par = parent as? View ?: return
        translationX = (fx * (par.width - width)).coerceIn(0f, (par.width - width).toFloat())
        translationY = (fy * (par.height - height)).coerceIn(0f, (par.height - height).toFloat())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = Px.dp(1f)
        p.color = 0x55000000; c.drawRoundRect(3 * d, 4 * d, w - d, h - d, 7 * d, 7 * d, p)
        p.color = 0xFF7A2630.toInt(); c.drawRoundRect(d, d, w - 3 * d, h - 3 * d, 7 * d, 7 * d, p)
        p.color = 0xFFFBF6E6.toInt(); c.drawRoundRect(4 * d, 9 * d, w - 6 * d, h - 6 * d, 4 * d, 4 * d, p)
        p.color = 0xFFA8C3DE.toInt(); for (k in 0 until 3) c.drawRect(6 * d, 19 * d + k * 8 * d, w - 8 * d, 20 * d + k * 8 * d, p)
        g.textSize = h * 0.42f
        c.drawText(Glyph.NOTEBOOK, (w - 2 * d) / 2, h * 0.68f, g)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startTX = translationX; startTY = translationY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > Px.dp(10f)) dragging = true
                if (dragging) {
                    val par = parent as? View ?: return true
                    translationX = (startTX + e.rawX - downX).coerceIn(0f, (par.width - width).toFloat())
                    translationY = (startTY + e.rawY - downY).coerceIn(0f, (par.height - height).toFloat())
                }
            }
            MotionEvent.ACTION_UP -> {
                val par = parent as? View
                if (dragging && par != null) prefs.edit()
                    .putFloat("tabX", translationX / (par.width - width).coerceAtLeast(1))
                    .putFloat("tabY", translationY / (par.height - height).coerceAtLeast(1)).apply()
                else { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); open() }
            }
        }
        return true
    }
}
