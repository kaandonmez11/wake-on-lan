package com.kdgames.wake_on_lan.tile

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.TileMode
import com.kdgames.wake_on_lan.domain.model.WakeStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileModelTest {
    private val pc = Device(id = 4, name = "Ofis PC", ip = "192.168.1.26", mac = "D8:BB:C1:0A:2F:91", iconKey = "pc", colorIndex = 0, agentId = "a", agentKeyAlias = "k")
    private val on = DeviceState.On(3, verified = true, agentReachable = true, uptimeSec = 2 * 3_600 + 120)
    private fun label(state: DeviceState?, wifi: Boolean = true, permission: Boolean = true, confirm: Boolean = false) =
        TileLabels.of(0, pc, state, wifi, permission, confirm)

    @Test
    fun `binder - slot cihaza eslenir, bos ve silinmis slot atanmamis`() {
        val devices = listOf(pc, pc.copy(id = 9, name = "NAS", mac = "AA:BB:CC:DD:EE:FF"))
        assertEquals(9L, TileBinder.deviceFor(1, listOf(4, 9, null), devices)?.id)
        assertNull(TileBinder.deviceFor(2, listOf(4, 9, null), devices))
        assertNull(TileBinder.deviceFor(0, listOf(77, null, null), devices))
        assertNull(TileBinder.deviceFor(5, listOf(4), devices))
    }

    @Test
    fun `atanmamis slot - WoL slot N, Cihaz sec`() {
        assertEquals(TileContent("WoL slot 2", "Cihaz seç", TileVisual.INACTIVE, null), TileLabels.of(1, null, null, wifi = true, permission = true))
    }

    @Test
    fun `tum durumlar - alt yazi ve state`() {
        assertEquals(TileContent("Ofis PC", "Açık · 2 sa", TileVisual.ACTIVE, "pc"), label(on))
        assertEquals("Açık", label(on.copy(uptimeSec = null)).subtitle)
        assertEquals("Açık · 5 dk", label(on.copy(uptimeSec = 310)).subtitle)
        assertEquals("Açık · 3 gün", label(on.copy(uptimeSec = 3 * 86_400L + 5)).subtitle)
        assertEquals("Kapalı" to TileVisual.INACTIVE, label(DeviceState.Off(tried = true)).let { it.subtitle to it.visual })
        assertEquals("Uyuyor", label(DeviceState.Off(true, lastAction = PowerAction.SLEEP)).subtitle)
        assertEquals("Hazırda", label(DeviceState.Off(true, lastAction = PowerAction.HIBERNATE)).subtitle)
        assertEquals("Uyanıyor 8 sn" to TileVisual.INACTIVE, label(DeviceState.Waking(WakeStep.REPLY, 8)).let { it.subtitle to it.visual })
        assertEquals("Uyanıyor…", label(DeviceState.Waking(WakeStep.PACKET, 0)).subtitle)
        assertEquals("Kapanıyor…", label(DeviceState.PowerTransition(PowerAction.SHUTDOWN, 2)).subtitle)
        assertEquals("Wi-Fi yok" to TileVisual.UNAVAILABLE, label(on, wifi = false).let { it.subtitle to it.visual })
        assertEquals("İzin yok" to TileVisual.UNAVAILABLE, label(on, wifi = false, permission = false).let { it.subtitle to it.visual })
        assertEquals("Kontrol ediliyor…", label(null).subtitle)
        assertEquals(TileLabels.CONFIRM, label(on, confirm = true).subtitle)
        // Onay yazısı yalnız açık cihazda.
        assertEquals("Kapalı", label(DeviceState.Off(true), confirm = true).subtitle)
    }

    @Test
    fun `dokunma karari - kapali uyandirir, acik moda gore, K12`() {
        fun click(state: DeviceState?, mode: TileMode = TileMode.A, d: Device? = pc, wifi: Boolean = true, permission: Boolean = true) =
            TileClicks.decide(d, state, wifi, permission, mode)
        assertEquals(TileClick.OPEN_SLOT_SETTINGS, click(null, d = null))
        assertEquals(TileClick.WAKE, click(DeviceState.Off(true)))
        assertEquals(TileClick.NOTHING, click(DeviceState.Off(true), d = pc.copy(mac = null)))
        assertEquals(TileClick.POWER_OVERLAY, click(on, TileMode.A))
        assertEquals(TileClick.POWER_DIALOG, click(on, TileMode.B))
        assertEquals(TileClick.DOUBLE_TAP, click(on, TileMode.C))
        assertEquals(TileClick.NOTHING, click(DeviceState.Waking(WakeStep.REPLY, 3)))
        assertEquals(TileClick.NOTHING, click(DeviceState.PowerTransition(PowerAction.SHUTDOWN, 1)))
        assertEquals(TileClick.NOTHING, click(on, wifi = false))
        assertEquals(TileClick.NOTHING, click(DeviceState.Off(true), permission = false))
        assertEquals(TileClick.NOTHING, click(null))
    }

    @Test
    fun `mod C - 3 sn pencere`() {
        val tap = DoubleTap()
        assertFalse(tap.pending(0))
        assertFalse(tap.tap(1_000)) // ilk dokunuş: pencere açılır
        assertTrue(tap.pending(3_999))
        assertFalse(tap.pending(4_000)) // 3 sn doldu
        assertFalse(tap.tap(4_500)) // yeniden ilk dokunuş sayılır
        assertTrue(tap.tap(6_000)) // pencere içinde ikinci dokunuş → Kapat
        assertFalse(tap.pending(6_001)) // pencere kapandı
        tap.tap(7_000)
        tap.reset()
        assertFalse(tap.pending(7_100))
    }
}
