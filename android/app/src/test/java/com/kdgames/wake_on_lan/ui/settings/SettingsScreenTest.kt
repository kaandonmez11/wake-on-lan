package com.kdgames.wake_on_lan.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.prefs.Settings
import com.kdgames.wake_on_lan.data.repo.RetryPolicy
import com.kdgames.wake_on_lan.domain.model.ShutdownMode
import com.kdgames.wake_on_lan.domain.model.TileMode
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val pc = Device(id = 1, name = "Ofis PC", ip = "192.168.1.26", mac = "D8:BB:C1:0A:2F:91", iconKey = "pc", colorIndex = 0, agentId = "a")
    private var settings by mutableStateOf(Settings())
    private var devices by mutableStateOf(listOf(pc))
    private var snapshot by mutableStateOf(PermissionSnapshot(true, true, true, true, true, true))
    private val shutdown = mutableListOf<Pair<Long, ShutdownMode>>()
    private val slots = mutableListOf<Pair<Int, Long?>>()
    private var mode: TileMode? = null

    /** ViewModel ile aynı kural: RetryPolicy.validate. */
    private fun show() {
        compose.setContent {
            WolTheme {
                SettingsScreen(
                    state = SettingsUi(settings, devices, PermissionRows.of(snapshot), snapshot, loaded = true),
                    actions = SettingsActions(
                        onInterval = { v -> (RetryPolicy.validate(v, settings.giveUpMin) == null).also { if (it) settings = settings.copy(retryIntervalMin = v) } },
                        onGiveUp = { v -> (RetryPolicy.validate(settings.retryIntervalMin, v) == null).also { if (it) settings = settings.copy(giveUpMin = v) } },
                        onShutdownMode = { id, m -> shutdown += id to m; devices = devices.map { if (it.id == id) it.copy(shutdownMode = m) else it } },
                        onTileSlot = { s, id -> slots += s to id },
                        onTileMode = { mode = it },
                    ),
                )
            }
        }
    }

    @Test
    fun `araliga disi deger eski degere doner`() {
        show()
        compose.onNodeWithTag("interval").performClick()
        compose.onNodeWithTag("interval").performTextReplacement("45")
        compose.onNodeWithTag("interval").performImeAction()
        compose.waitForIdle()
        assertEquals(2, settings.retryIntervalMin)
        compose.onNodeWithTag("interval").assertTextEquals("2")
        // Boş giriş de geri döner.
        compose.onNodeWithTag("interval").performClick()
        compose.onNodeWithTag("interval").performTextReplacement("")
        compose.onNodeWithTag("interval").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("interval").assertTextEquals("2")
        // Geçerli değer yazılır.
        compose.onNodeWithTag("interval").performClick()
        compose.onNodeWithTag("interval").performTextReplacement("5")
        compose.onNodeWithTag("interval").performImeAction()
        compose.waitForIdle()
        assertEquals(5, settings.retryIntervalMin)
    }

    @Test
    fun `vazgecme suresi araliktan kucuk olamaz`() {
        settings = Settings(retryIntervalMin = 5, giveUpMin = 10)
        show()
        compose.onNodeWithTag("giveUp").performClick()
        compose.onNodeWithTag("giveUp").performTextReplacement("3")
        compose.onNodeWithTag("giveUp").performImeAction()
        compose.waitForIdle()
        assertEquals(10, settings.giveUpMin)
        compose.onNodeWithTag("giveUp").assertTextEquals("10")
        // Aralık "+" ile vazgeçmeyi geçemez.
        settings = Settings(retryIntervalMin = 10, giveUpMin = 10)
        compose.waitForIdle()
        compose.onNodeWithTag("interval_plus").performClick()
        compose.waitForIdle()
        assertEquals(10, settings.retryIntervalMin)
    }

    @Test
    fun `stepper sinirlarda durur`() {
        settings = Settings(retryIntervalMin = 1, giveUpMin = 120)
        show()
        compose.onNodeWithTag("interval_minus").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("giveUp_plus").assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, settings.retryIntervalMin)
        assertEquals(120, settings.giveUpMin)
        compose.onNodeWithTag("interval_plus").performClick()
        compose.waitForIdle()
        assertEquals(2, settings.retryIntervalMin)
        compose.onNodeWithText("2 dk arayla 61 deneme · 120 dk sonra vazgeçilir").assertExists()
    }

    @Test
    fun `segment secimleri kaydedilir`() {
        show()
        compose.onNodeWithTag("shutdown_1_1").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(1L to ShutdownMode.GRACEFUL), shutdown)
        compose.onNodeWithTag("tilemode_2").performScrollTo().performClick()
        assertEquals(TileMode.C, mode)
        compose.onNodeWithTag("slot_0_1").performScrollTo().performClick()
        assertEquals(listOf(0 to 1L), slots)
    }

    @Test
    fun `izin satirlari durumu gosterir ve canli guncellenir`() {
        snapshot = snapshot.copy(exactAlarm = false, fineLocation = false)
        show()
        compose.onNodeWithTag("perm_action_EXACT_ALARM").assertExists()
        compose.onNodeWithText("Kapalı · zamanlayıcılar gecikebilir").assertExists()
        compose.onNodeWithTag("perm_action_LOCATION").assertExists()
        compose.onNodeWithTag("perm_granted_BATTERY").assertExists()
        snapshot = snapshot.copy(exactAlarm = true, fineLocation = true)
        compose.waitForIdle()
        compose.onNodeWithTag("perm_granted_EXACT_ALARM").assertExists()
        compose.onNodeWithTag("perm_granted_LOCATION").assertExists()
    }

    @Test
    fun `bosluga dokununca odak kalkar ve gecersiz deger geri doner`() {
        show()
        compose.onNodeWithTag("interval").performClick()
        compose.onNodeWithTag("interval").performTextReplacement("45")
        // Başlığa (tıklanamaz boş alan) dokun.
        compose.onNodeWithText("Ayarlar").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("interval").assertTextEquals("2")
        assertEquals(2, settings.retryIntervalMin)
    }

    @Test
    fun `ipuclari ve uyarilar, imza baglantisi`() {
        show()
        assertEquals(TIPS.count { it.warning }, compose.onAllNodesWithTag("warning_row").fetchSemanticsNodes().size)
        assertEquals(TIPS.count { !it.warning }, compose.onAllNodesWithTag("tip_row").fetchSemanticsNodes().size)
        compose.onNodeWithText("BIOS'ta Wake-on-LAN açık olmalı").performScrollTo().assertExists()
        compose.onNodeWithTag("credit").performScrollTo().assertTextEquals("KAAN DONMEZ")
        compose.onNodeWithText("DESIGNED AND DEVELOPED BY").assertExists()
        compose.onNodeWithText("Titreşim").assertDoesNotExist()
        assertEquals("https://www.linkedin.com/in/akaandonmez/", CREDIT_URL)
    }
}
