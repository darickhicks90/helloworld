package app.foxbox.pocket.art

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

/**
 * An animated GIF writer. One palette (up to 256 colours, chosen by median cut from all the frames)
 * is shared by every frame, so colours don't shimmer from frame to frame. Clear pixels stay clear
 * (for assets). `loop` = true plays forever; false plays once.
 */
class GifEncoder(file: File, private val w: Int, private val h: Int, private val delayMs: Int, private val loop: Boolean, private val transparent: Boolean) {
    private val out: OutputStream = BufferedOutputStream(file.outputStream(), 1 shl 16)
    private var palette = IntArray(0)
    private val lookup = ByteArray(1 shl 15)   // 5-5-5 RGB → palette index
    private var started = false

    /** Chooses the palette from (a sample of) all the frames' pixels. */
    fun buildPalette(frames: List<IntArray>) {
        val samples = ArrayList<Int>()
        val step = maxOf(1, frames.sumOf { it.size } / 200_000)
        var k = 0
        for (f in frames) { var i = k % step; while (i < f.size) { val c = f[i]; if (!transparent || (c ushr 24) >= 128) samples += c and 0xFFFFFF; i += step }; k++ }
        val colours = if (transparent) 255 else 256
        palette = medianCut(samples, colours)
        // the lookup: every 5-5-5 colour → its nearest palette entry
        for (key in 0 until (1 shl 15)) {
            val r = ((key shr 10) and 31) * 255 / 31; val g = ((key shr 5) and 31) * 255 / 31; val b = (key and 31) * 255 / 31
            var best = 0; var bd = Int.MAX_VALUE
            for (i in palette.indices) {
                val p = palette[i]; val dr = ((p shr 16) and 255) - r; val dg = ((p shr 8) and 255) - g; val db = (p and 255) - b
                val dd = dr * dr * 3 + dg * dg * 4 + db * db * 2
                if (dd < bd) { bd = dd; best = i }
            }
            lookup[key] = (best + if (transparent) 1 else 0).toByte()
        }
    }

    private fun medianCut(samples: List<Int>, n: Int): IntArray {
        if (samples.isEmpty()) return IntArray(1)
        val boxes = ArrayList<IntArray>()
        boxes += samples.toIntArray()
        while (boxes.size < n) {
            // split the box with the widest spread
            var bi = -1; var bspread = 0; var bch = 0
            for ((i, b) in boxes.withIndex()) {
                if (b.size < 2) continue
                for (ch in 0..2) {
                    val sh = 16 - ch * 8
                    var lo = 255; var hi = 0
                    for (c in b) { val v = (c shr sh) and 255; if (v < lo) lo = v; if (v > hi) hi = v }
                    if (hi - lo > bspread) { bspread = hi - lo; bi = i; bch = ch }
                }
            }
            if (bi < 0) break
            val b = boxes.removeAt(bi); val sh = 16 - bch * 8
            val sorted = b.sortedBy { (it shr sh) and 255 }
            val mid = sorted.size / 2
            boxes += sorted.subList(0, mid).toIntArray(); boxes += sorted.subList(mid, sorted.size).toIntArray()
        }
        return IntArray(boxes.size) { i ->
            val b = boxes[i]; var r = 0L; var g = 0L; var bl = 0L
            for (c in b) { r += (c shr 16) and 255; g += (c shr 8) and 255; bl += c and 255 }
            val m = maxOf(1, b.size)
            ((r / m).toInt() shl 16) or ((g / m).toInt() shl 8) or (bl / m).toInt()
        }
    }

    private fun header() {
        out.write("GIF89a".toByteArray())
        short(w); short(h)
        out.write(0xF7)          // a global colour table of 256 entries
        out.write(0); out.write(0)
        val entries = 256
        for (i in 0 until entries) {
            val c = if (transparent) (if (i == 0) 0 else palette.getOrElse(i - 1) { 0 }) else palette.getOrElse(i) { 0 }
            out.write((c shr 16) and 255); out.write((c shr 8) and 255); out.write(c and 255)
        }
        if (loop) {
            // NETSCAPE2.0: play forever
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); out.write("NETSCAPE2.0".toByteArray()); out.write(byteArrayOf(3, 1, 0, 0, 0))
        }
        started = true
    }

    fun addFrame(argb: IntArray) {
        if (!started) header()
        // graphic control: the delay, and colour 0 as clear for see-through drawings
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, (if (transparent) 0x09 else 0x08).toByte()))
        short((delayMs + 5) / 10); out.write(0); out.write(0)
        out.write(0x2C); short(0); short(0); short(w); short(h); out.write(0)
        val idx = ByteArray(w * h)
        for (i in idx.indices) {
            val c = argb[i]
            idx[i] = if (transparent && (c ushr 24) < 128) 0 else lookup[(((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)]
        }
        lzw(idx)
    }

    fun finish() { if (!started) header(); out.write(0x3B); out.close() }

    private fun short(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }

    /** GIF's LZW, 8-bit codes, written in sub-blocks. */
    private fun lzw(data: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 1 shl minCode; val eoi = clear + 1
        val block = ByteArray(255); var bn = 0
        var acc = 0; var bits = 0
        fun emit(code: Int, size: Int) {
            acc = acc or (code shl bits); bits += size
            while (bits >= 8) {
                block[bn++] = (acc and 255).toByte(); acc = acc ushr 8; bits -= 8
                if (bn == 255) { out.write(255); out.write(block, 0, 255); bn = 0 }
            }
        }
        val table = HashMap<Int, Int>(8192)
        var next = eoi + 1; var size = minCode + 1
        emit(clear, size)
        if (data.isEmpty()) { emit(eoi, size) } else {
            var prefix = data[0].toInt() and 255
            for (i in 1 until data.size) {
                val k = data[i].toInt() and 255
                val key = (prefix shl 8) or k
                val hit = table[key]
                if (hit != null) { prefix = hit; continue }
                emit(prefix, size)
                if (next < 4096) {
                    table[key] = next++
                    if (next > (1 shl size) && size < 12) size++
                } else {
                    emit(clear, size); table.clear(); next = eoi + 1; size = minCode + 1
                }
                prefix = k
            }
            emit(prefix, size)
            emit(eoi, size)
        }
        if (bits > 0) { block[bn++] = (acc and 255).toByte(); if (bn == 255) { out.write(255); out.write(block, 0, 255); bn = 0 } }
        if (bn > 0) { out.write(bn); out.write(block, 0, bn) }
        out.write(0)
    }
}
