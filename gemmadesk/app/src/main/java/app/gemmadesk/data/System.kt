package app.gemmadesk.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.time.ZoneId

/**
 * Appointment reminders: one alarm per event, a notification when it fires.
 * Re-scheduled after the phone restarts.
 */
class Reminders : BroadcastReceiver() {
    companion object {
        private const val CHANNEL = "reminders"

        private fun intent(ctx: Context, e: Event? = null, id: String = e?.id ?: "") = PendingIntent.getBroadcast(ctx, id.hashCode(),
            Intent(ctx, Reminders::class.java).setAction("app.gemmadesk.REMIND").putExtra("id", id)
                .putExtra("title", e?.title ?: "").putExtra("when", e?.let { describe(it) } ?: ""),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun describe(e: Event) = (e.start?.let { "%d:%02d %s".format(if (it.hour % 12 == 0) 12 else it.hour % 12, it.minute, if (it.hour < 12) "AM" else "PM") } ?: "All day") +
            (if (e.place.isNotBlank()) " · ${e.place}" else "")

        /** Set (or clear) the reminder for one event. */
        fun schedule(ctx: Context, e: Event) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = intent(ctx, e)
            am.cancel(pi)
            val mins = e.remind ?: return
            val at = e.startsAt().minusMinutes(mins.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (at < System.currentTimeMillis()) return
            try {
                if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // may be a few minutes late
            } catch (_: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }

        fun cancel(ctx: Context, id: String) = (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intent(ctx, id = id))

        fun scheduleAll(ctx: Context) {
            val cal = Calendar(File(ctx.filesDir, "calendar.json"))
            cal.events.forEach { schedule(ctx, it) }
        }
    }

    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) { scheduleAll(ctx); return }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Appointment reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(i.getStringExtra("title"))
            .setContentText(i.getStringExtra("when"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(i.getStringExtra("id").hashCode(), n) }
    }
}

/** Shares exported files (HTML, CSV) with other apps without exposing anything else. */
class ExportProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "app.gemmadesk.export"
        fun dir(ctx: Context) = File(ctx.cacheDir, "export").apply { mkdirs() }
        fun uri(f: File): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(f.name)}")
    }
    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val f = File(dir(context!!), name)
        return f.takeIf { it.parentFile == dir(context!!) && it.exists() }
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = when (file(uri)?.extension) { "html" -> "text/html"; "csv" -> "text/csv"; "zip" -> "application/zip"; "png" -> "image/png"; "md" -> "text/markdown"; "json" -> "application/json"; "txt" -> "text/plain"; else -> "application/octet-stream" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = file(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
