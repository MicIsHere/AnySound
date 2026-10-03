package io.anysound.speech

import com.k2fsa.sherpa.onnx.*
import io.anysound.config.LocalRecognitionLanguage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class LocalRecognizer(directory: Path, refinementDirectory: Path? = null,
    language: LocalRecognitionLanguage = LocalRecognitionLanguage.CHINESE) : SpeechRecognizer {
    private val recognizer: OnlineRecognizer
    private var refinement: OfflineRecognizer? = null
    private var audio: ByteArrayOutputStream? = null
    private var stream: OnlineStream? = null
    private var partial: (String) -> Unit = {}
    private val completed = mutableListOf<String>()
    init {
        val files = ModelFiles.streaming
        files.validate(directory)
        refinementDirectory?.let { ModelFiles.refinement.validate(it) }
        val model = OnlineModelConfig.builder()
            .setTransducer(OnlineTransducerModelConfig.builder()
                .setEncoder(directory.resolve(files.names[0]).toString())
                .setDecoder(directory.resolve(files.names[1]).toString())
                .setJoiner(directory.resolve(files.names[2]).toString()).build())
            .setTokens(directory.resolve("tokens.txt").toString())
            .setNumThreads(2).setProvider("cpu").setDebug(false).setModelType("zipformer").build()
        recognizer = OnlineRecognizer(OnlineRecognizerConfig.builder()
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
            .setOnlineModelConfig(model).setEnableEndpoint(true).setDecodingMethod("greedy_search").build())
        try {
            if (refinementDirectory != null) {
                refinement = OfflineRecognizer(OfflineRecognizerConfig.builder()
                    .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
                    .setOfflineModelConfig(OfflineModelConfig.builder()
                        .setSenseVoice(OfflineSenseVoiceModelConfig.builder()
                            .setModel(refinementDirectory.resolve("model.int8.onnx").toString())
                            .setLanguage(language.code).setInverseTextNormalization(true).build())
                        .setTokens(refinementDirectory.resolve("tokens.txt").toString())
                        .setNumThreads(2).setProvider("cpu").setDebug(false).build()).build())
            }
        } catch (error: Throwable) { recognizer.release(); throw error }
    }
    override fun start(onPartial: (String) -> Unit, onError: (String) -> Unit) {
        cancel()
        if (refinement != null) audio = ByteArrayOutputStream()
        partial = onPartial
        stream = recognizer.createStream()
    }
    override fun accept(pcm: ByteArray) {
        val input = stream ?: return
        audio?.let {
            require(it.size() + pcm.size <= 60 * 32000) { "单次录音超过 60 秒，已取消" }
            it.write(pcm)
        }
        input.acceptWaveform(pcm.samples(), 16000)
        decode(input)
        val current = recognizer.getResult(input).text.trim()
        partial((completed + current).filter { it.isNotBlank() }.joinToString(" "))
        if (recognizer.isEndpoint(input)) {
            if (current.isNotBlank()) completed += current
            recognizer.reset(input)
        }
    }
    private fun decode(input: OnlineStream) { while (recognizer.isReady(input)) recognizer.decode(input) }
    override fun finish(): String {
        val input = stream ?: return ""
        input.acceptWaveform(FloatArray(8000), 16000)
        input.inputFinished()
        decode(input)
        val finalPass = refinement
        if (finalPass != null) {
            val pcm = checkNotNull(audio).toByteArray()
            if (pcm.isEmpty()) return ""
            if (Thread.currentThread().isInterrupted) throw InterruptedException("识别已取消")
            val finalStream = finalPass.createStream()
            try {
                finalStream.acceptWaveform(pcm.samples(), 16000)
                finalPass.decode(finalStream)
                return finalPass.getResult(finalStream).text.trim()
            } finally { finalStream.release() }
        }
        return (completed + recognizer.getResult(input).text.trim()).filter { it.isNotBlank() }.joinToString(" ")
    }
    override fun cancel() { stream?.release(); stream = null; partial = {}; completed.clear(); audio = null }
    override fun close() { cancel(); try { refinement?.release() } finally { recognizer.release() } }

    private fun ByteArray.samples(): FloatArray {
        require(size % 2 == 0) { "音频数据不是完整的 16-bit PCM" }
        return FloatArray(size / 2) { i ->
            ((this[2 * i].toInt() and 255) or (this[2 * i + 1].toInt() shl 8)).toShort() / 32768f
        }
    }
}

class ModelFiles(val label: String, val model: String, val revision: String, private val hashes: Map<String, String>) {
    val names = hashes.keys.toList()
    fun matches(file: Path, name: String): Boolean {
        if (!Files.isRegularFile(file)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == hashes[name]
    }
    fun validate(directory: Path) {
        require(names.all { matches(directory.resolve(it), it) }) {
            "$label 模型文件不完整或校验不匹配，请重新下载或检查导入目录"
        }
    }
    companion object {
        val streaming = ModelFiles("实时字幕", "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            "98590b7ed6443e77b714204da2757d75e1a642f4", linkedMapOf(
                "encoder-epoch-99-avg-1.int8.onnx" to "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b",
                "decoder-epoch-99-avg-1.int8.onnx" to "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa",
                "joiner-epoch-99-avg-1.int8.onnx" to "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0",
                "tokens.txt" to "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
            ))
        val refinement = ModelFiles("准确率", "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            "2365baeacb507f821a0c8120fcee3d484dba7a07", linkedMapOf(
                "model.int8.onnx" to "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                "tokens.txt" to "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            ))
    }
}
