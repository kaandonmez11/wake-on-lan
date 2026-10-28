package com.kdgames.wake_on_lan.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.kdgames.wake_on_lan.data.db.AppDatabase
import com.kdgames.wake_on_lan.data.db.DeviceStateCache
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.repo.DeviceError
import com.kdgames.wake_on_lan.data.repo.DeviceInput
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.DeviceWarning
import com.kdgames.wake_on_lan.data.repo.SaveResult
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.data.repo.ScheduleSaveResult
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.CachedStateKind
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var vault: FakeKeyVault
    private lateinit var repo: DeviceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        vault = FakeKeyVault()
        repo = DeviceRepository(db, vault)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun add(name: String, ip: String = "192.168.1.10", mac: String? = null): Long =
        (repo.add(DeviceInput(name, ip, mac)) as SaveResult.Saved).id

    @Test
    fun ekleListeleEklemeSirasinda() = runBlocking {
        add("Ofis PC", mac = "aa-bb-cc-dd-ee-01")
        add("NAS", ip = "192.168.1.20")
        add("Salon TV", ip = "192.168.1.30")
        val devices = repo.getDevices()
        assertEquals(listOf("Ofis PC", "NAS", "Salon TV"), devices.map { it.name })
        assertEquals(listOf(0, 1, 2), devices.map { it.sortOrder })
        // MAC kaydedilirken büyük harf + ':' (spec 15).
        assertEquals("AA:BB:CC:DD:EE:01", devices[0].mac)
        assertNull(devices[1].mac)
    }

    @Test
    fun adTrimlenirVeBenzersizdir() = runBlocking {
        add("  Ofis PC  ")
        assertEquals("Ofis PC", repo.getDevices().single().name)
        val dup = repo.add(DeviceInput("ofis pc", "192.168.1.11", null))
        assertEquals(SaveResult.Invalid(setOf(DeviceError.NAME_DUPLICATE)), dup)
        // Türkçe büyük/küçük harf: "İ" ↔ "i".
        add("İstasyon", ip = "192.168.1.12")
        assertTrue(repo.validate(DeviceInput("istasyon", "192.168.1.13", null)).errors.contains(DeviceError.NAME_DUPLICATE))
    }

    @Test
    fun ayniMacIkinciKezEklenemez() = runBlocking {
        add("A", mac = "AA:BB:CC:DD:EE:FF")
        val result = repo.add(DeviceInput("B", "192.168.1.11", "aa-bb-cc-dd-ee-ff"))
        assertEquals(SaveResult.Invalid(setOf(DeviceError.MAC_DUPLICATE)), result)
        assertEquals(DeviceError.MAC_DUPLICATE.message, "Bu MAC zaten kayıtlı")
    }

    @Test
    fun macsizBirdenFazlaCihazOlabilir() = runBlocking {
        add("A")
        add("B", ip = "192.168.1.11")
        assertEquals(2, repo.getDevices().size)
    }

    @Test
    fun dogrulamaKurallari() = runBlocking {
        fun errs(input: DeviceInput) = runBlocking { repo.validate(input).errors }
        assertEquals(setOf(DeviceError.NAME_INVALID), errs(DeviceInput("   ", "192.168.1.5", null)))
        assertEquals(setOf(DeviceError.NAME_INVALID), errs(DeviceInput("x".repeat(33), "192.168.1.5", null)))
        assertTrue(errs(DeviceInput("x".repeat(32), "192.168.1.5", null)).isEmpty())
        assertEquals(setOf(DeviceError.IP_INVALID), errs(DeviceInput("A", "192.168.1.300", null)))
        assertEquals(setOf(DeviceError.IP_INVALID), errs(DeviceInput("A", "192.168.1", null)))
        assertEquals(setOf(DeviceError.MAC_INVALID), errs(DeviceInput("A", "192.168.1.5", "AA:BB:CC")))
        assertTrue(errs(DeviceInput("A", "192.168.1.5", "")).isEmpty())
    }

    @Test
    fun altAgDisiIpYalnizUyari() = runBlocking {
        val v = repo.validate(DeviceInput("A", "10.0.0.5", null), phoneIp = "192.168.1.40", phonePrefix = 24)
        assertTrue(v.isValid)
        assertEquals(setOf(DeviceWarning.IP_OUTSIDE_SUBNET), v.warnings)
        val ok = repo.validate(DeviceInput("A", "192.168.1.5", null), phoneIp = "192.168.1.40", phonePrefix = 24)
        assertTrue(ok.warnings.isEmpty())
    }

    @Test
    fun duzenlemeKendiAdVeMaciniCakismaSaymaz() = runBlocking {
        val id = add("A", mac = "AA:BB:CC:DD:EE:FF")
        val result = repo.update(id, DeviceInput("A", "192.168.1.99", "AA:BB:CC:DD:EE:FF", colorIndex = 3))
        assertEquals(SaveResult.Saved(id), result)
        val d = repo.getDevice(id)!!
        assertEquals("192.168.1.99", d.ip)
        assertEquals(3, d.colorIndex)
        assertEquals(0, d.sortOrder)
    }

    @Test
    fun silmeCascadeVeAnahtar() = runBlocking {
        val id = add("A")
        val alias = vault.newAlias().also { vault.store(it, ByteArray(32) { 1 }) }
        repo.updateDevice(repo.getDevice(id)!!.copy(agentId = "agent", agentKeyAlias = alias))
        repo.recordWakeAttempt(id, 1_000, true, 3, AttemptSource.MANUAL)
        repo.cacheState(DeviceStateCache(id, CachedStateKind.ON, at = 1_000))
        val schedules = ScheduleRepository(db)
        val sid = (schedules.save(Schedule(deviceId = id, action = PowerAction.WAKE, type = ScheduleType.TIME, daysMask = 1, minuteOfDay = 510)) as ScheduleSaveResult.Saved).id
        schedules.startRun(sid, 1_000, 1_000)

        repo.delete(id)

        assertTrue(repo.getDevices().isEmpty())
        assertEquals(0, db.wakeAttemptDao().count(id))
        assertTrue(repo.cachedStates().isEmpty())
        assertTrue(schedules.getSchedules().isEmpty())
        assertEquals(0, db.scheduleRunDao().count(sid))
        assertFalse(alias in vault.aliases)
    }

    @Test
    fun gecmisSon12EskidenYeniye() = runBlocking {
        val id = add("A")
        // 15 deneme: i % 4 == 0 başarısız.
        repeat(15) { i -> repo.recordWakeAttempt(id, 1_000L + i, success = i % 4 != 0, durationSec = 5, source = AttemptSource.MANUAL) }
        val h = repo.getHistory(id)
        assertEquals(12, h.total)
        assertEquals((3 until 15).map { 1_000L + it }, h.attempts.map { it.startedAt })
        val expectedSuccess = (3 until 15).count { it % 4 != 0 }
        assertEquals(expectedSuccess, h.successes)
        assertEquals("$expectedSuccess/12", h.label)
        assertEquals(1_014L, h.lastSuccessAt)
    }

    @Test
    fun denemeYoksaYeni() = runBlocking {
        val id = add("A")
        val h = repo.getHistory(id)
        assertTrue(h.isNew)
        assertEquals("yeni", h.label)
        assertNull(h.lastSuccessAt)
    }

    @Test
    fun cihazBasina100DenemeSaklanir() = runBlocking {
        val id = add("A")
        val other = add("B", ip = "192.168.1.11")
        repo.recordWakeAttempt(other, 1, true, 1, AttemptSource.TILE)
        repeat(105) { i -> repo.recordWakeAttempt(id, 10_000L + i, true, 1, AttemptSource.SCHEDULE) }
        assertEquals(100, db.wakeAttemptDao().count(id))
        assertEquals(10_104L, db.wakeAttemptDao().getRecent(id, 1).single().startedAt)
        assertEquals(10_005L, db.wakeAttemptDao().getRecent(id, 100).last().startedAt)
        assertEquals(1, db.wakeAttemptDao().count(other))
    }

    @Test
    fun gecmisAkisiYeniDenemedeGuncellenir() = runBlocking {
        val id = add("A")
        repo.observeHistory(id).test {
            assertEquals("yeni", awaitItem().label)
            repo.recordWakeAttempt(id, 1, false, null, AttemptSource.MANUAL)
            assertEquals("0/1", awaitItem().label)
            repo.recordWakeAttempt(id, 2, true, 4, AttemptSource.MANUAL)
            assertEquals("1/2", awaitItem().label)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun durumOnbellegiUpsert() = runBlocking {
        val id = add("A")
        repo.cacheState(DeviceStateCache(id, CachedStateKind.ON, uptimeSec = 60, at = 1))
        repo.cacheState(DeviceStateCache(id, CachedStateKind.OFF, lastPowerAction = PowerAction.SLEEP, at = 2))
        val cached = repo.cachedStates().single()
        assertEquals(CachedStateKind.OFF, cached.kind)
        assertEquals(PowerAction.SLEEP, cached.lastPowerAction)
    }
}
