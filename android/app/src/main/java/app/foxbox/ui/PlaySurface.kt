package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.widget.LinearLayout
import app.foxbox.Studio
import app.foxbox.seq.Theory
import kotlin.math.sin

/**
 * Middle column: key/scale settings plus one button per diatonic chord.
 * Tap a chord to play it; hold it and swipe the strum pad to strum it.
 */
@SuppressLint("ViewConstructor")
class ChordPanel(c: Context) : LinearLayout(c) {
    val chords = ChordButtons(c)

    init {
        orientation = VERTICAL
        val key = Stepper(c, "Key", 70, { Theory.NOTE_NAMES[Studio.project.root] }) { Studio.setKey(root = Studio.project.root + it) }
        val oct = Stepper(c, "Octave", 70, { (Studio.project.octave + 3).toString() }) { Studio.setKey(octave = Studio.project.octave + it) }
        val scale = Stepper(c, "Scale", 100, { Studio.scale.name }) { Studio.setKey(scale = Studio.project.scale + it) }
        val sev = Btn(c, "7th", Pal.AQUA).apply { onClick = { Studio.setKey(seventh = !Studio.project.seventh) } }
        addView(row(c, key, oct).apply { key.layoutParams = lp(0, Px.dp(38), 1f); oct.layoutParams = lp(0, Px.dp(38), 1f) }, lp(MATCH, WRAP))
        addView(row(c, scale, sev).apply { scale.layoutParams = lp(0, Px.dp(38), 1f) }, lp(MATCH, WRAP))
        addView(chords, lp(MATCH, 0, 1f).apply { topMargin = Px.dp(4) })
        onStudio { sev.on = Studio.project.seventh; key.invalidate(); oct.invalidate(); scale.invalidate(); chords.invalidate() }
    }
}

@SuppressLint("ViewConstructor")
class ChordButtons(c: Context) : PixelView(c) {
    private val pressed = HashMap<Int, Int>() // pointer -> degree
    private val muted = HashSet<Int>()
    var onHeld: (() -> Unit)? = null
    var accent = Pal.ORANGE
    /** Most recently pressed chord degree still held, or null. */
    val held: Int? get() = pressed.values.lastOrNull()
    private val roman = Px.text(10f, headFont = true, align = Paint.Align.LEFT)
    private val name = Px.text(18f, align = Paint.Align.RIGHT)

    private val count get() = Studio.scale.steps.size
    private fun keyBase(d: Int) = 0x10000000 + d * 8

    override fun paint(c: Canvas) {
        val rh = height.toFloat() / count
        val gap = Px.dp(3f)
        for (d in 0 until count) {
            val ch = Studio.chord(d)
            val on = d in pressed.values
            val t = d * rh
            c.pixelBox(u, t + gap, width - u, t + rh - gap, if (on) accent else if (d % 2 == 0) Pal.CREAM else Pal.PAPER)
            roman.color = if (on) Pal.CREAM else Pal.BROWN; name.color = if (on) Pal.CREAM else Pal.BROWN_D
            c.drawText(ch.roman, Px.dp(12f), t + rh / 2 + Px.dp(4f), roman)
            c.drawText(ch.name, width - Px.dp(12f), t + rh / 2 + Px.dp(6f), name)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val d = (e.getY(i) / height * count).toInt().coerceIn(0, count - 1)
                pressed[e.getPointerId(i)] = d
                muted.remove(d)
                Studio.chordOn(d, keyBase(d), 0.55f + 0.4f * (e.getX(i) / width))
                onHeld?.invoke(); invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_CANCEL) pressed.keys.toList() else listOf(e.getPointerId(i))
                ids.forEach { id ->
                    val d = pressed.remove(id) ?: return@forEach
                    if (d !in pressed.values) { if (!muted.remove(d)) Studio.chordOff(keyBase(d)) }
                }
                onHeld?.invoke(); invalidate()
            }
        }
        return true
    }
}

/**
 * Right column: a scale-note grid (not a piano). While a chord is held it
 * turns into strings of that chord's notes that you strum by swiping.
 */
@SuppressLint("ViewConstructor")
class StrumPad(c: Context, private val chords: ChordButtons) : PixelView(c) {
    var accent = Pal.ORANGE
    private val tileText = Px.text(15f)
    private val small = Px.text(9f, Pal.BROWN)
    private val rows = 3

    // keyboard mode
    private val down = HashMap<Int, Int>() // pointer -> note
    // strum mode
    private val lastX = HashMap<Int, Float>()
    private val ringing = HashSet<Int>()
    private val plucked = FloatArray(16)
    private var strumDegree: Int? = null

    init {
        animated = true
        chords.onHeld = {
            val h = chords.held
            if (h != strumDegree) { releaseStrings(); strumDegree = h }
            invalidate()
        }
    }

    private fun strings(): IntArray {
        val d = strumDegree ?: return IntArray(0)
        val ch = Studio.chord(d).notes
        return (listOf(-12, 0, 12).flatMap { o -> ch.map { it + o } }).toIntArray()
    }
    private fun releaseStrings() { val e = Studio.target; ringing.forEach { Studio.noteOff(e, 0x11000000 + it) }; ringing.clear() }
    // Strings use their own keys (0x11…), separate from the chord's (0x10…), so a strum never cuts the chord.

    override fun paint(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (strumDegree != null) paintStrings(c, w, h) else paintGrid(c, w, h)
    }

    private fun paintGrid(c: Canvas, w: Float, h: Float) {
        val cols = Studio.scale.steps.size
        val tw = w / cols; val th = h / rows
        val held = down.values.toSet()
        for (r in 0 until rows) for (col in 0 until cols) {
            val note = Studio.scaleNote(col + (rows - 1 - r) * cols)
            val root = Studio.isRoot(note)
            val bg = when { note in held -> accent; root -> Pal.ORANGE_L; (col + r) % 2 == 0 -> Pal.CREAM; else -> Pal.PAPER }
            val l = col * tw; val t = r * th
            c.pixelBox(l + u, t + u, l + tw - u, t + th - u, bg)
            tileText.color = if (note in held) Pal.CREAM else Pal.BROWN_D
            c.textCentered(Theory.noteName(note), l + tw / 2, t + th / 2 - Px.dp(4f), tileText)
            c.textCentered((note / 12 - 1).toString(), l + tw / 2, t + th / 2 + Px.dp(12f), small)
        }
    }

    private fun paintStrings(c: Canvas, w: Float, h: Float) {
        c.pixelBox(0f, 0f, w, h, Pal.BROWN, shade = false)
        val s = strings()
        val now = System.nanoTime() / 1e9f
        val sw = w / s.size
        val ch = Studio.chord(strumDegree ?: 0)
        tileText.color = Pal.CREAM
        c.textCentered("Strum ${ch.name}", w / 2, Px.dp(16f), tileText)
        for (i in s.indices) {
            val x = sw * (i + 0.5f)
            val age = now - plucked[i]
            val amp = if (age < 0.6f) (1 - age / 0.6f) * Px.dp(6f) else 0f
            val root = Studio.isRoot(s[i])
            var y = Px.dp(30f)
            while (y < h - Px.dp(20f)) {
                val dx = sin(y * 0.05f + now * 60f) * amp * sin(Math.PI.toFloat() * (y / h))
                c.dot(x + dx - u / 2, y, if (amp > 0) Pal.AQUA_L else if (root) Pal.ORANGE_L else Pal.CREAM, if (root) 2 else 1)
                y += u
            }
            small.color = Pal.PAPER
            c.textCentered(Theory.noteName(s[i]), x, h - Px.dp(8f), small)
        }
        small.color = Pal.BROWN
    }

    private fun noteAt(x: Float, y: Float): Int {
        val cols = Studio.scale.steps.size
        val col = (x / width * cols).toInt().coerceIn(0, cols - 1)
        val r = (y / height * rows).toInt().coerceIn(0, rows - 1)
        return Studio.scaleNote(col + (rows - 1 - r) * cols)
    }

    private fun pluck(i: Int, note: Int, vel: Float) {
        val e = Studio.target
        val key = 0x11000000 + i
        if (i in ringing) Studio.noteOff(e, key)
        Studio.noteOn(e, key, note, vel); ringing += i
        plucked[i] = System.nanoTime() / 1e9f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val strum = strumDegree != null
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                if (strum) {
                    // The chord keeps ringing; strummed strings layer on top of it.
                    lastX[id] = x
                    val s = strings(); val idx = (x / width * s.size).toInt().coerceIn(0, s.size - 1)
                    pluck(idx, s[idx], 0.75f)
                } else {
                    val n = noteAt(x, y); down[id] = n
                    val th = height / rows.toFloat()
                    Studio.noteOn(Studio.target, 0x12000000 + id, n, 0.55f + 0.45f * ((y % th) / th))
                }
            }
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) {
                val id = e.getPointerId(p); val x = e.getX(p); val y = e.getY(p)
                if (strum) {
                    val lx = lastX[id] ?: continue
                    val s = strings(); val sw = width.toFloat() / s.size
                    val a = (lx / sw).toInt(); val b = (x / sw).toInt()
                    if (a != b) {
                        val range = if (b > a) (a + 1)..b else (a - 1) downTo b
                        for (k in range) if (k in s.indices) pluck(k, s[k], 0.7f)
                    }
                    lastX[id] = x
                } else {
                    val old = down[id] ?: continue
                    val n = noteAt(x, y)
                    if (n != old) { val eng = Studio.target; Studio.noteOff(eng, 0x12000000 + id); Studio.noteOn(eng, 0x12000000 + id, n, 0.75f); down[id] = n }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_CANCEL) (down.keys + lastX.keys).toList() else listOf(e.getPointerId(e.actionIndex))
                ids.forEach { id ->
                    lastX.remove(id)
                    if (down.remove(id) != null) Studio.noteOff(Studio.target, 0x12000000 + id)
                }
            }
        }
        return true
    }
}
