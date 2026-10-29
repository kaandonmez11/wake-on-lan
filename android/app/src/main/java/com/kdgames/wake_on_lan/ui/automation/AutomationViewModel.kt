package com.kdgames.wake_on_lan.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.schedule.ScheduleEngine
import com.kdgames.wake_on_lan.domain.schedule.ScheduleService
import com.kdgames.wake_on_lan.domain.schedule.WallClock
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.net.WifiStateSource
import com.kdgames.wake_on_lan.ui.devices.DeviceLabels
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** Aksiyon etiketi rengi (spec 10.3): Aç yeşil, Kapat kırmızımsı, diğerleri nötr. */
enum class ActionTone { WAKE, SHUTDOWN, NEUTRAL }

data class ScheduleCardUi(
    val schedule: Schedule,
    val deviceName: String,
    val iconKey: String,
    val palette: DevicePalette,
    val actionLabel: String,
    val tone: ActionTone,
    val trigger: String,
    /** "Sonraki: Yarın 08:30" ya da amber "Yarın atlandı · sonraki …". */
    val nextLine: String?,
    val skipped: Boolean,
    val skipButton: String,
) {
    val id: Long get() = schedule.id
    val enabled: Boolean get() = schedule.enabled
}

data class AutomationUiState(
    val wifi: WifiState = WifiState.Disconnected,
    val cards: List<ScheduleCardUi> = emptyList(),
    val hasDevices: Boolean = false,
    /** false → geçici uyarı satırı (spec 9.4; Ayarlar'daki kırmızı satır S10'da). */
    val exactAlarmsAllowed: Boolean = true,
    val loaded: Boolean = false,
)

object ScheduleLabels {
    fun action(action: PowerAction): String = if (action == PowerAction.WAKE) "Aç" else DeviceLabels.actionTitle(action)

    fun tone(action: PowerAction): ActionTone = when (action) {
        PowerAction.WAKE -> ActionTone.WAKE
        PowerAction.SHUTDOWN -> ActionTone.SHUTDOWN
        else -> ActionTone.NEUTRAL
    }

    fun card(s: Schedule, device: Device?, now: Long, zone: ZoneId): ScheduleCardUi = ScheduleCardUi(
        schedule = s,
        deviceName = device?.name ?: "Cihaz",
        iconKey = device?.iconKey ?: "pc",
        palette = DevicePalette.of(device?.colorIndex ?: 0),
        actionLabel = action(s.action),
        tone = tone(s.action),
        trigger = ScheduleEngine.triggerLabel(s),
        nextLine = ScheduleEngine.nextLine(s, now, zone),
        skipped = ScheduleEngine.isSkipped(s, now),
        skipButton = ScheduleEngine.skipButton(s, now, zone),
    )
}

/** Otomasyon sekmesi (spec 10.3). "Bugün/Yarın" metinleri dakikada iki kez ve saat dilimi değişince yenilenir. */
@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val schedules: ScheduleRepository,
    devices: DeviceRepository,
    wifi: WifiStateSource,
    private val service: ScheduleService,
    private val wall: WallClock,
) : ViewModel() {
    private val ticks = flow {
        while (true) {
            emit(wall.nowMs() to ZoneId.systemDefault())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<AutomationUiState> = combine(
        schedules.observeSchedules(),
        devices.observeDevices(),
        wifi.state,
        ticks,
    ) { list, devs, w, (now, zone) ->
        val byId = devs.associateBy { it.id }
        AutomationUiState(
            wifi = w,
            cards = list.map { ScheduleLabels.card(it, byId[it.deviceId], now, zone) },
            hasDevices = devs.isNotEmpty(),
            exactAlarmsAllowed = service.canScheduleExact(),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationUiState())

    fun setEnabled(id: Long, enabled: Boolean) = edit(id) { ScheduleEngine.setEnabled(it, enabled) }

    fun delete(id: Long) {
        viewModelScope.launch { schedules.getSchedule(id)?.let { schedules.delete(it) } }
    }

    fun toggleSkip(id: Long) = edit(id) { s ->
        val now = wall.nowMs()
        if (ScheduleEngine.isSkipped(s, now)) ScheduleEngine.undoSkip(s) else ScheduleEngine.skip(s, now, ZoneId.systemDefault())
    }

    private fun edit(id: Long, transform: (Schedule) -> Schedule) {
        viewModelScope.launch {
            val s = schedules.getSchedule(id) ?: return@launch
            schedules.update(transform(s))
        }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
