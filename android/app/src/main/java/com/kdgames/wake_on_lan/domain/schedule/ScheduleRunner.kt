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
import com.kdgames.wake_on_lan.domain.model.ScheduleType
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Duvar saati (epoch ms); zamanlayıcılar gerçek saate göre çalışır. Testte sabit. */
fun interface WallClock {
    fun nowMs(): Long

    companion object {
        val System = WallClock { java.lang.System.currentTimeMillis() }
    }
}

/** Ayarlar'daki tekrar kuralı (spec 6): aralık ve vazgeçme süresi, dakika. */
data class RetrySettings(val intervalMin: Int, val giveUpMin: Int) {
    val intervalMs: Long get() = intervalMin * 60_000L
    val giveUpMs: Long get() = giveUpMin * 60_000L
}

fun interface RetrySettingsSource {
    suspend fun current(): RetrySettings
}

class SettingsRetry @Inject constructor(private val settings: SettingsStore) : RetrySettingsSource {
    override suspend fun current() = settings.current().let { RetrySettings(it.retryIntervalMin, it.giveUpMin) }
}

/** Tekrar alarmı (çalışma başına tek; testte kaydedici). [index] = yapılacak denemenin sırası (0'dan). */
interface RetryAlarms {
    fun set(runId: Long, index: Int, at: Long)
    fun cancel(runId: Long)
}

/** Zamanlayıcı ve çalışma kayıtları (testte bellek içi). */
interface ScheduleRunStore {
    suspend fun schedule(id: Long): Schedule?
    suspend fun update(schedule: Schedule)
    suspend fun startRun(scheduleId: Long, occurrence: Long, startedAt: Long): Long
    suspend fun run(runId: Long): ScheduleRun?
    /** Aynı planlı çalışma ikinci kez gelirse (WorkManager yeniden başlattı) yeni kayıt açılmaz. */
    suspend fun runFor(scheduleId: Long, occurrence: Long): ScheduleRun?
    suspend fun running(): List<ScheduleRun>
    /** Zincir sürerken denemeler (sonuç `RUNNING` kalır). */
    suspend fun saveAttempts(runId: Long, attemptsJson: String)
    suspend fun finishRun(runId: Long, endedAt: Long, result: RunResult, attemptsJson: String)
}

class RepositoryRunStore @Inject constructor(private val repo: ScheduleRepository) : ScheduleRunStore {
    override suspend fun schedule(id: Long) = repo.getSchedule(id)
    override suspend fun update(schedule: Schedule) = repo.update(schedule)
    override suspend fun startRun(scheduleId: Long, occurrence: Long, startedAt: Long) = repo.startRun(scheduleId, occurrence, startedAt)
    override suspend fun run(runId: Long) = repo.getRun(runId)
    override suspend fun runFor(scheduleId: Long, occurrence: Long) = repo.runFor(scheduleId, occurrence)
    override suspend fun running() = repo.runningRuns()
    override suspend fun saveAttempts(runId: Long, attemptsJson: String) {
        val run = repo.getRun(runId) ?: return
        repo.updateRun(run.copy(attemptsJson = attemptsJson))
    }
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
 * Spec 9.4 + K10/K12/K13. Çalışma = bir ya da daha çok **deneme**.
 * - İlk denemede hedef zaten sağlanmışsa (WAKE ama açık; agent aksiyonu ama kapalı) `SKIPPED_ALREADY`, sessiz.
 * - Başarısız deneme → `retryIntervalMin` sonra tekrar ([RetryAlarms], exact). Denemeler ilk denemeye göre
 *   t = 0, aralık, 2·aralık… zamanlarında; sıradaki deneme `giveUpMin`'i aşacaksa vazgeçilir → `FAILED` + bildirim.
 * - Wi-Fi yoksa deneme `NO_WIFI` sayılır, zincir sürer.
 * - Tekrar denemede hedef sağlanmışsa (ör. elle uyandırıldı) başarı sayılır.
 * Zamanlayıcı başına kilit + deneme sırası kontrolü: aynı deneme iki kez çalışmaz (alarm + WorkManager tekrarı).
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
    private val retry: RetrySettingsSource,
    private val alarms: RetryAlarms,
    private val clock: Clock,
    private val wall: WallClock,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    private fun lock(scheduleId: Long) = locks.getOrPut(scheduleId) { Mutex() }

    /**
     * Planlı çalışmanın ilk denemesi. Dönen değer: bitti ise sonuç, zincir sürüyorsa `RUNNING`.
     * @param packetSent alarm alıcısı ilk magic packet turunu zaten gönderdi.
     */
    suspend fun run(scheduleId: Long, occurrence: Long, packetSent: Boolean = false): RunResult? {
        val existing = store.runFor(scheduleId, occurrence)
        if (existing != null) {
            // Aynı planlı çalışma ikinci kez (WorkManager işi yeniden başlattı): yalnız ilk deneme hiç
            // yapılamadıysa (süreç deneme ortasında öldü) yapılır; sonrakiler tekrar alarmının işi.
            return if (existing.result == RunResult.RUNNING) retry(existing.id, 0, packetSent) else existing.result
        }
        return lock(scheduleId).withLock {
            if (store.runFor(scheduleId, occurrence) != null) return@withLock null
            val schedule = store.schedule(scheduleId) ?: return@withLock null
            if (!schedule.enabled) return@withLock null
            val started = wall.nowMs()
            val skipped = when (schedule.type) {
                ScheduleType.TIME -> schedule.skippedOccurrence == occurrence
                ScheduleType.WIFI_CONNECT -> schedule.skipNextEvent
            }
            if (skipped) {
                val runId = store.startRun(scheduleId, occurrence, started)
                store.update(schedule.copy(skippedOccurrence = null, skipNextEvent = false))
                store.finishRun(runId, wall.nowMs(), RunResult.SKIPPED_BY_USER, "[]")
                return@withLock RunResult.SKIPPED_BY_USER
            }
            val runId = store.startRun(scheduleId, occurrence, started)
            attempt(runId, schedule, started, emptyList(), packetSent)
        }
    }

    /** Tekrar alarmı: [index]. deneme. Sıra tutmuyorsa (o deneme yapılmış) hiçbir şey yapılmaz. */
    suspend fun retry(runId: Long, index: Int, packetSent: Boolean = false): RunResult? {
        val first = store.run(runId) ?: return null
        return lock(first.scheduleId).withLock {
            val run = store.run(runId) ?: return@withLock null
            if (run.result != RunResult.RUNNING) return@withLock run.result
            val previous = RunAttempts.decode(run.attemptsJson)
            if (previous.size != index) return@withLock RunResult.RUNNING
            val schedule = store.schedule(run.scheduleId) ?: return@withLock null
            if (!schedule.enabled) {
                // Zincir sürerken kullanıcı kapattı: sessizce biter.
                store.finishRun(runId, wall.nowMs(), RunResult.SKIPPED_BY_USER, run.attemptsJson)
                return@withLock RunResult.SKIPPED_BY_USER
            }
            attempt(runId, schedule, run.startedAt, previous, packetSent)
        }
    }

    /**
     * Boot / güncelleme sonrası (spec 9.4): süren zincirlerin tekrar alarmları yeniden kurulur; zamanı geçmiş
     * deneme hemen yapılır, vazgeçme süresi dolmuş zincir `FAILED` olur.
     */
    suspend fun resumeChains() {
        val policy = retry.current()
        val now = wall.nowMs()
        for (run in store.running()) {
            val attempts = RunAttempts.decode(run.attemptsJson)
            val offset = attempts.size * policy.intervalMs
            when {
                offset <= policy.giveUpMs -> alarms.set(run.id, attempts.size, maxOf(run.startedAt + offset, now + RESUME_DELAY_MS))
                else -> lock(run.scheduleId).withLock {
                    val schedule = store.schedule(run.scheduleId)
                    store.finishRun(run.id, now, RunResult.FAILED, run.attemptsJson)
                    // Telefon saatlerce kapalı kaldıysa eski hatayı bildirmeye gerek yok.
                    if (schedule != null && now - (run.startedAt + policy.giveUpMs) < STALE_FAILURE_MS) {
                        val name = engine.awaitDevice(schedule.deviceId, 2_000)?.name ?: "Cihaz"
                        notifyFailed(schedule, run.id, name, attempts)
                    }
                }
            }
        }
    }

    private suspend fun attempt(runId: Long, schedule: Schedule, firstAt: Long, previous: List<RunAttempt>, packetSent: Boolean): RunResult {
        val firstAttempt = previous.isEmpty()
        var deviceName = "Cihaz"
        val step = try {
            execute(schedule, packetSent, firstAttempt) { name ->
                deviceName = name
                if (schedule.notify && firstAttempt) runCatching { notifier.started(schedule.id, name, schedule.action) }
            }
        } catch (e: CancellationException) {
            // İş durduruldu (ör. sistem kapandı): deneme başarısız sayılır, zincir sürer.
            withContext(NonCancellable) {
                conclude(runId, schedule, firstAt, previous + RunAttempt(wall.nowMs(), AttemptOutcome.NO_REPLY, "İş durduruldu"), Step.Failed(), deviceName)
            }
            throw e
        } catch (e: Exception) {
            warn(TAG, "Çalışma hatası", e)
            Step.Failed(RunAttempt(wall.nowMs(), AttemptOutcome.AGENT_ERROR, "Hata: ${e.javaClass.simpleName}"))
        }
        val attempts = previous + listOfNotNull(step.attempt)
        return conclude(runId, schedule, firstAt, attempts, step, deviceName)
    }

    private suspend fun conclude(runId: Long, schedule: Schedule, firstAt: Long, attempts: List<RunAttempt>, step: Step, deviceName: String): RunResult {
        val json = RunAttempts.encode(attempts)
        when (step) {
            is Step.Done -> {
                store.finishRun(runId, wall.nowMs(), step.result, json)
                if (schedule.notify && step.result == RunResult.SUCCESS) {
                    // Başarıda yalnız uyanma süresi eklenir ("Açıldı · 12 sn'de").
                    val reason = if (schedule.action == PowerAction.WAKE) step.attempt?.reason else null
                    runCatching { notifier.finished(schedule.id, deviceName, schedule.action, RunResult.SUCCESS, reason) }
                }
                return step.result
            }
            is Step.Failed -> {
                val policy = retry.current()
                val offset = attempts.size * policy.intervalMs
                if (!step.terminal && offset <= policy.giveUpMs) {
                    store.saveAttempts(runId, json)
                    runCatching { alarms.set(runId, attempts.size, firstAt + offset) }.onFailure { warn(TAG, "Tekrar alarmı kurulamadı", it) }
                    if (schedule.notify) {
                        val last = attempts.lastOrNull()?.reason
                        runCatching { notifier.retrying(schedule.id, deviceName, schedule.action, attempts.size + 1, last) }
                    }
                    return RunResult.RUNNING
                }
                store.finishRun(runId, wall.nowMs(), RunResult.FAILED, json)
                notifyFailed(schedule, runId, deviceName, attempts)
                return RunResult.FAILED
            }
        }
    }

    /** Spec 9.4: `FAILED` her zaman bildirilir ("Bildirim gönder" kapalı olsa da); dokununca başarısız ekranı. */
    private fun notifyFailed(schedule: Schedule, runId: Long, deviceName: String, attempts: List<RunAttempt>) {
        runCatching { notifier.failed(schedule.id, runId, deviceName, schedule.action, attempts) }
    }

    private sealed interface Step {
        val attempt: RunAttempt?

        data class Done(val result: RunResult, override val attempt: RunAttempt?) : Step

        /** [terminal]: tekrar denemenin anlamı yok (cihaz silinmiş, agent eşli değil). */
        data class Failed(override val attempt: RunAttempt? = null, val terminal: Boolean = false) : Step
    }

    private fun ok(result: Pair<RunResult, RunAttempt>): Step =
        if (result.first == RunResult.SUCCESS) Step.Done(RunResult.SUCCESS, result.second) else Step.Failed(result.second)

    /** [onSend] aksiyon gerçekten gönderilmeden hemen önce (cihaz adıyla); atlamalarda çağrılmaz. */
    private suspend fun execute(schedule: Schedule, packetSent: Boolean, firstAttempt: Boolean, onSend: (String) -> Unit): Step {
        val device = engine.awaitDevice(schedule.deviceId)
            ?: return Step.Failed(RunAttempt(wall.nowMs(), AttemptOutcome.AGENT_ERROR, "Cihaz bulunamadı"), terminal = true)
        val at = wall.nowMs()
        val wake = schedule.action == PowerAction.WAKE
        return when (probe(device)) {
            Probe.NO_WIFI -> {
                onSend(device.name)
                Step.Failed(RunAttempt(at, AttemptOutcome.NO_WIFI, "Wi-Fi yok"))
            }
            Probe.ON -> when {
                !wake -> { onSend(device.name); ok(agentAction(device, schedule.action)) }
                firstAttempt -> Step.Done(RunResult.SKIPPED_ALREADY, null)
                // Zincir sürerken PC açılmış (ör. elle): hedefe ulaşıldı.
                else -> Step.Done(RunResult.SUCCESS, RunAttempt(at, AttemptOutcome.OK, "PC açık"))
            }
            Probe.OFF -> when {
                wake -> { onSend(device.name); ok(wake(device, packetSent)) }
                // K12: kapalı PC'ye güç komutu gönderilmez.
                firstAttempt || schedule.action == PowerAction.LOCK || schedule.action == PowerAction.RESTART ->
                    Step.Done(RunResult.SKIPPED_ALREADY, null)
                else -> Step.Done(RunResult.SUCCESS, RunAttempt(at, AttemptOutcome.OK, "PC kapandı"))
            }
        }.let { step ->
            // Agent eşli değilse tekrar denemek bir şey değiştirmez.
            if (step is Step.Failed && step.attempt?.reason == NOT_PAIRED) step.copy(terminal = true) else step
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

    /** Doğrulama penceresi `min(60 sn, aralık)` (spec 9.4). */
    private suspend fun window(): Long = minOf(VERIFY_MAX_MS, retry.current().intervalMs)

    private suspend fun wake(device: Device, packetSent: Boolean): Pair<RunResult, RunAttempt> {
        val at = wall.nowMs()
        val mac = device.mac ?: return RunResult.FAILED to RunAttempt(at, AttemptOutcome.NO_REPLY, "MAC yok · paket gönderilemedi")
        val start = clock.nowMs()
        engine.setOverlay(device.id, DeviceState.Waking(WakeStep.REPLY, 0))
        try {
            if (!packetSent) {
                try {
                    magic.send(mac) {}
                } catch (_: NoWifiException) {
                    return RunResult.FAILED to RunAttempt(at, AttemptOutcome.NO_WIFI, "Wi-Fi yok")
                } catch (_: IOException) {
                    return RunResult.FAILED to RunAttempt(at, AttemptOutcome.NO_WIFI, "Ağ hatası")
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
                    return RunResult.SUCCESS to RunAttempt(at, AttemptOutcome.OK, "$sec sn'de")
                }
                delay((StatusEngine.TRANSITION_INTERVAL_MS - (clock.nowMs() - roundStart)).coerceAtLeast(0))
            }
            recorder.record(device.id, at, false, null, AttemptSource.SCHEDULE)
            engine.markTried(device.id)
            return RunResult.FAILED to RunAttempt(at, AttemptOutcome.NO_REPLY, "Paket gitti · cevap yok")
        } finally {
            engine.setOverlay(device.id, null)
        }
    }

    private suspend fun agentAction(device: Device, action: PowerAction): Pair<RunResult, RunAttempt> {
        val at = wall.nowMs()
        if (device.agentId == null) return RunResult.FAILED to RunAttempt(at, AttemptOutcome.AGENT_ERROR, NOT_PAIRED)
        val restartOrShutdown = action == PowerAction.SHUTDOWN || action == PowerAction.RESTART
        val force = restartOrShutdown && device.shutdownMode == ShutdownMode.FORCE
        val result = try {
            commander.command(device, action.toCommand(), force)
        } catch (_: NoWifiException) {
            return RunResult.FAILED to RunAttempt(at, AttemptOutcome.NO_WIFI, "Wi-Fi yok")
        } catch (_: IOException) {
            return RunResult.FAILED to RunAttempt(at, AttemptOutcome.AGENT_NO_REPLY, "Agent cevap vermedi")
        }
        when (result) {
            is AgentResult.Ok -> Unit
            AgentResult.NoResponse -> return RunResult.FAILED to RunAttempt(at, AttemptOutcome.AGENT_NO_REPLY, "Agent cevap vermedi")
            AgentResult.UnknownPhone -> {
                runCatching { unknownPhone.onUnknownPhone(device) }
                return RunResult.FAILED to RunAttempt(at, AttemptOutcome.AGENT_ERROR, "PC bu telefonu tanımıyor")
            }
            AgentResult.BadSignature -> return RunResult.FAILED to RunAttempt(at, AttemptOutcome.AGENT_ERROR, "İmza doğrulanmadı")
            is AgentResult.Error -> return RunResult.FAILED to RunAttempt(at, AttemptOutcome.AGENT_ERROR, "Agent hatası: ${result.code}")
        }
        // Kilitle ve Yeniden'de ACK yeter; kapanan aksiyonlarda PC'nin düştüğü görülür.
        if (action == PowerAction.LOCK || action == PowerAction.RESTART) {
            return RunResult.SUCCESS to RunAttempt(at, AttemptOutcome.OK, "Komut uygulandı")
        }
        val start = clock.nowMs()
        // Windows'un kapanması sürer: en az 90 sn, ama sıradaki denemeye taşmadan (aralık − 5 sn).
        val interval = retry.current().intervalMs
        val limit = minOf(maxOf(window(), OFF_VERIFY_MIN_MS), maxOf(interval - OFF_VERIFY_MARGIN_MS, VERIFY_FLOOR_MS))
        var negatives = 0
        while (clock.nowMs() - start < limit) {
            val roundStart = clock.nowMs()
            val info = engine.round(device.id)
            if (info != null) negatives = if (info.verdict is Verdict.Positive) 0 else negatives + 1
            if (negatives >= OFF_ROUNDS) {
                engine.settleOff(device.id, action)
                return RunResult.SUCCESS to RunAttempt(at, AttemptOutcome.OK, "PC kapandı")
            }
            delay((StatusEngine.TRANSITION_INTERVAL_MS - (clock.nowMs() - roundStart)).coerceAtLeast(0))
        }
        return RunResult.FAILED to RunAttempt(at, AttemptOutcome.NO_REPLY, "Komut gitti · PC hâlâ açık")
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
        /** Kapanma Windows'ta sürer; en az 90 sn beklenir (PowerCoordinator ile aynı), aralık izin verdikçe. */
        const val OFF_VERIFY_MIN_MS = 90_000L
        const val OFF_VERIFY_MARGIN_MS = 5_000L
        const val VERIFY_FLOOR_MS = 30_000L
        const val OFF_ROUNDS = 2
        const val RESUME_DELAY_MS = 5_000L
        const val STALE_FAILURE_MS = 60 * 60_000L
        private const val NOT_PAIRED = "Agent eşli değil"
        private const val TAG = "ScheduleRunner"
    }
}
