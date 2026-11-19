package app.foxbox.radio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Internet radio. Stations play through their own player alongside the app's sound, so a station
 * keeps going in the studio, Clibbins, the notebook and the game. While recording, the decoded
 * sound is copied off (before the volume) for the samplers.
 */
@OptIn(markerClass = [UnstableApi::class])
object Radio {
    class Station(val name: String, val url: String) {
        fun toJson() = JSONObject().put("name", name).put("url", url)
    }

    private lateinit var app: Context
    val stations = ArrayList<Station>()
    var current = -1; private set
    var playing = false; private set
    var buffering = false; private set
    @Volatile var recording = false; private set
    var nowTitle: String? = null; private set
    var error: String? = null; private set
    var volume = 0.8f; set(v) { field = v.coerceIn(0f, 1f); player?.volume = field; save() }
    /** For the radio key (lamp) and the open panel. */
    var onChange: (() -> Unit)? = null
    var onPanel: (() -> Unit)? = null
    private var player: ExoPlayer? = null

    /** Long-running SomaFM channels to start with; add your own from the radio. */
    private val DEFAULTS = listOf(
        Station("SomaFM Groove Salad", "https://ice2.somafm.com/groovesalad-128-mp3"),
        Station("SomaFM Drone Zone", "https://ice2.somafm.com/dronezone-128-mp3"),
        Station("SomaFM Secret Agent", "https://ice2.somafm.com/secretagent-128-mp3"),
        Station("SomaFM Lush", "https://ice2.somafm.com/lush-128-mp3"),
    )

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        val prefs = app.getSharedPreferences("radio", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("stations", null) ?: "") }.getOrNull()
        if (arr == null) stations += DEFAULTS
        else for (i in 0 until arr.length()) arr.getJSONObject(i).let { stations += Station(it.getString("name"), it.getString("url")) }
        volume = prefs.getFloat("volume", 0.8f)
        current = prefs.getInt("current", if (stations.isEmpty()) -1 else 0).coerceIn(-1, stations.size - 1)
    }

    private fun save() {
        if (!::app.isInitialized) return
        app.getSharedPreferences("radio", Context.MODE_PRIVATE).edit()
            .putString("stations", JSONArray(stations.map { it.toJson() }).toString())
            .putFloat("volume", volume).putInt("current", current).apply()
    }

    private fun changed() { onChange?.invoke(); onPanel?.invoke() }

    // ---- the player, built once, with the tap on its sound
    private fun player(): ExoPlayer {
        player?.let { return it }
        val tee = TeeAudioProcessor(Capture)
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(tee)).build()
        }
        val p = ExoPlayer.Builder(app, renderers)
            // the radio never pauses for (or ducks) the app's own instruments
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
        p.volume = volume
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
                changed()
            }
            override fun onMediaMetadataChanged(m: MediaMetadata) {
                nowTitle = m.title?.toString()?.takeIf { it.isNotBlank() }
                changed()
            }
            override fun onPlayerError(e: PlaybackException) {
                error = "Can't tune in: " + (e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase())
                playing = false; buffering = false
                if (recording) recording = false
                changed()
            }
        })
        player = p
        return p
    }

    fun play(i: Int = current) {
        if (i !in stations.indices) return
        current = i; error = null; nowTitle = null
        val p = player()
        p.setMediaItem(MediaItem.fromUri(stations[i].url))
        p.prepare(); p.play()
        playing = true; buffering = true
        save(); changed()
    }

    fun stop() {
        if (recording) recording = false
        player?.stop()
        playing = false; buffering = false; nowTitle = null
        changed()
    }

    fun tune(d: Int) { if (stations.isNotEmpty()) play(Math.floorMod((if (current < 0) 0 else current) + d, stations.size)) }

    fun add(s: Station) { stations += s; save(); play(stations.size - 1) }
    fun remove(i: Int) {
        if (i !in stations.indices) return
        if (i == current) stop()
        stations.removeAt(i)
        current = when { stations.isEmpty() -> -1; current > i -> current - 1; else -> current.coerceAtMost(stations.size - 1) }
        save(); changed()
    }

    // ---- recording off the air
    const val MAX_SECONDS = 60

    fun startRecording(): Boolean {
        if (!playing) return false
        Capture.begin(); recording = true; changed(); return true
    }
    /** Mono at [rate] as captured. */
    fun stopRecording(): Pair<FloatArray, Int> {
        recording = false; changed()
        return Capture.take()
    }
    val recordedSeconds get() = Capture.seconds()

    /** Copies what's playing while [recording], folded to mono. Called on the player's audio thread. */
    private object Capture : TeeAudioProcessor.AudioBufferSink {
        @Volatile private var rate = 44100; @Volatile private var channels = 2; @Volatile private var encoding = C.ENCODING_PCM_16BIT
        private var buf = FloatArray(0); private var n = 0
        private val lock = Any()
        fun begin() = synchronized(lock) { buf = FloatArray(rate * 8); n = 0 }
        fun take(): Pair<FloatArray, Int> = synchronized(lock) { val out = buf.copyOf(n); buf = FloatArray(0); n = 0; out to rate }
        fun seconds() = synchronized(lock) { n / rate.toFloat() }

        override fun flush(sampleRateHz: Int, channelCount: Int, enc: Int) { rate = sampleRateHz; channels = channelCount.coerceAtLeast(1); encoding = enc }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!recording) return
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val ch = channels
            synchronized(lock) {
                val max = rate * MAX_SECONDS
                when (encoding) {
                    C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4 * ch && n < max) { var s = 0f; repeat(ch) { s += b.float }; add(s / ch) }
                    C.ENCODING_PCM_16BIT -> while (b.remaining() >= 2 * ch && n < max) { var s = 0f; repeat(ch) { s += b.short / 32768f }; add(s / ch) }
                    else -> return
                }
                if (n >= max) recording = false
            }
        }
        private fun add(v: Float) { if (n == buf.size) buf = buf.copyOf(maxOf(1024, buf.size * 2)); buf[n++] = v }
    }

    // ---- finding stations (the community Radio Browser directory)
    class Found(val name: String, val url: String, val info: String)

    /** Runs on a background thread. */
    fun search(q: String): List<Found> {
        val u = URL("https://all.api.radio-browser.info/json/stations/search?limit=40&hidebroken=true&order=clickcount&reverse=true&name=" + URLEncoder.encode(q, "UTF-8"))
        val con = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("User-Agent", "5IY-Radio/1.0")
        }
        val text = con.inputStream.use { String(it.readBytes()) }
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (url.isBlank()) null
            else Found(o.optString("name").trim().ifBlank { "Station" }, url,
                listOf(o.optString("countrycode"), o.optString("codec"), o.optInt("bitrate").takeIf { it > 0 }?.let { "${it}k" }).filter { !it.isNullOrBlank() }.joinToString(" · "))
        }
    }
}
