package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import app.foxbox.FireEscape
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.seq.ArrItem
import app.foxbox.seq.Arrangement
import app.foxbox.seq.Ticks
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the Fire Escape needs from the activity (dialogs and file pickers). */
interface FireHost {
    fun choose(title: String, items: List<String>, checked: Int = -1, pick: (Int) -> Unit)
    fun textDialog(title: String, initial: String, numeric: Boolean = false, done: (String) -> Unit)
    fun importAmp(ch: Int)
    fun importCab(ch: Int)
    fun showCredits(text: String)
    fun leaveFireEscape()
}

/**
 * The Fire Escape: the song laid out along a brick wall at night. Lanes run
 * left to right (one per block, one per audio track); clips are posters pasted
 * on the wall. The top bar plays, records and loops; the bottom bar edits. A
 * selected clip shows a card with its volume and pan (the same ones as in
 * Clibbins).
 */
@SuppressLint("ViewConstructor")
class FireEscapeScreen(c: Context, private val host: Host, private val fire: FireHost) : FrameLayout(c) {
    private val tl = Timeline(c)
    private val drawer = FrameLayout(c)
    private var drawerKind = ""
    private val narrow: Boolean get() { val dm = resources.displayMetrics; return maxOf(dm.widthPixels, dm.heightPixels) / Px.density < 760f }

    private lateinit var playBtn: Btn
    private lateinit var recBtn: Btn
    private lateinit var loopBtn: Btn
    private lateinit var pasteBtn: Btn
    private lateinit var keysBtn: Btn
    private lateinit var fxBtn: Btn
    private lateinit var crateBtn: Btn
    private lateinit var mixBtn: Btn
    private val mixer = MixerView(c)
    private var mixing = false

    /** Swap the timeline for the mixer (or back). */
    private fun showMixer(on: Boolean) {
        mixing = on
        tl.visibility = if (on) GONE else VISIBLE
        mixer.visibility = if (on) VISIBLE else GONE
        refresh()
    }

    init {
        addView(AlleyView(c), LayoutParams(MATCH, MATCH))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(topBar(c), lp(MATCH, Px.dp(48)))
        col.addView(tl, lp(MATCH, 0, 1f))
        col.addView(mixer, lp(MATCH, 0, 1f))
        mixer.visibility = GONE
        col.addView(bottomBar(c), lp(MATCH, Px.dp(48)))
        addView(col, LayoutParams(MATCH, MATCH))
        addView(drawer, LayoutParams(MATCH, MATCH).apply { topMargin = Px.dp(48) })
        drawer.visibility = GONE
        if (FireEscape.selectedLane == null) FireEscape.selectedLane = FireEscape.lanes().firstOrNull()?.id
        onStudio { refresh() }
    }

    private fun bar(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(Px.dp(4), 0, Px.dp(4), 0)
        setBackgroundColor(0xEE1A1520.toInt())
    }

    private fun topBar(c: Context): View {
        val b = bar(c)
        fun add(v: View, w: Int = WRAP) = b.addView(v, lp(w, Px.dp(44)).apply { rightMargin = Px.dp(3) })
        add(Btn(c, if (narrow) "◀" else "◀ Studio", Pal.BROWN, 40).apply { onClick = { fire.leaveFireEscape() } })
        add(Btn(c, "|◀", Pal.AQUA, 40).apply { short = "|◀"; onClick = { FireEscape.rewind() } })
        playBtn = Btn(c, "▶", Pal.AQUA, 40).apply { onClick = { FireEscape.play() } }.also { add(it) }
        recBtn = Btn(c, "● Rec", Snes.RED).apply { onClick = { FireEscape.record() } }.also { add(it) }
        if (!narrow) add(PositionView(c))
        loopBtn = Btn(c, "Loop", Snes.YELLOW).apply { onClick = { FireEscape.toggleLoop() } }.also { add(it) }
        add(Stepper(c, "Snap", if (narrow) 78 else 92, { Arrangement.SNAP_NAMES[FireEscape.arr.snap] }) { FireEscape.setSnap(FireEscape.arr.snap + it) })
        mixBtn = Btn(c, "Mixer", Snes.GREEN).apply { onClick = { showMixer(!mixing) } }.also { add(it) }
        if (!narrow) {
            add(Btn(c, "-", Pal.BROWN_L, 36).apply { onClick = { tl.zoomBy(0.7f) } })
            add(Btn(c, "+", Pal.BROWN_L, 36).apply { onClick = { tl.zoomBy(1.4f) } })
        }
        b.setPadding(Px.dp(4), 0, (TopKeys.WIDTH + NoodlerKey.WIDTH + Px.dp(4f)).toInt(), 0)
        return b
    }

    private fun bottomBar(c: Context): View {
        val b = bar(c)
        fun add(v: View) = b.addView(v, lp(WRAP, Px.dp(44)).apply { rightMargin = Px.dp(3) })
        crateBtn = Btn(c, "Crate", Pal.GREEN).apply { onClick = { toggleDrawer("crate") } }.also { add(it) }
        keysBtn = Btn(c, "Keys", Pal.ORANGE).apply { onClick = { toggleDrawer("keys") } }.also { add(it) }
        fxBtn = Btn(c, "FX", Snes.PURPLE, 44).apply { onClick = { toggleDrawer("fx") } }.also { add(it) }
        add(Btn(c, "Tuner", Pal.AQUA).apply { onClick = { openTuner() } })
        b.addView(spacer(c, Px.dp(6), 0f))
        add(Btn(c, "Copy", Pal.AQUA).apply { onClick = { sel()?.let { FireEscape.copy(it) } ?: Studio.message("Tap a clip to copy it") } })
        pasteBtn = Btn(c, "Paste", Pal.AQUA).apply { onClick = { FireEscape.paste(FireEscape.selectedLane, FireEscape.arr.playhead) } }.also { add(it) }
        add(Btn(c, "Dup", Pal.AQUA).apply { onClick = { sel()?.let { FireEscape.duplicate(it) } ?: Studio.message("Tap a clip first") } })
        add(Btn(c, "Split", Pal.AQUA).apply { onClick = { sel()?.let { FireEscape.split(it, FireEscape.arr.playhead) } ?: Studio.message("Tap a clip, put the playhead in it, then Split") } })
        add(Btn(c, "✕", Snes.RED, 40).apply { onClick = { sel()?.let { FireEscape.delete(it) } ?: Studio.message("Tap a clip to remove it") } })
        return b
    }

    private fun sel(): ArrItem? = FireEscape.item(FireEscape.selected)

    /** The tuner, listening to the selected lane: a track's input, or a sampler's own sound. */
    private fun openTuner() {
        val a = context as? android.app.Activity ?: return
        val lane = FireEscape.selectedLaneObj()
        val src = when {
            lane == null -> 2
            lane.isTrack -> app.foxbox.audio.AudioPrefs.planeInput(lane.channel).coerceIn(0, 2)
            lane.id in listOf("reflections", "leaves", "pickup") -> 100 + Audio.engines.indexOfFirst { it.id == lane.id }
            else -> 2
        }
        Tuner.show(a, src)
    }

    private fun refresh() {
        FireEscape.sync()
        if (!::playBtn.isInitialized) return
        val t = Audio.transport
        playBtn.text = if (t.playing) "■" else "▶"; playBtn.on = t.playing
        recBtn.on = FireEscape.recording; recBtn.blink = FireEscape.recording && (t.rec == app.foxbox.seq.Transport.Rec.ARMED || t.audioRec == app.foxbox.seq.Transport.Rec.ARMED)
        recBtn.text = if (FireEscape.recording) "■ Stop" else "● Rec"
        loopBtn.on = FireEscape.arr.loopOn
        crateBtn.on = drawerKind == "crate"; keysBtn.on = drawerKind == "keys"; fxBtn.on = drawerKind == "fx"
        mixBtn.on = mixing
        listOf(playBtn, recBtn, loopBtn, crateBtn, keysBtn, fxBtn, mixBtn).forEach { it.requestLayout(); it.invalidate() }
        mixer.invalidate()
        if (drawerKind == "crate") buildCrate()
        tl.invalidate()
    }

    // ------------------------------------------------------------------ drawers

    private fun toggleDrawer(kind: String) {
        if (drawerKind == kind) { closeDrawer(); return }
        drawerKind = kind
        drawer.removeAllViews(); drawer.visibility = VISIBLE
        when (kind) {
            "crate" -> buildCrate()
            "keys" -> buildKeys()
            "fx" -> buildFx()
        }
        refresh()
    }

    fun closeDrawer() { drawerKind = ""; drawer.removeAllViews(); drawer.visibility = GONE; refresh() }

    private var crateSig = ""
    private fun buildCrate() {
        val lane = FireEscape.selectedLaneObj()
        val items = lane?.let { FireEscape.crate(it) } ?: emptyList()
        val sig = (lane?.id ?: "") + items.joinToString { it.first + it.second }
        if (sig == crateSig && drawer.childCount > 0) return
        crateSig = sig
        drawer.removeAllViews()
        val strip = bar(context)
        strip.addView(Btn(context, "✕ Close", Pal.BROWN).apply { onClick = { closeDrawer() } }, lp(WRAP, Px.dp(44)).apply { rightMargin = Px.dp(6) })
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        when {
            lane == null -> chips.addView(Btn(context, "Tap a lane's name first", Pal.BROWN_L), lp(WRAP, Px.dp(44)))
            items.isEmpty() -> chips.addView(Btn(context, if (lane.isTrack) "No takes yet: arm the track and ● Rec" else "No clips yet: record some in the Studio, or ● Rec here", Pal.BROWN_L), lp(WRAP, Px.dp(44)))
            else -> items.forEach { (id, name) ->
                chips.addView(Btn(context, name, laneColor(lane.id)).apply {
                    icon = if (lane.isTrack) null else Sprites.icon(lane.id)
                    onClick = { FireEscape.place(id, lane.id, FireEscape.arr.playhead); tl.reveal(FireEscape.arr.playhead) }
                }, lp(WRAP, Px.dp(44)).apply { rightMargin = Px.dp(4) })
            }
        }
        strip.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(0, Px.dp(48), 1f))
        drawer.addView(strip, LayoutParams(MATCH, Px.dp(52)).apply { gravity = android.view.Gravity.BOTTOM; bottomMargin = Px.dp(48) })
        if (lane != null) Studio.message("Tap a ${if (lane.isTrack) "take" else "clip"} to lay it at the playhead on ${lane.title}")
    }

    private fun buildKeys() {
        val lane = FireEscape.selectedLaneObj()
        if (lane == null || lane.isTrack) { Studio.message(if (lane == null) "Tap a block's lane name first" else "Audio tracks record from their input: arm it and ● Rec"); drawerKind = ""; drawer.visibility = GONE; return }
        Studio.select(lane.id)
        val page: View = when (lane.id) {
            "minds" -> MindsPage(context)
            "pickup" -> PickupPage(context, host)
            else -> InstrumentPage(context, lane.id, host)
        }
        // the keys take the whole screen below the transport bar
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = Tv.WoodDrawable() }
        val head = bar(context)
        head.addView(Btn(context, "✕ Close", Pal.BROWN).apply { onClick = { closeDrawer() } }, lp(WRAP, Px.dp(32)))
        head.addView(Btn(context, "Playing ${lane.title}: ● Rec records it", laneColor(lane.id)).apply { icon = Sprites.icon(lane.id) }, lp(0, Px.dp(32), 1f).apply { leftMargin = Px.dp(6) })
        box.addView(head, lp(MATCH, Px.dp(36)))
        box.addView(page, lp(MATCH, 0, 1f))
        drawer.addView(box, LayoutParams(MATCH, MATCH))
    }

    private fun buildFx() {
        val lane = FireEscape.selectedLaneObj() ?: run { Studio.message("Tap a lane's name first"); drawerKind = ""; drawer.visibility = GONE; return }
        val fx = Audio.laneFx(lane.id)
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.alpha(Snes.INK, 0.86f)) }
        val head = bar(context)
        head.addView(Btn(context, "✕ Close", Pal.BROWN).apply { onClick = { closeDrawer() } }, lp(WRAP, Px.dp(40)))
        head.addView(Btn(context, "${lane.title} · effects", laneColor(lane.id)).apply { if (!lane.isTrack) icon = Sprites.icon(lane.id) }, lp(WRAP, Px.dp(40)).apply { leftMargin = Px.dp(6) })
        head.addView(spacer(context))
        if (fx.wraith.on.on) head.addView(Btn(context, "Wraith presets", Snes.PURPLE).apply { onClick = { wraithPresets(fx.wraith) } }, lp(WRAP, Px.dp(40)))
        head.addView(Btn(context, "Track…", Pal.AQUA).apply { onClick = { openLanePopup(lane) } }, lp(WRAP, Px.dp(40)).apply { leftMargin = Px.dp(4) })
        box.addView(head, lp(MATCH, Px.dp(44)))
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Px.dp(6), Px.dp(6), Px.dp(6), Px.dp(6)) }
        var k = 0
        fun panel(name: String, params: List<app.foxbox.audio.Param>, cols: Int, remove: (() -> Unit)? = null) {
            val accent = Snes.accent(k++)
            val p = Panel(context, name, accent = accent)
            val flow = Flow(context)
            params.forEach { flow.addView(Knob(context, it, accent)) }
            p.addView(flow, lp(Px.dp(74) * cols, WRAP))
            remove?.let { r -> p.addView(Btn(context, "Remove $name", Snes.RED).apply { onClick = { r(); Studio.save(); buildFxAgain(); Studio.changed() } }, lp(Px.dp(74) * cols, Px.dp(36)).apply { topMargin = Px.dp(4) }) }
            strip.addView(p, lp(WRAP, WRAP).apply { rightMargin = Px.dp(6) })
        }
        // only the effects added from the track's popup show here
        if (lane.isTrack && Audio.amps[lane.channel].on.on) strip.addView(AmpPanel(context, lane.channel, fire), lp(WRAP, WRAP).apply { rightMargin = Px.dp(6) })
        val hidden = setOf(fx.pcOn, fx.fOn, fx.cOn, fx.mOn, fx.wraith.on, fx.cSide, fx.mSide, fx.mBand)
        fun knobs(name: String) = fx.p.sections[name]?.filter { it !in hidden } ?: emptyList()
        fun box(name: String, cols: Int, remove: () -> Unit, build: (Panel, Int) -> Unit) {
            val accent = Snes.accent(k++)
            val pn = Panel(context, name, accent = accent)
            build(pn, accent)
            pn.addView(Btn(context, "Remove $name", Snes.RED).apply { onClick = { remove(); Studio.save(); buildFxAgain(); Studio.changed() } }, lp(Px.dp(74) * cols, Px.dp(34)).apply { topMargin = Px.dp(4) })
            strip.addView(pn, lp(WRAP, WRAP).apply { rightMargin = Px.dp(6) })
        }
        fun flow(params: List<app.foxbox.audio.Param>, accent: Int) = Flow(context).apply { params.forEach { addView(Knob(context, it, accent)) } }
        if (fx.pcOn.on) box("Pitch Correct", 4, { fx.pcOn.set(0f) }) { pn, acc -> pn.addView(flow(knobs("Pitch Correct"), acc), lp(Px.dp(74) * 4, WRAP)) }
        if (fx.fOn.on) box("Filter", 3, { fx.fOn.set(0f) }) { pn, acc -> pn.addView(flow(knobs("Filter"), acc), lp(Px.dp(74) * 3, WRAP)) }
        if (fx.cOn.on) box("Compressor", 3, { fx.cOn.set(0f) }) { pn, acc ->
            pn.addView(CompMeter(context, fx), lp(Px.dp(74) * 3, Px.dp(70)).apply { bottomMargin = Px.dp(4) })
            pn.addView(Btn(context, "Sidechain: ${sidechainName(lane)}", Snes.PURPLE).apply { onClick = { chooseSidechain(lane, band = false) { buildFxAgain() } } }, lp(Px.dp(74) * 3, Px.dp(36)).apply { bottomMargin = Px.dp(4) })
            pn.addView(flow(knobs("Compressor"), acc), lp(Px.dp(74) * 3, WRAP))
        }
        if (fx.mOn.on) box("Multiband", 5, { fx.mOn.set(0f) }) { pn, acc ->
            pn.addView(BandGraph(context, fx), lp(Px.dp(74) * 5, Px.dp(110)).apply { bottomMargin = Px.dp(4) })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val bk = fx.bandKey
            row.addView(Btn(context, "Sidechain: " + (if (bk < 0) "Off" else fx.sources.getOrElse(bk) { "?" }), Snes.PURPLE).apply { onClick = { chooseSidechain(lane, band = true) { buildFxAgain() } } }, lp(0, Px.dp(36), 1f))
            row.addView(Btn(context, "Keyed band: " + fx.mBand.text(), Pal.AQUA).apply {
                onClick = { fire.choose("Which band does the sidechain push down?", fx.mBand.choices, fx.mBand.index) { i -> fx.mBand.set(i.toFloat()); Studio.save(); buildFxAgain() } }
            }, lp(0, Px.dp(36), 1f).apply { leftMargin = Px.dp(4) })
            pn.addView(row, lp(Px.dp(74) * 5, Px.dp(38)).apply { bottomMargin = Px.dp(4) })
            pn.addView(flow(knobs("Multiband"), acc), lp(Px.dp(74) * 5, WRAP))
        }
        if (fx.wraith.on.on) fx.wraith.p.sections.forEach { (name, params) ->
            val ps = params.filter { it !== fx.wraith.on }
            val cols = if (ps.size > 9) 5 else (ps.size + 1) / 2
            if (name == "Wraith") box(name, cols, { fx.wraith.on.set(0f) }) { pn, acc -> pn.addView(flow(ps, acc), lp(Px.dp(74) * cols, WRAP)) }
            else { val pn = Panel(context, name, accent = Snes.accent(k++)); pn.addView(flow(ps, Snes.accent(k)), lp(Px.dp(74) * cols, WRAP)); strip.addView(pn, lp(WRAP, WRAP).apply { rightMargin = Px.dp(6) }) }
        }
        // the Flowers effects that were added
        fx.flowerFx.filter { it.toggle.on }.forEach { ff ->
            val cols = maxOf(2, (ff.params.size + 1) / 2)
            box(ff.name, cols, { ff.toggle.set(0f) }) { pn, acc -> pn.addView(flow(ff.params, acc), lp(Px.dp(74) * cols, WRAP)) }
        }
        if (strip.childCount == 0) strip.addView(Btn(context, "No effects on ${lane.title} yet: tap Track… (or the track's name) to add some", Pal.BROWN_L).apply { onClick = { openLanePopup(lane) } }, lp(WRAP, Px.dp(44)))
        box.addView(HorizontalScrollView(context).apply { addView(android.widget.ScrollView(context).apply { addView(strip) }) }, lp(MATCH, 0, 1f))
        drawer.addView(box, LayoutParams(MATCH, MATCH).apply { bottomMargin = Px.dp(48) })
    }

    private fun buildFxAgain() { drawer.removeAllViews(); buildFx() }

    /** After an import: show the new choice. */
    fun ampChanged() { if (drawerKind == "fx") buildFxAgain() }

    private fun wraithPresets(w: app.foxbox.fx.Wraith) {
        val list = app.foxbox.data.WraithPresets.list()
        fire.choose("Wraith presets", list.map { it.name + if (it.factory) "" else " ★" } + "Save these settings as a preset…" + "Delete a preset…") { i ->
            when {
                i < list.size -> { app.foxbox.data.WraithPresets.apply(w, list[i]); Studio.save(); Studio.message("Wraith: ${list[i].name}"); buildFxAgain() }
                i == list.size -> fire.textDialog("Preset name", "My Wraith") { name -> app.foxbox.data.WraithPresets.save(w, name); Studio.message("Saved Wraith preset $name") }
                else -> {
                    val mine = list.filter { !it.factory }
                    if (mine.isEmpty()) Studio.message("Only your own presets can be deleted") else fire.choose("Delete which preset?", mine.map { it.name }) { j -> app.foxbox.data.WraithPresets.delete(mine[j]) }
                }
            }
        }
    }

    fun back(): Boolean { if (drawerKind.isNotEmpty()) { closeDrawer(); return true }; return false }

    // ------------------------------------------------------------------ the position readout

    private inner class PositionView(c: Context) : PixelView(c) {
        private val p = Px.text(13f, Pal.CREAM)
        init { animated = true }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Px.dp(70), Px.dp(44))
        override fun paint(c: Canvas) {
            c.pixelBox(u, u, width - u, height - u, if (FireEscape.recording) Pal.ORANGE_D else Tv.BEZEL)
            val t = FireEscape.position
            val bar = Math.floorDiv(t.toLong(), Ticks.BAR.toLong()) + 1; val beat = Math.floorMod(Math.floorDiv(t.toLong(), Ticks.PPQ.toLong()), 4L) + 1
            p.textSize = Px.dp(13f)
            c.textFit("$bar.$beat", width / 2f, height / 2f, width - Px.dp(8f), p)
        }
    }

    // ------------------------------------------------------------------ the timeline

    @SuppressLint("ClickableViewAccessibility")
    private inner class Timeline(c: Context) : PixelView(c) {
        init { animated = true }
        private val headerW get() = Px.dp(if (narrow) 84f else 104f)
        private val rulerH get() = Px.dp(24f)
        /** Pixels per tick, and the tick at the timeline's left edge. */
        private var ppt = 0f
        private var left = 0f
        private var top = 0f
        private val p = Paint()
        private val txt = Px.text(11f, Pal.CREAM, headFont = false, align = Paint.Align.LEFT)
        private val head = Px.text(11f, Pal.CREAM, headFont = true, align = Paint.Align.LEFT)
        private val small = Px.text(9f, Pal.CREAM, headFont = false, align = Paint.Align.LEFT)
        private val waves = HashMap<String, FloatArray>()

        private fun lanes() = FireEscape.lanes()
        private val addH get() = if (FireEscape.arr.tracks.size < 3) Px.dp(30f) else 0f
        private fun laneH(n: Int) = ((height - rulerH - addH) / max(1, n)).coerceIn(Px.dp(40f), Px.dp(76f))
        private fun x(t: Float) = headerW + (t - left) * ppt
        private fun tickAt(x: Float) = ((x - headerW) / ppt + left).toInt()
        private fun laneY(i: Int, lh: Float) = rulerH + i * lh - top

        fun zoomBy(k: Float, fx: Float = headerW + (width - headerW) / 2f) {
            val at = (fx - headerW) / ppt + left
            ppt = (ppt * k).coerceIn(minPpt(), Px.dp(2f))
            left = max(0f, at - (fx - headerW) / ppt)
            invalidate()
        }
        private fun minPpt() = (width - headerW) / (Ticks.BAR * 160f)

        fun reveal(t: Int) {
            val w = (width - headerW) / ppt
            if (t < left || t > left + w * 0.9f) left = max(0f, t - w * 0.2f)
        }

        private fun color(id: String) = laneColor(id)

        override fun paint(c: Canvas) {
            if (ppt == 0f) ppt = (width - headerW) / (Ticks.BAR * 8f)
            val ls = lanes()
            val lh = laneH(ls.size)
            val maxTop = max(0f, rulerH + ls.size * lh - height)
            top = top.coerceIn(0f, maxTop)
            val arr = FireEscape.arr
            val pos = FireEscape.position.toFloat()
            // keep the playhead in view while playing
            if (FireEscape.playing && !dragging) {
                val w = (width - headerW) / ppt
                if (pos > left + w * 0.92f || pos < left) left = max(0f, pos - w * 0.1f)
            }
            // the lanes' dark glass over the wall, and the grid
            for ((i, lane) in ls.withIndex()) {
                val y = laneY(i, lh)
                if (y + lh < rulerH || y > height) continue
                val selLane = lane.id == FireEscape.selectedLane
                p.color = Pal.alpha(0xFF0E0C16.toInt(), if (selLane) 0.62f else 0.5f)
                c.drawRect(headerW, y + u, width.toFloat(), y + lh - u, p)
                if (lane.id in arr.muted) { p.color = Pal.alpha(0xFF000000.toInt(), 0.35f); c.drawRect(headerW, y, width.toFloat(), y + lh, p) }
            }
            drawGrid(c, ls.size, lh)
            // loop region
            if (arr.loopOn) {
                p.color = Pal.alpha(Snes.YELLOW, 0.12f)
                c.drawRect(max(headerW, x(arr.loopStart.toFloat())), rulerH, min(width.toFloat(), x(arr.loopEnd.toFloat())), height.toFloat(), p)
            }
            // the items: posters pasted on the wall
            for (it in arr.items) {
                val li = ls.indexOfFirst { l -> l.id == it.lane }
                if (li < 0) continue
                val y = laneY(li, lh)
                val x0 = x(it.start.toFloat()); val x1 = x(it.end.toFloat())
                if (x1 < headerW || x0 > width || y + lh < rulerH || y > height) continue
                drawItem(c, it, x0, y + Px.dp(3f), x1, y + lh - Px.dp(3f), it.id == FireEscape.selected, it.lane in arr.muted)
            }
            // the playhead and the record mark
            val px = x(pos)
            if (px >= headerW) {
                p.color = if (FireEscape.recording) Pal.ORANGE else 0xFFFF4A4A.toInt()
                c.drawRect(px - u * 0.5f, rulerH, px + u * 0.5f, height.toFloat(), p)
            }
            drawRuler(c, pos)
            drawHeaders(c, ls, lh)
            drawCard(c)
        }

        private fun drawGrid(c: Canvas, n: Int, lh: Float) {
            val beatPx = Ticks.PPQ * ppt
            val step = when { beatPx > Px.dp(14f) -> Ticks.PPQ; beatPx * 4 > Px.dp(14f) -> Ticks.BAR; beatPx * 16 > Px.dp(14f) -> Ticks.BAR * 4; else -> Ticks.BAR * 16 }
            var t = (left.toInt() / step) * step
            val bottom = min(height.toFloat(), rulerH + n * lh - top)
            while (x(t.toFloat()) < width) {
                val xx = x(t.toFloat())
                if (xx >= headerW) {
                    p.color = if (t % Ticks.BAR == 0) Pal.alpha(Pal.CREAM, 0.18f) else Pal.alpha(Pal.CREAM, 0.07f)
                    c.drawRect(xx, rulerH, xx + max(1f, u * 0.4f), bottom, p)
                }
                t += step
            }
        }

        private fun drawRuler(c: Canvas, pos: Float) {
            p.color = Pal.alpha(0xFF16121E.toInt(), 0.9f)
            c.drawRect(headerW, 0f, width.toFloat(), rulerH, p)
            val arr = FireEscape.arr
            if (arr.loopOn) { p.color = Snes.YELLOW; c.drawRect(max(headerW, x(arr.loopStart.toFloat())), rulerH - Px.dp(5f), min(width.toFloat(), x(arr.loopEnd.toFloat())), rulerH, p) }
            val barPx = Ticks.BAR * ppt
            val every = when { barPx > Px.dp(40f) -> 1; barPx > Px.dp(18f) -> 2; barPx > Px.dp(8f) -> 4; barPx > Px.dp(3f) -> 8; else -> 16 }
            var b = (left / Ticks.BAR).toInt()
            b -= b % every
            small.color = Pal.CREAM
            while (x((b * Ticks.BAR).toFloat()) < width) {
                val xx = x((b * Ticks.BAR).toFloat())
                if (xx >= headerW) {
                    p.color = Pal.alpha(Pal.CREAM, 0.6f); c.drawRect(xx, rulerH * 0.45f, xx + u * 0.5f, rulerH, p)
                    c.drawText("${b + 1}", xx + Px.dp(3f), rulerH * 0.62f, small)
                }
                b += every
            }
            // the playhead's flag
            val ph = x(arr.playhead.toFloat())
            if (ph >= headerW) { p.color = Pal.ORANGE; c.drawRect(ph - Px.dp(4f), 0f, ph + Px.dp(4f), Px.dp(7f), p) }
            val px = x(pos)
            if (px >= headerW) { p.color = 0xFFFF4A4A.toInt(); c.drawRect(px - Px.dp(3f), rulerH - Px.dp(8f), px + Px.dp(3f), rulerH, p) }
        }

        private fun drawHeaders(c: Canvas, ls: List<FireEscape.Lane>, lh: Float) {
            p.color = 0xFF1A1622.toInt()
            c.drawRect(0f, 0f, headerW, height.toFloat(), p)
            for ((i, lane) in ls.withIndex()) {
                val y = laneY(i, lh)
                if (y + lh < rulerH || y > height) continue
                val sel = lane.id == FireEscape.selectedLane
                val col = color(lane.id)
                c.pixelBox(u, max(rulerH, y + u), headerW - u, y + lh - u, if (sel) mixColor(col, 0xFF000000.toInt(), 0.35f) else 0xFF2A2433.toInt())
                p.color = col; c.drawRect(u * 2, y + u * 2, u * 2 + Px.dp(4f), y + lh - u * 2, p)
                head.color = Pal.CREAM; head.textSize = Px.dp(11f)
                val nameW = headerW - Px.dp(14f)
                c.drawText(fitText(lane.title, nameW, head, 8f), Px.dp(10f), y + Px.dp(15f), head)
                // badges: armed, muted, effects (tap the name for the track's popup), then a level bar
                val bw = Px.dp(22f); val by = y + lh - Px.dp(18f)
                val armed = FireEscape.isArmed(lane.id)
                c.pixelBox(Px.dp(10f), by, Px.dp(10f) + bw * 0.7f, by + Px.dp(14f), if (armed) Snes.RED else 0xFF4A4256.toInt())
                small.color = Pal.CREAM; small.textAlign = Paint.Align.CENTER
                c.drawText("●", Px.dp(10f) + bw * 0.35f, by + Px.dp(10.5f), small)
                val muted = lane.id in FireEscape.arr.muted
                val mx = Px.dp(12f) + bw * 0.7f
                c.pixelBox(mx, by, mx + bw * 0.7f, by + Px.dp(14f), if (muted) Snes.YELLOW else 0xFF4A4256.toInt())
                c.drawText("M", mx + bw * 0.35f, by + Px.dp(10.5f), small)
                val fxOn = Audio.laneFx(lane.id).active || (lane.isTrack && Audio.amps[lane.channel].on.on)
                val fx0 = mx + bw * 0.7f + Px.dp(2f)
                c.pixelBox(fx0, by, fx0 + bw, by + Px.dp(14f), if (fxOn) Snes.PURPLE else 0xFF4A4256.toInt())
                c.drawText("FX", fx0 + bw / 2, by + Px.dp(10.5f), small)
                small.textAlign = Paint.Align.LEFT
                val lx = fx0 + bw + Px.dp(4f); val lw = headerW - lx - Px.dp(6f)
                if (lw > Px.dp(10f) && lh > Px.dp(46f)) {
                    p.color = 0xFF0E0C14.toInt(); c.drawRect(lx, by + Px.dp(4f), lx + lw, by + Px.dp(10f), p)
                    p.color = col; c.drawRect(lx, by + Px.dp(4f), lx + lw * levelOf(lane), by + Px.dp(10f), p)
                } else if (lh > Px.dp(46f) || lw > 0) { /* too small for the level bar */ }
                // Wraith's breath
                val wr = Audio.laneFx(lane.id).wraith
                if (wr.on.on && wr.openness > 0.02f) { p.color = Pal.alpha(0xFFB9A8FF.toInt(), 0.5f * wr.openness); c.drawRect(headerW - Px.dp(4f), y + u * 2, headerW - u, y + lh - u * 2, p) }
            }
            // add an audio track (they only appear once added)
            if (FireEscape.arr.tracks.size < 3) {
                val y = laneY(ls.size, lh)
                if (y < height) {
                    c.pixelBox(u * 2, y + u * 2, headerW - u * 2, y + addH - u * 2, 0xFF2E5A40.toInt())
                    small.color = Pal.CREAM; small.textAlign = Paint.Align.CENTER
                    c.drawText("+ Audio track", headerW / 2, y + addH / 2 + Px.dp(3.5f), small)
                    small.textAlign = Paint.Align.LEFT
                }
            }
        }


        private fun drawItem(c: Canvas, it: ArrItem, x0: Float, y0: Float, x1: Float, y1: Float, sel: Boolean, muted: Boolean) {
            val col = color(it.lane)
            val clip = FireEscape.clipOf(it); val take = FireEscape.takeOf(it)
            val faded = muted || clip?.muted == true || take?.muted == true
            val l = max(x0, headerW); val r = max(l + u, x1)
            // the poster: paper in the lane's colour, with a darker edge and a strip of tape at the top
            c.pixelBox(l, y0, r, y1, Pal.alpha(mixColor(col, Pal.PAPER, 0.25f), if (faded) 0.4f else 0.92f))
            p.color = Pal.alpha(0xFFEDE6D0.toInt(), 0.7f)
            val tapeW = min(Px.dp(16f), (r - l) * 0.4f)
            if (x0 >= headerW && r - l > Px.dp(24f)) c.drawRect(l + Px.dp(6f), y0 - Px.dp(2f), l + Px.dp(6f) + tapeW, y0 + Px.dp(4f), p)
            // what's inside: notes, or the take's waveform
            val inner = RectF(l + Px.dp(4f), y0 + Px.dp(15f), r - Px.dp(4f), y1 - Px.dp(4f))
            if (clip != null && inner.height() > Px.dp(6f)) {
                val notes = clip.notes
                if (notes.isNotEmpty()) {
                    val lo = notes.minOf { n -> n.note }; val hi = max(lo + 6, notes.maxOf { n -> n.note })
                    p.color = Pal.alpha(Pal.BROWN_D, 0.8f)
                    val len = clip.lengthTicks.coerceAtLeast(1)
                    var rep = 0
                    while (it.start - it.offset + rep * len < it.end) {
                        val base = it.start - it.offset + rep * len
                        for (n in notes) {
                            val s = base + n.tick
                            if (s + n.dur < it.start || s >= it.end) continue
                            val nx0 = max(inner.left, x(s.toFloat())); val nx1 = min(inner.right, x((s + n.dur).toFloat()))
                            if (nx1 <= nx0 || nx0 > width) continue
                            val ny = inner.bottom - (n.note - lo) / (hi - lo).toFloat() * inner.height()
                            c.drawRect(nx0, ny - u * 0.6f, max(nx0 + u, nx1), ny + u * 0.6f, p)
                        }
                        // where the clip repeats
                        if (rep > 0) { val rx = x(base.toFloat()); if (rx > inner.left && rx < inner.right) { p.color = Pal.alpha(Pal.BROWN_D, 0.35f); c.drawRect(rx, inner.top, rx + u * 0.5f, inner.bottom, p); p.color = Pal.alpha(Pal.BROWN_D, 0.8f) } }
                        rep++
                        if (rep > 2000) break
                    }
                }
            } else if (take != null && inner.height() > Px.dp(6f)) {
                val w = waves.getOrPut(take.id + take.data.size) { peaks(take.data) }
                p.color = Pal.alpha(Pal.BROWN_D, 0.75f)
                val tpf = FireEscape.takeTicks(take).toFloat().coerceAtLeast(1f)
                var xx = inner.left
                while (xx < inner.right) {
                    val tk = tickAt(xx) - it.start + it.offset
                    val k = (tk / tpf * w.size).toInt()
                    if (k in w.indices) { val a = w[k] * inner.height() / 2; c.drawRect(xx, inner.centerY() - a, xx + u, inner.centerY() + a, p) }
                    xx += u
                }
            }
            txt.color = Pal.BROWN_D; txt.textSize = Px.dp(11f)
            if (r - l > Px.dp(18f)) c.drawText(fitText(FireEscape.nameOf(it), r - l - Px.dp(8f), txt, 7f), l + Px.dp(5f), y0 + Px.dp(12f), txt)
            if (sel) {
                p.color = Pal.CREAM; p.style = Paint.Style.STROKE; p.strokeWidth = u
                c.drawRect(l, y0, r, y1, p); p.style = Paint.Style.FILL
                // handles at both ends: drag to trim the start or stretch the end
                p.color = Pal.CREAM
                if (x0 >= headerW) c.drawRect(x0, y0 + (y1 - y0) * 0.3f, x0 + Px.dp(5f), y1 - (y1 - y0) * 0.3f, p)
                c.drawRect(x1 - Px.dp(5f), y0 + (y1 - y0) * 0.3f, x1, y1 - (y1 - y0) * 0.3f, p)
            }
        }

        private fun peaks(d: FloatArray): FloatArray {
            val n = 512; if (d.isEmpty()) return FloatArray(n)
            val per = max(1, d.size / n)
            val out = FloatArray(n) { k -> var pk = 0f; var i = k * per; val e = min(d.size, i + per); while (i < e) { pk = max(pk, abs(d[i])); i += 4 }; pk }
            val mx = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
            for (k in out.indices) out[k] = out[k] / mx
            return out
        }

        // ---- the selected clip's card: volume and pan (shared with Clibbins)
        private val card = RectF(); private val volBar = RectF(); private val panBar = RectF()
        private fun drawCard(c: Canvas) {
            val it = FireEscape.item(FireEscape.selected) ?: run { card.setEmpty(); return }
            val w = Px.dp(150f); val h = Px.dp(64f)
            card.set(width - w - Px.dp(6f), height - h - Px.dp(6f), width - Px.dp(6f), height - Px.dp(6f))
            c.pixelBox(card.left, card.top, card.right, card.bottom, 0xF0201A2A.toInt())
            small.color = Pal.CREAM; small.textAlign = Paint.Align.LEFT
            c.drawText(fitText(FireEscape.nameOf(it), w - Px.dp(12f), small, 7f), card.left + Px.dp(6f), card.top + Px.dp(12f), small)
            val vol = Studio.volumeOf(it.ref); val pan = Studio.panOf(it.ref)
            volBar.set(card.left + Px.dp(34f), card.top + Px.dp(20f), card.right - Px.dp(8f), card.top + Px.dp(34f))
            panBar.set(card.left + Px.dp(34f), card.top + Px.dp(42f), card.right - Px.dp(8f), card.top + Px.dp(56f))
            c.drawText("Vol", card.left + Px.dp(6f), volBar.centerY() + Px.dp(3.5f), small)
            c.drawText("Pan", card.left + Px.dp(6f), panBar.centerY() + Px.dp(3.5f), small)
            p.color = 0xFF0E0C14.toInt(); c.drawRect(volBar, p); c.drawRect(panBar, p)
            p.color = color(it.lane); c.drawRect(volBar.left, volBar.top, volBar.left + volBar.width() * vol, volBar.bottom, p)
            val px = panBar.centerX() + pan * panBar.width() / 2
            p.color = Pal.alpha(Pal.CREAM, 0.3f); c.drawRect(panBar.centerX() - u * 0.5f, panBar.top, panBar.centerX() + u * 0.5f, panBar.bottom, p)
            p.color = color(it.lane); c.drawRect(px - Px.dp(4f), panBar.top, px + Px.dp(4f), panBar.bottom, p)
        }

        // ------------------------------------------------------------------ touch

        private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
        private var moved = false
        private var dragging = false
        private var mode = 0  // 1 move item, 2 stretch end, 3 trim start, 4 scroll, 5 loop drag, 6 level, 7 vol, 8 pan
        private var dragItem: ArrItem? = null
        private var origStart = 0; private var origLen = 0
        private var loopFrom = 0
        private var dragLane: FireEscape.Lane? = null
        private var longFired = false
        private val longPress = Runnable { longFired = true; onLong() }
        private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor, d.focusX); return true }
        })

        private fun laneIndexAt(y: Float): Int { val ls = lanes(); val lh = laneH(ls.size); return ((y - rulerH + top) / lh).toInt().takeIf { y >= rulerH && it in ls.indices } ?: -1 }

        private fun itemAt(x: Float, y: Float): ArrItem? {
            val li = laneIndexAt(y); if (li < 0) return null
            val lane = lanes()[li]
            val t = (x - headerW) / ppt + left
            val slop = Px.dp(6f) / ppt
            return FireEscape.arr.items.lastOrNull { it.lane == lane.id && t >= it.start - slop && t <= it.end + slop }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaler.onTouchEvent(e)
            if (e.pointerCount > 1) { removeCallbacks(longPress); mode = 0; moved = true; return true }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; moved = false; longFired = false; mode = 0; dragItem = null; dragging = false
                    if (!card.isEmpty && card.contains(e.x, e.y)) { mode = if (e.y < (volBar.bottom + panBar.top) / 2) 7 else 8; cardDrag(e.x); return true }
                    if (e.x < headerW && e.y >= rulerH) {
                        val li = laneIndexAt(e.y)
                        if (li >= 0) {
                            val ls = lanes(); val lh = laneH(ls.size); val y = laneY(li, lh)
                            val bw = Px.dp(22f); val lx = Px.dp(18f) + bw * 2.4f
                            if (e.y > y + lh - Px.dp(20f) && e.x > lx) { mode = 6; dragLane = ls[li] }
                        }
                    }
                    postDelayed(longPress, 480)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    if (!moved && (abs(e.x - downX) > Px.dp(8f) || abs(e.y - downY) > Px.dp(8f))) {
                        moved = true; removeCallbacks(longPress)
                        if (mode == 0) startDrag()
                    }
                    if (moved || mode in 5..8) drag(e.x, e.y, dx, dy)
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (!moved && !longFired && mode !in 5..8) tap(e.x, e.y)
                    if (dragging && (mode == 1 || mode == 2 || mode == 3)) FireEscape.committed()
                    if (mode in 6..8) Studio.save()
                    dragging = false; mode = 0; dragItem = null
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); dragging = false; mode = 0 }
            }
            return true
        }

        private fun startDrag() {
            dragging = true
            if (downY < rulerH && downX > headerW) { mode = 4; return }
            if (downX < headerW) { mode = 4; return }
            val it = itemAt(downX, downY)
            if (it != null && it.id == FireEscape.selected) {
                val edge = Px.dp(14f)
                val x0 = x(it.start.toFloat()); val x1 = x(it.end.toFloat())
                mode = when { abs(downX - x1) < edge -> 2; abs(downX - x0) < edge && x0 >= headerW -> 3; else -> 1 }
                dragItem = it; origStart = it.start; origLen = it.length
            } else if (it != null) {
                FireEscape.selected = it.id; dragItem = it; origStart = it.start; origLen = it.length; mode = 1
                FireEscape.selectedLane = it.lane
            } else mode = 4
        }

        private fun drag(xx: Float, yy: Float, dx: Float, dy: Float) {
            when (mode) {
                1 -> dragItem?.let { it ->
                    val dt = ((xx - downX) / ppt).toInt()
                    val li = laneIndexAt(yy)
                    FireEscape.move(it, (origStart + dt).coerceAtLeast(0), if (li >= 0) lanes()[li].id else it.lane)
                }
                2 -> dragItem?.let { it -> FireEscape.resize(it, origLen + ((xx - downX) / ppt).toInt()) }
                3 -> dragItem?.let { it -> FireEscape.trimStart(it, origStart + ((xx - downX) / ppt).toInt()) }
                4 -> { left = max(0f, left - dx / ppt); top += -dy; invalidate() }
                5 -> FireEscape.setLoop(loopFrom, tickAt(xx).coerceAtLeast(0))
                6 -> dragLane?.let { lane -> val bw = Px.dp(22f); val lx = Px.dp(18f) + bw * 2.4f; val lw = headerW - lx - Px.dp(6f); setLevel(lane, ((xx - lx) / lw).coerceIn(0f, 1f)); invalidate() }
                7, 8 -> cardDrag(xx)
            }
        }

        private fun cardDrag(xx: Float) {
            val it = FireEscape.item(FireEscape.selected) ?: return
            if (mode == 7) Studio.setVolume(it.ref, ((xx - volBar.left) / volBar.width()).coerceIn(0f, 1f))
            else { var v = ((xx - panBar.centerX()) / (panBar.width() / 2)).coerceIn(-1f, 1f); if (abs(v) < 0.06f) v = 0f; Studio.setPan(it.ref, v) }
            invalidate()
        }

        private fun tap(xx: Float, yy: Float) {
            if (yy < rulerH) { if (xx > headerW) FireEscape.setPlayhead(tickAt(xx).coerceAtLeast(0)); return }
            if (xx < headerW && FireEscape.arr.tracks.size < 3) {
                val ls = lanes(); val y = laneY(ls.size, laneH(ls.size))
                if (yy in y..y + addH) { FireEscape.addTrack(); return }
            }
            val li = laneIndexAt(yy); if (li < 0) return
            val lane = lanes()[li]
            if (xx < headerW) {
                // the track's popup: arm, mute, volume, pan, sidechain and its effects
                FireEscape.selectedLane = lane.id
                if (drawerKind == "crate" || drawerKind == "keys" || drawerKind == "fx") { val k = drawerKind; closeDrawer(); toggleDrawer(k) }
                openLanePopup(lane)
                Studio.changed(); return
            }
            val it = itemAt(xx, yy)
            FireEscape.selectedLane = lane.id
            if (it != null) FireEscape.selected = it.id
            else { FireEscape.selected = null; FireEscape.setPlayhead(tickAt(xx).coerceAtLeast(0)) }
            Studio.changed()
        }

        private fun onLong() {
            if (downY < rulerH && downX > headerW) {
                // hold on the ruler and drag: the loop region
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                loopFrom = tickAt(downX).coerceAtLeast(0); mode = 5; FireEscape.arr.loopOn = true
                FireEscape.setLoop(loopFrom, loopFrom + Ticks.BAR)
                return
            }
            val it = itemAt(downX, downY) ?: return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            FireEscape.selected = it.id; Studio.changed()
            fire.choose(FireEscape.nameOf(it), listOf("Copy", "Duplicate after it", "Split at the playhead", "Loop this", "Remove from the Fire Escape")) { i ->
                when (i) {
                    0 -> FireEscape.copy(it)
                    1 -> FireEscape.duplicate(it)
                    2 -> FireEscape.split(it, FireEscape.arr.playhead)
                    3 -> { FireEscape.arr.loopOn = false; FireEscape.selected = it.id; FireEscape.toggleLoop() }
                    4 -> FireEscape.delete(it)
                }
            }
        }
    }

    // ------------------------------------------------------------------ a lane's popup

    /** A lane's volume (the same as its Studio / Clibbins level), 0..1. */
    private fun levelOf(lane: FireEscape.Lane) = if (lane.isTrack) Audio.channelLevel[lane.channel] else lane.engine?.volume?.value ?: 0f
    private fun setLevel(lane: FireEscape.Lane, v: Float) {
        if (lane.isTrack) Studio.setTrackLevel(lane.channel, v) else lane.engine?.volume?.set(v)
    }
    private fun dbText(g: Float) = if (g < 0.0005f) "-inf dB" else "%.1f dB".format(20f * kotlin.math.log10(g))
    private fun sidechainName(lane: FireEscape.Lane): String {
        val k = Audio.laneFx(lane.id).compKey
        return if (k < 0) "Off" else Audio.laneFx(lane.id).sources.getOrElse(k) { "?" }
    }

    /** Pick where the lane's compressor (or multiband) listens. */
    private fun chooseSidechain(lane: FireEscape.Lane, band: Boolean = false, then: () -> Unit = {}) {
        val fx = Audio.laneFx(lane.id)
        val others = Audio.laneIds.indices.filter { Audio.laneIds[it] != lane.id && FireEscape.lane(Audio.laneIds[it]) != null }
        val cur = if (band) fx.bandKey else fx.compKey
        fire.choose("${lane.title} ${if (band) "multiband" else "compressor"}: sidechain from", listOf("Off (listens to itself)") + others.map { fx.sources[it] }, if (cur < 0) 0 else others.indexOf(cur) + 1) { i ->
            val k = if (i == 0) -1 else others[i - 1]
            if (band) fx.mSide.set((k + 1).toFloat()) else fx.cSide.set((k + 1).toFloat())
            if (!band && k >= 0 && !fx.cOn.on) { fx.cOn.set(1f); Studio.message("Added a Compressor to ${lane.title}, keyed by ${fx.sources[k]}") }
            Studio.save(); Studio.changed(); then()
        }
    }

    fun openLanePopup(lane: FireEscape.Lane) {
        val a = context as? android.app.Activity ?: return
        val fx = Audio.laneFx(lane.id)
        val d = SnesDialog(a, lane.title, wide = 0.8f)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun rebuild() {
            box.removeAllViews()
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Btn(a, "● Arm", Snes.RED).apply { on = FireEscape.isArmed(lane.id); onClick = { FireEscape.toggleArm(lane.id); rebuild() } }, lp(0, Px.dp(42), 1f))
            row.addView(Btn(a, "Mute", Snes.YELLOW).apply { on = FireEscape.isMuted(lane.id); onClick = { FireEscape.toggleMute(lane.id); rebuild() } }, lp(0, Px.dp(42), 1f).apply { leftMargin = Px.dp(4) })
            if (lane.isTrack) row.addView(Btn(a, if (fx.stereo.on) "Stereo" else "Mono", Pal.AQUA).apply { on = fx.stereo.on; onClick = { FireEscape.toggleStereo(lane.channel); rebuild() } }, lp(0, Px.dp(42), 1f).apply { leftMargin = Px.dp(4) })
            box.addView(row, lp(MATCH, Px.dp(44)))
            box.addView(Slider(a, "Volume", { levelOf(lane) }, { setLevel(lane, it) }, { dbText(it) }), lp(MATCH, Px.dp(44)).apply { topMargin = Px.dp(6) })
            box.addView(Slider(a, "Pan", { (fx.pan.value + 1f) / 2f }, { v -> var x = v * 2f - 1f; if (abs(x) < 0.05f) x = 0f; fx.pan.set(x) },
                { val v = fx.pan.value; if (abs(v) < 0.01f) "Centre" else (if (v < 0) "L " else "R ") + (abs(v) * 100).toInt() }), lp(MATCH, Px.dp(44)).apply { topMargin = Px.dp(4) })
            // effects: chosen from a drop-down, then they appear in the effects panel
            val all = fx.effects + if (lane.isTrack) listOf("Amp (NAM)" to Audio.amps[lane.channel].on) else emptyList()
            val added = all.filter { it.second.on }
            val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            if (added.isEmpty()) list.addView(Btn(a, "No effects yet", Pal.BROWN_L), lp(MATCH, Px.dp(36)))
            added.forEach { (name, on) ->
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(Btn(a, "✓ $name", Pal.GREEN).apply { this.on = true; onClick = { d.dismiss(); FireEscape.selectedLane = lane.id; if (drawerKind == "fx") buildFxAgain() else toggleDrawer("fx") } }, lp(0, Px.dp(36), 1f))
                r.addView(Btn(a, "✕", Snes.RED, 36).apply { onClick = { on.set(0f); Studio.save(); Studio.changed(); if (drawerKind == "fx") buildFxAgain(); rebuild() } }, lp(WRAP, Px.dp(36)).apply { leftMargin = Px.dp(4) })
                list.addView(r, lp(MATCH, Px.dp(38)))
            }
            list.addView(Btn(a, "+ Add effect ▾", Snes.PURPLE).apply {
                onClick = {
                    val free = all.filter { !it.second.on }
                    if (free.isEmpty()) Studio.message("Every effect is on ${lane.title} already")
                    else fire.choose("Add an effect to ${lane.title}", free.map { it.first }) { i ->
                        free[i].second.set(1f); Studio.save(); Studio.changed(); if (drawerKind == "fx") buildFxAgain(); rebuild()
                    }
                }
            }, lp(MATCH, Px.dp(40)).apply { topMargin = Px.dp(4) })
            box.addView(Panel(a, "Effects", accent = Snes.PURPLE).apply { addView(list, lp(MATCH, WRAP)) }, lp(MATCH, WRAP).apply { topMargin = Px.dp(6) })
            val row2 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(Btn(a, "Edit effects", Snes.PURPLE).apply { onClick = { d.dismiss(); FireEscape.selectedLane = lane.id; if (drawerKind == "fx") buildFxAgain() else toggleDrawer("fx") } }, lp(0, Px.dp(40), 1f))
            row2.addView(Btn(a, "Tuner", Pal.SUN).apply { onClick = { FireEscape.selectedLane = lane.id; openTuner() } }, lp(0, Px.dp(40), 1f).apply { leftMargin = Px.dp(4) })
            if (lane.isTrack) row2.addView(Btn(a, "Remove track", Snes.RED).apply { onClick = { d.dismiss(); FireEscape.removeTrack(lane.channel) } }, lp(0, Px.dp(40), 1f).apply { leftMargin = Px.dp(4) })
            box.addView(row2, lp(MATCH, Px.dp(42)).apply { topMargin = Px.dp(6) })
        }
        rebuild()
        d.view(box)
        d.key("Done", Snes.GREEN) { Studio.save() }
        d.onDismiss { Studio.save(); Studio.changed() }
        d.show()
    }

    /** A labelled horizontal slider with a readout. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class Slider(c: Context, val label: String, val get: () -> Float, val set: (Float) -> Unit, val text: (Float) -> String) : PixelView(c) {
        private val tp = Px.text(12f, Pal.BROWN_D, align = Paint.Align.LEFT)
        private val p = Paint()
        private val labelW get() = Px.dp(64f)
        private val textW get() = Px.dp(76f)
        override fun paint(c: Canvas) {
            c.pixelBox(u, u, width - u, height - u, Pal.CREAM)
            tp.textSize = Px.dp(12f); tp.textAlign = Paint.Align.LEFT
            c.textFit(label, Px.dp(8f) + (labelW - Px.dp(8f)) / 2 - Px.dp(2f), height / 2f, labelW - Px.dp(10f), tp.apply { textAlign = Paint.Align.CENTER })
            val l = labelW; val r = width - textW
            val v = get().coerceIn(0f, 1f)
            p.color = 0xFF3A3040.toInt(); c.drawRect(l, height / 2f - Px.dp(3f), r, height / 2f + Px.dp(3f), p)
            p.color = Pal.ORANGE; c.drawRect(l, height / 2f - Px.dp(3f), l + (r - l) * v, height / 2f + Px.dp(3f), p)
            val kx = l + (r - l) * v
            c.pixelBox(kx - Px.dp(6f), height * 0.2f, kx + Px.dp(6f), height * 0.8f, Pal.PAPER)
            tp.textAlign = Paint.Align.CENTER
            c.textFit(text(v), width - textW / 2, height / 2f, textW - Px.dp(8f), tp)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val l = labelW; val r = width - textW
            set(((e.x - l) / (r - l)).coerceIn(0f, 1f)); invalidate()
            if (e.actionMasked == MotionEvent.ACTION_UP) Studio.save()
            return true
        }
    }

    // ------------------------------------------------------------------ the mixer

    /** Every lane as a channel strip: arm, mute, stereo, effects, sidechain, pan, fader and a dB meter. Plus the master. */
    @SuppressLint("ClickableViewAccessibility")
    private inner class MixerView(c: Context) : PixelView(c) {
        init { animated = true }
        private val p = Paint()
        private val tp = Px.text(10f, Pal.CREAM, align = Paint.Align.CENTER)
        private val hold = HashMap<String, Float>()
        private val holdAt = HashMap<String, Long>()
        private var scroll = 0f
        private val stripW get() = Px.dp(96f)
        private val hits = ArrayList<MixHit>()

        private fun peakOf(lane: FireEscape.Lane?): Float = when {
            lane == null -> Audio.peak
            lane.isTrack -> Audio.channelPeak[lane.channel]
            else -> lane.engine?.meter ?: 0f
        }
        private fun db(x: Float) = if (x < 1e-5f) -100f else 20f * kotlin.math.log10(x)
        /** Meter position for a level in dB: -60 .. +6. */
        private fun meterY(d: Float, top: Float, bot: Float) = bot - ((d + 60f) / 66f).coerceIn(0f, 1f) * (bot - top)

        override fun paint(c: Canvas) {
            hits.clear()
            p.color = 0xD8141018.toInt(); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            val ls: List<FireEscape.Lane?> = FireEscape.lanes() + listOf<FireEscape.Lane?>(null)
            scroll = scroll.coerceIn(0f, max(0f, ls.size * stripW - width))
            for ((i, lane) in ls.withIndex()) {
                val x0 = i * stripW - scroll; val x1 = x0 + stripW - Px.dp(4f)
                if (x1 < 0 || x0 > width) continue
                strip(c, lane, x0 + Px.dp(2f), x1)
            }
        }

        private fun strip(c: Canvas, lane: FireEscape.Lane?, l: Float, r: Float) {
            val h = height.toFloat()
            val col = if (lane == null) Pal.CREAM else laneColor(lane.id)
            c.pixelBox(l, Px.dp(4f), r, h - Px.dp(4f), 0xFF262030.toInt())
            // name
            p.color = col; c.drawRect(l + u * 2, Px.dp(6f), r - u * 2, Px.dp(24f), p)
            tp.color = textOn(col); tp.textSize = Px.dp(11f)
            c.textFit(lane?.title ?: "Master", (l + r) / 2, Px.dp(15f), r - l - Px.dp(8f), tp)
            hits += MixHit(l, Px.dp(4f), r, Px.dp(26f), 0, lane)
            var y = Px.dp(28f)
            if (lane != null) {
                val fx = Audio.laneFx(lane.id)
                // arm / mute / stereo
                val bw = (r - l - Px.dp(8f)) / 3f
                fun key(i: Int, text: String, lit: Boolean, litCol: Int, kind: Int, enabled: Boolean = true) {
                    val kl = l + Px.dp(3f) + i * (bw + Px.dp(1f)); val kr = kl + bw
                    c.pixelBox(kl, y, kr, y + Px.dp(20f), if (!enabled) 0xFF2A2533.toInt() else if (lit) litCol else 0xFF4A4256.toInt())
                    tp.color = Pal.CREAM; tp.textSize = Px.dp(9f)
                    c.textFit(text, (kl + kr) / 2, y + Px.dp(10f), bw - Px.dp(2f), tp)
                    if (enabled) hits += MixHit(kl, y, kr, y + Px.dp(20f), kind, lane)
                }
                key(0, "ARM", FireEscape.isArmed(lane.id), Snes.RED, 1)
                key(1, "M", FireEscape.isMuted(lane.id), Snes.YELLOW, 2)
                key(2, if (lane.isTrack) (if (fx.stereo.on) "ST" else "MO") else "–", lane.isTrack && fx.stereo.on, Pal.AQUA, 3, lane.isTrack)
                y += Px.dp(23f)
                // effects and sidechain
                val n = fx.effects.count { it.second.on } + if (lane.isTrack && Audio.amps[lane.channel].on.on) 1 else 0
                c.pixelBox(l + Px.dp(3f), y, r - Px.dp(3f), y + Px.dp(20f), if (n > 0) Snes.PURPLE else 0xFF4A4256.toInt())
                tp.color = Pal.CREAM; tp.textSize = Px.dp(9f)
                c.textFit(if (n > 0) "FX ($n)" else "FX +", (l + r) / 2, y + Px.dp(10f), r - l - Px.dp(10f), tp)
                hits += MixHit(l, y, r, y + Px.dp(20f), 4, lane); y += Px.dp(23f)
                val sc = sidechainName(lane)
                c.pixelBox(l + Px.dp(3f), y, r - Px.dp(3f), y + Px.dp(20f), if (sc != "Off") 0xFF5A4A8A.toInt() else 0xFF4A4256.toInt())
                c.textFit("SC: $sc", (l + r) / 2, y + Px.dp(10f), r - l - Px.dp(10f), tp)
                hits += MixHit(l, y, r, y + Px.dp(20f), 5, lane); y += Px.dp(23f)
                // pan
                val pan = fx.pan.value
                p.color = 0xFF0E0C14.toInt(); c.drawRect(l + Px.dp(6f), y + Px.dp(6f), r - Px.dp(6f), y + Px.dp(12f), p)
                val px = (l + r) / 2 + pan * ((r - l) / 2 - Px.dp(8f))
                p.color = Pal.alpha(Pal.CREAM, 0.35f); c.drawRect((l + r) / 2 - u * 0.5f, y + Px.dp(4f), (l + r) / 2 + u * 0.5f, y + Px.dp(14f), p)
                c.pixelBox(px - Px.dp(5f), y + Px.dp(2f), px + Px.dp(5f), y + Px.dp(16f), col)
                hits += MixHit(l, y, r, y + Px.dp(18f), 6, lane); y += Px.dp(20f)
                tp.textSize = Px.dp(8.5f); tp.color = Pal.alpha(Pal.CREAM, 0.7f)
                c.drawText(if (abs(pan) < 0.01f) "C" else (if (pan < 0) "L" else "R") + (abs(pan) * 100).toInt(), (l + r) / 2, y + Px.dp(6f), tp)
                y += Px.dp(10f)
            } else y += Px.dp(119f)
            // fader (left) and meter (right), with dB readings
            val top = y + Px.dp(14f); val bot = h - Px.dp(26f)
            if (bot - top < Px.dp(30f)) return
            val level = if (lane == null) Audio.masterVolume else levelOf(lane)
            val pk = peakOf(lane)
            val key = lane?.id ?: "master"
            val now = System.currentTimeMillis()
            if (pk >= (hold[key] ?: 0f) || now - (holdAt[key] ?: 0L) > 1500) { hold[key] = pk; holdAt[key] = now }
            tp.textSize = Px.dp(9f); tp.color = if (db(hold[key] ?: 0f) > -0.5f) Snes.RED else Pal.CREAM
            c.drawText(if ((hold[key] ?: 0f) < 1e-4f) "-inf" else "%.1f".format(db(hold[key] ?: 0f)), l + (r - l) * 0.72f, y + Px.dp(9f), tp)
            // meter
            val ml = l + (r - l) * 0.6f; val mr = r - Px.dp(8f)
            p.color = 0xFF0A080E.toInt(); c.drawRect(ml, top, mr, bot, p)
            val d = db(pk)
            val my = meterY(d, top, bot)
            var yy = bot
            while (yy > my) {
                val dd = (bot - yy) / (bot - top) * 66f - 60f
                p.color = if (dd > -3f) Snes.RED else if (dd > -12f) Snes.YELLOW else Pal.GREEN
                c.drawRect(ml + u * 0.5f, max(my, yy - Px.dp(2f)), mr - u * 0.5f, yy - u * 0.4f, p)
                yy -= Px.dp(2.5f)
            }
            val hy = meterY(db(hold[key] ?: 0f), top, bot)
            p.color = Pal.CREAM; c.drawRect(ml, hy - u * 0.5f, mr, hy + u * 0.5f, p)
            // scale ticks
            tp.textSize = Px.dp(7.5f); tp.color = Pal.alpha(Pal.CREAM, 0.55f)
            for (mark in intArrayOf(6, 0, -6, -12, -24, -36, -48)) {
                val ty = meterY(mark.toFloat(), top, bot)
                p.color = Pal.alpha(Pal.CREAM, 0.3f); c.drawRect(ml - Px.dp(3f), ty, ml, ty + u * 0.5f, p)
                c.drawText("$mark", ml - Px.dp(10f), ty + Px.dp(3f), tp)
            }
            // fader
            val fl = l + Px.dp(6f); val fr = l + (r - l) * 0.38f
            p.color = 0xFF0A080E.toInt(); c.drawRect((fl + fr) / 2 - Px.dp(2f), top, (fl + fr) / 2 + Px.dp(2f), bot, p)
            val fy = bot - level.coerceIn(0f, 1f) * (bot - top)
            c.pixelBox(fl, fy - Px.dp(8f), fr, fy + Px.dp(8f), Pal.PAPER)
            p.color = col; c.drawRect(fl + Px.dp(2f), fy - u * 0.5f, fr - Px.dp(2f), fy + u * 0.5f, p)
            hits += MixHit(l, top - Px.dp(8f), ml - Px.dp(12f), bot + Px.dp(8f), 7, lane)
            tp.textSize = Px.dp(9f); tp.color = Pal.CREAM
            c.textFit(dbText(level), (l + r) / 2, h - Px.dp(14f), r - l - Px.dp(6f), tp)
        }

        private var dragHit: MixHit? = null
        private var downX = 0f; private var lastX = 0f; private var moved = false
        private fun faderBounds(hit: MixHit) = (hit.t + Px.dp(8f)) to (hit.b - Px.dp(8f))
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; lastX = e.x; moved = false; dragHit = hits.lastOrNull { e.x in it.l..it.r && e.y in it.t..it.b }; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    val hh = dragHit
                    if (abs(e.x - downX) > Px.dp(6f)) moved = true
                    when {
                        hh != null && hh.kind == 7 -> { val (t, b) = faderBounds(hh); setFader(hh.lane, ((b - e.y) / (b - t)).coerceIn(0f, 1f)) }
                        hh != null && hh.kind == 6 && hh.lane != null -> { val mid = (hh.l + hh.r) / 2; var v = ((e.x - mid) / ((hh.r - hh.l) / 2 - Px.dp(8f))).coerceIn(-1f, 1f); if (abs(v) < 0.05f) v = 0f; Audio.laneFx(hh.lane.id).pan.set(v) }
                        else -> { scroll -= e.x - lastX }
                    }
                    lastX = e.x; invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val hh = dragHit
                    if (hh != null && !moved) when (hh.kind) {
                        0 -> hh.lane?.let { FireEscape.selectedLane = it.id; openLanePopup(it) }
                        1 -> hh.lane?.let { FireEscape.toggleArm(it.id) }
                        2 -> hh.lane?.let { FireEscape.toggleMute(it.id) }
                        3 -> hh.lane?.let { FireEscape.toggleStereo(it.channel) }
                        4 -> hh.lane?.let { FireEscape.selectedLane = it.id; val n = Audio.laneFx(it.id).effects.count { e2 -> e2.second.on } + if (it.isTrack && Audio.amps[it.channel].on.on) 1 else 0; if (n == 0) openLanePopup(it) else if (drawerKind == "fx") buildFxAgain() else toggleDrawer("fx") }
                        5 -> hh.lane?.let { chooseSidechain(it) }
                        7 -> { val (t, b) = faderBounds(hh); setFader(hh.lane, ((b - e.y) / (b - t)).coerceIn(0f, 1f)) }
                    }
                    if (hh?.kind == 6 || hh?.kind == 7) Studio.save()
                    dragHit = null; invalidate()
                }
            }
            return true
        }

        private fun setFader(lane: FireEscape.Lane?, v: Float) {
            if (lane == null) { Audio.masterVolume = v; Studio.project.audioMaster = v } else setLevel(lane, v)
        }
    }

    // ------------------------------------------------------------------ the backdrop

    /** The alley painting, scaled up with hard pixel edges to cover the screen, with twinkling string lights. */
    private class AlleyView(c: Context) : PixelView(c) {
        init { animated = true }
        private val bmp: Bitmap by lazy { Bitmap.createBitmap(AlleyArt.render(), AlleyArt.OUT_W, AlleyArt.OUT_H, Bitmap.Config.ARGB_8888) }
        private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        private val p = Paint()
        private val src = Rect(); private val dst = RectF()
        private val t0 = System.nanoTime()
        override fun paint(c: Canvas) {
            val s = max(width / AlleyArt.OUT_W.toFloat(), height / AlleyArt.OUT_H.toFloat())
            val w = AlleyArt.OUT_W * s; val h = AlleyArt.OUT_H * s
            val ox = (width - w) / 2f; val oy = (height - h) / 2f
            src.set(0, 0, AlleyArt.OUT_W, AlleyArt.OUT_H); dst.set(ox, oy, ox + w, oy + h)
            c.drawBitmap(bmp, src, dst, paint)
            // the string lights breathe, each at its own pace
            val time = (System.nanoTime() - t0) / 1e9f
            for ((i, b) in AlleyArt.bulbs.withIndex()) {
                val k = 0.5f + 0.5f * sin(time * (1.3f + (i % 4) * 0.37f) + i * 1.7f)
                p.color = Pal.alpha(b[2], 0.35f * k)
                val cx = ox + (b[0] + 0.5f) * s; val cy = oy + (b[1] + 0.5f) * s
                c.drawRect(cx - s * 1.5f, cy - s * 1.5f, cx + s * 1.5f, cy + s * 1.5f, p)
            }
            // the cat blinks now and then
            if ((time % 6.3f) < 0.18f) {
                p.color = 0xFF121016.toInt()
                val ex = ox + AlleyArt.catEyes[0] * s; val ey = oy + AlleyArt.catEyes[1] * s
                c.drawRect(ex, ey, ex + s * 3, ey + s, p)
            }
        }
    }
}

/** A lane's colour: the block's own, or the audio track's biplane colour. */
fun laneColor(id: String): Int = if (id.startsWith("track")) Iso.PLANES[id.removePrefix("track").toIntOrNull()?.coerceIn(0, 2) ?: 0] else Pal.accent(id)

/** Blend two colours (t = 0 gives a, 1 gives b). */
fun mixColor(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 255) + (((b shr s) and 255) - ((a shr s) and 255)) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** A touchable spot on the mixer: what kind of control, on which lane (null = master). */
private class MixHit(val l: Float, val t: Float, val r: Float, val b: Float, val kind: Int, val lane: FireEscape.Lane?)

/** The compressor's readouts: input and output level and gain reduction, in dB. */
@SuppressLint("ViewConstructor")
private class CompMeter(c: Context, private val fx: app.foxbox.fx.LaneFx) : PixelView(c) {
    init { animated = true }
    private val p = Paint()
    private val tp = Px.text(9f, Pal.CREAM, align = Paint.Align.LEFT)
    override fun paint(c: Canvas) {
        c.pixelBox(0f, 0f, width.toFloat(), height.toFloat(), 0xFF16121C.toInt())
        val rows = listOf(Triple("IN", fx.compIn, false), Triple("GR", -fx.compReduction, true), Triple("OUT", fx.compOut, false))
        val lw = Px.dp(30f); val rw = Px.dp(56f); val rh = (height - Px.dp(8f)) / 3f
        for ((i, row) in rows.withIndex()) {
            val (label, db, gr) = row
            val y = Px.dp(4f) + i * rh
            tp.textAlign = Paint.Align.LEFT; tp.color = Pal.CREAM; tp.textSize = Px.dp(9f)
            c.drawText(label, Px.dp(6f), y + rh * 0.65f, tp)
            val l = lw; val r = width - rw
            p.color = 0xFF0A080E.toInt(); c.drawRect(l, y + rh * 0.2f, r, y + rh * 0.8f, p)
            if (gr) {
                // reduction grows from the right, down to -24 dB
                val k = (-db / 24f).coerceIn(0f, 1f)
                p.color = Pal.ORANGE; c.drawRect(r - (r - l) * k, y + rh * 0.2f, r, y + rh * 0.8f, p)
            } else {
                val k = ((db + 60f) / 66f).coerceIn(0f, 1f)
                p.color = if (db > -1f) Snes.RED else if (db > -12f) Snes.YELLOW else Pal.GREEN
                c.drawRect(l, y + rh * 0.2f, l + (r - l) * k, y + rh * 0.8f, p)
                // the threshold mark on the input meter
                if (label == "IN") { val tx = l + (r - l) * ((fx.cThresh.value + 60f) / 66f).coerceIn(0f, 1f); p.color = Pal.CREAM; c.drawRect(tx - u * 0.5f, y + rh * 0.1f, tx + u * 0.5f, y + rh * 0.9f, p) }
            }
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(if (db < -99f) "-inf dB" else "%.1f dB".format(db), width - Px.dp(5f), y + rh * 0.65f, tp)
        }
    }
}

/**
 * The multiband at a glance: the three bands along a frequency scale (drag the two
 * crossover lines to move them), each band's level and how far it's being pushed down.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class BandGraph(c: Context, private val fx: app.foxbox.fx.LaneFx) : PixelView(c) {
    init { animated = true }
    private val p = Paint()
    private val tp = Px.text(9f, Pal.CREAM)
    private val cols = intArrayOf(0xFFC8603C.toInt(), 0xFFD9A03A.toInt(), 0xFF4A8FC8.toInt())
    private fun xOf(hz: Float) = Px.dp(6f) + (kotlin.math.ln(hz / 20f) / kotlin.math.ln(1000f)).coerceIn(0f, 1f) * (width - Px.dp(12f))
    private fun hzOf(x: Float) = 20f * Math.pow(1000.0, ((x - Px.dp(6f)) / (width - Px.dp(12f))).toDouble().coerceIn(0.0, 1.0)).toFloat()
    override fun paint(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.pixelBox(0f, 0f, w, h, 0xFF16121C.toInt())
        val x1 = xOf(fx.mLowX.value); val x2 = xOf(fx.mHighX.value)
        val top = Px.dp(16f); val bot = h - Px.dp(18f)
        val edges = floatArrayOf(Px.dp(6f), x1, x2, w - Px.dp(6f))
        val names = listOf("LOW", "MID", "HIGH")
        val keyed = fx.mBand.index
        for (b in 0 until 3) {
            val l = edges[b]; val r = edges[b + 1]
            p.color = Pal.alpha(cols[b], 0.28f); c.drawRect(l, top, r, bot, p)
            // the band's level (-60..+6) and its reduction hanging from the top
            val lv = ((fx.bandIn[b] + 60f) / 66f).coerceIn(0f, 1f)
            p.color = Pal.alpha(cols[b], 0.8f); c.drawRect(l + Px.dp(4f), bot - (bot - top) * lv, r - Px.dp(4f), bot, p)
            val gr = (fx.bandGr[b] / 24f).coerceIn(0f, 1f)
            p.color = Pal.alpha(Pal.CREAM, 0.75f); c.drawRect(l + Px.dp(4f), top, r - Px.dp(4f), top + (bot - top) * gr, p)
            // threshold line
            val th = when (b) { 0 -> fx.mLowT.value; 1 -> fx.mMidT.value; else -> fx.mHighT.value }
            val ty = bot - (bot - top) * ((th + 60f) / 66f).coerceIn(0f, 1f)
            p.color = Pal.CREAM; c.drawRect(l + Px.dp(2f), ty - u * 0.4f, r - Px.dp(2f), ty + u * 0.4f, p)
            tp.color = if (keyed == b + 1 || (keyed == 0 && fx.bandKey >= 0)) Pal.ORANGE else Pal.CREAM; tp.textSize = Px.dp(9f)
            c.textFit(names[b] + if (keyed == b + 1 || (keyed == 0 && fx.bandKey >= 0)) " ◆" else "", (l + r) / 2, Px.dp(9f), r - l - Px.dp(2f), tp)
            tp.color = Pal.CREAM
            c.textFit("-%.1f dB".format(fx.bandGr[b]), (l + r) / 2, top + Px.dp(9f), r - l - Px.dp(4f), tp)
        }
        // crossovers, with their frequencies
        for ((x, hz) in listOf(x1 to fx.mLowX.value, x2 to fx.mHighX.value)) {
            p.color = Pal.CREAM; c.drawRect(x - u * 0.6f, top - Px.dp(2f), x + u * 0.6f, bot + Px.dp(2f), p)
            c.pixelBox(x - Px.dp(5f), bot - Px.dp(5f), x + Px.dp(5f), bot + Px.dp(5f), Pal.PAPER)
            tp.textSize = Px.dp(9f)
            c.textFit(if (hz >= 1000f) "%.1fk".format(hz / 1000f) else "${hz.toInt()} Hz", x, h - Px.dp(8f), Px.dp(60f), tp)
        }
    }
    private var dragging = -1
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> dragging = if (abs(e.x - xOf(fx.mLowX.value)) < abs(e.x - xOf(fx.mHighX.value))) 0 else 1
            MotionEvent.ACTION_MOVE -> {
                val hz = hzOf(e.x)
                if (dragging == 0) fx.mLowX.set(minOf(hz, fx.mHighX.value / 1.6f)) else if (dragging == 1) fx.mHighX.set(maxOf(hz, fx.mLowX.value * 1.6f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = -1; Studio.save() }
        }
        invalidate(); return true
    }
}
