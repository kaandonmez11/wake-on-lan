package com.kdgames.wake_on_lan.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.data.repo.ScheduleSaveResult
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import com.kdgames.wake_on_lan.domain.schedule.ScheduleEngine
import com.kdgames.wake_on_lan.domain.schedule.WallClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

sealed interface EditorMode {
    data object Add : EditorMode
    data class Edit(val id: Long) : EditorMode
}

data class EditorForm(
    val deviceId: Long? = null,
    val action: PowerAction = PowerAction.WAKE,
    val daysMask: Int = ScheduleEngine.WEEKDAYS,
    val minuteOfDay: Int = 8 * 60 + 30,
    /** Çalışınca bildirim (S08, Kaan). */
    val notify: Boolean = false,
)

/** Kaydet'i engelleyen durumlar (spec 15 zamanlayıcı). */
enum class EditorIssue(val hint: String) {
    NO_DEVICE("Cihaz seç"),
    NO_DAYS("En az bir gün seç"),
    NO_AGENT("Bu aksiyon için cihazda agent eşli olmalı"),
    NO_HIBERNATE("Bu PC'de hazırda bekletme kapalı"),
}

data class EditorUiState(
    val mode: EditorMode = EditorMode.Add,
    val form: EditorForm = EditorForm(),
    val devices: List<Device> = emptyList(),
    val saving: Boolean = false,
    /** Önizleme: "Sonraki: Yarın 08:30". */
    val preview: String? = null,
) {
    val isEdit: Boolean get() = mode is EditorMode.Edit
    val device: Device? get() = devices.firstOrNull { it.id == form.deviceId }

    val issue: EditorIssue?
        get() {
            val d = device ?: return EditorIssue.NO_DEVICE
            return when {
                form.daysMask and ScheduleEngine.EVERY_DAY == 0 -> EditorIssue.NO_DAYS
                form.action.requiresAgent && d.agentId == null -> EditorIssue.NO_AGENT
                form.action == PowerAction.HIBERNATE && !d.hibernateAvailable -> EditorIssue.NO_HIBERNATE
                else -> null
            }
        }

    val canSave: Boolean get() = issue == null && !saving
    val hint: String get() = issue?.hint ?: preview.orEmpty()
}

/** Zamanlayıcı ekle/düzenle paneli (spec 10.3). WIFI tetikleyicisi S09'a kadar pasif. */
@HiltViewModel
class ScheduleEditorViewModel @Inject constructor(
    private val schedules: ScheduleRepository,
    private val devices: DeviceRepository,
    private val wall: WallClock,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var original: Schedule? = null

    init {
        viewModelScope.launch { devices.observeDevices().collect { list -> _state.update { it.copy(devices = list).withPreview() } } }
    }

    /** [preferredDevice]: eklerken seçili cihaz. */
    fun open(mode: EditorMode, preferredDevice: Long? = null) {
        original = null
        val now = Instant.ofEpochMilli(wall.nowMs()).atZone(ZoneId.systemDefault())
        // Yeni zamanlayıcı: şimdiki saatten sonraki ilk 5 dk dilimi.
        val rounded = ((now.hour * 60 + now.minute) / 5 + 1) * 5 % (24 * 60)
        _state.update {
            val device = preferredDevice?.takeIf { id -> it.devices.any { d -> d.id == id } } ?: it.devices.firstOrNull()?.id
            EditorUiState(mode = mode, devices = it.devices, form = EditorForm(deviceId = device, minuteOfDay = rounded)).withPreview()
        }
        if (mode is EditorMode.Edit) viewModelScope.launch {
            val s = schedules.getSchedule(mode.id) ?: return@launch
            original = s
            _state.update {
                it.copy(form = EditorForm(s.deviceId, s.action, s.daysMask, s.minuteOfDay ?: 0, s.notify)).withPreview()
            }
        }
    }

    fun edit(transform: EditorForm.() -> EditorForm) = _state.update { it.copy(form = it.form.transform()).withPreview() }

    fun toggleDay(bit: Int) = edit { copy(daysMask = daysMask xor bit) }

    fun shiftMinutes(delta: Int) = edit { copy(minuteOfDay = (minuteOfDay + delta).mod(24 * 60)) }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val base = original ?: Schedule(deviceId = s.form.deviceId!!, action = s.form.action, type = ScheduleType.TIME)
            // Saat/gün değişince eski atlama anlamını yitirir.
            val changed = base.daysMask != s.form.daysMask || base.minuteOfDay != s.form.minuteOfDay
            val schedule = base.copy(
                deviceId = s.form.deviceId!!,
                action = s.form.action,
                type = ScheduleType.TIME,
                daysMask = s.form.daysMask,
                minuteOfDay = s.form.minuteOfDay,
                notify = s.form.notify,
                skippedOccurrence = if (changed) null else base.skippedOccurrence,
            )
            when (schedules.save(schedule)) {
                is ScheduleSaveResult.Saved -> onDone()
                is ScheduleSaveResult.Invalid -> _state.update { it.copy(saving = false) }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = (_state.value.mode as? EditorMode.Edit)?.id ?: return
        viewModelScope.launch {
            schedules.getSchedule(id)?.let { schedules.delete(it) }
            onDone()
        }
    }

    private fun EditorUiState.withPreview(): EditorUiState {
        val now = wall.nowMs()
        val zone = ZoneId.systemDefault()
        val probe = Schedule(deviceId = 0, action = form.action, type = ScheduleType.TIME, daysMask = form.daysMask, minuteOfDay = form.minuteOfDay)
        val next = ScheduleEngine.nextOccurrence(probe, now, zone)
        return copy(preview = next?.let { "Sonraki: ${ScheduleEngine.whenLabel(it, now, zone)}" })
    }
}
