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
import app.foxbox.blocks.Blocks
import app.foxbox.engines.SampleEngine
import app.foxbox.fx.Flower
import app.foxbox.seq.Clip
import app.foxbox.seq.Ticks
import app.foxbox.seq.Transport

/** What the screens need from the activity. */
interface Host {
    fun showProjects()
    fun showValley()
    /** Load audio into "reflections", "leaves" or "pad<i>" (a Pickup pad). */
    fun pickAudio(target: String)
    fun toggleMic(target: String)
    fun micTarget(): String?
    fun clipMenu(clip: Clip)
    fun flowerMenu(f: Flower)
    fun chooseXyTarget(xy: XyState, isX: Boolean)
    fun midiMenu()
    fun bpmDialog()
    fun fileMenu()
    fun audioSettings()
    fun presetMenu(engineId: String)
    fun addBlockMenu()
    /** Rename a clip or take (from the clip launcher). */
    fun renameItem(id: String)
    /** Choose which input an audio track records. */
    fun channelInput(ch: Int)
}

/**
 * The writing screen: the rail of blocks (hold one and drag it to the
 * tombstone to remove it; + adds one), the top bar with the MIDI transport,
 * audio rec and the File / Audio / MIDI tabs, the page, and the clip bar.
 */
@SuppressLint("ViewConstructor")
class StudioScreen(c: Context, private val host: Host) : FrameLayout(c) {
    private val pages = HashMap<String, View>()
    private val content = FrameLayout(c)
    private val drawer = FrameLayout(c)
    private val tabBtns = HashMap<String, Btn>()
    private val rail = LinearLayout(c)
    private val clipBar = LinearLayout(c)
    private val chips = LinearLayout(c)
    private val drag = DragLayer(c)
    private var drawerOpen = false

    init {
        setBackgroundColor(Pal.BROWN)
        val body = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        rail.orientation = LinearLayout.VERTICAL
        rail.setPadding(Px.dp(4), Px.dp(4), Px.dp(2), Px.dp(4))
        body.addView(rail, lp(Px.dp(62), MATCH))

        val main = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        main.addView(topBar(c), lp(MATCH, Px.dp(48)))
        content.addView(drawer, LayoutParams(MATCH, MATCH))
        drawer.visibility = GONE
        main.addView(content, lp(MATCH, 0, 1f))
        clipBar.orientation = LinearLayout.HORIZONTAL
        clipBar.gravity = android.view.Gravity.CENTER_VERTICAL
        main.addView(clipBar, lp(MATCH, Px.dp(48)))
        body.addView(main, lp(0, MATCH, 1f))
        addView(body, LayoutParams(MATCH, MATCH))
        addView(drag, LayoutParams(MATCH, MATCH))
        select(Studio.page)
        onStudio { refresh() }
    }

    // ------------------------------------------------------------------ the rail of blocks

    private var railSig = ""
    private fun buildRail() {
        val sig = Studio.project.blocks.joinToString()
        if (sig == railSig) return
        railSig = sig
        rail.removeAllViews(); tabBtns.clear()
        Studio.project.blocks.forEach { id ->
            val b = Btn(context, "", Pal.accent(id)).apply {
                icon = Sprites.icon(id); onClick = { select(id) }
                onDrag = { x, y, phase -> dragBlock(id, x, y, phase) }
            }
            tabBtns[id] = b
            rail.addView(b, lp(Px.dp(56), 0, 1f))
        }
        if (Studio.canAddBlock) rail.addView(Btn(context, "+", Pal.AQUA).apply { onClick = { host.addBlockMenu() } }, lp(Px.dp(56), 0, 1f))
        val fl = Btn(context, "", Pal.accent(Studio.FLOWERS)).apply { icon = Sprites.icon(Studio.FLOWERS); onClick = { select(Studio.FLOWERS) } }
        tabBtns[Studio.FLOWERS] = fl
        rail.addView(fl, lp(Px.dp(56), 0, 1f))
        rail.addView(Btn(context, "", Pal.GREEN).apply { icon = Iso.iconStones; onClick = { host.showValley() } }, lp(Px.dp(56), 0, 1.2f))
        // Pages of removed blocks are dropped.
        pages.keys.filter { it != Studio.FLOWERS && it !in Studio.project.blocks }.forEach { content.removeView(pages.remove(it)) }
        if (Studio.page !in Studio.pages) select(Studio.pages.first())
    }

    private fun dragBlock(id: String, x: Float, y: Float, phase: Int) {
        when (phase) {
            0 -> drag.start(Sprites.icon(id), x, y)
            1 -> drag.move(x, y)
            2 -> { val hit = drag.overStone(); drag.end(); if (hit) Studio.removeBlock(id) }
            else -> drag.end()
        }
    }

    // ------------------------------------------------------------------ top bar

    private lateinit var playBtn: Btn
    private lateinit var recBtn: Btn
    private lateinit var audioBtn: Btn
    private lateinit var metroBtn: Btn
    private lateinit var nameBtn: Btn

    private fun topBar(c: Context): View {
        val t = Audio.transport
        nameBtn = Btn(c, Studio.project.name, Pal.BROWN).apply { onClick = { Studio.save(true); host.showProjects() } }
        playBtn = Btn(c, "▶", Pal.AQUA, 44).apply { onClick = { Studio.play() } }
        val stopBtn = Btn(c, "■", Pal.BROWN_L, 44).apply { onClick = { Studio.stop() } }
        recBtn = Btn(c, "●", Pal.ORANGE_D, 44).apply { onClick = { Studio.recordMidi() } }
        audioBtn = Btn(c, "audio rec", Pal.ROSE).apply { onClick = { Studio.recordAudio() } }
        val bpm = Stepper(c, "BPM", 92, { Studio.project.bpm.toInt().toString() }) { Studio.setBpm(Studio.project.bpm + it) }
        bpm.onMiddle = { host.bpmDialog() }
        metroBtn = Btn(c, "Click", Pal.AQUA).apply { onClick = { Studio.setMetronome(!t.metronome) } }
        val left = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        listOf(nameBtn, playBtn, stopBtn, recBtn, audioBtn, Position(c), bpm, metroBtn).forEach { left.addView(it, lp(WRAP, Px.dp(44))) }
        val tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        tabs.addView(Btn(c, "File", Pal.BROWN_L).apply { onClick = { host.fileMenu() } }, lp(WRAP, Px.dp(44)))
        tabs.addView(Btn(c, "Audio", Pal.BROWN_L).apply { onClick = { host.audioSettings() } }, lp(WRAP, Px.dp(44)))
        tabs.addView(Btn(c, "MIDI", Pal.BROWN_L).apply { onClick = { host.midiMenu() } }, lp(WRAP, Px.dp(44)))
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(left) }, lp(0, Px.dp(48), 1f))
            addView(tabs, lp(WRAP, Px.dp(48)))
        }
    }

    /** Bar.beat counter with record / count-in status. */
    private inner class Position(c: Context) : PixelView(c) {
        private val p = Px.text(13f, Pal.CREAM)
        init { animated = true }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Px.dp(96), Px.dp(44))
        override fun paint(c: Canvas) {
            val t = Audio.transport
            val counting = (t.rec == Transport.Rec.ARMED && t.tick < t.recStart) || t.audioRec == Transport.Rec.ARMED
            val recording = t.rec == Transport.Rec.RECORDING || t.audioRec == Transport.Rec.RECORDING
            val bg = when { recording -> Pal.ORANGE_D; counting -> Pal.ORANGE_L; else -> Pal.BROWN_D }
            c.pixelBox(u, u, width - u, height - u, bg)
            val s = when {
                counting -> "count ${t.beat + 1}"
                !t.playing -> "stopped"
                else -> "${t.bar + 1}.${t.beat + 1}" + if (recording) " REC" else ""
            }
            c.textCentered(s, width / 2f, height / 2f, p)
        }
    }

    // ------------------------------------------------------------------ pages

    private fun select(id: String) {
        Studio.select(id)
        val page = pages.getOrPut(id) { buildPage(id) }
        for (i in 0 until content.childCount) content.getChildAt(i).let { if (it !== drawer) it.visibility = GONE }
        if (page.parent == null) content.addView(page, 0, LayoutParams(MATCH, MATCH))
        page.visibility = VISIBLE
        closeDrawer()
        buildClipBar()
        refresh()
    }

    private fun buildPage(id: String): View = when (id) {
        "minds" -> MindsPage(context).apply { setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(4)) }
        "pickup" -> PickupPage(context, host)
        Studio.FLOWERS -> FlowersPage(context, host)
        else -> InstrumentPage(context, id, host)
    }

    // ------------------------------------------------------------------ clip bar

    private var soundBtn: Btn? = null
    private var micBtn: Btn? = null
    private var auditionBtn: Btn? = null

    private fun bar(v: View) = clipBar.addView(v, lp(WRAP, Px.dp(44)))

    private fun buildClipBar() {
        val c = context
        clipBar.removeAllViews()
        soundBtn = null; micBtn = null; auditionBtn = null
        (chips.parent as? android.view.ViewGroup)?.removeView(chips)
        if (Studio.page == Studio.FLOWERS) {
            auditionBtn = Btn(c, "Audition", Pal.AQUA).apply { onClick = { Studio.setAudition(!Studio.auditioning) } }.also { bar(it) }
            bar(Btn(c, "✿ Plant in garden", Pal.GREEN).apply { onClick = { Studio.flower?.let { Studio.plantFlower(it) } } })
            bar(Btn(c, "+ New flower", Pal.ORANGE).apply { onClick = { Studio.newFlower() } })
            bar(Btn(c, "Tip: on the stones, drag a line from a flower through clips", Pal.BROWN))
            return
        }
        val e = Studio.current
        soundBtn = Btn(c, "Sound", Pal.accent(e.id)).apply { onClick = { if (drawerOpen) closeDrawer() else openDrawer(e) } }.also { bar(it) }
        bar(Btn(c, "Presets", Pal.accent(e.id)).apply { onClick = { host.presetMenu(e.id) } })
        if (e is SampleEngine) {
            bar(Btn(c, "Load", Pal.AQUA).apply { onClick = { host.pickAudio(e.id) } })
            micBtn = Btn(c, "Mic", Pal.ORANGE_D).apply { onClick = { host.toggleMic(e.id) } }.also { bar(it) }
        }
        bar(Stepper(c, "Next clip", 104, { "${Studio.barsFor(e)} bar" + if (Studio.barsFor(e) > 1) "s" else "" }) { Studio.setBarsFor(e, Studio.barsFor(e) + it) })
        chips.orientation = LinearLayout.HORIZONTAL
        clipBar.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(0, Px.dp(44), 1f))
        chipSig = ""
    }

    private var chipSig = ""
    private fun refreshChips() {
        val e = Studio.current
        val clips = Studio.clipsFor(e)
        val sig = e.id + clips.joinToString { it.id + it.name + it.bars + Studio.isPlaying(it) + it.inValley + it.muted }
        if (sig == chipSig) return
        chipSig = sig
        chips.removeAllViews()
        if (clips.isEmpty()) chips.addView(Btn(context, "No clips yet: tap ● to record", Pal.BROWN), lp(WRAP, Px.dp(44)))
        clips.forEach { clip ->
            val playing = Studio.isPlaying(clip)
            val label = (if (playing) "▶ " else "") + clip.name + " · ${fmtBars(clip)}" + (if (clip.muted) " (muted)" else "")
            chips.addView(Btn(context, label, Pal.accent(e.id)).apply {
                on = playing; icon = Sprites.icon(clip.engine)
                onClick = { Studio.toggleClip(clip) }
                onLong = { host.clipMenu(clip) }
            }, lp(WRAP, Px.dp(44)))
        }
    }
    private fun fmtBars(c: Clip) = if (c.lengthTicks % Ticks.BAR == 0) "${c.lengthTicks / Ticks.BAR}b" else "%.1fb".format(c.bars)

    private fun refresh() {
        buildRail()
        val t = Audio.transport
        tabBtns.forEach { (id, b) -> b.on = id == Studio.page }
        nameBtn.text = Studio.project.name
        playBtn.on = t.playing
        val rec = t.rec != Transport.Rec.OFF
        recBtn.on = rec; recBtn.blink = rec && t.rec == Transport.Rec.ARMED
        val arec = t.audioRec != Transport.Rec.OFF
        audioBtn.on = arec; audioBtn.blink = t.audioRec == Transport.Rec.ARMED
        audioBtn.text = when (t.audioRec) { Transport.Rec.ARMED -> "count-in…"; Transport.Rec.RECORDING -> "■ stop take"; else -> "audio rec" }
        metroBtn.on = t.metronome
        micBtn?.let { b -> val on = host.micTarget() == Studio.current.id; b.on = on; b.blink = on; b.text = if (on) "■ Stop mic" else "Mic"; b.requestLayout() }
        auditionBtn?.let { it.on = Studio.auditioning }
        if (Studio.page != Studio.FLOWERS) refreshChips()
        listOf(nameBtn, playBtn, recBtn, audioBtn, metroBtn).forEach { it.requestLayout(); it.invalidate() }
        for (i in 0 until clipBar.childCount) clipBar.getChildAt(i).invalidate()
    }

    // ------------------------------------------------------------------ sound drawer

    private fun openDrawer(e: Engine) {
        drawerOpen = true; soundBtn?.on = true
        drawer.removeAllViews()
        drawer.setBackgroundColor(Pal.alpha(Pal.BROWN_D, 0.55f))
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Px.dp(6), Px.dp(6), Px.dp(6), Px.dp(6)) }
        val accent = Pal.accent(e.id)
        strip.addView(Btn(context, "✕ Close", accent).apply { onClick = { closeDrawer() } }, lp(WRAP, Px.dp(44)).apply { rightMargin = Px.dp(6) })
        e.p.sections.forEach { (name, params) ->
            if (name == "XY Pad" || name.startsWith("Pad ") || (e === Audio.minds && name in app.foxbox.engines.Minds.DRUMS)) return@forEach
            val panel = Panel(context, name, accent = accent)
            val flow = Flow(context)
            params.forEach { flow.addView(Knob(context, it, accent)) }
            panel.addView(flow, lp(Px.dp(74) * ((params.size + 1) / 2), WRAP))
            strip.addView(panel, lp(WRAP, WRAP).apply { rightMargin = Px.dp(6) })
        }
        drawer.addView(HorizontalScrollView(context).apply { addView(strip) }, LayoutParams(MATCH, MATCH))
        drawer.visibility = VISIBLE
        drawer.bringToFront()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        soundBtn?.on = false
        drawer.visibility = GONE
        Studio.save()
    }
}

/** Overlay while a block is being dragged: the ghost icon and the R.I.P. tombstone. */
@SuppressLint("ViewConstructor")
class DragLayer(c: Context) : PixelView(c) {
    private var icon: Sprite? = null
    private var x = 0f; private var y = 0f
    private val loc = IntArray(2)
    private val ripP = Px.text(11f, Pal.CREAM, headFont = true)
    private val stone = FloatArray(4)

    init { isClickable = false; visibility = GONE }

    fun start(s: Sprite, rx: Float, ry: Float) { icon = s; visibility = VISIBLE; animated = true; move(rx, ry) }
    fun move(rx: Float, ry: Float) { getLocationOnScreen(loc); x = rx - loc[0]; y = ry - loc[1]; invalidate() }
    fun end() { icon = null; animated = false; visibility = GONE }
    fun overStone() = x in stone[0]..stone[2] && y in stone[1]..stone[3]

    override fun paint(c: Canvas) {
        val ic = icon ?: return
        val h = minOf(height / 3.2f, Px.dp(190f))
        val cx = width / 2f; val bottom = height - Px.dp(8f)
        stone[0] = cx - h * 0.6f; stone[1] = bottom - h * 1.1f; stone[2] = cx + h * 0.6f; stone[3] = bottom
        with(Iso) { c.tombstone(cx, bottom, h, overStone(), ripP) }
        c.spriteCentered(ic, x, y - Px.dp(30f), Px.u * 1.4f, alpha = 220)
    }
    override fun onTouchEvent(event: android.view.MotionEvent?) = false
}
