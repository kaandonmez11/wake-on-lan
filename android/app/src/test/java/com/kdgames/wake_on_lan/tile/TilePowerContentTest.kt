package com.kdgames.wake_on_lan.tile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class TilePowerContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val pc = Device(id = 4, name = "Ofis PC", ip = "192.168.1.26", mac = "D8:BB:C1:0A:2F:91", iconKey = "pc", colorIndex = 0, agentId = "a", agentKeyAlias = "k", hibernateAvailable = true)
    private val on = DeviceState.On(3, verified = true, agentReachable = true)
    private val actions = mutableListOf<PowerAction>()
    private var cancelled = 0

    private fun show(state: DeviceState? = on, device: Device = pc) {
        compose.mainClock.autoAdvance = false
        compose.setContent { WolTheme { TilePowerContent(device, state, onAction = { actions += it }, onCancel = { cancelled++ }) } }
        compose.mainClock.advanceTimeBy(500)
    }

    private fun hold(tag: String, ms: Long) {
        compose.onNodeWithTag(tag).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(ms)
        compose.onNodeWithTag(tag).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(600)
    }

    @Test
    fun `orb 1,5 sn basili tutulunca Kapat, erken birakinca iptal`() {
        show()
        compose.onNodeWithText("Kapatmak için basılı tut").assertExists()
        hold("tile_orb", 800)
        assertEquals(emptyList<PowerAction>(), actions)
        hold("tile_orb", 1_700)
        assertEquals(listOf(PowerAction.SHUTDOWN), actions)
    }

    @Test
    fun `mini buton basili tutulunca kendi komutu, Vazgec kapatir`() {
        show()
        hold("tile_RESTART", 1_700)
        assertEquals(listOf(PowerAction.RESTART), actions)
        compose.onNodeWithTag("tile_cancel").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, cancelled)
    }

    @Test
    fun `K12 - PC acik degilse komut gitmez`() {
        show(state = DeviceState.Off(tried = true))
        compose.onNodeWithText("PC açık değil").assertExists()
        hold("tile_orb", 1_700)
        hold("tile_LOCK", 1_700)
        assertEquals(emptyList<PowerAction>(), actions)
    }
}
