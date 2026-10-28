package com.kdgames.wake_on_lan.net

/** MAC doğrulama ve normalizasyonu (spec bölüm 15). Kayıtlı biçim: "AA:BB:CC:DD:EE:FF". */
object MacAddress {
    private val PATTERN = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    fun isValid(input: String): Boolean = PATTERN.matches(input.trim())

    /** Geçersizse null. */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (!PATTERN.matches(trimmed)) return null
        return trimmed.uppercase().replace('-', ':')
    }

    fun toBytes(mac: String): ByteArray {
        val normalized = requireNotNull(normalize(mac)) { "Geçersiz MAC: $mac" }
        return normalized.split(':').map { it.toInt(16).toByte() }.toByteArray()
    }
}
