package app.foxbox.pocket

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import app.foxbox.notes.Notes
import app.foxbox.notes.NotebookView
import app.foxbox.notes.SketchEditor
import app.foxbox.radio.Radio
import app.foxbox.radio.RadioHost
import app.foxbox.radio.RadioPanel
import app.foxbox.ui.Px
import app.foxbox.ui.TopKeys

/**
 * 5IY Pocket: the notebook and the radio from 5 Eye Yeti, together and on their own. It opens on the
 * memo pad, held upright; the radio key in the corner brings up the portable, which keeps playing
 * while you write. A sketch turns with the phone, as in the big app.
 */
class PocketActivity : Activity(), RadioHost {
    private lateinit var root: FrameLayout
    private lateinit var keys: TopKeys
    private var notebook: NotebookView? = null
    private var sketch: SketchEditor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        Px.init(this)
        app.foxbox.kit.Export.init(this); app.foxbox.kit.Export.root = "5IY Pocket"
        Notes.init(this)
        Radio.init(this)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        root = FrameLayout(this)
        setContentView(root)
        openNotebook()
        keys = TopKeys(this, { RadioPanel.show(this, this) }) { if (sketch != null) sketch?.close() else notebook?.refresh() }
        keys.notebookOpen = true
        keys.radioOn = Radio.playing; keys.radioRec = Radio.recording
        Radio.onChange = { runOnUiThread { keys.radioOn = Radio.playing; keys.radioRec = Radio.recording } }
        root.addView(keys, FrameLayout.LayoutParams(TopKeys.WIDTH.toInt(), TopKeys.HEIGHT.toInt(), Gravity.TOP or Gravity.END).apply { topMargin = Px.dp(4); rightMargin = Px.dp(4) })
    }

    private fun openNotebook() {
        // "Close" on the pad tucks the app away (the radio keeps playing)
        val nb = NotebookView(this, { Notes.save(); moveTaskToBack(true) }) { s -> openSketch(s) }
        notebook = nb
        root.addView(nb, 0, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openSketch(s: Notes.Sketch) {
        if (sketch != null) return
        val v = SketchEditor(this, s) {
            sketch?.let { root.removeView(it) }
            sketch = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            notebook?.refresh()
        }
        sketch = v
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        root.addView(v, root.childCount - 1, FrameLayout.LayoutParams(-1, -1))
    }

    private fun goBack() {
        sketch?.let { it.close(); return }
        Notes.save(); moveTaskToBack(true)
    }

    @Deprecated("Back handling for Android 12 and older")
    override fun onBackPressed() = goBack()

    override fun onStop() { Notes.save(); super.onStop() }
    override fun onStart() { super.onStart(); immersive() }
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

    // recordings off the air are saved as WAV files (there are no samplers here)
    override fun sampleTargets(): List<Pair<String, String>> = emptyList()
    override fun deliverSample(target: String, data: FloatArray, rate: Int, name: String) {}
}
