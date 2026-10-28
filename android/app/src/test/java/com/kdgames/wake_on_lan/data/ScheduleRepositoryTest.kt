package com.kdgames.wake_on_lan.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kdgames.wake_on_lan.data.db.AppDatabase
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.repo.DeviceInput
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.SaveResult
import com.kdgames.wake_on_lan.data.repo.ScheduleError
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.data.repo.ScheduleSaveResult
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RunResult
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScheduleRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private var deviceId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ScheduleRepository(db)
        deviceId = (DeviceRepository(db, FakeKeyVault()).add(DeviceInput("PC", "192.168.1.5", null)) as SaveResult.Saved).id
    }

    @After
    fun tearDown() = db.close()

    private fun time(days: Int = 0b0011111, minute: Int? = 510) =
        Schedule(deviceId = deviceId, action = PowerAction.WAKE, type = ScheduleType.TIME, daysMask = days, minuteOfDay = minute)

    @Test
    fun zamanIcinEnAzBirGunVeGecerliSaat() = runBlocking {
        assertEquals(ScheduleSaveResult.Invalid(listOf(ScheduleError.NO_DAYS)), repo.save(time(days = 0)))
        assertEquals(ScheduleSaveResult.Invalid(listOf(ScheduleError.INVALID_TIME)), repo.save(time(minute = 1440)))
        assertEquals(ScheduleSaveResult.Invalid(listOf(ScheduleError.INVALID_TIME)), repo.save(time(minute = null)))
        val saved = repo.save(time()) as ScheduleSaveResult.Saved
        assertEquals(510, repo.getSchedule(saved.id)!!.minuteOfDay)
    }

    @Test
    fun wifiIcinSsidZorunluVeTrimlenir() = runBlocking {
        val wifi = Schedule(deviceId = deviceId, action = PowerAction.WAKE, type = ScheduleType.WIFI_CONNECT)
        assertEquals(ScheduleSaveResult.Invalid(listOf(ScheduleError.NO_SSID)), repo.save(wifi.copy(ssid = "  ")))
        val saved = repo.save(wifi.copy(ssid = " Ev-WiFi ")) as ScheduleSaveResult.Saved
        assertEquals("Ev-WiFi", repo.getSchedule(saved.id)!!.ssid)
    }

    @Test
    fun olmayanCihazaZamanlayiciKaydedilmez() = runBlocking {
        val result = repo.save(time().copy(deviceId = 999))
        assertEquals(ScheduleSaveResult.Invalid(listOf(ScheduleError.DEVICE_MISSING)), result)
    }

    @Test
    fun duzenlemeAyniKaydiGunceller() = runBlocking {
        val id = (repo.save(time()) as ScheduleSaveResult.Saved).id
        repo.save(repo.getSchedule(id)!!.copy(minuteOfDay = 600, enabled = false))
        val s = repo.getSchedules().single()
        assertEquals(600, s.minuteOfDay)
        assertEquals(false, s.enabled)
    }

    @Test
    fun calismaKayitlariVe100Siniri() = runBlocking {
        val id = (repo.save(time()) as ScheduleSaveResult.Saved).id
        repeat(103) { i -> repo.startRun(id, occurrence = i.toLong(), startedAt = 1_000L + i) }
        assertEquals(100, db.scheduleRunDao().count(id))
        val latest = repo.latestRun(id)!!
        assertEquals(1_102L, latest.startedAt)
        assertEquals(RunResult.RUNNING, latest.result)
        repo.updateRun(latest.copy(result = RunResult.FAILED, endedAt = 2_000, attemptsJson = """[{"at":1,"outcome":"NO_REPLY"}]"""))
        assertEquals(RunResult.FAILED, repo.latestRun(id)!!.result)
    }
}
