package com.kdgames.wake_on_lan.domain.status

import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.WakeStep

/** Spec 10.2 durum metinleri (hero çipi; kart satırı S06'da buna dayanır). */
object StateLabels {
    fun of(state: DeviceState?): String = when (state) {
        null -> "—"
        is DeviceState.On -> if (state.stale) "Açık · kontrol ediliyor" else "Açık · ${state.pingMs} ms"
        is DeviceState.Off -> offLabel(state.lastAction) + if (state.stale) " · kontrol ediliyor" else ""
        is DeviceState.Waking -> "Uyanıyor · ${stepLabel(state.step)} · ${state.elapsedSec} sn"
        is DeviceState.PowerTransition -> transitionLabel(state.action)
    }

    /** K9: son geçiş SLEEP → "Uyuyor", HIBERNATE → "Hazırda", diğer → "Kapalı". */
    fun offLabel(lastAction: PowerAction?) = when (lastAction) {
        PowerAction.SLEEP -> "Uyuyor"
        PowerAction.HIBERNATE -> "Hazırda"
        else -> "Kapalı"
    }

    fun transitionLabel(action: PowerAction) = when (action) {
        PowerAction.SLEEP -> "Uyutuluyor…"
        PowerAction.HIBERNATE -> "Hazırda bekletiliyor…"
        PowerAction.RESTART -> "Yeniden başlıyor…"
        else -> "Kapanıyor…"
    }

    fun stepLabel(step: WakeStep) = when (step) {
        WakeStep.PACKET -> "Paket"
        WakeStep.REPLY -> "Ping yanıtı"
        WakeStep.PORT -> "Port açık"
    }
}
