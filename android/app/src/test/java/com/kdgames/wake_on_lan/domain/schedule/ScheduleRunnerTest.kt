package com.kdgames.wake_on_lan.domain.schedule

import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.db.ScheduleRun
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
    val runs = mutableListOf<ScheduleRun>()
    override suspend fun schedule(id: Long) = schedule.takeIf { it.id == id }
    override suspend fun update(schedule: Schedule) {
        this.schedule = schedule
    }
    override suspend fun startRun(scheduleId: Long, occurrence: Long, startedAt: Long): Long =
        (runs.size + 1L).also { runs += ScheduleRun(it, scheduleId, occurrence, startedAt, null, RunResult.RUNNING) }
    override suspend fun run(runId: Long) = runs.firstOrNull { it.id == runId }
    override suspend fun runFor(scheduleId: Long, occurrence: Long) = runs.lastOrNull { it.scheduleId == scheduleId && it.occurrence == occurrence }
    override suspend fun running() = runs.filter { it.result == RunResult.RUNNING }
    override suspend fun saveAttempts(runId: Long, attemptsJson: String) = edit(runId) { it.copy(attemptsJson = attemptsJson) }
    override suspend fun finishRun(runId: Long, endedAt: Long, result: RunResult, attemptsJson: String) =
        edit(runId) { it.copy(endedAt = endedAt, result = result, attemptsJson = attemptsJson) }

    private fun edit(id: Long, f: (ScheduleRun) -> ScheduleRun) {
        val i = runs.indexOfFirst { it.id == id }
        runs[i] = f(runs[i])
    }
}

private class RecordingAlarms : RetryAlarms {
    /** runId → (sıra, zaman); son kurulan. */
    val armed = mutableMapOf<Long, Pair<Int, Long>>()
    var sets = 0
    override fun set(runId: Long, index: Int, at: Long) {
        armed[runId] = index to at
        sets++
    }
    override fun cancel(runId: Long) {
        armed.remove(runId)
    }
}

/** [signals] sanal ms ile çağrılır; [onCommand] komut gelince (ör. PC kapanır). */
private class RunnerSetup(
    val test: TestScope,
    action: PowerAction,
    var policy: RetrySettings = RetrySettings(2, 10),
    type: ScheduleType = ScheduleType.TIME,
    signals: (Long) -> RoundSignals,
) {
    val h = EngineHarness(test, signals = { _, t -> signals(t) })
    val store = MemoryRunStore(
        Schedule(id = 7, deviceId = 1, action = action, type = type, daysMask = if (type == ScheduleType.TIME) 0x7F else 0, minuteOfDay = if (type == ScheduleType.TIME) 510 else null, ssid = if (type == ScheduleType.WIFI_CONNECT) "Ev-WiFi" else null),
    )
    val attempts = RecordingAttempts()
    val alarms = RecordingAlarms()
    var packets = 0
    val commands = mutableListOf<AgentCommand>()
    var commandResult: AgentResult<Unit> = AgentResult.Ok(Unit, 5)
    var onCommand: () -> Unit = {}
    val notes = mutableListOf<String>()
    val failures = mutableListOf<Pair<Long, List<RunAttempt>>>()
    private val notifier = object : ScheduleNotifier {
        override fun started(scheduleId: Long, deviceName: String, action: PowerAction) {
            notes += "$deviceName: ${ScheduleTexts.ordered(action)}"
        }
        override fun retrying(scheduleId: Long, deviceName: String, action: PowerAction, nextAttempt: Int, reason: String?) {
            notes += "$deviceName: ${ScheduleTexts.retrying(action, nextAttempt, reason)}"
        }
        override fun finished(scheduleId: Long, deviceName: String, action: PowerAction, result: RunResult, reason: String?) {
            notes += "$deviceName: ${ScheduleTexts.result(action, result, reason)}"
        }
        override fun failed(scheduleId: Long, runId: Long, deviceName: String, action: PowerAction, attempts: List<RunAttempt>) {
            failures += runId to attempts
            notes += "${ScheduleTexts.failureTitle(deviceName, action)}: ${ScheduleTexts.failureSummary(attempts)}"
        }
    }
    fun wall() = 1_000_000L + test.testScheduler.currentTime
    val runner = runner()

    fun runner() = ScheduleRunner(
        store, h.engine,
        { _, onFirst -> packets++; onFirst() },
        { _, cmd, _ -> commands += cmd; onCommand(); commandResult },
        attempts,
        { },
        notifier,
        { policy },
        alarms,
        h.clock,
        { wall() },
    )

    /** İlk deneme + alarm zamanı gelen her tekrar; zincir bitene dek. */
    suspend fun chain(r: ScheduleRunner = runner): RunResult? {
        var result = r.run(7, 100)
        while (result == RunResult.RUNNING) {
            val (index, at) = alarms.armed.remove(1L) ?: break
            test.testScheduler.advanceTimeBy((at - wall()).coerceAtLeast(0))
            test.testScheduler.runCurrent()
            result = r.retry(1, index)
        }
        return result
    }

    fun run() = store.runs.single()
    fun outcomes() = RunAttempts.decode(run().attemptsJson).map { it.outcome }
    fun offsetsMin() = RunAttempts.decode(run().attemptsJson).let { a -> a.map { ((it.at - a.first().at) / 60_000.0).let(Math::round).toInt() } }
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
        assertTrue(s.alarms.armed.isEmpty())
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
    fun `ilk denemede basari - tek kayit, tek deneme, WakeAttempt SCHEDULE`() = runTest {
        // Kontrol turları (≈2 sn) kapalı, sonra uyanır.
        val s = RunnerSetup(this, PowerAction.WAKE) { t -> if (t < 6_000) off else on }
        assertEquals(RunResult.SUCCESS, s.chain())
        assertEquals(1, s.packets)
        assertEquals(1, s.store.runs.size)
        assertEquals(listOf(AttemptOutcome.OK), s.outcomes())
        assertEquals(AttemptSource.SCHEDULE, s.attempts.attempts.single().source)
        assertTrue(s.attempts.attempts.single().success)
        assertTrue(s.alarms.armed.isEmpty())
        assertTrue(s.failures.isEmpty())
    }

    @Test
    fun `alici paketi gonderdiyse tekrar gonderilmez`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { t -> if (t < 4_000) off else on }
        assertEquals(RunResult.SUCCESS, s.runner.run(7, 100, packetSent = true))
        assertEquals(0, s.packets)
    }

    @Test
    fun `2 dk 10 dk - 6 deneme t=0,2,4,6,8,10, sonra FAILED ve bildirim`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { off }
        assertEquals(RunResult.RUNNING, s.runner.run(7, 100))
        // İlk deneme: doğrulama min(60 sn, aralık); sonraki deneme ilk denemeden 2 dk sonra.
        val firstAt = RunAttempts.decode(s.run().attemptsJson).single().at
        assertEquals(1 to s.run().startedAt + 120_000, s.alarms.armed[1])
        // Deneme anı = paket anı (≈2 sn kontrol turlarından sonra).
        assertTrue(firstAt - s.run().startedAt in 0..5_000)
        assertEquals(RunResult.FAILED, s.chain())
        assertEquals(listOf(0, 2, 4, 6, 8, 10), s.offsetsMin())
        assertEquals(List(6) { AttemptOutcome.NO_REPLY }, s.outcomes())
        assertEquals(6, s.packets)
        assertEquals(1, s.store.runs.size)
        assertEquals(1, s.failures.size)
        assertEquals("Ofis PC uyanmadı: 6 deneme · 10 dk sonra vazgeçildi", s.notes.last())
        assertTrue(s.alarms.armed.isEmpty())
        assertTrue(s.attempts.attempts.none { it.success })
    }

    @Test
    fun `giveUp sinirlar - 2 dk 5 dk ile 3 deneme, 1 dk 2 dk ile 3 deneme`() = runTest {
        val a = RunnerSetup(this, PowerAction.WAKE, RetrySettings(2, 5)) { off }
        assertEquals(RunResult.FAILED, a.chain())
        assertEquals(listOf(0, 2, 4), a.offsetsMin())
        val b = RunnerSetup(this, PowerAction.WAKE, RetrySettings(1, 2)) { off }
        assertEquals(RunResult.FAILED, b.chain())
        assertEquals(listOf(0, 1, 2), b.offsetsMin())
    }

    @Test
    fun `3 denemede basari - SUCCESS, 3 deneme, basarisizlik bildirimi yok`() = runTest {
        // PC 4. dakikada (3. deneme) paketle uyanır.
        val s = RunnerSetup(this, PowerAction.WAKE) { t -> if (t < 4 * 60_000L + 6_000) off else on }
        assertEquals(RunResult.SUCCESS, s.chain())
        assertEquals(listOf(AttemptOutcome.NO_REPLY, AttemptOutcome.NO_REPLY, AttemptOutcome.OK), s.outcomes())
        assertEquals(RunResult.SUCCESS, s.run().result)
        assertTrue(s.failures.isEmpty())
        assertTrue(s.notes.isEmpty())
    }

    @Test
    fun `NO_WIFI sayilir ve zincir surer`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { t -> if (t < 4 * 60_000L + 6_000) off else on }
        s.h.wifi = false
        assertEquals(RunResult.RUNNING, s.runner.run(7, 100))
        val (i1, at1) = s.alarms.armed.remove(1L)!!
        testScheduler.advanceTimeBy(at1 - s.wall())
        assertEquals(RunResult.RUNNING, s.runner.retry(1, i1))
        // Wi-Fi geri geldi.
        s.h.wifi = true
        val (i2, at2) = s.alarms.armed.remove(1L)!!
        testScheduler.advanceTimeBy(at2 - s.wall())
        assertEquals(RunResult.SUCCESS, s.runner.retry(1, i2))
        assertEquals(listOf(AttemptOutcome.NO_WIFI, AttemptOutcome.NO_WIFI, AttemptOutcome.OK), s.outcomes())
        assertEquals(listOf(0, 2, 4), s.offsetsMin())
    }

    @Test
    fun `zincir surerken PC elle acildi - sonraki denemede SUCCESS, paket yok`() = runTest {
        var manual = false
        val s = RunnerSetup(this, PowerAction.WAKE) { if (manual) on else off }
        assertEquals(RunResult.RUNNING, s.runner.run(7, 100))
        manual = true
        val (i, at) = s.alarms.armed.remove(1L)!!
        testScheduler.advanceTimeBy(at - s.wall())
        assertEquals(RunResult.SUCCESS, s.runner.retry(1, i))
        assertEquals(1, s.packets)
        assertEquals("PC açık", RunAttempts.decode(s.run().attemptsJson).last().reason)
    }

    @Test
    fun `ayni deneme iki kez calismaz, yanlis sira yok sayilir`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { off }
        s.runner.run(7, 100)
        val (i, at) = s.alarms.armed.remove(1L)!!
        testScheduler.advanceTimeBy(at - s.wall())
        s.runner.retry(1, i)
        assertEquals(2, s.packets)
        // Aynı sıra tekrar (alarm + WorkManager): hiçbir şey yapılmaz.
        assertEquals(RunResult.RUNNING, s.runner.retry(1, i))
        assertEquals(2, s.packets)
        // Aynı planlı çalışma tekrar gelirse yeni kayıt açılmaz.
        s.runner.run(7, 100)
        assertEquals(1, s.store.runs.size)
    }

    @Test
    fun `boot sonrasi zincir kaldigi yerden surer`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { off }
        s.runner.run(7, 100)
        val firstSet = s.alarms.armed.remove(1L)!!
        // Telefon yeniden başladı: alarmlar silindi, yeni süreç (yeni runner) zinciri yeniden kurar.
        val fresh = s.runner()
        fresh.resumeChains()
        assertEquals(firstSet, s.alarms.armed[1])
        // Zamanı geçmiş deneme: hemen (5 sn sonra) yapılır.
        testScheduler.advanceTimeBy(5 * 60_000L)
        s.alarms.armed.clear()
        fresh.resumeChains()
        val (index, at) = s.alarms.armed[1]!!
        assertEquals(1, index)
        assertEquals(s.wall() + ScheduleRunner.RESUME_DELAY_MS, at)
        assertEquals(RunResult.FAILED, s.chain(fresh))
        assertEquals(6, RunAttempts.decode(s.run().attemptsJson).size)
    }

    @Test
    fun `boot sonrasi suresi dolmus zincir FAILED olur`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { off }
        s.runner.run(7, 100)
        s.alarms.armed.clear()
        testScheduler.advanceTimeBy(30 * 60_000L)
        // Ayar 10 dk; 1 deneme yapılmış → sıradaki 2. dk, vazgeçme süresi içinde ama vakti çoktan geçti.
        s.policy = RetrySettings(2, 1)
        s.runner().resumeChains()
        assertEquals(RunResult.FAILED, s.run().result)
        assertEquals(1, s.failures.size)
    }

    @Test
    fun `zincir surerken zamanlayici kapatilirsa sessizce biter`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { off }
        s.runner.run(7, 100)
        s.store.schedule = s.store.schedule.copy(enabled = false)
        val (i, at) = s.alarms.armed.remove(1L)!!
        testScheduler.advanceTimeBy(at - s.wall())
        assertEquals(RunResult.SKIPPED_BY_USER, s.runner.retry(1, i))
        assertTrue(s.failures.isEmpty())
        assertEquals(1, s.packets)
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
    fun `WIFI skipNextEvent - SKIPPED_BY_USER, atlama temizlenir`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE, type = ScheduleType.WIFI_CONNECT) { off }
        s.store.schedule = s.store.schedule.copy(skipNextEvent = true)
        assertEquals(RunResult.SKIPPED_BY_USER, s.runner.run(7, 5_000))
        assertFalse(s.store.schedule.skipNextEvent)
        assertEquals(0, s.packets)
        // Sonraki bağlanışta çalışır.
        assertEquals(RunResult.RUNNING, s.runner.run(7, 6_000))
        assertEquals(1, s.packets)
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
    fun `SHUTDOWN zincirde - PC sonradan kapandiysa SUCCESS, komut tekrar gitmez`() = runTest {
        var shut = false
        val s = RunnerSetup(this, PowerAction.SHUTDOWN) { if (shut) off else on }
        s.commandResult = AgentResult.NoResponse
        assertEquals(RunResult.RUNNING, s.runner.run(7, 100))
        shut = true
        val (i, at) = s.alarms.armed.remove(1L)!!
        testScheduler.advanceTimeBy(at - s.wall())
        assertEquals(RunResult.SUCCESS, s.runner.retry(1, i))
        assertEquals(1, s.commands.size)
    }

    @Test
    fun `agent cevap vermezse AGENT_NO_REPLY ve tekrar denenir`() = runTest {
        val s = RunnerSetup(this, PowerAction.LOCK) { on }
        s.commandResult = AgentResult.NoResponse
        assertEquals(RunResult.FAILED, s.chain())
        assertEquals(List(6) { AttemptOutcome.AGENT_NO_REPLY }, s.outcomes())
        assertEquals(6, s.commands.size)
        assertEquals("Ofis PC kilitlenemedi: 6 deneme · 10 dk sonra vazgeçildi", s.notes.last())
    }

    @Test
    fun `bildirim acik - emir, tekrar denemeler sessiz guncellenir, sonra sonuc`() = runTest {
        val s = RunnerSetup(this, PowerAction.WAKE) { t -> if (t < 2 * 60_000L + 6_000) off else on }
        s.store.schedule = s.store.schedule.copy(notify = true)
        assertEquals(RunResult.SUCCESS, s.chain())
        assertEquals(
            listOf("Ofis PC: Açma emri verildi", "Ofis PC: Açılmadı · Paket gitti · cevap yok · 2. deneme birazdan"),
            s.notes.take(2),
        )
        assertTrue(s.notes[2].startsWith("Ofis PC: Açıldı · "), s.notes[2])
    }

    @Test
    fun `bildirim kapali - basarida sessiz, basarisizlikta yine bildirim (spec 9-4)`() = runTest {
        val quiet = RunnerSetup(this, PowerAction.WAKE) { t -> if (t < 6_000) off else on }
        quiet.chain()
        assertTrue(quiet.notes.isEmpty())
        val failed = RunnerSetup(this, PowerAction.WAKE, RetrySettings(2, 2)) { off }
        assertEquals(RunResult.FAILED, failed.chain())
        assertEquals(listOf("Ofis PC uyanmadı: 2 deneme · 2 dk sonra vazgeçildi"), failed.notes)
        val skipped = RunnerSetup(this, PowerAction.SHUTDOWN) { off }
        skipped.store.schedule = skipped.store.schedule.copy(notify = true)
        assertEquals(RunResult.SKIPPED_ALREADY, skipped.runner.run(7, 100))
        assertTrue(skipped.notes.isEmpty())
    }

    @Test
    fun `agent esli degilse tekrar denenmez`() = runTest {
        val s = RunnerSetup(this, PowerAction.LOCK) { on }
        s.h.store.flow.value = listOf(com.kdgames.wake_on_lan.domain.pc(agent = false))
        testScheduler.runCurrent()
        assertEquals(RunResult.FAILED, s.runner.run(7, 100))
        assertTrue(s.alarms.armed.isEmpty())
    }

    @Test
    fun `attemptsJson gidip gelir`() {
        val list = listOf(RunAttempt(1, AttemptOutcome.OK, "Uyandı · 4 sn"), RunAttempt(2, AttemptOutcome.AGENT_ERROR, "Agent hatası: \"BUSY\""))
        assertEquals(list, RunAttempts.decode(RunAttempts.encode(list)))
    }
}
