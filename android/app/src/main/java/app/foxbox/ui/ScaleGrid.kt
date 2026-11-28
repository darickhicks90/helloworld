package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import app.foxbox.seq.Theory

/**
 * Noodle Forge's scale picker: every scale that fits, one per row, stacked. The
 * scale's name sits at the left (tap it to choose that scale) and its notes run
 * left to right; the notes that were sung or played are lit.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ScaleGrid(c: Context, private val cands: List<Pair<Int, Int>>, private val sung: Set<Int>, private val onPick: (Int) -> Unit) : PixelView(c) {
    private val nameP = Px.text(12f, Pal.BROWN_D, headFont = true, align = Paint.Align.LEFT)
    private val noteP = Px.text(11f, Pal.BROWN_D, headFont = true)
    private val rowH get() = Px.dp(38f)
    private val nameW get() = Px.dp(132f)
    var picked = -1; private set

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (rowH * cands.size + Px.dp(22f)).toInt())
    }

    override fun paint(c: Canvas) {
        // a header: the scale degrees
        noteP.textSize = Px.dp(9f); noteP.color = Pal.BROWN_L
        val cellW = (width - nameW - Px.dp(8f)) / 7f
        for (k in 0 until 7) c.drawText("${k + 1}", nameW + cellW * (k + 0.5f), Px.dp(14f), noteP)
        for ((i, pair) in cands.withIndex()) {
            val (root, si) = pair
            val sc = Theory.SCALES[si]
            val y = Px.dp(20f) + i * rowH
            val sel = i == picked
            // the name: tap here to choose the scale
            c.pixelBox(u, y + u, nameW - Px.dp(4f), y + rowH - u, if (sel) Pal.GREEN else if (i == 0) Pal.SUN else Pal.CREAM)
            nameP.color = if (sel) Pal.CREAM else Pal.BROWN_D; nameP.textSize = Px.dp(12f)
            c.textFit("${Theory.NOTE_NAMES[root]} ${sc.name}", Px.dp(8f) + (nameW - Px.dp(16f)) / 2, y + rowH / 2, nameW - Px.dp(16f), nameP.apply { textAlign = Paint.Align.CENTER })
            // its notes, left to right
            for ((k, st) in sc.steps.withIndex()) {
                val pc = (root + st) % 12
                val l = nameW + cellW * k; val r = l + cellW - Px.dp(3f)
                val lit = pc in sung
                c.pixelBox(l, y + Px.dp(4f), r, y + rowH - Px.dp(4f), if (lit) (if (sel) Pal.GREEN_L else Pal.ORANGE_L) else if (sel) 0xFFE9F2D8.toInt() else Pal.PAPER, shade = false)
                noteP.textSize = Px.dp(11f); noteP.color = if (lit) Pal.BROWN_D else Pal.BROWN_L
                c.textFit(Theory.NOTE_NAMES[pc], (l + r) / 2, y + rowH / 2, r - l - Px.dp(2f), noteP)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && e.x < nameW) {
            val i = ((e.y - Px.dp(20f)) / rowH).toInt()
            if (i in cands.indices) { picked = i; invalidate(); onPick(i) }
        }
        return true
    }
}
