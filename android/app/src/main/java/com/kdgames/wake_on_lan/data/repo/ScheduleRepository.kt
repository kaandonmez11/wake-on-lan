package com.kdgames.wake_on_lan.data.repo

import androidx.room.withTransaction
import com.kdgames.wake_on_lan.data.db.AppDatabase
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.db.ScheduleRun
import com.kdgames.wake_on_lan.domain.model.RunResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScheduleSaveResult {
    data class Saved(val id: Long) : ScheduleSaveResult
    data class Invalid(val errors: List<ScheduleError>) : ScheduleSaveResult
}

/**
 * Zamanlayıcı CRUD + çalışma kayıtları. "Aksiyon agent gerektiriyorsa cihaz eşli olmalı"
 * kuralı editörde (S08) uygulanır; burada yalnız yapısal doğrulama var (spec 15).
 */
@Singleton
class ScheduleRepository @Inject constructor(private val db: AppDatabase) {
    private val schedules = db.scheduleDao()
    private val runs = db.scheduleRunDao()

    fun observeSchedules(): Flow<List<Schedule>> = schedules.observeAll()

    suspend fun getSchedules(): List<Schedule> = schedules.getAll()

    suspend fun getSchedule(id: Long): Schedule? = schedules.getById(id)

    suspend fun save(schedule: Schedule): ScheduleSaveResult = db.withTransaction {
        val errors = Validation.validateSchedule(schedule.type, schedule.daysMask, schedule.minuteOfDay, schedule.ssid)
            .toMutableList()
        if (db.deviceDao().getById(schedule.deviceId) == null) errors += ScheduleError.DEVICE_MISSING
        if (errors.isNotEmpty()) return@withTransaction ScheduleSaveResult.Invalid(errors)
        val normalized = schedule.copy(ssid = schedule.ssid?.trim())
        if (schedule.id == 0L) {
            ScheduleSaveResult.Saved(schedules.insert(normalized))
        } else {
            schedules.update(normalized)
            ScheduleSaveResult.Saved(schedule.id)
        }
    }

    suspend fun update(schedule: Schedule) = schedules.update(schedule)

    suspend fun delete(schedule: Schedule) = schedules.delete(schedule)

    fun observeLatestRun(scheduleId: Long): Flow<ScheduleRun?> = runs.observeLatest(scheduleId)

    suspend fun latestRun(scheduleId: Long): ScheduleRun? = runs.latest(scheduleId)

    suspend fun getRun(id: Long): ScheduleRun? = runs.getById(id)

    /** Debug › Zamanlayıcı: tüm zamanlayıcıların son çalışmaları. */
    fun observeRecentRuns(limit: Int = 20): Flow<List<ScheduleRun>> = runs.observeRecent(limit)

    suspend fun startRun(scheduleId: Long, occurrence: Long, startedAt: Long): Long = db.withTransaction {
        val id = runs.insert(
            ScheduleRun(scheduleId = scheduleId, occurrence = occurrence, startedAt = startedAt, endedAt = null, result = RunResult.RUNNING),
        )
        runs.trim(scheduleId, RUN_RETENTION)
        id
    }

    suspend fun updateRun(run: ScheduleRun) = runs.update(run)

    companion object {
        const val RUN_RETENTION = 100
    }
}
