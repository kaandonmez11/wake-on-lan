package com.kdgames.wake_on_lan.net.probe

import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.IoDispatcher
import com.kdgames.wake_on_lan.net.SocketFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * S2 (spec 7.3): UDP 137 NBSTAT ("*" adı için node status). Yanıttan hostname ve MAC çıkar;
 * MAC eşleşmesi kimliği doğrular (K8: farklı MAC → bu IP'de başka cihaz var).
 */
@Singleton
class NetBiosProbe @Inject constructor(
    private val sockets: SocketFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    data class Result(val hostname: String?, val mac: String?, val elapsedMs: Long)

    /** Yanıt yoksa null. @throws com.kdgames.wake_on_lan.net.NoWifiException */
    suspend fun probe(ip: String, timeoutMs: Int = TIMEOUT_MS): Result? = withContext(io) {
        val txId = Random.nextInt(0x10000)
        val query = query(txId)
        val start = clock.nowMs()
        sockets.udp().use { socket ->
            val target = InetAddress.getByName(ip)
            socket.send(DatagramPacket(query, query.size, target, PORT))
            val buffer = ByteArray(1024)
            var result: Result? = null
            while (result == null) {
                val remaining = timeoutMs - (clock.nowMs() - start)
                if (remaining <= 0) break
                socket.soTimeout = remaining.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: IOException) {
                    break
                }
                if (packet.address != target) continue
                result = parse(packet.data.copyOf(packet.length), txId)?.copy(elapsedMs = clock.nowMs() - start)
            }
            result
        }
    }

    companion object {
        const val PORT = 137
        const val TIMEOUT_MS = 500

        /** RFC 1002 §4.2.17: NODE STATUS REQUEST, ad "*" (first-level encoding "CKAAAA…"). */
        fun query(txId: Int): ByteArray {
            val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
            val encoded = ByteArray(32)
            name.forEachIndexed { i, b ->
                val v = b.toInt() and 0xFF
                encoded[i * 2] = ('A'.code + (v shr 4)).toByte()
                encoded[i * 2 + 1] = ('A'.code + (v and 0x0F)).toByte()
            }
            return byteArrayOf((txId shr 8).toByte(), txId.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x20) +
                encoded + byteArrayOf(0, 0, 0x21, 0, 1)
        }

        /** NODE STATUS RESPONSE ayrıştırma; geçersiz/başka işlemse null. */
        fun parse(data: ByteArray, txId: Int): Result? {
            fun u8(i: Int) = data[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            if (data.size < 12 || u16(0) != (txId and 0xFFFF) || u8(2) and 0x80 == 0) return null
            val questions = u16(4)
            val answers = u16(6)
            if (answers < 1) return null
            var p = 12
            fun skipName(): Boolean {
                while (p < data.size) {
                    val len = u8(p)
                    when {
                        len == 0 -> { p += 1; return true }
                        len and 0xC0 == 0xC0 -> { p += 2; return true }
                        else -> p += 1 + len
                    }
                }
                return false
            }
            repeat(questions) { if (!skipName()) return null; p += 4 }
            if (!skipName() || p + 10 > data.size) return null
            val type = u16(p)
            if (type != 0x21) return null
            val rdLength = u16(p + 8)
            p += 10
            val end = minOf(data.size, p + rdLength)
            if (p >= end) return null
            val count = u8(p)
            p += 1
            var hostname: String? = null
            repeat(count) {
                if (p + 18 > end) return null
                val raw = String(data, p, 15, Charsets.ISO_8859_1).trimEnd(' ', '\u0000')
                val suffix = u8(p + 15)
                val group = u16(p + 16) and 0x8000 != 0
                if (hostname == null && suffix == 0x00 && !group && raw.isNotEmpty()) hostname = raw
                p += 18
            }
            val mac = if (p + 6 <= end) {
                val bytes = data.copyOfRange(p, p + 6)
                if (bytes.all { it == 0.toByte() }) null else bytes.joinToString(":") { "%02X".format(it) }
            } else null
            return Result(hostname, mac, 0)
        }
    }
}
