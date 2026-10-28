package com.kdgames.wake_on_lan

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric izinleri zorlamaz; eksik izin yalnız gerçek cihazda çökme olarak görünür.
 * Bu yüzden kullanılan her normal izin burada ayrıca kontrol edilir (S01: VIBRATE eksikti).
 */
@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {
    @Test
    fun gerekliIzinlerManifesteVar() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        listOf(android.Manifest.permission.VIBRATE).forEach {
            assertTrue("$it manifestte yok", it in requested)
        }
    }
}
