package io.anysound.speech

/** One utterance at a time. PCM is signed little-endian, mono, 16-bit, 16 kHz. */
interface SpeechRecognizer : AutoCloseable {
    fun start(onPartial: (String) -> Unit, onError: (String) -> Unit)
    fun accept(pcm: ByteArray)
    fun finish(): String
    fun cancel()
}

/** Revisions replace a sentence; server endpointing must not duplicate prior words. */
class SentenceTranscript {
    private data class Sentence(val text: String, val final: Boolean)
    private val sentences = sortedMapOf<Long, Sentence>()
    @Synchronized fun update(id: Long, text: String, final: Boolean): String {
        if (sentences[id]?.final != true || final) sentences[id] = Sentence(text, final)
        return preview()
    }
    @Synchronized fun preview() = sentences.values.joinToString(" ") { it.text }.trim()
    @Synchronized fun finalText(): String {
        check(sentences.values.none { !it.final && it.text.isNotBlank() }) { "识别未完整结束，请重试" }
        return preview()
    }
}
