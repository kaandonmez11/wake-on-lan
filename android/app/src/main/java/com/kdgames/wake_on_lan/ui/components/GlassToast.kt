package com.kdgames.wake_on_lan.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.REDUCED_CROSSFADE_MS
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay

enum class ToastKind { Info, Success, Warning }

data class Toast(val id: Long, val text: String, val kind: ToastKind, val symbol: String?)

@Stable
class ToastHostState {
    var current by mutableStateOf<Toast?>(null)
        private set
    private var counter = 0L
    private val queue = ArrayDeque<Toast>()

    fun show(text: String, kind: ToastKind = ToastKind.Info, symbol: String? = null) {
        val t = Toast(++counter, text, kind, symbol)
        if (current == null) current = t else queue.addLast(t)
    }

    internal fun next() {
        current = queue.removeFirstOrNull()
    }
}

private const val TOAST_MS = 2_500L

/** Kısa cam bildirimleri (S06 madde 11): yukarıdan kayıp gelir, süre dolunca yukarı çözülür. */
@Composable
fun GlassToastHost(state: ToastHostState, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val toast = state.current
    LaunchedEffect(toast?.id) {
        if (toast != null) {
            delay(TOAST_MS)
            state.next()
        }
    }
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedContent(
            targetState = toast,
            contentKey = { it?.id },
            transitionSpec = {
                if (reduceMotion) fadeIn(tween(REDUCED_CROSSFADE_MS)) togetherWith fadeOut(tween(REDUCED_CROSSFADE_MS))
                else (slideInVertically(SpringToken.Smooth.spec(IntOffset.VisibilityThreshold)) { -it } + fadeIn(SpringToken.Smooth.spec()) +
                    scaleIn(SpringToken.Bouncy.spec(), initialScale = 0.92f)) togetherWith
                    (slideOutVertically(SpringToken.Snappy.spec(IntOffset.VisibilityThreshold)) { -it / 2 } + fadeOut(SpringToken.Snappy.spec()) +
                        scaleOut(SpringToken.Snappy.spec(), targetScale = 0.96f))
            },
            label = "toast",
        ) { t ->
            if (t != null) {
                GlassSurface(shape = RoundedCornerShape(50), backdropBlur = true, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = when (t.kind) {
                            ToastKind.Success -> WolColors.Success
                            ToastKind.Warning -> WolColors.Danger
                            ToastKind.Info -> WolColors.TextPrimary
                        }
                        t.symbol?.let { SymbolIcon(it, contentDescription = null, size = 18.dp, tint = tint) }
                        Text(t.text, style = WolType.BodySmall, color = if (t.kind == ToastKind.Warning) WolColors.DangerLight else Color.White)
                    }
                }
            }
        }
    }
}
