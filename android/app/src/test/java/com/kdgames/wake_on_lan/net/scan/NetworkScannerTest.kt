package com.kdgames.wake_on_lan.net.scan

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.net.MulticastGuard
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.net.agent.AgentMessage
import com.kdgames.wake_on_lan.net.agent.AgentNic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private val wifi = WifiState(connected = true, phoneIp = "192.168.1.88", prefixLength = 24, broadcast = "192.168.1.255")

private fun reply(host: String = "KAAN-PC", paired: Boolean = false, nics: List<AgentNic>) = AgentMessage.DiscoverReply(
    rid = "r", agentId = "agent-1", hostname = host, agentVersion = "1.0", paired = paired,
    hibernate = true, nics = nics, sleepHibernates = true,
)

private class FakeSources(
    val alive: Map<String, Set<Int>> = emptyMap(),
    val agents: List<Pair<String, AgentMessage.DiscoverReply>> = emptyList(),
    val netBios: Map<String, Pair<String?, String?>> = emptyMap(),
    val dns: Map<String, String> = emptyMap(),
    val mdns: List<Triple<String, String?, String?>> = emptyList(),
) : ScanSources {
    val probed = mutableSetOf<String>()
    var block: CompletableDeferred<Unit>? = null
    override suspend fun agents(broadcast: String) = agents
    override suspend fun ports(ip: String, ports: List<Int>, timeoutMs: Int): Set<Int>? {
        probed += ip
        block?.let { awaitCancellation() }
        return alive[ip]
    }
    override suspend fun netBios(ip: String) = netBios[ip]
    override suspend fun reverseDns(ip: String) = dns[ip]
    override fun mdns(): Flow<Triple<String, String?, String?>> = if (mdns.isEmpty()) emptyFlow() else flowOf(*mdns.toTypedArray())
}

private class CountingGuard : MulticastGuard {
    var held = 0
    var acquired = 0
    override suspend fun <T> withLock(block: suspend () -> T): T {
        held++
        acquired++
        try {
            return block()
        } finally {
            held--
        }
    }
}

class NetworkScannerTest {
    private fun device(ip: String, mac: String?) = Device(id = 1, name = "Ekli", ip = ip, mac = mac, iconKey = "pc", colorIndex = 0)

    @Test
    fun `sonuclar MAC yoksa IP anahtariyla birlesir`() = runTest {
        val sources = FakeSources(
            alive = mapOf("192.168.1.1" to setOf(80, 443), "192.168.1.26" to setOf(445, 3389), "192.168.1.40" to emptySet()),
            netBios = mapOf("192.168.1.26" to ("KAAN-PC" to "d8-bb-c1-0a-2f-91")),
            dns = mapOf("192.168.1.1" to "router"),
        )
        val snap = NetworkScanner(sources, CountingGuard()).scan(wifi) { emptyList() }.last()
        assertFalse(snap.running)
        val byIp = snap.entries.associateBy { it.ip }
        assertEquals(3, snap.entries.size)
        assertEquals("D8:BB:C1:0A:2F:91", byIp.getValue("192.168.1.26").mac)
        assertEquals("KAAN-PC", byIp.getValue("192.168.1.26").name)
        assertNull(byIp.getValue("192.168.1.1").mac) // MAC yok → IP anahtarı
        assertEquals("router", byIp.getValue("192.168.1.1").name)
        assertEquals("192.168.1.40", byIp.getValue("192.168.1.40").name)
        assertNull(byIp.getValue("192.168.1.40").mac)
        // Telefonun kendi IP'si taranmaz.
        assertFalse("192.168.1.88" in sources.probed)
        assertEquals(253, sources.probed.size)
    }

    @Test
    fun `ayni MAC iki IP'de tek satir`() = runTest {
        val sources = FakeSources(
            alive = mapOf("192.168.1.60" to emptySet(), "192.168.1.61" to emptySet()),
            netBios = mapOf("192.168.1.60" to ("TV" to "AA:BB:CC:00:11:22"), "192.168.1.61" to ("TV" to "aa-bb-cc-00-11-22")),
        )
        val snap = NetworkScanner(sources, CountingGuard()).scan(wifi) { emptyList() }.last()
        assertEquals(1, snap.entries.size)
        assertEquals("AA:BB:CC:00:11:22", snap.entries.single().mac)
    }

    @Test
    fun `satir anahtari MAC gelince degismez`() {
        val merger = ScanMerger("192.168.1.88", 24)
        merger.addAlive("192.168.1.26", setOf(445))
        val before = merger.snapshot(emptyList(), true).entries.single()
        merger.addNetBios("192.168.1.26", "KAAN-PC", "D8:BB:C1:0A:2F:91")
        merger.addAlive("192.168.1.2", emptySet())
        val after = merger.snapshot(emptyList(), true).entries
        assertEquals(before.key, after.first().key)
        assertEquals(listOf("192.168.1.26", "192.168.1.2"), after.map { it.ip }) // geliş sırası
    }

    @Test
    fun `agent satiri kablolu MAC ile gelir, Wi-Fi karti ayri satir olmaz`() = runTest {
        val sources = FakeSources(
            alive = mapOf("192.168.1.26" to setOf(445), "192.168.1.31" to setOf(445)),
            agents = listOf(
                "192.168.1.26" to reply(
                    nics = listOf(
                        AgentNic("D8:BB:C1:0A:2F:91", "Ethernet", wired = true, ip = "192.168.1.26"),
                        AgentNic("A0:B1:C2:D3:E4:F5", "Wi-Fi", wired = false, ip = "192.168.1.31"),
                    ),
                ),
            ),
            netBios = mapOf("192.168.1.31" to ("KAAN-PC" to "A0:B1:C2:D3:E4:F5")),
        )
        val snap = NetworkScanner(sources, CountingGuard()).scan(wifi) { emptyList() }.last()
        assertEquals(1, snap.entries.size)
        val pc = snap.entries.single()
        assertEquals("D8:BB:C1:0A:2F:91", pc.mac)
        assertEquals("KAAN-PC", pc.name)
        assertEquals("pc", pc.iconKey)
        assertEquals("agent-1", pc.agent?.agentId)
    }

    @Test
    fun `ekli cihaz MAC ya da IP ile isaretlenir`() = runTest {
        val sources = FakeSources(
            alive = mapOf("192.168.1.26" to setOf(3389), "192.168.1.27" to emptySet(), "192.168.1.28" to emptySet()),
            netBios = mapOf("192.168.1.26" to ("PC" to "D8:BB:C1:0A:2F:91")),
        )
        val existing = listOf(device("192.168.1.99", "d8:bb:c1:0a:2f:91"), device("192.168.1.27", null))
        val snap = NetworkScanner(sources, CountingGuard()).scan(wifi) { existing }.last()
        val byIp = snap.entries.associateBy { it.ip }
        assertTrue(byIp.getValue("192.168.1.26").added)
        assertTrue(byIp.getValue("192.168.1.27").added)
        assertFalse(byIp.getValue("192.168.1.28").added)
    }

    @Test
    fun `mDNS adi ve MAC'i birlesir`() = runTest {
        val sources = FakeSources(
            alive = mapOf("192.168.1.50" to setOf(445)),
            mdns = listOf(Triple("192.168.1.50", "NAS-EV", "00:11:32:AA:BB:CC")),
        )
        val snap = NetworkScanner(sources, CountingGuard()).scan(wifi) { emptyList() }.last()
        val nas = snap.entries.single()
        assertEquals("NAS-EV", nas.name)
        assertEquals("00:11:32:AA:BB:CC", nas.mac)
        assertEquals(MdnsResolver.parseName("OFIS-PC [d8:bb:c1:0a:2f:91]"), "OFIS-PC" to "d8:bb:c1:0a:2f:91")
        assertEquals(MdnsResolver.parseName("Synology"), "Synology" to null)
    }

    @Test
    fun `sonuclar artarak akar`() = runTest {
        val sources = FakeSources(alive = mapOf("192.168.1.2" to emptySet(), "192.168.1.3" to emptySet()))
        val all = NetworkScanner(sources, CountingGuard()).scan(wifi) { emptyList() }.toList()
        assertTrue(all.first().running)
        assertTrue(all.first().entries.isEmpty())
        assertTrue(all.any { it.running && it.entries.isNotEmpty() })
        assertEquals(2, all.last().entries.size)
    }

    @Test
    fun `iptal edilince tarama durur ve kilit birakilir`() = runTest {
        val sources = FakeSources().apply { block = CompletableDeferred() }
        val guard = CountingGuard()
        val job = launch { NetworkScanner(sources, guard).scan(wifi) { emptyList() }.collect { } }
        runCurrent()
        assertEquals(1, guard.held)
        job.cancel()
        runCurrent()
        assertEquals(0, guard.held)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `Wi-Fi yoksa bos ve bitmis`() = runTest {
        val snap = NetworkScanner(FakeSources(), CountingGuard()).scan(WifiState.Disconnected) { emptyList() }.first()
        assertFalse(snap.running)
        assertTrue(snap.entries.isEmpty())
    }

    @Test
    fun `IO hatasi olan host atlanir`() = runTest {
        val sources = object : ScanSources by FakeSources() {
            override suspend fun ports(ip: String, ports: List<Int>, timeoutMs: Int): Set<Int>? =
                if (ip == "192.168.1.5") throw IOException("x") else if (ip == "192.168.1.6") emptySet() else null
        }
        val snap = NetworkScanner(sources, CountingGuard()).scan(wifi) { emptyList() }.last()
        assertEquals(listOf("192.168.1.6"), snap.entries.map { it.ip })
    }

    @Test
    fun `ikon tahmini`() {
        assertEquals("pc", ScanMerger.guessIcon(true, emptySet()))
        assertEquals("pc", ScanMerger.guessIcon(false, setOf(3389, 445)))
        assertEquals("nas", ScanMerger.guessIcon(false, setOf(5000, 445)))
        assertEquals("tv", ScanMerger.guessIcon(false, setOf(8001)))
        assertEquals("tv", ScanMerger.guessIcon(false, setOf(8002, 80)))
        assertEquals("pc", ScanMerger.guessIcon(false, setOf(80)))
    }

    @Test
    fun `taranacak adresler`() {
        val hosts = ScanMerger.hostsToScan("192.168.1.88", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        // /16 ağda telefonun /24'ü taranır.
        assertEquals(253, ScanMerger.hostsToScan("10.0.5.7", 16).size)
        assertEquals(listOf("192.168.1.89", "192.168.1.90"), ScanMerger.hostsToScan("192.168.1.88", 30))
    }
}
