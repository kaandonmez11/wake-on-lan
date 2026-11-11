package com.kdgames.wake_on_lan.ui.gates

import android.Manifest
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.net.WifiStateSource
import com.kdgames.wake_on_lan.ui.settings.PermissionSnapshot
import com.kdgames.wake_on_lan.ui.settings.PermissionSource
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class GateTest {
    @get:Rule
    val compose = createComposeRule()

    private val wifi = MutableStateFlow(WifiState(connected = true, ssid = "Ev-WiFi"))
    private val done = MutableStateFlow(true)
    private val mobile = MutableStateFlow(MobileInfo(onCellular = true, operator = "Turkcell"))
    private var snapshot = PermissionSnapshot(localNetwork = true)
    private lateinit var vm: GateViewModel
    private var grace = 0L

    private fun show() {
        vm = GateViewModel(
            object : WifiStateSource { override val state = wifi },
            PermissionSource { snapshot },
            object : OnboardingFlag {
                override val done = this@GateTest.done
                override suspend fun complete() { this@GateTest.done.value = true }
            },
            MobileInfoSource { mobile },
            graceMs = grace,
        )
        compose.setContent {
            WolTheme {
                // Sonsuz animasyonlar (halkalar, canlı arka plan) testte boşta durumunu engeller.
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    val ui by vm.state.collectAsState()
                    GateHost(ui, GateActions(onRefresh = vm::refreshPermissions, onOnboardingDone = vm::completeOnboarding))
                }
            }
        }
    }

    private fun gate() = vm.state.value.gate

    @Test
    fun `karar onceligi - onboarding, izin, Wi-Fi`() {
        assertEquals(Gate.NONE, Gates.decide(null, false, true))
        assertEquals(Gate.ONBOARDING, Gates.decide(false, false, true))
        assertEquals(Gate.NO_PERMISSION, Gates.decide(true, false, true))
        assertEquals(Gate.NO_WIFI, Gates.decide(true, true, true))
        assertEquals(Gate.NONE, Gates.decide(true, true, false))
        assertEquals("Şu an mobil veri · Turkcell", MobileInfo(true, "Turkcell").label)
        assertEquals("Şu an mobil veri", MobileInfo(true, null).label)
        assertEquals("Bağlantı yok", MobileInfo(false, "Turkcell").label)
    }

    @Test
    fun `Wi-Fi kesilince NoWifi gorunur, gelince kaybolur`() {
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("gate").assertDoesNotExist()
        wifi.value = WifiState.Disconnected
        compose.waitUntil(5_000) { gate() == Gate.NO_WIFI }
        compose.waitForIdle()
        compose.onNodeWithTag("gate_wifi").assertExists()
        compose.onNodeWithText("Wi-Fi'a bağlı değilsin").assertExists()
        compose.onNodeWithText("Şu an mobil veri · Turkcell").assertExists()
        // Mobil veri de kapanır.
        mobile.value = MobileInfo()
        compose.waitForIdle()
        compose.onNodeWithText("Bağlantı yok").assertExists()
        wifi.value = WifiState(connected = true)
        compose.waitUntil(5_000) { gate() == Gate.NONE }
        compose.waitForIdle()
        compose.onNodeWithTag("gate").assertDoesNotExist()
    }

    @Test
    fun `kisa kopukluk kapi acmaz`() {
        grace = 60_000
        show()
        wifi.value = WifiState.Disconnected
        compose.waitForIdle()
        assertEquals(Gate.NONE, gate())
        wifi.value = WifiState(connected = true)
        compose.waitForIdle()
        assertEquals(Gate.NONE, gate())
    }

    @Test
    fun `izin yokken NoPermission oncelikli, verilince Wi-Fi kapisina gecer`() {
        snapshot = PermissionSnapshot(localNetwork = false)
        wifi.value = WifiState.Disconnected
        show()
        compose.waitUntil(5_000) { gate() == Gate.NO_PERMISSION }
        compose.waitForIdle()
        compose.onNodeWithTag("gate_permission").assertExists()
        compose.onNodeWithTag("gate_wifi").assertDoesNotExist()
        snapshot = PermissionSnapshot(localNetwork = true)
        vm.refreshPermissions()
        compose.waitUntil(5_000) { gate() == Gate.NO_WIFI }
        compose.waitForIdle()
        compose.onNodeWithTag("gate_wifi").assertExists()
    }

    @Test
    fun `onboarding yalniz ilk acilista - uc sayfa, sonra bir daha cikmaz`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        done.value = false
        show()
        compose.waitUntil(5_000) { gate() == Gate.ONBOARDING }
        compose.waitForIdle()
        compose.onNodeWithText("Yerel ağ erişimi").assertExists()
        // İzin zaten verilmiş → "Devam".
        compose.onNodeWithText("Devam").assertExists()
        compose.onNodeWithTag("onboarding_primary").performClick()
        compose.waitForIdle()
        // Konum atlanabilir.
        compose.onNodeWithTag("onboarding_skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding_skip").performClick()
        compose.waitUntil(5_000) { gate() == Gate.NONE }
        assertEquals(true, done.value)
        compose.waitForIdle()
        compose.onNodeWithTag("gate").assertDoesNotExist()
    }
}
