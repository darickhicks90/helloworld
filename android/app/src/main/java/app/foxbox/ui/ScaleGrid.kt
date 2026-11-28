package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import app.foxbox.seq.Theory
import app.foxbox.ui.Snes.plate

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
    private val nameP = Px.text(12f, Snes.INK, headFont = true)
    private val noteP = Px.text(12f, Snes.INK, headFont = true)
    private val rowH get() = Px.dp(42f)
    private val nameW get() = minOf(Px.dp(190f), width * 0.32f)
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
        with(Snes) {
            val cw = cellW()
            for ((i, pair) in cands.withIndex()) {
                val (root, si) = pair
                val sc = Theory.SCALES[si]
                val y = i * rowH
                val sel = i == picked
                // the choice: a key with a lamp that lights green when chosen
                c.plate(0f, y + Px.dp(3f), nameW - u * 2, y + rowH - Px.dp(3f), if (nameDown == i) HI else if (sel) FACE_D else FACE)
                val s = Px.dp(9f); val lx = Px.dp(9f); val cy = y + rowH / 2
                c.fill(lx, cy - s / 2, lx + s, cy + s / 2, INK)
                c.fill(lx + u * 0.6f, cy - s / 2 + u * 0.6f, lx + s - u * 0.6f, cy + s / 2 - u * 0.6f, if (sel) GREEN else SHADE)
                nameP.color = if (sel) PURPLE_D else INK
                val pcs = sc.steps.map { (root + it) % 12 }
                val off = sung.count { it !in pcs }
                c.textFit("${Theory.NOTE_NAMES[root]} ${sc.name}" + (if (off > 0) " (−$off)" else ""), Px.dp(22f) + (nameW - Px.dp(30f)) / 2, cy, nameW - Px.dp(32f), nameP)
                // its notes, low to high (and the root again an octave up): play them to hear it
                for (k in 0..sc.steps.size) {
                    val pc = (root + (if (k < sc.steps.size) sc.steps[k] else 0)) % 12
                    val l = nameW + gap + cw * k; val r = l + cw - Px.dp(3f)
                    val down = held.any { it == i * 8 + k }
                    val lit = pc in sung
                    c.plate(l, y + Px.dp(5f), r, y + rowH - Px.dp(5f), if (down) HI else if (lit) YELLOW else FACE)
                    noteP.color = INK
                    c.textFit(Theory.NOTE_NAMES[pc], (l + r) / 2, cy, r - l - Px.dp(6f), noteP)
                }
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
