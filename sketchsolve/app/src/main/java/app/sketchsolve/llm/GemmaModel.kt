package app.sketchsolve.llm

import android.util.Log
import app.sketchsolve.word.Prompt
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/**
 * Gemma 4 running on the phone through LiteRT-LM. Replies are constrained
 * to the setup JSON schema, so they always parse. Tries the GPU first and
 * falls back to the CPU.
 */
class GemmaModel(private val modelFile: File, private val cacheDir: File) : LanguageModel {
    override val name = "Gemma 4 (on device)"
    private var engine: Engine? = null
    var backendUsed = ""
        private set

    @Synchronized
    private fun engine(): Engine {
        engine?.let { return it }
        var lastError: Throwable? = null
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            try {
                val e = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = backend, maxNumTokens = 4096, cacheDir = cacheDir.absolutePath))
                e.initialize()
                engine = e
                backendUsed = if (backend is Backend.GPU) "GPU" else "CPU"
                return e
            } catch (t: Throwable) {
                Log.w("SketchSolve", "Gemma on ${backend.name} failed", t)
                lastError = t
            }
        }
        throw IllegalStateException("Couldn't start Gemma: ${lastError?.message}", lastError)
    }

    /** Load the model ahead of time so the first question is quick. */
    fun warmUp() { engine() }

    override fun generate(prompt: String, temperature: Float, seed: Int, maxTokens: Int): String {
        val conv = engine().createConversation(ConversationConfig(
            samplerConfig = SamplerConfig(40, 0.95, temperature.toDouble(), seed),
            enableResponseFormat = true,
            maxOutputToken = maxTokens))
        try {
            val reply = conv.sendMessage(prompt, responseFormat = ResponseFormat.json(Prompt.SCHEMA))
            return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        } finally {
            conv.close()
        }
    }

    @Synchronized
    override fun close() { engine?.close(); engine = null }
}
