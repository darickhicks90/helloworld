package app.foxbox.audio

/**
 * Measures how small a buffer this device can run without dropouts. Plays a
 * quiet tone and, for each buffer size, counts dropouts and how much of the
 * buffer's time the audio render uses. Run off the main thread.
 */
object AudioTest {
    class Step(val requested: Int, val actual: Int, val dropouts: Int, val load: Float)
    class Report(val steps: List<Step>, val recommended: Int, val text: String)

    fun run(progress: (String) -> Unit): Report {
        if (!Audio.isNative) {
            return Report(emptyList(), 256, "This device is using the compatibility audio path (AudioTrack). " +
                "Buffer sizes can't be measured here; 256 is a safe choice. Latency: ~${Audio.latencyMs.toInt()} ms.")
        }
        Audio.testing = true; Audio.testTone = true
        val original = NativeAudio.nBufferSize()
        val steps = ArrayList<Step>()
        try {
            for (b in listOf(64, 128, 256, 512)) {
                progress("Testing a $b-frame buffer…")
                NativeAudio.nSetBufferSize(b)
                Thread.sleep(300)
                val x0 = NativeAudio.nXRuns()
                NativeAudio.maxRenderNs = 0
                Thread.sleep(2500)
                val dx = NativeAudio.nXRuns() - x0
                val period = NativeAudio.lastFrames.coerceAtLeast(1) * 1e9f / SR
                steps += Step(b, NativeAudio.nBufferSize(), dx, NativeAudio.maxRenderNs / period)
            }
        } finally {
            NativeAudio.nSetBufferSize(original)
            Audio.testing = false; Audio.testTone = false
        }
        val good = steps.firstOrNull { it.dropouts == 0 && it.load < 0.7f }
        val rec = good?.requested ?: 512
        val lines = steps.joinToString("\n") { s ->
            val ms = s.actual * 1000f / SR
            "• ${s.requested} frames (${"%.1f".format(ms)} ms): ${if (s.dropouts == 0) "clean" else "${s.dropouts} dropouts"}, CPU ${(s.load * 100).toInt()}%"
        }
        val text = "Sample rate ${SR / 1000f} kHz · burst ${NativeAudio.nBurst()} frames · " +
            (if (NativeAudio.nExclusive()) "exclusive low-latency mode" else "shared mode") +
            "\n\n$lines\n\nRecommended buffer: $rec frames (about ${"%.1f".format(rec * 1000f / SR)} ms)."
        return Report(steps, rec, text)
    }
}
