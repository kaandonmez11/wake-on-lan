package com.kdgames.wake_on_lan.domain.schedule

import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Spec 9.4 zamanlayıcı mantığı (saf; saat dilimi parametre). `daysMask`: bit0 = Pzt … bit6 = Paz.
 * Atlanan çalışma (`skippedOccurrence`) alarmı yine kurar; tetiklenince `SKIPPED_BY_USER` yazılır.
 */
object ScheduleEngine {
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000
    const val EVERY_DAY = 0b1111111

    private val DAY_SHORT = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
    private val DAY_LONG = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    /** [after]'dan **sonraki** ilk çalışma (epoch ms); WIFI ya da gün seçilmemişse null. */
    fun nextOccurrence(s: Schedule, after: Long, zone: ZoneId): Long? {
        if (s.type != ScheduleType.TIME) return null
        val minute = s.minuteOfDay ?: return null
        if (s.daysMask and EVERY_DAY == 0) return null
        val start = Instant.ofEpochMilli(after).atZone(zone)
        val time = LocalTime.of(minute / 60, minute % 60)
        for (d in 0..7) {
            val date = start.toLocalDate().plusDays(d.toLong())
            if (s.daysMask and bit(date.dayOfWeek) == 0) continue
            val at = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (at > after) return at
        }
        return null
    }

    /** Atlama geçerli mi (geçmiş atlama kendiliğinden temizlenmiş sayılır). */
    fun isSkipped(s: Schedule, now: Long): Boolean = when (s.type) {
        ScheduleType.TIME -> s.skippedOccurrence?.let { it >= now } == true
        ScheduleType.WIFI_CONNECT -> s.skipNextEvent
    }

    /** Zamanı geçmiş atlamayı siler (spec: "atlama zamanı geçince kendiliğinden temizlenir"). */
    fun cleaned(s: Schedule, now: Long): Schedule =
        if (s.skippedOccurrence != null && s.skippedOccurrence < now) s.copy(skippedOccurrence = null) else s

    /** "Atla": TIME → sıradaki çalışma; WIFI → sonraki olay. */
    fun skip(s: Schedule, now: Long, zone: ZoneId): Schedule = when (s.type) {
        ScheduleType.TIME -> s.copy(skippedOccurrence = nextOccurrence(s, now, zone))
        ScheduleType.WIFI_CONNECT -> s.copy(skipNextEvent = true)
    }

    fun undoSkip(s: Schedule): Schedule = s.copy(skippedOccurrence = null, skipNextEvent = false)

    /** Anahtar: açma/kapama atlamayı sıfırlar. */
    fun setEnabled(s: Schedule, enabled: Boolean): Schedule =
        s.copy(enabled = enabled, skippedOccurrence = null, skipNextEvent = false)

    /** Kartta gösterilen "sonraki": atlanmışsa atlanandan sonraki çalışma. */
    fun upcoming(s: Schedule, now: Long, zone: ZoneId): Long? {
        val next = nextOccurrence(s, now, zone) ?: return null
        return if (isSkipped(s, now) && s.skippedOccurrence == next) nextOccurrence(s, next, zone) else next
    }

    // --- metinler (spec 9.4, 10.3) ---------------------------------------------------------

    /** "Hafta içi · 08:30", "Her gün · 07:00", "Pzt, Çar · 07:00", "⌂ Eve gelince · Ev-WiFi". */
    fun triggerLabel(s: Schedule): String = when (s.type) {
        ScheduleType.WIFI_CONNECT -> "⌂ Eve gelince · ${s.ssid.orEmpty()}"
        ScheduleType.TIME -> "${daysLabel(s.daysMask)} · ${time(s.minuteOfDay ?: 0)}"
    }

    fun daysLabel(mask: Int): String = when (mask and EVERY_DAY) {
        EVERY_DAY -> "Her gün"
        WEEKDAYS -> "Hafta içi"
        WEEKEND -> "Hafta sonu"
        0 -> "Gün yok"
        else -> (0..6).filter { mask and (1 shl it) != 0 }.joinToString(", ") { DAY_SHORT[it] }
    }

    fun time(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** "Bugün 08:30", "Yarın 08:30", "Cuma 08:30", 7 günden uzaksa "12.11 08:30". */
    fun whenLabel(at: Long, now: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return "${dayWord(at, now, zone)} ${"%02d:%02d".format(t.hour, t.minute)}"
    }

    private fun dayWord(at: Long, now: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), t.toLocalDate())
        return when {
            days == 0L -> "Bugün"
            days == 1L -> "Yarın"
            days in 2..6 -> DAY_LONG[t.dayOfWeek.value - 1]
            else -> "%02d.%02d".format(t.dayOfMonth, t.monthValue)
        }
    }

    private fun isNight(at: Long, zone: ZoneId): Boolean {
        val h = Instant.ofEpochMilli(at).atZone(zone).hour
        return h >= 20 || h < 5
    }

    /** Atla butonu: "Yarını atla" / "Bugünü atla" / "Bu geceyi atla" / "Sonrakini atla"; atlanmışken "Geri al". */
    fun skipButton(s: Schedule, now: Long, zone: ZoneId): String {
        if (isSkipped(s, now)) return "Geri al"
        if (s.type == ScheduleType.WIFI_CONNECT) return "Sonrakini atla"
        val next = nextOccurrence(s, now, zone) ?: return "Sonrakini atla"
        return when (dayWord(next, now, zone)) {
            "Bugün" -> if (isNight(next, zone)) "Bu geceyi atla" else "Bugünü atla"
            "Yarın" -> "Yarını atla"
            else -> "Sonrakini atla"
        }
    }

    /** Kart alt satırı: "Sonraki: Yarın 08:30" ya da amber "Yarın atlandı · sonraki Cuma 08:30". */
    fun nextLine(s: Schedule, now: Long, zone: ZoneId): String? {
        if (s.type == ScheduleType.WIFI_CONNECT) {
            return if (s.skipNextEvent) "Sonraki olay atlandı" else "Sonraki: ${s.ssid.orEmpty()} ağına bağlanınca"
        }
        val upcoming = upcoming(s, now, zone) ?: return null
        if (!isSkipped(s, now)) return "Sonraki: ${whenLabel(upcoming, now, zone)}"
        val skipped = s.skippedOccurrence!!
        val what = when (val w = dayWord(skipped, now, zone)) {
            "Bugün" -> if (isNight(skipped, zone)) "Bu gece" else "Bugün"
            else -> w
        }
        return "$what atlandı · sonraki ${whenLabel(upcoming, now, zone)}"
    }
}
