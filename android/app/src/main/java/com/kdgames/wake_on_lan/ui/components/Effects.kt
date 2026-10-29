package com.kdgames.wake_on_lan.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.R
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import androidx.compose.foundation.layout.size

/**
 * Sonsuz döngü (spec 12.5): 0 → 1 doğrusal, [periodMs] periyotla. Hareket azaltılmışsa ya da
 * [running] false ise sabit [rest] (spec 12.1: sonsuz döngüler durur).
 */
@Composable
fun loopProgress(periodMs: Int, running: Boolean = true, rest: Float = 0f, label: String = "loop"): State<Float> {
    val reduceMotion = LocalReduceMotion.current
    if (!running || reduceMotion) return remember(rest) { mutableFloatStateOf(rest) }
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = label)
}

/** Nefes alma: [min] ↔ [max] arasında ileri-geri ([periodMs] tam döngü). */
@Composable
fun breathing(periodMs: Int, min: Float, max: Float, running: Boolean = true, label: String = "breath"): State<Float> {
    val reduceMotion = LocalReduceMotion.current
    val mid = (min + max) / 2
    if (!running || reduceMotion) return remember(mid) { mutableFloatStateOf(mid) }
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        min, max,
        infiniteRepeatable(tween(periodMs / 2, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
        label = label,
    )
}

/**
 * Spec 12.5 açık kart/hero efektleri: kenarda cihaz renginde dönen ışık hüzmesi (conic, 8 sn) ve
 * 7 sn'de bir geçen yumuşak ışık süpürmesi. [strength] 0..1 (açık ↔ kapalı geçişte akar).
 */
@Composable
fun Modifier.openCardEffects(shape: Shape, beamColor: Color, strength: Float): Modifier {
    val running = strength > 0.01f
    val beam = loopProgress(8_000, running, label = "beam")
    val sweep = loopProgress(7_000, running, rest = 1f, label = "sweep")
    return drawWithContent {
        drawContent()
        if (strength <= 0.01f) return@drawWithContent
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        // Dönen hüzme: kenar boyunca, merkez etrafında dönen conic gradient çizgisi.
        val brush = Brush.sweepGradient(
            0f to Color.Transparent,
            0.12f to beamColor.copy(alpha = 0.85f * strength),
            0.24f to Color.Transparent,
            1f to Color.Transparent,
            center = center,
        )
        drawPath(path, brush = RotatedSweep(brush, beam.value * 360f, center), style = Stroke(width = 1.5.dp.toPx()))
        // Işık süpürmesi: periyodun ilk %22'sinde soldan sağa geçen çapraz parlama.
        val t = sweep.value / 0.22f
        if (t in 0f..1f) {
            val x = -size.width * 0.6f + size.width * 2.2f * t
            clipPath(path) {
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.10f * strength),
                        1f to Color.Transparent,
                        start = Offset(x, 0f),
                        end = Offset(x + size.width * 0.5f, size.height),
                    ),
                )
            }
        }
    }
}

/** Sweep gradient'i merkez etrafında [angle] derece döndüren fırça. */
@Suppress("FunctionName")
private fun RotatedSweep(base: Brush, angle: Float, center: Offset): Brush {
    // Brush.sweepGradient başlangıç açısını desteklemediği için renk duraklarını kaydırıyoruz.
    return object : androidx.compose.ui.graphics.ShaderBrush() {
        override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader {
            val shader = (base as androidx.compose.ui.graphics.ShaderBrush).createShader(size)
            val m = android.graphics.Matrix().apply { setRotate(angle, center.x, center.y) }
            shader.setLocalMatrix(m)
            return shader
        }
    }
}

/** Windows 11 güç ikonu (K17, Segoe Fluent Icons glifi). */
@Composable
fun WinPowerIcon(action: PowerAction, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, contentDescription: String? = null) {
    val res = when (action) {
        PowerAction.LOCK -> R.drawable.win_lock
        PowerAction.SLEEP -> R.drawable.win_sleep
        PowerAction.HIBERNATE -> R.drawable.win_hibernate
        PowerAction.RESTART -> R.drawable.win_restart
        PowerAction.SHUTDOWN, PowerAction.WAKE -> R.drawable.win_shutdown
    }
    Image(
        painter = painterResource(res),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size).then(
            if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
        ),
    )
}
