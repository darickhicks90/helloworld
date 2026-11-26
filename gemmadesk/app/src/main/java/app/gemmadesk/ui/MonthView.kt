package app.gemmadesk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** A month grid: tap a day to pick it; dots show days with appointments. */
class MonthView(ctx: Context) : View(ctx) {
    var month: YearMonth = YearMonth.now()
        set(v) { field = v; requestLayout(); invalidate() }
    var selected: LocalDate = LocalDate.now()
        set(v) { field = v; invalidate() }
    var busyDays: (YearMonth) -> Map<LocalDate, Int> = { emptyMap() }
    var onPick: (LocalDate) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headH get() = 26 * d
    private val cellH get() = 44 * d

    private fun first() = month.atDay(1)
    private fun offset() = first().dayOfWeek.value % 7   // Sunday first
    private fun rows() = (offset() + month.lengthOfMonth() + 6) / 7

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (headH + rows() * cellH + 4 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cw = width / 7f
        p.textAlign = Paint.Align.CENTER
        p.textSize = 11 * d; p.typeface = Typeface.DEFAULT_BOLD; p.color = C.MUTED
        val names = listOf("S", "M", "T", "W", "T", "F", "S")
        names.forEachIndexed { i, n -> c.drawText(n, cw * i + cw / 2, 17 * d, p) }
        val busy = busyDays(month)
        val today = LocalDate.now()
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val k = offset() + day - 1
            val cx = cw * (k % 7) + cw / 2; val top = headH + (k / 7) * cellH
            val cy = top + 18 * d
            val r = 15 * d
            if (date == selected) { p.color = C.ACCENT; c.drawCircle(cx, cy, r, p) }
            else if (date == today) { p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = C.ACCENT; c.drawCircle(cx, cy, r, p); p.style = Paint.Style.FILL }
            p.textSize = 14 * d; p.typeface = if (date == today) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            p.color = if (date == selected) 0xFFFFFFFF.toInt() else C.INK
            c.drawText(day.toString(), cx, cy + 5 * d, p)
            val n = busy[date] ?: 0
            if (n > 0) {
                p.color = if (date == selected) C.ACCENT else 0xFFFF8A3D.toInt()
                for (j in 0 until minOf(n, 3)) c.drawCircle(cx + (j - (minOf(n, 3) - 1) / 2f) * 6 * d, top + 38 * d, 2.2f * d, p)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val col = (e.x / (width / 7f)).toInt().coerceIn(0, 6)
            val rowI = ((e.y - headH) / cellH).toInt()
            val day = rowI * 7 + col - offset() + 1
            if (e.y > headH && day in 1..month.lengthOfMonth()) { selected = month.atDay(day); onPick(selected) }
        }
        return true
    }

    fun title(): String = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year
}
