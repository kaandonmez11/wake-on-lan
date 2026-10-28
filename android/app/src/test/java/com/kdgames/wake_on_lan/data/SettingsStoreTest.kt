package com.kdgames.wake_on_lan.data

import com.kdgames.wake_on_lan.data.prefs.Settings
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.data.repo.RetryError
import com.kdgames.wake_on_lan.domain.model.TileMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SettingsStoreTest {
    private lateinit var store: SettingsStore

    @BeforeEach
    fun setUp() {
        store = SettingsStore(InMemoryPreferences())
    }

    @Test
    fun `varsayilanlar spec 6`() = runBlocking {
        val s = store.current()
        assertEquals(2, s.retryIntervalMin)
        assertEquals(10, s.giveUpMin)
        assertEquals(true, s.hapticsEnabled)
        assertEquals(listOf<Long?>(null, null, null), s.tileSlots)
        assertEquals(TileMode.A, s.tileMode)
        assertNull(s.selectedDeviceId)
    }

    @Test
    fun `retry araliklari ve giveUp buyuk esit interval`() = runBlocking {
        assertEquals(RetryError.INTERVAL_OUT_OF_RANGE, store.setRetryPolicy(31, 60))
        assertEquals(RetryError.INTERVAL_OUT_OF_RANGE, store.setRetryPolicy(0, 10))
        assertEquals(RetryError.GIVE_UP_OUT_OF_RANGE, store.setRetryPolicy(1, 121))
        assertEquals(RetryError.GIVE_UP_OUT_OF_RANGE, store.setRetryPolicy(1, 1))
        assertEquals(RetryError.GIVE_UP_LESS_THAN_INTERVAL, store.setRetryPolicy(10, 5))
        // Geçersiz denemeler hiçbir şey yazmadı.
        assertEquals(2 to 10, store.current().let { it.retryIntervalMin to it.giveUpMin })

        assertNull(store.setRetryPolicy(30, 30))
        assertNull(store.setRetryPolicy(1, 2))
        assertNull(store.setRetryPolicy(5, 120))
        assertEquals(5 to 120, store.current().let { it.retryIntervalMin to it.giveUpMin })
    }

    @Test
    fun `phoneId bir kez uretilir ve kalici`() = runBlocking {
        var n = 0
        store.newPhoneId = { "phone-${++n}" }
        val first = store.ensurePhoneId()
        assertEquals("phone-1", first)
        assertEquals("phone-1", store.ensurePhoneId())
        assertEquals("phone-1", store.current().phoneId)
        assertEquals(1, n)
    }

    @Test
    fun `tile slotlari, mod, secili cihaz, titresim`() = runBlocking {
        store.setTileSlot(0, 7)
        store.setTileSlot(2, 9)
        store.setTileMode(TileMode.C)
        store.setSelectedDevice(7)
        store.setHapticsEnabled(false)
        store.setTileSlot(2, null)
        val s = store.current()
        assertEquals(listOf<Long?>(7, null, null), s.tileSlots)
        assertEquals(TileMode.C, s.tileMode)
        assertEquals(7L, s.selectedDeviceId)
        assertEquals(false, s.hapticsEnabled)
        assertEquals(Settings.TILE_SLOT_COUNT, s.tileSlots.size)
    }
}
