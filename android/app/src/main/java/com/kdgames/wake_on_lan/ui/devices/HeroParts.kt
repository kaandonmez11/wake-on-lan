package com.kdgames.wake_on_lan.ui.devices

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.data.db.WakeAttempt
import com.kdgames.wake_on_lan.data.repo.WakeHistory
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.WakeStep
import com.kdgames.wake_on_lan.domain.status.StateLabels
import com.kdgames.wake_on_lan.ui.components.HoldState
import com.kdgames.wake_on_lan.ui.components.WinPowerIcon
import com.kdgames.wake_on_lan.ui.components.holdToConfirm
import com.kdgames.wake_on_lan.ui.components.loopProgress
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolDimens
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.motionSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Güç satırı butonu (spec 10.2, 12.6): 62 yükseklik, köşe 20, basılı tutunca dolum alttan yukarı.
 * Kapat ve Yeniden kırmızımsı dolar, diğerleri beyaz.
 */
@Composable
fun HoldPowerButton(
    action: PowerAction,
    hold: HoldState,
    enabled: Boolean,
    onCommit: () -> Unit,
    onAccessibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val shape = RoundedCornerShape(WolDimens.CornerPowerButton)
    val danger = action == PowerAction.SHUTDOWN
    val fillColor = if (action == PowerAction.SHUTDOWN || action == PowerAction.RESTART) WolColors.Danger else Color.White
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, motionSpec(SpringToken.Snappy, reduceMotion), label = "powerAlpha")
    val press by animateFloatAsState(if (hold.pressed) 0.96f else 1f, motionSpec(if (hold.pressed) SpringToken.Quick else SpringToken.Snappy, reduceMotion), label = "powerPress")
    val progress = hold.progress.value
    val title = DeviceLabels.actionTitle(action)
    Column(
        modifier
            .height(62.dp)
            .graphicsLayer {
                this.alpha = alpha
                val s = press * hold.commit.value
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(if (danger) WolColors.Danger.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (danger) WolColors.Danger.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.12f), shape)
            .drawBehind {
                if (progress > 0f) {
                    val h = size.height * progress
                    drawRect(fillColor.copy(alpha = 0.32f), topLeft = Offset(0f, size.height - h), size = size.copy(height = h))
                }
            }
            .semantics { contentDescription = "$title · basılı tut" }
            .holdToConfirm(hold, enabled, title, onCommit, onAccessibility),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WinPowerIcon(action, tint = if (danger) WolColors.DangerLight else Color.White, size = 20.dp)
        Spacer(Modifier.height(5.dp))
        Text(title, style = WolType.Label, color = if (danger) WolColors.DangerLight else WolColors.TextSecondary)
    }
}

/** Uyanırken 3 adım kartı (spec 10.2, 12.3): tamamlanan gradient ile dolar + ✓ çizilir; aktif halka döner. */
@Composable
fun WakeSteps(step: WakeStep, palette: DevicePalette, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WakeStep.entries.forEach { s ->
            val status = when {
                s.ordinal < step.ordinal -> StepStatus.Done
                s == step -> StepStatus.Active
                else -> StepStatus.Pending
            }
            StepCard(StateLabels.stepLabel(s), status, palette, Modifier.weight(1f))
        }
    }
}

private enum class StepStatus { Pending, Active, Done }

@Composable
private fun StepCard(title: String, status: StepStatus, palette: DevicePalette, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val fill by animateFloatAsState(if (status == StepStatus.Done) 1f else 0f, motionSpec(SpringToken.Smooth, reduceMotion), label = "stepFill")
    val check by animateFloatAsState(if (status == StepStatus.Done) 1f else 0f, motionSpec(SpringToken.Smooth, reduceMotion), label = "stepCheck")
    val ringAlpha by animateFloatAsState(if (status == StepStatus.Pending) 0.35f else 1f, motionSpec(SpringToken.Snappy, reduceMotion), label = "stepRing")
    val spin by loopProgress(1_000, running = status == StepStatus.Active, label = "stepSpin")
    val textColor by animateColorAsState(if (status == StepStatus.Done) WolColors.OnGradient else WolColors.TextSecondary, motionSpec(SpringToken.Snappy, reduceMotion), label = "stepText")
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .drawBehind {
                if (fill > 0f) drawRect(Brush.horizontalGradient(listOf(palette.start, palette.end)), size = size.copy(width = size.width * fill))
            }
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            if (status == StepStatus.Active) {
                drawCircle(Color.White.copy(alpha = 0.18f), style = Stroke(stroke))
                rotate(spin * 360f) {
                    drawArc(WolColors.Amber, -90f, 100f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            } else if (check < 0.01f) {
                drawCircle(Color.White.copy(alpha = 0.3f * ringAlpha), style = Stroke(stroke))
            }
            if (check > 0f) {
                // ✓ çizgisi path trim ile çizilerek belirir.
                val p = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.76f)
                    lineTo(size.width * 0.84f, size.height * 0.28f)
                }
                val measure = PathMeasure().apply { setPath(p, false) }
                val trimmed = Path()
                measure.getSegment(0f, measure.length * check, trimmed, true)
                drawPath(trimmed, WolColors.OnGradient, style = Stroke(stroke * 1.2f, cap = StrokeCap.Round))
            }
        }
        Text(title, style = WolType.Label, color = textColor, maxLines = 1)
    }
}

/**
 * Son 12 deneme çubukları (spec 10.2, K15): başarı beyaz %85 tam boy, başarısız #FF8F85 yarım boy.
 * İlk görünüşte soldan sağa 25 ms kademeli `bouncy` büyür; yeni deneme kayarak girer.
 */
@Composable
fun HistoryBars(history: WakeHistory, modifier: Modifier = Modifier) {
    val time = remember { SimpleDateFormat("HH:mm", Locale.ROOT) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val listState = rememberLazyListState()
        LazyRow(
            state = listState,
            userScrollEnabled = false,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth().height(30.dp),
            reverseLayout = false,
        ) {
            items(history.attempts, key = { it.id }) { attempt ->
                HistoryBar(attempt, history.attempts.indexOf(attempt), Modifier.animateItem())
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Son 12 deneme · ", style = WolType.Label, color = WolColors.TextTertiary)
            Text(history.label, style = WolType.TechnicalSmall, color = WolColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            history.lastSuccessAt?.let {
                Text("Son uyandırma · ", style = WolType.Label, color = WolColors.TextTertiary)
                Text(time.format(Date(it)), style = WolType.TechnicalSmall, color = WolColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun HistoryBar(attempt: WakeAttempt, index: Int, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val grow = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(25L * index)
        grow.animateTo(1f, motionSpec(SpringToken.Bouncy, reduceMotion))
    }
    Box(modifier.width(14.dp).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .width(14.dp)
                .fillMaxHeight(if (attempt.success) 1f else 0.5f)
                .graphicsLayer {
                    scaleY = grow.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                }
                .clip(RoundedCornerShape(4.dp))
                .background(if (attempt.success) Color.White.copy(alpha = 0.85f) else WolColors.Danger),
        )
    }
}

/** Durum noktası (çip başı). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(color))
}
