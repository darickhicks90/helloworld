package app.foxbox.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import app.foxbox.audio.SR
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records the selected input (a class-compliant audio interface, a headset
 * or the built-in mic) in stereo, so each biplane can take input 1, input 2
 * or both. The audio thread marks where the take starts and ends.
 */
class AudioIn(context: Context, deviceId: Int, private val keep: Boolean = true) {
    // ---- live monitor: a lock-free ring the audio thread reads (for mixing down to tape)
    private val ringL = FloatArray(16384); private val ringR = FloatArray(16384)
    @Volatile private var ringW = 0
    private var ringRd = 0
    private fun monitorPush(l: Float, r: Float) { val w = ringW; ringL[w] = l; ringR[w] = r; ringW = (w + 1) and 16383 }

    /** Audio thread: copy up to n live frames into l/r (zeros if the input is behind). Never allocates. */
    fun readMonitor(l: FloatArray, r: FloatArray, n: Int) {
        val w = ringW
        var avail = (w - ringRd) and 16383
        if (avail > 4096) { ringRd = (w - 512) and 16383; avail = 512 } // fell far behind: catch up
        for (i in 0 until n) {
            if (avail > 0) { l[i] = ringL[ringRd]; r[i] = ringR[ringRd]; ringRd = (ringRd + 1) and 16383; avail-- } else { l[i] = 0f; r[i] = 0f }
        }
    }

    /** Stop without keeping anything (a monitor-only input). */
    fun close() { running = false; runCatching { thread.join(1000) } }

    private val left = FloatArrayBuilder(); private val right = FloatArrayBuilder()
    private val captured = AtomicInteger(0)
    @Volatile private var running = true
    @Volatile var startFrame = -1; private set
    @Volatile var endFrame = -1; private set
    private val thread: Thread
    @SuppressLint("MissingPermission")
    private val rec: AudioRecord

    init {
        val fmt = AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        @SuppressLint("MissingPermission")
        val r = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED.takeIf { supportsUnprocessed(context) } ?: MediaRecorder.AudioSource.MIC)
            .setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, SR / 10 * 8)).build()
        rec = r
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("The audio input is busy or unavailable") }
        if (deviceId != 0) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId }?.let { rec.setPreferredDevice(it) }
        }
        thread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(SR / 50 * 2)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { if (n < 0) break else continue }
                    var i = 0
                    while (i + 1 < n) {
                        if (keep) { left.add(buf[i]); right.add(buf[i + 1]) }
                        monitorPush(buf[i], buf[i + 1])
                        i += 2
                    }
                    captured.addAndGet(n / 2)
                    if (keep && left.size > (app.foxbox.seq.AudioClip.MAX_SECONDS + 30) * SR) break
                }
            } catch (_: Exception) {
            } finally { runCatching { rec.stop() }; rec.release() }
        }.apply { start() }
    }

    /** Called from the audio thread when the take starts / ends; `latencyFrames` compensates the round trip. */
    fun markStart(latencyFrames: Int) { startFrame = maxOf(0, captured.get() - latencyFrames) }
    fun markEnd(latencyFrames: Int) { endFrame = maxOf(0, captured.get() - latencyFrames) }

    /** Stop and return the take for input 0 (left), 1 (right) or 2 (both mixed). */
    fun finish(): Array<FloatArray> {
        running = false; thread.join(1500)
        val s = startFrame.coerceAtLeast(0); val e = (if (endFrame < 0) captured.get() else endFrame).coerceIn(s, left.size)
        val l = left.slice(s, e); val r = right.slice(s, e)
        return arrayOf(l, r, FloatArray(l.size) { (l[it] + r[it]) * 0.5f })
    }

    class FloatArrayBuilder {
        @Volatile private var a = FloatArray(1 shl 16)
        @Volatile var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun slice(from: Int, to: Int): FloatArray = a.copyOfRange(from.coerceAtMost(size), to.coerceAtMost(size))
    }

    companion object {
        private fun supportsUnprocessed(c: Context) =
            (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        /** Input devices for the audio settings (class-compliant USB interfaces, headsets, built-in mic). */
        fun inputs(c: Context): List<AudioDeviceInfo> =
            (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        fun outputs(c: Context): List<AudioDeviceInfo> =
            (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        fun label(d: AudioDeviceInfo): String {
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                else -> "Device"
            }
            return "$kind: ${d.productName}"
        }
    }
}
