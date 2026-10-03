package io.anysound.osc

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object OscCodec {
    fun message(address: String, vararg arguments: Any): ByteArray {
        val output = ByteArrayOutputStream()
        fun string(value: String) {
            require('\u0000' !in value)
            val bytes = value.toByteArray(Charsets.UTF_8)
            output.write(bytes)
            repeat(4 - bytes.size % 4) { output.write(0) }
        }
        string(address)
        string("," + arguments.joinToString("") {
            when (it) { is String -> "s"; true -> "T"; false -> "F"; else -> error("Unsupported OSC type") }
        })
        arguments.filterIsInstance<String>().forEach(::string)
        return output.toByteArray()
    }
}

class OscClient : AutoCloseable {
    private val socket = DatagramSocket()
    @Synchronized
    fun send(host: String, port: Int, address: String, vararg arguments: Any) {
        val bytes = OscCodec.message(address, *arguments)
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
    }
    override fun close() = socket.close()
}
