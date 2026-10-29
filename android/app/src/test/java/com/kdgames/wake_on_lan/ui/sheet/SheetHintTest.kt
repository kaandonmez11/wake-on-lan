package com.kdgames.wake_on_lan.ui.sheet

import com.kdgames.wake_on_lan.net.scan.AgentFound
import com.kdgames.wake_on_lan.net.scan.ScanEntry
import com.kdgames.wake_on_lan.net.scan.ScanSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetHintTest {
    private fun state(paired: Boolean, pairedToYou: Boolean) = SheetUiState(
        form = SheetForm(name = "KAAN-PC", ip = "192.168.1.26", mac = "D8:BB:C1:0A:2F:91"),
        scan = ScanSnapshot(
            listOf(
                ScanEntry(
                    "192.168.1.26", "192.168.1.26", "D8:BB:C1:0A:2F:91", "KAAN-PC", "pc",
                    AgentFound("a", "KAAN-PC", paired, hibernate = true, sleepHibernates = true, pairedToYou = pairedToYou),
                ),
            ),
        ),
        errors = emptySet(),
    )

    @Test
    fun `K25 ipuclari`() {
        assertEquals(SheetUiState.HINT_AGENT, state(paired = false, pairedToYou = false).hint)
        assertEquals(SheetUiState.HINT_KNOWN, state(paired = true, pairedToYou = true).hint)
        assertEquals(SheetUiState.HINT_ELSEWHERE, state(paired = true, pairedToYou = false).hint)
        assertEquals("Agent bu telefonu tanıyor", state(paired = true, pairedToYou = true).agentLabel)
    }
}
