package app.foxbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.data.AmpLibrary

/**
 * The amp on an audio track, as an effect you add when you want it: until then
 * just "+ Add amp (NAM)"; once added, the capture and cabinet pickers, its
 * knobs, and Remove. Used by the Fire Escape's lane effects and by Clibbins'
 * track effects.
 */
@SuppressLint("ViewConstructor")
class AmpPanel(c: Context, private val ch: Int, private val fire: FireHost) : LinearLayout(c) {
    private val rig = Audio.amps[ch]
    private val listener: () -> Unit = { post { build() } }

    init { orientation = VERTICAL; build() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); rig.on.listeners.add(listener); live += this }
    override fun onDetachedFromWindow() { rig.on.listeners.remove(listener); live -= this; super.onDetachedFromWindow() }

    companion object {
        private val live = mutableSetOf<AmpPanel>()
        /** After an import: every open amp panel shows the new choice. */
        fun refreshAll() = live.toList().forEach { it.build(true) }
    }

    private var builtOn: Boolean? = null
    fun build(force: Boolean = false) {
        val on = rig.on.on
        if (!force && builtOn == on) return
        builtOn = on
        removeAllViews()
        rig.onProblem = { msg -> Studio.message(msg) }
        val accent = Pal.ORANGE
        val panel = Panel(context, "Amp (NAM) · ${Studio.CHANNEL_NAMES[ch]} track", accent = accent)
        if (!on) {
            panel.addView(Btn(context, "+ Add amp (NAM)", accent).apply { onClick = { rig.on.set(1f); Studio.save() } }, lp(Px.dp(200), Px.dp(42)))
            panel.addView(Btn(context, "Tuner", Pal.AQUA).apply { onClick = { tuner() } }, lp(Px.dp(200), Px.dp(38)).apply { topMargin = Px.dp(4) })
        } else {
            val w = Px.dp(74) * 3
            panel.addView(Btn(context, "Capture: ${rig.ampName}", accent).apply { onClick = { chooseAmp() } }, lp(w, Px.dp(40)).apply { bottomMargin = Px.dp(3) })
            panel.addView(Btn(context, "Cab: ${rig.cabName}", Pal.AQUA).apply { onClick = { chooseCab() } }, lp(w, Px.dp(40)).apply { bottomMargin = Px.dp(3) })
            val flow = Flow(context)
            listOf(rig.input, rig.output, rig.cabOn, rig.cabMix).forEachIndexed { k, p -> flow.addView(Knob(context, p, Snes.accent(k))) }
            panel.addView(flow, lp(w, WRAP))
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            row.addView(Btn(context, "Tuner", Pal.AQUA).apply { onClick = { tuner() } }, lp(0, Px.dp(36), 1f))
            row.addView(Btn(context, "Credits", Pal.BROWN_L).apply { onClick = { fire.showCredits(AmpLibrary.credits()) } }, lp(0, Px.dp(36), 1f).apply { leftMargin = Px.dp(3) })
            row.addView(Btn(context, "Remove", Snes.RED).apply { onClick = { rig.on.set(0f); Studio.save() } }, lp(0, Px.dp(36), 1f).apply { leftMargin = Px.dp(3) })
            panel.addView(row, lp(w, Px.dp(38)).apply { topMargin = Px.dp(4) })
        }
        addView(panel, lp(WRAP, WRAP))
    }

    private fun tuner() { (context as? android.app.Activity)?.let { Tuner.show(it, app.foxbox.audio.AudioPrefs.planeInput(ch).coerceIn(0, 2)) } }

    private fun chooseAmp() {
        val list = AmpLibrary.amps()
        fire.choose("Amp capture", list.map { it.name } + "Import a .nam capture…", list.indexOfFirst { it.name == rig.ampName }) { i ->
            if (i == list.size) fire.importAmp(ch) else { rig.chooseAmp(list[i].name); Studio.save(); build(true) }
        }
    }

    private fun chooseCab() {
        val list = AmpLibrary.cabs()
        fire.choose("Cabinet (impulse response)", list.map { it.name } + "Import an impulse response…", list.indexOfFirst { it.name == rig.cabName }) { i ->
            if (i == list.size) fire.importCab(ch) else { rig.chooseCab(list[i].name); Studio.save(); build(true) }
        }
    }
}
