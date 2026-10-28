package com.kdgames.wake_on_lan.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.CachedStateKind
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RunResult
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import com.kdgames.wake_on_lan.domain.model.ShutdownMode

// Spec 6. Canlı durum (açık/kapalı/uyanıyor) burada saklanmaz; StatusEngine hesaplar.

@Entity(indices = [Index(value = ["mac"], unique = true)])
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** trim, 1..32, büyük/küçük harf duyarsız benzersiz. */
    val name: String,
    /** Son bilinen IPv4. */
    val ip: String,
    /** "AA:BB:CC:DD:EE:FF"; null = "MAC bekleniyor". */
    val mac: String?,
    /** Agent / NetBIOS / mDNS'ten. */
    val hostname: String? = null,
    /** Spec 11.6 anahtarı. */
    val iconKey: String,
    /** 0..7, [com.kdgames.wake_on_lan.ui.theme.DevicePalette]. */
    val colorIndex: Int,
    val probePorts: List<Int> = DEFAULT_PROBE_PORTS,
    /** Ekleme sırası (K14). */
    val sortOrder: Int = 0,
    /** UUID; null = agent yok / eşleşmedi. */
    val agentId: String? = null,
    /** KeyVault'taki sarmalanmış oturum anahtarının takma adı. */
    val agentKeyAlias: String? = null,
    val agentPort: Int = 40900,
    val hibernateAvailable: Boolean = false,
    val shutdownMode: ShutdownMode = ShutdownMode.FORCE,
) {
    companion object {
        val DEFAULT_PROBE_PORTS = listOf(3389, 445, 135)
    }
}

@Entity(
    foreignKeys = [ForeignKey(entity = Device::class, parentColumns = ["id"], childColumns = ["deviceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("deviceId")],
)
data class WakeAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val startedAt: Long,
    val success: Boolean,
    val durationSec: Int?,
    val source: AttemptSource,
)

@Entity(
    foreignKeys = [ForeignKey(entity = Device::class, parentColumns = ["id"], childColumns = ["deviceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("deviceId")],
)
data class Schedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val action: PowerAction,
    val type: ScheduleType,
    /** TIME: bit0 = Pzt … bit6 = Paz. */
    val daysMask: Int = 0,
    /** TIME: 0..1439. */
    val minuteOfDay: Int? = null,
    /** WIFI_CONNECT. */
    val ssid: String? = null,
    val enabled: Boolean = true,
    val skippedOccurrence: Long? = null,
    val skipNextEvent: Boolean = false,
)

@Entity(
    foreignKeys = [ForeignKey(entity = Schedule::class, parentColumns = ["id"], childColumns = ["scheduleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scheduleId")],
)
data class ScheduleRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    /** Planlanan zaman (WIFI için tetiklenme anı). */
    val occurrence: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val result: RunResult,
    /** `[{at, outcome, reason}]` (spec 9.4). */
    val attemptsJson: String = "[]",
)

/**
 * K7: yalnız gösterim amaçlı son kesin durum. Açılışta `stale` gösterilir; doğruluk kaynağı
 * değildir, kararı yine StatusEngine verir.
 */
@Entity(
    foreignKeys = [ForeignKey(entity = Device::class, parentColumns = ["id"], childColumns = ["deviceId"], onDelete = ForeignKey.CASCADE)],
)
data class DeviceStateCache(
    @PrimaryKey val deviceId: Long,
    val kind: CachedStateKind,
    /** K9: son geçiş SLEEP/HIBERNATE ise "Uyuyor"/"Hazırda". */
    val lastPowerAction: PowerAction? = null,
    val uptimeSec: Long? = null,
    val at: Long,
)
