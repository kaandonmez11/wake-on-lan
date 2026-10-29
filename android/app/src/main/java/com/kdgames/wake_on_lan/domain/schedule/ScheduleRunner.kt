package com.kdgames.wake_on_lan.domain.schedule

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.db.ScheduleRun
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RunResult
import com.kdgames.wake_on_lan.domain.model.ShutdownMode
import com.kdgames.wake_on_lan.domain.model.Verdict
import com.kdgames.wake_on_lan.domain.model.WakeStep
import com.kdgames.wake_on_lan.domain.power.AgentCommander
import com.kdgames.wake_on_lan.domain.power.UnknownPhoneHandler
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import com.kdgames.wake_on_lan.domain.wake.AttemptRecorder
import com.kdgames.wake_on_lan.domain.wake.MagicSender
import com.kdgames.wake_on_lan.domain.warn
import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.agent.AgentCommand
import com.kdgames.wake_on_lan.net.agent.AgentResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Duvar saati (epoch ms); zamanlayıcılar gerçek saate göre çalışır. Testte sabit. */
fun interface WallClock {
    fun nowMs(): Long

    companion object {
        val System = WallClock { java.lang.System.currentTimeMillis() }
    }
}

/** Ayarlar'daki tekrar aralığı (dk); doğrulama penceresi `min(60 sn, aralık)` (spec 9.4). */
fun interface RetryIntervalSource {
    suspend fun minutes(): Int
}

class SettingsRetryInterval @Inject constructor(private val settings: SettingsStore) : RetryIntervalSource {
    override suspend fun minutes() = settings.current().retryIntervalMin
}

/** Zamanlayıcı ve çalışma kayıtları (testte bellek içi). */
interface ScheduleRunStore {
    suspend fun schedule(id: Long): Schedule?
    suspend fun update(schedule: Schedule)
    suspend fun startRun(scheduleId: Long, occurrence: Long, startedAt: Long): Long
    suspend fun finishRun(runId: Long, endedAt: Long, result: RunResult, attemptsJson: String)
}

class RepositoryRunStore @Inject constructor(private val repo: ScheduleRepository) : ScheduleRunStore {
    override suspend fun schedule(id: Long) = repo.getSchedule(id)
    override suspend fun update(schedule: Schedule) = repo.update(schedule)
    override suspend fun startRun(scheduleId: Long, occurrence: Long, startedAt: Long) = repo.startRun(scheduleId, occurrence, startedAt)
    override suspend fun finishRun(runId: Long, endedAt: Long, result: RunResult, attemptsJson: String) {
        val run: ScheduleRun = repo.getRun(runId) ?: return
        repo.updateRun(run.copy(endedAt = endedAt, result = result, attemptsJson = attemptsJson))
    }
}

/** Bir denemenin sonucu (spec 9.4). */
enum class AttemptOutcome { OK, NO_REPLY, AGENT_NO_REPLY, NO_WIFI, AGENT_ERROR }

data class RunAttempt(val at: Long, val outcome: AttemptOutcome, val reason: String)

/** `attemptsJson` = `[{at, outcome, reason}]`. */
object RunAttempts {
    fun encode(list: List<RunAttempt>): String = buildJsonArray {
        list.forEach { a ->
            add(buildJsonObject {
                put("at", a.at)
                put("outcome", a.outcome.name)
                put("reason", a.reason)
            })
        }
    }.toString()

    fun decode(json: String): List<RunAttempt> = runCatching {
        Json.parseToJsonElement(json).jsonArray.map { e ->
            val o = e.jsonObject
            RunAttempt(
                o.getValue("at").jsonPrimitive.long,
                AttemptOutcome.valueOf(o.getValue("outcome").jsonPrimitive.content),
                o.getValue("reason").jsonPrimitive.content,
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Spec 9.4 + K10/K12, S08: tek deneme. Hedef zaten sağlanmışsa (WAKE ama açık; agent aksiyonu ama kapalı)
 * `SKIPPED_ALREADY`, sessiz. Atlanmışsa `SKIPPED_BY_USER`. Aksi hâlde aksiyon uygulanır ve en fazla
 * `min(60 sn, retryInterval)` doğrulanır. Retry zinciri S09'da.
 */
@Singleton
class ScheduleRunner @Inject constructor(
    private val store: ScheduleRunStore,
    private val engine: StatusEngine,
    private val magic: MagicSender,
    private val commander: AgentCommander,
    private val recorder: AttemptRecorder,
    private val unknownPhone: UnknownPhoneHandler,
    private val notifier: ScheduleNotifier,
    private val retry: RetryIntervalSource,
    private val clock: Clock,
    private val wall: WallClock,
) {
    /** @param packetSent alarm alıcısı ilk magic packet turunu zaten gönderdi. */
    suspend fun run(scheduleId: Long, occurrence: Long, packetSent: Boolean = false): RunResult? {
        val schedule = store.schedule(scheduleId) ?: return null
        if (!schedule.enabled) return null
        val started = wall.nowMs()
        if (schedule.skippedOccurrence == occurrence) {
            val runId = store.startRun(scheduleId, occurrence, started)
            store.update(schedule.copy(skippedOccurrence = null))
            store.finishRun(runId, wall.nowMs(), RunResult.SKIPPED_BY_USER, "[]")
            return RunResult.SKIPPED_BY_USER
        }
        val runId = store.startRun(scheduleId, occurrence, started)
        var deviceName = "Cihaz"
        val notify = schedule.notify
        val (result, attempts) = try {
            execute(schedule, packetSent) { name ->
                deviceName = name
                if (notify) notifier.started(scheduleId, name, schedule.action)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            store.finishRun(runId, wall.nowMs(), RunResult.FAILED, RunAttempts.encode(listOf(RunAttempt(wall.nowMs(), AttemptOutcome.NO_REPLY, "İş durduruldu"))))
            throw e
        } catch (e: Exception) {
            warn(TAG, "Çalışma hatası", e)
            RunResult.FAILED to listOf(RunAttempt(wall.nowMs(), AttemptOutcome.AGENT_ERROR, "Hata: ${e.javaClass.simpleName}"))
        }
        store.finishRun(runId, wall.nowMs(), result, RunAttempts.encode(attempts))
        if (notify && (result == RunResult.SUCCESS || result == RunResult.FAILED)) {
            val last = attempts.lastOrNull()
            // Başarıda yalnız uyanma süresi eklenir ("Açıldı · 12 sn'de"); hatada sebep.
            val reason = when {
                result == RunResult.FAILED -> last?.reason
                schedule.action == PowerAction.WAKE -> last?.reason
                else -> null
            }
            runCatching { notifier.finished(scheduleId, deviceName, schedule.action, result, reason) }
        }
        return result
    }

    /** [onSend] aksiyon gerçekten gönderilmeden hemen önce (cihaz adıyla); atlamalarda çağrılmaz. */
    private suspend fun execute(schedule: Schedule, packetSent: Boolean, onSend: (String) -> Unit): Pair<RunResult, List<RunAttempt>> {
        val device = engine.awaitDevice(schedule.deviceId)
            ?: return RunResult.FAILED to listOf(RunAttempt(wall.nowMs(), AttemptOutcome.AGENT_ERROR, "Cihaz bulunamadı"))
        return when (probe(device)) {
            Probe.NO_WIFI -> {
                onSend(device.name)
                RunResult.FAILED to listOf(RunAttempt(wall.nowMs(), AttemptOutcome.NO_WIFI, "Wi-Fi yok"))
            }
            Probe.ON -> if (schedule.action == PowerAction.WAKE) RunResult.SKIPPED_ALREADY to emptyList()
            else { onSend(device.name); agentAction(device, schedule.action) }
            // K12: kapalı PC'ye güç komutu gönderilmez.
            Probe.OFF -> if (schedule.action == PowerAction.WAKE) { onSend(device.name); wake(device, packetSent) }
            else RunResult.SKIPPED_ALREADY to emptyList()
        }
    }

    private enum class Probe { ON, OFF, NO_WIFI }

    /** 3 tura kadar (1 sn arayla); bir pozitif = açık. Hiç tur sayılmadıysa Wi-Fi yok. */
    private suspend fun probe(device: Device): Probe {
        var counted = false
        repeat(PROBE_ROUNDS) { i ->
            val info = engine.round(device.id)
            if (info != null) {
                counted = true
                if (info.verdict is Verdict.Positive) return Probe.ON
            }
            if (i < PROBE_ROUNDS - 1) delay(StatusEngine.TRANSITION_INTERVAL_MS)
        }
        return if (counted) Probe.OFF else Probe.NO_WIFI
    }

    private suspend fun window(): Long = minOf(VERIFY_MAX_MS, retry.minutes() * 60_000L)

    private suspend fun wake(device: Device, packetSent: Boolean): Pair<RunResult, List<RunAttempt>> {
        val at = wall.nowMs()
        val mac = device.mac ?: return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.NO_REPLY, "MAC yok · paket gönderilemedi"))
        val start = clock.nowMs()
        engine.setOverlay(device.id, DeviceState.Waking(WakeStep.REPLY, 0))
        try {
            if (!packetSent) {
                try {
                    magic.send(mac) {}
                } catch (_: NoWifiException) {
                    return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.NO_WIFI, "Wi-Fi yok"))
                } catch (_: IOException) {
                    return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.NO_WIFI, "Ağ hatası"))
                }
            }
            val limit = window()
            while (clock.nowMs() - start < limit) {
                val roundStart = clock.nowMs()
                engine.setOverlay(device.id, DeviceState.Waking(WakeStep.REPLY, ((roundStart - start) / 1000).toInt()))
                if (engine.round(device.id)?.verdict is Verdict.Positive) {
                    val sec = ((clock.nowMs() - start) / 1000).toInt()
                    recorder.record(device.id, at, true, sec, AttemptSource.SCHEDULE)
                    engine.markTried(device.id)
                    return RunResult.SUCCESS to listOf(RunAttempt(at, AttemptOutcome.OK, "$sec sn'de"))
                }
                delay((StatusEngine.TRANSITION_INTERVAL_MS - (clock.nowMs() - roundStart)).coerceAtLeast(0))
            }
            recorder.record(device.id, at, false, null, AttemptSource.SCHEDULE)
            engine.markTried(device.id)
            return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.NO_REPLY, "Paket gitti · cevap yok"))
        } finally {
            engine.setOverlay(device.id, null)
        }
    }

    private suspend fun agentAction(device: Device, action: PowerAction): Pair<RunResult, List<RunAttempt>> {
        val at = wall.nowMs()
        if (device.agentId == null) return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.AGENT_ERROR, "Agent eşli değil"))
        val restartOrShutdown = action == PowerAction.SHUTDOWN || action == PowerAction.RESTART
        val force = restartOrShutdown && device.shutdownMode == ShutdownMode.FORCE
        val result = try {
            commander.command(device, action.toCommand(), force)
        } catch (_: NoWifiException) {
            return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.NO_WIFI, "Wi-Fi yok"))
        } catch (_: IOException) {
            return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.AGENT_NO_REPLY, "Agent cevap vermedi"))
        }
        when (result) {
            is AgentResult.Ok -> Unit
            AgentResult.NoResponse -> return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.AGENT_NO_REPLY, "Agent cevap vermedi"))
            AgentResult.UnknownPhone -> {
                runCatching { unknownPhone.onUnknownPhone(device) }
                return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.AGENT_ERROR, "PC bu telefonu tanımıyor"))
            }
            AgentResult.BadSignature -> return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.AGENT_ERROR, "İmza doğrulanmadı"))
            is AgentResult.Error -> return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.AGENT_ERROR, "Agent hatası: ${result.code}"))
        }
        // Kilitle ve Yeniden'de ACK yeter; kapanan aksiyonlarda PC'nin düştüğü görülür.
        if (action == PowerAction.LOCK || action == PowerAction.RESTART) {
            return RunResult.SUCCESS to listOf(RunAttempt(at, AttemptOutcome.OK, "Komut uygulandı"))
        }
        val start = clock.nowMs()
        val limit = maxOf(window(), OFF_VERIFY_MIN_MS)
        var negatives = 0
        while (clock.nowMs() - start < limit) {
            val roundStart = clock.nowMs()
            val info = engine.round(device.id)
            if (info != null) negatives = if (info.verdict is Verdict.Positive) 0 else negatives + 1
            if (negatives >= OFF_ROUNDS) {
                engine.settleOff(device.id, action)
                return RunResult.SUCCESS to listOf(RunAttempt(at, AttemptOutcome.OK, "PC kapandı"))
            }
            delay((StatusEngine.TRANSITION_INTERVAL_MS - (clock.nowMs() - roundStart)).coerceAtLeast(0))
        }
        return RunResult.FAILED to listOf(RunAttempt(at, AttemptOutcome.NO_REPLY, "Komut gitti · PC hâlâ açık"))
    }

    private fun PowerAction.toCommand(): AgentCommand = when (this) {
        PowerAction.LOCK -> AgentCommand.LOCK
        PowerAction.SLEEP -> AgentCommand.SLEEP
        PowerAction.HIBERNATE -> AgentCommand.HIBERNATE
        PowerAction.SHUTDOWN -> AgentCommand.SHUTDOWN
        PowerAction.RESTART -> AgentCommand.RESTART
        PowerAction.WAKE -> error("WAKE agent komutu değil")
    }

    companion object {
        const val PROBE_ROUNDS = 3
        const val VERIFY_MAX_MS = 60_000L
        /** Kapanma Windows'ta sürer; 1 dk'lık aralıkta bile en az 90 sn beklenir (PowerCoordinator ile aynı). */
        const val OFF_VERIFY_MIN_MS = 90_000L
        const val OFF_ROUNDS = 2
        private const val TAG = "ScheduleRunner"
    }
}
