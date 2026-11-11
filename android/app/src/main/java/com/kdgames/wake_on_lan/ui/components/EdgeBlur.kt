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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.theme.WolColors
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/** Kenar bulanıklığı kaynaklarının ekran dışına taşma payı (bulanıklık yarıçapının ~3 katı). */
val EdgeOverscan = 56.dp

/**
 * Kutuyu her yönden [by] kadar büyütür (yerleşimde yer kaplamadan). Bulanıklık kaynağı ekran kenarının ötesinde
 * de piksel taşır; kenarda örnekleme ekran dışını siyah/koyu görmez.
 */
fun Modifier.overscan(by: Dp): Modifier = layout { measurable, constraints ->
    val px = by.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + 2 * px, constraints.maxHeight + 2 * px))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-px, -px) }
}

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
                    // Arka plan + içerik birlikte bulanıklaşır (opak sonuç). Yalnız içeriği bulanıklaştırmak yarı saydam
                    // camı iki kez çizip bölgeyi açıyor, sınırda çizgi bırakıyordu (S10 cihaz testi, Kaan).
                    // Ekran kenarındaki koyu leke için kaynaklar ekran dışına taşırılır ([overscan]).
                    backgroundColor(WolColors.Background)
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
