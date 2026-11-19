package app.foxbox.radio

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.Toast
import app.foxbox.data.AudioFiles
import app.foxbox.ui.Px
import app.foxbox.ui.Snes
import app.foxbox.ui.SnesDialog
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Where a recording off the air can go (the open project's samplers), and how it gets there. */
interface RadioHost {
    /** Target id → label, e.g. "reflections" → "Load into Reflections". Empty with no project open. */
    fun sampleTargets(): List<Pair<String, String>>
    fun deliverSample(target: String, data: FloatArray, name: String)
}

/** The radio pop-up: a 1980s portable (not a boom box) with piano keys along the top. */
object RadioPanel {
    fun show(a: Activity, host: RadioHost) {
        Radio.init(a)
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = RadioView(a, host, d)
        d.setContentView(v)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.5f)
        }
        Radio.onPanel = { v.postInvalidate() }
        d.setOnDismissListener { Radio.onPanel = null }
        d.show()
        val dm = a.resources.displayMetrics
        val width = minOf(dm.widthPixels * 0.94f, Px.dp(640f)).toInt()
        d.window?.setLayout(width, minOf((width * 0.64f).toInt(), (dm.heightPixels * 0.9f).toInt()))
    }
}

@SuppressLint("ViewConstructor")
class RadioView(private val a: Activity, private val host: RadioHost, private val dialog: Dialog) : View(a) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Px.head; textAlign = Paint.Align.CENTER }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Px.body }
    private val box = RectF(); private val dial = RectF(); private val grille = RectF(); private val knob = RectF(); private val wheel = RectF()
    private val keys = Array(6) { RectF() }
    private val keyNames = arrayOf("◀◀", "PLAY", "▶▶", "● REC", "+ ADD", "− DEL")
    private var downKey = -1
    private var needle = -1f                 // 0..1 across the dial, eased toward the station
    private var dragNeedle: Float? = null
    private var wasRecording = Radio.recording
    private var mode = 0; private var lastY = 0f

    private companion object {
        const val CHARCOAL = 0xFF2A292F.toInt(); const val CHARCOAL_D = 0xFF17161A.toInt()
        const val SILVER = 0xFFC7CAD2.toInt(); const val SILVER_D = 0xFF8E929C.toInt(); const val CHROME = 0xFFE6E8EE.toInt()
        const val GLASS = 0xFF10161A.toInt(); const val AMBER = 0xFFF2B33D.toInt(); const val SCALE = 0xFFE8E0C8.toInt()
    }

    init { isClickable = true }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val d = Px.dp(1f)
        box.set(4 * d, h * 0.13f, w - 4 * d, h - 4 * d)
        val bh = box.height()
        dial.set(box.left + bh * 0.08f, box.top + bh * 0.14f, box.right - bh * 0.08f, box.top + bh * 0.46f)
        grille.set(box.left + bh * 0.08f, dial.bottom + bh * 0.08f, box.left + box.width() * 0.62f, box.bottom - bh * 0.08f)
        val kr = (box.bottom - bh * 0.08f - (dial.bottom + bh * 0.08f)) / 2
        knob.set(grille.right + bh * 0.1f, dial.bottom + bh * 0.08f, grille.right + bh * 0.1f + kr * 2, box.bottom - bh * 0.08f)
        wheel.set(box.right - bh * 0.16f, dial.bottom + bh * 0.1f, box.right - bh * 0.05f, box.bottom - bh * 0.1f)
        // piano keys along the top edge, standing up out of the case
        val kl = box.left + box.width() * 0.12f; val kw = box.width() * 0.62f / keys.size
        for (i in keys.indices) keys[i].set(kl + i * kw + 2 * d, box.top - bh * 0.09f, kl + (i + 1) * kw - 2 * d, box.top + bh * 0.05f)
    }

    private fun stationX(i: Int): Float { val n = Radio.stations.size.coerceAtLeast(1); return (i + 0.5f) / n }

    override fun onDraw(c: Canvas) {
        val d = Px.dp(1f); val t = SystemClock.uptimeMillis() / 1000f
        // recording stopped on its own (the 60 second limit, or the station dropped out)
        if (wasRecording && !Radio.recording) { wasRecording = false; post { finishRecording() } }
        wasRecording = Radio.recording
        // the telescopic antenna, pulled out and angled back
        val ax = box.right - box.width() * 0.1f
        p.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 3) {
            val f0 = k / 3f; val f1 = (k + 1) / 3f
            p.color = if (k % 2 == 0) CHROME else SILVER_D; p.strokeWidth = (4.5f - k) * d
            c.drawLine(ax - (ax - width * 0.6f) * f0, box.top - (box.top - 2 * d) * f0, ax - (ax - width * 0.6f) * f1, box.top - (box.top - 2 * d) * f1, p)
        }
        p.color = CHROME; c.drawCircle(width * 0.6f, 4 * d, 3.5f * d, p)
        // the keys first (the case's lip goes over their feet)
        for (i in keys.indices) {
            val r = keys[i]; val pressed = downKey == i || (i == 1 && Radio.playing) || (i == 3 && Radio.recording)
            val top = if (pressed) r.top + 4 * d else r.top
            p.color = CHARCOAL_D; c.drawRoundRect(r.left, top, r.right, r.bottom, 3 * d, 3 * d, p)
            p.color = if (i == 3) 0xFFC8342C.toInt() else SILVER
            c.drawRoundRect(r.left + d, top + d, r.right - d, r.bottom - 2 * d, 3 * d, 3 * d, p)
            p.color = 0x66FFFFFF; c.drawRect(r.left + 3 * d, top + 2 * d, r.right - 3 * d, top + 3.5f * d, p)
            head.textSize = minOf(r.height() * 0.28f, 9 * d); head.color = if (i == 3) Color.WHITE else CHARCOAL
            val label = if (i == 1 && Radio.playing) "STOP" else keyNames[i]
            c.drawText(label, r.centerX(), top + (r.bottom - top) * 0.45f, head)
        }
        // the case: charcoal plastic, a brushed silver face over the top half, chrome trim
        p.shader = LinearGradient(0f, box.top, 0f, box.bottom, CHARCOAL, CHARCOAL_D, Shader.TileMode.CLAMP)
        c.drawRoundRect(box, 16 * d, 16 * d, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = 0xFF55545C.toInt(); c.drawRoundRect(box, 16 * d, 16 * d, p); p.style = Paint.Style.FILL
        val face = RectF(box.left + 6 * d, box.top + 6 * d, box.right - 6 * d, dial.bottom + box.height() * 0.04f)
        p.shader = LinearGradient(0f, face.top, 0f, face.bottom, intArrayOf(CHROME, SILVER, SILVER_D), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(face, 11 * d, 11 * d, p); p.shader = null
        p.color = 0x14000000
        var by = face.top + 2 * d
        while (by < face.bottom) { c.drawRect(face.left + 8 * d, by, face.right - 8 * d, by + 0.6f * d, p); by += 2.2f * d }
        drawDial(c, t)
        drawGrille(c)
        drawKnob(c)
        drawWheel(c)
        // lamps: ON AIR (green, flickering while it tunes in), REC (red) with the seconds recorded
        val ly = dial.bottom + box.height() * 0.04f
        lamp(c, knob.left, ly, "ON AIR", if (Radio.playing && (!Radio.buffering || (t * 4).toInt() % 2 == 0)) 0xFF5BE36A.toInt() else 0xFF2E4A32.toInt())
        lamp(c, knob.left + box.width() * 0.13f, ly, if (Radio.recording) "REC %d:%02d".format(Radio.recordedSeconds.toInt() / 60, Radio.recordedSeconds.toInt() % 60) else "REC",
            if (Radio.recording && (t * 2).toInt() % 2 == 0) 0xFFFF4A3A.toInt() else 0xFF4A2626.toInt())
        if (isShown) postInvalidateOnAnimation()
    }

    private fun lamp(c: Canvas, x: Float, y: Float, label: String, col: Int) {
        val d = Px.dp(1f)
        p.color = CHARCOAL_D; c.drawCircle(x + 5 * d, y + 5 * d, 4.5f * d, p)
        p.color = col; c.drawCircle(x + 5 * d, y + 5 * d, 3.2f * d, p)
        head.textSize = 7 * d; head.color = SILVER; head.textAlign = Paint.Align.LEFT
        c.drawText(label, x + 13 * d, y + 8 * d, head); head.textAlign = Paint.Align.CENTER
    }

    private fun drawDial(c: Canvas, t: Float) {
        val d = Px.dp(1f)
        p.color = CHARCOAL_D; c.drawRoundRect(dial.left - 2 * d, dial.top - 2 * d, dial.right + 2 * d, dial.bottom + 2 * d, 6 * d, 6 * d, p)
        p.shader = LinearGradient(0f, dial.top, 0f, dial.bottom, 0xFF1C2630.toInt(), GLASS, Shader.TileMode.CLAMP)
        c.drawRoundRect(dial, 5 * d, 5 * d, p); p.shader = null
        // the scale: FM 88 … 108 MHz, ticks, and each station as an amber marker
        val sl = dial.left + dial.width() * 0.08f; val sr = dial.right - dial.width() * 0.08f; val sy = dial.top + dial.height() * 0.42f
        p.color = SCALE
        for (k in 0..40) { val x = sl + (sr - sl) * k / 40f; val tall = k % 5 == 0; c.drawRect(x - 0.5f * d, sy - (if (tall) 7 else 4) * d, x + 0.5f * d, sy, p) }
        head.textSize = 7.5f * d; head.color = SCALE
        for (k in 0..5) c.drawText("${88 + k * 4}", sl + (sr - sl) * k / 5f, sy - 10 * d, head)
        head.textAlign = Paint.Align.LEFT; c.drawText("FM", dial.left + 5 * d, sy - 2 * d, head)
        head.textAlign = Paint.Align.RIGHT; c.drawText("MHz", dial.right - 5 * d, sy - 2 * d, head); head.textAlign = Paint.Align.CENTER
        val n = Radio.stations.size
        for (i in 0 until n) {
            val x = sl + (sr - sl) * stationX(i)
            p.color = if (i == Radio.current) AMBER else 0xAAF2B33D.toInt()
            c.drawRect(x - 2 * d, sy + 2 * d, x + 2 * d, sy + 6 * d, p)
            head.textSize = 6.5f * d; head.color = p.color; c.drawText("${i + 1}", x, sy + 14 * d, head)
        }
        // the needle eases toward the station (or follows a finger dragging along the dial)
        val target = dragNeedle ?: if (Radio.current >= 0) stationX(Radio.current) else 0.5f
        needle = if (needle < 0) target else needle + (target - needle) * 0.18f
        val nx = sl + (sr - sl) * needle
        p.color = 0x55FF4A3A; c.drawRect(nx - 3 * d, dial.top + 3 * d, nx + 3 * d, dial.bottom - 3 * d, p)
        p.color = 0xFFFF4A3A.toInt(); c.drawRect(nx - d, dial.top + 3 * d, nx + d, dial.bottom - 3 * d, p)
        // the readout: station, and what's on (scrolling)
        body.textSize = minOf(dial.height() * 0.2f, 14 * d); body.color = AMBER; body.textAlign = Paint.Align.LEFT
        val st = Radio.stations.getOrNull(Radio.current)
        val line1 = when {
            Radio.stations.isEmpty() -> "No stations: press + ADD"
            dragNeedle != null -> Radio.stations.getOrNull(nearest(dragNeedle!!))?.name ?: ""
            st == null -> "Tune with the knob or the keys"
            else -> "${Radio.current + 1}. ${st.name}"
        }
        val line2 = Radio.error ?: when { Radio.buffering -> "tuning in…"; Radio.playing -> Radio.nowTitle ?: "on the air"; else -> "off" }
        c.save(); c.clipRect(dial.left + 4 * d, dial.top, dial.right - 4 * d, dial.bottom)
        c.drawText(fit(line1, dial.width() - 16 * d), dial.left + 8 * d, dial.bottom - dial.height() * 0.3f, body)
        body.color = if (Radio.error != null) 0xFFFF7A6A.toInt() else 0xCCF2B33D.toInt()
        val w2 = body.measureText(line2); val room = dial.width() - 16 * d
        val off = if (w2 > room) ((t * 30 * d) % (w2 + 40 * d)) else 0f
        c.drawText(line2, dial.left + 8 * d - off, dial.bottom - dial.height() * 0.08f, body)
        if (off > 0f) c.drawText(line2, dial.left + 8 * d - off + w2 + 40 * d, dial.bottom - dial.height() * 0.08f, body)
        c.restore()
        // glass glare
        p.color = 0x14FFFFFF; c.drawRect(dial.left + 4 * d, dial.top + 3 * d, dial.right - 4 * d, dial.top + dial.height() * 0.2f, p)
    }

    private fun fit(s: String, w: Float): String { var t = s; while (t.length > 3 && body.measureText(t) > w) t = t.dropLast(2) + "…"; return t }

    private fun drawGrille(c: Canvas) {
        val d = Px.dp(1f)
        p.color = CHARCOAL_D; c.drawRoundRect(grille, 8 * d, 8 * d, p)
        // rows of punched holes
        val step = 6 * d
        p.color = 0xFF0B0B0D.toInt()
        var y = grille.top + step; var row = 0
        while (y < grille.bottom - step * 0.6f) {
            var x = grille.left + step + (if (row % 2 == 0) 0f else step / 2)
            while (x < grille.right - step * 0.6f) { c.drawCircle(x, y, 1.6f * d, p); x += step }
            y += step * 0.86f; row++
        }
        // the name plate
        val pw = grille.width() * 0.46f; val ph = grille.height() * 0.2f
        val pl = grille.centerX() - pw / 2; val pt = grille.bottom - ph - 8 * d
        p.shader = LinearGradient(0f, pt, 0f, pt + ph, CHROME, SILVER_D, Shader.TileMode.CLAMP)
        c.drawRoundRect(pl, pt, pl + pw, pt + ph, 4 * d, 4 * d, p); p.shader = null
        head.textSize = ph * 0.42f; head.color = CHARCOAL
        c.drawText("5IY", pl + pw * 0.24f, pt + ph * 0.68f, head)
        body.textSize = ph * 0.34f; body.color = 0xFF8A2A24.toInt(); body.textAlign = Paint.Align.LEFT
        c.drawText("FM · NET RADIO", pl + pw * 0.44f, pt + ph * 0.64f, body)
    }

    private fun drawKnob(c: Canvas) {
        val d = Px.dp(1f)
        val cx = knob.centerX(); val cy = knob.centerY() + knob.height() * 0.06f; val r = minOf(knob.width(), knob.height()) * 0.38f
        p.color = CHARCOAL_D; c.drawCircle(cx + 2 * d, cy + 3 * d, r + 2 * d, p)
        p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f, CHROME, SILVER_D, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.shader = null
        p.color = 0x33000000; p.strokeWidth = d
        for (k in 0 until 36) { val a = Math.PI * 2 * k / 36; c.drawLine(cx + (cos(a) * r * 0.86f).toFloat(), cy + (sin(a) * r * 0.86f).toFloat(), cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), p) }
        val a = Math.toRadians(-220.0 + 260.0 * needle.coerceIn(0f, 1f))
        p.color = CHARCOAL; p.strokeWidth = 2.5f * d
        c.drawLine(cx, cy, cx + (cos(a) * r * 0.7f).toFloat(), cy + (sin(a) * r * 0.7f).toFloat(), p)
        head.textSize = 7 * d; head.color = SILVER
        c.drawText("◀ TUNING ▶", cx, knob.bottom + 2 * d, head)
    }

    private fun drawWheel(c: Canvas) {
        val d = Px.dp(1f)
        p.color = CHARCOAL_D; c.drawRoundRect(wheel, 4 * d, 4 * d, p)
        p.color = 0xFF3E3D45.toInt(); c.drawRoundRect(wheel.left + 2 * d, wheel.top + 2 * d, wheel.right - 2 * d, wheel.bottom - 2 * d, 3 * d, 3 * d, p)
        // ridges that move as the volume turns
        val off = (Radio.volume * 40 * d) % (5 * d)
        p.color = 0xFF6A6972.toInt()
        var y = wheel.top + 3 * d + off
        while (y < wheel.bottom - 3 * d) { c.drawRect(wheel.left + 3 * d, y, wheel.right - 3 * d, y + 1.5f * d, p); y += 5 * d }
        val fy = wheel.bottom - (wheel.height()) * Radio.volume
        p.color = AMBER; c.drawRect(wheel.right + 2 * d, fy - d, wheel.right + 5 * d, fy + d, p)
        head.textSize = 7 * d; head.color = SILVER
        c.drawText("VOL", wheel.centerX(), wheel.top - 3 * d, head)
    }

    private fun nearest(f: Float): Int = if (Radio.stations.isEmpty()) -1 else (0 until Radio.stations.size).minByOrNull { abs(stationX(it) - f) }!!

    // ---- touch
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downKey = keys.indexOfFirst { RectF(it.left, it.top - Px.dp(8f), it.right, it.bottom).contains(x, y) }
                mode = when {
                    downKey >= 0 -> 1
                    dial.contains(x, y) -> { dragNeedle = dialF(x); 2 }
                    RectF(wheel.left - Px.dp(14f), wheel.top, wheel.right + Px.dp(14f), wheel.bottom).contains(x, y) -> { lastY = y; 3 }
                    knob.contains(x, y) -> 4
                    !box.contains(x, y) && y > box.top -> 5
                    else -> 0
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                2 -> { dragNeedle = dialF(x) }
                3 -> { Radio.volume = Radio.volume + (lastY - y) / wheel.height(); lastY = y }
                else -> {}
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    1 -> if (keys[downKey].contains(x, y) || abs(y - keys[downKey].centerY()) < Px.dp(30f)) press(downKey)
                    2 -> { val i = nearest(dialF(x)); dragNeedle = null; if (i >= 0) Radio.play(i) }
                    4 -> Radio.tune(if (x < knob.centerX()) -1 else 1)
                    5 -> dialog.dismiss()
                    else -> {}
                }
                downKey = -1; mode = 0; dragNeedle = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { downKey = -1; mode = 0; dragNeedle = null }
        }
        return true
    }

    private fun dialF(x: Float): Float {
        val sl = dial.left + dial.width() * 0.08f; val sr = dial.right - dial.width() * 0.08f
        return ((x - sl) / (sr - sl)).coerceIn(0f, 1f)
    }

    private fun press(k: Int) {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        when (k) {
            0 -> Radio.tune(-1)
            1 -> if (Radio.playing) Radio.stop() else if (Radio.stations.isEmpty()) addMenu() else Radio.play(Radio.current.coerceAtLeast(0))
            2 -> Radio.tune(1)
            3 -> if (Radio.recording) finishRecording() else if (!Radio.startRecording()) toast("Tune in to a station first, then record")
            4 -> addMenu()
            5 -> Radio.stations.getOrNull(Radio.current)?.let { s ->
                SnesDialog.ask(a, "Remove ${s.name}?", "It comes off the dial. You can add it again any time.", "Keep" to null, "Remove" to { Radio.remove(Radio.current) })
            } ?: toast("Tune to the station you want to remove")
        }
    }

    private fun addMenu() {
        SnesDialog.choose(a, "Add a station", listOf("Search stations by name", "Add a stream link (URL)")) { i ->
            if (i == 0) SnesDialog.text(a, "Search for a station", "") { q -> if (q.isNotBlank()) search(q.trim()) }
            else SnesDialog.text(a, "Stream link (http://… or https://…)", "https://") { url ->
                val u = url.trim()
                if (!u.startsWith("http")) return@text toast("That doesn't look like a stream link")
                SnesDialog.text(a, "Name this station", u.substringAfter("://").substringBefore('/')) { name -> Radio.add(Radio.Station(name.ifBlank { "Station" }, u)) }
            }
        }
    }

    private fun search(q: String) {
        toast("Searching for \"$q\"…")
        Thread {
            val found = runCatching { Radio.search(q) }
            a.runOnUiThread {
                val list = found.getOrNull()
                when {
                    list == null -> toast("Couldn't reach the station directory")
                    list.isEmpty() -> toast("No stations called \"$q\"")
                    else -> SnesDialog.choose(a, "Stations: \"$q\"", list.map { it.name.take(34) + if (it.info.isNotBlank()) "  (${it.info})" else "" }) { i ->
                        Radio.add(Radio.Station(list[i].name, list[i].url))
                    }
                }
            }
        }.start()
    }

    /** Recording stopped (by REC, the 60 second limit, or the station dropping): choose where it goes. */
    private fun finishRecording() {
        val (raw, rate) = Radio.stopRecording()
        wasRecording = false
        if (raw.size < rate / 4) { toast("That was too short to keep"); return }
        val data = AudioFiles.resample(raw, rate)
        val name = (Radio.stations.getOrNull(Radio.current)?.name ?: "Radio").take(18)
        val targets = host.sampleTargets()
        val labels = targets.map { it.second } + "Save as a WAV file"
        SnesDialog.choose(a, "%.1f s off the air: where to?".format(data.size / app.foxbox.audio.SR.toFloat()), labels) { i ->
            if (i < targets.size) host.deliverSample(targets[i].first, data, name)
            else Thread {
                val f = File(a.cacheDir, "radio.wav")
                AudioFiles.writeWav(f, data)
                val uri = runCatching { app.foxbox.data.ProjectStore.export(null, "Radio", app.foxbox.data.ProjectStore.safe(name) + " " + System.currentTimeMillis() / 1000 + ".wav", "audio/wav", f.readBytes()) }.getOrNull()
                a.runOnUiThread { toast(if (uri != null) "Saved to your 5IY Radio folder" else "Couldn't save the recording") }
            }.start()
        }
    }

    private fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()
}
