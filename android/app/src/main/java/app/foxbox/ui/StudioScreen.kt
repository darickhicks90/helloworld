package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.audio.XyState
import app.foxbox.engines.SampleEngine
import app.foxbox.fx.Flower
import app.foxbox.seq.Clip
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport

/** What the screens need from the activity. */
interface Host {
    fun showProjects()
    fun showValley()
    fun pickAudio(e: SampleEngine)
    fun toggleMic(e: SampleEngine)
    fun micOn(e: SampleEngine): Boolean
    fun clipMenu(clip: Clip)
    fun flowerMenu(f: Flower)
    fun chooseXyTarget(xy: XyState, isX: Boolean)
    fun midiMenu()
    fun bpmDialog()
}

/** The writing screen: rail of pages, project transport, page, page transport. */
@SuppressLint("ViewConstructor")
class StudioScreen(c: Context, private val host: Host) : LinearLayout(c) {
    private val pages = HashMap<String, View>()
    private val content = FrameLayout(c)
    private val drawer = FrameLayout(c)
    private val tabBtns = HashMap<String, Btn>()
    private val clipBar = LinearLayout(c)
    private val chips = LinearLayout(c)
    private var drawerOpen = false

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Pal.BROWN)
        val rail = LinearLayout(c).apply { orientation = VERTICAL; setPadding(Px.dp(4), Px.dp(4), Px.dp(2), Px.dp(4)) }
        Studio.pages.forEach { id ->
            val b = Btn(c, "", Pal.accent(id)).apply { icon = Sprites.icon(id); onClick = { select(id) } }
            tabBtns[id] = b
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
        select(Studio.page)
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
        val bars = Stepper(c, "Loop", 84, { "${Studio.project.loopBars} bar" }) { Studio.setLoopBars(if (it > 0) Studio.project.loopBars * 2 else Studio.project.loopBars / 2) }
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
        private val p = Px.text(13f, Pal.CREAM)
        init { animated = true }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Px.dp(96), Px.dp(44))
        override fun paint(c: Canvas) {
            val t = Audio.transport
            val counting = t.rec == Transport.Rec.ARMED && t.tick < t.recStart
            val bg = when { t.rec == Transport.Rec.RECORDING -> Pal.ORANGE_D; counting -> Pal.ORANGE_L; else -> Pal.BROWN_D }
            c.pixelBox(u, u, width - u, height - u, bg)
            val s = when {
                counting -> "count ${t.beat + 1}"
                !t.playing -> "stopped"
                else -> "${t.bar + 1}.${t.beat + 1}" + if (t.rec == Transport.Rec.RECORDING) " REC" else ""
            }
            c.textCentered(s, width / 2f, height / 2f, p)
        }
    }

    // ------------------------------------------------------------------ pages

    private fun select(id: String) {
        Studio.select(id)
        val page = pages.getOrPut(id) { buildPage(id) }
        for (i in 0 until content.childCount) content.getChildAt(i).let { if (it !== drawer) it.visibility = GONE }
        if (page.parent == null) content.addView(page, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        page.visibility = VISIBLE
        closeDrawer()
        buildClipBar()
    }

    private fun buildPage(id: String): View = when (id) {
        "minds" -> MindsPage(context).apply { setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(4)) }
        "bumble" -> InstrumentPage(context, id, host) { chords -> BumbleRight(context, chords) }
        Studio.FLOWERS -> FlowersPage(context, host)
        else -> InstrumentPage(context, id, host)
    }

    // ------------------------------------------------------------------ page transport + clips

    private var clipRec: Btn? = null
    private var soundBtn: Btn? = null
    private var micBtn: Btn? = null
    private var auditionBtn: Btn? = null

    private fun bar(v: View) = clipBar.addView(v, lp(WRAP, Px.dp(44)))

    private fun buildClipBar() {
        val c = context
        clipBar.removeAllViews()
        clipRec = null; soundBtn = null; micBtn = null; auditionBtn = null
        (chips.parent as? android.view.ViewGroup)?.removeView(chips)
        if (Studio.page == Studio.FLOWERS) {
            auditionBtn = Btn(c, "Audition", Pal.AQUA).apply { onClick = { Studio.setAudition(!Studio.auditioning) } }.also { bar(it) }
            bar(Btn(c, "✿ Plant in Valley", Pal.GREEN).apply { onClick = { Studio.flower?.let { Studio.plantFlower(it) } } })
            bar(Btn(c, "+ New flower", Pal.ORANGE).apply { onClick = { Studio.newFlower() } })
            bar(Btn(c, "Tip: play clips, then audition your flower", Pal.BROWN))
            refresh(); return
        }
        val e = Studio.current
        clipRec = Btn(c, "● Rec ${e.title}", Pal.ORANGE_D).apply { onClick = { Studio.recordEngine(e) } }.also { bar(it) }
        bar(Stepper(c, "Clip length", 110, { "${Studio.barsFor(e)} bar" + if (Studio.barsFor(e) > 1) "s" else "" }) { Studio.setBarsFor(e, Studio.barsFor(e) + it) })
        soundBtn = Btn(c, "Sound", Pal.accent(e.id)).apply { onClick = { if (drawerOpen) closeDrawer() else openDrawer(e) } }.also { bar(it) }
        if (e is SampleEngine) {
            bar(Btn(c, "Load", Pal.AQUA).apply { onClick = { host.pickAudio(e) } })
            micBtn = Btn(c, "Mic", Pal.ORANGE_D).apply { onClick = { host.toggleMic(e) } }.also { bar(it) }
        }
        chips.orientation = HORIZONTAL
        clipBar.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(0, Px.dp(44), 1f))
        chipSig = ""
        refresh()
    }

    private var chipSig = ""
    private fun refreshChips() {
        val e = Studio.current
        val clips = Studio.clipsFor(e)
        val sig = e.id + clips.joinToString { it.id + it.name + it.bars + Studio.isPlaying(it) + it.inValley }
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
        tabBtns.forEach { (id, b) -> b.on = id == Studio.page }
        playBtn.text = if (t.playing) "■" else "▶"; playBtn.on = t.playing
        val project = t.rec != Transport.Rec.OFF && t.recTarget == null
        recBtn.on = project; recBtn.blink = project && t.rec == Transport.Rec.ARMED
        metroBtn.on = t.metronome; quantBtn.on = t.quantize; tapeBtn.on = Studio.taping; tapeBtn.blink = Studio.taping
        clipRec?.let { b -> val mine = t.rec != Transport.Rec.OFF && t.recTarget === Studio.current; b.on = mine; b.blink = mine && t.rec == Transport.Rec.ARMED }
        micBtn?.let { b -> val e = Studio.current as SampleEngine; val on = host.micOn(e); b.on = on; b.blink = on; b.text = if (on) "■ Stop mic" else "Mic"; b.requestLayout() }
        auditionBtn?.let { it.on = Studio.auditioning }
        if (Studio.page != Studio.FLOWERS) refreshChips()
        listOf(playBtn, recBtn, metroBtn, quantBtn, tapeBtn).forEach { it.requestLayout(); it.invalidate() }
        for (i in 0 until clipBar.childCount) clipBar.getChildAt(i).invalidate()
    }

    // ------------------------------------------------------------------ sound drawer

    private fun openDrawer(e: Engine) {
        drawerOpen = true; soundBtn?.on = true
        drawer.removeAllViews()
        drawer.setBackgroundColor(Pal.alpha(Pal.BROWN_D, 0.55f))
        val strip = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(Px.dp(6), Px.dp(6), Px.dp(6), Px.dp(6)) }
        val accent = Pal.accent(e.id)
        strip.addView(Btn(context, "✕ Close", accent).apply { onClick = { closeDrawer() } }, lp(WRAP, Px.dp(44)).apply { rightMargin = Px.dp(6) })
        e.p.sections.forEach { (name, params) ->
            if (name == "XY Pad" || (e === Audio.minds && name in app.foxbox.engines.Minds.DRUMS)) return@forEach
            val panel = Panel(context, name, accent = accent)
            val flow = Flow(context)
            params.forEach { flow.addView(Knob(context, it, accent)) }
            panel.addView(flow, lp(Px.dp(74) * ((params.size + 1) / 2), WRAP))
            strip.addView(panel, lp(WRAP, WRAP).apply { rightMargin = Px.dp(6) })
        }
        drawer.addView(HorizontalScrollView(context).apply { addView(strip) }, FrameLayout.LayoutParams(MATCH, MATCH))
        drawer.visibility = VISIBLE
        drawer.bringToFront()
    }

    private fun closeDrawer() {
        drawerOpen = false
        soundBtn?.on = false
        drawer.visibility = GONE
        Studio.save()
    }
}
