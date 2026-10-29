package com.kdgames.wake_on_lan.domain

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.DeviceStateCache
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.domain.status.AppForeground
import com.kdgames.wake_on_lan.domain.status.SignalProber
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import com.kdgames.wake_on_lan.domain.status.StatusStore
import com.kdgames.wake_on_lan.domain.wake.AttemptRecorder
import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.NoWifiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Sinyal kısaltmaları. */
object Sig {
    fun none() = RoundSignals(null, null, RoundSignals.TcpSignal(false, false, 400), RoundSignals.IcmpSignal(false, 1000))
    fun tcp(ms: Long = 3, accepted: Boolean = false) = RoundSignals(null, null, RoundSignals.TcpSignal(true, accepted, ms), RoundSignals.IcmpSignal(false, 1000))
    fun agent(ms: Long = 12, uptime: Long = 3600) = RoundSignals(RoundSignals.AgentSignal(true, ms, uptime, "on"), null, null, null)
    fun agentSilentTcp() = RoundSignals(RoundSignals.AgentSignal(false, 600), null, RoundSignals.TcpSignal(true, true, 4), null)
    fun netBios(mac: String?, ms: Long = 7) = RoundSignals(null, RoundSignals.NetBiosSignal(mac, "KAAN-PC", ms), RoundSignals.TcpSignal(true, false, 3), null)
}

class FakeStore(devices: List<Device>, private val cache: List<DeviceStateCache> = emptyList()) : StatusStore {
    val flow = MutableStateFlow(devices)
    val written = mutableListOf<DeviceStateCache>()
    var tried = emptySet<Long>()

    override fun devices() = flow
    override suspend fun tried(deviceId: Long) = deviceId in tried
    override suspend fun cached() = cache
    override suspend fun cache(entry: DeviceStateCache) {
        written += entry
    }
}

class FakeForeground(initial: Boolean) : AppForeground {
    override val isForeground = MutableStateFlow(initial)
}

class RecordingAttempts : AttemptRecorder {
    data class Attempt(val deviceId: Long, val startedAt: Long, val success: Boolean, val durationSec: Int?, val source: AttemptSource)

    val attempts = mutableListOf<Attempt>()
    override suspend fun record(deviceId: Long, startedAt: Long, success: Boolean, durationSec: Int?, source: AttemptSource) {
        attempts += Attempt(deviceId, startedAt, success, durationSec, source)
    }
}

fun pc(
    id: Long = 1,
    mac: String? = "D8:BB:C1:0A:2F:91",
    agent: Boolean = true,
    hibernate: Boolean = true,
    sleepHibernates: Boolean = false,
    mode: com.kdgames.wake_on_lan.domain.model.ShutdownMode = com.kdgames.wake_on_lan.domain.model.ShutdownMode.FORCE,
) = Device(
    id = id, name = "Ofis PC", ip = "192.168.1.26", mac = mac, iconKey = "pc", colorIndex = 0,
    agentId = if (agent) "agent-$id" else null, agentKeyAlias = if (agent) "alias-$id" else null,
    hibernateAvailable = hibernate, sleepHibernates = sleepHibernates, shutdownMode = mode,
)

/** Sanal zamanlı StatusEngine; [signals] her turda (cihaz, sanal ms) ile çağrılır. */
class EngineHarness(
    val test: TestScope,
    devices: List<Device> = listOf(pc()),
    cache: List<DeviceStateCache> = emptyList(),
    foreground: Boolean = false,
    var signals: (Device, Long) -> RoundSignals = { _, _ -> Sig.none() },
) {
    val clock = Clock { test.testScheduler.currentTime }
    val store = FakeStore(devices, cache)
    val fg = FakeForeground(foreground)
    val events = DomainEvents()
    val seen = mutableListOf<DomainEvent>()
    var probes = 0
    var wifi = true
    val engine = StatusEngine(
        store,
        SignalProber { d ->
            if (!wifi) throw NoWifiException()
            probes++
            signals(d, test.testScheduler.currentTime)
        },
        { _, _, _, _, _ -> },
        fg,
        clock,
        test.backgroundScope,
    )

    init {
        test.backgroundScope.launch { events.events.collect { seen += it } }
        engine.start()
        test.runCurrent()
    }

    fun state(id: Long = 1) = engine.states.value[id]
}
