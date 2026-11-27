package app.foxbox.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

/** Where everything sits on a memo page of a given size (the same proportions everywhere). */
class PageGeo(val paper: RectF) {
    val pw get() = paper.width()
    val ph get() = paper.height()
    val firstRule get() = paper.top + ph * 0.06f
    val lineH get() = (ph * 0.93f - ph * 0.06f) / Notes.LINES
    val marginX get() = paper.left + pw * 0.11f
    val textL get() = paper.left + pw * 0.135f
    val textR get() = paper.right - pw * 0.045f
    val textW get() = textR - textL
    val toolTop get() = paper.top + ph * 0.93f
    fun lineTop(i: Int) = firstRule + i * lineH
    fun baseline(i: Int) = lineTop(i + 1) - lineH * 0.2f
    fun lineAt(y: Float): Int = ((y - firstRule) / lineH).toInt().coerceIn(0, Notes.LINES - 1)
    fun sketchRect(s: Notes.Sketch, out: RectF = RectF()): RectF {
        // the window keeps its shape (4:3 or square) from its height in lines, whatever the screen
        val t = lineTop(s.line) + lineH * 0.12f; val b = lineTop(s.line + s.lines) - lineH * 0.12f
        val w = minOf((b - t) / s.ratio, textW)
        val l = (textL + s.x * textW).coerceAtMost(textR - w)
        return out.apply { set(l, t, l + w, b) }
    }
}

/** Draws a memo page: cream paper, blue rules, the red margin, typeset handwriting and sketch windows. */
class PageArt(c: Context) {
    val hand: Typeface = runCatching { Typeface.createFromAsset(c.assets, "fonts/CoveredByYourGrace.ttf") }.getOrDefault(Typeface.SERIF)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = hand; color = INK }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF(); private val src = Rect()

    companion object {
        const val PAPER = 0xFFFBF6E6.toInt()
        const val RULE = 0xFFA8C3DE.toInt()
        const val MARGIN = 0xFFE39A9A.toInt()
        const val INK = 0xFF23306E.toInt()
        const val MARK = 0x66F4D35E
    }

    /** Big, easy handwriting sized to the line's width (four or five words a line), never taller than the line allows. */
    fun sizeText(g: PageGeo) { text.textSize = minOf(g.textW * 0.124f, g.lineH * 0.8f) }

    /** Width of the first [n] characters of a line as typeset. */
    fun width(s: String, n: Int = s.length) = if (n <= 0) 0f else text.measureText(s, 0, n.coerceAtMost(s.length))

    /** `play`: when set, animated sketches show the frame for this moment and `loaded` redraws once frames are in. */
    fun draw(c: Canvas, g: PageGeo, page: Notes.Page, number: Int, highlight: String? = null, thumbs: Boolean = true, playMs: Long = -1L, loaded: () -> Unit = {}) {
        sizeText(g)
        p.style = Paint.Style.FILL
        p.color = PAPER; c.drawRect(g.paper, p)
        // the faint shadow of the binding at the top of the sheet
        p.color = 0x14000000; c.drawRect(g.paper.left, g.paper.top, g.paper.right, g.paper.top + g.ph * 0.012f, p)
        // rules, skipping the windows
        p.strokeWidth = maxOf(1f, g.lineH * 0.035f)
        for (i in 0 until Notes.LINES) {
            val y = g.lineTop(i + 1)
            p.color = RULE
            val cut = page.sketches.firstOrNull { it.covers(i) && it.line + it.lines - 1 != i }
            if (cut == null) c.drawLine(g.paper.left, y, g.paper.right, y, p)
            else { g.sketchRect(cut, r); c.drawLine(g.paper.left, y, r.left, y, p); c.drawLine(r.right, y, g.paper.right, y, p) }
        }
        p.color = RULE; c.drawLine(g.paper.left, g.firstRule, g.paper.right, g.firstRule, p)
        p.color = MARGIN; c.drawLine(g.marginX, g.paper.top, g.marginX, g.toolTop, p)
        // page number, top right
        val num = text.textSize
        text.textSize = g.lineH * 0.55f; text.color = 0xFF8090A8.toInt(); text.textAlign = Paint.Align.RIGHT
        c.drawText("$number", g.textR, g.firstRule - g.lineH * 0.25f, text)
        text.textAlign = Paint.Align.LEFT; text.textSize = num; text.color = INK
        // the writing
        for (i in 0 until Notes.LINES) {
            val s = page.lines[i]
            if (s.isEmpty()) continue
            if (!highlight.isNullOrBlank()) {
                var k = s.indexOf(highlight, ignoreCase = true)
                p.color = MARK
                while (k >= 0) {
                    c.drawRect(g.textL + width(s, k), g.lineTop(i) + g.lineH * 0.15f, g.textL + width(s, k + highlight.length), g.lineTop(i + 1) - g.lineH * 0.05f, p)
                    k = s.indexOf(highlight, k + highlight.length, ignoreCase = true)
                }
            }
            c.drawText(s, g.textL, g.baseline(i), text)
        }
        // sketch windows: plain white, no lines
        for (s in page.sketches) {
            g.sketchRect(s, r)
            p.color = 0x22000000; c.drawRect(r.left + g.lineH * 0.06f, r.top + g.lineH * 0.08f, r.right + g.lineH * 0.06f, r.bottom + g.lineH * 0.08f, p)
            p.color = 0xFFFFFFFF.toInt(); c.drawRect(r, p)
            val anim = if (thumbs && playMs >= 0) Notes.animation(s.id, loaded) else null
            val bmp = if (anim != null) anim.frames[((playMs * anim.fps / 1000f).toLong() % anim.frames.size).toInt()] else if (thumbs) Notes.thumb(s.id) else null
            if (bmp != null) { src.set(0, 0, bmp.width, bmp.height); c.drawBitmap(bmp, src, r, img) }
            else {
                val ts = text.textSize
                text.textSize = g.lineH * 0.55f; text.color = 0xFFA0A8B8.toInt(); text.textAlign = Paint.Align.CENTER
                c.drawText("tap to draw", r.centerX(), r.centerY() + text.textSize * 0.35f, text)
                text.textAlign = Paint.Align.LEFT; text.textSize = ts; text.color = INK
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1f, g.lineH * 0.04f); p.color = 0xFF9AA4B4.toInt(); c.drawRect(r, p); p.style = Paint.Style.FILL
        }
    }
}
