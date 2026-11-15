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
        val cards = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Px.dp(24), 0, Px.dp(24), 0) }
        cards.addView(Card(c, null), lp(Px.dp(200), Px.dp(170)).apply { rightMargin = Px.dp(14) })
        ProjectStore.list().forEach { cards.addView(Card(c, it), lp(Px.dp(200), Px.dp(170)).apply { rightMargin = Px.dp(14) }) }
        val scroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(cards) }
        addView(scroll, LayoutParams(MATCH, Px.dp(190), android.view.Gravity.BOTTOM).apply { bottomMargin = Px.dp(28) })
    }

    private val headP = Px.text(34f, Pal.ORANGE, headFont = true)
    private val shine = intArrayOf(0xFFFFF4D6.toInt(), 0xFFFFD27A.toInt(), 0xFFF4A259.toInt(), 0xFFE8742C.toInt(), 0xFFB8521B.toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.bands(0f, 0f, w, h, intArrayOf(0xFF22333B.toInt(), 0xFF27404A.toInt(), 0xFF2D4D57.toInt(), 0xFF335A63.toInt(), 0xFF3A6770.toInt()))
        // a field of dim pixel stars
        val rnd = kotlin.random.Random(5)
        repeat(70) { c.dot(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.55f, Pal.alpha(Pal.CREAM, 0.25f + rnd.nextFloat() * 0.4f)) }
        val cx = w / 2 + Px.dp(70f)
        snesTitle(c, "FOXBOX", cx, Px.dp(62f))
        c.textCentered("write it in the studio · perform it in the valley", cx, Px.dp(98f), tagP.apply { color = Pal.AQUA_L })
        cornucopia(c, cx - Px.dp(250f), Px.dp(70f), Px.u * 0.85f)
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

    /** A little 16-bit cornucopia spilling a bouquet of flowers. */
    private fun cornucopia(c: Canvas, cx: Float, cy: Float, g: Float) {
        with(Iso) {
            val n = 26
            // woven horn: rings from the curled tip to the wide mouth
            for (pass in 0..1) for (i in 0..n) {
                val s = i / n.toFloat()
                val x = cx + (-34f + 44f * s) * g
                val y = cy + (-10f * kotlin.math.sin(Math.PI.toFloat() * (0.15f + s * 0.85f)) + 6f * (1 - s) * (1 - s) - 2f) * g
                val r = 1.4f + 8.5f * s * s
                if (pass == 0) c.disc(x, y, r + 1, g, INK)
                else c.disc(x, y, r, g, if (i % 2 == 0) 0xFFC98A4B.toInt() else 0xFFA8693A.toInt())
            }
            // curled tip
            c.disc(cx - 36 * g, cy + 4 * g, 2f, g, INK); c.disc(cx - 36 * g, cy + 4 * g, 1.2f, g, 0xFFA8693A.toInt())
            val mx = cx + 10 * g; val my = cy - 2 * g
            c.disc(mx, my, 9.5f, g, INK, 1.1f)
            c.disc(mx + g, my, 8f, g, 0xFF3A2416.toInt(), 1.1f)
            // fruit and flowers spilling out
            c.disc(mx + 8 * g, my + 8 * g, 3f, g, INK); c.disc(mx + 8 * g, my + 8 * g, 2.3f, g, 0xFFD8473A.toInt())
            for ((dx, dy) in listOf(4f to 9f, 6f to 11f, 3f to 12f, 5f to 13f)) { c.disc(mx + dx * g, my + dy * g, 1.6f, g, INK); c.disc(mx + dx * g, my + dy * g, 1.1f, g, 0xFF7A4A8C.toInt()) }
            c.disc(mx + 12 * g, my + 5 * g, 2.6f, g, INK); c.disc(mx + 12 * g, my + 5 * g, 1.9f, g, 0xFFF0C23B.toInt())
        }
        val t = (System.nanoTime() / 1e9f)
        val bloomX = cx + 14 * g; val bloomY = cy - 8 * g
        listOf(Triple(-2f, -6f, 0), Triple(6f, -9f, 3), Triple(12f, -2f, 1), Triple(3f, 2f, 6), Triple(16f, -10f, 2), Triple(9f, 5f, 4)).forEach { (dx, dy, v) ->
            c.flower(bloomX + dx * g, bloomY + dy * g, 0.55f, 1f, v, t, false)
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class Card(c: Context, val project: Project?) : PixelView(c) {
        private val nameP = Px.text(15f, Pal.BROWN_D, align = Paint.Align.LEFT)
        private val infoP = Px.text(12f, Pal.BROWN, align = Paint.Align.LEFT)
        private var pressed = false
        private val long = Runnable { project?.let(onManage); pressed = false; invalidate() }
        override fun paint(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.pixelBox(u, u, w - u, h - u, if (pressed) Pal.PAPER else if (project == null) Pal.ORANGE else Pal.CREAM)
            if (project == null) {
                val p = Px.text(15f, Pal.CREAM)
                c.textCentered("+", w / 2, h / 2 - Px.dp(18f), Px.text(36f, Pal.CREAM, headFont = true))
                c.textCentered("New project", w / 2, h / 2 + Px.dp(30f), p)
                return
            }
            val clips = project.clips
                        project.blocks.forEachIndexed { i, id -> c.sprite(Sprites.icon(id), Px.dp(12f) + i * Px.dp(30f), Px.dp(14f), Px.u * 0.8f) }
            c.drawText(project.name.take(18), Px.dp(14f), h - Px.dp(66f), nameP)
            c.drawText("${project.bpm.toInt()} BPM · ${Theory.NOTE_NAMES[project.root]} ${Theory.SCALES[project.scale].name}", Px.dp(14f), h - Px.dp(46f), infoP)
            c.drawText("${project.blocks.size} blocks · ${clips.size} clips · ${project.audioClips.size} takes", Px.dp(14f), h - Px.dp(30f), infoP)
            c.drawText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.modified)), Px.dp(14f), h - Px.dp(14f), infoP)
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
