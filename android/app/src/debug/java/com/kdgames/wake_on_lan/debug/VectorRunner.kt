package com.kdgames.wake_on_lan.debug

import com.kdgames.wake_on_lan.data.crypto.Hkdf
import com.kdgames.wake_on_lan.data.crypto.X25519
import com.kdgames.wake_on_lan.net.BroadcastResolver
import com.kdgames.wake_on_lan.net.MacAddress
import com.kdgames.wake_on_lan.net.MagicPacket
import com.kdgames.wake_on_lan.net.agent.AgentCodec
import com.kdgames.wake_on_lan.net.agent.AgentCrypto
import com.kdgames.wake_on_lan.net.agent.AgentMessage
import com.kdgames.wake_on_lan.net.agent.Canonical
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import javax.crypto.KeyAgreement

/** protocol/vectors.json'u cihazda, uygulamanın kendi koduyla çalıştırır (birim testlerinin cihaz eşi). */
class VectorRunner(private val vectorsJson: String) {

    data class Group(val name: String, val passed: Int, val total: Int, val failures: List<String>) {
        val ok get() = passed == total
    }

    data class Report(val groups: List<Group>, val providerInfo: String) {
        val passed get() = groups.sumOf { it.passed }
        val total get() = groups.sumOf { it.total }
    }

    private val root = Json.parseToJsonElement(vectorsJson).jsonObject
    private val sessionKey = root.getValue("pairing").jsonObject.b64("sessionKey")

    fun run(): Report = Report(
        groups = listOf(
            group("X25519", rfc7748Case() + section("x25519").map(::x25519Case)) { it() },
            group("HKDF", section("hkdf").map(::hkdfCase)) { it() },
            group("Eşleşme anahtarı", listOf(::pairingCase)) { it.invoke() },
            group("İmzalar", section("signatures").map(::signatureCase)) { it() },
            group("Mesajlar", section("messages").map(::messageCase)) { it() },
            group("Magic packet", section("magicPackets").map(::magicCase)) { it() },
            group("Broadcast", section("broadcast").map(::broadcastCase)) { it() },
            group("MAC", section("macAddresses").map(::macCase)) { it() },
        ),
        providerInfo = runCatching { KeyAgreement.getInstance("XDH").provider.name }
            .getOrElse { "XDH sağlayıcısı yok: ${it.message}" },
    )

    private fun <T> group(name: String, cases: List<T>, check: (T) -> Pair<String, Boolean>): Group {
        val failures = mutableListOf<String>()
        var passed = 0
        cases.forEach { case ->
            val (label, ok) = runCatching { check(case) }.getOrElse { "${it::class.simpleName}: ${it.message}" to false }
            if (ok) passed++ else failures += label
        }
        return Group(name, passed, cases.size, failures)
    }

    private fun section(name: String): List<JsonObject> = root.getValue(name).jsonArray.map { it.jsonObject }

    // Her vaka bir (etiket, geçti mi) çifti üretir.
    private fun rfc7748Case(): List<() -> Pair<String, Boolean>> = listOf {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = X25519.sharedSecret(alicePriv, bobPub)
        "RFC 7748 bilinen sonuç" to shared.contentEquals(hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"))
    }

    private fun x25519Case(c: JsonObject): () -> Pair<String, Boolean> = {
        val ok = X25519.publicKey(c.b64("alicePriv")).contentEquals(c.b64("alicePub")) &&
            X25519.publicKey(c.b64("bobPriv")).contentEquals(c.b64("bobPub")) &&
            X25519.sharedSecret(c.b64("alicePriv"), c.b64("bobPub")).contentEquals(c.b64("shared")) &&
            X25519.sharedSecret(c.b64("bobPriv"), c.b64("alicePub")).contentEquals(c.b64("shared"))
        c.str("name") to ok
    }

    private fun hkdfCase(c: JsonObject): () -> Pair<String, Boolean> = {
        val okm = Hkdf.derive(c.b64("ikm"), c.b64("salt"), c.b64("info"), c.getValue("length").jsonPrimitive.int)
        c.str("name") to okm.contentEquals(c.b64("okm"))
    }

    private fun pairingCase(): Pair<String, Boolean> {
        val p = root.getValue("pairing").jsonObject
        val shared = X25519.sharedSecret(p.b64("phonePriv"), p.b64("agentPub"))
        val key = AgentCrypto.deriveSessionKey(shared, p.str("agentId"), p.str("phoneId"))
        return "K türetimi" to (shared.contentEquals(p.b64("sharedSecret")) && key.contentEquals(sessionKey))
    }

    private fun signatureCase(c: JsonObject): () -> Pair<String, Boolean> = {
        val canonical = c.str("canonical")
        val ok = AgentCrypto.sign(sessionKey, canonical) == c.str("sig") &&
            AgentCrypto.verify(sessionKey, canonical, c.str("sig")) &&
            !AgentCrypto.verify(sessionKey, canonical + "x", c.str("sig"))
        c.str("name") to ok
    }

    private fun messageCase(entry: JsonObject): () -> Pair<String, Boolean> = {
        val json = entry.getValue("json").jsonObject
        val message = AgentCodec.decode(json.toString())
        var ok = AgentCodec.toJson(message) == json && AgentCodec.decode(AgentCodec.encodeToBytes(message)) == message
        if (entry.containsKey("canonical")) {
            val context = entry["context"]?.jsonObject
            val canonical = when (message) {
                is AgentMessage.PairOk -> Canonical.pairOk(context!!.str("phoneId"), message.agentId)
                is AgentMessage.Status -> Canonical.status(message.nonce)
                is AgentMessage.StatusOk -> Canonical.statusOk(context!!.str("nonce"), message.agentId, message.uptimeSec, message.display)
                is AgentMessage.Cmd -> Canonical.cmd(message.nonce, message.cmd, message.force)
                is AgentMessage.Ack -> Canonical.ack(context!!.str("nonce"), message.cmd)
                else -> ""
            }
            ok = ok && canonical == entry.str("canonical") && AgentCrypto.verify(sessionKey, canonical, entry.str("sig"))
        }
        entry.str("name") to ok
    }

    private fun magicCase(c: JsonObject): () -> Pair<String, Boolean> = {
        val packet = MagicPacket.build(c.str("mac"))
        c.str("mac") to (packet.joinToString("") { "%02x".format(it) } == c.str("packetHex"))
    }

    private fun broadcastCase(c: JsonObject): () -> Pair<String, Boolean> = {
        val ip = c.str("ip")
        val prefix = c.getValue("prefix").jsonPrimitive.int
        val expectError = c["error"]?.jsonPrimitive?.boolean == true
        val result = runCatching { BroadcastResolver.compute(ip, prefix) }
        "$ip/$prefix" to if (expectError) result.isFailure else result.getOrNull() == c.str("broadcast")
    }

    private fun macCase(c: JsonObject): () -> Pair<String, Boolean> = {
        val input = c.str("input")
        val valid = c.getValue("valid").jsonPrimitive.boolean
        val normalized = MacAddress.normalize(input)
        "'$input'" to (MacAddress.isValid(input) == valid && normalized == (if (valid) c.str("normalized") else null))
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.b64(key: String): ByteArray = Base64.getDecoder().decode(str(key))
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
