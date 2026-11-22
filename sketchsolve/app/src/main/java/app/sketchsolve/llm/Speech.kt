package app.sketchsolve.llm

/** Push-to-talk speech input: start recording, stop and get the words. */
interface SpeechInput {
    fun start()
    /** Stop recording and transcribe; [onText] is called on a background thread. */
    fun stop(onText: (String) -> Unit)
    fun close() {}
}
