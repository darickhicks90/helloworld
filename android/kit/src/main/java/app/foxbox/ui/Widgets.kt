package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Base class for FoxBox's custom-drawn views. */
abstract class PixelView(c: Context) : View(c) {
    protected val u get() = Px.u
    /** Redraw every frame while visible (for animated views). */
    var animated = false
        set(v) { field = v; if (v) postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        paint(canvas)
        if (animated && isShown) postInvalidateOnAnimation()
    }
    abstract fun paint(c: Canvas)
}

/** Pixel button: tap, toggle, or hold (onDown/onUp). */
@SuppressLint("ViewConstructor")
class Btn(c: Context, var text: String, var accent: Int = Pal.ORANGE, private val minW: Int = 0) : PixelView(c) {
    var on = false; set(v) { if (field != v) { field = v; invalidate() } }
    var blink = false; set(v) { field = v; animated = v; invalidate() }
    var icon: Sprite? = null
    /**
     * What to show when the label won't fit at a readable size: a symbol. Defaults to the label's
     * leading symbol ("● Record clip" → "●"); labels without one wrap onto two lines instead.
     */
    var short: String? = null
    private fun shortForm(): String? = short ?: text.substringBefore(' ', "").takeIf { it.isNotEmpty() && it.length <= 2 && it.none { ch -> ch.isLetterOrDigit() } }
    var onClick: (() -> Unit)? = null
    var onLong: (() -> Unit)? = null
    var onDown: (() -> Unit)? = null
    var onUp: (() -> Unit)? = null
    /** After a long press: drag with screen coordinates; phase 0 start, 1 move, 2 drop, 3 cancel. */
    var onDrag: ((Float, Float, Int) -> Unit)? = null
    private var dragging = false
    private var pressed = false
    private var longFired = false
    private val textP = Px.text(14f)
    private var lastRawX = 0f; private var lastRawY = 0f
    private val long = Runnable {
        longFired = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val d = onDrag
        if (d != null) { dragging = true; pressed = false; invalidate(); parent?.requestDisallowInterceptTouchEvent(true); d(lastRawX, lastRawY, 0) } else onLong?.invoke()
    }

    override fun onMeasure(w: Int, h: Int) {
        val iconW = if (icon != null) Px.dp(26) else 0
        val want = maxOf(Px.dp(minW), (textP.measureText(text) + Px.dp(22)).toInt() + iconW)
        setMeasuredDimension(resolveSize(want, w), resolveSize(Px.dp(40), h))
    }

    override fun paint(c: Canvas) {
        val lit = on && (!blink || (System.currentTimeMillis() / 300) % 2 == 0L)
        val bg = when { pressed -> Pal.PAPER; lit -> accent; else -> Pal.CREAM }
        c.pixelBox(u, u, width - u, height - u, bg)
        textP.color = if (lit && !pressed) textOn(accent) else Pal.BROWN_D
        textP.textSize = Px.dp(14f)
        val pad = Px.dp(8f)
        val ic = icon
        if (ic != null) {
            // whole-pixel scaling keeps the sprite crisp; it never fills more than ~60% of the height
            val s = maxOf(1f, kotlin.math.floor(height * 0.62f / ic.h)).coerceAtMost(height * 0.75f / ic.h)
            val iw = ic.w * s
            val gap = if (text.isEmpty()) 0f else Px.dp(6f)
            val room = width - pad * 2 - iw - gap
            // beside an icon: the whole label at a readable size, else its symbol, else just the icon (it is the symbol)
            var t = text
            if (t.isNotEmpty()) {
                while (textP.measureText(t) > room && textP.textSize > Px.dp(10f)) textP.textSize -= Px.dp(0.5f)
                if (textP.measureText(t) > room) { textP.textSize = Px.dp(14f); t = shortForm()?.takeIf { textP.measureText(it) <= room } ?: "" }
            }
            val total = iw + if (t.isEmpty()) 0f else textP.measureText(t) + gap
            val x0 = maxOf(pad, (width - total) / 2)
            c.sprite(ic, x0, (height - ic.h * s) / 2, s)
            if (t.isNotEmpty()) { textP.textAlign = android.graphics.Paint.Align.LEFT; c.textCentered(t, x0 + iw + gap, height / 2f, textP); textP.textAlign = android.graphics.Paint.Align.CENTER }
        } else drawLabel(c, width - pad * 2)
    }

    /** The label, always whole: one line (down to 11sp), else its symbol, else two lines, else smaller still. */
    private fun drawLabel(c: Canvas, room: Float) {
        val cx = width / 2f; val cy = height / 2f
        fun fits(s: String, sp: Float): Boolean { textP.textSize = Px.dp(sp); return textP.measureText(s) <= room }
        var sp = 14f
        while (sp > 11f && !fits(text, sp)) sp -= 0.5f
        if (fits(text, sp)) { c.textCentered(text, cx, cy, textP); return }
        shortForm()?.let { s -> if (fits(s, 14f) || fits(s, 11f)) { c.textCentered(s, cx, cy, textP); return } }
        // two lines, split at the space nearest the middle
        val spaces = text.indices.filter { text[it] == ' ' }
        if (spaces.isNotEmpty()) {
            val at = spaces.minBy { kotlin.math.abs(it - text.length / 2) }
            val a = text.substring(0, at); val b = text.substring(at + 1)
            var s2 = 13f
            while (s2 > 8.5f && !(fits(a, s2) && fits(b, s2))) s2 -= 0.5f
            fits(a, s2)
            if (textP.measureText(a) <= room && textP.measureText(b) <= room && textP.textSize * 2.1f <= height) {
                val lh = textP.textSize * 1.05f
                c.textCentered(a, cx, cy - lh / 2, textP); c.textCentered(b, cx, cy + lh / 2, textP); return
            }
        }
        textP.textSize = Px.dp(14f)
        c.textFit(text, cx, cy, room, textP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastRawX = e.rawX; lastRawY = e.rawY; pressed = true; longFired = false; dragging = false; onDown?.invoke(); if (onLong != null || onDrag != null) postDelayed(long, 450); invalidate() }
            MotionEvent.ACTION_MOVE -> { lastRawX = e.rawX; lastRawY = e.rawY; if (dragging) onDrag?.invoke(e.rawX, e.rawY, 1) }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(long)
                if (dragging) { dragging = false; onDrag?.invoke(e.rawX, e.rawY, 2); return true }
                if (pressed && !longFired && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) onClick?.invoke()
                pressed = false; onUp?.invoke(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(long); if (dragging) { dragging = false; onDrag?.invoke(e.rawX, e.rawY, 3) }; pressed = false; onUp?.invoke(); invalidate() }
        }
        return true
    }
}


/** "◀ value ▶" control. */
@SuppressLint("ViewConstructor")
class Stepper(c: Context, private val label: String, private val minW: Int = 120, private val text: () -> String, private val step: (Int) -> Unit) : PixelView(c) {
    /** Optional action when the middle (the value) is tapped. */
    var onMiddle: (() -> Unit)? = null
    private val tp = Px.text(14f); private val lp = Px.text(10f, Pal.BROWN_D, headFont = false, align = android.graphics.Paint.Align.CENTER)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(Px.dp(minW), w), resolveSize(Px.dp(40), h))
    override fun paint(c: Canvas) {
        c.pixelBox(u, u, width - u, height - u, Pal.CREAM)
        // narrow steppers tuck their arrows in, so the value keeps room to be read whole
        val narrow = width < Px.dp(80f)
        tp.textSize = Px.dp(if (narrow) 11f else 14f)
        val ax = (width * 0.15f).coerceIn(Px.dp(7f), Px.dp(14f))
        val aw = tp.measureText("◀")
        c.textCentered("◀", ax, height / 2f, tp); c.textCentered("▶", width - ax, height / 2f, tp)
        // the value sits between the two arrows and shrinks to fit there
        val room = width - 2 * (ax + aw / 2 + Px.dp(3f))
        tp.textSize = Px.dp(14f); lp.textSize = Px.dp(10f)
        // the label sits above the arrows, so it can use nearly the whole width
        if (label.isNotEmpty()) { c.textFit(label, width / 2f, Px.dp(10f), width - Px.dp(12f), lp, 8f); c.textFit(text(), width / 2f, height / 2f + Px.dp(5f), room, tp) }
        else c.textFit(text(), width / 2f, height / 2f, room, tp)
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val mid = onMiddle
            if (mid != null && e.x > width / 3f && e.x < width * 2 / 3f) mid() else step(if (e.x < width / 2f) -1 else 1)
            invalidate()
        }
        return true
    }
}

/** Lays children left-to-right, wrapping to new rows. */
class Flow(c: Context) : ViewGroup(c) {
    var gap = Px.dp(4)
    override fun onMeasure(w: Int, h: Int) {
        val maxW = MeasureSpec.getSize(w).takeIf { MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED } ?: Int.MAX_VALUE
        var x = 0; var y = 0; var rowH = 0; var widest = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i); ch.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += ch.measuredWidth + gap; rowH = maxOf(rowH, ch.measuredHeight); widest = maxOf(widest, x)
        }
        setMeasuredDimension(if (maxW == Int.MAX_VALUE) widest else maxW, y + rowH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (x > 0 && x + ch.measuredWidth > r - l) { x = 0; y += rowH + gap; rowH = 0 }
            ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
            x += ch.measuredWidth + gap; rowH = maxOf(rowH, ch.measuredHeight)
        }
    }
}

/** A labelled parameter panel: grey console plastic with a coloured title stripe. */
@SuppressLint("ViewConstructor")
class Panel(c: Context, private val title: String, private val accent: Int = Snes.PURPLE) : LinearLayout(c) {
    private val tp = Px.text(9f, Snes.INK, headFont = true, align = android.graphics.Paint.Align.LEFT)
    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        val pad = Px.dp(9)
        setPadding(pad, if (title.isEmpty()) pad else Px.dp(30), pad, pad)
    }
    override fun onDraw(c: Canvas) {
        with(Snes) {
            val u = Px.u
            c.plate(0f, 0f, width.toFloat(), height.toFloat())
            if (title.isNotEmpty()) {
                val bar = Px.dp(24f)
                c.fill(u * 3, u * 3, width - u * 3, bar, Snes.FACE_D)
                c.fill(u * 3, bar, width - u * 3, bar + u, accent)
                val s = Px.dp(8f)
                c.fill(Px.dp(9f), (u * 3 + bar) / 2 - s / 2, Px.dp(9f) + s, (u * 3 + bar) / 2 + s / 2, Snes.INK)
                c.fill(Px.dp(9f) + u * 0.6f, (u * 3 + bar) / 2 - s / 2 + u * 0.6f, Px.dp(9f) + s - u * 0.6f, (u * 3 + bar) / 2 + s / 2 - u * 0.6f, accent)
                tp.color = if (accent == Snes.YELLOW) Snes.INK else accent
                c.drawText(fitText(title, width - Px.dp(30f), tp, 7f), Px.dp(22f), (u * 3 + bar) / 2 - (tp.descent() + tp.ascent()) / 2, tp)
            }
        }
    }
}

// Layout helpers
fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun row(c: Context, vararg views: View) = LinearLayout(c).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = android.view.Gravity.CENTER_VERTICAL
    views.forEach { addView(it) }
}

fun spacer(c: Context, w: Int = 0, weight: Float = 1f) = View(c).apply { layoutParams = lp(w, 1, weight) }

