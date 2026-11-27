package app.foxbox.audio

/** JNI bridge to the amp-capture runner and cabinet convolver in src/main/cpp/nam.c. */
object NativeNam {
    val available: Boolean get() = NativeAudio.available

    /** A loaded capture's handle (0 if the shape or weights don't fit). */
    @JvmStatic external fun nLoad(cfg: IntArray, weights: FloatArray): Long
    /** Run `n` samples of `buf` through the capture, in place (audio thread). */
    @JvmStatic external fun nProcess(handle: Long, buf: FloatArray, n: Int)
    @JvmStatic external fun nReset(handle: Long)
    @JvmStatic external fun nFree(handle: Long)

    @JvmStatic external fun nIrLoad(ir: FloatArray): Long
    @JvmStatic external fun nIrProcess(handle: Long, buf: FloatArray, n: Int)
    @JvmStatic external fun nIrFree(handle: Long)
}
