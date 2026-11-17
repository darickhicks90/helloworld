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

    // ---- the title scene: a starlit clearing where the yeti goes about his own business. He swipes at a
    //      firefly and misses twice, catches it, turns around, kneels, buries it, pats it down, gets up and
    //      turns back as another drifts in. Pixel art played back from title_story.json (12 steps a second).
    private fun img(name: String) = context.assets.open("images/$name").use { android.graphics.BitmapFactory.decodeStream(it) }
    private val bg = img("title_bg.png")
    private val fg = img("title_fg.png")
    private val sheet = img("yeti_frames.png")
    private val story = org.json.JSONObject(context.assets.open("images/title_story.json").use { String(it.readBytes()) })
    private val fps = story.getInt("fps")
    private val frameRects = story.getJSONArray("frames").let { a -> Array(a.length()) { i -> a.getJSONArray(i).let { r -> IntArray(6) { r.getInt(it) } } } }
    private val steps = story.getJSONArray("steps").let { a -> IntArray(a.length()) { a.getInt(it) } }
    private val fly = story.getJSONArray("fly").let { a -> Array(a.length()) { i -> a.getJSONArray(i).let { r -> floatArrayOf(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat()) } } }
    private val hole = story.getJSONArray("hole").let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
    private val tPlace = story.getDouble("place").toFloat()
    private val scoops = story.getJSONArray("scoops").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
    private val duration = story.getDouble("duration").toFloat()
    private val pixel = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val dot = Paint()
    private val src = android.graphics.Rect(); private val dst = android.graphics.RectF()
    private val t0 = System.nanoTime()
    /** Other fireflies: centre, orbit radii, speed and phase, in scene pixels. */
    private val bugs = arrayOf(
        floatArrayOf(250f, 150f, 30f, 12f, 0.45f, 2.1f), floatArrayOf(180f, 110f, 40f, 14f, 0.5f, 0.4f), floatArrayOf(90f, 140f, 30f, 18f, 0.7f, 3.0f),
        floatArrayOf(460f, 150f, 14f, 20f, 0.55f, 4.2f), floatArrayOf(40f, 175f, 26f, 10f, 0.6f, 5.1f), floatArrayOf(440f, 70f, 18f, 12f, 0.4f, 2.7f),
    )
    private val DIRT = intArrayOf(0xFF3E2816.toInt(), 0xFF5A3A20.toInt(), 0xFF7A5230.toInt(), 0xFF4F8436.toInt())
    private fun hash(n: Int): Float { var x = n * 374761393 + 668265263; x = (x xor (x ushr 13)) * 1274126177; return ((x xor (x ushr 16)) and 0xFFFF) / 65535f }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val clock = (System.nanoTime() - t0) / 1e9f
        val t = clock % duration
        val step = (t * fps).toInt().coerceIn(0, steps.size - 1)
        // Fill the screen; keep the right edge (where the yeti is) and crop from the left or top.
        val k = maxOf(w / bg.width, h / bg.height)
        val sw = (w / k).toInt().coerceAtMost(bg.width); val sh = (h / k).toInt().coerceAtMost(bg.height)
        val left = bg.width - sw; val top = (bg.height - sh) / 2
        src.set(left, top, left + sw, top + sh); dst.set(0f, 0f, w, h)
        c.drawBitmap(bg, src, dst, pixel)
        fun sx(x: Float) = (x - left) * k
        fun sy(y: Float) = (y - top) * k
        fun px(x: Int, y: Int, col: Int, size: Int = 1) { dot.color = col; c.drawRect(sx(x.toFloat()), sy(y.toFloat()), sx((x + size).toFloat()), sy((y + size).toFloat()), dot) }
        // the little grave: a dark hole once he lays the firefly in, then a mound that grows with each scoop
        if (t >= tPlace) {
            val hx = hole[0].toInt(); val hy = hole[1].toInt()
            val filled = scoops.count { t >= it }
            if (filled == 0) { for (x in -3..3) px(hx + x, hy, DIRT[0]); for (x in -2..2) px(hx + x, hy - 1, DIRT[0]) }
            else for (row in 0 until 1 + filled) { val half = 5 - row; for (x in -half..half) px(hx + x, hy - row, DIRT[if ((x + row) % 3 == 0) 2 else 1]) }
            val glow = fly[step][2]
            if (glow > 0.02f) { dot.color = Pal.alpha(0xFFE8FF8A.toInt(), 0.45f * glow); c.drawRect(sx(hx - 2f), sy(hy - 2f - filled), sx(hx + 3f), sy(hy + 1f), dot) }
        }
        // the yeti
        val fr = frameRects[steps[step]]
        src.set(fr[0], fr[1], fr[0] + fr[2], fr[1] + fr[3])
        dst.set(sx(fr[4].toFloat()), sy(fr[5].toFloat()), sx((fr[4] + fr[2]).toFloat()), sy((fr[5] + fr[3]).toFloat()))
        c.drawBitmap(sheet, src, dst, pixel)
        // earth he pulls over it, flying in little arcs
        for ((si, s0) in scoops.withIndex()) {
            val age = t - (s0 - 0.35f)
            if (age < 0f || age > 0.6f) continue
            for (j in 0 until 6) {
                val r1 = hash(si * 31 + j); val r2 = hash(si * 57 + j * 7)
                val x = hole[0] + 16f + 10f * r1 - (24f + 14f * r2) * age / 0.6f
                val y = hole[1] - 2f - 18f * r2 * kotlin.math.sin(Math.PI.toFloat() * age / 0.6f)
                px(x.toInt(), y.toInt(), DIRT[(r1 * 3.99f).toInt()], if (j % 3 == 0) 2 else 1)
            }
        }
        // the firefly he's after (in his hand once caught)
        val f = fly[step]
        if (t < tPlace && f[2] > 0.02f) {
            val pulse = 0.6f + 0.4f * kotlin.math.sin(clock * 6f)
            dot.color = Pal.alpha(0xFFE8FF8A.toInt(), 0.4f * pulse * f[2]); c.drawRect(sx(f[0] - 1), sy(f[1] - 1), sx(f[0] + 2), sy(f[1] + 2), dot)
            dot.color = Pal.alpha(0xFFF6FF9A.toInt(), f[2]); c.drawRect(sx(f[0].toInt().toFloat()), sy(f[1].toInt().toFloat()), sx(f[0].toInt() + 1f), sy(f[1].toInt() + 1f), dot)
        } else if (t >= tPlace && step >= 0 && f[2] > 0.3f && f[1] < hole[1] - 3) {
            dot.color = Pal.alpha(0xFFF6FF9A.toInt(), f[2]); c.drawRect(sx(f[0].toInt().toFloat()), sy(f[1].toInt().toFloat()), sx(f[0].toInt() + 1f), sy(f[1].toInt() + 1f), dot)
        }
        // tall grass in front
        src.set(left, top, left + sw, top + sh); dst.set(0f, 0f, w, h); c.drawBitmap(fg, src, dst, pixel)
        // the other fireflies drifting around the clearing
        for (b in bugs) {
            val x = b[0] + b[2] * kotlin.math.sin(clock * b[4] + b[5]); val y = b[1] + b[3] * kotlin.math.sin(clock * b[4] * 1.7f + b[5] * 2f)
            val pulse = 0.55f + 0.45f * kotlin.math.sin(clock * 3.1f + b[5] * 5f)
            val qx = sx(x.toInt().toFloat()); val qy = sy(y.toInt().toFloat())
            dot.color = Pal.alpha(0xFFE8FF8A.toInt(), 0.35f * pulse); c.drawRect(qx - k, qy - k, qx + 2 * k, qy + 2 * k, dot)
            dot.color = Pal.alpha(0xFFF6FF9A.toInt(), 0.6f + 0.4f * pulse); c.drawRect(qx, qy, qx + k, qy + k, dot)
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
