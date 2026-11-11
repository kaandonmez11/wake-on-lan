package com.kdgames.wake_on_lan.ui.background

import android.graphics.RuntimeShader
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Sekmeler arası parmakla geçişte arka planın sürüklemeye bire bir bağlanması (S10, Kaan): [target] sahnesine
 * [progress] (0..1) kadar yaklaşılır; ileri-geri sarılabilir. [snapping] true iken sahne değişimi animasyonsuz
 * oturur (görüntü zaten hedefte). Değerler yalnız çizimde okunur.
 */
@androidx.compose.runtime.Stable
class SceneScrub(
    val target: () -> Scene?,
    val progress: () -> Float,
    val snapping: () -> Boolean,
    val targetTint: () -> Color = { Color.Unspecified },
)

/**
 * Canlı arka plan.
 *
 * @param scene Sahne değişince her odak `gentle` spring ile yeni konum/renge akar.
 * @param continuous true: tam ekranlar — odaklar sürekli salınır. false: sekmeli ekranlar — sabit.
 * @param tint Bir odağı geçici olarak bu renge kaydırır (seçili cihaz, uyanma amber'i vb.; S06).
 * @param scrub Parmakla sekme geçişi (S10).
 */
@Composable
fun LivingBackground(
    scene: Scene,
    modifier: Modifier = Modifier,
    continuous: Boolean = false,
    tint: Color = Color.Unspecified,
    scrub: SceneScrub? = null,
    /** Kutu her yönden bu kadar ekran dışına taşıyor ([overscan]); odak konumları ekrana göre hesaplanır. */
    overscan: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
) {
    val reduceMotion = LocalReduceMotion.current
    val foci = scene.foci.mapIndexed { index, focus ->
        val color = if (index == 1 && tint != Color.Unspecified) tint else focus.color
        animateFocus(FocusValues.of(focus, color), index, snap = { scrub?.snapping?.invoke() == true })
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
                    // Parmakla geçiş: hedef sahnenin odaklarına sürükleme oranında yaklaş.
                    val target = scrub?.target?.invoke()
                    val p = scrub?.progress?.invoke()?.coerceIn(0f, 1f) ?: 0f
                    val tTint = scrub?.targetTint?.invoke() ?: Color.Unspecified
                    val positions = foci.mapIndexed { i, f ->
                        var v = f.current()
                        if (target != null && p > 0f) {
                            val tf = target.foci[i]
                            v = v.lerp(FocusValues.of(tf, if (i == 1 && tTint != Color.Unspecified) tTint else tf.color), p)
                        }
                        v.resolve(this, time, drift, i, overscan.toPx())
                    }
                    if (shader != null && brush != null) {
                        shader.setFloatUniform("resolution", size.width, size.height)
                        shader.setFloatUniform("time", time)
                        shader.setFloatUniform("base", WolColors.Background.red, WolColors.Background.green, WolColors.Background.blue)
                        positions.forEachIndexed { i, pos ->
                            shader.setFloatUniform("f$i", pos.center.x, pos.center.y, pos.radius, pos.opacity)
                            shader.setFloatUniform("c$i", pos.color.red, pos.color.green, pos.color.blue)
                        }
                        drawRect(brush)
                    } else {
                        // RuntimeShader yoksa (ör. JVM testleri) radial gradient yedeği.
                        drawRect(WolColors.Background)
                        positions.forEach { pos ->
                            drawRect(
                                Brush.radialGradient(
                                    listOf(pos.color.copy(alpha = pos.opacity), Color.Transparent),
                                    center = pos.center,
                                    radius = pos.radius * 1.6f,
                                ),
                            )
                        }
                    }
                }
            },
    )
}

/** Bir odağın çizim değerleri (dp cinsinden konum/yarıçap; anchor 0 = sol, 1 = sağ kenara göre). */
private data class FocusValues(val x: Float, val y: Float, val radius: Float, val color: Color, val opacity: Float, val anchor: Float) {
    fun lerp(to: FocusValues, t: Float) = FocusValues(
        x + (to.x - x) * t,
        y + (to.y - y) * t,
        radius + (to.radius - radius) * t,
        androidx.compose.ui.graphics.lerp(color, to.color, t),
        opacity + (to.opacity - opacity) * t,
        anchor + (to.anchor - anchor) * t,
    )

    companion object {
        fun of(f: Focus, color: Color) = FocusValues(f.x.value, f.y.value, f.radius.value, color, f.opacity, if (f.fromRight) 1f else 0f)
    }
}

/**
 * Hedef değişince o an çizilen değerden yeni hedefe tek bir 0→1 yayıyla akar (`gentle`; azaltılmış harekette
 * `quick`). [snap] true ise (parmakla geçiş bitti, görüntü zaten hedefte) animasyonsuz oturur.
 */
private class AnimatedFocus(initial: FocusValues) {
    var from = initial
    var to = initial
    val t = Animatable(1f)

    fun current(): FocusValues = if (t.value >= 1f) to else from.lerp(to, t.value)
}

private class ResolvedFocus(val center: Offset, val radius: Float, val color: Color, val opacity: Float)

@Composable
private fun animateFocus(target: FocusValues, index: Int, snap: () -> Boolean): AnimatedFocus {
    val reduceMotion = LocalReduceMotion.current
    val token = if (reduceMotion) SpringToken.Quick else SpringToken.Gentle
    val anim = remember { AnimatedFocus(target) }
    LaunchedEffect(target) {
        if (anim.to == target) return@LaunchedEffect
        anim.from = anim.current()
        anim.to = target
        anim.t.snapTo(0f)
        if (snap()) anim.t.snapTo(1f) else anim.t.animateTo(1f, token.spec())
    }
    return anim
}

private fun FocusValues.resolve(scope: DrawScope, time: Float, drift: Float, index: Int, overscanPx: Float = 0f): ResolvedFocus = with(scope) {
    val r = radius * density
    val inset = x * density
    val fromLeft = inset + r
    val fromRight = size.width - 2 * overscanPx - inset - r
    val cx = fromLeft + (fromRight - fromLeft) * anchor
    val cy = y * density + r
    var center = Offset(cx + overscanPx, cy + overscanPx)
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
    ResolvedFocus(center, r * scale, color, opacity)
}
