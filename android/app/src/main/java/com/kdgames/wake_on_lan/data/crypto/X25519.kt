package com.kdgames.wake_on_lan.data.crypto

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * java.security XDH üzerinden X25519, ham 32 baytlık anahtarlarla (K5).
 * Ham anahtar ↔ PKCS#8 / X.509 dönüşümü sabit ASN.1 önekleriyle yapılır (RFC 8410).
 */
object X25519 {
    const val KEY_SIZE = 32
    private const val ALGORITHM = "XDH"

    private val PKCS8_PREFIX = hex("302e020100300506032b656e04220420")
    private val X509_PREFIX = hex("302a300506032b656e032100")
    private val BASE_POINT = ByteArray(KEY_SIZE).also { it[0] = 9 }

    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(random: SecureRandom): KeyPair {
        val privateKey = ByteArray(KEY_SIZE).also(random::nextBytes)
        return KeyPair(privateKey, publicKey(privateKey))
    }

    fun publicKey(privateKey: ByteArray): ByteArray = sharedSecret(privateKey, BASE_POINT)

    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_SIZE) { "Özel anahtar 32 bayt olmalı" }
        require(peerPublicKey.size == KEY_SIZE) { "Açık anahtar 32 bayt olmalı" }
        val factory = KeyFactory.getInstance(ALGORITHM)
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + privateKey))
        val public = factory.generatePublic(X509EncodedKeySpec(X509_PREFIX + peerPublicKey))
        val agreement = KeyAgreement.getInstance(ALGORITHM)
        agreement.init(private)
        agreement.doPhase(public, true)
        return agreement.generateSecret()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
