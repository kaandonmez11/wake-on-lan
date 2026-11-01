package com.kdgames.wake_on_lan.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.kdgames.wake_on_lan.net.NetPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Ayarlar › İzinler satırları (spec 10.7, 14). */
enum class PermissionKind { LOCAL_NETWORK, LOCATION, EXACT_ALARM, NOTIFICATIONS, BATTERY }

/** Satırdaki buton: runtime izin isteği ya da ilgili sistem ekranı. */
enum class PermissionAction(val label: String) { REQUEST("İzin ver"), SETTINGS("Düzelt") }

/** Anlık izin durumu (sahte kaynakla testlenir). */
data class PermissionSnapshot(
    val localNetwork: Boolean = false,
    val fineLocation: Boolean = false,
    val locationService: Boolean = false,
    val exactAlarm: Boolean = false,
    val notifications: Boolean = false,
    val batteryUnrestricted: Boolean = false,
)

data class PermissionRowUi(
    val kind: PermissionKind,
    val title: String,
    val subtitle: String,
    val granted: Boolean,
    /** null = verildi. */
    val action: PermissionAction?,
    /** Tam saatli alarm yoksa satır kırmızı (spec 9.4). */
    val critical: Boolean = false,
)

/** Saf eşleme: durum + oturumda reddedilenler → satırlar. Reddedilen runtime izin artık sistem ekranından düzeltilir. */
object PermissionRows {
    fun of(s: PermissionSnapshot, denied: Set<PermissionKind> = emptySet()): List<PermissionRowUi> {
        fun runtime(kind: PermissionKind) = if (kind in denied) PermissionAction.SETTINGS else PermissionAction.REQUEST
        val locationOk = s.fineLocation && s.locationService
        return listOf(
            PermissionRowUi(
                PermissionKind.LOCAL_NETWORK, "Yerel ağ erişimi", "PC'leri bulmak, uyandırmak ve kapatmak için",
                s.localNetwork, if (s.localNetwork) null else runtime(PermissionKind.LOCAL_NETWORK),
            ),
            PermissionRowUi(
                PermissionKind.LOCATION, "Konum",
                if (s.fineLocation && !s.locationService) "Konum servisi kapalı · Wi-Fi adı okunamıyor" else "Wi-Fi adı ve \"Eve gelince\" için",
                locationOk,
                when {
                    locationOk -> null
                    !s.fineLocation -> runtime(PermissionKind.LOCATION)
                    else -> PermissionAction.SETTINGS
                },
            ),
            PermissionRowUi(
                PermissionKind.EXACT_ALARM, "Tam saatli alarm",
                if (s.exactAlarm) "Zamanlayıcılar dakikasında çalışır" else "Kapalı · zamanlayıcılar gecikebilir",
                s.exactAlarm, if (s.exactAlarm) null else PermissionAction.SETTINGS, critical = !s.exactAlarm,
            ),
            PermissionRowUi(
                PermissionKind.NOTIFICATIONS, "Bildirimler", "Başarısız zamanlayıcı ve çalışma bildirimleri",
                s.notifications, if (s.notifications) null else runtime(PermissionKind.NOTIFICATIONS),
            ),
            PermissionRowUi(
                PermissionKind.BATTERY, "Pil",
                if (s.batteryUnrestricted) "Kısıtlanmamış · arka planda zamanlayıcılar güvende" else "Kısıtlanmamış olmalı · yoksa zamanlayıcılar gecikebilir",
                s.batteryUnrestricted, if (s.batteryUnrestricted) null else PermissionAction.SETTINGS,
            ),
        )
    }

    /** Runtime istenen izinler (REQUEST). */
    fun runtimePermissions(kind: PermissionKind): Array<String> = when (kind) {
        PermissionKind.LOCAL_NETWORK -> arrayOf(NetPermissions.LOCAL_NETWORK)
        PermissionKind.LOCATION -> NetPermissions.LOCATION
        PermissionKind.NOTIFICATIONS -> arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else -> emptyArray()
    }
}

fun interface PermissionSource {
    fun snapshot(): PermissionSnapshot
}

class AndroidPermissionSource @Inject constructor(@ApplicationContext private val context: Context) : PermissionSource {
    override fun snapshot() = PermissionSnapshot(
        localNetwork = NetPermissions.hasLocalNetwork(context),
        fineLocation = NetPermissions.hasFineLocation(context),
        locationService = NetPermissions.isLocationServiceOn(context),
        exactAlarm = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    )
}

/**
 * İlgili sistem ekranları (S10 doğrulaması, developer.android.com):
 * - Tam saatli alarm: `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (paket uri'li); USE_EXACT_ALARM kurulumda verilir,
 *   açılmazsa uygulama bilgisi.
 * - Pil: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (manifestte REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) → sistem
 *   dialog'u; açılmazsa `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
 * - Konum servisi kapalıysa `ACTION_LOCATION_SOURCE_SETTINGS`; bildirim `ACTION_APP_NOTIFICATION_SETTINGS`;
 *   reddedilmiş runtime izinler (yerel ağ, konum) `ACTION_APPLICATION_DETAILS_SETTINGS`.
 */
object PermissionIntents {
    fun settings(context: Context, kind: PermissionKind, s: PermissionSnapshot): List<Intent> {
        val pkg = Uri.parse("package:${context.packageName}")
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        return when (kind) {
            PermissionKind.EXACT_ALARM -> listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg), details)
            PermissionKind.BATTERY -> listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            PermissionKind.NOTIFICATIONS -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), details)
            PermissionKind.LOCATION -> if (s.fineLocation && !s.locationService) listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) else listOf(details)
            PermissionKind.LOCAL_NETWORK -> listOf(details)
        }
    }

    /** Sırayla dener; ilk açılabilen ekran açılır. */
    fun open(context: Context, intents: List<Intent>) {
        for (i in intents) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
