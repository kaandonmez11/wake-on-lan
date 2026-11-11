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
                    // Yalnız içerik katmanı bulanıklaşır, zemini saydam: canlı arka plan (zaten yumuşak gradient)
                    // bulanıklaştırılmadan alttan görünür. Arka planı da bulanıklaştırmak ekran kenarında koyu,
                    // basamaklı bir leke bırakıyordu (S10 cihaz testi, Kaan).
                    backgroundColor(Color.Transparent)
                    // Kenar pikselleri tekrarlanır: ekran dışı siyah sayılmaz, sağ/sol kenarda kararma olmaz.
                    blurredEdgeTreatment(androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle)
                    // Yumuşak (ease-in-out) azalma: bulanık bölgeden net içeriğe çizgisiz geçiş.
                    progressive(
                        HazeProgressive.verticalGradient(
                            easing = androidx.compose.animation.core.CubicBezierEasing(0.45f, 0f, 0.55f, 1f),
                            startIntensity = if (top) 1f else 0f,
                            endIntensity = if (top) 0f else 1f,
                        ),
                    )
                },
            )
            .drawBehind {
                // Okunurluk için kenara doğru hafif kararma.
                val dark = WolColors.Background.copy(alpha = 0.40f)
                val mid = WolColors.Background.copy(alpha = 0.12f)
                drawRect(Brush.verticalGradient(if (top) listOf(dark, mid, Color.Transparent) else listOf(Color.Transparent, mid, dark)))
            },
    )
}
