package io.anysound

import io.anysound.vr.VrStatus
import io.anysound.vr.requestBindingUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.jupiter.api.Test
import kotlin.test.*

class BindingUiTest {
    @Test fun `button requests in-headset editor and clears previous warning`() {
        val status = MutableStateFlow(VrStatus(true, "SteamVR 已连接", true, "previous warning"))
        var calls = 0
        requestBindingUi(status) { showOnDesktop ->
            calls++
            assertFalse(showOnDesktop, "从桌面或 SteamVR 桌面 1 点击都应请求 VR 界面")
            assertTrue(status.value.bindingUiBusy)
            0
        }
        assertEquals(1, calls)
        assertTrue(status.value.connected)
        assertTrue(status.value.pttBound)
        assertFalse(status.value.bindingUiBusy)
        assertNull(status.value.bindingMessage)
    }

    @Test fun `unexpected OpenVR return preserves connection and persistent VR instructions`() {
        val status = MutableStateFlow(VrStatus(true, "SteamVR 已连接", true))
        requestBindingUi(status) { 345833472 }
        val warning = assertNotNull(status.value.bindingMessage)
        assertTrue(warning.contains("345833472"))
        assertTrue(warning.contains("头显内"))
        assertTrue(warning.contains("管理控制器绑定"))
        assertTrue(status.value.connected)
        assertTrue(status.value.pttBound)
        assertFalse(status.value.bindingUiBusy)
        status.update { it.withControllerInput(false) }
        assertEquals(warning, status.value.bindingMessage, "输入轮询不能擦除绑定提示")
        assertTrue(status.value.connected)
        assertFalse(status.value.pttBound)
    }

    @Test fun `native failures retain connection and allow retry`() {
        val status = MutableStateFlow(VrStatus(true, "SteamVR 已连接", true))
        for (failure in listOf(IllegalStateException("unavailable"), UnsatisfiedLinkError("missing"))) {
            requestBindingUi(status) { throw failure }
            assertTrue(status.value.connected)
            assertTrue(status.value.pttBound)
            assertFalse(status.value.bindingUiBusy)
            assertTrue(assertNotNull(status.value.bindingMessage).contains("管理控制器绑定"))
            requestBindingUi(status) { 0 }
            assertTrue(status.value.connected)
            assertNull(status.value.bindingMessage)
        }
    }

    @Test fun `disconnected requests never call OpenVR and cancellation is propagated`() {
        val status = MutableStateFlow(VrStatus(message = "SteamVR 已退出"))
        requestBindingUi(status) { fail("断开后不能调用 OpenVR") }
        assertEquals(VrStatus(message = "SteamVR 已退出"), status.value)
        status.value = VrStatus(connected = true)
        assertFailsWith<CancellationException> {
            requestBindingUi(status) { throw CancellationException() }
        }
        assertFalse(status.value.bindingUiBusy)
        assertNull(status.value.bindingMessage)
    }
}
