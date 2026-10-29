package com.kdgames.wake_on_lan.domain.power

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.domain.DomainEvent
import com.kdgames.wake_on_lan.domain.DomainEvents
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.ShutdownMode
import com.kdgames.wake_on_lan.domain.model.Verdict
import com.kdgames.wake_on_lan.domain.status.AgentSessions
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import com.kdgames.wake_on_lan.net.AppScope
import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.agent.AgentClient
import com.kdgames.wake_on_lan.net.agent.AgentCommand
import com.kdgames.wake_on_lan.net.agent.AgentErrorCode
import com.kdgames.wake_on_lan.net.agent.AgentResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** CHALLENGE → CMD → ACK (≤ 2 sn). */
fun interface AgentCommander {
    suspend fun command(device: Device, cmd: AgentCommand, force: Boolean): AgentResult<Unit>
}

class ClientAgentCommander @Inject constructor(private val client: AgentClient, private val sessions: AgentSessions) : AgentCommander {
    override suspend fun command(device: Device, cmd: AgentCommand, force: Boolean): AgentResult<Unit> {
        val session = sessions.of(device) ?: return AgentResult.Error(AgentErrorCode.UNKNOWN_PHONE)
        return client.command(device.ip, session, cmd, force)
    }
}

/** K6: PC bu telefonu tanımıyor → anahtarı sil, yeniden keşfet/eşleş (AgentLinker). */
fun interface UnknownPhoneHandler {
    suspend fun onUnknownPhone(device: Device)
}

/**
 * Spec 9.3 + K12 + K23. Komut yalnız `On` cihaza gider. ACK sonrası:
 * LOCK → On kalır + "Kilitlendi"; SLEEP/HIBERNATE/SHUTDOWN → PowerTransition → 3 negatif turda Off (90 sn);
 * RESTART → offline → online ya da uptime düşüşü (180 sn).
 */
@Singleton
class PowerCoordinator @Inject constructor(
    private val engine: StatusEngine,
    private val commander: AgentCommander,
    private val unknownPhone: UnknownPhoneHandler,
    private val events: DomainEvents,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) {
    private val active = ConcurrentHashMap<Long, Job>()

    fun isBusy(deviceId: Long) = active.containsKey(deviceId)

    /** Gönderilebilir mi (K12 + spec 9.3 madde 3–4); null = evet, değilse sebep. */
    fun rejection(deviceId: Long, action: PowerAction): DomainEvent.CommandRejected.Reason? {
        val device = engine.device(deviceId) ?: return DomainEvent.CommandRejected.Reason.NOT_ON
        return when {
            engine.state(deviceId) !is DeviceState.On -> DomainEvent.CommandRejected.Reason.NOT_ON
            device.agentId == null || device.agentKeyAlias == null -> DomainEvent.CommandRejected.Reason.NO_AGENT
            action == PowerAction.HIBERNATE && !device.hibernateAvailable -> DomainEvent.CommandRejected.Reason.HIBERNATE_OFF
            active.containsKey(deviceId) -> DomainEvent.CommandRejected.Reason.BUSY_LOCAL
            else -> null
        }
    }

    fun execute(deviceId: Long, action: PowerAction): Boolean {
        require(action.requiresAgent) { "WAKE için WakeCoordinator" }
        rejection(deviceId, action)?.let {
            events.emit(DomainEvent.CommandRejected(deviceId, it))
            return false
        }
        val device = engine.device(deviceId)!!
        val job = scope.launch(start = CoroutineStart.LAZY) { run(device, action) }
        if (active.putIfAbsent(deviceId, job) != null) {
            job.cancel()
            return false
        }
        job.invokeOnCompletion { active.remove(deviceId, job) }
        job.start()
        return true
    }

    suspend fun join(deviceId: Long) {
        active[deviceId]?.join()
    }

    private suspend fun run(device: Device, action: PowerAction) {
        val id = device.id
        val graceful = device.shutdownMode == ShutdownMode.GRACEFUL && (action == PowerAction.SHUTDOWN || action == PowerAction.RESTART)
        val force = device.shutdownMode == ShutdownMode.FORCE && (action == PowerAction.SHUTDOWN || action == PowerAction.RESTART)
        val result = try {
            commander.command(device, action.toCommand(), force)
        } catch (_: NoWifiException) {
            events.emit(DomainEvent.NoWifi(id))
            return
        }
        when (result) {
            is AgentResult.Ok -> Unit
            AgentResult.UnknownPhone -> {
                events.emit(DomainEvent.AgentFailed(id, AgentErrorCode.UNKNOWN_PHONE))
                unknownPhone.onUnknownPhone(device)
                return
            }
            is AgentResult.Error -> return events.emit(DomainEvent.AgentFailed(id, result.code))
            AgentResult.BadSignature -> return events.emit(DomainEvent.AgentFailed(id, AgentErrorCode.BAD_SIG))
            AgentResult.NoResponse -> return events.emit(DomainEvent.AgentFailed(id, "NO_REPLY"))
        }
        if (action == PowerAction.LOCK) {
            events.emit(DomainEvent.Locked(id))
            return
        }

        // K23: Modern Beklemeli PC'de Uyku = Hazırda Beklet; gösterim ve lastAction buna göre.
        val effective = if (action == PowerAction.SLEEP && device.sleepHibernates) PowerAction.HIBERNATE else action
        val start = clock.nowMs()
        val timeout = if (action == PowerAction.RESTART) RESTART_TIMEOUT_MS else OFF_TIMEOUT_MS
        val uptimeBefore = (engine.state(id) as? DeviceState.On)?.uptimeSec
        var negatives = 0
        var wentOffline = false
        try {
            while (clock.nowMs() - start < timeout) {
                engine.setOverlay(id, DeviceState.PowerTransition(effective, ((clock.nowMs() - start) / 1000).toInt()))
                val roundStart = clock.nowMs()
                val info = engine.round(id)
                when (val v = info?.verdict) {
                    is Verdict.Negative -> negatives++
                    is Verdict.Positive -> {
                        negatives = 0
                        if (action == PowerAction.RESTART) {
                            val uptime = info.signals.s1?.uptimeSec
                            val restarted = wentOffline || (uptimeBefore != null && uptime != null && uptime < uptimeBefore)
                            if (restarted) {
                                events.emit(DomainEvent.PowerDone(id, action))
                                return
                            }
                        }
                    }
                    null -> Unit
                }
                if (negatives >= OFFLINE_ROUNDS) {
                    if (action == PowerAction.RESTART) {
                        wentOffline = true
                    } else {
                        engine.settleOff(id, lastAction = effective)
                        events.emit(DomainEvent.PowerDone(id, effective))
                        return
                    }
                }
                delay((StatusEngine.TRANSITION_INTERVAL_MS - (clock.nowMs() - roundStart)).coerceAtLeast(0))
            }
            events.emit(DomainEvent.PowerTimedOut(id, effective, graceful))
        } finally {
            engine.setOverlay(id, null)
        }
    }

    private fun PowerAction.toCommand() = when (this) {
        PowerAction.LOCK -> AgentCommand.LOCK
        PowerAction.SLEEP -> AgentCommand.SLEEP
        PowerAction.HIBERNATE -> AgentCommand.HIBERNATE
        PowerAction.SHUTDOWN -> AgentCommand.SHUTDOWN
        PowerAction.RESTART -> AgentCommand.RESTART
        PowerAction.WAKE -> error("WAKE agent komutu değil")
    }

    companion object {
        const val OFF_TIMEOUT_MS = 90_000L
        const val RESTART_TIMEOUT_MS = 180_000L
        /** Spec 9.1: geçiş durumlarında 3 negatif tur → kapalı. */
        const val OFFLINE_ROUNDS = 3
    }
}
