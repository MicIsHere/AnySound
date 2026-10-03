package io.anysound.vr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Opening an editor must not tear down the active VR input/overlay session. */
internal fun requestBindingUi(
    status: MutableStateFlow<VrStatus>,
    openNative: (showOnDesktop: Boolean) -> Int,
) {
    if (!status.value.connected) return
    status.update { it.copy(bindingUiBusy = true, bindingMessage = null) }
    try {
        val nativeError = try {
            // The desktop button is also used through SteamVR's Desktop 1.
            // Open the editor in the headset regardless of where it was clicked.
            val code = openNative(false)
            if (code == 0) null else "OpenVR $code"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { e.message ?: e.javaClass.simpleName }
        catch (e: LinkageError) { e.message ?: e.javaClass.simpleName }
        if (nativeError == null) return

        status.update {
            it.copy(bindingMessage = "SteamVR 返回异常（$nativeError）。若头显内未显示绑定页面，请在头显内打开 SteamVR → 设置 → 控制器 → 管理控制器绑定，选择 AnySound。连接仍保留，可重试。")
        }
    } finally {
        status.update { it.copy(bindingUiBusy = false) }
    }
}
