package com.kdgames.wake_on_lan.domain.schedule

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kdgames.wake_on_lan.MainActivity
import com.kdgames.wake_on_lan.R
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RunResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Zamanlayıcı bildirimleri. "Bildirim gönder" açıksa (S08, Kaan) aynı bildirim önce "emir verildi", tekrar
 * denemelerde sessizce "n. deneme", sonra sonuçla güncellenir. `FAILED` ise (spec 9.4) her zaman
 * "Ofis PC uyanmadı" bildirimi gelir, dokununca başarısız ekranı açılır. Atlanan çalışmalar sessiz (K12).
 */
interface ScheduleNotifier {
    fun started(scheduleId: Long, deviceName: String, action: PowerAction)
    fun retrying(scheduleId: Long, deviceName: String, action: PowerAction, nextAttempt: Int, reason: String?) = Unit
    fun finished(scheduleId: Long, deviceName: String, action: PowerAction, result: RunResult, reason: String?)
    fun failed(scheduleId: Long, runId: Long, deviceName: String, action: PowerAction, attempts: List<RunAttempt>) = Unit
}

object ScheduleTexts {
    fun ordered(action: PowerAction): String = when (action) {
        PowerAction.WAKE -> "Açma emri verildi"
        PowerAction.SHUTDOWN -> "Kapatma emri verildi"
        PowerAction.SLEEP -> "Uyku emri verildi"
        PowerAction.HIBERNATE -> "Hazırda bekletme emri verildi"
        PowerAction.LOCK -> "Kilitleme emri verildi"
        PowerAction.RESTART -> "Yeniden başlatma emri verildi"
    }

    fun done(action: PowerAction): String = when (action) {
        PowerAction.WAKE -> "Açıldı"
        PowerAction.SHUTDOWN -> "Kapandı"
        PowerAction.SLEEP -> "Uyutuldu"
        PowerAction.HIBERNATE -> "Hazırda beklemeye alındı"
        PowerAction.LOCK -> "Kilitlendi"
        PowerAction.RESTART -> "Yeniden başlatıldı"
    }

    fun failed(action: PowerAction): String = when (action) {
        PowerAction.WAKE -> "Açılmadı"
        PowerAction.SHUTDOWN -> "Kapanmadı"
        PowerAction.SLEEP -> "Uyutulamadı"
        PowerAction.HIBERNATE -> "Hazırda beklemeye alınamadı"
        PowerAction.LOCK -> "Kilitlenemedi"
        PowerAction.RESTART -> "Yeniden başlatılamadı"
    }

    /** Başarısız başlığı (spec 10.4): "Çalışma PC uyanmadı". */
    fun failureTitle(deviceName: String, action: PowerAction): String = "$deviceName " + when (action) {
        PowerAction.WAKE -> "uyanmadı"
        PowerAction.SHUTDOWN -> "kapanmadı"
        PowerAction.SLEEP -> "uyutulamadı"
        PowerAction.HIBERNATE -> "hazırda beklemeye alınamadı"
        PowerAction.LOCK -> "kilitlenemedi"
        PowerAction.RESTART -> "yeniden başlatılamadı"
    }

    fun result(action: PowerAction, result: RunResult, reason: String?): String = when (result) {
        RunResult.SUCCESS -> listOfNotNull(done(action), reason).joinToString(" · ")
        else -> listOfNotNull(failed(action), reason).joinToString(" · ")
    }

    /** "Açılmadı · Paket gitti · cevap yok · 2. deneme birazdan". */
    fun retrying(action: PowerAction, nextAttempt: Int, reason: String?): String =
        listOfNotNull(failed(action), reason, "$nextAttempt. deneme birazdan").joinToString(" · ")

    /** Bildirim metni: "6 deneme · 10 dk sonra vazgeçildi". */
    fun failureSummary(attempts: List<RunAttempt>): String {
        val span = FailureLabels.spanMinutes(attempts)
        return if (attempts.size <= 1) "Tekrar denenmedi" else "${attempts.size} deneme · $span dk sonra vazgeçildi"
    }
}

class AndroidScheduleNotifier @Inject constructor(@ApplicationContext private val context: Context) : ScheduleNotifier {
    private val manager = NotificationManagerCompat.from(context)

    override fun started(scheduleId: Long, deviceName: String, action: PowerAction) =
        post(scheduleId, deviceName, ScheduleTexts.ordered(action), alert = true)

    override fun retrying(scheduleId: Long, deviceName: String, action: PowerAction, nextAttempt: Int, reason: String?) =
        post(scheduleId, deviceName, ScheduleTexts.retrying(action, nextAttempt, reason), alert = false)

    override fun finished(scheduleId: Long, deviceName: String, action: PowerAction, result: RunResult, reason: String?) =
        post(scheduleId, deviceName, ScheduleTexts.result(action, result, reason), alert = false)

    override fun failed(scheduleId: Long, runId: Long, deviceName: String, action: PowerAction, attempts: List<RunAttempt>) =
        post(
            scheduleId,
            ScheduleTexts.failureTitle(deviceName, action),
            ScheduleTexts.failureSummary(attempts),
            alert = true,
            open = MainActivity.failureIntent(context, runId),
            requestCode = runId.toInt(),
        )

    private fun post(
        scheduleId: Long,
        title: String,
        text: String,
        alert: Boolean,
        open: Intent = Intent(context, MainActivity::class.java),
        requestCode: Int = 0,
    ) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val pending = PendingIntent.getActivity(
            context, requestCode,
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("Zamanlayıcı")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { manager.notify(NOTIFICATION_BASE + scheduleId.toInt(), notification) }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Zamanlayıcılar", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Zamanlayıcı çalışınca: emir, sonuç ve başarısızlık"
            },
        )
    }

    private companion object {
        const val CHANNEL = "schedules"
        const val NOTIFICATION_BASE = 10_000
    }
}
