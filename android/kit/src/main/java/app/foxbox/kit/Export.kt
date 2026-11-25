package app.foxbox.kit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Where the notebook, the drawing board and the radio save their files. Each app decides: the
 * music app sends them to its chosen export location; on its own the kit writes to Downloads/<root>/<kind>.
 */
object Export {
    /** The folder name under Downloads (or Music, for audio). */
    var root = "5IY"
    /** Set by an app that keeps its own save location. */
    var handler: ((kind: String, name: String, mime: String, bytes: ByteArray) -> Uri?)? = null
    private var app: Context? = null
    fun init(c: Context) { app = c.applicationContext }

    fun save(kind: String, name: String, mime: String, bytes: ByteArray): Uri? {
        handler?.let { return it(kind, name, mime, bytes) }
        val ctx = app ?: return null
        return runCatching {
            val audio = mime.startsWith("audio")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS) + "/$root/$kind")
            }
            val uri = ctx.contentResolver.insert(if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri
        }.getOrNull()
    }

    /** Like [save], from a file (for big exports: a 4K animation doesn't fit in memory at once). */
    fun saveFile(kind: String, name: String, mime: String, file: File): Uri? {
        handler?.let { h -> return h(kind, name, mime, file.readBytes()) }
        val ctx = app ?: return null
        return runCatching {
            val video = mime.startsWith("video"); val audio = mime.startsWith("audio")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (when { audio -> Environment.DIRECTORY_MUSIC; video -> Environment.DIRECTORY_MOVIES; else -> Environment.DIRECTORY_DOWNLOADS }) + "/$root/$kind")
            }
            val col = when { audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI; else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI }
            val uri = ctx.contentResolver.insert(col, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            uri
        }.getOrNull()
    }

    fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifEmpty { root }

    /** A 16-bit mono WAV. */
    fun wav(mono: FloatArray, rate: Int): ByteArray {
        val n = mono.size * 2
        val bb = ByteBuffer.allocate(44 + n).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1); bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n)
        for (x in mono) bb.putShort((x.coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
        return bb.array()
    }

    @Suppress("unused") fun wavFile(f: File, mono: FloatArray, rate: Int) = RandomAccessFile(f, "rw").use { it.setLength(0); it.write(wav(mono, rate)) }
}
