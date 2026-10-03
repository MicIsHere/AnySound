package io.anysound.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import io.anysound.AppController
import io.anysound.config.RecognitionMode
import io.anysound.config.LocalRecognitionMode
import io.anysound.speech.VoicePhase
import io.anysound.text.TextProcessor
import io.anysound.vr.OverlayPanel
import io.anysound.vr.OverlayScene
import io.anysound.vr.HudActivity
import io.anysound.vr.HudContent
import kotlinx.coroutines.delay

val Accent = Color(0xFF8CE1D2)
val Backdrop = Color(0xFF10151C)
val Panel = Color(0xFF1A222D)
val Muted = Color(0xFF9DAEBF)

@Composable
fun AnySoundTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = darkColors(primary = Accent, secondary = Accent, background = Backdrop,
        surface = Panel, onPrimary = Backdrop, onSurface = Color(0xFFE9EEF5)),
        typography = Typography(defaultFontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif), content = content)
}

@Composable
fun MainScreen(controller: AppController, closing: Boolean) {
    val settings by controller.settings.collectAsState()
    val voice by controller.voice.state.collectAsState()
    val sending by controller.sender.state.collectAsState()
    val vr by controller.vr.state.collectAsState()
    val error by controller.error.collectAsState()
    val download by controller.download.collectAsState()
    val overlay by controller.overlayEnabled.collectAsState()
    var field by remember { mutableStateOf(TextFieldValue()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    val ime = remember { EnterSendGuard() }
    val recording = voice.phase in setOf(VoicePhase.STARTING, VoicePhase.RECORDING, VoicePhase.FINISHING)
    val missingLocalModels = settings.modelDirectory.isBlank() ||
        (settings.localMode == LocalRecognitionMode.ACCURATE && settings.refinementModelDirectory.isBlank())
    val parts = remember(field.text) { runCatching { TextProcessor.split(field.text).size }.getOrDefault(0) }
    val send = {
        val submitted = field.text
        controller.sendTyped(submitted) { if (field.text == submitted) field = TextFieldValue() }
    }
    Surface(color = Backdrop, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("AnySound", fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { settingsOpen = true }, enabled = !closing) { Text("设置") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wideLayout = maxWidth >= 800.dp
                val inputPanels: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MainPanel("文字输入") {
                            OutlinedTextField(
                                value = field,
                                onValueChange = { next ->
                                    ime.compositionChanged(field.composition != null, next.composition != null)
                                    field = next; controller.edited(next.text.isNotBlank())
                                },
                                placeholder = { Text("输入消息…", color = Muted) },
                                modifier = Modifier.fillMaxWidth().height(144.dp).onPreviewKeyEvent { event ->
                                    if (!closing && event.key == Key.Enter && !event.isShiftPressed && event.type == KeyEventType.KeyDown) {
                                        if (ime.canSend(field.composition != null)) { send(); true } else false
                                    } else false
                                },
                                textStyle = TextStyle(fontSize = 18.sp, color = MaterialTheme.colors.onSurface),
                                enabled = !closing,
                                shape = RoundedCornerShape(8.dp),
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    backgroundColor = Backdrop.copy(alpha = 0.6f),
                                    unfocusedBorderColor = Color(0xFF33404E),
                                ),
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                                    Text(if (parts > 1) "${field.text.length} 字符 · 分 $parts 条发送" else "${field.text.length} / 144", color = Muted, fontSize = 12.sp)
                                    Text("Enter 发送", color = Muted, fontSize = 11.sp)
                                }
                                Button(onClick = { send() }, enabled = field.text.isNotBlank() && !closing,
                                    shape = RoundedCornerShape(8.dp), modifier = Modifier.widthIn(min = 96.dp)) { Text("发送") }
                            }
                        }
                        val recognitionLabel = if (settings.mode == RecognitionMode.LOCAL) {
                            if (settings.localMode == LocalRecognitionMode.ACCURATE) "本地 · 准确率 · ${settings.localLanguage.label}" else "本地 · 速度"
                        } else "百炼在线"
                        MainPanel("语音输入", recognitionLabel) {
                            if (settings.mode == RecognitionMode.LOCAL && missingLocalModels) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("未配置本地模型", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { settingsOpen = true }, enabled = !closing) { Text("配置模型") }
                                }
                            }
                            download?.takeUnless { it == "模型已就绪" }?.let { Text(it, color = Accent, fontSize = 12.sp) }
                            val phaseText = when (voice.phase) {
                                VoicePhase.IDLE -> "待机"
                                VoicePhase.STARTING -> "准备中"
                                VoicePhase.RECORDING -> "录音中"
                                VoicePhase.FINISHING -> if (settings.mode == RecognitionMode.LOCAL && settings.localMode == LocalRecognitionMode.ACCURATE) "精校中" else "识别中"
                                VoicePhase.PREVIEW -> "待确认"
                                VoicePhase.DONE -> if (voice.text.isBlank()) "未检测到语音" else "转写完成"
                                VoicePhase.CANCELLED -> "已取消"
                                VoicePhase.ERROR -> "识别失败"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (voice.phase in setOf(VoicePhase.STARTING, VoicePhase.FINISHING)) {
                                    CircularProgressIndicator(Modifier.size(12.dp), color = Accent, strokeWidth = 2.dp)
                                } else {
                                    Box(Modifier.size(6.dp).background(if (recording) Accent else Muted, RoundedCornerShape(3.dp)))
                                }
                                Text(phaseText, color = if (recording) Accent else Muted, fontSize = 12.sp)
                                if (voice.phase == VoicePhase.RECORDING) {
                                    LinearProgressIndicator(progress = (voice.level * 5).coerceIn(0f, 1f),
                                        modifier = Modifier.padding(start = 8.dp).weight(1f).height(3.dp),
                                        color = Accent, backgroundColor = Color(0xFF2B3947))
                                }
                            }
                            if (voice.phase == VoicePhase.PREVIEW) {
                                OutlinedTextField(voice.text, { controller.voice.editPreview(it) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp),
                                    label = { Text("待发送文字") }, enabled = !closing, shape = RoundedCornerShape(8.dp))
                            } else {
                                SelectionContainer {
                                    Text(voice.text.ifBlank { "转写结果" },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 160.dp).verticalScroll(rememberScrollState()),
                                        fontSize = 17.sp, lineHeight = 25.sp,
                                        color = if (voice.text.isBlank()) Muted.copy(alpha = 0.65f) else MaterialTheme.colors.onSurface)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (voice.phase == VoicePhase.PREVIEW) {
                                    Button(onClick = controller::confirm, enabled = voice.text.isNotBlank() && !closing,
                                        shape = RoundedCornerShape(8.dp)) { Text("确认发送") }
                                } else {
                                    HoldToTalk(controller, !closing && voice.phase != VoicePhase.FINISHING &&
                                        !(settings.mode == RecognitionMode.LOCAL && missingLocalModels),
                                        voice.phase in setOf(VoicePhase.STARTING, VoicePhase.RECORDING))
                                    Text(if (settings.autoSend) "松开发送" else "松开预览", color = Muted, fontSize = 12.sp)
                                }
                                Spacer(Modifier.weight(1f))
                                if (recording || voice.phase == VoicePhase.PREVIEW) {
                                    TextButton(onClick = controller::cancel, enabled = !closing) { Text("取消") }
                                }
                            }
                        }
                    }
                }
                val toolPanels: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MainPanel("SteamVR") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(6.dp).background(if (vr.connected) Accent else Muted, RoundedCornerShape(3.dp)))
                                Text(if (vr.connected) { if (vr.pttBound) "已连接" else "已连接 · 待绑定控制器" } else vr.message,
                                    color = if (vr.connected) Accent else Muted, fontSize = 13.sp)
                            }
                            vr.bindingMessage?.let { Text(it, color = Muted, fontSize = 12.sp) }
                            OutlinedButton(onClick = { if (vr.connected) controller.vr.openBindings() else controller.vr.connect() },
                                enabled = !closing && !vr.bindingUiBusy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                                Text(if (vr.bindingUiBusy) "正在打开…" else if (vr.connected) "VR 按键绑定" else "连接 SteamVR")
                            }
                            Divider(color = Color(0xFF2D3947))
                            CompactSwitch("字幕覆盖层", overlay, !closing) { controller.toggleOverlay() }
                            TextButton(onClick = { previewOpen = true }, enabled = !closing, contentPadding = PaddingValues(0.dp)) { Text("覆盖层预览", fontSize = 12.sp) }
                        }
                        MainPanel("输出优化", "仅语音") {
                            CompactSwitch("过滤停顿词", settings.filterFillers, !recording && !closing) { controller.setFilters(fillers = it) }
                            CompactSwitch("清理逗号和句号", settings.cleanPunctuation, !recording && !closing) { controller.setFilters(punctuation = it) }
                        }
                    }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    (error ?: voice.error ?: sending.error)?.let { message ->
                        Surface(color = Color(0xFF3E2830), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, color = Color(0xFFFFB7B7), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (error != null) TextButton(onClick = controller::dismissError) { Text("关闭") }
                            }
                        }
                    }
                    if (wideLayout) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Box(Modifier.weight(1f)) { inputPanels() }
                            Box(Modifier.width(260.dp)) { toolPanels() }
                        }
                    } else {
                        inputPanels()
                        toolPanels()
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Divider(color = Color(0xFF283342))
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("OSC  ${settings.oscHost}:${settings.oscPort}", color = Muted, fontSize = 11.sp,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    when {
                        closing -> Text("正在关闭…", color = Muted, fontSize = 12.sp)
                        sending.pending > 0 -> {
                            Text("待发 ${sending.pending} 条", color = Accent, fontSize = 12.sp)
                            TextButton(onClick = controller::cancel) { Text("停止发送", fontSize = 12.sp) }
                        }
                        sending.current > 0 -> Text("已发出 ${sending.total} 条", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    if (settingsOpen) SettingsWindow(controller) { settingsOpen = false }
    if (previewOpen) DialogWindow(onCloseRequest = { previewOpen = false }, title = "AnySound · 覆盖层预览", state = rememberDialogState(width = 900.dp, height = 400.dp)) {
        AnySoundTheme {
            var content by remember { mutableStateOf(controller.hud()) }
            var demo by remember { mutableStateOf(false) }
            LaunchedEffect(demo) {
                if (demo) {
                    content = HudContent("", "")
                    delay(300)
                    content = HudContent("正在聆听 · 松开发送", "你好", "示例字幕 · 录音中", visible = true, activity = HudActivity.RECORDING)
                    for ((index, level) in listOf(15, 45, 25, 75, 40, 90, 30, 55).withIndex()) {
                        content = content.copy(level = level, text = if (index < 3) "你好" else "你好，我们一起去下一个世界吧")
                        delay(240)
                    }
                    content = content.copy(title = "正在整理文字…", footer = "示例字幕 · 识别中", level = 0, activity = HudActivity.WORKING)
                    delay(1000)
                    content = content.copy(title = "已处理", text = "你好 我们一起去下一个世界吧！", footer = "示例字幕 · 完成后自动隐藏", activity = HudActivity.IDLE)
                    delay(1600)
                    content = content.copy(visible = false)
                    delay(650)
                    demo = false
                } else while (true) { content = controller.hud(); delay(100) }
            }
            Surface(color = Backdrop, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (demo) "正在演示示例字幕" else "实时预览 · 覆盖层隐藏时画面留空", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { demo = !demo }) { Text(if (demo) "返回实时预览" else "演示动画") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BoxWithConstraints(Modifier.aspectRatio(OverlayScene.width.toFloat() / OverlayScene.height)) {
                            CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth.toFloat() / OverlayScene.width)) {
                                OverlayPanel(content)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldToTalk(controller: AppController, enabled: Boolean, recording: Boolean) {
    var held by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (held) controller.cancel() } }
    Box(Modifier
        .onFocusChanged { if (!it.isFocused && held) { held = false; controller.cancel() } }
        .onPreviewKeyEvent {
            if (enabled && it.key == Key.Spacebar) {
                if (it.type == KeyEventType.KeyDown) { held = true; controller.press() }
                if (it.type == KeyEventType.KeyUp) { held = false; controller.voice.release() }
                true
            } else false
        }
        .focusable(enabled)
        .semantics {
            contentDescription = "按住说话，松开结束；也可聚焦后按住空格"
            role = Role.Button
            if (!enabled) disabled()
        }
        .background(if (recording) Accent else Color(0xFF2B4148).copy(alpha = if (enabled) 1f else 0.4f), RoundedCornerShape(8.dp))
        .pointerInput(enabled) {
            if (enabled) detectTapGestures(onPress = {
                held = true
                controller.press()
                try { if (tryAwaitRelease()) controller.voice.release() else controller.cancel() }
                finally { held = false }
            })
        }.padding(horizontal = 22.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(if (recording) "松开结束" else "按住说话", color = if (!enabled) Muted.copy(alpha = 0.5f) else if (recording) Backdrop else Accent, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Section(title: String, caption: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF283342)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            caption?.let { Text(it, color = Muted, fontSize = 11.sp) }
            content()
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 13.sp); Text(subtitle, color = Muted, fontSize = 10.sp) }
        Switch(checked = value, onCheckedChange = change, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = Accent))
    }
}

@Composable
private fun MainPanel(title: String, detail: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF283342)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                detail?.let { Text(it, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
            content()
        }
    }
}

@Composable
private fun CompactSwitch(title: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = Accent))
    }
}

/** IME commit and the physical Enter can arrive in either order on Windows. */
class EnterSendGuard(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var lastCommit: Long? = null
    fun compositionChanged(wasComposing: Boolean, composing: Boolean) { if (wasComposing && !composing) lastCommit = now() }
    fun canSend(composing: Boolean): Boolean = !composing && (lastCommit?.let { now() - it >= 200 } ?: true)
}
