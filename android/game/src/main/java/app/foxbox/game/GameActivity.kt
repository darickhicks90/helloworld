package app.foxbox.game

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import app.foxbox.radio.RadioHost
import app.foxbox.radio.RadioPanel
import app.foxbox.ui.HallScreen
import app.foxbox.ui.Px
import app.foxbox.ui.SnesDialog

/**
 * Wildwood Hall, game first: the app opens straight into the great hall (where you left off; the
 * game saves itself). The radio is still in the bag, for music while you play.
 */
class GameActivity : Activity(), RadioHost {
    private var hall: HallScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        Px.init(this)
        app.foxbox.kit.Export.init(this); app.foxbox.kit.Export.root = "Wildwood Hall"
        app.foxbox.radio.Radio.init(this)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { askLeave() }
        }
        hall = HallScreen(this).apply { onExit = { askLeave() }; onRadio = { RadioPanel.show(this@GameActivity, this@GameActivity) } }
        setContentView(hall)
    }

    private fun askLeave() {
        SnesDialog.ask(this, "Leave the hall?", "Your progress is saved. You'll be right here when you come back.",
            "Stay" to {}, "Leave" to { finish() })
    }

    @Deprecated("Back handling for Android 12 and older")
    override fun onBackPressed() = askLeave()

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

    // the radio's recordings can only be saved as WAV files here (there are no samplers)
    override fun sampleTargets(): List<Pair<String, String>> = emptyList()
    override fun deliverSample(target: String, data: FloatArray, rate: Int, name: String) {}
}
