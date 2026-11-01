package com.kdgames.wake_on_lan.domain.schedule

import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Başarısız ekranı (spec 10.4) ve karttaki kırmızı satırın metinleri; saf, testli. */
object FailureLabels {
    private val MONTHS = listOf("Oca", "Şub", "Mar", "Nis", "May", "Haz", "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara")

    /** İlk ve son deneme arası, dakika. */
    fun spanMinutes(attempts: List<RunAttempt>): Int {
        if (attempts.size < 2) return 0
        return ((attempts.last().at - attempts.first().at) / 60_000.0).roundToInt()
    }

    /** Denemeler arası ortalama aralık, dakika (tek denemede null). */
    fun intervalMinutes(attempts: List<RunAttempt>): Int? {
        if (attempts.size < 2) return null
        return ((attempts.last().at - attempts.first().at) / 60_000.0 / (attempts.size - 1)).roundToInt().coerceAtLeast(1)
    }

    /** "6 deneme · 2 dk arayla". */
    fun header(attempts: List<RunAttempt>): String {
        val interval = intervalMinutes(attempts)
        return if (interval == null) "${attempts.size} deneme" else "${attempts.size} deneme · $interval dk arayla"
    }

    /** "10 dk sonra vazgeçildi". */
    fun gaveUp(attempts: List<RunAttempt>): String =
        if (attempts.size < 2) "Tekrar denenmedi" else "${spanMinutes(attempts)} dk sonra vazgeçildi"

    fun clock(at: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** "Bugün 18:42", "Dün 18:42", "12 Eki 18:42". */
    fun dayTime(at: Long, now: Long, zone: ZoneId): String {
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val word = when (ChronoUnit.DAYS.between(day, today)) {
            0L -> "Bugün"
            1L -> "Dün"
            else -> "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}"
        }
        return "$word ${clock(at, zone)}"
    }

    /** Tetikleyici kısa adı: "Eve gelince" ya da "Hafta içi · 08:30". */
    fun trigger(s: Schedule): String =
        if (s.type == ScheduleType.WIFI_CONNECT) "Eve gelince" else ScheduleEngine.triggerLabel(s)

    /** "Eve gelince · Bugün 18:42 – 18:52". */
    fun subtitle(s: Schedule, startedAt: Long, endedAt: Long?, now: Long, zone: ZoneId): String {
        val range = buildString {
            append(dayTime(startedAt, now, zone))
            if (endedAt != null && endedAt - startedAt >= 60_000) append(" – ").append(clock(endedAt, zone))
        }
        return "${trigger(s)} · $range"
    }

    /** Karttaki kırmızı satır: "Son çalışma başarısız · Bugün 18:42". */
    fun cardLine(startedAt: Long, now: Long, zone: ZoneId): String = "Son çalışma başarısız · ${dayTime(startedAt, now, zone)}"

    /** "Kontrol et" kartı (yalnız WAKE). */
    val CHECKS = listOf(
        "BIOS'ta Wake-on-LAN açık",
        "Ağ kartında \"Magic Packet ile uyandır\" açık",
        "Windows'ta Hızlı Başlat kapalı",
        "PC kabloyla (Ethernet) bağlı",
    )
}
