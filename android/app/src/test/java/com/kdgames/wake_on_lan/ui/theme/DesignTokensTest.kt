package com.kdgames.wake_on_lan.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesignTokensTest {

    @Test
    fun `spring stiffness spec 12_1 tablosuyla ayni`() {
        // stiffness = (2π / response)²
        val expected = mapOf(
            SpringToken.Quick to 987f,
            SpringToken.Snappy to 439f,
            SpringToken.Smooth to 158f,
            SpringToken.Bouncy to 158f,
            SpringToken.Gentle to 62f,
        )
        expected.forEach { (token, stiffness) -> assertEquals(stiffness, token.stiffness, 1f, token.name) }
        assertEquals(0.86f, SpringToken.Snappy.damping)
        assertEquals(0.70f, SpringToken.Bouncy.damping)
    }

    @Test
    fun `spring spec token degerlerini tasir`() {
        val spec = SpringToken.Bouncy.spec<Float>()
        assertEquals(SpringToken.Bouncy.damping, spec.dampingRatio)
        assertEquals(SpringToken.Bouncy.stiffness, spec.stiffness)
    }

    @Test
    fun `cihaz paleti 16 renk, ilk 8 sirasi spec ile ayni`() {
        assertEquals(16, DevicePalette.entries.size)
        assertEquals(Color(0xFF5EE7FF), DevicePalette.of(0).start)
        assertEquals(Color(0xFF9CA3AF), DevicePalette.of(7).end)
        assertEquals(DevicePalette.Lavender, DevicePalette.of(8))
        assertEquals(DevicePalette.Ice, DevicePalette.of(16))
    }

    @Test
    fun `acik kart karisimlari`() {
        val p = DevicePalette.Mint
        assertEquals(lerp(p.start, WolColors.OnGradient, 0.42f), p.openCardStart)
        assertEquals(lerp(p.end, WolColors.OnGradient, 0.50f), p.openCardEnd)
    }

    @Test
    fun `css gradient acilari`() {
        val size = Size(200f, 100f)
        // 90°: soldan sağa, dikey ortada.
        val (s90, e90) = cssGradientLine(90f, size)
        assertEquals(0f, s90.x, 0.01f); assertEquals(50f, s90.y, 0.01f)
        assertEquals(200f, e90.x, 0.01f); assertEquals(50f, e90.y, 0.01f)
        // 180°: yukarıdan aşağı.
        val (s180, e180) = cssGradientLine(180f, size)
        assertEquals(100f, s180.x, 0.01f); assertEquals(0f, s180.y, 0.01f)
        assertEquals(100f, e180.x, 0.01f); assertEquals(100f, e180.y, 0.01f)
        // 135° kare: sol üst köşeden sağ alt köşeye.
        val (s135, e135) = cssGradientLine(135f, Size(100f, 100f))
        assertEquals(0f, s135.x, 0.01f); assertEquals(0f, s135.y, 0.01f)
        assertEquals(100f, e135.x, 0.01f); assertEquals(100f, e135.y, 0.01f)
    }
}
