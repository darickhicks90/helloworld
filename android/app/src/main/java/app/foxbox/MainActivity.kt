package app.foxbox

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import app.foxbox.audio.Audio
import app.foxbox.audio.AudioPrefs
import app.foxbox.audio.AudioTest
import app.foxbox.audio.DeviceAudio
import app.foxbox.audio.SR
import app.foxbox.audio.XyState
import app.foxbox.blocks.Blocks
import app.foxbox.data.AudioFiles
import app.foxbox.data.AudioIn
import app.foxbox.data.CrashLog
import app.foxbox.data.Presets
import app.foxbox.data.Project
import app.foxbox.data.ProjectStore
import app.foxbox.data.Store
import app.foxbox.engines.SampleEngine
import app.foxbox.fx.Flower
import app.foxbox.midi.MidiInput
import app.foxbox.midi.MidiMap
import app.foxbox.seq.Clip
import app.foxbox.seq.Ticks
import app.foxbox.ui.Host
import app.foxbox.ui.ProjectScreen
import app.foxbox.ui.Px
import app.foxbox.ui.StudioScreen
import app.foxbox.ui.ClibbinsScreen
import app.foxbox.ui.SnesDialog

/** One activity, three screens: projects → studio ⇄ Clibbins. */
class MainActivity : Activity(), Host, app.foxbox.radio.RadioHost, app.foxbox.ui.FireHost {
    private lateinit var midi: MidiInput
    private var screen = 0 // 0 projects, 1 studio, 2 Clibbins, 3 the Fire Escape
    private var fireScreen: app.foxbox.ui.FireEscapeScreen? = null
    private var studio: StudioScreen? = null
    private var pickFor: String? = null
    private var mic: AudioFiles.Mic? = null
    private var micFor: String? = null
    private val main = Handler(Looper.getMainLooper())
    override fun micTarget() = Studio.samplerTarget?.id ?: if (mic != null) micFor else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        CrashLog.install(this)
        DeviceAudio.configure(this) // before anything creates the engines
        Px.init(this)
        app.foxbox.kit.AudioBoost.init(this)
        ProjectStore.init(this)
        Presets.init(this)
        app.foxbox.data.AmpLibrary.init(this)
        app.foxbox.data.WraithPresets.init(this)
        Noodler.init(this)
        Noodler.onChange = { noodleKey?.invalidate() }
        applyMetronomePrefs()
        app.foxbox.data.Banks.init(this)
        Store.init(this)
        MidiMap.init(this)
        Studio.init(this)
        app.foxbox.notes.Notes.init(this)
        app.foxbox.radio.Radio.init(this)
        app.foxbox.kit.Export.init(this)
        app.foxbox.kit.Export.handler = { kind, name, mime, bytes -> ProjectStore.export(null, kind, name, mime, bytes) }
        app.foxbox.jam.Jam.init(this)
        Studio.onMessage = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        Studio.needMic = { then -> withMic(then) }
        midi = MidiInput(this, Studio)
        midi.start()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        showProjects()
        if (!openForCheck()) offerCrashReport()
    }

    /**
     * For automated screenshot runs (the beta check in CI): `am start ... --es screen studio|clibbins|notebook
     * [--es page gusts|minds|...]` opens that screen on a project called "Beta check". Nothing else uses it.
     */
    private fun openForCheck(): Boolean {
        val target = intent?.getStringExtra("screen") ?: return false
        val p = ProjectStore.list().firstOrNull { it.name == "Beta check" } ?: ProjectStore.create("Beta check")
        when (target) {
            "studio", "clibbins", "fireescape" -> {
                Studio.open(p)
                intent.getStringExtra("page")?.let { pg -> runCatching { Studio.select(pg) } }
                studio = StudioScreen(this, this); showStudio()
                if (target == "clibbins") showClibbins()
                if (target == "fireescape") showFireEscape()
            }
            "notebook" -> openNotebook()
        }
        return true
    }

    /** If FoxBox crashed last time, offer to share the details (nothing is sent automatically). */
    private fun offerCrashReport() {
        val report = CrashLog.pending() ?: return
        SnesDialog.ask(this, "5 Eye Yeti closed unexpectedly", "Sorry about that. You can share the crash details with the developer to help fix it.",
            "Not now" to { CrashLog.clear() },
            "Share details" to {
                CrashLog.clear()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "5 Eye Yeti crash report"); putExtra(Intent.EXTRA_TEXT, report)
                }, "Share crash details"))
            })
    }

    private fun goBack() {
        if (closeNotebook()) return
        if (screen == 3 && fireScreen?.back() == true) return
        when (screen) { 3 -> leaveFireEscape(); 2 -> showStudio(); 1 -> showProjects(); else -> finish() }
    }

    // a steady processor while the app is in front (where the phone supports it), so the audio never glitches as it throttles
    override fun onResume() { super.onResume(); app.foxbox.kit.AudioBoost.sustained(this, true) }
    override fun onPause() { app.foxbox.kit.AudioBoost.sustained(this, false); super.onPause() }

    override fun onStart() { super.onStart(); Audio.start(); immersive() }
    override fun onStop() {
        if (projectOpen) Studio.save(true)
        if (!Studio.taping && !Studio.audioRecording) Audio.stop()
        super.onStop()
    }
    override fun onDestroy() { midi.stop(); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    // ------------------------------------------------------------------ navigation

    override fun showProjects() {
        if (projectOpen) { Studio.save(true); Audio.post { Audio.transport.stop() } }
        screen = 0; studio = null
        setContentView(ProjectScreen(this, ::openProject, ::newProject, ::manageProject))
        addTopKeys()
    }

    private fun openProject(p: Project) {
        Studio.open(p)
        studio = StudioScreen(this, this)
        showStudio()
    }

    private fun showStudio() {
        screen = 1
        setContentView(studio ?: StudioScreen(this, this).also { studio = it })
        addTopKeys()
    }

    override fun showClibbins() {
        Studio.enterClibbins()
        screen = 2
        (studio?.parent as? android.view.ViewGroup)?.removeView(studio)
        setContentView(ClibbinsScreen(this, this).apply { backToStudio = { showStudio() } })
        addTopKeys()
    }

    /** A project is open everywhere but the title screen. */
    private val projectOpen get() = screen != 0

    // ------------------------------------------------------------------ the notebook (every screen but the game)

    private var notebook: app.foxbox.notes.NotebookView? = null
    private var hiddenViews: List<View> = emptyList()
    private var restorePending = false
    private fun restoreViews() {
        if (!restorePending) return
        restorePending = false
        hiddenViews.forEach { it.visibility = View.VISIBLE }
        hiddenViews = emptyList()
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (restorePending && newConfig.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT) restoreViews()
    }
    private var orientationBefore = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var sketchView: app.foxbox.notes.SketchEditor? = null
    private val content get() = findViewById<android.widget.FrameLayout>(android.R.id.content)

    /** The radio and notebook keys, in the top right corner of the screen just shown (setContentView took the last ones away). */
    private var topKeys: app.foxbox.ui.TopKeys? = null
    private fun addTopKeys() {
        notebook = null; sketchView = null; hiddenViews = emptyList(); restorePending = false
        val k = app.foxbox.ui.TopKeys(this, { app.foxbox.radio.RadioPanel.show(this, this) }) { if (notebook != null) closeNotebook() else openNotebook() }
        k.radioOn = app.foxbox.radio.Radio.playing; k.radioRec = app.foxbox.radio.Radio.recording
        app.foxbox.radio.Radio.onChange = { runOnUiThread { topKeys?.radioOn = app.foxbox.radio.Radio.playing; topKeys?.radioRec = app.foxbox.radio.Radio.recording } }
        topKeys = k
        addContentView(k, android.widget.FrameLayout.LayoutParams(app.foxbox.ui.TopKeys.WIDTH.toInt(), app.foxbox.ui.TopKeys.HEIGHT.toInt(), android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = Px.dp(4); rightMargin = Px.dp(4) })
        // the Noodler key, just left of them, on the studio, Clibbins and the Fire Escape
        noodleKey = null
        if (screen in 1..3) {
            val nk = app.foxbox.ui.NoodlerKey(this, { noodlerPressed() }) { noodlerSettings() }
            noodleKey = nk
            addContentView(nk, android.widget.FrameLayout.LayoutParams(app.foxbox.ui.NoodlerKey.WIDTH.toInt(), app.foxbox.ui.TopKeys.HEIGHT.toInt(), android.view.Gravity.TOP or android.view.Gravity.END).apply {
                topMargin = Px.dp(4); rightMargin = (Px.dp(6f) + app.foxbox.ui.TopKeys.WIDTH).toInt()
            })
        } else if (Noodler.active) Noodler.stop()
    }

    private fun openNotebook() {
        if (notebook != null) return
        val nb = app.foxbox.notes.NotebookView(this, { closeNotebook() }) { s -> openSketch(s) }
        notebook = nb
        // the screen underneath steps aside (so it isn't laid out upright while the notebook is open)
        hiddenViews = (0 until content.childCount).map { content.getChildAt(it) }.filter { it.visibility == View.VISIBLE && it !== topKeys }
        hiddenViews.forEach { it.visibility = View.GONE }
        // held upright, like a memo pad in the hand
        orientationBefore = requestedOrientation
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        addContentView(nb, android.widget.FrameLayout.LayoutParams(-1, -1))
        topKeys?.let { it.visibility = View.VISIBLE; it.bringToFront(); it.notebookOpen = true }
    }

    private fun openSketch(s: app.foxbox.notes.Notes.Sketch) {
        if (sketchView != null) return
        val v = app.foxbox.notes.SketchEditor(this, s) {
            sketchView?.let { content.removeView(it) }
            sketchView = null
            // back on the page (upright again), showing what was just drawn
            if (notebook != null) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            notebook?.refresh()
        }
        sketchView = v
        // the drawing board turns with the phone, whichever way suits the picture
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        addContentView(v, android.widget.FrameLayout.LayoutParams(-1, -1))
        topKeys?.bringToFront()
    }

    /** Back (or Close): the sketch first, then the notebook. True if something closed. */
    private fun closeNotebook(): Boolean {
        sketchView?.let { it.close(); return true }
        val nb = notebook ?: return false
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(nb.windowToken, 0)
        content.removeView(nb)
        notebook = null
        topKeys?.notebookOpen = false
        requestedOrientation = orientationBefore
        // bring the screen back once the phone has turned back (or straight away if it won't turn)
        restorePending = true
        main.postDelayed({ restoreViews() }, 800)
        app.foxbox.notes.Notes.save()
        immersive()
        return true
    }

    @Deprecated("Back handling for Android 12 and older")
    override fun onBackPressed() = goBack()

    override fun textDialog(title: String, initial: String, numeric: Boolean, done: (String) -> Unit) {
        SnesDialog.text(this, title, initial, numeric, done)
    }

    override fun choose(title: String, items: List<String>, checked: Int, pick: (Int) -> Unit) {
        SnesDialog.choose(this, title, items, checked, pick)
    }

    // ------------------------------------------------------------------ the Fire Escape

    override fun showFireEscape() {
        if (!projectOpen) return
        screen = 3
        (studio?.parent as? android.view.ViewGroup)?.removeView(studio)
        FireEscape.enter()
        fireScreen = app.foxbox.ui.FireEscapeScreen(this, this, this)
        setContentView(fireScreen)
        addTopKeys()
    }

    override fun leaveFireEscape() {
        FireEscape.leave()
        fireScreen = null
        showStudio()
    }

    private var importFor = 0
    override fun importAmp(ch: Int) { importFor = ch; openDocument(REQ_AMP, "*/*") }
    override fun importCab(ch: Int) { importFor = ch; openDocument(REQ_CAB, "audio/*") }
    override fun showCredits(text: String) { SnesDialog(this, "Amp captures: credits & licence", wide = 0.9f).message(text.ifBlank { "No bundled captures." }).key("Done", app.foxbox.ui.Snes.GREEN, then = null).show() }

    private fun ask(title: String, msg: String, vararg keys: Pair<String, (() -> Unit)?>) = SnesDialog.ask(this, title, msg, *keys)

    private fun newProject() = textDialog("Name your project", "Song ${ProjectStore.list().size + 1}") { openProject(ProjectStore.create(it)) }

    private fun manageProject(p: Project) {
        choose(p.name, listOf("Open", "Rename", "Duplicate", "Delete")) { which ->
            when (which) {
                0 -> openProject(p)
                1 -> textDialog("Rename project", p.name) { ProjectStore.rename(p, it); showProjects() }
                2 -> textDialog("Name the copy", p.name + " copy") { ProjectStore.duplicate(p, it); showProjects() }
                3 -> ask("Delete ${p.name}?", "Its clips, takes and samples in the app will be removed. Exported files stay.",
                    "Cancel" to null, "Delete" to { ProjectStore.delete(p); showProjects() })
            }
        }
    }

    // ------------------------------------------------------------------ Host: basics

    override fun bpmDialog() = textDialog("Tempo (BPM)", Studio.project.bpm.toInt().toString(), numeric = true) { s ->
        s.toFloatOrNull()?.let { Studio.setBpm(it) }
    }

    override fun clipMenu(clip: Clip) {
        val playing = Studio.isPlaying(clip)
        val lengths = listOf(1, 2, 3, 4, 6, 8, 12, 16)
        fun length() = choose("Length (now ${clip.lengthTicks / Ticks.BAR} bars)", lengths.map { "$it bar" + if (it > 1) "s" else "" }) { i -> Studio.setClipBars(clip, lengths[i]) }
        if (clip.jam) {
            choose(clip.name, listOf(if (playing) "Stop" else "Play", "Keep it as a clip for Clibbins", "Loop length…", "Delete")) { which ->
                when (which) { 0 -> Studio.toggleClip(clip); 1 -> Studio.keepLoop(clip); 2 -> length(); 3 -> Studio.deleteClip(clip) }
            }
            return
        }
        val items = listOf(if (playing) "Stop" else "Play", if (clip.inClibbins) "Take out of Clibbins" else "Send to Clibbins", "Clip length…", "Rename", "Save MIDI file again", "Delete")
        choose(clip.name, items) { which ->
            when (which) {
                0 -> Studio.toggleClip(clip)
                1 -> Studio.toggleInClibbins(clip)
                2 -> length()
                3 -> textDialog("Rename clip", clip.name) { Studio.renameClip(clip, it) }
                4 -> { ProjectStore.saveClipMidi(Studio.project, clip); Studio.message("Saved ${clip.name}.mid to ${ProjectStore.location.label}") }
                5 -> Studio.deleteClip(clip)
            }
        }
    }

    override fun stripFx(ch: Int) {
        val fx = if (ch == 3) Audio.masterFx else Audio.stripFx[ch]
        val name = if (ch == 3) "Master" else Studio.CHANNEL_NAMES[ch] + " track"
        val d = app.foxbox.ui.SnesDialog(this, "$name · dynamics & effects", wide = 0.94f)
        val strip = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        var k = 0
        fx.p.sections.forEach { (section, params) ->
            val col = app.foxbox.ui.Snes.accent(k++)
            val panel = app.foxbox.ui.Panel(this, section, accent = col)
            val flow = app.foxbox.ui.Flow(this)
            params.forEach { flow.addView(app.foxbox.ui.Knob(this, it, col)) }
            panel.addView(flow, android.widget.LinearLayout.LayoutParams(Px.dp(74) * ((params.size + 1) / 2), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            strip.addView(panel, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Px.dp(6) })
        }
        // the amp: an effect a track has only once it's added
        if (ch < 3) strip.addView(app.foxbox.ui.AmpPanel(this, ch, this), 0, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Px.dp(6) })
        d.view(android.widget.HorizontalScrollView(this).apply { addView(strip) })
        if (ch < 3) d.key("Tuner", app.foxbox.ui.Snes.YELLOW, close = false) { showTuner(AudioPrefs.planeInput(ch).coerceIn(0, 2)) }
        d.key("Reset", app.foxbox.ui.Snes.RED, close = false) { fx.reset(); Studio.save() }
        d.key("Done", app.foxbox.ui.Snes.GREEN) { Studio.save() }
        d.onDismiss { Studio.save() }
        d.show()
    }

    override fun mainMenu() = choose("Menu", listOf("File…", "Audio settings…", "MIDI…", "Tuner", "Jam over the internet…")) { i ->
        when (i) { 0 -> fileMenu(); 1 -> audioSettings(); 2 -> midiMenu(); 3 -> showTuner(2); 4 -> app.foxbox.jam.JamPanel.show(this) }
    }

    override fun showTuner(source: Int) = app.foxbox.ui.Tuner.show(this, source)

    // ------------------------------------------------------------------ Noodler

    private var noodleKey: app.foxbox.ui.NoodlerKey? = null
    /** The metronome warning is given once a session. */
    private var warnedMetronome = false

    private fun noodlerPressed() {
        if (Noodler.active) { Noodler.stop(); return }
        withMic { noodlerChecks() }
    }

    private fun noodlerChecks() {
        if (Audio.transport.metronome && !warnedMetronome) {
            warnedMetronome = true
            ask("Metronome is on", "If the metronome plays where the mic can hear it, its clicks can get into the notes Noodler picks up. Headphones fix that.",
                "Got it" to { noodlerMode() })
            return
        }
        noodlerMode()
    }

    private fun noodlerMode() {
        when (Noodler.mode) {
            Noodler.Mode.LIVE -> ask("Headphones needed", "Live Noodler plays ${Noodler.targetEngine().title} as you sing. Headphones (or keeping the mic well away from the instrument's sound) are needed for it to work properly, or it will hear the synth and follow itself.",
                "Cancel" to null, "Headphones on: start" to { Noodler.start() })
            Noodler.Mode.FORGE -> ask("Noodle Forge", "Noodle Forge will change this project's tempo and scale to match your count and your idea. Do you want to do that?",
                "No" to null, "Yes, forge it" to { Noodler.start() })
            else -> Noodler.start()
        }
    }

    private fun noodlerSettings() {
        val d = SnesDialog(this, "Noodler", wide = 0.8f)
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        fun rebuild() {
            box.removeAllViews()
            fun row(text: String, color: Int, then: () -> Unit) = box.addView(app.foxbox.ui.Btn(this, text, color).apply { onClick = then }, android.widget.LinearLayout.LayoutParams(-1, Px.dp(42)).apply { bottomMargin = Px.dp(4) })
            row("Mode: ${Noodler.mode.label}", app.foxbox.ui.Snes.PURPLE) {
                choose("Noodler mode", Noodler.Mode.entries.map { it.label }, Noodler.mode.ordinal) { i -> Noodler.mode = Noodler.Mode.entries[i]; rebuild() }
            }
            val blocks = Studio.project.blocks.filter { app.foxbox.blocks.Blocks.get(it)?.melodic == true }
            row("Block: " + (if (Noodler.block.isEmpty()) "the one in front (${Noodler.targetEngine().title})" else Audio.engine(Noodler.block).title), app.foxbox.ui.Pal.ORANGE) {
                choose("Noodle into which block?", listOf("The melodic block in front") + blocks.map { Audio.engine(it).title }) { i -> Noodler.block = if (i == 0) "" else blocks[i - 1]; rebuild() }
            }
            row("Notes: " + (if (Noodler.snap) "in the project's scale" else "every semitone") + " (Noodle Forge ignores this)", app.foxbox.ui.Pal.AQUA) { Noodler.snap = !Noodler.snap; rebuild() }
            row("Listens to: " + listOf("Input 1", "Input 2", "Inputs 1+2")[Noodler.input], app.foxbox.ui.Pal.AQUA) {
                choose("Noodler listens to", listOf("Input 1", "Input 2", "Inputs 1+2"), Noodler.input) { i -> Noodler.input = i; rebuild() }
            }
            row("Capture grid: " + listOf("off (as played)", "1/16 notes", "1/8 notes")[Noodler.quantize], app.foxbox.ui.Pal.GREEN) {
                choose("Line captured notes up to", listOf("Off (as played)", "1/16 notes", "1/8 notes"), Noodler.quantize) { i -> Noodler.quantize = i; rebuild() }
            }
            box.addView(app.foxbox.ui.Stepper(this, "Sensitivity (hears sounds above)", 200, { "${Noodler.sensitivity.toInt()} dB" }) { Noodler.sensitivity = (Noodler.sensitivity + it * 3f).coerceIn(-66f, -18f) },
                android.widget.LinearLayout.LayoutParams(-1, Px.dp(44)).apply { bottomMargin = Px.dp(4) })
            box.addView(app.foxbox.ui.Stepper(this, "Octave", 200, { (if (Noodler.octave > 0) "+" else "") + Noodler.octave }) { Noodler.octave = Noodler.octave + it },
                android.widget.LinearLayout.LayoutParams(-1, Px.dp(44)))
        }
        rebuild()
        d.view(box)
        d.key("Done", app.foxbox.ui.Snes.GREEN, then = null)
        d.show()
    }

    // ------------------------------------------------------------------ tempo tools (hold the tempo)

    private val metPrefs get() = getSharedPreferences("metronome", MODE_PRIVATE)
    private fun applyMetronomePrefs() {
        val t = Audio.transport; val p = metPrefs
        t.clickVolume = p.getFloat("vol", 1f); t.clickSound = p.getInt("sound", 0); t.clickAccent = p.getBoolean("accent", true); t.clickAlways = p.getBoolean("always", false)
    }
    private fun saveMetronomePrefs() {
        val t = Audio.transport
        metPrefs.edit().putFloat("vol", t.clickVolume).putInt("sound", t.clickSound).putBoolean("accent", t.clickAccent).putBoolean("always", t.clickAlways).apply()
    }

    private val taps = ArrayList<Long>()

    override fun tempoTools() {
        val t = Audio.transport
        val d = SnesDialog(this, "Tempo & metronome", wide = 0.84f)
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        fun lpw(h: Int) = android.widget.LinearLayout.LayoutParams(-1, Px.dp(h)).apply { bottomMargin = Px.dp(4) }
        val bpmBox = app.foxbox.ui.Stepper(this, "Tempo", 200, { "${Studio.project.bpm.toInt()} BPM" }) { Studio.setBpm(Studio.project.bpm + it) }
        box.addView(bpmBox, lpw(46))
        taps.clear()
        box.addView(app.foxbox.ui.Btn(this, "TAP TEMPO", app.foxbox.ui.Pal.ORANGE).apply {
            onDown = {
                val now = System.nanoTime()
                if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
                taps += now; while (taps.size > 6) taps.removeAt(0)
                if (taps.size >= 3) {
                    val gaps = (1 until taps.size).map { (taps[it] - taps[it - 1]) / 1e9 }
                    Studio.setBpm(Math.round(60.0 / gaps.average()).toFloat()); bpmBox.invalidate()
                }
            }
        }, lpw(58))
        fun toggle(text: String, on: Boolean, then: () -> Unit) = app.foxbox.ui.Btn(this, text, app.foxbox.ui.Pal.AQUA).apply { this.on = on; onClick = { then(); this.on = !this.on; invalidate(); saveMetronomePrefs() } }
        box.addView(toggle("Metronome", t.metronome) { Studio.setMetronome(!t.metronome) }, lpw(42))
        box.addView(toggle("Click whenever playing (not just recording)", t.clickAlways) { t.clickAlways = !t.clickAlways }, lpw(42))
        box.addView(toggle("Accent the first beat", t.clickAccent) { t.clickAccent = !t.clickAccent }, lpw(42))
        val sounds = listOf("Beep", "Wood", "Tick")
        box.addView(app.foxbox.ui.Stepper(this, "Click sound", 200, { sounds[t.clickSound] }) { t.clickSound = Math.floorMod(t.clickSound + it, 3); saveMetronomePrefs() }, lpw(44))
        box.addView(app.foxbox.ui.Stepper(this, "Click volume", 200, { "${(t.clickVolume * 100).toInt()}%" }) { t.clickVolume = (t.clickVolume + it * 0.1f).coerceIn(0.1f, 1.5f); saveMetronomePrefs() }, lpw(44))
        box.addView(app.foxbox.ui.Btn(this, "Find the BPM and scale of an audio file…", app.foxbox.ui.Snes.PURPLE).apply { onClick = { d.dismiss(); openDocument(REQ_ANALYZE, "audio/*") } }, lpw(44))
        d.view(box)
        d.key("Done", app.foxbox.ui.Snes.GREEN, then = null)
        d.show()
    }

    private fun showAnalysis(name: String, r: app.foxbox.audio.Analysis.Result) {
        val bpm = Math.round(r.bpm)
        val scale = app.foxbox.seq.Theory.SCALES.indexOfFirst { it.name == if (r.minor) "Minor" else "Major" }.coerceAtLeast(0)
        val keyName = app.foxbox.seq.Theory.NOTE_NAMES[r.root] + " " + app.foxbox.seq.Theory.SCALES[scale].name
        ask(name, "Tempo: about $bpm BPM\nScale: $keyName" + (if (r.confidence < 0.25f) " (not certain: it could be its relative major / minor)" else ""),
            "Close" to null,
            "Use the tempo" to { Studio.setBpm(bpm.toFloat()) },
            "Use both" to { Studio.setBpm(bpm.toFloat()); Studio.setKey(root = r.root, scale = scale) })
    }

    override fun chooseXyTarget(xy: XyState, isX: Boolean) {
        val choice = if (isX) xy.xTarget else xy.yTarget
        choose(if (isX) "X axis controls…" else "Y axis controls…", xy.targets.map { it.name }, choice.index) { i -> choice.set(i.toFloat()); Studio.save(); Studio.changed() }
    }

    override fun flowerMenu(f: Flower) {
        choose(f.name, listOf(if (f.inClibbins) "Take out of the garden" else "Plant in the garden", "Rename", "Duplicate", "Delete")) { which ->
            when (which) {
                0 -> if (f.inClibbins) Studio.bury(f.id) else Studio.plantFlower(f)
                1 -> textDialog("Rename flower", f.name) { Studio.renameFlower(f, it) }
                2 -> Studio.duplicateFlower(f)
                3 -> Studio.deleteFlower(f)
            }
        }
    }

    override fun renameItem(id: String) = textDialog("Rename", Studio.nameOf(id)) { Studio.renameItem(id, it) }

    override fun channelInput(ch: Int) {
        val opts = listOf("Input 1 (left)", "Input 2 (right)", "Inputs 1+2 mixed")
        choose("${Studio.CHANNEL_NAMES[ch]} track records…", opts, AudioPrefs.planeInput(ch)) { k -> AudioPrefs.setPlaneInput(ch, k); Studio.refreshLive(); Studio.changed() }
    }

    override fun addBlockMenu() {
        val blocks = Studio.addableBlocks()
        val coming = Store.catalog.filter { it.kind == Store.Kind.BLOCK }
        val items = blocks.map { "${it.title}: ${it.type.blurb}" } + coming.map { "${it.title} (add-on, coming soon)" }
        if (items.isEmpty()) return Studio.message("Every block is already in this session")
        choose("Add a block (${Studio.project.blocks.size}/${Blocks.MAX})", items) { i ->
            if (i < blocks.size) Studio.addBlock(blocks[i].id) else Store.purchase(coming[i - blocks.size]) { Studio.message(it) }
        }
    }

    // ------------------------------------------------------------------ File

    override fun fileMenu() {
        val items = listOf(
            "Undo" + if (Studio.canUndo) "" else " (nothing yet)",
            "Redo" + if (Studio.canRedo) "" else " (nothing yet)",
            "Duplicate project…",
            "Rename project…",
            "Audio save location: ${ProjectStore.location.label}…",
            "Export a song: arm Master in Clibbins, then play…",
            "Sample packs…",
            "Blocks & add-ons…",
            "Import a preset file…",
            "Close project",
        )
        choose("File · ${Studio.project.name}", items) { i ->
            when (i) {
                0 -> Studio.undo()
                1 -> Studio.redo()
                2 -> textDialog("Name the copy", Studio.project.name + " copy") { name ->
                    Studio.save(true)
                    val copy = ProjectStore.duplicate(Studio.project, name)
                    openProject(copy); Studio.message("Now working in ${copy.name}")
                }
                3 -> textDialog("Rename project", Studio.project.name) { ProjectStore.rename(Studio.project, it); Studio.changed() }
                4 -> chooseLocation()
                5 -> Studio.message("In Clibbins, arm the Master strip, then press play. Stopping exports the song.")
                6 -> packsMenu()
                7 -> storeMenu()
                8 -> openDocument(REQ_PRESET, "*/*")
                9 -> showProjects()
            }
        }
    }

    private fun chooseLocation() {
        val locs = ProjectStore.Location.values()
        choose("Save audio, MIDI and presets to…", locs.map { it.label }, locs.indexOf(ProjectStore.location)) { i ->
            if (locs[i] == ProjectStore.Location.FOLDER) {
                @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
            } else { ProjectStore.location = locs[i]; Studio.message("Saving to ${locs[i].label}") }
        }
    }

    private fun storeMenu() {
        val built = Blocks.types.map { "${it.title}: ${it.blurb} (included)" }
        val addOns = Store.catalog.map { "${it.title}: ${it.blurb} (coming soon)" }
        choose("Blocks & add-ons", built + addOns) { i -> if (i >= built.size) Store.purchase(Store.catalog[i - built.size]) { Studio.message(it) } }
    }

    private fun packsMenu() {
        val packs = Store.packs()
        choose("Sample packs", listOf("Import a pack (a .zip of WAV files)…") + packs.map { "${it.name} (${it.sounds.size} sounds)" }) { i ->
            if (i == 0) openDocument(REQ_PACK, "application/zip")
            else {
                val pack = packs[i - 1]
                val sounds = pack.sounds
                choose(pack.name, listOf("Delete this pack") + sounds.map { it.nameWithoutExtension }) { k ->
                    if (k == 0) { Store.deletePack(pack); Studio.message("Deleted ${pack.name}") }
                    else {
                        val f = sounds[k - 1]
                        val data = AudioFiles.readWav(f) ?: return@choose Studio.message("Couldn't read ${f.name}")
                        val dests = listOf("Reflections", "Leaves", "Pickup pad ${Studio.selectedPad + 1}")
                        choose("Load ${f.nameWithoutExtension} into…", dests) { d ->
                            when (d) {
                                0 -> Studio.setSample(Audio.reflections, data, f.nameWithoutExtension)
                                1 -> Studio.setSample(Audio.leaves, data, f.nameWithoutExtension)
                                else -> Studio.setPadSample(Studio.selectedPad, data, f.nameWithoutExtension)
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ presets

    override fun presetMenu(engineId: String) {
        val block = Blocks.get(engineId) ?: return
        val list = Presets.list(engineId)
        val items = listOf("Save this sound as a preset…", "Import a preset file…") + list.map { (if (it.factory) "★ " else "") + it.name }
        choose("${block.title} presets", items) { i ->
            when (i) {
                0 -> textDialog("Name the preset", "${block.title} ${list.count { !it.factory } + 1}") { name ->
                    val p = Presets.saveUser(block, name)
                    Studio.message("Saved ${p.name}" + if (p.samples.isNotEmpty()) " (sample included)" else "")
                }
                1 -> openDocument(REQ_PRESET, "*/*")
                else -> {
                    val p = list[i - 2]
                    if (p.factory) { Presets.apply(block, p); Studio.save(); Studio.changed(); Studio.message("Loaded ${p.name}"); return@choose }
                    choose(p.name, listOf("Load", "Share…", "Delete")) { k ->
                        when (k) {
                            0 -> { Presets.apply(block, p); Studio.save(); Studio.changed(); Studio.message("Loaded ${p.name}") }
                            1 -> sharePreset(p)
                            2 -> { Presets.delete(p); Studio.message("Deleted ${p.name}") }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Pickup banks and chops

    override fun bankMenu() {
        Thread {
            val banks = app.foxbox.data.Banks.list()
            runOnUiThread {
                val items = listOf("Save these pads as a bank…", "Import a bank (.foxbank or a zip of WAVs)…", "Put audio files onto pads from pad ${Studio.selectedPad + 1}…") +
                    banks.map { (if (it.builtIn) "★ " else "") + it.name }
                choose("Pickup banks", items) { i ->
                    when (i) {
                        0 -> textDialog("Name the bank", "My bank ${banks.count { !it.builtIn } + 1}") { name ->
                            Studio.message("Saving $name…")
                            Thread { runCatching { app.foxbox.data.Banks.save(name) }.onSuccess { Studio.message("Saved the $name bank") }.onFailure { Studio.message("Couldn't save the bank") } }.start()
                        }
                        1 -> openDocument(REQ_BANK, "*/*")
                        2 -> openDocuments(REQ_PADS, "audio/*")
                        else -> {
                            val b = banks[i - 3]
                            if (b.builtIn) { Studio.applyBank(b); return@choose }
                            choose(b.name, listOf("Load onto the pads", "Share…", "Delete")) { k ->
                                when (k) {
                                    0 -> Studio.applyBank(b)
                                    1 -> shareBank(b)
                                    2 -> { app.foxbox.data.Banks.delete(b); Studio.message("Deleted ${b.name}") }
                                }
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun shareBank(b: app.foxbox.data.Banks.Bank) {
        Thread {
            val uri = runCatching { app.foxbox.data.Banks.export(b) }.getOrNull()
            runOnUiThread {
                if (uri == null) return@runOnUiThread Studio.message("Couldn't save the bank file")
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "${b.name} (5IY bank)")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share ${b.name}"))
            }
        }.start()
    }

    override fun chopMenu(pad: Int) = app.foxbox.ui.ChopPopup.show(this, this, pad)

    private fun openDocuments(req: Int, type: String) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); this.type = type; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        @Suppress("DEPRECATION") startActivityForResult(i, req)
    }

    private fun sharePreset(p: Presets.Preset) {
        Thread {
            val uri = Presets.export(p)
            runOnUiThread {
                if (uri == null) return@runOnUiThread Studio.message("Couldn't save the preset file")
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "${p.name} (5IY preset)")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share ${p.name}"))
            }
        }.start()
    }

    // ------------------------------------------------------------------ MIDI

    override fun midiMenu() {
        val devs = midi.devices
        val items = listOf("Mapping & MIDI learn…", "Pair a Bluetooth MIDI controller…") + devs.map { "${it.name}: plays as ${it.role.name.lowercase()}" }
        choose(if (devs.isEmpty()) "MIDI (no controllers connected)" else "MIDI", items) { which ->
            when (which) {
                0 -> mappingMenu()
                1 -> if (midi.hasBluetoothPermission()) midi.pairBluetooth() else requestPermissions(midi.bluetoothPermissions(), REQ_BT)
                else -> {
                    val d = devs[which - 2]
                    val roles = MidiInput.Role.values()
                    choose("${d.name} plays as…", listOf("Keys", "MPE keys (ROLI Seaboard)", "Pads (TempoPad C16)"), d.role.ordinal) { r -> d.role = roles[r] }
                }
            }
        }
    }

    private fun targetName(t: String): String = when {
        t == "xy:x" -> "XY pad X"; t == "xy:y" -> "XY pad Y"
        t == "block:volume" -> "Block volume"; t == "block:reverb" -> "Block reverb send"
        t == "transport:play" -> "Play"; t == "transport:stop" -> "Stop"; t == "transport:record" -> "Record"
        t.startsWith("param:") -> t.removePrefix("param:").split(".").let { (b, p) -> "${Blocks.type(b)?.title ?: b}: ${Audio.engines.firstOrNull { it.id == b }?.p?.all?.get(p)?.name ?: p}" }
        else -> t
    }

    private fun mappingMenu() {
        val entries = MidiMap.map.entries.toList()
        val items = listOf("Learn: map a control…", "Reset to the standard mapping") + entries.map { "${MidiMap.describe(it.key)} → ${targetName(it.value)}" }
        choose("MIDI mapping (tap one to remove it)", items) { i ->
            when (i) {
                0 -> learnMenu()
                1 -> { MidiMap.standard(); Studio.message("Standard mapping: CC1 → Y, CC74 → X, CC7 volume, CC91 reverb, CC115–117 transport") }
                else -> { MidiMap.remove(entries[i - 2].key); mappingMenu() }
            }
        }
    }

    private fun learnMenu() {
        val e = Studio.current
        val fixed = listOf("xy:x", "xy:y", "block:volume", "block:reverb", "transport:play", "transport:stop", "transport:record")
        val params = e.p.all.values.filter { !it.id.startsWith("xy") }
        choose("Map which control?", fixed.map { targetName(it) } + params.map { "${e.title}: ${it.name}" }) { i ->
            val target = if (i < fixed.size) fixed[i] else "param:${e.id}.${params[i - fixed.size].id}"
            MidiMap.learning = target
            val d = ask("MIDI learn", "Move a knob, fader or button on your controller for “${targetName(target)}”…", "Cancel" to { MidiMap.learning = null })
            val poll = object : Runnable { override fun run() { if (MidiMap.learning == null) d.dismiss() else if (d.isShowing) main.postDelayed(this, 150) } }
            main.postDelayed(poll, 150)
        }
    }

    // ------------------------------------------------------------------ audio settings

    override fun audioSettings() {
        val outs = AudioIn.outputs(this); val ins = AudioIn.inputs(this)
        val outName = outs.firstOrNull { it.id == AudioPrefs.outputDevice }?.let { AudioIn.label(it) } ?: "System default"
        val inName = ins.firstOrNull { it.id == AudioPrefs.inputDevice }?.let { AudioIn.label(it) } ?: "System default"
        val buf = AudioPrefs.bufferFrames
        val status = "${Audio.outputName} · ${"%.1f".format(Audio.latencyMs)} ms · ${Audio.xruns} dropouts"
        val items = listOf(
            "Output: $outName", "Input: $inName",
            "Sample rate: ${AudioPrefs.sampleRate / 1000f} kHz" + if (AudioPrefs.sampleRate != SR) " (restart needed)" else "",
            "Bit depth: ${AudioPrefs.bitDepth}-bit", "Buffer: " + (if (buf == 0) "Automatic" else "$buf frames (${"%.1f".format(buf * 1000f / SR)} ms)"),
            "Channel inputs…", "Recording offset: ${AudioPrefs.offsetMs} ms",
            "Run the audio test (suggests settings)…", "Troubleshooting wizard…", "Restore defaults (48 kHz, 24-bit, 128)", "Now: $status",
        )
        choose("Audio settings", items) { i ->
            when (i) {
                0 -> choose("Output device", listOf("System default") + outs.map { AudioIn.label(it) }) { k -> AudioPrefs.outputDevice = if (k == 0) 0 else outs[k - 1].id; Audio.restart(); audioSettings() }
                1 -> choose("Input device", listOf("System default") + ins.map { AudioIn.label(it) }) { k -> AudioPrefs.inputDevice = if (k == 0) 0 else ins[k - 1].id; audioSettings() }
                2 -> choose("Sample rate", AudioPrefs.SAMPLE_RATES.map { "${it / 1000f} kHz" }, AudioPrefs.SAMPLE_RATES.indexOf(AudioPrefs.sampleRate)) { k ->
                    AudioPrefs.sampleRate = AudioPrefs.SAMPLE_RATES[k]
                    if (AudioPrefs.sampleRate != SR) askRestart() else audioSettings()
                }
                3 -> choose("Bit depth for recordings and exports", AudioPrefs.BIT_DEPTHS.map { if (it == 32) "32-bit float" else "$it-bit" }, AudioPrefs.BIT_DEPTHS.indexOf(AudioPrefs.bitDepth)) { k -> AudioPrefs.bitDepth = AudioPrefs.BIT_DEPTHS[k]; audioSettings() }
                4 -> choose("Buffer size (smaller = less delay)", AudioPrefs.BUFFERS.map { if (it == 0) "Automatic (grows if audio drops out)" else "$it frames (${"%.1f".format(it * 1000f / SR)} ms)" }, AudioPrefs.BUFFERS.indexOf(buf)) { k ->
                    AudioPrefs.bufferFrames = AudioPrefs.BUFFERS[k]; Audio.restart(); audioSettings()
                }
                5 -> planeInputs()
                6 -> textDialog("Recording offset in ms (positive moves takes earlier)", AudioPrefs.offsetMs.toString(), numeric = true) { s -> s.toIntOrNull()?.let { AudioPrefs.offsetMs = it.coerceIn(-500, 500) }; audioSettings() }
                7 -> runAudioTest()
                8 -> wizard(0)
                9 -> {
                    val rateChanged = SR != 48000
                    AudioPrefs.sampleRate = 48000; AudioPrefs.bitDepth = 24; AudioPrefs.bufferFrames = 128; AudioPrefs.outputDevice = 0; AudioPrefs.inputDevice = 0; AudioPrefs.offsetMs = 0
                    Audio.restart(); if (rateChanged) askRestart() else Studio.message("Audio settings restored")
                }
                10 -> audioSettings()
            }
        }
    }

    private fun planeInputs() {
        val opts = listOf("Input 1 (left)", "Input 2 (right)", "Inputs 1+2 mixed")
        choose("Which input does each audio channel record?", Studio.CHANNEL_NAMES.mapIndexed { ch, n -> "$n channel: ${opts[AudioPrefs.planeInput(ch)]}" }) { ch ->
            choose("${Studio.CHANNEL_NAMES[ch]} channel records…", opts, AudioPrefs.planeInput(ch)) { k -> AudioPrefs.setPlaneInput(ch, k); planeInputs() }
        }
    }

    private fun askRestart() {
        ask("Restart 5 Eye Yeti?", "The new sample rate takes effect when 5 Eye Yeti restarts. Your project is saved first.", "Later" to null, "Restart now" to { restartApp() })
    }

    private fun restartApp() {
        if (Studio.isOpen) Studio.save(true)
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        (getSystemService(ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun runAudioTest() {
        val d = SnesDialog(this, "Audio test").message("You'll hear a quiet tone for about 12 seconds while 5 Eye Yeti tries each buffer size…").cancelable(false).show()
        Thread {
            val report = AudioTest.run { msg -> runOnUiThread { d.message(msg) } }
            runOnUiThread {
                d.dismiss()
                ask("Audio test results", report.text,
                    "Keep mine" to null,
                    "Automatic" to { AudioPrefs.bufferFrames = 0; Audio.restart() },
                    "Use ${report.recommended} frames" to { AudioPrefs.bufferFrames = report.recommended; Audio.restart(); Studio.message("Buffer set to ${report.recommended} frames") })
            }
        }.start()
    }

    /** A few yes/no questions that walk through the usual audio problems. */
    private fun wizard(step: Int) {
        fun ask(title: String, msg: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) =
            ask(title, msg, "Stop" to null, no to onNo, yes to onYes)
        when (step) {
            0 -> { Audio.testTone = true; ask("1 · Can you hear a tone?", "5 Eye Yeti is playing a quiet 440 Hz tone now.", "Yes", "No", { Audio.testTone = false; wizard(2) }, { Audio.testTone = false; wizard(1) }) }
            1 -> ask("No sound", "• Turn the media volume up (use the volume keys while 5 Eye Yeti is open).\n• If you use a USB interface, check its monitor / headphone level and that it is class-compliant (it should work without drivers).\n• Bluetooth headphones work but add a long delay; wired or USB is best.\n\n5 Eye Yeti will now switch output back to the system default.",
                "Try again", "Skip", { AudioPrefs.outputDevice = 0; Audio.restart(); wizard(0) }, { wizard(2) })
            2 -> ask("2 · Crackles or dropouts?", "Do you hear clicks, crackles or gaps while playing?", "Yes", "No", {
                Studio.message("Running the audio test to find a safe buffer…"); runAudioTest()
            }, { wizard(3) })
            3 -> ask("3 · Too much delay?", "Does sound come noticeably late after you touch a pad or key?", "Yes", "No", {
                AudioPrefs.bufferFrames = 128; Audio.restart()
                ask("Lower latency", "Buffer set to 128 frames. Also:\n• Use wired or USB headphones (Bluetooth adds 100–200 ms).\n• Close other audio apps.\n• If it now crackles, run the audio test.", "Next" to { wizard(4) })
            }, { wizard(4) })
            4 -> ask("4 · Recordings out of time?", "Do audio takes land a little late or early against the beat?", "Yes", "No", {
                textDialog("Recording offset in ms (try 20–60 if takes land late)", AudioPrefs.offsetMs.toString(), numeric = true) { s -> s.toIntOrNull()?.let { AudioPrefs.offsetMs = it.coerceIn(-500, 500) }; wizard(5) }
            }, { wizard(5) })
            5 -> ask("5 · Interface not showing up?", "Is your USB audio interface or MIDI controller missing from the lists?", "Yes", "No", {
                ask("Connecting USB gear", "• Use a USB-C OTG adapter or hub; bus-powered interfaces may need a powered hub.\n• Class-compliant devices need no drivers. Some have a \"class compliant\" or \"iOS\" mode switch.\n• Unplug and replug after 5 Eye Yeti is open; it picks devices up live.\n• Choose it under Audio → Output / Input, and MIDI devices appear under MIDI.", "Done" to null)
            }, { Studio.message("All good. Happy music making!") })
        }
    }

    // ------------------------------------------------------------------ loading and recording samples

    private fun openDocument(req: Int, type: String) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); this.type = type }
        @Suppress("DEPRECATION") startActivityForResult(i, req)
    }

    override fun pickAudio(target: String) { pickFor = target; openDocument(REQ_FILE, "audio/*") }

    private fun displayName(uri: Uri) = contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
    }

    // ---- the radio's recordings go to the open project's samplers
    override fun sampleTargets(): List<Pair<String, String>> {
        if (!projectOpen) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        Audio.active.filterIsInstance<SampleEngine>().forEach { out += it.id to "Load into ${it.title}" }
        if (Audio.active.any { it.id == "pickup" }) out += "pad${Studio.selectedPad}" to "Load onto Pickup pad ${Studio.selectedPad + 1}"
        return out
    }
    override fun deliverSample(target: String, data: FloatArray, rate: Int, name: String) = deliver(target, AudioFiles.resample(data, rate), name)

    private fun deliver(target: String, data: FloatArray, name: String) {
        when {
            target == "chopsrc" -> Studio.setChopSource(data, name)
            target.startsWith("pad") -> Studio.setPadSample(target.removePrefix("pad").toInt(), data, name)
            else -> (Audio.engine(target) as? SampleEngine)?.let { Studio.setSample(it, data, name) }
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PADS && resultCode == RESULT_OK && data != null) {
            val uris = data.clipData?.let { cd -> (0 until cd.itemCount).map { cd.getItemAt(it).uri } } ?: listOfNotNull(data.data)
            val first = Studio.selectedPad
            Studio.message("Loading ${uris.size} sound" + if (uris.size == 1) "…" else "s…")
            Thread {
                val sounds = uris.mapNotNull { u -> runCatching { (displayName(u)?.substringBeforeLast('.') ?: "Sound").take(16) to AudioFiles.decode(this, u) }.getOrNull() }
                runOnUiThread { if (sounds.isEmpty()) Studio.message("Couldn't read those files") else { Studio.fillPads(first, sounds); Studio.message("Loaded ${sounds.size} onto pads from pad ${first + 1}") } }
            }.start()
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_FILE -> {
                val target = pickFor ?: return
                Studio.message("Loading…")
                Thread {
                    try {
                        val pcm = AudioFiles.decode(this, uri)
                        val name = displayName(uri)?.substringBeforeLast('.') ?: "Sample"
                        runOnUiThread { deliver(target, pcm, name) }
                    } catch (ex: Exception) { Studio.message("Couldn't read that file: ${ex.message}") }
                }.start()
            }
            REQ_PRESET -> Thread {
                try {
                    val p = Presets.import(this, uri)
                    Studio.message("Imported ${p.name} for ${Blocks.type(p.blockId)?.title}: find it under Presets")
                } catch (ex: Exception) { Studio.message(ex.message ?: "Couldn't import that preset") }
            }.start()
            REQ_BANK -> {
                val name = displayName(uri)?.substringBeforeLast('.') ?: "Imported bank"
                Thread {
                    try {
                        val b = app.foxbox.data.Banks.import(this, uri, name)
                        Studio.message("Imported the ${b.name} bank: Pickup → Banks…")
                    } catch (ex: Exception) { Studio.message(ex.message ?: "Couldn't import that bank") }
                }.start()
            }
            REQ_PACK -> {
                val name = displayName(uri)?.substringBeforeLast('.') ?: "Pack"
                Thread {
                    val n = runCatching { Store.importPack(uri, name) }.getOrDefault(0)
                    Studio.message(if (n > 0) "Imported $name ($n sounds): File → Sample packs" else "No WAV files found in that zip")
                }.start()
            }
            REQ_ANALYZE -> {
                val name = displayName(uri)?.substringBeforeLast('.') ?: "Audio"
                Studio.message("Listening to $name…")
                Thread {
                    try {
                        val pcm = AudioFiles.decode(this, uri)
                        val r = app.foxbox.audio.Analysis.analyze(pcm, app.foxbox.audio.SR)
                        runOnUiThread { showAnalysis(name, r) }
                    } catch (ex: Exception) { Studio.message("Couldn't read that file: ${ex.message}") }
                }.start()
            }
            REQ_AMP, REQ_CAB -> {
                val ch = importFor; val isAmp = requestCode == REQ_AMP
                Studio.message("Importing…")
                Thread {
                    try {
                        val name = if (isAmp) app.foxbox.data.AmpLibrary.importAmp(uri, displayName(uri)) else app.foxbox.data.AmpLibrary.importCab(uri, displayName(uri))
                        runOnUiThread {
                            val rig = Audio.amps[ch]
                            if (isAmp) { rig.chooseAmp(name); if (!rig.on.on) rig.on.set(1f) } else rig.chooseCab(name)
                            Studio.save(); fireScreen?.ampChanged(); app.foxbox.ui.AmpPanel.refreshAll()
                            Studio.message("Imported $name")
                        }
                    } catch (ex: Exception) { Studio.message(ex.message ?: "Couldn't import that file") }
                }.start()
            }
            REQ_TREE -> {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                ProjectStore.folderUri = uri; ProjectStore.location = ProjectStore.Location.FOLDER
                Studio.message("Saving to your chosen folder")
            }
        }
    }

    override fun toggleMic(target: String) {
        // Reflections and Leaves record through the clock (count-in, looper takes); Pickup pads keep the simple recorder.
        (Audio.engines.firstOrNull { it.id == target } as? SampleEngine)?.let { e ->
            if (Studio.samplerTarget != null) Studio.samplerMic(e) else withMic { Studio.samplerMic(e) }
            return
        }
        val m = mic
        if (m != null) {
            val t = micFor ?: target
            mic = null; micFor = null
            Thread { val data = m.stop(); runOnUiThread { deliver(t, data, if (t.startsWith("pad")) "Recording" else "Mic recording") } }.start()
            Studio.changed(); return
        }
        withMic {
            mic = try { AudioFiles.Mic() } catch (ex: Exception) { Studio.message(ex.message ?: "Couldn't open the microphone"); return@withMic }
            micFor = target
            Studio.message("Recording… tap Stop when done")
            Studio.changed()
        }
    }

    private var afterMic: (() -> Unit)? = null
    private fun withMic(then: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return then()
        afterMic = then; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_MIC -> { val f = afterMic; afterMic = null; if (ok) f?.invoke() else Studio.message("5 Eye Yeti needs the microphone / audio input to record") }
            REQ_BT -> if (ok) midi.pairBluetooth() else Studio.message("5 Eye Yeti needs Bluetooth permission to find controllers")
        }
    }

    companion object {
        private const val REQ_FILE = 1
        private const val REQ_MIC = 2
        private const val REQ_BT = 3
        private const val REQ_PRESET = 4
        private const val REQ_PACK = 5
        private const val REQ_BANK = 20
        private const val REQ_PADS = 21
        private const val REQ_TREE = 6
        private const val REQ_AMP = 30
        private const val REQ_CAB = 31
        private const val REQ_ANALYZE = 32
    }
}
