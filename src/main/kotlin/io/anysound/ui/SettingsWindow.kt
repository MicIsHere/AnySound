package io.anysound.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import io.anysound.AppController
import io.anysound.audio.Microphone
import io.anysound.config.RecognitionMode
import io.anysound.config.LocalRecognitionMode
import io.anysound.config.LocalRecognitionLanguage
import io.anysound.speech.VoicePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser

@Composable
fun SettingsWindow(controller: AppController, close: () -> Unit) {
    val persisted by controller.settings.collectAsState()
    val voice by controller.voice.state.collectAsState()
    val download by controller.download.collectAsState()
    val appError by controller.error.collectAsState()
    var draft by remember { mutableStateOf(persisted) }
    var key by remember { mutableStateOf(controller.apiKey) }
    var host by remember { mutableStateOf(draft.oscHost) }
    var port by remember { mutableStateOf(draft.oscPort.toString()) }
    var interval by remember { mutableStateOf((draft.sendIntervalMs / 1000.0).toString()) }
    var fillers by remember { mutableStateOf(draft.fillers.joinToString("\n")) }
    var error by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val scope = rememberCoroutineScope()
    val busy = voice.phase in setOf(VoicePhase.STARTING, VoicePhase.RECORDING, VoicePhase.FINISHING, VoicePhase.PREVIEW)
    LaunchedEffect(Unit) { devices = withContext(Dispatchers.IO) { Microphone.devices() } }
    LaunchedEffect(persisted.modelDirectory) {
        if (persisted.modelDirectory.isNotBlank() && (draft.modelDirectory.isBlank() || download == "模型已就绪")) draft = draft.copy(modelDirectory = persisted.modelDirectory)
    }
    LaunchedEffect(persisted.refinementModelDirectory) {
        if (persisted.refinementModelDirectory.isNotBlank()) draft = draft.copy(refinementModelDirectory = persisted.refinementModelDirectory)
    }
    DialogWindow(onCloseRequest = close, title = "AnySound · 设置", state = rememberDialogState(width = 780.dp, height = 780.dp)) {
        AnySoundTheme {
            Surface(color = Backdrop, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("设置", style = MaterialTheme.typography.h5)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Section("语音识别") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draft.mode == RecognitionMode.LOCAL, { draft = draft.copy(mode = RecognitionMode.LOCAL) })
                                Text("本地离线", fontSize = 13.sp)
                                Spacer(Modifier.width(24.dp))
                                RadioButton(draft.mode == RecognitionMode.ALIBABA, { draft = draft.copy(mode = RecognitionMode.ALIBABA) })
                                Text("阿里云百炼", fontSize = 13.sp)
                            }
                            ToggleRow("松开后自动发送", "关闭后可以预览、修改，再按确认键发送", draft.autoSend) { draft = draft.copy(autoSend = it) }
                            var micOpen by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { micOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(devices.firstOrNull { it.first == draft.microphone }?.second ?: if (draft.microphone.isBlank()) "麦克风：系统默认" else "麦克风：已保存的设备（当前未找到）")
                                }
                                DropdownMenu(expanded = micOpen, onDismissRequest = { micOpen = false }) {
                                    DropdownMenuItem(onClick = { draft = draft.copy(microphone = ""); micOpen = false }) { Text("系统默认麦克风") }
                                    devices.forEach { (id, name) -> DropdownMenuItem(onClick = { draft = draft.copy(microphone = id); micOpen = false }) { Text(name) } }
                                }
                            }
                            TextButton(onClick = { scope.launch { devices = withContext(Dispatchers.IO) { Microphone.devices() } } }) { Text("刷新麦克风列表") }
                            if (draft.mode == RecognitionMode.LOCAL) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(draft.localMode == LocalRecognitionMode.FAST, { draft = draft.copy(localMode = LocalRecognitionMode.FAST) })
                                    Text("速度模式", fontSize = 13.sp)
                                    Spacer(Modifier.width(16.dp))
                                    RadioButton(draft.localMode == LocalRecognitionMode.ACCURATE, { draft = draft.copy(localMode = LocalRecognitionMode.ACCURATE) })
                                    Text("准确率模式", fontSize = 13.sp)
                                }
                                Text(if (draft.localMode == LocalRecognitionMode.ACCURATE)
                                    "边说边显示预览，松开后用 SenseVoice 重新识别整段，再发送最终文字。需要额外约 228 MiB 模型；等待时间和内存占用会增加，仍可能识别错误。"
                                    else "使用 Zipformer 实时识别，松开后发送当前模型的最终结果。占用较低，适合更看重响应速度的场景。", color = Muted, fontSize = 11.sp)
                                ModelDirectory("实时模型目录", draft.modelDirectory) { draft = draft.copy(modelDirectory = it) }
                                if (draft.localMode == LocalRecognitionMode.ACCURATE) {
                                    var languageOpen by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(onClick = { languageOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("首选语言：${draft.localLanguage.label}")
                                        }
                                        DropdownMenu(expanded = languageOpen, onDismissRequest = { languageOpen = false }) {
                                            LocalRecognitionLanguage.entries.forEach { language ->
                                                DropdownMenuItem(onClick = { draft = draft.copy(localLanguage = language); languageOpen = false }) {
                                                    Text(language.label)
                                                }
                                            }
                                        }
                                    }
                                    Text("主要说中文时建议选择中文，减少单字、短句被误判为英文。经常切换语言可选自动识别。保存后下次录音生效，仅影响最终精校文字。", color = Muted, fontSize = 11.sp)
                                    ModelDirectory("准确率模型目录", draft.refinementModelDirectory) { draft = draft.copy(refinementModelDirectory = it) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = { controller.downloadModel(draft.localMode) }, enabled = download == null || download == "模型已就绪") { Text("下载所需模型") }
                                    if (download != null && download != "模型已就绪") TextButton(onClick = controller::cancelDownload) { Text("取消下载") }
                                }
                                download?.let { Text(it, color = Accent, fontSize = 12.sp) }
                                Text("全程本地 CPU 推理；每个模型使用 2 个线程。下载完成后请保存设置，模型在首次录音时加载。", color = Muted, fontSize = 11.sp)
                            } else {
                                Text("在线模式会将按住期间的录音发送到阿里云百炼，并使用你的服务额度。", color = Muted, fontSize = 12.sp)
                                OutlinedTextField(key, { key = it }, label = { Text("API Key（仅当前会话）") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                                Input("业务空间 ID", draft.workspaceId) { draft = draft.copy(workspaceId = it) }
                                Input("WebSocket 服务端点", draft.cloudEndpoint) { draft = draft.copy(cloudEndpoint = it) }
                                Input("模型名称", draft.cloudModel) { draft = draft.copy(cloudModel = it) }
                                Text("默认使用北京地域。{WorkspaceId} 会替换为上方 ID；旧版账户可填写官方提供的完整服务端点。密钥也可通过 DASHSCOPE_API_KEY 环境变量提供。", color = Muted, fontSize = 11.sp)
                            }
                        }
                        Section("文字清理") {
                            ToggleRow("过滤独立停顿词", "按词边界处理，不删除普通词语中的字符", draft.filterFillers) { draft = draft.copy(filterFillers = it) }
                            OutlinedTextField(fillers, { fillers = it }, label = { Text("停顿词表，每行一个") }, modifier = Modifier.fillMaxWidth().height(100.dp))
                            ToggleRow("清理逗号和句号", "保留问号、感叹号、小数与网址", draft.cleanPunctuation) { draft = draft.copy(cleanPunctuation = it) }
                        }
                        Section("VRChat 文字发送") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(host, { host = it }, label = { Text("OSC 地址") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(port, { port = it }, label = { Text("端口") }, modifier = Modifier.width(130.dp), singleLine = true)
                            }
                            Input("分段发送间隔（秒，1.5–30）", interval) { interval = it }
                            ToggleRow("聊天提示音", "发送时触发 VRChat 的 ChatBox 提示音", draft.notificationSound) { draft = draft.copy(notificationSound = it) }
                            Text("长句自动拆分，每条最多 144 字符。发送成功表示数据已发出，不能证明 VRChat 已接收。", color = Muted, fontSize = 11.sp)
                        }
                        Section("覆盖层位置") {
                            SettingSlider("水平偏移", draft.overlayX, -1f..1f, "米") { draft = draft.copy(overlayX = it) }
                            SettingSlider("垂直偏移", draft.overlayY, -1f..1f, "米") { draft = draft.copy(overlayY = it) }
                            SettingSlider("距离", draft.overlayDistance, 0.3f..3f, "米") { draft = draft.copy(overlayDistance = it) }
                            SettingSlider("宽度", draft.overlayWidth, 0.2f..2f, "米") { draft = draft.copy(overlayWidth = it) }
                            SettingSlider("不透明度", draft.overlayOpacity, 0.1f..1f, "") { draft = draft.copy(overlayOpacity = it) }
                            Text("覆盖层跟随头部。保存后生效；实际位置请戴上头显检查。", color = Muted, fontSize = 11.sp)
                        }
                    }
                    (error ?: appError)?.let { Text(it, color = MaterialTheme.colors.error, fontSize = 12.sp) }
                    if (busy) Text("请先结束或取消当前语音，再保存设置。", color = Muted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = close) { Text("返回") }
                        Spacer(Modifier.width(12.dp))
                        Button(enabled = !busy, onClick = {
                            try {
                                val seconds = interval.toDoubleOrNull() ?: error("请填写有效的发送间隔")
                                require(seconds.isFinite() && seconds in 1.5..30.0) { "发送间隔应为 1.5–30 秒" }
                                val updated = draft.copy(oscHost = host.trim(), oscPort = port.toIntOrNull() ?: error("端口必须是整数"), sendIntervalMs = (seconds * 1000).toLong(), fillers = fillers.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct())
                                updated.validate()
                                error = null
                                controller.saveSettings(updated, key, close)
                            } catch (e: Exception) { error = e.message }
                        }) { Text("保存设置") }
                    }
                }
            }
        }
    }
}

@Composable private fun ModelDirectory(label: String, path: String, change: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(path, change, label = { Text(label) }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedButton(onClick = {
            val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "选择$label" }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) change(chooser.selectedFile.absolutePath)
        }) { Text("选择目录") }
    }
}

@Composable private fun Input(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, change: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(88.dp), fontSize = 12.sp)
        Slider(value, change, valueRange = range, modifier = Modifier.weight(1f))
        Text("%.2f %s".format(java.util.Locale.ROOT, value, unit), modifier = Modifier.width(85.dp).padding(start = 12.dp), fontSize = 12.sp, color = Muted)
    }
}
