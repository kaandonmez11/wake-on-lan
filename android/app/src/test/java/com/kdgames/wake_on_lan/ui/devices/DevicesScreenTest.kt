package com.kdgames.wake_on_lan.ui.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.db.WakeAttempt
import com.kdgames.wake_on_lan.data.repo.WakeHistory
import com.kdgames.wake_on_lan.domain.agent.AgentIssue
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.WakeStep
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun device(id: Long, name: String, ip: String, agent: Boolean = true) = Device(
    id = id, name = name, ip = ip, mac = "D8:BB:C1:0A:2F:9$id", iconKey = "pc", colorIndex = id.toInt(),
    agentId = if (agent) "a$id" else null, agentKeyAlias = if (agent) "k$id" else null, hibernateAvailable = true,
)

private val on = DeviceState.On(3, verified = true, agentReachable = true)
private val off = DeviceState.Off(tried = true)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class DevicesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val wakes = mutableListOf<Long>()
    private val powers = mutableListOf<Pair<Long, PowerAction>>()

    private fun show(initial: DevicesUiState): (DevicesUiState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            WolTheme {
                DevicesScreen(
                    state = state,
                    onSelect = { id -> state = state.copy(selectedId = id) },
                    onWake = { wakes += it },
                    onPower = { id, a -> powers += id to a },
                    onAdd = {},
                    onOpenSettings = {},
                )
            }
        }
        return { state = it }
    }

    private fun two(firstState: DeviceState, secondState: DeviceState = off) = DevicesUiState(
        wifi = WifiState(connected = true, ssid = "Ev", signalLevel = 3, linkSpeedMbps = 866, phoneIp = "192.168.1.88", prefixLength = 24),
        devices = listOf(
            DeviceUi(device(1, "Ofis PC", "192.168.1.26"), firstState),
            DeviceUi(device(2, "Oyun PC", "192.168.1.27"), secondState),
        ),
        selectedId = 1,
    )

    @Test
    fun karta_dokununca_hero_o_cihaza_gecer() {
        show(two(on))
        compose.onNode(hasText("192.168.1.26 · D8:BB:C1:0A:2F:91")).assertExists()
        compose.onNode(hasContentDescription("Oyun PC, Kapalı · yeni", substring = true)).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("192.168.1.27 · D8:BB:C1:0A:2F:92")).assertExists()
        compose.onNode(hasText("192.168.1.26 · D8:BB:C1:0A:2F:91")).assertDoesNotExist()
    }

    @Test
    fun kapali_cihazda_orba_dokunmak_uyandirir() {
        show(two(off))
        compose.onNode(hasContentDescription("Uyandır")).performClick()
        compose.waitForIdle()
        assertEquals(listOf(1L), wakes)
    }

    /** K24 (S06 cihaz testi): kapalı PC'de güç satırı görünür ama pasif; basılı tutmak bir şey yapmaz. */
    @Test
    fun kapali_cihazda_guc_satiri_gorunur_ama_pasif() {
        show(two(off))
        val lock = compose.onNode(hasContentDescription("Kilitle · basılı tut"))
        lock.assertExists()
        // Kapat satırda yok: büyük orb basılı tut = Kapat (Kaan, S06).
        compose.onNode(hasContentDescription("Kapat · basılı tut")).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        lock.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_700)
        lock.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        assertTrue(powers.isEmpty())
    }

    /** S06 cihaz testi: MAC'siz cihazda uyandır dokunulabilir görünüyor ama çalışmıyordu. */
    @Test
    fun mac_yokken_orb_pasif_ve_uyandirmaz() {
        val base = two(off)
        val noMac = base.copy(devices = listOf(base.devices[0].copy(device = base.devices[0].device.copy(mac = null)), base.devices[1]))
        show(noMac)
        compose.onNode(hasContentDescription("Uyandırılamaz · MAC bekleniyor")).assertExists().performClick()
        compose.waitForIdle()
        assertTrue(wakes.isEmpty())
        assertTrue(compose.onAllNodes(hasText("MAC bekleniyor")).fetchSemanticsNodes().isNotEmpty())
    }

    /** S06 cihaz testi: basılı tutarken ipucu metni butonları aşağı itiyordu. */
    @Test
    fun basili_tutarken_butonlar_yerinden_oynamaz() {
        show(two(on))
        val lock = compose.onNode(hasContentDescription("Kilitle · basılı tut"))
        val before = lock.getBoundsInRoot()
        compose.mainClock.autoAdvance = false
        lock.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNode(hasText("Kilitleniyor · bırakırsan iptal")).assertExists()
        val during = lock.getBoundsInRoot()
        lock.performTouchInput { up() }
        // Basma ölçeği (0.96) merkez etrafında küçültür; düzen kayması merkezin yer değiştirmesiyle ölçülür.
        assertEquals((before.top + before.bottom).value / 2, (during.top + during.bottom).value / 2, 0.5f)
        assertEquals((before.left + before.right).value / 2, (during.left + during.right).value / 2, 0.5f)
    }

    /** S06 cihaz testi: küçük kartın güç butonunu basılı tutup bırakınca kart seçiliyordu (çerçeve geliyordu). */
    @Test
    fun kart_guc_butonunu_basili_tutmak_karti_secmez() {
        show(two(on, on))
        compose.mainClock.autoAdvance = false
        // Kart tıklanabilir olduğu için butonun anlamı karta birleşir; butonun kendisi birleştirilmemiş ağaçta.
        val button = compose.onNode(hasContentDescription("Oyun PC kapat · basılı tut"), useUnmergedTree = true)
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_700)
        button.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf(2L to PowerAction.SHUTDOWN), powers)
        // Hero hâlâ ilk cihazda: seçim değişmedi.
        compose.onNode(hasText("192.168.1.26 · D8:BB:C1:0A:2F:91")).assertExists()
    }

    @Test
    fun acik_cihazda_orba_dokunmak_bir_sey_yapmaz() {
        show(two(on))
        compose.onNode(hasContentDescription("Güç halkası · Kapat için basılı tut")).performClick()
        compose.waitForIdle()
        assertTrue(wakes.isEmpty())
        assertTrue(powers.isEmpty())
        compose.onNode(hasContentDescription("Kilitle · basılı tut")).assertExists()
    }

    @Test
    fun orb_1_5_sn_basili_tutmak_kapati_tetikler_0_8_sn_tetiklemez() {
        show(two(on))
        compose.mainClock.autoAdvance = false
        val orb = compose.onNode(hasContentDescription("Güç halkası · Kapat için basılı tut"))

        orb.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        orb.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(600)
        assertTrue("0.8 sn'de bırakınca tetiklenmemeli", powers.isEmpty())

        orb.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_700)
        orb.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf(1L to PowerAction.SHUTDOWN), powers)
    }

    @Test
    fun guc_satiri_butonu_basili_tutunca_komut_gider() {
        show(two(on))
        compose.mainClock.autoAdvance = false
        val lock = compose.onNode(hasContentDescription("Kilitle · basılı tut"))
        lock.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_700)
        lock.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(listOf(1L to PowerAction.LOCK), powers)
    }

    @Test
    fun talkback_cift_dokunus_onay_penceresi_acar() {
        show(two(on))
        compose.onNode(hasContentDescription("Güç halkası · Kapat için basılı tut")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasText("Bu komut PC'ye gönderilsin mi?")).assertExists()
        assertTrue(powers.isEmpty())
        compose.onNode(hasText("Kapat") and hasClickActionText()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(1L to PowerAction.SHUTDOWN), powers)
    }

    @Test
    fun agent_cevap_vermiyorsa_guc_satiri_pasif_ve_ipucu() {
        show(two(DeviceState.On(3, verified = true, agentReachable = false)))
        assertTrue(compose.onAllNodes(hasText("Agent cevap vermiyor")).fetchSemanticsNodes().isNotEmpty())
        val lock = compose.onNode(hasContentDescription("Kilitle · basılı tut"))
        lock.assertExists()
        compose.mainClock.autoAdvance = false
        lock.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_700)
        lock.performTouchInput { up() }
        assertTrue(powers.isEmpty())
    }

    @Test
    fun uyanirken_adim_kartlari_gorunur() {
        show(two(DeviceState.Waking(WakeStep.REPLY, 4)))
        compose.onNode(hasText("Paket")).assertExists()
        compose.onNode(hasText("Ping yanıtı")).assertExists()
        compose.onNode(hasText("Port açık")).assertExists()
        compose.onNode(hasText("Uyanıyor · ")).assertExists()
        compose.onNode(hasText("4")).assertExists()
    }

    @Test
    fun bos_durum_ve_erisilebilirlik_etiketleri() {
        val set = show(DevicesUiState(wifi = WifiState(connected = true, signalLevel = 2, phoneIp = "192.168.1.88")))
        compose.onNode(hasText("Henüz cihaz yok")).assertExists()
        compose.onNode(hasContentDescription("Ayarlar")).assertExists()
        compose.onNode(hasContentDescription("Wi-Fi sinyali")).assertExists()
        compose.onNode(hasText("Wi-Fi")).assertExists()
        set(two(on))
        compose.waitForIdle()
        compose.onNode(hasText("Henüz cihaz yok")).assertDoesNotExist()
    }

    private fun hasClickActionText() = androidx.compose.ui.test.hasClickAction()
    @Test
    fun `kart basili tutulunca Duzenle ve Sil menusu`() {
        val edited = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        val state = two(off)
        compose.setContent {
            WolTheme {
                DevicesScreen(
                    state = state, onSelect = {}, onWake = {}, onPower = { _, _ -> }, onAdd = {}, onOpenSettings = {},
                    onEdit = { edited += it }, onDelete = { deleted += it },
                )
            }
        }
        compose.onAllNodes(hasContentDescription("Oyun PC, ", substring = true) and hasClickAction())[0].performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasTestTag("menu_Düzenle")).performClick()
        compose.waitForIdle()
        assertEquals(listOf(2L), edited)
        compose.onAllNodes(hasContentDescription("Oyun PC, ", substring = true) and hasClickAction())[0].performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasTestTag("menu_Sil")).performClick()
        compose.waitForIdle()
        assertEquals(listOf(2L), deleted)
    }
}

class DeviceLabelsTest {
    private fun history(vararg ok: Boolean) = WakeHistory(ok.mapIndexed { i, s -> WakeAttempt(i.toLong(), 1, i.toLong(), s, if (s) 5 else null, AttemptSource.MANUAL) })

    @org.junit.Test
    fun kart_metinleri_K9_dahil() {
        assertEquals("Açık · 3 ms", DeviceLabels.card(on, history()))
        assertEquals("Kapalı · 8/9", DeviceLabels.card(off, history(true, true, true, true, false, true, true, true, true)))
        assertEquals("Kapalı · yeni", DeviceLabels.card(DeviceState.Off(false), history()))
        assertEquals("Uyuyor", DeviceLabels.card(DeviceState.Off(true, lastAction = PowerAction.SLEEP), history(true)))
        assertEquals("Hazırda", DeviceLabels.card(DeviceState.Off(true, lastAction = PowerAction.HIBERNATE), history(true)))
        assertEquals("Uyanıyor…", DeviceLabels.card(DeviceState.Waking(WakeStep.PACKET, 1), history()))
        assertEquals("Kapatılıyor…", DeviceLabels.card(DeviceState.PowerTransition(PowerAction.SHUTDOWN, 1), history()))
        assertEquals("Uyanıyor · 7 sn", DeviceLabels.chip(DeviceState.Waking(WakeStep.REPLY, 7)))
    }

    @org.junit.Test
    fun agent_cipi_ve_guc_kullanilabilirligi() {
        val d = device(1, "Ofis PC", "192.168.1.26")
        assertEquals("Agent", DeviceLabels.agent(DeviceUi(d, on)))
        assertEquals("Agent cevap vermiyor", DeviceLabels.agent(DeviceUi(d, DeviceState.On(3, true, false))))
        assertEquals("Agent yok", DeviceLabels.agent(DeviceUi(device(1, "x", "1.1.1.1", agent = false), on)))
        assertEquals("Başka telefona eşli", DeviceLabels.agent(DeviceUi(d, on, issue = AgentIssue.PAIRED_ELSEWHERE)))

        assertEquals(PowerAvailability.Disabled(null), powerAvailability(DeviceUi(d, off), PowerAction.LOCK))
        assertEquals(PowerAvailability.Available, powerAvailability(DeviceUi(d, on), PowerAction.LOCK))
        assertTrue(powerAvailability(DeviceUi(d.copy(hibernateAvailable = false), on), PowerAction.HIBERNATE) is PowerAvailability.Disabled)
        assertTrue(powerAvailability(DeviceUi(device(1, "x", "1.1.1.1", agent = false), on), PowerAction.SHUTDOWN) is PowerAvailability.Disabled)
    }

    @org.junit.Test
    fun arka_plan_tonu() {
        val d = device(1, "Ofis PC", "192.168.1.26")
        assertEquals(com.kdgames.wake_on_lan.ui.theme.WolColors.Amber, backgroundTint(DeviceUi(d, DeviceState.Waking(WakeStep.PACKET, 0))))
        assertEquals(com.kdgames.wake_on_lan.ui.theme.WolColors.Danger, backgroundTint(DeviceUi(d, DeviceState.PowerTransition(PowerAction.SHUTDOWN, 0))))
        assertEquals(DeviceUi(d, on).palette.start, backgroundTint(DeviceUi(d, on)))
    }
}
