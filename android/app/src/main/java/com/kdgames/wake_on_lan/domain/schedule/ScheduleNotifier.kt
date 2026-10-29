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
 * S08 (Kaan): zamanlayıcıda "Bildirim gönder" açıksa çalışma bildirimi. Aynı bildirim önce "emir verildi",
 * sonra sonuçla güncellenir. Atlanan çalışmalar sessiz kalır (K12 / spec 9.4).
 */
interface ScheduleNotifier {
    fun started(scheduleId: Long, deviceName: String, action: PowerAction)
    fun finished(scheduleId: Long, deviceName: String, action: PowerAction, result: RunResult, reason: String?)
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

    fun result(action: PowerAction, result: RunResult, reason: String?): String = when (result) {
        RunResult.SUCCESS -> listOfNotNull(done(action), reason).joinToString(" · ")
        else -> listOfNotNull(failed(action), reason).joinToString(" · ")
    }
}

class AndroidScheduleNotifier @Inject constructor(@ApplicationContext private val context: Context) : ScheduleNotifier {
    private val manager = NotificationManagerCompat.from(context)

    override fun started(scheduleId: Long, deviceName: String, action: PowerAction) =
        post(scheduleId, deviceName, ScheduleTexts.ordered(action), alert = true)

    override fun finished(scheduleId: Long, deviceName: String, action: PowerAction, result: RunResult, reason: String?) =
        post(scheduleId, deviceName, ScheduleTexts.result(action, result, reason), alert = false)

    private fun post(scheduleId: Long, title: String, text: String, alert: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Zamanlayıcı")
            .setContentIntent(open)
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
                description = "Zamanlayıcı çalışınca: emir ve sonuç"
            },
        )
    }

    private companion object {
        const val CHANNEL = "schedules"
        const val NOTIFICATION_BASE = 10_000
    }
}
