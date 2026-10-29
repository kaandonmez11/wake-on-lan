package com.kdgames.wake_on_lan.ui.devices

import androidx.compose.ui.graphics.Color
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.repo.WakeHistory
import com.kdgames.wake_on_lan.domain.agent.AgentIssue
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.status.StateLabels
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.WolColors

/** Bir cihazın ekrandaki her şeyi: kayıt + StatusEngine kararı + geçmiş + agent sorunu. */
data class DeviceUi(
    val device: Device,
    val state: DeviceState?,
    val history: WakeHistory = WakeHistory(emptyList()),
    val issue: AgentIssue? = null,
) {
    val id: Long get() = device.id
    val palette: DevicePalette get() = DevicePalette.of(device.colorIndex)
    val isOn: Boolean get() = state is DeviceState.On
    val isWaking: Boolean get() = state is DeviceState.Waking
    val isBusy: Boolean get() = state is DeviceState.Waking || state is DeviceState.PowerTransition
}

data class DevicesUiState(
    val wifi: WifiState = WifiState.Disconnected,
    val devices: List<DeviceUi> = emptyList(),
    val selectedId: Long? = null,
) {
    /** Seçili cihaz; seçim yoksa ya da silinmişse ilk cihaz. */
    val selected: DeviceUi? get() = devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull()
}

/** Spec 11.6 ikon anahtarları. */
fun iconSymbol(key: String): String = when (key) {
    "laptop" -> Sym.laptop_mac
    "nas" -> Sym.dns
    "tv" -> Sym.tv
    "console" -> Sym.sports_esports
    "printer" -> Sym.print
    "speaker" -> Sym.speaker
    "camera" -> Sym.photo_camera
    "chip" -> Sym.memory
    "bulb" -> Sym.lightbulb
    else -> Sym.desktop_windows
}

/** Wi-Fi seviye ikonu (spec 10.1). */
fun wifiSymbol(level: Int): String = when (level.coerceIn(0, 4)) {
    0 -> Sym.signal_wifi_0_bar
    1 -> Sym.network_wifi_1_bar
    2 -> Sym.network_wifi_2_bar
    3 -> Sym.network_wifi_3_bar
    else -> Sym.signal_wifi_4_bar
}

object DeviceLabels {
    /** Hero durum çipi (spec 10.2). */
    fun chip(state: DeviceState?): String = when (state) {
        is DeviceState.Waking -> "Uyanıyor · ${state.elapsedSec} sn"
        else -> StateLabels.of(state)
    }

    /** Kart durum satırı (spec 10.2): "Açık · 3 ms", "Kapalı · 8/9", "Kapalı · yeni", "Uyuyor", "Uyanıyor…", "Kapatılıyor…". */
    fun card(state: DeviceState?, history: WakeHistory): String = when (state) {
        null -> "—"
        is DeviceState.On -> if (state.stale) "Açık · kontrol ediliyor" else "Açık · ${state.pingMs} ms"
        is DeviceState.Off -> when (state.lastAction) {
            PowerAction.SLEEP, PowerAction.HIBERNATE -> StateLabels.offLabel(state.lastAction)
            else -> "Kapalı · ${history.label}"
        }
        is DeviceState.Waking -> "Uyanıyor…"
        is DeviceState.PowerTransition -> when (state.action) {
            PowerAction.SLEEP -> "Uyutuluyor…"
            PowerAction.HIBERNATE -> "Hazırda bekletiliyor…"
            PowerAction.RESTART -> "Yeniden başlıyor…"
            else -> "Kapatılıyor…"
        }
    }

    /** Agent çipi (spec 10.2 + K6). null = çip yok. */
    fun agent(ui: DeviceUi): String = when {
        ui.issue == AgentIssue.PAIRED_ELSEWHERE -> "Başka telefona eşli"
        ui.device.agentId == null -> "Agent yok"
        ui.state is DeviceState.On && !ui.state.agentReachable -> "Agent cevap vermiyor"
        else -> "Agent"
    }

    /** Hold sırasında hero üst satırı (spec 12.6). */
    fun holdHint(action: PowerAction): String = when (action) {
        PowerAction.LOCK -> "Kilitleniyor · bırakırsan iptal"
        PowerAction.SLEEP -> "Uyutuluyor · bırakırsan iptal"
        PowerAction.HIBERNATE -> "Hazırda bekletiliyor · bırakırsan iptal"
        PowerAction.RESTART -> "Yeniden başlatılıyor · bırakırsan iptal"
        else -> "Kapatılıyor · bırakırsan iptal"
    }

    fun actionTitle(action: PowerAction): String = when (action) {
        PowerAction.LOCK -> "Kilitle"
        PowerAction.SLEEP -> "Uyku"
        PowerAction.HIBERNATE -> "Hazırda"
        PowerAction.RESTART -> "Yeniden"
        PowerAction.SHUTDOWN -> "Kapat"
        PowerAction.WAKE -> "Uyandır"
    }
}

/** Güç satırı kullanılabilirliği (K12 + spec 9.3 madde 3–4); UI ve test ortak. */
sealed interface PowerAvailability {
    data object Available : PowerAvailability

    /** Görünür ama pasif (K24: kapalı PC'de de satır görünür). [hint] null = ipucu yok. */
    data class Disabled(val hint: String?) : PowerAvailability
}

fun powerAvailability(ui: DeviceUi, action: PowerAction): PowerAvailability {
    val state = ui.state
    if (state !is DeviceState.On) return PowerAvailability.Disabled(null)
    return when {
        ui.issue == AgentIssue.PAIRED_ELSEWHERE -> PowerAvailability.Disabled("Başka telefona eşli · PC'de WolAgent.exe reset")
        ui.device.agentId == null -> PowerAvailability.Disabled("Agent bulunamadı · PC'ye agent kur")
        !state.agentReachable -> PowerAvailability.Disabled("Agent cevap vermiyor")
        action == PowerAction.HIBERNATE && !ui.device.hibernateAvailable -> PowerAvailability.Disabled("Hazırda bekletme bu PC'de kapalı")
        else -> PowerAvailability.Available
    }
}

/** Arka plan tonu (spec 12.4): seçili cihaz rengi; uyanırken amber, kapatırken kırmızımsı. */
fun backgroundTint(selected: DeviceUi?): Color {
    val ui = selected ?: return Color.Unspecified
    return when (ui.state) {
        is DeviceState.Waking -> WolColors.Amber
        is DeviceState.PowerTransition -> WolColors.Danger
        else -> ui.palette.start
    }
}
