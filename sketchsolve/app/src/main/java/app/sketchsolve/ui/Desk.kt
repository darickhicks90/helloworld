package app.sketchsolve.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/** Colours and sizes for the desk look. */
object Ink {
    const val PENCIL = 0xFF2B3040.toInt()
    const val KNOWN = 0xFF14864F.toInt()
    const val FIND = 0xFFC2255C.toInt()
    const val SELECT = 0xFFE8590C.toInt()
    const val GUIDE = 0xFF7F96BD.toInt()
    const val PAPER = 0xFFFBFBF4.toInt()
    const val GRID = 0x553F7FD0
    const val GRID_MAJOR = 0x884F86CF.toInt()
    const val PAD = 0xFFFFF5B5.toInt()
    const val PAD_LINE = 0x5568A0D8
    const val PAD_MARGIN = 0x99E05555.toInt()
    const val TEXT = 0xFF26262B.toInt()
    const val MUTED = 0xFF6B6658.toInt()
    const val WARN = 0xFFB35C00.toInt()

    val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    val serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    val hand: Typeface = Typeface.create("casual", Typeface.NORMAL)
    val handBold: Typeface = Typeface.create("casual", Typeface.BOLD)
}

fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
fun View.dp(v: Float) = context.dp(v)
fun View.dp(v: Int) = context.dp(v)

/** A warm wooden desk top, drawn once into a bitmap. */
class DeskDrawable(private val density: Float) : Drawable() {
    private var cache: Bitmap? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        var bmp = cache
        if (bmp == null || bmp.width != b.width() || bmp.height != b.height()) {
            bmp = render(b.width(), b.height())
            cache = bmp
        }
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
    }

    private fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF9A6538.toInt(), 0xFF84522C.toInt(), 0xFF915E34.toInt()), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), base)
        val rnd = Random(42)
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        // planks
        val plank = (h / 3.2f).coerceAtLeast(120 * density)
        var y = 0f
        while (y < h) {
            grain.color = 0x33000000; grain.strokeWidth = 1.5f * density
            c.drawLine(0f, y, w.toFloat(), y, grain)
            y += plank
        }
        // long wavy grain lines
        for (i in 0 until (h / (3 * density)).toInt()) {
            val gy = rnd.nextFloat() * h
            val amp = (2 + rnd.nextFloat() * 6) * density
            val freq = 0.002f + rnd.nextFloat() * 0.004f
            val phase = rnd.nextFloat() * 6f
            grain.strokeWidth = (0.6f + rnd.nextFloat() * 1.6f) * density
            grain.color = if (rnd.nextBoolean()) Color.argb(18 + rnd.nextInt(30), 60, 30, 10) else Color.argb(10 + rnd.nextInt(20), 255, 220, 170)
            val p = Path()
            p.moveTo(0f, gy)
            var x = 0f
            while (x <= w) { p.lineTo(x, gy + amp * sin(x * freq / density * 3 + phase)); x += 8 * density }
            c.drawPath(p, grain)
        }
        // a couple of knots
        repeat(3) {
            val kx = rnd.nextFloat() * w; val ky = rnd.nextFloat() * h
            for (r in 1..6) {
                grain.color = Color.argb(28, 50, 25, 8); grain.strokeWidth = 1.2f * density
                c.drawOval(kx - r * 7 * density, ky - r * 2.4f * density, kx + r * 7 * density, ky + r * 2.4f * density, grain)
            }
        }
        // soft vignette
        val vig = Paint().apply {
            shader = RadialGradient(w / 2f, h / 2f, max(w, h) * 0.75f, intArrayOf(0x00000000, 0x55000000), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vig)
        return bmp
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/** A yellow legal pad: ruled lines, a red margin and a glued top edge. */
class LegalPadDrawable(private val density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = RectF(bounds)
        val d = density
        // shadow
        p.color = 0x44000000; p.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(b.left + 3 * d, b.top + 5 * d, b.right + 2 * d, b.bottom + 4 * d), 4 * d, 4 * d, p)
        p.color = Ink.PAD
        canvas.drawRoundRect(b, 3 * d, 3 * d, p)
        // ruled lines
        p.color = Ink.PAD_LINE; p.strokeWidth = 1 * d
        var y = b.top + 46 * d
        while (y < b.bottom - 4 * d) { canvas.drawLine(b.left, y, b.right, y, p); y += 26 * d }
        p.color = Ink.PAD_MARGIN; p.strokeWidth = 1.2f * d
        canvas.drawLine(b.left + 22 * d, b.top + 18 * d, b.left + 22 * d, b.bottom, p)
        canvas.drawLine(b.left + 25 * d, b.top + 18 * d, b.left + 25 * d, b.bottom, p)
        // glued top
        p.shader = LinearGradient(0f, b.top, 0f, b.top + 18 * d, 0xFF9B3434.toInt(), 0xFF7A2424.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(b.left, b.top, b.right, b.top + 18 * d, p)
        p.shader = null
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A sticky note used as a tab. */
class StickyTab(ctx: Context, val label: String, val color: Int, private val tilt: Float) : View(ctx) {
    var selectedTab = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        tp.textSize = dp(15f)
        tp.typeface = Ink.handBold
        val w = (tp.measureText(label) + dp(34f)).roundToInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(dp(46), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val drop = if (selectedTab) 6 * d else 0f
        canvas.save()
        canvas.rotate(if (selectedTab) tilt * 0.3f else tilt, width / 2f, height / 2f)
        val r = RectF(4 * d, 4 * d + drop, width - 4 * d, height + 10 * d)
        p.color = 0x40000000
        canvas.drawRect(RectF(r.left + 2 * d, r.top + 3 * d, r.right + 2 * d, r.bottom), p)
        p.color = if (selectedTab) color else blend(color, 0xFFE9E2D0.toInt(), 0.35f)
        canvas.drawRect(r, p)
        // sticky strip shading
        p.color = 0x14000000
        canvas.drawRect(r.left, r.top, r.right, r.top + 8 * d, p)
        tp.color = if (selectedTab) Ink.TEXT else 0xCC3A3A3A.toInt()
        tp.typeface = if (selectedTab) Ink.handBold else Ink.hand
        canvas.drawText(label, width / 2f, r.top + 25 * d, tp)
        canvas.restore()
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).roundToInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).roundToInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).roundToInt())
}

/** A round wooden token on the tool tray with a drawn icon. */
class ToolToken(ctx: Context, val tool: String, val icon: String, val caption: String, val tint: Int) : View(ctx) {
    var active = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val compact get() = resources.configuration.screenHeightDp < 480

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(64), dp(if (compact) 50 else 60))
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val r = (if (compact) 16 else 19) * d
        val cx = width / 2f; val cy = r + 4 * d
        p.color = 0x55000000
        canvas.drawCircle(cx + 1.5f * d, cy + 2.5f * d, r, p)
        p.shader = RadialGradient(cx - r / 3, cy - r / 3, r * 1.4f, if (active) intArrayOf(0xFFFFFDF2.toInt(), 0xFFF1E6C8.toInt()) else intArrayOf(0xFFE9D3AE.toInt(), 0xFFC9A877.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null
        if (active) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = tint
            canvas.drawCircle(cx, cy, r + 1.5f * d, p)
            p.style = Paint.Style.FILL
        }
        if (tool == "erase") {
            // a pink rubber eraser with a blue sleeve
            canvas.save(); canvas.rotate(-30f, cx, cy)
            p.color = 0xFFF28BA8.toInt(); canvas.drawRoundRect(cx - 11 * d, cy - 6 * d, cx + 2 * d, cy + 6 * d, 2 * d, 2 * d, p)
            p.color = 0xFF3F6FB5.toInt(); canvas.drawRoundRect(cx + 1 * d, cy - 6 * d, cx + 11 * d, cy + 6 * d, 2 * d, 2 * d, p)
            canvas.restore()
        } else {
            tp.color = tint
            tp.textSize = (if (icon.length > 1) 15 else 20) * d
            tp.typeface = Ink.serifBold
            tp.isFakeBoldText = true
            val fm = tp.fontMetrics
            canvas.drawText(icon, cx, cy - (fm.ascent + fm.descent) / 2, tp)
            tp.isFakeBoldText = false
        }
        tp.color = 0xEEFFF6E4.toInt()
        tp.textSize = 11 * d
        tp.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(caption, cx, cy + r + 13 * d, tp)
    }
}
