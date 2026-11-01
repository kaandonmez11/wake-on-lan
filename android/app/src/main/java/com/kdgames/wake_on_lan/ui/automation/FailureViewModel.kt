package com.kdgames.wake_on_lan.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.db.ScheduleRun
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.schedule.AttemptOutcome
import com.kdgames.wake_on_lan.domain.schedule.FailureLabels
import com.kdgames.wake_on_lan.domain.schedule.RunAttempts
import com.kdgames.wake_on_lan.domain.schedule.ScheduleTexts
import com.kdgames.wake_on_lan.domain.schedule.WallClock
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

data class AttemptRow(val time: String, val reason: String, val ok: Boolean)

/** Başarısız ekranı (spec 10.4). */
data class FailureUi(
    val runId: Long,
    val deviceId: Long,
    val action: PowerAction,
    val iconKey: String,
    val palette: DevicePalette,
    val title: String,
    val subtitle: String,
    val header: String,
    val gaveUp: String,
    val attempts: List<AttemptRow>,
) {
    /** "Kontrol et" kartı yalnız WAKE için. */
    val showChecks: Boolean get() = action == PowerAction.WAKE

    companion object {
        fun of(run: ScheduleRun, schedule: Schedule, device: Device?, now: Long, zone: ZoneId): FailureUi {
            val attempts = RunAttempts.decode(run.attemptsJson)
            val name = device?.name ?: "Cihaz"
            return FailureUi(
                runId = run.id,
                deviceId = schedule.deviceId,
                action = schedule.action,
                iconKey = device?.iconKey ?: "pc",
                palette = DevicePalette.of(device?.colorIndex ?: 0),
                title = ScheduleTexts.failureTitle(name, schedule.action),
                subtitle = FailureLabels.subtitle(schedule, attempts.firstOrNull()?.at ?: run.startedAt, run.endedAt, now, zone),
                header = FailureLabels.header(attempts),
                gaveUp = FailureLabels.gaveUp(attempts),
                attempts = attempts.map { AttemptRow(FailureLabels.clock(it.at, zone), it.reason, it.outcome == AttemptOutcome.OK) },
            )
        }
    }
}

@HiltViewModel
class FailureViewModel @Inject constructor(
    schedules: ScheduleRepository,
    devices: DeviceRepository,
    private val wall: WallClock,
) : ViewModel() {
    private val runId = MutableStateFlow<Long?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<FailureUi?> = runId.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else combine(schedules.observeRun(id), schedules.observeSchedules(), devices.observeDevices()) { run, list, devs ->
            val schedule = run?.let { r -> list.firstOrNull { it.id == r.scheduleId } } ?: return@combine null
            FailureUi.of(run, schedule, devs.firstOrNull { it.id == schedule.deviceId }, wall.nowMs(), ZoneId.systemDefault())
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun open(id: Long) {
        runId.value = id
    }
}
