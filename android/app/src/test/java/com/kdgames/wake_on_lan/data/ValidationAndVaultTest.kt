package com.kdgames.wake_on_lan.data

import com.kdgames.wake_on_lan.data.db.Converters
import com.kdgames.wake_on_lan.data.repo.RetryError
import com.kdgames.wake_on_lan.data.repo.RetryPolicy
import com.kdgames.wake_on_lan.data.repo.ScheduleError
import com.kdgames.wake_on_lan.data.repo.Validation
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationAndVaultTest {

    @Test
    fun `alt ag kontrolu`() {
        assertTrue(Validation.isInSubnet("192.168.1.200", "192.168.1.4", 24))
        assertFalse(Validation.isInSubnet("192.168.2.4", "192.168.1.4", 24))
        assertTrue(Validation.isInSubnet("10.0.6.1", "10.0.5.17", 22))
        // Telefon ağı bilinmiyorsa uyarı yok.
        assertTrue(Validation.isInSubnet("10.0.0.1", null, null))
    }

    @Test
    fun `mac ayristirma`() {
        assertEquals(Validation.MacResult.Empty, Validation.parseMac(null))
        assertEquals(Validation.MacResult.Empty, Validation.parseMac("  "))
        assertEquals(Validation.MacResult.Valid("AA:BB:CC:DD:EE:FF"), Validation.parseMac("aa-bb-cc-dd-ee-ff"))
        assertEquals(Validation.MacResult.Invalid, Validation.parseMac("AABBCCDDEEFF"))
    }

    @Test
    fun `zamanlayici dogrulamasi`() {
        assertEquals(listOf(ScheduleError.NO_DAYS), Validation.validateSchedule(ScheduleType.TIME, 0, 510, null))
        // bit7 ve üstü gün sayılmaz.
        assertEquals(listOf(ScheduleError.NO_DAYS), Validation.validateSchedule(ScheduleType.TIME, 0x80, 510, null))
        assertTrue(Validation.validateSchedule(ScheduleType.TIME, 0b1000000, 0, null).isEmpty())
        assertTrue(Validation.validateSchedule(ScheduleType.TIME, 1, 1439, null).isEmpty())
        assertEquals(listOf(ScheduleError.NO_SSID), Validation.validateSchedule(ScheduleType.WIFI_CONNECT, 0, null, null))
    }

    @Test
    fun `retry politikasi`() {
        assertNull(RetryPolicy.validate(2, 10))
        assertEquals(RetryError.GIVE_UP_LESS_THAN_INTERVAL, RetryPolicy.validate(30, 29))
    }

    @Test
    fun `port listesi donusumu`() {
        val c = Converters()
        assertEquals("3389,445,135", c.portsToString(listOf(3389, 445, 135)))
        assertEquals(listOf(3389, 445, 135), c.stringToPorts("3389,445,135"))
        assertEquals(emptyList<Int>(), c.stringToPorts(""))
    }

    @Test
    fun `key vault sozlesmesi`() {
        val vault = FakeKeyVault()
        val a = vault.newAlias()
        val b = vault.newAlias()
        assertFalse(a == b)
        val key = ByteArray(32) { it.toByte() }
        vault.store(a, key)
        key[0] = 99 // dışarıdaki değişiklik saklananı etkilemez
        assertArrayEquals(ByteArray(32) { it.toByte() }, vault.load(a))
        assertNull(vault.load(b))
        vault.delete(a)
        assertNull(vault.load(a))
    }
}
