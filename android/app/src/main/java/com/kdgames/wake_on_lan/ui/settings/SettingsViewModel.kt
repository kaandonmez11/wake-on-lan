package com.kdgames.wake_on_lan.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.prefs.Settings
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.RetryPolicy
import com.kdgames.wake_on_lan.domain.model.ShutdownMode
import com.kdgames.wake_on_lan.domain.model.TileMode
import com.kdgames.wake_on_lan.net.WifiStateSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUi(
    val settings: Settings = Settings(),
    val devices: List<Device> = emptyList(),
    val permissions: List<PermissionRowUi> = PermissionRows.of(PermissionSnapshot()),
    val snapshot: PermissionSnapshot = PermissionSnapshot(),
    val loaded: Boolean = false,
)

/** Ayarlar ekranı (spec 10.7). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val devices: DeviceRepository,
    private val permissions: PermissionSource,
    private val wifi: WifiStateSource,
) : ViewModel() {
    private val snapshot = MutableStateFlow(permissions.snapshot())
    private val denied = MutableStateFlow<Set<PermissionKind>>(emptySet())

    val state: StateFlow<SettingsUi> = combine(store.settings, devices.observeDevices(), snapshot, denied) { s, devs, snap, d ->
        SettingsUi(s, devs, PermissionRows.of(snap, d), snap, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUi())

    /** Ekrana dönüşte (ON_RESUME) ve izin sonucundan sonra. */
    fun refreshPermissions() {
        val before = snapshot.value
        val now = permissions.snapshot()
        snapshot.value = now
        // Verilen izin "reddedildi" listesinden düşer; konum yeni açıldıysa SSID yeniden okunur.
        denied.update { set -> set.filterNot { granted(now, it) }.toSet() }
        if ((!before.fineLocation || !before.locationService) && now.fineLocation && now.locationService) wifi.refresh()
    }

    fun onPermissionResult(kind: PermissionKind, granted: Boolean) {
        if (!granted) denied.update { it + kind }
        refreshPermissions()
    }

    private fun granted(s: PermissionSnapshot, kind: PermissionKind) = when (kind) {
        PermissionKind.LOCAL_NETWORK -> s.localNetwork
        PermissionKind.LOCATION -> s.fineLocation
        PermissionKind.EXACT_ALARM -> s.exactAlarm
        PermissionKind.NOTIFICATIONS -> s.notifications
        PermissionKind.BATTERY -> s.batteryUnrestricted
    }

    /** Aralık değişimi; geçersizse (aralık dışı ya da vazgeçme < aralık) false → alan titrer, eski değer kalır. */
    fun setInterval(value: Int): Boolean = setRetry(value, state.value.settings.giveUpMin)

    fun setGiveUp(value: Int): Boolean = setRetry(state.value.settings.retryIntervalMin, value)

    private fun setRetry(interval: Int, giveUp: Int): Boolean {
        if (RetryPolicy.validate(interval, giveUp) != null) return false
        viewModelScope.launch { store.setRetryPolicy(interval, giveUp) }
        return true
    }

    fun setShutdownMode(deviceId: Long, mode: ShutdownMode) {
        viewModelScope.launch { devices.getDevice(deviceId)?.let { if (it.shutdownMode != mode) devices.updateDevice(it.copy(shutdownMode = mode)) } }
    }

    fun setTileSlot(slot: Int, deviceId: Long?) {
        viewModelScope.launch { store.setTileSlot(slot, deviceId) }
    }

    fun setTileMode(mode: TileMode) {
        viewModelScope.launch { store.setTileMode(mode) }
    }
}
