package com.kdgames.wake_on_lan.ui.background

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bir ışık odağı. Konumlar spec 11.1'deki CSS yerleşimi gibi verilir: [fromRight] false ise
 * `left: x`, true ise `right: x`; `top: y`. Merkez, yarıçap kadar içeride hesaplanır.
 */
data class Focus(
    val x: Dp,
    val y: Dp,
    val fromRight: Boolean,
    val radius: Dp,
    val color: Color,
    val opacity: Float,
)

/** Arka plan sahneleri (spec 12.4). Her sahne tam 4 odak içerir. */
enum class Scene(val foci: List<Focus>) {
    /** Cihazlar: soğuk mavi-mor (spec 11.1 varsayılan tablo). */
    Devices(
        listOf(
            Focus((-120).dp, (-100).dp, false, 180.dp, Color(0xFF5B6B8C), 0.55f),
            Focus((-140).dp, 250.dp, true, 180.dp, Color(0xFF3B4A6B), 0.50f),
            Focus((-100).dp, 540.dp, false, 170.dp, Color(0xFF4A3F6B), 0.45f),
            Focus((-90).dp, 660.dp, true, 150.dp, Color(0xFF2F6B6B), 0.40f),
        ),
    ),

    /** Otomasyon: mor-turkuaz ağırlıklı. */
    Automation(
        listOf(
            Focus((-90).dp, (-120).dp, true, 190.dp, Color(0xFF5A4A8C), 0.55f),
            Focus((-150).dp, 210.dp, false, 180.dp, Color(0xFF2F6B73), 0.50f),
            Focus((-110).dp, 500.dp, true, 175.dp, Color(0xFF3F7A78), 0.45f),
            Focus((-80).dp, 690.dp, false, 160.dp, Color(0xFF4B3A73), 0.42f),
        ),
    ),

    /** Tam ekranlar (Wi-Fi yok, izin yok, başarısız detayı, tile overlay) için nötr başlangıç. */
    FullScreen(
        listOf(
            Focus((-60).dp, (-60).dp, false, 200.dp, Color(0xFF5B6B8C), 0.55f),
            Focus((-120).dp, 200.dp, true, 190.dp, Color(0xFF4A3F6B), 0.50f),
            Focus((-80).dp, 470.dp, false, 180.dp, Color(0xFF3B4A6B), 0.48f),
            Focus((-40).dp, 640.dp, true, 170.dp, Color(0xFF2F6B6B), 0.42f),
        ),
    ),
}
