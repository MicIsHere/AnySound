package io.anysound

import io.anysound.audio.AudioSource
import io.anysound.speech.*
import io.anysound.config.Settings
import io.anysound.config.LocalRecognitionMode
import io.anysound.vr.HudContent
import io.anysound.vr.SteamVr
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {
    private class FakeAudio : AudioSource {
        var pcm: (ByteArray) -> Unit = {}
        var level: (Float) -> Unit = {}
        var failure: (String) -> Unit = {}
        var stopped = false
        override fun start(onAudio: (ByteArray) -> Unit, onLevel: (Float) -> Unit, onError: (String) -> Unit) { pcm = onAudio; level = onLevel; failure = onError }
        override fun stop() { stopped = true }
        fun speak() { level(0.2f); pcm(ByteArray(640)) }
    }
    private class FakeRecognizer : SpeechRecognizer {
        var callback: (String) -> Unit = {}
        var starts = 0
        var finishes = 0
        var cancels = 0
        var result = "嗯，我们走吧。你呢？"
        var finishFailure: String? = null
        override fun start(onPartial: (String) -> Unit, onError: (String) -> Unit) { starts++; callback = onPartial }
        override fun accept(pcm: ByteArray) { callback("嗯，我们") }
        override fun finish(): String { finishes++; finishFailure?.let { error(it) }; return result }
        override fun cancel() { cancels++ }
        override fun close() { }
    }

    @Test fun `holding updates preview but only release sends one final result`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer(); val sent = mutableListOf<String>()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { text, _ -> sent += text }, StandardTestDispatcher(testScheduler))
        voice.press(Settings()); voice.press(Settings()); runCurrent()
        assertEquals(1, engine.starts)
        audio.speak(); runCurrent()
        assertEquals(VoicePhase.RECORDING, voice.state.value.phase)
        assertTrue(sent.isEmpty())
        assertEquals("我们", voice.state.value.text)
        voice.release(); voice.release(); runCurrent()
        assertEquals(listOf("我们走吧 你呢？"), sent)
        assertEquals(1, engine.finishes)
        assertTrue(audio.stopped)
        assertEquals(VoicePhase.DONE, voice.state.value.phase)
    }

    @Test fun `cancel discards late callback and never finishes or sends`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer(); val sent = mutableListOf<String>()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { text, _ -> sent += text }, StandardTestDispatcher(testScheduler))
        voice.press(Settings()); runCurrent(); audio.speak(); runCurrent()
        val late = engine.callback
        voice.cancel(); runCurrent(); late("不应出现的迟到结果"); voice.release(); runCurrent()
        assertEquals(VoicePhase.CANCELLED, voice.state.value.phase)
        assertEquals("", voice.state.value.text)
        assertTrue(sent.isEmpty())
        assertEquals(0, engine.finishes)
    }

    @Test fun `cancel ignores idle and completed sessions but clears a pending preview once`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer(); val sent = mutableListOf<String>()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { text, _ -> sent += text }, StandardTestDispatcher(testScheduler))
        val idle = voice.state.value
        voice.cancel(); runCurrent()
        assertSame(idle, voice.state.value)

        voice.press(Settings(autoSend = false)); runCurrent(); audio.speak(); runCurrent(); voice.release(); runCurrent()
        assertEquals(VoicePhase.PREVIEW, voice.state.value.phase)
        voice.cancel(); runCurrent()
        val cancelled = voice.state.value
        assertEquals(VoicePhase.CANCELLED, cancelled.phase)
        assertEquals("", cancelled.text)
        voice.cancel(); voice.confirm(); voice.release(); runCurrent()
        assertSame(cancelled, voice.state.value)
        assertTrue(sent.isEmpty())

        voice.press(Settings()); runCurrent(); audio.speak(); runCurrent(); voice.release(); runCurrent()
        val completed = voice.state.value
        assertEquals(VoicePhase.DONE, completed.phase)
        voice.cancel(); runCurrent()
        assertSame(completed, voice.state.value)
        assertEquals(listOf("我们走吧 你呢？"), sent)
    }

    @Test fun `SteamVR cancel keeps input polling alive and the next hold records without reconnecting`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer(); val sent = mutableListOf<String>()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { text, _ -> sent += text }, StandardTestDispatcher(testScheduler))
        val vr = SteamVr(Path.of("unused-test-manifest"), { Settings() }, { HudContent("", "") },
            { voice.press(Settings()) }, { voice.release() }, {}, { voice.cancel() }, {})
        val events = Channel<() -> Unit>(Channel.UNLIMITED)
        val polling = backgroundScope.launch { for (event in events) event() }
        fun action(name: String, down: Boolean, changed: Boolean = true, active: Boolean = true) {
            check(events.trySend { vr.handleDigitalAction(name, active, down, changed) }.isSuccess)
            runCurrent()
        }
        try {
            action("ptt", false) // SteamVR long-press threshold has not been reached yet.
            assertEquals(0, engine.starts)
            action("ptt", true)
            audio.speak(); runCurrent()
            assertEquals(VoicePhase.RECORDING, voice.state.value.phase)
            action("cancel", true)
            assertEquals(VoicePhase.CANCELLED, voice.state.value.phase)
            assertTrue(polling.isActive, "取消录音不能停止 SteamVR 输入循环")
            assertTrue(vr.state.value.connected)
            assertTrue(sent.isEmpty())

            repeat(3) { action("ptt", true, changed = false) }
            action("cancel", true, changed = false)
            assertEquals(1, engine.starts, "取消后仍按住原键时不能自动重录")
            action("ptt", false)
            assertEquals(0, engine.finishes, "松开已取消的录音不能提交结果")

            action("ptt", true)
            assertEquals(2, engine.starts, "下一次长按应重新开始录音")
            audio.speak(); runCurrent()
            action("ptt", false)
            assertEquals(listOf("我们走吧 你呢？"), sent)
            assertEquals(1, engine.finishes)
            assertTrue(polling.isActive)

            action("ptt", true)
            audio.speak(); runCurrent()
            action("ptt", false, active = false)
            assertEquals(VoicePhase.CANCELLED, voice.state.value.phase, "输入失效应取消录音")
            assertEquals(1, sent.size, "输入失效不能提交录音")
            assertTrue(polling.isActive, "控制器输入失效不能停止连接循环")
            action("ptt", false)
            action("ptt", true)
            assertEquals(4, engine.starts)
        } finally {
            voice.cancel(); runCurrent()
            polling.cancel(); events.close(); vr.close()
        }
    }

    @Test fun `confirmation mode permits edits and sends at most once`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer(); val sent = mutableListOf<String>()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { text, _ -> sent += text }, StandardTestDispatcher(testScheduler))
        voice.press(Settings(autoSend = false)); runCurrent(); audio.speak(); runCurrent(); voice.release(); runCurrent()
        assertEquals(VoicePhase.PREVIEW, voice.state.value.phase)
        assertTrue(sent.isEmpty())
        voice.editPreview("我改好的文字。")
        voice.confirm(); voice.confirm()
        assertEquals(listOf("我改好的文字。"), sent)
    }

    @Test fun `silence and disconnected microphone do not produce chat messages`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer(); val sent = mutableListOf<String>()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { text, _ -> sent += text }, StandardTestDispatcher(testScheduler))
        voice.press(Settings()); runCurrent(); voice.release(); runCurrent()
        assertTrue(sent.isEmpty())
        voice.press(Settings()); runCurrent(); audio.speak(); audio.failure("设备断开"); runCurrent()
        assertEquals(VoicePhase.ERROR, voice.state.value.phase)
        assertTrue(sent.isEmpty())
    }

    @Test fun `server sentence revisions do not duplicate finalized text`() {
        val text = SentenceTranscript()
        text.update(0, "你好", false)
        text.update(0, "你好世界", true)
        text.update(0, "你好", false)
        text.update(1, "第二句", false)
        assertEquals("你好世界 第二句", text.preview())
        assertFailsWith<IllegalStateException> { text.finalText() }
        text.update(1, "第二句话", true)
        assertEquals("你好世界 第二句话", text.finalText())
    }

    @Test fun `failed confirmation preserves editable text`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { _, _ -> error("queue full") }, StandardTestDispatcher(testScheduler))
        voice.press(Settings(autoSend = false)); runCurrent(); audio.speak(); runCurrent(); voice.release(); runCurrent()
        voice.editPreview("保留这句话")
        assertFailsWith<IllegalStateException> { voice.confirm() }
        assertEquals(VoicePhase.PREVIEW, voice.state.value.phase)
        assertEquals("保留这句话", voice.state.value.text)
    }

    @Test fun `late partial cannot replace finalized preview while queuing`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer(); val hold = CompletableDeferred<Unit>()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { _, _ -> hold.await() }, StandardTestDispatcher(testScheduler))
        voice.press(Settings()); runCurrent(); audio.speak(); runCurrent(); voice.release(); runCurrent()
        assertEquals("我们走吧 你呢？", voice.state.value.text)
        engine.callback("迟到的临时文本")
        assertEquals("我们走吧 你呢？", voice.state.value.text)
        voice.cancel(); hold.complete(Unit); runCurrent()
        assertEquals(VoicePhase.CANCELLED, voice.state.value.phase)
    }

    @Test fun `shutdown waits for a previously cancelled engine to release its stream`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        voice.press(Settings()); runCurrent()
        voice.cancel()
        voice.awaitStopped()
        assertEquals(1, engine.cancels)
        assertTrue(audio.stopped)
    }

    @Test fun `held button timeout cancels rather than sending partial text`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer(); val sent = mutableListOf<String>()
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { text, _ -> sent += text }, StandardTestDispatcher(testScheduler))
        voice.press(Settings()); runCurrent(); audio.speak(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(VoicePhase.ERROR, voice.state.value.phase)
        assertTrue(sent.isEmpty())
        assertEquals(0, engine.finishes)
    }

    @Test fun `accurate final failure never falls back to streaming preview`() = runTest {
        val audio = FakeAudio(); val engine = FakeRecognizer(); val sent = mutableListOf<String>()
        engine.finishFailure = "精校失败"
        val voice = VoiceSession(backgroundScope, { engine }, { audio }, { text, _ -> sent += text }, StandardTestDispatcher(testScheduler))
        voice.press(Settings(localMode = LocalRecognitionMode.ACCURATE)); runCurrent()
        audio.speak(); runCurrent(); voice.release(); runCurrent()
        assertEquals(VoicePhase.ERROR, voice.state.value.phase)
        assertEquals("精校失败", voice.state.value.error)
        assertTrue(sent.isEmpty())
    }

    @Test fun `cancel during noninterruptible final recognition discards its late result`() = runBlocking<Unit> {
        val audio = FakeAudio(); val base = FakeRecognizer(); val sent = CopyOnWriteArrayList<String>()
        val entered = CompletableDeferred<Unit>()
        val finishGate = CountDownLatch(1)
        val engine = object : SpeechRecognizer by base {
            override fun finish(): String {
                entered.complete(Unit)
                // Simulate JNI that completes even after its calling coroutine is cancelled.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) { "Test final recognition timed out" }
                    try { if (finishGate.await(remaining, TimeUnit.NANOSECONDS)) break }
                    catch (_: InterruptedException) { }
                }
                base.callback("迟到的预览")
                return "迟到的最终精校结果"
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val voice = VoiceSession(scope, { engine }, { audio }, { text, _ -> sent += text })
        try {
            withTimeout(5000) {
                voice.press(Settings(localMode = LocalRecognitionMode.ACCURATE))
                voice.state.first { it.phase == VoicePhase.RECORDING }
                audio.speak(); voice.release(); entered.await()
                voice.cancel(); finishGate.countDown(); voice.awaitStopped()
            }
            assertTrue(sent.isEmpty())
            assertEquals(VoicePhase.CANCELLED, voice.state.value.phase)
            assertEquals("", voice.state.value.text)
        } finally {
            finishGate.countDown(); voice.cancel()?.join(); voice.awaitStopped(); scope.cancel()
        }
    }
}
