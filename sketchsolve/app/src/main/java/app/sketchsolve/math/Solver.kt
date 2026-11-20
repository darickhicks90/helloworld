package app.sketchsolve.math

import kotlin.math.*

enum class Domain { REAL, POSITIVE, ANGLE }

data class LabeledEq(val eq: Node.Eq, val label: String? = null)

data class Step(
    val v: String,
    val value: Double,
    val eq: Node.Eq,
    val rearranged: Node? = null,
    val plusMinus: Boolean = false,
    val system: List<Node.Eq>? = null
)

enum class Status { GIVEN, SOLVED, MULTIPLE, UNDERDETERMINED, INCONSISTENT, NO_SOLUTION, NO_EQUATION }

data class SolveResult(
    val status: Status,
    val target: String,
    val values: List<Double> = emptyList(),
    val steps: List<Step> = emptyList(),
    val need: List<String> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val conflicts: List<Node.Eq> = emptyList(),
    val eq: Node.Eq? = null,
    val env: Map<String, Double> = emptyMap()
) {
    val value: Double get() = values.firstOrNull() ?: Double.NaN
    val ok: Boolean get() = status == Status.SOLVED || status == Status.GIVEN || status == Status.MULTIPLE
}

/**
 * Solves for one target from a set of equations and known values, and
 * decides whether the known values actually pin the target down.
 */
object Solver {

    private class PEq(val src: Node.Eq, val label: String?, val fL: CF, val fR: CF, val mL: CF, val mR: CF, val vars: Set<String>, val vi: IntArray)

    private fun holds(eq: PEq, env: DoubleArray): Boolean {
        val l = eq.fL(env)
        val r = eq.fR(env)
        if (!l.isFinite() || !r.isFinite()) return false
        val m = eq.mL(env) + eq.mR(env)
        return abs(l - r) <= 1e-7 * m + 1e-13
    }

    private fun inDomain(x: Double, d: Domain) = x.isFinite() && when (d) {
        Domain.POSITIVE -> x > 0
        Domain.ANGLE -> x > 0 && x < 180
        Domain.REAL -> true
    }

    private val grids = HashMap<Domain, DoubleArray>()
    private fun grid(d: Domain): DoubleArray = grids.getOrPut(d) {
        val g = ArrayList<Double>()
        when (d) {
            Domain.ANGLE -> { var x = 0.05; while (x < 180) { g.add(x); x += 0.1 } }
            Domain.POSITIVE -> { var k = -13.0; while (k <= 13.0001) { g.add(10.0.pow(k)); k += 0.02 } }
            Domain.REAL -> {
                val pos = ArrayList<Double>()
                var k = -10.0
                while (k <= 10.0001) { pos.add(10.0.pow(k)); k += 0.025 }
                for (i in pos.indices.reversed()) g.add(-pos[i])
                g.add(0.0)
                g.addAll(pos)
            }
        }
        g.toDoubleArray()
    }

    private fun solve1D(eq: PEq, vIdx: Int, vName: String, env0: DoubleArray, domain: Domain, idx: Map<String, Int>, deg: Boolean): List<Double> {
        val env = env0.copyOf()
        fun g(x: Double): Double { env[vIdx] = x; return eq.fL(env) - eq.fR(env) }
        fun ok(x: Double): Boolean { env[vIdx] = x; return holds(eq, env) }
        val roots = ArrayList<Double>()
        fun add(x: Double) {
            if (!inDomain(x, domain) || !ok(x)) return
            if (roots.any { r -> abs(r - x) <= 1e-7 * max(abs(x), abs(r)) || (abs(r) < 1e-12 && abs(x) < 1e-12) }) return
            roots.add(x)
        }
        Expr.isolate(eq.src, vName)?.let { iso ->
            val v = Expr.compile(iso.expr, idx, deg)(env0)
            if (v.isFinite()) {
                add(v)
                if (iso.plusMinus) add(-v)
                if (iso.periodic && deg) { add(180 - v); add(-v); add(360 - v) }
            }
        }
        val xs = grid(domain)
        val gs = DoubleArray(xs.size) { g(xs[it]) }
        var minima = 0
        for (i in 1 until xs.size) {
            val a = gs[i - 1]
            val b = gs[i]
            if (!a.isFinite() || !b.isFinite()) continue
            if (b == 0.0) { add(xs[i]); continue }
            if (a != 0.0 && (a < 0) != (b < 0)) {
                var lo = xs[i - 1]
                var hi = xs[i]
                var glo = a
                for (it in 0 until 200) {
                    val mid = 0.5 * (lo + hi)
                    if (mid == lo || mid == hi) break
                    val gm = g(mid)
                    if (gm == 0.0) { lo = mid; hi = mid; break }
                    if ((gm < 0) == (glo < 0)) { lo = mid; glo = gm } else hi = mid
                }
                add(0.5 * (lo + hi))
            }
            if (i < xs.size - 1 && minima < 300) {
                val c = gs[i + 1]
                if (c.isFinite() && abs(b) <= abs(a) && abs(b) <= abs(c) && (b < 0) == (a < 0) && (b < 0) == (c < 0)) {
                    minima++
                    var lo = xs[i - 1]
                    var hi = xs[i + 1]
                    val gr = 0.6180339887
                    for (it in 0 until 120) {
                        val x1 = hi - gr * (hi - lo)
                        val x2 = lo + gr * (hi - lo)
                        if (abs(g(x1)) < abs(g(x2))) hi = x2 else lo = x1
                    }
                    add(0.5 * (lo + hi))
                }
            }
        }
        roots.sort()
        return roots
    }

    private fun solveLinear(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val M = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) A[i][j] else b[i] } }
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(M[r][c]) > abs(M[piv][c])) piv = r
            if (abs(M[piv][c]) < 1e-300) return null
            val t = M[c]; M[c] = M[piv]; M[piv] = t
            for (r in c + 1 until n) {
                val f = M[r][c] / M[c][c]
                if (f != 0.0) for (k in c..n) M[r][k] -= f * M[c][k]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = M[r][n]
            for (k in r + 1 until n) s -= M[r][k] * x[k]
            x[r] = s / M[r][r]
        }
        return x
    }

    /** Jacobi eigen-decomposition of a small symmetric matrix. */
    private fun symEig(A: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = A.size
        val a = Array(n) { A[it].copyOf() }
        val V = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        var norm = 0.0
        for (i in 0 until n) for (j in 0 until n) norm += a[i][j] * a[i][j]
        for (sweep in 0 until 60) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
            if (off <= 1e-30 * norm || off == 0.0) break
            for (p in 0 until n) for (q in p + 1 until n) {
                if (abs(a[p][q]) < 1e-300) continue
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val sgn = if (theta >= 0) 1.0 else -1.0
                val t = sgn / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until n) { val akp = a[k][p]; val akq = a[k][q]; a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq }
                for (k in 0 until n) { val apk = a[p][k]; val aqk = a[q][k]; a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk }
                for (k in 0 until n) { val vkp = V[k][p]; val vkq = V[k][q]; V[k][p] = c * vkp - s * vkq; V[k][q] = s * vkp + c * vkq }
            }
        }
        return DoubleArray(n) { a[it][it] } to V
    }

    private fun nearEdge(x: Double, d: Domain, scale: Double) = when (d) {
        Domain.POSITIVE -> x < 1e-6 * scale
        Domain.ANGLE -> x < 0.01 || x > 179.99
        Domain.REAL -> false
    }

    private class Rng(var s: Int) {
        fun next(): Double {
            s += 0x6D2B79F5
            var t = s
            t = (t xor (t ushr 15)) * (1 or t)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
        }
    }

    private fun lmSolve(eqs: List<PEq>, U: IntArray, base: DoubleArray, doms: Array<Domain>, scale: Double, rng: Rng, starts: Int): List<DoubleArray> {
        val n = U.size
        val m = eqs.size
        fun toU(z: Double, j: Int) = when (doms[j]) {
            Domain.POSITIVE -> exp(z)
            Domain.ANGLE -> 180 / (1 + exp(-z))
            Domain.REAL -> z
        }
        val env = base.copyOf()
        // Each equation is scaled by its size at the start of an iteration and
        // kept fixed during that iteration, so every step solves a smooth problem.
        val w = DoubleArray(m) { 1.0 }
        fun resid(z: DoubleArray): DoubleArray {
            for (j in 0 until n) env[U[j]] = toU(z[j], j)
            return DoubleArray(m) { i -> (eqs[i].fL(env) - eqs[i].fR(env)) * w[i] }
        }
        fun reweight(z: DoubleArray) {
            for (j in 0 until n) env[U[j]] = toU(z[j], j)
            var avg = 0.0
            val mags = DoubleArray(m) { i -> (eqs[i].mL(env) + eqs[i].mR(env)).let { if (it.isFinite()) it else 0.0 } }
            for (x in mags) avg += x
            avg = avg / max(1, m) + 1e-300
            for (i in 0 until m) w[i] = 1 / (mags[i] + 1e-6 * avg + 1e-300)
        }
        fun cost(r: DoubleArray): Double { var s = 0.0; for (x in r) s += x * x; return if (s.isFinite()) s else Double.POSITIVE_INFINITY }
        val out = ArrayList<DoubleArray>()
        for (st in 0 until starts) {
            var z = DoubleArray(n) { j ->
                when (doms[j]) {
                    Domain.POSITIVE -> ln(scale * 10.0.pow(rng.next() * 3 - 1.5))
                    Domain.ANGLE -> { val p = 0.03 + 0.94 * rng.next(); ln(p / (1 - p)) }
                    Domain.REAL -> (if (rng.next() < 0.5) -1 else 1) * scale * 10.0.pow(rng.next() * 3 - 1.5)
                }
            }
            reweight(z)
            var r = resid(z)
            var c = cost(r)
            if (!c.isFinite()) continue
            var lambda = 1e-3
            var it = 0
            while (it < 200 && c > 1e-26) {
                it++
                reweight(z)
                r = resid(z)
                c = cost(r)
                if (!c.isFinite()) break
                val J = Array(m) { DoubleArray(n) }
                for (j in 0 until n) {
                    val h = 1e-7 * max(1.0, abs(z[j]))
                    val zj = z[j]
                    z[j] = zj + h
                    val r2 = resid(z)
                    z[j] = zj
                    for (i in 0 until m) J[i][j] = (r2[i] - r[i]) / h
                }
                val A = Array(n) { DoubleArray(n) }
                val g = DoubleArray(n)
                for (p in 0 until n) {
                    for (q in 0 until n) { var s = 0.0; for (i in 0 until m) s += J[i][p] * J[i][q]; A[p][q] = s }
                    for (i in 0 until m) g[p] += J[i][p] * r[i]
                }
                var improved = false
                var small = false
                for (tries in 0 until 12) {
                    val Al = Array(n) { p -> DoubleArray(n) { q -> if (p == q) A[p][q] * (1 + lambda) + lambda * 1e-9 else A[p][q] } }
                    val delta = solveLinear(Al, DoubleArray(n) { -g[it] })
                    if (delta == null || delta.any { !it.isFinite() }) { lambda *= 10; continue }
                    val zn = DoubleArray(n) { z[it] + delta[it] }
                    val rn = resid(zn)
                    val cn = cost(rn)
                    if (cn < c) {
                        small = (0 until n).all { abs(delta[it]) < 1e-14 * (1 + abs(z[it])) }
                        z = zn; r = rn; c = cn
                        lambda = max(lambda / 3, 1e-15)
                        improved = true
                        break
                    }
                    lambda *= 4
                    if (lambda > 1e15) break
                }
                if (!improved || small) break
            }
            for (j in 0 until n) env[U[j]] = toU(z[j], j)
            if (eqs.all { holds(it, env) } && (0 until n).all { inDomain(env[U[it]], doms[it]) && !nearEdge(env[U[it]], doms[it], scale) }) {
                out.add(env.copyOf())
            }
        }
        return out
    }

    private fun jacobianNormal(eqs: List<PEq>, U: IntArray, env: DoubleArray, scale: Double): Array<DoubleArray> {
        val n = U.size
        val s = DoubleArray(n) { max(max(abs(env[U[it]]), 1e-9 * scale), 1e-15) }
        val rows = eqs.map { eq ->
            val w = 1 / (eq.mL(env) + eq.mR(env) + 1e-300)
            DoubleArray(n) { j ->
                val k = U[j]
                val x0 = env[k]
                val h = 1e-6 * s[j]
                env[k] = x0 + h
                val fp = eq.fL(env) - eq.fR(env)
                env[k] = x0 - h
                val fm = eq.fL(env) - eq.fR(env)
                env[k] = x0
                (fp - fm) / (2 * h) * s[j] * w
            }
        }
        return Array(n) { p -> DoubleArray(n) { q -> var sum = 0.0; for (row in rows) sum += row[p] * row[q]; sum } }
    }

    private fun targetFixed(M: Array<DoubleArray>, t: Int): Boolean {
        val (values, vectors) = symEig(M)
        val lmax = max(0.0, values.maxOrNull() ?: 0.0)
        for (k in values.indices) {
            if (values[k] <= 1e-9 * lmax + 1e-300 && abs(vectors[t][k]) > 1e-3) return false
        }
        return true
    }

    private fun withPinned(M: Array<DoubleArray>, j: Int): Array<DoubleArray> {
        val lmax = max(1e-300, symEig(M).first.maxOrNull() ?: 0.0)
        val M2 = Array(M.size) { M[it].copyOf() }
        M2[j][j] += lmax
        return M2
    }

    fun solveFor(
        equations: List<LabeledEq>,
        knowns: Map<String, Double>,
        target: String,
        domains: Map<String, Domain> = emptyMap(),
        defaultDomain: Domain = Domain.REAL,
        deg: Boolean = true,
        starts: Int = 36,
        seed: Int = 12345
    ): SolveResult {
        fun domOf(v: String) = domains[v] ?: defaultDomain
        val known = knowns.filterValues { it.isFinite() }
        known[target]?.let { return SolveResult(Status.GIVEN, target, listOf(it)) }

        // index every name
        val names = LinkedHashSet<String>()
        names.add(target)
        names.addAll(known.keys)
        for (e in equations) names.addAll(Expr.vars(e.eq))
        val nameList = names.toList()
        val idx = nameList.withIndex().associate { it.value to it.index }
        val eqs = equations.map { e ->
            val vs = Expr.vars(e.eq)
            PEq(e.eq, e.label, Expr.compile(e.eq.lhs, idx, deg), Expr.compile(e.eq.rhs, idx, deg),
                Expr.compileMag(e.eq.lhs, idx, deg), Expr.compileMag(e.eq.rhs, idx, deg), vs, vs.map { idx.getValue(it) }.toIntArray())
        }

        // only equations linked to the target through unknowns matter
        val comp = ArrayList<PEq>()
        val seen = hashSetOf(target)
        val queue = ArrayDeque(listOf(target))
        val used = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            eqs.forEachIndexed { i, eq ->
                if (i in used || v !in eq.vars) return@forEachIndexed
                used.add(i); comp.add(eq)
                for (w in eq.vars) if (w !in known && seen.add(w)) queue.add(w)
            }
        }
        if (comp.isEmpty()) return SolveResult(Status.NO_EQUATION, target)

        val env = DoubleArray(nameList.size) { Double.NaN }
        val isKnown = BooleanArray(nameList.size)
        for ((k, v) in known) { env[idx.getValue(k)] = v; isKnown[idx.getValue(k)] = true }
        data class Raw(val eq: PEq, val v: String, val value: Double)
        val steps = ArrayList<Raw>()

        // 1) chain: solve equations with exactly one unknown
        // An equation with no possible value (e.g. a total resistance of 6/0 when
        // no current flows) only matters if the answer depends on it.
        val failed = LinkedHashSet<PEq>()
        var progress = true
        while (progress) {
            progress = false
            for (eq in comp) {
                if (eq in failed) continue
                val unk = eq.vars.filter { !isKnown[idx.getValue(it)] }
                if (unk.size != 1) continue
                val v = unk[0]
                val vi = idx.getValue(v)
                val roots = solve1D(eq, vi, v, env, domOf(v), idx, deg)
                if (roots.size == 1) {
                    env[vi] = roots[0]; isKnown[vi] = true
                    steps.add(Raw(eq, v, roots[0]))
                    progress = true
                } else if (roots.isEmpty()) {
                    failed += eq
                }
            }
        }
        // 2) fully-known equations must agree
        val conflicts = eqs.filter { eq -> eq.vars.all { isKnown[idx.getValue(it)] } && !holds(eq, env) }
        if (conflicts.isNotEmpty()) return SolveResult(Status.INCONSISTENT, target, conflicts = conflicts.map { it.src })

        fun relevant(t: String?): List<Step> {
            if (t == null) return steps.map { stepOut(it.eq.src, it.v, it.value) }
            val need = hashSetOf(t)
            val keep = ArrayList<Raw>()
            for (i in steps.indices.reversed()) {
                val s = steps[i]
                if (s.v !in need) continue
                keep.add(0, s)
                for (w in s.eq.vars) if (w != s.v && w !in known) need.add(w)
            }
            return keep.map { stepOut(it.eq.src, it.v, it.value) }
        }
        fun envMap(e: DoubleArray) = nameList.withIndex().filter { e[it.index].isFinite() }.associate { it.value to e[it.index] }

        val ti = idx.getValue(target)
        if (isKnown[ti]) return SolveResult(Status.SOLVED, target, listOf(env[ti]), relevant(target), env = envMap(env))
        if (failed.isNotEmpty()) return SolveResult(Status.NO_SOLUTION, target, eq = failed.first().src, steps = relevant(target))

        // 3) solve what is left together
        val rest = comp.filter { eq -> eq.vars.any { !isKnown[idx.getValue(it)] } }
        val Ulist = rest.flatMap { eq -> eq.vars.filter { !isKnown[idx.getValue(it)] } }.distinct().sortedBy { if (it == target) 0 else 1 }
        val U = Ulist.map { idx.getValue(it) }.toIntArray()
        val doms = Array(Ulist.size) { domOf(Ulist[it]) }
        var sl = 0.0
        var sk = 0
        for ((k, v) in known) if (domOf(k) != Domain.ANGLE && v != 0.0) { sl += ln(abs(v)); sk++ }
        val scale = if (sk > 0) exp(sl / sk) else 1.0
        val sols = lmSolve(rest, U, env, doms, scale, Rng(seed), starts)
        if (sols.isEmpty()) return SolveResult(Status.NO_SOLUTION, target, steps = relevant(target))
        val t = 0
        val fixed = ArrayList<DoubleArray>()
        val loose = ArrayList<Pair<DoubleArray, Array<DoubleArray>>>()
        for (e in sols) {
            val M = jacobianNormal(rest, U, e, scale)
            if (targetFixed(M, t)) fixed.add(e) else loose.add(e to M)
        }
        if (fixed.isEmpty() || loose.size >= max(2.0, 0.3 * sols.size)) {
            val M = loose[0].second
            val need = Ulist.indices.filter { j -> j != t && targetFixed(withPinned(M, j), t) }.map { Ulist[it] }
            return SolveResult(Status.UNDERDETERMINED, target, need = need, unknowns = Ulist.filter { it != target })
        }
        val values = ArrayList<Double>()
        for (e in fixed) {
            val x = e[ti]
            if (values.none { y -> abs(y - x) <= 1e-6 * max(max(abs(x), abs(y)), 1e-12) }) values.add(x)
        }
        values.sort()
        val sys = Step(target, values[0], rest[0].src, system = rest.map { it.src })
        val all = relevant(null) + sys
        return if (values.size == 1) SolveResult(Status.SOLVED, target, values, all, env = envMap(fixed[0]))
        else SolveResult(Status.MULTIPLE, target, values, all)
    }

    private fun stepOut(eq: Node.Eq, v: String, value: Double): Step {
        val iso = Expr.isolate(eq, v)
        return Step(v, value, eq, iso?.expr, iso?.plusMinus ?: false)
    }
}
