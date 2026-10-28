package com.kdgames.wake_on_lan.ui.theme

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SymbolIconTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Material Symbols glifi fontun doğal satır kutusunda dikey olarak tam ortadadır
     * (ascent 1056, descent 96, em 960). Metin kutusunun merkezi ikon kutusunun merkeziyle
     * çakışmalı; satır yüksekliği kırpılırsa ("+" aşağıda kalma hatası) bu kayar.
     */
    @Test
    fun glifMetniKutudaDikeyOrtali() {
        compose.setContent {
            WolTheme { SymbolIcon(Sym.add, contentDescription = "ekle", size = 30.dp) }
        }
        val box = compose.onNodeWithContentDescription("ekle").getBoundsInRoot()
        val text = compose.onNodeWithText(Sym.add, useUnmergedTree = true).getBoundsInRoot()
        val boxCenter = (box.top + box.bottom) / 2
        val textCenter = (text.top + text.bottom) / 2
        assertEquals(boxCenter.value, textCenter.value, 0.5f)
        assertEquals((box.left + box.right).value / 2, (text.left + text.right).value / 2, 0.5f)
        // Satır kutusu kırpılmamalı: yükseklik = (ascent + descent) / em × boyut = 1152/960 × 30.
        // (1x yoğunlukta ascent/descent piksele yuvarlanır; kırpılmış hali 30 olurdu.)
        assertEquals(30f * 1152f / 960f, (text.bottom - text.top).value, 1.5f)
    }
}
