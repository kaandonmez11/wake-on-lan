package com.kdgames.wake_on_lan.debug

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.background.LivingBackground
import com.kdgames.wake_on_lan.ui.background.Scene
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.LocalHazeState
import com.kdgames.wake_on_lan.ui.components.RollingNumber
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import com.kdgames.wake_on_lan.ui.theme.GlassVariant
import com.kdgames.wake_on_lan.ui.theme.LocalHaptics
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolDimens
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import com.kdgames.wake_on_lan.ui.theme.WolType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** S01 tasarım sistemi vitrini: cam, spring'ler, haptikler, sahneler, sayılar, ikonlar, FPS. */
@Composable
fun DesignTab() {
    WolTheme {
        var scene by remember { mutableStateOf(Scene.Devices) }
        var showFps by remember { mutableStateOf(false) }
        val hazeState = rememberHazeState()
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            Box(Modifier.fillMaxSize()) {
                LivingBackground(
                    scene = scene,
                    continuous = scene == Scene.FullScreen,
                    modifier = Modifier.hazeSource(hazeState, zIndex = 0f),
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(WolDimens.ComponentGap),
                ) {
                    Section("Sahne") {
                        ChipRow(
                            options = listOf(Scene.Devices to "Cihazlar", Scene.Automation to "Otomasyon", Scene.FullScreen to "Tam ekran"),
                            selected = scene,
                            onSelect = { scene = it },
                        )
                    }
                    Section("Cam yüzeyler") { GlassShowcase() }
                    Section("Spring token'ları") { SpringShowcase() }
                    Section("Haptikler") { HapticsShowcase() }
                    Section("Haneli sayı") { RollingShowcase() }
                    Section("Material Symbols · FILL") { SymbolShowcase() }
                    Section("Yazı tipleri") { TypeShowcase() }
                    Section("Performans") {
                        GlassButton(if (showFps) "FPS göstergesini kapat" else "FPS göstergesini aç") { showFps = !showFps }
                    }
                }
                if (showFps) FpsOverlay(Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = WolType.BodyLarge, color = WolColors.TextPrimary)
        content()
    }
}

@Composable
private fun GlassButton(label: String, onClick: () -> Unit) {
    GlassSurface(shape = CircleShape, modifier = Modifier.pressable(onClick = onClick)) {
        Text(label, style = WolType.Chip, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            GlassSurface(
                shape = CircleShape,
                variant = if (value == selected) GlassVariant.Strong else GlassVariant.Normal,
                accent = DevicePalette.Ice.start,
                modifier = Modifier.pressable(onClick = { onSelect(value) }),
            ) {
                Text(
                    label,
                    style = WolType.Chip,
                    color = if (value == selected) WolColors.TextPrimary else WolColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun GlassShowcase() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            Triple(GlassVariant.Normal, "Normal", RoundedCornerShape(WolDimens.CornerCard)),
            Triple(GlassVariant.Strong, "Güçlü", RoundedCornerShape(WolDimens.CornerHero)),
            Triple(GlassVariant.Dock, "Dock", CircleShape),
        ).forEach { (variant, label, shape) ->
            GlassSurface(
                shape = shape,
                variant = variant,
                accent = DevicePalette.Ice.start,
                modifier = Modifier.weight(1f).height(110.dp),
            ) {
                Text(label, style = WolType.CardName, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun SpringShowcase() {
    var flipped by remember { mutableStateOf(false) }
    GlassSurface(shape = RoundedCornerShape(WolDimens.CornerCard), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SpringToken.entries.forEach { token ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(token.name.lowercase(), style = WolType.Technical, modifier = Modifier.width(64.dp))
                    BoxWithConstraints(Modifier.weight(1f).height(24.dp)) {
                        val x by animateDpAsState(
                            targetValue = if (flipped) maxWidth - 24.dp else 0.dp,
                            animationSpec = token.spec(),
                            label = "springDemo",
                        )
                        Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(Color.White.copy(alpha = 0.12f)))
                        Box(
                            Modifier
                                .offset(x = x)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(DevicePalette.Ice.start),
                        )
                    }
                }
            }
            Text("Animasyon sürerken tekrar bas: yön hızı koruyarak değişir.", style = WolType.BodySmall, color = WolColors.TextSecondary)
            GlassButton("Oynat") { flipped = !flipped }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HapticsShowcase() {
    val haptics = LocalHaptics.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassButton("TICK") { haptics.tick() }
        GlassButton("LOW_TICK 0.2") { haptics.lowTick(0.2f) }
        GlassButton("LOW_TICK 0.8") { haptics.lowTick(0.8f) }
        GlassButton("THUD") { haptics.thud() }
        GlassButton("CLICK") { haptics.click() }
        GlassButton("Başarı (SPIN)") { haptics.success() }
        GlassButton("İptal") { haptics.cancel() }
        GlassButton("Hata (çift tick)") { haptics.error() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RollingShowcase() {
    var value by remember { mutableLongStateOf(7L) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RollingNumber(value, style = WolType.TechnicalLarge.copy(fontSize = WolType.PageTitle.fontSize))
        Text("ms", style = WolType.TechnicalLarge, color = WolColors.TextSecondary)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassButton("−1") { value = (value - 1).coerceAtLeast(0) }
        GlassButton("+1") { value += 1 }
        GlassButton("+17") { value += 17 }
        GlassButton("Sıfırla") { value = 0 }
    }
}

@Composable
private fun SymbolShowcase() {
    var filled by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(Sym.desktop_windows, Sym.bolt, Sym.settings, Sym.power_settings_new, Sym.home, Sym.verified_user).forEach {
            SymbolIcon(it, contentDescription = null, size = 28.dp, filled = filled)
        }
    }
    Spacer(Modifier.height(4.dp))
    GlassButton(if (filled) "FILL → 0" else "FILL → 1") { filled = !filled }
}

@Composable
private fun TypeShowcase() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Cihazlar", style = WolType.PageTitle)
        Text("Ofis PC", style = WolType.HeroName)
        Text("Kart adı · Onest 700", style = WolType.CardName)
        Text("Gövde metni · Onest 500", style = WolType.Body, color = WolColors.TextSecondary)
        Text("192.168.1.24 · D8:BB:C1:0A:2F:91", style = WolType.Technical, color = WolColors.TextSecondary)
        Text("0123456789 · 940 Mbps · 3 ms", style = WolType.TechnicalLarge)
    }
}

/** Basit kare ölçer: son ~1 sn'nin ortalama fps'i ve en uzun kare süresi. */
@Composable
private fun FpsOverlay(modifier: Modifier = Modifier) {
    var fps by remember { mutableFloatStateOf(0f) }
    var worstMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var windowStart = 0L
        var last = 0L
        var frames = 0
        var worst = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    worst = maxOf(worst, now - last)
                    frames++
                } else {
                    windowStart = now
                }
                last = now
                if (now - windowStart >= 1_000_000_000L && frames > 0) {
                    fps = frames * 1e9f / (now - windowStart)
                    worstMs = worst / 1e6f
                    windowStart = now
                    frames = 0
                    worst = 0L
                }
            }
        }
    }
    GlassSurface(shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("%.0f fps".format(fps), style = WolType.TechnicalLarge, color = if (fps >= 110f) WolColors.Success else WolColors.Amber)
            Text("en uzun kare %.1f ms".format(worstMs), style = WolType.TechnicalSmall, color = if (worstMs <= 12f) WolColors.TextSecondary else WolColors.Danger)
        }
    }
}
