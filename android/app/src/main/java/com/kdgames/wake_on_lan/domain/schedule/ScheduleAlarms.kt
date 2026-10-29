package com.kdgames.wake_on_lan.domain.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import androidx.work.workDataOf
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import com.kdgames.wake_on_lan.domain.wake.MagicSender
import com.kdgames.wake_on_lan.domain.warn
import com.kdgames.wake_on_lan.net.AppScope
import com.kdgames.wake_on_lan.net.NetworkMonitor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Zamanlayıcı başına tek exact alarm (testte kaydedici). */
interface AlarmScheduler {
    fun set(scheduleId: Long, at: Long)
    fun cancel(scheduleId: Long)
    fun canScheduleExact(): Boolean
}

class AndroidAlarmScheduler @Inject constructor(@ApplicationContext private val context: Context) : AlarmScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun set(scheduleId: Long, at: Long) {
        val pi = pending(scheduleId, at)
        // K13 (telefonda doğrulandı): allow-while-idle kotası 72/saat, her alarm 10 sn ağ izni → setAlarmClock gerekmez.
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    override fun cancel(scheduleId: Long) = alarms.cancel(pending(scheduleId, 0))

    override fun canScheduleExact(): Boolean = alarms.canScheduleExactAlarms()

    private fun pending(scheduleId: Long, at: Long): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("wol://schedule/$scheduleId")) // zamanlayıcı başına ayrı PendingIntent
            .putExtra(EXTRA_ID, scheduleId)
            .putExtra(EXTRA_OCCURRENCE, at)
        return PendingIntent.getBroadcast(context, scheduleId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_FIRE = "com.kdgames.wake_on_lan.SCHEDULE_FIRE"
        const val EXTRA_ID = "scheduleId"
        const val EXTRA_OCCURRENCE = "occurrence"
    }
}

/** Debug ekranı için: kurulu alarm = zamanlayıcı + sonraki tetik. */
data class ArmedAlarm(val scheduleId: Long, val at: Long)

/**
 * Zamanlayıcı listesini izler ve alarmları onunla eşler: açık TIME zamanlayıcısının sıradaki çalışması kurulur,
 * kapalı/silinen/WIFI zamanlayıcıların alarmı iptal edilir. Geçmiş atlamalar burada temizlenir.
 * Boot, güncelleme, saat ve saat dilimi değişiminde [rescheduleAll].
 */
@Singleton
class ScheduleService @Inject constructor(
    private val repo: ScheduleRepository,
    private val alarms: AlarmScheduler,
    private val wall: WallClock,
    @AppScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private val _armed = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val armed: StateFlow<Map<Long, Long>> = _armed.asStateFlow()

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch { repo.observeSchedules().collect { sync(it) } }
    }

    fun rescheduleAll() {
        scope.launch { sync(repo.getSchedules(), force = true) }
    }

    /** Alarm tetiklenince: bu çalışmadan sonrakini hemen kur (süreç ölse de zincir kopmasın). */
    suspend fun armAfter(schedule: Schedule, occurrence: Long) = mutex.withLock {
        val next = ScheduleEngine.nextOccurrence(schedule, maxOf(occurrence, wall.nowMs()), ZoneId.systemDefault())
        if (schedule.enabled && next != null) arm(schedule.id, next) else disarm(schedule.id)
    }

    private suspend fun sync(list: List<Schedule>, force: Boolean = false) = mutex.withLock {
        val now = wall.nowMs()
        val zone = ZoneId.systemDefault()
        val ids = list.map { it.id }.toSet()
        (_armed.value.keys - ids).forEach(::disarm)
        for (s in list) {
            val clean = ScheduleEngine.cleaned(s, now)
            if (clean != s) runCatching { repo.update(clean) }
            val next = if (clean.enabled) ScheduleEngine.nextOccurrence(clean, now, zone) else null
            when {
                next == null -> if (s.id in _armed.value || force) disarm(s.id)
                force || _armed.value[s.id] != next -> arm(s.id, next)
            }
        }
    }

    private fun arm(id: Long, at: Long) {
        runCatching { alarms.set(id, at) }.onFailure { warn(TAG, "Alarm kurulamadı", it) }
        _armed.value = _armed.value + (id to at)
    }

    private fun disarm(id: Long) {
        runCatching { alarms.cancel(id) }
        _armed.value = _armed.value - id
    }

    fun canScheduleExact(): Boolean = alarms.canScheduleExact()

    private companion object {
        const val TAG = "ScheduleService"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduleEntryPoint {
    fun service(): ScheduleService
    fun runner(): ScheduleRunner
    fun repo(): ScheduleRepository
    fun engine(): StatusEngine
    fun magic(): MagicSender
    fun monitor(): NetworkMonitor

    @AppScope
    fun scope(): CoroutineScope
}

private fun Context.scheduleDeps() = EntryPointAccessors.fromApplication(applicationContext, ScheduleEntryPoint::class.java)

/**
 * Alarm (spec 9.4): `goAsync` ile (≈10 sn sınırı) sonraki alarmı kurar, WAKE ise ilk magic packet turunu
 * hemen gönderir, doğrulamayı expedited [VerifyWorker]'a bırakır (K10).
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AndroidAlarmScheduler.EXTRA_ID, -1)
        val occurrence = intent.getLongExtra(AndroidAlarmScheduler.EXTRA_OCCURRENCE, System.currentTimeMillis())
        if (id < 0) return
        val deps = context.scheduleDeps()
        val pending = goAsync()
        deps.scope().launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) { fire(context, deps, id, occurrence) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, deps: ScheduleEntryPoint, id: Long, occurrence: Long) {
        val schedule = deps.repo().getSchedule(id) ?: return
        deps.service().armAfter(schedule, occurrence)
        if (!schedule.enabled) return
        var packetSent = false
        if (schedule.action == PowerAction.WAKE && schedule.skippedOccurrence != occurrence) {
            // Süreç yeni açıldıysa Wi-Fi durumu ve cihaz listesi birkaç yüz ms içinde gelir.
            val device = deps.engine().awaitDevice(schedule.deviceId, 2_000)
            withTimeoutOrNull(2_000) { deps.monitor().state.first { it.connected && it.broadcast != null } }
            val mac = device?.mac
            if (mac != null) {
                // İlk tur gidince devam; kalan turlar (t=2/5 sn) uygulama kapsamında sürer.
                val first = kotlinx.coroutines.CompletableDeferred<Unit>()
                deps.scope().launch {
                    runCatching { deps.magic().send(mac) { first.complete(Unit) } }.onFailure { first.completeExceptionally(it) }
                }
                packetSent = withTimeoutOrNull(1_500) { runCatching { first.await() }.isSuccess } == true
            }
        }
        VerifyWorker.enqueue(context, id, occurrence, packetSent)
    }

    private companion object {
        const val RECEIVER_BUDGET_MS = 8_000L
    }
}

/** Açılış, uygulama güncellemesi, saat ve saat dilimi değişimi → tüm alarmlar yeniden (spec 9.4). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.scheduleDeps().service().rescheduleAll()
    }
}

/** K10: zamanlayıcı çalışmasının doğrulaması (expedited; kota bitince normal iş). */
class VerifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val occurrence = inputData.getLong(KEY_OCCURRENCE, 0)
        if (id < 0) return Result.success()
        applicationContext.scheduleDeps().runner().run(id, occurrence, inputData.getBoolean(KEY_PACKET, false))
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_OCCURRENCE = "occurrence"
        private const val KEY_PACKET = "packetSent"

        fun enqueue(context: Context, id: Long, occurrence: Long, packetSent: Boolean) {
            val request = OneTimeWorkRequestBuilder<VerifyWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_ID to id, KEY_OCCURRENCE to occurrence, KEY_PACKET to packetSent))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("schedule-$id-$occurrence", ExistingWorkPolicy.KEEP, request)
        }
    }
}
