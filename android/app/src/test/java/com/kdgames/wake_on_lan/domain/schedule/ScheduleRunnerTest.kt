package com.kdgames.wake_on_lan.domain.schedule

import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.domain.EngineHarness
import com.kdgames.wake_on_lan.domain.RecordingAttempts
import com.kdgames.wake_on_lan.domain.Sig
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.domain.model.RunResult
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import com.kdgames.wake_on_lan.net.agent.AgentCommand
import com.kdgames.wake_on_lan.net.agent.AgentResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class MemoryRunStore(var schedule: Schedule) : ScheduleRunStore {
    data class Run(val id: Long, val occurrence: Long, var result: RunResult, var attempts: String = "[]", var ended: Long? = null)

    val runs = mutableListOf<Run>()
    override suspend fun schedule(id: Long) = schedule.takeIf { it.id == id }
    override suspend fun update(schedule: Schedule) {
        this.schedule = schedule
    }
    override suspend fun startRun(scheduleId: Long, occurrence: Long, startedAt: Long): Long =
        (runs.size + 1L).also { runs += Run(it, occurrence, RunResult.RUNNING) }
    override suspend fun finishRun(runId: Long, endedAt: Long, result: RunResult, attemptsJson: String) {
        runs.first { it.id == runId }.apply {
            this.result = result
            attempts = attemptsJson
            ended = endedAt
        }
    }
}

/** [signals] sanal ms ile çağrılır; [onCommand] komut gelince (ör. PC kapanır). */
private class RunnerSetup(
    test: TestScope,
    action: PowerAction,
    signals: (Long) -> RoundSignals,
) {
    val h = EngineHarness(test, signals = { _, t -> signals(t) })
    val store = MemoryRunStore(Schedule(id = 7, deviceId = 1, action = action, type = ScheduleType.TIME, daysMask = 0x7F, minuteOfDay = 510))
    val attempts = RecordingAttempts()
    var packets = 0
    val commands = mutableListOf<AgentCommand>()
    var commandResult: AgentResult<Unit> = AgentResult.Ok(Unit, 5)
    var onCommand: () -> Unit = {}
    val runner = ScheduleRunner(
        store, h.engine,
        { _, onFirst -> packets++; onFirst() },
        { _, cmd, _ -> commands += cmd; onCommand(); commandResult },
        attempts,
        { },
        { 2 },
        h.clock,
        { 1_000_000L + test.testScheduler.currentTime },
    )

    fun outcomes() = RunAttempts.decode(store.runs.single().attempts).map { it.outcome }
}

class ScheduleRunnerTest {
    private val on = Sig.agent()
    private val off = Sig.none()

    @Test
    fun `zaten acik - SKIPPED_ALREADY, paket yok`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { on }
        assertEquals(RunResult.SKIPPED_ALREADY, s.runner.run(7, 100))
        assertEquals(0, s.packets)
        assertEquals(listOf(RunResult.SKIPPED_ALREADY), s.store.runs.map { it.result })
        assertTrue(s.attempts.attempts.isEmpty())
    }

    @Test
    fun `K12 - kapali PC'ye SHUTDOWN SLEEP LOCK gonderilmez`() = runTest {
        for (action in listOf(PowerAction.SHUTDOWN, PowerAction.SLEEP, PowerAction.LOCK)) {
            val s = RunnerSetup(this, action) { off }
            assertEquals(RunResult.SKIPPED_ALREADY, s.runner.run(7, 100), "$action")
            assertTrue(s.commands.isEmpty(), "$action")
        }
    }

    @Test
    fun `ilk denemede basari - tek kayit, WakeAttempt SCHEDULE`() = runTest {
        // Kontrol turları (≈2 sn) kapalı, sonra uyanır.
        val s = RunnerSetup(this, PowerAction.WAKE) { t -> if (t < 6_000) off else on }
        assertEquals(RunResult.SUCCESS, s.runner.run(7, 100))
        assertEquals(1, s.packets)
        assertEquals(1, s.store.runs.size)
        assertEquals(listOf(AttemptOutcome.OK), s.outcomes())
        assertEquals(AttemptSource.SCHEDULE, s.attempts.attempts.single().source)
        assertTrue(s.attempts.attempts.single().success)
    }

    @Test
    fun `alici paketi gonderdiyse tekrar gonderilmez`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { t -> if (t < 4_000) off else on }
        assertEquals(RunResult.SUCCESS, s.runner.run(7, 100, packetSent = true))
        assertEquals(0, s.packets)
    }

    @Test
    fun `cevap yoksa FAILED NO_REPLY, pencere min(60 sn, aralik)`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { off }
        val start = testScheduler.currentTime
        assertEquals(RunResult.FAILED, s.runner.run(7, 100))
        assertEquals(listOf(AttemptOutcome.NO_REPLY), s.outcomes())
        assertTrue(testScheduler.currentTime - start in 60_000..65_000)
        assertFalse(s.attempts.attempts.single().success)
    }

    @Test
    fun `atlanmis - SKIPPED_BY_USER ve atlama temizlenir`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { on }
        s.store.schedule = s.store.schedule.copy(skippedOccurrence = 100)
        assertEquals(RunResult.SKIPPED_BY_USER, s.runner.run(7, 100))
        assertNull(s.store.schedule.skippedOccurrence)
        assertEquals(0, s.packets)
    }

    @Test
    fun `kapali zamanlayici calismaz`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { off }
        s.store.schedule = s.store.schedule.copy(enabled = false)
        assertNull(s.runner.run(7, 100))
        assertTrue(s.store.runs.isEmpty())
    }

    @Test
    fun `acikken SHUTDOWN - komut, 2 negatif turda SUCCESS`() = runTest {
        var shut = false
        val s = RunnerSetup(this, PowerAction.SHUTDOWN) { if (shut) off else on }
        s.onCommand = { shut = true }
        assertEquals(RunResult.SUCCESS, s.runner.run(7, 100))
        assertEquals(listOf(AgentCommand.SHUTDOWN), s.commands)
        assertEquals(listOf(AttemptOutcome.OK), s.outcomes())
    }

    @Test
    fun `agent cevap vermezse AGENT_NO_REPLY`() = runTest {
        val s = RunnerSetup(this, PowerAction.LOCK) { on }
        s.commandResult = AgentResult.NoResponse
        assertEquals(RunResult.FAILED, s.runner.run(7, 100))
        assertEquals(listOf(AttemptOutcome.AGENT_NO_REPLY), s.outcomes())
    }

    @Test
    fun `Wi-Fi yok - NO_WIFI`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { off }
        s.h.wifi = false
        assertEquals(RunResult.FAILED, s.runner.run(7, 100))
        assertEquals(listOf(AttemptOutcome.NO_WIFI), s.outcomes())
    }

    @Test
    fun `attemptsJson gidip gelir`() {
        val list = listOf(RunAttempt(1, AttemptOutcome.OK, "Uyandı · 4 sn"), RunAttempt(2, AttemptOutcome.AGENT_ERROR, "Agent hatası: \"BUSY\""))
        assertEquals(list, RunAttempts.decode(RunAttempts.encode(list)))
    }
}
