package com.kdgames.wake_on_lan.net.probe

import com.kdgames.wake_on_lan.net.BroadcastResolver
import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/** `ping` sürecinin ham çıktısı. */
data class PingOutput(val exitCode: Int, val output: String)

/** Testte sahte çıktı verilir. null = süreç başlatılamadı (ping yok / çalıştırılamıyor). */
fun interface PingRunner {
    fun run(args: List<String>, timeoutMs: Long): PingOutput?
}

/** `/system/bin/ping`'i süreç olarak çalıştırır. */
class SystemPingRunner @Inject constructor() : PingRunner {
    override fun run(args: List<String>, timeoutMs: Long): PingOutput? {
        if (!File(args.first()).canExecute()) return null
        val process = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return null
        }
        val out = StringBuilder()
        val reader = thread(isDaemon = true) {
            runCatching { process.inputStream.bufferedReader().use { r -> out.append(r.readText()) } }
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return PingOutput(-1, "timeout")
        }
        reader.join(200)
        return PingOutput(process.exitValue(), synchronized(out) { out.toString() })
    }
}

/**
 * S4 (spec 7.3): `/system/bin/ping -c 1 -W 1 <ip>`, 1000 ms. Windows firewall ICMP'yi varsayılan
 * olarak bloklar; bu yüzden S4 yalnız pozitif kanıttır, tek başına "kapalı" demez.
 * Ping hiç çalıştırılamazsa [available] false olur ve sonraki çağrılar süreç açmadan döner.
 */
@Singleton
class IcmpProbe @Inject constructor(
    private val runner: PingRunner,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    sealed interface Result {
        data class Reply(val ms: Long) : Result
        /** [blocked]: gönderim EPERM ile reddedildi (genelde yerel ağ izni yok); ping yine de kullanılabilir. */
        data class NoReply(val elapsedMs: Long, val blocked: Boolean = false) : Result
        data class Unavailable(val reason: String) : Result
    }

    @Volatile
    var available: Boolean = true
        private set

    private var unavailableReason = ""

    suspend fun probe(ip: String): Result {
        BroadcastResolver.parse(ip) // yalnız IPv4; süreç argümanına başka şey gitmez
        if (!available) return Result.Unavailable(unavailableReason)
        val start = clock.nowMs()
        val output = withContext(io) { runner.run(listOf(PING, "-c", "1", "-W", "1", ip), PROCESS_TIMEOUT_MS) }
        val elapsed = clock.nowMs() - start
        val result = interpret(output, elapsed)
        if (result is Result.Unavailable) {
            unavailableReason = result.reason
            available = false
        }
        return result
    }

    companion object {
        const val PING = "/system/bin/ping"
        const val TIMEOUT_MS = 1_000L

        /** `-W 1` bir saniye bekler; süreç açılışı için küçük pay. */
        const val PROCESS_TIMEOUT_MS = 1_500L

        private val TIME = Regex("""time[=<]\s*([\d.]+)\s*ms""")
        /** ICMP soketi hiç açılamıyor / ikili yok → S4 kalıcı kapalı. */
        private val BROKEN = listOf("socket:", "not found", "inaccessible or not found")

        /** Gönderim izin yüzünden reddedildi. Android 17'de ICMP de yerel ağ iznine bağlı (cihazda doğrulandı). */
        private val BLOCKED = listOf("sendmsg: operation not permitted", "sendto: operation not permitted")

        fun interpret(output: PingOutput?, elapsedMs: Long): Result {
            if (output == null) return Result.Unavailable("ping bulunamadı")
            val text = output.output
            val lower = text.lowercase()
            if (output.exitCode != 0 && BROKEN.any { it in lower }) {
                return Result.Unavailable(text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "ping çalışmıyor")
            }
            if (output.exitCode == 0) {
                val ms = TIME.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.roundToLong() ?: elapsedMs
                if (ms <= TIMEOUT_MS) return Result.Reply(ms)
            }
            return Result.NoReply(elapsedMs, blocked = BLOCKED.any { it in lower })
        }
    }
}
