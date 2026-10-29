package com.kdgames.wake_on_lan.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import com.kdgames.wake_on_lan.ui.theme.GlassVariant
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

enum class MainTab(val title: String, val symbol: String) {
    Devices("Cihazlar", Sym.desktop_windows),
    Automation("Otomasyon", Sym.bolt),
}

private val IndicatorInset = 6.dp

/**
 * Spec 10.9: yüzen nav. Solda cam hap içinde 2 sekme; aktif gösterge spring ile kayar,
 * aktif ikon FILL 0→1. Sağda 68 gradient "+".
 */
@Composable
fun FloatingNavBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = WolDimens.NavSideMargin, end = WolDimens.NavSideMargin, bottom = WolDimens.NavBottomMargin),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassSurface(
            shape = CircleShape,
            variant = GlassVariant.Dock,
            modifier = Modifier.weight(1f).height(WolDimens.NavHeight),
        ) {
            TabPill(selected = selected, onSelect = { tab ->
                if (tab != selected) {
                    haptics.tick()
                    onSelect(tab)
                }
            })
        }
        AddButton(onClick = onAdd, label = if (selected == MainTab.Automation) "Zamanlayıcı ekle" else "Cihaz ekle")
    }
}

@Composable
private fun TabPill(selected: MainTab, onSelect: (MainTab) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    BoxWithConstraints(Modifier.fillMaxHeight().padding(IndicatorInset)) {
        val tabWidth = maxWidth / MainTab.entries.size
        val indicatorX by animateDpAsState(
            targetValue = tabWidth * selected.ordinal,
            animationSpec = motionSpec(SpringToken.Bouncy, reduceMotion),
            label = "navIndicator",
        )
        Box(
            Modifier
                .offset(x = indicatorX)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) WolColors.TextPrimary else WolColors.TextSecondary,
                    animationSpec = motionSpec(SpringToken.Snappy, reduceMotion),
                    label = "tabColor",
                )
                Column(
                    modifier = Modifier
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .semantics { this.selected = isSelected }
                        .pressable(onClick = { onSelect(tab) }, role = Role.Tab),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SymbolIcon(
                        name = tab.symbol,
                        contentDescription = null,
                        size = 22.dp,
                        filled = isSelected,
                        tint = contentColor,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(tab.title, style = WolType.Label, color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun AddButton(onClick: () -> Unit, label: String) {
    val palette = DevicePalette.Ice
    Box(
        modifier = Modifier
            .size(WolDimens.NavHeight)
            .clip(CircleShape)
            .pressable(onClick = onClick, onClickLabel = label)
            .drawBehind {
                drawRect(cssLinearGradient(135f, size, 0f to palette.start, 1f to palette.end))
            },
        contentAlignment = Alignment.Center,
    ) {
        SymbolIcon(Sym.add, contentDescription = label, size = 30.dp, weight = 600, tint = WolColors.OnGradient)
    }
}
