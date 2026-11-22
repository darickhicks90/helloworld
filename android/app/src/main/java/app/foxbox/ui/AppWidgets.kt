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

/** Rotary knob bound to a [Param]: drag up/down, double-tap to reset; choices step on tap. */
@SuppressLint("ViewConstructor")
class Knob(c: Context, val param: Param, var accent: Int, classic: Boolean = false) : PixelView(c) {
    private var startY = 0f; private var startN = 0f; private var lastTap = 0L; private var moved = false
    private val listener: () -> Unit = { postInvalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); param.listeners.add(listener) }
    override fun onDetachedFromWindow() { param.listeners.remove(listener); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Px.dp(70), Px.dp(84))

    private val face = KnobFace(classic)
    override fun paint(c: Canvas) {
        // "Pad 3 Tune" -> "Tune": the pad editor already says which pad
        face.draw(c, width.toFloat(), param.norm, accent, param.text(), param.name.replace(Regex("^Pad \\d+ "), ""))
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


/** Run `fn` whenever Studio changes, while this view is attached. */
fun android.view.View.onStudio(fn: () -> Unit) {
    addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: android.view.View) { app.foxbox.Studio.listen(fn); fn() }
        override fun onViewDetachedFromWindow(v: android.view.View) { app.foxbox.Studio.unlisten(fn) }
    })
}


/** A block's icon (the little isometric ones from the valley). */
fun Sprites.icon(pageId: String) = when (pageId) {
    "gusts" -> Iso.iconCloud; "reflections" -> Iso.iconBoat; "leaves" -> Iso.iconTree; "minds" -> Iso.iconMushroom
    "bumble" -> Iso.iconHive; "pickup" -> Iso.iconTruck; else -> Iso.iconFlower
}
