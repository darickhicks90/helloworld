package app.foxbox.audio

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

    init {
        params.section("XY Pad")
        xTarget = params.choice("xyX", "X Axis", targets.map { it.name }, idx(defaultX))
        yTarget = params.choice("xyY", "Y Axis", targets.map { it.name }, idx(defaultY))
    }

    val xParam get() = targets[xTarget.index.coerceIn(0, targets.size - 1)]
    val yParam get() = targets[yTarget.index.coerceIn(0, targets.size - 1)]

    /** Call once at the start of each audio block. */
    fun begin() { live = touching || automated; automated = false }
    val isLive get() = live

    /** Effective value of a parameter, including the XY override. */
    fun v(p: Param): Float {
        if (!live) return p.value
        if (p === xParam) return p.valueAt(x)
        if (p === yParam) return p.valueAt(y)
        return p.value
    }

    /** Where the dot sits when nobody is touching the pad. */
    fun restX() = xParam.norm
    fun restY() = yParam.norm
}
