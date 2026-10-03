package io.anysound

import io.anysound.vr.HudContent
import io.anysound.vr.OpenGlOverlay
import io.anysound.vr.OverlayScene
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.lwjgl.opengl.GL33C.*
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.Executors
import kotlin.test.*

/** Opt-in on a Windows PC with a real GL driver; does not require SteamVR or a headset. */
@EnabledIfSystemProperty(named = "anysound.testGpuOverlay", matches = "true")
class WindowsGpuOverlayTest {
    @Test fun `Compose draws into reusable GL texture and releases its context for reconnect`() {
        check(System.getProperty("os.name").startsWith("Windows")) { "GPU 覆盖层测试需要 Windows 显卡驱动" }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                repeat(2) {
                    OpenGlOverlay(dispatcher).use { renderer ->
                        val texture = renderer.textureId
                        var time = System.nanoTime()
                        val pixels = MemoryUtil.memAlloc(OverlayScene.width * OverlayScene.height * 4)
                        try {
                            fun meter(level: Int): IntArray {
                                repeat(45) {
                                    time += 16_666_667
                                    renderer.render(HudContent("正在聆听", "你好 GPU 👋", level = level, visible = true), time)
                                }
                                assertEquals(texture, renderer.textureId)
                                glBindTexture(GL_TEXTURE_2D, texture)
                                // Only the test reads pixels back; the application submits the GL handle.
                                glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
                                assertEquals(GL_NO_ERROR, glGetError())
                                assertEquals(0, pixels.get(3).toInt())
                                // GL rows start at the bottom, while Compose coordinates start at the top.
                                val background = ((OverlayScene.height - 1 - 200) * OverlayScene.width + 500) * 4
                                assertEquals(240, pixels.get(background + 3).toInt() and 255)
                                assertTrue((pixels.get(background).toInt() and 255) in 14..17)
                                return (300..330).map { y ->
                                    pixels.getInt(((OverlayScene.height - 1 - y) * OverlayScene.width + 500) * 4)
                                }.toIntArray()
                            }
                            val quiet = meter(0)
                            assertFalse(quiet.contentEquals(meter(100)), "音量条位置需符合 GL 的垂直方向")
                            assertContentEquals(quiet, meter(0), "纹理必须可重用且没有残影")
                            meter(25)
                            fun hasMeterColor(x: Int, top: Int, bottom: Int) = (top..bottom).any { y ->
                                val offset = ((OverlayScene.height - 1 - y) * OverlayScene.width + x) * 4
                                (pixels.get(offset).toInt() and 255) == 105 &&
                                    (pixels.get(offset + 1).toInt() and 255) == 210 &&
                                    (pixels.get(offset + 2).toInt() and 255) == 213
                            }
                            assertTrue(hasMeterColor(100, 300, 330), "四分之一音量条应从画面左下方开始")
                            assertFalse(hasMeterColor(900, 300, 330), "音量条不能左右镜像")
                            assertFalse(hasMeterColor(100, 54, 84), "音量条不能上下颠倒")
                        } finally { MemoryUtil.memFree(pixels) }
                    }
                }
            }
        }
    }
}
