package app.sketchsolve.word

import app.sketchsolve.data.FVar
import app.sketchsolve.data.Formula
import app.sketchsolve.math.Expr
import app.sketchsolve.math.Node

/**
 * Formula packs: plain-text files in a folder the user picks. Anyone can
 * write one in a notes app. Example (save as optics.txt):
 *
 *   # Optics
 *   formula: Thin lens equation
 *   equation: 1/f = 1/d_o + 1/d_i
 *   f: focal length (cm)
 *   d_o: distance from the object to the lens (cm)
 *   d_i: distance from the lens to the image (cm)
 *   keywords: lens, focal, image, magnifying glass
 *   note: A negative d_i means the image is on the same side as the object.
 *   example: A magnifying glass has a focal length of 10 cm. Where is the image of a stamp 6 cm away?
 *   ---
 *   formula: Magnification
 *   ...
 *
 * Each pack adds its formulas to the Formulas tab, and the language model
 * is shown the most relevant ones when it reads a word problem.
 */
data class LibraryEntry(val formula: Formula, val keywords: List<String>, val examples: List<String>, val pack: String, val file: String)

data class PackProblem(val file: String, val line: Int, val message: String)

object Library {
    private val RESERVED = setOf("formula", "name", "equation", "eq", "keywords", "keyword", "note", "notes", "example", "examples", "category", "pack")

    fun parsePack(text: String, file: String, problems: MutableList<PackProblem> = ArrayList()): List<LibraryEntry> {
        val out = ArrayList<LibraryEntry>()
        var pack = file.substringBeforeLast('.')
        var name: String? = null; var eq: String? = null; var note: String? = null
        val vars = LinkedHashMap<String, Pair<String, String>>()
        val keywords = ArrayList<String>(); val examples = ArrayList<String>()
        var startLine = 1
        fun flush(lineNo: Int) {
            if (name == null && eq == null && vars.isEmpty()) return
            val e = eq
            when {
                e == null -> problems += PackProblem(file, startLine, "“${name ?: "formula"}” has no equation: line.")
                Expr.tryParse(e, words = true) !is Node.Eq -> problems += PackProblem(file, startLine, "Can't read the equation “$e”.")
                else -> {
                    val ast = Expr.parse(e, words = true)
                    val vs = Expr.vars(ast)
                    for (v in vs) if (v !in vars) vars[v] = v to ""
                    val unknown = vars.keys - vs
                    if (unknown.isNotEmpty()) problems += PackProblem(file, startLine, "${unknown.joinToString()} described but not used in “$e”.")
                    val id = "lib:$file:${out.size}"
                    out += LibraryEntry(Formula(id, "lib", name ?: e, e, vs.map { FVar(it, vars[it]!!.first, vars[it]!!.second) }, note),
                        keywords.toList(), examples.toList(), pack, file)
                }
            }
            name = null; eq = null; note = null; vars.clear(); keywords.clear(); examples.clear(); startLine = lineNo + 1
        }
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("---") -> flush(i + 1)
                line.startsWith("#") -> if (name == null && eq == null) pack = line.trimStart('#').trim().ifEmpty { pack }
                ':' !in line -> problems += PackProblem(file, i + 1, "Expected “key: value”.")
                else -> {
                    val key = line.substringBefore(':').trim()
                    val value = line.substringAfter(':').trim()
                    when (key.lowercase()) {
                        "formula", "name" -> { if (name != null || eq != null) flush(i); name = value }
                        "equation", "eq" -> eq = value
                        "note", "notes" -> note = value
                        "keywords", "keyword" -> keywords += value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        "example", "examples" -> examples += value
                        "pack", "category" -> pack = value
                        else -> if (Regex("^[A-Za-z][A-Za-z0-9_]*$").matches(key) && key.lowercase() !in RESERVED) {
                            val m = Regex("^(.*?)\\s*\\(([^)]*)\\)\\s*$").matchEntire(value)
                            vars[key] = if (m != null) m.groupValues[1] to m.groupValues[2] else value to ""
                        } else problems += PackProblem(file, i + 1, "Unknown key “$key”.")
                    }
                }
            }
        }
        flush(text.lines().size)
        return out
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    /** The entries most related to a problem (simple keyword overlap, works offline). */
    fun relevant(problem: String, entries: List<LibraryEntry>, max: Int = 3): List<LibraryEntry> {
        val pw = words(problem)
        return entries.map { e ->
            val text = e.formula.name + " " + e.formula.vars.joinToString(" ") { it.desc + " " + it.unit } + " " + e.examples.joinToString(" ")
            val kw = e.keywords.count { k -> problem.lowercase().contains(k) } * 3
            e to (kw + (words(text) intersect pw).size)
        }.filter { it.second >= 2 }.sortedByDescending { it.second }.take(max).map { it.first }
    }
}
