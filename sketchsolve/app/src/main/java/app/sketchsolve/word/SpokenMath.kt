package app.sketchsolve.word

import app.sketchsolve.math.Expr
import app.sketchsolve.math.Node

/**
 * Turns spoken (or casually typed) maths into maths text.
 *   "x squared plus three equals eleven"  →  "x^2 + 3 = 11"
 *   "what is fifteen percent of eighty"   →  "(15/100)*80"
 * and decides whether what was said is a calculation, an equation, or a
 * word problem that needs the language model.
 */
object SpokenMath {
    enum class Kind { EXPRESSION, EQUATION, WORD_PROBLEM }
    data class Result(val kind: Kind, val text: String, val original: String)

    private val ONES = mapOf("zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val SCALES = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val FRACTIONS = mapOf("half" to 2, "halves" to 2, "third" to 3, "thirds" to 3, "quarter" to 4, "quarters" to 4, "fourth" to 4, "fourths" to 4,
        "fifth" to 5, "fifths" to 5, "sixth" to 6, "sixths" to 6, "seventh" to 7, "sevenths" to 7, "eighth" to 8, "eighths" to 8,
        "ninth" to 9, "ninths" to 9, "tenth" to 10, "tenths" to 10)
    val WORD_MULTIPLIERS = mapOf("twice" to 2.0, "double" to 2.0, "triple" to 3.0, "thrice" to 3.0, "half" to 0.5, "dozen" to 12.0, "quadruple" to 4.0)

    /** Replace number words with digits: "two hundred and five point two five" → "205.25". */
    fun numbersToDigits(text: String): String {
        val words = text.split(Regex("(?<=\\s)|(?=\\s)"))   // keep whitespace tokens
        val out = StringBuilder()
        var i = 0
        while (i < words.size) {
            if (words[i].isBlank()) { out.append(words[i]); i++; continue }
            val (value, used) = readNumber(words, i)
            if (used > 0) { out.append(fmtNum(value)); i += used } else { out.append(words[i]); i++ }
        }
        return out.toString()
    }

    private fun clean(w: String) = w.lowercase().trim().trim(',', '.', '?', '!')

    /** Reads a spoken number starting at word i; returns (value, tokens used). */
    private fun readNumber(tokens: List<String>, start: Int): Pair<Double, Int> {
        var i = start
        var total = 0L
        var current = 0L
        var any = false
        var lastIdx = start
        fun word(k: Int) = tokens.getOrNull(k)?.let { clean(it) } ?: ""
        while (i < tokens.size) {
            val w = word(i)
            if (w.isEmpty()) { i++; continue }           // whitespace
            when {
                ONES.containsKey(w) && !(w == "oh" && !any) -> { current += ONES.getValue(w); any = true; lastIdx = i }
                TENS.containsKey(w) -> { current += TENS.getValue(w); any = true; lastIdx = i }
                SCALES.containsKey(w) && any -> {
                    val s = SCALES.getValue(w)
                    if (s == 100L) current = (if (current == 0L) 1 else current) * 100 else { total += (if (current == 0L) 1 else current) * s; current = 0 }
                    lastIdx = i
                }
                w == "and" && any && (ONES.containsKey(word(i + 2)) || TENS.containsKey(word(i + 2))) -> {}
                else -> break
            }
            i++
        }
        if (!any) return 0.0 to 0
        var value = (total + current).toDouble()
        var end = lastIdx + 1
        // decimals: "point two five"
        if (word(end + 1) == "point") {
            var k = end + 3
            var frac = ""
            while (ONES.containsKey(word(k)) && ONES.getValue(word(k)) < 10) { frac += ONES.getValue(word(k)); end = k + 1; k += 2 }
            if (frac.isNotEmpty()) value += "0.$frac".toDouble()
        }
        // fractions: "two thirds"
        val f = word(end + 1)
        if (FRACTIONS.containsKey(f) && value in 1.0..20.0) {
            value /= FRACTIONS.getValue(f)
            end += 2
        }
        return value to (end - start)
    }

    private fun fmtNum(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else {
        val s = "%.10f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
        // keep exact thirds etc. readable as fractions
        val third = listOf(3, 6, 7, 9).firstOrNull { d -> kotlin.math.abs(v * d - Math.round(v * d)) < 1e-9 }
        if (third != null && s.length > 6) "(${Math.round(v * third)}/$third)" else s
    }

    private val PHRASES = listOf(
        "is equal to" to " = ", "is the same as" to " = ", "equals" to " = ", "equal to" to " = ", "equal" to " = ", "makes" to " = ",
        "to the power of" to " ^ ", "raised to the power of" to " ^ ", "raised to" to " ^ ", "to the" to " ^ ",
        "squared" to " ^2 ", "cubed" to " ^3 ", "square root of" to " sqrt ", "the square root of" to " sqrt ", "root of" to " sqrt ",
        "cube root of" to " cbrt ", "sine of" to " sin ", "cosine of" to " cos ", "tangent of" to " tan ", "log of" to " log ",
        "natural log of" to " ln ", "plus" to " + ", "add" to " + ", "minus" to " - ", "take away" to " - ", "subtract" to " - ",
        "multiplied by" to " * ", "times" to " * ", "x times" to " * ", "divided by" to " / ", "over" to " / ", "into" to " / ",
        "open parenthesis" to " ( ", "open bracket" to " ( ", "close parenthesis" to " ) ", "close bracket" to " ) ",
        "negative" to " - ", "pi" to " pi ", "degrees" to " ", "degree" to " ",
        "ex" to " x ", "why" to " y ", "zed" to " z ", "zee" to " z "
    ).sortedByDescending { it.first.length }

    private val LEADS = listOf("what is", "what's", "whats", "calculate", "compute", "work out", "evaluate", "solve for", "solve", "find", "how much is", "simplify")

    fun interpret(input: String): Result {
        val original = input.trim()
        var t = " " + original.lowercase().replace("’", "'") + " "
        t = t.replace(Regex("[?!]"), " ")
        var lead = false
        for (l in LEADS) if (t.trimStart().startsWith("$l ")) { t = " " + t.trimStart().removePrefix(l); lead = true; break }
        t = numbersToDigits(t)
        // "15 percent of 80" → "(15/100)*80"
        t = t.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s+of\\s+"), " ($1/100) * ")
        t = t.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)"), " ($1/100) ")
        for ((p, r) in PHRASES) t = t.replace(Regex("(?<![a-z])" + Regex.escape(p) + "(?![a-z])"), r)
        t = t.replace(Regex("\\s+of\\s+"), " * ")   // "half of 10", after phrases like "square root of"
        t = t.replace(Regex("^\\s*the\\s+"), " ")
        val math = t.replace(Regex("\\s+"), " ").trim().replace(" ^ ", "^").replace("^ ", "^").replace(" ^", "^")
        val node = Expr.tryParse(math)
        // Word problems have many ordinary words left over.
        val leftover = Regex("[a-z]{2,}").findAll(math).map { it.value }.filter { it !in setOf("sqrt", "cbrt", "sin", "cos", "tan", "log", "ln", "pi") }.count()
        if (node != null && leftover == 0) {
            return Result(if (node is Node.Eq) Kind.EQUATION else Kind.EXPRESSION, math, original)
        }
        return Result(Kind.WORD_PROBLEM, original, original)
    }
}
