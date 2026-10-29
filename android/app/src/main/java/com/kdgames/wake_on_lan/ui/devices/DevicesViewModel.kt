package com.kdgames.wake_on_lan.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.WakeHistory
import com.kdgames.wake_on_lan.domain.DomainEvent
import com.kdgames.wake_on_lan.domain.DomainEvents
import com.kdgames.wake_on_lan.domain.agent.AgentLinker
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.power.PowerCoordinator
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import com.kdgames.wake_on_lan.domain.wake.WakeCoordinator
import com.kdgames.wake_on_lan.net.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cihazlar sekmesi (spec 10.2). Yalnız StatusEngine ve repository'leri okur; aksiyonları
 * koordinatörlere iletir. Seçili cihaz DataStore'da (spec 6).
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val repo: DeviceRepository,
    engine: StatusEngine,
    private val linker: AgentLinker,
    monitor: NetworkMonitor,
    private val settings: SettingsStore,
    private val wake: WakeCoordinator,
    private val power: PowerCoordinator,
    events: DomainEvents,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val histories: Flow<Map<Long, WakeHistory>> = repo.observeDevices()
        .map { list -> list.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { id -> repo.observeHistory(id).map { id to it } }) { it.toMap() }
        }

    val state: StateFlow<DevicesUiState> = combine(
        repo.observeDevices(),
        engine.states,
        histories,
        linker.issues,
        combine(monitor.state, settings.settings.map { it.selectedDeviceId }) { w, s -> w to s },
    ) { devices, states, hist, issues, (wifi, selected) ->
        DevicesUiState(
            wifi = wifi,
            devices = devices.map { d -> DeviceUi(d, states[d.id], hist[d.id] ?: WakeHistory(emptyList()), issues[d.id]) },
            selectedId = selected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    /** Arka plan tonu ayrı akış: yalnız gerçekten değişince yayınlanır (her ping ms'sinde kökü yeniden çizdirmez). */
    val tint: StateFlow<androidx.compose.ui.graphics.Color> = state
        .map { backgroundTint(it.selected) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), androidx.compose.ui.graphics.Color.Unspecified)

    val events: SharedFlow<DomainEvent> = events.events

    fun select(id: Long) {
        viewModelScope.launch { settings.setSelectedDevice(id) }
    }

    fun wake(id: Long) = wake.wake(id)

    fun power(id: Long, action: PowerAction) = power.execute(id, action)

    /** Kart menüsünden silme: paneldeki gibi agent eşleşmesi de kalkar (K25). */
    fun delete(id: Long) {
        viewModelScope.launch {
            val device = repo.getDevice(id) ?: return@launch
            repo.delete(id, keepAgentKey = true)
            linker.release(device)
        }
    }
}
