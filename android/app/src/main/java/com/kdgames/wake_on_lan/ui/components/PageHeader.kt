package com.kdgames.wake_on_lan.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolDimens
import com.kdgames.wake_on_lan.ui.theme.WolType

/**
 * Sekmeli ekran iskeleti: üst güvenli alan + 12, yatay 16, alttaki nav için 124 boşluk (spec 10).
 * Wi-Fi pill ve ayarlar balonu S06'da başlığın üstüne eklenir.
 */
@Composable
fun TabScaffold(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = WolDimens.ScreenHorizontal)
            .padding(top = WolDimens.TopExtra, bottom = WolDimens.NavContentPadding),
    ) {
        Text(title, style = WolType.PageTitle, color = WolColors.TextPrimary, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = WolType.BodySmall, color = WolColors.TextSecondary)
        }
        Spacer(Modifier.height(WolDimens.ComponentGap))
        content()
    }
}
