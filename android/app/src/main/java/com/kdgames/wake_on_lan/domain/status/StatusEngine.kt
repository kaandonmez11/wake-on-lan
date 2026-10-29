package com.kdgames.wake_on_lan.domain.status

import com.kdgames.wake_on_lan.domain.warn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.DeviceStateCache
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.domain.model.CachedStateKind
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.domain.model.Verdict
import com.kdgames.wake_on_lan.net.AppScope
import com.kdgames.wake_on_lan.net.Clock
import com.kdgames.wake_on_lan.net.NoWifiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Uygulama ön planda mı (tur yalnız ön planda, spec 9.1). */
interface AppForeground {
    val isForeground: StateFlow<Boolean>
}

@Singleton
class ProcessForeground @Inject constructor(@AppScope scope: CoroutineScope) : AppForeground {
    override val isForeground: StateFlow<Boolean> = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
}

/** StatusEngine'in kalıcı veriye erişimi (testte bellek içi). */
interface StatusStore {
    fun devices(): Flow<List<Device>>
    suspend fun tried(deviceId: Long): Boolean
    suspend fun cached(): List<DeviceStateCache>
    suspend fun cache(entry: DeviceStateCache)
}

class RepositoryStatusStore @Inject constructor(private val repo: DeviceRepository) : StatusStore {
    override fun devices() = repo.observeDevices()
    override suspend fun tried(deviceId: Long) = !repo.getHistory(deviceId).isNew
    override suspend fun cached() = repo.cachedStates()
    override suspend fun cache(entry: DeviceStateCache) = repo.cacheState(entry)
}

/** Tur sonrası kanca (AgentLinker: K6, K8, MAC doldurma, On'a geçince eşleşme). */
fun interface RoundObserver {
    suspend fun onRound(device: Device, signals: RoundSignals, verdict: Verdict, previous: DeviceState?, now: DeviceState)
}

/**
 * Spec 9.1: tek doğruluk kaynağı. Ön plandayken her 3 sn tüm cihazlara tur atar (S1–S4 paralel),
 * kuralları uygular ve `StateFlow<Map<Long, DeviceState>>` yayınlar. Geçiş durumlarını (Waking,
 * PowerTransition) koordinatörler katman olarak koyar ve kendi 1 sn turlarını [round] ile sürer.
 */
@Singleton
class StatusEngine @Inject constructor(
    private val store: StatusStore,
    private val prober: SignalProber,
    private val observer: RoundObserver,
    private val foreground: AppForeground,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) {
    /** Debug için son turun ayrıntısı. */
    data class RoundInfo(val number: Long, val at: Long, val signals: RoundSignals, val verdict: Verdict, val negatives: Int)

    private val devices = MutableStateFlow<Map<Long, Device>?>(null)
    private val trackers = MutableStateFlow<Map<Long, Tracker>>(emptyMap())
    private val overlays = MutableStateFlow<Map<Long, DeviceState>>(emptyMap())
    private val _states = MutableStateFlow<Map<Long, DeviceState>>(emptyMap())
    private val _rounds = MutableStateFlow<Map<Long, RoundInfo>>(emptyMap())
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val lastCached = ConcurrentHashMap<Long, Pair<CachedStateKind, PowerAction?>>()
    private val roundCounter = AtomicLong()
    private val triedIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private var job: Job? = null

    val states: StateFlow<Map<Long, DeviceState>> = _states.asStateFlow()
    val rounds: StateFlow<Map<Long, RoundInfo>> = _rounds.asStateFlow()

    fun device(id: Long): Device? = devices.value?.get(id)

    fun state(id: Long): DeviceState? = _states.value[id]

    /** Katmanın altındaki kesin durum (On/Off). */
    fun baseState(id: Long): DeviceState? = trackers.value[id]?.view(clock.nowMs())

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { trackDevices() }
            launch {
                devices.first { it != null }
                foreground.isForeground.collectLatest { fg ->
                    while (fg && isActive) {
                        val started = clock.nowMs()
                        roundAll()
                        publish() // Wi-Fi yokken de bayatlık görünsün
                        delay((INTERVAL_MS - (clock.nowMs() - started)).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    private suspend fun trackDevices() {
        val cache = runCatching { store.cached() }.getOrDefault(emptyList()).associateBy { it.deviceId }
        store.devices().collect { list ->
            val map = list.associateBy { it.id }
            val current = trackers.value
            val added = map.keys - current.keys
            val fresh = added.associateWith { id ->
                val c = cache[id]
                c?.let { lastCached[id] = it.kind to it.lastPowerAction }
                val tried = runCatching { store.tried(id) }.getOrDefault(false)
                if (tried) triedIds += id
                Tracker.initial(c?.kind == CachedStateKind.ON, c?.lastPowerAction, c?.uptimeSec, tried)
            }
            trackers.update { (it + fresh).filterKeys(map::containsKey) }
            overlays.update { it.filterKeys(map::containsKey) }
            devices.value = map
            publish()
        }
    }

    private suspend fun roundAll() = coroutineScope {
        val ids = devices.value?.keys.orEmpty() - overlays.value.keys
        ids.forEach { id -> launch { round(id) } }
    }

    /** Tile/koordinatör için hemen tur at (tile `onStartListening`). */
    fun requestRound(deviceId: Long) {
        scope.launch { round(deviceId) }
    }

    /**
     * Cihaz için tek tur. Wi-Fi yoksa tur sayılmaz (null). Aynı cihaza eşzamanlı turlar sıraya girer.
     */
    suspend fun round(deviceId: Long): RoundInfo? = locks.getOrPut(deviceId) { Mutex() }.withLock {
        val device = devices.value?.get(deviceId) ?: return null
        val signals = try {
            prober.probe(device)
        } catch (_: NoWifiException) {
            return null
        }
        val now = clock.nowMs()
        val verdict = StatusRules.decide(signals, device.mac, agentConfigured = device.agentId != null)
        val previous = _states.value[deviceId]
        val threshold = if (overlays.value.containsKey(deviceId)) StatusRules.NEGATIVE_ROUNDS_TO_OFF_IN_TRANSITION
        else StatusRules.NEGATIVE_ROUNDS_TO_OFF
        var tracker: Tracker? = null
        trackers.update { all ->
            val t = all[deviceId] ?: return@update all
            tracker = t.next(verdict, now, threshold).withTried(deviceId in triedIds)
            all + (deviceId to tracker!!)
        }
        val info = RoundInfo(roundCounter.incrementAndGet(), now, signals, verdict, tracker?.negatives ?: 0)
        _rounds.update { it + (deviceId to info) }
        publish()
        tracker?.let { persist(deviceId, it, now) }
        val nowState = _states.value[deviceId]
        if (nowState != null) {
            runCatching { observer.onRound(device, signals, verdict, previous, nowState) }
                .onFailure { warn(TAG, "RoundObserver hatası", it) }
        }
        info
    }

    fun setOverlay(deviceId: Long, state: DeviceState?) {
        overlays.update { if (state == null) it - deviceId else it + (deviceId to state) }
        publish()
    }

    /** Koordinatörün kesin kararı (ör. kapanma bitti → Off(lastAction)). */
    suspend fun settle(deviceId: Long, state: DeviceState) {
        val now = clock.nowMs()
        var tracker: Tracker? = null
        trackers.update { all ->
            val t = all[deviceId] ?: return@update all
            tracker = t.settle(state, now)
            all + (deviceId to tracker!!)
        }
        publish()
        tracker?.let { persist(deviceId, it, now) }
    }

    /** Kesin "kapalı" kararı; K9 [lastAction] korunur. */
    suspend fun settleOff(deviceId: Long, lastAction: PowerAction?) =
        settle(deviceId, DeviceState.Off(deviceId in triedIds, lastAction = lastAction))

    fun markTried(deviceId: Long) {
        triedIds += deviceId
        trackers.update { all -> all[deviceId]?.let { all + (deviceId to it.withTried(true)) } ?: all }
        publish()
    }

    private fun publish() {
        val now = clock.nowMs()
        val overlay = overlays.value
        _states.value = trackers.value.mapValues { (id, t) -> overlay[id] ?: t.view(now) }
    }

    /** K7: kesin karar türü ya da son aksiyon değişince önbelleğe yazılır. */
    private suspend fun persist(deviceId: Long, tracker: Tracker, now: Long) {
        val base = tracker.base
        val kind = if (base is DeviceState.On) CachedStateKind.ON else CachedStateKind.OFF
        val lastAction = (base as? DeviceState.Off)?.lastAction
        if (tracker.decidedAt != now || lastCached[deviceId] == (kind to lastAction)) return
        lastCached[deviceId] = kind to lastAction
        runCatching {
            store.cache(DeviceStateCache(deviceId, kind, lastAction, (base as? DeviceState.On)?.uptimeSec, now))
        }.onFailure { warn(TAG, "Durum önbelleği yazılamadı", it) }
    }

    companion object {
        const val INTERVAL_MS = 3_000L
        const val TRANSITION_INTERVAL_MS = 1_000L
        private const val TAG = "StatusEngine"
    }
}
