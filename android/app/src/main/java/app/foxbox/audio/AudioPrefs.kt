package app.foxbox.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Audio settings, kept on the device (not per project). Defaults: 48 kHz,
 * 24-bit, 128-frame buffer, system default devices.
 */
object AudioPrefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("audio", Context.MODE_PRIVATE) }

    val SAMPLE_RATES = listOf(44100, 48000, 96000)
    val BIT_DEPTHS = listOf(16, 24, 32)
    /** 0 = automatic (grows only if the device drops audio). */
    val BUFFERS = listOf(64, 128, 256, 512, 1024, 0)

    var sampleRate: Int get() = sp.getInt("rate", 48000); set(v) = sp.edit().putInt("rate", v).apply()
    /** Bit depth for recordings and exported audio (playback is always 32-bit float). */
    var bitDepth: Int get() = sp.getInt("bits", 24); set(v) = sp.edit().putInt("bits", v).apply()
    var bufferFrames: Int get() = sp.getInt("buffer", 128); set(v) = sp.edit().putInt("buffer", v).apply()
    var outputDevice: Int get() = sp.getInt("out", 0); set(v) = sp.edit().putInt("out", v).apply()
    var inputDevice: Int get() = sp.getInt("in", 0); set(v) = sp.edit().putInt("in", v).apply()
    /** Extra recording offset to line takes up with the beat (ms, can be negative). */
    var offsetMs: Int get() = sp.getInt("offset", 0); set(v) = sp.edit().putInt("offset", v).apply()
    /** Which input each biplane records: 0 = input 1, 1 = input 2, 2 = both mixed. */
    fun planeInput(ch: Int) = sp.getInt("plane$ch", if (ch == 1) 1 else 0)
    fun setPlaneInput(ch: Int, v: Int) = sp.edit().putInt("plane$ch", v).apply()
}
