package app.sketchsolve.word

/**
 * The instructions given to the on-device language model. It only SETS UP
 * the problem as JSON; the app checks the setup and does the maths itself.
 */
object Prompt {
    const val VERSION = 1

    private const val RULES = """You turn maths word problems into equations. You do NOT solve them.
Reply with ONE JSON object and nothing else, in this form:
{
 "kind": "motion | percent | interest | mixture | work | age | numbers | geometry | money | rate | circuit | general",
 "givens": [ {"name": "...", "value": number, "unit": "...", "meaning": "...", "quote": "exact words from the problem"} ],
 "unknowns": [ {"name": "...", "unit": "...", "meaning": "..."} ],
 "equations": [ "..." ],
 "ask": "name of the unknown the question asks for",
 "answer_unit": "...",
 "diagram": { ... }
}
Rules:
- Names are short letters or words with underscores, like t, d_A, price, rate_B. Never use spaces.
- Every number you use must come from the problem, with "quote" showing the exact words. Well-known constants (g = 9.81) get "constant": true.
- Put each quantity in the unit the problem uses. If the problem mixes units (minutes and hours), add a given for the converted value and say so in "meaning".
- Write equations with * / + - ^ ( ) and =, one fact per equation, using the names. Use as many unknowns as you need.
- Percentages: write 15% as 15 with unit "%", and use name/100 in equations.
- If the problem doesn't give enough information, still write what it does give; never invent a number.
- "diagram" is optional. Use one of:
  {"type":"motion","movers":[{"label":"Train A","speed":"v_A","direction":"right","from":"left"}],"gap":"d"}
  {"type":"table","headers":["","rate","time","work"],"rows":[["Ann","1/6","t","t/6"]]}
  {"type":"bar","parts":[{"label":"discount","value":"15%"},{"label":"price paid","value":"p"}],"total":"80"}
  {"type":"numberline","points":[{"label":"x","value":"x"},{"label":"x+1","value":"x+1"}]}
  {"type":"shape","shape":"rectangle | triangle | rightTriangle | circle | box | cylinder","labels":{"width":"w","height":"2w"}}"""

    private const val EXAMPLES = """Example problem: Two trains leave stations 300 miles apart at the same time and travel toward each other. One goes 70 mph and the other 50 mph. How long until they meet?
Example JSON: {"kind":"motion","givens":[{"name":"d","value":300,"unit":"mi","meaning":"distance between the stations","quote":"300 miles apart"},{"name":"v_A","value":70,"unit":"mi/h","meaning":"speed of the first train","quote":"70 mph"},{"name":"v_B","value":50,"unit":"mi/h","meaning":"speed of the second train","quote":"50 mph"}],"unknowns":[{"name":"t","unit":"h","meaning":"hours until the trains meet"}],"equations":["v_A*t + v_B*t = d"],"ask":"t","answer_unit":"h","diagram":{"type":"motion","movers":[{"label":"Train 1","speed":"v_A","direction":"right","from":"left"},{"label":"Train 2","speed":"v_B","direction":"left","from":"right"}],"gap":"d"}}

Example problem: A jacket costs $80 and is on sale for 15% off. What is the sale price?
Example JSON: {"kind":"percent","givens":[{"name":"price","value":80,"unit":"$","meaning":"original price","quote":"$80"},{"name":"off","value":15,"unit":"%","meaning":"discount","quote":"15% off"}],"unknowns":[{"name":"sale","unit":"$","meaning":"sale price"}],"equations":["sale = price*(1 - off/100)"],"ask":"sale","answer_unit":"$","diagram":{"type":"bar","parts":[{"label":"15% off","value":"price*off/100"},{"label":"sale price","value":"sale"}],"total":"$80"}}

Example problem: Ann can paint a fence in 6 hours and Ben in 4 hours. How long does it take them working together?
Example JSON: {"kind":"work","givens":[{"name":"t_A","value":6,"unit":"h","meaning":"Ann's time alone","quote":"6 hours"},{"name":"t_B","value":4,"unit":"h","meaning":"Ben's time alone","quote":"4 hours"}],"unknowns":[{"name":"t","unit":"h","meaning":"time working together"}],"equations":["t/t_A + t/t_B = 1"],"ask":"t","answer_unit":"h","diagram":{"type":"table","headers":["","rate (fence/h)","time (h)","work done"],"rows":[["Ann","1/6","t","t/6"],["Ben","1/4","t","t/4"]]}}"""

    fun build(problem: String, library: List<LibraryEntry>): String {
        val sb = StringBuilder(RULES).append("\n\n").append(EXAMPLES).append("\n\n")
        if (library.isNotEmpty()) {
            sb.append("Formulas that may help (from the user's library):\n")
            for (e in library) {
                sb.append("- ${e.formula.name}: ${e.formula.eq}  where ")
                sb.append(e.formula.vars.joinToString("; ") { "${it.name} = ${it.desc}${if (it.unit.isNotEmpty()) " (${it.unit})" else ""}" })
                e.formula.note?.let { sb.append(". Note: $it") }
                sb.append('\n')
            }
            sb.append('\n')
        }
        sb.append("Problem: ").append(problem.trim()).append("\nJSON:")
        return sb.toString()
    }
}
