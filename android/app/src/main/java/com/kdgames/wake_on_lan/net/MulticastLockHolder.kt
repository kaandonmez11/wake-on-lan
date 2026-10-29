package com.kdgames.wake_on_lan.net

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tarama/discovery sırasında broadcast ve mDNS yanıtlarını almak için MulticastLock (spec 7.1).
 * Referans sayımlıdır: iç içe kullanımda kilit son kullanıcı bırakınca serbest kalır.
 */
@Singleton
class MulticastLockHolder @Inject constructor(@ApplicationContext context: Context) {
    private val lock = context.getSystemService(WifiManager::class.java)
        .createMulticastLock("wol-discovery")
        .apply { setReferenceCounted(true) }

    val isHeld: Boolean get() = lock.isHeld

    suspend fun <T> withLock(block: suspend () -> T): T {
        lock.acquire()
        try {
            return block()
        } finally {
            lock.release()
        }
    }
}
