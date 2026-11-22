package app.sketchsolve.llm

import app.sketchsolve.word.*

/** Anything that can turn a prompt into text, offline. */
interface LanguageModel {
    val name: String
    /** temperature 0 = most likely reading; a little above 0 gives independent second opinions. */
    fun generate(prompt: String, temperature: Float, seed: Int, maxTokens: Int = 900): String
    fun close() {}
}

/**
 * Word problem → checked answer:
 *  1. ask the model for a setup, a few times (one careful reading + two varied ones),
 *  2. check each setup against the problem (numbers, units, equations),
 *  3. solve with the app's own solver and compare the answers.
 */
class WordPipeline(private val model: LanguageModel, private val library: () -> List<LibraryEntry>) {
    var readings = 3

    fun solve(problem: String, deg: Boolean = true, onProgress: (String) -> Unit = {}): WordAnswer? {
        val prompt = Prompt.build(problem, Library.relevant(problem, library()))
        val setups = ArrayList<Setup>()
        for (i in 0 until readings) {
            onProgress(if (i == 0) "Reading the problem…" else "Double-checking (${i + 1} of $readings)…")
            val text = model.generate(prompt, if (i == 0) 0f else 0.6f, 1234 + i)
            Setup.parse(text)?.let { setups += it }
            // Two careful readings that agree are enough.
            if (i == 1 && setups.size == 2) {
                val a = WordSolver.check(problem, setups[0], deg); val b = WordSolver.check(problem, setups[1], deg)
                if (a.ok && b.ok && kotlin.math.abs(a.result!!.value - b.result!!.value) <= 1e-6 * kotlin.math.max(1.0, kotlin.math.abs(a.result.value))) break
            }
        }
        onProgress("Checking the working…")
        return WordSolver.consensus(problem, setups, deg)
    }
}
