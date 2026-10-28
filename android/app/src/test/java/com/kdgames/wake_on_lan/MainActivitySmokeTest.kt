package com.kdgames.wake_on_lan

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Robolectric (JUnit4/vintage) altyapısının çalıştığını doğrular; S01+ UI testleri bunun üzerine kurulur. */
@RunWith(RobolectricTestRunner::class)
class MainActivitySmokeTest {
    @Test
    fun mainActivityOpens() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            assertFalse(activity.isFinishing)
        }
    }
}
