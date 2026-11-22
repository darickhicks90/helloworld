package app.fiveiy.kids

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import app.foxbox.audio.SR
import app.foxbox.ui.Px
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Little Yeti: a first synth, drum machine and sampler for 5 to 10 year olds. */
class KidsActivity : Activity(), KidsView.Host {
    private lateinit var view: KidsView
    private val prefs by lazy { getSharedPreferences("little_yeti", MODE_PRIVATE) }
    private var afterMic: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        Px.init(this)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        Sound.init(rate)
        load()
        // (`--ei page N` opens a given page: used by the automated screenshot check)
        view = KidsView(this, this).apply { page = intent?.getIntExtra("page", -1)?.takeIf { it in 0..2 } ?: prefs.getInt("page", 0) }
        setContentView(view)
    }

    override fun onStart() {
        super.onStart(); immersive()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        Sound.speaker = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type in HEADPHONES }
        Sound.start()
    }
    override fun onStop() { Recorder.stop(); save(); Sound.stop(); super.onStop() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    // ------------------------------------------------------------------ the microphone

    override fun withMic(then: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) then()
        else { afterMic = then; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val ok = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        afterMic?.let { if (ok) it() }; afterMic = null
    }

    // ------------------------------------------------------------------ remembering everything

    private val sampleFile get() = File(filesDir, "sample.f32")
    override fun sampleRecorded(d: FloatArray) {
        Thread {
            runCatching {
                val bb = ByteBuffer.allocate(8 + d.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bb.putInt(SR); bb.putInt(d.size); for (v in d) bb.putFloat(v)
                sampleFile.writeBytes(bb.array())
            }
        }.start()
    }
    override fun changed() = save()

    private fun save() {
        val s = Sound.synth; val dr = Sound.drums; val sm = Sound.sampler
        prefs.edit()
            .putInt("page", if (::view.isInitialized) view.page else 0).putInt("root", Sound.root)
            .putInt("wave", s.wave).putFloat("sFilter", s.filter).putBoolean("sEnv", s.filterEnv)
            .putFloat("sA", s.shape.attack).putFloat("sD", s.shape.decay).putFloat("sS", s.shape.sustain).putFloat("sR", s.shape.release)
            .putFloat("dPitch", dr.pitch).putFloat("dFilter", dr.filter).putBoolean("dEnv", dr.filterEnv)
            .putFloat("mFilter", sm.filter).putBoolean("mEnv", sm.filterEnv)
            .putFloat("mA", sm.shape.attack).putFloat("mD", sm.shape.decay).putFloat("mS", sm.shape.sustain).putFloat("mR", sm.shape.release)
            .apply()
    }

    private fun load() {
        val p = prefs; val s = Sound.synth; val dr = Sound.drums; val sm = Sound.sampler
        Sound.root = p.getInt("root", 0).coerceIn(0, 11)
        s.wave = p.getInt("wave", 3).coerceIn(0, 3); s.filter = p.getFloat("sFilter", 0.75f); s.filterEnv = p.getBoolean("sEnv", false)
        s.shape.attack = p.getFloat("sA", s.shape.attack); s.shape.decay = p.getFloat("sD", s.shape.decay); s.shape.sustain = p.getFloat("sS", s.shape.sustain); s.shape.release = p.getFloat("sR", s.shape.release)
        dr.pitch = p.getFloat("dPitch", 0.5f); dr.filter = p.getFloat("dFilter", 1f); dr.filterEnv = p.getBoolean("dEnv", false)
        sm.filter = p.getFloat("mFilter", 0.75f); sm.filterEnv = p.getBoolean("mEnv", false)
        sm.shape.attack = p.getFloat("mA", sm.shape.attack); sm.shape.decay = p.getFloat("mD", sm.shape.decay); sm.shape.sustain = p.getFloat("mS", sm.shape.sustain); sm.shape.release = p.getFloat("mR", sm.shape.release)
        runCatching {
            val f = sampleFile
            if (f.exists()) {
                val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val rate = bb.int; val n = bb.int
                if (rate == SR && n in 64..(SR * 11)) sm.setSample(FloatArray(n) { bb.float })
            }
        }
    }

    companion object {
        private val HEADPHONES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
