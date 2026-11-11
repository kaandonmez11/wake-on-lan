package com.kdgames.wake_on_lan.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.withLink
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.repo.RetryPolicy
import com.kdgames.wake_on_lan.domain.model.ShutdownMode
import com.kdgames.wake_on_lan.domain.model.TileMode
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.pressable
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

/** Ayarlar'ın eylemleri (ViewModel'e bağlanır; testte sahte). */
class SettingsActions(
    val onBack: () -> Unit = {},
    val onRefreshPermissions: () -> Unit = {},
    val onPermissionResult: (PermissionKind, Boolean) -> Unit = { _, _ -> },
    val onInterval: (Int) -> Boolean = { true },
    val onGiveUp: (Int) -> Boolean = { true },
    val onShutdownMode: (Long, ShutdownMode) -> Unit = { _, _ -> },
    val onTileSlot: (Int, Long?) -> Unit = { _, _ -> },
    val onTileMode: (TileMode) -> Unit = {},
)

/**
 * Ayarlar (spec 10.7). Push ile açılır (AppNav), arka planı sabit canlı sahne. Ekrana her dönüşte
 * (ON_RESUME) izinler yeniden okunur; satırlar animasyonla güncellenir.
 */
@Composable
fun SettingsScreen(state: SettingsUi, actions: SettingsActions, focusTiles: Int = 0) {
    // Boş tile'dan gelindiyse "Hızlı ayarlar" bölümüne kaydır (S12).
    val tilesAnchor = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(focusTiles) {
        if (focusTiles > 0) {
            kotlinx.coroutines.delay(350)
            runCatching { tilesAnchor.bringIntoView() }
        }
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        actions.onRefreshPermissions()
        onPauseOrDispose { }
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Geri butonu içeriğin üstünde yüzer; içerik arkasından kayar, üstte boş şerit kalmaz (S10 cihaz testi).
    Box(
        Modifier
            .fillMaxSize()
            // Sayı alanındayken boş bir yere dokununca odak kalkar, değer doğrulanır (S10 cihaz testi).
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = WolDimens.ScreenHorizontal)
                .padding(top = WolDimens.TopExtra + 44.dp + 14.dp),
        ) {
            Text("Ayarlar", style = WolType.PageTitle, color = WolColors.TextPrimary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(18.dp))

            Section("İzinler") { state.permissions.forEachIndexed { i, row -> if (i > 0) Divider(); PermissionRow(row, state.snapshot, actions) } }

            Section("Zamanlayıcı tekrar denemesi") {
                val s = state.settings
                SettingRow("Tekrar aralığı", "Başarısız denemeden sonra bekleme") {
                    NumberStepperField(s.retryIntervalMin, RetryPolicy.INTERVAL_RANGE, actions.onInterval, "Tekrar aralığı", "interval")
                }
                Divider()
                SettingRow("Vazgeçme süresi", "Aralıktan kısa olamaz") {
                    NumberStepperField(s.giveUpMin, RetryPolicy.GIVE_UP_RANGE, actions.onGiveUp, "Vazgeçme süresi", "giveUp")
                }
                RetrySummary(s.retryIntervalMin, s.giveUpMin)
            }

            Section("Cihazlar · Kapatma şekli") {
                if (state.devices.isEmpty()) Text("Henüz cihaz yok", style = WolType.BodySmall, color = WolColors.TextTertiary, modifier = Modifier.padding(16.dp))
                state.devices.forEachIndexed { i, d ->
                    if (i > 0) Divider()
                    ShutdownRow(d) { actions.onShutdownMode(d.id, it) }
                }
                Text(
                    "Zorla: açık programlar beklemeden kapanır. Nazik: kaydedilmemiş iş varsa Windows kapanmayı durdurabilir.",
                    style = WolType.BodySmall,
                    color = WolColors.TextTertiary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }

            Box(Modifier.bringIntoViewRequester(tilesAnchor)) { Column {
            Section("Hızlı ayarlar") {
                Text(
                    "Bildirim panelini aşağı çek › düzenle (kalem) › \"WoL 1–3\" kutucuklarını panele sürükle. Kapalı PC'ye dokununca uyanır, açık PC'de aşağıdaki davranış çalışır.",
                    style = WolType.BodySmall,
                    color = WolColors.TextTertiary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                )
                repeat(com.kdgames.wake_on_lan.data.prefs.Settings.TILE_SLOT_COUNT) { slot ->
                    if (slot > 0) Divider()
                    TileSlotRow(slot, state.settings.tileSlots.getOrNull(slot), state.devices) { actions.onTileSlot(slot, it) }
                }
                Divider()
                TileModeRow(state.settings.tileMode, actions.onTileMode)
            }
            } }

            Section("İpuçları ve uyarılar") {
                TIPS.forEachIndexed { i, tip ->
                    if (i > 0) Divider()
                    TipRow(tip)
                }
            }

            Section("Hakkında") { AboutBlock() }
            CreditBlock()
            Spacer(Modifier.height(32.dp))
        }
        BackBubble(actions.onBack, Modifier.statusBarsPadding().padding(horizontal = WolDimens.ScreenHorizontal, vertical = WolDimens.TopExtra))
    }
}

/** Yüzen cam geri butonu (Ayarlar, Başarısız ekranı). */
@Composable
internal fun BackBubble(onBack: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(shape = CircleShape, modifier = modifier.size(44.dp).pressable(onClick = onBack, onClickLabel = "Geri")) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SymbolIcon(Sym.arrow_back_ios_new, contentDescription = "Geri", size = 18.dp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), style = WolType.Label, color = WolColors.TextTertiary, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
    GlassSurface(shape = RoundedCornerShape(WolDimens.CornerSchedule), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color.White.copy(alpha = 0.06f)))

@Composable
private fun SettingRow(title: String, subtitle: String?, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = WolType.BodyLarge, color = WolColors.TextPrimary)
            if (subtitle != null) Text(subtitle, style = WolType.BodySmall, color = WolColors.TextTertiary)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** İzin satırı: "✓ Verildi" (yeşil) ya da amber "İzin ver"/"Düzelt"; tam saatli alarm yoksa satır kırmızımsı. */
@Composable
private fun PermissionRow(row: PermissionRowUi, snapshot: PermissionSnapshot, actions: SettingsActions) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        actions.onPermissionResult(row.kind, result.values.any { it })
    }
    val bg by animateColorAsState(
        if (row.critical) WolColors.Danger.copy(alpha = 0.12f) else Color.Transparent,
        motionSpec(SpringToken.Smooth, reduceMotion),
        label = "permBg",
    )
    val subtitleColor by animateColorAsState(
        when {
            row.critical -> WolColors.Danger
            row.granted -> WolColors.TextTertiary
            else -> WolColors.Amber.copy(alpha = 0.85f)
        },
        motionSpec(SpringToken.Snappy, reduceMotion),
        label = "permSub",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(bg) }
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("perm_${row.kind.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, style = WolType.BodyLarge, color = WolColors.TextPrimary)
            com.kdgames.wake_on_lan.ui.components.AnimatedText(row.subtitle, style = WolType.BodySmall, color = subtitleColor, maxLines = 2)
        }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            targetState = row.action,
            transitionSpec = {
                (fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)) + scaleIn(motionSpec(SpringToken.Bouncy, reduceMotion), initialScale = 0.8f)) togetherWith
                    fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)) using SizeTransform(clip = false)
            },
            label = "permStatus",
        ) { action ->
            if (action == null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("perm_granted_${row.kind.name}")) {
                    SymbolIcon(Sym.check, contentDescription = null, size = 16.dp, tint = WolColors.Success)
                    Spacer(Modifier.width(4.dp))
                    Text("Verildi", style = WolType.Chip, color = WolColors.Success)
                }
            } else {
                val color = if (row.critical) WolColors.Danger else WolColors.Amber
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color.copy(alpha = 0.16f))
                        .pressable(onClick = {
                            haptics.tick()
                            when (action) {
                                PermissionAction.REQUEST -> launcher.launch(PermissionRows.runtimePermissions(row.kind))
                                PermissionAction.SETTINGS -> PermissionIntents.open(context, PermissionIntents.settings(context, row.kind, snapshot))
                            }
                        }, onClickLabel = action.label)
                        .padding(horizontal = 14.dp)
                        .testTag("perm_action_${row.kind.name}"),
                    contentAlignment = Alignment.Center,
                ) { Text(action.label, style = WolType.Chip, color = color) }
            }
        }
    }
}

@Composable
private fun RetrySummary(interval: Int, giveUp: Int) {
    val attempts = giveUp / interval + 1
    com.kdgames.wake_on_lan.ui.components.AnimatedText(
        "$interval dk arayla $attempts deneme · $giveUp dk sonra vazgeçilir",
        style = WolType.BodySmall,
        color = WolColors.TextTertiary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).testTag("retry_summary"),
    )
}

@Composable
private fun ShutdownRow(device: Device, onSelect: (ShutdownMode) -> Unit) {
    val palette = DevicePalette.of(device.colorIndex)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).drawBehind { drawRect(Brush.linearGradient(listOf(palette.start, palette.end))) })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = WolType.BodyLarge, color = WolColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (device.agentId != null) "Agent eşli" else "Agent yok · kapatma için gerekli",
                style = WolType.BodySmall,
                color = if (device.agentId != null) WolColors.TextTertiary else WolColors.Amber.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.width(8.dp))
        SlidingSegment(
            options = listOf("Zorla", "Nazik"),
            selected = if (device.shutdownMode == ShutdownMode.GRACEFUL) 1 else 0,
            palette = palette,
            tag = "shutdown_${device.id}",
            modifier = Modifier.width(150.dp),
        ) { onSelect(if (it == 1) ShutdownMode.GRACEFUL else ShutdownMode.FORCE) }
    }
}

@Composable
private fun TileSlotRow(slot: Int, deviceId: Long?, devices: List<Device>, onSelect: (Long?) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Tile ${slot + 1}", style = WolType.BodyLarge, color = WolColors.TextPrimary, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf<Device?>(null) + devices
            options.forEach { d ->
                val selected = d?.id == deviceId || (d == null && devices.none { it.id == deviceId })
                val palette = DevicePalette.of(d?.colorIndex ?: 0)
                val border by animateColorAsState(if (selected) (if (d == null) Color.White.copy(alpha = 0.5f) else palette.start) else Color.White.copy(alpha = 0.10f), motionSpec(SpringToken.Snappy, reduceMotion), label = "slotBorder")
                val bg by animateColorAsState(if (selected) (if (d == null) Color.White.copy(alpha = 0.10f) else palette.start.copy(alpha = 0.18f)) else Color.White.copy(alpha = 0.04f), motionSpec(SpringToken.Snappy, reduceMotion), label = "slotBg")
                Row(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(50))
                        .drawBehind { drawRect(bg) }
                        .border(1.5.dp, border, RoundedCornerShape(50))
                        .pressable(onClick = { if (!selected) { haptics.tick(); onSelect(d?.id) } }, onClickLabel = d?.name ?: "Boş")
                        .semantics { this.selected = selected }
                        .padding(horizontal = 12.dp)
                        .testTag("slot_${slot}_${d?.id ?: "empty"}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (d != null) {
                        Box(Modifier.size(10.dp).clip(CircleShape).drawBehind { drawRect(Brush.linearGradient(listOf(palette.start, palette.end))) })
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(d?.name ?: "Boş", style = WolType.Chip, color = if (selected) WolColors.TextPrimary else WolColors.TextSecondary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TileModeRow(mode: TileMode, onSelect: (TileMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Tile davranışı", style = WolType.BodyLarge, color = WolColors.TextPrimary)
        Text("Açık PC'nin tile'ına dokununca", style = WolType.BodySmall, color = WolColors.TextTertiary)
        Spacer(Modifier.height(10.dp))
        SlidingSegment(
            options = listOf("A · Panel", "B · Dialog", "C · 2 dokunuş"),
            selected = mode.ordinal,
            palette = DevicePalette.Ice,
            tag = "tilemode",
            modifier = Modifier.fillMaxWidth(),
        ) { onSelect(TileMode.entries[it]) }
        Spacer(Modifier.height(8.dp))
        com.kdgames.wake_on_lan.ui.components.AnimatedText(
            when (mode) {
                TileMode.A -> "Tam ekran güç paneli: büyük orb, basılı tutarak kapat; Kilitle, Uyku, Hazırda, Yeniden."
                TileMode.B -> "Hızlı ayarlar panelinin üstünde küçük cam pencere, aynı basılı tutma butonları."
                TileMode.C -> "İlk dokunuşta \"Kapatmak için tekrar dokun\", ikinci dokunuş kapatır."
            },
            style = WolType.BodySmall,
            color = WolColors.TextSecondary,
            maxLines = 3,
        )
    }
}

/** Seçim zemini `bouncy` kayan cam segment (spec 12.3). */
@Composable
fun SlidingSegment(options: List<String>, selected: Int, palette: DevicePalette, tag: String, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val pos by animateFloatAsState(selected.toFloat(), motionSpec(SpringToken.Bouncy, reduceMotion), label = "segment")
    val shape = RoundedCornerShape(50)
    Box(modifier.clip(shape).background(Color.White.copy(alpha = 0.06f)).padding(3.dp)) {
        Box(
            Modifier.matchParentSize().drawBehind {
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (options.size - 1)) / options.size
                val x = (w + gap) * pos
                drawRoundRect(
                    cssLinearGradient(135f, androidx.compose.ui.geometry.Size(w, size.height), 0f to palette.start.copy(alpha = 0.40f), 1f to palette.end.copy(alpha = 0.28f)),
                    topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(w, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEachIndexed { i, label ->
                val color by animateColorAsState(if (i == selected) WolColors.TextPrimary else WolColors.TextSecondary, motionSpec(SpringToken.Snappy, reduceMotion), label = "segColor")
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(shape)
                        .pressable(onClick = { if (i != selected) { haptics.tick(); onSelect(i) } }, onClickLabel = label)
                        .semantics { this.selected = i == selected }
                        .testTag("${tag}_$i"),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = WolType.Chip, color = color, maxLines = 1) }
            }
        }
    }
}

/** Sürüm + lisanslar (assets/licenses). Lisans satırına dokununca metin açılır. */
@Composable
private fun AboutBlock() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("?")
    }
    val files = remember { runCatching { context.assets.list("licenses")?.sorted().orEmpty() }.getOrDefault(emptyList()) }
    SettingRow("WoL Denetim Merkezi", "Sürüm $version") {}
    files.forEach { f ->
        Divider()
        LicenseRow(f)
    }
}

/**
 * İmza (S10, Kaan): kartın dışında, ortalı. "DESIGNED AND DEVELOPED BY" ve altında aynı stil/renkte, biraz daha
 * büyük ve altı çizili "KAAN DONMEZ"; ada dokununca LinkedIn.
 */
@Composable
private fun CreditBlock() {
    val label = WolType.Label.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp))
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("credit_block"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("DESIGNED AND DEVELOPED BY", style = label, color = WolColors.TextTertiary)
        Spacer(Modifier.height(6.dp))
        val text = androidx.compose.ui.text.buildAnnotatedString {
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Url(
                    CREDIT_URL,
                    androidx.compose.ui.text.TextLinkStyles(
                        style = androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                    ),
                ),
            ) { append("KAAN DONMEZ") }
        }
        Text(
            text,
            style = label.copy(fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = WolColors.TextTertiary,
            modifier = Modifier.testTag("credit"),
        )
    }
}

internal const val CREDIT_URL = "https://www.linkedin.com/in/akaandonmez/"

/** İpucu ya da uyarı (S10, Kaan): uyarılar önemli koşullar, ipuçları kolaylıklar. */
internal data class Tip(val warning: Boolean, val title: String, val text: String)

internal val TIPS = listOf(
    Tip(true, "BIOS'ta Wake-on-LAN açık olmalı", "BIOS/UEFI'de \"Wake on LAN\" ya da \"Power On by PCI-E\" kapalıysa kapalı PC paketi duymaz."),
    Tip(true, "Ağ kartı ayarları", "Aygıt Yöneticisi › ağ kartı: \"Magic Packet ile uyandır\" ve Güç Yönetimi'nde \"Bu aygıtın bilgisayarı uyandırmasına izin ver\" açık olmalı."),
    Tip(true, "Hızlı Başlat kapalı olmalı", "Windows'ta Hızlı Başlat açıkken kapatılan PC çoğu zaman uyanmaz: Denetim Masası › Güç Seçenekleri › Güç düğmelerinin yapacaklarını seçin."),
    Tip(true, "Aynı Wi-Fi ağında ol", "Uyandırma ve komutlar yalnız PC ile aynı ağdayken çalışır; mobil veriden ya da misafir ağından ulaşılamaz."),
    Tip(true, "Pil \"Kısıtlanmamış\" olsun", "Pil kısıtlıysa zamanlayıcılar ve \"Eve gelince\" gecikebilir ya da hiç çalışmayabilir (İzinler › Pil)."),
    Tip(false, "PC'yi kabloyla bağla", "Kablolu (Ethernet) bağlantı en güveniliridir; çoğu Wi-Fi kartı PC kapalıyken paketi dinlemez."),
    Tip(false, "Router'da sabit IP", "Bu PC'lere DHCP rezervasyonuyla sabit IP ver. IP değişirse uygulama yeni adresi kendisi bulur ama birkaç saniye sürebilir."),
    Tip(false, "Modern Bekleme'li PC'ler", "Bu PC'ler uykudan paketle uyanmaz; telefondaki \"Uyku\" bu yüzden Hazırda Beklet olarak çalışır ve PC sonra uyandırılabilir."),
    Tip(false, "\"Başka telefona eşli\" görünürse", "PC'de yönetici olarak `wolagent reset` çalıştır; uygulama PC'yi yeniden eşler."),
    Tip(false, "Hızlı kullanım", "Kartlara basılı tutarak düzenle/sil; Cihazlar ile Otomasyon arasında ekranı yatay kaydırarak geç."),
)

@Composable
private fun TipRow(tip: Tip) {
    val color = if (tip.warning) WolColors.Amber else DevicePalette.Ice.start
    Row(Modifier.fillMaxWidth().padding(16.dp).testTag(if (tip.warning) "warning_row" else "tip_row"), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            SymbolIcon(if (tip.warning) Sym.warning else Sym.lightbulb, contentDescription = if (tip.warning) "Uyarı" else "İpucu", size = 16.dp, tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tip.title, style = WolType.BodyLarge, color = WolColors.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(tip.text, style = WolType.BodySmall, color = WolColors.TextSecondary)
        }
    }
}

@Composable
private fun LicenseRow(file: String) {
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    var open by remember { mutableStateOf(false) }
    val text = remember(open) { if (open) runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrDefault("") else "" }
    val turn by animateFloatAsState(if (open) 90f else 0f, motionSpec(SpringToken.Snappy, reduceMotion), label = "licTurn")
    Column(Modifier.fillMaxWidth().pressable(onClick = { open = !open }, onClickLabel = "Lisans")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(licenseName(file), style = WolType.Body, color = WolColors.TextSecondary, modifier = Modifier.weight(1f))
            SymbolIcon(Sym.chevron_right, contentDescription = null, size = 18.dp, tint = WolColors.TextTertiary, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(motionSpec(SpringToken.Smooth, reduceMotion)) + expandVertically(motionSpec(SpringToken.Smooth, reduceMotion)),
            exit = fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)) + shrinkVertically(motionSpec(SpringToken.Smooth, reduceMotion)),
        ) {
            Text(
                text,
                style = WolType.TechnicalSmall,
                color = WolColors.TextTertiary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            )
        }
    }
}

/** Lisans dosyasının görünen adı. */
internal fun licenseName(file: String): String = when (file.substringBeforeLast('.')) {
    "FluentUI-System-Icons-MIT" -> "Fluent UI System Icons · MIT"
    "JetBrainsMono-OFL" -> "JetBrains Mono · OFL 1.1"
    "MaterialSymbols-Apache-2.0" -> "Material Symbols · Apache 2.0"
    "Onest-OFL" -> "Onest · OFL 1.1"
    "WINDOWS_ICONS" -> "Windows güç ikonları"
    else -> file.substringBeforeLast('.').replace('-', ' ')
}
