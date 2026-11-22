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
 * Tap a chord to play it; hold it and swipe the strum pad to strum it. Each button's
 * left end (the strings tab) brings up the strum pad for that chord without sounding it,
 * for strumming or picking it out by hand.
 */
@SuppressLint("ViewConstructor")
class ChordPanel(c: Context) : LinearLayout(c) {
    val chords = ChordButtons(c)
    private val sev = Btn(c, "7th", Pal.AQUA).apply { onClick = { Studio.setKey(seventh = !Studio.project.seventh) } }
    private val foot = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }

    init {
        orientation = VERTICAL
        // Key, octave and scale share one slim row and the 7th sits in the footer,
        // so the chord buttons get most of the column.
        val key = Stepper(c, "Key", 60, { Theory.NOTE_NAMES[Studio.project.root] }) { Studio.setKey(root = Studio.project.root + it) }
        // the octave shows as the key's note in that octave (C3, D4…)
        val oct = Stepper(c, "Octave", 74, { Theory.noteNameOct(48 + Studio.project.octave * 12 + Studio.project.root) }) { Studio.setKey(octave = Studio.project.octave + it) }
        val scale = Stepper(c, "Scale", 100, { Studio.scale.name }) { Studio.setKey(scale = Studio.project.scale + it) }
        addView(row(c, key, oct, scale).apply {
            key.layoutParams = lp(0, Px.dp(ROW), 1f); oct.layoutParams = lp(0, Px.dp(ROW), 1.25f); scale.layoutParams = lp(0, Px.dp(ROW), 1.9f)
        }, lp(MATCH, WRAP))
        addView(chords, lp(MATCH, 0, 1f).apply { topMargin = Px.dp(2); bottomMargin = Px.dp(2) })
        foot.addView(sev, lp(Px.dp(56), Px.dp(ROW)))
        addView(foot, lp(MATCH, WRAP))
        onStudio { sev.on = Studio.project.seventh; key.invalidate(); oct.invalidate(); scale.invalidate(); chords.invalidate() }
    }

    /** Extra buttons for the footer row, beside the 7th (e.g. the step-sequencer switches). */
    fun footer(vararg views: android.view.View) = views.forEach { foot.addView(it, lp(0, Px.dp(ROW), 1f)) }

    companion object { const val ROW = 36 }
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

    /** The strings tab at each button's left end: shows the strum pad for the chord, silently. */
    private val tabW get() = maxOf(Px.dp(38f), width * 0.2f)

    override fun paint(c: Canvas) {
        val rh = height.toFloat() / count
        val gap = Px.dp(2f)
        for (d in 0 until count) {
            val ch = Studio.chord(d)
            val on = d in pressed.values
            val quiet = on && d in muted
            val t = d * rh
            c.pixelBox(u, t + gap, width - u, t + rh - gap, if (on && !quiet) accent else if (d % 2 == 0) Pal.CREAM else Pal.PAPER)
            // the strings tab: three little strings on a darker panel (lit while it holds the strum pad)
            val tl = u * 2; val tr = tabW; val tt = t + gap + u; val tb = t + rh - gap - u * 2
            c.fill(tl, tt, tr, tb, if (quiet) accent else Pal.alpha(Pal.BROWN_D, 0.14f))
            c.fill(tr, tt, tr + u, tb, Pal.alpha(Pal.BROWN_D, 0.35f))
            for (k in 0 until 3) { val sx = tl + (tr - tl) * (k + 1) / 4f; c.fill(sx, tt + Px.dp(5f), sx + maxOf(1f, u * 0.5f), tb - Px.dp(5f), if (quiet) Pal.CREAM else Pal.BROWN) }
            roman.color = if (on && !quiet) Pal.CREAM else Pal.BROWN; name.color = if (on && !quiet) Pal.CREAM else Pal.BROWN_D
            c.drawText(ch.roman, tabW + Px.dp(8f), t + rh / 2 + Px.dp(4f), roman)
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
                if (e.getX(i) < tabW) muted.add(d)   // the strings tab: strum pad up, chord silent
                else { muted.remove(d); Studio.chordOn(d, keyBase(d), 0.55f + 0.4f * (e.getX(i) / width)) }
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
    private val ORIGINAL = 0xFF8FD3F4.toInt()   // a clear sky blue, used nowhere else on the grid

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
            val original = note == origNote
            val bg = when { note in held -> accent; original -> ORIGINAL; root -> Pal.ORANGE_L; (col + r) % 2 == 0 -> Pal.CREAM; else -> Pal.PAPER }
            val l = col * tw; val t = r * th
            c.pixelBox(l + u, t + u, l + tw - u, t + th - u, bg)
            tileText.color = if (note in held) Pal.CREAM else Pal.BROWN_D
            // narrow tiles (a small phone): the names shrink to fit, and the octave hides if it can't fit
            val name = Theory.noteName(note); val size0 = tileText.textSize
            val fit = tw * 0.8f / maxOf(1f, tileText.measureText(name))
            if (fit < 1f) tileText.textSize = size0 * maxOf(fit, 0.55f)
            c.textCentered(name, l + tw / 2, t + th / 2 - Px.dp(4f), tileText)
            tileText.textSize = size0
            val sub = if (original) "original" else (note / 12 - 1).toString()
            if (small.measureText(sub) < tw - Px.dp(4f)) c.textCentered(sub, l + tw / 2, t + th / 2 + Px.dp(12f), small)
        }
    }

    /** The note that plays a sampler's recording at its original pitch (-1 for other blocks). */
    private val origNote get() = (Studio.target as? app.foxbox.engines.SampleEngine)?.rootNote ?: -1

    private fun paintStrings(c: Canvas, w: Float, h: Float) {
        Tv.grille(c, 0f, 0f, w, h)
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
            val col = noteColor(s[i])
            var y = Px.dp(30f)
            while (y < h - Px.dp(20f)) {
                val dx = sin(y * 0.05f + now * 60f) * amp * sin(Math.PI.toFloat() * (y / h))
                c.dot(x + dx - u / 2, y, if (amp > 0) Pal.CREAM else col, if (root) 2 else 1)
                y += u
            }
            small.color = col
            c.textCentered(Theory.noteName(s[i]), x, h - Px.dp(8f), small)
        }
        small.color = Pal.BROWN
    }

    /** Each note name its own soft colour (round the circle of twelve), so the strings read at a glance. */
    private val noteColors = IntArray(12) { pc ->
        android.graphics.Color.HSVToColor(floatArrayOf((pc * 7 % 12) * 30f, 0.42f, 0.98f))
    }
    private fun noteColor(n: Int) = noteColors[Math.floorMod(n, 12)]

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
