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

    private fun alwaysTrue(eq: Node.Eq): Boolean {
        val vs = Expr.vars(eq).toList()
        if (vs.isEmpty()) return false
        val idx = vs.withIndex().associate { it.value to it.index }
        val l = Expr.compile(eq.lhs, idx, true); val r = Expr.compile(eq.rhs, idx, true)
        val rnd = java.util.Random(99)
        var tried = 0
        repeat(6) {
            val env = DoubleArray(vs.size) { 0.5 + rnd.nextDouble() * 9 }
            val a = l(env); val b = r(env)
            if (!a.isFinite() || !b.isFinite()) return@repeat
            tried++
            if (abs(a - b) > 1e-9 * max(1.0, max(abs(a), abs(b)))) return false
        }
        return tried >= 3
    }

    /** Check a setup against the problem and solve it. */
    /** Facts everyone knows, which a setup may use without them being in the problem. */
    private val KNOWN_FACTS = mapOf(180.0 to "angles in a triangle add to 180°", 360.0 to "a full turn is 360°", 90.0 to "a right angle is 90°",
        60.0 to "60 minutes in an hour / 60 seconds in a minute", 24.0 to "24 hours in a day", 7.0 to "7 days in a week", 12.0 to "12 months in a year / 12 inches in a foot",
        52.0 to "52 weeks in a year", 365.0 to "365 days in a year", 3600.0 to "3600 seconds in an hour", 100.0 to "100 per cent / 100 cm in a metre",
        1000.0 to "1000 in a kilo-", 9.81 to "gravity 9.81 m/s²", 9.8 to "gravity 9.8 m/s²", 5280.0 to "5280 feet in a mile", 3.0 to "3 feet in a yard", 16.0 to "16 ounces in a pound")

    /**
     * Small, safe fixes for common slips by the language model, each reported
     * as a note: the asked-for quantity listed as known with a dummy value,
     * helper letters used without being listed, comments inside equations.
     */
    fun repair(problem: String, s0: Setup): Pair<Setup, List<Issue>> {
        var s = s0
        val notes = ArrayList<Issue>()
        // comments written inside an equation: "a = b (Wait, let's re-read…)"
        val cleaned = s.equations.map { e ->
            val m = Regex("\\s*[(\\[][^()\\[\\]]*?\\b(?:wait|note|assume|let's|this|since|because|where|the|implies)\\b.*$", RegexOption.IGNORE_CASE).find(e)
            if (m != null && Expr.tryParse(e.substring(0, m.range.first), words = true) is Node.Eq) e.substring(0, m.range.first).trim() else e
        }
        if (cleaned != s.equations) s = s.copy(equations = cleaned)
        val mentions = numbersIn(problem)
        val asked = s.givens.firstOrNull { it.name == s.ask }
        if (asked != null && (asked.value == null || mentions.none { near(it.value, asked.value) })) {
            s = s.copy(givens = s.givens - asked, unknowns = s.unknowns + asked.copy(value = null))
            notes += Issue(Issue.Severity.NOTE, "Treated ${asked.name} as the unknown being asked for.")
        }
        val names = s.all.map { it.name }.toSet()
        val extra = s.equations.mapNotNull { Expr.tryParse(it, words = true) }.flatMap { Expr.vars(it) }.distinct().filter { it !in names }
        if (extra.isNotEmpty()) {
            s = s.copy(unknowns = s.unknowns + extra.map { Quantity(it, null, "", "a helper unknown", null) })
            for (x in extra) notes += Issue(Issue.Severity.WARNING, "The letter $x wasn't explained, so it's treated as an extra unknown.")
        }
        // a rate given as plain "%" with time in the same formula is a rate per period
        return s to notes
    }

    fun check(problem: String, s0: Setup, deg: Boolean = true): WordAnswer {
        val (s, repairNotes) = repair(problem, s0)
        val issues = ArrayList<Issue>(repairNotes)
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
        if (s.givens.any { it.name == s.ask }) err("The quantity being asked for (${s.ask}) was treated as already known.")
        if (s.ask.isNotEmpty() && parsed.none { s.ask in Expr.vars(it) }) err("No equation involves ${s.ask}, the quantity being asked for.")
        // An equation that holds for every value (like p = p·0.8/0.8) says nothing.
        for (eq in parsed) if (alwaysTrue(eq)) err("${Expr.show(eq)} is true for any value, so it doesn't pin anything down.")

        // Every given must come from the problem (or be a well-known constant).
        val mentions = numbersIn(problem)
        for (g in s.givens) {
            val v = g.value
            if (v == null) { err("${g.name} (${g.meaning}) has no value."); continue }
            if (g.constant) continue
            val quoteFound = g.quote.isNullOrBlank() || problem.contains(g.quote.trim(), ignoreCase = true)
            val plainUnit = Units.parse(g.unit)?.let { it.dim.isNone || it.dim == Dim.of("angle" to 1) || it.dim == Dim.of("time" to 1) || it.dim == Dim.of("time" to -1) } ?: g.unit.isBlank()
            val fact = KNOWN_FACTS.entries.firstOrNull { near(it.key, v) }
            if (fact != null && quoteFound && plainUnit && mentions.none { near(it.value, v) }) { issues += Issue(Issue.Severity.NOTE, "Uses the known fact: ${fact.value}."); continue }
            fun matches(x: Double) = near(x, v) || near(x / 100, v) || near(x * 100, v)
            // the value must be a number in the problem, or a number inside a quote that really is in the problem
            val inText = mentions.any { matches(it.value) } ||
                (g.quote != null && problem.contains(g.quote, ignoreCase = true) && numbersIn(g.quote).any { matches(it.value) })
            if (!inText && g.quote != null && problem.contains(g.quote.trim(), ignoreCase = true)) {
                // a conversion of what was quoted, e.g. "1 hour and 30 minutes" → 1.5 h
                val parts = Regex("(\\d+(?:\\.\\d+)?)\\s*([A-Za-z]+)").findAll(g.quote).mapNotNull { m -> Units.convert(m.groupValues[1].toDouble(), m.groupValues[2], g.unit) }.toList()
                if (parts.isNotEmpty() && near(parts.sum(), v)) { issues += Issue(Issue.Severity.NOTE, "Converted “${g.quote}” to ${Expr.fmt(v)} ${g.unit}."); continue }
            }
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

        // Every given should be used by an equation.
        val usedNames = parsed.flatMap { Expr.vars(it) }.toSet()
        val literalValues = ArrayList<Double>().also { l -> parsed.forEach { literals(it, l) } }
        for (g in s.givens) if (g.name !in usedNames && literalValues.none { g.value != null && near(it, g.value) }) warn("${g.name} = ${Expr.fmt(g.value ?: Double.NaN)} (${g.meaning}) was written down but never used.")
        // One number in the problem shouldn't stand for two different things.
        for (m in mentions.filter { !it.soft }) {
            // count givens that equations actually use by name; a given only typed in as a number is the same use as that number
            val sameGivens = s.givens.filter { g -> g.value != null && near(g.value, m.value) && !g.constant && g.name in usedNames }
            val countInText = mentions.count { near(it.value, m.value) }
            val literal = ArrayList<Double>().also { l -> parsed.forEach { literals(it, l) } }.count { near(it, m.value) }
            if (sameGivens.size + literal > countInText && sameGivens.size + literal > 1)
                warn("The ${m.text} in the problem is used for more than one thing (${(sameGivens.map { it.name } + if (literal > 0) listOf("a number in an equation") else emptyList()).joinToString(", ")}).")
        }

        // Units must make sense.
        // A percentage rate "per year" works like %/time: p·(r/100)·t comes out in money.
        val unitMap = s.all.associate { q ->
            val rate = q.unit.trim().replace(" ", "").lowercase().let { it == "%/year" || it == "%/yr" || it == "%peryear" || it == "%/month" } ||
                q.unit.trim() == "%" && Regex("rate|interest|per (year|annum|month)|annual|growth|apr", RegexOption.IGNORE_CASE).containsMatchIn(q.meaning + " " + q.name)
            q.name to if (rate) UnitInfo(Units.PERCENT / Dim.of("time" to 1), "%/time") else Units.parse(q.unit)
        }
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
            // CHECKED needs at least two independent readings that agree, and no reading that failed its checks
            val conf = if ((good.size < checked.size || groups[0].size < 2) && best.confidence == Confidence.HIGH) Confidence.MEDIUM else best.confidence
            return best.copy(confidence = conf)
        }
        val alts = groups.drop(1).map { it[0].setup to it[0].result!!.value }
        return best.copy(confidence = Confidence.LOW, alternatives = alts,
            issues = best.issues + Issue(Issue.Severity.WARNING, "Different readings of the problem gave different answers. Check the setup below."))
    }
}
