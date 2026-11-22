package app.foxbox.audio

/**
 * Little Eye Yeti's bridge to the same AAudio output as 5 Eye Yeti (app/src/main/cpp/foxbox_audio.c,
 * built into this app too): an exclusive, low-latency stream where the phone supports it.
 */
object NativeAudio {
    val available: Boolean = runCatching { System.loadLibrary("foxbox_audio") }.isSuccess

    @JvmStatic external fun nOpen(sampleRate: Int, deviceId: Int, bufferFrames: Int): Int
    @JvmStatic external fun nClose()
    @JvmStatic external fun nBurst(): Int
    @JvmStatic external fun nBufferSize(): Int
    @JvmStatic external fun nSetBufferSize(frames: Int): Int
    @JvmStatic external fun nCapacity(): Int
    @JvmStatic external fun nXRuns(): Int
    @JvmStatic external fun nDisconnected(): Boolean
    @JvmStatic external fun nExclusive(): Boolean

    /** Fills `buf` (interleaved stereo) with `frames` frames (a plain interface, so nothing is boxed per call). */
    fun interface Renderer { fun render(buf: FloatArray, frames: Int) }
    @Volatile var renderer: Renderer? = null

    /** Called from the AAudio real-time thread for every burst. */
    @JvmStatic
    fun render(buf: FloatArray, frames: Int) {
        val t = System.nanoTime()
        val r = renderer
        if (r == null) java.util.Arrays.fill(buf, 0, frames * 2, 0f) else r.render(buf, frames)
        app.foxbox.kit.AudioBoost.onRender(System.nanoTime() - t, frames, SR)
    }
}
