package com.kdgames.wake_on_lan.domain.wake

import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.domain.DomainEvent
import com.kdgames.wake_on_lan.domain.DomainEvents
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.Verdict
import com.kdgames.wake_on_lan.domain.model.WakeStep
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import com.kdgames.wake_on_lan.net.AppScope
import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.NetworkMonitor
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.WolSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Magic packet gönderimi (t=0/2/5). [onFirstSent] ilk tur gidince. @throws NoWifiException */
fun interface MagicSender {
    suspend fun send(mac: String, onFirstSent: () -> Unit)
}

class WifiMagicSender @Inject constructor(private val sender: WolSender, private val monitor: NetworkMonitor) : MagicSender {
    override suspend fun send(mac: String, onFirstSent: () -> Unit) {
        var first = true
        sender.send(mac, monitor.state.value.broadcast) {
            if (first) {
                first = false
                onFirstSent()
            }
        }
    }
}

/** `WakeAttempt` yazımı (K15 retention repository'de). */
fun interface AttemptRecorder {
    suspend fun record(deviceId: Long, startedAt: Long, success: Boolean, durationSec: Int?, source: AttemptSource)
}

class RepositoryAttemptRecorder @Inject constructor(private val repo: DeviceRepository) : AttemptRecorder {
    override suspend fun record(deviceId: Long, startedAt: Long, success: Boolean, durationSec: Int?, source: AttemptSource) {
        repo.recordWakeAttempt(deviceId, startedAt, success, durationSec, source)
    }
}

/**
 * Spec 9.2: cihaz başına tek uyandırma akışı. PACKET → REPLY (1 sn turlar, 60 sn) → PORT (≤ 30 sn).
 * Elle uyandırmada otomatik retry yok.
 */
@Singleton
class WakeCoordinator @Inject constructor(
    private val engine: StatusEngine,
    private val sender: MagicSender,
    private val recorder: AttemptRecorder,
    private val events: DomainEvents,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) {
    private val active = ConcurrentHashMap<Long, Job>()

    fun isWaking(deviceId: Long) = active.containsKey(deviceId)

    /** Akışı başlatır; MAC yoksa ya da zaten sürüyorsa false (ikinci tetikleme yok sayılır). */
    fun wake(deviceId: Long, source: AttemptSource = AttemptSource.MANUAL): Boolean {
        val device = engine.device(deviceId) ?: return false
        val mac = device.mac
        if (mac == null) {
            events.emit(DomainEvent.NoMac(deviceId))
            return false
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { run(deviceId, mac, source) }
        if (active.putIfAbsent(deviceId, job) != null) {
            job.cancel()
            return false
        }
        job.invokeOnCompletion { active.remove(deviceId, job) }
        job.start()
        return true
    }

    /** Bitene kadar bekle (testler ve VerifyWorker için). */
    suspend fun join(deviceId: Long) {
        active[deviceId]?.join()
    }

    private suspend fun run(deviceId: Long, mac: String, source: AttemptSource) {
        val start = clock.nowMs()
        fun elapsedSec() = ((clock.nowMs() - start) / 1000).toInt()
        engine.setOverlay(deviceId, DeviceState.Waking(WakeStep.PACKET, 0))
        try {
            coroutineScope {
                val firstSent = CompletableDeferred<Unit>()
                launch { sender.send(mac) { firstSent.complete(Unit) } }
                firstSent.await()

                // REPLY: ilk pozitif sinyal = başarı.
                var durationSec: Int? = null
                while (clock.nowMs() - start < REPLY_TIMEOUT_MS) {
                    engine.setOverlay(deviceId, DeviceState.Waking(WakeStep.REPLY, elapsedSec()))
                    val roundStart = clock.nowMs()
                    val info = engine.round(deviceId)
                    if (info?.verdict is Verdict.Positive) {
                        durationSec = ((info.at - start) / 1000).toInt()
                        break
                    }
                    pace(roundStart)
                }

                if (durationSec == null) {
                    recorder.record(deviceId, start, false, null, source)
                    engine.markTried(deviceId)
                    engine.settleOff(deviceId, lastAction = null)
                    events.emit(DomainEvent.WakeFailed(deviceId))
                    return@coroutineScope
                }
                recorder.record(deviceId, start, true, durationSec, source)
                engine.markTried(deviceId)
                events.emit(DomainEvent.WakeSucceeded(deviceId, durationSec))

                // PORT: probe portlarından biri kabul edene ya da S1 gelene dek; dolmazsa adım atlanır.
                val portStart = clock.nowMs()
                while (clock.nowMs() - portStart < PORT_TIMEOUT_MS) {
                    engine.setOverlay(deviceId, DeviceState.Waking(WakeStep.PORT, elapsedSec()))
                    val roundStart = clock.nowMs()
                    val s = engine.round(deviceId)?.signals
                    if (s?.s3?.accepted == true || s?.s1?.ok == true) break
                    pace(roundStart)
                }
            }
        } catch (_: NoWifiException) {
            events.emit(DomainEvent.NoWifi(deviceId))
        } finally {
            engine.setOverlay(deviceId, null)
        }
    }

    private suspend fun pace(roundStart: Long) =
        delay((StatusEngine.TRANSITION_INTERVAL_MS - (clock.nowMs() - roundStart)).coerceAtLeast(0))

    companion object {
        const val REPLY_TIMEOUT_MS = 60_000L
        const val PORT_TIMEOUT_MS = 30_000L
    }
}
