package app.foxbox.jam

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import app.foxbox.audio.Audio
import app.foxbox.audio.SR
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Jamming over the internet, like a private radio broadcast. Two phones meet at the relay with a
 * one-time code. The leader's mix goes out (AAC, in half-second packets, a few seconds behind);
 * the follower listens and can only send "hey wait" or "my turn", which the leader's phone says
 * aloud. Either can ask to swap; the swap happens once both have switched, and the other phone is
 * told when one switches first.
 */
object Jam {
    enum class State { IDLE, CREATING, WAITING, CONNECTING, CONNECTED }
    enum class Role { LEAD, FOLLOW }

    var state = State.IDLE; private set
    var code: String? = null; private set
    var role = Role.LEAD; private set
    /** What each side has switched to (a swap happens when they match up). */
    var myWant = Role.LEAD; private set
    var partnerWant = Role.FOLLOW; private set
    var status = ""; private set
    var onChange: (() -> Unit)? = null
    /** Things to tell the user right away (toasts). */
    var onNotice: ((String) -> Unit)? = null

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val http by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }
    private var ws: WebSocket? = null
    private var tts: TextToSpeech? = null
    private var sender: Broadcaster? = null
    private var listener: Listener? = null

    fun init(c: Context) { if (!::app.isInitialized) app = c.applicationContext }

    var relay: String
        get() = app.getSharedPreferences("jam", Context.MODE_PRIVATE).getString("relay", "") ?: ""
        set(v) { app.getSharedPreferences("jam", Context.MODE_PRIVATE).edit().putString("relay", v.trim().trimEnd('/')).apply() }

    private fun changed() { main.post { onChange?.invoke() } }
    private fun notice(s: String) { main.post { onNotice?.invoke(s) } }
    private fun set(st: State, msg: String) { state = st; status = msg; changed() }

    // ---- starting and joining
    fun start() {
        if (relay.isBlank()) return notice("Set the relay address first")
        set(State.CREATING, "Getting a code…")
        Thread {
            try {
                val req = Request.Builder().url("$relay/code").post("{}".toRequestBody("application/json".toMediaType())).build()
                val body = http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("relay said ${r.code}"); r.body!!.string() }
                val o = JSONObject(body)
                code = o.getString("code")
                role = Role.LEAD; myWant = Role.LEAD; partnerWant = Role.FOLLOW
                connect(o.getString("code"), o.getString("key"))
                set(State.WAITING, "Share the code. It works once, for 10 minutes.")
            } catch (e: Exception) { set(State.IDLE, "Couldn't reach the relay (${e.message})") }
        }.start()
    }

    fun join(c: String) {
        if (relay.isBlank()) return notice("Set the relay address first")
        val clean = c.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length != 8) return notice("A jam code has 8 letters and numbers")
        code = clean
        role = Role.FOLLOW; myWant = Role.FOLLOW; partnerWant = Role.LEAD
        set(State.CONNECTING, "Joining…")
        connect(clean, null)
    }

    private fun connect(code: String, key: String?) {
        val url = "$relay/ws?code=$code" + (key?.let { "&key=$it" } ?: "")
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { control(JSONObject(text)) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (role == Role.FOLLOW) listener().offer(bytes.toByteArray()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = when (response?.code) { 410 -> "That code has expired or been used"; 425 -> "The other phone isn't ready yet: try again in a moment"; 409 -> "Someone's already using that code"; else -> "Lost the connection" }
                end(why)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { end("The jam ended") }
        })
    }

    fun leave() { ws?.close(1000, "bye"); end("You left the jam") }

    private fun end(msg: String) {
        if (state == State.IDLE) return
        ws = null; code = null
        stopRoles()
        set(State.IDLE, msg)
        notice(msg)
    }

    // ---- what the two phones say to each other
    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun control(o: JSONObject) {
        when (o.optString("t")) {
            "joined" -> main.post {
                set(State.CONNECTED, "")
                notice(if (role == Role.LEAD) "Your partner joined: they're hearing your mix" else "Joined: you're listening to your partner")
                startRole()
            }
            "say" -> main.post {
                val what = if (o.optString("w") == "turn") "My turn!" else "Hey, wait!"
                say(what); notice("Your partner: $what")
            }
            "want" -> main.post {
                partnerWant = if (o.optString("r") == "lead") Role.LEAD else Role.FOLLOW
                val partnerRole = if (role == Role.LEAD) Role.FOLLOW else Role.LEAD
                if (partnerWant != partnerRole && myWant == role)
                    notice(if (partnerWant == Role.LEAD) "Your partner wants to lead: switch to Follow to hand it over" else "Your partner wants you to lead: switch to Lead to take over")
                maybeSwap()
            }
            "bye" -> end("Your partner left the jam")
        }
    }

    /** The follower's two buttons. The leader's phone says them aloud. */
    fun sayToLeader(turn: Boolean) { if (role == Role.FOLLOW) send(JSONObject().put("t", "say").put("w", if (turn) "turn" else "wait")) }

    /** Switch to leading or following (the swap happens when your partner has switched the other way). */
    fun want(r: Role) {
        if (state != State.CONNECTED) return
        myWant = r
        send(JSONObject().put("t", "want").put("r", if (r == Role.LEAD) "lead" else "follow"))
        maybeSwap()
        changed()
    }

    private fun maybeSwap() {
        val partnerRole = if (role == Role.LEAD) Role.FOLLOW else Role.LEAD
        if (myWant != role && partnerWant != partnerRole && myWant != partnerWant) {
            stopRoles()
            role = myWant
            startRole()
            notice(if (role == Role.LEAD) "You're leading now" else "You're following now")
        }
        changed()
    }

    private fun startRole() {
        if (role == Role.LEAD) { sender = Broadcaster { packet -> val w = ws; if (w != null && w.queueSize() < 2_000_000) w.send(packet.toByteString()) }.also { it.start() } }
        else listener()
        if (tts == null) tts = TextToSpeech(app) {}
    }

    private fun stopRoles() {
        sender?.finish(); sender = null
        listener?.finish(); listener = null
        if (state == State.IDLE || ws == null) { tts?.shutdown(); tts = null }
    }

    private fun listener(): Listener = listener ?: Listener().also { listener = it; it.start() }

    private fun say(s: String) { tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "jam" + System.nanoTime()) }

    // =====================================================================================
    // The leader: the master mix → AAC → half-second packets
    // =====================================================================================

    /**
     * Packet: [2][sample rate: int][channels: byte][config length: byte][AAC config][frame count: short]
     * then for each frame [length: short][bytes]. Every packet carries the config, so the follower
     * can start (or start again) anywhere.
     */
    private class Broadcaster(private val out: (ByteArray) -> Unit) : Thread("jam-send") {
        @Volatile private var running = true
        private val ring = JamRing(SR * 4)
        fun finish() { running = false; Audio.jamRing = null }

        override fun run() {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            Audio.jamRing = ring
            val pcm = FloatArray(2048 * 2)
            val info = MediaCodec.BufferInfo()
            var config = ByteArray(0)
            val frames = ArrayList<ByteArray>()
            var lastSend = System.nanoTime()
            var pts = 0L
            try {
                while (running) {
                    var fed = false
                    val inIdx = codec.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val n = ring.read(pcm, 1024)
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        for (i in 0 until n * 2) buf.putShort((pcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        codec.queueInputBuffer(inIdx, 0, n * 4, pts, 0)
                        pts += n * 1_000_000L / SR
                        fed = n > 0
                    }
                    while (true) {
                        val o = codec.dequeueOutputBuffer(info, 0)
                        if (o < 0) break
                        val b = codec.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size); b.position(info.offset); b.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = bytes
                        else if (bytes.isNotEmpty()) frames += bytes
                        codec.releaseOutputBuffer(o, false)
                    }
                    if (System.nanoTime() - lastSend > 500_000_000L && frames.isNotEmpty() && config.isNotEmpty()) {
                        out(pack(config, frames)); frames.clear(); lastSend = System.nanoTime()
                    }
                    if (!fed) Thread.sleep(8)
                }
            } catch (_: Exception) {
            } finally {
                Audio.jamRing = null
                runCatching { codec.stop() }; runCatching { codec.release() }
            }
        }

        private fun pack(config: ByteArray, frames: List<ByteArray>): ByteArray {
            val size = 1 + 4 + 1 + 1 + config.size + 2 + frames.sumOf { 2 + it.size }
            val b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            b.put(2); b.putInt(SR); b.put(2); b.put(config.size.toByte()); b.put(config); b.putShort(frames.size.toShort())
            frames.forEach { b.putShort(it.size.toShort()); b.put(it) }
            return b.array()
        }
    }

    // =====================================================================================
    // The follower: packets → AAC decoder → its own speaker, with a couple of seconds' cushion
    // =====================================================================================

    private class Listener : Thread("jam-listen") {
        @Volatile private var running = true
        private val queue = LinkedBlockingDeque<ByteArray>()
        fun offer(p: ByteArray) { queue.offer(p); while (queue.size > 20) queue.pollFirst() }   // over ten seconds behind: catch up
        fun finish() { running = false; interrupt() }

        private var codec: MediaCodec? = null
        private var track: AudioTrack? = null
        private var cfgKey = ""

        override fun run() {
            try {
                while (running) {
                    // a cushion of about a second and a half before playing (and after any gap)
                    if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) { while (running && queue.size < 3) Thread.sleep(50) }
                    val p = queue.poll(2, TimeUnit.SECONDS)
                    if (p == null) { track?.pause(); continue }
                    play(p)
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
            } finally {
                runCatching { codec?.stop() }; runCatching { codec?.release() }
                runCatching { track?.stop() }; runCatching { track?.release() }
            }
        }

        private fun setup(rate: Int, ch: Int, config: ByteArray) {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { track?.release() }
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(config))
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(fmt, null, null, 0); start() }
            val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 4, rate))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        }

        private fun play(p: ByteArray) {
            val b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
            if (b.get().toInt() != 2) return
            val rate = b.int; val ch = b.get().toInt(); val cl = b.get().toInt() and 0xFF
            val config = ByteArray(cl).also { b.get(it) }
            val key = "$rate/$ch/" + config.joinToString(",")
            if (key != cfgKey) { setup(rate, ch, config); cfgKey = key }
            val c = codec ?: return; val t = track ?: return
            val n = b.short.toInt() and 0xFFFF
            val info = MediaCodec.BufferInfo()
            repeat(n) {
                val len = b.short.toInt() and 0xFFFF
                val frame = ByteArray(len).also { b.get(it) }
                val i = c.dequeueInputBuffer(20_000)
                if (i >= 0) { c.getInputBuffer(i)!!.apply { clear(); put(frame) }; c.queueInputBuffer(i, 0, len, 0, 0) }
                while (true) {
                    val o = c.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val out = c.getOutputBuffer(o)!!
                    val pcm = ByteArray(info.size); out.position(info.offset); out.get(pcm)
                    c.releaseOutputBuffer(o, false)
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                    t.write(pcm, 0, pcm.size)
                }
            }
        }
    }
}

/** The master mix, handed from the audio thread to the broadcaster without locks (one writer, one reader). */
class JamRing(frames: Int) {
    private val buf = FloatArray(frames * 2)
    @Volatile private var w = 0L; @Volatile private var r = 0L
    private val cap = frames.toLong()

    /** Audio thread. Drops the oldest sound if the reader falls behind. */
    fun write(l: FloatArray, rr: FloatArray, n: Int) {
        var wi = w
        for (i in 0 until n) { val k = ((wi % cap) * 2).toInt(); buf[k] = l[i]; buf[k + 1] = rr[i]; wi++ }
        w = wi
        if (wi - r > cap) r = wi - cap
    }

    /** Up to [max] interleaved stereo frames into [out]; how many were read. */
    fun read(out: FloatArray, max: Int): Int {
        val avail = (w - r).toInt().coerceAtMost(max)
        var ri = r
        for (i in 0 until avail) { val k = ((ri % cap) * 2).toInt(); out[i * 2] = buf[k]; out[i * 2 + 1] = buf[k + 1]; ri++ }
        r = ri
        return avail
    }
}
