package com.kdgames.wake_on_lan.net.agent

import com.kdgames.wake_on_lan.TestVectors
import com.kdgames.wake_on_lan.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class AgentCodecVectorTest {
    private val key = TestVectors.sessionKey

    @TestFactory
    fun `imza vektorleri`() = TestVectors.section("signatures").map { c ->
        DynamicTest.dynamicTest(c.str("name")) {
            val canonical = c.str("canonical")
            assertEquals(c.str("sig"), AgentCrypto.sign(key, canonical))
            assertTrue(AgentCrypto.verify(key, canonical, c.str("sig")))
            assertFalse(AgentCrypto.verify(key, canonical + "x", c.str("sig")))
        }
    }

    @Test
    fun `canonical uretici imza vektorleriyle birebir ayni`() {
        val p = TestVectors.obj("pairing")
        val byName = TestVectors.section("signatures").associateBy { it.str("name") }
        assertEquals(byName.getValue("PAIR_OK.proof").str("canonical"), Canonical.pairOk(p.str("phoneId"), p.str("agentId")))
        AgentCommand.entries.forEach { cmd ->
            listOf(true, false).forEach { force ->
                val c = byName.getValue("CMD.sig/${cmd.name}/${if (force) "force" else "graceful"}")
                val nonce = c.str("canonical").split('|')[1]
                assertEquals(c.str("canonical"), Canonical.cmd(nonce, cmd, force))
            }
            val ack = byName.getValue("ACK.sig/${cmd.name}")
            assertEquals(ack.str("canonical"), Canonical.ack(ack.str("canonical").split('|')[1], cmd))
        }
    }

    @TestFactory
    fun `mesaj vektorleri cozulur, yeniden kodlanir, imzalari dogrulanir`() =
        TestVectors.section("messages").mapIndexed { i, entry ->
            DynamicTest.dynamicTest("${i + 1}. ${entry.str("name")}") {
                val json = entry.getValue("json").jsonObject
                val message = AgentCodec.decode(json.toString())
                assertEquals(json, AgentCodec.toJson(message))
                assertEquals(message, AgentCodec.decode(AgentCodec.encodeToBytes(message)))

                if (entry.containsKey("canonical")) {
                    val context = entry["context"]?.jsonObject
                    val (canonical, sig) = canonicalAndSig(message, context)
                    assertEquals(entry.str("canonical"), canonical)
                    assertEquals(entry.str("sig"), sig)
                    assertTrue(AgentCrypto.verify(key, canonical, sig))
                }
            }
        }

    @Test
    fun `tum mesaj tipleri vektorlerde var`() {
        val types = TestVectors.section("messages").map { it.getValue("json").jsonObject.str("t") }.toSet()
        assertEquals(
            setOf(
                "DISCOVER", "DISCOVER_REPLY", "PAIR_HELLO", "PAIR_OK", "PAIR_DENIED", "CHALLENGE",
                "NONCE", "STATUS", "STATUS_OK", "CMD", "ACK", "ERR",
            ),
            types,
        )
    }

    @Test
    fun `rid 16 kucuk harf hex`() {
        val rid = AgentCodec.newRid(Random(42))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(rid))
    }

    @Test
    fun `gecersiz girdiler reddedilir`() {
        assertThrows<AgentCodecException> { AgentCodec.decode("not json") }
        assertThrows<AgentCodecException> { AgentCodec.decode("""{"v":2,"t":"DISCOVER","rid":"0011223344556677"}""") }
        assertThrows<AgentCodecException> { AgentCodec.decode("""{"v":1,"t":"DISCOVER","rid":"ABC"}""") }
        assertThrows<AgentCodecException> { AgentCodec.decode("""{"v":1,"t":"NOPE","rid":"0011223344556677"}""") }
        assertThrows<AgentCodecException> { AgentCodec.decode("""{"v":1,"t":"NONCE","rid":"0011223344556677"}""") }
        assertThrows<AgentCodecException> {
            AgentCodec.decode("""{"v":1,"t":"ACK","rid":"0011223344556677","cmd":"FORMAT_C","sig":"x"}""")
        }
        assertThrows<AgentCodecException> { AgentCodec.decode(ByteArray(AgentProtocol.MAX_DATAGRAM_BYTES + 1)) }
    }

    @Test
    fun `1200 bayti asan mesaj kodlanmaz`() {
        val big = AgentMessage.Err("0011223344556677", "X".repeat(AgentProtocol.MAX_DATAGRAM_BYTES))
        assertThrows<AgentCodecException> { AgentCodec.encodeToBytes(big) }
    }

    @Test
    fun `bozuk base64 imza dogrulanmaz`() {
        assertFalse(AgentCrypto.verify(key, "status|x", "%%%"))
    }

    private fun canonicalAndSig(message: AgentMessage, context: JsonObject?): Pair<String, String> = when (message) {
        is AgentMessage.PairOk -> Canonical.pairOk(context!!.str("phoneId"), message.agentId) to message.proof
        is AgentMessage.Status -> Canonical.status(message.nonce) to message.sig
        is AgentMessage.StatusOk ->
            Canonical.statusOk(context!!.str("nonce"), message.agentId, message.uptimeSec, message.display) to message.sig
        is AgentMessage.Cmd -> Canonical.cmd(message.nonce, message.cmd, message.force) to message.sig
        is AgentMessage.Ack -> Canonical.ack(context!!.str("nonce"), message.cmd) to message.sig
        else -> error("İmzasız mesaj: $message")
    }
}
