package com.kdgames.wake_on_lan.tile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.status.StateLabels
import com.kdgames.wake_on_lan.ui.components.AnimatedText
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.components.rememberHoldState
import com.kdgames.wake_on_lan.ui.devices.HoldPowerButton
import com.kdgames.wake_on_lan.ui.devices.PowerOrb
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import com.kdgames.wake_on_lan.ui.theme.LocalHaptics
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolType

private val MINI_ACTIONS = listOf(PowerAction.LOCK, PowerAction.SLEEP, PowerAction.HIBERNATE, PowerAction.RESTART)

/**
 * Tile güç paneli içeriği (spec 10.8 mod A ve B): cihaz adı + durum, büyük orb (basılı tut = Kapat),
 * "Kapatmak için basılı tut", 4 mini hold buton (Windows ikonlu), "Vazgeç". [compact] = mod B dialog'u.
 * K12: komutlar yalnız `On` iken etkin. [orbScale] giriş animasyonu (0.8 → 1) için katman ölçeği.
 */
@Composable
fun TilePowerContent(
    device: Device?,
    state: DeviceState?,
    onAction: (PowerAction) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    orbScale: () -> Float = { 1f },
) {
    val haptics = LocalHaptics.current
    val palette = DevicePalette.of(device?.colorIndex ?: 0)
    val on = state is DeviceState.On
    val hasAgent = device?.agentId != null
    val orbHold = rememberHoldState()
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            device?.name ?: "Cihaz",
            style = if (compact) WolType.SheetTitle else WolType.ScreenTitle,
            color = WolColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        AnimatedText(
            if (on && !hasAgent) "Açık · agent yok" else StateLabels.of(state),
            style = WolType.BodySmall,
            color = WolColors.TextSecondary,
            maxLines = 1,
            modifier = Modifier.testTag("tile_status"),
        )
        Spacer(Modifier.height(if (compact) 16.dp else 30.dp))
        Box(Modifier.graphicsLayer { val s = orbScale(); scaleX = s; scaleY = s }) {
            PowerOrb(
                state = state,
                palette = palette,
                hold = orbHold,
                tapEnabled = false,
                holdEnabled = on && hasAgent,
                onTap = {},
                onHoldCommit = { onAction(PowerAction.SHUTDOWN) },
                onAccessibilityHold = { onAction(PowerAction.SHUTDOWN) },
                modifier = Modifier.testTag("tile_orb"),
                ringSize = if (compact) 132.dp else 220.dp,
                orbSize = if (compact) 100.dp else 172.dp,
            )
        }
        Spacer(Modifier.height(if (compact) 12.dp else 22.dp))
        AnimatedText(
            when {
                !on -> "PC açık değil"
                !hasAgent -> "Kapatmak için PC'de agent kurulu olmalı"
                orbHold.holding -> "Bırakma…"
                else -> "Kapatmak için basılı tut"
            },
            style = WolType.Body,
            color = WolColors.TextSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(if (compact) 16.dp else 26.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MINI_ACTIONS.forEach { action ->
                val enabled = on && hasAgent && (action != PowerAction.HIBERNATE || device?.hibernateAvailable == true)
                HoldPowerButton(
                    action = action,
                    hold = rememberHoldState(),
                    enabled = enabled,
                    onCommit = { onAction(action) },
                    onAccessibility = { onAction(action) },
                    modifier = Modifier.weight(1f).testTag("tile_${action.name}"),
                )
            }
        }
        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
        Box(
            Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .pressable(onClick = { haptics.tick(); onCancel() }, onClickLabel = "Vazgeç")
                .padding(horizontal = 28.dp)
                .testTag("tile_cancel"),
            contentAlignment = Alignment.Center,
        ) { Text("Vazgeç", style = WolType.BodyLarge, color = WolColors.TextSecondary) }
    }
}
