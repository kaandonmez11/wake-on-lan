package com.kdgames.wake_on_lan.net.agent

import com.kdgames.wake_on_lan.net.IoDispatcher
import com.kdgames.wake_on_lan.net.SocketFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.inject.Inject

/** Gelen bir datagram: gönderenin IPv4'ü ve baytlar. */
class Received(val host: String, val bytes: ByteArray)

/** Tek bir Wi-Fi'a bağlı UDP soketi üzerinde gönder/al. Yeniden denemelerde aynı soket kullanılır (geç yanıtlar kaybolmasın). */
interface AgentChannel {
    suspend fun send(host: String, port: Int, payload: ByteArray)

    /** [timeoutMs] içinde gelen ilk datagram; yoksa null. */
    suspend fun receive(timeoutMs: Long): Received?
}

/** Testte sahte agent ile değiştirilir. */
interface AgentTransport {
    /** @throws com.kdgames.wake_on_lan.net.NoWifiException */
    suspend fun <T> open(block: suspend AgentChannel.() -> T): T
}

class UdpAgentTransport @Inject constructor(
    private val sockets: SocketFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AgentTransport {

    override suspend fun <T> open(block: suspend AgentChannel.() -> T): T {
        val socket = withContext(io) { sockets.udp().apply { broadcast = true } }
        return try {
            Channel(socket).block()
        } finally {
            socket.close()
        }
    }

    private inner class Channel(private val socket: DatagramSocket) : AgentChannel {
        private val buffer = ByteArray(AgentProtocol.MAX_DATAGRAM_BYTES + 64)

        override suspend fun send(host: String, port: Int, payload: ByteArray) = withContext(io) {
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), port))
        }

        override suspend fun receive(timeoutMs: Long): Received? = withContext(io) {
            socket.soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                Received(packet.address.hostAddress.orEmpty(), packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
            } catch (_: SocketTimeoutException) {
                null
            }
        }
    }
}
