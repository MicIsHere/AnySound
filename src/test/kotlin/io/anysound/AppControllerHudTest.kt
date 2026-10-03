package io.anysound

import io.anysound.config.Settings
import io.anysound.config.SettingsStore
import io.anysound.speech.VoicePhase
import io.anysound.vr.HudActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerHudTest {
    @Test fun `cancellation shows a timed notice instead of the initial screen and new work replaces it`(@TempDir directory: Path) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
                val store = SettingsStore(directory)
                store.save(Settings(oscPort = receiver.localPort))
                val controller = AppController(store)
                try {
                    val idleVoice = controller.voice.state.value
                    val idleSender = controller.sender.state.value
                    controller.cancel(); runCurrent()
                    assertSame(idleVoice, controller.voice.state.value, "空闲时取消不能生成提示")
                    assertSame(idleSender, controller.sender.state.value)
                    assertFalse(controller.hud(System.currentTimeMillis() + 3000).visible)

                    controller.sender.enqueue("待发送的文字".repeat(40), controller.settings.value)
                    controller.cancel()
                    val cancelledState = controller.voice.state.value
                    controller.cancel()
                    assertSame(cancelledState, controller.voice.state.value, "队列清理完成前重复取消也不能刷新提示")
                    runCurrent()
                    controller.sender.state.first { it.pending == 0 }
                    val cancelledAt = controller.voice.state.value.cancelledAt
                    val notice = controller.hud(cancelledAt)
                    assertEquals(VoicePhase.CANCELLED, controller.voice.state.value.phase)
                    assertEquals("已取消", notice.title)
                    assertTrue(notice.text.contains("尚未发送"))
                    assertTrue(notice.footer.contains("再次按住"))
                    assertEquals(HudActivity.IDLE, notice.activity)
                    assertFalse(notice.error)
                    assertTrue(notice.visible)
                    assertTrue(controller.hud(cancelledAt + 1999).visible)
                    val hidden = controller.hud(cancelledAt + 2000)
                    assertFalse(hidden.visible)
                    assertEquals(notice.text, hidden.text, "退场时不能先变回初始文案")
                    controller.cancel(); runCurrent()
                    assertSame(cancelledState, controller.voice.state.value, "空闲时重复取消不能刷新提示时间")
                    assertFalse(controller.hud(cancelledAt + 2000).visible)

                    controller.overlayEnabled.value = false
                    assertFalse(controller.hud(cancelledAt).visible, "取消提示仍应遵守隐藏覆盖层开关")
                    controller.overlayEnabled.value = true
                    val queued = CompletableDeferred<Unit>()
                    controller.sendTyped("新的手动文字") { queued.complete(Unit) }
                    queued.await()
                    assertNotEquals("已取消", controller.hud(cancelledAt).title, "新消息不能被旧取消提示遮住")
                    controller.sender.state.first { it.pending == 0 }
                    runCurrent()
                    val completedVoice = controller.voice.state.value
                    val completedSend = controller.sender.state.value
                    assertTrue(completedSend.current > 0)
                    controller.cancel(); runCurrent()
                    assertSame(completedVoice, controller.voice.state.value, "已发送消息不算待取消内容")
                    assertSame(completedSend, controller.sender.state.value, "空闲取消不能重置已完成队列并触发覆盖层")
                    assertFalse(controller.hud(System.currentTimeMillis() + 3000).visible)

                    controller.sender.enqueue("新的待发消息", controller.settings.value)
                    controller.cancel()
                    assertEquals("已取消", controller.hud().title)
                    assertTrue(controller.hud().visible, "再次取消应重新显示提示")
                    assertEquals(HudActivity.IDLE, controller.hud().activity, "等待旧发送任务退出时也应立即显示取消提示")
                    controller.press()
                    assertNotEquals("已取消", controller.hud().title, "再次按住应立即替换提示，不等待停留时间")
                } finally { controller.close() }
            }
        } finally { Dispatchers.resetMain() }
    }
}
