package app.gemmadesk.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.gemmadesk.data.Html
import app.gemmadesk.data.Item
import app.gemmadesk.data.Page
import kotlin.math.max

/** Draws page items as they will look (used by the design screen and for mockup pictures). */
class PagePainter {
    companion object {
        fun color(c: String, fallback: Int): Int = runCatching { Color.parseColor(Html.safeColor(c, "")) }.getOrDefault(fallback)

        /** The whole page as a picture, [scale] pixels per CSS pixel. */
        fun render(p: Page, scale: Float = 2f): Bitmap {
            val b = Bitmap.createBitmap((p.frame.w * scale).toInt(), (p.height() * scale).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(color(p.background, Color.WHITE))
            c.scale(scale, scale)
            val painter = PagePainter()
            for (i in p.items) painter.drawItem(c, i)
            return b
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** Draw an item in page pixels, matching the CSS the HTML uses. */
    fun drawItem(c: Canvas, i: Item) {
        val r = RectF(i.x.toFloat(), i.y.toFloat(), i.right().toFloat(), i.bottom().toFloat())
        val bg = if (i.background.isBlank()) Color.TRANSPARENT else color(i.background, Color.TRANSPARENT)
        if (i.type == "divider") { fill.color = color(i.background, 0xFFD5DAE3.toInt()); c.drawRect(r, fill); return }
        if (bg != Color.TRANSPARENT) { fill.color = bg; c.drawRoundRect(r, i.radius.toFloat(), i.radius.toFloat(), fill) }
        if (i.type == "input") { stroke.color = 0xFFC9D0DC.toInt(); stroke.strokeWidth = 1f; c.drawRoundRect(RectF(r.left + .5f, r.top + .5f, r.right - .5f, r.bottom - .5f), i.radius.toFloat(), i.radius.toFloat(), stroke) }
        if (i.type == "image") {
            stroke.color = 0x33000000; stroke.strokeWidth = 1.5f
            c.save(); c.clipRect(r); c.drawLine(r.left, r.top, r.right, r.bottom, stroke); c.drawLine(r.right, r.top, r.left, r.bottom, stroke); c.restore()
        }
        if (i.text.isBlank()) return
        text.color = color(i.color, 0xFF1F2430.toInt())
        text.textSize = i.fontSize.toFloat()
        text.typeface = if (i.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        text.isUnderlineText = i.type == "link"
        val padX = if (i.type == "input") 12f else 0f
        val wrapW = max(1, (i.w - 2 * padX).toInt())
        val align = when (if (i.type in setOf("button", "image")) "center" else i.align) { "center" -> Layout.Alignment.ALIGN_CENTER; "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val lay = StaticLayout.Builder.obtain(i.text, 0, i.text.length, text, wrapW).setAlignment(align).setLineSpacing(0f, 1.3f).setIncludePad(false).build()
        val ty = when (i.type) { "button", "image", "input" -> r.top + (i.h - lay.height) / 2f; else -> r.top + i.fontSize * 0.15f }
        c.save(); c.translate(r.left + padX, ty); lay.draw(c); c.restore()
        text.isUnderlineText = false
    }

}
