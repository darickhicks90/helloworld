package app.foxbox.audio

import kotlin.concurrent.Volatile

/**
 * An XY pad's state. X and Y each drive a parameter chosen from [targets].
 * While a finger is on the pad (or a clip plays back XY automation) the
 * chosen parameters follow the pad; otherwise they keep their own values.
 */
class XyState(private val params: Params, defaultX: String, defaultY: String) {
    @Volatile var x = 0.5f
    @Volatile var y = 0.5f
    @Volatile var touching = false
    /** Set by a playing clip each block when it drives the pad. */
    @Volatile var automated = false
    @Volatile private var live = false

    val targets: List<Param> = params.all.values.filter { !it.isChoice }
    private fun idx(id: String) = targets.indexOfFirst { it.id == id }.coerceAtLeast(0)

    val xTarget: Param
    val yTarget: Param
    /** Stay where left (on) or spring back to the home position (off). */
    val stay: Param
    val homeX: Param
    val homeY: Param

    init {
        params.section("XY Pad")
        xTarget = params.choice("xyX", "X Axis", targets.map { it.name }, idx(defaultX))
        yTarget = params.choice("xyY", "Y Axis", targets.map { it.name }, idx(defaultY))
        stay = params.toggle("xyStay", "Stay Where Left", true)
        homeX = params.num("xyHomeX", "Home X", 0f, 1f, 0.5f)
        homeY = params.num("xyHomeY", "Home Y", 0f, 1f, 0.5f)
    }

    /** Finger lifted: keep the values (stay) or glide back home. */
    fun release() {
        if (stay.on) { xParam.norm = x; yParam.norm = y }
        else { x = homeX.value; y = homeY.value; xParam.norm = x; yParam.norm = y }
        touching = false
    }

    /** In stay mode, make the current position the home position. */
    fun setHome() { homeX.set(x); homeY.set(y) }

    val xParam get() = targets[xTarget.index.coerceIn(0, targets.size - 1)]
    val yParam get() = targets[yTarget.index.coerceIn(0, targets.size - 1)]

    // What the sound actually follows: the pad position, smoothed. A finger (or a touch screen
    // reporting every 8 ms or so) moves in jumps; stepping a filter or a pitch by those jumps once
    // per block is heard as zipper noise and clicks. A short glide (about 15 ms) is inaudible as
    // lag but removes the steps.
    private var sx = 0.5f; private var sy = 0.5f

    /** Call once at the start of each audio block. */
    fun begin() {
        live = touching || automated; automated = false
        if (!live) { sx = xParam.norm; sy = yParam.norm }   // rest where the parameters are, so a touch glides from there
        else { sx += (x - sx) * GLIDE; sy += (y - sy) * GLIDE }
    }
    val isLive get() = live

    /** Effective value of a parameter, including the XY override. */
    fun v(p: Param): Float {
        if (!live) return p.value
        if (p === xParam) return p.valueAt(sx)
        if (p === yParam) return p.valueAt(sy)
        return p.value
    }

    /** Where the dot sits when nobody is touching the pad. */
    fun restX() = xParam.norm
    fun restY() = yParam.norm

    private companion object {
        /** Per block (about 4 ms): reaches the finger in about 15 ms. */
        const val GLIDE = 0.25f
    }
}
