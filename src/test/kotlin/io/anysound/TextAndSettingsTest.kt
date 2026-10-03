package io.anysound

import io.anysound.config.Settings
import io.anysound.config.SettingsStore
import io.anysound.config.LocalRecognitionMode
import io.anysound.config.LocalRecognitionLanguage
import io.anysound.text.TextProcessor
import io.anysound.ui.EnterSendGuard
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TextAndSettingsTest {
    @Test fun `clean pauses while retaining normal expression and numeric punctuation`() {
        assertEquals("我们走吧 你呢？", TextProcessor.cleanVoice("嗯，我们走吧。你呢？", Settings()))
        assertEquals("好啊 走吧 你呢！", TextProcessor.cleanVoice("好啊，走吧，你呢！", Settings()))
        assertEquals("余额 额外 嗯哼", TextProcessor.cleanVoice("余额，额外，嗯哼。", Settings(fillers = listOf("额", "嗯"))))
        assertEquals("版本 1.2.3 价格 3.14 网站 https://example.com/a?q=1.2！", TextProcessor.cleanVoice("版本 1.2.3，价格 3.14。网站 https://example.com/a?q=1.2！", Settings()))
        assertEquals("嗯，你好。", TextProcessor.cleanVoice("嗯，你好。", Settings(filterFillers = false, cleanPunctuation = false)))
        assertEquals("你好。", TextProcessor.cleanVoice("嗯，你好。", Settings(cleanPunctuation = false)))
        assertEquals("嗯 你好", TextProcessor.cleanVoice("嗯，你好。", Settings(filterFillers = false)))
    }

    @Test fun `split is lossless and preserves combining characters flags and families`() {
        val family = "👨‍👩‍👧‍👦"
        val text = "中文".repeat(70) + family + "e\u0301" + "🇨🇳" + " hello world.".repeat(25)
        val chunks = TextProcessor.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 144 })
        assertEquals(1, chunks.count { family in it })
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() || it.first() == '\u0301' })
        assertEquals(listOf("a".repeat(144)), TextProcessor.split("a".repeat(144)))
        assertEquals(listOf("a".repeat(144), "b"), TextProcessor.split("a".repeat(144) + "b"))
        assertTrue(TextProcessor.split("  \n").isEmpty())
        assertFailsWith<IllegalArgumentException> { TextProcessor.split("hello\u0000world") }
        assertFailsWith<IllegalArgumentException> { TextProcessor.split("a" + "\u0301".repeat(145)) }
    }

    @Test fun `prefer sentence boundaries without dropping whitespace`() {
        val text = "a".repeat(100) + ". " + "b".repeat(100)
        val chunks = TextProcessor.split(text)
        assertEquals("a".repeat(100) + ".", chunks.first())
        assertEquals(text, chunks.joinToString(""))
        val multiline = (1..30).joinToString("\n") { "第 $it 行" }
        val lines = TextProcessor.split(multiline)
        assertEquals(multiline, lines.joinToString(""))
        assertTrue(lines.all { it.count { c -> c == '\n' } <= 8 })
    }

    @Test fun `IME Enter only sends after composition has settled`() {
        var time = 0L
        val guard = EnterSendGuard { time }
        assertTrue(guard.canSend(false))
        assertFalse(guard.canSend(true))
        guard.compositionChanged(true, false)
        assertFalse(guard.canSend(false))
        time = 199
        assertFalse(guard.canSend(false))
        time = 200
        assertTrue(guard.canSend(false))
    }

    @Test fun `settings roundtrip and invalid settings are not written`(@TempDir dir: Path) {
        val store = SettingsStore(dir)
        val settings = Settings(modelDirectory = "C:\\模型 空间", localMode = LocalRecognitionMode.ACCURATE,
            refinementModelDirectory = "C:\\精校 模型", localLanguage = LocalRecognitionLanguage.ENGLISH, fillers = listOf("嗯", "呃", "um"), oscPort = 9100)
        store.save(settings)
        assertEquals(settings, store.load())
        assertFalse(Files.readString(dir.resolve("settings.json")).contains("apiKey"))
        assertFailsWith<IllegalArgumentException> { store.save(settings.copy(oscPort = 0)) }
        assertEquals(settings, store.load())
        assertFailsWith<IllegalArgumentException> { settings.copy(overlayWidth = Float.NaN).validate() }
        for (language in LocalRecognitionLanguage.entries) {
            store.save(settings.copy(localLanguage = language))
            assertEquals(language, store.load().localLanguage, "显式语言选择应保存，不被中文默认值覆盖")
        }
        Files.writeString(dir.resolve("settings.json"), """{"modelDirectory":"existing-model"}""")
        assertEquals(LocalRecognitionMode.FAST, store.load().localMode, "旧设置应继续使用已安装的速度模型")
        assertEquals("existing-model", store.load().modelDirectory)
        assertEquals(LocalRecognitionLanguage.CHINESE, store.load().localLanguage, "旧配置缺少语言选项时应优先中文")
    }
}
