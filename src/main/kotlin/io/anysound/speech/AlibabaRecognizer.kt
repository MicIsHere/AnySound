package io.anysound.speech

import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult
import com.alibaba.dashscope.common.ResultCallback
import com.alibaba.dashscope.utils.Constants
import com.alibaba.dashscope.protocol.ConnectionOptions
import io.anysound.config.Settings
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AlibabaRecognizer(private val settings: Settings, private val key: String) : SpeechRecognizer {
    private var recognition: Recognition? = null
    private val active = AtomicBoolean(false)
    private var completion = CountDownLatch(1)
    private var transcript = SentenceTranscript()
    @Volatile private var failure: String? = null
    override fun start(onPartial: (String) -> Unit, onError: (String) -> Unit) {
        require(key.isNotBlank()) { "请在设置中填写百炼 API Key，或设置 DASHSCOPE_API_KEY" }
        val endpoint = settings.cloudEndpoint.replace("{WorkspaceId}", settings.workspaceId.trim())
        if ("{WorkspaceId}" in settings.cloudEndpoint) require(settings.workspaceId.matches(Regex("[A-Za-z0-9-]+"))) { "请填写百炼业务空间 ID" }
        val uri = URI(endpoint)
        require(uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "百炼服务端点必须是有效的 wss:// 地址" }
        cancel()
        completion = CountDownLatch(1)
        transcript = SentenceTranscript()
        failure = null
        Constants.baseWebsocketApiUrl = endpoint
        val client = Recognition(ConnectionOptions.builder().connectTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(20)).build())
        recognition = client
        active.set(true)
        val param = RecognitionParam.builder().model(settings.cloudModel).apiKey(key)
            .format("pcm").sampleRate(16000).disfluencyRemovalEnabled(false).build()
        client.call(param, object : ResultCallback<RecognitionResult>() {
            override fun onEvent(result: RecognitionResult) {
                if (!active.get()) return
                val sentence = result.sentence ?: return
                if (sentence.isHeartbeat) return
                val id = sentence.sentenceId ?: sentence.beginTime ?: return
                onPartial(transcript.update(id, sentence.text.orEmpty(), result.isSentenceEnd))
            }
            override fun onComplete() { completion.countDown() }
            override fun onError(e: Exception) {
                // SDK error strings may include request credentials or transcripts.
                failure = "百炼识别失败，请检查网络、密钥、业务空间及服务额度"
                completion.countDown()
                if (active.get()) onError(failure!!)
            }
        })
    }
    override fun accept(pcm: ByteArray) { if (active.get()) recognition?.sendAudioFrame(ByteBuffer.wrap(pcm)) }
    override fun finish(): String {
        recognition?.stop()
        check(completion.await(15, TimeUnit.SECONDS)) { "百炼识别超时，请重试" }
        failure?.let { error(it) }
        return transcript.finalText()
    }
    override fun cancel() {
        active.set(false)
        recognition?.let { runCatching { it.duplexApi.close(1000, "cancel") } }
        recognition = null
        completion.countDown()
    }
    override fun close() = cancel()
}
