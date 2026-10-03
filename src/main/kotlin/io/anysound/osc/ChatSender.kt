package io.anysound.osc

import io.anysound.config.Settings
import io.anysound.text.TextProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SendState(val pending: Int = 0, val current: Int = 0, val total: Int = 0, val text: String = "", val error: String? = null)

class ChatSender(
    scope: CoroutineScope,
    private val send: suspend (Settings, String) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Batch(val generation: Long, val parts: List<String>, val settings: Settings)
    private val queue = Channel<Batch>(32)
    private val gate = Mutex()
    private var generation = 0L
    private var lastSent: Long? = null
    private val mutable = MutableStateFlow(SendState())
    val state = mutable.asStateFlow()
    private val worker = scope.launch {
        for (batch in queue) {
            for ((index, part) in batch.parts.withIndex()) {
                val allowed = gate.withLock { batch.generation == generation }
                if (!allowed) break
                lastSent?.let { delay((it + batch.settings.sendIntervalMs - now()).coerceAtLeast(0)) }
                gate.withLock {
                    if (batch.generation != generation) return@withLock
                    try {
                        send(batch.settings, part)
                        lastSent = now()
                        mutable.value = SendState((mutable.value.pending - 1).coerceAtLeast(0), index + 1, batch.parts.size, part)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        generation++
                        while (queue.tryReceive().isSuccess) { /* discard unsent messages */ }
                        mutable.value = SendState(error = "发送失败，请检查 OSC 地址和网络；未发送内容已停止")
                    }
                }
            }
        }
    }

    suspend fun enqueue(text: String, settings: Settings) {
        settings.validate()
        val parts = TextProcessor.split(text)
        if (parts.isEmpty()) return
        gate.withLock {
            currentCoroutineContext().ensureActive()
            check(mutable.value.pending + parts.size <= 100) { "待发送内容超过 100 条，请等待或取消剩余消息" }
            check(queue.trySend(Batch(generation, parts, settings)).isSuccess) { "发送队列已满，请等待或取消剩余消息" }
            mutable.value = mutable.value.copy(pending = mutable.value.pending + parts.size, error = null)
        }
    }

    suspend fun cancel() = gate.withLock {
        generation++
        while (queue.tryReceive().isSuccess) { }
        mutable.value = SendState()
    }
    suspend fun close() { cancel(); queue.close(); worker.cancelAndJoin() }
}
