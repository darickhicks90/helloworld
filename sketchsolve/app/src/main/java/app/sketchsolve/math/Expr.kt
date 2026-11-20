package app.sketchsolve.math

import kotlin.math.*

/**
 * Maths expressions: parsing, printing, evaluating and rearranging.
 *
 * Two reading styles:
 *  - handwriting style (words = false): "2xy" is 2·x·y, "R_1" is one name.
 *  - formula style (words = true): "Area", "V_out" are whole names.
 */
sealed class Node {
    data class Num(val v: Double) : Node()
    data class Var(val name: String) : Node()
    data class Neg(val a: Node) : Node()
    data class Op(val op: Char, val a: Node, val b: Node) : Node()
    data class Fn(val name: String, val a: Node) : Node()
    data class Eq(val lhs: Node, val rhs: Node) : Node()
}

class MathSyntaxError(msg: String) : Exception(msg)

typealias CF = (DoubleArray) -> Double

object Expr {
    val FUNCS = listOf("asinh", "acosh", "atanh", "asin", "acos", "atan", "sinh", "cosh", "tanh",
        "sin", "cos", "tan", "sqrt", "cbrt", "ln", "log", "abs", "exp")
    private val ALIASES = mapOf("arcsin" to "asin", "arccos" to "acos", "arctan" to "atan")
    val GREEK = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "Delta" to "Δ", "epsilon" to "ε",
        "eta" to "η", "theta" to "θ", "lambda" to "λ", "mu" to "μ", "rho" to "ρ", "sigma" to "σ",
        "tau" to "τ", "phi" to "φ", "omega" to "ω", "Omega" to "Ω"
    )
    val CONSTANTS = mapOf("pi" to PI, "e" to E)
    private val NAMES = (FUNCS + ALIASES.keys + GREEK.keys + listOf("pi")).sortedByDescending { it.length }

    fun isConst(name: String) = CONSTANTS.containsKey(name)

    /* ---------------- tokenizer ---------------- */
    private sealed class Tok {
        data class N(val v: Double) : Tok()
        data class Id(val v: String) : Tok()
        data class F(val v: String) : Tok()
        data class O(val v: Char) : Tok()
    }

    private fun normalise(src: String): String = src
        .replace(Regex("[×✕✖]"), "*").replace(Regex("[·⋅∙•]"), "*").replace(Regex("[÷∕]"), "/")
        .replace(Regex("[−–—]"), "-").replace("²", "^2").replace("³", "^3")
        .replace("√", " sqrt ").replace("π", " pi ").replace(Regex("[\\[{]"), "(").replace(Regex("[\\]}]"), ")")

    private fun isLetter(c: Char) = c.isLetter() || c == '_'
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun tokenize(src: String, words: Boolean): List<Tok> {
        val s = normalise(src)
        val toks = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    var j = i
                    var dot = false
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) {
                        if (s[j] == '.') { if (dot) break; dot = true }
                        j++
                    }
                    val txt = s.substring(i, j)
                    if (txt == ".") throw MathSyntaxError("There is a dot on its own")
                    toks.add(Tok.N(txt.toDouble()))
                    i = j
                }
                isLetter(c) -> {
                    var j = i
                    while (j < s.length && isWordChar(s[j])) j++
                    val run = s.substring(i, j)
                    if (words) pushWord(run, toks) else splitRun(run, toks)
                    i = j
                }
                c in "+-*/^=(),!|" -> { toks.add(Tok.O(c)); i++ }
                else -> throw MathSyntaxError("I don't know the symbol \"$c\"")
            }
        }
        return toks
    }

    private fun pushName(nm: String, toks: MutableList<Tok>) {
        when {
            nm in FUNCS -> toks.add(Tok.F(nm))
            ALIASES.containsKey(nm) -> toks.add(Tok.F(ALIASES.getValue(nm)))
            else -> toks.add(Tok.Id(nm))
        }
    }

    private fun pushWord(run: String, toks: MutableList<Tok>) {
        if (run[0].isDigit()) {
            var k = 0
            while (k < run.length && (run[k].isDigit() || run[k] == '.')) k++
            toks.add(Tok.N(run.substring(0, k).toDouble()))
            if (k < run.length) pushWord(run.substring(k), toks)
            return
        }
        pushName(run, toks)
    }

    private fun splitRun(run: String, toks: MutableList<Tok>) {
        var k = 0
        while (k < run.length) {
            val ch = run[k]
            if (ch.isDigit()) {
                var j = k
                while (j < run.length && (run[j].isDigit() || run[j] == '.')) j++
                toks.add(Tok.N(run.substring(k, j).toDouble()))
                k = j; continue
            }
            if (ch == '_') { k++; continue }
            val matched = NAMES.firstOrNull { run.startsWith(it, k) }
            if (matched != null) { pushName(matched, toks); k += matched.length; continue }
            var name = ch.toString()
            var j = k + 1
            if (j < run.length && run[j] == '_') {
                var q = j + 1
                while (q < run.length && run[q].isLetterOrDigit()) q++
                if (q > j + 1) { name = run.substring(k, q); j = q }
            }
            toks.add(Tok.Id(name))
            k = j
        }
    }

    /* ---------------- parser ---------------- */
    fun parse(src: String, words: Boolean = false): Node {
        val toks = tokenize(src, words)
        if (toks.isEmpty()) throw MathSyntaxError("There is nothing to read")
        var p = 0
        fun peek(): Tok? = toks.getOrNull(p)
        fun next(): Tok? = toks.getOrNull(p++)
        fun isOp(t: Tok?, c: Char) = t is Tok.O && t.v == c
        fun expect(c: Char) { if (!isOp(next(), c)) throw MathSyntaxError("A \"$c\" seems to be missing") }
        fun startsPrimary(t: Tok?) = t is Tok.N || t is Tok.Id || t is Tok.F || isOp(t, '(')

        lateinit var parseExpr: () -> Node
        lateinit var parseUnary: () -> Node
        lateinit var parsePower: () -> Node

        fun parsePrimary(): Node {
            val t = next() ?: throw MathSyntaxError("The line stops too early")
            return when {
                t is Tok.N -> Node.Num(t.v)
                t is Tok.Id -> Node.Var(t.v)
                t is Tok.F -> {
                    val arg = if (isOp(peek(), '(')) { next(); val e = parseExpr(); expect(')'); e } else parsePower()
                    Node.Fn(t.v, arg)
                }
                isOp(t, '(') -> { val e = parseExpr(); expect(')'); e }
                isOp(t, '|') -> { val e = parseExpr(); expect('|'); Node.Fn("abs", e) }
                isOp(t, '=') -> throw MathSyntaxError("Something is missing before \"=\"")
                else -> throw MathSyntaxError("I didn't expect \"${(t as Tok.O).v}\" there")
            }
        }

        fun parsePostfix(): Node {
            var e = parsePrimary()
            while (isOp(peek(), '!')) { next(); e = Node.Fn("fact", e) }
            return e
        }
        parsePower = {
            val base = parsePostfix()
            if (isOp(peek(), '^')) { next(); Node.Op('^', base, parseUnary()) } else base
        }
        parseUnary = {
            when {
                isOp(peek(), '-') -> { next(); Node.Neg(parseUnary()) }
                isOp(peek(), '+') -> { next(); parseUnary() }
                else -> parsePower()
            }
        }
        fun parseTerm(): Node {
            var left = parseUnary()
            while (true) {
                val t = peek()
                left = when {
                    isOp(t, '*') || isOp(t, '/') -> { next(); Node.Op((t as Tok.O).v, left, parseUnary()) }
                    startsPrimary(t) -> Node.Op('*', left, parsePower())
                    else -> break
                }
            }
            return left
        }
        parseExpr = {
            var left = parseTerm()
            while (isOp(peek(), '+') || isOp(peek(), '-')) {
                val o = (next() as Tok.O).v
                left = Node.Op(o, left, parseTerm())
            }
            left
        }

        val lhs = parseExpr()
        var node: Node = lhs
        if (isOp(peek(), '=')) {
            next()
            if (peek() == null) throw MathSyntaxError("Something is missing after \"=\"")
            node = Node.Eq(lhs, parseExpr())
        }
        if (p < toks.size) {
            val t = toks[p]
            if (isOp(t, '=')) throw MathSyntaxError("Only one \"=\" per line, please")
            val shown = when (t) { is Tok.O -> t.v.toString(); is Tok.N -> fmt(t.v); is Tok.Id -> t.v; is Tok.F -> t.v }
            throw MathSyntaxError("I didn't expect \"$shown\" there")
        }
        return node
    }

    fun tryParse(src: String, words: Boolean = false): Node? = try { parse(src, words) } catch (e: MathSyntaxError) { null }

    /* ---------------- helpers ---------------- */
    fun vars(n: Node, out: MutableSet<String> = LinkedHashSet()): MutableSet<String> {
        when (n) {
            is Node.Var -> if (!isConst(n.name)) out.add(n.name)
            is Node.Eq -> { vars(n.lhs, out); vars(n.rhs, out) }
            is Node.Neg -> vars(n.a, out)
            is Node.Op -> { vars(n.a, out); vars(n.b, out) }
            is Node.Fn -> vars(n.a, out)
            is Node.Num -> {}
        }
        return out
    }

    fun count(n: Node, name: String): Int = when (n) {
        is Node.Var -> if (n.name == name) 1 else 0
        is Node.Eq -> count(n.lhs, name) + count(n.rhs, name)
        is Node.Neg -> count(n.a, name)
        is Node.Op -> count(n.a, name) + count(n.b, name)
        is Node.Fn -> count(n.a, name)
        is Node.Num -> 0
    }

    /* ---------------- evaluation ---------------- */
    private const val D2R = PI / 180

    private fun fact(x: Double): Double {
        if (x < 0 || x != floor(x) || x > 170) return Double.NaN
        var r = 1.0
        var i = 2
        while (i <= x) { r *= i; i++ }
        return r
    }

    fun powReal(x: Double, y: Double): Double {
        val r = x.pow(y)
        if (!r.isNaN() || x >= 0) return r
        val inv = 1 / y
        if (abs(inv - inv.roundToLong()) < 1e-9 && inv.roundToLong() % 2L != 0L) return -(-x).pow(y)
        return r
    }

    private fun fnImpl(name: String, deg: Boolean): (Double) -> Double {
        val k = if (deg) D2R else 1.0
        return when (name) {
            "sin" -> { x -> sin(x * k) }
            "cos" -> { x -> cos(x * k) }
            "tan" -> { x -> tan(x * k) }
            "asin" -> { x -> asin(x) / k }
            "acos" -> { x -> acos(x) / k }
            "atan" -> { x -> atan(x) / k }
            "sinh" -> { x -> sinh(x) }
            "cosh" -> { x -> cosh(x) }
            "tanh" -> { x -> tanh(x) }
            "asinh" -> { x -> asinh(x) }
            "acosh" -> { x -> acosh(x) }
            "atanh" -> { x -> atanh(x) }
            "sqrt" -> { x -> sqrt(x) }
            "cbrt" -> { x -> Math.cbrt(x) }
            "ln" -> { x -> ln(x) }
            "log" -> { x -> log10(x) }
            "abs" -> { x -> abs(x) }
            "exp" -> { x -> exp(x) }
            "fact" -> { x -> fact(x) }
            else -> { _ -> Double.NaN }
        }
    }

    /** Compile to a closure reading variables from an array via [idx]. */
    fun compile(n: Node, idx: Map<String, Int>, deg: Boolean): CF = when (n) {
        is Node.Num -> { val v = n.v; { _ -> v } }
        is Node.Var -> {
            val i = idx[n.name]
            if (i != null) { env -> env[i] } else { val c = CONSTANTS[n.name] ?: Double.NaN; { _ -> c } }
        }
        is Node.Neg -> { val a = compile(n.a, idx, deg); { env -> -a(env) } }
        is Node.Fn -> { val a = compile(n.a, idx, deg); val f = fnImpl(n.name, deg); { env -> f(a(env)) } }
        is Node.Op -> {
            val a = compile(n.a, idx, deg)
            val b = compile(n.b, idx, deg)
            when (n.op) {
                '+' -> { env -> a(env) + b(env) }
                '-' -> { env -> a(env) - b(env) }
                '*' -> { env -> a(env) * b(env) }
                '/' -> { env -> a(env) / b(env) }
                else -> { env -> powReal(a(env), b(env)) }
            }
        }
        is Node.Eq -> throw IllegalArgumentException("compile an expression, not an equation")
    }

    /** "Size" of an expression: sum of |top-level terms|; judges when a difference is zero. */
    fun compileMag(n: Node, idx: Map<String, Int>, deg: Boolean): CF {
        if (n is Node.Op && (n.op == '+' || n.op == '-')) {
            val a = compileMag(n.a, idx, deg)
            val b = compileMag(n.b, idx, deg)
            return { env -> a(env) + b(env) }
        }
        if (n is Node.Neg) return compileMag(n.a, idx, deg)
        val f = compile(n, idx, deg)
        return { env -> abs(f(env)) }
    }

    fun evaluate(n: Node, env: Map<String, Double> = emptyMap(), deg: Boolean = true): Double {
        val names = env.keys.toList()
        val idx = names.withIndex().associate { it.value to it.index }
        val arr = DoubleArray(names.size) { env.getValue(names[it]) }
        return compile(n, idx, deg)(arr)
    }

    /* ---------------- printing ---------------- */
    private val SUP = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶',
        '7' to '⁷', '8' to '⁸', '9' to '⁹', '-' to '⁻', '−' to '⁻', 'n' to 'ⁿ', 'x' to 'ˣ', 'y' to 'ʸ', '.' to '·')
    private val SUB = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆',
        '7' to '₇', '8' to '₈', '9' to '₉', 'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'h' to 'ₕ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ', 'i' to 'ᵢ', 'r' to 'ᵣ',
        'u' to 'ᵤ', 'v' to 'ᵥ')

    fun fmt(v: Double, sig: Int = 6): String {
        if (v.isNaN()) return "undefined"
        if (v.isInfinite()) return if (v > 0) "∞" else "−∞"
        if (v == 0.0) return "0"
        val av = abs(v)
        val r = v.roundToLong().toDouble()
        if (abs(v - r) <= 1e-9 * max(1.0, av) && av < 1e15) return (if (r < 0) "−" else "") + abs(r).toLong().toString()
        if (av >= 1e9 || av < 1e-4) {
            val e = floor(log10(av)).toInt()
            var m = v / 10.0.pow(e)
            var ee = e
            if (abs(m) >= 10 - 1e-12) { m /= 10; ee += 1 }
            val mant = trimZeros(String.format(java.util.Locale.US, "%.${sig - 1}f", m)).replace("-", "−")
            return "$mant×10" + ee.toString().map { SUP[it] ?: it }.joinToString("")
        }
        val digits = max(0, sig - 1 - floor(log10(av)).toInt())
        return trimZeros(String.format(java.util.Locale.US, "%.${digits}f", v)).replace("-", "−")
    }

    private fun trimZeros(s: String): String = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s

    fun nameOut(name: String): String {
        if (name == "pi") return "π"
        if (name == "__Q") return "circled part"
        GREEK[name]?.let { return it }
        val m = Regex("^([^_]+)_(.+)$").matchEntire(name)
        if (m != null) {
            val base = GREEK[m.groupValues[1]] ?: m.groupValues[1]
            val sub = m.groupValues[2]
            return if (sub.all { SUB.containsKey(it) }) base + sub.map { SUB.getValue(it) }.joinToString("") else "$base($sub)"
        }
        return name
    }

    private fun prec(n: Node): Int = when (n) {
        is Node.Op -> when (n.op) { '+', '-' -> 1; '*', '/' -> 2; else -> 4 }
        is Node.Neg -> 3
        is Node.Num -> if (n.v < 0) 3 else 5
        else -> 5
    }

    fun show(n: Node): String {
        fun wrap(s: String) = "($s)"
        return when (n) {
            is Node.Eq -> "${show(n.lhs)} = ${show(n.rhs)}"
            is Node.Num -> fmt(n.v)
            is Node.Var -> nameOut(n.name)
            is Node.Neg -> { val s = show(n.a); "−" + if (prec(n.a) <= 3) wrap(s) else s }
            is Node.Fn -> {
                val s = show(n.a)
                when (n.name) {
                    "abs" -> "|$s|"
                    "sqrt" -> "√" + if (prec(n.a) >= 5) s else wrap(s)
                    "fact" -> (if (prec(n.a) >= 5) s else wrap(s)) + "!"
                    else -> "${n.name}($s)"
                }
            }
            is Node.Op -> {
                val a = show(n.a)
                when (n.op) {
                    '+' -> {
                        val b = n.b
                        when {
                            b is Node.Neg -> "$a − " + if (prec(b.a) <= 1) wrap(show(b.a)) else show(b.a)
                            b is Node.Num && b.v < 0 -> "$a − ${fmt(-b.v)}"
                            else -> "$a + ${show(b)}"
                        }
                    }
                    '-' -> { val b = show(n.b); "$a − " + if (prec(n.b) <= 1 || prec(n.b) == 3) wrap(b) else b }
                    '*' -> {
                        val sa = if (prec(n.a) < 2) wrap(a) else a
                        val b = show(n.b)
                        val sb = if (prec(n.b) < 2 || prec(n.b) == 3) wrap(b) else b
                        val bb = n.b
                        val implicitOk = n.a is Node.Num && (n.a as Node.Num).v >= 0 && bb !is Node.Num &&
                            !(bb is Node.Op && (bb.op == '*' || bb.op == '/') && bb.a is Node.Num) && prec(bb) != 3
                        if (implicitOk) sa + sb else "$sa·$sb"
                    }
                    '/' -> {
                        val b = show(n.b)
                        val sa = if (prec(n.a) < 2 || prec(n.a) == 3) wrap(a) else a
                        val sb = if (prec(n.b) <= 3) wrap(b) else b
                        "$sa/$sb"
                    }
                    else -> {
                        val sa = if (prec(n.a) < 5) wrap(a) else a
                        val b = show(n.b)
                        if (b.all { SUP.containsKey(it) }) sa + b.map { SUP.getValue(it) }.joinToString("") else "$sa^($b)"
                    }
                }
            }
        }
    }

    /* ---------------- tidy & rearrange ---------------- */
    fun tidy(n: Node): Node = when (n) {
        is Node.Num, is Node.Var -> n
        is Node.Eq -> Node.Eq(tidy(n.lhs), tidy(n.rhs))
        is Node.Neg -> {
            val a = tidy(n.a)
            when (a) { is Node.Neg -> a.a; is Node.Num -> Node.Num(-a.v); else -> Node.Neg(a) }
        }
        is Node.Fn -> Node.Fn(n.name, tidy(n.a))
        is Node.Op -> {
            val a = tidy(n.a)
            val b = tidy(n.b)
            fun isN(x: Node, v: Double) = x is Node.Num && x.v == v
            when {
                n.op == '+' && isN(a, 0.0) -> b
                n.op == '+' && isN(b, 0.0) -> a
                n.op == '-' && isN(b, 0.0) -> a
                n.op == '-' && isN(a, 0.0) -> tidy(Node.Neg(b))
                n.op == '*' && isN(a, 1.0) -> b
                n.op == '*' && isN(b, 1.0) -> a
                n.op == '/' && isN(b, 1.0) -> a
                n.op == '^' && isN(b, 1.0) -> a
                else -> Node.Op(n.op, a, b)
            }
        }
    }

    data class Isolated(val target: String, val expr: Node, val plusMinus: Boolean, val periodic: Boolean)

    private val INVERSE = mapOf("sin" to "asin", "cos" to "acos", "tan" to "atan", "asin" to "sin", "acos" to "cos",
        "atan" to "tan", "sinh" to "asinh", "cosh" to "acosh", "tanh" to "atanh", "asinh" to "sinh",
        "acosh" to "cosh", "atanh" to "tanh", "ln" to "exp", "exp" to "ln")

    /** Get [target] on its own. Works when it appears exactly once. */
    fun isolate(eq: Node.Eq, target: String): Isolated? {
        var l = eq.lhs
        var r = eq.rhs
        val cl = count(l, target)
        val cr = count(r, target)
        if (cl + cr != 1) return null
        if (cr == 1) { val t = l; l = r; r = t }
        var pm = false
        var periodic = false
        repeat(200) {
            val L = l
            if (L is Node.Var && L.name == target) return Isolated(target, tidy(r), pm, periodic)
            when (L) {
                is Node.Neg -> { r = Node.Neg(r); l = L.a }
                is Node.Op -> {
                    val inA = count(L.a, target) > 0
                    val a = L.a
                    val b = L.b
                    when (L.op) {
                        '+' -> { r = Node.Op('-', r, if (inA) b else a); l = if (inA) a else b }
                        '-' -> if (inA) { r = Node.Op('+', r, b); l = a } else { r = Node.Op('-', a, r); l = b }
                        '*' -> { r = Node.Op('/', r, if (inA) b else a); l = if (inA) a else b }
                        '/' -> if (inA) { r = Node.Op('*', r, b); l = a } else { r = Node.Op('/', a, r); l = b }
                        '^' -> if (inA) {
                            r = when {
                                b is Node.Num && b.v == 2.0 -> Node.Fn("sqrt", r)
                                b is Node.Num && b.v == 3.0 -> Node.Fn("cbrt", r)
                                else -> Node.Op('^', r, Node.Op('/', Node.Num(1.0), b))
                            }
                            if (b is Node.Num && b.v == floor(b.v) && b.v.toLong() % 2L == 0L) pm = true
                            l = a
                        } else {
                            r = if (a is Node.Var && a.name == "e") Node.Fn("ln", r) else Node.Op('/', Node.Fn("ln", r), Node.Fn("ln", a))
                            l = b
                        }
                    }
                }
                is Node.Fn -> {
                    when {
                        L.name == "sqrt" -> r = Node.Op('^', r, Node.Num(2.0))
                        L.name == "cbrt" -> r = Node.Op('^', r, Node.Num(3.0))
                        L.name == "log" -> r = Node.Op('^', Node.Num(10.0), r)
                        L.name == "abs" -> pm = true
                        INVERSE.containsKey(L.name) -> {
                            r = Node.Fn(INVERSE.getValue(L.name), r)
                            if (L.name in listOf("sin", "cos", "tan")) periodic = true
                        }
                        else -> return null
                    }
                    l = L.a
                }
                else -> return null
            }
        }
        return null
    }
}
