package com.kdgames.wake_on_lan.net.agent

import com.kdgames.wake_on_lan.data.crypto.Hkdf
import com.kdgames.wake_on_lan.data.crypto.Hmac
import java.security.MessageDigest
import java.util.Base64

/** İmzalanan metinler; alanlar sabit sırayla "|" ile birleşir (K5). */
object Canonical {
    fun pairOk(phoneId: String, agentId: String) = "pair-ok|$phoneId|$agentId"

    fun status(nonce: String) = "status|$nonce"

    fun statusOk(nonce: String, agentId: String, uptimeSec: Long) = "status-ok|$nonce|$agentId|$uptimeSec"

    fun cmd(nonce: String, cmd: AgentCommand, force: Boolean) = "cmd|$nonce|${cmd.name}|$force"

    fun ack(nonce: String, cmd: AgentCommand) = "ack|$nonce|${cmd.name}"
}

object AgentCrypto {
    /** K = HKDF-SHA256(ikm = sır, salt = agentId‖phoneId (UTF-8), info = "wol-pair-v1", L = 32). */
    fun deriveSessionKey(sharedSecret: ByteArray, agentId: String, phoneId: String): ByteArray =
        Hkdf.derive(
            ikm = sharedSecret,
            salt = (agentId + phoneId).toByteArray(Charsets.UTF_8),
            info = AgentProtocol.PAIR_INFO.toByteArray(Charsets.UTF_8),
            length = AgentProtocol.SESSION_KEY_BYTES,
        )

    fun sign(key: ByteArray, canonical: String): String =
        Base64.getEncoder().encodeToString(Hmac.sha256(key, canonical.toByteArray(Charsets.UTF_8)))

    fun verify(key: ByteArray, canonical: String, signature: String): Boolean {
        val given = try {
            Base64.getDecoder().decode(signature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = Hmac.sha256(key, canonical.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(expected, given)
    }
}
