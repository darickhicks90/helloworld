package app.sketchsolve.word

import app.sketchsolve.math.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/* ===================== the setup the language model writes ===================== */

data class Quantity(val name: String, val value: Double?, val unit: String, val meaning: String, val quote: String?, val constant: Boolean = false)

sealed class Diagram {
    /** Things moving along a line, e.g. two trains. */
    data class Motion(val movers: List<Mover>, val gap: String?) : Diagram()
    data class Mover(val label: String, val speed: String, val direction: String, val from: String)
    /** A table, e.g. rate × time = work, or amount × percent = salt. */
    data class Table(val headers: List<String>, val rows: List<List<String>>) : Diagram()
    /** A bar split into parts (percent, ratio, part-whole). */
    data class Bar(val parts: List<Pair<String, String>>, val total: String?) : Diagram()
    data class NumberLine(val points: List<Pair<String, String>>) : Diagram()
    /** A labelled shape: type ∈ rectangle, triangle, rightTriangle, circle, box, cylinder. */
    data class Shape(val type: String, val labels: Map<String, String>) : Diagram()
}

data class Setup(
    val kind: String,
    val unknowns: List<Quantity>,
    val givens: List<Quantity>,
    val equations: List<String>,
    val ask: String,
    val answerUnit: String?,
    val diagram: Diagram?,
    val raw: String = ""
) {
    val all get() = givens + unknowns
    fun quantity(name: String) = all.firstOrNull { it.name == name }

    companion object {
        /** Pull the first JSON object out of model output, tolerating chatter and ``` fences. */
        fun extractJson(text: String): String? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0; var inStr = false; var esc = false
            for (i in start until text.length) {
                val c = text[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) } }
            }
            return null
        }

        fun parse(text: String): Setup? {
            val js = extractJson(text) ?: return null
            return try {
                val o = JSONObject(js)
                fun quantities(key: String): List<Quantity> {
                    val arr = o.optJSONArray(key) ?: return emptyList()
                    return (0 until arr.length()).mapNotNull { i ->
                        val q = arr.optJSONObject(i) ?: return@mapNotNull null
                        val name = q.optString("name").trim()
                        if (name.isEmpty()) return@mapNotNull null
                        val v = if (q.has("value") && !q.isNull("value")) q.optDouble("value").takeIf { it.isFinite() } ?: q.optString("value").let { s -> Expr.tryParse(s)?.let { runCatching { Expr.evaluate(it) }.getOrNull() } } else null
                        Quantity(cleanName(name), v, q.optString("unit"), q.optString("meaning"), q.optString("quote").ifEmpty { null }, q.optBoolean("constant"))
                    }
                }
                val eqs = o.optJSONArray("equations")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.filter { it.isNotBlank() } ?: emptyList()
                Setup(o.optString("kind", "general"), quantities("unknowns"), quantities("givens"), eqs, cleanName(o.optString("ask")),
                    o.optString("answer_unit").ifEmpty { null }, o.optJSONObject("diagram")?.let { parseDiagram(it) }, js)
            } catch (e: Exception) { null }
        }

        private fun cleanName(n: String) = n.trim().replace(Regex("[^A-Za-z0-9_]"), "_").trim('_')

        private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

        private fun parseDiagram(d: JSONObject): Diagram? = when (d.optString("type")) {
            "motion" -> {
                val m = d.optJSONArray("movers")
                Diagram.Motion((0 until (m?.length() ?: 0)).mapNotNull { i -> m!!.optJSONObject(i)?.let { Diagram.Mover(it.optString("label"), it.optString("speed"), it.optString("direction", "right"), it.optString("from", "left")) } }, d.optString("gap").ifEmpty { null })
            }
            "table" -> {
                val rows = d.optJSONArray("rows")
                Diagram.Table(strings(d.optJSONArray("headers")), (0 until (rows?.length() ?: 0)).map { strings(rows!!.optJSONArray(it)) })
            }
            "bar" -> {
                val p = d.optJSONArray("parts")
                Diagram.Bar((0 until (p?.length() ?: 0)).mapNotNull { i -> p!!.optJSONObject(i)?.let { it.optString("label") to it.optString("value") } }, d.optString("total").ifEmpty { null })
            }
            "numberline" -> {
                val p = d.optJSONArray("points")
                Diagram.NumberLine((0 until (p?.length() ?: 0)).mapNotNull { i -> p!!.optJSONObject(i)?.let { it.optString("label") to it.optString("value") } })
            }
            "shape" -> {
                val l = d.optJSONObject("labels")
                Diagram.Shape(d.optString("shape", "rectangle"), l?.keys()?.asSequence()?.associateWith { l.optString(it) } ?: emptyMap())
            }
            else -> null
        }
    }
}

/* ===================== checking and solving a setup ===================== */

data class Issue(val severity: Severity, val message: String) { enum class Severity { ERROR, WARNING, NOTE } }

enum class Confidence { HIGH, MEDIUM, LOW }

data class WordAnswer(
    val setup: Setup,
    val result: SolveResult?,
    val issues: List<Issue>,
    val confidence: Confidence,
    val alternatives: List<Pair<Setup, Double>> = emptyList()
) {
    val ok get() = result != null && result.ok && issues.none { it.severity == Issue.Severity.ERROR }
}

/** A number found in the problem text, with the words around it. */
data class NumberMention(val value: Double, val text: String, val start: Int, val soft: Boolean = false)

object WordSolver {

    /** Every number in the problem: digits, "%", money, number words, and words like "twice" or "half". */
    fun numbersIn(problem: String): List<NumberMention> {
        val out = ArrayList<NumberMention>()
        val digits = Regex("(?<![A-Za-z])[\\$£€]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|(?<![A-Za-z])[\\$£€]?\\d*\\.?\\d+")
        for (m in digits.findAll(problem)) {
            val t = m.value.replace(Regex("[\\$£€,]"), "")
            t.toDoubleOrNull()?.let { out += NumberMention(it, m.value, m.range.first) }
        }
        // number words ("three trains", "two thirds")
        val converted = SpokenMath.numbersToDigits(problem)
        if (converted != problem) {
            for (m in Regex("\\d+(?:\\.\\d+)?").findAll(converted)) {
                val v = m.value.toDouble()
                // small number words ("two trains") usually count things rather than being data
                if (out.none { it.value == v }) out += NumberMention(v, m.value, m.range.first, soft = v < 10)
            }
        }
        for ((w, v) in SpokenMath.WORD_MULTIPLIERS) Regex("\\b$w\\b", RegexOption.IGNORE_CASE).find(problem)?.let { out += NumberMention(v, it.value, it.range.first, soft = true) }
        return out.sortedBy { it.start }
    }

    private fun near(a: Double, b: Double) = abs(a - b) <= 1e-9 * max(1.0, max(abs(a), abs(b)))

    private fun literals(n: Node, out: MutableList<Double>) {
        when (n) {
            is Node.Num -> out += n.v
            is Node.Neg -> literals(n.a, out)
            is Node.Fn -> literals(n.a, out)
            is Node.Op -> { literals(n.a, out); literals(n.b, out) }
            is Node.Eq -> { literals(n.lhs, out); literals(n.rhs, out) }
            is Node.Var -> {}
        }
    }

    /** Check a setup against the problem and solve it. */
    fun check(problem: String, s: Setup, deg: Boolean = true): WordAnswer {
        val issues = ArrayList<Issue>()
        fun err(m: String) { issues += Issue(Issue.Severity.ERROR, m) }
        fun warn(m: String) { issues += Issue(Issue.Severity.WARNING, m) }

        if (s.equations.isEmpty()) err("No equations were written.")
        if (s.ask.isEmpty()) err("It isn't clear what the question asks for.")
        val parsed = ArrayList<Node.Eq>()
        for (e in s.equations) {
            when (val n = Expr.tryParse(e, words = true)) {
                is Node.Eq -> parsed += n
                null -> err("Couldn't read the equation “$e”.")
                else -> err("“$e” isn't an equation (it has no = sign).")
            }
        }
        val names = s.all.map { it.name }.toSet()
        for (eq in parsed) for (v in Expr.vars(eq)) if (v !in names) err("The letter $v in ${Expr.show(eq)} was never explained.")
        if (s.ask.isNotEmpty() && s.ask !in names) err("The answer ${s.ask} isn't one of the quantities.")

        // Every given must come from the problem (or be a well-known constant).
        val mentions = numbersIn(problem)
        for (g in s.givens) {
            val v = g.value
            if (v == null) { err("${g.name} (${g.meaning}) has no value."); continue }
            if (g.constant) continue
            fun matches(x: Double) = near(x, v) || near(x / 100, v) || near(x * 100, v)
            // the value must be a number in the problem, or a number inside a quote that really is in the problem
            val inText = mentions.any { matches(it.value) } ||
                (g.quote != null && problem.contains(g.quote, ignoreCase = true) && numbersIn(g.quote).any { matches(it.value) })
            if (!inText) err("${g.name} = ${Expr.fmt(v)} (${g.meaning}) doesn't appear in the problem.")
        }
        // Every number in the problem should be used somewhere.
        val used = ArrayList<Double>()
        s.givens.forEach { g -> g.value?.let { used += it } }
        parsed.forEach { literals(it, used) }
        val quotes = s.givens.mapNotNull { it.quote?.lowercase() }
        for (m in mentions) {
            if (m.soft) continue
            val whole = Regex("(?<![\\d.])" + Regex.escape(m.text.lowercase()) + "(?![\\d.])")
            val isUsed = used.any { near(it, m.value) || near(it, m.value / 100) || near(it, m.value * 100) } || quotes.any { whole.containsMatchIn(it) }
            if (!isUsed) warn("The ${m.text} in the problem wasn't used. Check that's right.")
        }

        // Units must make sense.
        val unitMap = s.all.associate { it.name to Units.parse(it.unit) }
        val an = Units.Analyzer(unitMap)
        for (eq in parsed) an.of(eq)
        for (p in an.problems.distinct()) when (p) {
            is Units.Check.Mismatch -> err("Units don't match: ${p.a} is being added to or compared with ${p.b}.")
            is Units.Check.Mixed -> warn("${p.a} and ${p.b} are mixed in one sum. They need converting to the same unit first.")
            Units.Check.Ok -> {}
        }

        if (issues.any { it.severity == Issue.Severity.ERROR }) return WordAnswer(s, null, issues, Confidence.LOW)

        val knowns = s.givens.mapNotNull { g -> g.value?.let { g.name to it } }.toMap()
        val domains = s.unknowns.associate { u ->
            val ui = unitMap[u.name]
            u.name to when {
                ui?.dim == Dim.of("angle" to 1) && u.unit.contains("°") || u.unit.lowercase().startsWith("deg") -> Domain.ANGLE
                Units.mustBePositive(ui) || Units.isCountable(ui) -> Domain.POSITIVE
                else -> Domain.REAL
            }
        }
        val r = Solver.solveFor(parsed.map { LabeledEq(it) }, knowns, s.ask, domains, Domain.REAL, deg)
        if (r.ok) {
            val u = unitMap[s.ask]
            if (Units.isCountable(u) && r.values.any { abs(it - Math.round(it)) > 1e-6 }) warn("The answer should be a whole number of ${s.quantity(s.ask)?.unit}, but it came out as ${Expr.fmt(r.value)}.")
            if (s.quantity(s.ask)?.unit?.trim() == "%" && r.values.any { it < 0 || it > 100 }) warn("A percentage of ${Expr.fmt(r.value)}% looks unlikely.")
        } else if (r.status == Status.UNDERDETERMINED || r.status == Status.NO_EQUATION) {
            issues += Issue(Issue.Severity.NOTE, "The problem doesn't give enough information to find ${s.ask}.")
        }
        val conf = when {
            !r.ok -> Confidence.LOW
            issues.any { it.severity == Issue.Severity.WARNING } -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }
        return WordAnswer(s, r, issues, conf)
    }

    /**
     * Combine several independent setups of the same problem. If their
     * answers agree, confidence stays; if they disagree, it drops to LOW and
     * the alternatives are kept so the person can choose.
     */
    fun consensus(problem: String, setups: List<Setup>, deg: Boolean = true): WordAnswer? {
        val checked = setups.map { check(problem, it, deg) }
        val good = checked.filter { it.ok }
        if (good.isEmpty()) return checked.minByOrNull { a -> a.issues.count { it.severity == Issue.Severity.ERROR } }
        val groups = ArrayList<MutableList<WordAnswer>>()
        for (a in good) {
            val g = groups.firstOrNull { near(it[0].result!!.value, a.result!!.value) || abs(it[0].result!!.value - a.result.value) <= 1e-6 * max(1.0, abs(a.result.value)) }
            if (g != null) g += a else groups += mutableListOf(a)
        }
        groups.sortByDescending { it.size }
        val best = groups[0].minByOrNull { a -> a.issues.size }!!
        if (groups.size == 1) {
            // a reading that failed its checks lowers confidence a notch
            val conf = if (good.size < checked.size && best.confidence == Confidence.HIGH) Confidence.MEDIUM else best.confidence
            return best.copy(confidence = conf)
        }
        val alts = groups.drop(1).map { it[0].setup to it[0].result!!.value }
        return best.copy(confidence = Confidence.LOW, alternatives = alts,
            issues = best.issues + Issue(Issue.Severity.WARNING, "Different readings of the problem gave different answers. Check the setup below."))
    }
}
