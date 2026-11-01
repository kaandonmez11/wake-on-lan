package com.kdgames.wake_on_lan.domain.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.ScheduleType
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
import kotlinx.coroutines.CompletableDeferred
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

/**
 * Retry zinciri alarmları (spec 9.4, K13): çalışma başına tek exact alarm. K13 doğrulaması: kota 72/saat,
 * 2 dk aralık (10 dk'da 6 alarm) sığar → `setExactAndAllowWhileIdle`, `setAlarmClock` gerekmez.
 */
@Singleton
class AndroidRetryAlarms @Inject constructor(@ApplicationContext private val context: Context) : RetryAlarms {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val _armed = MutableStateFlow<Map<Long, Pair<Int, Long>>>(emptyMap())

    /** Debug: süren zincirler → (sıradaki deneme sırası, zamanı). Süreç içi. */
    val armed: StateFlow<Map<Long, Pair<Int, Long>>> = _armed.asStateFlow()

    override fun set(runId: Long, index: Int, at: Long) {
        val pi = pending(runId, index)
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        _armed.value = _armed.value + (runId to (index to at))
    }

    override fun cancel(runId: Long) {
        alarms.cancel(pending(runId, 0))
        _armed.value = _armed.value - runId
    }

    /** Alarm çaldı: listeden düşer. */
    fun fired(runId: Long) {
        _armed.value = _armed.value - runId
    }

    private fun pending(runId: Long, index: Int): PendingIntent {
        val intent = Intent(context, RetryAlarmReceiver::class.java)
            .setAction(ACTION_RETRY)
            .setData(Uri.parse("wol://retry/$runId"))
            .putExtra(EXTRA_RUN, runId)
            .putExtra(EXTRA_INDEX, index)
        return PendingIntent.getBroadcast(context, runId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_RETRY = "com.kdgames.wake_on_lan.SCHEDULE_RETRY"
        const val EXTRA_RUN = "runId"
        const val EXTRA_INDEX = "index"
    }
}

/** Debug ekranı için: kurulu alarm = zamanlayıcı + sonraki tetik. */
data class ArmedAlarm(val scheduleId: Long, val at: Long)

/**
 * Zamanlayıcı listesini izler ve alarmları onunla eşler: açık TIME zamanlayıcısının sıradaki çalışması kurulur,
 * kapalı/silinen/WIFI zamanlayıcıların alarmı iptal edilir. Geçmiş atlamalar burada temizlenir. Açık bir
 * "eve gelince" zamanlayıcısı varsa ağ callback'i kayıtlı tutulur.
 * Boot, güncelleme, saat ve saat dilimi değişiminde [rescheduleAll].
 */
@Singleton
class ScheduleService @Inject constructor(
    private val repo: ScheduleRepository,
    private val alarms: AlarmScheduler,
    private val wall: WallClock,
    @AppScope private val scope: CoroutineScope,
    private val wifiTrigger: WifiTriggerSync,
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
        runCatching { wifiTrigger.sync(list.any { it.enabled && it.type == ScheduleType.WIFI_CONNECT }, force) }
            .onFailure { warn(TAG, "Wi-Fi tetikleyicisi kaydedilemedi", it) }
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

/** "Eve gelince" callback kaydı (testte boş). */
fun interface WifiTriggerSync {
    fun sync(needed: Boolean, force: Boolean)
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
    fun retryAlarms(): AndroidRetryAlarms
    fun wifiTrigger(): WifiTriggerRegistrar
    fun wifiLog(): WifiTriggerLog

    @AppScope
    fun scope(): CoroutineScope
}

internal fun Context.scheduleDeps() = EntryPointAccessors.fromApplication(applicationContext, ScheduleEntryPoint::class.java)

/** Alıcıların ortak işi: WAKE'te ilk magic packet turunu hemen gönderir (alarmın 10 sn ağ izni içinde). */
internal object ScheduleDispatch {
    suspend fun firstPacket(deps: ScheduleEntryPoint, deviceId: Long): Boolean {
        // Süreç yeni açıldıysa Wi-Fi durumu ve cihaz listesi birkaç yüz ms içinde gelir.
        val device = deps.engine().awaitDevice(deviceId, 2_000)
        withTimeoutOrNull(2_000) { deps.monitor().state.first { it.connected && it.broadcast != null } }
        val mac = device?.mac ?: return false
        // İlk tur gidince devam; kalan turlar (t=2/5 sn) uygulama kapsamında sürer.
        val first = CompletableDeferred<Unit>()
        deps.scope().launch {
            runCatching { deps.magic().send(mac) { first.complete(Unit) } }.onFailure { first.completeExceptionally(it) }
        }
        return withTimeoutOrNull(1_500) { runCatching { first.await() }.isSuccess } == true
    }

    /** Zamanlayıcıyı şimdi çalıştır (alarm ya da Wi-Fi tetiği): paket + doğrulama işi. */
    suspend fun start(context: Context, deps: ScheduleEntryPoint, schedule: Schedule, occurrence: Long) {
        val skipped = when (schedule.type) {
            ScheduleType.TIME -> schedule.skippedOccurrence == occurrence
            ScheduleType.WIFI_CONNECT -> schedule.skipNextEvent
        }
        val packetSent = schedule.action == PowerAction.WAKE && !skipped && firstPacket(deps, schedule.deviceId)
        VerifyWorker.enqueue(context, schedule.id, occurrence, packetSent)
    }
}

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
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    val schedule = deps.repo().getSchedule(id) ?: return@withTimeoutOrNull
                    deps.service().armAfter(schedule, occurrence)
                    if (schedule.enabled) ScheduleDispatch.start(context, deps, schedule, occurrence)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val RECEIVER_BUDGET_MS = 8_000L
    }
}

/** Retry alarmı (spec 9.4): WAKE ise paketi hemen gönderir, denemeyi [VerifyWorker]'a bırakır. */
class RetryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getLongExtra(AndroidRetryAlarms.EXTRA_RUN, -1)
        val index = intent.getIntExtra(AndroidRetryAlarms.EXTRA_INDEX, -1)
        if (runId < 0 || index < 0) return
        val deps = context.scheduleDeps()
        deps.retryAlarms().fired(runId)
        val pending = goAsync()
        deps.scope().launch {
            try {
                withTimeoutOrNull(8_000) {
                    val run = deps.repo().getRun(runId) ?: return@withTimeoutOrNull
                    val schedule = deps.repo().getSchedule(run.scheduleId) ?: return@withTimeoutOrNull
                    val packetSent = schedule.enabled && schedule.action == PowerAction.WAKE && ScheduleDispatch.firstPacket(deps, schedule.deviceId)
                    VerifyWorker.enqueueRetry(context, runId, index, packetSent)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Açılış, uygulama güncellemesi, saat ve saat dilimi değişimi → tüm alarmlar, "eve gelince" callback'i ve
 * süren retry zincirleri yeniden (spec 9.4).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deps = context.scheduleDeps()
        deps.service().rescheduleAll()
        val pending = goAsync()
        deps.scope().launch {
            try {
                withTimeoutOrNull(8_000) { deps.runner().resumeChains() }
            } finally {
                pending.finish()
            }
        }
    }
}

/** K10: zamanlayıcı çalışmasının doğrulaması (expedited; kota bitince normal iş). */
class VerifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runner = applicationContext.scheduleDeps().runner()
        val packet = inputData.getBoolean(KEY_PACKET, false)
        val runId = inputData.getLong(KEY_RUN, -1)
        if (runId >= 0) {
            runner.retry(runId, inputData.getInt(KEY_INDEX, 0), packet)
            return Result.success()
        }
        val id = inputData.getLong(KEY_ID, -1)
        val occurrence = inputData.getLong(KEY_OCCURRENCE, 0)
        if (id < 0) return Result.success()
        runner.run(id, occurrence, packet)
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_OCCURRENCE = "occurrence"
        private const val KEY_PACKET = "packetSent"
        private const val KEY_RUN = "runId"
        private const val KEY_INDEX = "index"

        fun enqueue(context: Context, id: Long, occurrence: Long, packetSent: Boolean) {
            val request = OneTimeWorkRequestBuilder<VerifyWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_ID to id, KEY_OCCURRENCE to occurrence, KEY_PACKET to packetSent))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("schedule-$id-$occurrence", ExistingWorkPolicy.KEEP, request)
        }

        fun enqueueRetry(context: Context, runId: Long, index: Int, packetSent: Boolean) {
            val request = OneTimeWorkRequestBuilder<VerifyWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_RUN to runId, KEY_INDEX to index, KEY_PACKET to packetSent))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("retry-$runId-$index", ExistingWorkPolicy.KEEP, request)
        }
    }
}
