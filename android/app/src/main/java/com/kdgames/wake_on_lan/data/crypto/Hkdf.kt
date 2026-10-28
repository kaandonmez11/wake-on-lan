package com.kdgames.wake_on_lan.data.crypto

import java.io.ByteArrayOutputStream

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = Hmac.sha256(salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * Hmac.SHA256_LENGTH) { "HKDF uzunluğu geçersiz: $length" }
        val out = ByteArrayOutputStream(length)
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = Hmac.sha256(prk, block + info + counter.toByte())
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}
