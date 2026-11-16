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
        addView(scroll, LayoutParams(MATCH, CARD_H + Px.dp(8), android.view.Gravity.BOTTOM).apply { bottomMargin = Px.dp(36) })
    }

    private val headP = Px.text(34f, Pal.ORANGE, headFont = true)
    private val shine = intArrayOf(0xFFFFF4D6.toInt(), 0xFFFFD27A.toInt(), 0xFFF4A259.toInt(), 0xFFE8742C.toInt(), 0xFFB8521B.toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.bands(0f, 0f, w, h, intArrayOf(0xFF22333B.toInt(), 0xFF27404A.toInt(), 0xFF2D4D57.toInt(), 0xFF335A63.toInt(), 0xFF3A6770.toInt()))
        // a field of dim pixel stars
        val rnd = kotlin.random.Random(5)
        repeat(70) { c.dot(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.55f, Pal.alpha(Pal.CREAM, 0.25f + rnd.nextFloat() * 0.4f)) }
        val cx = w / 2
        snesTitle(c, "FOXBOX", cx, Px.dp(62f))
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
