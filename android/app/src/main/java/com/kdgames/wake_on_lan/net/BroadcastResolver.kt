package com.kdgames.wake_on_lan.net

/** Alt ağ broadcast adresi (spec 7.2). /31 ve /32'de broadcast yoktur → açık hata. */
object BroadcastResolver {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** @throws IllegalArgumentException geçersiz IPv4 ya da 0..30 dışındaki prefix için. */
    fun compute(ip: String, prefixLength: Int): String {
        require(prefixLength in 0..30) { "Bu prefix için broadcast yok: /$prefixLength" }
        val value = parse(ip)
        val hostMask = if (prefixLength == 0) -1 else (1 shl (32 - prefixLength)) - 1
        return format(value or hostMask)
    }

    fun parse(ip: String): Int {
        require(IPV4.matches(ip)) { "Geçersiz IPv4: $ip" }
        return ip.split('.').fold(0) { acc, part ->
            val octet = part.toInt()
            require(octet in 0..255) { "Geçersiz IPv4: $ip" }
            (acc shl 8) or octet
        }
    }

    fun format(value: Int): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((value ushr it) and 0xFF).toString() }
}
