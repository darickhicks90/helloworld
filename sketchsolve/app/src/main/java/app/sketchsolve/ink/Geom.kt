package app.sketchsolve.ink

import kotlin.math.*

data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Double) = Pt(x * k, y * k)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
    fun len() = hypot(x, y)
    fun norm(): Pt { val l = len(); return if (l == 0.0) Pt(0.0, 0.0) else Pt(x / l, y / l) }
}

/** A pen stroke. [kind] says which tool drew it ("ink", "select", "shape", "mark", "find"). */
data class Stroke(val id: Int, var kind: String, val pts: List<Pt>)

data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val w get() = x1 - x0
    val h get() = y1 - y0
    val cx get() = (x0 + x1) / 2
    val cy get() = (y0 + y1) / 2
    val diag get() = hypot(w, h)
    fun contains(p: Pt) = p.x in x0..x1 && p.y in y0..y1
}

object Geom {
    fun bbox(pts: Iterable<Pt>): Box {
        var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (p in pts) { x0 = min(x0, p.x); y0 = min(y0, p.y); x1 = max(x1, p.x); y1 = max(y1, p.y) }
        if (x0 == Double.POSITIVE_INFINITY) return Box(0.0, 0.0, 0.0, 0.0)
        return Box(x0, y0, x1, y1)
    }

    fun union(boxes: List<Box>) = Box(boxes.minOf { it.x0 }, boxes.minOf { it.y0 }, boxes.maxOf { it.x1 }, boxes.maxOf { it.y1 })

    fun pathLen(pts: List<Pt>): Double { var l = 0.0; for (i in 1 until pts.size) l += pts[i].dist(pts[i - 1]); return l }

    fun centroid(pts: List<Pt>): Pt { var x = 0.0; var y = 0.0; for (p in pts) { x += p.x; y += p.y }; return Pt(x / pts.size, y / pts.size) }

    data class Stats(val len: Double, val chord: Double, val straight: Double, val ang: Double, val rising: Boolean, val box: Box)

    fun stats(s: List<Pt>): Stats {
        val len = pathLen(s)
        val a = s.first(); val b = s.last()
        val chord = a.dist(b)
        val ang = atan2(abs(b.y - a.y), abs(b.x - a.x)) * 180 / PI
        val rising = (b.x - a.x) * (b.y - a.y) < 0
        return Stats(len, chord, if (len > 0) chord / len else 1.0, ang, rising, bbox(s))
    }

    fun distPtSeg(p: Pt, a: Pt, b: Pt): Double {
        val v = b - a; val w = p - a
        val l2 = v.x * v.x + v.y * v.y
        val t = if (l2 == 0.0) 0.0 else ((w.x * v.x + w.y * v.y) / l2).coerceIn(0.0, 1.0)
        return p.dist(a + v * t)
    }

    fun foot(p: Pt, a: Pt, b: Pt): Pt {
        val v = b - a; val w = p - a
        val l2 = (v.x * v.x + v.y * v.y).takeIf { it > 0 } ?: 1.0
        return a + v * ((w.x * v.x + w.y * v.y) / l2)
    }

    fun pointInPoly(p: Pt, poly: List<Pt>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)

    fun interiorAngle(prev: Pt, v: Pt, next: Pt): Double {
        val a = (prev - v).norm(); val b = (next - v).norm()
        return acos((a.x * b.x + a.y * b.y).coerceIn(-1.0, 1.0)) * 180 / PI
    }

    fun lineDir(a: Pt, b: Pt) = atan2(b.y - a.y, b.x - a.x)

    fun angDiff180(a: Double, b: Double): Double {
        var d = abs(a - b) % PI
        if (d > PI / 2) d = PI - d
        return d * 180 / PI
    }

    fun sortAround(pts: List<Pt>): List<Pt> {
        val c = centroid(pts)
        return pts.sortedBy { atan2(it.y - c.y, it.x - c.x) }
    }

    fun resampleSp(pts: List<Pt>, sp: Double): List<Pt> {
        if (pts.size < 2) return pts
        val out = arrayListOf(pts[0])
        var d = 0.0
        var prev = pts[0]
        for (i in 1 until pts.size) {
            val cur = pts[i]
            var seg = prev.dist(cur)
            while (d + seg >= sp && seg > 0) {
                val t = (sp - d) / seg
                val q = Pt(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
                out.add(q)
                prev = q; seg = prev.dist(cur); d = 0.0
            }
            d += seg; prev = cur
        }
        if (out.last().dist(pts.last()) > sp * 0.3) out.add(pts.last())
        return out
    }

    /** Moving-average smoothing; removes pen jitter but keeps real corners. */
    fun smooth(pts: List<Pt>, r: Int = 2, cyclic: Boolean = false): List<Pt> {
        val n = pts.size
        if (n < 2 * r + 2) return pts
        return List(n) { i ->
            var x = 0.0; var y = 0.0; var k = 0
            for (j in -r..r) {
                var q = i + j
                if (cyclic) q = (q + n) % n else if (q < 0 || q >= n) continue
                x += pts[q].x; y += pts[q].y; k++
            }
            if (!cyclic && (i == 0 || i == n - 1)) pts[i] else Pt(x / k, y / k)
        }
    }

    fun loopiness(pts: List<Pt>): Triple<Boolean, Double, Box> {
        val b = bbox(pts)
        val sd = b.diag
        val len = pathLen(pts)
        val chord = pts.first().dist(pts.last())
        return Triple(chord < max(0.3 * sd, 12.0) && len > 1.8 * sd, sd, b)
    }
}
