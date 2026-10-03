package io.anysound

import io.anysound.config.*
import io.anysound.osc.*
import io.anysound.speech.*
import io.anysound.vr.*
import io.anysound.text.TextProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

class AppController(private val store: SettingsStore = SettingsStore()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableSettings = MutableStateFlow(runCatching { store.load() }.getOrElse {
        mutableError.value = "设置文件无法读取，已使用默认值；原文件仍保留在配置目录"
        Settings()
    })
    val settings = mutableSettings.asStateFlow()
    private val mutableDownload = MutableStateFlow<String?>(null)
    val download = mutableDownload.asStateFlow()
    val overlayEnabled = MutableStateFlow(true)
    @Volatile var apiKey: String = System.getenv("DASHSCOPE_API_KEY").orEmpty()
    private val osc = OscClient()
    private var localConfiguration: Triple<String, String, LocalRecognitionLanguage>? = null
    private var local: LocalRecognizer? = null
    @Volatile private var lastActivity = 0L
    @Volatile private var lastEdit = 0L
    private var downloadJob: Job? = null
    private var closing = false
    private val settingsGate = Mutex()

    val sender = ChatSender(scope, { prefs, text ->
        withContext(Dispatchers.IO) { osc.send(prefs.oscHost, prefs.oscPort, "/chatbox/input", text, true, prefs.notificationSound) }
    })
    val voice = VoiceSession(scope, { prefs ->
        when (prefs.mode) {
            RecognitionMode.LOCAL -> {
                require(prefs.modelDirectory.isNotBlank()) { "请先在设置中下载或导入离线模型" }
                val refinement = if (prefs.localMode == LocalRecognitionMode.ACCURATE) {
                    require(prefs.refinementModelDirectory.isNotBlank()) { "准确率模式还需要 SenseVoice 模型，请在设置中下载或导入" }
                    prefs.refinementModelDirectory
                } else ""
                val language = if (refinement.isNotBlank()) prefs.localLanguage else LocalRecognitionLanguage.CHINESE
                val configuration = Triple(prefs.modelDirectory, refinement, language)
                if (local == null || localConfiguration != configuration) {
                    local?.close()
                    local = null
                    local = LocalRecognizer(Path.of(prefs.modelDirectory), refinement.takeIf { it.isNotBlank() }?.let(Path::of), language)
                    localConfiguration = configuration
                }
                local!!
            }
            RecognitionMode.ALIBABA -> AlibabaRecognizer(prefs, apiKey)
        }
    }, onSend = { text, prefs -> sender.enqueue(text, prefs) })

    val vr = SteamVr(store.directory.resolve("steamvr"), { settings.value }, ::hud,
        ::press, { voice.release() }, ::confirm, ::cancel, ::toggleOverlay)

    private val typingJob: Job
    init {
        scope.launch { combine(voice.state, sender.state) { a, b -> a to b }.collect { lastActivity = System.currentTimeMillis() } }
        typingJob = scope.launch(Dispatchers.IO) {
            var previous = false
            var destination = settings.value
            try {
                while (isActive) {
                    val prefs = settings.value
                    if (previous && (prefs.oscHost != destination.oscHost || prefs.oscPort != destination.oscPort)) {
                        runCatching { osc.send(destination.oscHost, destination.oscPort, "/chatbox/typing", false) }
                        previous = false
                    }
                    destination = prefs
                    val wanted = voice.state.value.phase in setOf(VoicePhase.STARTING, VoicePhase.RECORDING, VoicePhase.FINISHING, VoicePhase.PREVIEW) || System.currentTimeMillis() - lastEdit < 2000
                    if (wanted || previous) runCatching { osc.send(prefs.oscHost, prefs.oscPort, "/chatbox/typing", wanted) }
                    previous = wanted
                    delay(1000)
                }
            } finally { runCatching { osc.send(destination.oscHost, destination.oscPort, "/chatbox/typing", false) } }
        }
    }

    fun edited(nonEmpty: Boolean) { lastEdit = if (nonEmpty) System.currentTimeMillis() else 0 }
    fun sendTyped(text: String, queued: () -> Unit) {
        if (text.isBlank()) return
        scope.launch {
            try { sender.enqueue(text, settings.value); voice.dismissCancellation(); lastEdit = 0; mutableError.value = null; queued() }
            catch (e: Exception) { mutableError.value = e.message ?: "发送失败" }
        }
    }
    fun press() { if (!closing) { mutableError.value = null; voice.press(settings.value) } }
    fun confirm() { scope.launch { runCatching { voice.confirm() }.onFailure { mutableError.value = it.message ?: "发送失败" } } }
    fun cancel() {
        val hasPendingMessages = sender.state.value.pending > 0
        val active = voice.cancel(hasPendingMessages)
        lastEdit = 0
        mutableError.value = null
        // An active session may already be enqueuing its final text when cancellation starts.
        if (hasPendingMessages || active != null) scope.launch { sender.cancel() }
    }
    fun toggleOverlay() { overlayEnabled.update { !it } }
    fun dismissError() { mutableError.value = null }

    fun saveSettings(prefs: Settings, key: String, saved: () -> Unit) {
        scope.launch {
            try {
                check(voice.state.value.phase !in setOf(VoicePhase.STARTING, VoicePhase.RECORDING, VoicePhase.FINISHING, VoicePhase.PREVIEW)) { "请先结束或取消当前语音，再修改设置" }
                settingsGate.withLock {
                    withContext(Dispatchers.IO) { store.save(prefs) }
                    apiKey = key.trim()
                    mutableSettings.value = prefs
                }
                mutableError.value = null
                saved()
            } catch (e: Exception) { mutableError.value = e.message ?: "保存设置失败" }
        }
    }
    fun setFilters(fillers: Boolean? = null, punctuation: Boolean? = null) {
        scope.launch {
            try {
                settingsGate.withLock {
                    val current = settings.value
                    val updated = current.copy(filterFillers = fillers ?: current.filterFillers, cleanPunctuation = punctuation ?: current.cleanPunctuation)
                    withContext(Dispatchers.IO) { store.save(updated) }
                    mutableSettings.value = updated
                }
            } catch (_: Exception) { mutableError.value = "保存文字清理设置失败" }
        }
    }
    fun downloadModel(mode: LocalRecognitionMode = settings.value.localMode) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            try {
                val root = store.directory.resolve("models")
                val directory = ModelDownloader.download(root.resolve(ModelFiles.streaming.model)) { mutableDownload.value = it }
                val refinement = if (mode == LocalRecognitionMode.ACCURATE) {
                    ModelDownloader.download(root.resolve(ModelFiles.refinement.model), ModelFiles.refinement) { mutableDownload.value = it }
                } else null
                settingsGate.withLock {
                    val updated = settings.value.copy(modelDirectory = directory.toString(),
                        refinementModelDirectory = refinement?.toString() ?: settings.value.refinementModelDirectory)
                    withContext(Dispatchers.IO) { store.save(updated) }
                    mutableSettings.value = updated
                }
                mutableDownload.value = "模型已就绪"
            } catch (e: CancellationException) { mutableDownload.value = null; throw e }
            catch (_: Exception) { mutableDownload.value = null; mutableError.value = "模型下载失败，可重试或在设置中导入手动下载的模型" }
        }
    }
    fun cancelDownload() { downloadJob?.cancel() }

    fun hud(): HudContent = hud(System.currentTimeMillis())

    internal fun hud(now: Long): HudContent {
        val v = voice.state.value
        val s = sender.state.value
        val cancelled = v.phase == VoicePhase.CANCELLED
        val problem = v.error ?: s.error.takeUnless { cancelled } ?: error.value
        val recording = v.phase in setOf(VoicePhase.STARTING, VoicePhase.RECORDING, VoicePhase.FINISHING)
        val title = when {
            problem != null -> "需要处理"
            v.phase == VoicePhase.STARTING -> "正在准备识别…"
            v.phase == VoicePhase.RECORDING -> "正在聆听 · 松开发送"
            v.phase == VoicePhase.FINISHING -> if (settings.value.mode == RecognitionMode.LOCAL && settings.value.localMode == LocalRecognitionMode.ACCURATE) "正在精校最终文字…" else "正在整理文字…"
            v.phase == VoicePhase.PREVIEW -> "预览 · 按确认键发送"
            cancelled -> "已取消"
            s.pending > 0 -> "正在发送 ${s.current}/${s.total}"
            v.phase == VoicePhase.DONE || s.current > 0 -> "已处理"
            else -> "AnySound"
        }
        val liveText = if (recording && v.text.length > 144) "…" + TextProcessor.split(v.text).last() else v.text
        val text = problem ?: when {
            cancelled -> "已停止录音，并清除尚未发送的内容"
            recording || v.phase == VoicePhase.PREVIEW -> liveText
            else -> s.text.ifBlank { v.text }
        }
        val footer = when {
            cancelled -> "松开录音键后，可再次按住说话"
            s.pending > 0 -> "剩余 ${s.pending} 条 · 取消键可停止待发消息"
            recording -> "${((now - v.startedAt).coerceAtLeast(0) / 1000).takeIf { v.startedAt > 0 } ?: 0}s · 仅转写，不开启 VRChat 麦克风"
            else -> "${if (settings.value.mode == RecognitionMode.LOCAL) "本地 · ${if (settings.value.localMode == LocalRecognitionMode.ACCURATE) "准确率模式" else "速度模式"}" else "百炼在线识别"} · AnySound"
        }
        val visible = if (cancelled && problem == null) now - v.cancelledAt < 2000
            else recording || v.phase == VoicePhase.PREVIEW || problem != null || s.pending > 0 || now - lastActivity < 3000
        val activity = when {
            problem != null || cancelled -> HudActivity.IDLE
            v.phase == VoicePhase.RECORDING -> HudActivity.RECORDING
            v.phase in setOf(VoicePhase.STARTING, VoicePhase.FINISHING) || s.pending > 0 -> HudActivity.WORKING
            else -> HudActivity.IDLE
        }
        return HudContent(title, text, footer, (v.level * 350).toInt().coerceIn(0, 100), overlayEnabled.value && visible, problem != null, activity)
    }

    suspend fun close() {
        closing = true
        val active = voice.cancel()
        vr.close()
        active?.join()
        voice.awaitStopped()
        downloadJob?.cancelAndJoin()
        sender.close()
        typingJob.cancelAndJoin()
        withContext(Dispatchers.IO) { local?.close(); osc.close() }
        scope.cancel()
        apiKey = ""
    }
}
