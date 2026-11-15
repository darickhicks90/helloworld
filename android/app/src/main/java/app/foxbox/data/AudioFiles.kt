package app.foxbox.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import app.foxbox.audio.Audio
import app.foxbox.audio.SR
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.roundToInt

/** Reading/writing WAV files, decoding any audio file, and recording. */
object AudioFiles {
    const val MAX_SECONDS = 60

    // ------------------------------------------------------------------ WAV

    /** WAV header for 16/24-bit PCM or 32-bit float. */
    private fun header(raf: RandomAccessFile, channels: Int, dataBytes: Int, bits: Int) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = bits / 8
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(if (bits == 32) 3 else 1).putShort(channels.toShort())
            .putInt(SR).putInt(SR * channels * bytes).putShort((channels * bytes).toShort()).putShort(bits.toShort())
        b.put("data".toByteArray()).putInt(dataBytes)
        raf.seek(0); raf.write(b.array())
    }

    private fun put(bb: ByteBuffer, v: Float, bits: Int) {
        val x = v.coerceIn(-1f, 1f)
        when (bits) {
            32 -> bb.putFloat(x)
            24 -> { val i = (x * 8388607).roundToInt(); bb.put(i.toByte()); bb.put((i shr 8).toByte()); bb.put((i shr 16).toByte()) }
            else -> bb.putShort((x * 32767).roundToInt().toShort())
        }
    }

    /** Write mono audio at the chosen bit depth (default from the audio settings). */
    fun writeWav(file: File, mono: FloatArray, bits: Int = app.foxbox.audio.AudioPrefs.bitDepth) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            header(raf, 1, mono.size * bits / 8, bits)
            val bb = ByteBuffer.allocate(mono.size * bits / 8).order(ByteOrder.LITTLE_ENDIAN)
            mono.forEach { put(bb, it, bits) }
            raf.write(bb.array())
        }
    }

    /** Read a 16/24-bit PCM or 32-bit float WAV as mono at SR. */
    fun readWav(file: File): FloatArray? = runCatching {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12; var ch = 1; var rate = SR; var bits = 16; var fmt = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4); val size = b.getInt(pos + 4)
            if (id == "fmt ") { fmt = b.getShort(pos + 8).toInt(); ch = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt() }
            if (id == "data") {
                val bps = bits / 8
                val frames = minOf(size, bytes.size - pos - 8) / bps / ch
                val mono = FloatArray(frames) { i ->
                    var s = 0f
                    for (c in 0 until ch) {
                        val o = pos + 8 + (i * ch + c) * bps
                        s += when {
                            fmt == 3 && bits == 32 -> b.getFloat(o)
                            bits == 24 -> ((bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() shl 16)) / 8388608f
                            bits == 32 -> b.getInt(o) / 2147483648f
                            else -> b.getShort(o) / 32768f
                        }
                    }
                    s / ch
                }
                return@runCatching resample(mono, rate)
            }
            pos += 8 + size + (size and 1)
        }
        null
    }.getOrNull()

    fun resample(data: FloatArray, rate: Int): FloatArray {
        if (rate == SR || rate <= 0) return data
        val ratio = rate.toDouble() / SR
        val n = (data.size / ratio).toInt()
        return FloatArray(n) { i ->
            val p = i * ratio; val a = p.toInt(); val f = (p - a).toFloat()
            data[a] + (data[minOf(a + 1, data.size - 1)] - data[a]) * f
        }
    }

    // ------------------------------------------------------------------ decoding any file

    /** Decode an audio file (mp3, m4a, ogg, wav, flac...) to mono at SR, up to MAX_SECONDS. */
    fun decode(context: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IllegalArgumentException("No audio in that file")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val out = FloatBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        val limit = MAX_SECONDS * 48000
        try {
            while (!outputDone && out.size < limit * 2) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    if (floatPcm) {
                        val fb = buf.asFloatBuffer()
                        while (fb.remaining() >= channels) { var s = 0f; repeat(channels) { s += fb.get() }; out.add(s / channels) }
                    } else {
                        val sb = buf.asShortBuffer()
                        while (sb.remaining() >= channels) { var s = 0f; repeat(channels) { s += sb.get() / 32768f }; out.add(s / channels) }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        return normalize(resample(out.toArray(), rate).let { if (it.size > limit) it.copyOf(limit) else it })
    }

    /** Growable float array without boxing. */
    private class FloatBuilder {
        private var a = FloatArray(1 shl 16); var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }

    fun normalize(d: FloatArray): FloatArray {
        val peak = d.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        if (peak > 0.0001f) { val g = 0.9f / peak; for (i in d.indices) d[i] *= g }
        return d
    }

    // ------------------------------------------------------------------ recording FoxBox's output

    /** Streams the master output to a stereo WAV file. */
    class Tape(val file: File) {
        private val free = ArrayBlockingQueue<FloatArray>(512)
        private val full = ArrayBlockingQueue<FloatArray>(512)
        @Volatile private var running = true
        private var frames = 0
        private val writer: Thread

        init {
            repeat(512) { free.add(FloatArray(Audio.BLOCK * 2 + 1)) }
            file.parentFile?.mkdirs()
            val bits = app.foxbox.audio.AudioPrefs.bitDepth
            val bytes = bits / 8
            val raf = RandomAccessFile(file, "rw").apply { setLength(0); header(this, 2, 0, bits) }
            writer = Thread {
                val bb = ByteBuffer.allocate((Audio.BLOCK * 2) * bytes).order(ByteOrder.LITTLE_ENDIAN)
                while (running || full.isNotEmpty()) {
                    val blk = full.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val n = blk[0].toInt()
                    bb.clear()
                    for (i in 1..n * 2) put(bb, blk[i], bits)
                    raf.write(bb.array(), 0, n * 2 * bytes)
                    frames += n
                    free.offer(blk)
                }
                header(raf, 2, frames * 2 * bytes, bits); raf.close()
            }.apply { start() }
            Audio.tap = { l, r, n ->
                free.poll()?.let { blk ->
                    blk[0] = n.toFloat()
                    for (i in 0 until n) { blk[1 + i * 2] = l[i]; blk[2 + i * 2] = r[i] }
                    full.offer(blk)
                }
            }
        }

        fun stop(): File {
            Audio.tap = null
            running = false
            writer.join(3000)
            return file
        }
    }

    // ------------------------------------------------------------------ microphone

    /** Records the microphone. Throws if the mic can't be opened. */
    class Mic {
        private val chunks = ArrayList<FloatArray>()
        @Volatile private var running = true
        private val thread: Thread
        @SuppressLint("MissingPermission")
        private val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT), SR / 5 * 4))

        init {
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("The microphone is busy or unavailable") }
            thread = Thread {
                try {
                    rec.startRecording()
                    var total = 0
                    while (running && total < MAX_SECONDS * SR) {
                        val buf = FloatArray(SR / 20)
                        val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                        if (n < 0) break
                        if (n > 0) { synchronized(chunks) { chunks += if (n == buf.size) buf else buf.copyOf(n) }; total += n }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { rec.stop() }; rec.release()
                }
            }.apply { start() }
        }

        fun stop(): FloatArray {
            running = false; thread.join(2000)
            val all = synchronized(chunks) { chunks.toList() }
            val out = FloatArray(all.sumOf { it.size })
            var p = 0
            all.forEach { System.arraycopy(it, 0, out, p, it.size); p += it.size }
            return normalize(out)
        }
    }
}
