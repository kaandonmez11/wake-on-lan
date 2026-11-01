package com.kdgames.wake_on_lan.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionRowsTest {
    private val all = PermissionSnapshot(true, true, true, true, true, true)
    private fun rows(s: PermissionSnapshot, denied: Set<PermissionKind> = emptySet()) = PermissionRows.of(s, denied).associateBy { it.kind }

    @Test
    fun `hepsi verildi - bes satir, buton yok, kirmizi yok`() {
        val r = PermissionRows.of(all)
        assertEquals(PermissionKind.entries, r.map { it.kind })
        assertTrue(r.all { it.granted && it.action == null && !it.critical })
    }

    @Test
    fun `runtime izin yoksa Izin ver, reddedildiyse Duzelt`() {
        val s = all.copy(localNetwork = false, notifications = false)
        assertEquals(PermissionAction.REQUEST, rows(s)[PermissionKind.LOCAL_NETWORK]!!.action)
        assertEquals("İzin ver", rows(s)[PermissionKind.NOTIFICATIONS]!!.action!!.label)
        assertEquals(PermissionAction.SETTINGS, rows(s, setOf(PermissionKind.LOCAL_NETWORK))[PermissionKind.LOCAL_NETWORK]!!.action)
    }

    @Test
    fun `konum izni var ama servis kapali - Duzelt ve aciklama`() {
        val row = rows(all.copy(locationService = false))[PermissionKind.LOCATION]!!
        assertFalse(row.granted)
        assertEquals(PermissionAction.SETTINGS, row.action)
        assertTrue(row.subtitle.startsWith("Konum servisi kapalı"))
        assertEquals(PermissionAction.REQUEST, rows(all.copy(fineLocation = false))[PermissionKind.LOCATION]!!.action)
    }

    @Test
    fun `tam saatli alarm yoksa kirmizi, pil kisitliysa Duzelt`() {
        val r = rows(all.copy(exactAlarm = false, batteryUnrestricted = false))
        assertTrue(r[PermissionKind.EXACT_ALARM]!!.critical)
        assertEquals(PermissionAction.SETTINGS, r[PermissionKind.EXACT_ALARM]!!.action)
        assertEquals("Düzelt", r[PermissionKind.BATTERY]!!.action!!.label)
        assertFalse(r[PermissionKind.BATTERY]!!.critical)
        assertNull(r[PermissionKind.LOCAL_NETWORK]!!.action)
    }

    @Test
    fun `sayi girisi ayristirma`() {
        assertEquals(5, StepperInput.parse("5", 1..30))
        assertEquals(5, StepperInput.parse(" 5 ", 1..30))
        assertNull(StepperInput.parse("45", 1..30))
        assertNull(StepperInput.parse("", 1..30))
        assertNull(StepperInput.parse("0", 1..30))
    }

    @Test
    fun `lisans adlari`() {
        assertEquals("Material Symbols · Apache 2.0", licenseName("MaterialSymbols-Apache-2.0.txt"))
        assertEquals("Windows güç ikonları", licenseName("WINDOWS_ICONS.txt"))
    }
}
