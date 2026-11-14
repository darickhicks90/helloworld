package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.foxbox.audio.Param
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
    var onClick: (() -> Unit)? = null
    var onLong: (() -> Unit)? = null
    var onDown: (() -> Unit)? = null
    var onUp: (() -> Unit)? = null
    private var pressed = false
    private var longFired = false
    private val textP = Px.text(14f)
    private val long = Runnable { longFired = true; onLong?.invoke(); performHapticFeedback(0) }

    override fun onMeasure(w: Int, h: Int) {
        val iconW = if (icon != null) Px.dp(26) else 0
        val want = maxOf(Px.dp(minW), (textP.measureText(text) + Px.dp(22)).toInt() + iconW)
        setMeasuredDimension(resolveSize(want, w), resolveSize(Px.dp(40), h))
    }

    override fun paint(c: Canvas) {
        val lit = on && (!blink || (System.currentTimeMillis() / 300) % 2 == 0L)
        val bg = when { pressed -> Pal.PAPER; lit -> accent; else -> Pal.CREAM }
        c.pixelBox(u, u, width - u, height - u, bg)
        textP.color = if (lit && !pressed) Pal.CREAM else Pal.BROWN_D
        val ic = icon
        if (ic != null) {
            val s = (height * 0.55f) / ic.h
            val iw = ic.w * s
            val total = iw + if (text.isEmpty()) 0f else textP.measureText(text) + Px.dp(6)
            val x0 = (width - total) / 2
            c.sprite(ic, x0, (height - ic.h * s) / 2, s)
            if (text.isNotEmpty()) { textP.textAlign = android.graphics.Paint.Align.LEFT; c.textCentered(text, x0 + iw + Px.dp(6), height / 2f, textP.apply { }) ; textP.textAlign = android.graphics.Paint.Align.CENTER }
        } else c.textCentered(text, width / 2f, height / 2f, textP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = true; longFired = false; onDown?.invoke(); if (onLong != null) postDelayed(long, 500); invalidate() }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(long)
                if (pressed && !longFired && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) onClick?.invoke()
                pressed = false; onUp?.invoke(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(long); pressed = false; onUp?.invoke(); invalidate() }
        }
        return true
    }
}

/** Rotary knob bound to a [Param]: drag up/down, double-tap to reset; choices step on tap. */
@SuppressLint("ViewConstructor")
class Knob(c: Context, val param: Param, var accent: Int) : PixelView(c) {
    private val label = Px.text(11f, Pal.BROWN)
    private val value = Px.text(12f, Pal.BROWN_D)
    private var startY = 0f; private var startN = 0f; private var lastTap = 0L; private var moved = false
    private val listener: () -> Unit = { postInvalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); param.listeners.add(listener) }
    override fun onDetachedFromWindow() { param.listeners.remove(listener); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Px.dp(70), Px.dp(84))

    override fun paint(c: Canvas) {
        val cx = width / 2f; val cy = Px.dp(28f); val r = Px.dp(20f)
        val n = param.norm
        // Ring of pixel lamps.
        val segs = 11
        val lit = (n * (segs - 1)).roundToInt()
        for (i in 0 until segs) {
            val a = Math.toRadians(-225.0 + 270.0 * i / (segs - 1))
            val col = if (i <= lit && (n > 0.001f || i == 0)) accent else Pal.PAPER
            c.dot(cx + (cos(a) * r).toFloat() - u, cy + (sin(a) * r).toFloat() - u, col, 2)
        }
        // Body: a small pixel disc with a pointer.
        val br = Px.dp(12f)
        var y = -br
        while (y <= br) {
            var x = -br
            while (x <= br) {
                val d = kotlin.math.hypot(x, y)
                if (d <= br) c.dot(cx + x, cy + y, if (d > br - u) Pal.BROWN_D else Pal.CREAM)
                x += u
            }
            y += u
        }
        val a = Math.toRadians(-225.0 + 270.0 * n)
        c.dot(cx + (cos(a) * br * 0.55f).toFloat() - u / 2, cy + (sin(a) * br * 0.55f).toFloat() - u / 2, Pal.BROWN_D, 2)
        c.textCentered(param.text(), cx, Px.dp(60f), value)
        c.textCentered(param.name, cx, Px.dp(75f), label.apply { textSize = Px.dp(if (param.name.length > 12) 9.5f else 11f) })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); startY = e.y; startN = param.norm; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.y - startY) > Px.dp(4f)) moved = true
                if (moved && !param.isChoice) param.norm = startN + (startY - e.y) / Px.dp(160f)
            }
            MotionEvent.ACTION_UP -> {
                val now = System.currentTimeMillis()
                if (!moved) {
                    if (param.isChoice) param.set(if (param.index >= param.max.toInt()) 0f else param.value + 1f)
                    else if (now - lastTap < 300) param.set(param.def)
                    lastTap = now
                } else if (param.isChoice) param.norm = startN + (startY - e.y) / Px.dp(120f)
                app.foxbox.Studio.save()
            }
        }
        return true
    }
}

/** "◀ value ▶" control. */
@SuppressLint("ViewConstructor")
class Stepper(c: Context, private val label: String, private val minW: Int = 120, private val text: () -> String, private val step: (Int) -> Unit) : PixelView(c) {
    /** Optional action when the middle (the value) is tapped. */
    var onMiddle: (() -> Unit)? = null
    private val tp = Px.text(14f); private val lp = Px.text(9f, Pal.BROWN, align = android.graphics.Paint.Align.CENTER)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(Px.dp(minW), w), resolveSize(Px.dp(40), h))
    override fun paint(c: Canvas) {
        c.pixelBox(u, u, width - u, height - u, Pal.CREAM)
        c.textCentered("◀", Px.dp(14f), height / 2f, tp); c.textCentered("▶", width - Px.dp(14f), height / 2f, tp)
        if (label.isNotEmpty()) { c.textCentered(label, width / 2f, Px.dp(11f), lp); c.textCentered(text(), width / 2f, height / 2f + Px.dp(5f), tp) }
        else c.textCentered(text(), width / 2f, height / 2f, tp)
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

/** A labelled pixel panel that holds other views. */
@SuppressLint("ViewConstructor")
class Panel(c: Context, private val title: String, private val bg: Int = Pal.CREAM, private val accent: Int = Pal.ORANGE) : LinearLayout(c) {
    private val tp = Px.text(8f, Pal.BROWN_D, headFont = true, align = android.graphics.Paint.Align.LEFT)
    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        val pad = Px.dp(8)
        setPadding(pad, if (title.isEmpty()) pad else Px.dp(22), pad, pad)
    }
    override fun onDraw(c: Canvas) {
        c.pixelBox(0f, 0f, width.toFloat(), height.toFloat(), bg, shade = false)
        if (title.isNotEmpty()) {
            c.fill(Px.dp(9f), Px.dp(9f), Px.dp(15f), Px.dp(15f), accent)
            c.drawText(title, Px.dp(20f), Px.dp(16f), tp)
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

/** Run `fn` whenever Studio changes, while this view is attached. */
fun android.view.View.onStudio(fn: () -> Unit) {
    addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: android.view.View) { app.foxbox.Studio.listen(fn); fn() }
        override fun onViewDetachedFromWindow(v: android.view.View) { app.foxbox.Studio.unlisten(fn) }
    })
}
