package com.kdgames.wake_on_lan.ui.nav

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isHeading
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

    /** S01 cihaz testi: geçiş sırasında başlık dikeyde kayıyordu (ölçek ekseni ekran ortasıydı). */
    @Test
    fun gecisSirasindaBaslikDikeydeOynamaz() {
        compose.setContent { WolTheme { AppNav() } }
        // Her başlığın oturmuş konumu (Otomasyon'da başlığın üstünde Wi-Fi pill'i var, S08).
        fun top(title: String) = compose.onNode(isHeading() and hasText(title)).getBoundsInRoot().top.value
        val finalTop = mutableMapOf("Cihazlar" to top("Cihazlar"))
        tab("Otomasyon").performClick()
        compose.waitForIdle()
        finalTop["Otomasyon"] = top("Otomasyon")
        tab("Cihazlar").performClick()
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        tab("Otomasyon").performClick()
        repeat(12) {
            compose.mainClock.advanceTimeBy(32)
            compose.onAllNodes(isHeading()).fetchSemanticsNodes().forEach { node ->
                val title = node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
                // Üst kenar eksenli 0.98 → 1 ölçek başlığı en çok %2 oynatabilir; kayma (S01 hatası) bunu aşar.
                val expected = finalTop.getValue(title)
                assertEquals(expected, node.boundsInRoot.top / node.layoutInfo.density.density, maxOf(0.5f, expected * 0.021f))
            }
        }
        compose.mainClock.autoAdvance = true
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
