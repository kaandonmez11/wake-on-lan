package com.kdgames.wake_on_lan.ui.background

import android.graphics.RuntimeShader
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.WolColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tek geçişli AGSL mesh gradient + çok hafif film grain (spec 12.4).
 * Odaklar Gaussian ışık lekeleri olarak eklenir; grain banding'i kırar.
 */
private const val SHADER_SRC = """
uniform float2 resolution;
uniform float time;
uniform float3 base;
uniform float4 f0; uniform float4 f1; uniform float4 f2; uniform float4 f3;
uniform float3 c0; uniform float3 c1; uniform float3 c2; uniform float3 c3;

float glow(float2 p, float4 f) {
    float d = distance(p, f.xy) / max(f.z, 1.0);
    return exp(-d * d * 1.15) * f.w;
}

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

half4 main(float2 p) {
    float3 col = base;
    col += c0 * glow(p, f0);
    col += c1 * glow(p, f1);
    col += c2 * glow(p, f2);
    col += c3 * glow(p, f3);
    float g = (hash(p + fract(time * 0.37) * 97.0) - 0.5) * (2.0 / 255.0);
    return half4(half3(col + g), 1.0);
}
"""

private const val TAG = "LivingBackground"

/** Sürekli modda odakların salınım periyotları (sn) ve faz farkları (spec 12.4: 14–19 sn). */
private val DRIFT_PERIODS = floatArrayOf(14f, 16.5f, 17.5f, 19f)
private val DRIFT_PHASES = floatArrayOf(0f, 1.7f, 3.1f, 4.6f)
private const val DRIFT_AMPLITUDE_DP = 45f
private const val SCALE_MIN = 0.92f
private const val SCALE_MAX = 1.12f

/**
 * Canlı arka plan.
 *
 * @param scene Sahne değişince her odak `gentle` spring ile yeni konum/renge akar.
 * @param continuous true: tam ekranlar — odaklar sürekli salınır. false: sekmeli ekranlar — sabit.
 * @param tint Bir odağı geçici olarak bu renge kaydırır (seçili cihaz, uyanma amber'i vb.; S06).
 */
@Composable
fun LivingBackground(
    scene: Scene,
    modifier: Modifier = Modifier,
    continuous: Boolean = false,
    tint: Color = Color.Unspecified,
) {
    val reduceMotion = LocalReduceMotion.current
    val foci = scene.foci.mapIndexed { index, focus ->
        val color = if (index == 1 && tint != Color.Unspecified) tint else focus.color
        animateFocus(focus, color, index)
    }

    var time by remember { mutableFloatStateOf(0f) }
    val animate = continuous && !reduceMotion
    // Salınım genliği 0↔1 arasında gentle spring ile açılıp kapanır: tam ekrana geçişte
    // hareket sıfırdan büyür, çıkışta odaklar yerlerine akarak döner (sıçrama yok).
    val drift by animateFloatAsState(
        targetValue = if (animate) 1f else 0f,
        animationSpec = SpringToken.Gentle.spec(),
        label = "drift",
    )
    val driftActive = animate || drift > 0.001f
    LaunchedEffect(driftActive) {
        if (!driftActive) return@LaunchedEffect
        val start = withFrameNanos { it } - (time * 1e9f).toLong()
        while (true) {
            withFrameNanos { now -> time = (now - start) / 1e9f }
        }
    }

    // Shader bir kez derlenir. drawWithCache içinde yaratınca her yeniden kompozisyonda (yeni lambda →
    // önbellek geçersiz) baştan derleniyordu (S06 bellek/FPS taraması).
    val shader = remember {
        runCatching { RuntimeShader(SHADER_SRC) }
            .onFailure { Log.w(TAG, "AGSL shader kullanılamıyor, gradient yedeği çiziliyor", it) }
            .getOrNull()
    }
    val brush = remember(shader) { shader?.let(::ShaderBrush) }
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                run {
                    val positions = foci.mapIndexed { i, f -> f.resolve(this, time, drift, i) }
                    if (shader != null && brush != null) {
                        shader.setFloatUniform("resolution", size.width, size.height)
                        shader.setFloatUniform("time", time)
                        shader.setFloatUniform("base", WolColors.Background.red, WolColors.Background.green, WolColors.Background.blue)
                        positions.forEachIndexed { i, p ->
                            shader.setFloatUniform("f$i", p.center.x, p.center.y, p.radius, p.opacity)
                            shader.setFloatUniform("c$i", p.color.red, p.color.green, p.color.blue)
                        }
                        drawRect(brush)
                    } else {
                        // RuntimeShader yoksa (ör. JVM testleri) radial gradient yedeği.
                        drawRect(WolColors.Background)
                        positions.forEach { p ->
                            drawRect(
                                Brush.radialGradient(
                                    listOf(p.color.copy(alpha = p.opacity), Color.Transparent),
                                    center = p.center,
                                    radius = p.radius * 1.6f,
                                ),
                            )
                        }
                    }
                }
            },
    )
}

private class AnimatedFocus(
    val focus: Focus,
    val x: State<Dp>,
    val y: State<Dp>,
    val radius: State<Dp>,
    val color: State<Color>,
    val opacity: State<Float>,
    /** 0 = sol kenara göre, 1 = sağ kenara göre konum; geçişte bağlantı noktası da akar. */
    val anchor: State<Float>,
)

private class ResolvedFocus(val center: Offset, val radius: Float, val color: Color, val opacity: Float)

@Composable
private fun animateFocus(focus: Focus, color: Color, index: Int): AnimatedFocus {
    val reduceMotion = LocalReduceMotion.current
    // "Animasyonları kaldır" modunda sahne geçişi kısalır (quick); sıçrama olmaz.
    val token = if (reduceMotion) SpringToken.Quick else SpringToken.Gentle
    return AnimatedFocus(
        focus = focus,
        x = animateDpAsState(focus.x, token.spec(), label = "focusX$index"),
        y = animateDpAsState(focus.y, token.spec(), label = "focusY$index"),
        radius = animateDpAsState(focus.radius, token.spec(), label = "focusR$index"),
        color = animateColorAsState(color, token.spec(), label = "focusC$index"),
        opacity = animateFloatAsState(focus.opacity, token.spec(), label = "focusO$index"),
        anchor = animateFloatAsState(if (focus.fromRight) 1f else 0f, token.spec(), label = "focusA$index"),
    )
}

private fun AnimatedFocus.resolve(scope: DrawScope, time: Float, drift: Float, index: Int): ResolvedFocus = with(scope) {
    val r = radius.value.toPx()
    val inset = x.value.toPx()
    val fromLeft = inset + r
    val fromRight = size.width - inset - r
    val cx = fromLeft + (fromRight - fromLeft) * anchor.value
    val cy = y.value.toPx() + r
    var center = Offset(cx, cy)
    var scale = 1f
    if (drift > 0f) {
        val w = (2 * PI / DRIFT_PERIODS[index]).toFloat()
        val phase = DRIFT_PHASES[index]
        val amp = DRIFT_AMPLITUDE_DP * density * drift
        center += Offset(amp * sin(w * time + phase), amp * sin(w * 0.8f * time + phase * 1.3f))
        val s = (sin(w * 1.1f * time + phase * 0.7f) + 1f) / 2f
        val target = SCALE_MIN + (SCALE_MAX - SCALE_MIN) * s
        scale = 1f + (target - 1f) * drift
    }
    ResolvedFocus(center, r * scale, color.value, opacity.value)
}
