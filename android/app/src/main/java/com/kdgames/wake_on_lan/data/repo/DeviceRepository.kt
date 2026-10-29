package com.kdgames.wake_on_lan.data.repo

import androidx.room.withTransaction
import com.kdgames.wake_on_lan.data.crypto.KeyVault
import com.kdgames.wake_on_lan.data.db.AppDatabase
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.DeviceStateCache
import com.kdgames.wake_on_lan.data.db.WakeAttempt
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Formdan gelen cihaz bilgisi (spec 10.5). */
data class DeviceInput(
    val name: String,
    val ip: String,
    val mac: String?,
    val iconKey: String = "pc",
    val colorIndex: Int = 0,
)

data class DeviceValidation(val errors: Set<DeviceError>, val warnings: Set<DeviceWarning>) {
    val isValid get() = errors.isEmpty()
}

sealed interface SaveResult {
    data class Saved(val id: Long) : SaveResult
    data class Invalid(val errors: Set<DeviceError>) : SaveResult
}

/** Son 12 deneme (K15), eskiden yeniye. */
data class WakeHistory(val attempts: List<WakeAttempt>) {
    val successes: Int get() = attempts.count { it.success }
    val total: Int get() = attempts.size

    /** Hiç deneme yoksa "yeni" (spec 6). */
    val isNew: Boolean get() = attempts.isEmpty()
    val lastSuccessAt: Long? get() = attempts.lastOrNull { it.success }?.startedAt

    /** "11/12" ya da "yeni". */
    val label: String get() = if (isNew) "yeni" else "$successes/$total"
}

@Singleton
class DeviceRepository @Inject constructor(
    private val db: AppDatabase,
    private val keyVault: KeyVault,
) {
    private val devices = db.deviceDao()
    private val attempts = db.wakeAttemptDao()
    private val stateCache = db.deviceStateCacheDao()

    fun observeDevices(): Flow<List<Device>> = devices.observeAll()

    fun observeDevice(id: Long): Flow<Device?> = devices.observeById(id)

    suspend fun getDevices(): List<Device> = devices.getAll()

    suspend fun getDevice(id: Long): Device? = devices.getById(id)

    fun observeHistory(deviceId: Long): Flow<WakeHistory> =
        attempts.observeRecent(deviceId, HISTORY_SIZE).map { WakeHistory(it.reversed()) }

    suspend fun getHistory(deviceId: Long): WakeHistory =
        WakeHistory(attempts.getRecent(deviceId, HISTORY_SIZE).reversed())

    /**
     * Spec 15. [editingId] düzenlenen cihaz (kendi adı/MAC'i çakışma sayılmaz).
     * [phoneIp]/[phonePrefix] bilinirse alt ağ dışı IP yalnız uyarıdır.
     */
    suspend fun validate(
        input: DeviceInput,
        editingId: Long? = null,
        phoneIp: String? = null,
        phonePrefix: Int? = null,
    ): DeviceValidation {
        val errors = mutableSetOf<DeviceError>()
        val warnings = mutableSetOf<DeviceWarning>()

        if (!Validation.isValidName(input.name)) {
            errors += DeviceError.NAME_INVALID
        } else {
            val key = Validation.nameKey(input.name)
            if (devices.allForNameCheck().any { it.id != editingId && Validation.nameKey(it.name) == key }) {
                errors += DeviceError.NAME_DUPLICATE
            }
        }

        if (!Validation.isValidIp(input.ip)) {
            errors += DeviceError.IP_INVALID
        } else if (!Validation.isInSubnet(input.ip, phoneIp, phonePrefix)) {
            warnings += DeviceWarning.IP_OUTSIDE_SUBNET
        }

        when (val mac = Validation.parseMac(input.mac)) {
            Validation.MacResult.Invalid -> errors += DeviceError.MAC_INVALID
            is Validation.MacResult.Valid -> {
                val owner = devices.findByMac(mac.mac)
                if (owner != null && owner.id != editingId) errors += DeviceError.MAC_DUPLICATE
            }
            Validation.MacResult.Empty -> Unit
        }
        return DeviceValidation(errors, warnings)
    }

    suspend fun add(input: DeviceInput): SaveResult = db.withTransaction {
        val validation = validate(input)
        if (!validation.isValid) return@withTransaction SaveResult.Invalid(validation.errors)
        val id = devices.insert(
            Device(
                name = Validation.normalizeName(input.name),
                ip = input.ip.trim(),
                mac = (Validation.parseMac(input.mac) as? Validation.MacResult.Valid)?.mac,
                iconKey = input.iconKey,
                colorIndex = input.colorIndex,
                sortOrder = devices.nextSortOrder(),
            ),
        )
        SaveResult.Saved(id)
    }

    /** Form alanlarını günceller; agent ve sıra bilgisi korunur. */
    suspend fun update(id: Long, input: DeviceInput): SaveResult = db.withTransaction {
        val existing = devices.getById(id) ?: return@withTransaction SaveResult.Invalid(emptySet())
        val validation = validate(input, editingId = id)
        if (!validation.isValid) return@withTransaction SaveResult.Invalid(validation.errors)
        devices.update(
            existing.copy(
                name = Validation.normalizeName(input.name),
                ip = input.ip.trim(),
                mac = (Validation.parseMac(input.mac) as? Validation.MacResult.Valid)?.mac,
                iconKey = input.iconKey,
                colorIndex = input.colorIndex,
            ),
        )
        SaveResult.Saved(id)
    }

    /** Agent/ağ katmanından gelen alan güncellemeleri (IP değişimi, hostname, agent eşleşmesi…). */
    suspend fun updateDevice(device: Device) = devices.update(device)

    /** MAC başka bir cihazda kayıtlı mı (unique index; otomatik doldurmada çakışmayı önler). */
    suspend fun macInUse(mac: String, exceptId: Long): Boolean =
        devices.findByMac(mac)?.let { it.id != exceptId } ?: false

    /** Cihazı, denemelerini, zamanlayıcılarını (cascade) ve agent anahtarını siler. */
    suspend fun delete(id: Long, keepAgentKey: Boolean = false) {
        val device = devices.getById(id) ?: return
        devices.delete(id)
        // K25: agent'a UNPAIR gönderilecekse anahtarı AgentLinker siler.
        if (!keepAgentKey) device.agentKeyAlias?.let(keyVault::delete)
    }

    suspend fun recordWakeAttempt(
        deviceId: Long,
        startedAt: Long,
        success: Boolean,
        durationSec: Int?,
        source: AttemptSource,
    ): Long = db.withTransaction {
        val id = attempts.insert(WakeAttempt(deviceId = deviceId, startedAt = startedAt, success = success, durationSec = durationSec, source = source))
        attempts.trim(deviceId, ATTEMPT_RETENTION)
        id
    }

    suspend fun cacheState(entry: DeviceStateCache) = stateCache.upsert(entry)

    suspend fun cachedStates(): List<DeviceStateCache> = stateCache.getAll()

    companion object {
        const val HISTORY_SIZE = 12
        const val ATTEMPT_RETENTION = 100
    }
}
