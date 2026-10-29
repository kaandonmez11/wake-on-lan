package com.kdgames.wake_on_lan.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.kdgames.wake_on_lan.ui.theme.Haptics
import com.kdgames.wake_on_lan.ui.theme.LocalHaptics
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.motionSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Spec 12.6: 1.5 sn, hafif ease-in (başta biraz daha yavaş). */
const val HOLD_DURATION_MS = 1_500
private val HoldEasing = CubicBezierEasing(0.35f, 0f, 0.85f, 1f)

/** Spec 12.7: dolum %15'lik dilimlerde LOW_TICK, şiddet 0.2 → 0.8. */
private const val TICK_STEP = 0.15f

/**
 * Basılı-tut durumu: [progress] 0..1 (dolum), [commit] tamamlanma vurgusu 1 → 1.05 → 1,
 * [pressed] basma ölçeği için.
 */
@Stable
class HoldState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: Haptics?,
    private val reduceMotion: Boolean,
) {
    val progress = Animatable(0f)
    val commit = Animatable(1f)
    var pressed by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var lastTick = 0

    /** Basılı tutuluyor mu (ipucu satırı). Yalnız basma durumunu okur; dolum değeri kompozisyonu tetiklemez. */
    val holding: Boolean get() = pressed

    internal fun press(onCommit: () -> Unit) {
        pressed = true
        job?.cancel()
        job = scope.launch {
            // Kaldığı yerden devam: kalan süre orantılı.
            val remaining = ((1f - progress.value) * HOLD_DURATION_MS).toInt().coerceAtLeast(1)
            lastTick = (progress.value / TICK_STEP).toInt()
            progress.animateTo(1f, tween(remaining, easing = HoldEasing)) {
                val slice = (value / TICK_STEP).toInt()
                if (slice > lastTick) {
                    lastTick = slice
                    haptics?.lowTick(0.2f + 0.6f * value)
                }
            }
            // Tamamlandı.
            pressed = false
            haptics?.thud()
            onCommit()
            launch { commit.animateTo(1.05f, motionSpec(SpringToken.Quick, reduceMotion)); commit.animateTo(1f, motionSpec(SpringToken.Bouncy, reduceMotion)) }
            progress.animateTo(0f, motionSpec(SpringToken.Smooth, reduceMotion))
        }
    }

    internal fun release() {
        if (!pressed) return
        pressed = false
        job?.cancel()
        if (progress.value > 0.02f) haptics?.cancel()
        // Anında sıfırlanmaz: snappy spring ile hızlıca geri boşalır (~250 ms).
        job = scope.launch { progress.animateTo(0f, motionSpec(SpringToken.Snappy, reduceMotion)) }
    }
}

@Composable
fun rememberHoldState(): HoldState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val reduceMotion = LocalReduceMotion.current
    return remember(scope) { HoldState(scope, haptics, reduceMotion) }
}

/**
 * Basılı tutarak onay (spec 12.6). Erişilebilirlik: TalkBack'te çift dokunuş [onAccessibilityClick]'e gider
 * (onay iletişim kutusu, spec 10.10).
 */
@Composable
fun Modifier.holdToConfirm(
    state: HoldState,
    enabled: Boolean,
    label: String,
    onCommit: () -> Unit,
    onAccessibilityClick: () -> Unit,
): Modifier {
    val commit by rememberUpdatedState(onCommit)
    val a11y by rememberUpdatedState(onAccessibilityClick)
    return this
        .semantics { if (enabled) onClick(label) { a11y(); true } }
        .then(
            if (!enabled) Modifier
            else Modifier.pointerInput(state) {
                awaitEachGesture {
                    // Olaylar tüketilir: karttaki güç butonunu basılı tutmak kartı "seçmesin" (S06 cihaz testi).
                    awaitFirstDown(requireUnconsumed = false).consume()
                    state.press { commit() }
                    waitForUpOrCancellation()?.consume()
                    state.release()
                }
            },
        )
}
