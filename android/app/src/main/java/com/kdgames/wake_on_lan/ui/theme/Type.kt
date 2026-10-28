package com.kdgames.wake_on_lan.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kdgames.wake_on_lan.R

// Değişken fontlar: her ağırlık aynı dosyanın wght ekseninden üretilir (spec 4, 11.4).
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(resId: Int, weights: List<Int>) = FontFamily(
    weights.map { w ->
        Font(resId, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

val Onest: FontFamily = variableFamily(R.font.onest_variable, listOf(500, 600, 700, 800))
val JetBrainsMono: FontFamily = variableFamily(R.font.jetbrains_mono_variable, listOf(500, 600, 700))

/** Rakamlar her yerde tabular (spec 11.4). */
private const val TABULAR = "tnum"

private fun onest(size: Int, line: Int, weight: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = Onest,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.sp,
    fontFeatureSettings = TABULAR,
)

private fun mono(size: Int, weight: Int = 500) = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = size.sp,
    lineHeight = 1.3.em,
    fontWeight = FontWeight(weight),
    letterSpacing = (-0.2).sp,
    fontFeatureSettings = TABULAR,
)

/** Spec 11.4 tipografi rolleri. */
object WolType {
    val PageTitle = onest(40, 42, 700, -1.2f)
    val HeroName = onest(28, 30, 700, -0.8f)
    val ScreenTitle = onest(30, 34, 800, -1.0f)
    val SheetTitle = onest(24, 28, 700, -0.6f)
    val CardName = onest(18, 22, 700)
    val BodyLarge = onest(16, 22, 600)
    val Body = onest(15, 20, 500)
    val BodySmall = onest(13, 18, 500)
    val Chip = onest(12, 16, 600)
    val Label = onest(11, 14, 600)

    val Technical = mono(12)
    val TechnicalSmall = mono(11)
    val TechnicalLarge = mono(13)
}
