package com.kdgames.wake_on_lan.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Spec 11.1 / 11.3 renkleri. */
object WolColors {
    val Background = Color(0xFF0C0D10)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFD5D8F0)
    val TextSecondaryAlt = Color(0xFFC9CCE0)
    val TextOnCard = Color(0xFFE8EAF8)
    val TextTertiary = Color(0xFFAEB2CC)

    val Amber = Color(0xFFFFD97A)
    val Danger = Color(0xFFFF8F85)
    val DangerLight = Color(0xFFFFD0CB)
    val Success = Color(0xFF7CFFB2)

    /** Gradient üstü koyu metin; açık kart karışımlarının da hedefi. */
    val OnGradient = Color(0xFF0B0B1A)
}

/** Cihaz paleti (spec 11.3), `colorIndex` sırasıyla. 135° gradient. */
enum class DevicePalette(val title: String, val start: Color, val end: Color) {
    Ice("Buz", Color(0xFF5EE7FF), Color(0xFFB07CFF)),
    Mint("Nane", Color(0xFF7CFFB2), Color(0xFF2DD4BF)),
    Sunset("Gün batımı", Color(0xFFFFB36B), Color(0xFFFF5FA2)),
    Sun("Güneş", Color(0xFFFDE047), Color(0xFFFB923C)),
    Pink("Pembe", Color(0xFFFF8AD8), Color(0xFF8B5CF6)),
    Ocean("Okyanus", Color(0xFF60A5FA), Color(0xFF22D3EE)),
    Coral("Mercan", Color(0xFFFF8F85), Color(0xFFFFB36B)),
    Silver("Gümüş", Color(0xFFE5E7EB), Color(0xFF9CA3AF)),

    // S07: Kaan'ın isteğiyle 8 ek renk (sıra korunur: eski colorIndex'ler değişmez).
    Lavender("Lavanta", Color(0xFFC4B5FD), Color(0xFF818CF8)),
    Lime("Limon", Color(0xFFD9F99D), Color(0xFF4ADE80)),
    Ruby("Yakut", Color(0xFFFDA4AF), Color(0xFFE11D48)),
    Sky("Gök", Color(0xFF7DD3FC), Color(0xFF6366F1)),
    Forest("Orman", Color(0xFF6EE7B7), Color(0xFF059669)),
    Rose("Gül", Color(0xFFFBCFE8), Color(0xFFEC4899)),
    Copper("Bakır", Color(0xFFFDBA74), Color(0xFFC2410C)),
    Night("Gece", Color(0xFFA5B4FC), Color(0xFF4338CA));

    /** Açık cihaz kartı 145° gradient: mix(c1, #0B0B1A, %42) → mix(c2, #0B0B1A, %50). */
    val openCardStart: Color get() = lerp(start, WolColors.OnGradient, 0.42f)
    val openCardEnd: Color get() = lerp(end, WolColors.OnGradient, 0.50f)

    companion object {
        fun of(index: Int): DevicePalette = entries[index.mod(entries.size)]
    }
}
