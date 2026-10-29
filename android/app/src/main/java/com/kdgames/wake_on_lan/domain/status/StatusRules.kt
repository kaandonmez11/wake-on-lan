package com.kdgames.wake_on_lan.domain.status

import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.domain.model.Verdict

/** Spec 9.1 karar kuralları; saf. */
object StatusRules {
    const val NEGATIVE_ROUNDS_TO_OFF = 2
    const val NEGATIVE_ROUNDS_TO_OFF_IN_TRANSITION = 3
    const val STALE_AFTER_MS = 15_000L

    /**
     * 1. S1 imzalı → doğrulanmış açık. 2. S2 + MAC eşleşiyor → doğrulanmış açık.
     * 3. S2 + farklı MAC → negatif, **S3/S4 de yok sayılır** (K8). 4. S3/S4 (ya da MAC'i bilinmeyen cihazda S2) → doğrulanmamış açık.
     * 5. Hiçbiri → negatif.
     */
    fun decide(signals: RoundSignals, deviceMac: String?, agentConfigured: Boolean): Verdict {
        val s1 = signals.s1
        if (s1 != null && s1.ok) return Verdict.Positive(true, true, s1.ms.toIntMs(), s1.uptimeSec)

        val agentReachable = !agentConfigured
        val s2 = signals.s2
        val s2Mac = s2?.mac
        if (s2 != null && deviceMac != null && s2Mac != null) {
            return if (s2Mac.equals(deviceMac, ignoreCase = true)) Verdict.Positive(true, agentReachable, s2.ms.toIntMs(), null)
            else Verdict.Negative(macMismatch = true)
        }

        val positives = listOfNotNull(
            s2?.ms,
            signals.s3?.takeIf { it.alive }?.ms,
            signals.s4?.takeIf { it.reply }?.ms,
        )
        if (positives.isNotEmpty()) return Verdict.Positive(false, agentReachable, positives.min().toIntMs(), null)
        return Verdict.Negative()
    }

    private fun Long.toIntMs() = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Cihaz başına kesin durum izleyici (geçiş katmanı hariç). Pozitif kanıt güçlü: tek cevap = açık.
 * Negatif zayıf: ancak [threshold] ardışık negatif turda kapalı.
 */
data class Tracker(
    val base: DeviceState,
    /** Son kesin karar anı; null = açılıştan beri hiç tur yok (önbellekten gelen durum). */
    val decidedAt: Long?,
    val negatives: Int = 0,
) {
    init {
        require(base is DeviceState.On || base is DeviceState.Off) { "Tracker yalnız On/Off tutar" }
    }

    fun next(verdict: Verdict, now: Long, threshold: Int = StatusRules.NEGATIVE_ROUNDS_TO_OFF): Tracker = when (verdict) {
        is Verdict.Positive -> Tracker(
            DeviceState.On(verdict.pingMs, verdict.verified, verdict.agentReachable, uptimeSec = verdict.uptimeSec ?: (base as? DeviceState.On)?.uptimeSec),
            decidedAt = now,
        )
        is Verdict.Negative -> {
            val count = negatives + 1
            if (count >= threshold) {
                val lastAction = (base as? DeviceState.Off)?.lastAction
                val tried = (base as? DeviceState.Off)?.tried ?: true
                Tracker(DeviceState.Off(tried, lastAction = lastAction), decidedAt = now, negatives = count)
            } else copy(negatives = count)
        }
    }

    /** Koordinatörün kesin kararı (ör. kapanma tamamlandı → Off(lastAction)). */
    fun settle(state: DeviceState, now: Long) = Tracker(state, decidedAt = now)

    fun withTried(tried: Boolean): Tracker =
        (base as? DeviceState.Off)?.let { copy(base = it.copy(tried = tried)) } ?: this

    /** Gösterilecek durum: 15 sn'den eski ya da hiç tur görmemiş karar `stale`. */
    fun view(now: Long): DeviceState {
        val stale = decidedAt == null || now - decidedAt > StatusRules.STALE_AFTER_MS
        return when (val b = base) {
            is DeviceState.On -> b.copy(stale = stale)
            is DeviceState.Off -> b.copy(stale = stale)
            else -> b
        }
    }

    companion object {
        fun initial(cachedOn: Boolean?, lastAction: PowerAction?, uptimeSec: Long?, tried: Boolean) = Tracker(
            base = if (cachedOn == true) DeviceState.On(0, verified = false, agentReachable = true, uptimeSec = uptimeSec)
            else DeviceState.Off(tried, lastAction = lastAction),
            decidedAt = null,
        )
    }
}
