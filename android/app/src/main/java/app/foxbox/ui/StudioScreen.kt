package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.seq.Clip
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport

/** What the screens need from the activity. */
interface Host {
    fun showProjects()
    fun showValley()
    fun pickAudio(e: Engine)
    fun toggleMic()
    val micOn: Boolean
    fun clipMenu(clip: Clip)
    fun midiMenu()
    fun bpmDialog()
}

/** The writing screen: rail of engines, project transport, engine page, engine transport. */
@SuppressLint("ViewConstructor")
class StudioScreen(c: Context, private val host: Host) : LinearLayout(c) {
    private val pages = HashMap<Engine, View>()
    private val content = FrameLayout(c)
    private val drawer = FrameLayout(c)
    private val rail = LinearLayout(c)
    private val tabBtns = HashMap<Engine, Btn>()
    private val clipBar = LinearLayout(c)
    private val chips = LinearLayout(c)
    private var drawerOpen = false

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Pal.BROWN)
        rail.orientation = VERTICAL
        rail.setPadding(Px.dp(4), Px.dp(4), Px.dp(2), Px.dp(4))
        Studio.tabs.forEach { e ->
            val b = Btn(c, "", Pal.accent(e.id)).apply { icon = Sprites.icon(e.id); onClick = { select(e) } }
            tabBtns[e] = b
            rail.addView(b, lp(Px.dp(56), 0, 1f))
        }
        rail.addView(Btn(c, "", Pal.ORANGE).apply { icon = Sprites.fox; onClick = { host.showValley() } }, lp(Px.dp(56), 0, 1.3f))
        addView(rail, lp(Px.dp(62), MATCH))

        val main = LinearLayout(c).apply { orientation = VERTICAL }
        main.addView(topBar(c), lp(MATCH, Px.dp(48)))
        content.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
        drawer.visibility = GONE
        main.addView(content, lp(MATCH, 0, 1f))
        clipBar.orientation = HORIZONTAL
        clipBar.gravity = android.view.Gravity.CENTER_VERTICAL
        main.addView(clipBar, lp(MATCH, Px.dp(48)))
        addView(main, lp(0, MATCH, 1f))
        select(Studio.current)
        onStudio { refresh() }
    }

    // ------------------------------------------------------------------ project transport

    private lateinit var playBtn: Btn
    private lateinit var recBtn: Btn
    private lateinit var metroBtn: Btn
    private lateinit var quantBtn: Btn
    private lateinit var tapeBtn: Btn

    private fun topBar(c: Context): View {
        val t = Audio.transport
        val back = Btn(c, "◀").apply { onClick = { Studio.save(true); host.showProjects() } }
        val name = Btn(c, Studio.project.name, Pal.BROWN).apply { onClick = { host.showProjects() } }
        playBtn = Btn(c, "▶", Pal.AQUA, 48).apply { onClick = { Studio.togglePlay() } }
        recBtn = Btn(c, "● Rec All", Pal.ORANGE_D).apply { onClick = { Studio.recordProject() } }
        val bars = Stepper(c, "Loop", 84, { "${Studio.project.loopBars} bar" }) { Studio.setLoopBars(Studio.project.loopBars * (if (it > 0) 2 else 1) / (if (it < 0) 2 else 1)) }
        metroBtn = Btn(c, "Metronome", Pal.AQUA).apply { onClick = { Studio.setMetronome(!t.metronome) } }
        quantBtn = Btn(c, "Quantize", Pal.AQUA).apply { onClick = { Studio.setQuantize(!t.quantize) } }
        val bpm = Stepper(c, "BPM", 96, { Studio.project.bpm.toInt().toString() }) { Studio.setBpm(Studio.project.bpm + it) }
        bpm.onMiddle = { host.bpmDialog() }
        tapeBtn = Btn(c, "● Audio", Pal.ORANGE_D).apply { onClick = { Studio.toggleTape() } }
        val midi = Btn(c, "MIDI", Pal.AQUA).apply { onClick = { host.midiMenu() } }
        val r = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        listOf(back, name, playBtn, recBtn, bars, Position(c), metroBtn, quantBtn, bpm, tapeBtn, midi).forEach { r.addView(it, lp(WRAP, Px.dp(44))) }
        return HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    /** Bar.beat counter with record / count-in status. */
    private inner class Position(c: Context) : PixelView(c) {
        private val p = Px.text(13f, headFont = false)
        init { animated = true }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Px.dp(96), Px.dp(44))
        override fun paint(c: Canvas) {
            val t = Audio.transport
            val counting = t.rec == Transport.Rec.ARMED && t.tick < t.recStart
            val bg = when { t.rec == Transport.Rec.RECORDING -> Pal.ORANGE_D; counting -> Pal.ORANGE_L; else -> Pal.BROWN_D }
            c.pixelBox(u, u, width - u, height - u, bg)
            p.color = Pal.CREAM
            val s = when {
                counting -> "count ${t.beat + 1}"
                !t.playing -> "stopped"
                else -> "${t.bar + 1}.${t.beat + 1}" + if (t.rec == Transport.Rec.RECORDING) " REC" else ""
            }
            c.textCentered(s, width / 2f, height / 2f, p)
        }
    }

    // ------------------------------------------------------------------ pages

    private fun select(e: Engine) {
        Studio.select(e)
        val page = pages.getOrPut(e) { buildPage(e) }
        for (i in 0 until content.childCount) content.getChildAt(i).let { if (it !== drawer) it.visibility = GONE }
        if (page.parent == null) content.addView(page, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        page.visibility = VISIBLE
        closeDrawer()
        buildClipBar()
    }

    private fun buildPage(e: Engine): View {
        val c = context
        if (e === Audio.minds) return MindsPage(c).apply { setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(4)) }
        val panel = ChordPanel(c)
        val strum = StrumPad(c, panel.chords)
        val accent = Pal.accent(if (e === Audio.teeth) Studio.melodic.id else e.id)
        panel.chords.accent = accent; strum.accent = accent
        return LinearLayout(c).apply {
            orientation = HORIZONTAL
            setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(4))
            addView(SquareBox(c, XyPad(c, e)), lp(WRAP, MATCH))
            addView(panel, lp(Px.dp(200), MATCH).apply { leftMargin = Px.dp(6) })
            addView(strum, lp(0, MATCH, 1f).apply { leftMargin = Px.dp(6) })
        }
    }

    /** Keeps the XY pad square, at most 42% of the screen width. */
    private class SquareBox(c: Context, child: View) : FrameLayout(c) {
        init { addView(child, LayoutParams(MATCH, MATCH)) }
        override fun onMeasure(w: Int, h: Int) {
            val size = minOf(MeasureSpec.getSize(h), (resources.displayMetrics.widthPixels * 0.42f).toInt())
            super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
        }
    }

    // ------------------------------------------------------------------ engine transport + clips

    private lateinit var clipRec: Btn
    private lateinit var soundBtn: Btn
    private var micBtn: Btn? = null

    private fun buildClipBar() {
        val c = context
        val e = Studio.current
        clipBar.removeAllViews()
        clipRec = Btn(c, "● Rec ${e.title}", Pal.ORANGE_D).apply { onClick = { Studio.recordEngine(e) } }
        val len = Stepper(c, "Clip length", 110, { "${Studio.barsFor(e)} bar" + if (Studio.barsFor(e) > 1) "s" else "" }) { Studio.setBarsFor(e, Studio.barsFor(e) + it) }
        soundBtn = Btn(c, "Sound", Pal.accent(e.id)).apply { onClick = { if (drawerOpen) closeDrawer() else openDrawer(e) } }
        listOf(clipRec, len, soundBtn).forEach { clipBar.addView(it, lp(WRAP, Px.dp(44))) }
        if (e === Audio.reflections || e === Audio.leaves) clipBar.addView(Btn(c, "Load", Pal.AQUA).apply { onClick = { host.pickAudio(e) } }, lp(WRAP, Px.dp(44)))
        micBtn = if (e === Audio.reflections) Btn(c, "Mic", Pal.ORANGE_D).apply { onClick = { host.toggleMic() }; clipBar.addView(this, lp(WRAP, Px.dp(44))) } else null
        chips.orientation = HORIZONTAL
        (chips.parent as? android.view.ViewGroup)?.removeView(chips)
        clipBar.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(0, Px.dp(44), 1f))
        chipSig = ""
        refresh()
    }

    private var chipSig = ""
    private fun refreshChips() {
        val e = Studio.current
        val clips = Studio.clipsFor(e)
        val sig = clips.joinToString { it.id + it.name + it.bars + Studio.isPlaying(it) + it.inValley }
        if (sig == chipSig) return
        chipSig = sig
        chips.removeAllViews()
        if (clips.isEmpty()) chips.addView(Btn(context, "No clips yet: tap ● Rec", Pal.BROWN), lp(WRAP, Px.dp(44)))
        clips.forEach { clip ->
            val playing = Studio.isPlaying(clip)
            val label = (if (playing) "▶ " else "") + clip.name + " · ${fmtBars(clip)}" + if (clip.inValley) " ✿" else ""
            chips.addView(Btn(context, label, Pal.accent(e.id)).apply {
                on = playing; icon = Sprites.critter(clip.engine, clip.variant)
                onClick = { Studio.toggleClip(clip) }
                onLong = { host.clipMenu(clip) }
            }, lp(WRAP, Px.dp(44)))
        }
    }
    private fun fmtBars(c: Clip) = if (c.lengthTicks % Ticks.BAR == 0) "${c.lengthTicks / Ticks.BAR}b" else "%.1fb".format(c.bars)

    private fun refresh() {
        val t = Audio.transport
        tabBtns.forEach { (e, b) -> b.on = e === Studio.current }
        playBtn.text = if (t.playing) "■" else "▶"; playBtn.on = t.playing
        val project = t.rec != Transport.Rec.OFF && t.recTarget == null
        recBtn.on = project; recBtn.blink = project && t.rec == Transport.Rec.ARMED
        metroBtn.on = t.metronome; quantBtn.on = t.quantize; tapeBtn.on = Studio.taping; tapeBtn.blink = Studio.taping
        if (::clipRec.isInitialized) {
            val mine = t.rec != Transport.Rec.OFF && t.recTarget === Studio.current
            clipRec.on = mine; clipRec.blink = mine && t.rec == Transport.Rec.ARMED
            micBtn?.let { it.on = host.micOn; it.blink = host.micOn; it.text = if (host.micOn) "■ Stop mic" else "Mic" }
            refreshChips()
        }
        listOf(playBtn, recBtn, metroBtn, quantBtn, tapeBtn).forEach { it.requestLayout(); it.invalidate() }
        (clipBar.getChildAt(1) as? Stepper)?.invalidate()
    }

    // ------------------------------------------------------------------ sound drawer

    private fun openDrawer(e: Engine) {
        drawerOpen = true; soundBtn.on = true
        drawer.removeAllViews()
        drawer.setBackgroundColor(Pal.alpha(Pal.BROWN_D, 0.55f))
        val strip = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(Px.dp(6), Px.dp(6), Px.dp(6), Px.dp(6)) }
        val accent = Pal.accent(e.id)
        strip.addView(Btn(context, "✕ Close", accent).apply { onClick = { closeDrawer() } }, lp(WRAP, Px.dp(44)).apply { rightMargin = Px.dp(6) })
        e.p.sections.forEach { (name, params) ->
            if (e === Audio.minds && name in app.foxbox.engines.Minds.DRUMS) return@forEach
            val panel = Panel(context, name, accent = accent)
            val flow = Flow(context)
            params.forEach { flow.addView(Knob(context, it, accent)) }
            val cols = (params.size + 1) / 2
            panel.addView(flow, lp(Px.dp(74) * cols, WRAP))
            strip.addView(panel, lp(WRAP, WRAP).apply { rightMargin = Px.dp(6) })
        }
        if (e === Audio.teeth) strip.addView(Panel(context, "Bite Pattern", accent = accent).apply { addView(BiteEditor(context), lp(Px.dp(300), Px.dp(80))) }, lp(WRAP, WRAP))
        drawer.addView(HorizontalScrollView(context).apply { addView(strip) }, FrameLayout.LayoutParams(MATCH, MATCH))
        drawer.visibility = VISIBLE
        drawer.bringToFront()
    }

    private fun closeDrawer() {
        drawerOpen = false
        if (::soundBtn.isInitialized) soundBtn.on = false
        drawer.visibility = GONE
        Studio.save()
    }

    /** Tap teeth to toggle Teeth's 16-step bite pattern. */
    private class BiteEditor(c: Context) : PixelView(c) {
        init { animated = true }
        override fun paint(c: Canvas) {
            val tw = width / 16f
            c.fill(0f, 0f, width.toFloat(), height.toFloat(), Pal.ORANGE_D)
            for (i in 0 until 16) {
                val cur = Audio.transport.playing && Audio.teeth.currentStep == i
                val col = when { Audio.teeth.bites[i] && cur -> Pal.AQUA_L; Audio.teeth.bites[i] -> Pal.CREAM; else -> Pal.BROWN }
                val top = u * 2 + if (cur) u * 2 else 0f
                c.fill(i * tw + u, top, (i + 1) * tw - u, height * 0.7f + top, col)
                c.fill(i * tw + tw * 0.3f, height * 0.7f + top, (i + 1) * tw - tw * 0.3f, height * 0.85f + top, col)
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                val i = (e.x / width * 16).toInt().coerceIn(0, 15)
                Audio.teeth.bites[i] = !Audio.teeth.bites[i]; Studio.save()
            }
            return true
        }
    }
}
