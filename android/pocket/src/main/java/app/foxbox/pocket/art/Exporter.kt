package app.foxbox.pocket.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Turning a drawing into files: a PNG, a PNG sequence, a sprite sheet, or an MP4 video. */
object Exporter {
    /** Frame `f`, all visible layers, at `scale` of full size; `transparent` leaves out the paper. */
    fun frame(doc: ArtDoc, f: Int, scale: Float = 1f, transparent: Boolean = doc.bg == 0): Bitmap {
        val w = max(1, (doc.w * scale).roundToInt()); val h = max(1, (doc.h * scale).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (!transparent) c.drawColor(if (doc.bg == 0) Color.WHITE else doc.bg)
        val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in doc.layers) {
            if (!l.visible) continue
            doc.replay(l.cel(f), tmp, scale)
            p.alpha = (l.opacity * 255).toInt(); c.drawBitmap(tmp, 0f, 0f, p)
        }
        tmp.recycle()
        return out
    }

    fun png(doc: ArtDoc, f: Int, out: File) {
        val b = frame(doc, f)
        out.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    /** Every frame as a numbered PNG (with transparency for assets), in one zip. */
    fun pngSequence(doc: ArtDoc, out: File, progress: (Int) -> Unit) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in 0 until doc.frames) {
                val b = frame(doc, f)
                zip.putNextEntry(ZipEntry("${ArtDocNames.safe(doc.title)}_%04d.png".format(f + 1)))
                b.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry(); b.recycle()
                progress(f + 1)
            }
        }
    }

    /** All frames side by side in rows (for game engines and the like); null if it would be too big. */
    fun spriteSheet(doc: ArtDoc): Bitmap? {
        val n = doc.frames
        val cols = min(n, max(1, (8192 / doc.w))); val rows = (n + cols - 1) / cols
        if (cols * doc.w > 8192 || rows * doc.h > 8192) return null
        val sheet = Bitmap.createBitmap(cols * doc.w, rows * doc.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        for (f in 0 until n) { val b = frame(doc, f); c.drawBitmap(b, (f % cols) * doc.w.toFloat(), (f / cols) * doc.h.toFloat(), null); b.recycle() }
        return sheet
    }

    /**
     * An H.264 MP4 at the drawing's size (4K where the phone's encoder can; otherwise the largest
     * size it can, same shape). Returns the size used, or null if no encoder would take it.
     */
    fun mp4(doc: ArtDoc, out: File, loops: Int, progress: (Int, Int) -> Unit): Pair<Int, Int>? = video(doc, out, loops, false, progress)

    /** Video: H.264 in MP4, or VP9/VP8 in WebM. `loops` = how many times through (1 = straight through once). */
    fun video(doc: ArtDoc, out: File, loops: Int, webm: Boolean, progress: (Int, Int) -> Unit): Pair<Int, Int>? {
        var vw = doc.w and 1.inv(); var vh = doc.h and 1.inv()
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var name: String? = null
        var fmt: MediaFormat? = null
        val mimes = if (webm) listOf(MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_VP8) else listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
        while (vw >= 320 && vh >= 2 && name == null) {
          for (mime in mimes) {
            val f = MediaFormat.createVideoFormat(mime, vw, vh).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (vw.toLong() * vh * doc.fps / 8).coerceIn(2_000_000L, 60_000_000L).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, doc.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            name = codecs.findEncoderForFormat(f)
            if (name != null) { fmt = f; break }
          }
            // step down a size, keeping the shape (and even sides)
            if (name == null) { vw = ((vw * 0.75f).toInt()) and 1.inv(); vh = ((vh * 0.75f).toInt()) and 1.inv() }
        }
        if (name == null || fmt == null) return null
        val codec = MediaCodec.createByCodecName(name)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val mux = MediaMuxer(out.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(codec.outputFormat); mux.start(); started = true }
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) { buf.position(info.offset); buf.limit(info.offset + info.size); mux.writeSampleData(track, buf, info) }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        val scale = vw / doc.w.toFloat()
        val total = doc.frames * max(1, loops)
        val argb = IntArray(vw * vh)
        var cache: Bitmap? = null; var cachedFrame = -1
        for (k in 0 until total) {
            val f = k % doc.frames
            if (f != cachedFrame) {
                cache?.recycle()
                val b = frame(doc, f, scale, transparent = false)
                cache = if (b.width == vw && b.height == vh) b else Bitmap.createScaledBitmap(b, vw, vh, true).also { b.recycle() }
                cachedFrame = f
            }
            cache!!.getPixels(argb, 0, vw, 0, 0, vw, vh)
            var idx: Int
            do { idx = codec.dequeueInputBuffer(10_000); if (idx < 0) drain(false) } while (idx < 0)
            val img = codec.getInputImage(idx)!!
            writeYuv(img, argb, vw, vh)
            codec.queueInputBuffer(idx, 0, vw * vh * 3 / 2, k * 1_000_000L / doc.fps, 0)
            drain(false)
            progress(k + 1, total)
        }
        cache?.recycle()
        var idx: Int
        do { idx = codec.dequeueInputBuffer(10_000); if (idx < 0) drain(false) } while (idx < 0)
        codec.queueInputBuffer(idx, 0, 0, total * 1_000_000L / doc.fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop(); codec.release()
        if (started) mux.stop()
        mux.release()
        return vw to vh
    }

    /** ARGB → the encoder's YUV 4:2:0 planes (BT.601, video range), whatever its layout. */
    private fun writeYuv(img: android.media.Image, argb: IntArray, w: Int, h: Int) {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val yRow = ByteArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = argb[y * w + x]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                yRow[x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            if (yP.pixelStride == 1) { yb.position(y * yP.rowStride); yb.put(yRow, 0, w) }
            else for (x in 0 until w) yb.put(y * yP.rowStride + x * yP.pixelStride, yRow[x])
        }
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            // average the 2 × 2 block
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) { val c = argb[(y * 2 + dy) * w + x * 2 + dx]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
            r = r shr 2; g = g shr 2; b = b shr 2
            val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
            val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
            ub.put(y * uP.rowStride + x * uP.pixelStride, u)
            vb.put(y * vP.rowStride + x * vP.pixelStride, v)
        }
    }
}

/** Animated GIFs: up to `maxSide` px across (GIFs are for sharing, not 4K), looping forever or playing once. */
fun Exporter.gif(doc: ArtDoc, out: File, loop: Boolean, maxSide: Int, progress: (Int, Int) -> Unit): Pair<Int, Int> {
    val scale = minOf(1f, maxSide / maxOf(doc.w, doc.h).toFloat())
    val transparent = doc.bg == 0
    val frames = ArrayList<IntArray>()
    var w = 0; var h = 0
    for (f in 0 until doc.frames) {
        val b = frame(doc, f, scale, transparent)
        w = b.width; h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h); b.recycle()
        frames += px
        progress(f + 1, doc.frames * 2)
    }
    val g = GifEncoder(out, w, h, 1000 / doc.fps, loop, transparent)
    g.buildPalette(frames)
    for ((i, px) in frames.withIndex()) { g.addFrame(px); progress(doc.frames + i + 1, doc.frames * 2) }
    g.finish()
    return w to h
}

object ArtDocNames {
    fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifEmpty { "Drawing" }
}
