package com.kdgames.wake_on_lan.tile

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.TileMode
import com.kdgames.wake_on_lan.domain.status.StateLabels

/** Slot → cihaz (DataStore `tileSlots`). Silinmiş cihaz = atanmamış. */
object TileBinder {
    fun deviceFor(slot: Int, slots: List<Long?>, devices: List<Device>): Device? {
        val id = slots.getOrNull(slot) ?: return null
        return devices.firstOrNull { it.id == id }
    }
}

/** `Tile.STATE_*` karşılığı (saf testler için ayrı). */
enum class TileVisual { ACTIVE, INACTIVE, UNAVAILABLE }

data class TileContent(val title: String, val subtitle: String, val visual: TileVisual, val iconKey: String?)

/** Spec 10.8: tile başlığı, alt yazısı ve durumu. */
object TileLabels {
    const val CONFIRM = "Kapatmak için tekrar dokun"

    fun of(slot: Int, device: Device?, state: DeviceState?, wifi: Boolean, permission: Boolean, confirmPending: Boolean = false): TileContent {
        if (device == null) return TileContent("WoL slot ${slot + 1}", "Cihaz seç", TileVisual.INACTIVE, null)
        fun content(subtitle: String, visual: TileVisual) = TileContent(device.name, subtitle, visual, device.iconKey)
        return when {
            !permission -> content("İzin yok", TileVisual.UNAVAILABLE)
            !wifi -> content("Wi-Fi yok", TileVisual.UNAVAILABLE)
            state == null -> content("Kontrol ediliyor…", TileVisual.INACTIVE)
            state is DeviceState.On -> content(if (confirmPending) CONFIRM else on(state.uptimeSec), TileVisual.ACTIVE)
            state is DeviceState.Off -> content(StateLabels.offLabel(state.lastAction), TileVisual.INACTIVE)
            state is DeviceState.Waking -> content(if (state.elapsedSec > 0) "Uyanıyor ${state.elapsedSec} sn" else "Uyanıyor…", TileVisual.INACTIVE)
            state is DeviceState.PowerTransition -> content(StateLabels.transitionLabel(state.action), TileVisual.INACTIVE)
            else -> content("—", TileVisual.INACTIVE)
        }
    }

    /** K9: "Açık · 2 sa" süresi agent `uptimeSec`'ten; agent yoksa yalnız "Açık". */
    fun on(uptimeSec: Long?): String {
        val s = uptimeSec ?: return "Açık"
        return when {
            s < 60 -> "Açık"
            s < 3_600 -> "Açık · ${s / 60} dk"
            s < 86_400 -> "Açık · ${s / 3_600} sa"
            else -> "Açık · ${s / 86_400} gün"
        }
    }
}

/** Tile'a dokununca ne olacağı (spec 10.8, K12). */
enum class TileClick { OPEN_SLOT_SETTINGS, NOTHING, WAKE, POWER_OVERLAY, POWER_DIALOG, DOUBLE_TAP }

object TileClicks {
    fun decide(device: Device?, state: DeviceState?, wifi: Boolean, permission: Boolean, mode: TileMode): TileClick = when {
        device == null -> TileClick.OPEN_SLOT_SETTINGS
        !permission || !wifi -> TileClick.NOTHING
        // Kapalı → doğrudan uyandır (kilit ekranında da); MAC yoksa uyandırılamaz.
        state is DeviceState.Off -> if (device.mac != null) TileClick.WAKE else TileClick.NOTHING
        state is DeviceState.On -> when (mode) {
            TileMode.A -> TileClick.POWER_OVERLAY
            TileMode.B -> TileClick.POWER_DIALOG
            TileMode.C -> TileClick.DOUBLE_TAP
        }
        // Geçişte (uyanıyor / kapanıyor) ve durum bilinmezken dokunuş yok sayılır.
        else -> TileClick.NOTHING
    }
}

/** Mod C: ilk dokunuş 3 sn'lik onay penceresi açar; pencere içindeki ikinci dokunuş Kapat. */
class DoubleTap(private val windowMs: Long = WINDOW_MS) {
    private var armedAt: Long? = null

    fun pending(now: Long): Boolean = armedAt?.let { now - it in 0 until windowMs } == true

    /** true = ikinci dokunuş (komutu gönder); false = pencere şimdi açıldı. */
    fun tap(now: Long): Boolean {
        if (pending(now)) {
            armedAt = null
            return true
        }
        armedAt = now
        return false
    }

    fun reset() {
        armedAt = null
    }

    companion object {
        const val WINDOW_MS = 3_000L
    }
}
