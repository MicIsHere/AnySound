package io.anysound.vr

import io.anysound.config.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import org.lwjgl.openvr.*
import org.lwjgl.openvr.VR.*
import org.lwjgl.openvr.VRApplications.*
import org.lwjgl.openvr.VRInput.*
import org.lwjgl.openvr.VROverlay.*
import org.lwjgl.openvr.VRSystem.*
import org.lwjgl.opengl.GL11C.glFlush
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memLengthNT1
import org.lwjgl.system.MemoryUtil.memUTF8
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class VrStatus(
    val connected: Boolean = false,
    val message: String = "未连接 SteamVR",
    val pttBound: Boolean = false,
    val bindingMessage: String? = null,
    val bindingUiBusy: Boolean = false,
) {
    internal fun withControllerInput(active: Boolean) = copy(
        connected = true,
        message = if (active) "SteamVR 已连接" else "已连接 · 请在 SteamVR 中绑定按住转文字",
        pttBound = active,
    )
}

class SteamVr(
    private val directory: Path,
    private val settings: () -> Settings,
    private val content: () -> HudContent,
    private val press: () -> Unit,
    private val release: () -> Unit,
    private val confirm: () -> Unit,
    private val onCancel: () -> Unit,
    private val toggle: () -> Unit,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "anysound-steamvr").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(VrStatus())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val bindingsRequested = AtomicBoolean(false)
    private var pressed = false

    /** Called once per action on the VR polling thread; also testable without a headset. */
    internal fun handleDigitalAction(name: String, active: Boolean, down: Boolean, changed: Boolean) {
        if (name == "ptt") {
            val held = active && down
            if (held && !pressed) press()
            if (!held && pressed) { if (active) release() else onCancel() }
            // Keep tracking the physical hold after cancel, until its release is observed.
            pressed = held
            mutable.update { it.withControllerInput(active) }
        } else if (active && changed && down) {
            when (name) { "confirm" -> confirm(); "cancel" -> onCancel(); "toggle_overlay" -> toggle() }
        }
    }

    @Synchronized fun connect() {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            mutable.value = VrStatus(message = "SteamVR 覆盖层需要 Windows PCVR；当前可使用桌面功能")
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            var initialized = false
            var overlay = 0L
            pressed = false
            try {
                bindingsRequested.set(false)
                mutable.value = VrStatus(message = "正在连接 SteamVR…")
                check(VR_IsRuntimeInstalled()) { "未安装 SteamVR" }
                check(VR_IsHmdPresent()) { "未检测到头显，请先启动 SteamVR 并连接头显" }
                MemoryStack.stackPush().use { stack ->
                    val error = stack.mallocInt(1)
                    val token = VR_InitInternal(error, EVRApplicationType_VRApplication_Overlay)
                    check(error[0] == 0) { "SteamVR 初始化失败（${error[0]}）" }
                    initialized = true
                    OpenVR.create(token)
                    val manifest = prepareManifest()
                    MemoryStack.stackPush().use { registration ->
                        // LWJGL's String overloads encode these paths as ASCII. Use UTF-8
                        // buffers so Chinese Windows user names and folders work too.
                        checkCode(VRApplications_AddApplicationManifest(registration.UTF8(manifest.toString()), false), "注册应用")
                        check(VRApplications_IsApplicationInstalled(appKey)) { "SteamVR 未登记 AnySound 应用，请重新连接" }
                        val pid = ProcessHandle.current().pid().toInt()
                        checkCode(VRApplications_IdentifyApplication(pid, appKey), "识别应用")
                        val registeredKey = registration.calloc(k_unMaxApplicationKeyLength)
                        checkCode(VRApplications_GetApplicationKeyByProcessId(pid, registeredKey), "核对应用身份")
                        val actualKey = readOpenVrString(registeredKey)
                        check(actualKey == appKey) { "SteamVR 应用身份不匹配：期望 $appKey，实际 ${actualKey.ifBlank { "（空）" }}" }
                        checkCode(VRInput_SetActionManifestPath(registration.UTF8(directory.resolve("actions.json").toAbsolutePath().toString())), "加载动作清单")
                        val registeredActions = registration.calloc(k_unMaxPropertyStringSize)
                        VRApplications_GetApplicationPropertyString(appKey,
                            EVRApplicationProperty_VRApplicationProperty_ActionManifestURL_String, registeredActions, error)
                        checkCode(error[0], "核对注册的动作清单")
                        check(readOpenVrString(registeredActions).isNotBlank()) { "SteamVR 中的 AnySound 缺少动作清单" }
                    }
                    val handle = stack.mallocLong(1)
                    checkCode(VRInput_GetActionSetHandle("/actions/anysound", handle), "读取动作组")
                    val actionSet = handle[0]
                    val actions = listOf("ptt", "confirm", "cancel", "toggle_overlay").associateWith { name ->
                        checkCode(VRInput_GetActionHandle("/actions/anysound/in/$name", handle), "读取 $name 动作")
                        handle[0]
                    }
                    checkCode(VROverlay_CreateOverlay(appKey, "AnySound", handle), "创建覆盖层")
                    overlay = handle[0]
                    val set = VRActiveActionSet.calloc(1, stack)
                    set[0].ulActionSet(actionSet)
                    val input = InputDigitalActionData.calloc(stack)
                    val event = VREvent.calloc(stack)
                    val transform = HmdMatrix34.calloc(stack)
                    checkCode(VROverlay_SetOverlayFlag(overlay, VROverlayFlags_IsPremultiplied, true), "设置覆盖层透明混合")
                    // OpenVR handles the OpenGL origin during import. Keep its normal
                    // bounds; reversing V here flips our BOTTOM_LEFT Skia surface twice.
                    val bounds = VRTextureBounds.calloc(stack).uMin(0f).vMin(0f).uMax(1f).vMax(1f)
                    checkCode(VROverlay_SetOverlayTextureBounds(overlay, bounds), "设置覆盖层纹理方向")
                    val texture = Texture.calloc(stack).eType(ETextureType_TextureType_OpenGL).eColorSpace(EColorSpace_ColorSpace_Gamma)
                    var lastSettings: Settings? = null
                    var lastContent: HudContent? = null
                    var lastPaint = 0L
                    OpenGlOverlay(dispatcher).use { renderer ->
                        texture.handle(renderer.textureId.toLong() and 0xffffffffL)
                        try {
                            while (currentCoroutineContext().isActive) {
                                renderer.pollEvents()
                                while (VRSystem_PollNextEvent(event)) {
                                    if (event.eventType() == EVREventType_VREvent_Quit) {
                                        VRSystem_AcknowledgeQuit_Exiting()
                                        error("SteamVR 已退出")
                                    }
                                }
                                checkCode(VRInput_UpdateActionState(set, VRActiveActionSet.SIZEOF), "更新控制器输入")
                                for ((name, action) in actions) {
                                    checkCode(VRInput_GetDigitalActionData(action, input, k_ulInvalidInputValueHandle), "读取控制器输入")
                                    handleDigitalAction(name, input.bActive(), input.bState(), input.bChanged())
                                }
                                if (bindingsRequested.getAndSet(false)) {
                                    requestBindingUi(mutable) { showOnDesktop ->
                                        openVrBindingUi(appKey, showOnDesktop)
                                    }
                                }
                                val prefs = settings()
                                if (prefs != lastSettings) {
                                    transform.m(0, 1f).m(5, 1f).m(10, 1f).m(3, prefs.overlayX).m(7, prefs.overlayY).m(11, -prefs.overlayDistance)
                                    checkCode(VROverlay_SetOverlayTransformTrackedDeviceRelative(overlay, k_unTrackedDeviceIndex_Hmd, transform), "定位覆盖层")
                                    checkCode(VROverlay_SetOverlayWidthInMeters(overlay, prefs.overlayWidth), "调整覆盖层大小")
                                    checkCode(VROverlay_SetOverlayAlpha(overlay, prefs.overlayOpacity), "调整覆盖层透明度")
                                    lastSettings = prefs
                                }
                                val view = content()
                                val now = System.nanoTime()
                                if ((view != lastContent || renderer.hasInvalidations()) && now - lastPaint >= 16_666_667) {
                                    // Feed hidden targets to Compose too; hide OpenVR only after its exit animation.
                                    renderer.render(view, now)
                                    if (renderer.isVisible) {
                                        checkCode(VROverlay_SetOverlayTexture(overlay, texture), "提交 Compose 覆盖层纹理（请确认与 SteamVR 使用同一显卡）")
                                        // A hidden offscreen context never swaps buffers to flush a queued transfer.
                                        glFlush()
                                        checkCode(VROverlay_ShowOverlay(overlay), "显示覆盖层")
                                    } else checkCode(VROverlay_HideOverlay(overlay), "隐藏覆盖层")
                                    lastPaint = now
                                    lastContent = view
                                }
                                delay(8)
                            }
                        } finally {
                            runCatching { VROverlay_HideOverlay(overlay) }
                            runCatching { VROverlay_ClearOverlayTexture(overlay) }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = VrStatus(message = e.message ?: "SteamVR 连接失败") }
            catch (_: LinkageError) { mutable.value = VrStatus(message = "SteamVR 原生库缺失，请使用完整的 Windows x64 安装包") }
            finally {
                bindingsRequested.set(false)
                // An unqualified cancel() here resolves to CoroutineScope.cancel(), not the recording callback.
                if (pressed || initialized) onCancel()
                if (initialized) {
                    if (overlay != 0L) runCatching { VROverlay_DestroyOverlay(overlay) }
                    runCatching { OpenVR.destroy() }
                    runCatching { VR_ShutdownInternal() }
                }
                mutable.update { it.copy(connected = false, pttBound = false, bindingUiBusy = false) }
            }
        }
    }
    fun openBindings() { if (state.value.connected) bindingsRequested.set(true) else connect() }
    suspend fun close() { job?.cancelAndJoin(); scope.cancel(); dispatcher.close() }

    internal fun prepareManifest(): Path {
        Files.createDirectories(directory)
        for (file in listOf("actions.json", "bindings_touch.json")) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/steamvr/$file")).use { it.readBytes() }
            Files.write(directory.resolve(file), bytes)
        }
        val packaged = System.getProperty("jpackage.app-path")
        val binary = packaged ?: Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString()
        val args = if (packaged != null) "" else buildList {
            add("-Dfile.encoding=UTF-8")
            add("--enable-native-access=ALL-UNNAMED")
            for (property in listOf("compose.application.resources.dir", "anysound.dataDir")) {
                System.getProperty(property)?.let { add("-D$property=$it") }
            }
            // SteamVR may launch us with a different working directory.
            val classPath = System.getProperty("java.class.path").split(File.pathSeparator)
                .joinToString(File.pathSeparator) { Path.of(it.ifEmpty { "." }).toAbsolutePath().normalize().toString() }
            addAll(listOf("-cp", classPath, "tech.origin.launch.Main"))
        }.joinToString(" ", transform = ::quoteWindows)
        val manifest = buildJsonObject {
            put("applications", buildJsonArray {
                add(buildJsonObject {
                    put("app_key", appKey); put("launch_type", "binary"); put("binary_path_windows", binary)
                    put("arguments", args); put("is_dashboard_overlay", true)
                    put("working_directory", Path.of(System.getProperty("user.dir")).toAbsolutePath().toString())
                    put("action_manifest_path", directory.resolve("actions.json").toAbsolutePath().toString())
                    put("strings", buildJsonObject { put("en_us", buildJsonObject { put("name", "AnySound"); put("description", "Voice and keyboard to VRChat ChatBox") }) })
                })
            })
        }
        return directory.resolve("anysound.vrmanifest").toAbsolutePath().also { Files.writeString(it, manifest.toString()) }
    }
    companion object {
        const val appKey = "io.anysound.desktop"
        private fun checkCode(code: Int, action: String) { check(code == 0) { "$action 失败（OpenVR $code）" } }
        private fun quoteWindows(value: String): String = "\"" + value.replace(Regex("(\\\\*)\"")) { it.groupValues[1].repeat(2) + "\\\"" }.replace(Regex("\\\\+$")) { it.value.repeat(2) } + "\""
    }
}

// Native output APIs leave position/limit unchanged; decode only through the first NUL.
internal fun readOpenVrString(buffer: ByteBuffer): String = memUTF8(buffer, memLengthNT1(buffer))
