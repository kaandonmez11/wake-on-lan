package com.kdgames.wake_on_lan.net.agent

import com.kdgames.wake_on_lan.data.crypto.X25519
import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.MulticastGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64
import kotlin.random.Random

/**
 * Bellekte agent: PROTOCOL.md'nin telefonun gördüğü davranışı (TOFU, nonce, imza, K4 önbelleği).
 * Gönderimler kaydedilir; ilk [dropFirst] isteğe yanıt verilmez (yeniden deneme testi).
 */
private class FakeAgent(val host: String = "192.168.1.26") : AgentTransport {
    val agentId = "3b9e7d10-4c2a-4e8f-a6b5-91d0c3e2f478"
    private val priv = X25519.generateKeyPair(SecureRandom()).privateKey
    var pairedPhone: String? = null
    var key: ByteArray? = null
    var display = AgentDisplay.ON
    var hibernate = true
    var dropFirst = 0
    var forgeSignatures = false
    var silentTypes = emptySet<String>()
    val sent = mutableListOf<Pair<String, ByteArray>>()
    val executed = mutableListOf<Pair<AgentCommand, Boolean>>()
    private val nonces = mutableSetOf<String>()
    private val cache = mutableMapOf<String, ByteArray>()
    private val inbox = ArrayDeque<Received>()
    var extraAgents = listOf<Pair<String, AgentMessage.DiscoverReply>>()

    override suspend fun <T> open(block: suspend AgentChannel.() -> T): T = object : AgentChannel {
        override suspend fun send(host: String, port: Int, payload: ByteArray) {
            assertEquals(AgentProtocol.PORT, port)
            sent += host to payload
            handle(host, payload)
        }

        override suspend fun receive(timeoutMs: Long): Received? {
            inbox.removeFirstOrNull()?.let { return it }
            delay(timeoutMs)
            return null
        }
    }.block()

    private fun reply(m: AgentMessage, from: String = host) {
        inbox += Received(from, AgentCodec.encodeToBytes(m))
    }

    private fun handle(target: String, payload: ByteArray) {
        val msg = AgentCodec.decode(payload)
        if (msg is AgentMessage.Discover) {
            reply(AgentMessage.DiscoverReply(msg.rid, agentId, "OFIS-PC", "1.0.0", pairedPhone != null, hibernate,
                listOf(AgentNic("D8:BB:C1:0A:2F:91", "Ethernet", true, host))))
            extraAgents.forEach { (ip, r) -> reply(r.copy(rid = msg.rid), ip) }
            return
        }
        if (target != host) return
        if (dropFirst > 0) { dropFirst--; return }
        if (msg::class.simpleName?.uppercase() in silentTypes) return
        cache[msg.rid]?.let { inbox += Received(host, it); return }
        val response: AgentMessage = when (msg) {
            is AgentMessage.PairHello -> if (pairedPhone != null) AgentMessage.PairDenied(msg.rid) else {
                val shared = X25519.sharedSecret(priv, Base64.getDecoder().decode(msg.phonePub))
                val k = AgentCrypto.deriveSessionKey(shared, agentId, msg.phoneId)
                pairedPhone = msg.phoneId; key = k
                val proofKey = if (forgeSignatures) ByteArray(32) else k
                AgentMessage.PairOk(msg.rid, agentId, b64(X25519.publicKey(priv)), AgentCrypto.sign(proofKey, Canonical.pairOk(msg.phoneId, agentId)))
            }
            is AgentMessage.Challenge -> if (msg.phoneId != pairedPhone) AgentMessage.Err(msg.rid, AgentErrorCode.UNKNOWN_PHONE) else {
                val n = b64(Random.nextBytes(16)); nonces += n; AgentMessage.Nonce(msg.rid, n)
            }
            is AgentMessage.Status -> when {
                !AgentCrypto.verify(key!!, Canonical.status(msg.nonce), msg.sig) -> AgentMessage.Err(msg.rid, AgentErrorCode.BAD_SIG)
                !nonces.remove(msg.nonce) -> AgentMessage.Err(msg.rid, AgentErrorCode.NONCE_EXPIRED)
                else -> {
                    val k = if (forgeSignatures) ByteArray(32) else key!!
                    AgentMessage.StatusOk(msg.rid, agentId, "OFIS-PC", 7200, hibernate, display, emptyList(),
                        AgentCrypto.sign(k, Canonical.statusOk(msg.nonce, agentId, 7200, display)))
                }
            }
            is AgentMessage.Cmd -> when {
                !AgentCrypto.verify(key!!, Canonical.cmd(msg.nonce, msg.cmd, msg.force), msg.sig) -> AgentMessage.Err(msg.rid, AgentErrorCode.BAD_SIG)
                !nonces.remove(msg.nonce) -> AgentMessage.Err(msg.rid, AgentErrorCode.NONCE_EXPIRED)
                msg.cmd == AgentCommand.HIBERNATE && !hibernate -> AgentMessage.Err(msg.rid, AgentErrorCode.UNSUPPORTED)
                else -> {
                    executed += msg.cmd to msg.force
                    AgentMessage.Ack(msg.rid, msg.cmd, AgentCrypto.sign(if (forgeSignatures) ByteArray(32) else key!!, Canonical.ack(msg.nonce, msg.cmd)))
                }
            }
            else -> return
        }
        val bytes = AgentCodec.encodeToBytes(response)
        cache[msg.rid] = bytes
        inbox += Received(host, bytes)
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

private object NoLock : MulticastGuard {
    override suspend fun <T> withLock(block: suspend () -> T): T = block()
}

class AgentClientTest {
    private val phoneId = "6f1c2a4e-8b3d-4f7a-9c21-0d5e7b9a1f34"

    private fun TestScope.client(agent: FakeAgent) =
        AgentClient(agent, NoLock, Clock { testScheduler.currentTime }, Random(7), SecureRandom())

    private suspend fun AgentClient.pairWith(agent: FakeAgent): AgentSession {
        val r = pair(agent.host, phoneId, "Galaxy S24")
        assertInstanceOf(PairResult.Paired::class.java, r)
        r as PairResult.Paired
        assertArrayEquals(agent.key, r.key)
        return AgentSession(r.agentId, phoneId, r.key)
    }

    @Nested
    inner class Discovery {
        @Test
        fun `subnet ve 255 e gider, agentId basina tek sonuc`() = runTest {
            val agent = FakeAgent()
            val other = AgentMessage.DiscoverReply("0", "baska-agent", "SALON-PC", "1.0.0", true, false, emptyList())
            agent.extraAgents = listOf("192.168.1.40" to other, "192.168.1.40" to other)
            val found = client(agent).discover("192.168.1.255", windowMs = 500)
            assertEquals(listOf("192.168.1.255", "255.255.255.255"), agent.sent.map { it.first })
            assertEquals(listOf(agent.agentId, "baska-agent"), found.map { it.reply.agentId })
            assertEquals(listOf("192.168.1.26", "192.168.1.40"), found.map { it.host })
        }
    }

    @Nested
    inner class Pairing {
        @Test
        fun `TOFU eslesir, K iki tarafta ayni`() = runTest {
            val agent = FakeAgent()
            client(agent).pairWith(agent)
            assertEquals(phoneId, agent.pairedPhone)
        }

        @Test
        fun `baska telefona esliyse Denied`() = runTest {
            val agent = FakeAgent().apply { pairedPhone = "baska" }
            assertEquals(PairResult.Denied, client(agent).pair(agent.host, phoneId, "x"))
        }

        @Test
        fun `proof dogrulanmazsa ProofInvalid`() = runTest {
            val agent = FakeAgent().apply { forgeSignatures = true }
            assertEquals(PairResult.ProofInvalid, client(agent).pair(agent.host, phoneId, "x"))
        }

        @Test
        fun `yanit yoksa NoResponse`() = runTest {
            val agent = FakeAgent().apply { dropFirst = 3 }
            assertEquals(PairResult.NoResponse, client(agent).pair(agent.host, phoneId, "x"))
            assertEquals(3, agent.sent.size)
        }
    }

    @Nested
    inner class Status {
        @Test
        fun `imzali STATUS_OK ve display`() = runTest {
            val agent = FakeAgent().apply { display = AgentDisplay.OFF }
            val c = client(agent)
            val session = c.pairWith(agent)
            val r = c.status(agent.host, session)
            assertInstanceOf(AgentResult.Ok::class.java, r)
            r as AgentResult.Ok
            assertEquals(7200, r.value.uptimeSec)
            assertEquals(AgentDisplay.OFF, r.value.display)
        }

        @Test
        fun `sahte imza S1 pozitif sayilmaz`() = runTest {
            val agent = FakeAgent()
            val c = client(agent)
            val session = c.pairWith(agent)
            agent.forgeSignatures = true
            assertEquals(AgentResult.BadSignature, c.status(agent.host, session))
        }

        @Test
        fun `reset yapilmis PC UnknownPhone (K6)`() = runTest {
            val agent = FakeAgent()
            val c = client(agent)
            val session = c.pairWith(agent)
            agent.pairedPhone = null
            assertEquals(AgentResult.UnknownPhone, c.status(agent.host, session))
        }

        @Test
        fun `600 ms butcesi asilinca NoResponse`() = runTest {
            val agent = FakeAgent()
            val c = client(agent)
            val session = c.pairWith(agent)
            agent.silentTypes = setOf("STATUS")
            val start = testScheduler.currentTime
            assertEquals(AgentResult.NoResponse, c.status(agent.host, session))
            assertEquals(600L, testScheduler.currentTime - start)
        }

        @Test
        fun `yeniden deneme ayni rid ve ayni baytlar (K4)`() = runTest {
            val agent = FakeAgent()
            val c = client(agent)
            val session = c.pairWith(agent)
            agent.sent.clear()
            agent.dropFirst = 2
            val r = c.status(agent.host, session, budgetMs = 5_000)
            assertInstanceOf(AgentResult.Ok::class.java, r)
            // CHALLENGE 3 kez (2 düştü), sonra STATUS 1 kez.
            val challenges = agent.sent.map { it.second }.filter { AgentCodec.decode(it) is AgentMessage.Challenge }
            assertEquals(3, challenges.size)
            assertTrue(challenges.all { it.contentEquals(challenges.first()) })
        }
    }

    @Nested
    inner class Command {
        @Test
        fun `ACK imzasi dogrulanir, komut bir kez calisir`() = runTest {
            val agent = FakeAgent()
            val c = client(agent)
            val session = c.pairWith(agent)
            assertInstanceOf(AgentResult.Ok::class.java, c.command(agent.host, session, AgentCommand.SHUTDOWN, force = true))
            assertEquals(listOf(AgentCommand.SHUTDOWN to true), agent.executed)
        }

        @Test
        fun `ACK kaybolursa ayni CMD tekrar gider, agent onbellekten yanitlar`() = runTest {
            val agent = FakeAgent()
            val c = client(agent)
            val session = c.pairWith(agent)
            // CMD'nin ilk yanıtını kaybettir: yalnız CMD'yi düşüren bir sayaç.
            agent.sent.clear()
            val result = commandDroppingFirstAck(agent, session)
            assertInstanceOf(AgentResult.Ok::class.java, result)
            assertEquals(1, agent.executed.size)
            val cmds = agent.sent.map { it.second }.filter { AgentCodec.decode(it) is AgentMessage.Cmd }
            assertEquals(2, cmds.size)
            assertArrayEquals(cmds[0], cmds[1])
        }

        @Test
        fun `force yalniz SHUTDOWN ve RESTART ta gider`() = runTest {
            val agent = FakeAgent()
            val c = client(agent)
            val session = c.pairWith(agent)
            c.command(agent.host, session, AgentCommand.LOCK, force = true)
            c.command(agent.host, session, AgentCommand.RESTART, force = false)
            c.command(agent.host, session, AgentCommand.SCREEN_ON, force = true)
            assertEquals(
                listOf(AgentCommand.LOCK to false, AgentCommand.RESTART to false, AgentCommand.SCREEN_ON to false),
                agent.executed,
            )
        }

        @Test
        fun `hibernate kapaliysa UNSUPPORTED`() = runTest {
            val agent = FakeAgent().apply { hibernate = false }
            val c = client(agent)
            val session = c.pairWith(agent)
            assertEquals(AgentResult.Error(AgentErrorCode.UNSUPPORTED), c.command(agent.host, session, AgentCommand.HIBERNATE, false))
        }

        @Test
        fun `sahte ACK reddedilir`() = runTest {
            val agent = FakeAgent()
            val c = client(agent)
            val session = c.pairWith(agent)
            agent.forgeSignatures = true
            assertEquals(AgentResult.BadSignature, c.command(agent.host, session, AgentCommand.LOCK, false))
        }
    }

    /** CMD'yi agent işler ama ilk ACK telefona ulaşmaz. */
    private suspend fun TestScope.commandDroppingFirstAck(agent: FakeAgent, session: AgentSession): AgentResult<Unit> {
        val lossy = object : AgentTransport {
            override suspend fun <T> open(block: suspend AgentChannel.() -> T): T = agent.open {
                val inner = this
                object : AgentChannel {
                    var dropped = false
                    override suspend fun send(host: String, port: Int, payload: ByteArray) = inner.send(host, port, payload)
                    override suspend fun receive(timeoutMs: Long): Received? {
                        val r = inner.receive(timeoutMs) ?: return null
                        if (!dropped && AgentCodec.decode(r.bytes) is AgentMessage.Ack) {
                            dropped = true
                            return inner.receive(timeoutMs)
                        }
                        return r
                    }
                }.block()
            }
        }
        return AgentClient(lossy, NoLock, Clock { testScheduler.currentTime }, Random(9), SecureRandom()).command(agent.host, session, AgentCommand.SLEEP, false)
    }
}

class NicSelectorTest {
    private val wifi = AgentNic("4C:03:4F:77:1E:05", "Wi-Fi", false, "192.168.1.57")
    private val wiredOther = AgentNic("00:11:22:33:44:55", "Ethernet 2", true, "10.0.0.5")
    private val wiredSame = AgentNic("D8:BB:C1:0A:2F:91", "Ethernet", true, "192.168.1.26")

    @Test
    fun `kablolu ve ayni alt ag once`() {
        assertEquals(wiredSame, NicSelector.select(listOf(wifi, wiredOther, wiredSame), "192.168.1.88", 24))
    }

    @Test
    fun `yoksa ilk kablolu, o da yoksa MAC i olan ilk`() {
        assertEquals(wiredOther, NicSelector.select(listOf(wifi, wiredOther), "192.168.1.88", 24))
        assertEquals(wifi, NicSelector.select(listOf(AgentNic("bozuk", "x", true, ""), wifi), "192.168.1.88", 24))
        assertEquals(null, NicSelector.select(emptyList(), "192.168.1.88", 24))
    }

    @Test
    fun `IP siz kablolu kart da secilebilir`() {
        val offline = AgentNic("D8:BB:C1:0A:2F:91", "Ethernet", true, "")
        assertEquals(offline, NicSelector.select(listOf(wifi, offline), "192.168.1.88", 24))
    }

    @Test
    fun `alt ag karsilastirmasi`() {
        assertTrue(NicSelector.sameSubnet("10.0.5.17", "10.0.6.1", 22))
        assertTrue(!NicSelector.sameSubnet("10.0.5.17", "10.0.8.1", 22))
        assertTrue(!NicSelector.sameSubnet("", "10.0.8.1", 22))
    }
}
