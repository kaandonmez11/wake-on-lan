package com.kdgames.wake_on_lan.net.probe

import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.IoDispatcher
import com.kdgames.wake_on_lan.net.SocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S3 (spec 7.3): `probePorts`'a paralel TCP connect. Bağlanma **veya** "connection refused" = canlı
 * (makine ayakta, port kapalı). Zaman aşımı ya da ulaşılamıyor = yanıt yok. Kimlik doğrulamaz.
 */
@Singleton
class TcpProbe @Inject constructor(
    private val sockets: SocketFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    data class Result(
        val alive: Boolean,
        /** Canlılığı ilk gösteren port. */
        val port: Int?,
        /** true: bağlantı kuruldu, false: refused ile canlı. */
        val accepted: Boolean,
        val elapsedMs: Long,
    )

    /** @throws com.kdgames.wake_on_lan.net.NoWifiException */
    suspend fun probe(ip: String, ports: List<Int>, timeoutMs: Int = TIMEOUT_MS): Result {
        require(ports.isNotEmpty()) { "Port listesi boş" }
        val start = clock.nowMs()
        val open: MutableList<Socket> = Collections.synchronizedList(mutableListOf())
        val hit = coroutineScope {
            val winner = CompletableDeferred<Pair<Int, Boolean>?>()
            val jobs = ports.distinct().map { port ->
                launch(io) { attempt(ip, port, timeoutMs, open)?.let { winner.complete(port to it) } }
            }
            launch { jobs.joinAll(); winner.complete(null) }
            winner.await().also {
                // Bloklayan connect'ler iptale tepki vermez; soketi kapatmak onları hemen düşürür.
                synchronized(open) { open.forEach { s -> runCatching { s.close() } } }
                coroutineContext.cancelChildren()
            }
        }
        return Result(hit != null, hit?.first, hit?.second ?: false, clock.nowMs() - start)
    }

    /** null = yanıt yok; true = bağlandı; false = refused (canlı). */
    private fun attempt(ip: String, port: Int, timeoutMs: Int, open: MutableList<Socket>): Boolean? {
        val socket = sockets.tcp()
        open += socket
        return try {
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        } catch (e: ConnectException) {
            if (isRefused(e)) false else null
        } catch (_: IOException) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        const val TIMEOUT_MS = 400

        fun isRefused(e: ConnectException): Boolean {
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
            return "ECONNREFUSED" in text || text.contains("refused", ignoreCase = true)
        }
    }
}
