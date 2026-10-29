package com.kdgames.wake_on_lan.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.motionSpec

data class MenuItem(val label: String, val symbol: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * Basılı tutunca açılan bağlam menüsü (S08, Kaan): kartın ortasında cam panel, `bouncy` ile büyüyerek gelir,
 * solarak kapanır; dışarı dokununca kapanır. Kapanış animasyonu bitene kadar Popup yaşar.
 */
@Composable
fun ContextMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>) {
    val reduceMotion = LocalReduceMotion.current
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) { visible.targetState = expanded }
    if (!visible.currentState && !visible.targetState) return
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = scaleIn(motionSpec(SpringToken.Bouncy, reduceMotion), 0.85f, TransformOrigin.Center) + fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)),
            exit = scaleOut(motionSpec(SpringToken.Snappy, reduceMotion), 0.9f) + fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)),
        ) {
            val shape = RoundedCornerShape(22.dp)
            // Gölge statik (animasyonlu değil, S06 dersi); panel kendi padding'i içinde kırpılmasın diye dış boşluk.
            Column(
                Modifier
                    .padding(16.dp)
                    .dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.45f), offset = DpOffset(0.dp, 6.dp)))
                    .widthIn(min = 180.dp)
                    .clip(shape)
                    .background(Color(0xFF23242D))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                    .padding(vertical = 6.dp),
            ) {
                items.forEachIndexed { i, item ->
                    if (i > 0) Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
                    val color = if (item.destructive) WolColors.Danger else WolColors.TextPrimary
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable(onClick = { onDismiss(); item.onClick() }, onClickLabel = item.label)
                            .testTag("menu_${item.label}")
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SymbolIcon(item.symbol, contentDescription = null, size = 20.dp, tint = color)
                        Spacer(Modifier.width(12.dp))
                        Text(item.label, style = WolType.BodyLarge, color = color)
                    }
                }
            }
        }
    }
}
