package app.foxbox

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
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
import app.foxbox.ui.ValleyScreen

/** One activity, three screens: projects → studio ⇄ valley. */
class MainActivity : Activity(), Host {
    private lateinit var midi: MidiInput
    private var screen = 0 // 0 projects, 1 studio, 2 valley
    private var studio: StudioScreen? = null
    private var pickFor: String? = null
    private var mic: AudioFiles.Mic? = null
    private var micFor: String? = null
    private val main = Handler(Looper.getMainLooper())
    override fun micTarget() = if (mic != null) micFor else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        CrashLog.install(this)
        DeviceAudio.configure(this) // before anything creates the engines
        Px.init(this)
        ProjectStore.init(this)
        Presets.init(this)
        Store.init(this)
        MidiMap.init(this)
        Studio.init(this)
        Studio.onMessage = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        Studio.needMic = { then -> withMic(then) }
        midi = MidiInput(this, Studio)
        midi.start()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        showProjects()
        offerCrashReport()
    }

    /** If FoxBox crashed last time, offer to share the details (nothing is sent automatically). */
    private fun offerCrashReport() {
        val report = CrashLog.pending() ?: return
        AlertDialog.Builder(this).setTitle("FoxBox closed unexpectedly")
            .setMessage("Sorry about that. You can share the crash details with the developer to help fix it.")
            .setPositiveButton("Share details") { _, _ ->
                CrashLog.clear()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "FoxBox crash report"); putExtra(Intent.EXTRA_TEXT, report)
                }, "Share crash details"))
            }
            .setNegativeButton("Not now") { _, _ -> CrashLog.clear() }
            .show()
    }

    private fun goBack() {
        when (screen) { 2 -> showStudio(); 1 -> showProjects(); else -> finish() }
    }

    override fun onStart() { super.onStart(); Audio.start(); immersive() }
    override fun onStop() {
        if (screen != 0) Studio.save(true)
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
        if (screen != 0) { Studio.save(true); Audio.post { Audio.transport.stop() } }
        screen = 0; studio = null
        setContentView(ProjectScreen(this, ::openProject, ::newProject, ::manageProject))
    }

    private fun openProject(p: Project) {
        Studio.open(p)
        studio = StudioScreen(this, this)
        showStudio()
    }

    private fun showStudio() {
        screen = 1
        setContentView(studio ?: StudioScreen(this, this).also { studio = it })
    }

    override fun showValley() {
        screen = 2
        (studio?.parent as? android.view.ViewGroup)?.removeView(studio)
        setContentView(ValleyScreen(this, this).apply { backToStudio = { showStudio() } })
    }

    @Deprecated("Back handling for Android 12 and older")
    override fun onBackPressed() = goBack()

    private fun textDialog(title: String, initial: String, numeric: Boolean = false, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); setSelection(initial.length)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> done(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun choose(title: String, items: List<String>, checked: Int = -1, pick: (Int) -> Unit) {
        val b = AlertDialog.Builder(this).setTitle(title)
        if (checked >= 0) b.setSingleChoiceItems(items.toTypedArray(), checked) { d, i -> d.dismiss(); pick(i) }
        else b.setItems(items.toTypedArray()) { _, i -> pick(i) }
        b.setNegativeButton("Close", null).show()
    }

    private fun newProject() = textDialog("Name your project", "Song ${ProjectStore.list().size + 1}") { openProject(ProjectStore.create(it)) }

    private fun manageProject(p: Project) {
        choose(p.name, listOf("Open", "Rename", "Duplicate", "Delete")) { which ->
            when (which) {
                0 -> openProject(p)
                1 -> textDialog("Rename project", p.name) { ProjectStore.rename(p, it); showProjects() }
                2 -> textDialog("Name the copy", p.name + " copy") { ProjectStore.duplicate(p, it); showProjects() }
                3 -> AlertDialog.Builder(this).setTitle("Delete ${p.name}?").setMessage("Its clips, takes and samples in the app will be removed. Exported files stay.")
                    .setPositiveButton("Delete") { _, _ -> ProjectStore.delete(p); showProjects() }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    // ------------------------------------------------------------------ Host: basics

    override fun bpmDialog() = textDialog("Tempo (BPM)", Studio.project.bpm.toInt().toString(), numeric = true) { s ->
        s.toFloatOrNull()?.let { Studio.setBpm(it) }
    }

    override fun clipMenu(clip: Clip) {
        val playing = Studio.isPlaying(clip)
        val items = listOf(if (playing) "Stop" else "Play", "Clip length…", "Rename", "Save MIDI file again", "Delete")
        choose(clip.name, items) { which ->
            when (which) {
                0 -> Studio.toggleClip(clip)
                1 -> {
                    val opts = listOf(1, 2, 3, 4, 6, 8, 12, 16)
                    choose("Length (now ${clip.lengthTicks / Ticks.BAR} bars)", opts.map { "$it bar" + if (it > 1) "s" else "" }) { i -> Studio.setClipBars(clip, opts[i]) }
                }
                2 -> textDialog("Rename clip", clip.name) { Studio.renameClip(clip, it) }
                3 -> { ProjectStore.saveClipMidi(Studio.project, clip); Studio.message("Saved ${clip.name}.mid to ${ProjectStore.location.label}") }
                4 -> Studio.deleteClip(clip)
            }
        }
    }

    override fun chooseXyTarget(xy: XyState, isX: Boolean) {
        val choice = if (isX) xy.xTarget else xy.yTarget
        choose(if (isX) "X axis controls…" else "Y axis controls…", xy.targets.map { it.name }, choice.index) { i -> choice.set(i.toFloat()); Studio.save(); Studio.changed() }
    }

    override fun flowerMenu(f: Flower) {
        choose(f.name, listOf(if (f.inValley) "Take out of the garden" else "Plant in the garden", "Rename", "Duplicate", "Delete")) { which ->
            when (which) {
                0 -> if (f.inValley) Studio.bury(f.id) else Studio.plantFlower(f)
                1 -> textDialog("Rename flower", f.name) { Studio.renameFlower(f, it) }
                2 -> Studio.duplicateFlower(f)
                3 -> Studio.deleteFlower(f)
            }
        }
    }

    override fun renameItem(id: String) = textDialog("Rename", Studio.nameOf(id)) { Studio.renameItem(id, it) }

    override fun channelInput(ch: Int) {
        val opts = listOf("Input 1 (left)", "Input 2 (right)", "Inputs 1+2 mixed")
        choose("${Studio.CHANNEL_NAMES[ch]} track records…", opts, AudioPrefs.planeInput(ch)) { k -> AudioPrefs.setPlaneInput(ch, k); Studio.changed() }
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
            "Export a song: arm Master on the clip launcher, then play…",
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
                5 -> Studio.message("On the clip launcher, arm the Master strip, then press play. Stopping exports the song.")
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

    private fun sharePreset(p: Presets.Preset) {
        Thread {
            val uri = Presets.export(p)
            runOnUiThread {
                if (uri == null) return@runOnUiThread Studio.message("Couldn't save the preset file")
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "${p.name} (FoxBox preset)")
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
            val d = AlertDialog.Builder(this).setTitle("MIDI learn").setMessage("Move a knob, fader or button on your controller for “${targetName(target)}”…")
                .setNegativeButton("Cancel") { _, _ -> MidiMap.learning = null }.show()
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
        AlertDialog.Builder(this).setTitle("Restart FoxBox?").setMessage("The new sample rate takes effect when FoxBox restarts. Your project is saved first.")
            .setPositiveButton("Restart now") { _, _ -> restartApp() }.setNegativeButton("Later", null).show()
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
        val d = AlertDialog.Builder(this).setTitle("Audio test").setMessage("You'll hear a quiet tone for about 12 seconds while FoxBox tries each buffer size…").setCancelable(false).show()
        Thread {
            val report = AudioTest.run { msg -> runOnUiThread { d.setMessage(msg) } }
            runOnUiThread {
                d.dismiss()
                AlertDialog.Builder(this).setTitle("Audio test results").setMessage(report.text)
                    .setPositiveButton("Use ${report.recommended} frames") { _, _ -> AudioPrefs.bufferFrames = report.recommended; Audio.restart(); Studio.message("Buffer set to ${report.recommended} frames") }
                    .setNeutralButton("Automatic") { _, _ -> AudioPrefs.bufferFrames = 0; Audio.restart() }
                    .setNegativeButton("Keep mine", null).show()
            }
        }.start()
    }

    /** A few yes/no questions that walk through the usual audio problems. */
    private fun wizard(step: Int) {
        fun ask(title: String, msg: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) =
            AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(yes) { _, _ -> onYes() }.setNegativeButton(no) { _, _ -> onNo() }.setNeutralButton("Stop", null).show()
        when (step) {
            0 -> { Audio.testTone = true; ask("1 · Can you hear a tone?", "FoxBox is playing a quiet 440 Hz tone now.", "Yes", "No", { Audio.testTone = false; wizard(2) }, { Audio.testTone = false; wizard(1) }) }
            1 -> ask("No sound", "• Turn the media volume up (use the volume keys while FoxBox is open).\n• If you use a USB interface, check its monitor / headphone level and that it is class-compliant (it should work without drivers).\n• Bluetooth headphones work but add a long delay; wired or USB is best.\n\nFoxBox will now switch output back to the system default.",
                "Try again", "Skip", { AudioPrefs.outputDevice = 0; Audio.restart(); wizard(0) }, { wizard(2) })
            2 -> ask("2 · Crackles or dropouts?", "Do you hear clicks, crackles or gaps while playing?", "Yes", "No", {
                Studio.message("Running the audio test to find a safe buffer…"); runAudioTest()
            }, { wizard(3) })
            3 -> ask("3 · Too much delay?", "Does sound come noticeably late after you touch a pad or key?", "Yes", "No", {
                AudioPrefs.bufferFrames = 128; Audio.restart()
                AlertDialog.Builder(this).setTitle("Lower latency").setMessage("Buffer set to 128 frames. Also:\n• Use wired or USB headphones (Bluetooth adds 100–200 ms).\n• Close other audio apps.\n• If it now crackles, run the audio test.").setPositiveButton("Next") { _, _ -> wizard(4) }.show()
            }, { wizard(4) })
            4 -> ask("4 · Recordings out of time?", "Do audio takes land a little late or early against the beat?", "Yes", "No", {
                textDialog("Recording offset in ms (try 20–60 if takes land late)", AudioPrefs.offsetMs.toString(), numeric = true) { s -> s.toIntOrNull()?.let { AudioPrefs.offsetMs = it.coerceIn(-500, 500) }; wizard(5) }
            }, { wizard(5) })
            5 -> ask("5 · Interface not showing up?", "Is your USB audio interface or MIDI controller missing from the lists?", "Yes", "No", {
                AlertDialog.Builder(this).setTitle("Connecting USB gear").setMessage("• Use a USB-C OTG adapter or hub; bus-powered interfaces may need a powered hub.\n• Class-compliant devices need no drivers. Some have a \"class compliant\" or \"iOS\" mode switch.\n• Unplug and replug after FoxBox is open; it picks devices up live.\n• Choose it under Audio → Output / Input, and MIDI devices appear under MIDI.").setPositiveButton("Done", null).show()
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

    private fun deliver(target: String, data: FloatArray, name: String) {
        when {
            target.startsWith("pad") -> Studio.setPadSample(target.removePrefix("pad").toInt(), data, name)
            else -> (Audio.engine(target) as? SampleEngine)?.let { Studio.setSample(it, data, name) }
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
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
            REQ_PACK -> {
                val name = displayName(uri)?.substringBeforeLast('.') ?: "Pack"
                Thread {
                    val n = runCatching { Store.importPack(uri, name) }.getOrDefault(0)
                    Studio.message(if (n > 0) "Imported $name ($n sounds): File → Sample packs" else "No WAV files found in that zip")
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
            REQ_MIC -> { val f = afterMic; afterMic = null; if (ok) f?.invoke() else Studio.message("FoxBox needs the microphone / audio input to record") }
            REQ_BT -> if (ok) midi.pairBluetooth() else Studio.message("FoxBox needs Bluetooth permission to find controllers")
        }
    }

    companion object {
        private const val REQ_FILE = 1
        private const val REQ_MIC = 2
        private const val REQ_BT = 3
        private const val REQ_PRESET = 4
        private const val REQ_PACK = 5
        private const val REQ_TREE = 6
    }
}
