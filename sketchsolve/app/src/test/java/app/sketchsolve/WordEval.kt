package app.sketchsolve

import app.sketchsolve.math.Status
import app.sketchsolve.word.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Word-problem evaluation.
 *  1. exportPrompts writes build/eval/prompts.jsonl (problem, prompt, schema).
 *  2. tools/run_eval.py runs Gemma on them and writes build/eval/outputs.jsonl.
 *  3. score reads the outputs, runs the app's checks and solver, and reports.
 */
class WordEval {
    private val dir = File("build/eval").apply { mkdirs() }
    private fun problems(): JSONArray = JSONArray(javaClass.classLoader!!.getResource("word_eval.json")!!.readText())

    @Test fun exportPrompts() {
        val arr = problems()
        File(dir, "prompts.jsonl").printWriter().use { w ->
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                w.println(JSONObject().put("id", p.getString("id")).put("prompt", Prompt.build(p.getString("problem"), emptyList())).put("schema", Prompt.SCHEMA))
            }
        }
    }

    @Test fun score() {
        val out = File(dir, "outputs.jsonl")
        if (!out.exists()) return
        val byId = HashMap<String, MutableList<String>>()
        out.forEachLine { l -> if (l.isNotBlank()) { val o = JSONObject(l); byId.getOrPut(o.getString("id")) { ArrayList() } += o.getString("text") } }
        val arr = problems()
        var right = 0; var wrong = 0; var flagged = 0; var total = 0
        val wrongConfident = ArrayList<String>()
        val report = StringBuilder()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val id = p.getString("id")
            val texts = byId[id] ?: continue
            total++
            val expected = if (p.isNull("answer")) null else p.getDouble("answer")
            val setups = texts.mapNotNull { Setup.parse(it) }
            val a = WordSolver.consensus(p.getString("problem"), setups)
            val got = a?.result?.takeIf { it.ok }?.values
            val verdict = when {
                expected == null && (a == null || !a.ok) -> { right++; "RIGHT (said not enough info)" }
                expected == null -> { if (a!!.confidence == Confidence.HIGH) { wrong++; wrongConfident += id; "WRONG (answered ${got})" } else { flagged++; "FLAGGED (answered ${got} but unsure)" } }
                got != null && got.any { abs(it - expected) <= 1e-4 * max(1.0, abs(expected)) } -> { right++; "RIGHT ${a!!.confidence}" }
                a == null || !a.ok || a.confidence == Confidence.LOW -> { flagged++; "FLAGGED (no confident answer; got $got)" }
                else -> { wrong++; if (a.confidence == Confidence.HIGH) wrongConfident += id; "WRONG got $got want $expected (${a.confidence})" }
            }
            report.append("$id: $verdict\n")
            if (!verdict.startsWith("RIGHT") || a?.confidence != Confidence.HIGH) {
                a?.issues?.forEach { report.append("     - ${it.severity}: ${it.message}\n") }
                setups.firstOrNull()?.let { report.append("     eqs: ${it.equations}  ask=${it.ask}\n") }
            }
        }
        report.append("\n$right right, $flagged flagged as unsure, $wrong wrong out of $total. Wrong but marked HIGH confidence: $wrongConfident\n")
        File(dir, "report.txt").writeText(report.toString())
        println(report)
    }
}
