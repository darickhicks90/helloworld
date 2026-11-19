package app.foxbox.notes

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import java.util.Locale
import com.google.mlkit.vision.digitalink.Ink as MlInk

/**
 * Handwriting → text, on the device (ML Kit digital ink). The language model is fetched
 * once (a few MB) the first time the notebook opens; after that it works offline.
 */
object Handwriting {
    enum class State { IDLE, DOWNLOADING, READY, FAILED }
    var state = State.IDLE; private set
    var onReady: (() -> Unit)? = null
    private var recognizer: DigitalInkRecognizer? = null

    private fun modelId(): DigitalInkRecognitionModelIdentifier? {
        val tags = listOf(Locale.getDefault().toLanguageTag(), Locale.getDefault().language, "en-US")
        for (t in tags) runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(t) }.getOrNull()?.let { return it }
        return null
    }

    fun prepare() {
        if (state == State.DOWNLOADING || state == State.READY) return
        val id = modelId() ?: run { state = State.FAILED; return }
        val model = DigitalInkRecognitionModel.builder(id).build()
        state = State.DOWNLOADING
        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                state = State.READY
                onReady?.invoke()
            }
            .addOnFailureListener { state = State.FAILED }
    }

    /**
     * [strokes]: each stroke as x, y, t (ms) triples. [before] is the text just before where this
     * goes (it helps the recognizer with word spacing and context).
     */
    fun recognize(strokes: List<FloatArray>, before: String, areaW: Float, areaH: Float, done: (String?) -> Unit) {
        val r = recognizer ?: return done(null)
        val ink = MlInk.builder()
        for (s in strokes) {
            val sb = MlInk.Stroke.builder()
            var i = 0
            while (i + 2 < s.size) { sb.addPoint(MlInk.Point.create(s[i], s[i + 1], s[i + 2].toLong())); i += 3 }
            ink.addStroke(sb.build())
        }
        val ctx = RecognitionContext.builder().setPreContext(before.takeLast(20)).setWritingArea(WritingArea(areaW, areaH)).build()
        r.recognize(ink.build(), ctx)
            .addOnSuccessListener { res -> done(res.candidates.firstOrNull()?.text) }
            .addOnFailureListener { done(null) }
    }
}
