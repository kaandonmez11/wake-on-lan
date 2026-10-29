package com.kdgames.wake_on_lan.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.domain.DomainEvent
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.ui.components.AnimatedText
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.GlassToastHost
import com.kdgames.wake_on_lan.ui.components.RollingNumber
import com.kdgames.wake_on_lan.ui.components.ToastHostState
import com.kdgames.wake_on_lan.ui.components.ToastKind
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.theme.LocalHaptics
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolDimens
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.motionSpec
import kotlinx.coroutines.flow.Flow

/**
 * Cihazlar sekmesi (spec 10.1, 10.2). Durumsuz: her şey [state]'ten gelir, aksiyonlar geri çağrılarla çıkar.
 * Tüm ekran tek bir [LazyVerticalGrid]: başlık ve hero tam genişlik; kartların eklenmesi/çıkması ve yer
 * değiştirmesi `animateItem` ile spring'li.
 */
@Composable
fun DevicesScreen(
    state: DevicesUiState,
    onSelect: (Long) -> Unit,
    onWake: (Long) -> Unit,
    onPower: (Long, PowerAction) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    events: Flow<DomainEvent>? = null,
    toasts: ToastHostState = remember { ToastHostState() },
) {
    val haptics = LocalHaptics.current
    val reduceMotion = LocalReduceMotion.current
    // Dokununca seçim anında görünür; DataStore yazımı arkadan gelir.
    var localSelection by remember { mutableStateOf<Long?>(null) }
    val selectedId = localSelection?.takeIf { id -> state.devices.any { it.id == id } } ?: state.selectedId
    val selected = state.copy(selectedId = selectedId).selected
    val cardRects = remember { mutableStateMapOf<Long, Rect>() }
    // Kart konumları halkayı çizen Box'a göre (kök yerine): sekme geçişinin kayma/ölçek katmanı
    // hem ölçüme hem çizime uygulanıp halkayı kaydırıyordu (S06 cihaz testi).
    val boxCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    var confirmCard by remember { mutableStateOf<DeviceUi?>(null) }
    val currentDevices by rememberUpdatedState(state.devices)

    LaunchedEffect(events) {
        events?.collect { e ->
            val name = currentDevices.firstOrNull { it.id == e.deviceId }?.device?.name
            val (kind, symbol) = when (e) {
                is DomainEvent.WakeSucceeded, is DomainEvent.Locked, is DomainEvent.PowerDone -> ToastKind.Success to Sym.check
                is DomainEvent.AgentInfo -> ToastKind.Info to Sym.info
                else -> ToastKind.Warning to Sym.warning
            }
            when (e) {
                is DomainEvent.WakeFailed, is DomainEvent.PowerTimedOut, is DomainEvent.AgentFailed,
                is DomainEvent.CommandRejected, is DomainEvent.NoWifi, is DomainEvent.NoMac -> haptics.error()
                is DomainEvent.Locked -> haptics.click()
                else -> Unit // uyanma başarısı haptiği orb bloom'u ile senkron çalar
            }
            toasts.show(if (name != null && e !is DomainEvent.AgentInfo) "$name · ${e.text}" else e.text, kind, symbol)
        }
    }

    Box(modifier.fillMaxSize().onGloballyPositioned { boxCoords[0] = it }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = WolDimens.ScreenHorizontal,
                end = WolDimens.ScreenHorizontal,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + WolDimens.TopExtra,
                bottom = WolDimens.NavContentPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { WifiPill(state.wifi) }
                        SettingsBubble(onOpenSettings)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Cihazlar", style = WolType.PageTitle, color = WolColors.TextPrimary, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(2.dp))
                }
            }
            if (selected != null) {
                item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                    HeroCard(
                        ui = selected,
                        onWake = { onWake(selected.id) },
                        onPower = { onPower(selected.id, it) },
                        onEdit = { toasts.show("Düzenleme bir sonraki güncellemede", ToastKind.Info, Sym.edit) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = motionSpec(SpringToken.Smooth, reduceMotion),
                            placementSpec = motionSpec(SpringToken.Smooth, reduceMotion),
                            fadeOutSpec = motionSpec(SpringToken.Snappy, reduceMotion),
                        ),
                    )
                }
            } else {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(onAdd, Modifier.animateItem(fadeInSpec = motionSpec(SpringToken.Smooth, reduceMotion), fadeOutSpec = motionSpec(SpringToken.Snappy, reduceMotion)))
                }
            }
            items(state.devices, key = { it.id }) { ui ->
                DisposableEffect(ui.id) { onDispose { cardRects.remove(ui.id) } }
                DeviceCard(
                    ui = ui,
                    onSelect = {
                        localSelection = ui.id
                        onSelect(ui.id)
                    },
                    onWake = { onWake(ui.id) },
                    onShutdown = { onPower(ui.id, PowerAction.SHUTDOWN) },
                    onAccessibilityShutdown = { confirmCard = ui },
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = motionSpec(SpringToken.Smooth, reduceMotion),
                            placementSpec = motionSpec(SpringToken.Smooth, reduceMotion),
                            fadeOutSpec = motionSpec(SpringToken.Snappy, reduceMotion),
                        )
                        .onGloballyPositioned { coords ->
                            val box = boxCoords[0]
                            if (box != null && box.isAttached && coords.isAttached) cardRects[ui.id] = box.localBoundingBoxOf(coords, clipBounds = false)
                        },
                )
            }
        }
        SelectionRing(selectedId = selected?.id, rects = cardRects, visible = state.devices.size > 1)
        GlassToastHost(toasts, Modifier.align(Alignment.TopCenter))
    }

    confirmCard?.let { ui ->
        AlertDialog(
            onDismissRequest = { confirmCard = null },
            title = { Text("Kapat · ${ui.device.name}") },
            text = { Text("Bu komut PC'ye gönderilsin mi?") },
            confirmButton = { TextButton(onClick = { confirmCard = null; onPower(ui.id, PowerAction.SHUTDOWN) }) { Text("Kapat") } },
            dismissButton = { TextButton(onClick = { confirmCard = null }) { Text("Vazgeç") } },
        )
    }
}

/**
 * Seçili kartın etrafında 2 beyaz halka (spec 10.2). Seçim değişince eski karttan yeniye `bouncy` ile kayar.
 * Konum iki kartın **canlı** konumları arasında 0→1 ilerlemeyle hesaplanır: hero boyut değiştirip kartlar
 * kayarken halka yarıda kesilip yeniden başlamaz (S06 cihaz testi). Konumlar yalnız çizimde okunur.
 */
@Composable
private fun SelectionRing(selectedId: Long?, rects: Map<Long, Rect>, visible: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(1f) }
    val target = remember { longArrayOf(Long.MIN_VALUE) }
    // Art arda dokunuşta halka eski karta sıçramaz: yeni hedefe o an çizildiği yerden gider.
    val from = remember { arrayOf<Rect?>(null) }
    val drawn = remember { arrayOf<Rect?>(null) }
    val alpha = animateFloatAsState(if (visible && selectedId != null) 1f else 0f, motionSpec(SpringToken.Snappy, reduceMotion), label = "ringAlpha")
    LaunchedEffect(selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        if (target[0] == Long.MIN_VALUE || drawn[0] == null) {
            target[0] = id
            return@LaunchedEffect
        }
        if (target[0] == id) return@LaunchedEffect
        from[0] = drawn[0]
        target[0] = id
        progress.snapTo(0f)
        progress.animateTo(1f, motionSpec(SpringToken.Bouncy, reduceMotion))
        from[0] = null
    }
    Canvas(Modifier.fillMaxSize()) {
        val a = alpha.value
        val to = rects[target[0]] ?: return@Canvas
        val start = from[0] ?: to
        val t = progress.value
        val r = Rect(
            left = start.left + (to.left - start.left) * t,
            top = start.top + (to.top - start.top) * t,
            right = start.right + (to.right - start.right) * t,
            bottom = start.bottom + (to.bottom - start.bottom) * t,
        )
        drawn[0] = r
        if (a <= 0.01f) return@Canvas
        val corner = WolDimens.CornerCard.toPx()
        listOf(3.dp to 0.95f, 7.dp to 0.35f).forEach { (gap, ringAlpha) ->
            val g = gap.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = ringAlpha * a),
                topLeft = Offset(r.left - g, r.top - g),
                size = Size(r.width + 2 * g, r.height + 2 * g),
                cornerRadius = CornerRadius(corner + g),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}

/** Spec 10.1 Wi-Fi pill: seviye ikonu (crossfade + 0.9→1), SSID · Mbps · IP (sayılar mono). */
@Composable
fun WifiPill(wifi: WifiState, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    GlassSurface(shape = RoundedCornerShape(50), modifier = modifier.height(44.dp)) {
        Row(Modifier.padding(horizontal = 14.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            // Bağlıyken kendi çizdiğimiz seviye ikonu (çubuklar tek tek akarak dolar); bağlı değilken wifi_off.
            AnimatedContent(
                targetState = wifi.connected,
                transitionSpec = {
                    (fadeIn(motionSpec(SpringToken.Smooth, reduceMotion)) + scaleIn(motionSpec(SpringToken.Smooth, reduceMotion), initialScale = 0.9f)) togetherWith
                        fadeOut(motionSpec(SpringToken.Smooth, reduceMotion))
                },
                label = "wifiIcon",
            ) { connected ->
                if (connected) WifiLevelIcon(wifi.signalLevel, Modifier.semantics { contentDescription = "Wi-Fi sinyali" })
                else SymbolIcon(Sym.wifi_off, contentDescription = "Wi-Fi sinyali", size = 18.dp, filled = true)
            }
            Spacer(Modifier.width(8.dp))
            if (!wifi.connected) {
                AnimatedText("Wi-Fi yok", style = WolType.Chip, color = WolColors.TextSecondary, maxLines = 1)
            } else {
                // SSID gerekirse "…" ile kısalır; Mbps ve IP her zaman tam ve tek satır.
                AnimatedText(
                    wifi.ssid ?: "Wi-Fi",
                    style = if (wifi.ssid != null) WolType.Technical else WolType.Chip,
                    color = WolColors.TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                wifi.linkSpeedMbps?.let { mbps ->
                    Dot()
                    RollingNumber(mbps.toLong(), style = WolType.Technical, color = WolColors.TextSecondary)
                    Text(" Mbps", style = WolType.Chip, color = WolColors.TextSecondary, maxLines = 1, softWrap = false)
                }
                wifi.phoneIp?.let { ip ->
                    Dot()
                    AnimatedText(ip, style = WolType.Technical, color = WolColors.TextSecondary, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Wi-Fi seviye ikonu (spec 10.1, 0–4): yukarı bakan 90°'lik yelpazede 4 bant. Seviye değişince her bant
 * ayrı ayrı dolar/söner (spring); glif değişimi gibi "pat" diye değişmez (S06 cihaz testi).
 */
@Composable
private fun WifiLevelIcon(level: Int, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val fills = (1..4).map { band ->
        animateFloatAsState(if (band <= level) 1f else 0.22f, motionSpec(SpringToken.Smooth, reduceMotion), label = "wifiBand$band")
    }
    Canvas(modifier.size(18.dp)) {
        val origin = Offset(size.width / 2, size.height * 0.88f)
        val outer = size.height * 0.80f
        val step = outer / 4
        val gap = step * 0.22f
        for (band in 1..4) {
            val alpha = fills[band - 1].value
            val r = step * band
            if (band == 1) {
                drawArc(Color.White, 225f, 90f, useCenter = true, topLeft = Offset(origin.x - r, origin.y - r), size = Size(2 * r, 2 * r), alpha = alpha)
            } else {
                val thickness = step - gap
                val mid = r - thickness / 2
                drawArc(
                    Color.White, 225f, 90f, useCenter = false,
                    topLeft = Offset(origin.x - mid, origin.y - mid), size = Size(2 * mid, 2 * mid),
                    alpha = alpha, style = Stroke(thickness),
                )
            }
        }
    }
}

@Composable
private fun Dot() = Text(" · ", style = WolType.Chip, color = WolColors.TextTertiary, maxLines = 1, softWrap = false)

@Composable
private fun SettingsBubble(onClick: () -> Unit) {
    GlassSurface(shape = CircleShape, modifier = Modifier.size(44.dp).pressable(onClick = onClick, onClickLabel = "Ayarlar")) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SymbolIcon(Sym.settings, contentDescription = "Ayarlar", size = 20.dp)
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(shape = RoundedCornerShape(WolDimens.CornerHero), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SymbolIcon(Sym.desktop_windows, contentDescription = null, size = 40.dp, tint = WolColors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            Text("Henüz cihaz yok", style = WolType.CardName, color = WolColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Uyandırmak istediğin PC'yi ekle", style = WolType.BodySmall, color = WolColors.TextTertiary)
            Spacer(Modifier.height(16.dp))
            GlassSurface(shape = RoundedCornerShape(50), modifier = Modifier.pressable(onClick = onAdd, onClickLabel = "Cihaz ekle")) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(Sym.add, contentDescription = null, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Cihaz ekle", style = WolType.BodyLarge, color = WolColors.TextPrimary)
                }
            }
        }
    }
}
