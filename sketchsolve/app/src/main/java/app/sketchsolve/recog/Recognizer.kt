package app.sketchsolve.recog

import app.sketchsolve.ink.Geom
import app.sketchsolve.ink.Pt
import kotlin.math.*

data class Cand(val label: String, val score: Double, val rule: Boolean = false, val dot: Boolean = false)

/**
 * Single-symbol recogniser: the $P point-cloud recogniser (Vatavu, Anthony &
 * Wobbrock 2012), which ignores stroke order and direction, plus simple
 * rules for straight-line symbols. Built-in templates are drawn in code;
 * samples the user teaches are added on top.
 */
object Recognizer {
    private const val N = 32

    private class CP(val x: Double, val y: Double, val id: Int)
    private class Template(val label: String, val cloud: Array<CP>, val user: Boolean)

    private val templates = ArrayList<Template>()
    val userSamples = ArrayList<Pair<String, List<List<Pt>>>>()

    /* ---------------- $P core ---------------- */
    private fun toCloud(strokes: List<List<Pt>>): Array<CP> {
        val pts = ArrayList<CP>()
        strokes.forEachIndexed { id, s ->
            if (s.size == 1) { pts.add(CP(s[0].x, s[0].y, id)); pts.add(CP(s[0].x + 0.01, s[0].y + 0.01, id)) }
            else for (p in s) pts.add(CP(p.x, p.y, id))
        }
        return translate(scale(resample(pts)))
    }

    private fun d(a: CP, b: CP) = hypot(a.x - b.x, a.y - b.y)

    private fun resample(points: List<CP>): List<CP> {
        val pts = ArrayList(points)
        var total = 0.0
        for (i in 1 until pts.size) if (pts[i].id == pts[i - 1].id) total += d(pts[i - 1], pts[i])
        if (total == 0.0) return List(N) { pts[0] }
        val interval = total / (N - 1)
        var acc = 0.0
        val out = arrayListOf(pts[0])
        var i = 1
        while (i < pts.size) {
            if (pts[i].id == pts[i - 1].id) {
                val dd = d(pts[i - 1], pts[i])
                if (acc + dd >= interval && dd > 0) {
                    val t = (interval - acc) / dd
                    val q = CP(pts[i - 1].x + t * (pts[i].x - pts[i - 1].x), pts[i - 1].y + t * (pts[i].y - pts[i - 1].y), pts[i].id)
                    out.add(q)
                    pts.add(i, q)
                    acc = 0.0
                } else acc += dd
            }
            i++
        }
        while (out.size < N) out.add(pts.last())
        return out.subList(0, N)
    }

    private fun scale(pts: List<CP>): List<CP> {
        val x0 = pts.minOf { it.x }; val x1 = pts.maxOf { it.x }; val y0 = pts.minOf { it.y }; val y1 = pts.maxOf { it.y }
        val s = max(x1 - x0, y1 - y0).takeIf { it > 0 } ?: 1.0
        return pts.map { CP((it.x - x0) / s, (it.y - y0) / s, it.id) }
    }

    private fun translate(pts: List<CP>): Array<CP> {
        val cx = pts.sumOf { it.x } / pts.size; val cy = pts.sumOf { it.y } / pts.size
        return Array(pts.size) { CP(pts[it].x - cx, pts[it].y - cy, pts[it].id) }
    }

    private fun cloudDistance(a: Array<CP>, b: Array<CP>, start: Int, minSoFar: Double): Double {
        val n = a.size
        val matched = BooleanArray(n)
        var sum = 0.0
        var i = start
        do {
            var min = Double.POSITIVE_INFINITY
            var idx = -1
            for (j in 0 until n) {
                if (matched[j]) continue
                val dx = a[i].x - b[j].x; val dy = a[i].y - b[j].y
                val dd = dx * dx + dy * dy
                if (dd < min) { min = dd; idx = j }
            }
            matched[idx] = true
            val weight = 1 - ((i - start + n) % n).toDouble() / n
            sum += weight * sqrt(min)
            if (sum >= minSoFar) return sum
            i = (i + 1) % n
        } while (i != start)
        return sum
    }

    private fun greedyMatch(a: Array<CP>, b: Array<CP>, minSoFar: Double): Double {
        val step = floor(sqrt(N.toDouble())).toInt()
        var min = minSoFar
        var i = 0
        while (i < N) {
            min = min(min, cloudDistance(a, b, i, min))
            min = min(min, cloudDistance(b, a, i, min))
            i += step
        }
        return min
    }

    /* ---------------- built-in templates ----------------
     * x right, y down; a full-height symbol spans y = 0..1.
     * Angles in degrees: 0 = right, 90 = down, 270 = up.            */
    private fun L(x1: Double, y1: Double, x2: Double, y2: Double, n: Int = 8) = List(n + 1) { Pt(x1 + (x2 - x1) * it / n, y1 + (y2 - y1) * it / n) }
    private fun A(cx: Double, cy: Double, rx: Double, ry: Double, a0: Double, a1: Double, n0: Int = 0): List<Pt> {
        val n = if (n0 > 0) n0 else max(6, (abs(a1 - a0) / 12).roundToInt())
        return List(n + 1) { val a = (a0 + (a1 - a0) * it / n) * PI / 180; Pt(cx + rx * cos(a), cy + ry * sin(a)) }
    }
    private fun S(vararg parts: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>()
        for (p in parts) out.addAll(if (out.isEmpty()) p else p.drop(1))
        return out
    }

    val BASE: Map<String, List<List<List<Pt>>>> by lazy {
        linkedMapOf(
            "0" to listOf(listOf(A(.35, .5, .35, .5, -90.0, 270.0)), listOf(A(.3, .5, .3, .5, -60.0, 300.0))),
            "1" to listOf(listOf(L(.1, 0.0, .1, 1.0)), listOf(S(L(0.0, .25, .3, 0.0, 4), L(.3, 0.0, .3, 1.0))),
                listOf(S(L(0.0, .25, .3, 0.0, 4), L(.3, 0.0, .3, 1.0)), L(0.0, 1.0, .6, 1.0))),
            "2" to listOf(listOf(S(A(.3, .3, .3, .3, 200.0, 390.0), L(.56, .45, 0.0, 1.0), L(0.0, 1.0, .65, 1.0))),
                listOf(S(A(.32, .28, .3, .28, 190.0, 380.0), L(.6, .38, .05, 1.0), L(.05, 1.0, .7, .98)))),
            "3" to listOf(listOf(S(A(.3, .25, .3, .25, 210.0, 450.0), A(.3, .75, .33, .25, 270.0, 510.0))),
                listOf(S(L(0.0, 0.0, .6, 0.0), L(.6, 0.0, .25, .42), A(.3, .72, .32, .3, 270.0, 510.0)))),
            "4" to listOf(listOf(S(L(.5, 0.0, 0.0, .65), L(0.0, .65, .75, .65)), L(.55, .2, .55, 1.0)),
                listOf(S(L(.05, 0.0, 0.0, .6), L(0.0, .6, .75, .6)), L(.55, 0.0, .55, 1.0)),
                listOf(S(L(.55, 1.0, .55, 0.0), L(.55, 0.0, 0.0, .65), L(0.0, .65, .75, .65)))),
            "5" to listOf(listOf(S(L(.09, .05, .09, .45), A(.3, .7, .3, .3, 225.0, 500.0)), L(.09, .05, .6, .05)),
                listOf(S(L(.62, 0.0, .1, 0.0), L(.1, 0.0, .08, .45), A(.3, .7, .3, .3, 225.0, 500.0)))),
            "6" to listOf(listOf(S(A(.55, .6, .5, .6, 268.0, 180.0), A(.3, .72, .25, .27, 180.0, -170.0))),
                listOf(S(L(.5, 0.0, .1, .55), A(.3, .72, .24, .27, 200.0, -150.0)))),
            "7" to listOf(listOf(S(L(0.0, 0.0, .6, 0.0), L(.6, 0.0, .2, 1.0))), listOf(S(L(0.0, 0.0, .6, 0.0), L(.6, 0.0, .2, 1.0)), L(.15, .5, .55, .5)),
                listOf(S(L(0.0, .15, 0.0, 0.0, 3), L(0.0, 0.0, .6, 0.0), L(.6, 0.0, .25, 1.0)))),
            "8" to listOf(listOf(S(A(.3, .25, .22, .25, 90.0, 450.0), A(.3, .73, .28, .27, 270.0, -90.0))),
                listOf(A(.3, .25, .22, .25, 0.0, 360.0), A(.3, .74, .26, .26, 0.0, 360.0))),
            "9" to listOf(listOf(S(A(.3, .28, .28, .28, 360.0, 0.0), L(.58, .28, .55, 1.0))),
                listOf(S(A(.3, .28, .28, .28, 360.0, 0.0), L(.58, .28, .58, .8), A(.35, .8, .23, .2, 0.0, 150.0)))),
            "+" to listOf(listOf(L(.5, 0.0, .5, 1.0), L(0.0, .5, 1.0, .5))),
            "-" to listOf(listOf(L(0.0, .5, 1.0, .5))),
            "=" to listOf(listOf(L(0.0, .3, 1.0, .3), L(0.0, .7, 1.0, .7))),
            "(" to listOf(listOf(A(.6, .5, .5, .55, 245.0, 115.0)), listOf(A(.9, .5, .8, .55, 240.0, 120.0))),
            ")" to listOf(listOf(A(-.1, .5, .5, .55, -65.0, 65.0)), listOf(A(-.4, .5, .8, .55, -60.0, 60.0))),
            "/" to listOf(listOf(L(0.0, 1.0, .6, 0.0))),
            "x" to listOf(listOf(L(0.0, 0.0, .8, 1.0), L(.8, 0.0, 0.0, 1.0)), listOf(A(-.05, .5, .42, .5, -70.0, 70.0), A(.85, .5, .42, .5, 250.0, 110.0)),
                listOf(S(L(0.0, .1, .1, 0.0, 2), L(.1, 0.0, .8, 1.0)), L(.8, 0.0, 0.0, 1.0))),
            "y" to listOf(listOf(L(0.0, 0.0, .4, .5), L(.75, 0.0, .15, 1.0)),
                listOf(S(L(0.0, 0.0, 0.0, .3), A(.27, .3, .27, .22, 180.0, 0.0), L(.54, .3, .54, 0.0, 4), L(.54, 0.0, .54, .85), A(.32, .85, .22, .15, 0.0, 160.0)))),
            "a" to listOf(listOf(S(A(.3, .5, .28, .42, 300.0, 30.0), L(.54, .71, .58, .05, 5), L(.58, .05, .62, 1.0))),
                listOf(A(.3, .5, .3, .42, 0.0, 360.0), L(.62, .08, .62, 1.0))),
            "b" to listOf(listOf(S(L(0.0, 0.0, 0.0, .72), A(.28, .72, .28, .28, 180.0, 540.0))), listOf(L(0.0, 0.0, 0.0, 1.0), A(.28, .72, .28, .28, 180.0, 540.0))),
            "c" to listOf(listOf(A(.4, .5, .4, .45, 320.0, 40.0))),
            "n" to listOf(listOf(S(L(0.0, 0.0, 0.0, 1.0), L(0.0, 1.0, 0.0, .35, 4), A(.3, .35, .3, .3, 180.0, 360.0), L(.6, .35, .6, 1.0)))),
            "t" to listOf(listOf(L(.3, 0.0, .3, 1.0), L(0.0, .3, .65, .3)), listOf(S(L(.3, 0.0, .3, .82), A(.5, .82, .2, .18, 180.0, 90.0)), L(0.0, .3, .65, .3))),
            "√" to listOf(listOf(S(L(0.0, .6, .15, .5, 3), L(.15, .5, .35, 1.0), L(.35, 1.0, .6, 0.0), L(.6, 0.0, 1.4, 0.0))),
                listOf(S(L(.1, .55, .35, 1.0), L(.35, 1.0, .6, 0.0), L(.6, 0.0, 1.6, 0.0)))),
            "π" to listOf(listOf(L(0.0, .1, 1.0, .08), L(.3, .1, .25, 1.0), L(.7, .1, .75, 1.0))),
            "?" to listOf(listOf(S(A(.35, .25, .33, .25, 190.0, 450.0), L(.35, .5, .35, .72)), listOf(Pt(.35, .95), Pt(.355, .96), Pt(.35, .97))))
        )
    }

    val LABELS: List<String> get() = BASE.keys.toList()

    private fun augment(strokes: List<List<Pt>>): List<List<List<Pt>>> {
        fun tf(f: (Pt) -> Pt) = strokes.map { s -> s.map(f) }
        return listOf(strokes, tf { Pt(it.x + 0.18 * (1 - it.y), it.y) }, tf { Pt(it.x - 0.12 * (1 - it.y), it.y) },
            tf { Pt(it.x * 1.25, it.y) }, tf { Pt(it.x * 0.8, it.y) })
    }

    @Volatile private var built = false
    private fun ensure() {
        if (built) return
        synchronized(this) {
            if (built) return
            for ((label, variants) in BASE) for (v in variants) for (a in augment(v)) templates.add(Template(label, toCloud(a), false))
            built = true
        }
    }

    fun addUserSample(label: String, strokes: List<List<Pt>>) {
        ensure()
        val b = Geom.bbox(strokes.flatten())
        val s = max(b.w, b.h).takeIf { it > 0 } ?: 1.0
        val norm = strokes.map { st -> st.map { Pt(((it.x - b.x0) / s * 100).roundToInt().toDouble(), ((it.y - b.y0) / s * 100).roundToInt().toDouble()) } }
        synchronized(this) {
            userSamples.add(label to norm)
            if (userSamples.size > 400) userSamples.removeAt(0)
            templates.add(Template(label, toCloud(norm), true))
        }
    }

    fun forgetUser() = synchronized(this) { userSamples.clear(); templates.removeAll { it.user } }

    fun sampleCounts(): Map<String, Int> = userSamples.groupingBy { it.first }.eachCount()

    fun matchCloud(strokes: List<List<Pt>>, allowed: Set<String>?): MutableList<Cand> {
        ensure()
        val cloud = toCloud(strokes)
        val best = HashMap<String, Double>()
        val list = synchronized(this) { templates.toList() }
        for (t in list) {
            if (allowed != null && t.label !in allowed) continue
            val cur = best[t.label] ?: Double.POSITIVE_INFINITY
            val k = if (t.user) 0.85 else 1.0
            val dd = greedyMatch(cloud, t.cloud, cur / k) * k
            if (dd < cur) best[t.label] = dd
        }
        return best.entries.map { Cand(it.key, max(0.0, (2.5 - it.value) / 2.5)) }.sortedByDescending { it.score }.toMutableList()
    }

    private fun segIntersect(p1: Pt, p2: Pt, p3: Pt, p4: Pt): Pair<Double, Double>? {
        val dn = (p2.x - p1.x) * (p4.y - p3.y) - (p2.y - p1.y) * (p4.x - p3.x)
        if (dn == 0.0) return null
        val t = ((p3.x - p1.x) * (p4.y - p3.y) - (p3.y - p1.y) * (p4.x - p3.x)) / dn
        val u = ((p3.x - p1.x) * (p2.y - p1.y) - (p3.y - p1.y) * (p2.x - p1.x)) / dn
        return t to u
    }

    fun rules(strokes: List<List<Pt>>): String? {
        if (strokes.size == 1) {
            val st = Geom.stats(strokes[0])
            if (st.straight > 0.92) {
                if (st.ang < 22) return "-"
                if (st.ang > 68) return "1"
                if (st.rising && st.ang > 30) return "/"
            }
            return null
        }
        if (strokes.size == 2) {
            val s1 = Geom.stats(strokes[0]); val s2 = Geom.stats(strokes[1])
            if (s1.straight > 0.88 && s2.straight > 0.88) {
                val h1 = s1.ang < 25; val h2 = s2.ang < 25; val v1 = s1.ang > 60; val v2 = s2.ang > 60
                if (h1 && h2) return "="
                val x = segIntersect(strokes[0].first(), strokes[0].last(), strokes[1].first(), strokes[1].last())
                val crosses = x != null && x.first > -0.1 && x.first < 1.1 && x.second > -0.1 && x.second < 1.1
                if (crosses && ((h1 && v2) || (v1 && h2))) {
                    val hv = if (h1) x!!.second else x!!.first
                    val vert = if (h1) strokes[1] else strokes[0]
                    val fromTop = if (vert.last().y > vert.first().y) hv else 1 - hv
                    return if (fromTop < 0.38) "t" else "+"
                }
                val d1 = s1.ang in 25.0..65.0; val d2 = s2.ang in 25.0..65.0
                if (crosses && d1 && d2 && s1.rising != s2.rising && x!!.first in 0.2..0.8 && x.second in 0.2..0.8) return "x"
            }
        }
        return null
    }

    fun recognize(strokes: List<List<Pt>>, allowed: Set<String>? = null): List<Cand> {
        val res = matchCloud(strokes, allowed)
        val r = rules(strokes)
        if (r != null && (allowed == null || r in allowed)) {
            res.removeAll { it.label == r }
            res.add(0, Cand(r, 0.99, rule = true))
        }
        return res
    }
}
