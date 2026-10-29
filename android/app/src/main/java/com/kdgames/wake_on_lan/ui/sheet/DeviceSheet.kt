package com.kdgames.wake_on_lan.ui.sheet

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.data.repo.DeviceError
import com.kdgames.wake_on_lan.data.repo.Validation
import com.kdgames.wake_on_lan.net.scan.ScanEntry
import com.kdgames.wake_on_lan.ui.components.AnimatedText
import com.kdgames.wake_on_lan.ui.components.loopProgress
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

/** Spec 11.6 ikon anahtarları + S07'de Kaan'ın isteğiyle 10 ek; 5×4 grid sırasıyla. */
val DeviceIcons = listOf(
    "pc" to "PC", "laptop" to "Dizüstü", "computer" to "Bilgisayar", "monitor" to "Monitör", "server" to "Sunucu",
    "nas" to "NAS", "router" to "Modem", "tv" to "TV", "console" to "Konsol", "phone" to "Telefon",
    "tablet" to "Tablet", "printer" to "Yazıcı", "speaker" to "Hoparlör", "headphones" to "Kulaklık", "camera" to "Kamera",
    "videocam" to "Güvenlik kamerası", "watch" to "Saat", "chip" to "Kart", "bulb" to "Lamba", "thermostat" to "Termostat",
)

/** Panelden ekrana olan çağrılar. */
class SheetActions(
    val onEdit: (SheetForm.() -> SheetForm) -> Unit,
    val onPick: (ScanEntry) -> Unit,
    val onRescan: () -> Unit,
    val onSave: () -> Unit,
    val onDelete: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * Cihaz ekle/düzenle paneli (spec 10.5). Durumsuz: [state] ViewModel'den gelir.
 */
@Composable
fun DeviceSheet(
    open: Boolean,
    state: SheetUiState,
    actions: SheetActions,
    motion: SheetMotion = rememberSheetMotion(),
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(open) {
        if (open) confirmDelete = false
        // İçerik kapalıyken de yaşadığı için klavye ve odak kapanışın başında bırakılır.
        else focus.clearFocus(force = true)
    }
    SheetHost(open = open, motion = motion, onDismiss = actions.onClose) { dragHandle, bodyScroll ->
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            SheetHeader(if (state.isEdit) "Cihazı düzenle" else "Cihaz ekle", dragHandle, actions.onClose)
            SheetBody(state, actions, bodyScroll, Modifier.weight(1f))
            BottomBar(state, onSave = actions.onSave, onDelete = { confirmDelete = true })
        }
        DeleteConfirm(
            visible = confirmDelete,
            name = state.form.name.trim().ifEmpty { "Cihaz" },
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
        )
    }
}

@Composable
private fun SheetHeader(title: String, dragHandle: Modifier, onClose: () -> Unit) {
    Column(dragHandle.fillMaxWidth().padding(horizontal = 20.dp)) {
        Box(
            Modifier
                .padding(top = 10.dp)
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.28f)),
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedText(title, style = WolType.SheetTitle, color = WolColors.TextPrimary, modifier = Modifier.weight(1f).semantics { heading() })
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .pressable(onClick = onClose, onClickLabel = "Kapat"),
                contentAlignment = Alignment.Center,
            ) { SymbolIcon(Sym.close, contentDescription = "Kapat", size = 18.dp) }
        }
    }
}

@Composable
private fun SheetBody(state: SheetUiState, actions: SheetActions, bodyScroll: NestedScrollConnection, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .nestedScroll(bodyScroll)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        PreviewCard(state)
        if (!state.isEdit) {
            Spacer(Modifier.height(22.dp))
            ScanSection(state, actions)
        }
        Spacer(Modifier.height(22.dp))
        SectionLabel("Cihaz bilgisi")
        val nameError = DeviceError.NAME_DUPLICATE in state.errors
        SheetField(
            label = "Ad",
            value = state.form.name,
            onValueChange = { v -> actions.onEdit { copy(name = v.take(Validation.NAME_MAX)) } },
            placeholder = "Ofis PC",
            error = nameError,
            tag = "field_name",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetField(
                label = "IP",
                value = state.form.ip,
                onValueChange = { v -> actions.onEdit { copy(ip = v.filter { it.isDigit() || it == '.' }.take(15)) } },
                placeholder = "192.168.1.26",
                mono = true,
                error = DeviceError.IP_INVALID in state.errors && state.form.ip.isNotBlank(),
                tag = "field_ip",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            SheetField(
                label = "MAC",
                value = state.form.mac,
                onValueChange = { v ->
                    actions.onEdit { copy(mac = v.uppercase().filter { it.isDigit() || it in 'A'..'F' || it == ':' || it == '-' }.take(17)) }
                },
                placeholder = "Boş bırakılabilir",
                mono = true,
                error = DeviceError.MAC_INVALID in state.errors || DeviceError.MAC_DUPLICATE in state.errors,
                tag = "field_mac",
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1.25f),
            )
        }
        Spacer(Modifier.height(22.dp))
        SectionLabel("İkon")
        IconPicker(state.form.iconKey, DevicePalette.of(state.form.colorIndex)) { key -> actions.onEdit { copy(iconKey = key) } }
        Spacer(Modifier.height(22.dp))
        SectionLabel("Renk")
        ColorPicker(state.form.colorIndex) { i -> actions.onEdit { copy(colorIndex = i) } }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = WolType.Label, color = WolColors.TextTertiary, modifier = modifier.padding(bottom = 8.dp, start = 4.dp))
}

/** Canlı önizleme: açık kart stili, seçili renk ve ikon akarak değişir (spec 10.5 madde 1). */
@Composable
private fun PreviewCard(state: SheetUiState) {
    val reduceMotion = LocalReduceMotion.current
    val palette = DevicePalette.of(state.form.colorIndex)
    val spec = motionSpec<Color>(SpringToken.Gentle, reduceMotion)
    val c1 by animateColorAsState(palette.openCardStart, spec, label = "previewC1")
    val c2 by animateColorAsState(palette.openCardEnd, spec, label = "previewC2")
    val edge by animateColorAsState(palette.start, spec, label = "previewEdge")
    val b1 by animateColorAsState(palette.start, spec, label = "previewB1")
    val b2 by animateColorAsState(palette.end, spec, label = "previewB2")
    // Kompakt (S07 cihaz testi: "aşağı doğru uzun"): içerik kadar yüksek, ~72 dp.
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind { drawRect(cssLinearGradient(145f, size, 0f to c1, 1f to c2)) }
            .border(1.dp, edge.copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).drawBehind { drawRect(Brush.linearGradient(listOf(b1, b2))) },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = state.form.iconKey,
                    transitionSpec = {
                        (fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)) + scaleIn(motionSpec(SpringToken.Bouncy, reduceMotion), 0.7f)) togetherWith
                            fadeOut(motionSpec(SpringToken.Snappy, reduceMotion))
                    },
                    label = "previewIcon",
                ) { k -> SymbolIcon(iconSymbol(k), contentDescription = null, size = 22.dp, tint = WolColors.OnGradient, filled = true) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.form.name.trim().ifEmpty { "Yeni cihaz" },
                    style = WolType.CardName,
                    color = WolColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("preview_name"),
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.form.ip.trim().ifEmpty { "IP yok" }, style = WolType.Technical, color = WolColors.TextOnCard, maxLines = 1)
                    Text("  ·  ", style = WolType.BodySmall, color = WolColors.TextOnCard)
                    AnimatedText(state.agentLabel, style = WolType.BodySmall, color = WolColors.TextOnCard, maxLines = 1)
                }
            }
        }
    }
}

/** "Ağda bulunanlar" (spec 7.4, 12.5): dönen gösterge, her yeni satır "pop" ile gelir. */
@Composable
private fun ScanSection(state: SheetUiState, actions: SheetActions) {
    val reduceMotion = LocalReduceMotion.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Ağda bulunanlar", style = WolType.Label, color = WolColors.TextTertiary, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.width(8.dp))
        AnimatedVisibility(
            visible = state.scan.running,
            enter = fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)) + scaleIn(motionSpec(SpringToken.Bouncy, reduceMotion), 0.5f),
            exit = fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)) + scaleOut(motionSpec(SpringToken.Snappy, reduceMotion), 0.5f),
        ) { ScanSpinner() }
        Spacer(Modifier.weight(1f))
        val rescanAlpha by animateFloatAsState(if (state.scan.running) 0.4f else 1f, motionSpec(SpringToken.Snappy, reduceMotion), label = "rescan")
        Row(
            Modifier
                .graphicsLayer { alpha = rescanAlpha }
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.08f))
                .pressable(onClick = actions.onRescan, enabled = !state.scan.running, onClickLabel = "Yeniden tara")
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SymbolIcon(Sym.refresh, contentDescription = null, size = 14.dp)
            Text("Yeniden tara", style = WolType.Chip, color = WolColors.TextPrimary)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WolDimens.CornerSettingsGroup))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(WolDimens.CornerSettingsGroup))
            .animateContentSize(motionSpec(SpringToken.Smooth, reduceMotion)),
    ) {
        val entries = state.scan.entries
        if (entries.isEmpty()) {
            AnimatedText(
                if (state.scan.running) "Ağ taranıyor…" else "Cihaz bulunamadı · IP'yi elle gir",
                style = WolType.BodySmall,
                color = WolColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
        entries.forEach { entry ->
            key(entry.key) { ScanRow(entry, picked = entry.key == state.pickedKey, onPick = { actions.onPick(entry) }) }
        }
    }
}

@Composable
private fun ScanSpinner() {
    val turn = loopProgress(900, label = "scanSpin")
    Box(
        Modifier
            .size(14.dp)
            .graphicsLayer { rotationZ = turn.value * 360f }
            .drawBehind {
                val w = 2.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(w))
                drawArc(Color.White, -90f, 100f, false, style = Stroke(w, cap = StrokeCap.Round))
            }
            .semantics { contentDescription = "Taranıyor" },
    )
}

@Composable
private fun ScanRow(entry: ScanEntry, picked: Boolean, onPick: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    // "Pop": satır ilk kez geldiğinde 0.9 → 1 `bouncy` + fade.
    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, motionSpec(SpringToken.Bouncy, reduceMotion)) }
    val dim by animateFloatAsState(if (entry.added) 0.45f else 1f, motionSpec(SpringToken.Snappy, reduceMotion), label = "rowDim")
    val bg by animateColorAsState(if (picked) Color.White.copy(alpha = 0.12f) else Color.Transparent, motionSpec(SpringToken.Snappy, reduceMotion), label = "rowBg")
    val name = entry.name
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = appear.value
                alpha = p.coerceIn(0f, 1f) * dim
                scaleX = 0.9f + 0.1f * p
                scaleY = 0.9f + 0.1f * p
            }
            .drawBehind { drawRect(bg) }
            .then(
                if (entry.added) {
                    Modifier.semantics { contentDescription = "$name, ekli" }
                } else {
                    Modifier.pressable(onClick = { haptics.tick(); onPick() }, onClickLabel = "Forma doldur", role = Role.Button)
                },
            )
            .semantics { selected = picked }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            SymbolIcon(iconSymbol(entry.iconKey), contentDescription = null, size = 18.dp, tint = WolColors.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = WolType.BodyLarge, color = WolColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(entry.ip, style = WolType.TechnicalSmall, color = WolColors.TextTertiary)
                Text("  ·  ", style = WolType.TechnicalSmall, color = WolColors.TextTertiary)
                Text(
                    entry.mac ?: "MAC bulunamadı",
                    style = if (entry.mac != null) WolType.TechnicalSmall else WolType.Label,
                    color = WolColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val (badge, color) = when {
            entry.added -> "Ekli" to WolColors.TextSecondaryAlt
            entry.agent != null -> "Agent" to WolColors.Success
            else -> "Agent yok" to WolColors.TextTertiary
        }
        Text(
            badge,
            style = WolType.Label,
            color = color,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SheetField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    error: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        when {
            error -> WolColors.Danger.copy(alpha = 0.75f)
            focused -> Color.White.copy(alpha = 0.38f)
            else -> SheetColors.FieldBorder
        },
        motionSpec(SpringToken.Snappy, reduceMotion),
        label = "fieldBorder",
    )
    val style = (if (mono) WolType.TechnicalLarge else WolType.Body).copy(color = WolColors.TextPrimary)
    val shape = RoundedCornerShape(WolDimens.CornerInput)
    Column(modifier) {
        Text(label, style = WolType.Label, color = WolColors.TextTertiary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = keyboard,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(shape)
                        .background(SheetColors.Field)
                        .border(1.dp, border, shape)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = WolColors.TextTertiary.copy(alpha = 0.6f)), maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/** Seçim halkası [cols]'luk grid'de hücreden hücreye `bouncy` ile kayar (spec 12.3). */
@Composable
private fun GridSelection(
    index: Int,
    cols: Int,
    cell: Dp,
    cellHeight: Dp,
    gap: Dp,
    content: @Composable (Modifier) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val col by animateFloatAsState((index % cols).toFloat(), motionSpec(SpringToken.Bouncy, reduceMotion), label = "selCol")
    val row by animateFloatAsState((index / cols).toFloat(), motionSpec(SpringToken.Bouncy, reduceMotion), label = "selRow")
    content(
        Modifier
            .offset {
                IntOffset(
                    (col * (cell + gap).toPx()).toInt(),
                    (row * (cellHeight + gap).toPx()).toInt(),
                )
            }
            .size(cell, cellHeight),
    )
}

@Composable
private fun IconPicker(current: String, palette: DevicePalette, onSelect: (String) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val ring by animateColorAsState(palette.start, motionSpec(SpringToken.Gentle, reduceMotion), label = "iconRing")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 10.dp
        val cell = (maxWidth - gap * 4) / 5
        val cellHeight = 56.dp
        val index = DeviceIcons.indexOfFirst { it.first == current }.coerceAtLeast(0)
        GridSelection(index, 5, cell, cellHeight, gap) { m ->
            Box(
                m
                    .clip(RoundedCornerShape(18.dp))
                    .drawBehind { drawRect(ring.copy(alpha = 0.22f)) }
                    .border(1.5.dp, ring.copy(alpha = 0.85f), RoundedCornerShape(18.dp)),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            DeviceIcons.chunked(5).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { (key, title) ->
                        val isSel = key == current
                        val tint by animateColorAsState(if (isSel) WolColors.TextPrimary else WolColors.TextTertiary, motionSpec(SpringToken.Snappy, reduceMotion), label = "iconTint")
                        Box(
                            Modifier
                                .size(cell, cellHeight)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .pressable(onClick = { if (!isSel) { haptics.tick(); onSelect(key) } }, onClickLabel = title)
                                .semantics { contentDescription = title; selected = isSel },
                            contentAlignment = Alignment.Center,
                        ) { SymbolIcon(iconSymbol(key), contentDescription = null, size = 24.dp, filled = isSel, tint = tint) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorPicker(current: Int, onSelect: (Int) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 10.dp
        val cell = (maxWidth - gap * 3) / 4
        val cellHeight = 48.dp
        val palettes = DevicePalette.entries
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            palettes.chunked(4).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { p ->
                        val isSel = p.ordinal == current
                        Box(
                            Modifier
                                .size(cell, cellHeight)
                                .clip(RoundedCornerShape(50))
                                .drawBehind { drawRect(Brush.linearGradient(listOf(p.start, p.end), start = Offset.Zero, end = Offset(size.width, size.height))) }
                                .pressable(onClick = { if (!isSel) { haptics.tick(); onSelect(p.ordinal) } }, onClickLabel = p.title)
                                .semantics { contentDescription = p.title; selected = isSel },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isSel,
                                enter = scaleIn(motionSpec(SpringToken.Bouncy, reduceMotion), 0.3f) + fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)),
                                exit = scaleOut(motionSpec(SpringToken.Snappy, reduceMotion), 0.3f) + fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)),
                            ) { SymbolIcon(Sym.check, contentDescription = null, size = 20.dp, weight = 700, tint = WolColors.OnGradient) }
                        }
                    }
                }
            }
        }
        // Beyaz iç halka seçili hapın üstünde `bouncy` ile kayar.
        GridSelection(current.coerceIn(0, palettes.lastIndex), 4, cell, cellHeight, gap) { m ->
            Box(m.padding(3.dp).border(2.dp, Color.White, RoundedCornerShape(50)))
        }
    }
}

@Composable
private fun BottomBar(state: SheetUiState, onSave: () -> Unit, onDelete: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val palette = DevicePalette.of(state.form.colorIndex)
    val spec = motionSpec<Color>(SpringToken.Gentle, reduceMotion)
    val g1 by animateColorAsState(palette.start, spec, label = "saveG1")
    val g2 by animateColorAsState(palette.end, spec, label = "saveG2")
    val enabled = state.canSave
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, motionSpec(SpringToken.Snappy, reduceMotion), label = "saveAlpha")
    val hint = state.hint
    val hintColor by animateColorAsState(
        when (hint) {
            SheetUiState.HINT_AGENT, SheetUiState.HINT_KNOWN -> WolColors.Success
            SheetUiState.HINT_NO_MAC, SheetUiState.HINT_ELSEWHERE -> WolColors.Amber
            SheetUiState.HINT_REQUIRED -> WolColors.TextTertiary
            else -> if (state.errors.isNotEmpty()) WolColors.DangerLight else WolColors.TextSecondaryAlt
        },
        motionSpec(SpringToken.Snappy, reduceMotion),
        label = "hintColor",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.08f), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
            }
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
    ) {
        AnimatedText(hint, style = WolType.BodySmall, color = hintColor, maxLines = 2, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp).testTag("sheet_hint"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.isEdit) {
                Row(
                    Modifier
                        .height(56.dp)
                        .clip(RoundedCornerShape(50))
                        .background(WolColors.Danger.copy(alpha = 0.14f))
                        .pressable(onClick = { haptics.tick(); onDelete() }, onClickLabel = "Sil")
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SymbolIcon(Sym.delete, contentDescription = null, size = 20.dp, tint = WolColors.Danger)
                    Text("Sil", style = WolType.BodyLarge, color = WolColors.Danger)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(RoundedCornerShape(50))
                    .drawBehind { drawRect(cssLinearGradient(135f, size, 0f to g1, 1f to g2)) }
                    .pressable(onClick = { haptics.click(); onSave() }, enabled = enabled, onClickLabel = null)
                    .testTag("sheet_save"),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (state.isEdit) "Kaydet" else "Cihazı ekle", style = WolType.BodyLarge, color = WolColors.OnGradient)
            }
        }
    }
}

/** "Cihaz silinsin mi?" cam onayı: scrim solar, kart `bouncy` ile büyüyerek gelir. */
@Composable
private fun DeleteConfirm(visible: Boolean, name: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)),
        exit = fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .pointerInput(Unit) { detectTapGestures { onCancel() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .animateEnterExit(
                        enter = scaleIn(motionSpec(SpringToken.Bouncy, reduceMotion), 0.85f),
                        exit = scaleOut(motionSpec(SpringToken.Snappy, reduceMotion), 0.9f),
                    )
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(WolDimens.CornerSettingsGroup))
                    .background(Color(0xFF23242D))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(WolDimens.CornerSettingsGroup))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(WolColors.Danger.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    SymbolIcon(Sym.delete, contentDescription = null, size = 24.dp, tint = WolColors.Danger)
                }
                Spacer(Modifier.height(12.dp))
                Text("Cihaz silinsin mi?", style = WolType.CardName, color = WolColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "$name, uyandırma geçmişi ve zamanlayıcıları silinir.",
                    style = WolType.BodySmall,
                    color = WolColors.TextTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConfirmButton("Vazgeç", Color.White.copy(alpha = 0.10f), WolColors.TextPrimary, Modifier.weight(1f), onCancel)
                    ConfirmButton("Sil", WolColors.Danger, WolColors.OnGradient, Modifier.weight(1f), onConfirm)
                }
            }
        }
    }
}

@Composable
private fun ConfirmButton(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .pressable(onClick = onClick, onClickLabel = null)
            .testTag("confirm_$text"),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = WolType.BodyLarge, color = fg) }
}
