package app.foxbox.kit

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process

/**
 * Keeps the phone's processor steady for the audio thread, so a song doesn't glitch when the
 * phone decides to slow down:
 *  - sustained performance mode while the app is in front (where the phone supports it), and
 *  - on Android 12 and newer, a performance hint for the audio thread: each burst reports how
 *    long its work took against the time it has, so the system raises the clock before the
 *    audio falls behind (instead of after a dropout).
 */
object AudioBoost {
    private var app: Context? = null
    private var session: Any? = null
    @Volatile private var tried = false
    private var targetNs = 0L

    fun init(c: Context) { app = c.applicationContext }

    /** Call from onResume (on = true) and onPause (on = false). */
    fun sustained(a: Activity, on: Boolean) {
        runCatching {
            val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isSustainedPerformanceModeSupported) a.window.setSustainedPerformanceMode(on)
        }
    }

    /** Call on the audio thread after rendering `frames` frames at `rate`, which took `workNs`. */
    fun onRender(workNs: Long, frames: Int, rate: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || rate <= 0) return
        val target = frames * 1_000_000_000L / rate
        var s = session as PerformanceHintManager.Session?
        if (s == null) {
            if (tried) return
            tried = true
            s = runCatching {
                val m = app?.getSystemService(PerformanceHintManager::class.java) ?: return
                m.createHintSession(intArrayOf(Process.myTid()), target)
            }.getOrNull() ?: return
            session = s; targetNs = target
        }
        if (target != targetNs) { targetNs = target; runCatching { s.updateTargetWorkDuration(target) } }
        if (workNs > 0) runCatching { s.reportActualWorkDuration(workNs) }
    }
}
