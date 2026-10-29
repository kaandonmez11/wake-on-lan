package com.kdgames.wake_on_lan.ui.automation

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kdgames.wake_on_lan.data.FakeKeyVault
import com.kdgames.wake_on_lan.data.db.AppDatabase
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.repo.DeviceInput
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.SaveResult
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import com.kdgames.wake_on_lan.domain.schedule.ScheduleEngine
import com.kdgames.wake_on_lan.domain.schedule.WallClock
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class AutomationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    // Çarşamba 12:00 → hafta içi 08:30'un sıradaki çalışması yarın.
    private val now = LocalDateTime.of(2026, 10, 28, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val device = Device(id = 1, name = "Ofis PC", ip = "192.168.1.26", mac = "D8:BB:C1:0A:2F:91", iconKey = "pc", colorIndex = 0)

    @Test
    fun `Yarini atla - amber satir ve Geri al, Geri al normale doner`() {
        compose.setContent {
            WolTheme {
                var s by remember {
                    mutableStateOf(Schedule(id = 1, deviceId = 1, action = PowerAction.WAKE, type = ScheduleType.TIME, daysMask = ScheduleEngine.WEEKDAYS, minuteOfDay = 510))
                }
                AutomationScreen(
                    state = AutomationUiState(cards = listOf(ScheduleLabels.card(s, device, now, zone)), hasDevices = true, loaded = true),
                    onSkip = { s = if (ScheduleEngine.isSkipped(s, now)) ScheduleEngine.undoSkip(s) else ScheduleEngine.skip(s, now, zone) },
                    onToggle = { _, on -> s = ScheduleEngine.setEnabled(s, on) },
                )
            }
        }
        compose.onNodeWithText("Sonraki: Yarın 08:30").assertExists()
        compose.onNode(hasText("Yarını atla") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Yarın atlandı · sonraki Cuma 08:30").assertExists()
        compose.onNode(hasText("Geri al") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sonraki: Yarın 08:30").assertExists()
        // Anahtar kapanınca alt satır kalkar.
        compose.onNodeWithContentDescription("Zamanlayıcı").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sonraki: Yarın 08:30").assertDoesNotExist()
    }

    @Test
    fun `bos sekme - baslik, aciklama, Zamanlayici ekle`() {
        compose.setContent { WolTheme { AutomationScreen(state = AutomationUiState(hasDevices = true, loaded = true)) } }
        compose.onNodeWithText("Otomasyon").assertExists()
        compose.onNodeWithText("Zamanlayıcılar telefondan çalışır, Wi-Fi şart").assertExists()
        compose.onNode(hasText("Zamanlayıcı ekle") and hasClickAction()).assertIsEnabled()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class ScheduleEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var vm: ScheduleEditorViewModel
    private var closed = 0
    private var deviceId = 0L

    @Before
    fun setUp() {
        val direct = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
        val devices = DeviceRepository(db, FakeKeyVault())
        schedules = ScheduleRepository(db)
        deviceId = runBlocking { (devices.add(DeviceInput("Ofis PC", "192.168.1.26", "D8:BB:C1:0A:2F:91")) as SaveResult.Saved).id }
        val now = LocalDateTime.of(2026, 10, 28, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        vm = ScheduleEditorViewModel(schedules, devices, WallClock { now })
    }

    @After
    fun tearDown() = db.close()

    private fun show() {
        compose.setContent {
            WolTheme {
                val state by vm.state.collectAsState()
                ScheduleEditorSheet(
                    open = true,
                    state = state,
                    actions = EditorActions(
                        onEdit = vm::edit,
                        onToggleDay = vm::toggleDay,
                        onShiftMinutes = vm::shiftMinutes,
                        onSave = { vm.save { closed++ } },
                        onDelete = { vm.delete { closed++ } },
                        onClose = {},
                    ),
                )
            }
        }
        compose.waitUntil(3_000) { vm.state.value.devices.isNotEmpty() }
        vm.open(EditorMode.Add)
        compose.waitForIdle()
    }

    @Test
    fun `gun yoksa kaydet pasif`() {
        show()
        compose.onNodeWithTag("editor_save").assertIsEnabled()
        listOf("Pzt", "Sal", "Çar", "Per", "Cum").forEach { compose.onNodeWithContentDescription(it).performClick() }
        compose.waitForIdle()
        compose.onNodeWithText(EditorIssue.NO_DAYS.hint).assertExists()
        compose.onNodeWithTag("editor_save").assertIsNotEnabled()
    }

    @Test
    fun `agent eslesmemis cihazda Kapat - kaydet pasif ve ipucu`() {
        show()
        compose.onNodeWithTag("action_SHUTDOWN").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(EditorIssue.NO_AGENT.hint).assertExists()
        compose.onNodeWithTag("editor_save").assertIsNotEnabled()
        // Aç'a dönünce kaydedilebilir.
        compose.onNodeWithTag("action_WAKE").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("editor_save").assertIsEnabled()
    }

    @Test
    fun `gecerli zamanlayici kaydedilir`() {
        show()
        compose.onNodeWithContentDescription("Saat artır").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil(3_000) { closed == 1 }
        val saved = runBlocking { schedules.getSchedules() }.single()
        assertEquals(deviceId, saved.deviceId)
        assertEquals(PowerAction.WAKE, saved.action)
        assertEquals(ScheduleEngine.WEEKDAYS, saved.daysMask)
        // 12:00 → sonraki 5 dk dilimi 12:05, +1 saat = 13:05.
        assertEquals(13 * 60 + 5, saved.minuteOfDay)
    }
}
