package io.anysound

import io.anysound.config.Settings
import io.anysound.osc.ChatSender
import io.anysound.osc.OscClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OscAndQueueTest {
    @Test fun `actual UDP packet has OSC UTF8 strings and payload-free booleans`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2000
            OscClient().use { sender ->
                sender.send("127.0.0.1", receiver.localPort, "/chatbox/input", "你好 👋", true, false)
                val packet = DatagramPacket(ByteArray(2048), 2048)
                receiver.receive(packet)
                val data = ByteBuffer.wrap(packet.data, 0, packet.length)
                assertEquals("/chatbox/input", readString(data))
                assertEquals(",sTF", readString(data))
                assertEquals("你好 👋", readString(data))
                assertFalse(data.hasRemaining())
                sender.send("127.0.0.1", receiver.localPort, "/chatbox/typing", false)
                receiver.receive(packet)
                val typing = ByteBuffer.wrap(packet.data, 0, packet.length)
                assertEquals("/chatbox/typing", readString(typing))
                assertEquals(",F", readString(typing))
                assertFalse(typing.hasRemaining())
            }
        }
    }

    @Test fun `queue serializes batches respects cooldown and cancels only unsent chunks`() = runTest {
        val sent = mutableListOf<Pair<Long, String>>()
        val queue = ChatSender(backgroundScope, { _, text -> sent += testScheduler.currentTime to text }, { testScheduler.currentTime })
        queue.enqueue("a".repeat(145), Settings())
        queue.enqueue("第二条", Settings())
        runCurrent()
        assertEquals(listOf(0L to "a".repeat(144)), sent)
        advanceTimeBy(2999); runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3000L to "a", sent.last())
        queue.cancel()
        queue.enqueue("新的文字", Settings())
        advanceTimeBy(3000); runCurrent()
        assertEquals(listOf("a".repeat(144), "a", "新的文字"), sent.map { it.second })
        assertEquals(6000L, sent.last().first)
        assertEquals(0, queue.state.value.pending)
        queue.close()
    }

    @Test fun `send failure stops subsequent queued messages`() = runTest {
        var attempts = 0
        val queue = ChatSender(backgroundScope, { _, _ -> attempts++; error("network") }, { testScheduler.currentTime })
        queue.enqueue("a".repeat(500), Settings())
        queue.enqueue("later", Settings())
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(1, attempts)
        assertNotNull(queue.state.value.error)
        assertEquals(0, queue.state.value.pending)
        queue.close()
    }

    private fun readString(data: ByteBuffer): String {
        val bytes = mutableListOf<Byte>()
        while (true) { val byte = data.get(); if (byte == 0.toByte()) break; bytes += byte }
        while (data.position() % 4 != 0) assertEquals(0, data.get().toInt())
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}
