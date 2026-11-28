package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import app.foxbox.seq.Theory

/**
 * Noodle Forge's scales: every scale the idea could be in, stacked. Each row's
 * name is a button that chooses that scale; beside it are the scale's notes, low
 * to high, as keys you can play to hear it. Notes the idea used are lit.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ScaleGrid(
    c: Context,
    private val cands: List<Pair<Int, Int>>,
    private val sung: Set<Int>,
    initial: Int = -1,
    private val onPick: (Int) -> Unit,
    private val noteOn: (id: Int, note: Int) -> Unit,
    private val noteOff: (id: Int) -> Unit,
) : PixelView(c) {
    private val nameP = Px.text(12f, Pal.BROWN_D, headFont = true)
    private val noteP = Px.text(12f, Pal.BROWN_D, headFont = true)
    private val rowH get() = Px.dp(46f)
    private val nameW get() = minOf(Px.dp(150f), width * 0.36f)
    private val gap get() = Px.dp(6f)
    var picked = initial; private set
    // which key each finger is holding: row*8+k, or -1
    private val held = IntArray(16) { -1 }
    private var nameDown = -1

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (rowH * cands.size).toInt())

    private fun cellW() = (width - nameW - gap) / 8f
    private fun midi(row: Int, k: Int): Int {
        val (root, si) = cands[row]
        val steps = Theory.SCALES[si].steps
        val base = 60 + root - (if (root > 6) 12 else 0)
        return if (k < steps.size) base + steps[k] else base + 12
    }

    override fun paint(c: Canvas) {
        val cw = cellW()
        for ((i, pair) in cands.withIndex()) {
            val (root, si) = pair
            val sc = Theory.SCALES[si]
            val y = i * rowH
            val sel = i == picked
            // the choice button
            val face = if (sel) Pal.GREEN else if (nameDown == i) Pal.SUN else Pal.CREAM
            c.pixelBox(u, y + Px.dp(4f), nameW - u, y + rowH - Px.dp(4f), face)
            nameP.color = if (sel) Pal.CREAM else Pal.BROWN_D
            c.textFit((if (sel) "✓ " else "") + "${Theory.NOTE_NAMES[root]} ${sc.name}", nameW / 2, y + rowH / 2, nameW - Px.dp(14f), nameP)
            // its notes, playable, low to high (and the root again an octave up)
            for (k in 0..sc.steps.size) {
                val pc = (root + (if (k < sc.steps.size) sc.steps[k] else 0)) % 12
                val l = nameW + gap + cw * k; val r = l + cw - Px.dp(3f)
                val down = held.any { it == i * 8 + k }
                val lit = pc in sung
                val col = if (down) Pal.SUN else if (lit) Pal.ORANGE_L else Pal.PAPER
                c.pixelBox(l, y + Px.dp(4f), r, y + rowH - Px.dp(4f), col, shade = !down)
                noteP.color = if (lit || down) Pal.BROWN_D else Pal.BROWN_L
                c.textFit(Theory.NOTE_NAMES[pc], (l + r) / 2, y + rowH / 2, r - l - Px.dp(2f), noteP)
            }
        }
    }

    private fun keyAt(x: Float, y: Float): Int {
        val row = (y / rowH).toInt()
        if (row !in cands.indices || x < nameW + gap) return -1
        val k = ((x - nameW - gap) / cellW()).toInt()
        val n = Theory.SCALES[cands[row].second].steps.size
        return if (k in 0..n) row * 8 + k else -1
    }

    private fun press(p: Int, key: Int) {
        if (p >= held.size || held[p] == key) return
        if (held[p] >= 0) noteOff(p)
        held[p] = key
        if (key >= 0) noteOn(p, midi(key / 8, key % 8))
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val ai = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(ai); val y = e.getY(ai); val p = e.getPointerId(ai)
                if (x < nameW && e.actionMasked == MotionEvent.ACTION_DOWN) { nameDown = (y / rowH).toInt().takeIf { it in cands.indices } ?: -1; invalidate() }
                else press(p, keyAt(x, y))
                parent?.requestDisallowInterceptTouchEvent(held.any { it >= 0 })
            }
            MotionEvent.ACTION_MOVE -> for (j in 0 until e.pointerCount) {
                val p = e.getPointerId(j)
                if (p < held.size && held[p] >= 0) press(p, keyAt(e.getX(j), e.getY(j)))
            }
            MotionEvent.ACTION_POINTER_UP -> press(e.getPointerId(ai), -1)
            MotionEvent.ACTION_UP -> {
                if (nameDown >= 0 && e.x < nameW && (e.y / rowH).toInt() == nameDown) { picked = nameDown; onPick(picked) }
                nameDown = -1
                for (p in held.indices) press(p, -1)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { nameDown = -1; for (p in held.indices) press(p, -1); invalidate() }
        }
        return true
    }
}
