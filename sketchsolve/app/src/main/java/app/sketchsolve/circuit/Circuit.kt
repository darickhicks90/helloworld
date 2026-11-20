package app.sketchsolve.circuit

import app.sketchsolve.ink.*
import app.sketchsolve.math.*
import app.sketchsolve.shapes.Prim
import app.sketchsolve.shapes.Prims
import app.sketchsolve.shapes.Shapes
import kotlin.math.*

enum class CompType(val prefix: String, val title: String, val unit: String) {
    RESISTOR("R", "Resistor", "Ω"),
    LAMP("L", "Lamp", "Ω"),
    BATTERY("B", "Battery", "V"),
    SOURCE("S", "Voltage source", "V"),
    CAPACITOR("C", "Capacitor", "µF")
}

/** A component with two terminals. For batteries/sources, [a] is − and [b] is +. */
class Component(val type: CompType, var a: Pt?, var b: Pt?, val body: Box, val strokeIds: Set<Int>) {
    var name = ""
    var nodeA = -1
    var nodeB = -1
    val center get() = Pt(body.cx, body.cy)
    val isSource get() = type == CompType.BATTERY || type == CompType.SOURCE
    val isResistive get() = type == CompType.RESISTOR || type == CompType.LAMP
}

class Wire(val pts: List<Pt>) {
    fun dist(p: Pt): Double {
        if (pts.size == 1) return pts[0].dist(p)
        var d = Double.POSITIVE_INFINITY
        for (i in 1 until pts.size) d = min(d, Geom.distPtSeg(p, pts[i - 1], pts[i]))
        return d
    }
}

class Schematic(
    val components: List<Component>,
    val wires: List<Wire>,
    val nodeCount: Int,
    val ground: Int,
    val terminalNodes: List<Int>,
    val junctions: List<Pt>,
    val openEnds: List<String>,
    val box: Box
) {
    val sources get() = components.filter { it.isSource }
    val topology: String get() {
        val passive = components.filter { !it.isSource }
        if (passive.isEmpty()) return "No resistors, lamps or capacitors yet"
        val pairs = passive.map { setOf(it.nodeA, it.nodeB) }.toSet()
        if (passive.size > 1 && pairs.size == 1) return "Parallel circuit"
        val deg = IntArray(nodeCount)
        for (c in components) { if (c.nodeA >= 0) deg[c.nodeA]++; if (c.nodeB >= 0) deg[c.nodeB]++ }
        if (passive.size > 1 && components.all { c -> listOf(c.nodeA, c.nodeB).all { it >= 0 && deg[it] == 2 } }) return "Series circuit"
        return if (passive.size == 1) "Simple circuit" else "Series–parallel circuit"
    }
}

object Circuit {

    /* ===================== recognition ===================== */
    private class Raw(val comps: MutableList<Component>, val wires: MutableList<List<Pt>>)

    /** Find a zigzag run (≥4 short strokes alternating left/right) inside one stroke. */
    private fun findZigzag(P: List<Pt>): Pair<Int, Int>? {
        if (P.size < 12) return null
        val idx = Prims.corners(P, false, 70.0, 3)
        if (idx.size < 3) return null
        val cuts = listOf(0) + idx + listOf(P.size - 1)
        val dirs = (1 until cuts.size).map { P[cuts[it]] - P[cuts[it - 1]] }
        // sign of each corner's turn
        val turn = (1 until dirs.size).map { val u = dirs[it - 1]; val v = dirs[it]; sign(u.x * v.y - u.y * v.x) }
        var bestStart = -1; var bestLen = 0
        var i = 0
        while (i < turn.size) {
            var j = i
            while (j + 1 < turn.size && turn[j + 1] != 0.0 && turn[j + 1] == -turn[j]) j++
            val corners = j - i + 1
            if (corners >= 3 && corners > bestLen) { bestLen = corners; bestStart = i }
            i = j + 1
        }
        if (bestStart < 0) return null
        // corners bestStart..bestStart+bestLen-1 are cuts[bestStart+1 ..]; the run spans the segments around them
        val segFirst = bestStart       // segment before the first zig corner
        val segLast = bestStart + bestLen // segment after the last zig corner
        val lens = (segFirst..segLast).map { dirs[it].len() }
        val inner = lens.subList(1, lens.size - 1)
        val typical = inner.sorted()[inner.size / 2]
        // trim the leading/trailing segments to roughly one zig length (they may be long wire legs)
        var startIdx = cuts[segFirst]
        var endIdx = cuts[segLast + 1]
        if (lens.first() > 1.6 * typical) {
            val target = P[cuts[segFirst + 1]]
            startIdx = (cuts[segFirst]..cuts[segFirst + 1]).firstOrNull { P[it].dist(target) <= 0.6 * typical } ?: cuts[segFirst + 1]
        }
        if (lens.last() > 1.6 * typical) {
            val from = P[cuts[segLast]]
            endIdx = (cuts[segLast]..cuts[segLast + 1]).lastOrNull { P[it].dist(from) <= 0.6 * typical } ?: cuts[segLast]
        }
        return startIdx to endIdx
    }

    fun recognise(strokes: List<Stroke>): Schematic? {
        val all = strokes.flatMap { it.pts }
        if (all.size < 4) return null
        val B = Geom.bbox(all)
        val diag = B.diag
        if (diag < 30) return null
        val raw = Raw(ArrayList(), ArrayList())
        val circles = ArrayList<Triple<Pt, Double, Stroke>>()

        for (s in strokes) {
            val sb = Geom.bbox(s.pts)
            if (sb.diag < 0.025 * diag) continue
            val P = Geom.resampleSp(s.pts, max(1.5, sb.diag / 160))
            val len = Geom.pathLen(P)
            val chord = P.first().dist(P.last())
            val closed = chord < max(0.22 * sb.diag, 10.0) && len > 1.6 * sb.diag
            if (closed) {
                val Ps = Geom.smooth(P, 3, cyclic = true)
                val e = Prims.fitEllipse(Ps)
                val corners = Prims.corners(Ps, true, 42.0)
                if (corners.size < 3 && e.residual < 0.2 && e.ratio > 0.55 && sb.diag < 0.45 * diag) {
                    circles += Triple(Pt(e.cx, e.cy), (e.box.w + e.box.h) / 4, s); continue
                }
                if (corners.size == 4) {
                    val V = corners.map { Ps[it] }
                    val bb = Geom.bbox(V)
                    val longSide = max(bb.w, bb.h); val shortSide = min(bb.w, bb.h)
                    if (longSide / max(shortSide, 1.0) >= 1.6 && longSide < 0.35 * diag) {
                        val horizontal = bb.w >= bb.h
                        val a = if (horizontal) Pt(bb.x0, bb.cy) else Pt(bb.cx, bb.y0)
                        val b = if (horizontal) Pt(bb.x1, bb.cy) else Pt(bb.cx, bb.y1)
                        raw.comps += Component(CompType.RESISTOR, a, b, bb, setOf(s.id)); continue
                    }
                }
                // a closed outline (e.g. the whole loop of wire drawn in one go)
                val q = if (corners.size >= 3) corners.map { Ps[it] } + Ps[corners[0]] else P
                raw.wires += q
                continue
            }
            val zz = findZigzag(P)
            if (zz != null) {
                val (i0, i1) = zz
                val body = Geom.bbox(P.subList(i0, i1 + 1))
                raw.comps += Component(CompType.RESISTOR, P[i0], P[i1], body, setOf(s.id))
                if (i0 > 0) addWirePieces(P.subList(0, i0 + 1), diag, raw)
                if (i1 < P.size - 1) addWirePieces(P.subList(i1, P.size), diag, raw)
                continue
            }
            addWirePieces(P, diag, raw)
        }

        // lamps and sources: circles; an X inside makes it a lamp
        for ((c, r, s) in circles) {
            val inside = raw.wires.filter { w -> w.all { it.dist(c) < 1.08 * r } }
            raw.wires.removeAll(inside)
            val lamp = inside.size >= 2
            val body = Box(c.x - r, c.y - r, c.x + r, c.y + r)
            raw.comps += Component(if (lamp) CompType.LAMP else CompType.SOURCE, null, null, body, setOf(s.id))
        }

        findPlates(raw, diag)

        // cut wires where they pass through a component body
        val keepOut = raw.comps.map { c -> Box(c.body.x0 + 1.5, c.body.y0 + 1.5, c.body.x1 - 1.5, c.body.y1 - 1.5) }
        val wires = ArrayList<List<Pt>>()
        for (w in raw.wires) {
            var cur = ArrayList<Pt>()
            for (p in densify(w, 2.0)) {
                if (keepOut.any { it.w > 3 && it.h > 3 && it.contains(p) }) { if (cur.size >= 2) wires += cur; cur = ArrayList() }
                else cur.add(p)
            }
            if (cur.size >= 2) wires += cur
        }
        val wireList = wires.filter { Geom.pathLen(it) > 0.015 * diag }.map { Wire(simplify(it)) }

        // terminals for round components: the wire ends that reach the circle
        val tol = max(0.05 * diag, 16.0)
        for (c in raw.comps.filter { it.a == null }) {
            val r = c.body.w / 2
            val ends = wireList.flatMap { listOf(it.pts.first(), it.pts.last()) }.filter { it.dist(c.center) < r + tol }
            var best: Pair<Pt, Pt>? = null; var bd = -1.0
            for (i in ends.indices) for (j in i + 1 until ends.size) { val d = ends[i].dist(ends[j]); if (d > bd) { bd = d; best = ends[i] to ends[j] } }
            if (best != null && bd > r) {
                // + at the top (or left)
                val (p, q) = best
                val plus = if (abs(p.y - q.y) > abs(p.x - q.x)) (if (p.y < q.y) p else q) else (if (p.x < q.x) p else q)
                c.b = plus; c.a = if (plus == p) q else p
            } else if (ends.isNotEmpty()) { c.a = ends[0] }
        }

        if (raw.comps.isEmpty() && wireList.isEmpty()) return null
        return connect(raw.comps, wireList, tol, B)
    }

    private fun addWirePieces(P: List<Pt>, diag: Double, raw: Raw) {
        if (Geom.pathLen(P) < 0.02 * diag) return
        for (pr in Prims.decompose(P, diag)) when (pr) {
            is Prim.Seg -> raw.wires += listOf(pr.a, pr.b)
            is Prim.Arc -> raw.wires += pr.pts
            is Prim.Poly -> raw.wires += pr.verts + pr.verts[0]
            is Prim.Ell -> raw.wires += pr.pts
        }
    }

    private fun densify(w: List<Pt>, step: Double): List<Pt> {
        val out = ArrayList<Pt>()
        for (i in 1 until w.size) {
            val a = w[i - 1]; val b = w[i]
            val n = max(1, (a.dist(b) / step).toInt())
            for (k in 0 until n) out += a + (b - a) * (k.toDouble() / n)
        }
        out += w.last()
        return out
    }

    private fun simplify(w: List<Pt>): List<Pt> {
        if (w.size <= 2) return w
        val out = arrayListOf(w[0])
        for (i in 1 until w.size - 1) if (w[i].dist(out.last()) >= 3) out += w[i]
        out += w.last()
        return out
    }

    /** Short parallel lines stacked side by side: batteries (unequal) and capacitors (equal). */
    private fun findPlates(raw: Raw, diag: Double) {
        val straight = raw.wires.filter { it.size >= 2 && Geom.pathLen(it) > 0 && it.first().dist(it.last()) / Geom.pathLen(it) > 0.9 }
        val cand = straight.filter { val l = it.first().dist(it.last()); l > max(8.0, 0.02 * diag) && l < max(0.18 * diag, 30.0) }
        fun len(w: List<Pt>) = w.first().dist(w.last())
        fun dir(w: List<Pt>) = Geom.lineDir(w.first(), w.last())
        fun mid(w: List<Pt>) = Geom.mid(w.first(), w.last())
        val n = cand.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        val paired = BooleanArray(n)
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = cand[i]; val b = cand[j]
            if (Geom.angDiff180(dir(a), dir(b)) > 20) continue
            val ml = max(len(a), len(b))
            val d = mid(b) - mid(a)
            val u = Pt(cos(dir(a)), sin(dir(a)))
            val along = abs(d.x * u.x + d.y * u.y)
            val perp = abs(d.x * -u.y + d.y * u.x)
            if (along > 0.4 * ml || perp > 0.75 * ml || perp < 3) continue
            val touching = listOf(a.first(), a.last()).any { p -> listOf(b.first(), b.last()).any { it.dist(p) < 0.25 * ml } }
            if (touching) continue
            parent[find(i)] = find(j); paired[i] = true; paired[j] = true
        }
        val groups = (0 until n).filter { paired[it] }.groupBy { find(it) }.values
        for (g in groups) {
            val plates = g.map { cand[it] }
            val u = Pt(cos(dir(plates[0])), sin(dir(plates[0])))
            val nrm = Pt(-u.y, u.x)
            val sorted = plates.sortedBy { val m = mid(it); m.x * nrm.x + m.y * nrm.y }
            val lens = sorted.map { len(it) }
            val first = sorted.first(); val last = sorted.last()
            val body = Geom.bbox(sorted.flatten())
            val equal = sorted.size == 2 && min(lens[0], lens[1]) / max(lens[0], lens[1]) > 0.75
            val type = if (equal) CompType.CAPACITOR else CompType.BATTERY
            // the longer outer plate is +
            val plusFirst = lens.first() >= lens.last()
            val pa = mid(first); val pb = mid(last)
            val comp = if (type == CompType.BATTERY) Component(type, if (plusFirst) pb else pa, if (plusFirst) pa else pb, body, emptySet())
            else Component(type, pa, pb, body, emptySet())
            raw.comps += comp
            raw.wires.removeAll(plates)
        }
    }

    private fun connect(comps: List<Component>, wires: List<Wire>, tol: Double, B: Box): Schematic {
        val n = wires.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        val junctions = ArrayList<Pt>()
        for (i in 0 until n) for (j in 0 until n) {
            if (i == j) continue
            for (e in listOf(wires[i].pts.first(), wires[i].pts.last())) {
                if (wires[j].dist(e) < tol * 0.6) {
                    if (find(i) != find(j)) parent[find(i)] = find(j)
                    val endOfJ = wires[j].pts.first().dist(e) < tol || wires[j].pts.last().dist(e) < tol
                    if (!endOfJ) junctions += e
                }
            }
        }
        val roots = (0 until n).map { find(it) }.distinct()
        val nodeOfWire = IntArray(n) { roots.indexOf(find(it)) }
        var nodeCount = roots.size
        val termNode = HashMap<Pair<Component, Boolean>, Int>()
        val loose = ArrayList<Triple<Component, Boolean, Pt>>()
        for (c in comps) for (end in listOf(false, true)) {
            val t = (if (end) c.b else c.a) ?: continue
            var bi = -1; var bd = Double.POSITIVE_INFINITY
            wires.forEachIndexed { i, w -> val d = w.dist(t); if (d < bd) { bd = d; bi = i } }
            if (bi >= 0 && bd < tol * 1.3) termNode[c to end] = nodeOfWire[bi] else loose += Triple(c, end, t)
        }
        // terminals touching each other directly
        for (i in loose.indices) {
            val (c, end, t) = loose[i]
            if (termNode.containsKey(c to end)) continue
            val other = loose.firstOrNull { (c2, e2, t2) -> !(c2 === c && e2 == end) && t2.dist(t) < tol }
            val node = other?.let { termNode[it.first to it.second] } ?: nodeCount++
            termNode[c to end] = node
            if (other != null) termNode[other.first to other.second] = node
        }
        val openEnds = ArrayList<String>()
        for (c in comps) {
            c.nodeA = termNode[c to false] ?: nodeCount++
            c.nodeB = termNode[c to true] ?: nodeCount++
        }
        // names: R1, R2… left-to-right, top-to-bottom
        for (t in CompType.values()) comps.filter { it.type == t }.sortedWith(compareBy({ (it.center.y / 60).toInt() }, { it.center.x }))
            .forEachIndexed { i, c -> c.name = t.prefix + (i + 1) }
        // nodes reached by only one component end and no other: open ends
        val uses = IntArray(nodeCount)
        for (c in comps) { uses[c.nodeA]++; uses[c.nodeB]++ }
        for (c in comps) {
            if (uses[c.nodeA] == 1) openEnds += c.name
            else if (uses[c.nodeB] == 1) openEnds += c.name
        }
        // free wire ends: test terminals
        val wireEnds = ArrayList<Pair<Int, Pt>>()
        wires.forEachIndexed { i, w ->
            for (e in listOf(w.pts.first(), w.pts.last())) {
                val touches = wires.indices.any { j -> j != i && wires[j].dist(e) < tol * 0.6 } ||
                    comps.any { c -> listOfNotNull(c.a, c.b).any { it.dist(e) < tol * 1.3 } }
                if (!touches) wireEnds += nodeOfWire[i] to e
            }
        }
        val terminalNodes = wireEnds.map { it.first }.distinct()
        val ground = comps.firstOrNull { it.isSource }?.nodeA ?: terminalNodes.firstOrNull() ?: 0
        return Schematic(comps, wires, nodeCount, ground, terminalNodes, junctions.distinctBy { (it.x / 8).toInt() to (it.y / 8).toInt() }, openEnds.distinct(), B)
    }

    /* ===================== equations ===================== */
    fun valueVar(c: Component) = c.name
    fun currentVar(c: Component) = "I_" + c.name
    fun voltageVar(c: Component) = "V_" + c.name
    fun powerVar(c: Component) = "P_" + c.name
    fun chargeVar(c: Component) = "Q_" + c.name

    /** What to report for a component, in order. */
    fun quantities(c: Component): List<String> = when (c.type) {
        CompType.RESISTOR, CompType.LAMP -> listOf(currentVar(c), voltageVar(c), powerVar(c))
        CompType.BATTERY, CompType.SOURCE -> listOf(currentVar(c), powerVar(c))
        CompType.CAPACITOR -> listOf(voltageVar(c), chargeVar(c))
    }

    data class System(val eqs: List<LabeledEq>, val hiddenKnowns: Map<String, Double>, val totals: List<String>, val domains: Map<String, Domain>)

    fun equations(s: Schematic): System {
        val eqs = ArrayList<String>()
        val hidden = HashMap<String, Double>()
        val domains = HashMap<String, Domain>()
        val comps = s.components
        val sources = s.sources
        val hasR = comps.any { it.isResistive }
        val hasC = comps.any { it.type == CompType.CAPACITOR }
        // Without a source, measure between two free wire ends.
        val testMode = sources.isEmpty() && s.terminalNodes.size == 2
        val ground = if (testMode) s.terminalNodes[0] else s.ground
        val testNode = if (testMode) s.terminalNodes[1] else -1
        fun nv(k: Int) = if (k == ground) "0" else "n_$k"

        val kcl = HashMap<Int, MutableList<String>>()   // current leaving each node
        val qcl = HashMap<Int, MutableList<String>>()   // charge leaving each node
        val resistiveNode = HashSet<Int>()
        for (c in comps) {
            when (c.type) {
                CompType.RESISTOR, CompType.LAMP -> {
                    domains[c.name] = Domain.POSITIVE
                    eqs += "${voltageVar(c)} = ${nv(c.nodeA)} - ${nv(c.nodeB)}"
                    eqs += "${voltageVar(c)} = ${currentVar(c)}*${c.name}"
                    eqs += "${powerVar(c)} = ${voltageVar(c)}*${currentVar(c)}"
                    kcl.getOrPut(c.nodeA) { ArrayList() } += "+${currentVar(c)}"
                    kcl.getOrPut(c.nodeB) { ArrayList() } += "-${currentVar(c)}"
                    resistiveNode += c.nodeA; resistiveNode += c.nodeB
                }
                CompType.BATTERY, CompType.SOURCE -> {
                    eqs += "${nv(c.nodeB)} - ${nv(c.nodeA)} = ${c.name}"
                    eqs += "${powerVar(c)} = ${c.name}*${currentVar(c)}"
                    kcl.getOrPut(c.nodeB) { ArrayList() } += "-${currentVar(c)}"
                    kcl.getOrPut(c.nodeA) { ArrayList() } += "+${currentVar(c)}"
                    resistiveNode += c.nodeA; resistiveNode += c.nodeB
                }
                CompType.CAPACITOR -> {
                    domains[c.name] = Domain.POSITIVE
                    eqs += "${voltageVar(c)} = ${nv(c.nodeA)} - ${nv(c.nodeB)}"
                    eqs += "${chargeVar(c)} = ${c.name}*${voltageVar(c)}"
                    qcl.getOrPut(c.nodeA) { ArrayList() } += "+${chargeVar(c)}"
                    qcl.getOrPut(c.nodeB) { ArrayList() } += "-${chargeVar(c)}"
                }
            }
        }
        val totals = ArrayList<String>()
        if (testMode) {
            if (hasR) {
                kcl.getOrPut(testNode) { ArrayList() } += "-I_test"
                hidden["I_test"] = 1.0
                eqs += "R_total = ${nv(testNode)}/I_test"
                domains["R_total"] = Domain.POSITIVE
                totals += "R_total"
                resistiveNode += testNode
            } else if (hasC) {
                eqs += "${nv(testNode)} = V_test"
                hidden["V_test"] = 1.0
                eqs += "C_total = (" + (qcl[testNode] ?: listOf("0")).joinToString(" ") + ")/V_test"
                domains["C_total"] = Domain.POSITIVE
                totals += "C_total"
            }
        }
        for ((node, terms) in kcl) if (node != ground) eqs += terms.joinToString(" ") + " = 0"
        for ((node, terms) in qcl) if (node != ground && node !in resistiveNode && node != testNode) eqs += terms.joinToString(" ") + " = 0"
        if (sources.size == 1) {
            val src = sources[0]
            eqs += "I_total = ${currentVar(src)}"
            totals += "I_total"
            eqs += "P_total = ${powerVar(src)}"
            totals += "P_total"
            if (hasR) { eqs += "R_total = ${src.name}/I_total"; domains["R_total"] = Domain.POSITIVE; totals.add(0, "R_total") }
            if (hasC && !hasR) {
                val q = qcl[src.nodeB] ?: emptyList()
                if (q.isNotEmpty()) {
                    eqs += "C_total = (" + q.joinToString(" ") + ")/${src.name}"
                    domains["C_total"] = Domain.POSITIVE
                    totals.add(0, "C_total")
                    totals.remove("I_total"); totals.remove("P_total")
                }
            }
        }
        val parsed = eqs.mapNotNull { e -> (Expr.tryParse(e.replace("+ +", "+ ").replace("= +", "= ").replace(Regex("^\\+"), ""), true) as? Node.Eq)?.let { LabeledEq(it, e) } }
        return System(parsed, hidden, totals, domains)
    }

    fun isHidden(v: String) = v.startsWith("n_") || v == "I_test" || v == "V_test"

    fun label(v: String): String = when {
        v == "R_total" -> "Total resistance"
        v == "I_total" -> "Total current"
        v == "P_total" -> "Total power"
        v == "C_total" -> "Total capacitance"
        v.startsWith("I_") -> "Current through ${v.drop(2)}"
        v.startsWith("V_") -> "Voltage across ${v.drop(2)}"
        v.startsWith("P_") -> "Power in ${v.drop(2)}"
        v.startsWith("Q_") -> "Charge on ${v.drop(2)}"
        else -> v
    }

    fun shortLabel(v: String): String = when {
        v.endsWith("_total") -> v.substringBefore('_') + "ₜₒₜₐₗ"
        v.length > 2 && v[1] == '_' -> "${v[0]}(${v.drop(2)})"
        else -> v
    }

    fun unit(v: String, s: Schematic?): String {
        val comp = s?.components?.firstOrNull { it.name == v }
        if (comp != null) return comp.type.unit
        return when {
            v == "C_total" -> "µF"
            v.startsWith("R") -> "Ω"
            v.startsWith("I") -> "A"
            v.startsWith("V") -> "V"
            v.startsWith("P") -> "W"
            v.startsWith("Q") -> "µC"
            else -> ""
        }
    }

    /* ===================== reading values ===================== */
    class Reading(val values: Map<String, Double>, val target: Component?, val whole: Boolean, val issues: List<String>, val numbers: List<Pair<Shapes.NumberInk, Component?>>)

    fun annotate(s: Schematic, marks: List<Stroke>, finds: List<Stroke>, cache: MutableMap<String, List<app.sketchsolve.recog.Cand>>?): Reading {
        val D = max(s.box.diag, 1.0)
        val loops = finds.toMutableList()
        val digits = ArrayList<Stroke>()
        for (m in marks) {
            val (closed, sd, _) = Geom.loopiness(m.pts)
            if (closed && sd > max(0.12 * D, 40.0)) loops += m else digits += m
        }
        val numbers = Shapes.readNumbers(digits, cache)
        val values = LinkedHashMap<String, Double>()
        val issues = ArrayList<String>()
        val taken = HashSet<Component>()
        var target: Component? = null
        val assigned = ArrayList<Pair<Shapes.NumberInk, Component?>>()
        val order = numbers.map { n -> n to s.components.minByOrNull { distToBox(Pt(n.box.cx, n.box.cy), it.body) } }
            .sortedBy { (n, c) -> if (c == null) Double.MAX_VALUE else distToBox(Pt(n.box.cx, n.box.cy), c.body) }
        for ((n, near) in order) {
            val cc = Pt(n.box.cx, n.box.cy)
            val c = s.components.filter { it !in taken }.minByOrNull { distToBox(cc, it.body) }?.takeIf { distToBox(cc, it.body) < 0.25 * D + 50 }
            assigned += n to c
            if (c == null) { issues += "I couldn't tell which part “${n.text}” belongs to. Write it right next to the part."; continue }
            taken += c
            when {
                n.unknown -> target = c
                n.value == null -> issues += "I couldn't read “${n.text}” next to ${c.name}. Type its value in the list."
                else -> values[c.name] = n.value
            }
        }
        var whole = false
        for (l in loops) {
            val pts = s.components.map { it.center } + s.wires.flatMap { listOf(it.pts.first(), it.pts.last()) }
            val inside = pts.count { Geom.pointInPoly(it, l.pts) }
            if (pts.isNotEmpty() && inside.toDouble() / pts.size >= 0.6 && s.components.size > 1) { whole = true; target = null; continue }
            val lc = Geom.centroid(l.pts)
            val c = s.components.filter { Geom.pointInPoly(it.center, l.pts) }.minByOrNull { it.center.dist(lc) }
                ?: s.components.minByOrNull { it.center.dist(lc) }?.takeIf { it.center.dist(lc) < 0.25 * D }
            if (c == null) issues += "Your circle isn't around a part. Circle one component, or the whole circuit."
            else { target = c; whole = false }
        }
        if (s.openEnds.isNotEmpty()) issues += "These parts have an end that isn't connected: ${s.openEnds.joinToString(", ")}. No current can flow through them."
        return Reading(values, target, whole, issues, assigned)
    }

    private fun distToBox(p: Pt, b: Box): Double {
        val dx = max(max(b.x0 - p.x, 0.0), p.x - b.x1)
        val dy = max(max(b.y0 - p.y, 0.0), p.y - b.y1)
        return hypot(dx, dy)
    }

    fun solve(s: Schematic, sys: System, knowns: Map<String, Double>, target: String): SolveResult {
        var r = Solver.solveFor(sys.eqs, knowns + sys.hiddenKnowns, target, sys.domains, Domain.REAL, true, starts = 24)
        // Which way current flows depends on how the part was drawn; report sizes.
        val passive = s.components.any { it.isResistive && (target == currentVar(it) || target == voltageVar(it)) } ||
            s.components.any { it.type == CompType.CAPACITOR && (target == voltageVar(it) || target == chargeVar(it)) }
        if (passive && r.values.isNotEmpty()) r = r.copy(values = r.values.map { abs(it) }.distinct())
        return if (r.status == Status.UNDERDETERMINED) r.copy(need = r.need.filter { !isHidden(it) }, unknowns = r.unknowns.filter { !isHidden(it) }) else r
    }
}
