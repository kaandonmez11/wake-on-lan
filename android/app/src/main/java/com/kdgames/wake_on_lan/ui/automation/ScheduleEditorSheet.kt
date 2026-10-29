package com.kdgames.wake_on_lan.ui.automation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.schedule.ScheduleEngine
import com.kdgames.wake_on_lan.ui.components.AnimatedText
import com.kdgames.wake_on_lan.ui.components.WinPowerIcon
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.sheet.DeleteConfirm
import com.kdgames.wake_on_lan.ui.sheet.SectionLabel
import com.kdgames.wake_on_lan.ui.sheet.SheetHeader
import com.kdgames.wake_on_lan.ui.sheet.SheetHost
import com.kdgames.wake_on_lan.ui.sheet.SheetMotion
import com.kdgames.wake_on_lan.ui.sheet.rememberSheetMotion
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import com.kdgames.wake_on_lan.ui.theme.LocalHaptics
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.cssLinearGradient
import com.kdgames.wake_on_lan.ui.theme.motionSpec
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class EditorActions(
    val onEdit: (EditorForm.() -> EditorForm) -> Unit,
    val onToggleDay: (Int) -> Unit,
    val onShiftMinutes: (Int) -> Unit,
    val onSave: () -> Unit,
    val onDelete: () -> Unit,
    val onClose: () -> Unit,
)

private val ACTIONS = listOf(PowerAction.WAKE, PowerAction.LOCK, PowerAction.SLEEP, PowerAction.HIBERNATE, PowerAction.RESTART, PowerAction.SHUTDOWN)
private val DAYS = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")

/** Zamanlayıcı ekle/düzenle paneli (spec 10.3). Cihaz panelinin kabuğunu kullanır. */
@Composable
fun ScheduleEditorSheet(
    open: Boolean,
    state: EditorUiState,
    actions: EditorActions,
    motion: SheetMotion = rememberSheetMotion(),
) {
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) confirmDelete = false }
    SheetHost(open = open, motion = motion, onDismiss = actions.onClose) { dragHandle, bodyScroll ->
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            SheetHeader(if (state.isEdit) "Zamanlayıcıyı düzenle" else "Zamanlayıcı ekle", dragHandle, actions.onClose)
            EditorBody(state, actions, bodyScroll, Modifier.weight(1f))
            EditorBar(state, onSave = actions.onSave, onDelete = { confirmDelete = true })
        }
        DeleteConfirm(
            visible = confirmDelete,
            title = "Zamanlayıcı silinsin mi?",
            message = "${state.device?.name ?: "Cihaz"} · ${ScheduleLabels.action(state.form.action)} zamanlayıcısı silinir.",
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
        )
    }
}

@Composable
private fun EditorBody(state: EditorUiState, actions: EditorActions, bodyScroll: NestedScrollConnection, modifier: Modifier) {
    val palette = DevicePalette.of(state.device?.colorIndex ?: 0)
    Column(
        modifier
            .fillMaxWidth()
            .nestedScroll(bodyScroll)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        SectionLabel("Cihaz")
        DevicePicker(state) { id -> actions.onEdit { copy(deviceId = id) } }
        Spacer(Modifier.height(20.dp))
        SectionLabel("Aksiyon")
        ActionPicker(state, palette) { a -> actions.onEdit { copy(action = a) } }
        Spacer(Modifier.height(20.dp))
        SectionLabel("Tetikleyici")
        TriggerSegment(palette)
        Spacer(Modifier.height(20.dp))
        SectionLabel("Günler")
        DayPicker(state.form.daysMask, palette, actions.onToggleDay) { mask -> actions.onEdit { copy(daysMask = mask) } }
        Spacer(Modifier.height(20.dp))
        SectionLabel("Saat")
        TimePicker(state.form.minuteOfDay, actions.onShiftMinutes)
        Spacer(Modifier.height(20.dp))
        SectionLabel("Bildirim")
        NotifyRow(state.form.notify, palette) { on -> actions.onEdit { copy(notify = on) } }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DevicePicker(state: EditorUiState, onSelect: (Long) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.devices.forEach { d ->
            val selected = d.id == state.form.deviceId
            val palette = DevicePalette.of(d.colorIndex)
            val border by animateColorAsState(if (selected) palette.start else Color.White.copy(alpha = 0.10f), motionSpec(SpringToken.Snappy, reduceMotion), label = "devBorder")
            val bg by animateColorAsState(if (selected) palette.start.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f), motionSpec(SpringToken.Snappy, reduceMotion), label = "devBg")
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .drawBehind { drawRect(bg) }
                    .border(1.5.dp, border, RoundedCornerShape(50))
                    .pressable(onClick = { if (!selected) { haptics.tick(); onSelect(d.id) } }, onClickLabel = d.name)
                    .semantics { this.selected = selected }
                    .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScheduleBubble(d.iconKey, palette, size = 30.dp)
                Spacer(Modifier.width(8.dp))
                Text(d.name, style = WolType.BodySmall, color = WolColors.TextPrimary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActionPicker(state: EditorUiState, palette: DevicePalette, onSelect: (PowerAction) -> Unit) {
    val haptics = LocalHaptics.current
    val hasAgent = state.device?.agentId != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ACTIONS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    ActionTile(
                        action = a,
                        selected = a == state.form.action,
                        available = !a.requiresAgent || hasAgent,
                        palette = palette,
                        onClick = { if (a != state.form.action) { haptics.tick(); onSelect(a) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionTile(action: PowerAction, selected: Boolean, available: Boolean, palette: DevicePalette, onClick: () -> Unit, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val fill by animateFloatAsState(if (selected) 1f else 0f, motionSpec(SpringToken.Snappy, reduceMotion), label = "tileFill")
    val alpha by animateFloatAsState(if (available) 1f else 0.45f, motionSpec(SpringToken.Snappy, reduceMotion), label = "tileAlpha")
    val label = ScheduleLabels.action(action)
    val tint = when {
        action == PowerAction.WAKE -> WolColors.Success
        action == PowerAction.SHUTDOWN -> WolColors.Danger
        else -> WolColors.TextPrimary
    }
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .height(66.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.05f))
                if (fill > 0f) drawRect(cssLinearGradient(135f, size, 0f to palette.start.copy(alpha = 0.30f * fill), 1f to palette.end.copy(alpha = 0.22f * fill)))
            }
            .border(1.5.dp, palette.start.copy(alpha = 0.85f * fill), shape)
            .pressable(onClick = onClick, onClickLabel = label)
            .semantics { this.selected = selected; contentDescription = label }
            .testTag("action_${action.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (action == PowerAction.WAKE) SymbolIcon(Sym.power_settings_new, contentDescription = null, size = 22.dp, tint = tint, filled = selected)
        else WinPowerIcon(action, tint = tint, size = 20.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, style = WolType.Chip, color = WolColors.TextPrimary)
    }
}

@Composable
private fun TriggerSegment(palette: DevicePalette) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.06f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(shape)
                .drawBehind { drawRect(cssLinearGradient(135f, size, 0f to palette.start.copy(alpha = 0.35f), 1f to palette.end.copy(alpha = 0.25f))) }
                .semantics { selected = true },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SymbolIcon(Sym.schedule, contentDescription = null, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("Saat", style = WolType.Chip, color = WolColors.TextPrimary)
            }
        }
        // "Eve gelince" S09'da: görünür ama pasif.
        Box(
            Modifier.weight(1f).height(40.dp).graphicsLayer { alpha = 0.45f }.semantics { disabled() },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SymbolIcon(Sym.home, contentDescription = null, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("Eve gelince · yakında", style = WolType.Chip, color = WolColors.TextSecondary)
            }
        }
    }
}

/**
 * "Bildirim gönder": açılınca Android 13+ bildirim izni istenir. Reddedildiyse ikinci dokunuş uygulamanın
 * bildirim ayarlarını açar; izin yokken anahtar açık kalmaz.
 */
@Composable
private fun NotifyRow(checked: Boolean, palette: DevicePalette, onChange: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun granted() = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var denied by remember { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) onChange(true)
    }
    val toggle = { on: Boolean ->
        when {
            !on -> onChange(false)
            granted() -> { denied = false; onChange(true) }
            denied -> context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else -> launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .pressable(onClick = { toggle(!checked) }, onClickLabel = "Bildirim gönder")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolIcon(Sym.notifications, contentDescription = null, size = 22.dp, filled = checked)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Bildirim gönder", style = WolType.BodyLarge, color = WolColors.TextPrimary)
            AnimatedText(
                if (denied && !checked) "Bildirim izni kapalı · dokun, ayarlardan aç" else "Çalışınca emri ve sonucu bildir",
                style = WolType.BodySmall,
                color = if (denied && !checked) WolColors.Amber else WolColors.TextTertiary,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(10.dp))
        GradientSwitch(checked = checked, palette = palette, onCheckedChange = { toggle(it) }, modifier = Modifier.testTag("notify_switch"))
    }
}

@Composable
private fun DayPicker(mask: Int, palette: DevicePalette, onToggle: (Int) -> Unit, onPreset: (Int) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
        listOf("Hafta içi" to ScheduleEngine.WEEKDAYS, "Hafta sonu" to ScheduleEngine.WEEKEND, "Her gün" to ScheduleEngine.EVERY_DAY).forEach { (label, m) ->
            val on = mask == m
            val bg by animateColorAsState(if (on) palette.start.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f), motionSpec(SpringToken.Snappy, reduceMotion), label = "preset")
            Text(
                label,
                style = WolType.Chip,
                color = WolColors.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .drawBehind { drawRect(bg) }
                    .pressable(onClick = { haptics.tick(); onPreset(m) }, onClickLabel = label)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAYS.forEachIndexed { i, name ->
            val bit = 1 shl i
            val on = mask and bit != 0
            val fill by animateFloatAsState(if (on) 1f else 0f, motionSpec(SpringToken.Bouncy, reduceMotion), label = "day")
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .drawBehind {
                        drawRect(Color.White.copy(alpha = 0.06f))
                        if (fill > 0f) drawRect(cssLinearGradient(135f, size, 0f to palette.start.copy(alpha = fill), 1f to palette.end.copy(alpha = fill)))
                    }
                    .pressable(onClick = { haptics.tick(); onToggle(bit) }, onClickLabel = name, role = Role.Checkbox)
                    .semantics { selected = on; contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                val color by animateColorAsState(if (on) WolColors.OnGradient else WolColors.TextSecondary, motionSpec(SpringToken.Snappy, reduceMotion), label = "dayText")
                Text(name, style = WolType.Chip, color = color)
            }
        }
    }
}

/** Saat seçici (mono): ok tuşları (basılı tutunca hızlanır) ya da sayının üstünde dikey sürükleme; haneler yuvarlanır. */
@Composable
private fun TimePicker(minuteOfDay: Int, onShift: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeUnit(minuteOfDay / 60, label = "Saat", step = 60, onShift = onShift, tag = "time_hour")
        Text(":", style = WolType.ScreenTitle.copy(fontFamily = com.kdgames.wake_on_lan.ui.theme.JetBrainsMono), color = WolColors.TextTertiary, modifier = Modifier.padding(horizontal = 10.dp))
        TimeUnit(minuteOfDay % 60, label = "Dakika", step = 1, onShift = onShift, tag = "time_minute")
    }
}

@Composable
private fun TimeUnit(value: Int, label: String, step: Int, onShift: (Int) -> Unit, tag: String) {
    val haptics = LocalHaptics.current
    val shift by rememberUpdatedState(onShift)
    val stepPx = with(LocalDensity.current) { 22.dp.toPx() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RepeatButton(up = true, "$label artır") { haptics.tick(); shift(step) }
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .width(96.dp)
                .height(64.dp)
                .testTag(tag)
                .semantics { contentDescription = "$label $value" }
                .pointerInput(step) {
                    var acc = 0f
                    detectVerticalDragGestures(onDragEnd = { acc = 0f }) { change, dy ->
                        change.consume()
                        acc += dy
                        while (abs(acc) >= stepPx) {
                            val up = acc < 0
                            acc += if (up) stepPx else -stepPx
                            haptics.tick()
                            shift(if (up) step else -step)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { RollingDigits("%02d".format(value), value) }
        RepeatButton(up = false, "$label azalt") { haptics.tick(); shift(-step) }
    }
}

/** Basılı tutunca 400 ms sonra hızla tekrarlar. */
@Composable
private fun RepeatButton(up: Boolean, description: String, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, motionSpec(if (pressed) SpringToken.Quick else SpringToken.Snappy, reduceMotion), label = "repeat")
    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .semantics {
                contentDescription = description
                onClick { step(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    coroutineScope {
                        val job = launch {
                            step()
                            delay(400)
                            while (true) {
                                step()
                                delay(70)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                    }
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        SymbolIcon(Sym.chevron_right, contentDescription = null, size = 20.dp, modifier = Modifier.graphicsLayer { rotationZ = if (up) -90f else 90f })
    }
}

/** İki haneli mono sayı; her hane bağımsız yuvarlanır (artışta yukarı). */
@Composable
private fun RollingDigits(text: String, value: Int) {
    val reduceMotion = LocalReduceMotion.current
    val previous = remember { intArrayOf(value) }
    val increasing = value >= previous[0] || (previous[0] >= 50 && value <= 5)
    SideEffect { previous[0] = value }
    val style = WolType.ScreenTitle.copy(fontFamily = com.kdgames.wake_on_lan.ui.theme.JetBrainsMono, fontSize = androidx.compose.ui.unit.TextUnit(44f, androidx.compose.ui.unit.TextUnitType.Sp))
    Row {
        text.forEachIndexed { i, ch ->
            key(i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val dir = if (increasing) 1 else -1
                        if (reduceMotion) fadeIn() togetherWith fadeOut()
                        else (slideInVertically(SpringToken.Snappy.spec(IntOffset.VisibilityThreshold)) { it * dir } + fadeIn(SpringToken.Snappy.spec())) togetherWith
                            (slideOutVertically(SpringToken.Snappy.spec(IntOffset.VisibilityThreshold)) { -it * dir } + fadeOut(SpringToken.Snappy.spec())) using
                            SizeTransform(clip = true)
                    },
                    label = "digit",
                ) { c -> Text(c.toString(), style = style, color = WolColors.TextPrimary) }
            }
        }
    }
}

@Composable
private fun EditorBar(state: EditorUiState, onSave: () -> Unit, onDelete: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val palette = DevicePalette.of(state.device?.colorIndex ?: 0)
    val g1 by animateColorAsState(palette.start, motionSpec(SpringToken.Gentle, reduceMotion), label = "edG1")
    val g2 by animateColorAsState(palette.end, motionSpec(SpringToken.Gentle, reduceMotion), label = "edG2")
    val alpha by animateFloatAsState(if (state.canSave) 1f else 0.38f, motionSpec(SpringToken.Snappy, reduceMotion), label = "edSave")
    val hintColor by animateColorAsState(
        if (state.issue != null) WolColors.Amber else WolColors.TextSecondaryAlt,
        motionSpec(SpringToken.Snappy, reduceMotion),
        label = "edHint",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(Color.White.copy(alpha = 0.08f), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
    ) {
        AnimatedText(state.hint, style = WolType.BodySmall, color = hintColor, maxLines = 2, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp).testTag("editor_hint"))
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
                    .pressable(onClick = { haptics.click(); onSave() }, enabled = state.canSave)
                    .testTag("editor_save"),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (state.isEdit) "Kaydet" else "Zamanlayıcıyı ekle", style = WolType.BodyLarge, color = WolColors.OnGradient, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
