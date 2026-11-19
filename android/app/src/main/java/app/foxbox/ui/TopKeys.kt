package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * The radio and notebook keys: always in the same place, the top right corner of every screen
 * (the studio, Clibbins, the title, the great hall, the notebook and the drawing board).
 */
@SuppressLint("ViewConstructor")
class TopKeys(c: Context, private val onRadio: () -> Unit, private val onNotebook: () -> Unit) : View(c) {
    /** Lit while the notebook is open / while a station is playing (a red lamp while recording). */
    var notebookOpen = false; set(v) { field = v; invalidate() }
    var radioOn = false; set(v) { field = v; invalidate() }
    var radioRec = false; set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Px.head; textAlign = Paint.Align.CENTER }
    private val radio = RectF(); private val note = RectF()
    private var down: RectF? = null

    companion object {
        /** Room a screen leaves free at its top right for these keys. */
        val WIDTH get() = Px.dp(100f)
        val HEIGHT get() = Px.dp(46f)
    }

    init { elevation = Px.dp(12f) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val d = Px.dp(1f); val kw = (w - 10 * d) / 2
        radio.set(2 * d, 4 * d, 2 * d + kw, h - 4 * d)
        note.set(radio.right + 6 * d, 4 * d, radio.right + 6 * d + kw, h - 4 * d)
    }

    private fun key(c: Canvas, r: RectF, glyph: String, lit: Boolean, lamp: Int, pressed: Boolean) {
        val d = Px.dp(1f)
        p.color = 0x55000000; c.drawRoundRect(r.left + d, r.top + 2 * d, r.right + d, r.bottom + 2 * d, 7 * d, 7 * d, p)
        p.color = Snes.INK; c.drawRoundRect(r, 7 * d, 7 * d, p)
        p.color = if (pressed) Snes.SHADE else Snes.FACE; c.drawRoundRect(r.left + 2 * d, r.top + 2 * d, r.right - 2 * d, r.bottom - 3 * d, 6 * d, 6 * d, p)
        p.color = 0x50FFFFFF; c.drawRect(r.left + 6 * d, r.top + 3 * d, r.right - 6 * d, r.top + 5 * d, p)
        g.textSize = r.height() * 0.5f; g.color = Snes.INK
        c.drawText(glyph, r.centerX() - d, r.centerY() + g.textSize * 0.4f, g)
        // a little lamp in the corner: lit while it's on
        p.color = if (lit) lamp else 0xFF6E6C78.toInt()
        c.drawCircle(r.right - 7 * d, r.top + 8 * d, 2.5f * d, p)
    }

    override fun onDraw(c: Canvas) {
        key(c, radio, Glyph.RADIO, radioOn || radioRec, if (radioRec) Snes.RED else Snes.GREEN, down === radio)
        key(c, note, Glyph.NOTEBOOK, notebookOpen, Snes.YELLOW, down === note)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = when { radio.contains(e.x, e.y) -> radio; note.contains(e.x, e.y) -> note; else -> null }; invalidate() }
            MotionEvent.ACTION_UP -> {
                val k = down; down = null; invalidate()
                if (k != null && k.contains(e.x, e.y)) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    if (k === radio) onRadio() else onNotebook()
                }
            }
            MotionEvent.ACTION_CANCEL -> { down = null; invalidate() }
        }
        return true
    }
}
