package com.kdgames.wake_on_lan.domain

import com.kdgames.wake_on_lan.data.db.DeviceStateCache
import com.kdgames.wake_on_lan.domain.model.CachedStateKind
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.domain.model.Verdict
import com.kdgames.wake_on_lan.domain.model.WakeStep
import com.kdgames.wake_on_lan.domain.status.StateLabels
import com.kdgames.wake_on_lan.domain.status.StatusRules
import com.kdgames.wake_on_lan.domain.status.Tracker
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StatusEngineTest {

    @Nested
    inner class Rules {
        private val mac = "D8:BB:C1:0A:2F:91"

        @Test
        fun `S1 tek basina dogrulanmis acik`() {
            val v = StatusRules.decide(Sig.agent(ms = 12), mac, agentConfigured = true)
            assertEquals(Verdict.Positive(verified = true, agentReachable = true, pingMs = 12, uptimeSec = 3600), v)
        }

        @Test
        fun `S2 MAC eslesiyorsa dogrulanmis, agentli cihazda S1 yoksa agentReachable false`() {
            val v = StatusRules.decide(Sig.netBios(mac.lowercase(), ms = 7), mac, agentConfigured = true) as Verdict.Positive
            assertTrue(v.verified)
            assertFalse(v.agentReachable)
            assertEquals(7, v.pingMs)
        }

        @Test
        fun `K8 S2 farkli MAC acik saymaz ve S3 u de gecersiz kilar`() {
            val v = StatusRules.decide(Sig.netBios("00:11:22:33:44:55"), mac, agentConfigured = false)
            assertEquals(Verdict.Negative(macMismatch = true), v)
        }

        @Test
        fun `S1 imzali yanit K8 den once gelir`() {
            val s = Sig.netBios("00:11:22:33:44:55").copy(s1 = RoundSignals.AgentSignal(true, 9, 10, "on"))
            assertInstanceOf(Verdict.Positive::class.java, StatusRules.decide(s, mac, true))
        }

        @Test
        fun `S3 ya da S4 dogrulanmamis acik, en kucuk ms`() {
            val s = RoundSignals(null, null, RoundSignals.TcpSignal(true, false, 9), RoundSignals.IcmpSignal(true, 4))
            assertEquals(Verdict.Positive(false, true, 4, null), StatusRules.decide(s, mac, agentConfigured = false))
            assertEquals(Verdict.Positive(false, false, 4, null), StatusRules.decide(Sig.agentSilentTcp(), mac, true))
        }

        @Test
        fun `MAC i bilinmeyen cihazda S2 dogrulanmamis pozitif`() {
            val v = StatusRules.decide(Sig.netBios(mac), null, agentConfigured = false) as Verdict.Positive
            assertFalse(v.verified)
        }

        @Test
        fun `hicbiri negatif`() {
            assertEquals(Verdict.Negative(), StatusRules.decide(Sig.none(), mac, true))
            assertEquals(Verdict.Negative(), StatusRules.decide(RoundSignals.None, mac, false))
        }

        @Test
        fun `tracker tek negatif kapali yapmaz iki yapar, gecislerde uc`() {
            val on = Tracker.initial(cachedOn = false, lastAction = null, uptimeSec = null, tried = true)
                .next(Verdict.Positive(true, true, 3, null), now = 0)
            val one = on.next(Verdict.Negative(), 3_000)
            assertInstanceOf(DeviceState.On::class.java, one.base)
            assertInstanceOf(DeviceState.Off::class.java, one.next(Verdict.Negative(), 6_000).base)

            val t2 = on.next(Verdict.Negative(), 1_000, 3).next(Verdict.Negative(), 2_000, 3)
            assertInstanceOf(DeviceState.On::class.java, t2.base)
            assertInstanceOf(DeviceState.Off::class.java, t2.next(Verdict.Negative(), 3_000, 3).base)
        }

        @Test
        fun `K9 lastAction metinleri ve On gorulunce temizlenir`() {
            assertEquals("Uyuyor", StateLabels.of(DeviceState.Off(true, lastAction = PowerAction.SLEEP)))
            assertEquals("Hazırda", StateLabels.of(DeviceState.Off(true, lastAction = PowerAction.HIBERNATE)))
            assertEquals("Kapalı", StateLabels.of(DeviceState.Off(true, lastAction = PowerAction.SHUTDOWN)))
            assertEquals("Kapalı", StateLabels.of(DeviceState.Off(false)))
            assertEquals("Açık · 3 ms", StateLabels.of(DeviceState.On(3, true, true)))
            assertEquals("Uyanıyor · Paket · 4 sn", StateLabels.of(DeviceState.Waking(WakeStep.PACKET, 4)))
            assertEquals("Kapanıyor…", StateLabels.of(DeviceState.PowerTransition(PowerAction.SHUTDOWN, 2)))

            val sleeping = Tracker(DeviceState.Off(true, lastAction = PowerAction.SLEEP), decidedAt = 0)
            val woke = sleeping.next(Verdict.Positive(true, true, 3, null), 1_000)
            val offAgain = woke.next(Verdict.Negative(), 2_000).next(Verdict.Negative(), 3_000)
            assertEquals(null, (offAgain.base as DeviceState.Off).lastAction)
        }

        @Test
        fun `15 sn sonra stale`() {
            val t = Tracker(DeviceState.On(3, true, true), decidedAt = 1_000)
            assertFalse(t.view(16_000).stale)
            assertTrue(t.view(16_001).stale)
            assertTrue(Tracker.initial(true, null, null, false).view(0).stale)
        }
    }

    @Nested
    inner class Engine {
        @Test
        fun `on planda her 3 sn tur, iki negatif turdan sonra kapali`() = runTest {
            val h = EngineHarness(this, foreground = true) { _, now -> if (now < 3_000) Sig.agent() else Sig.none() }
            assertEquals(DeviceState.On(12, true, true, uptimeSec = 3600), h.state())
            advanceTimeBy(3_001)
            assertInstanceOf(DeviceState.On::class.java, h.state(), "tek negatif tur kapalı yapmamalı")
            advanceTimeBy(3_000)
            assertEquals(DeviceState.Off(false), h.state())
            assertEquals(3, h.probes)
        }

        @Test
        fun `titreme yok - aralikli tek kayiplar durumu degistirmez`() = runTest {
            var n = 0
            val h = EngineHarness(this, foreground = true) { _, _ -> if (n++ % 2 == 1) Sig.none() else Sig.tcp() }
            val seen = mutableSetOf<String>()
            repeat(40) {
                seen += h.state()!!::class.simpleName!!
                advanceTimeBy(3_000)
            }
            assertEquals(setOf("On"), seen)
        }

        @Test
        fun `arka planda tur yok, on plana donunce hemen tur`() = runTest {
            val h = EngineHarness(this, foreground = false) { _, _ -> Sig.tcp() }
            advanceTimeBy(30_000)
            assertEquals(0, h.probes)
            h.fg.isForeground.value = true
            runCurrent()
            assertEquals(1, h.probes)
            assertInstanceOf(DeviceState.On::class.java, h.state())
        }

        @Test
        fun `acilista onbellekteki durum stale, ilk turda kesinlesir (K7)`() = runTest {
            val cache = listOf(DeviceStateCache(1, CachedStateKind.OFF, PowerAction.HIBERNATE, null, at = 0))
            val h = EngineHarness(this, cache = cache, foreground = false) { _, _ -> Sig.tcp() }
            assertEquals(DeviceState.Off(false, stale = true, lastAction = PowerAction.HIBERNATE), h.state())
            assertEquals("Hazırda · kontrol ediliyor", StateLabels.of(h.state()))
            h.engine.round(1)
            assertEquals(DeviceState.On(3, false, false), h.state())
        }

        @Test
        fun `Wi-Fi yokken tur sayilmaz, 15 sn sonra stale`() = runTest {
            val h = EngineHarness(this, foreground = true) { _, _ -> Sig.tcp() }
            h.wifi = false
            advanceTimeBy(12_100)
            assertEquals(DeviceState.On(3, false, false), h.state())
            advanceTimeBy(6_000)
            assertEquals(DeviceState.On(3, false, false, stale = true), h.state())
        }

        /** S06 cihaz testi: Wi-Fi kapanırken soket bağlama hatası durum döngüsünü öldürüp uygulamayı düşürüyordu. */
        @Test
        fun `beklenmeyen hata tur sayilmaz, dongu olmez`() = runTest {
            var fail = false
            val h = EngineHarness(this, foreground = true) { _, _ ->
                if (fail) throw IllegalStateException("Binding socket to network failed") else Sig.tcp()
            }
            fail = true
            advanceTimeBy(9_100)
            assertInstanceOf(DeviceState.On::class.java, h.state(), "hata negatif tur sayılmamalı")
            fail = false
            h.signals = { _, _ -> Sig.none() }
            advanceTimeBy(6_100)
            assertEquals(DeviceState.Off(false), h.state(), "döngü hatadan sonra da çalışmaya devam etmeli")
        }

        @Test
        fun `kesin karar degisince onbellege yazilir`() = runTest {
            val h = EngineHarness(this, foreground = true) { _, now -> if (now < 6_000) Sig.agent() else Sig.none() }
            advanceTimeBy(13_000)
            assertEquals(listOf(CachedStateKind.ON, CachedStateKind.OFF), h.store.written.map { it.kind })
            assertEquals(3600L, h.store.written.first().uptimeSec)
        }

        @Test
        fun `katman varken dongu o cihaza tur atmaz, gosterilen durum katmandir`() = runTest {
            val h = EngineHarness(this, foreground = true) { _, _ -> Sig.tcp() }
            val probes = h.probes
            h.engine.setOverlay(1, DeviceState.Waking(WakeStep.REPLY, 2))
            advanceTimeBy(9_000)
            assertEquals(probes, h.probes)
            assertEquals(DeviceState.Waking(WakeStep.REPLY, 2), h.state())
            h.engine.setOverlay(1, null)
            assertInstanceOf(DeviceState.On::class.java, h.state())
        }

        @Test
        fun `cihaz eklenince ve silinince harita guncellenir`() = runTest {
            val h = EngineHarness(this) { _, _ -> Sig.tcp() }
            h.store.flow.value = listOf(pc(1), pc(2, mac = "00:11:22:33:44:55"))
            runCurrent()
            assertEquals(setOf(1L, 2L), h.engine.states.value.keys)
            h.store.flow.value = listOf(pc(2, mac = "00:11:22:33:44:55"))
            runCurrent()
            assertEquals(setOf(2L), h.engine.states.value.keys)
        }

        @Test
        fun `yeni cihaz tried false, deneme sonrasi true`() = runTest {
            val h = EngineHarness(this)
            assertEquals(DeviceState.Off(tried = false, stale = true), h.state())
            h.engine.markTried(1)
            assertEquals(DeviceState.Off(tried = true, stale = true), h.state())
        }
    }
}
