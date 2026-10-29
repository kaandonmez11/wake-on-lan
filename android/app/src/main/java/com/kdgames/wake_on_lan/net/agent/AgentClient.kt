package com.kdgames.wake_on_lan.net.agent

import com.kdgames.wake_on_lan.data.crypto.X25519
import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.MulticastGuard
import com.kdgames.wake_on_lan.net.WolSender
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** Eşleşmiş bir agent ile konuşmak için gereken her şey. [key] KeyVault'tan açılmış K. */
class AgentSession(val agentId: String, val phoneId: String, val key: ByteArray)

data class DiscoveredAgent(val host: String, val reply: AgentMessage.DiscoverReply)

sealed interface PairResult {
    class Paired(val agentId: String, val key: ByteArray) : PairResult

    /** Agent başka bir telefona eşli (K6): "PC'de `wolagent reset` çalıştır". */
    data object Denied : PairResult

    /** `proof` doğrulanmadı ya da agent anahtarı geçersiz: eşleşme atılır. */
    data object ProofInvalid : PairResult

    data object NoResponse : PairResult

    data class Failed(val code: String) : PairResult
}

sealed interface AgentResult<out T> {
    data class Ok<T>(val value: T, val elapsedMs: Long) : AgentResult<T>

    /** K6: PC'de `reset` yapılmış; telefon anahtarı silmeli ve yeniden keşfetmeli. */
    data object UnknownPhone : AgentResult<Nothing>

    data class Error(val code: String) : AgentResult<Nothing>

    /** Yanıt imzası doğrulanmadı; S1 pozitif sayılmaz. */
    data object BadSignature : AgentResult<Nothing>

    data object NoResponse : AgentResult<Nothing>
}

/**
 * Telefon tarafı protokol istemcisi (PROTOCOL.md). Her istek en fazla 2 kez **aynı baytlarla**
 * yeniden gönderilir (K4); agent önbelleği sayesinde komut bir kez çalışır.
 */
@Singleton
class AgentClient(
    private val transport: AgentTransport,
    private val multicast: MulticastGuard,
    private val clock: Clock,
    private val random: Random,
    private val secureRandom: SecureRandom,
) {
    @Inject
    constructor(transport: AgentTransport, multicast: MulticastGuard, clock: Clock) :
        this(transport, multicast, clock, SecureRandom().asKotlinRandom(), SecureRandom())

    private val port = AgentProtocol.PORT

    /** Subnet broadcast + 255.255.255.255'e DISCOVER; [windowMs] boyunca gelen yanıtlar (agentId başına bir). */
    suspend fun discover(subnetBroadcast: String, phoneId: String? = null, windowMs: Long = DISCOVER_WINDOW_MS): List<DiscoveredAgent> =
        multicast.withLock {
            transport.open {
                val request = AgentMessage.Discover(AgentCodec.newRid(random), phoneId)
                val bytes = AgentCodec.encodeToBytes(request)
                listOf(subnetBroadcast, WolSender.LIMITED_BROADCAST).distinct().forEach { send(it, port, bytes) }
                val found = LinkedHashMap<String, DiscoveredAgent>()
                val deadline = clock.nowMs() + windowMs
                while (true) {
                    val remaining = deadline - clock.nowMs()
                    if (remaining <= 0) break
                    val packet = receive(remaining) ?: break
                    val reply = decodeOrNull(packet.bytes) as? AgentMessage.DiscoverReply ?: continue
                    if (reply.rid == request.rid) found.putIfAbsent(reply.agentId, DiscoveredAgent(packet.host, reply))
                }
                found.values.toList()
            }
        }

    /** TOFU eşleşme (PROTOCOL.md §4.2). Yeni X25519 çifti üretir; özel anahtar yalnız bu çağrıda yaşar. */
    /**
     * TOFU eşleşme. [phone] kalıcı anahtarlıdır (K25): aynı telefon yeniden kurulsa da aynı K türetilir ve agent
     * zaten eşli olduğu bu telefona PAIR_OK'u tekrar verir.
     */
    suspend fun pair(host: String, phone: PhoneKeys, phoneName: String, attemptMs: Long = COMMAND_ATTEMPT_MS): PairResult {
        val phoneId = phone.phoneId
        val hello = AgentMessage.PairHello(AgentCodec.newRid(random), phoneId, phoneName, b64(phone.publicKey))
        val reply = transport.open { exchange(host, hello, attemptMs) } ?: return PairResult.NoResponse
        return when (reply) {
            is AgentMessage.PairOk -> {
                val key = runCatching {
                    val agentPub = Base64.getDecoder().decode(reply.agentPub)
                    val shared = X25519.sharedSecret(phone.privateKey, agentPub)
                    check(shared.any { it != 0.toByte() }) { "Sıfır ortak sır" }
                    AgentCrypto.deriveSessionKey(shared, reply.agentId, phoneId)
                }.getOrNull()
                if (key != null && AgentCrypto.verify(key, Canonical.pairOk(phoneId, reply.agentId), reply.proof)) {
                    PairResult.Paired(reply.agentId, key)
                } else PairResult.ProofInvalid
            }
            is AgentMessage.PairDenied -> PairResult.Denied
            is AgentMessage.Err -> PairResult.Failed(reply.code)
            else -> PairResult.NoResponse
        }
    }

    /** S1 (spec 7.3): CHALLENGE → STATUS, imzalı STATUS_OK. Varsayılan bütçe 600 ms. */
    suspend fun status(
        host: String,
        session: AgentSession,
        budgetMs: Long = STATUS_BUDGET_MS,
        attemptMs: Long = STATUS_ATTEMPT_MS,
    ): AgentResult<AgentMessage.StatusOk> = timed(budgetMs) {
        authenticated(host, session, attemptMs) { nonce ->
            val request = AgentMessage.Status(AgentCodec.newRid(random), session.phoneId, nonce, AgentCrypto.sign(session.key, Canonical.status(nonce)))
            when (val reply = exchange(host, request, attemptMs)) {
                is AgentMessage.StatusOk ->
                    if (reply.agentId == session.agentId &&
                        AgentCrypto.verify(session.key, Canonical.statusOk(nonce, reply.agentId, reply.uptimeSec, reply.display), reply.sig)
                    ) Outcome.Value(reply) else Outcome.BadSig
                is AgentMessage.Err -> Outcome.Err(reply.code)
                else -> Outcome.Silent
            }
        }
    }

    /** CHALLENGE → CMD → imzalı ACK. Toplam ≤ 2 sn. */
    suspend fun command(
        host: String,
        session: AgentSession,
        cmd: AgentCommand,
        force: Boolean,
        budgetMs: Long = COMMAND_BUDGET_MS,
        attemptMs: Long = COMMAND_ATTEMPT_MS,
    ): AgentResult<Unit> = timed(budgetMs) {
        authenticated(host, session, attemptMs) { nonce ->
            val flag = force && (cmd == AgentCommand.SHUTDOWN || cmd == AgentCommand.RESTART)
            val request = AgentMessage.Cmd(
                AgentCodec.newRid(random), session.phoneId, nonce, cmd, flag,
                AgentCrypto.sign(session.key, Canonical.cmd(nonce, cmd, flag)),
            )
            when (val reply = exchange(host, request, attemptMs)) {
                is AgentMessage.Ack ->
                    if (reply.cmd == cmd && AgentCrypto.verify(session.key, Canonical.ack(nonce, cmd), reply.sig)) Outcome.Value(Unit)
                    else Outcome.BadSig
                is AgentMessage.Err -> Outcome.Err(reply.code)
                else -> Outcome.Silent
            }
        }
    }

    private sealed interface Outcome<out T> {
        data class Value<T>(val value: T) : Outcome<T>
        data class Err(val code: String) : Outcome<Nothing>
        data object BadSig : Outcome<Nothing>
        data object Silent : Outcome<Nothing>
    }

    private suspend fun <T> timed(budgetMs: Long, block: suspend () -> Outcome<T>): AgentResult<T> {
        val start = clock.nowMs()
        val outcome = withTimeoutOrNull(budgetMs) { block() } ?: Outcome.Silent
        return when (outcome) {
            is Outcome.Value -> AgentResult.Ok(outcome.value, clock.nowMs() - start)
            is Outcome.Err -> if (outcome.code == AgentErrorCode.UNKNOWN_PHONE) AgentResult.UnknownPhone else AgentResult.Error(outcome.code)
            Outcome.BadSig -> AgentResult.BadSignature
            Outcome.Silent -> AgentResult.NoResponse
        }
    }

    private suspend fun <T> authenticated(
        host: String,
        session: AgentSession,
        attemptMs: Long,
        block: suspend AgentChannel.(nonce: String) -> Outcome<T>,
    ): Outcome<T> = transport.open {
        when (val reply = exchange(host, AgentMessage.Challenge(AgentCodec.newRid(random), session.phoneId), attemptMs)) {
            is AgentMessage.Nonce -> block(reply.nonce)
            is AgentMessage.Err -> Outcome.Err(reply.code)
            else -> Outcome.Silent
        }
    }

    /** İlk gönderim + en fazla [RETRIES] tekrar; hepsi aynı bayt (K4). Yalnız [host]'tan ve aynı rid'li yanıt kabul. */
    private suspend fun AgentChannel.exchange(host: String, request: AgentMessage, attemptMs: Long): AgentMessage? {
        val bytes = AgentCodec.encodeToBytes(request)
        repeat(1 + RETRIES) {
            send(host, port, bytes)
            val deadline = clock.nowMs() + attemptMs
            while (true) {
                val remaining = deadline - clock.nowMs()
                if (remaining <= 0) break
                val packet = receive(remaining) ?: break
                if (packet.host != host) continue
                val reply = decodeOrNull(packet.bytes) ?: continue
                if (reply.rid == request.rid) return reply
            }
        }
        return null
    }

    private fun decodeOrNull(bytes: ByteArray): AgentMessage? =
        try {
            AgentCodec.decode(bytes)
        } catch (_: AgentCodecException) {
            null
        }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val RETRIES = 2
        const val STATUS_BUDGET_MS = 600L
        const val STATUS_ATTEMPT_MS = 200L
        const val COMMAND_BUDGET_MS = 2_000L
        const val COMMAND_ATTEMPT_MS = 300L
        const val DISCOVER_WINDOW_MS = 1_200L
    }
}

/**
 * Telefonun kalıcı agent kimliği (K25): `ANDROID_ID`'den (uygulama imzası + cihaz başına sabit; uygulama silinip
 * kurulunca değişmez, fabrika ayarında değişir) HKDF ile phoneId ve X25519 özel anahtarı türetilir.
 * ANDROID_ID ağa hiç gönderilmez.
 */
class PhoneKeys(val phoneId: String, val privateKey: ByteArray) {
    val publicKey: ByteArray = X25519.publicKey(privateKey)

    companion object {
        private const val SALT = "wol-phone-v1"

        fun derive(seed: String): PhoneKeys {
            val ikm = seed.toByteArray(Charsets.UTF_8)
            val salt = SALT.toByteArray(Charsets.UTF_8)
            val id = com.kdgames.wake_on_lan.data.crypto.Hkdf.derive(ikm, salt, "phone-id".toByteArray(Charsets.UTF_8), 16)
            val priv = com.kdgames.wake_on_lan.data.crypto.Hkdf.derive(ikm, salt, "phone-x25519".toByteArray(Charsets.UTF_8), 32)
            return PhoneKeys(id.joinToString("") { "%02x".format(it) }, priv)
        }
    }
}
