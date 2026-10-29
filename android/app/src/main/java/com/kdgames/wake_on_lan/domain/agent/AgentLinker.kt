package com.kdgames.wake_on_lan.domain.agent

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.kdgames.wake_on_lan.data.crypto.KeyVault
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.domain.DomainEvent
import com.kdgames.wake_on_lan.domain.DomainEvents
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.domain.model.Verdict
import com.kdgames.wake_on_lan.domain.power.UnknownPhoneHandler
import com.kdgames.wake_on_lan.domain.status.AppForeground
import com.kdgames.wake_on_lan.domain.status.RoundObserver
import com.kdgames.wake_on_lan.domain.warn
import com.kdgames.wake_on_lan.net.AppScope
import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.MacAddress
import com.kdgames.wake_on_lan.net.NetworkMonitor
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.agent.AgentClient
import com.kdgames.wake_on_lan.net.agent.AgentErrorCode
import com.kdgames.wake_on_lan.net.agent.DiscoveredAgent
import com.kdgames.wake_on_lan.net.agent.NicSelector
import com.kdgames.wake_on_lan.net.agent.PairResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Eşleşmede agent'a gönderilen telefon adı ("Galaxy S24"). */
class PhoneIdentity @Inject constructor(@ApplicationContext private val context: Context) {
    val name: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
}

/** Agent çipinin ek durumu (K6). */
enum class AgentIssue { PAIRED_ELSEWHERE }

/**
 * Spec 7.5 + 10.5 + K6 + K16:
 * - ön planda açılışta ve her 60 sn DISCOVER; agentId eşleşen cihazın IP'si sessizce güncellenir;
 * - agentId'siz cihazda (IP ya da MAC eşleşirse) otomatik TOFU eşleşme; agent başka telefona eşliyse "Başka telefona eşli";
 * - `UNKNOWN_PHONE` (PC'de reset) → anahtar silinir, yeniden keşfedilir;
 * - S2 farklı MAC (K8) → discovery; cihaz On'a geçince (agentId yoksa) discovery;
 * - MAC boşsa agent NIC'lerinden (K16) ya da NetBIOS'tan doldurulur; kullanıcının MAC'i değiştirilmez.
 */
@Singleton
class AgentLinker @Inject constructor(
    private val repo: DeviceRepository,
    private val client: AgentClient,
    private val vault: KeyVault,
    private val settings: SettingsStore,
    private val monitor: NetworkMonitor,
    private val phone: PhoneIdentity,
    private val events: DomainEvents,
    private val foreground: AppForeground,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) : RoundObserver, UnknownPhoneHandler {

    private val mutex = Mutex()
    private var lastRefresh = Long.MIN_VALUE / 2
    private var job: Job? = null
    private val _issues = MutableStateFlow<Map<Long, AgentIssue>>(emptyMap())
    val issues: StateFlow<Map<Long, AgentIssue>> = _issues.asStateFlow()

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            foreground.isForeground.collectLatest { fg ->
                while (fg && isActive) {
                    refresh(force = true)
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    /** Arka planda discovery tetikle (kısa aralıkla gelen istekler birleştirilir). */
    fun requestRefresh() {
        scope.launch { refresh(force = false) }
    }

    suspend fun refresh(force: Boolean) = mutex.withLock {
        val now = clock.nowMs()
        if (!force && now - lastRefresh < MIN_GAP_MS) return@withLock
        lastRefresh = now
        val wifi = monitor.state.value
        val broadcast = wifi.broadcast ?: return@withLock
        val found = try {
            client.discover(broadcast)
        } catch (_: NoWifiException) {
            return@withLock
        } catch (e: IOException) {
            warn(TAG, "DISCOVER başarısız", e)
            return@withLock
        }
        val devices = repo.getDevices()
        for (agent in found) {
            val device = devices.firstOrNull { it.agentId == agent.reply.agentId }
                ?: devices.firstOrNull { it.agentId == null && matches(it, agent) }
                ?: continue
            runCatching { link(device, agent, wifi.phoneIp, wifi.prefixLength) }
                .onFailure { warn(TAG, "Agent bağlanamadı: ${device.name}", it) }
        }
    }

    private fun matches(device: Device, agent: DiscoveredAgent): Boolean =
        device.ip == agent.host || (device.mac != null && agent.reply.nics.any { it.mac.equals(device.mac, ignoreCase = true) })

    private suspend fun link(original: Device, agent: DiscoveredAgent, phoneIp: String?, prefix: Int?) {
        val reply = agent.reply
        var d = original
        if (d.ip != agent.host) {
            events.emit(DomainEvent.AgentInfo(d.id, "IP güncellendi: ${d.ip} → ${agent.host}"))
            d = d.copy(ip = agent.host)
        }
        d = d.copy(hostname = reply.hostname, hibernateAvailable = reply.hibernate, sleepHibernates = reply.sleepHibernates)
        if (d.mac == null) {
            NicSelector.select(reply.nics, phoneIp, prefix)?.mac?.let(MacAddress::normalize)?.let { mac ->
                if (!repo.macInUse(mac, d.id)) {
                    d = d.copy(mac = mac)
                    events.emit(DomainEvent.AgentInfo(d.id, "MAC bulundu: $mac"))
                }
            }
        }

        val hasKey = d.agentId != null && d.agentKeyAlias?.let(vault::load) != null
        when {
            d.agentId != null && hasKey && reply.paired -> clearIssue(d.id)
            // Agent eşleşmemiş görünüyor (reset) ya da anahtarımız yok ama agent boşta → yeniden eşleş.
            !reply.paired -> d = pair(forget(d), agent)
            // Agent eşli ama bizde anahtar yok → başka telefon (K6).
            else -> {
                if (d.agentId == null || !hasKey) {
                    _issues.update { it + (d.id to AgentIssue.PAIRED_ELSEWHERE) }
                }
            }
        }
        if (d != original) repo.updateDevice(d)
    }

    private suspend fun pair(device: Device, agent: DiscoveredAgent): Device =
        when (val r = client.pair(agent.host, settings.ensurePhoneId(), phone.name)) {
            is PairResult.Paired -> {
                val alias = vault.newAlias()
                vault.store(alias, r.key)
                clearIssue(device.id)
                events.emit(DomainEvent.AgentInfo(device.id, "Agent eşleşti"))
                device.copy(agentId = r.agentId, agentKeyAlias = alias)
            }
            PairResult.Denied -> {
                _issues.update { it + (device.id to AgentIssue.PAIRED_ELSEWHERE) }
                device
            }
            PairResult.ProofInvalid -> {
                events.emit(DomainEvent.AgentInfo(device.id, "Eşleşme doğrulanamadı"))
                device
            }
            PairResult.NoResponse, is PairResult.Failed -> device
        }

    private fun forget(device: Device): Device {
        device.agentKeyAlias?.let(vault::delete)
        return device.copy(agentId = null, agentKeyAlias = null)
    }

    private fun clearIssue(id: Long) = _issues.update { it - id }

    /** K6: `UNKNOWN_PHONE` → anahtar silinir, discovery ile (agent boştaysa) yeniden eşleşilir. */
    override suspend fun onUnknownPhone(device: Device) {
        val current = repo.getDevice(device.id) ?: return
        if (current.agentId == null) return
        repo.updateDevice(forget(current))
        events.emit(DomainEvent.AgentInfo(device.id, "PC bu telefonu tanımıyor · anahtar silindi, yeniden eşleşiliyor"))
        refresh(force = true)
    }

    override suspend fun onRound(device: Device, signals: RoundSignals, verdict: Verdict, previous: DeviceState?, now: DeviceState) {
        if (signals.s1?.error == AgentErrorCode.UNKNOWN_PHONE) {
            scope.launch { onUnknownPhone(device) }
            return
        }
        if (verdict is Verdict.Negative && verdict.macMismatch) {
            requestRefresh()
            return
        }
        val s2Mac = signals.s2?.mac?.let(MacAddress::normalize)
        if (device.mac == null && s2Mac != null) {
            scope.launch {
                mutex.withLock {
                    val current = repo.getDevice(device.id) ?: return@withLock
                    if (current.mac == null && !repo.macInUse(s2Mac, current.id)) {
                        repo.updateDevice(current.copy(mac = s2Mac, hostname = current.hostname ?: signals.s2.hostname))
                        events.emit(DomainEvent.AgentInfo(device.id, "MAC bulundu (NetBIOS): $s2Mac"))
                    }
                }
            }
        }
        if (now is DeviceState.On && previous !is DeviceState.On && device.agentId == null) requestRefresh()
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 60_000L
        const val MIN_GAP_MS = 5_000L
        private const val TAG = "AgentLinker"
    }
}
