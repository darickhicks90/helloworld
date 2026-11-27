package app.foxbox.audio

import android.os.Handler
import android.os.Looper
import app.foxbox.data.AmpLibrary
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * An amp and cabinet on one audio track: a Neural Amp Modeler capture, then a
 * cabinet impulse response. Off until switched on. Captures and impulses load on
 * a background thread and are swapped in between audio blocks; the old ones are
 * freed a moment later, once the audio thread can no longer be using them.
 */
class AmpRig(val ch: Int) {
    val p = Params()
    init { p.section("Amp") }
    val on = p.toggle("ampOn", "Amp")
    val input = p.num("ampIn", "Input", -24f, 24f, 0f, Fmt.DB)
    val output = p.num("ampOut", "Output", -36f, 12f, -6f, Fmt.DB)
    init { p.section("Cab") }
    val cabOn = p.toggle("cabOn", "Cab", true)
    val cabMix = p.num("cabMix", "Cab Mix", 0f, 1f, 1f, Fmt.PCT)

    /** Names of the chosen capture and impulse (as AmpLibrary lists them). */
    @Volatile var ampName = AmpLibrary.DEFAULT_AMP; private set
    @Volatile var cabName = AmpLibrary.DEFAULT_CAB; private set
    /** Something to tell the person (a capture that wouldn't load). */
    var onProblem: ((String) -> Unit)? = null

    @Volatile private var nam = 0L
    @Volatile private var ir = 0L
    /** Brings a capture's loudness to about -18 dB (from its metadata, when it has some). */
    @Volatile private var trim = 1f
    private var loaded = ""
    private var loadedCab = ""

    private val mono = FloatArray(Audio.BLOCK)
    private val dry = FloatArray(Audio.BLOCK)
    private var gl = 1f; private var gr = 1f

    val ready get() = nam != 0L

    init { on.listeners.add { if (on.on) prepare() } }

    fun reset() { p.all.values.forEach { it.set(it.def) }; ampName = AmpLibrary.DEFAULT_AMP; cabName = AmpLibrary.DEFAULT_CAB }

    fun save(): JSONObject = p.save().put("amp", ampName).put("cab", cabName)
    fun load(o: JSONObject?) {
        p.load(o)
        ampName = o?.optString("amp", AmpLibrary.DEFAULT_AMP) ?: AmpLibrary.DEFAULT_AMP
        cabName = o?.optString("cab", AmpLibrary.DEFAULT_CAB) ?: AmpLibrary.DEFAULT_CAB
        if (on.on) prepare()
    }

    fun chooseAmp(name: String) { ampName = name; loaded = ""; prepare() }
    fun chooseCab(name: String) { cabName = name; loadedCab = ""; prepare() }

    /** Load whatever is chosen but not yet loaded (call when switching the amp on, or choosing). */
    fun prepare() {
        if (!NativeNam.available) { onProblem?.invoke("The amp needs this phone's native audio library, which didn't load"); return }
        val a = ampName; val c = cabName
        Thread {
            if (a != loaded) {
                val m = runCatching { AmpLibrary.loadAmp(a) }
                m.onSuccess { cap ->
                    val h = NativeNam.nLoad(cap.cfg, cap.weights)
                    if (h == 0L) onProblem?.invoke("$a uses a NAM format 5IY can't play yet")
                    else { loaded = a; swapNam(h, cap.trim) }
                }.onFailure { onProblem?.invoke(it.message ?: "Couldn't load $a") }
            }
            if (c != loadedCab) {
                runCatching { AmpLibrary.loadCab(c) }.onSuccess { taps ->
                    val h = NativeNam.nIrLoad(taps)
                    if (h != 0L) { loadedCab = c; swapIr(h) }
                }.onFailure { onProblem?.invoke(it.message ?: "Couldn't load $c") }
            }
        }.start()
    }

    private fun swapNam(h: Long, t: Float) = Audio.post { val old = nam; nam = h; trim = t; if (old != 0L) retire { NativeNam.nFree(old) } }
    private fun swapIr(h: Long) = Audio.post { val old = ir; ir = h; if (old != 0L) retire { NativeNam.nIrFree(old) } }

    /** Process one block of the track in place (audio thread). */
    fun process(l: FloatArray, r: FloatArray, n: Int) {
        if (!on.on) return
        val h = nam; val c = ir
        if (h == 0L && c == 0L) return
        // the track is mono with a pan: run the mono sound and put the pan back afterwards
        var el = 0f; var er = 0f
        for (i in 0 until n) { val a = l[i]; val b = r[i]; mono[i] = (a + b) * 0.5f; el += a * a; er += b * b }
        val em = (el + er) * 0.5f
        if (em > 1e-9f) {
            val tl = sqrt(el / em); val tr = sqrt(er / em)
            gl += (tl - gl) * 0.2f; gr += (tr - gr) * 0.2f
        }
        val gin = 10f.pow(input.value / 20f)
        for (i in 0 until n) mono[i] *= gin
        if (h != 0L) NativeNam.nProcess(h, mono, n)
        val gout = 10f.pow(output.value / 20f) * trim
        for (i in 0 until n) mono[i] *= gout
        if (c != 0L && cabOn.on) {
            val mix = cabMix.value
            System.arraycopy(mono, 0, dry, 0, n)
            NativeNam.nIrProcess(c, mono, n)
            if (mix < 0.999f) for (i in 0 until n) mono[i] = dry[i] + (mono[i] - dry[i]) * mix
        }
        for (i in 0 until n) { val v = mono[i]; l[i] = v * gl; r[i] = v * gr }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        /** Free a swapped-out capture once the audio thread has moved on to the new one. */
        private fun retire(free: () -> Unit) { main.postDelayed({ free() }, 500) }
    }
}
