package com.kdgames.wake_on_lan.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.theme.GlassTokens
import com.kdgames.wake_on_lan.ui.theme.GlassVariant
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.cssLinearGradient
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * Cam yüzeylerin bulanıklaştıracağı kaynakların durumu. Arka plan zIndex 0, kaydırılan içerik
 * zIndex 1 ile `hazeSource` olur; içerikteki cam yalnız arka planı, nav dock ikisini birden okur.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

private val SaturationFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(GlassTokens.SATURATION) })

/**
 * Spec 11.2 cam yüzey: arkası blur 24 + doygunluk 1.6, #0C0C1E %40 zemin, üstünde beyaz
 * %18→%5 (145°) gradient, 1dp beyaz %14 çerçeve + üst kenarda %35 vurgu, gölge 0/12/32 siyah %28.
 *
 * @param accent [GlassVariant.Strong] için sağ üstten gelen cihaz rengi (%30 radial).
 */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    variant: GlassVariant = GlassVariant.Normal,
    accent: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = LocalHazeState.current
    Box(
        modifier = modifier
            .dropShadow(
                shape = shape,
                shadow = Shadow(
                    radius = GlassTokens.ShadowRadius,
                    color = GlassTokens.ShadowColor,
                    offset = DpOffset(0.dp, GlassTokens.ShadowOffsetY),
                ),
            )
            .clip(shape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeBlur(
                        input = HazeInput.Sources(hazeState),
                        style = HazeBlurStyle {
                            blurRadius(variant.blur)
                            noiseFactor(0f)
                            backgroundColor(WolColors.Background)
                            colorEffects(listOf(HazeColorEffect.colorFilter(SaturationFilter)))
                        },
                    )
                } else {
                    Modifier
                },
            )
            .drawBehind {
                drawRect(GlassTokens.Tint)
                drawRect(
                    cssLinearGradient(
                        GlassTokens.ANGLE_DEG,
                        size,
                        0f to Color.White.copy(alpha = variant.topAlpha),
                        1f to Color.White.copy(alpha = variant.bottomAlpha),
                    ),
                )
                if (variant == GlassVariant.Strong && accent.isSpecified) {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = GlassTokens.ACCENT_ALPHA), Color.Transparent),
                            center = Offset(size.width, 0f),
                            radius = size.maxDimension * 0.75f,
                        ),
                    )
                }
            }
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to GlassTokens.HighlightColor,
                    0.18f to GlassTokens.BorderColor,
                    1f to GlassTokens.BorderColor,
                ),
                shape = shape,
            ),
        content = content,
    )
}
