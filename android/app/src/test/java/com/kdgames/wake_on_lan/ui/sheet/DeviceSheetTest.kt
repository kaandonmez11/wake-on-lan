package com.kdgames.wake_on_lan.ui.sheet

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kdgames.wake_on_lan.data.FakeKeyVault
import com.kdgames.wake_on_lan.data.db.AppDatabase
import com.kdgames.wake_on_lan.data.repo.DeviceInput
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.SaveResult
import com.kdgames.wake_on_lan.domain.agent.AgentIssue
import com.kdgames.wake_on_lan.domain.agent.AgentLinkTrigger
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.net.WifiStateSource
import com.kdgames.wake_on_lan.net.scan.AgentFound
import com.kdgames.wake_on_lan.net.scan.DeviceScanner
import com.kdgames.wake_on_lan.net.scan.ScanEntry
import com.kdgames.wake_on_lan.net.scan.ScanSnapshot
import com.kdgames.wake_on_lan.ui.devices.DeviceUi
import com.kdgames.wake_on_lan.ui.devices.DevicesScreen
import com.kdgames.wake_on_lan.ui.devices.DevicesUiState
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val wifiState = WifiState(connected = true, ssid = "Ev", phoneIp = "192.168.1.88", prefixLength = 24, broadcast = "192.168.1.255")

private val pcEntry = ScanEntry(
    key = "192.168.1.26", ip = "192.168.1.26", mac = "D8:BB:C1:0A:2F:91", name = "KAAN-PC", iconKey = "pc",
    agent = AgentFound("agent-1", "KAAN-PC", paired = false, hibernate = true, sleepHibernates = true),
)
private val routerEntry = ScanEntry(key = "192.168.1.1", ip = "192.168.1.1", mac = null, name = "router", iconKey = "pc", agent = null, added = true)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class DeviceSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repo: DeviceRepository
    private lateinit var vm: DeviceSheetViewModel
    private var links = 0
    private val released = mutableListOf<Long>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric'te Room'un işlem iş parçacığından ana looper'a dönüşü compose saatini beklemiyor;
        // sorgular ve işlemler test iş parçacığında çalışır.
        val direct = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        repo = DeviceRepository(db, FakeKeyVault())
        val scanner = DeviceScanner { _, _ -> flowOf(ScanSnapshot(listOf(pcEntry, routerEntry), running = false)) }
        val wifi = object : WifiStateSource {
            override val state: StateFlow<WifiState> = MutableStateFlow(wifiState)
        }
        val linker = object : AgentLinkTrigger {
            override fun linkNow() {
                links++
            }
            override fun release(device: com.kdgames.wake_on_lan.data.db.Device) {
                released += device.id
            }
            override val issues: StateFlow<Map<Long, AgentIssue>> = MutableStateFlow(emptyMap())
        }
        vm = DeviceSheetViewModel(repo, scanner, wifi, linker).apply { scanStartDelayMs = 0 }
    }

    @After
    fun tearDown() = db.close()

    /** MainActivity'deki bağlamanın aynısı: Cihazlar ekranı + panel; kayıt cihazı seçer. */
    private fun show(mode: SheetMode) {
        vm.open(mode)
        compose.setContent {
            WolTheme {
                var open by androidx.compose.runtime.remember { mutableStateOf(true) }
                var selectedId by androidx.compose.runtime.remember { mutableStateOf<Long?>(null) }
                val devices by repo.observeDevices().collectAsState(emptyList())
                val state by vm.state.collectAsState()
                val onResult = { r: SheetResult ->
                    open = false
                    vm.close()
                    if (r is SheetResult.Saved) selectedId = r.id
                    selected = selectedId
                }
                Box(Modifier.fillMaxSize()) {
                    DevicesScreen(
                        state = DevicesUiState(wifi = wifiState, devices = devices.map { DeviceUi(it, null) }, selectedId = selectedId),
                        onSelect = { selectedId = it },
                        onWake = {},
                        onPower = { _, _ -> },
                        onAdd = {},
                        onOpenSettings = {},
                    )
                    DeviceSheet(
                        open = open,
                        state = state,
                        actions = SheetActions(
                            onEdit = vm::edit,
                            onPick = vm::pick,
                            onRescan = vm::rescan,
                            onSave = { vm.save(onResult) },
                            onDelete = { vm.delete(onResult) },
                            onClose = { open = false; vm.close() },
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private var selected: Long? = null

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun waitHint(text: String) = compose.waitUntil(3_000) { vm.state.value.hint == text }

    @Test
    fun `gecersiz IP ile kaydet pasif`() {
        show(SheetMode.Add)
        compose.onNodeWithTag("sheet_save").assertIsNotEnabled()
        type("field_name", "Ofis PC")
        type("field_ip", "192.168.1.300")
        waitHint(SheetUiState.HINT_REQUIRED)
        compose.onNodeWithTag("sheet_save").assertIsNotEnabled()
        compose.onNodeWithText(SheetUiState.HINT_REQUIRED).assertExists()
    }

    @Test
    fun `gecerliyle cihaz listede belirir ve secilir`() {
        show(SheetMode.Add)
        type("field_name", "Yeni PC")
        type("field_ip", "192.168.1.40")
        waitHint(SheetUiState.HINT_NO_MAC)
        compose.onNodeWithTag("sheet_save").assertIsEnabled().performClick()
        compose.waitUntil(3_000) { selected != null }
        val saved = runBlocking { repo.getDevices() }.single()
        assertEquals("Yeni PC", saved.name)
        assertEquals(saved.id, selected)
        assertEquals(1, links)
        // Hero (seçili) ve kartta görünür.
        compose.waitUntil(3_000) { compose.onAllNodes(hasText("Yeni PC")).fetchSemanticsNodes().size >= 2 }
    }

    @Test
    fun `taranan satira dokununca form dolar, agent ipucu`() {
        show(SheetMode.Add)
        compose.onNodeWithText("Ağda bulunanlar").assertExists()
        compose.onNode(hasText("KAAN-PC") and hasClickAction()).performClick()
        compose.waitForIdle()
        val form = vm.state.value.form
        assertEquals("KAAN-PC", form.name)
        assertEquals("192.168.1.26", form.ip)
        assertEquals("D8:BB:C1:0A:2F:91", form.mac)
        waitHint(SheetUiState.HINT_AGENT)
        // Ekli satır dokunulamaz.
        compose.onNode(hasText("router") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Ekli").assertExists()
    }

    @Test
    fun `ayni MAC hatasi`() {
        runBlocking { repo.add(DeviceInput("Eski PC", "192.168.1.30", "D8:BB:C1:0A:2F:91")) }
        show(SheetMode.Add)
        type("field_name", "Yeni PC")
        type("field_ip", "192.168.1.40")
        type("field_mac", "d8-bb-c1-0a-2f-91")
        waitHint("Bu MAC zaten kayıtlı")
        compose.onNodeWithTag("sheet_save").assertIsNotEnabled()
    }

    @Test
    fun `duzenleme modunda Agda bulunanlar yok ve form dolu`() {
        val id = runBlocking { (repo.add(DeviceInput("Ofis PC", "192.168.1.26", null, "nas", 3)) as SaveResult.Saved).id }
        show(SheetMode.Edit(id))
        compose.waitUntil(3_000) { vm.state.value.form.name == "Ofis PC" }
        compose.onNodeWithText("Ağda bulunanlar").assertDoesNotExist()
        compose.onNodeWithText("Cihazı düzenle").assertExists()
        assertEquals("nas", vm.state.value.form.iconKey)
        type("field_name", "Salon PC")
        compose.waitUntil(3_000) { vm.state.value.canSave }
        compose.onNodeWithTag("sheet_save").performClick()
        compose.waitUntil(3_000) { selected != null }
        assertEquals("Salon PC", runBlocking { repo.getDevice(id) }?.name)
    }

    @Test
    fun `sil onayi`() {
        val id = runBlocking { (repo.add(DeviceInput("Ofis PC", "192.168.1.26", null)) as SaveResult.Saved).id }
        show(SheetMode.Edit(id))
        compose.waitUntil(3_000) { vm.state.value.form.name == "Ofis PC" }
        compose.onNodeWithText("Cihaz silinsin mi?").assertDoesNotExist()
        compose.onNode(hasText("Sil") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cihaz silinsin mi?").assertExists()
        // Vazgeç: cihaz kalır.
        compose.onNodeWithTag("confirm_Vazgeç").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cihaz silinsin mi?").assertDoesNotExist()
        assertTrue(runBlocking { repo.getDevice(id) } != null)
        compose.onNode(hasText("Sil") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("confirm_Sil").performClick()
        compose.waitUntil(3_000) { runBlocking { repo.getDevice(id) } == null }
        assertNull(runBlocking { repo.getDevice(id) })
        // K25: agent eşleşmesi kaldırılmak üzere AgentLinker'a verildi.
        assertEquals(listOf(id), released)
    }
}
