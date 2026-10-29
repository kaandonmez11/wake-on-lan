package com.kdgames.wake_on_lan.domain.schedule

import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleEngineTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val wed = LocalDate.of(2026, 10, 28).also { check(it.dayOfWeek == DayOfWeek.WEDNESDAY) }

    private fun at(date: LocalDate, h: Int, m: Int) = LocalDateTime.of(date, java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
    private fun s(mask: Int = ScheduleEngine.WEEKDAYS, minute: Int = 8 * 60 + 30) =
        Schedule(id = 1, deviceId = 1, action = PowerAction.WAKE, type = ScheduleType.TIME, daysMask = mask, minuteOfDay = minute)

    @Test
    fun `hafta ici 0830, carsamba 2349 - persembe 0830`() {
        assertEquals(at(wed.plusDays(1), 8, 30), ScheduleEngine.nextOccurrence(s(), at(wed, 23, 49), zone))
    }

    @Test
    fun `atla - sonraki cuma 0830, amber satir ve Geri al`() {
        val now = at(wed, 23, 49)
        val skipped = ScheduleEngine.skip(s(), now, zone)
        assertEquals(at(wed.plusDays(1), 8, 30), skipped.skippedOccurrence)
        assertTrue(ScheduleEngine.isSkipped(skipped, now))
        assertEquals(at(wed.plusDays(2), 8, 30), ScheduleEngine.upcoming(skipped, now, zone))
        assertEquals("Yarın atlandı · sonraki Cuma 08:30", ScheduleEngine.nextLine(skipped, now, zone))
        assertEquals("Geri al", ScheduleEngine.skipButton(skipped, now, zone))
        val undone = ScheduleEngine.undoSkip(skipped)
        assertEquals("Sonraki: Yarın 08:30", ScheduleEngine.nextLine(undone, now, zone))
        assertEquals("Yarını atla", ScheduleEngine.skipButton(undone, now, zone))
    }

    @Test
    fun `atlama gecince temizlenir`() {
        val skipped = ScheduleEngine.skip(s(), at(wed, 23, 49), zone)
        val later = at(wed.plusDays(1), 9, 0)
        assertFalse(ScheduleEngine.isSkipped(skipped, later))
        assertNull(ScheduleEngine.cleaned(skipped, later).skippedOccurrence)
        // Daha geçmemişse dokunulmaz.
        assertEquals(skipped, ScheduleEngine.cleaned(skipped, at(wed.plusDays(1), 8, 0)))
    }

    @Test
    fun `anahtar kapaninca atlama sifirlanir`() {
        val skipped = ScheduleEngine.skip(s(), at(wed, 12, 0), zone)
        val off = ScheduleEngine.setEnabled(skipped, false)
        assertFalse(off.enabled)
        assertNull(off.skippedOccurrence)
        assertNull(ScheduleEngine.setEnabled(skipped, true).skippedOccurrence)
    }

    @Test
    fun `hafta sonu ve gece yarisi gecisleri`() {
        val fri = wed.plusDays(2)
        // Hafta içi zamanlayıcı cuma 09:00'dan sonra pazartesiye atlar.
        assertEquals(at(fri.plusDays(3), 8, 30), ScheduleEngine.nextOccurrence(s(), at(fri, 9, 0), zone))
        // Hafta sonu zamanlayıcı.
        assertEquals(at(fri.plusDays(1), 8, 30), ScheduleEngine.nextOccurrence(s(ScheduleEngine.WEEKEND), at(wed, 9, 0), zone))
        // 00:05 her gün: 23:59'da ertesi gün 00:05.
        assertEquals(at(wed.plusDays(1), 0, 5), ScheduleEngine.nextOccurrence(s(ScheduleEngine.EVERY_DAY, 5), at(wed, 23, 59), zone))
        // Tam tetik anında sıradaki ertesi gün (aynı anı tekrar vermez).
        assertEquals(at(wed.plusDays(1), 8, 30), ScheduleEngine.nextOccurrence(s(ScheduleEngine.EVERY_DAY), at(wed, 8, 30), zone))
        // Gün yoksa ya da WIFI ise null.
        assertNull(ScheduleEngine.nextOccurrence(s(0), at(wed, 9, 0), zone))
        assertNull(ScheduleEngine.nextOccurrence(s().copy(type = ScheduleType.WIFI_CONNECT, ssid = "Ev"), at(wed, 9, 0), zone))
    }

    @Test
    fun `tum buton metinleri`() {
        assertEquals("Bugünü atla", ScheduleEngine.skipButton(s(), at(wed, 7, 0), zone))
        assertEquals("Yarını atla", ScheduleEngine.skipButton(s(), at(wed, 9, 0), zone))
        assertEquals("Bu geceyi atla", ScheduleEngine.skipButton(s(ScheduleEngine.EVERY_DAY, 23 * 60), at(wed, 21, 0), zone))
        assertEquals("Sonrakini atla", ScheduleEngine.skipButton(s(ScheduleEngine.WEEKEND), at(wed, 9, 0), zone))
        val wifi = s().copy(type = ScheduleType.WIFI_CONNECT, ssid = "Ev-WiFi")
        assertEquals("Sonrakini atla", ScheduleEngine.skipButton(wifi, at(wed, 9, 0), zone))
        assertEquals("Geri al", ScheduleEngine.skipButton(ScheduleEngine.skip(wifi, 0, zone), at(wed, 9, 0), zone))
        assertEquals("Bu gece atlandı · sonraki Yarın 23:00",
            ScheduleEngine.nextLine(ScheduleEngine.skip(s(ScheduleEngine.EVERY_DAY, 23 * 60), at(wed, 21, 0), zone), at(wed, 21, 0), zone))
    }

    @Test
    fun `tetikleyici ve zaman etiketleri`() {
        assertEquals("Hafta içi · 08:30", ScheduleEngine.triggerLabel(s()))
        assertEquals("Her gün · 07:00", ScheduleEngine.triggerLabel(s(ScheduleEngine.EVERY_DAY, 7 * 60)))
        assertEquals("Pzt, Çar · 07:00", ScheduleEngine.triggerLabel(s(0b0000101, 7 * 60)))
        assertEquals("Hafta sonu · 10:15", ScheduleEngine.triggerLabel(s(ScheduleEngine.WEEKEND, 10 * 60 + 15)))
        assertEquals("⌂ Eve gelince · Ev-WiFi", ScheduleEngine.triggerLabel(s().copy(type = ScheduleType.WIFI_CONNECT, ssid = "Ev-WiFi")))
        val now = at(wed, 9, 0)
        assertEquals("Bugün 18:00", ScheduleEngine.whenLabel(at(wed, 18, 0), now, zone))
        assertEquals("Cumartesi 10:00", ScheduleEngine.whenLabel(at(wed.plusDays(3), 10, 0), now, zone))
    }

    @Test
    fun `saat dilimi degisince sonraki ayni yerel saat`() {
        val now = at(wed, 9, 0)
        val london = ZoneId.of("Europe/London")
        val next = ScheduleEngine.nextOccurrence(s(), now, london)!!
        val local = java.time.Instant.ofEpochMilli(next).atZone(london)
        assertEquals(8, local.hour)
        assertEquals(30, local.minute)
    }
}
