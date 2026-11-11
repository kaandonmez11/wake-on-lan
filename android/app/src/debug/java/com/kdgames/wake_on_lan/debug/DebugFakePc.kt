package com.kdgames.wake_on_lan.debug

import android.content.Context
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.domain.FakePc
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.net.NetworkMonitor
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.agent.AgentCommand
import com.kdgames.wake_on_lan.net.agent.AgentErrorCode
import com.kdgames.wake_on_lan.net.agent.AgentResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Magic packet gelince sahte PC ne yapsın. */
enum class FakeWake(val label: String) {
    NORMAL("Paketle açılır"),
    NEVER("Hiç açılmaz"),
    AFTER_N("N. denemede açılır"),
}

/** Agent komutuna sahte PC ne desin. */
enum class FakeAgent(val label: String) {
    OK("Uygular"),
    SILENT("Cevap vermez"),
    BUSY("BUSY hatası"),
    STUCK("Kabul eder, kapanmaz"),
}

data class FakePcState(
    val on: Boolean = false,
    val wake: FakeWake = FakeWake.NORMAL,
    /** AFTER_N: kaçıncı paket turunda açılsın. */
    val wakeAfter: Int = 3,
    val agent: FakeAgent = FakeAgent.OK,
    /** Gelen paket turu sayısı (N. denemede açılma için). */
    val packets: Int = 0,
    /** Bu andan sonra açık / kapalı (geçiş süresi). */
    val onAt: Long? = null,
    val offAt: Long? = null,
    val bootedAt: Long = 0,
)

/**
 * Debug "Sahte PC" (S09). Durum SharedPreferences'ta: alarmla arka planda açılan süreç de aynı PC'yi görür.
 * Gerçek Wi-Fi yoksa tur sayılmaz ve paket gitmez (Wi-Fi testleri gerçek kalır). Açılma 5 sn, kapanma 5 sn,
 * yeniden başlatma 15 sn sürer.
 */
@Singleton
class DebugFakePc @Inject constructor(
    @ApplicationContext context: Context,
    private val monitor: NetworkMonitor,
) : FakePc {
    private val prefs = context.getSharedPreferences("fake_pc", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<FakePcState> = _state.asStateFlow()

    override fun handles(device: Device) = handlesMac(device.mac.orEmpty())
    override fun handlesMac(mac: String) = mac.equals(MAC, ignoreCase = true)

    /** Zamanı gelen geçişleri uygular; güncel durumu döner. */
    @Synchronized
    fun current(now: Long = System.currentTimeMillis()): FakePcState {
        var s = load()
        s.onAt?.let { if (now >= it) s = s.copy(on = true, onAt = null, bootedAt = it) }
        s.offAt?.let { if (now >= it) s = s.copy(on = false, offAt = null) }
        save(s)
        return s
    }

    @Synchronized
    fun update(transform: (FakePcState) -> FakePcState) = save(transform(current()))

    fun powerNow(on: Boolean) = update {
        it.copy(on = on, onAt = null, offAt = null, bootedAt = if (on) System.currentTimeMillis() else it.bootedAt)
    }

    override suspend fun probe(device: Device): RoundSignals {
        requireWifi()
        delay(25)
        val s = current()
        return if (s.on) {
            val uptime = (System.currentTimeMillis() - s.bootedAt).coerceAtLeast(0) / 1000
            RoundSignals(RoundSignals.AgentSignal(true, 4, uptime, "on"), null, RoundSignals.TcpSignal(true, true, 3), RoundSignals.IcmpSignal(true, 2))
        } else {
            RoundSignals(RoundSignals.AgentSignal(false, 600), null, RoundSignals.TcpSignal(false, false, 400), RoundSignals.IcmpSignal(false, 1000))
        }
    }

    override suspend fun magic(mac: String, onFirstSent: () -> Unit) {
        requireWifi()
        val now = System.currentTimeMillis()
        update { s ->
            val count = s.packets + 1
            val wakes = !s.on && s.onAt == null && when (s.wake) {
                FakeWake.NORMAL -> true
                FakeWake.NEVER -> false
                FakeWake.AFTER_N -> count >= s.wakeAfter
            }
            s.copy(packets = count, onAt = if (wakes) now + WAKE_MS else s.onAt)
        }
        onFirstSent()
    }

    override suspend fun command(device: Device, cmd: AgentCommand, force: Boolean): AgentResult<Unit> {
        requireWifi()
        val s = current()
        if (!s.on) {
            delay(1_000)
            return AgentResult.NoResponse
        }
        when (s.agent) {
            FakeAgent.SILENT -> {
                delay(1_500)
                return AgentResult.NoResponse
            }
            FakeAgent.BUSY -> return AgentResult.Error(AgentErrorCode.BUSY)
            FakeAgent.STUCK -> return AgentResult.Ok(Unit, 5)
            FakeAgent.OK -> Unit
        }
        val now = System.currentTimeMillis()
        when (cmd) {
            AgentCommand.SLEEP, AgentCommand.HIBERNATE, AgentCommand.SHUTDOWN -> update { it.copy(offAt = now + OFF_MS) }
            AgentCommand.RESTART -> update { it.copy(offAt = now + OFF_MS, onAt = now + RESTART_MS) }
            else -> Unit
        }
        return AgentResult.Ok(Unit, 5)
    }

    private fun requireWifi() {
        if (!monitor.state.value.connected) throw NoWifiException()
    }

    private fun load() = FakePcState(
        on = prefs.getBoolean("on", false),
        wake = runCatching { FakeWake.valueOf(prefs.getString("wake", null)!!) }.getOrDefault(FakeWake.NORMAL),
        wakeAfter = prefs.getInt("wakeAfter", 3),
        agent = runCatching { FakeAgent.valueOf(prefs.getString("agent", null)!!) }.getOrDefault(FakeAgent.OK),
        packets = prefs.getInt("packets", 0),
        onAt = prefs.getLong("onAt", 0).takeIf { it > 0 },
        offAt = prefs.getLong("offAt", 0).takeIf { it > 0 },
        bootedAt = prefs.getLong("bootedAt", 0),
    )

    private fun save(s: FakePcState) {
        prefs.edit()
            .putBoolean("on", s.on)
            .putString("wake", s.wake.name)
            .putInt("wakeAfter", s.wakeAfter)
            .putString("agent", s.agent.name)
            .putInt("packets", s.packets)
            .putLong("onAt", s.onAt ?: 0)
            .putLong("offAt", s.offAt ?: 0)
            .putLong("bootedAt", s.bootedAt)
            .commit()
        _state.value = s
    }

    companion object {
        /** Yerel yönetimli (02:…) sahte MAC; gerçek bir kartla çakışmaz. */
        const val MAC = "02:FA:4E:00:00:01"
        /** TEST-NET-1 (RFC 5737): gerçek ağda yok. */
        const val IP = "192.0.2.77"
        const val NAME = "Sahte PC"
        const val AGENT_ID = "fake-agent"
        /** Gerçek anahtar yok; komutlar sahte PC'ye yönlendiği için kullanılmaz, yalnız "eşli" sayılması için. */
        const val KEY_ALIAS = "fake-key"
        private const val WAKE_MS = 5_000L
        private const val OFF_MS = 5_000L
        private const val RESTART_MS = 15_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugFakePcModule {
    @Binds abstract fun fakePc(impl: DebugFakePc): FakePc
}
