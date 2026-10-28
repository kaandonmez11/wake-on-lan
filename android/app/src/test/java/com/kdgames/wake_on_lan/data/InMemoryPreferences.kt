package com.kdgames.wake_on_lan.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bellek içi Preferences DataStore. Dosya tabanlı DataStore Windows test makinesinde peş peşe
 * yazımlarda `.tmp` → hedef yeniden adlandırmasında takılıyor (Android'de yok); mantık testleri
 * bununla koşar, kalıcılık cihazda doğrulanır.
 */
class InMemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
