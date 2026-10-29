package com.kdgames.wake_on_lan.net

import com.kdgames.wake_on_lan.net.probe.IcmpProbe
import com.kdgames.wake_on_lan.net.probe.PingOutput
import com.kdgames.wake_on_lan.net.probe.PingRunner
import com.kdgames.wake_on_lan.net.probe.TcpProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Gerçek ağa çıkmayan soket üreticisi: UDP gönderimlerini kaydeder, TCP connect davranışı porta göre seçilir. */
private class FakeSockets(private val now: () -> Long = { 0 }) : SocketFactory {
    data class Sent(val atMs: Long, val host: String, val port: Int, val payload: ByteArray)

    val sent = mutableListOf<Sent>()
    var wifi = true
    var udpFailPort: Int? = null
    var connect: (port: Int, socket: FakeTcpSocket) -> Unit = { _, _ -> throw SocketTimeoutException("connect timed out") }

    override fun udp(): DatagramSocket {
        if (!wifi) throw NoWifiException()
        return object : DatagramSocket(null as SocketAddress?) {
            override fun send(p: DatagramPacket) {
                if (p.port == udpFailPort) throw IOException("sendto failed: EPERM")
                sent += Sent(now(), p.address.hostAddress!!, p.port, p.data.copyOfRange(p.offset, p.offset + p.length))
            }
        }
    }

    override fun tcp(): Socket {
        if (!wifi) throw NoWifiException()
        return FakeTcpSocket(connect)
    }
}

private class FakeTcpSocket(private val onConnect: (Int, FakeTcpSocket) -> Unit) : Socket() {
    val closed = CountDownLatch(1)

    override fun connect(endpoint: SocketAddress, timeout: Int) = onConnect((endpoint as InetSocketAddress).port, this)

    override fun close() {
        closed.countDown()
        super.close()
    }
}

class NetCoreTest {

    @Nested
    inner class WolSenderTest {
        private val mac = "D8:BB:C1:0A:2F:91"

        @Test
        fun `uc tur 0 2 5 sn, her turda subnet 9 ve 7 + 255 9`() = runTest {
            val fake = FakeSockets { testScheduler.currentTime }
            val sender = WolSender(fake, StandardTestDispatcher(testScheduler), Clock { testScheduler.currentTime })
            val rounds = sender.send(mac, "192.168.1.255")

            assertEquals(listOf(0L, 2_000L, 5_000L), rounds.map { it.atMs })
            assertTrue(rounds.all { it.allSent })
            val expectedTargets = listOf("192.168.1.255" to 9, "192.168.1.255" to 7, "255.255.255.255" to 9)
            assertEquals(List(3) { expectedTargets }.flatten(), fake.sent.map { it.host to it.port })
            assertEquals(listOf(0L, 0, 0, 2_000, 2_000, 2_000, 5_000, 5_000, 5_000), fake.sent.map { it.atMs })
            fake.sent.forEach { assertArrayEquals(MagicPacket.build(mac), it.payload) }
            assertEquals(5_000L, testScheduler.currentTime)
        }

        @Test
        fun `tur bilgisi aninda bildirilir`() = runTest {
            val fake = FakeSockets { testScheduler.currentTime }
            val sender = WolSender(fake, StandardTestDispatcher(testScheduler), Clock { testScheduler.currentTime })
            val seen = mutableListOf<Long>()
            sender.send(mac, "10.0.7.255") { seen += testScheduler.currentTime }
            assertEquals(listOf(0L, 2_000L, 5_000L), seen)
        }

        @Test
        fun `broadcast yoksa Wi-Fi yok, hicbir sey gitmez`() = runTest {
            val fake = FakeSockets()
            val sender = WolSender(fake, StandardTestDispatcher(testScheduler), Clock { testScheduler.currentTime })
            assertThrows<NoWifiException> { sender.send(mac, null) }
            assertTrue(fake.sent.isEmpty())
        }

        @Test
        fun `Wi-Fi turlar arasinda giderse hata yukari cikar`() = runTest {
            val fake = FakeSockets { testScheduler.currentTime }
            val sender = WolSender(fake, StandardTestDispatcher(testScheduler), Clock { testScheduler.currentTime })
            assertThrows<NoWifiException> { sender.send(mac, "192.168.1.255") { fake.wifi = false } }
            assertEquals(3, fake.sent.size)
        }

        @Test
        fun `tek hedef hatasi digerlerini durdurmaz`() = runTest {
            val fake = FakeSockets { testScheduler.currentTime }.apply { udpFailPort = 7 }
            val sender = WolSender(fake, StandardTestDispatcher(testScheduler), Clock { testScheduler.currentTime })
            val rounds = sender.send(mac, "192.168.1.255")
            rounds.forEach { round ->
                assertFalse(round.allSent)
                assertEquals(listOf(null, "sendto failed: EPERM", null), round.results.map { it.second })
            }
            assertEquals(6, fake.sent.size)
        }

        @Test
        fun `gecersiz MAC gonderimden once reddedilir`() = runTest {
            val fake = FakeSockets()
            val sender = WolSender(fake, StandardTestDispatcher(testScheduler), Clock { testScheduler.currentTime })
            assertThrows<IllegalArgumentException> { sender.send("AA:BB", "192.168.1.255") }
            assertTrue(fake.sent.isEmpty())
        }
    }

    @Nested
    inner class TcpProbeTest {
        private fun probe(fake: FakeSockets) = TcpProbe(fake, Dispatchers.IO, Clock.Monotonic)

        @Test
        fun `connection refused canli sayilir`() = runBlocking {
            val fake = FakeSockets().apply {
                connect = { port, _ ->
                    if (port == 445) throw ConnectException("failed to connect to /192.168.1.24 (port 445): connect failed: ECONNREFUSED (Connection refused)")
                    throw SocketTimeoutException("timeout")
                }
            }
            val r = probe(fake).probe("192.168.1.24", listOf(3389, 445, 135))
            assertTrue(r.alive)
            assertEquals(445, r.port)
            assertFalse(r.accepted)
        }

        @Test
        fun `baglanti kabul edilirse canli ve accepted`() = runBlocking {
            val fake = FakeSockets().apply { connect = { port, _ -> if (port != 3389) throw SocketTimeoutException() } }
            val r = probe(fake).probe("192.168.1.24", listOf(3389, 445))
            assertTrue(r.alive)
            assertEquals(3389, r.port)
            assertTrue(r.accepted)
        }

        @Test
        fun `timeout yanit yok`() = runBlocking {
            val r = probe(FakeSockets()).probe("192.168.1.24", listOf(3389, 445, 135))
            assertFalse(r.alive)
            assertNull(r.port)
        }

        @Test
        fun `ulasilamiyor hatasi canli sayilmaz`() = runBlocking {
            val fake = FakeSockets().apply {
                connect = { _, _ -> throw ConnectException("connect failed: EHOSTUNREACH (No route to host)") }
            }
            assertFalse(probe(fake).probe("192.168.1.24", listOf(3389)).alive)
        }

        @Test
        fun `ilk canli port gelince bekleyen connect'ler kapatilip hemen donulur`() = runBlocking {
            val fake = FakeSockets().apply {
                connect = { port, socket ->
                    if (port == 445) throw ConnectException("Connection refused")
                    // Diğer port soket kapanana kadar (en fazla 10 sn) bloklar.
                    socket.closed.await(10, TimeUnit.SECONDS)
                    throw java.net.SocketException("Socket closed")
                }
            }
            val r = probe(fake).probe("192.168.1.24", listOf(3389, 445), timeoutMs = 10_000)
            assertTrue(r.alive)
            assertTrue(r.elapsedMs < 2_000, "bekleyen connect sonucu geciktirdi: ${r.elapsedMs} ms")
        }

        @Test
        fun `Wi-Fi yoksa NoWifiException`() {
            val fake = FakeSockets().apply { wifi = false }
            assertThrows<NoWifiException> { runBlocking { probe(fake).probe("192.168.1.24", listOf(3389)) } }
        }
    }

    @Nested
    inner class IcmpProbeTest {
        private val success = """
            PING 192.168.1.24 (192.168.1.24) 56(84) bytes of data.
            64 bytes from 192.168.1.24: icmp_seq=1 ttl=128 time=3.62 ms

            --- 192.168.1.24 ping statistics ---
            1 packets transmitted, 1 received, 0% packet loss, time 0ms
            rtt min/avg/max/mdev = 3.621/3.621/3.621/0.000 ms
        """.trimIndent()

        private val loss = """
            PING 192.168.1.24 (192.168.1.24) 56(84) bytes of data.

            --- 192.168.1.24 ping statistics ---
            1 packets transmitted, 0 received, 100% packet loss, time 0ms
        """.trimIndent()

        @Test
        fun `basarili cikti ms ayristirilir`() {
            assertEquals(IcmpProbe.Result.Reply(4), IcmpProbe.interpret(PingOutput(0, success), 20))
        }

        @Test
        fun `kayip yanit yok`() {
            assertEquals(IcmpProbe.Result.NoReply(1_010), IcmpProbe.interpret(PingOutput(1, loss), 1_010))
            assertInstanceOf(IcmpProbe.Result.NoReply::class.java, IcmpProbe.interpret(PingOutput(-1, "timeout"), 1_500))
        }

        @Test
        fun `yerel ag izni yokken EPERM pingi kapatmaz, engellendi olarak doner`() = runTest {
            // Kaan'ın telefonunda (Android 17) izin yokken görülen gerçek çıktı.
            val blocked = """
                ping: sendmsg: Operation not permitted
                PING 192.168.1.1 (192.168.1.1) 56(84) bytes of data.

                --- 192.168.1.1 ping statistics ---
                1 packets transmitted, 0 received, 100% packet loss, time 0ms
            """.trimIndent()
            val icmp = IcmpProbe({ _, _ -> PingOutput(1, blocked) }, StandardTestDispatcher(testScheduler), Clock { 0 })
            assertEquals(IcmpProbe.Result.NoReply(0, blocked = true), icmp.probe("192.168.1.1"))
            assertTrue(icmp.available)
        }

        @Test
        fun `soket acilamazsa kullanilamaz`() {
            val r = IcmpProbe.interpret(PingOutput(2, "ping: socket: Permission denied\n"), 5)
            assertEquals(IcmpProbe.Result.Unavailable("ping: socket: Permission denied"), r)
        }

        @Test
        fun `ping yoksa available false ve bir daha surec acilmaz`() = runTest {
            var calls = 0
            val icmp = IcmpProbe({ _, _ -> calls++; null }, StandardTestDispatcher(testScheduler), Clock { testScheduler.currentTime })
            assertInstanceOf(IcmpProbe.Result.Unavailable::class.java, icmp.probe("192.168.1.24"))
            assertFalse(icmp.available)
            assertInstanceOf(IcmpProbe.Result.Unavailable::class.java, icmp.probe("192.168.1.24"))
            assertEquals(1, calls)
        }

        @Test
        fun `arguman listesi spec ile ayni`() = runTest {
            var args: List<String>? = null
            val runner = PingRunner { a, _ -> args = a; PingOutput(0, success) }
            val icmp = IcmpProbe(runner, StandardTestDispatcher(testScheduler), Clock { testScheduler.currentTime })
            assertEquals(IcmpProbe.Result.Reply(4), icmp.probe("192.168.1.24"))
            assertEquals(listOf("/system/bin/ping", "-c", "1", "-W", "1", "192.168.1.24"), args)
            assertTrue(icmp.available)
        }

        @Test
        fun `IPv4 disinda arguman kabul edilmez`() = runTest {
            val icmp = IcmpProbe({ _, _ -> error("çağrılmamalı") }, StandardTestDispatcher(testScheduler), Clock { 0 })
            assertThrows<IllegalArgumentException> { icmp.probe("192.168.1.24; reboot") }
            assertThrows<IllegalArgumentException> { icmp.probe("-f") }
        }
    }

    @Nested
    inner class WifiStateTest {
        private fun state(ip: String?, prefix: Int?, ssid: String? = "\"Ev\"", level: Int? = 3, max: Int? = 4, speed: Int? = 866) =
            WifiState.from(null, ssid, -55, level, max, speed, ip, prefix)

        @Test
        fun `broadcast BroadcastResolver ile hesaplanir`() {
            assertEquals("192.168.1.255", state("192.168.1.24", 24).broadcast)
            assertEquals("10.0.7.255", state("10.0.5.17", 22).broadcast)
        }

        @Test
        fun `31, 32 ya da IP yokken broadcast yok`() {
            assertNull(state("192.168.1.24", 31).broadcast)
            assertNull(state("192.168.1.24", 32).broadcast)
            assertNull(state(null, null).broadcast)
            assertTrue(state(null, null).connected)
        }

        @Test
        fun `SSID tirnaklari atilir, gizli SSID null`() {
            assertEquals("Ev", state("192.168.1.2", 24).ssid)
            assertEquals("Kaan 5G", WifiState.cleanSsid("\"Kaan 5G\""))
            assertNull(WifiState.cleanSsid("<unknown ssid>"))
            assertNull(WifiState.cleanSsid(""))
            assertNull(WifiState.cleanSsid(null))
        }

        @Test
        fun `sinyal seviyesi 0-4 olcegine cevrilir`() {
            assertEquals(3, WifiState.normalizeLevel(3, 4))
            assertEquals(4, WifiState.normalizeLevel(5, 5))
            assertEquals(2, WifiState.normalizeLevel(3, 5))
            assertEquals(0, WifiState.normalizeLevel(null, 4))
            assertEquals(4, WifiState.normalizeLevel(9, 4))
        }

        @Test
        fun `link hizi bilinmiyorsa null`() {
            assertNull(state("192.168.1.2", 24, speed = -1).linkSpeedMbps)
            assertEquals(866, state("192.168.1.2", 24).linkSpeedMbps)
        }

        @Test
        fun `baglanti yokken varsayilan durum`() {
            val d = WifiState.Disconnected
            assertFalse(d.connected)
            assertNull(d.network)
            assertNull(d.broadcast)
        }
    }
}
