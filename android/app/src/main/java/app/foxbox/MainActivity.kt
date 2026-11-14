package app.foxbox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import app.foxbox.audio.Audio
import app.foxbox.audio.Engine
import app.foxbox.data.AudioFiles
import app.foxbox.data.Project
import app.foxbox.data.ProjectStore
import app.foxbox.midi.MidiInput
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
    private var pickFor: Engine? = null
    private var mic: AudioFiles.Mic? = null
    override val micOn get() = mic != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        Px.init(this)
        ProjectStore.init(this)
        Studio.onMessage = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        midi = MidiInput(this, Studio)
        midi.start()
        showProjects()
    }

    override fun onStart() { super.onStart(); Audio.start(); immersive() }
    override fun onStop() {
        if (screen != 0) Studio.save(true)
        if (!Studio.taping) Audio.stop()
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

    @Deprecated("Back handling")
    override fun onBackPressed() {
        when (screen) { 2 -> showStudio(); 1 -> showProjects(); else -> @Suppress("DEPRECATION") super.onBackPressed() }
    }

    private fun textDialog(title: String, initial: String, numeric: Boolean = false, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); setSelection(initial.length)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> done(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun newProject() = textDialog("Name your project", "Song ${ProjectStore.list().size + 1}") { openProject(ProjectStore.create(it)) }

    private fun manageProject(p: Project) {
        AlertDialog.Builder(this).setTitle(p.name).setItems(arrayOf("Open", "Rename", "Delete")) { _, which ->
            when (which) {
                0 -> openProject(p)
                1 -> textDialog("Rename project", p.name) { ProjectStore.rename(p, it); showProjects() }
                2 -> AlertDialog.Builder(this).setTitle("Delete ${p.name}?").setMessage("Its clips and samples in the app will be removed. Exports in Music/FoxBox stay.")
                    .setPositiveButton("Delete") { _, _ -> ProjectStore.delete(p); showProjects() }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    // ------------------------------------------------------------------ Host

    override fun bpmDialog() = textDialog("Tempo (BPM)", Studio.project.bpm.toInt().toString(), numeric = true) { s ->
        s.toFloatOrNull()?.let { Studio.setBpm(it) }
    }

    override fun clipMenu(clip: Clip) {
        val playing = Studio.isPlaying(clip)
        val items = arrayOf(if (playing) "Stop" else "Play", if (clip.inValley) "Remove from Valley" else "Send to Valley", "Clip length…", "Rename", "Save MIDI file again", "Delete")
        AlertDialog.Builder(this).setTitle(clip.name).setItems(items) { _, which ->
            when (which) {
                0 -> Studio.toggleClip(clip)
                1 -> if (clip.inValley) Studio.removeFromValley(clip) else Studio.sendToValley(clip)
                2 -> {
                    val opts = arrayOf(1, 2, 3, 4, 6, 8, 12, 16)
                    AlertDialog.Builder(this).setTitle("Length (now ${clip.lengthTicks / Ticks.BAR} bars)")
                        .setItems(opts.map { "$it bar" + if (it > 1) "s" else "" }.toTypedArray()) { _, i -> Studio.setClipBars(clip, opts[i]) }.show()
                }
                3 -> textDialog("Rename clip", clip.name) { Studio.renameClip(clip, it) }
                4 -> { ProjectStore.saveClipMidi(Studio.project, clip); Studio.message("Saved ${clip.name}.mid to Music/FoxBox") }
                5 -> Studio.deleteClip(clip)
            }
        }.show()
    }

    override fun midiMenu() {
        val devs = midi.devices
        val items = (listOf("Pair a Bluetooth MIDI controller…") + devs.map { "${it.name}  —  plays as ${it.role.name.lowercase()}" }).toTypedArray()
        AlertDialog.Builder(this).setTitle(if (devs.isEmpty()) "No MIDI controllers connected" else "MIDI controllers").setItems(items) { _, which ->
            if (which == 0) {
                if (midi.hasBluetoothPermission()) midi.pairBluetooth() else requestPermissions(midi.bluetoothPermissions(), REQ_BT)
            } else {
                val d = devs[which - 1]
                val roles = MidiInput.Role.values()
                AlertDialog.Builder(this).setTitle("${d.name} plays as…")
                    .setItems(arrayOf("Keys", "MPE keys (ROLI Seaboard)", "Pads (TempoPad C16)")) { _, r -> d.role = roles[r] }.show()
            }
        }.setNegativeButton("Close", null).show()
    }

    override fun pickAudio(e: Engine) {
        pickFor = e
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_FILE)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val e = pickFor
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || uri == null || e == null) return
        Studio.message("Loading…")
        Thread {
            try {
                val pcm = AudioFiles.decode(this, uri)
                val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
                } ?: "Sample"
                runOnUiThread { Studio.setSample(e, pcm, name) }
            } catch (ex: Exception) {
                Studio.message("Couldn't read that file: ${ex.message}")
            }
        }.start()
    }

    override fun toggleMic() {
        val m = mic
        if (m != null) {
            mic = null
            Thread { val data = m.stop(); runOnUiThread { Studio.setSample(Audio.reflections, data, "Mic recording"); Studio.changed() } }.start()
            Studio.changed(); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        mic = AudioFiles.Mic()
        Studio.message("Recording from the microphone… tap Stop mic when done")
        Studio.changed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_MIC -> if (ok) toggleMic() else Studio.message("FoxBox needs the microphone to record samples")
            REQ_BT -> if (ok) midi.pairBluetooth() else Studio.message("FoxBox needs Bluetooth permission to find controllers")
        }
    }

    companion object {
        private const val REQ_FILE = 1
        private const val REQ_MIC = 2
        private const val REQ_BT = 3
    }
}
