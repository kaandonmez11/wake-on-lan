package com.kdgames.wake_on_lan.domain

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.domain.model.ShutdownMode
import com.kdgames.wake_on_lan.domain.model.WakeStep
import com.kdgames.wake_on_lan.domain.power.PowerCoordinator
import com.kdgames.wake_on_lan.domain.status.StateLabels
import com.kdgames.wake_on_lan.domain.wake.WakeCoordinator
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.agent.AgentCommand
import com.kdgames.wake_on_lan.net.agent.AgentResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private class WakeSetup(test: TestScope, device: Device = pc(), signals: (Device, Long) -> RoundSignals) {
    val h = EngineHarness(test, listOf(device), signals = signals)
    val attempts = RecordingAttempts()
    val packets = mutableListOf<Long>()
    var noWifi = false
    val wake = WakeCoordinator(
        h.engine,
        { _, onFirst ->
            if (noWifi) throw NoWifiException()
            val t0 = test.testScheduler.currentTime
            listOf(0L, 2_000L, 5_000L).forEachIndexed { i, offset ->
                delay(offset - (test.testScheduler.currentTime - t0))
                packets += test.testScheduler.currentTime
                if (i == 0) onFirst()
            }
        },
        attempts, h.events, h.clock, test.backgroundScope,
    )
}

private class PowerSetup(
    test: TestScope,
    device: Device = pc(),
    var result: AgentResult<Unit> = AgentResult.Ok(Unit, 20),
    signals: (Device, Long) -> RoundSignals,
) {
    val h = EngineHarness(test, listOf(device), signals = signals)
    val sent = mutableListOf<Pair<AgentCommand, Boolean>>()
    val unknown = mutableListOf<Long>()
    val power = PowerCoordinator(
        h.engine,
        { _, cmd, force -> sent += cmd to force; result },
        { unknown += it.id },
        h.events, h.clock, test.backgroundScope,
    )

    suspend fun on() {
        h.engine.round(1)
        check(h.state() is DeviceState.On)
    }
}

class CoordinatorsTest {

    @Nested
    inner class Wake {

        @Test
        fun `3 sn de yanit - basari durationSec 3, adimlar PACKET REPLY PORT`() = runTest {
            val s = WakeSetup(this) { _, now -> if (now >= 3_000) Sig.tcp(accepted = now >= 5_000) else Sig.none() }
            val steps = mutableListOf<DeviceState>()
            backgroundScope.launch { s.h.engine.states.collect { m -> m[1]?.let(steps::add) } }
            assertTrue(s.wake.wake(1))
            s.wake.join(1)
            assertEquals(listOf(RecordingAttempts.Attempt(1, 0, true, 3, AttemptSource.MANUAL)), s.attempts.attempts)
            assertEquals(listOf(0L, 2_000L, 5_000L), s.packets)
            val waking = steps.filterIsInstance<DeviceState.Waking>().map { it.step }.distinct()
            assertEquals(listOf(WakeStep.PACKET, WakeStep.REPLY, WakeStep.PORT), waking)
            assertInstanceOf(DeviceState.On::class.java, s.h.state())
            assertTrue(s.h.seen.any { it is DomainEvent.WakeSucceeded && it.durationSec == 3 })
        }

        @Test
        fun `60 sn yanitsiz - basarisiz deneme, uyari, Kapali`() = runTest {
            val s = WakeSetup(this) { _, _ -> Sig.none() }
            s.wake.wake(1, AttemptSource.TILE)
            s.wake.join(1)
            assertEquals(listOf(RecordingAttempts.Attempt(1, 0, false, null, AttemptSource.TILE)), s.attempts.attempts)
            assertTrue(testScheduler.currentTime in 60_000L..61_000L)
            assertEquals(DeviceState.Off(tried = true), s.h.state())
            assertTrue(s.h.seen.any { it is DomainEvent.WakeFailed })
            assertEquals("Kapalı", StateLabels.of(s.h.state()))
        }

        @Test
        fun `ikinci tetikleme yok sayilir`() = runTest {
            val s = WakeSetup(this) { _, now -> if (now >= 2_000) Sig.tcp(accepted = true) else Sig.none() }
            assertTrue(s.wake.wake(1))
            assertFalse(s.wake.wake(1))
            s.wake.join(1)
            assertEquals(1, s.attempts.attempts.size)
            assertTrue(s.wake.wake(1), "akış bitince yeniden başlatılabilir")
        }

        @Test
        fun `MAC yoksa baslamaz`() = runTest {
            val s = WakeSetup(this, pc(mac = null)) { _, _ -> Sig.none() }
            assertFalse(s.wake.wake(1))
            runCurrent()
            assertTrue(s.h.seen.any { it is DomainEvent.NoMac })
            assertTrue(s.packets.isEmpty())
        }

        @Test
        fun `Wi-Fi yoksa deneme yazilmaz, uyari`() = runTest {
            val s = WakeSetup(this) { _, _ -> Sig.none() }
            s.noWifi = true
            s.wake.wake(1)
            s.wake.join(1)
            assertTrue(s.attempts.attempts.isEmpty())
            assertTrue(s.h.seen.any { it is DomainEvent.NoWifi })
            assertFalse(s.h.state() is DeviceState.Waking)
        }

        @Test
        fun `PORT 30 sn de dolmazsa adim atlanir, basari gecerli`() = runTest {
            val s = WakeSetup(this) { _, now -> if (now >= 1_000) Sig.tcp(accepted = false) else Sig.none() }
            s.wake.wake(1)
            s.wake.join(1)
            assertEquals(true, s.attempts.attempts.single().success)
            assertEquals(1, s.attempts.attempts.single().durationSec)
            assertTrue(testScheduler.currentTime in 31_000L..32_500L)
            assertInstanceOf(DeviceState.On::class.java, s.h.state())
        }
    }

    @Nested
    inner class Power {

        @Test
        fun `K12 - acik olmayan cihaza komut gonderilmez`() = runTest {
            val s = PowerSetup(this) { _, _ -> Sig.none() }
            assertFalse(s.power.execute(1, PowerAction.SHUTDOWN))
            runCurrent()
            assertTrue(s.sent.isEmpty())
            assertTrue(s.h.seen.any { it is DomainEvent.CommandRejected && it.reason == DomainEvent.CommandRejected.Reason.NOT_ON })
        }

        @Test
        fun `agent yoksa ve hazirda kapaliysa reddedilir`() = runTest {
            val noAgent = PowerSetup(this, pc(agent = false)) { _, _ -> Sig.tcp() }
            noAgent.on()
            assertEquals(DomainEvent.CommandRejected.Reason.NO_AGENT, noAgent.power.rejection(1, PowerAction.LOCK))
            val noHib = PowerSetup(this, pc(hibernate = false)) { _, _ -> Sig.agent() }
            noHib.on()
            assertEquals(DomainEvent.CommandRejected.Reason.HIBERNATE_OFF, noHib.power.rejection(1, PowerAction.HIBERNATE))
            assertEquals(null, noHib.power.rejection(1, PowerAction.SHUTDOWN))
        }

        @Test
        fun `SHUTDOWN - ACK, PowerTransition, 3 negatif turda Kapali`() = runTest {
            val s = PowerSetup(this) { _, now -> if (now < 2_000) Sig.agent() else Sig.none() }
            s.on()
            val states = mutableListOf<DeviceState>()
            backgroundScope.launch { s.h.engine.states.collect { m -> m[1]?.let(states::add) } }
            assertTrue(s.power.execute(1, PowerAction.SHUTDOWN))
            s.power.join(1)
            assertEquals(listOf(AgentCommand.SHUTDOWN to true), s.sent)
            assertTrue(states.any { it is DeviceState.PowerTransition && it.action == PowerAction.SHUTDOWN })
            assertEquals(DeviceState.Off(false, lastAction = PowerAction.SHUTDOWN), s.h.state())
            assertEquals("Kapalı", StateLabels.of(s.h.state()))
            assertTrue(s.h.seen.any { it is DomainEvent.PowerDone })
            assertTrue(testScheduler.currentTime in 4_000L..5_500L)
        }

        @Test
        fun `SLEEP sonrasi Uyuyor`() = runTest {
            val s = PowerSetup(this) { _, now -> if (now < 1_000) Sig.agent() else Sig.none() }
            s.on()
            s.power.execute(1, PowerAction.SLEEP)
            s.power.join(1)
            assertEquals(listOf(AgentCommand.SLEEP to false), s.sent)
            assertEquals("Uyuyor", StateLabels.of(s.h.state()))
        }

        @Test
        fun `K23 sleepHibernates cihazda SLEEP Hazirda bekletiliyor sonra Hazirda`() = runTest {
            val s = PowerSetup(this, pc(sleepHibernates = true)) { _, now -> if (now < 1_000) Sig.agent() else Sig.none() }
            s.on()
            val states = mutableListOf<DeviceState>()
            backgroundScope.launch { s.h.engine.states.collect { m -> m[1]?.let(states::add) } }
            s.power.execute(1, PowerAction.SLEEP)
            s.power.join(1)
            assertEquals(listOf(AgentCommand.SLEEP to false), s.sent)
            assertTrue(states.any { it == DeviceState.PowerTransition(PowerAction.HIBERNATE, 0) })
            assertEquals("Hazırda bekletiliyor…", StateLabels.of(DeviceState.PowerTransition(PowerAction.HIBERNATE, 0)))
            assertEquals("Hazırda", StateLabels.of(s.h.state()))
        }

        @Test
        fun `90 sn de kapanmazsa uyari, Nazik modda ek metin`() = runTest {
            val s = PowerSetup(this, pc(mode = ShutdownMode.GRACEFUL)) { _, _ -> Sig.agent() }
            s.on()
            s.power.execute(1, PowerAction.SHUTDOWN)
            s.power.join(1)
            assertEquals(listOf(AgentCommand.SHUTDOWN to false), s.sent)
            val e = s.h.seen.filterIsInstance<DomainEvent.PowerTimedOut>().single()
            assertEquals("Kapanmadı · Açık bir program engelliyor olabilir", e.text)
            assertTrue(testScheduler.currentTime in 90_000L..91_500L)
            assertInstanceOf(DeviceState.On::class.java, s.h.state())
        }

        @Test
        fun `RESTART offline sonra online`() = runTest {
            val s = PowerSetup(this) { _, now -> if (now < 1_000 || now >= 20_000) Sig.agent(uptime = if (now < 1_000) 9_000 else 15) else Sig.none() }
            s.on()
            s.power.execute(1, PowerAction.RESTART)
            s.power.join(1)
            assertTrue(s.h.seen.any { it is DomainEvent.PowerDone && it.action == PowerAction.RESTART })
            assertInstanceOf(DeviceState.On::class.java, s.h.state())
            assertTrue(testScheduler.currentTime in 20_000L..21_500L)
        }

        @Test
        fun `RESTART hic offline gorunmese de uptime dususu yeniden basladi sayilir`() = runTest {
            val s = PowerSetup(this) { _, now -> Sig.agent(uptime = if (now < 4_000) 9_000 else 5) }
            s.on()
            s.power.execute(1, PowerAction.RESTART)
            s.power.join(1)
            assertTrue(s.h.seen.any { it is DomainEvent.PowerDone && it.action == PowerAction.RESTART })
            assertTrue(testScheduler.currentTime < 6_000)
        }

        @Test
        fun `LOCK - On kalir, Kilitlendi olayi`() = runTest {
            val s = PowerSetup(this) { _, _ -> Sig.agent() }
            s.on()
            s.power.execute(1, PowerAction.LOCK)
            s.power.join(1)
            assertTrue(s.h.seen.any { it is DomainEvent.Locked })
            assertInstanceOf(DeviceState.On::class.java, s.h.state())
            assertEquals(listOf(AgentCommand.LOCK to false), s.sent)
        }

        @Test
        fun `UNSUPPORTED, BUSY olaylari, UNKNOWN_PHONE K6 ye gider`() = runTest {
            val s = PowerSetup(this) { _, _ -> Sig.agent() }
            s.on()
            s.result = AgentResult.Error("UNSUPPORTED")
            s.power.execute(1, PowerAction.HIBERNATE); s.power.join(1)
            s.result = AgentResult.Error("BUSY")
            s.power.execute(1, PowerAction.SHUTDOWN); s.power.join(1)
            s.result = AgentResult.UnknownPhone
            s.power.execute(1, PowerAction.SHUTDOWN); s.power.join(1)
            val codes = s.h.seen.filterIsInstance<DomainEvent.AgentFailed>().map { it.code }
            assertEquals(listOf("UNSUPPORTED", "BUSY", "UNKNOWN_PHONE"), codes)
            assertEquals(listOf(1L), s.unknown)
            assertInstanceOf(DeviceState.On::class.java, s.h.state())
        }
    }
}
