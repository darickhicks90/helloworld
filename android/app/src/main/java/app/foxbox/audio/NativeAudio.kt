package app.foxbox.audio

/** JNI bridge to the AAudio output in src/main/cpp/foxbox_audio.c. */
object NativeAudio {
    val available: Boolean = runCatching { System.loadLibrary("foxbox_audio") }.isSuccess

    @JvmStatic external fun nOpen(sampleRate: Int): Int
    @JvmStatic external fun nClose()
    @JvmStatic external fun nBurst(): Int
    @JvmStatic external fun nBufferSize(): Int
    @JvmStatic external fun nSetBufferSize(frames: Int): Int
    @JvmStatic external fun nXRuns(): Int
    @JvmStatic external fun nDisconnected(): Boolean
    @JvmStatic external fun nExclusive(): Boolean

    /** Called from the AAudio real-time thread for every burst. */
    @JvmStatic
    fun render(buf: FloatArray, frames: Int) = Audio.render(buf, frames)
}
