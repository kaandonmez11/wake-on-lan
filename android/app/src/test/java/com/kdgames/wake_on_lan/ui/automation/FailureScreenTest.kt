package com.kdgames.wake_on_lan.ui.automation

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kdgames.wake_on_lan.data.FakeKeyVault
import com.kdgames.wake_on_lan.data.db.AppDatabase
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.db.ScheduleRun
import com.kdgames.wake_on_lan.data.repo.DeviceInput
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.SaveResult
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RunResult
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import com.kdgames.wake_on_lan.domain.schedule.AttemptOutcome
import com.kdgames.wake_on_lan.domain.schedule.RunAttempt
import com.kdgames.wake_on_lan.domain.schedule.RunAttempts
import com.kdgames.wake_on_lan.domain.schedule.ScheduleEngine
import com.kdgames.wake_on_lan.domain.schedule.WallClock
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.net.WifiStateSource
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class FailureScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val t0 = LocalDateTime.of(2026, 10, 28, 18, 42).atZone(zone).toInstant().toEpochMilli()
    private val device = Device(id = 1, name = "Çalışma PC", ip = "192.168.1.26", mac = "D8:BB:C1:0A:2F:91", iconKey = "pc", colorIndex = 0, agentId = "a")
    private val wifi = Schedule(id = 2, deviceId = 1, action = PowerAction.WAKE, type = ScheduleType.WIFI_CONNECT, ssid = "Ev-WiFi")

    private fun run(attempts: Int, action: PowerAction = PowerAction.WAKE) = ScheduleRun(
        id = 9, scheduleId = 2, occurrence = t0, startedAt = t0, endedAt = t0 + (attempts - 1) * 120_000L, result = RunResult.FAILED,
        attemptsJson = RunAttempts.encode(
            (0 until attempts).map {
                if (action == PowerAction.WAKE) RunAttempt(t0 + it * 120_000L, AttemptOutcome.NO_REPLY, "Paket gitti · cevap yok")
                else RunAttempt(t0 + it * 120_000L, AttemptOutcome.AGENT_NO_REPLY, "Agent cevap vermedi")
            },
        ),
    )

    @Test
    fun `WAKE - baslik, ozet, 6 deneme satiri ve Kontrol et karti`() {
        var retried: FailureUi? = null
        val ui = FailureUi.of(run(6), wifi, device, t0 + 3_600_000, zone)
        compose.setContent { WolTheme { FailureScreen(ui, onBack = {}, onRetry = { retried = it }) } }
        compose.onNodeWithText("Çalışma PC uyanmadı").assertExists()
        compose.onNodeWithText("Eve gelince · Bugün 18:42 – 18:52").assertExists()
        compose.onNodeWithText("6 deneme · 2 dk arayla").assertExists()
        compose.onNodeWithText("10 dk sonra vazgeçildi").assertExists()
        assertEquals(6, compose.onAllNodesWithTag("failure_attempt", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("18:52").assertExists()
        compose.onNodeWithTag("failure_checks").assertExists()
        compose.onNodeWithText("BIOS'ta Wake-on-LAN açık").assertExists()
        compose.onNodeWithTag("failure_retry").performClick()
        assertEquals(1L, retried?.deviceId)
    }

    @Test
    fun `agent aksiyonunda Kontrol et karti yok, baslik aksiyona gore`() {
        val ui = FailureUi.of(run(3, PowerAction.SHUTDOWN), wifi.copy(action = PowerAction.SHUTDOWN), device, t0 + 3_600_000, zone)
        compose.setContent { WolTheme { FailureScreen(ui, onBack = {}, onRetry = {}) } }
        compose.onNodeWithText("Çalışma PC kapanmadı").assertExists()
        compose.onNodeWithText("3 deneme · 2 dk arayla").assertExists()
        compose.onNodeWithTag("failure_checks").assertDoesNotExist()
    }

    @Test
    fun `kartta basarisiz satiri - Detay acar, basarili calismada kalkar`() {
        val time = Schedule(id = 2, deviceId = 1, action = PowerAction.WAKE, type = ScheduleType.TIME, daysMask = ScheduleEngine.WEEKDAYS, minuteOfDay = 510)
        var last by mutableStateOf(run(6))
        var opened = 0L
        compose.setContent {
            WolTheme {
                AutomationScreen(
                    state = AutomationUiState(cards = listOf(ScheduleLabels.card(time, device, t0 + 3_600_000, zone, last)), hasDevices = true, loaded = true),
                    onOpenFailure = { opened = it },
                )
            }
        }
        compose.onNodeWithText("Son çalışma başarısız · Bugün 18:42").assertExists()
        compose.onNodeWithTag("failure_row").performClick()
        assertEquals(9L, opened)
        last = last.copy(id = 10, result = RunResult.SUCCESS)
        compose.waitForIdle()
        compose.onNodeWithTag("failure_row").assertDoesNotExist()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class ScheduleEditorWifiTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var vm: ScheduleEditorViewModel
    private var closed = 0

    @Before
    fun setUp() {
        val direct = java.util.concurrent.Executor { it.run() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app as Context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
        val devices = DeviceRepository(db, FakeKeyVault())
        schedules = ScheduleRepository(db)
        runBlocking { (devices.add(DeviceInput("Ofis PC", "192.168.1.26", "D8:BB:C1:0A:2F:91")) as SaveResult.Saved).id }
        val now = LocalDateTime.of(2026, 10, 28, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        vm = ScheduleEditorViewModel(schedules, devices, WallClock { now }, object : WifiStateSource {
            override val state = MutableStateFlow(WifiState(connected = true, ssid = "Ev-WiFi"))
        })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `Eve gelince - SSID varsayilan su anki ag, gun-saat gizlenir, WIFI_CONNECT kaydedilir`() {
        compose.setContent {
            WolTheme {
                val state by vm.state.collectAsState()
                ScheduleEditorSheet(
                    open = true,
                    state = state,
                    actions = EditorActions(vm::edit, vm::toggleDay, vm::shiftMinutes, onSave = { vm.save { closed++ } }, onDelete = {}, onClose = {}),
                )
            }
        }
        compose.waitUntil(3_000) { vm.state.value.devices.isNotEmpty() }
        vm.open(EditorMode.Add)
        compose.waitForIdle()
        compose.onNodeWithTag("trigger_wifi").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ev-WiFi ağına bağlanınca çalışır").assertExists()
        compose.onNodeWithText("Günler").assertDoesNotExist()
        compose.onNodeWithTag("editor_save").assertIsEnabled().performClick()
        compose.waitUntil(3_000) { closed == 1 }
        val saved = runBlocking { schedules.getSchedules() }.single()
        assertEquals(ScheduleType.WIFI_CONNECT, saved.type)
        assertEquals("Ev-WiFi", saved.ssid)
        assertEquals(null, saved.minuteOfDay)
        assertEquals("⌂ Eve gelince · Ev-WiFi", ScheduleEngine.triggerLabel(saved))
    }
}
