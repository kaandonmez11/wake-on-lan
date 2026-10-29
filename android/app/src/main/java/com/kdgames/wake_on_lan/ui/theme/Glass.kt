package com.kdgames.wake_on_lan.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Spec 11.2 cam yüzey varyantları. */
enum class GlassVariant(val topAlpha: Float, val bottomAlpha: Float, val blur: Dp) {
    /** Normal kartlar. */
    Normal(0.18f, 0.05f, 24.dp),

    /** Hero kart: daha parlak + sağ üstten cihaz rengi radial. */
    Strong(0.22f, 0.06f, 24.dp),

    /** Yüzen nav. */
    Dock(0.20f, 0.06f, 30.dp),
}

object GlassTokens {
    const val ANGLE_DEG = 145f
    val Tint = Color(0xFF0C0C1E).copy(alpha = 0.40f)
    const val SATURATION = 1.6f
    val BorderColor = Color.White.copy(alpha = 0.14f)
    val HighlightColor = Color.White.copy(alpha = 0.35f)
    val ShadowColor = Color.Black.copy(alpha = 0.28f)
    // Spec 12; Kaan'ın cihaz testinde gölge "aşağı kaymış" göründü → 6.
    val ShadowOffsetY = 6.dp
    val ShadowRadius = 32.dp
    const val ACCENT_ALPHA = 0.30f
}

/**
 * CSS `linear-gradient(<angle>deg, …)` eşdeğeri: 0° yukarı, 90° sağa, saat yönünde.
 * Gradient çizgisi, köşeleri kapsayacak uzunlukta seçilir (CSS kuralı).
 */
fun cssLinearGradient(angleDeg: Float, size: Size, vararg stops: Pair<Float, Color>): Brush {
    val (start, end) = cssGradientLine(angleDeg, size)
    return Brush.linearGradient(colorStops = stops, start = start, end = end)
}

/** CSS gradient çizgisinin başlangıç ve bitiş noktaları. */
fun cssGradientLine(angleDeg: Float, size: Size): Pair<Offset, Offset> {
    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val half = abs(size.width / 2 * dx) + abs(size.height / 2 * dy)
    val center = Offset(size.width / 2, size.height / 2)
    return (center - Offset(dx * half, dy * half)) to (center + Offset(dx * half, dy * half))
}
