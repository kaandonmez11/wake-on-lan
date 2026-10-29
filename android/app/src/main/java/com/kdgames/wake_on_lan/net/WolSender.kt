package com.kdgames.wake_on_lan.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Magic packet gönderimi (spec 7.2): subnet broadcast port 9 ve 7 + 255.255.255.255 port 9,
 * t = 0 / 2 / 5 sn. Unicast yok. Soketler [SocketFactory] üzerinden Wi-Fi'a bağlıdır.
 */
@Singleton
class WolSender @Inject constructor(
    private val sockets: SocketFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    data class Target(val host: String, val port: Int) {
        override fun toString() = "$host:$port"
    }

    /** Bir gönderim turu: başlangıca göre ms ve hedef başına hata (null = gitti). */
    data class Round(val atMs: Long, val results: List<Pair<Target, String?>>) {
        val allSent: Boolean get() = results.all { it.second == null }
    }

    /**
     * Üç turu da gönderir; her tur bitince [onRound] çağrılır.
     * @throws NoWifiException Wi-Fi yoksa ya da broadcast adresi hesaplanamıyorsa (ilk turdan önce).
     */
    suspend fun send(mac: String, broadcast: String?, onRound: (Round) -> Unit = {}): List<Round> {
        val packet = MagicPacket.build(mac)
        val subnet = broadcast ?: throw NoWifiException()
        val targets = targets(subnet)
        val start = clock.nowMs()
        val rounds = mutableListOf<Round>()
        for (offset in OFFSETS_MS) {
            val wait = offset - (clock.nowMs() - start)
            if (wait > 0) delay(wait)
            val at = clock.nowMs() - start
            val results = withContext(io) { sendRound(packet, targets) }
            rounds += Round(at, results).also(onRound)
        }
        return rounds
    }

    private fun sendRound(packet: ByteArray, targets: List<Target>): List<Pair<Target, String?>> {
        // Wi-Fi kaybolduysa NoWifiException yukarı çıkar; tek tek hedef hataları tura yazılır.
        val socket = sockets.udp()
        return socket.use {
            it.broadcast = true
            targets.map { target ->
                target to try {
                    it.send(DatagramPacket(packet, packet.size, InetAddress.getByName(target.host), target.port))
                    null
                } catch (e: IOException) {
                    e.message ?: e::class.java.simpleName
                }
            }
        }
    }

    companion object {
        val OFFSETS_MS = listOf(0L, 2_000L, 5_000L)
        const val LIMITED_BROADCAST = "255.255.255.255"

        fun targets(subnetBroadcast: String) = listOf(
            Target(subnetBroadcast, 9),
            Target(subnetBroadcast, 7),
            Target(LIMITED_BROADCAST, 9),
        )
    }
}
