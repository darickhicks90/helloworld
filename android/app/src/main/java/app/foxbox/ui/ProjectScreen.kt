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

    // ---- the title scene: a starlit clearing where the yeti reaches up to catch fireflies (pixel art, played back as frames)
    private fun img(name: String) = context.assets.open("images/$name").use { android.graphics.BitmapFactory.decodeStream(it) }
    private val bg = img("title_bg.png")
    private val sheet = img("yeti_frames.png")
    private val blink = img("yeti_blink.png")
    private val anim = org.json.JSONObject(context.assets.open("images/yeti_anim.json").use { String(it.readBytes()) })
    private val frames = anim.getInt("frames"); private val fw = anim.getInt("w"); private val fh = anim.getInt("h")
    private val fx = anim.getInt("x"); private val fy = anim.getInt("y")
    private val hands = anim.getJSONArray("hands").let { a -> Array(a.length()) { i -> a.getJSONArray(i).let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } } }
    private val pixel = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val glow = Paint()
    private val src = android.graphics.Rect(); private val dst = android.graphics.RectF()
    private val t0 = System.nanoTime()
    /** Fireflies: centre, orbit radii, speed and phase, in scene pixels. The first one is the one he reaches for. */
    private val bugs = arrayOf(
        floatArrayOf(388f, 34f, 22f, 10f, 0.9f, 0f), floatArrayOf(300f, 120f, 30f, 14f, 0.6f, 1.3f), floatArrayOf(440f, 110f, 18f, 22f, 0.7f, 2.1f),
        floatArrayOf(210f, 150f, 40f, 12f, 0.5f, 0.4f), floatArrayOf(120f, 130f, 30f, 18f, 0.8f, 3.0f), floatArrayOf(470f, 60f, 14f, 20f, 0.65f, 4.2f),
        floatArrayOf(60f, 165f, 26f, 10f, 0.75f, 5.1f),
    )

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = (System.nanoTime() - t0) / 1e9f
        // Fill the screen; keep the right edge (where the yeti is) and crop from the left or top.
        val k = maxOf(w / bg.width, h / bg.height)
        val sw = (w / k).toInt().coerceAtMost(bg.width); val sh = (h / k).toInt().coerceAtMost(bg.height)
        val left = bg.width - sw; val top = (bg.height - sh) / 2
        src.set(left, top, left + sw, top + sh); dst.set(0f, 0f, w, h)
        c.drawBitmap(bg, src, dst, pixel)
        fun at(sx: Float, sy: Float, bmp: android.graphics.Bitmap, sr: android.graphics.Rect) {
            dst.set((sx - left) * k, (sy - top) * k, (sx - left + sr.width()) * k, (sy - top + sr.height()) * k); c.drawBitmap(bmp, sr, dst, pixel)
        }
        // reach up, hold a moment with the firefly, come back down (about 4 s a cycle)
        val cyc = (t / 4.2f) % 1f
        val reach = (1.35f * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI.toFloat() * cyc))).coerceAtMost(1f)
        val f = (reach * (frames - 1) + 0.5f).toInt().coerceIn(0, frames - 1)
        src.set(f * fw, 0, (f + 1) * fw, fh); at(fx.toFloat(), fy.toFloat(), sheet, src)
        if ((t % 3.7f) < 0.14f) { src.set(0, 0, fw, fh); at(fx.toFloat(), fy.toFloat(), blink, src) }
        // fireflies: soft pulsing lights; the first drifts down into his raised hand
        for ((i, b) in bugs.withIndex()) {
            var x = b[0] + b[2] * kotlin.math.sin(t * b[4] + b[5]); var y = b[1] + b[3] * kotlin.math.sin(t * b[4] * 1.7f + b[5] * 2f)
            if (i == 0) { val hp = hands[f]; val g = reach * reach * reach; x += (hp[0] - x) * g; y += (hp[1] - 3f - y) * g }
            val pulse = 0.55f + 0.45f * kotlin.math.sin(t * 3.1f + b[5] * 5f)
            val px = ((x.toInt() - left) * k); val py = ((y.toInt() - top) * k)
            glow.color = Pal.alpha(0xFFE8FF8A.toInt(), 0.35f * pulse); c.drawRect(px - k, py - k, px + 2 * k, py + 2 * k, glow)
            glow.color = Pal.alpha(0xFFF6FF9A.toInt(), 0.6f + 0.4f * pulse); c.drawRect(px, py, px + k, py + k, glow)
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
