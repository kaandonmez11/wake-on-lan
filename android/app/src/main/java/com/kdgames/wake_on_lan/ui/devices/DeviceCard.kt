package com.kdgames.wake_on_lan.ui.devices

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.ui.components.AnimatedText
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.breathing
import com.kdgames.wake_on_lan.ui.components.GlowLayer
import com.kdgames.wake_on_lan.ui.components.HoldState
import com.kdgames.wake_on_lan.ui.components.OpenCardOverlay
import com.kdgames.wake_on_lan.ui.components.holdToConfirm
import com.kdgames.wake_on_lan.ui.components.loopProgress
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.components.rememberHoldState
import com.kdgames.wake_on_lan.ui.theme.LocalHaptics
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolDimens
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.cssLinearGradient
import com.kdgames.wake_on_lan.ui.theme.motionSpec

/**
 * Cihaz kartı (spec 10.2, 11.3, 12.5): 150 yükseklik, köşe 32. Kapalı = cam; açık = cihaz renginde
 * 145° gradient, c1 %60 çerçeve, nefes alan c2 gölgesi, dönen hüzme ve ışık süpürmesi. Geçiş akar.
 */
@Composable
fun DeviceCard(
    ui: DeviceUi,
    onSelect: () -> Unit,
    onWake: () -> Unit,
    onShutdown: () -> Unit,
    onAccessibilityShutdown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(WolDimens.CornerCard)
    val palette = ui.palette
    val openness by animateFloatAsState(if (ui.isOn) 1f else 0f, motionSpec(SpringToken.Smooth, reduceMotion), label = "cardOpen")
    val breath = breathing(3_000, 0.85f, 1.15f, running = ui.isOn, label = "cardBreath")
    val hold = rememberHoldState()
    Box(
        modifier
            .height(150.dp)
            .semantics { contentDescription = "${ui.device.name}, ${DeviceLabels.card(ui.state, ui.history)}" }
            .pressable(onClick = { haptics.tick(); onSelect() }, onClickLabel = "Seç"),
    ) {
    GlowLayer(WolDimens.CornerCard, palette.end, maxAlpha = 0.40f, strength = { openness }, breath = breath, modifier = Modifier.matchParentSize())
    GlassSurface(shape = shape, modifier = Modifier.fillMaxSize()) {
        // Açık kart gradient'i + çerçeve, açıklık oranıyla akarak gelir.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    if (openness > 0f) {
                        drawRect(cssLinearGradient(145f, size, 0f to palette.openCardStart.copy(alpha = openness), 1f to palette.openCardEnd.copy(alpha = openness)))
                    }
                }
                .border(1.dp, palette.start.copy(alpha = 0.60f * openness), shape),
        )
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBubble(ui, size = 44.dp)
                Spacer(Modifier.weight(1f))
                CardPowerButton(ui, hold, onWake, onShutdown, onAccessibilityShutdown)
            }
            Spacer(Modifier.weight(1f))
            AnimatedText(ui.device.name, style = WolType.CardName, color = WolColors.TextPrimary, maxLines = 1)
            AnimatedText(
                DeviceLabels.card(ui.state, ui.history),
                style = WolType.BodySmall,
                color = if (ui.isOn) WolColors.TextOnCard else WolColors.TextSecondaryAlt,
            )
            Spacer(Modifier.height(10.dp))
            StatusBar(ui)
        }
        // Küçük kartlarda ışık süpürmesi yok (yalnız büyük kartta, Kaan); dönen hüzme kalır.
        OpenCardOverlay(shape, palette.start, openness, Modifier.matchParentSize(), lightSweep = false)
        HoldOutline(shape, hold, Modifier.matchParentSize())
    }
    }
}

/**
 * Kart çubuğu (4 yükseklik): kapalı = güvenilirlik oranı, açık = tam gradient,
 * uyanıyor = amber ilerleme, kapanıyor = kırmızı ilerleme.
 *
 * Zamanlı durumlarda (uyanıyor 60 sn, kapanıyor 90 sn, yeniden başlıyor 180 sn) çubuk saniyelik
 * değerlere atlamaz: girişte o anki konumdan kalan süre boyunca **doğrusal, kesintisiz** dolar
 * (S06 cihaz testi: "tık tık ilerliyor"). Diğer durumlarda hedefe spring ile akar.
 */
@Composable
private fun StatusBar(ui: DeviceUi) {
    val reduceMotion = LocalReduceMotion.current
    val state = ui.state
    val totalMs = when (state) {
        is DeviceState.Waking -> 60_000L
        is DeviceState.PowerTransition -> if (state.action == PowerAction.RESTART) 180_000L else 90_000L
        else -> null
    }
    val elapsedMs = when (state) {
        is DeviceState.Waking -> state.elapsedSec * 1_000L
        is DeviceState.PowerTransition -> state.elapsedSec * 1_000L
        else -> 0L
    }
    val restFraction = when (state) {
        is DeviceState.On -> 1f
        else -> if (ui.history.total == 0) 0f else ui.history.successes.toFloat() / ui.history.total
    }
    val bar = remember { Animatable(restFraction) }
    val currentElapsed by rememberUpdatedState(elapsedMs)
    // Anahtar: durum türü (zamanlıysa saniye değişimi efekti yeniden başlatmaz).
    val key = if (totalMs != null) "timed-${state!!::class.simpleName}-${(state as? DeviceState.PowerTransition)?.action}" else "rest-$restFraction"
    LaunchedEffect(key) {
        if (totalMs != null && !reduceMotion) {
            // Önce geçen süreye karşılık gelen noktaya kısa bir spring, sonra kalan süre boyunca sabit hız.
            val start = (currentElapsed.toFloat() / totalMs).coerceIn(0.02f, 1f)
            bar.animateTo(start, SpringToken.Snappy.spec())
            val remainingMs = ((1f - bar.value) * totalMs).toInt().coerceAtLeast(1)
            bar.animateTo(1f, tween(remainingMs, easing = LinearEasing))
        } else {
            bar.animateTo(if (totalMs != null) (currentElapsed.toFloat() / totalMs).coerceIn(0f, 1f) else restFraction, motionSpec(SpringToken.Smooth, reduceMotion))
        }
    }
    val c1 by animateColorAsState(
        when (state) {
            is DeviceState.On -> ui.palette.start
            is DeviceState.Waking -> WolColors.Amber
            is DeviceState.PowerTransition -> WolColors.Danger
            else -> Color.White.copy(alpha = 0.6f)
        }, motionSpec(SpringToken.Snappy, reduceMotion), label = "barC1",
    )
    val c2 by animateColorAsState(
        when (state) {
            is DeviceState.On -> ui.palette.end
            is DeviceState.Waking -> WolColors.Amber
            is DeviceState.PowerTransition -> WolColors.Danger
            else -> Color.White.copy(alpha = 0.6f)
        }, motionSpec(SpringToken.Snappy, reduceMotion), label = "barC2",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .drawBehind {
                val width = bar.value
                if (width > 0f) drawRect(Brush.horizontalGradient(listOf(c1, c2), endX = size.width * width), size = size.copy(width = size.width * width))
            },
    )
}

/** Kart güç butonu (44): kapalı = dokun uyandır; açık = basılı tut Kapat, halka dolumu kartta görünür. */
@Composable
private fun CardPowerButton(ui: DeviceUi, hold: HoldState, onWake: () -> Unit, onShutdown: () -> Unit, onAccessibilityShutdown: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val canWake = ui.state is DeviceState.Off && ui.device.mac != null
    val dimmed = ui.state is DeviceState.Off && ui.device.mac == null
    val dimAlpha = animateFloatAsState(if (dimmed) 0.38f else 1f, motionSpec(SpringToken.Smooth, reduceMotion), label = "cpbDim")
    val canHold = powerAvailability(ui, PowerAction.SHUTDOWN) == PowerAvailability.Available
    val on = ui.isOn
    val bg1 by animateColorAsState(if (on) ui.palette.start else Color.White.copy(alpha = 0.10f), motionSpec(SpringToken.Smooth, reduceMotion), label = "cpb1")
    val bg2 by animateColorAsState(if (on) ui.palette.end else Color.White.copy(alpha = 0.06f), motionSpec(SpringToken.Smooth, reduceMotion), label = "cpb2")
    val icon by animateColorAsState(if (on) WolColors.OnGradient else Color.White, motionSpec(SpringToken.Smooth, reduceMotion), label = "cpbIcon")
    val spin by loopProgress(1_000, running = ui.isBusy, label = "cpbSpin")
    val press by animateFloatAsState(if (hold.pressed) 0.96f else 1f, motionSpec(if (hold.pressed) SpringToken.Quick else SpringToken.Snappy, reduceMotion), label = "cpbPress")
    val gesture = when {
        canWake -> Modifier.pressable(onClick = { haptics.click(); onWake() }, onClickLabel = "Uyandır")
        canHold -> Modifier.holdToConfirm(hold, true, "Kapat", onShutdown, onAccessibilityShutdown)
        else -> Modifier
    }
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer {
                alpha = dimAlpha.value
                val s = press * hold.commit.value
                scaleX = s
                scaleY = s
            }
            .semantics { contentDescription = if (canWake) "${ui.device.name} uyandır" else if (canHold) "${ui.device.name} kapat · basılı tut" else "Güç" }
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
            val bgBrush = Brush.linearGradient(listOf(bg1, bg2), start = Offset.Zero, end = Offset(size.width, size.height))
            onDrawBehind {
            drawCircle(bgBrush)
            if (on) drawCircle(ui.palette.start.copy(alpha = 0.55f), radius = size.minDimension / 2 + 2.dp.toPx(), style = Stroke(1.dp.toPx()))
            val stroke = 3.dp.toPx()
            if (ui.isBusy) {
                val color = if (ui.isWaking) WolColors.Amber else WolColors.Danger
                drawArc(color, -90f + spin * 360f, 90f, false, topLeft = Offset(stroke / 2, stroke / 2), size = size.copy(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // Basılı tut dolumu kartın tüm çerçevesinde (HoldOutline); butonda yalnız hafif kırmızı ton.
            val p = hold.progress.value
            if (p > 0f) drawCircle(WolColors.Danger, alpha = 0.35f * p)
            }
        })
        SymbolIcon(Sym.power_settings_new, contentDescription = null, size = 22.dp, tint = icon)
    }
}

/**
 * Küçük kartta basılı tut dolumu: kartın çerçevesi boyunca, üst ortadan saat yönünde kırmızı çizgi (Kaan:
 * buton kenarında belli olmuyordu). Yol boyut başına bir kez; her karede yalnız kesit güncellenir.
 */
@Composable
private fun HoldOutline(shape: androidx.compose.ui.graphics.Shape, hold: HoldState, modifier: Modifier) {
    Spacer(
        modifier.graphicsLayer().drawWithCache {
            // Yol elle kurulur: üst ortadan başlar, saat yönünde döner (hazır yuvarlak dikdörtgen yolu
            // sol alttan saat yönünün tersine başlıyordu — S06 cihaz testi).
            val inset = 1.5.dp.toPx()
            val l = inset
            val t = inset
            val r = size.width - inset
            val b = size.height - inset
            val rad = (WolDimens.CornerCard.toPx() - inset).coerceAtMost(minOf(r - l, b - t) / 2)
            val full = androidx.compose.ui.graphics.Path().apply {
                moveTo((l + r) / 2, t)
                lineTo(r - rad, t)
                arcTo(androidx.compose.ui.geometry.Rect(r - 2 * rad, t, r, t + 2 * rad), -90f, 90f, false)
                lineTo(r, b - rad)
                arcTo(androidx.compose.ui.geometry.Rect(r - 2 * rad, b - 2 * rad, r, b), 0f, 90f, false)
                lineTo(l + rad, b)
                arcTo(androidx.compose.ui.geometry.Rect(l, b - 2 * rad, l + 2 * rad, b), 90f, 90f, false)
                lineTo(l, t + rad)
                arcTo(androidx.compose.ui.geometry.Rect(l, t, l + 2 * rad, t + 2 * rad), 180f, 90f, false)
                close()
            }
            val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(full, false) }
            val length = measure.length
            val segment = androidx.compose.ui.graphics.Path()
            val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                val p = hold.progress.value
                if (p <= 0f) return@onDrawBehind
                segment.rewind()
                measure.getSegment(0f, length * p, segment, true)
                drawPath(segment, WolColors.Danger, style = stroke)
            }
        },
    )
}
