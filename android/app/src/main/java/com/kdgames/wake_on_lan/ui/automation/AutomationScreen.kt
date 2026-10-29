package com.kdgames.wake_on_lan.ui.automation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.ui.components.AnimatedText
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.WinPowerIcon
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.devices.iconSymbol
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
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

/** Otomasyon sekmesi (spec 10.1, 10.3). Durumsuz; [state] ViewModel'den. */
@Composable
fun AutomationScreen(
    state: AutomationUiState = AutomationUiState(loaded = true),
    onAdd: () -> Unit = {},
    onEdit: (Long) -> Unit = {},
    onToggle: (Long, Boolean) -> Unit = { _, _ -> },
    onSkip: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val reduceMotion = LocalReduceMotion.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = WolDimens.ScreenHorizontal,
            end = WolDimens.ScreenHorizontal,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + WolDimens.TopExtra,
            bottom = WolDimens.NavContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            // Kaan (S08 cihaz testi): Otomasyon'da Wi-Fi pill'i ve ayarlar balonu yok; yalnız başlık + açıklama.
            Column {
                Text("Otomasyon", style = WolType.PageTitle, color = WolColors.TextPrimary, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Text("Zamanlayıcılar telefondan çalışır, Wi-Fi şart", style = WolType.BodySmall, color = WolColors.TextTertiary)
                Spacer(Modifier.height(2.dp))
            }
        }
        if (!state.exactAlarmsAllowed) {
            item(key = "exact") {
                WarningRow(
                    "Tam saatli alarm izni kapalı · zamanlayıcılar gecikebilir",
                    Modifier.animateItem(fadeInSpec = motionSpec(SpringToken.Smooth, reduceMotion), fadeOutSpec = motionSpec(SpringToken.Snappy, reduceMotion)),
                )
            }
        }
        items(state.cards, key = { it.id }) { card ->
            ScheduleCard(
                card,
                onClick = { onEdit(card.id) },
                onToggle = { onToggle(card.id, it) },
                onSkip = { onSkip(card.id) },
                modifier = Modifier.animateItem(
                    fadeInSpec = motionSpec(SpringToken.Smooth, reduceMotion),
                    placementSpec = motionSpec(SpringToken.Smooth, reduceMotion),
                    fadeOutSpec = motionSpec(SpringToken.Snappy, reduceMotion),
                ),
            )
        }
        item(key = "add") {
            AddButton(state.hasDevices, onAdd, Modifier.animateItem(placementSpec = motionSpec(SpringToken.Smooth, reduceMotion)))
        }
    }
}

@Composable
private fun WarningRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WolDimens.CornerSchedule))
            .background(WolColors.Amber.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SymbolIcon(Sym.warning, contentDescription = null, size = 18.dp, tint = WolColors.Amber)
        Text(text, style = WolType.BodySmall, color = WolColors.Amber)
    }
}

@Composable
private fun AddButton(enabled: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, motionSpec(SpringToken.Snappy, reduceMotion), label = "addAlpha")
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GlassSurface(
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .graphicsLayer { this.alpha = alpha }
                .pressable(onClick = onAdd, enabled = enabled, onClickLabel = "Zamanlayıcı ekle"),
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SymbolIcon(Sym.add, contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Zamanlayıcı ekle", style = WolType.BodyLarge, color = WolColors.TextPrimary)
            }
        }
        AnimatedVisibility(!enabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text("Önce bir cihaz ekle", style = WolType.BodySmall, color = WolColors.TextTertiary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Zamanlayıcı kartı (spec 10.3): cam, köşe 28; kapalıyken %70 opak. */
@Composable
fun ScheduleCard(
    card: ScheduleCardUi,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val alpha by animateFloatAsState(if (card.enabled) 1f else 0.7f, motionSpec(SpringToken.Smooth, reduceMotion), label = "cardAlpha")
    GlassSurface(
        shape = RoundedCornerShape(WolDimens.CornerSchedule),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .pressable(onClick = onClick, onClickLabel = "Düzenle"),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScheduleBubble(card.iconKey, card.palette)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.deviceName, style = WolType.BodyLarge, color = WolColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionTag(card.schedule.action, card.actionLabel, card.tone)
                        Spacer(Modifier.width(8.dp))
                        Text(card.trigger, style = WolType.BodySmall, color = WolColors.TextSecondaryAlt, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (card.schedule.notify) {
                            Spacer(Modifier.width(6.dp))
                            SymbolIcon(Sym.notifications, contentDescription = "Bildirim açık", size = 14.dp, tint = WolColors.TextTertiary, filled = true)
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                GradientSwitch(
                    checked = card.enabled,
                    palette = card.palette,
                    onCheckedChange = { haptics.tick(); onToggle(it) },
                )
            }
            AnimatedVisibility(
                visible = card.enabled && card.nextLine != null,
                enter = fadeIn(motionSpec(SpringToken.Smooth, reduceMotion)) + expandVertically(motionSpec(SpringToken.Smooth, reduceMotion)),
                exit = fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)) + shrinkVertically(motionSpec(SpringToken.Smooth, reduceMotion)),
            ) {
                NextRow(card, onSkip = { haptics.tick(); onSkip() })
            }
        }
    }
}

@Composable
private fun NextRow(card: ScheduleCardUi, onSkip: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val amber by animateColorAsState(if (card.skipped) WolColors.Amber else WolColors.TextSecondary, motionSpec(SpringToken.Snappy, reduceMotion), label = "nextColor")
    val bg by animateColorAsState(
        if (card.skipped) WolColors.Amber.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f),
        motionSpec(SpringToken.Snappy, reduceMotion),
        label = "nextBg",
    )
    Row(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .drawBehind { drawRect(bg) }
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedText(card.nextLine.orEmpty(), style = WolType.BodySmall, color = amber, maxLines = 1, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.10f))
                .pressable(onClick = onSkip, onClickLabel = card.skipButton)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = card.skipButton,
                transitionSpec = {
                    fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)) togetherWith fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)) using
                        SizeTransform(clip = false) { _, _ -> if (reduceMotion) snap() else SpringToken.Snappy.spec() }
                },
                label = "skipButton",
            ) { t -> Text(t, style = WolType.Chip, color = if (card.skipped) WolColors.Amber else WolColors.TextPrimary, maxLines = 1) }
        }
    }
}

@Composable
private fun ActionTag(action: PowerAction, label: String, tone: ActionTone) {
    val color = when (tone) {
        ActionTone.WAKE -> WolColors.Success
        ActionTone.SHUTDOWN -> WolColors.Danger
        ActionTone.NEUTRAL -> WolColors.TextSecondary
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (action == PowerAction.WAKE) SymbolIcon(Sym.power_settings_new, contentDescription = null, size = 12.dp, tint = color)
        else WinPowerIcon(action, tint = color, size = 12.dp)
        Text(label, style = WolType.Label, color = color)
    }
}

@Composable
internal fun ScheduleBubble(iconKey: String, palette: DevicePalette, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .drawBehind { drawRect(Brush.linearGradient(listOf(palette.start, palette.end))) },
        contentAlignment = Alignment.Center,
    ) { SymbolIcon(iconSymbol(iconKey), contentDescription = null, size = size * 0.5f, tint = WolColors.OnGradient, filled = true) }
}

/**
 * 52×32 anahtar (spec 10.3, 12.3): düğme `bouncy` ile kayar, açıkken zemin cihaz gradient'i + hafif parlama.
 * Değerler yalnız layout/draw aşamasında okunur.
 */
@Composable
fun GradientSwitch(checked: Boolean, palette: DevicePalette, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val p by animateFloatAsState(if (checked) 1f else 0f, motionSpec(SpringToken.Bouncy, reduceMotion), label = "switch")
    val fill by animateFloatAsState(if (checked) 1f else 0f, motionSpec(SpringToken.Snappy, reduceMotion), label = "switchFill")
    Box(
        modifier
            .size(width = 52.dp, height = 32.dp)
            .drawBehind {
                val r = size.height / 2
                if (fill > 0f) {
                    // Parlama: kenardan taşan yumuşak halo (animasyonlu gölge yerine sabit gradient, S06 dersi).
                    drawRoundRect(
                        Brush.radialGradient(listOf(palette.end.copy(alpha = 0.35f * fill), Color.Transparent), center = center, radius = size.width * 0.75f),
                        topLeft = Offset(-6.dp.toPx(), -6.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + 6.dp.toPx()),
                    )
                }
                drawRoundRect(Color.White.copy(alpha = 0.16f * (1f - fill)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                if (fill > 0f) {
                    drawRoundRect(
                        cssLinearGradient(135f, size, 0f to palette.start.copy(alpha = fill), 1f to palette.end.copy(alpha = fill)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                    )
                }
            }
            .clip(RoundedCornerShape(50))
            .pressable(onClick = { onCheckedChange(!checked) }, role = Role.Switch, onClickLabel = if (checked) "Kapat" else "Aç")
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "Açık" else "Kapalı"
                contentDescription = "Zamanlayıcı"
            },
    ) {
        Box(
            Modifier
                .offset { IntOffset((3.dp.toPx() + (52 - 26 - 6).dp.toPx() * p).toInt(), 3.dp.roundToPx()) }
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
