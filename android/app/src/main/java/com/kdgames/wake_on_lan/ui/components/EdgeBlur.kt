package com.kdgames.wake_on_lan.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.theme.WolColors
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * iOS gibi ekranın üst ve alt kenarında kademeli bulanıklık (S10, Kaan): içerik kenara yaklaştıkça bulanıklaşıp
 * hafifçe kararır. Altındaki kaynakları ([state]) bulanıklaştırır; üstüne çizilen yüzen nav etkilenmez.
 * Üstte durum çubuğu + [extra], altta gezinme çubuğu + [extra] yüksekliğinde.
 */
@Composable
fun EdgeBlur(
    state: HazeState,
    top: Boolean,
    extra: Dp,
    modifier: Modifier = Modifier,
    /** Hangi kaynak katmanları (zIndex) bulanıklaşsın. */
    include: (Float) -> Boolean = { true },
) {
    val inset = if (top) WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier
            .fillMaxWidth()
            .height(inset + extra)
            .hazeBlur(
                input = HazeInput.Sources(state, dev.chrisbanes.haze.HazeSourceSelection.All.where { include(it.zIndex) }),
                style = HazeBlurStyle {
                    blurRadius(18.dp)
                    noiseFactor(0f)
                    backgroundColor(WolColors.Background)
                    progressive(HazeProgressive.verticalGradient(startIntensity = if (top) 1f else 0f, endIntensity = if (top) 0f else 1f))
                },
            )
            .drawBehind {
                // Okunurluk için kenara doğru hafif kararma.
                val dark = WolColors.Background.copy(alpha = 0.45f)
                drawRect(Brush.verticalGradient(if (top) listOf(dark, Color.Transparent) else listOf(Color.Transparent, dark)))
            },
    )
}
