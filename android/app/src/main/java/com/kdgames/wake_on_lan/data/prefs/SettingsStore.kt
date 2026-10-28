package com.kdgames.wake_on_lan.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kdgames.wake_on_lan.data.repo.RetryError
import com.kdgames.wake_on_lan.data.repo.RetryPolicy
import com.kdgames.wake_on_lan.domain.model.TileMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Spec 6 DataStore ayarları. */
data class Settings(
    val retryIntervalMin: Int = RetryPolicy.DEFAULT_INTERVAL,
    val giveUpMin: Int = RetryPolicy.DEFAULT_GIVE_UP,
    val hapticsEnabled: Boolean = true,
    /** 3 slot → deviceId; null = boş. */
    val tileSlots: List<Long?> = List(TILE_SLOT_COUNT) { null },
    val tileMode: TileMode = TileMode.A,
    val phoneId: String? = null,
    val selectedDeviceId: Long? = null,
) {
    companion object {
        const val TILE_SLOT_COUNT = 3
    }
}

@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Test için enjekte edilebilir UUID üretici. */
    internal var newPhoneId: () -> String = { UUID.randomUUID().toString() }

    val settings: Flow<Settings> = dataStore.data.map(::read)

    suspend fun current(): Settings = settings.first()

    /** İlk açılışta üretilir, sonra hiç değişmez (agent eşleşmesindeki kimlik). */
    suspend fun ensurePhoneId(): String {
        current().phoneId?.let { return it }
        var id = ""
        dataStore.edit { prefs ->
            id = prefs[Keys.PHONE_ID] ?: newPhoneId().also { prefs[Keys.PHONE_ID] = it }
        }
        return id
    }

    /** İki değer birlikte doğrulanır (giveUp ≥ interval); geçersizse hiçbir şey yazılmaz. */
    suspend fun setRetryPolicy(intervalMin: Int, giveUpMin: Int): RetryError? {
        RetryPolicy.validate(intervalMin, giveUpMin)?.let { return it }
        dataStore.edit {
            it[Keys.RETRY_INTERVAL] = intervalMin
            it[Keys.GIVE_UP] = giveUpMin
        }
        return null
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.HAPTICS] = enabled }
    }

    suspend fun setTileSlot(slot: Int, deviceId: Long?) {
        require(slot in 0 until Settings.TILE_SLOT_COUNT) { "Geçersiz slot: $slot" }
        dataStore.edit {
            val key = Keys.tileSlot(slot)
            if (deviceId == null) it.remove(key) else it[key] = deviceId
        }
    }

    suspend fun setTileMode(mode: TileMode) {
        dataStore.edit { it[Keys.TILE_MODE] = mode.name }
    }

    suspend fun setSelectedDevice(deviceId: Long?) {
        dataStore.edit { if (deviceId == null) it.remove(Keys.SELECTED_DEVICE) else it[Keys.SELECTED_DEVICE] = deviceId }
    }

    private fun read(prefs: Preferences) = Settings(
        retryIntervalMin = prefs[Keys.RETRY_INTERVAL] ?: RetryPolicy.DEFAULT_INTERVAL,
        giveUpMin = prefs[Keys.GIVE_UP] ?: RetryPolicy.DEFAULT_GIVE_UP,
        hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
        tileSlots = List(Settings.TILE_SLOT_COUNT) { prefs[Keys.tileSlot(it)] },
        tileMode = prefs[Keys.TILE_MODE]?.let { name -> TileMode.entries.firstOrNull { it.name == name } } ?: TileMode.A,
        phoneId = prefs[Keys.PHONE_ID],
        selectedDeviceId = prefs[Keys.SELECTED_DEVICE],
    )

    private object Keys {
        val RETRY_INTERVAL = intPreferencesKey("retryIntervalMin")
        val GIVE_UP = intPreferencesKey("giveUpMin")
        val HAPTICS = booleanPreferencesKey("hapticsEnabled")
        val TILE_MODE = stringPreferencesKey("tileMode")
        val PHONE_ID = stringPreferencesKey("phoneId")
        val SELECTED_DEVICE = longPreferencesKey("selectedDeviceId")
        private val slots = List(Settings.TILE_SLOT_COUNT) { longPreferencesKey("tileSlot$it") }
        fun tileSlot(index: Int) = slots[index]
    }
}
