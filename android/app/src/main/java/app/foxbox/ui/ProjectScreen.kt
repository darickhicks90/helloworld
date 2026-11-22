package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import app.foxbox.data.Project
import app.foxbox.data.ProjectStore
import app.foxbox.seq.Theory
import java.text.DateFormat
import java.util.Date

/** First screen: pick a project or start a new one. */
@SuppressLint("ViewConstructor")
class ProjectScreen(c: Context, private val onOpen: (Project) -> Unit, private val onNew: () -> Unit, private val onManage: (Project) -> Unit) : FrameLayout(c) {
    private val titleP = Px.text(26f, Pal.ORANGE, headFont = true)
    private val tagP = Px.text(14f, Pal.AQUA_D)

    init {
        setWillNotDraw(false)
        // Neat, evenly spaced cards a quarter smaller than before, in one scrolling row.
        val cards = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Px.dp(24), 0, Px.dp(24), 0) }
        cards.addView(Card(c, null), lp(CARD_W, CARD_H).apply { rightMargin = Px.dp(10) })
        ProjectStore.list().forEach { cards.addView(Card(c, it), lp(CARD_W, CARD_H).apply { rightMargin = Px.dp(10) }) }
        val scroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(cards) }
        // The cards sit over the meadow on the left; the yeti stands on the right.
        addView(scroll, LayoutParams(MATCH, CARD_H + Px.dp(8), android.view.Gravity.BOTTOM or android.view.Gravity.START).apply { bottomMargin = Px.dp(24) })
        post { (scroll.layoutParams as LayoutParams).width = (width * 0.62f).toInt(); scroll.requestLayout() }
    }

    private val headP = Px.text(34f, Pal.ORANGE, headFont = true)

    // ---- the title scene: the little guy from the icon standing in a field at night, mountains behind
    //      him and the sky full of stars (no moon). Clean mint lines on night greens, drawn by
    //      art/title/title_scene.py; here he breathes, the bright stars twinkle and now and then one falls.
    private fun img(name: String) = context.assets.open("images/$name").use { android.graphics.BitmapFactory.decodeStream(it) }
    private val scene = img("title_scene.webp")
    private val yeti = img("title_yeti.png")
    private val meta = org.json.JSONObject(context.assets.open("images/title_scene.json").use { String(it.readBytes()) })
    private val yetiX = meta.getDouble("yetiX").toFloat()
    private val yetiFeet = meta.getDouble("yetiFeet").toFloat()
    private val twinkles = meta.getJSONArray("twinkles").let { a -> Array(a.length()) { i -> a.getJSONArray(i).let { r -> floatArrayOf(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat()) } } }
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = MINT }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = android.graphics.Rect(); private val dst = android.graphics.RectF()
    private val t0 = System.nanoTime()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = (System.nanoTime() - t0) / 1e9f
        // Fill the screen; keep the right edge (where he stands) and crop from the left, and evenly top and bottom.
        val k = maxOf(w / scene.width, h / scene.height)
        val sw = (w / k).toInt().coerceAtMost(scene.width); val sh = (h / k).toInt().coerceAtMost(scene.height)
        val left = scene.width - sw; val top = (scene.height - sh) / 2
        src.set(left, top, left + sw, top + sh); dst.set(0f, 0f, w, h)
        c.drawBitmap(scene, src, dst, smooth)
        fun sx(x: Float) = (x - left) * k
        fun sy(y: Float) = (y - top) * k
        // the bright stars twinkle, each in its own time
        for ((i, s) in twinkles.withIndex()) {
            val p = 0.5f + 0.5f * kotlin.math.sin(t * (0.9f + 0.23f * (i % 5)) + i * 2.1f)
            if (p < 0.55f) continue
            val a = (p - 0.55f) / 0.45f
            val x = sx(s[0]); val y = sy(s[1]); val r = s[2] * k * (1.2f + 0.9f * a)
            glow.shader = android.graphics.RadialGradient(x, y, r * 1.4f, Pal.alpha(MINT, 0.35f * a), 0, android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r * 1.4f, glow)
            line.strokeWidth = maxOf(1f, s[2] * k * 0.14f); line.alpha = (200 * a).toInt()
            c.drawLine(x, y - r, x, y + r, line); c.drawLine(x - r * 0.8f, y, x + r * 0.8f, y, line)
        }
        glow.shader = null
        // now and then a star falls: a thin line that thickens and fades
        val period = 11f; val ph = t % period; val n = (t / period).toInt()
        if (ph < 0.9f) {
            val f = ph / 0.9f
            val x0 = 300f + (n * 733 % 1500); val y0 = 60f + (n * 211 % 200)
            val hx = x0 + 420f * f; val hy = y0 + 150f * f
            val tx = x0 + 420f * maxOf(0f, f - 0.3f); val ty = y0 + 150f * maxOf(0f, f - 0.3f)
            line.strokeWidth = 2.2f * k; line.alpha = (230 * (1f - f)).toInt()
            c.drawLine(sx(tx), sy(ty), sx(hx), sy(hy), line)
        }
        line.alpha = 255
        // the yeti, breathing slowly (his feet stay put)
        val breathe = 1f + 0.014f * kotlin.math.sin(t * 1.7f)
        val yw = yeti.width * k * (2f - breathe); val yh = yeti.height * k * breathe
        val fx = sx(yetiX); val fy = sy(yetiFeet)
        dst.set(fx - yw / 2, fy - yh, fx + yw / 2, fy)
        c.drawBitmap(yeti, null, dst, smooth)
        title(c, "5 EYE YETI", Px.dp(28f), Px.dp(64f))
        postInvalidateDelayed(33)
    }

    /** The name in mint, with a soft dark shadow (to match the scene's lines). */
    private fun title(c: Canvas, text: String, x: Float, base: Float) {
        val p = headP
        p.textAlign = Paint.Align.LEFT; p.shader = null; p.style = Paint.Style.FILL
        p.color = 0xCC0C1610.toInt()
        val o = Px.dp(2f)
        for (dx in -1..1) for (dy in -1..2) c.drawText(text, x + dx * o, base + dy * o + o, p)
        p.color = MINT
        c.drawText(text, x, base, p)
    }

    @SuppressLint("ViewConstructor")
    private inner class Card(c: Context, val project: Project?) : PixelView(c) {
        private val nameP = Px.text(13f, Pal.BROWN_D, align = Paint.Align.LEFT)
        private val infoP = Px.text(10f, Pal.BROWN, align = Paint.Align.LEFT)
        private var pressed = false
        private val long = Runnable { project?.let(onManage); pressed = false; invalidate() }
        override fun paint(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.pixelBox(u, u, w - u, h - u, if (pressed) Pal.PAPER else if (project == null) Pal.ORANGE else Pal.CREAM)
            if (project == null) {
                c.textCentered("+", w / 2, h / 2 - Px.dp(12f), Px.text(26f, Pal.CREAM, headFont = true))
                c.textCentered("New project", w / 2, h / 2 + Px.dp(24f), Px.text(12f, Pal.CREAM))
                return
            }
            val clips = project.clips
                        val m = Px.dp(10f)
            // block icons in a tidy row, evenly spaced across the top
            val n = project.blocks.size.coerceAtLeast(1)
            val slot = (w - m * 2) / maxOf(n, 6)
            project.blocks.forEachIndexed { i, id -> val ic = Sprites.icon(id); val sc = minOf(slot * 0.8f / ic.w, Px.u * 0.6f); c.sprite(ic, m + i * slot + (slot - ic.w * sc) / 2, m, sc) }
            c.fill(m, Px.dp(36f), w - m, Px.dp(37f), Pal.alpha(Pal.BROWN, 0.35f))
            var name = project.name
            while (nameP.measureText(name) > w - m * 2 && name.length > 3) name = name.dropLast(2) + "…"
            c.drawText(name, m, Px.dp(54f), nameP)
            c.drawText("${project.bpm.toInt()} BPM · ${Theory.NOTE_NAMES[project.root]} ${Theory.SCALES[project.scale].name}", m, Px.dp(70f), infoP)
            c.drawText("${clips.size} clips · ${project.audioClips.size} takes", m, Px.dp(84f), infoP)
            c.drawText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.modified)), m, h - m, infoP)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = true; if (project != null) postDelayed(long, 500); invalidate() }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.x - width / 2) > width) { pressed = false; removeCallbacks(long) }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(long)
                    if (pressed) { if (project == null) onNew() else onOpen(project) }
                    pressed = false; invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(long); pressed = false; invalidate() }
            }
            return true
        }
    }
}

private const val MINT = 0xFFDEF2D4.toInt()
private val CARD_W = Px.dp(150)
private val CARD_H = Px.dp(128)
