package com.kdgames.wake_on_lan.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.cssLinearGradient
import com.kdgames.wake_on_lan.ui.theme.motionSpec
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
 * Varyant değişimi (ör. Normal → Strong) parlaklık, blur ve vurgu dahil `snappy` spring ile akar.
 * Strong'daki köşe parlaması 3 sn'lik döngüyle yavaşça nefes alır (hareket azaltılmışsa sabit).
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
    val reduceMotion = LocalReduceMotion.current
    val spec = motionSpec<Float>(SpringToken.Snappy, reduceMotion)
    val topAlpha by animateFloatAsState(variant.topAlpha, spec, label = "glassTop")
    val bottomAlpha by animateFloatAsState(variant.bottomAlpha, spec, label = "glassBottom")
    val blur by animateDpAsState(variant.blur, motionSpec(SpringToken.Snappy, reduceMotion), label = "glassBlur")
    val accentStrength by animateFloatAsState(
        targetValue = if (variant == GlassVariant.Strong && accent.isSpecified) 1f else 0f,
        animationSpec = spec,
        label = "glassAccent",
    )
    // Renk değişmesin diye vurgu kaybolurken son belirtilen renk korunur.
    val lastAccent = remember { AccentHolder() }
    if (accent.isSpecified) lastAccent.color = accent
    val breath = accentBreath(enabled = accentStrength > 0f && !reduceMotion)
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
                            blurRadius(blur)
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
                        0f to Color.White.copy(alpha = topAlpha),
                        1f to Color.White.copy(alpha = bottomAlpha),
                    ),
                )
                if (accentStrength > 0f) {
                    val alpha = GlassTokens.ACCENT_ALPHA * accentStrength * (1f + GlassTokens.ACCENT_BREATH * breath.value)
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(lastAccent.color.copy(alpha = alpha.coerceIn(0f, 1f)), Color.Transparent),
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

/** -1..1 arası, 3 sn'lik sinüs benzeri nefes; kapalıyken 0'a akar. Okuma çizim aşamasında yapılır. */
@Composable
private fun accentBreath(enabled: Boolean): State<Float> {
    val value = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        if (enabled) {
            value.animateTo(
                targetValue = 1f,
                animationSpec = tween(GlassTokens.ACCENT_BREATH_MS / 4, easing = FastOutSlowInEasing),
            )
            value.animateTo(
                targetValue = -1f,
                animationSpec = infiniteRepeatable(
                    tween(GlassTokens.ACCENT_BREATH_MS / 2, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
            )
        } else {
            value.animateTo(0f, SpringToken.Gentle.spec())
        }
    }
    return value.asState()
}

/** Kompozisyonda state yazmadan son vurgu rengini tutar (yalnız çizimde okunur). */
private class AccentHolder {
    var color: Color = Color.Transparent
}
