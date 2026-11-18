package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.widget.LinearLayout
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.engines.Minds
import kotlin.random.Random

/** Minds page: hand-editable 8×16 step grid plus the selected drum's sound. */
@SuppressLint("ViewConstructor")
class MindsPage(c: Context) : LinearLayout(c) {
    private val minds = Audio.minds
    private var selected = 0
    private val grid = StepGrid(c)
    private val drumPanel = Panel(c, "Drum", accent = Snes.RED)
    private val onBtn = Btn(c, "Sequencer", Pal.ORANGE, 120)
    private val latchBtn = Btn(c, "Latch", Pal.SUN)

    init {
        orientation = HORIZONTAL
        addView(grid, lp(0, MATCH, 1f))
        val side = LinearLayout(c).apply { orientation = VERTICAL; setPadding(Px.dp(6), 0, 0, 0) }
        onBtn.onClick = { minds.enabled.set(if (minds.enabled.on) 0f else 1f); Studio.save(); refresh() }
        val gen = Btn(c, "New layer", Pal.AQUA).apply { onClick = { minds.generate(Random.nextInt(1, 99999)); Studio.save(); grid.invalidate() } }
        val keep = Btn(c, "Keep", Pal.AQUA).apply { onClick = { minds.keepGenerated(); Studio.save(); grid.invalidate() } }
        val clear = Btn(c, "Clear").apply { onClick = { minds.clear(); Studio.save(); grid.invalidate() } }
        val btns = LinearLayout(c).apply { orientation = VERTICAL; addView(gen, lp(MATCH, Px.dp(40))); addView(row(c, keep, clear).apply { keep.layoutParams = lp(0, Px.dp(40), 1f); clear.layoutParams = lp(0, Px.dp(40), 1f) }, lp(MATCH, WRAP)) }
        latchBtn.onClick = { minds.latch.set(if (minds.latch.on) 0f else 1f); Studio.save(); refresh() }
        side.addView(row(c, onBtn, latchBtn).apply { onBtn.layoutParams = lp(0, Px.dp(44), 1f); latchBtn.layoutParams = lp(Px.dp(76), Px.dp(44)) }, lp(MATCH, WRAP))
        side.addView(row(c, Knob(c, minds.generative, Snes.PURPLE), btns).apply { btns.layoutParams = lp(0, WRAP, 1f) }, lp(MATCH, WRAP))
        side.addView(drumPanel, lp(MATCH, 0, 1f))
        addView(side, lp(Px.dp(250), MATCH))
        onStudio { refresh() }
    }

    private fun refresh() {
        onBtn.on = minds.enabled.on
        onBtn.text = if (minds.enabled.on) "Sequencer ON" else "Sequencer OFF"
        onBtn.invalidate()
        latchBtn.on = minds.latch.on; latchBtn.invalidate()
        buildDrumPanel()
        grid.invalidate()
    }

    private var builtFor = -1
    private fun buildDrumPanel() {
        if (builtFor == selected) return
        builtFor = selected
        drumPanel.removeAllViews()
        val dp = minds.drumParams[selected]
        val flow = Flow(context)
        listOf(dp.tune, dp.decay, dp.tone, dp.level).forEachIndexed { k, p -> flow.addView(Knob(context, p, Snes.accent(k))) }
        drumPanel.addView(Btn(context, Minds.DRUMS[selected], Pal.ORANGE).apply { on = true; onClick = { hit(selected) } }, lp(MATCH, Px.dp(38)))
        drumPanel.addView(flow, lp(MATCH, WRAP))
    }

    private fun hit(d: Int) {
        val key = 0x13000000 + d
        Studio.noteOn(minds, key, 36 + d, 0.9f); Studio.noteOff(minds, key)
    }

    @SuppressLint("ViewConstructor")
    inner class StepGrid(c: Context) : PixelView(c) {
        private val nameP = Px.text(13f, align = Paint.Align.LEFT)
        private val flash = FloatArray(Minds.DRUMS.size)
        private var read = 0L
        private val colors = intArrayOf(Pal.ORANGE, Pal.AQUA, Pal.CREAM, Pal.AQUA_L, Pal.ORANGE_L, Pal.BROWN_L, 0xFFD9A066.toInt(), Pal.ORANGE_D)
        init { animated = true }
        private val labelW get() = Px.dp(74f)

        override fun paint(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.pixelBox(0f, 0f, w, h, Pal.BROWN_D, shade = false)
            val ring = minds.hits
            while (read < ring.written) { val i = (read % ring.a.size).toInt(); flash[ring.a[i].toInt().coerceIn(0, 7)] = 1f; read++ }
            val rows = Minds.DRUMS.size
            val rh = (h - u * 4) / rows
            val cw = (w - labelW - u * 4) / Minds.STEPS
            val step = if (Audio.transport.playing) minds.currentStep else -1
            for (r in 0 until rows) {
                val t = u * 2 + r * rh
                val sel = r == selected
                c.pixelBox(u * 2, t + u, labelW, t + rh - u, if (sel) Pal.ORANGE else colors[r])
                nameP.color = if (sel) Pal.CREAM else Pal.BROWN_D
                c.drawText(Minds.DRUMS[r], u * 2 + Px.dp(6f), t + rh / 2 + Px.dp(5f), nameP)
                if (flash[r] > 0.05f) c.fill(labelW - Px.dp(8f), t + rh / 2 - u, labelW - Px.dp(4f), t + rh / 2 + u, Pal.alpha(Pal.CREAM, flash[r]))
                flash[r] *= 0.9f
                for (s in 0 until Minds.STEPS) {
                    val l = labelW + u * 2 + s * cw
                    val v = minds.pattern[r][s]
                    val base = if (s / 4 % 2 == 0) Pal.BROWN else 0xFF8A5733.toInt()
                    c.fill(l + u, t + u, l + cw - u, t + rh - u, if (v > 0f) Pal.alpha(colors[r], 0.4f + v * 0.6f) else base)
                    // The generative layer shows as a small dot, brighter the more it is mixed in.
                    if (minds.generated[r][s] > 0f && minds.generative.value > 0.01f)
                        c.fill(l + cw / 2 - u, t + rh / 2 - u, l + cw / 2 + u, t + rh / 2 + u, Pal.alpha(Pal.CREAM, 0.25f + minds.generative.value * 0.75f))
                    if (s == step) c.fill(l + u, t + u, l + cw - u, t + u * 2, Pal.CREAM)
                }
            }
            if (!minds.enabled.on) c.fill(labelW + u * 2, 0f, w, h, Pal.alpha(Pal.BROWN_D, 0.5f))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
            val rows = Minds.DRUMS.size
            val r = ((e.y - u * 2) / ((height - u * 4) / rows)).toInt().coerceIn(0, rows - 1)
            if (e.x < labelW) { selected = r; hit(r); refresh(); return true }
            val s = ((e.x - labelW - u * 2) / ((width - labelW - u * 4) / Minds.STEPS)).toInt().coerceIn(0, Minds.STEPS - 1)
            minds.toggleStep(r, s); Studio.save(); invalidate()
            return true
        }
    }
}
