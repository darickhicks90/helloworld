package app.sketchsolve.shapes

import app.sketchsolve.ink.*
import app.sketchsolve.recog.Layout
import kotlin.math.*

/* ===================== primitives ===================== */
sealed class Prim {
    data class Seg(val a: Pt, val b: Pt) : Prim() { val len get() = a.dist(b) }
    data class Arc(val pts: List<Pt>, val box: Box, val len: Double) : Prim()
    data class Poly(val verts: List<Pt>) : Prim()
    data class Ell(val cx: Double, val cy: Double, val a: Double, val b: Double, val ratio: Double, val residual: Double, val box: Box, val pts: List<Pt>) : Prim()
}

object Prims {
    fun turning(P: List<Pt>, k: Int, cyclic: Boolean): DoubleArray {
        val n = P.size
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var ia = i - k; var ic = i + k
            if (cyclic) { ia = (ia + n) % n; ic %= n } else if (ia < 0 || ic >= n) continue
            val v1 = P[i] - P[ia]; val v2 = P[ic] - P[i]
            val l1 = v1.len(); val l2 = v2.len()
            if (l1 == 0.0 || l2 == 0.0) continue
            out[i] = acos(((v1.x * v2.x + v1.y * v2.y) / (l1 * l2)).coerceIn(-1.0, 1.0)) * 180 / PI
        }
        return out
    }

    /** Indices of sharp corners. */
    fun corners(P: List<Pt>, cyclic: Boolean, thresh: Double, kOverride: Int = 0): List<Int> {
        val n = P.size
        val k = if (kOverride > 0) kOverride else max(2, (n / 22.0).roundToInt())
        val T = turning(P, k, cyclic)
        val idx = ArrayList<Int>()
        for (i in 0 until n) {
            if (T[i] < thresh) continue
            var isMax = true
            for (j in -k..k) {
                if (j == 0) continue
                var q = i + j
                if (cyclic) q = (q + n) % n else if (q < 0 || q >= n) continue
                if (T[q] > T[i] || (T[q] == T[i] && q < i)) { isMax = false; break }
            }
            if (isMax) idx.add(i)
        }
        return idx
    }

    fun fitEllipse(P: List<Pt>): Prim.Ell {
        val c = Geom.centroid(P)
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in P) { val dx = p.x - c.x; val dy = p.y - c.y; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val ang = 0.5 * atan2(2 * sxy, sxx - syy)
        val ca = cos(ang); val sa = sin(ang)
        var u0 = Double.POSITIVE_INFINITY; var u1 = Double.NEGATIVE_INFINITY; var v0 = Double.POSITIVE_INFINITY; var v1 = Double.NEGATIVE_INFINITY
        for (p in P) {
            val dx = p.x - c.x; val dy = p.y - c.y
            val u = dx * ca + dy * sa; val v = -dx * sa + dy * ca
            u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
        }
        val a = ((u1 - u0) / 2).takeIf { it > 0 } ?: 1.0
        val b = ((v1 - v0) / 2).takeIf { it > 0 } ?: 1.0
        val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
        val cx = c.x + cu * ca - cv * sa; val cy = c.y + cu * sa + cv * ca
        var s = 0.0; var s2 = 0.0
        for (p in P) {
            val dx = p.x - cx; val dy = p.y - cy
            val u = dx * ca + dy * sa; val v = -dx * sa + dy * ca
            val rho = sqrt((u / a).pow(2) + (v / b).pow(2))
            s += rho; s2 += rho * rho
        }
        val m = s / P.size
        val sd = sqrt(max(0.0, s2 / P.size - m * m))
        return Prim.Ell(cx, cy, max(a, b), min(a, b), min(a, b) / max(a, b), sd / (if (m > 0) m else 1.0), Geom.bbox(P), P)
    }

    fun decompose(stroke: List<Pt>, diag: Double): List<Prim> {
        val sb = Geom.bbox(stroke)
        val sDiag = sb.diag
        if (sDiag < 0.04 * diag || stroke.size < 2) return emptyList()
        val P = Geom.resampleSp(stroke, max(sDiag / 60, 1.5))
        if (P.size < 4) return listOf(Prim.Seg(stroke.first(), stroke.last()))
        val len = Geom.pathLen(P)
        val chord = P.first().dist(P.last())
        val closed = chord < max(0.22 * sDiag, 10.0) && len > 1.6 * sDiag
        if (closed) {
            var best = P.size - 1; var bestD = Double.POSITIVE_INFINITY
            for (i in (P.size * 0.7).toInt() until P.size) { val dd = P[i].dist(P[0]); if (dd < bestD) { bestD = dd; best = i } }
            var Q = P.subList(0, best + 1)
            if (Q.first().dist(Q.last()) < 1e-6) Q = Q.subList(0, Q.size - 1)
            val idx = corners(Q, true, 35.0)
            val ell = fitEllipse(Q)
            if (idx.size >= 3) return listOf(Prim.Poly(idx.map { Q[it] }))
            return listOf(ell)
        }
        if (chord / len > 0.94) return listOf(Prim.Seg(P.first(), P.last()))
        val idx = corners(P, false, 42.0)
        val cuts = listOf(0) + idx + listOf(P.size - 1)
        val out = ArrayList<Prim>()
        for (i in 1 until cuts.size) {
            val piece = P.subList(cuts[i - 1], cuts[i] + 1)
            if (piece.size < 2) continue
            val pl = Geom.pathLen(piece); val pc = piece.first().dist(piece.last())
            if (pl < 0.03 * diag) continue
            out.add(if (pc / pl > 0.95) Prim.Seg(piece.first(), piece.last()) else Prim.Arc(piece, Geom.bbox(piece), pl))
        }
        return out
    }

    class Graph(val nodes: List<Pt>, val edges: List<Pair<Int, Int>>, val adj: List<List<Int>>, val cycle: List<Pt>?)

    fun buildGraph(segs: List<Prim.Seg>, tol: Double): Graph {
        val nodes = ArrayList<Pt>(); val counts = ArrayList<Int>()
        fun nodeFor(p: Pt): Int {
            var bi = -1; var bd = Double.POSITIVE_INFINITY
            nodes.forEachIndexed { i, n -> val dd = n.dist(p); if (dd < bd) { bd = dd; bi = i } }
            if (bi >= 0 && bd < tol) {
                val k = counts[bi]
                nodes[bi] = Pt((nodes[bi].x * k + p.x) / (k + 1), (nodes[bi].y * k + p.y) / (k + 1)); counts[bi] = k + 1
                return bi
            }
            nodes.add(p); counts.add(1); return nodes.size - 1
        }
        val edges = ArrayList<Pair<Int, Int>>(); val keys = HashSet<String>()
        for (s in segs) {
            val i = nodeFor(s.a); val j = nodeFor(s.b)
            if (i == j) continue
            val key = if (i < j) "$i-$j" else "$j-$i"
            if (keys.add(key)) edges.add(i to j)
        }
        val adj = nodes.indices.map { ArrayList<Int>() }
        for ((i, j) in edges) { adj[i].add(j); adj[j].add(i) }
        var cycle: List<Pt>? = null
        if (nodes.size >= 3 && edges.size == nodes.size && adj.all { it.size == 2 }) {
            val order = arrayListOf(0); var prev = -1; var cur = 0
            for (k in nodes.indices) {
                val nx = if (adj[cur][0] == prev) adj[cur][1] else adj[cur][0]
                if (nx == 0) break
                order.add(nx); prev = cur; cur = nx
            }
            if (order.size == nodes.size) cycle = order.map { nodes[it] }
        }
        return Graph(nodes, edges, adj, cycle)
    }

    fun convexHull(points: List<Pt>): List<Pt> {
        val P = points.sortedWith(compareBy({ it.x }, { it.y }))
        if (P.size < 3) return P
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>(); val upper = ArrayList<Pt>()
        for (p in P) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1); lower.add(p) }
        for (p in P.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1); upper.add(p) }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    fun simplifyPoly(verts: List<Pt>, tol: Double, angTol: Double): List<Pt> {
        val V = verts.toMutableList()
        var changed = true
        while (changed && V.size > 3) {
            changed = false
            for (i in V.indices) {
                val j = (i + 1) % V.size
                if (V[i].dist(V[j]) < tol) { V[i] = Geom.mid(V[i], V[j]); V.removeAt(j); changed = true; break }
            }
        }
        changed = true
        while (changed && V.size > 3) {
            changed = false
            for (i in V.indices) {
                val a = V[(i - 1 + V.size) % V.size]; val b = V[i]; val c = V[(i + 1) % V.size]
                if (Geom.interiorAngle(a, b, c) > 180 - angTol) { V.removeAt(i); changed = true; break }
            }
        }
        return V
    }
}

/* ===================== shape models ===================== */
data class ShapeVar(val name: String, val desc: String, val kind: String, val whole: Boolean = false)

data class ShapeDef(val name: String, val dim: Int, val vars: List<ShapeVar>, val eqs: List<String>, val fixed: Map<String, Double> = emptyMap(), val note: String? = null, val n: Int = 0)

sealed class Part(open val v: String) {
    var labelPos: Pt = Pt(0.0, 0.0)
    data class Seg(override val v: String, val a: Pt, val b: Pt, val guide: Boolean = false, val label: Boolean = true, val labelAt: Double = 0.5) : Part(v)
    data class Angle(override val v: String, val o: Pt, val p: Pt, val q: Pt, val right: Boolean = false, val fixed: Boolean = false) : Part(v)
    data class Rim(override val v: String, val c: Pt, val rx: Double, val ry: Double) : Part(v)
    val showsLabel get() = this !is Seg || label
    val isFixed get() = this is Angle && fixed
}

sealed class Outline {
    data class Poly(val pts: List<Pt>) : Outline()
    data class Line(val a: Pt, val b: Pt, val dash: Boolean = false) : Outline()
    data class Ell(val c: Pt, val rx: Double, val ry: Double, val backDash: Boolean = false) : Outline()
}

class Fit(val verts: List<Pt>? = null, val c: Pt? = null, val r: Double = 0.0, val rx: Double = 0.0, val ry: Double = 0.0,
          val apex: Pt? = null, val base: List<Pt>? = null, val baseEll: Prim.Ell? = null, val segs: List<Triple<Pt, Pt, Char>>? = null)

data class Recognised(val type: String, val fit: Fit?, val confidence: Double)

class ShapeModel(val type: String, val def: ShapeDef, val parts: List<Part>, val outline: List<Outline>, val guideOnly: Boolean, val center: Pt, val box: Box) {
    val diag get() = box.diag.takeIf { it > 0 } ?: 1.0
    val whole get() = def.vars.filter { it.whole }.map { it.name }
}

object Shapes {
    private fun V(name: String, desc: String, kind: String, whole: Boolean = false) = ShapeVar(name, desc, kind, whole)
    private val TRI = listOf("A + B + C = 180", "a^2 = b^2 + c^2 - 2*b*c*cos(A)", "b^2 = a^2 + c^2 - 2*a*c*cos(B)",
        "c^2 = a^2 + b^2 - 2*a*b*cos(C)", "Area = 1/2*a*b*sin(C)", "Area = 1/2*b*c*sin(A)", "Area = 1/2*a*c*sin(B)",
        "Perimeter = a + b + c", "Area = 1/2*c*h")
    private val AREA = V("Area", "area", "area", true)
    private val PERIM = V("Perimeter", "perimeter", "length", true)
    private val VOL = V("Volume", "volume", "volume", true)
    private val SURF = V("Surface", "surface area", "area", true)

    val DEFS: Map<String, ShapeDef> = linkedMapOf(
        "triangle" to ShapeDef("Triangle", 2, listOf(V("a", "side a (opposite A)", "length"), V("b", "side b (opposite B)", "length"), V("c", "side c, the base", "length"),
            V("A", "angle A", "angle"), V("B", "angle B", "angle"), V("C", "angle C", "angle"), V("h", "height to base c", "length"), AREA, PERIM), TRI),
        "rightTriangle" to ShapeDef("Right triangle", 2, listOf(V("a", "leg a", "length"), V("b", "leg b", "length"), V("c", "hypotenuse c", "length"),
            V("A", "angle A", "angle"), V("B", "angle B", "angle"), V("h", "height to hypotenuse", "length"), AREA, PERIM), TRI, mapOf("C" to 90.0)),
        "rectangle" to ShapeDef("Rectangle", 2, listOf(V("w", "width", "length"), V("h", "height", "length"), V("d", "diagonal", "length"), AREA, PERIM),
            listOf("Area = w*h", "Perimeter = 2*w + 2*h", "d^2 = w^2 + h^2")),
        "square" to ShapeDef("Square", 2, listOf(V("s", "side", "length"), V("d", "diagonal", "length"), AREA, PERIM),
            listOf("Area = s^2", "Perimeter = 4*s", "d = s*sqrt(2)")),
        "parallelogram" to ShapeDef("Parallelogram", 2, listOf(V("b", "base", "length"), V("s", "slanted side", "length"), V("h", "height", "length"), V("theta", "angle θ", "angle"), AREA, PERIM),
            listOf("Area = b*h", "h = s*sin(theta)", "Perimeter = 2*b + 2*s")),
        "trapezoid" to ShapeDef("Trapezoid", 2, listOf(V("a", "top (parallel)", "length"), V("b", "bottom (parallel)", "length"), V("h", "height", "length"), V("c", "left side", "length"), V("d", "right side", "length"), AREA, PERIM),
            listOf("Area = (a + b)/2*h", "Perimeter = a + b + c + d")),
        "quadrilateral" to ShapeDef("Quadrilateral", 2, listOf(V("a", "side a", "length"), V("b", "side b", "length"), V("c", "side c", "length"), V("d", "side d", "length"), PERIM),
            listOf("Perimeter = a + b + c + d"), note = "A general four-sided shape only links its sides through the perimeter. For area, choose rectangle, parallelogram or trapezoid, or split it into triangles."),
        "regular5" to regular(5), "regular6" to regular(6), "regular7" to regular(7), "regular8" to regular(8),
        "circle" to ShapeDef("Circle", 2, listOf(V("r", "radius", "length"), V("d", "diameter", "length"), V("Circumference", "circumference", "length", true), AREA),
            listOf("d = 2*r", "Circumference = 2*pi*r", "Area = pi*r^2")),
        "ellipse" to ShapeDef("Ellipse", 2, listOf(V("a", "half-width", "length"), V("b", "half-height", "length"), AREA, V("Perimeter", "perimeter (close estimate)", "length", true)),
            listOf("Area = pi*a*b", "Perimeter = pi*(3*(a + b) - sqrt((3*a + b)*(a + 3*b)))")),
        "cube" to ShapeDef("Cube", 3, listOf(V("s", "edge", "length"), V("d", "space diagonal", "length"), VOL, SURF),
            listOf("Volume = s^3", "Surface = 6*s^2", "d = s*sqrt(3)")),
        "box" to ShapeDef("Box (rectangular prism)", 3, listOf(V("l", "length", "length"), V("w", "depth", "length"), V("h", "height", "length"), V("d", "space diagonal", "length"), VOL, SURF),
            listOf("Volume = l*w*h", "Surface = 2*(l*w + l*h + w*h)", "d^2 = l^2 + w^2 + h^2")),
        "cylinder" to ShapeDef("Cylinder", 3, listOf(V("r", "radius", "length"), V("d", "diameter", "length"), V("h", "height", "length"), VOL, V("Surface", "total surface area", "area", true), V("Lateral", "curved side area", "area", true)),
            listOf("d = 2*r", "Volume = pi*r^2*h", "Lateral = 2*pi*r*h", "Surface = 2*pi*r^2 + Lateral")),
        "cone" to ShapeDef("Cone", 3, listOf(V("r", "base radius", "length"), V("d", "base diameter", "length"), V("h", "height", "length"), V("l", "slant height", "length"), VOL, V("Surface", "total surface area", "area", true)),
            listOf("d = 2*r", "l^2 = r^2 + h^2", "Volume = 1/3*pi*r^2*h", "Surface = pi*r^2 + pi*r*l")),
        "sphere" to ShapeDef("Sphere", 3, listOf(V("r", "radius", "length"), V("d", "diameter", "length"), VOL, SURF),
            listOf("d = 2*r", "Volume = 4/3*pi*r^3", "Surface = 4*pi*r^2")),
        "pyramid" to ShapeDef("Square pyramid", 3, listOf(V("s", "base edge", "length"), V("h", "height", "length"), V("l", "slant height", "length"), V("edge", "slanted edge", "length"), VOL, V("Surface", "total surface area", "area", true)),
            listOf("l^2 = h^2 + (s/2)^2", "edge^2 = l^2 + (s/2)^2", "Volume = 1/3*s^2*h", "Surface = s^2 + 2*s*l"))
    )
    val TYPES = DEFS.keys.toList()

    private fun regular(n: Int) = ShapeDef(mapOf(5 to "Regular pentagon", 6 to "Regular hexagon", 7 to "Regular heptagon", 8 to "Regular octagon").getValue(n), 2,
        listOf(V("s", "side", "length"), V("ap", "apothem (centre to middle of a side)", "length"), V("R", "radius (centre to a corner)", "length"), AREA, PERIM),
        listOf("Perimeter = $n*s", "ap = s/(2*tan(180/$n))", "R = s/(2*sin(180/$n))", "Area = Perimeter*ap/2"), n = n)

    /* ---------------- recognition ---------------- */
    private fun segClass(s: Prim.Seg): Char {
        val ang = atan2(abs(s.b.y - s.a.y), abs(s.b.x - s.a.x)) * 180 / PI
        return if (ang < 20) 'H' else if (ang > 68) 'V' else 'O'
    }

    private fun polygonType(verts: List<Pt>, diag: Double): Recognised? {
        val V = Prims.simplifyPoly(Geom.sortAround(verts), 0.08 * diag, 25.0)
        val n = V.size
        if (n < 3) return null
        if (n == 3) {
            val angs = V.indices.map { Geom.interiorAngle(V[(it + 2) % 3], V[it], V[(it + 1) % 3]) }
            return Recognised(if (angs.any { abs(it - 90) < 9 }) "rightTriangle" else "triangle", Fit(verts = V), 0.85)
        }
        if (n == 4) {
            val E = V.indices.map { V[it] to V[(it + 1) % 4] }
            val dir = E.map { Geom.lineDir(it.first, it.second) }
            val len = E.map { it.first.dist(it.second) }
            val par02 = Geom.angDiff180(dir[0], dir[2]) < 13; val par13 = Geom.angDiff180(dir[1], dir[3]) < 13
            val angs = V.indices.map { Geom.interiorAngle(V[(it + 3) % 4], V[it], V[(it + 1) % 4]) }
            if (angs.all { abs(it - 90) < 13 }) {
                val r = min(len[0], len[1]) / max(len[0], len[1])
                return Recognised(if (r > 0.86) "square" else "rectangle", Fit(verts = V), 0.85)
            }
            if (par02 && par13) return Recognised("parallelogram", Fit(verts = V), 0.75)
            if (par02 || par13 || Geom.angDiff180(dir[0], dir[2]) < 20 || Geom.angDiff180(dir[1], dir[3]) < 20) return Recognised("trapezoid", Fit(verts = V), 0.7)
            return Recognised("quadrilateral", Fit(verts = V), 0.6)
        }
        if (n <= 8) return Recognised("regular$n", Fit(verts = V), 0.6)
        return null
    }

    private fun ellipseType(e: Prim.Ell, conf: Double = 0.85) =
        if (e.ratio >= 0.78) Recognised("circle", Fit(c = Pt(e.cx, e.cy), r = (e.box.w + e.box.h) / 4), conf)
        else Recognised("ellipse", Fit(c = Pt(e.box.cx, e.box.cy), rx = e.box.w / 2, ry = e.box.h / 2), conf * 0.9)

    fun recognise(strokes: List<List<Pt>>): Recognised? {
        val all = strokes.flatten()
        if (all.size < 3) return null
        val B = Geom.bbox(all)
        val diag = B.diag
        if (diag < 25) return null
        val prims = strokes.flatMap { Prims.decompose(it, diag) }
        val ells = prims.filterIsInstance<Prim.Ell>().sortedByDescending { it.a * it.b }
        val arcs = prims.filterIsInstance<Prim.Arc>()
        val polys = prims.filterIsInstance<Prim.Poly>()
        val segs = prims.filterIsInstance<Prim.Seg>()
        val polySegs = polys.flatMap { p -> p.verts.indices.map { Prim.Seg(p.verts[it], p.verts[(it + 1) % p.verts.size]) } }
        val allSegs = (segs + polySegs).filter { it.len > 0.06 * diag }

        if (ells.isEmpty()) {
            if (arcs.isEmpty() || arcs.all { it.len < 0.15 * diag }) {
                if (polys.size == 1 && segs.none { it.len > 0.12 * diag }) return polygonType(polys[0].verts, diag)
                val g = Prims.buildGraph(allSegs, 0.13 * diag)
                g.cycle?.let { return polygonType(it, diag) }
                detectPyramid(g, B)?.let { return it }
                detectBox(allSegs)?.let { return it }
                if (allSegs.size >= 3) return polygonType(Prims.convexHull(allSegs.flatMap { listOf(it.a, it.b) }), diag)
                return null
            }
            val pts = arcs.flatMap { it.pts } + segs.flatMap { listOf(it.a, it.b) }
            val e = Prims.fitEllipse(pts)
            return if (e.residual < 0.2) ellipseType(e) else null
        }

        val main = ells[0]
        val mainW = main.box.w; val mainH = main.box.h
        val extras: List<Pair<Box, Prim>> = (ells.drop(1).map { it.box to it as Prim } + arcs.map { it.box to it as Prim }).filter { it.first.w > 0.25 * mainW }
        val longSegs = allSegs.filter { it.len > 0.2 * diag }

        val inner = extras.firstOrNull { (ob, _) ->
            ob.w >= 0.5 * mainW && ob.w <= 1.12 * mainW && ob.h <= 0.65 * ob.w && abs(ob.cx - main.box.cx) < 0.25 * mainW &&
                ob.cy > main.box.y0 + 0.15 * mainH && ob.cy < main.box.y1 - 0.1 * mainH
        }
        if (main.ratio >= 0.7 && inner != null && longSegs.isEmpty() && main.box.h > 0.6 * B.h)
            return Recognised("sphere", Fit(c = Pt(main.cx, main.cy), r = (main.box.w + main.box.h) / 4), 0.75)

        if (longSegs.size >= 2 || main.box.h < 0.6 * B.h) {
            val ec = Pt(main.cx, main.cy)
            val far = longSegs.map { if (it.a.dist(ec) > it.b.dist(ec)) it.a else it.b }
            val near = longSegs.map { if (it.a.dist(ec) > it.b.dist(ec)) it.b else it.a }
            var maxFar = 0.0
            for (i in far.indices) for (j in i + 1 until far.size) maxFar = max(maxFar, far[i].dist(far[j]))
            val nearOk = near.all { abs(it.y - main.cy) < 0.9 * mainH + 0.1 * mainW }
            if (far.size >= 2 && maxFar < 0.2 * diag && nearOk)
                return Recognised("cone", Fit(apex = Geom.centroid(far), baseEll = main), 0.75)
            val verticals = longSegs.filter { segClass(it) == 'V' }
            val lowerCurve = extras.firstOrNull { (ob, _) -> ob.w > 0.6 * mainW && abs(ob.cx - main.box.cx) < 0.3 * mainW && abs(ob.cy - main.box.cy) > 0.5 * mainH }
            if (verticals.size >= 2 || (verticals.isNotEmpty() && lowerCurve != null) || (lowerCurve != null && lowerCurve.first.h > mainH))
                return Recognised("cylinder", null, 0.7)
        }
        val lowerCurve = extras.firstOrNull { (ob, _) -> ob.w > 0.6 * mainW && abs(ob.cx - main.box.cx) < 0.3 * mainW && ob.cy - main.box.cy > 0.5 * mainH }
        if (lowerCurve != null) return Recognised("cylinder", null, 0.65)
        if (extras.isEmpty() && longSegs.isEmpty()) return ellipseType(main)
        return ellipseType(main, 0.4)
    }

    private fun detectPyramid(g: Prims.Graph, B: Box): Recognised? {
        if (g.nodes.size < 4) return null
        val apex = g.nodes.indices.minByOrNull { g.nodes[it].y }!!
        val nb = g.adj[apex]
        if (nb.size < 3) return null
        if (!nb.all { g.nodes[it].y > g.nodes[apex].y + 0.35 * B.h }) return null
        return Recognised("pyramid", Fit(apex = g.nodes[apex], base = Geom.sortAround(g.nodes.filterIndexed { i, _ -> i != apex })), 0.7)
    }

    private fun detectBox(segs: List<Prim.Seg>): Recognised? {
        val H = segs.filter { segClass(it) == 'H' }; val Vv = segs.filter { segClass(it) == 'V' }; val O = segs.filter { segClass(it) == 'O' }
        if (H.size < 2 || Vv.size < 2 || O.size < 2 || segs.size < 6) return null
        fun med(a: List<Prim.Seg>) = a.map { it.len }.sorted()[a.size / 2]
        val l = med(H); val h = med(Vv)
        val cube = abs(l - h) / max(l, h) < 0.2
        return Recognised(if (cube) "cube" else "box", Fit(segs = segs.map { Triple(it.a, it.b, segClass(it)) }), 0.7)
    }

    /* ---------------- building a model ---------------- */
    private fun defaultFit(type: String, B: Box): Fit {
        val x0 = B.x0; val y0 = B.y0; val x1 = B.x1; val y1 = B.y1; val W = B.w; val H = B.h
        val c = Pt(B.cx, B.cy)
        return when (type) {
            "triangle" -> Fit(verts = listOf(Pt(x0, y1), Pt(x1, y1), Pt(x0 + 0.58 * W, y0)))
            "rightTriangle" -> Fit(verts = listOf(Pt(x0, y1), Pt(x1, y1), Pt(x0, y0)))
            "rectangle" -> Fit(verts = listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1)))
            "square" -> { val s = min(W, H); Fit(verts = listOf(Pt(x0, y0), Pt(x0 + s, y0), Pt(x0 + s, y0 + s), Pt(x0, y0 + s))) }
            "parallelogram" -> Fit(verts = listOf(Pt(x0 + 0.25 * W, y0), Pt(x1, y0), Pt(x1 - 0.25 * W, y1), Pt(x0, y1)))
            "trapezoid" -> Fit(verts = listOf(Pt(x0 + 0.22 * W, y0), Pt(x1 - 0.22 * W, y0), Pt(x1, y1), Pt(x0, y1)))
            "quadrilateral" -> Fit(verts = listOf(Pt(x0 + 0.1 * W, y0), Pt(x1, y0 + 0.2 * H), Pt(x1 - 0.15 * W, y1), Pt(x0, y1 - 0.1 * H)))
            "circle", "sphere" -> Fit(c = c, r = min(W, H) / 2)
            "ellipse" -> Fit(c = c, rx = W / 2, ry = max(H / 2, 8.0))
            else -> if (type.startsWith("regular")) {
                val n = DEFS.getValue(type).n; val r = min(W, H) / 2
                Fit(verts = List(n) { val a = -PI / 2 + 2 * PI * it / n; Pt(c.x + r * cos(a), c.y + r * sin(a)) })
            } else Fit()
        }
    }

    fun buildModel(type: String, fit: Fit?, B: Box): ShapeModel {
        val def = DEFS.getValue(type)
        val own = fit
        val F = own ?: defaultFit(type, B)
        val parts = ArrayList<Part>()
        val outline = ArrayList<Outline>()
        var centroidPts: List<Pt> = emptyList()
        fun avgY(e: Pair<Pt, Pt>) = (e.first.y + e.second.y) / 2
        fun avgX(e: Pair<Pt, Pt>) = (e.first.x + e.second.x) / 2

        when {
            type == "triangle" || type == "rightTriangle" -> {
                val Vv = F.verts!!
                val ai: Int; val bi: Int; val ci: Int
                if (type == "rightTriangle") {
                    val angs = Vv.indices.map { abs(Geom.interiorAngle(Vv[(it + 2) % 3], Vv[it], Vv[(it + 1) % 3]) - 90) }
                    ci = angs.indexOf(angs.minOrNull()!!)
                    val others = (0..2).filter { it != ci }
                    if (Vv[others[0]].x <= Vv[others[1]].x) { ai = others[0]; bi = others[1] } else { ai = others[1]; bi = others[0] }
                } else {
                    val best = (0..2).maxByOrNull { (Vv[it].y + Vv[(it + 1) % 3].y) / 2 }!!
                    val e0 = best; val e1 = (best + 1) % 3
                    if (Vv[e0].x <= Vv[e1].x) { ai = e0; bi = e1 } else { ai = e1; bi = e0 }
                    ci = 3 - ai - bi
                }
                val A = Vv[ai]; val Bp = Vv[bi]; val C = Vv[ci]
                centroidPts = listOf(A, Bp, C)
                outline.add(Outline.Poly(listOf(A, Bp, C)))
                parts += listOf(Part.Seg("a", Bp, C), Part.Seg("b", A, C), Part.Seg("c", A, Bp),
                    Part.Angle("A", A, Bp, C), Part.Angle("B", Bp, A, C), Part.Angle("C", C, A, Bp, right = type == "rightTriangle", fixed = type == "rightTriangle"),
                    Part.Seg("h", C, Geom.foot(C, A, Bp), guide = true))
            }
            type in listOf("rectangle", "square", "parallelogram", "trapezoid", "quadrilateral") -> {
                val Vv = Geom.sortAround(F.verts!!)
                centroidPts = Vv
                outline.add(Outline.Poly(Vv))
                val E = Vv.indices.map { Vv[it] to Vv[(it + 1) % 4] }
                fun horiz(e: Pair<Pt, Pt>) = abs(e.second.x - e.first.x) / max(1e-9, e.first.dist(e.second))
                val pairA = listOf(0, 2); val pairB = listOf(1, 3)
                val hPair = if (horiz(E[0]) + horiz(E[2]) >= horiz(E[1]) + horiz(E[3])) pairA else pairB
                val vPair = if (hPair == pairA) pairB else pairA
                val bottom = if (avgY(E[hPair[0]]) > avgY(E[hPair[1]])) E[hPair[0]] else E[hPair[1]]
                val top = if (bottom == E[hPair[0]]) E[hPair[1]] else E[hPair[0]]
                val left = if (avgX(E[vPair[0]]) < avgX(E[vPair[1]])) E[vPair[0]] else E[vPair[1]]
                val right = if (left == E[vPair[0]]) E[vPair[1]] else E[vPair[0]]
                when (type) {
                    "rectangle" -> parts += listOf(Part.Seg("w", bottom.first, bottom.second), Part.Seg("w", top.first, top.second, label = false),
                        Part.Seg("h", right.first, right.second), Part.Seg("h", left.first, left.second, label = false), Part.Seg("d", Vv[0], Vv[2], guide = true))
                    "square" -> parts += listOf(Part.Seg("s", bottom.first, bottom.second), Part.Seg("s", top.first, top.second, label = false),
                        Part.Seg("s", right.first, right.second, label = false), Part.Seg("s", left.first, left.second, label = false), Part.Seg("d", Vv[0], Vv[2], guide = true))
                    "parallelogram" -> {
                        parts += listOf(Part.Seg("b", bottom.first, bottom.second), Part.Seg("b", top.first, top.second, label = false),
                            Part.Seg("s", right.first, right.second), Part.Seg("s", left.first, left.second, label = false))
                        val bl = if (bottom.first.x < bottom.second.x) bottom.first else bottom.second
                        val br = if (bl == bottom.first) bottom.second else bottom.first
                        val tl = if (left.first.dist(bl) < left.second.dist(bl)) left.second else left.first
                        parts += Part.Angle("theta", bl, br, tl)
                        parts += Part.Seg("h", tl, Geom.foot(tl, bottom.first, bottom.second), guide = true)
                    }
                    "trapezoid" -> {
                        val par = if (Geom.angDiff180(Geom.lineDir(E[0].first, E[0].second), Geom.lineDir(E[2].first, E[2].second)) <=
                            Geom.angDiff180(Geom.lineDir(E[1].first, E[1].second), Geom.lineDir(E[3].first, E[3].second))) pairA else pairB
                        val other = if (par == pairA) pairB else pairA
                        val bot = if (avgY(E[par[0]]) > avgY(E[par[1]])) E[par[0]] else E[par[1]]
                        val tp = if (bot == E[par[0]]) E[par[1]] else E[par[0]]
                        val lf = if (avgX(E[other[0]]) < avgX(E[other[1]])) E[other[0]] else E[other[1]]
                        val rt = if (lf == E[other[0]]) E[other[1]] else E[other[0]]
                        parts += listOf(Part.Seg("b", bot.first, bot.second), Part.Seg("a", tp.first, tp.second), Part.Seg("c", lf.first, lf.second), Part.Seg("d", rt.first, rt.second))
                        val tl = if (tp.first.x < tp.second.x) tp.first else tp.second
                        parts += Part.Seg("h", tl, Geom.foot(tl, bot.first, bot.second), guide = true)
                    }
                    else -> parts += listOf(Part.Seg("a", bottom.first, bottom.second), Part.Seg("b", right.first, right.second), Part.Seg("c", top.first, top.second), Part.Seg("d", left.first, left.second))
                }
            }
            type.startsWith("regular") -> {
                val Vv = Geom.sortAround(F.verts!!)
                centroidPts = Vv
                outline.add(Outline.Poly(Vv))
                val c = Geom.centroid(Vv)
                val E = Vv.indices.map { Vv[it] to Vv[(it + 1) % Vv.size] }
                val bi = E.indices.maxByOrNull { E[it].first.y + E[it].second.y }!!
                E.forEachIndexed { i, e -> parts += Part.Seg("s", e.first, e.second, label = i == bi) }
                parts += Part.Seg("ap", c, Geom.mid(E[bi].first, E[bi].second), guide = true)
                val vi = Vv.indices.maxByOrNull { Vv[it].x - Vv[it].y }!!
                parts += Part.Seg("R", c, Vv[vi], guide = true)
            }
            type == "circle" -> {
                val c = F.c!!; val r = F.r
                centroidPts = listOf(c)
                outline.add(Outline.Ell(c, r, r))
                val a = -35 * PI / 180
                parts += Part.Seg("r", c, Pt(c.x + r * cos(a), c.y + r * sin(a)), guide = true)
                parts += Part.Seg("d", Pt(c.x - r, c.y), Pt(c.x + r, c.y), guide = true, labelAt = 0.25)
                parts += Part.Rim("Circumference", c, r, r)
            }
            type == "ellipse" -> {
                val c = F.c!!
                centroidPts = listOf(c)
                outline.add(Outline.Ell(c, F.rx, F.ry))
                parts += Part.Seg("a", c, Pt(c.x + F.rx, c.y), guide = true)
                parts += Part.Seg("b", c, Pt(c.x, c.y - F.ry), guide = true)
                parts += Part.Rim("Perimeter", c, F.rx, F.ry)
            }
            type == "sphere" -> {
                val c = F.c!!; val r = F.r
                centroidPts = listOf(c)
                outline += listOf(Outline.Ell(c, r, r), Outline.Ell(c, r, r * 0.28, backDash = true))
                parts += Part.Seg("r", c, Pt(c.x + r, c.y), guide = true)
                parts += Part.Seg("d", Pt(c.x, c.y - r), Pt(c.x, c.y + r), guide = true, labelAt = 0.25)
            }
            type == "cylinder" -> {
                val rx = B.w / 2; val ry = (B.w * 0.16).coerceIn(5.0, max(5.0, B.h * 0.2)); val cx = B.cx
                val ty = B.y0 + ry; val by = B.y1 - ry
                centroidPts = listOf(Pt(cx, ty), Pt(cx, by))
                outline += listOf(Outline.Ell(Pt(cx, ty), rx, ry), Outline.Ell(Pt(cx, by), rx, ry, backDash = true),
                    Outline.Line(Pt(cx - rx, ty), Pt(cx - rx, by)), Outline.Line(Pt(cx + rx, ty), Pt(cx + rx, by)))
                parts += listOf(Part.Seg("r", Pt(cx, ty), Pt(cx + rx, ty), guide = true), Part.Seg("h", Pt(cx + rx, ty), Pt(cx + rx, by)),
                    Part.Seg("h", Pt(cx - rx, ty), Pt(cx - rx, by), label = false), Part.Seg("d", Pt(cx - rx, by), Pt(cx + rx, by), guide = true, labelAt = 0.3))
            }
            type == "cone" -> {
                val apex: Pt; val bcx: Double; val bcy: Double; val brx: Double; val bry: Double
                if (own?.apex != null && own.baseEll != null) {
                    apex = own.apex; bcx = own.baseEll.cx; bcy = own.baseEll.cy; brx = own.baseEll.box.w / 2; bry = own.baseEll.box.h / 2
                } else {
                    bry = (B.w * 0.16).coerceIn(5.0, max(5.0, B.h * 0.2)); apex = Pt(B.cx, B.y0); bcx = B.cx; bcy = B.y1 - bry; brx = B.w / 2
                }
                val bc = Pt(bcx, bcy)
                centroidPts = listOf(apex, bc)
                outline += listOf(Outline.Ell(bc, brx, bry, backDash = apex.y < bcy), Outline.Line(apex, Pt(bcx - brx, bcy)), Outline.Line(apex, Pt(bcx + brx, bcy)))
                parts += listOf(Part.Seg("r", bc, Pt(bcx + brx, bcy), guide = true), Part.Seg("h", apex, bc, guide = true),
                    Part.Seg("l", apex, Pt(bcx + brx, bcy)), Part.Seg("l", apex, Pt(bcx - brx, bcy), label = false))
            }
            type == "box" || type == "cube" -> {
                fun name(c: Char) = if (type == "cube") "s" else when (c) { 'H' -> "l"; 'V' -> "h"; else -> "w" }
                val segs = own?.segs
                if (segs != null) {
                    val reps = HashMap<Char, Triple<Pt, Pt, Char>>()
                    fun score(s: Triple<Pt, Pt, Char>) = when (s.third) { 'H' -> s.first.y + s.second.y; 'V' -> s.first.x + s.second.x; else -> s.first.x + s.second.x + s.first.y + s.second.y }
                    for (s in segs) { val cur = reps[s.third]; if (cur == null || score(s) > score(cur)) reps[s.third] = s }
                    for (s in segs) {
                        val isRep = reps[s.third] === s
                        val show = if (type == "cube") s.third == 'H' && isRep else isRep
                        parts += Part.Seg(name(s.third), s.first, s.second, label = show)
                    }
                    centroidPts = segs.flatMap { listOf(it.first, it.second) }
                } else {
                    val m = if (type == "cube") min(B.w, B.h) else 0.0
                    val fw = (if (type == "cube") m else B.w) * 0.7; val fh = (if (type == "cube") m else B.h) * 0.7
                    val ox = (if (type == "cube") m else B.w) * 0.3; val oy = (if (type == "cube") m else B.h) * 0.3
                    val x0 = B.x0; val y1 = B.y0 + oy + fh
                    val Fbl = Pt(x0, y1); val Fbr = Pt(x0 + fw, y1); val Ftr = Pt(x0 + fw, y1 - fh); val Ftl = Pt(x0, y1 - fh)
                    val o = Pt(ox, -oy)
                    val Bbl = Fbl + o; val Bbr = Fbr + o; val Btr = Ftr + o; val Btl = Ftl + o
                    centroidPts = listOf(Fbl, Fbr, Ftr, Ftl, Bbl, Bbr, Btr, Btl)
                    outline += listOf(Outline.Poly(listOf(Fbl, Fbr, Ftr, Ftl)), Outline.Line(Ftl, Btl), Outline.Line(Ftr, Btr), Outline.Line(Fbr, Bbr),
                        Outline.Line(Btl, Btr), Outline.Line(Btr, Bbr), Outline.Line(Fbl, Bbl, true), Outline.Line(Bbl, Bbr, true), Outline.Line(Bbl, Btl, true))
                    parts += listOf(Part.Seg(name('H'), Fbl, Fbr), Part.Seg(name('H'), Ftl, Ftr, label = false), Part.Seg(name('H'), Btl, Btr, label = false),
                        Part.Seg(name('V'), Fbr, Ftr, label = type != "cube"), Part.Seg(name('V'), Fbl, Ftl, label = false), Part.Seg(name('V'), Bbr, Btr, label = false),
                        Part.Seg(name('O'), Fbr, Bbr, label = type != "cube"), Part.Seg(name('O'), Ftr, Btr, label = false), Part.Seg(name('O'), Ftl, Btl, label = false),
                        Part.Seg("d", Fbl, Btr, guide = true, label = false))
                }
            }
            type == "pyramid" -> {
                val apex: Pt; val base: List<Pt>
                if (own?.apex != null && own.base != null && own.base.size >= 3) { apex = own.apex; base = own.base }
                else {
                    val W = B.w; val H = B.h
                    base = listOf(Pt(B.x0, B.y1), Pt(B.x0 + 0.72 * W, B.y1), Pt(B.x1, B.y1 - 0.26 * H), Pt(B.x0 + 0.28 * W, B.y1 - 0.26 * H))
                    apex = Pt(B.x0 + 0.5 * W, B.y0)
                    outline += listOf(Outline.Line(base[0], base[1]), Outline.Line(base[1], base[2]), Outline.Line(base[2], base[3], true), Outline.Line(base[3], base[0], true),
                        Outline.Line(apex, base[0]), Outline.Line(apex, base[1]), Outline.Line(apex, base[2]), Outline.Line(apex, base[3], true))
                }
                val bc = Geom.centroid(base)
                centroidPts = base + apex
                val fi = base.indices.maxByOrNull { base[it].y + base[(it + 1) % base.size].y }!!
                base.forEachIndexed { i, p -> parts += Part.Seg("s", p, base[(i + 1) % base.size], label = i == fi) }
                val ri = base.indices.maxByOrNull { base[it].x }!!
                base.forEachIndexed { i, p -> parts += Part.Seg("edge", apex, p, label = i == ri) }
                parts += Part.Seg("h", apex, bc, guide = true)
                parts += Part.Seg("l", apex, Geom.mid(base[fi], base[(fi + 1) % base.size]), guide = true)
            }
        }
        val center = if (centroidPts.isNotEmpty()) Geom.centroid(centroidPts) else Pt(B.cx, B.cy)
        for (p in parts) p.labelPos = labelPos(p, center)
        val pts = outlinePoints(outline, parts)
        val mb = if (pts.isNotEmpty()) Geom.bbox(pts) else B
        return ShapeModel(type, def, parts, outline, own == null, center, mb)
    }

    private fun labelPos(p: Part, center: Pt): Pt = when (p) {
        is Part.Seg -> {
            val m = p.a + (p.b - p.a) * p.labelAt
            val dir = (p.b - p.a).norm()
            var nrm = Pt(-dir.y, dir.x)
            if (p.guide) m + Pt(-dir.y, dir.x) * (if (dir.x >= 0) -14.0 else 14.0)
            else {
                val out = m - center
                if (nrm.x * out.x + nrm.y * out.y < 0) nrm = nrm * -1.0
                m + nrm * 22.0
            }
        }
        is Part.Angle -> {
            val a = (p.p - p.o).norm(); val b = (p.q - p.o).norm()
            val bis = (a + b).norm()
            val r = (0.22 * min(p.p.dist(p.o), p.q.dist(p.o))).coerceIn(22.0, 48.0)
            p.o + bis * r
        }
        is Part.Rim -> { val a = 225 * PI / 180; Pt(p.c.x + p.rx * 1.1 * cos(a) - 8, p.c.y + p.ry * 1.1 * sin(a) - 8) }
    }

    fun outlinePoints(outline: List<Outline>, parts: List<Part>): List<Pt> {
        val pts = ArrayList<Pt>()
        for (o in outline) when (o) {
            is Outline.Poly -> pts += o.pts
            is Outline.Line -> { pts += o.a; pts += o.b }
            is Outline.Ell -> for (i in 0 until 16) { val a = i / 16.0 * 2 * PI; pts += Pt(o.c.x + o.rx * cos(a), o.c.y + o.ry * sin(a)) }
        }
        for (p in parts) {
            if (p is Part.Seg && !p.guide) { pts += p.a; pts += p.b }
            if (p is Part.Angle) pts += p.o
        }
        return pts
    }

    /* ---------------- reading brackets, numbers and circles ---------------- */
    val DIGITS = (('0'..'9').map { it.toString() } + listOf("?", "x", "-", ".")).toSet()

    data class NumberInk(val strokes: List<Stroke>, val box: Box, val text: String, val value: Double?, val unknown: Boolean)

    /** Group small strokes that sit next to each other into written numbers. */
    fun readNumbers(digits: List<Stroke>, cache: MutableMap<String, List<app.sketchsolve.recog.Cand>>?): List<NumberInk> {
        val boxes = digits.map { Geom.bbox(it.pts) }
        val parent = IntArray(digits.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        for (i in digits.indices) for (j in i + 1 until digits.size) {
            val a = boxes[i]; val b = boxes[j]
            val h = maxOf(a.h, b.h, 12.0)
            val gx = max(0.0, max(a.x0, b.x0) - min(a.x1, b.x1))
            val gy = max(0.0, max(a.y0, b.y0) - min(a.y1, b.y1))
            if (gx < 0.6 * h && gy < 0.35 * h) parent[find(i)] = find(j)
        }
        return digits.indices.groupBy { find(it) }.values.map { idx ->
            val g = idx.map { digits[it] }
            val (lines, _) = Layout.analyze(g, Layout.Opts(allowed = DIGITS, cache = cache))
            var text = lines.joinToString("") { it.text }
            text = text.replace(Regex("\\^\\(0\\)$"), "")
            val value = if (Regex("^-?\\d*\\.?\\d+$").matches(text)) text.toDouble() else null
            NumberInk(g, Geom.bbox(g.flatMap { it.pts }), text, value, value == null && Regex("^[?x]+$").matches(text))
        }
    }

    private fun angleArcPart(pts: List<Pt>, model: ShapeModel): Part.Angle? {
        val st = Geom.stats(pts)
        val c = Geom.centroid(pts)
        var best: Pair<Part.Angle, Double>? = null
        for (p in model.parts) {
            if (p !is Part.Angle || p.fixed) continue
            val arm = min(p.p.dist(p.o), p.q.dist(p.o))
            val dv = c.dist(p.o)
            if (dv > 0.5 * arm) continue
            val a = (p.p - p.o).norm(); val b = (p.q - p.o).norm(); val w = (c - p.o).norm()
            fun cross(u: Pt, v: Pt) = u.x * v.y - u.y * v.x
            if (!(cross(a, w) * cross(a, b) >= 0 && cross(b, w) * cross(b, a) >= 0)) continue
            val sd = max(8.0, st.box.diag)
            fun near(e: Pt, arm2: Pt) = Geom.distPtSeg(e, p.o, p.o + (arm2 - p.o) * 1.2) < 0.35 * sd + 6
            val e0 = pts.first(); val e1 = pts.last()
            val spans = (near(e0, p.p) && near(e1, p.q)) || (near(e0, p.q) && near(e1, p.p))
            if (!spans || st.straight < 0.45) continue
            if (best == null || dv < best.second) best = p to dv
        }
        return best?.first
    }

    private fun matchBracket(pts: List<Pt>, model: ShapeModel): Part? {
        val D = model.diag
        val c = Geom.centroid(pts)
        val st = Geom.stats(pts)
        val blen = max(st.chord, 1.0)
        val bdir = Geom.lineDir(pts.first(), pts.last())
        val arcPart = angleArcPart(pts, model)
        var best: Pair<Part, Double>? = null
        for (p in model.parts) {
            val score = when (p) {
                is Part.Seg -> {
                    val sl = p.a.dist(p.b)
                    val ad = Geom.angDiff180(bdir, Geom.lineDir(p.a, p.b))
                    if (ad > 40) continue
                    3 * Geom.distPtSeg(c, p.a, p.b) / D + ad / 45 + 0.6 * abs(ln(blen / sl)) + if (p.guide) 0.15 else 0.0
                }
                is Part.Angle -> {
                    if (p.fixed || arcPart !== p) continue
                    3 * c.dist(p.o) / D + if (st.straight > 0.9) 0.5 else 0.0
                }
                is Part.Rim -> 2 * abs(c.dist(p.c) / ((p.rx + p.ry) / 2) - 1.1) + if (st.straight > 0.9) 1.0 else 0.0
            }
            if (best == null || score < best.second) best = p to score
        }
        return best?.takeIf { it.second < 1.6 }?.first
    }

    class Bracket(val stroke: Stroke, val part: Part?) { var number: NumberInk? = null }
    class Find(val stroke: Stroke?, val part: Part?, val whole: Boolean = false)
    sealed class Issue {
        data class BracketNoNumber(val v: String) : Issue()
        object BracketUnmatched : Issue()
        data class Unreadable(val text: String, val v: String) : Issue()
        data class Stray(val text: String) : Issue()
        object FindNowhere : Issue()
    }

    class Annotation(val brackets: List<Bracket>, val numbers: List<Pair<NumberInk, Part?>>, val finds: List<Find>, val values: Map<String, Double>, val issues: List<Issue>) {
        val target: Find? get() = finds.lastOrNull()
    }

    fun annotate(model: ShapeModel, marks: List<Stroke>, findStrokes: List<Stroke>, cache: MutableMap<String, List<app.sketchsolve.recog.Cand>>? = null): Annotation {
        val D = model.diag
        val brackets = ArrayList<Bracket>(); val digits = ArrayList<Stroke>(); val loops = findStrokes.toMutableList()
        for (s in marks) {
            val (closed, sd, _) = Geom.loopiness(s.pts)
            if (closed && sd > max(0.1 * D, 40.0)) { loops += s; continue }
            if (sd > max(0.16 * D, 40.0)) { brackets += Bracket(s, matchBracket(s.pts, model)); continue }
            val ang = if (sd > 14) angleArcPart(s.pts, model) else null
            if (ang != null) { brackets += Bracket(s, ang); continue }
            digits += s
        }
        val numbers = readNumbers(digits, cache)
        val numPart = HashMap<Int, Part?>()
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        numbers.forEachIndexed { ci, n ->
            val cc = Pt(n.box.cx, n.box.cy)
            brackets.forEachIndexed { bi, br -> pairs += Triple(ci, bi, br.stroke.pts.minOf { it.dist(cc) }) }
        }
        pairs.sortBy { it.third }
        val usedC = HashSet<Int>(); val usedB = HashSet<Int>()
        for ((ci, bi, dd) in pairs) {
            if (ci in usedC || bi in usedB || dd > 0.35 * D + 50) continue
            usedC += ci; usedB += bi
            brackets[bi].number = numbers[ci]
            numPart[ci] = brackets[bi].part
        }
        numbers.forEachIndexed { ci, n ->
            if (ci in usedC) return@forEachIndexed
            val cc = Pt(n.box.cx, n.box.cy)
            var best: Pair<Part, Double>? = null
            for (p in model.parts) {
                if (p.isFixed) continue
                val dd = if (p is Part.Seg) min(Geom.distPtSeg(cc, p.a, p.b), cc.dist(p.labelPos)) else cc.dist(p.labelPos)
                if (best == null || dd < best.second) best = p to dd
            }
            numPart[ci] = best?.takeIf { it.second < 0.3 * D + 30 }?.first
        }
        val finds = ArrayList<Find>()
        val shapePts = outlinePoints(model.outline, model.parts)
        for (s in loops) {
            val inside = shapePts.count { Geom.pointInPoly(it, s.pts) }
            if (shapePts.isNotEmpty() && inside.toDouble() / shapePts.size >= 0.6) { finds += Find(s, null, true); continue }
            val qi = numbers.indices.firstOrNull { numPart[it] != null && Geom.pointInPoly(Pt(numbers[it].box.cx, numbers[it].box.cy), s.pts) }
            if (qi != null) { finds += Find(s, numPart[qi]); continue }
            val lc = Geom.centroid(s.pts)
            var best: Pair<Part, Double>? = null
            for (p in model.parts) {
                if (p.isFixed) continue
                val anchor = if (p is Part.Seg) Geom.mid(p.a, p.b) else p.labelPos
                for (a in listOf(anchor, p.labelPos)) {
                    val dd = a.dist(lc) / D + if (Geom.pointInPoly(a, s.pts)) 0.0 else 1.0
                    if (best == null || dd < best.second) best = p to dd
                }
            }
            finds += Find(s, best?.takeIf { it.second < 1.3 }?.first)
        }
        val values = LinkedHashMap<String, Double>(); val issues = ArrayList<Issue>()
        numbers.forEachIndexed { ci, n ->
            val p = numPart[ci]
            when {
                p == null -> issues += Issue.Stray(n.text)
                n.unknown -> finds += Find(null, p)
                n.value == null -> issues += Issue.Unreadable(n.text, p.v)
                else -> values[p.v] = n.value
            }
        }
        for (br in brackets) {
            if (br.part == null) issues += Issue.BracketUnmatched
            else if (br.number == null) issues += Issue.BracketNoNumber(br.part.v)
        }
        if (finds.any { !it.whole && it.part == null }) issues += Issue.FindNowhere
        return Annotation(brackets, numbers.mapIndexed { i, n -> n to numPart[i] }, finds, values, issues)
    }
}
