package app.gemmadesk.assist

import app.gemmadesk.data.Library
import app.gemmadesk.data.Passage
import app.gemmadesk.llm.LanguageModel
import app.gemmadesk.llm.extractJson
import org.json.JSONObject

/**
 * Answers plain questions from the user's library. The app finds the best
 * passages; Gemma may only answer from those, must quote the words it relied
 * on, and the app checks each quote really is in that passage. If the library
 * doesn't cover the question, it says so instead of guessing.
 */
class Reader(private val model: LanguageModel, private val library: Library) {

    data class Source(val passage: Passage, val quote: String, val quoteFound: Boolean)
    data class Answer(val question: String, val found: Boolean, val text: String, val sources: List<Source>, val searched: List<Passage>, val notes: List<String>)

    companion object {
        val SCHEMA = """
{"type":"object","properties":{
 "found":{"type":"boolean"},
 "answer":{"type":"string"},
 "sources":{"type":"array","items":{"type":"object","properties":{"n":{"type":"integer"},"quote":{"type":"string"}},"required":["n","quote"],"additionalProperties":false}},
 "search_terms":{"type":"array","items":{"type":"string"}}},
"required":["found","answer","sources","search_terms"],"additionalProperties":false}
""".trimIndent()

        fun prompt(question: String, passages: List<Passage>): String = buildString {
            append("Answer the user's question using ONLY the numbered passages from their reference library below.\n")
            append("- If the passages answer it: found = true; give a clear, practical answer in plain words (a few sentences or short steps). ")
            append("Mention section or table numbers when the passage has them. List the passages you used in \"sources\", each with a short exact quote (copied word for word) that supports the answer.\n")
            append("- If they don't answer it: found = false, say briefly what is missing, and give 2 to 5 better search_terms (words likely to appear in the right section).\n")
            append("- Never use outside knowledge for facts, numbers or code requirements. Don't invent section numbers.\n\n")
            passages.forEachIndexed { i, p -> append("[${i + 1}] (${p.where()})\n${p.text.take(1400)}\n\n") }
            append("Question: $question\n")
        }

        private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        /** Is the quote (allowing for line breaks, punctuation and a missing word or two) really in the passage? */
        fun quoteIn(quote: String, text: String): Boolean {
            val q = norm(quote); val t = norm(text)
            if (q.length < 4) return false
            if (t.contains(q)) return true
            // long quotes: most of their 4-word runs must appear
            val w = q.split(' ')
            if (w.size < 6) return false
            val runs = (0..w.size - 4).map { w.subList(it, it + 4).joinToString(" ") }
            return runs.count { t.contains(it) } >= runs.size * 0.7
        }
    }

    fun ask(question: String, onProgress: (String) -> Unit = {}): Answer {
        onProgress("Searching your library…")
        var passages = library.search(question, 6)
        if (passages.isEmpty()) return Answer(question, false, "Nothing in your library matches those words. Try different words, or add a document that covers it.", emptyList(), emptyList(), emptyList())
        onProgress("Gemma is reading ${passages.size} passages…")
        var o = run(question, passages)
        val notes = ArrayList<String>()
        // one more try with Gemma's own search words if the first passages missed
        if (o != null && !o.optBoolean("found")) {
            val terms = o.optJSONArray("search_terms")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            if (terms.isNotEmpty()) {
                onProgress("Looking again for: ${terms.joinToString(", ")}…")
                val more = library.search(terms.joinToString(" ") + " " + question, 6)
                val fresh = more.filter { m -> passages.none { it.doc.id == m.doc.id && it.text == m.text } }
                if (fresh.isNotEmpty()) {
                    passages = (fresh + passages).take(6)
                    run(question, passages)?.let { o = it; notes += "Searched again for: ${terms.joinToString(", ")}." }
                }
            }
        }
        val r = o ?: return Answer(question, false, "Gemma couldn't answer that. Try asking it another way.", emptyList(), passages, notes)
        val srcs = r.optJSONArray("sources")
        val sources = (0 until (srcs?.length() ?: 0)).mapNotNull { k ->
            val s = srcs!!.getJSONObject(k); val n = s.optInt("n") - 1
            passages.getOrNull(n)?.let { p -> Source(p, s.optString("quote").trim(), quoteIn(s.optString("quote"), p.text)) }
        }.distinctBy { it.passage.text to it.quote }
        val found = r.optBoolean("found") && sources.isNotEmpty()
        if (r.optBoolean("found") && sources.isEmpty()) notes += "Gemma didn't point to a passage for this answer, so treat it with care."
        if (sources.any { !it.quoteFound }) notes += "Some quoted words couldn't be found in the passage. Check the passage before relying on it."
        return Answer(question, found, r.optString("answer").trim().ifEmpty { "No answer." }, sources, passages, notes)
    }

    private fun run(question: String, passages: List<Passage>): JSONObject? =
        extractJson(model.generateJson(prompt(question, passages), SCHEMA, 900))?.let { runCatching { JSONObject(it) }.getOrNull() }
}
