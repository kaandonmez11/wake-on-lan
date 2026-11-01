package com.kdgames.wake_on_lan.net

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ağ imzası → SSID (S09). Android SSID'yi konum izniyle ve çoğunlukla yalnız ön planda verir; okunabildiği her
 * an imza ile birlikte saklanır. SSID gizli geldiğinde (arka plan, yeniden bağlanma) ad buradan bulunur:
 * Wi-Fi pill'i ve "Eve gelince" bunu kullanır. Son 8 ağ.
 */
@Singleton
class KnownNetworks @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("known_networks", Context.MODE_PRIVATE)
    @Volatile private var cache: Map<String, String>? = null

    /** İmza → SSID. */
    fun all(): Map<String, String> = cache ?: prefs.getStringSet(KEY, emptySet()).orEmpty()
        .mapNotNull { e -> e.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap().also { cache = it }

    fun ssidFor(fingerprint: String?): String? = fingerprint?.let { all()[it] }

    @Synchronized
    fun learn(fingerprint: String, ssid: String) {
        val current = all()
        if (current[fingerprint] == ssid) return
        val next = (current - fingerprint + (fingerprint to ssid)).entries.toList().takeLast(MAX)
        prefs.edit().putStringSet(KEY, next.map { "${it.key}\t${it.value}" }.toSet()).apply()
        cache = next.associate { it.key to it.value }
    }

    private companion object {
        const val KEY = "byFingerprint"
        const val MAX = 8
    }
}
