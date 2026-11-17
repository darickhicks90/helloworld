package app.foxbox.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the details of a crash to a local file so the player can choose to
 * share it on the next launch. Nothing is sent anywhere automatically.
 */
object CrashLog {
    private lateinit var file: File

    fun install(c: Context) {
        file = File(c.filesDir, "last-crash.txt")
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText("5 Eye Yeti $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nThread: ${thread.name}\n\n$sw")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The saved report from the last crash, if any. */
    fun pending(): String? = if (::file.isInitialized && file.exists()) runCatching { file.readText() }.getOrNull() else null
    fun clear() { if (::file.isInitialized) file.delete() }
}
