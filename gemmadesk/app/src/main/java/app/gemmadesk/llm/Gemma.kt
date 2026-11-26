package app.gemmadesk.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/** Anything that turns a prompt into JSON matching a schema, on the device. Tests use a fake. */
interface LanguageModel {
    /** [schema] is a JSON Schema; the reply always parses as JSON that fits it. */
    fun generateJson(prompt: String, schema: String, maxTokens: Int = 1200, temperature: Float = 0f): String
    fun close() {}
}

/**
 * Gemma 4 running on the phone through LiteRT-LM, with constrained decoding
 * so replies are always valid JSON for the schema. GPU first, then CPU.
 */
class GemmaModel(private val modelFile: File, private val cacheDir: File) : LanguageModel {
    private var engine: Engine? = null
    var backendUsed = ""
        private set

    @Synchronized
    private fun engine(): Engine {
        engine?.let { return it }
        var lastError: Throwable? = null
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            try {
                // room for a long design (prompt + up to ~3000 tokens of layout)
                val e = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = backend, maxNumTokens = 6144, cacheDir = cacheDir.absolutePath))
                e.initialize()
                engine = e
                backendUsed = if (backend is Backend.GPU) "GPU" else "CPU"
                return e
            } catch (t: Throwable) {
                Log.w("GemmaDesk", "Gemma on ${backend.name} failed", t)
                lastError = t
            }
        }
        throw IllegalStateException("Couldn't start Gemma: ${lastError?.message}", lastError)
    }

    fun warmUp() { engine() }

    override fun generateJson(prompt: String, schema: String, maxTokens: Int, temperature: Float): String {
        val conv = engine().createConversation(ConversationConfig(
            samplerConfig = SamplerConfig(40, 0.95, temperature.toDouble(), 1234),
            enableResponseFormat = true,
            maxOutputToken = maxTokens))
        try {
            val reply = conv.sendMessage(prompt, responseFormat = ResponseFormat.json(schema))
            return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        } finally {
            conv.close()
        }
    }

    @Synchronized
    override fun close() { engine?.close(); engine = null }
}

/** Pull the JSON object out of a reply, tolerating stray text or code fences around it. */
fun extractJson(text: String): String? {
    val s = text.indexOf('{'); val e = text.lastIndexOf('}')
    return if (s >= 0 && e > s) text.substring(s, e + 1) else null
}
