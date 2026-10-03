package io.anysound

import io.anysound.audio.Microphone
import io.anysound.vr.HudContent
import io.anysound.vr.HudActivity
import io.anysound.vr.OverlayScene
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.sound.sampled.*
import kotlin.math.*
import kotlin.test.*

class AudioAndOverlayTest {
    @Test fun `JDK converts stereo 48k PCM to mono 16k while preserving audible signal`() {
        val format = AudioFormat(48000f, 16, 2, true, false)
        val pcm = ByteArray(48000 * 4)
        for (i in 0 until 48000) {
            val sample = (sin(2 * PI * 440 * i / 48000) * 12000).toInt()
            repeat(2) { channel -> pcm[i * 4 + channel * 2] = sample.toByte(); pcm[i * 4 + channel * 2 + 1] = (sample shr 8).toByte() }
        }
        assertTrue(AudioSystem.isConversionSupported(Microphone.targetFormat, format))
        AudioInputStream(ByteArrayInputStream(pcm), format, 48000).use { source ->
            AudioSystem.getAudioInputStream(Microphone.targetFormat, source).use { converted ->
                val output = converted.readAllBytes()
                assertTrue(output.size in 31000..33000)
                assertTrue(Microphone.rms(output) in 0.2f..0.35f)
            }
        }
        assertEquals(0f, Microphone.rms(ByteArray(640)))
    }

    @Test fun `Compose overlay updates text and meter without accumulating alpha or stale glyphs`() = runBlocking<Unit> {
        Surface.makeRasterN32Premul(OverlayScene.width, OverlayScene.height).use { surface ->
            OverlayScene(coroutineContext).use { scene ->
                val short = HudContent("正在聆听", "你好，世界 👋", footer = "松开结束", visible = true)
                var time = 1_000_000_000L
                fun frame(content: HudContent): IntArray {
                    repeat(45) { time += 16_666_667; scene.render(surface, content, time) }
                    return surface.makeImageSnapshot().use { image ->
                        checkNotNull(image.encodeToData()).use { data ->
                            val bitmap = ImageIO.read(ByteArrayInputStream(data.bytes))
                            val preview = File("build/reports/tests/overlay-${if (content.error) "error" else "normal"}.png")
                            preview.parentFile.mkdirs()
                            ImageIO.write(bitmap, "png", preview)
                            bitmap.getRGB(0, 0, bitmap.width, bitmap.height, null, 0, bitmap.width)
                        }
                    }
                }
                val initial = frame(short)
                assertEquals(0, initial[0] ushr 24)
                assertEquals(240, initial[200 * OverlayScene.width + 500] ushr 24)
                val long = frame(short.copy(title = "识别错误", text = "这是更长的实时字幕 Chinese and English 👋 ".repeat(15),
                    footer = "等待确认", error = true, level = 100))
                assertFalse(initial.contentEquals(long), "同一个 composition 必须响应新字幕")
                assertTrue((300..330).any { y -> initial[y * OverlayScene.width + 500] != long[y * OverlayScene.width + 500] }, "底部音量条必须更新")
                assertContentEquals(initial, frame(short), "长文变短后不应残留字形或累积透明度")
            }
            val closed = OverlayScene(coroutineContext)
            closed.close()
            closed.close()
            assertFailsWith<IllegalStateException> { closed.render(surface, HudContent("", ""), System.nanoTime()) }
        }
    }

    @Test fun `Compose entry exit and interrupted animations keep rendering until hidden then stop`() = runBlocking<Unit> {
        Surface.makeRasterN32Premul(OverlayScene.width, OverlayScene.height).use { surface ->
            OverlayScene(coroutineContext).use { scene ->
                var time = 1_000_000_000L
                fun frame(content: HudContent, frames: Int = 1): IntArray {
                    repeat(frames) { time += 16_666_667; scene.render(surface, content, time) }
                    return surface.makeImageSnapshot().use { image ->
                        checkNotNull(image.encodeToData()).use { data ->
                            val bitmap = ImageIO.read(ByteArrayInputStream(data.bytes))
                            bitmap.getRGB(0, 0, bitmap.width, bitmap.height, null, 0, bitmap.width)
                        }
                    }
                }
                fun alpha(pixels: IntArray) = pixels[200 * OverlayScene.width + 500] ushr 24
                val hidden = HudContent("", "")
                val visible = HudContent("已处理", "你好 世界", visible = true)
                assertTrue(frame(hidden, 3).all { it == 0 })
                assertFalse(scene.isVisible)
                frame(visible)
                assertTrue(alpha(frame(visible, 7)) in 1..239, "进场中应存在半透明帧")
                assertTrue(scene.isVisible)
                assertTrue(scene.hasInvalidations(), "内容相同也要继续播放动画")
                val settled = frame(visible, 45)
                assertEquals(240, alpha(settled))
                assertFalse(scene.hasInvalidations(), "静止面板应停止重绘")

                frame(hidden)
                assertTrue(scene.isVisible, "不能在收到隐藏目标时立即隐藏 OpenVR")
                val exiting = frame(hidden, 5)
                assertTrue(alpha(exiting) in 1..239, "退场需要真正绘制半透明帧")
                assertTrue(exiting.any { (it ushr 16 and 255) > 200 && (it ushr 8 and 255) > 200 && (it and 255) > 200 },
                    "隐藏目标清空实时状态后，旧字幕仍应随面板一起退场")
                assertTrue(scene.hasInvalidations())
                assertContentEquals(settled, frame(visible, 45), "退场中再次显示应平滑恢复，无透明度累积")

                val fullMeter = visible.copy(level = 100)
                frame(fullMeter)
                val midMeter = frame(fullMeter, 3)
                val endMeter = frame(fullMeter, 20)
                assertFalse(midMeter.contentEquals(settled))
                assertFalse(midMeter.contentEquals(endMeter), "音量条应经过中间宽度")

                val working = visible.copy(title = "正在整理文字…", activity = HudActivity.WORKING)
                val busyFrame = frame(working, 45)
                assertTrue(scene.hasInvalidations())
                assertFalse(busyFrame.contentEquals(frame(working, 8)), "处理指示应持续运动")
                assertTrue(frame(hidden, 45).all { it == 0 }, "退场后纹理应完全透明且没有残影")
                assertFalse(scene.isVisible)
                assertFalse(scene.hasInvalidations(), "隐藏后应销毁持续动画，停止请求帧")
                assertContentEquals(settled, frame(visible, 45), "重新显示不能带回旧内容")

                val recording = visible.copy(title = "正在聆听", activity = HudActivity.RECORDING)
                val listening = frame(recording, 45)
                assertFalse(listening.contentEquals(frame(recording, 8)), "录音指示应持续呼吸")
                val revised = recording.copy(text = "实时字幕立即更新")
                val liveUpdate = frame(revised)
                val liveSettled = frame(revised, 20)
                val textRegion = 70 * OverlayScene.width until 300 * OverlayScene.width
                assertTrue(textRegion.all { liveUpdate[it] == liveSettled[it] }, "流式字幕不应逐次触发淡入淡出")
                frame(hidden, 45)
                assertFalse(scene.isVisible)
                assertFalse(scene.hasInvalidations(), "隐藏后录音呼吸动画也必须停止")
            }
        }
    }
}
