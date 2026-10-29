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
import com.kdgames.wake_on_lan.net.agent.AgentCommand
import com.kdgames.wake_on_lan.net.agent.AgentResult
import com.kdgames.wake_on_lan.net.agent.AgentSession
import com.kdgames.wake_on_lan.net.agent.PhoneKeys
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

/**
 * Telefonun agent'a görünen kimliği: ad ("Galaxy S24") + kalıcı anahtar (K25). Anahtar `ANDROID_ID`'den
 * türetilir; uygulama silinip kurulunca aynı kalır, böylece agent telefonu `reset` gerekmeden yeniden tanır.
 */
@Singleton
class PhoneIdentity @Inject constructor(@ApplicationContext private val context: Context) {
    val name: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    val keys: PhoneKeys by lazy { PhoneKeys.derive(seed()) }

    @android.annotation.SuppressLint("HardwareIds")
    private fun seed(): String {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        // ANDROID_ID yoksa (pratikte olmaz) uygulama verisinde kalıcı rastgele tohum: yeniden kurulumda değişir.
        val prefs = context.getSharedPreferences("wol_identity", Context.MODE_PRIVATE)
        return prefs.getString("seed", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("seed", it).apply() }
    }
}

/** Agent çipinin ek durumu (K6). */
enum class AgentIssue { PAIRED_ELSEWHERE }

/** Cihaz eklenince/düzenlenince hemen discovery + eşleşme (spec 10.5); testte kaydedici. */
interface AgentLinkTrigger {
    fun linkNow()

    /** K25: silinen cihazın agent eşleşmesini kaldırır (PC kapalıysa sonra); anahtarı bu siler. */
    fun release(device: Device)

    /** Agent sorunları (K6 "Başka telefona eşli"), cihaz id'sine göre. */
    val issues: StateFlow<Map<Long, AgentIssue>>
}

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
    private val sessions: com.kdgames.wake_on_lan.domain.status.AgentSessions,
    private val monitor: NetworkMonitor,
    private val phone: PhoneIdentity,
    private val events: DomainEvents,
    private val foreground: AppForeground,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) : RoundObserver, UnknownPhoneHandler, AgentLinkTrigger {

    /** Debug ekranı için kalıcı telefon kimliği (K25). */
    val phoneKeys: PhoneKeys get() = phone.keys

    private val mutex = Mutex()
    private var lastRefresh = Long.MIN_VALUE / 2
    private var job: Job? = null
    private val _issues = MutableStateFlow<Map<Long, AgentIssue>>(emptyMap())
    override val issues: StateFlow<Map<Long, AgentIssue>> = _issues.asStateFlow()

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

    override fun linkNow() {
        scope.launch { refresh(force = true) }
    }

    override fun release(device: Device) {
        scope.launch {
            mutex.withLock { runCatching { releaseNow(device) }.onFailure { warn(TAG, "UNPAIR başarısız", it) } }
        }
    }

    /** PC açıksa hemen UNPAIR; cevap yoksa bekleyenlere yazılır ve agent bir sonraki DISCOVER'da görülünce gönderilir. */
    private suspend fun releaseNow(device: Device) {
        val alias = device.agentKeyAlias ?: return
        val agentId = device.agentId
        val key = vault.load(alias)
        if (agentId == null || key == null) return dropKey(alias)
        if (unpair(device.ip, agentId, key)) {
            dropKey(alias)
        } else {
            settings.addPendingUnpair(pendingEntry(agentId, alias))
        }
    }

    /** true: agent artık bu telefona eşli değil (UNPAIR kabul edildi ya da zaten tanımıyor). false: cevap yok. */
    private suspend fun unpair(host: String, agentId: String, key: ByteArray): Boolean = try {
        when (client.command(host, AgentSession(agentId, phone.keys.phoneId, key), AgentCommand.UNPAIR, force = false)) {
            AgentResult.NoResponse -> false
            else -> true // Ok, UNKNOWN_PHONE, BAD_SIG (artık başka anahtar): bizim eşleşmemiz yok
        }
    } catch (_: NoWifiException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun dropKey(alias: String) {
        sessions.forget(alias)
        vault.delete(alias)
    }

    /** K25: PC kapalıyken silinmiş cihazların agent'ı göründüyse sessizce UNPAIR. */
    private suspend fun processPending(found: List<DiscoveredAgent>, devices: List<Device>) {
        for (entry in settings.pendingUnpairs()) {
            val (agentId, alias) = entry.split('|', limit = 2).takeIf { it.size == 2 } ?: run { settings.removePendingUnpair(entry); continue }
            val agent = found.firstOrNull { it.reply.agentId == agentId } ?: continue
            val done = when {
                // Kullanıcı PC'yi yeniden ekledi: eşleşme kalmalı (yeni kayıt kendi anahtarını kullanır).
                devices.any { it.agentId == agentId || (it.agentId == null && matches(it, agent)) } -> true
                // Agent artık bize eşli değil (reset, başka telefon): yapacak bir şey yok.
                !agent.reply.paired || !agent.reply.pairedToYou -> true
                else -> vault.load(alias)?.let { unpair(agent.host, agentId, it) } ?: true
            }
            if (done) {
                settings.removePendingUnpair(entry)
                if (devices.none { it.agentKeyAlias == alias }) dropKey(alias)
            }
        }
    }

    private fun pendingEntry(agentId: String, alias: String) = "$agentId|$alias"

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
            client.discover(broadcast, phone.keys.phoneId)
        } catch (_: NoWifiException) {
            return@withLock
        } catch (e: IOException) {
            warn(TAG, "DISCOVER başarısız", e)
            return@withLock
        }
        val devices = repo.getDevices()
        runCatching { processPending(found, devices) }.onFailure { warn(TAG, "Bekleyen UNPAIR", it) }
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
            // Agent boşta (reset) ya da bu telefona eşli ama anahtar yok (uygulama yeniden kuruldu, K25) → eşleş;
            // ikinci durumda agent kalıcı anahtarımızı tanıyıp aynı K ile PAIR_OK verir.
            !reply.paired || reply.pairedToYou -> d = pair(forget(d), agent)
            // Agent başka bir telefona eşli (K6).
            else -> {
                if (d.agentId == null || !hasKey) {
                    _issues.update { it + (d.id to AgentIssue.PAIRED_ELSEWHERE) }
                }
            }
        }
        if (d != original) repo.updateDevice(d)
    }

    private suspend fun pair(device: Device, agent: DiscoveredAgent): Device =
        when (val r = client.pair(agent.host, phone.keys, phone.name)) {
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
        sessions.forget(device.agentKeyAlias)
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
