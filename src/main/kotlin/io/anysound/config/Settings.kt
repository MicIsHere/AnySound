package io.anysound.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
enum class RecognitionMode { LOCAL, ALIBABA }

@Serializable
enum class LocalRecognitionMode { FAST, ACCURATE }

@Serializable
enum class LocalRecognitionLanguage(val code: String, val label: String) {
    CHINESE("zh", "中文（普通话）"),
    AUTO("auto", "自动识别"),
    ENGLISH("en", "英语"),
    CANTONESE("yue", "粤语"),
    JAPANESE("ja", "日语"),
    KOREAN("ko", "韩语"),
}

@Serializable
data class Settings(
    val mode: RecognitionMode = RecognitionMode.LOCAL,
    val modelDirectory: String = "",
    val localMode: LocalRecognitionMode = LocalRecognitionMode.FAST,
    val refinementModelDirectory: String = "",
    val localLanguage: LocalRecognitionLanguage = LocalRecognitionLanguage.CHINESE,
    val microphone: String = "",
    val autoSend: Boolean = true,
    val filterFillers: Boolean = true,
    val cleanPunctuation: Boolean = true,
    val fillers: List<String> = listOf("嗯", "呃"),
    val oscHost: String = "127.0.0.1",
    val oscPort: Int = 9000,
    val sendIntervalMs: Long = 3000,
    val notificationSound: Boolean = false,
    val workspaceId: String = "",
    val cloudEndpoint: String = "wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
    val cloudModel: String = "paraformer-realtime-v2",
    val overlayX: Float = 0f,
    val overlayY: Float = -0.25f,
    val overlayDistance: Float = 1f,
    val overlayWidth: Float = 0.7f,
    val overlayOpacity: Float = 0.9f,
) {
    fun validate() {
        require(oscHost.isNotBlank() && !oscHost.any { it.isWhitespace() }) { "请填写有效的 OSC 主机地址" }
        require(oscPort in 1..65535) { "OSC 端口必须在 1–65535 之间" }
        require(sendIntervalMs in 1500..30000) { "发送间隔应为 1.5–30 秒" }
        require(overlayX.isFinite() && overlayX in -2f..2f && overlayY.isFinite() && overlayY in -2f..2f) { "覆盖层位置应在 -2–2 米之间" }
        require(overlayDistance.isFinite() && overlayDistance in 0.3f..3f) { "覆盖层距离应为 0.3–3 米" }
        require(overlayWidth.isFinite() && overlayWidth in 0.2f..2f) { "覆盖层宽度应为 0.2–2 米" }
        require(overlayOpacity.isFinite() && overlayOpacity in 0.1f..1f) { "覆盖层透明度应为 0.1–1" }
        require(fillers.size <= 100 && fillers.all { it.isNotBlank() && it.length <= 30 }) { "停顿词最多 100 个，每个 1–30 字符" }
    }
}

class SettingsStore(val directory: Path = defaultDirectory()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = directory.resolve("settings.json")
    fun load(): Settings = if (Files.exists(file)) json.decodeFromString<Settings>(Files.readString(file)).also { it.validate() } else Settings()
    fun save(settings: Settings) {
        settings.validate()
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "settings-", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(settings))
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
    companion object {
        fun defaultDirectory(): Path = System.getProperty("anysound.dataDir")?.let(Path::of)
            ?: System.getenv("APPDATA")?.let { Path.of(it, "AnySound") }
            ?: Path.of(System.getProperty("user.home"), ".anysound")
    }
}
