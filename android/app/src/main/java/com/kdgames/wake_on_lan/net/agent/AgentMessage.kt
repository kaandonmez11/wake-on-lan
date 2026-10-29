package com.kdgames.wake_on_lan.net.agent

/** protocol/PROTOCOL.md sabitleri. */
object AgentProtocol {
    const val VERSION = 1
    const val PORT = 40900
    const val MAX_DATAGRAM_BYTES = 1200
    const val PAIR_INFO = "wol-pair-v1"
    const val SESSION_KEY_BYTES = 32
}

enum class AgentCommand { LOCK, SLEEP, HIBERNATE, SHUTDOWN, RESTART }

/** STATUS_OK.display (K22, GUID_CONSOLE_DISPLAY_STATE). */
object AgentDisplay {
    const val ON = "on"
    const val OFF = "off"
    const val DIMMED = "dimmed"
    const val UNKNOWN = "unknown"
}

data class AgentNic(val mac: String, val name: String, val wired: Boolean, val ip: String)

/** Her mesaj tek UDP datagramı; zarf: {"v":1,"t":TYPE,"rid":...}. */
sealed interface AgentMessage {
    val rid: String

    data class Discover(override val rid: String) : AgentMessage

    data class DiscoverReply(
        override val rid: String,
        val agentId: String,
        val hostname: String,
        val agentVersion: String,
        val paired: Boolean,
        val hibernate: Boolean,
        /** K23: bu PC'de SLEEP hazırda bekletme yapar (Modern Bekleme); UI "Hazırda" gösterir. */
        val sleepHibernates: Boolean,
        val nics: List<AgentNic>,
    ) : AgentMessage

    data class PairHello(
        override val rid: String,
        val phoneId: String,
        val phoneName: String,
        val phonePub: String,
    ) : AgentMessage

    data class PairOk(
        override val rid: String,
        val agentId: String,
        val agentPub: String,
        val proof: String,
    ) : AgentMessage

    data class PairDenied(override val rid: String) : AgentMessage

    data class Challenge(override val rid: String, val phoneId: String) : AgentMessage

    data class Nonce(override val rid: String, val nonce: String) : AgentMessage

    data class Status(
        override val rid: String,
        val phoneId: String,
        val nonce: String,
        val sig: String,
    ) : AgentMessage

    data class StatusOk(
        override val rid: String,
        val agentId: String,
        val hostname: String,
        val uptimeSec: Long,
        val hibernate: Boolean,
        /** [AgentDisplay] değerlerinden biri; imzaya dahil. */
        val display: String,
        val nics: List<AgentNic>,
        val sig: String,
    ) : AgentMessage

    data class Cmd(
        override val rid: String,
        val phoneId: String,
        val nonce: String,
        val cmd: AgentCommand,
        val force: Boolean,
        val sig: String,
    ) : AgentMessage

    data class Ack(override val rid: String, val cmd: AgentCommand, val sig: String) : AgentMessage

    data class Err(override val rid: String, val code: String) : AgentMessage
}

/** Agent hata kodları (PROTOCOL.md §4). */
object AgentErrorCode {
    const val UNKNOWN_PHONE = "UNKNOWN_PHONE"
    const val BAD_SIG = "BAD_SIG"
    const val NONCE_EXPIRED = "NONCE_EXPIRED"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val BUSY = "BUSY"
}
