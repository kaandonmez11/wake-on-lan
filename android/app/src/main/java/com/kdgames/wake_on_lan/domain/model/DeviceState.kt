package com.kdgames.wake_on_lan.domain.model

/** Spec 9.2: uyandırma adımları (hero kartta Paket / Ping yanıtı / Port açık). */
enum class WakeStep { PACKET, REPLY, PORT }

/**
 * Spec 9.1 + K9. Tek doğruluk kaynağı [com.kdgames.wake_on_lan.domain.status.StatusEngine];
 * UI, tile ve bildirim yalnız bunu okur.
 */
sealed interface DeviceState {
    val stale: Boolean get() = false

    data class On(
        val pingMs: Int,
        val verified: Boolean,
        /** Agent'lı cihazda S1 gelmediyse false ("Agent cevap vermiyor"). Agent'sız cihazda hep true. */
        val agentReachable: Boolean,
        override val stale: Boolean = false,
        /** K9: tile "Açık · 2 sa" için; agent yoksa null. */
        val uptimeSec: Long? = null,
    ) : DeviceState

    data class Off(
        /** false → hiç uyandırma denenmemiş ("yeni"). */
        val tried: Boolean,
        override val stale: Boolean = false,
        /** K9: SLEEP → "Uyuyor", HIBERNATE → "Hazırda", diğer → "Kapalı". */
        val lastAction: PowerAction? = null,
    ) : DeviceState

    data class Waking(val step: WakeStep, val elapsedSec: Int) : DeviceState

    data class PowerTransition(val action: PowerAction, val elapsedSec: Int) : DeviceState
}

/** Bir turun ham sinyalleri (spec 7.3). null = o sinyal bu turda yok/uygulanamadı. */
data class RoundSignals(
    val s1: AgentSignal?,
    val s2: NetBiosSignal?,
    val s3: TcpSignal?,
    val s4: IcmpSignal?,
) {
    /** S1 — imzalı STATUS. [ok]=false: cevap yok ya da imza/hata ([error]). */
    data class AgentSignal(val ok: Boolean, val ms: Long, val uptimeSec: Long? = null, val display: String? = null, val error: String? = null)

    /** S2 — NetBIOS yanıtı (yanıt yoksa sinyal null). */
    data class NetBiosSignal(val mac: String?, val hostname: String?, val ms: Long)

    data class TcpSignal(val alive: Boolean, val accepted: Boolean, val ms: Long)

    data class IcmpSignal(val reply: Boolean, val ms: Long, val unavailable: Boolean = false)

    companion object {
        val None = RoundSignals(null, null, null, null)
    }
}

/** Tur kararı (kurallar 1–5, K8). */
sealed interface Verdict {
    data class Positive(val verified: Boolean, val agentReachable: Boolean, val pingMs: Int, val uptimeSec: Long?) : Verdict

    /** [macMismatch]: S2 bu IP'de başka MAC gördü (K8) → S3/S4 yok sayıldı, IP yeniden çözümlenmeli. */
    data class Negative(val macMismatch: Boolean = false) : Verdict
}
