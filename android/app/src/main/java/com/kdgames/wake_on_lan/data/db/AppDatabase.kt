package com.kdgames.wake_on_lan.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Device::class, WakeAttempt::class, Schedule::class, ScheduleRun::class, DeviceStateCache::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // S05: Device.sleepHibernates (K23)
        AutoMigration(from = 2, to = 3), // S08: Schedule.notify
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun wakeAttemptDao(): WakeAttemptDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun scheduleRunDao(): ScheduleRunDao
    abstract fun deviceStateCacheDao(): DeviceStateCacheDao

    companion object {
        const val NAME = "wol.db"
    }
}
