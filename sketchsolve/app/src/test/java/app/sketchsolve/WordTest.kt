package app.sketchsolve

import app.sketchsolve.llm.LanguageModel
import app.sketchsolve.llm.WordPipeline
import app.sketchsolve.math.Status
import app.sketchsolve.word.*
import org.junit.Assert.*
import org.junit.Test

class WordTest {
    @Test fun spokenMath() {
        fun t(s: String) = SpokenMath.interpret(s)
        assertEquals("x^2 + 3 = 11", t("x squared plus three equals eleven").text)
        assertEquals(SpokenMath.Kind.EQUATION, t("two x plus three equals eleven").kind)
        assertEquals("2 x + 3 = 11", t("two x plus three equals eleven").text)
        assertEquals("(15/100) * 80", t("What is fifteen percent of eighty?").text)
        assertEquals(SpokenMath.Kind.EXPRESSION, t("what's 12 times 7").kind)
        assertEquals("sqrt 144", t("the square root of one hundred and forty four").text)
        assertEquals("3.25 * 4", t("three point two five times four").text)
        assertEquals(SpokenMath.Kind.WORD_PROBLEM, t("A train leaves the station at 60 miles per hour. How far does it go in 3 hours?").kind)
        assertEquals("1250", SpokenMath.numbersToDigits("one thousand two hundred and fifty"))
    }

    @Test fun units() {
        assertEquals(Units.parse("mi")!!.dim * Units.parse("h")!!.dim.pow(-1), Units.parse("miles per hour")!!.dim)
        assertEquals(Units.parse("mph")!!.dim, Units.parse("km/h")!!.dim)
        assertNotNull(Units.parse("$ per pound"))
        assertNotNull(Units.parse("square feet"))
        assertEquals(Units.parse("ohms")!!.dim * Units.parse("A")!!.dim, Units.parse("V")!!.dim)
    }

    private val trains = "Two trains leave stations 300 miles apart at the same time and head toward each other. One travels at 70 mph and the other at 50 mph. How many hours until they meet?"
    private val goodJson = """Sure! {"kind":"motion","givens":[{"name":"d","value":300,"unit":"mi","meaning":"distance apart","quote":"300 miles apart"},{"name":"v_A","value":70,"unit":"mi/h","meaning":"speed 1","quote":"70 mph"},{"name":"v_B","value":50,"unit":"mi/h","meaning":"speed 2","quote":"50 mph"}],"unknowns":[{"name":"t","unit":"h","meaning":"hours until they meet"}],"equations":["v_A*t + v_B*t = d"],"ask":"t","answer_unit":"h","diagram":{"type":"motion","movers":[{"label":"Train 1","speed":"v_A","direction":"right","from":"left"},{"label":"Train 2","speed":"v_B","direction":"left","from":"right"}],"gap":"d"}}"""

    @Test fun goodSetupSolves() {
        val s = Setup.parse(goodJson)!!
        assertTrue(s.diagram is Diagram.Motion)
        val a = WordSolver.check(trains, s)
        assertEquals(Status.SOLVED, a.result!!.status)
        assertEquals(2.5, a.result!!.value, 1e-9)
        assertEquals(Confidence.HIGH, a.confidence)
    }

    @Test fun inventedNumberIsCaught() {
        val bad = goodJson.replace("\"value\":50", "\"value\":55")
        val a = WordSolver.check(trains, Setup.parse(bad)!!)
        assertFalse(a.ok)
        assertTrue(a.issues.any { it.message.contains("doesn't appear in the problem") })
    }

    @Test fun unitMismatchIsCaught() {
        val bad = goodJson.replace("v_A*t + v_B*t = d", "v_A + v_B*t = d")
        val a = WordSolver.check(trains, Setup.parse(bad)!!)
        assertFalse(a.ok)
        assertTrue(a.issues.joinToString { it.message }, a.issues.any { it.message.startsWith("Units don't match") })
    }

    @Test fun mixedUnitsWarn() {
        val p = "Sam walks for 1 hour and 30 minutes at 4 miles per hour. How far does he walk?"
        val j = """{"givens":[{"name":"h","value":1,"unit":"h","meaning":"hours","quote":"1 hour"},{"name":"m","value":30,"unit":"min","meaning":"minutes","quote":"30 minutes"},{"name":"v","value":4,"unit":"mi/h","meaning":"speed","quote":"4 miles per hour"}],"unknowns":[{"name":"d","unit":"mi","meaning":"distance"}],"equations":["d = v*(h + m)"],"ask":"d"}"""
        val a = WordSolver.check(p, Setup.parse(j)!!)
        assertTrue(a.issues.joinToString { it.message }, a.issues.any { it.message.contains("need converting") })
        assertEquals(Confidence.MEDIUM, a.confidence)
    }

    @Test fun conversionsAndReciprocals() {
        val p = "Sam walks for 1 hour and 30 minutes at 4 miles per hour. How many miles does he walk?"
        val j = """{"givens":[{"name":"t","value":1.5,"unit":"h","meaning":"time","quote":"1 hour and 30 minutes"},{"name":"v","value":4,"unit":"mi/h","quote":"4 miles per hour"}],"unknowns":[{"name":"d","unit":"mi"}],"equations":["d = v*t"],"ask":"d"}"""
        val a = WordSolver.check(p, Setup.parse(j)!!)
        assertTrue(a.issues.toString(), a.ok); assertEquals(6.0, a.result!!.value, 1e-9)
        val p2 = "A car uses 8 gallons of gas to go 240 miles. How many gallons does it need to go 390 miles?"
        val j2 = """{"givens":[{"name":"g","value":8,"unit":"gallons","quote":"8 gallons"},{"name":"d","value":240,"unit":"miles","quote":"240 miles"},{"name":"d2","value":390,"unit":"miles","quote":"390 miles"}],"unknowns":[{"name":"g2","unit":"gallons"}],"equations":["g/d = 1/g2 * d2"],"ask":"g2"}"""
        assertTrue(WordSolver.check(p2, Setup.parse(j2)!!).issues.any { it.message.startsWith("Units don't match") })
    }

    @Test fun unusedNumberWarns() {
        val p = "A jacket costs $80 and is 15% off. Sales tax is 8%. What is the sale price before tax?"
        val j = """{"givens":[{"name":"price","value":80,"unit":"$","quote":"$80"},{"name":"off","value":15,"unit":"%","quote":"15% off"}],"unknowns":[{"name":"sale","unit":"$"}],"equations":["sale = price*(1 - off/100)"],"ask":"sale"}"""
        val a = WordSolver.check(p, Setup.parse(j)!!)
        assertEquals(68.0, a.result!!.value, 1e-9)
        assertTrue(a.issues.any { it.message.contains("The 8") })
    }

    @Test fun tautologyAndGivenAnswerAreCaught() {
        val p = "After a 20% discount a lamp costs $36. What was the original price in dollars?"
        val j = """{"givens":[{"name":"price","value":36,"unit":"$","quote":"costs $36"},{"name":"off","value":20,"unit":"%","quote":"20% discount"}],"unknowns":[],"equations":["price = price*(1 - off/100)/(1 - off/100)"],"ask":"price"}"""
        val a = WordSolver.check(p, Setup.parse(j)!!)
        assertFalse(a.ok)
        assertTrue(a.issues.any { it.message.contains("already known") })
        assertTrue(a.issues.any { it.message.contains("true for any value") })
    }

    @Test fun notEnoughInformation() {
        val p = "A rectangle has a perimeter of 30 cm. What is its area?"
        val j = """{"givens":[{"name":"P","value":30,"unit":"cm","quote":"perimeter of 30 cm"}],"unknowns":[{"name":"w","unit":"cm"},{"name":"l","unit":"cm"},{"name":"A","unit":"cm^2"}],"equations":["P = 2*l + 2*w","A = l*w"],"ask":"A"}"""
        val a = WordSolver.check(p, Setup.parse(j)!!)
        assertEquals(Status.UNDERDETERMINED, a.result!!.status)
        assertFalse(a.ok)
    }

    @Test fun consensusFlagsDisagreement() {
        val wrong = goodJson.replace("v_A*t + v_B*t = d", "v_A*t - v_B*t = d")
        val a = WordSolver.consensus(trains, listOf(Setup.parse(goodJson)!!, Setup.parse(wrong)!!))!!
        assertEquals(Confidence.LOW, a.confidence)
        assertEquals(1, a.alternatives.size)
    }

    @Test fun pipelineStopsEarlyWhenReadingsAgree() {
        var calls = 0
        val model = object : LanguageModel {
            override val name = "fake"
            override fun generate(prompt: String, temperature: Float, seed: Int, maxTokens: Int): String { calls++; assertTrue(prompt.contains("Problem: Two trains")); return goodJson }
        }
        val a = WordPipeline(model) { emptyList() }.solve(trains)!!
        assertEquals(2.5, a.result!!.value, 1e-9)
        assertEquals(2, calls)
    }

    @Test fun libraryPacks() {
        val pack = """
            # Optics
            formula: Thin lens equation
            equation: 1/f = 1/d_o + 1/d_i
            f: focal length (cm)
            d_o: distance from the object to the lens (cm)
            d_i: distance from the lens to the image (cm)
            keywords: lens, focal, magnifying
            example: A magnifying glass has a focal length of 10 cm.
            ---
            formula: Magnification
            equation: m = -d_i/d_o
            m: magnification
        """.trimIndent()
        val problems = ArrayList<PackProblem>()
        val entries = Library.parsePack(pack, "optics.txt", problems)
        assertEquals(problems.toString(), 2, entries.size)
        assertEquals("Optics", entries[0].pack)
        assertEquals("cm", entries[0].formula.vars.first { it.name == "f" }.unit)
        val rel = Library.relevant("A lens has a focal length of 12 cm. An object is 20 cm away. Where is the image?", entries)
        assertEquals("Thin lens equation", rel.first().formula.name)
        assertTrue(Prompt.build("A lens…", rel).contains("1/f = 1/d_o + 1/d_i"))
        val bad = ArrayList<PackProblem>()
        Library.parsePack("formula: Broken\nequation: x = = 2", "bad.txt", bad)
        assertEquals(1, bad.size)
    }
}
