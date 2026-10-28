package com.kdgames.wake_on_lan.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent oturum anahtarlarının (K) saklanması (spec 8.2). Anahtar Keystore'daki AES-GCM
 * anahtarıyla sarmalanır; Auto Backup'tan hariç tutulur.
 */
interface KeyVault {
    /** Yeni bir takma ad üretir (Device.agentKeyAlias). */
    fun newAlias(): String

    fun store(alias: String, key: ByteArray)

    /** Yoksa ya da açılamıyorsa null. */
    fun load(alias: String): ByteArray?

    fun delete(alias: String)
}

/**
 * Tek bir Keystore AES-256-GCM ana anahtarı; her K ayrı dosyada `IV(12) ‖ şifreli metin+etiket`.
 * Dosyalar `noBackupFilesDir/vault/` altında (yedeklemeye girmez; allowBackup da kapalı — K18).
 */
@Singleton
class AndroidKeyVault @Inject constructor(
    @ApplicationContext context: Context,
) : KeyVault {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }

    override fun newAlias(): String = "agent-" + UUID.randomUUID()

    override fun store(alias: String, key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val sealed = cipher.iv + cipher.doFinal(key)
        file(alias).writeBytes(sealed)
    }

    override fun load(alias: String): ByteArray? {
        val f = file(alias)
        if (!f.exists()) return null
        return runCatching {
            val sealed = f.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }.getOrNull()
    }

    override fun delete(alias: String) {
        file(alias).delete()
    }

    private fun file(alias: String): File {
        require(ALIAS.matches(alias)) { "Geçersiz takma ad: $alias" }
        return File(dir, "$alias.bin")
    }

    @Synchronized
    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_ALIAS = "wol_vault_master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val ALIAS = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}
