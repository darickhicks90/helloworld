package app.foxbox.audio

/** JNI bridge to the AAudio output in src/main/cpp/foxbox_audio.c. */
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

    /** Render-time statistics for the audio test (nanoseconds). */
    @Volatile var lastRenderNs = 0L
    @Volatile var maxRenderNs = 0L
    @Volatile var lastFrames = 0

    /** Called from the AAudio real-time thread for every burst. */
    @JvmStatic
    fun render(buf: FloatArray, frames: Int) {
        val t = System.nanoTime()
        Audio.render(buf, frames)
        val d = System.nanoTime() - t
        lastRenderNs = d; lastFrames = frames
        if (d > maxRenderNs) maxRenderNs = d
        app.foxbox.kit.AudioBoost.onRender(d, frames, SR)
    }
}
