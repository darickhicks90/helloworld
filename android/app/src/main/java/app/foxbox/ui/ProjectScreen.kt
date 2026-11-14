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

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.fill(0f, 0f, w, h, Pal.CREAM)
        val cell = Px.dp(22f)
        var x = 0f
        while (x < w) { c.fill(x, 0f, x + cell, h, Pal.alpha(Pal.ORANGE, 0.13f)); x += cell * 2 }
        var y = 0f
        while (y < h) { c.fill(0f, y, w, y + cell, Pal.alpha(Pal.ORANGE, 0.13f)); y += cell * 2 }
        val top = Px.dp(26f)
        val fox = Sprites.fox
        c.sprite(fox, w / 2 - fox.w * Px.u * 1.5f, top, Px.u * 3)
        val ty = top + fox.h * Px.u * 3 + Px.dp(36f)
        titleP.color = Pal.BROWN_D; c.textCentered("FoxBox", w / 2 + Px.dp(3f), ty + Px.dp(3f), titleP)
        titleP.color = Pal.ORANGE; c.textCentered("FoxBox", w / 2, ty, titleP)
        c.textCentered("write it in the studio · perform it in the valley", w / 2, ty + Px.dp(30f), tagP)
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
            val engines = clips.map { it.engine }.distinct()
            engines.forEachIndexed { i, id -> c.sprite(Sprites.critter(id, 0), Px.dp(14f) + i * Px.dp(34f), Px.dp(14f), Px.u * 1.6f) }
            c.drawText(project.name.take(18), Px.dp(14f), h - Px.dp(66f), nameP)
            c.drawText("${project.bpm.toInt()} BPM · ${Theory.NOTE_NAMES[project.root]} ${Theory.SCALES[project.scale].name}", Px.dp(14f), h - Px.dp(46f), infoP)
            c.drawText("${clips.size} clips · ${clips.count { it.inValley }} in the valley", Px.dp(14f), h - Px.dp(30f), infoP)
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
