package com.kdgames.wake_on_lan.data.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hmac {
    private const val ALGORITHM = "HmacSHA256"
    const val SHA256_LENGTH = 32

    fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        // SecretKeySpec boş anahtarı kabul etmez. HMAC anahtarı blok boyuna sıfırla doldurduğu için
        // boş anahtar, sıfırlardan oluşan anahtarla aynı sonucu verir (RFC 2104).
        val keyBytes = if (key.isEmpty()) ByteArray(SHA256_LENGTH) else key
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(keyBytes, ALGORITHM))
        return mac.doFinal(data)
    }
}
