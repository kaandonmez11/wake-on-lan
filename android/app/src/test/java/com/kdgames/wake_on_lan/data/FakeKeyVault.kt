package com.kdgames.wake_on_lan.data

import com.kdgames.wake_on_lan.data.crypto.KeyVault

/** Bellek içi KeyVault; AndroidKeyStore JVM'de yok (gerçek uygulama cihazda WoL Debug › Veri ile denenir). */
class FakeKeyVault : KeyVault {
    private val keys = mutableMapOf<String, ByteArray>()
    private var counter = 0

    override fun newAlias(): String = "agent-test-${++counter}"

    override fun store(alias: String, key: ByteArray) {
        keys[alias] = key.copyOf()
    }

    override fun load(alias: String): ByteArray? = keys[alias]?.copyOf()

    override fun delete(alias: String) {
        keys.remove(alias)
    }

    val aliases: Set<String> get() = keys.keys
}
