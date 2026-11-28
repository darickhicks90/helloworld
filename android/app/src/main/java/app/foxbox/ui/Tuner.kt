package app.foxbox.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.foxbox.Studio
import app.foxbox.audio.Audio
import app.foxbox.audio.SR
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A tuner in a small floating window: pop it up when needed, drag it out of the
 * way, close it when done. It sits over whatever screen is open and leaves the
 * rest of the screen alone, so you can keep playing while it listens. It hears
 * the audio input (for the audio tracks and for recording into samplers) or a
 * sampler block's own sound (to tune a loaded sample with its Tune knob).
 */
object Tuner {
    /** What it can listen to: a label and the Audio.tunerSource value. */
    fun sources(): List<Pair<String, Int>> {
        val out = arrayListOf("Input 1" to 0, "Input 2" to 1, "Inputs 1+2" to 2)
        Audio.engines.forEachIndexed { i, e -> if (e.id in listOf("reflections", "leaves", "pickup") && Studio.isOpen && e.id in Studio.project.blocks) out += "${e.title} (its sound)" to 100 + i }
        return out
    }

    private var panel: Panel? = null
    var a4 = 440f

    val showing get() = panel != null

    /** Open the tuner on `source` (or keep it open and switch to it). */
    fun show(a: Activity, source: Int) {
        val root = a.window.decorView as ViewGroup
        val p = panel ?: Panel(a).also { panel = it
            val w = Px.dp(260); val h = Px.dp(176)
            root.addView(it, FrameLayout.LayoutParams(w, h).apply { leftMargin = (root.width - w) / 2; topMargin = Px.dp(56) })
        }
        p.listen(source)
    }

    fun close() {
        val p = panel ?: return
        panel = null
        p.stop()
        (p.parent as? ViewGroup)?.removeView(p)
    }

    // ------------------------------------------------------------------ pitch (YIN, on a quarter-rate copy)

    /** The pitch in Hz of the newest audio (0 when nothing clear is there), and how loud it is. */
    private fun detect(buf: FloatArray): Pair<Float, Float> {
        val rate = SR / 2f
        val n = buf.size / 2
        val x = FloatArray(n) { (buf[it * 2] + buf[it * 2 + 1]) * 0.5f }
        var rms = 0f; for (v in x) rms += v * v; rms = kotlin.math.sqrt(rms / n)
        if (rms < gate) return 0f to rms
        val tauMax = minOf(n / 2, (rate / 38f).toInt()); val tauMin = (rate / 1400f).toInt().coerceAtLeast(2)
        val w = n - tauMax
        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) { var s = 0f; for (i in 0 until w) { val e = x[i] - x[i + tau]; s += e * e }; d[tau] = s }
        // cumulative mean normalised difference
        var run = 0f; d[0] = 1f
        for (tau in 1..tauMax) { run += d[tau]; d[tau] = if (run > 0f) d[tau] * tau / run else 1f }
        var best = -1
        for (tau in tauMin until tauMax) if (d[tau] < 0.12f) { var t = tau; while (t + 1 < tauMax && d[t + 1] < d[t]) t++; best = t; break }
        if (best < 0) { var mn = 1f; for (tau in tauMin until tauMax) if (d[tau] < mn) { mn = d[tau]; best = tau }; if (mn > 0.3f) return 0f to rms }
        // parabolic interpolation around the dip
        val a0 = d[best - 1]; val b0 = d[best]; val c0 = d[minOf(best + 1, tauMax)]
        val den = a0 - 2 * b0 + c0
        val shift = if (abs(den) > 1e-9f) 0.5f * (a0 - c0) / den else 0f
        return rate / (best + shift) to rms
    }

    /** Quieter than this the tuner doesn't try (rms; set from dB in the panel). */
    @Volatile private var gate = 0.004f
    private val prefs get() = Studio.app.getSharedPreferences("tuner", android.content.Context.MODE_PRIVATE)
    private var gateDb: Float
        get() = prefs.getFloat("gateDb", -48f)
        set(v) { val x = v.coerceIn(-72f, -24f); prefs.edit().putFloat("gateDb", x).apply(); gate = 10f.pow(x / 20f) }
    /** How quickly the needle follows (0.1 steady .. 0.8 quick). */
    private var response: Float
        get() = prefs.getFloat("resp", 0.35f)
        set(v) = prefs.edit().putFloat("resp", v.coerceIn(0.1f, 0.8f)).apply()

    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class Panel(private val a: Activity) : LinearLayout(a) {
        private val face = Face(a)
        private val srcBtn = Btn(a, "Input 1", Pal.AQUA)
        private var source = 0
        @Volatile private var running = true
        @Volatile var hz = 0f; @Volatile var level = 0f
        private var shownCents = 0f
        private val worker: Thread

        init {
            orientation = VERTICAL
            setWillNotDraw(false)
            setPadding(Px.dp(8), Px.dp(8), Px.dp(8), Px.dp(8))
            elevation = Px.dp(12f)
            val head = LinearLayout(a).apply { orientation = HORIZONTAL }
            head.addView(Btn(a, "Tuner", Snes.PURPLE).apply { onDown = { } }, lp(WRAP, Px.dp(34)))
            head.addView(srcBtn.apply { onClick = { pickSource() } }, lp(0, Px.dp(34), 1f).apply { leftMargin = Px.dp(4) })
            head.addView(Btn(a, "✕", Snes.RED, 34).apply { onClick = { close() } }, lp(WRAP, Px.dp(34)).apply { leftMargin = Px.dp(4) })
            addView(head, lp(MATCH, Px.dp(36)))
            addView(face, lp(MATCH, 0, 1f).apply { topMargin = Px.dp(4) })
            val foot = LinearLayout(a).apply { orientation = HORIZONTAL }
            gate = 10f.pow(gateDb / 20f)
            foot.addView(Stepper(a, "A4", 110, { "${a4.roundToInt()} Hz" }) { a4 = (a4 + it).coerceIn(415f, 466f) }, lp(0, Px.dp(36), 1f))
            foot.addView(Stepper(a, "Sens", 110, { "${gateDb.roundToInt()} dB" }) { gateDb -= it * 3f }, lp(0, Px.dp(36), 1f).apply { leftMargin = Px.dp(4) })
            foot.addView(Stepper(a, "Needle", 110, { if (response < 0.25f) "steady" else if (response < 0.5f) "normal" else "quick" }) { response += it * 0.15f }, lp(0, Px.dp(36), 1f).apply { leftMargin = Px.dp(4) })
            addView(foot, lp(MATCH, Px.dp(38)).apply { topMargin = Px.dp(4) })
            worker = Thread {
                val buf = FloatArray(4096)
                while (running) {
                    if (Audio.tunerSource >= 0) {
                        val w = Audio.tunerWrite
                        for (i in buf.indices) buf[i] = Audio.tunerRing[(w - buf.size + i) and 8191]
                        val (f, l) = detect(buf)
                        hz = f; level = l
                    }
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }.apply { isDaemon = true; name = "Tuner"; start() }
        }

        fun listen(src: Int) {
            source = src
            srcBtn.text = sources().firstOrNull { it.second == src }?.first ?: "Input"
            srcBtn.requestLayout(); srcBtn.invalidate()
            Audio.tunerSource = src
            val usesInput = src in 0..2
            if (usesInput && a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Studio.needMic?.invoke { Studio.tunerListening = true } ?: Studio.message("Allow the microphone so the tuner can hear the input")
            } else Studio.tunerListening = usesInput
            if (!usesInput) Studio.message("Play ${srcBtn.text.substringBefore(" (")} and tune it with its Tune knob")
        }

        private fun pickSource() {
            val list = sources()
            SnesDialog.choose(a, "Tuner listens to", list.map { it.first }, list.indexOfFirst { it.second == source }) { i -> listen(list[i].second) }
        }

        fun stop() {
            running = false; worker.interrupt()
            Audio.tunerSource = -1
            Studio.tunerListening = false
        }

        override fun onDraw(c: Canvas) { with(Snes) { c.plate(0f, 0f, width.toFloat(), height.toFloat()) } }

        // drag the window by its edges (anywhere that isn't a button)
        private var dx = 0f; private var dy = 0f
        override fun onInterceptTouchEvent(e: MotionEvent) = false
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val lpar = layoutParams as? FrameLayout.LayoutParams ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - lpar.leftMargin; dy = e.rawY - lpar.topMargin }
                MotionEvent.ACTION_MOVE -> {
                    val root = parent as? ViewGroup ?: return true
                    lpar.leftMargin = (e.rawX - dx).toInt().coerceIn(0, maxOf(0, root.width - width))
                    lpar.topMargin = (e.rawY - dy).toInt().coerceIn(0, maxOf(0, root.height - height))
                    layoutParams = lpar
                }
            }
            return true
        }

        /** The dial: note name, a needle for cents and the frequency. */
        private inner class Face(c: Context) : PixelView(c) {
            init { animated = true }
            private val big = Px.text(30f, Pal.CREAM, headFont = true)
            private val small = Px.text(11f, Pal.CREAM)
            private val p = Paint()
            override fun paint(c: Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                c.pixelBox(0f, 0f, w, h, 0xFF16131C.toInt())
                val f = hz
                val ok = f > 20f
                val midi = if (ok) 69f + 12f * (ln(f / a4) / ln(2f)) else 0f
                val near = midi.roundToInt()
                val cents = if (ok) (midi - near) * 100f else 0f
                if (ok) shownCents += (cents - shownCents) * response else shownCents *= 0.9f
                // the scale: -50 .. +50 cents
                val cy = h * 0.72f; val l = Px.dp(14f); val r = w - Px.dp(14f)
                for (k in -5..5) {
                    val x = l + (k + 5) / 10f * (r - l)
                    p.color = if (k == 0) Pal.GREEN_L else Pal.alpha(Pal.CREAM, 0.4f)
                    c.drawRect(x - u * 0.5f, cy - (if (k % 5 == 0) Px.dp(10f) else Px.dp(5f)), x + u * 0.5f, cy, p)
                }
                val inTune = ok && abs(cents) < 4f
                val nx = l + (shownCents.coerceIn(-50f, 50f) + 50f) / 100f * (r - l)
                p.color = if (!ok) Pal.alpha(Pal.CREAM, 0.25f) else if (inTune) Pal.GREEN_L else if (abs(cents) < 15f) Pal.SUN else Pal.ORANGE
                c.drawRect(nx - Px.dp(2f), cy - Px.dp(22f), nx + Px.dp(2f), cy + Px.dp(4f), p)
                // the two lamps either side tell which way to turn
                p.color = if (ok && cents < -4f) Pal.ORANGE else 0xFF3A3442.toInt(); c.drawRect(Px.dp(8f), Px.dp(8f), Px.dp(22f), Px.dp(22f), p)
                p.color = if (ok && cents > 4f) Pal.ORANGE else 0xFF3A3442.toInt(); c.drawRect(w - Px.dp(22f), Px.dp(8f), w - Px.dp(8f), Px.dp(22f), p)
                big.color = if (inTune) Pal.GREEN_L else Pal.CREAM
                big.textSize = Px.dp(28f)
                c.textCentered(if (ok) NAMES[Math.floorMod(near, 12)] + (Math.floorDiv(near, 12) - 1) else "–", w / 2f, h * 0.3f, big)
                small.textSize = Px.dp(10f)
                c.textCentered(if (ok) "%.1f Hz   %+d cents".format(f, cents.roundToInt()) else if (level > 0.001f) "listening…" else "play a note", w / 2f, h * 0.9f, small)
            }
        }
    }
}
