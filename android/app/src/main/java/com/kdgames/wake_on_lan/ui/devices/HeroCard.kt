package com.kdgames.wake_on_lan.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.ui.components.AnimatedText
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.RollingNumber
import com.kdgames.wake_on_lan.ui.components.breathing
import com.kdgames.wake_on_lan.ui.components.breathingShadow
import com.kdgames.wake_on_lan.ui.components.openCardEffects
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.components.rememberHoldState
import com.kdgames.wake_on_lan.ui.theme.GlassVariant
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.REDUCED_CROSSFADE_MS
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolDimens
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.motionSpec
import androidx.compose.animation.core.tween

private val PowerRow = listOf(PowerAction.LOCK, PowerAction.SLEEP, PowerAction.HIBERNATE, PowerAction.RESTART, PowerAction.SHUTDOWN)

/** Hero kartın modu: içerik bölümü bu üç durum arasında akar. */
private enum class HeroMode { Waking, Normal }

/**
 * Seçili cihaz kartı (spec 10.2). Seçim değişince ikon balonu rengi, ad ve adres akarak değişir;
 * çipler genişliğini spring ile ayarlar; uyanırken güç satırı ve geçmişin yerine adım kartları gelir.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroCard(
    ui: DeviceUi,
    onWake: () -> Unit,
    onPower: (PowerAction) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val shape = RoundedCornerShape(WolDimens.CornerHero)
    val palette = ui.palette
    val state = ui.state
    val orbHold = rememberHoldState()
    val rowHolds = PowerRow.associateWith { rememberHoldState() }
    var confirm by remember { mutableStateOf<PowerAction?>(null) }

    val holding = (listOf(PowerAction.SHUTDOWN to orbHold) + rowHolds.map { it.key to it.value }).firstOrNull { it.second.holding }?.first
    val openness by animateFloatAsState(if (ui.isOn) 1f else 0f, motionSpec(SpringToken.Smooth, reduceMotion), label = "heroOpen")
    // Sürekli değerler State olarak tutulur ve yalnız çizimde okunur: her karede yeniden kompozisyon yok.
    val breath = breathing(3_000, 0.85f, 1.15f, running = ui.isOn, label = "heroBreath")
    val wakingGlow = breathing(1_600, 0.6f, 1f, running = ui.isWaking, label = "heroWakingGlow")
    val amber = animateFloatAsState(if (ui.isWaking) 1f else 0f, motionSpec(SpringToken.Smooth, reduceMotion), label = "heroAmber")

    val shutdown = powerAvailability(ui, PowerAction.SHUTDOWN)
    GlassSurface(
        shape = shape,
        variant = GlassVariant.Strong,
        accent = palette.start,
        modifier = modifier
            .fillMaxWidth()
            .breathingShadow(shape, palette.end, maxAlpha = 0.30f, strength = { openness }, breath = breath)
            .openCardEffects(shape, palette.start, openness),
    ) {
        // Uyanıyor: amber zemin (%30 → %8) + nefes alan amber çerçeve (spec 10.2, 12.5); çizimde okunur.
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val a = amber.value
                    if (a <= 0.01f) return@drawBehind
                    drawRect(Brush.verticalGradient(listOf(WolColors.Amber.copy(alpha = 0.30f * a), WolColors.Amber.copy(alpha = 0.08f * a))))
                    val outline = shape.createOutline(size, layoutDirection, this)
                    drawOutline(outline, WolColors.Amber.copy(alpha = 0.60f * a * wakingGlow.value), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                },
        )
        // Boyutu tek kaynak yönetir (aşağıdaki AnimatedContent); iç içe iki boyut animasyonu takılma yapıyordu.
        Column(Modifier.padding(18.dp)) {
            AnimatedVisibility(
                visible = holding != null,
                enter = fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)) + expandVertically(motionSpec(SpringToken.Snappy, reduceMotion)),
                exit = fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)) + shrinkVertically(motionSpec(SpringToken.Snappy, reduceMotion)),
            ) {
                Text(
                    holding?.let(DeviceLabels::holdHint) ?: "",
                    style = WolType.Chip,
                    color = WolColors.DangerLight,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(ui, size = 48.dp)
                        EditButton(onEdit)
                    }
                    Spacer(Modifier.height(14.dp))
                    AnimatedText(ui.device.name, style = WolType.HeroName, color = WolColors.TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    AnimatedText("${ui.device.ip} · ${ui.device.mac ?: "MAC yok"}", style = WolType.TechnicalSmall, color = WolColors.TextTertiary)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StateChip(state)
                        AgentChip(ui)
                        AnimatedVisibility(ui.device.mac == null, enter = fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)), exit = fadeOut(motionSpec(SpringToken.Snappy, reduceMotion))) {
                            Chip { Text("MAC bekleniyor", style = WolType.Chip, color = WolColors.Amber) }
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                PowerOrb(
                    state = state,
                    palette = palette,
                    hold = orbHold,
                    tapEnabled = state is DeviceState.Off && ui.device.mac != null,
                    holdEnabled = shutdown == PowerAvailability.Available,
                    onTap = onWake,
                    onHoldCommit = { onPower(PowerAction.SHUTDOWN) },
                    onAccessibilityHold = { confirm = PowerAction.SHUTDOWN },
                )
            }

            // K24: güç satırı kapalı PC'de de görünür (pasif); yalnız uyanırken adım kartlarına yer verir.
            val mode = if (state is DeviceState.Waking) HeroMode.Waking else HeroMode.Normal
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(REDUCED_CROSSFADE_MS)) togetherWith fadeOut(tween(REDUCED_CROSSFADE_MS))
                    else (fadeIn(SpringToken.Smooth.spec()) togetherWith fadeOut(SpringToken.Snappy.spec())) using
                        SizeTransform(clip = false) { _, _ -> SpringToken.Smooth.spec(IntSize.VisibilityThreshold) }
                },
                label = "heroMode",
            ) { m ->
                Column {
                    when (m) {
                        HeroMode.Waking -> {
                            Spacer(Modifier.height(16.dp))
                            WakeSteps((state as? DeviceState.Waking)?.step ?: com.kdgames.wake_on_lan.domain.model.WakeStep.PACKET, palette)
                        }
                        HeroMode.Normal -> {
                            Spacer(Modifier.height(16.dp))
                            Text("Güç · Basılı tut", style = WolType.Label, color = WolColors.TextTertiary)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PowerRow.forEach { action ->
                                    HoldPowerButton(
                                        action = action,
                                        hold = rowHolds.getValue(action),
                                        enabled = powerAvailability(ui, action) == PowerAvailability.Available,
                                        onCommit = { onPower(action) },
                                        onAccessibility = { confirm = action },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            val hint = (powerAvailability(ui, PowerAction.LOCK) as? PowerAvailability.Disabled)?.hint
                            AnimatedVisibility(
                                hint != null,
                                enter = fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)) + expandVertically(motionSpec(SpringToken.Snappy, reduceMotion)),
                                exit = fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)) + shrinkVertically(motionSpec(SpringToken.Snappy, reduceMotion)),
                            ) {
                                Text(hint ?: "", style = WolType.Label, color = WolColors.TextTertiary, modifier = Modifier.padding(top = 8.dp))
                            }
                            Spacer(Modifier.height(16.dp))
                            HistoryBars(ui.history)
                        }
                    }
                }
            }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${DeviceLabels.actionTitle(action)} · ${ui.device.name}") },
            text = { Text("Bu komut PC'ye gönderilsin mi?") },
            confirmButton = { TextButton(onClick = { confirm = null; onPower(action) }) { Text(DeviceLabels.actionTitle(action)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Vazgeç") } },
        )
    }
}

/** İkon balonu: kapalıyken c1 %20 zemin + c1 ikon, açıkken gradient + koyu ikon; renk akar. */
@Composable
fun IconBubble(ui: DeviceUi, size: androidx.compose.ui.unit.Dp) {
    val reduceMotion = LocalReduceMotion.current
    val on = ui.isOn
    val bgStart by animateColorAsState(if (on) ui.palette.start else ui.palette.start.copy(alpha = 0.20f), motionSpec(SpringToken.Smooth, reduceMotion), label = "bubbleBg1")
    val bgEnd by animateColorAsState(if (on) ui.palette.end else ui.palette.start.copy(alpha = 0.20f), motionSpec(SpringToken.Smooth, reduceMotion), label = "bubbleBg2")
    val iconColor by animateColorAsState(if (on) WolColors.OnGradient else ui.palette.start, motionSpec(SpringToken.Smooth, reduceMotion), label = "bubbleIcon")
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(bgStart, bgEnd))),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = iconSymbol(ui.device.iconKey),
            transitionSpec = { fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)) togetherWith fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)) },
            label = "bubbleIcon",
        ) { sym -> SymbolIcon(sym, contentDescription = null, size = size * 0.5f, tint = iconColor) }
    }
}

@Composable
private fun EditButton(onClick: () -> Unit) {
    // S07'ye kadar pasif görünümlü; dokununca bilgi verir.
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .graphicsLayer { alpha = 0.55f }
            .pressable(onClick = onClick, onClickLabel = "Düzenle"),
        contentAlignment = Alignment.Center,
    ) { SymbolIcon(Sym.edit, contentDescription = "Düzenle", size = 18.dp) }
}

@Composable
fun Chip(content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .animateContentSize(motionSpec(SpringToken.Snappy, reduceMotion))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun StateChip(state: DeviceState?) {
    val reduceMotion = LocalReduceMotion.current
    val dot by animateColorAsState(
        when (state) {
            is DeviceState.On -> if (state.stale) WolColors.TextTertiary else WolColors.Success
            is DeviceState.Waking -> WolColors.Amber
            is DeviceState.PowerTransition -> WolColors.Danger
            else -> WolColors.TextTertiary
        },
        motionSpec(SpringToken.Snappy, reduceMotion), label = "stateDot",
    )
    // Sayılar (ping ms, uyanma saniyesi) mono ve haneli yuvarlanır (spec 11.4, 12.3).
    val (prefix, number, suffix) = when {
        state is DeviceState.On && !state.stale -> Triple("Açık · ", state.pingMs.toLong(), " ms")
        state is DeviceState.Waking -> Triple("Uyanıyor · ", state.elapsedSec.toLong(), " sn")
        else -> Triple(DeviceLabels.chip(state), null, "")
    }
    Chip {
        StatusDot(dot)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedText(prefix, style = WolType.Chip, color = WolColors.TextPrimary)
            if (number != null) {
                RollingNumber(number, style = WolType.TechnicalSmall, color = WolColors.TextPrimary)
                Text(suffix, style = WolType.Chip, color = WolColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun AgentChip(ui: DeviceUi) {
    val label = DeviceLabels.agent(ui)
    val reduceMotion = LocalReduceMotion.current
    val color by animateColorAsState(
        when (label) {
            "Agent" -> WolColors.Success
            "Agent yok" -> WolColors.TextTertiary
            else -> WolColors.Danger
        },
        motionSpec(SpringToken.Snappy, reduceMotion), label = "agentColor",
    )
    Chip {
        SymbolIcon(Sym.verified_user, contentDescription = null, size = 14.dp, tint = color, filled = label == "Agent")
        AnimatedText(label, style = WolType.Chip, color = WolColors.TextPrimary)
    }
}
