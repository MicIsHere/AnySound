package io.anysound.speech

import io.anysound.audio.AudioSource
import io.anysound.audio.Microphone
import io.anysound.config.Settings
import io.anysound.config.RecognitionMode
import io.anysound.text.TextProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VoicePhase { IDLE, STARTING, RECORDING, FINISHING, PREVIEW, DONE, CANCELLED, ERROR }
data class VoiceState(val phase: VoicePhase = VoicePhase.IDLE, val text: String = "", val level: Float = 0f,
    val startedAt: Long = 0, val error: String? = null, val cancelledAt: Long = 0)

class VoiceSession(
    private val scope: CoroutineScope,
    private val recognizerFactory: (Settings) -> SpeechRecognizer,
    private val audioFactory: (Settings) -> AudioSource = { Microphone(it.microphone) },
    private val onSend: suspend (String, Settings) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Session(val settings: Settings) {
        val audio = Channel<ByteArray>(100)
        var source: AudioSource? = null
        var job: Job? = null
        @Volatile var signal = false
        var stopped = false
        var lastMeterUpdate = 0L
    }
    private val guard = Any()
    private val engineGate = Mutex()
    private var current: Session? = null
    private var previewSettings = Settings()
    private val mutable = MutableStateFlow(VoiceState())
    val state = mutable.asStateFlow()

    fun press(settings: Settings) = synchronized(guard) {
        if (current != null || mutable.value.phase == VoicePhase.PREVIEW) return@synchronized
        val session = Session(settings)
        current = session
        mutable.value = VoiceState(VoicePhase.STARTING)
        session.job = scope.launch(io) {
            engineGate.withLock {
                var recognizer: SpeechRecognizer? = null
                try {
                    ensureActive()
                    recognizer = recognizerFactory(settings)
                    ensureActive()
                    val engine = recognizer
                    engine.start({ text ->
                        synchronized(guard) {
                            if (current === session && mutable.value.phase in setOf(VoicePhase.STARTING, VoicePhase.RECORDING, VoicePhase.FINISHING))
                                mutable.value = mutable.value.copy(text = TextProcessor.cleanVoice(text, settings))
                        }
                    }, { error -> fail(session, error) })
                    ensureActive()
                    val source = audioFactory(settings)
                    synchronized(guard) {
                        if (current !== session) throw CancellationException()
                        session.source = source
                    }
                    source.start({ pcm ->
                        synchronized(guard) {
                            if (current === session && !session.stopped) {
                                if (!session.audio.trySend(pcm).isSuccess) fail(session, "识别处理跟不上录音，已停止本次发送")
                            }
                        }
                    }, { level ->
                        synchronized(guard) {
                            if (current === session && !session.stopped) {
                                session.signal = session.signal || level > 0.003f
                                val now = System.nanoTime() / 1_000_000
                                if (now - session.lastMeterUpdate >= 100) {
                                    mutable.value = mutable.value.copy(level = level)
                                    session.lastMeterUpdate = now
                                }
                            }
                        }
                    }, { message -> fail(session, message) })
                    synchronized(guard) {
                        if (current !== session) throw CancellationException()
                        mutable.value = mutable.value.copy(phase = VoicePhase.RECORDING, startedAt = System.currentTimeMillis())
                    }
                    val watchdog = launch {
                        delay(60_000)
                        fail(session, "单次录音超过 60 秒，已取消；请分次说话")
                    }
                    try { for (pcm in session.audio) { ensureActive(); engine.accept(pcm) } }
                    finally { watchdog.cancel() }
                    val finalText = withTimeout(20_000) { runInterruptible(io) { engine.finish() } }
                    val clean = if (session.signal) TextProcessor.cleanVoice(finalText, settings) else ""
                    synchronized(guard) {
                        if (current !== session) throw CancellationException()
                        previewSettings = settings
                        mutable.value = VoiceState(if (clean.isBlank()) VoicePhase.DONE else VoicePhase.PREVIEW, clean)
                    }
                    // onSend is guarded by the session's lifetime; cancel() also clears the sender queue.
                    if (clean.isNotBlank() && settings.autoSend) {
                        ensureActive()
                        onSend(clean, settings)
                        synchronized(guard) {
                            if (current === session) mutable.value = VoiceState(VoicePhase.DONE, clean)
                        }
                    }
                } catch (e: TimeoutCancellationException) { fail(session, "识别结束超时，已取消发送") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(session, if (settings.mode == RecognitionMode.ALIBABA) "百炼识别失败，请检查网络和在线设置" else e.message ?: "本地识别失败") }
                catch (_: LinkageError) { fail(session, "无法加载语音原生库，请检查系统架构和安装包是否完整") }
                finally {
                    session.source?.stop()
                    session.audio.cancel()
                    runCatching { recognizer?.cancel() }
                    synchronized(guard) { if (current === session) current = null }
                }
            }
        }
    }

    fun release() = synchronized(guard) {
        val session = current ?: return@synchronized
        if (session.stopped) return@synchronized
        if (mutable.value.phase == VoicePhase.STARTING) { cancel(); return@synchronized }
        session.stopped = true
        mutable.value = mutable.value.copy(phase = VoicePhase.FINISHING, level = 0f)
        session.audio.close()
        scope.launch(io) { session.source?.stop() }
    }

    fun editPreview(text: String) = synchronized(guard) {
        if (mutable.value.phase == VoicePhase.PREVIEW && current == null) mutable.value = mutable.value.copy(text = text)
    }

    suspend fun confirm() {
        val preview = synchronized(guard) {
            if (current != null || mutable.value.phase != VoicePhase.PREVIEW) return
            val text = mutable.value.text
            mutable.value = VoiceState(VoicePhase.DONE, text)
            text to previewSettings
        }
        try { onSend(preview.first, preview.second) }
        catch (e: Exception) {
            synchronized(guard) {
                if (current == null && mutable.value == VoiceState(VoicePhase.DONE, preview.first))
                    mutable.value = VoiceState(VoicePhase.PREVIEW, preview.first)
            }
            throw e
        }
    }

    private fun fail(session: Session, message: String) = synchronized(guard) {
        if (current !== session) return@synchronized
        current = null
        session.audio.cancel()
        session.job?.cancel()
        scope.launch(io) { session.source?.stop() }
        mutable.value = VoiceState(VoicePhase.ERROR, mutable.value.text, error = message)
    }

    fun cancel(hasPendingMessages: Boolean = false): Job? = synchronized(guard) {
        val previous = current
        if (previous == null && mutable.value.phase != VoicePhase.PREVIEW && !hasPendingMessages) return@synchronized null
        current = null
        previous?.audio?.cancel()
        previous?.job?.cancel()
        if (previous != null) scope.launch(io) { previous.source?.stop() }
        if (mutable.value.phase != VoicePhase.CANCELLED)
            mutable.value = VoiceState(VoicePhase.CANCELLED, cancelledAt = System.currentTimeMillis())
        previous?.job
    }

    fun dismissCancellation() = synchronized(guard) {
        if (mutable.value.phase == VoicePhase.CANCELLED) mutable.value = VoiceState()
    }

    /** Also waits for an already-cancelled JNI call before its cached model is freed. */
    suspend fun awaitStopped() { engineGate.withLock { } }
}
