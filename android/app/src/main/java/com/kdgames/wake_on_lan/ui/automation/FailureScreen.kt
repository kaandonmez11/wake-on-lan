package com.kdgames.wake_on_lan.ui.automation

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.domain.schedule.FailureLabels
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.breathing
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.devices.iconSymbol
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
import kotlinx.coroutines.delay

/**
 * Zamanlayıcı başarısız ekranı (spec 10.4). Push ile açılır (AppNav), arka planı sabit canlı sahne.
 * Kendi arka planı yok. Bölümler sırayla `smooth` yayla süzülerek gelir.
 */
@Composable
fun FailureScreen(ui: FailureUi?, onBack: () -> Unit, onRetry: (FailureUi) -> Unit) {
    val haptics = LocalHaptics.current
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (ui == null) return@Column
        // Geri butonu üstte yüzer; içerik arkasından kayar (S10 cihaz testi).
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = WolDimens.ScreenHorizontal)
                .padding(top = WolDimens.TopExtra + 44.dp + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Reveal(0) { FailureOrb(ui) }
            Spacer(Modifier.height(18.dp))
            Reveal(1) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        ui.title,
                        style = WolType.ScreenTitle,
                        color = WolColors.TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() }.testTag("failure_title"),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(ui.subtitle, style = WolType.BodySmall, color = WolColors.TextTertiary, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(22.dp))
            Reveal(2) { AttemptsCard(ui) }
            if (ui.showChecks) {
                Spacer(Modifier.height(14.dp))
                Reveal(3) { ChecksCard() }
            }
            Spacer(Modifier.height(20.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = WolDimens.ScreenHorizontal, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassSurface(
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(56.dp).weight(1f).pressable(onClick = { haptics.tick(); onBack() }, onClickLabel = "Tamam").testTag("failure_ok"),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tamam", style = WolType.BodyLarge, color = WolColors.TextPrimary)
                }
            }
            Box(
                Modifier
                    .height(56.dp)
                    .weight(1.4f)
                    .clip(RoundedCornerShape(50))
                    .drawBehind { drawRect(cssLinearGradient(135f, size, 0f to ui.palette.start, 1f to ui.palette.end)) }
                    .pressable(onClick = { haptics.click(); onRetry(ui) }, onClickLabel = "Şimdi tekrar dene")
                    .testTag("failure_retry"),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(Sym.refresh, contentDescription = null, size = 20.dp, tint = WolColors.OnGradient)
                    Spacer(Modifier.width(6.dp))
                    Text("Şimdi tekrar dene", style = WolType.BodyLarge, color = WolColors.OnGradient, maxLines = 1)
                }
            }
        }
    }
    com.kdgames.wake_on_lan.ui.settings.BackBubble(onBack, Modifier.statusBarsPadding().padding(horizontal = WolDimens.ScreenHorizontal, vertical = WolDimens.TopExtra))
    }
}

/** Girişte sırayla: yukarıdan 16 dp süzülür + solarak gelir (katman aşamasında). */
@Composable
private fun Reveal(index: Int, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val p = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        delay(60L * index)
        p.animateTo(1f, motionSpec(SpringToken.Smooth, reduceMotion))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = p.value
            translationY = (1f - p.value) * 16.dp.toPx()
        },
    ) { content() }
}

/** 96 kırmızımsı cam daire + cihaz ikonu; arkada yavaş nefes alan kırmızı hale. */
@Composable
private fun FailureOrb(ui: FailureUi) {
    val breath = breathing(3_000, 0.92f, 1.08f, label = "failureHalo")
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(132.dp)
                .graphicsLayer { scaleX = breath.value; scaleY = breath.value }
                .drawBehind { drawCircle(Brush.radialGradient(listOf(WolColors.Danger.copy(alpha = 0.28f), Color.Transparent))) },
        )
        GlassSurface(shape = CircleShape, accent = WolColors.Danger, modifier = Modifier.size(96.dp)) {
            Box(Modifier.matchParentSize().background(WolColors.Danger.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                SymbolIcon(iconSymbol(ui.iconKey), contentDescription = null, size = 42.dp, tint = WolColors.DangerLight, filled = true)
            }
        }
    }
}

@Composable
private fun AttemptsCard(ui: FailureUi) {
    GlassSurface(shape = RoundedCornerShape(WolDimens.CornerSchedule), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(ui.header, style = WolType.BodyLarge, color = WolColors.TextPrimary, modifier = Modifier.testTag("failure_header"))
            Spacer(Modifier.height(2.dp))
            Text(ui.gaveUp, style = WolType.BodySmall, color = WolColors.Danger)
            Spacer(Modifier.height(14.dp))
            ui.attempts.forEachIndexed { i, a ->
                AttemptLine(a, first = i == 0, last = i == ui.attempts.lastIndex)
            }
        }
    }
}

/** Zaman çizgisi satırı: nokta + dikey bağlantı çizgisi, mono saat, sebep. */
@Composable
private fun AttemptLine(a: AttemptRow, first: Boolean, last: Boolean) {
    val dot = if (a.ok) WolColors.Success else WolColors.Danger
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(18.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    val cy = 11.dp.toPx()
                    val line = Color.White.copy(alpha = 0.14f)
                    val w = 1.5.dp.toPx()
                    if (!first) drawLine(line, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, cy), w)
                    if (!last) drawLine(line, androidx.compose.ui.geometry.Offset(x, cy), androidx.compose.ui.geometry.Offset(x, size.height), w)
                    drawCircle(dot.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, cy))
                    drawCircle(dot, radius = 3.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, cy))
                },
        )
        Spacer(Modifier.width(10.dp))
        Row(Modifier.padding(bottom = if (last) 0.dp else 12.dp).testTag("failure_attempt"), verticalAlignment = Alignment.Top) {
            Text(a.time, style = WolType.TechnicalLarge, color = WolColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Text(a.reason, style = WolType.Body, color = WolColors.TextPrimary)
        }
    }
}

@Composable
private fun ChecksCard() {
    GlassSurface(shape = RoundedCornerShape(WolDimens.CornerSchedule), modifier = Modifier.fillMaxWidth().testTag("failure_checks")) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SymbolIcon(Sym.info, contentDescription = null, size = 18.dp, tint = WolColors.Amber)
                Spacer(Modifier.width(8.dp))
                Text("Kontrol et", style = WolType.BodyLarge, color = WolColors.TextPrimary)
            }
            Spacer(Modifier.height(12.dp))
            FailureLabels.CHECKS.forEach { c ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                        SymbolIcon(Sym.check, contentDescription = null, size = 14.dp, tint = WolColors.TextSecondary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(c, style = WolType.Body, color = WolColors.TextSecondary)
                }
            }
        }
    }
}
