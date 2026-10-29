package com.kdgames.wake_on_lan.ui.devices

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.ui.components.HoldState
import com.kdgames.wake_on_lan.ui.components.holdToConfirm
import com.kdgames.wake_on_lan.ui.components.loopProgress
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import com.kdgames.wake_on_lan.ui.theme.LocalHaptics
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.motionSpec

/**
 * Hero güç halkası + orb (spec 10.2, 12.3, 12.5, 12.6).
 * Kapalı: nötr cam orb, dokun = uyandır. Uyanıyor: amber yay 1 sn'de döner. Açık: cihaz gradient'i,
 * halka 12 sn'de döner, basılı tut = Kapat (halka kırmızı dolar). Açılınca yay tamamlanır + bloom.
 */
@Composable
fun PowerOrb(
    state: DeviceState?,
    palette: DevicePalette,
    hold: HoldState,
    tapEnabled: Boolean,
    holdEnabled: Boolean,
    onTap: () -> Unit,
    onHoldCommit: () -> Unit,
    onAccessibilityHold: () -> Unit,
    modifier: Modifier = Modifier,
    /** Kapalı ama uyandırılamaz (MAC yok): soluk görünür, dokunuşa tepki vermez. */
    dimmed: Boolean = false,
    ringSize: Dp = 124.dp,
    orbSize: Dp = 96.dp,
) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val on = state is DeviceState.On
    val waking = state is DeviceState.Waking
    val transition = state is DeviceState.PowerTransition

    val orbStart by animateColorAsState(if (on) palette.start else Color.White.copy(alpha = 0.12f), motionSpec(SpringToken.Smooth, reduceMotion), label = "orbStart")
    val orbEnd by animateColorAsState(if (on) palette.end else Color.White.copy(alpha = 0.04f), motionSpec(SpringToken.Smooth, reduceMotion), label = "orbEnd")
    val glow by animateFloatAsState(if (on) 1f else 0f, motionSpec(SpringToken.Smooth, reduceMotion), label = "orbGlow")
    val arcColor by animateColorAsState(
        when {
            transition -> WolColors.Danger
            waking -> WolColors.Amber
            else -> palette.start
        },
        motionSpec(SpringToken.Snappy, reduceMotion), label = "arcColor",
    )
    // Yay uzunluğu: uyanırken/geçişte 0.25 tur; açılınca tam halkaya tamamlanır; kapalıyken yok.
    val arcSweep by animateFloatAsState(
        when {
            waking || transition -> 0.25f
            on -> 1f
            else -> 0f
        },
        motionSpec(SpringToken.Smooth, reduceMotion), label = "arcSweep",
    )
    val spin by loopProgress(if (on) 12_000 else 1_000, running = on || waking || transition, label = "orbSpin")

    // Bloom: uyanıyor → açık geçişinde 1.0 → 1.08 → 1.0 + parlama patlaması + başarı haptiği.
    val bloom = remember { Animatable(1f) }
    val burst = remember { Animatable(0f) }
    val wasWaking = remember { booleanArrayOf(false) }
    LaunchedEffect(on, waking) {
        if (on && wasWaking[0]) {
            haptics.success()
            launch {
                burst.snapTo(1f)
                burst.animateTo(0f, motionSpec(SpringToken.Gentle, reduceMotion))
            }
            bloom.animateTo(1.08f, motionSpec(SpringToken.Quick, reduceMotion))
            bloom.animateTo(1f, motionSpec(SpringToken.Bouncy, reduceMotion))
        }
        wasWaking[0] = waking
    }

    // Dolum, vurgu ve basma değerleri yalnız çizim/katman aşamasında okunur.
    val dimAlpha = animateFloatAsState(if (dimmed) 0.38f else 1f, motionSpec(SpringToken.Smooth, reduceMotion), label = "orbDim")
    val pressScale = animateFloatAsState(if (hold.pressed) 0.96f else 1f, motionSpec(if (hold.pressed) SpringToken.Quick else SpringToken.Snappy, reduceMotion), label = "orbPress")

    val gesture = when {
        tapEnabled -> Modifier.pressable(onClick = { haptics.click(); onTap() }, onClickLabel = "Uyandır")
        holdEnabled -> Modifier.holdToConfirm(hold, enabled = true, label = "Kapat", onCommit = onHoldCommit, onAccessibilityClick = onAccessibilityHold)
        else -> Modifier
    }
    val description = when {
        dimmed -> "Uyandırılamaz · MAC bekleniyor"
        tapEnabled -> "Uyandır"
        holdEnabled -> "Güç halkası · Kapat için basılı tut"
        waking -> "Uyanıyor"
        else -> "Güç"
    }

    Box(
        modifier
            .size(ringSize)
            .graphicsLayer {
                alpha = dimAlpha.value
                val s = bloom.value * hold.commit.value * pressScale.value
                scaleX = s
                scaleY = s
            }
            .semantics { contentDescription = description }
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        // Kendi katmanı + fırçalar önbellekte: dönen halka her karede yalnız bu katmanı, yeni nesne üretmeden çizer.
        Spacer(
            Modifier.size(ringSize).graphicsLayer().drawWithCache {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val ringStroke = Stroke(stroke, cap = StrokeCap.Round)
                val holdStroke = Stroke(stroke + 1.dp.toPx(), cap = StrokeCap.Round)
                val baseStroke = Stroke(stroke)
                val c = Offset(size.width / 2, size.height / 2)
                val glowBrush = Brush.radialGradient(listOf(palette.start, Color.Transparent), center = c, radius = size.minDimension / 2)
                val onBrush = Brush.sweepGradient(listOf(palette.start, palette.end, palette.start), c)
                val arcBrush = Brush.sweepGradient(listOf(arcColor.copy(alpha = 0f), arcColor), c)
                onDrawBehind {
                    drawCircle(Color.White.copy(alpha = 0.10f), radius = size.minDimension / 2 - inset, style = baseStroke)
                    // Parlama (açıkken + bloom patlaması).
                    val glowAlpha = (0.35f * glow + 0.45f * burst.value).coerceIn(0f, 1f)
                    if (glowAlpha > 0f) drawCircle(glowBrush, radius = size.minDimension / 2, alpha = glowAlpha)
                    // Durum yayı / açık halka.
                    if (arcSweep > 0.001f) {
                        rotate(spin * 360f) {
                            drawArc(if (on && !transition) onBrush else arcBrush, -90f, 360f * arcSweep, false, Offset(inset, inset), arcSize, style = ringStroke)
                        }
                    }
                    // Basılı tut dolumu: halka boyunca kırmızı.
                    val holdProgress = hold.progress.value
                    if (holdProgress > 0.001f) {
                        drawArc(WolColors.Danger, -90f, 360f * holdProgress, false, Offset(inset, inset), arcSize, style = holdStroke)
                    }
                }
            },
        )
        Canvas(Modifier.size(orbSize)) {
            drawCircle(Brush.linearGradient(listOf(orbStart, orbEnd), start = Offset.Zero, end = Offset(size.width, size.height)))
            drawCircle(Color.White.copy(alpha = 0.18f * (1f - glow) + 0.10f), style = Stroke(1.dp.toPx()))
        }
        SymbolIcon(
            Sym.power_settings_new,
            contentDescription = null,
            size = 38.dp,
            weight = 500,
            tint = lerpColor(Color.White, WolColors.OnGradient, glow),
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))
