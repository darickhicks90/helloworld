package app.foxbox.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The 16-bit console look for menus, dialogs and parameter panels: two-tone
 * grey plastic with a bevel, and red, yellow, green and purple in the details,
 * the text and the knobs.
 */
object Snes {
    const val HI = 0xFFEDEDF2.toInt()      // bevel highlight
    const val FACE = 0xFFCACAD2.toInt()    // the plastic
    const val FACE_D = 0xFFB3B3BE.toInt()  // the darker plate
    const val SHADE = 0xFF8E8E9C.toInt()   // bevel shadow
    const val DEEP = 0xFF5E5E6E.toInt()
    const val INK = 0xFF26242F.toInt()     // outlines and text
    const val RED = 0xFFD8403C.toInt()
    const val YELLOW = 0xFFEDB824.toInt()
    const val GREEN = 0xFF36A24C.toInt()
    const val PURPLE = 0xFF6448B4.toInt()
    const val PURPLE_D = 0xFF41307A.toInt()
    val ACCENTS = intArrayOf(RED, YELLOW, GREEN, PURPLE)
    /** The detail colour for the k-th panel, row or knob. */
    fun accent(k: Int) = ACCENTS[Math.floorMod(k, 4)]

    /** A raised grey plate with a 16-bit bevel (light top-left, dark bottom-right) and an ink outline. */
    fun Canvas.plate(l: Float, t: Float, r: Float, b: Float, face: Int = FACE) {
        val u = Px.u
        fill(l + u, t, r - u, b, INK); fill(l, t + u, r, b - u, INK)
        fill(l + u, t + u, r - u, b - u, SHADE)
        fill(l + u, t + u, r - u * 2, b - u * 2, HI)
        fill(l + u * 2, t + u * 2, r - u * 2, b - u * 2, face)
    }

    /** The row of four coloured lamps that sits in plate title bars. */
    fun Canvas.lamps(x: Float, cy: Float, size: Float) {
        for (k in 0 until 4) {
            val l = x + k * size * 1.6f
            fill(l, cy - size / 2, l + size, cy + size / 2, INK)
            fill(l + Px.u * 0.5f, cy - size / 2 + Px.u * 0.5f, l + size - Px.u * 0.5f, cy + size / 2 - Px.u * 0.5f, ACCENTS[k])
        }
    }
}

/** A grey bevelled frame with a title bar: the body of every dialog and parameter panel. */
@SuppressLint("ViewConstructor")
class SnesFrame(c: Context, private val title: String, private val accent: Int = Snes.PURPLE) : LinearLayout(c) {
    private val tp = Px.text(10f, Snes.INK, headFont = true, align = Paint.Align.LEFT)
    private val barH get() = if (title.isEmpty()) 0f else Px.dp(30f)
    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        val p = Px.dp(10)
        setPadding(p, p + barH.toInt(), p, p)
    }
    override fun onDraw(c: Canvas) {
        with(Snes) {
            c.plate(0f, 0f, width.toFloat(), height.toFloat())
            if (title.isNotEmpty()) {
                val u = Px.u
                c.fill(u * 3, u * 3, width - u * 3, barH, Snes.FACE_D)
                c.fill(u * 3, barH, width - u * 3, barH + u, accent)
                c.lamps(u * 6, (u * 3 + barH) / 2, Px.dp(7f))
                tp.textSize = Px.dp(10f)
                c.drawText(fitText(title, width - Px.dp(70f), tp, 7f), Px.dp(58f), (u * 3 + barH) / 2 - (tp.descent() + tp.ascent()) / 2, tp)
            }
        }
    }
}

/** One menu row: a grey key with a coloured lamp; checked rows get a purple marker. */
@SuppressLint("ViewConstructor")
class SnesItem(c: Context, var text: String, private val lamp: Int, private val checked: Boolean = false) : PixelView(c) {
    var onClick: (() -> Unit)? = null
    private var pressed = false
    private val tp = Px.text(15f, Snes.INK, align = Paint.Align.LEFT)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(Px.dp(240), w), Px.dp(44))
    override fun paint(c: Canvas) {
        with(Snes) {
            val w = width.toFloat(); val h = height.toFloat()
            c.plate(0f, u, w, h - u, if (pressed) Snes.HI else if (checked) Snes.FACE_D else Snes.FACE)
            val s = Px.dp(10f); val lx = Px.dp(12f)
            c.fill(lx, h / 2 - s / 2, lx + s, h / 2 + s / 2, Snes.INK)
            c.fill(lx + u * 0.6f, h / 2 - s / 2 + u * 0.6f, lx + s - u * 0.6f, h / 2 + s / 2 - u * 0.6f, lamp)
            tp.textSize = Px.dp(15f)
            tp.color = if (checked) Snes.PURPLE_D else Snes.INK
            val right = if (checked) Px.dp(34f) else Px.dp(10f)
            c.drawText(fitText(text, w - Px.dp(34f) - right, tp, 10f), Px.dp(32f), h / 2 - (tp.descent() + tp.ascent()) / 2, tp)
            if (checked) { val cx = w - Px.dp(20f); c.fill(cx - u * 2, h / 2 - u * 2, cx + u * 2, h / 2 + u * 2, Snes.PURPLE); c.fill(cx - u, h / 2 - u, cx, h / 2, Snes.HI) }
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = true; invalidate() }
            MotionEvent.ACTION_MOVE -> if (e.y < 0 || e.y > height) { pressed = false; invalidate() }
            MotionEvent.ACTION_UP -> { if (pressed) onClick?.invoke(); pressed = false; invalidate() }
            MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
        }
        return true
    }
}

/** A dialog button: a coloured key on the grey plate. */
@SuppressLint("ViewConstructor")
class SnesKey(c: Context, var text: String, private val color: Int) : PixelView(c) {
    var onClick: (() -> Unit)? = null
    private var pressed = false
    private val tp = Px.text(14f, Snes.INK)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize((tp.measureText(text) + Px.dp(30f)).toInt(), w), Px.dp(42))
    override fun paint(c: Canvas) {
        with(Snes) {
            val w = width.toFloat(); val h = height.toFloat()
            val dy = if (pressed) u else 0f
            c.fill(u * 2, u * 2, w, h, Snes.SHADE)
            c.fill(dy + u, dy, w - u + dy - u, h - u + dy - u, Snes.INK); c.fill(dy, dy + u, w - u + dy, h - u * 2 + dy, Snes.INK)
            c.fill(dy + u, dy + u, w - u * 2 + dy, h - u * 2 + dy, color)
            c.fill(dy + u * 2, dy + u, w - u * 3 + dy, dy + u * 2, Pal.alpha(Color.WHITE, 0.45f))
            tp.color = textOn(color); tp.textSize = Px.dp(14f)
            c.textFit(text, w / 2 + dy - u, h / 2 + dy - u, w - Px.dp(12f), tp)
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = true; invalidate() }
            MotionEvent.ACTION_UP -> { if (pressed && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) onClick?.invoke(); pressed = false; invalidate() }
            MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
        }
        return true
    }
}

/**
 * Dialogs in the grey console style. Every menu, list, question and text box
 * in the app goes through here.
 */
class SnesDialog(private val a: Activity, title: String, private val wide: Float = 0.62f) {
    val dialog = Dialog(a)
    val frame = SnesFrame(a, title)
    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val keys = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    private var msgView: TextView? = null

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        frame.addView(ScrollView(a).apply { addView(body); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Px.dp(6) })
        dialog.setContentView(frame)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.55f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    fun message(text: String): SnesDialog {
        val tv = msgView ?: TextView(a).apply {
            typeface = Px.body; setTextColor(Snes.INK); textSize = 15f
            setPadding(Px.dp(6), Px.dp(4), Px.dp(6), Px.dp(8))
        }.also { msgView = it; body.addView(it, 0) }
        tv.text = text
        return this
    }

    fun item(text: String, lamp: Int, checked: Boolean = false, onClick: () -> Unit): SnesDialog {
        body.addView(SnesItem(a, text, lamp, checked).apply { this.onClick = { dialog.dismiss(); onClick() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Px.dp(44)).apply { bottomMargin = Px.dp(2) })
        return this
    }

    fun view(v: View): SnesDialog { body.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return this }

    /** A key along the bottom. `then` null just closes the dialog. */
    fun key(text: String, color: Int, close: Boolean = true, then: (() -> Unit)?): SnesDialog {
        keys.addView(SnesKey(a, text, color).apply { onClick = { if (close) dialog.dismiss(); then?.invoke() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Px.dp(42)).apply { leftMargin = Px.dp(8) })
        return this
    }

    fun cancelable(on: Boolean): SnesDialog { dialog.setCancelable(on); return this }
    fun onDismiss(fn: () -> Unit): SnesDialog { dialog.setOnDismissListener { fn() }; return this }

    fun show(): SnesDialog {
        dialog.show()
        val dm = a.resources.displayMetrics
        dialog.window?.setLayout(minOf((dm.widthPixels * wide).toInt(), Px.dp(560)).coerceAtLeast(minOf(dm.widthPixels - Px.dp(16), Px.dp(300))), ViewGroup.LayoutParams.WRAP_CONTENT)
        // keep the app full screen while a dialog is up
        @Suppress("DEPRECATION")
        dialog.window?.decorView?.systemUiVisibility = a.window.decorView.systemUiVisibility
        frame.post {
            val maxH = (dm.heightPixels * 0.92f).toInt()
            if (frame.height > maxH) dialog.window?.setLayout(dialog.window!!.attributes.width, maxH)
        }
        return this
    }

    fun dismiss() = dialog.dismiss()
    val isShowing get() = dialog.isShowing

    companion object {
        /** A list to pick from. With `checked` >= 0 the current choice is marked. */
        fun choose(a: Activity, title: String, items: List<String>, checked: Int = -1, pick: (Int) -> Unit): SnesDialog {
            val d = SnesDialog(a, title)
            items.forEachIndexed { i, s -> d.item(s, Snes.accent(i), i == checked) { pick(i) } }
            return d.key("Close", Snes.RED, then = null).show()
        }

        /** A text (or number) box. */
        fun text(a: Activity, title: String, initial: String, numeric: Boolean = false, done: (String) -> Unit): SnesDialog {
            val input = EditText(a).apply {
                setText(initial); setSelection(initial.length)
                typeface = Px.body; textSize = 18f; setTextColor(Snes.INK)
                setBackgroundColor(Snes.HI); setPadding(Px.dp(10), Px.dp(8), Px.dp(10), Px.dp(8))
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }
            val box = FrameLayout(a).apply { setPadding(Px.dp(2), Px.dp(2), Px.dp(2), Px.dp(2)); setBackgroundColor(Snes.INK); addView(input) }
            return SnesDialog(a, title).view(box)
                .key("Cancel", Snes.RED, then = null)
                .key("OK", Snes.GREEN) { done(input.text.toString()) }.show()
        }

        /** A message with keys: pairs of label and action (null action = just close). The last key is the main one. */
        fun ask(a: Activity, title: String, msg: String, vararg keys: Pair<String, (() -> Unit)?>): SnesDialog {
            val d = SnesDialog(a, title).message(msg)
            val colors = intArrayOf(Snes.RED, Snes.YELLOW, Snes.PURPLE)
            keys.forEachIndexed { i, (label, fn) -> d.key(label, if (i == keys.size - 1) Snes.GREEN else colors[i % colors.size], then = fn) }
            return d.show()
        }
    }
}

/**
 * The premium knob: a machined grey cap with knurled edge and a coloured
 * pointer, sitting in a dark well ringed by lamps, with the value in a little
 * backlit window below and the name under that.
 */
class KnobFace(classic: Boolean = false) {
    // the console grey by default; `classic` uses the app's original cream, browns and orange
    private val cHi = if (classic) Pal.CREAM else Snes.HI
    private val cFace = if (classic) Pal.PAPER else Snes.FACE
    private val cFaceD = if (classic) 0xFFD9C29A.toInt() else Snes.FACE_D
    private val cShade = if (classic) Pal.BROWN_L else Snes.SHADE
    private val cDeep = if (classic) Pal.BROWN else Snes.DEEP
    private val cInk = if (classic) Pal.BROWN_D else Snes.INK
    private val cWell = if (classic) 0xFF4A2C18.toInt() else 0xFF3A3846.toInt()
    private val cOff = if (classic) 0xFF6E4A30.toInt() else 0xFF56535F.toInt()
    private val cWindow = if (classic) 0xFF3D2414.toInt() else 0xFF2E2C3A.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Px.text(11f, cInk)
    private val value = Px.text(11f, Snes.HI, headFont = false)

    fun draw(c: Canvas, w: Float, norm: Float, accent: Int, valueText: String, name: String) {
        val cx = w / 2f; val cy = Px.dp(27f)
        val ring = Px.dp(21f); val capR = Px.dp(14f)
        // the well
        p.shader = null; p.style = Paint.Style.FILL
        p.color = cShade; c.drawCircle(cx + Px.dp(1f), cy + Px.dp(1.5f), ring + Px.dp(3f), p)
        p.color = cInk; c.drawCircle(cx, cy, ring + Px.dp(2f), p)
        p.color = cWell; c.drawCircle(cx, cy, ring, p)
        // lamps around the arc
        val segs = 13
        val lit = (norm * (segs - 1) + 0.5f).toInt()
        for (i in 0 until segs) {
            val a = Math.toRadians(-225.0 + 270.0 * i / (segs - 1))
            val on = i <= lit && (norm > 0.001f || i == 0)
            p.color = if (on) accent else cOff
            val r = ring - Px.dp(3.5f)
            c.drawCircle(cx + (Math.cos(a) * r).toFloat(), cy + (Math.sin(a) * r).toFloat(), Px.dp(if (on) 2f else 1.5f), p)
        }
        // cap: drop shadow, knurled rim, brushed top with a soft highlight
        p.color = 0x66000000; c.drawCircle(cx + Px.dp(1.5f), cy + Px.dp(2f), capR, p)
        p.shader = LinearGradient(cx - capR, cy - capR, cx + capR, cy + capR, cHi, cShade, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, capR, p)
        p.shader = null; p.color = cDeep; p.strokeWidth = Px.dp(1f)
        for (k in 0 until 24) {
            val a = Math.PI * 2 * k / 24
            c.drawLine(cx + (Math.cos(a) * (capR - Px.dp(2f))).toFloat(), cy + (Math.sin(a) * (capR - Px.dp(2f))).toFloat(),
                cx + (Math.cos(a) * capR).toFloat(), cy + (Math.sin(a) * capR).toFloat(), p)
        }
        p.shader = RadialGradient(cx - capR * 0.35f, cy - capR * 0.4f, capR * 1.2f, intArrayOf(0xFFFFFFFF.toInt(), cFace, cFaceD), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, capR - Px.dp(2.5f), p)
        p.shader = null
        // pointer
        val a = Math.toRadians(-225.0 + 270.0 * norm)
        p.color = cInk; p.strokeWidth = Px.dp(4f); p.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx + (Math.cos(a) * capR * 0.2f).toFloat(), cy + (Math.sin(a) * capR * 0.2f).toFloat(), cx + (Math.cos(a) * (capR - Px.dp(3.5f))).toFloat(), cy + (Math.sin(a) * (capR - Px.dp(3.5f))).toFloat(), p)
        p.color = accent; p.strokeWidth = Px.dp(2.2f)
        c.drawLine(cx + (Math.cos(a) * capR * 0.25f).toFloat(), cy + (Math.sin(a) * capR * 0.25f).toFloat(), cx + (Math.cos(a) * (capR - Px.dp(4f))).toFloat(), cy + (Math.sin(a) * (capR - Px.dp(4f))).toFloat(), p)
        // value window
        val vw = w - Px.dp(8f); val vt = Px.dp(52f); val vb = Px.dp(66f)
        c.fill(cx - vw / 2, vt, cx + vw / 2, vb, cInk)
        c.fill(cx - vw / 2 + Px.dp(1f), vt + Px.dp(1f), cx + vw / 2 - Px.dp(1f), vb - Px.dp(1f), cWindow)
        value.color = brighten(accent); value.textSize = Px.dp(11f)
        c.textFit(valueText, cx, (vt + vb) / 2, vw - Px.dp(6f), value, 7f)
        // name, on two lines if that reads better
        label.textSize = Px.dp(11f)
        val words = name.split(' ')
        if (label.measureText(name) <= w - Px.dp(4f) || words.size < 2) c.textFit(name, cx, Px.dp(76f), w - Px.dp(4f), label)
        else {
            val half = (1 until words.size).minByOrNull { k -> kotlin.math.abs(label.measureText(words.take(k).joinToString(" ")) - label.measureText(words.drop(k).joinToString(" "))) } ?: 1
            label.textSize = Px.dp(10f)
            c.textFit(words.take(half).joinToString(" "), cx, Px.dp(73f), w - Px.dp(4f), label)
            c.textFit(words.drop(half).joinToString(" "), cx, Px.dp(82f), w - Px.dp(4f), label)
        }
    }

    /** Lamp colours read better a touch brighter on the dark value window. */
    private fun brighten(c: Int): Int {
        val r = (c shr 16 and 255); val g = (c shr 8 and 255); val b = (c and 255)
        fun up(v: Int) = (v + (255 - v) * 0.35f).toInt()
        return Color.rgb(up(r), up(g), up(b))
    }
}
