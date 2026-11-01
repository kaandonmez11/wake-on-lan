package com.kdgames.wake_on_lan.domain.schedule

import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import com.kdgames.wake_on_lan.net.WifiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WifiTriggerRulesTest {
    private val home = Schedule(id = 3, deviceId = 1, action = PowerAction.WAKE, type = ScheduleType.WIFI_CONNECT, ssid = "Ev-WiFi")
    private val d = WifiTriggerRules.Decision.entries.associateBy { it }

    @Test
    fun `SSID eslesirse tetiklenir, baska agda tetiklenmez`() {
        assertEquals(d[WifiTriggerRules.Decision.FIRE], WifiTriggerRules.decide(home, "Ev-WiFi", 1_000_000, null))
        assertEquals(WifiTriggerRules.Decision.OTHER_NETWORK, WifiTriggerRules.decide(home, "Kafe", 1_000_000, null))
        assertEquals(WifiTriggerRules.Decision.OTHER_NETWORK, WifiTriggerRules.decide(home, null, 1_000_000, null))
        // Büyük/küçük harf SSID'de anlamlı.
        assertEquals(WifiTriggerRules.Decision.OTHER_NETWORK, WifiTriggerRules.decide(home, "ev-wifi", 1_000_000, null))
    }

    @Test
    fun `10 dk icinde tekrar tetiklenmez, 10 dk sonra tetiklenir`() {
        val now = 50_000_000L
        assertEquals(WifiTriggerRules.Decision.LOCKED, WifiTriggerRules.decide(home, "Ev-WiFi", now, now - 9 * 60_000L))
        assertEquals(WifiTriggerRules.Decision.LOCKED, WifiTriggerRules.decide(home, "Ev-WiFi", now, now - 1_000))
        assertEquals(WifiTriggerRules.Decision.FIRE, WifiTriggerRules.decide(home, "Ev-WiFi", now, now - 10 * 60_000L))
    }

    @Test
    fun `kapali ve saat zamanlayicisi tetiklenmez`() {
        assertEquals(WifiTriggerRules.Decision.DISABLED, WifiTriggerRules.decide(home.copy(enabled = false), "Ev-WiFi", 0, null))
        assertEquals(WifiTriggerRules.Decision.NOT_WIFI, WifiTriggerRules.decide(home.copy(type = ScheduleType.TIME), "Ev-WiFi", 0, null))
    }

    @Test
    fun `SSID tirnakli gelir, gizliyse ag imzasindan bulunur`() {
        val fp = WifiState.fingerprint("192.168.1.1", "192.168.1.1", 24)!!
        val known = mapOf("Ev-WiFi" to fp, "Ofis" to "gw=10.0.0.1;dhcp=10.0.0.1;p=16")
        assertEquals(WifiTriggerRules.Resolved("Ev-WiFi", WifiTriggerRules.Source.SSID), WifiTriggerRules.resolve("\"Ev-WiFi\"", null, known))
        assertEquals(WifiTriggerRules.Resolved("Ev-WiFi", WifiTriggerRules.Source.FINGERPRINT), WifiTriggerRules.resolve(WifiState.UNKNOWN_SSID, fp, known))
        assertEquals(WifiTriggerRules.Source.NONE, WifiTriggerRules.resolve(null, "gw=172.16.0.1;dhcp=;p=24", known).source)
        assertNull(WifiState.fingerprint(null, "192.168.1.1", 24))
    }
}

class FailureLabelsTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val t0 = LocalDateTime.of(2026, 10, 28, 18, 42).atZone(zone).toInstant().toEpochMilli()
    private val six = (0 until 6).map { RunAttempt(t0 + it * 120_000L, AttemptOutcome.NO_REPLY, "Paket gitti · cevap yok") }

    @Test
    fun `deneme ozetleri`() {
        assertEquals("6 deneme · 2 dk arayla", FailureLabels.header(six))
        assertEquals("10 dk sonra vazgeçildi", FailureLabels.gaveUp(six))
        assertEquals("6 deneme · 10 dk sonra vazgeçildi", ScheduleTexts.failureSummary(six))
        assertEquals("1 deneme", FailureLabels.header(six.take(1)))
        assertEquals("Tekrar denenmedi", FailureLabels.gaveUp(six.take(1)))
    }

    @Test
    fun `basliklar aksiyona gore`() {
        assertEquals("Çalışma PC uyanmadı", ScheduleTexts.failureTitle("Çalışma PC", PowerAction.WAKE))
        assertEquals("Çalışma PC kapanmadı", ScheduleTexts.failureTitle("Çalışma PC", PowerAction.SHUTDOWN))
        assertEquals("Çalışma PC uyutulamadı", ScheduleTexts.failureTitle("Çalışma PC", PowerAction.SLEEP))
    }

    @Test
    fun `alt satir ve kart satiri`() {
        val now = t0 + 3 * 3_600_000L
        val wifi = Schedule(deviceId = 1, action = PowerAction.WAKE, type = ScheduleType.WIFI_CONNECT, ssid = "Ev-WiFi")
        assertEquals("Eve gelince · Bugün 18:42 – 18:52", FailureLabels.subtitle(wifi, t0, t0 + 600_000, now, zone))
        val time = Schedule(deviceId = 1, action = PowerAction.WAKE, type = ScheduleType.TIME, daysMask = 0x1F, minuteOfDay = 18 * 60 + 42)
        assertEquals("Hafta içi · 18:42 · Bugün 18:42 – 18:52", FailureLabels.subtitle(time, t0, t0 + 600_000, now, zone))
        assertEquals("Son çalışma başarısız · Bugün 18:42", FailureLabels.cardLine(t0, now, zone))
        assertEquals("Son çalışma başarısız · Dün 18:42", FailureLabels.cardLine(t0, now + 24 * 3_600_000L, zone))
        assertEquals("Son çalışma başarısız · 28 Eki 18:42", FailureLabels.cardLine(t0, now + 3 * 24 * 3_600_000L, zone))
    }
}
