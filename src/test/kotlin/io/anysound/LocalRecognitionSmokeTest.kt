package io.anysound

import io.anysound.audio.Microphone
import io.anysound.speech.LocalRecognizer
import io.anysound.speech.ModelDownloader
import io.anysound.speech.ModelFiles
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioSystem
import kotlin.test.*

/** Explicit opt-in: downloads ~190 MiB of the public model; never records the microphone. */
@EnabledIfSystemProperty(named = "anysound.testModel", matches = ".+")
class LocalRecognitionSmokeTest {
    @Test fun `official sample produces streaming text and model is reusable`() = runBlocking {
        val directory = Path.of(System.getProperty("anysound.testModel"))
        ModelDownloader.download(directory) { println(it) }
        val pcm = samplePcm(directory)
        LocalRecognizer(directory).use { recognizer ->
            repeat(2) {
                val partials = mutableListOf<String>()
                recognizer.start({ partial -> if (partial.isNotBlank()) partials += partial }, { error(it) })
                for (offset in pcm.indices step 640) recognizer.accept(pcm.copyOfRange(offset, minOf(pcm.size, offset + 640)))
                val text = recognizer.finish()
                println("Official sample transcript: $text")
                assertTrue(partials.isNotEmpty(), "Streaming partials must arrive before finish")
                assertTrue(text.length >= 5, "Expected substantive speech recognition")
                assertTrue(text.any { character -> character.code in 0x4E00..0x9FFF }, "Sample 0 contains Mandarin")
                recognizer.cancel()
            }
        }
    }

    @Test
    fun `silence and endpoint resets do not repeat a recognized sentence`() = runBlocking {
        val directory = Path.of(System.getProperty("anysound.testModel"))
        ModelDownloader.download(directory) { println(it) }
        val pcm = samplePcm(directory)
        LocalRecognizer(directory).use { recognizer ->
            fun feed(bytes: ByteArray) {
                for (offset in bytes.indices step 640) recognizer.accept(bytes.copyOfRange(offset, minOf(bytes.size, offset + 640)))
            }
            fun normalize(text: String) = text.replace(Regex("\\s+"), "")
            val silence = ByteArray(8 * 32000)
            recognizer.start({}, { error(it) })
            feed(silence)
            assertEquals("", recognizer.finish(), "纯静音不应生成文字")

            recognizer.start({}, { error(it) })
            feed(pcm)
            val baseline = normalize(recognizer.finish())
            assertTrue(baseline.isNotBlank())

            val partials = mutableListOf<String>()
            recognizer.start({ partials += it }, { error(it) })
            feed(silence); feed(pcm); feed(silence)
            assertEquals(baseline, normalize(recognizer.finish()), "停顿和松开收尾不应重复追加同一句")
            assertTrue(partials.all { normalize(it).length <= baseline.length }, "静音不能让字幕无限追加")

            recognizer.start({}, { error(it) })
            feed(pcm); feed(silence); feed(pcm); feed(silence)
            val repeated = recognizer.finish()
            // Decoder context can change ambiguous words after reset; count stable sentence boundaries.
            for (phrase in listOf("昨天是", "星期三")) {
                assertEquals(2, Regex(phrase).findAll(repeated).count(), "真正说了两遍的句子应各保留一次：$repeated")
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "anysound.testRefinementModel", matches = ".+")
    fun `accurate mode previews streaming text and refines final audio without leaking cancelled audio`() = runBlocking {
        val directory = Path.of(System.getProperty("anysound.testModel"))
        val refinement = Path.of(System.getProperty("anysound.testRefinementModel"))
        ModelDownloader.download(directory) { println(it) }
        ModelDownloader.download(refinement, ModelFiles.refinement) { println(it) }
        assertFailsWith<IllegalArgumentException> { ModelFiles.refinement.validate(directory) }
        val pcm = samplePcm(directory)
        LocalRecognizer(directory, refinement).use { recognizer ->
            val partials = mutableListOf<String>()
            fun feed() {
                recognizer.start({ if (it.isNotBlank()) partials += it }, { error(it) })
                for (offset in pcm.indices step 640) recognizer.accept(pcm.copyOfRange(offset, minOf(pcm.size, offset + 640)))
            }
            feed()
            assertTrue(partials.isNotEmpty(), "准确率模式仍应在松开前提供实时字幕")
            val started = System.nanoTime()
            val text = recognizer.finish()
            println("Accurate final (${(System.nanoTime() - started) / 1_000_000} ms): $text")
            println("Streaming preview: ${partials.last()}")
            assertTrue(text.length >= 5 && text.any { it.code in 0x4E00..0x9FFF })
            assertFalse(text.contains("<|"), "识别元数据不能混入聊天文本")
            feed()
            recognizer.cancel()
            recognizer.start({}, { error(it) })
            assertEquals("", recognizer.finish(), "取消后下一次空录音不能重用旧音频")
            feed()
            assertEquals(text, recognizer.finish(), "模型复用后最终结果应保持稳定")
        }
    }

    private fun samplePcm(directory: Path): ByteArray {
        val sample = directory.resolve("sample.wav")
        if (!Files.exists(sample)) {
            val files = ModelFiles.streaming
            val uri = URI("https://huggingface.co/csukuangfj/${files.model}/resolve/${files.revision}/test_wavs/0.wav")
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { client ->
                val response = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                check(response.statusCode() == 200)
                Files.write(sample, response.body())
            }
        }
        return AudioSystem.getAudioInputStream(sample.toFile()).use { audio ->
            AudioSystem.getAudioInputStream(Microphone.targetFormat, audio).use { it.readAllBytes() }
        }
    }
}
