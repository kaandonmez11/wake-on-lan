package com.kdgames.wake_on_lan.ui.nav

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppNavTest {
    @get:Rule
    val compose = createComposeRule()

    private fun tab(title: String) = compose.onNode(isSelectable() and hasText(title))

    @Test
    fun acilistaCihazlarSecili() {
        compose.setContent { WolTheme { AppNav() } }
        tab("Cihazlar").assertIsSelected()
        tab("Otomasyon").assertIsNotSelected()
    }

    @Test
    fun sekmeyeDokununcaSecimVeIcerikDegisir() {
        compose.setContent { WolTheme { AppNav() } }
        tab("Otomasyon").performClick()
        compose.waitForIdle()
        tab("Otomasyon").assertIsSelected()
        tab("Cihazlar").assertIsNotSelected()
        compose.onNode(hasText("Zamanlayıcılar telefondan çalışır, Wi-Fi şart")).assertExists()

        tab("Cihazlar").performClick()
        compose.waitForIdle()
        tab("Cihazlar").assertIsSelected()
        compose.onNode(hasText("Zamanlayıcılar telefondan çalışır, Wi-Fi şart")).assertDoesNotExist()
    }

    @Test
    fun artiButonuCagrilir() {
        var added = 0
        compose.setContent { WolTheme { AppNav(onAddDevice = { added++ }) } }
        compose.onNode(hasContentDescription("Cihaz ekle") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(1, added)
    }
}
