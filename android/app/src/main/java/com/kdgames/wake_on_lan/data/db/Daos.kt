package com.kdgames.wake_on_lan.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    /** Ekleme sırası (K14). */
    @Query("SELECT * FROM Device ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Device>>

    @Query("SELECT * FROM Device ORDER BY sortOrder, id")
    suspend fun getAll(): List<Device>

    @Query("SELECT * FROM Device WHERE id = :id")
    suspend fun getById(id: Long): Device?

    @Query("SELECT * FROM Device WHERE id = :id")
    fun observeById(id: Long): Flow<Device?>

    @Query("SELECT * FROM Device WHERE mac = :mac LIMIT 1")
    suspend fun findByMac(mac: String): Device?

    /** Ad benzersizliği büyük/küçük harf duyarsız (Türkçe İ/ı için repository ayrıca normalize eder). */
    @Query("SELECT * FROM Device")
    suspend fun allForNameCheck(): List<Device>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM Device")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(device: Device): Long

    @Update
    suspend fun update(device: Device)

    @Query("DELETE FROM Device WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface WakeAttemptDao {
    @Insert
    suspend fun insert(attempt: WakeAttempt): Long

    /** En yeniden eskiye son [limit] deneme. */
    @Query("SELECT * FROM WakeAttempt WHERE deviceId = :deviceId ORDER BY startedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(deviceId: Long, limit: Int): Flow<List<WakeAttempt>>

    @Query("SELECT * FROM WakeAttempt WHERE deviceId = :deviceId ORDER BY startedAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(deviceId: Long, limit: Int): List<WakeAttempt>

    @Query("SELECT COUNT(*) FROM WakeAttempt WHERE deviceId = :deviceId")
    suspend fun count(deviceId: Long): Int

    /** K15: cihaz başına en yeni [keep] kayıt kalır. */
    @Query(
        """
        DELETE FROM WakeAttempt WHERE deviceId = :deviceId AND id NOT IN (
            SELECT id FROM WakeAttempt WHERE deviceId = :deviceId ORDER BY startedAt DESC, id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(deviceId: Long, keep: Int)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM Schedule ORDER BY id")
    fun observeAll(): Flow<List<Schedule>>

    @Query("SELECT * FROM Schedule ORDER BY id")
    suspend fun getAll(): List<Schedule>

    @Query("SELECT * FROM Schedule WHERE id = :id")
    suspend fun getById(id: Long): Schedule?

    @Query("SELECT * FROM Schedule WHERE deviceId = :deviceId ORDER BY id")
    suspend fun getForDevice(deviceId: Long): List<Schedule>

    @Insert
    suspend fun insert(schedule: Schedule): Long

    @Update
    suspend fun update(schedule: Schedule)

    @Delete
    suspend fun delete(schedule: Schedule)
}

@Dao
interface ScheduleRunDao {
    @Insert
    suspend fun insert(run: ScheduleRun): Long

    @Update
    suspend fun update(run: ScheduleRun)

    @Query("SELECT * FROM ScheduleRun WHERE id = :id")
    suspend fun getById(id: Long): ScheduleRun?

    @Query("SELECT * FROM ScheduleRun WHERE scheduleId = :scheduleId ORDER BY startedAt DESC, id DESC LIMIT 1")
    suspend fun latest(scheduleId: Long): ScheduleRun?

    @Query("SELECT * FROM ScheduleRun WHERE scheduleId = :scheduleId ORDER BY startedAt DESC, id DESC LIMIT 1")
    fun observeLatest(scheduleId: Long): Flow<ScheduleRun?>

    @Query("SELECT COUNT(*) FROM ScheduleRun WHERE scheduleId = :scheduleId")
    suspend fun count(scheduleId: Long): Int

    /** K15: zamanlayıcı başına en yeni [keep] kayıt kalır. */
    @Query(
        """
        DELETE FROM ScheduleRun WHERE scheduleId = :scheduleId AND id NOT IN (
            SELECT id FROM ScheduleRun WHERE scheduleId = :scheduleId ORDER BY startedAt DESC, id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(scheduleId: Long, keep: Int)
}

@Dao
interface DeviceStateCacheDao {
    @Upsert
    suspend fun upsert(entry: DeviceStateCache)

    @Query("SELECT * FROM DeviceStateCache")
    suspend fun getAll(): List<DeviceStateCache>

    @Query("SELECT * FROM DeviceStateCache WHERE deviceId = :deviceId")
    suspend fun get(deviceId: Long): DeviceStateCache?
}
