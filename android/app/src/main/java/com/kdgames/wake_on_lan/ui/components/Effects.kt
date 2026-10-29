package com.kdgames.wake_on_lan.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
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
import com.kdgames.wake_on_lan.ui.theme.LocalAnimationsActive
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import androidx.compose.foundation.layout.size

/**
 * Sonsuz döngü (spec 12.5): 0 → 1 doğrusal, [periodMs] periyotla. Hareket azaltılmışsa ya da
 * [running] false ise sabit [rest] (spec 12.1: sonsuz döngüler durur).
 */
@Composable
fun loopProgress(periodMs: Int, running: Boolean = true, rest: Float = 0f, label: String = "loop"): State<Float> {
    val reduceMotion = LocalReduceMotion.current
    if (!running || reduceMotion || !LocalAnimationsActive.current) return remember(rest) { mutableFloatStateOf(rest) }
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = label)
}

/** Nefes alma: [min] ↔ [max] arasında ileri-geri ([periodMs] tam döngü). */
@Composable
fun breathing(periodMs: Int, min: Float, max: Float, running: Boolean = true, label: String = "breath"): State<Float> {
    val reduceMotion = LocalReduceMotion.current
    val mid = (min + max) / 2
    if (!running || reduceMotion || !LocalAnimationsActive.current) return remember(mid) { mutableFloatStateOf(mid) }
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
 *
 * Bellek: şekil yolu ve shader'lar boyut başına bir kez yaratılır; her karede yalnız dönüş/kaydırma
 * matrisi güncellenir (S06: kare başı yeni shader yerel belleği şişiriyordu).
 */
@Composable
fun OpenCardOverlay(shape: Shape, beamColor: Color, strength: Float, modifier: Modifier = Modifier, lightSweep: Boolean = true) {
    val running = strength > 0.01f
    val beam = loopProgress(8_000, running, label = "beam")
    val sweep = loopProgress(7_000, running && lightSweep, rest = 1f, label = "sweep")
    val current = rememberUpdatedState(strength)
    // Kendi grafik katmanı: her karede yalnız bu ince katman yeniden kaydedilir, kartın içeriği değil.
    Box(modifier.graphicsLayer().drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        val beamArgb = beamColor.copy(alpha = 0.85f).toArgb()
        val sweepShader = android.graphics.SweepGradient(size.width / 2, size.height / 2, intArrayOf(0, beamArgb, 0, 0), floatArrayOf(0f, 0.12f, 0.24f, 1f))
        val sweepMatrix = android.graphics.Matrix()
        val sweepBrush = ShaderBrush(sweepShader)
        // Bant yönü sabit (hafif çapraz), genişliği kartın %35'i; tüm köşelerin dışından başlayıp dışında biter.
        val dirX = 0.894f
        val dirY = 0.447f
        val band = size.width * 0.35f
        val travelEnd = size.width * dirX + size.height * dirY
        val lightShader = android.graphics.LinearGradient(
            0f, 0f, dirX * band, dirY * band,
            intArrayOf(0, Color.White.copy(alpha = 0.10f).toArgb(), 0), floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        val lightMatrix = android.graphics.Matrix()
        val lightBrush = ShaderBrush(lightShader)
        val stroke = Stroke(width = 1.5.dp.toPx())
        onDrawBehind {
            val s = current.value
            if (s <= 0.01f) return@onDrawBehind
            sweepMatrix.setRotate(beam.value * 360f, center.x, center.y)
            sweepShader.setLocalMatrix(sweepMatrix)
            drawPath(path, sweepBrush, alpha = s, style = stroke)
            // Işık süpürmesi: periyodun ilk %22'sinde soldan sağa geçen çapraz parlama.
            val t = sweep.value / 0.22f
            if (t in 0f..1f) {
                val offset = -band + (travelEnd + band) * t
                lightMatrix.setTranslate(dirX * offset, dirY * offset)
                lightShader.setLocalMatrix(lightMatrix)
                // Giriş/çıkış yumuşak: zarf sin(πt).
                val envelope = kotlin.math.sin(Math.PI.toFloat() * t)
                clipPath(path) { drawRect(lightBrush, alpha = s * envelope) }
            }
        }
    })
}

/**
 * Spec 12.5 nefes alan parlama (gölge opaklığı ±%15, 3 sn). Bulanık gölge bitmap'i yerine kartın dışına
 * çizilen, dışa doğru sönen ince halkalar: hiç bitmap ve kare başı nesne üretmez (S06: animasyonlu
 * `dropShadow` her karede yeni gölge bitmap'i üretip uygulamayı ~770 MB'a çıkarıyordu).
 */
@Composable
fun GlowLayer(corner: Dp, color: Color, maxAlpha: Float, strength: () -> Float, breath: State<Float>, modifier: Modifier = Modifier) {
    // Kartın arkasında, kendi katmanında: nefes her karede yalnız bu katmanı yeniden çizer.
    Box(modifier.graphicsLayer().breathingGlow(corner, color, maxAlpha, strength, breath))
}

private fun Modifier.breathingGlow(corner: Dp, color: Color, maxAlpha: Float, strength: () -> Float, breath: State<Float>): Modifier =
    drawWithCache {
        // Geometri boyut başına bir kez; her karede yalnız saydamlık okunur (nesne üretimi yok).
        val c = corner.toPx()
        val spread = 22.dp.toPx()
        val dy = 0f // ortalı: aşağı kaydırılmış parlama "oturmamış" görünüyordu (S06 cihaz testi)
        val ring = spread / GLOW_STEPS
        val stroke = Stroke(ring)
        val offsets = Array(GLOW_STEPS) { i -> val g = ring * (i + 0.5f); Offset(-g, -g + dy) }
        val sizes = Array(GLOW_STEPS) { i -> val g = ring * (i + 0.5f); androidx.compose.ui.geometry.Size(size.width + 2 * g, size.height + 2 * g) }
        val radii = Array(GLOW_STEPS) { i -> androidx.compose.ui.geometry.CornerRadius(c + ring * (i + 0.5f)) }
        onDrawBehind {
            val a = maxAlpha * strength() * breath.value
            if (a <= 0.005f) return@onDrawBehind
            for (i in 0 until GLOW_STEPS) {
                drawRoundRect(
                    color = color,
                    topLeft = offsets[i],
                    size = sizes[i],
                    cornerRadius = radii[i],
                    alpha = (a * (1f - i / GLOW_STEPS.toFloat()) * 0.55f).coerceIn(0f, 1f),
                    style = stroke,
                )
            }
        }
    }

private const val GLOW_STEPS = 7

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
