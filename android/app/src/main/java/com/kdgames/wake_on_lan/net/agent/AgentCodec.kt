package com.kdgames.wake_on_lan.net.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.random.Random

class AgentCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Agent mesajlarının JSON kodlaması (saf; Android bağımlılığı yok). */
object AgentCodec {
    private val RID = Regex("^[0-9a-f]{16}$")

    /** 8 rastgele bayt → 16 küçük harf hex (K5). */
    fun newRid(random: Random): String =
        random.nextBytes(8).joinToString("") { "%02x".format(it) }

    fun encode(message: AgentMessage): String = toJson(message).toString()

    fun encodeToBytes(message: AgentMessage): ByteArray {
        val bytes = encode(message).toByteArray(Charsets.UTF_8)
        if (bytes.size > AgentProtocol.MAX_DATAGRAM_BYTES) {
            throw AgentCodecException("Mesaj ${bytes.size} bayt; sınır ${AgentProtocol.MAX_DATAGRAM_BYTES}")
        }
        return bytes
    }

    fun decode(bytes: ByteArray, length: Int = bytes.size): AgentMessage {
        if (length > AgentProtocol.MAX_DATAGRAM_BYTES) throw AgentCodecException("Datagram çok büyük: $length")
        return decode(String(bytes, 0, length, Charsets.UTF_8))
    }

    fun decode(text: String): AgentMessage {
        val obj = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw AgentCodecException("Geçersiz JSON", e)
        }
        return fromJson(obj)
    }

    fun toJson(message: AgentMessage): JsonObject = buildJsonObject {
        put("v", AgentProtocol.VERSION)
        put("t", typeOf(message))
        put("rid", message.rid)
        when (message) {
            is AgentMessage.Discover, is AgentMessage.PairDenied -> Unit
            is AgentMessage.DiscoverReply -> {
                put("agentId", message.agentId)
                put("hostname", message.hostname)
                put("agentVersion", message.agentVersion)
                put("paired", message.paired)
                put("hibernate", message.hibernate)
                putNics(message.nics)
            }
            is AgentMessage.PairHello -> {
                put("phoneId", message.phoneId)
                put("phoneName", message.phoneName)
                put("phonePub", message.phonePub)
            }
            is AgentMessage.PairOk -> {
                put("agentId", message.agentId)
                put("agentPub", message.agentPub)
                put("proof", message.proof)
            }
            is AgentMessage.Challenge -> put("phoneId", message.phoneId)
            is AgentMessage.Nonce -> put("nonce", message.nonce)
            is AgentMessage.Status -> {
                put("phoneId", message.phoneId)
                put("nonce", message.nonce)
                put("sig", message.sig)
            }
            is AgentMessage.StatusOk -> {
                put("agentId", message.agentId)
                put("hostname", message.hostname)
                put("uptimeSec", message.uptimeSec)
                put("hibernate", message.hibernate)
                putNics(message.nics)
                put("sig", message.sig)
            }
            is AgentMessage.Cmd -> {
                put("phoneId", message.phoneId)
                put("nonce", message.nonce)
                put("cmd", message.cmd.name)
                put("force", message.force)
                put("sig", message.sig)
            }
            is AgentMessage.Ack -> {
                put("cmd", message.cmd.name)
                put("sig", message.sig)
            }
            is AgentMessage.Err -> put("code", message.code)
        }
    }

    fun fromJson(obj: JsonObject): AgentMessage {
        val r = Reader(obj)
        val version = r.int("v")
        if (version != AgentProtocol.VERSION) throw AgentCodecException("Desteklenmeyen sürüm: $version")
        val rid = r.string("rid")
        if (!RID.matches(rid)) throw AgentCodecException("Geçersiz rid: $rid")
        return when (val type = r.string("t")) {
            "DISCOVER" -> AgentMessage.Discover(rid)
            "DISCOVER_REPLY" -> AgentMessage.DiscoverReply(
                rid, r.string("agentId"), r.string("hostname"), r.string("agentVersion"),
                r.bool("paired"), r.bool("hibernate"), r.nics(),
            )
            "PAIR_HELLO" -> AgentMessage.PairHello(rid, r.string("phoneId"), r.string("phoneName"), r.string("phonePub"))
            "PAIR_OK" -> AgentMessage.PairOk(rid, r.string("agentId"), r.string("agentPub"), r.string("proof"))
            "PAIR_DENIED" -> AgentMessage.PairDenied(rid)
            "CHALLENGE" -> AgentMessage.Challenge(rid, r.string("phoneId"))
            "NONCE" -> AgentMessage.Nonce(rid, r.string("nonce"))
            "STATUS" -> AgentMessage.Status(rid, r.string("phoneId"), r.string("nonce"), r.string("sig"))
            "STATUS_OK" -> AgentMessage.StatusOk(
                rid, r.string("agentId"), r.string("hostname"), r.long("uptimeSec"),
                r.bool("hibernate"), r.nics(), r.string("sig"),
            )
            "CMD" -> AgentMessage.Cmd(
                rid, r.string("phoneId"), r.string("nonce"), r.command("cmd"), r.bool("force"), r.string("sig"),
            )
            "ACK" -> AgentMessage.Ack(rid, r.command("cmd"), r.string("sig"))
            "ERR" -> AgentMessage.Err(rid, r.string("code"))
            else -> throw AgentCodecException("Bilinmeyen mesaj tipi: $type")
        }
    }

    private fun typeOf(message: AgentMessage): String = when (message) {
        is AgentMessage.Discover -> "DISCOVER"
        is AgentMessage.DiscoverReply -> "DISCOVER_REPLY"
        is AgentMessage.PairHello -> "PAIR_HELLO"
        is AgentMessage.PairOk -> "PAIR_OK"
        is AgentMessage.PairDenied -> "PAIR_DENIED"
        is AgentMessage.Challenge -> "CHALLENGE"
        is AgentMessage.Nonce -> "NONCE"
        is AgentMessage.Status -> "STATUS"
        is AgentMessage.StatusOk -> "STATUS_OK"
        is AgentMessage.Cmd -> "CMD"
        is AgentMessage.Ack -> "ACK"
        is AgentMessage.Err -> "ERR"
    }

    private fun JsonObjectBuilder.putNics(nics: List<AgentNic>) {
        put("nics", buildJsonArray {
            nics.forEach { nic ->
                add(buildJsonObject {
                    put("mac", nic.mac)
                    put("name", nic.name)
                    put("wired", nic.wired)
                    put("ip", nic.ip)
                })
            }
        })
    }

    private class Reader(private val obj: JsonObject) {
        private fun primitive(key: String): JsonPrimitive =
            (obj[key] as? JsonPrimitive) ?: throw AgentCodecException("Eksik alan: $key")

        fun string(key: String): String {
            val p = primitive(key)
            if (!p.isString) throw AgentCodecException("Metin bekleniyordu: $key")
            return p.content
        }

        fun bool(key: String): Boolean = wrap(key) { primitive(key).boolean }

        fun int(key: String): Int = wrap(key) { primitive(key).int }

        fun long(key: String): Long = wrap(key) { primitive(key).long }

        fun command(key: String): AgentCommand {
            val value = string(key)
            return AgentCommand.entries.firstOrNull { it.name == value }
                ?: throw AgentCodecException("Bilinmeyen komut: $value")
        }

        fun nics(): List<AgentNic> {
            val array = obj["nics"] as? JsonArray ?: throw AgentCodecException("Eksik alan: nics")
            return array.map { element ->
                val nic = Reader(element as? JsonObject ?: throw AgentCodecException("Geçersiz NIC"))
                AgentNic(nic.string("mac"), nic.string("name"), nic.bool("wired"), nic.string("ip"))
            }
        }

        private inline fun <T> wrap(key: String, block: () -> T): T = try {
            block()
        } catch (e: AgentCodecException) {
            throw e
        } catch (e: Exception) {
            throw AgentCodecException("Geçersiz alan: $key", e)
        }
    }
}
