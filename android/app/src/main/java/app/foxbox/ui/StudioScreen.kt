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
    fun showClibbins()
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
    /** Pickup sample banks: load, save, import, share. */
    fun bankMenu()
    /** Chop a Pickup pad's sound across the pads. */
    fun chopMenu(pad: Int)
    fun addBlockMenu()
    /** Rename a clip or take (from Clibbins). */
    fun renameItem(id: String)
    /** Choose which input an audio track records. */
    fun channelInput(ch: Int)
    /** The great hall (poke the eye in Clibbins, then swipe it away). */
    fun showHall()
    /** File, Audio and MIDI in one menu. */
    fun mainMenu()
}

/**
 * The writing screen: the rail of blocks (hold one and drag it to the
 * tombstone to remove it; + adds one), the top bar (transport, tempo, the
 * block's own tools, ☰ Menu), the page, and the process bar: JAM to layer
 * loops across blocks, CLIPS to prepare clips for Clibbins.
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
    /** The block in front's own tools (Sound, Presets, Load, Mic), at the right of the top bar. */
    private val instr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
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
        rail.addView(Btn(context, "", Pal.GREEN).apply { icon = Iso.iconClibbins; onClick = { host.showClibbins() } }, lp(Px.dp(56), 0, 1.2f))
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
    private lateinit var metroBtn: Btn
    private lateinit var nameBtn: Btn

    private fun topBar(c: Context): View {
        val t = Audio.transport
        nameBtn = Btn(c, Studio.project.name, Pal.BROWN).apply { onClick = { Studio.save(true); host.showProjects() } }
        playBtn = Btn(c, "▶", Pal.AQUA, 44).apply { onClick = { Studio.play() } }
        val stopBtn = Btn(c, "■", Pal.BROWN_L, 44).apply { onClick = { Studio.stop() } }
        val bpm = Stepper(c, "BPM", 92, { Studio.project.bpm.toInt().toString() }) { Studio.setBpm(Studio.project.bpm + it) }
        bpm.onMiddle = { host.bpmDialog() }
        metroBtn = Btn(c, "Click", Pal.AQUA).apply { onClick = { Studio.setMetronome(!t.metronome) } }
        val left = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        listOf(nameBtn, playBtn, stopBtn, Position(c), bpm, metroBtn).forEach { left.addView(it, lp(WRAP, Px.dp(44))) }
        left.addView(View(c), lp(Px.dp(10), 1))
        left.addView(instr, lp(WRAP, Px.dp(44)))
        val menu = Btn(c, "☰ Menu", Snes.PURPLE).apply { onClick = { host.mainMenu() } }
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(left) }, lp(0, Px.dp(48), 1f))
            addView(menu, lp(WRAP, Px.dp(44)))
        }
    }

    /** Bar.beat counter with record / count-in status. */
    private inner class Position(c: Context) : PixelView(c) {
        private val p = Px.text(13f, Pal.CREAM)
        init { animated = true }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Px.dp(96), Px.dp(44))
        override fun paint(c: Canvas) {
            val t = Audio.transport
            val counting = (t.rec == Transport.Rec.ARMED && t.tick < t.recStart) || t.audioRec == Transport.Rec.ARMED || (t.sampRec == Transport.Rec.ARMED && t.tick < 0)
            val recording = t.rec == Transport.Rec.RECORDING || t.audioRec == Transport.Rec.RECORDING || t.sampRec == Transport.Rec.RECORDING
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

    // ------------------------------------------------------------------ the process bar: Jam or Clips

    private var soundBtn: Btn? = null
    private var micBtn: Btn? = null
    private var auditionBtn: Btn? = null
    private var jamBtn: Btn? = null
    private var clipsBtn: Btn? = null
    private var procRec: Btn? = null
    private var sendBtn: Btn? = null
    private var builtMode: Studio.Mode? = null

    private fun bar(v: View) = clipBar.addView(v, lp(WRAP, Px.dp(44)))

    private fun buildClipBar() {
        val c = context
        clipBar.removeAllViews(); instr.removeAllViews()
        soundBtn = null; micBtn = null; auditionBtn = null; jamBtn = null; clipsBtn = null; procRec = null; sendBtn = null
        (chips.parent as? android.view.ViewGroup)?.removeView(chips)
        builtMode = Studio.mode
        if (Studio.page == Studio.FLOWERS) {
            auditionBtn = Btn(c, "Audition", Pal.AQUA).apply { onClick = { Studio.setAudition(!Studio.auditioning) } }.also { bar(it) }
            bar(Btn(c, "✿ Plant in garden", Pal.GREEN).apply { onClick = { Studio.flower?.let { Studio.plantFlower(it) } } })
            bar(Btn(c, "+ New flower", Pal.ORANGE).apply { onClick = { Studio.newFlower() } })
            return
        }
        val e = Studio.current
        // the block's own tools, up in the top bar
        fun tool(v: View) = instr.addView(v, lp(WRAP, Px.dp(44)))
        soundBtn = Btn(c, "Sound", Pal.accent(e.id)).apply { onClick = { if (drawerOpen) closeDrawer() else openDrawer(e) } }.also { tool(it) }
        tool(Btn(c, "Presets", Pal.accent(e.id)).apply { onClick = { host.presetMenu(e.id) } })
        if (e is SampleEngine) {
            tool(Btn(c, "Load", Pal.AQUA).apply { onClick = { host.pickAudio(e.id) } })
            micBtn = Btn(c, "Mic", Pal.ORANGE_D).apply { onClick = { host.toggleMic(e.id) } }.also { tool(it) }
        }
        // the two ways of working: JAM (layer loops) or CLIPS (prepare clips for Clibbins)
        val jam = Studio.mode == Studio.Mode.JAM
        jamBtn = Btn(c, "JAM", Snes.GREEN, 62).apply { onClick = { if (Audio.transport.rec == Transport.Rec.OFF) Studio.setMode(Studio.Mode.JAM) } }.also { bar(it) }
        clipsBtn = Btn(c, "CLIPS", Snes.PURPLE, 70).apply { onClick = { if (Audio.transport.rec == Transport.Rec.OFF) Studio.setMode(Studio.Mode.CLIPS) } }.also { bar(it) }
        clipBar.addView(View(c), lp(Px.dp(8), 1))
        procRec = Btn(c, if (jam) "● Loop" else "● Record clip", Snes.RED).apply { onClick = { Studio.recordMidi() } }.also { bar(it) }
        bar(Stepper(c, if (jam) "Loop length" else "Clip length", 104, { "${Studio.barsFor(e)} bar" + if (Studio.barsFor(e) > 1) "s" else "" }) { Studio.setBarsFor(e, Studio.barsFor(e) + it) })
        chips.orientation = LinearLayout.HORIZONTAL
        clipBar.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(0, Px.dp(44), 1f))
        if (jam) bar(Btn(c, "Clear jam", Pal.BROWN_L).apply { onClick = { Studio.clearJam() } })
        else sendBtn = Btn(c, "➜ Clibbins", Snes.YELLOW).apply {
            onClick = { Studio.selectedClip?.takeIf { it.engine == Studio.current.id }?.let { Studio.toggleInClibbins(it) } ?: Studio.message("Record a clip, or tap one, then send it") }
        }.also { bar(it) }
        chipSig = ""
    }

    private var chipSig = ""
    private fun refreshChips() {
        val e = Studio.current
        val jam = Studio.mode == Studio.Mode.JAM
        val clips = if (jam) Studio.jamLoops else Studio.clipsFor(e)
        val sel = Studio.selectedClip
        val sig = "$jam" + e.id + clips.joinToString { it.id + it.name + it.bars + Studio.isPlaying(it) + it.inClibbins + it.muted } + sel?.id
        if (sig == chipSig) return
        chipSig = sig
        chips.removeAllViews()
        if (clips.isEmpty()) chips.addView(Btn(context, if (jam) "No loops yet: ● Loop records one, then switch blocks and layer more" else "No clips for ${e.title} yet: ● Record clip", Pal.BROWN), lp(WRAP, Px.dp(44)))
        clips.forEach { clip ->
            val playing = Studio.isPlaying(clip)
            val label = (if (playing) "▶ " else "") + clip.name + " · ${fmtBars(clip)}" + (if (!jam && clip.inClibbins) " ✓" else "") + (if (!jam && clip === sel) " ◀" else "")
            chips.addView(Btn(context, label, Pal.accent(clip.engine)).apply {
                on = playing; icon = Sprites.icon(clip.engine)
                // Jam: tap starts or stops the loop. Clips: tap previews it and picks it for ➜ Clibbins.
                onClick = { if (!jam) Studio.selectClip(clip); Studio.toggleClip(clip) }
                onLong = { host.clipMenu(clip) }
            }, lp(WRAP, Px.dp(44)))
        }
    }
    private fun fmtBars(c: Clip) = if (c.lengthTicks % Ticks.BAR == 0) "${c.lengthTicks / Ticks.BAR}b" else "%.1fb".format(c.bars)

    private fun refresh() {
        buildRail()
        if (Studio.page != Studio.FLOWERS && builtMode != Studio.mode) buildClipBar()
        val t = Audio.transport
        tabBtns.forEach { (id, b) -> b.on = id == Studio.page }
        nameBtn.text = Studio.project.name
        playBtn.on = t.playing
        val rec = t.rec != Transport.Rec.OFF
        val jam = Studio.mode == Studio.Mode.JAM
        procRec?.let { b ->
            b.on = rec; b.blink = rec && t.rec == Transport.Rec.ARMED
            b.text = when { t.rec == Transport.Rec.ARMED -> "count-in…"; rec -> "■ Stop"; jam -> "● Loop"; else -> "● Record clip" }
            b.requestLayout()
        }
        jamBtn?.on = jam; clipsBtn?.on = !jam
        sendBtn?.let { b -> val sc = Studio.selectedClip; b.text = if (sc != null && sc.inClibbins && sc.engine == Studio.current.id) "◀ Take out" else "➜ Clibbins"; b.requestLayout() }
        metroBtn.on = t.metronome
        micBtn?.let { b ->
            val on = host.micTarget() == Studio.current.id
            b.on = on; b.blink = on && t.sampRec != Transport.Rec.RECORDING
            b.text = when {
                !on -> "Mic"
                t.sampRec == Transport.Rec.ARMED -> if (t.tick < 0) "count-in…" else "next bar…"
                t.sampEnd >= 0 -> "ending on bar…"
                else -> "■ Stop mic"
            }
            b.requestLayout()
        }
        auditionBtn?.let { it.on = Studio.auditioning }
        if (Studio.page != Studio.FLOWERS) refreshChips()
        listOf(nameBtn, playBtn, metroBtn).forEach { it.requestLayout(); it.invalidate() }
        for (i in 0 until clipBar.childCount) clipBar.getChildAt(i).invalidate()
        for (i in 0 until instr.childCount) instr.getChildAt(i).invalidate()
    }

    // ------------------------------------------------------------------ sound drawer

    private fun openDrawer(e: Engine) {
        drawerOpen = true; soundBtn?.on = true
        drawer.removeAllViews()
        drawer.setBackgroundColor(Pal.alpha(Snes.INK, 0.6f))
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Px.dp(6), Px.dp(6), Px.dp(6), Px.dp(6)) }
        val accent = Pal.accent(e.id)
        strip.addView(Btn(context, "✕ Close", accent).apply { onClick = { closeDrawer() } }, lp(WRAP, Px.dp(44)).apply { rightMargin = Px.dp(6) })
        var k = 0
        e.p.sections.forEach { (name, params) ->
            if (name == "XY Pad" || name.startsWith("Pad ") || (e === Audio.minds && name in app.foxbox.engines.Minds.DRUMS)) return@forEach
            val col = Snes.accent(k++)
            val panel = Panel(context, name, accent = col)
            val flow = Flow(context)
            params.forEach { flow.addView(Knob(context, it, col)) }
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
