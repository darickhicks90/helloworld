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

    private fun header(raf: RandomAccessFile, channels: Int, dataBytes: Int) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(SR).putInt(SR * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        raf.seek(0); raf.write(b.array())
    }

    fun writeWav(file: File, mono: FloatArray) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            header(raf, 1, mono.size * 2)
            val bb = ByteBuffer.allocate(mono.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            mono.forEach { bb.putShort((it.coerceIn(-1f, 1f) * 32767).roundToInt().toShort()) }
            raf.write(bb.array())
        }
    }

    /** Read a WAV written by FoxBox (or any 16-bit / float PCM WAV) as mono at SR. */
    fun readWav(file: File): FloatArray? = runCatching {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12; var ch = 1; var rate = SR; var bits = 16; var fmt = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4); val size = b.getInt(pos + 4)
            if (id == "fmt ") { fmt = b.getShort(pos + 8).toInt(); ch = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt() }
            if (id == "data") {
                val frames = size / (bits / 8) / ch
                val mono = FloatArray(frames) { i ->
                    var s = 0f
                    for (c in 0 until ch) {
                        val o = pos + 8 + (i * ch + c) * (bits / 8)
                        s += if (fmt == 3 && bits == 32) b.getFloat(o) else b.getShort(o) / 32768f
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
            val raf = RandomAccessFile(file, "rw").apply { setLength(0); header(this, 2, 0) }
            writer = Thread {
                val bb = ByteBuffer.allocate((Audio.BLOCK * 2) * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (running || full.isNotEmpty()) {
                    val blk = full.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val n = blk[0].toInt()
                    bb.clear()
                    for (i in 1..n * 2) bb.putShort((blk[i].coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
                    raf.write(bb.array(), 0, n * 4)
                    frames += n
                    free.offer(blk)
                }
                header(raf, 2, frames * 4); raf.close()
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

    class Mic {
        private val chunks = ArrayList<FloatArray>()
        @Volatile private var running = true
        private val thread: Thread

        init {
            @SuppressLint("MissingPermission")
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT), SR / 5 * 4))
            thread = Thread {
                rec.startRecording()
                var total = 0
                while (running && total < MAX_SECONDS * SR) {
                    val buf = FloatArray(SR / 20)
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) { synchronized(chunks) { chunks += if (n == buf.size) buf else buf.copyOf(n) }; total += n }
                }
                rec.stop(); rec.release()
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
