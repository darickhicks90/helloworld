package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.foxbox.Noodler
import app.foxbox.seq.Theory

/**
 * The Noodler key: beside the radio and notebook keys, in the same place on the
 * studio, Clibbins and the Fire Escape. Tap to start or stop, hold for its settings.
 * While it listens its name flickers like an exotic's in the great hall, and it
 * shows the note it hears.
 */
@SuppressLint("ViewConstructor")
class NoodlerKey(c: Context, private val onTap: () -> Unit, private val onHold: () -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Px.head; textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private var pressed = false
    private var held = false
    private val t0 = System.nanoTime()
    private val hold = Runnable { held = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); pressed = false; invalidate(); onHold() }

    companion object {
        /** Room the key takes, left of the radio and notebook keys. */
        val WIDTH get() = Px.dp(62f)
        /** The exotic flicker: three colours, four changes a second. */
        fun flicker(time: Float): Int = when ((time * 4).toInt() % 3) { 0 -> 0xFFB060F0.toInt(); 1 -> 0xFF20C0B0.toInt(); else -> 0xFF60E0F0.toInt() }
    }

    init { elevation = Px.dp(12f) }

    override fun onDraw(c: Canvas) {
        val d = Px.dp(1f)
        r.set(2 * d, 4 * d, width - 2 * d, height - 4 * d)
        val on = Noodler.active
        val time = (System.nanoTime() - t0) / 1e9f
        p.color = 0x55000000; c.drawRoundRect(r.left + d, r.top + 2 * d, r.right + d, r.bottom + 2 * d, 7 * d, 7 * d, p)
        p.color = Snes.INK; c.drawRoundRect(r, 7 * d, 7 * d, p)
        p.color = if (pressed) Snes.SHADE else if (on) 0xFF2A2238.toInt() else Snes.FACE
        c.drawRoundRect(r.left + 2 * d, r.top + 2 * d, r.right - 2 * d, r.bottom - 3 * d, 6 * d, 6 * d, p)
        val note = Noodler.noteNow
        g.color = if (on) flicker(time) else Snes.INK
        g.textSize = r.height() * (if (on && note >= 0) 0.3f else 0.34f)
        // fit NOODLE across the key, whatever the font and screen
        val room = r.width() - 8 * d
        val tw = g.measureText("NOODLE")
        if (tw > room) g.textSize *= room / tw
        c.drawText("NOODLE", r.centerX(), r.centerY() + g.textSize * (if (on && note >= 0) -0.1f else 0.36f), g)
        if (on && note >= 0) {
            g.textSize = r.height() * 0.32f
            c.drawText(Theory.NOTE_NAMES[Math.floorMod(note, 12)] + (note / 12 - 1), r.centerX(), r.bottom - 6 * d, g)
        }
        if (on) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = true; held = false; postDelayed(hold, 480); invalidate() }
            MotionEvent.ACTION_UP -> { removeCallbacks(hold); if (pressed && !held) onTap(); pressed = false; invalidate() }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(hold); pressed = false; invalidate() }
        }
        return true
    }
}
