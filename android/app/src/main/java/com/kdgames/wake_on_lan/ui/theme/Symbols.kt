package com.kdgames.wake_on_lan.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kdgames.wake_on_lan.R
import kotlin.math.roundToInt

/**
 * Material Symbols Outlined değişken font ile ikon (spec 11.6).
 * FILL ekseni [filled] değişince 0↔1 arasında `snappy` spring ile akar (spec 12.3).
 *
 * @param name [Sym] sabitlerinden biri.
 */
@Composable
fun SymbolIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    filled: Boolean = false,
    weight: Int = 400,
    tint: Color = WolColors.TextPrimary,
) {
    val reduceMotion = LocalReduceMotion.current
    val fill by animateFloatAsState(
        targetValue = if (filled) 1f else 0f,
        animationSpec = motionSpec(SpringToken.Snappy, reduceMotion),
        label = "symbolFill",
    )
    // Font ölçeği ikon boyutunu etkilemez.
    val fontSize = with(LocalDensity.current) { (size.toPx() / fontScale / density).sp }
    val family = SymbolFonts.family(fill, weight, opticalSize = size.value)
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Glif, fontun doğal satır kutusunda (ascent 1056 / descent 96, em 960) tam ortadadır.
        // Satır yüksekliğini kırpmak glifi kaydırır; metin sınırsız ölçülüp kutuda ortalanır.
        Text(
            text = name,
            modifier = Modifier.wrapContentSize(unbounded = true),
            style = TextStyle(
                fontFamily = family,
                fontSize = fontSize,
                color = tint,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
        )
    }
}

/**
 * Her eksen kombinasyonu ayrı bir Typeface ister. FILL animasyonunda her karede yeni font
 * yaratmamak için FILL 0.1'lik adımlara yuvarlanır ve aileler önbellekte tutulur.
 */
@OptIn(ExperimentalTextApi::class)
private object SymbolFonts {
    private const val FILL_STEPS = 10
    private val cache = HashMap<Long, FontFamily>()

    fun family(fill: Float, weight: Int, opticalSize: Float): FontFamily {
        val step = (fill.coerceIn(0f, 1f) * FILL_STEPS).roundToInt()
        val opsz = opticalSize.coerceIn(20f, 48f).roundToInt()
        val wght = weight.coerceIn(100, 700)
        val key = (step.toLong() shl 32) or (opsz.toLong() shl 16) or wght.toLong()
        return synchronized(cache) {
            cache.getOrPut(key) {
                FontFamily(
                    Font(
                        R.font.material_symbols_outlined,
                        variationSettings = FontVariation.Settings(
                            FontVariation.Setting("FILL", step / FILL_STEPS.toFloat()),
                            FontVariation.Setting("wght", wght.toFloat()),
                            FontVariation.Setting("GRAD", 0f),
                            FontVariation.Setting("opsz", opsz.toFloat()),
                        ),
                    ),
                )
            }
        }
    }
}
