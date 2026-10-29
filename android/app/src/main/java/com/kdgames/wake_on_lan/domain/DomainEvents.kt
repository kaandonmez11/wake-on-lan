package com.kdgames.wake_on_lan.domain

import android.util.Log
import com.kdgames.wake_on_lan.domain.model.PowerAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Koordinatörlerin kullanıcıya gösterilecek tek seferlik olayları (uyarı, onay). UI toast/çip olarak gösterir. */
sealed interface DomainEvent {
    val deviceId: Long
    val text: String

    data class WakeSucceeded(override val deviceId: Long, val durationSec: Int) : DomainEvent {
        override val text get() = "Uyandı · $durationSec sn"
    }

    data class WakeFailed(override val deviceId: Long) : DomainEvent {
        override val text get() = "Uyanmadı · BIOS'ta WoL ve ağ kartında Magic Packet ayarını kontrol et"
    }

    data class NoMac(override val deviceId: Long) : DomainEvent {
        override val text get() = "MAC bekleniyor · PC açıkken otomatik bulunur"
    }

    data class NoWifi(override val deviceId: Long) : DomainEvent {
        override val text get() = "Wi-Fi yok"
    }

    data class Locked(override val deviceId: Long) : DomainEvent {
        override val text get() = "Kilitlendi"
    }

    data class PowerDone(override val deviceId: Long, val action: PowerAction) : DomainEvent {
        override val text get() = when (action) {
            PowerAction.SLEEP -> "Uyudu"
            PowerAction.HIBERNATE -> "Hazırda bekletildi"
            PowerAction.RESTART -> "Yeniden başladı"
            else -> "Kapandı"
        }
    }

    /** 90 sn'de kapanmadı (spec 9.3); RESTART'ta 180 sn'de geri gelmedi. */
    data class PowerTimedOut(override val deviceId: Long, val action: PowerAction, val graceful: Boolean) : DomainEvent {
        override val text get() = when (action) {
            PowerAction.RESTART -> "Yeniden başlamadı"
            PowerAction.SLEEP -> "Uyumadı"
            PowerAction.HIBERNATE -> "Hazırda bekletilemedi"
            else -> "Kapanmadı"
        } + if (graceful) " · Açık bir program engelliyor olabilir" else ""
    }

    /** K12 ve spec 9.3 madde 3–4: komut gönderilmedi. */
    data class CommandRejected(override val deviceId: Long, val reason: Reason) : DomainEvent {
        enum class Reason(val text: String) {
            NOT_ON("Cihaz açık değil"),
            NO_AGENT("Agent bulunamadı"),
            HIBERNATE_OFF("Hazırda bekletme bu PC'de kapalı"),
            BUSY_LOCAL("Başka bir işlem sürüyor"),
        }
        override val text get() = reason.text
    }

    /** Agent hata kodu ya da cevapsızlık. */
    data class AgentFailed(override val deviceId: Long, val code: String) : DomainEvent {
        override val text get() = when (code) {
            "NO_REPLY" -> "Agent cevap vermiyor"
            "UNSUPPORTED" -> "Bu PC bu komutu desteklemiyor"
            "BUSY" -> "PC başka bir komutu işliyor"
            "BAD_SIG" -> "İmza doğrulanamadı"
            "UNKNOWN_PHONE" -> "PC bu telefonu tanımıyor · yeniden eşleşiliyor"
            else -> "Agent hatası: $code"
        }
    }

    /** AgentLinker bilgileri (eşleşme, IP değişimi, MAC doldurma, K6). */
    data class AgentInfo(override val deviceId: Long, override val text: String) : DomainEvent
}

@Singleton
class DomainEvents @Inject constructor() {
    private val flow = MutableSharedFlow<DomainEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DomainEvent> = flow.asSharedFlow()

    fun emit(event: DomainEvent) {
        flow.tryEmit(event)
    }
}

/** JVM birim testlerinde android.util.Log yok; uyarı loglaması orada sessizce atlanır. */
internal fun warn(tag: String, message: String, error: Throwable? = null) {
    runCatching { Log.w(tag, message, error) }
}
