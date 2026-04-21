import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class LocalTranslator {
    private var translator: Translator? = null
    var isReady = false
        private set

    fun init(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()

        translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        translator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener {
                isReady = true
                onSuccess()
            }
            ?.addOnFailureListener { e -> onFailure(e) }
    }

    fun translate(text: String, onSuccess: (String) -> Unit) {
        if (!isReady) return
        translator?.translate(text)?.addOnSuccessListener(onSuccess)
    }

    fun close() {
        translator?.close()
        translator = null
        isReady = false
    }
}