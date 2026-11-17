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
    private val shine = intArrayOf(0xFFFFF4D6.toInt(), 0xFFFFD27A.toInt(), 0xFFF4A259.toInt(), 0xFFE8742C.toInt(), 0xFFB8521B.toInt())

    // ---- the title scene: a starlit clearing where the yeti kneels, turned away, digging through the grass
    //      (pixel art, played back as frames; he pays the viewer no mind)
    private fun img(name: String) = context.assets.open("images/$name").use { android.graphics.BitmapFactory.decodeStream(it) }
    private val bg = img("title_bg.png")
    private val fg = img("title_fg.png")
    private val sheet = img("yeti_frames.png")
    private val anim = org.json.JSONObject(context.assets.open("images/yeti_anim.json").use { String(it.readBytes()) })
    private val frames = anim.getInt("frames"); private val fw = anim.getInt("w"); private val fh = anim.getInt("h")
    private val fx = anim.getInt("x"); private val fy = anim.getInt("y")
    private val dig = anim.getJSONArray("dig").let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
    private val pixel = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val dot = Paint()
    private val src = android.graphics.Rect(); private val dst = android.graphics.RectF()
    private val t0 = System.nanoTime()
    /** Fireflies: centre, orbit radii, speed and phase, in scene pixels. */
    private val bugs = arrayOf(
        floatArrayOf(330f, 120f, 34f, 14f, 0.5f, 0f), floatArrayOf(420f, 90f, 26f, 18f, 0.6f, 1.3f), floatArrayOf(250f, 150f, 30f, 12f, 0.45f, 2.1f),
        floatArrayOf(180f, 110f, 40f, 14f, 0.5f, 0.4f), floatArrayOf(90f, 140f, 30f, 18f, 0.7f, 3.0f), floatArrayOf(460f, 150f, 14f, 20f, 0.55f, 4.2f),
        floatArrayOf(40f, 175f, 26f, 10f, 0.6f, 5.1f), floatArrayOf(300f, 60f, 22f, 12f, 0.4f, 2.7f),
    )
    private val DIRT = intArrayOf(0xFF3E2816.toInt(), 0xFF5A3A20.toInt(), 0xFF7A5230.toInt(), 0xFF4F8436.toInt(), 0xFF3C6E30.toInt())
    private fun hash(n: Int): Float { var x = n * 374761393 + 668265263; x = (x xor (x ushr 13)) * 1274126177; return ((x xor (x ushr 16)) and 0xFFFF) / 65535f }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = (System.nanoTime() - t0) / 1e9f
        // Fill the screen; keep the right edge (where the yeti is) and crop from the left or top.
        val k = maxOf(w / bg.width, h / bg.height)
        val sw = (w / k).toInt().coerceAtMost(bg.width); val sh = (h / k).toInt().coerceAtMost(bg.height)
        val left = bg.width - sw; val top = (bg.height - sh) / 2
        src.set(left, top, left + sw, top + sh); dst.set(0f, 0f, w, h)
        c.drawBitmap(bg, src, dst, pixel)
        fun sx(x: Float) = (x - left) * k
        fun sy(y: Float) = (y - top) * k
        // Dig, dig, dig, then a short rest looking at the hole: scoops of about 1.3 s.
        val period = 1.3f
        val cycle = (t / period).toInt(); val p = (t / period) % 1f
        val resting = cycle % 5 == 4
        val reach = if (resting) 0f else 0.5f - 0.5f * kotlin.math.cos(2 * Math.PI.toFloat() * p)
        val f = (reach * (frames - 1) + 0.5f).toInt().coerceIn(0, frames - 1)
        src.set(f * fw, 0, (f + 1) * fw, fh)
        dst.set(sx(fx.toFloat()), sy(fy.toFloat()), sx((fx + fw).toFloat()), sy((fy + fh).toFloat())); c.drawBitmap(sheet, src, dst, pixel)
        // Clods of earth and bits of grass fly back over his shoulder at the bottom of each scoop.
        for (back in 0..1) {
            val cc = cycle - back
            if (cc < 0 || cc % 5 == 4) continue
            val age = t - (cc + 0.55f) * period
            if (age < 0f || age > 1.1f) continue
            for (j in 0 until 7) {
                val r1 = hash(cc * 31 + j); val r2 = hash(cc * 57 + j * 7); val r3 = hash(cc * 13 + j * 3)
                val vx = 38f + 60f * r1; val vy = -70f - 55f * r2
                val x = dig[0] + (r3 - 0.5f) * 10f + vx * age; val y = dig[1] + vy * age + 160f * age * age
                if (y > dig[1] + 4f) continue
                dot.color = DIRT[(r1 * 4.99f).toInt()]
                val sz = if (j % 3 == 0) 2 else 1
                c.drawRect(sx(x.toInt().toFloat()), sy(y.toInt().toFloat()), sx(x.toInt().toFloat() + sz), sy(y.toInt().toFloat() + sz), dot)
            }
        }
        // tall grass in front of his knees
        src.set(left, top, left + sw, top + sh); dst.set(0f, 0f, w, h); c.drawBitmap(fg, src, dst, pixel)
        // fireflies drifting and pulsing around the clearing
        for (b in bugs) {
            val x = b[0] + b[2] * kotlin.math.sin(t * b[4] + b[5]); val y = b[1] + b[3] * kotlin.math.sin(t * b[4] * 1.7f + b[5] * 2f)
            val pulse = 0.55f + 0.45f * kotlin.math.sin(t * 3.1f + b[5] * 5f)
            val px = sx(x.toInt().toFloat()); val py = sy(y.toInt().toFloat())
            dot.color = Pal.alpha(0xFFE8FF8A.toInt(), 0.35f * pulse); c.drawRect(px - k, py - k, px + 2 * k, py + 2 * k, dot)
            dot.color = Pal.alpha(0xFFF6FF9A.toInt(), 0.6f + 0.4f * pulse); c.drawRect(px, py, px + k, py + k, dot)
        }
        headP.textAlign = Paint.Align.LEFT
        snesTitle(c, "5 EYE YETI", Px.dp(28f), Px.dp(64f))
        postInvalidateDelayed(40)
    }

    /** Super Nintendo-style title: banded metallic fill, thick ink outline and a drop shadow. */
    private fun snesTitle(c: Canvas, text: String, cx: Float, base: Float) {
        val p = headP
        p.shader = null; p.style = Paint.Style.FILL
        p.color = 0xFF120C0A.toInt()
        val o = Px.dp(3f)
        for (dx in -1..1) for (dy in -1..2) c.drawText(text, cx + dx * o, base + dy * o + Px.dp(3f), p)
        p.color = Pal.BROWN_D
        for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) c.drawText(text, cx + dx * o * 0.6f, base + dy * o * 0.6f, p)
        val top = base + p.ascent(); val bottom = base + p.descent() * 0.2f
        val n = shine.size
        val stops = FloatArray(n * 2); val cols = IntArray(n * 2)
        for (i in 0 until n) { stops[i * 2] = i / n.toFloat(); stops[i * 2 + 1] = (i + 1) / n.toFloat() - 0.001f; cols[i * 2] = shine[i]; cols[i * 2 + 1] = shine[i] }
        p.shader = android.graphics.LinearGradient(0f, top, 0f, bottom, cols, stops, android.graphics.Shader.TileMode.CLAMP)
        c.drawText(text, cx, base, p)
        p.shader = null
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

private val CARD_W = Px.dp(150)
private val CARD_H = Px.dp(128)
